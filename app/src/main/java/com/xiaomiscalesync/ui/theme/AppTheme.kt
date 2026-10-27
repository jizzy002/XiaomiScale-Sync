package com.xiaomiscalesync.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private fun fallbackLightColors() = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2EFE6),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF56615C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE5DF),
    onSecondaryContainer = Color(0xFF151F1A),
    tertiary = Color(0xFF795900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE08D),
    onTertiaryContainer = Color(0xFF241A00),
    background = Color(0xFFF7F7F4),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFAFAF7),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFE5E7E0),
    onSurfaceVariant = Color(0xFF454943),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F4F0),
    surfaceContainer = Color(0xFFEEEEEA),
    surfaceContainerHigh = Color(0xFFE8E8E4),
    surfaceContainerHighest = Color(0xFFE2E3DE),
    outline = Color(0xFF747871),
    outlineVariant = Color(0xFFC5C8C0),
    surfaceTint = Color(0xFF006A60),
)

private fun fallbackDarkColors() = darkColorScheme(
    primary = Color(0xFF78D7C8),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF9DF2E3),
    secondary = Color(0xFFBBC9C2),
    onSecondary = Color(0xFF26332E),
    secondaryContainer = Color(0xFF3D4943),
    onSecondaryContainer = Color(0xFFDCE5DF),
    tertiary = Color(0xFFE9C350),
    onTertiary = Color(0xFF3E2F00),
    tertiaryContainer = Color(0xFF5B4700),
    onTertiaryContainer = Color(0xFFFFE08D),
    background = Color(0xFF121311),
    onBackground = Color(0xFFE3E3DE),
    surface = Color(0xFF1B1C19),
    onSurface = Color(0xFFE3E3DE),
    surfaceVariant = Color(0xFF434640),
    onSurfaceVariant = Color(0xFFC4C6BE),
    surfaceContainerLowest = Color(0xFF0D0E0C),
    surfaceContainerLow = Color(0xFF191A17),
    surfaceContainer = Color(0xFF1E1F1C),
    surfaceContainerHigh = Color(0xFF282A26),
    surfaceContainerHighest = Color(0xFF333530),
    outline = Color(0xFF8E9088),
    outlineVariant = Color(0xFF434640),
    surfaceTint = Color(0xFF78D7C8),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Material 3 follows the system appearance and Android 12+ wallpaper palette.
 * Older Android versions use one calm built-in palette.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) fallbackDarkColors() else fallbackLightColors()
    }
    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}
