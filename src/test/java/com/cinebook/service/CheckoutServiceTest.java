package com.cinebook.service;

import com.cinebook.dao.CustomerDao;
import com.cinebook.dao.SeatDao;
import com.cinebook.dao.ShowtimeDao;
import com.cinebook.dao.StaffDao;
import com.cinebook.exception.PaymentFailedException;
import com.cinebook.model.ConcessionItem;
import com.cinebook.model.Customer;
import com.cinebook.model.Receipt;
import com.cinebook.model.Seat;
import com.cinebook.model.Showtime;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.PaymentMethod;
import com.cinebook.util.Db;
import com.cinebook.util.Money;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sale after the hold: food written to the hold, the payment procedure, and
 * the receipt read back from the rows it wrote.
 *
 * <p>Runs against the real seed because the interesting rules are the database's —
 * {@code chk_booking_sum}, the payment unique key and the totals recomputed inside
 * {@code sp_confirm_booking}. A mock would only prove the mock was called. Every
 * test deletes the bookings and customers it created, and any menu price it
 * changed is put back, so the seed stays the seed for the UI tests.
 */
@Tag("db")
class CheckoutServiceTest {

    private static final Db DB = Db.get();
    private final List<Integer> createdBookings = new ArrayList<>();
    private final List<Integer> createdCustomers = new ArrayList<>();

    @AfterEach
    void removeCreatedRows() throws SQLException {
        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection()) {
            for (int bookingId : createdBookings) {
                execute(connection, "DELETE FROM booking WHERE booking_id = ?", bookingId);
            }
            for (int customerId : createdCustomers) {
                execute(connection, "DELETE FROM customer WHERE customer_id = ?", customerId);
            }
        } finally {
            createdBookings.clear();
            createdCustomers.clear();
        }
    }

    @AfterAll
    static void closeConnection() {
        DB.close();
    }

    @Test
    void payingWritesThePaymentAndAReceiptThatMatchesTheRows() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(40).get(0);
        List<Seat> picked = freeSeats(show, 2);
        BookingService.Held held = new BookingService(DB).hold(show, picked, operator());
        createdBookings.add(held.bookingId());

        CheckoutService checkout = new CheckoutService(DB);
        List<ConcessionItem> menu = checkout.menu();
        assertTrue(menu.size() >= 2, "the seed must stock at least two items");
        ConcessionItem popcorn = menu.get(0);
        ConcessionItem drink = menu.get(1);

        checkout.setFood(held.bookingId(), popcorn, 2);
        checkout.setFood(held.bookingId(), drink, 1);
        assertEquals(2, checkout.selectedFood(held.bookingId()).get(popcorn.itemId()),
                "the second popcorn is a quantity on the same line");

        BigDecimal expectedFnb = Money.sum(List.of(
                popcorn.priceFor(2), drink.priceFor(1)));
        Receipt receipt = checkout.pay(held.bookingId(), PaymentMethod.UPI);

        assertEquals(held.bookingId(), receipt.bookingId());
        assertEquals(PaymentMethod.UPI, receipt.method());
        assertEquals("TXN" + String.format("%08d", held.bookingId()), receipt.txnRef(),
                "the procedure writes the reference, not the panel");
        assertNotNull(receipt.paidAt(), "a paid receipt carries its timestamp");

        assertEquals(0, held.total().compareTo(receipt.ticketAmount()),
                "tickets must equal the hold's total");
        assertEquals(0, expectedFnb.compareTo(receipt.fnbAmount()),
                "food must equal the snapshotted lines");
        assertEquals(0, held.total().add(expectedFnb).compareTo(receipt.totalAmount()),
                "the grand total is the procedure's sum");
        assertEquals(2, receipt.seats().size());
        assertEquals(2, receipt.concessions().size());

        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT b.status, p.method, p.amount
                       FROM booking b
                       JOIN payment p ON p.booking_id = b.booking_id
                      WHERE b.booking_id = ?
                     """)) {
            statement.setInt(1, held.bookingId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next(), "the sale must have one payment row");
                assertEquals("CONFIRMED", row.getString("status"));
                assertEquals("UPI", row.getString("method"));
                assertEquals(0, receipt.totalAmount().compareTo(row.getBigDecimal("amount")));
            }
        }
    }

    @Test
    void receiptKeepsThePriceTheItemWasSoldAt() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(40).get(0);
        BookingService.Held held = new BookingService(DB).hold(show,
                freeSeats(show, 1), operator());
        createdBookings.add(held.bookingId());

        CheckoutService checkout = new CheckoutService(DB);
        ConcessionItem item = checkout.menu().get(0);
        checkout.setFood(held.bookingId(), item, 1);
        Receipt receipt = checkout.pay(held.bookingId(), PaymentMethod.CARD);

        try {
            try (Db.Handle handle = DB.borrow();
                 Connection connection = handle.connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE concession_item SET unit_price = unit_price + 100 WHERE item_id = ?")) {
                statement.setInt(1, item.itemId());
                statement.executeUpdate();
            }
            Receipt later = checkout.receipt(held.bookingId()).orElseThrow();
            assertEquals(0, receipt.fnbAmount().compareTo(later.fnbAmount()),
                    "a later menu price change must not restate a sold receipt");
            assertEquals(0, receipt.totalAmount().compareTo(later.totalAmount()));
        } finally {
            try (Db.Handle handle = DB.borrow();
                 Connection connection = handle.connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE concession_item SET unit_price = unit_price - 100 WHERE item_id = ?")) {
                statement.setInt(1, item.itemId());
                statement.executeUpdate();
            }
        }
    }

    @Test
    void aSecondPaymentIsRefusedAndLeavesOnePaymentRow() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(40).get(0);
        BookingService.Held held = new BookingService(DB).hold(show,
                freeSeats(show, 1), operator());
        createdBookings.add(held.bookingId());

        CheckoutService checkout = new CheckoutService(DB);
        checkout.pay(held.bookingId(), PaymentMethod.CASH);

        PaymentFailedException refused = assertThrows(PaymentFailedException.class,
                () -> checkout.pay(held.bookingId(), PaymentMethod.CASH));
        assertTrue(refused.getMessage().contains("Only a HELD booking"),
                "the message must explain why the second payment failed: " + refused.getMessage());

        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) AS n FROM payment WHERE booking_id = ?")) {
            statement.setInt(1, held.bookingId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(1, row.getInt("n"), "a refused payment must not add a row");
            }
        }
    }

    @Test
    void theClerkCanReplaceTheWalkInGuestBeforePayment() throws Exception {
        Showtime show = new ShowtimeDao(DB).upcoming(40).get(0);
        BookingService.Held held = new BookingService(DB).hold(show,
                freeSeats(show, 1), operator());
        createdBookings.add(held.bookingId());

        CheckoutService checkout = new CheckoutService(DB);
        String mail = "buyer" + System.nanoTime() + "@example.com";
        Customer buyer = checkout.createCustomer("Meera Shah", mail, "9876500011");
        createdCustomers.add(buyer.customerId());

        List<Customer> matches = checkout.findCustomers("Meera");
        assertTrue(matches.stream().anyMatch(match -> match.customerId() == buyer.customerId()),
                "a new customer must be findable by name straight away");

        checkout.assignCustomer(held.bookingId(), buyer.customerId());
        Receipt receipt = checkout.pay(held.bookingId(), PaymentMethod.CARD);

        assertEquals("Meera Shah", receipt.customerName());
        assertEquals("9876500011", receipt.customerPhone());

        try (Db.Handle handle = DB.borrow();
             Connection connection = handle.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT customer_id FROM booking WHERE booking_id = ?")) {
            statement.setInt(1, held.bookingId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(buyer.customerId(), row.getInt("customer_id"));
            }
        }
    }

    private static StaffUser operator() throws Exception {
        return new StaffDao(DB).findByUsername("operator").orElseThrow();
    }

    private static List<Seat> freeSeats(Showtime show, int count) throws Exception {
        SeatDao seats = new SeatDao(DB);
        Set<String> sold = seats.soldLabels(show.showtimeId());
        return seats.byScreen(show.screen().screenId()).stream()
                .filter(seat -> !sold.contains(seat.label()))
                .limit(count)
                .toList();
    }

    private static void execute(Connection connection, String sql, int id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }
}
