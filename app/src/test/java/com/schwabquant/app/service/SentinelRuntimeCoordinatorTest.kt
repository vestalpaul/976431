package com.schwabquant.app.service

import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.streamer.LiveEquityQuote
import com.schwabquant.app.data.remote.streamer.SchwabStreamerClient
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleStatus
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.domain.model.StagedOrderHolder
import com.schwabquant.app.domain.repository.SentinelRepository
import com.schwabquant.app.domain.usecase.AntiStopHuntEvaluator
import com.schwabquant.app.domain.usecase.MarketSessionResolver
import com.schwabquant.app.domain.usecase.MarketSessionWindow
import com.schwabquant.app.domain.usecase.OvernightAlertBuilder
import com.schwabquant.app.domain.usecase.SentinelExecutionOutcome
import com.schwabquant.app.domain.usecase.SentinelExecutionStateMachine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class SentinelRuntimeCoordinatorTest {

    @Test
    fun `C-1 activeExecutionCount stays greater than zero while async execution and notification emit run`() = runTest {
        val rule = SentinelRule(
            id = 42L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            holdSeconds = 0,
            minConsecutiveTicks = 1,
            executionMode = ExecutionMode.LIVE
        )
        val sentinelRepository = mockk<SentinelRepository>(relaxed = true) {
            coEvery { getArmedRulesOnce() } returns listOf(rule)
        }
        val execGate = CompletableDeferred<Unit>()
        val executionStateMachine = mockk<SentinelExecutionStateMachine> {
            coEvery {
                executeTriggeredRule(rule = rule, quote = any(), session = any(), any(), any())
            } coAnswers {
                execGate.await()
                SentinelExecutionOutcome.Filled(
                    ruleId = rule.id,
                    orderId = "9001",
                    fillPrice = BigDecimal("269.00"),
                    executionMode = ExecutionMode.LIVE,
                    message = "Filled"
                )
            }
        }
        val preferences = mockk<AppPreferencesDataStore>(relaxed = true) {
            every { isLiveMode } returns flowOf(true)
        }
        val coordinator = SentinelRuntimeCoordinator(
            sentinelRepository = sentinelRepository,
            antiStopHuntEvaluator = AntiStopHuntEvaluator(),
            marketSessionResolver = MarketSessionResolver(),
            executionStateMachine = executionStateMachine,
            alertBuilder = OvernightAlertBuilder(),
            streamerClient = mockk<SchwabStreamerClient>(relaxed = true),
            preferences = preferences,
            stagedOrderHolder = StagedOrderHolder()
        )

        val collectedEventDeferred = async {
            coordinator.runtimeEvents.first { it is SentinelRuntimeEvent.OrderFilled }
        }
        runCurrent()

        coordinator.evaluateIncomingQuote(
            quote = LiveEquityQuote("GOOG", bidPrice = 269.00, askPrice = 269.20, securityStatus = "Normal"),
            scope = this,
            sessionOverride = MarketSessionWindow.REGULAR,
            nowEpochMs = 1_000L
        )
        runCurrent()

        assertEquals(1, coordinator.activeExecutionCount.value, "Service shutdown must see activeExecutionCount > 0 mid-execution")

        execGate.complete(Unit)
        runCurrent()

        val filledEvent = collectedEventDeferred.await() as SentinelRuntimeEvent.OrderFilled
        assertEquals("9001", filledEvent.orderId)
        assertEquals(0, coordinator.activeExecutionCount.value, "Count must decrement cleanly after notification emits")
    }

    @Test
    fun `M-4 recoverOrphanedExecutingRules returns recovered rules and emits OrphanedRulesRecovered`() = runTest {
        val orphaned = SentinelRule(
            id = 88L,
            symbol = "NVDA",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("120.00"),
            quantity = 5,
            status = SentinelRuleStatus.EXECUTING,
            executionMode = ExecutionMode.LIVE
        )
        val sentinelRepository = mockk<SentinelRepository>(relaxed = true) {
            coEvery { getRulesByStatus(SentinelRuleStatus.EXECUTING) } returns listOf(orphaned)
        }
        val preferences = mockk<AppPreferencesDataStore>(relaxed = true) {
            every { isLiveMode } returns MutableStateFlow(true)
        }
        val coordinator = SentinelRuntimeCoordinator(
            sentinelRepository = sentinelRepository,
            antiStopHuntEvaluator = AntiStopHuntEvaluator(),
            marketSessionResolver = MarketSessionResolver(),
            executionStateMachine = mockk<SentinelExecutionStateMachine>(relaxed = true),
            alertBuilder = OvernightAlertBuilder(),
            streamerClient = mockk<SchwabStreamerClient>(relaxed = true),
            preferences = preferences,
            stagedOrderHolder = StagedOrderHolder()
        )

        val listener = async {
            coordinator.runtimeEvents.first { it is SentinelRuntimeEvent.OrphanedRulesRecovered }
        }
        runCurrent()

        val recovered = coordinator.recoverOrphanedExecutingRules()
        runCurrent()

        assertEquals(listOf(orphaned), recovered)
        val event = listener.await() as SentinelRuntimeEvent.OrphanedRulesRecovered
        assertTrue(event.rules.any { it.id == 88L && it.symbol == "NVDA" })
    }

    @Test
    fun `M-5 recoverOrphanedExecutingRules skips active execution IDs and logs rule executionMode`() = runTest {
        val activelyExecuting = SentinelRule(
            id = 101L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            status = SentinelRuleStatus.EXECUTING,
            executionMode = ExecutionMode.SHADOW
        )
        val trulyOrphaned = activelyExecuting.copy(id = 102L, symbol = "SPY")

        val sentinelRepository = mockk<SentinelRepository>(relaxed = true) {
            coEvery { getRulesByStatus(SentinelRuleStatus.EXECUTING) } returns listOf(activelyExecuting, trulyOrphaned)
        }
        val stateMachine = mockk<SentinelExecutionStateMachine>(relaxed = true) {
            every { getActiveRuleIds() } returns setOf(101L)
        }
        // Global mode = LIVE while orphaned rule belongs to SHADOW
        val preferences = mockk<AppPreferencesDataStore>(relaxed = true) {
            every { isLiveMode } returns MutableStateFlow(true)
        }
        val coordinator = SentinelRuntimeCoordinator(
            sentinelRepository = sentinelRepository,
            antiStopHuntEvaluator = AntiStopHuntEvaluator(),
            marketSessionResolver = MarketSessionResolver(),
            executionStateMachine = stateMachine,
            alertBuilder = OvernightAlertBuilder(),
            streamerClient = mockk<SchwabStreamerClient>(relaxed = true),
            preferences = preferences,
            stagedOrderHolder = StagedOrderHolder()
        )

        val recovered = coordinator.recoverOrphanedExecutingRules()
        assertEquals(listOf(trulyOrphaned), recovered)

        coVerify(exactly = 0) {
            sentinelRepository.updateRuleStatusWithNote(101L, any(), any(), any())
        }
        coVerify(exactly = 1) {
            sentinelRepository.updateRuleStatusWithNote(102L, SentinelRuleStatus.UNFILLED_ESCALATED, any(), any())
        }
        coVerify(exactly = 1) {
            sentinelRepository.recordAuditEvent(
                category = "SENTINEL_ORPHANED_EXECUTING_RECOVERED",
                severity = "CRITICAL",
                symbol = "SPY",
                executionMode = ExecutionMode.SHADOW.name,
                message = any(),
                metadataJson = any()
            )
        }
    }

    @Test
    fun `HIGH-2 overnight alert holds activeExecutionCount above zero, deduplicates in-flight quotes, and carries rule executionMode`() = runTest {
        val rule = SentinelRule(
            id = 55L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            holdSeconds = 0,
            minConsecutiveTicks = 1,
            session = com.schwabquant.app.domain.model.TradingSession.SEAMLESS,
            executionMode = ExecutionMode.LIVE
        )
        val alertGate = CompletableDeferred<Unit>()
        val sentinelRepository = mockk<SentinelRepository>(relaxed = true) {
            coEvery { getArmedRulesOnce() } returns listOf(rule)
            coEvery {
                updateRuleStatusWithNote(
                    ruleId = rule.id,
                    status = SentinelRuleStatus.ALERTED,
                    note = any(),
                    lastSubmittedOrderId = any()
                )
            } coAnswers {
                alertGate.await()
            }
        }
        val coordinator = SentinelRuntimeCoordinator(
            sentinelRepository = sentinelRepository,
            antiStopHuntEvaluator = AntiStopHuntEvaluator(),
            marketSessionResolver = MarketSessionResolver(),
            executionStateMachine = mockk(relaxed = true),
            alertBuilder = OvernightAlertBuilder(),
            streamerClient = mockk<SchwabStreamerClient>(relaxed = true),
            preferences = mockk<AppPreferencesDataStore>(relaxed = true) {
                every { isLiveMode } returns flowOf(true)
            },
            stagedOrderHolder = StagedOrderHolder()
        )

        val alertDeferred = async {
            coordinator.runtimeEvents.first { it is SentinelRuntimeEvent.OvernightAlert }
        }
        runCurrent()

        coordinator.evaluateIncomingQuote(
            quote = LiveEquityQuote("GOOG", bidPrice = 269.00, askPrice = 269.20, securityStatus = "Normal"),
            scope = this,
            sessionOverride = MarketSessionWindow.OVERNIGHT_EXTO,
            nowEpochMs = 1_000L
        )
        runCurrent()
        assertEquals(1, coordinator.activeExecutionCount.value)

        // Second quote while async alert persistence/logging is still in flight must be deduplicated
        coordinator.evaluateIncomingQuote(
            quote = LiveEquityQuote("GOOG", bidPrice = 268.50, askPrice = 268.80, securityStatus = "Normal"),
            scope = this,
            sessionOverride = MarketSessionWindow.OVERNIGHT_EXTO,
            nowEpochMs = 2_000L
        )
        runCurrent()
        assertEquals(1, coordinator.activeExecutionCount.value)

        alertGate.complete(Unit)
        val emitted = alertDeferred.await() as SentinelRuntimeEvent.OvernightAlert
        runCurrent()

        assertEquals(0, coordinator.activeExecutionCount.value)
        assertEquals(ExecutionMode.LIVE, emitted.payload.executionMode)
        coVerify(exactly = 1) {
            sentinelRepository.updateRuleStatusWithNote(
                ruleId = rule.id,
                status = SentinelRuleStatus.ALERTED,
                note = any(),
                lastSubmittedOrderId = null
            )
        }
        coVerify(exactly = 1) {
            sentinelRepository.recordAuditEvent(
                category = any(),
                severity = any(),
                symbol = "GOOG",
                executionMode = ExecutionMode.LIVE.name,
                message = any(),
                metadataJson = any()
            )
        }
    }

    @Test
    fun `HIGH-1 UnfilledEscalated event preserves triggering rule executionMode`() = runTest {
        val rule = SentinelRule(
            id = 56L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 5,
            holdSeconds = 0,
            minConsecutiveTicks = 1,
            executionMode = ExecutionMode.LIVE
        )
        val stagedDraft = OrderDraft(
            strategyCategory = com.schwabquant.app.domain.model.OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = "GOOG",
            orderType = com.schwabquant.app.domain.model.OrderType.LIMIT,
            session = com.schwabquant.app.domain.model.TradingSession.SEAMLESS,
            duration = com.schwabquant.app.domain.model.OrderDuration.DAY,
            limitPrice = BigDecimal("268.00"),
            legs = listOf(
                com.schwabquant.app.domain.model.OrderLeg(
                    symbol = "GOOG",
                    category = com.schwabquant.app.domain.model.AssetCategory.EQUITY,
                    side = com.schwabquant.app.domain.model.OrderSide.SELL,
                    quantity = 5
                )
            ),
            replacePriorOrderId = "9009",
            isEscalatedReprice = true
        )
        val executionStateMachine = mockk<SentinelExecutionStateMachine> {
            coEvery {
                executeTriggeredRule(rule = rule, quote = any(), session = any(), any(), any())
            } returns SentinelExecutionOutcome.UnfilledEscalated(
                ruleId = rule.id,
                orderId = "9009",
                repriceDraft = stagedDraft,
                message = "Escalated"
            )
        }
        val coordinator = SentinelRuntimeCoordinator(
            sentinelRepository = mockk(relaxed = true) {
                coEvery { getArmedRulesOnce() } returns listOf(rule)
            },
            antiStopHuntEvaluator = AntiStopHuntEvaluator(),
            marketSessionResolver = MarketSessionResolver(),
            executionStateMachine = executionStateMachine,
            alertBuilder = OvernightAlertBuilder(),
            streamerClient = mockk<SchwabStreamerClient>(relaxed = true),
            preferences = mockk<AppPreferencesDataStore>(relaxed = true) {
                every { isLiveMode } returns flowOf(true)
            },
            stagedOrderHolder = StagedOrderHolder()
        )

        val escalatedDeferred = async {
            coordinator.runtimeEvents.first { it is SentinelRuntimeEvent.UnfilledEscalated }
        }
        runCurrent()

        coordinator.evaluateIncomingQuote(
            quote = LiveEquityQuote("GOOG", bidPrice = 269.00, askPrice = 269.20, securityStatus = "Normal"),
            scope = this,
            sessionOverride = MarketSessionWindow.REGULAR,
            nowEpochMs = 1_000L
        )
        val emitted = escalatedDeferred.await() as SentinelRuntimeEvent.UnfilledEscalated
        assertEquals(ExecutionMode.LIVE, emitted.executionMode)
        assertEquals("9009", emitted.orderId)
    }

    @Test
    fun `MEDIUM-3 activeExecutionCount stays above zero until trackedRuntimeEvents notification ack completes`() = runTest {
        val rule = SentinelRule(
            id = 77L,
            symbol = "SPY",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("580.00"),
            quantity = 4,
            holdSeconds = 0,
            minConsecutiveTicks = 1,
            executionMode = ExecutionMode.SHADOW
        )
        val executionStateMachine = mockk<SentinelExecutionStateMachine> {
            coEvery {
                executeTriggeredRule(rule = rule, quote = any(), session = any(), any(), any())
            } returns SentinelExecutionOutcome.Filled(
                ruleId = rule.id,
                orderId = "SHADOW-77",
                fillPrice = BigDecimal("579.00"),
                executionMode = ExecutionMode.SHADOW,
                message = "Filled"
            )
        }
        val coordinator = SentinelRuntimeCoordinator(
            sentinelRepository = mockk(relaxed = true) {
                coEvery { getArmedRulesOnce() } returns listOf(rule)
            },
            antiStopHuntEvaluator = AntiStopHuntEvaluator(),
            marketSessionResolver = MarketSessionResolver(),
            executionStateMachine = executionStateMachine,
            alertBuilder = OvernightAlertBuilder(),
            streamerClient = mockk<SchwabStreamerClient>(relaxed = true),
            preferences = mockk<AppPreferencesDataStore>(relaxed = true) {
                every { isLiveMode } returns flowOf(false)
            },
            stagedOrderHolder = StagedOrderHolder()
        )

        val receivedAckGate = CompletableDeferred<CompletableDeferred<Unit>?>()
        val collectorJob = backgroundScope.launch {
            coordinator.trackedRuntimeEvents.collect { tracked ->
                if (tracked.event is SentinelRuntimeEvent.OrderFilled) {
                    receivedAckGate.complete(tracked.ack)
                } else {
                    tracked.ack?.complete(Unit)
                }
            }
        }
        runCurrent()

        coordinator.evaluateIncomingQuote(
            quote = LiveEquityQuote("SPY", bidPrice = 579.00, askPrice = 579.20, securityStatus = "Normal"),
            scope = this,
            sessionOverride = MarketSessionWindow.REGULAR,
            nowEpochMs = 1_000L
        )
        runCurrent()

        val ack = receivedAckGate.await()
        assertEquals(1, coordinator.activeExecutionCount.value, "Must wait for notification ack before releasing last activeExecutionCount")

        ack?.complete(Unit)
        runCurrent()
        assertEquals(0, coordinator.activeExecutionCount.value)
        collectorJob.cancel()
    }

    @Test
    fun `HIGH-1 Shadow Tick Replay Simulator refuses LIVE rules without mutating status or wiping lastSubmittedOrderId`() = runTest {
        val liveEscalatedRule = SentinelRule(
            id = 99L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 10,
            status = SentinelRuleStatus.UNFILLED_ESCALATED,
            lastSubmittedOrderId = "1002345678",
            executionMode = ExecutionMode.LIVE
        )
        val sentinelRepository = mockk<SentinelRepository>(relaxed = true)
        val stagedOrderHolder = StagedOrderHolder()
        val coordinator = SentinelRuntimeCoordinator(
            sentinelRepository = sentinelRepository,
            antiStopHuntEvaluator = AntiStopHuntEvaluator(),
            marketSessionResolver = MarketSessionResolver(),
            executionStateMachine = mockk(relaxed = true),
            alertBuilder = OvernightAlertBuilder(),
            streamerClient = mockk<SchwabStreamerClient>(relaxed = true),
            preferences = mockk<AppPreferencesDataStore>(relaxed = true) {
                every { isLiveMode } returns flowOf(false)
            },
            stagedOrderHolder = stagedOrderHolder
        )

        assertTrue(coordinator.simulateStopHuntWick(liveEscalatedRule, this).isFailure)
        assertTrue(coordinator.simulateSpreadBlowout(liveEscalatedRule, this).isFailure)
        assertTrue(coordinator.simulateConfirmedPmBreak(liveEscalatedRule, this).isFailure)
        assertTrue(coordinator.simulateOvernightAlert(liveEscalatedRule, this).isFailure)

        coVerify(exactly = 0) {
            sentinelRepository.updateRuleStatusWithNote(any(), any(), any(), any())
        }
        assertEquals(null, stagedOrderHolder.activeOrderDraft.value)
    }
}
