package com.kdt.ui.common

import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox

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
    }
    private val partitionField = TextField().apply { promptText = "auto" }
    private val keyField = TextField(initialKey.orEmpty())
    private val valueArea = TextArea(initialValue.orEmpty()).apply {
        prefRowCount = 8
        style = "-fx-font-family: monospace; -fx-font-size: 12;"
    }
    private val headersTable = TableView<HeaderRow>().apply {
        prefHeight = 110.0
        isEditable = true
    }
    private val headerRows = FXCollections.observableArrayList<HeaderRow>().apply {
        addAll(initialHeaders.map { (k, v) -> HeaderRow(k, v ?: "") })
    }

    init {
        title = "Send message"
        headerText = "Produce a record to the broker."

        val nameCol = TableColumn<HeaderRow, String>("Header").apply {
            cellValueFactory = PropertyValueFactory("name")
            cellFactory = TextFieldTableCell.forTableColumn()
            isEditable = true
            prefWidth = 180.0
            setOnEditCommit { e -> e.rowValue.nameProperty.set(e.newValue ?: "") }
        }
        val valCol = TableColumn<HeaderRow, String>("Value").apply {
            cellValueFactory = PropertyValueFactory("value")
            cellFactory = TextFieldTableCell.forTableColumn()
            isEditable = true
            prefWidth = 380.0
            setOnEditCommit { e -> e.rowValue.valueProperty.set(e.newValue ?: "") }
        }
        headersTable.columns.setAll(nameCol, valCol)
        headersTable.items = headerRows

        val addHeaderBtn = Button("+ Header").apply { setOnAction { headerRows.add(HeaderRow("", "")) } }
        val rmHeaderBtn = Button("✕ Header").apply {
            setOnAction { headersTable.selectionModel.selectedItem?.let(headerRows::remove) }
        }
        val headersBar = HBox(8.0, Label("Headers:"), addHeaderBtn, rmHeaderBtn)

        val grid = GridPane().apply {
            hgap = 8.0; vgap = 8.0; padding = Insets(12.0)
        }
        var row = 0
        grid.add(Label("Topic:"), 0, row); grid.add(topicBox, 1, row++)
        grid.add(Label("Partition:"), 0, row); grid.add(partitionField, 1, row++)
        grid.add(Label("Key:"), 0, row); grid.add(keyField, 1, row++)
        grid.add(Label("Value:"), 0, row); grid.add(valueArea, 1, row++)
        grid.add(headersBar, 0, row, 2, 1); row++
        grid.add(headersTable, 0, row, 2, 1)

        topicBox.prefWidth = 320.0
        partitionField.prefWidth = 120.0
        keyField.prefWidth = 480.0
        valueArea.prefWidth = 480.0

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
                    .filter { it.getName().isNotBlank() }
                    .associate { it.getName() to it.getValue().toByteArray() },
            )
        }
    }
}

class HeaderRow(name: String, value: String) {
    val nameProperty = SimpleStringProperty(name)
    val valueProperty = SimpleStringProperty(value)
    fun getName(): String = nameProperty.get()
    fun getValue(): String = valueProperty.get()
}
