package com.schwabquant.app.domain.usecase

import com.schwabquant.app.domain.model.AccountSummary
import com.schwabquant.app.domain.model.AggregatedGreeks
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.PortfolioSnapshot
import com.schwabquant.app.domain.model.Position
import com.schwabquant.app.domain.model.UnderlyingPositionGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class GeneratePortfolioAdviceUseCaseTest {

    private val useCase = GeneratePortfolioAdviceUseCase()

    @Test
    fun `emits all 5 advisor diagnostic families with prefilled one-tap OrderDrafts`() {
        val positions = listOf(
            // 1. LEAPS delta drifted < 0.65 (NVDA 400 DTE call delta 0.52)
            Position(
                symbol = "NVDA  280121C00165000",
                underlyingSymbol = "NVDA",
                category = AssetCategory.OPTION,
                quantity = BigDecimal("1"),
                averageCost = BigDecimal("45.00"),
                markPrice = BigDecimal("40.00"),
                marketValue = BigDecimal("4000.00"),
                unrealizedPnl = BigDecimal("-500.00"),
                unrealizedPnlPercent = -11.1,
                delta = 0.52,
                isCall = true,
                strike = 165.0,
                daysToExpiration = 400
            ),
            // 2. Short option >= 50% profit (XLF short call avg 1.20 -> mark 0.45, 62.5% captured)
            Position(
                symbol = "XLF   261218C00052000",
                underlyingSymbol = "XLF",
                category = AssetCategory.OPTION,
                quantity = BigDecimal("-2"),
                averageCost = BigDecimal("1.20"),
                markPrice = BigDecimal("0.45"),
                marketValue = BigDecimal("-90.00"),
                unrealizedPnl = BigDecimal("150.00"),
                unrealizedPnlPercent = 62.5,
                delta = 0.22,
                isCall = true,
                strike = 52.0,
                daysToExpiration = 29
            ),
            // 3. Short option <= 14 DTE / >= 0.40 delta Roll trigger
            Position(
                symbol = "IWM   261128C00222000",
                underlyingSymbol = "IWM",
                category = AssetCategory.OPTION,
                quantity = BigDecimal("-1"),
                averageCost = BigDecimal("2.10"),
                markPrice = BigDecimal("3.40"),
                marketValue = BigDecimal("-340.00"),
                unrealizedPnl = BigDecimal("-130.00"),
                unrealizedPnlPercent = -61.9,
                delta = 0.58,
                isCall = true,
                strike = 222.0,
                daysToExpiration = 9
            ),
            // Equity leg backing > 25% ticker & > 40% Tech concentration
            Position(
                symbol = "AAPL",
                underlyingSymbol = "AAPL",
                category = AssetCategory.EQUITY,
                quantity = BigDecimal("30"),
                averageCost = BigDecimal("220.00"),
                markPrice = BigDecimal("230.00"),
                marketValue = BigDecimal("6900.00"),
                unrealizedPnl = BigDecimal("300.00"),
                unrealizedPnlPercent = 4.5
            )
        )

        val snapshot = PortfolioSnapshot(
            dataSource = PortfolioDataSource.SHADOW_PAPER_LEDGER,
            accountSummary = AccountSummary(
                accountHash = "SHADOW_PAPER",
                maskedAccountNumber = "PAPER-SIM",
                executionMode = ExecutionMode.SHADOW,
                netLiquidationValue = BigDecimal("15000.00"),
                cashBalance = BigDecimal("-250.00"), // Negative margin debit & < 12% cash buffer
                settledCash = BigDecimal("-250.00"),
                cashBufferPercent = -1.67,
                buyingPower = BigDecimal("1000.00"),
                optionBuyingPower = BigDecimal("1000.00"),
                dayPnl = BigDecimal.ZERO,
                dayPnlPercent = 0.0,
                portfolioSpyBetaWeightedDelta = 40.0,
                updatedAtEpochMs = 1L
            ),
            positions = positions,
            groups = listOf(
                UnderlyingPositionGroup("NVDA", 138.0, 1.8, BigDecimal("4000.00"), 26.7, BigDecimal.ZERO, BigDecimal.ZERO, 52.0, 0.0, listOf(positions[0])),
                UnderlyingPositionGroup("AAPL", 230.0, 1.1, BigDecimal("6900.00"), 46.0, BigDecimal.ZERO, BigDecimal.ZERO, 30.0, 0.0, listOf(positions[3]))
            ),
            aggregatedGreeks = AggregatedGreeks(),
            spySpot = 585.0
        )

        val exDivAlerts = listOf(
            ExDivRadarAlert(
                optionSymbol = "IWM   261128C00222000",
                underlyingSymbol = "IWM",
                strike = 222.0,
                underlyingSpot = 225.0,
                optionMark = 3.40,
                extrinsicValue = 0.40,
                dividendAmount = 0.65,
                exDivDate = LocalDate.of(2026, 11, 20),
                daysUntilExDiv = 1L,
                shortContracts = 1,
                riskLevel = ExDivRiskLevel.CRITICAL,
                rationale = "Ex-div in 1d; extrinsic <= 1.15x div"
            )
        )

        val cards = useCase.generateAdviceCards(snapshot, exDivAlerts = exDivAlerts)
        val categories = cards.map { it.category }.toSet()

        assertTrue(AdviceCategory.EX_DIV_DEFENSE in categories)
        assertTrue(AdviceCategory.GAMMA_DTE_ROLL in categories)
        assertTrue(AdviceCategory.SHORT_PROFIT_TAKE in categories)
        assertTrue(AdviceCategory.LEAPS_DELTA_DRIFT in categories)
        assertTrue(AdviceCategory.PORTFOLIO_HEALTH in categories)

        // Verify BTC 50% card pre-fills exact BUY_TO_CLOSE OrderDraft at mark 0.45 x2
        val btcCard = cards.first { it.category == AdviceCategory.SHORT_PROFIT_TAKE }
        assertEquals("BTC 50%", btcCard.recommendedActionLabel)
        assertNotNull(btcCard.orderDraft)
        assertEquals(OrderSide.BUY_TO_CLOSE, btcCard.orderDraft!!.legs.single().side)
        assertEquals(2, btcCard.orderDraft!!.legs.single().quantity)
        assertEquals("0.45", btcCard.orderDraft!!.limitPrice!!.toPlainString())

        // Verify Ex-Div card pre-fills Closing Call draft
        val exDivCard = cards.first { it.category == AdviceCategory.EX_DIV_DEFENSE }
        assertEquals("Close / Defend Short Call", exDivCard.recommendedActionLabel)
        assertEquals(OrderSide.BUY_TO_CLOSE, exDivCard.orderDraft!!.legs.single().side)

        // Verify Roll card, Tech sector (> 40%), Low Cash, and Negative Cash cards present
        assertTrue(cards.any { it.category == AdviceCategory.GAMMA_DTE_ROLL && it.orderDraft != null })
        assertTrue(cards.any { it.title.contains("Technology") })
        assertTrue(cards.any { it.title.contains("Negative Settled Cash") })
        assertTrue(cards.any { it.title.contains("Low Cash Reserve") })
    }

    @Test
    fun `MEDIUM-4 excludes CASH and INDEX groups from Ticker Concentration, Trim Shares, and Sector Concentration advice`() {
        val cashPos = Position(
            symbol = "SWVXX",
            underlyingSymbol = "SWVXX",
            category = AssetCategory.CASH,
            quantity = BigDecimal("45000"),
            averageCost = BigDecimal("1.00"),
            markPrice = BigDecimal("1.00"),
            marketValue = BigDecimal("45000.00"),
            unrealizedPnl = BigDecimal.ZERO,
            unrealizedPnlPercent = 0.0
        )
        val indexPos = Position(
            symbol = "\$SPX.X",
            underlyingSymbol = "\$SPX.X",
            category = AssetCategory.INDEX,
            quantity = BigDecimal("5"),
            averageCost = BigDecimal("5800.00"),
            markPrice = BigDecimal("5800.00"),
            marketValue = BigDecimal("29000.00"),
            unrealizedPnl = BigDecimal.ZERO,
            unrealizedPnlPercent = 0.0
        )
        val snapshot = PortfolioSnapshot(
            dataSource = PortfolioDataSource.LIVE_SCHWAB_READ_ONLY,
            accountSummary = AccountSummary(
                accountHash = "LIVE_1",
                maskedAccountNumber = "1234",
                executionMode = ExecutionMode.LIVE,
                netLiquidationValue = BigDecimal("100000.00"),
                cashBalance = BigDecimal("26000.00"),
                settledCash = BigDecimal("26000.00"),
                cashBufferPercent = 26.0,
                buyingPower = BigDecimal("50000.00"),
                optionBuyingPower = BigDecimal("26000.00"),
                dayPnl = BigDecimal.ZERO,
                dayPnlPercent = 0.0,
                portfolioSpyBetaWeightedDelta = 0.0,
                updatedAtEpochMs = 1L
            ),
            positions = listOf(cashPos, indexPos),
            groups = listOf(
                UnderlyingPositionGroup("SWVXX", 1.0, 0.0, BigDecimal("45000.00"), 45.0, BigDecimal.ZERO, BigDecimal.ZERO, 0.0, 0.0, listOf(cashPos)),
                UnderlyingPositionGroup("\$SPX.X", 5800.0, 0.0, BigDecimal("29000.00"), 29.0, BigDecimal.ZERO, BigDecimal.ZERO, 0.0, 0.0, listOf(indexPos))
            ),
            aggregatedGreeks = AggregatedGreeks(),
            spySpot = 585.0
        )

        val cards = useCase.generateAdviceCards(snapshot)
        assertTrue(cards.none { it.title.contains("Concentration", ignoreCase = true) })
        assertTrue(cards.none { it.recommendedActionLabel == "Trim Shares" })
    }
}
