package com.cinebook.service;

import com.cinebook.dao.StaffDao;
import com.cinebook.exception.AuthException;
import com.cinebook.model.StaffUser;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Who is signed in, and what they are allowed to do.
 *
 * <p>Authorization lives here rather than in a controller, because hiding the
 * Programme button is not a control (docs/PRD.md §16): a hand-built call to an
 * admin service still has to pass {@link #requireAdmin()} before it touches a
 * query.
 */
public final class AuthService {

    private final StaffDao staffDao;
    private StaffUser current;

    public AuthService(StaffDao staffDao) {
        this.staffDao = staffDao;
    }

    /**
     * @throws AuthException when the pair does not match, with one message for both
     *                       an unknown user and a wrong password
     */
    public StaffUser login(String username, String rawPassword) throws AuthException, SQLException {
        Optional<StaffUser> found = staffDao.findByUsername(username);
        if (found.isEmpty() || !found.get().authenticate(rawPassword)) {
            throw new AuthException("That username and password do not match. Check both and try again.");
        }
        current = found.get();
        return current;
    }

    public void logout() {
        current = null;
    }

    public Optional<StaffUser> currentUser() {
        return Optional.ofNullable(current);
    }

    /** Called by every admin entry point before its first query. */
    public void requireAdmin() throws AuthException {
        if (current == null) {
            throw new AuthException("Sign in first.");
        }
        if (!current.canManageProgramme()) {
            throw new AuthException("Only an administrator can do that.");
        }
    }
}
