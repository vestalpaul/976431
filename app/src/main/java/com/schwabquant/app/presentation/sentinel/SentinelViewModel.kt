package com.schwabquant.app.presentation.sentinel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schwabquant.app.core.security.DeepLinkIntentSanitizer
import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.data.remote.streamer.LiveEquityQuote
import com.schwabquant.app.data.remote.streamer.SchwabStreamerClient
import com.schwabquant.app.data.remote.streamer.StreamerConnectionState
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.Position
import com.schwabquant.app.domain.model.RuleRuntimeTelemetry
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleStatus
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.domain.model.SentinelServiceStatus
import com.schwabquant.app.domain.model.StagedOrderHolder
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.model.WorkingOrder
import com.schwabquant.app.domain.repository.OrderRepository
import com.schwabquant.app.domain.repository.PortfolioRepository
import com.schwabquant.app.domain.repository.SentinelRepository
import com.schwabquant.app.domain.usecase.MarketSessionResolver
import com.schwabquant.app.domain.usecase.MarketSessionWindow
import com.schwabquant.app.domain.usecase.OvernightAlertBuilder
import com.schwabquant.app.service.SentinelForegroundService
import com.schwabquant.app.service.SentinelRuntimeCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.floor

data class NewSentinelRuleInput(
    val symbol: String,
    val ruleType: SentinelRuleType,
    val triggerPrice: String,
    val quantity: String,
    val slippageCollarPct: String = "0.35",
    val holdSeconds: String = "15",
    val minConsecutiveTicks: String = "3",
    val maxSpreadPct: String = "1.5",
    val sweepBufferPct: String = "0.0",
    val antiKnifeLookbackMinutes: String = "10",
    val antiKnifeBouncePct: String = "0.25",
    val isShortPosition: Boolean = false
)

data class SentinelUiState(
    val executionMode: ExecutionMode = ExecutionMode.SHADOW,
    val serviceStatus: SentinelServiceStatus = SentinelServiceStatus(),
    val connectionState: StreamerConnectionState = StreamerConnectionState.DISCONNECTED,
    val activeSession: MarketSessionWindow = MarketSessionWindow.OVERNIGHT_EXTO,
    val heartbeatAgeSeconds: Long? = null,
    val liveQuotes: Map<String, LiveEquityQuote> = emptyMap(),
    val rules: List<SentinelRule> = emptyList(),
    val ruleTelemetry: Map<Long, RuleRuntimeTelemetry> = emptyMap(),
    val heldEquityPositions: List<Position> = emptyList(),
    val workingOrders: List<WorkingOrder> = emptyList(),
    val auditLogs: List<AuditLogEntity> = emptyList(),
    val lastFeedback: String? = null,
    val isErrorFeedback: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SentinelViewModel @Inject constructor(
    private val preferences: AppPreferencesDataStore,
    private val sentinelRepository: SentinelRepository,
    private val streamerClient: SchwabStreamerClient,
    private val runtimeCoordinator: SentinelRuntimeCoordinator,
    private val marketSessionResolver: MarketSessionResolver,
    private val alertBuilder: OvernightAlertBuilder,
    private val orderRepository: OrderRepository,
    private val portfolioRepository: PortfolioRepository,
    private val stagedOrderHolder: StagedOrderHolder
) : ViewModel() {

    private val feedbackState = MutableStateFlow<Pair<String, Boolean>?>(null)

    private val clockTickFlow = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000L)
        }
    }

    private val heldPositionsFlow = preferences.executionMode.flatMapLatest { mode ->
        portfolioRepository.observePositions(mode)
    }

    private val statusBundleFlow = combine(
        preferences.executionMode,
        runtimeCoordinator.serviceStatus,
        streamerClient.connectionState,
        streamerClient.lastHeartbeatEpochMs,
        clockTickFlow
    ) { mode, status, conn, heartbeatMs, nowMs ->
        val ageSec = if (heartbeatMs > 0L) ((nowMs - heartbeatMs) / 1_000L).coerceAtLeast(0L) else null
        StatusBundle(
            mode = mode,
            serviceStatus = status,
            connectionState = conn,
            activeSession = marketSessionResolver.resolve(),
            heartbeatAgeSeconds = ageSec
        )
    }

    private val rulesAndQuotesFlow = combine(
        sentinelRepository.observeRules(),
        runtimeCoordinator.ruleTelemetry,
        streamerClient.liveQuotesSnapshot,
        heldPositionsFlow
    ) { rules, telemetry, quotes, positions ->
        RulesAndQuotesBundle(
            rules = rules,
            telemetry = telemetry,
            quotes = quotes,
            heldEquityPositions = positions.filter { it.category == AssetCategory.EQUITY }
        )
    }

    val uiState: StateFlow<SentinelUiState> = combine(
        statusBundleFlow,
        rulesAndQuotesFlow,
        orderRepository.observeWorkingOrders(),
        orderRepository.observeRecentAuditLogs(25),
        feedbackState
    ) { statusBundle, rqBundle, orders, logs, feedback ->
        SentinelUiState(
            executionMode = statusBundle.mode,
            serviceStatus = statusBundle.serviceStatus,
            connectionState = statusBundle.connectionState,
            activeSession = statusBundle.activeSession,
            heartbeatAgeSeconds = statusBundle.heartbeatAgeSeconds,
            liveQuotes = rqBundle.quotes,
            rules = rqBundle.rules,
            ruleTelemetry = rqBundle.telemetry,
            heldEquityPositions = rqBundle.heldEquityPositions,
            workingOrders = orders,
            auditLogs = logs,
            lastFeedback = feedback?.first,
            isErrorFeedback = feedback?.second ?: false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SentinelUiState(activeSession = marketSessionResolver.resolve())
    )

    fun startSentinel(context: Context) {
        SentinelForegroundService.startSafely(context)
            .onSuccess {
                setFeedback("24/5 Sentinel Foreground Service started.", isError = false)
            }
            .onFailure { err ->
                setFeedback(err.message ?: "Failed to start Sentinel service.", isError = true)
            }
    }

    fun stopSentinel(context: Context) {
        SentinelForegroundService.stopSafely(context)
        setFeedback("24/5 Sentinel stopped.", isError = false)
    }

    fun disarmAllRules(context: Context) {
        viewModelScope.launch {
            sentinelRepository.disarmAllRules()
            runtimeCoordinator.clearAllTrackers()
            sentinelRepository.recordAuditEvent(
                category = "SENTINEL_DISARM_ALL",
                severity = "WARNING",
                symbol = null,
                executionMode = uiState.value.executionMode.name,
                message = "Disarmed all active Sentinel rules from Sentinel Command Center."
            )
            SentinelForegroundService.stopSafely(context)
            setFeedback("All armed Sentinel rules disarmed.", isError = false)
        }
    }

    fun addRule(input: NewSentinelRuleInput, context: Context): Boolean {
        val cleanSym = SchwabSymbolNormalizer.toDisplaySymbol(input.symbol)
        if (cleanSym.isBlank()) {
            setFeedback("Enter a valid equity symbol (e.g., GOOG, SPY, BRK.B).", isError = true)
            return false
        }
        val triggerBd = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(input.triggerPrice)
        if (triggerBd == null) {
            setFeedback("Enter a valid positive trigger price (>= 0.01).", isError = true)
            return false
        }
        val qty = input.quantity.trim().toIntOrNull()
        if (qty == null || qty <= 0) {
            setFeedback("Enter a valid share quantity (>= 1).", isError = true)
            return false
        }

        val collar = input.slippageCollarPct.trim().toDoubleOrNull()?.coerceIn(0.0, 10.0) ?: 0.35
        val holdSec = input.holdSeconds.trim().toIntOrNull()?.coerceIn(0, 300) ?: 15
        val minTicks = input.minConsecutiveTicks.trim().toIntOrNull()?.coerceIn(1, 20) ?: 3
        val maxSpread = input.maxSpreadPct.trim().toDoubleOrNull()?.coerceIn(0.1, 25.0) ?: 1.5
        val sweepBuf = input.sweepBufferPct.trim().toDoubleOrNull()?.coerceIn(0.0, 15.0) ?: 0.0
        val lookback = input.antiKnifeLookbackMinutes.trim().toIntOrNull()?.coerceIn(1, 120) ?: 10
        val bounce = input.antiKnifeBouncePct.trim().toDoubleOrNull()?.coerceIn(0.05, 10.0) ?: 0.25

        val rule = SentinelRule(
            symbol = cleanSym,
            category = AssetCategory.EQUITY,
            ruleType = input.ruleType,
            triggerPrice = triggerBd,
            quantity = qty,
            slippageCollarPct = collar,
            holdSeconds = holdSec,
            minConsecutiveTicks = minTicks,
            maxSpreadPct = maxSpread,
            sweepBufferPct = sweepBuf,
            antiKnifeLookbackMinutes = lookback,
            antiKnifeBouncePct = bounce,
            isShortPosition = input.isShortPosition || input.ruleType == SentinelRuleType.SHORT_STOP_LOSS,
            session = TradingSession.SEAMLESS,
            status = SentinelRuleStatus.ARMED,
            executionMode = uiState.value.executionMode
        )

        viewModelScope.launch {
            val id = sentinelRepository.upsertRule(rule)
            sentinelRepository.recordAuditEvent(
                category = "SENTINEL_RULE_ARMED",
                severity = "INFO",
                symbol = cleanSym,
                executionMode = uiState.value.executionMode.name,
                message = "Armed #${id} ${rule.ruleType.displayName} on $cleanSym x$qty @ $${rule.triggerPrice.toPlainString()} (collar ${collar}%, hold ${holdSec}s/${minTicks}t)."
            )
            if (!uiState.value.serviceStatus.isServiceRunning) {
                SentinelForegroundService.startSafely(context)
                    .onSuccess {
                        setFeedback("Armed ${rule.ruleType.shortTag} on $cleanSym & started Sentinel.", isError = false)
                    }
                    .onFailure { err ->
                        setFeedback("Rule saved, but service launch blocked: ${err.message}", isError = true)
                    }
            } else {
                setFeedback("Armed ${rule.ruleType.shortTag} on $cleanSym @ $${rule.triggerPrice.toPlainString()}.", isError = false)
            }
        }
        return true
    }

    fun toggleRuleArmed(rule: SentinelRule, context: Context) {
        viewModelScope.launch {
            val activeMode = preferences.executionMode.first()
            if (rule.executionMode != activeMode) {
                setFeedback(
                    "Switch to ${rule.executionMode.name} Mode before modifying ${rule.symbol} (${rule.ruleType.shortTag}).",
                    isError = true
                )
                return@launch
            }
            if (rule.status == SentinelRuleStatus.EXECUTING) {
                setFeedback(
                    "Cannot arm or pause ${rule.symbol} (${rule.ruleType.shortTag}) while execution is in flight.",
                    isError = true
                )
                return@launch
            }
            val shouldArm = rule.status != SentinelRuleStatus.ARMED
            if (shouldArm) {
                runtimeCoordinator.clearTracker(rule.id)
                sentinelRepository.updateRuleStatusWithNote(rule.id, SentinelRuleStatus.ARMED, null)
                if (!uiState.value.serviceStatus.isServiceRunning) {
                    SentinelForegroundService.startSafely(context)
                        .onFailure { err ->
                            setFeedback("Rule armed, but service start failed: ${err.message}", isError = true)
                            return@launch
                        }
                }
                setFeedback("Re-armed ${rule.symbol} (${rule.ruleType.shortTag}).", isError = false)
            } else {
                runtimeCoordinator.clearTracker(rule.id)
                sentinelRepository.updateRuleStatusWithNote(rule.id, SentinelRuleStatus.PAUSED, "Paused by user")
                setFeedback("Paused ${rule.symbol} (${rule.ruleType.shortTag}).", isError = false)
            }
        }
    }

    fun deleteRule(ruleId: Long) {
        viewModelScope.launch {
            runtimeCoordinator.clearTracker(ruleId)
            sentinelRepository.deleteRule(ruleId)
            setFeedback("Deleted Sentinel rule #$ruleId.", isError = false)
        }
    }

    fun stageOrderForRule(rule: SentinelRule) {
        val activeMode = uiState.value.executionMode
        if (rule.executionMode != activeMode) {
            setFeedback(
                "Switch to ${rule.executionMode.name} Mode to stage this rule.",
                isError = true
            )
            return
        }
        if (rule.status == SentinelRuleStatus.UNFILLED_ESCALATED) {
            stageEscalatedExitReprice(rule)
            return
        }
        val quote = uiState.value.liveQuotes[rule.symbol]
        val draft = when {
            rule.ruleType == SentinelRuleType.DIP_BUY_ANTI_KNIFE && quote != null ->
                alertBuilder.buildDipBuyStagingPayload(rule, quote).stagedOrderDraft
            else ->
                alertBuilder.buildOvernightAlert(
                    rule,
                    quote ?: LiveEquityQuote(
                        symbol = rule.symbol,
                        bidPrice = rule.triggerPrice.toDouble(),
                        askPrice = rule.triggerPrice.toDouble()
                    )
                ).stagedOrderDraft
        }
        stagedOrderHolder.stageDraft(draft)
    }

    fun stageEscalatedExitReprice(rule: SentinelRule) {
        val activeMode = uiState.value.executionMode
        if (rule.executionMode != activeMode) {
            setFeedback(
                "Switch to ${rule.executionMode.name} Mode to re-price this rule.",
                isError = true
            )
            return
        }
        val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(rule.symbol)
        val quote = uiState.value.liveQuotes[displaySym] ?: uiState.value.liveQuotes[rule.symbol]
        val expectsShortCover = rule.ruleType == SentinelRuleType.SHORT_STOP_LOSS ||
            (rule.ruleType == SentinelRuleType.TAKE_PROFIT && rule.isShortPosition)
        val netPosQty = uiState.value.heldEquityPositions
            .filter { SchwabSymbolNormalizer.toDisplaySymbol(it.symbol) == displaySym }
            .sumOf { it.quantity.toDouble() }
        val heldWholeShares = if (expectsShortCover) {
            if (netPosQty < 0.0) floor(abs(netPosQty)).toInt() else null
        } else {
            if (netPosQty > 0.0) floor(netPosQty).toInt() else null
        }
        val draft = alertBuilder.buildUnfilledRepriceDraft(
            rule = rule,
            quote = quote,
            priorOrderId = rule.lastSubmittedOrderId ?: "ESCALATED",
            heldPositionWholeShares = heldWholeShares
        )
        stagedOrderHolder.stageDraft(draft)
    }

    // -------------------------------------------------------------------------
    // Shadow Mode Tick Replay Simulator Actions (Disabled in LIVE Mode)
    // -------------------------------------------------------------------------

    fun simulateStopHuntWick(rule: SentinelRule) {
        viewModelScope.launch {
            runtimeCoordinator.simulateStopHuntWick(rule, viewModelScope)
                .onSuccess { setFeedback(it, isError = false) }
                .onFailure { setFeedback(it.message ?: "Simulation blocked.", isError = true) }
        }
    }

    fun simulateSpreadBlowout(rule: SentinelRule) {
        viewModelScope.launch {
            runtimeCoordinator.simulateSpreadBlowout(rule, viewModelScope)
                .onSuccess { setFeedback(it, isError = false) }
                .onFailure { setFeedback(it.message ?: "Simulation blocked.", isError = true) }
        }
    }

    fun simulateConfirmedPmBreak(rule: SentinelRule) {
        viewModelScope.launch {
            runtimeCoordinator.simulateConfirmedPmBreak(rule, viewModelScope)
                .onSuccess {
                    portfolioRepository.refreshShadowPortfolio()
                    setFeedback(it, isError = false)
                }
                .onFailure { setFeedback(it.message ?: "Simulation blocked.", isError = true) }
        }
    }

    fun simulateOvernightAlert(rule: SentinelRule) {
        viewModelScope.launch {
            runtimeCoordinator.simulateOvernightAlert(rule, viewModelScope)
                .onSuccess { setFeedback(it, isError = false) }
                .onFailure { setFeedback(it.message ?: "Simulation blocked.", isError = true) }
        }
    }

    fun openOrderSheet() {
        stagedOrderHolder.stageBlankOrder()
    }

    fun refreshWorkingOrders() {
        viewModelScope.launch {
            orderRepository.getWorkingOrders(forceRefresh = true)
                .onFailure { err -> setFeedback(err.message ?: "Refresh failed.", isError = true) }
        }
    }

    fun cancelWorkingOrder(orderId: String) {
        viewModelScope.launch {
            val targetOrder = uiState.value.workingOrders.firstOrNull { it.orderId == orderId }
            val mode = targetOrder?.executionMode ?: uiState.value.executionMode
            orderRepository.cancelOrder("", orderId, mode)
                .onSuccess { setFeedback("Canceled order $orderId.", isError = false) }
                .onFailure { err -> setFeedback(err.message ?: "Cancel failed.", isError = true) }
        }
    }

    fun simulateShadowFill(orderId: String) {
        viewModelScope.launch {
            orderRepository.simulateShadowFill(orderId)
                .onSuccess { res ->
                    portfolioRepository.refreshShadowPortfolio()
                    setFeedback(res.message, isError = false)
                }
                .onFailure { err -> setFeedback(err.message ?: "Simulated fill failed.", isError = true) }
        }
    }

    fun clearFeedback() {
        feedbackState.value = null
    }

    private fun setFeedback(message: String, isError: Boolean) {
        feedbackState.value = message to isError
    }

    private data class StatusBundle(
        val mode: ExecutionMode,
        val serviceStatus: SentinelServiceStatus,
        val connectionState: StreamerConnectionState,
        val activeSession: MarketSessionWindow,
        val heartbeatAgeSeconds: Long?
    )

    private data class RulesAndQuotesBundle(
        val rules: List<SentinelRule>,
        val telemetry: Map<Long, RuleRuntimeTelemetry>,
        val quotes: Map<String, LiveEquityQuote>,
        val heldEquityPositions: List<Position>
    )
}
