package cn.a10miaomiao.bilidown.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = L_Primary, onPrimary = L_OnPrimary,
    primaryContainer = L_PrimaryC, onPrimaryContainer = L_OnPrimaryC,
    background = L_Bg, onBackground = Color(0xFF171820),
    surface = L_Surface, onSurface = Color(0xFF171820),
    surfaceContainerHigh = L_SurfaceH, surfaceContainerHighest = L_SurfaceH,
    surfaceContainerLow = L_Surface, surfaceContainerLowest = L_Surface,
    outline = L_Outine, outlineVariant = L_OutineV,
    error = L_Error,
)

private val DarkColorScheme = darkColorScheme(
    primary = D_Primary, onPrimary = D_OnPrimary,
    primaryContainer = D_PrimaryC, onPrimaryContainer = D_OnPrimaryC,
    background = D_Bg, onBackground = Color(0xFFEEF1F6),
    surface = D_Surface, onSurface = Color(0xFFEEF1F6),
    surfaceContainerHigh = D_SurfaceH, surfaceContainerHighest = D_SurfaceH,
    surfaceContainerLow = D_Surface, surfaceContainerLowest = D_Surface,
    outline = D_Outine, outlineVariant = D_OutineV,
    error = D_Error,
)

@Composable
fun BiliDownTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}