package com.piyushbodaa.autark

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.piyushbodaa.autark.ui.AppViewModel
import com.piyushbodaa.autark.ui.AutarkRoot
import com.piyushbodaa.autark.ui.theme.AutarkTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receive(intent)
        setContent {
            AutarkTheme {
                AutarkRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as AutarkApplication).container.visible = true
    }

    override fun onStop() {
        super.onStop()
        (application as AutarkApplication).container.visible = false
    }

    /** Text selected in another app ("Ask Autark" in its menu) or shared from one. */
    private fun receive(intent: Intent) {
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!text.isNullOrBlank()) vm.share(text.toString())
    }
}
