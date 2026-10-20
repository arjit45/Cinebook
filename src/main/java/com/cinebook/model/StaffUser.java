package com.cinebook.model;

import com.cinebook.model.enums.Role;
import com.cinebook.util.Passwords;

/**
 * A counter or admin account. The role is data; what the role may do is the two
 * abstract answers below, so an {@code if (role == ADMIN)} never has to be
 * scattered through the UI.
 *
 * <p>The hash and salt never leave this object: {@link #authenticate(String)} is
 * the only way in, and {@link #toString()} deliberately prints the username only
 * (docs/PRD.md §16).
 */
public abstract class StaffUser {

    private final int staffId;
    private final String username;
    private final String fullName;
    private final Role role;
    private final String passwordHash;
    private final String salt;

    protected StaffUser(int staffId, String username, String fullName, Role role,
                        String passwordHash, String salt) {
        this.staffId = staffId;
        this.username = username;
        this.fullName = fullName;
        this.role = role;
        this.passwordHash = passwordHash;
        this.salt = salt;
    }

    public boolean authenticate(String rawPassword) {
        return rawPassword != null && Passwords.matches(salt, rawPassword, passwordHash);
    }

    /** The screen this user lands on after login. */
    public abstract String homeScreen();

    /** Whether the Programme and Reports surfaces are reachable at all. */
    public abstract boolean canManageProgramme();

    public int staffId() {
        return staffId;
    }

    public String username() {
        return username;
    }

    public String fullName() {
        return fullName;
    }

    public Role role() {
        return role;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + username + "]";
    }
}
