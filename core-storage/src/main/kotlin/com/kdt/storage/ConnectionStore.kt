package com.kdt.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * CRUD store for saved Kafka connections.
 *
 * Non-secret metadata is persisted as a JSON array at [jsonPath]
 * (default `~/.kafka-desktop/connections.json`). The four secret fields of each
 * connection (passwords) are delegated to the [SecretVault], keyed by connection id.
 *
 * Not thread-safe by design — the UI mutates connections from the JavaFX thread only.
 */
class ConnectionStore(
    private val jsonPath: Path = defaultPath(),
    private val vault: SecretVault = KeyringSecretVault(),
) {
    private val log = LoggerFactory.getLogger(ConnectionStore::class.java)
    private val mapper = ObjectMapper().registerKotlinModule()

    /** Bundle of secrets for one connection, in the same field names as [SecretFields]. */
    data class Secrets(
        val password: String = "",
        val truststorePassword: String = "",
        val keystorePassword: String = "",
        val keyPassword: String = "",
    )

    fun list(): List<SavedConnection> {
        if (!Files.exists(jsonPath)) return emptyList()
        return try {
            val bytes = Files.readAllBytes(jsonPath)
            if (bytes.isEmpty()) emptyList() else mapper.readValue(bytes)
        } catch (t: Throwable) {
            log.warn("Failed to read connections from {} — starting empty", jsonPath, t)
            emptyList()
        }
    }

    /** Load the secrets for a connection from the vault (e.g. before connecting or editing). */
    fun loadSecrets(connectionId: String): Secrets = Secrets(
        password = vault.get(connectionId, SecretFields.PASSWORD).orEmpty(),
        truststorePassword = vault.get(connectionId, SecretFields.TRUSTSTORE_PASSWORD).orEmpty(),
        keystorePassword = vault.get(connectionId, SecretFields.KEYSTORE_PASSWORD).orEmpty(),
        keyPassword = vault.get(connectionId, SecretFields.KEY_PASSWORD).orEmpty(),
    )

    /** Insert or update a connection (matched by id) plus its secrets. */
    fun save(connection: SavedConnection, secrets: Secrets) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == connection.id }
        if (idx >= 0) current[idx] = connection else current.add(connection)
        persist(current)

        vault.put(connection.id, SecretFields.PASSWORD, secrets.password)
        vault.put(connection.id, SecretFields.TRUSTSTORE_PASSWORD, secrets.truststorePassword)
        vault.put(connection.id, SecretFields.KEYSTORE_PASSWORD, secrets.keystorePassword)
        vault.put(connection.id, SecretFields.KEY_PASSWORD, secrets.keyPassword)
    }

    fun delete(connectionId: String) {
        val current = list().filterNot { it.id == connectionId }
        persist(current)
        vault.deleteAll(connectionId)
    }

    private fun persist(connections: List<SavedConnection>) {
        try {
            jsonPath.parent?.let { Files.createDirectories(it) }
            Files.write(jsonPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(connections))
        } catch (t: Throwable) {
            log.error("Failed to persist connections to {}", jsonPath, t)
        }
    }

    companion object {
        fun defaultPath(): Path =
            Paths.get(System.getProperty("user.home"), ".kafka-desktop", "connections.json")
    }
}
