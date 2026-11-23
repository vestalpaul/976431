package com.schwabquant.app.service

import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.data.remote.streamer.LiveEquityQuote
import com.schwabquant.app.data.remote.streamer.SchwabStreamerClient
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.MicrostructureEvalOutcome
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.RuleRuntimeTelemetry
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleStatus
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.domain.model.SentinelServiceStatus
import com.schwabquant.app.domain.model.StagedOrderHolder
import com.schwabquant.app.domain.repository.SentinelRepository
import com.schwabquant.app.domain.usecase.AntiStopHuntEvaluator
import com.schwabquant.app.domain.usecase.MarketSessionResolver
import com.schwabquant.app.domain.usecase.MarketSessionWindow
import com.schwabquant.app.domain.usecase.OvernightAlertBuilder
import com.schwabquant.app.domain.usecase.OvernightAlertPayload
import com.schwabquant.app.domain.usecase.RuleEvaluationTracker
import com.schwabquant.app.domain.usecase.SentinelExecutionOutcome
import com.schwabquant.app.domain.usecase.SentinelExecutionStateMachine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class TrackedRuntimeEvent(
    val event: SentinelRuntimeEvent,
    val ack: CompletableDeferred<Unit>? = null
)

sealed interface SentinelRuntimeEvent {
    data class OrderFilled(
        val ruleId: Long,
        val symbol: String,
        val orderId: String,
        val message: String
    ) : SentinelRuntimeEvent

    data class UnfilledEscalated(
        val ruleId: Long,
        val symbol: String,
        val orderId: String,
        val repriceDraft: OrderDraft,
        val message: String,
        val executionMode: ExecutionMode = ExecutionMode.SHADOW
    ) : SentinelRuntimeEvent

    data class OvernightAlert(
        val payload: OvernightAlertPayload
    ) : SentinelRuntimeEvent

    data class DipBuyAlert(
        val payload: OvernightAlertPayload
    ) : SentinelRuntimeEvent

    data class OrphanedRulesRecovered(
        val rules: List<SentinelRule>
    ) : SentinelRuntimeEvent

    data class InfoMessage(
        val message: String
    ) : SentinelRuntimeEvent
}

/**
 * Shared runtime coordinator uniting `SentinelForegroundService`, `AntiStopHuntEvaluator`,
 * `SentinelExecutionStateMachine`, `OvernightAlertBuilder`, and the Shadow Mode Tick Replay Simulator.
 */
@Singleton
class SentinelRuntimeCoordinator @Inject constructor(
    private val sentinelRepository: SentinelRepository,
    private val antiStopHuntEvaluator: AntiStopHuntEvaluator,
    private val marketSessionResolver: MarketSessionResolver,
    private val executionStateMachine: SentinelExecutionStateMachine,
    private val alertBuilder: OvernightAlertBuilder,
    private val streamerClient: SchwabStreamerClient,
    private val preferences: AppPreferencesDataStore,
    private val stagedOrderHolder: StagedOrderHolder
) {
    private val trackers = ConcurrentHashMap<Long, RuleEvaluationTracker>()
    private val inFlightAlertRuleIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private val _serviceStatus = MutableStateFlow(
        SentinelServiceStatus(
            activeSession = marketSessionResolver.resolve()
        )
    )
    val serviceStatus: StateFlow<SentinelServiceStatus> = _serviceStatus.asStateFlow()

    private val _ruleTelemetry = MutableStateFlow<Map<Long, RuleRuntimeTelemetry>>(emptyMap())
    val ruleTelemetry: StateFlow<Map<Long, RuleRuntimeTelemetry>> = _ruleTelemetry.asStateFlow()

    private val _runtimeEvents = MutableSharedFlow<SentinelRuntimeEvent>(extraBufferCapacity = 32)
    val runtimeEvents: SharedFlow<SentinelRuntimeEvent> = _runtimeEvents.asSharedFlow()

    private val _trackedRuntimeEvents = MutableSharedFlow<TrackedRuntimeEvent>(extraBufferCapacity = 32)
    val trackedRuntimeEvents: SharedFlow<TrackedRuntimeEvent> = _trackedRuntimeEvents.asSharedFlow()

    private val _activeExecutionCount = MutableStateFlow(0)
    val activeExecutionCount: StateFlow<Int> = _activeExecutionCount.asStateFlow()

    private suspend fun emitRuntimeEventAndAwaitHandled(event: SentinelRuntimeEvent) {
        _runtimeEvents.emit(event)
        if (_trackedRuntimeEvents.subscriptionCount.value > 0) {
            val ack = CompletableDeferred<Unit>()
            _trackedRuntimeEvents.emit(TrackedRuntimeEvent(event, ack))
            withTimeoutOrNull(EVENT_HANDLING_ACK_TIMEOUT_MS) {
                ack.await()
            }
        }
    }

    fun updateServiceStatus(transform: (SentinelServiceStatus) -> SentinelServiceStatus) {
        _serviceStatus.value = transform(_serviceStatus.value)
    }

    fun clearTracker(ruleId: Long) {
        trackers.remove(ruleId)
        _ruleTelemetry.value = _ruleTelemetry.value - ruleId
    }

    fun clearAllTrackers() {
        trackers.clear()
        _ruleTelemetry.value = emptyMap()
    }

    /**
     * Safeguard #5 + M-5: Recover any orphaned rules stuck in `EXECUTING` after an unexpected OS kill
     * or reboot by escalating them to `UNFILLED_ESCALATED` without blindly re-submitting orders,
     * excluding rules actively running in-memory and attributing audit entries to `rule.executionMode.name`.
     */
    suspend fun recoverOrphanedExecutingRules(): List<SentinelRule> {
        val activeIds = executionStateMachine.getActiveRuleIds()
        val orphaned = sentinelRepository.getRulesByStatus(SentinelRuleStatus.EXECUTING)
            .filter { it.id !in activeIds }
        if (orphaned.isEmpty()) return emptyList()

        orphaned.forEach { rule ->
            sentinelRepository.updateRuleStatusWithNote(
                ruleId = rule.id,
                status = SentinelRuleStatus.UNFILLED_ESCALATED,
                note = ORPHANED_EXECUTING_NOTE
            )
            sentinelRepository.recordAuditEvent(
                category = "SENTINEL_ORPHANED_EXECUTING_RECOVERED",
                severity = "CRITICAL",
                symbol = rule.symbol,
                executionMode = rule.executionMode.name,
                message = "Rule #${rule.id} (${rule.symbol}) found in EXECUTING on startup — $ORPHANED_EXECUTING_NOTE"
            )
            clearTracker(rule.id)
        }
        emitRuntimeEventAndAwaitHandled(SentinelRuntimeEvent.OrphanedRulesRecovered(orphaned))
        return orphaned
    }

    /**
     * Evaluates an incoming Level-1 Equity quote against every currently ARMED rule matching its symbol
     * and the currently active ExecutionMode (C-5 Shadow/Live isolation).
     */
    suspend fun evaluateIncomingQuote(
        quote: LiveEquityQuote,
        scope: CoroutineScope,
        sessionOverride: MarketSessionWindow? = null,
        nowEpochMs: Long = quote.receivedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
    ) {
        val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(quote.symbol)
        val activeMode = if (preferences.isLiveMode.first()) ExecutionMode.LIVE else ExecutionMode.SHADOW
        val armedRules = sentinelRepository.getArmedRulesOnce()
            .filter {
                it.executionMode == activeMode &&
                    SchwabSymbolNormalizer.toDisplaySymbol(it.symbol) == displaySym
            }
        if (armedRules.isEmpty()) return

        val session = sessionOverride ?: marketSessionResolver.resolve()
        for (rule in armedRules) {
            evaluateRuleOnTick(rule, quote, session, nowEpochMs, scope)
        }
    }

    private suspend fun evaluateRuleOnTick(
        rule: SentinelRule,
        quote: LiveEquityQuote,
        session: MarketSessionWindow,
        nowEpochMs: Long,
        scope: CoroutineScope
    ) {
        val currentTracker = trackers[rule.id] ?: RuleEvaluationTracker()
        val eval = antiStopHuntEvaluator.evaluateTick(
            rule = rule,
            quote = quote,
            session = session,
            tracker = currentTracker,
            nowEpochMs = nowEpochMs
        )
        trackers[rule.id] = eval.tracker

        val telemetry = RuleRuntimeTelemetry(
            ruleId = rule.id,
            outcome = eval.outcome,
            elapsedHoldSeconds = eval.elapsedSeconds,
            consecutiveTicks = eval.tracker.consecutiveBreachTicks,
            bid = quote.bidPrice,
            ask = quote.askPrice,
            spreadPct = eval.spreadPct,
            detailMessage = eval.detailMessage
        )
        _ruleTelemetry.value = _ruleTelemetry.value + (rule.id to telemetry)

        when (eval.outcome) {
            MicrostructureEvalOutcome.CONFIRMED_EXECUTE -> {
                launchTrackedExecution(
                    scope = scope,
                    rule = rule,
                    quote = quote,
                    session = session
                )
            }

            MicrostructureEvalOutcome.CONFIRMED_DIP_ALERT -> {
                if (inFlightAlertRuleIds.add(rule.id)) {
                    try {
                        launchTrackedAction(scope) {
                            try {
                                val outcome = executionStateMachine.executeTriggeredRule(
                                    rule = rule,
                                    quote = quote,
                                    session = session
                                )
                                handleExecutionOutcome(rule, outcome)
                            } finally {
                                inFlightAlertRuleIds.remove(rule.id)
                            }
                        }
                    } catch (t: Throwable) {
                        inFlightAlertRuleIds.remove(rule.id)
                        throw t
                    }
                }
            }

            MicrostructureEvalOutcome.OVERNIGHT_ALERT_ONLY -> {
                if (inFlightAlertRuleIds.add(rule.id)) {
                    try {
                        val payload = alertBuilder.buildOvernightAlert(rule, quote)
                        val note = "Overnight EXTO breach @ $${eval.evaluatedPrice ?: rule.triggerPrice} — alert only (zero API order)."
                        launchTrackedAction(scope) {
                            try {
                                sentinelRepository.updateRuleStatusWithNote(
                                    rule.id,
                                    SentinelRuleStatus.ALERTED,
                                    note
                                )
                                sentinelRepository.recordAuditEvent(
                                    category = "SENTINEL_OVERNIGHT_ALERT",
                                    severity = "INFO",
                                    symbol = rule.symbol,
                                    executionMode = rule.executionMode.name,
                                    message = note
                                )
                                trackers.remove(rule.id)
                                emitRuntimeEventAndAwaitHandled(SentinelRuntimeEvent.OvernightAlert(payload))
                            } finally {
                                inFlightAlertRuleIds.remove(rule.id)
                            }
                        }
                    } catch (t: Throwable) {
                        inFlightAlertRuleIds.remove(rule.id)
                        throw t
                    }
                }
            }

            else -> Unit
        }
    }

    private fun launchTrackedExecution(
        scope: CoroutineScope,
        rule: SentinelRule,
        quote: LiveEquityQuote,
        session: MarketSessionWindow
    ) {
        launchTrackedAction(scope) {
            val outcome = executionStateMachine.executeTriggeredRule(
                rule = rule,
                quote = quote,
                session = session
            )
            handleExecutionOutcome(rule, outcome)
        }
    }

    private fun launchTrackedAction(
        scope: CoroutineScope,
        block: suspend () -> Unit
    ) {
        _activeExecutionCount.update { it + 1 }
        try {
            scope.launch {
                try {
                    block()
                } finally {
                    _activeExecutionCount.update { (it - 1).coerceAtLeast(0) }
                }
            }
        } catch (t: Throwable) {
            _activeExecutionCount.update { (it - 1).coerceAtLeast(0) }
            throw t
        }
    }

    private suspend fun handleExecutionOutcome(
        rule: SentinelRule,
        outcome: SentinelExecutionOutcome
    ) {
        when (outcome) {
            is SentinelExecutionOutcome.Filled -> {
                trackers.remove(rule.id)
                emitRuntimeEventAndAwaitHandled(
                    SentinelRuntimeEvent.OrderFilled(
                        ruleId = rule.id,
                        symbol = rule.symbol,
                        orderId = outcome.orderId,
                        message = outcome.message
                    )
                )
            }

            is SentinelExecutionOutcome.UnfilledEscalated -> {
                trackers.remove(rule.id)
                emitRuntimeEventAndAwaitHandled(
                    SentinelRuntimeEvent.UnfilledEscalated(
                        ruleId = rule.id,
                        symbol = rule.symbol,
                        orderId = outcome.orderId,
                        repriceDraft = outcome.repriceDraft,
                        message = outcome.message,
                        executionMode = rule.executionMode
                    )
                )
            }

            is SentinelExecutionOutcome.StagedAlertOnly -> {
                trackers.remove(rule.id)
                if (outcome.payload.isDipBuyAlert) {
                    emitRuntimeEventAndAwaitHandled(SentinelRuntimeEvent.DipBuyAlert(outcome.payload))
                } else {
                    emitRuntimeEventAndAwaitHandled(SentinelRuntimeEvent.OvernightAlert(outcome.payload))
                }
            }

            is SentinelExecutionOutcome.AbortedNoPosition -> {
                trackers.remove(rule.id)
                emitRuntimeEventAndAwaitHandled(SentinelRuntimeEvent.InfoMessage(outcome.reason))
            }

            is SentinelExecutionOutcome.RejectedOvernight -> {
                emitRuntimeEventAndAwaitHandled(SentinelRuntimeEvent.InfoMessage(outcome.reason))
            }

            is SentinelExecutionOutcome.Failed -> {
                trackers.remove(rule.id)
                emitRuntimeEventAndAwaitHandled(SentinelRuntimeEvent.InfoMessage(outcome.reason))
            }

            is SentinelExecutionOutcome.AlreadyInProgress -> Unit
        }
    }

    // -------------------------------------------------------------------------
    // Shadow Mode Tick Replay Simulator (Strictly disabled in LIVE mode!)
    // -------------------------------------------------------------------------

    suspend fun simulateStopHuntWick(rule: SentinelRule, scope: CoroutineScope): Result<String> =
        runShadowSimulation(rule) { activeRule, trigger, baseTime ->
            clearTracker(activeRule.id)
            val isShortSide = activeRule.ruleType == SentinelRuleType.SHORT_STOP_LOSS ||
                (activeRule.ruleType == SentinelRuleType.TAKE_PROFIT && activeRule.isShortPosition)

            val breachBid = if (isShortSide) trigger + 0.30 else (trigger - 0.80).coerceAtLeast(0.05)
            val breachAsk = if (isShortSide) trigger + 0.50 else (breachBid + 0.12)

            val wickTick = LiveEquityQuote(
                symbol = activeRule.symbol,
                bidPrice = breachBid,
                askPrice = breachAsk,
                lastPrice = breachBid,
                securityStatus = "Normal",
                receivedAtEpochMs = baseTime
            )
            streamerClient.injectSimulatedQuote(wickTick)
            evaluateRuleOnTick(activeRule, wickTick, MarketSessionWindow.AFTER_HOURS, baseTime, scope)

            val recoverBid = if (isShortSide) (trigger - 0.60).coerceAtLeast(0.05) else trigger + 0.60
            val recoverAsk = recoverBid + 0.10
            val recoveryTick = LiveEquityQuote(
                symbol = activeRule.symbol,
                bidPrice = recoverBid,
                askPrice = recoverAsk,
                lastPrice = recoverBid,
                securityStatus = "Normal",
                receivedAtEpochMs = baseTime + 2_000L
            )
            streamerClient.injectSimulatedQuote(recoveryTick)
            evaluateRuleOnTick(activeRule, recoveryTick, MarketSessionWindow.AFTER_HOURS, baseTime + 2_000L, scope)

            val msg = "Stop-Hunt Wick defeated on ${activeRule.symbol}: 2s flash wick reset back to WATCHING (0 orders submitted)."
            sentinelRepository.recordAuditEvent(
                category = "SHADOW_SIM_STOP_HUNT_WICK",
                severity = "INFO",
                symbol = activeRule.symbol,
                executionMode = ExecutionMode.SHADOW.name,
                message = msg
            )
            msg
        }

    suspend fun simulateSpreadBlowout(rule: SentinelRule, scope: CoroutineScope): Result<String> =
        runShadowSimulation(rule) { activeRule, trigger, baseTime ->
            val bid = (trigger - 2.00).coerceAtLeast(1.00)
            val ask = bid * 1.08 // 7.7% spread blowout
            val wideTick = LiveEquityQuote(
                symbol = activeRule.symbol,
                bidPrice = bid,
                askPrice = ask,
                lastPrice = bid,
                securityStatus = "Normal",
                receivedAtEpochMs = baseTime
            )
            streamerClient.injectSimulatedQuote(wideTick)
            evaluateRuleOnTick(activeRule, wideTick, MarketSessionWindow.AFTER_HOURS, baseTime, scope)

            val msg = "Spread Blowout suppressed on ${activeRule.symbol} (spread ~7.7% > max ${activeRule.maxSpreadPct}%): execution paused, 0 orders submitted."
            sentinelRepository.recordAuditEvent(
                category = "SHADOW_SIM_SPREAD_BLOWOUT",
                severity = "INFO",
                symbol = activeRule.symbol,
                executionMode = ExecutionMode.SHADOW.name,
                message = msg
            )
            msg
        }

    suspend fun simulateConfirmedPmBreak(rule: SentinelRule, scope: CoroutineScope): Result<String> =
        runShadowSimulation(rule) { activeRule, trigger, baseTime ->
            clearTracker(activeRule.id)

            if (activeRule.ruleType == SentinelRuleType.DIP_BUY_ANTI_KNIFE) {
                val lowBid = (trigger - 1.50).coerceAtLeast(1.00)
                val bounceStep = (lowBid * (activeRule.antiKnifeBouncePct / 100.0 + 0.002)).coerceAtLeast(0.10)
                val ticks = listOf(
                    lowBid to baseTime,
                    (lowBid + bounceStep * 0.4) to (baseTime + 2_000L),
                    (lowBid + bounceStep * 0.8) to (baseTime + 4_000L),
                    (lowBid + bounceStep * 1.2) to (baseTime + 6_000L)
                )
                ticks.forEach { (b, ts) ->
                    val q = LiveEquityQuote(
                        symbol = activeRule.symbol,
                        bidPrice = b,
                        askPrice = b + 0.08,
                        lastPrice = b,
                        securityStatus = "Normal",
                        receivedAtEpochMs = ts
                    )
                    streamerClient.injectSimulatedQuote(q)
                    evaluateRuleOnTick(activeRule, q, MarketSessionWindow.AFTER_HOURS, ts, scope)
                }
                val payload = alertBuilder.buildDipBuyStagingPayload(
                    activeRule,
                    LiveEquityQuote(
                        symbol = activeRule.symbol,
                        bidPrice = lowBid + bounceStep * 1.2,
                        askPrice = lowBid + bounceStep * 1.2 + 0.08
                    )
                )
                stagedOrderHolder.stageDraft(payload.stagedOrderDraft)
                return@runShadowSimulation "Anti-Knife Dip stabilized on ${activeRule.symbol} — staged SEAMLESS LIMIT buy in Order Ticket for Biometric confirmation (zero auto-buy)."
            }

            val tickCount = activeRule.minConsecutiveTicks.coerceAtLeast(3)
            val holdMs = (activeRule.holdSeconds.coerceAtLeast(1) + 2) * 1_000L
            val stepMs = (holdMs / (tickCount - 1).coerceAtLeast(1)).coerceAtLeast(1_000L)

            val isUpwardTrigger = activeRule.ruleType == SentinelRuleType.SHORT_STOP_LOSS ||
                (activeRule.ruleType == SentinelRuleType.TAKE_PROFIT && !activeRule.isShortPosition)

            var finalQuote = LiveEquityQuote(symbol = activeRule.symbol)
            for (i in 0 until tickCount) {
                val ts = baseTime + i * stepMs
                val bid = if (isUpwardTrigger) {
                    trigger + 0.35 + i * 0.05
                } else {
                    (trigger - 0.35 - i * 0.05).coerceAtLeast(0.50)
                }
                val ask = bid + 0.08
                finalQuote = LiveEquityQuote(
                    symbol = activeRule.symbol,
                    bidPrice = bid,
                    askPrice = ask,
                    lastPrice = bid,
                    securityStatus = "Normal",
                    receivedAtEpochMs = ts
                )
                streamerClient.injectSimulatedQuote(finalQuote)
                if (i < tickCount - 1) {
                    evaluateRuleOnTick(activeRule, finalQuote, MarketSessionWindow.AFTER_HOURS, ts, scope)
                } else {
                    // Run final confirmed evaluation synchronously to return direct outcome feedback
                    val eval = antiStopHuntEvaluator.evaluateTick(
                        rule = activeRule,
                        quote = finalQuote,
                        session = MarketSessionWindow.AFTER_HOURS,
                        tracker = trackers[activeRule.id] ?: RuleEvaluationTracker(),
                        nowEpochMs = ts
                    )
                    trackers[activeRule.id] = eval.tracker
                    _ruleTelemetry.value = _ruleTelemetry.value + (
                        activeRule.id to RuleRuntimeTelemetry(
                            ruleId = activeRule.id,
                            outcome = eval.outcome,
                            elapsedHoldSeconds = eval.elapsedSeconds,
                            consecutiveTicks = eval.tracker.consecutiveBreachTicks,
                            bid = finalQuote.bidPrice,
                            ask = finalQuote.askPrice,
                            spreadPct = eval.spreadPct,
                            detailMessage = eval.detailMessage
                        )
                    )
                    if (eval.outcome == MicrostructureEvalOutcome.CONFIRMED_EXECUTE) {
                        _activeExecutionCount.update { it + 1 }
                        val outcome = try {
                            executionStateMachine.executeTriggeredRule(
                                rule = activeRule,
                                quote = finalQuote,
                                session = MarketSessionWindow.AFTER_HOURS,
                                allowSessionOverride = true
                            ).also { out ->
                                handleExecutionOutcome(activeRule, out)
                            }
                        } finally {
                            _activeExecutionCount.update { (it - 1).coerceAtLeast(0) }
                        }
                        return@runShadowSimulation when (outcome) {
                            is SentinelExecutionOutcome.Filled -> outcome.message
                            is SentinelExecutionOutcome.AbortedNoPosition -> outcome.reason
                            is SentinelExecutionOutcome.UnfilledEscalated -> outcome.message
                            is SentinelExecutionOutcome.Failed -> outcome.reason
                            else -> "Confirmed PM break processed for ${activeRule.symbol}."
                        }
                    }
                }
            }
            "Confirmed PM break simulated for ${activeRule.symbol}."
        }

    suspend fun simulateOvernightAlert(rule: SentinelRule, scope: CoroutineScope): Result<String> =
        runShadowSimulation(rule) { activeRule, trigger, baseTime ->
            clearTracker(activeRule.id)
            val isShortSide = activeRule.ruleType == SentinelRuleType.SHORT_STOP_LOSS ||
                (activeRule.ruleType == SentinelRuleType.TAKE_PROFIT && activeRule.isShortPosition)
            val bid = if (isShortSide) trigger + 0.90 else (trigger - 0.90).coerceAtLeast(0.50)
            val ask = bid + 0.20

            val overnightQuote = LiveEquityQuote(
                symbol = activeRule.symbol,
                bidPrice = bid,
                askPrice = ask,
                lastPrice = bid,
                markPrice = bid,
                securityStatus = "Closed",
                receivedAtEpochMs = baseTime
            )
            streamerClient.injectSimulatedQuote(overnightQuote)
            evaluateRuleOnTick(activeRule, overnightQuote, MarketSessionWindow.OVERNIGHT_EXTO, baseTime, scope)

            val payload = alertBuilder.buildOvernightAlert(activeRule, overnightQuote)
            stagedOrderHolder.stageDraft(payload.stagedOrderDraft)
            "Overnight EXTO alert fired on ${activeRule.symbol}: ZERO order submitted; staged 7:00 AM SEAMLESS LIMIT in Order Ticket."
        }

    private suspend fun runShadowSimulation(
        rule: SentinelRule,
        block: suspend (activeRule: SentinelRule, trigger: Double, baseTime: Long) -> String
    ): Result<String> {
        if (preferences.isLiveMode.first()) {
            return Result.failure(
                IllegalStateException("Tick Replay Simulator is strictly disabled in LIVE mode.")
            )
        }
        if (rule.executionMode != ExecutionMode.SHADOW) {
            return Result.failure(
                IllegalStateException(
                    "Tick Replay Simulator only allows SHADOW-mode rules (blocked ${rule.executionMode.name} rule #${rule.id})."
                )
            )
        }
        val activeRule = if (rule.status != SentinelRuleStatus.ARMED) {
            sentinelRepository.updateRuleStatusWithNote(rule.id, SentinelRuleStatus.ARMED, null)
            rule.copy(status = SentinelRuleStatus.ARMED, lastTriggerNote = null)
        } else {
            rule
        }
        val trigger = activeRule.triggerPrice.toDouble().coerceAtLeast(1.0)
        val baseTime = System.currentTimeMillis()
        return runCatching { block(activeRule, trigger, baseTime) }
    }

    companion object {
        const val ORPHANED_EXECUTING_NOTE = "Interrupted mid-exit — verify position/orders"
        private const val EVENT_HANDLING_ACK_TIMEOUT_MS = 2_000L
    }
}
