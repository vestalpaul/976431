package com.schwabquant.app.core.quant

import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.data.local.dao.ShadowLedgerDao
import com.schwabquant.app.data.local.dao.WashSaleDao
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.data.remote.api.SchwabTraderApi
import com.schwabquant.app.data.remote.dto.AccountWrapperDto
import com.schwabquant.app.data.remote.dto.BalancesDto
import com.schwabquant.app.data.remote.dto.InstrumentDto
import com.schwabquant.app.data.remote.dto.PositionDto
import com.schwabquant.app.data.remote.dto.QuoteDetailDto
import com.schwabquant.app.data.remote.dto.QuoteEntryDto
import com.schwabquant.app.data.remote.dto.SecuritiesAccountDto
import com.schwabquant.app.data.repository.PortfolioRepositoryImpl
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.PortfolioAnomalyType
import com.schwabquant.app.domain.model.Position
import com.schwabquant.app.domain.repository.MarketDataRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.math.abs

class BetaWeightingCalculatorTest {

    private val blackScholes = BlackScholesEngine()
    private val calculator = BetaWeightingCalculator(blackScholes)

    @Test
    fun `computes multi-ticker equity and option SPY beta-weighted delta`() {
        val spySpot = 500.0

        // 100 shares of GOOG at $250 with beta 1.2 -> 100 * 1.2 * (250 / 500) = 60.0 SPY-delta
        val googShares = Position(
            symbol = "GOOG",
            underlyingSymbol = "GOOG",
            category = AssetCategory.EQUITY,
            quantity = BigDecimal("100"),
            averageCost = BigDecimal("240.00"),
            markPrice = BigDecimal("250.00"),
            marketValue = BigDecimal("25000.00"),
            unrealizedPnl = BigDecimal("1000.00"),
            unrealizedPnlPercent = 4.17,
            delta = 1.0,
            betaToSpy = 1.2,
            underlyingSpot = 250.0
        )

        // +2 IWM call contracts (delta 0.75) at IWM $200, beta 1.15 -> 2 * 0.75 * 100 * 1.15 * (200 / 500) = 69.0
        val iwmCall = Position(
            symbol = "IWM   280121C00180000",
            underlyingSymbol = "IWM",
            category = AssetCategory.OPTION,
            quantity = BigDecimal("2"),
            averageCost = BigDecimal("35.00"),
            markPrice = BigDecimal("40.00"),
            marketValue = BigDecimal("8000.00"),
            unrealizedPnl = BigDecimal("1000.00"),
            unrealizedPnlPercent = 14.29,
            delta = 0.75,
            betaToSpy = 1.15,
            isCall = true,
            strike = 180.0,
            daysToExpiration = 400,
            underlyingSpot = 200.0,
            impliedVolatility = 0.25
        )

        val totalBwd = calculator.calculateSpyBetaWeightedDelta(listOf(googShares, iwmCall), spySpot)
        assertEquals(129.0, totalBwd, 1e-6)
    }

    @Test
    fun `computes 6-scenario stress matrix and models vol skew shock difference`() {
        val spySpot = 500.0
        val callMark = blackScholes.price(
            isCall = true,
            spot = 200.0,
            strike = 190.0,
            timeYears = 1.0,
            divYield = 0.0,
            iv = 0.25
        )
        val longCall = Position(
            symbol = "IWM   280121C00190000",
            underlyingSymbol = "IWM",
            category = AssetCategory.OPTION,
            quantity = BigDecimal("2"),
            averageCost = BigDecimal.valueOf(callMark),
            markPrice = BigDecimal.valueOf(callMark),
            marketValue = BigDecimal.valueOf(callMark * 200.0),
            unrealizedPnl = BigDecimal.ZERO,
            unrealizedPnlPercent = 0.0,
            delta = 0.68,
            betaToSpy = 1.10,
            isCall = true,
            strike = 190.0,
            daysToExpiration = 365,
            underlyingSpot = 200.0,
            impliedVolatility = 0.25
        )

        val withVolSkew = calculator.computeStressMatrix(
            positions = listOf(longCall),
            accountNetLiq = 10_000.0,
            spyPrice = spySpot,
            includeVolSkewShock = true
        )
        val withoutVolSkew = calculator.computeStressMatrix(
            positions = listOf(longCall),
            accountNetLiq = 10_000.0,
            spyPrice = spySpot,
            includeVolSkewShock = false
        )

        assertEquals(listOf(-15.0, -10.0, -5.0, 5.0, 10.0, 15.0), withVolSkew.map { it.spyShockPercent })

        // On -15% SPY shock (+8% IV expansion), long-vega call loses LESS with vol skew shock enabled
        val down15Skew = withVolSkew.first().portfolioDollarPnl
        val down15Flat = withoutVolSkew.first().portfolioDollarPnl
        assertTrue(down15Skew > down15Flat, "Expected vol expansion to cushion long call on -15% crash")

        // On +15% SPY rally (-3% IV compression), long-vega call gains LESS with vol compression
        val up15Skew = withVolSkew.last().portfolioDollarPnl
        val up15Flat = withoutVolSkew.last().portfolioDollarPnl
        assertTrue(up15Skew < up15Flat)
    }

    @Test
    fun `betaScalingAndLowCorrelationGuard scales shocks and protects bond ETFs plus emits BETA_FALLBACK`() = runTest {
        val equityBeta1 = Position(
            symbol = "NVDA",
            underlyingSymbol = "NVDA",
            category = AssetCategory.EQUITY,
            quantity = BigDecimal("100"),
            averageCost = BigDecimal("140.0"),
            markPrice = BigDecimal("150.0"),
            marketValue = BigDecimal("15000.0"),
            unrealizedPnl = BigDecimal("1000.0"),
            unrealizedPnlPercent = 7.14,
            delta = 1.0,
            betaToSpy = 1.0,
            underlyingSpot = 150.0
        )
        val equityBeta2 = equityBeta1.copy(betaToSpy = 2.0)

        val bwd1 = calculator.calculateSpyBetaWeightedDelta(listOf(equityBeta1), spyPrice = 500.0)
        val bwd2 = calculator.calculateSpyBetaWeightedDelta(listOf(equityBeta2), spyPrice = 500.0)
        assertEquals(bwd1 * 2.0, bwd2, 1e-6)

        val shock1 = calculator.computeStressMatrix(listOf(equityBeta1), 20_000.0, 500.0, includeVolSkewShock = false)
            .first { it.spyShockPercent == 10.0 }.portfolioDollarPnl
        val shock2 = calculator.computeStressMatrix(listOf(equityBeta2), 20_000.0, 500.0, includeVolSkewShock = false)
            .first { it.spyShockPercent == 10.0 }.portfolioDollarPnl
        assertEquals(shock1 * 2.0, shock2, 1e-6)

        // Low-correlation bond ETF (SGOV): 90 days where SPY fluctuates ±1.2% and SGOV ticks +0.015%/day flatly
        val spyCloses = mutableListOf(500.0)
        val sgovCloses = mutableListOf(100.0)
        for (i in 1..100) {
            val spyRet = when (i % 4) {
                0 -> 0.012
                1 -> -0.008
                2 -> 0.015
                else -> -0.011
            }
            // Coprime period-5 daily accrual uncorrelated with SPY's period-4 cycle (R^2 < 0.05)
            val sgovRet = when (i % 5) {
                0 -> 0.00022
                1 -> 0.00018
                2 -> 0.00020
                3 -> 0.00017
                else -> 0.00021
            }
            spyCloses.add(spyCloses.last() * (1.0 + spyRet))
            sgovCloses.add(sgovCloses.last() * (1.0 + sgovRet))
        }
        val sgovBeta = calculator.computeRollingOlsBeta(sgovCloses, spyCloses, applyBlumeShrinkage = true)
        assertNotNull(sgovBeta)
        assertEquals(BetaSource.OLS_252D, sgovBeta!!.source)
        assertTrue((sgovBeta.rSquared ?: 1.0) < 0.10, "Expected R^2 < 0.10, got ${sgovBeta.rSquared}")
        assertTrue(
            abs(sgovBeta.beta) < 0.10,
            "Low-correlation SGOV ETF must NOT be shrunk toward 1.0 (~0.33), got ${sgovBeta.beta}"
        )

        // High-correlation stock with raw beta ~1.60 receives Blume shrinkage -> (2/3)*1.60 + 1/3 = 1.40
        val nvdaCloses = mutableListOf(100.0)
        for (i in 1 until spyCloses.size) {
            val spyRet = (spyCloses[i] - spyCloses[i - 1]) / spyCloses[i - 1]
            nvdaCloses.add(nvdaCloses.last() * (1.0 + 1.60 * spyRet))
        }
        val nvdaBeta = calculator.computeRollingOlsBeta(nvdaCloses, spyCloses, applyBlumeShrinkage = true)
        assertNotNull(nvdaBeta)
        assertEquals(1.40, nvdaBeta!!.beta, 1e-3)

        // Missing history AND missing fundamental.beta emits BetaSource.FALLBACK_1_0 + BETA_FALLBACK anomaly
        val traderApi = mockk<SchwabTraderApi>(relaxed = true)
        val marketDataRepo = mockk<MarketDataRepository>(relaxed = true)
        val vault = mockk<CredentialVault>(relaxed = true) {
            every { getPrimaryAccountHash() } returns "HASH_FALLBACK"
            every { getAccessToken() } returns "TOKEN"
        }
        coEvery { traderApi.getAccount("HASH_FALLBACK", fields = "positions") } returns AccountWrapperDto(
            securitiesAccount = SecuritiesAccountDto(
                accountNumber = "12345678",
                currentBalances = BalancesDto(liquidationValue = 20_000.0, cashBalance = 5_000.0),
                positions = listOf(
                    PositionDto(
                        longQuantity = 50.0,
                        averagePrice = 100.0,
                        marketValue = 5_000.0,
                        instrument = InstrumentDto(symbol = "PLTR", assetType = "EQUITY")
                    )
                )
            )
        )
        coEvery { marketDataRepo.getQuotes(any(), any()) } returns Result.success(
            mapOf(
                "SPY" to QuoteEntryDto("SPY", quote = QuoteDetailDto(mark = 585.0)),
                "PLTR" to QuoteEntryDto("PLTR", quote = QuoteDetailDto(mark = 100.0), fundamental = null)
            )
        )
        coEvery { marketDataRepo.getDailyClosesForTradingDate(any(), any(), any()) } returns
            Result.success(emptyList())

        val repo = PortfolioRepositoryImpl(
            traderApi = traderApi,
            marketDataRepository = marketDataRepo,
            shadowLedgerDao = mockk<ShadowLedgerDao>(relaxed = true),
            washSaleDao = mockk<WashSaleDao>(relaxed = true),
            credentialVault = vault,
            preferences = mockk<AppPreferencesDataStore>(relaxed = true),
            blackScholesEngine = blackScholes,
            betaWeightingCalculator = calculator,
            occSymbolFormatter = OccSymbolFormatter()
        )
        val snapshot = repo.refreshLivePortfolio("HASH_FALLBACK").getOrThrow()
        val pltrPos = snapshot.positions.single()
        assertEquals(1.0, pltrPos.betaToSpy!!, 1e-9)
        assertEquals(BetaSource.FALLBACK_1_0, pltrPos.betaSource)
        assertTrue(snapshot.portfolioAnomalies.any { it.type == PortfolioAnomalyType.BETA_FALLBACK && it.symbol == "PLTR" })
    }
}
