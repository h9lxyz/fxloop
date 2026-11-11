package org.lab59.fxloop.showcase

import java.util.ResourceBundle
import java.util.logging.Logger
import java.util.prefs.Preferences
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.stage.Stage

object SettingsWindow {
    fun open(bundle: ResourceBundle) {
        val loader = FXMLLoader(SettingsWindow::class.java.getResource("settings.fxml"), bundle)
        val root: Parent = loader.load()
        val stage = Stage()
        stage.title = bundle["settings.title"]
        stage.scene = Scene(root, 420.0, 360.0).apply {
            stylesheets += SettingsWindow::class.java.getResource("settings.css")!!.toExternalForm()
        }
        stage.x = 40.0
        stage.y = 40.0
        stage.show()
    }
}

class SettingsController {

    private val log = Logger.getLogger("org.lab59.fxloop.showcase.SettingsController")
    private val prefs = Preferences.userNodeForPackage(SettingsController::class.java)

    @FXML lateinit var resources: ResourceBundle
    @FXML lateinit var darkModeCheck: CheckBox
    @FXML lateinit var notificationsCheck: CheckBox
    @FXML lateinit var languageCombo: ComboBox<String>
    @FXML lateinit var signatureField: TextField
    @FXML lateinit var hintLabel: Label
    @FXML lateinit var applyBtn: Button
    @FXML lateinit var okBtn: Button

    @FXML
    fun initialize() {
        languageCombo.items.setAll("English", "Deutsch")
        languageCombo.value = if (resources.locale.language == "de") "Deutsch" else "English"
        darkModeCheck.isSelected = prefs.getBoolean("darkMode", false)
        notificationsCheck.isSelected = true
        signatureField.text = "— Alex, Ticket Desk Support"
    }

    @FXML
    fun onOk() {
        prefs.putBoolean("darkMode", darkModeCheck.isSelected)
        log.info("Settings saved (darkMode=${darkModeCheck.isSelected})")
        okBtn.scene.window.hide()
    }

    @FXML
    fun onCancel() {
        okBtn.scene.window.hide()
    }
}
