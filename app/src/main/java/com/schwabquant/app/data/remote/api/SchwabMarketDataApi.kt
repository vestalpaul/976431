package com.schwabquant.app.data.remote.api

import com.schwabquant.app.data.remote.dto.OptionChainResponseDto
import com.schwabquant.app.data.remote.dto.PriceHistoryResponseDto
import com.schwabquant.app.data.remote.dto.QuoteEntryDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Schwab Market Data API v1 REST endpoints per SCHWAB_API_REFERENCE.md Section 2.
 * Base URL: https://api.schwabapi.com/marketdata/v1/
 */
interface SchwabMarketDataApi {

    @GET("quotes")
    suspend fun getQuotes(
        @Query("symbols") symbols: String,
        @Query("fields") fields: String = "quote,fundamental,reference"
    ): Map<String, QuoteEntryDto>

    @GET("chains")
    suspend fun getOptionChain(
        @Query("symbol") symbol: String,
        @Query("contractType") contractType: String = "ALL",
        @Query("includeUnderlyingQuote") includeUnderlyingQuote: Boolean = true,
        @Query("strategy") strategy: String = "SINGLE",
        @Query("strikeCount") strikeCount: Int = 80
    ): OptionChainResponseDto

    @GET("pricehistory")
    suspend fun getPriceHistory(
        @Query("symbol") symbol: String,
        @Query("periodType") periodType: String = "year",
        @Query("period") period: Int = 1,
        @Query("frequencyType") frequencyType: String = "daily",
        @Query("frequency") frequency: Int = 1,
        @Query("endDate") endDate: Long? = null,
        @Query("needExtendedHoursData") needExtendedHoursData: Boolean? = null
    ): PriceHistoryResponseDto
}
