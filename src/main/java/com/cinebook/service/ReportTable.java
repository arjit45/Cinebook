package com.cinebook.service;

import java.util.List;

/**
 * One report result, already formatted for the screen.
 *
 * <p>Columns are the SQL labels; rows are display cells in the same order. The
 * view builds its grid from this and never sees a {@link java.sql.ResultSet}.</p>
 */
public record ReportTable(List<String> columns, List<List<String>> rows, String emptyMessage) {

    public ReportTable {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
