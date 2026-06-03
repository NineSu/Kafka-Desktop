package com.kdt.ui.common.theme

import javafx.scene.paint.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeColorsTest {

    @Test
    fun `valid 6-digit hex passes`() {
        assertTrue(ThemeColors.isValidHex("#3574F0"))
        assertTrue(ThemeColors.isValidHex("#abcdef"))
    }

    @Test
    fun `invalid hex fails`() {
        assertFalse(ThemeColors.isValidHex("3574F0"))   // no #
        assertFalse(ThemeColors.isValidHex("#FFF"))      // 3-digit
        assertFalse(ThemeColors.isValidHex("#GGGGGG"))   // non-hex
        assertFalse(ThemeColors.isValidHex(""))
    }

    @Test
    fun `normalize uppercases and trims, rejects invalid`() {
        assertEquals("#ABCDEF", ThemeColors.normalizeHex("  #abcdef "))
        assertNull(ThemeColors.normalizeHex("nope"))
    }

    @Test
    fun `toHex formats an RGB color`() {
        assertEquals("#FF8800", ThemeColors.toHex(Color.web("#FF8800")))
        assertEquals("#000000", ThemeColors.toHex(Color.BLACK))
        assertEquals("#FFFFFF", ThemeColors.toHex(Color.WHITE))
    }
}
