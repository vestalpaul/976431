package com.schwabquant.app.core.quant

import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.TradingSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SchwabOrderPayloadBuilderTest {

    private val builder = SchwabOrderPayloadBuilder()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `builds exact SEAMLESS extended hours equity LIMIT DAY payload and rejects STOP`() {
        val seamlessLimit = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.SEAMLESS,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("269.5"),
            legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 16))
        )
        val raw = builder.toJsonString(seamlessLimit)
        val root = json.parseToJsonElement(raw).jsonObject

        assertEquals("LIMIT", root["orderType"]!!.jsonPrimitive.content)
        assertEquals("SEAMLESS", root["session"]!!.jsonPrimitive.content)
        assertEquals("269.50", root["price"]!!.jsonPrimitive.content)
        assertEquals("DAY", root["duration"]!!.jsonPrimitive.content)
        assertEquals("SINGLE", root["orderStrategyType"]!!.jsonPrimitive.content)

        // Safeguard: null fields completely omitted
        assertFalse(raw.contains("\"stopPrice\""))
        assertFalse(raw.contains("\"complexOrderStrategyType\""))
        assertFalse(raw.contains("\"underlyingSymbol\""))
        assertFalse(raw.contains("\"childOrderStrategies\""))

        // Extended hours STOP must be rejected by builder
        val badExtendedStop = seamlessLimit.copy(
            orderType = OrderType.STOP,
            stopPrice = BigDecimal("269.50"),
            limitPrice = null
        )
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(badExtendedStop)
        }
    }

    @Test
    fun `builds MARKET and STOP and Single Option payloads with strict null omission`() {
        val marketEquity = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.MARKET,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            legs = listOf(OrderLeg("SPY", AssetCategory.EQUITY, OrderSide.BUY, 5))
        )
        val marketJson = builder.toJsonString(marketEquity)
        assertFalse(marketJson.contains("\"price\""))
        assertFalse(marketJson.contains("\"stopPrice\""))
        assertFalse(marketJson.contains("\"underlyingSymbol\""))

        val singleOption = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("2.45"),
            legs = listOf(OrderLeg("IWM   261218C00235000", AssetCategory.OPTION, OrderSide.BUY_TO_CLOSE, 1))
        )
        val optObj = json.parseToJsonElement(builder.toJsonString(singleOption)).jsonObject
        val optLeg = optObj["orderLegCollection"]!!.jsonArray[0].jsonObject
        assertEquals("BUY_TO_CLOSE", optLeg["instruction"]!!.jsonPrimitive.content)
        assertEquals("OPTION", optLeg["instrument"]!!.jsonObject["assetType"]!!.jsonPrimitive.content)
        assertEquals("IWM   261218C00235000", optLeg["instrument"]!!.jsonObject["symbol"]!!.jsonPrimitive.content)
    }

    @Test
    fun `builds DIAGONAL PMCC, VERTICAL Credit, CALENDAR, and 4-leg IRON CONDOR payloads`() {
        val pmcc = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.NET_DEBIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            strategyType = "SINGLE",
            complexStrategyType = "DIAGONAL",
            limitPrice = BigDecimal("61.3600001"),
            legs = listOf(
                OrderLeg("IWM   280121C00220000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1),
                OrderLeg("IWM   261218C00284000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 1)
            )
        )
        val pmccObj = json.parseToJsonElement(builder.toJsonString(pmcc)).jsonObject
        assertEquals("NET_DEBIT", pmccObj["orderType"]!!.jsonPrimitive.content)
        assertEquals("DIAGONAL", pmccObj["complexOrderStrategyType"]!!.jsonPrimitive.content)
        assertEquals("61.36", pmccObj["price"]!!.jsonPrimitive.content)
        assertEquals(2, pmccObj["orderLegCollection"]!!.jsonArray.size)

        val vertical = pmcc.copy(
            orderType = OrderType.NET_CREDIT,
            complexStrategyType = "VERTICAL",
            limitPrice = BigDecimal("1.65")
        )
        assertTrue(builder.toJsonString(vertical).contains("\"complexOrderStrategyType\":\"VERTICAL\""))

        val calendar = pmcc.copy(
            orderType = OrderType.NET_DEBIT,
            complexStrategyType = "CALENDAR",
            limitPrice = BigDecimal("2.10")
        )
        assertTrue(builder.toJsonString(calendar).contains("\"complexOrderStrategyType\":\"CALENDAR\""))

        val condor = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.NET_CREDIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            strategyType = "SINGLE",
            complexStrategyType = "IRON_CONDOR",
            limitPrice = BigDecimal("2.40"),
            legs = listOf(
                OrderLeg("SPY   261218P00550000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1),
                OrderLeg("SPY   261218P00560000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 1),
                OrderLeg("SPY   261218C00605000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 1),
                OrderLeg("SPY   261218C00615000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1)
            )
        )
        val condorObj = json.parseToJsonElement(builder.toJsonString(condor)).jsonObject
        assertEquals("NET_CREDIT", condorObj["orderType"]!!.jsonPrimitive.content)
        assertEquals("IRON_CONDOR", condorObj["complexOrderStrategyType"]!!.jsonPrimitive.content)
        assertEquals(4, condorObj["orderLegCollection"]!!.jsonArray.size)
    }

    @Test
    fun `builds OCO Equity Bracket with null top level price fields completely omitted`() {
        val oco = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.GOOD_TILL_CANCEL,
            strategyType = "OCO",
            legs = emptyList(),
            childOrders = listOf(
                OrderTicket(
                    accountHash = "hash",
                    executionMode = ExecutionMode.LIVE,
                    orderType = OrderType.LIMIT,
                    session = TradingSession.NORMAL,
                    duration = OrderDuration.GOOD_TILL_CANCEL,
                    limitPrice = BigDecimal("300.00"),
                    legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 16))
                ),
                OrderTicket(
                    accountHash = "hash",
                    executionMode = ExecutionMode.LIVE,
                    orderType = OrderType.STOP,
                    session = TradingSession.NORMAL,
                    duration = OrderDuration.GOOD_TILL_CANCEL,
                    stopPrice = BigDecimal("270.00"),
                    legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 16))
                )
            )
        )

        val raw = builder.toJsonString(oco)
        val obj = json.parseToJsonElement(raw).jsonObject
        assertEquals("OCO", obj["orderStrategyType"]!!.jsonPrimitive.content)
        // Top-level OCO must omit orderType, session, duration, price, stopPrice, orderLegCollection
        assertFalse(obj.containsKey("orderType"))
        assertFalse(obj.containsKey("session"))
        assertFalse(obj.containsKey("duration"))
        assertFalse(obj.containsKey("price"))
        assertFalse(obj.containsKey("stopPrice"))
        assertFalse(obj.containsKey("orderLegCollection"))

        val children = obj["childOrderStrategies"]!!.jsonArray
        assertEquals(2, children.size)
        assertEquals("LIMIT", children[0].jsonObject["orderType"]!!.jsonPrimitive.content)
        assertEquals("300.00", children[0].jsonObject["price"]!!.jsonPrimitive.content)
        assertFalse(children[0].jsonObject.containsKey("stopPrice"))
        assertEquals("STOP", children[1].jsonObject["orderType"]!!.jsonPrimitive.content)
        assertEquals("270.00", children[1].jsonObject["stopPrice"]!!.jsonPrimitive.content)
        assertFalse(children[1].jsonObject.containsKey("price"))
    }

    @Test
    fun `H-2 and M-1 reject inverted OCO bracket prices and sub-half-cent zero-rounded prices`() {
        // M-1: 0.004 rounds to 0.00 at scale 2 and must be rejected
        val subCentOrder = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.SEAMLESS,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("0.004"),
            legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 10))
        )
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(subCentOrder)
        }

        // H-2: SELL OCO bracket with takeProfit ($250) <= stopPrice ($260) must be rejected
        val invertedOco = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.GOOD_TILL_CANCEL,
            strategyType = "OCO",
            legs = emptyList(),
            childOrders = listOf(
                OrderTicket(
                    accountHash = "hash",
                    executionMode = ExecutionMode.LIVE,
                    orderType = OrderType.LIMIT,
                    session = TradingSession.NORMAL,
                    duration = OrderDuration.GOOD_TILL_CANCEL,
                    limitPrice = BigDecimal("250.00"),
                    legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 16))
                ),
                OrderTicket(
                    accountHash = "hash",
                    executionMode = ExecutionMode.LIVE,
                    orderType = OrderType.STOP,
                    session = TradingSession.NORMAL,
                    duration = OrderDuration.GOOD_TILL_CANCEL,
                    stopPrice = BigDecimal("260.00"),
                    legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 16))
                )
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(invertedOco)
        }
    }

    @Test
    fun `H-2 normalizes BRK dot B to BRK slash B and rejects MARKET GTC and blank symbols`() {
        val brkTicket = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("445.00"),
            legs = listOf(OrderLeg("  brk.b  ", AssetCategory.EQUITY, OrderSide.BUY, 2))
        )
        val raw = builder.toJsonString(brkTicket)
        val obj = json.parseToJsonElement(raw).jsonObject
        val sym = obj["orderLegCollection"]!!.jsonArray[0].jsonObject["instrument"]!!.jsonObject["symbol"]!!.jsonPrimitive.content
        assertEquals("BRK/B", sym)

        val marketGtcEquity = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.MARKET,
            session = TradingSession.NORMAL,
            duration = OrderDuration.GOOD_TILL_CANCEL,
            legs = listOf(OrderLeg("SPY", AssetCategory.EQUITY, OrderSide.BUY, 1))
        )
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(marketGtcEquity)
        }

        val marketGtcOption = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.MARKET,
            session = TradingSession.NORMAL,
            duration = OrderDuration.GOOD_TILL_CANCEL,
            legs = listOf(OrderLeg("SPY   261218C00550000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1))
        )
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(marketGtcOption)
        }

        val blankSymbol = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("100.00"),
            legs = listOf(OrderLeg("   ", AssetCategory.EQUITY, OrderSide.BUY, 1))
        )
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(blankSymbol)
        }
    }

    @Test
    fun `H-6 BUY_TO_COVER serializes cleanly on single equity and OCO equity payloads`() {
        val coverSeamless = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.SEAMLESS,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("140.25"),
            legs = listOf(OrderLeg("NVDA", AssetCategory.EQUITY, OrderSide.BUY_TO_COVER, 15))
        )
        val obj = json.parseToJsonElement(builder.toJsonString(coverSeamless)).jsonObject
        val leg = obj["orderLegCollection"]!!.jsonArray[0].jsonObject
        assertEquals("BUY_TO_COVER", leg["instruction"]!!.jsonPrimitive.content)
        assertEquals("EQUITY", leg["instrument"]!!.jsonObject["assetType"]!!.jsonPrimitive.content)

        val coverOco = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.GOOD_TILL_CANCEL,
            strategyType = "OCO",
            legs = emptyList(),
            childOrders = listOf(
                OrderTicket(
                    accountHash = "hash",
                    executionMode = ExecutionMode.LIVE,
                    orderType = OrderType.LIMIT,
                    session = TradingSession.NORMAL,
                    duration = OrderDuration.GOOD_TILL_CANCEL,
                    limitPrice = BigDecimal("125.00"),
                    legs = listOf(OrderLeg("NVDA", AssetCategory.EQUITY, OrderSide.BUY_TO_COVER, 10))
                ),
                OrderTicket(
                    accountHash = "hash",
                    executionMode = ExecutionMode.LIVE,
                    orderType = OrderType.STOP,
                    session = TradingSession.NORMAL,
                    duration = OrderDuration.GOOD_TILL_CANCEL,
                    stopPrice = BigDecimal("145.00"),
                    legs = listOf(OrderLeg("NVDA", AssetCategory.EQUITY, OrderSide.BUY_TO_COVER, 10))
                )
            )
        )
        val ocoChildren = json.parseToJsonElement(builder.toJsonString(coverOco))
            .jsonObject["childOrderStrategies"]!!.jsonArray
        assertEquals(
            "BUY_TO_COVER",
            ocoChildren[0].jsonObject["orderLegCollection"]!!.jsonArray[0].jsonObject["instruction"]!!.jsonPrimitive.content
        )
        assertEquals(
            "BUY_TO_COVER",
            ocoChildren[1].jsonObject["orderLegCollection"]!!.jsonArray[0].jsonObject["instruction"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `MEDIUM-1 build OCO validates post-rounded wire prices and matching symbol and quantity`() {
        fun ocoTicket(tpChild: OrderTicket, slChild: OrderTicket) = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.GOOD_TILL_CANCEL,
            strategyType = "OCO",
            legs = emptyList(),
            childOrders = listOf(tpChild, slChild)
        )

        // Rounding collides 100.004 (rounds down to 100.00) with 100.001 (rounds down to 100.00)
        val collidingTp = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.GOOD_TILL_CANCEL,
            limitPrice = BigDecimal("100.004"),
            legs = listOf(OrderLeg("SPY", AssetCategory.EQUITY, OrderSide.SELL, 10))
        )
        val collidingSl = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.STOP,
            session = TradingSession.NORMAL,
            duration = OrderDuration.GOOD_TILL_CANCEL,
            stopPrice = BigDecimal("100.001"),
            legs = listOf(OrderLeg("SPY", AssetCategory.EQUITY, OrderSide.SELL, 10))
        )
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(ocoTicket(collidingTp, collidingSl))
        }

        // Inverted buy-to-cover bracket (TP above SL) must fail
        val invertedCoverTp = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.GOOD_TILL_CANCEL,
            limitPrice = BigDecimal("101.00"),
            legs = listOf(OrderLeg("SPY", AssetCategory.EQUITY, OrderSide.BUY_TO_COVER, 10))
        )
        val invertedCoverSl = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.STOP,
            session = TradingSession.NORMAL,
            duration = OrderDuration.GOOD_TILL_CANCEL,
            stopPrice = BigDecimal("100.00"),
            legs = listOf(OrderLeg("SPY", AssetCategory.EQUITY, OrderSide.BUY_TO_COVER, 10))
        )
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(ocoTicket(invertedCoverTp, invertedCoverSl))
        }

        // Mismatched quantity must fail
        val mismatchQtySl = collidingSl.copy(
            stopPrice = BigDecimal("95.00"),
            legs = listOf(OrderLeg("SPY", AssetCategory.EQUITY, OrderSide.SELL, 5))
        )
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(ocoTicket(collidingTp, mismatchQtySl))
        }

        // Mismatched symbol must fail
        val mismatchSymSl = collidingSl.copy(
            stopPrice = BigDecimal("95.00"),
            legs = listOf(OrderLeg("QQQ", AssetCategory.EQUITY, OrderSide.SELL, 10))
        )
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(ocoTicket(collidingTp, mismatchSymSl))
        }

        // Valid normalized symbol ("BRK/B" vs "brk.b") with clear post-rounded gap succeeds
        val validTp = collidingTp.copy(
            limitPrice = BigDecimal("420.00"),
            legs = listOf(OrderLeg("BRK/B", AssetCategory.EQUITY, OrderSide.SELL, 4))
        )
        val validSl = collidingSl.copy(
            stopPrice = BigDecimal("400.00"),
            legs = listOf(OrderLeg("brk.b", AssetCategory.EQUITY, OrderSide.SELL, 4))
        )
        val built = builder.build(ocoTicket(validTp, validSl))
        assertEquals("OCO", built.orderStrategyType)
        assertEquals(2, built.childOrderStrategies?.size)
    }

    @Test
    fun `V1-9-1 MEDIUM-2 builds NET_CREDIT DIAGONAL, NET_CREDIT CALENDAR, and NET_DEBIT IRON_CONDOR payloads`() {
        val creditDiag = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.NET_CREDIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            strategyType = "SINGLE",
            complexStrategyType = "DIAGONAL",
            limitPrice = BigDecimal("2.45"),
            legs = listOf(
                OrderLeg("IWM   280121C00240000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1),
                OrderLeg("IWM   261218C00210000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 1)
            )
        )
        val diagJson = json.parseToJsonElement(builder.toJsonString(creditDiag)).jsonObject
        assertEquals("NET_CREDIT", diagJson["orderType"]!!.jsonPrimitive.content)
        assertEquals("DIAGONAL", diagJson["complexOrderStrategyType"]!!.jsonPrimitive.content)

        val creditCal = creditDiag.copy(complexStrategyType = "CALENDAR")
        val calJson = json.parseToJsonElement(builder.toJsonString(creditCal)).jsonObject
        assertEquals("NET_CREDIT", calJson["orderType"]!!.jsonPrimitive.content)
        assertEquals("CALENDAR", calJson["complexOrderStrategyType"]!!.jsonPrimitive.content)

        val debitCondor = OrderTicket(
            accountHash = "hash",
            executionMode = ExecutionMode.LIVE,
            orderType = OrderType.NET_DEBIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            strategyType = "SINGLE",
            complexStrategyType = "IRON_CONDOR",
            limitPrice = BigDecimal("1.85"),
            legs = listOf(
                OrderLeg("IWM   261218P00205000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1),
                OrderLeg("IWM   261218P00210000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 1),
                OrderLeg("IWM   261218C00235000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 1),
                OrderLeg("IWM   261218C00240000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1)
            )
        )
        val condorJson = json.parseToJsonElement(builder.toJsonString(debitCondor)).jsonObject
        assertEquals("NET_DEBIT", condorJson["orderType"]!!.jsonPrimitive.content)
        assertEquals("IRON_CONDOR", condorJson["complexOrderStrategyType"]!!.jsonPrimitive.content)
    }
}
