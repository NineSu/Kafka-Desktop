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
