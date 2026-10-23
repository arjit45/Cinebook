package com.cinebook.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Query 5: the top-three report. Grossing means tickets and food, because that
 * is what the till banked in the last 30 days.
 */
public final class TopFilms implements ReportQuery {

    private static final String SQL = """
            SELECT m.title,
                   COUNT(*)                          AS bookings,
                   COALESCE(SUM(tk.tickets), 0)      AS tickets_sold,
                   ROUND(SUM(b.total_amount), 2)     AS revenue
            FROM booking b
            JOIN showtime st ON st.showtime_id = b.showtime_id
            JOIN movie    m  ON m.movie_id     = st.movie_id
            LEFT JOIN (SELECT booking_id, COUNT(*) AS tickets
                       FROM ticket GROUP BY booking_id) tk
                   ON tk.booking_id = b.booking_id
            WHERE b.status = 'CONFIRMED'
              AND st.show_date >= CURDATE() - INTERVAL 30 DAY
            GROUP BY m.movie_id, m.title
            ORDER BY revenue DESC
            LIMIT 3
            """;

    @Override
    public String id() {
        return "5";
    }

    @Override
    public String title() {
        return "Top films";
    }

    @Override
    public String question() {
        return "Which films grossed the most in the last 30 days?";
    }

    @Override
    public String sql() {
        return SQL;
    }

    @Override
    public List<String> map(ResultSet rows) throws SQLException {
        return List.of(
                ReportCells.text(rows, "title"),
                ReportCells.count(rows, "bookings"),
                ReportCells.count(rows, "tickets_sold"),
                ReportCells.money(rows, "revenue"));
    }
}
