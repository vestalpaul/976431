package com.schwabquant.app.domain.usecase

import com.schwabquant.app.core.quant.OptionChainParser
import com.schwabquant.app.core.quant.ParsedOptionChain
import com.schwabquant.app.core.quant.ParsedOptionContract
import com.schwabquant.app.core.quant.StrategyExplainerGenerator
import com.schwabquant.app.core.quant.VolRankCalculator
import com.schwabquant.app.core.quant.VolRankResult
import com.schwabquant.app.data.repository.DemoOptionChainProvider
import com.schwabquant.app.domain.model.BearChecklistEvaluation
import com.schwabquant.app.domain.model.EarningsCheckStatus
import com.schwabquant.app.domain.model.LegAction
import com.schwabquant.app.domain.model.LiquidUniverse
import com.schwabquant.app.domain.model.MarketRegime
import com.schwabquant.app.domain.model.OptionStrategyType
import com.schwabquant.app.domain.model.QuantCardMetrics
import com.schwabquant.app.domain.model.ScanProgress
import com.schwabquant.app.domain.model.StrategyCandidate
import com.schwabquant.app.domain.model.StrategyFilterChip
import com.schwabquant.app.domain.model.StrategyLeg
import com.schwabquant.app.domain.repository.MarketDataRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-JVM, deterministic, regime-aware 6-Strategy Options Scanner with bounded concurrency
 * (`Semaphore(3)`), strict liquidity & capital-tier guards, and deterministic two-layer explainers.
 */
@Singleton
class ScanOptionsStrategiesUseCase(
    private val marketDataRepository: MarketDataRepository? = null,
    private val optionChainParser: OptionChainParser = OptionChainParser(),
    private val volRankCalculator: VolRankCalculator = VolRankCalculator(),
    private val explainerGenerator: StrategyExplainerGenerator = StrategyExplainerGenerator(),
    private val demoOptionChainProvider: DemoOptionChainProvider = DemoOptionChainProvider(),
    private val nowProvider: () -> Long = System::currentTimeMillis
) {

    @Inject
    constructor(
        marketDataRepository: MarketDataRepository,
        optionChainParser: OptionChainParser,
        volRankCalculator: VolRankCalculator,
        explainerGenerator: StrategyExplainerGenerator,
        demoOptionChainProvider: DemoOptionChainProvider
    ) : this(
        marketDataRepository = marketDataRepository,
        optionChainParser = optionChainParser,
        volRankCalculator = volRankCalculator,
        explainerGenerator = explainerGenerator,
        demoOptionChainProvider = demoOptionChainProvider,
        nowProvider = System::currentTimeMillis
    )

    /**
     * Cancellable, concurrency-bounded watchlist scan across all active tickers.
     */
    suspend fun scanWatchlist(
        tickers: List<String>,
        regimeEvaluation: BearChecklistEvaluation?,
        maxCapitalDollars: Double?,
        selectedFilters: Set<StrategyFilterChip> = setOf(StrategyFilterChip.ALL),
        useDemoData: Boolean,
        forceRefresh: Boolean = false,
        onProgress: (ScanProgress) -> Unit = {}
    ): List<StrategyCandidate> {
        val cleanTickers = tickers.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (cleanTickers.isEmpty()) return emptyList()

        val completedCounter = AtomicInteger(0)
        val total = cleanTickers.size

        if (useDemoData || marketDataRepository == null) {
            val allCandidates = mutableListOf<StrategyCandidate>()
            for (ticker in cleanTickers) {
                currentCoroutineContext().ensureActive()
                val done = completedCounter.incrementAndGet()
                onProgress(ScanProgress(completed = done, total = total, currentTicker = ticker))
                val demo = demoOptionChainProvider.buildDemoData(ticker, nowEpochMs = nowProvider())
                allCandidates += scanChain(
                    chain = demo.chain,
                    volRank = demo.volRank,
                    sma50 = demo.sma50,
                    regimeEvaluation = regimeEvaluation,
                    maxCapitalDollars = maxCapitalDollars,
                    selectedFilters = selectedFilters,
                    isDemoData = true
                )
            }
            return allCandidates.sortedWith(CANDIDATE_COMPARATOR)
        }

        // Bounded live concurrency via Semaphore(3) flowing cleanly through TokenBucketRateLimiter
        val semaphore = Semaphore(MAX_LIVE_SCAN_CONCURRENCY)
        val allCandidates = coroutineScope {
            cleanTickers.map { ticker ->
                async {
                    semaphore.withPermit {
                        currentCoroutineContext().ensureActive()
                        try {
                            val chainDto = marketDataRepository.getOptionChain(
                                symbol = ticker,
                                forceRefresh = forceRefresh
                            ).getOrNull() ?: return@withPermit emptyList()

                            val parsedChain = optionChainParser.parse(chainDto, fetchedAtEpochMs = nowProvider())
                            if (parsedChain.underlyingPrice <= 0.0 || parsedChain.contracts.isEmpty()) {
                                return@withPermit emptyList()
                            }

                            val historyDto = marketDataRepository.getPriceHistory(
                                symbol = ticker,
                                forceRefresh = false
                            ).getOrNull()

                            val closes = historyDto?.candles
                                .orEmpty()
                                .sortedBy { it.datetime ?: 0L }
                                .mapNotNull { it.close?.takeIf { c -> c.isFinite() && c > 0.0 } }

                            val volRank = if (closes.isNotEmpty()) {
                                volRankCalculator.computeVolRank(closes, parsedChain.atm30DayIv)
                            } else {
                                null
                            }
                            val sma50 = if (closes.size >= 50) closes.takeLast(50).average() else null

                            scanChain(
                                chain = parsedChain,
                                volRank = volRank,
                                sma50 = sma50,
                                regimeEvaluation = regimeEvaluation,
                                maxCapitalDollars = maxCapitalDollars,
                                selectedFilters = selectedFilters,
                                isDemoData = false
                            )
                        } finally {
                            val done = completedCounter.incrementAndGet()
                            onProgress(ScanProgress(completed = done, total = total, currentTicker = ticker))
                        }
                    }
                }
            }.awaitAll().flatten()
        }

        return allCandidates.sortedWith(CANDIDATE_COMPARATOR)
    }

    /**
     * Pure-JVM strategy constructor & regime-aware scorer operating on a single parsed chain.
     */
    fun scanChain(
        chain: ParsedOptionChain,
        volRank: VolRankResult?,
        sma50: Double?,
        regimeEvaluation: BearChecklistEvaluation?,
        maxCapitalDollars: Double?,
        selectedFilters: Set<StrategyFilterChip> = setOf(StrategyFilterChip.ALL),
        isDemoData: Boolean = false
    ): List<StrategyCandidate> {
        if (chain.underlyingPrice <= 0.0 || chain.contracts.isEmpty()) return emptyList()

        val activeTypes = resolveEnabledStrategies(selectedFilters)
        val results = mutableListOf<StrategyCandidate>()

        if (OptionStrategyType.PMCC in activeTypes) {
            buildBestPmcc(chain, volRank, regimeEvaluation, isDemoData)?.let(results::add)
        }
        if (OptionStrategyType.PMCP in activeTypes) {
            buildBestPmcp(chain, volRank, regimeEvaluation, isDemoData)?.let(results::add)
        }
        if (OptionStrategyType.VERTICAL_BULL_PUT in activeTypes) {
            buildBestBullPut(chain, volRank, regimeEvaluation, isDemoData)?.let(results::add)
        }
        if (OptionStrategyType.VERTICAL_BEAR_CALL in activeTypes) {
            buildBestBearCall(chain, volRank, regimeEvaluation, isDemoData)?.let(results::add)
        }
        if (OptionStrategyType.CALENDAR in activeTypes) {
            buildBestCalendar(chain, volRank, regimeEvaluation, isDemoData)?.let(results::add)
        }
        if (OptionStrategyType.IRON_CONDOR in activeTypes) {
            buildBestIronCondor(chain, volRank, regimeEvaluation, isDemoData)?.let(results::add)
        }
        if (OptionStrategyType.WHEEL_CSP in activeTypes) {
            buildBestWheelCsp(chain, volRank, sma50, regimeEvaluation, isDemoData)?.let(results::add)
        }

        return results
            .filter { candidate ->
                maxCapitalDollars == null || candidate.metrics.buyingPowerRequirement <= maxCapitalDollars
            }
            .sortedWith(CANDIDATE_COMPARATOR)
    }

    private fun buildBestPmcc(
        chain: ParsedOptionChain,
        volRank: VolRankResult?,
        regimeEvaluation: BearChecklistEvaluation?,
        isDemoData: Boolean
    ): StrategyCandidate? {
        val longs = chain.calls
            .filter { it.dte in 200..450 && it.delta in 0.78..0.90 && isTradeable(it) }
            .sortedBy { abs(it.delta - 0.84) }
            .take(5)
        val shorts = chain.calls
            .filter { it.dte in 25..45 && it.delta in 0.22..0.35 && isTradeable(it) }
            .sortedBy { abs(it.delta - 0.28) }
            .take(5)

        if (longs.isEmpty() || shorts.isEmpty()) return null

        var best: StrategyCandidate? = null
        for (longLeg in longs) {
            for (shortLeg in shorts) {
                if (shortLeg.strike <= longLeg.strike + 0.5) continue
                val netDebit = longLeg.mid - shortLeg.mid
                if (netDebit <= 0.05) continue

                val strikeWidth = shortLeg.strike - longLeg.strike
                val debitToWidthPct = (netDebit / strikeWidth) * 100.0
                val bpr = netDebit * 100.0
                val termSpread = shortLeg.iv - longLeg.iv

                val warnings = mutableListOf<String>()
                var score = 58.0

                // Strongly prioritize Net Debit < Strike Width (zero upside expiration loss)
                if (debitToWidthPct < 100.0) {
                    val cushionBonus = when {
                        debitToWidthPct <= 88.0 -> 20.0
                        debitToWidthPct <= 95.0 -> 15.0
                        else -> 10.0
                    }
                    score += cushionBonus
                } else {
                    score -= 26.0
                    warnings += "Net Debit is ${fmt1(debitToWidthPct)}% of strike width (>= 100% locks upside expiration loss if underlying rallies past short strike)."
                }

                // IV Term-Structure Backwardation bonus
                if (termSpread > 0.0) {
                    score += (8.0 + termSpread * 100.0 * 1.5).coerceAtMost(14.0)
                }

                // Non-earnings ETF stability bonus
                val (earningsStatus, earningsDetail) = classifyEarnings(chain.symbol)
                if (earningsStatus == EarningsCheckStatus.NO_EARNINGS_ETF) {
                    score += 5.0
                } else {
                    warnings += "Equity underlying — verify earnings date before entering front short leg."
                }

                val regime = regimeEvaluation?.regime
                val regimeFitLabel = when (regime) {
                    MarketRegime.RISK_ON_BULL -> {
                        score += 12.0
                        "Optimal (Bull)"
                    }
                    MarketRegime.CAUTION_ELEVATED_VOL -> {
                        score -= 4.0
                        "Neutral (Caution)"
                    }
                    MarketRegime.CONFIRMED_BEAR -> {
                        score -= 28.0
                        warnings += "Confirmed Bear Regime active — PMCC entries halted by Bear-Market Directive."
                        "Halted (Bear)"
                    }
                    null -> "Unassessed"
                }

                score += liquidityScoreAndWarnings(listOf(longLeg, shortLeg), warnings)

                val strategyLegs = listOf(
                    toStrategyLeg(longLeg, LegAction.BUY_TO_OPEN),
                    toStrategyLeg(shortLeg, LegAction.SELL_TO_OPEN)
                )
                val netDelta = longLeg.delta - shortLeg.delta
                // Position daily dollar theta: +1 * long - 1 * short (long thetaPerDay is negative)
                val positionThetaDollars = 100.0 * (longLeg.thetaPerDay - shortLeg.thetaPerDay)
                val positionGamma = longLeg.gamma - shortLeg.gamma
                val positionVegaDollars = 100.0 * (longLeg.vegaPer1Pct - shortLeg.vegaPer1Pct)
                val popPct = ((1.0 - shortLeg.delta) * 100.0 * 0.90).coerceIn(25.0, 90.0)
                val maxUpside = ((strikeWidth - netDebit) * 100.0).takeIf { it > 0.0 }

                val metrics = QuantCardMetrics(
                    netDebitOrCreditPerShare = netDebit,
                    isNetCredit = false,
                    buyingPowerRequirement = bpr,
                    maxProfit = maxUpside,
                    maxLoss = bpr,
                    breakevens = listOf(longLeg.strike + netDebit),
                    widthRatioPct = debitToWidthPct,
                    widthRatioLabel = "Debit / Width",
                    popPct = popPct,
                    netDelta = netDelta,
                    netThetaPerDay = positionThetaDollars,
                    netGamma = positionGamma,
                    netVegaPer1Pct = positionVegaDollars,
                    frontIv = shortLeg.iv,
                    backIv = longLeg.iv,
                    ivTermSpread = termSpread,
                    volRank52w = volRank?.volRank,
                    annualizedRocPct = null,
                    distanceBelowSpotPct = null,
                    minOpenInterest = min(longLeg.openInterest, shortLeg.openInterest),
                    maxBidAskSpreadPct = max(longLeg.spreadWidthPct, shortLeg.spreadWidthPct),
                    earningsStatus = earningsStatus,
                    earningsDetail = earningsDetail
                )

                val edgeTag = if (termSpread > 0.005) {
                    "+${fmt1(termSpread * 100.0)}% IV Backw."
                } else {
                    "${fmt0(debitToWidthPct)}% Debit/W"
                }

                val candidate = buildCandidate(
                    chain = chain,
                    strategyType = OptionStrategyType.PMCC,
                    rawScore = score,
                    regimeFitLabel = regimeFitLabel,
                    edgeTag = edgeTag,
                    legs = strategyLegs,
                    metrics = metrics,
                    regimeEvaluation = regimeEvaluation,
                    warnings = warnings,
                    isDemoData = isDemoData
                )
                if (best == null || candidate.compositeScore > best.compositeScore) {
                    best = candidate
                }
            }
        }
        return best
    }

    private fun buildBestPmcp(
        chain: ParsedOptionChain,
        volRank: VolRankResult?,
        regimeEvaluation: BearChecklistEvaluation?,
        isDemoData: Boolean
    ): StrategyCandidate? {
        val longs = chain.puts
            .filter { it.dte in 180..270 && it.delta in -0.88..-0.75 && isTradeable(it) }
            .sortedBy { abs(it.delta - (-0.82)) }
            .take(5)
        val shorts = chain.puts
            .filter { it.dte in 25..45 && it.delta in -0.35..-0.22 && isTradeable(it) }
            .sortedBy { abs(it.delta - (-0.28)) }
            .take(5)

        if (longs.isEmpty() || shorts.isEmpty()) return null

        var best: StrategyCandidate? = null
        for (longLeg in longs) {
            for (shortLeg in shorts) {
                if (shortLeg.strike >= longLeg.strike - 0.5) continue
                val netDebit = longLeg.mid - shortLeg.mid
                if (netDebit <= 0.05) continue

                val strikeWidth = longLeg.strike - shortLeg.strike
                val debitToWidthPct = (netDebit / strikeWidth) * 100.0
                val bpr = netDebit * 100.0
                val termSpread = shortLeg.iv - longLeg.iv

                val warnings = mutableListOf<String>()
                var score = 55.0

                // Target Net Debit < 85% of Strike Width
                score += when {
                    debitToWidthPct < 85.0 -> 20.0
                    debitToWidthPct < 95.0 -> 10.0
                    else -> {
                        warnings += "PMCP Debit/Width is ${fmt1(debitToWidthPct)}% (above 85% target)."
                        -12.0
                    }
                }

                if (termSpread > 0.0) {
                    score += 6.0
                }

                val (earningsStatus, earningsDetail) = classifyEarnings(chain.symbol)
                val regime = regimeEvaluation?.regime
                val regimeFitLabel = when (regime) {
                    MarketRegime.CONFIRMED_BEAR -> {
                        score += 24.0
                        "Optimal (Bear)"
                    }
                    MarketRegime.CAUTION_ELEVATED_VOL -> {
                        score += 8.0
                        "Aligned (Caution)"
                    }
                    MarketRegime.RISK_ON_BULL -> {
                        score -= 20.0
                        warnings += "Counter-Trend in Bull Regime — size conservatively as tail hedge only."
                        "Counter-Trend"
                    }
                    null -> "Unassessed"
                }

                score += liquidityScoreAndWarnings(listOf(longLeg, shortLeg), warnings)

                val strategyLegs = listOf(
                    toStrategyLeg(longLeg, LegAction.BUY_TO_OPEN),
                    toStrategyLeg(shortLeg, LegAction.SELL_TO_OPEN)
                )
                val netDelta = longLeg.delta - shortLeg.delta
                val positionThetaDollars = 100.0 * (longLeg.thetaPerDay - shortLeg.thetaPerDay)
                val positionGamma = longLeg.gamma - shortLeg.gamma
                val positionVegaDollars = 100.0 * (longLeg.vegaPer1Pct - shortLeg.vegaPer1Pct)
                val popPct = ((1.0 - abs(shortLeg.delta)) * 100.0 * 0.85).coerceIn(20.0, 88.0)

                val metrics = QuantCardMetrics(
                    netDebitOrCreditPerShare = netDebit,
                    isNetCredit = false,
                    buyingPowerRequirement = bpr,
                    maxProfit = ((strikeWidth - netDebit) * 100.0).takeIf { it > 0.0 },
                    maxLoss = bpr,
                    breakevens = listOf(longLeg.strike - netDebit),
                    widthRatioPct = debitToWidthPct,
                    widthRatioLabel = "Debit / Width",
                    popPct = popPct,
                    netDelta = netDelta,
                    netThetaPerDay = positionThetaDollars,
                    netGamma = positionGamma,
                    netVegaPer1Pct = positionVegaDollars,
                    frontIv = shortLeg.iv,
                    backIv = longLeg.iv,
                    ivTermSpread = termSpread,
                    volRank52w = volRank?.volRank,
                    annualizedRocPct = null,
                    distanceBelowSpotPct = null,
                    minOpenInterest = min(longLeg.openInterest, shortLeg.openInterest),
                    maxBidAskSpreadPct = max(longLeg.spreadWidthPct, shortLeg.spreadWidthPct),
                    earningsStatus = earningsStatus,
                    earningsDetail = earningsDetail
                )

                val candidate = buildCandidate(
                    chain = chain,
                    strategyType = OptionStrategyType.PMCP,
                    rawScore = score,
                    regimeFitLabel = regimeFitLabel,
                    edgeTag = "${fmt0(debitToWidthPct)}% Debit/W",
                    legs = strategyLegs,
                    metrics = metrics,
                    regimeEvaluation = regimeEvaluation,
                    warnings = warnings,
                    isDemoData = isDemoData
                )
                if (best == null || candidate.compositeScore > best.compositeScore) {
                    best = candidate
                }
            }
        }
        return best
    }

    private fun buildBestBullPut(
        chain: ParsedOptionChain,
        volRank: VolRankResult?,
        regimeEvaluation: BearChecklistEvaluation?,
        isDemoData: Boolean
    ): StrategyCandidate? {
        val targetDelta = if (regimeEvaluation?.regime == MarketRegime.RISK_ON_BULL) -0.26 else -0.22
        val targetWidth = if (chain.underlyingPrice < 300.0) 5.0 else 10.0

        val putsByExp = chain.puts
            .filter { it.dte in 25..45 && isTradeable(it) }
            .groupBy { it.expirationDate }

        var best: StrategyCandidate? = null
        for ((_, expPuts) in putsByExp) {
            val shortLeg = expPuts
                .filter { it.delta in -0.32..-0.18 }
                .minByOrNull { abs(it.delta - targetDelta) } ?: continue

            val desiredLongStrike = shortLeg.strike - targetWidth
            val longLeg = expPuts
                .filter { it.strike < shortLeg.strike && (shortLeg.strike - it.strike) in 2.0..15.0 }
                .minByOrNull { abs(it.strike - desiredLongStrike) } ?: continue

            val width = shortLeg.strike - longLeg.strike
            val netCredit = shortLeg.mid - longLeg.mid
            if (netCredit <= 0.05 || netCredit >= width) continue

            val creditPct = (netCredit / width) * 100.0
            val maxLoss = (width - netCredit) * 100.0
            val maxProfit = netCredit * 100.0

            val warnings = mutableListOf<String>()
            var score = 54.0

            score += when {
                creditPct >= 30.0 -> 18.0
                creditPct >= 25.0 -> 14.0
                creditPct >= 18.0 -> 6.0
                else -> {
                    warnings += "Credit/Width (${fmt1(creditPct)}%) is below 25%–33% target."
                    -8.0
                }
            }

            if ((volRank?.volRank ?: 0.0) >= 50.0) {
                score += 8.0
            }

            val regimeFitLabel = when (regimeEvaluation?.regime) {
                MarketRegime.RISK_ON_BULL -> {
                    score += 12.0
                    "Optimal (Bull)"
                }
                MarketRegime.CAUTION_ELEVATED_VOL -> {
                    score -= 2.0
                    "Caution"
                }
                MarketRegime.CONFIRMED_BEAR -> {
                    score -= 20.0
                    warnings += "Bear Regime active — Bull Put Credit Spread carries elevated downside assignment risk."
                    "Counter-Trend"
                }
                null -> "Unassessed"
            }

            score += liquidityScoreAndWarnings(listOf(shortLeg, longLeg), warnings)
            val (earningsStatus, earningsDetail) = classifyEarnings(chain.symbol)

            val metrics = QuantCardMetrics(
                netDebitOrCreditPerShare = netCredit,
                isNetCredit = true,
                buyingPowerRequirement = maxLoss,
                maxProfit = maxProfit,
                maxLoss = maxLoss,
                breakevens = listOf(shortLeg.strike - netCredit),
                widthRatioPct = creditPct,
                widthRatioLabel = "Credit / Width",
                popPct = ((1.0 - abs(shortLeg.delta)) * 100.0).coerceIn(10.0, 95.0),
                netDelta = -shortLeg.delta + longLeg.delta,
                netThetaPerDay = 100.0 * (longLeg.thetaPerDay - shortLeg.thetaPerDay),
                netGamma = longLeg.gamma - shortLeg.gamma,
                netVegaPer1Pct = 100.0 * (longLeg.vegaPer1Pct - shortLeg.vegaPer1Pct),
                frontIv = shortLeg.iv,
                backIv = null,
                ivTermSpread = null,
                volRank52w = volRank?.volRank,
                annualizedRocPct = (maxProfit / maxLoss) * (365.0 / shortLeg.dte) * 100.0,
                distanceBelowSpotPct = ((chain.underlyingPrice - shortLeg.strike) / chain.underlyingPrice) * 100.0,
                minOpenInterest = min(shortLeg.openInterest, longLeg.openInterest),
                maxBidAskSpreadPct = max(shortLeg.spreadWidthPct, longLeg.spreadWidthPct),
                earningsStatus = earningsStatus,
                earningsDetail = earningsDetail
            )

            val candidate = buildCandidate(
                chain = chain,
                strategyType = OptionStrategyType.VERTICAL_BULL_PUT,
                rawScore = score,
                regimeFitLabel = regimeFitLabel,
                edgeTag = "${fmt0(creditPct)}% Credit/W",
                legs = listOf(
                    toStrategyLeg(shortLeg, LegAction.SELL_TO_OPEN),
                    toStrategyLeg(longLeg, LegAction.BUY_TO_OPEN)
                ),
                metrics = metrics,
                regimeEvaluation = regimeEvaluation,
                warnings = warnings,
                isDemoData = isDemoData
            )
            if (best == null || candidate.compositeScore > best.compositeScore) {
                best = candidate
            }
        }
        return best
    }

    private fun buildBestBearCall(
        chain: ParsedOptionChain,
        volRank: VolRankResult?,
        regimeEvaluation: BearChecklistEvaluation?,
        isDemoData: Boolean
    ): StrategyCandidate? {
        val targetDelta = if (regimeEvaluation?.regime == MarketRegime.RISK_ON_BULL) 0.25 else 0.22
        val targetWidth = if (chain.underlyingPrice < 300.0) 5.0 else 10.0

        val callsByExp = chain.calls
            .filter { it.dte in 25..45 && isTradeable(it) }
            .groupBy { it.expirationDate }

        var best: StrategyCandidate? = null
        for ((_, expCalls) in callsByExp) {
            val shortLeg = expCalls
                .filter { it.delta in 0.18..0.32 }
                .minByOrNull { abs(it.delta - targetDelta) } ?: continue

            val desiredLongStrike = shortLeg.strike + targetWidth
            val longLeg = expCalls
                .filter { it.strike > shortLeg.strike && (it.strike - shortLeg.strike) in 2.0..15.0 }
                .minByOrNull { abs(it.strike - desiredLongStrike) } ?: continue

            val width = longLeg.strike - shortLeg.strike
            val netCredit = shortLeg.mid - longLeg.mid
            if (netCredit <= 0.05 || netCredit >= width) continue

            val creditPct = (netCredit / width) * 100.0
            val maxLoss = (width - netCredit) * 100.0
            val maxProfit = netCredit * 100.0

            val warnings = mutableListOf<String>()
            var score = 54.0

            score += when {
                creditPct >= 30.0 -> 18.0
                creditPct >= 25.0 -> 14.0
                creditPct >= 18.0 -> 6.0
                else -> -8.0
            }

            if ((volRank?.volRank ?: 0.0) >= 50.0) {
                score += 8.0
            }

            val regimeFitLabel = when (regimeEvaluation?.regime) {
                MarketRegime.CONFIRMED_BEAR -> {
                    score += 22.0
                    "Optimal (Bear)"
                }
                MarketRegime.CAUTION_ELEVATED_VOL -> {
                    score += 10.0
                    "Aligned (Caution)"
                }
                MarketRegime.RISK_ON_BULL -> {
                    score -= 16.0
                    warnings += "Bull Regime active — selling upside calls risks whipsaw rallies."
                    "Counter-Trend"
                }
                null -> "Unassessed"
            }

            score += liquidityScoreAndWarnings(listOf(shortLeg, longLeg), warnings)
            val (earningsStatus, earningsDetail) = classifyEarnings(chain.symbol)

            val metrics = QuantCardMetrics(
                netDebitOrCreditPerShare = netCredit,
                isNetCredit = true,
                buyingPowerRequirement = maxLoss,
                maxProfit = maxProfit,
                maxLoss = maxLoss,
                breakevens = listOf(shortLeg.strike + netCredit),
                widthRatioPct = creditPct,
                widthRatioLabel = "Credit / Width",
                popPct = ((1.0 - shortLeg.delta) * 100.0).coerceIn(10.0, 95.0),
                netDelta = -shortLeg.delta + longLeg.delta,
                netThetaPerDay = 100.0 * (longLeg.thetaPerDay - shortLeg.thetaPerDay),
                netGamma = longLeg.gamma - shortLeg.gamma,
                netVegaPer1Pct = 100.0 * (longLeg.vegaPer1Pct - shortLeg.vegaPer1Pct),
                frontIv = shortLeg.iv,
                backIv = null,
                ivTermSpread = null,
                volRank52w = volRank?.volRank,
                annualizedRocPct = (maxProfit / maxLoss) * (365.0 / shortLeg.dte) * 100.0,
                distanceBelowSpotPct = null,
                minOpenInterest = min(shortLeg.openInterest, longLeg.openInterest),
                maxBidAskSpreadPct = max(shortLeg.spreadWidthPct, longLeg.spreadWidthPct),
                earningsStatus = earningsStatus,
                earningsDetail = earningsDetail
            )

            val candidate = buildCandidate(
                chain = chain,
                strategyType = OptionStrategyType.VERTICAL_BEAR_CALL,
                rawScore = score,
                regimeFitLabel = regimeFitLabel,
                edgeTag = "${fmt0(creditPct)}% Credit/W",
                legs = listOf(
                    toStrategyLeg(shortLeg, LegAction.SELL_TO_OPEN),
                    toStrategyLeg(longLeg, LegAction.BUY_TO_OPEN)
                ),
                metrics = metrics,
                regimeEvaluation = regimeEvaluation,
                warnings = warnings,
                isDemoData = isDemoData
            )
            if (best == null || candidate.compositeScore > best.compositeScore) {
                best = candidate
            }
        }
        return best
    }

    private fun buildBestCalendar(
        chain: ParsedOptionChain,
        volRank: VolRankResult?,
        regimeEvaluation: BearChecklistEvaluation?,
        isDemoData: Boolean
    ): StrategyCandidate? {
        val fronts = chain.calls.filter { it.dte in 21..35 && isTradeable(it) }
        val backs = chain.calls.filter { it.dte in 60..90 && isTradeable(it) }
        if (fronts.isEmpty() || backs.isEmpty()) return null

        val commonStrikes = fronts.map { it.strike }.toSet().intersect(backs.map { it.strike }.toSet())
        if (commonStrikes.isEmpty()) return null

        val atmStrike = commonStrikes.minByOrNull { abs(it - chain.underlyingPrice) } ?: return null
        val shortLeg = fronts.filter { it.strike == atmStrike }.minByOrNull { abs(it.dte - 28) } ?: return null
        val longLeg = backs.filter { it.strike == atmStrike }.minByOrNull { abs(it.dte - 75) } ?: return null

        val netDebit = longLeg.mid - shortLeg.mid
        if (netDebit <= 0.05) return null

        val bpr = netDebit * 100.0
        val termSpread = shortLeg.iv - longLeg.iv
        val positionTheta = 100.0 * (longLeg.thetaPerDay - shortLeg.thetaPerDay)
        val positionVega = 100.0 * (longLeg.vegaPer1Pct - shortLeg.vegaPer1Pct)

        val warnings = mutableListOf<String>()
        var score = 52.0

        // Strongly reward positive IV backwardation (frontIv > backIv)
        if (termSpread > 0.0) {
            score += (16.0 + termSpread * 100.0 * 2.0).coerceAtMost(24.0)
        } else if (termSpread < -0.015) {
            score -= 14.0
            warnings += "Calendar enters in IV Contango (${fmt1(termSpread * 100.0)}% term spread); front IV is cheaper than back IV."
        }

        val thetaVegaRatio = positionTheta / max(abs(positionVega), 0.1)
        if (positionTheta > 0.0 && thetaVegaRatio > 0.15) {
            score += (thetaVegaRatio * 12.0).coerceAtMost(8.0)
        }

        val regimeFitLabel = when (regimeEvaluation?.regime) {
            MarketRegime.CAUTION_ELEVATED_VOL -> {
                score += 14.0
                "Optimal (Caution)"
            }
            MarketRegime.CONFIRMED_BEAR -> {
                score += 6.0
                "Aligned (Bear)"
            }
            MarketRegime.RISK_ON_BULL -> {
                score += 4.0
                "Neutral (Bull)"
            }
            null -> "Unassessed"
        }

        score += liquidityScoreAndWarnings(listOf(shortLeg, longLeg), warnings)
        val (earningsStatus, earningsDetail) = classifyEarnings(chain.symbol)

        val metrics = QuantCardMetrics(
            netDebitOrCreditPerShare = netDebit,
            isNetCredit = false,
            buyingPowerRequirement = bpr,
            maxProfit = null,
            maxLoss = bpr,
            breakevens = listOf(atmStrike - netDebit, atmStrike + netDebit),
            widthRatioPct = null,
            widthRatioLabel = null,
            popPct = 58.0,
            netDelta = longLeg.delta - shortLeg.delta,
            netThetaPerDay = positionTheta,
            netGamma = longLeg.gamma - shortLeg.gamma,
            netVegaPer1Pct = positionVega,
            frontIv = shortLeg.iv,
            backIv = longLeg.iv,
            ivTermSpread = termSpread,
            volRank52w = volRank?.volRank,
            annualizedRocPct = null,
            distanceBelowSpotPct = null,
            minOpenInterest = min(shortLeg.openInterest, longLeg.openInterest),
            maxBidAskSpreadPct = max(shortLeg.spreadWidthPct, longLeg.spreadWidthPct),
            earningsStatus = earningsStatus,
            earningsDetail = earningsDetail
        )

        val termPct = termSpread * 100.0
        val edgeTag = if (termPct >= 0.0) "+${fmt1(termPct)}% IV Backw." else "${fmt1(termPct)}% Contango"

        return buildCandidate(
            chain = chain,
            strategyType = OptionStrategyType.CALENDAR,
            rawScore = score,
            regimeFitLabel = regimeFitLabel,
            edgeTag = edgeTag,
            legs = listOf(
                toStrategyLeg(longLeg, LegAction.BUY_TO_OPEN),
                toStrategyLeg(shortLeg, LegAction.SELL_TO_OPEN)
            ),
            metrics = metrics,
            regimeEvaluation = regimeEvaluation,
            warnings = warnings,
            isDemoData = isDemoData
        )
    }

    private fun buildBestIronCondor(
        chain: ParsedOptionChain,
        volRank: VolRankResult?,
        regimeEvaluation: BearChecklistEvaluation?,
        isDemoData: Boolean
    ): StrategyCandidate? {
        val targetWidth = if (chain.underlyingPrice < 300.0) 5.0 else 10.0
        val callsByExp = chain.calls.filter { it.dte in 30..45 && isTradeable(it) }.groupBy { it.expirationDate }
        val putsByExp = chain.puts.filter { it.dte in 30..45 && isTradeable(it) }.groupBy { it.expirationDate }

        val sharedExps = callsByExp.keys.intersect(putsByExp.keys)
        var best: StrategyCandidate? = null

        for (exp in sharedExps) {
            val expCalls = callsByExp[exp].orEmpty()
            val expPuts = putsByExp[exp].orEmpty()

            val shortPut = expPuts
                .filter { it.delta in -0.24..-0.13 }
                .minByOrNull { abs(it.delta - (-0.18)) } ?: continue
            val shortCall = expCalls
                .filter { it.delta in 0.13..0.24 }
                .minByOrNull { abs(it.delta - 0.18) } ?: continue
            if (shortPut.strike >= shortCall.strike) continue

            val longPut = expPuts
                .filter { it.strike < shortPut.strike && (shortPut.strike - it.strike) in 2.0..15.0 }
                .minByOrNull { abs((shortPut.strike - it.strike) - targetWidth) } ?: continue
            val longCall = expCalls
                .filter { it.strike > shortCall.strike && (it.strike - shortCall.strike) in 2.0..15.0 }
                .minByOrNull { abs((it.strike - shortCall.strike) - targetWidth) } ?: continue

            val putWidth = shortPut.strike - longPut.strike
            val callWidth = longCall.strike - shortCall.strike
            val maxWingWidth = max(putWidth, callWidth)

            val netCredit = (shortPut.mid - longPut.mid) + (shortCall.mid - longCall.mid)
            if (netCredit <= 0.10 || netCredit >= maxWingWidth) continue

            val maxLoss = (maxWingWidth - netCredit) * 100.0
            val maxProfit = netCredit * 100.0
            val creditPct = (netCredit / maxWingWidth) * 100.0
            val popPct = ((1.0 - abs(shortPut.delta) - shortCall.delta) * 100.0).coerceIn(15.0, 92.0)

            val warnings = mutableListOf<String>()
            var score = 54.0
            score += when {
                creditPct >= 28.0 -> 16.0
                creditPct >= 20.0 -> 10.0
                else -> 3.0
            }

            val isElevatedVolRank = (volRank?.volRank ?: 0.0) >= 50.0
            if (isElevatedVolRank) score += 8.0

            val regimeFitLabel = when (regimeEvaluation?.regime) {
                MarketRegime.CAUTION_ELEVATED_VOL -> {
                    score += if (isElevatedVolRank) 20.0 else 15.0
                    "Optimal (Caution)"
                }
                MarketRegime.RISK_ON_BULL -> {
                    score += 4.0
                    "Neutral (Bull)"
                }
                MarketRegime.CONFIRMED_BEAR -> {
                    score -= 6.0
                    "Caution (Bear)"
                }
                null -> "Unassessed"
            }

            val allLegs = listOf(longPut, shortPut, shortCall, longCall)
            score += liquidityScoreAndWarnings(allLegs, warnings)
            val (earningsStatus, earningsDetail) = classifyEarnings(chain.symbol)

            val metrics = QuantCardMetrics(
                netDebitOrCreditPerShare = netCredit,
                isNetCredit = true,
                buyingPowerRequirement = maxLoss,
                maxProfit = maxProfit,
                maxLoss = maxLoss,
                breakevens = listOf(shortPut.strike - netCredit, shortCall.strike + netCredit),
                widthRatioPct = creditPct,
                widthRatioLabel = "Credit / Wing",
                popPct = popPct,
                netDelta = -shortPut.delta + longPut.delta - shortCall.delta + longCall.delta,
                netThetaPerDay = 100.0 * (longPut.thetaPerDay - shortPut.thetaPerDay + longCall.thetaPerDay - shortCall.thetaPerDay),
                netGamma = longPut.gamma - shortPut.gamma + longCall.gamma - shortCall.gamma,
                netVegaPer1Pct = 100.0 * (longPut.vegaPer1Pct - shortPut.vegaPer1Pct + longCall.vegaPer1Pct - shortCall.vegaPer1Pct),
                frontIv = (shortPut.iv + shortCall.iv) * 0.5,
                backIv = null,
                ivTermSpread = null,
                volRank52w = volRank?.volRank,
                annualizedRocPct = (maxProfit / maxLoss) * (365.0 / shortCall.dte) * 100.0,
                distanceBelowSpotPct = ((chain.underlyingPrice - shortPut.strike) / chain.underlyingPrice) * 100.0,
                minOpenInterest = allLegs.minOf { it.openInterest },
                maxBidAskSpreadPct = allLegs.maxOf { it.spreadWidthPct },
                earningsStatus = earningsStatus,
                earningsDetail = earningsDetail
            )

            val candidate = buildCandidate(
                chain = chain,
                strategyType = OptionStrategyType.IRON_CONDOR,
                rawScore = score,
                regimeFitLabel = regimeFitLabel,
                edgeTag = "${fmt0(popPct)}% POP Condor",
                legs = listOf(
                    toStrategyLeg(longPut, LegAction.BUY_TO_OPEN),
                    toStrategyLeg(shortPut, LegAction.SELL_TO_OPEN),
                    toStrategyLeg(shortCall, LegAction.SELL_TO_OPEN),
                    toStrategyLeg(longCall, LegAction.BUY_TO_OPEN)
                ),
                metrics = metrics,
                regimeEvaluation = regimeEvaluation,
                warnings = warnings,
                isDemoData = isDemoData
            )
            if (best == null || candidate.compositeScore > best.compositeScore) {
                best = candidate
            }
        }
        return best
    }

    private fun buildBestWheelCsp(
        chain: ParsedOptionChain,
        volRank: VolRankResult?,
        sma50: Double?,
        regimeEvaluation: BearChecklistEvaluation?,
        isDemoData: Boolean
    ): StrategyCandidate? {
        val candidates = chain.puts
            .filter { it.dte in 25..45 && it.delta in -0.33..-0.22 && isTradeable(it) }
            .sortedBy { abs(it.delta - (-0.27)) }
            .take(3)

        val shortPut = candidates.firstOrNull() ?: return null
        val credit = shortPut.mid
        if (credit <= 0.05) return null

        val collateral = ((shortPut.strike - credit) * 100.0).coerceAtLeast(100.0)
        val maxProfit = credit * 100.0
        val annualizedRoc = (maxProfit / collateral) * (365.0 / shortPut.dte) * 100.0
        val distanceBelowSpot = ((chain.underlyingPrice - shortPut.strike) / chain.underlyingPrice) * 100.0

        val warnings = mutableListOf<String>()
        var score = 54.0
        score += (annualizedRoc * 0.4).coerceIn(2.0, 14.0)

        if (sma50 != null && shortPut.strike <= sma50) {
            score += 8.0
        }

        val regimeFitLabel = when (regimeEvaluation?.regime) {
            MarketRegime.RISK_ON_BULL -> {
                score += 12.0
                "Optimal (Bull)"
            }
            MarketRegime.CAUTION_ELEVATED_VOL -> {
                score += 2.0
                "Caution"
            }
            MarketRegime.CONFIRMED_BEAR -> {
                score -= 22.0
                warnings += "Bear Regime active — Cash-Secured Puts risk early assignment in falling tape."
                "Avoid (Bear)"
            }
            null -> "Unassessed"
        }

        score += liquidityScoreAndWarnings(listOf(shortPut), warnings)
        val (earningsStatus, earningsDetail) = classifyEarnings(chain.symbol)

        val metrics = QuantCardMetrics(
            netDebitOrCreditPerShare = credit,
            isNetCredit = true,
            buyingPowerRequirement = collateral,
            maxProfit = maxProfit,
            maxLoss = collateral,
            breakevens = listOf(shortPut.strike - credit),
            widthRatioPct = null,
            widthRatioLabel = null,
            popPct = ((1.0 - abs(shortPut.delta)) * 100.0).coerceIn(15.0, 92.0),
            netDelta = -shortPut.delta,
            netThetaPerDay = -100.0 * shortPut.thetaPerDay,
            netGamma = -shortPut.gamma,
            netVegaPer1Pct = -100.0 * shortPut.vegaPer1Pct,
            frontIv = shortPut.iv,
            backIv = null,
            ivTermSpread = null,
            volRank52w = volRank?.volRank,
            annualizedRocPct = annualizedRoc,
            distanceBelowSpotPct = distanceBelowSpot,
            minOpenInterest = shortPut.openInterest,
            maxBidAskSpreadPct = shortPut.spreadWidthPct,
            earningsStatus = earningsStatus,
            earningsDetail = earningsDetail
        )

        return buildCandidate(
            chain = chain,
            strategyType = OptionStrategyType.WHEEL_CSP,
            rawScore = score,
            regimeFitLabel = regimeFitLabel,
            edgeTag = "${fmt1(annualizedRoc)}% Ann. ROC",
            legs = listOf(toStrategyLeg(shortPut, LegAction.SELL_TO_OPEN)),
            metrics = metrics,
            regimeEvaluation = regimeEvaluation,
            warnings = warnings,
            isDemoData = isDemoData
        )
    }

    private fun buildCandidate(
        chain: ParsedOptionChain,
        strategyType: OptionStrategyType,
        rawScore: Double,
        regimeFitLabel: String,
        edgeTag: String,
        legs: List<StrategyLeg>,
        metrics: QuantCardMetrics,
        regimeEvaluation: BearChecklistEvaluation?,
        warnings: List<String>,
        isDemoData: Boolean
    ): StrategyCandidate {
        val explainer = explainerGenerator.generate(
            underlyingSymbol = chain.symbol,
            underlyingSpot = chain.underlyingPrice,
            strategyType = strategyType,
            legs = legs,
            metrics = metrics,
            regimeEvaluation = regimeEvaluation,
            warnings = warnings
        )
        val legKey = legs.joinToString("_") { "${it.action.shortLabel}-${it.occSymbol.trim()}" }
        return StrategyCandidate(
            id = "${chain.symbol}_${strategyType.name}_$legKey",
            underlyingSymbol = chain.symbol,
            underlyingSpot = chain.underlyingPrice,
            strategyType = strategyType,
            compositeScore = rawScore.roundToInt().coerceIn(0, 100),
            regimeFitLabel = regimeFitLabel,
            edgeTag = edgeTag,
            legs = legs,
            metrics = metrics,
            explainer = explainer,
            isDemoData = isDemoData
        )
    }

    private fun resolveEnabledStrategies(filters: Set<StrategyFilterChip>): Set<OptionStrategyType> {
        if (filters.isEmpty() || StrategyFilterChip.ALL in filters) {
            return OptionStrategyType.entries.toSet()
        }
        return OptionStrategyType.entries.filter { it.filterChip in filters }.toSet()
    }

    private fun isTradeable(contract: ParsedOptionContract): Boolean {
        if (contract.mid <= 0.0) return false
        val dollarSpread = (contract.ask - contract.bid).coerceAtLeast(0.0)
        // Permit tight tick-sized quotes (<= $0.15) on cheap OTM wings while rejecting illiquid wide quotes
        return dollarSpread <= 0.15 || contract.spreadWidthPct <= 0.30
    }

    private fun liquidityScoreAndWarnings(
        contracts: List<ParsedOptionContract>,
        warnings: MutableList<String>
    ): Double {
        var adj = 0.0
        val minOi = contracts.minOfOrNull { it.openInterest } ?: 0
        val maxSpread = contracts.maxOfOrNull { it.spreadWidthPct } ?: 0.0

        when {
            minOi >= PREFERRED_MIN_OI -> adj += 5.0
            minOi >= 25 -> {
                adj -= 10.0
                warnings += "Low Open Interest ($minOi < $PREFERRED_MIN_OI preferred) on at least one leg."
            }
            else -> {
                adj -= 20.0
                warnings += "Very thin liquidity ($minOi OI) — use strict limit orders at mid."
            }
        }

        val maxDollarSpread = contracts.maxOfOrNull { (it.ask - it.bid).coerceAtLeast(0.0) } ?: 0.0
        if (maxDollarSpread > 0.15) {
            when {
                maxSpread <= 0.05 -> adj += 4.0
                maxSpread <= 0.12 -> adj -= 3.0
                else -> {
                    adj -= 12.0
                    warnings += "Wide bid-ask spread (${fmt1(maxSpread * 100.0)}%) increases slippage."
                }
            }
        }
        return adj
    }

    private fun classifyEarnings(symbol: String): Pair<EarningsCheckStatus, String> {
        return if (LiquidUniverse.isEtf(symbol)) {
            EarningsCheckStatus.NO_EARNINGS_ETF to "$symbol is an ETF (zero single-stock earnings gap risk)."
        } else {
            EarningsCheckStatus.VERIFY_EARNINGS to "Confirm next $symbol quarterly earnings date before short-leg entry."
        }
    }

    private fun toStrategyLeg(contract: ParsedOptionContract, action: LegAction) = StrategyLeg(
        occSymbol = contract.symbol,
        action = action,
        quantity = 1,
        isCall = contract.isCall,
        strike = contract.strike,
        expirationIsoDate = contract.expirationDate.toString(),
        dte = contract.dte,
        bid = contract.bid,
        ask = contract.ask,
        mid = contract.mid,
        delta = contract.delta,
        gamma = contract.gamma,
        thetaPerDay = contract.thetaPerDay,
        vegaPer1Pct = contract.vegaPer1Pct,
        impliedVolatility = contract.iv,
        openInterest = contract.openInterest,
        volume = contract.volume,
        spreadWidthPct = contract.spreadWidthPct
    )

    private fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

    companion object {
        private const val MAX_LIVE_SCAN_CONCURRENCY = 3
        const val PREFERRED_MIN_OI = 100

        private val CANDIDATE_COMPARATOR = compareByDescending<StrategyCandidate> { it.compositeScore }
            .thenBy { it.underlyingSymbol }
            .thenBy { it.strategyType.ordinal }
    }
}
