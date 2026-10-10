package com.aura.cloud

import android.util.Log
import com.aura.di.SupabaseModule
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SupabaseAuthManager"

/**
 * SupabaseAuthManager — manages the Supabase auth session for AURA.
 *
 * Strategy:
 *   1. On first launch → sign in anonymously (no email/password required).
 *      Every install gets a stable UUID identity in Supabase.
 *   2. Supabase persists the session in SharedPreferences automatically.
 *      Subsequent launches restore the session — no repeated sign-in needed.
 *   3. All RLS INSERT/UPDATE policies require role = "authenticated",
 *      which anonymous sessions satisfy.
 *
 * Why anonymous?
 *   - Community skill uploads and telemetry need an auth token in the header.
 *   - We don't want to force users to create an account just to share skills.
 *   - Anonymous sessions are upgradeable to Google/Email later without data loss.
 *
 * Called from [AURAApplication.onCreate] once, before any cloud operations.
 */
@Singleton
class SupabaseAuthManager @Inject constructor(
    private val supabase: SupabaseClient
) {

    /** True if there is a valid active session (anonymous or signed-in). */
    val isSignedIn: Boolean
        get() = supabase.auth.currentSessionOrNull() != null

    /**
     * Ensure there is an active Supabase session.
     *
     * - If a session already exists (persisted from a previous launch) → restore it.
     * - If no session → sign in anonymously.
     * - If Supabase is not configured → no-op.
     *
     * Safe to call multiple times — idempotent.
     */
    suspend fun ensureSession() = withContext(Dispatchers.IO) {
        if (!SupabaseModule.isConfigured) {
            Log.d(TAG, "Supabase not configured — skipping auth")
            return@withContext
        }
        try {
            // Try to restore existing session first
            supabase.auth.awaitInitialization()
            val existing = supabase.auth.currentSessionOrNull()
            if (existing != null) {
                Log.i(TAG, "Session restored (uid=${existing.user?.id?.take(8)}...)")
                return@withContext
            }

            // No session — sign in anonymously
            supabase.auth.signInAnonymously()
            val newSession = supabase.auth.currentSessionOrNull()
            Log.i(TAG, "Anonymous sign-in OK (uid=${newSession?.user?.id?.take(8)}...)")
        } catch (e: Exception) {
            // Auth failure is non-fatal — app works fully offline
            Log.w(TAG, "Auth failed (non-fatal, cloud ops disabled): ${e.message}")
        }
    }

    /**
     * Returns the current user's Supabase UUID.
     * Used to attribute uploaded skills to a stable identity.
     */
    fun currentUserId(): String? =
        supabase.auth.currentSessionOrNull()?.user?.id

    /**
     * Sign out and clear the session.
     * Called from Settings if the user wants to reset their cloud identity.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        if (!SupabaseModule.isConfigured) return@withContext
        try {
            supabase.auth.signOut()
            Log.i(TAG, "Signed out")
        } catch (e: Exception) {
            Log.w(TAG, "Sign-out failed: ${e.message}")
        }
    }
}
