package com.cinebook.service;

import com.cinebook.exception.ValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The console guard, tested without a database. */
class SqlGuardTest {

    @Test
    void acceptsTheFourReadStatementsAndDropsTheTrailingSemicolon() throws Exception {
        assertEquals("SELECT 1", SqlGuard.validate("  SELECT 1;  "));
        assertEquals("SHOW TABLES", SqlGuard.validate("SHOW TABLES"));
        assertEquals("EXPLAIN SELECT * FROM movie", SqlGuard.validate("EXPLAIN SELECT * FROM movie"));
        assertEquals("DESCRIBE movie", SqlGuard.validate("DESCRIBE movie"));
    }

    @Test
    void acceptsLeadingLineComments() throws Exception {
        String sql = SqlGuard.validate("-- the five patterns\nSELECT 1");
        assertTrue(sql.contains("SELECT 1"), sql);
    }

    @Test
    void refusesWritesAndDdl() {
        assertRefused("DELETE FROM movie", "read-only");
        assertRefused("UPDATE booking SET status = 'CANCELLED'", "read-only");
        assertRefused("DROP TABLE payment", "read-only");
        assertRefused("CREATE TABLE x (id INT)", "read-only");
        assertRefused("CALL sp_confirm_booking(1, 'CASH')", "read-only");
    }

    @Test
    void refusesTwoStatements() {
        assertRefused("SELECT 1; DELETE FROM movie", "one statement");
    }

    @Test
    void refusesSelectFormsThatWriteFilesOrLockRows() {
        assertRefused("SELECT * FROM movie INTO OUTFILE '/tmp/movies.txt'", "blocked");
        assertRefused("SELECT * FROM booking FOR UPDATE", "blocked");
        assertRefused("SELECT LOAD_FILE('/etc/passwd')", "blocked");
    }

    @Test
    void refusesAnEmptyEditor() {
        assertRefused("   ", "Type a query");
        assertRefused(";", "Type a query");
    }

    private static void assertRefused(String sql, String expected) {
        ValidationException refused = assertThrows(ValidationException.class,
                () -> SqlGuard.validate(sql));
        assertTrue(refused.getMessage().toLowerCase().contains(expected.toLowerCase()),
                "expected \"" + expected + "\" in: " + refused.getMessage());
    }
}
