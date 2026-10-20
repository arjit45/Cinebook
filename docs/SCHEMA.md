# CineBook — data dictionary

> **Generated file.** Produced by `tools/schemadoc.py` straight from
> `db/01_schema.sql`. Do not edit this by hand — edit the SQL and rerun
> `python tools/schemadoc.py`. The script refuses to write if any table,
> column, key or constraint cannot be classified and resolved.

**12 tables · 70 columns · 51 constraints · 5 secondary indexes**

Views, the audit trigger and the confirmation procedure live in
`db/03_objects.sql` and are documented in that file's own comments.


## Foreign-key graph

| Parent | Children (delete action) |
|---|---|
| `movie` | `showtime`.`movie_id` (del RESTRICT) |
| `screen` | `seat`.`screen_id` (del CASCADE), `showtime`.`screen_id` (del RESTRICT) |
| `seat` | `ticket`.`seat_id` (del RESTRICT) |
| `showtime` | `booking`.`showtime_id` (del RESTRICT), `ticket`.`showtime_id` (del RESTRICT) |
| `customer` | `booking`.`customer_id` (del RESTRICT) |
| `staff_user` | `booking`.`sold_by_staff_id` (del RESTRICT), `booking`.`cancelled_by_staff_id` (del RESTRICT) |
| `concession_item` | `booking_concession`.`item_id` (del RESTRICT) |
| `booking` | `ticket`.`booking_id` (del CASCADE), `booking_concession`.`booking_id` (del CASCADE), `payment`.`booking_id` (del CASCADE), `booking_audit`.`booking_id` (del CASCADE) |


## 1. `movie`

catalogue, independent of any screening

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `movie_id` | INT | NOT NULL | — | PK, AI |  |
| `title` | VARCHAR(150) | NOT NULL | — | — |  |
| `genre` | VARCHAR(40) | NOT NULL | — | — |  |
| `language` | VARCHAR(20) | NOT NULL | 'English' | — |  |
| `runtime_min` | SMALLINT | NOT NULL | — | — |  |
| `release_date` | DATE | NOT NULL | — | — |  |
| `certificate` | ENUM('U','UA','A') | NOT NULL | 'UA' | — |  |
| `imdb_rating` | DECIMAL(3,1) | NULL | — | — |  |

| Constraint | Kind | Definition |
|---|---|---|
| — (inline PK) | PRIMARY KEY | (`movie_id`) |
| `chk_movie_runtime` | CHECK | runtime_min BETWEEN 60 AND 240 |
| `chk_movie_rating` | CHECK | imdb_rating IS NULL OR imdb_rating BETWEEN 0 AND 10 |


## 2. `screen`

an auditorium

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `screen_id` | INT | NOT NULL | — | PK, AI |  |
| `name` | VARCHAR(40) | NOT NULL | — | UQ |  |
| `seat_capacity` | SMALLINT | NOT NULL | — | — |  |

| Constraint | Kind | Definition |
|---|---|---|
| — (inline PK) | PRIMARY KEY | (`screen_id`) |
| `uq_screen_name` | UNIQUE | (`name`) |
| `chk_screen_capacity` | CHECK | seat_capacity > 0 |


## 3. `seat`

physical seat, belongs to exactly one screen

uq_seat_position stops a screen having two "A7" seats. chk_seat_no allows 60 per row, which is why the JavaFX seat grid is built from MAX(row_label) / MAX(seat_no) and never from a hardcoded 8 x 12.

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `seat_id` | INT | NOT NULL | — | PK, AI |  |
| `screen_id` | INT | NOT NULL | — | → `screen` UQ* |  |
| `row_label` | CHAR(1) | NOT NULL | — | UQ* |  |
| `seat_no` | TINYINT | NOT NULL | — | UQ* |  |
| `seat_class` | ENUM('REGULAR','PREMIUM','RECLINER') | NOT NULL | 'REGULAR' | — |  |

\* part of a composite `UNIQUE` constraint — the combination is unique, not the column alone.

| Constraint | Kind | Definition |
|---|---|---|
| — (inline PK) | PRIMARY KEY | (`seat_id`) |
| `fk_seat_screen` | FOREIGN KEY | (`screen_id`) → `screen`(`screen_id`) · ON DELETE CASCADE ON UPDATE CASCADE |
| `uq_seat_position` | UNIQUE | (`screen_id`, `row_label`, `seat_no`) |
| `chk_seat_no` | CHECK | seat_no BETWEEN 1 AND 60 |


## 4. `showtime`

a screening of a movie on a screen

uq_screen_slot prevents two films starting in the same auditorium at the same minute.

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `showtime_id` | INT | NOT NULL | — | PK, AI |  |
| `movie_id` | INT | NOT NULL | — | → `movie` |  |
| `screen_id` | INT | NOT NULL | — | → `screen` UQ* |  |
| `show_date` | DATE | NOT NULL | — | UQ* |  |
| `start_time` | TIME | NOT NULL | — | UQ* |  |
| `base_price` | DECIMAL(7,2) | NOT NULL | — | — |  |

\* part of a composite `UNIQUE` constraint — the combination is unique, not the column alone.

| Constraint | Kind | Definition |
|---|---|---|
| — (inline PK) | PRIMARY KEY | (`showtime_id`) |
| `fk_showtime_movie` | FOREIGN KEY | (`movie_id`) → `movie`(`movie_id`) · ON DELETE RESTRICT ON UPDATE CASCADE |
| `fk_showtime_screen` | FOREIGN KEY | (`screen_id`) → `screen`(`screen_id`) · ON DELETE RESTRICT ON UPDATE CASCADE |
| `uq_screen_slot` | UNIQUE | (`screen_id`, `show_date`, `start_time`) |
| `chk_showtime_price` | CHECK | base_price > 0 |


## 5. `customer`

registered patron

Row 21 is the counter walk-in ("Counter Guest"). It exists so a sale always has a real customer_id without borrowing customer 1, who is a real person in the seed.

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `customer_id` | INT | NOT NULL | — | PK, AI |  |
| `full_name` | VARCHAR(80) | NOT NULL | — | — |  |
| `email` | VARCHAR(120) | NOT NULL | — | UQ |  |
| `phone` | VARCHAR(15) | NOT NULL | — | — |  |
| `joined_on` | DATE | NOT NULL | — | — |  |

| Constraint | Kind | Definition |
|---|---|---|
| — (inline PK) | PRIMARY KEY | (`customer_id`) |
| `uq_customer_email` | UNIQUE | (`email`) |
| `chk_customer_phone` | CHECK | CHAR_LENGTH(phone) BETWEEN 10 AND 13 |


## 6. `staff_user`

the login, and the audit trail's anchor

password_hash = SHA-256(salt + password), lowercase hex. The salt is per user, so two clerks with the same password still get different hashes.

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `staff_id` | INT | NOT NULL | — | PK, AI |  |
| `username` | VARCHAR(40) | NOT NULL | — | UQ |  |
| `password_hash` | CHAR(64) | NOT NULL | — | — |  |
| `salt` | CHAR(32) | NOT NULL | — | — |  |
| `full_name` | VARCHAR(80) | NOT NULL | — | — |  |
| `role` | ENUM('ADMIN','OPERATOR') | NOT NULL | — | — |  |
| `is_active` | BOOLEAN | NOT NULL | TRUE | — |  |

| Constraint | Kind | Definition |
|---|---|---|
| — (inline PK) | PRIMARY KEY | (`staff_id`) |
| `uq_staff_username` | UNIQUE | (`username`) |
| `chk_staff_hash` | CHECK | CHAR_LENGTH(password_hash) = 64 |


## 7. `concession_item`

the price list at the counter

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `item_id` | INT | NOT NULL | — | PK, AI |  |
| `name` | VARCHAR(60) | NOT NULL | — | UQ |  |
| `category` | ENUM('SNACK','DRINK','COMBO','DESSERT') | NOT NULL | — | — |  |
| `unit_price` | DECIMAL(7,2) | NOT NULL | — | — |  |
| `is_active` | BOOLEAN | NOT NULL | TRUE | — |  |

| Constraint | Kind | Definition |
|---|---|---|
| — (inline PK) | PRIMARY KEY | (`item_id`) |
| `uq_item_name` | UNIQUE | (`name`) |
| `chk_item_price` | CHECK | unit_price > 0 |


## 8. `booking`

one purchase event: seats, food, money, people

status: HELD -> CONFIRMED -> (CANCELLED) The three money columns are snapshots and must be written together, in ONE statement, because chk_booking_sum is evaluated on the finished row: total_amount = ticket_amount + fnb_amount A two-statement update fails halfway through. Hold, confirm and the seed all obey that.

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `booking_id` | INT | NOT NULL | — | PK, AI |  |
| `customer_id` | INT | NOT NULL | — | → `customer` |  |
| `showtime_id` | INT | NOT NULL | — | → `showtime` |  |
| `sold_by_staff_id` | INT | NOT NULL | — | → `staff_user` |  |
| `cancelled_by_staff_id` | INT | NULL | — | → `staff_user` |  |
| `booked_at` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | — |  |
| `status` | ENUM('HELD','CONFIRMED','CANCELLED') | NOT NULL | 'HELD' | — |  |
| `ticket_amount` | DECIMAL(8,2) | NOT NULL | 0.00 | — |  |
| `fnb_amount` | DECIMAL(8,2) | NOT NULL | 0.00 | — |  |
| `total_amount` | DECIMAL(8,2) | NOT NULL | 0.00 | — |  |
| `cancelled_at` | DATETIME | NULL | — | — |  |
| `refund_amount` | DECIMAL(8,2) | NOT NULL | 0.00 | — |  |

| Constraint | Kind | Definition |
|---|---|---|
| — (inline PK) | PRIMARY KEY | (`booking_id`) |
| `fk_booking_customer` | FOREIGN KEY | (`customer_id`) → `customer`(`customer_id`) · ON DELETE RESTRICT ON UPDATE CASCADE |
| `fk_booking_showtime` | FOREIGN KEY | (`showtime_id`) → `showtime`(`showtime_id`) · ON DELETE RESTRICT ON UPDATE CASCADE |
| `fk_booking_sold_by` | FOREIGN KEY | (`sold_by_staff_id`) → `staff_user`(`staff_id`) · ON DELETE RESTRICT ON UPDATE CASCADE |
| `fk_booking_cancelled_by` | FOREIGN KEY | (`cancelled_by_staff_id`) → `staff_user`(`staff_id`) · ON DELETE RESTRICT ON UPDATE CASCADE |
| `chk_booking_total` | CHECK | total_amount >= 0 |
| `chk_booking_sum` | CHECK | total_amount = ticket_amount + fnb_amount |
| `chk_refund_amount` | CHECK | refund_amount >= 0 AND refund_amount <= total_amount AND refund_amount <= ticket_amount · — food is never refunded |
| `chk_booking_cancelled_at` | CHECK | (status = 'CANCELLED' AND cancelled_at IS NOT NULL) OR (status <> 'CANCELLED' AND cancelled_at IS NULL) |


## 9. `ticket`

bridge table: which seat on which booking

showtime_id is carried here on purpose so that the UNIQUE key can enforce "one ticket per seat per screening". price is the snapshot of Seat.priceFor(showtime).

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `booking_id` | INT | NOT NULL | — | PK → `booking` |  |
| `seat_id` | INT | NOT NULL | — | PK → `seat` |  |
| `showtime_id` | INT | NOT NULL | — | → `showtime` UQ* |  |
| `price` | DECIMAL(7,2) | NOT NULL | — | — |  |

\* part of a composite `UNIQUE` constraint — the combination is unique, not the column alone.

| Constraint | Kind | Definition |
|---|---|---|
| — (table PK) | PRIMARY KEY | (`booking_id`, `seat_id`) |
| `uq_seat_per_showtime` | UNIQUE | (`showtime_id`, `seat_id`) |
| `fk_ticket_booking` | FOREIGN KEY | (`booking_id`) → `booking`(`booking_id`) · ON DELETE CASCADE ON UPDATE CASCADE |
| `fk_ticket_seat` | FOREIGN KEY | (`seat_id`) → `seat`(`seat_id`) · ON DELETE RESTRICT ON UPDATE CASCADE |
| `fk_ticket_showtime` | FOREIGN KEY | (`showtime_id`) → `showtime`(`showtime_id`) · ON DELETE RESTRICT ON UPDATE CASCADE |
| `chk_ticket_price` | CHECK | price > 0 |


## 10. `booking_concession`

the second M:N

One line per item per booking, so a second popcorn is quantity = 2, not a second row. unit_price is the snapshot. CASCADE from booking is safe because a booking is never deleted; if that ever changed, the food would follow it.

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `booking_id` | INT | NOT NULL | — | PK → `booking` |  |
| `item_id` | INT | NOT NULL | — | PK → `concession_item` |  |
| `quantity` | SMALLINT | NOT NULL | — | — |  |
| `unit_price` | DECIMAL(7,2) | NOT NULL | — | — |  |

| Constraint | Kind | Definition |
|---|---|---|
| — (table PK) | PRIMARY KEY | (`booking_id`, `item_id`) |
| `fk_bc_booking` | FOREIGN KEY | (`booking_id`) → `booking`(`booking_id`) · ON DELETE CASCADE ON UPDATE CASCADE |
| `fk_bc_item` | FOREIGN KEY | (`item_id`) → `concession_item`(`item_id`) · ON DELETE RESTRICT ON UPDATE CASCADE |
| `chk_bc_qty` | CHECK | quantity BETWEEN 1 AND 20 |
| `chk_bc_price` | CHECK | unit_price > 0 |


## 11. `payment`

money actually received; one per booking

method, amount, txn_ref and nothing else: there is no column a card number could occupy.

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `payment_id` | INT | NOT NULL | — | PK, AI |  |
| `booking_id` | INT | NOT NULL | — | → `booking` UQ |  |
| `method` | ENUM('CARD','UPI','CASH') | NOT NULL | — | — |  |
| `amount` | DECIMAL(8,2) | NOT NULL | — | — |  |
| `paid_at` | DATETIME | NOT NULL | — | — |  |
| `txn_ref` | VARCHAR(40) | NOT NULL | — | UQ |  |

| Constraint | Kind | Definition |
|---|---|---|
| — (inline PK) | PRIMARY KEY | (`payment_id`) |
| `uq_payment_booking` | UNIQUE | (`booking_id`) |
| `uq_payment_txnref` | UNIQUE | (`txn_ref`) |
| `fk_payment_booking` | FOREIGN KEY | (`booking_id`) → `booking`(`booking_id`) · ON DELETE CASCADE ON UPDATE CASCADE |
| `chk_payment_amount` | CHECK | amount > 0 |


## 12. `booking_audit`

written by a trigger, never by the app

| Column | Type | Null | Default | Key | Notes |
|---|---|---|---|---|---|
| `audit_id` | INT | NOT NULL | — | PK, AI |  |
| `booking_id` | INT | NOT NULL | — | → `booking` |  |
| `old_status` | VARCHAR(12) | NULL | — | — |  |
| `new_status` | VARCHAR(12) | NOT NULL | — | — |  |
| `changed_at` | DATETIME | NOT NULL | CURRENT_TIMESTAMP | — |  |

| Constraint | Kind | Definition |
|---|---|---|
| — (inline PK) | PRIMARY KEY | (`audit_id`) |
| `fk_audit_booking` | FOREIGN KEY | (`booking_id`) → `booking`(`booking_id`) · ON DELETE CASCADE ON UPDATE CASCADE |


## Secondary indexes

A foreign key already indexes the child column, so these are only the
lookups the reports actually filter or sort on.

| Index | Table | Columns |
|---|---|---|
| `idx_showtime_date` | `showtime` | `show_date` |
| `idx_booking_status` | `booking` | `status` |
| `idx_payment_paid` | `payment` | `paid_at` |
| `idx_booking_sold_by` | `booking` | `sold_by_staff_id` |
| `idx_booking_cancelled_by` | `booking` | `cancelled_by_staff_id` |
