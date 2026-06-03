package com.kdt.ui.common

import javafx.collections.ObservableList
import com.kdt.ui.common.theme.applyTheme
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

/**
 * Manages the saved-connection list: add, edit, delete, and test connectivity.
 *
 * Operates directly on the shared [connections] ObservableList (also bound to the
 * ConnectionForm dropdown, so edits reflect live). Persistence and connectivity
 * testing are delegated to the app via callbacks:
 *  - [onSave]: persist an added/edited connection (incl. secrets).
 *  - [onDelete]: remove a connection by id.
 *  - [onTest]: test connectivity; invoke the result callback with a human-readable message.
 *  - [onLoadSecrets]: populate a VM's auth secrets from the vault before editing.
 */
class ConnectionManagerDialog(
    private val connections: ObservableList<ConnectionVM>,
    private val onSave: (ConnectionVM) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onTest: (ConnectionVM, (String, Boolean) -> Unit) -> Unit,
    private val onLoadSecrets: (ConnectionVM) -> Unit,
) : Dialog<Unit>() {

    private val listView = ListView(connections)
    private val testResult = Label("")

    init {
        title = "Manage connections"
        headerText = "Saved Kafka connections"

        val addBtn = Button("Add…").apply { setOnAction { addConnection() } }
        val editBtn = Button("Edit…").apply { setOnAction { editSelected() } }
        val delBtn = Button("Delete").apply { setOnAction { deleteSelected() } }
        val testBtn = Button("Test").apply { setOnAction { testSelected() } }

        val buttons = VBox(8.0, addBtn, editBtn, delBtn, testBtn).apply { padding = Insets(0.0, 0.0, 0.0, 10.0) }
        val pane = BorderPane().apply {
            center = listView
            right = buttons
            bottom = testResult
            padding = Insets(12.0)
            prefWidth = 520.0
            prefHeight = 320.0
        }
        dialogPane.content = pane
        dialogPane.buttonTypes.add(ButtonType.CLOSE)
        applyTheme()
    }

    private fun addConnection() {
        val vm = ConnectionVM(name = "New connection")
        if (ConnectionEditDialog(vm).showAndWait().orElse(false)) {
            connections.add(vm)
            listView.selectionModel.select(vm)
            onSave(vm)
        }
    }

    private fun editSelected() {
        val vm = listView.selectionModel.selectedItem ?: return
        onLoadSecrets(vm)
        if (ConnectionEditDialog(vm).showAndWait().orElse(false)) {
            // Force the cell to re-render the (possibly changed) label.
            val idx = connections.indexOf(vm)
            if (idx >= 0) connections[idx] = vm
            onSave(vm)
        }
    }

    private fun deleteSelected() {
        val vm = listView.selectionModel.selectedItem ?: return
        connections.remove(vm)
        onDelete(vm.id)
        testResult.text = "Deleted ${vm.name}"
        testResult.styleClass.setAll("status-label", "status-info")
    }

    private fun testSelected() {
        val vm = listView.selectionModel.selectedItem ?: return
        onLoadSecrets(vm)
        testResult.text = "Testing ${vm.name}…"
        testResult.styleClass.setAll("status-label", "status-info")
        onTest(vm) { msg, ok ->
            testResult.text = msg
            testResult.styleClass.setAll("status-label", if (ok) "status-ok" else "status-err")
        }
    }
}

/**
 * Edits one connection: name + bootstrap.servers + an "Auth…" button that opens
 * the existing [AuthDialog]. Returns true if the user confirmed (OK), false on cancel.
 * Edits the VM's name/bootstrap in place; auth is edited on a copy and committed only on OK.
 */
private class ConnectionEditDialog(private val vm: ConnectionVM) : Dialog<Boolean>() {

    private val nameField = TextField(vm.name).apply { promptText = "My cluster" }
    private val bootstrapField = TextField(vm.bootstrap).apply { promptText = "host1:9092,host2:9092" }
    private val workingAuth = vm.authState.copy()
    private val authButton = Button(authLabel(workingAuth)).apply {
        setOnAction {
            val result = AuthDialog(workingAuth).showAndWait()
            if (result.isPresent) text = authLabel(workingAuth)
        }
    }

    init {
        title = "Connection"
        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(12.0)
        }
        var row = 0
        grid.add(Label("Name:"), 0, row); grid.add(nameField, 1, row++)
        grid.add(Label("Bootstrap servers:"), 0, row); grid.add(bootstrapField, 1, row++)
        grid.add(Label("Auth:"), 0, row); grid.add(HBox(authButton), 1, row)

        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        setResultConverter { btn ->
            if (btn != ButtonType.OK) {
                false
            } else {
                vm.name = nameField.text.trim()
                vm.bootstrap = bootstrapField.text.trim()
                copyAuthInto(workingAuth, vm.authState)
                true
            }
        }
        applyTheme()
    }

    private fun authLabel(s: AuthFormState): String = when (s.protocol) {
        SecurityProtocol.SASL_PLAINTEXT, SecurityProtocol.SASL_SSL -> "${s.protocol.name} / ${s.saslMechanism.name}"
        else -> s.protocol.name
    }

    private fun copyAuthInto(src: AuthFormState, dst: AuthFormState) {
        dst.protocol = src.protocol
        dst.saslMechanism = src.saslMechanism
        dst.username = src.username
        dst.password = src.password
        dst.truststorePath = src.truststorePath
        dst.truststorePassword = src.truststorePassword
        dst.keystorePath = src.keystorePath
        dst.keystorePassword = src.keystorePassword
        dst.keyPassword = src.keyPassword
        dst.verifyHostname = src.verifyHostname
        dst.kerberosService = src.kerberosService
        dst.kerberosPrincipal = src.kerberosPrincipal
        dst.keytabPath = src.keytabPath
    }
}
