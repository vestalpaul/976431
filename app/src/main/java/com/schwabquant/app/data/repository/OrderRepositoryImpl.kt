package com.schwabquant.app.data.repository

import com.schwabquant.app.core.quant.SchwabOrderPayloadBuilder
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.local.dao.AuditLogDao
import com.schwabquant.app.data.local.dao.ShadowLedgerDao
import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.data.local.entity.ShadowOrderEntity
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import com.schwabquant.app.data.remote.dto.OrderLegResponseDto
import com.schwabquant.app.data.remote.dto.OrderResponseDto
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStatusSnapshot
import com.schwabquant.app.domain.model.OrderSubmissionResult
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.ReplaceOrderOutcome
import com.schwabquant.app.domain.model.SchwabOrderStatuses
import com.schwabquant.app.domain.model.WorkingOrder
import com.schwabquant.app.domain.repository.OrderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.floor

/**
 * Authoritative Order Execution Repository enforcing the Hard Shadow-Mode Firewall:
 * - C-1: Enforces strict bidirectional `ticket.executionMode == activeMode` alignment (fail-closed),
 *   never rewrites `SHADOW_PAPER` into a live hash, re-verifies mode immediately before HTTP POST,
 *   and routes `cancelOrder` strictly by order execution domain.
 * - C-3: Queries 3 sequential 59-day GTC windows (177 days, fail-closed on any chunk error) and
 *   recursively traverses `childOrderStrategies` for live OCO brackets.
 * - C-5: Captures `201 Created` order IDs inside `NonCancellable` and auto-cancels if canceled mid-flight.
 * - M-2: Tightened missing-`Location` fallback ignoring stale `FILLED`/`CANCELED` orders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OrderRepositoryImpl(
    private val traderApi: SchwabTraderApi,
    private val shadowExecutionEngine: ShadowExecutionEngine,
    private val shadowLedgerDao: ShadowLedgerDao,
    private val auditLogDao: AuditLogDao,
    private val preferences: AppPreferencesDataStore,
    private val credentialVault: CredentialVault,
    private val payloadBuilder: SchwabOrderPayloadBuilder,
    private val nowProvider: () -> Long = System::currentTimeMillis
) : OrderRepository {

    @Inject
    constructor(
        traderApi: SchwabTraderApi,
        shadowExecutionEngine: ShadowExecutionEngine,
        shadowLedgerDao: ShadowLedgerDao,
        auditLogDao: AuditLogDao,
        preferences: AppPreferencesDataStore,
        credentialVault: CredentialVault,
        payloadBuilder: SchwabOrderPayloadBuilder
    ) : this(
        traderApi = traderApi,
        shadowExecutionEngine = shadowExecutionEngine,
        shadowLedgerDao = shadowLedgerDao,
        auditLogDao = auditLogDao,
        preferences = preferences,
        credentialVault = credentialVault,
        payloadBuilder = payloadBuilder,
        nowProvider = System::currentTimeMillis
    )

    private val liveWorkingOrdersState = MutableStateFlow<List<WorkingOrder>>(emptyList())
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun observeRecentOrders(mode: ExecutionMode): Flow<List<OrderSubmissionResult>> =
        when (mode) {
            ExecutionMode.SHADOW -> shadowLedgerDao.observeOrders().map { entities ->
                entities.map { entity ->
                    OrderSubmissionResult(
                        orderId = entity.orderId,
                        executionMode = ExecutionMode.SHADOW,
                        status = entity.status,
                        message = "${entity.orderType} ${entity.symbol} x${entity.quantity}",
                        submittedAtEpochMs = entity.submittedAtEpochMs,
                        fillPrice = entity.fillPrice?.toBigDecimalOrNull()
                    )
                }
            }

            ExecutionMode.LIVE -> liveWorkingOrdersState.map { list ->
                list.map { wo ->
                    OrderSubmissionResult(
                        orderId = wo.orderId,
                        executionMode = ExecutionMode.LIVE,
                        status = wo.status,
                        message = "${wo.orderType} ${wo.symbol} x${wo.quantity}",
                        submittedAtEpochMs = wo.submittedAtEpochMs,
                        fillPrice = wo.fillPrice?.toBigDecimalOrNull()
                    )
                }
            }
        }

    override fun observeWorkingOrders(): Flow<List<WorkingOrder>> =
        preferences.isLiveMode.flatMapLatest { isLive ->
            if (isLive) {
                liveWorkingOrdersState
            } else {
                shadowLedgerDao.observeOrders().map { rows ->
                    rows.map { row -> row.toWorkingOrder() }
                }
            }
        }

    override fun observeRecentAuditLogs(limit: Int): Flow<List<AuditLogEntity>> =
        auditLogDao.observeRecent(limit)

    override suspend fun getWorkingOrders(forceRefresh: Boolean): Result<List<WorkingOrder>> =
        safeResult {
            val isLiveMode = preferences.isLiveMode.first()
            if (!isLiveMode) {
                // Hard Firewall: Shadow Mode reads strictly from local Room
                return@safeResult shadowLedgerDao.getOrdersSnapshot().map { row ->
                    row.toWorkingOrder()
                }
            }

            val hash = resolveLiveAccountHash(null)
            val nowInstant = Instant.ofEpochMilli(nowProvider())
            val deduplicated = linkedMapOf<Long, OrderResponseDto>()

            // C-3 + M-4 Safeguard: 177-day lookback chunked into 3 sequential 59-day GTC-safe windows (fail-closed)
            // Include +60s clock-skew buffer on the newest window so device clock lag never hides new working orders.
            for (chunkIndex in (GTC_CHUNK_COUNT - 1) downTo 0) {
                val windowEnd = if (chunkIndex == 0) {
                    nowInstant.plusSeconds(WORKING_ORDERS_CLOCK_SKEW_BUFFER_SECONDS)
                } else {
                    nowInstant.minusSeconds(chunkIndex.toLong() * GTC_CHUNK_DAYS * SECONDS_PER_DAY)
                }
                val windowStart = nowInstant.minusSeconds((chunkIndex + 1L) * GTC_CHUNK_DAYS * SECONDS_PER_DAY)
                val chunkDtos = traderApi.getOrders(
                    accountHash = hash,
                    fromEnteredTime = SCHWAB_ISO_UTC.format(windowStart),
                    toEnteredTime = SCHWAB_ISO_UTC.format(windowEnd)
                )
                for (dto in chunkDtos) {
                    val id = dto.orderId ?: continue
                    deduplicated[id] = dto
                }
            }

            val mapped = deduplicated.values
                .filter { it.hasActiveWorkingStatus() }
                .mapNotNull { it.toWorkingOrder() }
                .sortedByDescending { it.submittedAtEpochMs }
            liveWorkingOrdersState.value = mapped
            mapped
        }

    override suspend fun getOrderStatus(accountHash: String, orderId: String): Result<OrderStatusSnapshot> =
        safeResult {
            val cleanOrderId = orderId.trim()
            if (cleanOrderId.startsWith(SHADOW_ORDER_PREFIX, ignoreCase = true)) {
                return@safeResult shadowExecutionEngine.getShadowOrderStatusSnapshot(cleanOrderId)
                    ?: error("Shadow order '$cleanOrderId' not found.")
            }

            val isLiveMode = preferences.isLiveMode.first()
            if (!isLiveMode) {
                return@safeResult shadowExecutionEngine.getShadowOrderStatusSnapshot(cleanOrderId)
                    ?: error("Shadow order '$cleanOrderId' not found.")
            }

            val hash = resolveLiveAccountHash(accountHash)
            val dto = traderApi.getOrder(hash, cleanOrderId)
            val rawStatus = dto.status?.trim()?.uppercase() ?: "UNKNOWN"
            val allLegs = dto.collectAllLegsRecursively()
            val totalQty = dto.quantity ?: allLegs.firstOrNull()?.quantity ?: 0.0
            val aggregatedFilled = dto.computeAggregatedFilledQuantity()
            val childHasFill = dto.hasAnyChildFillActivity()
            val childHasPartial = dto.hasAnyChildStatus("PARTIALLY_FILLED")
            val childHasFullFill = dto.hasAnyChildStatus("FILLED")
            val filledQty = when {
                childHasFill -> maxOf(aggregatedFilled, if (rawStatus == "FILLED" && totalQty > 0.0) totalQty else 1.0)
                aggregatedFilled > 0.0 -> aggregatedFilled
                rawStatus == "FILLED" -> totalQty
                else -> 0.0
            }
            val prelimRem = dto.remainingQuantity
                ?: (totalQty - filledQty).takeIf { totalQty > 0.0 }?.coerceAtLeast(0.0)
            val effectiveStatus = when {
                rawStatus in TERMINAL_ABORTED_OR_FILLED_STATUSES -> rawStatus
                childHasPartial -> "PARTIALLY_FILLED"
                childHasFullFill && rawStatus in SchwabOrderStatuses.ACTIVE_WORKING_STATUSES ->
                    if (prelimRem == null || prelimRem <= 0.0) "FILLED" else "PARTIALLY_FILLED"
                else -> rawStatus
            }
            val remQty: Double? = when {
                dto.remainingQuantity != null -> dto.remainingQuantity
                effectiveStatus == "FILLED" -> null
                effectiveStatus in setOf("CANCELED", "CANCELLED", "REJECTED", "EXPIRED") -> 0.0
                else -> (totalQty - filledQty).coerceAtLeast(0.0)
            }
            val resolvedOrderQty = (dto.quantity ?: allLegs.firstOrNull()?.quantity)?.takeIf { it > 0.0 }
            OrderStatusSnapshot(
                orderId = dto.orderId?.toString() ?: cleanOrderId,
                status = effectiveStatus,
                filledQuantity = filledQty,
                remainingQuantity = remQty,
                orderQuantity = resolvedOrderQty
            )
        }

    override suspend fun submitOrder(
        ticket: OrderTicket,
        risksAcknowledged: Boolean,
        overriddenRiskCodes: List<String>,
        simulateImmediateMidFill: Boolean
    ): Result<OrderSubmissionResult> {
        val now = nowProvider()
        val primarySymbol = ticket.legs.firstOrNull()?.symbol
            ?: ticket.childOrders.firstOrNull()?.legs?.firstOrNull()?.symbol

        if (!risksAcknowledged) {
            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = now,
                    category = "ORDER_BLOCKED_UNACKED_RISK",
                    severity = "WARNING",
                    symbol = primarySymbol,
                    executionMode = ticket.executionMode.name,
                    message = "Order rejected before routing: Soft-Gate risk dialog not acknowledged."
                )
            )
            return Result.failure(
                IllegalStateException("Pre-trade risk warning dialog must be explicitly acknowledged.")
            )
        }

        // C-1 Safeguard: Strict bidirectional mode alignment against authoritative DataStore
        val isLiveMode = preferences.isLiveMode.first()
        val activeMode = if (isLiveMode) ExecutionMode.LIVE else ExecutionMode.SHADOW
        if (ticket.executionMode != activeMode) {
            val mismatchMsg = "Order blocked: ticket targets ${ticket.executionMode.name} while active mode is ${activeMode.name}."
            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = now,
                    category = "ORDER_MODE_MISMATCH_BLOCKED",
                    severity = "CRITICAL",
                    symbol = primarySymbol,
                    executionMode = activeMode.name,
                    message = mismatchMsg
                )
            )
            return Result.failure(IllegalStateException(mismatchMsg))
        }

        if (overriddenRiskCodes.isNotEmpty()) {
            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = now,
                    category = "RISK_OVERRIDE_ACK",
                    severity = "WARNING",
                    symbol = primarySymbol,
                    executionMode = activeMode.name,
                    message = "User overrode pre-trade warning(s): ${overriddenRiskCodes.joinToString(", ")}",
                    metadataJson = overriddenRiskCodes.joinToString(",")
                )
            )
        }

        if (!isLiveMode) {
            val shadowTicket = ticket.copy(
                accountHash = SHADOW_ACCOUNT_HASH,
                executionMode = ExecutionMode.SHADOW
            )
            return safeResult {
                shadowExecutionEngine.simulateOrder(
                    ticket = shadowTicket,
                    immediateMidFill = simulateImmediateMidFill,
                    overriddenRiskCodes = overriddenRiskCodes
                )
            }
        }

        return try {
            val hash = resolveLiveAccountHash(ticket.accountHash)
            val liveTicket = ticket.copy(accountHash = hash, executionMode = ExecutionMode.LIVE)
            val dto = payloadBuilder.build(liveTicket)

            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = nowProvider(),
                    category = "LIVE_ORDER_ATTEMPT",
                    severity = "INFO",
                    symbol = primarySymbol,
                    executionMode = ExecutionMode.LIVE.name,
                    message = "Submitting LIVE ${dto.complexOrderStrategyType ?: dto.orderType ?: dto.orderStrategyType} order."
                )
            )

            // C-1 Final TOCTOU Check after suspension and right before Schwab HTTP POST
            if (!preferences.isLiveMode.first()) {
                val abortMsg = "Aborted LIVE order before dispatch: execution mode flipped to SHADOW."
                auditLogDao.insert(
                    AuditLogEntity(
                        timestampEpochMs = nowProvider(),
                        category = "LIVE_ORDER_ABORTED_MODE_CHANGED",
                        severity = "CRITICAL",
                        symbol = primarySymbol,
                        executionMode = ExecutionMode.SHADOW.name,
                        message = abortMsg
                    )
                )
                error(abortMsg)
            }

            val attemptStartedAt = nowProvider()

            // C-5 Safeguard: Execute POST & capture orderId inside NonCancellable so mid-flight cancels never orphan live orders
            val (resolvedOrderId, locationHeader, submittedAt) = withContext(NonCancellable) {
                val response = traderApi.placeOrder(hash, dto)
                if (!response.isSuccessful) {
                    val errBody = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
                    error("Schwab order rejected with HTTP ${response.code()}: ${response.message()} $errBody".trim())
                }

                val locHeader = response.headers()["Location"]
                val parsedLocationId = locHeader
                    ?.trimEnd('/')
                    ?.substringAfterLast("/")
                    ?.takeIf { it.isNotBlank() }
                val doneAt = nowProvider()

                val orderId = parsedLocationId ?: resolveFallbackOrderId(
                    accountHash = hash,
                    liveTicket = liveTicket,
                    attemptStartedAt = attemptStartedAt,
                    submittedAt = doneAt
                )
                Triple(orderId, locHeader, doneAt)
            }

            if (!currentCoroutineContext().isActive) {
                if (!resolvedOrderId.isNullOrBlank()) {
                    withContext(NonCancellable) {
                        runCatching {
                            cancelLiveOrderInternal(
                                accountHash = hash,
                                cleanId = resolvedOrderId,
                                bypassModeFirewall = true
                            )
                        }
                        runCatching {
                            auditLogDao.insert(
                                AuditLogEntity(
                                    timestampEpochMs = nowProvider(),
                                    category = "LIVE_ORDER_CANCELED_AFTER_MIDFLIGHT_CANCEL",
                                    severity = "WARNING",
                                    symbol = primarySymbol,
                                    executionMode = ExecutionMode.LIVE.name,
                                    message = "Canceled live order $resolvedOrderId immediately after caller coroutine canceled mid-flight."
                                )
                            )
                        }
                    }
                }
                throw CancellationException("Order submission canceled mid-flight.")
            }

            val primaryAssetType = (ticket.legs.firstOrNull()?.category
                ?: ticket.childOrders.firstOrNull()?.legs?.firstOrNull()?.category
                ?: AssetCategory.EQUITY).name

            if (resolvedOrderId.isNullOrBlank()) {
                auditLogDao.insert(
                    AuditLogEntity(
                        timestampEpochMs = submittedAt,
                        category = "LIVE_ORDER_UNTRACKED_NO_LOCATION",
                        severity = "CRITICAL",
                        symbol = primarySymbol,
                        executionMode = ExecutionMode.LIVE.name,
                        message = "Live order accepted (201 Created) without Location header and orderId could not be resolved — verify in Schwab."
                    )
                )
                return Result.success(
                    OrderSubmissionResult(
                        orderId = "",
                        executionMode = ExecutionMode.LIVE,
                        status = "UNTRACKED",
                        message = "Live order accepted (201 Created) without Location header — verify working orders in Schwab.",
                        submittedAtEpochMs = submittedAt
                    )
                )
            }

            val effectiveLegs = liveTicket.legs.ifEmpty { liveTicket.childOrders.flatMap { it.legs } }
            val normalizedSymbols = effectiveLegs.map { SchwabSymbolNormalizer.toDisplaySymbol(it.symbol) }.toSet()
            val hasOption = effectiveLegs.any { it.category == AssetCategory.OPTION }
            val hasEquity = effectiveLegs.any { it.category == AssetCategory.EQUITY }

            val wo = WorkingOrder(
                orderId = resolvedOrderId,
                executionMode = ExecutionMode.LIVE,
                symbol = primarySymbol?.let(SchwabSymbolNormalizer::toDisplaySymbol) ?: "MULTI-LEG",
                underlyingSymbol = primarySymbol?.substringBefore(" ")
                    ?.let(SchwabSymbolNormalizer::toDisplaySymbol) ?: "",
                strategyType = dto.complexOrderStrategyType ?: dto.orderStrategyType,
                orderType = dto.orderType ?: dto.orderStrategyType,
                session = dto.session ?: "NORMAL",
                duration = dto.duration ?: "GOOD_TILL_CANCEL",
                status = "WORKING",
                limitPrice = dto.price,
                stopPrice = dto.stopPrice,
                fillPrice = null,
                quantity = effectiveLegs.minOfOrNull { it.quantity } ?: 1,
                legsSummary = effectiveLegs.joinToString(" | ") { "${it.side.name} ${it.quantity}x ${it.symbol}" },
                submittedAtEpochMs = submittedAt,
                filledAtEpochMs = null,
                assetType = primaryAssetType,
                legSymbols = normalizedSymbols,
                hasOptionLegs = hasOption,
                hasEquityLegs = hasEquity
            )
            liveWorkingOrdersState.value = listOf(wo) + liveWorkingOrdersState.value

            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = submittedAt,
                    category = "LIVE_ORDER_SUBMIT",
                    severity = "WARNING",
                    symbol = primarySymbol,
                    executionMode = ExecutionMode.LIVE.name,
                    message = "Submitted LIVE Schwab order $resolvedOrderId (Location: ${locationHeader ?: "resolved-from-orders"})."
                )
            )

            Result.success(
                OrderSubmissionResult(
                    orderId = resolvedOrderId,
                    executionMode = ExecutionMode.LIVE,
                    status = "SUBMITTED",
                    message = "Live Schwab order $resolvedOrderId accepted (201 Created).",
                    submittedAtEpochMs = submittedAt
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            runCatching {
                auditLogDao.insert(
                    AuditLogEntity(
                        timestampEpochMs = nowProvider(),
                        category = "LIVE_ORDER_ERROR",
                        severity = "CRITICAL",
                        symbol = primarySymbol,
                        executionMode = ExecutionMode.LIVE.name,
                        message = err.message ?: "Live Schwab order submission failed."
                    )
                )
            }
            Result.failure(err)
        }
    }

    /**
     * C-2 Domain & Mode-Aware Cancellation:
     * Routes SHADOW orders strictly to local Room. LIVE HTTP DELETE requires active `isLiveMode == true`
     * unless explicitly called from `submitOrder()`'s internal mid-flight NonCancellable orphan cleanup.
     */
    override suspend fun cancelOrder(
        accountHash: String,
        orderId: String,
        mode: ExecutionMode
    ): Result<Unit> = safeResult {
        val cleanId = orderId.trim()
        require(cleanId.isNotBlank()) { "Cannot cancel blank orderId." }

        val isShadowDomain = mode == ExecutionMode.SHADOW ||
            cleanId.startsWith(SHADOW_ORDER_PREFIX, ignoreCase = true)

        if (isShadowDomain) {
            shadowExecutionEngine.cancelShadowOrder(cleanId).getOrThrow()
            return@safeResult
        }

        cancelLiveOrderInternal(
            accountHash = accountHash,
            cleanId = cleanId,
            bypassModeFirewall = false
        )
    }

    private suspend fun cancelLiveOrderInternal(
        accountHash: String,
        cleanId: String,
        bypassModeFirewall: Boolean
    ) {
        if (!bypassModeFirewall && !preferences.isLiveMode.first()) {
            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = nowProvider(),
                    category = "ORDER_MODE_MISMATCH_BLOCKED",
                    severity = "CRITICAL",
                    symbol = null,
                    executionMode = ExecutionMode.SHADOW.name,
                    message = "Blocked LIVE order cancel ($cleanId) while active execution mode is SHADOW."
                )
            )
            error("Order cancel blocked: LIVE cancel attempted while active mode is SHADOW.")
        }

        val hash = resolveLiveAccountHash(accountHash)
        val response = traderApi.cancelOrder(hash, cleanId)
        if (!response.isSuccessful) {
            error("Cancel failed with HTTP ${response.code()}: ${response.message()}")
        }
        liveWorkingOrdersState.value = liveWorkingOrdersState.value.map { wo ->
            if (wo.orderId == cleanId) wo.copy(status = "CANCELED") else wo
        }
        auditLogDao.insert(
            AuditLogEntity(
                timestampEpochMs = nowProvider(),
                category = "LIVE_ORDER_CANCEL",
                severity = "INFO",
                symbol = null,
                executionMode = ExecutionMode.LIVE.name,
                message = "Canceled live Schwab order $cleanId."
            )
        )
    }

    /**
     * Safe Cancel -> Wait 1,500ms -> Status Verify -> Replace flow preventing double-fills
     * and mode-flip TOCTOU races across suspension points.
     */
    override suspend fun replaceOrder(
        accountHash: String,
        oldOrderId: String,
        replacementTicket: OrderTicket,
        risksAcknowledged: Boolean,
        overriddenRiskCodes: List<String>,
        cancelToReplaceDelayMs: Long,
        allowTerminalOrPartialReprice: Boolean
    ): Result<ReplaceOrderOutcome> = safeResult {
        val isLiveAtStart = preferences.isLiveMode.first()
        val modeAtStart = if (isLiveAtStart) ExecutionMode.LIVE else ExecutionMode.SHADOW
        val primarySym = replacementTicket.legs.firstOrNull()?.symbol
            ?: replacementTicket.childOrders.firstOrNull()?.legs?.firstOrNull()?.symbol

        if (replacementTicket.executionMode != modeAtStart) {
            val mismatchMsg = "Replace aborted: replacement ticket mode ${replacementTicket.executionMode.name} does not match active ${modeAtStart.name}."
            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = nowProvider(),
                    category = "REPLACE_ABORTED_MODE_MISMATCH",
                    severity = "CRITICAL",
                    symbol = primarySym,
                    executionMode = modeAtStart.name,
                    message = mismatchMsg
                )
            )
            error(mismatchMsg)
        }

        // HIGH-1 Safeguard: Strict order-ID domain alignment (never promote SHADOW-* to LIVE or demote LIVE IDs to SHADOW)
        val cleanOldId = oldOrderId.trim()
        val isOldShadowId = cleanOldId.startsWith("SHADOW-", ignoreCase = true)
        if ((modeAtStart == ExecutionMode.LIVE && isOldShadowId) ||
            (modeAtStart == ExecutionMode.SHADOW && !isOldShadowId)
        ) {
            val domainMsg = "Replace blocked: cannot replace order '$cleanOldId' inside ${modeAtStart.name} Mode."
            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = nowProvider(),
                    category = "ORDER_MODE_MISMATCH_BLOCKED",
                    severity = "CRITICAL",
                    symbol = primarySym,
                    executionMode = modeAtStart.name,
                    message = domainMsg
                )
            )
            error(domainMsg)
        }

        val hash = if (isLiveAtStart) resolveLiveAccountHash(accountHash) else SHADOW_ACCOUNT_HASH

        // C-2 Safeguard: Re-verify execution mode immediately before cancel
        verifyModeUnchangedOrAbort(isLiveAtStart, primarySym, "before cancel")

        // Attempt cancel (ignore expected rejection if order filled/terminated right as cancel arrived)
        cancelOrder(hash, oldOrderId, modeAtStart)

        if (cancelToReplaceDelayMs > 0L) {
            delay(cancelToReplaceDelayMs)
        }

        // C-2 Safeguard: Re-verify execution mode immediately before status poll & replacement submit
        verifyModeUnchangedOrAbort(isLiveAtStart, primarySym, "before status verify")

        val statusSnapshot = getOrderStatus(hash, oldOrderId).getOrThrow()
        val confirmedStatus = statusSnapshot.normalizedStatus

        if (confirmedStatus == "FILLED") {
            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = nowProvider(),
                    category = "REPLACE_ABORTED_PRIOR_FILLED",
                    severity = "WARNING",
                    symbol = primarySym,
                    executionMode = modeAtStart.name,
                    message = "Replace aborted: prior order $oldOrderId already $confirmedStatus."
                )
            )
            return@safeResult ReplaceOrderOutcome.AbortedAlreadyFilled(
                priorOrderId = oldOrderId,
                priorStatus = confirmedStatus,
                filledQuantity = statusSnapshot.filledQuantity,
                remainingQuantity = statusSnapshot.remainingQuantity ?: 0.0
            )
        }

        val effectiveTicket = if (allowTerminalOrPartialReprice) {
            // H-3 + C-4 Sentinel Emergency Re-Price: allows CANCELED/REJECTED/EXPIRED, caps by prior order unfilled
            // remainder (never double-subtracting), and clamps against live directional equity shares.
            if (confirmedStatus !in SENTINEL_REPRICEABLE_STATUSES) {
                auditLogDao.insert(
                    AuditLogEntity(
                        timestampEpochMs = nowProvider(),
                        category = "REPLACE_ABORTED_NOT_CANCELED",
                        severity = "WARNING",
                        symbol = primarySym,
                        executionMode = modeAtStart.name,
                        message = "Escalated re-price aborted: prior order $oldOrderId remained '$confirmedStatus'."
                    )
                )
                return@safeResult ReplaceOrderOutcome.AbortedNotCanceled(oldOrderId, confirmedStatus)
            }
            val requestedQty = replacementTicket.legs.firstOrNull()?.quantity
                ?: replacementTicket.childOrders.firstOrNull()?.legs?.firstOrNull()?.quantity
                ?: 0
            val priorUnfilledCap = statusSnapshot.orderQuantity?.let { priorQty ->
                floor(maxOf(0.0, priorQty - statusSnapshot.filledQuantity)).toInt()
            }
            if (priorUnfilledCap != null && priorUnfilledCap <= 0) {
                auditLogDao.insert(
                    AuditLogEntity(
                        timestampEpochMs = nowProvider(),
                        category = "REPLACE_ABORTED_PRIOR_FILLED",
                        severity = "WARNING",
                        symbol = primarySym,
                        executionMode = modeAtStart.name,
                        message = "Escalated re-price skipped: prior order $oldOrderId already filled ${statusSnapshot.filledQuantity} of ${statusSnapshot.orderQuantity}."
                    )
                )
                return@safeResult ReplaceOrderOutcome.AbortedAlreadyFilled(
                    priorOrderId = oldOrderId,
                    priorStatus = confirmedStatus,
                    filledQuantity = statusSnapshot.filledQuantity,
                    remainingQuantity = 0.0
                )
            }

            var safeQty = if (priorUnfilledCap != null) {
                minOf(requestedQty, priorUnfilledCap)
            } else {
                requestedQty
            }

            val singleEquityExitLeg = if (
                isLiveAtStart &&
                replacementTicket.strategyType == "SINGLE" &&
                replacementTicket.legs.size == 1 &&
                replacementTicket.legs.single().category == AssetCategory.EQUITY &&
                (replacementTicket.legs.single().side == OrderSide.SELL ||
                    replacementTicket.legs.single().side == OrderSide.BUY_TO_COVER)
            ) {
                replacementTicket.legs.single()
            } else {
                null
            }

            if (singleEquityExitLeg != null) {
                val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(singleEquityExitLeg.symbol)
                val wrapper = traderApi.getAccount(hash, fields = "positions")
                val account = wrapper.securitiesAccount
                    ?: error("Escalated live re-price aborted: Schwab returned empty securitiesAccount for $displaySym.")
                val netQty = account.positions
                    .filter { pos ->
                        !pos.instrument?.assetType.equals(AssetCategory.OPTION.name, ignoreCase = true) &&
                            SchwabSymbolNormalizer.toDisplaySymbol(pos.instrument?.symbol.orEmpty()) == displaySym
                    }
                    .sumOf { pos -> (pos.longQuantity ?: 0.0) - (pos.shortQuantity ?: 0.0) }
                val availableWholeShares = when (singleEquityExitLeg.side) {
                    OrderSide.SELL -> if (netQty > 0.0) floor(netQty).toInt() else 0
                    OrderSide.BUY_TO_COVER -> if (netQty < 0.0) floor(abs(netQty)).toInt() else 0
                    else -> 0
                }
                if (availableWholeShares <= 0) {
                    val noPosMsg = "Escalated live re-price aborted: no open directional equity position on $displaySym (openQty=$netQty)."
                    auditLogDao.insert(
                        AuditLogEntity(
                            timestampEpochMs = nowProvider(),
                            category = "REPLACE_ABORTED_NO_POSITION",
                            severity = "WARNING",
                            symbol = displaySym,
                            executionMode = ExecutionMode.LIVE.name,
                            message = noPosMsg
                        )
                    )
                    error(noPosMsg)
                }
                safeQty = minOf(safeQty, availableWholeShares)
            }

            if (safeQty <= 0) {
                auditLogDao.insert(
                    AuditLogEntity(
                        timestampEpochMs = nowProvider(),
                        category = "REPLACE_ABORTED_PRIOR_FILLED",
                        severity = "WARNING",
                        symbol = primarySym,
                        executionMode = modeAtStart.name,
                        message = "Escalated re-price skipped: zero remaining quantity ($safeQty) for prior order $oldOrderId."
                    )
                )
                return@safeResult ReplaceOrderOutcome.AbortedAlreadyFilled(
                    priorOrderId = oldOrderId,
                    priorStatus = confirmedStatus,
                    filledQuantity = statusSnapshot.filledQuantity,
                    remainingQuantity = 0.0
                )
            }

            if (safeQty != requestedQty) {
                replacementTicket.copy(
                    legs = replacementTicket.legs.map { it.copy(quantity = safeQty) }
                )
            } else {
                replacementTicket
            }
        } else {
            // Strict Walk-Up path: fail closed if ANY partial fill occurred or order not CANCELED
            if (confirmedStatus == "PARTIALLY_FILLED" || statusSnapshot.filledQuantity > 0.0) {
                val reportedStatus = if (confirmedStatus == "CANCELED" || confirmedStatus == "CANCELLED") {
                    "PARTIALLY_FILLED_CANCELED"
                } else {
                    confirmedStatus
                }
                auditLogDao.insert(
                    AuditLogEntity(
                        timestampEpochMs = nowProvider(),
                        category = "REPLACE_ABORTED_PARTIAL_FILL",
                        severity = "CRITICAL",
                        symbol = primarySym,
                        executionMode = modeAtStart.name,
                        message = "Replace aborted: prior order $oldOrderId has partial fill (filled=${statusSnapshot.filledQuantity}, remaining=${statusSnapshot.remainingQuantity}, status=$confirmedStatus)."
                    )
                )
                return@safeResult ReplaceOrderOutcome.AbortedAlreadyFilled(
                    priorOrderId = oldOrderId,
                    priorStatus = reportedStatus,
                    filledQuantity = statusSnapshot.filledQuantity,
                    remainingQuantity = statusSnapshot.remainingQuantity ?: 0.0
                )
            }

            if (confirmedStatus != "CANCELED" && confirmedStatus != "CANCELLED") {
                auditLogDao.insert(
                    AuditLogEntity(
                        timestampEpochMs = nowProvider(),
                        category = "REPLACE_ABORTED_NOT_CANCELED",
                        severity = "WARNING",
                        symbol = primarySym,
                        executionMode = modeAtStart.name,
                        message = "Walk-Up aborted: prior order $oldOrderId remained '$confirmedStatus' after cancel."
                    )
                )
                return@safeResult ReplaceOrderOutcome.AbortedNotCanceled(oldOrderId, confirmedStatus)
            }
            replacementTicket
        }

        // C-1/C-2 Safeguard: Final execution mode check & cancellation check right before replacement submit
        verifyModeUnchangedOrAbort(isLiveAtStart, primarySym, "before replacement submit")
        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Replace order canceled before replacement submit.")
        }

        val replacementResult = submitOrder(
            ticket = effectiveTicket,
            risksAcknowledged = risksAcknowledged,
            overriddenRiskCodes = overriddenRiskCodes,
            simulateImmediateMidFill = false
        ).getOrThrow()

        ReplaceOrderOutcome.Replaced(replacementResult)
    }

    private suspend fun verifyModeUnchangedOrAbort(
        expectedLive: Boolean,
        symbol: String?,
        stage: String
    ) {
        val currentLive = preferences.isLiveMode.first()
        if (currentLive != expectedLive) {
            val flipMsg = "Replace aborted: execution mode changed $stage."
            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = nowProvider(),
                    category = "REPLACE_ABORTED_MODE_CHANGED",
                    severity = "CRITICAL",
                    symbol = symbol,
                    executionMode = if (currentLive) ExecutionMode.LIVE.name else ExecutionMode.SHADOW.name,
                    message = flipMsg
                )
            )
            error(flipMsg)
        }
    }

    override suspend fun simulateShadowFill(orderId: String): Result<OrderSubmissionResult> {
        val isLiveMode = preferences.isLiveMode.first()
        if (isLiveMode) {
            return Result.failure(IllegalStateException("Simulate Fill is only available in Shadow Mode."))
        }
        return shadowExecutionEngine.simulateFillOrder(orderId)
    }

    private suspend fun resolveFallbackOrderId(
        accountHash: String,
        liveTicket: OrderTicket,
        attemptStartedAt: Long,
        submittedAt: Long
    ): String? = runCatching {
        val fromInstant = Instant.ofEpochMilli(attemptStartedAt - FALLBACK_QUERY_WINDOW_MS)
        val toInstant = Instant.ofEpochMilli(submittedAt + FALLBACK_CLOCK_SKEW_MS)
        val recentDtos = traderApi.getOrders(
            accountHash = accountHash,
            fromEnteredTime = SCHWAB_ISO_UTC.format(fromInstant),
            toEnteredTime = SCHWAB_ISO_UTC.format(toInstant)
        )

        val expectedLegs = liveTicket.legs.ifEmpty { liveTicket.childOrders.flatMap { it.legs } }
        val expectedSymbols = expectedLegs
            .map { SchwabSymbolNormalizer.toDisplaySymbol(it.symbol) }
            .filter { it.isNotBlank() }
            .toSet()
        val expectedQty = expectedLegs.minOfOrNull { it.quantity }
        val expectedSides = expectedLegs.map { it.side.name.uppercase() }.toSet()

        // M-2 Safeguard: Filter strictly to active working orders entered around submission time matching symbols/qty/side
        val candidates = recentDtos.filter { candidate ->
            val id = candidate.orderId ?: return@filter false
            if (id <= 0L || !candidate.hasActiveWorkingStatus()) return@filter false

            val enteredMs = parseSchwabTimestampMs(candidate.enteredTime) ?: return@filter false
            if (enteredMs < attemptStartedAt - FALLBACK_CLOCK_SKEW_MS) return@filter false

            val candidateLegs = candidate.collectAllLegsRecursively()
            if (candidateLegs.isEmpty()) return@filter false

            val candidateSymbols = candidateLegs
                .mapNotNull { it.instrument?.symbol?.takeIf { s -> s.isNotBlank() } }
                .map { SchwabSymbolNormalizer.toDisplaySymbol(it) }
                .toSet()
            if (expectedSymbols.isNotEmpty() && candidateSymbols != expectedSymbols) return@filter false

            if (expectedQty != null) {
                val qtyMatches = (candidate.quantity?.toInt() == expectedQty) ||
                    candidateLegs.any { it.quantity?.toInt() == expectedQty }
                if (!qtyMatches) return@filter false
            }

            if (expectedSides.isNotEmpty()) {
                val sideMatches = candidateLegs.any { leg ->
                    leg.instruction?.trim()?.uppercase() in expectedSides
                }
                if (!sideMatches) return@filter false
            }

            true
        }

        // Only bind when unambiguous (single matching working order); otherwise fail safe as UNTRACKED
        if (candidates.size == 1) {
            candidates.single().orderId?.toString()
        } else {
            null
        }
    }.getOrNull()

    private fun resolveLiveAccountHash(explicitHash: String?): String {
        val cleaned = explicitHash?.trim().orEmpty()
        // C-1 Safeguard: NEVER rewrite SHADOW_PAPER into a live brokerage account hash
        if (cleaned.equals(SHADOW_ACCOUNT_HASH, ignoreCase = true)) {
            error("Refusing to resolve SHADOW_PAPER account hash for a live Schwab brokerage operation.")
        }
        if (cleaned.isNotEmpty()) return cleaned
        return credentialVault.getPrimaryAccountHash()
            ?: error("No connected Schwab primary account hash in encrypted vault.")
    }

    private fun ShadowOrderEntity.toWorkingOrder(): WorkingOrder {
        val extracted = extractShadowLegMetadata(legsJson, symbol, strategyType)
        return WorkingOrder(
            orderId = orderId,
            executionMode = ExecutionMode.SHADOW,
            symbol = SchwabSymbolNormalizer.toDisplaySymbol(symbol),
            underlyingSymbol = SchwabSymbolNormalizer.toDisplaySymbol(symbol.substringBefore(" ")),
            strategyType = strategyType,
            orderType = orderType,
            session = session,
            duration = duration,
            status = status,
            limitPrice = limitPrice,
            stopPrice = stopPrice,
            fillPrice = fillPrice,
            quantity = quantity,
            legsSummary = symbol,
            submittedAtEpochMs = submittedAtEpochMs,
            filledAtEpochMs = filledAtEpochMs,
            assetType = extracted.assetType,
            legSymbols = extracted.legSymbols,
            hasOptionLegs = extracted.hasOptionLegs,
            hasEquityLegs = extracted.hasEquityLegs
        )
    }

    private fun extractShadowLegMetadata(
        legsJson: String,
        fallbackSymbol: String,
        strategyType: String
    ): ShadowLegMeta {
        val parsedArray = runCatching { lenientJson.parseToJsonElement(legsJson) as? JsonArray }.getOrNull()
        if (parsedArray != null && parsedArray.isNotEmpty()) {
            val symbols = linkedSetOf<String>()
            var hasOpt = strategyType.uppercase() in MULTI_LEG_OPTION_STRATEGIES
            var hasEq = false
            parsedArray.forEach { elem ->
                val obj = elem as? JsonObject ?: return@forEach
                val rawSym = (obj["symbol"] as? JsonPrimitive)?.content?.trim().orEmpty()
                val rawType = (obj["assetType"] as? JsonPrimitive)?.content?.trim()?.uppercase().orEmpty()
                if (rawSym.isNotBlank()) {
                    symbols += SchwabSymbolNormalizer.toDisplaySymbol(rawSym)
                }
                val isOcc = isOccOptionSymbol(rawSym)
                if (rawType == AssetCategory.OPTION.name || isOcc) {
                    hasOpt = true
                } else if (rawType == AssetCategory.EQUITY.name || rawSym.isNotBlank()) {
                    hasEq = true
                }
            }
            val assetType = if (hasOpt) AssetCategory.OPTION.name else AssetCategory.EQUITY.name
            return ShadowLegMeta(
                assetType = assetType,
                legSymbols = symbols.ifEmpty { setOf(SchwabSymbolNormalizer.toDisplaySymbol(fallbackSymbol)) },
                hasOptionLegs = hasOpt,
                hasEquityLegs = hasEq
            )
        }

        val isOpt = isOccOptionSymbol(fallbackSymbol) || strategyType.uppercase() in MULTI_LEG_OPTION_STRATEGIES
        val clean = SchwabSymbolNormalizer.toDisplaySymbol(fallbackSymbol)
        return ShadowLegMeta(
            assetType = if (isOpt) AssetCategory.OPTION.name else AssetCategory.EQUITY.name,
            legSymbols = if (clean.isNotBlank()) setOf(clean) else emptySet(),
            hasOptionLegs = isOpt,
            hasEquityLegs = !isOpt
        )
    }

    private data class ShadowLegMeta(
        val assetType: String,
        val legSymbols: Set<String>,
        val hasOptionLegs: Boolean,
        val hasEquityLegs: Boolean
    )

    private fun OrderResponseDto.collectAllLegsRecursively(): List<OrderLegResponseDto> =
        orderLegCollection + childOrderStrategies.orEmpty().flatMap { it.collectAllLegsRecursively() }

    private fun OrderResponseDto.hasActiveWorkingStatus(): Boolean {
        val own = status?.trim()?.uppercase()
        if (own in SchwabOrderStatuses.ACTIVE_WORKING_STATUSES) return true
        return childOrderStrategies.orEmpty().any { it.hasActiveWorkingStatus() }
    }

    private fun OrderResponseDto.effectiveActiveStatus(): String {
        val own = status?.trim()?.uppercase()
        if (own in SchwabOrderStatuses.ACTIVE_WORKING_STATUSES) return own!!
        val childStatus = childOrderStrategies.orEmpty()
            .firstNotNullOfOrNull { child ->
                child.effectiveActiveStatus().takeIf { it in SchwabOrderStatuses.ACTIVE_WORKING_STATUSES }
            }
        return childStatus ?: own ?: "WORKING"
    }

    private fun OrderResponseDto.computeAggregatedFilledQuantity(): Double {
        val ownStatus = status?.trim()?.uppercase().orEmpty()
        val fallbackOwn = if (ownStatus in CHILD_FILL_STATUSES) {
            quantity ?: orderLegCollection.firstOrNull()?.quantity ?: 0.0
        } else {
            0.0
        }
        val ownFilled = filledQuantity ?: fallbackOwn
        val childrenFilled = childOrderStrategies.orEmpty().sumOf { it.computeAggregatedFilledQuantity() }
        return maxOf(ownFilled, childrenFilled)
    }

    private fun OrderResponseDto.hasAnyChildFillActivity(): Boolean =
        childOrderStrategies.orEmpty().any { child ->
            val st = child.status?.trim()?.uppercase().orEmpty()
            (child.filledQuantity ?: 0.0) > 0.0 ||
                st in CHILD_FILL_STATUSES ||
                child.hasAnyChildFillActivity()
        }

    private fun OrderResponseDto.hasAnyChildStatus(targetStatus: String): Boolean =
        childOrderStrategies.orEmpty().any { child ->
            child.status?.trim()?.uppercase() == targetStatus ||
                child.hasAnyChildStatus(targetStatus)
        }

    private fun OrderResponseDto.hasComplexStrategyRecursively(): Boolean =
        !complexOrderStrategyType.isNullOrBlank() ||
            childOrderStrategies.orEmpty().any { it.hasComplexStrategyRecursively() }

    private fun OrderResponseDto.toWorkingOrder(): WorkingOrder? {
        val idStr = orderId?.toString() ?: return null
        val allLegs = collectAllLegsRecursively()
        val firstLeg = allLegs.firstOrNull()

        val displaySymbols = allLegs
            .mapNotNull { leg ->
                leg.instrument?.symbol?.takeIf { it.isNotBlank() }?.let { raw ->
                    if (isOccOptionSymbol(raw)) raw else SchwabSymbolNormalizer.toDisplaySymbol(raw)
                }
            }
            .toSet()

        val hasOptionLegs = hasComplexStrategyRecursively() || allLegs.any { leg ->
            val rawSym = leg.instrument?.symbol.orEmpty()
            leg.instrument?.assetType.equals(AssetCategory.OPTION.name, ignoreCase = true) ||
                isOccOptionSymbol(rawSym)
        }
        val hasEquityLegs = allLegs.any { leg ->
            val rawSym = leg.instrument?.symbol.orEmpty()
            val isOpt = leg.instrument?.assetType.equals(AssetCategory.OPTION.name, ignoreCase = true) ||
                isOccOptionSymbol(rawSym)
            !isOpt && rawSym.isNotBlank()
        }

        val primarySym = when {
            displaySymbols.size == 1 -> displaySymbols.single()
            firstLeg?.instrument?.symbol != null -> {
                val raw = firstLeg.instrument.symbol
                if (isOccOptionSymbol(raw)) raw else SchwabSymbolNormalizer.toDisplaySymbol(raw)
            }
            else -> "MULTI-LEG"
        }
        val underlying = firstLeg?.instrument?.underlyingSymbol
            ?.takeIf { it.isNotBlank() }
            ?.let(SchwabSymbolNormalizer::toDisplaySymbol)
            ?: primarySym.substringBefore(" ")
        val resolvedAssetType = if (hasOptionLegs) AssetCategory.OPTION.name else AssetCategory.EQUITY.name
        val enteredMs = parseSchwabTimestampMs(enteredTime) ?: nowProvider()
        val closedMs = parseSchwabTimestampMs(closeTime)

        val resolvedLimit = price
            ?: childOrderStrategies.orEmpty().firstNotNullOfOrNull { it.price }
        val resolvedStop = stopPrice
            ?: childOrderStrategies.orEmpty().firstNotNullOfOrNull { it.stopPrice }
        val resolvedSession = session
            ?: childOrderStrategies.orEmpty().firstNotNullOfOrNull { it.session }
            ?: "NORMAL"
        val resolvedDuration = duration
            ?: childOrderStrategies.orEmpty().firstNotNullOfOrNull { it.duration }
            ?: "DAY"
        val resolvedOrderType = orderType
            ?: childOrderStrategies.orEmpty().firstNotNullOfOrNull { it.orderType }
            ?: orderStrategyType
            ?: "LIMIT"

        return WorkingOrder(
            orderId = idStr,
            executionMode = ExecutionMode.LIVE,
            symbol = primarySym,
            underlyingSymbol = underlying,
            strategyType = complexOrderStrategyType ?: orderStrategyType ?: "SINGLE",
            orderType = resolvedOrderType,
            session = resolvedSession,
            duration = resolvedDuration,
            status = effectiveActiveStatus(),
            limitPrice = resolvedLimit?.let { String.format(Locale.US, "%.2f", it) },
            stopPrice = resolvedStop?.let { String.format(Locale.US, "%.2f", it) },
            fillPrice = null,
            quantity = (quantity ?: firstLeg?.quantity ?: 1.0).toInt(),
            legsSummary = allLegs.joinToString(" | ") {
                "${it.instruction ?: ""} ${(it.quantity ?: 1.0).toInt()}x ${it.instrument?.symbol ?: ""}".trim()
            },
            submittedAtEpochMs = enteredMs,
            filledAtEpochMs = closedMs,
            assetType = resolvedAssetType,
            legSymbols = displaySymbols,
            hasOptionLegs = hasOptionLegs,
            hasEquityLegs = hasEquityLegs
        )
    }

    private fun parseSchwabTimestampMs(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text, SCHWAB_OFFSET_FORMATTER_MS).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text, SCHWAB_OFFSET_FORMATTER).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun isOccOptionSymbol(symbol: String): Boolean =
        symbol.length == 21 && (symbol[12] == 'C' || symbol[12] == 'P')

    private inline fun <T> safeResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }

    companion object {
        private const val SHADOW_ACCOUNT_HASH = "SHADOW_PAPER"
        private const val SHADOW_ORDER_PREFIX = "SHADOW-"
        private const val GTC_CHUNK_DAYS = 59L
        private const val GTC_CHUNK_COUNT = 3
        private const val SECONDS_PER_DAY = 86_400L
        private const val WORKING_ORDERS_CLOCK_SKEW_BUFFER_SECONDS = 60L
        private const val FALLBACK_QUERY_WINDOW_MS = 300_000L
        private const val FALLBACK_CLOCK_SKEW_MS = 5_000L

        private val SCHWAB_ISO_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
        private val SCHWAB_OFFSET_FORMATTER_MS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        private val SCHWAB_OFFSET_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")

        private val CHILD_FILL_STATUSES = setOf("FILLED", "PARTIALLY_FILLED")
        private val TERMINAL_ABORTED_OR_FILLED_STATUSES = setOf(
            "FILLED",
            "CANCELED",
            "CANCELLED",
            "REJECTED",
            "EXPIRED"
        )
        private val SENTINEL_REPRICEABLE_STATUSES = setOf(
            "CANCELED",
            "CANCELLED",
            "REJECTED",
            "EXPIRED"
        )
        private val MULTI_LEG_OPTION_STRATEGIES = setOf(
            "DIAGONAL",
            "VERTICAL",
            "CALENDAR",
            "IRON_CONDOR"
        )
    }
}
