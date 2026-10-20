package com.cinebook.model;

import com.cinebook.model.enums.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** UPI: the VPA and the reference number the customer can quote. */
public final class UpiPayment extends Payment {

    private final String vpa;
    private final String upiRef;

    public UpiPayment(BigDecimal amount, LocalDateTime paidAt, String vpa, String upiRef) {
        super(PaymentMethod.UPI, amount, paidAt);
        this.vpa = vpa;
        this.upiRef = upiRef;
    }

    @Override
    public String renderReceipt(Booking booking) {
        return "UPI " + vpa + "   ref " + upiRef + "   " + amount().toPlainString();
    }

    public String vpa() {
        return vpa;
    }

    public String upiRef() {
        return upiRef;
    }
}
