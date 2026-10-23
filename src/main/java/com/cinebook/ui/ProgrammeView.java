package com.cinebook.ui;

import com.cinebook.model.Movie;
import com.cinebook.model.Screen;
import com.cinebook.model.Showtime;
import com.cinebook.model.enums.Certificate;
import com.cinebook.service.AuthService;
import com.cinebook.service.ProgrammeService;
import com.cinebook.service.ProgrammeService.MovieDraft;
import com.cinebook.util.Db;
import com.cinebook.util.Money;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The Programme screen (docs/PRD.md §3 screen 6): a two-page repertory sheet.
 *
 * <p>The left page is the film catalogue and its edit form; the right page is the
 * schedule builder and the day book of screenings still to come. The two columns
 * are paper stock and rules — no cards inside cards, no shadows — so the page reads
 * like a programme rather than a dashboard.
 *
 * <p>Every write goes through {@link ProgrammeService}, which checks the admin role
 * before it reaches a query. The screen's own messages repeat the service sentence:
 * "Audi 2 already has a show at 19:00." is the clash, not a stack trace.
 */
public final class ProgrammeView {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter INPUT_TIME =
            DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_HEADER =
            DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_FIELD =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final ProgrammeService service;

    private final VBox root = new VBox(14);
    private final VBox filmList = new VBox();
    private final VBox showList = new VBox(3);
    private final Label pageError = new Label();
    private final Label filmMessage = new Label();
    private final Label showMessage = new Label();
    private final Label showCount = new Label();

    // Film form (add and edit share it).
    private final VBox filmForm = new VBox(10);
    private final Label filmFormTitle = new Label("Add a film");
    private final TextField filmTitle = new TextField();
    private final TextField filmGenre = new TextField();
    private final TextField filmLanguage = new TextField("English");
    private final TextField filmRuntime = new TextField();
    private final TextField filmRating = new TextField();
    private final DatePicker filmRelease = new DatePicker();
    private final ComboBox<Certificate> filmCertificate = new ComboBox<>();
    private final Button saveFilm = new Button("Save film");
    private final Button cancelFilm = new Button("Cancel");
    private final Button newFilm = new Button("New film");
    private Integer editingFilmId;

    // Screening builder.
    private final ComboBox<Movie> showMovie = new ComboBox<>();
    private final ComboBox<Screen> showScreen = new ComboBox<>();
    private final DatePicker showDate = new DatePicker(LocalDate.now().plusDays(1));
    private final TextField showTime = new TextField("19:00");
    private final TextField showPrice = new TextField("250.00");
    private final Button addShow = new Button("Add screening");

    public ProgrammeView(AuthService auth) {
        this.service = new ProgrammeService(Db.get(), auth);
        build();
        load();
    }

    public Node root() {
        return root;
    }

    private void build() {
        root.setPadding(new Insets(10, 0, 20, 0));

        pageError.getStyleClass().add("error");
        pageError.setWrapText(true);
        filmMessage.setWrapText(true);
        showMessage.setWrapText(true);
        hide(pageError);
        hide(filmMessage);
        hide(showMessage);

        buildFilmForm();

        Label filmHeading = new Label("Film catalogue");
        filmHeading.getStyleClass().add("programme-eyebrow");
        newFilm.getStyleClass().add("outline-button");
        newFilm.setId("new-film");
        newFilm.setOnAction(event -> beginNewFilm());
        Region filmGap = new Region();
        HBox.setHgrow(filmGap, Priority.ALWAYS);
        HBox filmHeader = new HBox(10, filmHeading, filmGap, newFilm);
        filmHeader.setAlignment(Pos.CENTER_LEFT);

        VBox catalogue = new VBox(12, filmHeader, filmForm, filmMessage, filmList);
        catalogue.getStyleClass().addAll("panel-card", "programme-panel");
        catalogue.setPadding(new Insets(18));
        catalogue.setPrefWidth(530);
        catalogue.setMinWidth(470);
        catalogue.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(catalogue, Priority.ALWAYS);

        Label showHeading = new Label("Screenings");
        showHeading.getStyleClass().add("programme-eyebrow");
        showCount.getStyleClass().add("muted");
        Region showGap = new Region();
        HBox.setHgrow(showGap, Priority.ALWAYS);
        HBox showHeader = new HBox(10, showHeading, showGap, showCount);
        showHeader.setAlignment(Pos.CENTER_LEFT);

        VBox builder = new VBox(12, showHeader, screeningForm(), showMessage, showList);
        builder.getStyleClass().addAll("panel-card", "programme-panel");
        builder.setPadding(new Insets(18));
        builder.setPrefWidth(380);
        builder.setMinWidth(340);
        builder.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(builder, Priority.ALWAYS);

        HBox columns = new HBox(20, catalogue, builder);
        columns.setAlignment(Pos.TOP_LEFT);
        root.getChildren().addAll(pageError, columns);
    }

    private void buildFilmForm() {
        filmForm.setId("film-form");
        filmForm.getStyleClass().add("programme-form");
        filmFormTitle.getStyleClass().add("programme-form-title");
        filmFormTitle.setWrapText(true);

        filmTitle.setId("film-title");
        filmTitle.setPromptText("Title");
        filmGenre.setId("film-genre");
        filmGenre.setPromptText("Genre");
        filmLanguage.setId("film-language");
        filmLanguage.setPromptText("Language");
        filmRuntime.setId("film-runtime");
        filmRuntime.setPromptText("Minutes");
        filmRating.setId("film-rating");
        filmRating.setPromptText("0–10");
        filmRelease.setId("film-release");
        filmRelease.setEditable(false);
        filmRelease.setConverter(dateConverter());
        filmRelease.setValue(LocalDate.now());
        filmRelease.setMaxWidth(Double.MAX_VALUE);
        filmCertificate.setId("film-certificate");
        filmCertificate.getItems().setAll(Certificate.values());
        filmCertificate.setValue(Certificate.UA);
        filmCertificate.setMaxWidth(Double.MAX_VALUE);

        HBox titleRow = new HBox(10, field("Title", filmTitle), field("Genre", filmGenre));
        HBox numbersRow = new HBox(10,
                field("Runtime (min)", filmRuntime),
                field("Rating (0–10)", filmRating),
                field("Certificate", filmCertificate));
        HBox releaseRow = new HBox(10, field("Language", filmLanguage), field("Release date", filmRelease));

        saveFilm.setId("film-save");
        saveFilm.setOnAction(event -> saveFilm());
        cancelFilm.getStyleClass().add("outline-button");
        cancelFilm.setOnAction(event -> hideFilmForm());
        HBox actions = new HBox(8, saveFilm, cancelFilm);
        actions.setAlignment(Pos.CENTER_LEFT);

        filmForm.getChildren().addAll(filmFormTitle, titleRow, numbersRow, releaseRow, actions);
        hide(filmForm);
    }

    private VBox screeningForm() {
        VBox form = new VBox(10);
        form.getStyleClass().add("programme-form");

        showMovie.setId("show-film");
        showMovie.setConverter(movieConverter());
        showMovie.setMaxWidth(Double.MAX_VALUE);
        showScreen.setId("show-screen");
        showScreen.setConverter(screenConverter());
        showScreen.setMaxWidth(Double.MAX_VALUE);
        showDate.setId("show-date");
        showDate.setEditable(false);
        showDate.setConverter(dateConverter());
        showDate.setMaxWidth(Double.MAX_VALUE);
        showTime.setId("show-time");
        showTime.setPromptText("19:00");
        showPrice.setId("show-price");
        showPrice.setPromptText("250.00");

        HBox dateRow = new HBox(10, field("Date", showDate), field("Time (HH:mm)", showTime));
        Label hint = new Label("One screening per auditorium at a time.");
        hint.getStyleClass().add("programme-hint");

        addShow.setId("show-add");
        addShow.setMaxWidth(Double.MAX_VALUE);
        addShow.setOnAction(event -> addScreening());

        form.getChildren().addAll(
                field("Film", showMovie),
                field("Auditorium", showScreen),
                dateRow,
                field("Base price (₹)", showPrice),
                hint,
                addShow);
        return form;
    }

    private static VBox field(String label, Control control) {
        Label caption = new Label(label);
        caption.getStyleClass().add("field-label");
        control.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(3, caption, control);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private void load() {
        newFilm.setDisable(true);
        addShow.setDisable(true);
        FxTasks.run(
                () -> new Load(service.catalogue(), service.screens(), service.screenings()),
                this::render,
                this::loadFailed);
    }

    private void render(Load load) {
        hide(pageError);
        renderFilms(load.films());
        renderShows(load.screenings());
        updateChoices(load.films(), load.screens());
        newFilm.setDisable(false);
    }

    private void loadFailed(Throwable failure) {
        pageError.setText("Could not load the programme: " + failure.getMessage());
        show(pageError);
    }

    private void updateChoices(List<Movie> films, List<Screen> screens) {
        Integer selectedFilm = showMovie.getValue() == null ? null : showMovie.getValue().movieId();
        Integer selectedScreen = showScreen.getValue() == null ? null : showScreen.getValue().screenId();

        showMovie.getItems().setAll(films);
        showScreen.getItems().setAll(screens);

        selectMovie(selectedFilm);
        selectScreen(selectedScreen);
        showMovie.setDisable(films.isEmpty());
        showScreen.setDisable(screens.isEmpty());
        addShow.setDisable(films.isEmpty() || screens.isEmpty());
    }

    private void selectMovie(Integer id) {
        for (Movie movie : showMovie.getItems()) {
            if (id != null && movie.movieId() == id) {
                showMovie.setValue(movie);
                return;
            }
        }
        if (!showMovie.getItems().isEmpty()) {
            showMovie.getSelectionModel().selectFirst();
        }
    }

    private void selectScreen(Integer id) {
        for (Screen screen : showScreen.getItems()) {
            if (id != null && screen.screenId() == id) {
                showScreen.setValue(screen);
                return;
            }
        }
        if (!showScreen.getItems().isEmpty()) {
            showScreen.getSelectionModel().selectFirst();
        }
    }

    private void renderFilms(List<Movie> films) {
        filmList.getChildren().clear();
        if (films.isEmpty()) {
            Label empty = new Label("No films yet. Add the first one.");
            empty.getStyleClass().addAll("muted", "programme-empty");
            filmList.getChildren().add(empty);
            return;
        }
        for (Movie film : films) {
            filmList.getChildren().add(filmRow(film));
        }
    }

    private VBox filmRow(Movie film) {
        VBox row = new VBox(3);
        row.getStyleClass().add("programme-film");

        Label title = new Label(film.title());
        title.getStyleClass().add("programme-film-title");
        StringBuilder meta = new StringBuilder()
                .append(film.genre()).append("  ·  ")
                .append(film.language()).append("  ·  ")
                .append(film.runtimeMin()).append(" min  ·  ")
                .append(film.certificate());
        if (film.imdbRating() != null) {
            meta.append("  ·  ").append(film.imdbRating().toPlainString()).append("/10");
        }
        Label details = new Label(meta.toString());
        details.getStyleClass().add("programme-film-meta");

        Button edit = new Button("Edit");
        edit.getStyleClass().add("link-button");
        edit.setOnAction(event -> beginEdit(film));
        Button delete = new Button("Delete");
        delete.getStyleClass().addAll("link-button", "danger-link");
        delete.setOnAction(event -> confirmDelete(film, row));
        HBox actions = new HBox(10, edit, delete);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox words = new VBox(2, title, details);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(10, words, gap, actions);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(words, Priority.ALWAYS);
        row.getChildren().add(top);
        return row;
    }

    private void confirmDelete(Movie film, VBox row) {
        VBox confirm = new VBox(8);
        confirm.getStyleClass().add("programme-confirm");
        Label question = new Label("Delete " + film.title() + "?");
        question.getStyleClass().add("programme-confirm-title");
        Button delete = new Button("Delete film");
        delete.getStyleClass().add("danger-button");
        Button keep = new Button("Keep film");
        keep.getStyleClass().add("outline-button");
        HBox actions = new HBox(8, delete, keep);
        confirm.getChildren().addAll(question, actions);

        keep.setOnAction(event -> row.getChildren().remove(confirm));
        delete.setOnAction(event -> {
            delete.setDisable(true);
            keep.setDisable(true);
            FxTasks.run(
                    () -> {
                        service.deleteMovie(film.movieId());
                        return null;
                    },
                    ignored -> {
                        row.getChildren().remove(confirm);
                        showFilmNote("Deleted " + film.title() + ".");
                        load();
                    },
                    failure -> {
                        row.getChildren().remove(confirm);
                        showFilmError(failure.getMessage());
                    });
        });
        row.getChildren().add(confirm);
    }

    private void beginNewFilm() {
        editingFilmId = null;
        filmFormTitle.setText("Add a film");
        saveFilm.setText("Save film");
        filmTitle.clear();
        filmGenre.clear();
        filmLanguage.setText("English");
        filmRuntime.clear();
        filmRating.clear();
        filmRelease.setValue(LocalDate.now());
        filmCertificate.setValue(Certificate.UA);
        clearFilmMessage();
        show(filmForm);
        filmTitle.requestFocus();
    }

    private void beginEdit(Movie film) {
        editingFilmId = film.movieId();
        filmFormTitle.setText("Edit " + film.title());
        saveFilm.setText("Save changes");
        filmTitle.setText(film.title());
        filmGenre.setText(film.genre());
        filmLanguage.setText(film.language());
        filmRuntime.setText(String.valueOf(film.runtimeMin()));
        filmRating.setText(film.imdbRating() == null ? "" : film.imdbRating().toPlainString());
        filmRelease.setValue(film.releaseDate());
        filmCertificate.setValue(film.certificate());
        clearFilmMessage();
        show(filmForm);
        filmTitle.requestFocus();
    }

    private void hideFilmForm() {
        editingFilmId = null;
        hide(filmForm);
        clearFilmMessage();
    }

    private void saveFilm() {
        int runtime;
        try {
            runtime = Integer.parseInt(filmRuntime.getText().trim());
        } catch (NumberFormatException notANumber) {
            showFilmError("Runtime must be a whole number of minutes.");
            return;
        }
        BigDecimal rating = null;
        String ratingText = filmRating.getText().trim();
        if (!ratingText.isEmpty()) {
            try {
                rating = new BigDecimal(ratingText);
            } catch (NumberFormatException notANumber) {
                showFilmError("Rating must be a number between 0 and 10.");
                return;
            }
        }

        MovieDraft draft = new MovieDraft(
                filmTitle.getText(), filmGenre.getText(), filmLanguage.getText(), runtime,
                filmRelease.getValue(), filmCertificate.getValue(), rating);
        Integer editing = editingFilmId;
        saveFilm.setDisable(true);
        FxTasks.run(
                () -> editing == null ? service.addMovie(draft) : service.updateMovie(editing, draft),
                saved -> {
                    saveFilm.setDisable(false);
                    editingFilmId = null;
                    hide(filmForm);
                    showFilmNote("Saved " + saved.title() + ".");
                    load();
                },
                failure -> {
                    saveFilm.setDisable(false);
                    showFilmError(failure.getMessage());
                });
    }

    private void addScreening() {
        Movie film = showMovie.getValue();
        Screen screen = showScreen.getValue();
        LocalTime time;
        try {
            time = LocalTime.parse(showTime.getText().trim(), INPUT_TIME);
        } catch (DateTimeParseException notATime) {
            showShowError("Use a time like 19:00.");
            return;
        }
        BigDecimal price;
        try {
            price = new BigDecimal(showPrice.getText().trim());
        } catch (NumberFormatException notANumber) {
            showShowError("Enter a base price in rupees, for example 250.00.");
            return;
        }

        addShow.setDisable(true);
        FxTasks.run(
                () -> service.addScreening(film, screen, showDate.getValue(), time, price),
                saved -> {
                    showShowNote("Added " + saved.movie().title()
                            + " at " + TIME.format(saved.startTime()) + ".");
                    load();
                },
                failure -> {
                    addShow.setDisable(false);
                    showShowError(failure.getMessage());
                });
    }

    private void renderShows(List<Showtime> shows) {
        showList.getChildren().clear();
        showCount.setText(shows.size() + " upcoming");
        if (shows.isEmpty()) {
            Label empty = new Label("No screenings yet. Add one above.");
            empty.getStyleClass().addAll("muted", "programme-empty");
            showList.getChildren().add(empty);
            return;
        }

        Map<LocalDate, List<Showtime>> byDate = shows.stream().collect(Collectors.groupingBy(
                Showtime::showDate, LinkedHashMap::new, Collectors.toList()));
        byDate.forEach((date, dayShows) -> {
            Label dateLabel = new Label(DAY_HEADER.format(date));
            dateLabel.getStyleClass().add("programme-date");
            Label count = new Label(dayShows.size() + (dayShows.size() == 1 ? " show" : " shows"));
            count.getStyleClass().add("muted");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox header = new HBox(8, dateLabel, gap, count);
            header.setAlignment(Pos.CENTER_LEFT);
            showList.getChildren().add(header);
            for (Showtime show : dayShows) {
                showList.getChildren().add(showRow(show));
            }
        });
    }

    private HBox showRow(Showtime show) {
        Label time = new Label(TIME.format(show.startTime()));
        time.getStyleClass().add("programme-time");
        time.setMinWidth(44);

        Label title = new Label(show.movie().title());
        title.getStyleClass().add("programme-show-title");
        Label room = new Label(show.screen().name() + "  ·  " + show.movie().certificate());
        room.getStyleClass().add("programme-show-meta");
        VBox words = new VBox(1, title, room);
        HBox.setHgrow(words, Priority.ALWAYS);

        Label price = new Label("₹" + Money.plain(show.basePrice()));
        price.getStyleClass().add("money");
        Label sold = new Label(show.seatsSold() + "/" + show.screen().seatCapacity());
        sold.getStyleClass().add("programme-show-meta");

        HBox row = new HBox(10, time, words, price, sold);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("programme-show");
        return row;
    }

    private void showFilmError(String message) {
        message(filmMessage, message, true);
    }

    private void showFilmNote(String message) {
        message(filmMessage, message, false);
    }

    private void showShowError(String message) {
        message(showMessage, message, true);
    }

    private void showShowNote(String message) {
        message(showMessage, message, false);
    }

    private void clearFilmMessage() {
        hide(filmMessage);
    }

    private static void message(Label label, String text, boolean error) {
        label.setText(text);
        label.getStyleClass().removeAll("error", "programme-note");
        label.getStyleClass().add(error ? "error" : "programme-note");
        show(label);
    }

    private static void show(Node node) {
        node.setVisible(true);
        node.setManaged(true);
    }

    private static void hide(Node node) {
        node.setVisible(false);
        node.setManaged(false);
    }

    private static StringConverter<Movie> movieConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(Movie movie) {
                return movie == null ? "" : movie.title();
            }

            @Override
            public Movie fromString(String text) {
                return null;
            }
        };
    }

    private static StringConverter<Screen> screenConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(Screen screen) {
                return screen == null ? "" : screen.name();
            }

            @Override
            public Screen fromString(String text) {
                return null;
            }
        };
    }

    private static StringConverter<LocalDate> dateConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return date == null ? "" : DATE_FIELD.format(date);
            }

            @Override
            public LocalDate fromString(String text) {
                try {
                    return text == null || text.isBlank() ? null : LocalDate.parse(text, DATE_FIELD);
                } catch (DateTimeParseException invalid) {
                    return null;
                }
            }
        };
    }

    private record Load(List<Movie> films, List<Screen> screens, List<Showtime> screenings) {
    }
}
