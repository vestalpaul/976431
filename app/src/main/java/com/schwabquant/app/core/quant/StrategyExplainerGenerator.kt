package com.schwabquant.app.core.quant

import com.schwabquant.app.domain.model.BearChecklistEvaluation
import com.schwabquant.app.domain.model.OptionStrategyType
import com.schwabquant.app.domain.model.QuantCardMetrics
import com.schwabquant.app.domain.model.StrategyExplainer
import com.schwabquant.app.domain.model.StrategyLeg
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure-JVM deterministic Two-Layer Strategy Explainer:
 * Layer 1 -> Structured Plain-English Thesis & Mechanical Risk Rules citing live Quant Card telemetry.
 * Layer 2 -> Institutional Markdown packet ready for zero-API-cost Gemini handoff.
 */
@Singleton
class StrategyExplainerGenerator @Inject constructor() {

    fun generate(
        underlyingSymbol: String,
        underlyingSpot: Double,
        strategyType: OptionStrategyType,
        legs: List<StrategyLeg>,
        metrics: QuantCardMetrics,
        regimeEvaluation: BearChecklistEvaluation?,
        warnings: List<String>
    ): StrategyExplainer {
        val why = buildWhyItWorksNow(underlyingSymbol, underlyingSpot, strategyType, legs, metrics, regimeEvaluation)
        val rules = buildFailureModesAndRules(underlyingSymbol, underlyingSpot, strategyType, legs, metrics)
        val markdown = buildGeminiMarkdown(
            underlyingSymbol = underlyingSymbol,
            underlyingSpot = underlyingSpot,
            strategyType = strategyType,
            legs = legs,
            metrics = metrics,
            regimeEvaluation = regimeEvaluation,
            warnings = warnings,
            whyItWorksNow = why,
            rules = rules
        )

        return StrategyExplainer(
            whyItWorksNow = why,
            failureModesAndRules = rules,
            warnings = warnings,
            geminiMarkdownPacket = markdown
        )
    }

    private fun buildWhyItWorksNow(
        symbol: String,
        spot: Double,
        strategyType: OptionStrategyType,
        legs: List<StrategyLeg>,
        metrics: QuantCardMetrics,
        regimeEvaluation: BearChecklistEvaluation?
    ): String {
        val regimeClause = regimeEvaluation?.let {
            "Active Regime is ${it.regime.label} (${it.redCount}/${it.totalSignals} Red triggers, " +
                "Weighted ${fmt1(it.weightedScore)})."
        } ?: "Market Regime has not been scanned yet (assuming baseline risk controls)."

        val volRankClause = metrics.volRank52w?.let { vr ->
            " 52w Vol Rank sits at ${fmt0(vr)}%."
        }.orEmpty()

        val termClause = metrics.ivTermSpread?.let { ts ->
            val pct = ts * 100.0
            if (ts > 0.0) {
                " Front IV leads back IV by +${fmt1(pct)}% (IV term backwardation lowers long-leg net financing cost)."
            } else {
                " IV term spread is ${fmt1(pct)}% (normal contango)."
            }
        }.orEmpty()

        val thetaStr = "$${fmt2(metrics.netThetaPerDay)}/day"
        val bprStr = "$${fmt0(metrics.buyingPowerRequirement)}"

        return when (strategyType) {
            OptionStrategyType.PMCC -> {
                val widthStr = metrics.widthRatioPct?.let { "${fmt1(it)}% of strike width" } ?: "defined width"
                "$regimeClause This $symbol PMCC acquires synthetic stock exposure for $bprStr ($widthStr, " +
                    "Net Debit $${fmt2(metrics.netDebitOrCreditPerShare)}), generating $thetaStr in positive daily " +
                    "time decay with ${fmt1(metrics.popPct)}% est. POP.$termClause$volRankClause"
            }
            OptionStrategyType.PMCP -> {
                val widthStr = metrics.widthRatioPct?.let { "${fmt1(it)}% of strike width" } ?: "defined width"
                "$regimeClause This $symbol PMCP establishes a downside hedge / bearish diagonal for $bprStr " +
                    "($widthStr; interest rate carry discounts deep-ITM LEAPS puts), generating $thetaStr while " +
                    "monetizing elevated put skew.$termClause$volRankClause"
            }
            OptionStrategyType.VERTICAL_BULL_PUT -> {
                val creditPct = metrics.widthRatioPct?.let { "${fmt1(it)}% of width" } ?: ""
                "$regimeClause Selling the $symbol Bull Put spread collects $${fmt2(metrics.netDebitOrCreditPerShare)} " +
                    "($creditPct) with max risk $bprStr, ${fmt1(metrics.popPct)}% POP, and $thetaStr positive " +
                    "decay above breakeven $${fmt2(metrics.breakevens.firstOrNull() ?: spot)}.$volRankClause"
            }
            OptionStrategyType.VERTICAL_BEAR_CALL -> {
                val creditPct = metrics.widthRatioPct?.let { "${fmt1(it)}% of width" } ?: ""
                "$regimeClause Selling the $symbol Bear Call spread harvests $${fmt2(metrics.netDebitOrCreditPerShare)} " +
                    "($creditPct) on capped upside rallies with $bprStr max loss, ${fmt1(metrics.popPct)}% POP, and " +
                    "$thetaStr daily theta.$volRankClause"
            }
            OptionStrategyType.CALENDAR -> {
                val atmStrike = legs.firstOrNull()?.strike ?: spot
                "$regimeClause Long/Short ATM $symbol Calendar at strike $${fmt2(atmStrike)} (spot $${fmt2(spot)}) " +
                    "targets front-month theta acceleration ($thetaStr) vs. slower back-month decay for $bprStr " +
                    "defined risk.$termClause$volRankClause"
            }
            OptionStrategyType.IRON_CONDOR -> {
                val lowBe = metrics.breakevens.getOrNull(0) ?: spot
                val highBe = metrics.breakevens.getOrNull(1) ?: spot
                val creditPct = metrics.widthRatioPct?.let { "${fmt1(it)}% of wing" } ?: ""
                "$regimeClause Neutral $symbol Iron Condor collects $${fmt2(metrics.netDebitOrCreditPerShare)} " +
                    "($creditPct) across range $${fmt2(lowBe)}–$${fmt2(highBe)} (${fmt1(metrics.popPct)}% est. POP, " +
                    "$thetaStr daily decay) on $bprStr max risk.$volRankClause"
            }
            OptionStrategyType.WHEEL_CSP -> {
                val strike = legs.firstOrNull()?.strike ?: spot
                val rocStr = metrics.annualizedRocPct?.let { "${fmt1(it)}% ann. ROC" } ?: "yield"
                val cushion = metrics.distanceBelowSpotPct?.let { "${fmt1(it)}% below spot" } ?: "OTM cushion"
                "$regimeClause Selling the $symbol Cash-Secured Put at $${fmt2(strike)} ($cushion) earns " +
                    "$${fmt2(metrics.netDebitOrCreditPerShare)}/sh ($rocStr on $bprStr collateral) with " +
                    "${fmt1(metrics.popPct)}% POP and $thetaStr theta.${volRankClause}"
            }
        }
    }

    private fun buildFailureModesAndRules(
        symbol: String,
        spot: Double,
        strategyType: OptionStrategyType,
        legs: List<StrategyLeg>,
        metrics: QuantCardMetrics
    ): List<String> {
        val earningsRule = "Earnings Check (${metrics.earningsStatus.label}): ${metrics.earningsDetail}"

        val specificRules = when (strategyType) {
            OptionStrategyType.PMCC -> listOf(
                "Upside Cap Rule: Keep Net Debit < 100% of strike width (current: ${metrics.widthRatioPct?.let { fmt1(it) } ?: "--"}%) so a sharp rally never locks an expiration loss.",
                "Short Call Profit Take / Roll: Buy to close short call at 50% max profit, or roll out-and-up when short DTE reaches 14 DTE or |delta| exceeds 0.42.",
                "Downside Failure Mode: LEAPS loses delta convexity if $symbol breaks down > 12% or loses its 200-DMA; enforce a hard thesis invalidation exit near -25% on spread value.",
                earningsRule
            )
            OptionStrategyType.PMCP -> listOf(
                "Hedge Sizing Rule: Target Net Debit < 85% of width (current: ${metrics.widthRatioPct?.let { fmt1(it) } ?: "--"}%); cover/roll short put at 50% profit or <= 14 DTE.",
                "Squeeze Failure Mode: Violent bear-market V-bounces crush put diagonals via vega + delta; trim/close if $symbol reclaims key 50-DMA resistance.",
                "Roll Discipline: When short put |delta| reaches 0.42 on selloff, roll down-and-out for net credit to widen downside profit zone.",
                earningsRule
            )
            OptionStrategyType.VERTICAL_BULL_PUT -> listOf(
                "Mechanical Exit: Close for 50% of max credit collected, or close at 21 DTE to avoid late-stage gamma acceleration.",
                "Loss Control: Cut or defend if short strike $${fmt2(legs.firstOrNull { it.action.name == "SELL_TO_OPEN" }?.strike ?: spot)} is breached (spread reaches 2x credit loss limit).",
                "Gap Risk: Defined max risk is $${fmt0(metrics.maxLoss)}, but overnight gaps through both strikes realize near-max loss instantly.",
                earningsRule
            )
            OptionStrategyType.VERTICAL_BEAR_CALL -> listOf(
                "Mechanical Exit: Take profits at 50% max credit or by 14–21 DTE before gamma expansion accelerates against the short call.",
                "Squeeze Stop: Close if $symbol closes decisively above short call strike $${fmt2(legs.firstOrNull { it.action.name == "SELL_TO_OPEN" }?.strike ?: spot)}.",
                "Div Assignment Watch: Verify ex-dividend date exceeds short call extrinsic value if short call turns ITM.",
                earningsRule
            )
            OptionStrategyType.CALENDAR -> listOf(
                "Range Failure Mode: Calendars suffer if $symbol trends > ±1 expected move before front expiration; exit if spot moves beyond tent breakevens.",
                "Vol Crush Risk: If back-month IV collapses sharply (contango deepens), take-profit target 20%–30% on debit paid or exit by 7 DTE on front leg.",
                "Front-Leg Management: Roll or close before front leg enters final 7 DTE so pin/gamma risk stays bounded.",
                earningsRule
            )
            OptionStrategyType.IRON_CONDOR -> listOf(
                "Mechanical Management: Take profit at 50% max credit or manage at 21 DTE; roll the untested side toward spot only for meaningful net credit.",
                "Breakout Failure Mode: Trend breakouts outside $${fmt2(metrics.breakevens.getOrNull(0) ?: spot)}–$${fmt2(metrics.breakevens.getOrNull(1) ?: spot)} rapidly expand spread to max loss ($${fmt0(metrics.maxLoss)}).",
                "Gamma Control: Never hold narrow Iron Condors into the final 7–10 DTE unless comfortably centered with < 15% of profit remaining.",
                earningsRule
            )
            OptionStrategyType.WHEEL_CSP -> listOf(
                "Assignment Readiness: Only sell on underlyings you are willing to own at net cost basis $${fmt2((legs.firstOrNull()?.strike ?: spot) - metrics.netDebitOrCreditPerShare)} ($${fmt0(metrics.buyingPowerRequirement)} cash secured).",
                "Rolling / Profit Take: Close at 50%–65% max profit or roll down-and-out at 14–21 DTE if short put approaches ATM (|delta| >= 0.45).",
                "Downside Failure Mode: Strong downtrends inflict equity-like mark-to-market losses; verify support near 50-DMA/200-DMA before selling.",
                earningsRule
            )
        }
        return specificRules
    }

    private fun buildGeminiMarkdown(
        underlyingSymbol: String,
        underlyingSpot: Double,
        strategyType: OptionStrategyType,
        legs: List<StrategyLeg>,
        metrics: QuantCardMetrics,
        regimeEvaluation: BearChecklistEvaluation?,
        warnings: List<String>,
        whyItWorksNow: String,
        rules: List<String>
    ): String = buildString {
        appendLine("# Schwab Quant — ${strategyType.displayName} Trade Brief ($underlyingSymbol)")
        appendLine("- **Underlying:** $underlyingSymbol @ $${fmt2(underlyingSpot)}")
        appendLine("- **Strategy:** ${strategyType.displayName}")
        val regimeText = regimeEvaluation?.let {
            "${it.regime.label} (${it.redCount}/${it.totalSignals} Red, Score ${fmt1(it.weightedScore)})"
        } ?: "Unassessed"
        appendLine("- **Active Bear-Market Regime:** $regimeText")
        appendLine("- **Earnings Status:** ${metrics.earningsStatus.label} — ${metrics.earningsDetail}")
        appendLine()
        appendLine("## Quant Card")
        appendLine("| Metric | Value |")
        appendLine("|---|---:|")
        val priceLabel = if (metrics.isNetCredit) "Net Credit" else "Net Debit"
        appendLine("| $priceLabel | $${fmt2(metrics.netDebitOrCreditPerShare)} / share |")
        appendLine("| Buying Power Req (BPR) | $${fmt2(metrics.buyingPowerRequirement)} |")
        appendLine("| Max Profit | ${metrics.maxProfit?.let { "$${fmt2(it)}" } ?: "Capped at short roll / Uncapped upside"} |")
        appendLine("| Max Loss | $${fmt2(metrics.maxLoss)} |")
        appendLine("| Breakeven(s) | ${metrics.breakevens.joinToString(" / ") { "$${fmt2(it)}" }} |")
        metrics.widthRatioPct?.let { ratio ->
            appendLine("| ${metrics.widthRatioLabel ?: "Width Ratio"} | ${fmt1(ratio)}% |")
        }
        appendLine("| Est. POP | ${fmt1(metrics.popPct)}% |")
        appendLine("| Net Delta / Theta | ${fmt2(metrics.netDelta)}Δ / $${fmt2(metrics.netThetaPerDay)}/day |")
        appendLine("| Net Gamma / Vega | ${fmt4(metrics.netGamma)} / $${fmt2(metrics.netVegaPer1Pct)} per 1% IV |")
        appendLine("| Front IV / Term Spread | ${fmt1(metrics.frontIv * 100.0)}% / ${metrics.ivTermSpread?.let { "${fmt1(it * 100.0)}%" } ?: "--"} |")
        metrics.volRank52w?.let { vr ->
            appendLine("| Vol Rank (52w) | ${fmt1(vr)}% |")
        }
        metrics.annualizedRocPct?.let { roc ->
            appendLine("| Annualized ROC | ${fmt1(roc)}% |")
        }
        appendLine()
        appendLine("## Option Legs")
        appendLine("| Action | OCC Symbol | Type | Strike | Exp (DTE) | Mid | Delta | IV | OI |")
        appendLine("|---|---|---|---:|---|---:|---:|---:|---:|")
        legs.forEach { leg ->
            val cp = if (leg.isCall) "Call" else "Put"
            appendLine(
                "| ${leg.action.shortLabel} | `${leg.occSymbol}` | $cp | $${fmt2(leg.strike)} | " +
                    "${leg.expirationIsoDate} (${leg.dte}d) | $${fmt2(leg.mid)} | ${fmt2(leg.delta)} | " +
                    "${fmt1(leg.impliedVolatility * 100.0)}% | ${leg.openInterest} |"
            )
        }
        if (warnings.isNotEmpty()) {
            appendLine()
            appendLine("## Risk Warnings")
            warnings.forEach { w -> appendLine("- ⚠️ $w") }
        }
        appendLine()
        appendLine("## Deterministic Thesis")
        appendLine(whyItWorksNow)
        appendLine()
        appendLine("## Mechanical Management Rules")
        rules.forEach { r -> appendLine("- $r") }
        appendLine()
        appendLine("Stress-test this setup under ±5% spot / +10% IV shock, critique strike selection, and suggest optimal roll adjustments.")
    }

    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmt4(v: Double): String = String.format(Locale.US, "%.4f", v)
}
