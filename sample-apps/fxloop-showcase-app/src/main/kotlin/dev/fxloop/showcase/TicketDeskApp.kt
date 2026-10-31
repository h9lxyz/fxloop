package dev.fxloop.showcase

import java.util.Locale
import java.util.ResourceBundle
import java.util.logging.Logger
import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.geometry.NodeOrientation
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage

operator fun ResourceBundle.get(key: String): String = getString(key)

class TicketDeskApp : Application() {

    private val log = Logger.getLogger("dev.fxloop.showcase")

    override fun start(stage: Stage) {
        val args = (parameters?.raw ?: emptyList()) +
            System.getProperty("fxloop.showcase.args", "").split(Regex("\\s+")).filter { it.isNotBlank() }

        val locale = if ("--de" in args) Locale.GERMAN else Locale.ENGLISH
        val bundle = ResourceBundle.getBundle(
            "dev.fxloop.showcase.messages", locale,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
        )

        val store = TicketStore()
        if ("--empty" !in args) store.seed()

        val loader = FXMLLoader(TicketDeskApp::class.java.getResource("main-view.fxml"), bundle)
        loader.setControllerFactory { MainController(store) }
        val root: Parent = loader.load()
        if ("--rtl" in args) root.nodeOrientation = NodeOrientation.RIGHT_TO_LEFT

        val scene = Scene(root, 1100.0, 720.0)
        scene.stylesheets += TicketDeskApp::class.java.getResource("app.css")!!.toExternalForm()
        stage.title = bundle["app.title"]
        stage.scene = scene
        stage.show()
        log.info("Ticket Desk started with ${store.tickets.size} tickets (locale=$locale)")
    }
}
