package com.schwabquant.app.data.remote.api

import com.schwabquant.app.data.remote.dto.TokenResponseDto
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Schwab OAuth 2.0 token endpoints per SCHWAB_API_REFERENCE.md Section 1.
 */
interface SchwabAuthApi {

    @FormUrlEncoded
    @POST("v1/oauth/token")
    suspend fun exchangeAuthorizationCode(
        @Header("Authorization") basicAuth: String,
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String
    ): TokenResponseDto

    @FormUrlEncoded
    @POST("v1/oauth/token")
    suspend fun refreshToken(
        @Header("Authorization") basicAuth: String,
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("refresh_token") refreshToken: String
    ): TokenResponseDto
}
