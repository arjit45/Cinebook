package com.cinebook.ui;

import javafx.application.Application;

/**
 * The lab-machine entry point.
 *
 * <p>This class deliberately does <em>not</em> extend {@link Application}. Launching
 * through a plain main is what avoids "JavaFX runtime components are missing" when
 * a shaded jar is started from {@code run.bat} with no module path
 * (docs/PRD.md §13.4).
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(CineBookApp.class, args);
    }
}
