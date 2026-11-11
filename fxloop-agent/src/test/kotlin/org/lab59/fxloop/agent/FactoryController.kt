package org.lab59.fxloop.agent

import javafx.fxml.FXML
import javafx.scene.control.Label

/** FXML controller without a no-arg constructor: only a controller factory can build it (see `factory.fxml`). */
class FactoryController(val store: String) {
    @FXML
    lateinit var greeting: Label
}
