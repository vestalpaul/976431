package com.schwabquant.app.domain.usecase

import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class EvaluateExDivRadarUseCaseTest {

    private val useCase = EvaluateExDivRadarUseCase()
    private val today = LocalDate.of(2026, 11, 19)

    @Test
    fun `classifies CRITICAL, WARNING, and SAFE short call ex-dividend exposures`() {
        val criticalCall = shortCall(
            symbol = "XLF   261218C00049000",
            underlying = "XLF",
            strike = 49.0,
            spot = 49.60,
            mark = 0.80, // intrinsic 0.60 -> extrinsic 0.20 <= 0.24 * 1.15
            divAmount = 0.24,
            exDivDate = today.plusDays(2).toString()
        )

        val warningItmCall = shortCall(
            symbol = "IWM   261218C00220000",
            underlying = "IWM",
            strike = 220.0,
            spot = 224.0,
            mark = 6.50, // ITM, extrinsic 2.50 > div 0.45, ex-div in 5 days -> WARNING
            divAmount = 0.45,
            exDivDate = today.plusDays(5).toString()
        )

        val safeOtmCall = shortCall(
            symbol = "SPY   261218C00610000",
            underlying = "SPY",
            strike = 610.0,
            spot = 585.0,
            mark = 4.20, // OTM, extrinsic 4.20 > 1.70 * 2.0 (3.40) -> SAFE
            divAmount = 1.70,
            exDivDate = today.plusDays(6).toString()
        )

        val alerts = useCase(
            positions = listOf(safeOtmCall, warningItmCall, criticalCall),
            today = today
        )

        assertEquals(3, alerts.size)
        assertEquals(ExDivRiskLevel.CRITICAL, alerts[0].riskLevel)
        assertEquals("XLF", alerts[0].underlyingSymbol)
        assertEquals(0.20, alerts[0].extrinsicValue, 1e-6)

        assertEquals(ExDivRiskLevel.WARNING, alerts[1].riskLevel)
        assertEquals("IWM", alerts[1].underlyingSymbol)

        assertEquals(ExDivRiskLevel.SAFE, alerts[2].riskLevel)
        assertEquals("SPY", alerts[2].underlyingSymbol)
    }

    private fun shortCall(
        symbol: String,
        underlying: String,
        strike: Double,
        spot: Double,
        mark: Double,
        divAmount: Double,
        exDivDate: String
    ): Position = Position(
        symbol = symbol,
        underlyingSymbol = underlying,
        category = AssetCategory.OPTION,
        quantity = BigDecimal("-1"),
        averageCost = BigDecimal.valueOf(mark),
        markPrice = BigDecimal.valueOf(mark),
        marketValue = BigDecimal.valueOf(-mark * 100.0),
        unrealizedPnl = BigDecimal.ZERO,
        unrealizedPnlPercent = 0.0,
        isCall = true,
        strike = strike,
        underlyingSpot = spot,
        divDate = "$exDivDate 00:00:00.000",
        divAmount = divAmount
    )
}
