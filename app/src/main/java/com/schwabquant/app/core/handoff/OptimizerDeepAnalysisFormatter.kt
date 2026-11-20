package com.schwabquant.app.core.handoff

import com.schwabquant.app.domain.model.MarketRegime
import com.schwabquant.app.domain.model.SingleTickerOptimizationReport
import com.schwabquant.app.domain.model.StrategyOptimizationResult
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure-JVM Gemini Handoff Markdown builder for the Milestone 6 7-Strategy Single-Ticker Optimizer.
 */
@Singleton
class OptimizerDeepAnalysisFormatter @Inject constructor() {

    fun formatFullSevenStrategyPacket(
        report: SingleTickerOptimizationReport,
        dayChangeDollar: Double?,
        dayChangePct: Double?,
        capitalCapLabel: String = report.maxCapitalBudget?.let { "$${fmt0(it)}" } ?: "Unlimited"
    ): String {
        val sb = StringBuilder(2_048)
        val sourceTag = if (report.isDemoData) "SIMULATED CHAIN" else "LIVE SCHWAB CHAIN"
        val dayStr = if (dayChangeDollar != null && dayChangePct != null) {
            val sign = if (dayChangeDollar >= 0) "+" else ""
            "$sign$${fmt2(dayChangeDollar)} (${sign}${fmt2(dayChangePct)}%)"
        } else {
            "N/A"
        }
        val vrStr = report.volRank52w?.let { "${fmt1(it)}%" } ?: "N/A"
        val smaStr = report.sma50?.let { "$${fmt2(it)}" } ?: "N/A"

        sb.appendLine("# SchwabQuant Deep Analysis — ${report.symbol} ($sourceTag)")
        sb.appendLine()
        sb.appendLine("## Market & Account Context")
        sb.appendLine("- **Ticker Spot:** $${fmt2(report.underlyingSpot)} ($dayStr)")
        sb.appendLine("- **52w Vol Rank:** $vrStr • **50-DMA:** $smaStr")
        sb.appendLine("- **Active Macro Regime:** ${report.activeRegime.label} (Cash Target: ${report.activeRegime.cashReserveTarget}, Delta Guidance: ${report.activeRegime.shortDeltaGuidance})")
        sb.appendLine("- **Optimizer Profile:** ${report.riskProfile.label} • **Capital Cap:** $capitalCapLabel")
        sb.appendLine()
        sb.appendLine("## Ranked #1–#7 Strategy Matrix (100-Pt Model: 30 Delta/DTE + 35 Structure + 20 Liquidity + 15 Regime/Cap)")
        sb.appendLine("| Rank | Strategy | Score | Regime Fit | Order | Net $ | BPR | POP |")
        sb.appendLine("|---:|---|---:|---|---|---:|---:|---:|")

        report.rankedStrategies.forEach { item ->
            val cand = item.optimalCandidate
            if (cand != null) {
                val flowType = if (cand.isNetCredit) "CR" else "DB"
                sb.appendLine(
                    "| #${item.rank} | ${item.strategyType.displayName} | ${fmt1(item.compositeScore)}/100 | " +
                        "${item.regimeFit.label} | ${cand.orderType.apiValue} | " +
                        "\$${fmt2(cand.netDebitOrCreditPerShare)} $flowType | \$${fmt0(cand.buyingPowerRequirement)} | " +
                        "${fmt0(cand.popPct)}% |"
                )
            } else {
                sb.appendLine(
                    "| #${item.rank} | ${item.strategyType.displayName} | 0.0/100 | " +
                        "${item.regimeFit.label} | N/A | N/A | N/A | N/A |"
                )
            }
        }

        sb.appendLine()
        sb.appendLine("## Detailed Breakdown Across All 7 Strategies")
        report.rankedStrategies.forEach { item ->
            appendStrategyDetail(sb, item)
        }

        sb.appendLine("## Prompts for Gemini Critique")
        sb.appendLine("1. Validate whether **#${report.rankedStrategies.firstOrNull()?.rank} ${report.rankedStrategies.firstOrNull()?.strategyType?.displayName}** is the highest risk-adjusted trade given ${report.symbol}'s 52w Vol Rank ($vrStr) and 50-DMA ($smaStr).")
        sb.appendLine("2. Identify hidden catalyst, earnings, pin, or skew hazards across the top 3 ranked setups.")
        sb.appendLine("3. Recommend exact adjustment and profit-taking / stop triggers before staging in Schwab.")
        return sb.toString()
    }

    fun formatSingleStrategyPacket(
        result: StrategyOptimizationResult,
        report: SingleTickerOptimizationReport,
        dayChangeDollar: Double?,
        dayChangePct: Double?
    ): String {
        val sb = StringBuilder(1_200)
        val cand = result.optimalCandidate
        val vrStr = report.volRank52w?.let { "${fmt1(it)}%" } ?: "N/A"
        val dayStr = if (dayChangeDollar != null && dayChangePct != null) {
            val sign = if (dayChangeDollar >= 0) "+" else ""
            "$sign$${fmt2(dayChangeDollar)} (${sign}${fmt2(dayChangePct)}%)"
        } else {
            "N/A"
        }

        sb.appendLine("# SchwabQuant Focused Strategy Analysis — ${report.symbol} #${result.rank} ${result.strategyType.displayName}")
        sb.appendLine("- **Spot:** $${fmt2(report.underlyingSpot)} ($dayStr) • **52w Vol Rank:** $vrStr • **Regime:** ${report.activeRegime.label}")
        sb.appendLine("- **Profile:** ${report.riskProfile.label} • **Composite Score:** ${fmt1(result.compositeScore)}/100 (${result.regimeFit.label})")
        sb.appendLine("- **4-Pillar Score:** Delta/DTE ${fmt1(result.breakdown.deltaDteScore)}/30 | Structure ${fmt1(result.breakdown.structuralEdgeScore)}/35 | Liquidity ${fmt1(result.breakdown.liquidityScore)}/20 | Regime/Cap ${fmt1(result.breakdown.regimeCapitalScore)}/15")
        sb.appendLine("- **Ranking Rationale:** ${result.rankingExplanation}")
        sb.appendLine()

        if (cand != null) {
            val flow = if (cand.isNetCredit) "Net Credit" else "Net Debit"
            sb.appendLine("## Optimal #1 Candidate")
            sb.appendLine("- **$flow:** $${fmt2(cand.netDebitOrCreditPerShare)} (${cand.orderType.apiValue}) • **BPR:** $${fmt0(cand.buyingPowerRequirement)} • **POP:** ${fmt0(cand.popPct)}%")
            sb.appendLine("- **Greeks:** Δ ${fmt2(cand.netDelta)} | Θ $${fmt2(cand.netThetaPerDay)}/d | Γ ${fmt3(cand.netGamma)} | ν ${fmt2(cand.netVegaPer1Pct)}")
            sb.appendLine("- **Breakevens:** ${cand.breakevens.joinToString(", ") { "$${fmt2(it)}" }.ifEmpty { "N/A" }}")
            cand.legs.forEach { leg ->
                sb.appendLine(
                    "  - ${leg.action.shortLabel} ${leg.quantity}x `${leg.occSymbol}` " +
                        "(${leg.expirationIsoDate}, ${leg.dte}DTE, $${fmt2(leg.strike)}${if (leg.isCall) "C" else "P"} @ $${fmt2(leg.mid)}, Δ=${fmt2(leg.delta)}, OI=${leg.openInterest})"
                )
            }
            sb.appendLine("- **Leg Rationale:**")
            cand.legRationaleBullets.forEach { sb.appendLine("  - $it") }

            if (result.runnerUpCandidates.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("## Runner-Up Alternatives")
                result.runnerUpCandidates.forEachIndexed { idx, alt ->
                    sb.appendLine("- **Alt #${idx + 2} (${fmt1(alt.breakdown.compositeScore)}/100):** ${alt.summaryLine} (BPR $${fmt0(alt.buyingPowerRequirement)}, POP ${fmt0(alt.popPct)}%)")
                }
            }
        } else {
            sb.appendLine("**Unavailable Setup:** ${result.noValidSetupReason}")
        }

        sb.appendLine()
        sb.appendLine("## Critique Request")
        sb.appendLine("Stress-test this exact strike selection and expiration vs. alternative delta tiers, identify assignment/ex-div or IV-crush failure modes, and outline concrete roll/exit rules.")
        return sb.toString()
    }

    private fun appendStrategyDetail(sb: StringBuilder, item: StrategyOptimizationResult) {
        sb.appendLine("### #${item.rank} — ${item.strategyType.displayName} (${fmt1(item.compositeScore)}/100)")
        sb.appendLine("- **Why Ranked Here:** ${item.rankingExplanation}")
        val cand = item.optimalCandidate
        if (cand == null) {
            sb.appendLine("- **Exclusion Detail:** ${item.noValidSetupReason ?: "Unavailable"}")
            sb.appendLine()
            return
        }
        sb.appendLine("- **Summary:** ${cand.summaryLine} • BPR $${fmt0(cand.buyingPowerRequirement)} • Net Δ ${fmt2(cand.netDelta)} • Θ $${fmt2(cand.netThetaPerDay)}/d")
        cand.legs.forEach { leg ->
            sb.appendLine("  - ${leg.action.shortLabel} ${leg.quantity}x `${leg.occSymbol}` @ $${fmt2(leg.mid)} (Δ=${fmt2(leg.delta)}, OI=${leg.openInterest})")
        }
        cand.legRationaleBullets.forEach { bullet ->
            sb.appendLine("  - $bullet")
        }
        sb.appendLine()
    }

    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmt3(v: Double): String = String.format(Locale.US, "%.3f", v)
}
