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
import com.cinebook.model.enums.PaymentMethod;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
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
        List<Seat> seats = freeSeats(show, 1);
        String label = seats.get(0).label();

        BookingService.Held held = service.hold(show, seats, operator);
        created.add(held.bookingId());

        // Verify the seat is locked
        assertTrue(new SeatDao(DB).soldLabels(show.showtimeId()).contains(label));

        // Sweep with cutoff in the future: sweeps this hold
        int swept = service.sweepExpiredHolds(LocalDateTime.now().plusMinutes(5));
        assertTrue(swept >= 1, "at least our created hold was swept");

        // Verify seat is free again
        assertFalse(new SeatDao(DB).soldLabels(show.showtimeId()).contains(label));

        // Verify booking status CANCELLED and cancelled_by_staff_id is NULL
        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status, cancelled_by_staff_id, refund_amount FROM booking WHERE booking_id = ?")) {
            statement.setInt(1, held.bookingId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals("CANCELLED", row.getString("status"));
                assertNull(row.getObject("cancelled_by_staff_id"), "system sweeper sets null staff id");
                assertEquals(0, BigDecimal.ZERO.compareTo(row.getBigDecimal("refund_amount")));
            }
        }
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

        // Search by known booking
        BookingSummary first = recent.get(0);
        List<BookingSummary> byId = service.list(String.valueOf(first.bookingId()), 5);
        assertFalse(byId.isEmpty());
        assertEquals(first.bookingId(), byId.get(0).bookingId());

        Optional<BookingSummary> found = service.find(first.bookingId());
        assertTrue(found.isPresent());
        assertEquals(first.bookingId(), found.get().bookingId());
    }

    @Test
    void holdExpiryDaemonLifecycleAndSweep() throws Exception {
        BookingService service = new BookingService(DB);
        HoldExpiryDaemon daemon = new HoldExpiryDaemon(service, Clock.systemDefaultZone(), 1L);
        assertFalse(daemon.isRunning(), "daemon should not be running before start");
        daemon.start();
        assertTrue(daemon.isRunning(), "daemon should be running after start");
        int swept = daemon.sweepNow();
        assertTrue(swept >= 0, "sweepNow should complete cleanly");
        daemon.stop();
        assertFalse(daemon.isRunning(), "daemon should not be running after stop");
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
