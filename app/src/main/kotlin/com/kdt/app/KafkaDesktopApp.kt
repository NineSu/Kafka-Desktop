package com.kdt.app

import com.kdt.app.di.appModule
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin

class KafkaDesktopApp : Application() {

    override fun init() {
        startKoin {
            modules(appModule)
        }
    }

    override fun start(primaryStage: Stage) {
        val root = StackPane(Label("Kafka Desktop — ready"))
        primaryStage.title = "Kafka Desktop"
        primaryStage.scene = Scene(root, 800.0, 600.0)
        primaryStage.show()
    }

    override fun stop() {
        stopKoin()
    }
}
