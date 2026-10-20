#!/usr/bin/env python3
"""
Generates the CineBook ER diagram, from the schema, not from a drawing.

  docs/ER.svg        deterministic vector output (what the report embeds)
  docs/ER.drawio     opens at app.diagrams.net with nothing installed
  docs/ER.png        headless Edge, 2x device scale, >= 2000 px wide
  docs/ERDPlus.md    build sheet, for reproducing it in ERDPlus by hand

Run:  python tools/erdiagram.py            (writes all four)
      python tools/erdiagram.py --no-png   (skips Edge; SVG + drawio + sheet)

The entities, their attributes and every relationship are READ FROM
db/01_schema.sql. This file only says where the boxes go. Before it writes
anything it checks:

  * every table in the schema has a box, and no box has no table
  * every FOREIGN KEY in the schema is drawn as an edge, and no edge is
    invented that the schema does not have
  * both associative entities exist and carry attributes of their own
  * no box overlaps another, and nothing falls outside the canvas

That is the point of generating rather than drawing: the diagram cannot
quietly disagree with the database. Change a column, re-run, and either the
picture updates or the script refuses to run.

No third-party dependencies.
"""
import html
import os
import re
import shutil
import subprocess
import sys
import tempfile

SCHEMA = os.path.join(os.path.dirname(__file__), "..", "db", "01_schema.sql")
OUT = os.path.join(os.path.dirname(__file__), "..", "docs")

INK = "#16181C"
MUTED = "#6B6A63"
RULE = "#3A3C42"
HEAD_FILL = "#E7E4DA"
ASSOC_FILL = "#EFE2DC"
ACCENT = "#B3352A"
PAGE = "white"
FONT = "'Segoe UI', 'Helvetica Neue', Arial, sans-serif"

TITLE = "CineBook \u2014 entity relationship diagram"
SUBTITLE = ("12 entities \u00b7 14 foreign keys \u00b7 crow's-foot notation \u00b7 "
            "generated from db/01_schema.sql")

W, H = 1620, 1200
MARGIN = 40

# ---------------------------------------------------------------------------
# 1. Placement. The only hand-written geometry in this file.
#    x, y is the top-left corner; height is computed from the attribute count.
# ---------------------------------------------------------------------------
PLACEMENT = {
    "movie":              (60, 110, 330),
    "showtime":           (450, 110, 330),
    "screen":             (840, 110, 310),
    "seat":               (1210, 110, 330),
    "staff_user":         (60, 420, 330),
    "booking":            (450, 420, 350),
    "payment":            (1210, 420, 330),
    "customer":           (60, 720, 330),
    "ticket":             (860, 780, 310),
    "booking_audit":      (1210, 700, 330),
    "booking_concession": (450, 1000, 330),
    "concession_item":    (860, 1000, 330),
}

# ---------------------------------------------------------------------------
# 2. Relationships. Keyed by (child table, child column) -- exactly how the
#    schema names a foreign key. Everything else here is routing and wording.
#    side + coordinate: 'left'/'right' take a y, 'top'/'bottom' take an x.
# ---------------------------------------------------------------------------
RELATIONSHIPS = {
    ("showtime", "movie_id"): dict(
        label="screens", start=("movie", "right", 150),
        via=[], end=("showtime", "left", 150)),

    ("showtime", "screen_id"): dict(
        label="hosts", start=("screen", "left", 150),
        via=[], end=("showtime", "right", 150)),

    ("seat", "screen_id"): dict(
        label="has", start=("screen", "right", 160),
        via=[], end=("seat", "left", 160)),

    ("booking", "showtime_id"): dict(
        label="booked for", start=("showtime", "bottom", 620),
        via=[], end=("booking", "top", 620)),

    ("booking", "customer_id"): dict(
        label="placed by", start=("customer", "right", 780),
        via=[(420, 780), (420, 600)], end=("booking", "left", 600)),

    ("booking", "sold_by_staff_id"): dict(
        label="sold by", start=("staff_user", "right", 470),
        via=[], end=("booking", "left", 470)),

    ("booking", "cancelled_by_staff_id"): dict(
        label="cancels", optional=True,
        start=("staff_user", "right", 540), via=[], end=("booking", "left", 540)),

    ("ticket", "booking_id"): dict(
        label="holds", start=("booking", "bottom", 600),
        via=[(600, 690), (900, 690)], end=("ticket", "top", 900)),

    ("ticket", "showtime_id"): dict(
        label="for the UNIQUE key", start=("showtime", "bottom", 700),
        via=[(700, 340), (950, 340)], end=("ticket", "top", 950)),

    ("ticket", "seat_id"): dict(
        label="reserves", start=("seat", "bottom", 1375),
        via=[(1375, 320), (1100, 320)], end=("ticket", "top", 1100)),

    ("booking_concession", "booking_id"): dict(
        label="adds", start=("booking", "bottom", 520),
        via=[], end=("booking_concession", "top", 520)),

    ("booking_concession", "item_id"): dict(
        label="of", start=("concession_item", "left", 1050),
        via=[], end=("booking_concession", "right", 1050)),

    ("payment", "booking_id"): dict(
        label="paid by", start=("booking", "right", 470),
        via=[], end=("payment", "left", 470)),

    ("booking_audit", "booking_id"): dict(
        label="logged in", start=("booking", "right", 620),
        via=[(830, 620), (830, 900), (1350, 900)], end=("booking_audit", "bottom", 1350)),
}

# The two associative (bridge) entities. Checked, not assumed.
ASSOCIATIVE = ("ticket", "booking_concession")


# ---------------------------------------------------------------------------
# Parsing db/01_schema.sql
# ---------------------------------------------------------------------------
class Column:
    def __init__(self, name, type_text):
        self.name = name
        self.type, self.full_type = split_type(type_text)
        self.pk = False
        self.fk = False
        self.uq = False
        self.ref = None          # (table, column) when this column is an FK

    def tag(self):
        bits = []
        if self.pk:
            bits.append("PK")
        if self.fk:
            bits.append("FK")
        if self.uq:
            bits.append("UQ")
        return " ".join(bits)


def split_type(raw):
    """'INT AUTO_INCREMENT PRIMARY KEY' -> ('INT', 'INT').

    A long ENUM is drawn as ENUM(n) and its values are listed under the
    diagram -- a 33-character type would push the column name out of the box.
    """
    raw = " ".join(raw.strip().split())
    depth, out = 0, []
    for ch in raw:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == " " and depth == 0:
            break
        out.append(ch)
    full = "".join(out).rstrip(",")
    if full.upper().startswith("ENUM") and len(full) > 22:
        values = re.findall(r"'([^']*)'", full)
        return f"ENUM({len(values)})", full
    return full, full


def enum_domains(tables):
    """Every ENUM column, for the footnote: name (v1, v2, ...)."""
    seen = []
    for t in tables.values():
        for col in t["cols"].values():
            if col.full_type.upper().startswith("ENUM") and "(" in col.full_type:
                values = re.findall(r"'([^']*)'", col.full_type)
                if values and (col.name, values) not in [(n, v) for n, v in seen]:
                    entry = (col.name, values)
                    if not any(v == values for _, v in seen):
                        seen.append(entry)
    return seen


def strip_checks(body):
    """Remove CHECK (...) spans -- they are multi-line and would otherwise be
    read as columns (the seed of a very confusing diagram)."""
    while True:
        m = re.search(r"CHECK\s*\(", body)
        if not m:
            return body
        head = body.rfind("CONSTRAINT", 0, m.start())
        line_start = body.rfind("\n", 0, m.start()) + 1
        cut_from = head if head >= line_start else m.start()
        j, depth = m.end(), 1
        while j < len(body) and depth:
            depth += (body[j] == "(") - (body[j] == ")")
            j += 1
        while j < len(body) and body[j] in ", \t":
            j += 1
        body = body[:cut_from] + body[j:]


def parse_schema(path):
    sql = open(path, encoding="utf-8").read()
    sql = re.sub(r"--[^\n]*", "", sql)                 # comments out
    tables = {}

    for m in re.finditer(r"CREATE TABLE\s+(\w+)\s*\((.*?)\n\)\s*ENGINE", sql, re.S):
        name, body = m.group(1), m.group(2)
        cols, pk_cols, uq_cols, fks = {}, [], set(), []

        # Foreign keys are written across two lines; read them first, then
        # take those spans out before reading one column per line.
        for fk in re.finditer(
                r"FOREIGN KEY\s*\(([^)]*)\)\s*REFERENCES\s+(\w+)\s*\(([^)]*)\)",
                body, re.S):
            fks.append((fk.group(1).strip(), fk.group(2), fk.group(3).strip()))
        body = re.sub(
            r"CONSTRAINT\s+\w+\s+FOREIGN KEY\s*\([^)]*\)\s*REFERENCES\s+\w+\s*"
            r"\([^)]*\)[^,]*", "", body, flags=re.S)
        body = strip_checks(body)

        for line in body.split("\n"):
            line = line.strip().rstrip(",")
            if not line:
                continue
            up = line.upper()
            if up.startswith("PRIMARY KEY"):
                pk_cols = [c.strip() for c in re.search(r"\((.*?)\)", line).group(1).split(",")]
            elif up.startswith("CONSTRAINT") and "FOREIGN KEY" in up:
                fk_col = re.search(r"FOREIGN KEY\s*\(([^)]*)\)", line).group(1).strip()
                ref = re.search(r"REFERENCES\s+(\w+)\s*\(([^)]*)\)", line)
                fks.append((fk_col, ref.group(1), ref.group(2).strip()))
            elif up.startswith("CONSTRAINT") and "UNIQUE" in up:
                inner = re.search(r"UNIQUE\s*\(([^)]*)\)", line)
                for c in inner.group(1).split(","):
                    uq_cols.add(c.strip())
            elif up.startswith("CONSTRAINT") or up.startswith("CHECK"):
                continue
            else:
                col = re.match(r"(\w+)\s+(.*)", line)
                if col:
                    c = Column(col.group(1), col.group(2))
                    if re.search(r"\bPRIMARY KEY\b", up):
                        pk_cols.append(c.name)
                    if re.search(r"\bUNIQUE\b", up):
                        c.uq = True
                    cols[c.name] = c
        for c in pk_cols:
            if c in cols:
                cols[c].pk = True
        for c in uq_cols:
            if c in cols:
                cols[c].uq = True
        for fk_col, ref_table, ref_col in fks:
            if fk_col in cols:
                cols[fk_col].fk = True
                cols[fk_col].ref = (ref_table, ref_col)
        tables[name] = dict(name=name, cols=cols, pk=pk_cols,
                            assoc=(len(pk_cols) > 1))
    return tables


def check(tables):
    """Every mismatch between the picture and the database, collected."""
    problems = []

    boxed, placed = set(PLACEMENT), set(tables)
    for name in sorted(placed - boxed):
        problems.append(f"table {name!r} is in the schema but has no placement")
    for name in sorted(boxed - placed):
        problems.append(f"placement for {name!r}, which is not a table")

    schema_fks, drawn = {}, {}
    for tname, t in tables.items():
        for cname, col in t["cols"].items():
            if col.fk:
                schema_fks[(tname, cname)] = col.ref[0]
    for (child, col), spec in RELATIONSHIPS.items():
        if (child, col) not in schema_fks:
            problems.append(f"edge {child}.{col} is drawn but is not a foreign key")
        else:
            drawn[(child, col)] = schema_fks[(child, col)]
    for key in sorted(schema_fks):
        if key not in drawn:
            problems.append(f"foreign key {key[0]}.{key[1]} is not drawn")
        elif PLACEMENT.get(key[0]) is None:
            problems.append(f"edge {key[0]}.{key[1]} has no box to start from")

    for name in ASSOCIATIVE:
        t = tables.get(name)
        if t is None:
            problems.append(f"associative entity {name!r} is missing")
        elif not t["assoc"]:
            problems.append(f"{name!r} does not have a composite key")
        elif not [c for c in t["cols"].values() if not c.pk]:
            problems.append(f"{name!r} has no attributes of its own")

    # geometry
    rects = {}
    for name, (x, y, w) in PLACEMENT.items():
        if name not in tables:
            continue
        rects[name] = (x, y, w, box_height(tables[name]))
    for name, (x, y, w, h) in rects.items():
        if x < MARGIN or y < 80 or x + w > W - MARGIN or y + h > H - MARGIN:
            problems.append(f"box {name!r} falls outside the canvas")
    names = sorted(rects)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            if overlaps(rects[a], rects[b]):
                problems.append(f"boxes {a!r} and {b!r} overlap")
    for (child, col), spec in RELATIONSHIPS.items():
        for point in spec["via"]:
            x, y = point
            if not (MARGIN <= x <= W - MARGIN and 80 <= y <= H - MARGIN):
                problems.append(f"waypoint {point} of {child}.{col} is off-canvas")
    return problems


def overlaps(a, b):
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    return ax < bx + bw and bx < ax + aw and ay < by + bh and by < ay + ah


# ---------------------------------------------------------------------------
# Drawing
# ---------------------------------------------------------------------------
def esc(t):
    return html.escape(str(t), quote=False)


HEAD_H = 30


def box_height(table):
    n = len(table["cols"])
    return HEAD_H + 8 + 17 * n


def box(name, table):
    x, y, w = PLACEMENT[name]
    h = box_height(table)
    assoc = table["assoc"]
    out = [
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="2" fill="white" '
        f'stroke="{INK}" stroke-width="1.3"/>',
        f'<path d="M{x},{y + HEAD_H} H{x + w}" stroke="{INK}" stroke-width="1.1"/>',
    ]
    if assoc:
        out.insert(
            0,
            f'<rect x="{x}" y="{y}" width="{w}" height="{HEAD_H}" rx="2" '
            f'fill="{ASSOC_FILL}" stroke="{INK}" stroke-width="0"/>')
        out.append(
            f'<text x="{x + w - 10}" y="{y + 20}" font-family="{FONT}" font-size="10" '
            f'font-style="italic" fill="{ACCENT}" text-anchor="end">associative</text>')
    else:
        out.insert(
            0,
            f'<rect x="{x}" y="{y}" width="{w}" height="{HEAD_H}" rx="2" '
            f'fill="{HEAD_FILL}" stroke="{INK}" stroke-width="0"/>')
    out.append(
        f'<text x="{x + 12}" y="{y + 20}" font-family="{FONT}" font-size="14" '
        f'font-weight="600" fill="{INK}">{esc(name)}</text>')

    yy = y + HEAD_H + 8
    for col in table["cols"].values():
        yy += 17
        tag = col.tag()
        if tag:
            out.append(
                f'<text x="{x + 10}" y="{yy - 4}" font-family="{FONT}" font-size="9" '
                f'fill="{MUTED}">{esc(tag)}</text>')
        weight = ' font-weight="600"' if col.pk else ""
        deco = ' text-decoration="underline"' if col.pk else ""
        out.append(
            f'<text x="{x + 62}" y="{yy - 4}" font-family="{FONT}" font-size="11.5"'
            f'{weight}{deco} fill="{INK}">{esc(col.name)}</text>')
        out.append(
            f'<text x="{x + w - 10}" y="{yy - 4}" font-family="{FONT}" font-size="10" '
            f'fill="{MUTED}" text-anchor="end">{esc(col.type)}</text>')
    return "\n".join(out)


def anchor(name, table, side, at):
    x, y, w = PLACEMENT[name]
    h = box_height(table)
    if side == "left":
        return (x, at)
    if side == "right":
        return (x + w, at)
    if side == "top":
        return (at, y)
    if side == "bottom":
        return (at, y + h)
    raise ValueError(side)


def marker_path(spec, tables):
    start = anchor(spec["start"][0], tables[spec["start"][0]],
                   spec["start"][1], spec["start"][2])
    end = anchor(spec["end"][0], tables[spec["end"][0]],
                 spec["end"][1], spec["end"][2])
    return start, end


def longest_midpoint(pts):
    """Midpoint of the longest segment -- where a label has room to sit."""
    best, best_len = pts[0], -1
    for a, b in zip(pts, pts[1:]):
        d = abs(a[0] - b[0]) + abs(a[1] - b[1])
        if d > best_len:
            best_len = d
            best = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
    return best


def relationship_svg(key, spec, tables):
    start, end = marker_path(spec, tables)
    pts = [start] + [tuple(p) for p in spec["via"]] + [end]
    d = f"M{pts[0][0]},{pts[0][1]} " + " ".join(f"L{p[0]},{p[1]}" for p in pts[1:])
    dash = ' stroke-dasharray="6 4"' if spec.get("optional") else ""
    out = [
        f'<path d="{d}" fill="none" stroke="{RULE}" stroke-width="1.2"{dash} '
        f'marker-start="url(#one)" marker-end="url(#crow)"/>'
    ]
    label = spec.get("label")
    if label:
        mid = longest_midpoint(pts)
        out.append(
            f'<rect x="{mid[0] - 3.1 * len(label) - 4:.0f}" y="{mid[1] - 13:.0f}" '
            f'width="{6.2 * len(label) + 8:.0f}" height="15" fill="{PAGE}" '
            f'opacity="0.9"/>'
            f'<text x="{mid[0]:.0f}" y="{mid[1]:.0f}" font-family="{FONT}" '
            f'font-size="10.5" fill="{MUTED}" text-anchor="middle">{esc(label)}</text>')
    return "\n".join(out)


DEFS = f"""
<defs>
  <marker id="crow" viewBox="0 0 14 14" refX="13" refY="7" markerWidth="13"
          markerHeight="13" orient="auto">
    <path d="M12,7 L1,1 M12,7 L1,7 M12,7 L1,13" fill="none" stroke="{RULE}"
          stroke-width="1.1"/>
  </marker>
  <marker id="one" viewBox="0 0 14 14" refX="7" refY="7" markerWidth="13"
          markerHeight="13" orient="auto">
    <path d="M7,1 L7,13" fill="none" stroke="{RULE}" stroke-width="1.3"/>
  </marker>
</defs>
"""

LEGEND = f"""
<g>
  <rect x="60" y="900" width="330" height="118" rx="2" fill="white" stroke="{INK}"
        stroke-width="1.1"/>
  <path d="M60,930 H390" stroke="{INK}" stroke-width="1"/>
  <text x="72" y="920" font-family="{FONT}" font-size="12" font-weight="600"
        fill="{INK}">How to read this</text>
  <g transform="translate(74,952)">
    <path d="M0,0 L34,0" stroke="{RULE}" stroke-width="1.2" marker-start="url(#one)"
          marker-end="url(#crow)"/>
    <text x="44" y="4" font-family="{FONT}" font-size="10.5" fill="{INK}">one &#8594; many</text>
  </g>
  <g transform="translate(74,976)">
    <path d="M0,0 L34,0" stroke="{RULE}" stroke-width="1.2" stroke-dasharray="6 4"
          marker-start="url(#one)" marker-end="url(#crow)"/>
    <text x="44" y="4" font-family="{FONT}" font-size="10.5" fill="{INK}">optional many (NULL allowed)</text>
  </g>
  <text x="74" y="1002" font-family="{FONT}" font-size="10.5" fill="{MUTED}">PK key &#183; FK foreign key &#183; UQ unique</text>
</g>
"""


def subtitle(tables):
    fks = sum(1 for t in tables.values() for c in t["cols"].values() if c.fk)
    return (f"{len(tables)} entities \u00b7 {fks} foreign keys \u00b7 crow's-foot "
            f"notation \u00b7 generated from db/01_schema.sql")


def svg(tables):
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
             f'viewBox="0 0 {W} {H}">',
             f'<rect width="{W}" height="{H}" fill="{PAGE}"/>', DEFS,
             f'<text x="40" y="46" font-family="{FONT}" font-size="22" '
             f'font-weight="700" fill="{INK}">{esc(TITLE)}</text>',
             f'<text x="{W - 40}" y="46" font-family="{FONT}" font-size="12" '
             f'fill="{MUTED}" text-anchor="end">{esc(subtitle(tables))}</text>',
             f'<path d="M40,60 H{W - 40}" stroke="{INK}" stroke-width="1"/>']
    for key in sorted(RELATIONSHIPS):
        parts.append(relationship_svg(key, RELATIONSHIPS[key], tables))
    for name in sorted(PLACEMENT):
        parts.append(box(name, tables[name]))
    parts.append(LEGEND)
    domains = "  ·  ".join(
        f"{col} ({', '.join(values)})" for col, values in enum_domains(tables))
    parts.append(
        f'<text x="40" y="{H - 46}" font-family="{FONT}" font-size="10.5" '
        f'fill="{MUTED}">ENUM(n) values — {esc(domains)}</text>')
    parts.append(
        f'<text x="40" y="{H - 24}" font-family="{FONT}" font-size="10.5" '
        f'fill="{MUTED}">Both bridge tables carry attributes of their own: '
        f'ticket.price, booking_concession.quantity and unit_price. Without those, '
        f'they would be links, not entities.</text>')
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


# ---------------------------------------------------------------------------
# drawio
# ---------------------------------------------------------------------------
def drawio(tables):
    cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>']
    for name in sorted(PLACEMENT):
        x, y, w = PLACEMENT[name]
        t = tables[name]
        rows = []
        for col in t["cols"].values():
            tag = f"{col.tag()} " if col.tag() else ""
            star = "&#9733; " if col.pk else ""
            rows.append(f"{star}<b>{esc(col.name)}</b> : {esc(col.type)}"
                        if col.pk else
                        f"{tag}{esc(col.name)} : {esc(col.type)}")
        # The label is HTML (drawio renders it), so the markup itself has to
        # be XML-escaped before it goes in the attribute.
        label = (f"<b>{esc(name)}</b>" +
                 (" <i>associative</i>" if t["assoc"] else "") +
                 "<hr>" + "<br>".join(rows))
        value = html.escape(label, quote=True)
        style = ("rounded=0;whiteSpace=wrap;html=1;align=left;verticalAlign=top;"
                 "spacingLeft=8;spacingTop=4;fontSize=11;"
                 + ("fillColor=#EFE2DC;" if t["assoc"] else "fillColor=#E7E4DA;"))
        cells.append(
            f'<mxCell id="{name}" value="{value}" style="{style}" vertex="1" parent="1">'
            f'<mxGeometry x="{x}" y="{y}" width="{w}" height="{box_height(t)}" '
            f'as="geometry"/></mxCell>')
    for key in sorted(RELATIONSHIPS):
        spec = RELATIONSHIPS[key]
        child, col = key
        parent = spec["start"][0]
        eid = f"e_{child}_{col}"
        style = ("edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;"
                 "startArrow=ERone;startFill=0;endArrow=ERmany;endFill=0;"
                 + ("dashed=1;" if spec.get("optional") else ""))
        cells.append(
            f'<mxCell id="{eid}" value="{esc(spec.get("label", ""))}" '
            f'style="{style}" edge="1" parent="1" source="{parent}" target="{child}">'
            f'<mxGeometry relative="1" as="geometry"/></mxCell>')
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<mxfile host="app.diagrams.net" type="device">\n'
            '  <diagram id="cinebook-er" name="CineBook ER">\n'
            f'    <mxGraphModel dx="1400" dy="900" grid="0" gridSize="10" guides="1" '
            f'page="1" pageWidth="{W}" pageHeight="{H}" math="0" shadow="0">\n'
            '      <root>\n        '
            + "\n        ".join(cells) +
            '\n      </root>\n    </mxGraphModel>\n  </diagram>\n</mxfile>\n')


# ---------------------------------------------------------------------------
# ERDPlus build sheet
# ---------------------------------------------------------------------------
CARDINALITY_NOTE = {
    ("showtime", "movie_id"): "1 : N",
    ("showtime", "screen_id"): "1 : N",
    ("seat", "screen_id"): "1 : N",
    ("booking", "showtime_id"): "1 : N",
    ("booking", "customer_id"): "1 : N",
    ("booking", "sold_by_staff_id"): "1 : N",
    ("booking", "cancelled_by_staff_id"): "0..1 : N",
    ("ticket", "booking_id"): "1 : N (M : N end)",
    ("ticket", "showtime_id"): "1 : N",
    ("ticket", "seat_id"): "1 : N (M : N end)",
    ("booking_concession", "booking_id"): "1 : N (M : N end)",
    ("booking_concession", "item_id"): "1 : N (M : N end)",
    ("payment", "booking_id"): "1 : 1",
    ("booking_audit", "booking_id"): "1 : N",
}


def erdplus(tables):
    out = [
        "# CineBook \u2014 ER diagram build sheet (ERDPlus)",
        "",
        "ERDPlus draws *diagram \u2192 DDL*: it has no SQL \u2192 diagram import, so this",
        "file cannot be generated for you. What it is instead is the complete list of",
        "boxes, fields, keys and relationships, in placement order, so that the diagram",
        "can be rebuilt by hand in about twenty minutes and its exported DDL can be",
        "diffed against `db/01_schema.sql`.",
        "",
        "The generated diagrams (`ER.svg`, `ER.png`, `ER.drawio`) remain the primary",
        "artifacts. This sheet exists so the examiner's \u201cwhich tool did you use?\u201d",
        "question has a second answer that also works.",
        "",
        f"Canvas: {W} \u00d7 {H} units, matching the SVG so the two can be compared side by side.",
        "",
        "## 1. Entities, in placement order",
        "",
        "| # | Entity | Place at (x, y) | Attributes | Keys |",
        "|---|---|---|---|---|",
    ]
    for i, name in enumerate(sorted(PLACEMENT, key=lambda n: (PLACEMENT[n][1], PLACEMENT[n][0])), 1):
        t = tables[name]
        x, y, w = PLACEMENT[name]
        attrs = ", ".join(t["cols"].keys())
        keys = []
        pk = ", ".join(c for c in t["cols"] if t["cols"][c].pk)
        fks = ", ".join(c for c in t["cols"] if t["cols"][c].fk)
        uqs = ", ".join(c for c in t["cols"] if t["cols"][c].uq)
        keys.append(f"PK: {pk}")
        if uqs:
            keys.append(f"UQ: {uqs}")
        if fks:
            keys.append(f"FK: {fks}")
        flag = " *associative*" if t["assoc"] else ""
        out.append(f"| {i} | **{name}**{flag} | ({x}, {y}) | {attrs} | {'; '.join(keys)} |")

    out += [
        "",
        "Draw each entity as a rectangle. Put the entity name in the header, then one",
        "row per attribute. Mark the primary key **PK**, a foreign key **FK**, and a",
        "unique column **UQ** \u2014 ERDPlus calls them *primary key*, *foreign key* and",
        "*constraint: unique*. Widths do not matter; leave 120\u2013200 units between",
        "boxes and the relationship lines will route themselves.",
        "",
        "## 2. Relationships",
        "",
        "Every row here is a real FOREIGN KEY in `db/01_schema.sql`. Crow's-foot end at",
        "the child (the table on the left of the arrow), a single bar at the parent.",
        "",
        "| Child (many end) | Column | Parent (one end) | Cardinality | Nullable |",
        "|---|---|---|---|---|",
    ]
    for (child, col), spec in sorted(RELATIONSHIPS.items()):
        parent = tables[child]["cols"][col].ref[0]
        out.append(
            f"| {child} | `{col}` | {parent} | {CARDINALITY_NOTE[(child, col)]} | "
            f"{'yes \u2014 draw a dashed line' if spec.get('optional') else 'no'} |")
    out += [
        "",
        "## 3. The two associations, which are the point of the diagram",
        "",
        "| Association | Resolved by | Own attributes | Why they are not just links |",
        "|---|---|---|---|",
        "| `booking` \u2194 `seat` | `ticket` | `price` | the price paid is a fact about "
        "that booking of that seat, not about either table |",
        "| `booking` \u2194 `concession_item` | `booking_concession` | `quantity`, "
        "`unit_price` | how many, and at what price |",
        "",
        "Both are drawn as entities with their own attributes and a composite primary",
        "key. An association without attributes is a link; with them it is an entity,",
        "and that difference is the most common thing marked wrong in student ER",
        "diagrams.",
        "",
        "## 4. Reproducing it without ERDPlus",
        "",
        "```bash",
        "python tools/erdiagram.py        # rewrites ER.svg, ER.drawio, ER.png, this file",
        "```",
        "",
        "Open `docs/ER.drawio` at <https://app.diagrams.net> for a fully editable copy",
        "with no install, or `docs/ER.svg` for the vector version the report embeds.",
        "The script refuses to run if the schema and the diagram disagree, so the only",
        "way to have a stale diagram is to not run it.",
        "",
    ]
    return "\n".join(out)


# ---------------------------------------------------------------------------
# PNG, via headless Edge
# ---------------------------------------------------------------------------
EDGE_PATHS = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    "/usr/bin/microsoft-edge",
    "/usr/bin/google-chrome",
    "msedge",
    "google-chrome",
]


def find_browser():
    for p in EDGE_PATHS:
        if os.path.sep in p:
            if os.path.exists(p):
                return p
        else:
            found = shutil.which(p)
            if found:
                return found
    return None


def render_png(png_path, svg_name):
    browser = find_browser()
    if not browser:
        return "no Edge or Chrome on PATH \u2014 skipped (the SVG is still current)"
    wrapper = os.path.join(OUT, "_er_render.html")
    with open(wrapper, "w", encoding="utf-8") as fh:
        fh.write(
            "<!doctype html><meta charset='utf-8'>"
            "<style>html,body{margin:0;padding:0;background:#fff}"
            f"img{{display:block;width:{W}px;height:{H}px}}</style>"
            f"<img src='{svg_name}'>")
    url = "file:///" + os.path.abspath(wrapper).replace("\\", "/")
    # A throwaway profile: without one, a running Edge instance swallows the
    # command line and --screenshot writes nothing at all.
    profile = tempfile.mkdtemp(prefix="er-edge-")
    cmd = [browser, "--headless", "--disable-gpu", "--hide-scrollbars",
           "--no-first-run", "--no-default-browser-check",
           f"--user-data-dir={profile}",
           "--force-device-scale-factor=2",
           f"--screenshot={os.path.abspath(png_path)}",
           f"--window-size={W},{H}", url]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    except Exception as exc:                                  # pragma: no cover
        return f"Edge failed: {exc}"
    finally:
        if os.path.exists(wrapper):
            os.remove(wrapper)
        shutil.rmtree(profile, ignore_errors=True)
    if not os.path.exists(png_path):
        tail = (proc.stderr or "").strip().splitlines()[-1:] or ["no output"]
        return f"Edge produced no PNG ({tail[0]})"
    return f"{os.path.getsize(png_path) // 1024} KB"


def png_size(path):
    with open(path, "rb") as fh:
        head = fh.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return (int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big"))


# ---------------------------------------------------------------------------
def main():
    want_png = "--no-png" not in sys.argv
    tables = parse_schema(SCHEMA)
    print(f"parsed {SCHEMA}: {len(tables)} tables")

    problems = check(tables)
    if problems:
        print("\nREFUSING TO DRAW \u2014 the diagram and the schema disagree:")
        for p in problems:
            print(f"  * {p}")
        return 1

    fks = sum(1 for t in tables.values() for c in t["cols"].values() if c.fk)
    print(f"cross-check: {len(PLACEMENT)} boxes, {fks} foreign keys, "
          f"{len(RELATIONSHIPS)} edges, associative: {', '.join(ASSOCIATIVE)}")
    print("cross-check: every table boxed, every FK drawn, no overlap, nothing off-canvas")

    os.makedirs(OUT, exist_ok=True)
    written = []
    for name, text in [("ER.svg", svg(tables)),
                       ("ER.drawio", drawio(tables)),
                       ("ERDPlus.md", erdplus(tables))]:
        path = os.path.join(OUT, name)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        written.append(f"wrote {os.path.abspath(path)} ({len(text)} bytes)")

    if want_png:
        png = os.path.join(OUT, "ER.png")
        note = render_png(png, "ER.svg")
        size = png_size(png) if os.path.exists(png) else None
        if size:
            written.append(f"wrote {os.path.abspath(png)} "
                           f"({note}, {size[0]} x {size[1]} px)")
            if size[0] < 2000:
                written.append(f"  WARNING: PNG is only {size[0]} px wide")
        else:
            written.append(f"ER.png: {note}")
    for line in written:
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
