package com.cinebook.ui;

import com.cinebook.dao.MovieDao;
import com.cinebook.dao.ShowtimeDao;
import com.cinebook.dao.StaffDao;
import com.cinebook.model.Movie;
import com.cinebook.model.Screen;
import com.cinebook.model.Showtime;
import com.cinebook.service.AuthService;
import com.cinebook.util.Db;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Programme screen driven on the FX thread: the catalogue and the schedule
 * arrive from the services, the film form opens only when asked, and a slot the
 * database has already taken surfaces as the sentence a clerk can act on rather
 * than a stack trace.
 *
 * <p>Reads are asynchronous ({@link FxTasks}), so each test constructs the view,
 * attaches it to a scene, then waits from the test thread with {@link #awaitFx}.
 * Waiting inside {@code runOnFx} would block the queue the completion runs on.
 */
@Tag("ui")
class ProgrammeViewTest {

    private static final Db DB = Db.get();
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.start();
    }

    @AfterAll
    static void stopToolkit() {
        DB.close();
    }

    @Test
    void theCatalogueAndTheUpcomingScheduleAreBothDrawn() throws Exception {
        Parent root = openProgramme();

        int filmRows = runOnFx(() -> root.lookupAll(".programme-film").size());
        int showRows = runOnFx(() -> root.lookupAll(".programme-show").size());
        int panels = runOnFx(() -> root.lookupAll(".programme-panel").size());

        assertEquals(2, panels, "one page for the catalogue, one for the screenings");
        assertEquals(new MovieDao(DB).listAll().size(), filmRows,
                "every film in the catalogue must have a row");
        assertEquals(new ShowtimeDao(DB).programme(400).size(), showRows,
                "the day book must list every upcoming screening, not just the first day");
        assertNotNull(runOnFx(() -> root.lookup("#new-film")));
        assertFalse(runOnFx(() -> root.lookup("#film-form").isVisible()),
                "the film form stays out of the way until New film is pressed");
    }

    @Test
    void newFilmOpensTheFormAndCancelClosesIt() throws Exception {
        Parent root = openProgramme();

        runOnFx(() -> {
            ((Button) root.lookup("#new-film")).fire();
            return null;
        });
        assertTrue(runOnFx(() -> root.lookup("#film-form").isVisible()),
                "New film must reveal the form with its title field");
        assertTrue(runOnFx(() -> ((TextField) root.lookup("#film-title")).getText().isEmpty()),
                "a new form starts blank");

        runOnFx(() -> {
            Button cancel = (Button) root.lookupAll(".button").stream()
                    .filter(node -> node instanceof Button button && "Cancel".equals(button.getText()))
                    .findFirst().orElseThrow();
            cancel.fire();
            return null;
        });
        assertFalse(runOnFx(() -> root.lookup("#film-form").isVisible()),
                "Cancel must fold the form away again");
    }

    @Test
    void takingATakenSlotShowsTheClashSentenceAndAddsNothing() throws Exception {
        Parent root = openProgramme();
        Showtime existing = new ShowtimeDao(DB).programme(1).get(0);
        int before = new ShowtimeDao(DB).programme(400).size();

        runOnFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<Movie> filmBox = (ComboBox<Movie>) root.lookup("#show-film");
            for (Movie movie : filmBox.getItems()) {
                if (movie.movieId() == existing.movie().movieId()) {
                    filmBox.setValue(movie);
                }
            }
            @SuppressWarnings("unchecked")
            ComboBox<Screen> screenBox = (ComboBox<Screen>) root.lookup("#show-screen");
            for (Screen screen : screenBox.getItems()) {
                if (screen.screenId() == existing.screen().screenId()) {
                    screenBox.setValue(screen);
                }
            }
            ((DatePicker) root.lookup("#show-date")).setValue(existing.showDate());
            ((TextField) root.lookup("#show-time")).setText(TIME.format(existing.startTime()));
            ((TextField) root.lookup("#show-price")).setText("200.00");
            ((Button) root.lookup("#show-add")).fire();
            return null;
        });

        awaitFx(() -> visibleErrors(root).stream()
                .anyMatch(text -> text.contains("already has a show")));

        String message = runOnFx(() -> visibleErrors(root).get(0));
        assertTrue(message.contains(existing.screen().name()),
                "the refusal must name the auditorium: " + message);
        assertTrue(message.contains(TIME.format(existing.startTime())),
                "the refusal must name the clash time: " + message);
        assertEquals(before, new ShowtimeDao(DB).programme(400).size(),
                "a refused slot must not add a screening");
        assertTrue(runOnFx(() -> !((Button) root.lookup("#show-add")).isDisabled()),
                "the add button must come back after the refusal");
    }

    private static Parent openProgramme() {
        Parent[] root = new Parent[1];
        runOnFx(() -> {
            AuthService auth = new AuthService(new StaffDao(DB));
            auth.login("admin", "admin123");
            ProgrammeView view = new ProgrammeView(auth);
            root[0] = (Parent) view.root();
            attach(root[0]);
            return null;
        });
        awaitFx(() -> !root[0].lookupAll(".programme-film").isEmpty()
                && !root[0].lookupAll(".programme-show").isEmpty());
        return root[0];
    }

    private static java.util.List<String> visibleErrors(Parent root) {
        return root.lookupAll(".error").stream()
                .filter(Node::isVisible)
                .map(node -> ((Label) node).getText())
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }

    private static void attach(Parent root) {
        Scene scene = new Scene(root, 1280, 780);
        scene.getStylesheets().add(
                java.util.Objects.requireNonNull(
                        ProgrammeViewTest.class.getResource("/app.css")).toExternalForm());
        root.applyCss();
        root.layout();
    }

    private static void awaitFx(java.util.function.Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            if (Boolean.TRUE.equals(runOnFx(condition::get))) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the FX thread never reached the awaited state");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the FX thread", interrupted);
            }
        }
    }

    private static <T> T runOnFx(Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("the FX thread did not finish in 30s");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the FX thread", interrupted);
        }
        if (failure.get() != null) {
            if (failure.get() instanceof Error error) {
                throw error;
            }
            if (failure.get() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(failure.get());
        }
        return result.get();
    }
}
