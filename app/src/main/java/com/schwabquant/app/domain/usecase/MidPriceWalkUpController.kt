package com.schwabquant.app.domain.usecase

import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderStatusSnapshot
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.ReplaceOrderOutcome
import com.schwabquant.app.domain.model.WalkUpState
import com.schwabquant.app.domain.repository.OrderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Assisted Mid-Price Walk-Up Controller for option & multi-leg entries:
 * - C-4 Hardening: Serializes `startWalkUp()` and `cancelWalkUp()` via `lifecycleMutex` +
 *   `latestSubmittedOrder` (`AtomicReference`), awaits prior execution/pending-cancel jobs FIRST,
 *   requires `isCanceledWithZeroFill` before any restart, and rejects null/non-positive prices (H-2).
 */
@Singleton
class MidPriceWalkUpController(
    private val orderRepository: OrderRepository,
    private val restartCancelSettlementDelayMs: Long = DEFAULT_RESTART_SETTLEMENT_MS
) {

    @Inject
    constructor(orderRepository: OrderRepository) : this(orderRepository, DEFAULT_RESTART_SETTLEMENT_MS)

    private data class ActiveOrderContext(
        val accountHash: String,
        val orderId: String,
        val executionMode: ExecutionMode,
        val scope: CoroutineScope,
        val cancelClaimed: AtomicBoolean = AtomicBoolean(false),
        @Volatile var cancelError: String? = null,
        @Volatile var settleWaitNeeded: Boolean = false
    )

    private val _state = MutableStateFlow(WalkUpState())
    val state: StateFlow<WalkUpState> = _state.asStateFlow()

    private val sessionLock = Any()
    private val lifecycleMutex = Mutex()
    private val executionMutex = Mutex()
    private val latestSubmittedOrder = AtomicReference<ActiveOrderContext?>(null)
    private val operationEpoch = AtomicLong(0L)
    private val pendingCancelJobRef = AtomicReference<Job?>(null)

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var lastKnownScope: CoroutineScope? = null

    @Volatile
    private var manualPromptAckCallback: (() -> Unit)? = null

    @Volatile
    private var cancelOrderOnCoroutineCancel: Boolean = true

    fun startWalkUp(
        scope: CoroutineScope,
        initialTicket: OrderTicket,
        hardMaxCeiling: BigDecimal,
        intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
        stepAmount: BigDecimal = DEFAULT_STEP_AMOUNT,
        autoStep: Boolean = true,
        overriddenRiskCodes: List<String> = emptyList(),
        onTerminalEvent: (String) -> Unit = {}
    ) {
        val isOco = initialTicket.strategyType.equals("OCO", ignoreCase = true) ||
            initialTicket.childOrders.isNotEmpty()
        val isSupportedLimitType = initialTicket.orderType in SUPPORTED_WALK_UP_ORDER_TYPES
        if (isOco || !isSupportedLimitType) {
            val msg = "Walk-Up rejected: only LIMIT, NET_DEBIT, and NET_CREDIT orders are supported " +
                "(got strategy=${initialTicket.strategyType}, orderType=${initialTicket.orderType.apiValue})."
            _state.value = WalkUpState(isActive = false, statusMessage = msg)
            onTerminalEvent(msg)
            return
        }

        // H-2 Safeguard: Reject null or non-positive limit price / ceiling (no silent $1.00 fallback)
        val rawInitialPrice = initialTicket.limitPrice
        if (rawInitialPrice == null || rawInitialPrice <= BigDecimal.ZERO || hardMaxCeiling <= BigDecimal.ZERO) {
            val msg = "Walk-Up rejected: valid positive limit price and max ceiling are required."
            _state.value = WalkUpState(isActive = false, statusMessage = msg)
            onTerminalEvent(msg)
            return
        }

        val initialPrice = rawInitialPrice.setScale(2, RoundingMode.HALF_UP)
        val ceiling = hardMaxCeiling.setScale(2, RoundingMode.HALF_UP)
        val isDebitOrder = initialTicket.orderType != OrderType.NET_CREDIT &&
            !(initialTicket.orderType == OrderType.LIMIT &&
                initialTicket.legs.size == 1 &&
                initialTicket.legs.first().side.name.startsWith("SELL"))
        val startingTicket = initialTicket.copy(limitPrice = initialPrice)
        val stepDelta = stepAmount
        val intervalSec = intervalSeconds.coerceIn(5, 300)

        val myEpoch = operationEpoch.incrementAndGet()
        synchronized(sessionLock) {
            lastKnownScope = scope
        }

        // C-2/C-3/C-4 + H-3 Safeguard: Never join pendingCancelJob inside lifecycleMutex; re-check epoch after waits
        scope.launch {
            while (true) {
                while (true) {
                    val pending = pendingCancelJobRef.get()
                    if (pending == null || pending.isCompleted) break
                    runCatching { pending.join() }
                    if (operationEpoch.get() != myEpoch) return@launch
                }
                if (operationEpoch.get() != myEpoch) return@launch

                var shouldRetry = false
                lifecycleMutex.withLock {
                    if (pendingCancelJobRef.get()?.isCompleted == false) {
                        shouldRetry = true
                        return@withLock
                    }
                    if (operationEpoch.get() != myEpoch) return@withLock

                    val jobToStop = synchronized(sessionLock) {
                        cancelOrderOnCoroutineCancel = true
                        val current = activeJob
                        activeJob = null
                        manualPromptAckCallback = null
                        current
                    }

                    if (jobToStop != null) {
                        runCatching { jobToStop.cancelAndJoin() }
                    }
                    if (operationEpoch.get() != myEpoch) return@withLock

                    val prev = latestSubmittedOrder.get()
                    if (prev != null && prev.orderId.isNotBlank()) {
                        val verifiedZeroFillCancel = verifyPriorOrderCanceledFailClosed(prev, onTerminalEvent)
                        if (!verifiedZeroFillCancel) {
                            return@withLock
                        }
                        latestSubmittedOrder.compareAndSet(prev, null)
                    }

                    if (operationEpoch.get() != myEpoch) return@withLock

                    var launchedJob: Job? = null
                    synchronized(sessionLock) {
                        if (operationEpoch.get() != myEpoch) return@synchronized
                        cancelOrderOnCoroutineCancel = true
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            runWalkUpExecutionLoop(
                                scope = scope,
                                startingTicketInitial = startingTicket,
                                initialPrice = initialPrice,
                                ceiling = ceiling,
                                stepDelta = stepDelta,
                                intervalSec = intervalSec,
                                isDebitOrder = isDebitOrder,
                                autoStep = autoStep,
                                overriddenRiskCodes = overriddenRiskCodes,
                                onTerminalEvent = onTerminalEvent
                            )
                        }
                        activeJob = job
                        launchedJob = job
                    }
                    launchedJob?.start()
                }
                if (!shouldRetry) break
            }
        }
    }

    private fun isTerminalZeroFill(snap: OrderStatusSnapshot): Boolean =
        snap.isCanceledWithZeroFill ||
            (snap.normalizedStatus in TERMINAL_NON_WORKING_STATUSES && snap.filledQuantity <= 0.0)

    private fun isTerminalWithFill(snap: OrderStatusSnapshot): Boolean =
        snap.normalizedStatus == "FILLED" ||
            (snap.normalizedStatus in TERMINAL_NON_WORKING_STATUSES && snap.filledQuantity > 0.0)

    private fun formatFilledQty(qty: Double): String =
        if (qty % 1.0 == 0.0) qty.toInt().toString() else qty.toString()

    private fun handlePriorOrderFilledOnRestart(
        prev: ActiveOrderContext,
        snap: OrderStatusSnapshot,
        onTerminalEvent: (String) -> Unit
    ): Boolean {
        prev.cancelError = null
        prev.cancelClaimed.set(true)
        latestSubmittedOrder.compareAndSet(prev, null)
        val msg = "Prior walk-up order ${prev.orderId} filled (${formatFilledQty(snap.filledQuantity)} qty, status=${snap.normalizedStatus}) — aborted restart to prevent duplicate fill; verify position before retrying."
        _state.value = WalkUpState(isActive = false, orderId = prev.orderId, statusMessage = msg)
        onTerminalEvent(msg)
        return false
    }

    private suspend fun verifyPriorOrderCanceledFailClosed(
        prev: ActiveOrderContext,
        onTerminalEvent: (String) -> Unit
    ): Boolean {
        // H-2: Query broker status first unless a fresh DELETE just fired in finally and needs settlement delay
        if (!prev.settleWaitNeeded) {
            val initialSnap = orderRepository.getOrderStatus(prev.accountHash, prev.orderId).getOrNull()
            if (initialSnap != null) {
                if (isTerminalZeroFill(initialSnap)) {
                    prev.cancelError = null
                    prev.cancelClaimed.set(true)
                    latestSubmittedOrder.compareAndSet(prev, null)
                    return true
                }
                if (isTerminalWithFill(initialSnap)) {
                    return handlePriorOrderFilledOnRestart(prev, initialSnap, onTerminalEvent)
                }
            }

            if (prev.cancelClaimed.compareAndSet(false, true)) {
                val cancelRes = runCatching {
                    orderRepository.cancelOrder(
                        accountHash = prev.accountHash,
                        orderId = prev.orderId,
                        mode = prev.executionMode
                    )
                }.getOrElse { Result.failure(it) }
                if (cancelRes.isFailure) {
                    val postFailSnap = orderRepository.getOrderStatus(prev.accountHash, prev.orderId).getOrNull()
                    if (postFailSnap != null) {
                        if (isTerminalZeroFill(postFailSnap)) {
                            prev.cancelError = null
                            latestSubmittedOrder.compareAndSet(prev, null)
                            return true
                        }
                        if (isTerminalWithFill(postFailSnap)) {
                            return handlePriorOrderFilledOnRestart(prev, postFailSnap, onTerminalEvent)
                        }
                    }
                    val errMsg = cancelRes.exceptionOrNull()?.message ?: "cancel failed"
                    prev.cancelError = errMsg
                    prev.cancelClaimed.set(false)
                    val msg = "Walk-Up restart aborted: failed canceling prior order ${prev.orderId} ($errMsg)."
                    _state.value = WalkUpState(isActive = false, orderId = prev.orderId, statusMessage = msg)
                    onTerminalEvent(msg)
                    return false
                }
                prev.cancelError = null
                prev.settleWaitNeeded = true
            } else if (prev.cancelError != null) {
                val msg = "Walk-Up restart aborted: failed canceling prior order ${prev.orderId} (${prev.cancelError})."
                _state.value = WalkUpState(isActive = false, orderId = prev.orderId, statusMessage = msg)
                onTerminalEvent(msg)
                return false
            }
        }

        if (prev.settleWaitNeeded && restartCancelSettlementDelayMs > 0L) {
            delay(restartCancelSettlementDelayMs)
        }
        prev.settleWaitNeeded = false

        val priorSnap = orderRepository.getOrderStatus(prev.accountHash, prev.orderId)
            .getOrElse { err ->
                val msg = "Walk-Up restart aborted: unable to verify prior order ${prev.orderId} status (${err.message})."
                _state.value = WalkUpState(isActive = false, orderId = prev.orderId, statusMessage = msg)
                onTerminalEvent(msg)
                return false
            }
        if (isTerminalZeroFill(priorSnap)) {
            prev.cancelError = null
            latestSubmittedOrder.compareAndSet(prev, null)
            return true
        }
        if (isTerminalWithFill(priorSnap)) {
            return handlePriorOrderFilledOnRestart(prev, priorSnap, onTerminalEvent)
        }
        val msg = "Walk-Up restart aborted: prior order ${prev.orderId} remained ${priorSnap.normalizedStatus} (filled=${priorSnap.filledQuantity})."
        _state.value = WalkUpState(isActive = false, orderId = prev.orderId, statusMessage = msg)
        onTerminalEvent(msg)
        return false
    }

    private suspend fun runWalkUpExecutionLoop(
        scope: CoroutineScope,
        startingTicketInitial: OrderTicket,
        initialPrice: BigDecimal,
        ceiling: BigDecimal,
        stepDelta: BigDecimal,
        intervalSec: Int,
        isDebitOrder: Boolean,
        autoStep: Boolean,
        overriddenRiskCodes: List<String>,
        onTerminalEvent: (String) -> Unit
    ) {
        executionMutex.withLock {
            var trackedCtx: ActiveOrderContext? = null
            var cleanTerminalExit = false

            try {
                var sessionTicket = startingTicketInitial
                var newlySubmittedCtx: ActiveOrderContext? = null
                val submitResult = withContext(NonCancellable) {
                    orderRepository.submitOrder(
                        ticket = sessionTicket,
                        risksAcknowledged = true,
                        overriddenRiskCodes = overriddenRiskCodes,
                        simulateImmediateMidFill = false
                    ).also { res ->
                        res.getOrNull()?.let { sub ->
                            val st = sub.status.trim().uppercase()
                            if (st != "UNTRACKED" && st != "FILLED" && sub.orderId.isNotBlank()) {
                                val ctx = ActiveOrderContext(
                                    accountHash = sessionTicket.accountHash,
                                    orderId = sub.orderId,
                                    executionMode = sub.executionMode,
                                    scope = scope
                                )
                                newlySubmittedCtx = ctx
                                latestSubmittedOrder.set(ctx)
                            }
                        }
                    }
                }

                val initialSubmission = submitResult.getOrElse { err ->
                    cleanTerminalExit = true
                    _state.value = WalkUpState(
                        isActive = false,
                        statusMessage = "Walk-Up failed on initial submit: ${err.message}"
                    )
                    onTerminalEvent(_state.value.statusMessage.orEmpty())
                    return@withLock
                }

                val initialStatus = initialSubmission.status.trim().uppercase()
                if (newlySubmittedCtx != null) {
                    trackedCtx = newlySubmittedCtx
                }

                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Walk-Up canceled during initial submission.")
                }

                if (initialStatus == "UNTRACKED" || initialSubmission.orderId.isBlank()) {
                    cleanTerminalExit = true
                    latestSubmittedOrder.set(null)
                    _state.value = WalkUpState(
                        isActive = false,
                        statusMessage = "Walk-Up halted: initial order accepted without tracking ID — verify in Schwab."
                    )
                    onTerminalEvent(_state.value.statusMessage.orEmpty())
                    return@withLock
                }

                var activeOrderId = initialSubmission.orderId
                var activePrice = initialPrice
                var stepsDone = 0

                if (initialStatus == "FILLED") {
                    cleanTerminalExit = true
                    latestSubmittedOrder.set(null)
                    _state.value = WalkUpState(
                        isActive = false,
                        orderId = activeOrderId,
                        currentLimitPrice = activePrice,
                        statusMessage = "Order $activeOrderId filled immediately @ $$activePrice."
                    )
                    onTerminalEvent(_state.value.statusMessage.orEmpty())
                    return@withLock
                }

                while (true) {
                    for (sec in intervalSec downTo 1) {
                        _state.value = WalkUpState(
                            isActive = true,
                            orderId = activeOrderId,
                            currentLimitPrice = activePrice,
                            hardMaxCeiling = ceiling,
                            secondsRemaining = sec,
                            stepCount = stepsDone,
                            isDebit = isDebitOrder,
                            statusMessage = "Working order $activeOrderId @ $$activePrice — next walk-up in ${sec}s."
                        )
                        delay(1_000L)
                    }

                    // C-5 Fail-Closed: Never proceed into replaceOrder() if getOrderStatus() failed
                    val statusSnapshot = orderRepository.getOrderStatus(
                        sessionTicket.accountHash,
                        activeOrderId
                    ).getOrElse { err ->
                        cleanTerminalExit = true
                        _state.value = WalkUpState(
                            isActive = false,
                            orderId = activeOrderId,
                            currentLimitPrice = activePrice,
                            stepCount = stepsDone,
                            statusMessage = "Walk-Up halted: unable to verify order $activeOrderId status (${err.message ?: "network error"}) — verify manually."
                        )
                        onTerminalEvent(_state.value.statusMessage.orEmpty())
                        return@withLock
                    }
                    val liveStatus = statusSnapshot.normalizedStatus

                    if (statusSnapshot.isConfirmedFullyFilled) {
                        cleanTerminalExit = true
                        trackedCtx?.let { latestSubmittedOrder.compareAndSet(it, null) }
                        _state.value = WalkUpState(
                            isActive = false,
                            orderId = activeOrderId,
                            currentLimitPrice = activePrice,
                            stepCount = stepsDone,
                            statusMessage = "Walk-Up completed: $activeOrderId FILLED @ $$activePrice."
                        )
                        onTerminalEvent(_state.value.statusMessage.orEmpty())
                        return@withLock
                    }

                    if (liveStatus == "PARTIALLY_FILLED" || statusSnapshot.filledQuantity > 0.0) {
                        cleanTerminalExit = true
                        trackedCtx?.let { latestSubmittedOrder.compareAndSet(it, null) }
                        val filledQty = statusSnapshot.filledQuantity
                        val remQty = statusSnapshot.remainingQuantity ?: 0.0
                        _state.value = WalkUpState(
                            isActive = false,
                            orderId = activeOrderId,
                            currentLimitPrice = activePrice,
                            stepCount = stepsDone,
                            statusMessage = "Walk-Up halted: order $activeOrderId partially filled " +
                                "(${filledQty.toInt()} filled, ${remQty.toInt()} remaining). Verify position before modifying."
                        )
                        onTerminalEvent(_state.value.statusMessage.orEmpty())
                        return@withLock
                    }

                    if (liveStatus in TERMINAL_NON_WORKING_STATUSES) {
                        cleanTerminalExit = true
                        trackedCtx?.let { latestSubmittedOrder.compareAndSet(it, null) }
                        _state.value = WalkUpState(
                            isActive = false,
                            orderId = activeOrderId,
                            statusMessage = "Walk-Up stopped: order $activeOrderId status is $liveStatus."
                        )
                        onTerminalEvent(_state.value.statusMessage.orEmpty())
                        return@withLock
                    }

                    val nextPrice = if (isDebitOrder) {
                        (activePrice + stepDelta).setScale(2, RoundingMode.HALF_UP)
                    } else {
                        (activePrice - stepDelta).setScale(2, RoundingMode.HALF_UP)
                    }

                    val ceilingReached = if (isDebitOrder) {
                        nextPrice > ceiling
                    } else {
                        nextPrice < ceiling || nextPrice <= BigDecimal.ZERO
                    }
                    if (ceilingReached) {
                        cleanTerminalExit = true
                        // M-3 Safeguard: Transfer ownership of resting limit order to Working Orders
                        trackedCtx?.let { latestSubmittedOrder.compareAndSet(it, null) }
                        _state.value = WalkUpState(
                            isActive = false,
                            orderId = activeOrderId,
                            currentLimitPrice = activePrice,
                            hardMaxCeiling = ceiling,
                            stepCount = stepsDone,
                            isDebit = isDebitOrder,
                            statusMessage = "Reached Hard Max Walk-Up limit ($$ceiling). Order $activeOrderId left working @ $$activePrice."
                        )
                        onTerminalEvent(_state.value.statusMessage.orEmpty())
                        return@withLock
                    }

                    if (!autoStep) {
                        _state.value = WalkUpState(
                            isActive = true,
                            orderId = activeOrderId,
                            currentLimitPrice = activePrice,
                            hardMaxCeiling = ceiling,
                            secondsRemaining = 0,
                            stepCount = stepsDone,
                            isDebit = isDebitOrder,
                            waitingForStepPrompt = true,
                            statusMessage = "Ready to step $activeOrderId from $$activePrice -> $$nextPrice?"
                        )
                        awaitManualStepConfirmation()
                    }

                    _state.value = _state.value.copy(
                        waitingForStepPrompt = false,
                        statusMessage = "Canceling $activeOrderId, pausing 1.5s & verifying before replacing @ $$nextPrice..."
                    )

                    val replacementTicket = sessionTicket.copy(limitPrice = nextPrice)
                    var replacedCtx: ActiveOrderContext? = null
                    // C-1 Safeguard: Keep replaceOrder (cancel + 1.5s settlement + status verify) cancellable so
                    // cancelWalkUp() immediately interrupts before submitOrder() can dispatch a replacement.
                    val outcome = orderRepository.replaceOrder(
                        accountHash = replacementTicket.accountHash,
                        oldOrderId = activeOrderId,
                        replacementTicket = replacementTicket,
                        risksAcknowledged = true,
                        overriddenRiskCodes = overriddenRiskCodes,
                        cancelToReplaceDelayMs = 1_500L,
                        allowTerminalOrPartialReprice = false
                    ).also { res ->
                        val out = res.getOrNull()
                        if (out is ReplaceOrderOutcome.Replaced) {
                            trackedCtx?.cancelClaimed?.set(true)
                            val rep = out.result
                            val repStatus = rep.status.trim().uppercase()
                            if (repStatus != "UNTRACKED" && repStatus != "FILLED" && rep.orderId.isNotBlank()) {
                                val nextCtx = ActiveOrderContext(
                                    accountHash = replacementTicket.accountHash,
                                    orderId = rep.orderId,
                                    executionMode = rep.executionMode,
                                    scope = scope
                                )
                                replacedCtx = nextCtx
                                latestSubmittedOrder.set(nextCtx)
                            } else {
                                latestSubmittedOrder.set(null)
                            }
                        }
                    }.getOrElse { err ->
                        if (err is CancellationException) throw err
                        cleanTerminalExit = true
                        _state.value = WalkUpState(
                            isActive = false,
                            orderId = activeOrderId,
                            statusMessage = "Walk-Up replace error: ${err.message}"
                        )
                        onTerminalEvent(_state.value.statusMessage.orEmpty())
                        return@withLock
                    }

                    when (outcome) {
                        is ReplaceOrderOutcome.Replaced -> {
                            val rep = outcome.result
                            val repStatus = rep.status.trim().uppercase()
                            trackedCtx = replacedCtx

                            if (!currentCoroutineContext().isActive) {
                                throw CancellationException("Walk-Up canceled during replaceOrder.")
                            }

                            if (repStatus == "UNTRACKED" || rep.orderId.isBlank()) {
                                cleanTerminalExit = true
                                latestSubmittedOrder.set(null)
                                _state.value = WalkUpState(
                                    isActive = false,
                                    orderId = rep.orderId.ifBlank { null },
                                    currentLimitPrice = nextPrice,
                                    stepCount = stepsDone,
                                    statusMessage = "Walk-Up halted: replacement order accepted without tracking ID — verify in Schwab."
                                )
                                onTerminalEvent(_state.value.statusMessage.orEmpty())
                                return@withLock
                            }

                            if (repStatus == "FILLED") {
                                cleanTerminalExit = true
                                latestSubmittedOrder.set(null)
                                _state.value = WalkUpState(
                                    isActive = false,
                                    orderId = rep.orderId,
                                    currentLimitPrice = nextPrice,
                                    stepCount = stepsDone + 1,
                                    statusMessage = "Walk-Up completed: ${rep.orderId} FILLED @ $$nextPrice."
                                )
                                onTerminalEvent(_state.value.statusMessage.orEmpty())
                                return@withLock
                            }

                            activeOrderId = rep.orderId
                            activePrice = nextPrice
                            sessionTicket = replacementTicket
                            stepsDone++
                        }

                        is ReplaceOrderOutcome.AbortedAlreadyFilled -> {
                            cleanTerminalExit = true
                            trackedCtx?.cancelClaimed?.set(true)
                            trackedCtx?.let { latestSubmittedOrder.compareAndSet(it, null) }
                            trackedCtx = null
                            val detail = if (outcome.filledQuantity > 0.0 && outcome.remainingQuantity > 0.0) {
                                "${outcome.priorStatus} (filled=${outcome.filledQuantity.toInt()}, remaining=${outcome.remainingQuantity.toInt()})"
                            } else if (outcome.filledQuantity > 0.0) {
                                "${outcome.priorStatus} (filled=${outcome.filledQuantity.toInt()})"
                            } else {
                                outcome.priorStatus
                            }
                            _state.value = WalkUpState(
                                isActive = false,
                                orderId = outcome.priorOrderId,
                                currentLimitPrice = activePrice,
                                stepCount = stepsDone,
                                statusMessage = "Double-fill prevented: prior order ${outcome.priorOrderId} reported $detail."
                            )
                            onTerminalEvent(_state.value.statusMessage.orEmpty())
                            return@withLock
                        }

                        is ReplaceOrderOutcome.AbortedNotCanceled -> {
                            cleanTerminalExit = true
                            _state.value = WalkUpState(
                                isActive = false,
                                orderId = outcome.priorOrderId,
                                currentLimitPrice = activePrice,
                                stepCount = stepsDone,
                                statusMessage = "Walk-Up aborted cleanly: order status '${outcome.priorStatus}' was not CANCELED."
                            )
                            onTerminalEvent(_state.value.statusMessage.orEmpty())
                            return@withLock
                        }
                    }
                }
            } finally {
                // C-4/C-5 + H-2 Safeguard: NonCancellable cancel on coroutine cancellation; inspect broker status on failure
                val orphanCtx = trackedCtx ?: latestSubmittedOrder.get()
                if (!cleanTerminalExit && cancelOrderOnCoroutineCancel && orphanCtx != null && orphanCtx.orderId.isNotBlank()) {
                    if (orphanCtx.cancelClaimed.compareAndSet(false, true)) {
                        withContext(NonCancellable) {
                            val cancelRes = runCatching {
                                orderRepository.cancelOrder(
                                    accountHash = orphanCtx.accountHash,
                                    orderId = orphanCtx.orderId,
                                    mode = orphanCtx.executionMode
                                )
                            }.getOrElse { Result.failure(it) }
                            if (cancelRes.isFailure) {
                                val postFailSnap = runCatching {
                                    orderRepository.getOrderStatus(orphanCtx.accountHash, orphanCtx.orderId)
                                }.getOrNull()?.getOrNull()
                                if (postFailSnap != null && isTerminalZeroFill(postFailSnap)) {
                                    orphanCtx.cancelError = null
                                    orphanCtx.settleWaitNeeded = false
                                    latestSubmittedOrder.compareAndSet(orphanCtx, null)
                                } else if (postFailSnap != null && isTerminalWithFill(postFailSnap)) {
                                    // Keep latestSubmittedOrder so caller surfaces the fill and blocks duplicate restart
                                    orphanCtx.cancelError = null
                                    orphanCtx.settleWaitNeeded = false
                                } else {
                                    orphanCtx.cancelError =
                                        cancelRes.exceptionOrNull()?.message ?: "cancel failed"
                                    orphanCtx.settleWaitNeeded = false
                                    orphanCtx.cancelClaimed.set(false)
                                }
                            } else {
                                orphanCtx.cancelError = null
                                orphanCtx.settleWaitNeeded = true
                            }
                        }
                    }
                }
            }
        }
    }

    fun confirmNextManualStep() {
        val cb = synchronized(sessionLock) {
            val current = manualPromptAckCallback
            manualPromptAckCallback = null
            current
        }
        cb?.invoke()
    }

    fun cancelWalkUp(cancelWorkingOrder: Boolean = true) {
        // C-2/C-3 + H-3: Capture epoch synchronously; never mutate cancelOrderOnCoroutineCancel unlocked
        val cancelEpoch = operationEpoch.incrementAndGet()
        val cancelScope = synchronized(sessionLock) {
            manualPromptAckCallback = null
            latestSubmittedOrder.get()?.scope ?: lastKnownScope
        }

        if (cancelScope == null) {
            val (jobToCancel, hadTracked) = synchronized(sessionLock) {
                cancelOrderOnCoroutineCancel = cancelWorkingOrder
                val j = activeJob
                activeJob = null
                j to latestSubmittedOrder.get()
            }
            jobToCancel?.cancel()
            if (!cancelWorkingOrder && hadTracked != null) {
                latestSubmittedOrder.compareAndSet(hadTracked, null)
            }
            _state.value = WalkUpState(
                isActive = false,
                statusMessage = if (jobToCancel != null) "Mid-Price Walk-Up canceled." else null
            )
            return
        }

        synchronized(sessionLock) {
            val previousPending = pendingCancelJobRef.get()
            lateinit var cancelJob: Job
            cancelJob = cancelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    previousPending?.let { prevCancel ->
                        if (!prevCancel.isCompleted) {
                            runCatching { prevCancel.join() }
                        }
                    }
                    lifecycleMutex.withLock {
                        // H-3: Check epoch BEFORE mutating cancelOrderOnCoroutineCancel, activeJob, or latestSubmittedOrder
                        if (operationEpoch.get() != cancelEpoch) return@withLock

                        val jobToCancel = synchronized(sessionLock) {
                            cancelOrderOnCoroutineCancel = cancelWorkingOrder
                            manualPromptAckCallback = null
                            val current = activeJob
                            activeJob = null
                            current
                        }
                        if (jobToCancel != null) {
                            runCatching { jobToCancel.cancelAndJoin() }
                        }
                        if (operationEpoch.get() != cancelEpoch) return@withLock

                        val ctx = latestSubmittedOrder.get()
                        if (cancelWorkingOrder && ctx != null && ctx.orderId.isNotBlank()) {
                            cancelTrackedOrderForStopLocked(ctx)
                        } else {
                            if (!cancelWorkingOrder && ctx != null) {
                                latestSubmittedOrder.compareAndSet(ctx, null)
                            }
                            _state.value = WalkUpState(
                                isActive = false,
                                statusMessage = if (jobToCancel != null) "Mid-Price Walk-Up canceled." else null
                            )
                        }
                    }
                } finally {
                    pendingCancelJobRef.compareAndSet(cancelJob, null)
                }
            }
            pendingCancelJobRef.set(cancelJob)
            cancelJob.start()
        }
    }

    private suspend fun cancelTrackedOrderForStopLocked(ctx: ActiveOrderContext) {
        // H-2: Query broker status first so already-terminal orders clear latestSubmittedOrder cleanly
        val initialSnap = orderRepository.getOrderStatus(ctx.accountHash, ctx.orderId).getOrNull()
        if (initialSnap != null && applyTerminalBrokerSnapshotOnCancel(ctx, initialSnap)) {
            return
        }

        _state.value = WalkUpState(
            isActive = false,
            orderId = ctx.orderId,
            statusMessage = "Mid-Price Walk-Up canceled; canceling working order ${ctx.orderId}..."
        )
        if (ctx.cancelClaimed.compareAndSet(false, true)) {
            val res = runCatching {
                orderRepository.cancelOrder(
                    accountHash = ctx.accountHash,
                    orderId = ctx.orderId,
                    mode = ctx.executionMode
                )
            }.getOrElse { Result.failure(it) }
            if (res.isSuccess) {
                ctx.cancelError = null
                val afterCancelSnap = orderRepository.getOrderStatus(ctx.accountHash, ctx.orderId).getOrNull()
                if (afterCancelSnap != null && applyTerminalBrokerSnapshotOnCancel(ctx, afterCancelSnap)) {
                    return
                }
                _state.value = WalkUpState(
                    isActive = false,
                    orderId = ctx.orderId,
                    statusMessage = "Mid-Price Walk-Up canceled (order ${ctx.orderId} canceled)."
                )
            } else {
                val afterFailureSnap = orderRepository.getOrderStatus(ctx.accountHash, ctx.orderId).getOrNull()
                if (afterFailureSnap != null && applyTerminalBrokerSnapshotOnCancel(ctx, afterFailureSnap)) {
                    return
                }
                val errMsg = res.exceptionOrNull()?.message ?: "cancel failed"
                ctx.cancelError = errMsg
                ctx.cancelClaimed.set(false)
                _state.value = WalkUpState(
                    isActive = false,
                    orderId = ctx.orderId,
                    statusMessage = "Mid-Price Walk-Up stopped, but canceling order ${ctx.orderId} failed: $errMsg"
                )
            }
        } else if (ctx.cancelError != null) {
            val recoverySnap = orderRepository.getOrderStatus(ctx.accountHash, ctx.orderId).getOrNull()
            if (recoverySnap != null && applyTerminalBrokerSnapshotOnCancel(ctx, recoverySnap)) {
                return
            }
            _state.value = WalkUpState(
                isActive = false,
                orderId = ctx.orderId,
                statusMessage = "Mid-Price Walk-Up stopped, but canceling order ${ctx.orderId} failed: ${ctx.cancelError}"
            )
        } else {
            val afterPriorCancelSnap = orderRepository.getOrderStatus(ctx.accountHash, ctx.orderId).getOrNull()
            if (afterPriorCancelSnap != null && applyTerminalBrokerSnapshotOnCancel(ctx, afterPriorCancelSnap)) {
                return
            }
            _state.value = WalkUpState(
                isActive = false,
                orderId = ctx.orderId,
                statusMessage = "Mid-Price Walk-Up canceled (order ${ctx.orderId} canceled)."
            )
        }
    }

    private fun applyTerminalBrokerSnapshotOnCancel(
        ctx: ActiveOrderContext,
        snap: OrderStatusSnapshot
    ): Boolean {
        if (isTerminalZeroFill(snap)) {
            ctx.cancelError = null
            ctx.cancelClaimed.set(true)
            latestSubmittedOrder.compareAndSet(ctx, null)
            _state.value = WalkUpState(
                isActive = false,
                orderId = ctx.orderId,
                statusMessage = "Mid-Price Walk-Up stopped (order ${ctx.orderId} already ${snap.normalizedStatus} with 0 fill)."
            )
            return true
        }
        if (isTerminalWithFill(snap)) {
            ctx.cancelError = null
            ctx.cancelClaimed.set(true)
            latestSubmittedOrder.compareAndSet(ctx, null)
            _state.value = WalkUpState(
                isActive = false,
                orderId = ctx.orderId,
                statusMessage = "Mid-Price Walk-Up stopped: order ${ctx.orderId} already ${snap.normalizedStatus} (${formatFilledQty(snap.filledQuantity)} qty filled — verify position)."
            )
            return true
        }
        return false
    }

    private suspend fun awaitManualStepConfirmation() = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        synchronized(sessionLock) {
            manualPromptAckCallback = {
                if (cont.isActive) cont.resume(Unit)
            }
        }
        cont.invokeOnCancellation {
            synchronized(sessionLock) { manualPromptAckCallback = null }
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 60
        const val DEFAULT_RESTART_SETTLEMENT_MS = 1_500L
        val DEFAULT_STEP_AMOUNT: BigDecimal = BigDecimal("0.05")
        private val SUPPORTED_WALK_UP_ORDER_TYPES = setOf(
            OrderType.LIMIT,
            OrderType.NET_DEBIT,
            OrderType.NET_CREDIT
        )
        private val TERMINAL_NON_WORKING_STATUSES = setOf(
            "CANCELED",
            "CANCELLED",
            "REJECTED",
            "EXPIRED"
        )
    }
}
