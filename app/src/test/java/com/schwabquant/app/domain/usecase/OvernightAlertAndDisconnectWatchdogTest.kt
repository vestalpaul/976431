package com.schwabquant.app.domain.usecase

import com.schwabquant.app.data.remote.streamer.LiveEquityQuote
import com.schwabquant.app.data.remote.streamer.StreamerConnectionState
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleType
import com.schwabquant.app.domain.model.TradingSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OvernightAlertAndDisconnectWatchdogTest {

    private val alertBuilder = OvernightAlertBuilder()

    @Test
    fun `builds overnight alert payload staging SEAMLESS LIMIT DAY order for 7 AM review`() {
        val rule = SentinelRule(
            id = 5L,
            symbol = "GOOG",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("270.00"),
            quantity = 16,
            slippageCollarPct = 0.50
        )
        val quote = LiveEquityQuote(
            symbol = "GOOG",
            bidPrice = 266.00,
            askPrice = 266.40,
            securityStatus = "Closed"
        )

        val payload = alertBuilder.buildOvernightAlert(rule, quote)

        assertEquals("GOOG", payload.symbol)
        assertEquals("Queue 7:00 AM Pre-Market Limit", payload.actionLabel)
        assertEquals(TradingSession.SEAMLESS, payload.stagedOrderDraft.session)
        assertEquals(OrderType.LIMIT, payload.stagedOrderDraft.orderType)
        assertEquals(OrderDuration.DAY, payload.stagedOrderDraft.duration)
        assertEquals(OrderSide.SELL, payload.stagedOrderDraft.legs.single().side)
        assertEquals(16, payload.stagedOrderDraft.legs.single().quantity)
        // 266.00 * (1 - 0.005) = 264.67
        assertEquals(BigDecimal("264.67"), payload.stagedOrderDraft.limitPrice)
    }

    @Test
    fun `watchdog fires disconnect alarm strictly once per outage and resets after reconnect`() {
        val watchdog = DisconnectWatchdogEvaluator(maxSilenceMs = 30_000L)

        // Healthy authenticated stream at t = 25s (heartbeat age 5s) -> null
        assertNull(
            watchdog.evaluate(
                hasArmedAutomatedRules = true,
                isLiveStreamingActive = true,
                connectionState = StreamerConnectionState.AUTHENTICATED,
                lastHeartbeatEpochMs = 20_000L,
                nowEpochMs = 25_000L
            )
        )

        // Socket drops at t = 45s (heartbeat age 25s <= 30s grace) -> still null
        assertNull(
            watchdog.evaluate(
                hasArmedAutomatedRules = true,
                isLiveStreamingActive = true,
                connectionState = StreamerConnectionState.RECONNECTING,
                lastHeartbeatEpochMs = 20_000L,
                nowEpochMs = 45_000L
            )
        )

        // Outage exceeds 30s at t = 55s (age 35s > 30s) -> fires ONCE!
        val firstAlarm = watchdog.evaluate(
            hasArmedAutomatedRules = true,
            isLiveStreamingActive = true,
            connectionState = StreamerConnectionState.RECONNECTING,
            lastHeartbeatEpochMs = 20_000L,
            nowEpochMs = 55_000L,
            armedAutomatedRuleCount = 2
        )
        assertNotNull(firstAlarm)
        assertEquals(2, firstAlarm!!.armedAutomatedRuleCount)

        // Repeated check during same outage at t = 60s -> deduplicated (null)
        assertNull(
            watchdog.evaluate(
                hasArmedAutomatedRules = true,
                isLiveStreamingActive = true,
                connectionState = StreamerConnectionState.RECONNECTING,
                lastHeartbeatEpochMs = 20_000L,
                nowEpochMs = 60_000L
            )
        )

        // Reconnect succeeds with fresh heartbeat at t = 70s -> resets watchdog cleanly
        assertNull(
            watchdog.evaluate(
                hasArmedAutomatedRules = true,
                isLiveStreamingActive = true,
                connectionState = StreamerConnectionState.AUTHENTICATED,
                lastHeartbeatEpochMs = 70_000L,
                nowEpochMs = 72_000L
            )
        )

        // Distinct second outage (stale heartbeat at t = 110s, age 40s) -> fires again!
        val secondAlarm = watchdog.evaluate(
            hasArmedAutomatedRules = true,
            isLiveStreamingActive = true,
            connectionState = StreamerConnectionState.AUTHENTICATED,
            lastHeartbeatEpochMs = 70_000L,
            nowEpochMs = 110_000L
        )
        assertNotNull(secondAlarm)
        assertTrue(secondAlarm!!.reason.contains("heartbeat silent"))
    }

    @Test
    fun `watchdog stays silent in simulated mode or when no automated rules are armed`() {
        val watchdog = DisconnectWatchdogEvaluator(maxSilenceMs = 30_000L)

        // Simulated mode -> never fires
        assertNull(
            watchdog.evaluate(
                hasArmedAutomatedRules = true,
                isLiveStreamingActive = false,
                connectionState = StreamerConnectionState.SIMULATED,
                lastHeartbeatEpochMs = 0L,
                nowEpochMs = 500_000L
            )
        )

        // Live mode with 0 automated rules -> never fires
        assertNull(
            watchdog.evaluate(
                hasArmedAutomatedRules = false,
                isLiveStreamingActive = true,
                connectionState = StreamerConnectionState.DISCONNECTED,
                lastHeartbeatEpochMs = 1_000L,
                nowEpochMs = 500_000L
            )
        )

        // Schwab Error 12 -> fires immediately once when live-monitoring automated rules
        val err12 = watchdog.evaluate(
            hasArmedAutomatedRules = true,
            isLiveStreamingActive = true,
            connectionState = StreamerConnectionState.ERROR_CONFLICT_CLOSE_12,
            lastHeartbeatEpochMs = 499_000L,
            nowEpochMs = 500_000L
        )
        assertNotNull(err12)
        assertTrue(err12!!.reason.contains("Error 12"))
    }

    @Test
    fun `H-3 buildUnfilledRepriceDraft sanitizes placeholder IDs to null and marks isEscalatedReprice`() {
        val baseRule = SentinelRule(
            id = 9L,
            symbol = "AAPL",
            ruleType = SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = BigDecimal("210.00"),
            quantity = 4
        )
        val quote = LiveEquityQuote("AAPL", bidPrice = 208.00, askPrice = 208.20)

        listOf("REFRESH_FAILED", "PRE_SUBMIT_ABORT", "ESCALATED", "UNTRACKED", "LIVE-SUBMITTED", "bad id", "no-digits", "")
            .forEach { badId ->
                val sanitized = alertBuilder.buildUnfilledRepriceDraft(
                    rule = baseRule.copy(lastSubmittedOrderId = badId),
                    quote = quote,
                    priorOrderId = badId
                )
                assertNull(sanitized.replacePriorOrderId, "Placeholder '$badId' must sanitize to null")
                assertTrue(sanitized.isEscalatedReprice)
            }

        val fallbackValid = alertBuilder.buildUnfilledRepriceDraft(
            rule = baseRule.copy(lastSubmittedOrderId = "987654321"),
            quote = quote,
            priorOrderId = "REFRESH_FAILED"
        )
        assertEquals("987654321", fallbackValid.replacePriorOrderId)
        assertTrue(fallbackValid.isEscalatedReprice)
    }

    @Test
    fun `H-6 and C-4 short exits emit BUY_TO_COVER and reprice draft clamps to heldPositionWholeShares`() {
        val shortRule = SentinelRule(
            id = 12L,
            symbol = "NVDA",
            ruleType = SentinelRuleType.SHORT_STOP_LOSS,
            triggerPrice = BigDecimal("140.00"),
            quantity = 10,
            isShortPosition = true
        )
        val quote = LiveEquityQuote("NVDA", bidPrice = 140.10, askPrice = 140.30, securityStatus = "Closed")

        val overnight = alertBuilder.buildOvernightAlert(shortRule, quote)
        assertEquals(OrderSide.BUY_TO_COVER, overnight.stagedOrderDraft.legs.single().side)

        val tpShort = shortRule.copy(ruleType = SentinelRuleType.TAKE_PROFIT, triggerPrice = BigDecimal("125.00"))
        assertEquals(OrderSide.BUY_TO_COVER, alertBuilder.buildOvernightAlert(tpShort, quote).stagedOrderDraft.legs.single().side)

        val reprice = alertBuilder.buildUnfilledRepriceDraft(
            rule = shortRule,
            quote = quote,
            priorOrderId = "5551234",
            heldPositionWholeShares = 6
        )
        assertEquals(OrderSide.BUY_TO_COVER, reprice.legs.single().side)
        assertEquals(6, reprice.legs.single().quantity)
    }
}
