package com.schwabquant.app.core.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.sqrt

class VolRankCalculatorTest {

    private val calculator = VolRankCalculator()

    @Test
    fun `computes rolling 20 day annualized HV range and clamps vol rank between 0 and 100`() {
        // First ~120 bars calm (+/- 0.5% daily), middle ~60 bars volatile (+/- 2.5% daily), end calm
        val closes = ArrayList<Double>(253)
        var price = 100.0
        closes.add(price)
        for (i in 1..252) {
            val dailyStep = when {
                i in 120..179 -> if (i % 2 == 0) 0.025 else -0.024
                else -> if (i % 2 == 0) 0.005 else -0.0049
            }
            price *= exp(dailyStep)
            closes.add(price)
        }

        val lowSample = calculator.computeVolRank(closes, liveAtmIv30 = 0.02)
        assertTrue(lowSample.isValid)
        assertEquals("Vol Rank (52w)", lowSample.label)
        assertEquals(0.0, lowSample.volRank, 1e-6)
        assertTrue(lowSample.hv52wLow < lowSample.hv52wHigh)

        val highSample = calculator.computeVolRank(closes, liveAtmIv30 = 0.90)
        assertEquals(100.0, highSample.volRank, 1e-6)

        val midIv = (lowSample.hv52wLow + lowSample.hv52wHigh) * 0.5
        val midSample = calculator.computeVolRank(closes, liveAtmIv30 = midIv)
        assertEquals(50.0, midSample.volRank, 1e-4)

        // Annualized HV20 on +/-0.5% alternating daily log returns (~0.00495 std * sqrt(252) ~ 0.0785)
        assertTrue(lowSample.hv52wLow > 0.05 && lowSample.hv52wLow < 0.12)
        assertTrue(lowSample.hv52wHigh > 0.30)
        assertEquals(lowSample.hv52wHigh, (0.0245 * sqrt(252.0)), 0.05)
    }

    @Test
    fun `flat prices or insufficient history returns neutral 50`() {
        val flatCloses = List(100) { 100.0 }
        val flatResult = calculator.computeVolRank(flatCloses, liveAtmIv30 = 0.25)
        assertTrue(flatResult.isValid)
        assertEquals(50.0, flatResult.volRank, 1e-6)

        val shortResult = calculator.computeVolRank(listOf(100.0, 101.0, 102.0), liveAtmIv30 = 0.25)
        assertFalse(shortResult.isValid)
        assertEquals(50.0, shortResult.volRank, 1e-6)
    }
}
