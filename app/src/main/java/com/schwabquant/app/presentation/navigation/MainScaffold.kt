package com.schwabquant.app.presentation.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.schwabquant.app.BuildConfig
import com.schwabquant.app.core.security.TokenExpiryTier
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.repository.AuthSessionState
import com.schwabquant.app.presentation.dashboard.DashboardScreen
import com.schwabquant.app.presentation.market.MarketDetailScreen
import com.schwabquant.app.presentation.market.TickerPickerSheet
import com.schwabquant.app.presentation.regime.RegimeChecklistScreen
import com.schwabquant.app.presentation.scanner.ScannerScreen
import com.schwabquant.app.presentation.sentinel.OrderTicketSheet
import com.schwabquant.app.presentation.sentinel.OrderTicketViewModel
import com.schwabquant.app.presentation.sentinel.SentinelScreen
import com.schwabquant.app.presentation.settings.SchwabAuthSetupScreen
import com.schwabquant.app.presentation.settings.SettingsScreen
import com.schwabquant.app.presentation.theme.SchwabQuantTheme

@Composable
fun MainScaffold(
    executionMode: ExecutionMode,
    authSession: AuthSessionState,
    pendingNavRoute: String? = null,
    onPendingNavRouteConsumed: () -> Unit = {},
    pendingNotice: String? = null,
    onPendingNoticeConsumed: () -> Unit = {},
    orderTicketViewModel: OrderTicketViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isAuthSetupScreen = currentDestination?.route == NavRoutes.SCHWAB_AUTH_SETUP
    val isMarketDetailScreen = currentDestination?.route == NavRoutes.MARKET_DETAIL_ROUTE
    val ticketState by orderTicketViewModel.uiState.collectAsStateWithLifecycle()
    val globalSnackbarHostState = remember { SnackbarHostState() }
    var showTickerPicker by rememberSaveable { mutableStateOf(false) }

    val openMarketDetail: (String) -> Unit = { rawSymbol ->
        navController.navigate(NavRoutes.marketDetail(rawSymbol)) {
            launchSingleTop = true
        }
    }

    LaunchedEffect(ticketState.globalMessage) {
        val msg = ticketState.globalMessage ?: return@LaunchedEffect
        globalSnackbarHostState.showSnackbar(msg)
        orderTicketViewModel.consumeGlobalMessage()
    }

    LaunchedEffect(pendingNotice) {
        val msg = pendingNotice ?: return@LaunchedEffect
        globalSnackbarHostState.showSnackbar(msg)
        onPendingNoticeConsumed()
    }

    LaunchedEffect(pendingNavRoute) {
        val target = pendingNavRoute ?: return@LaunchedEffect
        if (target in NavRoutes.VALID_ROUTES) {
            navController.navigate(target) {
                launchSingleTop = true
            }
        }
        onPendingNavRouteConsumed()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = globalSnackbarHostState) },
        topBar = {
            PersistentSafetyTopBar(
                executionMode = executionMode,
                authSession = authSession,
                onSearchChartClick = { showTickerPicker = true },
                onTokenPillClick = {
                    if (!isAuthSetupScreen) {
                        navController.navigate(NavRoutes.SCHWAB_AUTH_SETUP) {
                            launchSingleTop = true
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!isAuthSetupScreen && !isMarketDetailScreen) {
                NavigationBar {
                    BottomNavDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label
                                )
                            },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.DASHBOARD,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(NavRoutes.DASHBOARD) {
                DashboardScreen(onOpenMarketDetail = openMarketDetail)
            }
            composable(NavRoutes.SCANNER) {
                ScannerScreen(onOpenMarketDetail = openMarketDetail)
            }
            composable(NavRoutes.REGIME) { RegimeChecklistScreen() }
            composable(NavRoutes.SENTINEL) { SentinelScreen() }
            composable(NavRoutes.SETTINGS) {
                SettingsScreen(
                    onOpenAuthSetup = {
                        navController.navigate(NavRoutes.SCHWAB_AUTH_SETUP) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(NavRoutes.SCHWAB_AUTH_SETUP) {
                SchwabAuthSetupScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = NavRoutes.MARKET_DETAIL_ROUTE,
                arguments = listOf(
                    navArgument(NavRoutes.MARKET_DETAIL_ARG_SYMBOL) { type = NavType.StringType }
                )
            ) {
                MarketDetailScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }

    if (showTickerPicker) {
        TickerPickerSheet(
            onDismiss = { showTickerPicker = false },
            onSelectTicker = openMarketDetail
        )
    }

    if (ticketState.isVisible) {
        OrderTicketSheet(
            state = ticketState,
            viewModel = orderTicketViewModel
        )
    }
}

@Composable
private fun PersistentSafetyTopBar(
    executionMode: ExecutionMode,
    authSession: AuthSessionState,
    onSearchChartClick: () -> Unit,
    onTokenPillClick: () -> Unit
) {
    val isLive = executionMode == ExecutionMode.LIVE
    val bannerBg = if (isLive) {
        SchwabQuantTheme.colors.liveBannerBg
    } else {
        SchwabQuantTheme.colors.shadowBannerBg
    }
    val bannerFg = if (isLive) {
        SchwabQuantTheme.colors.liveBannerFg
    } else {
        SchwabQuantTheme.colors.shadowBannerFg
    }

    val (pillText, pillColor) = formatTokenCountdownPill(authSession)

    Surface(
        color = bannerBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isLive) {
                        "LIVE SCHWAB EXECUTION • ${BuildConfig.APP_VERSION_LABEL}"
                    } else {
                        "SHADOW MODE (PAPER) • ${BuildConfig.APP_VERSION_LABEL}"
                    },
                    style = SchwabQuantTheme.numericTypography.badgeMono,
                    color = bannerFg,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                        modifier = Modifier.clickable(onClick = onSearchChartClick)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Open Chart & Options Chain",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Chart",
                                style = SchwabQuantTheme.numericTypography.badgeMono,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, pillColor),
                        modifier = Modifier.clickable(onClick = onTokenPillClick)
                    ) {
                        Text(
                            text = pillText,
                            style = SchwabQuantTheme.numericTypography.badgeMono,
                            color = pillColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun formatTokenCountdownPill(authSession: AuthSessionState): Pair<String, Color> {
    val colors = SchwabQuantTheme.colors
    return when (authSession.expiryTier) {
        TokenExpiryTier.NOT_CONNECTED -> "Not Connected" to MaterialTheme.colorScheme.onSurfaceVariant
        TokenExpiryTier.HEALTHY -> authSession.countdownLabel to colors.tokenHealthy
        TokenExpiryTier.DAY_5_WARNING -> authSession.countdownLabel to colors.tokenWarning
        TokenExpiryTier.DAY_6_CRITICAL -> authSession.countdownLabel to colors.tokenCritical
        TokenExpiryTier.EXPIRED -> "Expired" to colors.tokenCritical
    }
}
