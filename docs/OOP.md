# CineBook — OOP coverage

The class model itself — the package layout, the rule that keeps the layers apart, where
pricing lives, and why there is no discount strategy — is **`docs/PRD.md` §6**. Those are
design decisions, and they belong in the plan.

This file is the other half: which graded concept lives where, the three patterns that
are worth more than a name, and what the OOP report has to contain.

---

## 1. Coverage — every line is a viva answer

| Concept | Where it lives |
|---|---|
| Encapsulation | `Booking` exposes `addSeat()`, never `setTickets()`; totals are derived, not settable |
| Inheritance | `StaffUser` → `Administrator`, `BoxOfficeOperator`; `Seat` → `RegularSeat`, `PremiumSeat`, `ReclinerSeat` |
| Polymorphism | `Payment.renderReceipt()` differs per subclass; `Seat.priceFor(showtime)` differs per class |
| Abstract class | `StaffUser` with abstract `homeScreen()` and `canManageProgramme()` |
| Interface | `Repository<T, K>`, `ReportQuery`, `ReceiptWriter` |
| Generics | `Repository<T extends Identifiable<K>, K>`, `ResultSetMapper<T>` |
| Checked exceptions | `SeatUnavailableException` must be handled at the call site — it drives the UI toast |
| Collections | `List<Seat>`, `Map<SeatClass, BigDecimal>` for the cart breakdown, `List<ConcessionLine>` |
| Streams | `ReportService` grouping; `seats.stream().mapToDouble(...)`; `fnbTotal()` as a `BigDecimal` reduction |
| Enums | `SeatClass`, `BookingStatus`, `PaymentMethod`, `Role` |
| Records | `BookingSummary` (audit-aware projection for bookings search/list), `SeatView`, `RevenueRow`, `ConcessionLine` |
| Threads | `HoldExpiryDaemon` (`ScheduledExecutorService`) for periodic 60s hold sweeps; `FxTasks` (`Task<T>`) for transactions off the FX thread |
| File I/O | `ReceiptWriter` writes the ticket stub to disk |
| Patterns | Singleton (`Db`), DAO, Factory (`PaymentFactory`), Strategy (`ReportQuery`, one per report tab), Builder (`BookingDraft`), Observer (`ObservableList` selection binding), Template Method (`BaseController.loadAsync`) |

Each row is one sentence away from being a paragraph an examiner can check in the code.
That sentence is what the report needs; the table alone is what the marking scheme gets.

---

## 2. Three patterns worth more than a mention

These are the ones usually only *named* in student projects, so have the mechanism ready:

- **Builder, `BookingDraft`.** A sale is assembled from a seat set, a customer, a
  concession cart and a payment method that arrive in that order from three different
  screens. `BookingDraft` collects them, and `toBooking()` is the only way out — so a
  half-built booking cannot reach a DAO.
- **Strategy, `ReportQuery`.** One implementation per report tab, each carrying its own
  SQL and its own `ResultSetMapper`. The Reports screen iterates a `List<ReportQuery>`,
  so adding a tab is a new class plus one line in a registry — and the SQL console's
  preset list is generated from the same registry, which is why there is no second copy
  of any query.
- **Template Method, `BaseController.loadAsync()`.** Load, then render, then handle the
  failure — the three steps every screen repeats. The screen supplies the query; the
  base class supplies the background thread, the busy state and the error toast.

---

## 3. What the OOP report has to contain

Section by section, with the artifact behind each one, the OOP course report documents
the coverage table above, expanded with a sentence of evidence per row, the
two generated class diagrams (`docs/class-diagram-domain.svg`,
`docs/class-diagram-layers.svg`), and the test results from `docs/PRD.md` §14.
