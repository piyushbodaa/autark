package com.piyushbodaa.autark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.piyushbodaa.autark.ui.AutarkRoot
import com.piyushbodaa.autark.ui.theme.AutarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AutarkTheme {
                AutarkRoot()
            }
        }
    }
}
