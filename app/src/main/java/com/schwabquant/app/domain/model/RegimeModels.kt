package com.schwabquant.app.domain.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class SignalStatus(
    val label: String,
    val weight: Double
) {
    GREEN("GREEN", 0.0),
    AMBER("AMBER", 0.5),
    RED("RED", 1.0)
}

enum class MarketRegime(
    val label: String,
    val cashReserveTarget: String,
    val shortDeltaGuidance: String
) {
    RISK_ON_BULL(
        label = "Risk-On Bull",
        cashReserveTarget = "12%–15%",
        shortDeltaGuidance = "0.25–0.32Δ"
    ),
    CAUTION_ELEVATED_VOL(
        label = "Caution — Elevated Vol",
        cashReserveTarget = "20%–25%",
        shortDeltaGuidance = "0.20–0.25Δ"
    ),
    CONFIRMED_BEAR(
        label = "Confirmed Bear",
        cashReserveTarget = ">= 30%",
        shortDeltaGuidance = "0.18–0.22Δ (Bear Call / PMCP)"
    )
}

enum class BearSignalId(
    val number: Int,
    val title: String,
    val tickerTag: String
) {
    PRIMARY_TREND_SPY(1, "Primary Trend", "SPY"),
    SMALL_CAP_CANARY_IWM(2, "Small-Cap Canary", "IWM / SPY"),
    VOLATILITY_LEVEL_VIX(3, "Volatility Level", "\$VIX"),
    VOL_TERM_STRUCTURE(4, "Vol Term Structure", "\$VIX / \$VIX3M"),
    CREDIT_STRESS_HYG_LQD(5, "Credit Stress", "HYG / LQD"),
    MARKET_BREADTH_25(6, "Market Breadth", "Core 25 > 50-DMA")
}

data class BearSignalEvaluation(
    val id: BearSignalId,
    val status: SignalStatus,
    val currentValueFormatted: String,
    val referenceFormatted: String,
    val deltaFromTriggerFormatted: String,
    val interpretation: String
)

data class PortfolioDirective(
    val headline: String,
    val cashReserveTarget: String,
    val favoredStrategies: List<String>,
    val haltOrAvoidStrategies: List<String>,
    val shortDeltaGuidance: String,
    val actionBullets: List<String>
)

enum class DemoRegimePreset(val label: String) {
    BULL("Bull (0 Red)"),
    CAUTION("Caution (2 Red)"),
    BEAR("Bear (5 Red)")
}

data class RegimeEvaluationInput(
    val spyCloses: List<Double>,
    val iwmCloses: List<Double>,
    val vixCloses: List<Double>,
    val vix3mLatest: Double?,
    val hygCloses: List<Double>,
    val lqdCloses: List<Double>,
    val universeCloses: Map<String, List<Double>>,
    val isDemoData: Boolean = false,
    val demoPreset: DemoRegimePreset? = null,
    val evaluatedAtEpochMs: Long = System.currentTimeMillis()
)

data class BearChecklistEvaluation(
    val regime: MarketRegime,
    val redCount: Int,
    val amberCount: Int,
    val weightedScore: Double,
    val totalSignals: Int = 6,
    val signals: List<BearSignalEvaluation>,
    val directive: PortfolioDirective,
    val isDemoData: Boolean = false,
    val demoPreset: DemoRegimePreset? = null,
    val evaluatedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Shared in-memory holder broadcasting the most recent Bear-Market 6-Signal evaluation
 * to ScannerScreen and Dashboard Gemini handoff without re-hitting Schwab endpoints.
 */
@Singleton
class RegimeStateHolder @Inject constructor() {
    private val _currentEvaluation = MutableStateFlow<BearChecklistEvaluation?>(null)
    val currentEvaluation: StateFlow<BearChecklistEvaluation?> = _currentEvaluation.asStateFlow()

    fun update(evaluation: BearChecklistEvaluation) {
        _currentEvaluation.value = evaluation
    }
}
