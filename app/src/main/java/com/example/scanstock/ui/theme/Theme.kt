package com.example.scanstock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 应用配色。
 *
 * 说明：不启用 dynamicColor（Material You）。盘点 App 的语义色需要稳定——
 * 「识别成功」的绿色、列表底色的对比度如果跟着手机壁纸走，暗光仓库里会看不清。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF116B4B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBDC),
    onPrimaryContainer = Color(0xFF002115),
    secondary = Color(0xFF4A6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8DA),
    onSecondaryContainer = Color(0xFF072019),
    background = Color(0xFFF6FBF7),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF6FBF7),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDCE5DF),
    onSurfaceVariant = Color(0xFF404944),
    outline = Color(0xFF707974),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD5B4),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF005137),
    onPrimaryContainer = Color(0xFFAAF2CF),
    secondary = Color(0xFFB1CCBF),
    onSecondary = Color(0xFF1D352B),
    secondaryContainer = Color(0xFF334B41),
    onSecondaryContainer = Color(0xFFCCE8DA),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDEE4DF),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDEE4DF),
    surfaceVariant = Color(0xFF404944),
    onSurfaceVariant = Color(0xFFBFC9C3),
    outline = Color(0xFF89938D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun ScanStockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
