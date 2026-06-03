package com.kdt.ui.common.theme

import javafx.scene.paint.Color

/** Pure helpers for accent-color handling. No JavaFX toolkit required. */
object ThemeColors {
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    fun isValidHex(s: String): Boolean = HEX.matches(s.trim())

    /** Returns the trimmed, upper-cased `#RRGGBB`, or null if not a valid 6-digit hex. */
    fun normalizeHex(s: String): String? = s.trim().uppercase().takeIf { HEX.matches(it) }

    /** Formats a JavaFX [Color]'s RGB channels as `#RRGGBB`. */
    fun toHex(c: Color): String = String.format(
        "#%02X%02X%02X",
        Math.round(c.red * 255).toInt(),
        Math.round(c.green * 255).toInt(),
        Math.round(c.blue * 255).toInt(),
    )
}
