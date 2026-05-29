package com.kdt.app

import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane

/** What [SaveFilterDialog] returns: the filter name and whether it is scoped to a topic. */
data class SaveFilterRequest(val name: String, val topic: String?)

/**
 * Names a filter and chooses its scope: global, or bound to [currentTopic]. When there is
 * no current topic, only the global option is available. Returns null on cancel / blank name.
 */
class SaveFilterDialog(currentTopic: String?, suggestedName: String = "") : Dialog<SaveFilterRequest>() {

    private val nameField = TextField(suggestedName).apply { promptText = "filter name" }
    private val group = ToggleGroup()
    private val globalRadio = RadioButton("Global (all topics)").apply { toggleGroup = group; isSelected = true }
    private val topicRadio = RadioButton("Only topic \"$currentTopic\"").apply {
        toggleGroup = group
        isDisable = currentTopic == null
    }

    init {
        title = "Save filter"
        val grid = GridPane().apply { hgap = 10.0; vgap = 8.0; padding = Insets(12.0) }
        var row = 0
        grid.add(Label("Name:"), 0, row); grid.add(nameField, 1, row++)
        grid.add(Label("Scope:"), 0, row); grid.add(globalRadio, 1, row++)
        grid.add(topicRadio, 1, row)

        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val ok = dialogPane.lookupButton(ButtonType.OK)
        ok.isDisable = suggestedName.isBlank()
        nameField.textProperty().addListener { _, _, v -> ok.isDisable = v.trim().isEmpty() }

        setResultConverter { btn ->
            if (btn != ButtonType.OK) return@setResultConverter null
            val name = nameField.text.trim()
            if (name.isEmpty()) return@setResultConverter null
            val topic = if (topicRadio.isSelected) currentTopic else null
            SaveFilterRequest(name, topic)
        }
    }
}
