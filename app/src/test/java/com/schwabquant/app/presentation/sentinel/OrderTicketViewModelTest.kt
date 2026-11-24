package com.schwabquant.app.presentation.sentinel

import androidx.fragment.app.FragmentActivity
import com.schwabquant.app.core.security.BiometricAuthResult
import com.schwabquant.app.core.security.BiometricGate
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.domain.model.AccountSummary
import com.schwabquant.app.domain.model.AggregatedGreeks
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStrategyCategory
import com.schwabquant.app.domain.model.OrderSubmissionResult
import com.schwabquant.app.domain.model.OrderTicket
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.PortfolioSnapshot
import com.schwabquant.app.domain.model.ReplaceOrderOutcome
import com.schwabquant.app.domain.model.RiskRuleCode
import com.schwabquant.app.domain.model.StagedOrderHolder
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.repository.MarketDataRepository
import com.schwabquant.app.domain.repository.OrderRepository
import com.schwabquant.app.domain.repository.PortfolioRepository
import com.schwabquant.app.domain.usecase.EvaluatePreTradeRisksUseCase
import com.schwabquant.app.domain.usecase.MidPriceWalkUpController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class OrderTicketViewModelTest {

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
    fun `C-4 re-price ticket locks out Walk-Up and routes biometric swipe through replaceOrder first`() = runTest(mainDispatcher) {
        val harness = createHarness(isLive = false)
        val collectJob = backgroundScope.launch { harness.viewModel.uiState.collect {} }

        harness.stagedOrderHolder.stageDraft(
            OrderDraft(
                strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
                underlyingSymbol = "AAPL",
                orderType = OrderType.LIMIT,
                session = TradingSession.SEAMLESS,
                duration = OrderDuration.DAY,
                limitPrice = BigDecimal("210.00"),
                legs = listOf(
                    OrderLeg(
                        symbol = "AAPL",
                        category = AssetCategory.EQUITY,
                        side = OrderSide.SELL,
                        quantity = 2,
                        underlyingSymbol = "AAPL",
                        estimatedUnitPrice = BigDecimal("210.00")
                    )
                ),
                replacePriorOrderId = "ORD-PRIOR-99"
            )
        )
        advanceUntilIdle()

        assertEquals("ORD-PRIOR-99", harness.viewModel.uiState.value.replacePriorOrderId)

        // Attempt to turn on Walk-Up while replacePriorOrderId is active -> must stay disabled
        harness.viewModel.setWalkUpEnabled(true)
        assertFalse(harness.viewModel.uiState.value.walkUpEnabled)

        coEvery {
            harness.orderRepository.replaceOrder(
                accountHash = "SHADOW_PAPER",
                oldOrderId = "ORD-PRIOR-99",
                replacementTicket = any(),
                risksAcknowledged = true,
                overriddenRiskCodes = any(),
                cancelToReplaceDelayMs = any(),
                allowTerminalOrPartialReprice = any()
            )
        } returns Result.success(
            ReplaceOrderOutcome.Replaced(
                OrderSubmissionResult(
                    orderId = "ORD-NEW-100",
                    executionMode = ExecutionMode.SHADOW,
                    status = "WORKING",
                    message = "Replaced",
                    submittedAtEpochMs = 1_000L
                )
            )
        )

        harness.viewModel.reviewAndSubmit()
        advanceUntilIdle()
        harness.viewModel.onBiometricSwipeCompleted(null)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            harness.orderRepository.replaceOrder(
                accountHash = "SHADOW_PAPER",
                oldOrderId = "ORD-PRIOR-99",
                replacementTicket = any(),
                risksAcknowledged = true,
                overriddenRiskCodes = any(),
                cancelToReplaceDelayMs = any(),
                allowTerminalOrPartialReprice = false
            )
        }
        coVerify(exactly = 0) { harness.orderRepository.submitOrder(any(), any(), any(), any()) }
        collectJob.cancel()
    }

    @Test
    fun `C-1 Guard 1 Live submission never attaches SHADOW_PAPER accountHash even when vault primary hash is unset or stale`() = runTest(mainDispatcher) {
        val harness = createHarness(isLive = true, vaultAccountHash = "SHADOW_PAPER")
        val collectJob = backgroundScope.launch { harness.viewModel.uiState.collect {} }

        harness.livePortfolioFlow.value = healthyLiveSnapshot()
        every { harness.biometricGate.canAuthenticate() } returns true
        coEvery { harness.biometricGate.authenticate(any(), any(), any()) } returns BiometricAuthResult.Succeeded

        val ticketSlot = slot<OrderTicket>()
        coEvery {
            harness.orderRepository.submitOrder(
                ticket = capture(ticketSlot),
                risksAcknowledged = true,
                overriddenRiskCodes = any(),
                simulateImmediateMidFill = any()
            )
        } returns Result.success(
            OrderSubmissionResult(
                orderId = "LIVE-1",
                executionMode = ExecutionMode.LIVE,
                status = "WORKING",
                message = "Submitted",
                submittedAtEpochMs = 1_000L
            )
        )

        harness.stagedOrderHolder.stageBlankOrder(symbol = "SPY", spotPrice = 550.0)
        advanceUntilIdle()

        val activity = mockk<FragmentActivity>(relaxed = true)
        harness.viewModel.reviewAndSubmit()
        advanceUntilIdle()
        harness.viewModel.acknowledgeRisksAndContinue()
        harness.viewModel.onBiometricSwipeCompleted(activity)
        advanceUntilIdle()

        assertEquals(ExecutionMode.LIVE, ticketSlot.captured.executionMode)
        assertEquals("", ticketSlot.captured.accountHash)
        collectJob.cancel()
    }

    @Test
    fun `H-2 Guard 5 bidirectional SINGLE_EQUITY and SINGLE_OPTION switching plus MARKET DAY lock and trimming`() = runTest(mainDispatcher) {
        val harness = createHarness(isLive = false)
        val collectJob = backgroundScope.launch { harness.viewModel.uiState.collect {} }

        harness.stagedOrderHolder.stageBlankOrder(symbol = "  brk.b ", spotPrice = 445.0)
        advanceUntilIdle()

        assertEquals("BRK.B", harness.viewModel.uiState.value.underlyingSymbol)
        assertEquals(OrderSide.SELL, harness.viewModel.uiState.value.legs.single().side)

        // EQUITY -> OPTION
        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.SINGLE_OPTION)
        val optionState = harness.viewModel.uiState.value
        assertEquals(AssetCategory.OPTION, optionState.legs.single().category)
        assertEquals(OrderSide.SELL_TO_OPEN, optionState.legs.single().side)
        assertEquals("", optionState.legs.single().symbol)
        assertEquals("BRK.B", optionState.legs.single().underlyingSymbol)

        harness.viewModel.updateSingleOptionOccSymbol("BRK/B 260619C00450000")

        // OPTION -> EQUITY
        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.SINGLE_EQUITY)
        val equityState = harness.viewModel.uiState.value
        assertEquals(AssetCategory.EQUITY, equityState.legs.single().category)
        assertEquals("BRK.B", equityState.legs.single().symbol)
        assertEquals(OrderSide.SELL, equityState.legs.single().side)

        // GTC + MARKET must force DAY
        harness.viewModel.updateSession(TradingSession.NORMAL)
        harness.viewModel.updateDuration(OrderDuration.GOOD_TILL_CANCEL)
        assertEquals(OrderDuration.GOOD_TILL_CANCEL, harness.viewModel.uiState.value.duration)

        harness.viewModel.updateOrderType(OrderType.MARKET)
        assertEquals(OrderDuration.DAY, harness.viewModel.uiState.value.duration)
        collectJob.cancel()
    }

    @Test
    fun `M-4 Live reviewAndSubmit auto-refreshes null portfolio and emits PORTFOLIO_TELEMETRY_UNAVAILABLE warning on failure`() = runTest(mainDispatcher) {
        val harness = createHarness(isLive = true)
        val collectJob = backgroundScope.launch { harness.viewModel.uiState.collect {} }

        harness.livePortfolioFlow.value = null
        coEvery { harness.portfolioRepository.refreshLivePortfolio(null) } returns
            Result.failure(IllegalStateException("503 Service Unavailable"))

        harness.stagedOrderHolder.stageBlankOrder(symbol = "QQQ", spotPrice = 480.0)
        advanceUntilIdle()

        harness.viewModel.reviewAndSubmit()
        advanceUntilIdle()

        coVerify(exactly = 1) { harness.portfolioRepository.refreshLivePortfolio(null) }
        val assessment = harness.viewModel.uiState.value.pendingRiskAssessment
        assertTrue(assessment?.warnings?.any { it.code == RiskRuleCode.PORTFOLIO_TELEMETRY_UNAVAILABLE } == true)
        assertFalse(harness.viewModel.uiState.value.readyForBiometricSwipe)
        collectJob.cancel()
    }

    @Test
    fun `H-2 H-3 rejects blank or non-positive price and forwards allowTerminalOrPartialReprice for escalated reprice`() = runTest(mainDispatcher) {
        val harness = createHarness(isLive = false)
        val collectJob = backgroundScope.launch { harness.viewModel.uiState.collect {} }

        harness.stagedOrderHolder.stageBlankOrder(symbol = "GOOG", spotPrice = 270.0)
        advanceUntilIdle()

        harness.viewModel.updateLimitPrice("")
        harness.viewModel.reviewAndSubmit()
        advanceUntilIdle()
        assertFalse(harness.viewModel.uiState.value.readyForBiometricSwipe)
        assertTrue(harness.viewModel.uiState.value.globalMessage?.contains("positive limit price", ignoreCase = true) == true)
        harness.viewModel.consumeGlobalMessage()

        harness.viewModel.updateLimitPrice("268.00")
        harness.viewModel.setWalkUpEnabled(true)
        harness.viewModel.updateWalkUpCeiling("0.00")
        harness.viewModel.reviewAndSubmit()
        advanceUntilIdle()
        assertFalse(harness.viewModel.uiState.value.readyForBiometricSwipe)
        assertTrue(harness.viewModel.uiState.value.globalMessage?.contains("ceiling", ignoreCase = true) == true)

        // Escalated Sentinel reprice must forward allowTerminalOrPartialReprice = true
        coEvery {
            harness.orderRepository.replaceOrder(
                accountHash = "SHADOW_PAPER",
                oldOrderId = "ORD-ESC-55",
                replacementTicket = any(),
                risksAcknowledged = true,
                overriddenRiskCodes = any(),
                cancelToReplaceDelayMs = any(),
                allowTerminalOrPartialReprice = true
            )
        } returns Result.success(
            ReplaceOrderOutcome.Replaced(
                OrderSubmissionResult(
                    orderId = "ORD-ESC-56",
                    executionMode = ExecutionMode.SHADOW,
                    status = "WORKING",
                    message = "Replaced",
                    submittedAtEpochMs = 2_000L
                )
            )
        )

        harness.stagedOrderHolder.stageDraft(
            OrderDraft(
                strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
                underlyingSymbol = "GOOG",
                orderType = OrderType.LIMIT,
                session = TradingSession.SEAMLESS,
                duration = OrderDuration.DAY,
                limitPrice = BigDecimal("265.00"),
                legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 5)),
                replacePriorOrderId = "ORD-ESC-55",
                isEscalatedReprice = true
            )
        )
        advanceUntilIdle()
        harness.viewModel.reviewAndSubmit()
        advanceUntilIdle()
        harness.viewModel.onBiometricSwipeCompleted(null)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            harness.orderRepository.replaceOrder(
                accountHash = "SHADOW_PAPER",
                oldOrderId = "ORD-ESC-55",
                replacementTicket = any(),
                risksAcknowledged = true,
                overriddenRiskCodes = any(),
                cancelToReplaceDelayMs = any(),
                allowTerminalOrPartialReprice = true
            )
        }
        collectJob.cancel()
    }

    @Test
    fun `M-2 switching away from OCO_BRACKET to option, spread, or equity categories resets duration to DAY`() = runTest(mainDispatcher) {
        val harness = createHarness(isLive = false)
        val collectJob = backgroundScope.launch { harness.viewModel.uiState.collect {} }

        harness.stagedOrderHolder.stageBlankOrder(symbol = "GOOG", spotPrice = 270.0)
        advanceUntilIdle()

        // Selecting OCO_BRACKET sets GOOD_TILL_CANCEL
        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.OCO_BRACKET)
        assertEquals(OrderDuration.GOOD_TILL_CANCEL, harness.viewModel.uiState.value.duration)

        // Switching from OCO_BRACKET -> every option/spread category and SINGLE_EQUITY resets duration to DAY
        listOf(
            OrderStrategyCategory.SINGLE_OPTION,
            OrderStrategyCategory.VERTICAL,
            OrderStrategyCategory.DIAGONAL,
            OrderStrategyCategory.CALENDAR,
            OrderStrategyCategory.IRON_CONDOR
        ).forEach { cat ->
            harness.viewModel.selectStrategyCategory(OrderStrategyCategory.OCO_BRACKET)
            assertEquals(OrderDuration.GOOD_TILL_CANCEL, harness.viewModel.uiState.value.duration)
            harness.viewModel.selectStrategyCategory(cat)
            assertEquals(OrderDuration.DAY, harness.viewModel.uiState.value.duration, "Switching OCO -> $cat must reset duration to DAY")
        }

        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.OCO_BRACKET)
        assertEquals(OrderDuration.GOOD_TILL_CANCEL, harness.viewModel.uiState.value.duration)
        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.SINGLE_EQUITY)
        assertEquals(OrderDuration.DAY, harness.viewModel.uiState.value.duration, "Switching OCO -> SINGLE_EQUITY must reset duration to DAY")

        // Even starting with GTC on SINGLE_EQUITY, switching to SINGLE_OPTION/VERTICAL/DIAGONAL resets to DAY
        harness.viewModel.updateSession(TradingSession.NORMAL)
        harness.viewModel.updateDuration(OrderDuration.GOOD_TILL_CANCEL)
        assertEquals(OrderDuration.GOOD_TILL_CANCEL, harness.viewModel.uiState.value.duration)
        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.SINGLE_OPTION)
        assertEquals(OrderDuration.DAY, harness.viewModel.uiState.value.duration)

        collectJob.cancel()
    }

    @Test
    fun `MEDIUM-2 mutating ticket while reviewAndSubmit is suspended discards stale review and blocks biometric swipe`() = runTest {
        val refreshGate = kotlinx.coroutines.CompletableDeferred<Result<PortfolioSnapshot>>()
        val harness = createHarness(isLive = true)
        io.mockk.coEvery { harness.portfolioRepository.refreshLivePortfolio(any()) } coAnswers { refreshGate.await() }
        val collectJob = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            harness.viewModel.uiState.collect {}
        }

        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.SINGLE_EQUITY)
        harness.viewModel.updateQuantity("5")
        harness.viewModel.updateLimitPrice("270.00")
        testScheduler.runCurrent()

        // Start async reviewAndSubmit; it suspends on refreshLivePortfolio
        harness.viewModel.reviewAndSubmit()
        testScheduler.runCurrent()

        // User mutates quantity mid-flight -> increments draftRevision & invalidates in-flight review
        harness.viewModel.updateQuantity("100")
        testScheduler.runCurrent()

        refreshGate.complete(Result.success(healthyLiveSnapshot()))
        testScheduler.runCurrent()

        val staleState = harness.viewModel.uiState.value
        assertEquals("100", staleState.quantityInput)
        assertFalse(staleState.readyForBiometricSwipe, "Stale risk review must never arm biometric swipe")
        org.junit.jupiter.api.Assertions.assertNull(staleState.pendingRiskAssessment)

        // Calling acknowledgeRisksAndContinue or swipe completed on stale state must warn & submit nothing
        harness.viewModel.acknowledgeRisksAndContinue()
        testScheduler.runCurrent()
        org.junit.jupiter.api.Assertions.assertNotNull(harness.viewModel.uiState.value.globalMessage)
        harness.viewModel.onBiometricSwipeCompleted(null)
        testScheduler.runCurrent()
        io.mockk.coVerify(exactly = 0) { harness.orderRepository.submitOrder(any(), any(), any(), any()) }

        // Now perform a clean un-interrupted review, then mutate limit price AFTER review armed biometric swipe
        io.mockk.coEvery { harness.portfolioRepository.refreshLivePortfolio(any()) } returns Result.success(healthyLiveSnapshot())
        harness.viewModel.reviewAndSubmit()
        testScheduler.runCurrent()
        if (harness.viewModel.uiState.value.pendingRiskAssessment != null) {
            harness.viewModel.acknowledgeRisksAndContinue()
            testScheduler.runCurrent()
        }
        assertTrue(harness.viewModel.uiState.value.readyForBiometricSwipe)

        harness.viewModel.updateLimitPrice("275.00")
        testScheduler.runCurrent()
        assertFalse(harness.viewModel.uiState.value.readyForBiometricSwipe, "Post-review edit must disarm biometric swipe")
        harness.viewModel.onBiometricSwipeCompleted(null)
        testScheduler.runCurrent()
        io.mockk.coVerify(exactly = 0) { harness.orderRepository.submitOrder(any(), any(), any(), any()) }

        collectJob.cancel()
    }

    @Test
    fun `MEDIUM-2 rejects scientific notation DoS price input on reviewAndSubmit`() = runTest {
        val harness = createHarness(isLive = false)
        val collectJob = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            harness.viewModel.uiState.collect {}
        }

        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.SINGLE_EQUITY)
        harness.viewModel.updateQuantity("1")
        harness.viewModel.updateLimitPrice("1e1000000")
        harness.viewModel.reviewAndSubmit()
        testScheduler.runCurrent()

        val state = harness.viewModel.uiState.value
        assertFalse(state.readyForBiometricSwipe)
        org.junit.jupiter.api.Assertions.assertNull(state.pendingRiskAssessment)
        org.junit.jupiter.api.Assertions.assertNotNull(state.globalMessage)
        io.mockk.coVerify(exactly = 0) { harness.orderRepository.submitOrder(any(), any(), any(), any()) }

        collectJob.cancel()
    }

    @Test
    fun `MEDIUM-1 structural ticket edits clear replacePriorOrderId and isEscalatedReprice while keeping Walk-Up available`() = runTest(mainDispatcher) {
        val harness = createHarness(isLive = false)
        val collectJob = backgroundScope.launch { harness.viewModel.uiState.collect {} }

        coEvery {
            harness.orderRepository.submitOrder(any(), risksAcknowledged = true, any(), any())
        } returns Result.success(
            OrderSubmissionResult(
                orderId = "SHADOW-FRESH-1",
                executionMode = ExecutionMode.SHADOW,
                status = "FILLED",
                message = "Filled",
                submittedAtEpochMs = 1_000L
            )
        )

        fun stagePriorReprice() {
            harness.stagedOrderHolder.stageDraft(
                OrderDraft(
                    strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
                    underlyingSymbol = "GOOG",
                    orderType = OrderType.LIMIT,
                    session = TradingSession.SEAMLESS,
                    duration = OrderDuration.DAY,
                    limitPrice = BigDecimal("268.00"),
                    legs = listOf(OrderLeg("GOOG", AssetCategory.EQUITY, OrderSide.SELL, 5)),
                    replacePriorOrderId = "SHADOW-OLD-77",
                    isEscalatedReprice = true
                )
            )
            advanceUntilIdle()
        }

        // 1. Editing underlying symbol clears replace metadata and unlocks Walk-Up
        stagePriorReprice()
        assertEquals("SHADOW-OLD-77", harness.viewModel.uiState.value.replacePriorOrderId)
        assertTrue(harness.viewModel.uiState.value.isEscalatedReprice)
        harness.viewModel.updateUnderlyingSymbol("AAPL")
        org.junit.jupiter.api.Assertions.assertNull(harness.viewModel.uiState.value.replacePriorOrderId)
        assertFalse(harness.viewModel.uiState.value.isEscalatedReprice)
        harness.viewModel.setWalkUpEnabled(true)
        assertTrue(harness.viewModel.uiState.value.walkUpEnabled)

        // 2. Switching side clears replace metadata
        stagePriorReprice()
        harness.viewModel.selectSingleLegSide(OrderSide.BUY)
        org.junit.jupiter.api.Assertions.assertNull(harness.viewModel.uiState.value.replacePriorOrderId)
        assertFalse(harness.viewModel.uiState.value.isEscalatedReprice)

        // 3. Switching strategy category + setting OCC symbol clears replace metadata & routes as fresh submitOrder
        stagePriorReprice()
        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.SINGLE_OPTION)
        org.junit.jupiter.api.Assertions.assertNull(harness.viewModel.uiState.value.replacePriorOrderId)
        assertFalse(harness.viewModel.uiState.value.isEscalatedReprice)

        // 4. Editing OCC option symbol on a staged option reprice clears replace metadata
        harness.stagedOrderHolder.stageDraft(
            OrderDraft(
                strategyCategory = OrderStrategyCategory.SINGLE_OPTION,
                underlyingSymbol = "GOOG",
                orderType = OrderType.LIMIT,
                session = TradingSession.NORMAL,
                duration = OrderDuration.DAY,
                limitPrice = BigDecimal("4.20"),
                legs = listOf(OrderLeg("GOOG  261218C00270000", AssetCategory.OPTION, OrderSide.SELL_TO_CLOSE, 2)),
                replacePriorOrderId = "SHADOW-OPT-88",
                isEscalatedReprice = true
            )
        )
        advanceUntilIdle()
        assertEquals("SHADOW-OPT-88", harness.viewModel.uiState.value.replacePriorOrderId)
        harness.viewModel.updateSingleOptionOccSymbol("GOOG  270115C00275000")
        org.junit.jupiter.api.Assertions.assertNull(harness.viewModel.uiState.value.replacePriorOrderId)
        assertFalse(harness.viewModel.uiState.value.isEscalatedReprice)

        stagePriorReprice()
        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.SINGLE_EQUITY)
        harness.viewModel.updateUnderlyingSymbol("SPY")
        harness.viewModel.updateLimitPrice("580.00")
        harness.viewModel.reviewAndSubmit()
        advanceUntilIdle()
        if (harness.viewModel.uiState.value.pendingRiskAssessment != null) {
            harness.viewModel.acknowledgeRisksAndContinue()
        }
        harness.viewModel.onBiometricSwipeCompleted(null)
        advanceUntilIdle()

        coVerify(exactly = 0) { harness.orderRepository.replaceOrder(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { harness.orderRepository.submitOrder(any(), risksAcknowledged = true, any(), any()) }
        collectJob.cancel()
    }

    @Test
    fun `MEDIUM-2 executionMode flip invalidates review and concurrent double-swipe submits only once`() = runTest {
        val harness = createHarness(isLive = true)
        harness.livePortfolioFlow.value = healthyLiveSnapshot()
        every { harness.biometricGate.canAuthenticate() } returns true
        val authGate = kotlinx.coroutines.CompletableDeferred<BiometricAuthResult>()
        coEvery { harness.biometricGate.authenticate(any(), any(), any()) } coAnswers { authGate.await() }
        coEvery {
            harness.orderRepository.submitOrder(any(), risksAcknowledged = true, any(), any())
        } returns Result.success(
            OrderSubmissionResult(
                orderId = "LIVE-ONCE-1",
                executionMode = ExecutionMode.LIVE,
                status = "SUBMITTED",
                message = "Submitted",
                submittedAtEpochMs = 1_000L
            )
        )

        val collectJob = backgroundScope.launch { harness.viewModel.uiState.collect {} }
        val activity = mockk<FragmentActivity>(relaxed = true)

        harness.stagedOrderHolder.stageBlankOrder(symbol = "SPY", spotPrice = 550.0)
        harness.viewModel.updateLimitPrice("550.00")
        harness.viewModel.reviewAndSubmit()
        testScheduler.runCurrent()
        if (harness.viewModel.uiState.value.pendingRiskAssessment != null) {
            harness.viewModel.acknowledgeRisksAndContinue()
        }
        assertTrue(harness.viewModel.uiState.value.readyForBiometricSwipe)

        // Flip execution mode -> review must be disarmed immediately
        harness.isLiveFlow.value = false
        harness.modeFlow.value = ExecutionMode.SHADOW
        testScheduler.runCurrent()
        assertFalse(harness.viewModel.uiState.value.readyForBiometricSwipe)
        harness.viewModel.onBiometricSwipeCompleted(activity)
        testScheduler.runCurrent()
        coVerify(exactly = 0) { harness.biometricGate.authenticate(any(), any(), any()) }

        // Flip back to LIVE, re-review cleanly, and fire two back-to-back swipes while BiometricPrompt is in flight
        harness.isLiveFlow.value = true
        harness.modeFlow.value = ExecutionMode.LIVE
        testScheduler.runCurrent()
        harness.viewModel.reviewAndSubmit()
        testScheduler.runCurrent()
        if (harness.viewModel.uiState.value.pendingRiskAssessment != null) {
            harness.viewModel.acknowledgeRisksAndContinue()
        }
        assertTrue(harness.viewModel.uiState.value.readyForBiometricSwipe)

        harness.viewModel.onBiometricSwipeCompleted(activity)
        harness.viewModel.onBiometricSwipeCompleted(activity)
        testScheduler.runCurrent()

        coVerify(exactly = 1) { harness.biometricGate.authenticate(any(), any(), any()) }
        authGate.complete(BiometricAuthResult.Succeeded)
        testScheduler.runCurrent()

        coVerify(exactly = 1) { harness.orderRepository.submitOrder(any(), risksAcknowledged = true, any(), any()) }
        collectJob.cancel()
    }

    @Test
    fun `MEDIUM-2 mode flip during BiometricPrompt aborts live submission fail-closed`() = runTest {
        val harness = createHarness(isLive = true)
        harness.livePortfolioFlow.value = healthyLiveSnapshot()
        every { harness.biometricGate.canAuthenticate() } returns true
        val authGate = kotlinx.coroutines.CompletableDeferred<BiometricAuthResult>()
        coEvery { harness.biometricGate.authenticate(any(), any(), any()) } coAnswers { authGate.await() }

        val collectJob = backgroundScope.launch { harness.viewModel.uiState.collect {} }
        val activity = mockk<FragmentActivity>(relaxed = true)

        harness.stagedOrderHolder.stageBlankOrder(symbol = "SPY", spotPrice = 550.0)
        harness.viewModel.updateLimitPrice("550.00")
        harness.viewModel.reviewAndSubmit()
        testScheduler.runCurrent()
        if (harness.viewModel.uiState.value.pendingRiskAssessment != null) {
            harness.viewModel.acknowledgeRisksAndContinue()
        }

        harness.viewModel.onBiometricSwipeCompleted(activity)
        testScheduler.runCurrent()

        // Flip mode while biometric sheet is open
        harness.isLiveFlow.value = false
        harness.modeFlow.value = ExecutionMode.SHADOW
        authGate.complete(BiometricAuthResult.Succeeded)
        testScheduler.runCurrent()

        assertFalse(harness.viewModel.uiState.value.isSubmitting)
        assertFalse(harness.viewModel.uiState.value.readyForBiometricSwipe)
        coVerify(exactly = 0) { harness.orderRepository.submitOrder(any(), any(), any(), any()) }
        collectJob.cancel()
    }

    @Test
    fun `MEDIUM-6 rejects inverted or equal OCO bracket prices pre-review`() = runTest {
        val harness = createHarness(isLive = false)
        val collectJob = backgroundScope.launch { harness.viewModel.uiState.collect {} }

        harness.stagedOrderHolder.stageBlankOrder(symbol = "GOOG", spotPrice = 270.0)
        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.OCO_BRACKET)
        harness.viewModel.updateOcoTakeProfit("255.00")
        harness.viewModel.updateOcoStopPrice("260.00")
        harness.viewModel.reviewAndSubmit()
        testScheduler.runCurrent()

        assertFalse(harness.viewModel.uiState.value.readyForBiometricSwipe)
        assertTrue(harness.viewModel.uiState.value.globalMessage?.contains("strictly greater", ignoreCase = true) == true)
        coVerify(exactly = 0) { harness.orderRepository.submitOrder(any(), any(), any(), any()) }
        collectJob.cancel()
    }

    @Test
    fun `V1-8-2 switching between option and equity clears stale quote and BPR`() = runTest {
        val harness = createHarness(isLive = false)
        val collectJob = backgroundScope.launch { harness.viewModel.uiState.collect {} }

        harness.stagedOrderHolder.stageDraft(
            OrderDraft(
                strategyCategory = OrderStrategyCategory.SINGLE_OPTION,
                underlyingSymbol = "GOOG",
                orderType = OrderType.LIMIT,
                session = TradingSession.NORMAL,
                duration = OrderDuration.DAY,
                limitPrice = BigDecimal("2.50"),
                legs = listOf(
                    OrderLeg(
                        symbol = "GOOG  261218C00280000",
                        category = AssetCategory.OPTION,
                        side = OrderSide.SELL_TO_OPEN,
                        quantity = 1,
                        underlyingSymbol = "GOOG",
                        estimatedUnitPrice = BigDecimal("2.50")
                    )
                ),
                estimatedBid = 2.40,
                estimatedMid = 2.50,
                estimatedAsk = 2.60,
                estimatedBpr = 250.0,
                sourceLabel = "Test Option Draft"
            )
        )
        testScheduler.runCurrent()

        assertEquals(2.40, harness.viewModel.uiState.value.quoteBid)
        assertEquals(2.50, harness.viewModel.uiState.value.quoteMid)
        assertEquals(2.60, harness.viewModel.uiState.value.quoteAsk)
        assertEquals(250.0, harness.viewModel.uiState.value.estimatedBpr)

        harness.viewModel.selectStrategyCategory(OrderStrategyCategory.SINGLE_EQUITY)
        testScheduler.runCurrent()

        val afterSwitch = harness.viewModel.uiState.value
        assertEquals(OrderStrategyCategory.SINGLE_EQUITY, afterSwitch.strategyCategory)
        assertEquals(null, afterSwitch.quoteBid)
        assertEquals(null, afterSwitch.quoteMid)
        assertEquals(null, afterSwitch.quoteAsk)
        assertEquals(null, afterSwitch.estimatedBpr)
        collectJob.cancel()
    }

    private data class Harness(
        val viewModel: OrderTicketViewModel,
        val stagedOrderHolder: StagedOrderHolder,
        val orderRepository: OrderRepository,
        val portfolioRepository: PortfolioRepository,
        val biometricGate: BiometricGate,
        val livePortfolioFlow: MutableStateFlow<PortfolioSnapshot?>,
        val isLiveFlow: MutableStateFlow<Boolean>,
        val modeFlow: MutableStateFlow<ExecutionMode>
    )

    private fun createHarness(
        isLive: Boolean,
        vaultAccountHash: String? = "LIVE_HASH_1"
    ): Harness {
        val stagedOrderHolder = StagedOrderHolder()
        val isLiveFlow = MutableStateFlow(isLive)
        val modeFlow = MutableStateFlow(if (isLive) ExecutionMode.LIVE else ExecutionMode.SHADOW)
        val livePortfolioFlow = MutableStateFlow<PortfolioSnapshot?>(null)
        val shadowPortfolioFlow = MutableStateFlow<PortfolioSnapshot?>(null)

        val preferences = mockk<AppPreferencesDataStore>(relaxed = true) {
            every { isLiveMode } returns isLiveFlow
            every { executionMode } returns modeFlow
        }
        val portfolioRepository = mockk<PortfolioRepository>(relaxed = true) {
            every { observePortfolioSnapshot(PortfolioDataSource.LIVE_SCHWAB_READ_ONLY) } returns livePortfolioFlow
            every { observePortfolioSnapshot(PortfolioDataSource.SHADOW_PAPER_LEDGER) } returns shadowPortfolioFlow
            coEvery { refreshShadowPortfolio() } returns Result.success(healthyLiveSnapshot())
        }
        val marketDataRepository = mockk<MarketDataRepository>(relaxed = true)
        val orderRepository = mockk<OrderRepository>(relaxed = true)
        val biometricGate = mockk<BiometricGate>(relaxed = true) {
            every { canAuthenticate() } returns false
        }
        val credentialVault = mockk<CredentialVault>(relaxed = true) {
            every { getAccessToken() } returns null
            every { getPrimaryAccountHash() } returns vaultAccountHash
        }
        val evaluatePreTradeRisksUseCase = EvaluatePreTradeRisksUseCase()
        val walkUpController = MidPriceWalkUpController(orderRepository)

        val viewModel = OrderTicketViewModel(
            stagedOrderHolder = stagedOrderHolder,
            preferences = preferences,
            portfolioRepository = portfolioRepository,
            marketDataRepository = marketDataRepository,
            evaluatePreTradeRisksUseCase = evaluatePreTradeRisksUseCase,
            orderRepository = orderRepository,
            biometricGate = biometricGate,
            credentialVault = credentialVault,
            walkUpController = walkUpController
        )
        return Harness(
            viewModel = viewModel,
            stagedOrderHolder = stagedOrderHolder,
            orderRepository = orderRepository,
            portfolioRepository = portfolioRepository,
            biometricGate = biometricGate,
            livePortfolioFlow = livePortfolioFlow,
            isLiveFlow = isLiveFlow,
            modeFlow = modeFlow
        )
    }

    private fun healthyLiveSnapshot(): PortfolioSnapshot = PortfolioSnapshot(
        dataSource = PortfolioDataSource.LIVE_SCHWAB_READ_ONLY,
        accountSummary = AccountSummary(
            accountHash = "LIVE_HASH_1",
            maskedAccountNumber = "1234",
            executionMode = ExecutionMode.LIVE,
            netLiquidationValue = BigDecimal("100000.00"),
            cashBalance = BigDecimal("40000.00"),
            settledCash = BigDecimal("40000.00"),
            cashBufferPercent = 40.0,
            buyingPower = BigDecimal("80000.00"),
            optionBuyingPower = BigDecimal("40000.00"),
            dayPnl = BigDecimal.ZERO,
            dayPnlPercent = 0.0,
            portfolioSpyBetaWeightedDelta = 10.0,
            updatedAtEpochMs = 1_000L
        ),
        positions = emptyList(),
        groups = emptyList(),
        aggregatedGreeks = AggregatedGreeks(),
        spySpot = 550.0
    )
}
