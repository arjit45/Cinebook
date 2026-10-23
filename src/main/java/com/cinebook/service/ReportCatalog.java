package com.cinebook.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The one registry behind both surfaces: the five report tabs and the SQL
 * console's preset list.
 *
 * <p>The preset list is built from the tab objects first, so a tab's SQL cannot
 * drift from the same query in the console. The eight follow-up queries live
 * here because they are console material only. The numbering follows
 * {@code db/04_queries.sql}.</p>
 */
public final class ReportCatalog {

    private static final List<ReportQuery> TABS = List.of(
            new RevenueByScreen(),
            new TopFilms(),
            new DormantFilms(),
            new OccupancyReport(),
            new LoyalCustomers());

    private static final String UPCOMING_SQL = """
            SELECT m.title                                   AS movie,
                   sc.name                                   AS screen,
                   st.show_date,
                   st.start_time,
                   st.base_price,
                   sc.seat_capacity - COUNT(t.seat_id)       AS seats_available
            FROM showtime st
            JOIN movie  m  ON m.movie_id  = st.movie_id
            JOIN screen sc ON sc.screen_id = st.screen_id
            LEFT JOIN ticket t ON t.showtime_id = st.showtime_id
            WHERE st.show_date >= CURDATE()
            GROUP BY st.showtime_id, m.title, sc.name, st.show_date,
                     st.start_time, st.base_price, sc.seat_capacity
            ORDER BY st.show_date, st.start_time, sc.name, st.showtime_id
            LIMIT 15
            """;

    private static final String RECENT_BOOKINGS_SQL = """
            SELECT b.booking_id,
                   c.full_name      AS customer,
                   m.title          AS movie,
                   st.show_date,
                   st.start_time,
                   sc.name          AS screen,
                   sa.row_label,
                   sa.seat_no,
                   sa.seat_class,
                   t.price,
                   su.full_name     AS sold_by
            FROM booking b
            JOIN customer   c  ON c.customer_id  = b.customer_id
            JOIN showtime   st ON st.showtime_id = b.showtime_id
            JOIN movie      m  ON m.movie_id     = st.movie_id
            JOIN screen     sc ON sc.screen_id   = st.screen_id
            JOIN ticket     t  ON t.booking_id   = b.booking_id
            JOIN seat       sa ON sa.seat_id     = t.seat_id
            JOIN staff_user su ON su.staff_id    = b.sold_by_staff_id
            WHERE b.status = 'CONFIRMED'
            ORDER BY b.booking_id DESC
            LIMIT 20
            """;

    private static final String DORMANT_NOT_EXISTS_SQL = """
            SELECT m.movie_id, m.title
            FROM movie m
            WHERE NOT EXISTS (
                    SELECT 1
                    FROM showtime st
                    WHERE st.movie_id = m.movie_id
                      AND st.show_date >= CURDATE() - INTERVAL 6 MONTH
                  )
            ORDER BY m.title
            """;

    private static final String RECONCILIATION_SQL = """
            SELECT b.booking_id,
                   b.total_amount                       AS booking_total,
                   b.ticket_amount + b.fnb_amount       AS split_total,
                   ROUND(COALESCE(t.ticket_total, 0), 2) AS tickets_on_file,
                   ROUND(COALESCE(p.paid_total, 0), 2)   AS paid_total
            FROM booking b
            LEFT JOIN (SELECT booking_id, SUM(price) AS ticket_total
                       FROM ticket GROUP BY booking_id) t ON t.booking_id = b.booking_id
            LEFT JOIN (SELECT booking_id, SUM(amount) AS paid_total
                       FROM payment GROUP BY booking_id) p ON p.booking_id = b.booking_id
            WHERE b.status = 'CONFIRMED'
              AND (b.total_amount  <> b.ticket_amount + b.fnb_amount
                   OR b.ticket_amount <> COALESCE(t.ticket_total, 0)
                   OR b.total_amount <> COALESCE(p.paid_total, 0))
            """;

    private static final String FILM_RANK_SQL = """
            SELECT title, genre, revenue,
                   RANK() OVER (ORDER BY revenue DESC)      AS revenue_rank,
                   ROUND(100 * revenue / SUM(revenue) OVER (), 2) AS pct_of_total
            FROM v_movie_performance
            WHERE revenue > 0
            ORDER BY revenue_rank
            """;

    private static final String NEVER_SOLD_SEATS_SQL = """
            SELECT sc.name AS screen, sa.row_label, sa.seat_no, sa.seat_class
            FROM seat sa
            JOIN screen sc ON sc.screen_id = sa.screen_id
            LEFT JOIN ticket t ON t.seat_id = sa.seat_id
            WHERE t.seat_id IS NULL
            ORDER BY sc.name, sa.row_label, sa.seat_no
            LIMIT 15
            """;

    private static final String FOOD_VS_TICKETS_SQL = """
            SELECT m.title,
                   ROUND(tk.ticket_revenue, 2)                  AS ticket_revenue,
                   ROUND(COALESCE(fn.fnb_revenue, 0), 2)        AS fnb_revenue,
                   ROUND(100 * COALESCE(fn.fnb_revenue, 0)
                         / NULLIF(tk.ticket_revenue, 0), 1)     AS fnb_pct_of_tickets
            FROM movie m
            JOIN (SELECT st.movie_id, SUM(b.ticket_amount) AS ticket_revenue
                  FROM booking b
                  JOIN showtime st ON st.showtime_id = b.showtime_id
                  WHERE b.status = 'CONFIRMED'
                  GROUP BY st.movie_id) tk ON tk.movie_id = m.movie_id
            LEFT JOIN (SELECT st.movie_id, SUM(b.fnb_amount) AS fnb_revenue
                       FROM booking b
                       JOIN showtime st ON st.showtime_id = b.showtime_id
                       WHERE b.status = 'CONFIRMED'
                       GROUP BY st.movie_id) fn ON fn.movie_id = m.movie_id
            ORDER BY ticket_revenue DESC
            """;

    private static final String TODAY_TAKINGS_SQL = """
            SELECT p.method,
                   COUNT(*)                        AS bookings,
                   ROUND(SUM(b.ticket_amount), 2)  AS ticket_taken,
                   ROUND(SUM(b.fnb_amount), 2)     AS fnb_taken,
                   ROUND(SUM(p.amount), 2)         AS gross
            FROM booking b
            JOIN payment p ON p.booking_id = b.booking_id
            WHERE DATE(p.paid_at) = CURDATE()
            GROUP BY p.method
            ORDER BY FIELD(p.method, 'CARD', 'UPI', 'CASH'), p.method
            """;

    private ReportCatalog() {
    }

    /** The five tabs, in the order the ledger opens them. */
    public static List<ReportQuery> tabs() {
        return TABS;
    }

    /** Thirteen numbered queries for the console dropdown, in query order. */
    public static List<SqlPreset> presets() {
        List<SqlPreset> presets = new ArrayList<>();
        for (ReportQuery tab : TABS) {
            presets.add(new SqlPreset(tab.id(), tab.title(), tab.question(), tab.sql()));
        }
        presets.add(new SqlPreset("1", "Upcoming screenings",
                "Every remaining screening with seats left", UPCOMING_SQL));
        presets.add(new SqlPreset("3", "Recent bookings",
                "Confirmed bookings with seats and the clerk who sold them", RECENT_BOOKINGS_SQL));
        presets.add(new SqlPreset("7", "Dormant films, NOT EXISTS",
                "Dormant films written a second way", DORMANT_NOT_EXISTS_SQL));
        presets.add(new SqlPreset("8", "Money reconciliation",
                "Confirmed bookings whose money does not add up", RECONCILIATION_SQL));
        presets.add(new SqlPreset("9", "Film revenue ranking",
                "Films ranked by ticket revenue", FILM_RANK_SQL));
        presets.add(new SqlPreset("11", "Never-sold seats",
                "Seats that have never appeared on a ticket", NEVER_SOLD_SEATS_SQL));
        presets.add(new SqlPreset("12", "Food versus tickets",
                "How much of each film's money is food", FOOD_VS_TICKETS_SQL));
        presets.add(new SqlPreset("13", "Today's takings",
                "Today's payments split by method, all clerks", TODAY_TAKINGS_SQL));
        presets.sort(Comparator.comparingInt(preset -> Integer.parseInt(preset.id())));
        return List.copyOf(presets);
    }
}
