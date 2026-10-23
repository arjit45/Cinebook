package com.cinebook.service;

import com.cinebook.exception.AuthException;
import com.cinebook.exception.ValidationException;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.PaymentMethod;
import com.cinebook.util.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Reports surface: five fixed queries, a read-only SQL console and the
 * shift summary.
 *
 * <p>Every entry point calls {@link AuthService#requireAdmin()} before it borrows
 * a connection. The rail hides Reports from an operator, but that is not the
 * control; a hand-built call to this service still fails the role check.</p>
 *
 * <p>The console is deliberately read-only. The application account has DML
 * rights, so the guard is not the only defence, but refusing a DELETE here is
 * what prevents accidental data loss during live queries. DML and DDL belong
 * in administrative tools, where the person typing knows they are changing the database.</p>
 */
public final class ReportService {

    private static final int REPORT_TIMEOUT_SECONDS = 10;
    private static final int CONSOLE_TIMEOUT_SECONDS = 5;

    private static final String SHIFT_METHODS_SQL = """
            SELECT p.method,
                   COUNT(*)                        AS bookings,
                   ROUND(SUM(b.ticket_amount), 2)  AS tickets,
                   ROUND(SUM(b.fnb_amount), 2)     AS food,
                   ROUND(SUM(p.amount), 2)         AS gross
            FROM booking b
            JOIN payment p ON p.booking_id = b.booking_id
            WHERE b.sold_by_staff_id = ?
              AND DATE(p.paid_at) = ?
            GROUP BY p.method
            ORDER BY FIELD(p.method, 'CARD', 'UPI', 'CASH'), p.method
            """;

    private static final String SHIFT_REFUNDS_SQL = """
            SELECT COUNT(*)                              AS refunds,
                   ROUND(COALESCE(SUM(refund_amount), 0), 2) AS refunded
            FROM booking
            WHERE cancelled_by_staff_id = ?
              AND DATE(cancelled_at) = ?
            """;

    private final Db db;
    private final AuthService auth;

    public ReportService(Db db, AuthService auth) {
        this.db = Objects.requireNonNull(db, "db");
        this.auth = Objects.requireNonNull(auth, "auth");
    }

    /** The five tabs; metadata only, so no connection is needed. */
    public List<ReportQuery> tabs() {
        return ReportCatalog.tabs();
    }

    /** The thirteen console presets, built from the same query registry as the tabs. */
    public List<SqlPreset> presets() {
        return ReportCatalog.presets();
    }

    public ReportTable run(ReportQuery query) throws AuthException, SQLException {
        auth.requireAdmin();
        Objects.requireNonNull(query, "query");
        try (Db.Handle handle = db.borrow()) {
            return read(handle.connection(), query.sql(), query::map,
                    query.emptyMessage(), REPORT_TIMEOUT_SECONDS);
        }
    }

    /**
     * Runs one read-only statement for the console.
     *
     * @throws ValidationException when the guard refuses the text
     */
    public QueryResult runSql(String rawSql)
            throws AuthException, ValidationException, SQLException {
        auth.requireAdmin();
        String sql = SqlGuard.validate(rawSql);
        long started = System.nanoTime();
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(sql)) {
            statement.setQueryTimeout(CONSOLE_TIMEOUT_SECONDS);
            statement.setMaxRows(SqlGuard.MAX_ROWS + 1);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> columns = columns(rows.getMetaData());
                List<List<String>> data = new ArrayList<>();
                boolean truncated = false;
                while (rows.next()) {
                    if (data.size() == SqlGuard.MAX_ROWS) {
                        truncated = true;
                        break;
                    }
                    data.add(readCells(rows, columns.size()));
                }
                long millis = (System.nanoTime() - started) / 1_000_000;
                return new QueryResult(columns, data, millis, truncated);
            }
        }
    }

    /**
     * Today's Z-report for the signed-in administrator. The clerk ID comes from
     * the session, never from a control, so the screen cannot ask for someone
     * else's till.
     */
    public ShiftSummary shiftSummary(LocalDate date)
            throws AuthException, ValidationException, SQLException {
        auth.requireAdmin();
        if (date == null) {
            throw new ValidationException("Choose a date for the shift summary.");
        }
        StaffUser clerk = auth.currentUser()
                .orElseThrow(() -> new AuthException("Sign in first."));

        try (Db.Handle handle = db.borrow()) {
            List<ShiftSummary.MethodTotal> methods = shiftMethods(
                    handle.connection(), clerk.staffId(), date);
            RefundTotals refunds = shiftRefunds(handle.connection(), clerk.staffId(), date);
            return new ShiftSummary(clerk.fullName(), date, methods,
                    refunds.count(), refunds.refunded());
        }
    }

    private static List<ShiftSummary.MethodTotal> shiftMethods(
            Connection connection, int staffId, LocalDate date) throws SQLException {
        List<ShiftSummary.MethodTotal> methods = new ArrayList<>();
        try (PreparedStatement statement =
                     connection.prepareStatement(SHIFT_METHODS_SQL)) {
            statement.setInt(1, staffId);
            statement.setDate(2, java.sql.Date.valueOf(date));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    methods.add(new ShiftSummary.MethodTotal(
                            PaymentMethod.valueOf(rows.getString("method")),
                            rows.getInt("bookings"),
                            rows.getBigDecimal("tickets"),
                            rows.getBigDecimal("food"),
                            rows.getBigDecimal("gross")));
                }
            }
        }
        return methods;
    }

    private static RefundTotals shiftRefunds(
            Connection connection, int staffId, LocalDate date) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement(SHIFT_REFUNDS_SQL)) {
            statement.setInt(1, staffId);
            statement.setDate(2, java.sql.Date.valueOf(date));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                BigDecimal refunded = rows.getBigDecimal("refunded");
                return new RefundTotals(rows.getInt("refunds"),
                        refunded == null ? BigDecimal.ZERO : refunded);
            }
        }
    }

    private static ReportTable read(Connection connection, String sql,
                                    RowMapper mapper, String emptyMessage,
                                    int timeoutSeconds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> columns = columns(rows.getMetaData());
                List<List<String>> data = new ArrayList<>();
                while (rows.next()) {
                    List<String> cells = mapper.map(rows);
                    if (cells.size() != columns.size()) {
                        throw new SQLException("A report row did not match its "
                                + columns.size() + " columns.");
                    }
                    data.add(cells);
                }
                return new ReportTable(columns, data, emptyMessage);
            }
        }
    }

    private static List<String> columns(ResultSetMetaData meta) throws SQLException {
        List<String> columns = new ArrayList<>(meta.getColumnCount());
        for (int index = 1; index <= meta.getColumnCount(); index++) {
            columns.add(meta.getColumnLabel(index));
        }
        return columns;
    }

    private static List<String> readCells(ResultSet rows, int count) throws SQLException {
        List<String> cells = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            Object value = rows.getObject(index);
            cells.add(value == null ? "—" : value.toString());
        }
        return cells;
    }

    @FunctionalInterface
    private interface RowMapper {
        List<String> map(ResultSet rows) throws SQLException;
    }

    private record RefundTotals(int count, BigDecimal refunded) {
    }
}
