package com.schwabquant.app.presentation.theme

enum class AppThemeType(
    val displayName: String,
    val subtitle: String,
    val isDark: Boolean
) {
    INSTITUTIONAL_DAY(
        displayName = "Institutional Day",
        subtitle = "Crisp daylight slate & navy desk palette",
        isDark = false
    ),
    OLED_NIGHT(
        displayName = "OLED Night",
        subtitle = "True #000000 black for midnight monitoring",
        isDark = true
    ),
    TERMINAL_AMBER(
        displayName = "Terminal Amber",
        subtitle = "High-contrast classic quant terminal amber",
        isDark = true
    ),
    STEALTH_MIDNIGHT(
        displayName = "Stealth Midnight",
        subtitle = "Low-glare navy slate for prolonged desk sessions",
        isDark = true
    ),
    COLORBLIND_SAFE(
        displayName = "Colorblind Safe",
        subtitle = "Okabe-Ito blue/vermilion P&L semantic palette",
        isDark = true
    );

    companion object {
        fun fromStorageKey(key: String?): AppThemeType =
            entries.firstOrNull { it.name == key } ?: OLED_NIGHT
    }
}
