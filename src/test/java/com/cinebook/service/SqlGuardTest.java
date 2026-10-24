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
        assertEquals("EXPLAIN ANALYZE SELECT * FROM movie",
                SqlGuard.validate("EXPLAIN ANALYZE SELECT * FROM movie"));
        assertEquals("EXPLAIN FORMAT='TREE' SELECT 1",
                SqlGuard.validate("EXPLAIN FORMAT='TREE' SELECT 1"));
        assertEquals("EXPLAIN movie", SqlGuard.validate("EXPLAIN movie"));
        assertEquals("DESCRIBE movie", SqlGuard.validate("DESCRIBE movie"));
    }

    @Test
    void acceptsLeadingLineComments() throws Exception {
        String sql = SqlGuard.validate("-- the five patterns\nSELECT 1");
        assertTrue(sql.contains("SELECT 1"), sql);
    }

    @Test
    void refusesWritesAndDdlIncludingWritesHiddenBehindExplain() {
        assertRefused("DELETE FROM movie", "read-only");
        assertRefused("UPDATE booking SET status = 'CANCELLED'", "read-only");
        assertRefused("DROP TABLE payment", "read-only");
        assertRefused("CREATE TABLE x (id INT)", "read-only");
        assertRefused("CALL sp_confirm_booking(1, 'CASH')", "read-only");
        assertRefused("EXPLAIN ANALYZE DELETE FROM booking WHERE booking_id = -1", "read-only");
        assertRefused("EXPLAIN ANALYZE FORMAT=TREE DELETE FROM booking", "read-only");
        assertRefused("EXPLAIN ANALYZE FORMAT='TREE' DELETE FROM booking", "read-only");
        assertRefused("EXPLAIN ANALYZE FORMAT = 'TREE' DELETE FROM booking", "read-only");
        assertRefused("EXPLAIN ANALYZE FORMAT=\"TREE\" DELETE FROM booking", "read-only");
        assertRefused("EXPLAIN FORMAT=JSON DELETE FROM booking", "read-only");
        assertRefused("EXPLAIN ANALYZE UPDATE booking SET status = 'CANCELLED'", "read-only");
        assertRefused("EXPLAIN FORMAT=TREE UPDATE booking SET refund_amount = 0", "read-only");
        assertRefused("EXPLAIN ANALYZE /*! DELETE FROM booking WHERE booking_id = -1 */", "read-only");
    }

    @Test
    void refusesTwoStatements() {
        assertRefused("SELECT 1; DELETE FROM movie", "one statement");
    }

    @Test
    void refusesSelectFormsThatWriteFilesOrLockRows() {
        assertRefused("SELECT * FROM movie INTO OUTFILE '/tmp/movies.txt'", "blocked");
        assertRefused("SELECT * FROM booking FOR UPDATE", "blocked");
        assertRefused("SELECT * FROM booking FOR SHARE", "blocked");
        assertRefused("SELECT * FROM booking LOCK IN SHARE MODE", "blocked");
        assertRefused("SELECT LOAD_FILE('/etc/passwd')", "blocked");
    }

    @Test
    void refusesReadSideFormsWithCommentsBetweenTheirKeywords() {
        assertRefused("SELECT * FROM movie INTO/**/OUTFILE '/tmp/movies.txt'", "blocked");
        assertRefused("SELECT * FROM movie INTO-- comment\nOUTFILE '/tmp/movies.txt'", "blocked");
        assertRefused("SELECT * FROM movie INTO-- comment\rOUTFILE '/tmp/movies.txt'", "blocked");
        assertRefused("SELECT * FROM movie INTO # comment\nOUTFILE '/tmp/movies.txt'", "blocked");
        assertRefused("SELECT * FROM movie INTO /* comment */ DUMPFILE '/tmp/x'", "blocked");
        assertRefused("SELECT * FROM movie INTO /*+ hint */ OUTFILE '/tmp/movies.txt'", "blocked");
        assertRefused("SELECT LOAD_FILE /* comment */ ('/etc/passwd')", "blocked");
        assertRefused("SELECT LOAD_FILE\n('/etc/passwd')", "blocked");
        assertRefused("SELECT `LOAD_FILE`('/etc/passwd')", "blocked");
        assertRefused("SELECT \"LOAD_FILE\"('/etc/passwd')", "blocked");
        assertRefused("SELECT * FROM booking FOR/**/UPDATE", "blocked");
        assertRefused("SELECT * FROM booking FOR-- comment\nSHARE", "blocked");
        assertRefused("SELECT * FROM booking LOCK/**/IN/**/SHARE/**/MODE", "blocked");
    }

    @Test
    void doesNotRefuseBlockedWordsInsideStringsOrComments() throws Exception {
        assertEquals("SELECT 'INTO OUTFILE' AS note",
                SqlGuard.validate("SELECT 'INTO OUTFILE' AS note"));
        assertEquals("SELECT 'FOR UPDATE' AS note",
                SqlGuard.validate("SELECT 'FOR UPDATE' AS note"));
        assertEquals("SELECT 'LOAD_FILE' AS note",
                SqlGuard.validate("SELECT 'LOAD_FILE' AS note"));
        assertEquals("SELECT 1 /* INTO OUTFILE '/tmp/x' */",
                SqlGuard.validate("SELECT 1 /* INTO OUTFILE '/tmp/x' */"));
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
