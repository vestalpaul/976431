package com.schwabquant.app.domain.usecase

import com.schwabquant.app.core.quant.OccSymbolFormatter
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.entity.WashSaleTradeEntity
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.LiquidUniverse
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStrategyCategory
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.PmccOptionLeg
import com.schwabquant.app.domain.model.PmccSeverity
import com.schwabquant.app.domain.model.PortfolioSnapshot
import com.schwabquant.app.domain.model.PreTradeRiskAssessment
import com.schwabquant.app.domain.model.RiskRuleCode
import com.schwabquant.app.domain.model.RiskSeverity
import com.schwabquant.app.domain.model.RiskWarning
import com.schwabquant.app.domain.model.TickerSectorMap
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Pure-JVM, deterministic Soft-Gate Pre-Trade Risk Engine.
 * Zero hard blocks (`isBlocked = false`) — every triggered rule is surfaced cleanly inside
 * RiskWarningDialog for explicit user override and audit logging.
 */
@Singleton
class EvaluatePreTradeRisksUseCase(
    private val washSaleDao: WashSaleDao? = null,
    private val evaluateExDivRadarUseCase: EvaluateExDivRadarUseCase = EvaluateExDivRadarUseCase(),
    private val occSymbolFormatter: OccSymbolFormatter = OccSymbolFormatter(),
    private val validatePmccStructureUseCase: ValidatePmccStructureUseCase = ValidatePmccStructureUseCase(),
    private val zoneId: ZoneId = NY_ZONE
) {

    @Inject
    constructor(
        washSaleDao: WashSaleDao,
        evaluateExDivRadarUseCase: EvaluateExDivRadarUseCase,
        occSymbolFormatter: OccSymbolFormatter,
        validatePmccStructureUseCase: ValidatePmccStructureUseCase
    ) : this(
        washSaleDao = washSaleDao,
        evaluateExDivRadarUseCase = evaluateExDivRadarUseCase,
        occSymbolFormatter = occSymbolFormatter,
        validatePmccStructureUseCase = validatePmccStructureUseCase,
        zoneId = NY_ZONE
    )

    suspend operator fun invoke(
        draft: OrderDraft,
        snapshot: PortfolioSnapshot?,
        today: LocalDate = LocalDate.now(zoneId),
        requirePortfolioTelemetry: Boolean = false
    ): PreTradeRiskAssessment {
        val washHistory = washSaleDao?.getAll().orEmpty().map { it.toLot() }
        val exDivAlerts = snapshot?.let { evaluateExDivRadarUseCase(it.positions, today) }.orEmpty()
        return evaluate(
            draft = draft,
            snapshot = snapshot,
            washSaleHistory = washHistory,
            exDivAlerts = exDivAlerts,
            today = today,
            requirePortfolioTelemetry = requirePortfolioTelemetry
        )
    }

    fun evaluate(
        draft: OrderDraft,
        snapshot: PortfolioSnapshot?,
        washSaleHistory: List<WashSaleLot>,
        exDivAlerts: List<ExDivRadarAlert> = emptyList(),
        today: LocalDate = LocalDate.now(zoneId),
        requirePortfolioTelemetry: Boolean = false
    ): PreTradeRiskAssessment {
        val warnings = mutableListOf<RiskWarning>()

        if (requirePortfolioTelemetry && snapshot == null) {
            warnings += RiskWarning(
                code = RiskRuleCode.PORTFOLIO_TELEMETRY_UNAVAILABLE,
                severity = RiskSeverity.WARNING,
                plainEnglishImpact = "Live Schwab account balances and open positions could not be loaded. Reg-T margin borrow, ticker/sector concentration, settled cash reserve (< 12%), and ex-dividend assignment checks could not be verified.",
                metricDetail = "Live Portfolio Snapshot Unavailable"
            )
        }

        checkDiagonalStructureRules(draft, warnings)
        checkHeldLeapsRollGatekeeper(draft, snapshot, today, warnings)
        val cashOutlay = estimateCashOutlayDollars(draft)
        val addedExposure = estimateAddedRiskExposureDollars(draft, cashOutlay)
        checkCapitalConcentrationAndCashRules(draft, snapshot, cashOutlay, addedExposure, warnings)
        checkBidirectionalWashSale(draft, snapshot, washSaleHistory, today, warnings)
        checkEarningsAndExDivHazards(draft, exDivAlerts, today, warnings)

        return PreTradeRiskAssessment(
            warnings = warnings.distinctBy { it.code },
            isBlocked = false,
            blockReason = null
        )
    }

    private fun checkDiagonalStructureRules(
        draft: OrderDraft,
        warnings: MutableList<RiskWarning>
    ) {
        if (draft.strategyCategory != OrderStrategyCategory.DIAGONAL || draft.legs.size < 2) return

        val longLeg = draft.legs.firstOrNull {
            it.side == OrderSide.BUY_TO_OPEN || it.side == OrderSide.BUY
        } ?: return
        val shortLeg = draft.legs.firstOrNull {
            it.side == OrderSide.SELL_TO_OPEN || it.side == OrderSide.SELL
        } ?: return

        val longOcc = occSymbolFormatter.parse(longLeg.symbol)
        val shortOcc = occSymbolFormatter.parse(shortLeg.symbol)
        val longStrike = longLeg.strike ?: longOcc?.strike?.toDouble() ?: return
        val shortStrike = shortLeg.strike ?: shortOcc?.strike?.toDouble() ?: return
        val isCall = longLeg.isCall ?: longOcc?.isCall ?: true

        val isInverted = if (isCall) {
            shortStrike < longStrike
        } else {
            shortStrike > longStrike
        }

        if (isInverted) {
            warnings += RiskWarning(
                code = RiskRuleCode.INVERTED_DIAGONAL,
                severity = RiskSeverity.CRITICAL,
                plainEnglishImpact = "Short strike ($${fmt2(shortStrike)}) is inverted across long LEAPS " +
                    "strike ($${fmt2(longStrike)}), creating an immediate intrinsic deficit and elevated margin assignment risk.",
                metricDetail = "Long $${fmt2(longStrike)} vs Short $${fmt2(shortStrike)} (${if (isCall) "Call" else "Put"} Diagonal)"
            )
            return
        }

        val netDebit = (draft.limitPrice?.toDouble() ?: draft.estimatedMid ?: 0.0).coerceAtLeast(0.0)
        val strikeWidth = abs(shortStrike - longStrike)
        if (strikeWidth <= 0.0) return

        if (isCall && netDebit >= strikeWidth) {
            warnings += RiskWarning(
                code = RiskRuleCode.PMCC_UPSIDE_LOSS_RISK,
                severity = RiskSeverity.CRITICAL,
                plainEnglishImpact = "Net debit ($${fmt2(netDebit)}) equals or exceeds strike width ($${fmt2(strikeWidth)}). " +
                    "If ${draft.underlyingSymbol} rallies past short strike $${fmt2(shortStrike)}, max spread value guarantees an expiration loss.",
                metricDetail = "Debit $${fmt2(netDebit)} >= Width $${fmt2(strikeWidth)} (${fmt1((netDebit / strikeWidth) * 100.0)}%)"
            )
        } else if (!isCall && netDebit > 0.85 * strikeWidth) {
            warnings += RiskWarning(
                code = RiskRuleCode.PMCP_WEAK_STRUCTURE,
                severity = RiskSeverity.WARNING,
                plainEnglishImpact = "PMCP Net Debit ($${fmt2(netDebit)}) exceeds 85% of strike width ($${fmt2(strikeWidth)}), " +
                    "leaving thin convexity if volatility drops.",
                metricDetail = "Debit $${fmt2(netDebit)} (${fmt1((netDebit / strikeWidth) * 100.0)}% > 85% target)"
            )
        }
    }

    private fun checkHeldLeapsRollGatekeeper(
        draft: OrderDraft,
        snapshot: PortfolioSnapshot?,
        today: LocalDate,
        warnings: MutableList<RiskWarning>
    ) {
        if (snapshot == null || snapshot.positions.isEmpty()) return
        val newShortOrderLeg = draft.legs.firstOrNull { leg ->
            leg.category == AssetCategory.OPTION &&
                (leg.side == OrderSide.SELL_TO_OPEN || leg.side == OrderSide.SELL) &&
                (leg.isCall ?: occSymbolFormatter.parse(leg.symbol)?.isCall ?: true)
        } ?: return

        val cleanUnderlying = SchwabSymbolNormalizer.toDisplaySymbol(
            newShortOrderLeg.underlyingSymbol ?: draft.underlyingSymbol
        )
        val snapshotReport = validatePmccStructureUseCase.validatePortfolio(snapshot.positions)
        val matchingPairs = snapshotReport.pairs.filter { it.underlying == cleanUnderlying }
        val matchingUnhedgedLeaps = snapshotReport.unhedgedLeaps.filter { it.underlying == cleanUnderlying }
        if (matchingPairs.isEmpty() && matchingUnhedgedLeaps.isEmpty()) return

        val shortOcc = occSymbolFormatter.parse(newShortOrderLeg.symbol)
        val shortStrike = newShortOrderLeg.strike ?: shortOcc?.strike?.toDouble() ?: return
        val shortDte = newShortOrderLeg.dte
            ?: shortOcc?.expiration?.let { ChronoUnit.DAYS.between(today, it).toInt().coerceAtLeast(0) }
            ?: 30
        val shortUnitPx = newShortOrderLeg.estimatedUnitPrice?.toDouble()
            ?: draft.estimatedMid
            ?: draft.limitPrice?.toDouble()
            ?: 0.0
        val candidateShort = PmccOptionLeg(
            symbol = newShortOrderLeg.symbol,
            underlying = cleanUnderlying,
            strike = shortStrike,
            dte = shortDte,
            quantity = -newShortOrderLeg.quantity.coerceAtLeast(1).toDouble(),
            avgCost = shortUnitPx,
            mark = shortUnitPx,
            delta = newShortOrderLeg.delta ?: 0.30,
            isCall = true,
            expirationIsoDate = newShortOrderLeg.expirationIsoDate ?: shortOcc?.expiration?.toString()
        )

        val btcOrderLeg = draft.legs.firstOrNull { leg ->
            leg.category == AssetCategory.OPTION && leg.side == OrderSide.BUY_TO_CLOSE
        }

        val validationReport = if (btcOrderLeg != null && matchingPairs.isNotEmpty()) {
            val targetPair = matchingPairs.firstOrNull {
                it.shortLeg.symbol.equals(btcOrderLeg.symbol, ignoreCase = true)
            } ?: matchingPairs.first()
            val netRollCredit = when (draft.orderType) {
                OrderType.NET_CREDIT -> draft.limitPrice?.toDouble() ?: draft.estimatedMid ?: 0.0
                OrderType.NET_DEBIT -> -(draft.limitPrice?.toDouble() ?: draft.estimatedMid ?: 0.0)
                else -> shortUnitPx - (btcOrderLeg.estimatedUnitPrice?.toDouble() ?: 0.0)
            }
            validatePmccStructureUseCase.validateCandidateRoll(
                existingPair = targetPair,
                newShortLeg = candidateShort,
                netRollCredit = netRollCredit
            )
        } else if (matchingUnhedgedLeaps.isNotEmpty()) {
            val targetLeaps = matchingUnhedgedLeaps.first()
            validatePmccStructureUseCase.validatePmccBook(
                optionLegs = listOf(targetLeaps, candidateShort)
            )
        } else {
            val existingLegs = matchingPairs.flatMap { listOf(it.longLeg, it.shortLeg) }
            validatePmccStructureUseCase.validatePmccBook(
                optionLegs = existingLegs + candidateShort
            )
        }

        val blockingFindings = validationReport.findings.filter {
            it.supersededBy == null && it.severity != PmccSeverity.INFO
        }
        if (blockingFindings.isEmpty()) return

        val worstSeverity = if (blockingFindings.any { it.severity == PmccSeverity.CRITICAL }) {
            RiskSeverity.CRITICAL
        } else {
            RiskSeverity.WARNING
        }
        val pair = validationReport.pairs.firstOrNull()
        val basisDetail = pair?.let {
            "Basis $${fmt2(it.netDebitBasis)} vs Width $${fmt2(it.strikeWidth)} (Buf ${if (it.widthBufferBasis >= 0) "+" else "-"}$${fmt2(abs(it.widthBufferBasis))})"
        } ?: "${blockingFindings.size} PMCC finding(s)"

        warnings += RiskWarning(
            code = RiskRuleCode.PMCC_ROLL_STRUCTURE_RISK,
            severity = worstSeverity,
            plainEnglishImpact = blockingFindings.joinToString(" | ") { "${it.code}: ${it.message}" },
            metricDetail = basisDetail
        )
    }

    private fun checkCapitalConcentrationAndCashRules(
        draft: OrderDraft,
        snapshot: PortfolioSnapshot?,
        cashOutlay: Double,
        addedExposure: Double,
        warnings: MutableList<RiskWarning>
    ) {
        if (snapshot == null) return
        val summary = snapshot.accountSummary
        val netLiq = summary.netLiquidationValue.toDouble()
        val settledCash = summary.settledCash.toDouble()

        // Rule 4: MARGIN_BORROW_WARNING
        if (cashOutlay > 0.0 && cashOutlay > settledCash + 0.01) {
            val shortfall = cashOutlay - settledCash
            warnings += RiskWarning(
                code = RiskRuleCode.MARGIN_BORROW_WARNING,
                severity = RiskSeverity.WARNING,
                plainEnglishImpact = "Estimated cash outlay ($${fmt2(cashOutlay)}) exceeds settled cash ($${fmt2(settledCash)}). " +
                    "This trade borrows $${fmt2(shortfall)} on Reg-T margin (~11%–12% annual interest).",
                metricDetail = "Outlay $${fmt0(cashOutlay)} vs Settled Cash $${fmt0(settledCash)}"
            )
        }

        if (netLiq <= 0.0) return

        // Rule 5: CONCENTRATION_BREACH (ticker > 25% OR sector > 40%)
        if (addedExposure > 0.0) {
            val concentrationEligibleGroups = snapshot.groups.filterNot { group ->
                group.legs.isNotEmpty() &&
                    group.legs.all { it.category == AssetCategory.CASH || it.category == AssetCategory.INDEX }
            }
            val cleanUnderlying = SchwabSymbolNormalizer.toDisplaySymbol(draft.underlyingSymbol)
            val existingTickerVal = concentrationEligibleGroups
                .filter { SchwabSymbolNormalizer.toDisplaySymbol(it.underlyingSymbol) == cleanUnderlying }
                .sumOf { it.totalMarketValue.toDouble().coerceAtLeast(0.0) }
            val postTickerVal = existingTickerVal + addedExposure
            val postTickerPct = (postTickerVal / netLiq) * 100.0

            val sector = TickerSectorMap.sectorFor(cleanUnderlying)
            val existingSectorVal = concentrationEligibleGroups
                .filter { TickerSectorMap.sectorFor(SchwabSymbolNormalizer.toDisplaySymbol(it.underlyingSymbol)) == sector }
                .sumOf { it.totalMarketValue.toDouble().coerceAtLeast(0.0) }
            val postSectorVal = existingSectorVal + addedExposure
            val postSectorPct = (postSectorVal / netLiq) * 100.0

            if (postTickerPct > 25.0 || postSectorPct > 40.0) {
                val reasons = buildList {
                    if (postTickerPct > 25.0) add("$cleanUnderlying = ${fmt1(postTickerPct)}% (> 25%)")
                    if (postSectorPct > 40.0) add("$sector sector = ${fmt1(postSectorPct)}% (> 40%)")
                }
                warnings += RiskWarning(
                    code = RiskRuleCode.CONCENTRATION_BREACH,
                    severity = if (postTickerPct > 35.0 || postSectorPct > 50.0) RiskSeverity.CRITICAL else RiskSeverity.WARNING,
                    plainEnglishImpact = "Post-trade portfolio exposure breaches concentration caps: ${reasons.joinToString("; ")}.",
                    metricDetail = reasons.joinToString(" | ")
                )
            }
        }

        // Rule 6: LOW_CASH_RESERVE (< 12% of Net Liq remaining after trade)
        if (cashOutlay > 0.0) {
            val postCash = settledCash - cashOutlay
            val postCashPct = (postCash / netLiq) * 100.0
            if (postCashPct < 12.0) {
                warnings += RiskWarning(
                    code = RiskRuleCode.LOW_CASH_RESERVE,
                    severity = if (postCashPct < 5.0) RiskSeverity.CRITICAL else RiskSeverity.WARNING,
                    plainEnglishImpact = "Remaining settled cash ($${fmt0(postCash)}) drops to ${fmt1(postCashPct)}% of Net Liq, below the 12% cash cushion floor.",
                    metricDetail = "Post-Trade Cash ${fmt1(postCashPct)}% (< 12.0%)"
                )
            }
        }
    }

    private fun checkBidirectionalWashSale(
        draft: OrderDraft,
        snapshot: PortfolioSnapshot?,
        washSaleHistory: List<WashSaleLot>,
        today: LocalDate,
        warnings: MutableList<RiskWarning>
    ) {
        if (washSaleHistory.isEmpty()) return
        val windowStart = today.minusDays(WASH_WINDOW_DAYS)
        val underlying = SchwabSymbolNormalizer.toDisplaySymbol(draft.underlyingSymbol)

        val recentSameUnderlying = washSaleHistory.filter { lot ->
            val lotDate = Instant.ofEpochMilli(lot.tradeTimestampEpochMs).atZone(zoneId).toLocalDate()
            SchwabSymbolNormalizer.toDisplaySymbol(lot.underlyingSymbol) == underlying &&
                !lotDate.isBefore(windowStart) &&
                !lotDate.isAfter(today)
        }
        if (recentSameUnderlying.isEmpty()) return

        // Direction A: Closing an existing position at an estimated loss with a prior 30d buy/call acquisition
        val closingAtLossLeg = draft.legs.firstOrNull { leg ->
            if (leg.side != OrderSide.SELL && leg.side != OrderSide.SELL_TO_CLOSE) return@firstOrNull false
            val targetSym = if (leg.category == AssetCategory.OPTION) {
                leg.symbol.trim()
            } else {
                SchwabSymbolNormalizer.toDisplaySymbol(leg.symbol)
            }
            val pos = snapshot?.positions?.firstOrNull { p ->
                val heldSym = if (p.category == AssetCategory.OPTION) {
                    p.symbol.trim()
                } else {
                    SchwabSymbolNormalizer.toDisplaySymbol(p.symbol)
                }
                heldSym.equals(targetSym, ignoreCase = true)
            } ?: return@firstOrNull false
            if (pos.quantity.toDouble() <= 0.0) return@firstOrNull false
            val unitPx = leg.estimatedUnitPrice?.toDouble()
                ?: draft.limitPrice?.toDouble()
                ?: draft.estimatedMid
                ?: return@firstOrNull false
            unitPx < pos.averageCost.toDouble()
        }

        if (closingAtLossLeg != null) {
            val priorReplacement = recentSameUnderlying.firstOrNull { lot ->
                val inst = lot.instruction.uppercase()
                val isBuy = inst == "BUY" || inst == "BUY_TO_OPEN"
                if (!isBuy) return@firstOrNull false
                val isOption = lot.assetType.equals("OPTION", ignoreCase = true)
                val isPut = isOption && occSymbolFormatter.parse(lot.symbol)?.isCall == false
                !isPut
            }
            if (priorReplacement != null) {
                val priorDate = Instant.ofEpochMilli(priorReplacement.tradeTimestampEpochMs)
                    .atZone(zoneId).toLocalDate()
                warnings += RiskWarning(
                    code = RiskRuleCode.WASH_SALE_WARNING,
                    severity = RiskSeverity.WARNING,
                    plainEnglishImpact = "Selling ${closingAtLossLeg.symbol} at an estimated loss triggers an IRC § 1091 wash sale against your $priorDate ${priorReplacement.symbol} acquisition within 30 days.",
                    metricDetail = "Loss Close + Prior Acquisition ($priorDate)"
                )
                return
            }
        }

        // Direction B: Opening an Equity or Call purchase within 30d after a realized loss sale
        val openingCallOrEquity = draft.legs.firstOrNull { leg ->
            when (leg.side) {
                OrderSide.BUY -> leg.category == AssetCategory.EQUITY
                OrderSide.BUY_TO_OPEN -> {
                    if (leg.category == AssetCategory.EQUITY) {
                        true
                    } else {
                        val isCall = leg.isCall ?: occSymbolFormatter.parse(leg.symbol)?.isCall
                        isCall == true // Stock-to-Option wash sale applies to Calls, never Puts
                    }
                }
                else -> false
            }
        }

        if (openingCallOrEquity != null) {
            val recentLossSale = recentSameUnderlying.firstOrNull { lot ->
                (lot.realizedPnl ?: 0.0) < 0.0
            }
            if (recentLossSale != null) {
                val lossDate = Instant.ofEpochMilli(recentLossSale.tradeTimestampEpochMs)
                    .atZone(zoneId).toLocalDate()
                warnings += RiskWarning(
                    code = RiskRuleCode.WASH_SALE_WARNING,
                    severity = RiskSeverity.WARNING,
                    plainEnglishImpact = "Opening ${openingCallOrEquity.symbol} within 30 days of your $lossDate realized loss on $underlying ($${fmt2(abs(recentLossSale.realizedPnl ?: 0.0))}) will disallow that tax loss.",
                    metricDetail = "Replacement ${openingCallOrEquity.symbol} within 30d of $lossDate loss"
                )
            }
        }
    }

    private fun checkEarningsAndExDivHazards(
        draft: OrderDraft,
        exDivAlerts: List<ExDivRadarAlert>,
        today: LocalDate,
        warnings: MutableList<RiskWarning>
    ) {
        val underlying = SchwabSymbolNormalizer.toDisplaySymbol(draft.underlyingSymbol)
        val hasShortOptionLeg = draft.legs.any { leg ->
            leg.category == AssetCategory.OPTION &&
                (leg.side == OrderSide.SELL_TO_OPEN || leg.side == OrderSide.SELL)
        }

        // Check known earnings window before short expiration
        val earningsDate = draft.knownEarningsDateIso?.let { raw ->
            runCatching { LocalDate.parse(raw.trim().take(10)) }.getOrNull()
        }
        val shortExps = draft.legs
            .filter { it.category == AssetCategory.OPTION && it.side == OrderSide.SELL_TO_OPEN }
            .mapNotNull { leg ->
                leg.expirationIsoDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: occSymbolFormatter.parse(leg.symbol)?.expiration
            }

        if (earningsDate != null && !earningsDate.isBefore(today)) {
            val maxShortExp = shortExps.maxOrNull()
            val insideShortWindow = maxShortExp != null && !earningsDate.isAfter(maxShortExp)
            if (insideShortWindow || hasShortOptionLeg) {
                warnings += RiskWarning(
                    code = RiskRuleCode.EARNINGS_OR_EXDIV_HAZARD,
                    severity = RiskSeverity.CRITICAL,
                    plainEnglishImpact = "$underlying has scheduled earnings ($earningsDate) before short option expiration (${maxShortExp ?: "active cycle"}), creating severe overnight gap risk.",
                    metricDetail = "Earnings $earningsDate <= Exp ${maxShortExp ?: "N/A"}"
                )
                return
            }
        }

        // Check Ex-Dividend CRITICAL/WARNING hazard unless the order is strictly BTC closing the exposed call
        val isStrictBuyToClose = draft.legs.all { it.side == OrderSide.BUY_TO_CLOSE }
        if (!isStrictBuyToClose) {
            val matchingExDiv = exDivAlerts.firstOrNull { alert ->
                SchwabSymbolNormalizer.toDisplaySymbol(alert.underlyingSymbol) == underlying &&
                    (alert.riskLevel == ExDivRiskLevel.CRITICAL || alert.riskLevel == ExDivRiskLevel.WARNING)
            }
            if (matchingExDiv != null) {
                warnings += RiskWarning(
                    code = RiskRuleCode.EARNINGS_OR_EXDIV_HAZARD,
                    severity = if (matchingExDiv.riskLevel == ExDivRiskLevel.CRITICAL) RiskSeverity.CRITICAL else RiskSeverity.WARNING,
                    plainEnglishImpact = "Short call ${matchingExDiv.optionSymbol} faces ${matchingExDiv.riskLevel.name} ex-dividend early assignment (${matchingExDiv.rationale}).",
                    metricDetail = "Ex-Div ${matchingExDiv.exDivDate} (${matchingExDiv.daysUntilExDiv}d)"
                )
                return
            }
        }

        if (hasShortOptionLeg && !LiquidUniverse.isEtf(underlying)) {
            warnings += RiskWarning(
                code = RiskRuleCode.EARNINGS_OR_EXDIV_HAZARD,
                severity = RiskSeverity.INFO,
                plainEnglishImpact = "Single-stock short option on $underlying: confirm upcoming earnings & ex-dividend calendar dates before submitting.",
                metricDetail = "Single Stock Short Leg — Verify Corporate Calendar"
            )
        }
    }

    private fun estimateCashOutlayDollars(draft: OrderDraft): Double {
        if (draft.legs.isEmpty()) return 0.0
        val baseQty = draft.legs.minOf { it.quantity }.coerceAtLeast(1)

        if (draft.legs.size > 1) {
            if (draft.orderType == OrderType.NET_CREDIT) return 0.0
            val px = draft.limitPrice?.toDouble() ?: draft.estimatedMid ?: 0.0
            return (px * baseQty * OPTION_MULTIPLIER).coerceAtLeast(0.0)
        }

        val leg = draft.legs.single()
        val mult = if (leg.category == AssetCategory.OPTION) OPTION_MULTIPLIER else 1.0
        val unitPx = draft.limitPrice?.toDouble()
            ?: draft.stopPrice?.toDouble()
            ?: leg.estimatedUnitPrice?.toDouble()
            ?: draft.estimatedMid
            ?: 0.0

        return when (leg.side) {
            OrderSide.BUY, OrderSide.BUY_TO_COVER, OrderSide.BUY_TO_OPEN, OrderSide.BUY_TO_CLOSE ->
                (unitPx * leg.quantity * mult).coerceAtLeast(0.0)
            OrderSide.SELL, OrderSide.SELL_TO_OPEN, OrderSide.SELL_TO_CLOSE -> 0.0
        }
    }

    private fun estimateAddedRiskExposureDollars(draft: OrderDraft, cashOutlay: Double): Double {
        val isOpeningOrder = draft.legs.any {
            it.side == OrderSide.BUY || it.side == OrderSide.BUY_TO_OPEN || it.side == OrderSide.SELL_TO_OPEN
        }
        if (!isOpeningOrder) return 0.0
        draft.estimatedBpr?.takeIf { it > 0.0 }?.let { return it }

        if (draft.legs.size == 1) {
            val leg = draft.legs.single()
            if (leg.category == AssetCategory.OPTION && leg.side == OrderSide.SELL_TO_OPEN) {
                val strike = leg.strike ?: occSymbolFormatter.parse(leg.symbol)?.strike?.toDouble() ?: 0.0
                if (strike > 0.0) return strike * leg.quantity * OPTION_MULTIPLIER
            }
        }
        return cashOutlay
    }

    private fun WashSaleTradeEntity.toLot() = WashSaleLot(
        id = id,
        underlyingSymbol = underlyingSymbol,
        symbol = symbol,
        assetType = assetType,
        instruction = instruction,
        quantity = quantity,
        price = price,
        realizedPnl = realizedPnl,
        tradeTimestampEpochMs = tradeTimestampEpochMs,
        source = source
    )

    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    companion object {
        private const val WASH_WINDOW_DAYS = 30L
        private const val OPTION_MULTIPLIER = 100.0
        private val NY_ZONE: ZoneId = ZoneId.of("America/New_York")
    }
}
