package com.kdt.app

import com.kdt.kafka.PartitionInfo
import com.kdt.kafka.TopicDetail
import com.kdt.ui.common.theme.applyTheme
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextArea
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/** Read-only view of a topic: summary, per-partition leader/replicas/isr, and configs. */
class TopicDetailDialog(detail: TopicDetail) : Dialog<Unit>() {

    init {
        title = "Topic: ${detail.name}"
        headerText = "${detail.partitions} partition(s) · replication factor ${detail.replicationFactor}"

        val table = TableView(FXCollections.observableArrayList(detail.partitionInfos.map { PartitionRow(it) }))
        val pCol = TableColumn<PartitionRow, Number>("Partition").apply { cellValueFactory = PropertyValueFactory("partition") }
        val lCol = TableColumn<PartitionRow, Number>("Leader").apply { cellValueFactory = PropertyValueFactory("leader") }
        val rCol = TableColumn<PartitionRow, String>("Replicas").apply { cellValueFactory = PropertyValueFactory("replicas"); prefWidth = 120.0 }
        val iCol = TableColumn<PartitionRow, String>("ISR").apply { cellValueFactory = PropertyValueFactory("isr"); prefWidth = 120.0 }
        table.columns.setAll(pCol, lCol, rCol, iCol)
        table.prefHeight = 200.0

        val configsText = if (detail.configs.isEmpty()) "(all defaults)"
        else detail.configs.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        val configsArea = TextArea(configsText).apply { isEditable = false; prefRowCount = 6 }

        val box = VBox(8.0, Label("Partitions:"), table, Label("Non-default configs:"), configsArea).apply {
            padding = Insets(12.0); prefWidth = 560.0
            VBox.setVgrow(table, Priority.ALWAYS)
        }
        dialogPane.content = box
        dialogPane.buttonTypes.add(ButtonType.CLOSE)
        applyTheme()
    }

    class PartitionRow(info: PartitionInfo) {
        val partitionProperty = javafx.beans.property.SimpleIntegerProperty(info.partition)
        val leaderProperty = javafx.beans.property.SimpleIntegerProperty(info.leader)
        val replicasProperty = javafx.beans.property.SimpleStringProperty(info.replicas.joinToString(","))
        val isrProperty = javafx.beans.property.SimpleStringProperty(info.isr.joinToString(","))
        fun getPartition() = partitionProperty.get()
        fun getLeader() = leaderProperty.get()
        fun getReplicas(): String = replicasProperty.get()
        fun getIsr(): String = isrProperty.get()
    }
}

/** Asks for a new (larger) partition count. Returns null on cancel. Validates > [current]. */
class AddPartitionsDialog(topic: String, private val current: Int) : Dialog<Int>() {

    private val spinner: Spinner<Int> = editableIntSpinner(current + 1, 10_000, current + 1)

    init {
        title = "Add partitions"
        headerText = "Topic \"$topic\" currently has $current partition(s).\nKafka can only increase the count."
        val grid = GridPane().apply { hgap = 10.0; vgap = 8.0; padding = Insets(12.0) }
        grid.add(Label("New total:"), 0, 0); grid.add(spinner, 1, 0)
        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) return@setResultConverter null
            commit(spinner)
            spinner.value.takeIf { it > current }
        }
        applyTheme()
    }
}
