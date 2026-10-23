-- ============================================================
--  CineBook  --  ONE-SHOT SETUP
--  Open in MySQL Workbench and execute the whole file,
--  or run:  mysql -u root -p < db/00_setup.sql
--
--  GENERATED FILE. This is 01 + 02 + 03 + 04, in that order,
--  with nothing edited. If you change any part, rebuild this
--  file -- otherwise the submission carries two versions of the
--  schema and the wrong one always wins.
--
--  Not included, on purpose:
--    05_users.sql  -- creates the application's MySQL account.
--                     Account creation is DCL, needs root, and
--                     must survive a database rebuild.
-- ============================================================

-- ============================================================
--  CineBook  --  Cinema Ticketing Management System
--  File 1 of 5 : SCHEMA (tables, keys, constraints, indexes)
--  Engine      : MySQL 8.0  /  InnoDB
-- ============================================================
--
--  Core Architectural Decisions
--  ----------------------------
--  * Twelve tables. Two of them are associative entities with
--    their own attributes:
--        ticket             (booking_id, seat_id)  + price
--        booking_concession (booking_id, item_id)  + quantity, unit_price
--    a composite (not surrogate) key precisely because the
--    pairing IS the identity.
--
--  * ticket.price and booking_concession.unit_price are deliberate
--    SNAPSHOTS. showtime.base_price or concession_item.unit_price
--    can change next week; a past booking must still remember what
--    the customer actually paid. Historical accuracy beats
--    normalisation here, and that is worth saying out loud.
--
--  * booking.ticket_amount / fnb_amount / total_amount are snapshots
--    too, and for a sharper reason: cancelling deletes the ticket
--    rows, so the ticket subtotal has nowhere else to live. The
--    refund was calculated from it.
--
--  * UNIQUE (showtime_id, seat_id) on ticket is the single most
--    important constraint in the database. It makes double-booking
--    a seat physically impossible at the storage layer, even if
--    the application code is buggy.
--
--  * CHECK constraints are enforced from MySQL 8.0.16 onward.
--    This file targets 8.0.
-- ============================================================

DROP DATABASE IF EXISTS cinebook;
CREATE DATABASE cinebook
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
USE cinebook;

-- ------------------------------------------------------------
-- 1. MOVIE  --  catalogue, independent of any screening
-- ------------------------------------------------------------
CREATE TABLE movie (
    movie_id     INT AUTO_INCREMENT PRIMARY KEY,
    title        VARCHAR(150) NOT NULL,
    genre        VARCHAR(40)  NOT NULL,
    language     VARCHAR(20)  NOT NULL DEFAULT 'English',
    runtime_min  SMALLINT     NOT NULL,
    release_date DATE         NOT NULL,
    certificate  ENUM('U','UA','A') NOT NULL DEFAULT 'UA',
    imdb_rating  DECIMAL(3,1) NULL,
    CONSTRAINT chk_movie_runtime  CHECK (runtime_min BETWEEN 60 AND 240),
    CONSTRAINT chk_movie_rating   CHECK (imdb_rating IS NULL OR imdb_rating BETWEEN 0 AND 10)
) ENGINE = InnoDB;

-- ------------------------------------------------------------
-- 2. SCREEN  --  an auditorium
-- ------------------------------------------------------------
CREATE TABLE screen (
    screen_id     INT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(40) NOT NULL,
    seat_capacity SMALLINT    NOT NULL,
    CONSTRAINT uq_screen_name      UNIQUE (name),
    CONSTRAINT chk_screen_capacity CHECK (seat_capacity > 0)
) ENGINE = InnoDB;

-- ------------------------------------------------------------
-- 3. SEAT  --  physical seat, belongs to exactly one screen
--    uq_seat_position stops a screen having two "A7" seats.
--    chk_seat_no allows 60 per row, which is why the JavaFX seat
--    grid is built from MAX(row_label) / MAX(seat_no) and never
--    from a hardcoded 8 x 12.
-- ------------------------------------------------------------
CREATE TABLE seat (
    seat_id    INT AUTO_INCREMENT PRIMARY KEY,
    screen_id  INT     NOT NULL,
    row_label  CHAR(1) NOT NULL,
    seat_no    TINYINT NOT NULL,
    seat_class ENUM('REGULAR','PREMIUM','RECLINER') NOT NULL DEFAULT 'REGULAR',
    CONSTRAINT fk_seat_screen FOREIGN KEY (screen_id)
        REFERENCES screen (screen_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT uq_seat_position UNIQUE (screen_id, row_label, seat_no),
    CONSTRAINT chk_seat_no      CHECK (seat_no BETWEEN 1 AND 60)
) ENGINE = InnoDB;

-- ------------------------------------------------------------
-- 4. SHOWTIME  --  a screening of a movie on a screen
--    uq_screen_slot prevents two films starting in the same
--    auditorium at the same minute.
-- ------------------------------------------------------------
CREATE TABLE showtime (
    showtime_id INT AUTO_INCREMENT PRIMARY KEY,
    movie_id    INT          NOT NULL,
    screen_id   INT          NOT NULL,
    show_date   DATE         NOT NULL,
    start_time  TIME         NOT NULL,
    base_price  DECIMAL(7,2) NOT NULL,
    CONSTRAINT fk_showtime_movie  FOREIGN KEY (movie_id)
        REFERENCES movie (movie_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_showtime_screen FOREIGN KEY (screen_id)
        REFERENCES screen (screen_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT uq_screen_slot     UNIQUE (screen_id, show_date, start_time),
    CONSTRAINT chk_showtime_price CHECK (base_price > 0)
) ENGINE = InnoDB;

-- ------------------------------------------------------------
-- 5. CUSTOMER  --  registered patron
--    Row 21 is the counter walk-in ("Counter Guest"). It exists so
--    a sale always has a real customer_id without borrowing
--    customer 1, who is a real person in the seed.
-- ------------------------------------------------------------
CREATE TABLE customer (
    customer_id INT AUTO_INCREMENT PRIMARY KEY,
    full_name   VARCHAR(80)  NOT NULL,
    email       VARCHAR(120) NOT NULL,
    phone       VARCHAR(15)  NOT NULL,
    joined_on   DATE         NOT NULL,
    CONSTRAINT uq_customer_email UNIQUE (email),
    CONSTRAINT chk_customer_phone CHECK (CHAR_LENGTH(phone) BETWEEN 10 AND 13)
) ENGINE = InnoDB;

-- ------------------------------------------------------------
-- 6. STAFF_USER  --  the login, and the audit trail's anchor
--    password_hash = SHA-256(salt + password), lowercase hex.
--    The salt is per user, so two clerks with the same password
--    still get different hashes.
-- ------------------------------------------------------------
CREATE TABLE staff_user (
    staff_id      INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(40)  NOT NULL,
    password_hash CHAR(64)     NOT NULL,
    salt          CHAR(32)     NOT NULL,
    full_name     VARCHAR(80)  NOT NULL,
    role          ENUM('ADMIN','OPERATOR') NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_staff_username UNIQUE (username),
    CONSTRAINT chk_staff_hash     CHECK (CHAR_LENGTH(password_hash) = 64)
) ENGINE = InnoDB;

-- ------------------------------------------------------------
-- 7. CONCESSION_ITEM  --  the price list at the counter
-- ------------------------------------------------------------
CREATE TABLE concession_item (
    item_id    INT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(60)  NOT NULL,
    category   ENUM('SNACK','DRINK','COMBO','DESSERT') NOT NULL,
    unit_price DECIMAL(7,2) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_item_name   UNIQUE (name),
    CONSTRAINT chk_item_price CHECK (unit_price > 0)
) ENGINE = InnoDB;

-- ------------------------------------------------------------
-- 8. BOOKING  --  one purchase event: seats, food, money, people
--    status: HELD -> CONFIRMED -> (CANCELLED)
--
--    The three money columns are snapshots and must be written
--    together, in ONE statement, because chk_booking_sum is
--    evaluated on the finished row:
--        total_amount = ticket_amount + fnb_amount
--    A two-statement update fails halfway through. Hold, confirm
--    and the seed all obey that.
-- ------------------------------------------------------------
CREATE TABLE booking (
    booking_id            INT AUTO_INCREMENT PRIMARY KEY,
    customer_id           INT           NOT NULL,
    showtime_id           INT           NOT NULL,
    sold_by_staff_id      INT           NOT NULL,
    cancelled_by_staff_id INT           NULL,
    booked_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status                ENUM('HELD','CONFIRMED','CANCELLED') NOT NULL DEFAULT 'HELD',
    ticket_amount         DECIMAL(8,2)  NOT NULL DEFAULT 0.00,
    fnb_amount            DECIMAL(8,2)  NOT NULL DEFAULT 0.00,
    total_amount          DECIMAL(8,2)  NOT NULL DEFAULT 0.00,
    cancelled_at          DATETIME      NULL,
    refund_amount         DECIMAL(8,2)  NOT NULL DEFAULT 0.00,
    CONSTRAINT fk_booking_customer FOREIGN KEY (customer_id)
        REFERENCES customer (customer_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_booking_showtime FOREIGN KEY (showtime_id)
        REFERENCES showtime (showtime_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_booking_sold_by FOREIGN KEY (sold_by_staff_id)
        REFERENCES staff_user (staff_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_booking_cancelled_by FOREIGN KEY (cancelled_by_staff_id)
        REFERENCES staff_user (staff_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT chk_booking_total   CHECK (total_amount >= 0),
    CONSTRAINT chk_booking_sum     CHECK (total_amount = ticket_amount + fnb_amount),
    CONSTRAINT chk_refund_amount   CHECK (
        refund_amount >= 0
        AND refund_amount <= total_amount
        AND refund_amount <= ticket_amount),   -- food is never refunded
    CONSTRAINT chk_booking_cancelled_at CHECK (
        (status =  'CANCELLED' AND cancelled_at IS NOT NULL)
     OR (status <> 'CANCELLED' AND cancelled_at IS NULL))
) ENGINE = InnoDB;

-- ------------------------------------------------------------
-- 9. TICKET  --  bridge table: which seat on which booking
--    showtime_id is carried here on purpose so that the UNIQUE
--    key can enforce "one ticket per seat per screening".
--    price is the snapshot of Seat.priceFor(showtime).
-- ------------------------------------------------------------
CREATE TABLE ticket (
    booking_id  INT          NOT NULL,
    seat_id     INT          NOT NULL,
    showtime_id INT          NOT NULL,
    price       DECIMAL(7,2) NOT NULL,
    PRIMARY KEY (booking_id, seat_id),
    CONSTRAINT uq_seat_per_showtime UNIQUE (showtime_id, seat_id),
    CONSTRAINT fk_ticket_booking  FOREIGN KEY (booking_id)
        REFERENCES booking (booking_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_ticket_seat     FOREIGN KEY (seat_id)
        REFERENCES seat (seat_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_ticket_showtime FOREIGN KEY (showtime_id)
        REFERENCES showtime (showtime_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT chk_ticket_price CHECK (price > 0)
) ENGINE = InnoDB;

-- ------------------------------------------------------------
-- 10. BOOKING_CONCESSION  --  the second M:N
--     One line per item per booking, so a second popcorn is
--     quantity = 2, not a second row. unit_price is the snapshot.
--     CASCADE from booking is safe because a booking is never
--     deleted; if that ever changed, the food would follow it.
-- ------------------------------------------------------------
CREATE TABLE booking_concession (
    booking_id INT          NOT NULL,
    item_id    INT          NOT NULL,
    quantity   SMALLINT     NOT NULL,
    unit_price DECIMAL(7,2) NOT NULL,
    PRIMARY KEY (booking_id, item_id),
    CONSTRAINT fk_bc_booking FOREIGN KEY (booking_id)
        REFERENCES booking (booking_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_bc_item    FOREIGN KEY (item_id)
        REFERENCES concession_item (item_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT chk_bc_qty    CHECK (quantity BETWEEN 1 AND 20),
    CONSTRAINT chk_bc_price  CHECK (unit_price > 0)
) ENGINE = InnoDB;

-- ------------------------------------------------------------
-- 11. PAYMENT  --  money actually received; one per booking
--     method, amount, txn_ref and nothing else: there is no
--     column a card number could occupy.
-- ------------------------------------------------------------
CREATE TABLE payment (
    payment_id INT AUTO_INCREMENT PRIMARY KEY,
    booking_id INT          NOT NULL,
    method     ENUM('CARD','UPI','CASH') NOT NULL,
    amount     DECIMAL(8,2) NOT NULL,
    paid_at    DATETIME     NOT NULL,
    txn_ref    VARCHAR(40)  NOT NULL,
    CONSTRAINT uq_payment_booking UNIQUE (booking_id),
    CONSTRAINT uq_payment_txnref  UNIQUE (txn_ref),
    CONSTRAINT fk_payment_booking FOREIGN KEY (booking_id)
        REFERENCES booking (booking_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT chk_payment_amount CHECK (amount > 0)
) ENGINE = InnoDB;

-- ------------------------------------------------------------
-- 12. BOOKING_AUDIT  --  written by a trigger, never by the app
-- ------------------------------------------------------------
CREATE TABLE booking_audit (
    audit_id   INT AUTO_INCREMENT PRIMARY KEY,
    booking_id INT         NOT NULL,
    old_status VARCHAR(12) NULL,
    new_status VARCHAR(12) NOT NULL,
    changed_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_booking FOREIGN KEY (booking_id)
        REFERENCES booking (booking_id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB;

-- ------------------------------------------------------------
--  Secondary indexes
--
--  A foreign key already creates an index on the child column, so
--  writing idx_booking_cust / idx_ticket_show / idx_showtime_movie
--  by hand would only duplicate the engine's index and slow down
--  every INSERT. What is left is what the reports actually filter
--  or sort on, and none of it is FK-driven:
-- ------------------------------------------------------------
CREATE INDEX idx_showtime_date      ON showtime (show_date);
CREATE INDEX idx_booking_status     ON booking  (status);
CREATE INDEX idx_payment_paid       ON payment  (paid_at);
CREATE INDEX idx_booking_sold_by    ON booking  (sold_by_staff_id);
CREATE INDEX idx_booking_cancelled_by ON booking (cancelled_by_staff_id);

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

-- ============================================================
--  CineBook  --  File 3 of 5 : VIEWS, TRIGGER, STORED PROCEDURE
--  Run AFTER 02_seed.sql
--
--  Database objects: Views, Audit Trigger, and Atomic Booking Procedure.
--  Views encapsulate common query logic so the same occupancy calculation
--  is not copied into multiple reports and quietly drifting apart.
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

-- ============================================================
--  CineBook  --  File 4 of 5 : REPORT QUERIES
--  Run AFTER 03_objects.sql
--
--  Queries 1-5 cover baseline operational cataloguing and aggregation.
--  Queries 6-13 cover advanced business reporting, reconciliation,
--  and analytical audits; 14 is the atomic transaction drill
--  walkthrough on a HELD booking.
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
--    Demonstrates relational completeness and alternative query strategy.
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
-- 14. TRANSACTION drill — Manual verification of atomic booking confirmation.
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
