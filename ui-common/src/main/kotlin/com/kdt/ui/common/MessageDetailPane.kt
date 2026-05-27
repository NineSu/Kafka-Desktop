package com.kdt.ui.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextArea
import javafx.scene.control.TitledPane
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Detail view for a single Kafka message. Bind [MessageDetail] via [bind].
 * Shows metadata + key + value, each rendered in 3 tabs: Text, Hex, JSON (pretty).
 */
class MessageDetailPane : TitledPane() {

    data class MessageDetail(
        val partition: Int,
        val offset: Long,
        val timestampMs: Long,
        val keyText: String?,
        val keyBytes: ByteArray?,
        val valueText: String?,
        val valueBytes: ByteArray?,
        val valueJson: String?,
        val headersJson: String?,
    )

    private val mapper = ObjectMapper().apply { enable(SerializationFeature.INDENT_OUTPUT) }
    private val tsFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val metaLabel = Label("Select a message to see details.").apply {
        style = "-fx-font-family: monospace; -fx-font-size: 11; -fx-padding: 4 8 4 8;"
    }
    private val keyTabs = makeTabs("Key")
    private val valueTabs = makeTabs("Value")
    private val headersArea = TextArea().apply {
        isEditable = false
        prefRowCount = 4
        style = "-fx-font-family: monospace; -fx-font-size: 11;"
        text = ""
    }

    init {
        text = "Message detail"
        isCollapsible = true
        isExpanded = true

        val keyPane = TitledPane("Key", keyTabs.tabPane).apply { isCollapsible = false }
        val valuePane = TitledPane("Value", valueTabs.tabPane).apply { isCollapsible = false }
        val headersPane = TitledPane("Headers", headersArea).apply { isCollapsible = true; isExpanded = false }

        val split = SplitPane(keyPane, valuePane).apply { setDividerPositions(0.30) }
        SplitPane.setResizableWithParent(keyPane, true)

        val container = VBox(4.0, metaLabel, split, headersPane).apply {
            padding = Insets(6.0)
            VBox.setVgrow(split, Priority.ALWAYS)
        }
        content = container
    }

    fun bind(detail: MessageDetail?) {
        if (detail == null) {
            metaLabel.text = "Select a message to see details."
            keyTabs.set(null, null)
            valueTabs.set(null, null)
            headersArea.text = ""
            return
        }
        metaLabel.text = buildString {
            append("partition=").append(detail.partition)
            append("  offset=").append(detail.offset)
            append("  ts=").append(tsFormatter.format(Instant.ofEpochMilli(detail.timestampMs)))
            append("  keySize=").append(detail.keyBytes?.size ?: 0)
            append("  valueSize=").append(detail.valueBytes?.size ?: 0)
        }
        keyTabs.set(detail.keyText, detail.keyBytes)
        valueTabs.set(detail.valueText, detail.valueBytes)
        headersArea.text = prettyJson(detail.headersJson) ?: ""
    }

    private fun prettyJson(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        return try {
            mapper.writeValueAsString(mapper.readTree(raw))
        } catch (_: Exception) {
            raw
        }
    }

    private inner class FormatTabs(val tabPane: TabPane, val textArea: TextArea, val hexArea: TextArea, val jsonArea: TextArea) {
        fun set(text: String?, bytes: ByteArray?) {
            textArea.text = text ?: ""
            hexArea.text = bytes?.let { toHexDump(it) } ?: ""
            jsonArea.text = prettyJson(text) ?: "(not JSON)"
        }
    }

    private fun makeTabs(label: String): FormatTabs {
        val textArea = makeArea()
        val hexArea = makeArea()
        val jsonArea = makeArea()

        val textTab = Tab("Text", wrapWithCopy(textArea)).apply { isClosable = false }
        val hexTab = Tab("Hex", wrapWithCopy(hexArea)).apply { isClosable = false }
        val jsonTab = Tab("JSON", wrapWithCopy(jsonArea)).apply { isClosable = false }
        val tabPane = TabPane(textTab, hexTab, jsonTab).apply { tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE }
        return FormatTabs(tabPane, textArea, hexArea, jsonArea)
    }

    private fun makeArea() = TextArea().apply {
        isEditable = false
        style = "-fx-font-family: monospace; -fx-font-size: 12;"
    }

    private fun wrapWithCopy(area: TextArea): VBox {
        val copy = Button("Copy").apply {
            setOnAction {
                val content = ClipboardContent().apply { putString(area.text ?: "") }
                Clipboard.getSystemClipboard().setContent(content)
            }
        }
        val row = HBox(copy).apply { alignment = Pos.CENTER_RIGHT; padding = Insets(4.0) }
        return VBox(row, area).apply { VBox.setVgrow(area, Priority.ALWAYS) }
    }

    private fun toHexDump(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 4)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + 16, bytes.size)
            sb.append(String.format("%08x  ", i))
            for (j in i until i + 16) {
                if (j < end) sb.append(String.format("%02x ", bytes[j].toInt() and 0xff))
                else sb.append("   ")
                if (j == i + 7) sb.append(' ')
            }
            sb.append(' ')
            for (j in i until end) {
                val c = bytes[j].toInt() and 0xff
                sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
            }
            sb.append('\n')
            i += 16
        }
        return sb.toString()
    }
}
