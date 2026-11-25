package com.schwabquant.app.core.quant

import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStrategyCategory
import com.schwabquant.app.domain.model.OrderType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class SpreadDraftAssemblerTest {

    private val assembler = SpreadDraftAssembler()
    private val payloadBuilder = SchwabOrderPayloadBuilder()

    @Test
    fun `auto-classifies SINGLE VERTICAL CALENDAR DIAGONAL IRON_CONDOR with valid Schwab payloads and blank accountHash`() {
        val expFront = LocalDate.of(2026, 12, 18)
        val expBack = LocalDate.of(2027, 2, 19)
        val expLeaps = LocalDate.of(2027, 9, 17)

        val shortCall = opt("SPY   261218C00590000", true, 590.0, expFront, 28, mid = 3.20, delta = 0.28)
        val longCallWing = opt("SPY   261218C00600000", true, 600.0, expFront, 28, mid = 1.40, delta = 0.14)
        val shortPut = opt("SPY   261218P00570000", false, 570.0, expFront, 28, mid = 3.50, delta = -0.26)
        val longPutWing = opt("SPY   261218P00560000", false, 560.0, expFront, 28, mid = 1.70, delta = -0.14)
        val backCall = opt("SPY   270219C00590000", true, 590.0, expBack, 91, mid = 8.40, delta = 0.44)
        val leapsCall = opt("SPY   270917C00540000", true, 540.0, expLeaps, 300, mid = 64.50, delta = 0.84)

        // 1. Single Option
        val single = assembler.assemble("brk.b", 585.0, listOf(SelectedChainLeg(shortPut, OrderSide.SELL_TO_OPEN))).getOrThrow()
        assertEquals(OrderStrategyCategory.SINGLE_OPTION, single.strategyCategory)
        assertEquals(OrderType.LIMIT, single.orderType)
        assertEquals(BigDecimal("3.50"), single.limitPrice)
        assertEquals("BRK.B", single.draft.underlyingSymbol)
        val singleTicket = single.draft.toOrderTicket("", ExecutionMode.LIVE)
        assertEquals("", singleTicket.accountHash)
        payloadBuilder.build(singleTicket)

        // 2. Vertical Credit Spread
        val vertical = assembler.assemble(
            "SPY", 585.0,
            listOf(SelectedChainLeg(shortPut, OrderSide.SELL_TO_OPEN), SelectedChainLeg(longPutWing, OrderSide.BUY_TO_OPEN))
        ).getOrThrow()
        assertEquals(OrderStrategyCategory.VERTICAL, vertical.strategyCategory)
        assertEquals(OrderType.NET_CREDIT, vertical.orderType)
        assertEquals(BigDecimal("1.80"), vertical.limitPrice)
        payloadBuilder.build(vertical.draft.toOrderTicket("", ExecutionMode.LIVE))

        // 3. Calendar Debit Spread
        val calendar = assembler.assemble(
            "SPY", 585.0,
            listOf(SelectedChainLeg(backCall, OrderSide.BUY_TO_OPEN), SelectedChainLeg(shortCall, OrderSide.SELL_TO_OPEN))
        ).getOrThrow()
        assertEquals(OrderStrategyCategory.CALENDAR, calendar.strategyCategory)
        assertEquals(OrderType.NET_DEBIT, calendar.orderType)
        assertEquals(BigDecimal("5.20"), calendar.limitPrice)
        payloadBuilder.build(calendar.draft.toOrderTicket("", ExecutionMode.LIVE))

        // 4. Diagonal PMCC
        val diagonal = assembler.assemble(
            "SPY", 585.0,
            listOf(SelectedChainLeg(leapsCall, OrderSide.BUY_TO_OPEN), SelectedChainLeg(shortCall, OrderSide.SELL_TO_OPEN))
        ).getOrThrow()
        assertEquals(OrderStrategyCategory.DIAGONAL, diagonal.strategyCategory)
        assertEquals(OrderType.NET_DEBIT, diagonal.orderType)
        assertEquals(BigDecimal("61.30"), diagonal.limitPrice)
        payloadBuilder.build(diagonal.draft.toOrderTicket("", ExecutionMode.LIVE))

        // 5. Iron Condor
        val condor = assembler.assemble(
            "SPY", 585.0,
            listOf(
                SelectedChainLeg(longPutWing, OrderSide.BUY_TO_OPEN),
                SelectedChainLeg(shortPut, OrderSide.SELL_TO_OPEN),
                SelectedChainLeg(shortCall, OrderSide.SELL_TO_OPEN),
                SelectedChainLeg(longCallWing, OrderSide.BUY_TO_OPEN)
            )
        ).getOrThrow()
        assertEquals(OrderStrategyCategory.IRON_CONDOR, condor.strategyCategory)
        assertEquals(OrderType.NET_CREDIT, condor.orderType)
        assertEquals(BigDecimal("3.60"), condor.limitPrice)
        payloadBuilder.build(condor.draft.toOrderTicket("", ExecutionMode.LIVE))
    }

    @Test
    fun `rejects duplicate legs over 4 legs and invalid leg pairings`() {
        val exp = LocalDate.of(2026, 12, 18)
        val c1 = opt("SPY   261218C00590000", true, 590.0, exp, 28, 3.0, 0.30)
        val c2 = opt("SPY   261218C00595000", true, 595.0, exp, 28, 2.0, 0.22)
        val p1 = opt("SPY   261218P00580000", false, 580.0, exp, 28, 3.0, -0.30)
        val p2 = opt("SPY   261218P00575000", false, 575.0, exp, 28, 2.0, -0.22)
        val p3 = opt("SPY   261218P00570000", false, 570.0, exp, 28, 1.0, -0.14)

        assertTrue(assembler.assemble("SPY", 585.0, emptyList()).isFailure)
        assertTrue(
            assembler.assemble(
                "SPY", 585.0,
                listOf(SelectedChainLeg(c1, OrderSide.BUY_TO_OPEN), SelectedChainLeg(c1, OrderSide.SELL_TO_OPEN))
            ).isFailure
        )
        assertTrue(
            assembler.assemble(
                "SPY", 585.0,
                listOf(SelectedChainLeg(c1, OrderSide.BUY_TO_OPEN), SelectedChainLeg(p1, OrderSide.SELL_TO_OPEN))
            ).isFailure
        )
        assertTrue(
            assembler.assemble(
                "SPY", 585.0,
                listOf(
                    SelectedChainLeg(c1, OrderSide.SELL_TO_OPEN),
                    SelectedChainLeg(c2, OrderSide.BUY_TO_OPEN),
                    SelectedChainLeg(p1, OrderSide.SELL_TO_OPEN),
                    SelectedChainLeg(p2, OrderSide.BUY_TO_OPEN),
                    SelectedChainLeg(p3, OrderSide.BUY_TO_OPEN)
                )
            ).isFailure
        )
    }

    @Test
    fun `V1-9-1 MEDIUM-2 credit diagonal, credit calendar, and debit iron condor assemble and serialize cleanly`() {
        val expFront = LocalDate.of(2026, 12, 18)
        val expBack = LocalDate.of(2027, 2, 19)

        // 1. Credit Diagonal: sell expensive ITM front call, buy cheaper OTM back call -> NET_CREDIT
        val shortItmCall = opt("SPY   261218C00550000", true, 550.0, expFront, 28, mid = 38.00, delta = 0.88)
        val longOtmBackCall = opt("SPY   270219C00620000", true, 620.0, expBack, 91, mid = 5.50, delta = 0.25)
        val creditDiag = assembler.assemble(
            "SPY", 585.0,
            listOf(SelectedChainLeg(longOtmBackCall, OrderSide.BUY_TO_OPEN), SelectedChainLeg(shortItmCall, OrderSide.SELL_TO_OPEN))
        ).getOrThrow()
        assertEquals(OrderStrategyCategory.DIAGONAL, creditDiag.strategyCategory)
        assertEquals(OrderType.NET_CREDIT, creditDiag.orderType)
        val diagReq = payloadBuilder.build(creditDiag.draft.toOrderTicket("", ExecutionMode.LIVE))
        assertEquals("NET_CREDIT", diagReq.orderType)
        assertEquals("DIAGONAL", diagReq.complexOrderStrategyType)

        // 2. Credit Calendar: sell higher-mid leg, buy lower-mid leg at same strike -> NET_CREDIT
        val shortFrontCal = opt("SPY   261218C00585000", true, 585.0, expFront, 28, mid = 7.40, delta = 0.50)
        val longBackCal = opt("SPY   270219C00585000", true, 585.0, expBack, 91, mid = 5.20, delta = 0.52)
        val creditCal = assembler.assemble(
            "SPY", 585.0,
            listOf(SelectedChainLeg(longBackCal, OrderSide.BUY_TO_OPEN), SelectedChainLeg(shortFrontCal, OrderSide.SELL_TO_OPEN))
        ).getOrThrow()
        assertEquals(OrderStrategyCategory.CALENDAR, creditCal.strategyCategory)
        assertEquals(OrderType.NET_CREDIT, creditCal.orderType)
        val calReq = payloadBuilder.build(creditCal.draft.toOrderTicket("", ExecutionMode.LIVE))
        assertEquals("NET_CREDIT", calReq.orderType)
        assertEquals("CALENDAR", calReq.complexOrderStrategyType)

        // 3. Debit Iron Condor (long wings priced above short inner strikes -> NET_DEBIT)
        val buyPutWing = opt("SPY   261218P00560000", false, 560.0, expFront, 28, mid = 4.50, delta = -0.25)
        val sellPutInner = opt("SPY   261218P00570000", false, 570.0, expFront, 28, mid = 1.60, delta = -0.14)
        val sellCallInner = opt("SPY   261218C00595000", true, 595.0, expFront, 28, mid = 1.40, delta = 0.14)
        val buyCallWing = opt("SPY   261218C00605000", true, 605.0, expFront, 28, mid = 3.80, delta = 0.25)
        val debitCondor = assembler.assemble(
            "SPY", 585.0,
            listOf(
                SelectedChainLeg(buyPutWing, OrderSide.BUY_TO_OPEN),
                SelectedChainLeg(sellPutInner, OrderSide.SELL_TO_OPEN),
                SelectedChainLeg(sellCallInner, OrderSide.SELL_TO_OPEN),
                SelectedChainLeg(buyCallWing, OrderSide.BUY_TO_OPEN)
            )
        ).getOrThrow()
        assertEquals(OrderStrategyCategory.IRON_CONDOR, debitCondor.strategyCategory)
        assertEquals(OrderType.NET_DEBIT, debitCondor.orderType)
        val condorReq = payloadBuilder.build(debitCondor.draft.toOrderTicket("", ExecutionMode.LIVE))
        assertEquals("NET_DEBIT", condorReq.orderType)
        assertEquals("IRON_CONDOR", condorReq.complexOrderStrategyType)
    }

    private fun opt(
        occ: String,
        isCall: Boolean,
        strike: Double,
        exp: LocalDate,
        dte: Int,
        mid: Double,
        delta: Double
    ) = ParsedOptionContract(
        symbol = occ,
        isCall = isCall,
        strike = strike,
        expirationDate = exp,
        dte = dte,
        bid = (mid - 0.05).coerceAtLeast(0.01),
        ask = mid + 0.05,
        mid = mid,
        last = mid,
        openInterest = 1_200,
        volume = 250,
        iv = 0.25,
        delta = delta,
        gamma = 0.02,
        thetaPerDay = -0.05,
        vegaPer1Pct = 0.14,
        rhoPer1Pct = 0.03,
        intrinsicValue = 0.0,
        extrinsicValue = mid,
        spreadWidthPct = 0.03,
        greeksFromBlackScholesFallback = false
    )
}
