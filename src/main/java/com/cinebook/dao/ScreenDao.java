package com.cinebook.dao;

import com.cinebook.model.Screen;
import com.cinebook.util.Db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** The four auditoriums. A screening is always assigned to one of these rows. */
public final class ScreenDao {

    private static final String LIST_SQL = """
            SELECT screen_id, name, seat_capacity
              FROM screen
             ORDER BY name, screen_id
            """;

    private final Db db;

    public ScreenDao(Db db) {
        this.db = db;
    }

    public List<Screen> listAll() throws SQLException {
        List<Screen> screens = new ArrayList<>();
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(LIST_SQL);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                screens.add(new Screen(
                        rows.getInt("screen_id"),
                        rows.getString("name"),
                        rows.getInt("seat_capacity")));
            }
        }
        return screens;
    }
}
