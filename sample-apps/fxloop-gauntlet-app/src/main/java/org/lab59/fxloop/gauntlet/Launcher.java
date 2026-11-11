package org.lab59.fxloop.gauntlet;

import javafx.application.Application;

/** Plain main class (no Application superclass) so the app runs from a classpath. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        Application.launch(GauntletApp.class, args);
    }
}
