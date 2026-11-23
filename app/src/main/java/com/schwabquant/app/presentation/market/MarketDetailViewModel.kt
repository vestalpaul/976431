package com.schwabquant.app.presentation.market

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schwabquant.app.core.handoff.GeminiHandoffManager
import com.schwabquant.app.core.handoff.OptimizerDeepAnalysisFormatter
import com.schwabquant.app.core.quant.AssembledSpreadResult
import com.schwabquant.app.core.quant.ChainColumnPreset
import com.schwabquant.app.core.quant.ChartCandle
import com.schwabquant.app.core.quant.ChartIndicatorBundle
import com.schwabquant.app.core.quant.ChartIndicatorCalculator
import com.schwabquant.app.core.quant.ChartTimeframe
import com.schwabquant.app.core.quant.OptionChainParser
import com.schwabquant.app.core.quant.OptionsChainBoard
import com.schwabquant.app.core.quant.OptionsChainBoardBuilder
import com.schwabquant.app.core.quant.ParsedOptionChain
import com.schwabquant.app.core.quant.ParsedOptionContract
import com.schwabquant.app.core.quant.SelectedChainLeg
import com.schwabquant.app.core.quant.SpreadDraftAssembler
import com.schwabquant.app.core.quant.StrikeWindowFilter
import com.schwabquant.app.core.quant.VolRankCalculator
import com.schwabquant.app.core.security.DeepLinkIntentSanitizer
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.dto.SchwabQuoteNormalizer
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.data.remote.streamer.SchwabStreamerClient
import com.schwabquant.app.data.remote.streamer.StreamerConnectionState
import com.schwabquant.app.data.repository.DemoChartCandleProvider
import com.schwabquant.app.data.repository.DemoOptionChainProvider
import com.schwabquant.app.domain.model.AccountSummary
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ChartStrategyOverlayLine
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.MarketRegime
import com.schwabquant.app.domain.model.OptimizedStrategyCandidate
import com.schwabquant.app.domain.model.OptimizerCapitalPreset
import com.schwabquant.app.domain.model.OptimizerRiskProfile
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.Position
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleStatus
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.domain.model.SingleTickerOptimizationReport
import com.schwabquant.app.domain.model.StagedOrderHolder
import com.schwabquant.app.domain.model.StrategyOptimizationResult
import com.schwabquant.app.domain.model.RegimeStateHolder
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.model.WorkingOrder
import com.schwabquant.app.domain.repository.AuthRepository
import com.schwabquant.app.domain.repository.MarketDataRepository
import com.schwabquant.app.domain.repository.OAuthStatus
import com.schwabquant.app.domain.repository.OrderRepository
import com.schwabquant.app.domain.repository.PortfolioRepository
import com.schwabquant.app.domain.repository.SentinelRepository
import com.schwabquant.app.domain.usecase.AnalyzeSingleTickerStrategiesUseCase
import com.schwabquant.app.presentation.market.chart.ChartOverlayToggles
import com.schwabquant.app.presentation.market.chart.CrosshairTelemetry
import com.schwabquant.app.presentation.market.chart.JsPriceLine
import com.schwabquant.app.presentation.market.chart.RsiPaneMode
import com.schwabquant.app.presentation.navigation.NavRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

enum class MarketDetailTab(val label: String) {
    CHART("Chart"),
    OPTIONS_CHAIN("Options Chain"),
    OPTIMIZER("Optimizer (7)")
}

data class MarketDetailUiState(
    val symbol: String = "SPY",
    val activeTab: MarketDetailTab = MarketDetailTab.CHART,
    val executionMode: ExecutionMode = ExecutionMode.SHADOW,
    val isLiveMarketData: Boolean = false,
    val isChartStreamingLive: Boolean = false,
    val isLoadingChart: Boolean = true,
    val isLoadingChain: Boolean = true,
    val isComputingOptimizer: Boolean = false,
    val spotPrice: Double = 0.0,
    val dayChangeDollar: Double? = null,
    val dayChangePct: Double? = null,
    val volRank52w: Double? = null,
    val sma50: Double? = null,
    val activeRegime: MarketRegime = MarketRegime.CAUTION_ELEVATED_VOL,
    val timeframe: ChartTimeframe = ChartTimeframe.D1,
    val candles: List<ChartCandle> = emptyList(),
    val chartResetCounter: Int = 0,
    val indicators: ChartIndicatorBundle = ChartIndicatorBundle(),
    val overlayToggles: ChartOverlayToggles = ChartOverlayToggles(),
    val crosshair: CrosshairTelemetry = CrosshairTelemetry(),
    val latestLiveBar: ChartCandle? = null,
    val previewStrategyLabel: String? = null,
    val previewStrategyLines: List<ChartStrategyOverlayLine> = emptyList(),
    val baseOverlayPriceLines: List<JsPriceLine> = emptyList(),
    val strikeWindow: StrikeWindowFilter = StrikeWindowFilter.PM_12,
    val columnPreset: ChainColumnPreset = ChainColumnPreset.EXECUTION,
    val expandedExpirations: Set<LocalDate> = emptySet(),
    val chainBoard: OptionsChainBoard? = null,
    val spreadBuilderEnabled: Boolean = false,
    val selectedSpreadLegs: List<SelectedChainLeg> = emptyList(),
    val assembledSpreadPreview: AssembledSpreadResult? = null,
    val riskProfile: OptimizerRiskProfile = OptimizerRiskProfile.BALANCED,
    val capitalPreset: OptimizerCapitalPreset = OptimizerCapitalPreset.ALL,
    val resolvedCapitalBudget: Double? = null,
    val optimizationReport: SingleTickerOptimizationReport? = null,
    val suggestedStopQty: Int = 10,
    val snackbarMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MarketDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val marketDataRepository: MarketDataRepository,
    private val optionChainParser: OptionChainParser,
    private val volRankCalculator: VolRankCalculator,
    private val indicatorCalculator: ChartIndicatorCalculator,
    private val demoChartCandleProvider: DemoChartCandleProvider,
    private val demoOptionChainProvider: DemoOptionChainProvider,
    private val chainBoardBuilder: OptionsChainBoardBuilder,
    private val spreadDraftAssembler: SpreadDraftAssembler,
    private val analyzeStrategiesUseCase: AnalyzeSingleTickerStrategiesUseCase,
    private val deepAnalysisFormatter: OptimizerDeepAnalysisFormatter,
    private val geminiHandoffManager: GeminiHandoffManager,
    private val streamerClient: SchwabStreamerClient,
    private val portfolioRepository: PortfolioRepository,
    private val orderRepository: OrderRepository,
    private val sentinelRepository: SentinelRepository,
    private val authRepository: AuthRepository,
    private val regimeStateHolder: RegimeStateHolder,
    private val stagedOrderHolder: StagedOrderHolder,
    private val preferences: AppPreferencesDataStore
) : ViewModel() {

    private val initialSymbol = sanitizeTicker(
        savedStateHandle.get<String>(NavRoutes.MARKET_DETAIL_ARG_SYMBOL).orEmpty()
    )

    private val initialSeedCandles = demoChartCandleProvider.buildDemoCandles(initialSymbol, ChartTimeframe.D1)
    private val initialSeedIndicators = indicatorCalculator.computeAll(
        candles = initialSeedCandles,
        includeSessionVwap = false
    )

    private val _state = MutableStateFlow(
        MarketDetailUiState(
            symbol = initialSymbol,
            isLoadingChart = false,
            spotPrice = initialSeedCandles.lastOrNull()?.close ?: 0.0,
            candles = initialSeedCandles,
            chartResetCounter = 1,
            indicators = initialSeedIndicators
        )
    )
    val uiState: StateFlow<MarketDetailUiState> = _state

    private val activeSymbolFlow = MutableStateFlow(initialSymbol)
    private var latestParsedChain: ParsedOptionChain? = null
    private var latestDailyCloses: List<Double> = emptyList()
    private var latestAccountSummary: AccountSummary? = null
    private var chartLoadJob: Job? = null
    private var chainLoadJob: Job? = null
    private var optimizerJob: Job? = null
    private var quotePollJob: Job? = null
    private var isScreenVisible = false

    init {
        observeRuntimeOverlays()
        observeStreamerQuotes()
        observeRegimeChanges()
        loadTicker(initialSymbol, forceRefresh = false)
    }

    fun selectTab(tab: MarketDetailTab) {
        _state.value = _state.value.copy(activeTab = tab)
    }

    fun switchTicker(rawSymbol: String) {
        val clean = sanitizeTicker(rawSymbol)
        if (clean == _state.value.symbol) return
        _state.value = _state.value.copy(
            symbol = clean,
            previewStrategyLabel = null,
            previewStrategyLines = emptyList(),
            selectedSpreadLegs = emptyList(),
            assembledSpreadPreview = null,
            expandedExpirations = emptySet(),
            crosshair = CrosshairTelemetry()
        )
        activeSymbolFlow.value = clean
        updateOverlaySubscriptionState()
        loadTicker(clean, forceRefresh = false)
    }

    fun selectTimeframe(timeframe: ChartTimeframe) {
        if (_state.value.timeframe == timeframe) return
        _state.value = _state.value.copy(timeframe = timeframe, crosshair = CrosshairTelemetry())
        loadCandles(_state.value.symbol, timeframe, forceRefresh = false)
    }

    fun refreshAll() {
        loadTicker(_state.value.symbol, forceRefresh = true)
    }

    fun toggleEma9() = updateToggles { copy(ema9 = !ema9) }
    fun toggleEma21() = updateToggles { copy(ema21 = !ema21) }
    fun toggleSma50() = updateToggles { copy(sma50 = !sma50) }
    fun toggleSma200() = updateToggles { copy(sma200 = !sma200) }
    fun toggleRma() = updateToggles { copy(rma = !rma) }
    fun toggleVwap() = updateToggles { copy(vwap = !vwap) }

    fun cycleRsiMode() {
        val next = when (_state.value.overlayToggles.rsiMode) {
            RsiPaneMode.RSI_14 -> RsiPaneMode.RSI_2
            RsiPaneMode.RSI_2 -> RsiPaneMode.OFF
            RsiPaneMode.OFF -> RsiPaneMode.RSI_14
        }
        updateToggles { copy(rsiMode = next) }
    }

    fun updateCrosshair(telemetry: CrosshairTelemetry) {
        _state.value = _state.value.copy(crosshair = telemetry)
    }

    fun clearStrategyOverlay() {
        _state.value = _state.value.copy(
            previewStrategyLabel = null,
            previewStrategyLines = emptyList()
        )
    }

    fun selectStrikeWindow(window: StrikeWindowFilter) {
        val chain = latestParsedChain ?: return
        val board = chainBoardBuilder.buildBoard(chain, window)
        _state.value = _state.value.copy(strikeWindow = window, chainBoard = board)
    }

    fun selectColumnPreset(preset: ChainColumnPreset) {
        _state.value = _state.value.copy(columnPreset = preset)
    }

    fun toggleExpirationExpanded(expDate: LocalDate) {
        val current = _state.value.expandedExpirations.toMutableSet()
        if (!current.add(expDate)) current.remove(expDate)
        _state.value = _state.value.copy(expandedExpirations = current)
    }

    fun setSpreadBuilderEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(
            spreadBuilderEnabled = enabled,
            selectedSpreadLegs = if (enabled) _state.value.selectedSpreadLegs else emptyList(),
            assembledSpreadPreview = if (enabled) _state.value.assembledSpreadPreview else null
        )
    }

    fun clearSpreadSelection() {
        _state.value = _state.value.copy(
            selectedSpreadLegs = emptyList(),
            assembledSpreadPreview = null
        )
    }

    fun onChainQuoteTapped(contract: ParsedOptionContract, side: OrderSide) {
        val cur = _state.value
        if (!cur.spreadBuilderEnabled) {
            val res = spreadDraftAssembler.assemble(
                underlyingSymbol = cur.symbol,
                underlyingSpot = cur.spotPrice,
                selectedLegs = listOf(SelectedChainLeg(contract, side))
            )
            res.onSuccess { assembled ->
                stagedOrderHolder.stageDraft(assembled.draft)
            }.onFailure { err ->
                postMessage(err.message ?: "Unable to stage option order.")
            }
            return
        }

        val updated = cur.selectedSpreadLegs.toMutableList()
        val existingIdx = updated.indexOfFirst { it.contract.symbol == contract.symbol }
        if (existingIdx >= 0) {
            if (updated[existingIdx].side == side) {
                updated.removeAt(existingIdx)
            } else {
                updated[existingIdx] = SelectedChainLeg(contract, side)
            }
        } else {
            if (updated.size >= SpreadDraftAssembler.MAX_LEGS) {
                postMessage("Spread Builder supports up to ${SpreadDraftAssembler.MAX_LEGS} legs.")
                return
            }
            updated.add(SelectedChainLeg(contract, side))
        }

        val preview = if (updated.isNotEmpty()) {
            spreadDraftAssembler.assemble(cur.symbol, cur.spotPrice, updated).getOrNull()
        } else {
            null
        }
        _state.value = cur.copy(
            selectedSpreadLegs = updated,
            assembledSpreadPreview = preview
        )
    }

    fun stageAssembledSpread() {
        val cur = _state.value
        if (cur.selectedSpreadLegs.isEmpty()) {
            postMessage("Select 1 to 4 legs on the chain first.")
            return
        }
        spreadDraftAssembler.assemble(cur.symbol, cur.spotPrice, cur.selectedSpreadLegs)
            .onSuccess { assembled ->
                stagedOrderHolder.stageDraft(assembled.draft)
            }
            .onFailure { err ->
                postMessage(err.message ?: "Invalid leg combination for Schwab multi-leg order.")
            }
    }

    fun selectRiskProfile(profile: OptimizerRiskProfile) {
        if (_state.value.riskProfile == profile) return
        _state.value = _state.value.copy(riskProfile = profile)
        recomputeOptimizer()
    }

    fun selectCapitalPreset(preset: OptimizerCapitalPreset) {
        _state.value = _state.value.copy(capitalPreset = preset)
        recomputeOptimizer()
    }

    fun stageOptimizerCandidate(candidate: OptimizedStrategyCandidate) {
        stagedOrderHolder.stageDraft(candidate.toOrderDraft())
    }

    fun inspectOptimizerCandidateOnChain(candidate: OptimizedStrategyCandidate) {
        val targetExps = candidate.legs.map { it.expirationDate }.toSet()
        val chain = latestParsedChain
        val cur = _state.value
        val expanded = (cur.expandedExpirations + targetExps).toSet()

        // Expand strike window to All if any strategy strike falls outside the current slice
        val board = cur.chainBoard
        val legStrikes = candidate.legs.map { it.strike }.toSet()
        val allVisibleStrikes = board?.sections
            ?.filter { it.expirationDate in targetExps }
            ?.flatMap { it.rows.map { r -> r.strike } }
            ?.toSet()
            .orEmpty()
        val needsFullWindow = chain != null && !legStrikes.all { it in allVisibleStrikes }
        val nextWindow = if (needsFullWindow) StrikeWindowFilter.ALL else cur.strikeWindow
        val updatedBoard = if (needsFullWindow) {
            chainBoardBuilder.buildBoard(chain, nextWindow)
        } else {
            board
        }

        _state.value = cur.copy(
            activeTab = MarketDetailTab.OPTIONS_CHAIN,
            expandedExpirations = expanded,
            strikeWindow = nextWindow,
            chainBoard = updatedBoard
        )
    }

    fun previewOptimizerCandidateOnChart(candidate: OptimizedStrategyCandidate) {
        _state.value = _state.value.copy(
            activeTab = MarketDetailTab.CHART,
            previewStrategyLabel = "${candidate.strategyType.shortLabel}: ${candidate.summaryLine}",
            previewStrategyLines = candidate.toChartOverlayLines()
        )
    }

    fun handoffAllSevenToGemini() {
        val cur = _state.value
        val report = cur.optimizationReport ?: run {
            postMessage("Optimizer results still loading.")
            return
        }
        val capLabel = resolveCapitalPresetLabel(cur.capitalPreset, cur.resolvedCapitalBudget)
        val packet = deepAnalysisFormatter.formatFullSevenStrategyPacket(
            report = report,
            dayChangeDollar = cur.dayChangeDollar,
            dayChangePct = cur.dayChangePct,
            capitalCapLabel = capLabel
        )
        val result = geminiHandoffManager.handoffPrompt(
            prompt = packet,
            subject = "SchwabQuant ${cur.symbol} 7-Strategy Matrix"
        )
        postMessage(result.message)
    }

    fun handoffSingleStrategyToGemini(result: StrategyOptimizationResult) {
        val cur = _state.value
        val report = cur.optimizationReport ?: return
        val packet = deepAnalysisFormatter.formatSingleStrategyPacket(
            result = result,
            report = report,
            dayChangeDollar = cur.dayChangeDollar,
            dayChangePct = cur.dayChangePct
        )
        val handoff = geminiHandoffManager.handoffPrompt(
            prompt = packet,
            subject = "SchwabQuant ${cur.symbol} #${result.rank} ${result.strategyType.shortLabel}"
        )
        postMessage(handoff.message)
    }

    fun stageEquityOrder() {
        val cur = _state.value
        val spot = cur.spotPrice.takeIf { it > 0.0 } ?: 100.0
        stagedOrderHolder.stageBlankOrder(symbol = cur.symbol, spotPrice = spot)
    }

    fun addQuickSentinelRule(
        ruleType: SentinelRuleType,
        triggerPriceText: String,
        quantityText: String
    ) {
        val triggerPrice = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(triggerPriceText)
        val quantity = quantityText.trim().toIntOrNull()?.takeIf { it > 0 }
        if (triggerPrice == null || quantity == null) {
            postMessage("Enter a positive trigger price (>= 0.01) and share quantity.")
            return
        }
        viewModelScope.launch {
            val activeMode = preferences.executionMode.first()
            val symbol = _state.value.symbol
            val rule = SentinelRule(
                symbol = symbol,
                category = AssetCategory.EQUITY,
                ruleType = ruleType,
                triggerPrice = triggerPrice,
                quantity = quantity,
                isShortPosition = ruleType == SentinelRuleType.SHORT_STOP_LOSS,
                session = TradingSession.SEAMLESS,
                status = SentinelRuleStatus.ARMED,
                executionMode = activeMode,
                createdAtEpochMs = System.currentTimeMillis(),
                updatedAtEpochMs = System.currentTimeMillis()
            )
            sentinelRepository.upsertRule(rule)
            postMessage("Armed ${ruleType.shortTag} on $symbol @ \$${triggerPrice.toPlainString()} (${activeMode.name}).")
        }
    }

    fun onScreenResumed() {
        isScreenVisible = true
        updateOverlaySubscriptionState()
    }

    fun onScreenPaused() {
        isScreenVisible = false
        streamerClient.setChartOverlaySymbol(null)
        quotePollJob?.cancel()
        quotePollJob = null
    }

    fun consumeSnackbar() {
        _state.value = _state.value.copy(snackbarMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        streamerClient.setChartOverlaySymbol(null)
        quotePollJob?.cancel()
    }

    private fun updateOverlaySubscriptionState() {
        if (!isScreenVisible) return
        val sym = _state.value.symbol
        if (_state.value.isLiveMarketData) {
            streamerClient.setChartOverlaySymbol(sym)
        } else {
            streamerClient.setChartOverlaySymbol(null)
        }
        startQuotePollingIfNeeded()
    }

    private fun startQuotePollingIfNeeded() {
        quotePollJob?.cancel()
        if (!isScreenVisible) return
        quotePollJob = viewModelScope.launch {
            while (isActive) {
                delay(QUOTE_POLL_INTERVAL_MS)
                val sym = _state.value.symbol
                val authState = authRepository.observeAuthState().first()
                val authed = authState.status == OAuthStatus.AUTHENTICATED ||
                    authState.status == OAuthStatus.REFRESH_EXPIRING_SOON
                val streamerAuthed = streamerClient.connectionState.value == StreamerConnectionState.AUTHENTICATED
                _state.value = _state.value.copy(isChartStreamingLive = streamerAuthed)
                if (authed && !streamerAuthed) {
                    val quotes = marketDataRepository.getQuotes(listOf(sym), forceRefresh = true).getOrNull()
                    val dto = quotes?.get(sym)
                        ?: quotes?.get(SchwabSymbolNormalizer.toApiSymbol(sym))
                        ?: quotes?.values?.firstOrNull()
                    val last = dto?.quote?.lastPrice ?: dto?.quote?.mark
                    val netChg = dto?.quote?.netChange
                    val pctChg = dto?.quote?.netPercentChange
                    if (last != null && last > 0.0) {
                        applyLivePriceTick(
                            price = last,
                            dayChangeDollar = netChg,
                            dayChangePct = pctChg,
                            volumeDelta = null
                        )
                    }
                }
            }
        }
    }

    private fun observeStreamerQuotes() {
        viewModelScope.launch {
            streamerClient.equityQuotes.collect { quote ->
                if (quote.symbol == _state.value.symbol) {
                    val px = quote.lastPrice ?: quote.markPrice
                        ?: if (quote.bidPrice != null && quote.askPrice != null) {
                            (quote.bidPrice + quote.askPrice) * 0.5
                        } else null
                    if (px != null && px > 0.0) {
                        applyLivePriceTick(
                            price = px,
                            dayChangeDollar = null,
                            dayChangePct = null,
                            volumeDelta = quote.lastSize
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            streamerClient.connectionState.collect { st ->
                _state.value = _state.value.copy(
                    isChartStreamingLive = st == StreamerConnectionState.AUTHENTICATED
                )
            }
        }
    }

    private fun observeRegimeChanges() {
        viewModelScope.launch {
            regimeStateHolder.currentEvaluation.collect { eval ->
                val regime = eval?.regime ?: MarketRegime.CAUTION_ELEVATED_VOL
                if (regime != _state.value.activeRegime) {
                    _state.value = _state.value.copy(activeRegime = regime)
                    recomputeOptimizer()
                }
            }
        }
    }

    private fun observeRuntimeOverlays() {
        viewModelScope.launch {
            preferences.executionMode.flatMapLatest { mode ->
                combine(
                    portfolioRepository.observeAccountSummary(mode),
                    portfolioRepository.observePositions(mode),
                    sentinelRepository.observeActiveRules(),
                    orderRepository.observeWorkingOrders(),
                    activeSymbolFlow
                ) { acct, positions, rules, orders, sym ->
                    RuntimeOverlaySnapshot(mode, acct, positions, rules, orders, sym)
                }
            }.collect { snap ->
                latestAccountSummary = snap.accountSummary
                val equityPos = snap.positions.firstOrNull {
                    it.underlyingSymbol == snap.symbol && it.category == AssetCategory.EQUITY
                }
                val qtySuggestion = equityPos?.quantity?.toDouble()?.let { kotlin.math.abs(it).toInt() }
                    ?.takeIf { it > 0 } ?: 10
                val lines = buildBasePriceLines(snap.symbol, snap.positions, snap.rules, snap.workingOrders)
                _state.value = _state.value.copy(
                    executionMode = snap.mode,
                    baseOverlayPriceLines = lines,
                    suggestedStopQty = qtySuggestion
                )
                if (_state.value.capitalPreset == OptimizerCapitalPreset.SAFE_CAP && latestParsedChain != null) {
                    recomputeOptimizer()
                }
            }
        }
    }

    private data class RuntimeOverlaySnapshot(
        val mode: ExecutionMode,
        val accountSummary: AccountSummary?,
        val positions: List<Position>,
        val rules: List<SentinelRule>,
        val workingOrders: List<WorkingOrder>,
        val symbol: String
    )

    private fun buildBasePriceLines(
        symbol: String,
        positions: List<Position>,
        rules: List<SentinelRule>,
        orders: List<WorkingOrder>
    ): List<JsPriceLine> {
        val out = mutableListOf<JsPriceLine>()
        positions.firstOrNull {
            it.underlyingSymbol == symbol && it.category == AssetCategory.EQUITY && it.averageCost > BigDecimal.ZERO
        }?.let { eq ->
            out.add(
                JsPriceLine(
                    price = eq.averageCost.toDouble(),
                    color = "#58A6FF",
                    lineWidth = 1,
                    dashed = true,
                    title = "AVG"
                )
            )
        }
        rules.filter { it.symbol == symbol }.forEach { rule ->
            val color = when (rule.ruleType) {
                SentinelRuleType.LONG_STOP_LOSS,
                SentinelRuleType.SHORT_STOP_LOSS -> "#FF5252"
                SentinelRuleType.TAKE_PROFIT -> "#00E676"
                SentinelRuleType.DIP_BUY_ANTI_KNIFE,
                SentinelRuleType.OVERNIGHT_PRICE_ALERT -> "#FFB300"
            }
            out.add(
                JsPriceLine(
                    price = rule.triggerPrice.toDouble(),
                    color = color,
                    lineWidth = 2,
                    dashed = true,
                    title = rule.ruleType.shortTag
                )
            )
        }
        orders.filter { it.underlyingSymbol == symbol }.forEach { order ->
            order.limitPrice?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { lmt ->
                out.add(
                    JsPriceLine(
                        price = lmt,
                        color = "#2DD4BF",
                        lineWidth = 1,
                        dashed = false,
                        title = "LMT"
                    )
                )
            }
            order.stopPrice?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { stp ->
                out.add(
                    JsPriceLine(
                        price = stp,
                        color = "#FB7185",
                        lineWidth = 1,
                        dashed = true,
                        title = "STP"
                    )
                )
            }
        }
        return out
    }

    private fun loadTicker(symbol: String, forceRefresh: Boolean) {
        loadCandles(symbol, _state.value.timeframe, forceRefresh)
        loadChainAndOptimizer(symbol, forceRefresh)
    }

    private fun loadCandles(symbol: String, timeframe: ChartTimeframe, forceRefresh: Boolean) {
        chartLoadJob?.cancel()
        chartLoadJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingChart = true)
            val authState = authRepository.observeAuthState().first()
            val canUseLive = authState.status == OAuthStatus.AUTHENTICATED ||
                authState.status == OAuthStatus.REFRESH_EXPIRING_SOON

            var liveOk = false
            val candles = if (canUseLive) {
                val res = marketDataRepository.getChartCandles(symbol, timeframe, forceRefresh)
                val data = res.getOrNull()
                if (!data.isNullOrEmpty()) {
                    liveOk = true
                    data
                } else {
                    demoChartCandleProvider.buildDemoCandles(symbol, timeframe)
                }
            } else {
                demoChartCandleProvider.buildDemoCandles(symbol, timeframe)
            }

            if (canUseLive && isScreenVisible && liveOk) {
                streamerClient.setChartOverlaySymbol(symbol)
            }

            val indicators = withContext(Dispatchers.Default) {
                indicatorCalculator.computeAll(
                    candles = candles,
                    includeSessionVwap = timeframe.isIntraday
                )
            }
            val latestClose = candles.lastOrNull()?.close ?: _state.value.spotPrice
            val prevClose = if (candles.size >= 2) candles[candles.size - 2].close else null
            val chgDollar = if (prevClose != null && prevClose > 0.0) latestClose - prevClose else null
            val chgPct = if (prevClose != null && prevClose > 0.0 && chgDollar != null) (chgDollar / prevClose) * 100.0 else null

            _state.value = _state.value.copy(
                isLoadingChart = false,
                isLiveMarketData = liveOk || _state.value.isLiveMarketData,
                candles = candles,
                indicators = indicators,
                chartResetCounter = _state.value.chartResetCounter + 1,
                latestLiveBar = null,
                spotPrice = if (_state.value.spotPrice <= 0.0) latestClose else _state.value.spotPrice,
                dayChangeDollar = _state.value.dayChangeDollar ?: chgDollar,
                dayChangePct = _state.value.dayChangePct ?: chgPct
            )
        }
    }

    private fun loadChainAndOptimizer(symbol: String, forceRefresh: Boolean) {
        chainLoadJob?.cancel()
        chainLoadJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingChain = true, isComputingOptimizer = true)
            val authState = authRepository.observeAuthState().first()
            val canUseLive = authState.status == OAuthStatus.AUTHENTICATED ||
                authState.status == OAuthStatus.REFRESH_EXPIRING_SOON

            var isDemo = true
            var parsedChain: ParsedOptionChain? = null
            var volRankValue: Double? = null
            var sma50Value: Double? = null
            var dayDollar: Double? = null
            var dayPct: Double? = null

            if (canUseLive) {
                val chainRes = marketDataRepository.getSingleTickerOptionChain(symbol, forceRefresh)
                val chainDto = chainRes.getOrNull()
                if (chainDto != null) {
                    val quotesMap = marketDataRepository.getQuotes(listOf(symbol), forceRefresh = forceRefresh).getOrNull()
                    val quoteDto = quotesMap?.get(symbol)
                        ?: quotesMap?.get(SchwabSymbolNormalizer.toApiSymbol(symbol))
                        ?: quotesMap?.values?.firstOrNull()
                    val divYield = SchwabQuoteNormalizer.percentToDecimal(quoteDto?.fundamental?.divYield) ?: 0.0
                    val parsed = withContext(Dispatchers.Default) {
                        optionChainParser.parse(chainDto, divYield = divYield)
                    }
                    if (parsed.underlyingPrice > 0.0 && parsed.contracts.isNotEmpty()) {
                        isDemo = false
                        parsedChain = parsed
                        dayDollar = quoteDto?.quote?.netChange
                        dayPct = quoteDto?.quote?.netPercentChange
                        val dailyCandles = marketDataRepository.getChartCandles(symbol, ChartTimeframe.D1, forceRefresh = false)
                            .getOrNull().orEmpty()
                        latestDailyCloses = dailyCandles.map { it.close }
                        if (latestDailyCloses.isNotEmpty()) {
                            volRankValue = volRankCalculator.computeVolRank(latestDailyCloses, parsed.atm30DayIv).volRank
                            if (latestDailyCloses.size >= 50) {
                                sma50Value = latestDailyCloses.takeLast(50).average()
                            }
                        }
                    }
                }
            }

            if (parsedChain == null) {
                val demo = demoOptionChainProvider.buildDemoData(symbol)
                parsedChain = demo.chain
                volRankValue = demo.volRank.volRank
                sma50Value = demo.sma50
                isDemo = true
            }

            latestParsedChain = parsedChain
            val board = withContext(Dispatchers.Default) {
                chainBoardBuilder.buildBoard(parsedChain, _state.value.strikeWindow)
            }

            val autoExpanded = _state.value.expandedExpirations.ifEmpty {
                val targetSection = board.sections.firstOrNull { it.dte in 25..45 }
                    ?: board.sections.firstOrNull()
                if (targetSection != null) setOf(targetSection.expirationDate) else emptySet()
            }

            _state.value = _state.value.copy(
                isLoadingChain = false,
                isLiveMarketData = !isDemo,
                spotPrice = parsedChain.underlyingPrice.takeIf { it > 0.0 } ?: _state.value.spotPrice,
                dayChangeDollar = dayDollar ?: _state.value.dayChangeDollar,
                dayChangePct = dayPct ?: _state.value.dayChangePct,
                volRank52w = volRankValue,
                sma50 = sma50Value,
                chainBoard = board,
                expandedExpirations = autoExpanded
            )
            recomputeOptimizer(isDemoData = isDemo)
        }
    }

    private fun recomputeOptimizer(isDemoData: Boolean = !_state.value.isLiveMarketData) {
        val chain = latestParsedChain ?: return
        optimizerJob?.cancel()
        optimizerJob = viewModelScope.launch {
            _state.value = _state.value.copy(isComputingOptimizer = true)
            val cur = _state.value
            val resolvedBudget = resolveCapitalBudget(cur.capitalPreset, latestAccountSummary)
            val report = withContext(Dispatchers.Default) {
                analyzeStrategiesUseCase.analyze(
                    chain = chain,
                    activeRegime = cur.activeRegime,
                    volRank52w = cur.volRank52w,
                    sma50 = cur.sma50,
                    maxCapitalBudget = resolvedBudget,
                    riskProfile = cur.riskProfile,
                    isDemoData = isDemoData
                )
            }
            _state.value = _state.value.copy(
                isComputingOptimizer = false,
                resolvedCapitalBudget = resolvedBudget,
                optimizationReport = report
            )
        }
    }

    private fun resolveCapitalBudget(
        preset: OptimizerCapitalPreset,
        summary: AccountSummary?
    ): Double? {
        if (preset == OptimizerCapitalPreset.SAFE_CAP) {
            val s = summary ?: return 5_000.0
            val settled = s.settledCash.toDouble().coerceAtLeast(0.0)
            val quarterLiq = (s.netLiquidationValue.toDouble() * 0.25).coerceAtLeast(0.0)
            return minOf(settled, quarterLiq).coerceAtLeast(500.0)
        }
        return preset.fixedLimitDollars
    }

    private fun resolveCapitalPresetLabel(
        preset: OptimizerCapitalPreset,
        resolvedBudget: Double?
    ): String = when {
        preset == OptimizerCapitalPreset.ALL -> "Unlimited (All)"
        preset == OptimizerCapitalPreset.SAFE_CAP && resolvedBudget != null ->
            "Safe Cap ($${String.format(Locale.US, "%.0f", resolvedBudget)})"
        else -> preset.label
    }

    private fun applyLivePriceTick(
        price: Double,
        dayChangeDollar: Double?,
        dayChangePct: Double?,
        volumeDelta: Long?
    ) {
        val cur = _state.value
        val lastBar = cur.candles.lastOrNull()
        val updatedBar = if (lastBar != null) {
            val newBar = lastBar.copy(
                high = maxOf(lastBar.high, price),
                low = minOf(lastBar.low, price),
                close = price,
                volume = lastBar.volume + (volumeDelta?.coerceAtLeast(0L) ?: 0L)
            )
            newBar
        } else {
            null
        }
        val nextCandles = if (updatedBar != null && cur.candles.isNotEmpty()) {
            cur.candles.dropLast(1) + updatedBar
        } else {
            cur.candles
        }
        _state.value = cur.copy(
            spotPrice = price,
            dayChangeDollar = dayChangeDollar ?: cur.dayChangeDollar,
            dayChangePct = dayChangePct ?: cur.dayChangePct,
            candles = nextCandles,
            latestLiveBar = updatedBar
        )
    }

    private fun updateToggles(transform: ChartOverlayToggles.() -> ChartOverlayToggles) {
        _state.value = _state.value.copy(overlayToggles = _state.value.overlayToggles.transform())
    }

    private fun postMessage(msg: String) {
        _state.value = _state.value.copy(snackbarMessage = msg)
    }

    private fun sanitizeTicker(raw: String): String {
        val clean = SchwabSymbolNormalizer.toDisplaySymbol(raw)
        return if (clean.isNotBlank() && !clean.contains("..") && TICKER_REGEX.matches(clean)) {
            clean
        } else {
            "SPY"
        }
    }

    companion object {
        private const val QUOTE_POLL_INTERVAL_MS = 5_000L
        private val TICKER_REGEX = Regex("""^[A-Z0-9](?:[A-Z0-9.\-]{0,14}[A-Z0-9])?$""")
    }
}
