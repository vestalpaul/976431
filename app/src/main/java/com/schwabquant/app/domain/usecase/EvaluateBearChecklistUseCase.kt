package com.schwabquant.app.domain.usecase

import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.remote.dto.PriceHistoryResponseDto
import com.schwabquant.app.data.remote.dto.QuoteEntryDto
import com.schwabquant.app.domain.model.BearChecklistEvaluation
import com.schwabquant.app.domain.model.BearSignalEvaluation
import com.schwabquant.app.domain.model.BearSignalId
import com.schwabquant.app.domain.model.DemoRegimePreset
import com.schwabquant.app.domain.model.LiquidUniverse
import com.schwabquant.app.domain.model.MarketRegime
import com.schwabquant.app.domain.model.PortfolioDirective
import com.schwabquant.app.domain.model.RegimeEvaluationInput
import com.schwabquant.app.domain.model.RegimeStateHolder
import com.schwabquant.app.domain.model.SignalStatus
import com.schwabquant.app.domain.repository.MarketDataRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

/**
 * Automated Bear-Market 6-Signal Checklist Evaluator combining Schwab daily price history
 * with live intraday marks, plus deterministic offline Demo presets for disconnected exploration.
 */
@Singleton
class EvaluateBearChecklistUseCase(
    private val marketDataRepository: MarketDataRepository? = null,
    private val credentialVault: CredentialVault? = null,
    private val regimeStateHolder: RegimeStateHolder? = null,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {

    @Inject
    constructor(
        marketDataRepository: MarketDataRepository,
        credentialVault: CredentialVault,
        regimeStateHolder: RegimeStateHolder
    ) : this(
        marketDataRepository = marketDataRepository,
        credentialVault = credentialVault,
        regimeStateHolder = regimeStateHolder,
        nowProvider = System::currentTimeMillis
    )

    fun hasLiveAuth(): Boolean = !credentialVault?.getAccessToken().isNullOrBlank()

    suspend fun evaluateLive(forceRefresh: Boolean = false): Result<BearChecklistEvaluation> = runCatching {
        val repo = marketDataRepository ?: error("MarketDataRepository not configured.")
        val coreUniverse = LiquidUniverse.CORE_25_LIQUID_TICKERS
        val quoteSymbols = (coreUniverse + listOf(SYMBOL_VIX, SYMBOL_VIX3M, SYMBOL_HYG, SYMBOL_LQD)).distinct()

        val quotes = repo.getQuotes(quoteSymbols, forceRefresh = forceRefresh).getOrThrow()

        val historySymbols = (coreUniverse + listOf(SYMBOL_VIX, SYMBOL_HYG, SYMBOL_LQD)).distinct()
        val semaphore = Semaphore(MAX_LIVE_HISTORY_CONCURRENCY)

        val histories = coroutineScope {
            historySymbols.map { sym ->
                async {
                    semaphore.withPermit {
                        val dto = repo.getPriceHistory(
                            symbol = sym,
                            periodType = "year",
                            period = 1,
                            frequencyType = "daily",
                            frequency = 1,
                            forceRefresh = forceRefresh
                        ).getOrNull()
                        sym to extractSeriesWithIntradayMark(dto, quotes[sym])
                    }
                }
            }.awaitAll().toMap()
        }

        val spyCloses = histories[SYMBOL_SPY].orEmpty()
        require(spyCloses.size >= 200) {
            "Insufficient SPY daily history (${spyCloses.size} bars); verify Schwab API token."
        }

        val universeCloses = coreUniverse.associateWith { sym ->
            histories[sym].orEmpty()
        }

        val evaluation = evaluate(
            RegimeEvaluationInput(
                spyCloses = spyCloses,
                iwmCloses = histories[SYMBOL_IWM].orEmpty(),
                vixCloses = histories[SYMBOL_VIX].orEmpty(),
                vix3mLatest = extractLiveMark(quotes[SYMBOL_VIX3M]),
                hygCloses = histories[SYMBOL_HYG].orEmpty(),
                lqdCloses = histories[SYMBOL_LQD].orEmpty(),
                universeCloses = universeCloses,
                isDemoData = false,
                demoPreset = null,
                evaluatedAtEpochMs = nowProvider()
            )
        )
        regimeStateHolder?.update(evaluation)
        evaluation
    }

    fun evaluateDemo(preset: DemoRegimePreset = DemoRegimePreset.CAUTION): BearChecklistEvaluation {
        val input = buildDemoInput(preset)
        val evaluation = evaluate(input)
        regimeStateHolder?.update(evaluation)
        return evaluation
    }

    /**
     * Pure-JVM deterministic evaluator scoring all 6 Bear-Market signals.
     */
    fun evaluate(input: RegimeEvaluationInput): BearChecklistEvaluation {
        val spySig = evaluateSpyTrend(input.spyCloses)
        val iwmSig = evaluateIwmCanary(input.iwmCloses, input.spyCloses)
        val vixSig = evaluateVixLevel(input.vixCloses)
        val termSig = evaluateTermStructure(input.vixCloses.lastOrNull(), input.vix3mLatest)
        val creditSig = evaluateCreditStress(input.hygCloses, input.lqdCloses)
        val breadthSig = evaluateMarketBreadth(input.universeCloses)

        val signals = listOf(spySig, iwmSig, vixSig, termSig, creditSig, breadthSig)
        val redCount = signals.count { it.status == SignalStatus.RED }
        val amberCount = signals.count { it.status == SignalStatus.AMBER }
        val weightedScore = signals.sumOf { it.status.weight }

        val regime = when {
            redCount >= 4 -> MarketRegime.CONFIRMED_BEAR
            redCount >= 2 -> MarketRegime.CAUTION_ELEVATED_VOL
            else -> MarketRegime.RISK_ON_BULL
        }

        return BearChecklistEvaluation(
            regime = regime,
            redCount = redCount,
            amberCount = amberCount,
            weightedScore = weightedScore,
            totalSignals = signals.size,
            signals = signals,
            directive = directiveFor(regime, redCount),
            isDemoData = input.isDemoData,
            demoPreset = input.demoPreset,
            evaluatedAtEpochMs = input.evaluatedAtEpochMs
        )
    }

    fun appendIntradayMark(dailyCloses: List<Double>, liveMark: Double?): List<Double> {
        val clean = dailyCloses.filter { it.isFinite() && it > 0.0 }
        val mark = liveMark?.takeIf { it.isFinite() && it > 0.0 } ?: return clean
        if (clean.isEmpty()) return listOf(mark)
        val last = clean.last()
        return if (abs(last - mark) / last < 1e-6) clean else clean + mark
    }

    private fun evaluateSpyTrend(rawCloses: List<Double>): BearSignalEvaluation {
        val closes = rawCloses.filter { it.isFinite() && it > 0.0 }
        val latest = closes.lastOrNull()
        val sma50 = trailingSma(closes, 50)
        val sma200 = trailingSma(closes, 200)

        if (latest == null || sma50 == null) {
            return insufSignal(BearSignalId.PRIMARY_TREND_SPY, "Insufficient SPY daily bars (< 50).")
        }

        val lastThreeBelow50 = if (closes.size >= 52) {
            (0 until 3).all { offset ->
                val endIdx = closes.size - offset
                val sub = closes.subList(0, endIdx)
                val ma50 = trailingSma(sub, 50) ?: return@all false
                sub.last() < ma50
            }
        } else {
            closes.takeLast(3).all { it < sma50 }
        }

        val isRed = sma200 != null && latest < sma200
        val status = when {
            isRed -> SignalStatus.RED
            lastThreeBelow50 -> SignalStatus.AMBER
            else -> SignalStatus.GREEN
        }

        val refValue = sma200 ?: sma50
        val pctFrom200 = sma200?.let { ((latest - it) / it) * 100.0 }
        val pctFrom50 = ((latest - sma50) / sma50) * 100.0
        val deltaStr = if (pctFrom200 != null) {
            "${signedPct(pctFrom200)} vs 200D | ${signedPct(pctFrom50)} vs 50D"
        } else {
            "${signedPct(pctFrom50)} vs 50D"
        }

        val interpretation = when (status) {
            SignalStatus.RED -> "SPY broke below 200-DMA ($${fmt2(refValue)}); long-term downtrend active."
            SignalStatus.AMBER -> "3 consecutive closes below 50-DMA ($${fmt2(sma50)}); momentum weakening."
            SignalStatus.GREEN -> "Primary SPY uptrend intact above 50-DMA and 200-DMA."
        }

        return BearSignalEvaluation(
            id = BearSignalId.PRIMARY_TREND_SPY,
            status = status,
            currentValueFormatted = "$${fmt2(latest)}",
            referenceFormatted = "50D $${fmt2(sma50)}" + (sma200?.let { " / 200D $${fmt2(it)}" } ?: ""),
            deltaFromTriggerFormatted = deltaStr,
            interpretation = interpretation
        )
    }

    private fun evaluateIwmCanary(rawIwm: List<Double>, rawSpy: List<Double>): BearSignalEvaluation {
        val iwm = rawIwm.filter { it.isFinite() && it > 0.0 }
        val spy = rawSpy.filter { it.isFinite() && it > 0.0 }
        val latestIwm = iwm.lastOrNull()
        val iwmSma200 = trailingSma(iwm, 200)

        val pairLen = min(iwm.size, spy.size)
        if (latestIwm == null || pairLen < 20) {
            return insufSignal(BearSignalId.SMALL_CAP_CANARY_IWM, "Insufficient IWM/SPY history.")
        }

        val iwmTail = iwm.takeLast(pairLen)
        val spyTail = spy.takeLast(pairLen)
        val ratios = List(pairLen) { idx -> iwmTail[idx] / spyTail[idx] }
        val latestRatio = ratios.last()

        val prior60Low = if (ratios.size >= 61) {
            ratios.subList(ratios.size - 61, ratios.size - 1).minOrNull()
        } else {
            ratios.dropLast(1).minOrNull()
        }

        val below200 = iwmSma200 != null && latestIwm < iwmSma200
        val broke60Low = prior60Low != null && latestRatio <= prior60Low

        val status = when {
            below200 -> SignalStatus.RED
            broke60Low -> SignalStatus.AMBER
            else -> SignalStatus.GREEN
        }

        val deltaText = buildString {
            iwmSma200?.let { ma ->
                append("${signedPct(((latestIwm - ma) / ma) * 100.0)} vs 200D")
            }
            prior60Low?.let { low ->
                if (isNotEmpty()) append(" | ")
                append("${signedPct(((latestRatio - low) / low) * 100.0)} vs 60D RS low")
            }
        }.ifEmpty { "--" }

        val interpretation = when (status) {
            SignalStatus.RED -> "Small caps (IWM) below 200-DMA; cyclical risk appetite deteriorating."
            SignalStatus.AMBER -> "IWM/SPY relative ratio broke 60-trading-day low; narrow breadth warning."
            SignalStatus.GREEN -> "Small caps holding above 200-DMA and defending 60-day relative lows."
        }

        return BearSignalEvaluation(
            id = BearSignalId.SMALL_CAP_CANARY_IWM,
            status = status,
            currentValueFormatted = "$${fmt2(latestIwm)} (${fmt4(latestRatio)})",
            referenceFormatted = (iwmSma200?.let { "200D $${fmt2(it)}" } ?: "RS 60D") +
                (prior60Low?.let { " / Low ${fmt4(it)}" } ?: ""),
            deltaFromTriggerFormatted = deltaText,
            interpretation = interpretation
        )
    }

    private fun evaluateVixLevel(rawVix: List<Double>): BearSignalEvaluation {
        val vix = rawVix.filter { it.isFinite() && it > 0.0 }
        val latest = vix.lastOrNull()
            ?: return insufSignal(BearSignalId.VOLATILITY_LEVEL_VIX, "Missing \$VIX quote.")

        val sustainedAbove28 = vix.size >= 3 && vix.takeLast(3).all { it > 28.0 }
        val elevatedAbove22 = latest > 22.0

        val status = when {
            sustainedAbove28 -> SignalStatus.RED
            elevatedAbove22 -> SignalStatus.AMBER
            else -> SignalStatus.GREEN
        }

        val interpretation = when (status) {
            SignalStatus.RED -> "VIX sustained above 28.0 for 3+ sessions; crisis volatility regime."
            SignalStatus.AMBER -> if (latest > 28.0) {
                "VIX spiked above 28.0 (${vix.takeLast(3).count { it > 28.0 }}/3 sessions); monitoring persistence."
            } else {
                "VIX elevated above 22.0; tighten short deltas and hedge tail exposure."
            }
            SignalStatus.GREEN -> "VIX contained below 22.0; orderly implied volatility backdrop."
        }

        return BearSignalEvaluation(
            id = BearSignalId.VOLATILITY_LEVEL_VIX,
            status = status,
            currentValueFormatted = fmt2(latest),
            referenceFormatted = "Warn > 22.0 | Bear > 28.0 (3d)",
            deltaFromTriggerFormatted = "${signedNum(latest - 22.0)} vs 22 | ${signedNum(latest - 28.0)} vs 28",
            interpretation = interpretation
        )
    }

    private fun evaluateTermStructure(
        vixLatest: Double?,
        vix3mLatest: Double?
    ): BearSignalEvaluation {
        val vix = vixLatest?.takeIf { it.isFinite() && it > 0.0 }
        val vix3m = vix3mLatest?.takeIf { it.isFinite() && it > 0.0 }
        if (vix == null || vix3m == null) {
            return insufSignal(BearSignalId.VOL_TERM_STRUCTURE, "Missing \$VIX or \$VIX3M live mark.")
        }

        val ratio = vix / vix3m
        val status = when {
            ratio > 1.05 -> SignalStatus.RED
            ratio >= 0.98 -> SignalStatus.AMBER
            else -> SignalStatus.GREEN
        }

        val interpretation = when (status) {
            SignalStatus.RED -> "Term structure in Backwardation (${fmt3(ratio)} > 1.05); acute near-term crash pricing."
            SignalStatus.AMBER -> "Term structure Flat/Inverting (${fmt3(ratio)} >= 0.98); front-month hedges bid."
            SignalStatus.GREEN -> "Normal Contango (${fmt3(ratio)} < 0.98); favorable for systematic theta selling."
        }

        return BearSignalEvaluation(
            id = BearSignalId.VOL_TERM_STRUCTURE,
            status = status,
            currentValueFormatted = fmt3(ratio),
            referenceFormatted = "VIX ${fmt2(vix)} / VIX3M ${fmt2(vix3m)}",
            deltaFromTriggerFormatted = "${signedNum(ratio - 1.05)} vs 1.05 Bear",
            interpretation = interpretation
        )
    }

    private fun evaluateCreditStress(
        rawHyg: List<Double>,
        rawLqd: List<Double>
    ): BearSignalEvaluation {
        val hyg = rawHyg.filter { it.isFinite() && it > 0.0 }
        val lqd = rawLqd.filter { it.isFinite() && it > 0.0 }
        val pairLen = min(hyg.size, lqd.size)
        if (pairLen < 50) {
            return insufSignal(BearSignalId.CREDIT_STRESS_HYG_LQD, "Insufficient HYG/LQD history (< 50 bars).")
        }

        val hygTail = hyg.takeLast(pairLen)
        val lqdTail = lqd.takeLast(pairLen)
        val ratios = List(pairLen) { idx -> hygTail[idx] / lqdTail[idx] }
        val latest = ratios.last()
        val sma50 = trailingSma(ratios, 50)!!
        val sma200 = trailingSma(ratios, 200)

        val status = when {
            sma200 != null && latest < sma200 -> SignalStatus.RED
            latest < sma50 -> SignalStatus.AMBER
            else -> SignalStatus.GREEN
        }

        val deltaText = buildString {
            append("${signedPct(((latest - sma50) / sma50) * 100.0)} vs 50D")
            sma200?.let { ma ->
                append(" | ${signedPct(((latest - ma) / ma) * 100.0)} vs 200D")
            }
        }

        val interpretation = when (status) {
            SignalStatus.RED -> "High-yield credit (HYG/LQD) broke below 200-DMA; institutional spread widening."
            SignalStatus.AMBER -> "HYG/LQD slipped below 50-DMA; early credit appetite cooling."
            SignalStatus.GREEN -> "Credit spreads healthy with HYG/LQD holding above 50-DMA & 200-DMA."
        }

        return BearSignalEvaluation(
            id = BearSignalId.CREDIT_STRESS_HYG_LQD,
            status = status,
            currentValueFormatted = fmt4(latest),
            referenceFormatted = "50D ${fmt4(sma50)}" + (sma200?.let { " / 200D ${fmt4(it)}" } ?: ""),
            deltaFromTriggerFormatted = deltaText,
            interpretation = interpretation
        )
    }

    private fun evaluateMarketBreadth(universeCloses: Map<String, List<Double>>): BearSignalEvaluation {
        var evaluatedCount = 0
        var above50Count = 0

        universeCloses.values.forEach { raw ->
            val closes = raw.filter { it.isFinite() && it > 0.0 }
            val sma50 = trailingSma(closes, 50) ?: return@forEach
            evaluatedCount++
            if (closes.last() > sma50) {
                above50Count++
            }
        }

        if (evaluatedCount == 0) {
            return insufSignal(BearSignalId.MARKET_BREADTH_25, "Insufficient history across Core 25 universe.")
        }

        val breadthPct = (above50Count.toDouble() / evaluatedCount) * 100.0
        val status = when {
            breadthPct < 25.0 -> SignalStatus.RED
            breadthPct < 45.0 -> SignalStatus.AMBER
            else -> SignalStatus.GREEN
        }

        val interpretation = when (status) {
            SignalStatus.RED -> "Only $above50Count/$evaluatedCount leaders above 50-DMA (< 25%); broad distribution."
            SignalStatus.AMBER -> "Breadth narrowing at $above50Count/$evaluatedCount above 50-DMA (< 45%)."
            SignalStatus.GREEN -> "Healthy participation ($above50Count/$evaluatedCount above 50-DMA)."
        }

        return BearSignalEvaluation(
            id = BearSignalId.MARKET_BREADTH_25,
            status = status,
            currentValueFormatted = "${fmt1(breadthPct)}% ($above50Count/$evaluatedCount)",
            referenceFormatted = "Warn < 45% | Bear < 25%",
            deltaFromTriggerFormatted = "${signedNum(breadthPct - 45.0)}% vs 45% | ${signedNum(breadthPct - 25.0)}% vs 25%",
            interpretation = interpretation
        )
    }

    private fun directiveFor(regime: MarketRegime, redCount: Int): PortfolioDirective = when (regime) {
        MarketRegime.RISK_ON_BULL -> PortfolioDirective(
            headline = "RISK-ON BULL ($redCount/6 Red) — Press Directional Income",
            cashReserveTarget = regime.cashReserveTarget,
            favoredStrategies = listOf("PMCC", "Bull Put Spreads", "Wheel / CSP"),
            haltOrAvoidStrategies = listOf("Avoid aggressive PMCP / naked bear legs"),
            shortDeltaGuidance = regime.shortDeltaGuidance,
            actionBullets = listOf(
                "Target 12%–15% settled cash reserve; deploy remaining BP systematically.",
                "Favor 0.80–0.88Δ PMCCs with Net Debit < Strike Width and Bull Put Spreads on leaders.",
                "Keep short calls around 0.25–0.32Δ and manage winners at 50% max profit."
            )
        )
        MarketRegime.CAUTION_ELEVATED_VOL -> PortfolioDirective(
            headline = "CAUTION — ELEVATED VOL ($redCount/6 Red) — Tighten Deltas & Harvest Neutral Theta",
            cashReserveTarget = regime.cashReserveTarget,
            favoredStrategies = listOf("Calendar Spreads", "Iron Condors", "Tight Verticals"),
            haltOrAvoidStrategies = listOf("Avoid oversized bullish LEAPS leverage"),
            shortDeltaGuidance = regime.shortDeltaGuidance,
            actionBullets = listOf(
                "Raise settled cash reserve to 20%–25% for dislocation deployment.",
                "Tighten short strikes to 0.20–0.25Δ and prefer defined-risk spreads.",
                "Prioritize Calendars exploiting front-month IV backwardation and high-POP Iron Condors."
            )
        )
        MarketRegime.CONFIRMED_BEAR -> PortfolioDirective(
            headline = "CONFIRMED BEAR ($redCount/6 Red) — Defensive Preservation & Tactical Hedges",
            cashReserveTarget = regime.cashReserveTarget,
            favoredStrategies = listOf("Tactical PMCP", "Bear Call Spreads", "Calendars"),
            haltOrAvoidStrategies = listOf("HALT new bullish PMCCs", "Halt aggressive CSPs below breakdown levels"),
            shortDeltaGuidance = regime.shortDeltaGuidance,
            actionBullets = listOf(
                "Enforce >= 30% cash reserve immediately; halt new bullish PMCC entries.",
                "Authorize tactical PMCP hedges on relief rallies (Debit < 85% width) & Bear Call Spreads.",
                "Audit portfolio beta-weighted delta to ensure downside crash stress remains bounded."
            )
        )
    }

    fun buildRegimeGeminiMarkdown(evaluation: BearChecklistEvaluation): String = buildString {
        appendLine("# Schwab Quant — Bear-Market 6-Signal Regime Brief")
        appendLine("- **Active Regime:** ${evaluation.regime.label}")
        appendLine("- **Strict Red Triggers:** ${evaluation.redCount} / ${evaluation.totalSignals}")
        appendLine("- **Weighted Severity Score:** ${fmt1(evaluation.weightedScore)} / 6.0 (AMBER = 0.5, RED = 1.0)")
        appendLine("- **Source:** ${if (evaluation.isDemoData) "DEMO DATA (${evaluation.demoPreset?.label ?: "Offline"})" else "Live Schwab Market Data"}")
        appendLine()
        appendLine("## Portfolio Directives")
        appendLine("- **Cash Reserve Target:** ${evaluation.directive.cashReserveTarget}")
        appendLine("- **Short Delta Target:** ${evaluation.directive.shortDeltaGuidance}")
        appendLine("- **Favored Strategies:** ${evaluation.directive.favoredStrategies.joinToString(", ")}")
        appendLine("- **Halt / Avoid:** ${evaluation.directive.haltOrAvoidStrategies.joinToString(", ")}")
        appendLine()
        appendLine("## 6-Signal Diagnostic Matrix")
        appendLine("| # | Signal | Ticker | Status | Observed | Reference | Trigger Delta |")
        appendLine("|---:|---|---|---|---:|---:|---|")
        evaluation.signals.forEach { sig ->
            appendLine(
                "| ${sig.id.number} | ${sig.id.title} | `${sig.id.tickerTag}` | **${sig.status.label}** | " +
                    "${sig.currentValueFormatted} | ${sig.referenceFormatted} | ${sig.deltaFromTriggerFormatted} |"
            )
        }
        appendLine()
        appendLine("Provide tactical hedging adjustments, sector rotation notes, and risk-budget allocations for this regime.")
    }

    private fun extractSeriesWithIntradayMark(
        dto: PriceHistoryResponseDto?,
        quote: QuoteEntryDto?
    ): List<Double> {
        val dailyCloses = dto?.candles
            .orEmpty()
            .sortedBy { it.datetime ?: 0L }
            .mapNotNull { it.close?.takeIf { c -> c.isFinite() && c > 0.0 } }
        return appendIntradayMark(dailyCloses, extractLiveMark(quote))
    }

    private fun extractLiveMark(quote: QuoteEntryDto?): Double? {
        val q = quote?.quote ?: return null
        return listOfNotNull(q.mark, q.lastPrice, q.closePrice)
            .firstOrNull { it.isFinite() && it > 0.0 }
    }

    private fun trailingSma(series: List<Double>, period: Int): Double? {
        if (series.size < period || period <= 0) return null
        return series.takeLast(period).average()
    }

    private fun insufSignal(id: BearSignalId, note: String) = BearSignalEvaluation(
        id = id,
        status = SignalStatus.GREEN,
        currentValueFormatted = "--",
        referenceFormatted = "--",
        deltaFromTriggerFormatted = "Pending data",
        interpretation = note
    )

    private fun buildDemoInput(preset: DemoRegimePreset): RegimeEvaluationInput {
        val now = nowProvider()
        return when (preset) {
            DemoRegimePreset.BULL -> {
                val spy = buildRampSeries(220, start = 500.0, end = 588.0)
                val iwm = buildRampSeries(220, start = 190.0, end = 232.0)
                val vix = buildConstantWithTail(220, base = 16.5, tail = listOf(15.8, 15.6, 15.4))
                val hyg = buildRampSeries(220, start = 76.0, end = 80.5)
                val lqd = buildRampSeries(220, start = 108.0, end = 109.0)
                val universe = buildUniverseBreadthSeries(aboveCount = 21, total = LiquidUniverse.CORE_25_LIQUID_TICKERS)
                RegimeEvaluationInput(
                    spyCloses = spy,
                    iwmCloses = iwm,
                    vixCloses = vix,
                    vix3mLatest = 18.5, // VIX/VIX3M = 0.832 -> GREEN
                    hygCloses = hyg,
                    lqdCloses = lqd,
                    universeCloses = universe,
                    isDemoData = true,
                    demoPreset = preset,
                    evaluatedAtEpochMs = now
                )
            }
            DemoRegimePreset.CAUTION -> {
                // Exact 2 Red (Term Structure > 1.05, Breadth < 25%) + 4 Amber signals -> CAUTION_ELEVATED_VOL
                val spy = buildRampSeries(217, start = 520.0, end = 586.0) + listOf(578.0, 576.0, 574.0)
                val iwm = buildRampSeries(155, start = 195.0, end = 224.0) + List(64) { 224.0 } + listOf(216.0)
                val vix = buildConstantWithTail(220, base = 19.0, tail = listOf(23.1, 24.4, 24.8))
                val hyg = buildRampSeries(217, start = 74.0, end = 80.5) + listOf(78.8, 78.6, 78.5)
                val lqd = List(220) { 108.0 }
                val universe = buildUniverseBreadthSeries(aboveCount = 5, total = LiquidUniverse.CORE_25_LIQUID_TICKERS)
                RegimeEvaluationInput(
                    spyCloses = spy,
                    iwmCloses = iwm,
                    vixCloses = vix,
                    vix3mLatest = 23.2, // VIX/VIX3M = 1.069 -> RED
                    hygCloses = hyg,
                    lqdCloses = lqd,
                    universeCloses = universe,
                    isDemoData = true,
                    demoPreset = preset,
                    evaluatedAtEpochMs = now
                )
            }
            DemoRegimePreset.BEAR -> {
                // 5+ Red signals for confirmed bear testing
                val spy = buildSteadyThenDrop(220, base = 585.0, lastThree = listOf(535.0, 531.0, 528.0))
                val iwm = buildSteadyThenDrop(220, base = 222.0, lastThree = listOf(196.0, 194.0, 191.5))
                val vix = buildConstantWithTail(220, base = 20.0, tail = listOf(29.8, 31.4, 33.2))
                val hyg = buildSteadyThenDrop(220, base = 80.0, lastThree = listOf(73.5, 73.0, 72.4))
                val lqd = buildRampSeries(220, start = 106.0, end = 109.0)
                val universe = buildUniverseBreadthSeries(aboveCount = 4, total = LiquidUniverse.CORE_25_LIQUID_TICKERS)
                RegimeEvaluationInput(
                    spyCloses = spy,
                    iwmCloses = iwm,
                    vixCloses = vix,
                    vix3mLatest = 28.4, // ratio = 1.169 RED
                    hygCloses = hyg,
                    lqdCloses = lqd,
                    universeCloses = universe,
                    isDemoData = true,
                    demoPreset = preset,
                    evaluatedAtEpochMs = now
                )
            }
        }
    }

    private fun buildRampSeries(count: Int, start: Double, end: Double): List<Double> =
        List(count) { i ->
            val t = i.toDouble() / (count - 1).coerceAtLeast(1)
            start + (end - start) * t
        }

    private fun buildSteadyThenDrop(count: Int, base: Double, lastThree: List<Double>): List<Double> {
        val prefixCount = (count - lastThree.size).coerceAtLeast(0)
        return List(prefixCount) { base } + lastThree
    }

    private fun buildConstantWithTail(count: Int, base: Double, tail: List<Double>): List<Double> {
        val prefixCount = (count - tail.size).coerceAtLeast(0)
        return List(prefixCount) { base } + tail
    }

    private fun buildUniverseBreadthSeries(
        aboveCount: Int,
        total: List<String>
    ): Map<String, List<Double>> = total.mapIndexed { idx, sym ->
        val series = if (idx < aboveCount) {
            buildRampSeries(60, 100.0, 112.0)
        } else {
            buildSteadyThenDrop(60, 110.0, listOf(102.0, 101.0, 100.0))
        }
        sym to series
    }.toMap()

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmt3(v: Double): String = String.format(Locale.US, "%.3f", v)
    private fun fmt4(v: Double): String = String.format(Locale.US, "%.4f", v)

    private fun signedPct(v: Double): String {
        val sign = if (v >= 0.0) "+" else ""
        return "$sign${fmt1(v)}%"
    }

    private fun signedNum(v: Double): String {
        val sign = if (v >= 0.0) "+" else ""
        return "$sign${fmt1(v)}"
    }

    companion object {
        private const val MAX_LIVE_HISTORY_CONCURRENCY = 4
        private const val SYMBOL_SPY = "SPY"
        private const val SYMBOL_IWM = "IWM"
        private const val SYMBOL_VIX = "\$VIX"
        private const val SYMBOL_VIX3M = "\$VIX3M"
        private const val SYMBOL_HYG = "HYG"
        private const val SYMBOL_LQD = "LQD"
    }
}
