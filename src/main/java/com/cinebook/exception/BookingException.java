package com.cinebook.exception;

/**
 * Base of the checked domain failures: anything the user did that the rules
 * refuse. Checked on purpose — a service method that can fail because of user
 * input should say so in its signature.
 */
public class BookingException extends Exception {

    public BookingException(String message) {
        super(message);
    }

    public BookingException(String message, Throwable cause) {
        super(message, cause);
    }
}
