package com.cinebook.ui;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The one place the application leaves the FX thread (docs/PRD.md §11.7).
 *
 * <p>A blocking query on the FX thread freezes the window, and a frozen window
 * during a demo reads as a broken app. Every call that touches MySQL from a
 * control handler goes through {@link #run}: the work happens on a background
 * thread, and both outcomes come back to the FX thread through
 * {@link Platform#runLater}, so the callbacks may touch nodes directly.
 *
 * <p><b>Two threads, not one, and not more.</b> The connection pool is sized at
 * three because the design admits three consumers — two counter windows and the
 * 60-second hold-expiry job (§11.7). This pool is deliberately sized to the two
 * windows' worth of work: it lets a background hold overlap with a foreground
 * read inside one window, while leaving the third connection for the job. Two
 * threads here can never exhaust the three-connection handoff by themselves.
 *
 * <p><b>Daemon threads, and a shutdown.</b> A non-daemon worker keeps the JVM
 * alive after the last window closes: the window vanishes and {@code java.exe}
 * is still there in Task Manager. The factory sets {@code setDaemon(true)} and
 * {@code CineBookApp.stop()} calls {@link #shutdown()}, so the process ends with
 * the window.
 */
final class FxTasks {

    private static final int WORKER_THREADS = 2;

    private static final ExecutorService WORKER = Executors.newFixedThreadPool(WORKER_THREADS, task -> {
        Thread thread = new Thread(task, "cinebook-db-worker");
        thread.setDaemon(true);
        return thread;
    });

    private FxTasks() {
    }

    /**
     * Runs {@code work} off the FX thread and hands both outcomes back on it.
     *
     * <p>Exactly one of the two callbacks runs, and it always runs on the FX
     * thread, so neither has to guard its node access.
     *
     * @param work   the blocking unit — one borrowed connection's worth of work
     * @param done   runs on the FX thread with the result
     * @param failed runs on the FX thread with whatever {@code work} threw
     */
    static <T> void run(Callable<T> work, Consumer<T> done, Consumer<Throwable> failed) {
        WORKER.execute(() -> {
            T result;
            try {
                result = work.call();
            } catch (Throwable failure) {
                Platform.runLater(() -> failed.accept(failure));
                return;
            }
            Platform.runLater(() -> done.accept(result));
        });
    }

    /** Stops the workers. Called from {@code App.stop()}; safe to call twice. */
    static void shutdown() {
        WORKER.shutdownNow();
    }
}
