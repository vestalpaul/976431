package com.schwabquant.app.presentation.settings

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schwabquant.app.BuildConfig
import com.schwabquant.app.core.security.BiometricHardwareStatus
import com.schwabquant.app.core.security.TokenExpiryTier
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.presentation.theme.AppThemeType
import com.schwabquant.app.presentation.theme.SchwabQuantPalettes
import com.schwabquant.app.presentation.theme.SchwabQuantTheme

@Composable
fun SettingsScreen(
    onOpenAuthSetup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var readinessRefreshTick by remember { mutableIntStateOf(0) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        readinessRefreshTick++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBiometricStatus()
                readinessRefreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationsGranted = remember(readinessRefreshTick) {
        isNotificationPermissionGranted(context)
    }
    val batteryUnrestricted = remember(readinessRefreshTick) {
        isBatteryOptimizationIgnored(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Settings & Safety Controls",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = BuildConfig.APP_VERSION_LABEL,
                    style = SchwabQuantTheme.numericTypography.badgeMono,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Execution Mode Switch (SHADOW MODE vs LIVE SCHWAB EXECUTION)
        val isLive = state.executionMode == ExecutionMode.LIVE
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isLive) {
                    SchwabQuantTheme.colors.liveBannerBg
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ),
            border = BorderStroke(
                width = 1.dp,
                color = if (isLive) {
                    SchwabQuantTheme.colors.loss
                } else {
                    SchwabQuantTheme.colors.borderSubtle
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isLive) "LIVE SCHWAB EXECUTION" else "SHADOW MODE (PAPER)",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isLive) {
                            SchwabQuantTheme.colors.liveBannerFg
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = if (isLive) {
                            "Real brokerage order routing is ARMED. Tap switch to drop back to Shadow Mode immediately."
                        } else {
                            "Default safe mode. Upgrading to LIVE requires dialog + BiometricPrompt verification."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = isLive,
                    onCheckedChange = viewModel::onExecutionModeToggleRequested
                )
            }
        }

        state.statusMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodyMedium,
                color = SchwabQuantTheme.colors.profit
            )
        }
        state.errorMessage?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodyMedium,
                color = SchwabQuantTheme.colors.loss
            )
        }

        // Standalone Device Readiness Checklist Card
        DeviceReadinessChecklistCard(
            state = state,
            notificationsGranted = notificationsGranted,
            batteryUnrestricted = batteryUnrestricted,
            onOpenAuthSetup = onOpenAuthSetup,
            onRequestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings(context)
                }
            },
            onRequestBatteryExemption = { requestBatteryOptimizationExemption(context) },
            onOpenBiometricSettings = { openSecuritySettings(context) }
        )

        // 5-Theme Instant Hot-Swap Selector
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Appearance — 5-Theme Quant Engine",
                style = MaterialTheme.typography.titleMedium
            )
            AppThemeType.entries.forEach { theme ->
                val selected = theme == state.selectedTheme
                val scheme = SchwabQuantPalettes.colorSchemeFor(theme)
                val ext = SchwabQuantPalettes.extendedColorsFor(theme)

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    border = BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            SchwabQuantTheme.colors.borderSubtle
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectTheme(theme) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = theme.displayName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = theme.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ThemeSwatchDot(color = scheme.background)
                            ThemeSwatchDot(color = scheme.primary)
                            ThemeSwatchDot(color = ext.profit)
                            ThemeSwatchDot(color = ext.loss)
                            if (selected) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected theme",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // App Build & Version Telemetry Footer
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, SchwabQuantTheme.colors.borderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "App Build & Version",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "SchwabQuant ${BuildConfig.APP_VERSION_LABEL} (Build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = BuildConfig.BUILD_TYPE.uppercase(),
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    if (state.showLiveModeConfirmDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissLiveModeConfirmDialog,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = SchwabQuantTheme.colors.loss
                )
            },
            title = { Text("Enable Live Schwab Execution?") },
            text = {
                Text(
                    "Live mode unlocks real brokerage order routing to Schwab Trader API. " +
                        "You must confirm with device biometrics before arming Live Execution."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmEnableLiveMode(context.findFragmentActivity())
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SchwabQuantTheme.colors.loss
                    )
                ) {
                    Text("Authenticate & Arm")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissLiveModeConfirmDialog) {
                    Text("Stay in Shadow")
                }
            }
        )
    }
}

@Composable
private fun DeviceReadinessChecklistCard(
    state: SettingsUiState,
    notificationsGranted: Boolean,
    batteryUnrestricted: Boolean,
    onOpenAuthSetup: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenBiometricSettings: () -> Unit
) {
    val auth = state.authSession
    val oauthReady = auth.expiryTier == TokenExpiryTier.HEALTHY
    val oauthBadge = when (auth.expiryTier) {
        TokenExpiryTier.NOT_CONNECTED -> "Not Connected"
        TokenExpiryTier.EXPIRED -> "Expired"
        else -> auth.countdownLabel + (auth.maskedAccountNumber?.let { " ($it)" } ?: "")
    }

    val biometricReady = state.biometricStatus == BiometricHardwareStatus.READY
    val biometricBadge = when (state.biometricStatus) {
        BiometricHardwareStatus.READY -> "Ready"
        BiometricHardwareStatus.NOT_ENROLLED -> "Not Enrolled"
        BiometricHardwareStatus.NO_HARDWARE -> "No Hardware"
        BiometricHardwareStatus.UNAVAILABLE -> "Unavailable"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Standalone Device Readiness",
                style = MaterialTheme.typography.titleMedium
            )

            ReadinessRow(
                title = "Schwab OAuth 2.0",
                statusText = oauthBadge,
                isReady = oauthReady,
                actionLabel = if (oauthReady) "Manage" else "Setup / Re-Auth",
                onAction = onOpenAuthSetup
            )
            HorizontalDivider(color = SchwabQuantTheme.colors.borderSubtle)

            ReadinessRow(
                title = "Notifications (Token Cliff & Alerts)",
                statusText = if (notificationsGranted) "Granted" else "Permission Needed",
                isReady = notificationsGranted,
                actionLabel = if (notificationsGranted) null else "Grant",
                onAction = onRequestNotifications
            )
            HorizontalDivider(color = SchwabQuantTheme.colors.borderSubtle)

            ReadinessRow(
                title = "Battery Optimization Exemption",
                statusText = if (batteryUnrestricted) "Unrestricted" else "Restricted",
                isReady = batteryUnrestricted,
                actionLabel = if (batteryUnrestricted) null else "Exempt",
                onAction = onRequestBatteryExemption
            )
            HorizontalDivider(color = SchwabQuantTheme.colors.borderSubtle)

            ReadinessRow(
                title = "Biometric Security Gate",
                statusText = biometricBadge,
                isReady = biometricReady,
                actionLabel = if (state.biometricStatus == BiometricHardwareStatus.NOT_ENROLLED) {
                    "Enroll"
                } else {
                    null
                },
                onAction = onOpenBiometricSettings
            )

            OutlinedButton(
                onClick = onOpenAuthSetup,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Schwab OAuth Setup")
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    title: String,
    statusText: String,
    isReady: Boolean,
    actionLabel: String?,
    onAction: () -> Unit
) {
    val badgeColor = if (isReady) {
        SchwabQuantTheme.colors.profit
    } else {
        SchwabQuantTheme.colors.warning
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Surface(
                color = badgeColor.copy(alpha = 0.14f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = statusText,
                    style = SchwabQuantTheme.numericTypography.badgeMono,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        } else {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Ready",
                tint = SchwabQuantTheme.colors.profit,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ThemeSwatchDot(color: Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color)
    )
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

private fun isNotificationPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestBatteryOptimizationExemption(context: Context) {
    val directIntent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    runCatching { context.startActivity(directIntent) }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(intent) }
}

private fun openSecuritySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }
}
