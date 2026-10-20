package com.cinebook.model;

import com.cinebook.model.enums.PaymentMethod;
import com.cinebook.util.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Cash: the amount tendered is part of the record, because change is a till fact. */
public final class CashPayment extends Payment {

    private final BigDecimal tendered;

    public CashPayment(BigDecimal amount, LocalDateTime paidAt, BigDecimal tendered) {
        super(PaymentMethod.CASH, amount, paidAt);
        this.tendered = tendered;
    }

    public BigDecimal change() {
        return Money.round(tendered.subtract(amount()));
    }

    @Override
    public String renderReceipt(Booking booking) {
        return "CASH   tendered " + tendered.toPlainString()
                + "   change " + change().toPlainString();
    }

    public BigDecimal tendered() {
        return tendered;
    }
}
