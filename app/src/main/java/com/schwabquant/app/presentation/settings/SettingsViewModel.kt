package com.schwabquant.app.presentation.settings

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schwabquant.app.core.security.BiometricAuthResult
import com.schwabquant.app.core.security.BiometricGate
import com.schwabquant.app.core.security.BiometricHardwareStatus
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.domain.model.ExecutionMode
import com.schwabquant.app.domain.repository.AuthRepository
import com.schwabquant.app.domain.repository.AuthSessionState
import com.schwabquant.app.domain.repository.StoredCredentialsForm
import com.schwabquant.app.presentation.theme.AppThemeType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val selectedTheme: AppThemeType = AppThemeType.OLED_NIGHT,
    val executionMode: ExecutionMode = ExecutionMode.SHADOW,
    val antiStopHuntSeconds: Int = 15,
    val authSession: AuthSessionState = AuthSessionState(),
    val biometricStatus: BiometricHardwareStatus = BiometricHardwareStatus.UNAVAILABLE,
    val showLiveModeConfirmDialog: Boolean = false,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferencesDataStore,
    private val authRepository: AuthRepository,
    private val biometricGate: BiometricGate
) : ViewModel() {

    private val biometricStatusState = MutableStateFlow(biometricGate.getHardwareStatus())
    private val showLiveDialogState = MutableStateFlow(false)
    private val isBusyState = MutableStateFlow(false)
    private val statusMessageState = MutableStateFlow<String?>(null)
    private val errorMessageState = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences.selectedTheme,
        preferences.executionMode,
        preferences.antiStopHuntDelaySeconds,
        authRepository.observeAuthState(),
        biometricStatusState,
        showLiveDialogState,
        isBusyState,
        statusMessageState,
        errorMessageState
    ) { values ->
        SettingsUiState(
            selectedTheme = values[0] as AppThemeType,
            executionMode = values[1] as ExecutionMode,
            antiStopHuntSeconds = values[2] as Int,
            authSession = values[3] as AuthSessionState,
            biometricStatus = values[4] as BiometricHardwareStatus,
            showLiveModeConfirmDialog = values[5] as Boolean,
            isBusy = values[6] as Boolean,
            statusMessage = values[7] as String?,
            errorMessage = values[8] as String?
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    fun loadStoredCredentialsForm(): StoredCredentialsForm =
        authRepository.getStoredCredentialsForm()

    fun refreshBiometricStatus() {
        biometricStatusState.value = biometricGate.getHardwareStatus()
    }

    fun selectTheme(theme: AppThemeType) {
        viewModelScope.launch {
            preferences.setSelectedTheme(theme)
        }
    }

    fun onExecutionModeToggleRequested(enableLive: Boolean) {
        errorMessageState.value = null
        if (!enableLive) {
            showLiveDialogState.value = false
            viewModelScope.launch {
                preferences.setLiveMode(false)
                statusMessageState.value = "Switched to Shadow Mode (paper execution)."
            }
        } else {
            showLiveDialogState.value = true
        }
    }

    fun dismissLiveModeConfirmDialog() {
        showLiveDialogState.value = false
    }

    fun confirmEnableLiveMode(activity: FragmentActivity?) {
        showLiveDialogState.value = false
        if (activity == null) {
            errorMessageState.value = "Unable to resolve FragmentActivity for BiometricPrompt."
            return
        }

        val hardwareStatus = biometricGate.getHardwareStatus()
        biometricStatusState.value = hardwareStatus
        if (hardwareStatus != BiometricHardwareStatus.READY) {
            errorMessageState.value =
                "Biometric or device credential security must be enrolled before enabling Live Trading."
            return
        }

        viewModelScope.launch {
            when (
                val outcome = biometricGate.authenticate(
                    activity = activity,
                    title = "Enable Live Schwab Execution",
                    subtitle = "Authenticate to allow real brokerage order routing."
                )
            ) {
                BiometricAuthResult.Succeeded -> {
                    preferences.setLiveMode(true)
                    errorMessageState.value = null
                    statusMessageState.value = "LIVE SCHWAB EXECUTION armed."
                }
                BiometricAuthResult.Cancelled -> {
                    errorMessageState.value = "Biometric confirmation cancelled. Shadow Mode retained."
                }
                is BiometricAuthResult.Failed -> {
                    errorMessageState.value = "Biometric failed (${outcome.message}). Shadow Mode retained."
                }
            }
        }
    }

    fun saveSchwabCredentials(appKey: String, appSecret: String, callbackUrl: String) {
        viewModelScope.launch {
            errorMessageState.value = null
            statusMessageState.value = null
            runCatching {
                authRepository.saveAppCredentials(appKey, appSecret, callbackUrl)
            }.onSuccess {
                statusMessageState.value = "Credentials encrypted in Android Keystore Tink vault."
            }.onFailure { err ->
                errorMessageState.value =
                    err.message ?: "Failed to encrypt credentials in Android Keystore."
            }
        }
    }

    fun launchSchwabLoginUrl(onUrlReady: (String) -> Unit) {
        viewModelScope.launch {
            errorMessageState.value = null
            authRepository.buildAuthorizationUrl()
                .onSuccess { url -> onUrlReady(url) }
                .onFailure { err -> errorMessageState.value = err.message ?: "Failed to build OAuth URL." }
        }
    }

    fun exchangeAndVerifyConnection(rawCodeOrUrl: String) {
        viewModelScope.launch {
            isBusyState.value = true
            errorMessageState.value = null
            statusMessageState.value = null

            val exchangeResult = authRepository.exchangeAuthorizationCode(rawCodeOrUrl)
            if (exchangeResult.isFailure) {
                isBusyState.value = false
                errorMessageState.value =
                    exchangeResult.exceptionOrNull()?.message ?: "OAuth token exchange failed."
                return@launch
            }

            authRepository.verifyConnectionAndFetchAccounts()
                .onSuccess { info ->
                    statusMessageState.value =
                        "Connected (${info.maskedAccountNumber}, ${info.accountCount} account(s) verified)."
                }
                .onFailure { err ->
                    errorMessageState.value =
                        "Token saved, but account verification failed: ${err.message}"
                }
            isBusyState.value = false
        }
    }

    fun verifyExistingConnection() {
        viewModelScope.launch {
            isBusyState.value = true
            errorMessageState.value = null
            authRepository.verifyConnectionAndFetchAccounts()
                .onSuccess { info ->
                    statusMessageState.value =
                        "Verified Schwab connection (${info.maskedAccountNumber})."
                }
                .onFailure { err ->
                    errorMessageState.value = err.message ?: "Verification failed."
                }
            isBusyState.value = false
        }
    }

    fun clearTokensOnly() {
        viewModelScope.launch {
            authRepository.clearTokensOnly()
            errorMessageState.value = null
            statusMessageState.value = "OAuth tokens cleared. App Key & Secret retained."
        }
    }

    fun wipeAllCredentials() {
        viewModelScope.launch {
            authRepository.wipeAllCredentials()
            errorMessageState.value = null
            statusMessageState.value = "All Schwab credentials and tokens wiped from Keystore vault."
        }
    }

    fun clearFeedbackMessages() {
        statusMessageState.value = null
        errorMessageState.value = null
    }
}
