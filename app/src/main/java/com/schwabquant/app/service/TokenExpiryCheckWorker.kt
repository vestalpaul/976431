package com.schwabquant.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.schwabquant.app.MainActivity
import com.schwabquant.app.R
import com.schwabquant.app.SentinelDeepLinkActivity
import com.schwabquant.app.core.notifications.NotificationChannels
import com.schwabquant.app.core.security.CredentialVault
import com.schwabquant.app.core.security.TokenExpiryCalculator
import com.schwabquant.app.core.security.TokenExpiryEvaluation
import com.schwabquant.app.core.security.TokenExpiryTier
import com.schwabquant.app.data.local.prefs.AppPreferencesDataStore
import com.schwabquant.app.presentation.navigation.NavRoutes
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic WorkManager worker (every 6 hours) monitoring Schwab's 7-day OAuth refresh token cliff.
 * Fires deduplicated high-priority alerts once per tier (DAY_5_WARNING <=48h, DAY_6_CRITICAL <=24h, EXPIRED)
 * with a deep-link PendingIntent straight to SchwabAuthSetupScreen.
 */
@HiltWorker
class TokenExpiryCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val credentialVault: CredentialVault,
    private val preferences: AppPreferencesDataStore
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val issuedAtMs = credentialVault.getRefreshTokenIssuedAtEpochMs()
        if (issuedAtMs <= 0L) return Result.success()

        val evaluation = TokenExpiryCalculator.evaluateRefreshToken(
            refreshTokenIssuedAtEpochMs = issuedAtMs,
            nowEpochMs = System.currentTimeMillis()
        )

        val shouldAlert = when (evaluation.tier) {
            TokenExpiryTier.DAY_5_WARNING,
            TokenExpiryTier.DAY_6_CRITICAL,
            TokenExpiryTier.EXPIRED -> true
            TokenExpiryTier.NOT_CONNECTED,
            TokenExpiryTier.HEALTHY -> false
        }
        if (!shouldAlert) return Result.success()

        if (preferences.hasAlertedForTokenTier(issuedAtMs, evaluation.tier)) {
            return Result.success()
        }

        postTokenCliffAlert(evaluation)
        preferences.recordTokenCliffAlert(issuedAtMs, evaluation.tier)
        return Result.success()
    }

    private fun postTokenCliffAlert(evaluation: TokenExpiryEvaluation) {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

        val (title, body) = when (evaluation.tier) {
            TokenExpiryTier.DAY_5_WARNING ->
                "Schwab OAuth Day 5 Reminder" to
                    "Refresh token expires in ${evaluation.formattedCountdown}. Tap to re-authenticate."
            TokenExpiryTier.DAY_6_CRITICAL ->
                "Schwab OAuth Critical (<24h Left)" to
                    "Refresh token expires in ${evaluation.formattedCountdown}. Re-authenticate now to avoid disconnect."
            TokenExpiryTier.EXPIRED ->
                "Schwab OAuth Token Expired" to
                    "7-day refresh grant expired. Tap to sign in with Schwab."
            else -> return
        }

        val tapIntent = Intent(applicationContext, SentinelDeepLinkActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAV_DESTINATION, NavRoutes.SCHWAB_AUTH_SETUP)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            REQUEST_CODE_OPEN_AUTH_SETUP,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.TOKEN_CLIFF)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID_TOKEN_CLIFF, notification)
    }

    companion object {
        const val WORK_NAME = "schwab_quant_token_expiry_check"
        private const val REQUEST_CODE_OPEN_AUTH_SETUP = 4100
        private const val NOTIFICATION_ID_TOKEN_CLIFF = 4101
    }
}
