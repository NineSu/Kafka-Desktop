package com.kdt.kafka

import com.kdt.auth.AuthStrategy
import com.kdt.auth.PlaintextAuth
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ConsumedMessage(
    val partition: Int,
    val offset: Long,
    val timestampMs: Long,
    val key: ByteArray?,
    val value: ByteArray?,
    val headers: Map<String, ByteArray?>,
) {
    fun keyAsString(): String? = key?.toString(Charsets.UTF_8)
    fun valueAsString(): String? = value?.toString(Charsets.UTF_8)
}

/**
 * Polls a single topic on a dedicated thread and pushes each message to [onMessage].
 * Closing the consumer stops the thread and releases broker resources.
 *
 * Uses manual partition assignment + seek (not subscribe) so the [position] can be
 * honored precisely — earliest/latest/last-N-per-partition/by-timestamp/by-offset.
 *
 * Backpressure note: this delivers every message synchronously to [onMessage]; the
 * downstream UI batches via a bounded queue + DuckDB sink.
 */
class KafkaMessageConsumer(
    bootstrapServers: String,
    private val topic: String,
    private val position: StartingPosition = StartingPosition.Beginning,
    private val onMessage: (ConsumedMessage) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    auth: AuthStrategy = PlaintextAuth,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(KafkaMessageConsumer::class.java)
    private val running = AtomicBoolean(true)

    private val consumer = KafkaConsumer<ByteArray, ByteArray>(
        Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-desktop-${UUID.randomUUID()}")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500)
            auth.applyTo(this)
        }
    )

    private val thread = Thread({ runLoop() }, "kafka-consumer-$topic").apply {
        isDaemon = true
    }

    fun start() {
        thread.start()
    }

    private fun runLoop() {
        try {
            assignAndSeek()
            while (running.get()) {
                val batch = consumer.poll(Duration.ofMillis(500))
                for (record in batch) {
                    if (!running.get()) break
                    onMessage(
                        ConsumedMessage(
                            partition = record.partition(),
                            offset = record.offset(),
                            timestampMs = record.timestamp(),
                            key = record.key(),
                            value = record.value(),
                            headers = record.headers().associate { it.key() to it.value() },
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                log.error("Consumer loop failed for topic={}", topic, t)
                onError(t)
            }
        } finally {
            try {
                consumer.close(Duration.ofSeconds(5))
            } catch (e: Exception) {
                log.warn("Error closing consumer", e)
            }
        }
    }

    /** Discover the topic's partitions, assign them all, and seek per [position]. */
    private fun assignAndSeek() {
        val partitions = consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
        consumer.assign(partitions)
        seekTo(partitions, position)
    }

    private fun seekTo(partitions: List<TopicPartition>, position: StartingPosition) {
        when (position) {
            StartingPosition.Beginning -> consumer.seekToBeginning(partitions)
            StartingPosition.End -> consumer.seekToEnd(partitions)
            is StartingPosition.LastN -> {
                val begin = consumer.beginningOffsets(partitions)
                val end = consumer.endOffsets(partitions)
                for (tp in partitions) {
                    val lo = begin[tp] ?: 0L
                    val hi = end[tp] ?: lo
                    consumer.seek(tp, maxOf(lo, hi - position.n))
                }
            }
            is StartingPosition.FromTimestamp -> {
                val query = partitions.associateWith { position.epochMs }
                val found = consumer.offsetsForTimes(query)
                val noMatch = mutableListOf<TopicPartition>()
                for (tp in partitions) {
                    val ot = found[tp]
                    if (ot != null) consumer.seek(tp, ot.offset()) else noMatch.add(tp)
                }
                // Partitions with no record at/after the timestamp: position at the end (tail).
                if (noMatch.isNotEmpty()) consumer.seekToEnd(noMatch)
            }
            is StartingPosition.FromOffset -> {
                for (tp in partitions) consumer.seek(tp, position.offset)
            }
        }
    }

    override fun close() {
        running.set(false)
        consumer.wakeup()
        thread.join(3_000)
    }
}
