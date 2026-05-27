package com.kdt.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Properties

class AuthStrategyTest {

    private fun props(): Properties = Properties()

    @Test
    fun `PlaintextAuth sets security protocol to PLAINTEXT`() {
        val p = props().also { PlaintextAuth.applyTo(it) }
        p.getProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG) shouldBe "PLAINTEXT"
    }

    @Test
    fun `SaslPlainAuth without TLS produces SASL_PLAINTEXT with PLAIN mechanism`() {
        val p = props().also { SaslPlainAuth("alice", "s3cret").applyTo(it) }
        p.getProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG) shouldBe "SASL_PLAINTEXT"
        p.getProperty(SaslConfigs.SASL_MECHANISM) shouldBe "PLAIN"
        p.getProperty(SaslConfigs.SASL_JAAS_CONFIG) shouldContain "username=\"alice\""
        p.getProperty(SaslConfigs.SASL_JAAS_CONFIG) shouldContain "password=\"s3cret\""
    }

    @Test
    fun `SaslPlainAuth with TLS switches to SASL_SSL and applies SSL config`() {
        val ssl = SslConfig(truststorePath = "/etc/kafka/truststore.jks", truststorePassword = "tspwd")
        val p = props().also { SaslPlainAuth("alice", "x", useTls = true, sslConfig = ssl).applyTo(it) }
        p.getProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG) shouldBe "SASL_SSL"
        p.getProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG) shouldBe "/etc/kafka/truststore.jks"
        p.getProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG) shouldBe "tspwd"
    }

    @Test
    fun `SaslScramAuth SCRAM-SHA-512 default`() {
        val p = props().also { SaslScramAuth("bob", "p").applyTo(it) }
        p.getProperty(SaslConfigs.SASL_MECHANISM) shouldBe "SCRAM-SHA-512"
        p.getProperty(SaslConfigs.SASL_JAAS_CONFIG) shouldContain "ScramLoginModule"
    }

    @Test
    fun `SaslScramAuth SCRAM-SHA-256 override`() {
        val p = props().also { SaslScramAuth("bob", "p", mechanism = ScramMechanism.SCRAM_SHA_256).applyTo(it) }
        p.getProperty(SaslConfigs.SASL_MECHANISM) shouldBe "SCRAM-SHA-256"
    }

    @Test
    fun `SslAuth sets SSL protocol`() {
        val p = props().also { SslAuth(SslConfig(truststorePath = "/ts")).applyTo(it) }
        p.getProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG) shouldBe "SSL"
        p.getProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG) shouldBe "/ts"
    }

    @Test
    fun `MtlsAuth requires a keystore`() {
        assertThrows<IllegalArgumentException> {
            MtlsAuth(SslConfig(truststorePath = "/ts"))
        }
    }

    @Test
    fun `MtlsAuth includes keystore properties`() {
        val ssl = SslConfig(
            truststorePath = "/ts", truststorePassword = "tspwd",
            keystorePath = "/ks", keystorePassword = "kspwd", keyPassword = "keypwd",
        )
        val p = props().also { MtlsAuth(ssl).applyTo(it) }
        p.getProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG) shouldBe "SSL"
        p.getProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG) shouldBe "/ks"
        p.getProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG) shouldBe "kspwd"
        p.getProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG) shouldBe "keypwd"
    }

    @Test
    fun `KerberosAuth without keytab uses ticket cache`() {
        val p = props().also { KerberosAuth(servicePrincipal = "kafka", userPrincipal = "user@REALM").applyTo(it) }
        p.getProperty(SaslConfigs.SASL_MECHANISM) shouldBe "GSSAPI"
        p.getProperty(SaslConfigs.SASL_KERBEROS_SERVICE_NAME) shouldBe "kafka"
        p.getProperty(SaslConfigs.SASL_JAAS_CONFIG) shouldContain "useTicketCache=true"
    }

    @Test
    fun `KerberosAuth with keytab embeds keytab path and useKeyTab`() {
        val p = props().also {
            KerberosAuth(
                servicePrincipal = "kafka",
                userPrincipal = "user@REALM",
                keytabPath = "/etc/kafka/user.keytab",
            ).applyTo(it)
        }
        p.getProperty(SaslConfigs.SASL_JAAS_CONFIG) shouldContain "useKeyTab=true"
        p.getProperty(SaslConfigs.SASL_JAAS_CONFIG) shouldContain "/etc/kafka/user.keytab"
    }

    @Test
    fun `SaslPlainAuth escapes embedded double-quote in password`() {
        val p = props().also { SaslPlainAuth("alice", "pa\"ss").applyTo(it) }
        // JAAS-escaped: " inside the value must be backslash-quoted
        p.getProperty(SaslConfigs.SASL_JAAS_CONFIG) shouldContain "password=\"pa\\\"ss\""
    }

    @Test
    fun `SslConfig verifyHostname=false disables endpoint identification`() {
        val p = props().also { SslAuth(SslConfig(verifyHostname = false)).applyTo(it) }
        p.getProperty(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG) shouldBe ""
    }
}
