package com.cinebook.exception;

/** The payment leg failed; nothing is committed and the cart survives. */
public class PaymentFailedException extends BookingException {

    public PaymentFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
