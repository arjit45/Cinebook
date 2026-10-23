package com.cinebook.service;

import com.cinebook.dao.MovieDao;
import com.cinebook.dao.ScreenDao;
import com.cinebook.dao.ShowtimeDao;
import com.cinebook.exception.AuthException;
import com.cinebook.exception.ValidationException;
import com.cinebook.model.Movie;
import com.cinebook.model.Screen;
import com.cinebook.model.Showtime;
import com.cinebook.model.enums.Certificate;
import com.cinebook.util.Db;
import com.cinebook.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The Programme surface: the film catalogue and the screening schedule.
 *
 * <p>Every entry point calls {@link AuthService#requireAdmin()} before it touches a
 * query. The rail hides Programme from an operator, but hiding a button is not a
 * control (docs/PRD.md §16); a hand-built call to this service still has to pass the
 * role check.
 *
 * <p><b>The clash is a database fact.</b> {@code uq_screen_slot} is what makes a
 * duplicate screening impossible; this service pre-checks nothing and translates
 * MySQL's 1062 into the sentence the clerk reads. Two admins pressing Add at the
 * same moment therefore both get a truthful answer, one of them with the error.
 *
 * <p>A film with screenings cannot be deleted: {@code showtime.movie_id} is
 * {@code ON DELETE RESTRICT}. The count below is the friendly pre-check; the 1451
 * catch is the real guard if a screening is added between the two statements.
 */
public final class ProgrammeService {

    /** How many future screenings the Programme list shows. */
    private static final int SCREENING_LIMIT = 400;

    private static final int MIN_RUNTIME = 60;
    private static final int MAX_RUNTIME = 240;
    private static final int TITLE_MAX = 150;
    private static final int GENRE_MAX = 40;
    private static final int LANGUAGE_MAX = 20;
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999.99");
    private static final BigDecimal MAX_RATING = new BigDecimal("10");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    /** What the film form sends; validation happens once, in {@link #addMovie}. */
    public record MovieDraft(String title, String genre, String language, int runtimeMin,
                             LocalDate releaseDate, Certificate certificate,
                             BigDecimal imdbRating) {
    }

    private final Db db;
    private final AuthService auth;
    private final MovieDao movies;
    private final ScreenDao screens;
    private final ShowtimeDao showtimes;

    public ProgrammeService(Db db, AuthService auth) {
        this.db = Objects.requireNonNull(db, "db");
        this.auth = Objects.requireNonNull(auth, "auth");
        this.movies = new MovieDao(db);
        this.screens = new ScreenDao(db);
        this.showtimes = new ShowtimeDao(db);
    }

    public List<Movie> catalogue() throws AuthException, SQLException {
        auth.requireAdmin();
        return movies.listAll();
    }

    public List<Screen> screens() throws AuthException, SQLException {
        auth.requireAdmin();
        return screens.listAll();
    }

    /** Future screenings only; a finished slot belongs in the register, not the builder. */
    public List<Showtime> screenings() throws AuthException, SQLException {
        auth.requireAdmin();
        return showtimes.programme(SCREENING_LIMIT);
    }

    public Movie addMovie(MovieDraft draft) throws AuthException, ValidationException, SQLException {
        auth.requireAdmin();
        Movie clean = clean(draft);
        try (Db.Handle handle = db.borrow()) {
            int id = movies.insert(handle.connection(), clean);
            return withId(clean, id);
        }
    }

    public Movie updateMovie(int movieId, MovieDraft draft)
            throws AuthException, ValidationException, SQLException {
        auth.requireAdmin();
        if (movieId <= 0) {
            throw new ValidationException("Choose a film from the catalogue first.");
        }
        Movie clean = clean(draft);
        Movie updated = withId(clean, movieId);
        try (Db.Handle handle = db.borrow()) {
            if (movies.update(handle.connection(), updated) == 0) {
                throw new ValidationException("That film no longer exists. Refresh the catalogue.");
            }
        }
        return updated;
    }

    public void deleteMovie(int movieId) throws AuthException, ValidationException, SQLException {
        auth.requireAdmin();
        if (movieId <= 0) {
            throw new ValidationException("Choose a film from the catalogue first.");
        }
        try (Db.Handle handle = db.borrow()) {
            Connection connection = handle.connection();
            if (showtimes.countForMovie(connection, movieId) > 0) {
                throw new ValidationException(
                        "This film still has screenings, so it cannot be deleted.");
            }
            if (movies.delete(connection, movieId) == 0) {
                throw new ValidationException("That film no longer exists. Refresh the catalogue.");
            }
        } catch (SQLException failure) {
            if (failure.getErrorCode() == 1451) {
                throw new ValidationException(
                        "This film still has screenings, so it cannot be deleted.");
            }
            throw failure;
        }
    }

    /**
     * Adds a screening, refusing a time in the past and translating the slot key into
     * the sentence the clerk can act on: "Audi 2 already has a show at 19:00."
     */
    public Showtime addScreening(Movie movie, Screen screen, LocalDate showDate,
                                 LocalTime startTime, BigDecimal basePrice)
            throws AuthException, ValidationException, SQLException {
        auth.requireAdmin();
        if (movie == null) {
            throw new ValidationException("Choose a film for the screening.");
        }
        if (screen == null) {
            throw new ValidationException("Choose an auditorium for the screening.");
        }
        if (showDate == null) {
            throw new ValidationException("Choose a date for the screening.");
        }
        if (startTime == null) {
            throw new ValidationException("Choose a start time for the screening.");
        }
        if (basePrice == null) {
            throw new ValidationException("Enter a base price for the screening.");
        }
        LocalDateTime startsAt = LocalDateTime.of(showDate, startTime);
        if (!startsAt.isAfter(LocalDateTime.now())) {
            throw new ValidationException("Choose a screening time in the future.");
        }
        BigDecimal price = Money.round(basePrice);
        if (price.signum() <= 0) {
            throw new ValidationException("The base price must be greater than zero.");
        }
        if (price.compareTo(MAX_PRICE) > 0) {
            throw new ValidationException("That base price is too large for the till.");
        }

        try (Db.Handle handle = db.borrow()) {
            int id = showtimes.insert(handle.connection(), movie.movieId(), screen.screenId(),
                    showDate, startTime, price);
            return new Showtime(id, movie, screen, showDate, startTime, price);
        } catch (SQLException failure) {
            if (failure.getErrorCode() == 1062) {
                throw new ValidationException(screen.name()
                        + " already has a show at " + TIME.format(startTime) + ".");
            }
            if (failure.getErrorCode() == 1452) {
                throw new ValidationException(
                        "That film or auditorium no longer exists. Refresh the programme.");
            }
            throw failure;
        }
    }

    /**
     * Cleans and checks the form values. The database repeats every one of these
     * rules; this pass exists so the clerk reads a sentence, not a constraint name.
     */
    private static Movie clean(MovieDraft draft) throws ValidationException {
        if (draft == null) {
            throw new ValidationException("A film needs a title.");
        }
        String title = trimmed(draft.title());
        if (title.isEmpty()) {
            throw new ValidationException("A film needs a title.");
        }
        if (title.length() > TITLE_MAX) {
            throw new ValidationException("The title is too long (" + TITLE_MAX + " characters max).");
        }
        String genre = trimmed(draft.genre());
        if (genre.isEmpty()) {
            throw new ValidationException("A film needs a genre.");
        }
        if (genre.length() > GENRE_MAX) {
            throw new ValidationException("The genre is too long (" + GENRE_MAX + " characters max).");
        }
        String language = trimmed(draft.language());
        if (language.isEmpty()) {
            throw new ValidationException("A film needs a language.");
        }
        if (language.length() > LANGUAGE_MAX) {
            throw new ValidationException("The language is too long (" + LANGUAGE_MAX + " characters max).");
        }
        if (draft.runtimeMin() < MIN_RUNTIME || draft.runtimeMin() > MAX_RUNTIME) {
            throw new ValidationException(
                    "Runtime must be between " + MIN_RUNTIME + " and " + MAX_RUNTIME + " minutes.");
        }
        if (draft.releaseDate() == null) {
            throw new ValidationException("A film needs a release date.");
        }
        if (draft.certificate() == null) {
            throw new ValidationException("Choose a certificate for the film.");
        }
        BigDecimal rating = draft.imdbRating();
        if (rating != null) {
            if (rating.signum() < 0 || rating.compareTo(MAX_RATING) > 0) {
                throw new ValidationException("The rating must be between 0 and 10.");
            }
            rating = rating.setScale(1, RoundingMode.HALF_UP);
        }
        return new Movie(0, title, genre, language, draft.runtimeMin(),
                draft.releaseDate(), draft.certificate(), rating);
    }

    private static Movie withId(Movie movie, int id) {
        return new Movie(id, movie.title(), movie.genre(), movie.language(), movie.runtimeMin(),
                movie.releaseDate(), movie.certificate(), movie.imdbRating());
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
