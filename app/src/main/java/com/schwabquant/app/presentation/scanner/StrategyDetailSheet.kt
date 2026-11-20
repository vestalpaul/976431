package com.schwabquant.app.presentation.scanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.schwabquant.app.domain.model.LegAction
import com.schwabquant.app.domain.model.StrategyCandidate
import com.schwabquant.app.domain.model.StrategyLeg
import com.schwabquant.app.presentation.components.DeepAnalysisButton
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyDetailSheet(
    candidate: StrategyCandidate,
    onDismiss: () -> Unit,
    onOpenChartChain: () -> Unit = {},
    onDeepAnalysisGemini: () -> Unit = {},
    onStageOrder: () -> Unit = {}
) {
    val colors = SchwabQuantTheme.colors
    val m = candidate.metrics

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${candidate.underlyingSymbol} — ${candidate.strategyType.displayName}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Spot $${fmt2(candidate.underlyingSpot)} • ${candidate.regimeFitLabel} • Score ${candidate.compositeScore}/100",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = colors.profit.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, colors.profit)
                    ) {
                        Text(
                            text = candidate.edgeTag,
                            style = SchwabQuantTheme.numericTypography.badgeMono,
                            color = colors.profit,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (candidate.explainer.warnings.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.shadowBannerBg),
                        border = BorderStroke(1.dp, colors.warning),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "RISK & LIQUIDITY FLAGS",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.warning
                            )
                            candidate.explainer.warnings.forEach { w ->
                                Text(
                                    text = "• $w",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.shadowBannerFg
                                )
                            }
                        }
                    }
                }
            }

            // Option Legs Breakdown
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, colors.borderSubtle)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "OPTION LEGS (${candidate.legs.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        candidate.legs.forEachIndexed { idx, leg ->
                            if (idx > 0) HorizontalDivider()
                            StrategyLegRow(leg = leg)
                        }
                    }
                }
            }

            // Layer 1A: Quant Math Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
                    border = BorderStroke(1.dp, colors.borderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "QUANT MATH CARD",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        QuantRow(
                            label = if (m.isNetCredit) "Net Credit / Share" else "Net Debit / Share",
                            value = "$${fmt2(m.netDebitOrCreditPerShare)}"
                        )
                        QuantRow("Buying Power Req (BPR)", "$${fmt2(m.buyingPowerRequirement)}")
                        QuantRow(
                            "Max Profit / Max Loss",
                            "${m.maxProfit?.let { "$${fmt0(it)}" } ?: "Uncapped / Roll"} / $${fmt0(m.maxLoss)}"
                        )
                        QuantRow(
                            "Breakeven(s)",
                            m.breakevens.joinToString(" & ") { "$${fmt2(it)}" }
                        )
                        m.widthRatioPct?.let { ratio ->
                            QuantRow(m.widthRatioLabel ?: "Width Ratio", "${fmt1(ratio)}%")
                        }
                        QuantRow("Est. POP", "${fmt1(m.popPct)}%")
                        QuantRow("Net Δ / Θ ($/d)", "${fmt2(m.netDelta)}Δ / $${fmt2(m.netThetaPerDay)}")
                        QuantRow("Net Γ / Vega (1%)", "${fmt4(m.netGamma)} / $${fmt2(m.netVegaPer1Pct)}")
                        QuantRow(
                            "Front IV / Term Spread",
                            "${fmt1(m.frontIv * 100.0)}% / ${m.ivTermSpread?.let { "${fmt1(it * 100.0)}%" } ?: "--"}"
                        )
                        m.volRank52w?.let { vr ->
                            QuantRow("Vol Rank (52w)", "${fmt1(vr)}%")
                        }
                        m.annualizedRocPct?.let { roc ->
                            QuantRow("Annualized ROC", "${fmt1(roc)}%")
                        }
                        m.distanceBelowSpotPct?.let { dist ->
                            QuantRow("Downside Cushion", "${fmt1(dist)}% below spot")
                        }
                        QuantRow(
                            "Min OI / Max Spread",
                            "${m.minOpenInterest} / ${fmt1(m.maxBidAskSpreadPct * 100.0)}%"
                        )
                        QuantRow("Earnings Check", m.earningsStatus.label)
                    }
                }
            }

            // Layer 1B: Why This Works Now
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, colors.borderSubtle)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "WHY THIS WORKS NOW",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.profit
                        )
                        Text(
                            text = candidate.explainer.whyItWorksNow,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Layer 1C: Failure Modes & Mechanical Rules
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, colors.borderSubtle)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "FAILURE MODES & MECHANICAL RULES",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.warning
                        )
                        candidate.explainer.failureModesAndRules.forEach { rule ->
                            Text(
                                text = "• $rule",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // Actions: Chart & Chain + Gemini Deep Analysis + Stage Order Hook
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenChartChain,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Chart & Chain (${candidate.underlyingSymbol})")
                    }
                    DeepAnalysisButton(
                        label = "Deep Analysis in Gemini",
                        onClick = onDeepAnalysisGemini,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onStageOrder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Stage Order (Shadow Mode)")
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyLegRow(leg: StrategyLeg) {
    val colors = SchwabQuantTheme.colors
    val actionColor = if (leg.action == LegAction.BUY_TO_OPEN) colors.profit else colors.loss

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = actionColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, actionColor)
                ) {
                    Text(
                        text = leg.action.shortLabel,
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        color = actionColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = "${if (leg.isCall) "CALL" else "PUT"} $${fmt2(leg.strike)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "Mid $${fmt2(leg.mid)} (${leg.dte}d)",
                style = SchwabQuantTheme.numericTypography.tableCell
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = leg.occSymbol,
                style = SchwabQuantTheme.numericTypography.badgeMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Δ ${fmt2(leg.delta)} | IV ${fmt1(leg.impliedVolatility * 100.0)}% | OI ${leg.openInterest}",
                style = SchwabQuantTheme.numericTypography.tableCell,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuantRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = SchwabQuantTheme.numericTypography.tableCell,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
private fun fmt4(v: Double): String = String.format(Locale.US, "%.4f", v)
