package com.schwabquant.app.presentation.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schwabquant.app.core.handoff.GeminiHandoffManager
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.StoredAuthSnapshot
import com.schwabquant.app.domain.model.BearChecklistEvaluation
import com.schwabquant.app.domain.model.CapitalFilterTier
import com.schwabquant.app.domain.model.RegimeStateHolder
import com.schwabquant.app.domain.model.ScanProgress
import com.schwabquant.app.domain.model.StagedOrderHolder
import com.schwabquant.app.domain.model.StrategyCandidate
import com.schwabquant.app.domain.model.StrategyFilterChip
import com.schwabquant.app.domain.model.Watchlist
import com.schwabquant.app.domain.repository.MarketDataRepository
import com.schwabquant.app.domain.usecase.EvaluateBearChecklistUseCase
import com.schwabquant.app.domain.usecase.ScanOptionsStrategiesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScannerUiState(
    val watchlists: List<Watchlist> = emptyList(),
    val selectedWatchlist: Watchlist? = null,
    val selectedCapitalTier: CapitalFilterTier = CapitalFilterTier.ALL,
    val selectedStrategyFilters: Set<StrategyFilterChip> = setOf(StrategyFilterChip.ALL),
    val candidates: List<StrategyCandidate> = emptyList(),
    val totalUnfilteredCount: Int = 0,
    val selectedCandidate: StrategyCandidate? = null,
    val activeRegime: BearChecklistEvaluation? = null,
    val hasLiveAuth: Boolean = false,
    val useDemoChains: Boolean = true,
    val isScanning: Boolean = false,
    val scanProgress: ScanProgress? = null,
    val snackbarMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val marketDataRepository: MarketDataRepository,
    private val scanOptionsStrategiesUseCase: ScanOptionsStrategiesUseCase,
    private val evaluateBearChecklistUseCase: EvaluateBearChecklistUseCase,
    private val regimeStateHolder: RegimeStateHolder,
    private val stagedOrderHolder: StagedOrderHolder,
    private val credentialVault: CredentialVault,
    private val geminiHandoffManager: GeminiHandoffManager
) : ViewModel() {

    private val selectedWatchlistIdState = MutableStateFlow<Long?>(null)
    private val selectedCapitalTierState = MutableStateFlow(CapitalFilterTier.ALL)
    private val selectedStrategyFiltersState = MutableStateFlow(setOf(StrategyFilterChip.ALL))
    private val rawCandidatesState = MutableStateFlow<List<StrategyCandidate>>(emptyList())
    private val selectedCandidateState = MutableStateFlow<StrategyCandidate?>(null)
    private val explicitDemoOverrideState = MutableStateFlow<Boolean?>(null)
    private val isScanningState = MutableStateFlow(false)
    private val scanProgressState = MutableStateFlow<ScanProgress?>(null)
    private val snackbarState = MutableStateFlow<String?>(null)
    private val errorState = MutableStateFlow<String?>(null)

    private var scanJob: Job? = null

    val uiState: StateFlow<ScannerUiState> = combine(
        marketDataRepository.observeWatchlists(),
        selectedWatchlistIdState,
        selectedCapitalTierState,
        selectedStrategyFiltersState,
        rawCandidatesState,
        selectedCandidateState,
        regimeStateHolder.currentEvaluation,
        credentialVault.authSnapshotFlow,
        explicitDemoOverrideState,
        isScanningState,
        scanProgressState,
        snackbarState,
        errorState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val watchlists = values[0] as List<Watchlist>
        val selectedId = values[1] as Long?
        val capitalTier = values[2] as CapitalFilterTier
        @Suppress("UNCHECKED_CAST")
        val strategyFilters = values[3] as Set<StrategyFilterChip>
        @Suppress("UNCHECKED_CAST")
        val rawCandidates = values[4] as List<StrategyCandidate>
        val selectedCandidate = values[5] as StrategyCandidate?
        val activeRegime = values[6] as BearChecklistEvaluation?
        val auth = values[7] as StoredAuthSnapshot
        val demoOverride = values[8] as Boolean?
        val scanning = values[9] as Boolean
        val progress = values[10] as ScanProgress?
        val snackbar = values[11] as String?
        val err = values[12] as String?

        val selectedWatchlist = watchlists.firstOrNull { it.id == selectedId }
            ?: watchlists.firstOrNull { it.isDefault }
            ?: watchlists.firstOrNull()

        val useDemo = demoOverride ?: !auth.hasAccessToken

        val maxCapital = capitalTier.maxCapitalDollars
        val filtered = rawCandidates.filter { candidate ->
            val capOk = maxCapital == null || candidate.metrics.buyingPowerRequirement <= maxCapital
            val stratOk = StrategyFilterChip.ALL in strategyFilters ||
                candidate.strategyType.filterChip in strategyFilters
            capOk && stratOk
        }

        ScannerUiState(
            watchlists = watchlists,
            selectedWatchlist = selectedWatchlist,
            selectedCapitalTier = capitalTier,
            selectedStrategyFilters = strategyFilters,
            candidates = filtered,
            totalUnfilteredCount = rawCandidates.size,
            selectedCandidate = selectedCandidate,
            activeRegime = activeRegime,
            hasLiveAuth = auth.hasAccessToken,
            useDemoChains = useDemo,
            isScanning = scanning,
            scanProgress = progress,
            snackbarMessage = snackbar,
            errorMessage = err
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScannerUiState()
    )

    init {
        viewModelScope.launch {
            marketDataRepository.ensureDefaultWatchlistsSeeded()
            if (regimeStateHolder.currentEvaluation.value == null && !evaluateBearChecklistUseCase.hasLiveAuth()) {
                evaluateBearChecklistUseCase.evaluateDemo()
            }
        }
    }

    fun selectWatchlist(watchlistId: Long) {
        selectedWatchlistIdState.value = watchlistId
    }

    fun selectCapitalTier(tier: CapitalFilterTier) {
        selectedCapitalTierState.value = tier
    }

    fun toggleStrategyFilter(chip: StrategyFilterChip) {
        if (chip == StrategyFilterChip.ALL) {
            selectedStrategyFiltersState.value = setOf(StrategyFilterChip.ALL)
            return
        }
        val current = selectedStrategyFiltersState.value.toMutableSet()
        current.remove(StrategyFilterChip.ALL)
        if (!current.add(chip)) {
            current.remove(chip)
        }
        selectedStrategyFiltersState.value = if (current.isEmpty()) {
            setOf(StrategyFilterChip.ALL)
        } else {
            current
        }
    }

    fun setUseDemoChains(useDemo: Boolean) {
        explicitDemoOverrideState.value = useDemo
    }

    fun runScan(forceRefresh: Boolean = false) {
        val currentState = uiState.value
        val tickers = currentState.selectedWatchlist?.tickers.orEmpty()
        if (tickers.isEmpty()) {
            snackbarState.value = "Selected watchlist has no tickers. Add a ticker first."
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            isScanningState.value = true
            errorState.value = null
            scanProgressState.value = ScanProgress(completed = 0, total = tickers.size, currentTicker = tickers.first())

            val regimeEval = regimeStateHolder.currentEvaluation.value
                ?: evaluateBearChecklistUseCase.evaluateDemo()

            try {
                // Scan all 6 strategies + unlimited capital on cached chains so UI filter chips
                // re-slice candidates instantaneously without re-hitting Schwab.
                val scanned = scanOptionsStrategiesUseCase.scanWatchlist(
                    tickers = tickers,
                    regimeEvaluation = regimeEval,
                    maxCapitalDollars = null,
                    selectedFilters = setOf(StrategyFilterChip.ALL),
                    useDemoData = currentState.useDemoChains,
                    forceRefresh = forceRefresh,
                    onProgress = { progress ->
                        scanProgressState.value = progress
                    }
                )
                rawCandidatesState.value = scanned
                if (scanned.isEmpty()) {
                    snackbarState.value = "Scan completed: no liquid strategy candidates found."
                }
            } catch (_: CancellationException) {
                snackbarState.value = "Scan cancelled."
            } catch (t: Throwable) {
                errorState.value = t.message ?: "Option chain scan failed."
            } finally {
                isScanningState.value = false
                scanProgressState.value = null
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        isScanningState.value = false
        scanProgressState.value = null
    }

    fun createWatchlist(name: String, initialTickersRaw: String) {
        viewModelScope.launch {
            val tickers = initialTickersRaw
                .split(",", " ", "\n")
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
            marketDataRepository.createWatchlist(name, tickers)
                .onSuccess { newId ->
                    selectedWatchlistIdState.value = newId
                    snackbarState.value = "Created watchlist '$name'."
                }
                .onFailure { err ->
                    snackbarState.value = err.message ?: "Could not create watchlist."
                }
        }
    }

    fun renameWatchlist(watchlistId: Long, newName: String) {
        viewModelScope.launch {
            marketDataRepository.renameWatchlist(watchlistId, newName)
                .onSuccess { snackbarState.value = "Renamed watchlist to '$newName'." }
                .onFailure { err -> snackbarState.value = err.message ?: "Rename failed." }
        }
    }

    fun deleteWatchlist(watchlistId: Long) {
        viewModelScope.launch {
            marketDataRepository.deleteWatchlist(watchlistId)
                .onSuccess {
                    if (selectedWatchlistIdState.value == watchlistId) {
                        selectedWatchlistIdState.value = null
                    }
                    snackbarState.value = "Deleted watchlist."
                }
                .onFailure { err -> snackbarState.value = err.message ?: "Delete failed." }
        }
    }

    fun addTickerToWatchlist(watchlistId: Long, ticker: String) {
        viewModelScope.launch {
            marketDataRepository.addTickerToWatchlist(watchlistId, ticker)
                .onSuccess { snackbarState.value = "Added ${ticker.trim().uppercase()}." }
                .onFailure { err -> snackbarState.value = err.message ?: "Could not add ticker." }
        }
    }

    fun removeTickerFromWatchlist(watchlistId: Long, ticker: String) {
        viewModelScope.launch {
            marketDataRepository.removeTickerFromWatchlist(watchlistId, ticker)
                .onFailure { err -> snackbarState.value = err.message ?: "Could not remove ticker." }
        }
    }

    fun selectCandidate(candidate: StrategyCandidate?) {
        selectedCandidateState.value = candidate
    }

    fun handoffCandidateToGemini(candidate: StrategyCandidate) {
        val result = geminiHandoffManager.handoffPrompt(
            prompt = candidate.explainer.geminiMarkdownPacket,
            subject = "Schwab Quant — ${candidate.underlyingSymbol} ${candidate.strategyType.shortLabel}"
        )
        snackbarState.value = result.message
    }

    fun stageOrder(candidate: StrategyCandidate) {
        stagedOrderHolder.stage(candidate)
        selectedCandidateState.value = null
    }

    fun consumeSnackbar() {
        snackbarState.value = null
    }
}
