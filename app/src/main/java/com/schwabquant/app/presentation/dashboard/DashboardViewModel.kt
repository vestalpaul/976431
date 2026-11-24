package com.schwabquant.app.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schwabquant.app.core.handoff.GeminiHandoffManager
import com.schwabquant.app.core.quant.BetaWeightingCalculator
import com.schwabquant.app.core.quant.PortfolioStressCalculator
import com.schwabquant.app.core.quant.StressIvMode
import com.schwabquant.app.core.quant.StressMatrixSummary
import com.schwabquant.app.core.quant.StressScenarioResult
import com.schwabquant.app.data.local.entity.AuditLogEntity
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.domain.model.BearChecklistEvaluation
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.PmccValidationReport
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.PortfolioSnapshot
import com.schwabquant.app.domain.model.RegimeStateHolder
import com.schwabquant.app.domain.model.StagedOrderHolder
import com.schwabquant.app.domain.model.WorkingOrder
import com.schwabquant.app.domain.repository.OrderRepository
import com.schwabquant.app.domain.repository.PortfolioRepository
import com.schwabquant.app.domain.usecase.EvaluateExDivRadarUseCase
import com.schwabquant.app.domain.usecase.ExDivRadarAlert
import com.schwabquant.app.domain.usecase.GeneratePortfolioAdviceUseCase
import com.schwabquant.app.domain.usecase.PortfolioAdviceCard
import com.schwabquant.app.domain.usecase.TrackWashSalesUseCase
import com.schwabquant.app.domain.usecase.ValidatePmccStructureUseCase
import com.schwabquant.app.domain.usecase.WashSaleFlag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val executionMode: ExecutionMode = ExecutionMode.SHADOW,
    val selectedSource: PortfolioDataSource = PortfolioDataSource.SHADOW_PAPER_LEDGER,
    val snapshot: PortfolioSnapshot? = null,
    val stressMatrix: List<StressScenarioResult> = emptyList(),
    val stressSummary: StressMatrixSummary? = null,
    val stressIvMode: StressIvMode = StressIvMode.TERM_STRUCTURE_SQRT,
    val pmccReport: PmccValidationReport? = null,
    val includeVolSkewShock: Boolean = true,
    val washSaleFlags: List<WashSaleFlag> = emptyList(),
    val exDivAlerts: List<ExDivRadarAlert> = emptyList(),
    val adviceCards: List<PortfolioAdviceCard> = emptyList(),
    val workingOrders: List<WorkingOrder> = emptyList(),
    val auditLogs: List<AuditLogEntity> = emptyList(),
    val expandedGroups: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val snackbarMessage: String? = null,
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val preferences: AppPreferencesDataStore,
    private val portfolioRepository: PortfolioRepository,
    private val orderRepository: OrderRepository,
    private val betaWeightingCalculator: BetaWeightingCalculator,
    private val portfolioStressCalculator: PortfolioStressCalculator,
    private val validatePmccStructureUseCase: ValidatePmccStructureUseCase,
    private val trackWashSalesUseCase: TrackWashSalesUseCase,
    private val evaluateExDivRadarUseCase: EvaluateExDivRadarUseCase,
    private val generatePortfolioAdviceUseCase: GeneratePortfolioAdviceUseCase,
    private val geminiHandoffManager: GeminiHandoffManager,
    private val regimeStateHolder: RegimeStateHolder,
    private val stagedOrderHolder: StagedOrderHolder
) : ViewModel() {

    private val includeVolSkewShockState = MutableStateFlow(true)
    private val stressIvModeState = MutableStateFlow(StressIvMode.TERM_STRUCTURE_SQRT)
    private val expandedGroupsState = MutableStateFlow<Set<String>>(emptySet())
    private val isRefreshingState = MutableStateFlow(false)
    private val snackbarState = MutableStateFlow<String?>(null)
    private val errorState = MutableStateFlow<String?>(null)

    private val snapshotFlow = preferences.selectedPortfolioSource.flatMapLatest { source ->
        portfolioRepository.observePortfolioSnapshot(source)
    }

    private data class CoreTelemetryTuple(
        val mode: ExecutionMode,
        val source: PortfolioDataSource,
        val snapshot: PortfolioSnapshot?,
        val washFlags: List<WashSaleFlag>,
        val regime: BearChecklistEvaluation?
    )

    private data class OrderUiTuple(
        val orders: List<WorkingOrder>,
        val logs: List<AuditLogEntity>,
        val includeVolSkew: Boolean,
        val stressIvMode: StressIvMode,
        val expanded: Set<String>
    )

    private data class ChromeTuple(
        val refreshing: Boolean,
        val snackbar: String?,
        val error: String?
    )

    private val coreFlow = combine(
        preferences.executionMode,
        preferences.selectedPortfolioSource,
        snapshotFlow,
        trackWashSalesUseCase.observeWashSaleFlags(),
        regimeStateHolder.currentEvaluation
    ) { mode, source, snapshot, washFlags, regime ->
        CoreTelemetryTuple(mode, source, snapshot, washFlags, regime)
    }

    private val orderAndUiFlow = combine(
        orderRepository.observeWorkingOrders(),
        orderRepository.observeRecentAuditLogs(25),
        includeVolSkewShockState,
        stressIvModeState,
        expandedGroupsState
    ) { orders, logs, volSkew, ivMode, expanded ->
        OrderUiTuple(orders, logs, volSkew, ivMode, expanded)
    }

    private val chromeFlow = combine(
        isRefreshingState,
        snackbarState,
        errorState
    ) { refreshing, snackbar, err ->
        ChromeTuple(refreshing, snackbar, err)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        coreFlow,
        orderAndUiFlow,
        chromeFlow
    ) { core, orderUi, chrome ->
        val snapshot = core.snapshot
        val stressSummary = if (snapshot != null && snapshot.positions.isNotEmpty()) {
            portfolioStressCalculator.computeStressSummary(
                positions = snapshot.positions,
                accountNetLiq = snapshot.accountSummary.netLiquidationValue.toDouble(),
                spySpot = snapshot.spySpot,
                spyBetaDelta = snapshot.aggregatedGreeks.spyBetaWeightedDelta,
                includeVolSkewShock = orderUi.includeVolSkew,
                ivMode = orderUi.stressIvMode
            )
        } else {
            null
        }
        val stressMatrix = stressSummary?.scenarios.orEmpty()
        val pmccReport = if (snapshot != null && snapshot.positions.isNotEmpty()) {
            validatePmccStructureUseCase.validatePortfolio(snapshot.positions)
        } else {
            null
        }

        val exDivAlerts = if (snapshot != null) {
            evaluateExDivRadarUseCase(snapshot.positions)
        } else {
            emptyList()
        }

        val adviceCards = generatePortfolioAdviceUseCase.generateAdviceCards(
            snapshot = snapshot,
            exDivAlerts = exDivAlerts,
            regime = core.regime
        )

        DashboardUiState(
            executionMode = core.mode,
            selectedSource = core.source,
            snapshot = snapshot,
            stressMatrix = stressMatrix,
            stressSummary = stressSummary,
            stressIvMode = orderUi.stressIvMode,
            pmccReport = pmccReport,
            includeVolSkewShock = orderUi.includeVolSkew,
            washSaleFlags = core.washFlags,
            exDivAlerts = exDivAlerts,
            adviceCards = adviceCards,
            workingOrders = orderUi.orders,
            auditLogs = orderUi.logs,
            expandedGroups = orderUi.expanded,
            isRefreshing = chrome.refreshing,
            snackbarMessage = chrome.snackbar,
            errorMessage = chrome.error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState()
    )

    fun selectDataSource(source: PortfolioDataSource) {
        viewModelScope.launch {
            errorState.value = null
            preferences.setSelectedPortfolioSource(source)
            refresh(source)
        }
    }

    fun refresh(explicitSource: PortfolioDataSource = uiState.value.selectedSource) {
        viewModelScope.launch {
            isRefreshingState.value = true
            errorState.value = null
            val result = when (explicitSource) {
                PortfolioDataSource.LIVE_SCHWAB_READ_ONLY -> portfolioRepository.refreshLivePortfolio()
                PortfolioDataSource.SHADOW_PAPER_LEDGER -> portfolioRepository.refreshShadowPortfolio()
            }
            result.onFailure { err ->
                errorState.value = err.message ?: "Failed to refresh portfolio data."
            }
            orderRepository.getWorkingOrders(forceRefresh = true)
            isRefreshingState.value = false
        }
    }

    fun seedDemoPortfolio() {
        viewModelScope.launch {
            isRefreshingState.value = true
            errorState.value = null
            portfolioRepository.seedDemoPortfolio()
                .onSuccess {
                    portfolioRepository.refreshShadowPortfolio()
                    snackbarState.value = "Seeded demo PMCC + NVDA LEAPS + Wash-Sale portfolio."
                }
                .onFailure { err ->
                    errorState.value = err.message ?: "Failed to seed demo portfolio."
                }
            isRefreshingState.value = false
        }
    }

    fun openNewOrderTicket() {
        val firstSym = uiState.value.snapshot?.groups?.firstOrNull()?.underlyingSymbol ?: "GOOG"
        val spot = uiState.value.snapshot?.groups?.firstOrNull()?.underlyingSpot ?: 270.0
        stagedOrderHolder.stageBlankOrder(symbol = firstSym, spotPrice = spot)
    }

    fun stageAdviceOrder(draft: OrderDraft) {
        stagedOrderHolder.stageDraft(draft)
    }

    fun cancelWorkingOrder(orderId: String) {
        viewModelScope.launch {
            val targetOrder = uiState.value.workingOrders.firstOrNull { it.orderId == orderId }
            val mode = targetOrder?.executionMode ?: uiState.value.executionMode
            val hash = if (mode == ExecutionMode.LIVE) {
                uiState.value.snapshot?.accountSummary?.accountHash
                    ?.takeUnless { it.equals("SHADOW_PAPER", ignoreCase = true) || it.equals("SHADOW", ignoreCase = true) }
                    .orEmpty()
            } else {
                "SHADOW_PAPER"
            }
            orderRepository.cancelOrder(hash, orderId, mode)
                .onSuccess { snackbarState.value = "Canceled order $orderId." }
                .onFailure { err -> errorState.value = err.message ?: "Cancel failed." }
        }
    }

    fun simulateShadowFill(orderId: String) {
        viewModelScope.launch {
            orderRepository.simulateShadowFill(orderId)
                .onSuccess { res ->
                    portfolioRepository.refreshShadowPortfolio()
                    snackbarState.value = res.message
                }
                .onFailure { err ->
                    errorState.value = err.message ?: "Simulated fill failed."
                }
        }
    }

    fun refreshWorkingOrders() {
        viewModelScope.launch {
            orderRepository.getWorkingOrders(forceRefresh = true)
                .onFailure { err -> errorState.value = err.message ?: "Failed to load working orders." }
        }
    }

    fun toggleVolSkewShock(enabled: Boolean) {
        includeVolSkewShockState.value = enabled
    }

    fun selectStressIvMode(mode: StressIvMode) {
        stressIvModeState.value = mode
    }

    fun toggleGroupExpanded(underlyingSymbol: String) {
        val current = expandedGroupsState.value.toMutableSet()
        if (!current.add(underlyingSymbol)) {
            current.remove(underlyingSymbol)
        }
        expandedGroupsState.value = current
    }

    fun logManualWashSaleTrade(
        underlyingSymbol: String,
        symbol: String,
        assetType: String,
        instruction: String,
        quantity: Int,
        price: Double,
        realizedPnl: Double?
    ) {
        viewModelScope.launch {
            runCatching {
                trackWashSalesUseCase.logManualTrade(
                    underlyingSymbol = underlyingSymbol,
                    symbol = symbol,
                    assetType = assetType,
                    instruction = instruction,
                    quantity = quantity,
                    price = price,
                    realizedPnl = realizedPnl
                )
            }.onSuccess {
                snackbarState.value = "Trade logged to 61-day Wash-Sale ledger."
            }.onFailure { err ->
                errorState.value = err.message ?: "Failed to log trade."
            }
        }
    }

    fun syncWashSalesFromSchwab() {
        viewModelScope.launch {
            isRefreshingState.value = true
            errorState.value = null
            trackWashSalesUseCase.syncFilledOrdersFromSchwab()
                .onSuccess { inserted ->
                    snackbarState.value = "Synced $inserted filled order leg(s) from Schwab (65d)."
                }
                .onFailure { err ->
                    errorState.value = err.message ?: "Schwab order sync failed."
                }
            isRefreshingState.value = false
        }
    }

    fun handoffPortfolioToGemini() {
        val state = uiState.value
        val prompt = generatePortfolioAdviceUseCase(
            snapshot = state.snapshot,
            executionMode = state.executionMode,
            stressMatrix = state.stressMatrix,
            washSaleFlags = state.washSaleFlags,
            exDivAlerts = state.exDivAlerts,
            regime = regimeStateHolder.currentEvaluation.value,
            stressSummary = state.stressSummary,
            pmccReport = state.pmccReport
        )
        val result = geminiHandoffManager.handoffPrompt(prompt)
        snackbarState.value = result.message
    }

    fun consumeSnackbar() {
        snackbarState.value = null
    }
}
