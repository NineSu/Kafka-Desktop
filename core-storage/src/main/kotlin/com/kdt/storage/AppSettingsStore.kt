package com.kdt.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Loads/saves [AppSettings] as a JSON object at [jsonPath]
 * (default `~/.kafka-desktop/appearance.json`). Resilient: a missing or
 * corrupt file yields defaults rather than crashing.
 *
 * Not thread-safe by design — mutated from the JavaFX thread only.
 */
class AppSettingsStore(
    private val jsonPath: Path = defaultPath(),
) {
    private val log = LoggerFactory.getLogger(AppSettingsStore::class.java)
    private val mapper = ObjectMapper().registerKotlinModule()

    fun load(): AppSettings {
        if (!Files.exists(jsonPath)) return AppSettings()
        return try {
            val bytes = Files.readAllBytes(jsonPath)
            if (bytes.isEmpty()) AppSettings() else mapper.readValue(bytes)
        } catch (t: Throwable) {
            log.warn("Failed to read appearance settings from {} — using defaults", jsonPath, t)
            AppSettings()
        }
    }

    fun save(settings: AppSettings) {
        try {
            jsonPath.parent?.let { Files.createDirectories(it) }
            Files.write(jsonPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings))
        } catch (t: Throwable) {
            log.error("Failed to persist appearance settings to {}", jsonPath, t)
        }
    }

    companion object {
        fun defaultPath(): Path =
            Paths.get(System.getProperty("user.home"), ".kafka-desktop", "appearance.json")
    }
}
