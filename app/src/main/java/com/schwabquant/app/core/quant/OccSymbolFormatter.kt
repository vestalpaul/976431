package com.schwabquant.app.core.quant

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import javax.inject.Inject
import javax.inject.Singleton

data class ParsedOccSymbol(
    val root: String,
    val expiration: LocalDate,
    val isCall: Boolean,
    val strike: BigDecimal
)

/**
 * Formats and parses Schwab 21-character OCC option symbols per SCHWAB_API_REFERENCE.md Section 3:
 * [6-char space-padded root][YYMMDD][C|P][8-digit strike = price * 1000, zero-padded]
 * Example: "NVDA  280121C00165000", "IWM   261218C00284000".
 */
@Singleton
class OccSymbolFormatter @Inject constructor() {

    fun format(
        root: String,
        expiration: LocalDate,
        isCall: Boolean,
        strike: BigDecimal
    ): String {
        val cleanRoot = root.trim().uppercase()
        require(ROOT_INPUT_REGEX.matches(cleanRoot)) {
            "OCC root symbol must be 1..$ROOT_WIDTH uppercase alphanumeric characters: '$root'"
        }
        require(strike > BigDecimal.ZERO) { "Strike price must be strictly positive: $strike" }

        val paddedRoot = cleanRoot.padEnd(ROOT_WIDTH, ' ')
        val datePart = expiration.format(OCC_DATE_FORMAT)
        val typeChar = if (isCall) 'C' else 'P'
        val scaledStrike = strike
            .multiply(STRIKE_MULTIPLIER)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
        require(scaledStrike > 0L) { "Scaled OCC strike must be strictly positive: $strike" }
        val strikePart = scaledStrike.toString().padStart(STRIKE_WIDTH, '0')
        require(strikePart.length == STRIKE_WIDTH && STRIKE_DIGITS_REGEX.matches(strikePart)) {
            "Strike exceeds 8-digit OCC width: $strike"
        }

        return "$paddedRoot$datePart$typeChar$strikePart"
    }

    fun parse(occSymbol: String): ParsedOccSymbol? {
        if (occSymbol.length != OCC_TOTAL_LENGTH) return null
        return runCatching {
            val rawRoot = occSymbol.substring(0, ROOT_WIDTH)
            if (!PADDED_ROOT_REGEX.matches(rawRoot)) return null
            val root = rawRoot.trimEnd(' ')
            if (root.isEmpty()) return null

            val datePart = occSymbol.substring(6, 12)
            if (!DATE_DIGITS_REGEX.matches(datePart)) return null
            val date = LocalDate.parse(datePart, STRICT_OCC_DATE_FORMAT)

            val isCall = when (occSymbol[12]) {
                'C' -> true
                'P' -> false
                else -> return null
            }

            val strikePart = occSymbol.substring(13, OCC_TOTAL_LENGTH)
            if (!STRIKE_DIGITS_REGEX.matches(strikePart)) return null
            val strikeMilli = strikePart.toLong()
            if (strikeMilli <= 0L) return null

            val strike = BigDecimal(strikeMilli).divide(STRIKE_MULTIPLIER, 3, RoundingMode.HALF_UP)
            ParsedOccSymbol(
                root = root,
                expiration = date,
                isCall = isCall,
                strike = strike
            )
        }.getOrNull()
    }

    companion object {
        const val OCC_TOTAL_LENGTH = 21
        const val ROOT_WIDTH = 6
        const val STRIKE_WIDTH = 8
        private val STRIKE_MULTIPLIER = BigDecimal(1000)
        private val ROOT_INPUT_REGEX = Regex("^[A-Z0-9]{1,6}$")
        private val PADDED_ROOT_REGEX = Regex("^[A-Z0-9]{1,6} {0,5}$")
        private val DATE_DIGITS_REGEX = Regex("^[0-9]{6}$")
        private val STRIKE_DIGITS_REGEX = Regex("^[0-9]{8}$")
        private val OCC_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd")
        private val STRICT_OCC_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuMMdd").withResolverStyle(ResolverStyle.STRICT)
    }
}
