package com.cinebook.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Query 10: the occupancy view. Same calculation as the schedule's free-seat
 * count, read from the view so there is one definition of "how full".
 */
public final class OccupancyReport implements ReportQuery {

    private static final String SQL = """
            SELECT movie, screen, show_date, start_time,
                   seats_sold, seats_available,
                   ROUND(100 * seats_sold / seat_capacity, 1) AS occupancy_pct
            FROM v_show_occupancy
            WHERE show_date >= CURDATE()
            ORDER BY occupancy_pct DESC
            LIMIT 10
            """;

    @Override
    public String id() {
        return "10";
    }

    @Override
    public String title() {
        return "Occupancy";
    }

    @Override
    public String question() {
        return "How full are the screenings still to come?";
    }

    @Override
    public String sql() {
        return SQL;
    }

    @Override
    public String emptyMessage() {
        return "No remaining screenings. Add one in Programme.";
    }

    @Override
    public List<String> map(ResultSet rows) throws SQLException {
        return List.of(
                ReportCells.text(rows, "movie"),
                ReportCells.text(rows, "screen"),
                ReportCells.text(rows, "show_date"),
                ReportCells.text(rows, "start_time"),
                ReportCells.count(rows, "seats_sold"),
                ReportCells.count(rows, "seats_available"),
                ReportCells.percent(rows, "occupancy_pct"));
    }
}
