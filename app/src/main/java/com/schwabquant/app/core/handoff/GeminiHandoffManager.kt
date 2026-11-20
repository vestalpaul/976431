package com.schwabquant.app.core.handoff

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class HandoffTarget {
    GEMINI_APP,
    SHARE_SHEET,
    WEB_BROWSER,
    CLIPBOARD_ONLY
}

data class HandoffResult(
    val copiedToClipboard: Boolean,
    val target: HandoffTarget,
    val message: String
)

/**
 * Zero-API-cost Gemini handoff: copies Markdown portfolio packet to system clipboard and dispatches
 * ACTION_SEND to Google Gemini (`com.google.android.apps.bard`), falling back cleanly to the
 * Android Share Sheet or Gemini web app if unavailable.
 */
@Singleton
class GeminiHandoffManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    fun handoffPrompt(
        prompt: String,
        subject: String = "Schwab Quant Portfolio Analysis"
    ): HandoffResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val copied = runCatching {
            clipboard?.setPrimaryClip(ClipData.newPlainText(subject, prompt))
            clipboard != null
        }.getOrDefault(false)

        val targetedIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, prompt)
            setPackage(GEMINI_PACKAGE_NAME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(targetedIntent)
            HandoffResult(
                copiedToClipboard = copied,
                target = HandoffTarget.GEMINI_APP,
                message = "Context copied & opened in Gemini."
            )
        } catch (_: ActivityNotFoundException) {
            try {
                val genericShare = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, prompt)
                }
                val chooser = Intent.createChooser(genericShare, "Send quant context to AI").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                HandoffResult(
                    copiedToClipboard = copied,
                    target = HandoffTarget.SHARE_SHEET,
                    message = "Context copied. Select Gemini or AI assistant."
                )
            } catch (_: ActivityNotFoundException) {
                try {
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(GEMINI_WEB_URL)
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                    HandoffResult(
                        copiedToClipboard = copied,
                        target = HandoffTarget.WEB_BROWSER,
                        message = "Context copied to clipboard; opened Gemini Web."
                    )
                } catch (_: ActivityNotFoundException) {
                    HandoffResult(
                        copiedToClipboard = copied,
                        target = HandoffTarget.CLIPBOARD_ONLY,
                        message = "Portfolio Markdown copied to clipboard."
                    )
                }
            }
        }
    }

    companion object {
        const val GEMINI_PACKAGE_NAME = "com.google.android.apps.bard"
        const val GEMINI_WEB_URL = "https://gemini.google.com/app"
    }
}
