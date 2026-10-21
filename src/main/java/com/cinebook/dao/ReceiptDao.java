package com.cinebook.dao;

import com.cinebook.model.ConcessionLine;
import com.cinebook.model.Receipt;
import com.cinebook.model.enums.PaymentMethod;
import com.cinebook.model.enums.SeatClass;
import com.cinebook.util.Db;
import com.cinebook.util.Money;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a confirmed sale back as one immutable receipt. Three queries, not a join
 * across tickets and food, because joining the two one-to-many sides would
 * multiply the rows and quietly inflate the totals.
 */
public final class ReceiptDao {

    private static final String HEADER_SQL = """
            SELECT b.booking_id, b.ticket_amount, b.fnb_amount, b.total_amount,
                   c.full_name, c.phone,
                   m.title, sc.name AS screen_name, st.show_date, st.start_time,
                   p.method, p.txn_ref, p.paid_at
              FROM booking b
              JOIN customer c  ON c.customer_id = b.customer_id
              JOIN showtime st ON st.showtime_id = b.showtime_id
              JOIN movie m     ON m.movie_id = st.movie_id
              JOIN screen sc   ON sc.screen_id = st.screen_id
              LEFT JOIN payment p ON p.booking_id = b.booking_id
             WHERE b.booking_id = ?
            """;

    private static final String SEATS_SQL = """
            SELECT s.row_label, s.seat_no, s.seat_class, t.price
              FROM ticket t
              JOIN seat s ON s.seat_id = t.seat_id
             WHERE t.booking_id = ?
             ORDER BY s.row_label, s.seat_no
            """;

    private final Db db;
    private final ConcessionDao concessions;

    public ReceiptDao(Db db) {
        this.db = db;
        this.concessions = new ConcessionDao(db);
    }

    /**
     * Empty until the payment has been written.
     *
     * <p>One borrow for the whole receipt: the header, the seats and the food are
     * read on a single connection, so they cannot see three different moments.
     * The nested reads take that connection as a parameter rather than borrowing
     * again — the pool's one-borrow-per-unit-of-work rule refuses the nesting.
     */
    public Optional<Receipt> load(int bookingId) throws SQLException {
        try (Db.Handle handle = db.borrow()) {
            Connection connection = handle.connection();
            try (PreparedStatement statement = connection.prepareStatement(HEADER_SQL)) {
                statement.setInt(1, bookingId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    List<Receipt.SeatLine> seats = seats(connection, bookingId);
                    List<ConcessionLine> food = concessions.linesFor(connection, bookingId);
                    String method = row.getString("method");
                    return Optional.of(new Receipt(
                            row.getInt("booking_id"),
                            row.getString("full_name"),
                            row.getString("phone"),
                            row.getString("title"),
                            row.getString("screen_name"),
                            row.getDate("show_date").toLocalDate(),
                            row.getTime("start_time").toLocalTime(),
                            seats,
                            food,
                            Money.round(row.getBigDecimal("ticket_amount")),
                            Money.round(row.getBigDecimal("fnb_amount")),
                            Money.round(row.getBigDecimal("total_amount")),
                            method == null ? null : PaymentMethod.valueOf(method),
                            row.getString("txn_ref"),
                            row.getTimestamp("paid_at") == null
                                    ? null
                                    : row.getTimestamp("paid_at").toLocalDateTime()));
                }
            }
        }
    }

    private List<Receipt.SeatLine> seats(Connection connection, int bookingId) throws SQLException {
        List<Receipt.SeatLine> seats = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SEATS_SQL)) {
            statement.setInt(1, bookingId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    BigDecimal price = Money.round(rows.getBigDecimal("price"));
                    seats.add(new Receipt.SeatLine(
                            rows.getString("row_label") + rows.getInt("seat_no"),
                            SeatClass.valueOf(rows.getString("seat_class")),
                            price));
                }
            }
        }
        return seats;
    }
}
