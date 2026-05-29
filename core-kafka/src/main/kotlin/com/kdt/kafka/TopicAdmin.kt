package com.kdt.kafka

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.Config
import org.apache.kafka.clients.admin.ConfigEntry
import org.apache.kafka.clients.admin.NewPartitions
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.ConfigResource
import org.slf4j.LoggerFactory

/** Plain DTO describing a topic — no kafka-clients types leak out. */
data class TopicDetail(
    val name: String,
    val partitions: Int,
    val replicationFactor: Int,
    val configs: Map<String, String>,
    val partitionInfos: List<PartitionInfo>,
)

data class PartitionInfo(
    val partition: Int,
    val leader: Int,
    val replicas: List<Int>,
    val isr: List<Int>,
)

/**
 * Topic administration over an [AdminClient]. All calls are synchronous (block on the
 * KafkaFuture) and meant to run off the UI thread. Methods throw on failure; callers
 * surface the message.
 */
class TopicAdmin(private val admin: AdminClient) {

    private val log = LoggerFactory.getLogger(TopicAdmin::class.java)

    fun list(): List<String> =
        admin.listTopics().names().get().sorted()

    fun describe(topic: String): TopicDetail {
        val desc = admin.describeTopics(listOf(topic)).allTopicNames().get()[topic]
            ?: error("Topic '$topic' not found")
        val partitionInfos = desc.partitions().map { p ->
            PartitionInfo(
                partition = p.partition(),
                leader = p.leader()?.id() ?: -1,
                replicas = p.replicas().map { it.id() },
                isr = p.isr().map { it.id() },
            )
        }
        val replication = partitionInfos.firstOrNull()?.replicas?.size ?: 0

        val resource = ConfigResource(ConfigResource.Type.TOPIC, topic)
        val config: Config = admin.describeConfigs(listOf(resource)).all().get()[resource] ?: Config(emptyList())
        // Keep only operator-set (non-default) entries to avoid drowning the user in defaults.
        val configs = config.entries()
            .filterNot { it.isDefault }
            .filter { it.source() != ConfigEntry.ConfigSource.DEFAULT_CONFIG }
            .associate { it.name() to (it.value() ?: "") }
            .toSortedMap()

        return TopicDetail(
            name = topic,
            partitions = partitionInfos.size,
            replicationFactor = replication,
            configs = configs,
            partitionInfos = partitionInfos,
        )
    }

    fun create(name: String, partitions: Int, replication: Short, configs: Map<String, String> = emptyMap()) {
        val topic = NewTopic(name, partitions, replication)
        if (configs.isNotEmpty()) topic.configs(configs)
        admin.createTopics(listOf(topic)).all().get()
        log.info("Created topic {} (partitions={}, rf={})", name, partitions, replication)
    }

    /** Increase a topic's partition count to [newTotal]. Kafka only allows growth. */
    fun addPartitions(topic: String, newTotal: Int) {
        admin.createPartitions(mapOf(topic to NewPartitions.increaseTo(newTotal))).all().get()
        log.info("Topic {} partitions increased to {}", topic, newTotal)
    }

    fun delete(topic: String) {
        admin.deleteTopics(listOf(topic)).all().get()
        log.info("Deleted topic {}", topic)
    }
}
