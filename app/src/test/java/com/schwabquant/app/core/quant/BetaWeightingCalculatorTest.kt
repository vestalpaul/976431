package com.schwabquant.app.core.quant

import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BetaWeightingCalculatorTest {

    private val blackScholes = BlackScholesEngine()
    private val calculator = BetaWeightingCalculator(blackScholes)

    @Test
    fun `computes multi-ticker equity and option SPY beta-weighted delta`() {
        val spySpot = 500.0

        // 100 shares of GOOG at $250 with beta 1.2 -> 100 * 1.2 * (250 / 500) = 60.0 SPY-delta
        val googShares = Position(
            symbol = "GOOG",
            underlyingSymbol = "GOOG",
            category = AssetCategory.EQUITY,
            quantity = BigDecimal("100"),
            averageCost = BigDecimal("240.00"),
            markPrice = BigDecimal("250.00"),
            marketValue = BigDecimal("25000.00"),
            unrealizedPnl = BigDecimal("1000.00"),
            unrealizedPnlPercent = 4.17,
            delta = 1.0,
            betaToSpy = 1.2,
            underlyingSpot = 250.0
        )

        // +2 IWM call contracts (delta 0.75) at IWM $200, beta 1.15 -> 2 * 0.75 * 100 * 1.15 * (200 / 500) = 69.0
        val iwmCall = Position(
            symbol = "IWM   280121C00180000",
            underlyingSymbol = "IWM",
            category = AssetCategory.OPTION,
            quantity = BigDecimal("2"),
            averageCost = BigDecimal("35.00"),
            markPrice = BigDecimal("40.00"),
            marketValue = BigDecimal("8000.00"),
            unrealizedPnl = BigDecimal("1000.00"),
            unrealizedPnlPercent = 14.29,
            delta = 0.75,
            betaToSpy = 1.15,
            isCall = true,
            strike = 180.0,
            daysToExpiration = 400,
            underlyingSpot = 200.0,
            impliedVolatility = 0.25
        )

        val totalBwd = calculator.calculateSpyBetaWeightedDelta(listOf(googShares, iwmCall), spySpot)
        assertEquals(129.0, totalBwd, 1e-6)
    }

    @Test
    fun `computes 6-scenario stress matrix and models vol skew shock difference`() {
        val spySpot = 500.0
        val callMark = blackScholes.price(
            isCall = true,
            spot = 200.0,
            strike = 190.0,
            timeYears = 1.0,
            divYield = 0.0,
            iv = 0.25
        )
        val longCall = Position(
            symbol = "IWM   280121C00190000",
            underlyingSymbol = "IWM",
            category = AssetCategory.OPTION,
            quantity = BigDecimal("2"),
            averageCost = BigDecimal.valueOf(callMark),
            markPrice = BigDecimal.valueOf(callMark),
            marketValue = BigDecimal.valueOf(callMark * 200.0),
            unrealizedPnl = BigDecimal.ZERO,
            unrealizedPnlPercent = 0.0,
            delta = 0.68,
            betaToSpy = 1.10,
            isCall = true,
            strike = 190.0,
            daysToExpiration = 365,
            underlyingSpot = 200.0,
            impliedVolatility = 0.25
        )

        val withVolSkew = calculator.computeStressMatrix(
            positions = listOf(longCall),
            accountNetLiq = 10_000.0,
            spyPrice = spySpot,
            includeVolSkewShock = true
        )
        val withoutVolSkew = calculator.computeStressMatrix(
            positions = listOf(longCall),
            accountNetLiq = 10_000.0,
            spyPrice = spySpot,
            includeVolSkewShock = false
        )

        assertEquals(listOf(-15.0, -10.0, -5.0, 5.0, 10.0, 15.0), withVolSkew.map { it.spyShockPercent })

        // On -15% SPY shock (+8% IV expansion), long-vega call loses LESS with vol skew shock enabled
        val down15Skew = withVolSkew.first().portfolioDollarPnl
        val down15Flat = withoutVolSkew.first().portfolioDollarPnl
        assertTrue(down15Skew > down15Flat, "Expected vol expansion to cushion long call on -15% crash")

        // On +15% SPY rally (-3% IV compression), long-vega call gains LESS with vol compression
        val up15Skew = withVolSkew.last().portfolioDollarPnl
        val up15Flat = withoutVolSkew.last().portfolioDollarPnl
        assertTrue(up15Skew < up15Flat)
    }
}
