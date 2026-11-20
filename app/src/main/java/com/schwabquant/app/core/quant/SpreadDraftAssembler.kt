package com.schwabquant.app.core.quant

import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.OrderDraft
import com.schwabquant.app.domain.model.OrderDuration
import com.schwabquant.app.domain.model.OrderLeg
import com.schwabquant.app.domain.model.OrderSide
import com.schwabquant.app.domain.model.OrderStrategyCategory
import com.schwabquant.app.domain.model.OrderType
import com.schwabquant.app.domain.model.TradingSession
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

data class SelectedChainLeg(
    val contract: ParsedOptionContract,
    val side: OrderSide
)

data class AssembledSpreadResult(
    val strategyCategory: OrderStrategyCategory,
    val strategyLabel: String,
    val orderType: OrderType,
    val limitPrice: BigDecimal,
    val netDebitOrCreditPerShare: Double,
    val isNetCredit: Boolean,
    val netDelta: Double,
    val netThetaPerDay: Double,
    val netGamma: Double,
    val netVegaPer1Pct: Double,
    val estimatedBpr: Double,
    val draft: OrderDraft
)

/**
 * Pure-JVM multi-leg Spread Builder assembler converting 1..4 Thinkorswim chain selections into
 * a Schwab-verified `OrderDraft` compatible with `OrderTicketSheet`, `SchwabOrderPayloadBuilder`,
 * and `EvaluatePreTradeRisksUseCase`.
 */
@Singleton
class SpreadDraftAssembler @Inject constructor() {

    fun assemble(
        underlyingSymbol: String,
        underlyingSpot: Double,
        selectedLegs: List<SelectedChainLeg>
    ): Result<AssembledSpreadResult> = runCatching {
        require(selectedLegs.isNotEmpty()) { "Select 1 to 4 option legs first." }
        require(selectedLegs.size <= MAX_LEGS) { "Maximum $MAX_LEGS option legs supported per spread." }

        val allowedSides = setOf(OrderSide.BUY_TO_OPEN, OrderSide.SELL_TO_OPEN)
        require(selectedLegs.all { it.side in allowedSides }) {
            "Chain Spread Builder supports BUY_TO_OPEN / SELL_TO_OPEN legs only."
        }
        val distinctSymbols = selectedLegs.map { it.contract.symbol }.distinct()
        require(distinctSymbols.size == selectedLegs.size) {
            "Duplicate option contracts cannot appear twice in the same spread."
        }

        val cleanUnderlying = SchwabSymbolNormalizer.toDisplaySymbol(underlyingSymbol)
        require(cleanUnderlying.isNotEmpty()) { "Underlying symbol cannot be blank." }

        val strategyPair = classifyStrategy(selectedLegs)
        val category = strategyPair.first
        val label = strategyPair.second

        // Positive signedCashFlow = Net Credit received; Negative = Net Debit paid.
        val signedMidCashFlow = selectedLegs.sumOf { sel ->
            if (sel.side == OrderSide.SELL_TO_OPEN) sel.contract.mid else -sel.contract.mid
        }
        val netBid = selectedLegs.sumOf { sel ->
            if (sel.side == OrderSide.SELL_TO_OPEN) sel.contract.bid else -sel.contract.ask
        }
        val netAsk = selectedLegs.sumOf { sel ->
            if (sel.side == OrderSide.SELL_TO_OPEN) sel.contract.ask else -sel.contract.bid
        }

        val isNetCredit = signedMidCashFlow > 0.0
        val absMid = max(0.01, abs(signedMidCashFlow))
        val limitBd = BigDecimal.valueOf(absMid).setScale(2, RoundingMode.HALF_UP)

        val orderType = when (category) {
            OrderStrategyCategory.SINGLE_OPTION -> OrderType.LIMIT
            else -> if (isNetCredit) OrderType.NET_CREDIT else OrderType.NET_DEBIT
        }

        val netDelta = selectedLegs.sumOf { sel ->
            val sign = if (sel.side == OrderSide.BUY_TO_OPEN) 1.0 else -1.0
            sign * sel.contract.delta
        }
        val netTheta = selectedLegs.sumOf { sel ->
            val sign = if (sel.side == OrderSide.BUY_TO_OPEN) 1.0 else -1.0
            sign * sel.contract.thetaPerDay
        }
        val netGamma = selectedLegs.sumOf { sel ->
            val sign = if (sel.side == OrderSide.BUY_TO_OPEN) 1.0 else -1.0
            sign * sel.contract.gamma
        }
        val netVega = selectedLegs.sumOf { sel ->
            val sign = if (sel.side == OrderSide.BUY_TO_OPEN) 1.0 else -1.0
            sign * sel.contract.vegaPer1Pct
        }

        val estimatedBpr = estimateBuyingPower(category, selectedLegs, absMid, isNetCredit, underlyingSpot)

        val orderLegs = selectedLegs.map { sel ->
            val c = sel.contract
            OrderLeg(
                symbol = c.symbol,
                category = AssetCategory.OPTION,
                side = sel.side,
                quantity = 1,
                underlyingSymbol = cleanUnderlying,
                estimatedUnitPrice = BigDecimal.valueOf(max(0.01, c.mid)).setScale(2, RoundingMode.HALF_UP),
                strike = c.strike,
                expirationIsoDate = c.expirationDate.toString(),
                dte = c.dte,
                isCall = c.isCall,
                delta = c.delta
            )
        }

        val absBid = max(0.01, minOf(abs(netBid), abs(netAsk)))
        val absAsk = max(0.01, maxOf(abs(netBid), abs(netAsk)))

        val draft = OrderDraft(
            strategyCategory = category,
            underlyingSymbol = cleanUnderlying,
            orderType = orderType,
            session = TradingSession.NORMAL,
            duration = OrderDuration.DAY,
            limitPrice = limitBd,
            legs = orderLegs,
            estimatedBid = absBid,
            estimatedMid = absMid,
            estimatedAsk = absAsk,
            estimatedBpr = estimatedBpr,
            sourceLabel = "Chain Builder: $label",
            simulateImmediateMidFill = true,
            replacePriorOrderId = null,
            isEscalatedReprice = false
        )

        AssembledSpreadResult(
            strategyCategory = category,
            strategyLabel = label,
            orderType = orderType,
            limitPrice = limitBd,
            netDebitOrCreditPerShare = absMid,
            isNetCredit = isNetCredit,
            netDelta = netDelta,
            netThetaPerDay = netTheta,
            netGamma = netGamma,
            netVegaPer1Pct = netVega,
            estimatedBpr = estimatedBpr,
            draft = draft
        )
    }

    private fun classifyStrategy(legs: List<SelectedChainLeg>): Pair<OrderStrategyCategory, String> {
        return when (legs.size) {
            1 -> {
                val sel = legs.first()
                val tag = if (sel.side == OrderSide.SELL_TO_OPEN) "Short" else "Long"
                val cp = if (sel.contract.isCall) "Call" else "Put"
                OrderStrategyCategory.SINGLE_OPTION to "$tag $cp"
            }
            2 -> {
                val a = legs[0]
                val b = legs[1]
                require(a.side != b.side) {
                    "2-leg spreads require 1 BUY_TO_OPEN and 1 SELL_TO_OPEN leg."
                }
                require(a.contract.isCall == b.contract.isCall) {
                    "2-leg spreads require both legs to be the same option type (both Calls or both Puts)."
                }
                if (a.contract.expirationDate == b.contract.expirationDate) {
                    OrderStrategyCategory.VERTICAL to if (a.contract.isCall) "Call Vertical" else "Put Vertical"
                } else if (abs(a.contract.strike - b.contract.strike) < 0.01) {
                    OrderStrategyCategory.CALENDAR to if (a.contract.isCall) "Call Calendar" else "Put Calendar"
                } else {
                    val longLeg = if (a.side == OrderSide.BUY_TO_OPEN) a else b
                    val label = when {
                        longLeg.contract.isCall -> "Call Diagonal (PMCC)"
                        else -> "Put Diagonal (PMCP)"
                    }
                    OrderStrategyCategory.DIAGONAL to label
                }
            }
            4 -> {
                val exps = legs.map { it.contract.expirationDate }.distinct()
                require(exps.size == 1) { "4-leg Iron Condor requires all legs on the same expiration." }
                val puts = legs.filter { !it.contract.isCall }
                val calls = legs.filter { it.contract.isCall }
                require(puts.size == 2 && calls.size == 2) {
                    "4-leg Iron Condor requires 2 Put legs and 2 Call legs."
                }
                require(
                    puts.count { it.side == OrderSide.BUY_TO_OPEN } == 1 &&
                        calls.count { it.side == OrderSide.BUY_TO_OPEN } == 1
                ) {
                    "Iron Condor requires 1 Bull Put spread and 1 Bear Call spread."
                }
                OrderStrategyCategory.IRON_CONDOR to "Iron Condor"
            }
            else -> throw IllegalArgumentException("Unsupported ${legs.size}-leg selection; pick 1, 2, or 4 legs.")
        }
    }

    private fun estimateBuyingPower(
        category: OrderStrategyCategory,
        legs: List<SelectedChainLeg>,
        absMid: Double,
        isNetCredit: Boolean,
        underlyingSpot: Double
    ): Double {
        return when (category) {
            OrderStrategyCategory.SINGLE_OPTION -> {
                val sel = legs.first()
                if (sel.side == OrderSide.BUY_TO_OPEN) {
                    absMid * 100.0
                } else if (!sel.contract.isCall) {
                    max(absMid * 100.0, (sel.contract.strike - sel.contract.mid).coerceAtLeast(1.0) * 100.0)
                } else {
                    max(absMid * 100.0, underlyingSpot * 20.0)
                }
            }
            OrderStrategyCategory.VERTICAL -> {
                val width = abs(legs[0].contract.strike - legs[1].contract.strike) * 100.0
                if (isNetCredit) max(100.0, width - absMid * 100.0) else absMid * 100.0
            }
            OrderStrategyCategory.CALENDAR -> absMid * 100.0
            OrderStrategyCategory.DIAGONAL -> {
                if (isNetCredit) max(100.0, abs(legs[0].contract.strike - legs[1].contract.strike) * 100.0)
                else absMid * 100.0
            }
            OrderStrategyCategory.IRON_CONDOR -> {
                val puts = legs.filter { !it.contract.isCall }
                val calls = legs.filter { it.contract.isCall }
                val putWidth = abs(puts[0].contract.strike - puts[1].contract.strike) * 100.0
                val callWidth = abs(calls[0].contract.strike - calls[1].contract.strike) * 100.0
                val maxWing = max(putWidth, callWidth)
                if (isNetCredit) max(100.0, maxWing - absMid * 100.0) else absMid * 100.0
            }
            OrderStrategyCategory.SINGLE_EQUITY,
            OrderStrategyCategory.OCO_BRACKET -> absMid * 100.0
        }
    }

    companion object {
        const val MAX_LEGS = 4
    }
}
