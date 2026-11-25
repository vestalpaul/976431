package com.schwabquant.app.domain.usecase

import com.schwabquant.app.domain.model.AccountSummary
import com.schwabquant.app.domain.model.AggregatedGreeks
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStrategyCategory
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.PortfolioSnapshot
import com.schwabquant.app.domain.model.Position
import com.schwabquant.app.domain.model.RiskRuleCode
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.model.UnderlyingPositionGroup
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

class EvaluatePreTradeRisksUseCaseTest {

    private val useCase = EvaluatePreTradeRisksUseCase()
    private val nyZone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 11, 19)

    @Test
    fun `flags PMCC upside loss risk and PMCP weak structure and inverted diagonals without hard block`() {
        // 1. PMCC Debit >= Width (width = 230 - 190 = 40, debit = 42.00 >= 40)
        val badPmcc = OrderDraft(
            strategyCategory = OrderStrategyCategory.DIAGONAL,
            underlyingSymbol = "IWM",
            orderType = OrderType.NET_DEBIT,
            limitPrice = BigDecimal("42.00"),
            legs = listOf(
                OrderLeg("IWM   280121C00190000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1, strike = 190.0, isCall = true),
                OrderLeg("IWM   261218C00230000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 1, strike = 230.0, isCall = true)
            )
        )
        val pmccRes = useCase.evaluate(badPmcc, null, emptyList(), today = today)
        assertFalse(pmccRes.isBlocked)
        assertTrue(pmccRes.warnings.any { it.code == RiskRuleCode.PMCC_UPSIDE_LOSS_RISK })

        // 2. PMCP Weak Structure (width = 260 - 220 = 40, 85% = 34.0, debit = 36.50 > 34)
        val weakPmcp = OrderDraft(
            strategyCategory = OrderStrategyCategory.DIAGONAL,
            underlyingSymbol = "IWM",
            orderType = OrderType.NET_DEBIT,
            limitPrice = BigDecimal("36.50"),
            legs = listOf(
                OrderLeg("IWM   270618P00260000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1, strike = 260.0, isCall = false),
                OrderLeg("IWM   261218P00220000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 1, strike = 220.0, isCall = false)
            )
        )
        val pmcpRes = useCase.evaluate(weakPmcp, null, emptyList(), today = today)
        assertFalse(pmcpRes.isBlocked)
        assertTrue(pmcpRes.warnings.any { it.code == RiskRuleCode.PMCP_WEAK_STRUCTURE })

        // 3. Inverted Call Diagonal (Short 210 < Long 220)
        val inverted = badPmcc.copy(
            legs = listOf(
                OrderLeg("IWM   280121C00220000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1, strike = 220.0, isCall = true),
                OrderLeg("IWM   261218C00210000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 1, strike = 210.0, isCall = true)
            )
        )
        val invRes = useCase.evaluate(inverted, null, emptyList(), today = today)
        assertFalse(invRes.isBlocked)
        assertTrue(invRes.warnings.any { it.code == RiskRuleCode.INVERTED_DIAGONAL })
    }

    @Test
    fun `flags margin borrow, ticker and sector concentration, and low cash reserve`() {
        // Portfolio NetLiq = 10,000; Cash = 2,500; NVDA Tech = 2,200; MSFT Tech = 1,800 (Sector Tech = 4,000 = 40%)
        val snapshot = sampleSnapshot(
            netLiq = 10_000.0,
            settledCash = 2_500.0,
            groups = listOf(
                group("NVDA", 2_200.0),
                group("MSFT", 1_800.0)
            )
        )

        // Buying $2,800 of NVDA pushes:
        // - Cash from 2,500 to -300 -> MARGIN_BORROW_WARNING & LOW_CASH_RESERVE (< 12%)
        // - NVDA to 5,000 (50% > 25%) & Tech sector to 6,800 (68% > 40%) -> CONCENTRATION_BREACH
        val bigBuy = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = "NVDA",
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("140.00"),
            legs = listOf(
                OrderLeg("NVDA", AssetCategory.EQUITY, OrderSide.BUY, 20, estimatedUnitPrice = BigDecimal("140.00"))
            ),
            estimatedBpr = 2_800.0
        )

        val res = useCase.evaluate(bigBuy, snapshot, emptyList(), today = today)
        assertFalse(res.isBlocked)
        val codes = res.warnings.map { it.code }.toSet()
        assertTrue(RiskRuleCode.MARGIN_BORROW_WARNING in codes)
        assertTrue(RiskRuleCode.CONCENTRATION_BREACH in codes)
        assertTrue(RiskRuleCode.LOW_CASH_RESERVE in codes)

        // Now verify Sector-only breach even if single ticker AAPL stays <= 25% (1,500 / 10,000 = 15% <= 25%, Tech = 5,500 = 55% > 40%)
        val sectorBuy = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = "AAPL",
            orderType = OrderType.LIMIT,
            limitPrice = BigDecimal("225.00"),
            legs = listOf(OrderLeg("AAPL", AssetCategory.EQUITY, OrderSide.BUY, 5)),
            estimatedBpr = 1_125.0
        )
        val sectorRes = useCase.evaluate(sectorBuy, snapshot, emptyList(), today = today)
        assertTrue(sectorRes.warnings.any {
            it.code == RiskRuleCode.CONCENTRATION_BREACH && it.metricDetail.contains("Technology")
        })
    }

    @Test
    fun `flags bidirectional wash sale hazard and excludes puts as stock replacement`() {
        val lossTime = today.minusDays(5).atStartOfDay(nyZone).toInstant().toEpochMilli()
        val buyCallTime = today.minusDays(8).atStartOfDay(nyZone).toInstant().toEpochMilli()

        val lossHistory = listOf(
            WashSaleLot(1L, "NVDA", "NVDA", "EQUITY", "SELL", 10, 130.0, -400.0, lossTime, "MANUAL")
        )

        // Direction B: Opening Call BUY_TO_OPEN within 30d of equity loss -> WASH_SALE_WARNING
        val openCall = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_OPTION,
            underlyingSymbol = "NVDA",
            orderType = OrderType.LIMIT,
            limitPrice = BigDecimal("12.00"),
            legs = listOf(OrderLeg("NVDA  270115C00150000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1, isCall = true))
        )
        assertTrue(
            useCase.evaluate(openCall, null, lossHistory, today = today).warnings.any {
                it.code == RiskRuleCode.WASH_SALE_WARNING
            }
        )

        // Opening Put BUY_TO_OPEN must NOT trigger wash sale on stock loss
        val openPut = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_OPTION,
            underlyingSymbol = "NVDA",
            orderType = OrderType.LIMIT,
            limitPrice = BigDecimal("10.00"),
            legs = listOf(OrderLeg("NVDA  270115P00120000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1, isCall = false))
        )
        assertFalse(
            useCase.evaluate(openPut, null, lossHistory, today = today).warnings.any {
                it.code == RiskRuleCode.WASH_SALE_WARNING
            }
        )

        // Direction A: Closing NVDA stock at a loss ($125 < $140 avg cost) after Call acquisition 8d ago
        val snapWithLong = sampleSnapshot(
            netLiq = 50_000.0,
            settledCash = 20_000.0,
            groups = emptyList(),
            positions = listOf(
                Position(
                    symbol = "NVDA",
                    underlyingSymbol = "NVDA",
                    category = AssetCategory.EQUITY,
                    quantity = BigDecimal("10"),
                    averageCost = BigDecimal("140.00"),
                    markPrice = BigDecimal("125.00"),
                    marketValue = BigDecimal("1250.00"),
                    unrealizedPnl = BigDecimal("-150.00"),
                    unrealizedPnlPercent = -10.7
                )
            )
        )
        val sellAtLoss = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = "NVDA",
            orderType = OrderType.LIMIT,
            limitPrice = BigDecimal("125.00"),
            legs = listOf(OrderLeg("NVDA", AssetCategory.EQUITY, OrderSide.SELL, 10, estimatedUnitPrice = BigDecimal("125.00")))
        )
        val replacementHistory = listOf(
            WashSaleLot(2L, "NVDA", "NVDA  280121C00165000", "OPTION", "BUY_TO_OPEN", 1, 45.0, null, buyCallTime, "DEMO")
        )
        assertTrue(
            useCase.evaluate(sellAtLoss, snapWithLong, replacementHistory, today = today).warnings.any {
                it.code == RiskRuleCode.WASH_SALE_WARNING
            }
        )
    }

    @Test
    fun `flags earnings before short expiration and imminent ex div critical hazard`() {
        val earningsDraft = OrderDraft(
            strategyCategory = OrderStrategyCategory.VERTICAL,
            underlyingSymbol = "AAPL",
            orderType = OrderType.NET_CREDIT,
            limitPrice = BigDecimal("1.50"),
            knownEarningsDateIso = today.plusDays(10).toString(),
            legs = listOf(
                OrderLeg("AAPL  261218P00220000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 1, expirationIsoDate = today.plusDays(29).toString()),
                OrderLeg("AAPL  261218P00215000", AssetCategory.OPTION, OrderSide.BUY_TO_OPEN, 1, expirationIsoDate = today.plusDays(29).toString())
            )
        )
        val earnRes = useCase.evaluate(earningsDraft, null, emptyList(), today = today)
        assertTrue(earnRes.warnings.any { it.code == RiskRuleCode.EARNINGS_OR_EXDIV_HAZARD })

        val exDivAlerts = listOf(
            ExDivRadarAlert(
                optionSymbol = "IWM   261218C00220000",
                underlyingSymbol = "IWM",
                strike = 220.0,
                underlyingSpot = 225.0,
                optionMark = 5.20,
                extrinsicValue = 0.20,
                dividendAmount = 0.65,
                exDivDate = today.plusDays(1),
                daysUntilExDiv = 1L,
                shortContracts = 1,
                riskLevel = ExDivRiskLevel.CRITICAL,
                rationale = "Ex-div tomorrow with thin extrinsic"
            )
        )
        val iwmDraft = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_OPTION,
            underlyingSymbol = "IWM",
            orderType = OrderType.LIMIT,
            limitPrice = BigDecimal("2.10"),
            legs = listOf(OrderLeg("IWM   261218C00230000", AssetCategory.OPTION, OrderSide.SELL_TO_OPEN, 1))
        )
        val exDivRes = useCase.evaluate(iwmDraft, null, emptyList(), exDivAlerts = exDivAlerts, today = today)
        assertTrue(exDivRes.warnings.any { it.code == RiskRuleCode.EARNINGS_OR_EXDIV_HAZARD })
    }

    @Test
    fun `M-4 missing portfolio snapshot emits PORTFOLIO_TELEMETRY_UNAVAILABLE only when required`() {
        val draft = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = "QQQ",
            orderType = OrderType.LIMIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("480.00"),
            legs = listOf(OrderLeg("QQQ", AssetCategory.EQUITY, OrderSide.BUY, 2))
        )

        val liveWithoutTelemetry = useCase.evaluate(
            draft = draft,
            snapshot = null,
            washSaleHistory = emptyList(),
            today = today,
            requirePortfolioTelemetry = true
        )
        assertFalse(liveWithoutTelemetry.isBlocked)
        assertTrue(liveWithoutTelemetry.requiresSoftGateDialog)
        assertTrue(liveWithoutTelemetry.warnings.any { it.code == RiskRuleCode.PORTFOLIO_TELEMETRY_UNAVAILABLE })

        val shadowWithoutTelemetry = useCase.evaluate(
            draft = draft,
            snapshot = null,
            washSaleHistory = emptyList(),
            today = today,
            requirePortfolioTelemetry = false
        )
        assertFalse(shadowWithoutTelemetry.warnings.any { it.code == RiskRuleCode.PORTFOLIO_TELEMETRY_UNAVAILABLE })

        val liveWithTelemetry = useCase.evaluate(
            draft = draft,
            snapshot = sampleSnapshot(netLiq = 50_000.0, settledCash = 25_000.0, groups = emptyList()),
            washSaleHistory = emptyList(),
            today = today,
            requirePortfolioTelemetry = true
        )
        assertFalse(liveWithTelemetry.warnings.any { it.code == RiskRuleCode.PORTFOLIO_TELEMETRY_UNAVAILABLE })
    }

    @Test
    fun `MEDIUM-7 normalizes slash and dot symbols across concentration groups and sector map`() {
        val brkSlashDraft = com.schwabquant.app.domain.model.OrderDraft(
            strategyCategory = com.schwabquant.app.domain.model.OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = "BRK/B",
            orderType = com.schwabquant.app.domain.model.OrderType.LIMIT,
            session = com.schwabquant.app.domain.model.TradingSession.NORMAL,
            duration = com.schwabquant.app.domain.model.OrderDuration.DAY,
            limitPrice = java.math.BigDecimal("420.00"),
            legs = listOf(
                com.schwabquant.app.domain.model.OrderLeg(
                    symbol = "brk/b",
                    category = com.schwabquant.app.domain.model.AssetCategory.EQUITY,
                    side = com.schwabquant.app.domain.model.OrderSide.BUY,
                    quantity = 10,
                    underlyingSymbol = "BRK/B",
                    estimatedUnitPrice = java.math.BigDecimal("420.00")
                )
            ),
            estimatedBpr = 4_200.0
        )

        // Existing BRK.B group ($10k) + new BRK/B order ($4.2k) on $50k NetLiq = 28.4% > 25% concentration threshold
        val assessment = useCase.evaluate(
            draft = brkSlashDraft,
            snapshot = sampleSnapshot(
                netLiq = 50_000.0,
                settledCash = 25_000.0,
                groups = listOf(group("BRK.B", 10_000.0))
            ),
            washSaleHistory = emptyList(),
            today = today,
            requirePortfolioTelemetry = false
        )
        assertTrue(assessment.warnings.any { it.code == RiskRuleCode.CONCENTRATION_BREACH })
        org.junit.jupiter.api.Assertions.assertEquals("Financials", com.schwabquant.app.domain.model.TickerSectorMap.sectorFor("BRK/B"))
        org.junit.jupiter.api.Assertions.assertEquals("Financials", com.schwabquant.app.domain.model.TickerSectorMap.sectorFor("brk.b"))
    }

    @Test
    fun `LOW-2 excludes CASH and INDEX groups from pre-trade sector concentration checks`() {
        val cashPos = Position(
            symbol = "SWVXX",
            underlyingSymbol = "SWVXX",
            category = AssetCategory.CASH,
            quantity = BigDecimal("50000"),
            averageCost = BigDecimal("1.00"),
            markPrice = BigDecimal("1.00"),
            marketValue = BigDecimal("50000.00"),
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
        val snapshot = sampleSnapshot(
            netLiq = 100_000.0,
            settledCash = 20_000.0,
            groups = listOf(
                UnderlyingPositionGroup("SWVXX", 1.0, 0.0, BigDecimal("50000.00"), 50.0, BigDecimal.ZERO, BigDecimal.ZERO, 0.0, 0.0, listOf(cashPos)),
                UnderlyingPositionGroup("\$SPX.X", 5800.0, 0.0, BigDecimal("29000.00"), 29.0, BigDecimal.ZERO, BigDecimal.ZERO, 0.0, 0.0, listOf(indexPos))
            ),
            positions = listOf(cashPos, indexPos)
        )
        val smallUnmappedBuy = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = "ACME",
            orderType = OrderType.LIMIT,
            limitPrice = BigDecimal("50.00"),
            legs = listOf(OrderLeg("ACME", AssetCategory.EQUITY, OrderSide.BUY, 10)),
            estimatedBpr = 500.0
        )

        val assessment = useCase.evaluate(smallUnmappedBuy, snapshot, emptyList(), today = today)
        assertFalse(
            assessment.warnings.any { it.code == RiskRuleCode.CONCENTRATION_BREACH },
            "CASH and INDEX positions must not inflate Other-sector concentration warnings"
        )
    }

    @Test
    fun `V1-9-1 HIGH-2 zero or negative limitPrice falls back to positive estimatedMid for outlay and cash checks`() {
        val zeroLimitMarketDraft = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = "AAPL",
            orderType = OrderType.MARKET,
            limitPrice = BigDecimal.ZERO,
            legs = listOf(
                OrderLeg(
                    symbol = "AAPL",
                    category = AssetCategory.EQUITY,
                    side = OrderSide.BUY,
                    quantity = 100,
                    estimatedUnitPrice = BigDecimal("-5.00")
                )
            ),
            estimatedMid = 200.0
        )
        val snapshot = sampleSnapshot(
            netLiq = 40_000.0,
            settledCash = 10_000.0,
            groups = emptyList()
        )

        val assessment = useCase.evaluate(zeroLimitMarketDraft, snapshot, emptyList(), today = today)
        val codes = assessment.warnings.map { it.code }.toSet()
        assertTrue(RiskRuleCode.MARGIN_BORROW_WARNING in codes)
        assertTrue(RiskRuleCode.CONCENTRATION_BREACH in codes)
        assertTrue(RiskRuleCode.LOW_CASH_RESERVE in codes)
    }

    private fun group(sym: String, value: Double) = UnderlyingPositionGroup(
        underlyingSymbol = sym,
        underlyingSpot = 100.0,
        betaToSpy = 1.0,
        totalMarketValue = BigDecimal.valueOf(value),
        weightPercent = (value / 10_000.0) * 100.0,
        combinedOpenPnl = BigDecimal.ZERO,
        combinedDayPnl = BigDecimal.ZERO,
        groupNetDelta = 0.0,
        groupNetTheta = 0.0,
        legs = emptyList()
    )

    private fun sampleSnapshot(
        netLiq: Double,
        settledCash: Double,
        groups: List<UnderlyingPositionGroup>,
        positions: List<Position> = emptyList()
    ) = PortfolioSnapshot(
        dataSource = PortfolioDataSource.SHADOW_PAPER_LEDGER,
        accountSummary = AccountSummary(
            accountHash = "SHADOW",
            maskedAccountNumber = "PAPER",
            executionMode = ExecutionMode.SHADOW,
            netLiquidationValue = BigDecimal.valueOf(netLiq),
            cashBalance = BigDecimal.valueOf(settledCash),
            settledCash = BigDecimal.valueOf(settledCash),
            cashBufferPercent = (settledCash / netLiq) * 100.0,
            buyingPower = BigDecimal.valueOf(settledCash),
            optionBuyingPower = BigDecimal.valueOf(settledCash),
            dayPnl = BigDecimal.ZERO,
            dayPnlPercent = 0.0,
            portfolioSpyBetaWeightedDelta = 0.0,
            updatedAtEpochMs = 1L
        ),
        positions = positions,
        groups = groups,
        aggregatedGreeks = AggregatedGreeks(),
        spySpot = 585.0
    )
}
