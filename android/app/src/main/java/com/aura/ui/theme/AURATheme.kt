package com.aura.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── AURA colour palette ───────────────────────────────────────────
// Primary: Electric violet / indigo
val AuraPrimary        = Color(0xFF7C3AED)  // Violet 600
val AuraPrimaryVariant = Color(0xFF6D28D9)  // Violet 700
val AuraOnPrimary      = Color(0xFFFFFFFF)

// Secondary: Cyan accent
val AuraSecondary      = Color(0xFF06B6D4)  // Cyan 500
val AuraOnSecondary    = Color(0xFF000000)

// Surface: Dark backgrounds
val AuraSurface        = Color(0xFF0F0F1A)  // Near-black with blue tint
val AuraSurfaceVariant = Color(0xFF1A1A2E)  // Card surface
val AuraOnSurface      = Color(0xFFE2E8F0)  // Light text

// Error
val AuraError          = Color(0xFFEF4444)
val AuraOnError        = Color(0xFFFFFFFF)

// Status colours
val AuraSuccess        = Color(0xFF10B981)  // Emerald
val AuraWarning        = Color(0xFFF59E0B)  // Amber
val AuraCritical       = Color(0xFFEF4444)  // Red

private val DarkColorScheme = darkColorScheme(
    primary          = AuraPrimary,
    onPrimary        = AuraOnPrimary,
    primaryContainer = AuraPrimaryVariant,
    secondary        = AuraSecondary,
    onSecondary      = AuraOnSecondary,
    surface          = AuraSurface,
    surfaceVariant   = AuraSurfaceVariant,
    onSurface        = AuraOnSurface,
    error            = AuraError,
    onError          = AuraOnError,
    background       = AuraSurface,
    onBackground     = AuraOnSurface
)

@Composable
fun AURATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
