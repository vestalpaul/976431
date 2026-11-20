package com.schwabquant.app.domain.model

enum class RiskSeverity {
    INFO,
    WARNING,
    CRITICAL
}

enum class RiskRuleCode(val title: String) {
    PMCC_UPSIDE_LOSS_RISK("PMCC Upside Expiration Loss Risk"),
    PMCP_WEAK_STRUCTURE("PMCP Debit > 85% of Strike Width"),
    INVERTED_DIAGONAL("Inverted Diagonal Strikes"),
    MARGIN_BORROW_WARNING("Reg-T Margin Borrow Warning"),
    CONCENTRATION_BREACH("Ticker / Sector Concentration Breach"),
    LOW_CASH_RESERVE("Settled Cash Reserve < 12%"),
    WASH_SALE_WARNING("61-Day IRC § 1091 Wash-Sale Hazard"),
    EARNINGS_OR_EXDIV_HAZARD("Earnings / Ex-Dividend Assignment Hazard"),
    PORTFOLIO_TELEMETRY_UNAVAILABLE("Live Account Balances Unavailable")
}

data class RiskWarning(
    val code: RiskRuleCode,
    val severity: RiskSeverity,
    val title: String = code.title,
    val plainEnglishImpact: String,
    val metricDetail: String,
    val requiresExplicitAck: Boolean = true
) {
    val id: String get() = code.name
    val description: String get() = plainEnglishImpact
}

/**
 * Soft-Gate Pre-Trade Risk Assessment:
 * Never enforces hard blocks (`isBlocked` stays `false`) so every trade can proceed
 * after explicit RiskWarningDialog override & audit logging.
 */
data class PreTradeRiskAssessment(
    val warnings: List<RiskWarning>,
    val isBlocked: Boolean = false,
    val blockReason: String? = null
) {
    val hasWarnings: Boolean get() = warnings.isNotEmpty()
    val requiresSoftGateDialog: Boolean get() = warnings.any { it.requiresExplicitAck }
    val overriddenCodes: List<String> get() = warnings.map { it.code.name }
}

object TickerSectorMap {
    private val SECTOR_BY_TICKER = mapOf(
        "SPY" to "Broad Index",
        "QQQ" to "Broad Index",
        "IWM" to "Broad Index",
        "DIA" to "Broad Index",
        "VTI" to "Broad Index",
        "VOO" to "Broad Index",
        "XLK" to "Technology",
        "SMH" to "Technology",
        "AAPL" to "Technology",
        "MSFT" to "Technology",
        "NVDA" to "Technology",
        "GOOG" to "Technology",
        "META" to "Technology",
        "AMD" to "Technology",
        "AVGO" to "Technology",
        "XLF" to "Financials",
        "JPM" to "Financials",
        "BRK.B" to "Financials",
        "BRK/B" to "Financials",
        "XLE" to "Energy",
        "XOM" to "Energy",
        "XLV" to "Healthcare",
        "UNH" to "Healthcare",
        "XLI" to "Industrials",
        "AMZN" to "Consumer",
        "TSLA" to "Consumer",
        "TLT" to "Fixed Income",
        "HYG" to "Fixed Income",
        "LQD" to "Fixed Income",
        "GLD" to "Commodities",
        "SLV" to "Commodities"
    )

    fun sectorFor(symbol: String): String =
        SECTOR_BY_TICKER[symbol.trim().uppercase()] ?: "Other"
}
