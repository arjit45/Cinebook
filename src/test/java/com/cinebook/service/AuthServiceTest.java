package com.cinebook.service;

import com.cinebook.dao.StaffDao;
import com.cinebook.exception.AuthException;
import com.cinebook.model.Administrator;
import com.cinebook.model.BoxOfficeOperator;
import com.cinebook.model.StaffUser;
import com.cinebook.model.enums.Role;
import com.cinebook.util.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sign-in against the two accounts the seed creates: {@code admin / admin123} and
 * {@code operator / operator123} (db/02_seed.sql). Salted SHA-256 is the point —
 * if the hashing scheme drifts, these fail rather than quietly locking everyone out.
 */
@Tag("db")
class AuthServiceTest {

    private static final Db DB = Db.get();

    private final AuthService auth = new AuthService(new StaffDao(DB));

    @AfterAll
    static void closeConnection() {
        DB.close();
    }

    @Test
    void adminSignsInAndLandsOnReports() throws Exception {
        StaffUser user = auth.login("admin", "admin123");

        assertInstanceOf(Administrator.class, user);
        assertEquals(Role.ADMIN, user.role());
        assertEquals("Reports", user.homeScreen());
        assertTrue(user.canManageProgramme());
        assertEquals("Meera Krishnan", user.fullName());
    }

    @Test
    void operatorSignsInAndLandsOnSchedule() throws Exception {
        StaffUser user = auth.login("operator", "operator123");

        assertInstanceOf(BoxOfficeOperator.class, user);
        assertEquals(Role.OPERATOR, user.role());
        assertEquals("Schedule", user.homeScreen());
        assertFalse(user.canManageProgramme());
    }

    @Test
    void wrongPasswordIsRefused() {
        AuthException refused = assertThrows(AuthException.class,
                () -> auth.login("admin", "not-the-password"));
        assertTrue(refused.getMessage().contains("do not match"), refused.getMessage());
        assertTrue(auth.currentUser().isEmpty(), "a failed sign-in must not leave anyone signed in");
    }

    @Test
    void unknownUserIsIndistinguishableFromAWrongPassword() {
        String wrongPassword = assertThrows(AuthException.class,
                () -> auth.login("admin", "nope")).getMessage();
        String unknownUser = assertThrows(AuthException.class,
                () -> auth.login("nobody", "nope")).getMessage();

        assertEquals(wrongPassword, unknownUser,
                "the screen must not reveal whether an account exists");
    }

    @Test
    void operatorIsRefusedAdminWork() throws Exception {
        auth.login("operator", "operator123");
        assertThrows(AuthException.class, auth::requireAdmin);
    }

    @Test
    void adminPassesTheAdminGuardAndSignOutClears() throws Exception {
        auth.login("admin", "admin123");
        auth.requireAdmin();

        auth.logout();
        assertTrue(auth.currentUser().isEmpty());
        assertThrows(AuthException.class, auth::requireAdmin);
    }

    @Test
    void toStringNeverPrintsTheHashOrSalt() throws Exception {
        StaffUser user = auth.login("admin", "admin123");
        String printed = user.toString();

        assertEquals("Administrator[admin]", printed);
        assertFalse(printed.contains("0212b518"), "hash leaked into toString");
    }
}
