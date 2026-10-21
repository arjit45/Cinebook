package com.cinebook.service;

import com.cinebook.exception.ValidationException;
import com.cinebook.model.Booking;
import com.cinebook.model.enums.BookingStatus;
import com.cinebook.util.Money;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The refund rule, as one pure function so it can be tested at the boundary
 * without a database, a UI or a clock.
 *
 * <p>From docs/PRD.md §13.1: more than two hours before the film starts is 90% of
 * the <em>ticket</em> amount; two hours or less is 50%; once it has started there
 * is no refund at all. The boundary is {@code showStart - now > 2 h}, so exactly
 * two hours counts as the late case — someone arriving at the desk two hours early
 * is the late customer, not the early one.
 *
 * <p>Food is excluded by construction: the calculation never sees
 * {@code fnbAmount}. The customer has the popcorn and the counter cannot resell it.
 */
public final class RefundPolicy {

    public static final Duration EARLY_WINDOW = Duration.ofHours(2);

    private static final BigDecimal EARLY_RATE = new BigDecimal("0.90");
    private static final BigDecimal LATE_RATE = new BigDecimal("0.50");

    private RefundPolicy() {
    }

    /**
     * Refund for a ticket amount, given when the film starts and when it is being
     * cancelled.
     *
     * @throws ValidationException if the film has already started
     */
    public static BigDecimal ticketRefund(BigDecimal ticketAmount, LocalDateTime showStart,
                                          LocalDateTime now) throws ValidationException {
        if (ticketAmount == null || ticketAmount.signum() < 0) {
            throw new ValidationException("A refund needs a non-negative ticket amount.");
        }
        if (!now.isBefore(showStart)) {
            throw new ValidationException(
                    "This screening has already started — cancellation is no longer available.");
        }
        boolean early = showStart.minus(EARLY_WINDOW).isAfter(now);
        return Money.times(ticketAmount, early ? EARLY_RATE : LATE_RATE);
    }

    /**
     * Refund for a booking. Only a CONFIRMED booking can be refunded: a HELD hold
     * was never charged, and a cancelled one was already settled.
     */
    public static BigDecimal refundFor(Booking booking, LocalDateTime now) throws ValidationException {
        if (booking.status() != BookingStatus.CONFIRMED) {
            throw new ValidationException(
                    "Only a CONFIRMED booking can be refunded; this one is " + booking.status() + ".");
        }
        return ticketRefund(booking.ticketAmount(), booking.showtime().startsAt(), now);
    }
}
