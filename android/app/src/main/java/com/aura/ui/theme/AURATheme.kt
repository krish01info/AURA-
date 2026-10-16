package com.aura.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── AURA colour palette ───────────────────────────────────────────
// Calm indigo with one accent. These named colours are used directly by many
// screens on both light and dark backgrounds, so they're mid-tones that read on both.
val AuraPrimary        = Color(0xFF6366F1)  // Indigo 500
val AuraPrimaryVariant = Color(0xFF4F46E5)  // Indigo 600
val AuraOnPrimary      = Color(0xFFFFFFFF)

// Secondary: soft sky accent (used sparingly — skills, listening)
val AuraSecondary      = Color(0xFF0EA5C6)
val AuraOnSecondary    = Color(0xFFFFFFFF)

// Surfaces (dark)
val AuraSurface        = Color(0xFF0E0F1A)
val AuraSurfaceVariant = Color(0xFF1E2133)
val AuraOnSurface      = Color(0xFFE6E7F2)

// Error
val AuraError          = Color(0xFFDC2626)
val AuraOnError        = Color(0xFFFFFFFF)

// Status colours
val AuraSuccess        = Color(0xFF16A34A)  // Green 600
val AuraWarning        = Color(0xFFD97706)  // Amber 600
val AuraCritical       = Color(0xFFDC2626)  // Red 600

private val DarkColorScheme = darkColorScheme(
    primary              = Color(0xFF9B9DFB),
    onPrimary            = Color(0xFF15164A),
    primaryContainer     = Color(0xFF2E3080),
    onPrimaryContainer   = Color(0xFFE1E0FF),
    secondary            = Color(0xFF5CC8E0),
    onSecondary          = Color(0xFF00313D),
    secondaryContainer   = Color(0xFF16404D),
    onSecondaryContainer = Color(0xFFC4EEF8),
    tertiary             = Color(0xFFC4A5F5),
    onTertiary           = Color(0xFF2E1A55),
    background           = AuraSurface,
    onBackground         = AuraOnSurface,
    surface              = AuraSurface,
    onSurface            = AuraOnSurface,
    surfaceVariant       = AuraSurfaceVariant,
    onSurfaceVariant     = Color(0xFFA4A8C3),
    surfaceContainerLowest  = Color(0xFF0A0B14),
    surfaceContainerLow     = Color(0xFF131524),
    surfaceContainer        = Color(0xFF171A2A),
    surfaceContainerHigh    = Color(0xFF1C1F31),
    surfaceContainerHighest = Color(0xFF232739),
    outline              = Color(0xFF3A3F5C),
    outlineVariant       = Color(0xFF2A2E45),
    error                = Color(0xFFF87171),
    onError              = Color(0xFF450A0A),
    errorContainer       = Color(0xFF5C1A1A),
    onErrorContainer     = Color(0xFFFFDAD6),
    scrim                = Color(0xFF000000)
)

private val LightColorScheme = lightColorScheme(
    primary              = Color(0xFF5254D4),
    onPrimary            = Color(0xFFFFFFFF),
    primaryContainer     = Color(0xFFE3E2FF),
    onPrimaryContainer   = Color(0xFF17186B),
    secondary            = Color(0xFF0B7C96),
    onSecondary          = Color(0xFFFFFFFF),
    secondaryContainer   = Color(0xFFCDEFF7),
    onSecondaryContainer = Color(0xFF003543),
    tertiary             = Color(0xFF7448C4),
    onTertiary           = Color(0xFFFFFFFF),
    background           = Color(0xFFF7F7FC),
    onBackground         = Color(0xFF1A1B2E),
    surface              = Color(0xFFF7F7FC),
    onSurface            = Color(0xFF1A1B2E),
    surfaceVariant       = Color(0xFFE8E9F3),
    onSurfaceVariant     = Color(0xFF555973),
    surfaceContainerLowest  = Color(0xFFFFFFFF),
    surfaceContainerLow     = Color(0xFFF2F2F9),
    surfaceContainer        = Color(0xFFEDEDF6),
    surfaceContainerHigh    = Color(0xFFE8E8F2),
    surfaceContainerHighest = Color(0xFFE2E3EE),
    outline              = Color(0xFFC5C7D9),
    outlineVariant       = Color(0xFFDCDDEA),
    error                = AuraError,
    onError              = AuraOnError,
    errorContainer       = Color(0xFFFFE1DE),
    onErrorContainer     = Color(0xFF5C0B0B),
    scrim                = Color(0xFF000000)
)

private val AuraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val AuraTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall  = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge     = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium    = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge     = labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

/** Follows the phone's light/dark setting. */
@Composable
fun AURATheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = AuraTypography,
        shapes = AuraShapes,
        content = content
    )
}
