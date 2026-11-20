package com.schwabquant.app.data.repository

import com.schwabquant.app.core.quant.ChartCandle
import com.schwabquant.app.core.quant.ChartTimeframe
import com.schwabquant.app.core.quant.ChartTimeframeMapper
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Deterministic offline OHLCV candle generator for every ChartTimeframe so Interactive Webull Charts
 * work seamlessly even without Schwab OAuth credentials.
 */
@Singleton
class DemoChartCandleProvider @Inject constructor(
    private val demoOptionChainProvider: DemoOptionChainProvider
) {

    constructor() : this(DemoOptionChainProvider())

    fun buildDemoCandles(
        symbol: String,
        timeframe: ChartTimeframe,
        nowEpochMs: Long = DEFAULT_AS_OF_EPOCH_MS
    ): List<ChartCandle> {
        val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(symbol)
        val demoData = demoOptionChainProvider.buildDemoData(displaySym, nowEpochMs = nowEpochMs)
        val targetSpot = demoData.chain.underlyingPrice.coerceAtLeast(1.0)
        val atmIv = (demoData.chain.atm30DayIv ?: 0.25).coerceIn(0.12, 0.90)

        if (timeframe == ChartTimeframe.H1) {
            val thirtyMinBars = buildBaseSeries(
                symbol = displaySym,
                targetClose = targetSpot,
                annualIv = atmIv,
                count = 240,
                stepSeconds = 1_800L,
                isIntraday = true
            )
            return ChartTimeframeMapper.aggregateToHourlyCandles(thirtyMinBars)
        }

        val (count, stepSeconds) = when (timeframe) {
            ChartTimeframe.M1 -> 240 to 60L
            ChartTimeframe.M5 -> 240 to 300L
            ChartTimeframe.M15 -> 220 to 900L
            ChartTimeframe.M30 -> 200 to 1_800L
            ChartTimeframe.H1 -> 240 to 1_800L
            ChartTimeframe.D1 -> 252 to 86_400L
            ChartTimeframe.W1 -> 260 to 7L * 86_400L
        }

        return buildBaseSeries(
            symbol = displaySym,
            targetClose = targetSpot,
            annualIv = atmIv,
            count = count,
            stepSeconds = stepSeconds,
            isIntraday = timeframe.isIntraday
        )
    }

    private fun buildBaseSeries(
        symbol: String,
        targetClose: Double,
        annualIv: Double,
        count: Int,
        stepSeconds: Long,
        isIntraday: Boolean
    ): List<ChartCandle> {
        val seed = abs(symbol.hashCode()) % 97
        val closes = DoubleArray(count)
        val barSigma = if (isIntraday) {
            annualIv / kotlin.math.sqrt(252.0 * 78.0) * kotlin.math.sqrt(stepSeconds / 300.0)
        } else {
            annualIv / kotlin.math.sqrt(252.0) * kotlin.math.sqrt(stepSeconds / 86_400.0)
        }.coerceIn(0.0008, 0.035)

        closes[count - 1] = targetClose
        for (i in (count - 2) downTo 0) {
            val wave = sin((i + seed) * 0.19) * 0.85 + sin((i * 0.045) + seed) * 0.55 + 0.04
            val ret = barSigma * wave
            closes[i] = (closes[i + 1] / (1.0 + ret)).coerceAtLeast(1.0)
        }

        val anchorEnd = ZonedDateTime.of(2026, 11, 19, 16, 0, 0, 0, ZoneId.of("America/New_York"))
            .toEpochSecond()
        val candles = ArrayList<ChartCandle>(count)
        for (i in 0 until count) {
            val c = round2(closes[i])
            val prev = if (i == 0) round2(c * (1.0 - barSigma * 0.25)) else candles[i - 1].close
            val open = prev
            val wickPad = max(0.01, round2(c * barSigma * (0.65 + (abs(sin(i + seed * 1.1)) * 0.7))))
            val high = round2(max(open, c) + wickPad)
            val low = max(0.01, round2(min(open, c) - wickPad))
            val volBase = if (isIntraday) 45_000L else 3_200_000L
            val volWave = (1.0 + abs(sin((i + seed) * 0.37)) * 1.6)
            val vol = (volBase * volWave).toLong().coerceAtLeast(1_000L)
            val epochSeconds = if (isIntraday) {
                val tradingDayIdx = i / 26
                val intraSlot = i % 26
                val dayStart = anchorEnd - ((count / 26 - tradingDayIdx) * 86_400L) - (16 * 3600L)
                dayStart + (9 * 3600L + 1800L) + intraSlot * stepSeconds.coerceAtMost(1_800L)
            } else {
                anchorEnd - ((count - 1 - i) * stepSeconds)
            }
            val safeEpoch = if (candles.isNotEmpty() && epochSeconds <= candles.last().epochSeconds) {
                candles.last().epochSeconds + stepSeconds
            } else {
                epochSeconds
            }
            candles.add(
                ChartCandle(
                    epochSeconds = safeEpoch,
                    open = open,
                    high = maxOf(high, open, c),
                    low = minOf(low, open, c),
                    close = c,
                    volume = vol
                )
            )
        }
        return candles
    }

    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0

    companion object {
        private const val DEFAULT_AS_OF_EPOCH_MS = 1_795_120_000_000L
    }
}
