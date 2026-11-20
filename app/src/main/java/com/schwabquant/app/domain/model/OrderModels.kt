package com.schwabquant.app.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

enum class OrderSide {
    BUY,
    SELL,
    BUY_TO_COVER,
    BUY_TO_OPEN,
    SELL_TO_OPEN,
    BUY_TO_CLOSE,
    SELL_TO_CLOSE
}

enum class OrderType(val apiValue: String) {
    LIMIT("LIMIT"),
    STOP("STOP"),
    STOP_LIMIT("STOP_LIMIT"),
    MARKET("MARKET"),
    NET_DEBIT("NET_DEBIT"),
    NET_CREDIT("NET_CREDIT")
}

enum class OrderStrategyCategory(val displayName: String) {
    SINGLE_EQUITY("Single Equity"),
    SINGLE_OPTION("Single Option"),
    DIAGONAL("Diagonal (PMCC/PMCP)"),
    VERTICAL("Vertical Spread"),
    CALENDAR("Calendar Spread"),
    IRON_CONDOR("Iron Condor"),
    OCO_BRACKET("OCO Equity Bracket")
}

/**
 * Schwab trading sessions per SCHWAB_API_REFERENCE.md Section 4A:
 * AM (7:00-9:25 AM ET), PM (4:05-8:00 PM ET), SEAMLESS (7:00 AM-8:00 PM ET), NORMAL (9:30 AM-4:00 PM ET).
 */
enum class TradingSession(val apiValue: String) {
    AM("AM"),
    PM("PM"),
    SEAMLESS("SEAMLESS"),
    NORMAL("NORMAL")
}

enum class OrderDuration(val apiValue: String) {
    DAY("DAY"),
    GOOD_TILL_CANCEL("GOOD_TILL_CANCEL")
}

data class OrderLeg(
    val symbol: String,
    val category: AssetCategory,
    val side: OrderSide,
    val quantity: Int,
    val underlyingSymbol: String? = null,
    val estimatedUnitPrice: BigDecimal? = null,
    val strike: Double? = null,
    val expirationIsoDate: String? = null,
    val dte: Int? = null,
    val isCall: Boolean? = null,
    val delta: Double? = null
)

object SchwabOrderStatuses {
    val ACTIVE_WORKING_STATUSES: Set<String> = setOf(
        "AWAITING_STOP_CONDITION",
        "AWAITING_RELEASE_TIME",
        "PENDING_ACKNOWLEDGEMENT",
        "NEW",
        "AWAITING_PARENT_ORDER",
        "AWAITING_CONDITION",
        "AWAITING_MANUAL_REVIEW",
        "ACCEPTED",
        "AWAITING_UR_OUT",
        "PENDING_ACTIVATION",
        "AWAITING_ACTIVATION",
        "QUEUED",
        "WORKING",
        "PARTIALLY_FILLED"
    )
}

data class OrderDraft(
    val strategyCategory: OrderStrategyCategory,
    val underlyingSymbol: String,
    val orderType: OrderType,
    val session: TradingSession = TradingSession.NORMAL,
    val duration: OrderDuration = OrderDuration.DAY,
    val limitPrice: BigDecimal? = null,
    val stopPrice: BigDecimal? = null,
    val ocoTakeProfitPrice: BigDecimal? = null,
    val ocoStopPrice: BigDecimal? = null,
    val legs: List<OrderLeg>,
    val estimatedBid: Double? = null,
    val estimatedMid: Double? = null,
    val estimatedAsk: Double? = null,
    val estimatedBpr: Double? = null,
    val knownEarningsDateIso: String? = null,
    val sourceLabel: String = "Manual Order",
    val simulateImmediateMidFill: Boolean = true,
    val replacePriorOrderId: String? = null,
    val isEscalatedReprice: Boolean = false
) {
    fun toOrderTicket(
        accountHash: String,
        executionMode: ExecutionMode
    ): OrderTicket {
        // MEDIUM-4 Safeguard: Preserve passed accountHash verbatim (including "SHADOW_PAPER")
        // so misconfigured callers hit OrderRepositoryImpl's fail-closed firewall instead of being masked.
        val cleanQtyLegs = legs.map { it.copy(quantity = it.quantity.coerceAtLeast(1)) }
        if (strategyCategory == OrderStrategyCategory.OCO_BRACKET) {
            val primaryLeg = cleanQtyLegs.first()
            val tpPrice = ocoTakeProfitPrice ?: limitPrice ?: primaryLeg.estimatedUnitPrice
            val slPrice = ocoStopPrice ?: stopPrice ?: primaryLeg.estimatedUnitPrice
            val exitSide = when (primaryLeg.side) {
                OrderSide.BUY -> OrderSide.BUY
                OrderSide.BUY_TO_COVER -> OrderSide.BUY_TO_COVER
                else -> OrderSide.SELL
            }
            val exitLeg = primaryLeg.copy(category = AssetCategory.EQUITY, side = exitSide)
            return OrderTicket(
                accountHash = accountHash,
                executionMode = executionMode,
                orderType = OrderType.LIMIT,
                session = TradingSession.NORMAL,
                duration = OrderDuration.GOOD_TILL_CANCEL,
                strategyType = "OCO",
                complexStrategyType = null,
                limitPrice = tpPrice,
                stopPrice = slPrice,
                legs = listOf(exitLeg),
                childOrders = listOf(
                    OrderTicket(
                        accountHash = accountHash,
                        executionMode = executionMode,
                        orderType = OrderType.LIMIT,
                        session = TradingSession.NORMAL,
                        duration = OrderDuration.GOOD_TILL_CANCEL,
                        strategyType = "SINGLE",
                        limitPrice = tpPrice?.setScale(2, RoundingMode.HALF_UP),
                        legs = listOf(exitLeg)
                    ),
                    OrderTicket(
                        accountHash = accountHash,
                        executionMode = executionMode,
                        orderType = OrderType.STOP,
                        session = TradingSession.NORMAL,
                        duration = OrderDuration.GOOD_TILL_CANCEL,
                        strategyType = "SINGLE",
                        stopPrice = slPrice?.setScale(2, RoundingMode.HALF_UP),
                        legs = listOf(exitLeg)
                    )
                ),
                replacePriorOrderId = replacePriorOrderId
            )
        }

        val complexType = when (strategyCategory) {
            OrderStrategyCategory.DIAGONAL -> "DIAGONAL"
            OrderStrategyCategory.VERTICAL -> "VERTICAL"
            OrderStrategyCategory.CALENDAR -> "CALENDAR"
            OrderStrategyCategory.IRON_CONDOR -> "IRON_CONDOR"
            OrderStrategyCategory.SINGLE_EQUITY,
            OrderStrategyCategory.SINGLE_OPTION,
            OrderStrategyCategory.OCO_BRACKET -> null
        }
        val effectiveSession = if (strategyCategory != OrderStrategyCategory.SINGLE_EQUITY) {
            TradingSession.NORMAL
        } else {
            session
        }
        val effectiveOrderType = if (effectiveSession != TradingSession.NORMAL) {
            OrderType.LIMIT
        } else {
            orderType
        }
        val effectiveDuration = when {
            effectiveSession != TradingSession.NORMAL -> OrderDuration.DAY
            effectiveOrderType == OrderType.MARKET -> OrderDuration.DAY
            else -> duration
        }

        return OrderTicket(
            accountHash = accountHash,
            executionMode = executionMode,
            orderType = effectiveOrderType,
            session = effectiveSession,
            duration = effectiveDuration,
            strategyType = "SINGLE",
            complexStrategyType = complexType,
            limitPrice = limitPrice?.setScale(2, RoundingMode.HALF_UP),
            stopPrice = stopPrice?.setScale(2, RoundingMode.HALF_UP),
            legs = cleanQtyLegs,
            replacePriorOrderId = replacePriorOrderId
        )
    }
}

data class OrderTicket(
    val accountHash: String,
    val executionMode: ExecutionMode,
    val orderType: OrderType,
    val session: TradingSession,
    val duration: OrderDuration,
    val strategyType: String = "SINGLE",
    val complexStrategyType: String? = null,
    val limitPrice: BigDecimal? = null,
    val stopPrice: BigDecimal? = null,
    val legs: List<OrderLeg>,
    val childOrders: List<OrderTicket> = emptyList(),
    val replacePriorOrderId: String? = null
)

data class OrderSubmissionResult(
    val orderId: String,
    val executionMode: ExecutionMode,
    val status: String,
    val message: String,
    val submittedAtEpochMs: Long,
    val fillPrice: BigDecimal? = null
)

data class OrderStatusSnapshot(
    val orderId: String,
    val status: String,
    val filledQuantity: Double = 0.0,
    val remainingQuantity: Double? = null,
    val orderQuantity: Double? = null
) {
    val normalizedStatus: String
        get() = status.trim().uppercase()

    val hasAnyFill: Boolean
        get() = filledQuantity > 0.0 ||
            normalizedStatus == "FILLED" ||
            normalizedStatus == "PARTIALLY_FILLED"

    val isConfirmedFullyFilled: Boolean
        get() = normalizedStatus == "FILLED" && (remainingQuantity == null || remainingQuantity <= 0.0)

    val isFullyFilled: Boolean
        get() = isConfirmedFullyFilled ||
            (filledQuantity > 0.0 && remainingQuantity != null && remainingQuantity <= 0.0 &&
                normalizedStatus != "PARTIALLY_FILLED" &&
                normalizedStatus != "CANCELED" &&
                normalizedStatus != "CANCELLED" &&
                normalizedStatus != "REJECTED" &&
                normalizedStatus != "EXPIRED")

    val isCanceledWithZeroFill: Boolean
        get() = (normalizedStatus == "CANCELED" || normalizedStatus == "CANCELLED") &&
            filledQuantity <= 0.0
}

data class WorkingOrder(
    val orderId: String,
    val executionMode: ExecutionMode,
    val symbol: String,
    val underlyingSymbol: String,
    val strategyType: String,
    val orderType: String,
    val session: String,
    val duration: String,
    val status: String,
    val limitPrice: String?,
    val stopPrice: String?,
    val fillPrice: String?,
    val quantity: Int,
    val legsSummary: String,
    val submittedAtEpochMs: Long,
    val filledAtEpochMs: Long?,
    val assetType: String = "EQUITY",
    val legSymbols: Set<String> = emptySet(),
    val hasOptionLegs: Boolean = assetType.equals("OPTION", ignoreCase = true),
    val hasEquityLegs: Boolean = assetType.equals("EQUITY", ignoreCase = true)
)

sealed interface ReplaceOrderOutcome {
    data class Replaced(val result: OrderSubmissionResult) : ReplaceOrderOutcome
    data class AbortedAlreadyFilled(
        val priorOrderId: String,
        val priorStatus: String,
        val filledQuantity: Double = 0.0,
        val remainingQuantity: Double = 0.0
    ) : ReplaceOrderOutcome
    data class AbortedNotCanceled(
        val priorOrderId: String,
        val priorStatus: String
    ) : ReplaceOrderOutcome
}

data class WalkUpState(
    val isActive: Boolean = false,
    val orderId: String? = null,
    val currentLimitPrice: BigDecimal? = null,
    val hardMaxCeiling: BigDecimal? = null,
    val secondsRemaining: Int = 0,
    val stepCount: Int = 0,
    val isDebit: Boolean = true,
    val statusMessage: String? = null,
    val waitingForStepPrompt: Boolean = false
)
