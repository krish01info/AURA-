package com.aura.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aura.ui.theme.AURATheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single activity that hosts the entire Compose UI.
 * Navigation between screens is handled by NavHost inside [AURAApp].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AURATheme {
                AURAApp()
            }
        }
    }
}
