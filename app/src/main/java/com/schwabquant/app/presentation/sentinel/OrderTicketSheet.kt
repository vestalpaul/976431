package com.schwabquant.app.presentation.sentinel

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStrategyCategory
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.presentation.components.BiometricSwipeConfirmBar
import com.schwabquant.app.presentation.components.RiskWarningDialog
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.util.Locale

tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderTicketSheet(
    state: OrderTicketUiState,
    viewModel: OrderTicketViewModel
) {
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    val colors = SchwabQuantTheme.colors
    val isLive = state.executionMode == ExecutionMode.LIVE

    ModalBottomSheet(onDismissRequest = viewModel::dismissSheet) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Execution Mode Banner + Origin Tag
            item {
                Surface(
                    color = if (isLive) colors.liveBannerBg else colors.shadowBannerBg,
                    border = BorderStroke(1.dp, if (isLive) colors.liveBannerFg else colors.shadowBannerFg),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isLive) "LIVE SCHWAB ORDER" else "SHADOW PAPER ORDER",
                            style = SchwabQuantTheme.numericTypography.badgeMono,
                            color = if (isLive) colors.liveBannerFg else colors.shadowBannerFg,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = state.sourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isLive) colors.liveBannerFg else colors.shadowBannerFg
                        )
                    }
                }
            }

            // 2. Strategy Selector Chips
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(OrderStrategyCategory.entries) { cat ->
                        FilterChip(
                            selected = state.strategyCategory == cat,
                            onClick = { viewModel.selectStrategyCategory(cat) },
                            label = { Text(cat.displayName) }
                        )
                    }
                }
            }

            // 3. Underlying / Legs Summary & Editable Fields
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.underlyingSymbol,
                        onValueChange = viewModel::updateUnderlyingSymbol,
                        label = { Text("Underlying") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f)
                    )
                    OutlinedTextField(
                        value = state.quantityInput,
                        onValueChange = viewModel::updateQuantity,
                        label = { Text(if (state.strategyCategory == OrderStrategyCategory.SINGLE_EQUITY) "Shares" else "Contracts") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (state.strategyCategory == OrderStrategyCategory.SINGLE_EQUITY) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(OrderSide.BUY, OrderSide.SELL, OrderSide.BUY_TO_COVER)) { side ->
                            FilterChip(
                                selected = state.legs.firstOrNull()?.side == side,
                                onClick = { viewModel.selectSingleLegSide(side) },
                                label = { Text(side.name) }
                            )
                        }
                    }
                }
            } else if (state.strategyCategory == OrderStrategyCategory.SINGLE_OPTION) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = state.legs.firstOrNull()?.symbol.orEmpty(),
                            onValueChange = viewModel::updateSingleOptionOccSymbol,
                            label = { Text("OCC 21-Char Option Symbol") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(
                                listOf(
                                    OrderSide.BUY_TO_OPEN,
                                    OrderSide.SELL_TO_OPEN,
                                    OrderSide.BUY_TO_CLOSE,
                                    OrderSide.SELL_TO_CLOSE
                                )
                            ) { side ->
                                FilterChip(
                                    selected = state.legs.firstOrNull()?.side == side,
                                    onClick = { viewModel.selectSingleLegSide(side) },
                                    label = { Text(side.name) }
                                )
                            }
                        }
                    }
                }
            } else {
                // Multi-leg preview card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "LEGS (${state.legs.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            state.legs.forEach { leg ->
                                Text(
                                    text = "${leg.side.name} ${leg.quantity}x ${leg.symbol}",
                                    style = SchwabQuantTheme.numericTypography.badgeMono
                                )
                            }
                        }
                    }
                }
            }

            // 4. Live Quote Strip + Estimated BPR
            if (state.quoteBid != null || state.quoteMid != null || state.quoteAsk != null || state.estimatedBpr != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Bid ${state.quoteBid?.let { "$${fmt2(it)}" } ?: "--"} | " +
                                    "Mid ${state.quoteMid?.let { "$${fmt2(it)}" } ?: "--"} | " +
                                    "Ask ${state.quoteAsk?.let { "$${fmt2(it)}" } ?: "--"}",
                                style = SchwabQuantTheme.numericTypography.tableCell,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = "Est. BPR ${state.estimatedBpr?.let { "$${fmt0(it)}" } ?: "--"}",
                                    style = SchwabQuantTheme.numericTypography.tableCell,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // 5. Order Type + Pricing Inputs
            item {
                if (state.strategyCategory == OrderStrategyCategory.OCO_BRACKET) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.ocoTakeProfitInput,
                            onValueChange = viewModel::updateOcoTakeProfit,
                            label = { Text("Take-Profit LIMIT ($)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.ocoStopPriceInput,
                            onValueChange = viewModel::updateOcoStopPrice,
                            label = { Text("Hard STOP ($)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val allowedTypes = when (state.strategyCategory) {
                            OrderStrategyCategory.SINGLE_EQUITY -> if (state.session != TradingSession.NORMAL) {
                                listOf(OrderType.LIMIT)
                            } else {
                                listOf(OrderType.LIMIT, OrderType.MARKET, OrderType.STOP, OrderType.STOP_LIMIT)
                            }
                            OrderStrategyCategory.SINGLE_OPTION -> listOf(OrderType.LIMIT, OrderType.MARKET)
                            OrderStrategyCategory.DIAGONAL,
                            OrderStrategyCategory.CALENDAR -> listOf(OrderType.NET_DEBIT, OrderType.NET_CREDIT)
                            OrderStrategyCategory.VERTICAL,
                            OrderStrategyCategory.IRON_CONDOR -> listOf(OrderType.NET_CREDIT, OrderType.NET_DEBIT)
                            OrderStrategyCategory.OCO_BRACKET -> emptyList()
                        }

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(allowedTypes) { t ->
                                FilterChip(
                                    selected = state.orderType == t,
                                    onClick = { viewModel.updateOrderType(t) },
                                    label = { Text(t.apiValue) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (state.orderType != OrderType.MARKET && state.orderType != OrderType.STOP) {
                                OutlinedTextField(
                                    value = state.limitPriceInput,
                                    onValueChange = viewModel::updateLimitPrice,
                                    label = {
                                        Text(
                                            when (state.orderType) {
                                                OrderType.NET_DEBIT -> "Net Debit ($)"
                                                OrderType.NET_CREDIT -> "Net Credit ($)"
                                                else -> "Limit Price ($)"
                                            }
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (state.orderType == OrderType.STOP || state.orderType == OrderType.STOP_LIMIT) {
                                OutlinedTextField(
                                    value = state.stopPriceInput,
                                    onValueChange = viewModel::updateStopPrice,
                                    label = { Text("Stop Price ($)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // 6. Session & Duration (with Schwab Extended-Hours LIMIT + DAY indicator)
            if (state.strategyCategory == OrderStrategyCategory.SINGLE_EQUITY) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(TradingSession.entries) { session ->
                                FilterChip(
                                    selected = state.session == session,
                                    onClick = { viewModel.updateSession(session) },
                                    label = { Text(session.apiValue) }
                                )
                            }
                        }
                        if (state.session != TradingSession.NORMAL) {
                            Text(
                                text = "Extended-hours session enforces LIMIT + DAY per Schwab API rules.",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.warning
                            )
                        } else if (state.orderType == OrderType.MARKET) {
                            Text(
                                text = "MARKET orders enforce DAY duration per Schwab API rules.",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.warning
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OrderDuration.entries.forEach { dur ->
                                    FilterChip(
                                        selected = state.duration == dur,
                                        onClick = { viewModel.updateDuration(dur) },
                                        label = { Text(if (dur == OrderDuration.GOOD_TILL_CANCEL) "GTC" else "DAY") }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7. Shadow Immediate Fill & Mid-Price Walk-Up Controls
            if (!isLive) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Shadow Immediate Mid-Fill", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "Turn off to queue as WORKING order for manual Simulate Fill / Walk-Up testing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.simulateImmediateMidFill,
                            onCheckedChange = viewModel::setImmediateMidFill
                        )
                    }
                }
            }

            val isRepriceTicket = !state.replacePriorOrderId.isNullOrBlank()
            if (state.strategyCategory != OrderStrategyCategory.OCO_BRACKET &&
                (state.orderType == OrderType.LIMIT ||
                    state.orderType == OrderType.NET_DEBIT ||
                    state.orderType == OrderType.NET_CREDIT)
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Assisted Mid-Price Walk-Up", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = if (isRepriceTicket) {
                                            "Walk-Up disabled on Re-Price Replace tickets (cancels & verifies #${state.replacePriorOrderId} first)"
                                        } else {
                                            "Steps $0.05 every ${state.walkUpIntervalSeconds}s via Cancel -> 1.5s Verify -> Replace"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isRepriceTicket) colors.warning else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = if (isRepriceTicket) false else state.walkUpEnabled,
                                    enabled = !isRepriceTicket,
                                    onCheckedChange = viewModel::setWalkUpEnabled
                                )
                            }

                            if (state.walkUpEnabled) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = state.walkUpCeilingInput,
                                        onValueChange = viewModel::updateWalkUpCeiling,
                                        label = {
                                            Text(
                                                if (state.orderType == OrderType.NET_CREDIT) "Floor Credit ($)"
                                                else "Hard Max Walk-Up ($)"
                                            )
                                        },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = state.walkUpAutoStep,
                                        onClick = { viewModel.setWalkUpAutoStep(!state.walkUpAutoStep) },
                                        label = { Text(if (state.walkUpAutoStep) "Auto-Step" else "Prompt") }
                                    )
                                }
                            }

                            val wu = state.walkUpState
                            if (wu.isActive || !wu.statusMessage.isNullOrBlank()) {
                                HorizontalDivider()
                                Text(
                                    text = wu.statusMessage.orEmpty(),
                                    style = SchwabQuantTheme.numericTypography.tableCell,
                                    color = if (wu.isActive) colors.warning else MaterialTheme.colorScheme.onSurface
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (wu.waitingForStepPrompt) {
                                        Button(onClick = viewModel::confirmManualWalkUpStep) {
                                            Text("Step $0.05 Now")
                                        }
                                    }
                                    if (wu.isActive) {
                                        OutlinedButton(onClick = viewModel::cancelWalkUp) {
                                            Text("Cancel Walk-Up")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 8. Soft-Gate Review -> Biometric Swipe Flow
            item {
                if (!state.readyForBiometricSwipe) {
                    Button(
                        onClick = viewModel::reviewAndSubmit,
                        enabled = !state.isSubmitting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Review & Submit")
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (state.overriddenRiskCodes.isEmpty()) {
                                "0 Risk Warnings Triggered — Swipe to Authorize"
                            } else {
                                "Risk Override Logged (${state.overriddenRiskCodes.size}) — Swipe to Authorize"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.overriddenRiskCodes.isEmpty()) colors.profit else colors.warning
                        )
                        BiometricSwipeConfirmBar(
                            executionMode = state.executionMode,
                            enabled = !state.isSubmitting,
                            resetKey = state.swipeResetCounter,
                            onConfirmTriggered = {
                                viewModel.onBiometricSwipeCompleted(activity)
                            }
                        )
                    }
                }

                TextButton(
                    onClick = viewModel::dismissSheet,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }

    state.pendingRiskAssessment?.let { assessment ->
        RiskWarningDialog(
            warnings = assessment.warnings,
            isBlocked = assessment.isBlocked,
            blockReason = assessment.blockReason,
            onDismiss = viewModel::dismissRiskDialog,
            onAcknowledgeAndProceed = viewModel::acknowledgeRisksAndContinue
        )
    }
}

private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
