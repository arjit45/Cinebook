# CineBook — Build Plan

Status: System specification & architecture contract (Phases 0–5 implemented and verified).

---

## 1. What we are building

A **box-office console** for a single cinema: the machine the person at the counter
actually uses to sell seats, take money, and cancel a booking. Not a customer-facing
website, not a movie browser.

That choice is deliberate. Both subjects get graded from the same running program:

| Subject | What the examiner sees |
|---|---|
| **RDBMS** | You open Workbench, the schema is there, you run queries, then you switch to the app and sell a seat while two windows race for it. |
| **OOP** | A login, role-based navigation, a seat map, a checkout flow, exception handling, and a class model you can draw on the board. |

`db/` holds the schema and the sample data. This plan covers the database deliverable,
the Java application, and the contract that joins them.

---

### The document set

| Document | What it holds |
|---|---|
| `docs/PRD.md` | this file — system specification, domain rules, and architecture |
| `docs/SCHEMA.md` | data dictionary — the 12 tables, column types, keys, and constraints |
| `docs/QUERIES.md` | query catalogue — all 14 business queries with real MySQL execution output |
| `docs/TESTS.md` | integrity test log — proof of constraints refusing invalid rows |
| `docs/OOP.md` | the class model, the layering rule, and OOP concept coverage |
| `docs/class-diagram-*.svg` | architectural class diagrams (domain model and layer boundaries) |
| `db/` | schema DDL, seed data, database objects, queries, and user grants |

## 2. Scope

### In

- Staff login with two roles
- Dashboard: today's screenings and how full they are
- Browse the schedule by date
- **Seat map with live hold → confirm** (the centrepiece)
- Checkout: customer lookup or create, three payment methods, a one-click
  concession upsell, receipt
- Bookings: search, view, cancel with a refund, and **both names** on the record —
  who sold it, who cancelled it
- Admin: programme (movies + schedule), reports, and a shift summary (Z-report)
- SQL console tab for the viva, with a dropdown of the report queries

### Out (say no now, not in week three)

- Online customer self-service, payments gateways, email, PDF invoices
- Seat-map drag-to-select, 3D, trailers, any animation beyond one
- Multi-branch, user registration, password reset
- Concession stock control, kitchen tickets, dietary flags — one price list, nothing more
- Mobile, web, anything JavaFX does not do well

---

## 3. Screens

Seven programmatic JavaFX views and one side panel. Nothing else.

```
┌──────┬──────────────────────────────────────────────┐
│      │  TODAY · THU 17 OCT            operator ▾    │
│ rail │                                              │
│      │  ┌────────────────────────────────────────┐  │
│ Dash │  │ 20–27 Oct · 32 screenings              │  │
│ Sched│  ├────────────────────────────────────────┤  │
│ Book │  │ [SAT 24] [SUN 25] [MON 26] [TUE 27]     │  │
│ Prog │  │ ─────────────────────────────────────   │  │
│ Rep  │  │ Neon Monsoon   Audi 1  10:30   38/96   │  │
│      │  │ Turbo Kite     Audi 4  13:15   12/96   │  │
│      │  ...                                        │
└──────┴──────────────────────────────────────────────┘
```

1. **Login** — staff id + password, role badge. Two seeded accounts.
2. **Dashboard** — the day's eight screenings as a film strip; each frame is a
   showtime, its fill bar is real occupancy. Click a frame → seat map.
3. **Schedule** — every remaining day in the programme (the seed holds eight) as a strip
   of small selectable date panels (weekday, day, month, count, `TODAY` tag, riso rule for
   today). The selected panel takes a darker stock and a riso rule, and only that day's
   screenings are listed beneath the strip; the time column carries the clock only,
   because the date is in the panel.
4. **Seat Selection** — the auditorium printed on its own ivory sheet (wireframe
   below), opened from a schedule row: a turquoise screen, one ruled band per class
   with numbered cream seats and drawn armchairs, faded sold seats, and a green pick.
   A pick is a preview until Confirm, which holds the seats for the walk-in guest;
   sold seats explain why they cannot be picked.
5. **Bookings** — search by id / customer / date; detail rows including the
   concession lines; cancel action.
6. **Programme** *(admin)* — movie CRUD and a schedule builder with clash detection.
7. **Reports** *(admin)* — five report tabs, one per rubric query, plus a SQL console
   that is read-only by default and can be armed for writes by an admin. The
   **Shift summary** is a menu item, not a tab: it belongs to closing the till, not
   to studying the business.

Checkout is a **side panel** on the seat map, not its own screen — you never lose
sight of the seats you are selling. The blocks stack in the order the counter
conversation happens: the chosen seats and their class totals, then the customer, then
a 3-across concession grid where each tile carries a quantity stepper. The running total
and **Confirm payment** sit in a footer pinned below the scroll, so the amount and the
button that takes the money stay on screen however long the order grows. Food sits on
the receipt below the seats, sorted alphabetically by item name for deterministic output.

---

## 4. The seat map

```
  Neon Monsoon · Audi 1 · 18:30 · 147 min · UA
  ────────────────────────────────────────────────────────────
                     ╭───── SCREEN ─────╮    ← cyan, wings dip
     ───────── REGULAR SEATS · ₹300 ─────────
  A   ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢   A
  B   ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢   B
  C   ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢   C
  D   ▢ × × ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢   D     ← × = sold, faded
     ───────── PREMIUM SEATS · ₹375 ─────────
  E   ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢   E
  F   ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢ ▢   F
     ──────── RECLINER SEATS · ₹480 ────────
  G   ▭ ▭ ▭ ▭ ▭ ▭ ▭ ▭ ▭ ▭ ▭ ▭   G     ← drawn armchairs
  H   ▭ ▭ ▭ ▭ ▭ ▭ ▭ ▭ ▭ ▭ ▭ ▭   H

          E3 · Premium · ₹375.00 · picked for preview, not held
  ────────────────────────────────────────────────────────────
  SELECTED SEATS: 2 (E1, E2)    PREMIUM SEATS    TOTAL: ₹725 (INR)  [ Confirm Seats ]
```

Rules:

- **The page is ivory, not a panel.** The auditorium is printed straight on
  `#F9F5E9` with no box, border, column numbers or legend. The shell keeps its own
  stock; this screen gets its own sheet so the seating is the only thing on it.
- **Class is a band and a shape, not a wash.** Each class has a ruled heading with
  its live price, then its rows: regular is a 30 × 26 cream block with the seat
  number inside, premium the same block 3 px wider, recliner a 40 × 44 armchair
  drawn with a backrest and two cushion seams. There is no colour difference
  between classes, so the shape has to carry it — and a chair is unmistakable.
- **State is the only strong colour.** Free is cream, sold is faded with a hairline
  × (not solid ink), picked is `#0DAB77` green with a white number. A pick is a
  preview until Confirm: pressing it writes one `HELD` booking for the walk-in
  guest in a single transaction, the seats darken to `#0A8A60` and stop accepting
  clicks, and the bar reads `Held #id`. A duplicate key means another clerk won the
  seat: the page reloads and names the seat that was taken.
- **The screen is the one cyan surface:** `#18C3D6`, flat top, lower edge sweeping
  down at the ends, `SCREEN` under the centre of that curve. Nothing else on the
  page is cyan.
- Sold seats are struck with an × as well as faded — hue alone fails for
  colour-blind users and on a projector.
- Row letters sit at both margins, outside the seats, so a row can be found from
  either side of the room. Seats carry their number; the tooltip carries `A7`.
- Clicking a sold seat says why it is unavailable, rather than doing nothing.
- **The booking bar is the bill, and it is live.** It pins under the seating and
  reads `SELECTED SEATS: n (A1, A2)`, the class when the picks share one
  (`PREMIUM SEATS`, otherwise `MIXED CLASSES`), the summed price and the number
  of rupees in `(INR)`. Whole rupees print without `.00`; paise survive. Confirm
  is disabled while nothing is picked, and holds every picked seat in one
  transaction (see §5).
- Keyboard: arrow keys move, space selects, Enter holds. Focus ring always visible
  — still to come, because a tab stop per seat makes the page hard to leave today.
- **The grid is built from the data, never from the FXML.** Rows and columns come
  from `MAX(row_label)` and `MAX(seat_no)` for that screen. The seed is 4 screens ×
  8 rows × 12 seats today, but `chk_seat_no` allows 60 per row — a hardcoded 8 × 12
  `GridPane` would silently lose seats the first time somebody adds a row, and the
  screen would look fine while doing it.

---

## 5. Flows

### A. Sell a seat (the demo path)

1. Login → Dashboard.
2. Click a screening frame.
3. Seats load with occupancy (single query, one `LEFT JOIN`).
4. Click seats → panel totals by class in real time.
5. **Hold** → one transaction: `INSERT booking(HELD)` + `INSERT ticket` per seat,
   then a single `UPDATE` that sets `ticket_amount`, `fnb_amount` and
   `total_amount` together — one statement, because `CHECK (total_amount =
   ticket_amount + fnb_amount)` is evaluated on the finished row, not on a
   half-updated one. The buyer is the seeded walk-in guest for now, because
   `booking.customer_id` is NOT NULL and the design asks who the buyer is *after*
   the hold; the panel below reassigns it before payment. A duplicate key means
   someone beat you to it → `SeatUnavailableException` → map refreshes, toast
   names the seat.
6. Checkout panel: search customer by phone, or create inline. Choosing one
   updates `booking.customer_id` on the hold already attached to the walk-in row.
   The **Use walk-in guest**
   control fills a seeded counter guest — `Counter Guest / walkin@cinebook.local /
   9000000000` — not `customer_id = 1`, which is Aarav Sharma and would quietly
   credit every walk-up sale to him. This is a deliberate trade: the walk-in row
   accrues bookings, so the "loyal customers" report tilts towards it. Say that out
   loud before the examiner finds it in query 6.
7. Concession grid: each tile is a name, a price and a stepper. **+** on *Salted
   Popcorn (L)* adds one line at today's price, **−** takes one back, and the badge
   between them is the quantity — `booking_concession.quantity`, which the schema
   holds between 1 and 20. `booking_concession.unit_price` is the snapshot, so
   tomorrow's price change does not rewrite this receipt.
8. Choose Card / UPI / Cash → **Confirm** → `CALL sp_confirm_booking(id, method)`.
   The procedure totals the tickets **and** the food, writes `payment.amount` equal
   to the grand total, and `SELECT … FOR UPDATE` holds the row while it does it.
9. Receipt stub slides up into the panel. "Print" writes `receipts/BK-0042.txt`: the directory is
   created with `Files.createDirectories` before the first write, and if the jar's
   folder is unwritable (a Program Files install, a locked lab profile) it falls
   back to `~/CineBook/receipts` and says so in the toast. "Open receipt" launches
   the file — File I/O you can see, rather than a line in a log. "Done" returns to
   the schedule, occupancy already updated.

The receipt is laid out for a **40-column thermal printer**, not for a dialog box:
monospace, one item per line, amounts right-aligned so the rupees form a straight
column, food below the seats. The warp is worth saying out loud in the viva — a
proportional font makes the alignment look right on screen while the printed slip is
ragged, so the layout is checked by opening the real `.txt`, at a fixed width, rather
than by trusting how it renders in the app.

### B. Cancel

Bookings → find → Cancel. The dialog computes the refund **before** you commit to it,
so the clerk can read the number out loud:

| Time until the show | Refund |
|---|---|
| More than 2 hours | 90% of `ticket_amount` |
| 2 hours or less | 50% of `ticket_amount` |
| Already started | no refund, and the Cancel button is disabled |

**Food is not refunded.** That is real counter policy, not a shortcut: a popcorn the
customer has already carried away cannot be resold. So the percentage applies to
`ticket_amount` only, and `fnb_amount` is untouched — which is also why the refund
ceiling in the schema is `ticket_amount` and not the grand total.

On confirm, one transaction: delete the `ticket` rows, set `CANCELLED`,
`cancelled_at = NOW()`, `cancelled_by_staff_id` from the session, and `refund_amount`
— leaving `ticket_amount`, `fnb_amount` and `total_amount` untouched as the historical
figures the refund was calculated from. Deleting the tickets is what frees the seats,
because `UNIQUE(showtime_id, seat_id)` is the real lock. The trigger writes the audit
row.

The booking row is kept, never deleted: a cancelled booking is still evidence.

Every booking records **who sold it** (`sold_by_staff_id`) and, once cancelled, **who
refunded it** (`cancelled_by_staff_id`). Without the second column a refund is
attributed to whoever sold the ticket, which makes per-clerk reconciliation wrong on
exactly the day it matters — and both columns are FKs to `staff_user`, so that table
is not an island in the ER diagram.

### C. Hold expiry

A background `ScheduledExecutorService` runs every 60 s: set `HELD` bookings older
than 10 minutes to `CANCELLED`, with `cancelled_at = NOW()` and **`refund_amount = 0`**
— nothing was ever charged, so the 90 / 50 rule does not apply to them.
`cancelled_by_staff_id` stays `NULL`: the system did it, and inventing a clerk would
poison the shift report. Tickets are freed and the trigger records `HELD → CANCELLED`
in the audit trail.

Demo it by holding a seat and waiting: the seat comes back on its own.

### D. Programme clash (admin)

Add a screening; if the slot is taken the `UNIQUE(screen_id, show_date, start_time)`
key rejects it. The app translates the duplicate-key error into
"Audi 2 already has a show at 19:00", which is a much better demo than a stack trace.

### E. Reports (admin)

Five tabs, each backed by one query from `04_queries.sql`: revenue per screen,
top films, dormant films, occupancy, loyal customers. Sixth tab: a SQL text box and
a results grid — type anything, see rows. This is what you open when the examiner
says "show me all films not screened in six months".

The console carries a **preset dropdown**: the thirteen report queries, loaded from one
resource file into the editor with F5 to run. Same text as `04_queries.sql`, so there
is one copy of each query and no drift between the handout and the app.

### F. Shift close (Z-report)

One menu item, one query, one screen. For the logged-in clerk and today's date it
reports: bookings sold, tickets and food taken, refunds paid against bookings *this
clerk cancelled*, and a split by payment method. `BigDecimal` throughout,
`groupingBy` for the split — the point is that a till report in `double` is a bug
waiting for a busy Saturday.

Refunds are counted against `cancelled_by_staff_id`, not `sold_by_staff_id`; that is
the whole reason the column exists.

---

## 6. Class design

```
ui/        views, components, css        (no SQL here, ever)
service/   BookingService, CheckoutService, ProgrammeService, ReportService,
           AuthService, ScheduleService
           ↳ owns transactions
dao/       MovieDao, BookingDao, SeatDao, ShowtimeDao, ...  ↳ owns SQL
model/     Movie, Screen, Seat, Showtime, Customer, Booking, Ticket
           StaffUser, ConcessionItem, BookingConcession, enums
exception/ BookingException → SeatUnavailableException, PaymentFailedException,
           ValidationException, AuthException
util/      Db (3-connection pool with single-borrow enforcement), ReceiptWriter, Formatters
test/      JUnit 5 — unit, database integration, and headless UI suites
```

The rule that keeps it clean: **UI never sees a `Connection`, DAO never sees a
`Button`.** Services are the only layer allowed to open a transaction.

Pricing lives on the entity, not in a service: `Seat.priceFor(showtime)` applies the
1.00 / 1.25 / 1.60 multiplier for Regular, Premium and Recliner. The subclasses do the
work, so adding a fourth seat class means one new class and one new enum value — no
`switch` statement anywhere in the codebase.

There is deliberately **no discount strategy**. A matinee is a cheaper *showtime*, and
`showtime.base_price` already encodes that; discounting the seat as well would store
the same fact twice. With no discounts, `booking.ticket_amount = SUM(ticket.price)`
exactly, and the only thing that moves the grand total away from it is food — in its
own column, at a snapshotted price. That is what keeps query 8 returning zero rows:
it compares `total_amount` against `ticket_amount + fnb_amount`, and both against
`payment.amount`, rather than one number against itself.

`Booking` holds a `List<ConcessionLine>` beside its tickets. `fnbTotal()` is a stream
reduction in `BigDecimal` — no `double` anywhere near money — and `total()` delegates,
so the entity never offers a setter for a figure it can derive.

The concept-by-concept coverage table — every graded OOP topic mapped to the class that
demonstrates it — and the notes on the three patterns worth more than a name are in
**`docs/OOP.md`**, because they are rubric answers rather than build decisions. The two
generated class diagrams are `docs/class-diagram-domain.svg` and
`docs/class-diagram-layers.svg`, drawn by `tools/classdiagram.py`.

## 7. The schema

Twelve tables. Four decisions inside it are worth stating out loud, because each one
answers a question an examiner is likely to ask, and each one is enforced by the
database rather than described in a comment.

### Login

```sql
CREATE TABLE staff_user (
    staff_id      INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(40)  NOT NULL UNIQUE,
    password_hash CHAR(64)     NOT NULL,   -- SHA-256(salt + password)
    salt          CHAR(32)     NOT NULL,   -- per user, stored beside the hash
    full_name     VARCHAR(80)  NOT NULL,
    role          ENUM('ADMIN','OPERATOR') NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE
);
```

Two seeded rows: `admin` and `operator`. Passwords are salted and hashed, never stored
plain. The salt is per user and lives beside the hash, so two clerks who choose the same
password still get different hashes, and a stolen hash cannot be looked up in a rainbow
table.

### The seller and the canceller

```sql
ALTER TABLE booking
    ADD COLUMN sold_by_staff_id      INT          NOT NULL,
    ADD COLUMN cancelled_by_staff_id INT          NULL,
    ADD COLUMN cancelled_at          DATETIME     NULL,
    ADD COLUMN refund_amount         DECIMAL(8,2) NOT NULL DEFAULT 0.00,
    ADD CONSTRAINT fk_booking_sold_by FOREIGN KEY (sold_by_staff_id)
        REFERENCES staff_user (staff_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_booking_cancelled_by FOREIGN KEY (cancelled_by_staff_id)
        REFERENCES staff_user (staff_id) ON DELETE RESTRICT;
```

`cancelled_by_staff_id` is what makes reconciliation honest. A refund attributed to the
seller is wrong whenever a different clerk takes the return — which is most returns,
because the person who sold the ticket is often not at the desk when it is refunded.
`NULL` is meaningful here: a hold that expired was cancelled by the job, not by a person.

Those are two separate 1 : N edges to `staff_user`, from two different columns, which is
why the relationship list in the ER diagram names that table twice.

### Money, split three ways

`booking.total_amount` is the **grand total**, so `payment.amount` holds the whole sum
the customer paid. Tickets are deleted when a booking is cancelled, so the ticket
subtotal is a column of its own rather than something derived from `ticket`:

```sql
ALTER TABLE booking
    ADD COLUMN ticket_amount DECIMAL(8,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN fnb_amount    DECIMAL(8,2) NOT NULL DEFAULT 0.00,
    ADD CONSTRAINT chk_booking_sum CHECK (total_amount = ticket_amount + fnb_amount),
    ADD CONSTRAINT chk_refund_amount CHECK (
        refund_amount >= 0
        AND refund_amount <= total_amount
        AND refund_amount <= ticket_amount),   -- food is never refunded
    ADD CONSTRAINT chk_booking_cancelled_at CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL));
```

Three consequences, all of them deliberate:

1. **`ticket_amount` is a snapshot, like `ticket.price`.** After a cancellation the
   tickets are gone and the figure stays, because the refund was calculated from it.
   Recomputing it from `ticket` would silently refund zero.
2. **Every write that changes money sets all three columns in one statement.**
   `CHECK (total = ticket + fnb)` is evaluated on each finished row, so a two-statement
   update fails halfway through. Hold, confirm and the seed each use a single `UPDATE`.
3. **Every booking carries a seller.** The FK is `NOT NULL`, so a sale without a staff
   id cannot exist even if the session handling is broken.

### Concessions

`concession_item` is a price list; `booking_concession` is the second many-to-many,
carrying its own `quantity` and a price snapshot:

```sql
CREATE TABLE concession_item (
    item_id    INT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(60)  NOT NULL,
    category   ENUM('SNACK','DRINK','COMBO','DESSERT') NOT NULL,
    unit_price DECIMAL(7,2) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_item_name   UNIQUE (name),
    CONSTRAINT chk_item_price CHECK (unit_price > 0)
);

CREATE TABLE booking_concession (
    booking_id INT          NOT NULL,
    item_id    INT          NOT NULL,
    quantity   SMALLINT     NOT NULL,
    unit_price DECIMAL(7,2) NOT NULL,
    PRIMARY KEY (booking_id, item_id),
    CONSTRAINT fk_bc_booking FOREIGN KEY (booking_id)
        REFERENCES booking (booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_bc_item    FOREIGN KEY (item_id)
        REFERENCES concession_item (item_id) ON DELETE RESTRICT,
    CONSTRAINT chk_bc_qty    CHECK (quantity BETWEEN 1 AND 20),
    CONSTRAINT chk_bc_price  CHECK (unit_price > 0)
);
```

The composite primary key is the design: one line per item per booking, so a second
popcorn is `quantity = 2`, not a second row. `ON DELETE CASCADE` from `booking` is safe
precisely because a booking is never deleted; if that ever changed, the food would
follow it, which is the correct behaviour.

Everything else in the schema — `movie`, `screen`, `seat`, `showtime`, `customer`,
`ticket`, `payment`, `booking_audit` — is a plain 3NF table with its keys, `CHECK`s and
FK actions in `db/01_schema.sql`.

The four snapshots (`ticket.price`, `booking_concession.unit_price`,
`booking.ticket_amount`, `booking.fnb_amount`) are the one intentional exception to 3NF
in the whole design: historical sales must never mutate when catalog prices change.

---

## 8. Visual direction

**Concept — paper and projector light.** The console is ticket stock: warm, matte,
printed. The seat page is a lighter sheet of the same paper — ivory, unboxed, with a
single turquoise screen sweeping down at its ends and the room grouped into ruled
class bands. Seats are cream blocks on a tan hairline; recliners are drawn as chairs.
State is the only strong colour: sold fades with an ×, a pick turns emerald. Nothing
about class depends on hue, so the map survives a projector and colour blindness.

### Palette

| Token | Hex | Use |
|---|---|---|
| stock | `#E9E7DF` | app background, ticket stock |
| paper | `#F9F5E9` | seat page |
| ink | `#16181C` | body text, rules |
| riso | `#D8412F` | selection, primary action |
| teal | `#14615C` | confirmed, focus, occupancy fill |
| cyan | `#18C3D6` | the screen — the only cyan surface |
| seat | `#ECE3CE` on `#BFB49F` | free seat fill and hairline |
| pick | `#0DAB77` | a picked seat |
| held | `#0A8A60` | a seat whose hold has committed |
| muted seat | `#F1EBDD` on `#DCD5C4` | sold seat, with a pale × |
| mute | `#8C8B84` | secondary text, disabled |

Warm paper, a cold print red for actions and one projector cyan for the screen — the
palette of a risograph ticket, not a dashboard.

### Type

**Archivo** — one family, two jobs. **Archivo Expanded SemiBold** for headings,
screen titles and money; **Archivo Regular** for everything else. Tabular figures
for all prices so columns align. Bundled in `resources/fonts` and loaded with
`Font.loadFont()`, so it looks the same on the lab machine.

Two notes on how this was built. Archivo ships as one variable font whose width axis
JavaFX cannot select — `-fx-font-weight` picks a face, nothing picks a width — so the
two static faces were cut from the variable original with `fontTools` and registered as
`Archivo Regular` and `Archivo SemiBold`. The Expanded cut therefore renders as
SemiBold at standard width. `src/main/resources/fonts/README.md` records the commands
and the OFL position on the instancing. The variable file registers its family as
"Archivo SemiBold", which silently dropped every unsuffixed lookup to the system font
until the static faces replaced it — worth knowing if the font is ever swapped.

### Layout

Left rail 208 px, content on a 12-column grid with a 72 px gutter, lines instead of
boxes — the only filled surfaces are the screen, buttons and seats. Nothing gets a
shadow. Nothing gets a 16 px radius "just because"; buttons and seats are 4 px, the
screen 0. The seat page is 730 px wide: row letters at its margins, seats centred
between them, so the widest row (twelve armchairs) sets the measure.

### Motion

Focused and functional: no page-load fades, no hover bounces. The checkout panel uses a clean slide transition to open and close, keeping focus on the active transaction.

### Copy

Buttons name their outcome: "Hold seats", "Confirm payment", "Cancel booking" —
and the toast repeats the same verb. Errors state what happened and what to do:
"A7 was taken while you were choosing. Pick another seat." No apologies, no vagueness.

The concession grid is a tile grid, not a catalogue: eight items, three across, name
over price over a stepper. It sits after the customer because that is the order the
counter conversation happens in — seats, who is buying, then the upsell — and it is the
one block that grows with the order, which is why the running total and the pay button
live outside the scroll.

---

## 9. Build order

| Phase | Deliverable | Why this order |
|---|---|---|
| 0 | Install JDK 21, Maven, project skeleton, DB connection smoke test | Nothing else runs without it |
| 1 | `model` + `dao` + `Db`, no UI | Verified with a `main()` before any screen exists |
| 2 | App shell: login, rail navigation, CSS theme | Locks the visual direction early |
| 3 | Schedule + seat map, read-only | The hard UI, proven before transactions |
| 4 | Hold + confirm | Core transaction; the RDBMS centrepiece |
| 5 | Checkout panel + receipt writer + concession upsell | Completes the sale |
| 6 | Bookings list + cancel + hold expiry | Proves the seat is genuinely free again |
| 7 | Programme + reports + SQL console (presets) + shift summary | Admin surface, lowest risk |
| 8 | Packaging, demo script, screenshots | Submission polish |

Each phase ends with something runnable. If time runs out at phase 5 you still have a
complete product.

Tests are written **with** each phase, not afterwards — pure domain logic like `RefundPolicy`
and `Seat.priceFor` are tested from the start because they are pure functions, while
database and UI flows are validated through automated integration suites.

---

## 10. Database deliverable plan

The lab sheet asks for four things — ER diagram, schema, queries, sample output —
plus a live viva. Everything below serves one of those. The database is written; this
is the documentation and proof around it.

### 10.1 Artifacts

| # | Artifact | File | Description |
|---|---|---|---|
| 1 | ER diagram | authored in ERDPlus | 12 entities, Crow's-foot notation, keys and cardinalities |
| 2 | Data dictionary | `docs/SCHEMA.md` | generated by `tools/schemadoc.py` from `db/01_schema.sql` |
| 3 | Normalization writeup | course report | 1NF through 3NF derivations and intentional snapshot exceptions |
| 4 | DDL | `db/01_schema.sql` | 12 tables, constraints, foreign keys, cascade actions |
| 5 | Seed data | `db/02_seed.sql` | deterministic seed relative to `CURDATE()` |
| 6 | Views, trigger, procedure | `db/03_objects.sql` | `v_screen_occupancy`, `v_today_shows`, `trg_booking_audit`, `sp_confirm_booking` |
| 7 | Query catalogue + output | `db/04_queries.sql`, `docs/QUERIES.md` | 14 business queries fingerprinted with real MySQL execution output |
| 8 | App user + grants | `db/05_users.sql` | least-privilege `cinebook_app` user grants |
| 9 | Integrity test log | `docs/TESTS.md` | proof of constraint and trigger refusals against invalid rows |

The scripts in `db/` are now run, not merely reviewed: `db/00_setup.sql` loaded the full
dataset on MySQL 8.0.46 and the seven integrity checks in `docs/TESTS.md` were observed
against it, with the two branches no seeded row can reach listed separately instead of
ticked. `db/05_users.sql` is the exception — it needs an account holding `CREATE USER`,
so it remains a written script only, and nothing here claims otherwise.

The same rule that produced the ER diagram and the data dictionary covers the queries:
`tools/querydoc.py` reads `db/04_queries.sql` and writes both `docs/QUERIES.md` and
`docs/TESTS.md`, refusing to write if a statement cannot be classified, if a query names
a table that does not exist, or if the test table cites a query number that has gone
away. `tools/buildsetup.py` rebuilds the one-shot `db/00_setup.sql` from `01`–`04`, and
`querydoc.py` refuses to document queries that the one-shot script does not contain —
that check caught a real fault: the bundle once carried the schema twice, which would
have failed on the first `CREATE TABLE`.

`tools/sqlcheck.py` closes the gap those generators left: they proved a query's *tables*
exist, not its columns. It resolves every alias, derived table and view against the
schema, so a typo fails as `ERROR 1054` would, offline; it also checks the seed's literal
rows against every `ENUM`, simple `CHECK` and `UNIQUE`, proves the money identity on the
`SET` clauses (`total_amount = ticket_amount + fnb_amount`), refuses refund factors
above 1, requires `payment.amount` to come from `booking.total_amount`, and re-hashes
the documented staff passwords from their salts. It runs inside the docs gate, so a bad
column cannot reach `docs/QUERIES.md` either.

**The pasted output carries a date.** `docs/QUERIES.md` shows real rows, not
illustrations, and they come from `docs/query_output.json`: `python tools/querydoc.py
--capture` re-runs every query against the seeded `cinebook` and records exactly what
MySQL printed, each entry tagged with a fingerprint of the statement it belongs to.
Editing a query without recapturing therefore fails the gate (`recorded output is
stale`) rather than showing rows that belong to different SQL. The seed is anchored to
`CURDATE()`, so a rebuild on another day moves every printed calendar date while
counts, revenue, rankings and `days_dormant` stay put — running `--capture` after a
rebuild is what keeps the catalogue describing the database beside it. `--check-db`
answers that question against a live server and is deliberately **advisory**: it names
an aged or drifted capture and always exits 0, so no submission script can fail because
a snapshot is a week old.

**Timing.** Artifacts 1–3 are derived from the schema, so they can be produced before
the app exists. Artifact 9 followed the same shape: the checklist was generated first,
and a run has since filled its boxes in.

Everything this section describes is *evidence*: the diagram, the DDL, the queries and
their output.

### 10.2 ER diagram

Crow's-foot notation, twelve entities. The relationships that need care:

| Relationship | Cardinality | Note |
|---|---|---|
| movie → showtime | 1 : N | a film screens many times |
| screen → showtime | 1 : N | an auditorium hosts many shows |
| screen → seat | 1 : N | seats are physical, not per-show |
| showtime → booking | 1 : N | many bookings per screening |
| customer → booking | 1 : N | one patron, many purchases |
| staff_user → booking (sold) | 1 : N | the operator who sold it |
| staff_user → booking (cancelled) | 1 : N, optional | who took the refund; `NULL` when a hold expired |
| booking ↔ seat | **M : N** | resolved by `ticket`, which carries `price` |
| booking ↔ concession_item | **M : N** | resolved by `booking_concession`, which carries `quantity` and `unit_price` |
| booking → payment | 1 : 1 | enforced by `UNIQUE(booking_id)` |
| booking → booking_audit | 1 : N | written by trigger |
| ticket → showtime | 1 : N | carried for the `UNIQUE(showtime_id, seat_id)` key, not for the model |

Always show `ticket` and `booking_concession` as **associative entities**, each with
its own attributes. This explicitly models the fact that M:N relationships require bridge
tables with their own candidate keys and snapshot attributes.

The ER diagram is authored in **ERDPlus** in Crow's-foot notation directly matching `01_schema.sql`:
every foreign key in the schema corresponds to an explicit relationship edge with exact
cardinality constraints.

### 10.3 Privileges (`db/05_users.sql`)

```sql
CREATE USER IF NOT EXISTS 'cinebook_app'@'localhost' IDENTIFIED BY '<choose-one>';
GRANT SELECT, INSERT, UPDATE, DELETE ON cinebook.* TO 'cinebook_app'@'localhost';
GRANT EXECUTE ON PROCEDURE cinebook.sp_confirm_booking TO 'cinebook_app'@'localhost';
```

The application user gets **no DDL rights, no `GRANT OPTION`, and `EXECUTE` on exactly
one routine** — the confirmation procedure it actually calls. It cannot create a
table, cannot drop one, and cannot call a procedure it was not given. One short
section in the report under "security", and it demonstrates DCL alongside DDL and DML.

Note what is *not* granted: `CREATE`, `ALTER`, `DROP`, `CREATE ROUTINE`, and
`TRIGGER`. Those run once, as root, from `00_setup.sql`.

**Recorded run (MySQL 8.0.46, 2026-10-20).** Executed as root, then every claim above
was tested from the application account rather than assumed:

```text
GRANT USAGE ON *.*                    <- no rights at server level
GRANT SELECT, INSERT, UPDATE, DELETE ON `cinebook`.*
GRANT EXECUTE ON PROCEDURE `cinebook`.`sp_confirm_booking`
```

| Attempt as `cinebook_app` | Result |
|---|---|
| `SHOW DATABASES` | `cinebook`, `information_schema`, `performance_schema` — no `employee_db`, `restaurant_db`, `university_db` |
| `INSERT` / `UPDATE` / `DELETE` inside a transaction, then `ROLLBACK` | all succeed; row count returns to 4 screens |
| `CREATE TABLE` / `DROP TABLE` / `ALTER TABLE` / `TRUNCATE` | `1142` command denied |
| `USE employee_db` | `1044` access denied to database |
| `SELECT` from `mysql.user` | `1142` SELECT command denied |
| `GRANT` | `1044` — no `GRANT OPTION`, cannot pass rights on |
| `CREATE PROCEDURE` | `1044` — no `CREATE ROUTINE` |
| `SELECT … INTO OUTFILE` | `1227` — no `FILE` privilege |
| `CALL sp_confirm_booking(1,'UPI')` | `1644` *Only a HELD booking can be confirmed* — the call **reached the procedure body**, which a missing `EXECUTE` would have blocked with `1370` |

Note: `CREATE TRIGGER` is refused with `1419` (`log_bin_trust_function_creators`), not
`1142`, because MySQL checks `SUPER` first when binary logging is on. The account is
refused either way, and the grants above grant no `TRIGGER` privilege.

## 11. Integration plan — how the two halves join

### 11.1 The contract

Four rules, and every bug that crosses the boundary breaks one of them:

1. **`db/00_setup.sql` is the only source of schema truth.** The app never runs DDL.
2. **The app connects as `cinebook_app`**, never as root.
3. **Services own transactions. DAOs are stateless.** Nothing else calls `commit()`.
4. **Controllers contain no SQL.** If a controller imports `java.sql`, it is wrong.

### 11.2 Connection

Configuration lives in `src/main/resources/app.properties`, which is **not committed**:

```properties
jdbc.url=jdbc:mysql://localhost:3306/cinebook?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Kolkata
jdbc.user=cinebook_app
jdbc.password=
```

`serverTimezone` is not optional, but the reason is narrower than the folklore. Every
temporal column here is `DATETIME`, which Connector/J passes through untouched —
measured on 2026-10-20, a deliberately wrong `serverTimezone=UTC` against a +05:30
server still read `21:00` back as `21:00`, so the "booked at 9pm shows 3am" story is
true of `TIMESTAMP`, not of this schema. What the parameter actually buys is a decided
answer to "what zone is the server in?" at connect time instead of probing
`@@system_time_zone` — which on Windows answers with names like `India Standard Time` —
and the correct conversion whenever a `java.sql.Timestamp` or a future `TIMESTAMP`
column is involved. `DbSmokeTest` asserts it is present on the URL the connection
really used, not merely on the file.

Add `app.properties.example` with the fields blank so the submission is runnable.

### 11.3 Type mapping

The mismatch that causes most student bugs. Handle these nine and nothing surprises you:

| MySQL | Java | Trap |
|---|---|---|
| `INT` | `int` / `Integer` | use `Integer` for nullable FK columns |
| `DECIMAL(8,2)` | `BigDecimal` | never `double` for money |
| `DATE` | `LocalDate` | `rs.getObject(i, LocalDate.class)` |
| `TIME` | `LocalTime` | same |
| `DATETIME` | `LocalDateTime` | same |
| `ENUM` | Java `enum` | store `name()`, read with `valueOf` |
| `VARCHAR` | `String` | watch `CHAR` padding on fixed widths |
| `TINYINT(1)` | `boolean` | only if declared `BOOLEAN` |
| `NULL` | `null` | check `wasNull()` after primitive getters |

### 11.4 SQL error → Java exception

This mapping is what turns the database's constraints into the app's good error
messages. It is the single clearest proof that the two halves are designed together.

| MySQL error | Meaning | Java exception | UI behaviour |
|---|---|---|---|
| `1062` | duplicate key | `SeatUnavailableException` | "A7 was taken — pick another seat" |
| `1451` / `1452` | FK violation | `ValidationException` | "That record is still in use" |
| `3819` | CHECK failed | `ValidationException` | field-level error |
| `45000` | `SIGNAL` from procedure | `BookingException` | shows the procedure's message |
| `1045` | bad credentials | startup failure | config screen, not a crash |
| `08S01` | server down | `DbUnavailableException` | "MySQL is not running" |

### 11.5 Object map

| Table | Model | DAO | Screen that uses it |
|---|---|---|---|
| `movie` | `Movie` | `MovieDao` | Programme, Schedule |
| `screen` | `Screen` | `ScreenDao` | Schedule, Programme |
| `seat` | `Seat` + subclasses | `SeatDao` | Seat Map |
| `showtime` | `Showtime` | `ShowtimeDao` | Schedule, Dashboard |
| `customer` | `Customer` | `CustomerDao` | Checkout |
| `booking` | `Booking` | `BookingDao` | Checkout, Bookings |
| `ticket` | `Ticket` | `TicketDao` | Seat Map, Bookings |
| `payment` | `Payment` + subclasses | `PaymentDao` | Checkout |
| `booking_audit` | `AuditEntry` | `AuditDao` | Reports |
| `staff_user` | `StaffUser` + subclasses | `StaffDao` | Login |
| `concession_item` | `ConcessionItem` | `ConcessionDao` | Checkout, Reports |
| `booking_concession` | `BookingConcession` | `ConcessionDao` | Checkout, Bookings |

### 11.6 One query, two audiences

The report queries and the app queries are the same queries. The difference is only
`?` placeholders: `04_queries.sql` hardcodes dates for the report, `ReportService`
passes them as parameters. This establishes complete consistency between reporting
and transactional systems.

### 11.7 Threading and Connection Concurrency

All JDBC operations execute on background threads; JavaFX renders on the FX Application
Thread and nothing else. Blocking the FX thread freezes the window and degrades POS responsiveness.

`FxTasks.run(work, done, failed)` coordinates background execution: work runs on a
two-thread daemon pool and both outcomes return through `Platform.runLater`, allowing
callbacks to safely update UI nodes.

Three critical concurrency rules govern the architecture:

1. **Daemon threads and clean shutdown.** Worker threads are created via a `ThreadFactory`
   setting `setDaemon(true)`, and `CineBookApp.stop()` calls `FxTasks.shutdown()`. This
   guarantees background tasks never keep the JVM alive after the window closes.
2. **Three-connection handoff pool.** Concurrent consumers (two POS client windows and one
   background hold-expiry daemon) borrow connections from an `ArrayBlockingQueue(3)` in `Db.java`
   via try-with-resources (`try (Db.Handle handle = db.borrow())`). Connections are validated
   before use (`connection.isValid(2)`). Starvation is prevented by construction: the design
   admits at most three concurrent consumers.
3. **Single borrow enforcement and atomic release.** A thread holding a connection is refused
   nested borrows at runtime via a `ThreadLocal<Integer>` guard (`heldByThisThread`), preventing
   circular wait deadlocks. The hold release path (`BookingService.release`) runs on a single
   borrowed connection as an atomic three-statement transaction:
   - `UPDATE booking SET status = 'CANCELLED', cancelled_at = NOW(), refund_amount = 0.00, cancelled_by_staff_id = ? WHERE booking_id = ? AND status = 'HELD'`
     (`cancelled_by_staff_id` is set for clerk releases and `NULL` for background sweeps, preserving audit reports; throws `ValidationException` if rows affected == 0).
   - `DELETE FROM ticket WHERE booking_id = ?` (releases seats and the `UNIQUE(showtime_id, seat_id)` constraint).
   - `DELETE FROM booking_concession WHERE booking_id = ?` (cleans uncommitted concessions).

### 11.8 Administrative DML and Engine-Level Verification

The internal console is a reporting tool, not an open administrative terminal. Default mode accepts
`SELECT` · `SHOW` · `EXPLAIN` · `DESCRIBE` only; an admin can arm write mode through a deliberate
two-step toggle.

To demonstrate schema-level integrity enforcement (such as `ERROR 1062` on duplicate bookings, or
automatic `booking_audit` trigger rows upon status change), statements can also be executed directly
in MySQL Workbench. This proves that integrity constraints are enforced by the database engine
itself, rather than existing solely in application code.

Re-running `db/00_setup.sql` rebuilds and re-seeds the entire database deterministically in seconds.

---

## 12. Verification and deliverables

The system deliverables are structured and validated across two complementary tracks:

1. **RDBMS Track:** Verified through clean execution of `00_setup.sql`, least-privilege enforcement in `db/05_users.sql`, the fingerprinted query catalogue (`docs/QUERIES.md`), data dictionary (`docs/SCHEMA.md`), and the database integrity test log (`docs/TESTS.md`).
2. **Java OOP Track:** Verified through the pure unit suite (`mvn test`, 31 tests without database), database integration suite (`mvn test -Dgroups=db`, 29 tests with connection pool validation), and headless UI suite (`mvn test -Dgroups=ui`, 14 tests driving real JavaFX controls).

---

## 13. Design details to pin down

Things that are decided here so they are not improvised while coding.

### 13.1 Money rules

**Ticket prices**

| Rule | Value | Applied at |
|---|---|---|
| Regular seat | 1.00 × `base_price` | hold |
| Premium seat | 1.25 × `base_price` | hold |
| Recliner seat | 1.60 × `base_price` | hold |

**Invariant for live bookings:**
`booking.ticket_amount = SUM(ticket.price)`, `booking.total_amount = ticket_amount + fnb_amount`,
and, once confirmed, `payment.amount = total_amount`. With no discounts and
per-ticket rounding, those agree to the paisa — which is what keeps query 8 returning
zero rows. Three equalities, one `CHECK` for the second and a query for all three.

No group deals, no promo codes, no seasonal rules. Every one of them would break the
invariant above or need a `discount_amount` column to restore it, and neither is worth
the marks. Concessions are allowed precisely because they are *additive*: a separate
line with its own price, not a percentage off the basket. `ticket_amount` therefore
stays exactly the sum of the tickets, and the refund rule below has one clean base.

**Refunds**

| When the booking is cancelled | Refund |
|---|---|
| More than 2 h before `show_date + start_time` | 90% of `ticket_amount` |
| 2 h or less before the show | 50% of `ticket_amount` |
| After the show has started | 0 — cancellation refused |

The boundary is `show_start − now > 2 h`; exactly 2 h is treated as "inside", because
a customer arriving at the desk two hours before the film is the late case, not the
early one. Rounded to 2 dp with `HALF_UP`.

**Food is not refunded.** `fnb_amount` does not enter the calculation at all — the
customer has the popcorn, and the counter cannot resell it. This is a business rule
with a schema consequence: `chk_refund_amount` caps a refund at `ticket_amount`, not
at the grand total, so the rule is enforced even if `RefundPolicy` is wrong.

The refund rule applies only to `CONFIRMED` bookings. A `HELD` booking that expires
is cancelled with `refund_amount = 0` (nothing was charged) and
`cancelled_by_staff_id = NULL` (a job did it), and a booking cancelled once is never
re-refunded.

Once cancelled, the tickets are deleted, so the invariant above is stated for `HELD`
and `CONFIRMED` rows only — query 8 filters on `status = 'CONFIRMED'` for exactly that
reason. `ticket_amount`, `fnb_amount` and `total_amount` are retained as the figures
the refund was calculated from; `refund_amount`, `cancelled_at` and
`cancelled_by_staff_id` are filled in.

Net revenue everywhere in the reports is `SUM(payment.amount) − SUM(refund_amount)`.

### 13.2 Validation matrix

Both layers validate, for different reasons: the UI gives a usable message, the
database guarantees truth.

| Field | UI rule | Database rule |
|---|---|---|
| Seat selection | sold seats are not clickable | `UNIQUE(showtime_id, seat_id)` |
| Phone | 10–13 digits | `CHECK (CHAR_LENGTH(phone) BETWEEN 10 AND 13)` |
| Email | contains `@`, not blank | `UNIQUE(email)` |
| Showtime price | `> 0` | `CHECK (base_price > 0)` |
| Showtime slot | pre-check for display only | `UNIQUE(screen_id, show_date, start_time)` |
| Status change | only legal buttons shown | procedure rejects non-`HELD` |
| Runtime | 60–240 min | `CHECK` |
| Refund | computed and shown before confirming | `CHECK (0 <= refund_amount <= ticket_amount)` |
| Seller | taken from the session, never typed | `NOT NULL` FK to `staff_user` |
| Canceller | taken from the session, never typed | nullable FK; `NULL` only for an expired hold |
| Concession quantity | 1–20 per line, stepper not free text | `CHECK (quantity BETWEEN 1 AND 20)` |
| Cancelled state | Cancel button does one thing | `CHECK`: `CANCELLED` ⇔ `cancelled_at IS NOT NULL` |

The pre-check for a slot clash exists purely so the user sees a sentence instead of
an error code. The `UNIQUE` key is what actually prevents it.

### 13.3 Failure and concurrency policy

| Situation | Behaviour |
|---|---|
| Duplicate seat | `1062` → `SeatUnavailableException` → refresh map + toast |
| Deadlock (`1213`) | retry once after 50 ms, then surface a retry message |
| MySQL not running (`08S01`) | banner: "MySQL is not running — start the service" |
| Failure mid-checkout | rollback; cart preserved; nothing half-written |
| Expired hold | countdown chip on the seat map; auto-release at 10 min |
| App closed holding seats | nothing runs while the process is gone; the next launch's first sweep releases them within 60 s of starting |
| Stale connection | validate on borrow (`isValid(2)`), reconnect once on `08S01` / `08003`, then `DbUnavailableException` → banner |

The expiry row is deliberately in-process (`HoldExpiryDaemon`): a closed application
cannot release its own holds, and the TTL is not enforced by MySQL while no client is
running. Reopening the app is what sweeps the backlog — the first tick runs immediately
on `start()`, not after a minute. A server-side MySQL `EVENT` would release holds with
every terminal closed, but that trades away the single-process design and needs
`event_scheduler=ON`; it is not part of this build.

Isolation stays at MySQL's default (`REPEATABLE READ`); the explicit row lock in
`sp_confirm_booking` is what makes the critical section safe, not the isolation level.

The stale-connection row is not hypothetical: MySQL's `wait_timeout` is eight hours by
default, so a connection opened in the morning is dead by the afternoon, and the first
query after lunch fails for no reason the user can see. Validating on borrow costs a
round trip on a loopback socket and removes an entire class of "it worked yesterday".

### 13.4 Lab-machine realities

- **Minimum window 1280 × 720.** College lab monitors are often 1366 × 768. The rail
  collapses to icons below 1100 px and the seat map must fit 96 seats without
  scrolling at 1280 wide. Design to that, not to a 27-inch display.
- **Assume no Maven on the lab machine.** The final artifact is a self-contained jar
  with JavaFX natives bundled, launched by a `run.bat`. A launcher class that does
  **not** extend `Application` avoids the "JavaFX runtime components are missing"
  error that catches everyone.
- **Fonts are bundled** in `resources/fonts` with the Archivo OFL licence file, and
  loaded with `Font.loadFont()`. If it returns `null`, fall back to system sans rather
  than rendering nothing.
- **Nothing depends on internet** at demo time.

### 13.5 Performance targets

| Action | Target | How |
|---|---|---|
| Seat map load | < 150 ms | one `LEFT JOIN` query, never one query per seat |
| Schedule load | < 200 ms | single grouped query |
| Booking confirm | < 100 ms | procedure, one round trip |
| Bookings list | < 200 ms | `LIMIT 50` + page offset |
| Reports | < 500 ms | on 300 bookings / 136 showtimes |

No N+1 anywhere. If a table needs a per-row lookup, that is a join, not a loop.

### 13.6 Empty states

| Screen | When empty | Copy |
|---|---|---|
| Schedule | no shows that date | "No shows scheduled for this date. Try another day." |
| Seat map | every seat sold | "House full. Pick another screening." |
| Bookings | no match | "No bookings match that search." |
| Bookings, past show | screening already started | "This screening has already started — cancellation is no longer available." |
| Programme | no films | "No films yet. Add the first one." |
| Programme | no screenings | "No screenings yet. Add one above." |
| Reports | no data in range | "Nothing sold in this period yet." |

An empty screen is an invitation to act, not an apology.

### 13.7 Repo hygiene

`.gitignore` excludes `target/`, Python bytecode (`__pycache__/`, `*.pyc`), real credentials (`app.properties`, `.env`), generated runtime output (`receipts/`, `backup/`, logs), IDE and workspace metadata (`.idea/`, `*.iml`, `.vscode/`, `.freebuff/`), OS system files, and private drafts (`docs/REPORT.md`, `docs/VIVA.md`, `docs/ER.*`, `docs/ERDPlus.md`).

It must **include** `pom.xml`, `db/`, tracked documentation (`docs/PRD.md`, `SCHEMA.md`, `QUERIES.md`, `TESTS.md`, `OOP.md`, `screens/`), `src/`, and `app.properties.example` — ensuring the repository remains clean, secure, and reproducible on any machine.

### 13.8 The Maven project

Pinned here because §13.4 flags JavaFX setup as the single likely thing to eat a day.
Guessing the stack at phase 0 is how you lose an evening.

| Coordinate | Version | Role |
|---|---|---|
| `org.openjfx:javafx-controls`, `javafx-fxml` | 21 | all UI |
| `com.mysql:mysql-connector-j` | 8.x | JDBC driver |
| `org.junit.jupiter:junit-jupiter` | 5.x | tests |
| `maven-surefire-plugin` | 3.x | runs the three test groups from §14 |
| `maven-shade-plugin` | 3.x | self-contained jar for the lab machine |

Dev launch is `mvn javafx:run`. Lab launch is `run.bat` → shaded jar (§13.4), with the
launcher class that does **not** extend `Application`.

Two decisions worth writing down before they become archaeology: unit tests touch
neither JavaFX nor MySQL, so they need no module path and no running server; and the
launcher class is a separate `main` from the `Application` subclass, which is the whole
fix for "JavaFX runtime components are missing".

---

## 14. Testing

### 14.1 The rule that keeps tests honest

**Unit tests must pass with MySQL stopped.** A suite that only runs on the viva machine
is a suite nobody runs. Three groups, three commands:

```bash
mvn test               # unit tests — no database, milliseconds
mvn test -Dgroups=db   # integration tests — MySQL must be running
mvn test -Dgroups=ui   # the sign-in flow through real controls — needs a display
```

JUnit 5, with `@Tag("db")` on anything that opens a connection, `@Tag("ui")` on anything
that starts the JavaFX toolkit, and Surefire's `groups` property to split them. The `ui`
group is kept apart rather than folded into `db` because it fails for a different reason:
a build machine with no display can run every other suite. This one rule is why the distinction between a pure function
and a query matters: the refund rule, the seat multipliers and the state machine are
tested instantly; only `BookingService` waits for a database.

### 14.2 What gets tested

| Class under test | Group | Why it matters | Key assertions |
|---|---|---|---|
| `RefundPolicy` | unit | the 90 / 50 boundary is the easiest thing to get quietly wrong | 3 h out → 90%; 100 min → 50%; exactly 2 h → 50%; show started → refused; **food excluded from the base** |
| `Seat.priceFor` ×3 | unit | three subclasses, one contract | ₹180 base → ₹180 / ₹225 / ₹288; rounds to 2 dp |
| `Booking` state machine | unit | illegal transitions must throw, not mutate | cancelling an already-cancelled booking → `ValidationException` |
| validation rules | unit | cheap and table-driven | phone `12` rejected; duplicate email rejected; quantity `0` rejected |
| `ReceiptWriter` | unit | file I/O without littering the repo | writes into a temp dir; output contains the booking reference; food sits below the seats; every line is ≤ 40 columns |
| `BookingService` | `db` | the only place a transaction is opened | same seat held twice → `SeatUnavailableException`; sweep expired holds → seats freed, live holds untouched; cancel 3 h out → 90% of `ticket_amount`, exactly 2 h out → 50% |
| `HoldExpiryDaemon` | `db` | background hold maintenance | daemon starts and stops cleanly; sweeps expired holds without clerk attribution |
| `ProgrammeService` | `db` | the admin surface and the database's scheduling keys | add / read / update / delete; a film with screenings cannot be deleted; a taken slot returns the screen and time; past slots and short runtimes are refused; an operator cannot read the catalogue |
| `ProgrammeView` | `ui` | the seam between the form and those rules | catalogue and every upcoming screening drawn; **New film** opens and **Cancel** closes the form; a taken slot shows the refusal and adds no row |

The **price snapshot** gets its own test, because it is the invariant every report
depends on: add a line at today's price, change `concession_item.unit_price`, and the
`booking_concession` row must not move. Same test one level up — change
`showtime.base_price` after a hold and `ticket.price` must not move either. A snapshot
that can be disturbed by an unrelated `UPDATE` is just a copy.

Nine areas, 92 tests across the three groups (31 unit, 43 database, 18 UI). Credible,
without becoming the project.

### 14.3 What is deliberately not unit tested

- **UI Views.** Anything worth testing is moved into a service first; visual layout and end-to-end flows are covered by the headless UI test suites (`LoginFlowTest`, `ProgrammeViewTest`).
- **Database constraints.** Constraint enforcement is proved by the database integration tests (`DbSmokeTest`, `BookingServiceTest`, `ScheduleDaoTest`, `ProgrammeServiceTest`) and the SQL integrity test log in `docs/TESTS.md`.
- **Report output.** Verified by running the queries and comparing against the fingerprinted output in `docs/QUERIES.md`.

## 15. Risks

- **JavaFX setup** is the most likely thing to eat a day — modular runtime, fonts,
  packaging. Phase 0 exists to absorb that.
- **Two-window concurrency demo** needs two app instances and two connections; fine,
  but rehearse it once so the demo is not the first attempt.
- **Font loading** fails silently; check `Font.loadFont()` returns non-null.
- **Scope creep in Programme CRUD** is the classic trap. Movies get create, edit and a guarded delete — a film with screenings is refused — and nothing more.
- **A destructive slip during the viva** — mitigated by the rebuild script, but only
  if you have run it once beforehand and know the timings.

---

## 16. Security

The threat model is a box-office terminal on a lab network, next to a MySQL instance
that also holds other people's coursework. Every row below is a control that exists in
the code or the schema — not a policy, and not a promise.

| Threat | Control | Where it lives |
|---|---|---|
| SQL injection | `PreparedStatement` for every statement, including the SQL console. No concatenation in any DAO — not even for an `ORDER BY`: the sort column is looked up in a `Map` of allowed values, so the text box can never reach the query string | `dao/` |
| Privilege escalation | **Role checked in the service layer, not the UI.** Hiding the Programme button is not authorization: `AuthService.requireAdmin()` throws `AuthException`, and every admin entry point calls it before the first query. A hand-built call to `ScheduleService.addShowtime()` from an operator session fails whether or not a button exists | `AuthService.requireAdmin()` |
| Credential theft | Salted SHA-256 per user, salt stored beside the hash. The hash is never selected into a DTO, never logged, and `StaffUser.toString()` prints the username only | `staff_user` |
| Over-privileged app | `cinebook_app` gets DML on `cinebook.*` plus `EXECUTE` on one procedure: no DDL, no `GRANT OPTION`, no `CREATE ROUTINE`, no `TRIGGER` | `db/05_users.sql` |
| Secret leakage | `app.properties` gitignored; `app.properties.example` committed with every value blank | `.gitignore` |
| Information disclosure | No stack traces and no SQL text in the UI. The log line carries the booking id and the MySQL error code — never the customer's name, phone or email | `FxTasks` |
| Destructive keystroke | The SQL console is read-only by default (`SELECT`, `SHOW`, `EXPLAIN`, `DESCRIBE`); DML happens in Workbench, deliberately | `SqlConsoleController` |
| Card data | Never stored and never asked for. `payment` holds method, amount and `txn_ref` — there is no column a card number could sit in, so there is nothing to leak | schema |
| MySQL exposure | Bound to loopback for the demo; documented as a host-level recommendation below, **not performed by this project** | note below |

Two controls that do not fit in a table row:

- **Session timeout.** Fifteen minutes idle returns to the login screen and clears the
  in-memory session, including the staff id that would otherwise be stamped on the next
  booking. A `PauseTransition` reset by any key or click, not a wall-clock check on the
  FX thread.
- **Codes, not text.** `AuthException` says "Wrong username or password" for both an
  unknown user and a bad password. Distinguishing them is an account-enumeration oracle,
  and not providing one costs nothing.

**The honest limitation.** One shared database user means the trail identifies the
*terminal*, not the person. `sold_by_staff_id` and `cancelled_by_staff_id` record who
was logged in, which is enough for the shift report and for a viva — but a clerk using
another clerk's open session is indistinguishable from that clerk. Per-user MySQL
accounts (`cinebook_<username>`, one per staff row) are the real fix, and they are out
of scope on purpose: they multiply the DCL by the number of staff and make the demo
depend on creating accounts before it can sell a ticket.

**Note on the MySQL listener.** `bind-address` lives in `my.ini`, and changing it is a
**host-level recommendation, not something this project does**: bind MySQL to
`127.0.0.1`, and let the firewall block 3306 inbound. It is written down because "is
this safe on the college network?" deserves a real answer, and left undone because a
project that edits the machine's MySQL configuration to score a mark is a project that
breaks someone else's setup. If asked, say which of those two it is.

---

## 17. Data maintenance

The viva line, said before the question is asked: **the database does not maintain
itself — it *enforces* itself.** Integrity here is not a job that runs at 2 a.m. It is
`UNIQUE`, `CHECK`, the FK actions and one trigger, all of which act whatever the client
does. Occupancy is derived from tickets, so occupancy and tickets cannot disagree.
Everything below is about the data *around* those rules: keeping it, resetting it, and
letting it grow without quietly degrading.

### Backup and restore

`mysqldump` ships with the server that is already installed, so a backup is one command
and nothing new on the machine:

```bash
mysqldump -u root -p --single-transaction --routines --triggers \
          cinebook > backup/cinebook-$(date +%Y%m%d-%H%M).sql
```

`--single-transaction` takes a consistent InnoDB snapshot without locking the tables, so
a backup during the demo does not freeze the app. `--routines --triggers` matters more:
without them the dump restores the tables and loses the procedure and the audit trigger
— the worst possible half-restore, where the data is right and the behaviour is gone.
`backup/` is gitignored; a dump in the repository is a database leaking into a handout.

Restore into a scratch database to verify backup integrity:

```bash
mysql -u root -p -e "CREATE DATABASE cinebook_restore"
mysql -u root -p cinebook_restore < backup/cinebook-2026-10-18-0930.sql
```

`--single-transaction` reads `cinebook` under a consistent snapshot and writes a file,
changing nothing.

### Database Reset

`00_setup.sql` is the zero-state rebuild: drops and recreates the database, re-seeds
deterministically, and rebuilds both views, the trigger, and the procedure.

`00_setup.sql` is also the only thing that should ever run as root. It is assembled from
`01`–`04` in order by `tools/buildsetup.py`, and `--check` exits 1 the moment it drifts
from its parts. If the assembled file and its four parts ever disagree, the assembled
file is the one that is wrong.

### Growth and retention

Data grows in four places, and only one of them needs a policy:

| Grows with | Storage | Retention |
|---|---|---|
| `booking` — every sale | one row | keep; it is the business record |
| `ticket` — seats per sale | one row per seat, deleted on cancel | keep |
| `booking_audit` — **every status transition** | one row per transition, two for a confirmed-then-cancelled booking | `DELETE FROM booking_audit WHERE changed_at < NOW() - INTERVAL 1 YEAR` |
| `receipts/` — one printed receipt | one `.txt` | disposable; regenerate by re-printing |

`booking_audit` is the one that surprises people. At 300 bookings it is invisible; at
300 a day it is 100k rows a year of history nobody reads once the term ends. The
retention rule is a single `DELETE`, and it is safe because the trigger writes the
current row on every change — pruning the old ones removes forensics, not truth.

`receipts/` is output, not data: gitignored, regenerated on demand, and the one
directory in the project that can be deleted without a second thought.

### Indexes

A foreign key indexes the **child** side automatically; the parent side uses its primary
key. So `ticket(booking_id)`, `booking(customer_id)` and `booking(showtime_id)` need no
help from anyone. The indexes worth defending are the ones the reports actually use:

| Index | Why it exists |
|---|---|
| `idx_showtime_date` | every schedule and dashboard query filters on `show_date` |
| `idx_booking_status` | the expiry job scans `HELD`; reports filter `CONFIRMED` |
| `idx_payment_paid` | the shift report filters `DATE(paid_at) = CURDATE()` |
| `idx_booking_sold_by`, `idx_booking_cancelled_by` | per-clerk reconciliation joins on both |
| *none needed on `ticket`* | `UNIQUE (showtime_id, seat_id)` already starts with `showtime_id`, so the occupancy view's `LEFT JOIN` uses that index. A separate `idx_ticket_show` would be a second copy of the same B-tree, rebuilt on every insert |

Checked with `EXPLAIN`, not assumed: `EXPLAIN SELECT … WHERE show_date = CURDATE()`
reports `key = idx_showtime_date`, `type = ref`, 4 estimated rows — the predicate alone
does use the index at demo scale. Inside the pattern-1 join MySQL drives from `screen`
instead, which is a cost decision and a different question. Both query execution plans are documented in `db/explain-index.sql`.

### No migration framework

Schema changes are re-run scripts, not versioned migrations. That is a limitation and it
is named as one: there is no `V1__init.sql`, no Flyway, no Maven plugin, because for a
project whose whole lifetime is one term and whose reset is a `DROP DATABASE`, a
migration framework is infrastructure pretending to be rigour. Flyway is the right
answer for the version that outlives the course, so it belongs in future work rather
than half-adopted here.

The practical consequence is a clean workflow: change `01_schema.sql`, re-run
`00_setup.sql`, and verify the integrity test suite. No data survives the rebuild — which
is exactly why the dump above exists.

### MySQL housekeeping

Binary logs, `innodb_file_per_table` fragmentation, `OPTIMIZE TABLE`, version upgrades:
none of it applies at twelve tables and a few thousand rows. Say that plainly instead of
pretending to tune a database that fits in the buffer pool. The one server setting that
does matter is `wait_timeout`, and it is handled in the application (§13.3), not in the
server configuration.
