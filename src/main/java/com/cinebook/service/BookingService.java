package com.cinebook.service;

import com.cinebook.dao.BookingDao;
import com.cinebook.dao.ConcessionDao;
import com.cinebook.dao.CustomerDao;
import com.cinebook.dao.SeatDao;
import com.cinebook.exception.BookingException;
import com.cinebook.exception.SeatUnavailableException;
import com.cinebook.exception.ValidationException;
import com.cinebook.model.BookingSummary;
import com.cinebook.model.ConcessionLine;
import com.cinebook.model.Seat;
import com.cinebook.model.Showtime;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.BookingStatus;
import com.cinebook.util.Db;
import com.cinebook.util.Money;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The only place a hold transaction is opened (docs/PRD.md §13.1).
 *
 * <p>One booking in HELD, one ticket per seat, then the totals update — all on one
 * connection with {@code autoCommit} off. Any failure rolls the whole hold back,
 * so a hold can never leave a booking without its seats. The second person to
 * press Hold on the same seat hits {@code uq_seat_per_showtime}; that duplicate is
 * translated into {@link SeatUnavailableException} naming the seat, which is the
 * message the map can act on.
 *
 * <p>Customer: the seeded counter guest. The design chooses the buyer in the
 * checkout panel after the hold, and {@code booking.customer_id} is NOT NULL, so
 * the hold starts under the walk-in row; {@code CheckoutService.assignCustomer}
 * reassigns that id with one UPDATE before payment.
 */
public final class BookingService {

    /** What the bar needs back: the persisted id, not a whole object graph. */
    public record Held(int bookingId, int seats, BigDecimal total) {
    }

    /** What cancelConfirmed needs back: id, refund, and released seats. */
    public record Cancelled(int bookingId, BigDecimal refundAmount, int seatsFreed) {
    }

    private final Db db;
    private final BookingDao bookings;
    private final CustomerDao customers;
    private final ConcessionDao concessions;
    private final SeatDao seats;

    public BookingService(Db db) {
        this.db = db;
        this.bookings = new BookingDao();
        this.customers = new CustomerDao(db);
        this.concessions = new ConcessionDao(db);
        this.seats = new SeatDao(db);
    }

    /**
     * Holds every seat or none.
     *
     * @throws SeatUnavailableException if another booking took a picked seat first
     * @throws BookingException         if the input or the walk-in row is missing
     * @throws SQLException             if the hold could not be written (rolled back)
     */
    public Held hold(Showtime showtime, Collection<Seat> picked, StaffUser soldBy)
            throws BookingException, SQLException {
        if (showtime == null) {
            throw new ValidationException("A hold needs a screening.");
        }
        if (soldBy == null) {
            throw new ValidationException("A hold needs the signed-in staff member.");
        }
        if (picked == null || picked.isEmpty()) {
            throw new ValidationException("Pick at least one seat before holding.");
        }
        if (new HashSet<>(picked.stream().map(Seat::seatId).toList()).size() != picked.size()) {
            throw new ValidationException("The same seat cannot be held twice.");
        }

        OptionalInt walkIn = customers.walkInId();
        if (walkIn.isEmpty()) {
            throw new ValidationException(
                    "The walk-in customer is missing. Re-run db/02_seed.sql before selling.");
        }

        List<Seat> chosen = List.copyOf(picked);
        BigDecimal total = Money.sum(chosen.stream()
                .map(seat -> seat.priceFor(showtime))
                .toList());

        int bookingId;
        try (Db.Handle handle = db.borrow()) {
            Connection connection = handle.connection();
            connection.setAutoCommit(false);
            bookingId = bookings.insertHeld(connection, walkIn.getAsInt(), showtime, soldBy);
            bookings.insertTickets(connection, bookingId, showtime, chosen);
            bookings.writeTicketTotal(connection, bookingId, total);
            connection.commit();
        } catch (SQLException failure) {
            // The handle has already rolled back and returned its connection, so the
            // seat query below borrows a fresh one instead of nesting inside this
            // transaction — the pool refuses a nested borrow.
            if (isDuplicateKey(failure)) {
                throw seatTaken(showtime, chosen);
            }
            throw failure;
        }
        return new Held(bookingId, chosen.size(), total);
    }

    /**
     * Releases an unpaid hold: the booking is marked CANCELLED, its tickets and any
     * food are deleted in the same transaction, and the seats go back on sale.
     *
     * <p>One borrowed connection, three statements, one commit — the pool's
     * one-borrow-per-unit-of-work rule is what makes that safe: nothing else can be
     * mid-statement on this connection while the seats are being freed.
     *
     * <p><b>Why not {@code DELETE FROM booking}.</b> The schema carries an audit
     * trail on purpose — {@code cancelled_at}, {@code refund_amount} and
     * {@code cancelled_by_staff_id} — and a cancelled booking that vanishes cannot
     * be reported on later. The row stays; only its seat claims go.
     *
     * <p><b>The null is the design.</b> {@code releasedBy} is null when the
     * 60-second expiry job sweeps an abandoned hold, and set when a clerk releases
     * one from the counter. {@code db/04_queries.sql} filters the clerk performance
     * report on {@code cancelled_by_staff_id}, so stamping an automated sweep with
     * whoever happened to be signed in would quietly attribute it to a person.
     *
     * <p>Money is never touched beyond {@code refund_amount = 0}: a HELD booking was
     * never charged, so there is nothing to return and the 90/50 policy in
     * {@link RefundPolicy} does not come into it.
     *
     * @param releasedBy the clerk releasing the hold, or {@code null} for the
     *                   expiry job (system-initiated)
     * @return the number of seats returned to sale
     * @throws ValidationException if the hold is no longer open — already paid,
     *                             already released, or expired elsewhere
     */
    public int release(int bookingId, StaffUser releasedBy)
            throws BookingException, SQLException {
        int seatsFreed;
        try (Db.Handle handle = db.borrow()) {
            Connection connection = handle.connection();
            connection.setAutoCommit(false);
            int cancelled = bookings.markCancelled(connection, bookingId, releasedBy);
            if (cancelled == 0) {
                // Nothing to release: status <> 'HELD' means it was paid, released or
                // expired before we got here. Roll back so the deletes below never run
                // against a booking somebody else owns.
                throw new ValidationException(
                        "This hold is no longer open. It may have been paid for, released, "
                                + "or expired — reopen the screening.");
            }
            // The seats must go or they stay sold: soldLabels() reads ticket alone.
            seatsFreed = bookings.deleteTickets(connection, bookingId);
            // The food was never charged either, and ON DELETE CASCADE cannot fire
            // because the booking row survives for the audit trail.
            bookings.deleteConcessions(connection, bookingId);
            connection.commit();
        }
        return seatsFreed;
    }

    /**
     * Lists bookings matching an optional search term (customer name, phone, show
     * date or reference), newest first, up to the given limit.
     */
    public List<BookingSummary> list(String query, int limit) throws SQLException {
        return list(query, null, limit);
    }

    /**
     * Same register, narrowed to one status by the filter chips. The filter belongs
     * in the query: applied to the loaded page it would hide older matches that
     * were never fetched.
     */
    public List<BookingSummary> list(String query, BookingStatus status, int limit)
            throws SQLException {
        try (Db.Handle handle = db.borrow()) {
            return bookings.search(handle.connection(), query, status, limit);
        }
    }

    /** The food lines for one booking; read only when the clerk expands a card. */
    public List<ConcessionLine> foodLines(int bookingId) throws SQLException {
        return concessions.linesFor(bookingId);
    }

    /**
     * Finds a booking by id.
     */
    public Optional<BookingSummary> find(int bookingId) throws SQLException {
        try (Db.Handle handle = db.borrow()) {
            return bookings.findById(handle.connection(), bookingId);
        }
    }

    /**
     * Previews the refund for a booking without altering database state.
     */
    public BigDecimal previewRefund(BookingSummary booking, LocalDateTime now)
            throws ValidationException {
        if (booking == null) {
            throw new ValidationException("Booking is required to compute refund.");
        }
        if (booking.status() != BookingStatus.CONFIRMED) {
            throw new ValidationException(
                    "Only CONFIRMED bookings are eligible for a refund; this booking is "
                            + booking.status() + ".");
        }
        return RefundPolicy.ticketRefund(booking.ticketAmount(), booking.startsAt(), now);
    }

    /**
     * Cancels a CONFIRMED booking with a refund computed from {@link RefundPolicy}.
     *
     * <p>Executes as an atomic transaction on one borrowed connection:
     * 1. Verifies the booking is CONFIRMED and the screening has not started.
     * 2. Calculates refund (90% > 2h out, 50% <= 2h out; food excluded).
     * 3. Updates status to CANCELLED, fills refund_amount, cancelled_at and cancelled_by_staff_id.
     * 4. Deletes ticket rows to release physical seats back to inventory.
     *
     * @return summary with refund amount and count of released seats
     */
    public Cancelled cancel(int bookingId, StaffUser clerk, LocalDateTime now)
            throws BookingException, SQLException {
        if (clerk == null) {
            throw new ValidationException("Cancellation requires an authenticated staff member.");
        }
        if (now == null) {
            now = LocalDateTime.now();
        }

        BigDecimal refund;
        int seatsFreed;
        try (Db.Handle handle = db.borrow()) {
            Connection connection = handle.connection();
            connection.setAutoCommit(false);

            Optional<BookingSummary> existing = bookings.findById(connection, bookingId);
            if (existing.isEmpty()) {
                throw new ValidationException("Booking #" + bookingId + " does not exist.");
            }
            BookingSummary b = existing.get();
            if (b.status() == BookingStatus.CANCELLED) {
                throw new ValidationException("Booking #" + bookingId + " is already cancelled.");
            }
            if (b.status() != BookingStatus.CONFIRMED) {
                throw new ValidationException(
                        "Only CONFIRMED bookings can be cancelled with a refund; booking #"
                                + bookingId + " is " + b.status() + ".");
            }
            if (!now.isBefore(b.startsAt())) {
                throw new ValidationException(
                        "This screening has already started — cancellation is no longer available.");
            }

            refund = RefundPolicy.ticketRefund(b.ticketAmount(), b.startsAt(), now);

            int updated = bookings.cancelConfirmed(connection, bookingId, refund, clerk);
            if (updated == 0) {
                throw new ValidationException("Booking #" + bookingId + " could not be cancelled.");
            }
            // Frees seats back to inventory
            seatsFreed = bookings.deleteTickets(connection, bookingId);
            connection.commit();
        }
        return new Cancelled(bookingId, refund, seatsFreed);
    }

    /**
     * Sweeps all HELD bookings that were booked before the cutoff timestamp.
     *
     * <p>Operates as an atomic transaction on one borrowed connection:
     * marks each booking CANCELLED with cancelled_by_staff_id = NULL (system daemon),
     * deletes ticket claims, and cleans uncommitted concessions.
     *
     * @param cutoff the time threshold before which holds are considered expired
     * @return count of holds swept
     */
    public int sweepExpiredHolds(LocalDateTime cutoff) throws SQLException {
        if (cutoff == null) {
            cutoff = LocalDateTime.now().minusMinutes(10);
        }
        int swept = 0;
        try (Db.Handle handle = db.borrow()) {
            Connection connection = handle.connection();
            connection.setAutoCommit(false);
            List<Integer> expiredIds = bookings.findExpiredHoldIds(connection, cutoff);
            for (int id : expiredIds) {
                // The id list was locked FOR UPDATE, so this should always match. It
                // is still checked: deleting the tickets of a booking the UPDATE did
                // not cancel would free seats somebody has paid for.
                if (bookings.markCancelled(connection, id, null) == 0) {
                    continue;
                }
                bookings.deleteTickets(connection, id);
                bookings.deleteConcessions(connection, id);
                swept++;
            }
            connection.commit();
        }
        return swept;
    }

    /**
     * After a rollback, the seat that beat us is whichever picked label now appears
     * in the sold set. Its own tickets are gone with the rollback, so anything sold
     * now belongs to someone else.
     */
    private SeatUnavailableException seatTaken(Showtime showtime, List<Seat> chosen)
            throws SQLException {
        Set<String> sold = seats.soldLabels(showtime.showtimeId());
        String label = chosen.stream()
                .map(Seat::label)
                .filter(sold::contains)
                .findFirst()
                .orElse(null);
        String message = label == null
                ? "A seat was taken while you were choosing. The map has been refreshed — pick another."
                : label + " was taken while you were choosing. Pick another seat.";
        return new SeatUnavailableException(message);
    }

    private static boolean isDuplicateKey(SQLException failure) {
        return failure.getErrorCode() == 1062;
    }
}
