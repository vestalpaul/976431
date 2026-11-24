package com.schwabquant.app.presentation.regime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schwabquant.app.domain.model.BearChecklistEvaluation
import com.schwabquant.app.domain.model.BearSignalEvaluation
import com.schwabquant.app.domain.model.DemoRegimePreset
import com.schwabquant.app.domain.model.MarketRegime
import com.schwabquant.app.domain.model.PortfolioDirective
import com.schwabquant.app.domain.model.SignalStatus
import com.schwabquant.app.presentation.components.DeepAnalysisButton
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegimeChecklistScreen(
    viewModel: RegimeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeSnackbar()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { scaffoldPadding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bear-Market 6-Signal Checklist",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                text = if (state.evaluation?.isDemoData == true) {
                                    "Offline Demo Snapshot (${state.evaluation?.demoPreset?.label ?: "Preset"})"
                                } else {
                                    "Live Schwab Macro & Core-25 Breadth"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.evaluation?.isDemoData == true) {
                            DemoDataBadge()
                        }
                    }
                }

                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.hasLiveAuth) {
                            item {
                                FilterChip(
                                    selected = state.evaluation?.isDemoData == false,
                                    onClick = { viewModel.refreshLive(forceRefresh = true) },
                                    label = { Text("Live Schwab") }
                                )
                            }
                        }
                        items(DemoRegimePreset.entries) { preset ->
                            FilterChip(
                                selected = state.evaluation?.isDemoData == true &&
                                    state.selectedDemoPreset == preset,
                                onClick = { viewModel.selectDemoPreset(preset) },
                                label = { Text("Demo: ${preset.label}") }
                            )
                        }
                    }
                }

                state.errorMessage?.let { err ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = SchwabQuantTheme.colors.liveBannerBg
                            ),
                            border = BorderStroke(1.dp, SchwabQuantTheme.colors.loss),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = SchwabQuantTheme.colors.liveBannerFg,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                state.evaluation?.let { eval ->
                    item {
                        RegimeSummaryBannerCard(
                            evaluation = eval,
                            onRefresh = { viewModel.refresh() },
                            onGeminiHandoff = { viewModel.handoffToGemini() }
                        )
                    }

                    item {
                        PortfolioDirectiveCard(directive = eval.directive)
                    }

                    items(eval.signals, key = { it.id.name }) { signal ->
                        SignalChecklistCard(signal = signal)
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoDataBadge() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = SchwabQuantTheme.colors.shadowBannerBg,
        border = BorderStroke(1.dp, SchwabQuantTheme.colors.warning)
    ) {
        Text(
            text = "DEMO DATA",
            style = SchwabQuantTheme.numericTypography.badgeMono,
            color = SchwabQuantTheme.colors.warning,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun RegimeSummaryBannerCard(
    evaluation: BearChecklistEvaluation,
    onRefresh: () -> Unit,
    onGeminiHandoff: () -> Unit
) {
    val colors = SchwabQuantTheme.colors
    val regimeColor = when (evaluation.regime) {
        MarketRegime.RISK_ON_BULL -> colors.profit
        MarketRegime.CAUTION_ELEVATED_VOL -> colors.warning
        MarketRegime.CONFIRMED_BEAR -> colors.loss
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, regimeColor.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = evaluation.regime.label.uppercase(Locale.US),
                        style = MaterialTheme.typography.titleLarge,
                        color = regimeColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${evaluation.amberCount} Warning (AMBER) • Target Cash ${evaluation.regime.cashReserveTarget}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${evaluation.redCount} / ${evaluation.totalSignals} RED",
                        style = SchwabQuantTheme.numericTypography.priceMedium,
                        color = regimeColor
                    )
                    Text(
                        text = "Weighted ${String.format(Locale.US, "%.1f", evaluation.weightedScore)} / 6.0",
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 6-Segment visual gauge matching strict signals
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                evaluation.signals.forEach { sig ->
                    val segColor = statusColor(sig.status)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(segColor)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh")
                }
                DeepAnalysisButton(
                    label = "Deep Analysis",
                    onClick = onGeminiHandoff,
                    modifier = Modifier.weight(1.3f)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortfolioDirectiveCard(directive: PortfolioDirective) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = SchwabQuantTheme.colors.surfaceElevated
        ),
        border = BorderStroke(1.dp, SchwabQuantTheme.colors.borderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "PORTFOLIO DIRECTIVE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = directive.headline,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Cash Target: ${directive.cashReserveTarget}",
                    style = SchwabQuantTheme.numericTypography.tableCell,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "Short Δ: ${directive.shortDeltaGuidance}",
                    style = SchwabQuantTheme.numericTypography.tableCell,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Text(
                text = "Favor: ${directive.favoredStrategies.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = SchwabQuantTheme.colors.profit
            )
            Text(
                text = "Guardrails: ${directive.haltOrAvoidStrategies.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = SchwabQuantTheme.colors.loss
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            directive.actionBullets.forEach { bullet ->
                Text(
                    text = "• $bullet",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SignalChecklistCard(signal: BearSignalEvaluation) {
    val badgeColor = statusColor(signal.status)

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, SchwabQuantTheme.colors.borderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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
                    Text(
                        text = "#${signal.id.number} ${signal.id.title}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = signal.id.tickerTag,
                            style = SchwabQuantTheme.numericTypography.badgeMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = badgeColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, badgeColor)
                ) {
                    Text(
                        text = signal.status.label,
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = signal.currentValueFormatted,
                    style = SchwabQuantTheme.numericTypography.metricValue,
                    color = badgeColor
                )
                Text(
                    text = signal.referenceFormatted,
                    style = SchwabQuantTheme.numericTypography.tableCell,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "Delta: ${signal.deltaFromTriggerFormatted}",
                style = SchwabQuantTheme.numericTypography.tableCell,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = signal.interpretation,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun statusColor(status: SignalStatus): Color = when (status) {
    SignalStatus.GREEN -> SchwabQuantTheme.colors.profit
    SignalStatus.AMBER -> SchwabQuantTheme.colors.warning
    SignalStatus.RED -> SchwabQuantTheme.colors.loss
}
