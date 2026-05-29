package com.kdt.ui.common

import com.kdt.filter.Condition
import com.kdt.filter.Field
import com.kdt.filter.FilterNode
import com.kdt.filter.Group
import com.kdt.filter.Logic
import com.kdt.filter.Operator
import com.kdt.filter.SqlCompiler
import com.kdt.filter.Value
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ChoiceBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.TitledPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * UI-local snapshot of the flat builder's form: the combine-logic plus one entry per rule.
 * The app maps this to/from core-storage's SavedFilter (keeps ui-common free of core-storage).
 */
data class FilterSnapshot(val logic: String, val rows: List<FilterRowData>)

data class FilterRowData(
    val fieldKind: String,
    val aux: String,
    val operator: String,
    val value: String,
)

/**
 * Visual AND/OR filter builder. Produces a [FilterNode] AST via [currentFilter].
 * This iteration is flat (single-level group). Nested groups arrive in a later iteration.
 */
class FilterBuilder : TitledPane() {

    private val compiler = SqlCompiler()
    private val logicBox = ChoiceBox<Logic>().apply {
        items.addAll(Logic.AND, Logic.OR)
        value = Logic.AND
    }
    private val rulesBox = VBox(6.0)
    private val sqlPreview = TextArea().apply {
        isEditable = false
        prefRowCount = 3
        style = "-fx-font-family: monospace; -fx-font-size: 11;"
    }

    var onApply: (FilterNode?) -> Unit = {}

    /** Names of saved filters visible for the current topic; the app populates this. */
    val savedFilters: ObservableList<String> = FXCollections.observableArrayList()
    var onSaveRequested: () -> Unit = {}
    var onLoadFilter: (String) -> Unit = {}
    var onDeleteFilter: (String) -> Unit = {}
    private val savedBox = ComboBox(savedFilters).apply { promptText = "saved filters…" }

    init {
        text = "Filter"
        isCollapsible = true
        isExpanded = true

        val addBtn = Button("+ Add rule").apply { setOnAction { addRule() } }
        val applyBtn = Button("Apply").apply {
            style = "-fx-base: #3498db; -fx-text-fill: white;"
            setOnAction { onApply(buildFilter()) }
        }
        val clearBtn = Button("Clear").apply { setOnAction {
            rulesBox.children.clear()
            rebuildPreview()
            onApply(null)
        } }

        val header = HBox(8.0, Label("Combine with:"), logicBox, addBtn, applyBtn, clearBtn).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(4.0, 8.0, 8.0, 8.0)
        }
        logicBox.valueProperty().addListener { _, _, _ -> rebuildPreview() }

        val saveBtn = Button("Save…").apply { setOnAction { onSaveRequested() } }
        val loadBtn = Button("Load").apply { setOnAction { savedBox.value?.let { onLoadFilter(it) } } }
        val deleteBtn = Button("Delete").apply { setOnAction { savedBox.value?.let { onDeleteFilter(it) } } }
        val savedRow = HBox(8.0, Label("Saved:"), savedBox, loadBtn, saveBtn, deleteBtn).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(0.0, 8.0, 0.0, 8.0)
        }

        val container = VBox(4.0, savedRow, header, rulesBox, Label("SQL preview:"), sqlPreview).apply {
            padding = Insets(8.0)
        }
        content = container

        addRule() // start with one row so users see the shape
    }

    val currentFilter: FilterNode? get() = buildFilter()

    /**
     * Reset the builder to a single empty rule (no active filter). Does NOT fire
     * [onApply] — the caller is responsible for clearing any cached filter state.
     * Used when switching topics so a fresh view starts unfiltered.
     */
    fun reset() {
        rulesBox.children.clear()
        logicBox.value = Logic.AND
        addRule()
    }

    private fun addRule(initial: FilterRowData? = null) {
        val row = RuleRow(onRemove = { row ->
            rulesBox.children.remove(row)
            rebuildPreview()
        }, onChange = ::rebuildPreview, initial = initial)
        rulesBox.children.add(row)
        rebuildPreview()
    }

    /** Capture the current form as a [FilterSnapshot] (for saving). */
    fun snapshot(): FilterSnapshot = FilterSnapshot(
        logic = logicBox.value.name,
        rows = rulesBox.children.filterIsInstance<RuleRow>().map { it.toData() },
    )

    /** Repopulate the form from a saved [FilterSnapshot] and apply it immediately. */
    fun restore(snapshot: FilterSnapshot) {
        rulesBox.children.clear()
        logicBox.value = runCatching { Logic.valueOf(snapshot.logic) }.getOrDefault(Logic.AND)
        if (snapshot.rows.isEmpty()) addRule() else snapshot.rows.forEach { addRule(it) }
        onApply(buildFilter())
    }

    private fun buildFilter(): FilterNode? {
        val conditions = rulesBox.children
            .filterIsInstance<RuleRow>()
            .mapNotNull { it.toCondition() }
        if (conditions.isEmpty()) return null
        if (conditions.size == 1) return conditions.first()
        return Group(logicBox.value, conditions)
    }

    private fun rebuildPreview() {
        val node = buildFilter()
        if (node == null) {
            sqlPreview.text = "(no filter)"
            return
        }
        val compiled = compiler.compile(node)
        sqlPreview.text = "WHERE ${compiled.whereClause}\nparams: ${compiled.params}"
    }
}

/** One row in the flat filter builder. */
private class RuleRow(
    private val onRemove: (RuleRow) -> Unit,
    private val onChange: () -> Unit,
    initial: FilterRowData? = null,
) : HBox(6.0) {

    enum class FieldKind { KEY, VALUE_RAW, JSON_PATH, HEADER, PARTITION, OFFSET, TIMESTAMP }

    private val fieldKind = ChoiceBox<FieldKind>().apply {
        items.addAll(*FieldKind.values())
        value = initial?.fieldKind?.let { runCatching { FieldKind.valueOf(it) }.getOrNull() } ?: FieldKind.KEY
    }
    private val operator = ChoiceBox<Operator>().apply {
        items.addAll(*Operator.values())
        value = initial?.operator?.let { runCatching { Operator.valueOf(it) }.getOrNull() } ?: Operator.LIKE
    }
    private val auxField = TextField(initial?.aux.orEmpty()).apply {
        promptText = "path or header name"
        prefWidth = 130.0
    }
    private val valueField = TextField(initial?.value.orEmpty()).apply {
        promptText = "value"
        HBox.setHgrow(this, Priority.ALWAYS)
    }
    private val removeBtn = Button("✕").apply { setOnAction { onRemove(this@RuleRow) } }

    init {
        alignment = Pos.CENTER_LEFT
        children.addAll(fieldKind, operator, auxField, valueField, removeBtn)

        fieldKind.valueProperty().addListener { _, _, _ -> syncAuxVisibility(); onChange() }
        operator.valueProperty().addListener { _, _, _ -> onChange() }
        auxField.textProperty().addListener { _, _, _ -> onChange() }
        valueField.textProperty().addListener { _, _, _ -> onChange() }
        syncAuxVisibility()
    }

    fun toData(): FilterRowData = FilterRowData(
        fieldKind = fieldKind.value.name,
        aux = auxField.text.orEmpty(),
        operator = operator.value.name,
        value = valueField.text.orEmpty(),
    )

    private fun syncAuxVisibility() {
        val showsAux = fieldKind.value == FieldKind.JSON_PATH || fieldKind.value == FieldKind.HEADER
        auxField.isDisable = !showsAux
        auxField.promptText = when (fieldKind.value) {
            FieldKind.JSON_PATH -> "\$.path.to.field"
            FieldKind.HEADER -> "header name"
            else -> "n/a"
        }
    }

    fun toCondition(): Condition? {
        val op = operator.value ?: return null
        val field: Field = when (fieldKind.value) {
            FieldKind.KEY -> Field.Key
            FieldKind.VALUE_RAW -> Field.ValueRaw
            FieldKind.JSON_PATH -> {
                val p = auxField.text?.trim().orEmpty()
                if (p.isEmpty()) return null
                Field.JsonPath(p)
            }
            FieldKind.HEADER -> {
                val name = auxField.text?.trim().orEmpty()
                if (name.isEmpty()) return null
                Field.Header(name)
            }
            FieldKind.PARTITION -> Field.Partition
            FieldKind.OFFSET -> Field.Offset
            FieldKind.TIMESTAMP -> Field.Timestamp
            null -> return null
        }
        val raw = valueField.text?.trim().orEmpty()
        val value: Value = when (op) {
            Operator.IS_NULL, Operator.IS_NOT_NULL -> Value.Null
            Operator.BETWEEN -> {
                val parts = raw.split(",", limit = 2).map { it.trim() }
                if (parts.size != 2) return null
                val lo = parts[0].toDoubleOrNull() ?: return null
                val hi = parts[1].toDoubleOrNull() ?: return null
                Value.Range(lo, hi)
            }
            Operator.IN -> Value.List_(raw.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            else -> {
                if (raw.isEmpty()) return null
                // numeric-ish fields → Num; otherwise String
                if (field == Field.Partition || field == Field.Offset || field == Field.Timestamp) {
                    raw.toDoubleOrNull()?.let { Value.Num(it) } ?: Value.Str(raw)
                } else {
                    Value.Str(raw)
                }
            }
        }
        return Condition(field, op, value)
    }
}
