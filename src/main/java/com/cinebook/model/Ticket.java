package com.cinebook.model;

import java.math.BigDecimal;

/**
 * One seat on one booking.
 *
 * <p>{@code price} is a snapshot, not a lookup: once sold, a change to
 * {@code showtime.base_price} must not move it, or every past receipt and report
 * silently restates itself.
 */
public record Ticket(Seat seat, Showtime showtime, BigDecimal price) {

    public Ticket {
        if (seat == null) {
            throw new IllegalArgumentException("A ticket needs a seat");
        }
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("A ticket price cannot be negative");
        }
    }

    public String label() {
        return seat.label();
    }
}
