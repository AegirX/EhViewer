package moe.tarsin.indexreader.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

enum class AppTheme { Dark, Light, Sepia }

fun AppTheme.colorScheme(): ColorScheme = when (this) {
    AppTheme.Dark -> darkColorScheme(
        primary = Color(0xFFF0B429),
        onPrimary = Color(0xFF10141A),
        secondary = Color(0xFFF0B429),
        background = Color(0xFF0E1116),
        onBackground = Color(0xFFDDE4EE),
        surface = Color(0xFF141821),
        onSurface = Color(0xFFDDE4EE),
        surfaceVariant = Color(0xFF171C26),
        onSurfaceVariant = Color(0xFF93A0B3),
        outline = Color(0xFF232A36),
    )

    AppTheme.Light -> lightColorScheme(
        primary = Color(0xFF9A6206),
        onPrimary = Color.White,
        secondary = Color(0xFF9A6206),
        background = Color(0xFFF6F6F3),
        onBackground = Color(0xFF1D2127),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1D2127),
        surfaceVariant = Color(0xFFFBFBF8),
        onSurfaceVariant = Color(0xFF5F6875),
        outline = Color(0xFFE2E4E0),
    )

    AppTheme.Sepia -> lightColorScheme(
        primary = Color(0xFF8A5714),
        onPrimary = Color(0xFFFFF8EA),
        secondary = Color(0xFF8A5714),
        background = Color(0xFFF2E8D5),
        onBackground = Color(0xFF332B1E),
        surface = Color(0xFFF8EFDD),
        onSurface = Color(0xFF332B1E),
        surfaceVariant = Color(0xFFF5EAD6),
        onSurfaceVariant = Color(0xFF6D6049),
        outline = Color(0xFFE0D2B6),
    )
}

enum class ReaderFont { Serif, Sans, Mono, System }

fun ReaderFont.family(): FontFamily = when (this) {
    ReaderFont.Serif -> FontFamily.Serif
    ReaderFont.Sans -> FontFamily.SansSerif
    ReaderFont.Mono -> FontFamily.Monospace
    ReaderFont.System -> FontFamily.Default
}

fun String.toTheme(): AppTheme = when (lowercase()) {
    "light" -> AppTheme.Light
    "sepia" -> AppTheme.Sepia
    else -> AppTheme.Dark
}

fun String.toFont(): ReaderFont = when (lowercase()) {
    "sans" -> ReaderFont.Sans
    "mono" -> ReaderFont.Mono
    "system" -> ReaderFont.System
    else -> ReaderFont.Serif
}
