package com.schwabquant.app.data.repository

import com.schwabquant.app.core.quant.BetaWeightingCalculator
import com.schwabquant.app.core.quant.BlackScholesEngine
import com.schwabquant.app.core.quant.OccSymbolFormatter
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.StoredAuthSnapshot
import com.schwabquant.app.data.local.dao.ShadowLedgerDao
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.dao.WatchlistDao
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.api.SchwabMarketDataApi
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import com.schwabquant.app.domain.model.AssetCategory
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.math.BigDecimal

class PortfolioRepositoryImplTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses Schwab accounts and quotes with float ints, short option signs, and BS fallback`() = runTest {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        val traderApi = retrofit.create(SchwabTraderApi::class.java)
        val marketDataApi = retrofit.create(SchwabMarketDataApi::class.java)

        val accountJson = """
            {
              "securitiesAccount": {
                "accountNumber": "98765432",
                "roundTrips": 1.0,
                "currentBalances": {
                  "liquidationValue": 25000.50,
                  "cashBalance": 4200.25,
                  "settledFunds": 4000.00,
                  "buyingPower": 12000.00,
                  "availableFunds": 4200.25
                },
                "positions": [
                  {
                    "longQuantity": 1.0,
                    "shortQuantity": 0.0,
                    "averagePrice": 46.00,
                    "marketValue": 4820.00,
                    "longOpenProfitLoss": 220.00,
                    "currentDayProfitLoss": 55.00,
                    "instrument": {
                      "symbol": "IWM   280121C00190000",
                      "underlyingSymbol": "IWM",
                      "assetType": "OPTION",
                      "putCall": "CALL"
                    }
                  },
                  {
                    "longQuantity": 0.0,
                    "shortQuantity": 1.0,
                    "averagePrice": 3.10,
                    "marketValue": -245.00,
                    "shortOpenProfitLoss": 65.00,
                    "currentDayProfitLoss": 12.00,
                    "instrument": {
                      "symbol": "IWM   261218C00235000",
                      "underlyingSymbol": "IWM",
                      "assetType": "OPTION",
                      "putCall": "CALL"
                    }
                  },
                  {
                    "longQuantity": 16.0,
                    "shortQuantity": 0.0,
                    "averagePrice": 260.00,
                    "marketValue": 4320.00,
                    "longOpenProfitLoss": 160.00,
                    "currentDayProfitLoss": 32.00,
                    "instrument": {
                      "symbol": "GOOG",
                      "assetType": "EQUITY"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        // Notice float-formatted ints (totalVolume: 15230.0, openInterest: 4120.0) and missing Greeks on short option
        val quotesJson = """
            {
              "SPY": {
                "symbol": "SPY",
                "quote": { "mark": 585.00, "lastPrice": 585.00, "totalVolume": 52000000.0 },
                "fundamental": { "beta": 1.0, "divYield": 1.25 }
              },
              "IWM": {
                "symbol": "IWM",
                "quote": { "mark": 224.00, "lastPrice": 224.00, "totalVolume": 15230.0 },
                "fundamental": { "beta": 1.15, "divYield": 1.18, "divAmount": 0.65, "divDate": "2026-12-20 00:00:00.000" }
              },
              "GOOG": {
                "symbol": "GOOG",
                "quote": { "mark": 270.00, "lastPrice": 270.00 },
                "fundamental": { "beta": 1.08, "divYield": 0.30 }
              },
              "IWM   280121C00190000": {
                "symbol": "IWM   280121C00190000",
                "quote": {
                  "mark": 48.20,
                  "volatility": 24.50,
                  "openInterest": 4120.0,
                  "delta": 0.78,
                  "gamma": 0.006,
                  "theta": -0.025,
                  "vega": 0.85
                }
              },
              "IWM   261218C00235000": {
                "symbol": "IWM   261218C00235000",
                "quote": {
                  "mark": 2.45,
                  "volatility": 22.00,
                  "openInterest": 1850.0
                }
              }
            }
        """.trimIndent()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/accounts/hash-123") ->
                        MockResponse().setResponseCode(200).setBody(accountJson)
                    path.startsWith("/quotes") ->
                        MockResponse().setResponseCode(200).setBody(quotesJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val bs = BlackScholesEngine()
        val repo = PortfolioRepositoryImpl(
            traderApi = traderApi,
            marketDataRepository = MarketDataRepositoryImpl(
                marketDataApi = marketDataApi,
                watchlistDao = mockk<WatchlistDao>(relaxed = true)
            ),
            shadowLedgerDao = mockk<ShadowLedgerDao>(relaxed = true),
            washSaleDao = mockk<WashSaleDao>(relaxed = true),
            credentialVault = FakeVault("hash-123"),
            preferences = mockk<AppPreferencesDataStore>(relaxed = true),
            blackScholesEngine = bs,
            betaWeightingCalculator = BetaWeightingCalculator(bs),
            occSymbolFormatter = OccSymbolFormatter(),
            nowProvider = { 1_795_000_000_000L } // Nov 2026
        )

        val result = repo.refreshLivePortfolio("hash-123")
        assertTrue(result.isSuccess, "Expected refreshLivePortfolio success: ${result.exceptionOrNull()}")
        val snapshot = result.getOrThrow()

        assertEquals("••••5432", snapshot.accountSummary.maskedAccountNumber)
        assertEquals(3, snapshot.positions.size)

        val shortCall = snapshot.positions.first { it.symbol == "IWM   261218C00235000" }
        assertEquals(0, BigDecimal("-1.0").compareTo(shortCall.quantity))
        assertEquals(2.45, shortCall.markPrice.toDouble(), 1e-4)
        assertTrue(shortCall.greeksFromBlackScholesFallback, "Missing live Greeks must trigger BS fallback")
        assertNotNull(shortCall.delta)
        assertTrue(shortCall.delta!! > 0.0 && shortCall.delta!! < 0.5)
        assertEquals(0.22, shortCall.impliedVolatility!!, 1e-4)
        assertEquals(2.45, shortCall.extrinsicValue!!, 1e-4)

        val longLeaps = snapshot.positions.first { it.symbol == "IWM   280121C00190000" }
        assertEquals(false, longLeaps.greeksFromBlackScholesFallback)
        assertEquals(0.78, longLeaps.delta!!, 1e-4)
        // Intrinsic = 224 - 190 = 34.0; mark = 48.20 -> extrinsic = 14.20
        assertEquals(14.20, longLeaps.extrinsicValue!!, 1e-4)

        val goog = snapshot.positions.first { it.symbol == "GOOG" }
        assertEquals(AssetCategory.EQUITY, goog.category)
        assertEquals(0, BigDecimal("16.0").compareTo(goog.quantity))

        assertTrue(snapshot.groups.isNotEmpty())
        assertTrue(snapshot.aggregatedGreeks.spyBetaWeightedDelta > 0.0)
    }

    @Test
    fun `MEDIUM-7 normalizes BRK slash B live equity positions and matches canonical dot B quote and group`() = runTest {
        val traderApi = io.mockk.mockk<com.schwabquant.app.data.remote.api.SchwabTraderApi>()
        val marketDataRepo = io.mockk.mockk<com.schwabquant.app.domain.repository.MarketDataRepository>()
        val blackScholes = com.schwabquant.app.core.quant.BlackScholesEngine()
        val repo = PortfolioRepositoryImpl(
            traderApi = traderApi,
            marketDataRepository = marketDataRepo,
            shadowLedgerDao = io.mockk.mockk(relaxed = true),
            washSaleDao = io.mockk.mockk(relaxed = true),
            credentialVault = FakeVault("hash-brk"),
            preferences = io.mockk.mockk(relaxed = true),
            blackScholesEngine = blackScholes,
            betaWeightingCalculator = com.schwabquant.app.core.quant.BetaWeightingCalculator(blackScholes),
            occSymbolFormatter = com.schwabquant.app.core.quant.OccSymbolFormatter()
        )

        io.mockk.coEvery { traderApi.getAccount("hash-brk", fields = "positions") } returns com.schwabquant.app.data.remote.dto.AccountWrapperDto(
            securitiesAccount = com.schwabquant.app.data.remote.dto.SecuritiesAccountDto(
                accountNumber = "12345678",
                type = "MARGIN",
                currentBalances = com.schwabquant.app.data.remote.dto.BalancesDto(
                    liquidationValue = 100_000.0,
                    cashBalance = 50_000.0,
                    settledFunds = 50_000.0,
                    buyingPower = 100_000.0
                ),
                positions = listOf(
                    com.schwabquant.app.data.remote.dto.PositionDto(
                        longQuantity = 10.0,
                        averagePrice = 400.0,
                        marketValue = 4_200.0,
                        longOpenProfitLoss = 200.0,
                        currentDayProfitLoss = 15.0,
                        currentDayProfitLossPercentage = 0.35,
                        instrument = com.schwabquant.app.data.remote.dto.InstrumentDto(
                            symbol = "BRK/B",
                            underlyingSymbol = "BRK/B",
                            assetType = "EQUITY"
                        )
                    )
                )
            )
        )
        io.mockk.coEvery { marketDataRepo.getQuotes(any(), any()) } returns Result.success(
            mapOf(
                "BRK.B" to com.schwabquant.app.data.remote.dto.QuoteEntryDto(
                    symbol = "BRK.B",
                    quote = com.schwabquant.app.data.remote.dto.QuoteDetailDto(
                        mark = 420.0,
                        lastPrice = 420.0,
                        bidPrice = 419.90,
                        askPrice = 420.10
                    ),
                    fundamental = com.schwabquant.app.data.remote.dto.FundamentalDto(beta = 0.85)
                )
            )
        )

        val snapshot = repo.refreshLivePortfolio("hash-brk").getOrThrow()
        assertEquals(1, snapshot.positions.size)
        val pos = snapshot.positions.first()
        assertEquals("BRK.B", pos.symbol)
        assertEquals("BRK.B", pos.underlyingSymbol)
        assertEquals(0, BigDecimal("420.0").compareTo(pos.markPrice))
        assertEquals(0.85, pos.betaToSpy)
        assertEquals(1, snapshot.groups.size)
        assertEquals("BRK.B", snapshot.groups.first().underlyingSymbol)
    }

    @Test
    fun `MEDIUM-4 classifies CASH_EQUIVALENT FIXED_INCOME MUTUAL_FUND CURRENCY and INDEX with zero delta and no concentration flag`() = runTest {
        val traderApi = io.mockk.mockk<com.schwabquant.app.data.remote.api.SchwabTraderApi>()
        val marketDataRepo = io.mockk.mockk<com.schwabquant.app.domain.repository.MarketDataRepository>()
        val blackScholes = com.schwabquant.app.core.quant.BlackScholesEngine()
        val repo = PortfolioRepositoryImpl(
            traderApi = traderApi,
            marketDataRepository = marketDataRepo,
            shadowLedgerDao = io.mockk.mockk(relaxed = true),
            washSaleDao = io.mockk.mockk(relaxed = true),
            credentialVault = FakeVault("hash-assets"),
            preferences = io.mockk.mockk(relaxed = true),
            blackScholesEngine = blackScholes,
            betaWeightingCalculator = com.schwabquant.app.core.quant.BetaWeightingCalculator(blackScholes),
            occSymbolFormatter = com.schwabquant.app.core.quant.OccSymbolFormatter()
        )

        io.mockk.coEvery { traderApi.getAccount("hash-assets", fields = "positions") } returns com.schwabquant.app.data.remote.dto.AccountWrapperDto(
            securitiesAccount = com.schwabquant.app.data.remote.dto.SecuritiesAccountDto(
                accountNumber = "12345678",
                type = "MARGIN",
                currentBalances = com.schwabquant.app.data.remote.dto.BalancesDto(
                    liquidationValue = 100_000.0,
                    cashBalance = 5_000.0,
                    settledFunds = 5_000.0,
                    buyingPower = 100_000.0
                ),
                positions = listOf(
                    com.schwabquant.app.data.remote.dto.PositionDto(
                        longQuantity = 40_000.0,
                        averagePrice = 1.0,
                        marketValue = 40_000.0,
                        instrument = com.schwabquant.app.data.remote.dto.InstrumentDto(
                            symbol = "SWVXX",
                            assetType = "MUTUAL_FUND"
                        )
                    ),
                    com.schwabquant.app.data.remote.dto.PositionDto(
                        longQuantity = 30_000.0,
                        averagePrice = 1.0,
                        marketValue = 30_000.0,
                        instrument = com.schwabquant.app.data.remote.dto.InstrumentDto(
                            symbol = "TBILL_MM",
                            assetType = "CASH_EQUIVALENT"
                        )
                    ),
                    com.schwabquant.app.data.remote.dto.PositionDto(
                        longQuantity = 5.0,
                        averagePrice = 5800.0,
                        marketValue = 29_000.0,
                        instrument = com.schwabquant.app.data.remote.dto.InstrumentDto(
                            symbol = "\$SPX.X",
                            assetType = "INDEX"
                        )
                    )
                )
            )
        )
        io.mockk.coEvery { marketDataRepo.getQuotes(any(), any()) } returns Result.success(
            mapOf(
                "SPY" to com.schwabquant.app.data.remote.dto.QuoteEntryDto(
                    symbol = "SPY",
                    quote = com.schwabquant.app.data.remote.dto.QuoteDetailDto(mark = 585.0, lastPrice = 585.0),
                    fundamental = com.schwabquant.app.data.remote.dto.FundamentalDto(beta = 1.0)
                )
            )
        )

        val snapshot = repo.refreshLivePortfolio("hash-assets").getOrThrow()
        assertEquals(AssetCategory.CASH, PortfolioRepositoryImpl.classifyAssetCategory("CASH_EQUIVALENT"))
        assertEquals(AssetCategory.CASH, PortfolioRepositoryImpl.classifyAssetCategory("FIXED_INCOME"))
        assertEquals(AssetCategory.CASH, PortfolioRepositoryImpl.classifyAssetCategory("MUTUAL_FUND"))
        assertEquals(AssetCategory.CASH, PortfolioRepositoryImpl.classifyAssetCategory("CURRENCY"))
        assertEquals(AssetCategory.INDEX, PortfolioRepositoryImpl.classifyAssetCategory("INDEX"))
        assertEquals(AssetCategory.EQUITY, PortfolioRepositoryImpl.classifyAssetCategory("COLLECTIVE_INVESTMENT"))

        val swvxx = snapshot.positions.first { it.symbol == "SWVXX" }
        val tbill = snapshot.positions.first { it.symbol == "TBILL_MM" }
        val spx = snapshot.positions.first { it.symbol == "\$SPX.X" }
        assertEquals(AssetCategory.CASH, swvxx.category)
        assertEquals(AssetCategory.CASH, tbill.category)
        assertEquals(AssetCategory.INDEX, spx.category)
        assertEquals(0.0, swvxx.delta!!, 1e-9)
        assertEquals(0.0, tbill.betaToSpy!!, 1e-9)
        assertEquals(0.0, spx.delta!!, 1e-9)
        assertEquals(0.0, snapshot.aggregatedGreeks.spyBetaWeightedDelta, 1e-9)
        assertTrue(
            snapshot.portfolioAnomalies.none { it.type == com.schwabquant.app.domain.model.PortfolioAnomalyType.CONCENTRATION_OVER_25_PCT },
            "CASH & INDEX groups above 25% weight must not emit CONCENTRATION_OVER_25_PCT"
        )
    }

    private class FakeVault(private val hash: String) : CredentialVault {
        private val flow = MutableStateFlow(
            StoredAuthSnapshot(
                hasAppCredentials = true,
                hasAccessToken = true,
                hasRefreshToken = true,
                accessTokenExpiresAtEpochMs = Long.MAX_VALUE,
                refreshTokenIssuedAtEpochMs = 1L,
                primaryAccountHash = hash,
                maskedAccountNumber = "••••5432",
                callbackUrl = CredentialVault.DEFAULT_CALLBACK_URL
            )
        )

        override val authSnapshotFlow: StateFlow<StoredAuthSnapshot> = flow
        override fun saveAppCredentials(appKey: String, appSecret: String, callbackUrl: String) {}
        override fun getAppKey(): String = "k"
        override fun getAppSecret(): String = "s"
        override fun getCallbackUrl(): String = CredentialVault.DEFAULT_CALLBACK_URL
        override fun saveInitialOAuthGrant(
            accessToken: String,
            refreshToken: String,
            accessExpiresInSeconds: Long,
            nowEpochMs: Long
        ) {}

        override fun saveRefreshedAccessToken(
            accessToken: String,
            rotatedRefreshToken: String?,
            accessExpiresInSeconds: Long,
            nowEpochMs: Long
        ) {}

        override fun getAccessToken(): String = "t"
        override fun getRefreshToken(): String = "r"
        override fun getAccessTokenExpiresAtEpochMs(): Long = Long.MAX_VALUE
        override fun getRefreshTokenIssuedAtEpochMs(): Long = 1L
        override fun savePrimaryAccount(accountHash: String, maskedAccountNumber: String?) {}
        override fun getPrimaryAccountHash(): String = hash
        override fun getMaskedAccountNumber(): String = "••••5432"
        override fun isVaultCorrupted(): Boolean = false
        override fun clearTokensOnly() {}
        override fun wipeAllCredentials() {}
    }
}
