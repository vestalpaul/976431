package com.schwabquant.app.domain.usecase

import com.schwabquant.app.core.quant.OccSymbolFormatter
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.entity.WashSaleTradeEntity
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
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

    private fun nyEpoch(date: LocalDate): Long =
        date.atTime(12, 0).atZone(nyZone).toInstant().toEpochMilli()
}
