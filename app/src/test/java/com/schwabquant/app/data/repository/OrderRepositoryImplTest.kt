package com.schwabquant.app.data.repository

import com.schwabquant.app.core.quant.OccSymbolFormatter
import com.schwabquant.app.core.quant.SchwabOrderPayloadBuilder
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.local.dao.AuditLogDao
import com.schwabquant.app.data.local.dao.ShadowLedgerDao
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.data.local.entity.ShadowOrderEntity
import com.schwabquant.app.data.local.entity.ShadowPositionEntity
import com.schwabquant.app.data.local.entity.WashSaleTradeEntity
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.ReplaceOrderOutcome
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.repository.MarketDataRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.math.BigDecimal
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OrderRepositoryImplTest {

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
    fun `C-1 firewall fails closed bidirectionally on mode mismatch, rejects SHADOW_PAPER hash, aborts mid-flight toggle, and routes cancelOrder by mode`() = runTest {
        // 1. DataStore = SHADOW, caller passes LIVE -> FAIL-CLOSED, 0 HTTP calls, 0 shadow writes
        val shadowHarness = createHarness(isLive = false)
        val liveMismatch = shadowHarness.repo.submitOrder(
            ticket = sampleEquityTicket(ExecutionMode.LIVE),
            risksAcknowledged = true,
            simulateImmediateMidFill = false
        )
        assertTrue(liveMismatch.isFailure)
        assertTrue(shadowHarness.auditDao.entries.any { it.category == "ORDER_MODE_MISMATCH_BLOCKED" })
        assertTrue(shadowHarness.repo.getWorkingOrders().getOrThrow().isEmpty())
        assertTrue(shadowHarness.repo.cancelOrder("hash-123", "SHADOW-999", ExecutionMode.SHADOW).isFailure)
        assertEquals(0, server.requestCount)

        // 2. DataStore = LIVE, caller passes SHADOW -> FAIL-CLOSED, 0 HTTP calls
        val liveHarness = createHarness(isLive = true)
        val shadowMismatch = liveHarness.repo.submitOrder(
            ticket = sampleEquityTicket(ExecutionMode.SHADOW),
            risksAcknowledged = true
        )
        assertTrue(shadowMismatch.isFailure)
        assertTrue(liveHarness.auditDao.entries.any { it.category == "ORDER_MODE_MISMATCH_BLOCKED" })

        // 3. DataStore = LIVE, ticket carries SHADOW_PAPER accountHash -> FAIL-CLOSED (never silently rewrite)
        val shadowPaperRejected = liveHarness.repo.submitOrder(
            ticket = sampleEquityTicket(ExecutionMode.LIVE).copy(accountHash = "SHADOW_PAPER"),
            risksAcknowledged = true
        )
        assertTrue(shadowPaperRejected.isFailure)

        // 4. Mid-flight toggle from LIVE -> SHADOW before POST aborts cleanly with 0 HTTP POSTs
        val toggleHarness = createHarness(isLive = true)
        toggleHarness.auditDao.onInsert = { entity ->
            if (entity.category == "LIVE_ORDER_ATTEMPT") {
                toggleHarness.liveFlow.value = false
            }
        }
        val abortedToggle = toggleHarness.repo.submitOrder(
            ticket = sampleEquityTicket(ExecutionMode.LIVE),
            risksAcknowledged = true
        )
        assertTrue(abortedToggle.isFailure)
        assertTrue(toggleHarness.auditDao.entries.any { it.category == "LIVE_ORDER_ABORTED_MODE_CHANGED" })

        // 5. Domain-routed cancelOrder cancels SHADOW-* in Room even while DataStore = LIVE without hitting HTTP
        liveHarness.shadowDao.upsertOrder(
            ShadowOrderEntity(
                orderId = "SHADOW-77",
                symbol = "GOOG",
                strategyType = "SINGLE",
                orderType = "LIMIT",
                session = "NORMAL",
                duration = "DAY",
                status = "WORKING",
                limitPrice = "260.00",
                stopPrice = null,
                fillPrice = null,
                quantity = 1,
                legsJson = "[]",
                submittedAtEpochMs = 1_000L,
                filledAtEpochMs = null
            )
        )
        assertTrue(liveHarness.repo.cancelOrder("SHADOW_PAPER", "SHADOW-77", ExecutionMode.SHADOW).isSuccess)
        assertEquals("CANCELED", liveHarness.shadowDao.getOrderById("SHADOW-77")?.status)
        assertEquals(0, server.requestCount)

        // 6. Pass #3 C-2: cancelOrder(LIVE) while DataStore = SHADOW fails closed with ORDER_MODE_MISMATCH_BLOCKED and 0 HTTP calls
        val canceledLiveInShadow = shadowHarness.repo.cancelOrder("hash-123", "999", ExecutionMode.LIVE)
        assertTrue(canceledLiveInShadow.isFailure)
        assertTrue(shadowHarness.auditDao.entries.any { it.category == "ORDER_MODE_MISMATCH_BLOCKED" })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `live mode 201 Created parses Location orderId and logs audit entry`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.method == "POST" && request.path == "/accounts/hash-123/orders") {
                    MockResponse()
                        .setResponseCode(201)
                        .addHeader("Location", "https://api.schwabapi.com/trader/v1/accounts/hash-123/orders/987654321")
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }

        val h = createHarness(isLive = true)
        val result = h.repo.submitOrder(
            ticket = sampleEquityTicket(ExecutionMode.LIVE),
            risksAcknowledged = true,
            overriddenRiskCodes = listOf("LOW_CASH_RESERVE")
        ).getOrThrow()

        assertEquals("987654321", result.orderId)
        assertEquals(ExecutionMode.LIVE, result.executionMode)
        assertEquals("SUBMITTED", result.status)
        assertTrue(h.auditDao.entries.any { it.category == "RISK_OVERRIDE_ACK" })
        assertTrue(h.auditDao.entries.any { it.category == "LIVE_ORDER_SUBMIT" && it.message.contains("987654321") })
    }

    @Test
    fun `replaceOrder aborts replacement when prior order status returns FILLED after cancel`() = runTest {
        var postCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "DELETE" && path == "/accounts/hash-123/orders/111" ->
                        MockResponse().setResponseCode(200)
                    request.method == "GET" && path == "/accounts/hash-123/orders/111" ->
                        MockResponse().setResponseCode(200).setBody("""{"orderId":111,"status":"FILLED"}""")
                    request.method == "DELETE" && path == "/accounts/hash-123/orders/222" ->
                        MockResponse().setResponseCode(200)
                    request.method == "GET" && path == "/accounts/hash-123/orders/222" ->
                        MockResponse().setResponseCode(200).setBody("""{"orderId":222,"status":"CANCELED"}""")
                    request.method == "POST" && path == "/accounts/hash-123/orders" -> {
                        postCount++
                        MockResponse()
                            .setResponseCode(201)
                            .addHeader("Location", "/accounts/hash-123/orders/333")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val h = createHarness(isLive = true)
        val replacement = sampleEquityTicket(ExecutionMode.LIVE).copy(limitPrice = BigDecimal("270.05"))

        val aborted = h.repo.replaceOrder(
            accountHash = "hash-123",
            oldOrderId = "111",
            replacementTicket = replacement,
            risksAcknowledged = true,
            cancelToReplaceDelayMs = 0L
        ).getOrThrow()
        assertTrue(aborted is ReplaceOrderOutcome.AbortedAlreadyFilled)
        assertEquals(0, postCount, "Must NEVER send POST /orders if prior order already FILLED")

        val replaced = h.repo.replaceOrder(
            accountHash = "hash-123",
            oldOrderId = "222",
            replacementTicket = replacement,
            risksAcknowledged = true,
            cancelToReplaceDelayMs = 0L
        ).getOrThrow()
        assertTrue(replaced is ReplaceOrderOutcome.Replaced)
        assertEquals("333", (replaced as ReplaceOrderOutcome.Replaced).result.orderId)
        assertEquals(1, postCount)
    }

    @Test
    fun `C-1 replaceOrder aborts and logs REPLACE_ABORTED_PARTIAL_FILL when canceled order had partial fills`() = runTest {
        var postCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "DELETE" && path == "/accounts/hash-123/orders/444" ->
                        MockResponse().setResponseCode(200)
                    request.method == "GET" && path == "/accounts/hash-123/orders/444" ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"orderId":444,"status":"CANCELED","quantity":16.0,"filledQuantity":6.0,"remainingQuantity":0.0}"""
                        )
                    request.method == "POST" && path == "/accounts/hash-123/orders" -> {
                        postCount++
                        MockResponse().setResponseCode(201).addHeader("Location", "/accounts/hash-123/orders/555")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val h = createHarness(isLive = true)
        val replacement = sampleEquityTicket(ExecutionMode.LIVE).copy(limitPrice = BigDecimal("270.05"))
        val outcome = h.repo.replaceOrder(
            accountHash = "hash-123",
            oldOrderId = "444",
            replacementTicket = replacement,
            risksAcknowledged = true,
            cancelToReplaceDelayMs = 0L
        ).getOrThrow()

        assertTrue(outcome is ReplaceOrderOutcome.AbortedAlreadyFilled)
        val aborted = outcome as ReplaceOrderOutcome.AbortedAlreadyFilled
        assertEquals(6.0, aborted.filledQuantity, 1e-6)
        assertEquals(0, postCount, "Must NEVER submit replacement when partial fill occurred on canceled order")
        assertTrue(h.auditDao.entries.any { it.category == "REPLACE_ABORTED_PARTIAL_FILL" })
    }

    @Test
    fun `C-6 M-2 live submitOrder without Location header validates active working single-match or returns UNTRACKED`() = runTest {
        val nowMs = 1_730_000_000_000L
        val freshIso = Instant.ofEpochMilli(nowMs).toString()
        val staleIso = Instant.ofEpochMilli(nowMs - 120_000L).toString()
        var scenario = "VALID_SINGLE"

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path == "/accounts/hash-123/orders" ->
                        MockResponse().setResponseCode(201) // No Location header
                    request.method == "GET" && path.startsWith("/accounts/hash-123/orders") -> {
                        val body = when (scenario) {
                            "VALID_SINGLE" ->
                                """[{"orderId":777888,"status":"WORKING","enteredTime":"$freshIso","orderLegCollection":[{"instruction":"SELL","quantity":16.0,"instrument":{"symbol":"GOOG","assetType":"EQUITY"}}]}]"""
                            "STALE_AND_WRONG_SIDE" ->
                                """[
                                    {"orderId":111001,"status":"WORKING","enteredTime":"$staleIso","orderLegCollection":[{"instruction":"SELL","quantity":16.0,"instrument":{"symbol":"GOOG","assetType":"EQUITY"}}]},
                                    {"orderId":111002,"status":"WORKING","enteredTime":"$freshIso","orderLegCollection":[{"instruction":"BUY","quantity":16.0,"instrument":{"symbol":"GOOG","assetType":"EQUITY"}}]}
                                ]"""
                            "AMBIGUOUS_TWO" ->
                                """[
                                    {"orderId":222001,"status":"WORKING","enteredTime":"$freshIso","orderLegCollection":[{"instruction":"SELL","quantity":16.0,"instrument":{"symbol":"GOOG","assetType":"EQUITY"}}]},
                                    {"orderId":222002,"status":"WORKING","enteredTime":"$freshIso","orderLegCollection":[{"instruction":"SELL","quantity":16.0,"instrument":{"symbol":"GOOG","assetType":"EQUITY"}}]}
                                ]"""
                            else -> "[]"
                        }
                        MockResponse().setResponseCode(200).setBody(body)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val h = createHarness(isLive = true, nowProvider = { nowMs })
        val resolved = h.repo.submitOrder(
            ticket = sampleEquityTicket(ExecutionMode.LIVE),
            risksAcknowledged = true
        ).getOrThrow()
        assertEquals("777888", resolved.orderId)
        assertEquals("SUBMITTED", resolved.status)

        scenario = "STALE_AND_WRONG_SIDE"
        val staleRejected = h.repo.submitOrder(
            ticket = sampleEquityTicket(ExecutionMode.LIVE),
            risksAcknowledged = true
        ).getOrThrow()
        assertEquals("", staleRejected.orderId)
        assertEquals("UNTRACKED", staleRejected.status)

        scenario = "AMBIGUOUS_TWO"
        val ambiguousRejected = h.repo.submitOrder(
            ticket = sampleEquityTicket(ExecutionMode.LIVE),
            risksAcknowledged = true
        ).getOrThrow()
        assertEquals("", ambiguousRejected.orderId)
        assertEquals("UNTRACKED", ambiguousRejected.status)
        assertTrue(h.auditDao.entries.any { it.category == "LIVE_ORDER_UNTRACKED_NO_LOCATION" })
    }

    @Test
    fun `C-3 getWorkingOrders queries 3 GTC chunks, parses nested OCO children, and fails closed if any chunk fails`() = runTest {
        val nowMs = 1_730_000_000_000L
        val chunkCount = AtomicInteger(0)
        var failSecondChunk = false

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (request.method == "GET" && path.startsWith("/accounts/hash-123/orders")) {
                    val idx = chunkCount.incrementAndGet()
                    if (failSecondChunk && idx == 2) {
                        return MockResponse().setResponseCode(500)
                    }
                    val body = if (idx == 1) {
                        """
                        [
                          {
                            "orderId": 888001,
                            "orderStrategyType": "OCO",
                            "status": "AWAITING_PARENT_ORDER",
                            "enteredTime": "${Instant.ofEpochMilli(nowMs)}",
                            "childOrderStrategies": [
                              {
                                "orderId": 888002,
                                "orderType": "LIMIT",
                                "session": "NORMAL",
                                "duration": "GOOD_TILL_CANCEL",
                                "price": 295.00,
                                "status": "WORKING",
                                "orderLegCollection": [
                                  {"instruction": "SELL", "quantity": 16.0, "instrument": {"symbol": "GOOG", "assetType": "EQUITY"}}
                                ]
                              },
                              {
                                "orderId": 888003,
                                "orderType": "STOP",
                                "session": "NORMAL",
                                "duration": "GOOD_TILL_CANCEL",
                                "stopPrice": 265.00,
                                "status": "AWAITING_STOP_CONDITION",
                                "orderLegCollection": [
                                  {"instruction": "SELL", "quantity": 16.0, "instrument": {"symbol": "GOOG", "assetType": "EQUITY"}}
                                ]
                              }
                            ]
                          }
                        ]
                        """.trimIndent()
                    } else {
                        "[]"
                    }
                    return MockResponse().setResponseCode(200).setBody(body)
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val h = createHarness(isLive = true, nowProvider = { nowMs })
        val working = h.repo.getWorkingOrders(forceRefresh = true).getOrThrow()
        assertEquals(3, chunkCount.get())
        assertEquals(1, working.size)
        assertEquals("888001", working.single().orderId)
        assertEquals("OCO", working.single().strategyType)
        assertTrue(working.single().hasEquityLegs)
        assertEquals(setOf("GOOG"), working.single().legSymbols)

        // Chunk 2 failure must fail closed rather than returning partial chunk 1 orders
        chunkCount.set(0)
        failSecondChunk = true
        val partialFailure = h.repo.getWorkingOrders(forceRefresh = true)
        assertTrue(partialFailure.isFailure)
    }

    @Test
    fun `C-5 mid-flight cancellation of submitOrder completes POST inside NonCancellable and auto-cancels captured orderId`() = runBlocking {
        val postStarted = CountDownLatch(1)
        val allowPostResponse = CountDownLatch(1)
        val deleteReceived = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path == "/accounts/hash-123/orders" -> {
                        postStarted.countDown()
                        allowPostResponse.await(5, TimeUnit.SECONDS)
                        MockResponse()
                            .setResponseCode(201)
                            .addHeader("Location", "https://api.schwabapi.com/trader/v1/accounts/hash-123/orders/998877")
                    }
                    request.method == "DELETE" && path == "/accounts/hash-123/orders/998877" -> {
                        deleteReceived.countDown()
                        MockResponse().setResponseCode(200)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val h = createHarness(isLive = true)
        var sawCancellation = false
        val job = launch(Dispatchers.IO) {
            try {
                h.repo.submitOrder(
                    ticket = sampleEquityTicket(ExecutionMode.LIVE),
                    risksAcknowledged = true
                )
            } catch (_: CancellationException) {
                sawCancellation = true
            }
        }

        assertTrue(postStarted.await(5, TimeUnit.SECONDS))
        job.cancel()
        allowPostResponse.countDown()
        job.join()

        assertTrue(sawCancellation, "Expected CancellationException after cleanup")
        assertTrue(deleteReceived.await(5, TimeUnit.SECONDS), "Must issue DELETE for newly captured order 998877")
        assertTrue(h.auditDao.entries.any { it.category == "LIVE_ORDER_CANCELED_AFTER_MIDFLIGHT_CANCEL" })
    }

    @Test
    fun `C-3 M-3 H-3 AWAITING_STOP_CONDITION retained, OCO child fill aggregated, and escalated remainder repriced`() = runTest {
        val nowMs = 1_730_000_000_000L
        var lastPostedBody = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path == "/accounts/hash-123/orders/7001" ->
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "orderId": 7001,
                              "orderStrategyType": "OCO",
                              "status": "CANCELED",
                              "quantity": 10.0,
                              "childOrderStrategies": [
                                {"orderId": 7002, "status": "CANCELED", "filledQuantity": 4.0},
                                {"orderId": 7003, "status": "PARTIALLY_FILLED", "filledQuantity": 0.0}
                              ]
                            }
                            """.trimIndent()
                        )
                    request.method == "GET" && path == "/accounts/hash-123/orders/7002" ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"orderId":7002,"status":"REJECTED","quantity":10.0,"filledQuantity":4.0,"remainingQuantity":6.0}"""
                        )
                    request.method == "GET" && path.startsWith("/accounts/hash-123/orders") ->
                        MockResponse().setResponseCode(200).setBody(
                            """[{"orderId":6001,"status":"AWAITING_STOP_CONDITION","enteredTime":"${Instant.ofEpochMilli(nowMs)}","orderLegCollection":[{"instruction":"SELL","quantity":5.0,"instrument":{"symbol":"GOOG","assetType":"EQUITY"}}]}]"""
                        )
                    request.method == "GET" && path.startsWith("/accounts/hash-123?") ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"securitiesAccount":{"positions":[{"longQuantity":10.0,"shortQuantity":0.0,"instrument":{"symbol":"GOOG","assetType":"EQUITY"}}]}}"""
                        )
                    request.method == "DELETE" && path.startsWith("/accounts/hash-123/orders/") ->
                        MockResponse().setResponseCode(200)
                    request.method == "POST" && path == "/accounts/hash-123/orders" -> {
                        lastPostedBody = request.body.readUtf8()
                        MockResponse().setResponseCode(201).addHeader("Location", "/accounts/hash-123/orders/7009")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val h = createHarness(isLive = true, nowProvider = { nowMs })

        // C-3: AWAITING_STOP_CONDITION is retained by getWorkingOrders()
        val working = h.repo.getWorkingOrders(forceRefresh = true).getOrThrow()
        assertEquals(listOf("6001"), working.map { it.orderId })

        // M-3: Child fill inside OCO prevents isCanceledWithZeroFill from returning true
        val ocoStatus = h.repo.getOrderStatus("hash-123", "7001").getOrThrow()
        assertTrue(ocoStatus.filledQuantity >= 4.0)
        assertTrue(!ocoStatus.isCanceledWithZeroFill, "Child fill must keep isCanceledWithZeroFill = false")

        // H-3: Escalated reprice on REJECTED partial fill (4/10 filled) submits remaining qty = 6
        val repOutcome = h.repo.replaceOrder(
            accountHash = "hash-123",
            oldOrderId = "7002",
            replacementTicket = sampleEquityTicket(ExecutionMode.LIVE).copy(
                legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 10))
            ),
            risksAcknowledged = true,
            cancelToReplaceDelayMs = 0L,
            allowTerminalOrPartialReprice = true
        ).getOrThrow()
        assertTrue(repOutcome is ReplaceOrderOutcome.Replaced)
        assertTrue(lastPostedBody.contains("\"quantity\":6"))
    }

    @Test
    fun `C-1 canceling replaceOrder during settlement delay aborts before POST`() = runBlocking {
        val postCount = AtomicInteger(0)
        val deleteLatch = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "DELETE" && path == "/accounts/hash-123/orders/8100" -> {
                        deleteLatch.countDown()
                        MockResponse().setResponseCode(200)
                    }
                    request.method == "GET" && path == "/accounts/hash-123/orders/8100" ->
                        MockResponse().setResponseCode(200).setBody("""{"orderId":8100,"status":"CANCELED"}""")
                    request.method == "POST" && path == "/accounts/hash-123/orders" -> {
                        postCount.incrementAndGet()
                        MockResponse().setResponseCode(201).addHeader("Location", "/accounts/hash-123/orders/8101")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val h = createHarness(isLive = true)
        var canceled = false
        val job = launch(Dispatchers.IO) {
            try {
                h.repo.replaceOrder(
                    accountHash = "hash-123",
                    oldOrderId = "8100",
                    replacementTicket = sampleEquityTicket(ExecutionMode.LIVE),
                    risksAcknowledged = true,
                    cancelToReplaceDelayMs = 1_500L
                )
            } catch (_: CancellationException) {
                canceled = true
            }
        }
        assertTrue(deleteLatch.await(5, TimeUnit.SECONDS))
        delay(50L)
        job.cancel()
        job.join()

        assertTrue(canceled)
        assertEquals(0, postCount.get(), "Must NEVER POST replacement if canceled during 1.5s settlement sleep")
    }

    @Test
    fun `C-4 escalated reprice caps by prior order unfilled qty, prevents double-subtraction, and clamps to live shares`() = runTest {
        val postedBodies = mutableListOf<String>()
        var heldLongShares = 10.0
        var failAccountFetch = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "DELETE" -> MockResponse().setResponseCode(200)
                    request.method == "GET" && path == "/accounts/hash-123/orders/8001" ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"orderId":8001,"status":"REJECTED","quantity":10.0,"filledQuantity":6.0,"remainingQuantity":4.0}"""
                        )
                    request.method == "GET" && path == "/accounts/hash-123/orders/8002" ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"orderId":8002,"status":"CANCELED","quantity":16.0,"filledQuantity":10.0,"remainingQuantity":6.0}"""
                        )
                    request.method == "GET" && path.startsWith("/accounts/hash-123?") ->
                        if (failAccountFetch) {
                            MockResponse().setResponseCode(503)
                        } else {
                            MockResponse().setResponseCode(200).setBody(
                                """{"securitiesAccount":{"positions":[{"longQuantity":$heldLongShares,"shortQuantity":0.0,"instrument":{"symbol":"GOOG","assetType":"EQUITY"}}]}}"""
                            )
                        }
                    request.method == "POST" && path == "/accounts/hash-123/orders" -> {
                        postedBodies += request.body.readUtf8()
                        MockResponse().setResponseCode(201).addHeader("Location", "/accounts/hash-123/orders/900${postedBodies.size}")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val h = createHarness(isLive = true)

        // Case 1: rule.quantity = 100 (requested 100), prior order was 10 with 6 filled -> priorUnfilledCap = 4 (NEVER 94!)
        val oversized = h.repo.replaceOrder(
            accountHash = "hash-123",
            oldOrderId = "8001",
            replacementTicket = sampleEquityTicket(ExecutionMode.LIVE).copy(
                legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 100))
            ),
            risksAcknowledged = true,
            cancelToReplaceDelayMs = 0L,
            allowTerminalOrPartialReprice = true
        ).getOrThrow()
        assertTrue(oversized is ReplaceOrderOutcome.Replaced)
        assertTrue(postedBodies.last().contains("\"quantity\":4"))

        // Case 2: user edits shares to remaining 6 after 10 of 16 filled -> minOf(6, 6) = 6 (NO double subtraction!)
        heldLongShares = 6.0
        val userEditedRemaining = h.repo.replaceOrder(
            accountHash = "hash-123",
            oldOrderId = "8002",
            replacementTicket = sampleEquityTicket(ExecutionMode.LIVE).copy(
                legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 6))
            ),
            risksAcknowledged = true,
            cancelToReplaceDelayMs = 0L,
            allowTerminalOrPartialReprice = true
        ).getOrThrow()
        assertTrue(userEditedRemaining is ReplaceOrderOutcome.Replaced)
        assertTrue(postedBodies.last().contains("\"quantity\":6"))

        // Case 3: live held position shrunk to 2 shares < priorUnfilledCap 4 -> clamps to 2
        heldLongShares = 2.0
        val clampedToHeld = h.repo.replaceOrder(
            accountHash = "hash-123",
            oldOrderId = "8001",
            replacementTicket = sampleEquityTicket(ExecutionMode.LIVE).copy(
                legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 10))
            ),
            risksAcknowledged = true,
            cancelToReplaceDelayMs = 0L,
            allowTerminalOrPartialReprice = true
        ).getOrThrow()
        assertTrue(clampedToHeld is ReplaceOrderOutcome.Replaced)
        assertTrue(postedBodies.last().contains("\"quantity\":2"))

        // Case 4: flat position (0.0) or account fetch failure fails closed with 0 additional POSTs
        val postsBeforeAbort = postedBodies.size
        heldLongShares = 0.0
        assertTrue(
            h.repo.replaceOrder(
                accountHash = "hash-123",
                oldOrderId = "8001",
                replacementTicket = sampleEquityTicket(ExecutionMode.LIVE),
                risksAcknowledged = true,
                cancelToReplaceDelayMs = 0L,
                allowTerminalOrPartialReprice = true
            ).isFailure
        )
        failAccountFetch = true
        assertTrue(
            h.repo.replaceOrder(
                accountHash = "hash-123",
                oldOrderId = "8001",
                replacementTicket = sampleEquityTicket(ExecutionMode.LIVE),
                risksAcknowledged = true,
                cancelToReplaceDelayMs = 0L,
                allowTerminalOrPartialReprice = true
            ).isFailure
        )
        assertEquals(postsBeforeAbort, postedBodies.size)
    }

    @Test
    fun `M-4 getWorkingOrders adds 60s clock-skew buffer to newest toEnteredTime window`() = runTest {
        val nowMs = 1_730_000_000_000L
        val toTimes = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (request.method == "GET" && path.startsWith("/accounts/hash-123/orders")) {
                    val toParam = path.substringAfter("toEnteredTime=", "")
                        .substringBefore("&")
                    if (toParam.isNotBlank()) {
                        toTimes += URLDecoder.decode(toParam, StandardCharsets.UTF_8.name())
                    }
                    return MockResponse().setResponseCode(200).setBody("[]")
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val h = createHarness(isLive = true, nowProvider = { nowMs })
        h.repo.getWorkingOrders(forceRefresh = true).getOrThrow()

        val expectedBufferedEnd = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(nowMs).plusSeconds(60L))
        assertEquals(3, toTimes.size)
        assertEquals(expectedBufferedEnd, toTimes.last())
        assertFalse(toTimes.last().isEmpty())
    }

    @Test
    fun `H-1 live escalated re-price accepts COLLECTIVE_INVESTMENT ETF position and ignores OPTION leg`() = runTest {
        val postedBodies = mutableListOf<String>()
        var heldEtfShares = 5.0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "DELETE" && path == "/accounts/hash-123/orders/8501" ->
                        MockResponse().setResponseCode(200)
                    request.method == "GET" && path == "/accounts/hash-123/orders/8501" ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"orderId":8501,"status":"REJECTED","quantity":10.0,"filledQuantity":4.0,"remainingQuantity":6.0}"""
                        )
                    request.method == "GET" && path.startsWith("/accounts/hash-123?") ->
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "securitiesAccount": {
                                "positions": [
                                  {"longQuantity": $heldEtfShares, "shortQuantity": 0.0, "instrument": {"symbol": "SPY", "assetType": "COLLECTIVE_INVESTMENT"}},
                                  {"longQuantity": 10.0, "shortQuantity": 0.0, "instrument": {"symbol": "SPY", "assetType": "OPTION"}}
                                ]
                              }
                            }
                            """.trimIndent()
                        )
                    request.method == "POST" && path == "/accounts/hash-123/orders" -> {
                        postedBodies += request.body.readUtf8()
                        MockResponse().setResponseCode(201).addHeader("Location", "/accounts/hash-123/orders/8502")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val h = createHarness(isLive = true)
        val spyExitTicket = sampleEquityTicket(ExecutionMode.LIVE).copy(
            legs = listOf(OrderLeg("SPY", AssetCategory.EQUITY, OrderSide.SELL, 10))
        )

        val outcome = h.repo.replaceOrder(
            accountHash = "hash-123",
            oldOrderId = "8501",
            replacementTicket = spyExitTicket,
            risksAcknowledged = true,
            cancelToReplaceDelayMs = 0L,
            allowTerminalOrPartialReprice = true
        ).getOrThrow()

        assertTrue(outcome is ReplaceOrderOutcome.Replaced)
        assertEquals(1, postedBodies.size)
        assertTrue(postedBodies.single().contains("\"quantity\":5"), "Must use 5 COLLECTIVE_INVESTMENT ETF shares and ignore OPTION leg")

        // When ETF position is flat (only OPTION leg exists), live equity escalated re-price fails closed
        heldEtfShares = 0.0
        val aborted = h.repo.replaceOrder(
            accountHash = "hash-123",
            oldOrderId = "8501",
            replacementTicket = spyExitTicket,
            risksAcknowledged = true,
            cancelToReplaceDelayMs = 0L,
            allowTerminalOrPartialReprice = true
        )
        assertTrue(aborted.isFailure)
        assertEquals(1, postedBodies.size)
        assertTrue(h.auditDao.entries.any { it.category == "REPLACE_ABORTED_NO_POSITION" && it.symbol == "SPY" })
    }

    @Test
    fun `HIGH-1 replaceOrder blocks cross-domain SHADOW id in LIVE mode and numeric id in SHADOW mode`() = runTest {
        val isLiveFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val auditEntries = mutableListOf<com.schwabquant.app.data.local.entity.AuditLogEntity>()
        val traderApi = io.mockk.mockk<com.schwabquant.app.data.remote.api.SchwabTraderApi>(relaxed = true)
        val shadowEngine = io.mockk.mockk<ShadowExecutionEngine>(relaxed = true)
        val shadowLedgerDao = io.mockk.mockk<com.schwabquant.app.data.local.dao.ShadowLedgerDao>(relaxed = true)
        val auditLogDao = io.mockk.mockk<com.schwabquant.app.data.local.dao.AuditLogDao> {
            io.mockk.coEvery { insert(capture(auditEntries)) } returns 1L
        }
        val prefs = io.mockk.mockk<com.schwabquant.app.data.local.prefs.AppPreferencesDataStore>(relaxed = true) {
            io.mockk.every { isLiveMode } returns isLiveFlow
        }
        val vault = io.mockk.mockk<com.schwabquant.app.core.security.CredentialVault>(relaxed = true) {
            io.mockk.coEvery { getPrimaryAccountHash() } returns "hash-live"
        }
        val repo = OrderRepositoryImpl(
            traderApi = traderApi,
            shadowExecutionEngine = shadowEngine,
            shadowLedgerDao = shadowLedgerDao,
            auditLogDao = auditLogDao,
            preferences = prefs,
            credentialVault = vault,
            payloadBuilder = com.schwabquant.app.core.quant.SchwabOrderPayloadBuilder()
        )

        val shadowInLive = repo.replaceOrder(
            accountHash = "hash-live",
            oldOrderId = "SHADOW-42",
            replacementTicket = sampleEquityTicket(ExecutionMode.LIVE),
            risksAcknowledged = true,
            cancelToReplaceDelayMs = 0L,
            allowTerminalOrPartialReprice = true
        )
        assertTrue(shadowInLive.isFailure)
        assertTrue(auditEntries.any { it.category == "ORDER_MODE_MISMATCH_BLOCKED" })

        isLiveFlow.value = false
        val liveIdInShadow = repo.replaceOrder(
            accountHash = "SHADOW_PAPER",
            oldOrderId = "900123",
            replacementTicket = sampleEquityTicket(ExecutionMode.SHADOW),
            risksAcknowledged = true,
            cancelToReplaceDelayMs = 0L,
            allowTerminalOrPartialReprice = true
        )
        assertTrue(liveIdInShadow.isFailure)
        assertEquals(2, auditEntries.count { it.category == "ORDER_MODE_MISMATCH_BLOCKED" })
        io.mockk.coVerify(exactly = 0) { traderApi.cancelOrder(any(), any()) }
        io.mockk.coVerify(exactly = 0) { traderApi.placeOrder(any(), any()) }
        io.mockk.coVerify(exactly = 0) { traderApi.getOrder(any(), any()) }
    }

    @Test
    fun `MEDIUM-4 OrderDraft toOrderTicket preserves SHADOW_PAPER hash so submitOrder fails closed in LIVE mode`() = runTest {
        val draft = com.schwabquant.app.domain.model.OrderDraft(
            strategyCategory = com.schwabquant.app.domain.model.OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = "SPY",
            orderType = com.schwabquant.app.domain.model.OrderType.LIMIT,
            session = com.schwabquant.app.domain.model.TradingSession.NORMAL,
            duration = com.schwabquant.app.domain.model.OrderDuration.DAY,
            limitPrice = java.math.BigDecimal("500.00"),
            legs = listOf(
                com.schwabquant.app.domain.model.OrderLeg(
                    symbol = "SPY",
                    category = com.schwabquant.app.domain.model.AssetCategory.EQUITY,
                    side = com.schwabquant.app.domain.model.OrderSide.BUY,
                    quantity = 1
                )
            )
        )
        val liveTicketWithShadowHash = draft.toOrderTicket("SHADOW_PAPER", ExecutionMode.LIVE)
        assertEquals("SHADOW_PAPER", liveTicketWithShadowHash.accountHash)

        val isLiveFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val traderApi = io.mockk.mockk<com.schwabquant.app.data.remote.api.SchwabTraderApi>(relaxed = true)
        val repo = OrderRepositoryImpl(
            traderApi = traderApi,
            shadowExecutionEngine = io.mockk.mockk(relaxed = true),
            shadowLedgerDao = io.mockk.mockk(relaxed = true),
            auditLogDao = io.mockk.mockk(relaxed = true),
            preferences = io.mockk.mockk(relaxed = true) {
                io.mockk.every { isLiveMode } returns isLiveFlow
            },
            credentialVault = io.mockk.mockk(relaxed = true) {
                io.mockk.coEvery { getPrimaryAccountHash() } returns "hash-live"
            },
            payloadBuilder = com.schwabquant.app.core.quant.SchwabOrderPayloadBuilder()
        )
        val res = repo.submitOrder(liveTicketWithShadowHash, risksAcknowledged = true)
        assertTrue(res.isFailure)
        io.mockk.coVerify(exactly = 0) { traderApi.placeOrder(any(), any()) }
    }

    private fun sampleEquityTicket(mode: ExecutionMode) = OrderTicket(
        accountHash = "hash-123",
        executionMode = mode,
        orderType = OrderType.LIMIT,
        session = TradingSession.SEAMLESS,
        duration = OrderDuration.DAY,
        limitPrice = BigDecimal("269.50"),
        legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 16))
    )

    private fun createHarness(
        isLive: Boolean,
        nowProvider: () -> Long = System::currentTimeMillis
    ): Harness {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SchwabTraderApi::class.java)

        val liveFlow = MutableStateFlow(isLive)
        val cashFlow = MutableStateFlow(10_000.0)
        val prefs = mockk<AppPreferencesDataStore>(relaxed = true)
        every { prefs.isLiveMode } returns liveFlow
        every { prefs.shadowCashBalance } returns cashFlow
        coEvery { prefs.setShadowCashBalance(any()) } answers { cashFlow.value = firstArg() }

        val vault = mockk<CredentialVault>(relaxed = true)
        every { vault.getPrimaryAccountHash() } returns "hash-123"

        val marketRepo = mockk<MarketDataRepository>(relaxed = true)
        coEvery { marketRepo.getQuotes(any(), any()) } returns Result.success(emptyMap())

        val shadowDao = FakeShadowDao()
        val auditDao = FakeAuditDao()
        val washDao = mockk<WashSaleDao>(relaxed = true)
        coEvery { washDao.insertAll(any()) } returns Unit

        val engine = ShadowExecutionEngine(
            shadowLedgerDao = shadowDao,
            washSaleDao = washDao,
            auditLogDao = auditDao,
            preferences = prefs,
            marketDataRepository = marketRepo,
            occSymbolFormatter = OccSymbolFormatter(),
            json = json
        )

        val repo = OrderRepositoryImpl(
            traderApi = api,
            shadowExecutionEngine = engine,
            shadowLedgerDao = shadowDao,
            auditLogDao = auditDao,
            preferences = prefs,
            credentialVault = vault,
            payloadBuilder = SchwabOrderPayloadBuilder(),
            nowProvider = nowProvider
        )
        return Harness(repo, auditDao, shadowDao, liveFlow)
    }

    private data class Harness(
        val repo: OrderRepositoryImpl,
        val auditDao: FakeAuditDao,
        val shadowDao: FakeShadowDao,
        val liveFlow: MutableStateFlow<Boolean>
    )

    private class FakeShadowDao : ShadowLedgerDao {
        private val orders = linkedMapOf<String, ShadowOrderEntity>()
        private val orderFlow = MutableStateFlow<List<ShadowOrderEntity>>(emptyList())
        private val posFlow = MutableStateFlow<List<ShadowPositionEntity>>(emptyList())
        override fun observePositions(): Flow<List<ShadowPositionEntity>> = posFlow
        override suspend fun getPositionsSnapshot(): List<ShadowPositionEntity> = emptyList()
        override suspend fun getPositionBySymbol(symbol: String): ShadowPositionEntity? = null
        override suspend fun upsertPosition(position: ShadowPositionEntity) {}
        override suspend fun upsertPositions(positions: List<ShadowPositionEntity>) {}
        override suspend fun deletePosition(symbol: String) {}
        override suspend fun clearPositions() {}
        override fun observeOrders(): Flow<List<ShadowOrderEntity>> = orderFlow
        override suspend fun getOrdersSnapshot(): List<ShadowOrderEntity> = orders.values.toList()
        override suspend fun getOrderById(orderId: String): ShadowOrderEntity? = orders[orderId]
        override suspend fun upsertOrder(order: ShadowOrderEntity) {
            orders[order.orderId] = order
            orderFlow.value = orders.values.toList()
        }
        override suspend fun cancelOrder(orderId: String) {
            orders[orderId]?.let { orders[orderId] = it.copy(status = "CANCELED") }
        }
    }

    private class FakeAuditDao : AuditLogDao {
        val entries = mutableListOf<AuditLogEntity>()
        var onInsert: ((AuditLogEntity) -> Unit)? = null
        private val flow = MutableStateFlow<List<AuditLogEntity>>(emptyList())
        override fun observeRecent(limit: Int): Flow<List<AuditLogEntity>> = flow
        override suspend fun insert(entity: AuditLogEntity): Long {
            entries += entity
            onInsert?.invoke(entity)
            return entries.size.toLong()
        }
    }
}
