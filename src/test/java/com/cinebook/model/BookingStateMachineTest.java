package com.cinebook.model;

import com.cinebook.TestFixtures;
import com.cinebook.exception.ValidationException;
import com.cinebook.model.enums.BookingStatus;
import com.cinebook.model.enums.ItemCategory;
import com.cinebook.model.enums.SeatClass;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Illegal transitions must throw rather than mutate: a booking that quietly
 * accepts a second confirm is a booking whose audit trail lies.
 */
class BookingStateMachineTest {

    @Test
    void holdBecomesConfirmedOnce() throws Exception {
        Booking booking = TestFixtures.booking();
        booking.confirm();
        assertEquals(BookingStatus.CONFIRMED, booking.status());

        ValidationException error = assertThrows(ValidationException.class, booking::confirm);
        assertTrue(error.getMessage().contains("Only a HELD"), error.getMessage());
    }

    @Test
    void cancellingTwiceIsRefused() throws Exception {
        Booking booking = TestFixtures.booking();
        booking.cancel(TestFixtures.operator(), TestFixtures.SHOW_START.minusHours(3));

        ValidationException error = assertThrows(ValidationException.class, booking::cancel);
        assertTrue(error.getMessage().contains("already cancelled"), error.getMessage());
    }

    @Test
    void seatsCannotBeAddedAfterConfirmation() throws Exception {
        Booking booking = TestFixtures.booking();
        booking.confirm();

        assertThrows(ValidationException.class, () ->
                booking.addSeat(Seat.of(1, 'A', 1, SeatClass.REGULAR), new BigDecimal("180.00")));
    }

    @Test
    void theSameSeatTwiceIsRefused() throws Exception {
        Booking booking = TestFixtures.booking();
        booking.addSeat(Seat.of(1, 'A', 7, SeatClass.REGULAR), new BigDecimal("180.00"));

        ValidationException error = assertThrows(ValidationException.class, () ->
                booking.addSeat(Seat.of(1, 'A', 7, SeatClass.REGULAR), new BigDecimal("180.00")));
        assertTrue(error.getMessage().contains("A7"), error.getMessage());
    }

    @Test
    void zeroQuantityFoodIsRefused() throws Exception {
        Booking booking = TestFixtures.booking();
        ConcessionItem popcorn = new ConcessionItem(
                1, "Salted Popcorn", ItemCategory.SNACK, new BigDecimal("120.00"), true);

        assertThrows(ValidationException.class, () -> booking.addConcession(popcorn, 0));
        assertTrue(booking.concessions().isEmpty(), "a refused line must not be added");
    }

    @Test
    void totalsRecomputeFromTheLines() throws Exception {
        Booking booking = TestFixtures.booking();
        booking.addSeat(Seat.of(1, 'A', 1, SeatClass.REGULAR), new BigDecimal("200.00"));
        booking.addSeat(Seat.of(2, 'B', 2, SeatClass.PREMIUM), new BigDecimal("250.00"));
        booking.addConcession(new ConcessionItem(
                1, "Salted Popcorn", ItemCategory.SNACK, new BigDecimal("120.00"), true), 2);

        assertEquals(new BigDecimal("450.00"), booking.ticketAmount());
        assertEquals(new BigDecimal("240.00"), booking.fnbAmount());
        assertEquals(new BigDecimal("690.00"), booking.totalAmount());

        booking.removeSeat(Seat.of(2, 'B', 2, SeatClass.PREMIUM));
        assertEquals(new BigDecimal("200.00"), booking.ticketAmount());
        assertEquals(new BigDecimal("440.00"), booking.totalAmount(),
                "removing a seat must not leave a stale total behind");
    }

    @Test
    void aHoldExpiresAfterTenMinutes() throws Exception {
        Booking booking = TestFixtures.booking();
        java.time.LocalDateTime booked = booking.bookedAt();

        assertFalse(booking.isExpired(booked.plusMinutes(9)));
        assertTrue(booking.isExpired(booked.plusMinutes(11)));
    }

    @Test
    void refundCannotExceedTheTicketAmount() throws Exception {
        Booking booking = TestFixtures.booking();
        booking.addSeat(Seat.of(1, 'A', 1, SeatClass.REGULAR), new BigDecimal("180.00"));
        booking.cancel(TestFixtures.operator(), TestFixtures.SHOW_START.minusHours(3));

        assertThrows(ValidationException.class, () -> booking.recordRefund(new BigDecimal("180.01")));
        booking.recordRefund(new BigDecimal("162.00"));
        assertEquals(new BigDecimal("162.00"), booking.refundAmount());
    }
}
