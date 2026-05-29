package com.kdt.kafka

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory

/** Plain DTO for a consumer group's state + per-partition lag. */
data class ConsumerGroupInfo(
    val groupId: String,
    val state: String,
    val members: Int,
    val lags: List<PartitionLag>,
) {
    /** Reset is only accepted by the broker when no consumers are active. */
    val isResetSafe: Boolean get() = state == "EMPTY" || state == "DEAD"
}

data class PartitionLag(
    val topic: String,
    val partition: Int,
    val committed: Long, // -1 when the group has no committed offset for this partition
    val logEnd: Long,
    val lag: Long,
) {
    companion object {
        /** Lag never goes negative; a missing commit (committed < 0) counts the whole log as lag. */
        fun computeLag(committed: Long, logEnd: Long): Long =
            if (committed < 0) logEnd else (logEnd - committed).coerceAtLeast(0L)
    }
}

/** Where to move a consumer group's offsets. */
sealed interface OffsetResetSpec {
    data object Earliest : OffsetResetSpec
    data object Latest : OffsetResetSpec
    data class AtTimestamp(val epochMs: Long) : OffsetResetSpec
    data class AtOffset(val offset: Long) : OffsetResetSpec
}

/**
 * Consumer-group administration over an [AdminClient]. Synchronous; run off the UI thread.
 */
class ConsumerGroupAdmin(private val admin: AdminClient) {

    private val log = LoggerFactory.getLogger(ConsumerGroupAdmin::class.java)

    fun list(): List<String> =
        admin.listConsumerGroups().all().get().map { it.groupId() }.sorted()

    fun describe(groupId: String): ConsumerGroupInfo {
        val desc = admin.describeConsumerGroups(listOf(groupId)).all().get()[groupId]
            ?: error("Group '$groupId' not found")

        val committed: Map<TopicPartition, OffsetAndMetadata> =
            admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get()

        val lags = if (committed.isEmpty()) {
            emptyList()
        } else {
            val endOffsets = admin.listOffsets(committed.keys.associateWith { OffsetSpec.latest() })
                .all().get()
            committed.entries
                .map { (tp, off) ->
                    val end = endOffsets[tp]?.offset() ?: 0L
                    val c = off.offset()
                    PartitionLag(
                        topic = tp.topic(),
                        partition = tp.partition(),
                        committed = c,
                        logEnd = end,
                        lag = PartitionLag.computeLag(c, end),
                    )
                }
                .sortedWith(compareBy({ it.topic }, { it.partition }))
        }

        return ConsumerGroupInfo(
            groupId = groupId,
            state = desc.state().toString(),
            members = desc.members().size,
            lags = lags,
        )
    }

    /**
     * Reset the group's offsets for every partition of [topic] per [spec], via
     * alterConsumerGroupOffsets. Broker rejects this if the group has active members —
     * the caller should pre-check [ConsumerGroupInfo.isResetSafe].
     */
    fun resetOffsets(groupId: String, topic: String, spec: OffsetResetSpec) {
        val partitions = admin.describeTopics(listOf(topic)).allTopicNames().get()[topic]
            ?.partitions()?.map { TopicPartition(topic, it.partition()) }
            ?: error("Topic '$topic' not found")

        val targets: Map<TopicPartition, OffsetAndMetadata> = resolveTargets(partitions, spec)
        admin.alterConsumerGroupOffsets(groupId, targets).all().get()
        log.info("Reset group {} on {} ({} partitions) to {}", groupId, topic, partitions.size, spec)
    }

    private fun resolveTargets(
        partitions: List<TopicPartition>,
        spec: OffsetResetSpec,
    ): Map<TopicPartition, OffsetAndMetadata> = when (spec) {
        is OffsetResetSpec.AtOffset ->
            partitions.associateWith { OffsetAndMetadata(spec.offset.coerceAtLeast(0L)) }

        OffsetResetSpec.Earliest -> offsetsBySpec(partitions, OffsetSpec.earliest())
        OffsetResetSpec.Latest -> offsetsBySpec(partitions, OffsetSpec.latest())
        is OffsetResetSpec.AtTimestamp -> {
            val byTs = admin.listOffsets(partitions.associateWith { OffsetSpec.forTimestamp(spec.epochMs) })
                .all().get()
            // No record at/after the timestamp → offset() is -1; fall back to latest for those.
            val needLatest = partitions.filter { (byTs[it]?.offset() ?: -1L) < 0L }
            val latest = if (needLatest.isEmpty()) emptyMap()
            else admin.listOffsets(needLatest.associateWith { OffsetSpec.latest() }).all().get()
            partitions.associateWith { tp ->
                val o = byTs[tp]?.offset()?.takeIf { it >= 0L } ?: latest[tp]?.offset() ?: 0L
                OffsetAndMetadata(o)
            }
        }
    }

    private fun offsetsBySpec(partitions: List<TopicPartition>, offsetSpec: OffsetSpec): Map<TopicPartition, OffsetAndMetadata> {
        val res = admin.listOffsets(partitions.associateWith { offsetSpec }).all().get()
        return partitions.associateWith { OffsetAndMetadata(res[it]?.offset() ?: 0L) }
    }
}
