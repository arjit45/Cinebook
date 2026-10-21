package com.cinebook.dao;

import com.cinebook.model.BookingSummary;
import com.cinebook.model.Seat;
import com.cinebook.model.Showtime;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.BookingStatus;
import com.cinebook.model.enums.PaymentMethod;
import com.cinebook.util.Money;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Writes the hold. The methods take a {@link Connection} rather than borrow one,
 * because the three statements are one transaction and the service — not a DAO —
 * decides where it begins and ends (docs/PRD.md §13.1).
 *
 * <p>The seats are what actually lock: {@code uq_seat_per_showtime} turns a
 * simultaneous hold into a duplicate key on the second insert, which is the race
 * the seat map refreshes for.
 */
public final class BookingDao {

    private static final String INSERT_BOOKING_SQL = """
            INSERT INTO booking
                (customer_id, showtime_id, sold_by_staff_id, status,
                 ticket_amount, fnb_amount, total_amount)
            VALUES (?, ?, ?, 'HELD', 0.00, 0.00, 0.00)
            """;

    private static final String INSERT_TICKET_SQL = """
            INSERT INTO ticket (booking_id, seat_id, showtime_id, price)
            VALUES (?, ?, ?, ?)
            """;

    /**
     * One statement, not three: chk_booking_sum sees the finished row, so tickets
     * and food have to be written together (docs/PRD.md §5).
     */
    private static final String WRITE_TOTALS_SQL = """
            UPDATE booking
               SET ticket_amount = ?, fnb_amount = 0.00, total_amount = ?
             WHERE booking_id = ?
            """;

    /** See {@link #markCancelled}: status, timestamp, refund and clerk in one write. */
    private static final String CANCEL_SQL = """
            UPDATE booking
               SET status = 'CANCELLED',
                   cancelled_at = NOW(),
                   refund_amount = 0.00,
                   cancelled_by_staff_id = ?
             WHERE booking_id = ?
               AND status = 'HELD'
            """;

    private static final String DELETE_TICKETS_SQL =
            "DELETE FROM ticket WHERE booking_id = ?";

    private static final String DELETE_CONCESSIONS_SQL =
            "DELETE FROM booking_concession WHERE booking_id = ?";

    private static final String CANCEL_CONFIRMED_SQL = """
            UPDATE booking
               SET status = 'CANCELLED',
                   cancelled_at = NOW(),
                   refund_amount = ?,
                   cancelled_by_staff_id = ?
             WHERE booking_id = ?
               AND status = 'CONFIRMED'
            """;

    private static final String EXPIRED_HOLD_IDS_SQL = """
            SELECT booking_id
              FROM booking
             WHERE status = 'HELD'
               AND booked_at < ?
             ORDER BY booking_id
            """;

    private static final String BASE_SELECT_BOOKINGS_SQL = """
            SELECT b.booking_id, b.customer_id, c.full_name AS customer_name, c.phone AS customer_phone,
                   b.showtime_id, m.title AS movie_title, sc.name AS screen_name,
                   st.show_date, st.start_time,
                   b.status, b.ticket_amount, b.fnb_amount, b.total_amount, b.refund_amount,
                   b.booked_at, b.cancelled_at,
                   su_sold.full_name AS sold_by_name,
                   su_canc.full_name AS cancelled_by_name,
                   GROUP_CONCAT(DISTINCT CONCAT(sn.row_label, sn.seat_no) ORDER BY sn.row_label, sn.seat_no SEPARATOR ', ') AS seats
              FROM booking b
              JOIN customer c ON c.customer_id = b.customer_id
              JOIN showtime st ON st.showtime_id = b.showtime_id
              JOIN movie m ON m.movie_id = st.movie_id
              JOIN screen sc ON sc.screen_id = st.screen_id
              JOIN staff_user su_sold ON su_sold.staff_id = b.sold_by_staff_id
              LEFT JOIN staff_user su_canc ON su_canc.staff_id = b.cancelled_by_staff_id
              LEFT JOIN ticket t ON t.booking_id = b.booking_id
              LEFT JOIN seat sn ON sn.seat_id = t.seat_id
            """;

    private static final String GROUP_BY_ORDER_LIMIT = """
             GROUP BY b.booking_id, b.customer_id, c.full_name, c.phone,
                      b.showtime_id, m.title, sc.name, st.show_date, st.start_time,
                      b.status, b.ticket_amount, b.fnb_amount, b.total_amount, b.refund_amount,
                      b.booked_at, b.cancelled_at,
                      su_sold.full_name, su_canc.full_name
             ORDER BY b.booking_id DESC
             LIMIT ?
            """;

    /** Inserts the HELD booking and returns its generated id. */
    public int insertHeld(Connection connection, int customerId, Showtime showtime,
                          StaffUser soldBy) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                INSERT_BOOKING_SQL, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, customerId);
            statement.setInt(2, showtime.showtimeId());
            statement.setInt(3, soldBy.staffId());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("MySQL did not return a booking id");
                }
                return keys.getInt(1);
            }
        }
    }

    /** One ticket per seat, with the class price frozen at hold time. */
    public void insertTickets(Connection connection, int bookingId, Showtime showtime,
                              Collection<Seat> seats) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TICKET_SQL)) {
            for (Seat seat : seats) {
                statement.setInt(1, bookingId);
                statement.setInt(2, seat.seatId());
                statement.setInt(3, showtime.showtimeId());
                statement.setBigDecimal(4, Money.round(seat.priceFor(showtime)));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Sets the two money columns in one statement so the CHECK passes. */
    public void writeTicketTotal(Connection connection, int bookingId, BigDecimal total)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(WRITE_TOTALS_SQL)) {
            statement.setBigDecimal(1, Money.round(total));
            statement.setBigDecimal(2, Money.round(total));
            statement.setInt(3, bookingId);
            statement.executeUpdate();
        }
    }

    /**
     * Reassigns a hold to the customer the clerk found. Only a HELD booking may move:
     * once paid, the buyer on the receipt is history.
     *
     * @return rows changed; 0 means the hold is no longer open
     */
    public int assignCustomer(Connection connection, int bookingId, int customerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE booking SET customer_id = ? "
                        + "WHERE booking_id = ? AND status = 'HELD'")) {
            statement.setInt(1, customerId);
            statement.setInt(2, bookingId);
            return statement.executeUpdate();
        }
    }

    /**
     * Calls {@code sp_confirm_booking}: it locks the booking, recomputes both totals
     * from the persisted lines and writes the payment row in one transaction. The
     * procedure, not Java, is the source of the amount paid.
     */
    public void confirm(Connection connection, int bookingId, PaymentMethod method)
            throws SQLException {
        try (CallableStatement call = connection.prepareCall("{CALL sp_confirm_booking(?, ?)}")) {
            call.setInt(1, bookingId);
            call.setString(2, method.name());
            call.execute();
        }
    }

    /**
     * Marks a HELD booking CANCELLED and reports how many rows changed — 0 meaning
     * the hold was already gone (paid, released, or expired under us).
     *
     * <p>Three things happen in this one statement, and all three are required:
     * {@code status} and {@code cancelled_at} move together because
     * {@code chk_booking_cancelled_at} refuses a CANCELLED row with no timestamp;
     * {@code refund_amount} is set to 0 because a hold was never charged, which
     * satisfies {@code chk_refund_amount} without touching the 90/50 policy that
     * only ever applies to a CONFIRMED booking; and {@code cancelled_by_staff_id}
     * records who did it.
     *
     * <p>The staff id is deliberately nullable and the null is load-bearing:
     * {@code db/04_queries.sql} reports clerk performance with
     * {@code WHERE cancelled_by_staff_id = @staff_id}, so the 60-second expiry job
     * must pass null or every automated sweep would be charged to whichever clerk
     * happened to be signed in.
     *
     * <p>The money columns are left alone on purpose. The seed's own comment is the
     * reason: <em>"ticket_amount is already snapshotted, so the money survives the
     * ticket deletion that follows."</em>
     *
     * @return 1 if the hold was open, 0 if it was not
     */
    public int markCancelled(Connection connection, int bookingId, StaffUser cancelledBy)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CANCEL_SQL)) {
            if (cancelledBy == null) {
                statement.setNull(1, Types.INTEGER);
            } else {
                statement.setInt(1, cancelledBy.staffId());
            }
            statement.setInt(2, bookingId);
            return statement.executeUpdate();
        }
    }

    /**
     * Frees the seats. {@code soldLabels()} reads the {@code ticket} table alone and
     * never joins {@code booking.status}, so tickets left on a cancelled booking
     * would keep those seats looking sold forever — and
     * {@code uq_seat_per_showtime} would refuse anyone trying to rebook them.
     */
    public int deleteTickets(Connection connection, int bookingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_TICKETS_SQL)) {
            statement.setInt(1, bookingId);
            return statement.executeUpdate();
        }
    }

    /**
     * Drops any food written during the hold. Lives here rather than in
     * {@link ConcessionDao} because it is the third statement of this transaction:
     * one unit of work, one borrowed connection, one commit.
     */
    public int deleteConcessions(Connection connection, int bookingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_CONCESSIONS_SQL)) {
            statement.setInt(1, bookingId);
            return statement.executeUpdate();
        }
    }

    /**
     * Cancels a CONFIRMED booking and records its refund amount and staff member.
     * Only CONFIRMED bookings can be cancelled through this path (HELD bookings use
     * {@link #markCancelled}).
     *
     * @return 1 if cancelled, 0 if not CONFIRMED (already cancelled or expired)
     */
    public int cancelConfirmed(Connection connection, int bookingId, BigDecimal refundAmount,
                               StaffUser cancelledBy) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CANCEL_CONFIRMED_SQL)) {
            statement.setBigDecimal(1, Money.round(refundAmount));
            if (cancelledBy == null) {
                statement.setNull(2, Types.INTEGER);
            } else {
                statement.setInt(2, cancelledBy.staffId());
            }
            statement.setInt(3, bookingId);
            return statement.executeUpdate();
        }
    }

    /**
     * Finds the ids of all HELD bookings that have exceeded the TTL cutoff.
     */
    public List<Integer> findExpiredHoldIds(Connection connection, LocalDateTime cutoff)
            throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(EXPIRED_HOLD_IDS_SQL)) {
            statement.setTimestamp(1, Timestamp.valueOf(cutoff));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt("booking_id"));
                }
            }
        }
        return ids;
    }

    /**
     * Lists the most recent bookings up to limit, newest first.
     */
    public List<BookingSummary> listRecent(Connection connection, int limit) throws SQLException {
        String sql = BASE_SELECT_BOOKINGS_SQL + GROUP_BY_ORDER_LIMIT;
        List<BookingSummary> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(mapBookingSummary(rs));
                }
            }
        }
        return results;
    }

    /**
     * Searches bookings by customer name, phone number, or booking reference id.
     */
    public List<BookingSummary> search(Connection connection, String query, int limit)
            throws SQLException {
        if (query == null || query.isBlank()) {
            return listRecent(connection, limit);
        }
        String trimmed = query.trim();
        String wild = "%" + trimmed + "%";
        String sql = BASE_SELECT_BOOKINGS_SQL
                + " WHERE c.full_name LIKE ? OR c.phone LIKE ? OR CAST(b.booking_id AS CHAR) = ? "
                + GROUP_BY_ORDER_LIMIT;
        List<BookingSummary> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, wild);
            statement.setString(2, wild);
            statement.setString(3, trimmed);
            statement.setInt(4, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(mapBookingSummary(rs));
                }
            }
        }
        return results;
    }

    /**
     * Finds a single booking by ID.
     */
    public Optional<BookingSummary> findById(Connection connection, int bookingId)
            throws SQLException {
        String sql = BASE_SELECT_BOOKINGS_SQL
                + " WHERE b.booking_id = ? "
                + " GROUP BY b.booking_id, b.customer_id, c.full_name, c.phone, "
                + "          b.showtime_id, m.title, sc.name, st.show_date, st.start_time, "
                + "          b.status, b.ticket_amount, b.fnb_amount, b.total_amount, b.refund_amount, "
                + "          b.booked_at, b.cancelled_at, "
                + "          su_sold.full_name, su_canc.full_name";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, bookingId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapBookingSummary(rs));
                }
            }
        }
        return Optional.empty();
    }

    private BookingSummary mapBookingSummary(ResultSet rs) throws SQLException {
        Timestamp bookedAtTs = rs.getTimestamp("booked_at");
        Timestamp cancelledAtTs = rs.getTimestamp("cancelled_at");
        LocalDate showDate = rs.getObject("show_date", LocalDate.class);
        LocalTime startTime = rs.getObject("start_time", LocalTime.class);
        return new BookingSummary(
                rs.getInt("booking_id"),
                rs.getInt("customer_id"),
                rs.getString("customer_name"),
                rs.getString("customer_phone"),
                rs.getInt("showtime_id"),
                rs.getString("movie_title"),
                rs.getString("screen_name"),
                showDate,
                startTime,
                BookingStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("ticket_amount"),
                rs.getBigDecimal("fnb_amount"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("refund_amount"),
                bookedAtTs != null ? bookedAtTs.toLocalDateTime() : null,
                cancelledAtTs != null ? cancelledAtTs.toLocalDateTime() : null,
                rs.getString("sold_by_name"),
                rs.getString("cancelled_by_name"),
                rs.getString("seats")
        );
    }
}
