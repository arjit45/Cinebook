package com.cinebook.exception;

/**
 * Raised when the database cannot be opened at all: MySQL is not running, the
 * credentials are wrong, or the connection died and could not be re-established.
 *
 * <p>Separate from {@link java.sql.SQLException} because the UI treats it
 * differently — a banner saying "MySQL is not running", not a field-level error
 * (docs/PRD.md §11.4). Unchecked, because every call site would otherwise
 * declare it without ever handling it.
 */
public class DbUnavailableException extends RuntimeException {

    public DbUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
