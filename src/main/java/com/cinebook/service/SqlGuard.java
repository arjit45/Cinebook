package com.cinebook.service;

import com.cinebook.exception.ValidationException;

import java.util.Locale;
import java.util.Set;

/**
 * The SQL console is a reading desk, not a second client for MySQL
 * (docs/PRD.md §16). It accepts one of the four read statements, refuses a
 * second statement after a semicolon, and blocks the few SELECT forms that
 * write files or take locks.
 *
 * <p>This is a gate, not a parser: the database account's grants are the real
 * control. The point is that a destructive keystroke is answered with a
 * sentence rather than a failed transaction.</p>
 */
public final class SqlGuard {

    /** The console shows at most this many rows and says when it stopped. */
    public static final int MAX_ROWS = 500;

    private static final Set<String> READ_ONLY =
            Set.of("SELECT", "SHOW", "EXPLAIN", "DESCRIBE", "DESC");

    private static final String DESTRUCTIVE =
            "This console is read-only. Use SELECT, SHOW, EXPLAIN or DESCRIBE.";

    private SqlGuard() {
    }

    /**
     * @return the statement to run, without a trailing semicolon
     * @throws ValidationException with a sentence the console can show
     */
    public static String validate(String raw) throws ValidationException {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("Type a query first.");
        }
        String sql = raw.strip();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).stripTrailing();
        }
        if (sql.isEmpty()) {
            throw new ValidationException("Type a query first.");
        }
        if (sql.contains(";")) {
            throw new ValidationException("Run one statement at a time.");
        }

        String upper = sql.toUpperCase(Locale.ROOT);
        if (!READ_ONLY.contains(firstKeyword(sql))) {
            throw new ValidationException(DESTRUCTIVE);
        }
        if (upper.matches("(?s).*\\bINTO\\s+(OUTFILE|DUMPFILE)\\b.*")
                || upper.contains("LOAD_FILE(")
                || upper.matches("(?s).*\\bFOR\\s+UPDATE\\b.*")
                || upper.contains("LOCK IN SHARE MODE")) {
            throw new ValidationException(
                    "That form is blocked in the read-only console. Use Workbench instead.");
        }
        return sql;
    }

    /** The first word after leading whitespace and comments. */
    private static String firstKeyword(String sql) {
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
            } else if (current == '-' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '-') {
                index = skipLine(sql, index);
            } else if (current == '#') {
                index = skipLine(sql, index);
            } else if (current == '/' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '*') {
                int end = sql.indexOf("*/", index + 2);
                if (end < 0) {
                    return "";
                }
                index = end + 2;
            } else {
                break;
            }
        }
        int end = index;
        while (end < sql.length() && (Character.isLetter(sql.charAt(end)))) {
            end++;
        }
        return sql.substring(index, end).toUpperCase(Locale.ROOT);
    }

    private static int skipLine(String sql, int index) {
        int newline = sql.indexOf('\n', index);
        return newline < 0 ? sql.length() : newline + 1;
    }
}
