package com.schwabquant.app.core.quant

import com.schwabquant.app.domain.model.EarningsCheckStatus
import com.schwabquant.app.domain.model.LegAction
import com.schwabquant.app.domain.model.OptionStrategyType
import com.schwabquant.app.domain.model.QuantCardMetrics
import com.schwabquant.app.domain.model.StrategyLeg
import com.schwabquant.app.domain.usecase.EvaluateBearChecklistUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StrategyExplainerGeneratorTest {

    private val generator = StrategyExplainerGenerator()
    private val regimeEval = EvaluateBearChecklistUseCase().evaluateDemo()

    @Test
    fun `generates non empty deterministic thesis, rules, and Gemini packet for every strategy type`() {
        val sampleLeg = StrategyLeg(
            occSymbol = "IWM   261218C00235000",
            action = LegAction.SELL_TO_OPEN,
            quantity = 1,
            isCall = true,
            strike = 235.0,
            expirationIsoDate = "2026-12-18",
            dte = 35,
            bid = 2.40,
            ask = 2.50,
            mid = 2.45,
            delta = 0.28,
            gamma = 0.02,
            thetaPerDay = -0.05,
            vegaPer1Pct = 0.14,
            impliedVolatility = 0.26,
            openInterest = 1400,
            volume = 350,
            spreadWidthPct = 0.04
        )

        OptionStrategyType.entries.forEach { strategyType ->
            val metrics = QuantCardMetrics(
                netDebitOrCreditPerShare = 3.25,
                isNetCredit = strategyType != OptionStrategyType.PMCC &&
                    strategyType != OptionStrategyType.PMCP &&
                    strategyType != OptionStrategyType.CALENDAR,
                buyingPowerRequirement = 1_500.0,
                maxProfit = 325.0,
                maxLoss = 1_500.0,
                breakevens = listOf(220.0, 240.0),
                widthRatioPct = 32.5,
                widthRatioLabel = "Ratio",
                popPct = 71.0,
                netDelta = -0.15,
                netThetaPerDay = 4.20,
                netGamma = -0.01,
                netVegaPer1Pct = -12.0,
                frontIv = 0.28,
                backIv = 0.23,
                ivTermSpread = 0.05,
                volRank52w = 64.0,
                annualizedRocPct = 24.5,
                distanceBelowSpotPct = 5.5,
                minOpenInterest = 1400,
                maxBidAskSpreadPct = 0.04,
                earningsStatus = EarningsCheckStatus.NO_EARNINGS_ETF,
                earningsDetail = "IWM is an ETF (zero single-stock earnings gap risk)."
            )

            val explainer = generator.generate(
                underlyingSymbol = "IWM",
                underlyingSpot = 224.0,
                strategyType = strategyType,
                legs = listOf(sampleLeg),
                metrics = metrics,
                regimeEvaluation = regimeEval,
                warnings = listOf("Sample guardrail check")
            )

            assertTrue(explainer.whyItWorksNow.isNotBlank(), "Empty thesis for $strategyType")
            assertTrue(explainer.failureModesAndRules.size >= 3, "Missing rules for $strategyType")
            assertTrue(explainer.geminiMarkdownPacket.contains("IWM   261218C00235000"))
            assertTrue(explainer.geminiMarkdownPacket.contains(strategyType.displayName))
            assertTrue(explainer.geminiMarkdownPacket.contains("Quant Card"))
            assertEquals(1, explainer.warnings.size)
        }
    }
}
