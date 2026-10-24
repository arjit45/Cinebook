package com.cinebook.service;

import com.cinebook.exception.ValidationException;

import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>An {@code EXPLAIN} statement is checked down to what it explains. MySQL
 * executes the statement inside {@code EXPLAIN ANALYZE}, so allowing
 * {@code EXPLAIN ANALYZE DELETE} would turn the reading desk into a delete
 * button. Every write form under {@code EXPLAIN} is refused, including the
 * optional {@code FORMAT} clause. MySQL executable comments (those beginning
 * {@code /*!}) are refused too: they can hide the same write from a keyword
 * check while the server still runs their contents.</p>
 *
 * <p>The remaining read-side escapes are checked as SQL tokens, not as raw
 * text. Comments and whitespace may sit between {@code INTO} and
 * {@code OUTFILE}, between {@code FOR} and {@code UPDATE}, or between the words
 * of {@code LOCK IN SHARE MODE}; a raw regex would miss those forms while also
 * matching harmless text inside a string literal. Tokenizing skips strings, quoted
 * identifiers and comments, so only executable keywords can trigger a refusal.</p>
 */
public final class SqlGuard {

    /** The console shows at most this many rows and says when it stopped. */
    public static final int MAX_ROWS = 500;

    private static final Set<String> READ_ONLY =
            Set.of("SELECT", "SHOW", "EXPLAIN", "DESCRIBE", "DESC");

    /** Words that turn an explained statement into a write or a second action. */
    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "REPLACE", "CALL", "LOAD", "CREATE",
            "DROP", "ALTER", "TRUNCATE", "RENAME", "GRANT", "REVOKE", "LOCK",
            "UNLOCK", "DO", "PREPARE", "EXECUTE", "DEALLOCATE", "SET", "USE",
            "HANDLER", "WITH");

    private static final String DESTRUCTIVE =
            "This console is read-only. Use SELECT, SHOW, EXPLAIN or DESCRIBE.";

    private static final String EXPLAIN_WRITE =
            "EXPLAIN of a write is blocked in the read-only console. Use Workbench instead.";

    private static final String EXECUTABLE_COMMENT =
            "Executable comments are blocked in the read-only console. Use Workbench instead.";

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
        if (sql.contains("/*!")) {
            throw new ValidationException(EXECUTABLE_COMMENT);
        }

        Word first = wordAt(sql, 0);
        if (first == null || !READ_ONLY.contains(first.text())) {
            throw new ValidationException(DESTRUCTIVE);
        }
        if ("EXPLAIN".equals(first.text())) {
            String explained = explainedKeyword(sql, first.end());
            if (explained == null || explained.isEmpty()
                    || WRITE_KEYWORDS.contains(explained)) {
                throw new ValidationException(EXPLAIN_WRITE);
            }
        }

        if (hasBlockedReadForm(sql)) {
            throw new ValidationException(
                    "That form is blocked in the read-only console. Use Workbench instead.");
        }
        return sql;
    }

    /**
     * Refuses file output and file reads, row locks, and the same forms hidden by
     * comments between their keywords.
     */
    private static boolean hasBlockedReadForm(String sql) {
        List<String> tokens = tokens(sql);
        return tokens.contains("LOAD_FILE")
                || hasPhrase(tokens, "INTO", "OUTFILE")
                || hasPhrase(tokens, "INTO", "DUMPFILE")
                || hasPhrase(tokens, "FOR", "UPDATE")
                || hasPhrase(tokens, "FOR", "SHARE")
                || hasPhrase(tokens, "LOCK", "IN", "SHARE", "MODE");
    }

    private static boolean hasPhrase(List<String> tokens, String... phrase) {
        for (int start = 0; start + phrase.length <= tokens.size(); start++) {
            boolean match = true;
            for (int offset = 0; offset < phrase.length; offset++) {
                if (!tokens.get(start + offset).equals(phrase[offset])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    /**
     * Executable SQL words, with strings, quoted identifiers and comments removed.
     * Tokens stay adjacent across whitespace and comments, which is exactly what
     * makes an {@code INTO} … {@code OUTFILE} pair visible to
     * {@link #hasBlockedReadForm(String)}.
     */
    private static List<String> tokens(String sql) {
        List<String> tokens = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
            } else if (current == '#'
                    || (current == '-' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '-' && isDashComment(sql, index))) {
                index = skipLine(sql, index);
            } else if (current == '/' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '*') {
                int end = sql.indexOf("*/", index + 2);
                if (end < 0) {
                    return tokens;
                }
                index = end + 2;
            } else if (current == '\'' || current == '"' || current == '`') {
                int afterQuote = skipQuoted(sql, index);
                if (afterQuote < 0) {
                    return tokens;
                }
                // MySQL accepts a quoted identifier as the name of a built-in, so
                // `LOAD_FILE`('x') is the same file read as the bare spelling.
                if (current != '\'') {
                    String quoted = sql.substring(index + 1, afterQuote - 1)
                            .replace("``", "`")
                            .replace("\"\"", "\"");
                    if ("LOAD_FILE".equalsIgnoreCase(quoted)) {
                        tokens.add("LOAD_FILE");
                    }
                }
                index = afterQuote;
            } else if (Character.isLetterOrDigit(current) || current == '_' || current == '$') {
                int start = index;
                while (index < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(index))
                        || sql.charAt(index) == '_' || sql.charAt(index) == '$')) {
                    index++;
                }
                tokens.add(sql.substring(start, index).toUpperCase(Locale.ROOT));
            } else {
                index++;
            }
        }
        return tokens;
    }

    /** MySQL's two-dash comment needs whitespace or a control character after it. */
    private static boolean isDashComment(String sql, int index) {
        return index + 2 >= sql.length()
                || Character.isWhitespace(sql.charAt(index + 2))
                || Character.isISOControl(sql.charAt(index + 2));
    }

    /**
     * The statement keyword after the {@code EXPLAIN} modifiers, or {@code null}
     * when the text ends or the format value cannot be read.
     *
     * <p>{@code EXPLAIN [ANALYZE] [FORMAT = name] statement} is the grammar that
     * matters here; the option words are skipped so
     * {@code EXPLAIN ANALYZE FORMAT=TREE DELETE ...} cannot hide the DELETE.
     * MySQL also accepts the format name as a quoted string, so {@code FORMAT}
     * skips a quoted value as well as a bare word.</p>
     */
    private static String explainedKeyword(String sql, int from) {
        int index = from;
        while (true) {
            Word word = wordAt(sql, index);
            if (word == null) {
                return null;
            }
            switch (word.text()) {
                case "ANALYZE", "EXTENDED", "PARTITIONS" -> index = word.end();
                case "FORMAT" -> {
                    index = skipEquals(sql, word.end());
                    Word format = wordAt(sql, index);
                    if (format != null) {
                        index = format.end();
                    } else {
                        int afterQuoted = skipQuoted(sql, index);
                        if (afterQuoted < 0) {
                            return null;
                        }
                        index = afterQuoted;
                    }
                }
                default -> {
                    return word.text();
                }
            }
        }
    }

    /**
     * Skips a quoted value and returns the index after its closing quote, or -1
     * when the quote is never closed. MySQL accepts single-quoted strings,
     * double-quoted strings and backtick identifiers.
     */
    private static int skipQuoted(String sql, int start) {
        int index = skipIgnored(sql, start);
        if (index >= sql.length()) {
            return -1;
        }
        char quote = sql.charAt(index);
        if (quote != '\'' && quote != '"' && quote != '`') {
            return -1;
        }
        for (int i = index + 1; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (current == '\\' && quote != '`') {
                i++;
            } else if (current == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i++;
                } else {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static int skipEquals(String sql, int index) {
        int next = skipIgnored(sql, index);
        while (next < sql.length() && sql.charAt(next) == '=') {
            next = skipIgnored(sql, next + 1);
        }
        return next;
    }

    /** The next SQL word, with comments and whitespace skipped. */
    private static Word wordAt(String sql, int start) {
        int index = skipIgnored(sql, start);
        int end = index;
        while (end < sql.length() && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        if (end == index) {
            return null;
        }
        return new Word(sql.substring(index, end).toUpperCase(Locale.ROOT), end);
    }

    private static int skipIgnored(String sql, int index) {
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
                return end < 0 ? sql.length() : skipIgnored(sql, end + 2);
            } else {
                return index;
            }
        }
        return index;
    }

    /** Skips to after the next LF or CR, so a CR-only comment cannot swallow the check. */
    private static int skipLine(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
            index++;
        }
        if (index < sql.length() && sql.charAt(index) == '\r') {
            index++;
        }
        if (index < sql.length() && sql.charAt(index) == '\n') {
            index++;
        }
        return index;
    }

    private record Word(String text, int end) {
    }
}
