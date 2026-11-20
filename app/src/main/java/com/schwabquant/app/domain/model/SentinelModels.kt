package com.schwabquant.app.domain.model

import com.schwabquant.app.data.remote.streamer.StreamerConnectionState
import com.schwabquant.app.domain.usecase.MarketSessionWindow
import java.math.BigDecimal

enum class SentinelRuleType(
    val displayName: String,
    val shortTag: String
) {
    LONG_STOP_LOSS("Long Stop-Loss (Bid)", "LONG STOP"),
    SHORT_STOP_LOSS("Short Stop-Loss (Ask)", "SHORT STOP"),
    TAKE_PROFIT("Take-Profit Target", "TAKE PROFIT"),
    DIP_BUY_ANTI_KNIFE("Dip Buy (Anti-Knife)", "ANTI-KNIFE"),
    OVERNIGHT_PRICE_ALERT("Overnight Price Alert", "OVERNIGHT ALERT")
}

enum class SentinelRuleStatus(val label: String) {
    ARMED("ARMED"),
    PAUSED("PAUSED"),
    EXECUTING("EXECUTING"),
    TRIGGERED_FILLED("TRIGGERED_FILLED"),
    UNFILLED_ESCALATED("UNFILLED_ESCALATED"),
    ABORTED_NO_POSITION("ABORTED_NO_POSITION"),
    ALERTED("ALERTED")
}

enum class MicrostructureEvalOutcome(val badgeLabel: String) {
    WATCHING("WATCHING"),
    IGNORED_BAD_QUOTE_OR_HALTED("HALTED / BAD QUOTE"),
    SPREAD_BLOWOUT_SUPPRESSED("SPREAD SUPPRESSED"),
    ARMED_WARNING("ARMED WARNING"),
    ANTI_KNIFE_FALLING_BLOCKED("FALLING KNIFE"),
    CONFIRMED_EXECUTE("CONFIRMED EXECUTE"),
    CONFIRMED_DIP_ALERT("DIP STABILIZED"),
    OVERNIGHT_ALERT_ONLY("OVERNIGHT ALERT")
}

data class SentinelRule(
    val id: Long = 0L,
    val symbol: String,
    val category: AssetCategory = AssetCategory.EQUITY,
    val ruleType: SentinelRuleType,
    val triggerPrice: BigDecimal,
    val quantity: Int,
    val slippageCollarPct: Double = 0.35,
    val holdSeconds: Int = 15,
    val minConsecutiveTicks: Int = 3,
    val maxSpreadPct: Double = 1.5,
    val sweepBufferPct: Double = 0.0,
    val antiKnifeLookbackMinutes: Int = 10,
    val antiKnifeBouncePct: Double = 0.25,
    val isShortPosition: Boolean = false,
    val session: TradingSession = TradingSession.SEAMLESS,
    val status: SentinelRuleStatus = SentinelRuleStatus.ARMED,
    val executionMode: ExecutionMode = ExecutionMode.SHADOW,
    val lastTriggerNote: String? = null,
    val lastSubmittedOrderId: String? = null,
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L
) {
    val isAutomatedExitRule: Boolean
        get() = ruleType == SentinelRuleType.LONG_STOP_LOSS ||
            ruleType == SentinelRuleType.SHORT_STOP_LOSS ||
            ruleType == SentinelRuleType.TAKE_PROFIT
}

data class RuleRuntimeTelemetry(
    val ruleId: Long,
    val outcome: MicrostructureEvalOutcome = MicrostructureEvalOutcome.WATCHING,
    val elapsedHoldSeconds: Int = 0,
    val consecutiveTicks: Int = 0,
    val bid: Double? = null,
    val ask: Double? = null,
    val spreadPct: Double? = null,
    val detailMessage: String = ""
)

data class SentinelServiceStatus(
    val isServiceRunning: Boolean = false,
    val connectionState: StreamerConnectionState = StreamerConnectionState.DISCONNECTED,
    val armedRuleCount: Int = 0,
    val lastHeartbeatEpochMs: Long? = null,
    val activeSession: MarketSessionWindow = MarketSessionWindow.OVERNIGHT_EXTO,
    val disconnectReason: String? = null
)
