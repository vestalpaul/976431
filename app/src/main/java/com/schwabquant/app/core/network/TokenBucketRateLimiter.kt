package com.schwabquant.app.core.network

import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Client-side token bucket enforcing Schwab's strict 120 requests/minute REST API rate limit
 * per SCHWAB_API_REFERENCE.md Section 1.
 */
@Singleton
class TokenBucketRateLimiter @Inject constructor() {

    private val capacity: Double = MAX_REQUESTS_PER_MINUTE.toDouble()
    private val refillPerNano: Double = MAX_REQUESTS_PER_MINUTE.toDouble() / 60_000_000_000.0

    private var availableTokens: Double = capacity
    private var lastRefillNanos: Long = System.nanoTime()

    suspend fun acquire() {
        while (true) {
            val waitMillis = tryConsumeOrWaitMillis()
            if (waitMillis <= 0L) return
            delay(waitMillis)
        }
    }

    fun acquireBlocking() {
        while (true) {
            val waitMillis = tryConsumeOrWaitMillis()
            if (waitMillis <= 0L) return
            Thread.sleep(waitMillis)
        }
    }

    @Synchronized
    private fun tryConsumeOrWaitMillis(): Long {
        refill()
        if (availableTokens >= 1.0) {
            availableTokens -= 1.0
            return 0L
        }
        val missingTokens = 1.0 - availableTokens
        val nanosToWait = missingTokens / refillPerNano
        return ceil(nanosToWait / 1_000_000.0).toLong().coerceAtLeast(10L)
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedNanos = (now - lastRefillNanos).coerceAtLeast(0L)
        if (elapsedNanos > 0L) {
            availableTokens = (availableTokens + elapsedNanos * refillPerNano).coerceAtMost(capacity)
            lastRefillNanos = now
        }
    }

    companion object {
        const val MAX_REQUESTS_PER_MINUTE = 120
    }
}
