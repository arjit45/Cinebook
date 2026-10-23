# CineBook — integrity test log

> **Generated file.** Produced by `tools/querydoc.py` from
> `db/04_queries.sql`. Do not edit by hand — edit the SQL and rerun
> `python tools/querydoc.py`.

These are the checks that turn the schema's constraints from written
rules into observed facts. Each one has a query in `db/04_queries.sql`,
an expected result, and a way of failing loudly.


**Status: executed against MySQL 8.0.46 on 2026-10-20 — all seven checks passed.**
The *Observed* column is a recorded run, not a design claim: `db/00_setup.sql`
loaded the whole dataset (12 tables, 1 523 rows) and each check below was then
run against it. Two branches that no seeded row can reach are listed separately
under *Unexercised branches* and are not claimed as observed.


## How to run

```sql
SOURCE db/00_setup.sql;   -- 01 schema + 02 seed + 03 objects + 04 queries
-- or, on a database that already exists:
SOURCE db/04_queries.sql;
```

Then refresh the output recorded in `docs/QUERIES.md` so the catalogue describes
the database you just built rather than the one it was captured from:

```bash
MYSQL_PWD=... python tools/querydoc.py --capture    # re-run and record
MYSQL_PWD=... python tools/querydoc.py --check-db   # advisory, always exits 0
```


## The tests

| # | What it proves | Expected | Ran | Observed |
|---|---|---|---|---|
| 2 | Ticket and food revenue are reported separately per auditorium. | `total_revenue = ticket_revenue + fnb_revenue` on every row. | [x] | 4 rows, 0 of them breaking the identity (Audi 1 41 620.00 = 14 940.00 + 26 680.00; the other three have no food). |
| 4 | A film screened within the last six months must not appear. | Only dormant films are listed; `days_dormant` is 9999 when never shown (the COALESCE sentinel, not a NULL). | [x] | 2 rows: Blue Meridian 200 days, Static Bloom 204. Zero of them screened inside six months. |
| 7 | The same rule as query 4, written with `NOT EXISTS`. | **Identical rows to query 4** — two forms, one answer. | [x] | 2 rows, `movie_id` set 7,8 — the same set query 4 returned. |
| 8 | The three money columns agree: `total = ticket + fnb`, tickets match `SUM(ticket.price)`, and the payment matches the total. | **Zero rows.** Any row is a discount or a half-written update. | [x] | 0 rows across 244 CONFIRMED bookings. |
| 11 | No seat is dead inventory: every seat that has never been sold is found. | A seat with a `ticket` row must **not** appear; a fully sold screen returns nothing. | [x] | 372 of 384 seats unsold; 0 of those carry a ticket, and 372 + 12 sold = 384. |
| 12 | Food revenue is isolated from ticket revenue per film, with no row multiplication. | Each film appears at most once; a zero ticket base yields NULL from `NULLIF`, not a division error. | [x] | 6 rows, 6 distinct titles. The naive double join on the same bookings yields 19 910.00 instead of 14 940.00 (60 tickets counted as 80 rows). |
| 13 | A clerk's takings and a clerk's refunds are separable by role. | The refund half is non-zero for a clerk who cancelled a sale someone else rang up — proof it keys on `cancelled_by_staff_id`. | [x] | Clerk 2: 1 refund, 234.00, on a sale clerk 1 rang up. Keyed on `sold_by_staff_id` it would be 0 for clerk 2 and 234.00 for clerk 1 — the wrong clerk. |

### Unexercised branches

Correct by construction, but no seeded row reaches them, so they are not claimed
as observed above:

- Query 4's `9999` sentinel — all 8 films have at least one `showtime`, so the
  `MAX(st.show_date) IS NULL` branch never fires.
- Query 12's `NULLIF` guard — the `INNER JOIN` on ticket revenue already drops a
  zero base, so no row can divide by zero.

Query 2's `COALESCE(SUM(tk.tickets), 0)` is likewise unexercised: every confirmed booking
has at least one ticket, so the outer join never produces a NULL count.


## Supporting queries (reported, not pass/fail)

These produce numbers to read rather than a constraint to prove.

| # | Question |
|---|---|
| 1 | "Retrieve all X with attributes, sorted" |
| 3 | "Detail list joining three or more tables" |
| 5 | "Top 3 by aggregate value" |
| 6 | HAVING — loyal customers (more than 3 confirmed bookings). |
| 9 | Window function — rank films by ticket revenue |
| 10 | Occupancy view — how full is each remaining screening? |
| 14 | TRANSACTION drill — Manual verification of atomic booking confirmation. |


## The transaction drill

Query 14 is a manual procedure, not a SELECT. Run it on a `HELD` booking
and confirm all four steps:

```sql
SELECT booking_id, status, total_amount FROM booking WHERE status='HELD' LIMIT 1;
CALL sp_confirm_booking(<that id>, 'UPI');
SELECT * FROM booking       WHERE booking_id = <that id>;
SELECT * FROM payment       WHERE booking_id = <that id>;
SELECT * FROM booking_audit WHERE booking_id = <that id>;
```

It proves four things at once: status moved `HELD → CONFIRMED`, the
three money columns agree, money was recorded, and the trigger wrote an
audit row nobody asked it for.


**Recorded run (2026-10-20, booking 8):** status `HELD → CONFIRMED`, money stayed
250.00 + 660.00 = 910.00, `payment` 256 recorded 910.00 UPI as `TXN00000008`, and
`booking_audit` row 32 (`HELD → CONFIRMED`) was written by the trigger with the
procedure never mentioning it. The row was then returned to `HELD` and the payment
and audit rows removed, so the seeded state is what the other checks were run on.


The procedure's guards were exercised too, each rejected with SQLSTATE 45000 and a
named message rather than a general failure: `CHEQUE` as a method, a booking id
that does not exist, and a booking that is not `HELD`.


## Coverage

- Numbered queries parsed: **14**
- Of those, produce a result set: **13**
- Manual drill blocks: **1** (query 14)
- Constraint-bearing checks in the table above: **7**
- Views exercised: `v_movie_performance`, `v_show_occupancy`
- Objects exercised: trigger `trg_booking_status_audit` (fires when the procedure confirms) · procedure `sp_confirm_booking` (query 14)
- Every column and alias above resolves against `db/01_schema.sql` — `tools/sqlcheck.py` runs inside this gate and refuses the documents otherwise
- One-shot bundle `db/00_setup.sql` carries 01–04 exactly once each, in order, so the `SOURCE` above runs exactly the SQL documented here