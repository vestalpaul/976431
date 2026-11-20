package com.schwabquant.app.core.quant

import com.schwabquant.app.data.remote.dto.OptionChainResponseDto
import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class OptionChainParserTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val parser = OptionChainParser(BlackScholesEngine())

    @Test
    fun `decodes nested expDateMap, sanitizes NaN and -999 sentinels, and triggers BS fallback`() {
        val rawJson = """
            {
              "symbol": "IWM",
              "status": "SUCCESS",
              "underlyingPrice": 224.0,
              "volatility": 22.5,
              "interestRate": 4.5,
              "callExpDateMap": {
                "2028-01-21:429": {
                  "190.0": [
                    {
                      "putCall": "CALL",
                      "symbol": "IWM   280121C00190000",
                      "bid": 47.80,
                      "ask": 48.60,
                      "mark": 48.20,
                      "openInterest": 4120.0,
                      "totalVolume": 150.0,
                      "volatility": 23.4,
                      "delta": 0.82,
                      "gamma": 0.005,
                      "theta": -0.024,
                      "vega": 0.88,
                      "strikePrice": 190.0,
                      "daysToExpiration": 429.0
                    }
                  ]
                },
                "2026-12-24:35": {
                  "235.0": [
                    {
                      "putCall": "CALL",
                      "symbol": "IWM   261224C00235000",
                      "bid": 2.35,
                      "ask": 2.45,
                      "mark": 2.40,
                      "openInterest": 1850.0,
                      "totalVolume": 640.0,
                      "volatility": "NaN",
                      "delta": -999.0,
                      "gamma": -999.0,
                      "theta": -999.0,
                      "vega": "NaN",
                      "strikePrice": 235.0,
                      "daysToExpiration": 35.0
                    }
                  ],
                  "0.0": [
                    {
                      "putCall": "CALL",
                      "symbol": "BAD_STRIKE",
                      "bid": 1.0,
                      "ask": 1.2,
                      "strikePrice": 0.0,
                      "daysToExpiration": 35.0
                    }
                  ]
                },
                "2026-11-19:0": {
                  "225.0": [
                    {
                      "putCall": "CALL",
                      "symbol": "EXPIRED_TODAY",
                      "bid": 0.5,
                      "ask": 0.6,
                      "strikePrice": 225.0,
                      "daysToExpiration": 0.0
                    }
                  ]
                }
              },
              "putExpDateMap": {
                "2028-01-21:429": {
                  "260.0": [
                    {
                      "putCall": "PUT",
                      "symbol": "IWM   280121P00260000",
                      "bid": 34.0,
                      "ask": 34.8,
                      "mark": 34.4,
                      "openInterest": 920.0,
                      "totalVolume": 45.0,
                      "volatility": 0.0,
                      "delta": -0.79,
                      "gamma": 0.005,
                      "theta": -0.021,
                      "vega": 0.72,
                      "strikePrice": 260.0,
                      "daysToExpiration": 429.0
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val dto = json.decodeFromString<OptionChainResponseDto>(rawJson)
        val parsed = parser.parse(dto, divYield = 0.012, fetchedAtEpochMs = 123_456L)

        assertEquals("IWM", parsed.symbol)
        assertEquals(224.0, parsed.underlyingPrice, 1e-6)
        assertEquals(0.045, parsed.riskFreeRate, 1e-6)
        // Expired (0 DTE) and strike <= 0 contracts must be ignored; 3 valid contracts remain
        assertEquals(3, parsed.contracts.size)

        val liveLeaps = parsed.contracts.first { it.symbol == "IWM   280121C00190000" }
        assertFalse(liveLeaps.greeksFromBlackScholesFallback)
        assertEquals(0.82, liveLeaps.delta, 1e-6)
        assertEquals(0.234, liveLeaps.iv, 1e-6)
        assertEquals(4120, liveLeaps.openInterest)
        assertEquals(LocalDate.of(2028, 1, 21), liveLeaps.expirationDate)

        // NaN + -999.0 short call triggers full BS IV & Greek fallback
        val sanitizedShort = parsed.contracts.first { it.symbol == "IWM   261224C00235000" }
        assertTrue(sanitizedShort.greeksFromBlackScholesFallback)
        assertTrue(sanitizedShort.iv > 0.05 && sanitizedShort.iv < 1.0)
        assertTrue(sanitizedShort.delta in 0.10..0.50)
        assertTrue(sanitizedShort.thetaPerDay < 0.0)
        assertNotNull(parsed.atm30DayIv)

        // Valid negative put delta preserved, while volatility = 0.0 still triggers BS fallback
        val zeroVolPut = parsed.contracts.first { it.symbol == "IWM   280121P00260000" }
        assertEquals(-0.79, zeroVolPut.delta, 1e-6)
        assertTrue(zeroVolPut.greeksFromBlackScholesFallback)
        assertTrue(zeroVolPut.iv > 0.05)
    }

    @Test
    fun `symbol normalizer handles BRK dot B to slash and dynamic strike counts`() {
        assertEquals("BRK/B", SchwabSymbolNormalizer.toSchwabMarketDataSymbol("BRK.B"))
        assertEquals("BRK.B", SchwabSymbolNormalizer.toDisplaySymbol("BRK/B"))
        assertEquals("\$VIX", SchwabSymbolNormalizer.toSchwabMarketDataSymbol("\$VIX"))
        assertEquals("IWM   280121C00190000", SchwabSymbolNormalizer.toSchwabMarketDataSymbol("IWM   280121C00190000"))

        assertEquals(100, SchwabSymbolNormalizer.strikeCountForSymbol("SPY"))
        assertEquals(100, SchwabSymbolNormalizer.strikeCountForSymbol("QQQ"))
        assertEquals(100, SchwabSymbolNormalizer.strikeCountForSymbol("IWM"))
        assertEquals(60, SchwabSymbolNormalizer.strikeCountForSymbol("NVDA"))
    }
}
