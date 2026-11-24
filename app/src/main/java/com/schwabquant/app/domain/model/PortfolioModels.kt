package com.schwabquant.app.domain.model

import com.schwabquant.app.core.quant.BetaSource
import com.schwabquant.app.core.quant.StressMatrixSummary
import java.math.BigDecimal

/**
 * Execution mode enforced across portfolio routing, order submission, and audit logging.
 * Shadow Mode (paper trading) is ALWAYS the default.
 */
enum class ExecutionMode {
    SHADOW,
    LIVE
}

enum class PortfolioDataSource(val label: String) {
    LIVE_SCHWAB_READ_ONLY("Live Account"),
    SHADOW_PAPER_LEDGER("Paper Ledger")
}

enum class AssetCategory {
    EQUITY,
    OPTION,
    CASH,
    INDEX
}

enum class PortfolioAnomalyType {
    LEAPS_LOW_DELTA,
    CONCENTRATION_OVER_25_PCT,
    CASH_BUFFER_BELOW_12_PCT,
    BETA_FALLBACK,
    NEGATIVE_SKEW,
    UPSIDE_CAPPED
}

data class PortfolioAnomaly(
    val type: PortfolioAnomalyType,
    val symbol: String?,
    val message: String
)

data class AggregatedGreeks(
    val spyBetaWeightedDelta: Double = 0.0,
    val netDailyTheta: Double = 0.0,
    val netGamma: Double = 0.0,
    val netVegaPer1Pct: Double = 0.0
)

data class AccountSummary(
    val accountHash: String,
    val maskedAccountNumber: String,
    val executionMode: ExecutionMode,
    val netLiquidationValue: BigDecimal,
    val cashBalance: BigDecimal,
    val settledCash: BigDecimal = cashBalance,
    val cashBufferPercent: Double = 0.0,
    val buyingPower: BigDecimal,
    val optionBuyingPower: BigDecimal,
    val openPnl: BigDecimal = BigDecimal.ZERO,
    val openPnlPercent: Double = 0.0,
    val dayPnl: BigDecimal,
    val dayPnlPercent: Double,
    val portfolioSpyBetaWeightedDelta: Double,
    val updatedAtEpochMs: Long
)

data class Position(
    val symbol: String,
    val underlyingSymbol: String,
    val category: AssetCategory,
    val quantity: BigDecimal,
    val averageCost: BigDecimal,
    val markPrice: BigDecimal,
    val marketValue: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val unrealizedPnlPercent: Double,
    val dayPnl: BigDecimal = BigDecimal.ZERO,
    val delta: Double? = null,
    val gamma: Double? = null,
    val theta: Double? = null,
    val vega: Double? = null,
    val betaToSpy: Double? = null,
    val betaSource: BetaSource = BetaSource.FUNDAMENTAL,
    val isCall: Boolean? = null,
    val strike: Double? = null,
    val expirationDate: String? = null,
    val daysToExpiration: Int? = null,
    val underlyingSpot: Double? = null,
    val impliedVolatility: Double? = null,
    val extrinsicValue: Double? = null,
    val divDate: String? = null,
    val divAmount: Double? = null,
    val divYield: Double? = null,
    val greeksFromBlackScholesFallback: Boolean = false,
    val isShadowPosition: Boolean = false
)

data class UnderlyingPositionGroup(
    val underlyingSymbol: String,
    val underlyingSpot: Double,
    val betaToSpy: Double,
    val totalMarketValue: BigDecimal,
    val weightPercent: Double,
    val combinedOpenPnl: BigDecimal,
    val combinedDayPnl: BigDecimal,
    val groupNetDelta: Double,
    val groupNetTheta: Double,
    val legs: List<Position>,
    val anomalies: List<PortfolioAnomaly> = emptyList(),
    val betaSource: BetaSource = BetaSource.FUNDAMENTAL
)

data class PortfolioSnapshot(
    val dataSource: PortfolioDataSource,
    val accountSummary: AccountSummary,
    val positions: List<Position>,
    val groups: List<UnderlyingPositionGroup>,
    val aggregatedGreeks: AggregatedGreeks,
    val spySpot: Double,
    val portfolioAnomalies: List<PortfolioAnomaly> = emptyList(),
    val stressSummary: StressMatrixSummary? = null,
    val isDemoSeeded: Boolean = false
)
