package com.schwabquant.app.core.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChartIndicatorCalculatorTest {

    private val calculator = ChartIndicatorCalculator()

    @Test
    fun `SMA EMA and Wilder RMA match exact mathematical recurrences`() {
        val candles = listOf(10.0, 12.0, 14.0, 16.0, 18.0).mapIndexed { idx, close ->
            ChartCandle(
                epochSeconds = (idx + 1) * 60L,
                open = close,
                high = close,
                low = close,
                close = close,
                volume = 100L
            )
        }

        val sma3 = calculator.computeSma(candles, 3)
        assertEquals(3, sma3.size)
        assertEquals(12.0, sma3[0].value, 1e-6)
        assertEquals(14.0, sma3[1].value, 1e-6)
        assertEquals(16.0, sma3[2].value, 1e-6)

        val ema3 = calculator.computeEma(candles, 3)
        assertEquals(3, ema3.size)
        assertEquals(12.0, ema3[0].value, 1e-6)
        // k = 2/(3+1) = 0.5 -> ema[1] = 16*0.5 + 12*0.5 = 14.0; ema[2] = 18*0.5 + 14*0.5 = 16.0
        assertEquals(14.0, ema3[1].value, 1e-6)
        assertEquals(16.0, ema3[2].value, 1e-6)

        val rma3 = calculator.computeWilderRma(candles, 3)
        assertEquals(3, rma3.size)
        assertEquals(12.0, rma3[0].value, 1e-6)
        // RMA_1 = (12 * 2 + 16) / 3 = 13.3333333
        val expectedRma1 = (12.0 * 2.0 + 16.0) / 3.0
        assertEquals(expectedRma1, rma3[1].value, 1e-6)
        // RMA_2 = (RMA_1 * 2 + 18) / 3
        val expectedRma2 = (expectedRma1 * 2.0 + 18.0) / 3.0
        assertEquals(expectedRma2, rma3[2].value, 1e-6)
    }

    @Test
    fun `session VWAP resets cumulatives on new America New_York trading date`() {
        val ny = ZoneId.of("America/New_York")
        val day1Bar1 = ChartCandle(
            epochSeconds = ZonedDateTime.of(2026, 11, 18, 10, 0, 0, 0, ny).toEpochSecond(),
            open = 100.0, high = 102.0, low = 98.0, close = 100.0, volume = 1_000L // typical = 100
        )
        val day1Bar2 = ChartCandle(
            epochSeconds = ZonedDateTime.of(2026, 11, 18, 10, 30, 0, 0, ny).toEpochSecond(),
            open = 108.0, high = 112.0, low = 108.0, close = 110.0, volume = 1_000L // typical = 110
        )
        val day2Bar1 = ChartCandle(
            epochSeconds = ZonedDateTime.of(2026, 11, 19, 9, 30, 0, 0, ny).toEpochSecond(),
            open = 200.0, high = 200.0, low = 200.0, close = 200.0, volume = 500L // reset -> typical = 200
        )

        val vwap = calculator.computeSessionVwap(listOf(day1Bar1, day1Bar2, day2Bar1), ny)
        assertEquals(3, vwap.size)
        assertEquals(100.0, vwap[0].value, 1e-6)
        assertEquals(105.0, vwap[1].value, 1e-6)
        assertEquals(200.0, vwap[2].value, 1e-6)
    }

    @Test
    fun `RSI14 and RSI2 stay strictly bounded within 0 to 100 across extremes`() {
        val upTrend = (1..20).map { i ->
            ChartCandle(i * 60L, i.toDouble(), i.toDouble(), i.toDouble(), i.toDouble(), 100L)
        }
        val downTrend = (20 downTo 1).mapIndexed { idx, p ->
            ChartCandle(idx * 60L, p.toDouble(), p.toDouble(), p.toDouble(), p.toDouble(), 100L)
        }
        val mixed = listOf(100.0, 103.0, 101.0, 104.0, 102.0, 106.0, 105.0, 108.0, 104.0, 107.0, 109.0, 106.0, 110.0, 111.0, 108.0, 112.0, 113.0)
            .mapIndexed { idx, p -> ChartCandle(idx * 60L, p, p + 1, p - 1, p, 500L) }

        val rsiUp14 = calculator.computeRsi(upTrend, 14)
        assertTrue(rsiUp14.isNotEmpty())
        assertTrue(rsiUp14.all { it.value in 0.0..100.0 })
        assertEquals(100.0, rsiUp14.last().value, 1e-6)

        val rsiDown2 = calculator.computeRsi(downTrend, 2)
        assertTrue(rsiDown2.isNotEmpty())
        assertTrue(rsiDown2.all { it.value in 0.0..100.0 })
        assertEquals(0.0, rsiDown2.last().value, 1e-6)

        val rsiMixed2 = calculator.computeRsi(mixed, 2)
        val rsiMixed14 = calculator.computeRsi(mixed, 14)
        assertTrue(rsiMixed2.all { it.value in 0.0..100.0 })
        assertTrue(rsiMixed14.all { it.value in 0.0..100.0 })
    }
}
