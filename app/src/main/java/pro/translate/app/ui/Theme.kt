package pro.translate.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF5B7CFF)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A3570),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFF8FA2FF),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF1A1D24),
    onSurfaceVariant = Color(0xFFA6ABB8),
    surfaceContainer = Color(0xFF161920),
    surfaceContainerHigh = Color(0xFF1C2029),
    outline = Color(0xFF353A46),
    error = Color(0xFFFF6B6B),
)

@Composable
fun TPTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
