package com.schwabquant.app.data.remote.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate

/**
 * Defensive KSerializer for Schwab Market Data numeric fields that may emit:
 * - JSON sentinel numbers (-999.0) when illiquid or outside regular trading hours
 * - String literals ("NaN", "Infinity", "-Infinity")
 * - Float-formatted integer quantities (e.g., 4120.0 for openInterest)
 * Preserves legitimate negative Greeks (e.g., put delta -0.82, theta -0.05, rho -0.40).
 */
@OptIn(ExperimentalSerializationApi::class)
object SchwabSafeDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SchwabSafeDouble", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null || !value.isFinite()) {
            encoder.encodeNull()
        } else {
            encoder.encodeDouble(value)
        }
    }

    override fun deserialize(decoder: Decoder): Double? {
        val rawValue: Double? = if (decoder is JsonDecoder) {
            when (val element = decoder.decodeJsonElement()) {
                is JsonNull -> null
                is JsonPrimitive -> {
                    val content = element.content.trim()
                    if (content.isEmpty() ||
                        content.equals("null", ignoreCase = true) ||
                        content.equals("nan", ignoreCase = true) ||
                        content.equals("infinity", ignoreCase = true) ||
                        content.equals("-infinity", ignoreCase = true) ||
                        content.equals("+infinity", ignoreCase = true)
                    ) {
                        null
                    } else {
                        element.doubleOrNull ?: content.toDoubleOrNull()
                    }
                }
                else -> null
            }
        } else {
            decoder.decodeString().trim().toDoubleOrNull()
        }
        return rawValue?.takeIf { it.isFinite() && it > SCHWAB_SENTINEL_CUTOFF }
    }

    private const val SCHWAB_SENTINEL_CUTOFF = -998.5
}

/**
 * Schwab Market Data DTOs per SCHWAB_API_REFERENCE.md Section 2.
 * Numeric fields are typed defensively as Double? because Schwab occasionally emits floats
 * where schemas declare integer quantities (e.g., volume, openInterest).
 */
@Serializable
data class QuoteEntryDto(
    val symbol: String? = null,
    val assetMainType: String? = null,
    val quote: QuoteDetailDto? = null,
    val fundamental: FundamentalDto? = null,
    val reference: ReferenceDto? = null
)

@Serializable
data class QuoteDetailDto(
    val bidPrice: Double? = null,
    val askPrice: Double? = null,
    val lastPrice: Double? = null,
    val mark: Double? = null,
    val openPrice: Double? = null,
    val highPrice: Double? = null,
    val lowPrice: Double? = null,
    val closePrice: Double? = null,
    val netChange: Double? = null,
    val netPercentChange: Double? = null,
    val totalVolume: Double? = null,
    val bidSize: Double? = null,
    val askSize: Double? = null,
    val securityStatus: String? = null,
    val quoteTime: Long? = null,
    val tradeTime: Long? = null,
    val delta: Double? = null,
    val gamma: Double? = null,
    val theta: Double? = null,
    val vega: Double? = null,
    val rho: Double? = null,
    val volatility: Double? = null,
    val openInterest: Double? = null,
    val underlyingPrice: Double? = null,
    val strikePrice: Double? = null,
    val daysToExpiration: Double? = null,
    val timeValue: Double? = null,
    val intrinsicValue: Double? = null,
    val contractType: String? = null
)

@Serializable
data class FundamentalDto(
    val high52: Double? = null,
    val low52: Double? = null,
    val peRatio: Double? = null,
    val dividendYield: Double? = null,
    val divAmount: Double? = null,
    val divDate: String? = null,
    val divYield: Double? = null,
    val divPayDate: String? = null,
    val divPayAmount: Double? = null,
    val eps: Double? = null,
    val beta: Double? = null
)

@Serializable
data class ReferenceDto(
    val description: String? = null,
    val exchange: String? = null,
    val exchangeName: String? = null,
    val isShortable: Boolean? = null,
    val isHardToBorrow: Boolean? = null,
    val htbRate: Double? = null,
    val htbQuantity: Double? = null,
    val underlying: String? = null,
    val contractType: String? = null,
    val strikePrice: Double? = null
)

@Serializable
data class UnderlyingQuoteDto(
    val symbol: String? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val mark: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val last: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val bid: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val ask: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val close: Double? = null
)

@Serializable
data class OptionChainResponseDto(
    val symbol: String? = null,
    val status: String? = null,
    val isDelayed: Boolean? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val underlyingPrice: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val volatility: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val interestRate: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val numberOfContracts: Double? = null,
    val underlying: UnderlyingQuoteDto? = null,
    val callExpDateMap: Map<String, Map<String, List<OptionContractDto>>> = emptyMap(),
    val putExpDateMap: Map<String, Map<String, List<OptionContractDto>>> = emptyMap()
)

@Serializable
data class OptionContractDto(
    val putCall: String? = null,
    val symbol: String? = null,
    val description: String? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val bid: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val ask: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val last: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val mark: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val bidSize: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val askSize: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val totalVolume: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val openInterest: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val volatility: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val delta: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val gamma: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val theta: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val vega: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val rho: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val strikePrice: Double? = null,
    val expirationDate: String? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val daysToExpiration: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val timeValue: Double? = null,
    @Serializable(with = SchwabSafeDoubleSerializer::class)
    val intrinsicValue: Double? = null,
    val inTheMoney: Boolean? = null
)

@Serializable
data class PriceHistoryResponseDto(
    val symbol: String? = null,
    val empty: Boolean? = null,
    val candles: List<CandleDto> = emptyList()
)

@Serializable
data class CandleDto(
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val close: Double? = null,
    val volume: Double? = null,
    val datetime: Long? = null
)

object SchwabQuoteNormalizer {

    /**
     * Schwab /quotes emits volatility and divYield as percentages (e.g. 45.56 -> 0.4556).
     */
    fun percentToDecimal(rawPercent: Double?): Double? =
        rawPercent?.takeIf { it.isFinite() && it >= 0.0 }?.div(100.0)

    /**
     * Parses Schwab's divDate defensively (e.g. "2026-11-21 00:00:00.000", ISO-8601, or blank).
     */
    fun parseDivDate(rawDate: String?): LocalDate? {
        val trimmed = rawDate?.trim().orEmpty()
        if (trimmed.length < 10) return null
        val prefix = trimmed.substring(0, 10)
        return runCatching { LocalDate.parse(prefix) }.getOrNull()
    }
}

object SchwabSymbolNormalizer {
    private val SHARE_CLASS_DOT_REGEX = Regex("^([A-Z]{1,5})\\.([A-Z])$")
    private val SHARE_CLASS_SLASH_REGEX = Regex("^([A-Z]{1,5})/([A-Z])$")
    private val DENSE_INDEX_ETFS = setOf("SPY", "QQQ", "IWM")

    /**
     * Schwab Market Data endpoints expect `/` for share classes (`BRK.B` -> `BRK/B`),
     * while preserving 21-character OCC option symbols and index symbols (`$VIX`, `$VIX3M`).
     */
    fun toSchwabMarketDataSymbol(symbol: String): String {
        if (symbol.length == 21 && (symbol[12] == 'C' || symbol[12] == 'P')) {
            return symbol
        }
        val cleaned = symbol.trim().uppercase()
        return SHARE_CLASS_DOT_REGEX.replace(cleaned, "$1/$2")
    }

    /**
     * Canonical Schwab REST & Streamer wire symbol helper (`BRK.B` -> `BRK/B`).
     */
    fun toApiSymbol(symbol: String): String = toSchwabMarketDataSymbol(symbol)

    /**
     * Canonical UI/Domain display representation (`BRK/B` -> `BRK.B`).
     */
    fun toDisplaySymbol(symbol: String): String {
        val cleaned = symbol.trim().uppercase()
        return SHARE_CLASS_SLASH_REGEX.replace(cleaned, "$1.$2")
    }

    /**
     * Dynamic strikeCount ensuring dense $1-spaced ETF chains cover both 0.16-0.20 delta
     * Iron Condor wings and deep-ITM 0.85 delta LEAPS without truncating tails.
     */
    fun strikeCountForSymbol(symbol: String): Int {
        val clean = toDisplaySymbol(symbol)
        return if (clean in DENSE_INDEX_ETFS) 100 else 60
    }
}
