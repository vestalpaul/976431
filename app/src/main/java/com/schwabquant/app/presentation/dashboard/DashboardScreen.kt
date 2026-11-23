package com.schwabquant.app.presentation.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schwabquant.app.core.quant.StressScenarioResult
import com.schwabquant.app.domain.model.AccountSummary
import com.schwabquant.app.domain.model.AggregatedGreeks
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.Position
import com.schwabquant.app.domain.model.UnderlyingPositionGroup
import com.schwabquant.app.domain.usecase.AdviceSeverity
import com.schwabquant.app.domain.usecase.ExDivRadarAlert
import com.schwabquant.app.domain.usecase.ExDivRiskLevel
import com.schwabquant.app.domain.usecase.PortfolioAdviceCard
import com.schwabquant.app.domain.usecase.WashSaleFlag
import com.schwabquant.app.presentation.components.DeepAnalysisButton
import com.schwabquant.app.presentation.components.WorkingOrdersAndAuditCard
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenMarketDetail: (String) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var washRadarExpanded by rememberSaveable { mutableStateOf(true) }
    var exDivRadarExpanded by rememberSaveable { mutableStateOf(true) }
    var ordersExpanded by rememberSaveable { mutableStateOf(false) }
    var showLogTradeDialog by rememberSaveable { mutableStateOf(false) }

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
                // 1. Source Toggle Strip + Actions
                item {
                    SourceToggleHeader(
                        selectedSource = state.selectedSource,
                        showSeedDemo = state.selectedSource == PortfolioDataSource.SHADOW_PAPER_LEDGER &&
                            (state.snapshot?.positions.isNullOrEmpty()),
                        onSelectSource = viewModel::selectDataSource,
                        onRefresh = { viewModel.refresh() },
                        onSeedDemo = viewModel::seedDemoPortfolio,
                        onOpenChartChain = {
                            val defaultTicker = state.snapshot?.groups?.firstOrNull()?.underlyingSymbol ?: "SPY"
                            onOpenMarketDetail(defaultTicker)
                        },
                        onNewOrder = viewModel::openNewOrderTicket,
                        onGeminiHandoff = viewModel::handoffPortfolioToGemini
                    )
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
                                style = MaterialTheme.typography.bodyMedium,
                                color = SchwabQuantTheme.colors.liveBannerFg,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                val snapshot = state.snapshot
                if (snapshot == null) {
                    item {
                        EmptyPortfolioCard(
                            selectedSource = state.selectedSource,
                            onSeedDemo = viewModel::seedDemoPortfolio,
                            onRefreshLive = { viewModel.refresh(PortfolioDataSource.LIVE_SCHWAB_READ_ONLY) }
                        )
                    }
                } else {
                    // 2. Account Telemetry Card
                    item {
                        AccountTelemetryCard(
                            summary = snapshot.accountSummary,
                            source = snapshot.dataSource
                        )
                    }

                    // 3. Aggregated Greeks Ribbon
                    item {
                        AggregatedGreeksRibbon(
                            greeks = snapshot.aggregatedGreeks,
                            spySpot = snapshot.spySpot
                        )
                    }

                    // 4. Interactive Portfolio Stress Matrix Card
                    if (state.stressMatrix.isNotEmpty()) {
                        item {
                            StressMatrixCard(
                                scenarios = state.stressMatrix,
                                includeVolSkewShock = state.includeVolSkewShock,
                                onToggleVolSkew = viewModel::toggleVolSkewShock
                            )
                        }
                    }

                    // 4B. Portfolio Advisor Diagnostic Feed (One-Tap Actionable Cards)
                    if (state.adviceCards.isNotEmpty()) {
                        item {
                            AdvisorFeedSection(
                                cards = state.adviceCards,
                                onStageAdviceOrder = viewModel::stageAdviceOrder
                            )
                        }
                    }

                    // 5. Grouped Positions Accordion
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Positions by Underlying (${snapshot.groups.size})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Sorted by Weight",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (snapshot.groups.isEmpty()) {
                        item {
                            EmptyPortfolioCard(
                                selectedSource = state.selectedSource,
                                onSeedDemo = viewModel::seedDemoPortfolio,
                                onRefreshLive = { viewModel.refresh(PortfolioDataSource.LIVE_SCHWAB_READ_ONLY) }
                            )
                        }
                    } else {
                        items(
                            items = snapshot.groups,
                            key = { it.underlyingSymbol }
                        ) { group ->
                            val groupExDivAlerts = state.exDivAlerts.filter {
                                it.underlyingSymbol.equals(group.underlyingSymbol, ignoreCase = true) &&
                                    it.riskLevel != ExDivRiskLevel.SAFE
                            }
                            GroupedPositionCard(
                                group = group,
                                groupExDivAlerts = groupExDivAlerts,
                                expanded = state.expandedGroups.contains(group.underlyingSymbol),
                                onToggleExpand = { viewModel.toggleGroupExpanded(group.underlyingSymbol) },
                                onOpenChartChain = { onOpenMarketDetail(group.underlyingSymbol) }
                            )
                        }
                    }
                }

                // 6A. 61-Day Wash-Sale Radar Card
                item {
                    WashSaleRadarCard(
                        flags = state.washSaleFlags,
                        expanded = washRadarExpanded,
                        onToggleExpand = { washRadarExpanded = !washRadarExpanded },
                        onOpenLogTrade = { showLogTradeDialog = true },
                        onSyncSchwab = viewModel::syncWashSalesFromSchwab
                    )
                }

                // 6B. Ex-Div Radar Card
                item {
                    ExDivRadarCard(
                        alerts = state.exDivAlerts,
                        expanded = exDivRadarExpanded,
                        onToggleExpand = { exDivRadarExpanded = !exDivRadarExpanded }
                    )
                }

                // 6C. Working Orders & Recent Audit Log
                item {
                    WorkingOrdersAndAuditCard(
                        workingOrders = state.workingOrders,
                        auditLogs = state.auditLogs,
                        executionMode = state.executionMode,
                        expanded = ordersExpanded,
                        onToggleExpand = { ordersExpanded = !ordersExpanded },
                        onRefreshOrders = viewModel::refreshWorkingOrders,
                        onCancelOrder = viewModel::cancelWorkingOrder,
                        onSimulateShadowFill = viewModel::simulateShadowFill
                    )
                }
            }
        }
    }

    if (showLogTradeDialog) {
        ManualWashSaleTradeDialog(
            onDismiss = { showLogTradeDialog = false },
            onSave = { underlying, symbol, assetType, instruction, qty, price, pnl ->
                viewModel.logManualWashSaleTrade(
                    underlyingSymbol = underlying,
                    symbol = symbol,
                    assetType = assetType,
                    instruction = instruction,
                    quantity = qty,
                    price = price,
                    realizedPnl = pnl
                )
                showLogTradeDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceToggleHeader(
    selectedSource: PortfolioDataSource,
    showSeedDemo: Boolean,
    onSelectSource: (PortfolioDataSource) -> Unit,
    onRefresh: () -> Unit,
    onSeedDemo: () -> Unit,
    onOpenChartChain: () -> Unit,
    onNewOrder: () -> Unit,
    onGeminiHandoff: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                PortfolioDataSource.entries.forEachIndexed { idx, source ->
                    SegmentedButton(
                        selected = selectedSource == source,
                        onClick = { onSelectSource(source) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = idx,
                            count = PortfolioDataSource.entries.size
                        )
                    ) {
                        Text(
                            text = source.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            IconButton(onClick = onRefresh) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Refresh portfolio")
            }
        }

        if (showSeedDemo) {
            Button(
                onClick = onSeedDemo,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Seed Demo Paper Portfolio")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenChartChain,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ShowChart,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Chart & Chain", maxLines = 1)
            }
            OutlinedButton(
                onClick = onNewOrder,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Order", maxLines = 1)
            }
        }

        DeepAnalysisButton(
            label = "Deep Analysis in Gemini",
            onClick = onGeminiHandoff,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AdvisorFeedSection(
    cards: List<PortfolioAdviceCard>,
    onStageAdviceOrder: (com.schwabquant.app.domain.model.OrderDraft) -> Unit
) {
    val colors = SchwabQuantTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Portfolio Advisor (${cards.size})",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Rules Engine",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        cards.forEach { card ->
            val badgeColor = when (card.severity) {
                AdviceSeverity.CRITICAL -> colors.loss
                AdviceSeverity.WARNING -> colors.warning
                AdviceSeverity.OPPORTUNITY -> colors.profit
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
                border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
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
                            text = card.title,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        StatusChip(text = card.severity.name, color = badgeColor)
                    }
                    Text(
                        text = card.rationale,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val actionLabel = card.recommendedActionLabel
                    val draft = card.orderDraft
                    if (!actionLabel.isNullOrBlank() && draft != null) {
                        Button(
                            onClick = { onStageAdviceOrder(draft) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(actionLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountTelemetryCard(
    summary: AccountSummary,
    source: PortfolioDataSource
) {
    val cashBufferColor = when {
        summary.cashBufferPercent < 8.0 -> SchwabQuantTheme.colors.loss
        summary.cashBufferPercent < 12.0 -> SchwabQuantTheme.colors.warning
        else -> SchwabQuantTheme.colors.profit
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${source.label} • ${summary.maskedAccountNumber}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = cashBufferColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = "CASH ${fmt1(summary.cashBufferPercent)}%",
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        color = cashBufferColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = "$${fmtMoney(summary.netLiquidationValue)}",
                style = SchwabQuantTheme.numericTypography.priceLarge
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricPair(
                    label = "Settled Cash",
                    value = "$${fmtMoney(summary.settledCash)}"
                )
                MetricPair(
                    label = "Reg-T Margin BP",
                    value = "$${fmtMoney(summary.buyingPower)}"
                )
            }

            HorizontalDivider(color = SchwabQuantTheme.colors.borderSubtle)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val openColor = pnlColor(summary.openPnl.toDouble())
                val dayColor = pnlColor(summary.dayPnl.toDouble())
                MetricPair(
                    label = "Open P&L",
                    value = "${signedMoney(summary.openPnl)} (${signedPct(summary.openPnlPercent)})",
                    valueColor = openColor
                )
                MetricPair(
                    label = "Day P&L",
                    value = "${signedMoney(summary.dayPnl)} (${signedPct(summary.dayPnlPercent)})",
                    valueColor = dayColor
                )
            }
        }
    }
}

@Composable
private fun AggregatedGreeksRibbon(
    greeks: AggregatedGreeks,
    spySpot: Double
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SchwabQuantTheme.colors.surfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Portfolio Greeks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "SPY Ref $${fmt2(spySpot)}",
                    style = SchwabQuantTheme.numericTypography.badgeMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GreekTile("SPY β-Δ", fmt2(greeks.spyBetaWeightedDelta))
                GreekTile(
                    "Theta /d",
                    "${if (greeks.netDailyTheta >= 0) "+" else ""}$${fmt2(greeks.netDailyTheta)}",
                    color = pnlColor(greeks.netDailyTheta)
                )
                GreekTile("Gamma", fmt4(greeks.netGamma))
                GreekTile("Vega /1%", "$${fmt2(greeks.netVegaPer1Pct)}")
            }
        }
    }
}

@Composable
private fun StressMatrixCard(
    scenarios: List<StressScenarioResult>,
    includeVolSkewShock: Boolean,
    onToggleVolSkew: (Boolean) -> Unit
) {
    val maxAbsPnl = scenarios.maxOfOrNull { abs(it.portfolioDollarPnl) }?.coerceAtLeast(1.0) ?: 1.0

    Card(modifier = Modifier.fillMaxWidth()) {
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
                        text = "SPY Stress Matrix",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (includeVolSkewShock) "With realistic Vol Skew IV shock" else "Constant IV (spot shock only)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Vol Skew",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = includeVolSkewShock,
                        onCheckedChange = onToggleVolSkew
                    )
                }
            }

            scenarios.forEach { scenario ->
                val ratio = (abs(scenario.portfolioDollarPnl) / maxAbsPnl).toFloat().coerceIn(0.04f, 1f)
                val barColor = pnlColor(scenario.portfolioDollarPnl)
                val shockSign = if (scenario.spyShockPercent > 0) "+" else ""
                val pnlSign = if (scenario.portfolioDollarPnl >= 0) "+" else "-"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${shockSign}${fmt0(scenario.spyShockPercent)}%",
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        modifier = Modifier.width(42.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(SchwabQuantTheme.colors.surfaceElevated)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(ratio)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(barColor)
                        )
                    }
                    Text(
                        text = "$pnlSign$${fmt0(abs(scenario.portfolioDollarPnl))} (${signedPct(scenario.portfolioPercentChange)})",
                        style = SchwabQuantTheme.numericTypography.tableCell,
                        color = barColor
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupedPositionCard(
    group: UnderlyingPositionGroup,
    groupExDivAlerts: List<ExDivRadarAlert>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenChartChain: () -> Unit
) {
    val openColor = pnlColor(group.combinedOpenPnl.toDouble())
    Card(
        border = BorderStroke(1.dp, SchwabQuantTheme.colors.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
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
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.clickable(onClick = onOpenChartChain)
                        ) {
                            Text(
                                text = group.underlyingSymbol,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$${fmt2(group.underlyingSpot)} • ${fmt1(group.weightPercent)}% WT",
                            style = SchwabQuantTheme.numericTypography.tableCell,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Δ ${fmt2(group.groupNetDelta)} • Θ $${fmt2(group.groupNetTheta)}/d • MV $${fmtMoney(group.totalMarketValue)}",
                        style = SchwabQuantTheme.numericTypography.tableCell,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = signedMoney(group.combinedOpenPnl),
                        style = SchwabQuantTheme.numericTypography.metricValue,
                        color = openColor
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }

            if (group.anomalies.isNotEmpty() || groupExDivAlerts.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    group.anomalies.forEach { anomaly ->
                        StatusChip(
                            text = anomaly.message,
                            color = SchwabQuantTheme.colors.warning
                        )
                    }
                    if (groupExDivAlerts.isNotEmpty()) {
                        val worst = groupExDivAlerts.first()
                        val chipColor = if (worst.riskLevel == ExDivRiskLevel.CRITICAL) {
                            SchwabQuantTheme.colors.loss
                        } else {
                            SchwabQuantTheme.colors.warning
                        }
                        StatusChip(
                            text = "EX-DIV ${worst.daysUntilExDiv}D",
                            color = chipColor
                        )
                    }
                }
            }

            if (expanded) {
                HorizontalDivider(color = SchwabQuantTheme.colors.borderSubtle)
                group.legs.forEach { leg ->
                    PositionLegRow(leg)
                }
                OutlinedButton(
                    onClick = onOpenChartChain,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Chart & Chain (${group.underlyingSymbol})")
                }
            }
        }
    }
}

@Composable
private fun PositionLegRow(leg: Position) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${leg.quantity.stripTrailingZeros().toPlainString()}x ${leg.symbol}",
                style = SchwabQuantTheme.numericTypography.greekValue
            )
            val details = if (leg.category == AssetCategory.OPTION) {
                val bsTag = if (leg.greeksFromBlackScholesFallback) " [BS]" else ""
                "K=$${fmt2(leg.strike ?: 0.0)} • ${leg.daysToExpiration ?: 0}DTE • " +
                    "Δ=${fmt2(leg.delta ?: 0.0)}$bsTag • Extr=$${fmt2(leg.extrinsicValue ?: 0.0)}"
            } else {
                "Avg $${fmt2(leg.averageCost.toDouble())} • Mark $${fmt2(leg.markPrice.toDouble())}"
            }
            Text(
                text = details,
                style = SchwabQuantTheme.numericTypography.tableCell,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = signedMoney(leg.unrealizedPnl),
            style = SchwabQuantTheme.numericTypography.tableCell,
            color = pnlColor(leg.unrealizedPnl.toDouble())
        )
    }
}

@Composable
private fun WashSaleRadarCard(
    flags: List<WashSaleFlag>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenLogTrade: () -> Unit,
    onSyncSchwab: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "61-Day IRC § 1091 Wash-Sale Radar (${flags.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }

            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenLogTrade,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Log Trade")
                    }
                    OutlinedButton(
                        onClick = onSyncSchwab,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sync 65d")
                    }
                }

                if (flags.isEmpty()) {
                    Text(
                        text = "No active 61-day wash-sale flags detected across equities or Call LEAPS.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    flags.forEach { flag ->
                        Surface(
                            color = SchwabQuantTheme.colors.surfaceElevated,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, SchwabQuantTheme.colors.warning.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${flag.underlyingSymbol} — Disallowed $${flag.disallowedLossAmount}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SchwabQuantTheme.colors.warning
                                    )
                                    if (flag.isCrossAssetStockToOption) {
                                        StatusChip(
                                            text = "STOCK -> CALL",
                                            color = SchwabQuantTheme.colors.loss
                                        )
                                    }
                                }
                                Text(
                                    text = "Loss Lot: ${flag.lossSymbol} (${flag.lossDate}) -> Replacement: ${flag.replacementLotSymbol}",
                                    style = SchwabQuantTheme.numericTypography.tableCell
                                )
                                Text(
                                    text = "Adjusted Basis: $${flag.adjustedReplacementBasis} • Safe Re-Buy: ${flag.clearToRebuyDate}",
                                    style = SchwabQuantTheme.numericTypography.tableCell,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExDivRadarCard(
    alerts: List<ExDivRadarAlert>,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ex-Dividend Early Assignment Radar (${alerts.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }

            if (expanded) {
                if (alerts.isEmpty()) {
                    Text(
                        text = "No short calls with upcoming ex-dividend events.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    alerts.forEach { alert ->
                        val alertColor = when (alert.riskLevel) {
                            ExDivRiskLevel.CRITICAL -> SchwabQuantTheme.colors.loss
                            ExDivRiskLevel.WARNING -> SchwabQuantTheme.colors.warning
                            ExDivRiskLevel.SAFE -> SchwabQuantTheme.colors.profit
                        }
                        Surface(
                            color = SchwabQuantTheme.colors.surfaceElevated,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, alertColor.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${alert.optionSymbol} (${alert.shortContracts}x)",
                                        style = SchwabQuantTheme.numericTypography.greekValue
                                    )
                                    StatusChip(text = alert.riskLevel.name, color = alertColor)
                                }
                                Text(
                                    text = alert.rationale,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPortfolioCard(
    selectedSource: PortfolioDataSource,
    onSeedDemo: () -> Unit,
    onRefreshLive: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (selectedSource == PortfolioDataSource.SHADOW_PAPER_LEDGER) {
                        "Paper Ledger is Empty"
                    } else {
                        "Live Schwab Account Not Loaded"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = if (selectedSource == PortfolioDataSource.SHADOW_PAPER_LEDGER) {
                    "Tap Seed Demo to populate a realistic PMCC + NVDA LEAPS portfolio with $2,125 cash and an IRC § 1091 wash-sale scenario."
                } else {
                    "Configure Schwab OAuth in Settings, then pull down or tap Refresh to fetch read-only balances and positions."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selectedSource == PortfolioDataSource.SHADOW_PAPER_LEDGER) {
                Button(onClick = onSeedDemo, modifier = Modifier.fillMaxWidth()) {
                    Text("Seed Demo PMCC Portfolio")
                }
            } else {
                Button(onClick = onRefreshLive, modifier = Modifier.fillMaxWidth()) {
                    Text("Fetch Live Account")
                }
            }
        }
    }
}

@Composable
private fun ManualWashSaleTradeDialog(
    onDismiss: () -> Unit,
    onSave: (
        underlying: String,
        symbol: String,
        assetType: String,
        instruction: String,
        qty: Int,
        price: Double,
        realizedPnl: Double?
    ) -> Unit
) {
    var underlying by remember { mutableStateOf("NVDA") }
    var symbol by remember { mutableStateOf("NVDA") }
    var assetType by remember { mutableStateOf("EQUITY") }
    var instruction by remember { mutableStateOf("SELL") }
    var qtyText by remember { mutableStateOf("22") }
    var priceText by remember { mutableStateOf("136.50") }
    var pnlText by remember { mutableStateOf("-318.44") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Wash-Sale Trade Lot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = underlying,
                        onValueChange = { underlying = it.uppercase() },
                        label = { Text("Underlying") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = symbol,
                        onValueChange = { symbol = it.uppercase() },
                        label = { Text("Symbol / OCC") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("EQUITY", "OPTION").forEach { type ->
                        FilterChip(
                            selected = assetType == type,
                            onClick = { assetType = type },
                            label = { Text(type) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val options = if (assetType == "EQUITY") {
                        listOf("BUY", "SELL")
                    } else {
                        listOf("BUY_TO_OPEN", "SELL_TO_CLOSE")
                    }
                    options.forEach { inst ->
                        FilterChip(
                            selected = instruction == inst,
                            onClick = { instruction = inst },
                            label = { Text(inst) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it },
                        label = { Text("Qty") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("Price") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = pnlText,
                    onValueChange = { pnlText = it },
                    label = { Text("Realized P&L (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val qty = qtyText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val price = priceText.toDoubleOrNull() ?: 0.0
                    val pnl = pnlText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                    onSave(underlying, symbol, assetType, instruction, qty, price, pnl)
                },
                enabled = underlying.isNotBlank() && symbol.isNotBlank()
            ) {
                Text("Save Lot")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun MetricPair(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = SchwabQuantTheme.numericTypography.metricValue,
            color = valueColor
        )
    }
}

@Composable
private fun GreekTile(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = SchwabQuantTheme.numericTypography.metricValue,
            color = color
        )
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = SchwabQuantTheme.numericTypography.badgeMono,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun pnlColor(value: Double): Color =
    if (value >= 0.0) SchwabQuantTheme.colors.profit else SchwabQuantTheme.colors.loss

private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
private fun fmt4(v: Double): String = String.format(Locale.US, "%.4f", v)
private fun fmtMoney(bd: BigDecimal): String = String.format(Locale.US, "%,.2f", bd.toDouble())
private fun signedMoney(bd: BigDecimal): String {
    val v = bd.toDouble()
    val sign = if (v >= 0.0) "+" else "-"
    return "$sign$${String.format(Locale.US, "%,.2f", abs(v))}"
}

private fun signedPct(v: Double): String {
    val sign = if (v >= 0.0) "+" else "-"
    return "$sign${String.format(Locale.US, "%.2f", abs(v))}%"
}
