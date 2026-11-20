package com.schwabquant.app.core.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp

class BlackScholesEngineTest {

    private val engine = BlackScholesEngine()

    @Test
    fun `matches textbook Black-Scholes call, put, and analytical Greeks`() {
        val call = engine.greeks(
            isCall = true,
            spot = 100.0,
            strike = 100.0,
            timeYears = 1.0,
            rate = 0.05,
            divYield = 0.0,
            iv = 0.20
        )
        val put = engine.greeks(
            isCall = false,
            spot = 100.0,
            strike = 100.0,
            timeYears = 1.0,
            rate = 0.05,
            divYield = 0.0,
            iv = 0.20
        )

        assertEquals(10.4506, call.price, 1e-4)
        assertEquals(5.5735, put.price, 1e-4)
        assertEquals(0.6368, call.delta, 1e-4)
        assertEquals(-0.3632, put.delta, 1e-4)
        assertEquals(0.01876, call.gamma, 1e-5)
        assertEquals(call.gamma, put.gamma, 1e-9)
        assertEquals(0.37524, call.vegaPer1PctIv, 1e-4)
        assertEquals(-0.01757, call.thetaPerDay, 1e-4)
        assertEquals(0.5323, call.rhoPer1PctRate, 1e-4)
    }

    @Test
    fun `satisfies put-call parity with continuous dividend yield q`() {
        val spot = 224.0
        val strike = 220.0
        val t = 0.75
        val r = 0.045
        val q = 0.015
        val iv = 0.28

        val call = engine.price(true, spot, strike, t, r, q, iv)
        val put = engine.price(false, spot, strike, t, r, q, iv)
        val theoreticalParity = spot * exp(-q * t) - strike * exp(-r * t)

        assertEquals(theoreticalParity, call - put, 1e-8)
    }

    @Test
    fun `round-trips implied volatility across ITM, ATM, OTM and rejects arbitrage violations`() {
        val spot = 142.0
        val t = 1.2
        val r = 0.045
        val q = 0.003
        val targetIv = 0.48

        listOf(110.0, 142.0, 180.0).forEach { strike ->
            listOf(true, false).forEach { isCall ->
                val price = engine.price(isCall, spot, strike, t, r, q, targetIv)
                val solvedIv = engine.impliedVolatility(isCall, price, spot, strike, t, r, q)
                assertNotNull(solvedIv, "Expected valid IV for strike=$strike isCall=$isCall")
                assertEquals(targetIv, solvedIv!!, 1e-5)
                assertTrue(solvedIv.isFinite())
            }
        }

        // Below intrinsic arbitrage bound -> null (never NaN/Infinity)
        assertNull(engine.impliedVolatility(true, marketPrice = 10.0, spot = 150.0, strike = 120.0, timeYears = 0.5))
        // Zero / negative market price -> null
        assertNull(engine.impliedVolatility(false, marketPrice = 0.0, spot = 100.0, strike = 100.0, timeYears = 0.5))
        // Above upper bound -> null
        assertNull(engine.impliedVolatility(true, marketPrice = 160.0, spot = 150.0, strike = 120.0, timeYears = 0.5))
    }

    @Test
    fun `handles expiration boundary timeYears zero cleanly`() {
        val itmCall = engine.greeks(true, spot = 110.0, strike = 100.0, timeYears = 0.0, iv = 0.30)
        assertEquals(10.0, itmCall.price, 1e-9)
        assertEquals(1.0, itmCall.delta, 1e-9)
        assertEquals(0.0, itmCall.gamma, 1e-9)
        assertEquals(0.0, itmCall.thetaPerDay, 1e-9)
        assertEquals(0.0, itmCall.vegaPer1PctIv, 1e-9)
        assertEquals(0.0, itmCall.extrinsicValue, 1e-9)

        val otmPut = engine.greeks(false, spot = 110.0, strike = 100.0, timeYears = 0.0, iv = 0.30)
        assertEquals(0.0, otmPut.price, 1e-9)
        assertEquals(0.0, otmPut.delta, 1e-9)
    }
}
