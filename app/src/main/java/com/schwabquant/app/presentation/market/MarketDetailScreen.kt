package com.schwabquant.app.presentation.market

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.presentation.market.chain.OptionsChainTabContent
import com.schwabquant.app.presentation.market.chart.ChartTabContent
import com.schwabquant.app.presentation.market.optimizer.StrategyOptimizerTabContent
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: MarketDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var showTickerSearch by rememberSaveable { mutableStateOf(false) }
    var showAddSentinelDialog by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onScreenResumed()
                Lifecycle.Event.ON_STOP -> viewModel.onScreenPaused()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenPaused()
        }
    }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeSnackbar()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
        ) {
            // 1. Header Bar: Back + Ticker + Spot + Change + Vol Rank + Shortcuts
            MarketDetailHeader(
                state = state,
                onBack = onNavigateBack,
                onSearchTicker = { showTickerSearch = true },
                onRefresh = viewModel::refreshAll,
                onTradeStock = viewModel::stageEquityOrder,
                onAddSentinelStop = { showAddSentinelDialog = true }
            )

            // 2. 3-Tab Segmented Switcher
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                MarketDetailTab.entries.forEachIndexed { idx, tab ->
                    SegmentedButton(
                        selected = state.activeTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = idx,
                            count = MarketDetailTab.entries.size
                        ),
                        label = { Text(tab.label, maxLines = 1) }
                    )
                }
            }

            HorizontalDivider(color = SchwabQuantTheme.colors.borderSubtle)

            // 3. Active Tab Content
            when (state.activeTab) {
                MarketDetailTab.CHART -> ChartTabContent(
                    state = state,
                    onSelectTimeframe = viewModel::selectTimeframe,
                    onToggleEma9 = viewModel::toggleEma9,
                    onToggleEma21 = viewModel::toggleEma21,
                    onToggleSma50 = viewModel::toggleSma50,
                    onToggleSma200 = viewModel::toggleSma200,
                    onToggleRma = viewModel::toggleRma,
                    onToggleVwap = viewModel::toggleVwap,
                    onCycleRsiMode = viewModel::cycleRsiMode,
                    onClearStrategyOverlay = viewModel::clearStrategyOverlay,
                    onCrosshairTelemetry = viewModel::updateCrosshair,
                    modifier = Modifier.weight(1f)
                )
                MarketDetailTab.OPTIONS_CHAIN -> OptionsChainTabContent(
                    state = state,
                    onSelectStrikeWindow = viewModel::selectStrikeWindow,
                    onSelectColumnPreset = viewModel::selectColumnPreset,
                    onToggleExpiration = viewModel::toggleExpirationExpanded,
                    onToggleSpreadBuilder = viewModel::setSpreadBuilderEnabled,
                    onQuoteTapped = viewModel::onChainQuoteTapped,
                    onClearSpread = viewModel::clearSpreadSelection,
                    onStageSpread = viewModel::stageAssembledSpread,
                    modifier = Modifier.weight(1f)
                )
                MarketDetailTab.OPTIMIZER -> StrategyOptimizerTabContent(
                    state = state,
                    onSelectRiskProfile = viewModel::selectRiskProfile,
                    onSelectCapitalPreset = viewModel::selectCapitalPreset,
                    onStageOrder = viewModel::stageOptimizerCandidate,
                    onInspectOnChain = viewModel::inspectOptimizerCandidateOnChain,
                    onPreviewOnChart = viewModel::previewOptimizerCandidateOnChart,
                    onDeepAnalysisSingle = viewModel::handoffSingleStrategyToGemini,
                    onDeepAnalysisAllSeven = viewModel::handoffAllSevenToGemini,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showTickerSearch) {
        TickerPickerSheet(
            onDismiss = { showTickerSearch = false },
            onSelectTicker = { selected -> viewModel.switchTicker(selected) }
        )
    }

    if (showAddSentinelDialog) {
        QuickSentinelRuleDialog(
            state = state,
            onDismiss = { showAddSentinelDialog = false },
            onConfirm = { type, price, qty ->
                viewModel.addQuickSentinelRule(type, price, qty)
                showAddSentinelDialog = false
            }
        )
    }
}

@Composable
private fun MarketDetailHeader(
    state: MarketDetailUiState,
    onBack: () -> Unit,
    onSearchTicker: () -> Unit,
    onRefresh: () -> Unit,
    onTradeStock: () -> Unit,
    onAddSentinelStop: () -> Unit
) {
    val colors = SchwabQuantTheme.colors
    val chgDollar = state.dayChangeDollar
    val chgPct = state.dayChangePct
    val chgColor = when {
        chgDollar == null -> MaterialTheme.colorScheme.onSurfaceVariant
        chgDollar >= 0.0 -> colors.profit
        else -> colors.loss
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.symbol,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (state.spotPrice > 0.0) {
                            Text(
                                text = "$${fmt2(state.spotPrice)}",
                                style = SchwabQuantTheme.numericTypography.priceMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (chgDollar != null && chgPct != null) {
                            val sign = if (chgDollar >= 0.0) "+" else "-"
                            Text(
                                text = "${sign}$${fmt2(kotlin.math.abs(chgDollar))} (${sign}${fmt2(kotlin.math.abs(chgPct))}%)",
                                style = SchwabQuantTheme.numericTypography.badgeMono,
                                color = chgColor
                            )
                        }
                        state.volRank52w?.let { vr ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = colors.surfaceElevated,
                                border = BorderStroke(1.dp, colors.borderSubtle)
                            ) {
                                Text(
                                    text = "VR ${fmt0(vr)}%",
                                    style = SchwabQuantTheme.numericTypography.badgeMono,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onSearchTicker) {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = "Switch Ticker")
                }
                IconButton(onClick = onRefresh) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Refresh")
                }
                IconButton(onClick = onTradeStock) {
                    Icon(imageVector = Icons.Filled.AddShoppingCart, contentDescription = "Trade Stock")
                }
                IconButton(onClick = onAddSentinelStop) {
                    Icon(imageVector = Icons.Outlined.Shield, contentDescription = "Add Sentinel Stop")
                }
            }
        }
    }
}

@Composable
private fun QuickSentinelRuleDialog(
    state: MarketDetailUiState,
    onDismiss: () -> Unit,
    onConfirm: (SentinelRuleType, String, String) -> Unit
) {
    val spot = state.spotPrice.takeIf { it > 0.0 } ?: 100.0
    var selectedType by rememberSaveable { mutableStateOf(SentinelRuleType.LONG_STOP_LOSS) }
    var triggerInput by rememberSaveable(spot) {
        mutableStateOf(fmt2(spot * 0.98))
    }
    var qtyInput by rememberSaveable(state.suggestedStopQty) {
        mutableStateOf(state.suggestedStopQty.toString())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Arm Sentinel Rule (${state.symbol} • ${state.executionMode.name})")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        SentinelRuleType.LONG_STOP_LOSS,
                        SentinelRuleType.TAKE_PROFIT,
                        SentinelRuleType.OVERNIGHT_PRICE_ALERT
                    ).forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = {
                                selectedType = type
                                val mult = if (type == SentinelRuleType.TAKE_PROFIT) 1.05 else 0.98
                                triggerInput = fmt2(spot * mult)
                            },
                            label = { Text(type.shortTag) }
                        )
                    }
                }
                OutlinedTextField(
                    value = triggerInput,
                    onValueChange = { triggerInput = it },
                    label = { Text("Trigger Price ($)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = qtyInput,
                    onValueChange = { qtyInput = it },
                    label = { Text("Equity Shares Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.width(2.dp))
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedType, triggerInput, qtyInput) }) {
                Text("Arm Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
