package com.kdt.storage

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.io.SerializedString
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class ExportFormat(val extension: String, val displayName: String) {
    CSV("csv", "CSV"),
    JSON("json", "JSON (array)"),
    JSONL("jsonl", "JSON Lines"),
}

/**
 * Streams message rows to a file in CSV / JSON / JSONL. The caller supplies rows via
 * a producer (typically [MessageRepository.streamFiltered]) so nothing is buffered in
 * memory beyond the current row. Columns: partition, offset, timestamp (ISO-8601 UTC),
 * key, value, headers (raw JSON string).
 *
 * Returns the number of rows written.
 */
class MessageExporter {

    private val mapper = ObjectMapper()
    private val tsFmt = DateTimeFormatter.ISO_INSTANT

    init {
        // We manage the Writer lifecycle ourselves; never let a generator close it.
        mapper.factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
    }

    /** [produce] must call the supplied sink once per row to be exported. */
    fun export(format: ExportFormat, file: Path, produce: ((MessageRow) -> Unit) -> Unit): Long {
        Files.newBufferedWriter(file).use { writer ->
            return when (format) {
                ExportFormat.CSV -> exportCsv(writer, produce)
                ExportFormat.JSONL -> exportJsonl(writer, produce)
                ExportFormat.JSON -> exportJsonArray(writer, produce)
            }
        }
    }

    private fun iso(ms: Long): String = tsFmt.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

    private fun exportCsv(writer: Writer, produce: ((MessageRow) -> Unit) -> Unit): Long {
        var n = 0L
        val format = CSVFormat.DEFAULT.builder()
            .setHeader("partition", "offset", "timestamp", "key", "value", "headers")
            .build()
        CSVPrinter(writer, format).use { printer ->
            produce { row ->
                printer.printRecord(
                    row.partition,
                    row.offset,
                    iso(row.timestampMs),
                    row.key ?: "",
                    row.valueStr ?: "",
                    row.headersJson ?: "",
                )
                n++
            }
        }
        return n
    }

    private fun exportJsonl(writer: Writer, produce: ((MessageRow) -> Unit) -> Unit): Long {
        var n = 0L
        val gen = mapper.factory.createGenerator(writer)
        // One object per line: newline is the root-value separator.
        gen.setRootValueSeparator(SerializedString("\n"))
        produce { row ->
            writeRow(gen, row)
            n++
        }
        gen.flush()
        return n
    }

    private fun exportJsonArray(writer: Writer, produce: ((MessageRow) -> Unit) -> Unit): Long {
        var n = 0L
        val gen = mapper.factory.createGenerator(writer)
        gen.useDefaultPrettyPrinter()
        gen.writeStartArray()
        produce { row ->
            writeRow(gen, row)
            n++
        }
        gen.writeEndArray()
        gen.flush()
        return n
    }

    private fun writeRow(gen: JsonGenerator, row: MessageRow) {
        gen.writeStartObject()
        gen.writeNumberField("partition", row.partition)
        gen.writeNumberField("offset", row.offset)
        gen.writeStringField("timestamp", iso(row.timestampMs))
        if (row.key != null) gen.writeStringField("key", row.key) else gen.writeNullField("key")
        if (row.valueStr != null) gen.writeStringField("value", row.valueStr) else gen.writeNullField("value")
        // headers is itself a JSON string — embed as raw so it stays structured.
        if (row.headersJson != null) {
            gen.writeFieldName("headers")
            gen.writeRawValue(row.headersJson)
        } else {
            gen.writeNullField("headers")
        }
        gen.writeEndObject()
    }
}
