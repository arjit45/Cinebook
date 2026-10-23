package com.cinebook.dao;

import com.cinebook.model.Movie;
import com.cinebook.model.enums.Certificate;
import com.cinebook.util.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * The film catalogue. Reads are one ordered query; writes take the caller's
 * connection so the service can decide where the unit of work starts and ends
 * (docs/PRD.md §11.1).
 *
 * <p>A delete is not given a friendly pre-check here: the {@code showtime} foreign
 * key is {@code ON DELETE RESTRICT}, so the database refuses to orphan a schedule.
 * {@link com.cinebook.service.ProgrammeService} turns that refusal into a sentence.
 */
public final class MovieDao {

    private static final String SELECT_COLUMNS = """
            SELECT movie_id, title, genre, language, runtime_min,
                   release_date, certificate, imdb_rating
              FROM movie
            """;

    private static final String LIST_SQL = SELECT_COLUMNS + " ORDER BY title, movie_id";

    private static final String INSERT_SQL = """
            INSERT INTO movie
                (title, genre, language, runtime_min, release_date, certificate, imdb_rating)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_SQL = """
            UPDATE movie
               SET title = ?, genre = ?, language = ?, runtime_min = ?,
                   release_date = ?, certificate = ?, imdb_rating = ?
             WHERE movie_id = ?
            """;

    private static final String DELETE_SQL = "DELETE FROM movie WHERE movie_id = ?";

    private final Db db;

    public MovieDao(Db db) {
        this.db = db;
    }

    public List<Movie> listAll() throws SQLException {
        List<Movie> movies = new ArrayList<>();
        try (Db.Handle handle = db.borrow();
             PreparedStatement statement = handle.connection().prepareStatement(LIST_SQL);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                movies.add(map(rows));
            }
        }
        return movies;
    }

    /** Inserts a film whose id is still 0 and returns the id MySQL assigned. */
    public int insert(Connection connection, Movie movie) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, movie);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("MySQL did not return a movie id");
                }
                return keys.getInt(1);
            }
        }
    }

    /** @return rows changed; 0 means the film no longer exists */
    public int update(Connection connection, Movie movie) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
            bind(statement, movie);
            statement.setInt(8, movie.movieId());
            return statement.executeUpdate();
        }
    }

    /** @return rows removed; 0 means the film no longer exists */
    public int delete(Connection connection, int movieId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_SQL)) {
            statement.setInt(1, movieId);
            return statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Movie movie) throws SQLException {
        statement.setString(1, movie.title());
        statement.setString(2, movie.genre());
        statement.setString(3, movie.language());
        statement.setInt(4, movie.runtimeMin());
        statement.setObject(5, movie.releaseDate());
        statement.setString(6, movie.certificate().name());
        if (movie.imdbRating() == null) {
            statement.setNull(7, Types.DECIMAL);
        } else {
            statement.setBigDecimal(7, movie.imdbRating());
        }
    }

    private static Movie map(ResultSet rows) throws SQLException {
        return new Movie(
                rows.getInt("movie_id"),
                rows.getString("title"),
                rows.getString("genre"),
                rows.getString("language"),
                rows.getInt("runtime_min"),
                rows.getObject("release_date", java.time.LocalDate.class),
                Certificate.valueOf(rows.getString("certificate")),
                rows.getBigDecimal("imdb_rating"));
    }
}
