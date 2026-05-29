package com.kdt.storage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.csv.CSVFormat
import org.slf4j.LoggerFactory
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path

/** One message parsed from an import file. Only the sendable fields are kept. */
data class ImportedMessage(
    val key: String?,
    val value: String?,
    val headers: Map<String, String?>,
)

/**
 * Reads message rows from a CSV / JSON / JSONL file produced by [MessageExporter]
 * (or hand-authored in the same shape) and yields each as an [ImportedMessage].
 *
 * Only key / value / headers are used; partition / offset / timestamp are ignored
 * (the broker reassigns them on send). Malformed rows are skipped; [parse] returns
 * the count skipped so the caller can report it.
 */
class MessageImporter {

    private val log = LoggerFactory.getLogger(MessageImporter::class.java)
    private val mapper = ObjectMapper()

    fun parse(format: ExportFormat, file: Path, onRow: (ImportedMessage) -> Unit): Long =
        Files.newBufferedReader(file).use { reader ->
            when (format) {
                ExportFormat.CSV -> parseCsv(reader, onRow)
                ExportFormat.JSONL -> parseJsonl(reader, onRow)
                ExportFormat.JSON -> parseJsonArray(reader, onRow)
            }
        }

    private fun parseCsv(reader: Reader, onRow: (ImportedMessage) -> Unit): Long {
        var skipped = 0L
        val format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
        format.parse(reader).use { parser ->
            for (record in parser) {
                try {
                    val key = record.takeIf { it.isMapped("key") }?.get("key")?.ifEmpty { null }
                    val value = record.takeIf { it.isMapped("value") }?.get("value")?.ifEmpty { null }
                    val headers = record.takeIf { it.isMapped("headers") }?.get("headers")
                    onRow(ImportedMessage(key, value, parseHeaders(headers)))
                } catch (e: Exception) {
                    skipped++
                    log.debug("skipping malformed CSV record", e)
                }
            }
        }
        return skipped
    }

    private fun parseJsonl(reader: Reader, onRow: (ImportedMessage) -> Unit): Long {
        var skipped = 0L
        reader.buffered().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                onRow(toMessage(mapper.readTree(line)))
            } catch (e: Exception) {
                skipped++
                log.debug("skipping malformed JSONL line", e)
            }
        }
        return skipped
    }

    private fun parseJsonArray(reader: Reader, onRow: (ImportedMessage) -> Unit): Long {
        var skipped = 0L
        val root = mapper.readTree(reader)
        if (root == null || !root.isArray) error("Expected a JSON array at the top level")
        for (node in root) {
            try {
                if (!node.isObject) { skipped++; continue }
                onRow(toMessage(node))
            } catch (e: Exception) {
                skipped++
                log.debug("skipping malformed JSON element", e)
            }
        }
        return skipped
    }

    private fun toMessage(node: JsonNode): ImportedMessage {
        val key = node.get("key")?.takeIf { !it.isNull }?.asText()
        val value = node.get("value")?.takeIf { !it.isNull }?.asText()
        val headersNode = node.get("headers")
        val headers = if (headersNode != null && headersNode.isObject) {
            headersNode.fields().asSequence()
                .associate { (k, v) -> k to if (v.isNull) null else v.asText() }
        } else emptyMap()
        return ImportedMessage(key, value, headers)
    }

    /** Headers cell is a JSON object string; parse leniently, empty on failure. */
    private fun parseHeaders(json: String?): Map<String, String?> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val node = mapper.readTree(json)
            if (!node.isObject) emptyMap()
            else node.fields().asSequence().associate { (k, v) -> k to if (v.isNull) null else v.asText() }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
