package com.aura.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.aura.ui.theme.AURATheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single activity that hosts the entire Compose UI.
 * Navigation between screens is handled by NavHost inside [AURAApp].
 *
 * A FragmentActivity (not a plain ComponentActivity) because BiometricPrompt needs
 * one: HomeScreen checks `context as? FragmentActivity`, and with a ComponentActivity
 * that was always null, so CRITICAL approvals skipped the fingerprint check.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions granted/denied handled by individual features
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request necessary runtime permissions
        // Microphone (voice), contacts ("message Mom" → the right person; optional)
        // Phone calls: "call Rahul" places the call (without it AURA presses the dialer's Call button).
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())

        enableEdgeToEdge()
        setContent {
            AURATheme {
                AURAApp()
            }
        }
    }
}
