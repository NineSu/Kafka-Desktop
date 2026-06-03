package com.kdt.ui.common

import com.kdt.ui.common.theme.AccentPreset
import com.kdt.ui.common.theme.ThemeColors
import com.kdt.ui.common.theme.ThemeManager
import com.kdt.ui.common.theme.ThemeMode
import com.kdt.ui.common.theme.applyTheme
import javafx.event.ActionEvent
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.ColorPicker
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color

/**
 * Settings → Appearance & Behavior → Appearance. Lets the user switch
 * Light/Dark and pick an accent (7 presets + custom ColorPicker), with a
 * live preview. Changes apply immediately via [ThemeManager]; Cancel restores
 * the snapshot taken on open, Apply commits the current state as the new baseline.
 */
class SettingsDialog : Dialog<Unit>() {

    private var snapMode: ThemeMode = ThemeManager.mode
    private var snapAccent: String = ThemeManager.accentHex

    private val swatches = mutableListOf<Button>()
    private val hexLabel = Label(ThemeManager.accentHex).apply { styleClass.add("mono") }
    private val picker = ColorPicker(Color.web(ThemeManager.accentHex))

    init {
        title = "Settings"
        isResizable = true

        val content = HBox(buildNav(), buildAppearancePane()).apply { styleClass.add("settings-root") }
        dialogPane.content = content
        dialogPane.buttonTypes.addAll(ButtonType.CANCEL, ButtonType.APPLY, ButtonType.OK)
        applyTheme()

        // APPLY: re-baseline the snapshot and keep the dialog open.
        (dialogPane.lookupButton(ButtonType.APPLY) as Button).addEventFilter(ActionEvent.ACTION) { e ->
            snapMode = ThemeManager.mode
            snapAccent = ThemeManager.accentHex
            e.consume()
        }
        // CANCEL: restore the snapshot. OK: keep current (no-op).
        setResultConverter { btn ->
            if (btn == ButtonType.CANCEL) {
                ThemeManager.setMode(snapMode)
                ThemeManager.setAccent(snapAccent)
            }
            null
        }
        refreshSelectedSwatch()
    }

    private fun buildNav(): VBox = VBox(
        Label("Appearance & Behavior").apply { styleClass.add("settings-nav-group") },
        Label("Appearance").apply { styleClass.addAll("settings-nav-item", "settings-nav-item-selected") },
        Label("Behavior").apply { styleClass.add("settings-nav-item") },
        Label("Connections").apply { styleClass.add("settings-nav-item"); isDisable = true },
        Label("Keymap").apply { styleClass.add("settings-nav-item"); isDisable = true },
    ).apply { styleClass.add("settings-nav") }

    private fun buildAppearancePane(): VBox {
        // --- Theme (Light/Dark) ---
        val group = ToggleGroup()
        val light = ToggleButton("Light").apply { toggleGroup = group; isSelected = ThemeManager.mode == ThemeMode.LIGHT }
        val dark = ToggleButton("Dark").apply { toggleGroup = group; isSelected = ThemeManager.mode == ThemeMode.DARK }
        light.setOnAction { if (light.isSelected) ThemeManager.setMode(ThemeMode.LIGHT) else light.isSelected = true }
        dark.setOnAction { if (dark.isSelected) ThemeManager.setMode(ThemeMode.DARK) else dark.isSelected = true }
        val themeRow = HBox(0.0, light, dark)

        // --- Accent presets ---
        val palette = FlowPane(9.0, 9.0)
        AccentPreset.entries.forEach { preset ->
            val sw = Button().apply {
                styleClass.add("accent-swatch")
                style = "-fx-background-color: ${preset.hex};"
                setOnAction { selectAccent(preset.hex) }
            }
            swatches.add(sw)
            palette.children.add(sw)
        }
        picker.setOnAction { selectAccent(ThemeColors.toHex(picker.value)) }
        val customRow = HBox(9.0, palette, picker, hexLabel).apply { alignment = Pos.CENTER_LEFT }

        // --- Preview ---
        val preview = HBox(14.0,
            Button("Connect").apply { styleClass.add("primary-button") },
            Label("orders.created").apply { styleClass.add("chip") },
            Label("Save filter…").apply { styleClass.add("link-label") },
        ).apply { alignment = Pos.CENTER_LEFT; styleClass.add("preview-pane") }

        return VBox(8.0,
            Label("THEME").apply { styleClass.add("section-label") },
            themeRow,
            spacer(),
            Label("ACCENT COLOR").apply { styleClass.add("section-label") },
            customRow,
            spacer(),
            Label("PREVIEW").apply { styleClass.add("section-label") },
            preview,
        ).apply { styleClass.add("settings-pane"); HBox.setHgrow(this, Priority.ALWAYS) }
    }

    private fun selectAccent(hex: String) {
        ThemeManager.setAccent(hex)
        hexLabel.text = ThemeManager.accentHex
        picker.value = Color.web(ThemeManager.accentHex)
        refreshSelectedSwatch()
    }

    private fun refreshSelectedSwatch() {
        AccentPreset.entries.forEachIndexed { i, preset ->
            val sw = swatches[i]
            if (preset.hex.equals(ThemeManager.accentHex, ignoreCase = true)) {
                if (!sw.styleClass.contains("accent-swatch-selected")) sw.styleClass.add("accent-swatch-selected")
            } else {
                sw.styleClass.remove("accent-swatch-selected")
            }
        }
    }

    private fun spacer() = Region().apply { minHeight = 6.0 }
}
