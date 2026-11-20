package com.schwabquant.app.domain.usecase

import com.schwabquant.app.core.quant.ParsedOptionChain
import com.schwabquant.app.core.quant.ParsedOptionContract
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.domain.model.LegAction
import com.schwabquant.app.domain.model.MarketRegime
import com.schwabquant.app.domain.model.OptimizedStrategyCandidate
import com.schwabquant.app.domain.model.OptimizerLeg
import com.schwabquant.app.domain.model.OptimizerRegimeFit
import com.schwabquant.app.domain.model.OptimizerRiskProfile
import com.schwabquant.app.domain.model.OptimizerStrategyType
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.SingleTickerOptimizationReport
import com.schwabquant.app.domain.model.StrategyOptimizationResult
import com.schwabquant.app.domain.model.StrategyRankingBreakdown
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-JVM exhaustive single-ticker 7-Strategy Options Chain Optimizer.
 *
 * Always evaluates all 7 core strategies (PMCC, PMCP, Bull Put Vertical, Bear Call Vertical,
 * Calendar, Iron Condor, Wheel/CSP), scores valid candidates using a deterministic 4-pillar
 * 100-point model (30 Delta/DTE + 35 Structure + 20 Liquidity + 15 Regime/Capital), and guarantees
 * `rankedStrategies.size == 7` ordered most-to-least suggested (`rank = 1..7`) with explicit
 * ranking rationale on every single item.
 */
@Singleton
class AnalyzeSingleTickerStrategiesUseCase @Inject constructor() {

    private data class StrategySearchOutput(
        val strategyType: OptimizerStrategyType,
        val regimeFit: OptimizerRegimeFit,
        val candidates: List<OptimizedStrategyCandidate>,
        val failureReason: String?
    )

    fun analyze(
        chain: ParsedOptionChain,
        activeRegime: MarketRegime,
        volRank52w: Double? = null,
        sma50: Double? = null,
        maxCapitalBudget: Double? = null,
        riskProfile: OptimizerRiskProfile = OptimizerRiskProfile.BALANCED,
        nowEpochMs: Long = System.currentTimeMillis(),
        isDemoData: Boolean = false
    ): SingleTickerOptimizationReport {
        val cleanSymbol = SchwabSymbolNormalizer.toDisplaySymbol(chain.symbol).ifBlank { chain.symbol }
        val spot = chain.underlyingPrice

        val rawOutputs = if (spot <= 0.0 || chain.contracts.isEmpty()) {
            OptimizerStrategyType.entries.map { type ->
                StrategySearchOutput(
                    strategyType = type,
                    regimeFit = classifyRegimeFit(type, activeRegime),
                    candidates = emptyList(),
                    failureReason = "Option chain for $cleanSymbol has no valid quotes or underlying spot."
                )
            }
        } else {
            OptimizerStrategyType.entries.map { type ->
                evaluateStrategy(
                    type = type,
                    chain = chain,
                    cleanSymbol = cleanSymbol,
                    activeRegime = activeRegime,
                    volRank52w = volRank52w,
                    sma50 = sma50,
                    maxCapitalBudget = maxCapitalBudget,
                    riskProfile = riskProfile
                )
            }
        }

        val sorted = rawOutputs.sortedWith(
            compareByDescending<StrategySearchOutput> { it.candidates.isNotEmpty() }
                .thenByDescending { it.candidates.firstOrNull()?.breakdown?.compositeScore ?: 0.0 }
                .thenBy { it.regimeFit.ordinal }
                .thenBy { it.strategyType.ordinal }
        )

        val topScore = sorted.firstOrNull { it.candidates.isNotEmpty() }
            ?.candidates?.firstOrNull()?.breakdown?.compositeScore ?: 0.0

        val rankedResults = sorted.mapIndexed { idx, out ->
            val rank = idx + 1
            val winner = out.candidates.firstOrNull()
            val runnerUps = if (out.candidates.size > 1) {
                out.candidates.drop(1).distinctBy { it.summaryLine }.take(MAX_RUNNER_UPS)
            } else {
                emptyList()
            }
            val breakdown = winner?.breakdown ?: StrategyRankingBreakdown.ZERO
            val score = breakdown.compositeScore
            val prev = if (idx > 0) sorted[idx - 1] else null
            val next = if (idx + 1 < sorted.size && sorted[idx + 1].candidates.isNotEmpty()) sorted[idx + 1] else null

            val explanation = buildRankingExplanation(
                rank = rank,
                out = out,
                winner = winner,
                prev = prev,
                next = next,
                topScore = topScore,
                activeRegime = activeRegime,
                volRank52w = volRank52w
            )

            StrategyOptimizationResult(
                rank = rank,
                strategyType = out.strategyType,
                optimalCandidate = winner,
                runnerUpCandidates = runnerUps,
                compositeScore = score,
                breakdown = breakdown,
                regimeFit = out.regimeFit,
                rankingExplanation = explanation,
                noValidSetupReason = if (winner == null) out.failureReason else null
            )
        }

        return SingleTickerOptimizationReport(
            symbol = cleanSymbol,
            underlyingSpot = spot,
            activeRegime = activeRegime,
            volRank52w = volRank52w,
            sma50 = sma50,
            riskProfile = riskProfile,
            maxCapitalBudget = maxCapitalBudget,
            rankedStrategies = rankedResults,
            evaluatedAtEpochMs = nowEpochMs,
            isDemoData = isDemoData
        )
    }

    private fun evaluateStrategy(
        type: OptimizerStrategyType,
        chain: ParsedOptionChain,
        cleanSymbol: String,
        activeRegime: MarketRegime,
        volRank52w: Double?,
        sma50: Double?,
        maxCapitalBudget: Double?,
        riskProfile: OptimizerRiskProfile
    ): StrategySearchOutput {
        val fit = classifyRegimeFit(type, activeRegime)
        val rawCandidates = when (type) {
            OptimizerStrategyType.PMCC ->
                generatePmcc(chain, cleanSymbol, activeRegime, fit, volRank52w, maxCapitalBudget, riskProfile)
            OptimizerStrategyType.PMCP ->
                generatePmcp(chain, cleanSymbol, activeRegime, fit, volRank52w, maxCapitalBudget, riskProfile)
            OptimizerStrategyType.BULL_PUT_VERTICAL ->
                generateBullPut(chain, cleanSymbol, activeRegime, fit, volRank52w, maxCapitalBudget, riskProfile)
            OptimizerStrategyType.BEAR_CALL_VERTICAL ->
                generateBearCall(chain, cleanSymbol, activeRegime, fit, volRank52w, maxCapitalBudget, riskProfile)
            OptimizerStrategyType.CALENDAR ->
                generateCalendar(chain, cleanSymbol, activeRegime, fit, volRank52w, maxCapitalBudget, riskProfile)
            OptimizerStrategyType.IRON_CONDOR ->
                generateIronCondor(chain, cleanSymbol, activeRegime, fit, volRank52w, maxCapitalBudget, riskProfile)
            OptimizerStrategyType.WHEEL_CSP ->
                generateWheelCsp(chain, cleanSymbol, activeRegime, fit, volRank52w, sma50, maxCapitalBudget, riskProfile)
        }

        val budgetFiltered = if (maxCapitalBudget == null) {
            rawCandidates
        } else {
            rawCandidates.filter { it.buyingPowerRequirement <= maxCapitalBudget }
        }.sortedWith(
            compareByDescending<OptimizedStrategyCandidate> { it.breakdown.compositeScore }
                .thenBy { it.buyingPowerRequirement }
                .thenBy { it.id }
        )

        val failureReason = if (budgetFiltered.isEmpty()) {
            when {
                rawCandidates.isNotEmpty() && maxCapitalBudget != null -> {
                    val lowestBpr = rawCandidates.minOf { it.buyingPowerRequirement }
                    "All ${rawCandidates.size} liquid ${type.shortLabel} setups require at least \$${fmt0(lowestBpr)} BPR, exceeding capital cap \$${fmt0(maxCapitalBudget)}."
                }
                type == OptimizerStrategyType.PMCC || type == OptimizerStrategyType.PMCP ->
                    "No valid ${type.shortLabel} pairs passed 180–450 DTE LEAPS / 25–45 DTE short / liquidity filters."
                type == OptimizerStrategyType.CALENDAR ->
                    "No matching ATM 21–35 DTE vs 60–90 DTE strike pairs passed liquidity filters."
                type == OptimizerStrategyType.IRON_CONDOR ->
                    "No 30–45 DTE OTM put+call wings passed spread (<=15%) and OI (>=25) filters."
                else ->
                    "No 25–45 DTE contracts passed bid-ask spread (<=15%) and open interest (>=25) filters."
            }
        } else {
            null
        }

        return StrategySearchOutput(
            strategyType = type,
            regimeFit = fit,
            candidates = budgetFiltered,
            failureReason = failureReason
        )
    }

    private fun generatePmcc(
        chain: ParsedOptionChain,
        cleanSymbol: String,
        activeRegime: MarketRegime,
        fit: OptimizerRegimeFit,
        volRank52w: Double?,
        maxCapitalBudget: Double?,
        riskProfile: OptimizerRiskProfile
    ): List<OptimizedStrategyCandidate> {
        val longs = chain.calls
            .filter { it.dte in 200..450 && it.delta in 0.68..0.93 && isLiquid(it) }
            .sortedBy { abs(it.delta - 0.84) + abs(it.dte - 270) / 500.0 }
            .take(6)
        val shorts = chain.calls
            .filter { it.dte in 25..45 && it.delta in 0.16..0.44 && isLiquid(it) }
            .sortedBy { abs(it.delta - riskProfile.shortCallDeltaTarget) + abs(it.dte - 35) / 100.0 }
            .take(6)
        if (longs.isEmpty() || shorts.isEmpty()) return emptyList()

        val list = mutableListOf<OptimizedStrategyCandidate>()
        for (longLeg in longs) {
            for (shortLeg in shorts) {
                if (shortLeg.strike <= longLeg.strike + 0.01) continue
                val netDebit = longLeg.mid - shortLeg.mid
                if (netDebit <= 0.05) continue
                val width = shortLeg.strike - longLeg.strike
                val debitToWidth = netDebit / width
                if (debitToWidth >= 1.15) continue

                val bpr = netDebit * 100.0
                val zeroUpsideLoss = debitToWidth < 1.0
                val upsideMargin = max(0.0, width - netDebit)
                val maxProfit = if (upsideMargin > 0.0) upsideMargin * 100.0 else null
                val termAdvantage = shortLeg.iv - longLeg.iv
                val pop = ((1.0 - abs(shortLeg.delta)) * 100.0).coerceIn(20.0, 92.0)

                val deltaDte = scoreDeltaDte(
                    deltaErr = (abs(longLeg.delta - 0.84) + abs(shortLeg.delta - riskProfile.shortCallDeltaTarget)) * 0.5,
                    maxDeltaErr = 0.20,
                    dteDist = (abs(longLeg.dte - 270) / 180.0 + abs(shortLeg.dte - 35) / 20.0) * 0.5
                )
                val cushionPts = when {
                    debitToWidth <= 0.85 -> 22.0
                    debitToWidth <= 0.95 -> 17.0
                    zeroUpsideLoss -> 12.0
                    else -> 4.0
                }
                val yieldPts = ((shortLeg.mid / netDebit) * 45.0).coerceIn(0.0, 9.0)
                val ivPts = (termAdvantage * 40.0).coerceIn(0.0, 4.0)
                val structural = cushionPts + yieldPts + ivPts
                val liquidity = scoreLiquidity(listOf(longLeg, shortLeg))
                val regimeCapital = scoreRegimeCapital(
                    type = OptimizerStrategyType.PMCC,
                    fit = fit,
                    activeRegime = activeRegime,
                    volRank52w = volRank52w,
                    bpr = bpr,
                    maxCapitalBudget = maxCapitalBudget
                )
                val breakdown = StrategyRankingBreakdown.of(deltaDte, structural, liquidity, regimeCapital)

                val rationale = listOf(
                    "LEAPS ${longLeg.dte}DTE K=$${fmtStrike(longLeg.strike)} (Δ ${fmt2(longLeg.delta)}) paired with ${shortLeg.dte}DTE K=$${fmtStrike(shortLeg.strike)} (Δ ${fmt2(shortLeg.delta)}) for ${riskProfile.label}.",
                    if (zeroUpsideLoss) "Net Debit \$${fmt2(netDebit)} is ${fmt1(debitToWidth * 100.0)}% of \$${fmt2(width)} width — zero upside expiration loss."
                    else "Net Debit \$${fmt2(netDebit)} captures \$${fmt2(shortLeg.mid)} (${shortLeg.dte}D) toward basis.",
                    "Tightest leg spreads ${fmt1(maxOf(longLeg.spreadWidthPct, shortLeg.spreadWidthPct) * 100.0)}% with min OI ${minOf(longLeg.openInterest, shortLeg.openInterest)}."
                )

                list.add(
                    buildCandidate(
                        id = "${cleanSymbol}_PMCC_${longLeg.symbol}_${shortLeg.symbol}",
                        cleanSymbol = cleanSymbol,
                        spot = chain.underlyingPrice,
                        type = OptimizerStrategyType.PMCC,
                        legs = listOf(
                            longLeg.toOptimizerLeg(LegAction.BUY_TO_OPEN),
                            shortLeg.toOptimizerLeg(LegAction.SELL_TO_OPEN)
                        ),
                        breakdown = breakdown,
                        fit = fit,
                        orderType = OrderType.NET_DEBIT,
                        netPerShare = netDebit,
                        isNetCredit = false,
                        estBid = max(0.01, longLeg.bid - shortLeg.ask),
                        estAsk = max(0.01, longLeg.ask - shortLeg.bid),
                        bpr = bpr,
                        maxProfit = maxProfit,
                        maxLoss = bpr,
                        breakevens = listOf(longLeg.strike + netDebit),
                        popPct = pop,
                        rationale = rationale,
                        summaryLine = "${longLeg.dte}D +${fmtStrike(longLeg.strike)}C / ${shortLeg.dte}D -${fmtStrike(shortLeg.strike)}C @ \$${fmt2(netDebit)} db"
                    )
                )
            }
        }
        return list
    }

    private fun generatePmcp(
        chain: ParsedOptionChain,
        cleanSymbol: String,
        activeRegime: MarketRegime,
        fit: OptimizerRegimeFit,
        volRank52w: Double?,
        maxCapitalBudget: Double?,
        riskProfile: OptimizerRiskProfile
    ): List<OptimizedStrategyCandidate> {
        val longs = chain.puts
            .filter { it.dte in 180..450 && abs(it.delta) in 0.65..0.93 && isLiquid(it) }
            .sortedBy { abs(abs(it.delta) - 0.80) + abs(it.dte - 225) / 400.0 }
            .take(6)
        val shorts = chain.puts
            .filter { it.dte in 25..45 && abs(it.delta) in 0.16..0.44 && isLiquid(it) }
            .sortedBy { abs(it.delta - riskProfile.shortPutDeltaTarget) + abs(it.dte - 35) / 100.0 }
            .take(6)
        if (longs.isEmpty() || shorts.isEmpty()) return emptyList()

        val list = mutableListOf<OptimizedStrategyCandidate>()
        for (longLeg in longs) {
            for (shortLeg in shorts) {
                if (shortLeg.strike >= longLeg.strike - 0.01) continue
                val netDebit = longLeg.mid - shortLeg.mid
                if (netDebit <= 0.05) continue
                val width = longLeg.strike - shortLeg.strike
                val debitToWidth = netDebit / width
                if (debitToWidth >= 1.05) continue

                val bpr = netDebit * 100.0
                val maxProfit = max(0.0, (width - netDebit) * 100.0)
                val pop = (abs(shortLeg.delta) * 100.0).coerceIn(18.0, 82.0)

                val deltaDte = scoreDeltaDte(
                    deltaErr = (abs(abs(longLeg.delta) - 0.82) + abs(shortLeg.delta - riskProfile.shortPutDeltaTarget)) * 0.5,
                    maxDeltaErr = 0.20,
                    dteDist = (abs(longLeg.dte - 225) / 160.0 + abs(shortLeg.dte - 35) / 20.0) * 0.5
                )
                val structCushion = when {
                    debitToWidth <= 0.75 -> 22.0
                    debitToWidth <= 0.85 -> 18.0
                    debitToWidth <= 0.95 -> 11.0
                    else -> 4.0
                }
                val rorPts = ((maxProfit / bpr) * 20.0).coerceIn(0.0, 9.0)
                val termPts = ((shortLeg.iv - longLeg.iv) * 40.0).coerceIn(0.0, 4.0)
                val structural = structCushion + rorPts + termPts
                val liquidity = scoreLiquidity(listOf(longLeg, shortLeg))
                val regimeCapital = scoreRegimeCapital(
                    type = OptimizerStrategyType.PMCP,
                    fit = fit,
                    activeRegime = activeRegime,
                    volRank52w = volRank52w,
                    bpr = bpr,
                    maxCapitalBudget = maxCapitalBudget
                )
                val breakdown = StrategyRankingBreakdown.of(deltaDte, structural, liquidity, regimeCapital)

                val rationale = listOf(
                    "Deep ITM ${longLeg.dte}DTE +${fmtStrike(longLeg.strike)}P (Δ ${fmt2(longLeg.delta)}) + ${shortLeg.dte}DTE -${fmtStrike(shortLeg.strike)}P (Δ ${fmt2(shortLeg.delta)}).",
                    "Net Debit \$${fmt2(netDebit)} is ${fmt1(debitToWidth * 100.0)}% of \$${fmt2(width)} put width (max ROR ${fmt1((maxProfit / bpr) * 100.0)}%).",
                    "Both legs meet OI >= 25 (min ${minOf(longLeg.openInterest, shortLeg.openInterest)}) and max spread ${fmt1(maxOf(longLeg.spreadWidthPct, shortLeg.spreadWidthPct) * 100.0)}%."
                )

                list.add(
                    buildCandidate(
                        id = "${cleanSymbol}_PMCP_${longLeg.symbol}_${shortLeg.symbol}",
                        cleanSymbol = cleanSymbol,
                        spot = chain.underlyingPrice,
                        type = OptimizerStrategyType.PMCP,
                        legs = listOf(
                            longLeg.toOptimizerLeg(LegAction.BUY_TO_OPEN),
                            shortLeg.toOptimizerLeg(LegAction.SELL_TO_OPEN)
                        ),
                        breakdown = breakdown,
                        fit = fit,
                        orderType = OrderType.NET_DEBIT,
                        netPerShare = netDebit,
                        isNetCredit = false,
                        estBid = max(0.01, longLeg.bid - shortLeg.ask),
                        estAsk = max(0.01, longLeg.ask - shortLeg.bid),
                        bpr = bpr,
                        maxProfit = maxProfit,
                        maxLoss = bpr,
                        breakevens = listOf(longLeg.strike - netDebit),
                        popPct = pop,
                        rationale = rationale,
                        summaryLine = "${longLeg.dte}D +${fmtStrike(longLeg.strike)}P / ${shortLeg.dte}D -${fmtStrike(shortLeg.strike)}P @ \$${fmt2(netDebit)} db"
                    )
                )
            }
        }
        return list
    }

    private fun generateBullPut(
        chain: ParsedOptionChain,
        cleanSymbol: String,
        activeRegime: MarketRegime,
        fit: OptimizerRegimeFit,
        volRank52w: Double?,
        maxCapitalBudget: Double?,
        riskProfile: OptimizerRiskProfile
    ): List<OptimizedStrategyCandidate> {
        val spot = chain.underlyingPrice
        val minWing = max(1.0, spot * 0.015)
        val maxWing = max(minWing + 1.0, spot * 0.11)
        val list = mutableListOf<OptimizedStrategyCandidate>()

        val byExp = chain.puts
            .filter { it.dte in 25..45 && isLiquid(it) }
            .groupBy { it.expirationDate }

        for ((_, puts) in byExp) {
            val shorts = puts
                .filter { abs(it.delta) in 0.14..0.42 && it.strike <= spot * 1.02 }
                .sortedBy { abs(it.delta - riskProfile.shortPutDeltaTarget) }
                .take(6)
            for (shortLeg in shorts) {
                val wings = puts
                    .filter { longLeg ->
                        val w = shortLeg.strike - longLeg.strike
                        w in minWing..maxWing
                    }
                    .sortedBy { abs((shortLeg.strike - it.strike) - spot * 0.04) }
                    .take(6)
                for (longLeg in wings) {
                    val credit = shortLeg.mid - longLeg.mid
                    if (credit <= 0.05) continue
                    val width = shortLeg.strike - longLeg.strike
                    val creditToWidth = credit / width
                    if (creditToWidth < 0.10) continue

                    val bpr = max(50.0, (width - credit) * 100.0)
                    val maxProfit = credit * 100.0
                    val pop = ((1.0 - abs(shortLeg.delta)) * 100.0).coerceIn(50.0, 92.0)

                    val deltaDte = scoreDeltaDte(
                        deltaErr = abs(shortLeg.delta - riskProfile.shortPutDeltaTarget),
                        maxDeltaErr = 0.18,
                        dteDist = abs(shortLeg.dte - 35) / 15.0
                    )
                    val ctwPts = ((creditToWidth / 0.36) * 20.0).coerceIn(4.0, 22.0)
                    val popPts = (((pop - 55.0) / 30.0) * 13.0).coerceIn(0.0, 13.0)
                    val structural = ctwPts + popPts
                    val liquidity = scoreLiquidity(listOf(shortLeg, longLeg))
                    val regimeCapital = scoreRegimeCapital(
                        type = OptimizerStrategyType.BULL_PUT_VERTICAL,
                        fit = fit,
                        activeRegime = activeRegime,
                        volRank52w = volRank52w,
                        bpr = bpr,
                        maxCapitalBudget = maxCapitalBudget
                    )
                    val breakdown = StrategyRankingBreakdown.of(deltaDte, structural, liquidity, regimeCapital)
                    val be = shortLeg.strike - credit
                    val cushionPct = ((spot - be) / spot) * 100.0

                    val rationale = listOf(
                        "${shortLeg.dte}DTE Short ${fmtStrike(shortLeg.strike)}P (Δ ${fmt2(shortLeg.delta)}) aligns with ${riskProfile.label} target ${fmt2(riskProfile.shortPutDeltaTarget)}.",
                        "Collects \$${fmt2(credit)} credit on \$${fmt2(width)} width (${fmt1(creditToWidth * 100.0)}% ROI-on-width, BE \$${fmt2(be)}, ${fmt1(cushionPct)}% OTM).",
                        "Defined risk \$${fmt0(bpr)} BPR with min leg OI ${minOf(shortLeg.openInterest, longLeg.openInterest)}."
                    )

                    list.add(
                        buildCandidate(
                            id = "${cleanSymbol}_BPUT_${shortLeg.symbol}_${longLeg.symbol}",
                            cleanSymbol = cleanSymbol,
                            spot = spot,
                            type = OptimizerStrategyType.BULL_PUT_VERTICAL,
                            legs = listOf(
                                shortLeg.toOptimizerLeg(LegAction.SELL_TO_OPEN),
                                longLeg.toOptimizerLeg(LegAction.BUY_TO_OPEN)
                            ),
                            breakdown = breakdown,
                            fit = fit,
                            orderType = OrderType.NET_CREDIT,
                            netPerShare = credit,
                            isNetCredit = true,
                            estBid = max(0.01, shortLeg.bid - longLeg.ask),
                            estAsk = max(0.01, shortLeg.ask - longLeg.bid),
                            bpr = bpr,
                            maxProfit = maxProfit,
                            maxLoss = bpr,
                            breakevens = listOf(be),
                            popPct = pop,
                            rationale = rationale,
                            summaryLine = "${shortLeg.dte}D -${fmtStrike(shortLeg.strike)}/+${fmtStrike(longLeg.strike)}P @ \$${fmt2(credit)} cr"
                        )
                    )
                }
            }
        }
        return list
    }

    private fun generateBearCall(
        chain: ParsedOptionChain,
        cleanSymbol: String,
        activeRegime: MarketRegime,
        fit: OptimizerRegimeFit,
        volRank52w: Double?,
        maxCapitalBudget: Double?,
        riskProfile: OptimizerRiskProfile
    ): List<OptimizedStrategyCandidate> {
        val spot = chain.underlyingPrice
        val minWing = max(1.0, spot * 0.015)
        val maxWing = max(minWing + 1.0, spot * 0.11)
        val list = mutableListOf<OptimizedStrategyCandidate>()

        val byExp = chain.calls
            .filter { it.dte in 25..45 && isLiquid(it) }
            .groupBy { it.expirationDate }

        for ((_, calls) in byExp) {
            val shorts = calls
                .filter { it.delta in 0.14..0.42 && it.strike >= spot * 0.98 }
                .sortedBy { abs(it.delta - riskProfile.shortCallDeltaTarget) }
                .take(6)
            for (shortLeg in shorts) {
                val wings = calls
                    .filter { longLeg ->
                        val w = longLeg.strike - shortLeg.strike
                        w in minWing..maxWing
                    }
                    .sortedBy { abs((it.strike - shortLeg.strike) - spot * 0.04) }
                    .take(6)
                for (longLeg in wings) {
                    val credit = shortLeg.mid - longLeg.mid
                    if (credit <= 0.05) continue
                    val width = longLeg.strike - shortLeg.strike
                    val creditToWidth = credit / width
                    if (creditToWidth < 0.10) continue

                    val bpr = max(50.0, (width - credit) * 100.0)
                    val maxProfit = credit * 100.0
                    val pop = ((1.0 - shortLeg.delta) * 100.0).coerceIn(50.0, 92.0)

                    val deltaDte = scoreDeltaDte(
                        deltaErr = abs(shortLeg.delta - riskProfile.shortCallDeltaTarget),
                        maxDeltaErr = 0.18,
                        dteDist = abs(shortLeg.dte - 35) / 15.0
                    )
                    val ctwPts = ((creditToWidth / 0.36) * 20.0).coerceIn(4.0, 22.0)
                    val popPts = (((pop - 55.0) / 30.0) * 13.0).coerceIn(0.0, 13.0)
                    val structural = ctwPts + popPts
                    val liquidity = scoreLiquidity(listOf(shortLeg, longLeg))
                    val regimeCapital = scoreRegimeCapital(
                        type = OptimizerStrategyType.BEAR_CALL_VERTICAL,
                        fit = fit,
                        activeRegime = activeRegime,
                        volRank52w = volRank52w,
                        bpr = bpr,
                        maxCapitalBudget = maxCapitalBudget
                    )
                    val breakdown = StrategyRankingBreakdown.of(deltaDte, structural, liquidity, regimeCapital)
                    val be = shortLeg.strike + credit
                    val cushionPct = ((be - spot) / spot) * 100.0

                    val rationale = listOf(
                        "${shortLeg.dte}DTE Short ${fmtStrike(shortLeg.strike)}C (Δ ${fmt2(shortLeg.delta)}) hits ${riskProfile.label} call delta target.",
                        "Net Credit \$${fmt2(credit)} on \$${fmt2(width)} width (${fmt1(creditToWidth * 100.0)}% credit-to-width, BE \$${fmt2(be)}, +${fmt1(cushionPct)}% above spot).",
                        "Defined risk \$${fmt0(bpr)} BPR with min OI ${minOf(shortLeg.openInterest, longLeg.openInterest)}."
                    )

                    list.add(
                        buildCandidate(
                            id = "${cleanSymbol}_BCALL_${shortLeg.symbol}_${longLeg.symbol}",
                            cleanSymbol = cleanSymbol,
                            spot = spot,
                            type = OptimizerStrategyType.BEAR_CALL_VERTICAL,
                            legs = listOf(
                                shortLeg.toOptimizerLeg(LegAction.SELL_TO_OPEN),
                                longLeg.toOptimizerLeg(LegAction.BUY_TO_OPEN)
                            ),
                            breakdown = breakdown,
                            fit = fit,
                            orderType = OrderType.NET_CREDIT,
                            netPerShare = credit,
                            isNetCredit = true,
                            estBid = max(0.01, shortLeg.bid - longLeg.ask),
                            estAsk = max(0.01, shortLeg.ask - longLeg.bid),
                            bpr = bpr,
                            maxProfit = maxProfit,
                            maxLoss = bpr,
                            breakevens = listOf(be),
                            popPct = pop,
                            rationale = rationale,
                            summaryLine = "${shortLeg.dte}D -${fmtStrike(shortLeg.strike)}/+${fmtStrike(longLeg.strike)}C @ \$${fmt2(credit)} cr"
                        )
                    )
                }
            }
        }
        return list
    }

    private fun generateCalendar(
        chain: ParsedOptionChain,
        cleanSymbol: String,
        activeRegime: MarketRegime,
        fit: OptimizerRegimeFit,
        volRank52w: Double?,
        maxCapitalBudget: Double?,
        riskProfile: OptimizerRiskProfile
    ): List<OptimizedStrategyCandidate> {
        val spot = chain.underlyingPrice
        val frontPool = chain.calls.filter { it.dte in 21..35 && isLiquid(it) }
        val backPool = chain.calls.filter { it.dte in 60..90 && isLiquid(it) }
        if (frontPool.isEmpty() || backPool.isEmpty()) return emptyList()

        val frontByStrike = frontPool.groupBy { it.strike }
        val backByStrike = backPool.groupBy { it.strike }
        val targetBias = when (riskProfile) {
            OptimizerRiskProfile.CONSERVATIVE -> 1.00
            OptimizerRiskProfile.BALANCED -> 1.005
            OptimizerRiskProfile.AGGRESSIVE -> 1.015
        }
        val commonStrikes = (frontByStrike.keys intersect backByStrike.keys)
            .sortedBy { abs(it - spot * targetBias) }
            .take(6)

        val list = mutableListOf<OptimizedStrategyCandidate>()
        for (strike in commonStrikes) {
            val shorts = frontByStrike[strike].orEmpty()
                .sortedBy { abs(it.dte - 28) }
                .take(2)
            val longs = backByStrike[strike].orEmpty()
                .sortedBy { abs(it.dte - 75) }
                .take(2)
            for (shortLeg in shorts) {
                for (longLeg in longs) {
                    val netDebit = longLeg.mid - shortLeg.mid
                    if (netDebit <= 0.05) continue
                    val bpr = netDebit * 100.0
                    val ivTermSpread = shortLeg.iv - longLeg.iv
                    val netTheta = longLeg.thetaPerDay - shortLeg.thetaPerDay
                    val netVega = longLeg.vegaPer1Pct - shortLeg.vegaPer1Pct
                    val thetaVegaRatio = if (netVega > 1e-4) abs(netTheta) / netVega else 0.0

                    val deltaDte = scoreDeltaDte(
                        deltaErr = abs(abs(shortLeg.delta) - 0.50),
                        maxDeltaErr = 0.25,
                        dteDist = (abs(shortLeg.dte - 28) / 14.0 + abs(longLeg.dte - 75) / 20.0) * 0.5
                    )
                    val termPts = ((ivTermSpread + 0.04) * 180.0).coerceIn(2.0, 20.0)
                    val decayPts = (thetaVegaRatio * 20.0).coerceIn(0.0, 15.0)
                    val structural = termPts + decayPts
                    val liquidity = scoreLiquidity(listOf(shortLeg, longLeg))
                    val regimeCapital = scoreRegimeCapital(
                        type = OptimizerStrategyType.CALENDAR,
                        fit = fit,
                        activeRegime = activeRegime,
                        volRank52w = volRank52w,
                        bpr = bpr,
                        maxCapitalBudget = maxCapitalBudget
                    )
                    val breakdown = StrategyRankingBreakdown.of(deltaDte, structural, liquidity, regimeCapital)
                    val pop = 54.0 + (ivTermSpread * 100.0).coerceIn(-6.0, 12.0)

                    val rationale = listOf(
                        "ATM strike \$${fmtStrike(strike)} captures max pin gamma on ${shortLeg.dte}D short vs ${longLeg.dte}D long.",
                        "Front-back IV spread ${fmt1(ivTermSpread * 100.0)} pts; net theta \$${fmt2(netTheta)}/d for \$${fmt2(netDebit)} debit.",
                        "Both expirations pass liquidity filters (min OI ${minOf(shortLeg.openInterest, longLeg.openInterest)})."
                    )

                    list.add(
                        buildCandidate(
                            id = "${cleanSymbol}_CAL_${longLeg.symbol}_${shortLeg.symbol}",
                            cleanSymbol = cleanSymbol,
                            spot = spot,
                            type = OptimizerStrategyType.CALENDAR,
                            legs = listOf(
                                longLeg.toOptimizerLeg(LegAction.BUY_TO_OPEN),
                                shortLeg.toOptimizerLeg(LegAction.SELL_TO_OPEN)
                            ),
                            breakdown = breakdown,
                            fit = fit,
                            orderType = OrderType.NET_DEBIT,
                            netPerShare = netDebit,
                            isNetCredit = false,
                            estBid = max(0.01, longLeg.bid - shortLeg.ask),
                            estAsk = max(0.01, longLeg.ask - shortLeg.bid),
                            bpr = bpr,
                            maxProfit = null,
                            maxLoss = bpr,
                            breakevens = listOf(max(0.01, strike - netDebit * 0.9), strike + netDebit * 0.9),
                            popPct = pop.coerceIn(35.0, 75.0),
                            rationale = rationale,
                            summaryLine = "${shortLeg.dte}D/${longLeg.dte}D ${fmtStrike(strike)}C Cal @ \$${fmt2(netDebit)} db"
                        )
                    )
                }
            }
        }
        return list
    }

    private fun generateIronCondor(
        chain: ParsedOptionChain,
        cleanSymbol: String,
        activeRegime: MarketRegime,
        fit: OptimizerRegimeFit,
        volRank52w: Double?,
        maxCapitalBudget: Double?,
        riskProfile: OptimizerRiskProfile
    ): List<OptimizedStrategyCandidate> {
        val spot = chain.underlyingPrice
        val minWing = max(1.0, spot * 0.015)
        val maxWing = max(minWing + 1.0, spot * 0.09)
        val list = mutableListOf<OptimizedStrategyCandidate>()

        val exps = chain.contracts
            .filter { it.dte in 30..45 && isLiquid(it) }
            .groupBy { it.expirationDate }

        val targetWingDelta = riskProfile.wingShortDeltaTarget

        for ((_, expContracts) in exps) {
            val puts = expContracts.filter { !it.isCall }
            val calls = expContracts.filter { it.isCall }

            val putPairs = mutableListOf<Pair<ParsedOptionContract, ParsedOptionContract>>()
            val shortPuts = puts
                .filter { abs(it.delta) in 0.12..0.34 && it.strike < spot }
                .sortedBy { abs(abs(it.delta) - targetWingDelta) }
                .take(4)
            for (sp in shortPuts) {
                val lp = puts
                    .filter { (sp.strike - it.strike) in minWing..maxWing }
                    .minByOrNull { abs((sp.strike - it.strike) - spot * 0.04) }
                if (lp != null && sp.mid > lp.mid + 0.02) {
                    putPairs.add(sp to lp)
                }
            }

            val callPairs = mutableListOf<Pair<ParsedOptionContract, ParsedOptionContract>>()
            val shortCalls = calls
                .filter { it.delta in 0.12..0.34 && it.strike > spot }
                .sortedBy { abs(it.delta - targetWingDelta) }
                .take(4)
            for (sc in shortCalls) {
                val lc = calls
                    .filter { (it.strike - sc.strike) in minWing..maxWing }
                    .minByOrNull { abs((it.strike - sc.strike) - spot * 0.04) }
                if (lc != null && sc.mid > lc.mid + 0.02) {
                    callPairs.add(sc to lc)
                }
            }

            for ((sp, lp) in putPairs.take(4)) {
                for ((sc, lc) in callPairs.take(4)) {
                    if (sc.strike <= sp.strike) continue
                    val putCredit = sp.mid - lp.mid
                    val callCredit = sc.mid - lc.mid
                    val totalCredit = putCredit + callCredit
                    if (totalCredit <= 0.10) continue

                    val putWidth = sp.strike - lp.strike
                    val callWidth = lc.strike - sc.strike
                    val maxWidth = max(putWidth, callWidth)
                    val creditToWidth = totalCredit / maxWidth
                    if (creditToWidth < 0.14) continue

                    val bpr = max(50.0, (maxWidth - totalCredit) * 100.0)
                    val maxProfit = totalCredit * 100.0
                    val pop = ((1.0 - abs(sp.delta) - sc.delta) * 100.0).coerceIn(35.0, 88.0)

                    val deltaErr = (abs(abs(sp.delta) - targetWingDelta) + abs(sc.delta - targetWingDelta)) * 0.5
                    val deltaDte = scoreDeltaDte(
                        deltaErr = deltaErr,
                        maxDeltaErr = 0.16,
                        dteDist = abs(sp.dte - 37) / 15.0
                    )
                    val ctwPts = ((creditToWidth / 0.42) * 20.0).coerceIn(4.0, 21.0)
                    val popPts = (((pop - 45.0) / 32.0) * 14.0).coerceIn(0.0, 14.0)
                    val structural = ctwPts + popPts
                    val legs4 = listOf(sp, lp, sc, lc)
                    val liquidity = scoreLiquidity(legs4)
                    val regimeCapital = scoreRegimeCapital(
                        type = OptimizerStrategyType.IRON_CONDOR,
                        fit = fit,
                        activeRegime = activeRegime,
                        volRank52w = volRank52w,
                        bpr = bpr,
                        maxCapitalBudget = maxCapitalBudget
                    )
                    val breakdown = StrategyRankingBreakdown.of(deltaDte, structural, liquidity, regimeCapital)
                    val lowBe = sp.strike - totalCredit
                    val highBe = sc.strike + totalCredit

                    val rationale = listOf(
                        "${sp.dte}DTE Short ${fmtStrike(sp.strike)}P (Δ ${fmt2(sp.delta)}) / ${fmtStrike(sc.strike)}C (Δ ${fmt2(sc.delta)}) strangles spot.",
                        "Collects \$${fmt2(totalCredit)} total credit (${fmt1(creditToWidth * 100.0)}% of \$${fmt2(maxWidth)} max wing, POP ${fmt0(pop)}%, BE \$${fmt2(lowBe)}–\$${fmt2(highBe)}).",
                        "All 4 legs liquid (min OI ${legs4.minOf { it.openInterest }}) with defined \$${fmt0(bpr)} BPR."
                    )

                    list.add(
                        buildCandidate(
                            id = "${cleanSymbol}_IC_${sp.symbol}_${sc.symbol}",
                            cleanSymbol = cleanSymbol,
                            spot = spot,
                            type = OptimizerStrategyType.IRON_CONDOR,
                            legs = listOf(
                                lp.toOptimizerLeg(LegAction.BUY_TO_OPEN),
                                sp.toOptimizerLeg(LegAction.SELL_TO_OPEN),
                                sc.toOptimizerLeg(LegAction.SELL_TO_OPEN),
                                lc.toOptimizerLeg(LegAction.BUY_TO_OPEN)
                            ),
                            breakdown = breakdown,
                            fit = fit,
                            orderType = OrderType.NET_CREDIT,
                            netPerShare = totalCredit,
                            isNetCredit = true,
                            estBid = max(0.01, (sp.bid + sc.bid) - (lp.ask + lc.ask)),
                            estAsk = max(0.01, (sp.ask + sc.ask) - (lp.bid + lc.bid)),
                            bpr = bpr,
                            maxProfit = maxProfit,
                            maxLoss = bpr,
                            breakevens = listOf(lowBe, highBe),
                            popPct = pop,
                            rationale = rationale,
                            summaryLine = "${sp.dte}D ${fmtStrike(lp.strike)}/${fmtStrike(sp.strike)}P-${fmtStrike(sc.strike)}/${fmtStrike(lc.strike)}C @ \$${fmt2(totalCredit)} cr"
                        )
                    )
                }
            }
        }
        return list
    }

    private fun generateWheelCsp(
        chain: ParsedOptionChain,
        cleanSymbol: String,
        activeRegime: MarketRegime,
        fit: OptimizerRegimeFit,
        volRank52w: Double?,
        sma50: Double?,
        maxCapitalBudget: Double?,
        riskProfile: OptimizerRiskProfile
    ): List<OptimizedStrategyCandidate> {
        val spot = chain.underlyingPrice
        val puts = chain.puts
            .filter { it.dte in 25..45 && abs(it.delta) in 0.14..0.46 && it.strike <= spot * 1.02 && isLiquid(it) }
            .sortedBy { abs(it.delta - riskProfile.shortPutDeltaTarget) + abs(it.dte - 35) / 100.0 }
            .take(8)
        if (puts.isEmpty()) return emptyList()

        return puts.map { putLeg ->
            val credit = putLeg.mid
            val bpr = max(50.0, (putLeg.strike - credit) * 100.0)
            val maxProfit = credit * 100.0
            val annRocPct = ((maxProfit / bpr) * (365.0 / putLeg.dte.coerceAtLeast(1))) * 100.0
            val pop = ((1.0 - abs(putLeg.delta)) * 100.0).coerceIn(50.0, 90.0)
            val discountPct = ((spot - (putLeg.strike - credit)) / spot) * 100.0
            val belowSmaBonus = if (sma50 != null && putLeg.strike <= sma50) 4.0 else 0.0

            val deltaDte = scoreDeltaDte(
                deltaErr = abs(putLeg.delta - riskProfile.shortPutDeltaTarget),
                maxDeltaErr = 0.20,
                dteDist = abs(putLeg.dte - 35) / 15.0
            )
            val rocPts = (annRocPct / 28.0 * 21.0).coerceIn(3.0, 21.0)
            val cushionPts = (discountPts(discountPct) + belowSmaBonus).coerceIn(0.0, 14.0)
            val structural = rocPts + cushionPts
            val liquidity = scoreLiquidity(listOf(putLeg))
            val regimeCapital = scoreRegimeCapital(
                type = OptimizerStrategyType.WHEEL_CSP,
                fit = fit,
                activeRegime = activeRegime,
                volRank52w = volRank52w,
                bpr = bpr,
                maxCapitalBudget = maxCapitalBudget
            )
            val breakdown = StrategyRankingBreakdown.of(deltaDte, structural, liquidity, regimeCapital)
            val be = putLeg.strike - credit

            val rationale = listOf(
                "${putLeg.dte}DTE Short ${fmtStrike(putLeg.strike)}P (Δ ${fmt2(putLeg.delta)}) hits ${riskProfile.label} assignment target.",
                "Receives \$${fmt2(credit)} premium (${fmt1(annRocPct)}% annualized ROC, effective basis \$${fmt2(be)}, ${fmt1(discountPct)}% discount to spot).",
                "Cash collateral \$${fmt0(bpr)} BPR; OI ${putLeg.openInterest} with ${fmt1(putLeg.spreadWidthPct * 100.0)}% bid-ask spread."
            )

            buildCandidate(
                id = "${cleanSymbol}_CSP_${putLeg.symbol}",
                cleanSymbol = cleanSymbol,
                spot = spot,
                type = OptimizerStrategyType.WHEEL_CSP,
                legs = listOf(putLeg.toOptimizerLeg(LegAction.SELL_TO_OPEN)),
                breakdown = breakdown,
                fit = fit,
                orderType = OrderType.LIMIT,
                netPerShare = credit,
                isNetCredit = true,
                estBid = max(0.01, putLeg.bid),
                estAsk = max(0.01, putLeg.ask),
                bpr = bpr,
                maxProfit = maxProfit,
                maxLoss = bpr,
                breakevens = listOf(be),
                popPct = pop,
                rationale = rationale,
                summaryLine = "${putLeg.dte}D -${fmtStrike(putLeg.strike)}P @ \$${fmt2(credit)} cr (${fmt1(annRocPct)}% Ann)"
            )
        }
    }

    private fun buildCandidate(
        id: String,
        cleanSymbol: String,
        spot: Double,
        type: OptimizerStrategyType,
        legs: List<OptimizerLeg>,
        breakdown: StrategyRankingBreakdown,
        fit: OptimizerRegimeFit,
        orderType: OrderType,
        netPerShare: Double,
        isNetCredit: Boolean,
        estBid: Double,
        estAsk: Double,
        bpr: Double,
        maxProfit: Double?,
        maxLoss: Double,
        breakevens: List<Double>,
        popPct: Double,
        rationale: List<String>,
        summaryLine: String
    ): OptimizedStrategyCandidate {
        val netDelta = legs.sumOf { (if (it.action == LegAction.BUY_TO_OPEN) 1.0 else -1.0) * it.delta }
        val netTheta = legs.sumOf { (if (it.action == LegAction.BUY_TO_OPEN) 1.0 else -1.0) * it.thetaPerDay }
        val netGamma = legs.sumOf { (if (it.action == LegAction.BUY_TO_OPEN) 1.0 else -1.0) * it.gamma }
        val netVega = legs.sumOf { (if (it.action == LegAction.BUY_TO_OPEN) 1.0 else -1.0) * it.vegaPer1Pct }
        return OptimizedStrategyCandidate(
            id = id,
            underlyingSymbol = cleanSymbol,
            underlyingSpot = spot,
            strategyType = type,
            legs = legs,
            breakdown = breakdown,
            regimeFit = fit,
            orderType = orderType,
            netDebitOrCreditPerShare = netPerShare,
            isNetCredit = isNetCredit,
            estimatedBid = estBid,
            estimatedAsk = estAsk,
            buyingPowerRequirement = bpr,
            maxProfit = maxProfit,
            maxLoss = maxLoss,
            breakevens = breakevens,
            popPct = popPct,
            netDelta = netDelta,
            netThetaPerDay = netTheta,
            netGamma = netGamma,
            netVegaPer1Pct = netVega,
            minOpenInterest = legs.minOf { it.openInterest },
            maxSpreadWidthPct = legs.maxOf { it.spreadWidthPct },
            legRationaleBullets = rationale.take(3),
            summaryLine = summaryLine
        )
    }

    private fun isLiquid(c: ParsedOptionContract): Boolean {
        if (c.bid <= 0.0 || c.ask <= c.bid || c.mid < 0.05) return false
        if (c.openInterest < MIN_OPEN_INTEREST) return false
        val maxSpread = if (c.dte >= 180) MAX_LEAPS_SPREAD_PCT else MAX_STANDARD_SPREAD_PCT
        return c.spreadWidthPct <= maxSpread
    }

    private fun scoreDeltaDte(deltaErr: Double, maxDeltaErr: Double, dteDist: Double): Double {
        val deltaPts = (1.0 - (deltaErr / maxDeltaErr).coerceIn(0.0, 1.0)) * 20.0
        val dtePts = (1.0 - dteDist.coerceIn(0.0, 1.0)) * 10.0
        return (deltaPts + dtePts).coerceIn(0.0, 30.0)
    }

    private fun scoreLiquidity(contracts: List<ParsedOptionContract>): Double {
        val avgSpread = contracts.map { it.spreadWidthPct }.average()
        val spreadPts = ((1.0 - (avgSpread / MAX_LEAPS_SPREAD_PCT).coerceIn(0.0, 1.0)) * 12.0).coerceIn(0.0, 12.0)
        val minOi = contracts.minOf { it.openInterest }.coerceAtLeast(1)
        val oiPts = ((ln(1.0 + minOi) / ln(1.0 + 3000.0)) * 8.0).coerceIn(0.0, 8.0)
        return (spreadPts + oiPts).coerceIn(0.0, 20.0)
    }

    private fun scoreRegimeCapital(
        type: OptimizerStrategyType,
        fit: OptimizerRegimeFit,
        activeRegime: MarketRegime,
        volRank52w: Double?,
        bpr: Double,
        maxCapitalBudget: Double?
    ): Double {
        val regimeBase = when (fit) {
            OptimizerRegimeFit.ALIGNED -> 9.0
            OptimizerRegimeFit.NEUTRAL -> 5.5
            OptimizerRegimeFit.COUNTER_REGIME_CAUTION -> 1.5
        }
        val vr = volRank52w ?: 50.0
        val volPts = when (type) {
            OptimizerStrategyType.PMCC,
            OptimizerStrategyType.PMCP,
            OptimizerStrategyType.CALENDAR -> when {
                vr < 35.0 -> 3.0
                vr < 55.0 -> 2.0
                else -> 1.0
            }
            OptimizerStrategyType.BULL_PUT_VERTICAL,
            OptimizerStrategyType.BEAR_CALL_VERTICAL,
            OptimizerStrategyType.IRON_CONDOR,
            OptimizerStrategyType.WHEEL_CSP -> when {
                vr >= 55.0 -> 3.0
                vr >= 35.0 -> 2.0
                else -> 1.0
            }
        }
        val capPts = if (maxCapitalBudget != null && maxCapitalBudget > 0.0) {
            val util = (bpr / maxCapitalBudget).coerceIn(0.0, 1.0)
            (1.0 - util * 0.5) * 3.0
        } else {
            when {
                bpr <= 1_500.0 -> 3.0
                bpr <= 5_000.0 -> 2.5
                bpr <= 12_000.0 -> 1.8
                else -> 1.0
            }
        }
        val regimeBiasAdj = when {
            activeRegime == MarketRegime.CONFIRMED_BEAR &&
                (type == OptimizerStrategyType.BEAR_CALL_VERTICAL || type == OptimizerStrategyType.PMCP) -> 0.5
            activeRegime == MarketRegime.RISK_ON_BULL &&
                (type == OptimizerStrategyType.PMCC || type == OptimizerStrategyType.BULL_PUT_VERTICAL) -> 0.5
            else -> 0.0
        }
        return (regimeBase + volPts + capPts + regimeBiasAdj).coerceIn(0.0, 15.0)
    }

    private fun classifyRegimeFit(
        type: OptimizerStrategyType,
        activeRegime: MarketRegime
    ): OptimizerRegimeFit = when (activeRegime) {
        MarketRegime.RISK_ON_BULL -> when (type) {
            OptimizerStrategyType.PMCC,
            OptimizerStrategyType.BULL_PUT_VERTICAL,
            OptimizerStrategyType.WHEEL_CSP -> OptimizerRegimeFit.ALIGNED
            OptimizerStrategyType.CALENDAR,
            OptimizerStrategyType.IRON_CONDOR -> OptimizerRegimeFit.NEUTRAL
            OptimizerStrategyType.BEAR_CALL_VERTICAL,
            OptimizerStrategyType.PMCP -> OptimizerRegimeFit.COUNTER_REGIME_CAUTION
        }
        MarketRegime.CAUTION_ELEVATED_VOL -> when (type) {
            OptimizerStrategyType.IRON_CONDOR,
            OptimizerStrategyType.BULL_PUT_VERTICAL,
            OptimizerStrategyType.BEAR_CALL_VERTICAL,
            OptimizerStrategyType.CALENDAR -> OptimizerRegimeFit.ALIGNED
            OptimizerStrategyType.PMCC,
            OptimizerStrategyType.PMCP,
            OptimizerStrategyType.WHEEL_CSP -> OptimizerRegimeFit.NEUTRAL
        }
        MarketRegime.CONFIRMED_BEAR -> when (type) {
            OptimizerStrategyType.PMCP,
            OptimizerStrategyType.BEAR_CALL_VERTICAL -> OptimizerRegimeFit.ALIGNED
            OptimizerStrategyType.IRON_CONDOR,
            OptimizerStrategyType.CALENDAR -> OptimizerRegimeFit.NEUTRAL
            OptimizerStrategyType.PMCC,
            OptimizerStrategyType.BULL_PUT_VERTICAL,
            OptimizerStrategyType.WHEEL_CSP -> OptimizerRegimeFit.COUNTER_REGIME_CAUTION
        }
    }

    private fun buildRankingExplanation(
        rank: Int,
        out: StrategySearchOutput,
        winner: OptimizedStrategyCandidate?,
        prev: StrategySearchOutput?,
        next: StrategySearchOutput?,
        topScore: Double,
        activeRegime: MarketRegime,
        volRank52w: Double?
    ): String {
        val vrText = volRank52w?.let { "Vol Rank ${fmt0(it)}%" } ?: "Vol Rank unavailable"
        if (winner == null) {
            return "Ranked #$rank of 7 (0.0/100 — Unavailable): ${out.failureReason ?: "No valid contract combination."} " +
                "Active regime '${activeRegime.label}' classifies ${out.strategyType.shortLabel} as ${out.regimeFit.label}; $vrText."
        }
        val b = winner.breakdown
        val deltaNote = when {
            rank == 1 && next != null -> {
                val lead = b.compositeScore - (next.candidates.firstOrNull()?.breakdown?.compositeScore ?: 0.0)
                "Leads #2 (${next.strategyType.shortLabel}) by +${fmt1(lead)} pts."
            }
            prev != null && prev.candidates.isNotEmpty() -> {
                val prevScore = prev.candidates.first().breakdown.compositeScore
                val gapToPrev = prevScore - b.compositeScore
                val gapToTop = topScore - b.compositeScore
                if (rank > 2) {
                    "Trails #${rank - 1} (${prev.strategyType.shortLabel}) by -${fmt1(gapToPrev)} pts (-${fmt1(gapToTop)} behind #1)."
                } else {
                    "Trails #1 (${prev.strategyType.shortLabel}) by -${fmt1(gapToPrev)} pts."
                }
            }
            else -> "Only eligible strategy meeting filters."
        }
        return "Ranked #$rank of 7 (${fmt1(b.compositeScore)}/100): " +
            "Delta/DTE ${fmt1(b.deltaDteScore)}/30 + Structure ${fmt1(b.structuralEdgeScore)}/35 + " +
            "Liquidity ${fmt1(b.liquidityScore)}/20 + Regime/Cap ${fmt1(b.regimeCapitalScore)}/15. " +
            "${activeRegime.label} -> ${out.regimeFit.label}; $vrText. $deltaNote"
    }

    private fun discountPts(discountPct: Double): Double =
        ((discountPct / 10.0) * 10.0).coerceIn(0.0, 10.0)

    private fun ParsedOptionContract.toOptimizerLeg(action: LegAction): OptimizerLeg = OptimizerLeg(
        occSymbol = symbol,
        action = action,
        quantity = 1,
        isCall = isCall,
        strike = strike,
        expirationDate = expirationDate,
        dte = dte,
        bid = bid,
        ask = ask,
        mid = mid,
        delta = delta,
        gamma = gamma,
        thetaPerDay = thetaPerDay,
        vegaPer1Pct = vegaPer1Pct,
        iv = iv,
        openInterest = openInterest,
        spreadWidthPct = spreadWidthPct
    )

    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmtStrike(v: Double): String =
        if (abs(v - v.toLong()) < 0.01) v.toLong().toString() else fmt2(v)

    companion object {
        private const val MIN_OPEN_INTEREST = 25
        private const val MAX_STANDARD_SPREAD_PCT = 0.15
        private const val MAX_LEAPS_SPREAD_PCT = 0.20
        private const val MAX_RUNNER_UPS = 2
    }
}
