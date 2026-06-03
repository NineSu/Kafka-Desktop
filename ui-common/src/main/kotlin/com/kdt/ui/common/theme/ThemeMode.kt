package com.kdt.ui.common.theme

/** The two Islands palettes. [styleClass] is the root CSS class; [storageKey] is the persisted string. */
enum class ThemeMode(val storageKey: String, val styleClass: String) {
    LIGHT("light", "theme-light"),
    DARK("dark", "theme-dark");

    companion object {
        fun fromStorage(key: String): ThemeMode =
            entries.firstOrNull { it.storageKey == key.trim().lowercase() } ?: LIGHT
    }
}
