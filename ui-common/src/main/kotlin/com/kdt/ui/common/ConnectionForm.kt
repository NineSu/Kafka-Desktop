package com.kdt.ui.common

import com.kdt.auth.AuthStrategy
import com.kdt.auth.PlaintextAuth
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * Reusable connection form: text field for bootstrap.servers, "Auth..." button, Connect button, status label.
 * Holds both [bootstrapServers] and [authStrategy] as properties; UI consumers read them on connect.
 */
class ConnectionForm : VBox() {

    private val bootstrapField = TextField("localhost:9092").apply {
        promptText = "host1:9092,host2:9092"
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    private val authButton = Button("Auth: PLAINTEXT").apply {
        style = "-fx-base: #ecf0f1;"
    }

    private val connectButton = Button("Connect")

    private val statusLabel = Label("").apply {
        styleClass.add("status-label")
    }

    val bootstrapServers = SimpleStringProperty("localhost:9092")
    val authStrategy: SimpleObjectProperty<AuthStrategy> = SimpleObjectProperty(PlaintextAuth)
    private val authState = AuthFormState()

    var onConnect: () -> Unit = {}

    init {
        spacing = 8.0
        padding = Insets(12.0)

        bootstrapField.textProperty().bindBidirectional(bootstrapServers)
        connectButton.onAction = EventHandler { onConnect() }
        authButton.onAction = EventHandler { openAuthDialog() }

        val row = HBox(8.0, Label("Bootstrap servers:"), bootstrapField, authButton, connectButton).apply {
            alignment = Pos.CENTER_LEFT
        }
        children.addAll(row, statusLabel)
    }

    private fun openAuthDialog() {
        val dialog = AuthDialog(authState)
        val result = dialog.showAndWait()
        if (result.isPresent) {
            authStrategy.value = result.get()
            authButton.text = "Auth: ${labelFor(authState)}"
        }
    }

    private fun labelFor(state: AuthFormState): String {
        val p = state.protocol.name
        return when (state.protocol) {
            SecurityProtocol.SASL_PLAINTEXT, SecurityProtocol.SASL_SSL -> "$p / ${state.saslMechanism.name}"
            else -> p
        }
    }

    fun setStatus(text: String, error: Boolean = false) {
        statusLabel.text = text
        statusLabel.style = if (error) "-fx-text-fill: #c0392b;" else "-fx-text-fill: #2c3e50;"
    }

    fun setBusy(busy: Boolean) {
        connectButton.isDisable = busy
        bootstrapField.isDisable = busy
        authButton.isDisable = busy
    }
}
