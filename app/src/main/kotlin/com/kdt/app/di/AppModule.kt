package com.kdt.app.di

import com.kdt.kafka.KafkaConnection
import org.koin.dsl.module

val appModule = module {
    factory { (bootstrapServers: String) -> KafkaConnection(bootstrapServers) }
}
