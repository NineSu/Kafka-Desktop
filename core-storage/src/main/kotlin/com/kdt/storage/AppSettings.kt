package com.kdt.storage

/**
 * Persisted UI appearance settings. Written as plain JSON (no secrets) to
 * `~/.kafka-desktop/appearance.json` by [AppSettingsStore].
 *
 * @param themeMode "light" or "dark" (the storage key of ui-common's ThemeMode)
 * @param accentHex "#RRGGBB" accent color
 */
data class AppSettings(
    val themeMode: String = "light",
    val accentHex: String = "#3574F0",
)
