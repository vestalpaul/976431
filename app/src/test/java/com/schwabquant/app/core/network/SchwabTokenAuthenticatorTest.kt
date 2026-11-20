package com.schwabquant.app.core.network

import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.StoredAuthSnapshot
import com.schwabquant.app.data.remote.api.SchwabAuthApi
import com.schwabquant.app.data.remote.dto.TokenResponseDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

class SchwabTokenAuthenticatorTest {

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
    fun `concurrent 401 responses perform single-flight refresh and preserve refreshTokenIssuedAt`() {
        val initialIssuedAt = 1_700_000_000_000L
        val fixedNow = initialIssuedAt + 40L * 60L * 1_000L
        val vault = TestVault(
            appKey = "my-app-key",
            appSecret = "my-app-secret",
            accessToken = "stale-token",
            refreshToken = "refresh-7d",
            accessExpiresAt = fixedNow + 1_000_000L,
            refreshIssuedAt = initialIssuedAt
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
                Thread.sleep(60)
                refreshCount.incrementAndGet()
                return TokenResponseDto(
                    accessToken = "fresh-token",
                    refreshToken = "rotated-refresh",
                    expiresIn = 1800L
                )
            }
        }

        val coordinator = SchwabTokenRefreshCoordinator(
            credentialVault = vault,
            authApiProvider = Provider { fakeAuthApi },
            nowProvider = { fixedNow }
        )
        val authenticator = SchwabTokenAuthenticator(coordinator)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.getHeader("Authorization") == "Bearer fresh-token") {
                    MockResponse().setResponseCode(200).setBody("ok")
                } else {
                    MockResponse().setResponseCode(401)
                }
            }
        }

        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .build()

        val threads = 5
        val readyLatch = CountDownLatch(threads)
        val startLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)

        val futures = (1..threads).map {
            executor.submit(
                Callable {
                    readyLatch.countDown()
                    startLatch.await()
                    val request = Request.Builder()
                        .url(server.url("/trader/v1/accounts"))
                        .header("Authorization", "Bearer stale-token")
                        .build()
                    client.newCall(request).execute().use { it.code }
                }
            )
        }

        readyLatch.await()
        startLatch.countDown()

        val codes = futures.map { it.get() }
        executor.shutdown()

        assertEquals(List(threads) { 200 }, codes)
        assertEquals(1, refreshCount.get(), "Parallel 401s must share a single token refresh")
        assertEquals("fresh-token", vault.getAccessToken())
        assertEquals(
            initialIssuedAt,
            vault.getRefreshTokenIssuedAtEpochMs(),
            "Silent refresh must preserve initial 7-day refreshTokenIssuedAtEpochMs"
        )
    }

    @Test
    fun `stops retrying after one retry on persistent 401 to prevent infinite loops`() {
        val vault = TestVault(
            appKey = "key",
            appSecret = "secret",
            accessToken = "stale-token",
            refreshToken = "refresh-token",
            accessExpiresAt = 2_000_000L,
            refreshIssuedAt = 1_000_000L
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
                return TokenResponseDto(accessToken = "still-rejected", expiresIn = 1800L)
            }
        }

        val coordinator = SchwabTokenRefreshCoordinator(vault, Provider { fakeAuthApi })
        val authenticator = SchwabTokenAuthenticator(coordinator)

        val serverHitCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                serverHitCount.incrementAndGet()
                return MockResponse().setResponseCode(401)
            }
        }

        val client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .build()

        val responseCode = client.newCall(
            Request.Builder()
                .url(server.url("/trader/v1/accounts"))
                .header("Authorization", "Bearer stale-token")
                .build()
        ).execute().use { it.code }

        assertEquals(401, responseCode)
        assertEquals(1, refreshCount.get())
        assertEquals(2, serverHitCount.get(), "Initial attempt + 1 retry max")
    }

    @Test
    fun `never triggers token refresh on 401 from oauth token endpoint`() {
        val vault = TestVault(
            appKey = "key",
            appSecret = "secret",
            accessToken = "token",
            refreshToken = "refresh",
            accessExpiresAt = 2_000_000L,
            refreshIssuedAt = 1_000_000L
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
                return TokenResponseDto(accessToken = "unexpected", expiresIn = 1800L)
            }
        }

        val coordinator = SchwabTokenRefreshCoordinator(vault, Provider { fakeAuthApi })
        val client = OkHttpClient.Builder()
            .authenticator(SchwabTokenAuthenticator(coordinator))
            .build()

        server.enqueue(MockResponse().setResponseCode(401))

        val code = client.newCall(
            Request.Builder()
                .url(server.url("/v1/oauth/token"))
                .header("Authorization", "Basic dGVzdA==")
                .build()
        ).execute().use { it.code }

        assertEquals(401, code)
        assertEquals(0, refreshCount.get())
    }

    @Test
    fun `M-2 discards in-flight refresh response when vault is cleared or re-authenticated mid-flight`() {
        val vault = TestVault(
            appKey = "key",
            appSecret = "secret",
            accessToken = "old-access",
            refreshToken = "old-refresh",
            accessExpiresAt = 1_000L,
            refreshIssuedAt = 500L
        )
        lateinit var coordinator: SchwabTokenRefreshCoordinator
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
                // Simulate concurrent user logout / new OAuth grant while refresh HTTP call is in flight
                vault.clearTokensOnly()
                coordinator.recordSessionMutation()
                return TokenResponseDto(
                    accessToken = "stale-refreshed-access",
                    refreshToken = "stale-rotated-refresh",
                    expiresIn = 1800L
                )
            }
        }

        coordinator = SchwabTokenRefreshCoordinator(vault, Provider { fakeAuthApi })
        val result = coordinator.refreshNow()
        assertEquals(true, result.isFailure)
        assertEquals(null, vault.getAccessToken(), "Cleared vault must NOT be repopulated by stale in-flight refresh")
        assertEquals(null, vault.getRefreshToken())
    }

    @Test
    fun `M-1 enforces 15s failure cooldown and HTTP 400 invalid_grant latch until session mutation`() {
        val vault = TestVault(
            appKey = "key",
            appSecret = "secret",
            accessToken = "old-access",
            refreshToken = "refresh-v1",
            accessExpiresAt = 0L,
            refreshIssuedAt = 0L
        )
        var nowMs = 1_000_000L
        var networkCalls = 0
        var mode = "TRANSIENT_500"

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
                networkCalls++
                return when (mode) {
                    "TRANSIENT_500" -> throw HttpException(
                        Response.error<TokenResponseDto>(
                            500,
                            """{"error":"server_error"}""".toResponseBody("application/json".toMediaType())
                        )
                    )
                    "INVALID_GRANT" -> throw HttpException(
                        Response.error<TokenResponseDto>(
                            400,
                            """{"error":"invalid_grant","error_description":"Refresh token revoked"}""".toResponseBody("application/json".toMediaType())
                        )
                    )
                    else -> TokenResponseDto(
                        accessToken = "fresh-$refreshToken",
                        refreshToken = "rotated-$refreshToken",
                        expiresIn = 1800L
                    )
                }
            }
        }

        val coordinator = SchwabTokenRefreshCoordinator(
            credentialVault = vault,
            authApiProvider = Provider { fakeAuthApi },
            nowProvider = { nowMs }
        )

        // 1. Transient failure -> enters 15s cooldown
        assertNull(coordinator.refreshOnUnauthorized("old-access"))
        assertEquals(1, networkCalls)

        nowMs += 10_000L // Within 15s window
        assertNull(coordinator.refreshOnUnauthorized("old-access"))
        assertTrue(coordinator.refreshNow().isFailure)
        assertEquals(1, networkCalls, "Must NOT hit /oauth/token again during 15s failure cooldown")

        // 2. Advance +5s (total +15s) -> cooldown expires and attempt 2 executes -> hits 400 invalid_grant
        nowMs += 5_000L
        mode = "INVALID_GRANT"
        assertNull(coordinator.refreshOnUnauthorized("old-access"))
        assertEquals(2, networkCalls)

        // Even +60s later, invalid_grant stays permanently latched on (refresh-v1, gen 0)
        nowMs += 60_000L
        mode = "SUCCESS"
        assertNull(coordinator.refreshOnUnauthorized("old-access"))
        assertTrue(coordinator.refreshNow().isFailure)
        assertEquals(2, networkCalls, "HTTP 400 invalid_grant latch must stay latched until new OAuth grant")

        // 3. New OAuth grant bumps vaultGeneration & mutationCounter -> latch clears immediately
        vault.saveInitialOAuthGrant("new-access-v2", "refresh-v2", 1800L, nowMs)
        coordinator.recordSessionMutation()
        val recovered = coordinator.refreshNow()
        assertTrue(recovered.isSuccess)
        assertEquals("fresh-refresh-v2", recovered.getOrThrow())
        assertEquals(3, networkCalls)
    }

    @Test
    fun `H-1 catches vault saveRefreshedAccessToken exceptions inside performRefreshLocked cleanly`() {
        val throwingVault = object : CredentialVault {
            override val authSnapshotFlow: StateFlow<StoredAuthSnapshot> = MutableStateFlow(
                StoredAuthSnapshot(
                    hasAppCredentials = true,
                    hasAccessToken = true,
                    hasRefreshToken = true,
                    accessTokenExpiresAtEpochMs = 0L,
                    refreshTokenIssuedAtEpochMs = 0L,
                    primaryAccountHash = null,
                    maskedAccountNumber = null,
                    callbackUrl = CredentialVault.DEFAULT_CALLBACK_URL
                )
            )
            override fun saveAppCredentials(appKey: String, appSecret: String, callbackUrl: String) {}
            override fun getAppKey(): String = "k"
            override fun getAppSecret(): String = "s"
            override fun getCallbackUrl(): String = CredentialVault.DEFAULT_CALLBACK_URL
            override fun saveInitialOAuthGrant(accessToken: String, refreshToken: String, accessExpiresInSeconds: Long, nowEpochMs: Long) {}
            override fun saveRefreshedAccessToken(accessToken: String, rotatedRefreshToken: String?, accessExpiresInSeconds: Long, nowEpochMs: Long) {
                error("Simulated Keystore exception")
            }
            override fun getAccessToken(): String = "expired-access"
            override fun getRefreshToken(): String = "valid-refresh"
            override fun getAccessTokenExpiresAtEpochMs(): Long = 0L
            override fun getRefreshTokenIssuedAtEpochMs(): Long = 0L
            override fun savePrimaryAccount(accountHash: String, maskedAccountNumber: String?) {}
            override fun getPrimaryAccountHash(): String? = null
            override fun getMaskedAccountNumber(): String? = null
            override fun isVaultCorrupted(): Boolean = false
            override fun clearTokensOnly() {}
            override fun wipeAllCredentials() {}
        }
        val fakeAuthApi = object : SchwabAuthApi {
            override suspend fun exchangeAuthorizationCode(basicAuth: String, grantType: String, code: String, redirectUri: String) =
                error("Unused")
            override suspend fun refreshToken(basicAuth: String, grantType: String, refreshToken: String) =
                TokenResponseDto(accessToken = "new-access", refreshToken = "new-refresh", expiresIn = 1800L)
        }
        val coordinator = SchwabTokenRefreshCoordinator(throwingVault, Provider { fakeAuthApi })

        val res = coordinator.refreshNow()
        assertTrue(res.isFailure)
        assertNull(coordinator.refreshOnUnauthorized("expired-access"))
    }

    @Test
    fun `H-4 post-refresh transient Keystore decrypt failure still saves T_new and rotated R_new`() {
        var refreshReadCount = 0
        var savedAccess: String? = null
        var savedRefresh: String? = null
        val vault = object : CredentialVault {
            override val authSnapshotFlow: StateFlow<StoredAuthSnapshot> = MutableStateFlow(
                StoredAuthSnapshot(
                    hasAppCredentials = true,
                    hasAccessToken = true,
                    hasRefreshToken = true,
                    accessTokenExpiresAtEpochMs = 0L,
                    refreshTokenIssuedAtEpochMs = 0L,
                    primaryAccountHash = null,
                    maskedAccountNumber = null,
                    callbackUrl = CredentialVault.DEFAULT_CALLBACK_URL
                )
            )
            override fun saveAppCredentials(appKey: String, appSecret: String, callbackUrl: String) {}
            override fun getAppKey(): String = "app-key"
            override fun getAppSecret(): String = "app-secret"
            override fun getCallbackUrl(): String = CredentialVault.DEFAULT_CALLBACK_URL
            override fun saveInitialOAuthGrant(accessToken: String, refreshToken: String, accessExpiresInSeconds: Long, nowEpochMs: Long) {}
            override fun saveRefreshedAccessToken(
                accessToken: String,
                rotatedRefreshToken: String?,
                accessExpiresInSeconds: Long,
                nowEpochMs: Long
            ) {
                savedAccess = accessToken
                savedRefresh = rotatedRefreshToken
            }
            override fun getAccessToken(): String = "old-access"
            override fun getRefreshToken(): String? = if (refreshReadCount++ == 0) "R_old" else null
            override fun getAccessTokenExpiresAtEpochMs(): Long = 0L
            override fun getRefreshTokenIssuedAtEpochMs(): Long = 0L
            override fun savePrimaryAccount(accountHash: String, maskedAccountNumber: String?) {}
            override fun getPrimaryAccountHash(): String? = null
            override fun getMaskedAccountNumber(): String? = null
            override fun isVaultCorrupted(): Boolean = false
            override fun getVaultGeneration(): Long = 7L
            override fun clearTokensOnly() {}
            override fun wipeAllCredentials() {}
        }
        val fakeAuthApi = object : SchwabAuthApi {
            override suspend fun exchangeAuthorizationCode(basicAuth: String, grantType: String, code: String, redirectUri: String) =
                error("Unused")
            override suspend fun refreshToken(basicAuth: String, grantType: String, refreshToken: String) =
                TokenResponseDto(accessToken = "T_new", refreshToken = "R_new", expiresIn = 1800L)
        }
        val coordinator = SchwabTokenRefreshCoordinator(vault, Provider { fakeAuthApi })

        val result = coordinator.refreshNow()
        assertTrue(result.isSuccess)
        assertEquals("T_new", result.getOrThrow())
        assertEquals("T_new", savedAccess)
        assertEquals("R_new", savedRefresh, "Must NEVER discard single-use rotated R_new on post-HTTP decrypt fault")
    }

    private class TestVault(
        private var appKey: String?,
        private var appSecret: String?,
        private var accessToken: String?,
        private var refreshToken: String?,
        private var accessExpiresAt: Long,
        private var refreshIssuedAt: Long
    ) : CredentialVault {
        private val snapshot = MutableStateFlow(
            StoredAuthSnapshot(
                hasAppCredentials = true,
                hasAccessToken = true,
                hasRefreshToken = true,
                accessTokenExpiresAtEpochMs = accessExpiresAt,
                refreshTokenIssuedAtEpochMs = refreshIssuedAt,
                primaryAccountHash = null,
                maskedAccountNumber = null,
                callbackUrl = CredentialVault.DEFAULT_CALLBACK_URL
            )
        )

        override val authSnapshotFlow: StateFlow<StoredAuthSnapshot> = snapshot
        override fun saveAppCredentials(appKey: String, appSecret: String, callbackUrl: String) {}
        override fun getAppKey(): String? = appKey
        override fun getAppSecret(): String? = appSecret
        override fun getCallbackUrl(): String = CredentialVault.DEFAULT_CALLBACK_URL

        override fun saveInitialOAuthGrant(
            accessToken: String,
            refreshToken: String,
            accessExpiresInSeconds: Long,
            nowEpochMs: Long
        ) {
            generation++
            this.accessToken = accessToken
            this.refreshToken = refreshToken
            this.accessExpiresAt = nowEpochMs + accessExpiresInSeconds * 1_000L
            this.refreshIssuedAt = nowEpochMs
        }

        override fun saveRefreshedAccessToken(
            accessToken: String,
            rotatedRefreshToken: String?,
            accessExpiresInSeconds: Long,
            nowEpochMs: Long
        ) {
            this.accessToken = accessToken
            if (!rotatedRefreshToken.isNullOrBlank()) this.refreshToken = rotatedRefreshToken
            this.accessExpiresAt = nowEpochMs + accessExpiresInSeconds * 1_000L
        }

        private var generation: Long = 0L
        override fun getAccessToken(): String? = accessToken
        override fun getRefreshToken(): String? = refreshToken
        override fun getAccessTokenExpiresAtEpochMs(): Long = accessExpiresAt
        override fun getRefreshTokenIssuedAtEpochMs(): Long = refreshIssuedAt
        override fun savePrimaryAccount(accountHash: String, maskedAccountNumber: String?) {}
        override fun getPrimaryAccountHash(): String? = null
        override fun getMaskedAccountNumber(): String? = null
        override fun isVaultCorrupted(): Boolean = false
        override fun getVaultGeneration(): Long = generation
        override fun clearTokensOnly() {
            generation++
            accessToken = null
            refreshToken = null
            accessExpiresAt = 0L
            refreshIssuedAt = 0L
        }
        override fun wipeAllCredentials() {
            generation++
            appKey = null
            appSecret = null
            accessToken = null
            refreshToken = null
            accessExpiresAt = 0L
            refreshIssuedAt = 0L
        }
    }
}
