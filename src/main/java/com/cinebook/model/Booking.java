package com.cinebook.model;

import com.cinebook.exception.ValidationException;
import com.cinebook.model.enums.BookingStatus;
import com.cinebook.util.Money;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The sale, from the seat map to the receipt.
 *
 * <p>Two rules are enforced here rather than in a service, because they are
 * statements about the object itself: the state machine (a booking only moves
 * HELD → CONFIRMED → CANCELLED, never backwards) and the money identity
 * {@code total = tickets + food} (docs/PRD.md §13.1). The database repeats both —
 * {@code chk_booking_total} and {@code sp_confirm_booking} — because both layers
 * guarantee truth, for different reasons.
 *
 * <p>Totals are recomputed from the lines on every change rather than accumulated,
 * so a removed seat cannot leave a stale number behind.
 */
public final class Booking {

    /** How long a hold survives before the expiry job releases it (docs/PRD.md §13.3). */
    public static final Duration HOLD_TTL = Duration.ofMinutes(10);

    private final LocalDateTime bookedAt;
    private final Customer customer;
    private final Showtime showtime;
    private final StaffUser soldBy;
    private final List<Ticket> tickets = new ArrayList<>();
    private final List<ConcessionLine> concessions = new ArrayList<>();

    private int bookingId;
    private BookingStatus status = BookingStatus.HELD;
    private BigDecimal ticketAmount = Money.round(BigDecimal.ZERO);
    private BigDecimal fnbAmount = Money.round(BigDecimal.ZERO);
    private BigDecimal totalAmount = Money.round(BigDecimal.ZERO);
    private BigDecimal refundAmount = Money.round(BigDecimal.ZERO);
    private StaffUser cancelledBy;
    private LocalDateTime cancelledAt;

    public Booking(Customer customer, Showtime showtime, StaffUser soldBy, LocalDateTime bookedAt) {
        if (customer == null || showtime == null) {
            throw new IllegalArgumentException("A booking needs a customer and a showtime");
        }
        this.customer = customer;
        this.showtime = showtime;
        this.soldBy = soldBy;
        this.bookedAt = bookedAt;
    }

    // ---------------------------------------------------------------- seats

    public void addSeat(Seat seat, BigDecimal price) throws ValidationException {
        requireStatus(BookingStatus.HELD, "add a seat to");
        for (Ticket ticket : tickets) {
            if (ticket.label().equals(seat.label())) {
                throw new ValidationException("Seat " + seat.label() + " is already on this booking.");
            }
        }
        tickets.add(new Ticket(seat, showtime, Money.round(price)));
        recalculateTotal();
    }

    public void removeSeat(Seat seat) throws ValidationException {
        requireStatus(BookingStatus.HELD, "remove a seat from");
        if (!tickets.removeIf(ticket -> ticket.label().equals(seat.label()))) {
            throw new ValidationException("Seat " + seat.label() + " is not on this booking.");
        }
        recalculateTotal();
    }

    // ----------------------------------------------------------------- food

    public void addConcession(ConcessionItem item, int quantity) throws ValidationException {
        if (quantity < 1) {
            throw new ValidationException("Quantity must be at least 1.");
        }
        requireStatus(BookingStatus.HELD, "add food to");
        concessions.add(new ConcessionLine(item.name(), item.category(), quantity, item.unitPrice()));
        recalculateTotal();
    }

    // ---------------------------------------------------------- state machine

    public void confirm() throws ValidationException {
        if (status != BookingStatus.HELD) {
            throw new ValidationException(
                    "Only a HELD booking can be confirmed; this one is " + status + ".");
        }
        status = BookingStatus.CONFIRMED;
    }

    public void cancel() throws ValidationException {
        cancel(soldBy, LocalDateTime.now());
    }

    public void cancel(StaffUser by, LocalDateTime at) throws ValidationException {
        if (status == BookingStatus.CANCELLED) {
            throw new ValidationException("This booking is already cancelled.");
        }
        status = BookingStatus.CANCELLED;
        cancelledBy = by;
        cancelledAt = at;
    }

    /**
     * Records what was handed back. The rule that a refund never exceeds the ticket
     * amount mirrors {@code chk_refund_amount}: food is not refundable (docs/PRD.md §13.1).
     */
    public void recordRefund(BigDecimal amount) throws ValidationException {
        if (status != BookingStatus.CANCELLED) {
            throw new ValidationException("A refund only records on a cancelled booking.");
        }
        if (amount == null || amount.signum() < 0) {
            throw new ValidationException("A refund cannot be negative.");
        }
        if (amount.compareTo(ticketAmount) > 0) {
            throw new ValidationException("A refund cannot exceed the ticket amount of "
                    + ticketAmount.toPlainString() + ".");
        }
        refundAmount = Money.round(amount);
    }

    public boolean isExpired(LocalDateTime now) {
        return status == BookingStatus.HELD && bookedAt.plus(HOLD_TTL).isBefore(now);
    }

    // ----------------------------------------------------------------- money

    public void recalculateTotal() {
        ticketAmount = Money.sum(tickets.stream().map(Ticket::price).toList());
        fnbAmount = fnbTotal();
        totalAmount = Money.round(ticketAmount.add(fnbAmount));
    }

    public BigDecimal fnbTotal() {
        return Money.sum(concessions.stream().map(ConcessionLine::lineTotal).toList());
    }

    // ------------------------------------------------------------- accessors

    public int bookingId() {
        return bookingId;
    }

    public void assignId(int bookingId) {
        this.bookingId = bookingId;
    }

    public BookingStatus status() {
        return status;
    }

    public LocalDateTime bookedAt() {
        return bookedAt;
    }

    public Customer customer() {
        return customer;
    }

    public Showtime showtime() {
        return showtime;
    }

    public StaffUser soldBy() {
        return soldBy;
    }

    public StaffUser cancelledBy() {
        return cancelledBy;
    }

    public LocalDateTime cancelledAt() {
        return cancelledAt;
    }

    public BigDecimal ticketAmount() {
        return ticketAmount;
    }

    public BigDecimal fnbAmount() {
        return fnbAmount;
    }

    public BigDecimal totalAmount() {
        return totalAmount;
    }

    public BigDecimal refundAmount() {
        return refundAmount;
    }

    public List<Ticket> tickets() {
        return Collections.unmodifiableList(tickets);
    }

    public List<ConcessionLine> concessions() {
        return Collections.unmodifiableList(concessions);
    }

    private void requireStatus(BookingStatus expected, String action) throws ValidationException {
        if (status != expected) {
            throw new ValidationException("Cannot " + action + " a " + status + " booking.");
        }
    }
}
