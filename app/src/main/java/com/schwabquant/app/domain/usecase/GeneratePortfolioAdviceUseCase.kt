package com.schwabquant.app.domain.usecase

import com.schwabquant.app.core.quant.StressMatrixSummary
import com.schwabquant.app.core.quant.StressScenarioResult
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.BearChecklistEvaluation
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStrategyCategory
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.PmccValidationReport
import com.schwabquant.app.domain.model.PortfolioAnomalyType
import com.schwabquant.app.domain.model.PortfolioSnapshot
import com.schwabquant.app.domain.model.Position
import com.schwabquant.app.domain.model.TickerSectorMap
import com.schwabquant.app.domain.model.TradingSession
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

enum class AdviceSeverity {
    CRITICAL,
    WARNING,
    OPPORTUNITY
}

enum class AdviceCategory {
    EX_DIV_DEFENSE,
    GAMMA_DTE_ROLL,
    SHORT_PROFIT_TAKE,
    LEAPS_DELTA_DRIFT,
    PORTFOLIO_HEALTH
}

data class PortfolioAdviceCard(
    val id: String,
    val severity: AdviceSeverity,
    val category: AdviceCategory,
    val underlyingSymbol: String?,
    val title: String,
    val rationale: String,
    val recommendedActionLabel: String?,
    val orderDraft: OrderDraft?
)

/**
 * Dual-purpose Portfolio Management Advisor:
 * 1) Generates structured, one-tap actionable PortfolioAdviceCard diagnostics with pre-filled OrderDrafts.
 * 2) Generates the institutional Markdown portfolio context packet for zero-API-cost Gemini handoff.
 */
class GeneratePortfolioAdviceUseCase @Inject constructor() {

    fun generateAdviceCards(
        snapshot: PortfolioSnapshot?,
        exDivAlerts: List<ExDivRadarAlert> = emptyList(),
        regime: BearChecklistEvaluation? = null
    ): List<PortfolioAdviceCard> {
        if (snapshot == null || snapshot.positions.isEmpty()) return emptyList()

        val cards = mutableListOf<PortfolioAdviceCard>()
        val summary = snapshot.accountSummary
        val netLiq = summary.netLiquidationValue.toDouble()

        // 1. Ex-Dividend Critical & Warning Defense
        exDivAlerts
            .filter { it.riskLevel == ExDivRiskLevel.CRITICAL || it.riskLevel == ExDivRiskLevel.WARNING }
            .forEach { alert ->
                val matchingPos = snapshot.positions.firstOrNull {
                    it.symbol.equals(alert.optionSymbol, ignoreCase = true)
                }
                val qty = matchingPos?.let { abs(it.quantity.toInt()).coerceAtLeast(1) } ?: alert.shortContracts
                val btcPrice = BigDecimal.valueOf(alert.optionMark.coerceAtLeast(0.05))
                    .setScale(2, RoundingMode.HALF_UP)

                cards += PortfolioAdviceCard(
                    id = "exdiv_${alert.optionSymbol}",
                    severity = if (alert.riskLevel == ExDivRiskLevel.CRITICAL) {
                        AdviceSeverity.CRITICAL
                    } else {
                        AdviceSeverity.WARNING
                    },
                    category = AdviceCategory.EX_DIV_DEFENSE,
                    underlyingSymbol = alert.underlyingSymbol,
                    title = "Ex-Div Early Assignment Risk: ${alert.optionSymbol}",
                    rationale = "${alert.rationale} Closing or rolling before ${alert.exDivDate} protects against dividend capture assignment.",
                    recommendedActionLabel = "Close / Defend Short Call",
                    orderDraft = buildSingleOptionBtcDraft(
                        underlying = alert.underlyingSymbol,
                        occSymbol = alert.optionSymbol,
                        qty = qty,
                        limitPrice = btcPrice,
                        pos = matchingPos,
                        sourceLabel = "Advisor: Ex-Div Defense"
                    )
                )
            }

        // 2. Gamma / DTE Roll Trigger (<= 14 DTE or |delta| >= 0.40) on short option legs
        snapshot.positions
            .filter { it.category == AssetCategory.OPTION && it.quantity < BigDecimal.ZERO }
            .forEach { pos ->
                val dte = pos.daysToExpiration
                val absDelta = pos.delta?.let { abs(it) }
                val triggerDte = dte != null && dte <= 14
                val triggerDelta = absDelta != null && absDelta >= 0.40
                if (triggerDte || triggerDelta) {
                    val reasons = buildList {
                        if (triggerDte) add("${dte} DTE <= 14d")
                        if (triggerDelta) add("|Δ|=${fmt2(absDelta!!)} >= 0.40")
                    }
                    val btcPrice = pos.markPrice.max(BigDecimal("0.05"))
                        .setScale(2, RoundingMode.HALF_UP)

                    cards += PortfolioAdviceCard(
                        id = "roll_${pos.symbol}",
                        severity = if ((dte ?: 99) <= 7 || (absDelta ?: 0.0) >= 0.55) {
                            AdviceSeverity.CRITICAL
                        } else {
                            AdviceSeverity.WARNING
                        },
                        category = AdviceCategory.GAMMA_DTE_ROLL,
                        underlyingSymbol = pos.underlyingSymbol,
                        title = "Gamma / Roll Trigger: ${pos.symbol}",
                        rationale = "Short option triggered ${reasons.joinToString(" & ")}. Close or roll out in time to reset gamma and defend upside.",
                        recommendedActionLabel = "Roll / Close Short",
                        orderDraft = buildSingleOptionBtcDraft(
                            underlying = pos.underlyingSymbol,
                            occSymbol = pos.symbol,
                            qty = abs(pos.quantity.toInt()).coerceAtLeast(1),
                            limitPrice = btcPrice,
                            pos = pos,
                            sourceLabel = "Advisor: Gamma/DTE Roll"
                        )
                    )
                }
            }

        // 3. 50% Short-Leg Profit Take Opportunity
        snapshot.positions
            .filter {
                it.category == AssetCategory.OPTION &&
                    it.quantity < BigDecimal.ZERO &&
                    it.averageCost > BigDecimal.ZERO
            }
            .forEach { pos ->
                val openCredit = pos.averageCost.toDouble()
                val mark = pos.markPrice.toDouble()
                val capturedPct = ((openCredit - mark) / openCredit) * 100.0
                if (capturedPct >= 50.0) {
                    val btcPrice = BigDecimal.valueOf(mark.coerceAtLeast(0.01))
                        .setScale(2, RoundingMode.HALF_UP)
                    cards += PortfolioAdviceCard(
                        id = "btc50_${pos.symbol}",
                        severity = AdviceSeverity.OPPORTUNITY,
                        category = AdviceCategory.SHORT_PROFIT_TAKE,
                        underlyingSymbol = pos.underlyingSymbol,
                        title = "Capture ${fmt0(capturedPct)}% Profit: ${pos.symbol}",
                        rationale = "Short leg captured ${fmt1(capturedPct)}% of $${fmt2(openCredit)} credit (mark $${fmt2(mark)}). Mechanical rule favors buying to close and freeing buying power.",
                        recommendedActionLabel = "BTC 50%",
                        orderDraft = buildSingleOptionBtcDraft(
                            underlying = pos.underlyingSymbol,
                            occSymbol = pos.symbol,
                            qty = abs(pos.quantity.toInt()).coerceAtLeast(1),
                            limitPrice = btcPrice,
                            pos = pos,
                            sourceLabel = "Advisor: BTC ${fmt0(capturedPct)}%"
                        )
                    )
                }
            }

        // 4. LEAPS Delta Drift (< 0.65Δ) recovery mode
        snapshot.positions
            .filter {
                it.category == AssetCategory.OPTION &&
                    it.quantity > BigDecimal.ZERO &&
                    it.isCall == true &&
                    (it.daysToExpiration ?: 0) >= 90 &&
                    (it.delta ?: 1.0) < 0.65
            }
            .forEach { leaps ->
                cards += PortfolioAdviceCard(
                    id = "leaps_drift_${leaps.symbol}",
                    severity = AdviceSeverity.WARNING,
                    category = AdviceCategory.LEAPS_DELTA_DRIFT,
                    underlyingSymbol = leaps.underlyingSymbol,
                    title = "LEAPS Delta Drift: ${leaps.symbol} (Δ=${fmt2(leaps.delta ?: 0.0)})",
                    rationale = "Long ${leaps.daysToExpiration} DTE call delta drifted below 0.65Δ. Switch short calls to conservative 0.18–0.24Δ recovery mode and NEVER sell strikes below your long strike ($${fmt2(leaps.strike ?: 0.0)}).",
                    recommendedActionLabel = null,
                    orderDraft = null
                )
            }

        // 5. Concentration, Cash Reserve & Margin Health
        if (summary.settledCash < BigDecimal.ZERO || summary.cashBalance < BigDecimal.ZERO) {
            cards += PortfolioAdviceCard(
                id = "margin_debit_alert",
                severity = AdviceSeverity.CRITICAL,
                category = AdviceCategory.PORTFOLIO_HEALTH,
                underlyingSymbol = null,
                title = "Negative Settled Cash / Margin Debit",
                rationale = "Account settled cash is $${summary.settledCash}. Borrowing on Reg-T margin incurs ~11%–12% interest; trim losers or close capital-intensive legs.",
                recommendedActionLabel = null,
                orderDraft = null
            )
        }

        val targetCashFloor = if (regime?.redCount != null && regime.redCount >= 4) 30.0 else 12.0
        if (netLiq > 0.0 && summary.cashBufferPercent < 12.0) {
            cards += PortfolioAdviceCard(
                id = "cash_buffer_low",
                severity = AdviceSeverity.WARNING,
                category = AdviceCategory.PORTFOLIO_HEALTH,
                underlyingSymbol = null,
                title = "Low Cash Reserve (${fmt1(summary.cashBufferPercent)}% < 12%)",
                rationale = "Settled cash ($${summary.settledCash}) sits below the 12% baseline reserve (active target ${fmt0(targetCashFloor)}%). Defer new debit entries until cash rebuilds.",
                recommendedActionLabel = null,
                orderDraft = null
            )
        }

        val concentrationEligibleGroups = snapshot.groups.filterNot { group ->
            group.legs.isNotEmpty() &&
                group.legs.all { it.category == AssetCategory.CASH || it.category == AssetCategory.INDEX }
        }

        // Ticker > 25% concentration
        concentrationEligibleGroups
            .filter { it.weightPercent > 25.0 }
            .forEach { group ->
                val equityLeg = group.legs.firstOrNull {
                    it.category == AssetCategory.EQUITY && it.quantity > BigDecimal.ZERO
                }
                val trimQty = equityLeg?.let { (it.quantity.toInt() / 3).coerceAtLeast(1) }
                val trimDraft = if (equityLeg != null && trimQty != null) {
                    val px = equityLeg.markPrice.max(BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP)
                    OrderDraft(
                        strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
                        underlyingSymbol = group.underlyingSymbol,
                        orderType = OrderType.LIMIT,
                        session = TradingSession.NORMAL,
                        duration = OrderDuration.DAY,
                        limitPrice = px,
                        legs = listOf(
                            OrderLeg(
                                symbol = equityLeg.symbol,
                                category = AssetCategory.EQUITY,
                                side = OrderSide.SELL,
                                quantity = trimQty,
                                underlyingSymbol = group.underlyingSymbol,
                                estimatedUnitPrice = px
                            )
                        ),
                        estimatedMid = px.toDouble(),
                        sourceLabel = "Advisor: Trim Concentration"
                    )
                } else {
                    null
                }

                cards += PortfolioAdviceCard(
                    id = "conc_ticker_${group.underlyingSymbol}",
                    severity = AdviceSeverity.WARNING,
                    category = AdviceCategory.PORTFOLIO_HEALTH,
                    underlyingSymbol = group.underlyingSymbol,
                    title = "Ticker Concentration: ${group.underlyingSymbol} (${fmt1(group.weightPercent)}% > 25%)",
                    rationale = "${group.underlyingSymbol} consumes ${fmt1(group.weightPercent)}% of Net Liq ($${group.totalMarketValue}). Trim partial exposure or avoid adding correlated beta.",
                    recommendedActionLabel = if (trimDraft != null) "Trim Shares" else null,
                    orderDraft = trimDraft
                )
            }

        // Sector > 40% concentration
        if (netLiq > 0.0) {
            concentrationEligibleGroups
                .groupBy { TickerSectorMap.sectorFor(it.underlyingSymbol) }
                .forEach { (sector, groups) ->
                    val sectorWeight = (groups.sumOf { it.totalMarketValue.toDouble() } / netLiq) * 100.0
                    if (sectorWeight > 40.0) {
                        cards += PortfolioAdviceCard(
                            id = "conc_sector_$sector",
                            severity = AdviceSeverity.WARNING,
                            category = AdviceCategory.PORTFOLIO_HEALTH,
                            underlyingSymbol = groups.firstOrNull()?.underlyingSymbol,
                            title = "Sector Concentration: $sector (${fmt1(sectorWeight)}% > 40%)",
                            rationale = "Combined $sector positions (${groups.joinToString { it.underlyingSymbol }}) total ${fmt1(sectorWeight)}% of Net Liq. Diversify new entries into uncorrelated sectors.",
                            recommendedActionLabel = null,
                            orderDraft = null
                        )
                    }
                }
        }

        return cards.sortedWith(
            compareBy<PortfolioAdviceCard> { it.severity.ordinal }
                .thenBy { it.category.ordinal }
                .thenBy { it.underlyingSymbol.orEmpty() }
        )
    }

    private fun buildSingleOptionBtcDraft(
        underlying: String,
        occSymbol: String,
        qty: Int,
        limitPrice: BigDecimal,
        pos: Position?,
        sourceLabel: String
    ): OrderDraft = OrderDraft(
        strategyCategory = OrderStrategyCategory.SINGLE_OPTION,
        underlyingSymbol = underlying,
        orderType = OrderType.LIMIT,
        session = TradingSession.NORMAL,
        duration = OrderDuration.DAY,
        limitPrice = limitPrice,
        legs = listOf(
            OrderLeg(
                symbol = occSymbol,
                category = AssetCategory.OPTION,
                side = OrderSide.BUY_TO_CLOSE,
                quantity = qty,
                underlyingSymbol = underlying,
                estimatedUnitPrice = limitPrice,
                strike = pos?.strike,
                expirationIsoDate = pos?.expirationDate,
                dte = pos?.daysToExpiration,
                isCall = pos?.isCall,
                delta = pos?.delta
            )
        ),
        estimatedBid = (limitPrice.toDouble() * 0.96).coerceAtLeast(0.01),
        estimatedMid = limitPrice.toDouble(),
        estimatedAsk = limitPrice.toDouble() * 1.04,
        estimatedBpr = limitPrice.toDouble() * qty * 100.0,
        sourceLabel = sourceLabel
    )

    operator fun invoke(
        snapshot: PortfolioSnapshot?,
        executionMode: ExecutionMode,
        stressMatrix: List<StressScenarioResult> = emptyList(),
        washSaleFlags: List<WashSaleFlag> = emptyList(),
        exDivAlerts: List<ExDivRadarAlert> = emptyList(),
        regime: BearChecklistEvaluation? = null,
        stressSummary: StressMatrixSummary? = snapshot?.stressSummary,
        pmccReport: PmccValidationReport? = null
    ): String {
        if (snapshot == null) {
            return """
                # Schwab Quant Portfolio Context
                - Execution Mode: ${executionMode.name}
                - Status: No portfolio snapshot loaded yet.
            """.trimIndent()
        }

        val summary = snapshot.accountSummary
        val greeks = snapshot.aggregatedGreeks
        val activeStressRows = stressSummary?.scenarios?.takeIf { it.isNotEmpty() } ?: stressMatrix

        return buildString {
            appendLine("# Schwab Quant Portfolio Context")
            appendLine("- **Source:** ${snapshot.dataSource.label} (${summary.maskedAccountNumber})")
            appendLine("- **Execution Mode:** ${executionMode.name}")
            appendLine("- **SPY Spot:** $${fmt2(snapshot.spySpot)}")
            val regimeLabel = regime?.let {
                "${it.regime.label} (${it.redCount}/${it.totalSignals} Red, Score ${fmt1(it.weightedScore)})"
            } ?: "Unassessed"
            appendLine("- **Regime:** $regimeLabel")
            appendLine()
            appendLine("## Account Telemetry")
            appendLine("| Metric | Value |")
            appendLine("|---|---:|")
            appendLine("| Net Liquidation | $${summary.netLiquidationValue} |")
            appendLine("| Settled Cash (${fmt1(summary.cashBufferPercent)}%) | $${summary.settledCash} |")
            appendLine("| Reg-T Margin Buying Power | $${summary.buyingPower} |")
            appendLine("| Open P&L | $${summary.openPnl} (${fmt2(summary.openPnlPercent)}%) |")
            appendLine("| Day P&L | $${summary.dayPnl} (${fmt2(summary.dayPnlPercent)}%) |")
            appendLine()
            appendLine("## Aggregated Greeks")
            appendLine("- **SPY β-Weighted Delta:** ${fmt2(greeks.spyBetaWeightedDelta)}")
            appendLine("- **Daily Net Theta:** $${fmt2(greeks.netDailyTheta)}/day")
            appendLine("- **Net Gamma:** ${fmt4(greeks.netGamma)}")
            appendLine("- **Net Vega (1% IV):** $${fmt2(greeks.netVegaPer1Pct)}")

            val betaFallbacks = snapshot.portfolioAnomalies.filter { it.type == PortfolioAnomalyType.BETA_FALLBACK }
            if (betaFallbacks.isNotEmpty()) {
                val tickers = betaFallbacks.mapNotNull { it.symbol }.distinct().joinToString(", ")
                appendLine("- **⚠️ BETA FALLBACK (1.0):** Missing 252d OLS & fundamental beta on: $tickers")
            }

            if (activeStressRows.isNotEmpty()) {
                appendLine()
                val ivModeLabel = stressSummary?.ivMode?.label ?: "Term √(30/DTE)"
                appendLine("## Stress Matrix (SPY Shocks — $ivModeLabel)")
                if (stressSummary != null) {
                    val skewText = stressSummary.payoffSkew15?.let { "${fmt2(it)}x" } ?: "N/A"
                    val captureText = stressSummary.upsideCapture5?.let { "${fmt0(it * 100.0)}%" } ?: "N/A"
                    appendLine("- **Payoff Skew (-15%/+15%):** $skewText | **Upside Capture (+5%):** $captureText")
                    if (stressSummary.isNegativeSkew) {
                        appendLine("- **⚠️ NEGATIVE_SKEW:** Downside -15% loss exceeds 2.0x +15% upside.")
                    }
                    if (stressSummary.isUpsideCapped) {
                        appendLine("- **⚠️ UPSIDE_CAPPED:** +5% rally captures < 50% of linear β-delta upside.")
                    }
                }
                appendLine("| SPY Shock | IV Shift | Projected P&L | Portfolio % |")
                appendLine("|---:|---:|---:|---:|")
                activeStressRows.forEach { row ->
                    val sign = if (row.spyShockPercent > 0) "+" else ""
                    val ivPct = row.ivShockAbsolute * 100.0
                    val ivSign = if (ivPct > 0) "+" else ""
                    appendLine(
                        "| ${sign}${fmt0(row.spyShockPercent)}% | ${ivSign}${fmt1(ivPct)}% | " +
                            "$${fmt2(row.portfolioDollarPnl)} | ${fmt2(row.portfolioPercentChange)}% |"
                    )
                }
            }

            if (pmccReport != null && (pmccReport.pairs.isNotEmpty() || pmccReport.findings.isNotEmpty())) {
                appendLine()
                appendLine("## PMCC Structure Health (${if (pmccReport.passed) "PASSED" else "WARNINGS/VIOLATIONS"})")
                pmccReport.pairs.forEach { pair ->
                    val statusTag = if (pair.passed) "OK" else "FLAG"
                    appendLine(
                        "- **$statusTag ${pair.underlying} ${pair.contracts}x** (+K$${fmt2(pair.longLeg.strike)}/-K$${fmt2(pair.shortLeg.strike)}): " +
                            "Width $${fmt2(pair.strikeWidth)} | Basis $${fmt2(pair.netDebitBasis)} [${pair.basisMode.name}] | " +
                            "Buf(Basis) $${fmt2(pair.widthBufferBasis)} | Buf(Mark) $${fmt2(pair.widthBufferMark)} | Net Δ ${fmt2(pair.netDelta)}"
                    )
                }
                pmccReport.findings.forEach { f ->
                    val supTag = f.supersededBy?.let { " (superseded by $it)" } ?: ""
                    appendLine("- **[${f.severity.name}] ${f.code}** (${f.pairLabel ?: f.underlying})$supTag: ${f.message}")
                }
            }

            if (snapshot.groups.isNotEmpty()) {
                appendLine()
                appendLine("## Grouped Positions (${snapshot.groups.size} Underlyings)")
                snapshot.groups.forEach { group ->
                    val anomalyTags = group.anomalies.joinToString(", ") { it.message }
                    appendLine(
                        "### ${group.underlyingSymbol} ($${fmt2(group.underlyingSpot)}, β=${fmt2(group.betaToSpy)} [${group.betaSource.badge}]) — " +
                            "${fmt1(group.weightPercent)}% wt | Open P&L $${group.combinedOpenPnl} | " +
                            "Δ=${fmt2(group.groupNetDelta)} | Θ=$${fmt2(group.groupNetTheta)}" +
                            if (anomalyTags.isNotBlank()) " | ⚠️ $anomalyTags" else ""
                    )
                    group.legs.forEach { leg ->
                        val legInfo = if (leg.daysToExpiration != null && leg.strike != null) {
                            "K=$${fmt2(leg.strike)} ${leg.daysToExpiration}DTE Δ=${fmt2(leg.delta ?: 0.0)} Extr=$${fmt2(leg.extrinsicValue ?: 0.0)}"
                        } else {
                            "Equity"
                        }
                        appendLine("- `${leg.symbol}` x${leg.quantity} | Mark $${leg.markPrice} | $legInfo | P&L $${leg.unrealizedPnl}")
                    }
                }
            }

            if (washSaleFlags.isNotEmpty()) {
                appendLine()
                appendLine("## Active 61-Day IRC § 1091 Wash-Sale Alerts")
                washSaleFlags.forEach { flag ->
                    val crossTag = if (flag.isCrossAssetStockToOption) " [STOCK->CALL]" else ""
                    appendLine(
                        "- **${flag.underlyingSymbol}**$crossTag: Disallowed $${flag.disallowedLossAmount} on ${flag.lossSymbol} " +
                            "-> Adjusted basis $${flag.adjustedReplacementBasis} on `${flag.replacementLotSymbol}` (Safe re-buy: ${flag.clearToRebuyDate})"
                    )
                }
            }

            if (exDivAlerts.isNotEmpty()) {
                appendLine()
                appendLine("## Ex-Dividend Early Assignment Radar")
                exDivAlerts.forEach { alert ->
                    appendLine(
                        "- **[${alert.riskLevel.name}] ${alert.optionSymbol}**: ${alert.rationale}"
                    )
                }
            }

            appendLine()
            appendLine("Analyze concentration risks, delta/theta balance, PMCC roll candidates, and tax/ex-div actions.")
        }
    }

    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmt4(v: Double): String = String.format(Locale.US, "%.4f", v)
}
