package com.schwabquant.app.domain.repository

import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.TokenExpiryTier
import kotlinx.coroutines.flow.Flow

enum class OAuthStatus {
    NOT_CONFIGURED,
    UNAUTHENTICATED,
    AUTHENTICATED,
    REFRESH_EXPIRING_SOON,
    EXPIRED
}

data class VerifiedAccountInfo(
    val primaryAccountHash: String,
    val maskedAccountNumber: String,
    val accountCount: Int
)

data class StoredCredentialsForm(
    val appKey: String = "",
    val appSecret: String = "",
    val callbackUrl: String = CredentialVault.DEFAULT_CALLBACK_URL
)

data class AuthSessionState(
    val status: OAuthStatus = OAuthStatus.NOT_CONFIGURED,
    val expiryTier: TokenExpiryTier = TokenExpiryTier.NOT_CONNECTED,
    val countdownLabel: String = "Not Connected",
    val hasAppCredentials: Boolean = false,
    val accessTokenExpiresAtEpochMs: Long? = null,
    val refreshTokenIssuedAtEpochMs: Long? = null,
    val refreshTokenExpiresAtEpochMs: Long? = null,
    val remainingRefreshMillis: Long? = null,
    val primaryAccountHash: String? = null,
    val maskedAccountNumber: String? = null,
    val callbackUrl: String = CredentialVault.DEFAULT_CALLBACK_URL,
    val vaultCorruptionDetected: Boolean = false,
    val vaultUnavailableTransient: Boolean = false
)

interface AuthRepository {
    fun observeAuthState(): Flow<AuthSessionState>
    fun getStoredCredentialsForm(): StoredCredentialsForm
    suspend fun saveAppCredentials(appKey: String, appSecret: String, callbackUrl: String)
    suspend fun buildAuthorizationUrl(): Result<String>
    suspend fun exchangeAuthorizationCode(rawCodeOrUrl: String): Result<Unit>
    suspend fun refreshAccessToken(): Result<Unit>
    suspend fun verifyConnectionAndFetchAccounts(): Result<VerifiedAccountInfo>
    suspend fun clearTokensOnly()
    suspend fun wipeAllCredentials()
}
