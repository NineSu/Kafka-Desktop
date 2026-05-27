package com.kdt.ui.common

import javafx.beans.property.SimpleStringProperty
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * Reusable connection form: a text field for bootstrap.servers, a Connect button, and a status label.
 *
 * Consumers register an onConnect handler. The form itself does no Kafka IO — it only collects input
 * and surfaces status updates set via [setStatus] / [setBusy].
 */
class ConnectionForm : VBox() {

    private val bootstrapField = TextField("localhost:9092").apply {
        promptText = "host1:9092,host2:9092"
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    private val connectButton = Button("Connect")

    private val statusLabel = Label("").apply {
        styleClass.add("status-label")
    }

    val bootstrapServers = SimpleStringProperty("localhost:9092")

    var onConnect: () -> Unit = {}

    init {
        spacing = 8.0
        padding = Insets(12.0)

        bootstrapField.textProperty().bindBidirectional(bootstrapServers)
        connectButton.onAction = EventHandler { onConnect() }

        val row = HBox(8.0, Label("Bootstrap servers:"), bootstrapField, connectButton).apply {
            alignment = javafx.geometry.Pos.CENTER_LEFT
        }
        children.addAll(row, statusLabel)
    }

    fun setStatus(text: String, error: Boolean = false) {
        statusLabel.text = text
        statusLabel.style = if (error) "-fx-text-fill: #c0392b;" else "-fx-text-fill: #2c3e50;"
    }

    fun setBusy(busy: Boolean) {
        connectButton.isDisable = busy
        bootstrapField.isDisable = busy
    }
}
