package com.cinebook.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Query 6: HAVING. More than three confirmed bookings, ranked by what the patron
 * has spent at this counter — food included, because it was taken here too.
 */
public final class LoyalCustomers implements ReportQuery {

    private static final String SQL = """
            SELECT c.full_name,
                   COUNT(*)                          AS bookings,
                   ROUND(SUM(b.total_amount), 2)     AS total_spent
            FROM customer c
            JOIN booking b ON b.customer_id = c.customer_id
                          AND b.status = 'CONFIRMED'
            GROUP BY c.customer_id, c.full_name
            HAVING COUNT(*) > 3
            ORDER BY total_spent DESC
            """;

    @Override
    public String id() {
        return "6";
    }

    @Override
    public String title() {
        return "Loyal customers";
    }

    @Override
    public String question() {
        return "Who has booked more than three times?";
    }

    @Override
    public String sql() {
        return SQL;
    }

    @Override
    public String emptyMessage() {
        return "No customer has more than three confirmed bookings yet.";
    }

    @Override
    public List<String> map(ResultSet rows) throws SQLException {
        return List.of(
                ReportCells.text(rows, "full_name"),
                ReportCells.count(rows, "bookings"),
                ReportCells.money(rows, "total_spent"));
    }
}
