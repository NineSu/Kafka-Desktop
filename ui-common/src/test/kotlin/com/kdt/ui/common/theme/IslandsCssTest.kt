package com.kdt.ui.common.theme

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IslandsCssTest {

    @Test
    fun `islands css is on the classpath and defines both themes plus accent`() {
        val url = javaClass.getResource("/com/kdt/ui/common/theme/islands.css")
        assertNotNull(url, "islands.css must be at /com/kdt/ui/common/theme/islands.css")
        val css = url!!.readText()
        assertTrue(css.contains(".theme-light"), "missing .theme-light")
        assertTrue(css.contains(".theme-dark"), "missing .theme-dark")
        assertTrue(css.contains("-accent"), "missing -accent token")
        assertTrue(css.contains(".island"), "missing .island")
        assertTrue(css.contains(".status-ok"), "missing .status-ok")
    }
}
