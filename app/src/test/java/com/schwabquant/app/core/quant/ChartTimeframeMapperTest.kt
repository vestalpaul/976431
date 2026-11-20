package com.schwabquant.app.core.quant

import com.schwabquant.app.data.remote.dto.CandleDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChartTimeframeMapperTest {

    @Test
    fun `maps all 7 timeframes accurately and never sends frequency 60 for H1`() {
        val q1m = ChartTimeframeMapper.toSchwabQuery("spy", ChartTimeframe.M1)
        assertEquals("SPY", q1m.apiSymbol)
        assertEquals("day", q1m.periodType)
        assertEquals(1, q1m.period)
        assertEquals("minute", q1m.frequencyType)
        assertEquals(1, q1m.frequency)
        assertTrue(q1m.needExtendedHoursData)

        val q5m = ChartTimeframeMapper.toSchwabQuery("BRK.B", ChartTimeframe.M5)
        assertEquals("BRK/B", q5m.apiSymbol)
        assertEquals(5, q5m.period)
        assertEquals(5, q5m.frequency)
        assertTrue(q5m.needExtendedHoursData)

        val q15m = ChartTimeframeMapper.toSchwabQuery("QQQ", ChartTimeframe.M15)
        assertEquals(10, q15m.period)
        assertEquals(15, q15m.frequency)

        val q30m = ChartTimeframeMapper.toSchwabQuery("IWM", ChartTimeframe.M30)
        assertEquals(10, q30m.period)
        assertEquals(30, q30m.frequency)

        val q1h = ChartTimeframeMapper.toSchwabQuery("brk.b", ChartTimeframe.H1)
        assertEquals("BRK/B", q1h.apiSymbol)
        assertEquals("day", q1h.periodType)
        assertEquals(10, q1h.period)
        assertEquals("minute", q1h.frequencyType)
        assertEquals(30, q1h.frequency)
        assertTrue(q1h.frequency != 60)
        assertTrue(q1h.needExtendedHoursData)

        val q1d = ChartTimeframeMapper.toSchwabQuery("AAPL", ChartTimeframe.D1)
        assertEquals("year", q1d.periodType)
        assertEquals(1, q1d.period)
        assertEquals("daily", q1d.frequencyType)
        assertEquals(1, q1d.frequency)
        assertFalse(q1d.needExtendedHoursData)

        val q1w = ChartTimeframeMapper.toSchwabQuery("MSFT", ChartTimeframe.W1)
        assertEquals("year", q1w.periodType)
        assertEquals(5, q1w.period)
        assertEquals("weekly", q1w.frequencyType)
        assertEquals(1, q1w.frequency)
        assertFalse(q1w.needExtendedHoursData)
    }

    @Test
    fun `normalizeCandles deduplicates sorts ascending and clamps high low bounds`() {
        val raw = listOf(
            CandleDto(open = 102.0, high = 101.0, low = 103.0, close = 105.0, volume = 500.0, datetime = 1_700_002_000_000L),
            CandleDto(open = 100.0, high = 101.5, low = 99.5, close = 101.0, volume = 200.0, datetime = 1_700_001_000_000L),
            CandleDto(open = 102.0, high = 106.0, low = 101.0, close = 104.5, volume = 800.0, datetime = 1_700_002_000_000L),
            CandleDto(open = -1.0, high = 10.0, low = 5.0, close = 9.0, volume = 100.0, datetime = 1_700_003_000_000L)
        )
        val normalized = ChartTimeframeMapper.normalizeCandles(raw)
        assertEquals(2, normalized.size)
        assertEquals(1_700_001_000L, normalized[0].epochSeconds)
        assertEquals(1_700_002_000L, normalized[1].epochSeconds)
        assertEquals(104.5, normalized[1].close, 0.001)
        assertEquals(800L, normalized[1].volume)
    }

    @Test
    fun `aggregateToHourlyCandles combines 30m bars into America New_York hourly OHLCV buckets`() {
        val ny = ZoneId.of("America/New_York")
        val t1 = ZonedDateTime.of(2026, 11, 19, 9, 30, 0, 0, ny).toEpochSecond()
        val t2 = ZonedDateTime.of(2026, 11, 19, 10, 0, 0, 0, ny).toEpochSecond()
        val t3 = ZonedDateTime.of(2026, 11, 19, 10, 30, 0, 0, ny).toEpochSecond()

        val bars = listOf(
            ChartCandle(epochSeconds = t3, open = 104.0, high = 108.0, low = 103.0, close = 107.5, volume = 3_000L),
            ChartCandle(epochSeconds = t1, open = 100.0, high = 102.0, low = 99.0, close = 101.0, volume = 1_000L),
            ChartCandle(epochSeconds = t2, open = 101.0, high = 105.0, low = 100.5, close = 104.0, volume = 2_500L)
        )

        val hourly = ChartTimeframeMapper.aggregateToHourlyCandles(bars, ny)
        assertEquals(2, hourly.size)

        val hour9 = hourly[0]
        assertEquals(ZonedDateTime.of(2026, 11, 19, 9, 0, 0, 0, ny).toEpochSecond(), hour9.epochSeconds)
        assertEquals(100.0, hour9.open, 0.001)
        assertEquals(102.0, hour9.high, 0.001)
        assertEquals(99.0, hour9.low, 0.001)
        assertEquals(101.0, hour9.close, 0.001)
        assertEquals(1_000L, hour9.volume)

        val hour10 = hourly[1]
        assertEquals(ZonedDateTime.of(2026, 11, 19, 10, 0, 0, 0, ny).toEpochSecond(), hour10.epochSeconds)
        assertEquals(101.0, hour10.open, 0.001)
        assertEquals(108.0, hour10.high, 0.001)
        assertEquals(100.5, hour10.low, 0.001)
        assertEquals(107.5, hour10.close, 0.001)
        assertEquals(5_500L, hour10.volume)
    }
}
