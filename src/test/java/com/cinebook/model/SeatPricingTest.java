package com.cinebook.model;

import com.cinebook.TestFixtures;
import com.cinebook.model.enums.SeatClass;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Three subclasses, one contract. The multipliers are 1.00 / 1.25 / 1.60
 * (docs/PRD.md §13.1) and every answer is rounded to 2 dp with HALF_UP, which is
 * what keeps {@code booking.ticket_amount = SUM(ticket.price)} true to the paisa.
 */
class SeatPricingTest {

    @Test
    void eighteenHundredBaseGivesTheThreeDocumentedPrices() {
        Showtime showtime = TestFixtures.showtime(new BigDecimal("180.00"));

        assertEquals(new BigDecimal("180.00"), new RegularSeat(1, 'A', 1).priceFor(showtime));
        assertEquals(new BigDecimal("225.00"), new PremiumSeat(2, 'B', 2).priceFor(showtime));
        assertEquals(new BigDecimal("288.00"), new ReclinerSeat(3, 'C', 3).priceFor(showtime));
    }

    @Test
    void halfUpRoundingAtTheThirdDecimal() {
        Showtime showtime = TestFixtures.showtime(new BigDecimal("199.99"));

        // 199.99 * 1.25 = 249.9875 -> 249.99 ; 199.99 * 1.60 = 319.984 -> 319.98
        assertEquals(new BigDecimal("249.99"), new PremiumSeat(2, 'B', 2).priceFor(showtime));
        assertEquals(new BigDecimal("319.98"), new ReclinerSeat(3, 'C', 3).priceFor(showtime));
    }

    @Test
    void factoryBuildsTheSubclassNamedByTheColumn() {
        assertInstanceOf(RegularSeat.class, Seat.of(1, 'A', 1, SeatClass.REGULAR));
        assertInstanceOf(PremiumSeat.class, Seat.of(2, 'B', 2, SeatClass.PREMIUM));
        assertInstanceOf(ReclinerSeat.class, Seat.of(3, 'C', 3, SeatClass.RECLINER));
    }

    @Test
    void labelIsRowThenNumberWithoutPadding() {
        assertEquals("A7", new RegularSeat(1, 'A', 7).label());
        assertEquals("B12", new PremiumSeat(2, 'B', 12).label());
    }
}
