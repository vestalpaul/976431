package com.schwabquant.app.core.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenExpiryCalculatorTest {

    private val issuedAt = 1_700_000_000_000L
    private val hourMs = 60L * 60L * 1_000L
    private val minuteMs = 60L * 1_000L

    @Test
    fun `returns NOT_CONNECTED when refresh token issuedAt is zero`() {
        val eval = TokenExpiryCalculator.evaluateRefreshToken(
            refreshTokenIssuedAtEpochMs = 0L,
            nowEpochMs = issuedAt
        )
        assertEquals(TokenExpiryTier.NOT_CONNECTED, eval.tier)
        assertEquals("Not Connected", eval.formattedCountdown)
    }

    @Test
    fun `evaluates HEALTHY, DAY_5_WARNING, DAY_6_CRITICAL, and EXPIRED transitions cleanly`() {
        val expiresAt = issuedAt + TokenExpiryCalculator.REFRESH_TOKEN_LIFETIME_MS

        // Just above 48h remaining -> HEALTHY (Day 4 / early Day 5)
        val healthy = TokenExpiryCalculator.evaluateRefreshToken(issuedAt, expiresAt - (48L * hourMs + 1L))
        assertEquals(TokenExpiryTier.HEALTHY, healthy.tier)

        // Exactly 48h remaining -> DAY_5_WARNING
        val day5Boundary = TokenExpiryCalculator.evaluateRefreshToken(issuedAt, expiresAt - 48L * hourMs)
        assertEquals(TokenExpiryTier.DAY_5_WARNING, day5Boundary.tier)
        assertEquals("2d 0h", day5Boundary.formattedCountdown)

        // 24h + 1ms remaining -> still DAY_5_WARNING
        val day5Low = TokenExpiryCalculator.evaluateRefreshToken(issuedAt, expiresAt - (24L * hourMs + 1L))
        assertEquals(TokenExpiryTier.DAY_5_WARNING, day5Low.tier)

        // Exactly 24h remaining -> DAY_6_CRITICAL
        val day6Boundary = TokenExpiryCalculator.evaluateRefreshToken(issuedAt, expiresAt - 24L * hourMs)
        assertEquals(TokenExpiryTier.DAY_6_CRITICAL, day6Boundary.tier)
        assertEquals("24h 0m", day6Boundary.formattedCountdown)

        // 30 minutes remaining -> DAY_6_CRITICAL
        val finalMinutes = TokenExpiryCalculator.evaluateRefreshToken(issuedAt, expiresAt - 30L * minuteMs)
        assertEquals(TokenExpiryTier.DAY_6_CRITICAL, finalMinutes.tier)
        assertEquals("30m", finalMinutes.formattedCountdown)

        // At cliff & past cliff -> EXPIRED
        val expiredExact = TokenExpiryCalculator.evaluateRefreshToken(issuedAt, expiresAt)
        assertEquals(TokenExpiryTier.EXPIRED, expiredExact.tier)
        assertEquals("Expired", expiredExact.formattedCountdown)

        val expiredPast = TokenExpiryCalculator.evaluateRefreshToken(issuedAt, expiresAt + hourMs)
        assertEquals(TokenExpiryTier.EXPIRED, expiredPast.tier)
        assertEquals(0L, expiredPast.remainingMillis)
    }

    @Test
    fun `triggers proactive access-token refresh at minute 25 of 30 (5 minutes remaining)`() {
        val accessExpiresAt = issuedAt + 30L * minuteMs

        // 5m 01s remaining -> do not refresh yet
        assertFalse(
            TokenExpiryCalculator.shouldProactivelyRefreshAccessToken(
                accessTokenExpiresAtEpochMs = accessExpiresAt,
                nowEpochMs = accessExpiresAt - (5L * minuteMs + 1L)
            )
        )

        // Exactly 5m 00s remaining (minute 25) -> proactively refresh
        assertTrue(
            TokenExpiryCalculator.shouldProactivelyRefreshAccessToken(
                accessTokenExpiresAtEpochMs = accessExpiresAt,
                nowEpochMs = accessExpiresAt - 5L * minuteMs
            )
        )

        // Already expired -> proactively refresh
        assertTrue(
            TokenExpiryCalculator.shouldProactivelyRefreshAccessToken(
                accessTokenExpiresAtEpochMs = accessExpiresAt,
                nowEpochMs = accessExpiresAt + 1_000L
            )
        )
    }
}
