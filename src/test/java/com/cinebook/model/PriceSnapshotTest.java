package com.cinebook.model;

import com.cinebook.TestFixtures;
import com.cinebook.model.enums.ItemCategory;
import com.cinebook.model.enums.SeatClass;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The invariant every report depends on (docs/PRD.md §14.2): once sold, a line
 * keeps the price it was sold at. A snapshot that an unrelated price change can
 * disturb is just a copy.
 *
 * <p>Both changes are simulated the way the real ones happen — by handing the
 * booking a <em>new</em> price source, because the catalogue is edited while the
 * sale is already written.
 */
class PriceSnapshotTest {

    @Test
    void aSoldTicketKeepsItsPriceWhenTheBasePriceChanges() throws Exception {
        Booking booking = TestFixtures.booking();
        Seat seat = Seat.of(1, 'A', 7, SeatClass.REGULAR);

        booking.addSeat(seat, TestFixtures.showtime(new BigDecimal("180.00")).basePrice());
        assertEquals(new BigDecimal("180.00"), booking.tickets().get(0).price());

        // The programme is edited after the sale. The ticket must not move.
        Showtime repriced = TestFixtures.showtime(new BigDecimal("250.00"));
        booking.recalculateTotal();

        assertEquals(new BigDecimal("180.00"), booking.tickets().get(0).price(),
                "a ticket is a receipt, not a live lookup");
        assertEquals(new BigDecimal("180.00"), booking.ticketAmount());
        assertEquals(new BigDecimal("250.00"), seat.priceFor(repriced),
                "the new price applies to new sales only");
    }

    @Test
    void aSoldFoodLineKeepsItsPriceWhenThePriceListChanges() throws Exception {
        Booking booking = TestFixtures.booking();
        ConcessionItem popcornAt120 = new ConcessionItem(
                1, "Salted Popcorn", ItemCategory.SNACK, new BigDecimal("120.00"), true);

        booking.addConcession(popcornAt120, 2);
        assertEquals(new BigDecimal("120.00"), booking.concessions().get(0).unitPrice());

        // Someone raises the price in the catalogue the next morning.
        ConcessionItem popcornAt150 = new ConcessionItem(
                1, "Salted Popcorn", ItemCategory.SNACK, new BigDecimal("150.00"), true);
        booking.recalculateTotal();

        assertEquals(new BigDecimal("120.00"), booking.concessions().get(0).unitPrice());
        assertEquals(new BigDecimal("240.00"), booking.fnbAmount());
        assertEquals(new BigDecimal("150.00"), popcornAt150.unitPrice(),
                "the catalogue moves; the sold line does not");
    }

    @Test
    void bookingConcessionCarriesItsOwnSnapshotToo() {
        BookingConcession persisted = new BookingConcession(1, 1, 2, new BigDecimal("120.00"));
        assertEquals(new BigDecimal("240.00"), persisted.lineTotal());
    }
}
