package com.cinebook.dao;

import com.cinebook.model.Movie;
import com.cinebook.model.Screen;
import com.cinebook.model.Showtime;
import com.cinebook.model.enums.Certificate;
import com.cinebook.util.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The schedule screen's query: every upcoming screening with its film, its
 * auditorium and how full it already is.
 *
 * <p>One statement, one round trip, no N+1 (docs/PRD.md §13.5). The seats-sold
 * figure is a correlated subquery rather than a join, because joining
 * {@code ticket} here would multiply the screening row once per sold seat and
 * then need a {@code GROUP BY} that the screen does not want.
 *
 * <p>Stateless, as §11.1 requires: the DAO owns the SQL, the service owns the
 * transaction, the model owns the rules.
 *
 * <p>The sort ends with {@code screen_name, showtime_id} on purpose. Screenings can
 * share a date and a start time (the seed starts two screens together every third
 * day), and ordering by time alone leaves their relative order to whatever plan MySQL
 * picks — the same set of rows, in a different sequence between runs. A list that
 * reorders itself under a demo is a list nobody trusts.
 */
public final class ShowtimeDao {

    private static final String SHOW_COLUMNS = """
            SELECT st.showtime_id, st.show_date, st.start_time, st.base_price,
                   m.movie_id, m.title, m.genre, m.language, m.runtime_min,
                   m.release_date, m.certificate, m.imdb_rating,
                   sc.screen_id, sc.name, sc.seat_capacity,
                   (SELECT COUNT(*) FROM ticket t
                     WHERE t.showtime_id = st.showtime_id) AS seats_sold
              FROM showtime st
              JOIN movie  m  ON m.movie_id  = st.movie_id
              JOIN screen sc ON sc.screen_id = st.screen_id
            """;

    private static final String ORDER_AND_LIMIT = """
             ORDER BY st.show_date, st.start_time, sc.name, st.showtime_id
             LIMIT ?
            """;

    private static final String UPCOMING_SQL = SHOW_COLUMNS
            + " WHERE st.show_date >= CURDATE()" + ORDER_AND_LIMIT;

    /** The Programme list: only what has not already started. */
    private static final String PROGRAMME_SQL = SHOW_COLUMNS
            + " WHERE TIMESTAMP(st.show_date, st.start_time) >= NOW()" + ORDER_AND_LIMIT;

    /** What the counter may actually sell: a screening that has not started yet. */
    private static final String SELLABLE_SQL = SHOW_COLUMNS
            + " WHERE TIMESTAMP(st.show_date, st.start_time) > NOW()" + ORDER_AND_LIMIT;

    private static final String INSERT_SQL = """
            INSERT INTO showtime (movie_id, screen_id, show_date, start_time, base_price)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String COUNT_FOR_MOVIE_SQL =
            "SELECT COUNT(*) AS n FROM showtime WHERE movie_id = ?";

    private final Db db;

    public ShowtimeDao(Db db) {
        this.db = db;
    }

    public List<Showtime> upcoming(int limit) throws SQLException {
        return query(UPCOMING_SQL, limit);
    }

    /** Upcoming screenings for the Programme screen, excluding today's finished slots. */
    public List<Showtime> programme(int limit) throws SQLException {
        return query(PROGRAMME_SQL, limit);
    }

    /**
     * Screenings the counter may still sell: strictly after {@code NOW()}, so a
     * show that starts this second is already gone.
     *
     * <p>The schedule keeps started rows on the day's slate so a clerk sees why a
     * time is no longer openable, but every booking path and every test that
     * writes a booking draws from this list rather than {@link #upcoming(int)},
     * which is a display query and deliberately includes today's finished slots.
     */
    public List<Showtime> sellable(int limit) throws SQLException {
        return query(SELLABLE_SQL, limit);
    }

    private List<Showtime> query(String sql, int limit) throws SQLException {
        List<Showtime> shows = new ArrayList<>();
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    shows.add(map(rows));
                }
            }
        }
        return shows;
    }

    /** Adds one screening and returns the id MySQL assigned. */
    public int insert(Connection connection, int movieId, int screenId,
                      LocalDate showDate, LocalTime startTime, BigDecimal basePrice)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, movieId);
            statement.setInt(2, screenId);
            statement.setObject(3, showDate);
            statement.setObject(4, startTime);
            statement.setBigDecimal(5, basePrice);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("MySQL did not return a showtime id");
                }
                return keys.getInt(1);
            }
        }
    }

    /** How many screenings a film has; a film with any cannot be deleted. */
    public int countForMovie(Connection connection, int movieId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COUNT_FOR_MOVIE_SQL)) {
            statement.setInt(1, movieId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt("n");
            }
        }
    }

    private static Showtime map(ResultSet rows) throws SQLException {
        Movie movie = new Movie(
                rows.getInt("movie_id"),
                rows.getString("title"),
                rows.getString("genre"),
                rows.getString("language"),
                rows.getInt("runtime_min"),
                rows.getObject("release_date", java.time.LocalDate.class),
                Certificate.valueOf(rows.getString("certificate")),
                rows.getBigDecimal("imdb_rating"));

        Screen screen = new Screen(
                rows.getInt("screen_id"),
                rows.getString("name"),
                rows.getInt("seat_capacity"));

        return new Showtime(
                rows.getInt("showtime_id"),
                movie,
                screen,
                rows.getObject("show_date", java.time.LocalDate.class),
                rows.getObject("start_time", java.time.LocalTime.class),
                rows.getBigDecimal("base_price"),
                rows.getInt("seats_sold"));
    }
}
