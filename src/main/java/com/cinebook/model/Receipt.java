package com.cinebook.model;

import com.cinebook.model.enums.PaymentMethod;
import com.cinebook.model.enums.SeatClass;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * What the till prints: a snapshot of one confirmed sale, assembled from the
 * persisted rows rather than from the cart. Reading it back after the payment
 * makes the receipt agree with the database, including the totals
 * {@code sp_confirm_booking} wrote.
 */
public record Receipt(
        int bookingId,
        String customerName,
        String customerPhone,
        String movieTitle,
        String screenName,
        LocalDate showDate,
        LocalTime startTime,
        List<SeatLine> seats,
        List<ConcessionLine> concessions,
        BigDecimal ticketAmount,
        BigDecimal fnbAmount,
        BigDecimal totalAmount,
        PaymentMethod method,
        String txnRef,
        LocalDateTime paidAt) {

    /** One ticket as the receipt prints it. */
    public record SeatLine(String label, SeatClass seatClass, BigDecimal price) {
    }

    /** BK-0042, the name the receipt file uses too. */
    public String reference() {
        return String.format("BK-%04d", bookingId);
    }
}
