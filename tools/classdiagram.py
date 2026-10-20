#!/usr/bin/env python3
"""
Generates the two class diagrams for the OOP report:

  docs/class-diagram-domain.svg   entities, inheritance, associations
  docs/class-diagram-layers.svg   presentation / service / DAO / infrastructure

Run:  python tools/classdiagram.py
No third-party dependencies.
"""
import html
import os

INK = "#16181C"
MUTED = "#6B6A63"
RULE = "#3A3C42"
HEAD_FILL = "#E7E4DA"
ABSTRACT_FILL = "#DCD7C9"
ACCENT = "#B3352A"
FONT = "'Segoe UI', 'Helvetica Neue', Arial, sans-serif"

OUT = os.path.join(os.path.dirname(__file__), "..", "docs")


def esc(t):
    return html.escape(str(t), quote=False)


class Box:
    """A UML class compartment box."""

    def __init__(self, x, y, w, name, stereo="", fields=(), methods=(), abstract=False):
        self.x, self.y, self.w = x, y, w
        self.name, self.stereo = name, stereo
        self.fields, self.methods = list(fields), list(methods)
        self.abstract = abstract
        self.head_h = 47 if stereo else 30
        body = 0
        if self.fields:
            body += 8 + 17 * len(self.fields)
        if self.methods:
            body += 8 + 17 * len(self.methods)
        if not self.fields and not self.methods:
            body += 10
        self.h = self.head_h + body

    def bottom(self):
        return self.y + self.h

    def mid(self):
        return self.y + self.h / 2

    def cx(self):
        return self.x + self.w / 2

    def svg(self):
        fill = ABSTRACT_FILL if self.abstract else "white"
        out = [
            f'<rect x="{self.x}" y="{self.y}" width="{self.w}" height="{self.h}" '
            f'rx="2" fill="{fill}" stroke="{INK}" stroke-width="1.3"/>',
            f'<path d="M{self.x},{self.y + self.head_h} H{self.x + self.w}" '
            f'stroke="{INK}" stroke-width="1.1"/>',
        ]
        y = self.y + 20
        if self.stereo:
            out.append(
                f'<text x="{self.x + 12}" y="{y}" font-family="{FONT}" font-size="10.5" '
                f'font-style="italic" fill="{MUTED}">\u00ab{esc(self.stereo)}\u00bb</text>'
            )
            y += 20
        style = ' font-style="italic"' if self.abstract else ""
        out.append(
            f'<text x="{self.x + 12}" y="{y}" font-family="{FONT}" font-size="14.5" '
            f'font-weight="600" fill="{INK}"{style}>{esc(self.name)}</text>'
        )
        y = self.y + self.head_h
        if self.fields:
            y += 8
            for f in self.fields:
                y += 17
                out.append(
                    f'<text x="{self.x + 12}" y="{y - 4}" font-family="{FONT}" '
                    f'font-size="11.5" fill="{INK}">{esc(f)}</text>'
                )
        if self.methods:
            out.append(
                f'<path d="M{self.x},{y} H{self.x + self.w}" stroke="{RULE}" '
                f'stroke-width="0.8" stroke-dasharray="3 2"/>'
            )
            y += 8
            for m in self.methods:
                y += 17
                out.append(
                    f'<text x="{self.x + 12}" y="{y - 4}" font-family="{FONT}" '
                    f'font-size="11.5" fill="{INK}">{esc(m)}</text>'
                )
        return "\n".join(out)


def path(points, marker_end=None, marker_start=None, dashed=False, label=None,
         label_xy=None, weight=1.2):
    d = f"M{points[0][0]},{points[0][1]} " + " ".join(
        f"L{p[0]},{p[1]}" for p in points[1:]
    )
    me = f' marker-end="url(#{marker_end})"' if marker_end else ""
    ms = f' marker-start="url(#{marker_start})"' if marker_start else ""
    dash = ' stroke-dasharray="5 4"' if dashed else ""
    out = [
        f'<path d="{d}" fill="none" stroke="{RULE}" stroke-width="{weight}"{dash}{me}{ms}/>'
    ]
    if label and label_xy:
        out.append(
            f'<text x="{label_xy[0]}" y="{label_xy[1]}" font-family="{FONT}" '
            f'font-size="11" fill="{MUTED}">{esc(label)}</text>'
        )
    return "\n".join(out)


def mult(x, y, text, anchor="start"):
    return (
        f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="11" fill="{MUTED}" '
        f'text-anchor="{anchor}">{esc(text)}</text>'
    )


def bus(parent, children, spine_x, stub_gap=18):
    """Inheritance: hollow triangle at the parent, one spine, stubs to children."""
    cx, y0 = parent.cx(), parent.bottom()
    ys = [c.mid() for c in children]
    pts = [(cx, y0), (cx, y0 + stub_gap), (spine_x, y0 + stub_gap), (spine_x, max(ys))]
    out = [path(pts, marker_start="tri", weight=1.3)]
    for c, y in zip(children, ys):
        end_x = c.x + c.w
        out.append(
            f'<path d="M{spine_x},{y} H{end_x}" fill="none" stroke="{RULE}" '
            f'stroke-width="1.3"/>'
        )
    return "\n".join(out)


DEFS = f"""
<defs>
  <marker id="tri" viewBox="0 0 14 14" refX="1" refY="7" markerWidth="14"
          markerHeight="14" orient="auto">
    <path d="M13,1 L1,7 L13,13 z" fill="white" stroke="{INK}" stroke-width="1.2"/>
  </marker>
  <marker id="crow" viewBox="0 0 14 14" refX="13" refY="7" markerWidth="14"
          markerHeight="14" orient="auto">
    <path d="M12,7 L1,1 M12,7 L1,7 M12,7 L1,13" fill="none" stroke="{RULE}"
          stroke-width="1.1"/>
  </marker>
  <marker id="open" viewBox="0 0 14 14" refX="13" refY="7" markerWidth="14"
          markerHeight="14" orient="auto">
    <path d="M1,1 L13,7 L1,13" fill="none" stroke="{RULE}" stroke-width="1.1"/>
  </marker>
</defs>
"""


def header(title, subtitle, w):
    return (
        f'<text x="30" y="46" font-family="{FONT}" font-size="22" font-weight="700" '
        f'fill="{INK}">{esc(title)}</text>'
        f'<text x="{w - 30}" y="46" font-family="{FONT}" font-size="12" '
        f'fill="{MUTED}" text-anchor="end">{esc(subtitle)}</text>'
        f'<path d="M30,60 H{w - 30}" stroke="{INK}" stroke-width="1"/>'
    )


# ---------------------------------------------------------------------------
# Diagram 1 - domain model
# ---------------------------------------------------------------------------
def domain():
    W, H = 1740, 1400
    b = {}

    b["Movie"] = Box(30, 80, 300, "Movie", fields=[
        "- movieId : int", "- title : String", "- genre : String",
        "- language : String", "- runtimeMin : int", "- releaseDate : LocalDate",
        "- certificate : Certificate", "- imdbRating : BigDecimal",
    ])

    b["Screen"] = Box(30, 290, 300, "Screen", fields=[
        "- screenId : int", "- name : String", "- seatCapacity : int",
    ], methods=["+ seatsAvailable(Showtime) : int"])

    b["Customer"] = Box(30, 450, 300, "Customer", fields=[
        "- customerId : int", "- fullName : String", "- email : String",
        "- phone : String", "- joinedOn : LocalDate",
    ], methods=["+ displayName() : String"])

    b["StaffUser"] = Box(30, 650, 300, "StaffUser", stereo="abstract",
                         abstract=True, fields=[
                             "# staffId : int", "# username : String",
                             "# fullName : String", "# role : Role",
                             "# passwordHash : String",
                         ], methods=[
                             "+ authenticate(raw : String) : boolean",
                             "+ homeScreen() : String",
                             "+ canManageProgramme() : boolean",
                         ])
    b["Administrator"] = Box(70, 880, 260, "Administrator", methods=[
        "+ homeScreen() : \"Reports\"", "+ canManageProgramme() : true",
    ])
    b["BoxOfficeOperator"] = Box(70, 990, 260, "BoxOfficeOperator", methods=[
        "+ homeScreen() : \"Schedule\"", "+ canManageProgramme() : false",
    ])

    b["Showtime"] = Box(370, 80, 300, "Showtime", fields=[
        "- showtimeId : int", "- showDate : LocalDate", "- startTime : LocalTime",
        "- basePrice : BigDecimal", "- movie : Movie", "- screen : Screen",
    ], methods=["+ seatsSold() : int", "+ isSoldOut() : boolean"])

    b["AuditEntry"] = Box(370, 580, 300, "AuditEntry", fields=[
        "- auditId : int", "- bookingId : int", "- oldStatus : BookingStatus",
        "- newStatus : BookingStatus", "- changedAt : LocalDateTime",
    ])

    b["Booking"] = Box(710, 80, 310, "Booking", fields=[
        "- bookingId : int", "- bookedAt : LocalDateTime", "- status : BookingStatus",
        "- ticketAmount : BigDecimal", "- fnbAmount : BigDecimal",
        "- totalAmount : BigDecimal", "- refundAmount : BigDecimal",
        "- customer : Customer", "- showtime : Showtime",
        "- tickets : List<Ticket>", "- concessions : List<ConcessionLine>",
        "- soldBy : StaffUser", "- cancelledBy : StaffUser",
    ], methods=[
        "+ addSeat(seat, price) : void", "+ addConcession(item, qty) : void",
        "+ removeSeat(seat) : void", "+ confirm() : void", "+ cancel() : void",
        "+ recalculateTotal() : void", "+ fnbTotal() : BigDecimal",
        "+ isExpired(now) : boolean",
    ])

    b["Payment"] = Box(710, 570, 310, "Payment", stereo="abstract", abstract=True,
                       fields=[
                           "# paymentId : int", "# amount : BigDecimal",
                           "# paidAt : LocalDateTime", "# method : PaymentMethod",
                       ], methods=["+ renderReceipt(b : Booking) : String"])

    b["CardPayment"] = Box(740, 770, 280, "CardPayment", fields=[
        "- cardLast4 : String", "- authCode : String",
    ], methods=["+ renderReceipt(b) : String"])
    b["UpiPayment"] = Box(740, 900, 280, "UpiPayment", fields=[
        "- vpa : String", "- upiRef : String",
    ], methods=["+ renderReceipt(b) : String"])
    b["CashPayment"] = Box(740, 1030, 280, "CashPayment", fields=[
        "- tendered : BigDecimal",
    ], methods=["+ renderReceipt(b) : String"])

    b["Ticket"] = Box(1120, 80, 290, "Ticket", fields=[
        "- seat : Seat", "- showtime : Showtime", "- price : BigDecimal",
        "- rowLabel : char", "- seatNo : int",
    ], methods=["+ label() : String"])

    b["Seat"] = Box(1120, 260, 290, "Seat", stereo="abstract", abstract=True, fields=[
        "# seatId : int", "# rowLabel : char", "# seatNo : int",
        "# seatClass : SeatClass",
    ], methods=["+ priceFor(showtime) : BigDecimal", "+ label() : String"])

    b["RegularSeat"] = Box(1150, 500, 260, "RegularSeat",
                           methods=["+ priceFor(s) : 1.00 x base"])
    b["PremiumSeat"] = Box(1150, 620, 260, "PremiumSeat",
                           methods=["+ priceFor(s) : 1.25 x base"])
    b["ReclinerSeat"] = Box(1150, 740, 260, "ReclinerSeat",
                            methods=["+ priceFor(s) : 1.60 x base"])

    b["SeatClass"] = Box(1460, 80, 250, "SeatClass", stereo="enumeration",
                         fields=["REGULAR", "PREMIUM", "RECLINER"])
    b["BookingStatus"] = Box(1460, 230, 250, "BookingStatus", stereo="enumeration",
                             fields=["HELD", "CONFIRMED", "CANCELLED"])
    b["PaymentMethod"] = Box(1460, 380, 250, "PaymentMethod", stereo="enumeration",
                             fields=["CARD", "UPI", "CASH"])
    b["ItemCategory"] = Box(1460, 530, 250, "ItemCategory", stereo="enumeration",
                            fields=["SNACK", "DRINK", "COMBO", "DESSERT"])

    # the food side of the booking: price list, persisted line, immutable view
    b["ConcessionItem"] = Box(370, 1170, 300, "ConcessionItem", fields=[
        "- itemId : int", "- name : String",
        "- category : ItemCategory", "- unitPrice : BigDecimal",
        "- active : boolean",
    ], methods=["+ priceFor(qty) : BigDecimal"])

    b["BookingConcession"] = Box(710, 1170, 310, "BookingConcession", fields=[
        "- bookingId : int", "- itemId : int",
        "- quantity : int", "- unitPrice : BigDecimal",
    ], methods=["+ lineTotal() : BigDecimal"])

    b["ConcessionLine"] = Box(1120, 1170, 290, "ConcessionLine", stereo="record",
                              fields=[
                                  "- name : String", "- category : ItemCategory",
                                  "- quantity : int", "- unitPrice : BigDecimal",
                              ], methods=["+ lineTotal() : BigDecimal"])

    lines = []
    # Showtime -> Movie
    lines.append(path([(370, 150), (330, 150)], marker_end="crow"))
    lines.append(mult(342, 142, "1", "end"))
    lines.append(mult(358, 142, "*"))
    # Showtime -> Screen
    lines.append(path([(370, 230), (345, 230), (345, 330), (330, 330)],
                      marker_end="crow"))
    lines.append(mult(338, 322, "1", "end"))
    lines.append(mult(352, 322, "*"))
    # Booking -> Showtime   (labels live in the 670-710 gap, never inside a box)
    lines.append(path([(710, 150), (670, 150)], marker_end="crow"))
    lines.append(mult(676, 142, "1"))
    lines.append(mult(702, 142, "*", "end"))
    # Booking -> Customer   (one straight run, under Showtime, above AuditEntry)
    lines.append(path([(710, 465), (330, 465)], marker_end="crow"))
    lines.append(mult(338, 457, "1", "end"))
    lines.append(mult(702, 457, "*", "end"))
    # Booking -> StaffUser, two parallel lines: who sold it, who refunded it
    lines.append(path([(710, 400), (684, 400), (684, 750), (330, 750)],
                      marker_end="crow"))
    lines.append(mult(702, 394, "*", "end"))
    lines.append(mult(336, 744, "soldBy  1"))
    lines.append(path([(710, 430), (700, 430), (700, 785), (330, 785)],
                      marker_end="crow"))
    lines.append(mult(702, 424, "*", "end"))
    lines.append(mult(336, 779, "cancelledBy  0..1"))
    # Booking -> Payment
    lines.append(path([(865, b["Booking"].bottom()), (865, 570)], marker_end="crow"))
    lines.append(mult(873, b["Booking"].bottom() + 16, "1"))
    lines.append(mult(873, 562, "1"))
    # Booking -> Ticket
    lines.append(path([(1020, 130), (1120, 130)], marker_end="crow"))
    lines.append(mult(1028, 122, "1", "end"))
    lines.append(mult(1114, 122, "*", "end"))
    # Booking -> AuditEntry  (down the open lane, into the right edge)
    lines.append(path([(760, b["Booking"].bottom()), (760, 640), (670, 640)],
                      marker_end="crow"))
    # Booking -> BookingConcession, down the corridor right of the Payment stack
    lines.append(path([(1020, 460), (1060, 460), (1060, 1235), (1020, 1235)],
                      marker_end="crow"))
    lines.append(mult(1026, 454, "1"))
    lines.append(mult(1034, 1229, "*", "end"))
    # BookingConcession -> ConcessionItem, and the record it maps to
    lines.append(path([(710, 1235), (670, 1235)], marker_end="crow"))
    lines.append(mult(676, 1229, "1"))
    lines.append(mult(704, 1229, "*", "end"))
    lines.append(path([(1020, 1275), (1120, 1275)], marker_end="open", dashed=True,
                      label="maps to", label_xy=(1070, 1266)))
    # Ticket -> Seat
    lines.append(path([(b["Ticket"].cx(), b["Ticket"].bottom()),
                       (b["Ticket"].cx(), 260)], marker_end="crow"))
    lines.append(mult(1257, 244, "*", "end"))
    lines.append(mult(1273, 254, "1"))
    # Screen -> Seat: down the open lane at x=520, back up at x=1080
    lines.append(path([(330, 350), (520, 350), (520, 510), (1080, 510),
                       (1080, 350), (1120, 350)], marker_end="crow"))
    lines.append(mult(430, 341, "1 : N  seats belong to a screen", "middle"))
    # inheritance buses
    lines.append(bus(b["Seat"], [b["RegularSeat"], b["PremiumSeat"], b["ReclinerSeat"]],
                     1430))
    lines.append(bus(b["Payment"], [b["CardPayment"], b["UpiPayment"], b["CashPayment"]],
                     1035))
    lines.append(bus(b["StaffUser"],
                     [b["Administrator"], b["BoxOfficeOperator"]], 45))

    body = [header("CineBook \u2014 domain model",
                   "diagram 1 of 2 \u00b7 entities, inheritance, associations", W), DEFS]
    body += lines
    body += [bx.svg() for bx in b.values()]
    body.append(
        f'<text x="30" y="{H - 34}" font-family="{FONT}" font-size="11" fill="{MUTED}">'
        f'Multiplicity is shown at each end of an association. Enumerations are declared '
        f'on the right; every enum lives in com.cinebook.model.enums. '
        f'BookingDraft (Builder) and PaymentFactory are omitted for clarity.</text>'
    )
    body.append(
        f'<text x="30" y="{H - 14}" font-family="{FONT}" font-size="11" fill="{MUTED}">'
        f'ConcessionLine is the immutable line Booking holds: BookingDao maps each '
        f'booking_concession row into one, snapshotted at sale time.</text>'
    )
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
            f'viewBox="0 0 {W} {H}">\n<rect width="{W}" height="{H}" fill="white"/>\n'
            + "\n".join(body) + "\n</svg>\n")


# ---------------------------------------------------------------------------
# Diagram 2 - layers
# ---------------------------------------------------------------------------
def layer(x, y, w, h, title, items, sub=""):
    out = [
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="3" fill="#F7F5EF" '
        f'stroke="{INK}" stroke-width="1.2"/>',
        f'<path d="M{x},{y + 34} H{x + w}" stroke="{INK}" stroke-width="1"/>',
        f'<text x="{x + 14}" y="{y + 23}" font-family="{FONT}" font-size="13" '
        f'font-weight="700" fill="{INK}">{esc(title)}</text>',
    ]
    if sub:
        out.append(
            f'<text x="{x + w - 14}" y="{y + 23}" font-family="{FONT}" font-size="10.5" '
            f'fill="{MUTED}" text-anchor="end">{esc(sub)}</text>'
        )
    yy = y + 34
    for it in items:
        yy += 20
        out.append(
            f'<text x="{x + 14}" y="{yy - 6}" font-family="{FONT}" font-size="11.5" '
            f'fill="{INK}">{esc(it)}</text>'
        )
    return "\n".join(out)


def layers():
    W, H = 1400, 880
    parts = [header("CineBook \u2014 application layers",
                    "diagram 2 of 2 \u00b7 who may talk to whom", W), DEFS]

    parts.append(layer(60, 90, 1280, 140, "Presentation  \u00b7  com.cinebook.ui",
                       ["LoginController   DashboardController   ScheduleController   "
                        "SeatMapController   CheckoutController   BookingsController   "
                        "ReportsController",
                        "FXML views + one stylesheet. No SQL, no java.sql import, "
                        "no Connection."],
                       "depends on services only"))

    parts.append(layer(60, 270, 800, 140, "Service  \u00b7  com.cinebook.service",
                       ["BookingService    ScheduleService    AuthService    ReportService",
                        "The only layer that opens, commits or rolls back a transaction.",
                        "BookingService records soldBy / cancelledBy \u2014 a refund never "
                        "includes food."],
                       "owns transactions"))

    parts.append(layer(900, 270, 440, 140, "\u00abinterface\u00bb ReportQuery",
                       ["RevenueByScreen   TopFilms   DormantFilms",
                        "OccupancyReport   LoyalCustomers",
                        "One Strategy implementation per report tab."],
                       "Strategy pattern"))

    parts.append(layer(60, 450, 1280, 150, "Data access  \u00b7  com.cinebook.dao",
                       ["\u00abinterface\u00bb Repository<T extends Identifiable<K>, K>"
                        "   \u2014 findAll \u00b7 findById \u00b7 save \u00b7 update \u00b7 delete",
                        "MovieDao   ScreenDao   SeatDao   ShowtimeDao   CustomerDao   "
                        "BookingDao   TicketDao   PaymentDao   StaffDao   AuditDao   "
                        "ConcessionDao",
                        "Stateless. Owns the SQL. PreparedStatement only \u2014 never "
                        "string concatenation."],
                       "one DAO per aggregate"))

    parts.append(layer(60, 640, 380, 130, "Infrastructure  \u00b7  com.cinebook.util",
                       ["Db (singleton, DriverManager)", "ReceiptWriter (file I/O)",
                        "Formatters (money, dates)"]))

    parts.append(layer(480, 640, 340, 130, "Exceptions  \u00b7  com.cinebook.exception",
                       ["BookingException (checked)",
                        "SeatUnavailableException  PaymentFailedException",
                        "ValidationException  AuthException"]))

    parts.append(layer(860, 640, 480, 130, "MySQL 8.0  \u00b7  schema cinebook",
                       ["12 tables   \u00b7   2 views   \u00b7   1 trigger   \u00b7   1 procedure",
                        "Reached only through cinebook_app (DML, no DDL rights)."],
                       "external"))

    # vertical arrows between bands
    for x, y0, y1, label in [
        (400, 230, 270, "calls"),
        (400, 410, 450, "calls"),
        (400, 600, 640, "opens"),
    ]:
        parts.append(path([(x, y0), (x, y1)], marker_end="open"))
        parts.append(
            f'<text x="{x + 8}" y="{(y0 + y1) / 2 + 4}" font-family="{FONT}" '
            f'font-size="10.5" fill="{MUTED}">{esc(label)}</text>'
        )

    # DAO implements Repository
    parts.append(path([(700, 450), (700, 425), (700, 410)], marker_end="tri",
                      dashed=True))
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
            f'viewBox="0 0 {W} {H}">\n<rect width="{W}" height="{H}" fill="white"/>\n'
            + "\n".join(parts) + "\n</svg>\n")


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, svg in [("class-diagram-domain.svg", domain()),
                      ("class-diagram-layers.svg", layers())]:
        p = os.path.join(OUT, name)
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(svg)
        print(f"wrote {os.path.abspath(p)} ({len(svg)} bytes)")


if __name__ == "__main__":
    main()
