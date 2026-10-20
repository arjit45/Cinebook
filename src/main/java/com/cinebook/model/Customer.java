package com.cinebook.model;

import com.cinebook.exception.ValidationException;

import java.time.LocalDate;

/**
 * A ticket buyer. The two contact rules in docs/PRD.md §13.2 live here as static
 * checks so the checkout form and the DAO can share one implementation, and the
 * unit tests can drive them table-style.
 */
public final class Customer {

    private final int customerId;
    private final String fullName;
    private final String email;
    private final String phone;
    private final LocalDate joinedOn;

    public Customer(int customerId, String fullName, String email, String phone, LocalDate joinedOn)
            throws ValidationException {
        this.customerId = customerId;
        this.fullName = requireName(fullName);
        this.email = requireEmail(email);
        this.phone = requirePhone(phone);
        this.joinedOn = joinedOn;
    }

    public static String requireName(String fullName) throws ValidationException {
        if (fullName == null || fullName.isBlank()) {
            throw new ValidationException("A customer needs a name.");
        }
        return fullName.trim();
    }

    public static String requireEmail(String email) throws ValidationException {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new ValidationException("An email address needs an @ and cannot be blank.");
        }
        return email.trim();
    }

    public static String requirePhone(String phone) throws ValidationException {
        if (phone == null || !phone.matches("[0-9]{10,13}")) {
            throw new ValidationException("A phone number must be 10 to 13 digits.");
        }
        return phone;
    }

    /** What the receipt prints — the full name, never the email. */
    public String displayName() {
        return fullName;
    }

    public int customerId() {
        return customerId;
    }

    public String fullName() {
        return fullName;
    }

    public String email() {
        return email;
    }

    public String phone() {
        return phone;
    }

    public LocalDate joinedOn() {
        return joinedOn;
    }
}
