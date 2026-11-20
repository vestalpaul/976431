package com.schwabquant.app.data.repository

import com.schwabquant.app.core.network.SchwabTokenRefreshCoordinator
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.OAuthCallbackParser
import com.schwabquant.app.core.security.StoredAuthSnapshot
import com.schwabquant.app.core.security.TokenExpiryCalculator
import com.schwabquant.app.core.security.TokenExpiryTier
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.api.SchwabAuthApi
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import com.schwabquant.app.domain.repository.AuthRepository
import com.schwabquant.app.domain.repository.AuthSessionState
import com.schwabquant.app.domain.repository.OAuthStatus
import com.schwabquant.app.domain.repository.StoredCredentialsForm
import com.schwabquant.app.domain.repository.VerifiedAccountInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val credentialVault: CredentialVault,
    private val schwabAuthApi: SchwabAuthApi,
    private val traderApiProvider: Provider<SchwabTraderApi>,
    private val refreshCoordinator: SchwabTokenRefreshCoordinator,
    private val preferences: AppPreferencesDataStore
) : AuthRepository {

    private val minuteTicker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(COUNTDOWN_TICK_MS)
        }
    }

    override fun observeAuthState(): Flow<AuthSessionState> =
        combine(credentialVault.authSnapshotFlow, minuteTicker) { snapshot, nowMs ->
            mapToSessionState(snapshot, nowMs)
        }

    override fun getStoredCredentialsForm(): StoredCredentialsForm = StoredCredentialsForm(
        appKey = credentialVault.getAppKey().orEmpty(),
        appSecret = credentialVault.getAppSecret().orEmpty(),
        callbackUrl = credentialVault.getCallbackUrl()
    )

    override suspend fun saveAppCredentials(
        appKey: String,
        appSecret: String,
        callbackUrl: String
    ) {
        credentialVault.saveAppCredentials(appKey, appSecret, callbackUrl)
    }

    override suspend fun buildAuthorizationUrl(): Result<String> = runCatching {
        val appKey = credentialVault.getAppKey()
            ?: error("Schwab App Key is not configured.")
        val callbackUrl = credentialVault.getCallbackUrl()
        val encodedClientId = URLEncoder.encode(appKey, StandardCharsets.UTF_8.name())
        val encodedRedirect = URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8.name())
        "https://api.schwabapi.com/v1/oauth/authorize?client_id=$encodedClientId&redirect_uri=$encodedRedirect"
    }

    override suspend fun exchangeAuthorizationCode(rawCodeOrUrl: String): Result<Unit> = runCatching {
        val decodedCode = OAuthCallbackParser.parseAuthorizationCode(rawCodeOrUrl).getOrThrow()
        val appKey = credentialVault.getAppKey()
            ?: error("Schwab App Key is missing.")
        val appSecret = credentialVault.getAppSecret()
            ?: error("Schwab App Secret is missing.")
        val callbackUrl = credentialVault.getCallbackUrl()

        val basicAuth = refreshCoordinator.buildBasicAuthHeader(appKey, appSecret)
        val response = schwabAuthApi.exchangeAuthorizationCode(
            basicAuth = basicAuth,
            code = decodedCode,
            redirectUri = callbackUrl
        )
        val refreshToken = response.refreshToken
            ?: error("Schwab token response omitted refresh_token.")

        withContext(Dispatchers.IO) {
            refreshCoordinator.withRefreshLock {
                refreshCoordinator.recordSessionMutation()
                credentialVault.saveInitialOAuthGrant(
                    accessToken = response.accessToken,
                    refreshToken = refreshToken,
                    accessExpiresInSeconds = response.expiresIn ?: DEFAULT_ACCESS_TTL_SECONDS
                )
            }
        }
        preferences.clearTokenCliffAlertHistory()
    }

    override suspend fun refreshAccessToken(): Result<Unit> = withContext(Dispatchers.IO) {
        refreshCoordinator.refreshNow().map { }
    }

    override suspend fun verifyConnectionAndFetchAccounts(): Result<VerifiedAccountInfo> = runCatching {
        val accounts = traderApiProvider.get().getAccountNumbers()
        val primary = accounts.firstOrNull()
            ?: error("Schwab returned zero linked brokerage accounts.")
        val masked = maskAccountNumber(primary.accountNumber)
        credentialVault.savePrimaryAccount(
            accountHash = primary.hashValue,
            maskedAccountNumber = masked
        )
        VerifiedAccountInfo(
            primaryAccountHash = primary.hashValue,
            maskedAccountNumber = masked,
            accountCount = accounts.size
        )
    }

    override suspend fun clearTokensOnly() {
        withContext(Dispatchers.IO) {
            refreshCoordinator.withRefreshLock {
                refreshCoordinator.recordSessionMutation()
                credentialVault.clearTokensOnly()
            }
        }
        preferences.clearTokenCliffAlertHistory()
    }

    override suspend fun wipeAllCredentials() {
        withContext(Dispatchers.IO) {
            refreshCoordinator.withRefreshLock {
                refreshCoordinator.recordSessionMutation()
                credentialVault.wipeAllCredentials()
            }
        }
        preferences.clearTokenCliffAlertHistory()
    }

    private fun mapToSessionState(
        snapshot: StoredAuthSnapshot,
        nowEpochMs: Long
    ): AuthSessionState {
        val hasTokens = snapshot.hasAccessToken && snapshot.hasRefreshToken
        val evaluation = if (hasTokens) {
            TokenExpiryCalculator.evaluateRefreshToken(
                refreshTokenIssuedAtEpochMs = snapshot.refreshTokenIssuedAtEpochMs,
                nowEpochMs = nowEpochMs
            )
        } else {
            TokenExpiryCalculator.evaluateRefreshToken(
                refreshTokenIssuedAtEpochMs = 0L,
                nowEpochMs = nowEpochMs
            )
        }

        val status = when {
            !snapshot.hasAppCredentials -> OAuthStatus.NOT_CONFIGURED
            !hasTokens -> OAuthStatus.UNAUTHENTICATED
            evaluation.tier == TokenExpiryTier.EXPIRED -> OAuthStatus.EXPIRED
            evaluation.tier == TokenExpiryTier.DAY_5_WARNING ||
                evaluation.tier == TokenExpiryTier.DAY_6_CRITICAL -> OAuthStatus.REFRESH_EXPIRING_SOON
            else -> OAuthStatus.AUTHENTICATED
        }

        return AuthSessionState(
            status = status,
            expiryTier = evaluation.tier,
            countdownLabel = evaluation.formattedCountdown,
            hasAppCredentials = snapshot.hasAppCredentials,
            accessTokenExpiresAtEpochMs = snapshot.accessTokenExpiresAtEpochMs.takeIf { it > 0L },
            refreshTokenIssuedAtEpochMs = snapshot.refreshTokenIssuedAtEpochMs.takeIf { it > 0L },
            refreshTokenExpiresAtEpochMs = evaluation.expiresAtEpochMs.takeIf { it > 0L },
            remainingRefreshMillis = evaluation.remainingMillis.takeIf {
                evaluation.tier != TokenExpiryTier.NOT_CONNECTED
            },
            primaryAccountHash = snapshot.primaryAccountHash,
            maskedAccountNumber = snapshot.maskedAccountNumber,
            callbackUrl = snapshot.callbackUrl,
            vaultCorruptionDetected = snapshot.vaultCorruptionDetected,
            vaultUnavailableTransient = snapshot.vaultUnavailableTransient
        )
    }

    private fun maskAccountNumber(rawNumber: String): String {
        val trimmed = rawNumber.trim()
        return if (trimmed.length <= 4) "••••$trimmed" else "••••${trimmed.takeLast(4)}"
    }

    companion object {
        private const val COUNTDOWN_TICK_MS = 60_000L
        private const val DEFAULT_ACCESS_TTL_SECONDS = 1800L
    }
}
