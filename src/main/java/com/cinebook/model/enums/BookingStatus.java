package com.cinebook.model.enums;

/** The three states a booking can be in. Transitions are enforced in {@code Booking}. */
public enum BookingStatus {
    HELD, CONFIRMED, CANCELLED
}
