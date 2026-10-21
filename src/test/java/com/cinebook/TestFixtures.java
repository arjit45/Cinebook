package com.cinebook;

import com.cinebook.exception.ValidationException;
import com.cinebook.model.Booking;
import com.cinebook.model.BoxOfficeOperator;
import com.cinebook.model.Customer;
import com.cinebook.model.Movie;
import com.cinebook.model.Screen;
import com.cinebook.model.Showtime;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.Certificate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One screening, one customer, one operator — built by hand so the unit tests
 * never touch MySQL, and always at the same wall-clock time so the refund
 * boundary can be asserted exactly.
 */
public final class TestFixtures {

    /** The film starts at 21:00 on this date; every refund test measures from here. */
    public static final LocalDateTime SHOW_START = LocalDateTime.of(2026, 10, 24, 21, 0);

    private TestFixtures() {
    }

    public static Movie movie() {
        return new Movie(1, "Blue Meridian", "Drama", "English", 128,
                LocalDate.of(2026, 3, 6), Certificate.UA, new BigDecimal("8.1"));
    }

    public static Screen screen() {
        return new Screen(1, "Audi 1", 96);
    }

    public static Showtime showtime() {
        return showtime(new BigDecimal("180.00"));
    }

    public static Showtime showtime(BigDecimal basePrice) {
        return new Showtime(10, movie(), screen(), SHOW_START.toLocalDate(),
                SHOW_START.toLocalTime(), basePrice);
    }

    public static Customer customer() throws ValidationException {
        return new Customer(1, "Asha Rao", "asha@example.com", "9876543210",
                LocalDate.of(2026, 1, 5));
    }

    public static StaffUser operator() {
        return new BoxOfficeOperator(2, "operator", "Ravi Kumar", "unused", "unused");
    }

    /** A fresh HELD booking, ten minutes before the film is another matter entirely. */
    public static Booking booking() throws ValidationException {
        return new Booking(customer(), showtime(), operator(), LocalDateTime.of(2026, 10, 20, 12, 0));
    }
}
