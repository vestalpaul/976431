package com.schwabquant.app.domain.usecase

import com.schwabquant.app.data.remote.streamer.LiveEquityQuote
import com.schwabquant.app.domain.model.MicrostructureEvalOutcome
import com.schwabquant.app.domain.model.SentinelRule
import com.schwabquant.app.domain.model.SentinelRuleType
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class RuleEvaluationTracker(
    val breachStartedEpochMs: Long? = null,
    val consecutiveBreachTicks: Int = 0,
    val rollingBidHistory: List<Pair<Long, Double>> = emptyList(),
    val consecutiveRisingTicks: Int = 0,
    val lastBid: Double? = null
)

data class MicrostructureEvalResult(
    val outcome: MicrostructureEvalOutcome,
    val tracker: RuleEvaluationTracker,
    val evaluatedPrice: Double?,
    val spreadPct: Double?,
    val elapsedSeconds: Int,
    val detailMessage: String
)

/**
 * Pure-JVM, deterministic Anti-Stop-Hunt & Anti-Falling-Knife microstructure evaluator.
 * - Evaluates LONG stops strictly against BID (ignoring stray LAST prints) & SHORT stops against ASK.
 * - Enforces Quote Integrity / Halt Guard, Spread-Blowout Filter, Hold Timer + Consecutive Ticks,
 *   optional Two-Stage Liquidity-Sweep Buffer, Anti-Falling-Knife stabilization, and Overnight Alert-Only.
 */
@Singleton
class AntiStopHuntEvaluator @Inject constructor() {

    fun evaluateTick(
        rule: SentinelRule,
        quote: LiveEquityQuote,
        session: MarketSessionWindow,
        tracker: RuleEvaluationTracker = RuleEvaluationTracker(),
        nowEpochMs: Long = quote.receivedAtEpochMs
    ): MicrostructureEvalResult {
        val trigger = rule.triggerPrice.toDouble()
        val isOvernightOrAlertRule = session == MarketSessionWindow.OVERNIGHT_EXTO ||
            rule.ruleType == SentinelRuleType.OVERNIGHT_PRICE_ALERT

        // 1. Quote Integrity & Halt Guard
        val statusText = quote.securityStatus?.trim().orEmpty()
        val isHalted = statusText.equals("Halted", ignoreCase = true)
        val isClosed = statusText.equals("Closed", ignoreCase = true)

        val bid = quote.bidPrice
        val ask = quote.askPrice
        val resetBreachTracker = tracker.copy(
            breachStartedEpochMs = null,
            consecutiveBreachTicks = 0
        )

        if (!isOvernightOrAlertRule) {
            if (isHalted || isClosed) {
                return MicrostructureEvalResult(
                    outcome = MicrostructureEvalOutcome.IGNORED_BAD_QUOTE_OR_HALTED,
                    tracker = resetBreachTracker,
                    evaluatedPrice = null,
                    spreadPct = quote.spreadPct,
                    elapsedSeconds = 0,
                    detailMessage = "Suppressed: securityStatus=$statusText."
                )
            }
            if (bid == null || ask == null || bid <= 0.0 || ask <= 0.0 || ask < bid) {
                return MicrostructureEvalResult(
                    outcome = MicrostructureEvalOutcome.IGNORED_BAD_QUOTE_OR_HALTED,
                    tracker = resetBreachTracker,
                    evaluatedPrice = null,
                    spreadPct = null,
                    elapsedSeconds = 0,
                    detailMessage = "Ignored invalid quote (bid=$bid, ask=$ask)."
                )
            }
        } else if (isHalted) {
            return MicrostructureEvalResult(
                outcome = MicrostructureEvalOutcome.IGNORED_BAD_QUOTE_OR_HALTED,
                tracker = resetBreachTracker,
                evaluatedPrice = null,
                spreadPct = quote.spreadPct,
                elapsedSeconds = 0,
                detailMessage = "Suppressed: security halted."
            )
        }

        val spreadPct = quote.spreadPct
        // 2. Spread-Blowout Filter (evaluate BEFORE directional freshness check so Ask-only liquidity
        // vacuums immediately suppress execution and reset breach timers — MEDIUM-3)
        if (!isOvernightOrAlertRule && spreadPct != null) {
            val effectiveMaxSpread = if (rule.maxSpreadPct > 0.0) {
                rule.maxSpreadPct
            } else if (session == MarketSessionWindow.REGULAR) {
                DEFAULT_RTH_MAX_SPREAD_PCT
            } else {
                DEFAULT_EXT_MAX_SPREAD_PCT
            }
            if (spreadPct > effectiveMaxSpread) {
                return MicrostructureEvalResult(
                    outcome = MicrostructureEvalOutcome.SPREAD_BLOWOUT_SUPPRESSED,
                    tracker = resetBreachTracker,
                    evaluatedPrice = bid,
                    spreadPct = spreadPct,
                    elapsedSeconds = 0,
                    detailMessage = "Spread blowout ${fmt2(spreadPct)}% > max ${fmt2(effectiveMaxSpread)}% — execution paused & hold timer reset."
                )
            }
        }

        // 3. Ignore deltas lacking a fresh directional Bid (longs) / Ask (shorts) quote (M-2)
        val requiresFreshAsk = rule.ruleType == SentinelRuleType.SHORT_STOP_LOSS ||
            (rule.ruleType == SentinelRuleType.TAKE_PROFIT && rule.isShortPosition)
        val hasRelevantFreshQuote = if (requiresFreshAsk) {
            quote.hasFreshAskUpdate
        } else {
            quote.hasFreshBidUpdate
        }
        if (!hasRelevantFreshQuote && !isOvernightOrAlertRule) {
            val currentElapsed = tracker.breachStartedEpochMs
                ?.let { ((nowEpochMs - it) / 1_000L).toInt().coerceAtLeast(0) } ?: 0
            val unchangedOutcome = if (tracker.consecutiveBreachTicks > 0) {
                MicrostructureEvalOutcome.ARMED_WARNING
            } else {
                MicrostructureEvalOutcome.WATCHING
            }
            return MicrostructureEvalResult(
                outcome = unchangedOutcome,
                tracker = tracker,
                evaluatedPrice = if (requiresFreshAsk) ask else bid,
                spreadPct = quote.spreadPct,
                elapsedSeconds = currentElapsed,
                detailMessage = "Delta without fresh directional ${if (requiresFreshAsk) "Ask" else "Bid"} — hold/tick counters unchanged."
            )
        }

        // 3. Anti-Falling-Knife Rule (DIP_BUY_ANTI_KNIFE — always biometric-gated alert, never auto-executes)
        if (rule.ruleType == SentinelRuleType.DIP_BUY_ANTI_KNIFE) {
            val evalBid = bid ?: quote.markPrice ?: quote.lastPrice
                ?: return MicrostructureEvalResult(
                    outcome = MicrostructureEvalOutcome.IGNORED_BAD_QUOTE_OR_HALTED,
                    tracker = resetBreachTracker,
                    evaluatedPrice = null,
                    spreadPct = spreadPct,
                    elapsedSeconds = 0,
                    detailMessage = "Missing bid/mark for Anti-Knife evaluation."
                )
            return evaluateAntiFallingKnife(rule, evalBid, spreadPct, session, tracker, nowEpochMs)
        }

        // 4. Directional Quote Selection (BID for long exits, ASK for short covers — NEVER stray LAST!)
        val evaluatedPrice: Double? = when (rule.ruleType) {
            SentinelRuleType.LONG_STOP_LOSS ->
                if (isOvernightOrAlertRule) (bid ?: quote.markPrice ?: quote.lastPrice) else bid
            SentinelRuleType.SHORT_STOP_LOSS ->
                if (isOvernightOrAlertRule) (ask ?: quote.markPrice ?: quote.lastPrice) else ask
            SentinelRuleType.TAKE_PROFIT ->
                if (rule.isShortPosition) {
                    if (isOvernightOrAlertRule) (ask ?: quote.markPrice ?: quote.lastPrice) else ask
                } else {
                    if (isOvernightOrAlertRule) (bid ?: quote.markPrice ?: quote.lastPrice) else bid
                }
            SentinelRuleType.OVERNIGHT_PRICE_ALERT -> bid ?: quote.markPrice ?: quote.lastPrice
            SentinelRuleType.DIP_BUY_ANTI_KNIFE -> bid
        }

        if (evaluatedPrice == null || !evaluatedPrice.isFinite() || evaluatedPrice <= 0.0) {
            return MicrostructureEvalResult(
                outcome = MicrostructureEvalOutcome.IGNORED_BAD_QUOTE_OR_HALTED,
                tracker = resetBreachTracker,
                evaluatedPrice = null,
                spreadPct = spreadPct,
                elapsedSeconds = 0,
                detailMessage = "No valid directional quote price available."
            )
        }

        val isBreached = when (rule.ruleType) {
            SentinelRuleType.LONG_STOP_LOSS -> evaluatedPrice <= trigger
            SentinelRuleType.SHORT_STOP_LOSS -> evaluatedPrice >= trigger
            SentinelRuleType.TAKE_PROFIT -> if (rule.isShortPosition) evaluatedPrice <= trigger else evaluatedPrice >= trigger
            SentinelRuleType.OVERNIGHT_PRICE_ALERT -> if (rule.isShortPosition) evaluatedPrice >= trigger else evaluatedPrice <= trigger
            SentinelRuleType.DIP_BUY_ANTI_KNIFE -> false
        }

        // Recovery across trigger resets confirmation immediately (defeats stop-hunt wicks)
        if (!isBreached) {
            val resetTracker = tracker.copy(
                breachStartedEpochMs = null,
                consecutiveBreachTicks = 0
            )
            return MicrostructureEvalResult(
                outcome = MicrostructureEvalOutcome.WATCHING,
                tracker = resetTracker,
                evaluatedPrice = evaluatedPrice,
                spreadPct = spreadPct,
                elapsedSeconds = 0,
                detailMessage = "Watching @ $${fmt2(evaluatedPrice)} (trigger $${fmt2(trigger)})."
            )
        }

        // Overnight EXTO or OVERNIGHT_PRICE_ALERT -> strictly Alert-Only, zero automated order execution
        if (isOvernightOrAlertRule) {
            return MicrostructureEvalResult(
                outcome = MicrostructureEvalOutcome.OVERNIGHT_ALERT_ONLY,
                tracker = tracker.copy(
                    breachStartedEpochMs = tracker.breachStartedEpochMs ?: nowEpochMs,
                    consecutiveBreachTicks = tracker.consecutiveBreachTicks + 1
                ),
                evaluatedPrice = evaluatedPrice,
                spreadPct = spreadPct,
                elapsedSeconds = 0,
                detailMessage = "Overnight / Alert breach @ $${fmt2(evaluatedPrice)} — alert only (zero API order)."
            )
        }

        // 5. Persistence Confirmation (Hold Timer + Consecutive Ticks) + Optional Two-Stage Sweep Buffer
        val startMs = tracker.breachStartedEpochMs ?: nowEpochMs
        val elapsedMs = (nowEpochMs - startMs).coerceAtLeast(0L)
        val elapsedSec = (elapsedMs / 1_000L).toInt()
        val nextTicks = tracker.consecutiveBreachTicks + 1
        val requiredHoldMs = rule.holdSeconds.coerceAtLeast(0).toLong() * 1_000L
        val requiredTicks = rule.minConsecutiveTicks.coerceAtLeast(1)

        val hardFloorBreached = if (rule.sweepBufferPct > 0.0) {
            when (rule.ruleType) {
                SentinelRuleType.LONG_STOP_LOSS ->
                    evaluatedPrice <= trigger * (1.0 - rule.sweepBufferPct / 100.0)
                SentinelRuleType.SHORT_STOP_LOSS ->
                    evaluatedPrice >= trigger * (1.0 + rule.sweepBufferPct / 100.0)
                else -> false
            }
        } else {
            false
        }

        val updatedTracker = tracker.copy(
            breachStartedEpochMs = startMs,
            consecutiveBreachTicks = nextTicks
        )

        val confirmedByPersistence = elapsedMs >= requiredHoldMs && nextTicks >= requiredTicks
        return if (confirmedByPersistence || hardFloorBreached) {
            val reason = if (hardFloorBreached && !confirmedByPersistence) {
                "Secondary sweep-buffer hard floor breached @ $${fmt2(evaluatedPrice)}."
            } else {
                "Confirmed break (${elapsedSec}s/${rule.holdSeconds}s, $nextTicks/$requiredTicks ticks) @ $${fmt2(evaluatedPrice)}."
            }
            MicrostructureEvalResult(
                outcome = MicrostructureEvalOutcome.CONFIRMED_EXECUTE,
                tracker = updatedTracker,
                evaluatedPrice = evaluatedPrice,
                spreadPct = spreadPct,
                elapsedSeconds = elapsedSec,
                detailMessage = reason
            )
        } else {
            MicrostructureEvalResult(
                outcome = MicrostructureEvalOutcome.ARMED_WARNING,
                tracker = updatedTracker,
                evaluatedPrice = evaluatedPrice,
                spreadPct = spreadPct,
                elapsedSeconds = elapsedSec,
                detailMessage = "Armed Warning [${elapsedSec}s/${rule.holdSeconds}s • $nextTicks/$requiredTicks ticks] @ $${fmt2(evaluatedPrice)}."
            )
        }
    }

    private fun evaluateAntiFallingKnife(
        rule: SentinelRule,
        bid: Double,
        spreadPct: Double?,
        session: MarketSessionWindow,
        tracker: RuleEvaluationTracker,
        nowEpochMs: Long
    ): MicrostructureEvalResult {
        val trigger = rule.triggerPrice.toDouble()
        val lookbackMs = rule.antiKnifeLookbackMinutes.coerceAtLeast(1).toLong() * 60_000L
        val prunedHistory = tracker.rollingBidHistory
            .filter { (ts, _) -> (nowEpochMs - ts) <= lookbackMs } + (nowEpochMs to bid)
        val rollingLow = prunedHistory.minOf { it.second }

        if (bid > trigger) {
            val updated = tracker.copy(
                rollingBidHistory = prunedHistory,
                consecutiveRisingTicks = 0,
                lastBid = bid
            )
            return MicrostructureEvalResult(
                outcome = MicrostructureEvalOutcome.WATCHING,
                tracker = updated,
                evaluatedPrice = bid,
                spreadPct = spreadPct,
                elapsedSeconds = 0,
                detailMessage = "Waiting for dip below $${fmt2(trigger)} (bid $${fmt2(bid)})."
            )
        }

        val prevBid = tracker.lastBid
        val stillFalling = prevBid != null && bid < prevBid
        if (stillFalling) {
            val updated = tracker.copy(
                rollingBidHistory = prunedHistory,
                consecutiveRisingTicks = 0,
                lastBid = bid
            )
            return MicrostructureEvalResult(
                outcome = MicrostructureEvalOutcome.ANTI_KNIFE_FALLING_BLOCKED,
                tracker = updated,
                evaluatedPrice = bid,
                spreadPct = spreadPct,
                elapsedSeconds = 0,
                detailMessage = "Falling knife blocked: bid $${fmt2(bid)} < prev $${fmt2(prevBid)} (rolling low $${fmt2(rollingLow)})."
            )
        }

        val risingTicks = if (prevBid == null) 1 else tracker.consecutiveRisingTicks + 1
        val bouncePct = if (rollingLow > 0.0) ((bid - rollingLow) / rollingLow) * 100.0 else 0.0
        val requiredTicks = rule.minConsecutiveTicks.coerceAtLeast(1)
        val updated = tracker.copy(
            rollingBidHistory = prunedHistory,
            consecutiveRisingTicks = risingTicks,
            lastBid = bid
        )

        val stabilized = risingTicks >= requiredTicks && bouncePct >= rule.antiKnifeBouncePct
        if (!stabilized) {
            return MicrostructureEvalResult(
                outcome = MicrostructureEvalOutcome.ANTI_KNIFE_FALLING_BLOCKED,
                tracker = updated,
                evaluatedPrice = bid,
                spreadPct = spreadPct,
                elapsedSeconds = 0,
                detailMessage = "Stabilizing [$risingTicks/$requiredTicks ticks, bounce ${fmt2(bouncePct)}%/${fmt2(rule.antiKnifeBouncePct)}%]."
            )
        }

        val finalOutcome = if (session == MarketSessionWindow.OVERNIGHT_EXTO) {
            MicrostructureEvalOutcome.OVERNIGHT_ALERT_ONLY
        } else {
            MicrostructureEvalOutcome.CONFIRMED_DIP_ALERT
        }

        return MicrostructureEvalResult(
            outcome = finalOutcome,
            tracker = updated,
            evaluatedPrice = bid,
            spreadPct = spreadPct,
            elapsedSeconds = 0,
            detailMessage = "Anti-Knife dip stabilized @ $${fmt2(bid)} (+${fmt2(bouncePct)}% off rolling low $${fmt2(rollingLow)}) — ready for biometric review."
        )
    }

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    companion object {
        const val DEFAULT_RTH_MAX_SPREAD_PCT = 0.5
        const val DEFAULT_EXT_MAX_SPREAD_PCT = 1.5
    }
}
