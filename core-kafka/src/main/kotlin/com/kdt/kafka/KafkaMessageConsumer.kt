package com.kdt.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
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
 * Backpressure note: this iteration delivers every message synchronously to [onMessage].
 * Downstream (UI) must absorb at the polling rate. Iteration 3 will introduce a bounded
 * queue + DuckDB sink to decouple consumption from rendering.
 */
class KafkaMessageConsumer(
    bootstrapServers: String,
    private val topic: String,
    private val fromBeginning: Boolean = true,
    private val onMessage: (ConsumedMessage) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(KafkaMessageConsumer::class.java)
    private val running = AtomicBoolean(true)

    private val consumer = KafkaConsumer<ByteArray, ByteArray>(
        Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-desktop-${UUID.randomUUID()}")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, if (fromBeginning) "earliest" else "latest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500)
        }
    )

    private val thread = Thread({ runLoop() }, "kafka-consumer-$topic").apply {
        isDaemon = true
    }

    fun start() {
        consumer.subscribe(listOf(topic))
        thread.start()
    }

    private fun runLoop() {
        try {
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

    override fun close() {
        running.set(false)
        consumer.wakeup()
        thread.join(3_000)
    }
}
