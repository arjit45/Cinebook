package com.cinebook.exception;

/** A value or a transition the domain rules refuse, with a message fit for the UI. */
public class ValidationException extends BookingException {

    public ValidationException(String message) {
        super(message);
    }
}
