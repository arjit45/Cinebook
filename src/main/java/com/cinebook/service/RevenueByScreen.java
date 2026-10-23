package com.cinebook.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Query 2: the GROUP BY report. Confirmed bookings in the last 30 days,
 * aggregated per auditorium with ticket and food money kept apart.
 */
public final class RevenueByScreen implements ReportQuery {

    private static final String SQL = """
            SELECT sc.name                            AS screen,
                   COUNT(*)                           AS bookings,
                   COALESCE(SUM(tk.tickets), 0)       AS tickets_sold,
                   ROUND(SUM(b.ticket_amount), 2)     AS ticket_revenue,
                   ROUND(SUM(b.fnb_amount), 2)        AS fnb_revenue,
                   ROUND(SUM(b.total_amount), 2)      AS total_revenue
            FROM booking b
            JOIN showtime st ON st.showtime_id = b.showtime_id
            JOIN screen   sc ON sc.screen_id   = st.screen_id
            LEFT JOIN (SELECT booking_id, COUNT(*) AS tickets
                       FROM ticket GROUP BY booking_id) tk
                   ON tk.booking_id = b.booking_id
            WHERE b.status = 'CONFIRMED'
              AND st.show_date >= CURDATE() - INTERVAL 30 DAY
            GROUP BY sc.screen_id, sc.name
            ORDER BY total_revenue DESC
            """;

    @Override
    public String id() {
        return "2";
    }

    @Override
    public String title() {
        return "Revenue by screen";
    }

    @Override
    public String question() {
        return "Which auditorium took the money in the last 30 days?";
    }

    @Override
    public String sql() {
        return SQL;
    }

    @Override
    public List<String> map(ResultSet rows) throws SQLException {
        return List.of(
                ReportCells.text(rows, "screen"),
                ReportCells.count(rows, "bookings"),
                ReportCells.count(rows, "tickets_sold"),
                ReportCells.money(rows, "ticket_revenue"),
                ReportCells.money(rows, "fnb_revenue"),
                ReportCells.money(rows, "total_revenue"));
    }
}
