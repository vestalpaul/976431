package com.schwabquant.app.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class OptimizerStrategyType(
    val displayName: String,
    val shortLabel: String,
    val styleTag: String,
    val orderStrategyCategory: OrderStrategyCategory
) {
    PMCC("PMCC (Call Diagonal)", "PMCC", "Bullish Diagonal", OrderStrategyCategory.DIAGONAL),
    PMCP("PMCP (Put Diagonal)", "PMCP", "Bearish Diagonal", OrderStrategyCategory.DIAGONAL),
    BULL_PUT_VERTICAL("Bull Put Credit Spread", "Bull Put", "Bullish Credit", OrderStrategyCategory.VERTICAL),
    BEAR_CALL_VERTICAL("Bear Call Credit Spread", "Bear Call", "Bearish Credit", OrderStrategyCategory.VERTICAL),
    CALENDAR("Calendar Spread", "Calendar", "Volatility / Time", OrderStrategyCategory.CALENDAR),
    IRON_CONDOR("Iron Condor", "Iron Condor", "Range-Bound Credit", OrderStrategyCategory.IRON_CONDOR),
    WHEEL_CSP("Wheel / Cash-Secured Put", "Wheel / CSP", "Income / Discount", OrderStrategyCategory.SINGLE_OPTION)
}

enum class OptimizerRiskProfile(
    val label: String,
    val shortCallDeltaTarget: Double,
    val shortPutDeltaTarget: Double,
    val wingShortDeltaTarget: Double
) {
    CONSERVATIVE("Conservative", 0.20, -0.20, 0.16),
    BALANCED("Balanced", 0.28, -0.28, 0.20),
    AGGRESSIVE("Aggressive", 0.36, -0.36, 0.26)
}

enum class OptimizerCapitalPreset(
    val label: String,
    val fixedLimitDollars: Double?
) {
    ALL("All", null),
    SAFE_CAP("Safe Cap", null),
    K2_5("$2.5k", 2_500.0),
    K5("$5k", 5_000.0),
    K10("$10k", 10_000.0),
    K15("$15k", 15_000.0),
    K20("$20k", 20_000.0)
}

enum class OptimizerRegimeFit(val label: String) {
    ALIGNED("Aligned"),
    NEUTRAL("Neutral"),
    COUNTER_REGIME_CAUTION("Counter-Regime Caution")
}

data class StrategyRankingBreakdown(
    val deltaDteScore: Double,
    val structuralEdgeScore: Double,
    val liquidityScore: Double,
    val regimeCapitalScore: Double
) {
    val compositeScore: Double = round1(
        deltaDteScore + structuralEdgeScore + liquidityScore + regimeCapitalScore
    )

    companion object {
        val ZERO = StrategyRankingBreakdown(0.0, 0.0, 0.0, 0.0)

        fun of(
            deltaDte: Double,
            structuralEdge: Double,
            liquidity: Double,
            regimeCapital: Double
        ): StrategyRankingBreakdown = StrategyRankingBreakdown(
            deltaDteScore = round1(deltaDte.coerceIn(0.0, 30.0)),
            structuralEdgeScore = round1(structuralEdge.coerceIn(0.0, 35.0)),
            liquidityScore = round1(liquidity.coerceIn(0.0, 20.0)),
            regimeCapitalScore = round1(regimeCapital.coerceIn(0.0, 15.0))
        )

        fun round1(value: Double): Double = (value * 10.0).roundToInt() / 10.0
    }
}

data class ChartStrategyOverlayLine(
    val price: Double,
    val label: String,
    val isBreakeven: Boolean
)

data class OptimizerLeg(
    val occSymbol: String,
    val action: LegAction,
    val quantity: Int,
    val isCall: Boolean,
    val strike: Double,
    val expirationDate: LocalDate,
    val dte: Int,
    val bid: Double,
    val ask: Double,
    val mid: Double,
    val delta: Double,
    val gamma: Double,
    val thetaPerDay: Double,
    val vegaPer1Pct: Double,
    val iv: Double,
    val openInterest: Int,
    val spreadWidthPct: Double
) {
    val expirationIsoDate: String get() = expirationDate.toString()

    fun toOrderLeg(underlyingSymbol: String): OrderLeg = OrderLeg(
        symbol = occSymbol,
        category = AssetCategory.OPTION,
        side = when (action) {
            LegAction.BUY_TO_OPEN -> OrderSide.BUY_TO_OPEN
            LegAction.SELL_TO_OPEN -> OrderSide.SELL_TO_OPEN
        },
        quantity = quantity,
        underlyingSymbol = underlyingSymbol,
        estimatedUnitPrice = BigDecimal.valueOf(max(0.01, mid)).setScale(2, RoundingMode.HALF_UP),
        strike = strike,
        expirationIsoDate = expirationIsoDate,
        dte = dte,
        isCall = isCall,
        delta = delta
    )
}

data class OptimizedStrategyCandidate(
    val id: String,
    val underlyingSymbol: String,
    val underlyingSpot: Double,
    val strategyType: OptimizerStrategyType,
    val legs: List<OptimizerLeg>,
    val breakdown: StrategyRankingBreakdown,
    val regimeFit: OptimizerRegimeFit,
    val orderType: OrderType,
    val netDebitOrCreditPerShare: Double,
    val isNetCredit: Boolean,
    val estimatedBid: Double,
    val estimatedAsk: Double,
    val buyingPowerRequirement: Double,
    val maxProfit: Double?,
    val maxLoss: Double,
    val breakevens: List<Double>,
    val popPct: Double,
    val netDelta: Double,
    val netThetaPerDay: Double,
    val netGamma: Double,
    val netVegaPer1Pct: Double,
    val minOpenInterest: Int,
    val maxSpreadWidthPct: Double,
    val legRationaleBullets: List<String>,
    val summaryLine: String
) {
    fun toOrderDraft(): OrderDraft {
        val safeMid = max(0.01, netDebitOrCreditPerShare)
        val limitBd = BigDecimal.valueOf(safeMid).setScale(2, RoundingMode.HALF_UP)
        val safeBid = max(0.01, minOf(estimatedBid, estimatedAsk))
        val safeAsk = max(safeBid, maxOf(estimatedBid, estimatedAsk))
        return OrderDraft(
            strategyCategory = strategyType.orderStrategyCategory,
            underlyingSymbol = underlyingSymbol,
            orderType = orderType,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = limitBd,
            legs = legs.map { it.toOrderLeg(underlyingSymbol) },
            estimatedBid = safeBid,
            estimatedMid = safeMid,
            estimatedAsk = safeAsk,
            estimatedBpr = buyingPowerRequirement,
            sourceLabel = "Optimizer: ${strategyType.shortLabel}",
            simulateImmediateMidFill = true,
            replacePriorOrderId = null,
            isEscalatedReprice = false
        )
    }

    fun toChartOverlayLines(): List<ChartStrategyOverlayLine> {
        val lines = mutableListOf<ChartStrategyOverlayLine>()
        legs.distinctBy { "${it.action.shortLabel}:${it.isCall}:${it.strike}" }.forEach { leg ->
            val sideTag = if (leg.action == LegAction.BUY_TO_OPEN) "BTO" else "STO"
            val cpTag = if (leg.isCall) "C" else "P"
            lines.add(
                ChartStrategyOverlayLine(
                    price = leg.strike,
                    label = "$sideTag ${leg.strike.toInt()}$cpTag",
                    isBreakeven = false
                )
            )
        }
        breakevens.forEachIndexed { idx, be ->
            if (be.isFinite() && be > 0.0) {
                val prefix = if (breakevens.size > 1) "BE${idx + 1}" else "BE"
                lines.add(
                    ChartStrategyOverlayLine(
                        price = (be * 100.0).roundToInt() / 100.0,
                        label = prefix,
                        isBreakeven = true
                    )
                )
            }
        }
        return lines.distinctBy { "${it.label}:${((it.price * 100.0).roundToInt())}" }
    }
}

data class StrategyOptimizationResult(
    val rank: Int,
    val strategyType: OptimizerStrategyType,
    val optimalCandidate: OptimizedStrategyCandidate?,
    val runnerUpCandidates: List<OptimizedStrategyCandidate>,
    val compositeScore: Double,
    val breakdown: StrategyRankingBreakdown,
    val regimeFit: OptimizerRegimeFit,
    val rankingExplanation: String,
    val noValidSetupReason: String?
)

data class SingleTickerOptimizationReport(
    val symbol: String,
    val underlyingSpot: Double,
    val activeRegime: MarketRegime,
    val volRank52w: Double?,
    val sma50: Double?,
    val riskProfile: OptimizerRiskProfile,
    val maxCapitalBudget: Double?,
    val rankedStrategies: List<StrategyOptimizationResult>,
    val evaluatedAtEpochMs: Long,
    val isDemoData: Boolean = false
)
