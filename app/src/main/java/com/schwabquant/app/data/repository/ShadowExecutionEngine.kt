package com.schwabquant.app.data.repository

import androidx.room.withTransaction
import com.schwabquant.app.core.quant.OccSymbolFormatter
import com.schwabquant.app.data.local.dao.AuditLogDao
import com.schwabquant.app.data.local.dao.ShadowLedgerDao
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.db.SchwabQuantDatabase
import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.data.local.entity.ShadowOrderEntity
import com.schwabquant.app.data.local.entity.ShadowPositionEntity
import com.schwabquant.app.data.local.entity.WashSaleTradeEntity
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.dto.QuoteEntryDto
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStatusSnapshot
import com.schwabquant.app.domain.model.OrderSubmissionResult
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.repository.MarketDataRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Serializable
data class ShadowOrderLegPayload(
    val symbol: String,
    val underlyingSymbol: String,
    val assetType: String,
    val instruction: String,
    val quantity: Int,
    val referenceUnitPrice: Double,
    val strike: Double? = null,
    val expirationIsoDate: String? = null,
    val isCall: Boolean? = null
)

/**
 * Full Room-backed paper-trading broker for Shadow Mode.
 * Evaluates simulated fills against live/demo quotes, reconciles multi-leg spread fills to exact
 * per-leg prices, updates ShadowPositionEntity + shadowCashBalance + WashSaleTradeEntity
 * atomically inside Room transactions, and never invokes Schwab Trader endpoints.
 */
@Singleton
class ShadowExecutionEngine(
    private val shadowLedgerDao: ShadowLedgerDao,
    private val washSaleDao: WashSaleDao,
    private val auditLogDao: AuditLogDao,
    private val preferences: AppPreferencesDataStore,
    private val marketDataRepository: MarketDataRepository,
    private val occSymbolFormatter: OccSymbolFormatter,
    private val json: Json,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val nowProvider: () -> Long = System::currentTimeMillis
) {

    @Inject
    constructor(
        database: SchwabQuantDatabase,
        shadowLedgerDao: ShadowLedgerDao,
        washSaleDao: WashSaleDao,
        auditLogDao: AuditLogDao,
        preferences: AppPreferencesDataStore,
        marketDataRepository: MarketDataRepository,
        occSymbolFormatter: OccSymbolFormatter,
        json: Json
    ) : this(
        shadowLedgerDao = shadowLedgerDao,
        washSaleDao = washSaleDao,
        auditLogDao = auditLogDao,
        preferences = preferences,
        marketDataRepository = marketDataRepository,
        occSymbolFormatter = occSymbolFormatter,
        json = json,
        runInTransaction = { block -> database.withTransaction { block() } },
        nowProvider = System::currentTimeMillis
    )

    suspend fun simulateOrder(
        ticket: OrderTicket,
        immediateMidFill: Boolean = true,
        overriddenRiskCodes: List<String> = emptyList()
    ): OrderSubmissionResult {
        val now = nowProvider()
        val orderId = "SHADOW-${UUID.randomUUID().toString().take(8).uppercase()}"
        val activeLegs = resolveExecutableLegs(ticket)
        val primaryLeg = activeLegs.firstOrNull()
        val primarySymbol = if (activeLegs.size > 1) {
            "${activeLegs.first().underlyingSymbol ?: primaryLeg?.symbol ?: "MULTI"} ${ticket.complexStrategyType ?: ticket.strategyType}"
        } else {
            primaryLeg?.symbol ?: "MULTI-LEG"
        }
        val totalQty = activeLegs.minOfOrNull { it.quantity }?.coerceAtLeast(1) ?: 1

        val symbolsToQuote = activeLegs.map { it.symbol }.distinct()
        val quotes = runCatching {
            marketDataRepository.getQuotes(symbolsToQuote, forceRefresh = false).getOrNull()
        }.getOrNull().orEmpty()

        val legPayloads = buildLegPayloads(ticket, activeLegs, quotes)
        val legsJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ShadowOrderLegPayload.serializer()),
            legPayloads
        )

        val shouldFillNow = shouldFillImmediately(
            ticket = ticket,
            legPayloads = legPayloads,
            quotes = quotes,
            immediateMidFill = immediateMidFill
        )

        if (!shouldFillNow) {
            val workingEntity = ShadowOrderEntity(
                orderId = orderId,
                symbol = primarySymbol,
                orderType = ticket.orderType.apiValue,
                session = ticket.session.apiValue,
                duration = ticket.duration.apiValue,
                strategyType = ticket.complexStrategyType ?: ticket.strategyType,
                limitPrice = ticket.limitPrice?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
                stopPrice = ticket.stopPrice?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
                quantity = totalQty,
                status = STATUS_WORKING,
                legsJson = legsJson,
                submittedAtEpochMs = now
            )
            runInTransaction {
                shadowLedgerDao.upsertOrder(workingEntity)
                auditLogDao.insert(
                    AuditLogEntity(
                        timestampEpochMs = now,
                        category = "SHADOW_ORDER_WORKING",
                        severity = "INFO",
                        symbol = primarySymbol,
                        executionMode = ExecutionMode.SHADOW.name,
                        message = "Paper ${ticket.orderType.apiValue} order $orderId queued as WORKING.",
                        metadataJson = if (overriddenRiskCodes.isEmpty()) null else overriddenRiskCodes.joinToString(",")
                    )
                )
            }
            return OrderSubmissionResult(
                orderId = orderId,
                executionMode = ExecutionMode.SHADOW,
                status = STATUS_WORKING,
                message = "Shadow order queued as WORKING (tap Simulate Fill or Cancel anytime).",
                submittedAtEpochMs = now
            )
        }

        val netFillPrice = resolveNetFillPrice(ticket, legPayloads, quotes, overridePrice = null)
        val filledOrderEntity = ShadowOrderEntity(
            orderId = orderId,
            symbol = primarySymbol,
            orderType = ticket.orderType.apiValue,
            session = ticket.session.apiValue,
            duration = ticket.duration.apiValue,
            strategyType = ticket.complexStrategyType ?: ticket.strategyType,
            limitPrice = ticket.limitPrice?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
            stopPrice = ticket.stopPrice?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
            quantity = totalQty,
            status = STATUS_FILLED,
            legsJson = legsJson,
            submittedAtEpochMs = now,
            filledAtEpochMs = now,
            fillPrice = netFillPrice.setScale(2, RoundingMode.HALF_UP).toPlainString()
        )

        applyFillAtomically(
            orderEntity = filledOrderEntity,
            legPayloads = legPayloads,
            netFillPrice = netFillPrice,
            fillTimeMs = now,
            overriddenRiskCodes = overriddenRiskCodes
        )

        return OrderSubmissionResult(
            orderId = orderId,
            executionMode = ExecutionMode.SHADOW,
            status = STATUS_FILLED,
            message = "Shadow paper fill executed @ $${netFillPrice.setScale(2, RoundingMode.HALF_UP)}.",
            submittedAtEpochMs = now,
            fillPrice = netFillPrice.setScale(2, RoundingMode.HALF_UP)
        )
    }

    suspend fun simulateFillOrder(
        orderId: String,
        overrideFillPrice: BigDecimal? = null
    ): Result<OrderSubmissionResult> = runCatching {
        val existing = shadowLedgerDao.getOrderById(orderId)
            ?: error("Shadow order '$orderId' not found.")
        require(existing.status == STATUS_WORKING) {
            "Cannot simulate fill for order $orderId in status '${existing.status}'."
        }

        val legPayloads = parseLegPayloads(existing)
        val quotes = runCatching {
            marketDataRepository.getQuotes(legPayloads.map { it.symbol }.distinct()).getOrNull()
        }.getOrNull().orEmpty()

        val limitOrStop = overrideFillPrice
            ?: existing.limitPrice?.toBigDecimalOrNull()
            ?: existing.stopPrice?.toBigDecimalOrNull()
        val computedRefNet = abs(computeSignedNetReference(legPayloads))
        val netFillPrice = (limitOrStop ?: BigDecimal.valueOf(computedRefNet))
            .max(BigDecimal("0.01"))
            .setScale(2, RoundingMode.HALF_UP)

        val now = nowProvider()
        val updatedEntity = existing.copy(
            status = STATUS_FILLED,
            filledAtEpochMs = now,
            fillPrice = netFillPrice.toPlainString()
        )

        applyFillAtomically(
            orderEntity = updatedEntity,
            legPayloads = legPayloads,
            netFillPrice = netFillPrice,
            fillTimeMs = now,
            overriddenRiskCodes = emptyList(),
            quotes = quotes
        )

        OrderSubmissionResult(
            orderId = orderId,
            executionMode = ExecutionMode.SHADOW,
            status = STATUS_FILLED,
            message = "Simulated paper fill executed for ${existing.symbol} @ $$netFillPrice.",
            submittedAtEpochMs = now,
            fillPrice = netFillPrice
        )
    }

    suspend fun cancelShadowOrder(orderId: String): Result<Unit> = runCatching {
        val existing = shadowLedgerDao.getOrderById(orderId)
            ?: error("Shadow order '$orderId' not found.")
        require(existing.status != STATUS_FILLED) {
            "Order $orderId is already FILLED and cannot be canceled."
        }
        val now = nowProvider()
        runInTransaction {
            shadowLedgerDao.cancelOrder(orderId)
            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = now,
                    category = "SHADOW_ORDER_CANCEL",
                    severity = "INFO",
                    symbol = existing.symbol,
                    executionMode = ExecutionMode.SHADOW.name,
                    message = "Canceled Shadow Mode order $orderId."
                )
            )
        }
    }

    suspend fun getShadowOrderStatus(orderId: String): String? =
        shadowLedgerDao.getOrderById(orderId)?.status

    suspend fun getShadowOrderStatusSnapshot(orderId: String): OrderStatusSnapshot? {
        val entity = shadowLedgerDao.getOrderById(orderId) ?: return null
        val normalized = entity.status.trim().uppercase()
        val resolvedEntityQty = entity.quantity.takeIf { it > 0 }
            ?: parseLegPayloads(entity).firstOrNull()?.quantity
            ?: 0
        val qty = resolvedEntityQty.toDouble().coerceAtLeast(0.0)
        val filled = if (normalized == STATUS_FILLED) qty else 0.0
        val remaining = if (normalized == STATUS_WORKING) qty else 0.0
        return OrderStatusSnapshot(
            orderId = entity.orderId,
            status = normalized,
            filledQuantity = filled,
            remainingQuantity = remaining,
            orderQuantity = qty.takeIf { it > 0.0 }
        )
    }

    private suspend fun applyFillAtomically(
        orderEntity: ShadowOrderEntity,
        legPayloads: List<ShadowOrderLegPayload>,
        netFillPrice: BigDecimal,
        fillTimeMs: Long,
        overriddenRiskCodes: List<String>,
        quotes: Map<String, QuoteEntryDto> = emptyMap()
    ) {
        val isNetCredit = orderEntity.orderType == OrderType.NET_CREDIT.apiValue ||
            (legPayloads.size == 1 && !isBuyingSide(legPayloads.first().instruction))
        val reconciledPrices = reconcilePerLegFillPrices(
            legs = legPayloads,
            netFillPrice = netFillPrice.toDouble(),
            isNetCredit = isNetCredit
        )

        runInTransaction {
            shadowLedgerDao.upsertOrder(orderEntity)

            var totalCashDelta = 0.0
            val washTrades = mutableListOf<WashSaleTradeEntity>()

            legPayloads.forEachIndexed { idx, leg ->
                val fillPrice = reconciledPrices[idx]
                val isOption = leg.assetType.equals(AssetCategory.OPTION.name, ignoreCase = true)
                val multiplier = if (isOption) OPTION_MULTIPLIER else 1.0
                val isBuy = isBuyingSide(leg.instruction)
                val signedQty = if (isBuy) leg.quantity else -leg.quantity

                // Cash delta: buying debits cash (-), selling credits cash (+)
                totalCashDelta += -signedQty * fillPrice * multiplier

                val existingPos = shadowLedgerDao.getPositionBySymbol(leg.symbol)
                var realizedPnlForLeg: Double? = null

                if (existingPos == null) {
                    val mark = quotes[leg.symbol]?.quote?.mark
                        ?: quotes[leg.symbol]?.quote?.lastPrice
                        ?: fillPrice
                    shadowLedgerDao.upsertPosition(
                        ShadowPositionEntity(
                            symbol = leg.symbol,
                            assetType = leg.assetType,
                            underlyingSymbol = leg.underlyingSymbol,
                            quantity = signedQty.toString(),
                            averageCost = formatPrice2(fillPrice),
                            markPrice = formatPrice2(mark),
                            openedAtEpochMs = fillTimeMs,
                            updatedAtEpochMs = fillTimeMs
                        )
                    )
                } else {
                    val oldQty = existingPos.quantity.toDoubleOrNull()?.roundToInt() ?: 0
                    val oldAvg = existingPos.averageCost.toDoubleOrNull() ?: fillPrice
                    val newQty = oldQty + signedQty

                    val sameDirection = (oldQty >= 0 && signedQty >= 0) || (oldQty <= 0 && signedQty <= 0)
                    if (sameDirection && oldQty != 0) {
                        val absOld = abs(oldQty).toDouble()
                        val absAdd = abs(signedQty).toDouble()
                        val weightedAvg = (absOld * oldAvg + absAdd * fillPrice) / (absOld + absAdd)
                        shadowLedgerDao.upsertPosition(
                            existingPos.copy(
                                quantity = newQty.toString(),
                                averageCost = formatPrice2(weightedAvg),
                                markPrice = formatPrice2(fillPrice),
                                updatedAtEpochMs = fillTimeMs
                            )
                        )
                    } else if (oldQty != 0) {
                        // Reducing or closing an existing position
                        val closedCount = minOf(abs(oldQty), abs(signedQty))
                        realizedPnlForLeg = if (oldQty > 0) {
                            (fillPrice - oldAvg) * closedCount * multiplier
                        } else {
                            (oldAvg - fillPrice) * closedCount * multiplier
                        }
                        if (newQty == 0) {
                            shadowLedgerDao.deletePosition(leg.symbol)
                        } else {
                            val flipped = (oldQty > 0 && newQty < 0) || (oldQty < 0 && newQty > 0)
                            val updatedAvg = if (flipped) fillPrice else oldAvg
                            shadowLedgerDao.upsertPosition(
                                existingPos.copy(
                                    quantity = newQty.toString(),
                                    averageCost = formatPrice2(updatedAvg),
                                    markPrice = formatPrice2(fillPrice),
                                    updatedAtEpochMs = fillTimeMs
                                )
                            )
                        }
                    } else {
                        shadowLedgerDao.upsertPosition(
                            existingPos.copy(
                                quantity = signedQty.toString(),
                                averageCost = formatPrice2(fillPrice),
                                markPrice = formatPrice2(fillPrice),
                                updatedAtEpochMs = fillTimeMs
                            )
                        )
                    }
                }

                washTrades += WashSaleTradeEntity(
                    underlyingSymbol = leg.underlyingSymbol.uppercase(),
                    symbol = leg.symbol,
                    assetType = leg.assetType.uppercase(),
                    instruction = leg.instruction.uppercase(),
                    quantity = leg.quantity,
                    price = fillPrice,
                    realizedPnl = realizedPnlForLeg,
                    tradeTimestampEpochMs = fillTimeMs,
                    source = "SHADOW",
                    externalOrderId = "${orderEntity.orderId}_${idx}_${leg.symbol}"
                )
            }

            if (washTrades.isNotEmpty()) {
                washSaleDao.insertAll(washTrades)
            }

            val currentCash = preferences.shadowCashBalance.first()
            val updatedCash = BigDecimal.valueOf(currentCash + totalCashDelta)
                .setScale(2, RoundingMode.HALF_UP)
                .toDouble()
            preferences.setShadowCashBalance(updatedCash)

            auditLogDao.insert(
                AuditLogEntity(
                    timestampEpochMs = fillTimeMs,
                    category = "SHADOW_ORDER_FILL",
                    severity = "INFO",
                    symbol = orderEntity.symbol,
                    executionMode = ExecutionMode.SHADOW.name,
                    message = "Shadow order ${orderEntity.orderId} FILLED @ $${netFillPrice.toPlainString()} " +
                        "(Cash Δ ${formatSignedCash(totalCashDelta)}, Cash $$updatedCash).",
                    metadataJson = if (overriddenRiskCodes.isEmpty()) null else overriddenRiskCodes.joinToString(",")
                )
            )
        }
    }

    /**
     * Reconciles individual leg fill prices against the composite net fill price so the
     * sum of signed leg cash flows matches the net debit/credit exactly.
     */
    internal fun reconcilePerLegFillPrices(
        legs: List<ShadowOrderLegPayload>,
        netFillPrice: Double,
        isNetCredit: Boolean
    ): List<Double> {
        if (legs.isEmpty()) return emptyList()
        if (legs.size == 1) {
            return listOf(netFillPrice.coerceAtLeast(0.01).roundCents())
        }

        val spreadUnits = legs.minOf { it.quantity }.coerceAtLeast(1)
        val targetSignedTotal = (if (isNetCredit) -abs(netFillPrice) else abs(netFillPrice)) * spreadUnits

        var currentSignedTotal = 0.0
        var totalWeight = 0.0
        val basePrices = DoubleArray(legs.size) { i ->
            val leg = legs[i]
            val base = leg.referenceUnitPrice.takeIf { it > 0.0 } ?: 1.0
            val sign = if (isBuyingSide(leg.instruction)) 1.0 else -1.0
            currentSignedTotal += sign * base * leg.quantity
            totalWeight += max(0.05, base * leg.quantity)
            base
        }

        val diff = targetSignedTotal - currentSignedTotal
        val adjusted = DoubleArray(legs.size) { i ->
            val leg = legs[i]
            val sign = if (isBuyingSide(leg.instruction)) 1.0 else -1.0
            val weight = max(0.05, basePrices[i] * leg.quantity)
            val signedDelta = diff * (weight / totalWeight)
            val unitDelta = sign * (signedDelta / leg.quantity)
            (basePrices[i] + unitDelta).coerceAtLeast(0.01).roundCents()
        }

        // Absorb rounding pennies on highest-priced leg
        var actualSignedTotal = 0.0
        for (i in legs.indices) {
            val sign = if (isBuyingSide(legs[i].instruction)) 1.0 else -1.0
            actualSignedTotal += sign * adjusted[i] * legs[i].quantity
        }
        val pennyResidual = targetSignedTotal - actualSignedTotal
        if (abs(pennyResidual) >= 0.005) {
            val anchorIdx = adjusted.indices.maxByOrNull { adjusted[it] } ?: 0
            val anchorSign = if (isBuyingSide(legs[anchorIdx].instruction)) 1.0 else -1.0
            val deltaPerUnit = anchorSign * (pennyResidual / legs[anchorIdx].quantity)
            adjusted[anchorIdx] = (adjusted[anchorIdx] + deltaPerUnit).coerceAtLeast(0.01).roundCents()
        }

        return adjusted.toList()
    }

    private fun resolveExecutableLegs(ticket: OrderTicket): List<OrderLeg> {
        if (ticket.strategyType == "OCO" && ticket.childOrders.isNotEmpty()) {
            val limitChild = ticket.childOrders.firstOrNull { it.orderType == OrderType.LIMIT }
                ?: ticket.childOrders.first()
            return limitChild.legs
        }
        return ticket.legs
    }

    private fun buildLegPayloads(
        ticket: OrderTicket,
        legs: List<OrderLeg>,
        quotes: Map<String, QuoteEntryDto>
    ): List<ShadowOrderLegPayload> {
        val fallbackSingle = ticket.limitPrice?.toDouble() ?: ticket.stopPrice?.toDouble() ?: 1.0
        return legs.map { leg ->
            val isOption = leg.category == AssetCategory.OPTION
            val occ = if (isOption) occSymbolFormatter.parse(leg.symbol) else null
            val underlying = leg.underlyingSymbol?.takeIf { it.isNotBlank() }
                ?: occ?.root
                ?: leg.symbol
            val quoteMark = quotes[leg.symbol]?.quote?.let { q ->
                q.mark ?: q.lastPrice ?: if (q.bidPrice != null && q.askPrice != null) {
                    (q.bidPrice + q.askPrice) * 0.5
                } else {
                    null
                }
            }
            val refPrice = leg.estimatedUnitPrice?.toDouble()?.takeIf { it > 0.0 }
                ?: quoteMark?.takeIf { it > 0.0 }
                ?: if (legs.size == 1) fallbackSingle else 1.0

            ShadowOrderLegPayload(
                symbol = leg.symbol,
                underlyingSymbol = underlying.uppercase(),
                assetType = leg.category.name,
                instruction = leg.side.name,
                quantity = leg.quantity,
                referenceUnitPrice = refPrice,
                strike = leg.strike ?: occ?.strike?.toDouble(),
                expirationIsoDate = leg.expirationIsoDate ?: occ?.expiration?.toString(),
                isCall = leg.isCall ?: occ?.isCall
            )
        }
    }

    private fun shouldFillImmediately(
        ticket: OrderTicket,
        legPayloads: List<ShadowOrderLegPayload>,
        quotes: Map<String, QuoteEntryDto>,
        immediateMidFill: Boolean
    ): Boolean {
        if (ticket.strategyType == "OCO") return false
        if (ticket.orderType == OrderType.STOP || ticket.orderType == OrderType.STOP_LIMIT) return false
        if (ticket.orderType == OrderType.MARKET) return true
        if (immediateMidFill) return true

        val limit = ticket.limitPrice?.toDouble() ?: return false
        if (legPayloads.size == 1) {
            val leg = legPayloads.first()
            val q = quotes[leg.symbol]?.quote
            val ask = q?.askPrice?.takeIf { it > 0.0 }
            val bid = q?.bidPrice?.takeIf { it > 0.0 }
            return if (isBuyingSide(leg.instruction)) {
                ask != null && limit >= ask
            } else {
                bid != null && limit <= bid
            }
        }
        return false
    }

    private fun resolveNetFillPrice(
        ticket: OrderTicket,
        legPayloads: List<ShadowOrderLegPayload>,
        quotes: Map<String, QuoteEntryDto>,
        overridePrice: BigDecimal?
    ): BigDecimal {
        if (overridePrice != null) return overridePrice
        if (ticket.orderType == OrderType.MARKET && legPayloads.size == 1) {
            val leg = legPayloads.first()
            val q = quotes[leg.symbol]?.quote
            val marketPx = if (isBuyingSide(leg.instruction)) {
                q?.askPrice ?: q?.mark ?: q?.lastPrice
            } else {
                q?.bidPrice ?: q?.mark ?: q?.lastPrice
            }
            val finalPx = marketPx?.takeIf { it > 0.0 } ?: leg.referenceUnitPrice
            return BigDecimal.valueOf(finalPx).setScale(2, RoundingMode.HALF_UP)
        }
        val refNet = abs(computeSignedNetReference(legPayloads)).coerceAtLeast(0.01)
        return (ticket.limitPrice ?: BigDecimal.valueOf(refNet))
            .max(BigDecimal("0.01"))
            .setScale(2, RoundingMode.HALF_UP)
    }

    private fun computeSignedNetReference(legPayloads: List<ShadowOrderLegPayload>): Double {
        if (legPayloads.isEmpty()) return 1.0
        val spreadUnits = legPayloads.minOf { it.quantity }.coerceAtLeast(1)
        val totalSigned = legPayloads.sumOf { leg ->
            val sign = if (isBuyingSide(leg.instruction)) 1.0 else -1.0
            sign * leg.referenceUnitPrice * leg.quantity
        }
        return totalSigned / spreadUnits
    }

    private fun parseLegPayloads(entity: ShadowOrderEntity): List<ShadowOrderLegPayload> {
        return runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ShadowOrderLegPayload.serializer()),
                entity.legsJson
            )
        }.getOrElse {
            entity.legsJson.split(";").mapNotNull { chunk ->
                val parts = chunk.split(":")
                if (parts.size < 3) return@mapNotNull null
                val sym = parts[2]
                val occ = occSymbolFormatter.parse(sym)
                ShadowOrderLegPayload(
                    symbol = sym,
                    underlyingSymbol = occ?.root ?: sym,
                    assetType = if (occ != null) AssetCategory.OPTION.name else AssetCategory.EQUITY.name,
                    instruction = parts[0],
                    quantity = parts[1].toIntOrNull() ?: 1,
                    referenceUnitPrice = entity.limitPrice?.toDoubleOrNull() ?: 1.0,
                    strike = occ?.strike?.toDouble(),
                    expirationIsoDate = occ?.expiration?.toString(),
                    isCall = occ?.isCall
                )
            }
        }
    }

    private fun isBuyingSide(instruction: String): Boolean =
        instruction.equals(OrderSide.BUY.name, ignoreCase = true) ||
            instruction.equals(OrderSide.BUY_TO_COVER.name, ignoreCase = true) ||
            instruction.equals(OrderSide.BUY_TO_OPEN.name, ignoreCase = true) ||
            instruction.equals(OrderSide.BUY_TO_CLOSE.name, ignoreCase = true)

    private fun Double.roundCents(): Double = (this * 100.0).roundToInt() / 100.0
    private fun formatPrice2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
    private fun formatSignedCash(v: Double): String =
        (if (v >= 0.0) "+$" else "-$") + formatPrice2(abs(v))

    companion object {
        const val STATUS_WORKING = "WORKING"
        const val STATUS_FILLED = "FILLED"
        const val STATUS_CANCELED = "CANCELED"
        private const val OPTION_MULTIPLIER = 100.0
    }
}
