package com.schwabquant.app.presentation.market

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.schwabquant.app.core.handoff.GeminiHandoffManager
import com.schwabquant.app.core.handoff.OptimizerDeepAnalysisFormatter
import com.schwabquant.app.core.quant.ChartCandle
import com.schwabquant.app.core.quant.ChartIndicatorCalculator
import com.schwabquant.app.core.quant.ChartTimeframe
import com.schwabquant.app.core.quant.OptionChainParser
import com.schwabquant.app.core.quant.OptionsChainBoardBuilder
import com.schwabquant.app.core.quant.SpreadDraftAssembler
import com.schwabquant.app.core.quant.VolRankCalculator
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.dto.QuoteDetailDto
import com.schwabquant.app.data.remote.dto.QuoteEntryDto
import com.schwabquant.app.data.remote.streamer.LiveEquityQuote
import com.schwabquant.app.data.remote.streamer.SchwabStreamerClient
import com.schwabquant.app.data.remote.streamer.StreamerConnectionState
import com.schwabquant.app.data.repository.DemoChartCandleProvider
import com.schwabquant.app.data.repository.DemoOptionChainProvider
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.RegimeStateHolder
import com.schwabquant.app.domain.model.StagedOrderHolder
import com.schwabquant.app.domain.repository.AuthRepository
import com.schwabquant.app.domain.repository.AuthSessionState
import com.schwabquant.app.domain.repository.MarketDataRepository
import com.schwabquant.app.domain.repository.OAuthStatus
import com.schwabquant.app.domain.repository.OrderRepository
import com.schwabquant.app.domain.repository.PortfolioRepository
import com.schwabquant.app.domain.repository.SentinelRepository
import com.schwabquant.app.domain.usecase.AnalyzeSingleTickerStrategiesUseCase
import com.schwabquant.app.presentation.navigation.NavRoutes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class MarketDetailViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val nyZone = ZoneId.of("America/New_York")
    private val createdViewModels = mutableListOf<MarketDetailViewModel>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        createdViewModels.forEach { vm ->
            runBlocking {
                vm.viewModelScope.coroutineContext[Job]?.children?.toList()?.forEach { child ->
                    child.cancelAndJoin()
                }
                vm.viewModelScope.cancel()
            }
        }
        createdViewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `V1-8-2 live quote in newer bucket appends new bar without mutating prior candle`() = runTest {
        val day1Sec = ZonedDateTime.of(2026, 11, 18, 9, 30, 0, 0, nyZone).toEpochSecond()
        val day2Sec = ZonedDateTime.of(2026, 11, 19, 9, 30, 0, 0, nyZone).toEpochSecond()
        val day3TickMs = ZonedDateTime.of(2026, 11, 20, 10, 15, 0, 0, nyZone).toInstant().toEpochMilli()

        val initialCandles = listOf(
            ChartCandle(day1Sec, open = 545.0, high = 547.0, low = 544.0, close = 546.0, volume = 800L),
            ChartCandle(day2Sec, open = 549.0, high = 552.0, low = 548.0, close = 550.0, volume = 1_000L)
        )
        val quotesMap = mapOf(
            "SPY" to QuoteEntryDto(
                symbol = "SPY",
                quote = QuoteDetailDto(
                    lastPrice = 550.0,
                    netChange = 4.0,
                    netPercentChange = 0.73
                )
            )
        )

        val harness = createHarness(
            authStatus = OAuthStatus.AUTHENTICATED,
            liveCandles = initialCandles,
            liveQuotes = quotesMap
        )
        awaitInitialLoad(harness.viewModel)

        assertEquals(2, harness.viewModel.uiState.value.candles.size)

        harness.equityQuotesFlow.emit(
            LiveEquityQuote(
                symbol = "SPY",
                lastPrice = 562.50,
                lastSize = 100L,
                tradeTimeMs = day3TickMs
            )
        )
        testScheduler.runCurrent()

        val candles = harness.viewModel.uiState.value.candles
        assertEquals(3, candles.size)
        // Prior session bar must remain completely intact (no stretched high/low/close)
        assertEquals(549.0, candles[1].open)
        assertEquals(552.0, candles[1].high)
        assertEquals(548.0, candles[1].low)
        assertEquals(550.0, candles[1].close)
        assertEquals(1_000L, candles[1].volume)
        // New bucket bar has O=H=L=C=livePrice
        assertEquals(562.50, candles[2].open)
        assertEquals(562.50, candles[2].high)
        assertEquals(562.50, candles[2].low)
        assertEquals(562.50, candles[2].close)
        assertEquals(100L, candles[2].volume)
        assertEquals(562.50, harness.viewModel.uiState.value.spotPrice)
    }

    @Test
    fun `V1-8-2 live quote in same bucket updates rightmost bar high low and close`() = runTest {
        val day1Sec = ZonedDateTime.of(2026, 11, 18, 9, 30, 0, 0, nyZone).toEpochSecond()
        val day2Sec = ZonedDateTime.of(2026, 11, 19, 9, 30, 0, 0, nyZone).toEpochSecond()
        val day2LaterMs = ZonedDateTime.of(2026, 11, 19, 14, 0, 0, 0, nyZone).toInstant().toEpochMilli()

        val initialCandles = listOf(
            ChartCandle(day1Sec, open = 545.0, high = 547.0, low = 544.0, close = 546.0, volume = 800L),
            ChartCandle(day2Sec, open = 549.0, high = 552.0, low = 548.0, close = 550.0, volume = 1_000L)
        )
        val quotesMap = mapOf(
            "SPY" to QuoteEntryDto(
                symbol = "SPY",
                quote = QuoteDetailDto(lastPrice = 550.0, netChange = 4.0, netPercentChange = 0.73)
            )
        )

        val harness = createHarness(
            authStatus = OAuthStatus.AUTHENTICATED,
            liveCandles = initialCandles,
            liveQuotes = quotesMap
        )
        awaitInitialLoad(harness.viewModel)

        harness.equityQuotesFlow.emit(
            LiveEquityQuote(
                symbol = "SPY",
                lastPrice = 554.25,
                lastSize = 250L,
                tradeTimeMs = day2LaterMs
            )
        )
        testScheduler.runCurrent()

        val candles = harness.viewModel.uiState.value.candles
        assertEquals(2, candles.size)
        assertEquals(549.0, candles[1].open)
        assertEquals(554.25, candles[1].high)
        assertEquals(548.0, candles[1].low)
        assertEquals(554.25, candles[1].close)
        assertEquals(1_250L, candles[1].volume)
    }

    @Test
    fun `V1-8-2 day change comes strictly from live quote and stays null when offline`() = runTest {
        val day1Sec = ZonedDateTime.of(2026, 11, 18, 9, 30, 0, 0, nyZone).toEpochSecond()
        val day2Sec = ZonedDateTime.of(2026, 11, 19, 9, 30, 0, 0, nyZone).toEpochSecond()
        val gappedCandles = listOf(
            ChartCandle(day1Sec, open = 500.0, high = 502.0, low = 499.0, close = 500.0, volume = 500L),
            ChartCandle(day2Sec, open = 548.0, high = 552.0, low = 547.0, close = 550.0, volume = 900L)
        )

        // Offline: demo candles have non-zero candle-to-candle moves, yet dayChange must stay null (never faked)
        val offlineHarness = createHarness(
            authStatus = OAuthStatus.NOT_CONFIGURED
        )
        awaitInitialLoad(offlineHarness.viewModel)
        assertTrue(offlineHarness.viewModel.uiState.value.candles.size >= 2)
        assertNull(offlineHarness.viewModel.uiState.value.dayChangeDollar)
        assertNull(offlineHarness.viewModel.uiState.value.dayChangePct)

        // Online: must use exact quote.netChange / quote.netPercentChange rather than +50 candle diff
        val liveHarness = createHarness(
            authStatus = OAuthStatus.AUTHENTICATED,
            liveCandles = gappedCandles,
            liveQuotes = mapOf(
                "SPY" to QuoteEntryDto(
                    symbol = "SPY",
                    quote = QuoteDetailDto(
                        lastPrice = 546.75,
                        netChange = -3.25,
                        netPercentChange = -0.59
                    )
                )
            )
        )
        awaitInitialLoad(liveHarness.viewModel)
        assertEquals(-3.25, liveHarness.viewModel.uiState.value.dayChangeDollar)
        assertEquals(-0.59, liveHarness.viewModel.uiState.value.dayChangePct)
    }

    @Test
    fun `V1-9-1 HIGH-1 addQuickSentinelRule records SENTINEL_RULE_ARMED and warns if service launch blocked`() = runTest {
        val harness = createHarness(authStatus = OAuthStatus.NOT_CONFIGURED)
        awaitInitialLoad(harness.viewModel)

        var starterCalls = 0
        var starterResult = true
        harness.viewModel.sentinelServiceStarter = {
            starterCalls++
            starterResult
        }

        val ctx = mockk<android.content.Context>(relaxed = true)
        harness.viewModel.addQuickSentinelRule(
            ruleType = com.schwabquant.app.domain.model.SentinelRuleType.LONG_STOP_LOSS,
            triggerPriceText = "540.00",
            quantityText = "10",
            context = ctx
        )
        testScheduler.runCurrent()

        assertEquals(1, starterCalls)
        io.mockk.coVerify(exactly = 1) { harness.sentinelRepository.upsertRule(any()) }
        io.mockk.coVerify(exactly = 1) {
            harness.sentinelRepository.recordAuditEvent(
                category = "SENTINEL_RULE_ARMED",
                severity = "INFO",
                symbol = "SPY",
                executionMode = any(),
                message = any()
            )
        }
        assertEquals(true, harness.viewModel.uiState.value.snackbarMessage?.contains("started Sentinel"))

        // When FGS start is blocked on Android 14+, surface explicit manual monitoring warning
        starterResult = false
        harness.viewModel.consumeSnackbar()
        harness.viewModel.addQuickSentinelRule(
            ruleType = com.schwabquant.app.domain.model.SentinelRuleType.TAKE_PROFIT,
            triggerPriceText = "565.00",
            quantityText = "10",
            context = ctx
        )
        testScheduler.runCurrent()

        assertEquals(2, starterCalls)
        val blockedMsg = harness.viewModel.uiState.value.snackbarMessage.orEmpty()
        assertTrue(blockedMsg.contains("Open the Sentinel tab", ignoreCase = true))
    }

    private suspend fun awaitInitialLoad(viewModel: MarketDetailViewModel) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                viewModel.uiState.first {
                    !it.isLoadingChart && !it.isLoadingChain && !it.isComputingOptimizer
                }
            }
        }
    }

    private data class Harness(
        val viewModel: MarketDetailViewModel,
        val equityQuotesFlow: MutableSharedFlow<LiveEquityQuote>,
        val sentinelRepository: SentinelRepository
    )

    private fun createHarness(
        symbol: String = "SPY",
        authStatus: OAuthStatus,
        liveCandles: List<ChartCandle> = emptyList(),
        liveQuotes: Map<String, QuoteEntryDto> = emptyMap()
    ): Harness {
        val equityQuotesFlow = MutableSharedFlow<LiveEquityQuote>(extraBufferCapacity = 16)
        val marketDataRepository = mockk<MarketDataRepository>(relaxed = true) {
            coEvery { getChartCandles(any(), any(), any()) } returns Result.success(liveCandles)
            coEvery { getQuotes(any(), any()) } returns Result.success(liveQuotes)
            coEvery { getSingleTickerOptionChain(any(), any()) } returns Result.failure(RuntimeException("skip"))
        }
        val streamerClient = mockk<SchwabStreamerClient>(relaxed = true) {
            every { equityQuotes } returns equityQuotesFlow
            every { connectionState } returns MutableStateFlow(StreamerConnectionState.DISCONNECTED)
        }
        val portfolioRepository = mockk<PortfolioRepository>(relaxed = true) {
            every { observeAccountSummary(any()) } returns MutableStateFlow(null)
            every { observePositions(any()) } returns MutableStateFlow(emptyList())
        }
        val orderRepository = mockk<OrderRepository>(relaxed = true) {
            every { observeWorkingOrders() } returns MutableStateFlow(emptyList())
        }
        val sentinelRepository = mockk<SentinelRepository>(relaxed = true) {
            every { observeActiveRules() } returns MutableStateFlow(emptyList())
            coEvery { upsertRule(any()) } returns 42L
        }
        val authRepository = mockk<AuthRepository>(relaxed = true) {
            every { observeAuthState() } returns MutableStateFlow(AuthSessionState(status = authStatus))
        }
        val preferences = mockk<AppPreferencesDataStore>(relaxed = true) {
            every { executionMode } returns MutableStateFlow(ExecutionMode.SHADOW)
        }

        val viewModel = MarketDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(NavRoutes.MARKET_DETAIL_ARG_SYMBOL to symbol)),
            marketDataRepository = marketDataRepository,
            optionChainParser = OptionChainParser(),
            volRankCalculator = VolRankCalculator(),
            indicatorCalculator = ChartIndicatorCalculator(),
            demoChartCandleProvider = DemoChartCandleProvider(),
            demoOptionChainProvider = DemoOptionChainProvider(),
            chainBoardBuilder = OptionsChainBoardBuilder(),
            spreadDraftAssembler = SpreadDraftAssembler(),
            analyzeStrategiesUseCase = AnalyzeSingleTickerStrategiesUseCase(),
            deepAnalysisFormatter = OptimizerDeepAnalysisFormatter(),
            geminiHandoffManager = mockk<GeminiHandoffManager>(relaxed = true),
            streamerClient = streamerClient,
            portfolioRepository = portfolioRepository,
            orderRepository = orderRepository,
            sentinelRepository = sentinelRepository,
            authRepository = authRepository,
            regimeStateHolder = RegimeStateHolder(),
            stagedOrderHolder = StagedOrderHolder(),
            preferences = preferences
        )
        createdViewModels += viewModel
        return Harness(
            viewModel = viewModel,
            equityQuotesFlow = equityQuotesFlow,
            sentinelRepository = sentinelRepository
        )
    }
}
