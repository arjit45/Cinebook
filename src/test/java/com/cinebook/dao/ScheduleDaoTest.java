package com.cinebook.dao;

import com.cinebook.model.Seat;
import com.cinebook.model.Showtime;
import com.cinebook.util.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DAO layer against the real seed: does one statement return what the SQL in
 * {@code db/04_queries.sql} returns, and do two different DAOs agree with each
 * other?
 *
 * <p>The cross-check is the point. {@code ShowtimeDao} counts sold seats with a
 * subquery; {@code SeatDao} lists them by label join. If those two disagree, one
 * of them is wrong and the seat map would show a seat as free that the schedule
 * counts as taken.
 */
@Tag("db")
class ScheduleDaoTest {

    private static final Db DB = Db.get();

    @AfterAll
    static void closeConnection() {
        DB.close();
    }

    @Test
    void upcomingReturnsTheSeededScheduleInOrder() throws Exception {
        List<Showtime> shows = new ShowtimeDao(DB).upcoming(8);

        assertEquals(8, shows.size(), "the seed has 32 screenings from today onward; the limit is 8");
        for (int i = 1; i < shows.size(); i++) {
            Showtime previous = shows.get(i - 1);
            Showtime current = shows.get(i);
            assertTrue(previous.startsAt().compareTo(current.startsAt()) <= 0,
                    "rows must come back in show order: " + previous.startsAt() + " before " + current.startsAt());
        }
    }

    @Test
    void screeningsAtTheSameMinuteAreOrderedByScreen() throws Exception {
        // The seed puts two screens on one start time every third day (Audi 3 and 4),
        // and those rows share a date and a time. Without an explicit tiebreak their
        // order is whatever the plan produces, and it changes between runs — so the
        // tiebreak is asserted, not assumed. The sawTie guard keeps this test from
        // passing vacuously if the seed ever stops producing a tie.
        List<Showtime> shows = new ShowtimeDao(DB).upcoming(20);
        boolean sawTie = false;
        for (int i = 1; i < shows.size(); i++) {
            Showtime previous = shows.get(i - 1);
            Showtime current = shows.get(i);
            if (previous.startsAt().equals(current.startsAt())) {
                sawTie = true;
                assertTrue(previous.screen().name().compareTo(current.screen().name()) < 0,
                        "same start time must fall back to screen order: "
                                + previous.screen().name() + " then " + current.screen().name());
            }
        }
        assertTrue(sawTie, "the seed must contain a same-start-time pair for this test to mean anything");
    }

    @Test
    void theSameQueryRunTwiceGivesTheSameOrder() throws Exception {
        String first = new ShowtimeDao(DB).upcoming(8).stream()
                .map(show -> show.showtimeId() + ":" + show.screen().name()).toList().toString();
        String second = new ShowtimeDao(DB).upcoming(8).stream()
                .map(show -> show.showtimeId() + ":" + show.screen().name()).toList().toString();
        assertEquals(first, second);
    }

    @Test
    void everyRowIsTodayOrLaterAndFullyMapped() throws Exception {
        for (Showtime show : new ShowtimeDao(DB).upcoming(20)) {
            assertFalse(show.showDate().isBefore(LocalDate.now()), "past screening leaked in");
            assertFalse(show.movie().title().isBlank(), "movie title did not map");
            assertFalse(show.screen().name().isBlank(), "screen name did not map");
            assertTrue(show.basePrice().compareTo(BigDecimal.ZERO) > 0, "base price must be positive");
            assertEquals(96, show.screen().seatCapacity());
        }
    }

    @Test
    void limitIsHonoured() throws Exception {
        assertEquals(3, new ShowtimeDao(DB).upcoming(3).size());
    }

    @Test
    void sellableDropsScreeningsTodayThatHaveAlreadyStarted() throws Exception {
        // A screening stamped 00:00 today is on by the time any test runs. The display
        // query must still list it — the schedule keeps the day's slate intact — while
        // the sellable query must drop it, or the counter would be offered a film that
        // is already running. Inserted here because the seed's slots are never past:
        // this is the boundary no seeded row can reach on its own.
        ShowtimeDao shows = new ShowtimeDao(DB);
        int showtimeId;
        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection()) {
            showtimeId = shows.insert(connection, 1, 1, LocalDate.now(),
                    LocalTime.of(0, 0), new BigDecimal("199.00"));
        }
        try {
            assertTrue(shows.upcoming(400).stream()
                            .anyMatch(show -> show.showtimeId() == showtimeId),
                    "the schedule still lists a screening that started earlier today");
            assertFalse(shows.sellable(400).stream()
                            .anyMatch(show -> show.showtimeId() == showtimeId),
                    "the counter must not be offered a screening that is already on");
        } finally {
            deleteScreening(showtimeId);
        }
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

    @Test
    void seatCountAndSoldLabelsAgreeBetweenDaos() throws Exception {
        Showtime first = new ShowtimeDao(DB).upcoming(1).get(0);

        List<Seat> seats = new SeatDao(DB).byScreen(first.screen().screenId());
        Set<String> sold = new SeatDao(DB).soldLabels(first.showtimeId());

        assertEquals(96, seats.size(), "each auditorium holds 96 seats");
        assertEquals(first.seatsSold(), sold.size(),
                "the schedule's seats_sold and the seat map's sold labels must be the same number");

        Set<String> labels = new HashSet<>(seats.stream().map(Seat::label).toList());
        assertEquals(96, labels.size(), "seat labels must be unique within a screen");
        assertTrue(labels.containsAll(sold), "every sold label must exist among the screen's seats");
    }

    @Test
    void seatsComeBackInReadingOrder() throws Exception {
        List<Seat> seats = new SeatDao(DB).byScreen(1);
        Seat first = seats.get(0);
        Seat last = seats.get(seats.size() - 1);

        assertEquals("A1", first.label());
        assertEquals("H12", last.label(), "8 rows of 12 is what chk_seat_no allows the seed to use");
    }
}
