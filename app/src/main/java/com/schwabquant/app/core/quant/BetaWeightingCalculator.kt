package com.schwabquant.app.core.quant

import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.Position
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class BetaSource(val badge: String) {
    OLS_252D("OLS"),
    FUNDAMENTAL("FUND"),
    FALLBACK_1_0("FALLBACK 1.0")
}

data class BetaEstimate(
    val beta: Double,
    val source: BetaSource,
    val rSquared: Double? = null
)

data class BetaWeightedPosition(
    val symbol: String,
    val spyBetaWeightedDelta: Double
)

data class StressScenarioResult(
    val spyShockPercent: Double,
    val ivShockAbsolute: Double,
    val portfolioDollarPnl: Double,
    val portfolioPercentChange: Double
)

/**
 * Institutional SPY Beta-Weighted Delta aggregator, rolling 252-day OLS beta estimator with
 * low-correlation ETF guard, and Black-Scholes Portfolio Stress Matrix delegator.
 */
@Singleton
class BetaWeightingCalculator @Inject constructor(
    private val blackScholesEngine: BlackScholesEngine
) {
    private val stressCalculator = PortfolioStressCalculator(blackScholesEngine)

    fun computeRollingOlsBeta(
        symbolCloses: List<Double>,
        spyCloses: List<Double>,
        applyBlumeShrinkage: Boolean = true
    ): BetaEstimate? {
        val nCloses = minOf(symbolCloses.size, spyCloses.size, MAX_OLS_CLOSES)
        if (nCloses < MIN_OLS_CLOSES) return null

        val symTail = symbolCloses.takeLast(nCloses)
        val spyTail = spyCloses.takeLast(nCloses)

        val symReturns = ArrayList<Double>(nCloses - 1)
        val spyReturns = ArrayList<Double>(nCloses - 1)

        for (i in 1 until nCloses) {
            val prevSym = symTail[i - 1]
            val currSym = symTail[i]
            val prevSpy = spyTail[i - 1]
            val currSpy = spyTail[i]
            if (!prevSym.isFinite() || !currSym.isFinite() || prevSym <= 0.0 || currSym <= 0.0) continue
            if (!prevSpy.isFinite() || !currSpy.isFinite() || prevSpy <= 0.0 || currSpy <= 0.0) continue
            symReturns.add((currSym - prevSym) / prevSym)
            spyReturns.add((currSpy - prevSpy) / prevSpy)
        }

        val count = symReturns.size
        if (count < MIN_OLS_RETURNS) return null

        val meanSym = symReturns.average()
        val meanSpy = spyReturns.average()

        var covSum = 0.0
        var varSpySum = 0.0
        var varSymSum = 0.0
        for (i in 0 until count) {
            val dSym = symReturns[i] - meanSym
            val dSpy = spyReturns[i] - meanSpy
            covSum += dSym * dSpy
            varSpySum += dSpy * dSpy
            varSymSum += dSym * dSym
        }

        if (!varSpySum.isFinite() || varSpySum <= 1e-12) return null
        val betaRaw = covSum / varSpySum
        if (!betaRaw.isFinite()) return null

        val rSquared = if (varSymSum > 1e-12) {
            ((covSum * covSum) / (varSymSum * varSpySum)).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        // Low-Correlation ETF Guard (protects SGOV / bond ETFs from Blume reversion toward 1.0)
        val betaAdjusted = if (applyBlumeShrinkage && rSquared >= BLUME_MIN_R2 && betaRaw >= BLUME_MIN_RAW_BETA) {
            (2.0 / 3.0) * betaRaw + (1.0 / 3.0) * 1.0
        } else {
            betaRaw
        }

        val clamped = betaAdjusted.coerceIn(MIN_BETA, MAX_BETA)
        return BetaEstimate(
            beta = clamped,
            source = BetaSource.OLS_252D,
            rSquared = rSquared
        )
    }

    fun computeRollingOlsBetaFromCandles(
        symbolCandles: List<ChartCandle>,
        spyCandles: List<ChartCandle>,
        applyBlumeShrinkage: Boolean = true,
        zoneId: ZoneId = ChartTimeframeMapper.NEW_YORK_ZONE
    ): BetaEstimate? {
        if (symbolCandles.size < MIN_OLS_CLOSES || spyCandles.size < MIN_OLS_CLOSES) return null

        val symByDate = LinkedHashMap<LocalDate, Double>(symbolCandles.size)
        symbolCandles.sortedBy { it.epochSeconds }.forEach { candle ->
            if (candle.close.isFinite() && candle.close > 0.0) {
                val date = Instant.ofEpochSecond(candle.epochSeconds).atZone(zoneId).toLocalDate()
                symByDate[date] = candle.close
            }
        }

        val spyByDate = LinkedHashMap<LocalDate, Double>(spyCandles.size)
        spyCandles.sortedBy { it.epochSeconds }.forEach { candle ->
            if (candle.close.isFinite() && candle.close > 0.0) {
                val date = Instant.ofEpochSecond(candle.epochSeconds).atZone(zoneId).toLocalDate()
                spyByDate[date] = candle.close
            }
        }

        val overlappingDates = spyByDate.keys.filter { it in symByDate }
        if (overlappingDates.size < MIN_OLS_CLOSES) return null

        val alignedDates = overlappingDates.takeLast(MAX_OLS_CLOSES)
        val symCloses = alignedDates.mapNotNull { symByDate[it] }
        val spyCloses = alignedDates.mapNotNull { spyByDate[it] }
        return computeRollingOlsBeta(symCloses, spyCloses, applyBlumeShrinkage)
    }

    fun betaWeightedDelta(
        positionDelta: Double,
        contractMultiplier: Double,
        tickerBeta: Double?,
        tickerSpot: Double,
        spySpot: Double
    ): Double {
        if (!spySpot.isFinite() || !tickerSpot.isFinite() || spySpot <= 0.0 || tickerSpot <= 0.0) {
            return 0.0
        }
        val beta = tickerBeta ?: 1.0
        return positionDelta * contractMultiplier * beta * (tickerSpot / spySpot)
    }

    fun calculateSpyBetaWeightedDelta(
        positions: List<Position>,
        spyPrice: Double
    ): Double = breakdown(positions, spyPrice).sumOf { it.spyBetaWeightedDelta }

    fun breakdown(
        positions: List<Position>,
        spyPrice: Double
    ): List<BetaWeightedPosition> {
        if (spyPrice <= 0.0) return emptyList()

        return positions.mapNotNull { pos ->
            val qty = pos.quantity.toDouble()
            val beta = pos.betaToSpy ?: 1.0
            val bwd = when (pos.category) {
                AssetCategory.EQUITY -> {
                    val spot = pos.underlyingSpot ?: pos.markPrice.toDouble()
                    betaWeightedDelta(
                        positionDelta = qty,
                        contractMultiplier = 1.0,
                        tickerBeta = beta,
                        tickerSpot = spot,
                        spySpot = spyPrice
                    )
                }

                AssetCategory.OPTION -> {
                    val spot = pos.underlyingSpot ?: return@mapNotNull null
                    val delta = pos.delta ?: 0.0
                    betaWeightedDelta(
                        positionDelta = qty * delta,
                        contractMultiplier = OPTION_MULTIPLIER,
                        tickerBeta = beta,
                        tickerSpot = spot,
                        spySpot = spyPrice
                    )
                }

                AssetCategory.CASH,
                AssetCategory.INDEX -> 0.0
            }

            BetaWeightedPosition(
                symbol = pos.symbol,
                spyBetaWeightedDelta = bwd
            )
        }
    }

    fun computeStressMatrix(
        positions: List<Position>,
        accountNetLiq: Double,
        spyPrice: Double,
        includeVolSkewShock: Boolean = true,
        riskFreeRate: Double = BlackScholesEngine.DEFAULT_RISK_FREE_RATE,
        ivMode: StressIvMode = StressIvMode.TERM_STRUCTURE_SQRT
    ): List<StressScenarioResult> {
        val spyBetaDelta = calculateSpyBetaWeightedDelta(positions, spyPrice)
        return stressCalculator.computeStressSummary(
            positions = positions,
            accountNetLiq = accountNetLiq,
            spySpot = spyPrice,
            spyBetaDelta = spyBetaDelta,
            includeVolSkewShock = includeVolSkewShock,
            ivMode = ivMode,
            riskFreeRate = riskFreeRate
        ).scenarios
    }

    companion object {
        private const val OPTION_MULTIPLIER = 100.0
        private const val MIN_OLS_RETURNS = 60
        private const val MIN_OLS_CLOSES = MIN_OLS_RETURNS + 1
        private const val MAX_OLS_CLOSES = 253
        private const val BLUME_MIN_R2 = 0.10
        private const val BLUME_MIN_RAW_BETA = 0.25
        private const val MIN_BETA = -3.0
        private const val MAX_BETA = 5.0
    }
}
