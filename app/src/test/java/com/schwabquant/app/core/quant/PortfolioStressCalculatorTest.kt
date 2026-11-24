package com.schwabquant.app.core.quant

import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.math.abs

class PortfolioStressCalculatorTest {

    private val blackScholes = BlackScholesEngine()
    private val betaCalculator = BetaWeightingCalculator(blackScholes)
    private val stressCalculator = PortfolioStressCalculator(blackScholes)

    @Test
    fun `zeroShockInvariant returns exact zero PnL with 424-DTE ITM LEAPS fixture`() {
        val positions = listOf(
            Position(
                symbol = "NVDA",
                underlyingSymbol = "NVDA",
                category = AssetCategory.EQUITY,
                quantity = BigDecimal("22"),
                averageCost = BigDecimal("136.50"),
                markPrice = BigDecimal("142.00"),
                marketValue = BigDecimal("3124.00"),
                unrealizedPnl = BigDecimal("121.00"),
                unrealizedPnlPercent = 3.87,
                delta = 1.0,
                betaToSpy = 1.65,
                underlyingSpot = 142.0
            ),
            Position(
                symbol = "NVDA  280121C00085000",
                underlyingSymbol = "NVDA",
                category = AssetCategory.OPTION,
                quantity = BigDecimal("1"),
                averageCost = BigDecimal("71.9066"),
                markPrice = BigDecimal("71.9066"),
                marketValue = BigDecimal("7190.66"),
                unrealizedPnl = BigDecimal.ZERO,
                unrealizedPnlPercent = 0.0,
                delta = 0.90,
                betaToSpy = 1.65,
                isCall = true,
                strike = 85.0,
                daysToExpiration = 424,
                underlyingSpot = 142.0,
                impliedVolatility = 0.48
            ),
            Position(
                symbol = "NVDA  280121C00165000",
                underlyingSymbol = "NVDA",
                category = AssetCategory.OPTION,
                quantity = BigDecimal("1"),
                averageCost = BigDecimal("37.6266"),
                markPrice = BigDecimal("37.6266"),
                marketValue = BigDecimal("3762.66"),
                unrealizedPnl = BigDecimal.ZERO,
                unrealizedPnlPercent = 0.0,
                delta = 0.55,
                betaToSpy = 1.65,
                isCall = true,
                strike = 165.0,
                daysToExpiration = 424,
                underlyingSpot = 142.0,
                impliedVolatility = 0.45
            ),
            Position(
                symbol = "NVDA  261218C00152000",
                underlyingSymbol = "NVDA",
                category = AssetCategory.OPTION,
                quantity = BigDecimal("-1"),
                averageCost = BigDecimal("3.4933"),
                markPrice = BigDecimal("3.4933"),
                marketValue = BigDecimal("-349.33"),
                unrealizedPnl = BigDecimal.ZERO,
                unrealizedPnlPercent = 0.0,
                delta = 0.70,
                betaToSpy = 1.65,
                isCall = true,
                strike = 152.0,
                daysToExpiration = 21,
                underlyingSpot = 142.0,
                impliedVolatility = 0.52
            ),
            Position(
                symbol = "NVDA  261218C00165000",
                underlyingSymbol = "NVDA",
                category = AssetCategory.OPTION,
                quantity = BigDecimal("-1"),
                averageCost = BigDecimal("2.7833"),
                markPrice = BigDecimal("2.7833"),
                marketValue = BigDecimal("-278.33"),
                unrealizedPnl = BigDecimal.ZERO,
                unrealizedPnlPercent = 0.0,
                delta = 0.45,
                betaToSpy = 1.65,
                isCall = true,
                strike = 165.0,
                daysToExpiration = 21,
                underlyingSpot = 142.0,
                impliedVolatility = 0.50
            )
        )

        val zeroResult = stressCalculator.runStress(
            positions = positions,
            accountNetLiq = 15_000.0,
            spySpot = 585.0,
            spyShock = 0.0,
            ivShift = 0.0,
            ivMode = StressIvMode.TERM_STRUCTURE_SQRT
        )
        assertTrue(
            abs(zeroResult.portfolioDollarPnl) < 0.01,
            "Zero-shock P&L must be 0.00 but was ${zeroResult.portfolioDollarPnl}"
        )

        val summary = stressCalculator.computeStressSummary(
            positions = positions,
            accountNetLiq = 15_000.0,
            spySpot = 585.0,
            spyBetaDelta = betaCalculator.calculateSpyBetaWeightedDelta(positions, 585.0),
            includeVolSkewShock = true,
            ivMode = StressIvMode.TERM_STRUCTURE_SQRT
        )
        assertTrue(abs(summary.zeroShockPnl) < 0.01)
    }

    @Test
    fun `termStructureVsParallelIvScaling dampens 424-DTE LEAPS IV shock`() {
        val leapsMark = blackScholes.price(
            isCall = true,
            spot = 142.0,
            strike = 120.0,
            timeYears = 424.0 / 365.0,
            rate = BlackScholesEngine.DEFAULT_RISK_FREE_RATE,
            divYield = 0.0,
            iv = 0.45
        )
        val leapsOnly = listOf(
            Position(
                symbol = "NVDA  280121C00120000",
                underlyingSymbol = "NVDA",
                category = AssetCategory.OPTION,
                quantity = BigDecimal("1"),
                averageCost = BigDecimal.valueOf(leapsMark),
                markPrice = BigDecimal.valueOf(leapsMark),
                marketValue = BigDecimal.valueOf(leapsMark * 100.0),
                unrealizedPnl = BigDecimal.ZERO,
                unrealizedPnlPercent = 0.0,
                delta = 0.78,
                betaToSpy = 1.65,
                isCall = true,
                strike = 120.0,
                daysToExpiration = 424,
                underlyingSpot = 142.0,
                impliedVolatility = 0.45
            )
        )

        val termIvOnly = stressCalculator.runStress(
            positions = leapsOnly,
            accountNetLiq = 10_000.0,
            spySpot = 585.0,
            spyShock = 0.0,
            ivShift = 0.08,
            ivMode = StressIvMode.TERM_STRUCTURE_SQRT
        )
        val parallelIvOnly = stressCalculator.runStress(
            positions = leapsOnly,
            accountNetLiq = 10_000.0,
            spySpot = 585.0,
            spyShock = 0.0,
            ivShift = 0.08,
            ivMode = StressIvMode.PARALLEL
        )

        assertTrue(termIvOnly.portfolioDollarPnl > 0.0)
        assertTrue(
            termIvOnly.portfolioDollarPnl < parallelIvOnly.portfolioDollarPnl * 0.5,
            "424-DTE sqrt(30/424) (~0.266) scaling must yield strictly smaller IV P&L than Parallel: " +
                "term=${termIvOnly.portfolioDollarPnl} vs parallel=${parallelIvOnly.portfolioDollarPnl}"
        )
    }

    @Test
    fun `negativeSkewAndUpsideCappedAlerts fire when ITM short calls compress upside below 50 pct`() {
        val spot = 150.0
        val spySpot = 500.0
        // Deep LEAPS +2 vs ATM/ITM short calls -2 at strike 135 (cap upside heavily while retaining positive spot delta)
        val leapsMark = blackScholes.price(true, spot, 100.0, 424.0 / 365.0, iv = 0.35)
        val shortMark = blackScholes.price(true, spot, 135.0, 21.0 / 365.0, iv = 0.40)

        val cappedPositions = listOf(
            Position(
                symbol = "NVDA",
                underlyingSymbol = "NVDA",
                category = AssetCategory.EQUITY,
                quantity = BigDecimal("100"),
                averageCost = BigDecimal("140.0"),
                markPrice = BigDecimal.valueOf(spot),
                marketValue = BigDecimal.valueOf(100.0 * spot),
                unrealizedPnl = BigDecimal.ZERO,
                unrealizedPnlPercent = 0.0,
                delta = 1.0,
                betaToSpy = 1.5,
                underlyingSpot = spot
            ),
            Position(
                symbol = "NVDA  280121C00100000",
                underlyingSymbol = "NVDA",
                category = AssetCategory.OPTION,
                quantity = BigDecimal("2"),
                averageCost = BigDecimal.valueOf(leapsMark),
                markPrice = BigDecimal.valueOf(leapsMark),
                marketValue = BigDecimal.valueOf(leapsMark * 200.0),
                unrealizedPnl = BigDecimal.ZERO,
                unrealizedPnlPercent = 0.0,
                delta = 0.85,
                betaToSpy = 1.5,
                isCall = true,
                strike = 100.0,
                daysToExpiration = 424,
                underlyingSpot = spot,
                impliedVolatility = 0.35
            ),
            Position(
                symbol = "NVDA  261218C00135000",
                underlyingSymbol = "NVDA",
                category = AssetCategory.OPTION,
                quantity = BigDecimal("-3"),
                averageCost = BigDecimal.valueOf(shortMark),
                markPrice = BigDecimal.valueOf(shortMark),
                marketValue = BigDecimal.valueOf(-shortMark * 300.0),
                unrealizedPnl = BigDecimal.ZERO,
                unrealizedPnlPercent = 0.0,
                delta = 0.72,
                betaToSpy = 1.5,
                isCall = true,
                strike = 135.0,
                daysToExpiration = 21,
                underlyingSpot = spot,
                impliedVolatility = 0.40
            )
        )

        val spyBetaDelta = betaCalculator.calculateSpyBetaWeightedDelta(cappedPositions, spySpot)
        assertTrue(spyBetaDelta > 0.0, "Expected net positive SPY beta delta, got $spyBetaDelta")

        val summary = stressCalculator.computeStressSummary(
            positions = cappedPositions,
            accountNetLiq = 25_000.0,
            spySpot = spySpot,
            spyBetaDelta = spyBetaDelta,
            includeVolSkewShock = true,
            ivMode = StressIvMode.TERM_STRUCTURE_SQRT
        )

        assertNotNull(summary.payoffSkew15)
        assertNotNull(summary.upsideCapture5)
        assertTrue(summary.isNegativeSkew, "Expected NEGATIVE_SKEW, got skew=${summary.payoffSkew15}")
        assertTrue(summary.isUpsideCapped, "Expected UPSIDE_CAPPED, got capture=${summary.upsideCapture5}")
    }

    @Test
    fun `solveImpliedVolatilityRoundTripAndIntrinsicFloor`() {
        val targetIv = 0.62
        val price = blackScholes.price(
            isCall = true,
            spot = 142.0,
            strike = 152.0,
            timeYears = 21.0 / 365.0,
            rate = 0.045,
            divYield = 0.002,
            iv = targetIv
        )
        val solved = blackScholes.solveImpliedVolatility(
            marketPrice = price,
            spot = 142.0,
            strike = 152.0,
            timeYears = 21.0 / 365.0,
            rate = 0.045,
            dividendYield = 0.002,
            isCall = true
        )
        assertNotNull(solved)
        assertEquals(targetIv, solved!!, 1e-5)

        // Deep-ITM price below discounted intrinsic floor returns null cleanly without diverging
        val noRoot = blackScholes.solveImpliedVolatility(
            marketPrice = 50.0,
            spot = 142.0,
            strike = 85.0,
            timeYears = 424.0 / 365.0,
            rate = 0.045,
            dividendYield = 0.0,
            isCall = true
        )
        assertNull(noRoot)
        assertFalse(solved.isNaN())
    }
}
