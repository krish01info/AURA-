package com.aura.di

import com.aura.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import javax.inject.Singleton

/**
 * Hilt module providing the Supabase client and its feature plugins.
 *
 * Credentials are pulled from BuildConfig (injected via local.properties):
 *   SUPABASE_URL      — your project URL, e.g. https://xxxx.supabase.co
 *   SUPABASE_ANON_KEY — your project's anon/public key (safe to ship in APK)
 *
 * If either credential is blank (e.g. during development before keys are added),
 * [isConfigured] returns false and callers should skip cloud operations gracefully.
 *
 * Installed plugins:
 *   Auth     — anonymous sign-in, OAuth upgrade, session management
 *   Postgrest — typed REST queries against skill_packs, memory, telemetry tables
 *   Realtime  — live skill version push notifications from the community
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    /**
     * Whether Supabase credentials have been configured in local.properties.
     * All cloud feature classes should check this before making network calls.
     */
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
                BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient {
        return createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL.ifBlank { "https://placeholder.supabase.co" },
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY.ifBlank { "placeholder" }
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
}
