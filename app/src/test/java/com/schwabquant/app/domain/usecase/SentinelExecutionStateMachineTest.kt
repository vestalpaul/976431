package com.schwabquant.app.domain.usecase

import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.data.remote.streamer.AccountActivityEvent
import com.schwabquant.app.data.remote.streamer.AccountActivityFields
import com.schwabquant.app.data.remote.streamer.LiveEquityQuote
import com.schwabquant.app.domain.model.AccountSummary
import com.schwabquant.app.domain.model.AggregatedGreeks
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStatusSnapshot
import com.schwabquant.app.domain.model.OrderSubmissionResult
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.PortfolioSnapshot
import com.schwabquant.app.domain.model.Position
import com.schwabquant.app.domain.model.ReplaceOrderOutcome
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleStatus
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.model.WorkingOrder
import com.schwabquant.app.domain.repository.OrderRepository
import com.schwabquant.app.domain.repository.PortfolioRepository
import com.schwabquant.app.domain.repository.SentinelRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SentinelExecutionStateMachineTest {

    @Test
    fun `executes full 6-step sequence canceling conflicts, clamping qty, and submitting SEAMLESS LIMIT DAY exit`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val orderRepo = FakeOrderRepository().apply {
            workingOrders = listOf(
                WorkingOrder(
                    orderId = "CONF-101",
                    executionMode = ExecutionMode.LIVE,
                    symbol = "GOOG",
                    underlyingSymbol = "GOOG",
                    strategyType = "SINGLE",
                    orderType = "LIMIT",
                    session = "NORMAL",
                    duration = "GOOD_TILL_CANCEL",
                    status = "WORKING",
                    limitPrice = "300.00",
                    stopPrice = null,
                    fillPrice = null,
                    quantity = 16,
                    legsSummary = "SELL 16x GOOG",
                    submittedAtEpochMs = 1_000L,
                    filledAtEpochMs = null
                )
            )
            submitStatus = "WORKING"
            polledOrderStatus = "WORKING"
        }
        val portfolioRepo = FakePortfolioRepository(openEquityQty = 12.0) // Held 12 < rule qty 16
        val accountEvents = MutableSharedFlow<AccountActivityEvent>(extraBufferCapacity = 8)

        val rule = SentinelRule(
            id = 10L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 16,
            slippageCollarPct = 0.50,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(rule)

        val stateMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = portfolioRepo,
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = accountEvents,
            settlementDelayMs = 10L,
            fillMonitorTimeoutMs = 500L,
            fillPollIntervalMs = 50L
        )

        // Emit ACCT_ACTIVITY OrderFill shortly after submission (alongside REST confirmation)
        val emitter = launch {
            delay(40L)
            orderRepo.polledOrderStatus = "FILLED"
            accountEvents.emit(
                AccountActivityEvent(
                    subscriptionKey = "Account Activity",
                    accountNumber = "1234",
                    messageType = AccountActivityFields.MSG_ORDER_FILL,
                    messageDataJson = """{"orderId":"ORD-9001"}""",
                    orderId = "ORD-9001"
                )
            )
        }

        val outcome = stateMachine.executeTriggeredRule(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 269.00, askPrice = 269.20, securityStatus = "Normal"),
            session = MarketSessionWindow.REGULAR // Safeguard #3 verifies SEAMLESS even in REGULAR!
        )
        emitter.join()

        assertTrue(outcome is SentinelExecutionOutcome.Filled)
        // Step 2: Conflicting working order was canceled
        assertEquals(listOf("CONF-101"), orderRepo.canceledOrderIds)
        // Step 5: Submitted with SEAMLESS + LIMIT + DAY, clamped qty 12, collar 0.50% -> 269.00 * 0.995 = 267.66
        val ticket = orderRepo.submittedTickets.single()
        assertEquals(TradingSession.SEAMLESS, ticket.session)
        assertEquals(OrderType.LIMIT, ticket.orderType)
        assertEquals(OrderDuration.DAY, ticket.duration)
        assertEquals(OrderSide.SELL, ticket.legs.single().side)
        assertEquals(12, ticket.legs.single().quantity)
        assertEquals(BigDecimal("267.66"), ticket.limitPrice)
        assertEquals(SentinelRuleStatus.TRIGGERED_FILLED, sentinelRepo.getRuleById(10L)?.status)
    }

    @Test
    fun `safeguard 1 aborts long exit when position is flat or short preventing accidental shorting`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val orderRepo = FakeOrderRepository()
        val rule = SentinelRule(
            id = 20L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 16
        )
        sentinelRepo.upsertRule(rule)

        // Case A: Flat position (openQty == 0.0)
        val flatMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 0.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { false },
            accountHashProvider = { null },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L,
            fillMonitorTimeoutMs = 100L,
            fillPollIntervalMs = 20L
        )
        val flatOutcome = flatMachine.executeTriggeredRule(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20),
            session = MarketSessionWindow.AFTER_HOURS
        )
        assertTrue(flatOutcome is SentinelExecutionOutcome.AbortedNoPosition)
        assertTrue(orderRepo.submittedTickets.isEmpty())
        assertEquals(SentinelRuleStatus.ABORTED_NO_POSITION, sentinelRepo.getRuleById(20L)?.status)

        // Case B: Short position (-10.0) on a LONG_STOP_LOSS -> MUST still abort!
        sentinelRepo.updateRuleStatus(20L, SentinelRuleStatus.ARMED)
        val oppositeSignMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = -10.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { false },
            accountHashProvider = { null },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L,
            fillMonitorTimeoutMs = 100L,
            fillPollIntervalMs = 20L
        )
        val oppositeOutcome = oppositeSignMachine.executeTriggeredRule(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20),
            session = MarketSessionWindow.AFTER_HOURS
        )
        assertTrue(oppositeOutcome is SentinelExecutionOutcome.AbortedNoPosition)
        assertTrue(orderRepo.submittedTickets.isEmpty())
    }

    @Test
    fun `safeguard 1 aborts short cover when position is flat or long preventing accidental long entry`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val orderRepo = FakeOrderRepository()
        val shortRule = SentinelRule(
            id = 21L,
            symbol = "NVDA",
            ruleType = SentinelRuleType.SHORT_STOP_LOSS,
            triggerPrice = BigDecimal("140.00"),
            quantity = 20,
            isShortPosition = true
        )
        sentinelRepo.upsertRule(shortRule)

        val longHeldMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(symbol = "NVDA", openEquityQty = 20.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { false },
            accountHashProvider = { null },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L,
            fillMonitorTimeoutMs = 100L,
            fillPollIntervalMs = 20L
        )

        val outcome = longHeldMachine.executeTriggeredRule(
            rule = shortRule,
            quote = LiveEquityQuote("NVDA", bidPrice = 140.10, askPrice = 140.30),
            session = MarketSessionWindow.PRE_MARKET
        )
        assertTrue(outcome is SentinelExecutionOutcome.AbortedNoPosition)
        assertTrue(orderRepo.submittedTickets.isEmpty())
        assertEquals(SentinelRuleStatus.ABORTED_NO_POSITION, sentinelRepo.getRuleById(21L)?.status)
    }

    @Test
    fun `escalates to UNFILLED_ESCALATED with reprice draft when order remains unfilled after timeout`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val orderRepo = FakeOrderRepository().apply {
            submitStatus = "WORKING"
            polledOrderStatus = "WORKING"
        }
        val rule = SentinelRule(
            id = 30L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(rule)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 10.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L,
            fillMonitorTimeoutMs = 80L,
            fillPollIntervalMs = 20L
        )

        val outcome = machine.executeTriggeredRule(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 269.00, askPrice = 269.20),
            session = MarketSessionWindow.AFTER_HOURS
        )

        assertTrue(outcome is SentinelExecutionOutcome.UnfilledEscalated)
        val escalated = outcome as SentinelExecutionOutcome.UnfilledEscalated
        assertEquals(TradingSession.SEAMLESS, escalated.repriceDraft.session)
        assertEquals(OrderType.LIMIT, escalated.repriceDraft.orderType)
        // C-6: Escalated reprice draft carries replacePriorOrderId and persists lastSubmittedOrderId
        assertEquals("ORD-9001", escalated.repriceDraft.replacePriorOrderId)
        assertEquals("ORD-9001", sentinelRepo.getRuleById(30L)?.lastSubmittedOrderId)
        assertEquals(SentinelRuleStatus.UNFILLED_ESCALATED, sentinelRepo.getRuleById(30L)?.status)
    }

    @Test
    fun `safeguard 2 never auto-submits buy orders for DIP_BUY_ANTI_KNIFE and stages alert only`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val orderRepo = FakeOrderRepository()
        val dipRule = SentinelRule(
            id = 40L,
            symbol = "MSFT",
            ruleType = SentinelRuleType.DIP_BUY_ANTI_KNIFE,
            triggerPrice = BigDecimal("400.00"),
            quantity = 5,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(dipRule)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(symbol = "MSFT", openEquityQty = 0.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L,
            fillMonitorTimeoutMs = 100L,
            fillPollIntervalMs = 20L
        )

        val outcome = machine.executeTriggeredRule(
            rule = dipRule,
            quote = LiveEquityQuote("MSFT", bidPrice = 397.20, askPrice = 397.40),
            session = MarketSessionWindow.REGULAR
        )

        assertTrue(outcome is SentinelExecutionOutcome.StagedAlertOnly)
        val staged = outcome as SentinelExecutionOutcome.StagedAlertOnly
        assertTrue(staged.payload.isDipBuyAlert)
        assertEquals(OrderSide.BUY, staged.payload.stagedOrderDraft.legs.single().side)
        assertEquals(TradingSession.SEAMLESS, staged.payload.stagedOrderDraft.session)
        assertTrue(orderRepo.submittedTickets.isEmpty())
        assertEquals(SentinelRuleStatus.ALERTED, sentinelRepo.getRuleById(40L)?.status)
    }

    @Test
    fun `rejects automated exit execution during OVERNIGHT_EXTO with zero orders submitted`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val orderRepo = FakeOrderRepository()
        val rule = SentinelRule(
            id = 50L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(rule)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 10.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow()
        )

        val outcome = machine.executeTriggeredRule(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 265.00, askPrice = 265.50),
            session = MarketSessionWindow.OVERNIGHT_EXTO
        )

        assertTrue(outcome is SentinelExecutionOutcome.RejectedOvernight)
        assertTrue(orderRepo.submittedTickets.isEmpty())
    }

    @Test
    fun `C-2 per-symbol mutex serializes two concurrent rules on GOOG so only one exit order is submitted`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val portfolioRepo = FakePortfolioRepository(symbol = "GOOG", openEquityQty = 16.0)
        val orderRepo = FakeOrderRepository().apply {
            submitStatus = "FILLED"
            onOrderFilled = { portfolioRepo.setOpenQuantity(0.0) }
        }

        val rule1 = SentinelRule(
            id = 61L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 16,
            executionMode = ExecutionMode.LIVE
        )
        val rule2 = SentinelRule(
            id = 62L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("269.50"),
            quantity = 16,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(rule1)
        sentinelRepo.upsertRule(rule2)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = portfolioRepo,
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 10L
        )

        val quote = LiveEquityQuote("GOOG", bidPrice = 269.00, askPrice = 269.20, securityStatus = "Normal")
        val outcomes = mutableListOf<SentinelExecutionOutcome>()
        val j1 = launch { outcomes += machine.executeTriggeredRule(rule1, quote, MarketSessionWindow.REGULAR) }
        val j2 = launch { outcomes += machine.executeTriggeredRule(rule2, quote, MarketSessionWindow.REGULAR) }
        j1.join()
        j2.join()

        assertEquals(1, orderRepo.submittedTickets.size, "Per-symbol mutex must allow strictly ONE exit submission")
        assertEquals(1, outcomes.count { it is SentinelExecutionOutcome.Filled })
        assertEquals(1, outcomes.count { it is SentinelExecutionOutcome.AbortedNoPosition })
    }

    @Test
    fun `C-3 fails closed when live portfolio refresh fails instead of falling back to stale cached position`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val orderRepo = FakeOrderRepository()
        // Cached snapshot has +16 shares, but live refresh fails!
        val portfolioRepo = FakePortfolioRepository(
            symbol = "GOOG",
            openEquityQty = 16.0,
            failLiveRefresh = true
        )
        val rule = SentinelRule(
            id = 70L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 16,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(rule)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = portfolioRepo,
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow()
        )

        val outcome = machine.executeTriggeredRule(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20),
            session = MarketSessionWindow.REGULAR
        )

        assertTrue(outcome is SentinelExecutionOutcome.UnfilledEscalated)
        assertTrue(orderRepo.submittedTickets.isEmpty(), "Must NEVER submit order when live refresh fails")
        assertTrue("SENTINEL_ABORTED_REFRESH_FAILED" in sentinelRepo.auditEvents)
        assertEquals(SentinelRuleStatus.UNFILLED_ESCALATED, sentinelRepo.getRuleById(70L)?.status)
    }

    @Test
    fun `C-4 preserves open option orders on same underlying and fails closed on working-order or cancel errors`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val optionWorkingOrder = WorkingOrder(
            orderId = "OPT-201",
            executionMode = ExecutionMode.LIVE,
            symbol = "GOOG  261218C00280000",
            underlyingSymbol = "GOOG",
            strategyType = "DIAGONAL",
            orderType = "NET_DEBIT",
            session = "NORMAL",
            duration = "DAY",
            status = "WORKING",
            limitPrice = "25.00",
            stopPrice = null,
            fillPrice = null,
            quantity = 1,
            legsSummary = "BUY_TO_OPEN 1x GOOG  261218C00280000",
            submittedAtEpochMs = 1_000L,
            filledAtEpochMs = null,
            assetType = "OPTION"
        )
        val equityWorkingOrder = WorkingOrder(
            orderId = "EQ-202",
            executionMode = ExecutionMode.LIVE,
            symbol = "GOOG",
            underlyingSymbol = "GOOG",
            strategyType = "SINGLE",
            orderType = "LIMIT",
            session = "NORMAL",
            duration = "GOOD_TILL_CANCEL",
            status = "WORKING",
            limitPrice = "310.00",
            stopPrice = null,
            fillPrice = null,
            quantity = 16,
            legsSummary = "SELL 16x GOOG",
            submittedAtEpochMs = 1_000L,
            filledAtEpochMs = null,
            assetType = "EQUITY"
        )
        val orderRepo = FakeOrderRepository().apply {
            workingOrders = listOf(optionWorkingOrder, equityWorkingOrder)
            submitStatus = "FILLED"
        }
        val rule = SentinelRule(
            id = 80L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 16,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(rule)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 16.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L
        )

        val outcome = machine.executeTriggeredRule(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 269.00, askPrice = 269.20),
            session = MarketSessionWindow.REGULAR
        )
        assertTrue(outcome is SentinelExecutionOutcome.Filled)
        assertEquals(listOf("EQ-202"), orderRepo.canceledOrderIds, "Must cancel equity conflict but NEVER cancel OPTION orders")

        // Now verify fail-closed when cancelOrder fails
        val failCancelRepo = FakeOrderRepository().apply {
            workingOrders = listOf(equityWorkingOrder)
            failCancelOrder = true
        }
        val ruleFailCancel = rule.copy(id = 81L)
        sentinelRepo.upsertRule(ruleFailCancel)
        val machineFailCancel = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = failCancelRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 16.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L
        )
        val failCancelOutcome = machineFailCancel.executeTriggeredRule(
            rule = ruleFailCancel,
            quote = LiveEquityQuote("GOOG", bidPrice = 269.00, askPrice = 269.20),
            session = MarketSessionWindow.REGULAR
        )
        assertTrue(failCancelOutcome is SentinelExecutionOutcome.UnfilledEscalated)
        assertTrue(failCancelRepo.submittedTickets.isEmpty(), "Must abort exit when conflicting order cancel fails")
    }

    @Test
    fun `C-5 blocks Shadow-created rule from executing when app is in Live Mode`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val orderRepo = FakeOrderRepository()
        val shadowRule = SentinelRule(
            id = 90L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 16,
            executionMode = ExecutionMode.SHADOW
        )
        sentinelRepo.upsertRule(shadowRule)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 16.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true }, // App switched to LIVE Mode
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow()
        )

        val outcome = machine.executeTriggeredRule(
            rule = shadowRule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20),
            session = MarketSessionWindow.REGULAR
        )

        assertTrue(outcome is SentinelExecutionOutcome.Failed)
        assertTrue(orderRepo.submittedTickets.isEmpty())
        assertTrue("SENTINEL_MODE_MISMATCH_BLOCKED" in sentinelRepo.auditEvents)
    }

    @Test
    fun `H-6 aborts on fractional share residual under 1 share and aborts when rule was deleted`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val orderRepo = FakeOrderRepository()
        val fractionalRule = SentinelRule(
            id = 95L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 5,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(fractionalRule)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 0.40),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow()
        )

        val fractionalOutcome = machine.executeTriggeredRule(
            rule = fractionalRule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20),
            session = MarketSessionWindow.REGULAR
        )
        assertTrue(fractionalOutcome is SentinelExecutionOutcome.AbortedNoPosition)
        assertTrue(orderRepo.submittedTickets.isEmpty(), "Must NEVER round 0.40 fractional shares up to 1")

        // Deleted rule (not in sentinelRepo) must abort immediately under mutex
        val deletedRule = fractionalRule.copy(id = 999L)
        val deletedMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 16.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow()
        )
        val deletedOutcome = deletedMachine.executeTriggeredRule(
            rule = deletedRule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20),
            session = MarketSessionWindow.REGULAR
        )
        assertTrue(deletedOutcome is SentinelExecutionOutcome.AlreadyInProgress)
        assertTrue(orderRepo.submittedTickets.isEmpty())
    }

    @Test
    fun `C-1 aborts LIVE execution cleanly when isLiveMode flips to SHADOW during pre-submission settlement`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        var currentLiveMode = true
        val orderRepo = FakeOrderRepository().apply {
            onGetWorkingOrders = { currentLiveMode = false }
        }
        val rule = SentinelRule(
            id = 101L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(rule)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 10.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { currentLiveMode },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L
        )

        val outcome = machine.executeTriggeredRule(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20, securityStatus = "Normal"),
            session = MarketSessionWindow.REGULAR
        )

        assertTrue(outcome is SentinelExecutionOutcome.Failed)
        assertTrue(orderRepo.submittedTickets.isEmpty(), "Must abort before submitOrder after post-settlement mode flip")
        assertTrue("SENTINEL_MODE_MISMATCH_BLOCKED" in sentinelRepo.auditEvents)
    }

    @Test
    fun `C-3 cancels live OCO equity bracket conflict while leaving option OCO untouched`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val ocoEquityBracket = WorkingOrder(
            orderId = "OCO-EQ-1",
            executionMode = ExecutionMode.LIVE,
            symbol = "GOOG",
            underlyingSymbol = "GOOG",
            strategyType = "OCO",
            orderType = "LIMIT",
            session = "NORMAL",
            duration = "GOOD_TILL_CANCEL",
            status = "AWAITING_PARENT_ORDER",
            limitPrice = "290.00",
            stopPrice = "265.00",
            fillPrice = null,
            quantity = 10,
            legsSummary = "OCO GOOG",
            submittedAtEpochMs = 1_000L,
            filledAtEpochMs = null,
            assetType = "OCO",
            legSymbols = setOf("GOOG"),
            hasOptionLegs = false,
            hasEquityLegs = true
        )
        val ocoOptionBracket = ocoEquityBracket.copy(
            orderId = "OCO-OPT-2",
            symbol = "GOOG  261218C00280000",
            legSymbols = setOf("GOOG  261218C00280000"),
            hasOptionLegs = true,
            hasEquityLegs = false
        )
        val orderRepo = FakeOrderRepository().apply {
            workingOrders = listOf(ocoEquityBracket, ocoOptionBracket)
            submitStatus = "FILLED"
            polledOrderStatus = "FILLED"
        }
        val rule = SentinelRule(
            id = 102L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(rule)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 10.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L
        )

        val outcome = machine.executeTriggeredRule(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20, securityStatus = "Normal"),
            session = MarketSessionWindow.REGULAR
        )

        assertTrue(outcome is SentinelExecutionOutcome.Filled)
        assertEquals(listOf("OCO-EQ-1"), orderRepo.canceledOrderIds)
    }

    @Test
    fun `H-1 partial LeavesQuantity and lagging REST do not mark TRIGGERED_FILLED while null remaining on FILLED confirms fill`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val accountEvents = MutableSharedFlow<AccountActivityEvent>(extraBufferCapacity = 8)
        val partialRepo = FakeOrderRepository().apply {
            submitStatus = "WORKING"
            polledOrderStatus = "WORKING"
            polledRemainingQtyOverride = true to 4.0
        }
        val partialRule = SentinelRule(
            id = 103L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(partialRule)

        val partialMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = partialRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 10.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = accountEvents,
            settlementDelayMs = 0L,
            fillMonitorTimeoutMs = 80L,
            fillPollIntervalMs = 20L
        )

        val emitter = launch {
            delay(20L)
            accountEvents.emit(
                AccountActivityEvent(
                    subscriptionKey = "Account Activity",
                    accountNumber = "1234",
                    messageType = AccountActivityFields.MSG_ORDER_FILL,
                    messageDataJson = """{"orderId":"ORD-9001","LeavesQuantity":4}""",
                    orderId = "ORD-9001"
                )
            )
        }
        val partialOutcome = partialMachine.executeTriggeredRule(
            rule = partialRule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20, securityStatus = "Normal"),
            session = MarketSessionWindow.REGULAR
        )
        emitter.join()

        assertTrue(partialOutcome is SentinelExecutionOutcome.UnfilledEscalated)
        assertEquals(SentinelRuleStatus.UNFILLED_ESCALATED, sentinelRepo.getRuleById(103L)?.status)

        // Now verify REST status = "FILLED" with remainingQuantity = null confirms fill cleanly
        val nullRemRepo = FakeOrderRepository().apply {
            submitStatus = "WORKING"
            polledOrderStatus = "FILLED"
            polledRemainingQtyOverride = true to null
        }
        val nullRemRule = partialRule.copy(id = 104L)
        sentinelRepo.upsertRule(nullRemRule)
        val nullRemMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = nullRemRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 10.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L,
            fillMonitorTimeoutMs = 80L,
            fillPollIntervalMs = 20L
        )
        val filledOutcome = nullRemMachine.executeTriggeredRule(
            rule = nullRemRule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20, securityStatus = "Normal"),
            session = MarketSessionWindow.REGULAR
        )
        assertTrue(filledOutcome is SentinelExecutionOutcome.Filled)
        assertEquals(SentinelRuleStatus.TRIGGERED_FILLED, sentinelRepo.getRuleById(104L)?.status)
    }

    @Test
    fun `M-3 sessionRevalidator aborts LIVE rule at 20_00 ET even with allowSessionOverride and uses fresher valid quote for collar`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val liveOrderRepo = FakeOrderRepository()
        val liveRule = SentinelRule(
            id = 105L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            slippageCollarPct = 0.50,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(liveRule)

        val closedMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = liveOrderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 10.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L,
            sessionRevalidator = { MarketSessionWindow.OVERNIGHT_EXTO }
        )

        // Even with allowSessionOverride = true, LIVE rule MUST abort when wall clock crosses 20:00 ET
        val overnightOutcome = closedMachine.executeTriggeredRule(
            rule = liveRule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20, securityStatus = "Normal"),
            session = MarketSessionWindow.AFTER_HOURS,
            allowSessionOverride = true
        )
        assertTrue(overnightOutcome is SentinelExecutionOutcome.StagedAlertOnly)
        assertEquals(SentinelRuleStatus.ALERTED, sentinelRepo.getRuleById(105L)?.status)
        assertTrue(liveOrderRepo.submittedTickets.isEmpty())

        // Fresh valid quote from latestQuoteProvider overrides trigger quote for Step 5 collar price
        val freshOrderRepo = FakeOrderRepository().apply { submitStatus = "FILLED"; polledOrderStatus = "FILLED" }
        val freshRule = liveRule.copy(id = 106L)
        sentinelRepo.upsertRule(freshRule)
        val freshMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = freshOrderRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 10.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L,
            sessionRevalidator = { MarketSessionWindow.AFTER_HOURS },
            latestQuoteProvider = { sym ->
                if (sym == "GOOG") {
                    LiveEquityQuote(
                        symbol = "GOOG",
                        bidPrice = 266.00,
                        askPrice = 266.20,
                        securityStatus = "Normal",
                        receivedAtEpochMs = 5_000L
                    )
                } else null
            }
        )

        val freshOutcome = freshMachine.executeTriggeredRule(
            rule = freshRule,
            quote = LiveEquityQuote("GOOG", bidPrice = 269.00, askPrice = 269.20, securityStatus = "Normal", receivedAtEpochMs = 1_000L),
            session = MarketSessionWindow.AFTER_HOURS
        )
        assertTrue(freshOutcome is SentinelExecutionOutcome.Filled)
        // Collar 0.50% below fresh bid 266.00 -> 264.67 (NOT stale 269.00 * 0.995 = 267.66)
        assertEquals(BigDecimal("264.67"), freshOrderRepo.submittedTickets.single().limitPrice)
    }

    @Test
    fun `C-2 C-3 H-3 aborts before Step 3 on mode flip, cancels AWAITING_STOP_CONDITION, and sanitizes placeholder IDs`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val workingStopCond = WorkingOrder(
            orderId = "STOP-COND-1",
            executionMode = ExecutionMode.LIVE,
            symbol = "GOOG",
            underlyingSymbol = "GOOG",
            strategyType = "SINGLE",
            orderType = "STOP",
            session = "NORMAL",
            duration = "GOOD_TILL_CANCEL",
            status = "AWAITING_STOP_CONDITION",
            limitPrice = null,
            stopPrice = "265.00",
            fillPrice = null,
            quantity = 10,
            legsSummary = "SELL 10x GOOG",
            submittedAtEpochMs = 1_000L,
            filledAtEpochMs = null,
            assetType = "EQUITY",
            legSymbols = setOf("GOOG"),
            hasOptionLegs = false,
            hasEquityLegs = true
        )

        // C-2: Flip mode during getWorkingOrders right before Step 3 conflict cancel -> aborts without canceling!
        var liveToggle = true
        val flipRepo = FakeOrderRepository().apply {
            workingOrders = listOf(workingStopCond)
            onGetWorkingOrders = { liveToggle = false }
        }
        val flipRule = SentinelRule(
            id = 201L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(flipRule)
        val flipMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = flipRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 10.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { liveToggle },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L
        )
        val flipOutcome = flipMachine.executeTriggeredRule(
            rule = flipRule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20),
            session = MarketSessionWindow.REGULAR
        )
        assertTrue(flipOutcome is SentinelExecutionOutcome.Failed)
        assertTrue(flipRepo.canceledOrderIds.isEmpty(), "Mode flip before Step 3 must abort BEFORE cancelOrder()")

        // C-3: AWAITING_STOP_CONDITION conflicting order IS canceled cleanly
        val stopCondRepo = FakeOrderRepository().apply {
            workingOrders = listOf(workingStopCond)
            submitStatus = "FILLED"
            polledOrderStatus = "FILLED"
        }
        val stopCondRule = flipRule.copy(id = 202L)
        sentinelRepo.upsertRule(stopCondRule)
        val stopCondMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = stopCondRepo,
            portfolioRepository = FakePortfolioRepository(openEquityQty = 10.0),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L
        )
        val stopCondOutcome = stopCondMachine.executeTriggeredRule(
            rule = stopCondRule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20),
            session = MarketSessionWindow.REGULAR
        )
        assertTrue(stopCondOutcome is SentinelExecutionOutcome.Filled)
        assertEquals(listOf("STOP-COND-1"), stopCondRepo.canceledOrderIds)

        // H-3: REFRESH_FAILED escalation draft has null replacePriorOrderId
        val failRefreshMachine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = FakeOrderRepository(),
            portfolioRepository = FakePortfolioRepository(openEquityQty = 10.0, failLiveRefresh = true),
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L
        )
        val escRule = flipRule.copy(id = 203L, lastSubmittedOrderId = "PRE_SUBMIT_ABORT")
        sentinelRepo.upsertRule(escRule)
        val escOutcome = failRefreshMachine.executeTriggeredRule(
            rule = escRule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20),
            session = MarketSessionWindow.REGULAR
        ) as SentinelExecutionOutcome.UnfilledEscalated
        assertEquals(null, escOutcome.repriceDraft.replacePriorOrderId)
    }

    @Test
    fun `H-2 always re-fetches live positions in Step 4 even with zero conflicts and aborts if flat before Step 5`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val portfolioRepo = FakePortfolioRepository(symbol = "GOOG", openEquityQty = 10.0)
        val orderRepo = FakeOrderRepository().apply {
            workingOrders = emptyList() // conflicting.isEmpty()!
            onGetWorkingOrders = {
                // Position closed concurrently between Step 1 and Step 2
                portfolioRepo.setOpenQuantity(0.0)
            }
        }
        val rule = SentinelRule(
            id = 210L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(rule)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = portfolioRepo,
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L
        )

        val outcome = machine.executeTriggeredRule(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20, securityStatus = "Normal"),
            session = MarketSessionWindow.REGULAR
        )

        assertTrue(outcome is SentinelExecutionOutcome.AbortedNoPosition)
        assertEquals(2, portfolioRepo.livePositionRefreshCalls, "Step 1 AND Step 4 must both refresh live positions even when conflicting.isEmpty()")
        assertTrue(orderRepo.submittedTickets.isEmpty(), "Must abort before Step 5 without submitting short sell")
    }

    @Test
    fun `H-6 SHORT_STOP_LOSS and short TAKE_PROFIT emit BUY_TO_COVER instruction`() = runTest {
        val sentinelRepo = FakeSentinelRepository()
        val orderRepo = FakeOrderRepository().apply {
            submitStatus = "FILLED"
            polledOrderStatus = "FILLED"
        }
        val portfolioRepo = FakePortfolioRepository(symbol = "NVDA", openEquityQty = -15.0)
        val shortStopRule = SentinelRule(
            id = 211L,
            symbol = "NVDA",
            ruleType = SentinelRuleType.SHORT_STOP_LOSS,
            triggerPrice = BigDecimal("140.00"),
            quantity = 15,
            isShortPosition = true,
            executionMode = ExecutionMode.LIVE
        )
        val shortTpRule = SentinelRule(
            id = 212L,
            symbol = "NVDA",
            ruleType = SentinelRuleType.TAKE_PROFIT,
            triggerPrice = BigDecimal("125.00"),
            quantity = 10,
            isShortPosition = true,
            executionMode = ExecutionMode.LIVE
        )
        sentinelRepo.upsertRule(shortStopRule)
        sentinelRepo.upsertRule(shortTpRule)

        val machine = SentinelExecutionStateMachine(
            sentinelRepository = sentinelRepo,
            orderRepository = orderRepo,
            portfolioRepository = portfolioRepo,
            alertBuilder = OvernightAlertBuilder(),
            isLiveModeProvider = { true },
            accountHashProvider = { "HASH_LIVE_1" },
            accountEventsFlow = emptyFlow(),
            settlementDelayMs = 0L
        )

        val slOutcome = machine.executeTriggeredRule(
            rule = shortStopRule,
            quote = LiveEquityQuote("NVDA", bidPrice = 140.10, askPrice = 140.20, securityStatus = "Normal"),
            session = MarketSessionWindow.AFTER_HOURS
        )
        val tpOutcome = machine.executeTriggeredRule(
            rule = shortTpRule,
            quote = LiveEquityQuote("NVDA", bidPrice = 124.80, askPrice = 124.90, securityStatus = "Normal"),
            session = MarketSessionWindow.AFTER_HOURS
        )

        assertTrue(slOutcome is SentinelExecutionOutcome.Filled)
        assertTrue(tpOutcome is SentinelExecutionOutcome.Filled)
        assertEquals(2, orderRepo.submittedTickets.size)
        val builder = com.schwabquant.app.core.quant.SchwabOrderPayloadBuilder()
        orderRepo.submittedTickets.forEach { ticket ->
            assertEquals(OrderSide.BUY_TO_COVER, ticket.legs.single().side)
            assertTrue(builder.toJsonString(ticket).contains("\"instruction\":\"BUY_TO_COVER\""))
        }
    }

    // --- Pure-JVM Fakes ---

    private class FakeSentinelRepository : SentinelRepository {
        private val store = linkedMapOf<Long, SentinelRule>()
        val auditEvents = mutableListOf<String>()

        override fun observeRules(): Flow<List<SentinelRule>> = MutableStateFlow(store.values.toList())
        override fun observeArmedRules(): Flow<List<SentinelRule>> =
            MutableStateFlow(store.values.filter { it.status == SentinelRuleStatus.ARMED })
        override fun observeActiveRules(): Flow<List<SentinelRule>> =
            MutableStateFlow(
                store.values.filter {
                    it.status == SentinelRuleStatus.ARMED || it.status == SentinelRuleStatus.EXECUTING
                }
            )
        override suspend fun getRuleById(ruleId: Long): SentinelRule? = store[ruleId]
        override suspend fun getArmedRulesOnce(): List<SentinelRule> =
            store.values.filter { it.status == SentinelRuleStatus.ARMED }
        override suspend fun getRulesByStatus(status: SentinelRuleStatus): List<SentinelRule> =
            store.values.filter { it.status == status }
        override suspend fun upsertRule(rule: SentinelRule): Long {
            store[rule.id] = rule
            return rule.id
        }
        override suspend fun updateRuleStatus(ruleId: Long, status: SentinelRuleStatus) {
            store[ruleId]?.let { store[ruleId] = it.copy(status = status) }
        }
        override suspend fun updateRuleStatusWithNote(
            ruleId: Long,
            status: SentinelRuleStatus,
            note: String?,
            lastSubmittedOrderId: String?
        ) {
            store[ruleId]?.let {
                store[ruleId] = it.copy(
                    status = status,
                    lastTriggerNote = note,
                    lastSubmittedOrderId = if (status == SentinelRuleStatus.ARMED) null else (lastSubmittedOrderId ?: it.lastSubmittedOrderId)
                )
            }
        }
        override suspend fun disarmAllRules() {
            store.replaceAll { _, r -> if (r.status == SentinelRuleStatus.ARMED) r.copy(status = SentinelRuleStatus.PAUSED) else r }
        }
        override suspend fun deleteRule(ruleId: Long) {
            store.remove(ruleId)
        }
        override suspend fun recordAuditEvent(
            category: String,
            severity: String,
            symbol: String?,
            executionMode: String,
            message: String,
            metadataJson: String?
        ) {
            auditEvents.add(category)
        }
    }

    private class FakeOrderRepository : OrderRepository {
        var workingOrders: List<WorkingOrder> = emptyList()
        var submitStatus: String = "WORKING"
        var polledOrderStatus: String = "WORKING"
        var polledRemainingQtyOverride: Pair<Boolean, Double?> = false to null
        var failCancelOrder: Boolean = false
        var onOrderFilled: (() -> Unit)? = null
        var onGetWorkingOrders: (() -> Unit)? = null
        val canceledOrderIds = mutableListOf<String>()
        val submittedTickets = mutableListOf<OrderTicket>()

        override fun observeRecentOrders(mode: ExecutionMode): Flow<List<OrderSubmissionResult>> = emptyFlow()
        override fun observeWorkingOrders(): Flow<List<WorkingOrder>> = emptyFlow()
        override fun observeRecentAuditLogs(limit: Int): Flow<List<AuditLogEntity>> = emptyFlow()
        override suspend fun getWorkingOrders(forceRefresh: Boolean): Result<List<WorkingOrder>> {
            onGetWorkingOrders?.invoke()
            return Result.success(workingOrders)
        }
        override suspend fun getOrderStatus(accountHash: String, orderId: String): Result<OrderStatusSnapshot> {
            val status = if (orderId in canceledOrderIds) "CANCELED" else polledOrderStatus
            val remQty = if (polledRemainingQtyOverride.first) {
                polledRemainingQtyOverride.second
            } else {
                if (status == "WORKING") 16.0 else 0.0
            }
            return Result.success(
                OrderStatusSnapshot(
                    orderId = orderId,
                    status = status,
                    filledQuantity = if (status == "FILLED") 16.0 else 0.0,
                    remainingQuantity = remQty
                )
            )
        }
        override suspend fun submitOrder(
            ticket: OrderTicket,
            risksAcknowledged: Boolean,
            overriddenRiskCodes: List<String>,
            simulateImmediateMidFill: Boolean
        ): Result<OrderSubmissionResult> {
            submittedTickets.add(ticket)
            if (submitStatus.equals("FILLED", ignoreCase = true)) {
                onOrderFilled?.invoke()
            }
            return Result.success(
                OrderSubmissionResult(
                    orderId = "ORD-9001",
                    executionMode = ticket.executionMode,
                    status = submitStatus,
                    message = "Submitted",
                    submittedAtEpochMs = 1_000L,
                    fillPrice = ticket.limitPrice
                )
            )
        }
        override suspend fun cancelOrder(accountHash: String, orderId: String, mode: ExecutionMode): Result<Unit> {
            if (failCancelOrder) return Result.failure(IllegalStateException("Cancel rejected"))
            canceledOrderIds.add(orderId)
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
        ): Result<ReplaceOrderOutcome> = error("Unused")
        override suspend fun simulateShadowFill(orderId: String): Result<OrderSubmissionResult> = error("Unused")
    }

    private class FakePortfolioRepository(
        private val symbol: String = "GOOG",
        openEquityQty: Double,
        private val failLiveRefresh: Boolean = false
    ) : PortfolioRepository {
        private var currentQty: Double = openEquityQty
        private val snapshotFlow = MutableStateFlow(buildSnapshot(openEquityQty))
        var livePositionRefreshCalls = 0
            private set

        fun setOpenQuantity(newQty: Double) {
            currentQty = newQty
            snapshotFlow.value = buildSnapshot(newQty)
        }

        private fun buildSnapshot(qty: Double) = PortfolioSnapshot(
            dataSource = PortfolioDataSource.SHADOW_PAPER_LEDGER,
            accountSummary = AccountSummary(
                accountHash = "TEST",
                maskedAccountNumber = "****0000",
                executionMode = ExecutionMode.SHADOW,
                netLiquidationValue = BigDecimal("100000"),
                cashBalance = BigDecimal("50000"),
                buyingPower = BigDecimal("50000"),
                optionBuyingPower = BigDecimal("50000"),
                dayPnl = BigDecimal.ZERO,
                dayPnlPercent = 0.0,
                portfolioSpyBetaWeightedDelta = 0.0,
                updatedAtEpochMs = 1_000L
            ),
            positions = if (qty != 0.0) {
                listOf(
                    Position(
                        symbol = symbol,
                        underlyingSymbol = symbol,
                        category = AssetCategory.EQUITY,
                        quantity = BigDecimal.valueOf(qty),
                        averageCost = BigDecimal("260.00"),
                        markPrice = BigDecimal("269.00"),
                        marketValue = BigDecimal.valueOf(qty * 269.0),
                        unrealizedPnl = BigDecimal.ZERO,
                        unrealizedPnlPercent = 0.0
                    )
                )
            } else {
                emptyList()
            },
            groups = emptyList(),
            aggregatedGreeks = AggregatedGreeks(),
            spySpot = 580.0
        )

        override fun observePortfolioSnapshot(source: PortfolioDataSource): Flow<PortfolioSnapshot?> =
            snapshotFlow
        override fun observeAccountSummary(mode: ExecutionMode): Flow<AccountSummary?> = emptyFlow()
        override fun observePositions(mode: ExecutionMode): Flow<List<Position>> = emptyFlow()
        override suspend fun fetchAccountHashes(): Result<List<String>> = Result.success(listOf("TEST"))
        override suspend fun refreshLivePortfolio(accountHash: String?): Result<PortfolioSnapshot> {
            livePositionRefreshCalls++
            return if (failLiveRefresh) Result.failure(IllegalStateException("HTTP 503")) else Result.success(snapshotFlow.value)
        }
        override suspend fun refreshLivePositionsOnly(accountHash: String?): Result<PortfolioSnapshot> =
            refreshLivePortfolio(accountHash)
        override suspend fun refreshShadowPortfolio(): Result<PortfolioSnapshot> =
            Result.success(snapshotFlow.value)
        override suspend fun seedDemoPortfolio(): Result<Unit> = Result.success(Unit)
    }
}
