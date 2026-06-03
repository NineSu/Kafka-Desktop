package com.kdt.app

import com.kdt.app.di.appModule
import com.kdt.storage.AppSettings
import com.kdt.storage.AppSettingsStore
import com.kdt.ui.common.theme.ThemeManager
import com.kdt.ui.common.theme.ThemeMode
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
        val settingsStore = AppSettingsStore()
        val settings = settingsStore.load()
        ThemeManager.seed(ThemeMode.fromStorage(settings.themeMode), settings.accentHex)
        ThemeManager.addListener { mode, hex -> settingsStore.save(AppSettings(mode.storageKey, hex)) }
        MainView().show(primaryStage)
    }

    override fun stop() {
        stopKoin()
    }
}
