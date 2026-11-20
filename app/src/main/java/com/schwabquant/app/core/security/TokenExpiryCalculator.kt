package com.schwabquant.app.core.security

import java.util.concurrent.TimeUnit

enum class TokenExpiryTier {
    NOT_CONNECTED,
    HEALTHY,
    DAY_5_WARNING,
    DAY_6_CRITICAL,
    EXPIRED
}

data class TokenExpiryEvaluation(
    val tier: TokenExpiryTier,
    val remainingMillis: Long,
    val expiresAtEpochMs: Long,
    val formattedCountdown: String
)

/**
 * Pure deterministic calculator for Schwab's 30-minute access token (25-minute proactive refresh)
 * and 7-day refresh token hard cliff per SCHWAB_API_REFERENCE.md Section 1.
 */
object TokenExpiryCalculator {

    const val REFRESH_TOKEN_LIFETIME_MS: Long = 7L * 24L * 60L * 60L * 1_000L
    const val DAY_5_WARNING_THRESHOLD_MS: Long = 48L * 60L * 60L * 1_000L
    const val DAY_6_CRITICAL_THRESHOLD_MS: Long = 24L * 60L * 60L * 1_000L
    const val PROACTIVE_REFRESH_WINDOW_MS: Long = 5L * 60L * 1_000L

    fun evaluateRefreshToken(
        refreshTokenIssuedAtEpochMs: Long,
        nowEpochMs: Long
    ): TokenExpiryEvaluation {
        if (refreshTokenIssuedAtEpochMs <= 0L) {
            return TokenExpiryEvaluation(
                tier = TokenExpiryTier.NOT_CONNECTED,
                remainingMillis = 0L,
                expiresAtEpochMs = 0L,
                formattedCountdown = "Not Connected"
            )
        }

        val expiresAt = refreshTokenIssuedAtEpochMs + REFRESH_TOKEN_LIFETIME_MS
        val remaining = expiresAt - nowEpochMs

        if (remaining <= 0L) {
            return TokenExpiryEvaluation(
                tier = TokenExpiryTier.EXPIRED,
                remainingMillis = 0L,
                expiresAtEpochMs = expiresAt,
                formattedCountdown = "Expired"
            )
        }

        val tier = when {
            remaining <= DAY_6_CRITICAL_THRESHOLD_MS -> TokenExpiryTier.DAY_6_CRITICAL
            remaining <= DAY_5_WARNING_THRESHOLD_MS -> TokenExpiryTier.DAY_5_WARNING
            else -> TokenExpiryTier.HEALTHY
        }

        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(remaining)
        val totalHours = totalMinutes / 60L
        val days = totalHours / 24L
        val remHours = totalHours % 24L
        val remMinutes = totalMinutes % 60L

        val formatted = when {
            days > 0L && remaining > DAY_6_CRITICAL_THRESHOLD_MS -> "${days}d ${remHours}h"
            totalHours > 0L -> "${totalHours}h ${remMinutes}m"
            remMinutes > 0L -> "${remMinutes}m"
            else -> "<1m"
        }

        return TokenExpiryEvaluation(
            tier = tier,
            remainingMillis = remaining,
            expiresAtEpochMs = expiresAt,
            formattedCountdown = formatted
        )
    }

    fun shouldProactivelyRefreshAccessToken(
        accessTokenExpiresAtEpochMs: Long,
        nowEpochMs: Long
    ): Boolean {
        if (accessTokenExpiresAtEpochMs <= 0L) return false
        val remaining = accessTokenExpiresAtEpochMs - nowEpochMs
        return remaining <= PROACTIVE_REFRESH_WINDOW_MS
    }
}
