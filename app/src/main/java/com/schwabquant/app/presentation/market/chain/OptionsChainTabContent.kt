package com.schwabquant.app.presentation.market.chain

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.schwabquant.app.core.quant.ChainColumnPreset
import com.schwabquant.app.core.quant.ExpirationChainSection
import com.schwabquant.app.core.quant.OptionStrikeRow
import com.schwabquant.app.core.quant.ParsedOptionContract
import com.schwabquant.app.core.quant.SelectedChainLeg
import com.schwabquant.app.core.quant.StrikeWindowFilter
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.presentation.market.MarketDetailUiState
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

@Composable
fun OptionsChainTabContent(
    state: MarketDetailUiState,
    onSelectStrikeWindow: (StrikeWindowFilter) -> Unit,
    onSelectColumnPreset: (ChainColumnPreset) -> Unit,
    onToggleExpiration: (LocalDate) -> Unit,
    onToggleSpreadBuilder: (Boolean) -> Unit,
    onQuoteTapped: (ParsedOptionContract, OrderSide) -> Unit,
    onClearSpread: () -> Unit,
    onStageSpread: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedBySymbol = state.selectedSpreadLegs.associateBy { it.contract.symbol }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Controls Strip: Strike Window + Column Preset + Spread Builder Switch
        Surface(
            color = SchwabQuantTheme.colors.surfaceElevated,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(StrikeWindowFilter.entries, key = { it.name }) { win ->
                            FilterChip(
                                selected = state.strikeWindow == win,
                                onClick = { onSelectStrikeWindow(win) },
                                label = { Text(win.label, style = SchwabQuantTheme.numericTypography.badgeMono) }
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Spread",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Switch(
                            checked = state.spreadBuilderEnabled,
                            onCheckedChange = onToggleSpreadBuilder
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChainColumnPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = state.columnPreset == preset,
                            onClick = { onSelectColumnPreset(preset) },
                            label = { Text(preset.label) }
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = if (state.spreadBuilderEnabled) "Tap Bid/Ask to build" else "Tap Ask=Buy / Bid=Sell",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(color = SchwabQuantTheme.colors.borderSubtle)

        // 2. Collapsible Expiration Accordions
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val board = state.chainBoard
            if (state.isLoadingChain && board == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (board == null || board.sections.isEmpty()) {
                Text(
                    text = "No option contracts available for ${state.symbol}.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    board.sections.forEach { section ->
                        val expanded = section.expirationDate in state.expandedExpirations
                        item(key = "hdr_${section.expirationDate}") {
                            ExpirationAccordionHeader(
                                section = section,
                                expanded = expanded,
                                onToggle = { onToggleExpiration(section.expirationDate) }
                            )
                        }
                        if (expanded) {
                            item(key = "cols_${section.expirationDate}") {
                                ChainColumnSubheader(preset = state.columnPreset)
                            }
                            items(
                                items = section.rows,
                                key = { "${section.expirationDate}_${it.strike}" }
                            ) { row ->
                                OptionChainStrikeRowView(
                                    row = row,
                                    spot = board.underlyingSpot,
                                    preset = state.columnPreset,
                                    selectedBySymbol = selectedBySymbol,
                                    onQuoteTapped = onQuoteTapped
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Sticky Bottom Spread Builder Dock
        if (state.spreadBuilderEnabled && state.selectedSpreadLegs.isNotEmpty()) {
            SpreadBuilderDock(
                state = state,
                onClear = onClearSpread,
                onStage = onStageSpread
            )
        }
    }
}

@Composable
private fun ExpirationAccordionHeader(
    section: ExpirationChainSection,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val colors = SchwabQuantTheme.colors
    Surface(
        color = colors.surfaceElevated,
        border = BorderStroke(0.5.dp, colors.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "${section.formattedDate} (${section.dte}D)",
                    style = SchwabQuantTheme.numericTypography.metricValue,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = section.cycleTag.badge,
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "IV ${fmt1(section.atmIvPct)}% • ±$${fmt2(section.expectedMoveDollars)} (${fmt1(section.expectedMovePct)}%)",
                style = SchwabQuantTheme.numericTypography.greekValue,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand"
            )
        }
    }
}

@Composable
private fun ChainColumnSubheader(preset: ChainColumnPreset) {
    val labelText = when (preset) {
        ChainColumnPreset.EXECUTION -> "Bid / Ask  (Mk • OI)"
        ChainColumnPreset.GREEKS -> "Δ / Θ  (IV • Γ)"
        ChainColumnPreset.INCOME_PMCC -> "Bid / Ask  (Δ • Ext)"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "CALLS  •  $labelText",
            style = SchwabQuantTheme.numericTypography.badgeMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "STRIKE",
            style = SchwabQuantTheme.numericTypography.badgeMono,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = "$labelText  •  PUTS",
            style = SchwabQuantTheme.numericTypography.badgeMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OptionChainStrikeRowView(
    row: OptionStrikeRow,
    spot: Double,
    preset: ChainColumnPreset,
    selectedBySymbol: Map<String, SelectedChainLeg>,
    onQuoteTapped: (ParsedOptionContract, OrderSide) -> Unit
) {
    val colors = SchwabQuantTheme.colors
    val itmShade = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)

    Column(modifier = Modifier.fillMaxWidth()) {
        if (row.isAtmBoundary && spot > 0.0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "SPOT $${fmt2(spot)}",
                    style = SchwabQuantTheme.numericTypography.badgeMono,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OptionHalfCell(
                contract = row.call,
                isItm = row.isCallItm,
                itmShade = itmShade,
                preset = preset,
                selected = row.call?.let { selectedBySymbol[it.symbol] },
                isPutSide = false,
                onQuoteTapped = onQuoteTapped,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = colors.surfaceElevated,
                modifier = Modifier.width(64.dp)
            ) {
                Text(
                    text = fmtStrike(row.strike),
                    style = SchwabQuantTheme.numericTypography.greekValue,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            OptionHalfCell(
                contract = row.put,
                isItm = row.isPutItm,
                itmShade = itmShade,
                preset = preset,
                selected = row.put?.let { selectedBySymbol[it.symbol] },
                isPutSide = true,
                onQuoteTapped = onQuoteTapped,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.45f))
    }
}

@Composable
private fun OptionHalfCell(
    contract: ParsedOptionContract?,
    isItm: Boolean,
    itmShade: Color,
    preset: ChainColumnPreset,
    selected: SelectedChainLeg?,
    isPutSide: Boolean,
    onQuoteTapped: (ParsedOptionContract, OrderSide) -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (isItm) itmShade else Color.Transparent
    if (contract == null) {
        Box(
            modifier = modifier
                .background(bg)
                .height(36.dp)
        )
        return
    }

    val colors = SchwabQuantTheme.colors
    val subLine = when (preset) {
        ChainColumnPreset.EXECUTION -> "Mk ${fmt2(contract.mid)} • OI ${contract.openInterest}"
        ChainColumnPreset.GREEKS -> "Δ ${fmt2(contract.delta)} • Θ ${fmt2(contract.thetaPerDay)} • IV ${fmt0(contract.iv * 100)}%"
        ChainColumnPreset.INCOME_PMCC -> "Δ ${fmt2(contract.delta)} • Ext $${fmt2(contract.extrinsicValue)} • ${fmt0(abs(contract.delta) * 100)}%"
    }

    val badgeText = when (selected?.side) {
        OrderSide.BUY_TO_OPEN -> "B"
        OrderSide.SELL_TO_OPEN -> "S"
        else -> null
    }
    val badgeColor = when (selected?.side) {
        OrderSide.BUY_TO_OPEN -> colors.profit
        OrderSide.SELL_TO_OPEN -> colors.loss
        else -> Color.Transparent
    }

    Column(
        modifier = modifier
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = if (isPutSide) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (badgeText != null) {
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = badgeColor.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, badgeColor)
                ) {
                    Text(
                        text = badgeText,
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = colors.loss.copy(alpha = 0.10f),
                modifier = Modifier.clickable { onQuoteTapped(contract, OrderSide.SELL_TO_OPEN) }
            ) {
                Text(
                    text = fmt2(contract.bid),
                    style = SchwabQuantTheme.numericTypography.greekValue,
                    color = colors.loss,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = colors.profit.copy(alpha = 0.10f),
                modifier = Modifier.clickable { onQuoteTapped(contract, OrderSide.BUY_TO_OPEN) }
            ) {
                Text(
                    text = fmt2(contract.ask),
                    style = SchwabQuantTheme.numericTypography.greekValue,
                    color = colors.profit,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            text = subLine,
            style = SchwabQuantTheme.numericTypography.badgeMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun SpreadBuilderDock(
    state: MarketDetailUiState,
    onClear: () -> Unit,
    onStage: () -> Unit
) {
    val preview = state.assembledSpreadPreview
    Surface(
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preview?.strategyLabel
                            ?: "${state.selectedSpreadLegs.size} legs selected (adjust legs to form valid spread)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (preview != null) {
                        val flowTag = if (preview.isNetCredit) "Net CR" else "Net DB"
                        Text(
                            text = "$flowTag \$${preview.limitPrice.toPlainString()} • BPR $${fmt0(preview.estimatedBpr)} • Δ ${fmt2(preview.netDelta)} • Θ $${fmt2(preview.netThetaPerDay)}/d",
                            style = SchwabQuantTheme.numericTypography.greekValue,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(0.4f)
                ) {
                    Text("Clear")
                }
                Button(
                    onClick = onStage,
                    enabled = preview != null,
                    modifier = Modifier.weight(0.6f)
                ) {
                    Text("Stage Spread (${state.selectedSpreadLegs.size})")
                }
            }
        }
    }
}

private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
private fun fmtStrike(v: Double): String =
    if (abs(v - v.toLong()) < 0.01) v.toLong().toString() else fmt2(v)
