package com.schwabquant.app.domain.usecase

import com.schwabquant.app.core.quant.BlackScholesEngine
import com.schwabquant.app.core.quant.OccSymbolFormatter
import com.schwabquant.app.core.quant.ParsedOptionChain
import com.schwabquant.app.core.quant.ParsedOptionContract
import com.schwabquant.app.core.quant.VolRankResult
import com.schwabquant.app.data.repository.DemoOptionChainProvider
import com.schwabquant.app.domain.model.DemoRegimePreset
import com.schwabquant.app.domain.model.OptionStrategyType
import com.schwabquant.app.domain.model.StrategyFilterChip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ScanOptionsStrategiesUseCaseTest {

    private val bs = BlackScholesEngine()
    private val occ = OccSymbolFormatter()
    private val demoProvider = DemoOptionChainProvider()
    private val scanner = ScanOptionsStrategiesUseCase()
    private val regimeEvaluator = EvaluateBearChecklistUseCase()

    @Test
    fun `scans all 6 option strategy families on synthetic IWM Black-Scholes chain`() {
        val data = demoProvider.buildDemoData("IWM")
        val cautionRegime = regimeEvaluator.evaluateDemo(DemoRegimePreset.CAUTION)

        val candidates = scanner.scanChain(
            chain = data.chain,
            volRank = data.volRank,
            sma50 = data.sma50,
            regimeEvaluation = cautionRegime,
            maxCapitalDollars = null,
            selectedFilters = setOf(StrategyFilterChip.ALL),
            isDemoData = true
        )

        val foundTypes = candidates.map { it.strategyType }.toSet()
        assertTrue(OptionStrategyType.PMCC in foundTypes, "Expected PMCC candidate")
        assertTrue(OptionStrategyType.PMCP in foundTypes, "Expected PMCP candidate")
        assertTrue(
            OptionStrategyType.VERTICAL_BULL_PUT in foundTypes || OptionStrategyType.VERTICAL_BEAR_CALL in foundTypes,
            "Expected Vertical Credit candidate"
        )
        assertTrue(OptionStrategyType.CALENDAR in foundTypes, "Expected Calendar candidate")
        assertTrue(OptionStrategyType.IRON_CONDOR in foundTypes, "Expected Iron Condor candidate")
        assertTrue(OptionStrategyType.WHEEL_CSP in foundTypes, "Expected Wheel/CSP candidate")

        candidates.forEach { c ->
            assertTrue(c.compositeScore in 0..100)
            assertTrue(c.metrics.buyingPowerRequirement > 0.0)
            assertTrue(c.explainer.whyItWorksNow.isNotBlank())
            assertTrue(c.explainer.failureModesAndRules.isNotEmpty())
        }
    }

    @Test
    fun `capital filter enforces strict BPR ceiling`() {
        val data = demoProvider.buildDemoData("SPY")
        val bullRegime = regimeEvaluator.evaluateDemo(DemoRegimePreset.BULL)

        val under5k = scanner.scanChain(
            chain = data.chain,
            volRank = data.volRank,
            sma50 = data.sma50,
            regimeEvaluation = bullRegime,
            maxCapitalDollars = 5_000.0,
            selectedFilters = setOf(StrategyFilterChip.ALL)
        )
        val unfiltered = scanner.scanChain(
            chain = data.chain,
            volRank = data.volRank,
            sma50 = data.sma50,
            regimeEvaluation = bullRegime,
            maxCapitalDollars = null,
            selectedFilters = setOf(StrategyFilterChip.ALL)
        )

        assertTrue(under5k.isNotEmpty())
        assertTrue(under5k.all { it.metrics.buyingPowerRequirement <= 5_000.0 })
        // SPY CSP at ~550 strike costs ~$54,000 collateral so it must appear unfiltered and vanish under $5k
        assertTrue(unfiltered.any { it.strategyType == OptionStrategyType.WHEEL_CSP })
        assertTrue(under5k.none { it.strategyType == OptionStrategyType.WHEEL_CSP })
    }

    @Test
    fun `pmcc penalizes debit over 100 pct width and rewards backwardation while bear regime boosts pmcp`() {
        val bull = regimeEvaluator.evaluateDemo(DemoRegimePreset.BULL)
        val bear = regimeEvaluator.evaluateDemo(DemoRegimePreset.BEAR)
        val data = demoProvider.buildDemoData("IWM")

        val bullRanked = scanner.scanChain(data.chain, data.volRank, data.sma50, bull, null)
        val bearRanked = scanner.scanChain(data.chain, data.volRank, data.sma50, bear, null)

        val bullPmcc = bullRanked.first { it.strategyType == OptionStrategyType.PMCC }
        val bullPmcp = bullRanked.first { it.strategyType == OptionStrategyType.PMCP }
        val bearPmcc = bearRanked.first { it.strategyType == OptionStrategyType.PMCC }
        val bearPmcp = bearRanked.first { it.strategyType == OptionStrategyType.PMCP }
        val bearCall = bearRanked.first { it.strategyType == OptionStrategyType.VERTICAL_BEAR_CALL }

        assertTrue(bullPmcc.compositeScore > bullPmcp.compositeScore, "Bull should favor PMCC over PMCP")
        assertTrue(bearPmcp.compositeScore > bearPmcc.compositeScore, "Bear should rank PMCP above PMCC")
        assertTrue(bearCall.compositeScore > bearPmcc.compositeScore, "Bear should boost Bear Call above PMCC")
        assertTrue((bullPmcc.metrics.widthRatioPct ?: 200.0) < 100.0, "Healthy PMCC has debit < 100% width")
        assertTrue((bearPmcp.metrics.widthRatioPct ?: 200.0) < 85.0, "Healthy PMCP targets debit < 85% width")

        // Now construct an explicit overpriced PMCC where netDebit >= strikeWidth (100%+) vs healthy PMCC
        val badPmccChain = buildOverpricedPmccChain()
        val badScan = scanner.scanChain(
            chain = badPmccChain,
            volRank = VolRankResult(45.0, hv52wLow = 0.15, hv52wHigh = 0.35, currentHv20 = 0.22, referenceIv = 0.24, isValid = true),
            sma50 = 100.0,
            regimeEvaluation = bull,
            maxCapitalDollars = null,
            selectedFilters = setOf(StrategyFilterChip.PMCC)
        )
        val badPmcc = badScan.firstOrNull()
        assertNotNull(badPmcc)
        assertTrue((badPmcc!!.metrics.widthRatioPct ?: 0.0) >= 100.0)
        assertTrue(badPmcc.explainer.warnings.any { it.contains("100%", ignoreCase = true) })
        assertTrue(bullPmcc.compositeScore > badPmcc.compositeScore)
    }

    @Test
    fun `calendar backwardation boosts score compared to contango`() {
        val caution = regimeEvaluator.evaluateDemo(DemoRegimePreset.CAUTION)
        val volRank = VolRankResult(55.0, hv52wLow = 0.16, hv52wHigh = 0.36, currentHv20 = 0.24, referenceIv = 0.27, isValid = true)

        val backwChain = buildCalendarTestChain(frontIv = 0.32, backIv = 0.22)
        val contangoChain = buildCalendarTestChain(frontIv = 0.20, backIv = 0.28)

        val backwCal = scanner.scanChain(backwChain, volRank, 100.0, caution, null, setOf(StrategyFilterChip.CALENDARS)).first()
        val contCal = scanner.scanChain(contangoChain, volRank, 100.0, caution, null, setOf(StrategyFilterChip.CALENDARS)).first()

        assertTrue((backwCal.metrics.ivTermSpread ?: 0.0) > 0.0)
        assertTrue((contCal.metrics.ivTermSpread ?: 0.0) < 0.0)
        assertTrue(backwCal.compositeScore > contCal.compositeScore)
        assertTrue(contCal.explainer.warnings.any { it.contains("Contango", ignoreCase = true) })
    }

    private fun buildOverpricedPmccChain(): ParsedOptionChain {
        val asOf = LocalDate.of(2026, 11, 19)
        val longExp = asOf.plusDays(300)
        val shortExp = asOf.plusDays(35)
        val contracts = listOf(
            ParsedOptionContract(
                symbol = occ.format("XYZ", longExp, true, BigDecimal("85.00")),
                isCall = true,
                strike = 85.0,
                expirationDate = longExp,
                dte = 300,
                bid = 21.8,
                ask = 22.2,
                mid = 22.0,
                last = 22.0,
                openInterest = 400,
                volume = 50,
                iv = 0.28,
                delta = 0.84,
                gamma = 0.01,
                thetaPerDay = -0.02,
                vegaPer1Pct = 0.20,
                rhoPer1Pct = 0.50,
                intrinsicValue = 15.0,
                extrinsicValue = 7.0,
                spreadWidthPct = 0.018,
                greeksFromBlackScholesFallback = false
            ),
            ParsedOptionContract(
                symbol = occ.format("XYZ", shortExp, true, BigDecimal("104.00")),
                isCall = true,
                strike = 104.0,
                expirationDate = shortExp,
                dte = 35,
                bid = 1.75,
                ask = 1.85,
                mid = 1.80,
                last = 1.80,
                openInterest = 600,
                volume = 120,
                iv = 0.24,
                delta = 0.28,
                gamma = 0.03,
                thetaPerDay = -0.05,
                vegaPer1Pct = 0.10,
                rhoPer1Pct = 0.05,
                intrinsicValue = 0.0,
                extrinsicValue = 1.80,
                spreadWidthPct = 0.055,
                greeksFromBlackScholesFallback = false
            )
        )
        // netDebit = 22.0 - 1.80 = 20.20 vs width = 104 - 85 = 19.0 -> 106.3% >= 100%
        return ParsedOptionChain(
            symbol = "XYZ",
            underlyingPrice = 100.0,
            chainVolatility = 0.25,
            riskFreeRate = 0.045,
            contracts = contracts,
            atm30DayIv = 0.24,
            fetchedAtEpochMs = 1_000L
        )
    }

    private fun buildCalendarTestChain(frontIv: Double, backIv: Double): ParsedOptionChain {
        val asOf = LocalDate.of(2026, 11, 19)
        val frontExp = asOf.plusDays(28)
        val backExp = asOf.plusDays(75)
        val fg = bs.greeks(true, 100.0, 100.0, 28.0 / 365.0, 0.045, 0.0, frontIv)
        val bg = bs.greeks(true, 100.0, 100.0, 75.0 / 365.0, 0.045, 0.0, backIv)

        val front = ParsedOptionContract(
            symbol = occ.format("SPY", frontExp, true, BigDecimal("100.00")),
            isCall = true,
            strike = 100.0,
            expirationDate = frontExp,
            dte = 28,
            bid = fg.price - 0.05,
            ask = fg.price + 0.05,
            mid = fg.price,
            last = fg.price,
            openInterest = 1200,
            volume = 300,
            iv = frontIv,
            delta = fg.delta,
            gamma = fg.gamma,
            thetaPerDay = fg.thetaPerDay,
            vegaPer1Pct = fg.vegaPer1PctIv,
            rhoPer1Pct = fg.rhoPer1PctRate,
            intrinsicValue = fg.intrinsicValue,
            extrinsicValue = fg.extrinsicValue,
            spreadWidthPct = 0.03,
            greeksFromBlackScholesFallback = false
        )
        val back = ParsedOptionContract(
            symbol = occ.format("SPY", backExp, true, BigDecimal("100.00")),
            isCall = true,
            strike = 100.0,
            expirationDate = backExp,
            dte = 75,
            bid = bg.price - 0.05,
            ask = bg.price + 0.05,
            mid = bg.price,
            last = bg.price,
            openInterest = 900,
            volume = 200,
            iv = backIv,
            delta = bg.delta,
            gamma = bg.gamma,
            thetaPerDay = bg.thetaPerDay,
            vegaPer1Pct = bg.vegaPer1PctIv,
            rhoPer1Pct = bg.rhoPer1PctRate,
            intrinsicValue = bg.intrinsicValue,
            extrinsicValue = bg.extrinsicValue,
            spreadWidthPct = 0.03,
            greeksFromBlackScholesFallback = false
        )
        return ParsedOptionChain(
            symbol = "SPY",
            underlyingPrice = 100.0,
            chainVolatility = frontIv,
            riskFreeRate = 0.045,
            contracts = listOf(front, back),
            atm30DayIv = frontIv,
            fetchedAtEpochMs = 1_000L
        )
    }
}
