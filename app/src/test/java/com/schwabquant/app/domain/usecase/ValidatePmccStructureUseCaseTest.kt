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
import com.schwabquant.app.domain.model.PmccOptionLeg
import com.schwabquant.app.domain.model.PmccSeverity
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.PortfolioSnapshot
import com.schwabquant.app.domain.model.Position
import com.schwabquant.app.domain.model.RiskRuleCode
import com.schwabquant.app.domain.model.TradingSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ValidatePmccStructureUseCaseTest {

    private val validator = ValidatePmccStructureUseCase()

    @Test
    fun `liveNvdaSnapshotPmccRegression verifies deterministic pairing and 5-gate findings`() {
        val optionLegs = listOf(
            PmccOptionLeg(
                symbol = "NVDA  280121C00085000",
                underlying = "NVDA",
                strike = 85.0,
                dte = 424,
                quantity = 1.0,
                avgCost = 71.9066,
                mark = 71.9066,
                delta = 0.90
            ),
            PmccOptionLeg(
                symbol = "NVDA  280121C00165000",
                underlying = "NVDA",
                strike = 165.0,
                dte = 424,
                quantity = 1.0,
                avgCost = 37.6266,
                mark = 37.6266,
                delta = 0.55
            ),
            PmccOptionLeg(
                symbol = "NVDA  261218C00152000",
                underlying = "NVDA",
                strike = 152.0,
                dte = 21,
                quantity = -1.0,
                avgCost = 3.4933,
                mark = 3.4933,
                delta = 0.70
            ),
            PmccOptionLeg(
                symbol = "NVDA  261218C00165000",
                underlying = "NVDA",
                strike = 165.0,
                dte = 21,
                quantity = -1.0,
                avgCost = 2.7833,
                mark = 2.7833,
                delta = 0.45
            )
        )

        val report = validator.validatePmccBook(
            optionLegs = optionLegs,
            sharesByUnderlying = mapOf("NVDA" to 22.0)
        )

        assertFalse(report.passed)
        assertTrue(report.coveredByEquityShorts.isEmpty())
        assertTrue(report.uncoveredShortCalls.isEmpty())
        assertEquals(2, report.pairs.size)

        val pair1 = report.pairs.first { it.longLeg.strike == 85.0 && it.shortLeg.strike == 152.0 }
        assertEquals(67.0, pair1.strikeWidth, 1e-4)
        assertEquals(68.4133, pair1.netDebitBasis, 1e-4)
        assertEquals(-1.4133, pair1.widthBufferBasis, 1e-4)
        val pair1Codes = pair1.findings.map { it.code }.toSet()
        assertTrue(ValidatePmccStructureUseCase.CODE_WIDTH_TO_DEBIT_BREACH in pair1Codes)
        assertTrue(ValidatePmccStructureUseCase.CODE_SHORT_ITM_ROLL_TRIGGER in pair1Codes)
        assertEquals(
            null,
            pair1.findings.first { it.code == ValidatePmccStructureUseCase.CODE_WIDTH_TO_DEBIT_BREACH }.supersededBy
        )

        val pair2 = report.pairs.first { it.longLeg.strike == 165.0 && it.shortLeg.strike == 165.0 }
        assertEquals(0.0, pair2.strikeWidth, 1e-4)
        val pair2Codes = pair2.findings.map { it.code }.toSet()
        assertTrue(ValidatePmccStructureUseCase.CODE_ZERO_WIDTH_CALENDAR in pair2Codes)
        assertTrue(ValidatePmccStructureUseCase.CODE_LEAPS_DELTA_LOW in pair2Codes)
        assertTrue(ValidatePmccStructureUseCase.CODE_NET_DELTA_COMPRESSED in pair2Codes)
        val breachFinding = pair2.findings.first {
            it.code == ValidatePmccStructureUseCase.CODE_WIDTH_TO_DEBIT_BREACH
        }
        assertEquals(ValidatePmccStructureUseCase.CODE_ZERO_WIDTH_CALENDAR, breachFinding.supersededBy)
    }

    @Test
    fun `candidateRollPreservesCumulativeBasis when rolling C152 to C160 at even credit`() {
        val optionLegs = listOf(
            PmccOptionLeg("NVDA  280121C00085000", "NVDA", 85.0, 424, 1.0, 71.9066, 71.9066, 0.90),
            PmccOptionLeg("NVDA  261218C00152000", "NVDA", 152.0, 21, -1.0, 3.4933, 3.4933, 0.70)
        )
        val initialPair = validator.validatePmccBook(optionLegs).pairs.single()
        assertEquals(68.4133, initialPair.netDebitBasis, 1e-4)

        val candidateShort = PmccOptionLeg(
            symbol = "NVDA  270219C00160000",
            underlying = "NVDA",
            strike = 160.0,
            dte = 88,
            quantity = -1.0,
            avgCost = 5.20,
            mark = 5.20,
            delta = 0.35
        )
        val rollReport = validator.validateCandidateRoll(
            existingPair = initialPair,
            newShortLeg = candidateShort,
            netRollCredit = 0.0
        )

        assertTrue(rollReport.passed, "Expected roll to C160 to pass: ${rollReport.findings}")
        val rolledPair = rollReport.pairs.single()
        // Cumulative basis stays 68.4133 (NOT falsely lowered to open long 71.9066 - new short 16.32 = 55.58)
        assertEquals(68.4133, rolledPair.netDebitBasis, 1e-4)
        assertEquals(75.0, rolledPair.strikeWidth, 1e-4)
        assertEquals(6.5867, rolledPair.widthBufferBasis, 1e-4)
    }

    @Test
    fun `equity covered call exclusion prevents false uncovered short findings`() {
        val optionLegs = listOf(
            PmccOptionLeg("AAPL  261218C00240000", "AAPL", 240.0, 25, -1.0, 2.20, 1.80, 0.25)
        )
        val report = validator.validatePmccBook(
            optionLegs = optionLegs,
            sharesByUnderlying = mapOf("AAPL" to 100.0)
        )

        assertEquals(0, report.pairs.size)
        assertEquals(0, report.uncoveredShortCalls.size)
        assertEquals(1, report.coveredByEquityShorts.size)
        assertTrue(report.passed)
    }

    @Test
    fun `multi-contract +2 pairing consolidates and detects inverted diagonal and uncovered short`() {
        val multiLegs = listOf(
            PmccOptionLeg("IWM  280121C00190000", "IWM", 190.0, 424, 2.0, 46.50, 48.20, 0.80),
            PmccOptionLeg("IWM  261218C00245000", "IWM", 245.0, 25, -2.0, 3.10, 2.45, 0.25),
            PmccOptionLeg("MSFT  280121C00420000", "MSFT", 420.0, 400, 1.0, 50.0, 48.0, 0.78),
            PmccOptionLeg("MSFT  261218C00400000", "MSFT", 400.0, 20, -1.0, 12.0, 15.0, 0.68),
            PmccOptionLeg("TSLA  261218C00350000", "TSLA", 350.0, 18, -1.0, 4.0, 3.5, 0.30)
        )

        val report = validator.validatePmccBook(multiLegs)
        assertFalse(report.passed)

        val iwmPair = report.pairs.first { it.underlying == "IWM" }
        assertEquals(2, iwmPair.contracts)
        assertTrue(iwmPair.passed)

        val msftPair = report.pairs.first { it.underlying == "MSFT" }
        assertFalse(msftPair.passed)
        assertTrue(
            msftPair.findings.any {
                it.code == ValidatePmccStructureUseCase.CODE_INVERTED_DIAGONAL &&
                    it.severity == PmccSeverity.CRITICAL
            }
        )

        assertEquals(1, report.uncoveredShortCalls.size)
        assertTrue(
            report.findings.any {
                it.code == ValidatePmccStructureUseCase.CODE_UNCOVERED_SHORT_CALL &&
                    it.severity == PmccSeverity.CRITICAL
            }
        )
    }

    @Test
    fun `pre-trade soft-gate warns on breached roll structure without blocking`() {
        val riskUseCase = EvaluatePreTradeRisksUseCase()
        val snapshot = PortfolioSnapshot(
            dataSource = PortfolioDataSource.SHADOW_PAPER_LEDGER,
            accountSummary = AccountSummary(
                accountHash = "SHADOW_PAPER",
                maskedAccountNumber = "PAPER-SIM",
                executionMode = ExecutionMode.SHADOW,
                netLiquidationValue = BigDecimal("50000.00"),
                cashBalance = BigDecimal("25000.00"),
                settledCash = BigDecimal("25000.00"),
                cashBufferPercent = 50.0,
                buyingPower = BigDecimal("40000.00"),
                optionBuyingPower = BigDecimal("25000.00"),
                dayPnl = BigDecimal.ZERO,
                dayPnlPercent = 0.0,
                portfolioSpyBetaWeightedDelta = 40.0,
                updatedAtEpochMs = 1L
            ),
            positions = listOf(
                Position(
                    symbol = "NVDA  280121C00085000",
                    underlyingSymbol = "NVDA",
                    category = AssetCategory.OPTION,
                    quantity = BigDecimal("1"),
                    averageCost = BigDecimal("71.90"),
                    markPrice = BigDecimal("71.90"),
                    marketValue = BigDecimal("7190.00"),
                    unrealizedPnl = BigDecimal.ZERO,
                    unrealizedPnlPercent = 0.0,
                    delta = 0.90,
                    isCall = true,
                    strike = 85.0,
                    daysToExpiration = 424
                ),
                Position(
                    symbol = "NVDA  261218C00152000",
                    underlyingSymbol = "NVDA",
                    category = AssetCategory.OPTION,
                    quantity = BigDecimal("-1"),
                    averageCost = BigDecimal("3.50"),
                    markPrice = BigDecimal("3.50"),
                    marketValue = BigDecimal("-350.00"),
                    unrealizedPnl = BigDecimal.ZERO,
                    unrealizedPnlPercent = 0.0,
                    delta = 0.70,
                    isCall = true,
                    strike = 152.0,
                    daysToExpiration = 21
                )
            ),
            groups = emptyList(),
            aggregatedGreeks = AggregatedGreeks(),
            spySpot = 585.0
        )

        val badRollDraft = OrderDraft(
            strategyCategory = OrderStrategyCategory.DIAGONAL,
            underlyingSymbol = "NVDA",
            orderType = OrderType.NET_CREDIT,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = BigDecimal("0.00"),
            legs = listOf(
                OrderLeg(
                    symbol = "NVDA  261218C00152000",
                    category = AssetCategory.OPTION,
                    side = OrderSide.BUY_TO_CLOSE,
                    quantity = 1,
                    underlyingSymbol = "NVDA",
                    strike = 152.0,
                    dte = 21,
                    isCall = true,
                    estimatedUnitPrice = BigDecimal("3.50")
                ),
                OrderLeg(
                    symbol = "NVDA  270115C00148000",
                    category = AssetCategory.OPTION,
                    side = OrderSide.SELL_TO_OPEN,
                    quantity = 1,
                    underlyingSymbol = "NVDA",
                    strike = 148.0,
                    dte = 54,
                    isCall = true,
                    delta = 0.68,
                    estimatedUnitPrice = BigDecimal("3.50")
                )
            ),
            sourceLabel = "Test Roll"
        )

        val assessment = riskUseCase.evaluate(
            draft = badRollDraft,
            snapshot = snapshot,
            washSaleHistory = emptyList(),
            today = LocalDate.of(2026, 11, 23)
        )

        assertFalse(assessment.isBlocked, "Pre-trade engine must stay non-blocking Soft-Gate")
        assertTrue(assessment.warnings.any { it.code == RiskRuleCode.PMCC_ROLL_STRUCTURE_RISK })
    }
}
