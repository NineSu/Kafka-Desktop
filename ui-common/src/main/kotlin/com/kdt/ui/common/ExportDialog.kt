package com.kdt.ui.common

import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.VBox

/**
 * UI-local result of [ExportDialog]. The app maps this to core-storage's `ExportFormat`
 * (keeps ui-common free of the core-storage/DuckDB dependency).
 */
enum class ExportChoice { CSV, JSON, JSONL }

/** Picks an export format for the current filtered result set. Defaults to CSV. */
class ExportDialog(rowCount: Long) : Dialog<ExportChoice>() {

    private val group = ToggleGroup()
    private val csvRadio = RadioButton("CSV").apply { toggleGroup = group; isSelected = true }
    private val jsonRadio = RadioButton("JSON (array)").apply { toggleGroup = group }
    private val jsonlRadio = RadioButton("JSON Lines (.jsonl)").apply { toggleGroup = group }

    init {
        title = "Export messages"
        headerText = "Export $rowCount message(s) matching the current filter"

        val box = VBox(8.0, Label("Format:"), csvRadio, jsonRadio, jsonlRadio).apply {
            padding = Insets(12.0)
        }
        dialogPane.content = box
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        setResultConverter { btn ->
            if (btn != ButtonType.OK) null
            else when (group.selectedToggle) {
                jsonRadio -> ExportChoice.JSON
                jsonlRadio -> ExportChoice.JSONL
                else -> ExportChoice.CSV
            }
        }
    }
}
