# Islands Theme System Implementation Plan (iter-14)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Kafka Desktop's bare JavaFX Modena look with an Islands Light/Dark theme, add a full menu bar + toolbar, and a Settings → Appearance panel for choosing the accent color (7 presets + custom color picker), applied at runtime and persisted.

**Architecture:** One hand-written `islands.css` whose colors are JavaFX *looked-up colors*. Light/Dark = two root style classes (`.theme-light`/`.theme-dark`) that redefine those colors; the accent is overridden at runtime via an inline `-accent:` style on each scene/dialog root so JavaFX re-derives selection/hover colors instantly. A `ThemeManager` singleton (in `ui-common`, persistence-agnostic) applies the theme to every registered `Scene`/`DialogPane` and fires a change listener; the `app` module wires load/save to an `AppSettingsStore` (in `core-storage`, mirrors `ConnectionStore`). Zero new dependencies.

**Tech Stack:** Kotlin 1.9.25, JavaFX 21.0.4, Gradle (multi-module), Jackson (JSON), JUnit5 + Kotest (core-storage tests), JUnit5 (ui-common tests).

**Repo conventions:** Work on branch `iter-14-islands-theme` (already created). Every commit message ends with the trailer:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

**Spec:** `docs/superpowers/specs/2026-06-03-kafka-desktop-iter-14-islands-theme-design.md`

---

## File Structure

**Created:**
- `core-storage/src/main/kotlin/com/kdt/storage/AppSettings.kt` — settings data class
- `core-storage/src/main/kotlin/com/kdt/storage/AppSettingsStore.kt` — JSON persistence
- `core-storage/src/test/kotlin/com/kdt/storage/AppSettingsStoreTest.kt`
- `ui-common/src/main/kotlin/com/kdt/ui/common/theme/ThemeMode.kt`
- `ui-common/src/main/kotlin/com/kdt/ui/common/theme/AccentPreset.kt`
- `ui-common/src/main/kotlin/com/kdt/ui/common/theme/ThemeColors.kt` — pure hex/color helpers
- `ui-common/src/main/kotlin/com/kdt/ui/common/theme/ThemeManager.kt` — singleton + `Dialog.applyTheme()`
- `ui-common/src/main/resources/com/kdt/ui/common/theme/islands.css`
- `ui-common/src/test/kotlin/com/kdt/ui/common/theme/ThemeColorsTest.kt`
- `ui-common/src/test/kotlin/com/kdt/ui/common/theme/IslandsCssTest.kt`
- `ui-common/src/test/kotlin/com/kdt/ui/common/theme/ThemeManagerTest.kt`
- `ui-common/src/main/kotlin/com/kdt/ui/common/SettingsDialog.kt`
- `app/src/main/kotlin/com/kdt/app/AppTopBar.kt` — menu bar + toolbar + `AppActions`

**Modified:**
- `app/src/main/kotlin/com/kdt/app/KafkaDesktopApp.kt` — seed + persist theme at startup
- `app/src/main/kotlin/com/kdt/app/MainView.kt` — register scene, top bar, island classes, status-style refactor, dialog theming
- `ui-common/src/main/kotlin/com/kdt/ui/common/ConnectionForm.kt` — status style classes
- The ~13 dialog classes (app + ui-common) — one `applyTheme()` call each

**No build-file changes.** (`ui-common` keeps no `core-storage` dependency; no new libraries.)

---

## Task 1: AppSettings + AppSettingsStore (core-storage)

**Files:**
- Create: `core-storage/src/main/kotlin/com/kdt/storage/AppSettings.kt`
- Create: `core-storage/src/main/kotlin/com/kdt/storage/AppSettingsStore.kt`
- Test: `core-storage/src/test/kotlin/com/kdt/storage/AppSettingsStoreTest.kt`

- [ ] **Step 1: Write the data class**

`AppSettings.kt`:
```kotlin
package com.kdt.storage

/**
 * Persisted UI appearance settings. Written as plain JSON (no secrets) to
 * `~/.kafka-desktop/appearance.json` by [AppSettingsStore].
 *
 * @param themeMode "light" or "dark" (the storage key of ui-common's ThemeMode)
 * @param accentHex "#RRGGBB" accent color
 */
data class AppSettings(
    val themeMode: String = "light",
    val accentHex: String = "#3574F0",
)
```

- [ ] **Step 2: Write the failing test**

`AppSettingsStoreTest.kt` (mirrors `ConnectionStoreTest`/`FilterStoreTest` style — Kotest matchers + JUnit5 `@TempDir`):
```kotlin
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
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :core-storage:test --tests "com.kdt.storage.AppSettingsStoreTest"`
Expected: FAIL to compile — `AppSettingsStore` is unresolved.

- [ ] **Step 4: Write the store**

`AppSettingsStore.kt` (mirrors `ConnectionStore` exactly: Jackson + graceful fallback):
```kotlin
package com.kdt.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Loads/saves [AppSettings] as a JSON object at [jsonPath]
 * (default `~/.kafka-desktop/appearance.json`). Resilient: a missing or
 * corrupt file yields defaults rather than crashing.
 *
 * Not thread-safe by design — mutated from the JavaFX thread only.
 */
class AppSettingsStore(
    private val jsonPath: Path = defaultPath(),
) {
    private val log = LoggerFactory.getLogger(AppSettingsStore::class.java)
    private val mapper = ObjectMapper().registerKotlinModule()

    fun load(): AppSettings {
        if (!Files.exists(jsonPath)) return AppSettings()
        return try {
            val bytes = Files.readAllBytes(jsonPath)
            if (bytes.isEmpty()) AppSettings() else mapper.readValue(bytes)
        } catch (t: Throwable) {
            log.warn("Failed to read appearance settings from {} — using defaults", jsonPath, t)
            AppSettings()
        }
    }

    fun save(settings: AppSettings) {
        try {
            jsonPath.parent?.let { Files.createDirectories(it) }
            Files.write(jsonPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings))
        } catch (t: Throwable) {
            log.error("Failed to persist appearance settings to {}", jsonPath, t)
        }
    }

    companion object {
        fun defaultPath(): Path =
            Paths.get(System.getProperty("user.home"), ".kafka-desktop", "appearance.json")
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :core-storage:test --tests "com.kdt.storage.AppSettingsStoreTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add core-storage/src/main/kotlin/com/kdt/storage/AppSettings.kt \
        core-storage/src/main/kotlin/com/kdt/storage/AppSettingsStore.kt \
        core-storage/src/test/kotlin/com/kdt/storage/AppSettingsStoreTest.kt
git commit -m "feat(iter-14): AppSettings + AppSettingsStore (appearance persistence)"
```

---

## Task 2: Theming model — ThemeMode, AccentPreset, ThemeColors (ui-common)

Pure logic, no JavaFX toolkit needed. ui-common has only JUnit5 → use `org.junit.jupiter.api.Assertions`.

**Files:**
- Create: `ui-common/src/main/kotlin/com/kdt/ui/common/theme/ThemeMode.kt`
- Create: `ui-common/src/main/kotlin/com/kdt/ui/common/theme/AccentPreset.kt`
- Create: `ui-common/src/main/kotlin/com/kdt/ui/common/theme/ThemeColors.kt`
- Test: `ui-common/src/test/kotlin/com/kdt/ui/common/theme/ThemeColorsTest.kt`

- [ ] **Step 1: Write ThemeMode**

`ThemeMode.kt`:
```kotlin
package com.kdt.ui.common.theme

/** The two Islands palettes. [styleClass] is the root CSS class; [storageKey] is the persisted string. */
enum class ThemeMode(val storageKey: String, val styleClass: String) {
    LIGHT("light", "theme-light"),
    DARK("dark", "theme-dark");

    companion object {
        fun fromStorage(key: String): ThemeMode =
            entries.firstOrNull { it.storageKey == key.trim().lowercase() } ?: LIGHT
    }
}
```

- [ ] **Step 2: Write AccentPreset**

`AccentPreset.kt`:
```kotlin
package com.kdt.ui.common.theme

/** Built-in accent colors shown as quick swatches in Settings. Users may also pick a custom color. */
enum class AccentPreset(val displayName: String, val hex: String) {
    BLUE("Blue", "#3574F0"),
    PURPLE("Purple", "#8A5CF6"),
    TEAL("Teal", "#16A394"),
    GREEN("Green", "#1F9254"),
    AMBER("Amber", "#E0871E"),
    RED("Red", "#E0556B"),
    GRAPHITE("Graphite", "#5B6470");

    companion object {
        val DEFAULT = BLUE
    }
}
```

- [ ] **Step 3: Write the failing test**

`ThemeColorsTest.kt`:
```kotlin
package com.kdt.ui.common.theme

import javafx.scene.paint.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeColorsTest {

    @Test
    fun `valid 6-digit hex passes`() {
        assertTrue(ThemeColors.isValidHex("#3574F0"))
        assertTrue(ThemeColors.isValidHex("#abcdef"))
    }

    @Test
    fun `invalid hex fails`() {
        assertFalse(ThemeColors.isValidHex("3574F0"))   // no #
        assertFalse(ThemeColors.isValidHex("#FFF"))      // 3-digit
        assertFalse(ThemeColors.isValidHex("#GGGGGG"))   // non-hex
        assertFalse(ThemeColors.isValidHex(""))
    }

    @Test
    fun `normalize uppercases and trims, rejects invalid`() {
        assertEquals("#ABCDEF", ThemeColors.normalizeHex("  #abcdef "))
        assertNull(ThemeColors.normalizeHex("nope"))
    }

    @Test
    fun `toHex formats an RGB color`() {
        assertEquals("#FF8800", ThemeColors.toHex(Color.web("#FF8800")))
        assertEquals("#000000", ThemeColors.toHex(Color.BLACK))
        assertEquals("#FFFFFF", ThemeColors.toHex(Color.WHITE))
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :ui-common:test --tests "com.kdt.ui.common.theme.ThemeColorsTest"`
Expected: FAIL to compile — `ThemeColors` unresolved.

- [ ] **Step 5: Write ThemeColors**

`ThemeColors.kt`:
```kotlin
package com.kdt.ui.common.theme

import javafx.scene.paint.Color

/** Pure helpers for accent-color handling. No JavaFX toolkit required. */
object ThemeColors {
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    fun isValidHex(s: String): Boolean = HEX.matches(s.trim())

    /** Returns the trimmed, upper-cased `#RRGGBB`, or null if not a valid 6-digit hex. */
    fun normalizeHex(s: String): String? = s.trim().uppercase().takeIf { HEX.matches(it) }

    /** Formats a JavaFX [Color]'s RGB channels as `#RRGGBB`. */
    fun toHex(c: Color): String = String.format(
        "#%02X%02X%02X",
        Math.round(c.red * 255).toInt(),
        Math.round(c.green * 255).toInt(),
        Math.round(c.blue * 255).toInt(),
    )
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :ui-common:test --tests "com.kdt.ui.common.theme.ThemeColorsTest"`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add ui-common/src/main/kotlin/com/kdt/ui/common/theme/ ui-common/src/test/kotlin/com/kdt/ui/common/theme/ThemeColorsTest.kt
git commit -m "feat(iter-14): theming model — ThemeMode, AccentPreset, ThemeColors"
```

---

## Task 3: islands.css stylesheet + resource-load test (ui-common)

**Files:**
- Create: `ui-common/src/main/resources/com/kdt/ui/common/theme/islands.css`
- Test: `ui-common/src/test/kotlin/com/kdt/ui/common/theme/IslandsCssTest.kt`

- [ ] **Step 1: Write the failing resource test**

`IslandsCssTest.kt` (guards that the resource is on the classpath at the exact path ThemeManager loads, and defines both themes):
```kotlin
package com.kdt.ui.common.theme

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IslandsCssTest {

    @Test
    fun `islands css is on the classpath and defines both themes plus accent`() {
        val url = javaClass.getResource("/com/kdt/ui/common/theme/islands.css")
        assertNotNull(url, "islands.css must be at /com/kdt/ui/common/theme/islands.css")
        val css = url!!.readText()
        assertTrue(css.contains(".theme-light"), "missing .theme-light")
        assertTrue(css.contains(".theme-dark"), "missing .theme-dark")
        assertTrue(css.contains("-accent"), "missing -accent token")
        assertTrue(css.contains(".island"), "missing .island")
        assertTrue(css.contains(".status-ok"), "missing .status-ok")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :ui-common:test --tests "com.kdt.ui.common.theme.IslandsCssTest"`
Expected: FAIL — resource not found (assertNotNull fails).

- [ ] **Step 3: Write the stylesheet**

`islands.css` (complete; visual fine-tuning expected during the run-check in Task 6/7):
```css
/* ============================================================
   Kafka Desktop — Islands theme (Light + Dark)  [iter-14]
   Colors are JavaFX looked-up colors defined per theme class
   on the root. The accent is overridden at runtime via an
   inline "-accent:" style set by ThemeManager.
   ============================================================ */

/* ---- Root defaults (accent overridden inline at runtime) ---- */
.root {
    -accent: #3574F0;
    -fx-font-family: "Inter", "SF Pro Text", "Segoe UI", "System";
    -fx-font-size: 13px;
    -fx-focus-color: -accent;
    -fx-faint-focus-color: transparent;
}

/* ---- Light palette ---- */
.theme-light {
    -sea: #EDEEF2;
    -island: #FFFFFF;
    -island-border: #E2E4EA;
    -line: #ECEEF2;
    -text: #272930;
    -text-muted: #7A7E87;
    -text-faint: #A0A4AD;
    -control-bg: #F6F7F9;
    -control-border: #CDD1DA;
    -chip: #EEF0F4;
    -selection-bg: derive(-accent, 80%);
    -selection-text: derive(-accent, -20%);
    -status-ok: #1F9254;
    -status-err: #D4374B;
    -status-info: #3574F0;
    -fx-background-color: -sea;
}

/* ---- Dark palette ---- */
.theme-dark {
    -sea: #1A1B1E;
    -island: #2B2D30;
    -island-border: #393B40;
    -line: #34363A;
    -text: #DFE1E5;
    -text-muted: #9DA0A8;
    -text-faint: #6F737A;
    -control-bg: #363840;
    -control-border: #4B4E55;
    -chip: #3A3D42;
    -selection-bg: derive(-accent, -50%);
    -selection-text: derive(-accent, 55%);
    -status-ok: #62B06A;
    -status-err: #E16A77;
    -status-info: #6BA5F7;
    -fx-background-color: -sea;
}

/* ---- App shell ---- */
.app-root { -fx-padding: 5; -fx-background-color: -sea; }

.island {
    -fx-background-color: -island;
    -fx-background-radius: 11;
    -fx-border-color: -island-border;
    -fx-border-radius: 11;
    -fx-border-width: 1;
    -fx-effect: dropshadow(gaussian, rgba(20,24,40,0.06), 10, 0.0, 0, 2);
}

/* SplitPane: show the sea between islands as a thin gap */
.island-split { -fx-background-color: -sea; -fx-padding: 0; }
.island-split > .split-pane-divider {
    -fx-background-color: -sea;
    -fx-padding: 0 2.5 0 2.5;
    -fx-border-width: 0;
}

/* ---- Text ---- */
.label { -fx-text-fill: -text; }
.text-field, .text-area { -fx-text-fill: -text; }
.mono { -fx-font-family: "JetBrains Mono", "SF Mono", "Consolas", monospace; -fx-font-size: 12px; }
.header-strong { -fx-padding: 6 12 6 12; -fx-font-weight: bold; -fx-text-fill: -text; }
.muted-label  { -fx-padding: 6 8 6 8; -fx-text-fill: -text-muted; }

/* status labels */
.status-label { -fx-padding: 6 12 6 12; }
.status-info  { -fx-text-fill: -status-info; }
.status-ok    { -fx-text-fill: -status-ok; -fx-font-weight: bold; }
.status-err   { -fx-text-fill: -status-err; -fx-font-weight: bold; }

/* ---- Buttons ---- */
.button {
    -fx-background-radius: 7;
    -fx-border-radius: 7;
    -fx-background-color: -control-bg;
    -fx-border-color: -control-border;
    -fx-border-width: 1;
    -fx-text-fill: -text;
    -fx-padding: 5 13 5 13;
    -fx-cursor: hand;
}
.button:hover { -fx-background-color: derive(-control-bg, -4%); }
.button:armed { -fx-background-color: derive(-control-bg, -8%); }
.primary-button {
    -fx-background-color: -accent;
    -fx-border-color: -accent;
    -fx-text-fill: white;
}
.primary-button:hover { -fx-background-color: derive(-accent, -8%); -fx-border-color: derive(-accent, -8%); }
.gear-button { -fx-padding: 5 9 5 9; -fx-font-size: 15px; }

/* ---- Inputs ---- */
.text-field, .text-area, .combo-box, .choice-box, .spinner, .color-picker {
    -fx-background-radius: 7;
    -fx-border-radius: 7;
    -fx-background-color: -island;
    -fx-border-color: -control-border;
    -fx-border-width: 1;
}
.text-area .content { -fx-background-color: -island; }
.text-field:focused, .text-area:focused, .combo-box:focused {
    -fx-border-color: -accent;
}
.combo-box .list-cell { -fx-text-fill: -text; -fx-background-color: transparent; }
.combo-box-popup .list-view { -fx-background-color: -island; -fx-border-color: -island-border; }

/* ---- ListView (topic list) ---- */
.list-view { -fx-background-color: transparent; -fx-border-width: 0; -fx-padding: 2; }
.list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: -text;
    -fx-padding: 6 12 6 12;
}
.list-cell:filled:hover { -fx-background-color: -chip; }
.list-cell:filled:selected {
    -fx-background-color: -selection-bg;
    -fx-text-fill: -selection-text;
    -fx-font-weight: bold;
}

/* ---- TableView (message table) ---- */
.table-view { -fx-background-color: transparent; -fx-border-width: 0; -fx-table-cell-border-color: transparent; }
.table-view .column-header-background { -fx-background-color: derive(-island, -2%); -fx-background-radius: 0; }
.table-view .column-header, .table-view .filler { -fx-background-color: transparent; -fx-border-color: transparent -line transparent transparent; }
.table-view .column-header .label { -fx-text-fill: -text-faint; -fx-font-weight: bold; -fx-alignment: center-left; }
.table-row-cell { -fx-background-color: -island; -fx-border-color: transparent transparent -line transparent; -fx-table-cell-border-color: transparent; }
.table-row-cell:odd { -fx-background-color: derive(-island, -1.5%); }
.table-row-cell:hover { -fx-background-color: -chip; }
.table-row-cell:selected { -fx-background-color: -selection-bg; }
.table-row-cell:selected .text { -fx-fill: -selection-text; }
.table-cell { -fx-text-fill: -text; -fx-padding: 5 11 5 11; -fx-border-width: 0; }

/* ---- TabPane (detail pane) ---- */
.tab-pane .tab-header-background { -fx-background-color: transparent; }
.tab-pane .tab { -fx-background-color: transparent; -fx-background-radius: 7 7 0 0; -fx-padding: 5 13 5 13; }
.tab-pane .tab:selected { -fx-background-color: -selection-bg; }
.tab-pane .tab .tab-label { -fx-text-fill: -text-muted; }
.tab-pane .tab:selected .tab-label { -fx-text-fill: -selection-text; -fx-font-weight: bold; }
.tab-pane > .tab-header-area > .headers-region > .tab:selected .focus-indicator { -fx-border-color: transparent; }

/* ---- ScrollBar ---- */
.scroll-bar { -fx-background-color: transparent; }
.scroll-bar .thumb { -fx-background-color: -control-border; -fx-background-radius: 6; }
.scroll-bar .thumb:hover { -fx-background-color: -text-faint; }
.scroll-bar .track { -fx-background-color: transparent; }
.scroll-bar .increment-button, .scroll-bar .decrement-button { -fx-background-color: transparent; -fx-padding: 0; }
.scroll-pane { -fx-background-color: transparent; }
.scroll-pane > .viewport { -fx-background-color: transparent; }

/* ---- Menu bar / toolbar ---- */
.app-topbar { -fx-background-color: -island; -fx-border-color: transparent transparent -island-border transparent; -fx-border-width: 1; }
.topbar-row { -fx-padding: 6 11 6 11; }
.app-logo {
    -fx-background-color: -accent; -fx-text-fill: white; -fx-font-weight: bold;
    -fx-background-radius: 6; -fx-min-width: 22; -fx-min-height: 22; -fx-alignment: center;
}
.app-name { -fx-font-weight: bold; -fx-text-fill: -text; -fx-padding: 0 8 0 4; }
.menu-bar { -fx-background-color: transparent; -fx-padding: 0; }
.menu-bar > .container > .menu-button { -fx-background-radius: 6; }
.menu-bar > .container > .menu-button:hover, .menu-bar > .container > .menu-button:showing { -fx-background-color: -selection-bg; }
.menu-bar .label { -fx-text-fill: -text; }
.context-menu, .menu-bar .context-menu { -fx-background-color: -island; -fx-border-color: -island-border; -fx-border-radius: 9; -fx-background-radius: 9; -fx-effect: dropshadow(gaussian, rgba(20,24,40,0.18), 16, 0, 0, 4); }
.menu-item:focused { -fx-background-color: -selection-bg; }
.menu-item:focused .label { -fx-text-fill: -selection-text; }
.accent-chip { -fx-border-color: -control-border; -fx-border-radius: 20; -fx-background-radius: 20; -fx-padding: 3 9 3 9; }
.accent-chip .label { -fx-text-fill: -text-muted; -fx-font-size: 11px; }
.chip { -fx-background-color: -chip; -fx-background-radius: 6; -fx-padding: 3 10 3 10; -fx-font-weight: bold; -fx-text-fill: -text; }

/* ---- Dialogs ---- */
.dialog-pane { -fx-background-color: -sea; }
.dialog-pane > .content { -fx-background-color: -sea; }
.dialog-pane .header-panel { -fx-background-color: -island; }
.dialog-pane .header-panel .label { -fx-text-fill: -text; -fx-font-weight: bold; }
.dialog-pane .button-bar .button { -fx-min-width: 72; }

/* ---- ColorPicker (custom accent) ---- */
.color-picker { -fx-color-label-visible: true; }

/* ---- Settings dialog ---- */
.settings-root { -fx-min-width: 660; -fx-min-height: 320; }
.settings-nav { -fx-background-color: derive(-sea, 2%); -fx-border-color: transparent -line transparent transparent; -fx-border-width: 1; -fx-min-width: 188; -fx-padding: 8; }
.settings-nav-group { -fx-font-weight: bold; -fx-text-fill: -text; -fx-padding: 7 9 7 9; }
.settings-nav-item { -fx-text-fill: -text-muted; -fx-padding: 6 9 6 22; -fx-background-radius: 7; }
.settings-nav-item-selected { -fx-background-color: -selection-bg; -fx-text-fill: -selection-text; -fx-font-weight: bold; }
.settings-pane { -fx-padding: 16 18 16 18; }
.section-label { -fx-font-size: 11px; -fx-text-fill: -text-faint; -fx-font-weight: bold; }
.accent-swatch { -fx-min-width: 26; -fx-min-height: 26; -fx-background-radius: 8; -fx-cursor: hand; -fx-border-width: 0; }
.accent-swatch-selected { -fx-border-color: -text; -fx-border-width: 2; -fx-border-radius: 8; }
.preview-pane { -fx-background-color: derive(-island, -1%); -fx-border-color: -line; -fx-border-width: 1; -fx-background-radius: 9; -fx-border-radius: 9; -fx-padding: 12; }
.link-label { -fx-text-fill: -accent; -fx-underline: true; }

/* segmented toggle (Light/Dark) */
.toggle-button { -fx-background-color: -control-bg; -fx-text-fill: -text; -fx-border-color: -control-border; -fx-border-width: 1; -fx-padding: 5 14 5 14; }
.toggle-button:selected { -fx-background-color: -accent; -fx-text-fill: white; -fx-border-color: -accent; }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :ui-common:test --tests "com.kdt.ui.common.theme.IslandsCssTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ui-common/src/main/resources/com/kdt/ui/common/theme/islands.css \
        ui-common/src/test/kotlin/com/kdt/ui/common/theme/IslandsCssTest.kt
git commit -m "feat(iter-14): islands.css (Light/Dark looked-up-color stylesheet)"
```

---

## Task 4: ThemeManager + Dialog.applyTheme (ui-common)

**Files:**
- Create: `ui-common/src/main/kotlin/com/kdt/ui/common/theme/ThemeManager.kt`
- Test: `ui-common/src/test/kotlin/com/kdt/ui/common/theme/ThemeManagerTest.kt`

- [ ] **Step 1: Write ThemeManager + applyTheme extension**

`ThemeManager.kt`:
```kotlin
package com.kdt.ui.common.theme

import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Dialog
import javafx.scene.control.DialogPane
import java.util.Collections
import java.util.WeakHashMap

/**
 * Central theme state + applier. Persistence-agnostic: holds the current
 * [mode] + [accentHex], applies them to every registered Scene/DialogPane,
 * and notifies listeners on change. The app module wires a listener to persist.
 *
 * Applying = (1) ensure islands.css is attached, (2) swap the theme-light/
 * theme-dark style class on the root, (3) set an inline `-accent:` so JavaFX
 * re-derives selection/hover colors instantly.
 */
object ThemeManager {

    private val cssUrl: String = ThemeManager::class.java
        .getResource("/com/kdt/ui/common/theme/islands.css")
        ?.toExternalForm()
        ?: error("islands.css not found on classpath")

    var mode: ThemeMode = ThemeMode.LIGHT
        private set
    var accentHex: String = AccentPreset.DEFAULT.hex
        private set

    private val roots = Collections.newSetFromMap(WeakHashMap<Parent, Boolean>())
    private val listeners = mutableListOf<(ThemeMode, String) -> Unit>()

    /** Set initial state without notifying (called once at startup from persisted settings). */
    fun seed(mode: ThemeMode, accentHex: String) {
        this.mode = mode
        this.accentHex = ThemeColors.normalizeHex(accentHex) ?: AccentPreset.DEFAULT.hex
    }

    fun addListener(listener: (ThemeMode, String) -> Unit) { listeners.add(listener) }

    fun register(scene: Scene) {
        if (!scene.stylesheets.contains(cssUrl)) scene.stylesheets.add(cssUrl)
        apply(scene.root)
        roots.add(scene.root)
    }

    fun register(pane: DialogPane) {
        if (!pane.stylesheets.contains(cssUrl)) pane.stylesheets.add(cssUrl)
        apply(pane)
        roots.add(pane)
    }

    fun setMode(newMode: ThemeMode) {
        if (newMode == mode) return
        mode = newMode
        roots.forEach(::apply)
        notifyListeners()
    }

    fun setAccent(hex: String) {
        val normalized = ThemeColors.normalizeHex(hex) ?: return
        if (normalized == accentHex) return
        accentHex = normalized
        roots.forEach(::apply)
        notifyListeners()
    }

    private fun apply(root: Parent) {
        root.styleClass.removeAll("theme-light", "theme-dark")
        root.styleClass.add(mode.styleClass)
        root.style = "-accent: $accentHex;"
    }

    private fun notifyListeners() {
        val snapshot = listeners.toList()
        snapshot.forEach { it(mode, accentHex) }
    }
}

/** Apply the current theme to any dialog (works for Alert too — it extends Dialog). */
fun Dialog<*>.applyTheme() {
    ThemeManager.register(this.dialogPane)
}
```

- [ ] **Step 2: Write the failing test**

`ThemeManagerTest.kt` (exercises state + listeners with no registered scenes, so no JavaFX toolkit is needed):
```kotlin
package com.kdt.ui.common.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ThemeManagerTest {

    @BeforeEach
    fun reset() {
        // Known starting point; seed() does not notify listeners.
        ThemeManager.seed(ThemeMode.LIGHT, AccentPreset.DEFAULT.hex)
    }

    @Test
    fun `seed sets mode and accent`() {
        ThemeManager.seed(ThemeMode.DARK, "#8A5CF6")
        assertEquals(ThemeMode.DARK, ThemeManager.mode)
        assertEquals("#8A5CF6", ThemeManager.accentHex)
    }

    @Test
    fun `seed with invalid accent falls back to default`() {
        ThemeManager.seed(ThemeMode.LIGHT, "garbage")
        assertEquals(AccentPreset.DEFAULT.hex, ThemeManager.accentHex)
    }

    @Test
    fun `setMode updates state and notifies listener`() {
        var seen: ThemeMode? = null
        ThemeManager.addListener { m, _ -> seen = m }
        ThemeManager.setMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, ThemeManager.mode)
        assertEquals(ThemeMode.DARK, seen)
    }

    @Test
    fun `setAccent normalizes and notifies`() {
        var seenHex: String? = null
        ThemeManager.addListener { _, hex -> seenHex = hex }
        ThemeManager.setAccent("#abcdef")
        assertEquals("#ABCDEF", ThemeManager.accentHex)
        assertEquals("#ABCDEF", seenHex)
    }

    @Test
    fun `setAccent ignores invalid hex`() {
        var calls = 0
        ThemeManager.addListener { _, _ -> calls++ }
        ThemeManager.setAccent("not-a-color")
        assertEquals(AccentPreset.DEFAULT.hex, ThemeManager.accentHex)
        assertEquals(0, calls)
    }
}
```

> Note: `ThemeManager` is a singleton, so listeners added by earlier tests linger. Each test asserts via its own captured local var (lingering listeners are harmless), and `@BeforeEach` resets `mode`/`accentHex`.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :ui-common:test --tests "com.kdt.ui.common.theme.ThemeManagerTest"`
Expected: FAIL to compile — `ThemeManager` unresolved.

- [ ] **Step 4: (implementation already written in Step 1)**

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :ui-common:test --tests "com.kdt.ui.common.theme.ThemeManagerTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add ui-common/src/main/kotlin/com/kdt/ui/common/theme/ThemeManager.kt \
        ui-common/src/test/kotlin/com/kdt/ui/common/theme/ThemeManagerTest.kt
git commit -m "feat(iter-14): ThemeManager singleton + Dialog.applyTheme()"
```

---

## Task 5: SettingsDialog — Appearance page (ui-common)

**Files:**
- Create: `ui-common/src/main/kotlin/com/kdt/ui/common/SettingsDialog.kt`

No unit test (UI). Verified by compile + manual run in Task 7.

- [ ] **Step 1: Write SettingsDialog**

`SettingsDialog.kt`:
```kotlin
package com.kdt.ui.common

import com.kdt.ui.common.theme.AccentPreset
import com.kdt.ui.common.theme.ThemeColors
import com.kdt.ui.common.theme.ThemeManager
import com.kdt.ui.common.theme.ThemeMode
import com.kdt.ui.common.theme.applyTheme
import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.ColorPicker
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color

/**
 * Settings → Appearance & Behavior → Appearance. Lets the user switch
 * Light/Dark and pick an accent (7 presets + custom ColorPicker), with a
 * live preview. Changes apply immediately via [ThemeManager]; Cancel restores
 * the snapshot taken on open, Apply commits the current state as the new baseline.
 */
class SettingsDialog : Dialog<Unit>() {

    private var snapMode: ThemeMode = ThemeManager.mode
    private var snapAccent: String = ThemeManager.accentHex

    private val swatches = mutableListOf<Button>()
    private val hexLabel = Label(ThemeManager.accentHex).apply { styleClass.add("mono") }
    private val picker = ColorPicker(Color.web(ThemeManager.accentHex))

    init {
        title = "Settings"
        isResizable = true

        val content = HBox(buildNav(), buildAppearancePane()).apply { styleClass.add("settings-root") }
        dialogPane.content = content
        dialogPane.buttonTypes.addAll(ButtonType.CANCEL, ButtonType.APPLY, ButtonType.OK)
        applyTheme()

        // APPLY: re-baseline the snapshot and keep the dialog open.
        (dialogPane.lookupButton(ButtonType.APPLY) as Button).addEventFilter(ActionEvent.ACTION) { e ->
            snapMode = ThemeManager.mode
            snapAccent = ThemeManager.accentHex
            e.consume()
        }
        // CANCEL: restore the snapshot. OK: keep current (no-op).
        setResultConverter { btn ->
            if (btn == ButtonType.CANCEL) {
                ThemeManager.setMode(snapMode)
                ThemeManager.setAccent(snapAccent)
            }
            null
        }
        refreshSelectedSwatch()
    }

    private fun buildNav(): VBox = VBox(
        Label("Appearance & Behavior").apply { styleClass.add("settings-nav-group") },
        Label("Appearance").apply { styleClass.addAll("settings-nav-item", "settings-nav-item-selected") },
        Label("Behavior").apply { styleClass.add("settings-nav-item") },
        Label("Connections").apply { styleClass.add("settings-nav-item"); isDisable = true },
        Label("Keymap").apply { styleClass.add("settings-nav-item"); isDisable = true },
    ).apply { styleClass.add("settings-nav") }

    private fun buildAppearancePane(): VBox {
        // --- Theme (Light/Dark) ---
        val group = ToggleGroup()
        val light = ToggleButton("Light").apply { toggleGroup = group; isSelected = ThemeManager.mode == ThemeMode.LIGHT }
        val dark = ToggleButton("Dark").apply { toggleGroup = group; isSelected = ThemeManager.mode == ThemeMode.DARK }
        light.setOnAction { if (light.isSelected) ThemeManager.setMode(ThemeMode.LIGHT) else light.isSelected = true }
        dark.setOnAction { if (dark.isSelected) ThemeManager.setMode(ThemeMode.DARK) else dark.isSelected = true }
        val themeRow = HBox(0.0, light, dark)

        // --- Accent presets ---
        val palette = FlowPane(9.0, 9.0)
        AccentPreset.entries.forEach { preset ->
            val sw = Button().apply {
                styleClass.add("accent-swatch")
                style = "-fx-background-color: ${preset.hex};"
                setOnAction { selectAccent(preset.hex) }
            }
            swatches.add(sw)
            palette.children.add(sw)
        }
        picker.setOnAction { selectAccent(ThemeColors.toHex(picker.value)) }
        val customRow = HBox(9.0, palette, picker, hexLabel).apply { alignment = Pos.CENTER_LEFT }

        // --- Preview ---
        val preview = HBox(14.0,
            Button("Connect").apply { styleClass.add("primary-button") },
            Label("orders.created").apply { styleClass.add("chip") },
            Label("Save filter…").apply { styleClass.add("link-label") },
        ).apply { alignment = Pos.CENTER_LEFT; styleClass.add("preview-pane") }

        return VBox(8.0,
            Label("THEME").apply { styleClass.add("section-label") },
            themeRow,
            spacer(),
            Label("ACCENT COLOR").apply { styleClass.add("section-label") },
            customRow,
            spacer(),
            Label("PREVIEW").apply { styleClass.add("section-label") },
            preview,
        ).apply { styleClass.add("settings-pane"); HBox.setHgrow(this, Priority.ALWAYS) }
    }

    private fun selectAccent(hex: String) {
        ThemeManager.setAccent(hex)
        hexLabel.text = ThemeManager.accentHex
        picker.value = Color.web(ThemeManager.accentHex)
        refreshSelectedSwatch()
    }

    private fun refreshSelectedSwatch() {
        AccentPreset.entries.forEachIndexed { i, preset ->
            val sw = swatches[i]
            if (preset.hex.equals(ThemeManager.accentHex, ignoreCase = true)) {
                if (!sw.styleClass.contains("accent-swatch-selected")) sw.styleClass.add("accent-swatch-selected")
            } else {
                sw.styleClass.remove("accent-swatch-selected")
            }
        }
    }

    private fun spacer() = Region().apply { minHeight = 6.0 }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :ui-common:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add ui-common/src/main/kotlin/com/kdt/ui/common/SettingsDialog.kt
git commit -m "feat(iter-14): SettingsDialog — Appearance page (theme + accent + preview)"
```

---

## Task 6: Wire startup, register MainView scene, refactor inline styles + island layout

**Files:**
- Modify: `app/src/main/kotlin/com/kdt/app/KafkaDesktopApp.kt`
- Modify: `app/src/main/kotlin/com/kdt/app/MainView.kt`
- Modify: `ui-common/src/main/kotlin/com/kdt/ui/common/ConnectionForm.kt`

- [ ] **Step 1: Seed + persist theme at startup**

In `KafkaDesktopApp.kt`, replace the `start` method body. Add imports and the wiring:
```kotlin
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
```

- [ ] **Step 2: Add the status-label helper to MainView**

In `MainView.kt`, add (e.g. just below the class header / companion). This DRYs up all the inline status styling:
```kotlin
    private enum class Status(val cssClass: String) {
        INFO("status-info"), OK("status-ok"), ERR("status-err")
    }

    /** Set the action label text + semantic status style class. */
    private fun action(text: String, status: Status) {
        actionLabel.text = text
        actionLabel.styleClass.setAll("status-label", status.cssClass)
    }
```

- [ ] **Step 3: Replace label constructions (remove inline styles, add style classes)**

In `MainView.kt`, change these four field initializers (lines ~82–90):
```kotlin
    private val topicHeader = Label("(no topic)").apply { styleClass.add("header-strong") }
    private val statsLabel = Label("").apply { styleClass.add("muted-label") }
    private val actionLabel = Label("").apply { styleClass.add("status-label") }
    // ... (sendButton/importButton/exportButton/prev/next unchanged) ...
    private val pageLabel = Label("").apply { styleClass.add("muted-label") }
```

- [ ] **Step 4: Replace every `actionLabel.text = …; actionLabel.style = …` pair with `action(...)`**

Apply each replacement (search for `actionLabel.style`):

| Location (method) | Old | New |
|---|---|---|
| `onSaveFilter` | `actionLabel.text = "✓ saved filter \"${req.name}\""` + `actionLabel.style = "...#16a085..."` | `action("✓ saved filter \"${req.name}\"", Status.OK)` |
| `openImportDialog` (busy) | `text = "Importing…"` + `style = "...#2c3e50"` | `action("Importing…", Status.INFO)` |
| `openImportDialog` setOnSucceeded | text + conditional style | `action("✓ sent ${o.sent} · skipped ${o.skipped} · failed ${o.failed} → ${req.topic}", if (o.failed == 0L) Status.OK else Status.ERR)` |
| `openImportDialog` setOnFailed | text + `#c0392b` | `action("✗ import failed: ${task.exception?.message ?: task.exception?.javaClass?.simpleName}", Status.ERR)` |
| `openExportDialog` (busy) | `text = "Exporting…"` + `#2c3e50` | `action("Exporting…", Status.INFO)` |
| `openExportDialog` setOnSucceeded | text + `#16a085` | `action("✓ exported ${task.value} rows → ${file.name}", Status.OK)` |
| `openExportDialog` setOnFailed | text + `#c0392b` | `action("✗ export failed: ${task.exception?.message ?: task.exception?.javaClass?.simpleName}", Status.ERR)` |
| `sendOne` setOnSucceeded | text + `#16a085` | `action("✓ sent → ${rm.topic}:${rm.partition}@${rm.offset}", Status.OK)` |
| `sendOne` setOnFailed | text + `#c0392b` | `action("✗ send failed: ${task.exception.message ?: task.exception.javaClass.simpleName}", Status.ERR)` |
| `runAdmin` (busy) | `actionLabel.text = busyMsg` + `#2c3e50` | `action(busyMsg, Status.INFO)` |
| `runAdmin` setOnSucceeded | `actionLabel.text = okMsg()` + `#16a085` | `action(okMsg(), Status.OK)` |
| `runAdmin` setOnFailed | text + `#c0392b` | `action("✗ ${t?.message ?: t?.javaClass?.simpleName}", Status.ERR)` |

After this, **no `actionLabel.style =` assignment remains** in MainView.

- [ ] **Step 5: Add island classes + register the scene in `show()`**

In `MainView.kt` `show()`, replace the layout/scene block (lines ~143–170) so each region is an island and the scene is themed:
```kotlin
        val topicToolbar = HBox(6.0, newTopicBtn, groupsBtn, refreshTopicsBtn).apply {
            padding = Insets(6.0, 6.0, 6.0, 6.0)
        }
        val left = VBox(topicToolbar, topicList).apply {
            VBox.setVgrow(topicList, Priority.ALWAYS)
            styleClass.add("island")
        }
        val headerRow = HBox(8.0, topicHeader, statsLabel, prevPageBtn, pageLabel, nextPageBtn, sendButton, importButton, exportButton, actionLabel)
        val tableArea = BorderPane().apply {
            top = headerRow
            center = messageTable
            styleClass.add("island")
        }
        detailPane.styleClass.add("island")
        filterBuilder.styleClass.add("island")
        val rightSplit = SplitPane(tableArea, detailPane).apply {
            orientation = javafx.geometry.Orientation.VERTICAL
            setDividerPositions(0.55)
            styleClass.add("island-split")
        }
        val right = BorderPane().apply {
            center = rightSplit
            bottom = filterBuilder
        }
        val split = SplitPane(left, right).apply {
            setDividerPositions(0.20)
            styleClass.add("island-split")
        }

        val actions = AppActions(
            onNewTopic = { onCreateTopic() },
            onImport = { openImportDialog() },
            onExport = { openExportDialog() },
            onRefreshTopics = { refreshTopicList() },
            onManageConnections = { openConnectionManager() },
            onConsumerGroups = { openConsumerGroups() },
            onSendMessage = { openProducerDialog(null) },
            onOpenSettings = { com.kdt.ui.common.SettingsDialog().showAndWait() },
        )
        connectionForm.styleClass.add("island")
        val root = BorderPane().apply {
            top = VBox(AppTopBar(actions), connectionForm)
            center = split
            styleClass.add("app-root")
        }

        stage.title = "Kafka Desktop"
        val scene = Scene(root, 1300.0, 850.0)
        com.kdt.ui.common.theme.ThemeManager.register(scene)
        stage.scene = scene
        stage.setOnCloseRequest { tearDown() }
        stage.show()

        startWriter()
        refreshTimer.start()
```

- [ ] **Step 6: Refactor ConnectionForm status styling**

In `ConnectionForm.kt`, replace `setStatus` (lines ~68–71):
```kotlin
    fun setStatus(text: String, error: Boolean = false) {
        statusLabel.text = text
        statusLabel.styleClass.setAll("status-label", if (error) "status-err" else "status-info")
    }
```

- [ ] **Step 7: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL. (If `AppTopBar` is unresolved, it's created in Task 7 — do Task 7 before running, or temporarily comment the `AppTopBar(actions)` line. Recommended: implement Task 7 next, then compile both together.)

- [ ] **Step 8: Commit** (after Task 7 compiles — see note)

```bash
git add app/src/main/kotlin/com/kdt/app/KafkaDesktopApp.kt \
        app/src/main/kotlin/com/kdt/app/MainView.kt \
        ui-common/src/main/kotlin/com/kdt/ui/common/ConnectionForm.kt
git commit -m "feat(iter-14): theme MainView scene + island layout + status style classes"
```

---

## Task 7: AppTopBar (menu bar + toolbar) + run check

**Files:**
- Create: `app/src/main/kotlin/com/kdt/app/AppTopBar.kt`

- [ ] **Step 1: Write AppActions + AppTopBar**

`AppTopBar.kt`:
```kotlin
package com.kdt.app

import com.kdt.ui.common.theme.ThemeManager
import com.kdt.ui.common.theme.ThemeMode
import com.kdt.ui.common.theme.applyTheme
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle

/** Callbacks the top bar routes to. All are safe to call when disconnected (handlers no-op). */
class AppActions(
    val onNewTopic: () -> Unit,
    val onImport: () -> Unit,
    val onExport: () -> Unit,
    val onRefreshTopics: () -> Unit,
    val onManageConnections: () -> Unit,
    val onConsumerGroups: () -> Unit,
    val onSendMessage: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/** App menu bar (File/View/Tools/Help) + a toolbar row with an accent indicator and ⚙ Settings. */
class AppTopBar(actions: AppActions) : VBox() {

    private val accentDot = Circle(6.0)
    private val accentLabel = Label()

    init {
        styleClass.add("app-topbar")

        val logo = Label("K").apply { styleClass.add("app-logo") }
        val name = Label("Kafka Desktop").apply { styleClass.add("app-name") }
        val menuBar = buildMenuBar(actions)

        updateAccentChip(ThemeManager.accentHex)
        val accentChip = HBox(6.0, accentDot, accentLabel).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("accent-chip")
            setOnMouseClicked { actions.onOpenSettings() }
        }
        val gear = Button("⚙").apply {
            styleClass.add("gear-button")
            setOnAction { actions.onOpenSettings() }
        }
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }

        val row = HBox(6.0, logo, name, menuBar, spacer, accentChip, gear).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("topbar-row")
        }
        children.add(row)

        ThemeManager.addListener { _, hex -> updateAccentChip(hex) }
    }

    private fun updateAccentChip(hex: String) {
        accentDot.fill = Color.web(hex)
        accentLabel.text = hex
    }

    private fun buildMenuBar(a: AppActions): MenuBar {
        val file = Menu("File", null,
            item("New Topic…", a.onNewTopic),
            item("Import…", a.onImport),
            item("Export…", a.onExport),
            SeparatorMenuItem(),
            item("Exit") { Platform.exit() },
        )
        val themeMenu = Menu("Theme", null,
            item("Light") { ThemeManager.setMode(ThemeMode.LIGHT) },
            item("Dark") { ThemeManager.setMode(ThemeMode.DARK) },
        )
        val view = Menu("View", null,
            item("Refresh Topics", a.onRefreshTopics),
            SeparatorMenuItem(),
            themeMenu,
        )
        val tools = Menu("Tools", null,
            item("Manage Connections…", a.onManageConnections),
            item("Consumer Groups…", a.onConsumerGroups),
            item("Send Message…", a.onSendMessage),
            SeparatorMenuItem(),
            item("Settings…", a.onOpenSettings),
        )
        val help = Menu("Help", null,
            item("About") { showAbout() },
            item("Open GitHub repo") { openRepo() },
        )
        return MenuBar(file, view, tools, help).apply {
            isUseSystemMenuBar = false   // keep in-window to preserve the Islands look
            HBox.setHgrow(this, Priority.NEVER)
        }
    }

    private fun item(text: String, action: () -> Unit) = MenuItem(text).apply { setOnAction { action() } }

    private fun showAbout() {
        Alert(Alert.AlertType.INFORMATION).apply {
            title = "About"
            headerText = "Kafka Desktop"
            contentText = "A self-built Kafka desktop tool.\nIslands theme (iter-14)."
            applyTheme()
        }.showAndWait()
    }

    private fun openRepo() {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI("https://github.com/NineSu/Kafka-Desktop"))
        } catch (_: Exception) { /* best-effort */ }
    }
}
```

- [ ] **Step 2: Compile the whole app**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL (resolves the `AppTopBar(actions)` reference from Task 6).

- [ ] **Step 3: Commit (Task 6 + 7 together)**

```bash
git add app/src/main/kotlin/com/kdt/app/AppTopBar.kt \
        app/src/main/kotlin/com/kdt/app/KafkaDesktopApp.kt \
        app/src/main/kotlin/com/kdt/app/MainView.kt \
        ui-common/src/main/kotlin/com/kdt/ui/common/ConnectionForm.kt
git commit -m "feat(iter-14): AppTopBar (menu bar + toolbar) wired to actions + Settings"
```

- [ ] **Step 4: Manual run check (Light + Dark + accent)**

Run: `./gradlew :app:run`
Verify:
- Main window shows islands (rounded white cards on gray sea) with the top bar (logo, File/View/Tools/Help, accent chip, ⚙).
- Tools → Settings… (or ⚙) opens the Settings dialog. Switch Light/Dark — whole window + dialog reskin instantly. Click each accent preset and the ColorPicker — primary button / selection / chip / link recolor live.
- View → Theme → Light/Dark toggles too; the accent chip in the toolbar tracks the color.
- Close & relaunch: the last theme + accent persist (`~/.kafka-desktop/appearance.json`).
- Note any visual rough edges (contrast, spacing) and tune `islands.css`; re-run.

---

## Task 8: Theme all remaining dialogs

Add one `applyTheme()` call per dialog so popups inherit the theme (they create their own `DialogPane`, which doesn't auto-pick-up the scene stylesheet). For each `Dialog<T>` subclass, add the call at the **end of its `init {}` block**, after `dialogPane.content`/`buttonTypes` are set.

Import in each file: `import com.kdt.ui.common.theme.applyTheme`

**Files + call site:**
- `app/.../ConfirmNameDialog.kt` — end of `init`: `applyTheme()`
- `app/.../CreateTopicDialog.kt` — end of `init`: `applyTheme()`
- `app/.../ImportDialog.kt` — end of `init`: `applyTheme()`
- `app/.../ResetOffsetDialog.kt` — end of `init`: `applyTheme()`
- `app/.../SaveFilterDialog.kt` — end of `init`: `applyTheme()`
- `app/.../TopicDetailDialog.kt` — **two** classes (`TopicDetailDialog`, `AddPartitionsDialog`): `applyTheme()` at end of each `init`
- `app/.../ConsumerGroupDialog.kt` — `applyTheme()` at end of `init`; also the two inline `Alert(...)` (lines ~119, ~127): add `.apply { applyTheme() }` before `.showAndWait()`
- `ui-common/.../AuthDialog.kt` — end of `init`: `applyTheme()`
- `ui-common/.../ConnectionManagerDialog.kt` — **two** classes (`ConnectionManagerDialog`, private `ConnectionEditDialog`): `applyTheme()` at end of each `init`
- `ui-common/.../ExportDialog.kt` — end of `init`: `applyTheme()`
- `ui-common/.../ProducerDialog.kt` — end of `init`: `applyTheme()`; also change `valueArea` (line ~48–52) to use the `.mono` class instead of the inline style:
  ```kotlin
  private val valueArea = TextArea(initialValue.orEmpty()).apply {
      prefRowCount = 8
      prefWidth = 480.0
      styleClass.add("mono")
  }
  ```
- `ui-common/.../StartFromPicker.kt` — end of `init`: `applyTheme()`

Example (`ProducerDialog.kt`, end of `init` after `setResultConverter { … }`):
```kotlin
        applyTheme()
    }
```

Example for the inline Alerts in `ConsumerGroupDialog.kt`:
```kotlin
            Alert(Alert.AlertType.WARNING, /* …existing args… */).apply { applyTheme() }.showAndWait()
```

- [ ] **Step 1:** Add the import + `applyTheme()` call to each dialog listed above (and the `ProducerDialog` `.mono` change + the two Alert `.apply { applyTheme() }`).

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin` (or `./gradlew :app:compileKotlin :ui-common:compileKotlin`)
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual run check (dialogs)**

Run: `./gradlew :app:run`
Open each dialog (Manage Connections…, Send message…, a topic's Start-from picker, Create Topic, Export, Consumer Groups + its Alerts, Describe topic, Save filter) and confirm it renders themed (islands look, accent, current Light/Dark) — including after switching to Dark.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/kdt/app/*.kt ui-common/src/main/kotlin/com/kdt/ui/common/*.kt
git commit -m "feat(iter-14): apply theme to all dialogs + ProducerDialog .mono class"
```

---

## Task 9: Full build, README, finalize

**Files:**
- Modify: `README.md` (add a short Appearance/Theming note + a screenshot placeholder if the repo uses screenshots)

- [ ] **Step 1: Full build + all tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; new tests (AppSettingsStore, ThemeColors, IslandsCss, ThemeManager) green; existing tests still pass.

- [ ] **Step 2: Add a README note**

Add a short subsection under the features/usage area of `README.md`:
```markdown
### Appearance

Kafka Desktop ships an **Islands** look in **Light** and **Dark**. Open **Tools → Settings… → Appearance** (or the ⚙ in the toolbar) to switch theme and pick an **accent color** — 7 presets plus a custom color picker. Your choice is saved to `~/.kafka-desktop/appearance.json` and restored on next launch.
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs(iter-14): README — Appearance/theming note"
```

- [ ] **Step 4: Push the branch**

```bash
git push -u origin iter-14-islands-theme
```
(Open a PR or merge per your branch workflow. Per repo convention, release tags `v*` are cut separately — not part of this iteration.)

---

## Self-Review (performed against the spec)

**1. Spec coverage:**
- 主题机制 (looked-up colors, .theme-light/dark, inline -accent, island+gap) → Task 3 (CSS) + Task 4 (ThemeManager) + Task 6 (island classes, island-split).  ✓
- 新增组件 ThemeManager / AccentPreset / SettingsDialog / AppTopBar / AppSettingsStore+AppSettings → Tasks 4 / 2 / 5 / 7 / 1.  ✓ (AccentPreset replaces the spec's "AccentPresets" name.)
- 配色 (Light/Dark hex + 7 presets + radii) → Task 3 CSS + Task 2 AccentPreset.  ✓
- 菜单栏+Toolbar (File/View/Tools/Help, no system menu bar, routes to existing actions, View→Theme toggle, ⚙) → Task 7.  ✓
- Settings→Appearance (theme toggle, presets, ColorPicker, hex, preview, Cancel/Apply/OK semantics) → Task 5.  ✓
- 改动已有代码 (~25 inline styles→classes, ConnectionForm, dialogs register, startup, build files unchanged) → Tasks 6 + 8. ✓ (Koin DI not used for ThemeManager — spec said "沿用现有 DI 模式", but the codebase news-up stores directly and ui-common can't depend on core-storage; startup wiring in `KafkaDesktopApp` matches the actual pattern better. Documented in Task 6.)
- 持久化&启动 (appearance.json, defaultPath, fallback, seed→show) → Task 1 + Task 6 Step 1.  ✓
- 错误处理 (missing/corrupt→default, invalid hex ignored, live preview + Cancel revert, new dialogs inherit) → Task 1 tests, Task 2/4 (hex), Task 5 (Cancel), Task 8.  ✓
- 测试 (AppSettingsStore, ThemeManager, hex; no TestFX; manual CSS) → Tasks 1/2/3/4 + manual run checks.  ✓
- 范围边界 (only Appearance functional; no follow-OS/fonts) → Task 5 nav placeholders disabled; not implemented elsewhere.  ✓

**2. Placeholder scan:** No TBD/TODO; all code blocks are complete; the only "implement later" is the explicit cross-task note that `AppTopBar` (Task 7) resolves the reference added in Task 6 — both commit together.

**3. Type consistency:** `ThemeManager.seed/setMode/setAccent/register/addListener/mode/accentHex`, `ThemeMode.LIGHT/DARK/.storageKey/.styleClass/fromStorage`, `AccentPreset.entries/.hex/.displayName/.DEFAULT`, `ThemeColors.isValidHex/normalizeHex/toHex`, `Dialog<*>.applyTheme()`, `AppActions(onNewTopic,…,onOpenSettings)`, `AppSettings(themeMode, accentHex)`, MainView `Status`/`action()` — all consistent across tasks.
