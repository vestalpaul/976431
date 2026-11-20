package com.schwabquant.app.core.quant

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.sqrt

data class VolRankResult(
    val volRank: Double,
    val label: String = "Vol Rank (52w)",
    val hv52wLow: Double,
    val hv52wHigh: Double,
    val currentHv20: Double,
    val referenceIv: Double,
    val isValid: Boolean
)

/**
 * Pure-JVM 52-Week Vol Rank Proxy:
 * Computes 20-day rolling annualized Historical Volatility across up to 252 daily log returns
 * to derive [hv52wLow, hv52wHigh], then maps live 30-day ATM option chain IV onto [0.0, 100.0].
 */
@Singleton
class VolRankCalculator @Inject constructor() {

    fun computeVolRank(
        dailyCloses: List<Double>,
        liveAtmIv30: Double?,
        windowDays: Int = DEFAULT_WINDOW_DAYS
    ): VolRankResult {
        val cleanCloses = dailyCloses
            .filter { it.isFinite() && it > 0.0 }
            .takeLast(MAX_CLOSES_FOR_52W_RETURNS)

        if (windowDays < 2 || cleanCloses.size < windowDays + 1) {
            val fallbackIv = liveAtmIv30?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            return VolRankResult(
                volRank = 50.0,
                hv52wLow = 0.0,
                hv52wHigh = 0.0,
                currentHv20 = 0.0,
                referenceIv = fallbackIv,
                isValid = false
            )
        }

        val logReturns = DoubleArray(cleanCloses.size - 1) { idx ->
            ln(cleanCloses[idx + 1] / cleanCloses[idx])
        }

        val rollingHvs = ArrayList<Double>(logReturns.size - windowDays + 1)
        val sqrt252 = sqrt(TRADING_DAYS_PER_YEAR)

        for (start in 0..(logReturns.size - windowDays)) {
            var sum = 0.0
            for (k in start until (start + windowDays)) {
                sum += logReturns[k]
            }
            val mean = sum / windowDays
            var sqDiffSum = 0.0
            for (k in start until (start + windowDays)) {
                val diff = logReturns[k] - mean
                sqDiffSum += diff * diff
            }
            val variance = sqDiffSum / (windowDays - 1)
            val annualizedHv = sqrt(variance.coerceAtLeast(0.0)) * sqrt252
            rollingHvs.add(annualizedHv)
        }

        val hvLow = rollingHvs.minOrNull() ?: 0.0
        val hvHigh = rollingHvs.maxOrNull() ?: 0.0
        val currentHv20 = rollingHvs.lastOrNull() ?: 0.0
        val referenceIv = liveAtmIv30?.takeIf { it.isFinite() && it > 0.0 } ?: currentHv20

        val range = hvHigh - hvLow
        val rank = if (range < EPSILON) {
            50.0
        } else {
            (((referenceIv - hvLow) / range) * 100.0).coerceIn(0.0, 100.0)
        }

        return VolRankResult(
            volRank = rank,
            hv52wLow = hvLow,
            hv52wHigh = hvHigh,
            currentHv20 = currentHv20,
            referenceIv = referenceIv,
            isValid = true
        )
    }

    companion object {
        const val DEFAULT_WINDOW_DAYS = 20
        private const val TRADING_DAYS_PER_YEAR = 252.0
        private const val MAX_CLOSES_FOR_52W_RETURNS = 253
        private const val EPSILON = 1e-6
    }
}
