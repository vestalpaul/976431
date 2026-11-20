package com.schwabquant.app.data.remote.api

import com.schwabquant.app.data.remote.dto.AccountNumberDto
import com.schwabquant.app.data.remote.dto.AccountWrapperDto
import com.schwabquant.app.data.remote.dto.OrderRequestDto
import com.schwabquant.app.data.remote.dto.OrderResponseDto
import com.schwabquant.app.data.remote.dto.UserPreferenceDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Schwab Trader API v1 REST endpoints per SCHWAB_API_REFERENCE.md Section 2.
 * Base URL: https://api.schwabapi.com/trader/v1/
 */
interface SchwabTraderApi {

    @GET("accounts/accountNumbers")
    suspend fun getAccountNumbers(): List<AccountNumberDto>

    @GET("accounts/{accountHash}")
    suspend fun getAccount(
        @Path("accountHash") accountHash: String,
        @Query("fields") fields: String = "positions"
    ): AccountWrapperDto

    @GET("accounts/{accountHash}/orders")
    suspend fun getOrders(
        @Path("accountHash") accountHash: String,
        @Query("fromEnteredTime") fromEnteredTime: String,
        @Query("toEnteredTime") toEnteredTime: String
    ): List<OrderResponseDto>

    @GET("accounts/{accountHash}/orders/{orderId}")
    suspend fun getOrder(
        @Path("accountHash") accountHash: String,
        @Path("orderId") orderId: String
    ): OrderResponseDto

    @POST("accounts/{accountHash}/orders")
    suspend fun placeOrder(
        @Path("accountHash") accountHash: String,
        @Body order: OrderRequestDto
    ): Response<Unit>

    @DELETE("accounts/{accountHash}/orders/{orderId}")
    suspend fun cancelOrder(
        @Path("accountHash") accountHash: String,
        @Path("orderId") orderId: String
    ): Response<Unit>

    @GET("userPreference")
    suspend fun getUserPreference(): UserPreferenceDto
}
