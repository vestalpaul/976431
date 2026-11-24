package com.schwabquant.app.core.quant

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class OptionGreeks(
    val price: Double,
    val delta: Double,
    val gamma: Double,
    val thetaPerDay: Double,
    val vegaPer1PctIv: Double,
    val rhoPer1PctRate: Double,
    val intrinsicValue: Double,
    val extrinsicValue: Double
)

/**
 * Pure-JVM Generalized Black-Scholes-Merton pricing, analytical Greeks, and hybrid
 * Newton-Raphson / bisection Implied Volatility solver with continuous dividend yield (q).
 */
@Singleton
class BlackScholesEngine @Inject constructor() {

    fun price(
        isCall: Boolean,
        spot: Double,
        strike: Double,
        timeYears: Double,
        rate: Double = DEFAULT_RISK_FREE_RATE,
        divYield: Double = 0.0,
        iv: Double
    ): Double = greeks(
        isCall = isCall,
        spot = spot,
        strike = strike,
        timeYears = timeYears,
        rate = rate,
        divYield = divYield,
        iv = iv
    ).price

    fun greeks(
        isCall: Boolean,
        spot: Double,
        strike: Double,
        timeYears: Double,
        rate: Double = DEFAULT_RISK_FREE_RATE,
        divYield: Double = 0.0,
        iv: Double
    ): OptionGreeks {
        if (!spot.isFinite() || !strike.isFinite() || spot <= 0.0 || strike <= 0.0) {
            return ZERO_GREEKS
        }

        val intrinsic = if (isCall) max(0.0, spot - strike) else max(0.0, strike - spot)

        if (!timeYears.isFinite() || timeYears <= 0.0) {
            val expiryDelta = when {
                isCall && spot > strike -> 1.0
                isCall && spot < strike -> 0.0
                isCall -> 0.5
                spot < strike -> -1.0
                spot > strike -> 0.0
                else -> -0.5
            }
            return OptionGreeks(
                price = intrinsic,
                delta = expiryDelta,
                gamma = 0.0,
                thetaPerDay = 0.0,
                vegaPer1PctIv = 0.0,
                rhoPer1PctRate = 0.0,
                intrinsicValue = intrinsic,
                extrinsicValue = 0.0
            )
        }

        val dfR = exp(-rate * timeYears)
        val dfQ = exp(-divYield * timeYears)

        if (!iv.isFinite() || iv <= 0.0) {
            val discountedPrice = if (isCall) {
                max(0.0, spot * dfQ - strike * dfR)
            } else {
                max(0.0, strike * dfR - spot * dfQ)
            }
            val forward = spot * dfQ - strike * dfR
            val detDelta = when {
                isCall && forward > 0.0 -> dfQ
                isCall && forward < 0.0 -> 0.0
                isCall -> 0.5 * dfQ
                forward < 0.0 -> -dfQ
                forward > 0.0 -> 0.0
                else -> -0.5 * dfQ
            }
            return OptionGreeks(
                price = discountedPrice,
                delta = detDelta,
                gamma = 0.0,
                thetaPerDay = 0.0,
                vegaPer1PctIv = 0.0,
                rhoPer1PctRate = 0.0,
                intrinsicValue = intrinsic,
                extrinsicValue = max(0.0, discountedPrice - intrinsic)
            )
        }

        val sqrtT = sqrt(timeYears)
        val volSqrtT = iv * sqrtT
        val d1 = (ln(spot / strike) + (rate - divYield + 0.5 * iv * iv) * timeYears) / volSqrtT
        val d2 = d1 - volSqrtT

        val nd1 = normCdf(d1)
        val nd2 = normCdf(d2)
        val nNegD1 = normCdf(-d1)
        val nNegD2 = normCdf(-d2)
        val pdfD1 = normPdf(d1)

        val optionPrice = if (isCall) {
            max(0.0, spot * dfQ * nd1 - strike * dfR * nd2)
        } else {
            max(0.0, strike * dfR * nNegD2 - spot * dfQ * nNegD1)
        }

        val delta = if (isCall) {
            dfQ * nd1
        } else {
            dfQ * (nd1 - 1.0)
        }

        val gamma = (dfQ * pdfD1) / (spot * volSqrtT)

        val annualTheta = if (isCall) {
            -(spot * dfQ * pdfD1 * iv) / (2.0 * sqrtT) -
                rate * strike * dfR * nd2 +
                divYield * spot * dfQ * nd1
        } else {
            -(spot * dfQ * pdfD1 * iv) / (2.0 * sqrtT) +
                rate * strike * dfR * nNegD2 -
                divYield * spot * dfQ * nNegD1
        }

        val rawVega = spot * dfQ * pdfD1 * sqrtT
        val rawRho = if (isCall) {
            strike * timeYears * dfR * nd2
        } else {
            -strike * timeYears * dfR * nNegD2
        }

        return OptionGreeks(
            price = optionPrice,
            delta = delta,
            gamma = gamma,
            thetaPerDay = annualTheta / 365.0,
            vegaPer1PctIv = rawVega / 100.0,
            rhoPer1PctRate = rawRho / 100.0,
            intrinsicValue = intrinsic,
            extrinsicValue = max(0.0, optionPrice - intrinsic)
        )
    }

    fun impliedVolatility(
        isCall: Boolean,
        marketPrice: Double,
        spot: Double,
        strike: Double,
        timeYears: Double,
        rate: Double = DEFAULT_RISK_FREE_RATE,
        divYield: Double = 0.0
    ): Double? {
        if (!marketPrice.isFinite() || !spot.isFinite() || !strike.isFinite() ||
            !timeYears.isFinite() || !rate.isFinite() || !divYield.isFinite()
        ) {
            return null
        }
        if (spot <= 0.0 || strike <= 0.0 || timeYears <= 0.0 || marketPrice <= 0.0) {
            return null
        }

        val dfQ = exp(-divYield * timeYears)
        val dfR = exp(-rate * timeYears)
        val lowerBound = if (isCall) {
            max(0.0, spot * dfQ - strike * dfR)
        } else {
            max(0.0, strike * dfR - spot * dfQ)
        }
        val upperBound = if (isCall) spot * dfQ else strike * dfR

        if (marketPrice <= lowerBound + ARBITRAGE_EPS || marketPrice >= upperBound - ARBITRAGE_EPS) {
            return null
        }

        var low = MIN_IV
        var high = MAX_IV
        val fLow = price(isCall, spot, strike, timeYears, rate, divYield, low) - marketPrice
        val fHigh = price(isCall, spot, strike, timeYears, rate, divYield, high) - marketPrice
        if (fLow > 0.0 || fHigh < 0.0) {
            return null
        }

        val brennerGuess = sqrt(2.0 * PI / timeYears) * (marketPrice / spot)
        var sigma = if (brennerGuess.isFinite()) {
            brennerGuess.coerceIn(low + 1e-3, high * 0.5)
        } else {
            0.30
        }

        for (i in 0 until IV_MAX_ITERATIONS) {
            val g = greeks(isCall, spot, strike, timeYears, rate, divYield, sigma)
            val diff = g.price - marketPrice
            if (abs(diff) <= IV_TOLERANCE) {
                return sigma.takeIf { it.isFinite() }
            }

            if (diff < 0.0) {
                low = sigma
            } else {
                high = sigma
            }

            if (abs(high - low) <= IV_TOLERANCE) {
                return (0.5 * (low + high)).takeIf { it.isFinite() }
            }

            val rawVega = g.vegaPer1PctIv * 100.0
            val newtonCandidate = if (rawVega > 1e-10) sigma - diff / rawVega else Double.NaN
            sigma = if (newtonCandidate.isFinite() && newtonCandidate > low && newtonCandidate < high) {
                newtonCandidate
            } else {
                0.5 * (low + high)
            }
        }

        val finalDiff = abs(price(isCall, spot, strike, timeYears, rate, divYield, sigma) - marketPrice)
        return if (sigma.isFinite() && finalDiff <= 1e-3) sigma else null
    }

    /**
     * Bounded Brent root-finder for implied volatility over [0.01, 5.00] using exact (r, q, T).
     * Returns null when the option trades at/below intrinsic floor or outside the [1%, 500%] bracket.
     */
    fun solveImpliedVolatility(
        marketPrice: Double,
        spot: Double,
        strike: Double,
        timeYears: Double,
        rate: Double = DEFAULT_RISK_FREE_RATE,
        dividendYield: Double = 0.0,
        isCall: Boolean
    ): Double? {
        if (!marketPrice.isFinite() || !spot.isFinite() || !strike.isFinite() ||
            !timeYears.isFinite() || !rate.isFinite() || !dividendYield.isFinite()
        ) {
            return null
        }
        if (spot <= 0.0 || strike <= 0.0 || timeYears <= 0.0 || marketPrice <= 0.0) {
            return null
        }

        val lowBound = 0.01
        val highBound = 5.00
        val tol = 1e-5
        var a = lowBound
        var b = highBound
        var fa = price(isCall, spot, strike, timeYears, rate, dividendYield, a) - marketPrice
        var fb = price(isCall, spot, strike, timeYears, rate, dividendYield, b) - marketPrice

        if (abs(fa) <= tol) return a
        if (abs(fb) <= tol) return b
        if (fa * fb > 0.0) return null

        var c = a
        var fc = fa
        var d = b - a
        var e = d

        for (iter in 0 until IV_MAX_ITERATIONS) {
            if ((fb > 0.0 && fc > 0.0) || (fb < 0.0 && fc < 0.0)) {
                c = a
                fc = fa
                d = b - a
                e = d
            }
            if (abs(fc) < abs(fb)) {
                a = b
                b = c
                c = a
                fa = fb
                fb = fc
                fc = fa
            }

            val tol1 = 2.0 * 1e-12 * abs(b) + 0.5 * tol
            val xm = 0.5 * (c - b)
            if (abs(xm) <= tol1 || abs(fb) <= tol) {
                return b.takeIf { it.isFinite() && it in lowBound..highBound }
            }

            if (abs(e) >= tol1 && abs(fa) > abs(fb)) {
                val s = fb / fa
                var p: Double
                var q: Double
                if (a == c) {
                    p = 2.0 * xm * s
                    q = 1.0 - s
                } else {
                    val q0 = fa / fc
                    val r0 = fb / fc
                    p = s * (2.0 * xm * q0 * (q0 - r0) - (b - a) * (r0 - 1.0))
                    q = (q0 - 1.0) * (r0 - 1.0) * (s - 1.0)
                }
                if (p > 0.0) q = -q else p = -p
                if (2.0 * p < kotlin.math.min(3.0 * xm * q - abs(tol1 * q), abs(e * q))) {
                    e = d
                    d = p / q
                } else {
                    d = xm
                    e = d
                }
            } else {
                d = xm
                e = d
            }

            a = b
            fa = fb
            b += if (abs(d) > tol1) d else if (xm >= 0.0) tol1 else -tol1
            fb = price(isCall, spot, strike, timeYears, rate, dividendYield, b) - marketPrice
        }

        return b.takeIf { it.isFinite() && it in lowBound..highBound && abs(fb) <= 1e-3 }
    }

    private fun normPdf(x: Double): Double =
        INV_SQRT_2PI * exp(-0.5 * x * x)

    private fun normCdf(x: Double): Double =
        0.5 * erfc(-x * INV_SQRT_2)

    /**
     * High-precision complementary error function erfc(x) (Cody's rational Chebyshev approximation,
     * max relative error < 1e-15 across double precision).
     */
    private fun erfc(x: Double): Double {
        val ax = abs(x)
        if (ax > 26.5) {
            return if (x >= 0.0) 0.0 else 2.0
        }

        val result: Double
        if (ax <= 0.46875) {
            val z2 = ax * ax
            var xnum = ERFC_A[4] * z2
            var xden = z2
            for (i in 0..2) {
                xnum = (xnum + ERFC_A[i]) * z2
                xden = (xden + ERFC_B[i]) * z2
            }
            val erfVal = x * (xnum + ERFC_A[3]) / (xden + ERFC_B[3])
            return 1.0 - erfVal
        } else if (ax <= 4.0) {
            var xnum = ERFC_C[8] * ax
            var xden = ax
            for (i in 0..6) {
                xnum = (xnum + ERFC_C[i]) * ax
                xden = (xden + ERFC_D[i]) * ax
            }
            result = exp(-ax * ax) * ((xnum + ERFC_C[7]) / (xden + ERFC_D[7]))
        } else {
            val z2 = 1.0 / (ax * ax)
            var xnum = ERFC_P[5] * z2
            var xden = z2
            for (i in 0..3) {
                xnum = (xnum + ERFC_P[i]) * z2
                xden = (xden + ERFC_Q[i]) * z2
            }
            val tail = z2 * (xnum + ERFC_P[4]) / (xden + ERFC_Q[4])
            result = (exp(-ax * ax) / ax) * (INV_SQRT_PI - tail)
        }

        return if (x < 0.0) 2.0 - result else result
    }

    companion object {
        const val DEFAULT_RISK_FREE_RATE = 0.045
        const val IV_TOLERANCE = 1e-6
        const val IV_MAX_ITERATIONS = 60
        private const val MIN_IV = 1e-4
        private const val MAX_IV = 10.0
        private const val ARBITRAGE_EPS = 1e-9

        private val INV_SQRT_2PI = 1.0 / sqrt(2.0 * PI)
        private val INV_SQRT_2 = 1.0 / sqrt(2.0)
        private val INV_SQRT_PI = 1.0 / sqrt(PI)

        private val ZERO_GREEKS = OptionGreeks(
            price = 0.0,
            delta = 0.0,
            gamma = 0.0,
            thetaPerDay = 0.0,
            vegaPer1PctIv = 0.0,
            rhoPer1PctRate = 0.0,
            intrinsicValue = 0.0,
            extrinsicValue = 0.0
        )

        private val ERFC_A = doubleArrayOf(
            3.1611237438705656E+00,
            1.1386415415105016E+02,
            3.7748523768530202E+02,
            3.2093775891384695E+03,
            1.8577770618460315E-01
        )
        private val ERFC_B = doubleArrayOf(
            2.3601290952344121E+01,
            2.4402463793444417E+02,
            1.2826165260773723E+03,
            2.8442368334391706E+03,
            1.0
        )
        private val ERFC_C = doubleArrayOf(
            5.6418849698867009E-01,
            8.8831497943883759E+00,
            6.6119190637141629E+01,
            2.9863513819740013E+02,
            8.8195222124176909E+02,
            1.7120476126340706E+03,
            2.0510783778260715E+03,
            1.2303393547979972E+03,
            2.1531153547440385E-08
        )
        private val ERFC_D = doubleArrayOf(
            1.5744926110709835E+01,
            1.1769395089131250E+02,
            5.3718110186200986E+02,
            1.6213895745666902E+03,
            3.2907992357334596E+03,
            4.3626190901432472E+03,
            3.4393676741437216E+03,
            1.2303393548037494E+03,
            1.0
        )
        private val ERFC_P = doubleArrayOf(
            3.0532663496123234E-01,
            3.6034489994980444E-01,
            1.2578172611122925E-01,
            1.6083785148742277E-02,
            6.5874916152983780E-04,
            1.6315387137302098E-02
        )
        private val ERFC_Q = doubleArrayOf(
            2.5685201922898222E+00,
            1.8729528499234605E+00,
            5.2790510295142841E-01,
            6.0518341312441320E-02,
            2.3352049762686919E-03,
            1.0
        )
    }
}
