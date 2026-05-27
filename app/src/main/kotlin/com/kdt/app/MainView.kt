package com.kdt.app

import com.kdt.kafka.ConsumedMessage
import com.kdt.kafka.KafkaConnection
import com.kdt.kafka.KafkaMessageConsumer
import com.kdt.ui.common.ConnectionForm
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.SplitPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainView {

    private val log = LoggerFactory.getLogger(MainView::class.java)
    private val tsFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val connectionForm = ConnectionForm()
    private val topicList = ListView<String>()
    private val messageTable = TableView<MessageRow>()
    private val messageRows = FXCollections.observableArrayList<MessageRow>()
    private val topicHeader = Label("(no topic)").apply { style = "-fx-padding: 6 12 6 12; -fx-font-weight: bold;" }

    private var connection: KafkaConnection? = null
    private var currentConsumer: KafkaMessageConsumer? = null

    fun show(stage: Stage) {
        buildMessageTable()
        wireConnectionForm()
        wireTopicSelection()

        val left = VBox(topicList).apply {
            VBox.setVgrow(topicList, javafx.scene.layout.Priority.ALWAYS)
        }
        val right = BorderPane().apply {
            top = topicHeader
            center = messageTable
        }
        val split = SplitPane(left, right).apply { setDividerPositions(0.25) }

        val root = BorderPane().apply {
            top = connectionForm
            center = split
        }

        stage.title = "Kafka Desktop"
        stage.scene = Scene(root, 1100.0, 700.0)
        stage.setOnCloseRequest { tearDown() }
        stage.show()
    }

    private fun buildMessageTable() {
        val partitionCol = TableColumn<MessageRow, Number>("Partition").apply {
            cellValueFactory = PropertyValueFactory("partition")
            prefWidth = 80.0
        }
        val offsetCol = TableColumn<MessageRow, Number>("Offset").apply {
            cellValueFactory = PropertyValueFactory("offset")
            prefWidth = 100.0
        }
        val tsCol = TableColumn<MessageRow, String>("Timestamp").apply {
            cellValueFactory = PropertyValueFactory("timestamp")
            prefWidth = 140.0
        }
        val keyCol = TableColumn<MessageRow, String>("Key").apply {
            cellValueFactory = PropertyValueFactory("key")
            prefWidth = 180.0
        }
        val valueCol = TableColumn<MessageRow, String>("Value (preview)").apply {
            cellValueFactory = PropertyValueFactory("valuePreview")
            prefWidth = 500.0
        }
        messageTable.columns.setAll(partitionCol, offsetCol, tsCol, keyCol, valueCol)
        messageTable.items = messageRows
        messageTable.placeholder = Label("Select a topic to start consuming.")
    }

    private fun wireConnectionForm() {
        connectionForm.onConnect = { onConnectClicked() }
    }

    private fun wireTopicSelection() {
        topicList.selectionModel.selectedItemProperty().addListener { _, _, topic ->
            if (topic != null) startConsuming(topic)
        }
    }

    private fun onConnectClicked() {
        val bootstrap = connectionForm.bootstrapServers.value.orEmpty().trim()
        if (bootstrap.isEmpty()) {
            connectionForm.setStatus("Enter bootstrap.servers first.", error = true)
            return
        }
        connectionForm.setBusy(true)
        connectionForm.setStatus("Connecting to $bootstrap …")

        val task = object : Task<Set<String>>() {
            override fun call(): Set<String> {
                connection?.close()
                val conn = KafkaConnection(bootstrap)
                connection = conn
                return conn.listTopics()
            }
        }
        task.setOnSucceeded {
            val topics = task.value.sorted()
            topicList.items = FXCollections.observableArrayList(topics)
            connectionForm.setBusy(false)
            connectionForm.setStatus("Connected — ${topics.size} topic(s)")
        }
        task.setOnFailed {
            val t = task.exception
            log.error("Connect failed", t)
            connectionForm.setBusy(false)
            connectionForm.setStatus("Connect failed: ${t?.message ?: t?.javaClass?.simpleName}", error = true)
            topicList.items = FXCollections.observableArrayList()
        }
        Thread(task, "kafka-connect").apply { isDaemon = true }.start()
    }

    private fun startConsuming(topic: String) {
        val bootstrap = connectionForm.bootstrapServers.value?.trim() ?: return
        if (bootstrap.isEmpty()) return

        currentConsumer?.close()
        messageRows.clear()
        topicHeader.text = "Topic: $topic (streaming from beginning)"

        val consumer = KafkaMessageConsumer(
            bootstrapServers = bootstrap,
            topic = topic,
            fromBeginning = true,
            onMessage = { msg -> Platform.runLater { appendRow(msg) } },
            onError = { t ->
                Platform.runLater {
                    topicHeader.text = "Topic: $topic — ERROR: ${t.message ?: t.javaClass.simpleName}"
                }
            },
        )
        currentConsumer = consumer
        consumer.start()
    }

    private fun appendRow(msg: ConsumedMessage) {
        val preview = msg.valueAsString()?.let { v ->
            if (v.length > 200) v.substring(0, 200) + "…" else v
        } ?: "(null)"
        val row = MessageRow(
            partition = msg.partition,
            offset = msg.offset,
            timestamp = tsFormatter.format(Instant.ofEpochMilli(msg.timestampMs)),
            key = msg.keyAsString() ?: "(null)",
            valuePreview = preview,
        )
        // cap at 5000 visible rows to keep this iteration's UI responsive; iteration 3 will replace with DuckDB-backed paging
        if (messageRows.size >= 5000) {
            messageRows.removeAt(0)
        }
        messageRows.add(row)
    }

    private fun tearDown() {
        try {
            currentConsumer?.close()
        } catch (_: Exception) {}
        try {
            connection?.close()
        } catch (_: Exception) {}
    }

    class MessageRow(
        partition: Int,
        offset: Long,
        timestamp: String,
        key: String,
        valuePreview: String,
    ) {
        val partitionProperty = javafx.beans.property.SimpleIntegerProperty(partition)
        val offsetProperty = javafx.beans.property.SimpleLongProperty(offset)
        val timestampProperty = SimpleStringProperty(timestamp)
        val keyProperty = SimpleStringProperty(key)
        val valuePreviewProperty = SimpleStringProperty(valuePreview)

        // JavaFX PropertyValueFactory looks for these JavaBean-style accessors
        fun getPartition(): Int = partitionProperty.get()
        fun getOffset(): Long = offsetProperty.get()
        fun getTimestamp(): String = timestampProperty.get()
        fun getKey(): String = keyProperty.get()
        fun getValuePreview(): String = valuePreviewProperty.get()
    }
}
