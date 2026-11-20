package com.schwabquant.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.schwabquant.app.core.security.InternalIntentAuth

/**
 * Non-exported notification deep-link trampoline (`android:exported="false"`).
 * Validates notification PendingIntent origination and attaches a per-process 32-byte
 * `InternalIntentAuth.token` before forwarding whitelisted fields to exported `MainActivity`.
 */
class SentinelDeepLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incoming = intent
        val forward = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_INTERNAL_INTENT_TOKEN, InternalIntentAuth.token)
            incoming?.getStringExtra(MainActivity.EXTRA_NAV_DESTINATION)?.let {
                putExtra(MainActivity.EXTRA_NAV_DESTINATION, it)
            }
            if (incoming?.action == MainActivity.ACTION_STAGE_SENTINEL_ORDER) {
                action = MainActivity.ACTION_STAGE_SENTINEL_ORDER
                putExtra(
                    MainActivity.EXTRA_STAGE_EXECUTION_MODE,
                    incoming.getStringExtra(MainActivity.EXTRA_STAGE_EXECUTION_MODE)
                )
                putExtra(
                    MainActivity.EXTRA_STAGE_SYMBOL,
                    incoming.getStringExtra(MainActivity.EXTRA_STAGE_SYMBOL)
                )
                putExtra(
                    MainActivity.EXTRA_STAGE_SIDE,
                    incoming.getStringExtra(MainActivity.EXTRA_STAGE_SIDE)
                )
                putExtra(
                    MainActivity.EXTRA_STAGE_QTY,
                    incoming.getIntExtra(MainActivity.EXTRA_STAGE_QTY, 1)
                )
                putExtra(
                    MainActivity.EXTRA_STAGE_LIMIT_PRICE,
                    incoming.getStringExtra(MainActivity.EXTRA_STAGE_LIMIT_PRICE)
                )
                putExtra(
                    MainActivity.EXTRA_STAGE_SOURCE_LABEL,
                    incoming.getStringExtra(MainActivity.EXTRA_STAGE_SOURCE_LABEL)
                )
                putExtra(
                    MainActivity.EXTRA_STAGE_REPLACE_ORDER_ID,
                    incoming.getStringExtra(MainActivity.EXTRA_STAGE_REPLACE_ORDER_ID)
                )
                putExtra(
                    MainActivity.EXTRA_STAGE_IS_ESCALATED_REPRICE,
                    incoming.getBooleanExtra(MainActivity.EXTRA_STAGE_IS_ESCALATED_REPRICE, false)
                )
            }
        }
        startActivity(forward)
        finish()
    }
}
