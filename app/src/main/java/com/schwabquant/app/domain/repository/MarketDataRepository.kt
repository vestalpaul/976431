package com.schwabquant.app.domain.repository

import com.schwabquant.app.core.quant.ChartCandle
import com.schwabquant.app.core.quant.ChartTimeframe
import com.schwabquant.app.data.remote.dto.OptionChainResponseDto
import com.schwabquant.app.data.remote.dto.PriceHistoryResponseDto
import com.schwabquant.app.data.remote.dto.QuoteEntryDto
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.Watchlist
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface MarketDataRepository {
    fun observeWatchlistSymbols(): Flow<List<String>>
    suspend fun addToWatchlist(symbol: String, category: AssetCategory, betaToSpy: Double = 1.0)
    suspend fun removeFromWatchlist(symbol: String)

    fun observeWatchlists(): Flow<List<Watchlist>>
    suspend fun ensureDefaultWatchlistsSeeded()
    suspend fun createWatchlist(name: String, tickers: List<String> = emptyList()): Result<Long>
    suspend fun renameWatchlist(watchlistId: Long, newName: String): Result<Unit>
    suspend fun deleteWatchlist(watchlistId: Long): Result<Unit>
    suspend fun addTickerToWatchlist(watchlistId: Long, ticker: String): Result<Unit>
    suspend fun removeTickerFromWatchlist(watchlistId: Long, ticker: String): Result<Unit>

    suspend fun getQuotes(
        symbols: List<String>,
        forceRefresh: Boolean = false
    ): Result<Map<String, QuoteEntryDto>>

    suspend fun getOptionChain(
        symbol: String,
        forceRefresh: Boolean = false
    ): Result<OptionChainResponseDto>

    suspend fun getSingleTickerOptionChain(
        symbol: String,
        forceRefresh: Boolean = false
    ): Result<OptionChainResponseDto> = getOptionChain(symbol, forceRefresh)

    suspend fun getChartCandles(
        symbol: String,
        timeframe: ChartTimeframe,
        forceRefresh: Boolean = false
    ): Result<List<ChartCandle>> = Result.failure(UnsupportedOperationException("getChartCandles not implemented"))

    suspend fun getPriceHistory(
        symbol: String,
        periodType: String = "year",
        period: Int = 1,
        frequencyType: String = "daily",
        frequency: Int = 1,
        forceRefresh: Boolean = false
    ): Result<PriceHistoryResponseDto>

    suspend fun getDailyClosesForTradingDate(
        symbol: String,
        tradingDate: LocalDate,
        forceRefresh: Boolean = false
    ): Result<List<ChartCandle>> = getChartCandles(symbol, ChartTimeframe.D1, forceRefresh)
}
