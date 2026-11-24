package com.schwabquant.app.core.quant

import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.Position
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class StressIvMode(val label: String) {
    TERM_STRUCTURE_SQRT("Term √(30/DTE)"),
    PARALLEL("Parallel IV")
}

data class StressMatrixSummary(
    val scenarios: List<StressScenarioResult>,
    val ivMode: StressIvMode,
    val zeroShockPnl: Double,
    val payoffSkew15: Double?,
    val upsideCapture5: Double?,
    val isNegativeSkew: Boolean,
    val isUpsideCapped: Boolean
)

/**
 * Calibrated Black-Scholes-Merton portfolio stress calculator.
 * Calibrates each option leg's base implied volatility (iv0) to its live mark before applying
 * beta-scaled spot shocks and term-structure or parallel IV shifts differentially against
 * bsm(spot, K, T, r, q, iv0), guaranteeing exact zero P&L at (spyShock = 0.0, ivShift = 0.0).
 */
@Singleton
class PortfolioStressCalculator @Inject constructor(
    private val blackScholesEngine: BlackScholesEngine
) {

    fun runStress(
        positions: List<Position>,
        accountNetLiq: Double,
        spySpot: Double = 0.0,
        spyShock: Double,
        ivShift: Double,
        ivMode: StressIvMode = StressIvMode.TERM_STRUCTURE_SQRT,
        riskFreeRate: Double = BlackScholesEngine.DEFAULT_RISK_FREE_RATE
    ): StressScenarioResult {
        var totalPnl = 0.0

        positions.forEach { pos ->
            val qty = pos.quantity.toDouble()
            if (qty == 0.0) return@forEach

            val beta = pos.betaToSpy ?: 1.0
            when (pos.category) {
                AssetCategory.EQUITY -> {
                    val baseSpot = pos.underlyingSpot ?: pos.markPrice.toDouble()
                    if (baseSpot > 0.0) {
                        val shockedSpot = baseSpot * max(0.01, 1.0 + beta * spyShock)
                        totalPnl += qty * (shockedSpot - baseSpot)
                    }
                }

                AssetCategory.OPTION -> {
                    val baseSpot = pos.underlyingSpot ?: return@forEach
                    val strike = pos.strike ?: return@forEach
                    val isCall = pos.isCall ?: return@forEach
                    if (baseSpot <= 0.0 || strike <= 0.0) return@forEach

                    val dte = pos.daysToExpiration ?: 30
                    val timeYears = max(0.0, dte.toDouble()) / 365.0
                    val divYield = pos.divYield ?: 0.0
                    val liveMark = pos.markPrice.toDouble()

                    val calibratedIv = if (liveMark > 0.0 && timeYears > 0.0) {
                        blackScholesEngine.solveImpliedVolatility(
                            marketPrice = liveMark,
                            spot = baseSpot,
                            strike = strike,
                            timeYears = timeYears,
                            rate = riskFreeRate,
                            dividendYield = divYield,
                            isCall = isCall
                        )
                    } else {
                        null
                    }
                    val iv0 = (calibratedIv
                        ?: pos.impliedVolatility?.takeIf { it.isFinite() && it > 0.01 }
                        ?: DEFAULT_FALLBACK_IV
                    ).coerceIn(MIN_SOLVER_IV, MAX_SOLVER_IV)

                    val effectiveIvShift = when (ivMode) {
                        StressIvMode.PARALLEL -> ivShift
                        StressIvMode.TERM_STRUCTURE_SQRT -> {
                            val scale = sqrt(30.0 / max(7.0, dte.toDouble())).coerceAtMost(1.5)
                            ivShift * scale
                        }
                    }

                    val shockedSpot = baseSpot * max(0.01, 1.0 + beta * spyShock)
                    val shockedIv = max(MIN_SOLVER_IV, iv0 + effectiveIvShift).coerceAtMost(MAX_SOLVER_IV)

                    val baseModelPrice = blackScholesEngine.price(
                        isCall = isCall,
                        spot = baseSpot,
                        strike = strike,
                        timeYears = timeYears,
                        rate = riskFreeRate,
                        divYield = divYield,
                        iv = iv0
                    )
                    val shockedModelPrice = blackScholesEngine.price(
                        isCall = isCall,
                        spot = shockedSpot,
                        strike = strike,
                        timeYears = timeYears,
                        rate = riskFreeRate,
                        divYield = divYield,
                        iv = shockedIv
                    )
                    totalPnl += (shockedModelPrice - baseModelPrice) * qty * OPTION_MULTIPLIER
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

        return StressScenarioResult(
            spyShockPercent = spyShock * 100.0,
            ivShockAbsolute = ivShift,
            portfolioDollarPnl = totalPnl,
            portfolioPercentChange = pctChange
        )
    }

    fun computeStressSummary(
        positions: List<Position>,
        accountNetLiq: Double,
        spySpot: Double,
        spyBetaDelta: Double,
        includeVolSkewShock: Boolean = true,
        ivMode: StressIvMode = StressIvMode.TERM_STRUCTURE_SQRT,
        riskFreeRate: Double = BlackScholesEngine.DEFAULT_RISK_FREE_RATE
    ): StressMatrixSummary {
        val scenarios = STRESS_SHOCKS.map { (spyShock, ivSkewShock) ->
            val appliedIvShift = if (includeVolSkewShock) ivSkewShock else 0.0
            runStress(
                positions = positions,
                accountNetLiq = accountNetLiq,
                spySpot = spySpot,
                spyShock = spyShock,
                ivShift = appliedIvShift,
                ivMode = ivMode,
                riskFreeRate = riskFreeRate
            )
        }

        val zeroShockPnl = runStress(
            positions = positions,
            accountNetLiq = accountNetLiq,
            spySpot = spySpot,
            spyShock = 0.0,
            ivShift = 0.0,
            ivMode = ivMode,
            riskFreeRate = riskFreeRate
        ).portfolioDollarPnl

        val pnlAtMinus15 = scenarios.firstOrNull { it.spyShockPercent <= -14.9 }?.portfolioDollarPnl ?: 0.0
        val pnlAtPlus5 = scenarios.firstOrNull { it.spyShockPercent in 4.9..5.1 }?.portfolioDollarPnl ?: 0.0
        val pnlAtPlus15 = scenarios.firstOrNull { it.spyShockPercent >= 14.9 }?.portfolioDollarPnl ?: 0.0

        val payoffSkew15 = if (pnlAtMinus15 < 0.0) {
            abs(pnlAtMinus15) / max(pnlAtPlus15, 1.0)
        } else {
            null
        }

        val linearUp5 = if (spySpot > 0.0) spyBetaDelta * (0.05 * spySpot) else 0.0
        val upsideCapture5 = if (linearUp5 > 0.0) {
            pnlAtPlus5 / linearUp5
        } else {
            null
        }

        val isNegativeSkew = spyBetaDelta > 0.0 && payoffSkew15 != null && payoffSkew15 > NEGATIVE_SKEW_THRESHOLD
        val isUpsideCapped = spyBetaDelta > 0.0 && upsideCapture5 != null && upsideCapture5 < UPSIDE_CAP_THRESHOLD

        return StressMatrixSummary(
            scenarios = scenarios,
            ivMode = ivMode,
            zeroShockPnl = zeroShockPnl,
            payoffSkew15 = payoffSkew15,
            upsideCapture5 = upsideCapture5,
            isNegativeSkew = isNegativeSkew,
            isUpsideCapped = isUpsideCapped
        )
    }

    companion object {
        private const val OPTION_MULTIPLIER = 100.0
        private const val DEFAULT_FALLBACK_IV = 0.35
        private const val MIN_SOLVER_IV = 0.01
        private const val MAX_SOLVER_IV = 5.00
        private const val NEGATIVE_SKEW_THRESHOLD = 2.0
        private const val UPSIDE_CAP_THRESHOLD = 0.50

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
