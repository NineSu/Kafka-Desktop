package com.kdt.ui.common.theme

import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Dialog
import javafx.scene.control.DialogPane
import java.util.Collections
import java.util.WeakHashMap

/**
 * Central theme state + applier. Persistence-agnostic: holds the current
 * [mode] + [accentHex], applies them to every registered Scene/DialogPane,
 * and notifies listeners on change. The app module wires a listener to persist.
 *
 * Applying = (1) ensure islands.css is attached, (2) swap the theme-light/
 * theme-dark style class on the root, (3) set an inline `-accent:` so JavaFX
 * re-derives selection/hover colors instantly.
 */
object ThemeManager {

    private val cssUrl: String = ThemeManager::class.java
        .getResource("/com/kdt/ui/common/theme/islands.css")
        ?.toExternalForm()
        ?: error("islands.css not found on classpath")

    var mode: ThemeMode = ThemeMode.LIGHT
        private set
    var accentHex: String = AccentPreset.DEFAULT.hex
        private set

    private val roots = Collections.newSetFromMap(WeakHashMap<Parent, Boolean>())
    private val listeners = mutableListOf<(ThemeMode, String) -> Unit>()

    /** Set initial state without notifying (called once at startup from persisted settings). */
    fun seed(mode: ThemeMode, accentHex: String) {
        this.mode = mode
        this.accentHex = ThemeColors.normalizeHex(accentHex) ?: AccentPreset.DEFAULT.hex
    }

    fun addListener(listener: (ThemeMode, String) -> Unit) { listeners.add(listener) }

    fun register(scene: Scene) {
        if (!scene.stylesheets.contains(cssUrl)) scene.stylesheets.add(cssUrl)
        apply(scene.root)
        roots.add(scene.root)
    }

    fun register(pane: DialogPane) {
        if (!pane.stylesheets.contains(cssUrl)) pane.stylesheets.add(cssUrl)
        apply(pane)
        roots.add(pane)
    }

    fun setMode(newMode: ThemeMode) {
        if (newMode == mode) return
        mode = newMode
        roots.forEach(::apply)
        notifyListeners()
    }

    fun setAccent(hex: String) {
        val normalized = ThemeColors.normalizeHex(hex) ?: return
        if (normalized == accentHex) return
        accentHex = normalized
        roots.forEach(::apply)
        notifyListeners()
    }

    private fun apply(root: Parent) {
        root.styleClass.removeAll("theme-light", "theme-dark")
        root.styleClass.add(mode.styleClass)
        root.style = "-accent: $accentHex;"
    }

    private fun notifyListeners() {
        val snapshot = listeners.toList()
        snapshot.forEach { it(mode, accentHex) }
    }
}

/** Apply the current theme to any dialog (works for Alert too — it extends Dialog). */
fun Dialog<*>.applyTheme() {
    ThemeManager.register(this.dialogPane)
}
