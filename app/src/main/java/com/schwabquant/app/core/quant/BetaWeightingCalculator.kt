package com.schwabquant.app.core.quant

import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.Position
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

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
 * Institutional SPY Beta-Weighted Delta aggregator and Black-Scholes Portfolio Stress Matrix
 * calculator across [-15%, -10%, -5%, +5%, +10%, +15%] SPY shocks.
 */
@Singleton
class BetaWeightingCalculator @Inject constructor(
    private val blackScholesEngine: BlackScholesEngine
) {

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
        riskFreeRate: Double = BlackScholesEngine.DEFAULT_RISK_FREE_RATE
    ): List<StressScenarioResult> {
        return STRESS_SHOCKS.map { (spyShock, ivSkewShock) ->
            val appliedIvShock = if (includeVolSkewShock) ivSkewShock else 0.0
            var totalPnl = 0.0

            positions.forEach { pos ->
                val qty = pos.quantity.toDouble()
                if (qty == 0.0) return@forEach

                val beta = pos.betaToSpy ?: 1.0
                when (pos.category) {
                    AssetCategory.EQUITY -> {
                        val baseSpot = pos.underlyingSpot ?: pos.markPrice.toDouble()
                        if (baseSpot > 0.0) {
                            val shockedSpot = max(0.01, baseSpot * (1.0 + spyShock * beta))
                            totalPnl += qty * (shockedSpot - baseSpot)
                        }
                    }

                    AssetCategory.OPTION -> {
                        val baseSpot = pos.underlyingSpot ?: return@forEach
                        val strike = pos.strike ?: return@forEach
                        val isCall = pos.isCall ?: return@forEach
                        val dte = pos.daysToExpiration ?: 30
                        val timeYears = max(0.0, dte.toDouble()) / 365.0
                        val divYield = pos.divYield ?: 0.0
                        val baseMark = pos.markPrice.toDouble()

                        val baseIv = resolveOptionBaseIv(
                            pos = pos,
                            isCall = isCall,
                            baseMark = baseMark,
                            baseSpot = baseSpot,
                            strike = strike,
                            timeYears = timeYears,
                            riskFreeRate = riskFreeRate,
                            divYield = divYield
                        )
                        val shockedSpot = max(0.01, baseSpot * (1.0 + spyShock * beta))
                        val shockedIv = (baseIv + appliedIvShock).coerceIn(0.05, 5.0)

                        val referenceBasePrice = if (baseMark > 0.0) {
                            baseMark
                        } else {
                            blackScholesEngine.price(
                                isCall = isCall,
                                spot = baseSpot,
                                strike = strike,
                                timeYears = timeYears,
                                rate = riskFreeRate,
                                divYield = divYield,
                                iv = baseIv
                            )
                        }

                        val shockedPrice = blackScholesEngine.price(
                            isCall = isCall,
                            spot = shockedSpot,
                            strike = strike,
                            timeYears = timeYears,
                            rate = riskFreeRate,
                            divYield = divYield,
                            iv = shockedIv
                        )

                        totalPnl += qty * OPTION_MULTIPLIER * (shockedPrice - referenceBasePrice)
                    }

                    AssetCategory.CASH,
                    AssetCategory.INDEX -> Unit
                }
            }

            val pctChange = if (accountNetLiq > 0.0) {
                (totalPnl / accountNetLiq) * 100.0
            } else {
                0.0
            }

            StressScenarioResult(
                spyShockPercent = spyShock * 100.0,
                ivShockAbsolute = appliedIvShock,
                portfolioDollarPnl = totalPnl,
                portfolioPercentChange = pctChange
            )
        }
    }

    private fun resolveOptionBaseIv(
        pos: Position,
        isCall: Boolean,
        baseMark: Double,
        baseSpot: Double,
        strike: Double,
        timeYears: Double,
        riskFreeRate: Double,
        divYield: Double
    ): Double {
        val explicitIv = pos.impliedVolatility
        if (explicitIv != null && explicitIv.isFinite() && explicitIv > 0.01) {
            return explicitIv.coerceIn(0.05, 5.0)
        }
        val solved = blackScholesEngine.impliedVolatility(
            isCall = isCall,
            marketPrice = baseMark,
            spot = baseSpot,
            strike = strike,
            timeYears = timeYears,
            rate = riskFreeRate,
            divYield = divYield
        )
        return (solved ?: DEFAULT_FALLBACK_IV).coerceIn(0.05, 5.0)
    }

    companion object {
        private const val OPTION_MULTIPLIER = 100.0
        private const val DEFAULT_FALLBACK_IV = 0.35

        private val STRESS_SHOCKS = listOf(
            -0.15 to 0.08,
            -0.10 to 0.05,
            -0.05 to 0.025,
            0.05 to -0.015,
            0.10 to -0.025,
            0.15 to -0.03
        )
    }
}
