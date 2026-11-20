package com.schwabquant.app.presentation.market

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.domain.model.LiquidUniverse
import com.schwabquant.app.domain.model.Watchlist
import com.schwabquant.app.domain.repository.MarketDataRepository
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TickerPickerViewModel @Inject constructor(
    marketDataRepository: MarketDataRepository
) : ViewModel() {
    val watchlists: StateFlow<List<Watchlist>> = marketDataRepository.observeWatchlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TickerPickerSheet(
    onDismiss: () -> Unit,
    onSelectTicker: (String) -> Unit,
    viewModel: TickerPickerViewModel = hiltViewModel()
) {
    val watchlists by viewModel.watchlists.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = SchwabSymbolNormalizer.toDisplaySymbol(query)
    val submitQuery = {
        val candidate = normalizedQuery.takeIf { it.isNotBlank() && VALID_TICKER_INPUT.matches(it) && !it.contains("..") }
        if (candidate != null) {
            onSelectTicker(candidate)
            onDismiss()
        }
    }

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
                    text = "Open Interactive Chart & Chain",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Ticker (e.g. SPY, NVDA, BRK.B)") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(onSearch = { submitQuery() }),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = submitQuery,
                        enabled = normalizedQuery.isNotBlank() &&
                            VALID_TICKER_INPUT.matches(normalizedQuery) &&
                            !normalizedQuery.contains("..")
                    ) {
                        Text("Open")
                    }
                }
            }

            if (watchlists.isNotEmpty()) {
                watchlists.forEach { wl ->
                    val filtered = if (normalizedQuery.isBlank()) {
                        wl.tickers
                    } else {
                        wl.tickers.filter { it.contains(normalizedQuery, ignoreCase = true) }
                    }
                    if (filtered.isNotEmpty()) {
                        item(key = "wl_${wl.id}") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = wl.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    filtered.forEach { sym ->
                                        TickerQuickChip(symbol = sym) {
                                            onSelectTicker(sym)
                                            onDismiss()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item(key = "core25_fallback") {
                    val filtered = if (normalizedQuery.isBlank()) {
                        LiquidUniverse.CORE_25_LIQUID_TICKERS
                    } else {
                        LiquidUniverse.CORE_25_LIQUID_TICKERS.filter {
                            it.contains(normalizedQuery, ignoreCase = true)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = LiquidUniverse.CORE_25_NAME,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            filtered.forEach { sym ->
                                TickerQuickChip(symbol = sym) {
                                    onSelectTicker(sym)
                                    onDismiss()
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.padding(top = 2.dp))
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

@Composable
private fun TickerQuickChip(
    symbol: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = SchwabQuantTheme.colors.surfaceElevated,
        border = BorderStroke(1.dp, SchwabQuantTheme.colors.borderSubtle),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = symbol,
            style = SchwabQuantTheme.numericTypography.badgeMono,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

private val VALID_TICKER_INPUT = Regex("""^[A-Z0-9](?:[A-Z0-9.\-]{0,14}[A-Z0-9])?$""")
