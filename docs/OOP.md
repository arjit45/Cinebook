# CineBook — Object-Oriented Architecture & Design Patterns

The class model itself — the package layout, the rule that keeps the layers apart, where
pricing lives, and why there is no discount strategy — is **`docs/PRD.md` §6**. Those are
architectural design decisions, and they govern the application structure.

This document details the concrete object-oriented concepts implemented across the system,
the key GoF design patterns in use, and the architectural boundaries of the codebase.

---

## 1. Object-Oriented Principles & Language Features

| Concept | Implementation in CineBook |
|---|---|
| Encapsulation | `Booking` exposes `addSeat()`, never `setTickets()`; totals are derived, not settable |
| Inheritance | `StaffUser` → `Administrator`, `BoxOfficeOperator`; `Seat` → `RegularSeat`, `PremiumSeat`, `ReclinerSeat` |
| Polymorphism | `Payment.renderReceipt()` differs per subclass; `Seat.priceFor(showtime)` differs per class |
| Abstract class | `StaffUser` with abstract `homeScreen()` and `canManageProgramme()` |
| Interface | `ReportQuery`, with one implementation per report tab |
| Generics | `FxTasks.run<T>(...)`; typed `List<ReportQuery>` and report result lists |
| Checked exceptions | `SeatUnavailableException` must be handled at the call site — it drives the UI toast |
| Collections | `List<Seat>`, `EnumMap<SeatClass, List<Seat>>` for the class breakdown, `List<ConcessionLine>` |
| Streams | `ProgrammeView` grouping upcoming screenings by date; `seats.stream().mapToDouble(...)`; `fnbTotal()` as a `BigDecimal` reduction |
| Enums | `SeatClass`, `BookingStatus`, `PaymentMethod`, `Role` |
| Records | `BookingSummary` (audit-aware projection for bookings search/list), `Receipt`, `ConcessionLine`, `ReportTable`, `QueryResult`, `ShiftSummary`, `SqlPreset` |
| Threads | `HoldExpiryDaemon` (`ScheduledExecutorService`) for periodic 60s hold sweeps; `FxTasks` (`ExecutorService`) for database work off the FX thread |
| File I/O | `ReceiptWriter` writes the ticket stub to disk |
| Patterns | Singleton (`Db`), DAO, simple factory (`Seat.of`), Strategy (`ReportQuery`, one per report tab), Observer (JavaFX `textProperty`/`valueProperty` listeners) |

This matrix maps language features to specific classes, ensuring strong adherence to
SOLID design principles, clean encapsulation, and robust exception boundaries.

---

## 2. Key Architectural Design Patterns

The core design patterns that structure the application runtime:

- **Strategy, `ReportQuery`.** One implementation per report tab, each carrying its own
  SQL and its own row mapper. The Reports screen iterates a `List<ReportQuery>`, so adding
  a tab is a new class plus one line in `ReportCatalog` — and the SQL console's preset
  list is generated from the same registry, which is why there is no second copy of any
  tab query.
- **Simple factory, `Seat.of(...)`.** The `seat_class` enum is mapped to
  `RegularSeat`/`PremiumSeat`/`ReclinerSeat` in one place. Every caller gets a seat that
  knows its own `priceFor(showtime)`, so the multiplier never becomes a `switch` in the
  checkout code.
- **Singleton, `Db`.** One instance owns a three-connection handoff pool, validates a
  connection before lending it, and refuses a nested borrow on the same thread. The
  singleton is the process-wide entry point; the `Handle` is the unit-of-work boundary
  that returns the connection even when the work throws.

---

## 3. Architecture Diagrams & Verification

The class structure and layering boundaries are formally mapped in the architectural diagrams:

- **Domain Model**: `docs/class-diagram-domain.svg`
- **Layer Boundaries**: `docs/class-diagram-layers.svg`
- **Verification Suites**: Covered by JUnit 5 suites documented in `docs/PRD.md` §14.

