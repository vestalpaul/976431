package com.schwabquant.app.core.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

enum class BiometricHardwareStatus {
    READY,
    NOT_ENROLLED,
    NO_HARDWARE,
    UNAVAILABLE
}

sealed interface BiometricAuthResult {
    data object Succeeded : BiometricAuthResult
    data object Cancelled : BiometricAuthResult
    data class Failed(val errorCode: Int, val message: String) : BiometricAuthResult
}

interface BiometricGate {
    fun canAuthenticate(): Boolean
    fun getHardwareStatus(): BiometricHardwareStatus
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String
    ): BiometricAuthResult
}

/**
 * Hardware-backed BiometricPrompt wrapper enforcing user confirmation prior to live order routing
 * or mode escalation. Handles API 28-29 vs API 30+ authenticator rules safely.
 */
@Singleton
class BiometricGateImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : BiometricGate {

    override fun canAuthenticate(): Boolean = getHardwareStatus() == BiometricHardwareStatus.READY

    override fun getHardwareStatus(): BiometricHardwareStatus {
        val manager = BiometricManager.from(context)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
        return when (manager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricHardwareStatus.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricHardwareStatus.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricHardwareStatus.NO_HARDWARE
            else -> BiometricHardwareStatus.UNAVAILABLE
        }
    }

    override suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String
    ): BiometricAuthResult = suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) {
                    continuation.resume(BiometricAuthResult.Succeeded)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) {
                    val outcome = if (
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        BiometricAuthResult.Cancelled
                    } else {
                        BiometricAuthResult.Failed(errorCode, errString.toString())
                    }
                    continuation.resume(outcome)
                }
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            promptInfoBuilder.setNegativeButtonText("Cancel")
        }

        val promptInfo = promptInfoBuilder.build()
        executor.execute {
            prompt.authenticate(promptInfo)
        }

        continuation.invokeOnCancellation {
            executor.execute { prompt.cancelAuthentication() }
        }
    }
}
