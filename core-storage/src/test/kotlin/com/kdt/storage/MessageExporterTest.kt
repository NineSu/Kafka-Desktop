package com.kdt.storage

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files

class MessageExporterTest {

    private val exporter = MessageExporter()
    private val mapper = ObjectMapper()

    private val rows = listOf(
        MessageRow(0, 10, 0L, "k1", """{"status":"OK"}""", """{"status":"OK"}""", """{"h":"v"}"""),
        // value contains comma, quote, newline → exercises CSV escaping
        MessageRow(1, 11, 60_000L, "k2", "a,b\"c\nd", null, null),
    )

    private fun produce(): ((MessageRow) -> Unit) -> Unit = { sink -> rows.forEach(sink) }

    @Test
    fun `csv has header plus one row each and survives reparse`() {
        val file = Files.createTempFile("export", ".csv")
        val n = exporter.export(ExportFormat.CSV, file, produce())
        n shouldBe 2L

        val text = Files.readString(file)
        text shouldContain "partition,offset,timestamp,key,value,headers"
        // the messy value must be quoted so it stays one field
        text shouldContain "\"a,b\"\"c\nd\""
    }

    @Test
    fun `jsonl emits one json object per line`() {
        val file = Files.createTempFile("export", ".jsonl")
        val n = exporter.export(ExportFormat.JSONL, file, produce())
        n shouldBe 2L

        val lines = Files.readAllLines(file).filter { it.isNotBlank() }
        lines.size shouldBe 2
        val first = mapper.readTree(lines[0])
        first["partition"].asInt() shouldBe 0
        first["key"].asText() shouldBe "k1"
        // headers embedded as structured JSON, not a string
        first["headers"]["h"].asText() shouldBe "v"
    }

    @Test
    fun `json produces a parseable array`() {
        val file = Files.createTempFile("export", ".json")
        val n = exporter.export(ExportFormat.JSON, file, produce())
        n shouldBe 2L

        val tree = mapper.readTree(Files.readString(file))
        tree.isArray shouldBe true
        tree.size() shouldBe 2
        tree[1]["key"].asText() shouldBe "k2"
        tree[1]["value"].asText() shouldBe "a,b\"c\nd"
        tree[1]["headers"].isNull shouldBe true
    }
}
