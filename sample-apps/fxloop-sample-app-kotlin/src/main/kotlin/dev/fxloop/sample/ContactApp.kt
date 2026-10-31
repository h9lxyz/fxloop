package dev.fxloop.sample

import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage

class ContactApp : Application() {

    override fun start(stage: Stage) {
        val args = (parameters?.raw.orEmpty()).toMutableList()
        val extra = System.getProperty("fxloop.sample.args", "").trim()
        if (extra.isNotEmpty()) args += extra.split(Regex("\\s+"))

        val loader = FXMLLoader(ContactApp::class.java.getResource("main-view.fxml"))
        val root = loader.load<Parent>()
        val controller = loader.getController<MainController>()
        if ("--seed" in args) controller.seed()

        val scene = Scene(root, 640.0, 480.0)
        scene.stylesheets += ContactApp::class.java.getResource("app.css")!!.toExternalForm()
        if ("--broken-css" in args) scene.stylesheets += ContactApp::class.java.getResource("broken.css")!!.toExternalForm()
        stage.title = "FXLoop Sample — Contacts"
        stage.scene = scene
        stage.show()
    }
}
