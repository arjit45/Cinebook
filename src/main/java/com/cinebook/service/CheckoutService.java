package com.cinebook.service;

import com.cinebook.dao.BookingDao;
import com.cinebook.dao.ConcessionDao;
import com.cinebook.dao.CustomerDao;
import com.cinebook.dao.ReceiptDao;
import com.cinebook.exception.BookingException;
import com.cinebook.exception.PaymentFailedException;
import com.cinebook.exception.ValidationException;
import com.cinebook.model.ConcessionItem;
import com.cinebook.model.Customer;
import com.cinebook.model.Receipt;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.PaymentMethod;
import com.cinebook.util.Db;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The checkout leg of a sale. Nothing here re-implements a rule the database
 * already owns: the food price snapshot lives in {@code booking_concession}, the
 * grand total and the payment row are written by {@code sp_confirm_booking}.
 *
 * <p>Customer and food are changed while the booking is HELD. The payment call is
 * the point of no return; the receipt is then read back from the persisted rows,
 * not assembled from the panel's state.
 */
public final class CheckoutService {

    private final Db db;
    private final CustomerDao customers;
    private final ConcessionDao concessions;
    private final BookingDao bookings;
    private final ReceiptDao receipts;
    private final BookingService bookingService;

    public CheckoutService(Db db) {
        this.db = db;
        this.customers = new CustomerDao(db);
        this.concessions = new ConcessionDao(db);
        this.bookings = new BookingDao();
        this.receipts = new ReceiptDao(db);
        this.bookingService = new BookingService(db);
    }

    /**
     * Abandons an unpaid hold and puts the seats back on sale — the counter's way
     * out when a customer walks away before paying.
     *
     * <p>The transaction itself lives in {@link BookingService}, which owns every
     * booking-lifecycle write; this is the checkout panel's route to it, carrying
     * the signed-in clerk so {@code cancelled_by_staff_id} names a person rather
     * than being left null the way the 60-second expiry job leaves it.
     *
     * @param releasedBy the clerk at the counter, never null
     * @return the number of seats returned to sale
     * @throws ValidationException if the hold was paid, already released or expired
     */
    public int release(int bookingId, StaffUser releasedBy)
            throws BookingException, SQLException {
        if (releasedBy == null) {
            throw new ValidationException(
                    "Releasing a hold needs the signed-in clerk for the audit trail.");
        }
        return bookingService.release(bookingId, releasedBy);
    }

    /** The default buyer of a hold: the seeded counter guest. */
    public Customer walkIn() throws BookingException, SQLException {
        int id = customers.walkInId().orElseThrow(() -> new ValidationException(
                "The walk-in customer is missing. Re-run db/02_seed.sql before selling."));
        return customers.findById(id).orElseThrow(() -> new ValidationException(
                "The walk-in customer is missing. Re-run db/02_seed.sql before selling."));
    }

    public List<Customer> findCustomers(String query) throws BookingException, SQLException {
        return customers.search(query);
    }

    public Customer createCustomer(String name, String email, String phone)
            throws BookingException, SQLException {
        return customers.create(name, email, phone);
    }

    /** Reassigns the hold. Once confirmed the buyer on the receipt is history. */
    public void assignCustomer(int bookingId, int customerId)
            throws BookingException, SQLException {
        int moved;
        try (Db.Handle handle = db.borrow()) {
            moved = bookings.assignCustomer(handle.connection(), bookingId, customerId);
        }
        if (moved == 0) {
            throw new ValidationException("This hold is no longer open. Reopen the screening.");
        }
    }

    public List<ConcessionItem> menu() throws SQLException {
        return concessions.activeItems();
    }

    public Map<Integer, Integer> selectedFood(int bookingId) throws SQLException {
        return concessions.quantities(bookingId);
    }

    /** One click adds one; a second click removes the line (quantity 0). */
    public void setFood(int bookingId, ConcessionItem item, int quantity)
            throws BookingException, SQLException {
        if (quantity < 0 || quantity > 20) {
            throw new ValidationException("Food quantity must be between 0 and 20.");
        }
        concessions.setQuantity(bookingId, item, quantity);
    }

    /**
     * Calls the procedure and returns the receipt it made possible. A procedure
     * SIGNAL (SQLState 45000) is the domain refusing the payment — not a broken
     * connection — so it is translated into the checked failure the panel shows.
     */
    public Receipt pay(int bookingId, PaymentMethod method)
            throws BookingException, SQLException {
        if (method == null) {
            throw new ValidationException("Choose Card, UPI or Cash before taking payment.");
        }
        // The procedure is the transaction. It borrows and returns on its own, so the
        // receipt read below is a second, separate borrow rather than a nested one.
        try (Db.Handle handle = db.borrow()) {
            bookings.confirm(handle.connection(), bookingId, method);
        } catch (SQLException failure) {
            if ("45000".equals(failure.getSQLState())) {
                throw new PaymentFailedException(failure.getMessage(), failure);
            }
            throw failure;
        }

        Receipt receipt = receipts.load(bookingId).orElseThrow(() ->
                new PaymentFailedException("The payment was taken but the receipt could not be read.",
                        null));
        if (receipt.method() == null) {
            throw new PaymentFailedException(
                    "The booking was confirmed without a payment row. Check the procedure.", null);
        }
        return receipt;
    }

    public Optional<Receipt> receipt(int bookingId) throws SQLException {
        return receipts.load(bookingId);
    }
}
