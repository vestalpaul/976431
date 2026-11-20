package com.schwabquant.app.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAppThemeType = staticCompositionLocalOf { AppThemeType.OLED_NIGHT }

val LocalSchwabQuantColors = staticCompositionLocalOf {
    SchwabQuantPalettes.extendedColorsFor(AppThemeType.OLED_NIGHT)
}

val LocalSchwabQuantNumericTypography = staticCompositionLocalOf {
    SchwabQuantNumericTypography()
}

@Composable
fun SchwabQuantTheme(
    themeType: AppThemeType = AppThemeType.OLED_NIGHT,
    content: @Composable () -> Unit
) {
    val colorScheme = SchwabQuantPalettes.colorSchemeFor(themeType)
    val extendedColors = SchwabQuantPalettes.extendedColorsFor(themeType)
    val numericTypography = SchwabQuantNumericTypography()

    CompositionLocalProvider(
        LocalAppThemeType provides themeType,
        LocalSchwabQuantColors provides extendedColors,
        LocalSchwabQuantNumericTypography provides numericTypography
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SchwabQuantTypography,
            content = content
        )
    }
}

object SchwabQuantTheme {
    val colors: SchwabQuantExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSchwabQuantColors.current

    val numericTypography: SchwabQuantNumericTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalSchwabQuantNumericTypography.current
}
