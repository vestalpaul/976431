package com.schwabquant.app.core.network

import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.TokenExpiryCalculator
import com.schwabquant.app.data.remote.api.SchwabAuthApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Thread-safe single-flight OAuth token refresh coordinator shared by proactive
 * interceptor refreshes (minute 25 of 30) and reactive 401 authenticator retries.
 * Uses a dedicated @AuthOkHttpClient-backed SchwabAuthApi so refreshes never recurse.
 */
@Singleton
class SchwabTokenRefreshCoordinator(
    private val credentialVault: CredentialVault,
    private val authApiProvider: Provider<SchwabAuthApi>,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {

    @Inject
    constructor(
        credentialVault: CredentialVault,
        authApiProvider: Provider<SchwabAuthApi>
    ) : this(credentialVault, authApiProvider, System::currentTimeMillis)

    private val refreshLock = Any()
    private val sessionMutationCounter = AtomicLong(0L)
    private var lastFailedRefreshToken: String? = null
    private var lastFailedVaultGeneration: Long = -1L
    private var lastFailedMutationCounter: Long = -1L
    private var lastFailureEpochMs: Long = Long.MIN_VALUE
    private var permanentlyInvalidRefreshToken: String? = null
    private var permanentlyInvalidVaultGeneration: Long = -1L
    private var permanentlyInvalidMutationCounter: Long = -1L

    fun <T> withRefreshLock(block: () -> T): T = synchronized(refreshLock) {
        block()
    }

    fun recordSessionMutation() {
        sessionMutationCounter.incrementAndGet()
    }

    fun buildBasicAuthHeader(appKey: String, appSecret: String): String {
        val raw = "$appKey:$appSecret".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    /**
     * Proactively refreshes the access token when it is within 5 minutes of expiring
     * (minute 25 of Schwab's 30-minute window).
     */
    fun refreshIfNeededForProactiveWindow(): String? {
        val now = nowProvider()
        val expiresAt = credentialVault.getAccessTokenExpiresAtEpochMs()
        if (!TokenExpiryCalculator.shouldProactivelyRefreshAccessToken(expiresAt, now)) {
            return credentialVault.getAccessToken()
        }

        synchronized(refreshLock) {
            val recheckedNow = nowProvider()
            val recheckedExpiresAt = credentialVault.getAccessTokenExpiresAtEpochMs()
            if (!TokenExpiryCalculator.shouldProactivelyRefreshAccessToken(recheckedExpiresAt, recheckedNow)) {
                return credentialVault.getAccessToken()
            }
            return performRefreshLocked().getOrNull() ?: credentialVault.getAccessToken()
        }
    }

    /**
     * Single-flight 401 refresh: concurrent threads blocked on the failed token reuse
     * the newly refreshed access token instead of firing duplicate /v1/oauth/token calls.
     */
    fun refreshOnUnauthorized(failedAccessToken: String?): String? {
        synchronized(refreshLock) {
            val currentToken = credentialVault.getAccessToken()
            if (!currentToken.isNullOrBlank() && currentToken != failedAccessToken) {
                return currentToken
            }
            return performRefreshLocked().getOrNull()
        }
    }

    fun refreshNow(): Result<String> = synchronized(refreshLock) {
        performRefreshLocked()
    }

    private fun performRefreshLocked(): Result<String> {
        val mutationBefore = sessionMutationCounter.get()
        val vaultGenBefore = credentialVault.getVaultGeneration()
        val refreshToken = credentialVault.getRefreshToken()
            ?: return Result.failure(IllegalStateException("No Schwab refresh token in vault."))

        if (permanentlyInvalidRefreshToken == refreshToken &&
            permanentlyInvalidVaultGeneration == vaultGenBefore &&
            permanentlyInvalidMutationCounter == mutationBefore
        ) {
            return Result.failure(
                IllegalStateException(
                    "Schwab refresh token was rejected with invalid_grant; re-authorize via OAuth."
                )
            )
        }

        val attemptNowMs = nowProvider()
        if (lastFailedRefreshToken == refreshToken &&
            lastFailedVaultGeneration == vaultGenBefore &&
            lastFailedMutationCounter == mutationBefore &&
            lastFailureEpochMs != Long.MIN_VALUE &&
            (attemptNowMs - lastFailureEpochMs) in 0L until FAILURE_COOLDOWN_MS
        ) {
            return Result.failure(
                IllegalStateException("Schwab token refresh is in 15s failure cooldown.")
            )
        }

        val appKey = credentialVault.getAppKey()
            ?: return Result.failure(IllegalStateException("Schwab App Key not configured."))
        val appSecret = credentialVault.getAppSecret()
            ?: return Result.failure(IllegalStateException("Schwab App Secret not configured."))

        val basicAuth = buildBasicAuthHeader(appKey, appSecret)
        val response = try {
            runBlocking {
                authApiProvider.get().refreshToken(
                    basicAuth = basicAuth,
                    refreshToken = refreshToken
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (sessionMutationCounter.get() == mutationBefore &&
                credentialVault.getVaultGeneration() == vaultGenBefore
            ) {
                lastFailedRefreshToken = refreshToken
                lastFailedVaultGeneration = vaultGenBefore
                lastFailedMutationCounter = mutationBefore
                lastFailureEpochMs = nowProvider()
                if (isInvalidGrantError(t)) {
                    permanentlyInvalidRefreshToken = refreshToken
                    permanentlyInvalidVaultGeneration = vaultGenBefore
                    permanentlyInvalidMutationCounter = mutationBefore
                }
            }
            return Result.failure(t)
        }

        // H-4 / M-2 Safeguard: Verify via pure in-memory generation counters that the vault was not
        // cleared, wiped, or replaced by a fresh OAuth grant while the refresh HTTP call was in flight,
        // without performing a post-refresh Keystore decrypt that could discard a newly rotated R_new.
        if (sessionMutationCounter.get() != mutationBefore ||
            credentialVault.getVaultGeneration() != vaultGenBefore
        ) {
            return Result.failure(
                IllegalStateException(
                    "OAuth session changed or was cleared while token refresh was in flight; discarding stale refresh token response."
                )
            )
        }

        clearFailureStateLocked()
        try {
            credentialVault.saveRefreshedAccessToken(
                accessToken = response.accessToken,
                rotatedRefreshToken = response.refreshToken,
                accessExpiresInSeconds = response.expiresIn ?: DEFAULT_ACCESS_TTL_SECONDS,
                nowEpochMs = nowProvider()
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        return Result.success(response.accessToken)
    }

    private fun clearFailureStateLocked() {
        lastFailedRefreshToken = null
        lastFailedVaultGeneration = -1L
        lastFailedMutationCounter = -1L
        lastFailureEpochMs = Long.MIN_VALUE
        permanentlyInvalidRefreshToken = null
        permanentlyInvalidVaultGeneration = -1L
        permanentlyInvalidMutationCounter = -1L
    }

    private fun isInvalidGrantError(throwable: Throwable): Boolean {
        if (throwable is HttpException && throwable.code() == 400) {
            val body = runCatching { throwable.response()?.errorBody()?.string() }.getOrNull().orEmpty()
            val msg = throwable.message.orEmpty()
            return body.contains(INVALID_GRANT, ignoreCase = true) ||
                msg.contains(INVALID_GRANT, ignoreCase = true)
        }
        return throwable.message?.contains(INVALID_GRANT, ignoreCase = true) == true
    }

    companion object {
        private const val DEFAULT_ACCESS_TTL_SECONDS = 1800L
        internal const val FAILURE_COOLDOWN_MS = 15_000L
        private const val INVALID_GRANT = "invalid_grant"
    }
}
