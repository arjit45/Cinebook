package com.cinebook.model;

import com.cinebook.model.enums.SeatClass;
import com.cinebook.util.Money;

import java.math.BigDecimal;

/** Base price, no multiplier (docs/PRD.md §13.1). */
public final class RegularSeat extends Seat {

    private static final BigDecimal MULTIPLIER = new BigDecimal("1.00");

    public RegularSeat(int seatId, char rowLabel, int seatNo) {
        super(seatId, rowLabel, seatNo, SeatClass.REGULAR);
    }

    @Override
    public BigDecimal priceFor(Showtime showtime) {
        return Money.times(showtime.basePrice(), MULTIPLIER);
    }
}
