package com.schwabquant.app.data.repository

import com.schwabquant.app.core.quant.BlackScholesEngine
import com.schwabquant.app.core.quant.OccSymbolFormatter
import com.schwabquant.app.core.quant.ParsedOptionChain
import com.schwabquant.app.core.quant.ParsedOptionContract
import com.schwabquant.app.core.quant.VolRankCalculator
import com.schwabquant.app.core.quant.VolRankResult
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

data class DemoSymbolData(
    val chain: ParsedOptionChain,
    val volRank: VolRankResult,
    val sma50: Double
)

/**
 * Deterministic Black-Scholes offline option chain and 252-day close generator so all
 * 6 scanner strategies (PMCC, PMCP, Verticals, Calendars, Iron Condors, Wheel/CSP) can be
 * exercised and inspected cleanly without live Schwab API credentials.
 */
@Singleton
class DemoOptionChainProvider @Inject constructor(
    private val blackScholesEngine: BlackScholesEngine,
    private val occSymbolFormatter: OccSymbolFormatter,
    private val volRankCalculator: VolRankCalculator
) {

    constructor() : this(
        blackScholesEngine = BlackScholesEngine(),
        occSymbolFormatter = OccSymbolFormatter(),
        volRankCalculator = VolRankCalculator()
    )

    private data class DemoSpec(
        val spot: Double,
        val frontIv: Double,
        val backIv: Double,
        val divYield: Double,
        val step: Double,
        val baseOi: Int
    )

    fun buildDemoData(
        symbol: String,
        asOfDate: LocalDate = LocalDate.of(2026, 11, 19),
        nowEpochMs: Long = System.currentTimeMillis()
    ): DemoSymbolData {
        val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(symbol)
        val occRoot = SchwabSymbolNormalizer.toSchwabMarketDataSymbol(displaySym)
            .replace("/", "")
            .take(OccSymbolFormatter.ROOT_WIDTH)

        val spec = PRESET_SPECS[displaySym] ?: syntheticSpecFor(displaySym)
        val dailyCloses = buildSynthetic252Closes(spec.spot, spec.frontIv, displaySym.hashCode())
        val sma50 = dailyCloses.takeLast(50).average()

        val strikeGrid = generateStrikeGrid(spec.spot, spec.step)
        val cycles = listOf(
            28 to spec.frontIv,
            35 to (spec.frontIv - 0.005).coerceAtLeast(0.10),
            75 to spec.backIv,
            225 to (spec.backIv - 0.01).coerceAtLeast(0.10),
            365 to (spec.backIv - 0.015).coerceAtLeast(0.10)
        )

        val contracts = ArrayList<ParsedOptionContract>(strikeGrid.size * cycles.size * 2)
        val r = BlackScholesEngine.DEFAULT_RISK_FREE_RATE

        for ((dte, cycleBaseIv) in cycles) {
            val expDate = asOfDate.plusDays(dte.toLong())
            val t = dte / 365.0

            for (strike in strikeGrid) {
                val moneyness = (spec.spot - strike) / spec.spot
                val skewAdj = if (moneyness > 0) moneyness * 0.14 else abs(moneyness) * 0.04
                val iv = (cycleBaseIv + skewAdj).coerceIn(0.10, 0.95)
                val distFactor = (1.0 - abs(moneyness) * 2.2).coerceIn(0.20, 1.0)
                val oi = (spec.baseOi * distFactor).toInt().coerceAtLeast(140)
                val vol = (oi / 4).coerceAtLeast(35)

                for (isCall in listOf(true, false)) {
                    val g = blackScholesEngine.greeks(
                        isCall = isCall,
                        spot = spec.spot,
                        strike = strike,
                        timeYears = t,
                        rate = r,
                        divYield = spec.divYield,
                        iv = iv
                    )
                    val mid = (g.price * 100.0).roundToInt() / 100.0
                    if (mid <= 0.05) continue

                    val halfSpread = max(0.01, (mid * 0.018).let { (it * 100.0).roundToInt() / 100.0 })
                    val bid = (mid - halfSpread).coerceAtLeast(0.01)
                    val ask = mid + halfSpread
                    val spreadPct = (ask - bid) / mid

                    val strikeBd = BigDecimal.valueOf(strike).setScale(2, RoundingMode.HALF_UP)
                    val occ = occSymbolFormatter.format(
                        root = occRoot,
                        expiration = expDate,
                        isCall = isCall,
                        strike = strikeBd
                    )

                    contracts.add(
                        ParsedOptionContract(
                            symbol = occ,
                            isCall = isCall,
                            strike = strike,
                            expirationDate = expDate,
                            dte = dte,
                            bid = bid,
                            ask = ask,
                            mid = mid,
                            last = mid,
                            openInterest = oi,
                            volume = vol,
                            iv = iv,
                            delta = g.delta,
                            gamma = g.gamma,
                            thetaPerDay = g.thetaPerDay,
                            vegaPer1Pct = g.vegaPer1PctIv,
                            rhoPer1Pct = g.rhoPer1PctRate,
                            intrinsicValue = g.intrinsicValue,
                            extrinsicValue = g.extrinsicValue,
                            spreadWidthPct = spreadPct,
                            greeksFromBlackScholesFallback = false
                        )
                    )
                }
            }
        }

        val atm30Iv = spec.frontIv
        val volRank = volRankCalculator.computeVolRank(dailyCloses, liveAtmIv30 = atm30Iv)

        val chain = ParsedOptionChain(
            symbol = displaySym,
            underlyingPrice = spec.spot,
            chainVolatility = atm30Iv,
            riskFreeRate = r,
            contracts = contracts,
            atm30DayIv = atm30Iv,
            fetchedAtEpochMs = nowEpochMs
        )

        return DemoSymbolData(
            chain = chain,
            volRank = volRank,
            sma50 = sma50
        )
    }

    private fun generateStrikeGrid(spot: Double, step: Double): List<Double> {
        val rawMin = ((spot * 0.68) / step).roundToInt() * step
        val rawMax = ((spot * 1.28) / step).roundToInt() * step
        val strikes = mutableListOf<Double>()
        var k = rawMin.coerceAtLeast(step)
        while (k <= rawMax + 1e-6) {
            strikes.add((k * 100.0).roundToInt() / 100.0)
            k += step
        }
        return strikes
    }

    private fun buildSynthetic252Closes(spot: Double, frontIv: Double, seed: Int): List<Double> {
        val dailySigma = (frontIv * 0.80) / kotlin.math.sqrt(252.0)
        val count = 253
        val series = DoubleArray(count)
        series[count - 1] = spot
        for (i in (count - 2) downTo 0) {
            val wave = sin((i + seed) * 0.27) * 0.9 + sin((i * 0.08) + seed) * 0.5
            val stepReturn = dailySigma * wave
            series[i] = (series[i + 1] / (1.0 + stepReturn)).coerceAtLeast(1.0)
        }
        return series.toList()
    }

    private fun syntheticSpecFor(symbol: String): DemoSpec {
        val hash = abs(symbol.hashCode())
        val spot = 75.0 + (hash % 240)
        val step = if (spot < 110.0) 2.5 else 5.0
        val frontIv = 0.24 + ((hash % 18) * 0.01)
        val backIv = (frontIv - 0.02).coerceAtLeast(0.18)
        return DemoSpec(
            spot = spot,
            frontIv = frontIv,
            backIv = backIv,
            divYield = 0.006,
            step = step,
            baseOi = 650
        )
    }

    companion object {
        private val PRESET_SPECS = mapOf(
            "SPY" to DemoSpec(spot = 585.0, frontIv = 0.21, backIv = 0.17, divYield = 0.012, step = 5.0, baseOi = 4500),
            "QQQ" to DemoSpec(spot = 505.0, frontIv = 0.25, backIv = 0.22, divYield = 0.006, step = 5.0, baseOi = 3800),
            "IWM" to DemoSpec(spot = 224.0, frontIv = 0.27, backIv = 0.225, divYield = 0.012, step = 2.0, baseOi = 2900),
            "XLK" to DemoSpec(spot = 232.0, frontIv = 0.26, backIv = 0.215, divYield = 0.007, step = 2.0, baseOi = 1400),
            "XLF" to DemoSpec(spot = 48.0, frontIv = 0.22, backIv = 0.195, divYield = 0.015, step = 1.0, baseOi = 2200),
            "AAPL" to DemoSpec(spot = 228.0, frontIv = 0.26, backIv = 0.24, divYield = 0.005, step = 2.5, baseOi = 3200),
            "MSFT" to DemoSpec(spot = 425.0, frontIv = 0.24, backIv = 0.23, divYield = 0.007, step = 5.0, baseOi = 2400),
            "NVDA" to DemoSpec(spot = 138.0, frontIv = 0.48, backIv = 0.42, divYield = 0.001, step = 2.0, baseOi = 4800)
        )
    }
}
