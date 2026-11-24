package com.schwabquant.app.core.quant

import com.schwabquant.app.data.remote.dto.CandleDto
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class ChartTimeframe(
    val label: String,
    val isIntraday: Boolean
) {
    M1("1m", true),
    M5("5m", true),
    M15("15m", true),
    M30("30m", true),
    H1("1H", true),
    D1("1D", false),
    W1("1W", false)
}

data class SchwabPriceHistoryQuery(
    val apiSymbol: String,
    val periodType: String,
    val period: Int,
    val frequencyType: String,
    val frequency: Int,
    val needExtendedHoursData: Boolean
)

data class ChartCandle(
    val epochSeconds: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

/**
 * Pure-JVM mapper between Interactive Chart timeframes and Schwab `/marketdata/v1/pricehistory`
 * parameters per SCHWAB_API_REFERENCE.md (Schwab only allows minute frequencies 1, 5, 10, 15, 30,
 * so `1H` fetches 30m bars and aggregates them cleanly into America/New_York hourly OHLCV candles).
 */
object ChartTimeframeMapper {

    val NEW_YORK_ZONE: ZoneId = ZoneId.of("America/New_York")

    fun toSchwabQuery(
        symbol: String,
        timeframe: ChartTimeframe
    ): SchwabPriceHistoryQuery {
        val apiSymbol = SchwabSymbolNormalizer.toApiSymbol(symbol)
        return when (timeframe) {
            ChartTimeframe.M1 -> SchwabPriceHistoryQuery(
                apiSymbol = apiSymbol,
                periodType = "day",
                period = 1,
                frequencyType = "minute",
                frequency = 1,
                needExtendedHoursData = true
            )
            ChartTimeframe.M5 -> SchwabPriceHistoryQuery(
                apiSymbol = apiSymbol,
                periodType = "day",
                period = 5,
                frequencyType = "minute",
                frequency = 5,
                needExtendedHoursData = true
            )
            ChartTimeframe.M15 -> SchwabPriceHistoryQuery(
                apiSymbol = apiSymbol,
                periodType = "day",
                period = 10,
                frequencyType = "minute",
                frequency = 15,
                needExtendedHoursData = true
            )
            ChartTimeframe.M30 -> SchwabPriceHistoryQuery(
                apiSymbol = apiSymbol,
                periodType = "day",
                period = 10,
                frequencyType = "minute",
                frequency = 30,
                needExtendedHoursData = true
            )
            ChartTimeframe.H1 -> SchwabPriceHistoryQuery(
                apiSymbol = apiSymbol,
                periodType = "day",
                period = 10,
                frequencyType = "minute",
                frequency = 30,
                needExtendedHoursData = true
            )
            ChartTimeframe.D1 -> SchwabPriceHistoryQuery(
                apiSymbol = apiSymbol,
                periodType = "year",
                period = 1,
                frequencyType = "daily",
                frequency = 1,
                needExtendedHoursData = false
            )
            ChartTimeframe.W1 -> SchwabPriceHistoryQuery(
                apiSymbol = apiSymbol,
                periodType = "year",
                period = 5,
                frequencyType = "weekly",
                frequency = 1,
                needExtendedHoursData = false
            )
        }
    }

    fun normalizeCandles(dtos: List<CandleDto>): List<ChartCandle> {
        if (dtos.isEmpty()) return emptyList()
        val byTimestamp = LinkedHashMap<Long, ChartCandle>(dtos.size)
        for (dto in dtos) {
            val rawTime = dto.datetime ?: continue
            if (rawTime <= 0L) continue
            val epochSeconds = if (rawTime > 100_000_000_000L) rawTime / 1_000L else rawTime
            val open = dto.open?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            val close = dto.close?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            val rawHigh = dto.high?.takeIf { it.isFinite() && it > 0.0 } ?: maxOf(open, close)
            val rawLow = dto.low?.takeIf { it.isFinite() && it > 0.0 } ?: minOf(open, close)
            val high = maxOf(rawHigh, rawLow, open, close)
            val low = minOf(rawHigh, rawLow, open, close)
            val volume = dto.volume?.takeIf { it.isFinite() && it >= 0.0 }?.toLong() ?: 0L

            byTimestamp[epochSeconds] = ChartCandle(
                epochSeconds = epochSeconds,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume
            )
        }
        return byTimestamp.values.sortedBy { it.epochSeconds }
    }

    fun aggregateToHourlyCandles(
        thirtyMinuteCandles: List<ChartCandle>,
        zoneId: ZoneId = NEW_YORK_ZONE
    ): List<ChartCandle> {
        if (thirtyMinuteCandles.isEmpty()) return emptyList()
        val sorted = thirtyMinuteCandles
            .associateBy { it.epochSeconds }
            .values
            .sortedBy { it.epochSeconds }

        val buckets = LinkedHashMap<Long, MutableList<ChartCandle>>()
        for (candle in sorted) {
            val hourEpoch = Instant.ofEpochSecond(candle.epochSeconds)
                .atZone(zoneId)
                .truncatedTo(ChronoUnit.HOURS)
                .toEpochSecond()
            buckets.getOrPut(hourEpoch) { mutableListOf() }.add(candle)
        }

        return buckets.entries.map { (bucketEpoch, bars) ->
            var high = Double.NEGATIVE_INFINITY
            var low = Double.POSITIVE_INFINITY
            var volume = 0L
            for (bar in bars) {
                if (bar.high > high) high = bar.high
                if (bar.low < low) low = bar.low
                volume += bar.volume.coerceAtLeast(0L)
            }
            ChartCandle(
                epochSeconds = bucketEpoch,
                open = bars.first().open,
                high = maxOf(high, bars.first().open, bars.last().close),
                low = minOf(low, bars.first().open, bars.last().close),
                close = bars.last().close,
                volume = volume
            )
        }.sortedBy { it.epochSeconds }
    }

    fun alignEpochSecondsToBucket(
        epochSeconds: Long,
        timeframe: ChartTimeframe,
        zoneId: ZoneId = NEW_YORK_ZONE
    ): Long = when (timeframe) {
        ChartTimeframe.M1 -> epochSeconds - Math.floorMod(epochSeconds, 60L)
        ChartTimeframe.M5 -> epochSeconds - Math.floorMod(epochSeconds, 300L)
        ChartTimeframe.M15 -> epochSeconds - Math.floorMod(epochSeconds, 900L)
        ChartTimeframe.M30 -> epochSeconds - Math.floorMod(epochSeconds, 1_800L)
        ChartTimeframe.H1 -> Instant.ofEpochSecond(epochSeconds)
            .atZone(zoneId)
            .truncatedTo(ChronoUnit.HOURS)
            .toEpochSecond()
        ChartTimeframe.D1 -> Instant.ofEpochSecond(epochSeconds)
            .atZone(zoneId)
            .toLocalDate()
            .atStartOfDay(zoneId)
            .toEpochSecond()
        ChartTimeframe.W1 -> Instant.ofEpochSecond(epochSeconds)
            .atZone(zoneId)
            .toLocalDate()
            .with(DayOfWeek.MONDAY)
            .atStartOfDay(zoneId)
            .toEpochSecond()
    }

    fun isSameBucket(
        barEpochSeconds: Long,
        tickEpochSeconds: Long,
        timeframe: ChartTimeframe,
        zoneId: ZoneId = NEW_YORK_ZONE
    ): Boolean =
        alignEpochSecondsToBucket(barEpochSeconds, timeframe, zoneId) ==
            alignEpochSecondsToBucket(tickEpochSeconds, timeframe, zoneId)
}
