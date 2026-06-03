package com.kdt.ui.common.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ThemeManagerTest {

    @BeforeEach
    fun reset() {
        // Known starting point; seed() does not notify listeners.
        ThemeManager.seed(ThemeMode.LIGHT, AccentPreset.DEFAULT.hex)
    }

    @Test
    fun `seed sets mode and accent`() {
        ThemeManager.seed(ThemeMode.DARK, "#8A5CF6")
        assertEquals(ThemeMode.DARK, ThemeManager.mode)
        assertEquals("#8A5CF6", ThemeManager.accentHex)
    }

    @Test
    fun `seed with invalid accent falls back to default`() {
        ThemeManager.seed(ThemeMode.LIGHT, "garbage")
        assertEquals(AccentPreset.DEFAULT.hex, ThemeManager.accentHex)
    }

    @Test
    fun `setMode updates state and notifies listener`() {
        var seen: ThemeMode? = null
        ThemeManager.addListener { m, _ -> seen = m }
        ThemeManager.setMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, ThemeManager.mode)
        assertEquals(ThemeMode.DARK, seen)
    }

    @Test
    fun `setAccent normalizes and notifies`() {
        var seenHex: String? = null
        ThemeManager.addListener { _, hex -> seenHex = hex }
        ThemeManager.setAccent("#abcdef")
        assertEquals("#ABCDEF", ThemeManager.accentHex)
        assertEquals("#ABCDEF", seenHex)
    }

    @Test
    fun `setAccent ignores invalid hex`() {
        var calls = 0
        ThemeManager.addListener { _, _ -> calls++ }
        ThemeManager.setAccent("not-a-color")
        assertEquals(AccentPreset.DEFAULT.hex, ThemeManager.accentHex)
        assertEquals(0, calls)
    }
}
