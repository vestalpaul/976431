package com.schwabquant.app.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class SchwabQuantExtendedColors(
    val profit: Color,
    val loss: Color,
    val warning: Color,
    val shadowBannerBg: Color,
    val shadowBannerFg: Color,
    val liveBannerBg: Color,
    val liveBannerFg: Color,
    val tokenHealthy: Color,
    val tokenWarning: Color,
    val tokenCritical: Color,
    val surfaceElevated: Color,
    val borderSubtle: Color
)

object SchwabQuantPalettes {

    fun colorSchemeFor(themeType: AppThemeType): ColorScheme = when (themeType) {
        AppThemeType.INSTITUTIONAL_DAY -> InstitutionalDayScheme
        AppThemeType.OLED_NIGHT -> OledNightScheme
        AppThemeType.TERMINAL_AMBER -> TerminalAmberScheme
        AppThemeType.STEALTH_MIDNIGHT -> StealthMidnightScheme
        AppThemeType.COLORBLIND_SAFE -> ColorblindSafeScheme
    }

    fun extendedColorsFor(themeType: AppThemeType): SchwabQuantExtendedColors = when (themeType) {
        AppThemeType.INSTITUTIONAL_DAY -> InstitutionalDayExtended
        AppThemeType.OLED_NIGHT -> OledNightExtended
        AppThemeType.TERMINAL_AMBER -> TerminalAmberExtended
        AppThemeType.STEALTH_MIDNIGHT -> StealthMidnightExtended
        AppThemeType.COLORBLIND_SAFE -> ColorblindSafeExtended
    }

    private val InstitutionalDayScheme = lightColorScheme(
        primary = Color(0xFF0F2942),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD6E3F3),
        onPrimaryContainer = Color(0xFF08192B),
        secondary = Color(0xFF2B5B84),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDCE9F7),
        onSecondaryContainer = Color(0xFF0F2B42),
        tertiary = Color(0xFF0E7C66),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFF5F7FA),
        onBackground = Color(0xFF0F172A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFE7ECF3),
        onSurfaceVariant = Color(0xFF475569),
        outline = Color(0xFFCBD5E1),
        error = Color(0xFFC62828),
        onError = Color(0xFFFFFFFF)
    )

    private val InstitutionalDayExtended = SchwabQuantExtendedColors(
        profit = Color(0xFF0E7C47),
        loss = Color(0xFFC62828),
        warning = Color(0xFFB45309),
        shadowBannerBg = Color(0xFFFEF3C7),
        shadowBannerFg = Color(0xFF78350F),
        liveBannerBg = Color(0xFFFEE2E2),
        liveBannerFg = Color(0xFF991B1B),
        tokenHealthy = Color(0xFF0E7C47),
        tokenWarning = Color(0xFFD97706),
        tokenCritical = Color(0xFFC62828),
        surfaceElevated = Color(0xFFF0F4F9),
        borderSubtle = Color(0xFFD8E0EB)
    )

    private val OledNightScheme = darkColorScheme(
        primary = Color(0xFF58A6FF),
        onPrimary = Color(0xFF001C38),
        primaryContainer = Color(0xFF102A43),
        onPrimaryContainer = Color(0xFFD1E4FF),
        secondary = Color(0xFF3FB950),
        onSecondary = Color(0xFF00240B),
        secondaryContainer = Color(0xFF0D2E18),
        onSecondaryContainer = Color(0xFFA7F3BA),
        tertiary = Color(0xFFF0883E),
        onTertiary = Color(0xFF2B1200),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF0F6FC),
        surface = Color(0xFF0B0E14),
        onSurface = Color(0xFFF0F6FC),
        surfaceVariant = Color(0xFF161B22),
        onSurfaceVariant = Color(0xFF8B949E),
        outline = Color(0xFF30363D),
        error = Color(0xFFFF5252),
        onError = Color(0xFF290000)
    )

    private val OledNightExtended = SchwabQuantExtendedColors(
        profit = Color(0xFF00E676),
        loss = Color(0xFFFF5252),
        warning = Color(0xFFFFB300),
        shadowBannerBg = Color(0xFF2A1F00),
        shadowBannerFg = Color(0xFFFFD54F),
        liveBannerBg = Color(0xFF3B0909),
        liveBannerFg = Color(0xFFFF8A80),
        tokenHealthy = Color(0xFF00E676),
        tokenWarning = Color(0xFFFFB300),
        tokenCritical = Color(0xFFFF5252),
        surfaceElevated = Color(0xFF12161E),
        borderSubtle = Color(0xFF242A33)
    )

    private val TerminalAmberScheme = darkColorScheme(
        primary = Color(0xFFFFB000),
        onPrimary = Color(0xFF1C1300),
        primaryContainer = Color(0xFF332200),
        onPrimaryContainer = Color(0xFFFFDF8F),
        secondary = Color(0xFFFF8F00),
        onSecondary = Color(0xFF1F0F00),
        secondaryContainer = Color(0xFF2B1A00),
        onSecondaryContainer = Color(0xFFFFCC80),
        tertiary = Color(0xFF66FF66),
        onTertiary = Color(0xFF002200),
        background = Color(0xFF0B0906),
        onBackground = Color(0xFFFFE0A3),
        surface = Color(0xFF120F0A),
        onSurface = Color(0xFFFFE0A3),
        surfaceVariant = Color(0xFF1F1A12),
        onSurfaceVariant = Color(0xFFC2A366),
        outline = Color(0xFF4A3B22),
        error = Color(0xFFFF4D4D),
        onError = Color(0xFF2B0000)
    )

    private val TerminalAmberExtended = SchwabQuantExtendedColors(
        profit = Color(0xFF5CE65C),
        loss = Color(0xFFFF5733),
        warning = Color(0xFFFFB000),
        shadowBannerBg = Color(0xFF2E2204),
        shadowBannerFg = Color(0xFFFFCA45),
        liveBannerBg = Color(0xFF3E1008),
        liveBannerFg = Color(0xFFFF8A65),
        tokenHealthy = Color(0xFF5CE65C),
        tokenWarning = Color(0xFFFFB000),
        tokenCritical = Color(0xFFFF5733),
        surfaceElevated = Color(0xFF1A150E),
        borderSubtle = Color(0xFF3D311C)
    )

    private val StealthMidnightScheme = darkColorScheme(
        primary = Color(0xFF7CA7D9),
        onPrimary = Color(0xFF0A1929),
        primaryContainer = Color(0xFF182A40),
        onPrimaryContainer = Color(0xFFCFE2F7),
        secondary = Color(0xFF67B5A5),
        onSecondary = Color(0xFF07211C),
        secondaryContainer = Color(0xFF16322D),
        onSecondaryContainer = Color(0xFFB5E3D9),
        tertiary = Color(0xFFC7A46B),
        onTertiary = Color(0xFF241A08),
        background = Color(0xFF0B101B),
        onBackground = Color(0xFFD5DCE8),
        surface = Color(0xFF111827),
        onSurface = Color(0xFFD5DCE8),
        surfaceVariant = Color(0xFF1B2436),
        onSurfaceVariant = Color(0xFF8C9AB0),
        outline = Color(0xFF2E3B52),
        error = Color(0xFFE57373),
        onError = Color(0xFF2E0B0B)
    )

    private val StealthMidnightExtended = SchwabQuantExtendedColors(
        profit = Color(0xFF5CC9B0),
        loss = Color(0xFFE57373),
        warning = Color(0xFFD9B26F),
        shadowBannerBg = Color(0xFF1F2433),
        shadowBannerFg = Color(0xFFD9B26F),
        liveBannerBg = Color(0xFF361722),
        liveBannerFg = Color(0xFFF29B9B),
        tokenHealthy = Color(0xFF5CC9B0),
        tokenWarning = Color(0xFFD9B26F),
        tokenCritical = Color(0xFFE57373),
        surfaceElevated = Color(0xFF172033),
        borderSubtle = Color(0xFF27344D)
    )

    /**
     * Okabe-Ito colorblind-safe palette: uses Sky Blue (#56B4E9 / #0072B2) for bullish/profit
     * and Vermilion-Orange (#E69F00 / #D55E00) for bearish/loss to eliminate red-green confusion.
     */
    private val ColorblindSafeScheme = darkColorScheme(
        primary = Color(0xFF56B4E9),
        onPrimary = Color(0xFF001F30),
        primaryContainer = Color(0xFF0C3852),
        onPrimaryContainer = Color(0xFFCCE8FA),
        secondary = Color(0xFF009E73),
        onSecondary = Color(0xFF002116),
        secondaryContainer = Color(0xFF0B3B2D),
        onSecondaryContainer = Color(0xFFA6EAD3),
        tertiary = Color(0xFFF0E442),
        onTertiary = Color(0xFF222000),
        background = Color(0xFF0C1017),
        onBackground = Color(0xFFF3F6FA),
        surface = Color(0xFF131924),
        onSurface = Color(0xFFF3F6FA),
        surfaceVariant = Color(0xFF1D2535),
        onSurfaceVariant = Color(0xFFAAB6C8),
        outline = Color(0xFF3B4961),
        error = Color(0xFFD55E00),
        onError = Color(0xFF2E1100)
    )

    private val ColorblindSafeExtended = SchwabQuantExtendedColors(
        profit = Color(0xFF56B4E9),
        loss = Color(0xFFD55E00),
        warning = Color(0xFFF0E442),
        shadowBannerBg = Color(0xFF292607),
        shadowBannerFg = Color(0xFFF0E442),
        liveBannerBg = Color(0xFF3D1A00),
        liveBannerFg = Color(0xFFFFB47A),
        tokenHealthy = Color(0xFF56B4E9),
        tokenWarning = Color(0xFFE69F00),
        tokenCritical = Color(0xFFD55E00),
        surfaceElevated = Color(0xFF192130),
        borderSubtle = Color(0xFF2E3A4F)
    )
}
