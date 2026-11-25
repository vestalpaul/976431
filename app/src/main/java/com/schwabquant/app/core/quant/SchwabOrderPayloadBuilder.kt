package com.schwabquant.app.core.quant

import com.schwabquant.app.data.remote.dto.OrderInstrumentDto
import com.schwabquant.app.data.remote.dto.OrderLegDto
import com.schwabquant.app.data.remote.dto.OrderRequestDto
import com.schwabquant.app.data.remote.dto.SchwabAssetType
import com.schwabquant.app.data.remote.dto.SchwabComplexOrderStrategyType
import com.schwabquant.app.data.remote.dto.SchwabDuration
import com.schwabquant.app.data.remote.dto.SchwabOrderInstruction
import com.schwabquant.app.data.remote.dto.SchwabOrderStrategyType
import com.schwabquant.app.data.remote.dto.SchwabOrderType
import com.schwabquant.app.data.remote.dto.SchwabSession
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.data.remote.dto.toSchwabPriceString
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.TradingSession
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure-JVM serializer constructing exact Schwab Trader API v1 `POST /trader/v1/accounts/{hash}/orders`
 * payloads matching SCHWAB_API_REFERENCE.md Section 4, enforcing strict extended-hours rules,
 * share-class symbol normalization (`BRK.B` -> `BRK/B`), `MARKET` + `DAY` enforcement,
 * OCC 21-char symbols, positive 2-decimal BigDecimal prices, and strict null omission.
 */
@Singleton
class SchwabOrderPayloadBuilder @Inject constructor(
    private val occSymbolFormatter: OccSymbolFormatter
) {

    constructor() : this(OccSymbolFormatter())

    @OptIn(ExperimentalSerializationApi::class)
    val outboundJson: Json = Json {
        explicitNulls = false
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun toJsonString(ticket: OrderTicket): String {
        val dto = build(ticket)
        return outboundJson.encodeToString(OrderRequestDto.serializer(), dto)
    }

    fun build(ticket: OrderTicket): OrderRequestDto {
        if (ticket.strategyType == SchwabOrderStrategyType.OCO) {
            return buildOcoBracket(ticket)
        }

        require(ticket.legs.isNotEmpty()) { "Order must contain at least one leg." }

        val complex = ticket.complexStrategyType?.trim()?.uppercase()
        if (complex != null) {
            return buildComplexMultiLeg(ticket, complex)
        }

        require(ticket.legs.size == 1) {
            "Single-strategy orders require 1 leg unless complexOrderStrategyType is specified."
        }
        val leg = ticket.legs.single()
        return when (leg.category) {
            AssetCategory.OPTION -> buildSingleOption(ticket, leg)
            AssetCategory.EQUITY -> buildSingleEquity(ticket, leg)
            else -> error("Unsupported order asset category: ${leg.category}")
        }
    }

    private fun buildSingleEquity(ticket: OrderTicket, leg: OrderLeg): OrderRequestDto {
        val cleanSymbol = SchwabSymbolNormalizer.toApiSymbol(leg.symbol.trim())
        require(cleanSymbol.isNotBlank()) { "Equity order symbol must not be blank." }
        require(leg.side in EQUITY_SIDES) {
            "Equity order leg must use BUY, SELL, or BUY_TO_COVER, got ${leg.side}."
        }
        require(leg.quantity > 0) { "Order quantity must be positive." }

        if (ticket.orderType == OrderType.MARKET) {
            require(ticket.duration == OrderDuration.DAY) {
                "MARKET orders must use DAY duration (Schwab rejects MARKET + GOOD_TILL_CANCEL)."
            }
        }

        // SCHWAB_API_REFERENCE.md Section 4A: Extended-hours orders must use LIMIT + DAY.
        if (ticket.session != TradingSession.NORMAL) {
            require(ticket.orderType == OrderType.LIMIT && ticket.duration == OrderDuration.DAY) {
                "Extended-hours (${ticket.session.apiValue}) equity orders MUST use LIMIT and DAY."
            }
        }

        val priceStr = when (ticket.orderType) {
            OrderType.LIMIT, OrderType.STOP_LIMIT -> {
                val limit = requireNotNull(ticket.limitPrice) {
                    "${ticket.orderType.apiValue} order requires positive limitPrice."
                }
                limit.toSchwabPriceString()
            }
            OrderType.MARKET, OrderType.STOP -> null
            OrderType.NET_DEBIT, OrderType.NET_CREDIT -> {
                error("Single equity order cannot use ${ticket.orderType.apiValue}.")
            }
        }

        val stopPriceStr = when (ticket.orderType) {
            OrderType.STOP, OrderType.STOP_LIMIT -> {
                val stop = requireNotNull(ticket.stopPrice) {
                    "${ticket.orderType.apiValue} order requires positive stopPrice."
                }
                stop.toSchwabPriceString()
            }
            else -> null
        }

        return OrderRequestDto(
            orderType = ticket.orderType.apiValue,
            session = ticket.session.apiValue,
            price = priceStr,
            stopPrice = stopPriceStr,
            duration = ticket.duration.apiValue,
            complexOrderStrategyType = null,
            orderStrategyType = SchwabOrderStrategyType.SINGLE,
            orderLegCollection = listOf(toLegDto(leg, SchwabAssetType.EQUITY)),
            childOrderStrategies = null
        )
    }

    private fun buildSingleOption(ticket: OrderTicket, leg: OrderLeg): OrderRequestDto {
        require(ticket.session == TradingSession.NORMAL) {
            "Option orders are restricted to NORMAL session."
        }
        if (ticket.orderType == OrderType.MARKET) {
            require(ticket.duration == OrderDuration.DAY) {
                "MARKET orders must use DAY duration (Schwab rejects MARKET + GOOD_TILL_CANCEL)."
            }
        }
        require(leg.side in OPTION_SIDES) {
            "Option leg must use BUY_TO_OPEN/SELL_TO_OPEN/BUY_TO_CLOSE/SELL_TO_CLOSE, got ${leg.side}."
        }
        require(occSymbolFormatter.parse(leg.symbol) != null) {
            "Invalid 21-char OCC option symbol: '${leg.symbol}'."
        }
        require(leg.quantity > 0) { "Order quantity must be positive." }

        val priceStr = when (ticket.orderType) {
            OrderType.LIMIT, OrderType.STOP_LIMIT -> {
                requireNotNull(ticket.limitPrice) { "Option LIMIT order requires limitPrice." }
                    .toSchwabPriceString()
            }
            OrderType.MARKET -> null
            else -> error("Single option order does not support orderType=${ticket.orderType}.")
        }
        val stopStr = if (ticket.orderType == OrderType.STOP_LIMIT) {
            requireNotNull(ticket.stopPrice) { "STOP_LIMIT order requires stopPrice." }
                .toSchwabPriceString()
        } else {
            null
        }

        return OrderRequestDto(
            orderType = ticket.orderType.apiValue,
            session = SchwabSession.NORMAL,
            price = priceStr,
            stopPrice = stopStr,
            duration = ticket.duration.apiValue,
            complexOrderStrategyType = null,
            orderStrategyType = SchwabOrderStrategyType.SINGLE,
            orderLegCollection = listOf(toLegDto(leg, SchwabAssetType.OPTION)),
            childOrderStrategies = null
        )
    }

    private fun buildComplexMultiLeg(ticket: OrderTicket, complex: String): OrderRequestDto {
        require(ticket.session == TradingSession.NORMAL) {
            "Multi-leg option spreads require NORMAL session."
        }
        require(ticket.legs.all { it.category == AssetCategory.OPTION }) {
            "All legs in a $complex spread must be OPTION contracts."
        }
        ticket.legs.forEach { leg ->
            require(leg.side in OPTION_SIDES) {
                "Option spread leg must use BTO/STO/BTC/STC, got ${leg.side}."
            }
            require(occSymbolFormatter.parse(leg.symbol) != null) {
                "Invalid 21-char OCC symbol in $complex spread: '${leg.symbol}'."
            }
            require(leg.quantity > 0) { "Leg quantity must be positive." }
        }

        when (complex) {
            SchwabComplexOrderStrategyType.DIAGONAL -> {
                require(ticket.legs.size == 2) { "DIAGONAL requires 2 option legs." }
                require(ticket.orderType == OrderType.NET_DEBIT || ticket.orderType == OrderType.NET_CREDIT) {
                    "DIAGONAL requires NET_DEBIT or NET_CREDIT orderType."
                }
            }
            SchwabComplexOrderStrategyType.VERTICAL -> {
                require(ticket.legs.size == 2) { "VERTICAL requires 2 option legs." }
                require(ticket.orderType == OrderType.NET_CREDIT || ticket.orderType == OrderType.NET_DEBIT) {
                    "VERTICAL requires NET_CREDIT or NET_DEBIT."
                }
            }
            SchwabComplexOrderStrategyType.CALENDAR -> {
                require(ticket.legs.size == 2) { "CALENDAR requires 2 option legs." }
                require(ticket.orderType == OrderType.NET_DEBIT || ticket.orderType == OrderType.NET_CREDIT) {
                    "CALENDAR requires NET_DEBIT or NET_CREDIT."
                }
            }
            SchwabComplexOrderStrategyType.IRON_CONDOR -> {
                require(ticket.legs.size == 4) { "IRON_CONDOR requires 4 option legs." }
                require(ticket.orderType == OrderType.NET_CREDIT || ticket.orderType == OrderType.NET_DEBIT) {
                    "IRON_CONDOR requires NET_CREDIT or NET_DEBIT."
                }
            }
            else -> error("Unsupported complexOrderStrategyType: '$complex'.")
        }

        val priceStr = requireNotNull(ticket.limitPrice) {
            "$complex spread requires a positive net limitPrice."
        }.toSchwabPriceString()

        return OrderRequestDto(
            orderType = ticket.orderType.apiValue,
            session = SchwabSession.NORMAL,
            price = priceStr,
            stopPrice = null,
            duration = ticket.duration.apiValue,
            complexOrderStrategyType = complex,
            orderStrategyType = SchwabOrderStrategyType.SINGLE,
            orderLegCollection = ticket.legs.map { toLegDto(it, SchwabAssetType.OPTION) },
            childOrderStrategies = null
        )
    }

    private fun buildOcoBracket(ticket: OrderTicket): OrderRequestDto {
        require(ticket.childOrders.size == 2) {
            "OCO equity bracket requires exactly 2 childOrderStrategies (LIMIT + STOP)."
        }

        val limitChild = ticket.childOrders.firstOrNull { it.orderType == OrderType.LIMIT }
            ?: error("OCO bracket requires a LIMIT take-profit child order.")
        val stopChild = ticket.childOrders.firstOrNull { it.orderType == OrderType.STOP }
            ?: error("OCO bracket requires a STOP hard-stop child order.")

        val builtLimit = buildOcoChildEquity(limitChild, isLimit = true)
        val builtStop = buildOcoChildEquity(stopChild, isLimit = false)

        val wireTp = BigDecimal(requireNotNull(builtLimit.price) { "OCO LIMIT child requires serialized price." })
        val wireSl = BigDecimal(requireNotNull(builtStop.stopPrice) { "OCO STOP child requires serialized stopPrice." })
        val limitLeg = limitChild.legs.single()
        val stopLeg = stopChild.legs.single()
        val normLimitSym = SchwabSymbolNormalizer.toApiSymbol(limitLeg.symbol.trim())
        val normStopSym = SchwabSymbolNormalizer.toApiSymbol(stopLeg.symbol.trim())
        require(normLimitSym == normStopSym && limitLeg.quantity == stopLeg.quantity) {
            "OCO bracket children must target the same symbol and quantity ($normLimitSym x${limitLeg.quantity} vs $normStopSym x${stopLeg.quantity})."
        }
        require(limitLeg.side == stopLeg.side) {
            "OCO bracket children must share the same exit instruction (${limitLeg.side} vs ${stopLeg.side})."
        }
        if (limitLeg.side == OrderSide.SELL) {
            require(wireTp > wireSl) {
                "OCO SELL bracket take-profit limit ($wireTp) must be strictly greater than stop price ($wireSl)."
            }
        } else {
            require(wireTp < wireSl) {
                "OCO BUY bracket take-profit limit ($wireTp) must be strictly less than stop price ($wireSl)."
            }
        }

        val builtChildren = listOf(builtLimit, builtStop)

        return OrderRequestDto(
            orderType = null,
            session = null,
            price = null,
            stopPrice = null,
            duration = null,
            complexOrderStrategyType = null,
            orderStrategyType = SchwabOrderStrategyType.OCO,
            orderLegCollection = null,
            childOrderStrategies = builtChildren
        )
    }

    private fun buildOcoChildEquity(child: OrderTicket, isLimit: Boolean): OrderRequestDto {
        val leg = child.legs.singleOrNull()
            ?: error("OCO child order must contain 1 equity leg.")
        val cleanSymbol = SchwabSymbolNormalizer.toApiSymbol(leg.symbol.trim())
        require(cleanSymbol.isNotBlank()) { "OCO equity order symbol must not be blank." }
        require(leg.category == AssetCategory.EQUITY) {
            "OCO equity bracket legs must be EQUITY."
        }
        require(leg.side in EQUITY_SIDES) {
            "OCO equity leg must use BUY, SELL, or BUY_TO_COVER."
        }
        require(leg.quantity > 0) { "OCO child leg quantity must be positive." }

        val priceStr = if (isLimit) {
            requireNotNull(child.limitPrice) { "OCO LIMIT child requires positive limitPrice." }
                .toSchwabPriceString()
        } else {
            null
        }
        val stopStr = if (!isLimit) {
            requireNotNull(child.stopPrice) { "OCO STOP child requires positive stopPrice." }
                .toSchwabPriceString()
        } else {
            null
        }

        return OrderRequestDto(
            orderType = if (isLimit) SchwabOrderType.LIMIT else SchwabOrderType.STOP,
            session = SchwabSession.NORMAL,
            price = priceStr,
            stopPrice = stopStr,
            duration = SchwabDuration.GOOD_TILL_CANCEL,
            complexOrderStrategyType = null,
            orderStrategyType = SchwabOrderStrategyType.SINGLE,
            orderLegCollection = listOf(toLegDto(leg, SchwabAssetType.EQUITY)),
            childOrderStrategies = null
        )
    }

    private fun toLegDto(leg: OrderLeg, assetType: String): OrderLegDto {
        val wireSymbol = if (assetType == SchwabAssetType.EQUITY) {
            SchwabSymbolNormalizer.toApiSymbol(leg.symbol.trim())
        } else {
            leg.symbol
        }
        val instruction = when (leg.side) {
            OrderSide.BUY -> SchwabOrderInstruction.BUY
            OrderSide.SELL -> SchwabOrderInstruction.SELL
            OrderSide.BUY_TO_COVER -> SchwabOrderInstruction.BUY_TO_COVER
            OrderSide.BUY_TO_OPEN -> SchwabOrderInstruction.BUY_TO_OPEN
            OrderSide.SELL_TO_OPEN -> SchwabOrderInstruction.SELL_TO_OPEN
            OrderSide.BUY_TO_CLOSE -> SchwabOrderInstruction.BUY_TO_CLOSE
            OrderSide.SELL_TO_CLOSE -> SchwabOrderInstruction.SELL_TO_CLOSE
        }
        return OrderLegDto(
            instruction = instruction,
            quantity = leg.quantity,
            instrument = OrderInstrumentDto(
                symbol = wireSymbol,
                assetType = assetType,
                underlyingSymbol = null
            )
        )
    }

    companion object {
        private val EQUITY_SIDES = setOf(
            OrderSide.BUY,
            OrderSide.SELL,
            OrderSide.BUY_TO_COVER
        )
        private val OPTION_SIDES = setOf(
            OrderSide.BUY_TO_OPEN,
            OrderSide.SELL_TO_OPEN,
            OrderSide.BUY_TO_CLOSE,
            OrderSide.SELL_TO_CLOSE
        )
    }
}
