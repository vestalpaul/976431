package com.schwabquant.app.domain.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton

enum class StrategyFilterChip(val label: String) {
    ALL("All"),
    PMCC("PMCC"),
    PMCP("PMCP"),
    VERTICALS("Verticals"),
    CALENDARS("Calendars"),
    IRON_CONDORS("Iron Condors"),
    WHEEL_CSP("Wheel/CSP")
}

enum class OptionStrategyType(
    val displayName: String,
    val shortLabel: String,
    val filterChip: StrategyFilterChip
) {
    PMCC("PMCC (Call Diagonal)", "PMCC", StrategyFilterChip.PMCC),
    PMCP("PMCP (Put Diagonal)", "PMCP", StrategyFilterChip.PMCP),
    VERTICAL_BULL_PUT("Bull Put Credit Spread", "Bull Put", StrategyFilterChip.VERTICALS),
    VERTICAL_BEAR_CALL("Bear Call Credit Spread", "Bear Call", StrategyFilterChip.VERTICALS),
    CALENDAR("Calendar Spread", "Calendar", StrategyFilterChip.CALENDARS),
    IRON_CONDOR("Iron Condor", "Iron Condor", StrategyFilterChip.IRON_CONDORS),
    WHEEL_CSP("Wheel / Cash-Secured Put", "Wheel/CSP", StrategyFilterChip.WHEEL_CSP)
}

enum class CapitalFilterTier(
    val label: String,
    val maxCapitalDollars: Double?
) {
    ALL("All", null),
    K2_5("$2.5k", 2_500.0),
    K5("$5k", 5_000.0),
    K7_5("$7.5k", 7_500.0),
    K10("$10k", 10_000.0),
    K15("$15k", 15_000.0),
    K20("$20k", 20_000.0)
}

enum class LegAction(
    val schwabInstruction: String,
    val shortLabel: String
) {
    BUY_TO_OPEN("BUY_TO_OPEN", "BTO"),
    SELL_TO_OPEN("SELL_TO_OPEN", "STO")
}

data class StrategyLeg(
    val occSymbol: String,
    val action: LegAction,
    val quantity: Int,
    val isCall: Boolean,
    val strike: Double,
    val expirationIsoDate: String,
    val dte: Int,
    val bid: Double,
    val ask: Double,
    val mid: Double,
    val delta: Double,
    val gamma: Double,
    val thetaPerDay: Double,
    val vegaPer1Pct: Double,
    val impliedVolatility: Double,
    val openInterest: Int,
    val volume: Int,
    val spreadWidthPct: Double
)

enum class EarningsCheckStatus(val label: String) {
    NO_EARNINGS_ETF("No Earnings (ETF)"),
    VERIFY_EARNINGS("Verify Earnings Calendar")
}

data class QuantCardMetrics(
    val netDebitOrCreditPerShare: Double,
    val isNetCredit: Boolean,
    val buyingPowerRequirement: Double,
    val maxProfit: Double?,
    val maxLoss: Double,
    val breakevens: List<Double>,
    val widthRatioPct: Double?,
    val widthRatioLabel: String?,
    val popPct: Double,
    val netDelta: Double,
    val netThetaPerDay: Double,
    val netGamma: Double,
    val netVegaPer1Pct: Double,
    val frontIv: Double,
    val backIv: Double?,
    val ivTermSpread: Double?,
    val volRank52w: Double?,
    val annualizedRocPct: Double?,
    val distanceBelowSpotPct: Double?,
    val minOpenInterest: Int,
    val maxBidAskSpreadPct: Double,
    val earningsStatus: EarningsCheckStatus,
    val earningsDetail: String
)

data class StrategyExplainer(
    val whyItWorksNow: String,
    val failureModesAndRules: List<String>,
    val warnings: List<String>,
    val geminiMarkdownPacket: String
)

data class StrategyCandidate(
    val id: String,
    val underlyingSymbol: String,
    val underlyingSpot: Double,
    val strategyType: OptionStrategyType,
    val compositeScore: Int,
    val regimeFitLabel: String,
    val edgeTag: String,
    val legs: List<StrategyLeg>,
    val metrics: QuantCardMetrics,
    val explainer: StrategyExplainer,
    val isDemoData: Boolean = false
)

data class ScanProgress(
    val completed: Int,
    val total: Int,
    val currentTicker: String
)

data class ScannerFilterConfig(
    val minDte: Int = 21,
    val maxDte: Int = 60,
    val minShortDelta: Double = 0.20,
    val maxShortDelta: Double = 0.35,
    val minOpenInterest: Int = 100
)

/**
 * Shared order-staging bridge feeding MainScaffold's global OrderTicketSheet from:
 * - Milestone 3 StrategyDetailSheet (`Stage Order`)
 * - Milestone 4 Portfolio Advisor (`BTC 50%`, `Roll / Close Short`, `Close / Defend Short Call`)
 * - Manual `+ New Order` buttons on Dashboard and Sentinel
 */
@Singleton
class StagedOrderHolder @Inject constructor() {
    private val _stagedCandidate = MutableStateFlow<StrategyCandidate?>(null)
    val stagedCandidate: StateFlow<StrategyCandidate?> = _stagedCandidate.asStateFlow()

    private val _activeOrderDraft = MutableStateFlow<OrderDraft?>(null)
    val activeOrderDraft: StateFlow<OrderDraft?> = _activeOrderDraft.asStateFlow()

    fun stage(candidate: StrategyCandidate) {
        _stagedCandidate.value = candidate
        _activeOrderDraft.value = candidate.toOrderDraft()
    }

    fun stageDraft(draft: OrderDraft) {
        _stagedCandidate.value = null
        _activeOrderDraft.value = draft
    }

    fun stageBlankOrder(symbol: String = "GOOG", spotPrice: Double = 270.0) {
        _stagedCandidate.value = null
        val bdPrice = BigDecimal.valueOf(spotPrice).setScale(2, RoundingMode.HALF_UP)
        _activeOrderDraft.value = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = symbol.trim().uppercase().ifEmpty { "GOOG" },
            orderType = OrderType.LIMIT,
            session = TradingSession.SEAMLESS,
            duration = OrderDuration.DAY,
            limitPrice = bdPrice,
            legs = listOf(
                OrderLeg(
                    symbol = symbol.trim().uppercase().ifEmpty { "GOOG" },
                    category = AssetCategory.EQUITY,
                    side = OrderSide.SELL,
                    quantity = 1,
                    underlyingSymbol = symbol.trim().uppercase().ifEmpty { "GOOG" },
                    estimatedUnitPrice = bdPrice
                )
            ),
            estimatedBid = (spotPrice - 0.10).coerceAtLeast(0.01),
            estimatedMid = spotPrice,
            estimatedAsk = spotPrice + 0.10,
            estimatedBpr = spotPrice,
            sourceLabel = "Manual Order"
        )
    }

    fun clear() {
        _stagedCandidate.value = null
        _activeOrderDraft.value = null
    }
}

private fun StrategyCandidate.toOrderDraft(): OrderDraft {
    val category = when (strategyType) {
        OptionStrategyType.PMCC,
        OptionStrategyType.PMCP -> OrderStrategyCategory.DIAGONAL
        OptionStrategyType.VERTICAL_BULL_PUT,
        OptionStrategyType.VERTICAL_BEAR_CALL -> OrderStrategyCategory.VERTICAL
        OptionStrategyType.CALENDAR -> OrderStrategyCategory.CALENDAR
        OptionStrategyType.IRON_CONDOR -> OrderStrategyCategory.IRON_CONDOR
        OptionStrategyType.WHEEL_CSP -> OrderStrategyCategory.SINGLE_OPTION
    }
    val orderType = when {
        category == OrderStrategyCategory.SINGLE_OPTION -> OrderType.LIMIT
        metrics.isNetCredit -> OrderType.NET_CREDIT
        else -> OrderType.NET_DEBIT
    }
    val limitBd = BigDecimal.valueOf(metrics.netDebitOrCreditPerShare)
        .setScale(2, RoundingMode.HALF_UP)

    val netBid = legs.sumOf { leg ->
        if (leg.action == LegAction.SELL_TO_OPEN) {
            if (metrics.isNetCredit) leg.bid else -leg.ask
        } else {
            if (metrics.isNetCredit) -leg.ask else leg.bid
        }
    }
    val netAsk = legs.sumOf { leg ->
        if (leg.action == LegAction.SELL_TO_OPEN) {
            if (metrics.isNetCredit) leg.ask else -leg.bid
        } else {
            if (metrics.isNetCredit) -leg.bid else leg.ask
        }
    }

    return OrderDraft(
        strategyCategory = category,
        underlyingSymbol = underlyingSymbol,
        orderType = orderType,
        session = TradingSession.NORMAL,
        duration = OrderDuration.DAY,
        limitPrice = limitBd,
        legs = legs.map { leg ->
            OrderLeg(
                symbol = leg.occSymbol,
                category = AssetCategory.OPTION,
                side = when (leg.action) {
                    LegAction.BUY_TO_OPEN -> OrderSide.BUY_TO_OPEN
                    LegAction.SELL_TO_OPEN -> OrderSide.SELL_TO_OPEN
                },
                quantity = leg.quantity,
                underlyingSymbol = underlyingSymbol,
                estimatedUnitPrice = BigDecimal.valueOf(leg.mid).setScale(2, RoundingMode.HALF_UP),
                strike = leg.strike,
                expirationIsoDate = leg.expirationIsoDate,
                dte = leg.dte,
                isCall = leg.isCall,
                delta = leg.delta
            )
        },
        estimatedBid = minOf(netBid, netAsk).coerceAtLeast(0.01),
        estimatedMid = metrics.netDebitOrCreditPerShare,
        estimatedAsk = maxOf(netBid, netAsk).coerceAtLeast(0.01),
        estimatedBpr = metrics.buyingPowerRequirement,
        sourceLabel = "Scanner: ${strategyType.shortLabel}"
    )
}
