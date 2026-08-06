package com.macroplus.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = SageGreen,
    onPrimary = Cream,
    primaryContainer = SageGreenLight,
    onPrimaryContainer = SageGreenDark,
    secondary = SoftAmber,
    background = WarmSand,
    onBackground = Charcoal,
    surface = Cream,
    onSurface = Charcoal,
    error = MutedRed,
    outline = NeutralGray,
)

private val DarkColors = darkColorScheme(
    primary = SageGreenLight,
    onPrimary = SageGreenDark,
    primaryContainer = SageGreenDark,
    onPrimaryContainer = SageGreenLight,
    secondary = SoftAmber,
    background = WarmSandDark,
    onBackground = Cream,
    surface = Charcoal,
    onSurface = Cream,
    error = MutedRed,
    outline = NeutralGray,
)

@Composable
fun MacroPlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MacroPlusTypography,
        content = content,
    )
}
