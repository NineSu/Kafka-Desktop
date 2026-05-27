package com.kdt.kafka

import com.kdt.auth.AuthStrategy
import com.kdt.auth.PlaintextAuth
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import java.util.Properties

class KafkaConnection(
    private val bootstrapServers: String,
    private val auth: AuthStrategy = PlaintextAuth,
) : AutoCloseable {

    val adminClient: AdminClient = AdminClient.create(
        Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000)
            auth.applyTo(this)
        }
    )

    fun listTopics(): Set<String> =
        adminClient.listTopics().names().get()

    override fun close() {
        adminClient.close()
    }
}
