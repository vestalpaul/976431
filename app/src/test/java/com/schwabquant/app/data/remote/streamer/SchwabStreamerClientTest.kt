package com.schwabquant.app.data.remote.streamer

import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import com.schwabquant.app.data.remote.dto.StreamerInfoDto
import com.schwabquant.app.data.remote.dto.UserPreferenceDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SchwabStreamerClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun createClient(now: () -> Long = { 100_000L }): SchwabStreamerClient =
        SchwabStreamerClient(
            okHttpClient = OkHttpClient(),
            json = json,
            traderApi = null,
            tokenRefreshCoordinator = null,
            credentialVault = null,
            nowProvider = now
        )

    @Test
    fun `partial delta frames preserve previous bid and ask in cache`() {
        val client = createClient()

        // Frame 1: Full Level-1 Equity snapshot for GOOG (fields 1=Bid, 2=Ask, 3=Last, 32=Status)
        val fullFrame = """
            {
              "data": [
                {
                  "service": "LEVELONE_EQUITIES",
                  "content": [
                    {
                      "key": "GOOG",
                      "1": 270.10,
                      "2": 270.30,
                      "3": 270.20,
                      "32": "Normal",
                      "33": 270.20
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        client.processIncomingMessage(fullFrame, nowEpochMs = 1_000L)

        // Frame 2: Partial delta updating ONLY Last Price (field 3) and Volume (field 8)
        val partialDelta = """
            {
              "data": [
                {
                  "service": "LEVELONE_EQUITIES",
                  "content": [
                    {
                      "key": "GOOG",
                      "3": 268.00,
                      "8": 1520000
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        client.processIncomingMessage(partialDelta, nowEpochMs = 2_000L)

        val merged = client.getCachedQuote("GOOG")
        assertNotNull(merged)
        assertEquals(270.10, merged!!.bidPrice!!, 1e-6)
        assertEquals(270.30, merged.askPrice!!, 1e-6)
        assertEquals(268.00, merged.lastPrice!!, 1e-6)
        assertEquals(1520000L, merged.totalVolume)
        assertEquals("Normal", merged.securityStatus)
        assertEquals(2_000L, merged.receivedAtEpochMs)
    }

    @Test
    fun `normalizes share-class symbols BRK dot B to BRK slash B on outbound and back on inbound`() {
        val subEnvelope = StreamerRequestFactory.createEquitySubscription(
            requestId = "7",
            command = StreamerCommand.ADD,
            schwabClientCustomerId = "cust",
            schwabClientCorrelId = "correl",
            symbols = listOf("BRK.B", "GOOG")
        )
        assertEquals("BRK/B,GOOG", subEnvelope.requests.first().parameters["keys"])

        val client = createClient()
        val inboundBrk = """
            {
              "data": [
                {
                  "service": "LEVELONE_EQUITIES",
                  "content": [
                    { "key": "BRK/B", "1": 450.25, "2": 450.55, "32": "Normal" }
                  ]
                }
              ]
            }
        """.trimIndent()
        client.processIncomingMessage(inboundBrk, nowEpochMs = 5_000L)

        val cached = client.getCachedQuote("BRK.B")
        assertNotNull(cached)
        assertEquals("BRK.B", cached!!.symbol)
        assertEquals(450.25, cached.bidPrice!!, 1e-6)
    }

    @Test
    fun `handles heartbeat notify and transitions cleanly to AUTHENTICATED on ADMIN LOGIN code 0`() {
        val client = createClient()

        val loginSuccess = """
            {
              "response": [
                {
                  "service": "ADMIN",
                  "command": "LOGIN",
                  "content": { "code": 0, "msg": "server=s1;status=PN" }
                }
              ]
            }
        """.trimIndent()
        client.processIncomingMessage(loginSuccess, nowEpochMs = 10_000L)
        assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)
        assertEquals(10_000L, client.lastHeartbeatEpochMs.value)

        val heartbeat = """{ "notify": [ { "heartbeat": "1730000000000" } ] }"""
        client.processIncomingMessage(heartbeat, nowEpochMs = 20_000L)
        assertEquals(20_000L, client.lastHeartbeatEpochMs.value)
    }

    @Test
    fun `halts auto-reconnect immediately on Schwab Error 12 CLOSE_CONNECTION`() {
        val client = createClient()
        val error12Notify = """
            {
              "notify": [
                { "code": 12, "msg": "CLOSE_CONNECTION" }
              ]
            }
        """.trimIndent()

        client.processIncomingMessage(error12Notify, nowEpochMs = 30_000L)

        assertEquals(StreamerConnectionState.ERROR_CONFLICT_CLOSE_12, client.connectionState.value)
        assertTrue(client.lastErrorMessage.value.orEmpty().contains("Error 12"))
    }

    @Test
    fun `H-4 ignores 0_0 bid and ask in partial frames and clears quoteCache on disconnect`() {
        val client = createClient()
        val initial = """
            {
              "data": [
                {
                  "service": "LEVELONE_EQUITIES",
                  "content": [
                    { "key": "GOOG", "1": 270.10, "2": 270.30, "32": "Normal" }
                  ]
                }
              ]
            }
        """.trimIndent()
        client.processIncomingMessage(initial, nowEpochMs = 1_000L)

        // Partial frame emitting 0.0 for bid/ask must NOT overwrite cached 270.10 / 270.30
        val zeroFrame = """
            {
              "data": [
                {
                  "service": "LEVELONE_EQUITIES",
                  "content": [
                    { "key": "GOOG", "1": 0.0, "2": 0.0, "8": 99000 }
                  ]
                }
              ]
            }
        """.trimIndent()
        client.processIncomingMessage(zeroFrame, nowEpochMs = 2_000L)

        val cached = client.getCachedQuote("GOOG")
        assertNotNull(cached)
        assertEquals(270.10, cached!!.bidPrice!!, 1e-6)
        assertEquals(270.30, cached.askPrice!!, 1e-6)
        assertEquals(99000L, cached.totalVolume)

        // Disconnect clears quoteCache so stale pre-disconnect quotes never resurrect
        client.disconnect()
        assertNull(client.getCachedQuote("GOOG"))
        assertTrue(client.liveQuotesSnapshot.value.isEmpty())
    }

    @Test
    fun `M-3 malformed non-object and JsonNull elements in notify, response, and data do not throw`() {
        val client = createClient()
        val malformedFrames = listOf(
            """["not-an-object"]""",
            """{"notify": ["bad-primitive", {"content": null, "code": "nan"}]}""",
            """{"response": [null, {"service": "ADMIN", "command": "LOGIN", "content": "not-an-object"}]}""",
            """{"data": [{"service": "LEVELONE_EQUITIES", "content": [123, null, {"key": "GOOG", "1": 255.00, "2": 255.20}]}]}"""
        )

        malformedFrames.forEach { raw ->
            client.processIncomingMessage(raw, nowEpochMs = 5_000L)
        }

        val validQuote = client.getCachedQuote("GOOG")
        assertNotNull(validQuote)
        assertEquals(255.00, validQuote!!.bidPrice!!, 1e-6)
    }

    @Test
    fun `C-2 scheduleReconnect retries transient connect failure without self-canceling until AUTHENTICATED`() = runBlocking {
        val serverSocketRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocketRef.set(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
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
        val attempts = AtomicInteger(0)
        val traderApi = mockk<SchwabTraderApi> {
            coEvery { getUserPreference() } coAnswers {
                if (attempts.incrementAndGet() == 1) {
                    throw IOException("Transient userPreference timeout")
                }
                UserPreferenceDto(
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
        }
        val vault = mockk<CredentialVault>(relaxed = true) {
            every { getAccessToken() } returns "live-access-token"
        }
        val okHttp = OkHttpClient()
        val client = SchwabStreamerClient(
            okHttpClient = okHttp,
            json = json,
            traderApi = traderApi,
            tokenRefreshCoordinator = null,
            credentialVault = vault,
            nowProvider = { 100_000L },
            backoffDelaysMs = longArrayOf(20L, 40L)
        )

        try {
            val firstAttempt = client.connectLive(scope, setOf("GOOG"))
            assertTrue(firstAttempt.isFailure)
            assertEquals(StreamerConnectionState.RECONNECTING, client.connectionState.value)

            val deadlineMs = System.currentTimeMillis() + 3_000L
            while (client.connectionState.value != StreamerConnectionState.AUTHENTICATED &&
                System.currentTimeMillis() < deadlineMs
            ) {
                Thread.sleep(20L)
            }

            assertTrue(attempts.get() >= 2, "Expected reconnect retry after transient connectLive failure")
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)
        } finally {
            client.disconnect()
            serverSocketRef.get()?.close(1000, "test finished")
            okHttp.connectionPool.evictAll()
            okHttp.dispatcher.executorService.shutdown()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `H-3 computes hasFreshBidAskUpdate post positive filter and ignores delayed frames after disconnect or UNSUBS`() = runBlocking {
        val client = createClient()
        client.syncEquitySubscriptions(setOf("GOOG"))

        // Initial positive Bid/Ask sets hasFreshBidAskUpdate = true
        val initialFrame = """
            {
              "data": [
                {
                  "service": "LEVELONE_EQUITIES",
                  "content": [
                    { "key": "GOOG", "1": 270.10, "2": 270.30, "32": "Normal" }
                  ]
                }
              ]
            }
        """.trimIndent()
        client.processIncomingMessage(initialFrame, nowEpochMs = 1_000L)
        assertTrue(client.getCachedQuote("GOOG")!!.hasFreshBidAskUpdate)

        // Trade/volume-only delta sets hasFreshBidAskUpdate = false
        val tradeOnlyFrame = """
            {
              "data": [
                {
                  "service": "LEVELONE_EQUITIES",
                  "content": [
                    { "key": "GOOG", "3": 269.50, "8": 12000 }
                  ]
                }
              ]
            }
        """.trimIndent()
        client.processIncomingMessage(tradeOnlyFrame, nowEpochMs = 2_000L)
        assertFalse(client.getCachedQuote("GOOG")!!.hasFreshBidAskUpdate)

        // Zeroed bid/ask after positive filter must STILL report hasFreshBidAskUpdate = false
        val zeroedBidAskFrame = """
            {
              "data": [
                {
                  "service": "LEVELONE_EQUITIES",
                  "content": [
                    { "key": "GOOG", "1": 0.0, "2": 0.0 }
                  ]
                }
              ]
            }
        """.trimIndent()
        client.processIncomingMessage(zeroedBidAskFrame, nowEpochMs = 3_000L)
        assertFalse(client.getCachedQuote("GOOG")!!.hasFreshBidAskUpdate)

        // Positive Bid/Ask update sets hasFreshBidAskUpdate = true again
        client.processIncomingMessage(initialFrame, nowEpochMs = 4_000L)
        assertTrue(client.getCachedQuote("GOOG")!!.hasFreshBidAskUpdate)

        // Unsubscribe removes GOOG and ignores delayed in-flight frames
        client.syncEquitySubscriptions(emptySet())
        assertNull(client.getCachedQuote("GOOG"))
        client.processIncomingMessage(initialFrame, nowEpochMs = 5_000L)
        assertNull(client.getCachedQuote("GOOG"), "Delayed frame after UNSUBS must not resurrect quoteCache")

        // Disconnect also clears and ignores delayed frames
        client.syncEquitySubscriptions(setOf("AAPL"))
        val aaplFrame = """{"data":[{"service":"LEVELONE_EQUITIES","content":[{"key":"AAPL","1":220.0,"2":220.2}]}]}"""
        client.processIncomingMessage(aaplFrame, nowEpochMs = 6_000L)
        assertNotNull(client.getCachedQuote("AAPL"))
        client.disconnect()
        client.processIncomingMessage(aaplFrame, nowEpochMs = 7_000L)
        assertNull(client.getCachedQuote("AAPL"), "Delayed frame after disconnect() must not resurrect quoteCache")
    }

    @Test
    fun `M-1 and M-2 ignore stale socket Error 12 and split positive Bid vs Ask freshness`() = runBlocking {
        val sockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
        val upgradedCount = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets.add(webSocket)
                        upgradedCount.incrementAndGet()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
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
        val vault = mockk<CredentialVault>(relaxed = true) {
            every { getAccessToken() } returns "tok-1"
        }
        val client = SchwabStreamerClient(
            okHttpClient = okHttp,
            json = json,
            traderApi = traderApi,
            tokenRefreshCoordinator = null,
            credentialVault = vault,
            nowProvider = { 100_000L },
            backoffDelaysMs = longArrayOf(20L)
        )

        try {
            assertTrue(client.connectLive(scope, setOf("GOOG")).isSuccess)
            val deadline1 = System.currentTimeMillis() + 3_000L
            while (client.connectionState.value != StreamerConnectionState.AUTHENTICATED && System.currentTimeMillis() < deadline1) {
                Thread.sleep(20L)
            }
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)

            // Replace connection with Socket #2
            assertTrue(client.connectLive(scope, setOf("GOOG")).isSuccess)
            val deadline2 = System.currentTimeMillis() + 3_000L
            while (upgradedCount.get() < 2 && System.currentTimeMillis() < deadline2) {
                Thread.sleep(20L)
            }
            while (client.connectionState.value != StreamerConnectionState.AUTHENTICATED && System.currentTimeMillis() < deadline2) {
                Thread.sleep(20L)
            }

            // Stale Socket #1 emits late Error 12 -> Socket #2 must remain AUTHENTICATED!
            sockets.first().send("""{"notify":[{"code":12,"msg":"CLOSE_CONNECTION"}]}""")
            Thread.sleep(80L)
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)

            // M-2: Ask-only delta sets hasFreshBidUpdate = false, hasFreshAskUpdate = true
            client.processIncomingMessage(
                """{"data":[{"service":"LEVELONE_EQUITIES","content":[{"key":"GOOG","2":270.35}]}]}""",
                nowEpochMs = 2_000L
            )
            val askOnly = client.getCachedQuote("GOOG")!!
            assertFalse(askOnly.hasFreshBidUpdate)
            assertTrue(askOnly.hasFreshAskUpdate)

            // M-2: Bid-only delta sets hasFreshBidUpdate = true, hasFreshAskUpdate = false
            client.processIncomingMessage(
                """{"data":[{"service":"LEVELONE_EQUITIES","content":[{"key":"GOOG","1":270.15}]}]}""",
                nowEpochMs = 3_000L
            )
            val bidOnly = client.getCachedQuote("GOOG")!!
            assertTrue(bidOnly.hasFreshBidUpdate)
            assertFalse(bidOnly.hasFreshAskUpdate)
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
    fun `H-3 ADMIN LOGIN mutates AUTHENTICATED atomically with subscription flush and never clobbers Error 12 or DISCONNECTED`() = runBlocking {
        val outboundFrames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
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
        val vault = mockk<CredentialVault>(relaxed = true) {
            every { getAccessToken() } returns "tok-1"
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
            val syncJob = launch {
                client.syncEquitySubscriptions(setOf("GOOG", "NVDA"))
            }
            val deadline = System.currentTimeMillis() + 3_000L
            while (client.connectionState.value != StreamerConnectionState.AUTHENTICATED &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(15L)
            }
            syncJob.join()
            // Also trigger an explicit sync after LOGIN to cover post-auth ADD determinism
            client.syncEquitySubscriptions(setOf("GOOG", "NVDA"))
            Thread.sleep(50L)

            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)
            val equityFrames = outboundFrames.filter { it.contains("LEVELONE_EQUITIES") }
            assertTrue(equityFrames.any { it.contains("GOOG") }, "GOOG subscription must not be lost during LOGIN")
            assertTrue(equityFrames.any { it.contains("NVDA") }, "NVDA subscription must not be dropped across LOGIN transition")

            // Error 12 must NOT be clobbered by a late LOGIN frame
            client.processIncomingMessage("""{"notify":[{"code":12,"msg":"CLOSE_CONNECTION"}]}""", 200_000L)
            assertEquals(StreamerConnectionState.ERROR_CONFLICT_CLOSE_12, client.connectionState.value)
            client.processIncomingMessage(
                """{"response":[{"service":"ADMIN","command":"LOGIN","content":{"code":0,"msg":"late"}}]}""",
                201_000L
            )
            assertEquals(StreamerConnectionState.ERROR_CONFLICT_CLOSE_12, client.connectionState.value)

            // DISCONNECTED must NOT be clobbered by a late LOGIN frame
            client.disconnect()
            assertEquals(StreamerConnectionState.DISCONNECTED, client.connectionState.value)
            client.processIncomingMessage(
                """{"response":[{"service":"ADMIN","command":"LOGIN","content":{"code":0,"msg":"late"}}]}""",
                202_000L
            )
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
    fun `M-1 and M-2 generation-scoped disconnectGracefully and scheduleReconnect protect active session`() = runBlocking {
        val sockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
        val upgradedCount = AtomicInteger(0)
        val prefCount = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets.add(webSocket)
                        upgradedCount.incrementAndGet()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
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
            coEvery { getUserPreference() } coAnswers {
                prefCount.incrementAndGet()
                UserPreferenceDto(
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
        }
        val vault = mockk<CredentialVault>(relaxed = true) {
            every { getAccessToken() } returns "tok-1"
        }
        val client = SchwabStreamerClient(
            okHttpClient = okHttp,
            json = json,
            traderApi = traderApi,
            tokenRefreshCoordinator = null,
            credentialVault = vault,
            nowProvider = { 100_000L },
            backoffDelaysMs = longArrayOf(20L, 40L)
        )

        try {
            assertTrue(client.connectLive(scope, setOf("GOOG")).isSuccess)
            val d1 = System.currentTimeMillis() + 3_000L
            while (client.connectionState.value != StreamerConnectionState.AUTHENTICATED && System.currentTimeMillis() < d1) {
                Thread.sleep(15L)
            }
            val staleGen = client.currentConnectionGeneration()

            // Start newer connection generation
            assertTrue(client.connectLive(scope, setOf("NVDA")).isSuccess)
            val d2 = System.currentTimeMillis() + 3_000L
            while ((upgradedCount.get() < 2 || client.connectionState.value != StreamerConnectionState.AUTHENTICATED) &&
                System.currentTimeMillis() < d2
            ) {
                Thread.sleep(15L)
            }
            val activeGen = client.currentConnectionGeneration()
            assertTrue(activeGen > staleGen)

            // M-1: disconnectGracefully with staleGen must NOT tear down the active AUTHENTICATED session
            client.disconnectGracefully(expectedGeneration = staleGen)
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)
            assertEquals(activeGen, client.currentConnectionGeneration())

            // M-2: scheduleReconnect with staleGen OR while AUTHENTICATED must abort immediately (no extra getUserPreference)
            val prefsBefore = prefCount.get()
            client.scheduleReconnect(scope, staleGen)
            client.scheduleReconnect(scope, activeGen)
            Thread.sleep(80L)
            assertEquals(prefsBefore, prefCount.get())
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)

            // Matching generation disconnectGracefully tears down cleanly
            client.disconnectGracefully(expectedGeneration = activeGen)
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
    fun `H-1 advanceOwnerEpoch preserves AUTHENTICATED socket while stale ownerEpoch disconnectGracefully is a no-op`() = runBlocking {
        val sockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets.add(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
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
        val vault = mockk<CredentialVault>(relaxed = true) {
            every { getAccessToken() } returns "tok-1"
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
            while (client.connectionState.value != StreamerConnectionState.AUTHENTICATED && System.currentTimeMillis() < deadline) {
                Thread.sleep(15L)
            }
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)

            val sameGen = client.currentConnectionGeneration()
            val oldOwnerEpoch = client.currentOwnerEpoch()

            // Emit initial GOOG quote into cache
            sockets.single().send("""{"data":[{"service":"LEVELONE_EQUITIES","content":[{"key":"GOOG","1":270.10,"2":270.30}]}]}""")
            val qDeadline = System.currentTimeMillis() + 2_000L
            while (client.getCachedQuote("GOOG") == null && System.currentTimeMillis() < qDeadline) {
                Thread.sleep(15L)
            }
            assertEquals(270.10, client.getCachedQuote("GOOG")!!.bidPrice!!, 1e-6)

            // Advance ownerEpoch while socket remains AUTHENTICATED -> connectionGeneration & socket intact!
            val newOwnerEpoch = client.advanceOwnerEpoch()
            assertEquals(oldOwnerEpoch + 1L, newOwnerEpoch)
            assertEquals(sameGen, client.currentConnectionGeneration())
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)

            // Stale Path A / Path B disconnect carrying sameGen + oldOwnerEpoch must be ignored as a no-op!
            client.disconnectGracefully(expectedGeneration = sameGen, expectedOwnerEpoch = oldOwnerEpoch)
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)
            assertEquals(sameGen, client.currentConnectionGeneration())
            assertNotNull(client.getCachedQuote("GOOG"))

            // Incoming quote on the SAME activeSocket continues updating quoteCache (intentionalDisconnect == false)
            sockets.single().send("""{"data":[{"service":"LEVELONE_EQUITIES","content":[{"key":"GOOG","1":271.50,"2":271.70}]}]}""")
            val q2Deadline = System.currentTimeMillis() + 2_000L
            while ((client.getCachedQuote("GOOG")?.bidPrice ?: 0.0) < 271.0 && System.currentTimeMillis() < q2Deadline) {
                Thread.sleep(15L)
            }
            assertEquals(271.50, client.getCachedQuote("GOOG")!!.bidPrice!!, 1e-6)

            // Matching sameGen + newOwnerEpoch disconnects cleanly
            client.disconnectGracefully(expectedGeneration = sameGen, expectedOwnerEpoch = newOwnerEpoch)
            assertEquals(StreamerConnectionState.DISCONNECTED, client.connectionState.value)
            assertNull(client.getCachedQuote("GOOG"))
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
    fun `Pass 4 H-1 disconnectGracefully preserves Error 12 halt while clearError12Halt resets state so connectLive succeeds`() = runBlocking {
        val sockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
        val prefCalls = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets.add(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
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
            coEvery { getUserPreference() } coAnswers {
                prefCalls.incrementAndGet()
                UserPreferenceDto(
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
        }
        val vault = mockk<CredentialVault>(relaxed = true) {
            every { getAccessToken() } returns "tok-1"
        }
        val client = SchwabStreamerClient(
            okHttpClient = okHttp,
            json = json,
            traderApi = traderApi,
            tokenRefreshCoordinator = null,
            credentialVault = vault,
            nowProvider = { 100_000L },
            backoffDelaysMs = longArrayOf(20L, 40L)
        )

        try {
            // Reach Error 12 CLOSE_CONNECTION
            assertTrue(client.connectLive(scope, setOf("GOOG")).isSuccess)
            val d1 = System.currentTimeMillis() + 3_000L
            while (client.connectionState.value != StreamerConnectionState.AUTHENTICATED && System.currentTimeMillis() < d1) {
                Thread.sleep(15L)
            }
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)

            sockets.single().send("""{"notify":[{"code":12,"msg":"CLOSE_CONNECTION"}]}""")
            val dErr12 = System.currentTimeMillis() + 2_000L
            while (client.connectionState.value != StreamerConnectionState.ERROR_CONFLICT_CLOSE_12 && System.currentTimeMillis() < dErr12) {
                Thread.sleep(15L)
            }
            assertTrue(client.isError12Halted())
            assertEquals(StreamerConnectionState.ERROR_CONFLICT_CLOSE_12, client.connectionState.value)

            // Stop / disconnectGracefully transitions state to DISCONNECTED but MUST keep error12Halted == true
            val prefsBefore = prefCalls.get()
            client.disconnectGracefully()
            assertTrue(client.isError12Halted())
            assertEquals(StreamerConnectionState.DISCONNECTED, client.connectionState.value)

            // Automated scheduleReconnect stays blocked while error12Halted == true
            client.scheduleReconnect(scope, client.currentConnectionGeneration())
            Thread.sleep(60L)
            assertEquals(prefsBefore, prefCalls.get())
            assertTrue(client.isError12Halted())

            // Explicit clearError12Halt() resets state cleanly and returns false on redundant call
            assertTrue(client.clearError12Halt())
            assertFalse(client.isError12Halted())
            assertEquals(StreamerConnectionState.DISCONNECTED, client.connectionState.value)
            assertNull(client.lastErrorMessage.value)
            assertFalse(client.clearError12Halt())

            // Subsequent connectLive() recovers to AUTHENTICATED cleanly
            assertTrue(client.connectLive(scope, setOf("GOOG")).isSuccess)
            val d2 = System.currentTimeMillis() + 3_000L
            while (client.connectionState.value != StreamerConnectionState.AUTHENTICATED && System.currentTimeMillis() < d2) {
                Thread.sleep(15L)
            }
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)
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
    fun `Milestone 6 setChartOverlaySymbol manages union cleanly without disrupting Sentinel epoch generation or Error 12`() = runBlocking {
        val sockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
        val sentMessages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets.add(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        sentMessages.add(text)
                        if (text.contains("\"LOGIN\"")) {
                            webSocket.send(
                                """{"response":[{"service":"ADMIN","command":"LOGIN","content":{"code":0,"msg":"ok"}}]}"""
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
        val vault = mockk<CredentialVault>(relaxed = true) {
            every { getAccessToken() } returns "tok-1"
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
            while (client.connectionState.value != StreamerConnectionState.AUTHENTICATED && System.currentTimeMillis() < deadline) {
                Thread.sleep(15L)
            }
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)

            client.advanceOwnerEpoch()
            val genBefore = client.currentConnectionGeneration()
            val epochBefore = client.currentOwnerEpoch()
            val err12Before = client.isError12Halted()

            // Set Chart overlay to NVDA -> ADD NVDA while keeping GOOG
            sentMessages.clear()
            client.setChartOverlaySymbol("NVDA")
            val addDeadline = System.currentTimeMillis() + 1_500L
            while (sentMessages.none { it.contains("\"ADD\"") && it.contains("NVDA") } && System.currentTimeMillis() < addDeadline) {
                Thread.sleep(15L)
            }
            assertTrue(sentMessages.any { it.contains("\"ADD\"") && it.contains("NVDA") })
            assertFalse(sentMessages.any { it.contains("\"UNSUBS\"") && it.contains("GOOG") })

            client.processIncomingMessage(
                """{"data":[{"service":"LEVELONE_EQUITIES","content":[{"key":"NVDA","3":139.25}]}]}""",
                nowEpochMs = 101_000L
            )
            assertEquals(139.25, client.getCachedQuote("NVDA")?.lastPrice!!, 0.001)

            // Switching overlay to GOOG sends UNSUBS for NVDA ONLY, keeping GOOG
            sentMessages.clear()
            client.setChartOverlaySymbol("GOOG")
            val unsubsDeadline = System.currentTimeMillis() + 1_500L
            while (sentMessages.none { it.contains("\"UNSUBS\"") && it.contains("NVDA") } && System.currentTimeMillis() < unsubsDeadline) {
                Thread.sleep(15L)
            }
            assertTrue(sentMessages.any { it.contains("\"UNSUBS\"") && it.contains("NVDA") })
            assertNull(client.getCachedQuote("NVDA"))

            // Clearing overlay while overlay == GOOG (owned by Sentinel) MUST NOT unsubscribe GOOG
            sentMessages.clear()
            client.setChartOverlaySymbol(null)
            Thread.sleep(50L)
            assertFalse(sentMessages.any { it.contains("\"UNSUBS\"") && it.contains("GOOG") })

            // Lifecycle counters & Error 12 flag never mutated by Chart overlay actions
            assertEquals(1, sockets.size)
            assertEquals(genBefore, client.currentConnectionGeneration())
            assertEquals(epochBefore, client.currentOwnerEpoch())
            assertEquals(err12Before, client.isError12Halted())
            assertEquals(StreamerConnectionState.AUTHENTICATED, client.connectionState.value)
        } finally {
            client.disconnect()
            sockets.forEach { runCatching { it.close(1000, "done") } }
            okHttp.connectionPool.evictAll()
            okHttp.dispatcher.executorService.shutdown()
            scope.cancel()
            runCatching { server.shutdown() }
        }
    }
}
