package com.schwabquant.app.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.ui.graphics.vector.ImageVector

import com.schwabquant.app.data.remote.dto.SchwabSymbolNormalizer

object NavRoutes {
    const val DASHBOARD = "dashboard"
    const val SCANNER = "scanner"
    const val REGIME = "regime"
    const val SENTINEL = "sentinel"
    const val SETTINGS = "settings"
    const val SCHWAB_AUTH_SETUP = "schwab_auth_setup"
    const val MARKET_DETAIL_ARG_SYMBOL = "symbol"
    const val MARKET_DETAIL_ROUTE = "market/{$MARKET_DETAIL_ARG_SYMBOL}"

    fun marketDetail(rawSymbol: String): String {
        val clean = SchwabSymbolNormalizer.toDisplaySymbol(rawSymbol).ifBlank { "SPY" }
        return "market/${android.net.Uri.encode(clean)}"
    }

    val VALID_ROUTES: Set<String> = setOf(
        DASHBOARD,
        SCANNER,
        REGIME,
        SENTINEL,
        SETTINGS,
        SCHWAB_AUTH_SETUP
    )
}

enum class BottomNavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    DASHBOARD(NavRoutes.DASHBOARD, "Dashboard", Icons.Outlined.Dashboard),
    SCANNER(NavRoutes.SCANNER, "Scanner", Icons.Outlined.QueryStats),
    REGIME(NavRoutes.REGIME, "Regime", Icons.AutoMirrored.Outlined.FactCheck),
    SENTINEL(NavRoutes.SENTINEL, "Sentinel", Icons.Outlined.Shield),
    SETTINGS(NavRoutes.SETTINGS, "Settings", Icons.Outlined.Settings)
}
