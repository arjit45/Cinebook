package com.cinebook.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * One report, carrying its own SQL and its own row mapper — the Strategy
 * implementation the docs call out for each Reports tab (docs/OOP.md §2).
 *
 * <p>The query text lives here, not in the view, so the screen never imports
 * {@code java.sql}; {@link ReportService} owns the connection, the role gate and
 * the result-set lifecycle.</p>
 */
public interface ReportQuery {

    /** The query number in {@code db/04_queries.sql}, as text so presets sort naturally. */
    String id();

    /** The tab or preset label. */
    String title();

    /** The question the report answers, in the clerk's language. */
    String question();

    String sql();

    /** Reads one row into display cells, in column order. */
    List<String> map(ResultSet rows) throws SQLException;

    /** The main five tabs; presets that are not tabs override this to false. */
    default boolean tab() {
        return true;
    }

    /**
     * What an empty result means for this report. The default is the PRD's
     * sentence for a quiet sales period; the film and seat reports say something
     * more specific.
     */
    default String emptyMessage() {
        return "Nothing sold in this period yet.";
    }
}
