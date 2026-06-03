package com.kdt.storage

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AppSettingsStoreTest {

    @Test
    fun `load returns defaults when file is absent`(@TempDir dir: Path) {
        val store = AppSettingsStore(dir.resolve("appearance.json"))
        store.load() shouldBe AppSettings()
    }

    @Test
    fun `save then load round-trips`(@TempDir dir: Path) {
        val store = AppSettingsStore(dir.resolve("appearance.json"))
        store.save(AppSettings(themeMode = "dark", accentHex = "#8A5CF6"))
        store.load() shouldBe AppSettings(themeMode = "dark", accentHex = "#8A5CF6")
    }

    @Test
    fun `save creates parent directories`(@TempDir dir: Path) {
        val path = dir.resolve("nested/sub/appearance.json")
        AppSettingsStore(path).save(AppSettings(themeMode = "dark"))
        Files.exists(path) shouldBe true
    }

    @Test
    fun `corrupt file falls back to defaults`(@TempDir dir: Path) {
        val path = dir.resolve("appearance.json")
        Files.writeString(path, "{ this is not valid json")
        AppSettingsStore(path).load() shouldBe AppSettings()
    }
}
