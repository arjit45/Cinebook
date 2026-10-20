-- ============================================================
--  CineBook  --  File 3 of 5 : VIEWS, TRIGGER, STORED PROCEDURE
--  Run AFTER 02_seed.sql
--
--  These are the "extra credit" objects. If the examiner asks
--  what a view is for: it is a named query, so the same
--  occupancy calculation is not copied into five different
--  reports and quietly drifting apart.
-- ============================================================
USE cinebook;

-- ------------------------------------------------------------
-- VIEW 1: live occupancy of every screening
--
--   revenue here is TICKET revenue only. Food cannot be joined in
--   at this level: one booking has many tickets and many food
--   lines, so a single join would multiply both and inflate the
--   totals. Query 12 does F&B properly with two separate
--   aggregates, which is the honest way round.
-- ------------------------------------------------------------
CREATE OR REPLACE VIEW v_show_occupancy AS
SELECT st.showtime_id,
       st.show_date,
       st.start_time,
       m.title                              AS movie,
       sc.name                              AS screen,
       sc.seat_capacity,
       COUNT(t.seat_id)                     AS seats_sold,
       sc.seat_capacity - COUNT(t.seat_id)  AS seats_available,
       ROUND(COALESCE(SUM(t.price), 0), 2)  AS revenue
FROM showtime st
JOIN movie  m  ON m.movie_id  = st.movie_id
JOIN screen sc ON sc.screen_id = st.screen_id
LEFT JOIN ticket t ON t.showtime_id = st.showtime_id
GROUP BY st.showtime_id, st.show_date, st.start_time,
         m.title, sc.name, sc.seat_capacity;

-- ------------------------------------------------------------
-- VIEW 2: lifetime performance of each film
--   Ticket revenue again, for the same reason.
-- ------------------------------------------------------------
CREATE OR REPLACE VIEW v_movie_performance AS
SELECT m.movie_id,
       m.title,
       m.genre,
       COUNT(DISTINCT st.showtime_id)        AS screenings,
       COUNT(t.seat_id)                      AS tickets_sold,
       ROUND(COALESCE(SUM(t.price), 0), 2)   AS revenue
FROM movie m
LEFT JOIN showtime st ON st.movie_id = m.movie_id
LEFT JOIN ticket   t  ON t.showtime_id = st.showtime_id
GROUP BY m.movie_id, m.title, m.genre;

-- ------------------------------------------------------------
-- TRIGGER: every status change is recorded automatically,
-- so the audit trail cannot be forgotten by application code.
--
-- Note that it fires on UPDATE only. An INSERT of an already-
-- CANCELLED row writes no audit row — which is why the seed had
-- to write its own history by hand.
-- ------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_booking_status_audit;

DELIMITER $$
CREATE TRIGGER trg_booking_status_audit
AFTER UPDATE ON booking
FOR EACH ROW
BEGIN
    IF OLD.status <> NEW.status THEN
        INSERT INTO booking_audit (booking_id, old_status, new_status)
        VALUES (NEW.booking_id, OLD.status, NEW.status);
    END IF;
END$$
DELIMITER ;

-- ------------------------------------------------------------
-- PROCEDURE: confirm a held booking and take payment,
-- as one atomic unit.
--
--   SELECT ... FOR UPDATE  -> locks that booking row, so two
--                             box-office terminals cannot both
--                             confirm (and both charge) it.
--   START TRANSACTION ... COMMIT -> all or nothing.
--
--   The total is tickets + food, written into all three money
--   columns in a single UPDATE, because CHECK (total = ticket +
--   fnb) is evaluated on the finished row. Writing ticket_amount
--   on its own, then total_amount after, fails in between.
--
--   The UPDATE also fires the audit trigger: HELD -> CONFIRMED
--   appears in booking_audit without this procedure mentioning it.
-- ------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_confirm_booking;

DELIMITER $$
CREATE PROCEDURE sp_confirm_booking(
    IN p_booking_id INT,
    IN p_method     VARCHAR(10)
)
BEGIN
    DECLARE v_status VARCHAR(12)  DEFAULT NULL;
    DECLARE v_ticket DECIMAL(8,2) DEFAULT 0.00;
    DECLARE v_fnb    DECIMAL(8,2) DEFAULT 0.00;
    DECLARE v_total  DECIMAL(8,2) DEFAULT 0.00;
    DECLARE v_method VARCHAR(10);

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    SET v_method = UPPER(COALESCE(p_method, 'CARD'));

    IF v_method NOT IN ('CARD', 'UPI', 'CASH') THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Payment method must be CARD, UPI or CASH';
    END IF;

    START TRANSACTION;

    SELECT status INTO v_status
    FROM booking
    WHERE booking_id = p_booking_id
    FOR UPDATE;

    IF v_status IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Booking not found';
    END IF;

    IF v_status <> 'HELD' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Only a HELD booking can be confirmed';
    END IF;

    SELECT COALESCE(SUM(price), 0) INTO v_ticket
    FROM ticket
    WHERE booking_id = p_booking_id;

    IF v_ticket <= 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Booking has no seats attached';
    END IF;

    SELECT COALESCE(SUM(quantity * unit_price), 0) INTO v_fnb
    FROM booking_concession
    WHERE booking_id = p_booking_id;

    SET v_total = v_ticket + v_fnb;

    UPDATE booking
       SET status        = 'CONFIRMED',
           ticket_amount = v_ticket,
           fnb_amount    = v_fnb,
           total_amount  = v_total
     WHERE booking_id = p_booking_id;

    INSERT INTO payment (booking_id, method, amount, paid_at, txn_ref)
    VALUES (p_booking_id, v_method, v_total, NOW(),
            CONCAT('TXN', LPAD(p_booking_id, 8, '0')));

    COMMIT;
END$$
DELIMITER ;
