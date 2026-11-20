package com.schwabquant.app.service

import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.StoredAuthSnapshot
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import com.schwabquant.app.data.remote.dto.StreamerInfoDto
import com.schwabquant.app.data.remote.dto.UserPreferenceDto
import com.schwabquant.app.data.remote.streamer.SchwabStreamerClient
import com.schwabquant.app.data.remote.streamer.StreamerConnectionState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SentinelForegroundServiceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun `C-1 overlapping ACTION_START while startupJob or monitoringJob is active preserves serviceSessionId`() {
        val sessionId = AtomicLong(4L)
        val latestStartId = AtomicInteger(10)

        // Overlapping ACTION_START while startupJob is still recovering orphaned rules -> NO bump!
        val skippedDuringStartup = SentinelForegroundService.onActionStartLocked(
            serviceSessionId = sessionId,
            latestStartId = latestStartId,
            startId = 11,
            isShuttingDown = false,
            monitoringJobActive = false,
            startupJobActive = true
        )
        assertNull(skippedDuringStartup)
        assertEquals(4L, sessionId.get())
        assertEquals(11, latestStartId.get())

        // Overlapping ACTION_START while monitoringJob is active -> NO bump!
        val skippedDuringMonitor = SentinelForegroundService.onActionStartLocked(
            serviceSessionId = sessionId,
            latestStartId = latestStartId,
            startId = 12,
            isShuttingDown = false,
            monitoringJobActive = true,
            startupJobActive = false
        )
        assertNull(skippedDuringMonitor)
        assertEquals(4L, sessionId.get())
        assertEquals(12, latestStartId.get())

        // Restart overriding an active shutdown OR from idle DOES bump serviceSessionId
        val restartOverShutdown = SentinelForegroundService.onActionStartLocked(
            serviceSessionId = sessionId,
            latestStartId = latestStartId,
            startId = 13,
            isShuttingDown = true,
            monitoringJobActive = false,
            startupJobActive = true
        )
        assertEquals(5L, restartOverShutdown)
        assertEquals(5L, sessionId.get())
        assertEquals(13, latestStartId.get())

        val freshStart = SentinelForegroundService.onActionStartLocked(
            serviceSessionId = sessionId,
            latestStartId = latestStartId,
            startId = 14,
            isShuttingDown = false,
            monitoringJobActive = false,
            startupJobActive = false
        )
        assertEquals(6L, freshStart)
    }

    @Test
    fun `M-1 M-2 M-3 guarded shutdown preserves newer Streamer session, transient AUTHENTICATED syncs symbols, and watchdog probes recovery`() = runBlocking {
        val outboundFrames = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets.add(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        outboundFrames.add(text)
                        if (text.contains("\"LOGIN\"")) {
                            webSocket.send(
                                """{"response":[{"service":"ADMIN","command":"LOGIN","content":{"code":0,"msg":"server=s1;status=PN"}}]}"""
                            )
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                })
        }
        server.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val okHttp = OkHttpClient()
        val traderApi = mockk<SchwabTraderApi> {
            coEvery { getUserPreference() } returns UserPreferenceDto(
                streamerInfo = listOf(
                    StreamerInfoDto(
                        streamerSocketUrl = "ws://${server.hostName}:${server.port}/ws",
                        schwabClientCustomerId = "cust-1",
                        schwabClientCorrelId = "corr-1",
                        schwabClientChannel = "IO",
                        schwabClientFunctionId = "APIAPP"
                    )
                )
            )
        }
        val probeCalls = AtomicInteger(0)
        val snapshotFlow = MutableStateFlow(
            StoredAuthSnapshot(
                hasAppCredentials = true,
                hasAccessToken = false,
                hasRefreshToken = true,
                accessTokenExpiresAtEpochMs = 0L,
                refreshTokenIssuedAtEpochMs = 0L,
                primaryAccountHash = "HASH-1",
                maskedAccountNumber = "••••1234",
                callbackUrl = CredentialVault.DEFAULT_CALLBACK_URL,
                vaultCorruptionDetected = false,
                vaultUnavailableTransient = true
            )
        )
        val vault = object : CredentialVault {
            override val authSnapshotFlow: StateFlow<StoredAuthSnapshot> = snapshotFlow
            override fun saveAppCredentials(appKey: String, appSecret: String, callbackUrl: String) {}
            override fun getAppKey(): String = "k"
            override fun getAppSecret(): String = "s"
            override fun getCallbackUrl(): String = CredentialVault.DEFAULT_CALLBACK_URL
            override fun saveInitialOAuthGrant(accessToken: String, refreshToken: String, accessExpiresInSeconds: Long, nowEpochMs: Long) {}
            override fun saveRefreshedAccessToken(accessToken: String, rotatedRefreshToken: String?, accessExpiresInSeconds: Long, nowEpochMs: Long) {}
            override fun getAccessToken(): String = "live-tok"
            override fun getRefreshToken(): String = "live-ref"
            override fun getAccessTokenExpiresAtEpochMs(): Long = Long.MAX_VALUE
            override fun getRefreshTokenIssuedAtEpochMs(): Long = 1L
            override fun savePrimaryAccount(accountHash: String, maskedAccountNumber: String?) {}
            override fun getPrimaryAccountHash(): String = "HASH-1"
            override fun getMaskedAccountNumber(): String = "••••1234"
            override fun isVaultCorrupted(): Boolean = false
            override fun probeKeystoreRecovery(): Boolean {
                probeCalls.incrementAndGet()
                snapshotFlow.value = snapshotFlow.value.copy(vaultUnavailableTransient = false)
                return true
            }
            override fun clearTokensOnly() {}
            override fun wipeAllCredentials() {}
        }
        val client = SchwabStreamerClient(
            okHttpClient = okHttp,
            json = json,
            traderApi = traderApi,
            tokenRefreshCoordinator = null,
            credentialVault = vault,
            nowProvider = { 100_000L }
        )

        try {
            assertTrue(client.connectLive(scope, setOf("GOOG")).isSuccess)
            val deadline = System.currentTimeMillis() + 3_000L
            while (client.connectionState.value != StreamerConnectionState.AUTHENTICATED &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(15L)
            }
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)

            // M-3: Even with vaultUnavailableTransient == true, syncMustRemainLiveState syncs newly armed symbols
            outboundFrames.clear()
            SentinelForegroundService.syncMustRemainLiveState(
                streamerClient = client,
                authSnapshot = snapshotFlow.value,
                activeSymbols = setOf("GOOG", "SPY"),
                scope = scope
            )
            val syncDeadline = System.currentTimeMillis() + 2_000L
            while (outboundFrames.none { it.contains("SPY") } && System.currentTimeMillis() < syncDeadline) {
                Thread.sleep(15L)
            }
            assertTrue(outboundFrames.any { it.contains("SPY") }, "Expected SPY subscription sync during transient fault")

            // M-3: Watchdog probe helper calls probeKeystoreRecovery only when mustRemainLive && vaultUnavailableTransient
            assertFalse(SentinelForegroundService.probeKeystoreRecoveryIfNeeded(mustRemainLive = false, credentialVault = vault))
            assertEquals(0, probeCalls.get())
            assertTrue(SentinelForegroundService.probeKeystoreRecoveryIfNeeded(mustRemainLive = true, credentialVault = vault))
            assertEquals(1, probeCalls.get())
            assertFalse(vault.authSnapshotFlow.value.vaultUnavailableTransient)

            // M-1/M-2: Canceled shutdown or stale sessionId must NEVER disconnect active Streamer
            val lock = Any()
            val sessionCounter = AtomicLong(10L)
            var finalTeardownRan = false

            // Case A: Canceled coroutine check via ensureActive()
            var sawCancel = false
            val canceledJob = launch {
                cancel()
                try {
                    SentinelForegroundService.executeGuardedShutdownStop(
                        sessionLock = lock,
                        serviceSessionId = sessionCounter,
                        expectedSessionId = 10L,
                        streamerClient = client
                    ) {
                        finalTeardownRan = true
                    }
                } catch (_: CancellationException) {
                    sawCancel = true
                }
            }
            canceledJob.join()
            assertTrue(sawCancel)
            assertFalse(finalTeardownRan)
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)

            // Case B: Superseded sessionId (10 vs current 11)
            sessionCounter.set(11L)
            SentinelForegroundService.executeGuardedShutdownStop(
                sessionLock = lock,
                serviceSessionId = sessionCounter,
                expectedSessionId = 10L,
                streamerClient = client
            ) {
                finalTeardownRan = true
            }
            assertFalse(finalTeardownRan)
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)

            // Case C: Matching sessionId DOES tear down cleanly
            SentinelForegroundService.executeGuardedShutdownStop(
                sessionLock = lock,
                serviceSessionId = sessionCounter,
                expectedSessionId = 11L,
                streamerClient = client
            ) {
                finalTeardownRan = true
            }
            assertTrue(finalTeardownRan)
            assertEquals(StreamerConnectionState.DISCONNECTED, client.connectionState.value)
        } finally {
            client.disconnect()
            sockets.forEach { runCatching { it.close(1000, "done") } }
            okHttp.connectionPool.evictAll()
            okHttp.dispatcher.executorService.shutdown()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `H-1 and M-1 syncMustRemainLiveState syncs across AUTHENTICATED CONNECTING RECONNECTING and ownerEpoch + selfHeal protect Streamer`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stateFlow = MutableStateFlow(StreamerConnectionState.AUTHENTICATED)
        val genCounter = AtomicLong(4L)
        val ownerCounter = AtomicLong(1L)
        val error12Flow = MutableStateFlow(false)

        val mockStreamer = mockk<SchwabStreamerClient>(relaxed = true) {
            every { connectionState } returns stateFlow
            every { currentConnectionGeneration() } answers { genCounter.get() }
            every { currentOwnerEpoch() } answers { ownerCounter.get() }
            every { advanceOwnerEpoch() } answers { ownerCounter.incrementAndGet() }
            every { isError12Halted() } answers { error12Flow.value }
            coEvery { syncEquitySubscriptions(any()) } just Runs
            coEvery { connectLive(any(), any()) } returns Result.success(Unit)
        }
        val transientSnapshot = StoredAuthSnapshot(
            hasAppCredentials = true,
            hasAccessToken = false,
            hasRefreshToken = true,
            accessTokenExpiresAtEpochMs = 0L,
            refreshTokenIssuedAtEpochMs = 0L,
            primaryAccountHash = "HASH-1",
            maskedAccountNumber = "••••1234",
            callbackUrl = CredentialVault.DEFAULT_CALLBACK_URL,
            vaultCorruptionDetected = false,
            vaultUnavailableTransient = true
        )

        try {
            // M-1: Sync desired symbols during AUTHENTICATED, CONNECTING, and RECONNECTING while vaultUnavailableTransient == true
            val symbols = setOf("SPY", "QQQ")
            stateFlow.value = StreamerConnectionState.AUTHENTICATED
            SentinelForegroundService.syncMustRemainLiveState(mockStreamer, transientSnapshot, symbols, scope)

            stateFlow.value = StreamerConnectionState.CONNECTING
            SentinelForegroundService.syncMustRemainLiveState(mockStreamer, transientSnapshot, symbols, scope)

            stateFlow.value = StreamerConnectionState.RECONNECTING
            SentinelForegroundService.syncMustRemainLiveState(mockStreamer, transientSnapshot, symbols, scope)

            coVerify(exactly = 3) { mockStreamer.syncEquitySubscriptions(symbols) }

            // H-1: executeGuardedShutdownStop captures BOTH connectionGeneration AND ownerEpoch and passes them through
            val lock = Any()
            val sessionCounter = AtomicLong(2L)
            SentinelForegroundService.executeGuardedShutdownStop(
                sessionLock = lock,
                serviceSessionId = sessionCounter,
                expectedSessionId = 2L,
                streamerClient = mockStreamer
            ) {}
            coVerify(exactly = 1) {
                mockStreamer.disconnectGracefully(expectedGeneration = 4L, expectedOwnerEpoch = 1L)
            }

            // H-1 defense-in-depth self-heal reconnects DISCONNECTED active session (except when shuttingDown or Error 12)
            val mockVault = mockk<CredentialVault>(relaxed = true) {
                every { getAccessToken() } returns "fresh-tok"
            }
            stateFlow.value = StreamerConnectionState.DISCONNECTED
            assertFalse(
                SentinelForegroundService.selfHealDisconnectedStreamerIfNeeded(
                    isShuttingDown = true,
                    activeLiveSymbols = symbols,
                    credentialVault = mockVault,
                    streamerClient = mockStreamer,
                    scope = scope
                )
            )
            error12Flow.value = true
            assertFalse(
                SentinelForegroundService.selfHealDisconnectedStreamerIfNeeded(
                    isShuttingDown = false,
                    activeLiveSymbols = symbols,
                    credentialVault = mockVault,
                    streamerClient = mockStreamer,
                    scope = scope
                )
            )
            error12Flow.value = false
            assertTrue(
                SentinelForegroundService.selfHealDisconnectedStreamerIfNeeded(
                    isShuttingDown = false,
                    activeLiveSymbols = symbols,
                    credentialVault = mockVault,
                    streamerClient = mockStreamer,
                    scope = scope
                )
            )
            coVerify(exactly = 1) { mockStreamer.connectLive(scope, symbols) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Pass 4 H-1 explicit ACTION_START clears Error 12 halt on both fresh start and running resume whereas START_STICKY preserves halt`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val okHttp = OkHttpClient()
        val vault = mockk<CredentialVault>(relaxed = true) {
            every { getAccessToken() } returns "live-tok"
        }
        val symbols = setOf("GOOG", "SPY")

        // Real SchwabStreamerClient wired with mock SchwabTraderApi that records connectLive attempts
        val connectAttempts = AtomicInteger(0)
        val traderApi = mockk<SchwabTraderApi> {
            coEvery { getUserPreference() } coAnswers {
                connectAttempts.incrementAndGet()
                throw java.io.IOException("Simulated offline test endpoint")
            }
        }
        val client = SchwabStreamerClient(
            okHttpClient = okHttp,
            json = json,
            traderApi = traderApi,
            tokenRefreshCoordinator = null,
            credentialVault = vault,
            nowProvider = { 100_000L }
        )

        try {
            // 1. OS START_STICKY restart (isExplicitStart = false) MUST NOT clear Error 12 halt (both fresh and running)
            client.processIncomingMessage("""{"notify":[{"code":12,"msg":"CLOSE_CONNECTION"}]}""", 1_000L)
            assertTrue(client.isError12Halted())
            val sessionId = AtomicLong(1L)
            val startId = AtomicInteger(1)

            val stickyFresh = SentinelForegroundService.handleActionStartLocked(
                serviceSessionId = sessionId,
                latestStartId = startId,
                startId = 2,
                isShuttingDown = false,
                monitoringJobActive = false,
                startupJobActive = false,
                isExplicitStart = false,
                streamerClient = client
            )
            assertEquals(2L, stickyFresh.newSessionId)
            assertFalse(stickyFresh.shouldTriggerExplicitError12Reconnect)
            assertTrue(client.isError12Halted(), "OS START_STICKY must preserve Error 12 halt")
            assertFalse(
                SentinelForegroundService.selfHealDisconnectedStreamerIfNeeded(
                    isShuttingDown = false,
                    activeLiveSymbols = symbols,
                    credentialVault = vault,
                    streamerClient = client,
                    scope = scope
                )
            )
            assertEquals(0, connectAttempts.get())

            val stickyWhileRunning = SentinelForegroundService.handleActionStartLocked(
                serviceSessionId = sessionId,
                latestStartId = startId,
                startId = 3,
                isShuttingDown = false,
                monitoringJobActive = true,
                startupJobActive = false,
                isExplicitStart = false,
                streamerClient = client
            )
            assertNull(stickyWhileRunning.newSessionId)
            assertFalse(stickyWhileRunning.shouldTriggerExplicitError12Reconnect)
            assertTrue(client.isError12Halted())
            assertEquals(0, connectAttempts.get())

            // 2. Explicit ACTION_START after Stop Monitor (fresh session) clears Error 12 and enables connectLive
            client.disconnectGracefully()
            assertTrue(client.isError12Halted())
            val ownerBeforeExplicitFresh = client.currentOwnerEpoch()
            val explicitFresh = SentinelForegroundService.handleActionStartLocked(
                serviceSessionId = sessionId,
                latestStartId = startId,
                startId = 4,
                isShuttingDown = false,
                monitoringJobActive = false,
                startupJobActive = false,
                isExplicitStart = true,
                streamerClient = client
            )
            assertEquals(3L, explicitFresh.newSessionId)
            assertFalse(explicitFresh.shouldTriggerExplicitError12Reconnect)
            assertEquals(ownerBeforeExplicitFresh + 1L, client.currentOwnerEpoch())
            assertFalse(client.isError12Halted())
            assertEquals(StreamerConnectionState.DISCONNECTED, client.connectionState.value)
            assertTrue(
                SentinelForegroundService.selfHealDisconnectedStreamerIfNeeded(
                    isShuttingDown = false,
                    activeLiveSymbols = symbols,
                    credentialVault = vault,
                    streamerClient = client,
                    scope = scope
                )
            )
            assertEquals(1, connectAttempts.get())

            // 3. Explicit ACTION_START while already running in Error 12 keeps sessionId, clears halt, and requests reconnect
            client.disconnect()
            client.processIncomingMessage("""{"notify":[{"code":12,"msg":"CLOSE_CONNECTION"}]}""", 2_000L)
            assertTrue(client.isError12Halted())
            assertEquals(StreamerConnectionState.ERROR_CONFLICT_CLOSE_12, client.connectionState.value)

            val explicitWhileRunning = SentinelForegroundService.handleActionStartLocked(
                serviceSessionId = sessionId,
                latestStartId = startId,
                startId = 5,
                isShuttingDown = false,
                monitoringJobActive = true,
                startupJobActive = false,
                isExplicitStart = true,
                streamerClient = client
            )
            assertNull(explicitWhileRunning.newSessionId)
            assertTrue(explicitWhileRunning.shouldTriggerExplicitError12Reconnect)
            assertEquals(3L, sessionId.get())
            assertFalse(client.isError12Halted())
            assertEquals(StreamerConnectionState.DISCONNECTED, client.connectionState.value)
            assertTrue(
                SentinelForegroundService.selfHealDisconnectedStreamerIfNeeded(
                    isShuttingDown = false,
                    activeLiveSymbols = symbols,
                    credentialVault = vault,
                    streamerClient = client,
                    scope = scope
                )
            )
            assertEquals(2, connectAttempts.get())
        } finally {
            client.disconnect()
            okHttp.connectionPool.evictAll()
            okHttp.dispatcher.executorService.shutdown()
            scope.cancel()
        }
    }
}
