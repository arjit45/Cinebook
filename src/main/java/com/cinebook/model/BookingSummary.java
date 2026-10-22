package com.cinebook.model;

import com.cinebook.model.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Projection of a booking for the bookings list and search view (docs/PRD.md §2).
 *
 * <p>Carries both staff names — who sold it and who cancelled it — so per-clerk
 * reconciliation and audit trails remain accurate even after cancellation.
 */
public record BookingSummary(
        int bookingId,
        int customerId,
        String customerName,
        String customerPhone,
        int showtimeId,
        String movieTitle,
        String screenName,
        LocalDate showDate,
        LocalTime startTime,
        BookingStatus status,
        BigDecimal ticketAmount,
        BigDecimal fnbAmount,
        BigDecimal totalAmount,
        BigDecimal refundAmount,
        LocalDateTime bookedAt,
        LocalDateTime cancelledAt,
        String soldByName,
        String cancelledByName,
        String seats
) {

    /** BK-0042 — the same reference the receipt file and the checkout panel use. */
    public String reference() {
        return String.format("BK-%04d", bookingId);
    }

    public LocalDateTime startsAt() {
        return LocalDateTime.of(showDate, startTime);
    }

    /**
     * A booking can only be cancelled if it is currently CONFIRMED and the screening
     * has not yet started (docs/PRD.md §13.1).
     */
    public boolean canCancel(LocalDateTime now) {
        return status == BookingStatus.CONFIRMED && now.isBefore(startsAt());
    }

    public String formattedSeats() {
        return seats == null || seats.isBlank() ? "—" : seats;
    }

    public String displayCancelledBy() {
        if (status != BookingStatus.CANCELLED) {
            return "—";
        }
        return cancelledByName != null && !cancelledByName.isBlank()
                ? cancelledByName
                : "System Expiry";
    }
}
