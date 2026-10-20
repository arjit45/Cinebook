package com.cinebook.ui;

import javafx.scene.text.Font;

import java.io.InputStream;

/**
 * Loads the bundled Archivo faces (docs/PRD.md §8) so the console looks the same on
 * the lab machine as it does here.
 *
 * <p>Two static faces, not the variable original: JavaFX picks a face by family and
 * weight, and the variable file registered as a family called "Archivo SemiBold",
 * which left {@code -fx-font-family: "Archivo"} matching nothing and silently falling
 * back to the system sans. The statics are cut from it as described in
 * {@code resources/fonts/README.md}.
 *
 * <p>If a face fails to load, JavaFX falls back to the platform sans and nothing
 * renders blank — the behaviour the PRD asks for when a font is missing.
 */
public final class Fonts {

    private static final String[] FACES = {
            "/fonts/Archivo-Regular.ttf",
            "/fonts/Archivo-SemiBold.ttf"
    };

    private static boolean loaded;

    private Fonts() {
    }

    public static void load() {
        if (loaded) {
            return;
        }
        boolean any = false;
        for (String face : FACES) {
            try (InputStream in = Fonts.class.getResourceAsStream(face)) {
                if (in != null && Font.loadFont(in, 14) != null) {
                    any = true;
                }
            } catch (Exception unavailable) {
                // Fall through: the platform sans is a usable answer.
            }
        }
        loaded = any;
    }
}
