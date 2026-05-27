package com.kdt.app

import com.kdt.filter.FilterNode
import com.kdt.kafka.ConsumedMessage
import com.kdt.kafka.KafkaConnection
import com.kdt.kafka.KafkaMessageConsumer
import com.kdt.kafka.KafkaMessageProducer
import com.kdt.kafka.SendResult
import com.kdt.storage.MessageRepository
import com.kdt.storage.MessageRow
import com.kdt.ui.common.ConnectionForm
import com.kdt.ui.common.FilterBuilder
import com.kdt.ui.common.MessageDetailPane
import com.kdt.ui.common.ProducerDialog
import com.kdt.ui.common.ProducerRequest
import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.SplitPane
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

class MainView {

    private val log = LoggerFactory.getLogger(MainView::class.java)
    private val tsFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val clusterId = "default"  // multi-cluster comes in a later iteration

    private val repo = MessageRepository()
    private val connectionForm = ConnectionForm()
    private val filterBuilder = FilterBuilder().apply {
        onApply = { node ->
            applyFilter(node)
        }
    }
    private val topicList = ListView<String>()
    private val messageTable = TableView<MessageRowFx>()
    private val tableRows = FXCollections.observableArrayList<MessageRowFx>()
    private val detailPane = MessageDetailPane()
    private val topicHeader = Label("(no topic)").apply { style = "-fx-padding: 6 12 6 12; -fx-font-weight: bold;" }
    private val statsLabel = Label("").apply { style = "-fx-padding: 6 12 6 12;" }
    private val sendButton = javafx.scene.control.Button("Send message…").apply { isDisable = true }

    private var connection: KafkaConnection? = null
    private var currentConsumer: KafkaMessageConsumer? = null
    private var currentProducer: KafkaMessageProducer? = null
    private var currentTopic: String? = null
    private var currentFilter: FilterNode? = null
    @Volatile private var refreshing: Boolean = false
    private var allTopics: List<String> = emptyList()

    // Producer thread fills this queue; UI-side timer batches it into DuckDB.
    private val inboxQueue = ConcurrentLinkedQueue<ConsumedMessage>()
    private val refreshTimer = object : AnimationTimer() {
        private var lastDrainMs = 0L
        private var lastQueryMs = 0L
        override fun handle(now: Long) {
            val nowMs = now / 1_000_000
            if (nowMs - lastDrainMs >= 250) {
                drainInboxIntoRepo()
                lastDrainMs = nowMs
            }
            if (nowMs - lastQueryMs >= 500) {
                refreshTable()
                lastQueryMs = nowMs
            }
        }
    }

    fun show(stage: Stage) {
        buildMessageTable()
        wireConnectionForm()
        wireTopicSelection()
        wireRowSelection()
        wireSendButton()

        val left = VBox(topicList).apply { VBox.setVgrow(topicList, Priority.ALWAYS) }
        val headerRow = HBox(8.0, topicHeader, statsLabel, sendButton)
        val tableArea = BorderPane().apply {
            top = headerRow
            center = messageTable
        }
        val rightSplit = SplitPane(tableArea, detailPane).apply {
            orientation = javafx.geometry.Orientation.VERTICAL
            setDividerPositions(0.55)
        }
        val right = BorderPane().apply {
            center = rightSplit
            bottom = filterBuilder
        }
        val split = SplitPane(left, right).apply { setDividerPositions(0.20) }

        val root = BorderPane().apply {
            top = connectionForm
            center = split
        }

        stage.title = "Kafka Desktop"
        stage.scene = Scene(root, 1300.0, 850.0)
        stage.setOnCloseRequest { tearDown() }
        stage.show()

        refreshTimer.start()
    }

    private fun wireRowSelection() {
        messageTable.selectionModel.selectedItemProperty().addListener { _, _, row ->
            val topic = currentTopic
            if (row == null) {
                // Refresh transiently nulls selection — only clear detail when not refreshing
                if (!refreshing) detailPane.bind(null)
                return@addListener
            }
            if (topic == null) { detailPane.bind(null); return@addListener }
            val task = object : Task<MessageRow?>() {
                override fun call(): MessageRow? =
                    repo.findOne(clusterId, topic, row.getPartition(), row.getOffset())
            }
            task.setOnSucceeded {
                val m = task.value
                log.info("detail-fetch p={} o={} -> found={}", row.getPartition(), row.getOffset(), m != null)
                if (m == null) return@setOnSucceeded detailPane.bind(null)
                detailPane.bind(
                    MessageDetailPane.MessageDetail(
                        partition = m.partition,
                        offset = m.offset,
                        timestampMs = m.timestampMs,
                        keyText = m.key,
                        keyBytes = m.keyBytes,
                        valueText = m.valueStr,
                        valueBytes = m.valueBytes,
                        valueJson = m.valueJson,
                        headersJson = m.headersJson,
                    )
                )
            }
            task.setOnFailed {
                log.error("detail-fetch failed", task.exception)
            }
            Thread(task, "detail-fetch").apply { isDaemon = true }.start()
        }
    }

    private fun buildMessageTable() {
        val partitionCol = TableColumn<MessageRowFx, Number>("Partition").apply {
            cellValueFactory = PropertyValueFactory("partition"); prefWidth = 80.0
        }
        val offsetCol = TableColumn<MessageRowFx, Number>("Offset").apply {
            cellValueFactory = PropertyValueFactory("offset"); prefWidth = 100.0
        }
        val tsCol = TableColumn<MessageRowFx, String>("Timestamp").apply {
            cellValueFactory = PropertyValueFactory("timestamp"); prefWidth = 140.0
        }
        val keyCol = TableColumn<MessageRowFx, String>("Key").apply {
            cellValueFactory = PropertyValueFactory("key"); prefWidth = 180.0
        }
        val valueCol = TableColumn<MessageRowFx, String>("Value (preview)").apply {
            cellValueFactory = PropertyValueFactory("valuePreview"); prefWidth = 600.0
        }
        messageTable.columns.setAll(partitionCol, offsetCol, tsCol, keyCol, valueCol)
        messageTable.items = tableRows
        messageTable.placeholder = Label("Select a topic to start consuming.")

        // Right-click "Replay this message"
        messageTable.setRowFactory { _ ->
            val row = TableRow<MessageRowFx>()
            val menu = ContextMenu()
            val replayItem = MenuItem("Replay this message…").apply {
                setOnAction { row.item?.let { openProducerDialog(it) } }
            }
            menu.items.add(replayItem)
            row.contextMenuProperty().bind(
                javafx.beans.binding.Bindings.`when`(row.emptyProperty()).then(null as ContextMenu?).otherwise(menu)
            )
            row
        }
    }

    private fun wireSendButton() {
        sendButton.setOnAction { openProducerDialog(null) }
    }

    private fun openProducerDialog(replayRow: MessageRowFx?) {
        val topic = currentTopic ?: allTopics.firstOrNull()
        // For replay, fetch the full message to get its bytes; otherwise blank
        val dialog = if (replayRow != null && topic != null) {
            val m = repo.findOne(clusterId, topic, replayRow.getPartition(), replayRow.getOffset())
            val headers = m?.headersJson?.let { parseHeadersJson(it) } ?: emptyMap()
            ProducerDialog(allTopics, topic, m?.key, m?.valueStr, headers)
        } else {
            ProducerDialog(allTopics, topic)
        }
        val result = dialog.showAndWait()
        if (!result.isPresent) return
        val req = result.get()
        sendOne(req)
    }

    private fun parseHeadersJson(json: String): Map<String, String?> = try {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val node = mapper.readTree(json)
        node.fields().asSequence().associate { (k, v) -> k to if (v.isNull) null else v.asText() }
    } catch (_: Exception) { emptyMap() }

    private fun sendOne(req: ProducerRequest) {
        val bootstrap = connectionForm.bootstrapServers.value.orEmpty().trim()
        if (bootstrap.isEmpty()) return
        val producer = currentProducer ?: KafkaMessageProducer(bootstrap, connectionForm.authStrategy.value).also { currentProducer = it }
        val task = object : Task<SendResult>() {
            override fun call(): SendResult =
                producer.send(req.topic, req.key, req.value, req.headers, req.partition).get()
        }
        task.setOnSucceeded {
            val rm = task.value
            statsLabel.text = "sent → ${rm.topic}:${rm.partition}@${rm.offset}"
        }
        task.setOnFailed {
            statsLabel.text = "send failed: ${task.exception.message ?: task.exception.javaClass.simpleName}"
            log.error("send failed", task.exception)
        }
        Thread(task, "producer-send").apply { isDaemon = true }.start()
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
            connectionForm.setStatus("Enter bootstrap.servers first.", error = true); return
        }
        val auth = connectionForm.authStrategy.value
        connectionForm.setBusy(true)
        connectionForm.setStatus("Connecting to $bootstrap …")

        val task = object : Task<Set<String>>() {
            override fun call(): Set<String> {
                connection?.close()
                val conn = KafkaConnection(bootstrap, auth)
                connection = conn
                return conn.listTopics()
            }
        }
        task.setOnSucceeded {
            val topics = task.value.sorted()
            allTopics = topics
            topicList.items = FXCollections.observableArrayList(topics)
            connectionForm.setBusy(false)
            connectionForm.setStatus("Connected — ${topics.size} topic(s)")
            sendButton.isDisable = false
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
        val bootstrap = connectionForm.bootstrapServers.value?.trim().orEmpty()
        if (bootstrap.isEmpty()) return

        currentConsumer?.close()
        repo.clear(clusterId, topic)
        inboxQueue.clear()
        tableRows.clear()
        currentTopic = topic
        topicHeader.text = "Topic: $topic (from beginning)"
        statsLabel.text = ""

        val consumer = KafkaMessageConsumer(
            bootstrapServers = bootstrap,
            topic = topic,
            fromBeginning = true,
            onMessage = { msg -> inboxQueue.offer(msg) },
            onError = { t ->
                Platform.runLater {
                    topicHeader.text = "Topic: $topic — ERROR: ${t.message ?: t.javaClass.simpleName}"
                }
            },
            auth = connectionForm.authStrategy.value,
        )
        currentConsumer = consumer
        consumer.start()
    }

    private fun drainInboxIntoRepo() {
        val topic = currentTopic ?: return
        if (inboxQueue.isEmpty()) return
        val batch = ArrayList<ConsumedMessage>(inboxQueue.size.coerceAtMost(1_000))
        var drained = 0
        while (drained < 1_000) {
            val m = inboxQueue.poll() ?: break
            batch.add(m); drained++
        }
        if (batch.isNotEmpty()) {
            try {
                repo.insertBatch(clusterId, topic, batch)
            } catch (e: Exception) {
                log.warn("insertBatch failed", e)
            }
        }
    }

    private fun refreshTable() {
        val topic = currentTopic ?: return
        val task = object : Task<Pair<List<MessageRow>, Long>>() {
            override fun call(): Pair<List<MessageRow>, Long> {
                val total = repo.count(clusterId, topic, currentFilter)
                val rows = repo.query(clusterId, topic, currentFilter, offsetPage = 0L, limit = 1_000)
                return rows to total
            }
        }
        task.setOnSucceeded {
            val (rows, total) = task.value
            // Preserve selection across the refresh
            val selected = messageTable.selectionModel.selectedItem
            val selKey = selected?.let { it.getPartition() to it.getOffset() }
            refreshing = true
            try {
                tableRows.setAll(rows.map { it.toFx() })
                if (selKey != null) {
                    val match = tableRows.firstOrNull { it.getPartition() == selKey.first && it.getOffset() == selKey.second }
                    if (match != null) messageTable.selectionModel.select(match)
                }
            } finally {
                refreshing = false
            }
            statsLabel.text = "${rows.size} shown / $total total"
        }
        task.setOnFailed {
            log.warn("Refresh failed", task.exception)
        }
        Thread(task, "ui-refresh").apply { isDaemon = true }.start()
    }

    private fun MessageRow.toFx(): MessageRowFx {
        val preview = valueStr?.let { v -> if (v.length > 200) v.substring(0, 200) + "…" else v } ?: "(null)"
        return MessageRowFx(
            partition = partition,
            offset = offset,
            timestamp = tsFormatter.format(Instant.ofEpochMilli(timestampMs)),
            key = key ?: "(null)",
            valuePreview = preview,
        )
    }

    private fun tearDown() {
        refreshTimer.stop()
        try { currentConsumer?.close() } catch (_: Exception) {}
        try { currentProducer?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        try { repo.close() } catch (_: Exception) {}
    }

    class MessageRowFx(
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

        fun getPartition(): Int = partitionProperty.get()
        fun getOffset(): Long = offsetProperty.get()
        fun getTimestamp(): String = timestampProperty.get()
        fun getKey(): String = keyProperty.get()
        fun getValuePreview(): String = valuePreviewProperty.get()
    }

    /** Hook for the visual filter builder. Re-queries the active page immediately. */
    fun applyFilter(filter: FilterNode?) {
        currentFilter = filter
        refreshTable()
    }
}
