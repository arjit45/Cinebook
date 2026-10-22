-- ============================================================
--  CineBook  --  File 2 of 5 : SEED DATA
--  Run AFTER 01_schema.sql
--
--  Data is generated with a numbers table + deterministic maths
--  instead of RAND(), so every run produces the SAME rows and
--  therefore the same query output. That matters when you are
--  writing expected output into a report.
--
--  The one place this seed is deliberately strict is money. Three
--  CHECK constraints watch the booking row:
--      total_amount = ticket_amount + fnb_amount
--      refund_amount <= ticket_amount
--      CANCELLED  <=>  cancelled_at IS NOT NULL
--  so every write below sets the columns together, in one
--  statement, exactly as the application has to.
-- ============================================================
USE cinebook;

-- ------------------------------------------------------------
-- Helper: a temporary table holding 0..999
-- ------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS nums;
CREATE TEMPORARY TABLE nums (n INT PRIMARY KEY);

INSERT INTO nums (n)
SELECT a.i * 100 + b.i * 10 + c.i
FROM (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
CROSS JOIN
     (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b
CROSS JOIN
     (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c;

-- ------------------------------------------------------------
-- Movies
-- ------------------------------------------------------------
INSERT INTO movie (title, genre, language, runtime_min, release_date, certificate, imdb_rating) VALUES
('Neon Monsoon',    'Sci-Fi',    'English', 148, '2026-03-14', 'UA', 8.2),
('The Last Ledger', 'Thriller',  'English', 121, '2026-02-02', 'UA', 7.6),
('Paper Lanterns',  'Drama',     'Hindi',   132, '2026-01-20', 'U',  7.9),
('Turbo Kite',      'Animation', 'English',  96, '2025-12-25', 'U',  8.5),
('Silent Karst',    'Horror',    'English', 104, '2026-04-04', 'A',  6.8),
('Chai & Chaos',    'Comedy',    'Hindi',   112, '2026-05-01', 'UA', 7.1),
('Blue Meridian',   'Adventure', 'English', 139, '2025-11-11', 'UA', 7.8),
('Static Bloom',    'Romance',   'Tamil',   118, '2026-06-18', 'U',  7.4);

-- ------------------------------------------------------------
-- Screens
-- ------------------------------------------------------------
INSERT INTO screen (name, seat_capacity) VALUES
('Audi 1', 96),
('Audi 2', 96),
('Audi 3', 96),
('Audi 4', 96);

-- ------------------------------------------------------------
-- Seats: 8 rows (A..H) x 12 seats per screen = 96 each
-- Rows A-D REGULAR, E-F PREMIUM, G-H RECLINER
-- ------------------------------------------------------------
INSERT INTO seat (screen_id, row_label, seat_no, seat_class)
SELECT sc.screen_id,
       CHAR(65 + FLOOR(nums.n / 12)),
       (nums.n % 12) + 1,
       CASE WHEN FLOOR(nums.n / 12) >= 6 THEN 'RECLINER'
            WHEN FLOOR(nums.n / 12) >= 4 THEN 'PREMIUM'
            ELSE 'REGULAR' END
FROM screen sc
CROSS JOIN nums
WHERE nums.n < 96;

-- ------------------------------------------------------------
-- Current programme: 26 days per screen, from 18 days ago to
-- 7 days ahead. Movies 1-6 only.
--
-- Start times rotate through five daily slots instead of the whole
-- multiplex opening on the same minute: each auditorium moves one
-- slot along per day, so a screen is not always the morning room.
-- Every third day Audi 4 takes Audi 3's slot, putting two screenings
-- on one start time -- a real multiplex does start two screens
-- together, and that pair is what keeps the schedule's
-- date/time/screen ordering tiebreak exercised by the seed.
-- ------------------------------------------------------------
INSERT INTO showtime (movie_id, screen_id, show_date, start_time, base_price)
SELECT 1 + ((nums.n * 5 + sc.screen_id) % 6),
       sc.screen_id,
       CURDATE() + INTERVAL (nums.n - 18) DAY,
       CASE ((nums.n + CASE sc.screen_id
                            WHEN 1 THEN 0
                            WHEN 2 THEN 1
                            WHEN 3 THEN 3
                            ELSE IF(nums.n % 3 = 0, 3, 4)
                        END) % 5)
            WHEN 0 THEN '10:30:00'
            WHEN 1 THEN '13:15:00'
            WHEN 2 THEN '16:20:00'
            WHEN 3 THEN '19:00:00'
            ELSE '21:30:00' END,
       CAST(180 + sc.screen_id * 40 + (nums.n % 3) * 30 AS DECIMAL(7,2))
FROM screen sc
CROSS JOIN nums
WHERE nums.n < 26;

-- ------------------------------------------------------------
-- Archive: movies 7 and 8 were last screened 200+ days ago.
-- This is what makes the "not screened in the last 6 months"
-- query return a meaningful result.
-- ------------------------------------------------------------
INSERT INTO showtime (movie_id, screen_id, show_date, start_time, base_price)
SELECT 7 + (nums.n % 2),
       sc.screen_id,
       CURDATE() - INTERVAL (200 + nums.n * 4) DAY,
       '19:00:00',
       220.00
FROM screen sc
CROSS JOIN nums
WHERE nums.n < 8;

-- ------------------------------------------------------------
-- Customers: 20 regulars, then the counter walk-in.
-- The walk-in is row 21 and NOT customer 1: id 1 is Aarav Sharma,
-- a real patron, and quietly crediting every walk-up sale to him
-- would be a lie the reports would repeat forever.
-- ------------------------------------------------------------
INSERT INTO customer (full_name, email, phone, joined_on) VALUES
('Aarav Sharma',     'aarav.sharma@example.com',    '9812345670', '2025-11-04'),
('Diya Menon',       'diya.menon@example.com',      '9812345671', '2025-11-19'),
('Rohan Iyer',       'rohan.iyer@example.com',      '9812345672', '2025-12-02'),
('Ishita Rao',       'ishita.rao@example.com',      '9812345673', '2025-12-21'),
('Kabir Nair',       'kabir.nair@example.com',      '9812345674', '2026-01-08'),
('Ananya Gupta',     'ananya.gupta@example.com',    '9812345675', '2026-01-15'),
('Vihaan Joshi',     'vihaan.joshi@example.com',    '9812345676', '2026-01-29'),
('Meera Pillai',     'meera.pillai@example.com',    '9812345677', '2026-02-11'),
('Arjun Deshmukh',   'arjun.deshmukh@example.com',  '9812345678', '2026-02-26'),
('Saanvi Kulkarni',  'saanvi.kulkarni@example.com', '9812345679', '2026-03-07'),
('Aditya Verma',     'aditya.verma@example.com',    '9812345680', '2026-03-19'),
('Nisha Bhat',       'nisha.bhat@example.com',      '9812345681', '2026-04-02'),
('Reyansh Kapoor',   'reyansh.kapoor@example.com',  '9812345682', '2026-04-16'),
('Tara Seshadri',    'tara.seshadri@example.com',   '9812345683', '2026-04-28'),
('Dev Malhotra',     'dev.malhotra@example.com',    '9812345684', '2026-05-09'),
('Zara Qureshi',     'zara.qureshi@example.com',    '9812345685', '2026-05-23'),
('Nikhil Chandra',   'nikhil.chandra@example.com',  '9812345686', '2026-06-04'),
('Priya Ranjan',     'priya.ranjan@example.com',    '9812345687', '2026-06-18'),
('Yash Thakur',      'yash.thakur@example.com',     '9812345688', '2026-07-01'),
('Ritika Bose',      'ritika.bose@example.com',     '9812345689', '2026-07-15'),
('Counter Guest',    'walkin@cinebook.local',       '9000000000', '2026-07-20');

-- ------------------------------------------------------------
-- Staff: two accounts, salted hashes only. No password appears
-- in this file. SHA-256(salt + password), lowercase hex.
--   admin    / admin123      Meera Krishnan  ADMIN
--   operator / operator123   Leena Mathew    OPERATOR
-- ------------------------------------------------------------
INSERT INTO staff_user (username, password_hash, salt, full_name, role) VALUES
('admin',
 '0212b518b03b50cc62a0dadc9e897f48190e21e0af3ba93665b4b0085885e265',
 'a1b2c3d4e5f60718293a4b5c6d7e8f90',
 'Meera Krishnan', 'ADMIN'),
('operator',
 'a4de93770135453c68b5435dad155e8d2da4932f7dd2f2a49011ecf522bff841',
 '0f1e2d3c4b5a69788796a5b4c3d2e1f0',
 'Leena Mathew', 'OPERATOR');

-- ------------------------------------------------------------
-- Concession price list: 8 items, the size of one upsell grid.
-- ------------------------------------------------------------
INSERT INTO concession_item (name, category, unit_price) VALUES
('Salted Popcorn (S)',   'SNACK',   120.00),
('Salted Popcorn (L)',   'SNACK',   220.00),
('Caramel Popcorn',      'SNACK',   260.00),
('Nachos & Salsa',       'SNACK',   180.00),
('Cola (Regular)',       'DRINK',   150.00),
('Iced Tea',             'DRINK',   140.00),
('Movie Combo',          'COMBO',   330.00),
('Choco Lava Cup',       'DESSERT', 160.00);

-- ------------------------------------------------------------
-- Bookings: 300, spread across every current showtime.
-- Every row records the clerk who sold it (sold_by_staff_id).
-- Every 7th row is HELD. The 40 that stay HELD are booked a few
-- minutes ago, inside the 10-minute hold TTL, so the expiry job
-- leaves them for the demo; the three HELD rows that step 4
-- cancels keep the historical timestamp that matches their
-- cancelled_at. Every 19th row (booking_id = 19k + 1) is
-- cancelled later, because a CANCELLED row needs cancelled_at at
-- the same moment the status changes.
-- ------------------------------------------------------------
INSERT INTO booking (customer_id, showtime_id, booked_at, status, sold_by_staff_id)
SELECT 1 + ((nums.n * 7) % 20),
       st.showtime_id,
       CASE
           WHEN nums.n % 7 = 0 AND nums.n % 19 <> 0
               THEN NOW() - INTERVAL (1 + (nums.n % 5)) MINUTE
           ELSE TIMESTAMP(LEAST(st.show_date, CURDATE()), '11:00:00')
                - INTERVAL ((nums.n % 60) + 2) HOUR
       END,
       CASE WHEN nums.n % 7 = 0 THEN 'HELD' ELSE 'CONFIRMED' END,
       1 + (nums.n % 2)
FROM nums
JOIN (SELECT showtime_id, show_date,
             ROW_NUMBER() OVER (ORDER BY showtime_id) AS rn,
             COUNT(*)     OVER ()                     AS cnt
      FROM showtime
      WHERE show_date >= CURDATE() - INTERVAL 18 DAY) st
  ON st.rn = 1 + (nums.n % st.cnt)
WHERE nums.n < 300;

-- ------------------------------------------------------------
-- 1. Tickets: one seat per booking.
--    bs.rn = this booking's position within its own showtime
--    sn.sn = this seat's position within its own screen
--    Matching the two guarantees each showtime gets distinct seats.
--    Price = showtime base price scaled by seat class.
-- ------------------------------------------------------------
INSERT INTO ticket (booking_id, seat_id, showtime_id, price)
SELECT bs.booking_id,
       sn.seat_id,
       bs.showtime_id,
       ROUND(st.base_price *
             CASE sn.seat_class WHEN 'RECLINER' THEN 1.60
                                WHEN 'PREMIUM'  THEN 1.25
                                ELSE 1.00 END, 2)
FROM (SELECT b.booking_id, b.showtime_id, s.screen_id,
             ROW_NUMBER() OVER (PARTITION BY b.showtime_id ORDER BY b.booking_id) AS rn
      FROM booking b
      JOIN showtime s ON s.showtime_id = b.showtime_id) bs
JOIN (SELECT seat_id, screen_id, seat_class,
             ROW_NUMBER() OVER (PARTITION BY screen_id ORDER BY seat_id) AS sn
      FROM seat) sn
  ON sn.screen_id = bs.screen_id AND sn.sn = bs.rn
JOIN showtime st ON st.showtime_id = bs.showtime_id;

-- ------------------------------------------------------------
-- 2. Food: 100 lines across 75 bookings.
--      every booking_id divisible by 4  -> one snack  (75 lines)
--      every booking_id divisible by 12 -> a dessert   (25 lines)
--    Different item ranges, so the composite primary key
--    (booking_id, item_id) never collides.
-- ------------------------------------------------------------
INSERT INTO booking_concession (booking_id, item_id, quantity, unit_price)
SELECT b.booking_id,
       1 + (b.booking_id % 7),
       1 + (b.booking_id % 3),
       ci.unit_price
FROM booking b
JOIN concession_item ci ON ci.item_id = 1 + (b.booking_id % 7)
WHERE b.booking_id % 4 = 0;

INSERT INTO booking_concession (booking_id, item_id, quantity, unit_price)
SELECT b.booking_id, 8, 1, ci.unit_price
FROM booking b
JOIN concession_item ci ON ci.item_id = 8
WHERE b.booking_id % 12 = 0;

-- ------------------------------------------------------------
-- 3. The money split, in ONE statement.
--    total = ticket + fnb is a CHECK, so a two-statement version
--    (set ticket_amount, then set total) fails on the first one.
--    This runs BEFORE the cancelled tickets are deleted, because
--    ticket_amount is a snapshot of what those tickets cost.
-- ------------------------------------------------------------
UPDATE booking b
LEFT JOIN (SELECT booking_id, SUM(price) AS amt
           FROM ticket GROUP BY booking_id) tk
       ON tk.booking_id = b.booking_id
LEFT JOIN (SELECT booking_id, SUM(quantity * unit_price) AS amt
           FROM booking_concession GROUP BY booking_id) cc
       ON cc.booking_id = b.booking_id
SET b.ticket_amount = COALESCE(tk.amt, 0),
    b.fnb_amount    = COALESCE(cc.amt, 0),
    b.total_amount  = COALESCE(tk.amt, 0) + COALESCE(cc.amt, 0);

-- ------------------------------------------------------------
-- 4. Cancel 16 bookings. Every 19th row.
--    ticket_amount is already snapshotted, so the money survives
--    the ticket deletion that follows. cancelled_at is set in the
--    same statement as the status: the CHECK refuses a CANCELLED
--    row without a timestamp.
-- ------------------------------------------------------------
UPDATE booking
SET status       = 'CANCELLED',
    cancelled_at = booked_at + INTERVAL 10 MINUTE
WHERE (booking_id - 1) % 19 = 0;

-- Cancelling releases the seats: the ticket rows are deleted, so
-- UNIQUE (showtime_id, seat_id) frees up. The money does not move.
DELETE t
FROM ticket t
JOIN booking b ON b.booking_id = t.booking_id
WHERE b.status = 'CANCELLED';

-- ------------------------------------------------------------
-- 5. Refunds, against the right clerk.
--    Of the 16 cancelled bookings, half were paid first and half
--    were holds that expired. k = (booking_id - 1) / 19 is the
--    row's position in the cancelled set:
--      k % 4 = 0  -> refunded at 50%  (inside 2 h)
--      k % 2 = 0  -> refunded at 90%  (more than 2 h out)
--      k odd      -> expired hold, nothing was charged
--    Food is never refunded, which is why the base is ticket_amount.
--    cancelled_by_staff_id is NULL for an expiry: a job did it.
-- ------------------------------------------------------------
UPDATE booking
SET refund_amount = CASE
        WHEN ((booking_id - 1) DIV 19) % 4 = 0
             THEN ROUND(ticket_amount * 0.50, 2)
        WHEN ((booking_id - 1) DIV 19) % 2 = 0
             THEN ROUND(ticket_amount * 0.90, 2)
        ELSE 0.00 END,
    cancelled_by_staff_id = CASE
        WHEN ((booking_id - 1) DIV 19) % 2 = 0
             THEN 1 + (booking_id % 2)
        ELSE NULL END,
    cancelled_at = booked_at + INTERVAL
        (CASE WHEN ((booking_id - 1) DIV 19) % 2 = 0 THEN 90 ELSE 10 END) MINUTE
WHERE (booking_id - 1) % 19 = 0;

-- ------------------------------------------------------------
-- 6. Audit history for those cancellations.
--    Written by hand here only because the trigger is created
--    later (03_objects.sql) and cannot record the past. From then
--    on, the trigger is the only thing that writes this table.
-- ------------------------------------------------------------
INSERT INTO booking_audit (booking_id, old_status, new_status, changed_at)
SELECT booking_id, 'HELD', 'CONFIRMED', booked_at + INTERVAL 6 MINUTE
FROM booking
WHERE status = 'CANCELLED' AND ((booking_id - 1) DIV 19) % 2 = 0
UNION ALL
SELECT booking_id, 'CONFIRMED', 'CANCELLED', cancelled_at
FROM booking
WHERE status = 'CANCELLED' AND ((booking_id - 1) DIV 19) % 2 = 0
UNION ALL
SELECT booking_id, 'HELD', 'CANCELLED', cancelled_at
FROM booking
WHERE status = 'CANCELLED' AND ((booking_id - 1) DIV 19) % 2 = 1;

-- ------------------------------------------------------------
-- 7. Payments: every confirmed booking, plus the cancelled ones
--    that were paid before they were refunded. A cancelled
--    booking with no payment and no refund is an expired hold.
-- ------------------------------------------------------------
INSERT INTO payment (booking_id, method, amount, paid_at, txn_ref)
SELECT b.booking_id,
       ELT(1 + (b.booking_id % 3), 'CARD', 'UPI', 'CASH'),
       b.total_amount,
       b.booked_at + INTERVAL 6 MINUTE,
       CONCAT('TXN', LPAD(b.booking_id, 8, '0'))
FROM booking b
WHERE b.total_amount > 0
  AND (b.status = 'CONFIRMED' OR b.refund_amount > 0);

-- ------------------------------------------------------------
-- 8. Sanity check -- expect:
--      8 movies, 4 screens, 384 seats, 136 showtimes, 21 customers,
--      2 staff, 8 concession items, 300 bookings, 284 tickets,
--      100 food lines, 24 audit rows, 252 payments
--    300 - 16 cancelled = 284 tickets. 244 confirmed + 8 paid-then-
--    refunded = 252 payments.
-- ------------------------------------------------------------
SELECT 'movie'              AS tbl, COUNT(*) AS rows_loaded FROM movie
UNION ALL SELECT 'screen',              COUNT(*) FROM screen
UNION ALL SELECT 'seat',                COUNT(*) FROM seat
UNION ALL SELECT 'showtime',            COUNT(*) FROM showtime
UNION ALL SELECT 'customer',            COUNT(*) FROM customer
UNION ALL SELECT 'staff_user',          COUNT(*) FROM staff_user
UNION ALL SELECT 'concession_item',     COUNT(*) FROM concession_item
UNION ALL SELECT 'booking',             COUNT(*) FROM booking
UNION ALL SELECT 'ticket',              COUNT(*) FROM ticket
UNION ALL SELECT 'booking_concession',  COUNT(*) FROM booking_concession
UNION ALL SELECT 'booking_audit',       COUNT(*) FROM booking_audit
UNION ALL SELECT 'payment',             COUNT(*) FROM payment;
