# CineBook — Cinema Ticketing Management System

A high-performance box-office Point-of-Sale (POS) and ticketing system engineered for multi-screen cinema multiplexes, combining an ACID-compliant MySQL 8.0 relational backend with a responsive, modern JavaFX 21 desktop application.

---

## Overview

**CineBook** is a desktop box-office client built to streamline counter operations in busy cinemas. It allows clerks to browse screening schedules, select tiered seats on dynamic auditorium maps, bundle food and beverage concessions, process payments, and issue thermal receipts—all backed by strict concurrency controls and automated audit trails.

Designed to eliminate common ticketing bottlenecks, CineBook guarantees zero seat double-booking, automatically clears abandoned holds, and provides managers with real-time shift reconciliation reports.

---

## Key Features

- **Double-Booking Prevention (Storage Layer)**  
  Guarantees zero seat collisions using a composite `UNIQUE (showtime_id, seat_id)` constraint on ticket claims, combined with `SELECT ... FOR UPDATE` row locks inside the `sp_confirm_booking` stored procedure. Exactly one clerk can win a contested seat, even under simultaneous high-frequency clicks.

- **10-Minute Hold TTL & Background Sweeper**  
  Holds lock seats temporarily for payment. An in-app countdown chip (`⏱ 10:00`) alerts the clerk, while an asynchronous daemon (`HoldExpiryDaemon`) runs every 60 seconds to automatically release abandoned seats and concessions back to inventory with zero shift misattribution (`cancelled_by = NULL`).

- **Dynamic Auditorium Seat Maps**  
  Seating grids are generated at runtime directly from physical screen and row data. Supports multi-tier pricing with automatic multiplier calculation: **Regular (1.00×)**, **Premium (1.25×)**, and luxury **Recliner (1.60×)**.

- **Fast Concession Upselling & Snapshotted Pricing**  
  Add snacks and drinks directly to tickets in a single checkout flow. Item prices and ticket amounts are snapshotted on the booking row at the moment of sale, ensuring future catalog price changes never alter historical till receipts.

- **Refund-Aware Cancellations & Shift Auditing**  
  Refunds follow the 90 / 50 ticket rule; database check constraints cap them at the ticket amount and keep food non-refundable:
  - **Early (> 2 hrs before show)**: 90% ticket refund.
  - **Late (≤ 2 hrs before show)**: 50% ticket refund.
  - **Started Screening**: Cancellation locked. Food & concessions are non-refundable.  
  Every sale and cancellation records both the selling clerk (`sold_by`) and refunding clerk (`cancelled_by`) for accurate end-of-day Z-reports.

- **Thermal Receipt Generator**  
  Writes 40-column plain-text thermal slips (`receipts/BK-XXXX.txt`) with itemized ticket and food breakdowns, the payment method, and the transaction reference.

---

## Architecture at a Glance

CineBook strictly separates concerns across four decoupled layers:

```
┌────────────────────────────────────────────────────────┐
│  Presentation Layer (JavaFX 21 · com.cinebook.ui)       │
│  Programmatic views, CSS styles, FxTasks async work    │
└──────────────────────────┬─────────────────────────────┘
                           │ calls
┌──────────────────────────▼─────────────────────────────┐
│  Service Layer (com.cinebook.service)                  │
│  Transaction boundary (commit/rollback), daemons, rules│
└──────────────────────────┬─────────────────────────────┘
                           │ calls
┌──────────────────────────▼─────────────────────────────┐
│  Data Access Layer (com.cinebook.dao)                  │
│  Stateless JDBC DAOs, parameterized PreparedStatements │
└──────────────────────────┬─────────────────────────────┘
                           │ queries
┌──────────────────────────▼─────────────────────────────┐
│  Database (MySQL 8.0 InnoDB · cinebook)                │
│  12 tables, constraints, views, audit triggers, procs  │
└────────────────────────────────────────────────────────┘
```

- **UI (`com.cinebook.ui`)**: Paper-and-house-lights styling with zero SQL or database dependencies. Long queries run off the JavaFX thread via `FxTasks.run` so the window never freezes.
- **Service (`com.cinebook.service`)**: Owns transactions, business rules, and background tasks (`AuthService`, `BookingService`, `CheckoutService`, `HoldExpiryDaemon`).
- **DAO (`com.cinebook.dao`)**: Parameterized PreparedStatements eliminate SQL injection risks.
- **Database (`MySQL 8.0`)**: 12 normalized tables in 3NF/BCNF, foreign key cascades/restricts, integrity triggers, and reporting views.

---

### Core Technical Disciplines

| Domain | Architecture & Scope | Implementation |
|---|---|---|
| **Relational Storage** | Relational schema, 3NF normalization, integrity constraints, ACID transactions, and analytical reporting | `db/` (MySQL 8.0 / InnoDB) |
| **Object-Oriented Desktop** | Layered architecture, GoF design patterns, concurrent background daemons, and JavaFX UI | `src/` (Java 21 / Maven) |

Database: **MySQL 8.0** (already installed on this machine) · Client: **MySQL Workbench**

---

## Running it

1. Open MySQL Workbench and connect to your local instance.
2. `File → Open SQL Script…` → select **`db/00_setup.sql`**.
3. Press the lightning bolt (⚡) to execute the entire script.
4. Refresh the schema sidebar — a database called **`cinebook`** appears with 12 tables
   and 2 views.
5. When the seed finishes, partway through the script, a sanity-check grid prints row
   counts. Expect:

```
movie 8 | screen 4 | seat 384 | showtime 136 | customer 21 | staff_user 2
concession_item 8 | booking 300 | ticket 284 | booking_concession 100
booking_audit 24 | payment 252
```

If your numbers match, the database is complete and consistent.

6. If you want the output pasted in `docs/QUERIES.md` to describe *your* database
   rather than the one it shipped with, refresh the capture:

```bash
MYSQL_PWD=... python tools/querydoc.py --capture
```

The seed is anchored to `CURDATE()`, so a rebuild on a later date shifts every printed
calendar date. Counts, revenue, rankings and `days_dormant` stay identical — only the
dates move — but the capture is dated, and refreshing it is what keeps the catalogue
honest.

Then, **once**, as root, create the account the application connects with:

```bash
mysql -u root -p < db/05_users.sql      # edit CHANGE_ME first
```

That password must match `jdbc.password` in `app.properties` in the project root, which
is gitignored. Copy `app.properties.example` to `app.properties` and fill it in; the
same file is used by `mvn javafx:run`, by the packaged jar and by `run.bat`. Expect
three grant lines: `USAGE ON *.*` (MySQL's "no rights here" placeholder), DML on
`cinebook.*`, and `EXECUTE` on `sp_confirm_booking` — nothing else. The account is
tested against DDL, other schemas and `GRANT`; the transcript is in
[docs/PRD.md §10.3](docs/PRD.md#103-privileges-db05_userssql).

> ⚠️ `00_setup.sql` starts with `DROP DATABASE IF EXISTS cinebook`. It is meant to be re-run whenever you want a clean rebuild — never point it at a database you care about. It does not touch the application's MySQL account, which is why `05_users.sql` is separate.

### Running a single section

The same script is also split into five files, in execution order:

| File | Contents |
|---|---|
| `db/01_schema.sql` | 12 tables, keys, `CHECK` constraints, FK actions, 5 secondary indexes |
| `db/02_seed.sql` | Deterministic sample data (no `RAND()`, so output is repeatable) |
| `db/03_objects.sql` | 2 views, 1 audit trigger, 1 stored procedure |
| `db/04_queries.sql` | 14 numbered report queries (13 with a result set) + the transaction drill to run by hand |
| `db/05_users.sql` | The `cinebook_app` login and its grants (DCL, run as root, once) |

`db/00_setup.sql` is generated: it is `01` + `02` + `03` + `04` concatenated, with
nothing edited. Change a part and rebuild it — do not hand-edit the bundle:

```bash
python tools/buildsetup.py          # rebuild
python tools/buildsetup.py --check  # exit 1 if it has drifted from its parts
```

Skipping that step can cause deployment drift between the bundle and individual migration files.

`db/explain-index.sql` is the evidence file, and it is **not** part of that order. It is
read-only: it reprints both `EXPLAIN` plans for the date query, then the same queries
under `EXPLAIN ANALYZE` for measured milliseconds, on a 200 000-row `TEMPORARY` copy of
`showtime` that disappears when the session closes. Run it whenever the index claim is
verified — the output it prints provides measured query execution times and query optimization plans.

`db/viva-queries.sql` is the verification script — 21 self-contained interactive
query blocks for live walkthroughs, concurrency drills, and testing constraint refusals.

### Building and testing

Needs **JDK 21** and **Maven 3.9+** on the `PATH`, with `JAVA_HOME` pointing at the JDK.
No JavaFX install is required: the dependencies come from Maven.

```bash
mvn -q test               # unit tests — no database, no server
mvn test -Dgroups=db      # integration tests — MySQL must be running
mvn test -Dgroups=ui      # UI flows through the real controls — needs a display
```

`mvn test` runs 42 unit tests in well under a second and opens no socket: the refund
boundary at ±1 minute, the three seat multipliers, the booking state machine, the price
snapshots, the contact rules, the 40-column receipt, the SQL console's read-only guard and
the report registry's tab/preset parity. They pass with MySQL stopped, which is the only
way they stay useful ([docs/PRD.md §14.1](docs/PRD.md#141-the-rule-that-keeps-tests-honest)).

`mvn test -Dgroups=db` runs 54 integration tests and does need a running server:
the config file, the `cinebook_app` login, the seeded 300 bookings, the
`BigDecimal`/`LocalDate`/`LocalTime` mapping and the `serverTimezone` pin, the schedule
and seat DAOs — row order, the 96-seat auditorium, and a cross-check that
`ShowtimeDao`'s sold count equals `SeatDao`'s sold labels — seven sign-in cases
against the seeded hashes, both hold paths, the release transaction that cancels a
hold and deletes its tickets, the three-connection handoff including its refusal of a
nested borrow, the four checkout cases (payment row and receipt totals, price snapshot,
refused second payment, replacing walk-in with named customer), plus hold-expiry sweeps,
refund-aware cancellations (90% early vs 50% late), refused cancellations for past screenings,
bookings search and audit projections, hold-expiry daemon lifecycle, and the Programme
service's film CRUD, guarded delete, screening insert, double-booked slot message and
admin gate. The Reports service checks the core business reporting queries against the seed, the
console's read-only guard, and the shift summary against a direct aggregate of the same
clerk's payments and refunds. The pom excludes that group by default, so the quick suite
never waits on MySQL.

`mvn test -Dgroups=ui` drives `LoginView` the way a person does: type into the two
fields, press **Sign in**, read what the window says next. It proves the seam the unit
tests cannot see — that the button reaches `AuthService`, that a refusal lands in the
message label with the password cleared, that an unknown user reads exactly the same
sentence as a wrong password, and that the rail an operator gets has no admin sections
while the admin's does, that the schedule offers a panel for every show date, that
pressing one lists only that day's screenings, that pressing a screening opens its
seat map with the same sold count the schedule shows, that holding seats opens the
checkout panel whose stepper moves the total and whose payment footer stays on screen
while the panel scrolls, that **Print** writes a receipt file whose every line fits the
printer, that **Release seats** closes the panel and hands the seat back — and is
refused in the footer when another till already paid for that hold, leaving every
control usable — that paying shows a slip read back from the database, and that the
Bookings screen searches the whole register by printed reference and cancels a paid
booking with a refund, that the Programme screen draws the catalogue and every upcoming
screening, opens the film form on **New film**, closes it on **Cancel**, and turns a
taken slot into the screen-and-time refusal without adding a row, and that the Reports
screen opens from the admin rail, switches to occupancy, loads a console preset, runs a
read query, refuses a DELETE without leaving rows behind, and opens and closes the shift
summary. Twenty-eight tests, about twenty seconds, a display required.

### Running the app

```bash
mvn javafx:run
```

### Standalone Application Packaging

Build the self-contained jar once on a machine with Maven:

```bash
mvn clean package
```

The result is `target/cinebook-1.0.0.jar` — JavaFX, its native DLLs, the MySQL driver,
the bundled Archivo fonts and every resource inside one file. On target client machines only
**Java 21 or newer** and the project folder are needed; Maven and an internet connection
are not.

1. Copy `app.properties.example` to `app.properties` next to `run.bat` and fill in
   `jdbc.password`.
2. Double-click **`run.bat`**.

The jar deliberately contains no `app.properties`: the password stays in the local file
and is never baked into an artifact you hand in. `run.bat` checks for the jar, the config
and Java 21 before starting, and returns a readable message instead of a silent failure.
The packaged build has [its own demo script](docs/DEMO.md).

Sign in with one of the two seeded accounts: `operator / operator123` (lands on Schedule) or
`admin / admin123` (lands on Reports, with Schedule, Bookings and Programme in the rail). Those are
the passwords whose hashes `db/02_seed.sql` stores — the app never knows them in plain text.

Pick a date panel, then press any screening to open the seat map: an ivory sheet with a
turquoise screen, one ruled band per class, cream seats and armchairs numbered in
place, and sold seats faded behind an ×. Clicking free seats turns them green and adds
them to the pinned booking bar — seats, class and rupee total. **Confirm** holds every
pick in one transaction under the seeded walk-in guest: the seats darken and the bar
reads `Held #id`. If another clerk took a seat first, the map reloads and names it.
A sold seat says why. Back returns to the same day.

The hold opens the **checkout panel** on the right, without losing the seats: the held
seats with their class totals, a customer line that starts at the walk-in guest (search
by phone or name, or add someone inline), eight concession tiles — each with a ±stepper
and a count badge — and a payment footer pinned below the scroll, so the method chips,
the running total and **Confirm payment** never leave the screen. **Release seats** gives
an unpaid hold back to the map in one transaction. **Confirm payment** calls
`sp_confirm_booking`, which recomputes both totals from the persisted lines and writes
the `payment` row; the panel then swaps to the receipt stub it read back from those
rows, sliding it up as the form goes. **Print receipt** writes `receipts/BK-0042.txt` as
40-column thermal text, and **Open receipt** hands it to the desktop (falling back to
`~/CineBook/receipts` if the working directory is not writable). A 25% premium or a menu
price change after the sale cannot restate it: tickets and food carry their own snapshot.

As admin, **Programme** is the two-page repertory sheet: the film catalogue on the left
with **New film**, **Edit** and a delete that refuses any film that still has screenings;
the screening builder on the right with film, auditorium, date, time and base price. A
taken slot is rejected by `UNIQUE (screen_id, show_date, start_time)` and reads
"Audi 2 already has a show at 19:00." Under the builder, the day book lists every
upcoming screening grouped by date with seats sold against capacity.

**Reports** opens on Revenue by screen: bookings and money per auditorium over the last
30 days, with ticket and food money kept apart. **Top films** is the top three by total
take, **Dormant films** is the six-month absence list, **Occupancy** is the ten fullest
remaining screenings, and **Loyal customers** is the HAVING report. The **SQL
console** loads any of the thirteen preset queries into a monospaced editor; F5 or
**Run query** returns a grid whose columns come from the result, and the console refuses
anything outside SELECT, SHOW, EXPLAIN and DESCRIBE. **Shift summary** sits beside the
tabs, not in them: it opens the signed-in clerk's Z-report — bookings, ticket and food
take, the payment split, and refunds counted against the clerk who cancelled.

The window needs 1280 × 720 at minimum. Renders of the current build are in
`docs/screens/` — one for every screen: `ui-login.png`, `ui-shell-operator.png`
(Schedule), `ui-shell-admin.png` (the admin's Reports landing), `ui-seatmap.png`,
`ui-checkout.png` (the held seats and the food grid), `ui-checkout-payment.png` (the
payment row), `ui-receipt.png` (the paid stub), `ui-bookings.png` and
`ui-programme.png`. The six-minute run-through is in [docs/DEMO.md](docs/DEMO.md).

### Printing the schedule without a UI

Phase 1's check, and the numbers it prints must match `db/04_queries.sql` query 1:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes;$(cat target/cp.txt)" com.cinebook.cli.ScheduleReport 8
```

It lists the next screenings with film, screen, price and seats sold, then builds the
first screening's seat map from the `seat` rows to show the map is built from data, not
from a hardcoded 8 × 12 grid. On Windows use `;` between classpath entries; on
macOS/Linux use `:`.

### Logging into the app

| Username | Password | Role |
|---|---|---|
| `admin` | `admin123` | ADMIN — programme, reports and shift summary |
| `operator` | `operator123` | OPERATOR — sell and cancel |

Demo credentials, hashed with a per-user salt in the seed. The seed file contains the
hashes, not the passwords.

---

## The 12 tables

```
movie ──< showtime >── screen ──< seat
             │                     │
             │                     │
             └──< booking >────────┴──< ticket >──┘
                    │  │
                    │  └──< booking_concession >──< concession_item
                    │
                    ├──< payment
                    ├──< booking_audit
                    └──< staff_user   (sold_by, cancelled_by)
```

- **`ticket`** is the many-to-many bridge between `booking` and `seat`.
- **`booking_concession`** is the second many-to-many: `booking` ↔ `concession_item`,
  carrying `quantity` and a price snapshot. Two bridge tables, each with attributes of
  its own — that is what makes them entities rather than links.
- **`UNIQUE (showtime_id, seat_id)`** on `ticket` makes double-booking a seat
  impossible at the storage layer, even if the Java code has a bug.
- **`UNIQUE (screen_id, show_date, start_time)`** on `showtime` prevents two films
  starting in the same auditorium at the same minute.
- **`CHECK (total_amount = ticket_amount + fnb_amount)`** on `booking` is why the three
  money columns are always written in one statement.

---

## ER diagram

The entity-relationship diagram is authored in **ERDPlus** in Crow's-foot notation directly matching `db/01_schema.sql` (12 entities, exact candidate keys, foreign key constraints, and cardinalities).

`docs/SCHEMA.md` is the data dictionary — every table, column, type, key and constraint.
It is generated from the same file, so it cannot drift from the SQL:

```bash
python tools/schemadoc.py          # regenerate
python tools/schemadoc.py --check  # verify it is current (exit 1 if stale)
```

It parses every `CREATE TABLE`, resolves every foreign key and every `CHECK`
identifier, and refuses to write if any item cannot be classified.

`docs/QUERIES.md` (query catalogue) and `docs/TESTS.md` (integrity test log) are
generated from the SQL they document, so they cannot drift from it either:

```bash
python tools/querydoc.py          # regenerate both
python tools/querydoc.py --check  # verify they are current (exit 1 if stale)
```

The output pasted under each query in `QUERIES.md` comes from a real run. `--capture`
executes every query against the seeded `cinebook` database and records what MySQL
printed into `docs/query_output.json`, which the generator reads back:

```bash
MYSQL_PWD=... python tools/querydoc.py --capture
```

Each capture stores a fingerprint of the statement it belongs to, so editing a query
without recapturing fails the gate with `recorded output is stale` rather than pasting
rows that belong to a different query. `MYSQL_BIN`, `MYSQL_HOST`, `MYSQL_PORT` and
`MYSQL_USER` override the client and connection (defaults: `mysql`, `127.0.0.1`, `3306`,
`root`).

To ask whether a capture still matches the database without changing anything:

```bash
MYSQL_PWD=... python tools/querydoc.py --check-db
```

```text
ADVISORY  capture is 9 day(s) old: recorded 2026-10-11, server says 2026-10-20 - refresh with: python tools/querydoc.py --capture
ADVISORY  all 13 recorded result sets match this database
```

This one is **advisory and always exits 0** — it re-runs the queries, names any whose
rows have changed, and skips cleanly when there is no server or no `MYSQL_PWD`. This
check runs as a non-breaking diagnostic; only `--check` fails the build on an actual
schema drift or data inconsistency.

`db/00_setup.sql` is the one-shot script (01 + 02 + 03 + 04 in order). It is
built, not edited:

```bash
python tools/buildsetup.py          # rebuild the bundle
python tools/buildsetup.py --check  # verify it matches its four parts
```

`tools/querydoc.py` refuses to document anything the bundle does not contain, so a
stale `00_setup.sql` fails the docs build rather than shipping silently.

No MySQL needed to catch the mistakes MySQL would reject. `tools/sqlcheck.py`
resolves every alias and column in the queries against the schema (views and
derived tables included) and checks the seed's data and arithmetic:

```bash
python tools/sqlcheck.py            # columns + seed; exit 1 on a problem
python tools/sqlcheck.py --queries  # only the query catalogue
python tools/sqlcheck.py --seed     # only the seed
```

It reports what the server would report as `ERROR 1054` (unknown column),
`ERROR 1052` (ambiguous column) or `ERROR 1452` (unresolved foreign key), and
it checks the seed's money identity — `total_amount = ticket_amount +
fnb_amount`, refunds never above `ticket_amount`, payments taken from
`booking.total_amount` — plus `ENUM` values, simple `CHECK` ranges, `UNIQUE`
keys, seats-per-screen against `seat_capacity`, and the documented staff
passwords re-hashed from their salts.

---

## Business & Analytical Queries

The database ships with 14 production queries covering operational ticketing, revenue analytics,
and audit reconciliation:

| Category | Query in `04_queries.sql` |
|---|---|
| Active Inventory | 1 — upcoming screenings with remaining seat availability |
| Category Breakdown | 2 — bookings and revenue by auditorium (`GROUP BY`) |
| Multi-Entity Detail | 3 — itemized sale details (booking + customer + movie + seat + clerk) |
| Dormancy Analysis | 4 — films with no screenings in the past 6 months (`LEFT JOIN … IS NULL`) |
| Leaderboards | 5 — highest grossing films by ticket sales (`ORDER BY … LIMIT 3`) |
| Advanced Analytics | 6 `HAVING` threshold filter, 7 `NOT EXISTS` unbooked seats, 8 three-way money reconciliation, 9 ranking window functions, 10 reporting view, 11 customer dormancy |
| Non-Inflated Aggregation | 12 — ticket vs F&B split without Cartesian join fan-out |
| Daily Till Reconciliation | 13 — shift Z-report: collected vs refunded per clerk |

---

## The documents

| File | What it is |
|---|---|
| `docs/PRD.md` | System specification, domain rules, and architecture contract |
| `docs/SCHEMA.md` | Data dictionary, generated from `db/01_schema.sql` |
| `docs/QUERIES.md` | Query catalogue with fingerprinted MySQL execution output |
| `docs/TESTS.md` | Integrity test log proving constraint and trigger refusals |
| `docs/OOP.md` | Class model, layering rules, and OOP concept coverage |
| `docs/class-diagram-*.svg` | Architectural class diagrams (domain model and layer boundaries) |
| `docs/DEMO.md` | Step-by-step interactive demonstration walkthrough and operational runbook |
| `db/viva-queries.sql` | 21 self-contained verification queries for database inspections and constraint testing |
| `tools/sqlcheck.py` | Offline proof that the queries and seed match the schema |

