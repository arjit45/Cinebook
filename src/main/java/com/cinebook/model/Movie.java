package com.cinebook.model;

import com.cinebook.model.enums.Certificate;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A film in the catalogue. Independent of any screening: {@link Showtime} points here. */
public final class Movie {

    private final int movieId;
    private final String title;
    private final String genre;
    private final String language;
    private final int runtimeMin;
    private final LocalDate releaseDate;
    private final Certificate certificate;
    private final BigDecimal imdbRating;

    public Movie(int movieId, String title, String genre, String language, int runtimeMin,
                 LocalDate releaseDate, Certificate certificate, BigDecimal imdbRating) {
        this.movieId = movieId;
        this.title = title;
        this.genre = genre;
        this.language = language;
        this.runtimeMin = runtimeMin;
        this.releaseDate = releaseDate;
        this.certificate = certificate;
        this.imdbRating = imdbRating;
    }

    public int movieId() {
        return movieId;
    }

    public String title() {
        return title;
    }

    public String genre() {
        return genre;
    }

    public String language() {
        return language;
    }

    public int runtimeMin() {
        return runtimeMin;
    }

    public LocalDate releaseDate() {
        return releaseDate;
    }

    public Certificate certificate() {
        return certificate;
    }

    public BigDecimal imdbRating() {
        return imdbRating;
    }
}
