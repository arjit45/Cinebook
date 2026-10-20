package com.cinebook.dao;

import com.cinebook.model.Administrator;
import com.cinebook.model.BoxOfficeOperator;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.Role;
import com.cinebook.util.Db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Login lookup. Returns the concrete subclass for the row's role, so the rest of
 * the application never switches on {@code Role} to decide what a person may do.
 *
 * <p>The hash and salt are read here and handed straight to {@link StaffUser};
 * they are never returned to a caller, logged, or put in a DTO.
 */
public final class StaffDao {

    private static final String BY_USERNAME_SQL = """
            SELECT staff_id, username, full_name, password_hash, salt, role
              FROM staff_user
             WHERE username = ?
               AND is_active = TRUE
            """;

    private final Db db;

    public StaffDao(Db db) {
        this.db = db;
    }

    public Optional<StaffUser> findByUsername(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(BY_USERNAME_SQL)) {
            statement.setString(1, username.trim());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(map(rows));
            }
        }
    }

    private static StaffUser map(ResultSet rows) throws SQLException {
        int staffId = rows.getInt("staff_id");
        String username = rows.getString("username");
        String fullName = rows.getString("full_name");
        String hash = rows.getString("password_hash");
        String salt = rows.getString("salt");

        return switch (Role.valueOf(rows.getString("role"))) {
            case ADMIN -> new Administrator(staffId, username, fullName, hash, salt);
            case OPERATOR -> new BoxOfficeOperator(staffId, username, fullName, hash, salt);
        };
    }
}
