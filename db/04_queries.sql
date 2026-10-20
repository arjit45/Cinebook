-- ============================================================
--  CineBook  --  File 4 of 5 : REPORT QUERIES
--  Run AFTER 03_objects.sql
--
--  Queries 1-5 are the five patterns your lab sheet uses for
--  every project. Queries 6-13 are the follow-ups an examiner
--  is likely to invent on the spot; 14 is the transaction drill
--  you walk through on a HELD booking during the viva.
--
--  One recurring trap, visible in 2, 5 and 12: a booking has many
--  tickets AND many food lines, so joining both in one statement
--  multiplies the rows and inflates every SUM. The money columns
--  live on `booking` for exactly this reason — read them from
--  there, and aggregate the many-side in its own subquery.
-- ============================================================
USE cinebook;

-- ============================================================
-- PATTERN 1 -- "Retrieve all X with attributes, sorted"
-- All upcoming screenings with seats still available.
-- ============================================================
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
LIMIT 15;


-- ============================================================
-- PATTERN 2 -- "Count per category / GROUP BY"
-- Bookings, seats and money per auditorium, last 30 days,
-- with the ticket and food revenue shown separately.
-- ============================================================
SELECT sc.name                            AS screen,
       COUNT(*)                           AS bookings,
       COALESCE(SUM(tk.tickets), 0)       AS tickets_sold,
       ROUND(SUM(b.ticket_amount), 2)     AS ticket_revenue,
       ROUND(SUM(b.fnb_amount), 2)        AS fnb_revenue,
       ROUND(SUM(b.total_amount), 2)      AS total_revenue
FROM booking b
JOIN showtime st ON st.showtime_id = b.showtime_id
JOIN screen   sc ON sc.screen_id   = st.screen_id
LEFT JOIN (SELECT booking_id, COUNT(*) AS tickets
           FROM ticket GROUP BY booking_id) tk
       ON tk.booking_id = b.booking_id
WHERE b.status = 'CONFIRMED'
  AND st.show_date >= CURDATE() - INTERVAL 30 DAY
GROUP BY sc.screen_id, sc.name
ORDER BY total_revenue DESC;


-- ============================================================
-- PATTERN 3 -- "Detail list joining three or more tables"
-- Most recent confirmed bookings with full detail, including
-- the clerk who sold each one. Eight tables.
-- ============================================================
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
LIMIT 20;


-- ============================================================
-- PATTERN 4 -- "X with no activity in the last N months"
-- Films not screened in the last six months.
-- ============================================================
SELECT m.movie_id,
       m.title,
       m.genre,
       MAX(st.show_date)                        AS last_screened_on,
       COALESCE(DATEDIFF(CURDATE(), MAX(st.show_date)), 9999) AS days_dormant
FROM movie m
LEFT JOIN showtime st ON st.movie_id = m.movie_id
GROUP BY m.movie_id, m.title, m.genre
HAVING MAX(st.show_date) IS NULL
    OR MAX(st.show_date) < CURDATE() - INTERVAL 6 MONTH
ORDER BY last_screened_on;


-- ============================================================
-- PATTERN 5 -- "Top 3 by aggregate value"
-- Highest grossing films of the last month, where grossing now
-- means tickets AND food, because that is what the till banked.
-- ============================================================
SELECT m.title,
       COUNT(*)                          AS bookings,
       COALESCE(SUM(tk.tickets), 0)      AS tickets_sold,
       ROUND(SUM(b.total_amount), 2)     AS revenue
FROM booking b
JOIN showtime st ON st.showtime_id = b.showtime_id
JOIN movie    m  ON m.movie_id     = st.movie_id
LEFT JOIN (SELECT booking_id, COUNT(*) AS tickets
           FROM ticket GROUP BY booking_id) tk
       ON tk.booking_id = b.booking_id
WHERE b.status = 'CONFIRMED'
  AND st.show_date >= CURDATE() - INTERVAL 30 DAY
GROUP BY m.movie_id, m.title
ORDER BY revenue DESC
LIMIT 3;


-- ============================================================
-- 6. HAVING -- loyal customers (more than 3 confirmed bookings).
--    total_spent is the grand total, food included: the patron
--    spent it at this counter, whether it was a seat or a cola.
-- ============================================================
SELECT c.full_name,
       COUNT(*)                          AS bookings,
       ROUND(SUM(b.total_amount), 2)     AS total_spent
FROM customer c
JOIN booking b ON b.customer_id = c.customer_id
              AND b.status = 'CONFIRMED'
GROUP BY c.customer_id, c.full_name
HAVING COUNT(*) > 3
ORDER BY total_spent DESC;


-- ============================================================
-- 7. Same answer as Pattern 4, written with NOT EXISTS.
--    Worth showing both -- the examiner often asks for the
--    other form.
-- ============================================================
SELECT m.movie_id, m.title
FROM movie m
WHERE NOT EXISTS (
        SELECT 1
        FROM showtime st
        WHERE st.movie_id = m.movie_id
          AND st.show_date >= CURDATE() - INTERVAL 6 MONTH
      )
ORDER BY m.title;


-- ============================================================
-- 8. Derived tables -- the three-way money reconciliation.
--    For a CONFIRMED booking, all of these must hold:
--        total_amount   = ticket_amount + fnb_amount
--        ticket_amount  = SUM(ticket.price)
--        total_amount   = payment.amount
--    Should return zero rows. This is the query that catches a
--    discount sneaking in, or a half-written update.
-- ============================================================
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
       OR b.total_amount <> COALESCE(p.paid_total, 0));


-- ============================================================
-- 9. Window function -- rank films by ticket revenue
--    (no LIMIT needed)
-- ============================================================
SELECT title, genre, revenue,
       RANK() OVER (ORDER BY revenue DESC)      AS revenue_rank,
       ROUND(100 * revenue / SUM(revenue) OVER (), 2) AS pct_of_total
FROM v_movie_performance
WHERE revenue > 0
ORDER BY revenue_rank;


-- ============================================================
-- 10. Occupancy view -- how full is each remaining screening?
-- ============================================================
SELECT movie, screen, show_date, start_time,
       seats_sold, seats_available,
       ROUND(100 * seats_sold / seat_capacity, 1) AS occupancy_pct
FROM v_show_occupancy
WHERE show_date >= CURDATE()
ORDER BY occupancy_pct DESC
LIMIT 10;


-- ============================================================
-- 11. LEFT JOIN + IS NULL -- seats that have never been sold
-- ============================================================
SELECT sc.name AS screen, sa.row_label, sa.seat_no, sa.seat_class
FROM seat sa
JOIN screen sc ON sc.screen_id = sa.screen_id
LEFT JOIN ticket t ON t.seat_id = sa.seat_id
WHERE t.seat_id IS NULL
ORDER BY sc.name, sa.row_label, sa.seat_no
LIMIT 15;


-- ============================================================
-- 12. The F&B-vs-ticket query.
--     How much of each film's money is popcorn?
--
--     Two subqueries, not one join. `booking` is the only table
--     both sides share, and each side aggregates to one row per
--     movie before the join, so nothing multiplies. Writing this
--     as booking JOIN ticket JOIN booking_concession would count a
--     2-popcorn, 3-ticket booking eighteen times.
-- ============================================================
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
ORDER BY ticket_revenue DESC;


-- ============================================================
-- 13. SHIFT SUMMARY (the Z-report).
--     One clerk, one day, and the two questions that matter:
--     what did I take, and what did I pay back?
--
--     The application passes ? for @staff_id. Refunds are counted
--     against cancelled_by_staff_id, NOT sold_by_staff_id --
--     otherwise a refund taken by the evening clerk is charged to
--     the morning clerk who sold the ticket. That distinction is
--     the whole reason the second column exists.
-- ============================================================
SET @staff_id = 1;

-- 13a. What this clerk took today, split by method
SELECT p.method,
       COUNT(*)                        AS bookings,
       ROUND(SUM(b.ticket_amount), 2)  AS ticket_taken,
       ROUND(SUM(b.fnb_amount), 2)     AS fnb_taken,
       ROUND(SUM(p.amount), 2)         AS gross
FROM booking b
JOIN payment p ON p.booking_id = b.booking_id
WHERE b.sold_by_staff_id = @staff_id
  AND DATE(p.paid_at) = CURDATE()
GROUP BY p.method
ORDER BY p.method;

-- 13b. What this clerk paid back today
SELECT COUNT(*)                              AS refunds,
       ROUND(COALESCE(SUM(refund_amount), 0), 2) AS refunded
FROM booking
WHERE cancelled_by_staff_id = @staff_id
  AND DATE(cancelled_at) = CURDATE();


-- ============================================================
-- 14. TRANSACTION demo for the viva.
--     Run these by hand on a HELD booking:
--
--       SELECT booking_id, status, total_amount
--         FROM booking WHERE status='HELD' LIMIT 1;
--       CALL sp_confirm_booking(<that id>, 'UPI');
--       SELECT * FROM booking       WHERE booking_id = <that id>;
--       SELECT * FROM payment       WHERE booking_id = <that id>;
--       SELECT * FROM booking_audit WHERE booking_id = <that id>;
--
--     The four SELECTs prove: status moved HELD -> CONFIRMED, the
--     three money columns agree, money was recorded, and the
--     trigger wrote the audit row nobody asked it for.
-- ============================================================
