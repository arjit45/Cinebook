package com.cinebook.cli;

import com.cinebook.dao.SeatDao;
import com.cinebook.dao.ShowtimeDao;
import com.cinebook.exception.DbUnavailableException;
import com.cinebook.model.Seat;
import com.cinebook.model.Showtime;
import com.cinebook.util.Db;

import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Phase 1's proof, and the habit the PRD asks for in §9: something runnable at the
 * end of every phase, verified with a {@code main()} before a single screen exists.
 *
 * <p>It reads the seeded schedule through {@link ShowtimeDao} and the seat map
 * through {@link SeatDao}, then prints it. If the numbers here disagree with
 * {@code db/04_queries.sql}, the bug is in this layer, not in the database — which
 * is exactly the question a phase-1 check should be able to answer.
 *
 * <pre>
 *   mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 *   java -cp "target/classes;$(cat target/cp.txt)" com.cinebook.cli.ScheduleReport 8
 * </pre>
 */
public final class ScheduleReport {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE dd MMM");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private ScheduleReport() {
    }

    public static void main(String[] args) {
        int limit = args.length > 0 ? Integer.parseInt(args[0]) : 10;

        try {
            Db db = Db.get();
            List<Showtime> shows = new ShowtimeDao(db).upcoming(limit);

            System.out.printf("Upcoming screenings: %d%n%n", shows.size());
            System.out.printf("%-12s %-5s  %-28s %-8s %8s  %5s%n",
                    "DATE", "TIME", "FILM", "SCREEN", "PRICE", "SOLD");
            System.out.println("-".repeat(74));
            for (Showtime show : shows) {
                System.out.printf("%-12s %-5s  %-28s %-8s %8s  %2d/%-2d%n",
                        show.showDate().format(DATE),
                        show.startTime().format(TIME),
                        trim(show.movie().title(), 28),
                        trim(show.screen().name(), 8),
                        show.basePrice().toPlainString(),
                        show.seatsSold(),
                        show.screen().seatCapacity());
            }

            if (!shows.isEmpty()) {
                Showtime first = shows.get(0);
                SeatDao seats = new SeatDao(db);
                List<Seat> all = seats.byScreen(first.screen().screenId());
                var sold = seats.soldLabels(first.showtimeId());
                System.out.printf("%nSeat map check - %s, %s %s%n",
                        first.screen().name(),
                        first.showDate().format(DATE),
                        first.startTime().format(TIME));
                System.out.printf("  %d seats built from the row labels, %d sold, %d free%n",
                        all.size(), sold.size(), all.size() - sold.size());
                System.out.printf("  first 8 labels: %s%n",
                        all.subList(0, Math.min(8, all.size())).stream()
                                .map(Seat::label)
                                .reduce((a, b) -> a + " " + b).orElse("-"));
            }

            db.close();
        } catch (DbUnavailableException e) {
            System.err.println("Database unavailable: " + e.getMessage());
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Query failed: " + e.getMessage());
            System.exit(2);
        }
    }

    private static String trim(String value, int width) {
        return value.length() <= width ? value : value.substring(0, width - 2) + "..";
    }
}
