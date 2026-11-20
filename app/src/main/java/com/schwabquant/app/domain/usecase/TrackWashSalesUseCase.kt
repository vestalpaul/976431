package com.schwabquant.app.domain.usecase

import com.schwabquant.app.core.quant.OccSymbolFormatter
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.entity.WashSaleTradeEntity
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import com.schwabquant.app.data.remote.dto.OrderResponseDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min

data class WashSaleLot(
    val id: Long,
    val underlyingSymbol: String,
    val symbol: String,
    val assetType: String,
    val instruction: String,
    val quantity: Int,
    val price: Double,
    val realizedPnl: Double?,
    val tradeTimestampEpochMs: Long,
    val source: String
)

data class WashSaleFlag(
    val lossTradeId: Long,
    val underlyingSymbol: String,
    val lossSymbol: String,
    val lossDate: LocalDate,
    val totalRealizedLoss: BigDecimal,
    val disallowedLossAmount: BigDecimal,
    val allowedLossAmount: BigDecimal,
    val matchedShareEquivalents: Int,
    val replacementLotSymbol: String,
    val replacementDate: LocalDate,
    val adjustedReplacementBasis: BigDecimal,
    val clearToRebuyDate: LocalDate,
    val isCrossAssetStockToOption: Boolean
)

/**
 * IRC § 1091 61-day Wash-Sale evaluation engine ([lossDate - 30d, lossDate + 30d],
 * safe on lossDate + 31d) with cross-asset equity-loss-to-call-option matching,
 * FIFO realized P/L calculation, and idempotent 65-day chunked Schwab /orders sync.
 */
class TrackWashSalesUseCase @Inject constructor(
    private val washSaleDao: WashSaleDao,
    private val traderApi: SchwabTraderApi,
    private val credentialVault: CredentialVault,
    private val occSymbolFormatter: OccSymbolFormatter
) {

    fun observeWashSaleFlags(
        zoneId: ZoneId = NY_ZONE
    ): Flow<List<WashSaleFlag>> = washSaleDao.observeAll().map { entities ->
        evaluateWashSales(
            trades = entities.map { it.toLot() },
            zoneId = zoneId
        )
    }

    suspend fun logManualTrade(
        underlyingSymbol: String,
        symbol: String,
        assetType: String,
        instruction: String,
        quantity: Int,
        price: Double,
        realizedPnl: Double?,
        tradeTimestampEpochMs: Long = System.currentTimeMillis()
    ): Long = washSaleDao.insert(
        WashSaleTradeEntity(
            underlyingSymbol = underlyingSymbol.trim().uppercase(),
            symbol = symbol.trim().uppercase(),
            assetType = assetType.trim().uppercase(),
            instruction = instruction.trim().uppercase(),
            quantity = quantity.coerceAtLeast(1),
            price = price,
            realizedPnl = realizedPnl,
            tradeTimestampEpochMs = tradeTimestampEpochMs,
            source = "MANUAL"
        )
    )

    suspend fun syncFilledOrdersFromSchwab(
        accountHash: String? = null,
        nowInstant: Instant = Instant.now()
    ): Result<Int> = runCatching {
        val hash = accountHash
            ?: credentialVault.getPrimaryAccountHash()
            ?: traderApi.getAccountNumbers().firstOrNull()?.also {
                credentialVault.savePrimaryAccount(it.hashValue, "••••${it.accountNumber.takeLast(4)}")
            }?.hashValue
            ?: error("No Schwab account hash available for order sync.")

        // Chunk 65-day lookback into two <= 35-day windows per Schwab API limits
        val midInstant = nowInstant.minusSeconds(32L * SECONDS_PER_DAY)
        val startInstant = nowInstant.minusSeconds(65L * SECONDS_PER_DAY)

        val window1 = traderApi.getOrders(
            accountHash = hash,
            fromEnteredTime = SCHWAB_ISO_UTC.format(startInstant),
            toEnteredTime = SCHWAB_ISO_UTC.format(midInstant)
        )
        val window2 = traderApi.getOrders(
            accountHash = hash,
            fromEnteredTime = SCHWAB_ISO_UTC.format(midInstant),
            toEnteredTime = SCHWAB_ISO_UTC.format(nowInstant)
        )

        val filledOrders = (window1 + window2)
            .distinctBy { it.orderId }
            .filter { it.status.equals("FILLED", ignoreCase = true) }

        var insertedCount = 0
        filledOrders.forEach { order ->
            val entities = mapFilledOrderToLots(order)
            entities.forEach { entity ->
                val extId = entity.externalOrderId
                if (extId != null && washSaleDao.countByExternalOrderId(extId) == 0) {
                    washSaleDao.insert(entity)
                    insertedCount++
                }
            }
        }
        insertedCount
    }

    fun evaluateWashSales(
        trades: List<WashSaleLot>,
        zoneId: ZoneId = NY_ZONE
    ): List<WashSaleFlag> {
        if (trades.isEmpty()) return emptyList()

        val sorted = trades.sortedWith(
            compareBy<WashSaleLot> { it.tradeTimestampEpochMs }.thenBy { it.id }
        )

        val fifoBuyQueues = mutableMapOf<String, ArrayDeque<FifoOpenLot>>()
        val computedPnlByTradeKey = mutableMapOf<Int, Double?>()
        val consumedBuyIndicesBySellKey = mutableMapOf<Int, MutableSet<Int>>()

        sorted.forEachIndexed { index, lot ->
            val isOption = lot.assetType.equals("OPTION", ignoreCase = true)
            val multiplier = if (isOption) OPTION_SHARES else 1
            val instruction = lot.instruction.uppercase()

            when (instruction) {
                "BUY", "BUY_TO_OPEN" -> {
                    val queue = fifoBuyQueues.getOrPut(lot.symbol) { ArrayDeque() }
                    queue.addLast(
                        FifoOpenLot(
                            sortIndex = index,
                            remainingQty = lot.quantity,
                            unitPrice = lot.price,
                            multiplier = multiplier
                        )
                    )
                    computedPnlByTradeKey[index] = lot.realizedPnl
                }

                "SELL", "SELL_TO_CLOSE" -> {
                    if (lot.realizedPnl != null) {
                        computedPnlByTradeKey[index] = lot.realizedPnl
                    } else {
                        val queue = fifoBuyQueues[lot.symbol]
                        var remainingToClose = lot.quantity
                        var matchedAny = false
                        var fifoPnl = 0.0
                        val consumedSet = mutableSetOf<Int>()
                        while (remainingToClose > 0 && queue != null && queue.isNotEmpty()) {
                            val head = queue.first()
                            val matched = min(remainingToClose, head.remainingQty)
                            fifoPnl += (lot.price - head.unitPrice) * matched * multiplier
                            head.remainingQty -= matched
                            remainingToClose -= matched
                            matchedAny = true
                            consumedSet += head.sortIndex
                            if (head.remainingQty <= 0) queue.removeFirst()
                        }
                        consumedBuyIndicesBySellKey[index] = consumedSet
                        computedPnlByTradeKey[index] = if (matchedAny && remainingToClose == 0) fifoPnl else null
                    }
                }

                else -> computedPnlByTradeKey[index] = lot.realizedPnl
            }
        }

        val replacementPool = sorted.mapIndexedNotNull { index, lot ->
            val instruction = lot.instruction.uppercase()
            if (instruction != "BUY" && instruction != "BUY_TO_OPEN") return@mapIndexedNotNull null

            val isOption = lot.assetType.equals("OPTION", ignoreCase = true)
            val parsedOption = if (isOption) occSymbolFormatter.parse(lot.symbol) else null
            // Stock-loss wash-sale replacement rules only trigger on Call option acquisitions (never Put)
            if (isOption && parsedOption?.isCall != true) return@mapIndexedNotNull null

            val multiplier = if (isOption) OPTION_SHARES else 1
            val lotDate = Instant.ofEpochMilli(lot.tradeTimestampEpochMs).atZone(zoneId).toLocalDate()
            MutableReplacementLot(
                sortIndex = index,
                lot = lot,
                date = lotDate,
                isOption = isOption,
                isCall = parsedOption?.isCall,
                multiplier = multiplier,
                remainingShareEquivalents = lot.quantity * multiplier
            )
        }

        val flags = mutableListOf<WashSaleFlag>()

        sorted.forEachIndexed { index, lossLot ->
            val instruction = lossLot.instruction.uppercase()
            if (instruction != "SELL" && instruction != "SELL_TO_CLOSE") return@forEachIndexed

            val realized = computedPnlByTradeKey[index] ?: return@forEachIndexed
            if (realized >= 0.0) return@forEachIndexed

            val lossIsOption = lossLot.assetType.equals("OPTION", ignoreCase = true)
            val lossParsedOption = if (lossIsOption) occSymbolFormatter.parse(lossLot.symbol) else null
            val lossMultiplier = if (lossIsOption) OPTION_SHARES else 1
            val totalLossShares = (lossLot.quantity * lossMultiplier).coerceAtLeast(1)
            val absLossTotal = abs(realized)
            val lossDate = Instant.ofEpochMilli(lossLot.tradeTimestampEpochMs).atZone(zoneId).toLocalDate()
            val windowStart = lossDate.minusDays(WASH_WINDOW_DAYS)
            val windowEnd = lossDate.plusDays(WASH_WINDOW_DAYS)
            val clearDate = lossDate.plusDays(WASH_WINDOW_DAYS + 1L)

            var remainingLossShares = totalLossShares
            val consumedBasisIndices = consumedBuyIndicesBySellKey[index].orEmpty()

            val candidates = replacementPool.filter { rep ->
                rep.sortIndex != index &&
                    rep.sortIndex !in consumedBasisIndices &&
                    rep.lot.id != lossLot.id &&
                    rep.remainingShareEquivalents > 0 &&
                    rep.lot.underlyingSymbol.equals(lossLot.underlyingSymbol, ignoreCase = true) &&
                    !rep.date.isBefore(windowStart) &&
                    !rep.date.isAfter(windowEnd) &&
                    isSubstantiallyIdenticalReplacement(
                        lossIsOption = lossIsOption,
                        lossIsCall = lossParsedOption?.isCall,
                        rep = rep
                    )
            }

            if (candidates.isEmpty()) return@forEachIndexed

            val matchedEntries = mutableListOf<Pair<MutableReplacementLot, Int>>()
            candidates.forEach { candidate ->
                if (remainingLossShares <= 0) return@forEach
                val matched = min(remainingLossShares, candidate.remainingShareEquivalents)
                if (matched > 0) {
                    candidate.remainingShareEquivalents -= matched
                    remainingLossShares -= matched
                    matchedEntries += candidate to matched
                }
            }

            val totalMatchedShares = totalLossShares - remainingLossShares
            val totalDisallowed = (absLossTotal * (totalMatchedShares.toDouble() / totalLossShares.toDouble()))
            val allowedLoss = (absLossTotal - totalDisallowed).coerceAtLeast(0.0)

            matchedEntries.forEach { (rep, matchedShares) ->
                val disallowedForRep = absLossTotal * (matchedShares.toDouble() / totalLossShares.toDouble())
                val repInitialCost = rep.lot.quantity * rep.lot.price * rep.multiplier
                val adjustedBasis = repInitialCost + disallowedForRep

                flags += WashSaleFlag(
                    lossTradeId = lossLot.id,
                    underlyingSymbol = lossLot.underlyingSymbol.uppercase(),
                    lossSymbol = lossLot.symbol,
                    lossDate = lossDate,
                    totalRealizedLoss = absLossTotal.toMoneyBd(),
                    disallowedLossAmount = disallowedForRep.toMoneyBd(),
                    allowedLossAmount = allowedLoss.toMoneyBd(),
                    matchedShareEquivalents = matchedShares,
                    replacementLotSymbol = rep.lot.symbol,
                    replacementDate = rep.date,
                    adjustedReplacementBasis = adjustedBasis.toMoneyBd(),
                    clearToRebuyDate = clearDate,
                    isCrossAssetStockToOption = !lossIsOption && rep.isOption
                )
            }
        }

        return flags.sortedByDescending { it.lossDate }
    }

    private fun isSubstantiallyIdenticalReplacement(
        lossIsOption: Boolean,
        lossIsCall: Boolean?,
        rep: MutableReplacementLot
    ): Boolean {
        if (!lossIsOption) {
            // Equity loss matches Equity BUY or Call Option BUY_TO_OPEN
            return !rep.isOption || rep.isCall == true
        }
        // Option loss matches Option BUY_TO_OPEN of the same Put/Call side
        return rep.isOption && (lossIsCall == null || rep.isCall == lossIsCall)
    }

    private fun mapFilledOrderToLots(order: OrderResponseDto): List<WashSaleTradeEntity> {
        val orderId = order.orderId?.toString() ?: return emptyList()
        val executionLeg = order.orderActivityCollection
            .flatMap { it.executionLegs }
            .firstOrNull()

        val rawTimestamp = executionLeg?.time ?: order.closeTime ?: order.enteredTime
        val timestampMs = parseSchwabTimestampMs(rawTimestamp) ?: System.currentTimeMillis()
        val fallbackPrice = executionLeg?.price ?: order.price ?: 0.0

        return order.orderLegCollection.mapIndexedNotNull { idx, leg ->
            val inst = leg.instrument ?: return@mapIndexedNotNull null
            val symbol = inst.symbol?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val instruction = leg.instruction?.uppercase() ?: return@mapIndexedNotNull null
            val assetType = inst.assetType?.uppercase() ?: "EQUITY"
            val qty = (leg.quantity ?: order.filledQuantity ?: order.quantity ?: 0.0).toInt()
            if (qty <= 0) return@mapIndexedNotNull null

            val parsedOcc = if (assetType == "OPTION") occSymbolFormatter.parse(symbol) else null
            val underlying = inst.underlyingSymbol?.takeIf { it.isNotBlank() }
                ?: parsedOcc?.root
                ?: symbol

            WashSaleTradeEntity(
                underlyingSymbol = underlying.uppercase(),
                symbol = symbol,
                assetType = assetType,
                instruction = instruction,
                quantity = qty,
                price = fallbackPrice,
                realizedPnl = null,
                tradeTimestampEpochMs = timestampMs,
                source = "SCHWAB_SYNC",
                externalOrderId = "${orderId}_${idx}_${symbol}"
            )
        }
    }

    private fun parseSchwabTimestampMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw.trim()).toEpochMilli() }.getOrNull()
    }

    private fun WashSaleTradeEntity.toLot(): WashSaleLot = WashSaleLot(
        id = id,
        underlyingSymbol = underlyingSymbol,
        symbol = symbol,
        assetType = assetType,
        instruction = instruction,
        quantity = quantity,
        price = price,
        realizedPnl = realizedPnl,
        tradeTimestampEpochMs = tradeTimestampEpochMs,
        source = source
    )

    private fun Double.toMoneyBd(): BigDecimal =
        BigDecimal.valueOf(this).setScale(2, RoundingMode.HALF_UP)

    private data class FifoOpenLot(
        val sortIndex: Int,
        var remainingQty: Int,
        val unitPrice: Double,
        val multiplier: Int
    )

    private data class MutableReplacementLot(
        val sortIndex: Int,
        val lot: WashSaleLot,
        val date: LocalDate,
        val isOption: Boolean,
        val isCall: Boolean?,
        val multiplier: Int,
        var remainingShareEquivalents: Int
    )

    companion object {
        private const val WASH_WINDOW_DAYS = 30L
        private const val OPTION_SHARES = 100
        private const val SECONDS_PER_DAY = 86_400L
        private val NY_ZONE: ZoneId = ZoneId.of("America/New_York")
        private val SCHWAB_ISO_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
