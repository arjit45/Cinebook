package com.cinebook.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** One screening of one film in one auditorium. */
public final class Showtime {

    private final int showtimeId;
    private final Movie movie;
    private final Screen screen;
    private final LocalDate showDate;
    private final LocalTime startTime;
    private final BigDecimal basePrice;
    private final int seatsSold;

    public Showtime(int showtimeId, Movie movie, Screen screen, LocalDate showDate,
                    LocalTime startTime, BigDecimal basePrice) {
        this(showtimeId, movie, screen, showDate, startTime, basePrice, 0);
    }

    public Showtime(int showtimeId, Movie movie, Screen screen, LocalDate showDate,
                    LocalTime startTime, BigDecimal basePrice, int seatsSold) {
        this.showtimeId = showtimeId;
        this.movie = movie;
        this.screen = screen;
        this.showDate = showDate;
        this.startTime = startTime;
        this.basePrice = basePrice;
        this.seatsSold = seatsSold;
    }

    /** The instant the film begins, which is what the refund boundary is measured from. */
    public LocalDateTime startsAt() {
        return LocalDateTime.of(showDate, startTime);
    }

    public boolean isSoldOut() {
        return seatsSold >= screen.seatCapacity();
    }

    public int showtimeId() {
        return showtimeId;
    }

    public Movie movie() {
        return movie;
    }

    public Screen screen() {
        return screen;
    }

    public LocalDate showDate() {
        return showDate;
    }

    public LocalTime startTime() {
        return startTime;
    }

    public BigDecimal basePrice() {
        return basePrice;
    }

    public int seatsSold() {
        return seatsSold;
    }
}
