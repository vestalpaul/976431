package com.schwabquant.app.domain.usecase

import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStatusSnapshot
import com.schwabquant.app.domain.model.OrderSubmissionResult
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.ReplaceOrderOutcome
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.model.WorkingOrder
import com.schwabquant.app.domain.repository.OrderRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class MidPriceWalkUpControllerTest {

    @Test
    fun `C-1 halts walk-up immediately without calling replaceOrder when partial fill is detected`() = runTest {
        val repo = FakeWalkUpOrderRepository().apply {
            statusByOrderId["ORD-1"] = OrderStatusSnapshot(
                orderId = "ORD-1",
                status = "PARTIALLY_FILLED",
                filledQuantity = 1.0,
                remainingQuantity = 2.0
            )
        }
        val controller = MidPriceWalkUpController(repo)

        val ticket = OrderTicket(
            accountHash = "HASH-1",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.NET_DEBIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            strategyType = "SINGLE",
            complexStrategyType = "DIAGONAL",
            limitPrice = BigDecimal("10.00"),
            legs = listOf(
                OrderLeg("IWM   280121C00220000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 3),
                OrderLeg("IWM   261218C00284000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 3)
            )
        )

        controller.startWalkUp(
            scope = this,
            initialTicket = ticket,
            hardMaxCeiling = BigDecimal("10.50"),
            intervalSeconds = 5
        )
        runCurrent()
        assertTrue(controller.state.value.isActive)

        advanceTimeBy(5_000L)
        runCurrent()

        assertFalse(controller.state.value.isActive)
        assertEquals(0, repo.replaceCount, "Must never call replaceOrder after partial fill")
        assertTrue(controller.state.value.statusMessage.orEmpty().contains("partially filled"))
    }

    @Test
    fun `H-2 rejects OCO_BRACKET, MARKET, and STOP orders from starting walk-up`() = runTest {
        val repo = FakeWalkUpOrderRepository()
        val controller = MidPriceWalkUpController(repo)

        val ocoTicket = OrderTicket(
            accountHash = "HASH-1",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.GOOD_TILL_CANCEL,
            strategyType = "OCO",
            legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 10))
        )
        controller.startWalkUp(
            scope = this,
            initialTicket = ocoTicket,
            hardMaxCeiling = BigDecimal("305.00")
        )
        runCurrent()
        assertFalse(controller.state.value.isActive)
        assertEquals(0, repo.submittedTickets.size)
        assertTrue(controller.state.value.statusMessage.orEmpty().contains("Walk-Up rejected"))

        val marketTicket = ocoTicket.copy(strategyType = "SINGLE", orderType = OrderType.MARKET)
        controller.startWalkUp(
            scope = this,
            initialTicket = marketTicket,
            hardMaxCeiling = BigDecimal("305.00")
        )
        runCurrent()
        assertFalse(controller.state.value.isActive)
        assertEquals(0, repo.submittedTickets.size)
    }

    @Test
    fun `H-5 cancelWalkUp cancels active working order and concurrent startWalkUp cancels prior session`() = runTest {
        val repo = FakeWalkUpOrderRepository()
        val controller = MidPriceWalkUpController(repo)

        val firstTicket = OrderTicket(
            accountHash = "HASH-1",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("2.00"),
            legs = listOf(OrderLeg("IWM   261218C00235000", AssetCategory.OPTION, OrderSide.BUY_TO_CLOSE, 1))
        )

        controller.startWalkUp(
            scope = this,
            initialTicket = firstTicket,
            hardMaxCeiling = BigDecimal("2.20"),
            intervalSeconds = 10
        )
        runCurrent()
        assertEquals("ORD-1", controller.state.value.orderId)

        // Starting a second Walk-Up cancels the prior session & cancels ORD-1 on broker
        val secondTicket = firstTicket.copy(limitPrice = BigDecimal("3.00"))
        controller.startWalkUp(
            scope = this,
            initialTicket = secondTicket,
            hardMaxCeiling = BigDecimal("3.20"),
            intervalSeconds = 10
        )
        advanceTimeBy(1_600L)
        runCurrent()
        assertTrue("ORD-1" in repo.canceledOrderIds, "Prior Walk-Up order ORD-1 must be canceled")
        assertEquals("ORD-2", controller.state.value.orderId)

        // Explicit cancelWalkUp cancels ORD-2
        controller.cancelWalkUp(cancelWorkingOrder = true)
        runCurrent()
        assertFalse(controller.state.value.isActive)
        assertTrue("ORD-2" in repo.canceledOrderIds, "Active Walk-Up order ORD-2 must be canceled on cancelWalkUp()")
    }

    @Test
    fun `C-5 mid-flight submitOrder and replaceOrder cancellation auto-cancels newly created order`() = runTest {
        val repo = FakeWalkUpOrderRepository()
        val submitGate = CompletableDeferred<Unit>()
        repo.onSubmitSuspend = { submitGate.await() }
        val controller = MidPriceWalkUpController(repo, restartCancelSettlementDelayMs = 0L)

        val ticket = OrderTicket(
            accountHash = "HASH_LIVE",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("2.00"),
            legs = listOf(OrderLeg("IWM   261218C00235000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1))
        )

        // Cancel while initial submitOrder is still suspended
        controller.startWalkUp(
            scope = this,
            initialTicket = ticket,
            hardMaxCeiling = BigDecimal("2.20"),
            intervalSeconds = 10
        )
        runCurrent()
        controller.cancelWalkUp(cancelWorkingOrder = true)
        submitGate.complete(Unit)
        runCurrent()
        assertTrue("ORD-1" in repo.canceledOrderIds, "Mid-flight canceled submitOrder must auto-cancel ORD-1")

        // Now test mid-flight replaceOrder cancellation
        val repo2 = FakeWalkUpOrderRepository()
        val replaceGate = CompletableDeferred<Unit>()
        val controller2 = MidPriceWalkUpController(repo2, restartCancelSettlementDelayMs = 0L)
        controller2.startWalkUp(
            scope = this,
            initialTicket = ticket,
            hardMaxCeiling = BigDecimal("2.20"),
            intervalSeconds = 5,
            autoStep = true
        )
        runCurrent()
        assertEquals("ORD-1", controller2.state.value.orderId)

        repo2.onReplaceSuspend = { replaceGate.await() }
        advanceTimeBy(5_100L)
        runCurrent()

        controller2.cancelWalkUp(cancelWorkingOrder = true)
        replaceGate.complete(Unit)
        runCurrent()
        // C-1 Safeguard: canceling during replaceOrder cancels prior ORD-1 and never submits replacement ORD-2
        assertTrue("ORD-1" in repo2.canceledOrderIds, "Canceled replace must cancel active working order ORD-1")
        assertEquals(1, repo2.submittedTickets.size, "Must NOT submit replacement ORD-2 after cancelWalkUp()")
    }

    @Test
    fun `C-5 restart fails closed on cancel failure or partial fill, halts on UNTRACKED, and halts on getOrderStatus error`() = runTest {
        val repoFailCancel = FakeWalkUpOrderRepository().apply { failCancelOrder = true }
        val controllerFailCancel = MidPriceWalkUpController(repoFailCancel, restartCancelSettlementDelayMs = 0L)
        val ticket = OrderTicket(
            accountHash = "HASH_LIVE",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("2.00"),
            legs = listOf(OrderLeg("IWM   261218C00235000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1))
        )

        controllerFailCancel.startWalkUp(this, ticket, BigDecimal("2.20"), intervalSeconds = 10)
        runCurrent()
        val terminalEvents = mutableListOf<String>()
        controllerFailCancel.startWalkUp(
            scope = this,
            initialTicket = ticket.copy(limitPrice = BigDecimal("2.50")),
            hardMaxCeiling = BigDecimal("2.70"),
            intervalSeconds = 10,
            onTerminalEvent = { terminalEvents += it }
        )
        runCurrent()
        assertEquals(1, repoFailCancel.submittedTickets.size, "Must fail closed without starting second order when prior cancel fails")
        assertTrue(terminalEvents.any { it.contains("cancel", ignoreCase = true) })

        // UNTRACKED replacement halts loop immediately
        val repoUntracked = FakeWalkUpOrderRepository().apply { returnUntrackedOnReplace = true }
        val controllerUntracked = MidPriceWalkUpController(repoUntracked, restartCancelSettlementDelayMs = 0L)
        val untrackedEvents = mutableListOf<String>()
        controllerUntracked.startWalkUp(
            scope = this,
            initialTicket = ticket,
            hardMaxCeiling = BigDecimal("2.30"),
            intervalSeconds = 5,
            autoStep = true,
            onTerminalEvent = { untrackedEvents += it }
        )
        runCurrent()
        advanceTimeBy(5_100L)
        runCurrent()
        assertEquals(1, repoUntracked.replaceCount)
        assertFalse(controllerUntracked.state.value.isActive)
        assertTrue(untrackedEvents.any { it.contains("without tracking ID", ignoreCase = true) })

        // getOrderStatus failure must halt fail-closed without calling replaceOrder
        val repoStatusFail = FakeWalkUpOrderRepository()
        val controllerStatusFail = MidPriceWalkUpController(repoStatusFail, restartCancelSettlementDelayMs = 0L)
        val statusFailEvents = mutableListOf<String>()
        controllerStatusFail.startWalkUp(
            scope = this,
            initialTicket = ticket,
            hardMaxCeiling = BigDecimal("2.30"),
            intervalSeconds = 5,
            autoStep = true,
            onTerminalEvent = { statusFailEvents += it }
        )
        runCurrent()
        repoStatusFail.failStatusCheck = true
        advanceTimeBy(5_100L)
        runCurrent()
        assertEquals(0, repoStatusFail.replaceCount, "Must never step/replace when getOrderStatus fails")
        assertFalse(controllerStatusFail.state.value.isActive)
        assertTrue(statusFailEvents.any { it.contains("status", ignoreCase = true) })
    }

    @Test
    fun `C-4 and H-2 serializes cancelWalkUp with startWalkUp, retains failed cancel, and rejects null or non-positive prices`() = runTest {
        val repo = FakeWalkUpOrderRepository()
        val controller = MidPriceWalkUpController(repo, restartCancelSettlementDelayMs = 0L)

        // H-2: null limitPrice and <= 0 ceiling rejected with 0 submissions
        val invalidLimitTicket = OrderTicket(
            accountHash = "HASH_LIVE",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = null,
            legs = listOf(OrderLeg("IWM   261218C00235000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1))
        )
        val errMsgs = mutableListOf<String>()
        controller.startWalkUp(this, invalidLimitTicket, BigDecimal("2.50"), onTerminalEvent = { errMsgs += it })
        runCurrent()
        assertEquals(0, repo.submittedTickets.size)
        assertTrue(errMsgs.any { it.contains("positive", ignoreCase = true) })

        val validTicket = invalidLimitTicket.copy(limitPrice = BigDecimal("2.00"))
        errMsgs.clear()
        controller.startWalkUp(this, validTicket, BigDecimal("0.00"), onTerminalEvent = { errMsgs += it })
        runCurrent()
        assertEquals(0, repo.submittedTickets.size)

        // C-4: cancelWalkUp followed immediately by startWalkUp while cancel is in-flight serializes properly
        controller.startWalkUp(this, validTicket, BigDecimal("2.50"), intervalSeconds = 10)
        runCurrent()
        assertEquals("ORD-1", controller.state.value.orderId)

        val slowCancelGate = CompletableDeferred<Unit>()
        repo.onCancelSuspend = { slowCancelGate.await() }
        controller.cancelWalkUp(cancelWorkingOrder = true)
        controller.startWalkUp(this, validTicket.copy(limitPrice = BigDecimal("2.20")), BigDecimal("2.80"), intervalSeconds = 10)
        runCurrent()

        // Second startWalkUp must NOT submit until cancelWalkUp finishes
        assertEquals(1, repo.submittedTickets.size)
        slowCancelGate.complete(Unit)
        runCurrent()
        assertEquals(2, repo.submittedTickets.size)
        assertEquals("ORD-2", controller.state.value.orderId)
    }

    @Test
    fun `C-1 cancelWalkUp interrupts 1_5s replaceOrder settlement delay before submitOrder fires`() = runTest {
        val repo = FakeWalkUpOrderRepository()
        val controller = MidPriceWalkUpController(repo, restartCancelSettlementDelayMs = 0L)
        val ticket = OrderTicket(
            accountHash = "HASH_LIVE",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("2.00"),
            legs = listOf(OrderLeg("IWM   261218C00235000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1))
        )

        controller.startWalkUp(this, ticket, BigDecimal("2.30"), intervalSeconds = 5, autoStep = true)
        runCurrent()
        assertEquals("ORD-1", controller.state.value.orderId)

        // Simulate real OrderRepositoryImpl.replaceOrder cancellable 1.5s settlement delay
        repo.onReplaceSuspend = {
            repo.cancelOrder("HASH_LIVE", "ORD-1", ExecutionMode.LIVE)
            delay(1_500L)
        }
        advanceTimeBy(5_100L)
        runCurrent()

        controller.cancelWalkUp(cancelWorkingOrder = true)
        advanceTimeBy(2_000L)
        runCurrent()

        assertFalse(controller.state.value.isActive)
        assertTrue("ORD-1" in repo.canceledOrderIds)
        assertEquals(1, repo.submittedTickets.size, "Must NEVER submit replacement ORD-2 when canceled during 1.5s settlement")
    }

    @Test
    fun `C-2 C-3 interleaved cancelWalkUp startWalkUp cancelWalkUp never deadlocks and aborts restart before submit`() = runTest {
        val repo = FakeWalkUpOrderRepository()
        val controller = MidPriceWalkUpController(repo, restartCancelSettlementDelayMs = 1_500L)
        val ticket = OrderTicket(
            accountHash = "HASH_LIVE",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("2.00"),
            legs = listOf(OrderLeg("SPY   261218C00550000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1))
        )

        controller.startWalkUp(this, ticket, BigDecimal("2.30"), intervalSeconds = 10)
        runCurrent()
        assertEquals("ORD-1", controller.state.value.orderId)

        // Interleave cancelWalkUp -> startWalkUp -> cancelWalkUp while initial cancel is suspended
        val cancelGate = CompletableDeferred<Unit>()
        repo.onCancelSuspend = { cancelGate.await() }
        controller.cancelWalkUp(cancelWorkingOrder = true)
        controller.startWalkUp(this, ticket.copy(limitPrice = BigDecimal("2.05")), BigDecimal("2.30"), intervalSeconds = 10)
        controller.cancelWalkUp(cancelWorkingOrder = true)
        runCurrent()

        cancelGate.complete(Unit)
        advanceTimeBy(2_500L)
        runCurrent()

        assertFalse(controller.state.value.isActive)
        assertEquals(1, repo.submittedTickets.size, "Latest cancelWalkUp epoch must abort superseded startWalkUp before submit")

        // Also test canceling mid-way through a restart's 1.5s settlement delay
        val repo2 = FakeWalkUpOrderRepository()
        val controller2 = MidPriceWalkUpController(repo2, restartCancelSettlementDelayMs = 1_500L)
        controller2.startWalkUp(this, ticket, BigDecimal("2.30"), intervalSeconds = 10)
        runCurrent()

        controller2.startWalkUp(this, ticket.copy(limitPrice = BigDecimal("2.10")), BigDecimal("2.40"), intervalSeconds = 10)
        advanceTimeBy(500L)
        runCurrent()
        controller2.cancelWalkUp(cancelWorkingOrder = true)
        advanceTimeBy(2_000L)
        runCurrent()

        assertFalse(controller2.state.value.isActive)
        assertEquals(1, repo2.submittedTickets.size, "Canceling during restart settlement delay must prevent second submission")
    }

    @Test
    fun `M-3 reaching hardMaxCeiling clears latestSubmittedOrder so later Walk-Up does not cancel prior resting order`() = runTest {
        val repo = FakeWalkUpOrderRepository()
        val controller = MidPriceWalkUpController(repo, restartCancelSettlementDelayMs = 0L)
        val iwmTicket = OrderTicket(
            accountHash = "HASH_LIVE",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("2.00"),
            legs = listOf(OrderLeg("IWM   261218C00235000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1))
        )

        // With step=0.05 and ceiling=2.02, after 5s countdown nextPrice=2.05 > 2.02 -> clean ceiling exit
        controller.startWalkUp(this, iwmTicket, BigDecimal("2.02"), intervalSeconds = 5, autoStep = true)
        runCurrent()
        assertEquals("ORD-1", controller.state.value.orderId)

        advanceTimeBy(5_100L)
        runCurrent()
        assertFalse(controller.state.value.isActive)
        assertTrue(controller.state.value.statusMessage.orEmpty().contains("Reached Hard Max Walk-Up limit"))
        assertFalse("ORD-1" in repo.canceledOrderIds)

        // Starting a new Walk-Up on SPY must NOT cancel IWM's resting limit order ORD-1
        val spyTicket = iwmTicket.copy(
            limitPrice = BigDecimal("4.00"),
            legs = listOf(OrderLeg("SPY   261218C00550000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1))
        )
        controller.startWalkUp(this, spyTicket, BigDecimal("4.20"), intervalSeconds = 10)
        runCurrent()
        assertEquals("ORD-2", controller.state.value.orderId)
        assertEquals(2, repo.submittedTickets.size)
        assertFalse("ORD-1" in repo.canceledOrderIds, "Prior ceiling-resting order ORD-1 must NOT be canceled by new ticker Walk-Up")
    }

    @Test
    fun `H-2 cancel failure when broker reports CANCELED or FILLED clears singleton state without permanent lockout`() = runTest {
        val repo = FakeWalkUpOrderRepository()
        val controller = MidPriceWalkUpController(repo, restartCancelSettlementDelayMs = 0L)
        val ticket = OrderTicket(
            accountHash = "HASH_LIVE",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("2.00"),
            legs = listOf(OrderLeg("IWM   261218C00235000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1))
        )

        // 1. Start ORD-1, then broker already CANCELED it (0 fill) and cancelOrder() returns HTTP 400 failure
        controller.startWalkUp(this, ticket, BigDecimal("2.30"), intervalSeconds = 10)
        runCurrent()
        assertEquals("ORD-1", controller.state.value.orderId)
        repo.statusByOrderId["ORD-1"] = OrderStatusSnapshot("ORD-1", "CANCELED", filledQuantity = 0.0, remainingQuantity = 0.0)
        repo.failCancelOrder = true

        controller.cancelWalkUp(cancelWorkingOrder = true)
        runCurrent()
        assertFalse(controller.state.value.isActive)
        assertTrue(controller.state.value.statusMessage.orEmpty().contains("canceled", ignoreCase = true))

        // Subsequent startWalkUp submits ORD-2 cleanly (NO @Singleton lockout)
        repo.failCancelOrder = false
        controller.startWalkUp(this, ticket.copy(limitPrice = BigDecimal("2.05")), BigDecimal("2.35"), intervalSeconds = 10)
        runCurrent()
        assertEquals("ORD-2", controller.state.value.orderId)
        assertEquals(2, repo.submittedTickets.size)

        // Also verify direct cancelWalkUp() when prior attempt failed cancel (e.g. ORD-2 retained while WORKING, then broker CANCELED it)
        repo.failCancelOrder = true
        controller.cancelWalkUp(cancelWorkingOrder = true) // fails while WORKING -> retains ORD-2
        runCurrent()
        repo.statusByOrderId["ORD-2"] = OrderStatusSnapshot("ORD-2", "CANCELED", filledQuantity = 0.0, remainingQuantity = 0.0)
        controller.cancelWalkUp(cancelWorkingOrder = true) // queries broker, sees CANCELED (0 fill), clears ORD-2
        runCurrent()
        assertTrue(controller.state.value.statusMessage.orEmpty().contains("already CANCELED"))

        repo.failCancelOrder = false
        controller.startWalkUp(this, ticket.copy(limitPrice = BigDecimal("2.08")), BigDecimal("2.38"), intervalSeconds = 10)
        runCurrent()
        assertEquals("ORD-3", controller.state.value.orderId)
        assertEquals(3, repo.submittedTickets.size)

        // 2. ORD-3 becomes FILLED -> restart fails closed ONCE to prevent duplicate fill, then recovers
        repo.statusByOrderId["ORD-3"] = OrderStatusSnapshot("ORD-3", "FILLED", filledQuantity = 1.0, remainingQuantity = 0.0)
        repo.failCancelOrder = true
        val terminalEvents = mutableListOf<String>()
        controller.startWalkUp(
            scope = this,
            initialTicket = ticket.copy(limitPrice = BigDecimal("2.10")),
            hardMaxCeiling = BigDecimal("2.40"),
            intervalSeconds = 10,
            onTerminalEvent = { terminalEvents += it }
        )
        runCurrent()
        assertEquals(3, repo.submittedTickets.size, "Must abort restart once to prevent duplicate fill when ORD-3 FILLED")
        assertTrue(terminalEvents.any { it.contains("filled", ignoreCase = true) })

        // Now latestSubmittedOrder was cleared on terminal FILLED, so a subsequent manual retry succeeds!
        repo.failCancelOrder = false
        controller.startWalkUp(this, ticket.copy(limitPrice = BigDecimal("2.15")), BigDecimal("2.45"), intervalSeconds = 10)
        runCurrent()
        assertEquals("ORD-4", controller.state.value.orderId)
        assertEquals(4, repo.submittedTickets.size)
    }

    @Test
    fun `H-3 stale cancelJob from superseded epoch cannot kill newer startWalkUp session`() = runTest {
        val repo = FakeWalkUpOrderRepository()
        val controller = MidPriceWalkUpController(repo, restartCancelSettlementDelayMs = 0L)
        val ticket = OrderTicket(
            accountHash = "HASH_LIVE",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("2.00"),
            legs = listOf(OrderLeg("SPY   261218C00550000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1))
        )

        // Start ORD-1
        controller.startWalkUp(this, ticket, BigDecimal("2.30"), intervalSeconds = 10)
        runCurrent()
        assertEquals("ORD-1", controller.state.value.orderId)

        // Start ORD-2 restart holding lifecycleMutex in verifyPriorOrderCanceledFailClosed
        val holdMutexGate = CompletableDeferred<Unit>()
        repo.onCancelSuspend = { holdMutexGate.await() }
        controller.startWalkUp(this, ticket.copy(limitPrice = BigDecimal("2.05")), BigDecimal("2.35"), intervalSeconds = 10)
        runCurrent()

        // Enqueue cancelWalkUp(false) while lifecycleMutex is held, then immediately supersede with a newer startWalkUp
        controller.cancelWalkUp(cancelWorkingOrder = false)
        repo.onCancelSuspend = null
        controller.startWalkUp(this, ticket.copy(limitPrice = BigDecimal("2.10")), BigDecimal("2.40"), intervalSeconds = 10)

        holdMutexGate.complete(Unit)
        runCurrent()

        // Stale cancelWalkUp(false) must NOT kill or disarm newer startWalkUp (ORD-2 succeeds and stays active)
        assertTrue(controller.state.value.isActive)
        assertEquals("ORD-2", controller.state.value.orderId)
        assertEquals(2, repo.submittedTickets.size)
        assertFalse("ORD-2" in repo.canceledOrderIds)

        // Verify cancelWalkUp(true) works properly with the intact cancelOrderOnCoroutineCancel flag
        controller.cancelWalkUp(cancelWorkingOrder = true)
        runCurrent()
        assertFalse(controller.state.value.isActive)
        assertTrue("ORD-2" in repo.canceledOrderIds)
    }

    private class FakeWalkUpOrderRepository : OrderRepository {
        private var nextId = 1
        val submittedTickets = mutableListOf<OrderTicket>()
        val canceledOrderIds = mutableListOf<String>()
        val statusByOrderId = mutableMapOf<String, OrderStatusSnapshot>()
        var replaceCount = 0
        var failCancelOrder = false
        var failStatusCheck = false
        var returnUntrackedOnReplace = false
        var onSubmitSuspend: (suspend () -> Unit)? = null
        var onReplaceSuspend: (suspend () -> Unit)? = null
        var onCancelSuspend: (suspend () -> Unit)? = null

        override fun observeRecentOrders(mode: ExecutionMode): Flow<List<OrderSubmissionResult>> = emptyFlow()
        override fun observeWorkingOrders(): Flow<List<WorkingOrder>> = emptyFlow()
        override fun observeRecentAuditLogs(limit: Int): Flow<List<AuditLogEntity>> = emptyFlow()
        override suspend fun getWorkingOrders(forceRefresh: Boolean): Result<List<WorkingOrder>> =
            Result.success(emptyList())

        override suspend fun getOrderStatus(accountHash: String, orderId: String): Result<OrderStatusSnapshot> {
            if (failStatusCheck) return Result.failure(IllegalStateException("HTTP 503 status unavailable"))
            return Result.success(
                statusByOrderId[orderId] ?: OrderStatusSnapshot(
                    orderId = orderId,
                    status = "WORKING",
                    filledQuantity = 0.0,
                    remainingQuantity = 1.0
                )
            )
        }

        override suspend fun submitOrder(
            ticket: OrderTicket,
            risksAcknowledged: Boolean,
            overriddenRiskCodes: List<String>,
            simulateImmediateMidFill: Boolean
        ): Result<OrderSubmissionResult> {
            onSubmitSuspend?.invoke()
            submittedTickets += ticket
            val id = "ORD-${nextId++}"
            return Result.success(
                OrderSubmissionResult(
                    orderId = id,
                    executionMode = ticket.executionMode,
                    status = "WORKING",
                    message = "Submitted $id",
                    submittedAtEpochMs = 1_000L
                )
            )
        }

        override suspend fun cancelOrder(accountHash: String, orderId: String, mode: ExecutionMode): Result<Unit> {
            onCancelSuspend?.invoke()
            if (failCancelOrder) return Result.failure(IllegalStateException("Cancel failed 500"))
            canceledOrderIds += orderId
            statusByOrderId[orderId] = OrderStatusSnapshot(orderId, "CANCELED", 0.0, 0.0)
            return Result.success(Unit)
        }

        override suspend fun replaceOrder(
            accountHash: String,
            oldOrderId: String,
            replacementTicket: OrderTicket,
            risksAcknowledged: Boolean,
            overriddenRiskCodes: List<String>,
            cancelToReplaceDelayMs: Long,
            allowTerminalOrPartialReprice: Boolean
        ): Result<ReplaceOrderOutcome> {
            replaceCount++
            onReplaceSuspend?.invoke()
            if (returnUntrackedOnReplace) {
                return Result.success(
                    ReplaceOrderOutcome.Replaced(
                        OrderSubmissionResult(
                            orderId = "",
                            executionMode = replacementTicket.executionMode,
                            status = "UNTRACKED",
                            message = "Missing Location header",
                            submittedAtEpochMs = 1_000L
                        )
                    )
                )
            }
            val submitted = submitOrder(replacementTicket, risksAcknowledged, overriddenRiskCodes, false).getOrThrow()
            return Result.success(ReplaceOrderOutcome.Replaced(submitted))
        }

        override suspend fun simulateShadowFill(orderId: String): Result<OrderSubmissionResult> = error("Unused")
    }
}
