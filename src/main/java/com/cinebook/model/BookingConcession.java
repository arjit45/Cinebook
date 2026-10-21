package com.cinebook.model;

import com.cinebook.util.Money;

import java.math.BigDecimal;

/** A persisted food line: {@code booking_concession}, with its own price snapshot. */
public final class BookingConcession {

    private final int bookingId;
    private final int itemId;
    private final int quantity;
    private final BigDecimal unitPrice;

    public BookingConcession(int bookingId, int itemId, int quantity, BigDecimal unitPrice) {
        this.bookingId = bookingId;
        this.itemId = itemId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public BigDecimal lineTotal() {
        return Money.round(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    public int bookingId() {
        return bookingId;
    }

    public int itemId() {
        return itemId;
    }

    public int quantity() {
        return quantity;
    }

    public BigDecimal unitPrice() {
        return unitPrice;
    }
}
