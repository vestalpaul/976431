package com.schwabquant.app.domain.usecase

import com.schwabquant.app.data.remote.dto.SchwabQuoteNormalizer
import com.schwabquant.app.domain.model.AssetCategory
import com.schwabquant.app.domain.model.Position
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max

enum class ExDivRiskLevel {
    CRITICAL,
    WARNING,
    SAFE
}

data class ExDivRadarAlert(
    val optionSymbol: String,
    val underlyingSymbol: String,
    val strike: Double,
    val underlyingSpot: Double,
    val optionMark: Double,
    val extrinsicValue: Double,
    val dividendAmount: Double,
    val exDivDate: LocalDate,
    val daysUntilExDiv: Long,
    val shortContracts: Int,
    val riskLevel: ExDivRiskLevel,
    val rationale: String
)

/**
 * Inspects short call legs against upcoming underlying ex-dividend dates and compares
 * remaining option extrinsic value against the upcoming dividend to flag early assignment risk.
 */
class EvaluateExDivRadarUseCase @Inject constructor() {

    operator fun invoke(
        positions: List<Position>,
        today: LocalDate = LocalDate.now(NY_ZONE)
    ): List<ExDivRadarAlert> {
        return positions.mapNotNull { pos ->
            if (pos.category != AssetCategory.OPTION) return@mapNotNull null
            if (pos.quantity >= BigDecimal.ZERO) return@mapNotNull null
            if (pos.isCall != true) return@mapNotNull null

            val exDivDate = SchwabQuoteNormalizer.parseDivDate(pos.divDate) ?: return@mapNotNull null
            val divAmount = pos.divAmount?.takeIf { it > 0.0 } ?: return@mapNotNull null
            val daysUntil = ChronoUnit.DAYS.between(today, exDivDate)
            if (daysUntil < 0L) return@mapNotNull null

            val strike = pos.strike ?: return@mapNotNull null
            val spot = pos.underlyingSpot ?: return@mapNotNull null
            val mark = pos.markPrice.toDouble()
            val intrinsic = max(0.0, spot - strike)
            val extrinsic = max(0.0, mark - intrinsic)
            val shortContracts = abs(pos.quantity.toInt()).coerceAtLeast(1)

            val level = when {
                daysUntil <= 2L && extrinsic <= divAmount * 1.15 -> ExDivRiskLevel.CRITICAL
                daysUntil <= 7L && (spot >= strike || extrinsic <= divAmount * 2.0) ->
                    ExDivRiskLevel.WARNING
                else -> ExDivRiskLevel.SAFE
            }

            val rationale = when (level) {
                ExDivRiskLevel.CRITICAL ->
                    "Ex-div in ${daysUntil}d; extrinsic $${format2(extrinsic)} <= 1.15x div $${format2(divAmount)}. Early assignment imminent."
                ExDivRiskLevel.WARNING ->
                    "Ex-div in ${daysUntil}d with ${if (spot >= strike) "ITM call" else "tight extrinsic ($${format2(extrinsic)})"}; monitor roll."
                ExDivRiskLevel.SAFE ->
                    "Ex-div in ${daysUntil}d; extrinsic $${format2(extrinsic)} cushions $${format2(divAmount)} dividend."
            }

            ExDivRadarAlert(
                optionSymbol = pos.symbol,
                underlyingSymbol = pos.underlyingSymbol,
                strike = strike,
                underlyingSpot = spot,
                optionMark = mark,
                extrinsicValue = extrinsic,
                dividendAmount = divAmount,
                exDivDate = exDivDate,
                daysUntilExDiv = daysUntil,
                shortContracts = shortContracts,
                riskLevel = level,
                rationale = rationale
            )
        }.sortedWith(
            compareBy<ExDivRadarAlert> { it.riskLevel.ordinal }.thenBy { it.daysUntilExDiv }
        )
    }

    private fun format2(value: Double): String = String.format("%.2f", value)

    companion object {
        private val NY_ZONE: ZoneId = ZoneId.of("America/New_York")
    }
}
