package com.schwabquant.app.presentation.sentinel

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schwabquant.app.core.security.BiometricAuthResult
import com.schwabquant.app.core.security.BiometricGate
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.DeepLinkIntentSanitizer
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStrategyCategory
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.PortfolioDataSource
import com.schwabquant.app.domain.model.PreTradeRiskAssessment
import com.schwabquant.app.domain.model.ReplaceOrderOutcome
import com.schwabquant.app.domain.model.StagedOrderHolder
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.model.WalkUpState
import com.schwabquant.app.domain.repository.MarketDataRepository
import com.schwabquant.app.domain.repository.OrderRepository
import com.schwabquant.app.domain.repository.PortfolioRepository
import com.schwabquant.app.domain.usecase.EvaluatePreTradeRisksUseCase
import com.schwabquant.app.domain.usecase.MidPriceWalkUpController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

data class OrderTicketUiState(
    val isVisible: Boolean = false,
    val executionMode: ExecutionMode = ExecutionMode.SHADOW,
    val strategyCategory: OrderStrategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
    val underlyingSymbol: String = "GOOG",
    val orderType: OrderType = OrderType.LIMIT,
    val session: TradingSession = TradingSession.SEAMLESS,
    val duration: OrderDuration = OrderDuration.DAY,
    val limitPriceInput: String = "269.50",
    val stopPriceInput: String = "",
    val ocoTakeProfitInput: String = "",
    val ocoStopPriceInput: String = "",
    val legs: List<OrderLeg> = emptyList(),
    val quantityInput: String = "1",
    val quoteBid: Double? = null,
    val quoteMid: Double? = null,
    val quoteAsk: Double? = null,
    val estimatedBpr: Double? = null,
    val sourceLabel: String = "Manual Order",
    val simulateImmediateMidFill: Boolean = true,
    val walkUpEnabled: Boolean = false,
    val walkUpAutoStep: Boolean = true,
    val walkUpCeilingInput: String = "",
    val walkUpIntervalSeconds: Int = 60,
    val walkUpState: WalkUpState = WalkUpState(),
    val replacePriorOrderId: String? = null,
    val isEscalatedReprice: Boolean = false,
    val pendingRiskAssessment: PreTradeRiskAssessment? = null,
    val readyForBiometricSwipe: Boolean = false,
    val overriddenRiskCodes: List<String> = emptyList(),
    val swipeResetCounter: Int = 0,
    val isSubmitting: Boolean = false,
    val globalMessage: String? = null
)

@HiltViewModel
class OrderTicketViewModel @Inject constructor(
    private val stagedOrderHolder: StagedOrderHolder,
    private val preferences: AppPreferencesDataStore,
    private val portfolioRepository: PortfolioRepository,
    private val marketDataRepository: MarketDataRepository,
    private val evaluatePreTradeRisksUseCase: EvaluatePreTradeRisksUseCase,
    private val orderRepository: OrderRepository,
    private val biometricGate: BiometricGate,
    private val credentialVault: CredentialVault,
    private val walkUpController: MidPriceWalkUpController
) : ViewModel() {

    private val editState = MutableStateFlow(OrderTicketUiState())
    private var currentActiveDraftMeta: OrderDraft? = null
    private var draftRevision: Long = 0L
    private var lastReviewedDraft: OrderDraft? = null
    private var lastReviewedRevision: Long = -1L
    private var lastReviewedExecutionMode: ExecutionMode? = null

    private fun invalidateReviewedDraft() {
        draftRevision++
        lastReviewedDraft = null
        lastReviewedRevision = -1L
        lastReviewedExecutionMode = null
    }

    val uiState: StateFlow<OrderTicketUiState> = combine(
        editState,
        preferences.executionMode,
        walkUpController.state
    ) { form, mode, walkUp ->
        form.copy(
            executionMode = mode,
            walkUpState = walkUp
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OrderTicketUiState()
    )

    init {
        viewModelScope.launch {
            var previousMode: ExecutionMode? = null
            preferences.executionMode.collect { mode ->
                if (previousMode != null && previousMode != mode) {
                    invalidateReviewedDraft()
                    editState.update {
                        it.copy(
                            pendingRiskAssessment = null,
                            overriddenRiskCodes = emptyList(),
                            readyForBiometricSwipe = false,
                            isSubmitting = false,
                            swipeResetCounter = it.swipeResetCounter + 1
                        )
                    }
                }
                previousMode = mode
            }
        }
        viewModelScope.launch {
            stagedOrderHolder.activeOrderDraft.collect { draft ->
                if (draft != null) {
                    hydrateFromDraft(draft)
                } else if (!walkUpController.state.value.isActive) {
                    invalidateReviewedDraft()
                    editState.value = editState.value.copy(
                        isVisible = false,
                        replacePriorOrderId = null,
                        isEscalatedReprice = false,
                        pendingRiskAssessment = null,
                        readyForBiometricSwipe = false
                    )
                }
            }
        }
    }

    private fun hydrateFromDraft(draft: OrderDraft) {
        invalidateReviewedDraft()
        currentActiveDraftMeta = draft
        val baseQty = draft.legs.minOfOrNull { it.quantity }?.coerceAtLeast(1) ?: 1
        val limitStr = draft.limitPrice?.setScale(2, RoundingMode.HALF_UP)?.toPlainString().orEmpty()
        val stopStr = draft.stopPrice?.setScale(2, RoundingMode.HALF_UP)?.toPlainString().orEmpty()
        val refPx = draft.limitPrice?.toDouble()
            ?: draft.estimatedMid
            ?: draft.legs.firstOrNull()?.estimatedUnitPrice?.toDouble()
        val ocoTpStr = draft.ocoTakeProfitPrice?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
            ?: refPx?.takeIf { it > 0.0 }?.let {
                BigDecimal.valueOf(it * 1.05).setScale(2, RoundingMode.HALF_UP).toPlainString()
            }.orEmpty()
        val ocoSlStr = draft.ocoStopPrice?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
            ?: refPx?.takeIf { it > 0.0 }?.let {
                BigDecimal.valueOf((it * 0.95).coerceAtLeast(0.01)).setScale(2, RoundingMode.HALF_UP).toPlainString()
            }.orEmpty()
        val defaultCeiling = draft.limitPrice?.let { px ->
            val isCredit = draft.orderType == OrderType.NET_CREDIT ||
                (draft.legs.size == 1 && draft.legs.first().side.name.startsWith("SELL"))
            val offset = BigDecimal("0.20")
            val c = if (isCredit) (px - offset).max(BigDecimal("0.05")) else px + offset
            c.setScale(2, RoundingMode.HALF_UP).toPlainString()
        }.orEmpty()
        val cleanPriorReplaceId = draft.replacePriorOrderId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanDuration = if (draft.orderType == OrderType.MARKET || draft.session != TradingSession.NORMAL) {
            OrderDuration.DAY
        } else {
            draft.duration
        }

        editState.value = editState.value.copy(
            isVisible = true,
            strategyCategory = draft.strategyCategory,
            underlyingSymbol = draft.underlyingSymbol.trim().uppercase(),
            orderType = draft.orderType,
            session = draft.session,
            duration = cleanDuration,
            limitPriceInput = limitStr,
            stopPriceInput = stopStr,
            ocoTakeProfitInput = ocoTpStr,
            ocoStopPriceInput = ocoSlStr,
            legs = draft.legs,
            quantityInput = baseQty.toString(),
            quoteBid = draft.estimatedBid,
            quoteMid = draft.estimatedMid,
            quoteAsk = draft.estimatedAsk,
            estimatedBpr = draft.estimatedBpr,
            sourceLabel = draft.sourceLabel,
            simulateImmediateMidFill = draft.simulateImmediateMidFill,
            walkUpEnabled = false,
            walkUpCeilingInput = defaultCeiling,
            replacePriorOrderId = cleanPriorReplaceId,
            isEscalatedReprice = draft.isEscalatedReprice,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false,
            overriddenRiskCodes = emptyList(),
            isSubmitting = false
        )

        refreshQuoteContext(draft.underlyingSymbol, draft.legs)
    }

    private fun refreshQuoteContext(underlying: String, legs: List<OrderLeg>) {
        if (!credentialVault.getAccessToken().isNullOrBlank()) {
            viewModelScope.launch {
                val symbols = (listOf(underlying) + legs.map { it.symbol }).distinct()
                marketDataRepository.getQuotes(symbols, forceRefresh = false).onSuccess { map ->
                    if (legs.size == 1) {
                        val q = map[legs.first().symbol]?.quote ?: map[underlying]?.quote
                        val bid = q?.bidPrice?.takeIf { it > 0.0 }
                        val ask = q?.askPrice?.takeIf { it > 0.0 }
                        val mid = q?.mark?.takeIf { it > 0.0 }
                            ?: if (bid != null && ask != null) (bid + ask) * 0.5 else q?.lastPrice
                        if (bid != null || mid != null || ask != null) {
                            editState.value = editState.value.copy(
                                quoteBid = bid ?: editState.value.quoteBid,
                                quoteMid = mid ?: editState.value.quoteMid,
                                quoteAsk = ask ?: editState.value.quoteAsk
                            )
                        }
                    }
                }
            }
        }
    }

    fun selectStrategyCategory(category: OrderStrategyCategory) {
        val current = editState.value
        val cleanSym = current.underlyingSymbol.trim().uppercase().ifEmpty { "GOOG" }
        val updatedOrderType = when (category) {
            OrderStrategyCategory.DIAGONAL,
            OrderStrategyCategory.CALENDAR -> OrderType.NET_DEBIT
            OrderStrategyCategory.IRON_CONDOR -> OrderType.NET_CREDIT
            OrderStrategyCategory.VERTICAL -> if (current.orderType == OrderType.NET_DEBIT) OrderType.NET_DEBIT else OrderType.NET_CREDIT
            OrderStrategyCategory.SINGLE_EQUITY,
            OrderStrategyCategory.SINGLE_OPTION,
            OrderStrategyCategory.OCO_BRACKET -> OrderType.LIMIT
        }
        val updatedSession = if (category == OrderStrategyCategory.SINGLE_EQUITY) {
            current.session
        } else {
            TradingSession.NORMAL
        }
        val updatedDuration = when {
            category == OrderStrategyCategory.OCO_BRACKET -> OrderDuration.GOOD_TILL_CANCEL
            category == OrderStrategyCategory.SINGLE_OPTION ||
                category == OrderStrategyCategory.DIAGONAL ||
                category == OrderStrategyCategory.VERTICAL ||
                category == OrderStrategyCategory.CALENDAR ||
                category == OrderStrategyCategory.IRON_CONDOR -> OrderDuration.DAY
            current.strategyCategory == OrderStrategyCategory.OCO_BRACKET -> OrderDuration.DAY
            updatedSession != TradingSession.NORMAL -> OrderDuration.DAY
            updatedOrderType == OrderType.MARKET -> OrderDuration.DAY
            else -> current.duration
        }
        val qty = current.quantityInput.toIntOrNull()?.coerceAtLeast(1) ?: 1

        // H-2 + Guard 5 + H-6: Bidirectional conversion between EQUITY (incl. BUY_TO_COVER) and OPTION legs
        val updatedLegs = when (category) {
            OrderStrategyCategory.SINGLE_EQUITY,
            OrderStrategyCategory.OCO_BRACKET -> {
                val priorSide = current.legs.firstOrNull()?.side
                val equitySide = when {
                    category == OrderStrategyCategory.OCO_BRACKET -> OrderSide.SELL
                    priorSide == OrderSide.BUY_TO_COVER -> OrderSide.BUY_TO_COVER
                    priorSide == OrderSide.SELL ||
                        priorSide == OrderSide.SELL_TO_OPEN ||
                        priorSide == OrderSide.SELL_TO_CLOSE -> OrderSide.SELL
                    else -> OrderSide.BUY
                }
                listOf(
                    OrderLeg(
                        symbol = cleanSym,
                        category = AssetCategory.EQUITY,
                        side = equitySide,
                        quantity = qty,
                        underlyingSymbol = cleanSym,
                        estimatedUnitPrice = current.limitPriceInput.toBigDecimalOrNull()
                    )
                )
            }

            OrderStrategyCategory.SINGLE_OPTION -> {
                val first = current.legs.firstOrNull()
                if (first != null && first.category == AssetCategory.OPTION) {
                    val optSide = if (first.side in OPTION_SIDES) first.side else OrderSide.BUY_TO_OPEN
                    listOf(first.copy(category = AssetCategory.OPTION, side = optSide, quantity = qty))
                } else {
                    val mappedOptSide = when (first?.side) {
                        OrderSide.SELL, OrderSide.SELL_TO_OPEN, OrderSide.SELL_TO_CLOSE -> OrderSide.SELL_TO_OPEN
                        else -> OrderSide.BUY_TO_OPEN
                    }
                    listOf(
                        OrderLeg(
                            symbol = "",
                            category = AssetCategory.OPTION,
                            side = mappedOptSide,
                            quantity = qty,
                            underlyingSymbol = cleanSym,
                            estimatedUnitPrice = current.limitPriceInput.toBigDecimalOrNull()
                        )
                    )
                }
            }

            else -> current.legs.ifEmpty {
                listOf(
                    OrderLeg(
                        symbol = cleanSym,
                        category = AssetCategory.OPTION,
                        side = OrderSide.BUY_TO_OPEN,
                        quantity = qty,
                        underlyingSymbol = cleanSym
                    )
                )
            }
        }

        val refPx = current.quoteMid
            ?: current.limitPriceInput.toDoubleOrNull()
            ?: current.quoteBid
            ?: current.quoteAsk
        val derivedOcoTp = if (category == OrderStrategyCategory.OCO_BRACKET && current.ocoTakeProfitInput.isBlank() && refPx != null && refPx > 0.0) {
            BigDecimal.valueOf(refPx * 1.05).setScale(2, RoundingMode.HALF_UP).toPlainString()
        } else {
            current.ocoTakeProfitInput
        }
        val derivedOcoSl = if (category == OrderStrategyCategory.OCO_BRACKET && current.ocoStopPriceInput.isBlank() && refPx != null && refPx > 0.0) {
            BigDecimal.valueOf((refPx * 0.95).coerceAtLeast(0.01)).setScale(2, RoundingMode.HALF_UP).toPlainString()
        } else {
            current.ocoStopPriceInput
        }
        val walkUpAllowed = isWalkUpAllowed(category, updatedOrderType, null)
        invalidateReviewedDraft()

        editState.value = current.copy(
            strategyCategory = category,
            orderType = updatedOrderType,
            session = updatedSession,
            duration = updatedDuration,
            ocoTakeProfitInput = derivedOcoTp,
            ocoStopPriceInput = derivedOcoSl,
            legs = updatedLegs,
            walkUpEnabled = if (walkUpAllowed) current.walkUpEnabled else false,
            replacePriorOrderId = null,
            isEscalatedReprice = false,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun updateUnderlyingSymbol(sym: String) {
        val clean = sym.trim().uppercase()
        val s = editState.value
        val updatedLegs = if (s.legs.size == 1 && s.legs.first().category == AssetCategory.EQUITY) {
            listOf(s.legs.first().copy(symbol = clean, underlyingSymbol = clean))
        } else {
            s.legs
        }
        val walkUpAllowed = isWalkUpAllowed(s.strategyCategory, s.orderType, null)
        invalidateReviewedDraft()
        editState.value = s.copy(
            underlyingSymbol = clean,
            ocoTakeProfitInput = "",
            ocoStopPriceInput = "",
            legs = updatedLegs,
            walkUpEnabled = if (walkUpAllowed) s.walkUpEnabled else false,
            replacePriorOrderId = null,
            isEscalatedReprice = false,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun selectSingleLegSide(side: OrderSide) {
        val s = editState.value
        val updatedLegs = if (s.legs.isNotEmpty()) {
            listOf(s.legs.first().copy(side = side)) + s.legs.drop(1)
        } else {
            s.legs
        }
        val walkUpAllowed = isWalkUpAllowed(s.strategyCategory, s.orderType, null)
        invalidateReviewedDraft()
        editState.value = s.copy(
            legs = updatedLegs,
            walkUpEnabled = if (walkUpAllowed) s.walkUpEnabled else false,
            replacePriorOrderId = null,
            isEscalatedReprice = false,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun updateSingleOptionOccSymbol(occ: String) {
        val s = editState.value
        val cleanSym = s.underlyingSymbol.trim().uppercase().ifEmpty { "GOOG" }
        val qty = s.quantityInput.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val existing = s.legs.firstOrNull()
        val validSide = existing?.side?.takeIf { it in OPTION_SIDES } ?: OrderSide.BUY_TO_OPEN
        val updatedFirst = existing?.copy(
            symbol = occ.uppercase(),
            category = AssetCategory.OPTION,
            side = validSide,
            underlyingSymbol = cleanSym
        ) ?: OrderLeg(
            symbol = occ.uppercase(),
            category = AssetCategory.OPTION,
            side = validSide,
            quantity = qty,
            underlyingSymbol = cleanSym
        )
        val walkUpAllowed = isWalkUpAllowed(s.strategyCategory, s.orderType, null)
        invalidateReviewedDraft()
        editState.value = s.copy(
            legs = listOf(updatedFirst) + s.legs.drop(1),
            walkUpEnabled = if (walkUpAllowed) s.walkUpEnabled else false,
            replacePriorOrderId = null,
            isEscalatedReprice = false,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun updateOrderType(orderType: OrderType) {
        val s = editState.value
        val validSession = if (orderType != OrderType.LIMIT) TradingSession.NORMAL else s.session
        val validDuration = if (orderType == OrderType.MARKET || validSession != TradingSession.NORMAL) {
            OrderDuration.DAY
        } else {
            s.duration
        }
        val walkUpAllowed = isWalkUpAllowed(s.strategyCategory, orderType, s.replacePriorOrderId)
        invalidateReviewedDraft()
        editState.value = s.copy(
            orderType = orderType,
            session = validSession,
            duration = validDuration,
            walkUpEnabled = if (walkUpAllowed) s.walkUpEnabled else false,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun updateSession(session: TradingSession) {
        val s = editState.value
        // Schwab Section 4A: Extended-hours (AM/PM/SEAMLESS) must enforce LIMIT + DAY
        val effectiveType = if (session != TradingSession.NORMAL) OrderType.LIMIT else s.orderType
        val effectiveDuration = if (session != TradingSession.NORMAL || effectiveType == OrderType.MARKET) {
            OrderDuration.DAY
        } else {
            s.duration
        }
        invalidateReviewedDraft()
        editState.value = s.copy(
            session = session,
            orderType = effectiveType,
            duration = effectiveDuration,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun updateDuration(duration: OrderDuration) {
        val s = editState.value
        val effectiveDuration = if (s.session != TradingSession.NORMAL || s.orderType == OrderType.MARKET) {
            OrderDuration.DAY
        } else {
            duration
        }
        invalidateReviewedDraft()
        editState.value = s.copy(
            duration = effectiveDuration,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun updateQuantity(qty: String) {
        val clean = qty.filter { it.isDigit() }
        val intQty = clean.toIntOrNull()?.coerceAtLeast(1) ?: 1
        invalidateReviewedDraft()
        editState.value = editState.value.copy(
            quantityInput = clean,
            legs = editState.value.legs.map { it.copy(quantity = intQty) },
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun updateLimitPrice(px: String) {
        invalidateReviewedDraft()
        editState.value = editState.value.copy(
            limitPriceInput = px,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun updateStopPrice(px: String) {
        invalidateReviewedDraft()
        editState.value = editState.value.copy(
            stopPriceInput = px,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun updateOcoTakeProfit(px: String) {
        invalidateReviewedDraft()
        editState.value = editState.value.copy(
            ocoTakeProfitInput = px,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun updateOcoStopPrice(px: String) {
        invalidateReviewedDraft()
        editState.value = editState.value.copy(
            ocoStopPriceInput = px,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun setImmediateMidFill(enabled: Boolean) {
        invalidateReviewedDraft()
        editState.value = editState.value.copy(
            simulateImmediateMidFill = enabled,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun setWalkUpEnabled(enabled: Boolean) {
        val s = editState.value
        val walkUpAllowed = isWalkUpAllowed(s.strategyCategory, s.orderType, s.replacePriorOrderId)
        val effectiveEnabled = enabled && walkUpAllowed
        invalidateReviewedDraft()
        editState.value = s.copy(
            walkUpEnabled = effectiveEnabled,
            simulateImmediateMidFill = if (effectiveEnabled) false else s.simulateImmediateMidFill,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun setWalkUpAutoStep(auto: Boolean) {
        invalidateReviewedDraft()
        editState.value = editState.value.copy(
            walkUpAutoStep = auto,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun updateWalkUpCeiling(px: String) {
        invalidateReviewedDraft()
        editState.value = editState.value.copy(
            walkUpCeilingInput = px,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    fun reviewAndSubmit() {
        val currentForm = editState.value
        val validationError = validateOrderPrices(currentForm)
        if (validationError != null) {
            editState.value = currentForm.copy(
                pendingRiskAssessment = null,
                readyForBiometricSwipe = false,
                globalMessage = validationError
            )
            return
        }

        val revisionAtStart = draftRevision
        val draft = buildCurrentDraft()
        viewModelScope.launch {
            val isLiveAtStart = preferences.isLiveMode.first()
            val modeAtStart = if (isLiveAtStart) ExecutionMode.LIVE else ExecutionMode.SHADOW
            val source = if (isLiveAtStart) {
                PortfolioDataSource.LIVE_SCHWAB_READ_ONLY
            } else {
                PortfolioDataSource.SHADOW_PAPER_LEDGER
            }
            var snapshot = portfolioRepository.observePortfolioSnapshot(source).first()
            // M-4 Safeguard: Auto-fetch live portfolio telemetry before claiming 0 risk warnings
            if (isLiveAtStart && snapshot == null) {
                snapshot = portfolioRepository.refreshLivePortfolio().getOrNull()
            }
            val assessment = evaluatePreTradeRisksUseCase(
                draft = draft,
                snapshot = snapshot,
                requirePortfolioTelemetry = isLiveAtStart
            )
            val currentModeNow = if (preferences.isLiveMode.first()) ExecutionMode.LIVE else ExecutionMode.SHADOW
            // MEDIUM-2 Safeguard: Discard stale risk assessment if any form field or execution mode mutated in flight
            if (draftRevision != revisionAtStart || currentModeNow != modeAtStart) {
                invalidateReviewedDraft()
                editState.update {
                    it.copy(
                        pendingRiskAssessment = null,
                        overriddenRiskCodes = emptyList(),
                        readyForBiometricSwipe = false
                    )
                }
                return@launch
            }
            lastReviewedDraft = draft
            lastReviewedRevision = revisionAtStart
            lastReviewedExecutionMode = modeAtStart
            if (assessment.hasWarnings) {
                editState.value = editState.value.copy(
                    pendingRiskAssessment = assessment,
                    readyForBiometricSwipe = false
                )
            } else {
                editState.value = editState.value.copy(
                    pendingRiskAssessment = null,
                    overriddenRiskCodes = emptyList(),
                    readyForBiometricSwipe = true
                )
            }
        }
    }

    fun dismissRiskDialog() {
        editState.value = editState.value.copy(pendingRiskAssessment = null)
    }

    fun acknowledgeRisksAndContinue() {
        val currentForm = editState.value
        if (draftRevision != lastReviewedRevision || lastReviewedDraft == null || lastReviewedExecutionMode == null) {
            editState.value = currentForm.copy(
                pendingRiskAssessment = null,
                overriddenRiskCodes = emptyList(),
                readyForBiometricSwipe = false,
                globalMessage = "Order ticket changed after review; tap Review & Submit again."
            )
            return
        }
        val codes = currentForm.pendingRiskAssessment?.overriddenCodes.orEmpty()
        editState.value = currentForm.copy(
            pendingRiskAssessment = null,
            overriddenRiskCodes = codes,
            readyForBiometricSwipe = true
        )
    }

    fun onBiometricSwipeCompleted(activity: FragmentActivity?) {
        val current = editState.value
        if (current.isSubmitting) return

        val expectedRevision = lastReviewedRevision
        val reviewedDraft = lastReviewedDraft
        val reviewedMode = lastReviewedExecutionMode
        if (reviewedDraft == null || reviewedMode == null || draftRevision != expectedRevision) {
            editState.value = current.copy(
                pendingRiskAssessment = null,
                overriddenRiskCodes = emptyList(),
                readyForBiometricSwipe = false,
                swipeResetCounter = current.swipeResetCounter + 1,
                globalMessage = "Order ticket modified after risk review; tap Review & Submit again."
            )
            return
        }

        // Set isSubmitting synchronously BEFORE launching coroutine to prevent rapid double-swipe races
        editState.value = current.copy(isSubmitting = true)

        viewModelScope.launch {
            val isLiveBeforeAuth = preferences.isLiveMode.first()
            val modeBeforeAuth = if (isLiveBeforeAuth) ExecutionMode.LIVE else ExecutionMode.SHADOW
            if (modeBeforeAuth != reviewedMode || draftRevision != expectedRevision) {
                invalidateReviewedDraft()
                editState.value = editState.value.copy(
                    isSubmitting = false,
                    pendingRiskAssessment = null,
                    overriddenRiskCodes = emptyList(),
                    readyForBiometricSwipe = false,
                    swipeResetCounter = editState.value.swipeResetCounter + 1,
                    globalMessage = "Execution mode or ticket changed after review; tap Review & Submit again."
                )
                return@launch
            }

            val authPassed = if (biometricGate.canAuthenticate()) {
                if (activity == null) {
                    editState.value = editState.value.copy(
                        isSubmitting = false,
                        swipeResetCounter = editState.value.swipeResetCounter + 1,
                        globalMessage = "Unable to resolve FragmentActivity for BiometricPrompt."
                    )
                    return@launch
                }
                val promptTitle = if (isLiveBeforeAuth) "Authorize LIVE Schwab Order" else "Authorize SHADOW Paper Order"
                val promptSubtitle = "${current.underlyingSymbol} ${current.strategyCategory.displayName}"
                when (val result = biometricGate.authenticate(activity, promptTitle, promptSubtitle)) {
                    is BiometricAuthResult.Succeeded -> true
                    is BiometricAuthResult.Cancelled -> {
                        editState.value = editState.value.copy(
                            isSubmitting = false,
                            swipeResetCounter = editState.value.swipeResetCounter + 1,
                            globalMessage = "Biometric confirmation canceled; swipe reset."
                        )
                        false
                    }
                    is BiometricAuthResult.Failed -> {
                        editState.value = editState.value.copy(
                            isSubmitting = false,
                            swipeResetCounter = editState.value.swipeResetCounter + 1,
                            globalMessage = "Biometric verification failed: ${result.message}"
                        )
                        false
                    }
                }
            } else if (isLiveBeforeAuth) {
                // Never bypass Biometric verification for Live orders
                editState.value = editState.value.copy(
                    isSubmitting = false,
                    swipeResetCounter = editState.value.swipeResetCounter + 1,
                    globalMessage = "Live Mode blocked: enroll device biometrics or screen lock in Android Settings."
                )
                false
            } else {
                // Shadow Mode only: explicit fallback when device/emulator has no enrolled biometrics
                true
            }

            if (!authPassed) return@launch

            val isLive = preferences.isLiveMode.first()
            val modeAfterAuth = if (isLive) ExecutionMode.LIVE else ExecutionMode.SHADOW
            if (draftRevision != expectedRevision || modeAfterAuth != reviewedMode) {
                invalidateReviewedDraft()
                editState.value = editState.value.copy(
                    isSubmitting = false,
                    pendingRiskAssessment = null,
                    overriddenRiskCodes = emptyList(),
                    readyForBiometricSwipe = false,
                    swipeResetCounter = editState.value.swipeResetCounter + 1,
                    globalMessage = "Order ticket or execution mode changed during biometric confirmation; review again."
                )
                return@launch
            }

            val draft = reviewedDraft
            val mode = reviewedMode
            // Guard 1: Never pass "SHADOW_PAPER" onto a Live ticket
            val accountHash = if (isLive) {
                credentialVault.getPrimaryAccountHash()
                    ?.trim()
                    ?.takeUnless { it.equals("SHADOW_PAPER", ignoreCase = true) }
                    .orEmpty()
            } else {
                "SHADOW_PAPER"
            }

            val ticket = runCatching {
                draft.toOrderTicket(accountHash = accountHash, executionMode = mode)
            }.getOrElse { err ->
                editState.value = editState.value.copy(
                    isSubmitting = false,
                    swipeResetCounter = editState.value.swipeResetCounter + 1,
                    globalMessage = err.message ?: "Invalid order parameters."
                )
                return@launch
            }

            // C-4 Safeguard: ALWAYS execute replaceOrder FIRST when replacePriorOrderId is set (never bypass to Walk-Up or bare submitOrder)
            val priorOrderId = ticket.replacePriorOrderId?.trim()?.takeIf { it.isNotEmpty() }
            if (priorOrderId != null) {
                orderRepository.replaceOrder(
                    accountHash = accountHash,
                    oldOrderId = priorOrderId,
                    replacementTicket = ticket,
                    risksAcknowledged = true,
                    overriddenRiskCodes = current.overriddenRiskCodes,
                    allowTerminalOrPartialReprice = current.isEscalatedReprice
                ).onSuccess { outcome ->
                    if (!isLive) {
                        portfolioRepository.refreshShadowPortfolio()
                    }
                    when (outcome) {
                        is ReplaceOrderOutcome.Replaced -> {
                            stagedOrderHolder.clear()
                            editState.value = editState.value.copy(
                                isVisible = false,
                                isSubmitting = false,
                                replacePriorOrderId = null,
                                readyForBiometricSwipe = false,
                                swipeResetCounter = editState.value.swipeResetCounter + 1,
                                globalMessage = "Replaced #$priorOrderId -> ${outcome.result.orderId}: ${outcome.result.message}"
                            )
                        }
                        is ReplaceOrderOutcome.AbortedAlreadyFilled -> {
                            stagedOrderHolder.clear()
                            editState.value = editState.value.copy(
                                isVisible = false,
                                isSubmitting = false,
                                replacePriorOrderId = null,
                                readyForBiometricSwipe = false,
                                swipeResetCounter = editState.value.swipeResetCounter + 1,
                                globalMessage = "Re-price aborted (double-sell prevented): prior order #$priorOrderId reported ${outcome.priorStatus}."
                            )
                        }
                        is ReplaceOrderOutcome.AbortedNotCanceled -> {
                            editState.value = editState.value.copy(
                                isSubmitting = false,
                                swipeResetCounter = editState.value.swipeResetCounter + 1,
                                globalMessage = "Re-price aborted: prior order #$priorOrderId remained '${outcome.priorStatus}' after cancel."
                            )
                        }
                    }
                }.onFailure { err ->
                    editState.value = editState.value.copy(
                        isSubmitting = false,
                        swipeResetCounter = editState.value.swipeResetCounter + 1,
                        globalMessage = err.message ?: "Cancel-and-replace failed."
                    )
                }
                return@launch
            }

            if (current.walkUpEnabled) {
                val validatedLimit = ticket.limitPrice?.takeIf { it > BigDecimal.ZERO }
                val ceiling = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(current.walkUpCeilingInput)
                if (validatedLimit == null || ceiling == null) {
                    editState.value = editState.value.copy(
                        isSubmitting = false,
                        readyForBiometricSwipe = false,
                        swipeResetCounter = editState.value.swipeResetCounter + 1,
                        globalMessage = "Walk-Up requires valid positive limit price and max ceiling."
                    )
                    return@launch
                }
                walkUpController.startWalkUp(
                    scope = viewModelScope,
                    initialTicket = ticket,
                    hardMaxCeiling = ceiling,
                    intervalSeconds = current.walkUpIntervalSeconds,
                    autoStep = current.walkUpAutoStep,
                    overriddenRiskCodes = current.overriddenRiskCodes,
                    onTerminalEvent = { msg ->
                        viewModelScope.launch {
                            if (!isLive) portfolioRepository.refreshShadowPortfolio()
                            editState.value = editState.value.copy(globalMessage = msg)
                        }
                    }
                )
                editState.value = editState.value.copy(
                    isSubmitting = false,
                    swipeResetCounter = editState.value.swipeResetCounter + 1,
                    globalMessage = "Mid-Price Walk-Up armed ($${validatedLimit} -> ceiling $$ceiling)."
                )
                return@launch
            }

            orderRepository.submitOrder(
                ticket = ticket,
                risksAcknowledged = true,
                overriddenRiskCodes = current.overriddenRiskCodes,
                simulateImmediateMidFill = current.simulateImmediateMidFill
            ).onSuccess { result ->
                if (!isLive) {
                    portfolioRepository.refreshShadowPortfolio()
                }
                stagedOrderHolder.clear()
                editState.value = editState.value.copy(
                    isVisible = false,
                    isSubmitting = false,
                    readyForBiometricSwipe = false,
                    swipeResetCounter = editState.value.swipeResetCounter + 1,
                    globalMessage = "${result.executionMode.name} ${result.status}: ${result.message}"
                )
            }.onFailure { err ->
                editState.value = editState.value.copy(
                    isSubmitting = false,
                    swipeResetCounter = editState.value.swipeResetCounter + 1,
                    globalMessage = err.message ?: "Order submission failed."
                )
            }
        }
    }

    fun confirmManualWalkUpStep() {
        walkUpController.confirmNextManualStep()
    }

    fun cancelWalkUp() {
        walkUpController.cancelWalkUp()
    }

    fun dismissSheet() {
        invalidateReviewedDraft()
        stagedOrderHolder.clear()
        editState.value = editState.value.copy(
            isVisible = false,
            replacePriorOrderId = null,
            isEscalatedReprice = false,
            pendingRiskAssessment = null,
            readyForBiometricSwipe = false
        )
    }

    private fun validateOrderPrices(form: OrderTicketUiState): String? {
        val qty = form.quantityInput.toIntOrNull()
        if (qty == null || qty <= 0) {
            return "Quantity must be a positive whole number."
        }
        if (form.strategyCategory == OrderStrategyCategory.OCO_BRACKET) {
            val tp = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(form.ocoTakeProfitInput)
            val sl = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(form.ocoStopPriceInput)
            if (tp == null) {
                return "Enter a valid positive OCO take-profit limit price."
            }
            if (sl == null) {
                return "Enter a valid positive OCO stop price."
            }
            val side = form.legs.firstOrNull()?.side ?: OrderSide.SELL
            val roundedTp = tp.setScale(2, RoundingMode.HALF_UP)
            val roundedSl = sl.setScale(2, RoundingMode.HALF_UP)
            if (side == OrderSide.SELL) {
                if (roundedTp <= roundedSl) {
                    return "OCO SELL take-profit limit ($roundedTp) must be strictly greater than stop price ($roundedSl)."
                }
            } else {
                if (roundedTp >= roundedSl) {
                    return "OCO ${side.name} take-profit limit ($roundedTp) must be strictly less than stop price ($roundedSl)."
                }
            }
            return null
        }
        val needsLimit = form.orderType in setOf(
            OrderType.LIMIT,
            OrderType.NET_DEBIT,
            OrderType.NET_CREDIT,
            OrderType.STOP_LIMIT
        )
        if (needsLimit) {
            val limit = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(form.limitPriceInput)
            if (limit == null) {
                return "Enter a valid positive limit price before reviewing risks."
            }
        }
        if (form.orderType == OrderType.STOP || form.orderType == OrderType.STOP_LIMIT) {
            val stop = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(form.stopPriceInput)
            if (stop == null) {
                return "Enter a valid positive stop price before reviewing risks."
            }
        }
        if (form.walkUpEnabled) {
            val ceiling = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(form.walkUpCeilingInput)
            if (ceiling == null) {
                return "Enter a valid positive Mid-Price Walk-Up ceiling."
            }
        }
        return null
    }

    fun consumeGlobalMessage() {
        editState.value = editState.value.copy(globalMessage = null)
    }

    private fun isWalkUpAllowed(
        category: OrderStrategyCategory,
        orderType: OrderType,
        replacePriorOrderId: String?
    ): Boolean = replacePriorOrderId.isNullOrBlank() &&
        category != OrderStrategyCategory.OCO_BRACKET &&
        orderType in setOf(OrderType.LIMIT, OrderType.NET_DEBIT, OrderType.NET_CREDIT)

    private fun buildCurrentDraft(): OrderDraft {
        val s = editState.value
        val base = currentActiveDraftMeta
        val qty = s.quantityInput.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val cleanUnderlying = s.underlyingSymbol.trim().uppercase().ifEmpty { "GOOG" }
        val legs = s.legs.map { leg ->
            val legSym = if (leg.category == AssetCategory.EQUITY) {
                leg.symbol.trim().uppercase().ifEmpty { cleanUnderlying }
            } else {
                leg.symbol
            }
            leg.copy(symbol = legSym, quantity = qty, underlyingSymbol = cleanUnderlying)
        }.ifEmpty {
            listOf(
                OrderLeg(
                    symbol = cleanUnderlying,
                    category = AssetCategory.EQUITY,
                    side = OrderSide.BUY,
                    quantity = qty,
                    underlyingSymbol = cleanUnderlying
                )
            )
        }
        val limitBd = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(s.limitPriceInput)
            ?: s.limitPriceInput.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
        val stopBd = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(s.stopPriceInput)
            ?: s.stopPriceInput.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
        val ocoTpBd = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(s.ocoTakeProfitInput)
            ?: s.ocoTakeProfitInput.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
        val ocoSlBd = DeepLinkIntentSanitizer.parseStrictPositiveDecimal(s.ocoStopPriceInput)
            ?: s.ocoStopPriceInput.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)

        val computedBpr = when {
            s.strategyCategory == OrderStrategyCategory.SINGLE_EQUITY && limitBd != null ->
                limitBd.toDouble() * qty
            else -> s.estimatedBpr ?: limitBd?.toDouble()?.times(100.0 * qty)
        }

        val effectiveDuration = if (s.orderType == OrderType.MARKET || s.session != TradingSession.NORMAL) {
            OrderDuration.DAY
        } else {
            s.duration
        }

        return OrderDraft(
            strategyCategory = s.strategyCategory,
            underlyingSymbol = cleanUnderlying,
            orderType = s.orderType,
            session = s.session,
            duration = effectiveDuration,
            limitPrice = limitBd,
            stopPrice = stopBd,
            ocoTakeProfitPrice = ocoTpBd,
            ocoStopPrice = ocoSlBd,
            legs = legs,
            estimatedBid = s.quoteBid,
            estimatedMid = s.quoteMid,
            estimatedAsk = s.quoteAsk,
            estimatedBpr = computedBpr,
            knownEarningsDateIso = base?.knownEarningsDateIso,
            sourceLabel = s.sourceLabel,
            simulateImmediateMidFill = s.simulateImmediateMidFill,
            replacePriorOrderId = s.replacePriorOrderId,
            isEscalatedReprice = s.isEscalatedReprice
        )
    }

    companion object {
        private val OPTION_SIDES = setOf(
            OrderSide.BUY_TO_OPEN,
            OrderSide.SELL_TO_OPEN,
            OrderSide.BUY_TO_CLOSE,
            OrderSide.SELL_TO_CLOSE
        )
    }
}
