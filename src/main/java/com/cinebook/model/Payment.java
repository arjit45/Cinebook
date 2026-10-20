package com.cinebook.model;

import com.cinebook.model.enums.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Money received. The method-specific detail lives in the subclasses, so a
 * receipt can print "VPA 9F3K2" for UPI and "tendered 1000, change 80" for cash
 * without a switch statement in the writer.
 */
public abstract class Payment {

    private final BigDecimal amount;
    private final LocalDateTime paidAt;
    private final PaymentMethod method;

    protected Payment(PaymentMethod method, BigDecimal amount, LocalDateTime paidAt) {
        this.method = method;
        this.amount = amount;
        this.paidAt = paidAt;
    }

    /** One line describing how the money arrived, for the receipt footer. */
    public abstract String renderReceipt(Booking booking);

    public BigDecimal amount() {
        return amount;
    }

    public LocalDateTime paidAt() {
        return paidAt;
    }

    public PaymentMethod method() {
        return method;
    }
}
