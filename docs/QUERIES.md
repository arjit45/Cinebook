# CineBook — query catalogue

> **Generated file.** Produced by `tools/querydoc.py` straight from
> `db/04_queries.sql`. Do not edit by hand — edit the SQL and rerun
> `python tools/querydoc.py`. The script refuses to write if any
> statement cannot be classified or names a table that does not exist.

**14 numbered queries** · 13 return a result set · 1 is a manual drill.

Every output below was captured from MySQL 8.0.46 on 2026-10-22 against the seeded database; `tools/querydoc.py`
refuses to write if the SQL changes without a fresh capture. Rebuilding the seed on a
later day moves every printed calendar date — counts, revenue, rankings and
`days_dormant` are date-independent — so after a rebuild run `python tools/querydoc.py
--capture` to bring this page back in line with your database.

Run AFTER 03_objects.sql

Queries 1-5 are the five patterns your lab sheet uses for every project. Queries 6-13 are the follow-ups an examiner is likely to invent on the spot; 14 is the transaction drill you walk through on a HELD booking during the viva.

One recurring trap, visible in 2, 5 and 12: a booking has many tickets AND many food lines, so joining both in one statement multiplies the rows and inflates every SUM. The money columns live on `booking` for exactly this reason — read them from there, and aggregate the many-side in its own subquery.


## The five patterns

| # | Lab-sheet request | What this query returns |
|---|---|---|
| 1 | "Retrieve all X with attributes, sorted" | All upcoming screenings with seats still available. |
| 2 | "Count per category / GROUP BY" | Bookings, seats and money per auditorium, last 30 days, with the ticket and food revenue shown separately. |
| 3 | "Detail list joining three or more tables" | Most recent confirmed bookings with full detail, including the clerk who sold each one. Eight tables. |
| 4 | "X with no activity in the last N months" | Films not screened in the last six months. |
| 5 | "Top 3 by aggregate value" | Highest grossing films of the last month, where grossing now means tickets AND food, because that is what the till banked. |


## Follow-ups

| # | Question |
|---|---|
| 6 | HAVING — loyal customers (more than 3 confirmed bookings). |
| 7 | Same answer as Pattern 4, written with NOT EXISTS. |
| 8 | Derived tables — the three-way money reconciliation. |
| 9 | Window function — rank films by ticket revenue |
| 10 | Occupancy view — how full is each remaining screening? |
| 11 | LEFT JOIN + IS NULL — seats that have never been sold |
| 12 | The F&B-vs-ticket query. |
| 13 | SHIFT SUMMARY (the Z-report). |
| 14 | TRANSACTION demo for the viva. |


## 1. "Retrieve all X with attributes, sorted"

All upcoming screenings with seats still available.

```sql
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
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+-----------------+--------+------------+------------+------------+-----------------+
| movie           | screen | show_date  | start_time | base_price | seats_available |
+-----------------+--------+------------+------------+------------+-----------------+
| Chai & Chaos    | Audi 1 | 2026-10-22 | 10:30:00   |     280.00 |              93 |
| Neon Monsoon    | Audi 2 | 2026-10-22 | 13:15:00   |     320.00 |              93 |
| The Last Ledger | Audi 3 | 2026-10-22 | 19:00:00   |     360.00 |              93 |
| Paper Lanterns  | Audi 4 | 2026-10-22 | 21:30:00   |     400.00 |              93 |
| Silent Karst    | Audi 1 | 2026-10-23 | 13:15:00   |     220.00 |              93 |
| Chai & Chaos    | Audi 2 | 2026-10-23 | 16:20:00   |     260.00 |              95 |
| Neon Monsoon    | Audi 3 | 2026-10-23 | 21:30:00   |     300.00 |              94 |
| The Last Ledger | Audi 4 | 2026-10-23 | 21:30:00   |     340.00 |              94 |
| Chai & Chaos    | Audi 3 | 2026-10-24 | 10:30:00   |     330.00 |              93 |
| Neon Monsoon    | Audi 4 | 2026-10-24 | 13:15:00   |     370.00 |              93 |
| Turbo Kite      | Audi 1 | 2026-10-24 | 16:20:00   |     250.00 |              94 |
| Silent Karst    | Audi 2 | 2026-10-24 | 19:00:00   |     290.00 |              93 |
| Silent Karst    | Audi 3 | 2026-10-25 | 13:15:00   |     360.00 |              94 |
| Chai & Chaos    | Audi 4 | 2026-10-25 | 16:20:00   |     400.00 |              95 |
| Paper Lanterns  | Audi 1 | 2026-10-25 | 19:00:00   |     280.00 |              95 |
+-----------------+--------+------------+------------+------------+-----------------+
```


## 2. "Count per category / GROUP BY"

Bookings, seats and money per auditorium, last 30 days, with the ticket and food revenue shown separately.

```sql
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
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+--------+----------+--------------+----------------+-------------+---------------+
| screen | bookings | tickets_sold | ticket_revenue | fnb_revenue | total_revenue |
+--------+----------+--------------+----------------+-------------+---------------+
| Audi 1 |       60 |           60 |       14940.00 |    26680.00 |      41620.00 |
| Audi 4 |       61 |           61 |       22480.00 |        0.00 |      22480.00 |
| Audi 3 |       62 |           62 |       20400.00 |        0.00 |      20400.00 |
| Audi 2 |       61 |           61 |       17690.00 |        0.00 |      17690.00 |
+--------+----------+--------------+----------------+-------------+---------------+
```


## 3. "Detail list joining three or more tables"

Most recent confirmed bookings with full detail, including the clerk who sold each one. Eight tables.

```sql
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
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+------------+----------------+-----------------+------------+------------+--------+-----------+---------+------------+--------+----------------+
| booking_id | customer       | movie           | show_date  | start_time | screen | row_label | seat_no | seat_class | price  | sold_by        |
+------------+----------------+-----------------+------------+------------+--------+-----------+---------+------------+--------+----------------+
|        300 | Tara Seshadri  | Turbo Kite      | 2026-10-24 | 16:20:00   | Audi 1 | A         |       3 | REGULAR    | 250.00 | Leena Mathew   |
|        299 | Vihaan Joshi   | Silent Karst    | 2026-10-24 | 19:00:00   | Audi 2 | A         |       3 | REGULAR    | 290.00 | Meera Krishnan |
|        298 | Ritika Bose    | Chai & Chaos    | 2026-10-24 | 10:30:00   | Audi 3 | A         |       3 | REGULAR    | 330.00 | Leena Mathew   |
|        297 | Reyansh Kapoor | Neon Monsoon    | 2026-10-24 | 13:15:00   | Audi 4 | A         |       3 | REGULAR    | 370.00 | Meera Krishnan |
|        296 | Ananya Gupta   | Silent Karst    | 2026-10-23 | 13:15:00   | Audi 1 | A         |       3 | REGULAR    | 220.00 | Leena Mathew   |
|        294 | Nisha Bhat     | Neon Monsoon    | 2026-10-23 | 21:30:00   | Audi 3 | A         |       3 | REGULAR    | 300.00 | Leena Mathew   |
|        293 | Kabir Nair     | The Last Ledger | 2026-10-23 | 21:30:00   | Audi 4 | A         |       3 | REGULAR    | 340.00 | Meera Krishnan |
|        292 | Priya Ranjan   | Chai & Chaos    | 2026-10-22 | 10:30:00   | Audi 1 | A         |       3 | REGULAR    | 280.00 | Leena Mathew   |
|        291 | Aditya Verma   | Neon Monsoon    | 2026-10-22 | 13:15:00   | Audi 2 | A         |       3 | REGULAR    | 320.00 | Meera Krishnan |
|        290 | Ishita Rao     | The Last Ledger | 2026-10-22 | 19:00:00   | Audi 3 | A         |       3 | REGULAR    | 360.00 | Leena Mathew   |
|        289 | Nikhil Chandra | Paper Lanterns  | 2026-10-22 | 21:30:00   | Audi 4 | A         |       3 | REGULAR    | 400.00 | Meera Krishnan |
|        287 | Rohan Iyer     | The Last Ledger | 2026-10-21 | 10:30:00   | Audi 2 | A         |       3 | REGULAR    | 290.00 | Meera Krishnan |
|        285 | Arjun Deshmukh | Turbo Kite      | 2026-10-21 | 19:00:00   | Audi 4 | A         |       3 | REGULAR    | 370.00 | Meera Krishnan |
|        284 | Diya Menon     | The Last Ledger | 2026-10-20 | 19:00:00   | Audi 1 | A         |       3 | REGULAR    | 220.00 | Leena Mathew   |
|        283 | Dev Malhotra   | Paper Lanterns  | 2026-10-20 | 21:30:00   | Audi 2 | A         |       3 | REGULAR    | 260.00 | Meera Krishnan |
|        282 | Meera Pillai   | Turbo Kite      | 2026-10-20 | 13:15:00   | Audi 3 | A         |       3 | REGULAR    | 300.00 | Leena Mathew   |
|        280 | Tara Seshadri  | Paper Lanterns  | 2026-10-19 | 16:20:00   | Audi 1 | A         |       3 | REGULAR    | 280.00 | Leena Mathew   |
|        279 | Vihaan Joshi   | Turbo Kite      | 2026-10-19 | 19:00:00   | Audi 2 | A         |       3 | REGULAR    | 320.00 | Meera Krishnan |
|        278 | Ritika Bose    | Silent Karst    | 2026-10-19 | 10:30:00   | Audi 3 | A         |       3 | REGULAR    | 360.00 | Leena Mathew   |
|        277 | Reyansh Kapoor | Chai & Chaos    | 2026-10-19 | 13:15:00   | Audi 4 | A         |       3 | REGULAR    | 400.00 | Meera Krishnan |
+------------+----------------+-----------------+------------+------------+--------+-----------+---------+------------+--------+----------------+
```


## 4. "X with no activity in the last N months"

Films not screened in the last six months.

```sql
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
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+----------+---------------+-----------+------------------+--------------+
| movie_id | title         | genre     | last_screened_on | days_dormant |
+----------+---------------+-----------+------------------+--------------+
|        8 | Static Bloom  | Romance   | 2026-03-30       |          206 |
|        7 | Blue Meridian | Adventure | 2026-04-03       |          202 |
+----------+---------------+-----------+------------------+--------------+
```


## 5. "Top 3 by aggregate value"

Highest grossing films of the last month, where grossing now means tickets AND food, because that is what the till banked.

```sql
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
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+-----------------+----------+--------------+----------+
| title           | bookings | tickets_sold | revenue  |
+-----------------+----------+--------------+----------+
| Neon Monsoon    |       42 |           42 | 18510.00 |
| The Last Ledger |       43 |           43 | 18040.00 |
| Chai & Chaos    |       38 |           38 | 17040.00 |
+-----------------+----------+--------------+----------+
```


## 6. HAVING — loyal customers (more than 3 confirmed bookings).

total_spent is the grand total, food included: the patron spent it at this counter, whether it was a seat or a cola.

```sql
SELECT c.full_name,
       COUNT(*)                          AS bookings,
       ROUND(SUM(b.total_amount), 2)     AS total_spent
FROM customer c
JOIN booking b ON b.customer_id = c.customer_id
              AND b.status = 'CONFIRMED'
GROUP BY c.customer_id, c.full_name
HAVING COUNT(*) > 3
ORDER BY total_spent DESC;
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+-----------------+----------+-------------+
| full_name       | bookings | total_spent |
+-----------------+----------+-------------+
| Diya Menon      |       13 |     9360.00 |
| Priya Ranjan    |       12 |     8320.00 |
| Ananya Gupta    |       12 |     8250.00 |
| Tara Seshadri   |       12 |     7990.00 |
| Saanvi Kulkarni |       11 |     7700.00 |
| Arjun Deshmukh  |       13 |     4810.00 |
| Aarav Sharma    |       12 |     4470.00 |
| Kabir Nair      |       12 |     4410.00 |
| Nikhil Chandra  |       12 |     4410.00 |
| Reyansh Kapoor  |       12 |     4380.00 |
| Nisha Bhat      |       13 |     4290.00 |
| Meera Pillai    |       13 |     4170.00 |
| Ishita Rao      |       12 |     4050.00 |
| Zara Qureshi    |       12 |     3990.00 |
| Ritika Bose     |       12 |     3900.00 |
| Dev Malhotra    |       13 |     3710.00 |
| Rohan Iyer      |       13 |     3680.00 |
| Vihaan Joshi    |       12 |     3540.00 |
| Aditya Verma    |       12 |     3510.00 |
| Yash Thakur     |       11 |     3250.00 |
+-----------------+----------+-------------+
```


## 7. Same answer as Pattern 4, written with NOT EXISTS.

Worth showing both — the examiner often asks for the other form.

```sql
SELECT m.movie_id, m.title
FROM movie m
WHERE NOT EXISTS (
        SELECT 1
        FROM showtime st
        WHERE st.movie_id = m.movie_id
          AND st.show_date >= CURDATE() - INTERVAL 6 MONTH
      )
ORDER BY m.title;
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+----------+---------------+
| movie_id | title         |
+----------+---------------+
|        7 | Blue Meridian |
|        8 | Static Bloom  |
+----------+---------------+
```


## 8. Derived tables — the three-way money reconciliation.

For a CONFIRMED booking, all of these must hold:  
total_amount = ticket_amount + fnb_amount  
ticket_amount = SUM(ticket.price)  
total_amount = payment.amount  
Should return zero rows. This is the query that catches a discount sneaking in, or a half-written update.

```sql
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
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
(no rows)
```


## 9. Window function — rank films by ticket revenue

(no LIMIT needed)

```sql
SELECT title, genre, revenue,
       RANK() OVER (ORDER BY revenue DESC)      AS revenue_rank,
       ROUND(100 * revenue / SUM(revenue) OVER (), 2) AS pct_of_total
FROM v_movie_performance
WHERE revenue > 0
ORDER BY revenue_rank;
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+-----------------+-----------+----------+--------------+--------------+
| title           | genre     | revenue  | revenue_rank | pct_of_total |
+-----------------+-----------+----------+--------------+--------------+
| Neon Monsoon    | Sci-Fi    | 12900.00 |            1 |        17.08 |
| The Last Ledger | Thriller  | 12760.00 |            2 |        16.90 |
| Paper Lanterns  | Drama     | 12730.00 |            3 |        16.86 |
| Turbo Kite      | Animation | 12620.00 |            4 |        16.71 |
| Silent Karst    | Horror    | 12510.00 |            5 |        16.57 |
| Chai & Chaos    | Comedy    | 11990.00 |            6 |        15.88 |
+-----------------+-----------+----------+--------------+--------------+
```


## 10. Occupancy view — how full is each remaining screening?

```sql
SELECT movie, screen, show_date, start_time,
       seats_sold, seats_available,
       ROUND(100 * seats_sold / seat_capacity, 1) AS occupancy_pct
FROM v_show_occupancy
WHERE show_date >= CURDATE()
ORDER BY occupancy_pct DESC
LIMIT 10;
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+-----------------+--------+------------+------------+------------+-----------------+---------------+
| movie           | screen | show_date  | start_time | seats_sold | seats_available | occupancy_pct |
+-----------------+--------+------------+------------+------------+-----------------+---------------+
| Chai & Chaos    | Audi 1 | 2026-10-22 | 10:30:00   |          3 |              93 |           3.1 |
| Silent Karst    | Audi 1 | 2026-10-23 | 13:15:00   |          3 |              93 |           3.1 |
| Neon Monsoon    | Audi 2 | 2026-10-22 | 13:15:00   |          3 |              93 |           3.1 |
| Silent Karst    | Audi 2 | 2026-10-24 | 19:00:00   |          3 |              93 |           3.1 |
| The Last Ledger | Audi 3 | 2026-10-22 | 19:00:00   |          3 |              93 |           3.1 |
| Chai & Chaos    | Audi 3 | 2026-10-24 | 10:30:00   |          3 |              93 |           3.1 |
| Paper Lanterns  | Audi 4 | 2026-10-22 | 21:30:00   |          3 |              93 |           3.1 |
| Neon Monsoon    | Audi 4 | 2026-10-24 | 13:15:00   |          3 |              93 |           3.1 |
| Turbo Kite      | Audi 1 | 2026-10-24 | 16:20:00   |          2 |              94 |           2.1 |
| Neon Monsoon    | Audi 1 | 2026-10-27 | 10:30:00   |          2 |              94 |           2.1 |
+-----------------+--------+------------+------------+------------+-----------------+---------------+
```


## 11. LEFT JOIN + IS NULL — seats that have never been sold

```sql
SELECT sc.name AS screen, sa.row_label, sa.seat_no, sa.seat_class
FROM seat sa
JOIN screen sc ON sc.screen_id = sa.screen_id
LEFT JOIN ticket t ON t.seat_id = sa.seat_id
WHERE t.seat_id IS NULL
ORDER BY sc.name, sa.row_label, sa.seat_no
LIMIT 15;
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+--------+-----------+---------+------------+
| screen | row_label | seat_no | seat_class |
+--------+-----------+---------+------------+
| Audi 1 | A         |       4 | REGULAR    |
| Audi 1 | A         |       5 | REGULAR    |
| Audi 1 | A         |       6 | REGULAR    |
| Audi 1 | A         |       7 | REGULAR    |
| Audi 1 | A         |       8 | REGULAR    |
| Audi 1 | A         |       9 | REGULAR    |
| Audi 1 | A         |      10 | REGULAR    |
| Audi 1 | A         |      11 | REGULAR    |
| Audi 1 | A         |      12 | REGULAR    |
| Audi 1 | B         |       1 | REGULAR    |
| Audi 1 | B         |       2 | REGULAR    |
| Audi 1 | B         |       3 | REGULAR    |
| Audi 1 | B         |       4 | REGULAR    |
| Audi 1 | B         |       5 | REGULAR    |
| Audi 1 | B         |       6 | REGULAR    |
+--------+-----------+---------+------------+
```


## 12. The F&B-vs-ticket query.

How much of each film's money is popcorn?

Two subqueries, not one join. `booking` is the only table both sides share, and each side aggregates to one row per movie before the join, so nothing multiplies. Writing this as booking JOIN ticket JOIN booking_concession would count a 2-popcorn, 3-ticket booking eighteen times.

```sql
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
```

*1 statement(s): SELECT.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+-----------------+----------------+-------------+--------------------+
| title           | ticket_revenue | fnb_revenue | fnb_pct_of_tickets |
+-----------------+----------------+-------------+--------------------+
| Neon Monsoon    |       12900.00 |     5610.00 |               43.5 |
| The Last Ledger |       12760.00 |     5280.00 |               41.4 |
| Paper Lanterns  |       12730.00 |     3440.00 |               27.0 |
| Turbo Kite      |       12620.00 |     3140.00 |               24.9 |
| Silent Karst    |       12510.00 |     4160.00 |               33.3 |
| Chai & Chaos    |       11990.00 |     5050.00 |               42.1 |
+-----------------+----------------+-------------+--------------------+
```


## 13. SHIFT SUMMARY (the Z-report).

One clerk, one day, and the two questions that matter: what did I take, and what did I pay back?

The application passes ? for @staff_id. Refunds are counted against cancelled_by_staff_id, NOT sold_by_staff_id — otherwise a refund taken by the evening clerk is charged to the morning clerk who sold the ticket. That distinction is the whole reason the second column exists.

13a. What this clerk took today, split by method  
13b. What this clerk paid back today

```sql
SET @staff_id = 1;
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
SELECT COUNT(*)                              AS refunds,
       ROUND(COALESCE(SUM(refund_amount), 0), 2) AS refunded
FROM booking
WHERE cancelled_by_staff_id = @staff_id
  AND DATE(cancelled_at) = CURDATE();
```

*3 statement(s): SELECT, SET.*

**Output** — MySQL 8.0.46, captured 2026-10-22.

```text
+---------+----------+
| refunds | refunded |
+---------+----------+
|       0 |     0.00 |
+---------+----------+
```


## 14. TRANSACTION demo for the viva.

Run these by hand on a HELD booking:

The four SELECTs prove: status moved HELD -> CONFIRMED, the three money columns agree, money was recorded, and the trigger wrote the audit row nobody asked it for.

```sql
SELECT booking_id, status, total_amount
FROM booking WHERE status='HELD' LIMIT 1;
CALL sp_confirm_booking(<that id>, 'UPI');
SELECT * FROM booking       WHERE booking_id = <that id>;
SELECT * FROM payment       WHERE booking_id = <that id>;
SELECT * FROM booking_audit WHERE booking_id = <that id>;
```

*Manual drill — run these by hand; nothing to execute in bulk.*
