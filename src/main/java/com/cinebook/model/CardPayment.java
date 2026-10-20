package com.cinebook.model;

import com.cinebook.model.enums.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Card: store the last four digits and the authorisation code, never the PAN. */
public final class CardPayment extends Payment {

    private final String cardLast4;
    private final String authCode;

    public CardPayment(BigDecimal amount, LocalDateTime paidAt, String cardLast4, String authCode) {
        super(PaymentMethod.CARD, amount, paidAt);
        this.cardLast4 = cardLast4;
        this.authCode = authCode;
    }

    @Override
    public String renderReceipt(Booking booking) {
        return "CARD ****" + cardLast4 + "   auth " + authCode + "   " + amount().toPlainString();
    }

    public String cardLast4() {
        return cardLast4;
    }

    public String authCode() {
        return authCode;
    }
}
