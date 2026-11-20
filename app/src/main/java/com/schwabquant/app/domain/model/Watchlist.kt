package com.schwabquant.app.domain.model

data class Watchlist(
    val id: Long,
    val name: String,
    val tickers: List<String>,
    val isDefault: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

object LiquidUniverse {
    const val CORE_25_NAME = "Core 25 Liquid"

    val CORE_25_LIQUID_TICKERS: List<String> = listOf(
        "SPY", "QQQ", "IWM", "DIA", "XLK",
        "XLF", "XLE", "XLV", "XLI", "SMH",
        "TLT", "GLD", "AAPL", "MSFT", "NVDA",
        "GOOG", "AMZN", "META", "TSLA", "AMD",
        "AVGO", "JPM", "BRK.B", "UNH", "XOM"
    )

    val KNOWN_LIQUID_ETFS: Set<String> = setOf(
        "SPY", "QQQ", "IWM", "DIA", "XLK", "XLF", "XLE", "XLV", "XLI", "SMH",
        "TLT", "GLD", "HYG", "LQD", "XLU", "XLP", "XLY", "XLC", "XLB", "XLRE",
        "SLV", "USO", "EEM", "EFA", "ARKK", "VTI", "VOO", "TQQQ", "SQQQ"
    )

    fun isEtf(symbol: String): Boolean = symbol.trim().uppercase() in KNOWN_LIQUID_ETFS

    fun parseTickersCsv(csv: String): List<String> =
        csv.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
}
