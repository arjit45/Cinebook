package com.cinebook.service;

import com.cinebook.dao.CustomerDao;
import com.cinebook.dao.SeatDao;
import com.cinebook.dao.ShowtimeDao;
import com.cinebook.dao.StaffDao;
import com.cinebook.exception.SeatUnavailableException;
import com.cinebook.exception.ValidationException;
import com.cinebook.model.Seat;
import com.cinebook.model.Showtime;
import com.cinebook.model.StaffUser;
import com.cinebook.util.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import com.cinebook.model.BookingSummary;
import com.cinebook.model.enums.BookingStatus;
import com.cinebook.model.enums.PaymentMethod;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transaction the seat map relies on: every picked seat is held, or none is.
 *
 * <p>These run against the real seed on purpose. The duplicate-key race is a
 * database behaviour — `uq_seat_per_showtime` — and a mock would only prove that
 * the mock throws. Each test deletes the bookings it created, so the seed stays
 * the seed for the UI tests that follow.
 */
@Tag("db")
class BookingServiceTest {

    private static final Db DB = Db.get();
    private final List<Integer> created = new ArrayList<>();

    @AfterEach
    void removeCreatedBookings() throws SQLException {
        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection()) {
            for (int bookingId : created) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM booking WHERE booking_id = ?")) {
                    statement.setInt(1, bookingId);
                    statement.executeUpdate();
                }
            }
        } finally {
            created.clear();
        }
    }

    @AfterAll
    static void closeConnection() {
        DB.close();
    }

    @Test
    void holdWritesOneBookingWithItsTicketsAndTotals() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(1).get(0);
        List<Seat> picked = freeSeats(show, 2);
        StaffUser operator = new StaffDao(DB).findByUsername("operator").orElseThrow();
        BigDecimal expected = picked.stream()
                .map(seat -> seat.priceFor(show))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BookingService.Held held = new BookingService(DB).hold(show, picked, operator);
        created.add(held.bookingId());

        assertEquals(2, held.seats());
        assertEquals(0, expected.compareTo(held.total()), "the hold total is the class sum");

        // Read before borrowing below: the walk-in lookup borrows the pool's next
        // connection, and one unit of work may hold only one at a time.
        int walkIn = new CustomerDao(DB).walkInId().orElseThrow();

        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status, customer_id, sold_by_staff_id,
                            ticket_amount, fnb_amount, total_amount
                       FROM booking
                      WHERE booking_id = ?
                     """)) {
            statement.setInt(1, held.bookingId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next(), "the hold must exist");
                assertEquals("HELD", row.getString("status"));
                assertEquals(walkIn, row.getInt("customer_id"),
                        "phase 4 holds under the walk-in guest");
                assertEquals(operator.staffId(), row.getInt("sold_by_staff_id"));
                assertEquals(0, held.total().compareTo(row.getBigDecimal("ticket_amount")));
                assertEquals(0, BigDecimal.ZERO.compareTo(row.getBigDecimal("fnb_amount")));
                assertEquals(0, held.total().compareTo(row.getBigDecimal("total_amount")));
            }
        }

        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) AS n, COALESCE(SUM(price), 0) AS total "
                             + "FROM ticket WHERE booking_id = ?")) {
            statement.setInt(1, held.bookingId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(2, row.getInt("n"), "one ticket per picked seat");
                assertEquals(0, held.total().compareTo(row.getBigDecimal("total")));
            }
        }

        Set<String> sold = new SeatDao(DB).soldLabels(show.showtimeId());
        for (Seat seat : picked) {
            assertTrue(sold.contains(seat.label()), seat.label() + " must read as sold after the hold");
        }
    }

    @Test
    void secondHoldOnTheSameSeatIsRefusedAndRollsBack() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(1).get(0);
        Seat seat = freeSeats(show, 1).get(0);
        StaffUser operator = new StaffDao(DB).findByUsername("operator").orElseThrow();
        BookingService service = new BookingService(DB);

        BookingService.Held first = service.hold(show, List.of(seat), operator);
        created.add(first.bookingId());
        int bookingsBefore = bookingsFor(show.showtimeId());

        SeatUnavailableException refused = assertThrows(SeatUnavailableException.class,
                () -> service.hold(show, List.of(seat), operator));

        assertTrue(refused.getMessage().contains(seat.label()),
                "the message must name the seat that was taken: " + refused.getMessage());
        assertEquals(bookingsBefore, bookingsFor(show.showtimeId()),
                "the failed hold must leave no booking behind");
    }

    @Test
    void releasingAnUnpaidHoldFreesTheSeatsAndKeepsTheAuditTrail() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(1).get(0);
        List<Seat> picked = freeSeats(show, 2);
        StaffUser operator = new StaffDao(DB).findByUsername("operator").orElseThrow();
        BookingService service = new BookingService(DB);

        BookingService.Held held = service.hold(show, picked, operator);
        created.add(held.bookingId());
        for (Seat seat : picked) {
            assertTrue(new SeatDao(DB).soldLabels(show.showtimeId()).contains(seat.label()),
                    seat.label() + " must read as sold while the hold is open");
        }

        int freed = service.release(held.bookingId(), operator);

        assertEquals(2, freed, "both seats must go back on sale");
        Set<String> soldNow = new SeatDao(DB).soldLabels(show.showtimeId());
        for (Seat seat : picked) {
            assertFalse(soldNow.contains(seat.label()),
                    seat.label() + " must be sellable again after the release");
        }

        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status, cancelled_at, refund_amount, cancelled_by_staff_id,
                            sold_by_staff_id, ticket_amount
                       FROM booking
                      WHERE booking_id = ?
                     """)) {
            statement.setInt(1, held.bookingId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next(), "the booking must survive for the audit trail");
                assertEquals("CANCELLED", row.getString("status"));
                assertNotNull(row.getTimestamp("cancelled_at"),
                        "chk_booking_cancelled_at requires the timestamp with the status");
                assertEquals(0, BigDecimal.ZERO.compareTo(row.getBigDecimal("refund_amount")),
                        "a hold was never charged, so nothing is refunded");
                assertEquals(operator.staffId(), row.getInt("cancelled_by_staff_id"),
                        "a clerk release must name the clerk");
                assertEquals(operator.staffId(), row.getInt("sold_by_staff_id"),
                        "the original seller must not be rewritten");
                assertTrue(row.getBigDecimal("ticket_amount").signum() > 0,
                        "the money snapshot must survive the ticket deletion");
            }
        }

        // The row-count guard: a second release finds no HELD row and must refuse.
        ValidationException refused = assertThrows(ValidationException.class,
                () -> service.release(held.bookingId(), operator));
        assertTrue(refused.getMessage().contains("no longer open"),
                "the message must explain why: " + refused.getMessage());
    }

    @Test
    void expirySweepLeavesTheClerkNullSoThePerformanceReportCannotBePolluted() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(1).get(0);
        StaffUser operator = new StaffDao(DB).findByUsername("operator").orElseThrow();
        BookingService service = new BookingService(DB);

        BookingService.Held held = service.hold(show, freeSeats(show, 1), operator);
        created.add(held.bookingId());

        // The 60-second expiry job releases with no clerk: null means "the system
        // did this", which is what keeps db/04_queries.sql's clerk filter honest.
        service.release(held.bookingId(), null);

        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT cancelled_by_staff_id FROM booking WHERE booking_id = ?")) {
            statement.setInt(1, held.bookingId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertNull(row.getObject("cancelled_by_staff_id"),
                        "an expiry sweep must not be attributed to a signed-in clerk");
            }
        }
    }

    @Test
    void sweepExpiredHoldsReleasesSeatsAndCleansConcessions() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(1).get(0);
        StaffUser operator = new StaffDao(DB).findByUsername("operator").orElseThrow();
        BookingService service = new BookingService(DB);
        CheckoutService checkout = new CheckoutService(DB);

        List<Seat> seats = freeSeats(show, 1);
        String label = seats.get(0).label();
        BookingService.Held expired = service.hold(show, seats, operator);
        created.add(expired.bookingId());
        // Food written during the hold must be swept too: the booking row survives
        // for the audit trail, so its ON DELETE CASCADE never fires.
        checkout.setFood(expired.bookingId(), checkout.menu().get(0), 2);
        assertEquals(1, foodLines(expired.bookingId()), "the hold carries its food line");

        // A second, live hold proves the cutoff decides what is swept — not
        // "everything currently HELD". The old future cutoff cancelled all 40
        // seeded holds, so every run of this suite damaged the demo data.
        Seat liveSeat = freeSeats(show, 1).get(0);
        BookingService.Held live = service.hold(show, List.of(liveSeat), operator);
        created.add(live.bookingId());

        backdate(expired.bookingId(), LocalDateTime.of(2000, 1, 1, 9, 0));
        assertTrue(new SeatDao(DB).soldLabels(show.showtimeId()).contains(label));

        int swept = service.sweepExpiredHolds(LocalDateTime.of(2000, 1, 2, 0, 0));

        assertEquals(1, swept, "only the backdated hold is before the cutoff");
        assertFalse(new SeatDao(DB).soldLabels(show.showtimeId()).contains(label),
                "a swept hold must put its seat back on sale");
        assertEquals(0, foodLines(expired.bookingId()), "the hold's food lines go with it");
        assertEquals("HELD", statusOf(live.bookingId()),
                "a hold inside the TTL must survive the sweep");
        assertTrue(new SeatDao(DB).soldLabels(show.showtimeId()).contains(liveSeat.label()),
                "the live hold keeps its seat");

        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status, cancelled_by_staff_id, refund_amount FROM booking WHERE booking_id = ?")) {
            statement.setInt(1, expired.bookingId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals("CANCELLED", row.getString("status"));
                assertNull(row.getObject("cancelled_by_staff_id"), "system sweeper sets null staff id");
                assertEquals(0, BigDecimal.ZERO.compareTo(row.getBigDecimal("refund_amount")));
            }
        }
    }

    @Test
    void sweepNeverTouchesAPaidBookingEvenPastTheCutoff() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(1).get(0);
        StaffUser operator = new StaffDao(DB).findByUsername("operator").orElseThrow();
        BookingService service = new BookingService(DB);
        CheckoutService checkout = new CheckoutService(DB);

        BookingService.Held paid = service.hold(show, freeSeats(show, 1), operator);
        created.add(paid.bookingId());
        checkout.pay(paid.bookingId(), PaymentMethod.CARD);
        backdate(paid.bookingId(), LocalDateTime.of(2000, 1, 1, 9, 0));

        int swept = service.sweepExpiredHolds(LocalDateTime.of(2000, 1, 2, 0, 0));

        assertEquals(0, swept, "a CONFIRMED booking is not an expired hold");
        assertEquals("CONFIRMED", statusOf(paid.bookingId()));
        assertEquals(1, ticketCount(paid.bookingId()),
                "a paid booking must keep its tickets — deleting them would resell the seat");
    }

    @Test
    void cancelConfirmedBookingCalculatesRefundAndReleasesSeats() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(1).get(0);
        StaffUser operator = new StaffDao(DB).findByUsername("operator").orElseThrow();
        BookingService bookingService = new BookingService(DB);
        CheckoutService checkoutService = new CheckoutService(DB);

        List<Seat> seats = freeSeats(show, 1);
        String label = seats.get(0).label();

        BookingService.Held held = bookingService.hold(show, seats, operator);
        created.add(held.bookingId());

        // Confirm the booking
        checkoutService.pay(held.bookingId(), PaymentMethod.UPI);

        // Cancel 3 hours before screening (should get 90% refund of ticketAmount)
        LocalDateTime threeHoursBefore = show.startsAt().minusHours(3);
        BookingService.Cancelled cancelled = bookingService.cancel(held.bookingId(), operator, threeHoursBefore);

        assertEquals(1, cancelled.seatsFreed());
        assertTrue(cancelled.refundAmount().signum() > 0, "refund must be positive");

        // Verify seat is free again
        assertFalse(new SeatDao(DB).soldLabels(show.showtimeId()).contains(label));

        // Verify audit columns
        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status, cancelled_by_staff_id, refund_amount FROM booking WHERE booking_id = ?")) {
            statement.setInt(1, held.bookingId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals("CANCELLED", row.getString("status"));
                assertEquals(operator.staffId(), row.getInt("cancelled_by_staff_id"));
                assertEquals(0, cancelled.refundAmount().compareTo(row.getBigDecimal("refund_amount")));
            }
        }
    }

    @Test
    void cancelConfirmedBookingRefusesPastScreening() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(1).get(0);
        StaffUser operator = new StaffDao(DB).findByUsername("operator").orElseThrow();
        BookingService bookingService = new BookingService(DB);
        CheckoutService checkoutService = new CheckoutService(DB);

        BookingService.Held held = bookingService.hold(show, freeSeats(show, 1), operator);
        created.add(held.bookingId());
        checkoutService.pay(held.bookingId(), PaymentMethod.CASH);

        // After showtime starts
        LocalDateTime pastStart = show.startsAt().plusMinutes(5);
        ValidationException err = assertThrows(ValidationException.class,
                () -> bookingService.cancel(held.bookingId(), operator, pastStart));
        assertTrue(err.getMessage().contains("already started"), err.getMessage());
    }

    @Test
    void searchAndListBookings() throws Exception {
        BookingService service = new BookingService(DB);
        List<BookingSummary> recent = service.list(null, 10);
        assertFalse(recent.isEmpty(), "recent bookings must not be empty");

        BookingSummary first = recent.get(0);
        assertEquals(String.format("BK-%04d", first.bookingId()), first.reference(),
                "the register and the receipt use one reference format");

        // The same booking is reachable by id, by the printed reference, and by
        // the show date — the three things a clerk has at the counter.
        for (String query : List.of(
                String.valueOf(first.bookingId()),
                first.reference(),
                first.showDate().toString())) {
            List<BookingSummary> matches = service.list(query, 50);
            assertTrue(matches.stream().anyMatch(b -> b.bookingId() == first.bookingId()),
                    "searching '" + query + "' must find " + first.reference());
        }

        List<BookingSummary> confirmed = service.list(null, BookingStatus.CONFIRMED, 10);
        assertFalse(confirmed.isEmpty());
        assertTrue(confirmed.stream().allMatch(b -> b.status() == BookingStatus.CONFIRMED),
                "the status filter belongs in the query");

        Optional<BookingSummary> found = service.find(first.bookingId());
        assertTrue(found.isPresent());
        assertEquals(first.bookingId(), found.get().bookingId());
    }

    @Test
    void holdExpiryDaemonSweepsItsExpiredHoldAndStops() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(1).get(0);
        StaffUser operator = new StaffDao(DB).findByUsername("operator").orElseThrow();
        BookingService service = new BookingService(DB);

        BookingService.Held held = service.hold(show, freeSeats(show, 1), operator);
        created.add(held.bookingId());
        backdate(held.bookingId(), LocalDateTime.of(2000, 1, 1, 9, 0));

        // A fixed 2000 clock makes the scheduler's own tick sweep this hold and
        // nothing else: the seeded holds are in 2026 and stay for the demo.
        LocalDateTime fixedNow = LocalDateTime.of(2000, 1, 1, 10, 0);
        Clock clock = Clock.fixed(fixedNow.atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());
        HoldExpiryDaemon daemon = new HoldExpiryDaemon(service, clock, 1L);

        assertFalse(daemon.isRunning(), "daemon should not be running before start");
        try {
            daemon.start();
            assertTrue(daemon.isRunning(), "daemon should be running after start");
            assertTrue(waitForStatus(held.bookingId(), "CANCELLED", 3000),
                    "the scheduler's first tick must release an expired hold");
        } finally {
            daemon.stop();
        }
        assertFalse(daemon.isRunning(), "daemon should not be running after stop");

        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT cancelled_by_staff_id FROM booking WHERE booking_id = ?")) {
            statement.setInt(1, held.bookingId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertNull(row.getObject("cancelled_by_staff_id"),
                        "a scheduler sweep is attributed to the system, never a clerk");
            }
        }
    }

    /** Moves a created booking into the far past so a fixed cutoff can target it. */
    private static void backdate(int bookingId, LocalDateTime bookedAt) throws SQLException {
        try (Db.Handle handle = DB.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(
                     "UPDATE booking SET booked_at = ? WHERE booking_id = ?")) {
            statement.setTimestamp(1, Timestamp.valueOf(bookedAt));
            statement.setInt(2, bookingId);
            statement.executeUpdate();
        }
    }

    private static String statusOf(int bookingId) throws SQLException {
        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status FROM booking WHERE booking_id = ?")) {
            statement.setInt(1, bookingId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next(), "booking " + bookingId + " must exist");
                return row.getString("status");
            }
        }
    }

    private static int foodLines(int bookingId) throws SQLException {
        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) AS n FROM booking_concession WHERE booking_id = ?")) {
            statement.setInt(1, bookingId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt("n");
            }
        }
    }

    private static int ticketCount(int bookingId) throws SQLException {
        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) AS n FROM ticket WHERE booking_id = ?")) {
            statement.setInt(1, bookingId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt("n");
            }
        }
    }

    private static boolean waitForStatus(int bookingId, String expected, long timeoutMillis)
            throws Exception {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (expected.equals(statusOf(bookingId))) {
                return true;
            }
            Thread.sleep(50);
        }
        return expected.equals(statusOf(bookingId));
    }

    private static int bookingsFor(int showtimeId) throws SQLException {
        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) AS n FROM booking WHERE showtime_id = ?")) {
            statement.setInt(1, showtimeId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt("n");
            }
        }
    }

    /** Seats of this screening that no ticket holds yet. */
    private static List<Seat> freeSeats(Showtime show, int count) throws Exception {
        SeatDao seats = new SeatDao(DB);
        Set<String> sold = seats.soldLabels(show.showtimeId());
        return seats.byScreen(show.screen().screenId()).stream()
                .filter(seat -> !sold.contains(seat.label()))
                .limit(count)
                .toList();
    }
}
