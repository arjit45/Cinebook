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
