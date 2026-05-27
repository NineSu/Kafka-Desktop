package com.kdt.kafka

import com.kdt.auth.AuthStrategy
import com.kdt.auth.PlaintextAuth
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.util.Properties
import java.util.concurrent.CompletableFuture

data class SendResult(val topic: String, val partition: Int, val offset: Long)

class KafkaMessageProducer(
    bootstrapServers: String,
    auth: AuthStrategy = PlaintextAuth,
    clientId: String = "kafka-desktop-producer",
) : AutoCloseable {

    private val producer = KafkaProducer<ByteArray, ByteArray>(
        Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ProducerConfig.CLIENT_ID_CONFIG, clientId)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
            put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000)
            auth.applyTo(this)
        }
    )

    fun send(
        topic: String,
        key: ByteArray?,
        value: ByteArray?,
        headers: Map<String, ByteArray?> = emptyMap(),
        partition: Int? = null,
    ): CompletableFuture<SendResult> {
        val record = ProducerRecord(topic, partition, null, key, value)
        for ((name, v) in headers) {
            record.headers().add(RecordHeader(name, v))
        }
        val fut = CompletableFuture<SendResult>()
        producer.send(record) { metadata, ex ->
            if (ex != null) fut.completeExceptionally(ex)
            else fut.complete(SendResult(metadata.topic(), metadata.partition(), metadata.offset()))
        }
        return fut
    }

    fun flush() = producer.flush()

    override fun close() = producer.close()
}
