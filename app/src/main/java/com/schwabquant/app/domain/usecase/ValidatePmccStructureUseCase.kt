package com.schwabquant.app.domain.usecase

import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.PmccBasisMode
import com.schwabquant.app.domain.model.PmccFinding
import com.schwabquant.app.domain.model.PmccOptionLeg
import com.schwabquant.app.domain.model.PmccPair
import com.schwabquant.app.domain.model.PmccSeverity
import com.schwabquant.app.domain.model.PmccValidationReport
import com.schwabquant.app.domain.model.PmccValidatorConfig
import com.schwabquant.app.domain.model.Position
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Deterministic 2-Stage, 5-Gate PMCC Structural Validator & Pre-Trade Roll Gatekeeper.
 *
 * Stage 1 (`pairPmccPositions`): Allocates equity-covered short calls first (`floor(shares/100)`),
 * splits multi-contract option legs into 1-lot units, and monotonically pairs remaining shorts
 * with the lowest-strike eligible long LEAPS (`long.dte > short.dte`), consolidating identical pairs.
 *
 * Stage 2 (`validatePmccBook` / `validateCandidateRoll`): Enforces Geometry, Cost Basis (with
 * `supersededBy` precedence when geometry blocks), Delta, Hygiene, and Orphan gates.
 */
@Singleton
class ValidatePmccStructureUseCase @Inject constructor() {

    data class PairingStageResult(
        val pairs: List<PmccPair>,
        val coveredByEquityShorts: List<PmccOptionLeg>,
        val uncoveredShortCalls: List<PmccOptionLeg>,
        val unhedgedLeaps: List<PmccOptionLeg>
    )

    fun validatePortfolio(
        positions: List<Position>,
        rollCreditsByLongSymbol: Map<String, Double>? = null,
        config: PmccValidatorConfig = PmccValidatorConfig()
    ): PmccValidationReport {
        val sharesByUnderlying = HashMap<String, Double>()
        positions.filter { it.category == AssetCategory.EQUITY }.forEach { eq ->
            val sym = SchwabSymbolNormalizer.toDisplaySymbol(eq.underlyingSymbol)
            val shares = eq.quantity.toDouble()
            if (shares > 0.0) {
                sharesByUnderlying[sym] = (sharesByUnderlying[sym] ?: 0.0) + shares
            }
        }

        val callLegs = positions.mapNotNull { pos ->
            if (pos.category != AssetCategory.OPTION) return@mapNotNull null
            if (pos.isCall != true) return@mapNotNull null
            val qty = pos.quantity.toDouble()
            if (abs(qty) < 1e-6) return@mapNotNull null
            PmccOptionLeg(
                symbol = pos.symbol,
                underlying = SchwabSymbolNormalizer.toDisplaySymbol(pos.underlyingSymbol),
                strike = pos.strike ?: return@mapNotNull null,
                dte = pos.daysToExpiration ?: 0,
                quantity = qty,
                avgCost = pos.averageCost.toDouble(),
                mark = pos.markPrice.toDouble(),
                delta = pos.delta ?: 0.0,
                isCall = true,
                expirationIsoDate = pos.expirationDate
            )
        }

        return validatePmccBook(
            optionLegs = callLegs,
            sharesByUnderlying = sharesByUnderlying,
            rollCreditsByLongSymbol = rollCreditsByLongSymbol,
            config = config
        )
    }

    fun pairPmccPositions(
        optionLegs: List<PmccOptionLeg>,
        sharesByUnderlying: Map<String, Double> = emptyMap(),
        rollCreditsByLongSymbol: Map<String, Double>? = null,
        config: PmccValidatorConfig = PmccValidatorConfig()
    ): PairingStageResult {
        val normalizedShares = HashMap<String, Double>()
        sharesByUnderlying.forEach { (k, v) ->
            val clean = SchwabSymbolNormalizer.toDisplaySymbol(k)
            normalizedShares[clean] = (normalizedShares[clean] ?: 0.0) + v
        }

        val legsByUnderlying = optionLegs
            .filter { it.isCall && abs(it.quantity) >= 1e-6 }
            .map { it.copy(underlying = SchwabSymbolNormalizer.toDisplaySymbol(it.underlying)) }
            .groupBy { it.underlying }

        val consolidatedPairs = mutableListOf<PmccPair>()
        val coveredEquityShorts = mutableListOf<PmccOptionLeg>()
        val uncoveredShorts = mutableListOf<PmccOptionLeg>()
        val unhedgedLeaps = mutableListOf<PmccOptionLeg>()

        legsByUnderlying.keys.sorted().forEach { underlying ->
            val legs = legsByUnderlying[underlying].orEmpty()
            val equityLots = floor(max(0.0, normalizedShares[underlying] ?: 0.0) / 100.0).toInt()

            val longUnits = mutableListOf<PmccOptionLeg>()
            val shortUnits = mutableListOf<PmccOptionLeg>()

            legs.forEach { leg ->
                val count = abs(leg.quantity).roundToInt().coerceAtLeast(1)
                if (leg.quantity > 0.0) {
                    repeat(count) { longUnits += leg.copy(quantity = 1.0) }
                } else {
                    repeat(count) { shortUnits += leg.copy(quantity = -1.0) }
                }
            }

            // 1. Allocate equity covered-call capacity to shortest-DTE, lowest-strike short calls first.
            val shortsSortedForEquity = shortUnits.sortedWith(
                compareBy<PmccOptionLeg> { it.dte }
                    .thenBy { it.strike }
                    .thenBy { it.symbol }
            )
            val coveredIndices = mutableSetOf<Int>()
            val equityCoveredBuckets = LinkedHashMap<String, PmccOptionLeg>()
            for (idx in shortsSortedForEquity.indices) {
                if (coveredIndices.size >= equityLots) break
                val shortUnit = shortsSortedForEquity[idx]
                coveredIndices += idx
                val existing = equityCoveredBuckets[shortUnit.symbol]
                equityCoveredBuckets[shortUnit.symbol] = if (existing == null) {
                    shortUnit.copy(quantity = -1.0)
                } else {
                    existing.copy(quantity = existing.quantity - 1.0)
                }
            }
            coveredEquityShorts += equityCoveredBuckets.values

            val remainingShorts = shortsSortedForEquity
                .filterIndexed { idx, _ -> idx !in coveredIndices }
                .sortedWith(
                    compareBy<PmccOptionLeg> { it.strike }
                        .thenBy { it.dte }
                        .thenBy { it.symbol }
                )

            val remainingLongs = longUnits
                .sortedWith(
                    compareBy<PmccOptionLeg> { it.strike }
                        .thenByDescending { it.dte }
                        .thenBy { it.symbol }
                )
                .toMutableList()

            val unitPairs = mutableListOf<Pair<PmccOptionLeg, PmccOptionLeg>>()
            val localUncovered = LinkedHashMap<String, PmccOptionLeg>()

            // 2. Monotonically pair each short 1-lot with the lowest-strike eligible long (long.dte > short.dte).
            for (shortUnit in remainingShorts) {
                val chosenIdx = selectLongIndexForShort(remainingLongs, shortUnit)
                if (chosenIdx >= 0) {
                    val longUnit = remainingLongs.removeAt(chosenIdx)
                    unitPairs += (longUnit to shortUnit)
                } else {
                    val prev = localUncovered[shortUnit.symbol]
                    localUncovered[shortUnit.symbol] = if (prev == null) {
                        shortUnit.copy(quantity = -1.0)
                    } else {
                        prev.copy(quantity = prev.quantity - 1.0)
                    }
                }
            }
            uncoveredShorts += localUncovered.values

            val localUnhedged = LinkedHashMap<String, PmccOptionLeg>()
            remainingLongs.filter { it.dte >= config.minLeapsDte }.forEach { leaps ->
                val prev = localUnhedged[leaps.symbol]
                localUnhedged[leaps.symbol] = if (prev == null) {
                    leaps.copy(quantity = 1.0)
                } else {
                    prev.copy(quantity = prev.quantity + 1.0)
                }
            }
            unhedgedLeaps += localUnhedged.values

            // 3. Consolidate identical (long.symbol, short.symbol) 1-lot pairs into multi-contract pairs.
            unitPairs.groupBy { it.first.symbol to it.second.symbol }.values.forEach { grouped ->
                val longSample = grouped.first().first
                val shortSample = grouped.first().second
                val contracts = grouped.size
                consolidatedPairs += buildEvaluatedPair(
                    underlying = underlying,
                    longLeg = longSample.copy(quantity = contracts.toDouble()),
                    shortLeg = shortSample.copy(quantity = -contracts.toDouble()),
                    contracts = contracts,
                    explicitRollCredits = rollCreditsByLongSymbol?.get(longSample.symbol),
                    explicitBasisMode = null,
                    overrideNetDebitBasis = null,
                    config = config
                )
            }
        }

        return PairingStageResult(
            pairs = consolidatedPairs,
            coveredByEquityShorts = coveredEquityShorts,
            uncoveredShortCalls = uncoveredShorts,
            unhedgedLeaps = unhedgedLeaps
        )
    }

    fun validatePmccBook(
        optionLegs: List<PmccOptionLeg>,
        sharesByUnderlying: Map<String, Double> = emptyMap(),
        rollCreditsByLongSymbol: Map<String, Double>? = null,
        config: PmccValidatorConfig = PmccValidatorConfig()
    ): PmccValidationReport {
        val staged = pairPmccPositions(
            optionLegs = optionLegs,
            sharesByUnderlying = sharesByUnderlying,
            rollCreditsByLongSymbol = rollCreditsByLongSymbol,
            config = config
        )

        val allFindings = mutableListOf<PmccFinding>()
        staged.pairs.forEach { pair -> allFindings += pair.findings }

        // Gate 5: Orphans
        staged.uncoveredShortCalls.forEach { shortLeg ->
            allFindings += PmccFinding(
                code = CODE_UNCOVERED_SHORT_CALL,
                severity = PmccSeverity.CRITICAL,
                message = "Uncovered short call ${shortLeg.symbol} (${abs(shortLeg.quantity).roundToInt()}x K=${fmt2(shortLeg.strike)}, ${shortLeg.dte} DTE) has no long LEAPS or 100-share cover.",
                underlying = shortLeg.underlying,
                pairLabel = shortLeg.symbol
            )
        }

        val passed = staged.pairs.all { it.passed } &&
            staged.uncoveredShortCalls.isEmpty() &&
            allFindings.none { it.severity != PmccSeverity.INFO }

        return PmccValidationReport(
            pairs = staged.pairs,
            findings = allFindings,
            coveredByEquityShorts = staged.coveredByEquityShorts,
            uncoveredShortCalls = staged.uncoveredShortCalls,
            unhedgedLeaps = staged.unhedgedLeaps,
            passed = passed
        )
    }

    fun validateCandidateRoll(
        existingPair: PmccPair,
        newShortLeg: PmccOptionLeg,
        netRollCredit: Double,
        config: PmccValidatorConfig = PmccValidatorConfig()
    ): PmccValidationReport {
        val contracts = existingPair.contracts.coerceAtLeast(1)
        val normalizedShort = newShortLeg.copy(
            underlying = existingPair.underlying,
            quantity = -contracts.toDouble()
        )
        val newNetDebitBasis = existingPair.netDebitBasis - netRollCredit
        val newClosedCredits = existingPair.closedRollCredits + netRollCredit

        val rolledPair = buildEvaluatedPair(
            underlying = existingPair.underlying,
            longLeg = existingPair.longLeg,
            shortLeg = normalizedShort,
            contracts = contracts,
            explicitRollCredits = newClosedCredits,
            explicitBasisMode = existingPair.basisMode,
            overrideNetDebitBasis = newNetDebitBasis,
            config = config
        )

        val findings = rolledPair.findings.toMutableList()
        if (existingPair.longLeg.dte <= normalizedShort.dte) {
            findings += PmccFinding(
                code = CODE_UNCOVERED_SHORT_CALL,
                severity = PmccSeverity.CRITICAL,
                message = "Candidate short ${normalizedShort.symbol} (${normalizedShort.dte} DTE) expires on/after LEAPS ${existingPair.longLeg.symbol} (${existingPair.longLeg.dte} DTE).",
                underlying = existingPair.underlying,
                pairLabel = pairLabel(existingPair.longLeg, normalizedShort)
            )
        }

        val pairWithDteCheck = rolledPair.copy(
            findings = findings,
            passed = findings.none { it.severity != PmccSeverity.INFO }
        )

        return PmccValidationReport(
            pairs = listOf(pairWithDteCheck),
            findings = findings,
            coveredByEquityShorts = emptyList(),
            uncoveredShortCalls = if (existingPair.longLeg.dte <= normalizedShort.dte) listOf(normalizedShort) else emptyList(),
            unhedgedLeaps = emptyList(),
            passed = pairWithDteCheck.passed
        )
    }

    private fun selectLongIndexForShort(
        availableLongs: List<PmccOptionLeg>,
        shortUnit: PmccOptionLeg
    ): Int {
        // Prefer valid diagonal (short.strike > long.strike) with lowest long strike
        val strictIdx = availableLongs.indexOfFirst { long ->
            long.dte > shortUnit.dte && shortUnit.strike > long.strike + 1e-6
        }
        if (strictIdx >= 0) return strictIdx

        // Next preserve exact calendars (e.g., C165/C165)
        val calendarIdx = availableLongs.indexOfFirst { long ->
            long.dte > shortUnit.dte && abs(shortUnit.strike - long.strike) <= 1e-6
        }
        if (calendarIdx >= 0) return calendarIdx

        // Otherwise take lowest-strike longer-dated long so inversions get caught explicitly by Gate 1
        return availableLongs.indexOfFirst { long -> long.dte > shortUnit.dte }
    }

    private fun buildEvaluatedPair(
        underlying: String,
        longLeg: PmccOptionLeg,
        shortLeg: PmccOptionLeg,
        contracts: Int,
        explicitRollCredits: Double?,
        explicitBasisMode: PmccBasisMode?,
        overrideNetDebitBasis: Double?,
        config: PmccValidatorConfig
    ): PmccPair {
        val label = pairLabel(longLeg, shortLeg)
        val strikeWidth = shortLeg.strike - longLeg.strike
        val hasExplicitLedgerCredits = explicitBasisMode == PmccBasisMode.LEDGER_ADJUSTED ||
            (explicitBasisMode == null && explicitRollCredits != null)

        val closedCredits = explicitRollCredits ?: 0.0
        val basisMode = explicitBasisMode ?: if (hasExplicitLedgerCredits) {
            PmccBasisMode.LEDGER_ADJUSTED
        } else {
            PmccBasisMode.OPEN_LEG_COST
        }

        val netDebitBasis = overrideNetDebitBasis ?: when (basisMode) {
            PmccBasisMode.LEDGER_ADJUSTED -> longLeg.avgCost - shortLeg.avgCost - closedCredits
            PmccBasisMode.OPEN_LEG_COST -> longLeg.avgCost - shortLeg.avgCost
            PmccBasisMode.MARK_TO_MARKET -> longLeg.mark - shortLeg.mark
        }

        val widthBufferBasis = strikeWidth - netDebitBasis
        val widthBufferMark = strikeWidth - (longLeg.mark - shortLeg.mark)
        val longDeltaAbs = abs(longLeg.delta)
        val shortDeltaAbs = abs(shortLeg.delta)
        val netDelta = longDeltaAbs - shortDeltaAbs

        val findings = mutableListOf<PmccFinding>()

        if (basisMode == PmccBasisMode.OPEN_LEG_COST && overrideNetDebitBasis == null) {
            findings += PmccFinding(
                code = CODE_BASIS_UNADJUSTED_FOR_ROLLS,
                severity = PmccSeverity.INFO,
                message = "Using open leg cost (${fmt2(longLeg.avgCost)} - ${fmt2(shortLeg.avgCost)}); prior closed short roll credits are not supplied.",
                underlying = underlying,
                pairLabel = label
            )
        }

        // Gate 1: Geometry
        var geometryBlocker: String? = null
        if (strikeWidth < -1e-6) {
            geometryBlocker = CODE_INVERTED_DIAGONAL
            findings += PmccFinding(
                code = CODE_INVERTED_DIAGONAL,
                severity = PmccSeverity.CRITICAL,
                message = "Inverted diagonal: short strike $${fmt2(shortLeg.strike)} < long strike $${fmt2(longLeg.strike)} (width -$${fmt2(abs(strikeWidth))}).",
                underlying = underlying,
                pairLabel = label
            )
        } else if (abs(strikeWidth) <= 1e-6) {
            geometryBlocker = CODE_ZERO_WIDTH_CALENDAR
            findings += PmccFinding(
                code = CODE_ZERO_WIDTH_CALENDAR,
                severity = PmccSeverity.CRITICAL,
                message = "Zero-width calendar spread at K=$${fmt2(longLeg.strike)} caps upside profit cleanly against $${fmt2(netDebitBasis)} debit.",
                underlying = underlying,
                pairLabel = label
            )
        }

        // Gate 2: Cost Basis
        if (widthBufferBasis < -1e-6) {
            findings += PmccFinding(
                code = CODE_WIDTH_TO_DEBIT_BREACH,
                severity = PmccSeverity.ERROR,
                message = "Width-to-debit breach: strike width $${fmt2(strikeWidth)} < net debit $${fmt2(netDebitBasis)} (buffer ${signedDollar(widthBufferBasis)}).",
                underlying = underlying,
                pairLabel = label,
                supersededBy = geometryBlocker
            )
        } else if (geometryBlocker == null) {
            val minSafeBuffer = max(config.thinBufferFloor, config.thinBufferPct * max(netDebitBasis, 0.0))
            if (widthBufferBasis < minSafeBuffer) {
                findings += PmccFinding(
                    code = CODE_WIDTH_BUFFER_THIN,
                    severity = PmccSeverity.WARNING,
                    message = "Thin PMCC width buffer +$${fmt2(widthBufferBasis)} < min safety floor $${fmt2(minSafeBuffer)}.",
                    underlying = underlying,
                    pairLabel = label
                )
            }
        }

        // Gate 3: Delta
        if (longDeltaAbs < config.leapsMinDelta) {
            findings += PmccFinding(
                code = CODE_LEAPS_DELTA_LOW,
                severity = PmccSeverity.ERROR,
                message = "LEAPS delta ${fmt2(longDeltaAbs)} < ${fmt2(config.leapsMinDelta)} minimum threshold.",
                underlying = underlying,
                pairLabel = label
            )
        } else if (longDeltaAbs < config.leapsTargetDelta) {
            findings += PmccFinding(
                code = CODE_LEAPS_DELTA_MARGINAL,
                severity = PmccSeverity.WARNING,
                message = "LEAPS delta ${fmt2(longDeltaAbs)} below ideal ${fmt2(config.leapsTargetDelta)} target.",
                underlying = underlying,
                pairLabel = label
            )
        }

        if (netDelta < config.netDeltaMin) {
            findings += PmccFinding(
                code = CODE_NET_DELTA_COMPRESSED,
                severity = PmccSeverity.WARNING,
                message = "Net diagonal delta ${fmt2(netDelta)} < ${fmt2(config.netDeltaMin)} floor (short call absorbing LEAPS upside).",
                underlying = underlying,
                pairLabel = label
            )
        }

        // Gate 4: Hygiene
        if (longLeg.dte < config.minLeapsDte) {
            findings += PmccFinding(
                code = CODE_LEAPS_DTE_LOW,
                severity = PmccSeverity.WARNING,
                message = "Long leg ${longLeg.dte} DTE < ${config.minLeapsDte} DTE LEAPS threshold.",
                underlying = underlying,
                pairLabel = label
            )
        }
        if (shortDeltaAbs >= config.shortItmRollDelta && shortLeg.dte <= config.shortItmRollDte) {
            findings += PmccFinding(
                code = CODE_SHORT_ITM_ROLL_TRIGGER,
                severity = PmccSeverity.WARNING,
                message = "Short call ${fmt2(shortDeltaAbs)}Δ (${shortLeg.dte} DTE) triggered ITM roll rule (>= ${fmt2(config.shortItmRollDelta)}Δ & <= ${config.shortItmRollDte} DTE).",
                underlying = underlying,
                pairLabel = label
            )
        }

        val passed = findings.none { it.severity != PmccSeverity.INFO }
        return PmccPair(
            underlying = underlying,
            longLeg = longLeg,
            shortLeg = shortLeg,
            contracts = contracts,
            strikeWidth = strikeWidth,
            netDebitBasis = netDebitBasis,
            basisMode = basisMode,
            closedRollCredits = closedCredits,
            widthBufferBasis = widthBufferBasis,
            widthBufferMark = widthBufferMark,
            netDelta = netDelta,
            findings = findings,
            passed = passed
        )
    }

    private fun pairLabel(longLeg: PmccOptionLeg, shortLeg: PmccOptionLeg): String =
        "${longLeg.underlying} +C${stripZero(longLeg.strike)}/-C${stripZero(shortLeg.strike)}"

    private fun stripZero(v: Double): String =
        if (abs(v - v.roundToInt()) < 1e-4) v.roundToInt().toString() else fmt2(v)

    private fun signedDollar(v: Double): String =
        if (v >= 0) "+$${fmt2(v)}" else "-$${fmt2(abs(v))}"

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    companion object {
        const val CODE_INVERTED_DIAGONAL = "INVERTED_DIAGONAL"
        const val CODE_ZERO_WIDTH_CALENDAR = "ZERO_WIDTH_CALENDAR"
        const val CODE_WIDTH_TO_DEBIT_BREACH = "WIDTH_TO_DEBIT_BREACH"
        const val CODE_WIDTH_BUFFER_THIN = "WIDTH_BUFFER_THIN"
        const val CODE_LEAPS_DELTA_LOW = "LEAPS_DELTA_LOW"
        const val CODE_LEAPS_DELTA_MARGINAL = "LEAPS_DELTA_MARGINAL"
        const val CODE_NET_DELTA_COMPRESSED = "NET_DELTA_COMPRESSED"
        const val CODE_LEAPS_DTE_LOW = "LEAPS_DTE_LOW"
        const val CODE_SHORT_ITM_ROLL_TRIGGER = "SHORT_ITM_ROLL_TRIGGER"
        const val CODE_UNCOVERED_SHORT_CALL = "UNCOVERED_SHORT_CALL"
        const val CODE_BASIS_UNADJUSTED_FOR_ROLLS = "BASIS_UNADJUSTED_FOR_ROLLS"
    }
}
