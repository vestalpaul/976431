package com.schwabquant.app.data.repository

import com.schwabquant.app.core.quant.ChartCandle
import com.schwabquant.app.core.quant.ChartTimeframe
import com.schwabquant.app.core.quant.ChartTimeframeMapper
import com.schwabquant.app.data.local.dao.WatchlistDao
import com.schwabquant.app.data.local.entity.WatchlistEntity
import com.schwabquant.app.data.remote.api.SchwabMarketDataApi
import com.schwabquant.app.data.remote.dto.OptionChainResponseDto
import com.schwabquant.app.data.remote.dto.PriceHistoryResponseDto
import com.schwabquant.app.data.remote.dto.QuoteEntryDto
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.LiquidUniverse
import com.schwabquant.app.domain.model.Watchlist
import com.schwabquant.app.domain.repository.MarketDataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarketDataRepositoryImpl(
    private val marketDataApi: SchwabMarketDataApi,
    private val watchlistDao: WatchlistDao,
    private val nowProvider: () -> Long = System::currentTimeMillis
) : MarketDataRepository {

    @Inject
    constructor(
        marketDataApi: SchwabMarketDataApi,
        watchlistDao: WatchlistDao
    ) : this(marketDataApi, watchlistDao, System::currentTimeMillis)

    private data class CachedEntry<T>(
        val data: T,
        val fetchedAtEpochMs: Long
    )

    private data class CachedChainEntry(
        val data: OptionChainResponseDto,
        val fetchedAtEpochMs: Long,
        val strikeCount: Int
    )

    private val seedMutex = Mutex()
    private val quoteCache = ConcurrentHashMap<String, CachedEntry<QuoteEntryDto>>()
    private val optionChainCache = ConcurrentHashMap<String, CachedChainEntry>()
    private val priceHistoryCache = ConcurrentHashMap<String, CachedEntry<PriceHistoryResponseDto>>()

    override fun observeWatchlistSymbols(): Flow<List<String>> =
        observeWatchlists().map { lists ->
            val target = lists.firstOrNull { it.isDefault } ?: lists.firstOrNull()
            target?.tickers ?: LiquidUniverse.CORE_25_LIQUID_TICKERS
        }

    override suspend fun addToWatchlist(
        symbol: String,
        category: AssetCategory,
        betaToSpy: Double
    ) {
        ensureDefaultWatchlistsSeeded()
        val defaultList = watchlistDao.findDefault(LiquidUniverse.CORE_25_NAME)
            ?: watchlistDao.getAllOnce().firstOrNull()
            ?: return
        addTickerToWatchlist(defaultList.id, symbol)
    }

    override suspend fun removeFromWatchlist(symbol: String) {
        val defaultList = watchlistDao.findDefault(LiquidUniverse.CORE_25_NAME)
            ?: watchlistDao.getAllOnce().firstOrNull()
            ?: return
        removeTickerFromWatchlist(defaultList.id, symbol)
    }

    override fun observeWatchlists(): Flow<List<Watchlist>> =
        watchlistDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun ensureDefaultWatchlistsSeeded() {
        seedMutex.withLock {
            // Safeguard: skip if default exists OR rows were already migrated by MIGRATION_2_3.
            val existingDefault = watchlistDao.findDefault(LiquidUniverse.CORE_25_NAME)
            if (existingDefault != null || watchlistDao.count() > 0) {
                return@withLock
            }
            val now = nowProvider()
            watchlistDao.insert(
                WatchlistEntity(
                    name = LiquidUniverse.CORE_25_NAME,
                    tickersCsv = LiquidUniverse.CORE_25_LIQUID_TICKERS.joinToString(","),
                    isDefault = true,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
        }
    }

    override suspend fun createWatchlist(
        name: String,
        tickers: List<String>
    ): Result<Long> = runCatching {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Watchlist name cannot be empty." }
        val existing = watchlistDao.getAllOnce()
        require(existing.none { it.name.equals(cleanName, ignoreCase = true) }) {
            "A watchlist named '$cleanName' already exists."
        }
        val cleanTickers = tickers
            .map { SchwabSymbolNormalizer.toDisplaySymbol(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        val now = nowProvider()
        watchlistDao.insert(
            WatchlistEntity(
                name = cleanName,
                tickersCsv = cleanTickers.joinToString(","),
                isDefault = false,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
    }

    override suspend fun renameWatchlist(watchlistId: Long, newName: String): Result<Unit> = runCatching {
        val cleanName = newName.trim()
        require(cleanName.isNotEmpty()) { "Watchlist name cannot be empty." }
        val entity = watchlistDao.getById(watchlistId)
            ?: error("Watchlist not found.")
        val others = watchlistDao.getAllOnce().filter { it.id != watchlistId }
        require(others.none { it.name.equals(cleanName, ignoreCase = true) }) {
            "A watchlist named '$cleanName' already exists."
        }
        watchlistDao.update(
            entity.copy(
                name = cleanName,
                updatedAtEpochMs = nowProvider()
            )
        )
    }

    override suspend fun deleteWatchlist(watchlistId: Long): Result<Unit> = runCatching {
        val entity = watchlistDao.getById(watchlistId) ?: error("Watchlist not found.")
        require(!entity.isDefault) {
            "Default watchlist '${entity.name}' cannot be deleted."
        }
        watchlistDao.deleteById(watchlistId)
    }

    override suspend fun addTickerToWatchlist(watchlistId: Long, ticker: String): Result<Unit> = runCatching {
        val cleanTicker = SchwabSymbolNormalizer.toDisplaySymbol(ticker)
        require(cleanTicker.isNotEmpty()) { "Ticker symbol cannot be empty." }
        val entity = watchlistDao.getById(watchlistId) ?: error("Watchlist not found.")
        val current = LiquidUniverse.parseTickersCsv(entity.tickersCsv).toMutableList()
        if (cleanTicker !in current) {
            current.add(cleanTicker)
            watchlistDao.update(
                entity.copy(
                    tickersCsv = current.joinToString(","),
                    updatedAtEpochMs = nowProvider()
                )
            )
        }
    }

    override suspend fun removeTickerFromWatchlist(watchlistId: Long, ticker: String): Result<Unit> = runCatching {
        val cleanTicker = SchwabSymbolNormalizer.toDisplaySymbol(ticker)
        val entity = watchlistDao.getById(watchlistId) ?: error("Watchlist not found.")
        val updated = LiquidUniverse.parseTickersCsv(entity.tickersCsv).filterNot { it == cleanTicker }
        watchlistDao.update(
            entity.copy(
                tickersCsv = updated.joinToString(","),
                updatedAtEpochMs = nowProvider()
            )
        )
    }

    override suspend fun getQuotes(
        symbols: List<String>,
        forceRefresh: Boolean
    ): Result<Map<String, QuoteEntryDto>> = runCatching {
        val originals = symbols
            .filter { it.isNotBlank() }
            .distinct()

        if (originals.isEmpty()) return@runCatching emptyMap()

        val apiToOriginals = LinkedHashMap<String, MutableList<String>>()
        originals.forEach { orig ->
            val apiSym = SchwabSymbolNormalizer.toSchwabMarketDataSymbol(orig)
            apiToOriginals.getOrPut(apiSym) { mutableListOf() }.add(orig)
        }

        val now = nowProvider()
        val result = linkedMapOf<String, QuoteEntryDto>()
        val toFetchApiSymbols = mutableListOf<String>()

        apiToOriginals.forEach { (apiSym, aliasList) ->
            val cached = if (!forceRefresh) quoteCache[apiSym] else null
            if (cached != null && (now - cached.fetchedAtEpochMs) < QUOTE_CACHE_TTL_MS) {
                result[apiSym] = cached.data
                aliasList.forEach { alias -> result[alias] = cached.data }
            } else {
                toFetchApiSymbols.add(apiSym)
            }
        }

        toFetchApiSymbols.chunked(MAX_QUOTE_BATCH_SIZE).forEach { batch ->
            val fetched = marketDataApi.getQuotes(batch.joinToString(","))
            val fetchedAt = nowProvider()
            fetched.forEach { (key, dto) ->
                val apiKey = SchwabSymbolNormalizer.toSchwabMarketDataSymbol(key)
                quoteCache[apiKey] = CachedEntry(dto, fetchedAt)
                result[key] = dto
                result[apiKey] = dto
                apiToOriginals[apiKey]?.forEach { alias -> result[alias] = dto }
                val displayAlias = SchwabSymbolNormalizer.toDisplaySymbol(key)
                result[displayAlias] = dto
            }
        }

        result
    }

    override suspend fun getOptionChain(
        symbol: String,
        forceRefresh: Boolean
    ): Result<OptionChainResponseDto> =
        fetchOptionChainInternal(
            symbol = symbol,
            minStrikeCount = SchwabSymbolNormalizer.strikeCountForSymbol(symbol),
            forceRefresh = forceRefresh
        )

    override suspend fun getSingleTickerOptionChain(
        symbol: String,
        forceRefresh: Boolean
    ): Result<OptionChainResponseDto> =
        fetchOptionChainInternal(
            symbol = symbol,
            minStrikeCount = SINGLE_TICKER_STRIKE_COUNT,
            forceRefresh = forceRefresh
        )

    private suspend fun fetchOptionChainInternal(
        symbol: String,
        minStrikeCount: Int,
        forceRefresh: Boolean
    ): Result<OptionChainResponseDto> = runCatching {
        val apiSym = SchwabSymbolNormalizer.toSchwabMarketDataSymbol(symbol)
        val now = nowProvider()
        val cached = if (!forceRefresh) optionChainCache[apiSym] else null
        if (cached != null &&
            cached.strikeCount >= minStrikeCount &&
            (now - cached.fetchedAtEpochMs) < CHAIN_CACHE_TTL_MS
        ) {
            return@runCatching cached.data
        }
        val requestedStrikes = maxOf(minStrikeCount, SchwabSymbolNormalizer.strikeCountForSymbol(apiSym))
        val fresh = marketDataApi.getOptionChain(
            symbol = apiSym,
            strikeCount = requestedStrikes
        )
        optionChainCache[apiSym] = CachedChainEntry(fresh, nowProvider(), requestedStrikes)
        fresh
    }

    override suspend fun getChartCandles(
        symbol: String,
        timeframe: ChartTimeframe,
        forceRefresh: Boolean
    ): Result<List<ChartCandle>> = runCatching {
        val query = ChartTimeframeMapper.toSchwabQuery(symbol, timeframe)
        val cacheKey = "${query.apiSymbol}|${query.periodType}|${query.period}|" +
            "${query.frequencyType}|${query.frequency}|ext=${query.needExtendedHoursData}"
        val ttlMs = if (timeframe.isIntraday) INTRADAY_CHART_CACHE_TTL_MS else DAILY_CHART_CACHE_TTL_MS
        val now = nowProvider()
        val cached = if (!forceRefresh) priceHistoryCache[cacheKey] else null
        val dto = if (cached != null && (now - cached.fetchedAtEpochMs) < ttlMs) {
            cached.data
        } else {
            val fresh = marketDataApi.getPriceHistory(
                symbol = query.apiSymbol,
                periodType = query.periodType,
                period = query.period,
                frequencyType = query.frequencyType,
                frequency = query.frequency,
                needExtendedHoursData = query.needExtendedHoursData
            )
            priceHistoryCache[cacheKey] = CachedEntry(fresh, nowProvider())
            fresh
        }
        val normalized = ChartTimeframeMapper.normalizeCandles(dto.candles)
        if (timeframe == ChartTimeframe.H1) {
            ChartTimeframeMapper.aggregateToHourlyCandles(normalized)
        } else {
            normalized
        }
    }

    override suspend fun getPriceHistory(
        symbol: String,
        periodType: String,
        period: Int,
        frequencyType: String,
        frequency: Int,
        forceRefresh: Boolean
    ): Result<PriceHistoryResponseDto> = runCatching {
        val apiSym = SchwabSymbolNormalizer.toSchwabMarketDataSymbol(symbol)
        val cacheKey = "$apiSym|$periodType|$period|$frequencyType|$frequency"
        val now = nowProvider()
        val cached = if (!forceRefresh) priceHistoryCache[cacheKey] else null
        if (cached != null && (now - cached.fetchedAtEpochMs) < PRICE_HISTORY_CACHE_TTL_MS) {
            return@runCatching cached.data
        }
        val fresh = marketDataApi.getPriceHistory(
            symbol = apiSym,
            periodType = periodType,
            period = period,
            frequencyType = frequencyType,
            frequency = frequency
        )
        priceHistoryCache[cacheKey] = CachedEntry(fresh, nowProvider())
        fresh
    }

    private fun WatchlistEntity.toDomain(): Watchlist = Watchlist(
        id = id,
        name = name,
        tickers = LiquidUniverse.parseTickersCsv(tickersCsv),
        isDefault = isDefault,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs
    )

    companion object {
        private const val QUOTE_CACHE_TTL_MS = 5_000L
        private const val CHAIN_CACHE_TTL_MS = 60_000L
        private const val PRICE_HISTORY_CACHE_TTL_MS = 15 * 60_000L
        private const val INTRADAY_CHART_CACHE_TTL_MS = 15_000L
        private const val DAILY_CHART_CACHE_TTL_MS = 5 * 60_000L
        private const val SINGLE_TICKER_STRIKE_COUNT = 100
        private const val MAX_QUOTE_BATCH_SIZE = 50
    }
}
