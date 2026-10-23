package com.cinebook.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Query 4: films with no activity in the last six months. The LEFT JOIN keeps a
 * film that has never been screened, and the COALESCE gives it the deliberately
 * large dormant count the query catalogue documents.
 */
public final class DormantFilms implements ReportQuery {

    private static final String SQL = """
            SELECT m.movie_id,
                   m.title,
                   m.genre,
                   MAX(st.show_date)                        AS last_screened_on,
                   COALESCE(DATEDIFF(CURDATE(), MAX(st.show_date)), 9999) AS days_dormant
            FROM movie m
            LEFT JOIN showtime st ON st.movie_id = m.movie_id
            GROUP BY m.movie_id, m.title, m.genre
            HAVING MAX(st.show_date) IS NULL
                OR MAX(st.show_date) < CURDATE() - INTERVAL 6 MONTH
            ORDER BY last_screened_on
            """;

    @Override
    public String id() {
        return "4";
    }

    @Override
    public String title() {
        return "Dormant films";
    }

    @Override
    public String question() {
        return "Which films have not been screened in six months?";
    }

    @Override
    public String sql() {
        return SQL;
    }

    @Override
    public String emptyMessage() {
        return "Every film has been screened in the last six months.";
    }

    @Override
    public List<String> map(ResultSet rows) throws SQLException {
        return List.of(
                ReportCells.count(rows, "movie_id"),
                ReportCells.text(rows, "title"),
                ReportCells.text(rows, "genre"),
                ReportCells.text(rows, "last_screened_on"),
                ReportCells.count(rows, "days_dormant"));
    }
}
