package com.schwabquant.app.presentation.market.optimizer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.schwabquant.app.domain.model.OptimizedStrategyCandidate
import com.schwabquant.app.domain.model.OptimizerCapitalPreset
import com.schwabquant.app.domain.model.OptimizerRegimeFit
import com.schwabquant.app.domain.model.OptimizerRiskProfile
import com.schwabquant.app.domain.model.StrategyOptimizationResult
import com.schwabquant.app.domain.model.StrategyRankingBreakdown
import com.schwabquant.app.presentation.components.DeepAnalysisButton
import com.schwabquant.app.presentation.market.MarketDetailUiState
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.util.Locale

@Composable
fun StrategyOptimizerTabContent(
    state: MarketDetailUiState,
    onSelectRiskProfile: (OptimizerRiskProfile) -> Unit,
    onSelectCapitalPreset: (OptimizerCapitalPreset) -> Unit,
    onStageOrder: (OptimizedStrategyCandidate) -> Unit,
    onInspectOnChain: (OptimizedStrategyCandidate) -> Unit,
    onPreviewOnChart: (OptimizedStrategyCandidate) -> Unit,
    onDeepAnalysisSingle: (StrategyOptimizationResult) -> Unit,
    onDeepAnalysisAllSeven: () -> Unit,
    modifier: Modifier = Modifier
) {
    var methodologyExpanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Top Control Strip (Risk Profile + Capital Cap + Context + All-7 Gemini Handoff)
        Surface(
            color = SchwabQuantTheme.colors.surfaceElevated,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OptimizerRiskProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = state.riskProfile == profile,
                            onClick = { onSelectRiskProfile(profile) },
                            label = { Text(profile.label) }
                        )
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(OptimizerCapitalPreset.entries, key = { it.name }) { preset ->
                        FilterChip(
                            selected = state.capitalPreset == preset,
                            onClick = { onSelectCapitalPreset(preset) },
                            label = { Text(preset.label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val vrText = state.volRank52w?.let { "VR ${fmt0(it)}%" } ?: "VR N/A"
                        val smaText = state.sma50?.let { "50DMA $${fmt2(it)}" } ?: "50DMA N/A"
                        Text(
                            text = "${state.activeRegime.label} • $vrText • $smaText",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (state.resolvedCapitalBudget != null) {
                                "Capital Cap: $${fmt0(state.resolvedCapitalBudget)}"
                            } else {
                                "Capital Cap: Unlimited"
                            },
                            style = SchwabQuantTheme.numericTypography.badgeMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DeepAnalysisButton(
                        label = "Deep Analysis (All 7)",
                        onClick = onDeepAnalysisAllSeven
                    )
                }
            }
        }

        HorizontalDivider(color = SchwabQuantTheme.colors.borderSubtle)

        // 2. Methodology Banner + All 7 Ranked Strategy Cards
        val report = state.optimizationReport
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.isComputingOptimizer && report == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (report == null) {
                Text(
                    text = "Option chain loading...",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "methodology_banner") {
                        MethodologyBanner(
                            expanded = methodologyExpanded,
                            onToggle = { methodologyExpanded = !methodologyExpanded }
                        )
                    }
                    items(
                        items = report.rankedStrategies,
                        key = { it.strategyType.name }
                    ) { result ->
                        RankedStrategyCard(
                            result = result,
                            onStageOrder = onStageOrder,
                            onInspectOnChain = onInspectOnChain,
                            onPreviewOnChart = onPreviewOnChart,
                            onDeepAnalysis = { onDeepAnalysisSingle(result) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MethodologyBanner(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        border = BorderStroke(1.dp, SchwabQuantTheme.colors.borderSubtle),
        colors = CardDefaults.cardColors(containerColor = SchwabQuantTheme.colors.surfaceElevated),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "How Strategies Are Ranked (100-Pt Model)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }
            Text(
                text = "30% Delta/DTE • 35% Structure • 20% Liquidity • 15% Regime/Capital",
                style = SchwabQuantTheme.numericTypography.badgeMono,
                color = MaterialTheme.colorScheme.primary
            )
            if (expanded) {
                Text(
                    text = "Every single ticker scan evaluates all 7 option structures (PMCC, PMCP, Bull Put, Bear Call, Calendar, Iron Condor, Wheel/CSP), filters out wide spreads (>15% standard / >20% LEAPS) and low OI (<25), and ranks setups from #1 to #7 by composite score.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RankedStrategyCard(
    result: StrategyOptimizationResult,
    onStageOrder: (OptimizedStrategyCandidate) -> Unit,
    onInspectOnChain: (OptimizedStrategyCandidate) -> Unit,
    onPreviewOnChart: (OptimizedStrategyCandidate) -> Unit,
    onDeepAnalysis: () -> Unit
) {
    val colors = SchwabQuantTheme.colors
    var selectedVariantIdx by remember(result.optimalCandidate?.id) { mutableIntStateOf(0) }
    val variants = remember(result) {
        listOfNotNull(result.optimalCandidate) + result.runnerUpCandidates
    }
    val activeCandidate = variants.getOrNull(selectedVariantIdx) ?: result.optimalCandidate

    val fitColor = when (result.regimeFit) {
        OptimizerRegimeFit.ALIGNED -> colors.profit
        OptimizerRegimeFit.NEUTRAL -> colors.warning
        OptimizerRegimeFit.COUNTER_REGIME_CAUTION -> colors.loss
    }
    val scoreColor = when {
        result.optimalCandidate == null -> MaterialTheme.colorScheme.onSurfaceVariant
        result.compositeScore >= 75.0 -> colors.profit
        result.compositeScore >= 55.0 -> colors.warning
        else -> colors.loss
    }

    Card(
        border = BorderStroke(
            width = if (result.rank == 1 && activeCandidate != null) 1.5.dp else 1.dp,
            color = if (result.rank == 1 && activeCandidate != null) colors.profit else colors.borderSubtle
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Card Header: Rank Pill + Strategy Name + Regime Fit + Composite Score
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = scoreColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, scoreColor)
                    ) {
                        Text(
                            text = "#${result.rank}",
                            style = SchwabQuantTheme.numericTypography.metricValue,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Column {
                        Text(
                            text = result.strategyType.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = result.regimeFit.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = fitColor
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = colors.surfaceElevated,
                    border = BorderStroke(1.dp, scoreColor)
                ) {
                    Text(
                        text = "${fmt1(activeCandidate?.breakdown?.compositeScore ?: result.compositeScore)}/100",
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        color = scoreColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 4-Pillar Score Micro-Bars
            val breakdown = activeCandidate?.breakdown ?: result.breakdown
            PillarBreakdownRow(breakdown = breakdown)

            // Mandatory Explicit Ranking Explanation
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = colors.surfaceElevated,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = result.rankingExplanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            }

            if (activeCandidate == null) {
                Text(
                    text = result.noValidSetupReason ?: "Unavailable under current chain & budget constraints.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warning
                )
                return@Column
            }

            // Optimal #1 / Alt #2 / Alt #3 Switcher
            if (variants.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    variants.forEachIndexed { idx, _ ->
                        val label = if (idx == 0) "Optimal #1" else "Alt #${idx + 1}"
                        FilterChip(
                            selected = selectedVariantIdx == idx,
                            onClick = { selectedVariantIdx = idx },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // Leg Table
            HorizontalDivider(color = colors.borderSubtle)
            activeCandidate.legs.forEach { leg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${leg.action.shortLabel} ${leg.dte}D $${fmtStrike(leg.strike)}${if (leg.isCall) "C" else "P"}",
                        style = SchwabQuantTheme.numericTypography.greekValue,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Mid $${fmt2(leg.mid)} • Δ ${fmt2(leg.delta)} • OI ${leg.openInterest}",
                        style = SchwabQuantTheme.numericTypography.tableCell,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Metrics Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val flowLabel = if (activeCandidate.isNetCredit) "Credit" else "Debit"
                Text(
                    text = "$flowLabel $${fmt2(activeCandidate.netDebitOrCreditPerShare)}",
                    style = SchwabQuantTheme.numericTypography.tableCell,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "BPR $${fmt0(activeCandidate.buyingPowerRequirement)}",
                    style = SchwabQuantTheme.numericTypography.tableCell
                )
                Text(
                    text = "POP ${fmt0(activeCandidate.popPct)}%",
                    style = SchwabQuantTheme.numericTypography.tableCell
                )
                Text(
                    text = "Δ ${fmt2(activeCandidate.netDelta)}",
                    style = SchwabQuantTheme.numericTypography.tableCell
                )
            }

            // 3 Leg Rationale Bullets
            activeCandidate.legRationaleBullets.forEach { bullet ->
                Text(
                    text = "• $bullet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 4-Action Row: Stage Order | Inspect on Chain | Preview on Chart | Deep Analysis
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(onClick = { onStageOrder(activeCandidate) }) {
                    Text("Stage Order")
                }
                OutlinedButton(onClick = { onInspectOnChain(activeCandidate) }) {
                    Text("Inspect on Chain")
                }
                OutlinedButton(onClick = { onPreviewOnChart(activeCandidate) }) {
                    Text("Preview on Chart")
                }
                OutlinedButton(onClick = onDeepAnalysis) {
                    Text("Deep Analysis")
                }
            }
        }
    }
}

@Composable
private fun PillarBreakdownRow(breakdown: StrategyRankingBreakdown) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PillarBar(label = "Δ/DTE", value = breakdown.deltaDteScore, max = 30.0, modifier = Modifier.weight(1f))
        PillarBar(label = "Struct", value = breakdown.structuralEdgeScore, max = 35.0, modifier = Modifier.weight(1f))
        PillarBar(label = "Liq", value = breakdown.liquidityScore, max = 20.0, modifier = Modifier.weight(1f))
        PillarBar(label = "Reg/Cap", value = breakdown.regimeCapitalScore, max = 15.0, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PillarBar(
    label: String,
    value: Double,
    max: Double,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "$label ${fmt1(value)}/${max.toInt()}",
            style = SchwabQuantTheme.numericTypography.badgeMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        LinearProgressIndicator(
            progress = { (value / max).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
        )
    }
}

private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
private fun fmtStrike(v: Double): String =
    if (kotlin.math.abs(v - v.toLong()) < 0.01) v.toLong().toString() else fmt2(v)
