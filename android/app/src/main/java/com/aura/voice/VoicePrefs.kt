package com.aura.voice

import android.content.Context
import java.util.Locale

/**
 * VoicePrefs — speech settings shared by the assistant service and VoiceManager.
 *
 * Speech language: "" = phone default, or a tag like "en-IN" / "hi-IN".
 * The wake phrase "Hey AURA" is English, so when Hindi is chosen the wake listener
 * uses Indian English (which also writes Hinglish commands in Latin letters), while
 * the command itself is recognised in Hindi.
 */
object VoicePrefs {
    private const val PREFS = "aura_assistant"
    private const val KEY_LANGUAGE = "speech_language"

    /** Choices shown in Settings: tag → label. */
    val LANGUAGES: Map<String, String> = linkedMapOf(
        "" to "Phone default",
        "en-IN" to "English (India)",
        "hi-IN" to "Hindi",
        "en-US" to "English (US)"
    )

    fun speechLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, "") ?: ""

    fun setSpeechLanguage(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, tag).apply()
    }

    /** Language for recognising the command. */
    fun commandLanguageTag(context: Context): String =
        speechLanguage(context).ifBlank { Locale.getDefault().toLanguageTag() }

    /** Language for the always-on "Hey AURA" listener. */
    fun wakeLanguageTag(context: Context): String {
        val chosen = speechLanguage(context)
        return if (chosen.startsWith("hi")) "en-IN" else commandLanguageTag(context)
    }
}
