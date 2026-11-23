package com.schwabquant.app.presentation.scanner

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schwabquant.app.domain.model.BearChecklistEvaluation
import com.schwabquant.app.domain.model.CapitalFilterTier
import com.schwabquant.app.domain.model.MarketRegime
import com.schwabquant.app.domain.model.StrategyCandidate
import com.schwabquant.app.domain.model.StrategyFilterChip
import com.schwabquant.app.domain.model.Watchlist
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onOpenMarketDetail: (String) -> Unit = {},
    viewModel: ScannerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showWatchlistManager by rememberSaveable { mutableStateOf(false) }

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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header + Regime Pill + Chain Source Badge
            ScannerHeaderBar(
                activeRegime = state.activeRegime,
                hasLiveAuth = state.hasLiveAuth,
                useDemoChains = state.useDemoChains,
                onToggleDataSource = { useDemo -> viewModel.setUseDemoChains(useDemo) }
            )

            // Watchlist Selector + Quick Chart + Manage Watchlists CRUD button
            WatchlistControlRow(
                watchlists = state.watchlists,
                selectedWatchlist = state.selectedWatchlist,
                onSelectWatchlist = viewModel::selectWatchlist,
                onOpenChart = {
                    val ticker = state.selectedWatchlist?.tickers?.firstOrNull() ?: "SPY"
                    onOpenMarketDetail(ticker)
                },
                onOpenManager = { showWatchlistManager = true }
            )

            // Capital Limit Filter Chips
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Max Capital / BPR Limit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(CapitalFilterTier.entries) { tier ->
                        FilterChip(
                            selected = state.selectedCapitalTier == tier,
                            onClick = { viewModel.selectCapitalTier(tier) },
                            label = {
                                Text(
                                    text = tier.label,
                                    style = SchwabQuantTheme.numericTypography.badgeMono
                                )
                            }
                        )
                    }
                }
            }

            // Strategy Filter Chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(StrategyFilterChip.entries) { chip ->
                    FilterChip(
                        selected = chip in state.selectedStrategyFilters,
                        onClick = { viewModel.toggleStrategyFilter(chip) },
                        label = { Text(chip.label) }
                    )
                }
            }

            // Run Scan / Cancel + Live Progress Indicator
            if (state.isScanning) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = SchwabQuantTheme.colors.surfaceElevated
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val prog = state.scanProgress
                        val fraction = if (prog != null && prog.total > 0) {
                            (prog.completed.toFloat() / prog.total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = prog?.let {
                                    "Scanning ${it.completed} / ${it.total} — ${it.currentTicker}..."
                                } ?: "Scanning chains...",
                                style = SchwabQuantTheme.numericTypography.tableCell,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = viewModel::cancelScan) {
                                Text("Cancel")
                            }
                        }
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { viewModel.runScan() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val count = state.selectedWatchlist?.tickers?.size ?: 0
                        Text("Run Scan ($count tickers)")
                    }
                    if (state.candidates.isNotEmpty() && !state.useDemoChains) {
                        OutlinedButton(onClick = { viewModel.runScan(forceRefresh = true) }) {
                            Text("Force")
                        }
                    }
                }
            }

            state.errorMessage?.let { err ->
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
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Ranked Candidate Feed
            if (state.candidates.isEmpty() && !state.isScanning) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = SchwabQuantTheme.colors.surfaceElevated
                    ),
                    border = BorderStroke(1.dp, SchwabQuantTheme.colors.borderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (state.totalUnfilteredCount > 0) {
                                "0 candidates match active Capital / Strategy filters (${state.totalUnfilteredCount} total scanned)."
                            } else {
                                "Ready to scan all 6 strategies across ${state.selectedWatchlist?.name ?: "Core 25 Liquid"}."
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Tap Run Scan to rank PMCC, PMCP, Bull Put & Bear Call Verticals, Calendars, Iron Condors, and Wheel/CSP setups by regime fit, IV term structure, and capital efficiency.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "${state.candidates.size} Ranked Setups" +
                        if (state.candidates.size < state.totalUnfilteredCount) {
                            " (filtered from ${state.totalUnfilteredCount})"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.candidates, key = { it.id }) { candidate ->
                        StrategyCandidateCard(
                            candidate = candidate,
                            onClick = { viewModel.selectCandidate(candidate) },
                            onOpenChartChain = { onOpenMarketDetail(candidate.underlyingSymbol) }
                        )
                    }
                }
            }
        }
    }

    if (showWatchlistManager) {
        ManageWatchlistsSheet(
            watchlists = state.watchlists,
            selectedWatchlist = state.selectedWatchlist,
            onDismiss = { showWatchlistManager = false },
            onSelectWatchlist = viewModel::selectWatchlist,
            onCreateWatchlist = viewModel::createWatchlist,
            onRenameWatchlist = viewModel::renameWatchlist,
            onDeleteWatchlist = viewModel::deleteWatchlist,
            onAddTicker = viewModel::addTickerToWatchlist,
            onRemoveTicker = viewModel::removeTickerFromWatchlist
        )
    }

    state.selectedCandidate?.let { candidate ->
        StrategyDetailSheet(
            candidate = candidate,
            onDismiss = { viewModel.selectCandidate(null) },
            onOpenChartChain = {
                viewModel.selectCandidate(null)
                onOpenMarketDetail(candidate.underlyingSymbol)
            },
            onDeepAnalysisGemini = { viewModel.handoffCandidateToGemini(candidate) },
            onStageOrder = { viewModel.stageOrder(candidate) }
        )
    }
}

@Composable
private fun ScannerHeaderBar(
    activeRegime: BearChecklistEvaluation?,
    hasLiveAuth: Boolean,
    useDemoChains: Boolean,
    onToggleDataSource: (Boolean) -> Unit
) {
    val colors = SchwabQuantTheme.colors
    val regimeColor = when (activeRegime?.regime) {
        MarketRegime.RISK_ON_BULL -> colors.profit
        MarketRegime.CAUTION_ELEVATED_VOL -> colors.warning
        MarketRegime.CONFIRMED_BEAR -> colors.loss
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "6-Strategy Scanner",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = activeRegime?.let {
                    "Regime: ${it.regime.label} (${it.redCount}/6 Red)"
                } ?: "Regime: Caution Demo Baseline",
                style = MaterialTheme.typography.bodySmall,
                color = regimeColor,
                fontWeight = FontWeight.Medium
            )
        }

        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (useDemoChains) colors.shadowBannerBg else colors.surfaceElevated,
            border = BorderStroke(1.dp, if (useDemoChains) colors.warning else colors.profit),
            modifier = Modifier.clickable(enabled = hasLiveAuth) {
                onToggleDataSource(!useDemoChains)
            }
        ) {
            Text(
                text = if (useDemoChains) {
                    if (hasLiveAuth) "DEMO (Tap Live)" else "DEMO CHAINS"
                } else {
                    "LIVE SCHWAB"
                },
                style = SchwabQuantTheme.numericTypography.badgeMono,
                color = if (useDemoChains) colors.warning else colors.profit,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun WatchlistControlRow(
    watchlists: List<Watchlist>,
    selectedWatchlist: Watchlist?,
    onSelectWatchlist: (Long) -> Unit,
    onOpenChart: () -> Unit,
    onOpenManager: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                color = SchwabQuantTheme.colors.surfaceElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { menuExpanded = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = selectedWatchlist?.name ?: "Loading Watchlists...",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "${selectedWatchlist?.tickers?.size ?: 0} symbols",
                            style = SchwabQuantTheme.numericTypography.tableCell,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(imageVector = Icons.Filled.ExpandMore, contentDescription = "Select watchlist")
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                watchlists.forEach { wl ->
                    DropdownMenuItem(
                        text = { Text("${wl.name} (${wl.tickers.size})") },
                        onClick = {
                            onSelectWatchlist(wl.id)
                            menuExpanded = false
                        }
                    )
                }
            }
        }

        OutlinedButton(onClick = onOpenChart) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ShowChart,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Chart")
        }

        OutlinedButton(onClick = onOpenManager) {
            Text("Manage")
        }
    }
}

@Composable
private fun StrategyCandidateCard(
    candidate: StrategyCandidate,
    onClick: () -> Unit,
    onOpenChartChain: () -> Unit
) {
    val colors = SchwabQuantTheme.colors
    val scoreColor = when {
        candidate.compositeScore >= 75 -> colors.profit
        candidate.compositeScore >= 55 -> colors.warning
        else -> colors.loss
    }
    val metrics = candidate.metrics

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = scoreColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, scoreColor)
                    ) {
                        Text(
                            text = "${candidate.compositeScore}",
                            style = SchwabQuantTheme.numericTypography.metricValue,
                            color = scoreColor,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }

                    Column {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = candidate.underlyingSymbol,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "$${fmt2(candidate.underlyingSpot)}",
                                style = SchwabQuantTheme.numericTypography.tableCell,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.clickable(onClick = onOpenChartChain)
                            ) {
                                Text(
                                    text = "Chart & Chain",
                                    style = SchwabQuantTheme.numericTypography.badgeMono,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = candidate.strategyType.shortLabel,
                                    style = SchwabQuantTheme.numericTypography.badgeMono,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = candidate.regimeFitLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = regimeFitColor(candidate.regimeFitLabel)
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = colors.surfaceElevated,
                        border = BorderStroke(1.dp, colors.borderSubtle)
                    ) {
                        Text(
                            text = candidate.edgeTag,
                            style = SchwabQuantTheme.numericTypography.badgeMono,
                            color = colors.profit,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                    if (candidate.explainer.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Risk warning",
                                tint = colors.warning,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${candidate.explainer.warnings.size} note",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.warning
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricMiniColumn(
                    label = if (metrics.isNetCredit) "Credit" else "Debit",
                    value = "$${fmt2(metrics.netDebitOrCreditPerShare)}"
                )
                MetricMiniColumn(
                    label = "BPR",
                    value = "$${fmt0(metrics.buyingPowerRequirement)}"
                )
                MetricMiniColumn(
                    label = "POP",
                    value = "${fmt0(metrics.popPct)}%"
                )
                MetricMiniColumn(
                    label = "Δ / Θ",
                    value = "${fmt2(metrics.netDelta)} / $${fmt1(metrics.netThetaPerDay)}"
                )
            }
        }
    }
}

@Composable
private fun MetricMiniColumn(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = SchwabQuantTheme.numericTypography.tableCell,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun regimeFitColor(label: String): Color = when {
    label.startsWith("Optimal", ignoreCase = true) || label.startsWith("Aligned", ignoreCase = true) ->
        SchwabQuantTheme.colors.profit
    label.startsWith("Halted", ignoreCase = true) ||
        label.startsWith("Avoid", ignoreCase = true) ||
        label.startsWith("Counter", ignoreCase = true) -> SchwabQuantTheme.colors.loss
    else -> SchwabQuantTheme.colors.warning
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ManageWatchlistsSheet(
    watchlists: List<Watchlist>,
    selectedWatchlist: Watchlist?,
    onDismiss: () -> Unit,
    onSelectWatchlist: (Long) -> Unit,
    onCreateWatchlist: (String, String) -> Unit,
    onRenameWatchlist: (Long, String) -> Unit,
    onDeleteWatchlist: (Long) -> Unit,
    onAddTicker: (Long, String) -> Unit,
    onRemoveTicker: (Long, String) -> Unit
) {
    var newListName by rememberSaveable { mutableStateOf("") }
    var newListTickers by rememberSaveable { mutableStateOf("") }
    var addTickerInput by rememberSaveable { mutableStateOf("") }
    var renamingWatchlist by remember { mutableStateOf<Watchlist?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Manage Watchlists & Symbols",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            // List Switcher + Rename/Delete Actions
            items(watchlists, key = { it.id }) { wl ->
                val isSelected = selectedWatchlist?.id == wl.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectWatchlist(wl.id) },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else SchwabQuantTheme.colors.borderSubtle
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = wl.name + if (wl.isDefault) " (Default)" else "",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "${wl.tickers.size} tickers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row {
                            IconButton(
                                onClick = {
                                    renamingWatchlist = wl
                                    renameDraft = wl.name
                                }
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename watchlist")
                            }
                            if (!wl.isDefault) {
                                IconButton(onClick = { onDeleteWatchlist(wl.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete watchlist",
                                        tint = SchwabQuantTheme.colors.loss
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Active Watchlist Tickers CRUD
            selectedWatchlist?.let { active ->
                item {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tickers in '${active.name}' (${active.tickers.size})",
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = addTickerInput,
                            onValueChange = { addTickerInput = it.uppercase() },
                            label = { Text("Add Ticker (e.g. NVDA, BRK.B)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val sym = addTickerInput.trim()
                                if (sym.isNotEmpty()) {
                                    onAddTicker(active.id, sym)
                                    addTickerInput = ""
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add")
                        }
                    }
                }

                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        active.tickers.forEach { ticker ->
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = {
                                    Text(
                                        text = ticker,
                                        style = SchwabQuantTheme.numericTypography.badgeMono
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove $ticker",
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { onRemoveTicker(active.id, ticker) }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Create New Watchlist
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Create New Watchlist",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newListName,
                        onValueChange = { newListName = it },
                        label = { Text("Watchlist Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newListTickers,
                        onValueChange = { newListTickers = it.uppercase() },
                        label = { Text("Initial Tickers (comma separated)") },
                        placeholder = { Text("SPY, QQQ, IWM, NVDA") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (newListName.isNotBlank()) {
                                onCreateWatchlist(newListName, newListTickers)
                                newListName = ""
                                newListTickers = ""
                            }
                        },
                        enabled = newListName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create Watchlist")
                    }
                }
            }
        }
    }

    renamingWatchlist?.let { target ->
        AlertDialog(
            onDismissRequest = { renamingWatchlist = null },
            title = { Text("Rename Watchlist") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("New Watchlist Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameWatchlist(target.id, renameDraft)
                        renamingWatchlist = null
                    },
                    enabled = renameDraft.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { renamingWatchlist = null },
                    colors = ButtonDefaults.textButtonColors()
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
