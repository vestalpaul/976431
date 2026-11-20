package com.schwabquant.app.domain.usecase

import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.data.remote.streamer.AccountActivityEvent
import com.schwabquant.app.data.remote.streamer.LiveEquityQuote
import com.schwabquant.app.data.remote.streamer.SchwabStreamerClient
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStatusSnapshot
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.PortfolioSnapshot
import com.schwabquant.app.domain.model.SchwabOrderStatuses
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleStatus
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.repository.OrderRepository
import com.schwabquant.app.domain.repository.PortfolioRepository
import com.schwabquant.app.domain.repository.SentinelRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.floor

sealed interface SentinelExecutionOutcome {
    data class Filled(
        val ruleId: Long,
        val orderId: String,
        val fillPrice: BigDecimal?,
        val executionMode: ExecutionMode,
        val message: String
    ) : SentinelExecutionOutcome

    data class UnfilledEscalated(
        val ruleId: Long,
        val orderId: String,
        val repriceDraft: OrderDraft,
        val message: String
    ) : SentinelExecutionOutcome

    data class AbortedNoPosition(
        val ruleId: Long,
        val symbol: String,
        val openQuantity: Double,
        val reason: String
    ) : SentinelExecutionOutcome

    data class StagedAlertOnly(
        val ruleId: Long,
        val payload: OvernightAlertPayload,
        val reason: String
    ) : SentinelExecutionOutcome

    data class RejectedOvernight(
        val ruleId: Long,
        val reason: String
    ) : SentinelExecutionOutcome

    data class AlreadyInProgress(val ruleId: Long) : SentinelExecutionOutcome

    data class Failed(
        val ruleId: Long,
        val reason: String
    ) : SentinelExecutionOutcome
}

/**
 * 6-Step Double-Sell-Proof & Accidental-Short-Proof Sentinel Exit Execution State Machine:
 * - C-1: Enforces `rule.executionMode == effectiveMode` at entry AND re-verifies immediately before Step 5 submit.
 * - C-3: Cancels conflicting equity-only working orders INCLUDING live OCO brackets via `legSymbols`/`hasEquityLegs`,
 *   while preserving open `OPTION` orders; fail-closed live portfolio refresh.
 * - H-1: Thread-safe `AtomicReference<FillProgressSnapshot>` + strict REST fill confirmation (`FILLED` & `remainingQuantity == null || <= 0`),
 *   detecting `LeavesQuantity`/`leavesQuantity` > 0 partial fills.
 * - H-2: Normalizes share-class equity symbols (`BRK.B` -> `BRK/B`) on live Schwab legs.
 * - M-3: Re-checks `MarketSessionResolver` before Step 5 (`20:00:00 ET` boundary abort) & uses freshest valid quote for collar pricing.
 */
@Singleton
class SentinelExecutionStateMachine(
    private val sentinelRepository: SentinelRepository,
    private val orderRepository: OrderRepository,
    private val portfolioRepository: PortfolioRepository,
    private val alertBuilder: OvernightAlertBuilder,
    private val isLiveModeProvider: suspend () -> Boolean,
    private val accountHashProvider: () -> String?,
    private val accountEventsFlow: Flow<AccountActivityEvent>,
    private val settlementDelayMs: Long = DEFAULT_SETTLEMENT_DELAY_MS,
    private val fillMonitorTimeoutMs: Long = DEFAULT_FILL_MONITOR_TIMEOUT_MS,
    private val fillPollIntervalMs: Long = DEFAULT_FILL_POLL_INTERVAL_MS,
    private val sessionRevalidator: (() -> MarketSessionWindow)? = null,
    private val latestQuoteProvider: (String) -> LiveEquityQuote? = { null }
) {

    @Inject
    constructor(
        sentinelRepository: SentinelRepository,
        orderRepository: OrderRepository,
        portfolioRepository: PortfolioRepository,
        alertBuilder: OvernightAlertBuilder,
        preferences: AppPreferencesDataStore,
        credentialVault: CredentialVault,
        streamerClient: SchwabStreamerClient,
        marketSessionResolver: MarketSessionResolver
    ) : this(
        sentinelRepository = sentinelRepository,
        orderRepository = orderRepository,
        portfolioRepository = portfolioRepository,
        alertBuilder = alertBuilder,
        isLiveModeProvider = { preferences.isLiveMode.first() },
        accountHashProvider = { credentialVault.getPrimaryAccountHash() },
        accountEventsFlow = streamerClient.accountEvents,
        settlementDelayMs = DEFAULT_SETTLEMENT_DELAY_MS,
        fillMonitorTimeoutMs = DEFAULT_FILL_MONITOR_TIMEOUT_MS,
        fillPollIntervalMs = DEFAULT_FILL_POLL_INTERVAL_MS,
        sessionRevalidator = { marketSessionResolver.resolveCurrentSession() },
        latestQuoteProvider = { sym -> streamerClient.liveQuotesSnapshot.value[sym] }
    )

    private val symbolMutexes = ConcurrentHashMap<String, Mutex>()
    private val inFlightRuleIds = ConcurrentHashMap.newKeySet<Long>()

    fun getActiveRuleIds(): Set<Long> = inFlightRuleIds.toSet()

    private data class FillProgressSnapshot(
        val filledQuantity: Double,
        val remainingQuantity: Double,
        val status: String
    )

    private data class FillMonitorOutcome(
        val isFullyFilled: Boolean,
        val filledQuantity: Double = 0.0,
        val remainingQuantity: Double = 0.0,
        val lastStatus: String = "WORKING"
    )

    private sealed interface PositionCheckOutcome {
        data class Verified(
            val openQty: Double,
            val availableWholeShares: Int,
            val expectsLongPosition: Boolean
        ) : PositionCheckOutcome

        data class Aborted(val outcome: SentinelExecutionOutcome.AbortedNoPosition) : PositionCheckOutcome
    }

    suspend fun executeTriggeredRule(
        rule: SentinelRule,
        quote: LiveEquityQuote,
        session: MarketSessionWindow,
        simulateImmediateShadowFill: Boolean = true,
        allowSessionOverride: Boolean = false
    ): SentinelExecutionOutcome {
        val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(rule.symbol)
        val isLive = isLiveModeProvider()
        val effectiveMode = if (isLive) ExecutionMode.LIVE else ExecutionMode.SHADOW

        // C-1/C-5 Safeguard: Strict Shadow-to-Live / Live-to-Shadow mode isolation
        if (rule.executionMode != effectiveMode) {
            val reason = "Blocked rule #${rule.id} on $displaySym: rule mode (${rule.executionMode.name}) " +
                "does not match active execution mode (${effectiveMode.name})."
            sentinelRepository.recordAuditEvent(
                category = "SENTINEL_MODE_MISMATCH_BLOCKED",
                severity = "WARNING",
                symbol = displaySym,
                executionMode = effectiveMode.name,
                message = reason
            )
            return SentinelExecutionOutcome.Failed(rule.id, reason)
        }

        // Safeguard #2: DIP_BUY_ANTI_KNIFE and OVERNIGHT_PRICE_ALERT NEVER auto-submit orders
        if (rule.ruleType == SentinelRuleType.DIP_BUY_ANTI_KNIFE) {
            if (!inFlightRuleIds.add(rule.id)) {
                return SentinelExecutionOutcome.AlreadyInProgress(rule.id)
            }
            try {
                val payload = if (session == MarketSessionWindow.OVERNIGHT_EXTO) {
                    alertBuilder.buildOvernightAlert(rule, quote)
                } else {
                    alertBuilder.buildDipBuyStagingPayload(rule, quote)
                }
                val note = "Anti-Knife stabilized @ $${quote.bidPrice ?: quote.markPrice ?: rule.triggerPrice} — awaiting biometric buy confirmation."
                sentinelRepository.updateRuleStatusWithNote(rule.id, SentinelRuleStatus.ALERTED, note)
                sentinelRepository.recordAuditEvent(
                    category = "SENTINEL_DIP_BUY_ALERTED",
                    severity = "INFO",
                    symbol = displaySym,
                    executionMode = effectiveMode.name,
                    message = note
                )
                return SentinelExecutionOutcome.StagedAlertOnly(rule.id, payload, note)
            } finally {
                inFlightRuleIds.remove(rule.id)
            }
        }

        if (rule.ruleType == SentinelRuleType.OVERNIGHT_PRICE_ALERT) {
            if (!inFlightRuleIds.add(rule.id)) {
                return SentinelExecutionOutcome.AlreadyInProgress(rule.id)
            }
            try {
                val payload = alertBuilder.buildOvernightAlert(rule, quote)
                val note = "Overnight price alert triggered — staged 7:00 AM review (zero API order)."
                sentinelRepository.updateRuleStatusWithNote(rule.id, SentinelRuleStatus.ALERTED, note)
                sentinelRepository.recordAuditEvent(
                    category = "SENTINEL_OVERNIGHT_ALERT",
                    severity = "INFO",
                    symbol = displaySym,
                    executionMode = effectiveMode.name,
                    message = note
                )
                return SentinelExecutionOutcome.StagedAlertOnly(rule.id, payload, note)
            } finally {
                inFlightRuleIds.remove(rule.id)
            }
        }

        // Overnight EXTO safety rail: ZERO automated order submissions outside 07:00–19:59:59 ET
        if (!session.allowsAutomatedExecution) {
            val reason = "Automated execution blocked during ${session.label} — alert-only window."
            sentinelRepository.recordAuditEvent(
                category = "SENTINEL_OVERNIGHT_EXEC_BLOCKED",
                severity = "WARNING",
                symbol = displaySym,
                executionMode = effectiveMode.name,
                message = reason
            )
            return SentinelExecutionOutcome.RejectedOvernight(rule.id, reason)
        }

        if (!inFlightRuleIds.add(rule.id)) {
            return SentinelExecutionOutcome.AlreadyInProgress(rule.id)
        }

        val symbolMutex = symbolMutexes.computeIfAbsent(displaySym) { Mutex() }
        try {
            return symbolMutex.withLock {
                // Re-check persisted rule state under per-symbol lock; abort if deleted (null) or not ARMED
                val latestRule = sentinelRepository.getRuleById(rule.id)
                if (latestRule == null || latestRule.status != SentinelRuleStatus.ARMED) {
                    return@withLock SentinelExecutionOutcome.AlreadyInProgress(rule.id)
                }

                val currentIsLive = isLiveModeProvider()
                val currentMode = if (currentIsLive) ExecutionMode.LIVE else ExecutionMode.SHADOW
                if (latestRule.executionMode != currentMode) {
                    val reason = "Blocked rule #${rule.id} on $displaySym: rule mode (${latestRule.executionMode.name}) " +
                        "does not match active execution mode (${currentMode.name})."
                    sentinelRepository.recordAuditEvent(
                        category = "SENTINEL_MODE_MISMATCH_BLOCKED",
                        severity = "WARNING",
                        symbol = displaySym,
                        executionMode = currentMode.name,
                        message = reason
                    )
                    return@withLock SentinelExecutionOutcome.Failed(rule.id, reason)
                }

                val accountHash = if (currentIsLive) {
                    accountHashProvider()?.takeIf { it.isNotBlank() }
                        ?: return@withLock failRule(rule.id, displaySym, currentMode, "Missing Schwab primary account hash in vault.")
                } else {
                    SHADOW_ACCOUNT_HASH
                }

                // Persist EXECUTING immediately (recoverable by Safeguard #5 if OS kills process mid-flight)
                sentinelRepository.updateRuleStatusWithNote(
                    ruleId = rule.id,
                    status = SentinelRuleStatus.EXECUTING,
                    note = "Step 1/6: Verifying directional position on $displaySym..."
                )

                // STEP 1: Fail-closed pre-check of directional equity position BEFORE touching working orders
                val initialSnapshot = fetchPortfolioFailClosed(
                    isLive = currentIsLive,
                    accountHash = accountHash,
                    rule = rule,
                    quote = quote,
                    displaySym = displaySym,
                    mode = currentMode
                ) ?: return@withLock buildRefreshFailedEscalation(rule, quote, displaySym)

                when (val prePos = verifyDirectionalPosition(rule, displaySym, currentMode, initialSnapshot)) {
                    is PositionCheckOutcome.Aborted -> return@withLock prePos.outcome
                    is PositionCheckOutcome.Verified -> Unit
                }

                // STEP 2 (C-3): Query working orders FAIL-CLOSED & filter strictly to EQUITY orders (incl. OCO brackets) on displaySym
                val workingOrders = orderRepository.getWorkingOrders(forceRefresh = true).getOrElse { err ->
                    val reason = "Aborted: working orders check failed (${err.message ?: "network error"}) — verify manually."
                    return@withLock escalatePreSubmitFailure(
                        rule = rule,
                        quote = quote,
                        displaySym = displaySym,
                        mode = currentMode,
                        auditCategory = "SENTINEL_CONFLICT_CHECK_FAILED",
                        reason = reason
                    )
                }

                val conflicting = workingOrders.filter { wo ->
                    val isEquityOrder = !wo.hasOptionLegs &&
                        wo.hasEquityLegs &&
                        (wo.assetType.equals(AssetCategory.EQUITY.name, ignoreCase = true) ||
                            wo.assetType.equals("OCO", ignoreCase = true)) &&
                        wo.strategyType.uppercase() !in MULTI_LEG_OPTION_STRATEGIES &&
                        !(wo.symbol.length == 21 && (wo.symbol[12] == 'C' || wo.symbol[12] == 'P'))
                    val orderSymbols = wo.legSymbols.ifEmpty { setOf(wo.symbol) }
                    val symMatch = orderSymbols.any { sym ->
                        SchwabSymbolNormalizer.toDisplaySymbol(sym) == displaySym
                    }
                    isEquityOrder && symMatch && wo.status.uppercase() in SchwabOrderStatuses.ACTIVE_WORKING_STATUSES
                }

                // STEP 3: Re-verify active mode (C-2) before cancelling conflicting equity orders fail-closed
                if (conflicting.isNotEmpty()) {
                    val liveBeforeCancel = isLiveModeProvider()
                    val modeBeforeCancel = if (liveBeforeCancel) ExecutionMode.LIVE else ExecutionMode.SHADOW
                    if (latestRule.executionMode != modeBeforeCancel || currentMode != modeBeforeCancel) {
                        val reason = "Blocked conflict cancel on $displaySym: execution mode changed " +
                            "(rule=${latestRule.executionMode.name}, active=${modeBeforeCancel.name})."
                        sentinelRepository.updateRuleStatusWithNote(rule.id, SentinelRuleStatus.PAUSED, reason)
                        sentinelRepository.recordAuditEvent(
                            category = "SENTINEL_MODE_MISMATCH_BLOCKED",
                            severity = "CRITICAL",
                            symbol = displaySym,
                            executionMode = modeBeforeCancel.name,
                            message = reason
                        )
                        return@withLock SentinelExecutionOutcome.Failed(rule.id, reason)
                    }
                    for (conflict in conflicting) {
                        orderRepository.cancelOrder(accountHash, conflict.orderId, currentMode)
                            .getOrElse { err ->
                                val reason = "Aborted: failed canceling conflicting equity order ${conflict.orderId} (${err.message ?: "error"}) — verify manually."
                                return@withLock escalatePreSubmitFailure(
                                    rule = rule,
                                    quote = quote,
                                    displaySym = displaySym,
                                    mode = currentMode,
                                    auditCategory = "SENTINEL_CONFLICT_CANCEL_FAILED",
                                    reason = reason
                                )
                            }
                    }

                    sentinelRepository.recordAuditEvent(
                        category = "SENTINEL_CONFLICT_CANCELED",
                        severity = "INFO",
                        symbol = displaySym,
                        executionMode = currentMode.name,
                        message = "Requested cancel of ${conflicting.size} conflicting equity working order(s) on $displaySym."
                    )

                    if (settlementDelayMs > 0L) {
                        delay(settlementDelayMs)
                    }

                    for (conflict in conflicting) {
                        val conflictStatus = orderRepository.getOrderStatus(accountHash, conflict.orderId)
                            .getOrElse { err ->
                                val reason = "Aborted: unable to verify cancellation of conflicting order ${conflict.orderId} (${err.message})."
                                return@withLock escalatePreSubmitFailure(
                                    rule = rule,
                                    quote = quote,
                                    displaySym = displaySym,
                                    mode = currentMode,
                                    auditCategory = "SENTINEL_CONFLICT_VERIFY_FAILED",
                                    reason = reason
                                )
                            }
                        if (!conflictStatus.isCanceledWithZeroFill) {
                            val reason = "Aborted: conflicting order ${conflict.orderId} remained '${conflictStatus.normalizedStatus}' " +
                                "(filled=${conflictStatus.filledQuantity}) after cancel."
                            return@withLock escalatePreSubmitFailure(
                                rule = rule,
                                quote = quote,
                                displaySym = displaySym,
                                mode = currentMode,
                                auditCategory = "SENTINEL_CONFLICT_NOT_CANCELED",
                                reason = reason
                            )
                        }
                    }
                }

                // STEP 4 (H-2): ALWAYS re-verify live/shadow directional equity position fail-closed right before Step 5
                val verifiedSnapshot = fetchPortfolioFailClosed(
                    isLive = currentIsLive,
                    accountHash = accountHash,
                    rule = rule,
                    quote = quote,
                    displaySym = displaySym,
                    mode = currentMode
                ) ?: return@withLock buildRefreshFailedEscalation(rule, quote, displaySym)

                val verifiedPos = when (val postPos = verifyDirectionalPosition(rule, displaySym, currentMode, verifiedSnapshot)) {
                    is PositionCheckOutcome.Aborted -> return@withLock postPos.outcome
                    is PositionCheckOutcome.Verified -> postPos
                }

                // C-1 + M-3 Safeguards (Pre-Step-5): Re-verify mode, wall-clock session boundary, & freshest valid quote
                val isLiveBeforeSubmit = isLiveModeProvider()
                val modeBeforeSubmit = if (isLiveBeforeSubmit) ExecutionMode.LIVE else ExecutionMode.SHADOW
                if (latestRule.executionMode != modeBeforeSubmit || currentMode != modeBeforeSubmit) {
                    val reason = "Aborted exit on $displaySym before Step 5 submit: execution mode changed " +
                        "(rule=${latestRule.executionMode.name}, active=${modeBeforeSubmit.name})."
                    sentinelRepository.updateRuleStatusWithNote(rule.id, SentinelRuleStatus.PAUSED, reason)
                    sentinelRepository.recordAuditEvent(
                        category = "SENTINEL_MODE_MISMATCH_BLOCKED",
                        severity = "CRITICAL",
                        symbol = displaySym,
                        executionMode = modeBeforeSubmit.name,
                        message = reason
                    )
                    return@withLock SentinelExecutionOutcome.Failed(rule.id, reason)
                }

                // Guard 4: Strictly ignore allowSessionOverride whenever Live Mode or Live rule
                val effectiveAllowOverride = allowSessionOverride &&
                    !isLiveBeforeSubmit &&
                    latestRule.executionMode == ExecutionMode.SHADOW
                val sessionBeforeSubmit = if (effectiveAllowOverride) {
                    session
                } else {
                    sessionRevalidator?.invoke() ?: session
                }
                if (!sessionBeforeSubmit.allowsAutomatedExecution) {
                    val overnightQuote = latestQuoteProvider(displaySym)?.takeIf { isValidExecutionQuote(it) } ?: quote
                    val payload = alertBuilder.buildOvernightAlert(latestRule, overnightQuote)
                    val reason = "Execution aborted before submit: clock entered ${sessionBeforeSubmit.label} — converted to overnight alert."
                    sentinelRepository.updateRuleStatusWithNote(rule.id, SentinelRuleStatus.ALERTED, reason)
                    sentinelRepository.recordAuditEvent(
                        category = "SENTINEL_SESSION_CROSSED_OVERNIGHT",
                        severity = "WARNING",
                        symbol = displaySym,
                        executionMode = modeBeforeSubmit.name,
                        message = reason
                    )
                    return@withLock SentinelExecutionOutcome.StagedAlertOnly(rule.id, payload, reason)
                }

                val exitQty = minOf(
                    rule.quantity.coerceAtLeast(1),
                    verifiedPos.availableWholeShares
                )
                val exitSide = if (verifiedPos.expectsLongPosition) OrderSide.SELL else OrderSide.BUY_TO_COVER

                // Pull freshest valid quote from live Streamer snapshot if newer/valid
                val freshCandidate = latestQuoteProvider(displaySym)
                val pricingQuote = if (freshCandidate != null &&
                    isValidExecutionQuote(freshCandidate) &&
                    (quote.receivedAtEpochMs == 0L || freshCandidate.receivedAtEpochMs >= quote.receivedAtEpochMs)
                ) {
                    freshCandidate
                } else {
                    quote
                }

                // STEP 5: Boundary-safe SEAMLESS LIMIT DAY collar-protected exit (Safeguard #3 + H-2 BRK/B)
                val refPrice = if (exitSide == OrderSide.SELL) {
                    pricingQuote.bidPrice ?: pricingQuote.markPrice ?: rule.triggerPrice.toDouble()
                } else {
                    pricingQuote.askPrice ?: pricingQuote.markPrice ?: rule.triggerPrice.toDouble()
                }.coerceAtLeast(0.01)

                val collarFraction = rule.slippageCollarPct.coerceAtLeast(0.0) / 100.0
                val rawLimit = if (exitSide == OrderSide.SELL) {
                    refPrice * (1.0 - collarFraction)
                } else {
                    refPrice * (1.0 + collarFraction)
                }.coerceAtLeast(0.01)
                val limitPrice = BigDecimal.valueOf(rawLimit).setScale(2, RoundingMode.HALF_UP)

                // Safeguard #3: Always use SEAMLESS + LIMIT + DAY across 07:00-19:59:59 ET
                val exitSession = sessionBeforeSubmit.exitOrderSession ?: TradingSession.SEAMLESS
                val outboundLegSymbol = if (isLiveBeforeSubmit) {
                    SchwabSymbolNormalizer.toApiSymbol(displaySym)
                } else {
                    displaySym
                }
                val ticket = OrderTicket(
                    accountHash = accountHash,
                    executionMode = modeBeforeSubmit,
                    orderType = OrderType.LIMIT,
                    session = exitSession,
                    duration = OrderDuration.DAY,
                    strategyType = "SINGLE",
                    limitPrice = limitPrice,
                    legs = listOf(
                        OrderLeg(
                            symbol = outboundLegSymbol,
                            category = AssetCategory.EQUITY,
                            side = exitSide,
                            quantity = exitQty,
                            underlyingSymbol = displaySym,
                            estimatedUnitPrice = limitPrice
                        )
                    )
                )

                val submitResult = orderRepository.submitOrder(
                    ticket = ticket,
                    risksAcknowledged = true,
                    overriddenRiskCodes = listOf("SENTINEL_PRE_ARMED_AUTOMATED_EXIT"),
                    simulateImmediateMidFill = !isLiveBeforeSubmit && simulateImmediateShadowFill
                ).getOrElse { err ->
                    return@withLock failRule(
                        rule.id,
                        displaySym,
                        modeBeforeSubmit,
                        err.message ?: "Exit order submission failed."
                    )
                }

                // Missing Location header / UNTRACKED order escalates immediately
                if (submitResult.status.equals("UNTRACKED", ignoreCase = true) ||
                    submitResult.orderId.isBlank()
                ) {
                    val untrackedMsg = submitResult.message.ifBlank {
                        "Live exit accepted (201 Created) without Location orderId — verify working orders in Schwab."
                    }
                    val repriceDraft = alertBuilder.buildUnfilledRepriceDraft(
                        rule = rule,
                        quote = pricingQuote,
                        priorOrderId = "UNTRACKED",
                        overrideQuantity = exitQty
                    )
                    sentinelRepository.updateRuleStatusWithNote(
                        ruleId = rule.id,
                        status = SentinelRuleStatus.UNFILLED_ESCALATED,
                        note = untrackedMsg,
                        lastSubmittedOrderId = null
                    )
                    sentinelRepository.recordAuditEvent(
                        category = "SENTINEL_EXIT_UNTRACKED_ESCALATED",
                        severity = "CRITICAL",
                        symbol = displaySym,
                        executionMode = modeBeforeSubmit.name,
                        message = untrackedMsg
                    )
                    return@withLock SentinelExecutionOutcome.UnfilledEscalated(
                        ruleId = rule.id,
                        orderId = "UNTRACKED",
                        repriceDraft = repriceDraft,
                        message = untrackedMsg
                    )
                }

                sentinelRepository.updateRuleStatusWithNote(
                    ruleId = rule.id,
                    status = SentinelRuleStatus.EXECUTING,
                    note = "Step 6/6: Monitoring exit order ${submitResult.orderId}...",
                    lastSubmittedOrderId = submitResult.orderId
                )

                if (submitResult.status.equals("FILLED", ignoreCase = true)) {
                    if (!isLiveBeforeSubmit) {
                        portfolioRepository.refreshShadowPortfolio()
                    }
                    val msg = "${modeBeforeSubmit.name} exit FILLED (${submitResult.orderId}): ${exitSide.name} ${exitQty}x $displaySym @ $${submitResult.fillPrice?.toPlainString() ?: limitPrice.toPlainString()} (SEAMLESS LIMIT)."
                    sentinelRepository.updateRuleStatusWithNote(
                        ruleId = rule.id,
                        status = SentinelRuleStatus.TRIGGERED_FILLED,
                        note = msg,
                        lastSubmittedOrderId = submitResult.orderId
                    )
                    sentinelRepository.recordAuditEvent(
                        category = "SENTINEL_EXIT_FILLED",
                        severity = "INFO",
                        symbol = displaySym,
                        executionMode = modeBeforeSubmit.name,
                        message = msg
                    )
                    return@withLock SentinelExecutionOutcome.Filled(
                        ruleId = rule.id,
                        orderId = submitResult.orderId,
                        fillPrice = submitResult.fillPrice ?: limitPrice,
                        executionMode = modeBeforeSubmit,
                        message = msg
                    )
                }

                // STEP 6 (H-1): 15-second Fill Monitor with strict REST full-fill confirmation
                val monitorOutcome = monitorOrderFill(
                    accountHash = accountHash,
                    orderId = submitResult.orderId,
                    submittedQuantity = exitQty
                )

                if (monitorOutcome.isFullyFilled) {
                    if (!isLiveBeforeSubmit) {
                        portfolioRepository.refreshShadowPortfolio()
                    }
                    val msg = "${modeBeforeSubmit.name} exit FILLED (${submitResult.orderId}): ${exitSide.name} ${exitQty}x $displaySym @ $${limitPrice.toPlainString()}."
                    sentinelRepository.updateRuleStatusWithNote(
                        ruleId = rule.id,
                        status = SentinelRuleStatus.TRIGGERED_FILLED,
                        note = msg,
                        lastSubmittedOrderId = submitResult.orderId
                    )
                    sentinelRepository.recordAuditEvent(
                        category = "SENTINEL_EXIT_FILLED",
                        severity = "INFO",
                        symbol = displaySym,
                        executionMode = modeBeforeSubmit.name,
                        message = msg
                    )
                    return@withLock SentinelExecutionOutcome.Filled(
                        ruleId = rule.id,
                        orderId = submitResult.orderId,
                        fillPrice = limitPrice,
                        executionMode = modeBeforeSubmit,
                        message = msg
                    )
                }

                // Unfilled or partially filled after timeout -> UNFILLED_ESCALATED with cancel-before-replace draft
                val remainingWhole = if (monitorOutcome.filledQuantity > 0.0 && monitorOutcome.remainingQuantity > 0.0) {
                    floor(monitorOutcome.remainingQuantity).toInt().coerceAtLeast(1)
                } else {
                    exitQty
                }
                val partialTag = if (monitorOutcome.filledQuantity > 0.0) {
                    " (partial fill: ${monitorOutcome.filledQuantity.toInt()} filled, $remainingWhole remaining)"
                } else {
                    ""
                }
                val escalationMsg = "Exit order ${submitResult.orderId} unfilled after ${fillMonitorTimeoutMs / 1_000L}s @ $${limitPrice.toPlainString()}$partialTag — re-price required."
                val updatedRuleWithOrderId = rule.copy(lastSubmittedOrderId = submitResult.orderId)
                val repriceDraft = alertBuilder.buildUnfilledRepriceDraft(
                    rule = updatedRuleWithOrderId,
                    quote = pricingQuote,
                    priorOrderId = submitResult.orderId,
                    overrideQuantity = remainingWhole
                )
                sentinelRepository.updateRuleStatusWithNote(
                    ruleId = rule.id,
                    status = SentinelRuleStatus.UNFILLED_ESCALATED,
                    note = escalationMsg,
                    lastSubmittedOrderId = submitResult.orderId
                )
                sentinelRepository.recordAuditEvent(
                    category = "SENTINEL_EXIT_UNFILLED_ESCALATED",
                    severity = "CRITICAL",
                    symbol = displaySym,
                    executionMode = modeBeforeSubmit.name,
                    message = escalationMsg
                )
                SentinelExecutionOutcome.UnfilledEscalated(
                    ruleId = rule.id,
                    orderId = submitResult.orderId,
                    repriceDraft = repriceDraft,
                    message = escalationMsg
                )
            }
        } finally {
            inFlightRuleIds.remove(rule.id)
        }
    }

    private fun isValidExecutionQuote(q: LiveEquityQuote): Boolean {
        val status = q.securityStatus?.trim().orEmpty()
        if (status.equals("Halted", ignoreCase = true) || status.equals("Closed", ignoreCase = true)) {
            return false
        }
        val bid = q.bidPrice
        val ask = q.askPrice
        return bid != null && ask != null && bid.isFinite() && ask.isFinite() && bid > 0.0 && ask >= bid
    }

    /**
     * Live Mode strictly requires a fresh `refreshLivePortfolio(accountHash)` response
     * and NEVER falls back to cached `observePortfolioSnapshot`.
     */
    private suspend fun fetchPortfolioFailClosed(
        isLive: Boolean,
        accountHash: String,
        rule: SentinelRule,
        quote: LiveEquityQuote,
        displaySym: String,
        mode: ExecutionMode
    ): PortfolioSnapshot? {
        return if (isLive) {
            val fresh = portfolioRepository.refreshLivePositionsOnly(accountHash).getOrNull()
            if (fresh == null) {
                val reason = "Aborted: live position refresh failed — verify manually"
                sentinelRepository.updateRuleStatusWithNote(
                    ruleId = rule.id,
                    status = SentinelRuleStatus.UNFILLED_ESCALATED,
                    note = reason
                )
                sentinelRepository.recordAuditEvent(
                    category = "SENTINEL_ABORTED_REFRESH_FAILED",
                    severity = "CRITICAL",
                    symbol = displaySym,
                    executionMode = mode.name,
                    message = reason
                )
            }
            fresh
        } else {
            portfolioRepository.refreshShadowPortfolio().getOrNull()
                ?: portfolioRepository.observePortfolioSnapshot(PortfolioDataSource.SHADOW_PAPER_LEDGER).first()
        }
    }

    private fun buildRefreshFailedEscalation(
        rule: SentinelRule,
        quote: LiveEquityQuote,
        displaySym: String
    ): SentinelExecutionOutcome.UnfilledEscalated {
        val reason = "Aborted: live position refresh failed — verify manually"
        val draft = alertBuilder.buildUnfilledRepriceDraft(rule, quote, "REFRESH_FAILED")
        return SentinelExecutionOutcome.UnfilledEscalated(
            ruleId = rule.id,
            orderId = "REFRESH_FAILED",
            repriceDraft = draft,
            message = "[$displaySym] $reason"
        )
    }

    private suspend fun verifyDirectionalPosition(
        rule: SentinelRule,
        displaySym: String,
        mode: ExecutionMode,
        snapshot: PortfolioSnapshot
    ): PositionCheckOutcome {
        val openQty = snapshot.positions
            .filter {
                it.category == AssetCategory.EQUITY &&
                    SchwabSymbolNormalizer.toDisplaySymbol(it.symbol) == displaySym
            }
            .sumOf { it.quantity.toDouble() }

        val expectsLongPosition = when (rule.ruleType) {
            SentinelRuleType.LONG_STOP_LOSS -> true
            SentinelRuleType.SHORT_STOP_LOSS -> false
            SentinelRuleType.TAKE_PROFIT -> !rule.isShortPosition
            else -> true
        }

        val positionDirectionValid = if (expectsLongPosition) openQty > 0.0 else openQty < 0.0
        if (!positionDirectionValid) {
            val reason = if (expectsLongPosition) {
                "Aborted LONG exit on $displaySym: openQty=$openQty (requires > 0.0; prevents accidental shorting)."
            } else {
                "Aborted SHORT cover on $displaySym: openQty=$openQty (requires < 0.0; prevents accidental long entry)."
            }
            sentinelRepository.updateRuleStatusWithNote(
                ruleId = rule.id,
                status = SentinelRuleStatus.ABORTED_NO_POSITION,
                note = reason
            )
            sentinelRepository.recordAuditEvent(
                category = "SENTINEL_ABORT_NO_POSITION",
                severity = "WARNING",
                symbol = displaySym,
                executionMode = mode.name,
                message = reason
            )
            return PositionCheckOutcome.Aborted(
                SentinelExecutionOutcome.AbortedNoPosition(
                    ruleId = rule.id,
                    symbol = displaySym,
                    openQuantity = openQty,
                    reason = reason
                )
            )
        }

        // Never round fractional residuals (< 1 share) up to 1
        val availableWholeShares = floor(abs(openQty)).toInt()
        if (availableWholeShares <= 0) {
            val reason = "Fractional residual < 1 share on $displaySym (openQty=$openQty) — cannot place whole-share exit."
            sentinelRepository.updateRuleStatusWithNote(
                ruleId = rule.id,
                status = SentinelRuleStatus.ABORTED_NO_POSITION,
                note = reason
            )
            sentinelRepository.recordAuditEvent(
                category = "SENTINEL_ABORT_NO_POSITION",
                severity = "WARNING",
                symbol = displaySym,
                executionMode = mode.name,
                message = reason
            )
            return PositionCheckOutcome.Aborted(
                SentinelExecutionOutcome.AbortedNoPosition(
                    ruleId = rule.id,
                    symbol = displaySym,
                    openQuantity = openQty,
                    reason = reason
                )
            )
        }

        return PositionCheckOutcome.Verified(
            openQty = openQty,
            availableWholeShares = availableWholeShares,
            expectsLongPosition = expectsLongPosition
        )
    }

    private suspend fun escalatePreSubmitFailure(
        rule: SentinelRule,
        quote: LiveEquityQuote,
        displaySym: String,
        mode: ExecutionMode,
        auditCategory: String,
        reason: String
    ): SentinelExecutionOutcome.UnfilledEscalated {
        val draft = alertBuilder.buildUnfilledRepriceDraft(rule, quote, "PRE_SUBMIT_ABORT")
        sentinelRepository.updateRuleStatusWithNote(
            ruleId = rule.id,
            status = SentinelRuleStatus.UNFILLED_ESCALATED,
            note = reason
        )
        sentinelRepository.recordAuditEvent(
            category = auditCategory,
            severity = "CRITICAL",
            symbol = displaySym,
            executionMode = mode.name,
            message = reason
        )
        return SentinelExecutionOutcome.UnfilledEscalated(
            ruleId = rule.id,
            orderId = "PRE_SUBMIT_ABORT",
            repriceDraft = draft,
            message = reason
        )
    }

    /**
     * H-1 Safeguard: Thread-safe fill monitor requiring strict REST confirmation
     * (`status == "FILLED"` AND `remainingQuantity == null || remainingQuantity <= 0.0`).
     * Never trusts bare `ACCT_ACTIVITY` `OrderFill` events when REST lags (`WORKING`/`0`/`null`) or when partial.
     */
    private suspend fun monitorOrderFill(
        accountHash: String,
        orderId: String,
        submittedQuantity: Int
    ): FillMonitorOutcome {
        val progressRef = AtomicReference(
            FillProgressSnapshot(
                filledQuantity = 0.0,
                remainingQuantity = submittedQuantity.toDouble(),
                status = "WORKING"
            )
        )

        val result = withTimeoutOrNull(fillMonitorTimeoutMs) {
            coroutineScope {
                val fillDetected = CompletableDeferred<Boolean>()

                val eventJob = launch {
                    accountEventsFlow.collect { event ->
                        if (event.isOrderFill && event.orderId == orderId) {
                            val eventIndicatesPartial = eventPayloadIndicatesPartialFill(event.messageDataJson)
                            val snap = orderRepository.getOrderStatus(accountHash, orderId).getOrNull()
                            if (snap != null) {
                                updateProgress(progressRef, snap)
                                // Guard 4: Complete ONLY when REST confirms FILLED AND (remainingQuantity == null || <= 0.0)
                                if (snap.isRestConfirmedFullFill() && !eventIndicatesPartial) {
                                    fillDetected.complete(true)
                                }
                            }
                            // If snap == null, WORKING, PARTIALLY_FILLED, or remaining > 0 -> do NOT complete; let pollJob keep checking!
                        }
                    }
                }

                val pollJob = launch {
                    while (!fillDetected.isCompleted) {
                        val snap = orderRepository.getOrderStatus(accountHash, orderId).getOrNull()
                        if (snap != null) {
                            updateProgress(progressRef, snap)
                            if (snap.isRestConfirmedFullFill()) {
                                fillDetected.complete(true)
                                break
                            }
                            if (snap.normalizedStatus in TERMINAL_ABORTED_STATUSES) {
                                fillDetected.complete(false)
                                break
                            }
                        }
                        delay(fillPollIntervalMs)
                    }
                }

                val outcome = fillDetected.await()
                eventJob.cancel()
                pollJob.cancel()
                outcome
            }
        }
        val finalProgress = progressRef.get()
        return FillMonitorOutcome(
            isFullyFilled = result == true,
            filledQuantity = finalProgress.filledQuantity,
            remainingQuantity = finalProgress.remainingQuantity,
            lastStatus = finalProgress.status
        )
    }

    private fun updateProgress(
        ref: AtomicReference<FillProgressSnapshot>,
        snap: OrderStatusSnapshot
    ) {
        ref.updateAndGet { prev ->
            FillProgressSnapshot(
                filledQuantity = if (snap.filledQuantity > 0.0) snap.filledQuantity else prev.filledQuantity,
                remainingQuantity = snap.remainingQuantity
                    ?: if (snap.normalizedStatus == "FILLED") 0.0 else prev.remainingQuantity,
                status = snap.normalizedStatus
            )
        }
    }

    private fun OrderStatusSnapshot.isRestConfirmedFullFill(): Boolean =
        normalizedStatus == "FILLED" && (remainingQuantity == null || remainingQuantity <= 0.0)

    private fun eventPayloadIndicatesPartialFill(dataJson: String?): Boolean {
        if (dataJson.isNullOrBlank()) return false
        if (dataJson.contains("PARTIALLY_FILLED", ignoreCase = true)) return true
        val remMatch = REMAINING_QTY_REGEX.find(dataJson)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        return remMatch != null && remMatch > 0.0
    }

    private suspend fun failRule(
        ruleId: Long,
        symbol: String,
        mode: ExecutionMode,
        reason: String
    ): SentinelExecutionOutcome.Failed {
        sentinelRepository.updateRuleStatusWithNote(
            ruleId = ruleId,
            status = SentinelRuleStatus.UNFILLED_ESCALATED,
            note = reason
        )
        sentinelRepository.recordAuditEvent(
            category = "SENTINEL_EXIT_ERROR",
            severity = "CRITICAL",
            symbol = symbol,
            executionMode = mode.name,
            message = reason
        )
        return SentinelExecutionOutcome.Failed(ruleId, reason)
    }

    companion object {
        const val DEFAULT_SETTLEMENT_DELAY_MS = 1_500L
        const val DEFAULT_FILL_MONITOR_TIMEOUT_MS = 15_000L
        const val DEFAULT_FILL_POLL_INTERVAL_MS = 1_000L
        private const val SHADOW_ACCOUNT_HASH = "SHADOW_PAPER"
        private val REMAINING_QTY_REGEX = Regex(
            """(?:remainingQuantity|orderRemainingQuantity|LeavesQuantity|leavesQuantity)"?\s*[:=]\s*"?([0-9]+(?:\.[0-9]+)?)""",
            RegexOption.IGNORE_CASE
        )
        private val MULTI_LEG_OPTION_STRATEGIES = setOf(
            "DIAGONAL",
            "VERTICAL",
            "CALENDAR",
            "IRON_CONDOR"
        )
        private val TERMINAL_ABORTED_STATUSES = setOf(
            "REJECTED",
            "CANCELED",
            "CANCELLED",
            "EXPIRED"
        )
    }
}
