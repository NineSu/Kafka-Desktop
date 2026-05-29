package com.kdt.ui.common

import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.DatePicker
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.Spinner
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * UI-local result of [StartFromPicker]. The app maps this to core-kafka's
 * `StartingPosition` (keeps ui-common free of the core-kafka/kafka-clients dependency).
 */
sealed interface StartChoice {
    data object Beginning : StartChoice
    data object End : StartChoice
    data class LastN(val n: Long) : StartChoice
    data class FromTimestamp(val epochMs: Long) : StartChoice
    data class FromOffset(val offset: Long) : StartChoice
}

/**
 * Asks where to start consuming a topic. Returns a [StartChoice], or empty if
 * cancelled. Defaults to "From beginning".
 */
class StartFromPicker(topic: String) : Dialog<StartChoice>() {

    private val group = ToggleGroup()
    private val beginningRadio = RadioButton("From beginning (earliest)").apply { toggleGroup = group; isSelected = true }
    private val endRadio = RadioButton("From end (only new messages)").apply { toggleGroup = group }
    private val lastNRadio = RadioButton("Last N per partition:").apply { toggleGroup = group }
    private val timestampRadio = RadioButton("From timestamp:").apply { toggleGroup = group }
    private val offsetRadio = RadioButton("From offset:").apply { toggleGroup = group }

    private val lastNSpinner = Spinner<Int>(1, 1_000_000, 1000, 100).apply {
        isEditable = true
        // Commit the typed text on focus loss (Spinner doesn't by default).
        focusedProperty().addListener { _, _, focused -> if (!focused) commitSpinner(this) }
    }
    private val datePicker = DatePicker(LocalDate.now(ZoneId.systemDefault()))
    private val timeField = TextField(LocalTime.now(ZoneId.systemDefault()).withSecond(0).withNano(0).toString())
        .apply { promptText = "HH:mm" }
    private val offsetField = TextField("0").apply { promptText = "absolute offset" }

    init {
        title = "Consume \"$topic\""
        headerText = "Where should consumption start?"

        val grid = GridPane().apply { hgap = 10.0; vgap = 8.0; padding = Insets(12.0) }
        var row = 0
        grid.add(beginningRadio, 0, row++, 2, 1)
        grid.add(endRadio, 0, row++, 2, 1)
        grid.add(lastNRadio, 0, row); grid.add(lastNSpinner, 1, row++)
        grid.add(timestampRadio, 0, row); grid.add(datePicker, 1, row++)
        grid.add(Label("Time (HH:mm):"), 0, row); grid.add(timeField, 1, row++)
        grid.add(offsetRadio, 0, row); grid.add(offsetField, 1, row++)

        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else resolve()
        }
    }

    private fun resolve(): StartChoice = when (group.selectedToggle) {
        endRadio -> StartChoice.End
        lastNRadio -> { commitSpinner(lastNSpinner); StartChoice.LastN(lastNSpinner.value.toLong()) }
        timestampRadio -> StartChoice.FromTimestamp(epochMs())
        offsetRadio -> StartChoice.FromOffset(offsetField.text.trim().toLongOrNull() ?: 0L)
        else -> StartChoice.Beginning
    }

    /** Push the spinner's edited text into its value (editable spinners don't auto-commit). */
    private fun commitSpinner(spinner: Spinner<Int>) {
        val text = spinner.editor.text
        val parsed = text.trim().toIntOrNull() ?: return
        val clamped = parsed.coerceIn(1, 1_000_000)
        spinner.valueFactory.value = clamped
    }

    private fun epochMs(): Long {
        val date = datePicker.value ?: LocalDate.now(ZoneId.systemDefault())
        val time = runCatching { LocalTime.parse(timeField.text.trim()) }.getOrDefault(LocalTime.MIDNIGHT)
        return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
