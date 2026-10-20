package com.cinebook.exception;

/**
 * Sign-in or authorization refused. The message is deliberately identical for a
 * wrong username and a wrong password, so the screen cannot be used to discover
 * which accounts exist.
 */
public class AuthException extends Exception {

    public AuthException(String message) {
        super(message);
    }
}
