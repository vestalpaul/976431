package com.schwabquant.app.core.security

import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStrategyCategory
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.TradingSession
import com.schwabquant.app.domain.usecase.OvernightAlertBuilder
import com.schwabquant.app.presentation.navigation.NavRoutes
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Per-process cryptographic nonce verifying internal notification deep-link forwards from
 * non-exported `SentinelDeepLinkActivity` to exported `MainActivity`.
 */
object InternalIntentAuth {
    val token: String by lazy {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun isValid(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8)
        )
    }
}

data class RawStageOrderIntentData(
    val internalToken: String?,
    val executionMode: String?,
    val symbol: String?,
    val side: String?,
    val quantity: Int,
    val limitPrice: String?,
    val sourceLabel: String?,
    val replacePriorOrderId: String?,
    val isEscalatedReprice: Boolean
)

sealed interface DeepLinkStageResult {
    data class ValidDraft(val draft: OrderDraft) : DeepLinkStageResult
    data class ModeMismatch(
        val expectedMode: ExecutionMode,
        val activeMode: ExecutionMode,
        val message: String
    ) : DeepLinkStageResult
    data class Rejected(val reason: String) : DeepLinkStageResult
}

/**
 * Pure-JVM deep-link route & order staging sanitizer defending against malicious/malformed
 * intents, scientific-notation DoS inputs, cross-mode staging, and bogus asset/side pairs.
 */
object DeepLinkIntentSanitizer {
    private const val MAX_DECIMAL_LEN = 16
    private const val MAX_QUANTITY = 1_000_000
    private val MAX_PRICE = BigDecimal("1000000")
    private val PLAIN_DECIMAL_REGEX = Regex("""^\d+(?:\.\d+)?$""")
    private val EQUITY_SYMBOL_REGEX = Regex("""^[A-Z0-9](?:[A-Z0-9./-]{0,14}[A-Z0-9])?$""")
    private val ALLOWED_EQUITY_SIDES = setOf(
        OrderSide.BUY,
        OrderSide.SELL,
        OrderSide.BUY_TO_COVER
    )

    fun sanitizeNavDestination(rawRoute: String?): String? {
        val trimmed = rawRoute?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.takeIf { it in NavRoutes.VALID_ROUTES }
    }

    fun parseStrictPositiveDecimal(raw: String?): BigDecimal? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_DECIMAL_LEN } ?: return null
        if (!PLAIN_DECIMAL_REGEX.matches(trimmed)) return null
        val parsed = trimmed.toBigDecimalOrNull() ?: return null
        if (parsed > MAX_PRICE) return null
        val scaled = runCatching { parsed.setScale(2, RoundingMode.HALF_UP) }.getOrNull() ?: return null
        return scaled.takeIf { it > BigDecimal.ZERO }
    }

    fun parseAndSanitizeStageOrder(
        raw: RawStageOrderIntentData,
        activeMode: ExecutionMode
    ): DeepLinkStageResult {
        if (!InternalIntentAuth.isValid(raw.internalToken)) {
            return DeepLinkStageResult.Rejected("Unauthenticated internal staging intent rejected.")
        }

        val stagedMode = raw.executionMode?.trim()?.uppercase()
            ?.let { name -> ExecutionMode.entries.firstOrNull { it.name == name } }
            ?: return DeepLinkStageResult.Rejected("Missing or invalid staged executionMode.")

        if (stagedMode != activeMode) {
            return DeepLinkStageResult.ModeMismatch(
                expectedMode = stagedMode,
                activeMode = activeMode,
                message = "Blocked ${stagedMode.name} notification order in ${activeMode.name} Mode — switch to ${stagedMode.name} Mode first."
            )
        }

        val symbol = SchwabSymbolNormalizer.toDisplaySymbol(raw.symbol.orEmpty())
        if (symbol.isBlank() || symbol.contains("..") || !EQUITY_SYMBOL_REGEX.matches(symbol)) {
            return DeepLinkStageResult.Rejected("Invalid equity symbol '${raw.symbol}'.")
        }

        val sideName = raw.side?.trim()?.uppercase().orEmpty()
        val side = ALLOWED_EQUITY_SIDES.firstOrNull { it.name == sideName }
            ?: return DeepLinkStageResult.Rejected("Unsupported equity order side '${raw.side}'.")

        if (raw.quantity !in 1..MAX_QUANTITY) {
            return DeepLinkStageResult.Rejected("Invalid order quantity ${raw.quantity}.")
        }

        val rawPrice = raw.limitPrice?.trim().orEmpty()
        val limitPrice = if (rawPrice.isNotEmpty()) {
            parseStrictPositiveDecimal(rawPrice)
                ?: return DeepLinkStageResult.Rejected("Invalid limit price '$rawPrice'.")
        } else {
            null
        }

        val estimatedBpr = if (limitPrice != null) {
            val bpr = limitPrice.toDouble() * raw.quantity.toDouble()
            if (!bpr.isFinite() || bpr < 0.0) {
                return DeepLinkStageResult.Rejected("Non-finite BPR estimate.")
            }
            bpr
        } else {
            null
        }

        val sanitizedReplaceId = OvernightAlertBuilder.sanitizeTrackableOrderId(raw.replacePriorOrderId)
        if (sanitizedReplaceId != null) {
            val isShadowPrior = sanitizedReplaceId.startsWith("SHADOW-", ignoreCase = true)
            if ((stagedMode == ExecutionMode.LIVE && isShadowPrior) ||
                (stagedMode == ExecutionMode.SHADOW && !isShadowPrior)
            ) {
                return DeepLinkStageResult.Rejected("Cross-domain prior order ID rejected.")
            }
        }

        val cleanSourceLabel = raw.sourceLabel?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
            ?: "Sentinel Staged Order ($symbol)"
        val unitDouble = limitPrice?.toDouble()

        val draft = OrderDraft(
            strategyCategory = OrderStrategyCategory.SINGLE_EQUITY,
            underlyingSymbol = symbol,
            orderType = OrderType.LIMIT,
            session = TradingSession.SEAMLESS,
            duration = OrderDuration.DAY,
            limitPrice = limitPrice,
            legs = listOf(
                OrderLeg(
                    symbol = symbol,
                    category = AssetCategory.EQUITY,
                    side = side,
                    quantity = raw.quantity,
                    underlyingSymbol = symbol,
                    estimatedUnitPrice = limitPrice
                )
            ),
            estimatedBid = unitDouble,
            estimatedMid = unitDouble,
            estimatedAsk = unitDouble,
            estimatedBpr = estimatedBpr,
            sourceLabel = cleanSourceLabel,
            simulateImmediateMidFill = false,
            replacePriorOrderId = sanitizedReplaceId,
            isEscalatedReprice = raw.isEscalatedReprice
        )
        return DeepLinkStageResult.ValidDraft(draft)
    }
}
