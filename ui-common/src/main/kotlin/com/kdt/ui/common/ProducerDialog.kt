package com.kdt.ui.common

import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * Result of the producer dialog. Bytes are produced by the dialog from the user's text input;
 * the calling code passes them to KafkaMessageProducer.send.
 */
data class ProducerRequest(
    val topic: String,
    val partition: Int?,
    val key: ByteArray?,
    val value: ByteArray?,
    val headers: Map<String, ByteArray?>,
)

class ProducerDialog(
    topics: List<String>,
    initialTopic: String? = null,
    initialKey: String? = null,
    initialValue: String? = null,
    initialHeaders: Map<String, String?> = emptyMap(),
) : Dialog<ProducerRequest>() {

    private val topicBox = ComboBox<String>().apply {
        items.setAll(topics)
        value = initialTopic ?: topics.firstOrNull()
        isEditable = true
        prefWidth = 320.0
    }
    private val partitionField = TextField().apply { promptText = "auto"; prefWidth = 120.0 }
    private val keyField = TextField(initialKey.orEmpty()).apply { prefWidth = 480.0 }
    private val valueArea = TextArea(initialValue.orEmpty()).apply {
        prefRowCount = 8
        prefWidth = 480.0
        style = "-fx-font-family: monospace; -fx-font-size: 12;"
    }
    private val headersContainer = VBox(4.0)
    private val headerRows = FXCollections.observableArrayList<HeaderEditorRow>()

    init {
        title = "Send message"
        headerText = "Produce a record to the broker."

        // Seed initial headers
        if (initialHeaders.isEmpty()) {
            addHeaderRow("", "")
        } else {
            initialHeaders.forEach { (k, v) -> addHeaderRow(k, v ?: "") }
        }

        val addHeaderBtn = Button("+ Header").apply { setOnAction { addHeaderRow("", "") } }
        val headersBar = HBox(8.0, Label("Headers:"), addHeaderBtn).apply { alignment = Pos.CENTER_LEFT }

        val headersScroll = ScrollPane(headersContainer).apply {
            isFitToWidth = true
            prefHeight = 130.0
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        }

        val grid = GridPane().apply { hgap = 8.0; vgap = 8.0; padding = Insets(12.0) }
        var row = 0
        grid.add(Label("Topic:"), 0, row); grid.add(topicBox, 1, row++)
        grid.add(Label("Partition:"), 0, row); grid.add(partitionField, 1, row++)
        grid.add(Label("Key:"), 0, row); grid.add(keyField, 1, row++)
        grid.add(Label("Value:"), 0, row); grid.add(valueArea, 1, row++)
        grid.add(headersBar, 0, row, 2, 1); row++
        grid.add(headersScroll, 0, row, 2, 1)

        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        (dialogPane.lookupButton(ButtonType.OK) as? Button)?.text = "Send"

        setResultConverter { btn ->
            if (btn != ButtonType.OK) null
            else ProducerRequest(
                topic = topicBox.value.orEmpty().trim(),
                partition = partitionField.text?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull(),
                key = keyField.text?.takeIf { it.isNotEmpty() }?.toByteArray(),
                value = valueArea.text?.takeIf { it.isNotEmpty() }?.toByteArray(),
                headers = headerRows
                    .filter { it.name.isNotBlank() }
                    .associate { it.name to it.valueOrNull?.toByteArray() },
            )
        }
    }

    private fun addHeaderRow(name: String, value: String) {
        val row = HeaderEditorRow(name, value) { toRemove -> removeHeaderRow(toRemove) }
        headerRows.add(row)
        headersContainer.children.add(row.node)
    }

    private fun removeHeaderRow(row: HeaderEditorRow) {
        headerRows.remove(row)
        headersContainer.children.remove(row.node)
    }
}

/** A single editable header row: [name | value | ✕]. Bound to live TextFields so values are always current. */
private class HeaderEditorRow(
    initialName: String,
    initialValue: String,
    onRemove: (HeaderEditorRow) -> Unit,
) {
    private val nameField = TextField(initialName).apply { promptText = "header-name"; prefWidth = 180.0 }
    private val valueField = TextField(initialValue).apply { promptText = "header value"; HBox.setHgrow(this, Priority.ALWAYS) }
    private val removeBtn = Button("✕").apply { setOnAction { onRemove(this@HeaderEditorRow) } }

    val node: HBox = HBox(6.0, nameField, valueField, removeBtn).apply { alignment = Pos.CENTER_LEFT }

    val name: String get() = nameField.text?.trim().orEmpty()
    val valueOrNull: String? get() = valueField.text
}
