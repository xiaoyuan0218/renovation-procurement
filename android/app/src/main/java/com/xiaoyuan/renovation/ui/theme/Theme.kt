package com.xiaoyuan.renovation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RenovationColors = darkColorScheme(
    primary = Ink.Blue,
    onPrimary = Color.White,
    primaryContainer = Ink.Blue.copy(alpha = 0.22f),
    onPrimaryContainer = Ink.TextPrimary,
    secondary = Ink.Indigo,
    onSecondary = Color.White,
    secondaryContainer = Ink.Indigo.copy(alpha = 0.22f),
    onSecondaryContainer = Ink.TextPrimary,
    tertiary = Ink.Cyan,
    onTertiary = Ink.BgTop,
    background = Ink.BgTop,
    onBackground = Ink.TextPrimary,
    surface = Ink.BgMid,
    onSurface = Ink.TextPrimary,
    surfaceVariant = Ink.GlassFillStrong,
    onSurfaceVariant = Ink.TextSecondary,
    error = Ink.Danger,
    onError = Color.White,
    outline = Ink.GlassBorder,
    outlineVariant = Ink.Divider,
)

/** App 始终使用深色霓虹主题 —— 参考图就是深色场景，浅色没有意义。 */
@Composable
fun RenovationTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = RenovationColors,
        typography = AppTypography,
        content = content,
    )
}
