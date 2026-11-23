package com.schwabquant.app.presentation.settings

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schwabquant.app.core.security.OAuthCallbackParser
import com.schwabquant.app.core.security.TokenExpiryTier
import com.schwabquant.app.presentation.theme.SchwabQuantTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SchwabAuthSetupScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val initialForm = remember { viewModel.loadStoredCredentialsForm() }
    var appKey by remember { mutableStateOf(initialForm.appKey) }
    var appSecret by remember { mutableStateOf(initialForm.appSecret) }
    var callbackUrl by rememberSaveable { mutableStateOf(initialForm.callbackUrl) }
    var callbackInput by remember { mutableStateOf("") }

    LaunchedEffect(state.authSession.hasAppCredentials, state.authSession.vaultUnavailableTransient) {
        val refreshed = viewModel.loadStoredCredentialsForm()
        if (appKey.isBlank() && refreshed.appKey.isNotBlank()) {
            appKey = refreshed.appKey
        }
        if (appSecret.isBlank() && refreshed.appSecret.isNotBlank()) {
            appSecret = refreshed.appSecret
        }
    }

    var showAppKey by rememberSaveable { mutableStateOf(false) }
    var showAppSecret by rememberSaveable { mutableStateOf(false) }
    var awaitingOAuthReturn by rememberSaveable { mutableStateOf(false) }
    var showWipeConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var clipboardDetectNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.statusMessage, state.errorMessage, state.isBusy) {
        if (state.statusMessage != null && state.errorMessage == null && !state.isBusy) {
            callbackInput = ""
            clipboardDetectNotice = null
        }
    }

    // Scoped clipboard inspection: ONLY fires on ON_RESUME after user launched Schwab Custom Tab.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingOAuthReturn) {
                awaitingOAuthReturn = false
                val clipText = readPrimaryClipboardText(context)
                if (OAuthCallbackParser.looksLikeSchwabCallback(clipText)) {
                    callbackInput = clipText.orEmpty()
                    clipboardDetectNotice = "Detected Schwab redirect URL from clipboard."
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Schwab OAuth 2.0 Setup",
                style = MaterialTheme.typography.headlineMedium
            )
            TextButton(onClick = onNavigateBack) {
                Text("Done")
            }
        }

        if (state.authSession.vaultCorruptionDetected || state.authSession.vaultUnavailableTransient) {
            val isTransientOnly = !state.authSession.vaultCorruptionDetected &&
                state.authSession.vaultUnavailableTransient
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = SchwabQuantTheme.colors.liveBannerBg
                ),
                border = BorderStroke(1.dp, SchwabQuantTheme.colors.loss),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = SchwabQuantTheme.colors.loss
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isTransientOnly) {
                                "Keystore Temporarily Unavailable"
                            } else {
                                "Keystore Vault Reset Required"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = SchwabQuantTheme.colors.liveBannerFg
                        )
                    }
                    Text(
                        text = if (isTransientOnly) {
                            "Android Keystore reported a transient hardware or I/O error. Unlock your device and tap Verify Connection below before resetting the vault."
                        } else {
                            "Android Keystore integrity check detected a stale encryption keyset. Reset and re-enter your Schwab credentials."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = SchwabQuantTheme.colors.liveBannerFg
                    )
                    Button(
                        onClick = {
                            viewModel.wipeAllCredentials()
                            appKey = ""
                            appSecret = ""
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SchwabQuantTheme.colors.loss
                        )
                    ) {
                        Text("Reset Encrypted Vault")
                    }
                }
            }
        }

        ConnectionStatusSummaryCard(
            state = state,
            isBusy = state.isBusy,
            onVerifyNow = viewModel::verifyExistingConnection
        )

        // Step 1: Developer App Credentials
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Step 1 — Schwab App Credentials",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Encrypted with Tink AES256-GCM & Android Keystore.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = appKey,
                    onValueChange = { appKey = it },
                    label = { Text("App Key (Client ID)") },
                    singleLine = true,
                    visualTransformation = if (showAppKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showAppKey = !showAppKey }) {
                            Icon(
                                imageVector = if (showAppKey) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = "Toggle App Key visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = appSecret,
                    onValueChange = { appSecret = it },
                    label = { Text("App Secret") },
                    singleLine = true,
                    visualTransformation = if (showAppSecret) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showAppSecret = !showAppSecret }) {
                            Icon(
                                imageVector = if (showAppSecret) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = "Toggle App Secret visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = callbackUrl,
                    onValueChange = { callbackUrl = it },
                    label = { Text("Registered Callback URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        viewModel.saveSchwabCredentials(appKey, appSecret, callbackUrl)
                    },
                    enabled = appKey.isNotBlank() && appSecret.isNotBlank() && callbackUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save to Vault")
                }
            }
        }

        // Step 2: Launch Schwab Login
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Step 2 — Authorize in Schwab",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Sign in and approve account access. After redirect to 127.0.0.1, copy the full address bar URL and return here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        viewModel.launchSchwabLoginUrl { authUrl ->
                            awaitingOAuthReturn = true
                            val customTabs = CustomTabsIntent.Builder().build()
                            customTabs.launchUrl(context, Uri.parse(authUrl))
                        }
                    },
                    enabled = state.authSession.hasAppCredentials && !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Launch Schwab Login (Chrome Custom Tab)")
                }
            }
        }

        // Step 3: Paste Redirect URL / Auth Code & Exchange
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Step 3 — Paste Redirect URL & Verify",
                    style = MaterialTheme.typography.titleMedium
                )

                OutlinedTextField(
                    value = callbackInput,
                    onValueChange = {
                        callbackInput = it
                        clipboardDetectNotice = null
                    },
                    label = { Text("Redirect URL or Auth Code") },
                    placeholder = { Text("https://127.0.0.1/?code=C0...") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clip = readPrimaryClipboardText(context)
                            if (!clip.isNullOrBlank()) {
                                callbackInput = clip
                                clipboardDetectNotice = "Pasted from clipboard."
                            } else {
                                clipboardDetectNotice = "Clipboard is empty."
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Paste from Clipboard")
                    }
                }

                clipboardDetectNotice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Button(
                    onClick = { viewModel.exchangeAndVerifyConnection(callbackInput) },
                    enabled = callbackInput.isNotBlank() &&
                        state.authSession.hasAppCredentials &&
                        !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Exchanging & Verifying...")
                    } else {
                        Text("Exchange & Verify Connection")
                    }
                }
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = viewModel::clearTokensOnly,
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear Tokens Only")
            }
            OutlinedButton(
                onClick = { showWipeConfirmDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SchwabQuantTheme.colors.loss
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Wipe All Vault")
            }
        }
    }

    if (showWipeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            title = { Text("Wipe All Schwab Credentials?") },
            text = {
                Text("This permanently erases your stored App Key, App Secret, OAuth tokens, and cached account hash from the encrypted vault.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWipeConfirmDialog = false
                        viewModel.wipeAllCredentials()
                        appKey = ""
                        appSecret = ""
                        callbackInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SchwabQuantTheme.colors.loss
                    )
                ) {
                    Text("Wipe Vault")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ConnectionStatusSummaryCard(
    state: SettingsUiState,
    isBusy: Boolean,
    onVerifyNow: () -> Unit
) {
    val auth = state.authSession
    val statusColor = when (auth.expiryTier) {
        TokenExpiryTier.HEALTHY -> SchwabQuantTheme.colors.tokenHealthy
        TokenExpiryTier.DAY_5_WARNING -> SchwabQuantTheme.colors.tokenWarning
        TokenExpiryTier.DAY_6_CRITICAL,
        TokenExpiryTier.EXPIRED -> SchwabQuantTheme.colors.tokenCritical
        TokenExpiryTier.NOT_CONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (auth.expiryTier == TokenExpiryTier.HEALTHY) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Filled.Warning
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Connection: ${auth.countdownLabel}",
                        style = SchwabQuantTheme.numericTypography.metricValue,
                        color = statusColor
                    )
                }

                auth.maskedAccountNumber?.let { masked ->
                    Text(
                        text = masked,
                        style = SchwabQuantTheme.numericTypography.badgeMono,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            auth.accessTokenExpiresAtEpochMs?.let { accessExpiry ->
                Text(
                    text = "Access Token Expires: ${formatEpoch(accessExpiry)}",
                    style = SchwabQuantTheme.numericTypography.tableCell,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            auth.refreshTokenExpiresAtEpochMs?.let { refreshExpiry ->
                Text(
                    text = "7-Day Refresh Cliff: ${formatEpoch(refreshExpiry)}",
                    style = SchwabQuantTheme.numericTypography.tableCell,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (auth.hasAppCredentials && auth.expiryTier != TokenExpiryTier.NOT_CONNECTED) {
                TextButton(
                    onClick = onVerifyNow,
                    enabled = !isBusy
                ) {
                    Text("Verify Account Connection")
                }
            }
        }
    }
}

private fun readPrimaryClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
}

private val TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm z").withZone(ZoneId.systemDefault())

private fun formatEpoch(epochMs: Long): String =
    TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(epochMs))
