package com.schwabquant.app.core.security

import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.presentation.navigation.NavRoutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DeepLinkIntentSanitizerTest {

    @Test
    fun `sanitizeNavDestination accepts only whitelisted NavRoutes`() {
        assertTrue(NavRoutes.VALID_ROUTES.isNotEmpty())
        NavRoutes.VALID_ROUTES.forEach { route ->
            assertEquals(route, DeepLinkIntentSanitizer.sanitizeNavDestination(route))
            assertEquals(route, DeepLinkIntentSanitizer.sanitizeNavDestination("  $route  "))
        }

        assertNull(DeepLinkIntentSanitizer.sanitizeNavDestination(null))
        assertNull(DeepLinkIntentSanitizer.sanitizeNavDestination(""))
        assertNull(DeepLinkIntentSanitizer.sanitizeNavDestination("   "))
        assertNull(DeepLinkIntentSanitizer.sanitizeNavDestination("evil/arbitrary"))
        assertNull(DeepLinkIntentSanitizer.sanitizeNavDestination("${NavRoutes.VALID_ROUTES.first()}?hack=1"))
    }

    @Test
    fun `parseStrictPositiveDecimal blocks scientific notation DoS and post-rounding zero`() {
        assertEquals(BigDecimal("150.00"), DeepLinkIntentSanitizer.parseStrictPositiveDecimal("150"))
        assertEquals(BigDecimal("450.25"), DeepLinkIntentSanitizer.parseStrictPositiveDecimal(" 450.25 "))
        assertEquals(BigDecimal("0.01"), DeepLinkIntentSanitizer.parseStrictPositiveDecimal("0.005"))
        assertEquals(BigDecimal("1000000.00"), DeepLinkIntentSanitizer.parseStrictPositiveDecimal("1000000"))

        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal(null))
        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal(""))
        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal("   "))
        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal("0"))
        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal("0.00"))
        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal("0.004"))
        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal("-10.00"))
        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal("1e1000000"))
        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal("1E2"))
        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal("1,250.00"))
        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal("1000000.01"))
        assertNull(DeepLinkIntentSanitizer.parseStrictPositiveDecimal("1".repeat(17)))
    }

    @Test
    fun `parseAndSanitizeStageOrder rejects missing or forged internal token`() {
        assertTrue(InternalIntentAuth.isValid(InternalIntentAuth.token))
        assertFalse(InternalIntentAuth.isValid(null))
        assertFalse(InternalIntentAuth.isValid(""))
        assertFalse(InternalIntentAuth.isValid("forged-token"))

        val unauthenticated = DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
            raw = RawStageOrderIntentData(
                internalToken = "wrong-token",
                executionMode = ExecutionMode.SHADOW.name,
                symbol = "SPY",
                side = OrderSide.BUY.name,
                quantity = 5,
                limitPrice = "500.00",
                sourceLabel = "Forged",
                replacePriorOrderId = null,
                isEscalatedReprice = false
            ),
            activeMode = ExecutionMode.SHADOW
        )
        assertTrue(unauthenticated is DeepLinkStageResult.Rejected)

        val missingToken = DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
            raw = RawStageOrderIntentData(
                internalToken = null,
                executionMode = ExecutionMode.LIVE.name,
                symbol = "SPY",
                side = OrderSide.SELL.name,
                quantity = 5,
                limitPrice = "500.00",
                sourceLabel = "External App",
                replacePriorOrderId = null,
                isEscalatedReprice = true
            ),
            activeMode = ExecutionMode.LIVE
        )
        assertTrue(missingToken is DeepLinkStageResult.Rejected)
    }

    @Test
    fun `parseAndSanitizeStageOrder flags cross-mode notification intents as ModeMismatch`() {
        val shadowToLive = DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
            raw = RawStageOrderIntentData(
                internalToken = InternalIntentAuth.token,
                executionMode = ExecutionMode.SHADOW.name,
                symbol = "QQQ",
                side = OrderSide.SELL.name,
                quantity = 10,
                limitPrice = "480.00",
                sourceLabel = "Shadow Alert",
                replacePriorOrderId = "SHADOW-42",
                isEscalatedReprice = true
            ),
            activeMode = ExecutionMode.LIVE
        )
        assertTrue(shadowToLive is DeepLinkStageResult.ModeMismatch)
        val mismatch1 = shadowToLive as DeepLinkStageResult.ModeMismatch
        assertEquals(ExecutionMode.SHADOW, mismatch1.expectedMode)
        assertEquals(ExecutionMode.LIVE, mismatch1.activeMode)

        val liveToShadow = DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
            raw = RawStageOrderIntentData(
                internalToken = InternalIntentAuth.token,
                executionMode = ExecutionMode.LIVE.name,
                symbol = "QQQ",
                side = OrderSide.BUY_TO_COVER.name,
                quantity = 10,
                limitPrice = "480.00",
                sourceLabel = "Live Alert",
                replacePriorOrderId = "900123",
                isEscalatedReprice = true
            ),
            activeMode = ExecutionMode.SHADOW
        )
        assertTrue(liveToShadow is DeepLinkStageResult.ModeMismatch)
        val mismatch2 = liveToShadow as DeepLinkStageResult.ModeMismatch
        assertEquals(ExecutionMode.LIVE, mismatch2.expectedMode)
        assertEquals(ExecutionMode.SHADOW, mismatch2.activeMode)
    }

    @Test
    fun `parseAndSanitizeStageOrder rejects invalid sides quantities symbols DoS prices and cross-domain order IDs`() {
        fun baseRaw() = RawStageOrderIntentData(
            internalToken = InternalIntentAuth.token,
            executionMode = ExecutionMode.LIVE.name,
            symbol = "spy",
            side = OrderSide.SELL.name,
            quantity = 10,
            limitPrice = "510.25",
            sourceLabel = "Sentinel",
            replacePriorOrderId = "880011",
            isEscalatedReprice = false
        )

        assertTrue(
            DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
                baseRaw().copy(side = OrderSide.BUY_TO_OPEN.name),
                ExecutionMode.LIVE
            ) is DeepLinkStageResult.Rejected
        )
        assertTrue(
            DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
                baseRaw().copy(side = "SELL_SHORT"),
                ExecutionMode.LIVE
            ) is DeepLinkStageResult.Rejected
        )
        assertTrue(
            DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
                baseRaw().copy(side = OrderSide.SELL_TO_OPEN.name),
                ExecutionMode.LIVE
            ) is DeepLinkStageResult.Rejected
        )
        assertTrue(
            DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
                baseRaw().copy(symbol = "../SPY"),
                ExecutionMode.LIVE
            ) is DeepLinkStageResult.Rejected
        )
        assertTrue(
            DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
                baseRaw().copy(symbol = "BRK..B"),
                ExecutionMode.LIVE
            ) is DeepLinkStageResult.Rejected
        )
        val brkNormalized = DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
            baseRaw().copy(symbol = "brk/b"),
            ExecutionMode.LIVE
        )
        assertTrue(brkNormalized is DeepLinkStageResult.ValidDraft)
        assertEquals("BRK.B", (brkNormalized as DeepLinkStageResult.ValidDraft).draft.underlyingSymbol)
        assertTrue(
            DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
                baseRaw().copy(quantity = 0),
                ExecutionMode.LIVE
            ) is DeepLinkStageResult.Rejected
        )
        assertTrue(
            DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
                baseRaw().copy(quantity = 1_000_001),
                ExecutionMode.LIVE
            ) is DeepLinkStageResult.Rejected
        )
        assertTrue(
            DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
                baseRaw().copy(limitPrice = "1e999"),
                ExecutionMode.LIVE
            ) is DeepLinkStageResult.Rejected
        )
        assertTrue(
            DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
                baseRaw().copy(replacePriorOrderId = "SHADOW-99"),
                ExecutionMode.LIVE
            ) is DeepLinkStageResult.Rejected
        )
        assertTrue(
            DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
                baseRaw().copy(executionMode = ExecutionMode.SHADOW.name, replacePriorOrderId = "880011"),
                ExecutionMode.SHADOW
            ) is DeepLinkStageResult.Rejected
        )

        val validLive = DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(baseRaw(), ExecutionMode.LIVE)
        assertTrue(validLive is DeepLinkStageResult.ValidDraft)
        val draft = (validLive as DeepLinkStageResult.ValidDraft).draft
        assertEquals("SPY", draft.underlyingSymbol)
        assertEquals(BigDecimal("510.25"), draft.limitPrice)
        assertEquals("880011", draft.replacePriorOrderId)
        assertEquals(1, draft.legs.size)
        assertEquals(AssetCategory.EQUITY, draft.legs.first().category)
        assertEquals(OrderSide.SELL, draft.legs.first().side)
        assertEquals(5102.5, draft.estimatedBpr!!, 0.01)

        val validShadow = DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(
            baseRaw().copy(
                executionMode = ExecutionMode.SHADOW.name,
                side = OrderSide.BUY_TO_COVER.name,
                replacePriorOrderId = "SHADOW-77",
                limitPrice = null
            ),
            ExecutionMode.SHADOW
        )
        assertTrue(validShadow is DeepLinkStageResult.ValidDraft)
        val shadowDraft = (validShadow as DeepLinkStageResult.ValidDraft).draft
        assertEquals(OrderSide.BUY_TO_COVER, shadowDraft.legs.first().side)
        assertEquals("SHADOW-77", shadowDraft.replacePriorOrderId)
        assertNull(shadowDraft.limitPrice)
        assertNull(shadowDraft.estimatedBpr)
    }

    @Test
    fun `MEDIUM-5 minted internal intent nonces are strictly single-use and marketDetail route is blocked from external deep links`() {
        val singleUseNonce = InternalIntentAuth.mintNonce()
        val raw = RawStageOrderIntentData(
            internalToken = singleUseNonce,
            executionMode = ExecutionMode.SHADOW.name,
            symbol = "SPY",
            side = OrderSide.BUY.name,
            quantity = 3,
            limitPrice = "580.00",
            sourceLabel = "Notification",
            replacePriorOrderId = null,
            isEscalatedReprice = false
        )

        val firstUse = DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(raw, ExecutionMode.SHADOW)
        assertTrue(firstUse is DeepLinkStageResult.ValidDraft)

        // Exact replay using the same nonce must fail closed!
        val replayUse = DeepLinkIntentSanitizer.parseAndSanitizeStageOrder(raw, ExecutionMode.SHADOW)
        assertTrue(replayUse is DeepLinkStageResult.Rejected)

        // Unauthenticated external deep-links must never resolve marketDetail routes
        assertNull(DeepLinkIntentSanitizer.sanitizeNavDestination(NavRoutes.MARKET_DETAIL_ROUTE))
        assertNull(DeepLinkIntentSanitizer.sanitizeNavDestination(NavRoutes.marketDetail("SPY")))
    }
}
