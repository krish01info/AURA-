package com.aura.policy

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "BiometricGate"

/**
 * BiometricGate — secures CRITICAL actions behind biometric authentication.
 *
 * Called by the UI (not TaskManager) when the ConfirmationDialog recognises
 * a CRITICAL risk level. The flow is:
 *   1. ConfirmationDialog shows for HIGH actions → user taps Allow/Deny
 *   2. For CRITICAL: ConfirmationDialog calls authenticate() first
 *   3. BiometricPrompt launches — fingerprint / face / device credential
 *   4. Returns true on success, false on failure/cancellation
 *
 * Falls back gracefully if device has no biometric hardware:
 *   - BIOMETRIC_STRONG unavailable → tries DEVICE_CREDENTIAL (PIN/pattern)
 *   - Both unavailable → auto-allows with a warning log (unavoidable edge case)
 */
@Singleton
class BiometricGate @Inject constructor() {

    /**
     * Check if the device supports biometric or device-credential authentication.
     */
    fun isAvailable(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        val result = bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Show the biometric prompt and suspend until the user responds.
     *
     * Must be called from an Activity context (requires FragmentActivity for
     * the BiometricPrompt API).
     *
     * @param activity The hosting FragmentActivity.
     * @param title    Dialog title (e.g. "Confirm Payment").
     * @param subtitle Subtitle shown below the title.
     * @return true if authenticated successfully, false if failed/cancelled.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = "Confirm Critical Action",
        subtitle: String = "This action cannot be undone"
    ): Boolean = suspendCancellableCoroutine { cont ->

        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.i(TAG, "✅ Biometric auth succeeded")
                if (cont.isActive) cont.resume(true)
            }

            override fun onAuthenticationFailed() {
                // Finger not recognised — user can retry; don't dismiss yet
                Log.w(TAG, "⚠️ Biometric attempt failed — user can retry")
                // Do NOT resume here; BiometricPrompt lets the user retry
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.e(TAG, "❌ Biometric error [$errorCode]: $errString")
                if (cont.isActive) cont.resume(false)
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val allowedAuthenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            @Suppress("DEPRECATION")
            BIOMETRIC_STRONG
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowedAuthenticators)
            .apply {
                // setNegativeButtonText is required when DEVICE_CREDENTIAL is NOT included
                // (pre-R only BIOMETRIC_STRONG path — need cancel button)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    setNegativeButtonText("Cancel")
                }
            }
            .build()

        prompt.authenticate(promptInfo)

        // If the coroutine is cancelled (e.g. task emergency-stopped), cancel the prompt
        cont.invokeOnCancellation {
            prompt.cancelAuthentication()
        }
    }
}
