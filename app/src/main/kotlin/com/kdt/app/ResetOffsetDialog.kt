package com.kdt.app

import com.kdt.kafka.OffsetResetSpec
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
 * Picks how to reset a consumer group's offsets for one topic. Returns null on cancel.
 * Mirrors the consume StartFromPicker semantics (earliest/latest/timestamp/offset).
 */
class ResetOffsetDialog(groupId: String, topic: String) : Dialog<OffsetResetSpec>() {

    private val group = ToggleGroup()
    private val earliestRadio = RadioButton("Earliest").apply { toggleGroup = group; isSelected = true }
    private val latestRadio = RadioButton("Latest").apply { toggleGroup = group }
    private val timestampRadio = RadioButton("At timestamp:").apply { toggleGroup = group }
    private val offsetRadio = RadioButton("At offset (all partitions):").apply { toggleGroup = group }

    private val datePicker = DatePicker(LocalDate.now(ZoneId.systemDefault()))
    private val timeField = TextField(LocalTime.now(ZoneId.systemDefault()).withSecond(0).withNano(0).toString())
        .apply { promptText = "HH:mm" }
    private val offsetSpinner: Spinner<Int> = editableIntSpinner(0, Int.MAX_VALUE, 0)

    init {
        title = "Reset offsets"
        headerText = "Group \"$groupId\" → topic \"$topic\""
        val grid = GridPane().apply { hgap = 10.0; vgap = 8.0; padding = Insets(12.0) }
        var row = 0
        grid.add(earliestRadio, 0, row++, 2, 1)
        grid.add(latestRadio, 0, row++, 2, 1)
        grid.add(timestampRadio, 0, row); grid.add(datePicker, 1, row++)
        grid.add(Label("Time (HH:mm):"), 0, row); grid.add(timeField, 1, row++)
        grid.add(offsetRadio, 0, row); grid.add(offsetSpinner, 1, row)

        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn -> if (btn != ButtonType.OK) null else resolve() }
    }

    private fun resolve(): OffsetResetSpec = when (group.selectedToggle) {
        latestRadio -> OffsetResetSpec.Latest
        timestampRadio -> OffsetResetSpec.AtTimestamp(epochMs())
        offsetRadio -> { commit(offsetSpinner); OffsetResetSpec.AtOffset(offsetSpinner.value.toLong()) }
        else -> OffsetResetSpec.Earliest
    }

    private fun epochMs(): Long {
        val date = datePicker.value ?: LocalDate.now(ZoneId.systemDefault())
        val time = runCatching { LocalTime.parse(timeField.text.trim()) }.getOrDefault(LocalTime.MIDNIGHT)
        return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
