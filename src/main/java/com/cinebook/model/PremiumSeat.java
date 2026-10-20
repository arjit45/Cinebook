package com.cinebook.model;

import com.cinebook.model.enums.SeatClass;
import com.cinebook.util.Money;

import java.math.BigDecimal;

/** 1.25x the base price (docs/PRD.md §13.1). */
public final class PremiumSeat extends Seat {

    private static final BigDecimal MULTIPLIER = new BigDecimal("1.25");

    public PremiumSeat(int seatId, char rowLabel, int seatNo) {
        super(seatId, rowLabel, seatNo, SeatClass.PREMIUM);
    }

    @Override
    public BigDecimal priceFor(Showtime showtime) {
        return Money.times(showtime.basePrice(), MULTIPLIER);
    }
}
