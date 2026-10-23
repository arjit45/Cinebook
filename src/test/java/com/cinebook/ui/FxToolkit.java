package com.cinebook.ui;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One JavaFX toolkit for the whole UI test run.
 *
 * <p>{@link Platform#startup} may only be called once per JVM, and
 * {@link Platform#exit} is terminal: two test classes that each start and stop the
 * toolkit fail depending on the order Surefire picks. Every UI class calls
 * {@link #start()} instead; the toolkit stays up until the forked JVM exits.
 * Surefire ends that JVM with {@code System.exit}, so no test class needs to close
 * the toolkit and no shutdown hook is needed (a hook that called
 * {@link Platform#exit} while the FX thread was idle delayed the fork by 30 s).
 */
final class FxToolkit {

    private static boolean started;

    private FxToolkit() {
    }

    static synchronized void start() throws Exception {
        if (started) {
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            // The tests decide when the last window closes, not the toolkit.
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        if (!ready.await(30, TimeUnit.SECONDS)) {
            throw new AssertionError("the JavaFX toolkit did not start");
        }
        started = true;
    }
}
