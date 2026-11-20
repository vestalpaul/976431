package com.schwabquant.app.domain.usecase

import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.data.remote.streamer.LiveEquityQuote
import com.schwabquant.app.data.remote.streamer.StreamerConnectionState
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStrategyCategory
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.domain.model.TradingSession
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class OvernightAlertPayload(
    val ruleId: Long,
    val symbol: String,
    val title: String,
    val body: String,
    val actionLabel: String,
    val stagedOrderDraft: OrderDraft,
    val isDipBuyAlert: Boolean = false,
    val executionMode: ExecutionMode = ExecutionMode.SHADOW
)

/**
 * Pure-JVM builder for Overnight EXTO Alert-Only payloads, Biometric-Gated Anti-Knife Dip Buy
 * alerts, and Unfilled-Escalation re-price drafts. Never submits API orders directly.
 */
@Singleton
class OvernightAlertBuilder @Inject constructor() {

    fun buildOvernightAlert(
        rule: SentinelRule,
        quote: LiveEquityQuote
    ): OvernightAlertPayload {
        val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(rule.symbol)
        val side = when {
            rule.ruleType == SentinelRuleType.SHORT_STOP_LOSS ||
                (rule.ruleType == SentinelRuleType.TAKE_PROFIT && rule.isShortPosition) -> OrderSide.BUY_TO_COVER
            rule.ruleType == SentinelRuleType.DIP_BUY_ANTI_KNIFE -> OrderSide.BUY
            else -> OrderSide.SELL
        }

        val refPrice = when (side) {
            OrderSide.SELL -> quote.bidPrice ?: quote.markPrice ?: quote.lastPrice ?: rule.triggerPrice.toDouble()
            else -> quote.askPrice ?: quote.markPrice ?: quote.lastPrice ?: rule.triggerPrice.toDouble()
        }.coerceAtLeast(0.01)

        val collar = rule.slippageCollarPct.coerceAtLeast(0.0) / 100.0
        val rawLimit = if (side == OrderSide.SELL) {
            refPrice * (1.0 - collar)
        } else {
            refPrice * (1.0 + collar)
        }.coerceAtLeast(0.01)
        val limitBd = BigDecimal.valueOf(rawLimit).setScale(2, RoundingMode.HALF_UP)
        val qty = rule.quantity.coerceAtLeast(1)

        val draft = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = displaySym,
            orderType = OrderType.LIMIT,
            session = TradingSession.SEAMLESS,
            duration = OrderDuration.DAY,
            limitPrice = limitBd,
            legs = listOf(
                OrderLeg(
                    symbol = displaySym,
                    category = AssetCategory.EQUITY,
                    side = side,
                    quantity = qty,
                    underlyingSymbol = displaySym,
                    estimatedUnitPrice = limitBd
                )
            ),
            estimatedBid = quote.bidPrice ?: refPrice,
            estimatedMid = quote.midPrice ?: refPrice,
            estimatedAsk = quote.askPrice ?: refPrice,
            estimatedBpr = limitBd.toDouble() * qty,
            sourceLabel = "Overnight Alert ($displaySym) — 7:00 AM Queue",
            simulateImmediateMidFill = false
        )

        return OvernightAlertPayload(
            ruleId = rule.id,
            symbol = displaySym,
            title = "Overnight Alert: $displaySym @ $${fmt2(refPrice)}",
            body = "${rule.ruleType.displayName} crossed $${rule.triggerPrice.toPlainString()} during Overnight EXTO. Zero API order sent — tap to queue 7:00 AM SEAMLESS LIMIT (${side.name} ${qty}x @ $${limitBd.toPlainString()}).",
            actionLabel = "Queue 7:00 AM Pre-Market Limit",
            stagedOrderDraft = draft,
            isDipBuyAlert = false,
            executionMode = rule.executionMode
        )
    }

    /**
     * Safeguard #2: DIP_BUY_ANTI_KNIFE entries are strictly biometric-gated and NEVER auto-submitted.
     */
    fun buildDipBuyStagingPayload(
        rule: SentinelRule,
        quote: LiveEquityQuote
    ): OvernightAlertPayload {
        val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(rule.symbol)
        val refPrice = (quote.askPrice ?: quote.bidPrice ?: quote.markPrice ?: rule.triggerPrice.toDouble())
            .coerceAtLeast(0.01)
        val collar = rule.slippageCollarPct.coerceAtLeast(0.0) / 100.0
        val limitBd = BigDecimal.valueOf((refPrice * (1.0 + collar)).coerceAtLeast(0.01))
            .setScale(2, RoundingMode.HALF_UP)
        val qty = rule.quantity.coerceAtLeast(1)

        val draft = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = displaySym,
            orderType = OrderType.LIMIT,
            session = TradingSession.SEAMLESS,
            duration = OrderDuration.DAY,
            limitPrice = limitBd,
            legs = listOf(
                OrderLeg(
                    symbol = displaySym,
                    category = AssetCategory.EQUITY,
                    side = OrderSide.BUY,
                    quantity = qty,
                    underlyingSymbol = displaySym,
                    estimatedUnitPrice = limitBd
                )
            ),
            estimatedBid = quote.bidPrice ?: refPrice,
            estimatedMid = quote.midPrice ?: refPrice,
            estimatedAsk = quote.askPrice ?: refPrice,
            estimatedBpr = limitBd.toDouble() * qty,
            sourceLabel = "Anti-Knife Dip Buy ($displaySym) — Biometric Review",
            simulateImmediateMidFill = true
        )

        return OvernightAlertPayload(
            ruleId = rule.id,
            symbol = displaySym,
            title = "Anti-Knife Stabilized: $displaySym @ $${fmt2(refPrice)}",
            body = "Bounce confirmed off rolling low. Tap to review Soft-Gate risks & biometric-confirm BUY ${qty}x $displaySym SEAMLESS LIMIT @ $${limitBd.toPlainString()}.",
            actionLabel = "Review & Biometric Buy",
            stagedOrderDraft = draft,
            isDipBuyAlert = true,
            executionMode = rule.executionMode
        )
    }

    fun buildUnfilledRepriceDraft(
        rule: SentinelRule,
        quote: LiveEquityQuote?,
        priorOrderId: String,
        overrideQuantity: Int? = null,
        heldPositionWholeShares: Int? = null
    ): OrderDraft {
        val displaySym = SchwabSymbolNormalizer.toDisplaySymbol(rule.symbol)
        val expectsShortCover = rule.ruleType == SentinelRuleType.SHORT_STOP_LOSS ||
            (rule.ruleType == SentinelRuleType.TAKE_PROFIT && rule.isShortPosition)
        val side = if (expectsShortCover) OrderSide.BUY_TO_COVER else OrderSide.SELL
        val refPrice = when (side) {
            OrderSide.SELL -> quote?.bidPrice ?: quote?.markPrice ?: rule.triggerPrice.toDouble()
            else -> quote?.askPrice ?: quote?.markPrice ?: rule.triggerPrice.toDouble()
        }.coerceAtLeast(0.01)

        val widerCollar = (rule.slippageCollarPct.coerceAtLeast(0.35) * 2.0) / 100.0
        val rawLimit = if (side == OrderSide.SELL) {
            refPrice * (1.0 - widerCollar)
        } else {
            refPrice * (1.0 + widerCollar)
        }.coerceAtLeast(0.01)
        val limitBd = BigDecimal.valueOf(rawLimit).setScale(2, RoundingMode.HALF_UP)
        val baseQty = (overrideQuantity ?: rule.quantity).coerceAtLeast(1)
        val qty = if (heldPositionWholeShares != null && heldPositionWholeShares > 0) {
            minOf(baseQty, heldPositionWholeShares)
        } else {
            baseQty
        }.coerceAtLeast(1)
        val trackablePriorId = sanitizeTrackableOrderId(priorOrderId)
            ?: sanitizeTrackableOrderId(rule.lastSubmittedOrderId)

        return OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = displaySym,
            orderType = OrderType.LIMIT,
            session = TradingSession.SEAMLESS,
            duration = OrderDuration.DAY,
            limitPrice = limitBd,
            legs = listOf(
                OrderLeg(
                    symbol = displaySym,
                    category = AssetCategory.EQUITY,
                    side = side,
                    quantity = qty,
                    underlyingSymbol = displaySym,
                    estimatedUnitPrice = limitBd
                )
            ),
            estimatedBid = quote?.bidPrice ?: refPrice,
            estimatedMid = quote?.midPrice ?: refPrice,
            estimatedAsk = quote?.askPrice ?: refPrice,
            estimatedBpr = limitBd.toDouble() * qty,
            sourceLabel = "Re-Price Escalated Exit ($displaySym #${trackablePriorId ?: priorOrderId})",
            simulateImmediateMidFill = false,
            replacePriorOrderId = trackablePriorId,
            isEscalatedReprice = true
        )
    }

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    companion object {
        private val NON_BROKER_PLACEHOLDER_IDS = setOf(
            "REFRESH_FAILED",
            "PRE_SUBMIT_ABORT",
            "ESCALATED",
            "UNTRACKED",
            "LIVE-SUBMITTED"
        )
        private val TRACKABLE_ORDER_ID_REGEX = Regex("^[A-Za-z0-9-]+$")

        fun sanitizeTrackableOrderId(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.uppercase(Locale.US) in NON_BROKER_PLACEHOLDER_IDS) return null
            if (!TRACKABLE_ORDER_ID_REGEX.matches(trimmed) || !trimmed.any { it.isDigit() }) return null
            return trimmed
        }
    }
}

data class DisconnectAlarmEvent(
    val reason: String,
    val outageDurationMs: Long,
    val armedAutomatedRuleCount: Int,
    val connectionState: StreamerConnectionState
)

/**
 * Pure-JVM stateful Disconnect Alarm Watchdog.
 * Fires an alarm strictly ONCE per live-streaming outage while armed automated rules exist,
 * resetting cleanly as soon as the Streamer re-authenticates with a fresh heartbeat.
 */
class DisconnectWatchdogEvaluator(
    private val maxSilenceMs: Long = DEFAULT_MAX_SILENCE_MS
) {
    @Inject
    constructor() : this(DEFAULT_MAX_SILENCE_MS)

    private var alarmFiredForCurrentOutage: Boolean = false
    private var outageStartedEpochMs: Long? = null

    fun reset() {
        alarmFiredForCurrentOutage = false
        outageStartedEpochMs = null
    }

    fun evaluate(
        hasArmedAutomatedRules: Boolean,
        isLiveStreamingActive: Boolean,
        connectionState: StreamerConnectionState,
        lastHeartbeatEpochMs: Long,
        nowEpochMs: Long,
        armedAutomatedRuleCount: Int = 1
    ): DisconnectAlarmEvent? {
        if (!hasArmedAutomatedRules ||
            !isLiveStreamingActive ||
            connectionState == StreamerConnectionState.SIMULATED
        ) {
            reset()
            return null
        }

        val heartbeatAgeMs = if (lastHeartbeatEpochMs > 0L) {
            (nowEpochMs - lastHeartbeatEpochMs).coerceAtLeast(0L)
        } else {
            null
        }

        val isHealthy = connectionState == StreamerConnectionState.AUTHENTICATED &&
            heartbeatAgeMs != null &&
            heartbeatAgeMs <= maxSilenceMs

        if (isHealthy) {
            reset()
            return null
        }

        if (alarmFiredForCurrentOutage) {
            return null
        }

        // Immediate terminal alarm on Schwab Error 12 (duplicate socket conflict)
        if (connectionState == StreamerConnectionState.ERROR_CONFLICT_CLOSE_12) {
            alarmFiredForCurrentOutage = true
            return DisconnectAlarmEvent(
                reason = "Schwab Error 12 (CLOSE_CONNECTION): Another session evicted the Sentinel socket. $armedAutomatedRuleCount automated rule(s) unmonitored.",
                outageDurationMs = heartbeatAgeMs ?: 0L,
                armedAutomatedRuleCount = armedAutomatedRuleCount,
                connectionState = connectionState
            )
        }

        val startMs = outageStartedEpochMs ?: nowEpochMs
        outageStartedEpochMs = startMs
        val elapsedOutageMs = heartbeatAgeMs ?: (nowEpochMs - startMs).coerceAtLeast(0L)

        if (elapsedOutageMs > maxSilenceMs) {
            alarmFiredForCurrentOutage = true
            val sec = elapsedOutageMs / 1_000L
            val reason = if (connectionState == StreamerConnectionState.AUTHENTICATED) {
                "Schwab Streamer heartbeat silent for ${sec}s (> ${maxSilenceMs / 1_000L}s). $armedAutomatedRuleCount automated rule(s) may be stale."
            } else {
                "Schwab Streamer ${connectionState.name} for ${sec}s. $armedAutomatedRuleCount automated rule(s) unmonitored."
            }
            return DisconnectAlarmEvent(
                reason = reason,
                outageDurationMs = elapsedOutageMs,
                armedAutomatedRuleCount = armedAutomatedRuleCount,
                connectionState = connectionState
            )
        }

        return null
    }

    companion object {
        const val DEFAULT_MAX_SILENCE_MS = 30_000L
    }
}
