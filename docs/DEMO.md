# CineBook — Interactive Demonstration & Operational Runbook

Six minutes, one prepared database, two application windows. The goal is to show the
seat hold winning a race, the money reconciling, and the database refusing what the
application should never have asked for.

## Prerequisites & Environment Setup

1. Rebuild the database and confirm the sanity grid that `db/02_seed.sql` leaves
   inside `db/00_setup.sql`: `movie 8 | screen 4 | seat 384 | showtime 136 |
   customer 21 | staff_user 2 | concession_item 8 | booking 300 | ticket 284 |
   booking_concession 100 | booking_audit 24 | payment 252`.
2. Copy `app.properties.example` to `app.properties` in the project root and fill in
   `jdbc.password`.
3. Build once: `mvn clean package`. The artifact is
   `target/cinebook-1.0.0.jar`.
4. Launch one window with `run.bat`. Launch a second window the same way for the
   concurrency beat.
5. Sign in as `operator / operator123` in both. Keep Workbench open on the
   `cinebook` schema and `db/viva-queries.sql` (interactive proof queries).

The two windows share the three-connection pool; that is intentional. Each window is a
separate counter, and the hold transaction is what keeps them from selling the same seat.

## Demonstration Walkthrough

| Time | Where | What to show |
|---|---|---|
| 0:00 | Workbench | The schema tree: 12 tables, both bridge tables, 2 views. |
| 0:40 | Workbench | Query 4 — films not screened in six months, using `LEFT JOIN … IS NULL`. |
| 1:20 | Workbench | Query 5 — top three films by ticket revenue. |
| 1:50 | App | Operator lands on **Schedule**; pick a date, open a screening. |
| 2:20 | App | Select two seats; point at the class multipliers and the live total. |
| 3:00 | App | **Confirm** the hold. Read the held reference and the pinned bar. |
| 3:20 | App #2 | The second window tries the same seat: the map reloads and names it. |
| 4:00 | App | Checkout: add a popcorn, confirm payment, read the receipt stub. |
| 4:40 | Workbench | `payment`, `booking_concession` and `booking_audit` rows prove the sale. |
| 5:10 | App | Cancel in **Bookings**: the refund figure and the seat returning to the map. |
| 5:30 | App | Sign out of window #1 and sign in as `admin / admin123`; **Reports** is now in the rail. |
| 5:40 | App (admin) | Open **SQL console**, load a preset, and run it; close on constraints, normalization and price snapshots. |

## Key Architectural Highlights

- The refund is a percentage of **ticket money**, not the grand total; food is
  non-refundable.
- Cancelling deletes the `ticket` rows, which is why the seat becomes available again.
  It is not a status flag on the seat.
- `ticket.price` and `booking_concession.unit_price` are snapshots. Changing a price
  list after the sale cannot rewrite the receipt.
- The SQL console accepts `SELECT`, `SHOW`, `EXPLAIN` and `DESCRIBE` only. Writes belong
  in Workbench, deliberately. It is behind the admin login; the operator rail does not
  offer Reports at all.
- The Reports tab is the manager's ledger: five business revenue/occupancy reports, the interactive
  SQL console, and the per-clerk shift summary Z-report behind the menu button.

## Troubleshooting & Operational Recovery

| Symptom | Recovery |
|---|---|
| `app.properties` missing | Copy the template next to `run.bat`; the window shows the same instruction. |
| MySQL stopped | Start the service, then sign out and back in; the sign-in screen reports the connection failure instead of hanging. |
| A seat is already held | Wait ten seconds for the sweeper, or use another free seat; the other till's hold is not yours to release. |
| The demo database is dirty | Re-run `db/00_setup.sql`; it drops and rebuilds the whole schema. |
| The jar will not start | Run `java -jar target/cinebook-1.0.0.jar` in a terminal to see the error; `run.bat` hides the console. |

## Environment Reset

Re-run `db/00_setup.sql` so the next run starts from the same 300 bookings, 136
screenings and deterministic seat map. The jar and `app.properties` do not need to be
rebuilt.

