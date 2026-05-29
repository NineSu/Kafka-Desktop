package com.kdt.app

import com.kdt.filter.FilterNode
import com.kdt.auth.AuthStrategy
import com.kdt.auth.PlaintextAuth
import com.kdt.kafka.ConsumedMessage
import com.kdt.kafka.KafkaConnection
import com.kdt.kafka.KafkaMessageConsumer
import com.kdt.kafka.KafkaMessageProducer
import com.kdt.kafka.SendResult
import com.kdt.kafka.StartingPosition
import com.kdt.storage.ConnectionStore
import com.kdt.storage.MessageExporter
import com.kdt.storage.MessageImporter
import com.kdt.storage.MessageRepository
import com.kdt.storage.MessageRow
import com.kdt.ui.common.ConnectionForm
import com.kdt.ui.common.ConnectionManagerDialog
import com.kdt.ui.common.ConnectionVM
import com.kdt.ui.common.ExportDialog
import com.kdt.ui.common.FilterBuilder
import com.kdt.ui.common.MessageDetailPane
import com.kdt.ui.common.ProducerDialog
import com.kdt.ui.common.ProducerRequest
import com.kdt.ui.common.StartChoice
import com.kdt.ui.common.StartFromPicker
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
import javafx.geometry.Insets
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MainView {

    private val log = LoggerFactory.getLogger(MainView::class.java)
    private val tsFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val clusterId = "default"  // multi-cluster comes in a later iteration

    private val repo = MessageRepository()
    private val connectionStore = ConnectionStore()
    private val connectionForm = ConnectionForm()
    private val filterBuilder = FilterBuilder().apply {
        onApply = { node ->
            applyFilter(node)
        }
    }
    private val topicList = ListView<String>()
    private val newTopicBtn = javafx.scene.control.Button("＋ Topic").apply { isDisable = true }
    private val groupsBtn = javafx.scene.control.Button("Groups…").apply { isDisable = true }
    private val refreshTopicsBtn = javafx.scene.control.Button("⟳").apply { isDisable = true }
    private val messageTable = TableView<MessageRowFx>()
    private val tableRows = FXCollections.observableArrayList<MessageRowFx>()
    private val detailPane = MessageDetailPane()
    private val topicHeader = Label("(no topic)").apply { style = "-fx-padding: 6 12 6 12; -fx-font-weight: bold;" }
    private val statsLabel = Label("").apply { style = "-fx-padding: 6 12 6 12;" }
    private val actionLabel = Label("").apply { style = "-fx-padding: 6 12 6 12; -fx-text-fill: #2c3e50;" }
    private val sendButton = javafx.scene.control.Button("Send message…").apply { isDisable = true }
    private val importButton = javafx.scene.control.Button("Import…").apply { isDisable = true }
    private val exportButton = javafx.scene.control.Button("Export…").apply { isDisable = true }
    private val prevPageBtn = javafx.scene.control.Button("◀ Prev").apply { isDisable = true }
    private val nextPageBtn = javafx.scene.control.Button("Next ▶").apply { isDisable = true }
    private val pageLabel = Label("").apply { style = "-fx-padding: 6 8 6 8;" }
    private var pageOffset: Long = 0L
    private val pageSize: Int = 500

    private var connection: KafkaConnection? = null
    private var currentConsumer: KafkaMessageConsumer? = null
    private var currentProducer: KafkaMessageProducer? = null
    @Volatile private var currentTopic: String? = null
    private var currentFilter: FilterNode? = null
    // Bootstrap + auth of the currently-connected cluster; reused by consumer & producer.
    private var currentBootstrap: String = ""
    private var currentAuth: AuthStrategy = PlaintextAuth
    @Volatile private var refreshing: Boolean = false
    private var allTopics: List<String> = emptyList()

    // Consumer thread fills this bounded queue; a dedicated writer thread drains it into
    // DuckDB. The bound provides backpressure (a full queue blocks the consumer's put),
    // and keeping DuckDB writes off the JavaFX thread avoids UI jank under high throughput.
    private val inboxQueue = LinkedBlockingQueue<ConsumedMessage>(QUEUE_CAPACITY)
    @Volatile private var writerRunning = false
    private var writerThread: Thread? = null

    private val refreshTimer = object : AnimationTimer() {
        private var lastQueryMs = 0L
        override fun handle(now: Long) {
            val nowMs = now / 1_000_000
            if (nowMs - lastQueryMs >= 500) {
                refreshTable()
                lastQueryMs = nowMs
            }
        }
    }

    private companion object {
        const val QUEUE_CAPACITY = 10_000
        const val ROW_CAP = 500_000
        const val EVICT_CHECK_INTERVAL = 10_000
        const val WRITE_BATCH = 1_000
    }

    fun show(stage: Stage) {
        buildMessageTable()
        wireConnectionForm()
        wireTopicSelection()
        wireRowSelection()
        wireSendButton()
        wireExportButton()
        wireImportButton()
        wirePagination()
        wireTopicAdmin()
        loadConnections()

        val topicToolbar = HBox(6.0, newTopicBtn, groupsBtn, refreshTopicsBtn).apply {
            padding = Insets(6.0, 6.0, 6.0, 6.0)
        }
        val left = VBox(topicToolbar, topicList).apply { VBox.setVgrow(topicList, Priority.ALWAYS) }
        val headerRow = HBox(8.0, topicHeader, statsLabel, prevPageBtn, pageLabel, nextPageBtn, sendButton, importButton, exportButton, actionLabel)
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

        startWriter()
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

    private fun wireExportButton() {
        exportButton.setOnAction { openExportDialog() }
    }

    private fun wireImportButton() {
        importButton.setOnAction { openImportDialog() }
    }

    private fun openImportDialog() {
        if (currentBootstrap.isBlank()) return
        val chooser = javafx.stage.FileChooser().apply {
            title = "Import messages from file"
            extensionFilters.addAll(
                javafx.stage.FileChooser.ExtensionFilter("Messages (csv/json/jsonl)", "*.csv", "*.json", "*.jsonl"),
                javafx.stage.FileChooser.ExtensionFilter("All files", "*.*"),
            )
        }
        val file = chooser.showOpenDialog(importButton.scene.window) ?: return
        val req = ImportDialog(
            fileName = file.name,
            topics = allTopics,
            inferredFormat = inferFormat(file.name),
            defaultTopic = currentTopic ?: allTopics.firstOrNull(),
        ).showAndWait().orElse(null) ?: return

        actionLabel.text = "Importing…"
        actionLabel.style = "-fx-padding: 6 12 6 12; -fx-text-fill: #2c3e50;"
        val producer = currentProducer ?: KafkaMessageProducer(currentBootstrap, currentAuth).also { currentProducer = it }
        val task = object : Task<ImportOutcome>() {
            override fun call(): ImportOutcome {
                val futures = ArrayList<java.util.concurrent.Future<SendResult>>()
                val skipped = MessageImporter().parse(req.format, file.toPath()) { row ->
                    futures += producer.send(
                        req.topic,
                        row.key?.toByteArray(),
                        row.value?.toByteArray(),
                        row.headers.mapValues { it.value?.toByteArray() },
                        null,
                    )
                }
                var sent = 0L; var failed = 0L
                for (f in futures) {
                    try { f.get(); sent++ } catch (_: Exception) { failed++ }
                }
                return ImportOutcome(sent, skipped, failed)
            }
        }
        task.setOnSucceeded {
            val o = task.value
            actionLabel.text = "✓ sent ${o.sent} · skipped ${o.skipped} · failed ${o.failed} → ${req.topic}"
            actionLabel.style = "-fx-padding: 6 12 6 12; -fx-text-fill: ${if (o.failed == 0L) "#16a085" else "#c0392b"}; -fx-font-weight: bold;"
        }
        task.setOnFailed {
            actionLabel.text = "✗ import failed: ${task.exception?.message ?: task.exception?.javaClass?.simpleName}"
            actionLabel.style = "-fx-padding: 6 12 6 12; -fx-text-fill: #c0392b; -fx-font-weight: bold;"
            log.error("import failed", task.exception)
        }
        Thread(task, "import").apply { isDaemon = true }.start()
    }

    private fun inferFormat(fileName: String): com.kdt.storage.ExportFormat = when {
        fileName.endsWith(".csv", true) -> com.kdt.storage.ExportFormat.CSV
        fileName.endsWith(".json", true) -> com.kdt.storage.ExportFormat.JSON
        else -> com.kdt.storage.ExportFormat.JSONL
    }

    private data class ImportOutcome(val sent: Long, val skipped: Long, val failed: Long)


    private fun openExportDialog() {
        val topic = currentTopic ?: return
        val total = repo.count(clusterId, topic, currentFilter)
        val choice = ExportDialog(total).showAndWait().orElse(null) ?: return
        val format = ConnectionMapping.toExportFormat(choice)

        val chooser = javafx.stage.FileChooser().apply {
            title = "Export $topic"
            initialFileName = "$topic.${format.extension}"
            extensionFilters.add(
                javafx.stage.FileChooser.ExtensionFilter(format.displayName, "*.${format.extension}")
            )
        }
        val file = chooser.showSaveDialog(exportButton.scene.window) ?: return

        actionLabel.text = "Exporting…"
        actionLabel.style = "-fx-padding: 6 12 6 12; -fx-text-fill: #2c3e50;"
        val capturedFilter = currentFilter
        val task = object : Task<Long>() {
            override fun call(): Long =
                MessageExporter().export(format, file.toPath()) { sink ->
                    repo.streamFiltered(clusterId, topic, capturedFilter) { row -> sink(row) }
                }
        }
        task.setOnSucceeded {
            actionLabel.text = "✓ exported ${task.value} rows → ${file.name}"
            actionLabel.style = "-fx-padding: 6 12 6 12; -fx-text-fill: #16a085; -fx-font-weight: bold;"
        }
        task.setOnFailed {
            actionLabel.text = "✗ export failed: ${task.exception?.message ?: task.exception?.javaClass?.simpleName}"
            actionLabel.style = "-fx-padding: 6 12 6 12; -fx-text-fill: #c0392b; -fx-font-weight: bold;"
            log.error("export failed", task.exception)
        }
        Thread(task, "export").apply { isDaemon = true }.start()
    }

    private fun wirePagination() {
        prevPageBtn.setOnAction {
            pageOffset = (pageOffset - pageSize).coerceAtLeast(0L)
            refreshTable()
        }
        nextPageBtn.setOnAction {
            pageOffset += pageSize
            refreshTable()
        }
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
        val bootstrap = currentBootstrap.trim()
        if (bootstrap.isEmpty()) return
        val producer = currentProducer ?: KafkaMessageProducer(bootstrap, currentAuth).also { currentProducer = it }
        val task = object : Task<SendResult>() {
            override fun call(): SendResult =
                producer.send(req.topic, req.key, req.value, req.headers, req.partition).get()
        }
        task.setOnSucceeded {
            val rm = task.value
            actionLabel.text = "✓ sent → ${rm.topic}:${rm.partition}@${rm.offset}"
            actionLabel.style = "-fx-padding: 6 12 6 12; -fx-text-fill: #16a085; -fx-font-weight: bold;"
        }
        task.setOnFailed {
            actionLabel.text = "✗ send failed: ${task.exception.message ?: task.exception.javaClass.simpleName}"
            actionLabel.style = "-fx-padding: 6 12 6 12; -fx-text-fill: #c0392b; -fx-font-weight: bold;"
            log.error("send failed", task.exception)
        }
        Thread(task, "producer-send").apply { isDaemon = true }.start()
    }

    private fun wireConnectionForm() {
        connectionForm.onConnect = { onConnectClicked() }
        connectionForm.onManage = { openConnectionManager() }
    }

    // ---- Topic & consumer-group administration (iter-8) ----

    private fun wireTopicAdmin() {
        newTopicBtn.setOnAction { onCreateTopic() }
        refreshTopicsBtn.setOnAction { refreshTopicList() }
        groupsBtn.setOnAction { openConsumerGroups() }

        // Right-click a topic → Describe / Add partitions / Delete.
        topicList.setCellFactory {
            val cell = javafx.scene.control.ListCell<String>()
            cell.textProperty().bind(cell.itemProperty())
            val menu = javafx.scene.control.ContextMenu()
            val describe = javafx.scene.control.MenuItem("Describe…").apply {
                setOnAction { cell.item?.let { onDescribeTopic(it) } }
            }
            val addParts = javafx.scene.control.MenuItem("Add partitions…").apply {
                setOnAction { cell.item?.let { onAddPartitions(it) } }
            }
            val delete = javafx.scene.control.MenuItem("Delete…").apply {
                setOnAction { cell.item?.let { onDeleteTopic(it) } }
            }
            menu.items.addAll(describe, addParts, delete)
            cell.contextMenuProperty().bind(
                javafx.beans.binding.Bindings.`when`(cell.emptyProperty())
                    .then(null as javafx.scene.control.ContextMenu?).otherwise(menu)
            )
            cell
        }
    }

    private fun topicAdmin(): com.kdt.kafka.TopicAdmin? = connection?.topicAdmin()

    private fun runAdmin(busyMsg: String, work: () -> Unit, okMsg: () -> String, onOk: () -> Unit = {}) {
        actionLabel.text = busyMsg
        actionLabel.style = "-fx-padding: 6 12 6 12; -fx-text-fill: #2c3e50;"
        val task = object : Task<Unit>() { override fun call() = work() }
        task.setOnSucceeded {
            actionLabel.text = okMsg()
            actionLabel.style = "-fx-padding: 6 12 6 12; -fx-text-fill: #16a085; -fx-font-weight: bold;"
            onOk()
        }
        task.setOnFailed {
            val t = task.exception
            actionLabel.text = "✗ ${t?.message ?: t?.javaClass?.simpleName}"
            actionLabel.style = "-fx-padding: 6 12 6 12; -fx-text-fill: #c0392b; -fx-font-weight: bold;"
            log.error("admin op failed", t)
        }
        Thread(task, "topic-admin").apply { isDaemon = true }.start()
    }

    private fun onCreateTopic() {
        val admin = topicAdmin() ?: return
        val req = CreateTopicDialog().showAndWait().orElse(null) ?: return
        runAdmin(
            "Creating ${req.name}…",
            { admin.create(req.name, req.partitions, req.replication, req.configs) },
            { "✓ created ${req.name}" },
            onOk = { refreshTopicList() },
        )
    }

    private fun onDescribeTopic(topic: String) {
        val admin = topicAdmin() ?: return
        var detail: com.kdt.kafka.TopicDetail? = null
        runAdmin(
            "Describing $topic…",
            { detail = admin.describe(topic) },
            { "✓ described $topic" },
            onOk = { detail?.let { TopicDetailDialog(it).showAndWait() } },
        )
    }

    private fun onAddPartitions(topic: String) {
        val admin = topicAdmin() ?: return
        // Need current count first; describe then prompt.
        var current = 0
        runAdmin(
            "Reading $topic…",
            { current = admin.describe(topic).partitions },
            { "ready" },
            onOk = {
                val newTotal = AddPartitionsDialog(topic, current).showAndWait().orElse(null)
                if (newTotal != null) {
                    runAdmin(
                        "Adding partitions to $topic…",
                        { admin.addPartitions(topic, newTotal) },
                        { "✓ $topic now has $newTotal partitions" },
                    )
                }
            },
        )
    }

    private fun onDeleteTopic(topic: String) {
        val admin = topicAdmin() ?: return
        val confirmed = ConfirmNameDialog(
            "Delete topic", "This permanently deletes topic \"$topic\" and all its data.", topic
        ).showAndWait().orElse(false)
        if (!confirmed) return
        runAdmin(
            "Deleting $topic…",
            { admin.delete(topic) },
            { "✓ deleted $topic" },
            onOk = { refreshTopicList() },
        )
    }

    private fun refreshTopicList() {
        val admin = topicAdmin() ?: return
        var topics: List<String> = emptyList()
        runAdmin(
            "Refreshing topics…",
            { topics = admin.list() },
            { "✓ ${topics.size} topic(s)" },
            onOk = {
                allTopics = topics
                topicList.items = FXCollections.observableArrayList(topics)
            },
        )
    }

    private fun openConsumerGroups() {
        val conn = connection ?: return
        val admin = conn.consumerGroupAdmin()
        ConsumerGroupDialog(
            listGroups = { admin.list() },
            describeGroup = { admin.describe(it) },
            resetOffsets = { g, t, spec -> admin.resetOffsets(g, t, spec) },
        ).showAndWait()
    }

    /** Load saved connections from the store into the dropdown. */
    private fun loadConnections() {
        val vms = connectionStore.list().map { ConnectionMapping.toVM(it) }
        connectionForm.setConnections(vms)
        if (vms.isEmpty()) {
            connectionForm.setStatus("No saved connections — click \"Manage…\" to add one.")
        }
    }

    private fun openConnectionManager() {
        ConnectionManagerDialog(
            connections = connectionForm.connections,
            onSave = { vm ->
                val (saved, secrets) = ConnectionMapping.toSaved(vm)
                connectionStore.save(saved, secrets)
            },
            onDelete = { id -> connectionStore.delete(id) },
            onTest = { vm, report -> testConnection(vm, report) },
            onLoadSecrets = { vm -> ConnectionMapping.loadSecretsInto(vm, connectionStore) },
        ).showAndWait()
        // Refresh the dropdown from the store so adds/edits/deletes are reflected
        // (sidesteps ComboBox not always re-rendering live ObservableList mutations).
        loadConnections()
    }

    /** Background connectivity probe for the connection manager's "Test" button. */
    private fun testConnection(vm: ConnectionVM, report: (String, Boolean) -> Unit) {
        val task = object : Task<Int>() {
            override fun call(): Int {
                KafkaConnection(vm.bootstrap, vm.authState.toAuthStrategy()).use { conn ->
                    return conn.listTopics().size
                }
            }
        }
        task.setOnSucceeded { report("✓ OK — ${task.value} topic(s)", true) }
        task.setOnFailed {
            val t = task.exception
            report("✗ ${t?.message ?: t?.javaClass?.simpleName}", false)
        }
        Thread(task, "conn-test").apply { isDaemon = true }.start()
    }

    private fun wireTopicSelection() {
        topicList.selectionModel.selectedItemProperty().addListener { _, _, topic ->
            if (topic != null) promptStartAndConsume(topic)
        }
    }

    private fun onConnectClicked() {
        val vm = connectionForm.selectedConnection() ?: run {
            connectionForm.setStatus("Select a connection first.", error = true); return
        }
        ConnectionMapping.loadSecretsInto(vm, connectionStore)
        val bootstrap = vm.bootstrap.trim()
        val auth = vm.authState.toAuthStrategy()
        currentBootstrap = bootstrap
        currentAuth = auth
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
            importButton.isDisable = false
            newTopicBtn.isDisable = false
            groupsBtn.isDisable = false
            refreshTopicsBtn.isDisable = false
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

    /** Topic clicked → ask where to start, then begin consuming. */
    private fun promptStartAndConsume(topic: String) {
        val choice = StartFromPicker(topic).showAndWait().orElse(null) ?: return
        startConsuming(topic, choice)
    }

    private fun startConsuming(topic: String, choice: StartChoice) {
        val bootstrap = currentBootstrap.trim()
        if (bootstrap.isEmpty()) return

        currentConsumer?.close()
        repo.clear(clusterId, topic)
        inboxQueue.clear()
        tableRows.clear()
        currentTopic = topic
        currentFilter = null
        filterBuilder.reset()
        pageOffset = 0L
        topicHeader.text = "Topic: $topic (${ConnectionMapping.positionLabel(choice)})"
        statsLabel.text = ""
        pageLabel.text = ""
        prevPageBtn.isDisable = true
        nextPageBtn.isDisable = true
        exportButton.isDisable = false

        val consumer = KafkaMessageConsumer(
            bootstrapServers = bootstrap,
            topic = topic,
            position = ConnectionMapping.toStartingPosition(choice),
            onMessage = { msg -> inboxQueue.put(msg) }, // blocks when full → backpressure
            onError = { t ->
                Platform.runLater {
                    topicHeader.text = "Topic: $topic — ERROR: ${t.message ?: t.javaClass.simpleName}"
                }
            },
            auth = currentAuth,
        )
        currentConsumer = consumer
        consumer.start()
    }

    /** Background thread: drains the inbox queue into DuckDB and enforces the row cap. */
    private fun startWriter() {
        writerRunning = true
        val t = Thread({ writerLoop() }, "duckdb-writer").apply { isDaemon = true }
        writerThread = t
        t.start()
    }

    private fun stopWriter() {
        writerRunning = false
        writerThread?.interrupt()
    }

    private fun writerLoop() {
        var insertedSinceCheck = 0
        val batch = ArrayList<ConsumedMessage>(WRITE_BATCH)
        while (writerRunning) {
            try {
                // Block for the first item, then drain a batch without waiting.
                val first = inboxQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                batch.clear()
                batch.add(first)
                inboxQueue.drainTo(batch, WRITE_BATCH - 1)

                val topic = currentTopic ?: continue
                repo.insertBatch(clusterId, topic, batch)

                insertedSinceCheck += batch.size
                if (insertedSinceCheck >= EVICT_CHECK_INTERVAL) {
                    insertedSinceCheck = 0
                    val deleted = repo.evictOldest(clusterId, topic, ROW_CAP)
                    if (deleted > 0) log.debug("LRU evicted {} rows from {}", deleted, topic)
                }
            } catch (_: InterruptedException) {
                break // stop signal
            } catch (e: Exception) {
                log.warn("writer batch failed", e)
            }
        }
    }

    private fun refreshTable() {
        val topic = currentTopic ?: return
        val capturedOffset = pageOffset
        val capturedLimit = pageSize
        val task = object : Task<Pair<List<MessageRow>, Long>>() {
            override fun call(): Pair<List<MessageRow>, Long> {
                val total = repo.count(clusterId, topic, currentFilter)
                val rows = repo.query(clusterId, topic, currentFilter, offsetPage = capturedOffset, limit = capturedLimit)
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
            val pageStart = capturedOffset + 1
            val pageEnd = capturedOffset + rows.size
            statsLabel.text = "$pageStart-$pageEnd / $total total"
            val totalPages = if (total == 0L) 1 else ((total - 1) / capturedLimit) + 1
            val currentPage = (capturedOffset / capturedLimit) + 1
            pageLabel.text = "page $currentPage/$totalPages"
            prevPageBtn.isDisable = capturedOffset == 0L
            nextPageBtn.isDisable = pageEnd >= total
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
        stopWriter()
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
