package com.kdt.storage

/**
 * A persisted Kafka cluster connection. Secret-free by design: passwords and
 * keystore/truststore passwords live in the [SecretVault], not here, so this
 * object can be safely written to a plaintext JSON file.
 */
data class SavedConnection(
    val id: String,
    val name: String,
    val bootstrapServers: String,
    val auth: StoredAuth,
)

/**
 * Serializable, secret-free representation of an auth configuration.
 * [protocol] and [saslMechanism] are stored as the enum `name` of the UI-layer
 * enums (kept as String so core-storage need not depend on ui-common).
 */
data class StoredAuth(
    val protocol: String = "PLAINTEXT",
    val saslMechanism: String = "PLAIN",
    val username: String = "",
    val truststorePath: String = "",
    val keystorePath: String = "",
    val keytabPath: String = "",
    val kerberosService: String = "kafka",
    val kerberosPrincipal: String = "",
    val verifyHostname: Boolean = true,
)

/** Field names used as vault keys for the four secret values of a connection. */
object SecretFields {
    const val PASSWORD = "password"
    const val TRUSTSTORE_PASSWORD = "truststorePassword"
    const val KEYSTORE_PASSWORD = "keystorePassword"
    const val KEY_PASSWORD = "keyPassword"

    val ALL = listOf(PASSWORD, TRUSTSTORE_PASSWORD, KEYSTORE_PASSWORD, KEY_PASSWORD)
}

/**
 * Stores per-connection secrets out of band from the plaintext connection JSON.
 * Implementations back this with the OS keystore (Keychain / Credential Manager /
 * libsecret). All methods must be resilient: a vault failure must never crash the
 * app — secrets simply won't persist.
 */
interface SecretVault {
    /** Store (or overwrite) a secret. A blank value deletes the entry. */
    fun put(connectionId: String, field: String, secret: String)

    /** Returns the stored secret, or null if absent/unavailable. */
    fun get(connectionId: String, field: String): String?

    /** Remove every secret field belonging to this connection. */
    fun deleteAll(connectionId: String)
}
