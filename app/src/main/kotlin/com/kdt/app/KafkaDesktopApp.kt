package com.kdt.app

import com.kdt.app.di.appModule
import javafx.application.Application
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
        MainView().show(primaryStage)
    }

    override fun stop() {
        stopKoin()
    }
}
