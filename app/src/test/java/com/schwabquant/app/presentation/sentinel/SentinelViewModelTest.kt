package com.schwabquant.app.presentation.sentinel

import android.content.Context
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.streamer.SchwabStreamerClient
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleStatus
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.domain.model.StagedOrderHolder
import com.schwabquant.app.domain.repository.OrderRepository
import com.schwabquant.app.domain.repository.PortfolioRepository
import com.schwabquant.app.domain.repository.SentinelRepository
import com.schwabquant.app.domain.usecase.MarketSessionResolver
import com.schwabquant.app.domain.usecase.OvernightAlertBuilder
import com.schwabquant.app.service.SentinelRuntimeCoordinator
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class SentinelViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `MEDIUM-1 addRule rejects sub-half-cent trigger prices that round to 0_00 and scientific notation DoS`() = runTest(mainDispatcher) {
        val sentinelRepo = mockk<SentinelRepository>(relaxed = true) {
            every { observeRules() } returns MutableStateFlow(emptyList())
        }
        val prefs = mockk<AppPreferencesDataStore>(relaxed = true) {
            every { executionMode } returns MutableStateFlow(ExecutionMode.SHADOW)
        }
        val streamer = mockk<SchwabStreamerClient>(relaxed = true) {
            every { connectionState } returns MutableStateFlow(com.schwabquant.app.data.remote.streamer.StreamerConnectionState.DISCONNECTED)
            every { lastHeartbeatEpochMs } returns MutableStateFlow(0L)
            every { liveQuotesSnapshot } returns MutableStateFlow(emptyMap())
        }
        val coordinator = mockk<SentinelRuntimeCoordinator>(relaxed = true) {
            every { serviceStatus } returns MutableStateFlow(com.schwabquant.app.domain.model.SentinelServiceStatus())
            every { ruleTelemetry } returns MutableStateFlow(emptyMap())
        }
        val orderRepo = mockk<OrderRepository>(relaxed = true) {
            every { observeWorkingOrders() } returns MutableStateFlow(emptyList())
            every { observeRecentAuditLogs(any()) } returns MutableStateFlow(emptyList())
        }
        val portfolioRepo = mockk<PortfolioRepository>(relaxed = true) {
            every { observePositions(any()) } returns MutableStateFlow(emptyList())
        }

        val viewModel = SentinelViewModel(
            preferences = prefs,
            sentinelRepository = sentinelRepo,
            streamerClient = streamer,
            runtimeCoordinator = coordinator,
            marketSessionResolver = MarketSessionResolver(),
            alertBuilder = OvernightAlertBuilder(),
            orderRepository = orderRepo,
            portfolioRepository = portfolioRepo,
            stagedOrderHolder = StagedOrderHolder()
        )
        val ctx = mockk<Context>(relaxed = true)

        assertFalse(
            viewModel.addRule(
                NewSentinelRuleInput(
                    symbol = "GOOG",
                    ruleType = SentinelRuleType.TAKE_PROFIT,
                    triggerPrice = "0.004",
                    quantity = "10"
                ),
                ctx
            )
        )
        assertFalse(
            viewModel.addRule(
                NewSentinelRuleInput(
                    symbol = "GOOG",
                    ruleType = SentinelRuleType.SHORT_STOP_LOSS,
                    triggerPrice = "1e999999",
                    quantity = "10"
                ),
                ctx
            )
        )
        assertFalse(
            viewModel.addRule(
                NewSentinelRuleInput(
                    symbol = "GOOG",
                    ruleType = SentinelRuleType.TAKE_PROFIT,
                    triggerPrice = "1e-4",
                    quantity = "10"
                ),
                ctx
            )
        )

        coVerify(exactly = 0) { sentinelRepo.upsertRule(any()) }
    }

    @Test
    fun `V1-9-1 LOW-1 toggleRuleArmed fails closed on cross-mode rule and EXECUTING rule`() = runTest(mainDispatcher) {
        val sentinelRepo = mockk<SentinelRepository>(relaxed = true) {
            every { observeRules() } returns MutableStateFlow(emptyList())
        }
        val prefs = mockk<AppPreferencesDataStore>(relaxed = true) {
            every { executionMode } returns MutableStateFlow(ExecutionMode.SHADOW)
        }
        val streamer = mockk<SchwabStreamerClient>(relaxed = true) {
            every { connectionState } returns MutableStateFlow(com.schwabquant.app.data.remote.streamer.StreamerConnectionState.DISCONNECTED)
            every { lastHeartbeatEpochMs } returns MutableStateFlow(0L)
            every { liveQuotesSnapshot } returns MutableStateFlow(emptyMap())
        }
        val coordinator = mockk<SentinelRuntimeCoordinator>(relaxed = true) {
            every { serviceStatus } returns MutableStateFlow(com.schwabquant.app.domain.model.SentinelServiceStatus())
            every { ruleTelemetry } returns MutableStateFlow(emptyMap())
        }
        val orderRepo = mockk<OrderRepository>(relaxed = true) {
            every { observeWorkingOrders() } returns MutableStateFlow(emptyList())
            every { observeRecentAuditLogs(any()) } returns MutableStateFlow(emptyList())
        }
        val portfolioRepo = mockk<PortfolioRepository>(relaxed = true) {
            every { observePositions(any()) } returns MutableStateFlow(emptyList())
        }

        val viewModel = SentinelViewModel(
            preferences = prefs,
            sentinelRepository = sentinelRepo,
            streamerClient = streamer,
            runtimeCoordinator = coordinator,
            marketSessionResolver = MarketSessionResolver(),
            alertBuilder = OvernightAlertBuilder(),
            orderRepository = orderRepo,
            portfolioRepository = portfolioRepo,
            stagedOrderHolder = StagedOrderHolder()
        )
        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        val ctx = mockk<Context>(relaxed = true)

        val liveEscalatedRule = SentinelRule(
            id = 77L,
            symbol = "GOOG",
            category = AssetCategory.EQUITY,
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("260.00"),
            quantity = 10,
            status = SentinelRuleStatus.UNFILLED_ESCALATED,
            executionMode = ExecutionMode.LIVE,
            lastSubmittedOrderId = "LIVE-ORD-991"
        )
        val shadowExecutingRule = liveEscalatedRule.copy(
            id = 78L,
            status = SentinelRuleStatus.EXECUTING,
            executionMode = ExecutionMode.SHADOW,
            lastSubmittedOrderId = "SHADOW-ORD-12"
        )

        viewModel.toggleRuleArmed(liveEscalatedRule, ctx)
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isErrorFeedback)
        assertTrue(viewModel.uiState.value.lastFeedback?.contains("LIVE Mode", ignoreCase = true) == true)

        viewModel.toggleRuleArmed(shadowExecutingRule, ctx)
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isErrorFeedback)
        assertTrue(viewModel.uiState.value.lastFeedback?.contains("in flight", ignoreCase = true) == true)

        coVerify(exactly = 0) { coordinator.clearTracker(any()) }
        coVerify(exactly = 0) { sentinelRepo.updateRuleStatusWithNote(any(), any(), any()) }
        assertTrue(liveEscalatedRule.lastSubmittedOrderId == "LIVE-ORD-991")
        collectJob.cancel()
    }
}
