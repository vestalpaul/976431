package com.schwabquant.app.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val SENTINEL_SERVICE = "schwab_quant_sentinel_service"
    const val TOKEN_CLIFF = "schwab_quant_token_cliff"
    const val DISCONNECT_ALARM = "schwab_quant_disconnect_alarm"
    const val OVERNIGHT_PRICE_ALERT = "schwab_quant_overnight_price_alert"
    const val OVERNIGHT_ALERT = OVERNIGHT_PRICE_ALERT
    const val EXECUTION_FILL = "schwab_quant_execution_fill"

    fun createAll(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val channels = listOf(
            NotificationChannel(
                SENTINEL_SERVICE,
                "Sentinel Background Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent status notification for 24/5 Schwab Streamer quote monitoring."
                setShowBadge(false)
            },
            NotificationChannel(
                TOKEN_CLIFF,
                "OAuth Token Cliff Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Day 5 & Day 6 alerts before Schwab's 7-day OAuth refresh token expires."
            },
            NotificationChannel(
                DISCONNECT_ALARM,
                "Streamer Disconnect Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority alarm when WebSocket quote monitoring drops unexpectedly."
                enableVibration(true)
            },
            NotificationChannel(
                OVERNIGHT_PRICE_ALERT,
                "Overnight & Synthetic Stop Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Triggered synthetic stop-loss, price boundary, and anti-stop-hunt alerts."
                enableVibration(true)
            },
            NotificationChannel(
                EXECUTION_FILL,
                "Order & Execution Fills",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for Shadow Mode simulated fills and live order executions."
            }
        )

        manager.createNotificationChannels(channels)
    }
}
