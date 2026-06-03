package com.kdt.app

import com.kdt.kafka.ConsumerGroupInfo
import com.kdt.kafka.OffsetResetSpec
import com.kdt.kafka.PartitionLag
import com.kdt.ui.common.theme.applyTheme
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.ChoiceDialog
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * Browse consumer groups and their per-partition lag; reset offsets for a topic.
 *
 * All broker access is delegated to the app via callbacks ([listGroups]/[describeGroup]/
 * [resetOffsets]); the dialog wraps each in a background [Task] so the broker calls never
 * run on the FX thread. Reset is guarded by [ConsumerGroupInfo.isResetSafe].
 */
class ConsumerGroupDialog(
    private val listGroups: () -> List<String>,
    private val describeGroup: (String) -> ConsumerGroupInfo,
    private val resetOffsets: (String, String, OffsetResetSpec) -> Unit,
) : Dialog<Unit>() {

    private val groupList = ListView<String>()
    private val stateLabel = Label("Select a group.")
    private val lagRows = FXCollections.observableArrayList<LagRow>()
    private val lagTable = TableView(lagRows)
    private val resetButton = Button("Reset offsets…").apply { isDisable = true }
    private val status = Label("")
    private var current: ConsumerGroupInfo? = null

    init {
        title = "Consumer groups"
        buildLagTable()

        groupList.selectionModel.selectedItemProperty().addListener { _, _, g -> if (g != null) loadGroup(g) }
        resetButton.setOnAction { onReset() }

        val right = VBox(8.0, stateLabel, lagTable, HBox(8.0, resetButton, status)).apply {
            padding = Insets(0.0, 0.0, 0.0, 10.0)
            VBox.setVgrow(lagTable, Priority.ALWAYS)
        }
        val pane = BorderPane().apply {
            left = VBox(4.0, Label("Groups:"), groupList).also { VBox.setVgrow(groupList, Priority.ALWAYS) }
            center = right
            padding = Insets(12.0)
            prefWidth = 760.0; prefHeight = 460.0
        }
        dialogPane.content = pane
        dialogPane.buttonTypes.add(ButtonType.CLOSE)

        loadGroups()
        applyTheme()
    }

    private fun buildLagTable() {
        val t = TableColumn<LagRow, String>("Topic").apply { cellValueFactory = PropertyValueFactory("topic"); prefWidth = 180.0 }
        val p = TableColumn<LagRow, Number>("Partition").apply { cellValueFactory = PropertyValueFactory("partition") }
        val c = TableColumn<LagRow, Number>("Committed").apply { cellValueFactory = PropertyValueFactory("committed"); prefWidth = 110.0 }
        val e = TableColumn<LagRow, Number>("Log end").apply { cellValueFactory = PropertyValueFactory("logEnd"); prefWidth = 110.0 }
        val l = TableColumn<LagRow, Number>("Lag").apply { cellValueFactory = PropertyValueFactory("lag"); prefWidth = 110.0 }
        lagTable.columns.setAll(t, p, c, e, l)
        lagTable.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
        lagTable.placeholder = Label("No committed offsets.")
    }

    private fun runTask(work: () -> Unit, onError: (Throwable) -> Unit, onOk: () -> Unit) {
        val task = object : Task<Unit>() { override fun call() = work() }
        task.setOnSucceeded { onOk() }
        task.setOnFailed { onError(task.exception) }
        Thread(task, "cg-admin").apply { isDaemon = true }.start()
    }

    private fun loadGroups() {
        status.text = "Loading groups…"
        var result: List<String> = emptyList()
        runTask(
            work = { result = listGroups() },
            onError = { t -> status.text = "✗ ${t.message ?: t.javaClass.simpleName}" },
            onOk = {
                groupList.items = FXCollections.observableArrayList(result)
                status.text = "${result.size} group(s)"
            },
        )
    }

    private fun loadGroup(groupId: String) {
        stateLabel.text = "Loading $groupId…"
        resetButton.isDisable = true
        var info: ConsumerGroupInfo? = null
        runTask(
            work = { info = describeGroup(groupId) },
            onError = { t -> stateLabel.text = "✗ ${t.message ?: t.javaClass.simpleName}" },
            onOk = {
                val i = info ?: return@runTask
                current = i
                stateLabel.text = "state=${i.state}  ·  members=${i.members}  ·  ${if (i.isResetSafe) "reset OK" else "active — stop consumers to reset"}"
                lagRows.setAll(i.lags.map { LagRow(it) })
                resetButton.isDisable = false
            },
        )
    }

    private fun onReset() {
        val info = current ?: return
        if (!info.isResetSafe) {
            Alert(Alert.AlertType.WARNING,
                "Group \"${info.groupId}\" is ${info.state} with ${info.members} active member(s).\n" +
                    "Kafka rejects offset resets while consumers are active — stop them first."
            ).apply { headerText = "Cannot reset an active group" }.apply { applyTheme() }.showAndWait()
            return
        }
        val topics = info.lags.map { it.topic }.distinct()
        if (topics.isEmpty()) {
            Alert(Alert.AlertType.INFORMATION, "Group has no committed offsets — no topic to reset.").apply { applyTheme() }.showAndWait()
            return
        }
        val topic = if (topics.size == 1) topics.first()
        else ChoiceDialog(topics.first(), topics).apply {
            title = "Pick topic"; headerText = "Reset which topic for ${info.groupId}?"
            applyTheme()
        }.showAndWait().orElse(null) ?: return

        val spec = ResetOffsetDialog(info.groupId, topic).showAndWait().orElse(null) ?: return
        if (!ConfirmNameDialog("Confirm reset", "Reset offsets of \"${info.groupId}\" on \"$topic\".", info.groupId)
                .showAndWait().orElse(false)) return

        status.text = "Resetting…"
        runTask(
            work = { resetOffsets(info.groupId, topic, spec) },
            onError = { t -> status.text = "✗ reset failed: ${t.message ?: t.javaClass.simpleName}" },
            onOk = { status.text = "✓ reset done"; loadGroup(info.groupId) },
        )
    }

    class LagRow(lag: PartitionLag) {
        val topicProperty = javafx.beans.property.SimpleStringProperty(lag.topic)
        val partitionProperty = javafx.beans.property.SimpleIntegerProperty(lag.partition)
        val committedProperty = javafx.beans.property.SimpleLongProperty(lag.committed)
        val logEndProperty = javafx.beans.property.SimpleLongProperty(lag.logEnd)
        val lagProperty = javafx.beans.property.SimpleLongProperty(lag.lag)
        fun getTopic(): String = topicProperty.get()
        fun getPartition() = partitionProperty.get()
        fun getCommitted() = committedProperty.get()
        fun getLogEnd() = logEndProperty.get()
        fun getLag() = lagProperty.get()
    }
}
