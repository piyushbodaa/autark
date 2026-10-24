package com.piyushbodaa.autark.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.piyushbodaa.autark.models.CatalogModel
import com.piyushbodaa.autark.models.ModelManager.Status
import com.piyushbodaa.autark.ui.theme.AutarkTheme
import com.piyushbodaa.autark.ui.theme.LocalPalette
import com.piyushbodaa.autark.ui.theme.Night

/** First run: one button, then progress until the model is on the phone. Always night. */
@Composable
fun SetupScreen(model: CatalogModel, status: Status, onDownload: () -> Unit, onCancel: () -> Unit) {
    LightStatusBarIcons()
    AutarkTheme(palette = Night) {
        val p = LocalPalette.current
        Box(
            Modifier
                .fillMaxSize()
                .background(p.paper)
                .safeDrawingPadding()
                .padding(horizontal = 20.dp),
        ) {
            Wordmark(modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 420.dp),
            ) {
                Headline("A private AI", "that lives on your phone.")
                Spacer(Modifier.height(20.dp))
                Fine(
                    "It works offline. No account, no cloud, and nothing you type ever leaves this phone.",
                    align = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
                Spacer(Modifier.height(32.dp))
                Progress(model, status, onDownload, onCancel)
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
            ) {
                Fine("No account", upper = true)
                Fine("No cloud", upper = true)
                Fine("No tracking", upper = true)
            }
        }
    }
}

@Composable
private fun Progress(model: CatalogModel, status: Status, onDownload: () -> Unit, onCancel: () -> Unit) {
    val p = LocalPalette.current
    when (status) {
        Status.Absent -> {
            Pill("Download · ${formatBytes(model.sizeBytes)}", onClick = onDownload, solid = true)
            Spacer(Modifier.height(12.dp))
            Fine("A one-time download. Wi-Fi is best.", align = TextAlign.Center)
        }
        is Status.Downloading -> {
            val fraction = if (status.total > 0) status.downloaded.toFloat() / status.total else 0f
            LinearProgressIndicator(
                progress = { fraction },
                color = p.ink,
                trackColor = p.pill,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Fine(
                if (status.waiting) "Waiting for network" else "${formatBytes(status.downloaded)} of ${formatBytes(status.total)}",
                upper = true,
            )
            Spacer(Modifier.height(4.dp))
            Fine("You can leave the app. It keeps going.", align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            NavText("Cancel", onClick = onCancel)
        }
        is Status.Failed -> {
            Fine(status.message, align = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Pill("Try again", onClick = onDownload, solid = true)
        }
        Status.Ready -> Unit
    }
}

/** Night screens (this one and the chat drawer) need light status-bar icons even when the phone is in light mode. */
@Composable
fun LightStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val wasLight = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (wasLight != null) {
                controller.isAppearanceLightStatusBars = wasLight
                controller.isAppearanceLightNavigationBars = wasLight
            }
        }
    }
}
