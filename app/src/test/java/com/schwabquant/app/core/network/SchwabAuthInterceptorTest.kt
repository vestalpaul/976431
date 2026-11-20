package com.schwabquant.app.core.network

import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.StoredAuthSnapshot
import com.schwabquant.app.data.remote.api.SchwabAuthApi
import com.schwabquant.app.data.remote.dto.TokenResponseDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

class SchwabAuthInterceptorTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `proactively refreshes access token at 25-minute mark before attaching Bearer header`() {
        val nowMs = 1_700_000_000_000L
        val fourMinutesLeft = nowMs + 4L * 60L * 1_000L
        val vault = LocalFakeVault(
            accessToken = "expiring-token",
            refreshToken = "refresh-token",
            accessExpiresAt = fourMinutesLeft
        )
        val refreshCount = AtomicInteger(0)
        val fakeAuthApi = object : SchwabAuthApi {
            override suspend fun exchangeAuthorizationCode(
                basicAuth: String,
                grantType: String,
                code: String,
                redirectUri: String
            ): TokenResponseDto = error("Unused")

            override suspend fun refreshToken(
                basicAuth: String,
                grantType: String,
                refreshToken: String
            ): TokenResponseDto {
                refreshCount.incrementAndGet()
                return TokenResponseDto(accessToken = "proactive-fresh-token", expiresIn = 1800L)
            }
        }

        val coordinator = SchwabTokenRefreshCoordinator(
            credentialVault = vault,
            authApiProvider = Provider { fakeAuthApi },
            nowProvider = { nowMs }
        )
        val interceptor = SchwabAuthInterceptor(
            credentialVault = vault,
            rateLimiter = TokenBucketRateLimiter(),
            refreshCoordinator = coordinator
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        client.newCall(Request.Builder().url(server.url("/marketdata/v1/quotes")).build())
            .execute()
            .close()

        val recorded = server.takeRequest()
        assertEquals(1, refreshCount.get())
        assertEquals("Bearer proactive-fresh-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `attaches existing Bearer token without refresh when outside 5-minute window`() {
        val nowMs = 1_700_000_000_000L
        val tenMinutesLeft = nowMs + 10L * 60L * 1_000L
        val vault = LocalFakeVault(
            accessToken = "healthy-token",
            refreshToken = "refresh-token",
            accessExpiresAt = tenMinutesLeft
        )
        val refreshCount = AtomicInteger(0)
        val fakeAuthApi = object : SchwabAuthApi {
            override suspend fun exchangeAuthorizationCode(
                basicAuth: String,
                grantType: String,
                code: String,
                redirectUri: String
            ): TokenResponseDto = error("Unused")

            override suspend fun refreshToken(
                basicAuth: String,
                grantType: String,
                refreshToken: String
            ): TokenResponseDto {
                refreshCount.incrementAndGet()
                return TokenResponseDto(accessToken = "unused", expiresIn = 1800L)
            }
        }

        val coordinator = SchwabTokenRefreshCoordinator(vault, Provider { fakeAuthApi }, { nowMs })
        val client = OkHttpClient.Builder()
            .addInterceptor(SchwabAuthInterceptor(vault, TokenBucketRateLimiter(), coordinator))
            .build()

        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/trader/v1/accounts/accountNumbers")).build())
            .execute()
            .close()

        val recorded = server.takeRequest()
        assertEquals(0, refreshCount.get())
        assertEquals("Bearer healthy-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `skips Bearer attachment and proactive refresh on oauth token endpoint`() {
        val nowMs = 1_700_000_000_000L
        val vault = LocalFakeVault(
            accessToken = "should-not-override",
            refreshToken = "refresh-token",
            accessExpiresAt = nowMs + 1_000L
        )
        val refreshCount = AtomicInteger(0)
        val fakeAuthApi = object : SchwabAuthApi {
            override suspend fun exchangeAuthorizationCode(
                basicAuth: String,
                grantType: String,
                code: String,
                redirectUri: String
            ): TokenResponseDto = error("Unused")

            override suspend fun refreshToken(
                basicAuth: String,
                grantType: String,
                refreshToken: String
            ): TokenResponseDto {
                refreshCount.incrementAndGet()
                return TokenResponseDto(accessToken = "unused", expiresIn = 1800L)
            }
        }

        val coordinator = SchwabTokenRefreshCoordinator(vault, Provider { fakeAuthApi }, { nowMs })
        val client = OkHttpClient.Builder()
            .addInterceptor(SchwabAuthInterceptor(vault, TokenBucketRateLimiter(), coordinator))
            .build()

        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            Request.Builder()
                .url(server.url("/v1/oauth/token"))
                .header("Authorization", "Basic YWJjOnh5eg==")
                .build()
        ).execute().close()

        val recorded = server.takeRequest()
        assertEquals(0, refreshCount.get())
        assertEquals("Basic YWJjOnh5eg==", recorded.getHeader("Authorization"))
    }

    private class LocalFakeVault(
        private var accessToken: String?,
        private var refreshToken: String?,
        private var accessExpiresAt: Long
    ) : CredentialVault {
        private val snapshot = MutableStateFlow(
            StoredAuthSnapshot(
                hasAppCredentials = true,
                hasAccessToken = true,
                hasRefreshToken = true,
                accessTokenExpiresAtEpochMs = accessExpiresAt,
                refreshTokenIssuedAtEpochMs = 1_699_999_000_000L,
                primaryAccountHash = null,
                maskedAccountNumber = null,
                callbackUrl = CredentialVault.DEFAULT_CALLBACK_URL
            )
        )

        override val authSnapshotFlow: StateFlow<StoredAuthSnapshot> = snapshot
        override fun saveAppCredentials(appKey: String, appSecret: String, callbackUrl: String) {}
        override fun getAppKey(): String = "app-key"
        override fun getAppSecret(): String = "app-secret"
        override fun getCallbackUrl(): String = CredentialVault.DEFAULT_CALLBACK_URL
        override fun saveInitialOAuthGrant(
            accessToken: String,
            refreshToken: String,
            accessExpiresInSeconds: Long,
            nowEpochMs: Long
        ) {}

        override fun saveRefreshedAccessToken(
            accessToken: String,
            rotatedRefreshToken: String?,
            accessExpiresInSeconds: Long,
            nowEpochMs: Long
        ) {
            this.accessToken = accessToken
            this.accessExpiresAt = nowEpochMs + accessExpiresInSeconds * 1_000L
        }

        override fun getAccessToken(): String? = accessToken
        override fun getRefreshToken(): String? = refreshToken
        override fun getAccessTokenExpiresAtEpochMs(): Long = accessExpiresAt
        override fun getRefreshTokenIssuedAtEpochMs(): Long = 1_699_999_000_000L
        override fun savePrimaryAccount(accountHash: String, maskedAccountNumber: String?) {}
        override fun getPrimaryAccountHash(): String? = null
        override fun getMaskedAccountNumber(): String? = null
        override fun isVaultCorrupted(): Boolean = false
        override fun clearTokensOnly() {}
        override fun wipeAllCredentials() {}
    }
}
