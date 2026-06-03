package com.kdt.app

import com.kdt.ui.common.theme.applyTheme
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane

/** What [CreateTopicDialog] returns. */
data class CreateTopicRequest(
    val name: String,
    val partitions: Int,
    val replication: Short,
    val configs: Map<String, String>,
)

/**
 * Create-topic form: name + partition count + replication factor + optional configs
 * (one `key=value` per line). Returns null on cancel.
 */
class CreateTopicDialog : Dialog<CreateTopicRequest>() {

    private val nameField = TextField().apply { promptText = "my-topic" }
    private val partitionsSpinner = editableIntSpinner(1, 10_000, 1)
    private val replicationSpinner = editableIntSpinner(1, 100, 1)
    private val configsArea = TextArea().apply {
        promptText = "retention.ms=604800000\ncleanup.policy=delete"
        prefRowCount = 4
    }

    init {
        title = "Create topic"
        val grid = GridPane().apply { hgap = 10.0; vgap = 8.0; padding = Insets(12.0) }
        var row = 0
        grid.add(Label("Name:"), 0, row); grid.add(nameField, 1, row++)
        grid.add(Label("Partitions:"), 0, row); grid.add(partitionsSpinner, 1, row++)
        grid.add(Label("Replication factor:"), 0, row); grid.add(replicationSpinner, 1, row++)
        grid.add(Label("Configs (key=value/line):"), 0, row); grid.add(configsArea, 1, row)

        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        setResultConverter { btn ->
            if (btn != ButtonType.OK) return@setResultConverter null
            val name = nameField.text.trim()
            if (name.isEmpty()) return@setResultConverter null
            commit(partitionsSpinner); commit(replicationSpinner)
            CreateTopicRequest(
                name = name,
                partitions = partitionsSpinner.value,
                replication = replicationSpinner.value.toShort(),
                configs = parseConfigs(configsArea.text),
            )
        }
        applyTheme()
    }

    private fun parseConfigs(text: String): Map<String, String> =
        text.lines()
            .mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || '=' !in t) null
                else t.substringBefore('=').trim() to t.substringAfter('=').trim()
            }
            .filter { it.first.isNotEmpty() }
            .toMap()
}

/** Editable integer spinner that commits typed text on focus loss. */
internal fun editableIntSpinner(min: Int, max: Int, initial: Int): Spinner<Int> =
    Spinner<Int>(min, max, initial).apply {
        isEditable = true
        focusedProperty().addListener { _, _, focused -> if (!focused) commit(this) }
    }

internal fun commit(spinner: Spinner<Int>) {
    val parsed = spinner.editor.text.trim().toIntOrNull() ?: return
    val factory = spinner.valueFactory as? javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory
    val lo = factory?.min ?: Int.MIN_VALUE
    val hi = factory?.max ?: Int.MAX_VALUE
    spinner.valueFactory.value = parsed.coerceIn(lo, hi)
}
