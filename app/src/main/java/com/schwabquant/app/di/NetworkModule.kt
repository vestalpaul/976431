package com.schwabquant.app.di

import com.schwabquant.app.BuildConfig
import com.schwabquant.app.core.network.SchwabAuthInterceptor
import com.schwabquant.app.core.network.SchwabTokenAuthenticator
import com.schwabquant.app.data.remote.api.SchwabAuthApi
import com.schwabquant.app.data.remote.api.SchwabMarketDataApi
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthOkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SchwabApiOkHttp

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamerOkHttp

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TraderRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MarketDataRetrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_ROOT_URL = "https://api.schwabapi.com/"
    private const val BASE_TRADER_URL = "https://api.schwabapi.com/trader/v1/"
    private const val BASE_MARKET_DATA_URL = "https://api.schwabapi.com/marketdata/v1/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideHttpLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }

    /**
     * Dedicated OkHttpClient for Schwab OAuth token exchange & refresh endpoints.
     * Deliberately omits SchwabAuthInterceptor and SchwabTokenAuthenticator to avoid recursion/deadlock.
     */
    @Provides
    @Singleton
    @AuthOkHttpClient
    fun provideAuthOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @SchwabApiOkHttp
    fun provideSchwabApiOkHttpClient(
        authInterceptor: SchwabAuthInterceptor,
        tokenAuthenticator: SchwabTokenAuthenticator,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @StreamerOkHttp
    fun provideStreamerOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Provides
    @Singleton
    @AuthRetrofit
    fun provideAuthRetrofit(
        @AuthOkHttpClient okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit = Retrofit.Builder()
        .baseUrl(BASE_ROOT_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    @TraderRetrofit
    fun provideTraderRetrofit(
        @SchwabApiOkHttp okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit = Retrofit.Builder()
        .baseUrl(BASE_TRADER_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    @MarketDataRetrofit
    fun provideMarketDataRetrofit(
        @SchwabApiOkHttp okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit = Retrofit.Builder()
        .baseUrl(BASE_MARKET_DATA_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideSchwabAuthApi(@AuthRetrofit retrofit: Retrofit): SchwabAuthApi =
        retrofit.create(SchwabAuthApi::class.java)

    @Provides
    @Singleton
    fun provideSchwabTraderApi(@TraderRetrofit retrofit: Retrofit): SchwabTraderApi =
        retrofit.create(SchwabTraderApi::class.java)

    @Provides
    @Singleton
    fun provideSchwabMarketDataApi(@MarketDataRetrofit retrofit: Retrofit): SchwabMarketDataApi =
        retrofit.create(SchwabMarketDataApi::class.java)
}
