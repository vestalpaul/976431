package com.schwabquant.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Verified Schwab Trader Order DTOs & string enums per SCHWAB_API_REFERENCE.md Section 4.
 * Prices are serialized as formatted 2-decimal Strings via BigDecimal to avoid float artifacts.
 */
object SchwabOrderType {
    const val LIMIT = "LIMIT"
    const val STOP = "STOP"
    const val STOP_LIMIT = "STOP_LIMIT"
    const val MARKET = "MARKET"
    const val NET_DEBIT = "NET_DEBIT"
    const val NET_CREDIT = "NET_CREDIT"
}

object SchwabSession {
    const val AM = "AM"
    const val PM = "PM"
    const val SEAMLESS = "SEAMLESS"
    const val NORMAL = "NORMAL"
}

object SchwabDuration {
    const val DAY = "DAY"
    const val GOOD_TILL_CANCEL = "GOOD_TILL_CANCEL"
}

object SchwabOrderStrategyType {
    const val SINGLE = "SINGLE"
    const val OCO = "OCO"
}

object SchwabComplexOrderStrategyType {
    const val DIAGONAL = "DIAGONAL"
    const val VERTICAL = "VERTICAL"
    const val CALENDAR = "CALENDAR"
    const val IRON_CONDOR = "IRON_CONDOR"
}

object SchwabOrderInstruction {
    const val BUY = "BUY"
    const val SELL = "SELL"
    const val BUY_TO_COVER = "BUY_TO_COVER"
    const val BUY_TO_OPEN = "BUY_TO_OPEN"
    const val SELL_TO_OPEN = "SELL_TO_OPEN"
    const val BUY_TO_CLOSE = "BUY_TO_CLOSE"
    const val SELL_TO_CLOSE = "SELL_TO_CLOSE"
}

object SchwabAssetType {
    const val EQUITY = "EQUITY"
    const val OPTION = "OPTION"
}

fun BigDecimal.toSchwabPriceString(): String {
    val scaled = setScale(2, RoundingMode.HALF_UP)
    require(scaled > BigDecimal.ZERO) { "Schwab order price must be strictly positive (>= 0.01): $this" }
    return scaled.toPlainString()
}

@Serializable
data class OrderRequestDto(
    val orderType: String? = null,
    val session: String? = null,
    val price: String? = null,
    val stopPrice: String? = null,
    val duration: String? = null,
    val complexOrderStrategyType: String? = null,
    val orderStrategyType: String,
    val orderLegCollection: List<OrderLegDto>? = null,
    val childOrderStrategies: List<OrderRequestDto>? = null
)

@Serializable
data class OrderLegDto(
    val instruction: String,
    val quantity: Int,
    val instrument: OrderInstrumentDto
)

@Serializable
data class OrderInstrumentDto(
    val symbol: String,
    val assetType: String,
    val underlyingSymbol: String? = null
)

@Serializable
data class OrderResponseDto(
    val orderId: Long? = null,
    val session: String? = null,
    val duration: String? = null,
    val orderType: String? = null,
    val complexOrderStrategyType: String? = null,
    val orderStrategyType: String? = null,
    val quantity: Double? = null,
    val filledQuantity: Double? = null,
    val remainingQuantity: Double? = null,
    val price: Double? = null,
    val stopPrice: Double? = null,
    val status: String? = null,
    val enteredTime: String? = null,
    val closeTime: String? = null,
    val orderLegCollection: List<OrderLegResponseDto> = emptyList(),
    @SerialName("childOrderStrategies")
    val childOrderStrategies: List<OrderResponseDto>? = null,
    val orderActivityCollection: List<OrderActivityDto> = emptyList()
)

@Serializable
data class OrderLegResponseDto(
    val instruction: String? = null,
    val quantity: Double? = null,
    val instrument: OrderInstrumentDto? = null
)

@Serializable
data class OrderActivityDto(
    val activityType: String? = null,
    val executionType: String? = null,
    val quantity: Double? = null,
    val orderRemainingQuantity: Double? = null,
    val executionLegs: List<ExecutionLegDto> = emptyList()
)

@Serializable
data class ExecutionLegDto(
    val legId: Int? = null,
    val quantity: Double? = null,
    val mismarkedQuantity: Double? = null,
    val price: Double? = null,
    val time: String? = null
)
