#!/usr/bin/env python3
"""
Generates docs/SCHEMA.md - the data dictionary - directly from db/01_schema.sql,
so the document cannot drift from the database.

  python tools/schemadoc.py            # regenerates docs/SCHEMA.md
  python tools/schemadoc.py --check    # regenerate into memory and diff only

The parser refuses to write anything if it cannot classify every item in every
CREATE TABLE, if a foreign key points at a table or column that does not exist,
if a CHECK constraint names a column of a different table, if an index names a
missing column, or if a table's numbered comment header disagrees with the table
itself.  Every failure is reported; nothing is silently skipped.

No third-party dependencies.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
SRC = os.path.join(ROOT, "db", "01_schema.sql")
OUT = os.path.join(ROOT, "docs", "SCHEMA.md")

SQL_KEYWORDS = {
    "and", "or", "is", "not", "null", "between", "in", "like", "true", "false",
    "char_length", "current_timestamp", "coalesce", "greatest", "least",
}


class SchemaError(Exception):
    pass


def strip_comment(line):
    """Return (code, comment) with the '--' comment split off outside quotes."""
    inq = False
    i = 0
    while i < len(line):
        ch = line[i]
        if ch == "'":
            inq = not inq
        if not inq and line[i:i + 2] == "--":
            return line[:i].rstrip(), line[i + 2:].strip()
        i += 1
    return line.rstrip(), ""


def split_top(text, sep=","):
    """Split on a separator at paren depth 0, outside single quotes."""
    parts, cur, depth, inq = [], [], 0, False
    for ch in text:
        if ch == "'":
            inq = not inq
        if not inq:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            elif ch == sep and depth == 0:
                parts.append("".join(cur).strip())
                cur = []
                continue
        cur.append(ch)
    if "".join(cur).strip():
        parts.append("".join(cur).strip())
    return parts


def tidy(sql):
    return " ".join(str(sql).split())


# ---------------------------------------------------------------------------
# parser
# ---------------------------------------------------------------------------

def parse(path):
    with open(path, encoding="utf-8") as fh:
        raw = fh.read()

    tables = {}
    order = []
    indexes = []
    notes = []          # comment lines accumulated before the next construct

    lines = raw.splitlines()
    i = 0
    while i < len(lines):
        code, note = strip_comment(lines[i])
        s = " ".join(code.split())

        m = re.match(r"^CREATE TABLE (\w+) \($", s)
        if m:
            name = m.group(1)
            body_lines = []
            j = i + 1
            while j < len(lines) and not lines[j].startswith(") ENGINE"):
                body_lines.append(lines[j])
                j += 1
            if j >= len(lines):
                raise SchemaError(f"{name}: CREATE TABLE block is not closed")
            tables[name] = {"notes": notes, "body": "\n".join(body_lines),
                            "columns": [], "constraints": []}
            order.append(name)
            notes = []
            i = j + 1
            continue

        m = re.match(r"^CREATE INDEX (\w+) ON (\w+) \((.+)\);$", s)
        if m:
            indexes.append((m.group(1), m.group(2), split_top(m.group(3))))
            i += 1
            continue

        if note and not s:
            notes.append(note)
        elif s:
            # DROP/CREATE DATABASE, USE, plain SQL we do not model: must not
            # appear inside a table, which the block reader already guarantees.
            notes = []
        i += 1

    if not tables:
        raise SchemaError("no CREATE TABLE found - parser is broken or file moved")

    for name, t in tables.items():
        parse_table(name, t)

    return order, tables, indexes


def parse_table(name, t):
    # numbered header line, e.g. "-- 12. BOOKING_AUDIT  --  written by a trigger"
    purpose, kept = None, []
    for n in t["notes"]:
        if set(n) <= {"-"}:
            continue                                    # rule line
        m = re.match(r"^\d+\.\s+(\w+)\s+--\s*(.*)$", n)
        if m and purpose is None:
            if m.group(1).lower() != name.lower():
                raise SchemaError(
                    f"comment header names table '{m.group(1)}' above '{name}'")
            purpose = m.group(2).strip()
        else:
            kept.append(n)
    t["purpose"] = purpose or ""
    t["notes"] = kept

    body, pend = t["body"], []
    cur, depth, inq = [], 0, False

    def finish():
        item = " ".join("".join(cur).split())
        if item:
            t["_raw"].append((item, [p for p in pend if p]))
        cur.clear()
        pend.clear()

    t["_raw"] = []
    for raw_line in body.splitlines():
        code, note = strip_comment(raw_line)
        if note:
            pend.append(note)
        for ch in code:
            if ch == "'":
                inq = not inq
            if not inq:
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                elif ch == "," and depth == 0:
                    finish()
                    continue
            cur.append(ch)
        cur.append("\n")
    finish()

    for item, inotes in t["_raw"]:
        classify(name, item, inotes, t)

    inline_pk = [c["name"] for c in t["columns"] if c.get("pk")]
    if len(inline_pk) > 1:
        raise SchemaError(f"{name}: several inline PRIMARY KEY columns: {inline_pk}")
    has_pk = any(c["kind"] == "PRIMARY KEY" for c in t["constraints"])
    if inline_pk and has_pk:
        raise SchemaError(f"{name}: PRIMARY KEY declared twice (inline and table-level)")
    if inline_pk:
        t["constraints"].insert(0, {
            "name": None, "kind": "PRIMARY KEY", "cols": inline_pk,
            "text": f"PRIMARY KEY ({inline_pk[0]}) (declared inline)",
            "notes": [], "inline": True})


def classify(table, item, inotes, t):
    item = tidy(item)

    m = re.match(r"^PRIMARY KEY \((.+)\)$", item)
    if m:
        t["constraints"].append(
            {"name": None, "kind": "PRIMARY KEY", "cols": split_top(m.group(1)),
             "text": item, "notes": inotes})
        return

    m = re.match(r"^CONSTRAINT (\w+) FOREIGN KEY \((.+?)\) REFERENCES (\w+) "
                 r"\((.+?)\)(.*)$", item, re.S)
    if m:
        actions = re.findall(r"ON (DELETE|UPDATE) (\w+)", m.group(5))
        t["constraints"].append(
            {"name": m.group(1), "kind": "FOREIGN KEY", "cols": split_top(m.group(2)),
             "ref_table": m.group(3), "ref_cols": split_top(m.group(4)),
             "actions": [f"ON {a} {b}" for a, b in actions], "text": item,
             "notes": inotes})
        return

    m = re.match(r"^CONSTRAINT (\w+) CHECK \((.*)\)$", item, re.S)
    if m:
        t["constraints"].append(
            {"name": m.group(1), "kind": "CHECK", "expr": tidy(m.group(2)),
             "text": item, "notes": inotes})
        return

    m = re.match(r"^CONSTRAINT (\w+) UNIQUE \((.+)\)$", item)
    if m:
        t["constraints"].append(
            {"name": m.group(1), "kind": "UNIQUE", "cols": split_top(m.group(2)),
             "text": item, "notes": inotes})
        return

    m = re.match(r"^(\w+)\s+([A-Za-z]+(?:\s*\([^)]*\))?)\s*(.*)$", item, re.S)
    if m and not item.upper().startswith("CONSTRAINT"):
        rest = m.group(3)
        default = re.search(r"DEFAULT\s+('[^']*'|[A-Za-z0-9_.]+)", rest)
        pk_inline = bool(re.search(r"\bPRIMARY KEY\b", rest.upper()))
        t["columns"].append({
            "name": m.group(1), "type": tidy(m.group(2)),
            "not_null": "NOT NULL" in rest.upper() or pk_inline,
            "default": default.group(1) if default else None,
            "auto": "AUTO_INCREMENT" in rest.upper(),
            "pk": pk_inline,
            "notes": inotes, "table": table,
        })
        return

    raise SchemaError(f"{table}: cannot classify item: {item!r}")


# ---------------------------------------------------------------------------
# validation - all of it must pass before a byte is written
# ---------------------------------------------------------------------------

def validate(order, tables, indexes):
    problems = []

    def cols_of(table, col):
        return any(c["name"] == col for c in tables[table]["columns"])

    for name in order:
        t = tables[name]
        if not t["columns"]:
            problems.append(f"{name}: no columns parsed")

        pk_cols = []
        for c in t["constraints"]:
            if c["kind"] == "PRIMARY KEY":
                pk_cols = c["cols"]
            elif c["kind"] == "FOREIGN KEY":
                for col in c["cols"]:
                    if not cols_of(name, col):
                        problems.append(f"{name}.{c['name']}: no such column {col}")
                rt = c["ref_table"]
                if rt not in tables:
                    problems.append(f"{name}.{c['name']}: references missing table {rt}")
                else:
                    for col in c["ref_cols"]:
                        if not cols_of(rt, col):
                            problems.append(f"{name}.{c['name']}: {rt} has no column {col}")
            elif c["kind"] == "CHECK":
                expr = re.sub(r"'[^']*'", " ", c["expr"])
                for ident in re.findall(r"[A-Za-z_][A-Za-z0-9_]*", expr):
                    low = ident.lower()
                    if low in SQL_KEYWORDS:
                        continue
                    if cols_of(name, low):
                        continue
                    if any(cols_of(other, low) for other in tables if other != name):
                        problems.append(
                            f"{name}.{c['name']}: CHECK names a column of another table: {ident}")
                    else:
                        problems.append(
                            f"{name}.{c['name']}: unknown identifier in CHECK: {ident}")
            elif c["kind"] == "UNIQUE":
                for col in c["cols"]:
                    if not cols_of(name, col):
                        problems.append(f"{name}.{c['name']}: no such column {col}")

        for col in pk_cols:
            if not cols_of(name, col):
                problems.append(f"{name}: PRIMARY KEY names missing column {col}")

        ai = [c for c in t["columns"] if c["auto"]]
        if len(ai) > 1:
            problems.append(f"{name}: more than one AUTO_INCREMENT column")
        if ai and pk_cols and ai[0]["name"] not in pk_cols:
            problems.append(f"{name}: AUTO_INCREMENT column {ai[0]['name']} is not in the PK")

    for idx, table, cols in indexes:
        if table not in tables:
            problems.append(f"index {idx}: missing table {table}")
        else:
            for col in cols:
                if not cols_of(table, col):
                    problems.append(f"index {idx}: {table} has no column {col}")

    return problems


# ---------------------------------------------------------------------------
# markdown
# ---------------------------------------------------------------------------

def esc(text):
    return str(text).replace("|", "\\|")


def render(order, tables, indexes):
    total_cols = sum(len(tables[n]["columns"]) for n in order)
    total_con = sum(len(tables[n]["constraints"]) for n in order)
    out = []
    w = out.append

    w("# CineBook \u2014 data dictionary\n")
    w("> **Generated file.** Produced by `tools/schemadoc.py` straight from\n"
      "> `db/01_schema.sql`. Do not edit this by hand \u2014 edit the SQL and rerun\n"
      "> `python tools/schemadoc.py`. The script refuses to write if any table,\n"
      "> column, key or constraint cannot be classified and resolved.\n")
    w(f"**{len(order)} tables \u00b7 {total_cols} columns \u00b7 {total_con} "
      f"constraints \u00b7 {len(indexes)} secondary indexes**\n")
    w("Views, the audit trigger and the confirmation procedure live in\n"
      "`db/03_objects.sql` and are documented in that file's own comments.\n")

    w("\n## Foreign-key graph\n")
    w("| Parent | Children (delete action) |")
    w("|---|---|")
    parents = []
    for name in order:
        for c in tables[name]["constraints"]:
            if c["kind"] == "FOREIGN KEY":
                parents.append((c["ref_table"], name, c))
    for parent in order:
        kids = [(n, c) for p, n, c in parents if p == parent]
        if not kids:
            continue
        cell = ", ".join(
            f"`{n}`.`{'`, `'.join(c['cols'])}` "
            f"(del {next(a.split()[2] for a in c['actions'] if a.startswith('ON DELETE'))})"
            for n, c in kids)
        w(f"| `{parent}` | {cell} |")
    w("")

    for n, name in enumerate(order, 1):
        t = tables[name]
        w(f"\n## {n}. `{name}`")
        if t["purpose"]:
            w(f"\n{t['purpose']}\n")
        else:
            w("")
        if t["notes"]:
            w(" ".join(t["notes"]))
            w("")

        uq_single = {c["cols"][0] for c in t["constraints"]
                     if c["kind"] == "UNIQUE" and len(c["cols"]) == 1}
        uq_comp = {col for c in t["constraints"]
                   if c["kind"] == "UNIQUE" and len(c["cols"]) > 1
                   for col in c["cols"]}

        w("| Column | Type | Null | Default | Key | Notes |")
        w("|---|---|---|---|---|---|")
        pk = next((c["cols"] for c in t["constraints"] if c["kind"] == "PRIMARY KEY"), [])
        for c in t["columns"]:
            keys = []
            if c["name"] in pk:
                keys.append("PK" + (", AI" if c["auto"] else ""))
            fks = [x for x in t["constraints"]
                   if x["kind"] == "FOREIGN KEY" and c["name"] in x["cols"]]
            keys += [f"\u2192 `{x['ref_table']}`" for x in fks]
            if c["name"] not in pk:
                if c["name"] in uq_single:
                    keys.append("UQ")
                if c["name"] in uq_comp:
                    keys.append("UQ*")
            w(f"| `{c['name']}` | {c['type']} | "
              f"{'NOT NULL' if c['not_null'] else 'NULL'} | "
              f"{esc(c['default']) if c['default'] else '\u2014'} | "
              f"{' '.join(keys) if keys else '\u2014'} | "
              f"{esc('; '.join(c['notes'])) if c['notes'] else ''} |")
        if uq_comp:
            w("\n\\* part of a composite `UNIQUE` constraint \u2014 the combination is "
              "unique, not the column alone.")
        w("")

        w("| Constraint | Kind | Definition |")
        w("|---|---|---|")
        for c in t["constraints"]:
            if c["name"]:
                cname = f"`{c['name']}`"
            elif c.get("inline"):
                cname = "\u2014 (inline PK)"
            else:
                cname = "\u2014 (table PK)"
            if c["kind"] == "FOREIGN KEY":
                definition = (f"(`{'`, `'.join(c['cols'])}`) \u2192 "
                              f"`{c['ref_table']}`(`{'`, `'.join(c['ref_cols'])}`)"
                              + (f" \u00b7 {' '.join(c['actions'])}" if c["actions"] else ""))
            elif c["kind"] == "CHECK":
                definition = esc(c["expr"])
                if c["notes"]:
                    definition += f" \u00b7 \u2014 {esc('; '.join(c['notes']))}"
            else:
                definition = "(`" + "`, `".join(c["cols"]) + "`)"
            w(f"| {cname} | {c['kind']} | {definition} |")
        w("")

    w("\n## Secondary indexes\n")
    w("A foreign key already indexes the child column, so these are only the\n"
      "lookups the reports actually filter or sort on.\n")
    w("| Index | Table | Columns |")
    w("|---|---|---|")
    for idx, table, cols in indexes:
        w(f"| `{idx}` | `{table}` | `{'`, `'.join(cols)}` |")
    w("")

    return "\n".join(out)


def main():
    try:
        order, tables, indexes = parse(SRC)
    except SchemaError as exc:
        print(f"REFUSING TO WRITE - {exc}")
        return 1
    problems = validate(order, tables, indexes)
    if problems:
        print("REFUSING TO WRITE - schema validation failed:")
        for p in problems:
            print("  -", p)
        return 1

    doc = render(order, tables, indexes)

    if "--check" in sys.argv:
        try:
            with open(OUT, encoding="utf-8") as fh:
                current = fh.read()
        except FileNotFoundError:
            print("docs/SCHEMA.md is missing - run: python tools/schemadoc.py")
            return 1
        if current != doc:
            print("docs/SCHEMA.md is STALE - run: python tools/schemadoc.py")
            return 1
        print("docs/SCHEMA.md is up to date")
        return 0

    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(doc)
    cols = sum(len(tables[n]["columns"]) for n in order)
    print(f"wrote {os.path.abspath(OUT)} "
          f"({len(order)} tables, {cols} columns, {len(indexes)} indexes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
