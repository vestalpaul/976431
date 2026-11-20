package com.schwabquant.app.core.quant

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class IndicatorPoint(
    val epochSeconds: Long,
    val value: Double
)

data class ChartIndicatorBundle(
    val ema9: List<IndicatorPoint> = emptyList(),
    val ema21: List<IndicatorPoint> = emptyList(),
    val sma50: List<IndicatorPoint> = emptyList(),
    val sma200: List<IndicatorPoint> = emptyList(),
    val rmaFast9: List<IndicatorPoint> = emptyList(),
    val rmaSlow21: List<IndicatorPoint> = emptyList(),
    val sessionVwap: List<IndicatorPoint> = emptyList(),
    val rsi14: List<IndicatorPoint> = emptyList(),
    val rsi2: List<IndicatorPoint> = emptyList()
)

/**
 * Pure-JVM chart technical indicator engine aligned strictly to sorted OHLCV candle timestamps.
 */
@Singleton
class ChartIndicatorCalculator @Inject constructor() {

    fun computeAll(
        candles: List<ChartCandle>,
        includeSessionVwap: Boolean = true,
        rmaFastPeriod: Int = 9,
        rmaSlowPeriod: Int = 21
    ): ChartIndicatorBundle {
        val sorted = candles.sortedBy { it.epochSeconds }
        if (sorted.isEmpty()) return ChartIndicatorBundle()
        return ChartIndicatorBundle(
            ema9 = computeEma(sorted, 9),
            ema21 = computeEma(sorted, 21),
            sma50 = computeSma(sorted, 50),
            sma200 = computeSma(sorted, 200),
            rmaFast9 = computeWilderRma(sorted, rmaFastPeriod),
            rmaSlow21 = computeWilderRma(sorted, rmaSlowPeriod),
            sessionVwap = if (includeSessionVwap) computeSessionVwap(sorted) else emptyList(),
            rsi14 = computeRsi(sorted, 14),
            rsi2 = computeRsi(sorted, 2)
        )
    }

    fun computeSma(candles: List<ChartCandle>, period: Int): List<IndicatorPoint> {
        if (period <= 0 || candles.size < period) return emptyList()
        val result = ArrayList<IndicatorPoint>(candles.size - period + 1)
        var rollingSum = 0.0
        for (i in candles.indices) {
            rollingSum += candles[i].close
            if (i >= period) {
                rollingSum -= candles[i - period].close
            }
            if (i >= period - 1) {
                result.add(IndicatorPoint(candles[i].epochSeconds, rollingSum / period))
            }
        }
        return result
    }

    fun computeEma(candles: List<ChartCandle>, period: Int): List<IndicatorPoint> {
        if (period <= 0 || candles.size < period) return emptyList()
        val result = ArrayList<IndicatorPoint>(candles.size - period + 1)
        var sum = 0.0
        for (i in 0 until period) {
            sum += candles[i].close
        }
        var prevEma = sum / period
        result.add(IndicatorPoint(candles[period - 1].epochSeconds, prevEma))

        val k = 2.0 / (period + 1)
        for (i in period until candles.size) {
            val ema = candles[i].close * k + prevEma * (1.0 - k)
            result.add(IndicatorPoint(candles[i].epochSeconds, ema))
            prevEma = ema
        }
        return result
    }

    /**
     * Wilder's Running Moving Average: `RMA_t = (RMA_{t-1} * (n - 1) + Close_t) / n`.
     */
    fun computeWilderRma(candles: List<ChartCandle>, period: Int): List<IndicatorPoint> {
        if (period <= 0 || candles.size < period) return emptyList()
        val result = ArrayList<IndicatorPoint>(candles.size - period + 1)
        var sum = 0.0
        for (i in 0 until period) {
            sum += candles[i].close
        }
        var prevRma = sum / period
        result.add(IndicatorPoint(candles[period - 1].epochSeconds, prevRma))

        for (i in period until candles.size) {
            val rma = (prevRma * (period - 1) + candles[i].close) / period
            result.add(IndicatorPoint(candles[i].epochSeconds, rma))
            prevRma = rma
        }
        return result
    }

    /**
     * Cumulative intraday Session VWAP resetting at each `America/New_York` calendar date boundary.
     */
    fun computeSessionVwap(
        candles: List<ChartCandle>,
        zoneId: ZoneId = ChartTimeframeMapper.NEW_YORK_ZONE
    ): List<IndicatorPoint> {
        if (candles.isEmpty()) return emptyList()
        val result = ArrayList<IndicatorPoint>(candles.size)
        var currentDate: LocalDate? = null
        var cumPv = 0.0
        var cumVolume = 0L

        for (bar in candles) {
            val barDate = Instant.ofEpochSecond(bar.epochSeconds).atZone(zoneId).toLocalDate()
            if (currentDate != barDate) {
                currentDate = barDate
                cumPv = 0.0
                cumVolume = 0L
            }
            val typical = (bar.high + bar.low + bar.close) / 3.0
            val vol = bar.volume.coerceAtLeast(0L)
            if (vol > 0L) {
                cumPv += typical * vol
                cumVolume += vol
            }
            val vwap = if (cumVolume > 0L) cumPv / cumVolume else typical
            result.add(IndicatorPoint(bar.epochSeconds, vwap))
        }
        return result
    }

    /**
     * Wilder RSI strictly bounded to `[0.0, 100.0]`.
     */
    fun computeRsi(candles: List<ChartCandle>, period: Int): List<IndicatorPoint> {
        if (period <= 0 || candles.size <= period) return emptyList()
        val result = ArrayList<IndicatorPoint>(candles.size - period)

        var gainSum = 0.0
        var lossSum = 0.0
        for (i in 1..period) {
            val delta = candles[i].close - candles[i - 1].close
            if (delta >= 0.0) gainSum += delta else lossSum += -delta
        }

        var avgGain = gainSum / period
        var avgLoss = lossSum / period
        result.add(IndicatorPoint(candles[period].epochSeconds, rsiFromAverages(avgGain, avgLoss)))

        for (i in (period + 1) until candles.size) {
            val delta = candles[i].close - candles[i - 1].close
            val gain = if (delta > 0.0) delta else 0.0
            val loss = if (delta < 0.0) -delta else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            result.add(IndicatorPoint(candles[i].epochSeconds, rsiFromAverages(avgGain, avgLoss)))
        }
        return result
    }

    private fun rsiFromAverages(avgGain: Double, avgLoss: Double): Double {
        if (avgGain <= EPSILON && avgLoss <= EPSILON) return 50.0
        if (avgLoss <= EPSILON) return 100.0
        val rs = avgGain / avgLoss
        return (100.0 - 100.0 / (1.0 + rs)).coerceIn(0.0, 100.0)
    }

    companion object {
        private const val EPSILON = 1e-12
    }
}
