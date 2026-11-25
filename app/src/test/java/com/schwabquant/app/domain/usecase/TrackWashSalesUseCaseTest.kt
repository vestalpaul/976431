package com.schwabquant.app.domain.usecase

import com.schwabquant.app.core.quant.OccSymbolFormatter
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.entity.WashSaleTradeEntity
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import com.schwabquant.app.data.remote.dto.ExecutionLegDto
import com.schwabquant.app.data.remote.dto.OrderActivityDto
import com.schwabquant.app.data.remote.dto.OrderInstrumentDto
import com.schwabquant.app.data.remote.dto.OrderLegResponseDto
import com.schwabquant.app.data.remote.dto.OrderResponseDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TrackWashSalesUseCaseTest {

    private val nyZone = ZoneId.of("America/New_York")
    private val useCase = TrackWashSalesUseCase(
        washSaleDao = mockk<WashSaleDao>(relaxed = true),
        traderApi = mockk<SchwabTraderApi>(relaxed = true),
        credentialVault = mockk<CredentialVault>(relaxed = true),
        occSymbolFormatter = OccSymbolFormatter()
    )

    @Test
    fun `detects cross-asset stock loss to NVDA Call LEAPS wash sale`() {
        val lossDate = LocalDate.of(2026, 11, 10)
        val leapsDate = LocalDate.of(2026, 11, 17)

        val flags = useCase.evaluateWashSales(
            trades = listOf(
                WashSaleLot(
                    id = 1L,
                    underlyingSymbol = "NVDA",
                    symbol = "NVDA",
                    assetType = "EQUITY",
                    instruction = "SELL",
                    quantity = 22,
                    price = 136.50,
                    realizedPnl = -318.44,
                    tradeTimestampEpochMs = nyEpoch(lossDate),
                    source = "MANUAL"
                ),
                WashSaleLot(
                    id = 2L,
                    underlyingSymbol = "NVDA",
                    symbol = "NVDA  280121C00165000",
                    assetType = "OPTION",
                    instruction = "BUY_TO_OPEN",
                    quantity = 1,
                    price = 45.00,
                    realizedPnl = null,
                    tradeTimestampEpochMs = nyEpoch(leapsDate),
                    source = "MANUAL"
                )
            )
        )

        assertEquals(1, flags.size)
        val flag = flags.first()
        assertTrue(flag.isCrossAssetStockToOption)
        assertEquals(0, BigDecimal("318.44").compareTo(flag.disallowedLossAmount))
        assertEquals(0, BigDecimal("4818.44").compareTo(flag.adjustedReplacementBasis))
        assertEquals(LocalDate.of(2026, 12, 11), flag.clearToRebuyDate)
    }

    @Test
    fun `does NOT match stock loss against Put option purchase`() {
        val lossDate = LocalDate.of(2026, 11, 10)
        val flags = useCase.evaluateWashSales(
            trades = listOf(
                WashSaleLot(1L, "NVDA", "NVDA", "EQUITY", "SELL", 50, 130.0, -400.0, nyEpoch(lossDate), "MANUAL"),
                WashSaleLot(2L, "NVDA", "NVDA  280121P00120000", "OPTION", "BUY_TO_OPEN", 1, 18.0, null, nyEpoch(lossDate.plusDays(3)), "MANUAL")
            )
        )
        assertTrue(flags.isEmpty())
    }

    @Test
    fun `enforces exact 30-day before and after boundaries vs Day 31 safe clearance`() {
        val lossDate = LocalDate.of(2026, 10, 15)
        val lossLot = WashSaleLot(10L, "SPY", "SPY", "EQUITY", "SELL", 10, 580.0, -100.0, nyEpoch(lossDate), "MANUAL")

        // Day -30 triggers
        val dayMinus30 = useCase.evaluateWashSales(
            listOf(
                WashSaleLot(1L, "SPY", "SPY", "EQUITY", "BUY", 10, 575.0, null, nyEpoch(lossDate.minusDays(30)), "MANUAL"),
                lossLot
            )
        )
        assertEquals(1, dayMinus30.size)

        // Day -31 is safe
        val dayMinus31 = useCase.evaluateWashSales(
            listOf(
                WashSaleLot(2L, "SPY", "SPY", "EQUITY", "BUY", 10, 575.0, null, nyEpoch(lossDate.minusDays(31)), "MANUAL"),
                lossLot
            )
        )
        assertTrue(dayMinus31.isEmpty())

        // Day +30 triggers
        val dayPlus30 = useCase.evaluateWashSales(
            listOf(
                lossLot,
                WashSaleLot(3L, "SPY", "SPY", "EQUITY", "BUY", 10, 575.0, null, nyEpoch(lossDate.plusDays(30)), "MANUAL")
            )
        )
        assertEquals(1, dayPlus30.size)

        // Day +31 is safe
        val dayPlus31 = useCase.evaluateWashSales(
            listOf(
                lossLot,
                WashSaleLot(4L, "SPY", "SPY", "EQUITY", "BUY", 10, 575.0, null, nyEpoch(lossDate.plusDays(31)), "MANUAL")
            )
        )
        assertTrue(dayPlus31.isEmpty())
    }

    @Test
    fun `prorates partial share matching and computes FIFO realized PnL when omitted`() {
        val d0 = LocalDate.of(2026, 10, 1)
        val d1 = LocalDate.of(2026, 10, 10)
        val d2 = LocalDate.of(2026, 10, 15)

        val flags = useCase.evaluateWashSales(
            trades = listOf(
                // Buy 100 GOOG @ $260
                WashSaleLot(1L, "GOOG", "GOOG", "EQUITY", "BUY", 100, 260.0, null, nyEpoch(d0), "SCHWAB_SYNC"),
                // Sell 100 GOOG @ $255 (FIFO loss = -$500; realizedPnl = null)
                WashSaleLot(2L, "GOOG", "GOOG", "EQUITY", "SELL", 100, 255.0, null, nyEpoch(d1), "SCHWAB_SYNC"),
                // Rebuy only 40 shares @ $256 within 5 days -> 40% ($200) disallowed, $300 allowed
                WashSaleLot(3L, "GOOG", "GOOG", "EQUITY", "BUY", 40, 256.0, null, nyEpoch(d2), "SCHWAB_SYNC")
            )
        )

        assertEquals(1, flags.size)
        val flag = flags.first()
        assertFalse(flag.isCrossAssetStockToOption)
        assertEquals(40, flag.matchedShareEquivalents)
        assertEquals(0, BigDecimal("200.00").compareTo(flag.disallowedLossAmount))
        assertEquals(0, BigDecimal("300.00").compareTo(flag.allowedLossAmount))
        assertEquals(0, BigDecimal("10440.00").compareTo(flag.adjustedReplacementBasis))
    }

    @Test
    fun `V1-9-1 MEDIUM-1 syncs +0000 offset timestamps, per-legId prices, and nested OCO child fills`() = runTest {
        val inserted = mutableListOf<WashSaleTradeEntity>()
        val washDao = mockk<WashSaleDao>(relaxed = true) {
            coEvery { countByExternalOrderId(any()) } returns 0
            coEvery { insert(capture(inserted)) } returns 1L
        }
        val traderApi = mockk<SchwabTraderApi>(relaxed = true)
        val vault = mockk<CredentialVault>(relaxed = true) {
            every { getPrimaryAccountHash() } returns "HASH_SYNC"
        }

        // Order 1: +0000 offset timestamp & multi-leg distinct per-legId prices
        val multiLegOrder = OrderResponseDto(
            orderId = 9001L,
            status = "FILLED",
            closeTime = "2026-10-15T14:30:00+0000",
            enteredTime = "2026-10-15T14:00:00+0000",
            price = 61.30,
            orderLegCollection = listOf(
                OrderLegResponseDto(
                    instruction = "BUY_TO_OPEN",
                    quantity = 1.0,
                    instrument = OrderInstrumentDto("SPY   270917C00540000", "OPTION", "SPY"),
                    legId = 1
                ),
                OrderLegResponseDto(
                    instruction = "SELL_TO_OPEN",
                    quantity = 1.0,
                    instrument = OrderInstrumentDto("SPY   261218C00590000", "OPTION", "SPY"),
                    legId = 2
                )
            ),
            orderActivityCollection = listOf(
                OrderActivityDto(
                    activityType = "EXECUTION",
                    executionLegs = listOf(
                        ExecutionLegDto(
                            legId = 1,
                            quantity = 1.0,
                            price = 64.50,
                            time = "2026-10-15T14:30:00+0000"
                        ),
                        ExecutionLegDto(
                            legId = 2,
                            quantity = 1.0,
                            price = 3.20,
                            time = "2026-10-15T14:30:00+0000"
                        )
                    )
                )
            )
        )

        // Order 2: Parent OCO wrapper containing FILLED child STOP loss order
        val parentOcoOrder = OrderResponseDto(
            orderId = 9002L,
            orderStrategyType = "OCO",
            status = "WORKING",
            childOrderStrategies = listOf(
                OrderResponseDto(
                    orderId = 9003L,
                    orderType = "LIMIT",
                    status = "CANCELED",
                    orderLegCollection = listOf(
                        OrderLegResponseDto("SELL", 10.0, OrderInstrumentDto("GOOG", "EQUITY"))
                    )
                ),
                OrderResponseDto(
                    orderId = 9004L,
                    orderType = "STOP",
                    status = "FILLED",
                    closeTime = "2026-10-16T15:45:00+0000",
                    orderLegCollection = listOf(
                        OrderLegResponseDto("SELL", 10.0, OrderInstrumentDto("GOOG", "EQUITY"), legId = 1)
                    ),
                    orderActivityCollection = listOf(
                        OrderActivityDto(
                            activityType = "EXECUTION",
                            executionLegs = listOf(
                                ExecutionLegDto(legId = 1, quantity = 10.0, price = 252.40, time = "2026-10-16T15:45:00+0000")
                            )
                        )
                    )
                )
            )
        )

        coEvery { traderApi.getOrders(any(), any(), any()) } returns listOf(multiLegOrder, parentOcoOrder) andThen emptyList()

        val syncUseCase = TrackWashSalesUseCase(
            washSaleDao = washDao,
            traderApi = traderApi,
            credentialVault = vault,
            occSymbolFormatter = OccSymbolFormatter()
        )
        val count = syncUseCase.syncFilledOrdersFromSchwab(
            accountHash = "HASH_SYNC",
            nowInstant = Instant.parse("2026-11-24T12:00:00Z")
        ).getOrThrow()

        assertEquals(3, count)
        assertEquals(3, inserted.size)

        val expectedOct15Ms = Instant.parse("2026-10-15T14:30:00Z").toEpochMilli()
        val leg1 = inserted.first { it.symbol == "SPY   270917C00540000" }
        val leg2 = inserted.first { it.symbol == "SPY   261218C00590000" }
        assertEquals(expectedOct15Ms, leg1.tradeTimestampEpochMs)
        assertEquals(expectedOct15Ms, leg2.tradeTimestampEpochMs)
        assertEquals(64.50, leg1.price, 1e-6)
        assertEquals(3.20, leg2.price, 1e-6)

        val ocoStopLot = inserted.first { it.symbol == "GOOG" }
        assertEquals(Instant.parse("2026-10-16T15:45:00Z").toEpochMilli(), ocoStopLot.tradeTimestampEpochMs)
        assertEquals(252.40, ocoStopLot.price, 1e-6)
        assertEquals("SELL", ocoStopLot.instruction)
    }

    private fun nyEpoch(date: LocalDate): Long =
        date.atTime(12, 0).atZone(nyZone).toInstant().toEpochMilli()
}
