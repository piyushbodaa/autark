package com.piyushbodaa.autark.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%d MB", bytes / 1_000_000)
    else -> String.format(Locale.US, "%d KB", bytes / 1_000)
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        // No browser or email app installed; nothing useful to do.
    }
}

const val DEVELOPER_EMAIL = "email@piyushbodaa.com"

/**
 * Google Play requires AI apps to let people flag offensive output. Autark has no server,
 * so this opens the person's own email app with the response filled in; nothing is sent
 * unless they choose to send it.
 */
fun reportResponse(context: Context, model: String?, response: String) {
    val body = buildString {
        appendLine("I'd like to report this response from Autark.")
        appendLine()
        appendLine("Model: ${model ?: "unknown"}")
        appendLine("What was wrong with it:")
        appendLine()
        appendLine("---")
        append(response.take(4000))
    }
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, "Autark: reported response")
        .putExtra(Intent.EXTRA_TEXT, body)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No browser or email app installed; nothing useful to do.
    }
}
