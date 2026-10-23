package com.cinebook.util;

import com.cinebook.exception.DbUnavailableException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one place in the application that opens a database connection, and — since
 * docs/PRD.md §11.7 — a borrow-and-return handoff rather than a single shared
 * connection.
 *
 * <p><b>Why three.</b> The design admits exactly three concurrent consumers: two
 * counter windows and the 60-second hold-expiry job. Starvation is prevented by
 * that construction, not by the queue — there is no fourth caller to starve. The
 * {@link ArrayBlockingQueue} is the backpressure valve: if one appears anyway it
 * waits a bounded time and then fails loudly, rather than opening a fourth
 * connection and overwhelming a MySQL whose {@code max_connections} is small.
 *
 * <p><b>Why borrow at all.</b> A single shared connection is safe only while
 * nothing can interleave on it. The moment the expiry job runs beside a seat-map
 * query, two statements land on one connection and — with autocommit on — one
 * commits half of the other's work. No exception, no deadlock, just money that
 * does not add up. Isolation here is structural: {@link #borrow()} hands out a
 * connection this unit of work owns exclusively, and {@link Handle#close()}
 * gives it back.
 *
 * <p><b>One borrow per unit of work.</b> A thread that borrows while already
 * holding one is refused outright. Nesting two borrows is how a three-connection
 * pool deadlocks: three consumers each holding one and each requesting a second
 * can never make progress. The rule is cheap to state and easy to erode by
 * accident, so it is a runtime check rather than a comment. Nested code passes
 * the borrowed {@link Connection} down instead.
 *
 * <p>Every borrow is validated first (§13.3): a connection opened in the morning
 * is dead by the afternoon, because MySQL's {@code wait_timeout} closes it. A
 * dead connection is replaced, never handed out.
 *
 * <p>The application account is {@code cinebook_app}: DML plus EXECUTE on
 * {@code sp_confirm_booking}, and no DDL. If this class ever succeeds at
 * {@code CREATE TABLE}, the grants are wrong.
 */
public final class Db {

    private static final String CONFIG_RESOURCE = "/app.properties";
    private static final String CONFIG_FILE = "app.properties";
    private static final String CONFIG_HELP =
            "Copy app.properties.example to app.properties in the project root (next to "
                    + "run.bat) and set jdbc.password to the password chosen in db/05_users.sql.";

    /** Two windows and the expiry job (docs/PRD.md §11.7). */
    static final int POOL_SIZE = 3;

    /** Long enough to ride out a checkpoint, short enough to fail inside a demo. */
    private static final int BORROW_TIMEOUT_SECONDS = 10;

    /** How long a borrow waits to hear that a connection is still alive. */
    private static final int VALIDATE_TIMEOUT_SECONDS = 2;

    private static Db instance;

    private final String url;
    private final String user;
    private final String password;

    private final ArrayBlockingQueue<Connection> pool = new ArrayBlockingQueue<>(POOL_SIZE);
    private final AtomicInteger openCount = new AtomicInteger();

    /**
     * How many connections this thread is holding right now. Used only to refuse a
     * nested borrow; never read across threads, so plain ThreadLocal is enough.
     */
    private final ThreadLocal<Integer> heldByThisThread = ThreadLocal.withInitial(() -> 0);

    private Db(Properties config) {
        this.url = required(config, "jdbc.url");
        this.user = required(config, "jdbc.user");
        this.password = required(config, "jdbc.password");
    }

    /** The shared instance, loading {@code app.properties} on first use. */
    public static synchronized Db get() {
        if (instance == null) {
            instance = new Db(loadConfig());
        }
        return instance;
    }

    /**
     * Borrows a connection for the length of one unit of work.
     *
     * <p>Always use it in try-with-resources so the connection comes back even
     * when the work throws:
     * <pre>{@code
     * try (Db.Handle handle = db.borrow();
     *      PreparedStatement sql = handle.connection().prepareStatement(...)) {
     *     ...
     * }
     * }</pre>
     *
     * @throws DbUnavailableException if MySQL cannot be reached, or if all three
     *                                 connections stayed out longer than
     *                                 {@link #BORROW_TIMEOUT_SECONDS}
     * @throws IllegalStateException  if this thread is already holding one — the
     *                                 one-borrow-per-unit-of-work rule
     */
    public Handle borrow() {
        guardAgainstNestedBorrow();
        Connection connection = acquire();
        heldByThisThread.set(heldByThisThread.get() + 1);
        return new Handle(connection);
    }

    /**
     * Refuses a second borrow on the same thread.
     *
     * <p>This is the invariant that keeps a three-connection pool from
     * deadlocking. Pass the connection you already hold to the nested call
     * instead — that is what {@code BookingDao}'s {@code Connection}-taking
     * methods are for.
     */
    private void guardAgainstNestedBorrow() {
        if (heldByThisThread.get() > 0) {
            throw new IllegalStateException(
                    "This thread already holds a database connection. One borrow per unit of "
                            + "work: pass the borrowed connection down to nested calls instead "
                            + "of borrowing a second one.");
        }
    }

    /** Takes an idle connection, filling the pool if it is empty. */
    private Connection acquire() {
        Connection connection = pool.poll();
        if (connection != null) {
            return isAlive(connection) ? connection : replace(connection);
        }
        fill();
        return await();
    }

    /**
     * Opens connections up to {@link #POOL_SIZE} on first use, so a stopped MySQL
     * surfaces as a message on the sign-in screen rather than a window that never
     * appears (docs/PRD.md §11.4). Synchronized only on the cold path — once the
     * pool is full this never runs again.
     */
    private synchronized void fill() {
        if (pool.size() >= POOL_SIZE) {
            return;
        }
        // Anything already queued failed validation and was discarded; start clean
        // so a persistent failure is reported once rather than compounded.
        Connection stale;
        while ((stale = pool.poll()) != null) {
            closeQuietly(stale);
            openCount.decrementAndGet();
        }
        for (int i = pool.size(); i < POOL_SIZE; i++) {
            try {
                pool.put(DriverManager.getConnection(url, user, password));
                openCount.incrementAndGet();
            } catch (SQLException failure) {
                throw translate(failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DbUnavailableException(
                        "Interrupted while opening the database pool.", interrupted);
            }
        }
    }

    /** Waits a bounded time for a connection to come back rather than blocking forever. */
    private Connection await() {
        Connection connection;
        try {
            connection = pool.poll(BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DbUnavailableException("Interrupted while waiting for a database connection.",
                    interrupted);
        }
        if (connection == null) {
            throw new DbUnavailableException(
                    "All " + POOL_SIZE + " database connections were still in use after "
                            + BORROW_TIMEOUT_SECONDS + " seconds. A unit of work is holding one "
                            + "for too long.", null);
        }
        return isAlive(connection) ? connection : replace(connection);
    }

    /**
     * Disposes a connection that failed validation and opens a fresh one in its
     * place, so the pool keeps its full size across a MySQL restart or a timeout.
     */
    private Connection replace(Connection dead) {
        closeQuietly(dead);
        openCount.decrementAndGet();
        try {
            Connection replacement = DriverManager.getConnection(url, user, password);
            openCount.incrementAndGet();
            return replacement;
        } catch (SQLException failure) {
            throw translate(failure);
        }
    }

    private boolean isAlive(Connection connection) {
        try {
            return connection.isValid(VALIDATE_TIMEOUT_SECONDS);
        } catch (SQLException dead) {
            return false;
        }
    }

    /** Idle connections currently queued. Package-private for the pool's own test. */
    int idleConnections() {
        return pool.size();
    }

    /** Connections this instance has open. Package-private for the pool's own test. */
    int openConnections() {
        return openCount.get();
    }

    /**
     * Closes every idle connection, emptying the pool.
     *
     * <p>Called from {@code App.stop()} so no MySQL session outlives the window
     * (docs/PRD.md §11.7) — the window stops borrowing at that point, so nothing
     * reopens. Deliberately <em>not</em> a terminal latch: {@code Db} is a
     * singleton, and the next borrow simply refills the pool. That keeps the
     * pre-pool contract, which the test suites depend on when one class's
     * teardown runs before the next class's setup.
     */
    public synchronized void close() {
        Connection connection;
        while ((connection = pool.poll()) != null) {
            closeQuietly(connection);
            openCount.decrementAndGet();
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException alreadyBroken) {
            // A connection that cannot be closed is of no further use either way;
            // the reference is dropped and the next borrow opens a fresh one.
        }
    }

    /**
     * Turns the two failures with an obvious user-facing meaning into
     * {@link DbUnavailableException}, and passes everything else through with the
     * original cause attached.
     */
    private DbUnavailableException translate(SQLException e) {
        // 1045 arrives with SQLState 28000, so it is checked before the 08xxx family.
        if (e.getErrorCode() == 1045) {
            return new DbUnavailableException(
                    "MySQL rejected the login for '" + user + "'. Check jdbc.password in "
                            + "app.properties against the password in db/05_users.sql.", e);
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("08")) {
            return new DbUnavailableException(
                    "MySQL is not reachable at " + url + ". Is the MySQL service running?", e);
        }
        return new DbUnavailableException(
                "Could not open a database connection: " + e.getMessage(), e);
    }

    /**
     * A borrowed connection and the obligation to give it back.
     *
     * <p>Close it in try-with-resources. Doing so returns the connection to the
     * pool even when the unit of work failed, which is what stops a handful of
     * exceptions from emptying a three-connection pool.
     */
    public final class Handle implements AutoCloseable {

        private Connection connection;
        private boolean returned;

        private Handle(Connection connection) {
            this.connection = connection;
        }

        /**
         * The connection this unit of work owns exclusively.
         *
         * @throws IllegalStateException if the handle has already been closed
         */
        public Connection connection() {
            if (returned) {
                throw new IllegalStateException("This connection has already been returned.");
            }
            return connection;
        }

        /**
         * Returns the connection to the pool, reset and ready for the next borrower.
         *
         * <p>Autocommit is restored here rather than trusted to the caller: a unit
         * of work that turned it off and then threw would otherwise leak an open
         * transaction into someone else's borrow.
         */
        @Override
        public void close() {
            if (returned) {
                return;
            }
            returned = true;
            Connection toReturn = connection;
            connection = null;
            heldByThisThread.set(Math.max(0, heldByThisThread.get() - 1));
            if (resetBeforeReturn(toReturn)) {
                pool.offer(toReturn);
            }
        }

        /**
         * Rolls back and re-enables autocommit, so a unit of work that turned it off
         * and then threw cannot leak an open transaction into the next borrow.
         *
         * @return false if the connection was too broken to keep, so it is discarded
         *         rather than handed back in an unknown state
         */
        private boolean resetBeforeReturn(Connection toReturn) {
            try {
                if (!toReturn.getAutoCommit()) {
                    toReturn.rollback();
                    toReturn.setAutoCommit(true);
                }
                return true;
            } catch (SQLException broken) {
                closeQuietly(toReturn);
                openCount.decrementAndGet();
                return false;
            }
        }
    }

    private static Properties loadConfig() {
        Properties properties = new Properties();

        // The packaged jar is built without app.properties so it can be handed to
        // someone without handing over a password. run.bat starts in the project
        // root, where the local file sits next to app.properties.example; the
        // classpath lookup remains only for older development checkouts.
        Path file = Path.of(CONFIG_FILE);
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(in);
                return properties;
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Could not read " + file.toAbsolutePath() + ". " + CONFIG_HELP, e);
            }
        }

        try (InputStream in = Db.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "app.properties was not found next to run.bat or on the classpath. "
                                + CONFIG_HELP);
            }
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + CONFIG_RESOURCE + ". " + CONFIG_HELP, e);
        }
        return properties;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("'" + key + "' is blank in app.properties. " + CONFIG_HELP);
        }
        // The password is not trimmed: whitespace may be intentional.
        return key.equals("jdbc.password") ? value : value.trim();
    }
}
