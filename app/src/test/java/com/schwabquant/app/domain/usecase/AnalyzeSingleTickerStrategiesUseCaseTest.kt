package com.schwabquant.app.domain.usecase

import com.schwabquant.app.core.handoff.OptimizerDeepAnalysisFormatter
import com.schwabquant.app.core.quant.ParsedOptionChain
import com.schwabquant.app.core.quant.SchwabOrderPayloadBuilder
import com.schwabquant.app.data.repository.DemoOptionChainProvider
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.MarketRegime
import com.schwabquant.app.domain.model.OptimizerRegimeFit
import com.schwabquant.app.domain.model.OptimizerRiskProfile
import com.schwabquant.app.domain.model.OptimizerStrategyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AnalyzeSingleTickerStrategiesUseCaseTest {

    private val useCase = AnalyzeSingleTickerStrategiesUseCase()
    private val demoProvider = DemoOptionChainProvider()
    private val payloadBuilder = SchwabOrderPayloadBuilder()
    private val deepFormatter = OptimizerDeepAnalysisFormatter()

    @Test
    fun `always outputs all 7 strategies strictly ranked 1 to 7 with exact 4-pillar sums and valid Schwab payloads`() {
        val spyDemo = demoProvider.buildDemoData("SPY")
        val report = useCase.analyze(
            chain = spyDemo.chain,
            activeRegime = MarketRegime.RISK_ON_BULL,
            volRank52w = spyDemo.volRank.volRank,
            sma50 = spyDemo.sma50,
            maxCapitalBudget = null,
            riskProfile = OptimizerRiskProfile.BALANCED,
            isDemoData = true
        )

        assertEquals(7, report.rankedStrategies.size)
        assertEquals(
            OptimizerStrategyType.entries.toSet(),
            report.rankedStrategies.map { it.strategyType }.toSet()
        )
        assertEquals((1..7).toList(), report.rankedStrategies.map { it.rank })

        // Valid setups must appear before unavailable ones and stay descending by compositeScore
        var seenUnavailable = false
        var prevScore = Double.POSITIVE_INFINITY
        report.rankedStrategies.forEach { res ->
            assertTrue(res.rankingExplanation.isNotBlank())
            val b = res.breakdown
            val pillarSum = b.deltaDteScore + b.structuralEdgeScore + b.liquidityScore + b.regimeCapitalScore
            assertEquals(pillarSum, res.compositeScore, 0.05)

            val cand = res.optimalCandidate
            if (cand == null) {
                seenUnavailable = true
                assertEquals(0.0, res.compositeScore, 1e-6)
                assertFalseOrNullCheck(res.noValidSetupReason)
            } else {
                assertTrue(!seenUnavailable, "Valid candidate cannot appear after an unavailable strategy")
                assertTrue(res.compositeScore <= prevScore + 1e-9)
                assertTrue(cand.legRationaleBullets.size == 3)
                prevScore = res.compositeScore

                // Schwab Order Payload & Live Ticket compatibility
                val ticket = cand.toOrderDraft().toOrderTicket("HASH123", ExecutionMode.LIVE)
                payloadBuilder.build(ticket)
            }
        }

        // Deep Analysis Markdown verifies all 7 ranks + explanations are included
        val packet = deepFormatter.formatFullSevenStrategyPacket(report, 2.15, 0.37)
        OptimizerStrategyType.entries.forEach { type ->
            assertTrue(packet.contains(type.displayName), "Packet missing ${type.displayName}")
        }
        (1..7).forEach { rank ->
            assertTrue(packet.contains("#$rank"), "Packet missing #$rank")
        }
        report.rankedStrategies.first().let { first ->
            val singlePacket = deepFormatter.formatSingleStrategyPacket(first, report, 2.15, 0.37)
            assertTrue(singlePacket.contains(first.strategyType.displayName))
            assertTrue(singlePacket.contains(first.rankingExplanation))
        }
    }

    @Test
    fun `shifting regime RISK_ON_BULL to CONFIRMED_BEAR boosts PMCP and BEAR_CALL_VERTICAL and updates explanation`() {
        val nvdaDemo = demoProvider.buildDemoData("NVDA")
        val bullReport = useCase.analyze(
            chain = nvdaDemo.chain,
            activeRegime = MarketRegime.RISK_ON_BULL,
            volRank52w = 55.0,
            sma50 = nvdaDemo.sma50,
            maxCapitalBudget = null,
            riskProfile = OptimizerRiskProfile.BALANCED
        )
        val bearReport = useCase.analyze(
            chain = nvdaDemo.chain,
            activeRegime = MarketRegime.CONFIRMED_BEAR,
            volRank52w = 55.0,
            sma50 = nvdaDemo.sma50,
            maxCapitalBudget = null,
            riskProfile = OptimizerRiskProfile.BALANCED
        )

        val bullBearCall = bullReport.rankedStrategies.first { it.strategyType == OptimizerStrategyType.BEAR_CALL_VERTICAL }
        val bearBearCall = bearReport.rankedStrategies.first { it.strategyType == OptimizerStrategyType.BEAR_CALL_VERTICAL }
        assertEquals(OptimizerRegimeFit.COUNTER_REGIME_CAUTION, bullBearCall.regimeFit)
        assertEquals(OptimizerRegimeFit.ALIGNED, bearBearCall.regimeFit)
        assertTrue(bearBearCall.compositeScore > bullBearCall.compositeScore)
        assertNotEquals(bullBearCall.rankingExplanation, bearBearCall.rankingExplanation)

        val bullPmcp = bullReport.rankedStrategies.first { it.strategyType == OptimizerStrategyType.PMCP }
        val bearPmcp = bearReport.rankedStrategies.first { it.strategyType == OptimizerStrategyType.PMCP }
        assertEquals(OptimizerRegimeFit.COUNTER_REGIME_CAUTION, bullPmcp.regimeFit)
        assertEquals(OptimizerRegimeFit.ALIGNED, bearPmcp.regimeFit)
        assertTrue(bearPmcp.compositeScore > bullPmcp.compositeScore)
    }

    @Test
    fun `riskProfile shifts short deltas while tight capital cap and illiquidity preserve all 7 rows with exclusion reasons`() {
        val qqqDemo = demoProvider.buildDemoData("QQQ")
        val conservative = useCase.analyze(
            chain = qqqDemo.chain,
            activeRegime = MarketRegime.CAUTION_ELEVATED_VOL,
            volRank52w = 60.0,
            sma50 = qqqDemo.sma50,
            maxCapitalBudget = null,
            riskProfile = OptimizerRiskProfile.CONSERVATIVE
        )
        val aggressive = useCase.analyze(
            chain = qqqDemo.chain,
            activeRegime = MarketRegime.CAUTION_ELEVATED_VOL,
            volRank52w = 60.0,
            sma50 = qqqDemo.sma50,
            maxCapitalBudget = null,
            riskProfile = OptimizerRiskProfile.AGGRESSIVE
        )
        val consBullPutDelta = abs(
            conservative.rankedStrategies.first { it.strategyType == OptimizerStrategyType.BULL_PUT_VERTICAL }
                .optimalCandidate!!.legs.first().delta
        )
        val aggBullPutDelta = abs(
            aggressive.rankedStrategies.first { it.strategyType == OptimizerStrategyType.BULL_PUT_VERTICAL }
                .optimalCandidate!!.legs.first().delta
        )
        assertTrue(aggBullPutDelta >= consBullPutDelta)

        // Tiny capital budget filters out high-BPR Wheel CSP while keeping all 7 rows
        val capped = useCase.analyze(
            chain = qqqDemo.chain,
            activeRegime = MarketRegime.RISK_ON_BULL,
            volRank52w = 45.0,
            sma50 = qqqDemo.sma50,
            maxCapitalBudget = 400.0
        )
        assertEquals(7, capped.rankedStrategies.size)
        val cappedWheel = capped.rankedStrategies.first { it.strategyType == OptimizerStrategyType.WHEEL_CSP }
        assertNull(cappedWheel.optimalCandidate)
        assertNotNull(cappedWheel.noValidSetupReason)
        assertTrue(cappedWheel.noValidSetupReason!!.contains("400"))

        // Illiquid chain (bid == 0) yields 7 unavailable strategies cleanly
        val illiquidChain = ParsedOptionChain(
            symbol = "ILLQ",
            underlyingPrice = 100.0,
            chainVolatility = 0.25,
            riskFreeRate = 0.045,
            contracts = qqqDemo.chain.contracts.map { it.copy(bid = 0.0, openInterest = 5) },
            atm30DayIv = 0.25,
            fetchedAtEpochMs = 1_000L
        )
        val illiquidReport = useCase.analyze(illiquidChain, MarketRegime.RISK_ON_BULL)
        assertEquals(7, illiquidReport.rankedStrategies.size)
        assertTrue(illiquidReport.rankedStrategies.all { it.optimalCandidate == null && it.compositeScore == 0.0 })
    }

    private fun assertFalseOrNullCheck(reason: String?) {
        assertTrue(!reason.isNullOrBlank())
    }
}
