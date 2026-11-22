package com.dozy.pocketai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Pulse palette — the same one the Winteria phone layout uses (src/ui/mobile.rs):
 * cool graphite surfaces, a calm near-white (night) / slate (day) accent pill.
 */
@Immutable
data class Pulse(
    val ink: Color,        // main text
    val muted: Color,      // secondary text
    val accent: Color,     // accent pill
    val onAccent: Color,
    val bg: Color,
    val bar: Color,        // header, dock, drawer
    val outline: Color,
    val card: Color,
    val selected: Color,
    val gridLine: Color,   // card outline
    val inset: Color,      // code blocks, input field
    val dark: Boolean,
)

val PulseNight = Pulse(
    ink = Color(0xFFF1F3F5), muted = Color(0xFFBFC5CC),
    accent = Color(0xFFDCE5EE), onAccent = Color(0xFF1A1B1D),
    bg = Color(0xFF1A1E23), bar = Color(0xFF22272E), outline = Color(0xFF555F69),
    card = Color(0xFF2A3139), selected = Color(0xFF3B4B57), gridLine = Color(0xFF45525E),
    inset = Color(0xFF1B2026), dark = true,
)

val PulseDay = Pulse(
    ink = Color(0xFF202A30), muted = Color(0xFF35454D),
    accent = Color(0xFF334953), onAccent = Color(0xFFE4EAEB),
    bg = Color(0xFFB8C2C7), bar = Color(0xFFC2CBCE), outline = Color(0xFF74858D),
    card = Color(0xFFCBD2D4), selected = Color(0xFFA4B8C0), gridLine = Color(0xFF96A7AF),
    inset = Color(0xFFACB9BF), dark = false,
)

/** Badge colours for list rows (Winteria lane palette). */
val BadgeColors = listOf(
    Color(0xFF4FC3F7), Color(0xFF3DDC97), Color(0xFF9A8CFF), Color(0xFFFFA94D),
    Color(0xFFFF7AB6), Color(0xFFA3E04F), Color(0xFFFF6B6B), Color(0xFFFFD43B),
)
val Green = Color(0xFF3DDC97)
val Amber = Color(0xFFFFB020)
val Red = Color(0xFFFF5C5C)

val LocalPulse = staticCompositionLocalOf { PulseNight }

/** theme: 0 = follow the system, 1 = night, 2 = day */
@Composable
fun PocketTheme(theme: Int = 0, content: @Composable () -> Unit) {
    val dark = when (theme) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    val p = if (dark) PulseNight else PulseDay
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.bar,
        surfaceVariant = p.card, surfaceContainer = p.card, surfaceContainerHigh = p.card,
        onSurface = p.ink, onSurfaceVariant = p.muted, outline = p.outline, error = Red,
        secondaryContainer = p.selected, onSecondaryContainer = p.ink,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.bar,
        surfaceVariant = p.card, surfaceContainer = p.card, surfaceContainerHigh = p.card,
        onSurface = p.ink, onSurfaceVariant = p.muted, outline = p.outline, error = Red,
        secondaryContainer = p.selected, onSecondaryContainer = p.ink,
    )
    CompositionLocalProvider(LocalPulse provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
