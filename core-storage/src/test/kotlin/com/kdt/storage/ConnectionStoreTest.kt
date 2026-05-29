package com.kdt.storage

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ConnectionStoreTest {

    /** In-memory vault so tests never touch the real OS keystore. */
    private class FakeVault : SecretVault {
        val map = mutableMapOf<String, String>()
        override fun put(connectionId: String, field: String, secret: String) {
            val k = "$connectionId/$field"
            if (secret.isBlank()) map.remove(k) else map[k] = secret
        }
        override fun get(connectionId: String, field: String): String? = map["$connectionId/$field"]
        override fun deleteAll(connectionId: String) {
            map.keys.filter { it.startsWith("$connectionId/") }.forEach { map.remove(it) }
        }
    }

    private fun newStore(): Pair<ConnectionStore, FakeVault> {
        val path = Files.createTempFile("connections", ".json")
        Files.delete(path) // store should handle a missing file
        val vault = FakeVault()
        return ConnectionStore(path, vault) to vault
    }

    private fun sampleConn(id: String = "c1") = SavedConnection(
        id = id,
        name = "Prod",
        bootstrapServers = "broker:9092",
        auth = StoredAuth(protocol = "SASL_SSL", saslMechanism = "SCRAM_SHA_512", username = "svc"),
    )

    @Test
    fun `save then list round-trips non-secret fields`() {
        val (store, _) = newStore()
        store.save(sampleConn(), ConnectionStore.Secrets(password = "p@ss"))

        val loaded = store.list()
        loaded shouldHaveSize 1
        loaded[0].name shouldBe "Prod"
        loaded[0].bootstrapServers shouldBe "broker:9092"
        loaded[0].auth.protocol shouldBe "SASL_SSL"
        loaded[0].auth.username shouldBe "svc"
    }

    @Test
    fun `secrets go to the vault and load back`() {
        val (store, vault) = newStore()
        store.save(sampleConn(), ConnectionStore.Secrets(password = "p@ss", keystorePassword = "ks"))

        // secrets are NOT in the persisted SavedConnection
        val secrets = store.loadSecrets("c1")
        secrets.password shouldBe "p@ss"
        secrets.keystorePassword shouldBe "ks"
        vault.map["c1/${SecretFields.PASSWORD}"] shouldBe "p@ss"
    }

    @Test
    fun `save with same id updates in place`() {
        val (store, _) = newStore()
        store.save(sampleConn(), ConnectionStore.Secrets())
        store.save(sampleConn().copy(name = "Renamed"), ConnectionStore.Secrets())

        val loaded = store.list()
        loaded shouldHaveSize 1
        loaded[0].name shouldBe "Renamed"
    }

    @Test
    fun `delete removes connection and its secrets`() {
        val (store, vault) = newStore()
        store.save(sampleConn(), ConnectionStore.Secrets(password = "p@ss"))
        store.delete("c1")

        store.list() shouldHaveSize 0
        vault.map.keys.none { it.startsWith("c1/") } shouldBe true
    }
}
