package com.schwabquant.app.core.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class OptionsChainBoardBuilderTest {

    private val builder = OptionsChainBoardBuilder()

    @Test
    fun `groups expirations computes ToS expected move ITM flags and slices strike windows`() {
        val spot = 100.0
        val expMonthly = LocalDate.of(2026, 12, 18) // 3rd Friday
        val expWeekly = LocalDate.of(2026, 12, 4)
        val expLeaps = LocalDate.of(2027, 9, 17)

        val strikes = (80..120 step 2).map { it.toDouble() } // 21 strikes; spot = 100.0 at index 10
        val contracts = mutableListOf<ParsedOptionContract>()

        for (strike in strikes) {
            contracts.add(contract("OPT_M_C_$strike", true, strike, expMonthly, 28, mid = if (strike == 100.0) 4.0 else 2.0, iv = 0.25))
            contracts.add(contract("OPT_M_P_$strike", false, strike, expMonthly, 28, mid = if (strike == 100.0) 6.0 else 2.0, iv = 0.27))
        }
        // Fallback section with call mid == 0 (forces IV expected-move fallback)
        contracts.add(contract("OPT_W_C_100", true, 100.0, expWeekly, 14, mid = 0.0, iv = 0.30))
        contracts.add(contract("OPT_W_P_100", false, 100.0, expWeekly, 14, mid = 2.0, iv = 0.30))
        // LEAPS
        contracts.add(contract("OPT_L_C_100", true, 100.0, expLeaps, 300, mid = 18.0, iv = 0.24))

        val chain = ParsedOptionChain(
            symbol = "SPY",
            underlyingPrice = spot,
            chainVolatility = 0.26,
            riskFreeRate = 0.045,
            contracts = contracts,
            atm30DayIv = 0.26,
            fetchedAtEpochMs = 1_000L
        )

        val boardAll = builder.buildBoard(chain, StrikeWindowFilter.ALL)
        assertEquals(3, boardAll.sections.size)
        assertEquals(expWeekly, boardAll.sections[0].expirationDate)
        assertEquals(ExpirationCycleTag.WEEKLY, boardAll.sections[0].cycleTag)
        assertEquals(expMonthly, boardAll.sections[1].expirationDate)
        assertEquals(ExpirationCycleTag.MONTHLY, boardAll.sections[1].cycleTag)
        assertEquals(expLeaps, boardAll.sections[2].expirationDate)
        assertEquals(ExpirationCycleTag.LEAPS, boardAll.sections[2].cycleTag)

        // Monthly ToS EM = 0.85 * (4.0 + 6.0) = 8.50
        val monthlySec = boardAll.sections[1]
        assertEquals(8.50, monthlySec.expectedMoveDollars, 1e-6)
        assertEquals(8.50, monthlySec.expectedMovePct, 1e-6)
        assertEquals(26.0, monthlySec.atmIvPct, 1e-6)
        assertEquals(21, monthlySec.rows.size)

        // Verify ITM & ATM boundary flags
        val callItmRow = monthlySec.rows.first { it.strike == 98.0 }
        assertTrue(callItmRow.isCallItm)
        assertFalse(callItmRow.isPutItm)
        val putItmRow = monthlySec.rows.first { it.strike == 102.0 }
        assertFalse(putItmRow.isCallItm)
        assertTrue(putItmRow.isPutItm)
        assertTrue(monthlySec.rows.first { it.strike == 100.0 }.isAtmBoundary)

        // Window slicing: ±6 -> max 13 strikes centered on 100
        val board6 = builder.buildBoard(chain, StrikeWindowFilter.PM_6)
        val sliced6 = board6.sections[1]
        assertEquals(13, sliced6.rows.size)
        assertEquals(88.0, sliced6.rows.first().strike, 1e-6)
        assertEquals(112.0, sliced6.rows.last().strike, 1e-6)
        assertEquals(21, sliced6.totalStrikeCount)

        // Fallback expected move for weekly section (mid=0 on call -> spot * 0.30 * sqrt(14/365))
        val expectedFallback = 100.0 * 0.30 * kotlin.math.sqrt(14.0 / 365.0)
        assertEquals(expectedFallback, boardAll.sections[0].expectedMoveDollars, 1e-4)
    }

    private fun contract(
        sym: String,
        isCall: Boolean,
        strike: Double,
        exp: LocalDate,
        dte: Int,
        mid: Double,
        iv: Double
    ) = ParsedOptionContract(
        symbol = sym,
        isCall = isCall,
        strike = strike,
        expirationDate = exp,
        dte = dte,
        bid = (mid - 0.05).coerceAtLeast(0.0),
        ask = if (mid > 0.0) mid + 0.05 else 0.05,
        mid = mid,
        last = mid,
        openInterest = 500,
        volume = 120,
        iv = iv,
        delta = if (isCall) 0.50 else -0.50,
        gamma = 0.03,
        thetaPerDay = -0.04,
        vegaPer1Pct = 0.12,
        rhoPer1Pct = 0.02,
        intrinsicValue = 0.0,
        extrinsicValue = mid,
        spreadWidthPct = 0.05,
        greeksFromBlackScholesFallback = false
    )
}
