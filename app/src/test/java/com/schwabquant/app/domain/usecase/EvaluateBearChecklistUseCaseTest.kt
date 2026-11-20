package com.schwabquant.app.domain.usecase

import com.schwabquant.app.domain.model.BearSignalId
import com.schwabquant.app.domain.model.DemoRegimePreset
import com.schwabquant.app.domain.model.LiquidUniverse
import com.schwabquant.app.domain.model.MarketRegime
import com.schwabquant.app.domain.model.RegimeEvaluationInput
import com.schwabquant.app.domain.model.SignalStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EvaluateBearChecklistUseCaseTest {

    private val useCase = EvaluateBearChecklistUseCase()

    @Test
    fun `risk on bull triggers zero reds when all macro and breadth metrics are healthy`() {
        val eval = useCase.evaluate(healthyInput())

        assertEquals(0, eval.redCount)
        assertEquals(0.0, eval.weightedScore, 1e-6)
        assertEquals(MarketRegime.RISK_ON_BULL, eval.regime)
        eval.signals.forEach { sig ->
            assertEquals(SignalStatus.GREEN, sig.status, "Expected ${sig.id} GREEN")
        }
    }

    @Test
    fun `spy 3 consecutive closes below 50 dma triggers AMBER while above 200 dma`() {
        // First 217 bars at 550.0 (200-DMA ~ 549.6, 50-DMA ~ 549.5); last 3 bars drop to 544.0 (below 50D, above 200D? wait!)
        // Wait: 544 < 549.5 AND 544 < 549.6! So build an uptrending base from 480 to 560 so 200-DMA is ~520 and 50-DMA is ~558,
        // then last 3 closes = 551, 550, 549 (< 50-DMA 558, > 200-DMA 520)!
        val spy = ramp(217, 480.0, 565.0) + listOf(551.0, 550.0, 549.0)
        val input = healthyInput().copy(spyCloses = spy)

        val eval = useCase.evaluate(input)
        val spySig = eval.signals.first { it.id == BearSignalId.PRIMARY_TREND_SPY }
        assertEquals(SignalStatus.AMBER, spySig.status)
        assertEquals(0, eval.redCount)
        assertEquals(0.5, eval.weightedScore, 1e-6)
    }

    @Test
    fun `spy break below 200 dma triggers RED`() {
        val spy = ramp(217, 560.0, 580.0) + listOf(560.0, 555.0, 550.0) // latest 550 < 200D (~570)
        val eval = useCase.evaluate(healthyInput().copy(spyCloses = spy))

        val spySig = eval.signals.first { it.id == BearSignalId.PRIMARY_TREND_SPY }
        assertEquals(SignalStatus.RED, spySig.status)
    }

    @Test
    fun `iwm relative 60d low triggers AMBER while iwm below 200 dma triggers RED`() {
        // Case A: IWM uptrended so still > 200D, but lagged SPY on last bar -> new 60-day IWM/SPY low
        val spySteady = List(220) { 500.0 }
        val iwmFlat = ramp(155, 190.0, 225.0) + List(64) { 225.0 } + listOf(221.0)
        val amberEval = useCase.evaluate(
            healthyInput().copy(spyCloses = spySteady, iwmCloses = iwmFlat)
        )
        assertEquals(
            SignalStatus.AMBER,
            amberEval.signals.first { it.id == BearSignalId.SMALL_CAP_CANARY_IWM }.status
        )

        // Case B: IWM breaks below 200D -> RED
        val iwmRed = List(219) { 225.0 } + listOf(210.0)
        val redEval = useCase.evaluate(healthyInput().copy(iwmCloses = iwmRed))
        assertEquals(
            SignalStatus.RED,
            redEval.signals.first { it.id == BearSignalId.SMALL_CAP_CANARY_IWM }.status
        )
    }

    @Test
    fun `vix above 22 single day spikes stay AMBER and 3 consecutive sessions above 28 trigger RED`() {
        // Single day > 28 (previous two <= 28) -> AMBER
        val singleSpike = useCase.evaluate(
            healthyInput().copy(
                vixCloses = List(217) { 16.0 } + listOf(21.0, 24.0, 29.5),
                vix3mLatest = 35.0 // isolate VIX level without term structure interference
            )
        )
        assertEquals(
            SignalStatus.AMBER,
            singleSpike.signals.first { it.id == BearSignalId.VOLATILITY_LEVEL_VIX }.status
        )

        // Sustained 3 sessions > 28 -> RED
        val sustainedRed = useCase.evaluate(
            healthyInput().copy(
                vixCloses = List(217) { 16.0 } + listOf(28.4, 29.1, 31.0),
                vix3mLatest = 36.0
            )
        )
        assertEquals(
            SignalStatus.RED,
            sustainedRed.signals.first { it.id == BearSignalId.VOLATILITY_LEVEL_VIX }.status
        )
    }

    @Test
    fun `vix term structure and credit stress and breadth thresholds map cleanly to caution and bear`() {
        // Caution case: 2 RED (VIX/VIX3M = 1.07 RED, Breadth = 40% AMBER, Credit < 200D RED) -> CAUTION_ELEVATED_VOL
        val cautionEval = useCase.evaluate(
            healthyInput().copy(
                vixCloses = List(219) { 18.0 } + listOf(23.5), // AMBER
                vix3mLatest = 21.8, // 23.5 / 21.8 = 1.078 -> RED #1
                hygCloses = List(219) { 80.0 } + listOf(76.0), // < 200D ratio -> RED #2
                universeCloses = buildUniverse(aboveCount = 10) // 40% < 45% -> AMBER
            )
        )
        assertEquals(2, cautionEval.redCount)
        assertEquals(2, cautionEval.amberCount)
        assertEquals(3.0, cautionEval.weightedScore, 1e-6)
        assertEquals(MarketRegime.CAUTION_ELEVATED_VOL, cautionEval.regime)

        // Confirmed Bear case: 5 RED triggers -> CONFIRMED_BEAR
        val bearEval = useCase.evaluate(
            RegimeEvaluationInput(
                spyCloses = List(219) { 580.0 } + listOf(530.0), // RED #1
                iwmCloses = List(219) { 222.0 } + listOf(195.0), // RED #2
                vixCloses = List(217) { 18.0 } + listOf(29.0, 30.5, 32.0), // RED #3
                vix3mLatest = 28.0, // ratio 1.143 -> RED #4
                hygCloses = List(219) { 80.0 } + listOf(72.0), // RED #5
                lqdCloses = List(220) { 108.0 },
                universeCloses = buildUniverse(aboveCount = 4) // 16% < 25% -> RED #6
            )
        )
        assertEquals(6, bearEval.redCount)
        assertEquals(MarketRegime.CONFIRMED_BEAR, bearEval.regime)
        assertTrue(bearEval.directive.haltOrAvoidStrategies.any { it.contains("PMCC", ignoreCase = true) })
    }

    @Test
    fun `demo presets evaluate through real logic without contradictory classifications`() {
        val bull = useCase.evaluateDemo(DemoRegimePreset.BULL)
        val caution = useCase.evaluateDemo(DemoRegimePreset.CAUTION)
        val bear = useCase.evaluateDemo(DemoRegimePreset.BEAR)

        assertTrue(bull.isDemoData && caution.isDemoData && bear.isDemoData)
        assertEquals(MarketRegime.RISK_ON_BULL, bull.regime)
        assertEquals(MarketRegime.CAUTION_ELEVATED_VOL, caution.regime)
        assertEquals(MarketRegime.CONFIRMED_BEAR, bear.regime)
    }

    private fun healthyInput(): RegimeEvaluationInput = RegimeEvaluationInput(
        spyCloses = ramp(220, 500.0, 588.0),
        iwmCloses = ramp(220, 190.0, 232.0),
        vixCloses = List(220) { 16.0 },
        vix3mLatest = 19.0, // ratio 0.842 GREEN
        hygCloses = ramp(220, 76.0, 81.0),
        lqdCloses = ramp(220, 108.0, 108.5),
        universeCloses = buildUniverse(aboveCount = 21) // 84% GREEN
    )

    private fun ramp(count: Int, start: Double, end: Double): List<Double> =
        List(count) { i -> start + (end - start) * (i.toDouble() / (count - 1).coerceAtLeast(1)) }

    private fun buildUniverse(aboveCount: Int): Map<String, List<Double>> =
        LiquidUniverse.CORE_25_LIQUID_TICKERS.mapIndexed { idx, sym ->
            val series = if (idx < aboveCount) {
                ramp(60, 100.0, 112.0)
            } else {
                List(59) { 110.0 } + listOf(98.0)
            }
            sym to series
        }.toMap()
}
