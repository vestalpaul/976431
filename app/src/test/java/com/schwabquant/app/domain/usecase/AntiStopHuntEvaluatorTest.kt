package com.schwabquant.app.domain.usecase

import com.schwabquant.app.data.remote.streamer.LiveEquityQuote
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.MicrostructureEvalOutcome
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AntiStopHuntEvaluatorTest {

    private val evaluator = AntiStopHuntEvaluator()

    private fun longStopRule(
        trigger: String = "270.00",
        holdSeconds: Int = 15,
        minTicks: Int = 3,
        maxSpreadPct: Double = 1.5,
        sweepBufferPct: Double = 0.0
    ) = SentinelRule(
        id = 1L,
        symbol = "GOOG",
        category = AssetCategory.EQUITY,
        ruleType = SentinelRuleType.LONG_STOP_LOSS,
        triggerPrice = BigDecimal(trigger),
        quantity = 16,
        holdSeconds = holdSeconds,
        minConsecutiveTicks = minTicks,
        maxSpreadPct = maxSpreadPct,
        sweepBufferPct = sweepBufferPct
    )

    @Test
    fun `ignores stray last print below trigger while bid remains healthy above trigger`() {
        val rule = longStopRule()
        val quote = LiveEquityQuote(
            symbol = "GOOG",
            bidPrice = 271.20,
            askPrice = 271.40,
            lastPrice = 264.00, // Stray odd-lot stop-hunt print
            securityStatus = "Normal",
            receivedAtEpochMs = 1_000L
        )

        val result = evaluator.evaluateTick(rule, quote, MarketSessionWindow.AFTER_HOURS)
        assertEquals(MicrostructureEvalOutcome.WATCHING, result.outcome)
        assertEquals(0, result.tracker.consecutiveBreachTicks)
    }

    @Test
    fun `defeats flash stop-hunt wick by resetting hold timer on recovery above trigger`() {
        val rule = longStopRule(holdSeconds = 15, minTicks = 3)

        val tick1 = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 269.40, askPrice = 269.60, securityStatus = "Normal"),
            session = MarketSessionWindow.AFTER_HOURS,
            nowEpochMs = 1_000L
        )
        assertEquals(MicrostructureEvalOutcome.ARMED_WARNING, tick1.outcome)
        assertEquals(1, tick1.tracker.consecutiveBreachTicks)

        val recovery = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 270.50, askPrice = 270.70, securityStatus = "Normal"),
            session = MarketSessionWindow.AFTER_HOURS,
            tracker = tick1.tracker,
            nowEpochMs = 4_000L
        )
        assertEquals(MicrostructureEvalOutcome.WATCHING, recovery.outcome)
        assertEquals(0, recovery.tracker.consecutiveBreachTicks)
        assertEquals(null, recovery.tracker.breachStartedEpochMs)
    }

    @Test
    fun `requires both hold duration and consecutive ticks for confirmed execute`() {
        val rule = longStopRule(holdSeconds = 15, minTicks = 3)

        val t1 = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 269.50, askPrice = 269.70, securityStatus = "Normal"),
            session = MarketSessionWindow.AFTER_HOURS,
            nowEpochMs = 1_000L
        )
        assertEquals(MicrostructureEvalOutcome.ARMED_WARNING, t1.outcome)

        val t2 = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 269.45, askPrice = 269.65, securityStatus = "Normal"),
            session = MarketSessionWindow.AFTER_HOURS,
            tracker = t1.tracker,
            nowEpochMs = 10_000L
        )
        assertEquals(MicrostructureEvalOutcome.ARMED_WARNING, t2.outcome)

        val t3 = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 269.30, askPrice = 269.50, securityStatus = "Normal"),
            session = MarketSessionWindow.AFTER_HOURS,
            tracker = t2.tracker,
            nowEpochMs = 16_500L
        )
        assertEquals(MicrostructureEvalOutcome.CONFIRMED_EXECUTE, t3.outcome)
    }

    @Test
    fun `suppresses execution on wide spread blowout and halted or invalid quotes`() {
        val rule = longStopRule(maxSpreadPct = 1.5)

        val wideSpread = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 260.00, askPrice = 275.00, securityStatus = "Normal"),
            session = MarketSessionWindow.AFTER_HOURS,
            nowEpochMs = 1_000L
        )
        assertEquals(MicrostructureEvalOutcome.SPREAD_BLOWOUT_SUPPRESSED, wideSpread.outcome)

        val halted = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 265.00, askPrice = 265.20, securityStatus = "Halted"),
            session = MarketSessionWindow.REGULAR,
            nowEpochMs = 2_000L
        )
        assertEquals(MicrostructureEvalOutcome.IGNORED_BAD_QUOTE_OR_HALTED, halted.outcome)

        val inverted = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 270.00, askPrice = 269.00, securityStatus = "Normal"),
            session = MarketSessionWindow.REGULAR,
            nowEpochMs = 3_000L
        )
        assertEquals(MicrostructureEvalOutcome.IGNORED_BAD_QUOTE_OR_HALTED, inverted.outcome)
    }

    @Test
    fun `triggers immediately when secondary two-stage liquidity sweep buffer hard floor breaches`() {
        // Trigger $270 with 1.0 pct sweep buffer -> hard floor = $267.30
        val rule = longStopRule(holdSeconds = 30, minTicks = 5, sweepBufferPct = 1.0)
        val plunge = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 267.00, askPrice = 267.30, securityStatus = "Normal"),
            session = MarketSessionWindow.REGULAR,
            nowEpochMs = 1_000L
        )
        assertEquals(MicrostructureEvalOutcome.CONFIRMED_EXECUTE, plunge.outcome)
    }

    @Test
    fun `short stop-loss evaluates strictly against ask price`() {
        val shortRule = SentinelRule(
            id = 2L,
            symbol = "NVDA",
            ruleType = SentinelRuleType.SHORT_STOP_LOSS,
            triggerPrice = BigDecimal("140.00"),
            quantity = 50,
            holdSeconds = 0,
            minConsecutiveTicks = 1,
            isShortPosition = true
        )

        // Bid below 140, Ask crosses 140.10 -> breaches immediately for short stop
        val res = evaluator.evaluateTick(
            rule = shortRule,
            quote = LiveEquityQuote("NVDA", bidPrice = 139.90, askPrice = 140.10, securityStatus = "Normal"),
            session = MarketSessionWindow.PRE_MARKET,
            nowEpochMs = 5_000L
        )
        assertEquals(MicrostructureEvalOutcome.CONFIRMED_EXECUTE, res.outcome)
    }

    @Test
    fun `anti-falling-knife blocks declining bids and alerts only after confirmed bounce`() {
        val dipRule = SentinelRule(
            id = 3L,
            symbol = "MSFT",
            ruleType = SentinelRuleType.DIP_BUY_ANTI_KNIFE,
            triggerPrice = BigDecimal("400.00"),
            quantity = 10,
            minConsecutiveTicks = 3,
            antiKnifeLookbackMinutes = 10,
            antiKnifeBouncePct = 0.25
        )

        val d1 = evaluator.evaluateTick(
            dipRule,
            LiveEquityQuote("MSFT", bidPrice = 398.00, askPrice = 398.20, securityStatus = "Normal"),
            MarketSessionWindow.REGULAR,
            nowEpochMs = 10_000L
        )
        assertEquals(MicrostructureEvalOutcome.ANTI_KNIFE_FALLING_BLOCKED, d1.outcome)

        // Still falling -> blocked
        val d2 = evaluator.evaluateTick(
            dipRule,
            LiveEquityQuote("MSFT", bidPrice = 396.00, askPrice = 396.20, securityStatus = "Normal"),
            MarketSessionWindow.REGULAR,
            tracker = d1.tracker,
            nowEpochMs = 20_000L
        )
        assertEquals(MicrostructureEvalOutcome.ANTI_KNIFE_FALLING_BLOCKED, d2.outcome)

        // Rebounding off $396.00 rolling low (+0.25 pct = $396.99) over 3 rising ticks
        val r1 = evaluator.evaluateTick(
            dipRule,
            LiveEquityQuote("MSFT", bidPrice = 396.40, askPrice = 396.60, securityStatus = "Normal"),
            MarketSessionWindow.REGULAR,
            tracker = d2.tracker,
            nowEpochMs = 30_000L
        )
        assertEquals(MicrostructureEvalOutcome.ANTI_KNIFE_FALLING_BLOCKED, r1.outcome)

        val r2 = evaluator.evaluateTick(
            dipRule,
            LiveEquityQuote("MSFT", bidPrice = 396.80, askPrice = 397.00, securityStatus = "Normal"),
            MarketSessionWindow.REGULAR,
            tracker = r1.tracker,
            nowEpochMs = 40_000L
        )
        assertEquals(MicrostructureEvalOutcome.ANTI_KNIFE_FALLING_BLOCKED, r2.outcome)

        val r3 = evaluator.evaluateTick(
            dipRule,
            LiveEquityQuote("MSFT", bidPrice = 397.20, askPrice = 397.40, securityStatus = "Normal"),
            MarketSessionWindow.REGULAR,
            tracker = r2.tracker,
            nowEpochMs = 50_000L
        )
        assertEquals(MicrostructureEvalOutcome.CONFIRMED_DIP_ALERT, r3.outcome)
    }

    @Test
    fun `overnight EXTO session strictly returns OVERNIGHT_ALERT_ONLY instead of execute`() {
        val rule = longStopRule(holdSeconds = 0, minTicks = 1)
        val res = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 265.00, askPrice = 265.40, securityStatus = "Closed"),
            session = MarketSessionWindow.OVERNIGHT_EXTO,
            nowEpochMs = 1_000L
        )
        assertEquals(MicrostructureEvalOutcome.OVERNIGHT_ALERT_ONLY, res.outcome)
    }

    @Test
    fun `M-3 resets breachStartedEpochMs and consecutiveBreachTicks across HALTED and SPREAD_BLOWOUT ticks`() {
        val rule = longStopRule(holdSeconds = 15, minTicks = 2, maxSpreadPct = 1.5)

        // Tick 1 at t=1s breaches trigger ($269.50 <= $270.00)
        val t1 = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 269.50, askPrice = 269.70, securityStatus = "Normal"),
            session = MarketSessionWindow.REGULAR,
            nowEpochMs = 1_000L
        )
        assertEquals(MicrostructureEvalOutcome.ARMED_WARNING, t1.outcome)
        assertEquals(1_000L, t1.tracker.breachStartedEpochMs)
        assertEquals(1, t1.tracker.consecutiveBreachTicks)

        // Stock halts at t=5s -> must reset breachStartedEpochMs and consecutiveBreachTicks
        val halted = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 268.00, askPrice = 268.20, securityStatus = "Halted"),
            session = MarketSessionWindow.REGULAR,
            tracker = t1.tracker,
            nowEpochMs = 5_000L
        )
        assertEquals(MicrostructureEvalOutcome.IGNORED_BAD_QUOTE_OR_HALTED, halted.outcome)
        assertEquals(null, halted.tracker.breachStartedEpochMs)
        assertEquals(0, halted.tracker.consecutiveBreachTicks)

        // First post-halt tick at t=60s starts a fresh hold window (ARMED_WARNING, NOT CONFIRMED_EXECUTE)
        val postHalt = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 269.00, askPrice = 269.20, securityStatus = "Normal"),
            session = MarketSessionWindow.REGULAR,
            tracker = halted.tracker,
            nowEpochMs = 60_000L
        )
        assertEquals(MicrostructureEvalOutcome.ARMED_WARNING, postHalt.outcome)
        assertEquals(60_000L, postHalt.tracker.breachStartedEpochMs)
        assertEquals(1, postHalt.tracker.consecutiveBreachTicks)

        // Spread blowout at t=65s also resets breach timer
        val blowout = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote("GOOG", bidPrice = 260.00, askPrice = 275.00, securityStatus = "Normal"),
            session = MarketSessionWindow.REGULAR,
            tracker = postHalt.tracker,
            nowEpochMs = 65_000L
        )
        assertEquals(MicrostructureEvalOutcome.SPREAD_BLOWOUT_SUPPRESSED, blowout.outcome)
        assertEquals(null, blowout.tracker.breachStartedEpochMs)
        assertEquals(0, blowout.tracker.consecutiveBreachTicks)
    }

    @Test
    fun `H-3 trade or volume only delta frames with stale carried bid do not advance breach timer but Halted still resets`() {
        val rule = longStopRule(holdSeconds = 5, minTicks = 2, maxSpreadPct = 1.5)

        val freshBreach = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote(
                symbol = "GOOG",
                bidPrice = 269.00,
                askPrice = 269.20,
                securityStatus = "Normal",
                hasFreshBidUpdate = true,
                hasFreshAskUpdate = true
            ),
            session = MarketSessionWindow.REGULAR,
            nowEpochMs = 1_000L
        )
        assertEquals(MicrostructureEvalOutcome.ARMED_WARNING, freshBreach.outcome)
        assertEquals(1, freshBreach.tracker.consecutiveBreachTicks)
        assertEquals(1_000L, freshBreach.tracker.breachStartedEpochMs)

        // Volume/last-print delta has no fresh bid/ask -> must return tracker unchanged without advancing ticks
        val volumeOnlyDelta = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote(
                symbol = "GOOG",
                bidPrice = 269.00,
                askPrice = 269.20,
                lastPrice = 268.50,
                totalVolume = 150_000L,
                securityStatus = "Normal",
                hasFreshBidUpdate = false,
                hasFreshAskUpdate = false
            ),
            session = MarketSessionWindow.REGULAR,
            tracker = freshBreach.tracker,
            nowEpochMs = 10_000L
        )
        assertEquals(MicrostructureEvalOutcome.ARMED_WARNING, volumeOnlyDelta.outcome)
        assertEquals(1, volumeOnlyDelta.tracker.consecutiveBreachTicks)
        assertEquals(1_000L, volumeOnlyDelta.tracker.breachStartedEpochMs)

        // Halted delta even without fresh bid/ask must STILL reset the breach tracker cleanly
        val haltedDelta = evaluator.evaluateTick(
            rule = rule,
            quote = LiveEquityQuote(
                symbol = "GOOG",
                bidPrice = 269.00,
                askPrice = 269.20,
                securityStatus = "Halted",
                hasFreshBidUpdate = false,
                hasFreshAskUpdate = false
            ),
            session = MarketSessionWindow.REGULAR,
            tracker = volumeOnlyDelta.tracker,
            nowEpochMs = 11_000L
        )
        assertEquals(MicrostructureEvalOutcome.IGNORED_BAD_QUOTE_OR_HALTED, haltedDelta.outcome)
        assertEquals(0, haltedDelta.tracker.consecutiveBreachTicks)
        assertEquals(null, haltedDelta.tracker.breachStartedEpochMs)
    }

    @Test
    fun `M-2 separates Bid and Ask freshness strictly between LONG and SHORT exits`() {
        val longRule = longStopRule(holdSeconds = 5, minTicks = 2)
        // Ask-only update must NOT advance a LONG_STOP_LOSS breach
        val askOnlyOnLong = evaluator.evaluateTick(
            rule = longRule,
            quote = LiveEquityQuote(
                symbol = "GOOG",
                bidPrice = 269.00,
                askPrice = 269.20,
                securityStatus = "Normal",
                hasFreshBidUpdate = false,
                hasFreshAskUpdate = true
            ),
            session = MarketSessionWindow.REGULAR,
            nowEpochMs = 1_000L
        )
        assertEquals(0, askOnlyOnLong.tracker.consecutiveBreachTicks)

        // Fresh Bid advances LONG_STOP_LOSS
        val bidFreshOnLong = evaluator.evaluateTick(
            rule = longRule,
            quote = LiveEquityQuote(
                symbol = "GOOG",
                bidPrice = 269.00,
                askPrice = 269.20,
                securityStatus = "Normal",
                hasFreshBidUpdate = true,
                hasFreshAskUpdate = false
            ),
            session = MarketSessionWindow.REGULAR,
            nowEpochMs = 2_000L
        )
        assertEquals(1, bidFreshOnLong.tracker.consecutiveBreachTicks)

        // Bid-only update must NOT advance SHORT_STOP_LOSS; Ask-fresh DOES advance it
        val shortRule = longRule.copy(
            id = 2L,
            ruleType = SentinelRuleType.SHORT_STOP_LOSS,
            isShortPosition = true
        )
        val bidOnlyOnShort = evaluator.evaluateTick(
            rule = shortRule,
            quote = LiveEquityQuote(
                symbol = "GOOG",
                bidPrice = 271.00,
                askPrice = 271.20,
                securityStatus = "Normal",
                hasFreshBidUpdate = true,
                hasFreshAskUpdate = false
            ),
            session = MarketSessionWindow.REGULAR,
            nowEpochMs = 3_000L
        )
        assertEquals(0, bidOnlyOnShort.tracker.consecutiveBreachTicks)

        val askFreshOnShort = evaluator.evaluateTick(
            rule = shortRule,
            quote = LiveEquityQuote(
                symbol = "GOOG",
                bidPrice = 271.00,
                askPrice = 271.20,
                securityStatus = "Normal",
                hasFreshBidUpdate = false,
                hasFreshAskUpdate = true
            ),
            session = MarketSessionWindow.REGULAR,
            nowEpochMs = 4_000L
        )
        assertEquals(1, askFreshOnShort.tracker.consecutiveBreachTicks)
    }

    @Test
    fun `MEDIUM-3 evaluates spread blowout filter even when relevant quote leg is stale and resets breach tracker`() {
        val longRule = com.schwabquant.app.domain.model.SentinelRule(
            id = 9901L,
            symbol = "SPY",
            ruleType = com.schwabquant.app.domain.model.SentinelRuleType.LONG_STOP_LOSS,
            triggerPrice = java.math.BigDecimal("500.00"),
            quantity = 10,
            holdSeconds = 0,
            minConsecutiveTicks = 3,
            maxSpreadPct = 1.5
        )

        val step1 = evaluator.evaluateTick(
            rule = longRule,
            quote = LiveEquityQuote(
                symbol = "SPY",
                bidPrice = 499.00,
                askPrice = 500.00,
                securityStatus = "Normal",
                hasFreshBidUpdate = true,
                hasFreshAskUpdate = true
            ),
            session = MarketSessionWindow.REGULAR,
            nowEpochMs = 1_000L
        )
        assertEquals(1, step1.tracker.consecutiveBreachTicks)

        // Ask-only quote update with wide spread (> 1.5%) must NOT short-circuit before spread filter
        val spreadBlowoutStaleBid = evaluator.evaluateTick(
            rule = longRule,
            quote = LiveEquityQuote(
                symbol = "SPY",
                bidPrice = 499.00,
                askPrice = 520.00,
                securityStatus = "Normal",
                hasFreshBidUpdate = false,
                hasFreshAskUpdate = true
            ),
            session = MarketSessionWindow.REGULAR,
            nowEpochMs = 2_000L
        )
        assertEquals(
            com.schwabquant.app.domain.model.MicrostructureEvalOutcome.SPREAD_BLOWOUT_SUPPRESSED,
            spreadBlowoutStaleBid.outcome
        )
        assertEquals(0, spreadBlowoutStaleBid.tracker.consecutiveBreachTicks)
    }
}
