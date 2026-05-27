package com.kdt.auth

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import java.util.Properties

/**
 * Strategy that applies authentication properties to a Kafka client config.
 * Each protocol has its own implementation; UI selects one and passes it into
 * KafkaConnection / KafkaMessageConsumer.
 */
sealed interface AuthStrategy {
    fun applyTo(props: Properties)
}

/** No-auth plaintext. Default for local Kafka. */
data object PlaintextAuth : AuthStrategy {
    override fun applyTo(props: Properties) {
        props.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT")
    }
}

/** SASL/PLAIN over plaintext or TLS. */
data class SaslPlainAuth(
    val username: String,
    val password: String,
    val useTls: Boolean = false,
    val sslConfig: SslConfig? = null,
) : AuthStrategy {
    override fun applyTo(props: Properties) {
        props.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, if (useTls) "SASL_SSL" else "SASL_PLAINTEXT")
        props.setProperty(SaslConfigs.SASL_MECHANISM, "PLAIN")
        props.setProperty(
            SaslConfigs.SASL_JAAS_CONFIG,
            "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${escape(username)}\" password=\"${escape(password)}\";"
        )
        if (useTls) sslConfig?.applyTo(props)
    }
}

/** SASL/SCRAM-SHA-256 or SCRAM-SHA-512. */
data class SaslScramAuth(
    val username: String,
    val password: String,
    val mechanism: ScramMechanism = ScramMechanism.SCRAM_SHA_512,
    val useTls: Boolean = false,
    val sslConfig: SslConfig? = null,
) : AuthStrategy {
    override fun applyTo(props: Properties) {
        props.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, if (useTls) "SASL_SSL" else "SASL_PLAINTEXT")
        props.setProperty(SaslConfigs.SASL_MECHANISM, mechanism.kafkaName)
        props.setProperty(
            SaslConfigs.SASL_JAAS_CONFIG,
            "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"${escape(username)}\" password=\"${escape(password)}\";"
        )
        if (useTls) sslConfig?.applyTo(props)
    }
}

enum class ScramMechanism(val kafkaName: String) {
    SCRAM_SHA_256("SCRAM-SHA-256"),
    SCRAM_SHA_512("SCRAM-SHA-512"),
}

/** Server-side TLS only (no client cert). */
data class SslAuth(val sslConfig: SslConfig) : AuthStrategy {
    override fun applyTo(props: Properties) {
        props.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
        sslConfig.applyTo(props)
    }
}

/** Mutual TLS — server verifies client cert from keystore. */
data class MtlsAuth(val sslConfig: SslConfig) : AuthStrategy {
    init {
        require(sslConfig.keystorePath != null) { "mTLS requires a keystore" }
    }
    override fun applyTo(props: Properties) {
        props.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
        sslConfig.applyTo(props)
    }
}

/** SASL/GSSAPI (Kerberos). The user provides a keytab + principal or relies on cached TGT. */
data class KerberosAuth(
    val servicePrincipal: String, // e.g. "kafka"
    val userPrincipal: String,
    val keytabPath: String? = null,
    val useTls: Boolean = false,
    val sslConfig: SslConfig? = null,
) : AuthStrategy {
    override fun applyTo(props: Properties) {
        props.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, if (useTls) "SASL_SSL" else "SASL_PLAINTEXT")
        props.setProperty(SaslConfigs.SASL_MECHANISM, "GSSAPI")
        props.setProperty(SaslConfigs.SASL_KERBEROS_SERVICE_NAME, servicePrincipal)
        val jaas = if (keytabPath != null) {
            "com.sun.security.auth.module.Krb5LoginModule required useKeyTab=true storeKey=true keyTab=\"${escape(keytabPath)}\" principal=\"${escape(userPrincipal)}\";"
        } else {
            "com.sun.security.auth.module.Krb5LoginModule required useTicketCache=true principal=\"${escape(userPrincipal)}\";"
        }
        props.setProperty(SaslConfigs.SASL_JAAS_CONFIG, jaas)
        if (useTls) sslConfig?.applyTo(props)
    }
}

/**
 * Common TLS configuration. Used inside SSL/MTLS strategies or combined with SASL_SSL.
 * Paths may be null to fall back to the JVM defaults.
 */
data class SslConfig(
    val truststorePath: String? = null,
    val truststorePassword: String? = null,
    val keystorePath: String? = null,
    val keystorePassword: String? = null,
    val keyPassword: String? = null,
    /** Set to false to disable hostname verification. Default: enabled. */
    val verifyHostname: Boolean = true,
) {
    fun applyTo(props: Properties) {
        truststorePath?.let { props.setProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, it) }
        truststorePassword?.let { props.setProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, it) }
        keystorePath?.let { props.setProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, it) }
        keystorePassword?.let { props.setProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, it) }
        keyPassword?.let { props.setProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG, it) }
        if (!verifyHostname) {
            props.setProperty(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "")
        }
    }
}

// JAAS uses backslash + double-quote escaping; chars that need escaping inside the quoted JAAS values
private fun escape(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")
