package com.kdt.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A named, reusable filter. [topic] null means global (offered for every topic);
 * otherwise it is only offered when that topic is selected. [conditions] mirror the
 * flat visual builder's rows so they round-trip without serializing the rich AST.
 */
data class SavedFilter(
    val name: String,
    val topic: String?,
    val logic: String,
    val conditions: List<SavedCondition>,
)

data class SavedCondition(
    val fieldKind: String,
    val aux: String = "",
    val operator: String,
    val value: String = "",
)

/**
 * CRUD store for saved filters, persisted as a JSON array at [jsonPath]
 * (default `~/.kafka-desktop/filters.json`). Keyed by [SavedFilter.name] (save upserts).
 * Not thread-safe — mutated from the JavaFX thread only.
 */
class FilterStore(
    private val jsonPath: Path = defaultPath(),
) {
    private val log = LoggerFactory.getLogger(FilterStore::class.java)
    private val mapper = ObjectMapper().registerKotlinModule()

    fun list(): List<SavedFilter> {
        if (!Files.exists(jsonPath)) return emptyList()
        return try {
            val bytes = Files.readAllBytes(jsonPath)
            if (bytes.isEmpty()) emptyList() else mapper.readValue(bytes)
        } catch (t: Throwable) {
            log.warn("Failed to read filters from {} — starting empty", jsonPath, t)
            emptyList()
        }
    }

    /** Filters visible for [topic]: globals (topic == null) plus this topic's own. */
    fun listFor(topic: String?): List<SavedFilter> =
        list().filter { it.topic == null || it.topic == topic }

    fun save(filter: SavedFilter) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.name == filter.name }
        if (idx >= 0) current[idx] = filter else current.add(filter)
        persist(current)
    }

    fun delete(name: String) {
        persist(list().filterNot { it.name == name })
    }

    private fun persist(filters: List<SavedFilter>) {
        try {
            jsonPath.parent?.let { Files.createDirectories(it) }
            Files.write(jsonPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(filters))
        } catch (t: Throwable) {
            log.error("Failed to persist filters to {}", jsonPath, t)
        }
    }

    companion object {
        fun defaultPath(): Path =
            Paths.get(System.getProperty("user.home"), ".kafka-desktop", "filters.json")
    }
}
