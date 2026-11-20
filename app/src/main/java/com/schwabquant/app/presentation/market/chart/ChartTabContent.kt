package com.schwabquant.app.presentation.market.chart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.schwabquant.app.core.quant.ChartTimeframe
import com.schwabquant.app.presentation.market.MarketDetailUiState
import com.schwabquant.app.presentation.theme.LocalAppThemeType
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.util.Locale

@Composable
fun ChartTabContent(
    state: MarketDetailUiState,
    onSelectTimeframe: (ChartTimeframe) -> Unit,
    onToggleEma9: () -> Unit,
    onToggleEma21: () -> Unit,
    onToggleSma50: () -> Unit,
    onToggleSma200: () -> Unit,
    onToggleRma: () -> Unit,
    onToggleVwap: () -> Unit,
    onCycleRsiMode: () -> Unit,
    onClearStrategyOverlay: () -> Unit,
    onCrosshairTelemetry: (CrosshairTelemetry) -> Unit,
    modifier: Modifier = Modifier
) {
    val themeType = LocalAppThemeType.current
    val themePayload = remember(themeType) { ChartThemeMapper.fromAppTheme(themeType) }
    val chartDataPayload = remember(
        state.chartResetCounter,
        state.overlayToggles,
        themePayload
    ) {
        if (state.candles.isEmpty()) {
            null
        } else {
            ChartThemeMapper.buildChartPayload(
                candles = state.candles,
                indicators = state.indicators,
                toggles = state.overlayToggles,
                theme = themePayload,
                fitContent = true
            )
        }
    }

    val liveBarUpdate = remember(state.latestLiveBar, themePayload) {
        state.latestLiveBar?.let { bar -> ChartThemeMapper.buildLiveBarUpdate(bar, themePayload) }
    }

    val combinedPriceLines = remember(state.baseOverlayPriceLines, state.previewStrategyLines, themePayload) {
        val strategyLines = state.previewStrategyLines.map { line ->
            JsPriceLine(
                price = line.price,
                color = if (line.isBreakeven) "#FFB300" else "#A78BFA",
                lineWidth = if (line.isBreakeven) 2 else 1,
                dashed = true,
                title = line.label
            )
        }
        state.baseOverlayPriceLines + strategyLines
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Crosshair OHLCV Telemetry Header
        CrosshairTelemetryStrip(state = state)

        // 2. Timeframe Chips (1m..1W) + Streamer Live/Simulated Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(end = 6.dp)
            ) {
                items(ChartTimeframe.entries, key = { it.name }) { tf ->
                    FilterChip(
                        selected = state.timeframe == tf,
                        onClick = { onSelectTimeframe(tf) },
                        label = { Text(tf.label, style = SchwabQuantTheme.numericTypography.badgeMono) }
                    )
                }
            }
            val feedText = when {
                state.isChartStreamingLive -> "WS LIVE"
                state.isLiveMarketData -> "REST"
                else -> "SIM"
            }
            val feedColor = if (state.isLiveMarketData) SchwabQuantTheme.colors.profit else SchwabQuantTheme.colors.warning
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = feedColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, feedColor)
            ) {
                Text(
                    text = feedText,
                    style = SchwabQuantTheme.numericTypography.badgeMono,
                    color = feedColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        // 3. Technical Indicator Toggles + Clear Strategy Overlay chip
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (state.previewStrategyLines.isNotEmpty()) {
                item(key = "clear_strategy_overlay") {
                    InputChip(
                        selected = true,
                        onClick = onClearStrategyOverlay,
                        label = { Text("Clear Strategy Overlay") },
                        trailingIcon = {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = "Clear overlay")
                        }
                    )
                }
            }
            item {
                FilterChip(
                    selected = state.overlayToggles.ema9,
                    onClick = onToggleEma9,
                    label = { Text("EMA9") }
                )
            }
            item {
                FilterChip(
                    selected = state.overlayToggles.ema21,
                    onClick = onToggleEma21,
                    label = { Text("EMA21") }
                )
            }
            item {
                FilterChip(
                    selected = state.overlayToggles.sma50,
                    onClick = onToggleSma50,
                    label = { Text("SMA50") }
                )
            }
            item {
                FilterChip(
                    selected = state.overlayToggles.sma200,
                    onClick = onToggleSma200,
                    label = { Text("SMA200") }
                )
            }
            item {
                FilterChip(
                    selected = state.overlayToggles.rma,
                    onClick = onToggleRma,
                    label = { Text("RMA") }
                )
            }
            item {
                FilterChip(
                    selected = state.overlayToggles.vwap,
                    onClick = onToggleVwap,
                    label = { Text("VWAP") }
                )
            }
            item {
                FilterChip(
                    selected = state.overlayToggles.rsiMode != RsiPaneMode.OFF,
                    onClick = onCycleRsiMode,
                    label = { Text(state.overlayToggles.rsiMode.label) }
                )
            }
        }

        HorizontalDivider(color = SchwabQuantTheme.colors.borderSubtle)

        // 4. Offline TradingView Lightweight Charts Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LightweightChartWebView(
                themePayload = themePayload,
                chartDataPayload = chartDataPayload,
                priceLines = combinedPriceLines,
                liveBarUpdate = liveBarUpdate,
                onCrosshairTelemetry = onCrosshairTelemetry,
                modifier = Modifier.fillMaxSize()
            )
            if (state.isLoadingChart) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun CrosshairTelemetryStrip(state: MarketDetailUiState) {
    val active = state.crosshair.active
    val latest = state.candles.lastOrNull()
    val o = if (active) state.crosshair.open else latest?.open
    val h = if (active) state.crosshair.high else latest?.high
    val l = if (active) state.crosshair.low else latest?.low
    val c = if (active) state.crosshair.close else latest?.close
    val v = if (active) state.crosshair.volume else latest?.volume

    Surface(
        color = SchwabQuantTheme.colors.surfaceElevated,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (o != null && h != null && l != null && c != null) {
                        "O ${fmt2(o)}  H ${fmt2(h)}  L ${fmt2(l)}  C ${fmt2(c)}"
                    } else {
                        "Touch & hold chart for OHLCV crosshair"
                    },
                    style = SchwabQuantTheme.numericTypography.greekValue,
                    fontWeight = FontWeight.SemiBold
                )
                if (v != null) {
                    Text(
                        text = "Vol ${fmtVol(v)}",
                        style = SchwabQuantTheme.numericTypography.greekValue,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (active) {
                val indBits = buildList {
                    state.crosshair.ema9?.let { add("E9 ${fmt2(it)}") }
                    state.crosshair.ema21?.let { add("E21 ${fmt2(it)}") }
                    state.crosshair.vwap?.let { add("VWAP ${fmt2(it)}") }
                    state.crosshair.rsi?.let { add("RSI ${fmt1(it)}") }
                }
                if (indBits.isNotEmpty()) {
                    Text(
                        text = indBits.joinToString(" • "),
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (!state.previewStrategyLabel.isNullOrBlank()) {
                Text(
                    text = "Overlay: ${state.previewStrategyLabel}",
                    style = SchwabQuantTheme.numericTypography.badgeMono,
                    color = SchwabQuantTheme.colors.warning
                )
            }
        }
    }
}

private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
private fun fmtVol(v: Long): String = when {
    v >= 1_000_000L -> String.format(Locale.US, "%.2fM", v / 1_000_000.0)
    v >= 1_000L -> String.format(Locale.US, "%.1fK", v / 1_000.0)
    else -> v.toString()
}
