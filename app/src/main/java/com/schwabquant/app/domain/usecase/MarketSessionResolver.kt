package com.schwabquant.app.domain.usecase

import com.schwabquant.app.domain.model.TradingSession
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class MarketSessionWindow(
    val label: String,
    val shortLabel: String,
    val exitOrderSession: TradingSession?,
    val allowsAutomatedExecution: Boolean
) {
    PRE_MARKET(
        label = "Pre-Market (07:00–09:30 ET)",
        shortLabel = "PRE-MARKET",
        exitOrderSession = TradingSession.SEAMLESS,
        allowsAutomatedExecution = true
    ),
    REGULAR(
        label = "Regular Market (09:30–16:00 ET)",
        shortLabel = "RTH",
        // Boundary-safe SEAMLESS across 07:00-19:59:59 ET prevents 15:59:58 -> 16:00:01 rejections
        exitOrderSession = TradingSession.SEAMLESS,
        allowsAutomatedExecution = true
    ),
    AFTER_HOURS(
        label = "After-Hours (16:00–20:00 ET)",
        shortLabel = "AFTER-HOURS",
        exitOrderSession = TradingSession.SEAMLESS,
        allowsAutomatedExecution = true
    ),
    OVERNIGHT_EXTO(
        label = "Overnight EXTO (Alert Only)",
        shortLabel = "OVERNIGHT EXTO",
        exitOrderSession = null,
        allowsAutomatedExecution = false
    )
}

/**
 * Pure-JVM U.S. Eastern trading session clock (America/New_York) governing extended-hours
 * execution boundaries vs. Overnight EXTO alert-only protection.
 */
@Singleton
class MarketSessionResolver(
    private val clock: Clock
) {

    @Inject
    constructor() : this(Clock.system(NY_ZONE))

    fun resolveCurrentSession(): MarketSessionWindow = resolve()

    fun resolve(instant: Instant = clock.instant()): MarketSessionWindow {
        val zoned = instant.atZone(NY_ZONE)
        val dow = zoned.dayOfWeek
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return MarketSessionWindow.OVERNIGHT_EXTO
        }

        val time = zoned.toLocalTime()
        return when {
            !time.isBefore(PRE_MARKET_START) && time.isBefore(REGULAR_START) ->
                MarketSessionWindow.PRE_MARKET
            !time.isBefore(REGULAR_START) && time.isBefore(AFTER_HOURS_START) ->
                MarketSessionWindow.REGULAR
            !time.isBefore(AFTER_HOURS_START) && time.isBefore(OVERNIGHT_START) ->
                MarketSessionWindow.AFTER_HOURS
            else -> MarketSessionWindow.OVERNIGHT_EXTO
        }
    }

    companion object {
        val NY_ZONE: ZoneId = ZoneId.of("America/New_York")
        private val PRE_MARKET_START: LocalTime = LocalTime.of(7, 0, 0)
        private val REGULAR_START: LocalTime = LocalTime.of(9, 30, 0)
        private val AFTER_HOURS_START: LocalTime = LocalTime.of(16, 0, 0)
        private val OVERNIGHT_START: LocalTime = LocalTime.of(20, 0, 0)
    }
}
