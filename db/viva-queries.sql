-- ============================================================
--  CineBook  --  DEMO PROOF QUERIES
--  For running in MySQL Workbench during your own presentation.
--
--  Written against db/01_schema.sql: 12 tables, including
--  staff_user, concession_item and both bridge tables, plus
--  booking.sold_by_staff_id / cancelled_by_staff_id / cancelled_at /
--  refund_amount / ticket_amount / fnb_amount / total_amount.
--  Regenerate alongside db/01_schema.sql.
--
--  HOW TO USE THIS FILE
--  --------------------
--  Every block is numbered and self-contained. Highlight the block,
--  press the lightning bolt. Nothing here is destructive — blocks
--  that create a row delete it again at the end.
--
--  The point of a proof query is not that it is clever. It is that
--  you run the SAME query before and after an action, and the
--  numbers move. Block 01 and block 07 are the same query on
--  purpose.
-- ============================================================
USE cinebook;

-- ============================================================
-- BLOCK 00  --  Baseline. Read this out loud, then forget it.
--              It tells the examiner what state you started from.
-- ============================================================
SELECT 'movie'              AS tbl, COUNT(*) AS rows FROM movie
UNION ALL SELECT 'screen',           COUNT(*) FROM screen
UNION ALL SELECT 'seat',             COUNT(*) FROM seat
UNION ALL SELECT 'showtime',         COUNT(*) FROM showtime
UNION ALL SELECT 'customer',         COUNT(*) FROM customer
UNION ALL SELECT 'staff_user',       COUNT(*) FROM staff_user
UNION ALL SELECT 'concession_item',  COUNT(*) FROM concession_item
UNION ALL SELECT 'booking',          COUNT(*) FROM booking
UNION ALL SELECT 'ticket',           COUNT(*) FROM ticket
UNION ALL SELECT 'booking_concession', COUNT(*) FROM booking_concession
UNION ALL SELECT 'booking_audit',    COUNT(*) FROM booking_audit
UNION ALL SELECT 'payment',          COUNT(*) FROM payment;


-- ============================================================
-- BLOCK 01  --  BEFORE: today's screenings and how full they are
--               (identical to block 07 — that is the point)
-- ============================================================
SELECT showtime_id,
       show_date,
       start_time,
       movie,
       screen,
       seats_sold,
       seats_available,
       revenue
FROM v_show_occupancy
WHERE show_date = CURDATE()
ORDER BY screen, start_time
LIMIT 8;


-- ============================================================
-- BLOCK 02  --  Pick a screening and a free seat to demonstrate with
--               Run this, note the numbers, they go in your script.
-- ============================================================
SET @show := (SELECT showtime_id FROM showtime
              WHERE show_date >= CURDATE()
              ORDER BY showtime_id LIMIT 1);

SET @seat := (SELECT s.seat_id
              FROM seat s
              JOIN showtime st ON st.screen_id = s.screen_id
              LEFT JOIN ticket t ON t.showtime_id = st.showtime_id
                                AND t.seat_id = s.seat_id
              WHERE st.showtime_id = @show AND t.seat_id IS NULL
              ORDER BY s.seat_id LIMIT 1);

SELECT @show AS chosen_showtime, @seat AS chosen_seat;


-- ============================================================
-- BLOCK 03  --  THE HEADLINE PROOF: two customers, one seat
--               Customer 1 books it. Customer 2 tries to. MySQL says no.
-- ============================================================
-- Pick a screening and a free seat (same as block 02, so this
-- block is safe to run on its own if you go out of order)
SET @show := (SELECT showtime_id FROM showtime
              WHERE show_date >= CURDATE()
              ORDER BY showtime_id LIMIT 1);

SET @seat := (SELECT s.seat_id
              FROM seat s
              JOIN showtime st ON st.screen_id = s.screen_id
              LEFT JOIN ticket t ON t.showtime_id = st.showtime_id
                                AND t.seat_id = s.seat_id
              WHERE st.showtime_id = @show AND t.seat_id IS NULL
              ORDER BY s.seat_id LIMIT 1);

-- Step 1 — customer 1 takes the seat
INSERT INTO booking (customer_id, showtime_id, booked_at, status,
                     total_amount, sold_by_staff_id)
VALUES (1, @show, NOW(), 'HELD', 0.00, 1);

SET @booking1 := LAST_INSERT_ID();

INSERT INTO ticket (booking_id, seat_id, showtime_id, price)
VALUES (@booking1, @seat, @show,
        (SELECT base_price FROM showtime WHERE showtime_id = @show));

-- The money columns are written in ONE statement, because
-- CHECK (total_amount = ticket_amount + fnb_amount) is evaluated
-- on the finished row. Setting ticket_amount first and the total a
-- statement later fails in between — the app does it this way too.
UPDATE booking
SET ticket_amount = (SELECT SUM(price) FROM ticket WHERE booking_id = @booking1),
    fnb_amount    = 0.00,
    total_amount  = (SELECT SUM(price) FROM ticket WHERE booking_id = @booking1)
WHERE booking_id = @booking1;

-- Step 2 — customer 2 tries the SAME seat. Run this on its own.
INSERT INTO booking (customer_id, showtime_id, booked_at, status,
                     total_amount, sold_by_staff_id)
VALUES (2, @show, NOW(), 'HELD', 0.00, 1);

SET @booking2 := LAST_INSERT_ID();

INSERT INTO ticket (booking_id, seat_id, showtime_id, price)
VALUES (@booking2, @seat, @show,
        (SELECT base_price FROM showtime WHERE showtime_id = @show));

-- >>> Expected: ERROR 1062 (23000): Duplicate entry
--               ... for key 'ticket.uq_seat_per_showtime'
--
-- Say this: "the application could have written buggy code and this
-- still could not happen — the storage engine refused it."

-- Step 3 — tidy up both rows so the demo is repeatable
DELETE FROM booking WHERE booking_id IN (@booking1, @booking2);


-- ============================================================
-- BLOCK 04  --  Referential integrity. Deliberately wrong on purpose.
-- ============================================================
-- Booking for a customer who does not exist
INSERT INTO booking (customer_id, showtime_id, booked_at, status,
                     total_amount, sold_by_staff_id)
VALUES (99999, 1, NOW(), 'HELD', 0.00, 1);

-- >>> Expected: ERROR 1452 (23000): Cannot add or update a child row:
--               a foreign key constraint fails (fk_booking_customer)

-- Booking sold by a staff member who does not exist
INSERT INTO booking (customer_id, showtime_id, booked_at, status,
                     total_amount, sold_by_staff_id)
VALUES (1, 1, NOW(), 'HELD', 0.00, 99999);

-- >>> Expected: ERROR 1452 ... (fk_booking_staff)


-- ============================================================
-- BLOCK 05  --  CHECK constraints
-- ============================================================
-- A negative total.
-- ticket_amount equals total_amount on purpose: that keeps
-- chk_booking_sum satisfied, so the error you see is the one you
-- asked for rather than whichever constraint MySQL checks first.
INSERT INTO booking (customer_id, showtime_id, booked_at, status,
                     ticket_amount, fnb_amount, total_amount, sold_by_staff_id)
VALUES (1, 1, NOW(), 'HELD', -5.00, 0.00, -5.00, 1);

-- >>> Expected: ERROR 3819 (23019): Check constraint
--               'booking.chk_booking_total' is violated.

-- And the split itself, which is the constraint the app has to
-- respect on every write:
INSERT INTO booking (customer_id, showtime_id, booked_at, status,
                     ticket_amount, fnb_amount, total_amount, sold_by_staff_id)
VALUES (1, 1, NOW(), 'HELD', 400.00, 0.00, 500.00, 1);

-- >>> Expected: ERROR 3819 ... 'booking.chk_booking_sum' is violated.
-- Say this: "the three money columns cannot disagree, so no report
-- can ever be reading a half-written booking."

-- A negative price
INSERT INTO showtime (movie_id, screen_id, show_date, start_time, base_price)
VALUES (1, 1, '2099-01-01', '01:00:00', -10.00);

-- >>> Expected: ERROR 3819 ... 'showtime.chk_showtime_price' is violated.


-- ============================================================
-- BLOCK 06  --  Two films cannot start in the same auditorium at once
-- ============================================================
INSERT INTO showtime (movie_id, screen_id, show_date, start_time, base_price)
SELECT movie_id, screen_id, show_date, start_time, 250.00
FROM showtime
WHERE show_date = CURDATE()
LIMIT 1;

-- >>> Expected: ERROR 1062 ... 'showtime.uq_screen_slot'
--
-- Say this: "the schedule builder in the app checks for this first
-- so the user gets a sentence, but this key is what actually stops it."


-- ============================================================
-- BLOCK 07  --  ON DELETE RESTRICT: a film in use cannot be removed
-- ============================================================
DELETE FROM movie WHERE movie_id = 1;

-- >>> Expected: ERROR 1451 (23000): Cannot delete or update a parent row:
--               a foreign key constraint fails (fk_showtime_movie)
--
-- This is the answer to "what if someone deletes a film that is
-- still screening?". They can't. Ask the database, not the developer.


-- ============================================================
-- BLOCK 08  --  AFTER: the same query as block 01
--               Run it again after selling a seat in the app.
--               seats_sold goes up, revenue goes up. That is the proof.
-- ============================================================
SELECT showtime_id,
       show_date,
       start_time,
       movie,
       screen,
       seats_sold,
       seats_available,
       revenue
FROM v_show_occupancy
WHERE show_date = CURDATE()
ORDER BY screen, start_time
LIMIT 8;


-- ============================================================
-- BLOCK 09  --  The money actually moved
-- ============================================================
SELECT b.booking_id,
       c.full_name       AS customer,
       m.title           AS movie,
       st.show_date,
       st.start_time,
       b.status,
       b.total_amount,
       p.method          AS paid_by,
       p.amount          AS paid,
       s.full_name       AS sold_by
FROM booking b
JOIN customer  c ON c.customer_id = b.customer_id
JOIN showtime st ON st.showtime_id = b.showtime_id
JOIN movie     m ON m.movie_id    = st.movie_id
JOIN payment   p ON p.booking_id  = b.booking_id
LEFT JOIN staff_user s ON s.staff_id = b.sold_by_staff_id
ORDER BY b.booking_id DESC
LIMIT 10;


-- ============================================================
-- BLOCK 10  --  The audit trail: the trigger doing its job
--               Status changes are recorded whether or not the
--               application remembers to write them.
-- ============================================================
SELECT a.audit_id,
       a.booking_id,
       a.old_status,
       a.new_status,
       a.changed_at
FROM booking_audit a
ORDER BY a.changed_at DESC
LIMIT 10;


-- ============================================================
-- BLOCK 11  --  Cancellation: the refund, and the seat coming back
-- ============================================================
SELECT b.booking_id,
       b.status,
       b.total_amount,
       b.refund_amount,
       st.show_date,
       st.start_time,
       TIMESTAMPDIFF(MINUTE, NOW(),
              CONCAT(st.show_date, ' ', st.start_time)) AS minutes_until_show,
       -- The percentages apply to ticket_amount, never to the grand
       -- total: food is not refunded.
       CASE
           WHEN b.refund_amount = 0 AND b.cancelled_at IS NOT NULL
                THEN 'expired hold, or show already started'
           WHEN b.refund_amount = ROUND(b.ticket_amount * 0.90, 2) THEN '90% of tickets — over 2 h out'
           WHEN b.refund_amount = ROUND(b.ticket_amount * 0.50, 2) THEN '50% of tickets — inside 2 h'
           ELSE 'other'
       END AS refund_reason
FROM booking b
JOIN showtime st ON st.showtime_id = b.showtime_id
WHERE b.status = 'CANCELLED'
ORDER BY b.cancelled_at DESC
LIMIT 10;


-- ============================================================
-- BLOCK 12  --  Net revenue: money in, money back out
-- ============================================================
SELECT COUNT(*)                              AS bookings_charged,
       ROUND(SUM(p.amount), 2)               AS taken,
       ROUND(SUM(COALESCE(b.refund_amount, 0)), 2) AS refunded,
       ROUND(SUM(p.amount) - SUM(COALESCE(b.refund_amount, 0)), 2) AS net
FROM booking b
JOIN payment p ON p.booking_id = b.booking_id;


-- ============================================================
-- BLOCK 13  --  Indexes are real, not decorative
-- ============================================================
EXPLAIN SELECT showtime_id, screen_id, show_date
FROM showtime
WHERE show_date = CURDATE();

-- >>> Look for: key = idx_showtime_date, type = ref, rows = small.
-- Say this: "it uses the index instead of scanning every showtime."


-- ============================================================
-- BLOCK 14  --  The app connects with no DDL rights
--               Needs the app user to exist (db/05_users.sql).
--               If it errors, skip it — it is a bonus, not a beat.
-- ============================================================
SHOW GRANTS FOR 'cinebook_app'@'localhost';

-- >>> Expected: SELECT, INSERT, UPDATE, DELETE on cinebook.*,
--               EXECUTE on sp_confirm_booking, and nothing else.
--
-- This is the answer to "what happens if the application tries to
-- DROP a table?" It cannot. Least privilege, not good intentions.


-- ============================================================
-- BLOCK 15  --  Window function, because the syllabus likes them
-- ============================================================
SELECT title,
       genre,
       tickets_sold,
       revenue,
       RANK() OVER (ORDER BY revenue DESC) AS revenue_rank
FROM v_movie_performance
WHERE tickets_sold > 0
ORDER BY revenue_rank;


-- ============================================================
-- BLOCK 16  --  "Do it another way" — the same answer as a subquery
--               Have this ready. It is the most common follow-up.
-- ============================================================
-- Form A — LEFT JOIN + IS NULL
-- Note the date condition sits in the ON clause, not the WHERE.
-- Put it in the WHERE and you turn the outer join into an inner one
-- and silently lose every film that has no shows at all.
SELECT m.title
FROM movie m
LEFT JOIN showtime s
       ON s.movie_id = m.movie_id
      AND s.show_date >= CURDATE() - INTERVAL 6 MONTH
WHERE s.movie_id IS NULL;

-- Form B — NOT EXISTS
SELECT m.title
FROM movie m
WHERE NOT EXISTS (
        SELECT 1 FROM showtime s
        WHERE s.movie_id = m.movie_id
          AND s.show_date >= CURDATE() - INTERVAL 6 MONTH);

-- >>> Both return the same rows. Explain WHY they differ in the plan,
-- not in the result: NOT EXISTS usually gives the optimiser more to
-- work with, and handles NULLs more predictably.

-- ============================================================
-- BLOCK 17  --  The money split, and why ticket_amount survives
--               a cancellation
-- ============================================================
SELECT b.booking_id,
       b.status,
       b.ticket_amount,
       b.fnb_amount,
       b.total_amount
FROM booking b
WHERE b.fnb_amount > 0
ORDER BY b.booking_id
LIMIT 10;

-- Now the same booking after cancellation. The tickets are gone —
-- and ticket_amount is not. That is not a bug: the refund was
-- calculated from it, and it is the only surviving record of what
-- the seats cost.
SELECT b.booking_id,
       b.status,
       b.ticket_amount,
       (SELECT COUNT(*) FROM ticket t WHERE t.booking_id = b.booking_id) AS tickets_on_file,
       b.refund_amount
FROM booking b
WHERE b.status = 'CANCELLED'
ORDER BY b.booking_id
LIMIT 8;

-- >>> Count tickets_on_file = 0 while ticket_amount > 0, then say:
--     "the seats came back, the money history did not."


-- ============================================================
-- BLOCK 18  --  Food is not refundable, and the database knows it
-- ============================================================
-- Deliberately wrong: refund the whole grand total instead of the
-- ticket subtotal. A booking that had popcorn has ticket_amount
-- below total_amount, so this is the row that breaks.
UPDATE booking
SET refund_amount = total_amount
WHERE status = 'CANCELLED' AND ticket_amount < total_amount
LIMIT 1;

-- >>> Expected: ERROR 3819 (23019): Check constraint
--               'booking.chk_refund_amount' is violated.
--
-- This is the answer to "what if the clerk refunds the popcorn
-- too?". The rule lives in RefundPolicy, but it is enforced here,
-- in the schema, so a bug (or a tired human at 11 pm) cannot post
-- a refund bigger than the tickets were.


-- ============================================================
-- BLOCK 19  --  The trigger, live. The only block in this file
--               that changes data on purpose.
-- ============================================================
-- Confirm a held booking through the procedure, then look for an
-- audit row that no application code wrote.
SET @held := (SELECT booking_id FROM booking
              WHERE status = 'HELD' ORDER BY booking_id LIMIT 1);

SELECT @held AS confirming_booking, status, ticket_amount, fnb_amount, total_amount
FROM booking WHERE booking_id = @held;

CALL sp_confirm_booking(@held, 'UPI');

SELECT booking_id, old_status, new_status, changed_at
FROM booking_audit
WHERE booking_id = @held;

SELECT booking_id, status, ticket_amount, fnb_amount, total_amount
FROM booking WHERE booking_id = @held;

-- >>> Expect: a HELD -> CONFIRMED audit row, and a total that is
--     the tickets PLUS the food (if that booking had any).
-- Re-running BLOCK 19 on the same booking fails, correctly:
-- "Only a HELD booking can be confirmed". Pick the next HELD row,
-- or rebuild with db/00_setup.sql.


-- ============================================================
-- BLOCK 20  --  The price snapshot, which is the 3NF exception
--               you should volunteer before being asked
-- ============================================================
-- Raise a price, then look at what past customers actually paid.
UPDATE concession_item SET unit_price = unit_price + 50 WHERE item_id = 2;

SELECT ci.name,
       ci.unit_price AS price_list_today,
       bc.unit_price AS price_paid_then
FROM booking_concession bc
JOIN concession_item ci ON ci.item_id = bc.item_id
WHERE bc.item_id = 2
ORDER BY bc.booking_id
LIMIT 5;

-- Old rows do not move. That is the whole point of storing the
-- price on the line, and it is the same argument as ticket.price.
-- Put the list back so the demo is repeatable:
UPDATE concession_item SET unit_price = unit_price - 50 WHERE item_id = 2;
