package com.schwabquant.app.presentation.sentinel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schwabquant.app.data.remote.streamer.LiveEquityQuote
import com.schwabquant.app.data.remote.streamer.StreamerConnectionState
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.MicrostructureEvalOutcome
import com.schwabquant.app.domain.model.Position
import com.schwabquant.app.domain.model.RuleRuntimeTelemetry
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleStatus
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.domain.usecase.MarketSessionWindow
import com.schwabquant.app.presentation.components.WorkingOrdersAndAuditCard
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelScreen(
    viewModel: SentinelViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var ordersExpanded by rememberSaveable { mutableStateOf(true) }
    var showAddRuleSheet by rememberSaveable { mutableStateOf(false) }
    var selectedSimRuleId by rememberSaveable { mutableLongStateOf(-1L) }

    val armedCount = remember(state.rules) {
        state.rules.count { it.status == SentinelRuleStatus.ARMED }
    }

    val shadowSimRules = remember(state.rules) {
        state.rules.filter { it.executionMode == ExecutionMode.SHADOW }
    }

    val activeSimRule = remember(shadowSimRules, selectedSimRuleId) {
        shadowSimRules.firstOrNull { it.id == selectedSimRuleId } ?: shadowSimRules.firstOrNull()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
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
                        text = "24/5 Sentinel & Order Desk",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Anti-Stop-Hunt Streamer • Boundary-Safe SEAMLESS Exits",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 1. Sentinel Status Header Card
        item {
            SentinelStatusHeaderCard(
                state = state,
                armedCount = armedCount,
                onStart = { viewModel.startSentinel(context) },
                onStop = { viewModel.stopSentinel(context) },
                onDisarmAll = { viewModel.disarmAllRules(context) }
            )
        }

        // Quick Action Bar: + Add Sentinel Rule & + New Order
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { showAddRuleSheet = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Rule")
                }

                OutlinedButton(
                    onClick = viewModel::openOrderSheet,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Order")
                }
            }
        }

        // Feedback Banner
        state.lastFeedback?.let { msg ->
            item {
                val accent = if (state.isErrorFeedback) {
                    SchwabQuantTheme.colors.loss
                } else {
                    SchwabQuantTheme.colors.profit
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = accent.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = viewModel::clearFeedback,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss feedback",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Active Watch Rules Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Watch Rules (${state.rules.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "$armedCount Armed",
                    style = SchwabQuantTheme.numericTypography.badgeMono,
                    color = if (armedCount > 0) SchwabQuantTheme.colors.profit else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.rules.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SchwabQuantTheme.colors.surfaceElevated)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No Synthetic Sentinel Rules Configured",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Add a Long/Short Stop-Loss, Take-Profit, Anti-Falling-Knife Dip Buy, or Overnight EXTO Alert to protect positions across Pre-Market, RTH, and After-Hours.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { showAddRuleSheet = true }) {
                            Text("+ Create First Sentinel Rule")
                        }
                    }
                }
            }
        } else {
            items(state.rules, key = { it.id }) { rule ->
                SentinelRuleCard(
                    rule = rule,
                    activeExecutionMode = state.executionMode,
                    liveQuote = state.liveQuotes[rule.symbol],
                    telemetry = state.ruleTelemetry[rule.id],
                    onToggleArmed = { viewModel.toggleRuleArmed(rule, context) },
                    onDelete = { viewModel.deleteRule(rule.id) },
                    onStageOrder = { viewModel.stageOrderForRule(rule) }
                )
            }
        }

        // 3. Shadow Mode Tick Replay Simulator Card (STRICTLY HIDDEN IN LIVE MODE)
        if (state.executionMode == ExecutionMode.SHADOW) {
            item {
                ShadowTickReplaySimulatorCard(
                    rules = shadowSimRules,
                    selectedRule = activeSimRule,
                    onSelectRule = { selectedSimRuleId = it.id },
                    onSimulateWick = { rule -> viewModel.simulateStopHuntWick(rule) },
                    onSimulateSpreadBlowout = { rule -> viewModel.simulateSpreadBlowout(rule) },
                    onSimulateConfirmedBreak = { rule -> viewModel.simulateConfirmedPmBreak(rule) },
                    onSimulateOvernightAlert = { rule -> viewModel.simulateOvernightAlert(rule) }
                )
            }
        }

        // 4. Working Orders & Audit Trail Card (Milestone 4 preserved)
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

    if (showAddRuleSheet) {
        AddSentinelRuleBottomSheet(
            heldPositions = state.heldEquityPositions,
            onDismiss = { showAddRuleSheet = false },
            onSave = { form ->
                val saved = viewModel.addRule(form, context)
                if (saved) {
                    showAddRuleSheet = false
                }
            }
        )
    }
}

@Composable
private fun SentinelStatusHeaderCard(
    state: SentinelUiState,
    armedCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDisarmAll: () -> Unit
) {
    val colors = SchwabQuantTheme.colors
    val connColor = when (state.connectionState) {
        StreamerConnectionState.AUTHENTICATED -> colors.profit
        StreamerConnectionState.SIMULATED -> colors.warning
        StreamerConnectionState.CONNECTING,
        StreamerConnectionState.RECONNECTING -> colors.warning
        StreamerConnectionState.ERROR_CONFLICT_CLOSE_12,
        StreamerConnectionState.ERROR -> colors.loss
        StreamerConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val sessionColor = when (state.activeSession) {
        MarketSessionWindow.REGULAR -> colors.profit
        MarketSessionWindow.PRE_MARKET,
        MarketSessionWindow.AFTER_HOURS -> colors.warning
        MarketSessionWindow.OVERNIGHT_EXTO -> MaterialTheme.colorScheme.primary
    }

    val heartbeatText = state.heartbeatAgeSeconds?.let { "${it}s ago" } ?: "—"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
        border = BorderStroke(1.dp, colors.borderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    text = state.connectionState.name.replace('_', ' '),
                    color = connColor
                )
                StatusPill(
                    text = state.activeSession.shortLabel,
                    color = sessionColor
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricCol(label = "SERVICE", value = if (state.serviceStatus.isServiceRunning) "RUNNING" else "STOPPED")
                MetricCol(label = "ARMED", value = armedCount.toString())
                MetricCol(label = "HEARTBEAT", value = heartbeatText)
                MetricCol(
                    label = "EXIT ROUTING",
                    value = if (state.activeSession.allowsAutomatedExecution) "SEAMLESS" else "ALERT ONLY"
                )
            }

            state.serviceStatus.disconnectReason?.let { errReason ->
                Text(
                    text = errReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.loss
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.serviceStatus.isServiceRunning) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop Monitor")
                    }
                } else {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start Sentinel")
                    }
                }

                if (armedCount > 0) {
                    OutlinedButton(
                        onClick = onDisarmAll,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.loss),
                        border = BorderStroke(1.dp, colors.loss.copy(alpha = 0.6f))
                    ) {
                        Text("Disarm All")
                    }
                }
            }
        }
    }
}

@Composable
private fun SentinelRuleCard(
    rule: SentinelRule,
    activeExecutionMode: ExecutionMode,
    liveQuote: LiveEquityQuote?,
    telemetry: RuleRuntimeTelemetry?,
    onToggleArmed: () -> Unit,
    onDelete: () -> Unit,
    onStageOrder: () -> Unit
) {
    val colors = SchwabQuantTheme.colors
    val isModeMismatch = rule.executionMode != activeExecutionMode
    val statusColor = when (rule.status) {
        SentinelRuleStatus.ARMED -> colors.profit
        SentinelRuleStatus.EXECUTING -> colors.warning
        SentinelRuleStatus.TRIGGERED_FILLED -> MaterialTheme.colorScheme.primary
        SentinelRuleStatus.UNFILLED_ESCALATED -> colors.loss
        SentinelRuleStatus.ABORTED_NO_POSITION -> colors.warning
        SentinelRuleStatus.ALERTED -> MaterialTheme.colorScheme.tertiary
        SentinelRuleStatus.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = rule.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    StatusPill(text = rule.ruleType.shortTag, color = MaterialTheme.colorScheme.primary)
                    StatusPill(text = rule.status.label.replace('_', ' '), color = statusColor)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = rule.status == SentinelRuleStatus.ARMED,
                        onCheckedChange = { onToggleArmed() }
                    )
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete rule",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (isModeMismatch) {
                StatusPill(
                    text = "${rule.executionMode.name} RULE — INACTIVE IN ${activeExecutionMode.name} MODE",
                    color = colors.warning
                )
            }

            // Trigger & Anti-Stop-Hunt Parameters
            Text(
                text = "Trigger $${rule.triggerPrice.toPlainString()} • Qty ${rule.quantity} • Collar ${fmt2(rule.slippageCollarPct)}% • Hold ${rule.holdSeconds}s / ${rule.minConsecutiveTicks}t • MaxSpread ${fmt2(rule.maxSpreadPct)}%",
                style = SchwabQuantTheme.numericTypography.tableCell,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (rule.sweepBufferPct > 0.0) {
                Text(
                    text = "Two-Stage Sweep Hard Floor: ${fmt2(rule.sweepBufferPct)}% beyond trigger",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.warning
                )
            }

            // Live Streamer Quote Row
            if (liveQuote != null) {
                val bidStr = liveQuote.bidPrice?.let { "$${fmt2(it)}" } ?: "—"
                val askStr = liveQuote.askPrice?.let { "$${fmt2(it)}" } ?: "—"
                val spreadStr = liveQuote.spreadPct?.let { "${fmt2(it)}%" } ?: "—"
                Text(
                    text = "Live Bid $bidStr  |  Ask $askStr  |  Spread $spreadStr  |  Status ${liveQuote.securityStatus ?: "Normal"}",
                    style = SchwabQuantTheme.numericTypography.badgeMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Runtime Microstructure State Badge & Detail
            if (telemetry != null) {
                val teleColor = when (telemetry.outcome) {
                    MicrostructureEvalOutcome.WATCHING -> MaterialTheme.colorScheme.onSurfaceVariant
                    MicrostructureEvalOutcome.ARMED_WARNING -> colors.warning
                    MicrostructureEvalOutcome.SPREAD_BLOWOUT_SUPPRESSED,
                    MicrostructureEvalOutcome.IGNORED_BAD_QUOTE_OR_HALTED,
                    MicrostructureEvalOutcome.ANTI_KNIFE_FALLING_BLOCKED -> colors.loss
                    MicrostructureEvalOutcome.CONFIRMED_EXECUTE,
                    MicrostructureEvalOutcome.CONFIRMED_DIP_ALERT -> colors.profit
                    MicrostructureEvalOutcome.OVERNIGHT_ALERT_ONLY -> MaterialTheme.colorScheme.tertiary
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(text = telemetry.outcome.badgeLabel, color = teleColor)
                    Text(
                        text = telemetry.detailMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            rule.lastTriggerNote?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }

            if (rule.status == SentinelRuleStatus.UNFILLED_ESCALATED ||
                rule.status == SentinelRuleStatus.ALERTED
            ) {
                OutlinedButton(
                    onClick = onStageOrder,
                    enabled = !isModeMismatch,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (rule.status == SentinelRuleStatus.UNFILLED_ESCALATED) {
                            "Re-Price Escalated Exit in Order Ticket"
                        } else if (rule.ruleType == SentinelRuleType.DIP_BUY_ANTI_KNIFE) {
                            "Review & Biometric-Confirm Dip Buy"
                        } else {
                            "Queue 7:00 AM SEAMLESS Limit Order"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShadowTickReplaySimulatorCard(
    rules: List<SentinelRule>,
    selectedRule: SentinelRule?,
    onSelectRule: (SentinelRule) -> Unit,
    onSimulateWick: (SentinelRule) -> Unit,
    onSimulateSpreadBlowout: (SentinelRule) -> Unit,
    onSimulateConfirmedBreak: (SentinelRule) -> Unit,
    onSimulateOvernightAlert: (SentinelRule) -> Unit
) {
    val colors = SchwabQuantTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.shadowBannerBg.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, colors.warning.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Shadow Mode Tick Replay Simulator",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.shadowBannerFg,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(text = "PAPER ONLY", color = colors.warning)
            }

            Text(
                text = "Deterministic offline verification of Anti-Stop-Hunt, Spread-Blowout suppression, 6-step Shadow exits, and Overnight EXTO alert staging.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (rules.isEmpty() || selectedRule == null) {
                Text(
                    text = "Add at least one Sentinel rule above to run Tick Replay scenarios.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warning
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rules.forEach { rule ->
                        FilterChip(
                            selected = rule.id == selectedRule.id,
                            onClick = { onSelectRule(rule) },
                            label = { Text("${rule.symbol} • ${rule.ruleType.shortTag}") }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSimulateWick(selectedRule) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("1. Stop-Hunt Wick", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { onSimulateSpreadBlowout(selectedRule) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("2. Spread Blowout", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onSimulateConfirmedBreak(selectedRule) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("3. Confirmed PM Break", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { onSimulateOvernightAlert(selectedRule) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("4. Overnight Alert", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSentinelRuleBottomSheet(
    heldPositions: List<Position>,
    onDismiss: () -> Unit,
    onSave: (NewSentinelRuleInput) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var symbol by rememberSaveable { mutableStateOf(heldPositions.firstOrNull()?.symbol ?: "GOOG") }
    var ruleType by rememberSaveable { mutableStateOf(SentinelRuleType.LONG_STOP_LOSS) }
    var triggerPrice by rememberSaveable {
        mutableStateOf(
            heldPositions.firstOrNull()?.let { pos ->
                (pos.markPrice.toDouble() * 0.97).coerceAtLeast(1.0).let { fmt2(it) }
            } ?: "270.00"
        )
    }
    var quantity by rememberSaveable {
        mutableStateOf(
            heldPositions.firstOrNull()?.let { abs(it.quantity.toDouble()).toInt().coerceAtLeast(1).toString() }
                ?: "16"
        )
    }
    var slippageCollarPct by rememberSaveable { mutableStateOf("0.35") }
    var holdSeconds by rememberSaveable { mutableStateOf("15") }
    var minTicks by rememberSaveable { mutableStateOf("3") }
    var maxSpreadPct by rememberSaveable { mutableStateOf("1.5") }
    var sweepBufferPct by rememberSaveable { mutableStateOf("0.0") }
    var antiKnifeLookback by rememberSaveable { mutableStateOf("10") }
    var antiKnifeBounce by rememberSaveable { mutableStateOf("0.25") }
    var isShortPos by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Add 24/5 Sentinel Watch Rule",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            // Held Position Quick-Pick Chips
            if (heldPositions.isNotEmpty()) {
                Text(
                    text = "Quick-Pick Held Position",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    heldPositions.forEach { pos ->
                        val qtyInt = abs(pos.quantity.toDouble()).toInt().coerceAtLeast(1)
                        val isShort = pos.quantity < BigDecimal.ZERO
                        FilterChip(
                            selected = symbol.equals(pos.symbol, ignoreCase = true),
                            onClick = {
                                symbol = pos.symbol
                                quantity = qtyInt.toString()
                                isShortPos = isShort
                                val mark = pos.markPrice.toDouble().takeIf { it > 0.0 } ?: 100.0
                                if (isShort) {
                                    ruleType = SentinelRuleType.SHORT_STOP_LOSS
                                    triggerPrice = BigDecimal.valueOf(mark * 1.03)
                                        .setScale(2, RoundingMode.HALF_UP)
                                        .toPlainString()
                                } else {
                                    ruleType = SentinelRuleType.LONG_STOP_LOSS
                                    triggerPrice = BigDecimal.valueOf(mark * 0.97)
                                        .setScale(2, RoundingMode.HALF_UP)
                                        .toPlainString()
                                }
                            },
                            label = { Text("${pos.symbol} (${if (isShort) "-" else ""}$qtyInt @ $${fmt2(pos.markPrice.toDouble())})") }
                        )
                    }
                }
            }

            // Rule Type Selector
            Text(
                text = "Rule Type",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SentinelRuleType.entries.forEach { type ->
                    FilterChip(
                        selected = ruleType == type,
                        onClick = {
                            ruleType = type
                            if (type == SentinelRuleType.SHORT_STOP_LOSS) isShortPos = true
                            if (type == SentinelRuleType.LONG_STOP_LOSS || type == SentinelRuleType.DIP_BUY_ANTI_KNIFE) {
                                isShortPos = false
                            }
                        },
                        label = { Text(type.displayName) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it.uppercase() },
                    label = { Text("Symbol") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Shares") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = triggerPrice,
                    onValueChange = { triggerPrice = it },
                    label = { Text("Trigger Price ($)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = slippageCollarPct,
                    onValueChange = { slippageCollarPct = it },
                    label = { Text("Collar (%)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            if (ruleType == SentinelRuleType.TAKE_PROFIT || ruleType == SentinelRuleType.OVERNIGHT_PRICE_ALERT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Short Position Direction",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = isShortPos,
                        onCheckedChange = { isShortPos = it }
                    )
                }
            }

            HorizontalDivider()
            Text(
                text = "Anti-Stop-Hunt Microstructure Guards",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = holdSeconds,
                    onValueChange = { holdSeconds = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Hold Timer (s)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = minTicks,
                    onValueChange = { minTicks = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Min Ticks") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = maxSpreadPct,
                    onValueChange = { maxSpreadPct = it },
                    label = { Text("Max Spread (%)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = sweepBufferPct,
                    onValueChange = { sweepBufferPct = it },
                    label = { Text("Sweep Hard Floor (%)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            if (ruleType == SentinelRuleType.DIP_BUY_ANTI_KNIFE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = antiKnifeLookback,
                        onValueChange = { antiKnifeLookback = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Lookback (min)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = antiKnifeBounce,
                        onValueChange = { antiKnifeBounce = it },
                        label = { Text("Bounce (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val safetyNote = when (ruleType) {
                SentinelRuleType.DIP_BUY_ANTI_KNIFE ->
                    "Anti-Falling-Knife entries NEVER auto-execute: once stabilized, Sentinel alerts and stages a SEAMLESS LIMIT buy for Soft-Gate + Biometric confirmation."
                SentinelRuleType.OVERNIGHT_PRICE_ALERT ->
                    "Alert-Only rule: zero API orders are submitted. Tapping the alert stages a 7:00 AM Pre-Market SEAMLESS LIMIT order for review."
                else ->
                    "Automated exits route strictly via SEAMLESS LIMIT DAY (07:00–20:00 ET) after directional position verification. Overnight EXTO (20:00–07:00 ET) is strictly Alert-Only."
            }
            Text(
                text = safetyNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        onSave(
                            NewSentinelRuleInput(
                                symbol = symbol,
                                ruleType = ruleType,
                                triggerPrice = triggerPrice,
                                quantity = quantity,
                                slippageCollarPct = slippageCollarPct,
                                holdSeconds = holdSeconds,
                                minConsecutiveTicks = minTicks,
                                maxSpreadPct = maxSpreadPct,
                                sweepBufferPct = sweepBufferPct,
                                antiKnifeLookbackMinutes = antiKnifeLookback,
                                antiKnifeBouncePct = antiKnifeBounce,
                                isShortPosition = isShortPos
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Arm Rule")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f))
    ) {
        Text(
            text = text,
            style = SchwabQuantTheme.numericTypography.badgeMono,
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun MetricCol(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
