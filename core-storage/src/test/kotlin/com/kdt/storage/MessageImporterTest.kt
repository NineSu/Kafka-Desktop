package com.kdt.storage

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files

class MessageImporterTest {

    private val exporter = MessageExporter()
    private val importer = MessageImporter()

    // value of row 2 deliberately has comma, quote, newline → exercises CSV escaping round-trip
    private val rows = listOf(
        MessageRow(0, 10, 0L, "k1", """{"status":"OK"}""", """{"status":"OK"}""", """{"h":"v"}"""),
        MessageRow(1, 11, 60_000L, "k2", "a,b\"c\nd", null, null),
        MessageRow(2, 12, 0L, null, "plain", null, """{"src":"x","n":null}"""),
    )

    private fun export(format: ExportFormat) = Files.createTempFile("imp", ".${format.extension}").also { file ->
        exporter.export(format, file) { sink -> rows.forEach(sink) }
    }

    private fun importAll(format: ExportFormat) = buildList {
        val skipped = importer.parse(format, export(format)) { add(it) }
        skipped shouldBe 0L
    }

    @Test
    fun `csv round-trips key value headers`() {
        val out = importAll(ExportFormat.CSV)
        out shouldHaveSize 3
        out[0].key shouldBe "k1"
        out[0].value shouldBe """{"status":"OK"}"""
        out[0].headers shouldBe mapOf("h" to "v")
        // messy value survives the CSV round trip verbatim
        out[1].value shouldBe "a,b\"c\nd"
        out[1].headers shouldBe emptyMap()
    }

    @Test
    fun `jsonl round-trips, including null key and null header value`() {
        val out = importAll(ExportFormat.JSONL)
        out shouldHaveSize 3
        out[1].key shouldBe "k2"
        out[2].key shouldBe null
        out[2].value shouldBe "plain"
        out[2].headers shouldBe mapOf("src" to "x", "n" to null)
    }

    @Test
    fun `json array round-trips`() {
        val out = importAll(ExportFormat.JSON)
        out shouldHaveSize 3
        out[0].headers shouldBe mapOf("h" to "v")
        out[2].value shouldBe "plain"
    }

    @Test
    fun `malformed jsonl lines are skipped and counted`() {
        val file = Files.createTempFile("bad", ".jsonl")
        Files.write(file, listOf(
            """{"key":"a","value":"1"}""",
            "not json at all",
            """{"key":"b","value":"2"}""",
            "{ broken",
        ))
        val parsed = mutableListOf<ImportedMessage>()
        val skipped = importer.parse(ExportFormat.JSONL, file) { parsed.add(it) }
        parsed shouldHaveSize 2
        skipped shouldBe 2L
        parsed.map { it.key } shouldBe listOf("a", "b")
    }
}
