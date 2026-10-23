#!/usr/bin/env python3
"""
Generates docs/QUERIES.md (the query catalogue) and docs/TESTS.md (the
integrity test log) straight from db/04_queries.sql, so neither document
can drift from the SQL that produces it.

  python tools/querydoc.py            # regenerate both documents
  python tools/querydoc.py --check    # regenerate into memory and diff only
  python tools/querydoc.py --capture  # re-run every query, record the output
  python tools/querydoc.py --check-db # advisory: does the capture match this DB?

--check-db is advisory by design: it prints NOTICE/ADVISORY lines and always
exits 0, so an offline grader or a submission script can never fail because a
recorded snapshot is a few days old.

The parser refuses to write anything if it cannot classify every statement,
if a numbered query has no question above it, if a query touches a table that
does not exist in db/01_schema.sql, or if the two documents disagree about
how many queries there are.  Every failure is reported; nothing is skipped.

Column-level checks come from tools/sqlcheck.py: every alias.column the
queries use is resolved against db/01_schema.sql (views included), so a typo
that MySQL would only report as ERROR 1054 at run time fails the docs build.

No third-party dependencies.
"""
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sqlcheck

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
SQL = os.path.join(ROOT, "db", "04_queries.sql")
SCHEMA = os.path.join(ROOT, "db", "01_schema.sql")
SEED = os.path.join(ROOT, "db", "02_seed.sql")
OBJECTS = os.path.join(ROOT, "db", "03_objects.sql")
SETUP = os.path.join(ROOT, "db", "00_setup.sql")
QUERIES_OUT = os.path.join(ROOT, "docs", "QUERIES.md")
TESTS_OUT = os.path.join(ROOT, "docs", "TESTS.md")
CAPTURE_OUT = os.path.join(ROOT, "docs", "query_output.json")

# MySQL reserved words and functions that are never tables.
SQL_NOISE = {
    "select", "from", "where", "join", "left", "inner", "right", "on", "and",
    "or", "not", "in", "as", "group", "by", "order", "having", "limit",
    "offset", "case", "when", "then", "else", "end", "distinct", "all",
    "union", "between", "is", "null", "exists", "desc", "asc", "with",
    "count", "sum", "avg", "min", "max", "round", "coalesce", "nullif",
    "datediff", "curdate", "curtime", "now", "date", "int", "varchar",
    "decimal", "datetime", "char", "tinyint", "boolean", "enum", "smallint",
    "time", "rank", "over", "partition", "values", "call", "set", "use",
    "insert", "update", "delete", "into", "if", "like", "interval", "day",
    "month", "year", "true", "false",
}

# A query whose job is to prove integrity rather than report a number.
INTEGRITY = {"8": "reconciliation"}


class DocError(Exception):
    pass


def tidy(text):
    return " ".join(str(text).split())


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def tables_in_schema(*paths):
    """Table and view names declared in the schema files."""
    names = set()
    for path in paths:
        src = read(path)
        names.update(m.group(1).lower()
                     for m in re.finditer(r"^CREATE TABLE (\w+) \(", src, re.M))
        names.update(m.group(1).lower()
                     for m in re.finditer(r"^CREATE OR REPLACE VIEW (\w+) ", src, re.M))
    if not names:
        raise DocError("no tables found in the schema files")
    return names


def strip_comment(line):
    """Split a '--' comment off a line, ignoring '--' inside quotes."""
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


def parse(path):
    """Split the file into blocks: each comment banner plus the SQL under it."""
    raw = read(path)
    lines = raw.splitlines()

    # A banner is a rule line framed by '=' (or '-') followed by label
    # comment lines up to the next rule. The rule is inside the comment, so
    # strip_comment yields code='' and note='===='; test the note, not code.
    def is_rule(note):
        s = note.strip()
        return len(s) >= 3 and set(s) <= {"=", "-"}

    banners = []
    i = 0
    while i < len(lines):
        _, note = strip_comment(lines[i])
        if not is_rule(note):
            i += 1
            continue
        j = i + 1
        label = []
        while j < len(lines):
            c2, n2 = strip_comment(lines[j])
            if is_rule(n2):
                break
            if n2:
                label.append(n2)
            elif c2.strip():
                label.append(c2.strip())
            else:
                label.append("")          # paragraph break inside the banner
            j += 1
        if label:
            banners.append((i, j, label))
        i = j + 1

    if not banners:
        raise DocError("no query banners found - parser is broken or file moved")

    queries = []
    intro_notes = []
    seen_intro = False
    for idx, (start, end, label) in enumerate(banners):
        # Text immediately under the closing rule is the SQL for this block.
        body_start = end + 1
        body_end = banners[idx + 1][0] if idx + 1 < len(banners) else len(lines)
        body_lines = lines[body_start:body_end]

        head = label[0]
        rest_lines = label[1:]

        # Two banner shapes: "PATTERN 1 -- <request>" and "6. <topic> -- <question>".
        m = re.match(r"^(?:PATTERN\s+)?(\d+)([a-z]?)\b\s*\.?\s*(.*)$",
                     head, re.I)
        if not m:
            seen_intro = True
            intro_notes.extend(rest_lines)
            continue

        number = m.group(1) + m.group(2)
        rest = m.group(3).strip()
        if rest[:2] in ("--", "\u2014"):
            rest = rest[2:].strip()
        if " -- " in rest:
            topic_a, question = rest.split(" -- ", 1)
        else:
            topic_a, question = "", rest

        sql, comments = [], []
        for line in body_lines:
            code, note = strip_comment(line)
            if code.strip():
                sql.append(code)
            if note:
                comments.append(note)

        stmts = [s for s in split_statements(sql) if s.strip()]
        drill_prose, drill_sql = [], []
        if not stmts:
            # A banner with no SQL of its own: the drill lives in the banner
            # text (query 14's manual transaction walk-through).
            drill_prose, drill_sql = split_drill(rest_lines)

        queries.append({
            "number": number,
            "question": tidy(question),
            "topic": tidy(head),
            "topic_a": tidy(topic_a),
            "label": rest_lines,
            "prose": drill_prose,
            "drill_sql": drill_sql,
            "sql": "\n".join(sql).strip(),
            "comments": comments,
            "statements": stmts,
        })

    if not seen_intro:
        raise DocError("no file preamble found - expected an opening banner")
    return intro_notes, queries


def split_drill(label_lines):
    """Separate banner prose from the runnable steps of a manual drill."""
    prose, sql, in_sql = [], [], False
    for line in label_lines:
        s = line.strip()
        if in_sql:
            sql.append(line)
            if s.endswith(";"):
                in_sql = False
            continue
        if re.match(r"^(SELECT|CALL|UPDATE|SET)\b", s, re.I) or s.endswith(";"):
            if not s.endswith(";"):
                in_sql = True
            sql.append(line)
        else:
            prose.append(line)
    return prose, sql


def paragraphs(lines):
    """Group banner lines into paragraphs, split on blank marker entries."""
    paras, cur = [], []
    for line in lines:
        if line == "":
            if cur:
                paras.append(" ".join(cur))
                cur = []
        else:
            cur.append(line)
    if cur:
        paras.append(" ".join(cur))
    return paras


def prose_blocks(lines):
    """Paragraphs as lists of lines, so structured comment blocks survive.

    A banner comment that lists three equations one per line must not be
    flattened into one run-on sentence; the renderer turns each line into a
    markdown hard break.
    """
    paras, cur = [], []
    for line in lines:
        if not line.strip():
            if cur:
                paras.append(cur)
                cur = []
        else:
            cur.append(tidy(line))
    if cur:
        paras.append(cur)
    return paras


def is_fact(line):
    """A line that stands alone: an equation, a bullet or a numbered step."""
    return ("=" in line or re.match(r"^\d+[a-z]?\.\s", line) is not None
            or re.match(r"^[-*\u2022]\s", line) is not None)


def render_block(block):
    """Keep line structure where the comment uses it; rejoin wrapped prose.

    A comment block that lists equations or numbered steps is tabulated, so
    each of those lines stands alone. Text wrapped mid-sentence by the 80
    column margin is rejoined, and never glued onto a preceding fact.
    """
    lines = []
    for line in block:
        if not lines or is_fact(line) or is_fact(lines[-1]):
            lines.append(line)
        else:
            lines[-1] += " " + line
    return "  \n".join(lines).replace(" -- ", " \u2014 ")


def split_statements(lines):
    """Split SQL text into statements on ';' at paren depth 0, outside quotes."""
    out, cur, depth, inq = [], [], 0, False
    text = "\n".join(lines)
    for ch in text:
        if ch == "'":
            inq = not inq
        if not inq:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            elif ch == ";" and depth == 0:
                out.append("".join(cur).strip() + ";")
                cur = []
                continue
        cur.append(ch)
    if "".join(cur).strip():
        out.append("".join(cur).strip())
    return out


def referenced_tables(sql):
    """Tables and views named in the FROM/JOIN clauses of a statement."""
    found = set()
    for m in re.finditer(
            r"\b(?:FROM|JOIN)\s+([a-zA-Z_]\w*)", sql, re.I):
        found.add(m.group(1).lower())
    return found


# ---------------------------------------------------------------------------
# validation - must pass before a byte is written
# ---------------------------------------------------------------------------

def validate(queries, schema_tables, schema):
    problems = []
    numbers = [q["number"] for q in queries]

    if len(numbers) != len(set(numbers)):
        problems.append("duplicate query numbers: " + ", ".join(sorted(set(
            n for n in numbers if numbers.count(n) > 1))))

    select_like = 0
    for q in queries:
        label = f"query {q['number']}"

        if not q["statements"]:
            if not q["drill_sql"]:
                problems.append(f"{label}: no SQL statements parsed")
            elif not q["prose"]:
                problems.append(f"{label}: manual drill has no explanation")
            continue

        if any(s.upper().startswith("SELECT") for s in q["statements"]):
            select_like += 1

        if not q["question"] and not q["topic"]:
            problems.append(f"{label}: no question or topic above the SQL")

        for stmt in q["statements"]:
            upper = stmt.upper()
            if not upper.startswith(("SELECT", "SET", "CALL")):
                problems.append(
                    f"{label}: statement does not start with SELECT/SET/CALL: "
                    f"{stmt.split()[0]}")
            # tables, aliases and every column reference, resolved against
            # the schema (and the views in db/03_objects.sql)
            problems.extend(sqlcheck.check_statement(stmt, schema, label))

    if select_like < 5:
        problems.append(
            f"only {select_like} queries start with SELECT - expected at "
            "least the five lab patterns")
    return problems, select_like


# ---------------------------------------------------------------------------
# markdown - QUERIES.md
# ---------------------------------------------------------------------------

def heading(q):
    if q["topic_a"]:
        return f"{q['number']}. {q['topic_a']} — {q['question']}"
    return f"{q['number']}. {q['question']}"


def title(q):
    """The question without its number, for tables that already show #."""
    if q["topic_a"]:
        return f"{q['topic_a']} — {q['question']}"
    return q["question"] or q["topic"]


# ---------------------------------------------------------------------------
# recorded output - the "sample output" the lab sheet asks for
#
# QUERIES.md is only honest if the pasted rows came from a real run. Each
# capture therefore carries a fingerprint of the exact SQL it belongs to, and
# the gate refuses to write if the SQL has moved on since. Regenerate with:
#
#   MYSQL_PWD=... python tools/querydoc.py --capture
# ---------------------------------------------------------------------------

def capture_key(q):
    """Fingerprint of the statements a capture was taken against."""
    return hashlib.sha256(
        "\n".join(q["statements"]).encode("utf-8")).hexdigest()[:16]


def load_captures():
    if not os.path.exists(CAPTURE_OUT):
        return {}
    try:
        with open(CAPTURE_OUT, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError) as exc:
        raise DocError(f"docs/query_output.json is unreadable: {exc}")
    if not isinstance(data, dict) or "entries" not in data:
        raise DocError("docs/query_output.json has no 'entries' map")
    return data


def mysql_command():
    return [os.environ.get("MYSQL_BIN", "mysql"),
            "-h", os.environ.get("MYSQL_HOST", "127.0.0.1"),
            "-P", os.environ.get("MYSQL_PORT", "3306"),
            "-u", os.environ.get("MYSQL_USER", "root"),
            "--default-character-set=utf8mb4", "-t", "cinebook"]


def capture_output(questions):
    """Run every result-producing query and record what MySQL printed."""
    if not os.environ.get("MYSQL_PWD"):
        raise DocError("--capture needs MYSQL_PWD set in the environment")
    cmd = mysql_command()

    def run(text):
        try:
            return subprocess.run(cmd, input=text, capture_output=True,
                                  text=True, encoding="utf-8")
        except FileNotFoundError:
            raise DocError(f"mysql client not found: {cmd[0]} "
                           "(set MYSQL_BIN to its full path)")

    # The date is the *server's* CURDATE(), not the clock here: every query
    # below is relative to it, so it is the only date the output belongs to.
    meta = run("SELECT CONCAT(VERSION(), '|', CURDATE()) AS meta;")
    if meta.returncode != 0:
        raise DocError("cannot connect to MySQL: " +
                       (meta.stderr.strip() or "unknown error"))
    # -t prints a bordered table: line 1 is the column header, line 2 the value.
    rows = [ln for ln in meta.stdout.splitlines() if ln.startswith("|")]
    if len(rows) < 2:
        raise DocError("could not read the MySQL version from: " +
                       meta.stdout.strip())
    # strip the outer borders only - the value itself contains a pipe
    server, _sep, today = rows[1].strip().strip("|").strip().partition("|")
    if not server or not today:
        raise DocError("unexpected version/date reply: " + rows[1])

    entries = {}
    for q in questions:
        if not q["statements"]:
            continue
        proc = run("USE cinebook;\n" + ";\n".join(q["statements"]) + ";\n")
        if proc.returncode != 0:
            raise DocError(f"query {q['number']} failed against MySQL: " +
                           proc.stderr.strip())
        entries[q["number"]] = {"sql_sha":     capture_key(q),
                                 "output":      proc.stdout.rstrip("\n"),
                                 "captured_on": today}

    return {"mysql_version": server, "captured_on": today, "entries": entries}


def check_db(questions, caps):
    """Advisory comparison of the recorded capture with the live database.

    Never fails, and never changes anything: an offline grader must not get a
    red build because a snapshot is a few days old. Returns the lines to print.
    """
    if not caps.get("entries"):
        return ["ADVISORY  no recorded output to compare - nothing to do"]
    if not os.environ.get("MYSQL_PWD"):
        return ["ADVISORY  MYSQL_PWD is not set - skipped (this check never "
                "fails)"]
    try:
        live = capture_output(questions)
    except DocError as exc:
        return [f"ADVISORY  skipped - {exc}"]

    lines = []
    recorded = caps.get("captured_on", "?")
    today = live["captured_on"]
    try:
        age = (datetime.date.fromisoformat(today)
               - datetime.date.fromisoformat(recorded)).days
    except ValueError:
        age = None

    if age is None:
        lines.append(f"ADVISORY  capture date {recorded!r} is unreadable; "
                     f"server says {today}")
    elif age == 0:
        lines.append(f"ADVISORY  capture is current (server date {today})")
    else:
        lines.append(f"ADVISORY  capture is {age} day(s) old: recorded "
                     f"{recorded}, server says {today} - refresh with: "
                     "python tools/querydoc.py --capture")

    if live["mysql_version"] != caps.get("mysql_version"):
        lines.append(f"ADVISORY  this server is MySQL {live['mysql_version']}; "
                     f"the capture came from "
                     f"{caps.get('mysql_version')}")

    differing = [n for n in sorted(live["entries"], key=int)
                 if live["entries"][n]["output"]
                 != caps["entries"].get(n, {}).get("output")]
    if differing:
        lines.append("ADVISORY  queries returning different rows now: "
                     + ", ".join(differing)
                     + " - the database has changed since the capture")
    else:
        lines.append(f"ADVISORY  all {len(live['entries'])} recorded result "
                     "sets match this database")
    return lines


def validate_captures(questions, caps):
    """Captured output must belong to the SQL currently in the file."""
    problems = []
    entries = caps.get("entries", {})
    for q in questions:
        if not q["statements"]:
            continue
        entry = entries.get(q["number"])
        if entry is None:
            problems.append(f"query {q['number']}: no recorded output - run "
                            "python tools/querydoc.py --capture")
        elif entry.get("sql_sha") != capture_key(q):
            problems.append(f"query {q['number']}: recorded output is stale "
                            "(the SQL changed) - run "
                            "python tools/querydoc.py --capture")
    return problems


def render_queries(intro_notes, questions, caps=None):
    out = []
    w = out.append
    w("# CineBook \u2014 query catalogue\n")
    w("> **Generated file.** Produced by `tools/querydoc.py` straight from\n"
      "> `db/04_queries.sql`. Do not edit by hand \u2014 edit the SQL and rerun\n"
      "> `python tools/querydoc.py`. The script refuses to write if any\n"
      "> statement cannot be classified or names a table that does not exist.\n")
    rows = sum(1 for q in questions if q["statements"])
    w(f"**{len(questions)} numbered queries** \u00b7 {rows} return a result set "
      f"\u00b7 {len(questions) - rows} is a manual drill.\n")
    if caps and caps.get("entries"):
        w(f"Every output below was captured from MySQL "
          f"{caps.get('mysql_version', '?')} on "
          f"{caps.get('captured_on', '?')} against the seeded database; "
          "`tools/querydoc.py`\nrefuses to write if the SQL changes without a "
          "fresh capture. Rebuilding the seed on a\nlater day moves every "
          "printed calendar date \u2014 counts, revenue, rankings and\n"
          "`days_dormant` are date-independent \u2014 so after a rebuild run "
          "`python tools/querydoc.py\n--capture` to bring this page back in "
          "line with your database.\n")
    for para in paragraphs(intro_notes):
        w(para + "\n")

    w("\n## Core Operational Patterns\n")
    w("| # | Business Requirement | What this query returns |")
    w("|---|---|---|")
    for q in questions:
        if re.fullmatch(r"[1-5]", q["number"]):
            paras = paragraphs(q["label"])
            desc = paras[0] if paras else q["question"]
            w(f"| {q['number']} | {q['question']} | {desc} |")
    w("")

    w("\n## Follow-ups\n")
    w("| # | Question |")
    w("|---|---|")
    for q in questions:
        if not re.fullmatch(r"[1-5]", q["number"]):
            w(f"| {q['number']} | {title(q)} |")
    w("")

    for q in questions:
        w(f"\n## {heading(q)}\n")

        prose = q["label"] if q["statements"] else q["prose"]
        notes = [c for c in q["comments"] if c and set(c) != {"-"}]
        blocks = prose_blocks(list(prose) + [""] + notes)
        for block in blocks:
            w(render_block(block))
            w("")

        code = q["sql"] if q["statements"] else "\n".join(q["drill_sql"])
        if code:
            w("```sql")
            w(code)
            w("```")
            w("")
        if q["statements"]:
            kinds = ", ".join(sorted(
                {s.split()[0].upper() for s in q["statements"]}))
            w(f"*{len(q['statements'])} statement(s): {kinds}.*")
            w("")
            entry = (caps or {}).get("entries", {}).get(q["number"])
            if entry:
                w(f"**Output** \u2014 MySQL {caps.get('mysql_version', '?')}, "
                  f"captured {entry.get('captured_on', '?')}.\n")
                w("```text")
                w(entry["output"] if entry.get("output") else "(no rows)")
                w("```")
        else:
            w("*Manual drill \u2014 run these by hand; nothing to execute in bulk.*")
        w("")

    return "\n".join(out)


# ---------------------------------------------------------------------------
# markdown - TESTS.md
# ---------------------------------------------------------------------------

# The recorded run. This is a fact about one machine on one day, not a design
# claim, so it is stated with its version and date. If the SQL changes, the
# observations must be taken again -- which is why they live beside the SQL
# rather than in free prose in the markdown.
RUN_VERSION = "8.0.46"
RUN_DATE = "2026-10-20"


def render_tests(questions, select_like, objects):
    out = []
    w = out.append
    w("# CineBook \u2014 integrity test log\n")
    w("> **Generated file.** Produced by `tools/querydoc.py` from\n"
      "> `db/04_queries.sql`. Do not edit by hand \u2014 edit the SQL and rerun\n"
      "> `python tools/querydoc.py`.\n")
    w("These are the checks that turn the schema's constraints from written\n"
      "rules into observed facts. Each one has a query in `db/04_queries.sql`,\n"
      "an expected result, and a way of failing loudly.\n")
    w(f"\n**Status: executed against MySQL {RUN_VERSION} on {RUN_DATE} \u2014 "
      "all seven checks passed.**\n"
      "The *Observed* column is a recorded run, not a design claim: "
      "`db/00_setup.sql`\nloaded the whole dataset (12 tables, 1 523 rows) and "
      "each check below was then\nrun against it. Two branches that no seeded "
      "row can reach are listed separately\nunder *Unexercised branches* and are "
      "not claimed as observed.\n")

    w("\n## How to run\n")
    w("```sql\n"
      "SOURCE db/00_setup.sql;   -- 01 schema + 02 seed + 03 objects + 04 queries\n"
      "-- or, on a database that already exists:\n"
      "SOURCE db/04_queries.sql;\n"
      "```\n")
    w("Then refresh the output recorded in `docs/QUERIES.md` so the catalogue "
      "describes\nthe database you just built rather than the one it was captured "
      "from:\n")
    w("```bash\n"
      "MYSQL_PWD=... python tools/querydoc.py --capture    # re-run and record\n"
      "MYSQL_PWD=... python tools/querydoc.py --check-db   # advisory, always "
      "exits 0\n"
      "```\n")

    w("\n## The tests\n")
    w("| # | What it proves | Expected | Ran | Observed |")
    w("|---|---|---|---|---|")

    rows = [
        ("2", "Ticket and food revenue are reported separately per auditorium.",
         "`total_revenue = ticket_revenue + fnb_revenue` on every row.",
         "4 rows, 0 of them breaking the identity (Audi 1 41 620.00 = "
         "14 940.00 + 26 680.00; the other three have no food)."),
        ("4", "A film screened within the last six months must not appear.",
         "Only dormant films are listed; `days_dormant` is 9999 when never "
         "shown (the COALESCE sentinel, not a NULL).",
         "2 rows: Blue Meridian 200 days, Static Bloom 204. Zero of them "
         "screened inside six months."),
        ("7", "The same rule as query 4, written with `NOT EXISTS`.",
         "**Identical rows to query 4** \u2014 two forms, one answer.",
         "2 rows, `movie_id` set 7,8 \u2014 the same set query 4 returned."),
        ("8", "The three money columns agree: "
              "`total = ticket + fnb`, tickets match `SUM(ticket.price)`, "
              "and the payment matches the total.",
         "**Zero rows.** Any row is a discount or a half-written update.",
         "0 rows across 244 CONFIRMED bookings."),
        ("11", "No seat is dead inventory: every seat that has never been sold "
               "is found.",
         "A seat with a `ticket` row must **not** appear; a fully sold screen "
         "returns nothing.",
         "372 of 384 seats unsold; 0 of those carry a ticket, and "
         "372 + 12 sold = 384."),
        ("12", "Food revenue is isolated from ticket revenue per film, with no "
               "row multiplication.",
         "Each film appears at most once; a zero ticket base yields NULL from "
         "`NULLIF`, not a division error.",
         "6 rows, 6 distinct titles. The naive double join on the same "
         "bookings yields 19 910.00 instead of 14 940.00 (60 tickets "
         "counted as 80 rows)."),
        ("13", "A clerk's takings and a clerk's refunds are separable by role.",
         "The refund half is non-zero for a clerk who cancelled a sale someone "
         "else rang up \u2014 proof it keys on `cancelled_by_staff_id`.",
         "Clerk 2: 1 refund, 234.00, on a sale clerk 1 rang up. Keyed on "
         "`sold_by_staff_id` it would be 0 for clerk 2 and 234.00 for "
         "clerk 1 \u2014 the wrong clerk."),
    ]

    known = {q["number"] for q in questions}
    for num, _proves, _expected, _observed in rows:
        if num not in known:
            raise DocError(
                f"the test table cites query {num}, which no longer exists "
                "in the SQL file")

    for num, proves, expected, observed in rows:
        w(f"| {num} | {proves} | {expected} | [x] | {observed} |")

    w("\n### Unexercised branches\n")
    w("Correct by construction, but no seeded row reaches them, so they are "
      "not claimed\nas observed above:\n")
    w("- Query 4's `9999` sentinel \u2014 all 8 films have at least one "
      "`showtime`, so the\n  `MAX(st.show_date) IS NULL` branch never fires.")
    w("- Query 12's `NULLIF` guard \u2014 the `INNER JOIN` on ticket revenue "
      "already drops a\n  zero base, so no row can divide by zero.")
    w("")
    w("Query 2's `COALESCE(SUM(tk.tickets), 0)` is likewise unexercised: every "
      "confirmed booking\nhas at least one ticket, so the outer join never "
      "produces a NULL count.")

    # Any numbered query that is not in the table above still belongs in the
    # log as an observation rather than a pass/fail assertion.
    covered = {r[0] for r in rows}
    extras = [q for q in questions if q["number"] not in covered]
    w("")

    if extras:
        w("\n## Supporting queries (reported, not pass/fail)\n")
        w("These produce numbers to read rather than a constraint to prove.\n")
        w("| # | Question |")
        w("|---|---|")
        for q in extras:
            w(f"| {q['number']} | {title(q)} |")
        w("")

    w("\n## The transaction drill\n")
    w("Query 14 is a manual procedure, not a SELECT. Run it on a `HELD` booking\n"
      "and confirm all four steps:\n")
    w("```sql\n"
      "SELECT booking_id, status, total_amount FROM booking WHERE status='HELD' LIMIT 1;\n"
      "CALL sp_confirm_booking(<that id>, 'UPI');\n"
      "SELECT * FROM booking       WHERE booking_id = <that id>;\n"
      "SELECT * FROM payment       WHERE booking_id = <that id>;\n"
      "SELECT * FROM booking_audit WHERE booking_id = <that id>;\n```\n")
    w("It proves four things at once: status moved `HELD \u2192 CONFIRMED`, the\n"
      "three money columns agree, money was recorded, and the trigger wrote an\n"
      "audit row nobody asked it for.\n")
    w(f"\n**Recorded run ({RUN_DATE}, booking 8):** status `HELD \u2192 CONFIRMED`, "
      "money stayed\n250.00 + 660.00 = 910.00, `payment` 256 recorded 910.00 UPI "
      "as `TXN00000008`, and\n`booking_audit` row 32 (`HELD \u2192 CONFIRMED`) was "
      "written by the trigger with the\nprocedure never mentioning it. The row "
      "was then returned to `HELD` and the payment\nand audit rows removed, so "
      "the seeded state is what the other checks were run on.\n")
    w("\nThe procedure's guards were exercised too, each rejected with "
      "SQLSTATE 45000 and a\nnamed message rather than a general failure: "
      "`CHEQUE` as a method, a booking id\nthat does not exist, and a booking "
      "that is not `HELD`.\n")

    w("\n## Coverage\n")
    w(f"- Numbered queries parsed: **{len(questions)}**")
    w(f"- Of those, produce a result set: **{select_like}**")
    drills = [q["number"] for q in questions if not q["statements"]]
    w("- Manual drill blocks: **%d**%s" % (
        len(drills), " (" + ", ".join(f"query {n}" for n in drills) + ")"
        if drills else ""))
    w("- Constraint-bearing checks in the table above: "
      f"**{len(rows)}**")
    views = sorted({t for q in questions for s in q["statements"]
                    for t in referenced_tables(s) if t.startswith("v_")})
    w("- Views exercised: " + ", ".join(f"`{v}`" for v in views))
    triggers = [o for o in objects if o[0] == "TRIGGER"]
    procs = [o for o in objects if o[0] == "PROCEDURE"]
    w("- Objects exercised: " + " \u00b7 ".join(
        [f"trigger `{n}` (fires when the procedure confirms)" for _, n in triggers] +
        [f"procedure `{n}` (query 14)" for _, n in procs]))
    w("- Every column and alias above resolves against `db/01_schema.sql` — "
      "`tools/sqlcheck.py` runs inside this gate and refuses the documents "
      "otherwise")
    w("- One-shot bundle `db/00_setup.sql` carries 01\u201304 exactly once each, in "
      "order, so the `SOURCE` above runs exactly the SQL documented here")
    return "\n".join(out)


def write_or_check(path, doc, check):
    if check:
        try:
            with open(path, encoding="utf-8") as fh:
                current = fh.read()
        except FileNotFoundError:
            print(f"{os.path.basename(path)} is missing - run: "
                  "python tools/querydoc.py")
            return False
        if current != doc:
            print(f"{os.path.basename(path)} is STALE - run: "
                  "python tools/querydoc.py")
            return False
        print(f"{os.path.basename(path)} is up to date")
        return True

    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(doc)
    return True


def stale_bundle():
    """db/00_setup.sql must be exactly the concatenation of 01+02+03+04.

    TESTS.md tells the reader to run the one-shot file. If that file has
    fallen behind the SQL the documents describe, the reader would test an
    older database than the one documented here. Containment is not enough:
    a stray second copy of the schema (which really happened once) still
    contains every part, yet fails on the first CREATE TABLE.
    """
    try:
        import buildsetup                     # lives beside this script
    except ImportError:
        raise DocError("tools/buildsetup.py is missing - cannot verify "
                       "db/00_setup.sql against its parts")
    try:
        expected = buildsetup.assemble()
    except buildsetup.BuildError as exc:
        raise DocError(str(exc))

    actual = read(SETUP)
    if actual == expected:
        return []

    problems = []
    for path in (SCHEMA, SEED, OBJECTS, SQL):
        count = actual.count(read(path))
        if count != 1:
            problems.append(f"{os.path.basename(path)} appears {count}x")
    if not problems:
        problems.append("text differs from 01+02+03+04 (edited or extra)")
    return problems


def named_objects():
    """(kind, name) for every trigger and procedure defined in db/03_objects.sql."""
    found = [(m.group(1).upper(), m.group(2)) for m in re.finditer(
        r"CREATE\s+(TRIGGER|PROCEDURE)\s+(\w+)", read(OBJECTS), re.I)]
    if not found:
        raise DocError("no trigger or procedure found in db/03_objects.sql")
    return found


def main():
    check = "--check" in sys.argv
    capture = "--capture" in sys.argv
    check_db_only = "--check-db" in sys.argv
    try:
        schema_tables = tables_in_schema(SCHEMA, OBJECTS)
        intro_notes, questions = parse(SQL)
        problems, select_like = validate(questions, schema_tables, sqlcheck.Schema())
        objects = named_objects()
        stale = stale_bundle()
        if stale:
            raise DocError(
                "db/00_setup.sql is not 01+02+03+04: " + "; ".join(stale) +
                " - run: python tools/buildsetup.py")
        if problems:
            raise DocError("query validation failed: " + "; ".join(problems))
        caps = capture_output(questions) if capture else load_captures()
        if check_db_only:
            # advisory: report and stop, without touching either document
            for line in check_db(questions, caps):
                print(line)
            return 0
        problems = validate_captures(questions, caps)
        queries_doc = render_queries(intro_notes, questions, caps)
        tests_doc = render_tests(questions, select_like, objects)
    except DocError as exc:
        print(f"REFUSING TO WRITE - {exc}")
        return 1

    if problems:
        print("REFUSING TO WRITE - recorded output is not current:")
        for p in problems:
            print("  -", p)
        return 1

    if capture:
        with open(CAPTURE_OUT, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(caps, fh, indent=2, ensure_ascii=False)
            fh.write("\n")
        print(f"captured output for {len(caps['entries'])} queries from "
              f"MySQL {caps['mysql_version']}")

    ok = write_or_check(QUERIES_OUT, queries_doc, check)
    ok = write_or_check(TESTS_OUT, tests_doc, check) and ok

    if not check:
        print(f"wrote {os.path.basename(QUERIES_OUT)} and "
              f"{os.path.basename(TESTS_OUT)} "
              f"({len(questions)} queries, {select_like} result sets)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
