package com.schwabquant.app.core.quant

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class StrikeWindowFilter(
    val label: String,
    val halfCount: Int?
) {
    PM_6("±6", 6),
    PM_12("±12", 12),
    PM_20("±20", 20),
    ALL("All", null)
}

enum class ChainColumnPreset(val label: String) {
    EXECUTION("Exec"),
    GREEKS("Greeks"),
    INCOME_PMCC("PMCC")
}

enum class ExpirationCycleTag(val badge: String) {
    WEEKLY("WEEKLY"),
    MONTHLY("MONTHLY"),
    LEAPS("LEAPS")
}

data class OptionStrikeRow(
    val strike: Double,
    val call: ParsedOptionContract?,
    val put: ParsedOptionContract?,
    val isCallItm: Boolean,
    val isPutItm: Boolean,
    val isAtmBoundary: Boolean
)

data class ExpirationChainSection(
    val expirationDate: LocalDate,
    val formattedDate: String,
    val dte: Int,
    val cycleTag: ExpirationCycleTag,
    val atmIvPct: Double,
    val expectedMoveDollars: Double,
    val expectedMovePct: Double,
    val atmStrike: Double,
    val rows: List<OptionStrikeRow>,
    val totalStrikeCount: Int
)

data class OptionsChainBoard(
    val symbol: String,
    val underlyingSpot: Double,
    val sections: List<ExpirationChainSection>
)

/**
 * Pure-JVM Thinkorswim-style paired CALLS | STRIKE | PUTS board builder with expiration grouping,
 * cycle tags, ATM IV%, ToS Expected Move (`0.85 * straddle`), ITM shading flags, and window slicing.
 */
@Singleton
class OptionsChainBoardBuilder @Inject constructor() {

    fun buildBoard(
        chain: ParsedOptionChain,
        strikeWindow: StrikeWindowFilter = StrikeWindowFilter.PM_12
    ): OptionsChainBoard {
        val spot = chain.underlyingPrice
        if (chain.contracts.isEmpty() || spot <= 0.0) {
            return OptionsChainBoard(
                symbol = chain.symbol,
                underlyingSpot = spot,
                sections = emptyList()
            )
        }

        val sections = chain.contracts
            .groupBy { it.expirationDate }
            .entries
            .sortedBy { it.key }
            .map { (expDate, expContracts) ->
                val dte = expContracts.maxOf { it.dte }.coerceAtLeast(1)
                val callsByStrike = expContracts.filter { it.isCall }.associateBy { it.strike }
                val putsByStrike = expContracts.filter { !it.isCall }.associateBy { it.strike }
                val allStrikes = (callsByStrike.keys + putsByStrike.keys).toSortedSet().toList()

                val atmStrike = allStrikes.minByOrNull { abs(it - spot) } ?: spot
                val pairedAtmStrike = allStrikes
                    .filter { callsByStrike[it] != null && putsByStrike[it] != null }
                    .minByOrNull { abs(it - spot) }
                    ?: atmStrike

                val atmCall = callsByStrike[pairedAtmStrike]
                val atmPut = putsByStrike[pairedAtmStrike]

                val atmIvDecimal = when {
                    atmCall != null && atmPut != null && atmCall.iv > 0.0 && atmPut.iv > 0.0 ->
                        (atmCall.iv + atmPut.iv) * 0.5
                    atmCall != null && atmCall.iv > 0.0 -> atmCall.iv
                    atmPut != null && atmPut.iv > 0.0 -> atmPut.iv
                    else -> chain.atm30DayIv ?: chain.chainVolatility ?: 0.25
                }

                val expectedMove = if (atmCall != null && atmPut != null && atmCall.mid > 0.0 && atmPut.mid > 0.0) {
                    0.85 * (atmCall.mid + atmPut.mid)
                } else {
                    spot * atmIvDecimal * sqrt(max(1, dte) / 365.0)
                }
                val expectedMovePct = if (spot > 0.0) (expectedMove / spot) * 100.0 else 0.0

                val nearestIdx = allStrikes.indices.minByOrNull { abs(allStrikes[it] - spot) } ?: 0
                val visibleStrikes = if (strikeWindow.halfCount == null || allStrikes.isEmpty()) {
                    allStrikes
                } else {
                    val start = (nearestIdx - strikeWindow.halfCount).coerceAtLeast(0)
                    val end = (nearestIdx + strikeWindow.halfCount).coerceAtMost(allStrikes.lastIndex)
                    allStrikes.subList(start, end + 1)
                }

                val rows = visibleStrikes.map { strike ->
                    OptionStrikeRow(
                        strike = strike,
                        call = callsByStrike[strike],
                        put = putsByStrike[strike],
                        isCallItm = strike < spot,
                        isPutItm = strike > spot,
                        isAtmBoundary = abs(strike - atmStrike) < 1e-6
                    )
                }

                ExpirationChainSection(
                    expirationDate = expDate,
                    formattedDate = expDate.format(HEADER_DATE_FORMAT).uppercase(Locale.US),
                    dte = dte,
                    cycleTag = classifyCycle(expDate, dte),
                    atmIvPct = atmIvDecimal * 100.0,
                    expectedMoveDollars = expectedMove,
                    expectedMovePct = expectedMovePct,
                    atmStrike = atmStrike,
                    rows = rows,
                    totalStrikeCount = allStrikes.size
                )
            }

        return OptionsChainBoard(
            symbol = chain.symbol,
            underlyingSpot = spot,
            sections = sections
        )
    }

    private fun classifyCycle(expDate: LocalDate, dte: Int): ExpirationCycleTag = when {
        dte >= 180 -> ExpirationCycleTag.LEAPS
        expDate.dayOfWeek == DayOfWeek.FRIDAY && expDate.dayOfMonth in 15..21 -> ExpirationCycleTag.MONTHLY
        else -> ExpirationCycleTag.WEEKLY
    }

    companion object {
        private val HEADER_DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yy", Locale.US)
    }
}
