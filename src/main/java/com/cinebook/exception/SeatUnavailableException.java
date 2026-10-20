package com.cinebook.exception;

/** The seat was taken by someone else between the map load and the hold. */
public class SeatUnavailableException extends BookingException {

    public SeatUnavailableException(String message) {
        super(message);
    }
}
