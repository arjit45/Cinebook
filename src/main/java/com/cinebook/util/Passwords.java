package com.cinebook.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * The salted SHA-256 scheme used by {@code staff_user}: {@code sha256(salt + password)}
 * as lowercase hex, which is what {@code db/02_seed.sql} stores and what
 * {@code chk_staff_hash} enforces the length of.
 */
public final class Passwords {

    private Passwords() {
    }

    public static String hash(String salt, String rawPassword) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((salt + rawPassword).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM ships SHA-256", e);
        }
    }

    /** Constant-time comparison, so a failed login costs the same whatever the input. */
    public static boolean matches(String salt, String rawPassword, String expectedHash) {
        byte[] actual = hash(salt, rawPassword).getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actual, expected);
    }
}
