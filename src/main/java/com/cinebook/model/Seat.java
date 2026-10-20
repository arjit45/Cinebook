package com.cinebook.model;

import com.cinebook.model.enums.SeatClass;

import java.math.BigDecimal;

/**
 * A physical seat. The class is a real subclass rather than a multiplier on a
 * column, so the price rule cannot be changed by editing data — the seat map
 * builds {@code RegularSeat}/{@code PremiumSeat}/{@code ReclinerSeat} from the
 * {@code seat.seat_class} enum and each answers {@link #priceFor(Showtime)}.
 */
public abstract class Seat {

    private final int seatId;
    private final char rowLabel;
    private final int seatNo;
    private final SeatClass seatClass;

    protected Seat(int seatId, char rowLabel, int seatNo, SeatClass seatClass) {
        this.seatId = seatId;
        this.rowLabel = rowLabel;
        this.seatNo = seatNo;
        this.seatClass = seatClass;
    }

    /** Builds the right subclass for a row read from the database. */
    public static Seat of(int seatId, char rowLabel, int seatNo, SeatClass seatClass) {
        return switch (seatClass) {
            case REGULAR -> new RegularSeat(seatId, rowLabel, seatNo);
            case PREMIUM -> new PremiumSeat(seatId, rowLabel, seatNo);
            case RECLINER -> new ReclinerSeat(seatId, rowLabel, seatNo);
        };
    }

    /** This seat's price for a given screening, rounded to 2 dp. */
    public abstract BigDecimal priceFor(Showtime showtime);

    /** What the receipt prints: A7, B12. */
    public String label() {
        return rowLabel + String.valueOf(seatNo);
    }

    public int seatId() {
        return seatId;
    }

    public char rowLabel() {
        return rowLabel;
    }

    public int seatNo() {
        return seatNo;
    }

    public SeatClass seatClass() {
        return seatClass;
    }
}
