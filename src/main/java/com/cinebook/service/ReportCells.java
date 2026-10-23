package com.cinebook.service;

import com.cinebook.util.Money;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The small formatting vocabulary shared by every report row mapper. */
final class ReportCells {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private ReportCells() {
    }

    /**
     * A value as the ledger prints it: dates as dates, decimals without trailing
     * zeroes, and an em dash for a NULL that means "nothing yet".
     */
    static String text(ResultSet rows, String column) throws SQLException {
        Object value = rows.getObject(column);
        if (value == null) {
            return "—";
        }
        if (value instanceof BigDecimal decimal) {
            return Money.plain(decimal);
        }
        if (value instanceof java.sql.Date date) {
            return DATE.format(date.toLocalDate());
        }
        if (value instanceof java.sql.Time time) {
            return TIME.format(time.toLocalTime());
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return DATE_TIME.format(timestamp.toLocalDateTime());
        }
        if (value instanceof LocalDate date) {
            return DATE.format(date);
        }
        if (value instanceof LocalTime time) {
            return TIME.format(time);
        }
        if (value instanceof LocalDateTime dateTime) {
            return DATE_TIME.format(dateTime);
        }
        return value.toString();
    }

    static String money(ResultSet rows, String column) throws SQLException {
        BigDecimal value = rows.getBigDecimal(column);
        return value == null ? "—" : "₹" + Money.plain(value);
    }

    static String percent(ResultSet rows, String column) throws SQLException {
        BigDecimal value = rows.getBigDecimal(column);
        return value == null ? "—" : Money.plain(value) + "%";
    }

    static String count(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? "—" : String.valueOf(value);
    }
}
