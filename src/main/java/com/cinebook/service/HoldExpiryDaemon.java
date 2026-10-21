package com.cinebook.service;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background maintenance daemon that periodically sweeps and releases expired
 * seat holds (docs/PRD.md §11.7, §13.3).
 *
 * <p>Runs on a single daemon thread every 60 seconds, releasing abandoned holds
 * so that physical auditorium seats are restored to inventory.
 *
 * <p>Supports clean lifecycle control ({@link #start()} and {@link #stop()})
 * and an injectable {@link Clock} so integration tests never experience race
 * conditions or uncoordinated background cancellations.
 */
public final class HoldExpiryDaemon {

    public static final long DEFAULT_PERIOD_SECONDS = 60L;
    public static final int HOLD_TTL_MINUTES = 10;

    private final BookingService bookingService;
    private final Clock clock;
    private final long periodSeconds;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;

    public HoldExpiryDaemon(BookingService bookingService) {
        this(bookingService, Clock.systemDefaultZone(), DEFAULT_PERIOD_SECONDS);
    }

    public HoldExpiryDaemon(BookingService bookingService, Clock clock, long periodSeconds) {
        this.bookingService = Objects.requireNonNull(bookingService, "bookingService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.periodSeconds = Math.max(1L, periodSeconds);
    }

    /**
     * Starts the periodic background sweeper. Runs an initial sweep immediately,
     * then schedules subsequent sweeps at the configured interval.
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cinebook-hold-expiry");
                thread.setDaemon(true);
                return thread;
            });
            // Initial sweep on start, then repeat every periodSeconds
            scheduler.scheduleWithFixedDelay(this::safeSweep, 0L, periodSeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * Shuts down the background sweeper gracefully.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false) && scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Executes a single sweep synchronously, using {@code now() - 10 minutes}.
     *
     * @return number of holds swept
     */
    public int sweepNow() throws SQLException {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusMinutes(HOLD_TTL_MINUTES);
        return bookingService.sweepExpiredHolds(cutoff);
    }

    private void safeSweep() {
        try {
            int swept = sweepNow();
            if (swept > 0) {
                System.out.printf("[HoldExpiryDaemon] Swept %d expired hold(s)%n", swept);
            }
        } catch (Throwable error) {
            System.err.printf("[HoldExpiryDaemon] Sweep failed: %s%n", error.getMessage());
        }
    }
}
