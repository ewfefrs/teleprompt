package com.example.teleprompter

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Монохромная тема в духе Even: строгий контраст, один тон. Светлая — чёрное на белом,
// тёмная — белое на чёрном. Переопределяем ВСЕ контейнерные роли и surfaceTint, иначе
// Card/Chip и прочее берут базовый фиолетовый тон M3 (тот самый «сиреневый» фон).
private val MonoLight = lightColorScheme(
    primary = Color(0xFF0A0A0A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF0A0A0A),
    secondary = Color(0xFF3A3A3A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF0A0A0A),
    tertiary = Color(0xFF3A3A3A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8E8E8),
    onTertiaryContainer = Color(0xFF0A0A0A),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFF4F4F4),
    onSurfaceVariant = Color(0xFF5A5A5A),
    surfaceTint = Color(0xFF0A0A0A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE8E8E8),
    inverseSurface = Color(0xFF0A0A0A),
    inverseOnSurface = Color(0xFFF5F5F5),
    outline = Color(0xFFD4D4D4),
    outlineVariant = Color(0xFFE6E6E6),
    error = Color(0xFF0A0A0A),
    onError = Color(0xFFFFFFFF),
)

private val MonoDark = darkColorScheme(
    primary = Color(0xFFF5F5F5),
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF262628),
    onPrimaryContainer = Color(0xFFF3F3F3),
    secondary = Color(0xFFC8C8C8),
    onSecondary = Color(0xFF0A0A0A),
    secondaryContainer = Color(0xFF262628),
    onSecondaryContainer = Color(0xFFF3F3F3),
    tertiary = Color(0xFFC8C8C8),
    onTertiary = Color(0xFF0A0A0A),
    tertiaryContainer = Color(0xFF262628),
    onTertiaryContainer = Color(0xFFF3F3F3),
    background = Color(0xFF0B0B0C),
    onBackground = Color(0xFFF3F3F3),
    surface = Color(0xFF0B0B0C),
    onSurface = Color(0xFFF3F3F3),
    surfaceVariant = Color(0xFF1B1B1D),
    onSurfaceVariant = Color(0xFF9A9A9E),
    surfaceTint = Color(0xFFF5F5F5),
    surfaceContainerLowest = Color(0xFF060607),
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF19191B),
    surfaceContainerHigh = Color(0xFF212123),
    surfaceContainerHighest = Color(0xFF262628),
    inverseSurface = Color(0xFFF3F3F3),
    inverseOnSurface = Color(0xFF0A0A0A),
    outline = Color(0xFF3A3A3D),
    outlineVariant = Color(0xFF2A2A2C),
    error = Color(0xFFF3F3F3),
    onError = Color(0xFF0A0A0A),
)

@Composable
fun AppTheme(themeMode: Int, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeManager.LIGHT -> false
        ThemeManager.DARK -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) MonoDark else MonoLight, content = content)
}
