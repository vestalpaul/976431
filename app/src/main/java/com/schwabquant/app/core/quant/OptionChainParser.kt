package com.schwabquant.app.core.quant

import com.schwabquant.app.data.remote.dto.OptionChainResponseDto
import com.schwabquant.app.data.remote.dto.OptionContractDto
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

data class ParsedOptionContract(
    val symbol: String,
    val isCall: Boolean,
    val strike: Double,
    val expirationDate: LocalDate,
    val dte: Int,
    val bid: Double,
    val ask: Double,
    val mid: Double,
    val last: Double,
    val openInterest: Int,
    val volume: Int,
    val iv: Double,
    val delta: Double,
    val gamma: Double,
    val thetaPerDay: Double,
    val vegaPer1Pct: Double,
    val rhoPer1Pct: Double,
    val intrinsicValue: Double,
    val extrinsicValue: Double,
    val spreadWidthPct: Double,
    val greeksFromBlackScholesFallback: Boolean
)

data class ParsedOptionChain(
    val symbol: String,
    val underlyingPrice: Double,
    val chainVolatility: Double?,
    val riskFreeRate: Double,
    val contracts: List<ParsedOptionContract>,
    val atm30DayIv: Double?,
    val fetchedAtEpochMs: Long
) {
    val calls: List<ParsedOptionContract>
        get() = contracts.filter { it.isCall }

    val puts: List<ParsedOptionContract>
        get() = contracts.filter { !it.isCall }
}

/**
 * Pure-JVM parser converting nested Schwab /marketdata/v1/chains payloads into strongly typed,
 * sentinel-sanitized contracts with automatic Black-Scholes-Merton IV/Greeks fallback.
 */
@Singleton
class OptionChainParser @Inject constructor(
    private val blackScholesEngine: BlackScholesEngine
) {

    constructor() : this(BlackScholesEngine())

    fun parseExpirationKey(rawKey: String): Pair<LocalDate, Int>? {
        val parts = rawKey.trim().split(":")
        if (parts.isEmpty()) return null
        val dateStr = parts[0].trim()
        if (dateStr.length < 10) return null
        val date = runCatching { LocalDate.parse(dateStr.substring(0, 10)) }.getOrNull() ?: return null
        val dte = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return date to dte
    }

    fun parse(
        dto: OptionChainResponseDto,
        divYield: Double = 0.0,
        fetchedAtEpochMs: Long = System.currentTimeMillis()
    ): ParsedOptionChain {
        val spot = listOfNotNull(
            dto.underlyingPrice,
            dto.underlying?.mark,
            dto.underlying?.last,
            dto.underlying?.close,
            dto.underlying?.bid,
            dto.underlying?.ask
        ).firstOrNull { it.isFinite() && it > 0.0 } ?: 0.0

        val riskFreeRate = dto.interestRate
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { raw -> if (raw > 0.5) raw / 100.0 else raw }
            ?: BlackScholesEngine.DEFAULT_RISK_FREE_RATE

        val chainVol = dto.volatility
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { raw -> if (raw > 3.0) raw / 100.0 else raw }

        val parsed = ArrayList<ParsedOptionContract>(256)

        parseSide(
            expDateMap = dto.callExpDateMap,
            isCall = true,
            spot = spot,
            riskFreeRate = riskFreeRate,
            divYield = divYield,
            chainVolatility = chainVol,
            out = parsed
        )
        parseSide(
            expDateMap = dto.putExpDateMap,
            isCall = false,
            spot = spot,
            riskFreeRate = riskFreeRate,
            divYield = divYield,
            chainVolatility = chainVol,
            out = parsed
        )

        val atm30Iv = computeAtm30DayIv(parsed, spot, chainVol)
        val cleanSymbol = SchwabSymbolNormalizer.toDisplaySymbol(dto.symbol.orEmpty())

        return ParsedOptionChain(
            symbol = cleanSymbol,
            underlyingPrice = spot,
            chainVolatility = chainVol,
            riskFreeRate = riskFreeRate,
            contracts = parsed.sortedWith(
                compareBy<ParsedOptionContract> { it.dte }
                    .thenBy { !it.isCall }
                    .thenBy { it.strike }
            ),
            atm30DayIv = atm30Iv,
            fetchedAtEpochMs = fetchedAtEpochMs
        )
    }

    private fun parseSide(
        expDateMap: Map<String, Map<String, List<OptionContractDto>>>,
        isCall: Boolean,
        spot: Double,
        riskFreeRate: Double,
        divYield: Double,
        chainVolatility: Double?,
        out: MutableList<ParsedOptionContract>
    ) {
        expDateMap.forEach { (expKey, strikeMap) ->
            val parsedExp = parseExpirationKey(expKey) ?: return@forEach
            val (expDate, keyDte) = parsedExp

            strikeMap.forEach { (strikeKey, contractDtos) ->
                val mapStrike = strikeKey.trim().toDoubleOrNull()
                contractDtos.forEach { raw ->
                    val contract = buildContract(
                        raw = raw,
                        isCall = isCall,
                        expDate = expDate,
                        keyDte = keyDte,
                        mapStrike = mapStrike,
                        spot = spot,
                        riskFreeRate = riskFreeRate,
                        divYield = divYield,
                        chainVolatility = chainVolatility
                    )
                    if (contract != null) {
                        out.add(contract)
                    }
                }
            }
        }
    }

    private fun buildContract(
        raw: OptionContractDto,
        isCall: Boolean,
        expDate: LocalDate,
        keyDte: Int,
        mapStrike: Double?,
        spot: Double,
        riskFreeRate: Double,
        divYield: Double,
        chainVolatility: Double?
    ): ParsedOptionContract? {
        val strike = sanitizePositive(raw.strikePrice) ?: mapStrike?.takeIf { it > 0.0 } ?: return null
        val dte = raw.daysToExpiration?.takeIf { it.isFinite() }?.toInt() ?: keyDte
        if (dte <= 0 || strike <= 0.0) return null

        val bid = sanitizeNonNegative(raw.bid) ?: 0.0
        val ask = sanitizeNonNegative(raw.ask) ?: 0.0
        val last = sanitizeNonNegative(raw.last) ?: 0.0
        val mark = sanitizeNonNegative(raw.mark)

        val mid = when {
            mark != null && mark > 0.0 -> mark
            bid > 0.0 && ask > 0.0 && ask >= bid -> (bid + ask) * 0.5
            ask > 0.0 && bid == 0.0 -> ask * 0.5
            last > 0.0 -> last
            else -> 0.0
        }

        val spreadWidthPct = when {
            mid > 0.0 && ask >= bid && (ask > 0.0 || bid > 0.0) -> (ask - bid) / mid
            else -> 1.0
        }

        val openInterest = sanitizeNonNegative(raw.openInterest)?.toInt() ?: 0
        val volume = sanitizeNonNegative(raw.totalVolume)?.toInt() ?: 0
        val timeYears = dte / 365.0

        // Schwab frequently emits volatility = 0.0 or -999.0 outside regular trading hours;
        // treat <= 0.0 identically to null so BlackScholesEngine solves IV from mid.
        val rawIvPct = raw.volatility?.takeIf { it.isFinite() && it > 0.0 }
        val liveIvDecimal = rawIvPct?.let { it / 100.0 }

        val resolvedIv = liveIvDecimal
            ?: (if (spot > 0.0 && mid > 0.0) {
                blackScholesEngine.impliedVolatility(
                    isCall = isCall,
                    marketPrice = mid,
                    spot = spot,
                    strike = strike,
                    timeYears = timeYears,
                    rate = riskFreeRate,
                    divYield = divYield
                )
            } else {
                null
            })
            ?: chainVolatility
            ?: FALLBACK_DEFAULT_IV

        val liveDelta = raw.delta?.takeIf { d ->
            d.isFinite() && if (isCall) d in 0.0..1.005 else d in -1.005..0.0
        }
        val liveGamma = raw.gamma?.takeIf { it.isFinite() && it >= 0.0 }
        val liveTheta = raw.theta?.takeIf { it.isFinite() && it > SCHWAB_SENTINEL_CUTOFF }
        val liveVega = raw.vega?.takeIf { it.isFinite() && it >= 0.0 }
        val liveRho = raw.rho?.takeIf { it.isFinite() && it > SCHWAB_SENTINEL_CUTOFF }

        val needsFallback = liveIvDecimal == null ||
            liveDelta == null ||
            liveGamma == null ||
            liveTheta == null ||
            liveVega == null

        val bs = if (needsFallback && spot > 0.0) {
            blackScholesEngine.greeks(
                isCall = isCall,
                spot = spot,
                strike = strike,
                timeYears = timeYears,
                rate = riskFreeRate,
                divYield = divYield,
                iv = resolvedIv
            )
        } else {
            null
        }

        val finalMid = if (mid > 0.0) mid else (bs?.price ?: 0.0)
        val intrinsic = if (spot > 0.0) {
            if (isCall) max(0.0, spot - strike) else max(0.0, strike - spot)
        } else {
            sanitizeNonNegative(raw.intrinsicValue) ?: 0.0
        }
        val extrinsic = max(0.0, finalMid - intrinsic)

        return ParsedOptionContract(
            symbol = raw.symbol?.trim().orEmpty(),
            isCall = isCall,
            strike = strike,
            expirationDate = expDate,
            dte = dte,
            bid = bid,
            ask = ask,
            mid = finalMid,
            last = last,
            openInterest = openInterest,
            volume = volume,
            iv = resolvedIv,
            delta = (liveDelta ?: bs?.delta ?: 0.0).coerceIn(-1.0, 1.0),
            gamma = liveGamma ?: bs?.gamma ?: 0.0,
            thetaPerDay = liveTheta ?: bs?.thetaPerDay ?: 0.0,
            vegaPer1Pct = liveVega ?: bs?.vegaPer1PctIv ?: 0.0,
            rhoPer1Pct = liveRho ?: bs?.rhoPer1PctRate ?: 0.0,
            intrinsicValue = intrinsic,
            extrinsicValue = extrinsic,
            spreadWidthPct = spreadWidthPct,
            greeksFromBlackScholesFallback = needsFallback
        )
    }

    private fun computeAtm30DayIv(
        contracts: List<ParsedOptionContract>,
        spot: Double,
        chainVolatility: Double?
    ): Double? {
        if (contracts.isEmpty() || spot <= 0.0) return chainVolatility
        val byExp = contracts.filter { it.iv > 0.0 }.groupBy { it.expirationDate }
        if (byExp.isEmpty()) return chainVolatility

        val preferredPool = byExp.entries.filter { (_, list) -> list.first().dte in 15..60 }
        val pool = preferredPool.ifEmpty { byExp.entries.toList() }
        val targetExpContracts = pool.minByOrNull { abs(it.value.first().dte - 30) }?.value ?: return chainVolatility

        val atmCall = targetExpContracts.filter { it.isCall }.minByOrNull { abs(it.strike - spot) }
        val atmPut = targetExpContracts.filter { !it.isCall }.minByOrNull { abs(it.strike - spot) }

        val ivs = listOfNotNull(atmCall?.iv, atmPut?.iv).filter { it.isFinite() && it > 0.0 }
        return if (ivs.isNotEmpty()) ivs.average() else chainVolatility
    }

    private fun sanitizePositive(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it > 0.0 }

    private fun sanitizeNonNegative(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it >= 0.0 }

    companion object {
        private const val SCHWAB_SENTINEL_CUTOFF = -998.5
        private const val FALLBACK_DEFAULT_IV = 0.25
    }
}
