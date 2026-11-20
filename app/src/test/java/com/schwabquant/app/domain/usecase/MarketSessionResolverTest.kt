package com.schwabquant.app.domain.usecase

import com.schwabquant.app.domain.model.TradingSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZonedDateTime

class MarketSessionResolverTest {

    private val resolver = MarketSessionResolver()

    private fun nyInstant(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, MarketSessionResolver.NY_ZONE).toInstant()

    @Test
    fun `resolves weekday pre-market, regular, after-hours, and overnight boundaries accurately`() {
        // Wednesday 2026-06-10 (EDT)
        val pre0659 = resolver.resolve(nyInstant(2026, 6, 10, 6, 59, 59))
        assertEquals(MarketSessionWindow.OVERNIGHT_EXTO, pre0659)
        assertFalse(pre0659.allowsAutomatedExecution)
        assertNull(pre0659.exitOrderSession)

        val pre0700 = resolver.resolve(nyInstant(2026, 6, 10, 7, 0, 0))
        assertEquals(MarketSessionWindow.PRE_MARKET, pre0700)
        assertTrue(pre0700.allowsAutomatedExecution)
        assertEquals(TradingSession.SEAMLESS, pre0700.exitOrderSession)

        val rth0930 = resolver.resolve(nyInstant(2026, 6, 10, 9, 30, 0))
        assertEquals(MarketSessionWindow.REGULAR, rth0930)
        assertTrue(rth0930.allowsAutomatedExecution)
        // Safeguard #3: boundary-safe SEAMLESS even during RTH
        assertEquals(TradingSession.SEAMLESS, rth0930.exitOrderSession)

        val rth155959 = resolver.resolve(nyInstant(2026, 6, 10, 15, 59, 59))
        assertEquals(MarketSessionWindow.REGULAR, rth155959)
        assertEquals(TradingSession.SEAMLESS, rth155959.exitOrderSession)

        val pm1600 = resolver.resolve(nyInstant(2026, 6, 10, 16, 0, 0))
        assertEquals(MarketSessionWindow.AFTER_HOURS, pm1600)
        assertTrue(pm1600.allowsAutomatedExecution)
        assertEquals(TradingSession.SEAMLESS, pm1600.exitOrderSession)

        val pm195959 = resolver.resolve(nyInstant(2026, 6, 10, 19, 59, 59))
        assertEquals(MarketSessionWindow.AFTER_HOURS, pm195959)
        assertEquals(TradingSession.SEAMLESS, pm195959.exitOrderSession)

        val overnight2000 = resolver.resolve(nyInstant(2026, 6, 10, 20, 0, 0))
        assertEquals(MarketSessionWindow.OVERNIGHT_EXTO, overnight2000)
        assertFalse(overnight2000.allowsAutomatedExecution)
        assertNull(overnight2000.exitOrderSession)
    }

    @Test
    fun `treats Saturday and Sunday as OVERNIGHT_EXTO alert only`() {
        // Saturday 2026-06-13 12:00 ET
        assertEquals(
            MarketSessionWindow.OVERNIGHT_EXTO,
            resolver.resolve(nyInstant(2026, 6, 13, 12, 0, 0))
        )
        // Sunday 2026-06-14 14:30 ET
        assertEquals(
            MarketSessionWindow.OVERNIGHT_EXTO,
            resolver.resolve(nyInstant(2026, 6, 14, 14, 30, 0))
        )
    }
}
