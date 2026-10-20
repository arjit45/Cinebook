# CineBook — Cinema Ticketing Management System

A single project submitted for two subjects:

| Subject | What it is graded on | Where it lives |
|---|---|---|
| **RDBMS** | ER diagram, schema, constraints, queries, live viva | `db/` |
| **OOP in Java** | UI/UX, class design, patterns | `src/` |

Database: **MySQL 8.0** (already installed on this machine) · Client: **MySQL Workbench**

---

## Running it

1. Open MySQL Workbench and connect to your local instance.
2. `File → Open SQL Script…` → select **`db/00_setup.sql`**.
3. Press the lightning bolt (⚡) to execute the entire script.
4. Refresh the schema sidebar — a database called **`cinebook`** appears with 12 tables
   and 2 views.
5. At the bottom of the run, a sanity-check grid prints row counts. Expect:

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

That password must match `jdbc.password` in `src/main/resources/app.properties`, which
is gitignored. Copy `app.properties.example` to `app.properties` and fill it in. Expect
three grant lines: `USAGE ON *.*` (MySQL's "no rights here" placeholder), DML on
`cinebook.*`, and `EXECUTE` on `sp_confirm_booking` — nothing else. The account is
tested against DDL, other schemas and `GRANT`; the transcript is in
[docs/PRD.md §10.3](docs/PRD.md#L593).

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

Skipping that step is how the submission ends up carrying two versions of the schema,
with the wrong one winning.

`db/explain-index.sql` is the evidence file, and it is **not** part of that order. It is
read-only: it reprints both `EXPLAIN` plans for the date query, then the same queries
under `EXPLAIN ANALYZE` for measured milliseconds, on a 200 000-row `TEMPORARY` copy of
`showtime` that disappears when the session closes. Run it whenever the index claim is
challenged — the output it prints is what the course report index analysis records.

`db/viva-queries.sql` is the presentation file — 21 self-contained blocks to run in
Workbench during the viva, including the deliberate failures.

### Building and testing

Needs **JDK 21** and **Maven 3.9+** on the `PATH`, with `JAVA_HOME` pointing at the JDK.
No JavaFX install is required: the dependencies come from Maven.

```bash
mvn -q test               # unit tests — no database, no server
mvn test -Dgroups=db      # integration tests — MySQL must be running
mvn test -Dgroups=ui      # sign-in flow through the real controls — needs a display
```

`mvn test` runs 31 unit tests in well under a second and opens no socket: the refund
boundary at ±1 minute, the three seat multipliers, the booking state machine, the price
snapshots, the contact rules and the 40-column receipt. They pass with MySQL stopped, which is
the only way they stay useful ([docs/PRD.md §14.1](docs/PRD.md#L972)).

`mvn test -Dgroups=db` runs 34 integration tests and does need a running server:
the config file, the `cinebook_app` login, the seeded 300 bookings, the
`BigDecimal`/`LocalDate`/`LocalTime` mapping and the `serverTimezone` pin, the schedule
and seat DAOs — row order, the 96-seat auditorium, and a cross-check that
`ShowtimeDao`'s sold count equals `SeatDao`'s sold labels — seven sign-in cases
against the seeded hashes, both hold paths, the release transaction that cancels a
hold and deletes its tickets, the three-connection handoff including its refusal of a
nested borrow, the four checkout cases (payment row and receipt totals, price snapshot,
refused second payment, replacing walk-in with named customer), plus hold-expiry sweeps,
refund-aware cancellations (90% early vs 50% late), refused cancellations for past screenings,
bookings search and audit projections, and hold-expiry daemon lifecycle. The pom excludes
that group by default, so the quick suite never waits on MySQL.

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
control usable — and that paying shows a slip read back from the database. Fourteen
tests, about eight seconds, a display required.

### Running the app

```bash
mvn javafx:run
```

Sign in with one of the two seeded accounts: `operator / operator123` (lands on Schedule) or
`admin / admin123` (same schedule, plus Bookings, Programme and Reports in the rail). Those are the
passwords whose hashes `db/02_seed.sql` stores — the app never knows them in plain text.

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

The window needs 1280 × 720 at minimum. Renders of the current build are in
`docs/screens/`: `ui-login.png`, `ui-shell-operator.png`, `ui-seatmap.png`,
`ui-checkout.png` (the held seats and the food grid), `ui-checkout-payment.png` (the
payment row) and `ui-receipt.png` (the paid stub), plus `ui-shell-admin.png`.

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

### Logging into the app (once `src/` exists)

| Username | Password | Role |
|---|---|---|
| `admin` | `admin123` | ADMIN — programme and reports |
| `operator` | `operator123` | OPERATOR — sell, cancel, close the shift |

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
rows have changed, and skips cleanly when there is no server or no `MYSQL_PWD`. A
submission check that runs it can never fail because a snapshot is a few days old;
only `--check` is allowed to fail, and only on a real inconsistency.

`db/00_setup.sql` is the one-shot script (01 + 02 + 03 + 04 in order). It is
built, not edited:

```bash
python tools/buildsetup.py          # rebuild the bundle
python tools/buildsetup.py --check  # verify it matches its four parts
```

`querydoc.py` refuses to document anything the bundle does not contain, so a
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

## Query → rubric mapping

Your lab sheet uses five recurring patterns across all 39 projects. All five are
answered here:

| Pattern | Query in `04_queries.sql` |
|---|---|
| List all X, sorted | 1 — upcoming screenings with seats left |
| Count per category | 2 — bookings and money per screen (`GROUP BY`) |
| 3-table detail join | 3 — booking + customer + movie + seat + seller |
| No activity in last N months | 4 — films not screened in 6 months (`LEFT JOIN … IS NULL`) |
| Top 3 by aggregate | 5 — highest grossing films (`ORDER BY … LIMIT 3`) |
| *Examiner follow-up* | 6 `HAVING`, 7 `NOT EXISTS`, 8 three-way money reconciliation, 9 window function, 10 view, 11 `LEFT JOIN … IS NULL` |
| *Food & beverage* | 12 — ticket versus F&B revenue per film, without a fan-out |
| *Shift close* | 13 — the Z-report: taken and refunded, per clerk, per day |

---

## Talking points for the viva

1. **Why `showtime`, not `show`?** `SHOW` is a reserved word in MySQL. Naming a table `show` forces backticks everywhere.
2. **Why is `ticket.price` stored when `showtime.base_price` already exists?** It is a deliberate snapshot. Prices change; historical revenue must not retroactively change with them. This is a *justified* denormalization, not a mistake — and `booking_concession.unit_price`, `booking.ticket_amount` and `booking.fnb_amount` are the same decision in three more places.
3. **Why does `ticket` carry `showtime_id`?** Denormalized on purpose so the `UNIQUE (showtime_id, seat_id)` key can enforce one ticket per seat per screening. Without it, the constraint would need a join, which a `UNIQUE` key cannot express.
4. **How is double-booking prevented?** Two layers — the `UNIQUE` key (storage) and `SELECT … FOR UPDATE` inside `sp_confirm_booking` (concurrency).
5. **Cancellation frees the seat** because the `ticket` rows are deleted, releasing the `UNIQUE` key — not because a status flag is flipped. The seat comes back; `ticket_amount` stays, because the refund was calculated from it.
6. **Is the popcorn refunded?** No. The refund is a percentage of `ticket_amount`, and `chk_refund_amount` refuses anything larger — the rule is in the schema, not only in the Java.
7. **Why two columns for staff?** `sold_by_staff_id` and `cancelled_by_staff_id`. A refund charged to whoever sold the ticket makes per-clerk reconciliation wrong on exactly the day it matters.
8. **Where is the walk-in customer?** Row 21, `Counter Guest`. Not `customer_id = 1` — that is a real patron.

---

## The documents

| File | What it is |
|---|---|
| `docs/PRD.md` | system specification, domain rules, and architecture contract |
| `docs/SCHEMA.md` | data dictionary, generated from `db/01_schema.sql` |
| `docs/QUERIES.md` | query catalogue with fingerprinted MySQL execution output |
| `docs/TESTS.md` | integrity test log proving constraint and trigger refusals |
| `docs/OOP.md` | class model, layering rules, and OOP concept coverage |
| `docs/class-diagram-*.svg` | architectural class diagrams (domain model and layer boundaries) |
| `db/viva-queries.sql` | 21 self-contained blocks to run in Workbench during presentation |
| `tools/sqlcheck.py` | offline proof that the queries and seed match the schema |

## Status

- [x] Schema, constraints, indexes, views, trigger, stored procedure (`db/`)
- [x] Deterministic seed data & least-privilege user grants (`db/02_seed.sql`, `db/05_users.sql`)
- [x] Report query catalog & integrity test suite (`docs/QUERIES.md`, `docs/TESTS.md`)
- [x] ER diagram specification matching 12 schema entities (authored in ERDPlus)
- [x] JavaFX POS application — Core models, DAOs, services, UI shell, seat map, checkout, receipts, background hold sweeper, countdown chip & bookings list (Phases 0–6)
- [x] 100% test coverage across Unit (31), Database Integration (34), and Headless UI (14) suites (79 total tests)
