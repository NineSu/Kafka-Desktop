package com.kdt.app

import com.kdt.storage.ExportFormat
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.ChoiceBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.layout.GridPane

/** What [ImportDialog] returns: the chosen file format and target topic. */
data class ImportRequest(val format: ExportFormat, val topic: String)

/**
 * After a file is chosen, picks the parse format (pre-filled from the file extension)
 * and the target topic to send every row to. Returns null on cancel.
 */
class ImportDialog(
    fileName: String,
    topics: List<String>,
    inferredFormat: ExportFormat,
    defaultTopic: String?,
) : Dialog<ImportRequest>() {

    private val formatBox = ChoiceBox(FXCollections.observableArrayList(*ExportFormat.values())).apply {
        value = inferredFormat
    }
    private val topicBox = ComboBox(FXCollections.observableArrayList(topics)).apply {
        value = defaultTopic ?: topics.firstOrNull()
        promptText = "target topic"
    }

    init {
        title = "Import messages"
        headerText = "Send rows from \"$fileName\" to a topic"

        val grid = GridPane().apply { hgap = 10.0; vgap = 8.0; padding = Insets(12.0) }
        grid.add(Label("Format:"), 0, 0); grid.add(formatBox, 1, 0)
        grid.add(Label("Target topic:"), 0, 1); grid.add(topicBox, 1, 1)
        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val ok = dialogPane.lookupButton(ButtonType.OK)
        ok.isDisable = topicBox.value == null
        topicBox.valueProperty().addListener { _, _, v -> ok.isDisable = v == null }

        setResultConverter { btn ->
            if (btn != ButtonType.OK) null
            else topicBox.value?.let { ImportRequest(formatBox.value, it) }
        }
    }
}
