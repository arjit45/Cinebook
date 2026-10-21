package com.cinebook.dao;

import com.cinebook.model.ConcessionItem;
import com.cinebook.model.ConcessionLine;
import com.cinebook.model.enums.ItemCategory;
import com.cinebook.util.Db;
import com.cinebook.util.Money;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The concession price list and the food on one hold.
 *
 * <p>A line's price is written once, when the item is added, and never read from
 * the menu again: later price changes must not restate a receipt (docs/PRD.md
 * §7). The composite key makes the second popcorn a quantity, not a second row.
 */
public final class ConcessionDao {

    private static final String ACTIVE_SQL = """
            SELECT item_id, name, category, unit_price, is_active
              FROM concession_item
             WHERE is_active = TRUE
             ORDER BY FIELD(category, 'SNACK', 'DRINK', 'COMBO', 'DESSERT'), name
            """;

    private static final String QUANTITIES_SQL = """
            SELECT item_id, quantity
              FROM booking_concession
             WHERE booking_id = ?
            """;

    private static final String LINE_SQL = """
            SELECT ci.name, ci.category, bc.quantity, bc.unit_price
              FROM booking_concession bc
              JOIN concession_item ci ON ci.item_id = bc.item_id
             WHERE bc.booking_id = ?
             ORDER BY ci.name
            """;

    /** quantity = 1 or 2 ... on the same row; the snapshot is refreshed only when re-added. */
    private static final String UPSERT_SQL = """
            INSERT INTO booking_concession (booking_id, item_id, quantity, unit_price)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE quantity = VALUES(quantity), unit_price = VALUES(unit_price)
            """;

    private static final String DELETE_SQL =
            "DELETE FROM booking_concession WHERE booking_id = ? AND item_id = ?";

    private final Db db;

    public ConcessionDao(Db db) {
        this.db = db;
    }

    public List<ConcessionItem> activeItems() throws SQLException {
        List<ConcessionItem> items = new ArrayList<>();
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(ACTIVE_SQL);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                items.add(new ConcessionItem(
                        rows.getInt("item_id"),
                        rows.getString("name"),
                        ItemCategory.valueOf(rows.getString("category")),
                        Money.round(rows.getBigDecimal("unit_price")),
                        rows.getBoolean("is_active")));
            }
        }
        return items;
    }

    /** Item id → quantity currently on the booking. */
    public Map<Integer, Integer> quantities(int bookingId) throws SQLException {
        Map<Integer, Integer> lines = new LinkedHashMap<>();
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(QUANTITIES_SQL)) {
            statement.setInt(1, bookingId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    lines.put(rows.getInt("item_id"), rows.getInt("quantity"));
                }
            }
        }
        return lines;
    }

    /** The receipt lines, with the names the menu had when they were sold. */
    public List<ConcessionLine> linesFor(int bookingId) throws SQLException {
        try (Db.Handle handle = db.borrow()) {
            return linesFor(handle.connection(), bookingId);
        }
    }

    /**
     * Same read on a connection the caller already owns, so a receipt can be
     * assembled on one borrow instead of nesting a second.
     */
    List<ConcessionLine> linesFor(Connection connection, int bookingId) throws SQLException {
        List<ConcessionLine> lines = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(LINE_SQL)) {
            statement.setInt(1, bookingId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    lines.add(new ConcessionLine(
                            rows.getString("name"),
                            ItemCategory.valueOf(rows.getString("category")),
                            rows.getInt("quantity"),
                            Money.round(rows.getBigDecimal("unit_price"))));
                }
            }
        }
        return lines;
    }

    /**
     * Adds, changes or removes the one line for this item. Quantity 0 deletes it,
     * which is what a second click on a selected chip does.
     */
    public void setQuantity(int bookingId, ConcessionItem item, int quantity)
            throws SQLException {
        if (quantity <= 0) {
            try (Db.Handle handle = db.borrow();
                 PreparedStatement statement = handle.connection().prepareStatement(DELETE_SQL)) {
                statement.setInt(1, bookingId);
                statement.setInt(2, item.itemId());
                statement.executeUpdate();
            }
            return;
        }
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(UPSERT_SQL)) {
            statement.setInt(1, bookingId);
            statement.setInt(2, item.itemId());
            statement.setInt(3, quantity);
            statement.setBigDecimal(4, Money.round(item.unitPrice()));
            statement.executeUpdate();
        }
    }
}
