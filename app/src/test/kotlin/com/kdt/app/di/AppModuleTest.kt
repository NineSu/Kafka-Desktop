package com.kdt.app.di

import com.kdt.kafka.KafkaConnection
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.core.parameter.parametersOf
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension

class AppModuleTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinExtension = KoinTestExtension.create {
        modules(appModule)
    }

    @Test
    fun `KafkaConnection can be injected with bootstrapServers parameter`() {
        val connection: KafkaConnection by inject { parametersOf("localhost:9092") }
        connection.shouldBeInstanceOf<KafkaConnection>()
        connection.close()
    }
}
