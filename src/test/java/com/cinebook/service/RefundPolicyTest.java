package com.cinebook.service;

import com.cinebook.TestFixtures;
import com.cinebook.exception.ValidationException;
import com.cinebook.model.Booking;
import com.cinebook.model.ConcessionItem;
import com.cinebook.model.Seat;
import com.cinebook.model.enums.ItemCategory;
import com.cinebook.model.enums.SeatClass;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 90 / 50 boundary, tested at the minute it flips. This is the rule most
 * likely to be quiet and wrong by a few rupees, so each case names the boundary
 * it covers (docs/PRD.md §13.1).
 */
class RefundPolicyTest {

    private static final BigDecimal FIVE_HUNDRED = new BigDecimal("500.00");

    @Test
    void threeHoursOutRefundsNinetyPercent() throws Exception {
        BigDecimal refund = RefundPolicy.ticketRefund(
                FIVE_HUNDRED, TestFixtures.SHOW_START, TestFixtures.SHOW_START.minusHours(3));
        assertEquals(new BigDecimal("450.00"), refund);
    }

    @Test
    void hundredMinutesOutRefundsHalf() throws Exception {
        BigDecimal refund = RefundPolicy.ticketRefund(
                FIVE_HUNDRED, TestFixtures.SHOW_START, TestFixtures.SHOW_START.minusMinutes(100));
        assertEquals(new BigDecimal("250.00"), refund);
    }

    @Test
    void exactlyTwoHoursCountsAsLate() throws Exception {
        BigDecimal refund = RefundPolicy.ticketRefund(
                FIVE_HUNDRED, TestFixtures.SHOW_START, TestFixtures.SHOW_START.minusHours(2));
        assertEquals(new BigDecimal("250.00"), refund,
                "exactly 2 h is inside the window; 90% would be the off-by-a-minute bug");
    }

    @Test
    void oneMinuteEitherSideOfTheBoundary() throws Exception {
        BigDecimal justEarly = RefundPolicy.ticketRefund(
                FIVE_HUNDRED, TestFixtures.SHOW_START,
                TestFixtures.SHOW_START.minusHours(2).minusMinutes(1));
        BigDecimal justLate = RefundPolicy.ticketRefund(
                FIVE_HUNDRED, TestFixtures.SHOW_START,
                TestFixtures.SHOW_START.minusHours(2).plusMinutes(1));
        assertEquals(new BigDecimal("450.00"), justEarly);
        assertEquals(new BigDecimal("250.00"), justLate);
    }

    @Test
    void startedShowIsRefused() {
        ValidationException error = assertThrows(ValidationException.class, () ->
                RefundPolicy.ticketRefund(FIVE_HUNDRED, TestFixtures.SHOW_START,
                        TestFixtures.SHOW_START.plusMinutes(1)));
        assertTrue(error.getMessage().contains("already started"), error.getMessage());
    }

    @Test
    void foodIsExcludedFromTheRefundBase() throws Exception {
        Booking booking = TestFixtures.booking();
        booking.addSeat(seat('A', 1, SeatClass.REGULAR), new BigDecimal("200.00"));
        booking.addSeat(seat('B', 2, SeatClass.PREMIUM), new BigDecimal("300.00"));
        booking.addConcession(
                new ConcessionItem(1, "Salted Popcorn", ItemCategory.SNACK, new BigDecimal("120.00"), true),
                2);
        booking.confirm();

        BigDecimal refund = RefundPolicy.refundFor(booking, TestFixtures.SHOW_START.minusHours(3));

        assertEquals(new BigDecimal("500.00"), booking.ticketAmount());
        assertEquals(new BigDecimal("240.00"), booking.fnbAmount());
        assertEquals(new BigDecimal("740.00"), booking.totalAmount());
        assertEquals(new BigDecimal("450.00"), refund,
                "90% of the tickets, not of the popcorn");
    }

    @Test
    void heldBookingHasNothingToRefund() throws Exception {
        Booking held = TestFixtures.booking();
        held.addSeat(seat('A', 1, SeatClass.REGULAR), new BigDecimal("180.00"));

        ValidationException error = assertThrows(ValidationException.class, () ->
                RefundPolicy.refundFor(held, TestFixtures.SHOW_START.minusHours(5)));
        assertTrue(error.getMessage().contains("HELD"), error.getMessage());
    }

    private static Seat seat(char row, int number, SeatClass seatClass) {
        return Seat.of(number, row, number, seatClass);
    }
}
