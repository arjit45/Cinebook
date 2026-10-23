package com.cinebook.service;

import com.cinebook.dao.StaffDao;
import com.cinebook.exception.AuthException;
import com.cinebook.exception.ValidationException;
import com.cinebook.model.StaffUser;
import com.cinebook.util.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Reports service against the seeded database: the core analytical queries, the
 * read-only guard, and the shift summary's own-clerk scope.
 *
 * <p>The report totals are compared with a direct aggregate of the same rows, so
 * a mapping that drops or reorders a column fails here rather than in the UI.</p>
 */
@Tag("db")
class ReportServiceTest {

    private static final Db DB = Db.get();
    private static final AuthService AUTH = new AuthService(new StaffDao(DB));
    private static final ReportService REPORTS = new ReportService(DB, AUTH);

    private static final Map<String, Integer> COLUMN_COUNTS = Map.of(
            "2", 6,
            "5", 4,
            "4", 5,
            "10", 7,
            "6", 3);

    @BeforeEach
    void signInAsAdmin() throws Exception {
        AUTH.login("admin", "admin123");
    }

    @AfterAll
    static void closeConnection() {
        DB.close();
    }

    @Test
    void theFiveReportsReturnTheirColumnsAndRows() throws Exception {
        for (ReportQuery query : REPORTS.tabs()) {
            ReportTable table = REPORTS.run(query);
            assertEquals(COLUMN_COUNTS.get(query.id()), table.columns().size(),
                    query.title() + " must expose the columns its mapper reads");
            assertFalse(table.isEmpty(), query.title() + " has data in the seed");
            for (List<String> row : table.rows()) {
                assertEquals(table.columns().size(), row.size(),
                        query.title() + " returned a short row");
            }
        }
    }

    @Test
    void revenueByScreenAddsUpToTheConfirmedBookingsInTheWindow() throws Exception {
        ReportTable table = REPORTS.run(new RevenueByScreen());
        BigDecimal total = BigDecimal.ZERO;
        for (List<String> row : table.rows()) {
            BigDecimal tickets = money(row.get(3));
            BigDecimal food = money(row.get(4));
            BigDecimal rowTotal = money(row.get(5));
            assertEquals(0, tickets.add(food).compareTo(rowTotal),
                    "ticket and food revenue must equal the row total");
            total = total.add(rowTotal);
        }
        assertEquals(0, total.compareTo(confirmedRevenueLast30Days()),
                "the report total must match the confirmed bookings it summarises");
    }

    @Test
    void topFilmsAreAtMostThreeAndDescendingByRevenue() throws Exception {
        ReportTable table = REPORTS.run(new TopFilms());
        assertTrue(table.rows().size() <= 3, "the top-films tab is a top three");
        BigDecimal previous = null;
        for (List<String> row : table.rows()) {
            BigDecimal revenue = money(row.get(3));
            if (previous != null) {
                assertTrue(previous.compareTo(revenue) >= 0,
                        "top films must be ordered by revenue");
            }
            previous = revenue;
        }
    }

    @Test
    void occupancyPercentagesAreDescending() throws Exception {
        ReportTable table = REPORTS.run(new OccupancyReport());
        BigDecimal previous = null;
        for (List<String> row : table.rows()) {
            BigDecimal occupancy = new BigDecimal(row.get(6).replace("%", ""));
            if (previous != null) {
                assertTrue(previous.compareTo(occupancy) >= 0,
                        "occupancy must be ordered fullest first");
            }
            previous = occupancy;
        }
    }

    @Test
    void loyalCustomersAllHaveMoreThanThreeBookings() throws Exception {
        ReportTable table = REPORTS.run(new LoyalCustomers());
        for (List<String> row : table.rows()) {
            assertTrue(Integer.parseInt(row.get(1)) > 3,
                    "a loyal customer has more than three confirmed bookings");
        }
    }

    @Test
    void theConsoleRunsAReadQueryAndRefusesAWrite() throws Exception {
        QueryResult result = REPORTS.runSql("SELECT 1 AS one, 'two' AS two");
        assertEquals(List.of("one", "two"), result.columns());
        assertEquals(1, result.rows().size());
        assertEquals(List.of("1", "two"), result.rows().get(0));
        assertFalse(result.truncated());

        ValidationException refused = assertThrows(ValidationException.class,
                () -> REPORTS.runSql("DELETE FROM movie"));
        assertTrue(refused.getMessage().contains("read-only"), refused.getMessage());
    }

    @Test
    void shiftSummaryMatchesTheClerksOwnPaymentsAndRefunds() throws Exception {
        StaffUser admin = AUTH.currentUser().orElseThrow();
        LocalDate today = LocalDate.now();
        ShiftSummary summary = REPORTS.shiftSummary(today);

        Till till = directTill(admin.staffId(), today);
        assertEquals(till.bookings(), summary.bookings(), "bookings sold today");
        assertEquals(0, till.tickets().compareTo(summary.tickets()), "ticket money");
        assertEquals(0, till.food().compareTo(summary.food()), "food money");
        assertEquals(0, till.gross().compareTo(summary.gross()), "gross money");
        assertEquals(till.refunds(), summary.refundCount(), "refunds taken today");
        assertEquals(0, till.refunded().compareTo(summary.refunded()), "refund money");
        assertEquals(0, summary.gross().subtract(summary.refunded()).compareTo(summary.net()),
                "net is gross less refunded");
        assertEquals(today, summary.date());
        assertEquals("Meera Krishnan", summary.clerkName());
    }

    @Test
    void anOperatorCannotReadAReportRunTheConsoleOrCloseAShift() throws Exception {
        AuthService operatorAuth = new AuthService(new StaffDao(DB));
        operatorAuth.login("operator", "operator123");
        ReportService asOperator = new ReportService(DB, operatorAuth);

        AuthException report = assertThrows(AuthException.class,
                () -> asOperator.run(new RevenueByScreen()));
        assertTrue(report.getMessage().contains("administrator"), report.getMessage());
        assertThrows(AuthException.class, () -> asOperator.runSql("SELECT 1"));
        assertThrows(AuthException.class, () -> asOperator.shiftSummary(LocalDate.now()));
    }

    private static BigDecimal confirmedRevenueLast30Days() throws Exception {
        String sql = """
                SELECT ROUND(SUM(b.total_amount), 2) AS revenue
                FROM booking b
                JOIN showtime st ON st.showtime_id = b.showtime_id
                WHERE b.status = 'CONFIRMED'
                  AND st.show_date >= CURDATE() - INTERVAL 30 DAY
                """;
        try (Db.Handle handle = DB.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            BigDecimal revenue = rows.getBigDecimal("revenue");
            return revenue == null ? BigDecimal.ZERO : revenue;
        }
    }

    private static Till directTill(int staffId, LocalDate date) throws Exception {
        String payments = """
                SELECT COUNT(*) AS bookings,
                       ROUND(COALESCE(SUM(b.ticket_amount), 0), 2) AS tickets,
                       ROUND(COALESCE(SUM(b.fnb_amount), 0), 2) AS food,
                       ROUND(COALESCE(SUM(p.amount), 0), 2) AS gross
                FROM booking b
                JOIN payment p ON p.booking_id = b.booking_id
                WHERE b.sold_by_staff_id = ?
                  AND DATE(p.paid_at) = ?
                """;
        String refunds = """
                SELECT COUNT(*) AS refunds,
                       ROUND(COALESCE(SUM(refund_amount), 0), 2) AS refunded
                FROM booking
                WHERE cancelled_by_staff_id = ?
                  AND DATE(cancelled_at) = ?
                """;
        try (Db.Handle handle = DB.borrow()) {
            int bookings;
            BigDecimal tickets;
            BigDecimal food;
            BigDecimal gross;
            try (PreparedStatement statement = handle.connection().prepareStatement(payments)) {
                statement.setInt(1, staffId);
                statement.setDate(2, java.sql.Date.valueOf(date));
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    bookings = rows.getInt("bookings");
                    tickets = rows.getBigDecimal("tickets");
                    food = rows.getBigDecimal("food");
                    gross = rows.getBigDecimal("gross");
                }
            }
            int refundCount;
            BigDecimal refunded;
            try (PreparedStatement statement = handle.connection().prepareStatement(refunds)) {
                statement.setInt(1, staffId);
                statement.setDate(2, java.sql.Date.valueOf(date));
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    refundCount = rows.getInt("refunds");
                    refunded = rows.getBigDecimal("refunded");
                }
            }
            return new Till(bookings, tickets, food, gross, refundCount, refunded);
        }
    }

    private static BigDecimal money(String cell) {
        return new BigDecimal(cell.replace("₹", ""));
    }

    private record Till(int bookings, BigDecimal tickets, BigDecimal food, BigDecimal gross,
                        int refunds, BigDecimal refunded) {
    }
}
