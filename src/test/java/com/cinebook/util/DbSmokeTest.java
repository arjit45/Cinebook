package com.cinebook.util;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0's smoke test: the first proof that the Java side and the database side
 * actually meet.
 *
 * <p>Tagged {@code db}, so {@code mvn test} skips it and stays runnable with no
 * server (docs/PRD.md §14.1). Run it with {@code mvn test -Dgroups=db}.
 *
 * <p>What it covers, in the order the failures usually appear: the config file
 * loads, the credentials work, the seeded data is visible, the JDBC types map as
 * §11.3 requires, a local time survives the round trip, and the URL still pins the
 * server zone.
 *
 * <p>Note what the last two do <em>not</em> claim. Every temporal column in this
 * schema is {@code DATETIME}, which Connector/J passes through untouched: measured
 * on 2026-10-20, a deliberately wrong {@code serverTimezone=UTC} against a +05:30
 * server still read {@code 21:00} back as {@code 21:00}. A clock-drift test would
 * therefore pass with the setting removed, which is worse than no test. What the
 * parameter actually buys is a decided answer to "what zone is the server in?" at
 * connect time, instead of probing {@code @@system_time_zone} — and the correct
 * conversion for {@code TIMESTAMP} columns and {@code java.sql.Timestamp} reads.
 */
@Tag("db")
class DbSmokeTest {

    private static Db.Handle connection() {
        return Db.get().borrow();
    }

    @AfterAll
    static void closeConnection() {
        Db.get().close();
    }

    /**
     * The invariant that keeps a three-connection pool from deadlocking.
     *
     * <p>If a thread could borrow while already holding one, three consumers each
     * holding a connection and each asking for a second would stall forever. The
     * pool refuses the nesting instead of silently handing out another.
     */
    @Test
    void refusesASecondBorrowOnTheSameThread() throws SQLException {
        try (Db.Handle held = connection()) {
            assertTrue(held.connection().isValid(2), "the first borrow must succeed");
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    Db.get()::borrow,
                    "a nested borrow must be refused, not queued");
            assertTrue(refused.getMessage().contains("One borrow per unit of work"),
                    "the refusal should say why: " + refused.getMessage());
        }
        // After the outer handle closes, the same thread may borrow again.
        try (Db.Handle again = connection()) {
            assertTrue(again.connection().isValid(2),
                    "closing the handle must free this thread to borrow once more");
        }
    }

    /**
     * Closing a handle returns its connection, so borrowing more times than the
     * pool holds does not exhaust it.
     *
     * <p>If any path leaked a borrow, the fourth iteration would block and then
     * fail; looping well past {@link Db#POOL_SIZE} proves every handle came back.
     */
    @Test
    void everyBorrowComesBackToThePool() throws SQLException {
        for (int i = 0; i < Db.POOL_SIZE * 4; i++) {
            try (Db.Handle handle = connection()) {
                assertTrue(handle.connection().isValid(2),
                        "borrow " + i + " must hand out a live connection");
            }
        }
        assertEquals(Db.POOL_SIZE, Db.get().idleConnections(),
                "all connections must be idle again once every handle is closed");
    }

    @Test
    void connectsAsTheApplicationAccount() throws SQLException {
        try (Db.Handle handle = connection();
             var statement = handle.connection().prepareStatement("SELECT CURRENT_USER()");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("cinebook_app@localhost", rows.getString(1),
                    "the app must connect as cinebook_app, never as root");
        }
    }

    @Test
    void readsTheSeededBookings() throws SQLException {
        try (Db.Handle handle = connection();
             var statement = handle.connection().prepareStatement("SELECT COUNT(*) FROM booking");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(300, rows.getInt(1), "the seed loads 300 bookings");
        }
    }

    @Test
    void mapsMoneyDatesAndTimesAsThePrdRequires() throws SQLException {
        try (Db.Handle handle = connection();
             var statement = handle.connection().prepareStatement(
                     "SELECT base_price, show_date, start_time FROM showtime "
                             + "ORDER BY showtime_id LIMIT 1");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertInstanceOf(BigDecimal.class, rows.getObject(1, BigDecimal.class),
                    "money is BigDecimal, never double");
            assertInstanceOf(LocalDate.class, rows.getObject(2, LocalDate.class),
                    "DATE is LocalDate");
            assertInstanceOf(LocalTime.class, rows.getObject(3, LocalTime.class),
                    "TIME is LocalTime");
        }
    }

    @Test
    void keepsLocalTimesUnshiftedThroughAParameterAndBack() throws SQLException {
        // The 9pm demo rests on this: a DATETIME written as a LocalDateTime must
        // come back as the same wall-clock time, with no zone conversion anywhere.
        LocalDateTime bookedAt = LocalDateTime.of(2026, 10, 20, 21, 0);
        try (Db.Handle handle = connection();
             var statement = handle.connection().prepareStatement("SELECT CAST(? AS DATETIME)")) {
            statement.setObject(1, bookedAt);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(bookedAt, rows.getObject(1, LocalDateTime.class),
                        "a 9pm booking must read back as 9pm");
            }
        }
    }

    @Test
    void jdbcUrlPinsTheServerTimezone() throws SQLException {
        // Asserted on the URL the connection actually used, not on the file: this is
        // the value Connector/J saw. Without it the driver has to interrogate the
        // server, which on Windows answers with a name like "India Standard Time".
        try (Db.Handle handle = connection()) {
            String url = handle.connection().getMetaData().getURL();
            assertTrue(url.contains("serverTimezone="),
                    "jdbc.url must pin serverTimezone so Connector/J never guesses: " + url);
        }
    }
}
