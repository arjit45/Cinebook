package com.cinebook.service;

import com.cinebook.dao.StaffDao;
import com.cinebook.exception.AuthException;
import com.cinebook.exception.ValidationException;
import com.cinebook.model.Movie;
import com.cinebook.model.Screen;
import com.cinebook.model.Showtime;
import com.cinebook.model.enums.Certificate;
import com.cinebook.service.ProgrammeService.MovieDraft;
import com.cinebook.util.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Programme service against the real seed: film CRUD, the deletion guard,
 * the double-booking sentence, and the admin gate.
 *
 * <p>Films created here carry a unique title and are deleted in a {@code finally},
 * so the suite can run twice without turning the catalogue into a museum of test
 * data. Screenings are never created except by the clash test, which is expected
 * to be refused.
 */
@Tag("db")
class ProgrammeServiceTest {

    private static final Db DB = Db.get();
    private static final AuthService AUTH = new AuthService(new StaffDao(DB));
    private static final ProgrammeService PROGRAMME = new ProgrammeService(DB, AUTH);

    @BeforeEach
    void signInAsAdmin() throws Exception {
        AUTH.login("admin", "admin123");
    }

    @AfterAll
    static void closeConnection() {
        DB.close();
    }

    @Test
    void addMovieAppearsInTheCatalogueWithItsFieldsIntact() throws Exception {
        MovieDraft draft = new MovieDraft("Test Film " + System.nanoTime(), "Drama", "English",
                128, LocalDate.now().minusDays(30), Certificate.UA, new BigDecimal("7.5"));

        Movie saved = PROGRAMME.addMovie(draft);
        try {
            assertTrue(saved.movieId() > 0, "MySQL must return the new film's id");
            Movie readBack = find(saved.movieId());
            assertEquals(draft.title(), readBack.title());
            assertEquals("Drama", readBack.genre());
            assertEquals(128, readBack.runtimeMin());
            assertEquals(Certificate.UA, readBack.certificate());
            assertEquals(new BigDecimal("7.5"), readBack.imdbRating());
        } finally {
            PROGRAMME.deleteMovie(saved.movieId());
        }
    }

    @Test
    void updateChangesTheCatalogueRow() throws Exception {
        Movie saved = PROGRAMME.addMovie(new MovieDraft(
                "Test Film " + System.nanoTime(), "Drama", "English", 120,
                LocalDate.now().minusDays(10), Certificate.U, new BigDecimal("6.0")));
        try {
            Movie updated = PROGRAMME.updateMovie(saved.movieId(), new MovieDraft(
                    saved.title() + " (Restored)", "Mystery", "Hindi", 141,
                    LocalDate.now().minusDays(5), Certificate.A, new BigDecimal("8.4")));

            assertEquals(saved.movieId(), updated.movieId());
            Movie readBack = find(saved.movieId());
            assertEquals(saved.title() + " (Restored)", readBack.title());
            assertEquals("Mystery", readBack.genre());
            assertEquals("Hindi", readBack.language());
            assertEquals(141, readBack.runtimeMin());
            assertEquals(Certificate.A, readBack.certificate());
            assertEquals(new BigDecimal("8.4"), readBack.imdbRating());
        } finally {
            PROGRAMME.deleteMovie(saved.movieId());
        }
    }

    @Test
    void aFilmWithScreeningsCannotBeDeleted() throws Exception {
        Movie seeded = PROGRAMME.catalogue().get(0);
        int idBefore = seeded.movieId();

        ValidationException refused = assertThrows(ValidationException.class,
                () -> PROGRAMME.deleteMovie(idBefore));

        assertTrue(refused.getMessage().contains("still has screenings"), refused.getMessage());
        assertNotNull(find(idBefore), "the refused delete must leave the film in place");
    }

    @Test
    void aScreeningInAFreeSlotIsPersistedWithItsGeneratedId() throws Exception {
        Movie film = PROGRAMME.catalogue().get(0);
        Screen screen = PROGRAMME.screens().get(0);
        // Far past the seeded eight days, so this slot is free whatever the capture date.
        LocalDate date = LocalDate.now().plusDays(30);
        LocalTime time = LocalTime.of(3, 17);

        Showtime saved = PROGRAMME.addScreening(film, screen, date, time, new BigDecimal("200.00"));
        try {
            assertTrue(saved.showtimeId() > 0, "MySQL must return the new screening's id");
            assertTrue(PROGRAMME.screenings().stream()
                            .anyMatch(show -> show.showtimeId() == saved.showtimeId()),
                    "the new screening must appear in the Programme list");
            Showtime readBack = PROGRAMME.screenings().stream()
                    .filter(show -> show.showtimeId() == saved.showtimeId())
                    .findFirst().orElseThrow();
            assertEquals(film.movieId(), readBack.movie().movieId());
            assertEquals(screen.screenId(), readBack.screen().screenId());
            assertEquals(date, readBack.showDate());
            assertEquals(time, readBack.startTime());
            assertEquals(new BigDecimal("200.00"), readBack.basePrice());
        } finally {
            deleteScreening(saved.showtimeId());
        }
    }

    @Test
    void aDoubleBookedSlotIsRefusedWithTheScreenAndTime() throws Exception {
        Showtime existing = PROGRAMME.screenings().get(0);
        Movie film = PROGRAMME.catalogue().get(0);

        ValidationException refused = assertThrows(ValidationException.class, () ->
                PROGRAMME.addScreening(film, existing.screen(), existing.showDate(),
                        existing.startTime(), new BigDecimal("200.00")));

        assertTrue(refused.getMessage().contains(existing.screen().name()), refused.getMessage());
        assertTrue(refused.getMessage().contains("already has a show"), refused.getMessage());
        assertTrue(refused.getMessage().contains(
                String.format("%02d:%02d", existing.startTime().getHour(), existing.startTime().getMinute())),
                "the sentence must name the time the clerk tried to take: " + refused.getMessage());
    }

    @Test
    void aScreeningInThePastIsRefusedBeforeAnyInsert() throws Exception {
        Movie film = PROGRAMME.catalogue().get(0);
        Screen screen = PROGRAMME.screens().get(0);

        ValidationException refused = assertThrows(ValidationException.class, () ->
                PROGRAMME.addScreening(film, screen, LocalDate.now().minusDays(1),
                        LocalTime.of(19, 0), new BigDecimal("200.00")));

        assertTrue(refused.getMessage().contains("future"), refused.getMessage());
    }

    @Test
    void blankTitlesAndShortRuntimesAreRefused() {
        ValidationException noTitle = assertThrows(ValidationException.class, () ->
                PROGRAMME.addMovie(new MovieDraft("   ", "Drama", "English", 120,
                        LocalDate.now(), Certificate.UA, null)));
        assertTrue(noTitle.getMessage().contains("title"), noTitle.getMessage());

        ValidationException shortFilm = assertThrows(ValidationException.class, () ->
                PROGRAMME.addMovie(new MovieDraft("Test Film " + System.nanoTime(), "Drama",
                        "English", 30, LocalDate.now(), Certificate.UA, null)));
        assertTrue(shortFilm.getMessage().contains("between 60 and 240"), shortFilm.getMessage());
    }

    @Test
    void anOperatorCannotReadTheCatalogue() throws Exception {
        AuthService operatorAuth = new AuthService(new StaffDao(DB));
        operatorAuth.login("operator", "operator123");
        ProgrammeService asOperator = new ProgrammeService(DB, operatorAuth);

        AuthException refused = assertThrows(AuthException.class, asOperator::catalogue);
        assertTrue(refused.getMessage().contains("administrator"), refused.getMessage());
    }

    /** Programme has no delete-screening action, so the test removes its own row. */
    private static void deleteScreening(int showtimeId) throws Exception {
        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM showtime WHERE showtime_id = ?")) {
            statement.setInt(1, showtimeId);
            statement.executeUpdate();
        }
    }

    private static Movie find(int movieId) throws Exception {
        List<Movie> catalogue = PROGRAMME.catalogue();
        return catalogue.stream()
                .filter(movie -> movie.movieId() == movieId)
                .findFirst()
                .orElse(null);
    }
}
