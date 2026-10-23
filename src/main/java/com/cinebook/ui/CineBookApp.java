package com.cinebook.ui;

import com.cinebook.dao.StaffDao;
import com.cinebook.model.StaffUser;
import com.cinebook.service.AuthService;
import com.cinebook.util.Db;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.Objects;

/**
 * The window. Owns no rules: it shows the sign-in screen, swaps in the shell once
 * someone is in, and swaps back on sign out.
 *
 * <p>The database is not opened at start-up — {@link Db} connects on the first
 * borrow — so a stopped MySQL shows up as a message on the sign-in screen instead
 * of a window that never appears.
 */
public final class CineBookApp extends Application {

    private static final double DEFAULT_WIDTH = 1280;
    private static final double DEFAULT_HEIGHT = 780;

    private Scene scene;
    private AuthService auth;
    private com.cinebook.service.HoldExpiryDaemon holdExpiryDaemon;

    @Override
    public void start(Stage stage) {
        Fonts.load();
        try {
            auth = new AuthService(new StaffDao(Db.get()));
        } catch (IllegalStateException misconfigured) {
            showConfigurationProblem(stage, misconfigured.getMessage());
            return;
        }

        holdExpiryDaemon = new com.cinebook.service.HoldExpiryDaemon(
                new com.cinebook.service.BookingService(Db.get()));
        holdExpiryDaemon.start();

        scene = new Scene(loginView(), DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/app.css")).toExternalForm());

        stage.setTitle("CineBook — cinema ticketing");
        stage.setMinWidth(DEFAULT_WIDTH);
        stage.setMinHeight(720);
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        // §11.7: the workers and sweeper are daemons and are also shut down explicitly,
        // so no thread and no MySQL session outlives the window.
        if (holdExpiryDaemon != null) {
            holdExpiryDaemon.stop();
        }
        FxTasks.shutdown();
        Db.get().close();
    }

    private Parent loginView() {
        return new LoginView(auth, this::signedIn).root();
    }

    private void signedIn(StaffUser user) {
        scene.setRoot(new ShellView(auth, user, this::signedOut).root());
    }

    private void signedOut() {
        auth.logout();
        scene.setRoot(loginView());
    }

    /** Shown instead of a stack trace when {@code app.properties} is missing or blank. */
    private void showConfigurationProblem(Stage stage, String detail) {
        Label wordmark = new Label("CineBook");
        wordmark.getStyleClass().add("wordmark");
        Label title = new Label("The app is not configured");
        title.getStyleClass().add("section-title");
        Label message = new Label(detail);
        message.setWrapText(true);
        message.setMaxWidth(560);
        Label hint = new Label("Copy app.properties.example to app.properties in the "
                + "project root, fill in jdbc.password, and run again.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setMaxWidth(560);

        VBox box = new VBox(16, wordmark, title, message, hint);
        box.setPadding(new Insets(60, 40, 40, 40));

        Scene problemScene = new Scene(box, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        var css = getClass().getResource("/app.css");
        if (css != null) {
            problemScene.getStylesheets().add(css.toExternalForm());
        }
        stage.setTitle("CineBook — configuration");
        stage.setScene(problemScene);
        stage.show();
    }
}
