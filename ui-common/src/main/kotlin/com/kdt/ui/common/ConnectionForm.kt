package com.kdt.ui.common

import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * Connection bar: a dropdown of saved connections, a "Manage…" button that opens
 * the [ConnectionManagerDialog], and a Connect button.
 *
 * The form holds no bootstrap text field anymore — every connection must be saved
 * first (see iter-7 design). The app populates [connections] from the ConnectionStore
 * and reacts to [onConnect] / [onManage].
 */
class ConnectionForm : VBox() {

    val connections: ObservableList<ConnectionVM> = FXCollections.observableArrayList()
    val selected = SimpleObjectProperty<ConnectionVM?>(null)

    private val connectionBox = ComboBox<ConnectionVM>(connections).apply {
        promptText = "Select a connection…"
        HBox.setHgrow(this, Priority.ALWAYS)
        maxWidth = Double.MAX_VALUE
    }
    private val manageButton = Button("Manage…")
    private val connectButton = Button("Connect").apply { isDisable = true }
    private val statusLabel = Label("").apply { styleClass.add("status-label") }

    var onConnect: () -> Unit = {}
    var onManage: () -> Unit = {}

    init {
        spacing = 8.0
        padding = Insets(12.0)

        connectionBox.valueProperty().addListener { _, _, v ->
            selected.value = v
            connectButton.isDisable = v == null
        }
        connectButton.onAction = EventHandler { onConnect() }
        manageButton.onAction = EventHandler { onManage() }

        val row = HBox(8.0, Label("Connection:"), connectionBox, manageButton, connectButton).apply {
            alignment = Pos.CENTER_LEFT
        }
        children.addAll(row, statusLabel)
    }

    /** Replace the dropdown contents, preserving the selection by id when possible. */
    fun setConnections(items: List<ConnectionVM>, selectId: String? = null) {
        val keepId = selectId ?: selected.value?.id
        connections.setAll(items)
        val toSelect = items.firstOrNull { it.id == keepId } ?: items.firstOrNull()
        connectionBox.value = toSelect
    }

    fun selectedConnection(): ConnectionVM? = connectionBox.value

    fun setStatus(text: String, error: Boolean = false) {
        statusLabel.text = text
        statusLabel.styleClass.setAll("status-label", if (error) "status-err" else "status-info")
    }

    fun setBusy(busy: Boolean) {
        connectButton.isDisable = busy || connectionBox.value == null
        connectionBox.isDisable = busy
        manageButton.isDisable = busy
    }
}
