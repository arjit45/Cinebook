package com.cinebook.ui;

import com.cinebook.exception.AuthException;
import com.cinebook.exception.DbUnavailableException;
import com.cinebook.model.StaffUser;
import com.cinebook.service.AuthService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The first screen: a column of type on ticket stock, not a card.
 *
 * <p>Errors are written for the person at the counter, not for a log: a wrong
 * password says both fields are worth checking (so the screen cannot be used to
 * enumerate accounts), and a stopped MySQL says to start the service rather than
 * showing a stack trace (docs/PRD.md §13.3).
 */
public final class LoginView {

    private final AuthService auth;
    private final Consumer<StaffUser> onSignedIn;

    private final VBox root = new VBox();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label message = new Label();
    private final Button submit = new Button("Sign in");

    public LoginView(AuthService auth, Consumer<StaffUser> onSignedIn) {
        this.auth = auth;
        this.onSignedIn = onSignedIn;
        build();
    }

    public Parent root() {
        return root;
    }

    private void build() {
        Label wordmark = new Label("CineBook");
        wordmark.getStyleClass().add("wordmark");

        // The date on the header rule: ticket stock has a date on it, and it gives the
        // empty right-hand side one thing worth saying.
        Label today = new Label(LocalDate.now()
                .format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)));
        today.getStyleClass().add("muted");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox header = new HBox(wordmark, gap, today);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");

        Label title = new Label("Sign in");
        title.getStyleClass().add("display");
        Label subtitle = new Label("Box office console");
        subtitle.getStyleClass().add("muted");

        username.setPromptText("admin or operator");
        username.setMaxWidth(360);
        password.setPromptText("password");
        password.setMaxWidth(360);

        VBox usernameBlock = field("Username", username);
        VBox passwordBlock = field("Password", password);

        submit.setDefaultButton(true);
        submit.setOnAction(event -> submit());
        username.setOnAction(event -> password.requestFocus());
        password.setOnAction(event -> submit());

        message.getStyleClass().add("error");
        message.setWrapText(true);
        message.setMaxWidth(360);
        message.setMinHeight(Region.USE_PREF_SIZE);

        VBox form = new VBox(22, title, subtitle, usernameBlock, passwordBlock, submit, message);
        form.setPadding(new Insets(24, 40, 24, 40));
        form.setMaxWidth(460);

        // Equal spacers above and below centre the form between the wordmark rule and
        // the bottom edge, instead of leaving a void under the button.
        Region above = new Region();
        Region below = new Region();
        VBox.setVgrow(above, Priority.ALWAYS);
        VBox.setVgrow(below, Priority.ALWAYS);
        below.setMinHeight(40);

        root.getChildren().addAll(header, above, form, below);
    }

    private static VBox field(String label, javafx.scene.control.TextInputControl control) {
        Label caption = new Label(label);
        caption.getStyleClass().add("field-label");
        VBox block = new VBox(4, caption, control);
        block.setMaxWidth(360);
        return block;
    }

    private void submit() {
        message.setText("");
        submit.setDisable(true);
        try {
            StaffUser user = auth.login(username.getText(), password.getText());
            onSignedIn.accept(user);
        } catch (AuthException refused) {
            message.setText(refused.getMessage());
            password.clear();
            password.requestFocus();
        } catch (DbUnavailableException unavailable) {
            message.setText("MySQL is not running — start the service, then sign in again.");
        } catch (SQLException failure) {
            message.setText("The database could not be read: " + failure.getMessage());
        } finally {
            submit.setDisable(false);
        }
    }
}
