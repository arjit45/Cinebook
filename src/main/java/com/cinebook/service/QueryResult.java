package com.cinebook.service;

import java.util.List;

/**
 * A read-only SQL console result. The column labels come from the database, so
 * an ad-hoc query can be shown without knowing its shape in advance.
 */
public record QueryResult(List<String> columns, List<List<String>> rows,
                          long millis, boolean truncated) {

    public QueryResult {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
