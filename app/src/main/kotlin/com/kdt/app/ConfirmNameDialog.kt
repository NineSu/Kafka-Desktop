package com.kdt.app

import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.VBox

/**
 * Type-to-confirm guard for destructive actions. The OK button stays disabled until
 * the user types [expected] verbatim. Returns true only on confirmed OK.
 */
class ConfirmNameDialog(
    title: String,
    message: String,
    private val expected: String,
) : Dialog<Boolean>() {

    private val field = TextField().apply { promptText = expected }

    init {
        this.title = title
        val box = VBox(
            8.0,
            Label(message),
            Label("Type \"$expected\" to confirm:"),
            field,
        ).apply { padding = Insets(12.0); prefWidth = 420.0 }
        dialogPane.content = box
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val okButton = dialogPane.lookupButton(ButtonType.OK)
        okButton.isDisable = true
        field.textProperty().addListener { _, _, v -> okButton.isDisable = v.trim() != expected }

        setResultConverter { btn -> btn == ButtonType.OK && field.text.trim() == expected }
    }
}
