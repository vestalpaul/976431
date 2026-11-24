package com.schwabquant.app.domain.model

enum class PmccSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}

enum class PmccBasisMode {
    LEDGER_ADJUSTED,
    OPEN_LEG_COST,
    MARK_TO_MARKET
}

data class PmccOptionLeg(
    val symbol: String,
    val underlying: String,
    val strike: Double,
    val dte: Int,
    val quantity: Double,
    val avgCost: Double,
    val mark: Double,
    val delta: Double,
    val isCall: Boolean = true,
    val expirationIsoDate: String? = null
)

data class PmccValidatorConfig(
    val minLeapsDte: Int = 120,
    val leapsTargetDelta: Double = 0.75,
    val leapsMinDelta: Double = 0.65,
    val netDeltaMin: Double = 0.15,
    val shortItmRollDelta: Double = 0.65,
    val shortItmRollDte: Int = 28,
    val thinBufferFloor: Double = 0.50,
    val thinBufferPct: Double = 0.02
)

data class PmccFinding(
    val code: String,
    val severity: PmccSeverity,
    val message: String,
    val underlying: String,
    val pairLabel: String? = null,
    val supersededBy: String? = null
)

data class PmccPair(
    val underlying: String,
    val longLeg: PmccOptionLeg,
    val shortLeg: PmccOptionLeg,
    val contracts: Int,
    val strikeWidth: Double,
    val netDebitBasis: Double,
    val basisMode: PmccBasisMode,
    val closedRollCredits: Double = 0.0,
    val widthBufferBasis: Double,
    val widthBufferMark: Double,
    val netDelta: Double,
    val findings: List<PmccFinding> = emptyList(),
    val passed: Boolean
)

data class PmccValidationReport(
    val pairs: List<PmccPair>,
    val findings: List<PmccFinding>,
    val coveredByEquityShorts: List<PmccOptionLeg> = emptyList(),
    val uncoveredShortCalls: List<PmccOptionLeg> = emptyList(),
    val unhedgedLeaps: List<PmccOptionLeg> = emptyList(),
    val passed: Boolean
)
