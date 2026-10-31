package dev.fxloop.sample

import javafx.application.Application

/** Plain entry point so the app can start from the classpath (no module-path check); compiles to `MainKt`. */
fun main(args: Array<String>) = Application.launch(ContactApp::class.java, *args)
