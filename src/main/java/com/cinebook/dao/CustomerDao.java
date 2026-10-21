package com.cinebook.dao;

import com.cinebook.exception.ValidationException;
import com.cinebook.model.Customer;
import com.cinebook.util.Db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Customer lookups and the one inline create the checkout panel needs.
 *
 * <p>The walk-in row exists in the seed because {@code booking.customer_id} is
 * NOT NULL and the design chooses the buyer after the hold. The checkout panel
 * starts there and swaps the hold to a named customer when the clerk finds one.
 */
public final class CustomerDao {

    /** The seeded row the Walk-in chip fills (docs/PRD.md §5). */
    public static final String WALK_IN_EMAIL = "walkin@cinebook.local";

    private static final String COLUMNS =
            "customer_id, full_name, email, phone, joined_on";

    private static final String BY_EMAIL_SQL =
            "SELECT " + COLUMNS + " FROM customer WHERE email = ?";

    private static final String BY_ID_SQL =
            "SELECT " + COLUMNS + " FROM customer WHERE customer_id = ?";

    private static final String SEARCH_SQL = """
            SELECT customer_id, full_name, email, phone, joined_on
              FROM customer
             WHERE phone LIKE ?
                OR full_name LIKE ?
             ORDER BY full_name
             LIMIT 8
            """;

    private static final String INSERT_SQL = """
            INSERT INTO customer (full_name, email, phone, joined_on)
            VALUES (?, ?, ?, ?)
            """;

    private final Db db;

    public CustomerDao(Db db) {
        this.db = db;
    }

    /** The walk-in customer's id, or empty if the seed row was removed. */
    public OptionalInt walkInId() throws SQLException {
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(
                     "SELECT customer_id FROM customer WHERE email = ?")) {
            statement.setString(1, WALK_IN_EMAIL);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? OptionalInt.of(rows.getInt("customer_id"))
                        : OptionalInt.empty();
            }
        }
    }

    public Optional<Customer> findById(int customerId) throws SQLException, ValidationException {
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(BY_ID_SQL)) {
            statement.setInt(1, customerId);
            return one(statement);
        }
    }

    /** Phone prefix or any part of the name; the checkout search box drives this. */
    public List<Customer> search(String query) throws SQLException, ValidationException {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<Customer> matches = new ArrayList<>();
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(SEARCH_SQL)) {
            statement.setString(1, trimmed + "%");
            statement.setString(2, "%" + trimmed + "%");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    matches.add(map(rows));
                }
            }
        }
        return matches;
    }

    /** Creates a walk-up customer and returns the persisted row. */
    public Customer create(String fullName, String email, String phone)
            throws SQLException, ValidationException {
        String name = Customer.requireName(fullName);
        String mail = Customer.requireEmail(email);
        String number = Customer.requirePhone(phone);
        LocalDate today = LocalDate.now();

        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(
                     INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, mail);
            statement.setString(3, number);
            statement.setDate(4, java.sql.Date.valueOf(today));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("MySQL did not return a customer id");
                }
                return new Customer(keys.getInt(1), name, mail, number, today);
            }
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            throw new ValidationException("That email already belongs to a customer.");
        }
    }

    private static Optional<Customer> one(PreparedStatement statement)
            throws SQLException, ValidationException {
        try (ResultSet rows = statement.executeQuery()) {
            return rows.next() ? Optional.of(map(rows)) : Optional.empty();
        }
    }

    private static Customer map(ResultSet rows) throws SQLException, ValidationException {
        return new Customer(
                rows.getInt("customer_id"),
                rows.getString("full_name"),
                rows.getString("email"),
                rows.getString("phone"),
                rows.getDate("joined_on").toLocalDate());
    }
}
