package com.cinebook.dao;

import com.cinebook.model.Seat;
import com.cinebook.model.enums.SeatClass;
import com.cinebook.util.Db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The two reads the seat map needs: the physical seats of an auditorium, and
 * which of them are already sold for one screening.
 *
 * <p>Both are single statements. The map is then built in memory by matching
 * labels, which is why the screen never issues one query per seat — the §13.5
 * target is under 150 ms, and 96 round trips would not be.
 */
public final class SeatDao {

    private static final String BY_SCREEN_SQL = """
            SELECT seat_id, row_label, seat_no, seat_class
              FROM seat
             WHERE screen_id = ?
             ORDER BY row_label, seat_no
            """;

    private static final String SOLD_LABELS_SQL = """
            SELECT CONCAT(s.row_label, s.seat_no) AS label
              FROM ticket t
              JOIN seat   s ON s.seat_id = t.seat_id
             WHERE t.showtime_id = ?
            """;

    private final Db db;

    public SeatDao(Db db) {
        this.db = db;
    }

    /** Every seat in the auditorium, in reading order (row then number). */
    public List<Seat> byScreen(int screenId) throws SQLException {
        List<Seat> seats = new ArrayList<>();
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(BY_SCREEN_SQL)) {
            statement.setInt(1, screenId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    seats.add(Seat.of(
                            rows.getInt("seat_id"),
                            rows.getString("row_label").charAt(0),
                            rows.getInt("seat_no"),
                            SeatClass.valueOf(rows.getString("seat_class"))));
                }
            }
        }
        return seats;
    }

    /** Labels such as {@code A7} that are already sold for this screening. */
    public Set<String> soldLabels(int showtimeId) throws SQLException {
        Set<String> sold = new LinkedHashSet<>();
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(SOLD_LABELS_SQL)) {
            statement.setInt(1, showtimeId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    sold.add(rows.getString("label"));
                }
            }
        }
        return sold;
    }
}
