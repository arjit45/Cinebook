#!/usr/bin/env python3
"""
Offline SQL checker for CineBook -- catches at authoring time what MySQL
would otherwise only report at run time (ERROR 1054 unknown column,
ERROR 1052 ambiguous column, ERROR 1452 foreign key).

Two jobs, neither needing a server:

  columns   Every alias-qualified reference in db/04_queries.sql and every
            column the seed writes must exist in db/01_schema.sql. Views are
            resolved from db/03_objects.sql, derived tables from their own
            select list. An unknown alias, an unknown column and an ambiguous
            unqualified column are all reported.

  seed      The seed's data and arithmetic against the schema's own rules:
            literal rows checked against every ENUM, simple CHECK and UNIQUE,
            foreign keys resolved, money identity total = ticket + fnb
            enforced symbolically on the SET clauses, refund factors proven
            <= 1 and based on ticket_amount, payment amounts taken from
            booking.total_amount, seats per screen equal to seat_capacity,
            and the documented staff passwords re-hashed from their salts.

  python tools/sqlcheck.py             # everything; exit 1 on a problem
  python tools/sqlcheck.py --queries   # only db/04_queries.sql
  python tools/sqlcheck.py --seed      # only db/02_seed.sql
  python tools/sqlcheck.py --verbose   # list what was checked, not just failures

No third-party dependencies.
"""
import hashlib
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
SCHEMA = os.path.join(ROOT, "db", "01_schema.sql")
SEED = os.path.join(ROOT, "db", "02_seed.sql")
OBJECTS = os.path.join(ROOT, "db", "03_objects.sql")
QUERIES = os.path.join(ROOT, "db", "04_queries.sql")

# Words that are never a column reference.
KEYWORDS = set("""
SELECT FROM WHERE JOIN LEFT RIGHT INNER OUTER CROSS ON USING AND OR NOT NULL IS IN
AS GROUP BY ORDER HAVING LIMIT OFFSET DISTINCT CASE WHEN THEN ELSE END BETWEEN LIKE
EXISTS UNION ALL ASC DESC INTERVAL DAY HOUR MINUTE SECOND MONTH YEAR WEEK TRUE FALSE
WITH OVER PARTITION ROWS RANGE CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP DIV MOD
INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE VIEW TRIGGER PROCEDURE CALL
PRIMARY KEY UNIQUE FOREIGN REFERENCES CHECK CONSTRAINT DEFAULT AUTO_INCREMENT
ENGINE INNODB INT VARCHAR CHAR TINYINT SMALLINT BIGINT DECIMAL DATE DATETIME TIME
BOOLEAN ENUM TEXT BLOB FLOAT DOUBLE UNSIGNED
""".split())

# Keywords that can never be a table alias.
NOT_ALIAS = KEYWORDS | {
    "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "JOIN", "ON", "USING", "WHERE",
    "GROUP", "ORDER", "HAVING", "LIMIT", "SET", "VALUES", "UNION", "AS",
}

# Relations that are not in the schema: the seed's temporary numbers table.
TEMP_RELATIONS = {"nums"}


class Problem(Exception):
    pass


# ---------------------------------------------------------------------------
# reading and masking
# ---------------------------------------------------------------------------

def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def mask(text):
    """Blank comments and string contents, keeping offsets and newlines."""
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        if text.startswith("--", i) or text[i] == "#":
            j = text.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif text[i] == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    if j + 1 < n and text[j + 1] == "'":
                        j += 2
                        continue
                    break
                j += 1
            for k in range(i + 1, min(j, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 1
        else:
            i += 1
    return "".join(out)


def split_statements(text):
    """Split on ';' at paren depth 0, outside strings and comments."""
    masked = mask(text)
    cuts, start, depth = [], 0, 0
    for i, ch in enumerate(masked):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == ";" and depth == 0:
            cuts.append((start, i + 1))
            start = i + 1
    if masked[start:].strip():
        cuts.append((start, len(masked)))
    return [(text[a:b], a) for a, b in cuts if text[a:b].strip()]


def tokenize(text, base=0):
    """(kind, value, start, end) with kinds ident/num/str/var/punct."""
    toks = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch in " \t\r\n":
            i += 1
        elif text.startswith("--", i) or ch == "#":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif ch == "'":
            j, buf = i + 1, []
            while j < n:
                if text[j] == "\\" and j + 1 < n:
                    buf.append(text[j + 1])
                    j += 2
                    continue
                if text[j] == "'":
                    if j + 1 < n and text[j + 1] == "'":
                        buf.append("'")
                        j += 2
                        continue
                    break
                buf.append(text[j])
                j += 1
            toks.append(("str", "".join(buf), base + i, base + j + 1))
            i = j + 1
        elif ch == "`":
            j = text.find("`", i + 1)
            j = n if j < 0 else j
            toks.append(("ident", text[i + 1:j], base + i, base + j + 1))
            i = j + 1
        elif ch.isalpha() or ch == "_":
            j = i
            while j < n and (text[j].isalnum() or text[j] in "_$"):
                j += 1
            toks.append(("ident", text[i:j], base + i, base + j))
            i = j
        elif ch == "@":
            j = i + 1
            while j < n and (text[j].isalnum() or text[j] in "_$@"):
                j += 1
            toks.append(("var", text[i:j], base + i, base + j))
            i = j
        elif ch.isdigit() or (ch == "." and i + 1 < n and text[i + 1].isdigit()):
            j = i
            while j < n and (text[j].isdigit() or text[j] == "."):
                j += 1
            toks.append(("num", text[i:j], base + i, base + j))
            i = j
        else:
            toks.append(("punct", ch, base + i, base + i + 1))
            i += 1
    return toks


def kw(tok, word):
    return tok is not None and tok[0] == "ident" and tok[1].upper() == word


def is_comma(tok):
    return tok is not None and tok[0] == "punct" and tok[1] == ","


def is_paren(tok, value="("):
    return tok is not None and tok[0] == "punct" and tok[1] == value


# ---------------------------------------------------------------------------
# schema model
# ---------------------------------------------------------------------------

class Table(object):
    def __init__(self, name):
        self.name = name
        self.columns = {}          # name -> {"not_null", "enum", "type"}
        self.pk = []
        self.uniques = []          # list of [column, ...]
        self.fks = []              # list of (cols, parent, pcols)
        self.checks = []           # raw CHECK expressions

    def column_set(self):
        return set(self.columns)


class Schema(object):
    def __init__(self, schema_path=SCHEMA, objects_path=OBJECTS):
        self.tables = {}
        self.views = {}
        self._parse_tables(read(schema_path))
        self._parse_views(read(objects_path))

    # -- relations -------------------------------------------------------
    def columns_of(self, name):
        key = name.lower()
        if key in self.tables:
            return self.tables[key].column_set()
        if key in self.views:
            return set(self.views[key])
        if key in TEMP_RELATIONS:
            return None            # known relation, columns unknown
        return None

    def known(self, name):
        return name.lower() in self.tables or name.lower() in self.views \
            or name.lower() in TEMP_RELATIONS

    # -- parsing ---------------------------------------------------------
    def _parse_tables(self, src):
        for m in re.finditer(r"CREATE TABLE (\w+)\s*\(", src, re.I):
            name = m.group(1).lower()
            open_at = src.index("(", m.start())
            close_at = _match_paren(src, open_at)
            body = src[open_at + 1:close_at]
            table = Table(name)
            for item in _split_top(body):
                self._parse_item(table, item)
            self.tables[name] = table
        if not self.tables:
            raise Problem("no CREATE TABLE found in the schema")

    def _parse_item(self, table, item):
        toks = tokenize(item)
        if not toks:
            return
        head = toks[0][1].upper()
        text = " ".join(item.split())

        if head == "CONSTRAINT" or head in ("PRIMARY", "UNIQUE", "FOREIGN", "CHECK"):
            offset = 2 if head == "CONSTRAINT" else 0
            kind = toks[offset][1].upper() if offset < len(toks) else ""
            if head == "CONSTRAINT" and offset + 1 < len(toks):
                kind = toks[offset][1].upper()
            if kind == "FOREIGN":
                self._parse_fk(table, text)
            elif kind == "UNIQUE":
                cols = _paren_idents(text)
                if cols:
                    table.uniques.append(cols)
            elif kind == "PRIMARY":
                cols = _paren_idents(text)
                if cols:
                    table.pk = cols
            elif kind == "CHECK":
                m = re.search(r"CHECK\s*\(", text, re.I)
                if m:
                    start = text.index("(", m.start())
                    table.checks.append(text[start + 1:_match_paren(text, start)])
            return

        name = toks[0][1].lower()
        rest = item[toks[0][3] - toks[0][2]:] if len(item) > toks[0][3] - toks[0][2] else ""
        rest = item[item.index(toks[0][1]) + len(toks[0][1]):]
        type_end = len(rest)
        for word in ("NOT", "DEFAULT", "PRIMARY", "UNIQUE", "CHECK", "REFERENCES",
                     "AUTO_INCREMENT", "COMMENT", "COLLATE", "GENERATED"):
            mm = re.search(r"\b" + word + r"\b", rest, re.I)
            if mm:
                type_end = min(type_end, mm.start())
        col_type = " ".join(rest[:type_end].split())

        enum = None
        em = re.search(r"ENUM\s*\(([^)]*)\)", col_type, re.I)
        if em:
            enum = re.findall(r"'([^']*)'", em.group(1))

        table.columns[name] = {
            "type": col_type,
            "not_null": re.search(r"\bNOT\s+NULL\b", rest, re.I) is not None,
            "enum": enum,
        }
        if re.search(r"\bPRIMARY\s+KEY\b", rest, re.I):
            table.pk = [name] + [c for c in table.pk if c != name]
        if re.search(r"\bUNIQUE\b", rest, re.I):
            table.uniques.append([name])
        if re.search(r"\bREFERENCES\b", rest, re.I):
            self._parse_fk(table, name + " " + rest)

    def _parse_fk(self, table, text):
        m = re.search(
            r"FOREIGN\s+KEY\s*\(([^)]*)\)\s*REFERENCES\s+(\w+)\s*\(([^)]*)\)",
            text, re.I | re.S)
        if not m:
            return
        cols = [c.strip().strip("`").lower() for c in m.group(1).split(",")]
        pcols = [c.strip().strip("`").lower() for c in m.group(3).split(",")]
        table.fks.append((cols, m.group(2).lower(), pcols))

    def _parse_views(self, src):
        for m in re.finditer(r"CREATE\s+OR\s+REPLACE\s+VIEW\s+(\w+)\s+AS\s+SELECT",
                             src, re.I):
            name = m.group(1).lower()
            tail = src[m.end():]
            toks = tokenize(tail)
            items = select_items(toks, 0)
            cols = []
            for item in items:
                out = output_name(item)
                if out is None:
                    raise Problem(f"view {name} has a column without an alias")
                cols.append(out)
            self.views[name] = cols


def _match_paren(text, open_at):
    depth = 0
    i = open_at
    while i < len(text):
        ch = text[i]
        if ch == "'":
            i += 1
            while i < len(text):
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "'":
                    break
                i += 1
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise Problem("unbalanced parentheses")


def _split_top(text):
    """Split a comma separated list at paren depth 0, outside strings."""
    parts, cur, depth, i = [], [], 0, 0
    while i < len(text):
        ch = text[i]
        if ch == "'":
            j = i + 1
            while j < len(text):
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    break
                j += 1
            cur.append(text[i:j + 1])
            i = j + 1
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
            i += 1
            continue
        cur.append(ch)
        i += 1
    if "".join(cur).strip():
        parts.append("".join(cur))
    return parts


def _paren_idents(text):
    m = re.search(r"\(([^)]*)\)", text)
    if not m:
        return []
    return [c.strip().strip("`").lower() for c in m.group(1).split(",") if c.strip()]


# ---------------------------------------------------------------------------
# query structure: select lists, aliases, references
# ---------------------------------------------------------------------------

def select_items(tokens, start=0):
    """Top-level comma items of a select list, stopping at FROM."""
    items, cur, depth = [], [], 0
    for tok in tokens[start:]:
        if tok[0] == "punct":
            if tok[1] == "(":
                depth += 1
            elif tok[1] == ")":
                if depth == 0:
                    break
                depth -= 1
            elif tok[1] == "," and depth == 0:
                items.append(cur)
                cur = []
                continue
        if depth == 0 and kw(tok, "FROM"):
            break
        cur.append(tok)
    if cur:
        items.append(cur)
    return items


def output_name(item):
    depth = 0
    for idx, tok in enumerate(item):
        if tok[0] == "punct" and tok[1] == "(":
            depth += 1
        elif tok[0] == "punct" and tok[1] == ")":
            depth -= 1
        elif depth == 0 and kw(tok, "AS") and idx + 1 < len(item):
            nxt = item[idx + 1]
            return nxt[1].lower() if nxt[0] == "ident" else None
    if len(item) == 1 and item[0][0] == "ident":
        return item[0][1].lower()
    if (len(item) == 3 and item[0][0] == "ident"
            and item[1][0] == "punct" and item[1][1] == "."
            and item[2][0] == "ident"):
        return item[2][1].lower()
    return None


def _token_paren(tokens, open_at):
    depth = 0
    for idx in range(open_at, len(tokens)):
        if tokens[idx][0] == "punct":
            if tokens[idx][1] == "(":
                depth += 1
            elif tokens[idx][1] == ")":
                depth -= 1
                if depth == 0:
                    return idx
    raise Problem("unbalanced parentheses in statement")


def _alias_after(toks, k):
    """Optional alias at index k: (alias, next index)."""
    if k < len(toks) and kw(toks[k], "AS") and k + 1 < len(toks) \
            and toks[k + 1][0] == "ident":
        return toks[k + 1][1], k + 2
    if k < len(toks) and toks[k][0] == "ident" \
            and toks[k][1].upper() not in NOT_ALIAS:
        return toks[k][1], k + 1
    return None, k


def _subquery_spans(toks):
    """(open, close) index pairs of parenthesised subqueries at this level."""
    spans, i = [], 0
    while i < len(toks):
        if is_paren(toks[i]):
            close = _token_paren(toks, i)
            inner = toks[i + 1:close]
            if inner and (kw(inner[0], "SELECT") or kw(inner[0], "WITH")):
                spans.append((i, close))
            i = close + 1
        else:
            i += 1
    return spans


def select_columns(inner, problems, label):
    """Output columns of a derived table's SELECT, or None when unknown."""
    if not inner or not kw(inner[0], "SELECT"):
        return None
    cols = set()
    for item in select_items(inner, 1):
        if not item:
            continue
        out = output_name(item)
        if out is None:
            return None
        cols.add(out)
    return cols


def _scope_bindings(toks, spans, schema, problems, label):
    """alias -> column set for relations at this level, plus tokens to skip."""
    span_end = {a: b for a, b in spans}
    bindings, skip = {}, set()
    i = 0
    while i < len(toks):
        if i in span_end:
            close = span_end[i]
            # 'FROM (SELECT ...) x': the derived table is a relation here.
            if i > 0 and (kw(toks[i - 1], "FROM") or kw(toks[i - 1], "JOIN")):
                alias, nxt = _alias_after(toks, close + 1)
                if alias:
                    bindings[alias.lower()] = select_columns(toks[i + 1:close],
                                                            problems, label)
                skip.update(range(close + 1, max(nxt, close + 1)))
                i = nxt
                continue
            i = close + 1
            continue

        tok = toks[i]
        # INSERT targets are checked separately; they are not in the query scope.
        if kw(tok, "FROM") or kw(tok, "JOIN") or kw(tok, "UPDATE"):
            if i + 1 >= len(toks):
                break
            j = i + 1
            if j in span_end:
                i = j
                continue
            if toks[j][0] != "ident":
                i += 1
                continue
            name = toks[j][1]
            skip.add(j)
            k = j + 1
            if (k + 1 < len(toks) and is_paren(toks[k], ".")
                    and toks[k + 1][0] == "ident"):
                name = toks[k + 1][1]
                skip.add(k + 1)
                k += 2
            if not schema.known(name):
                problems.append(f"{label}: unknown table or view '{name}'")
            cols = schema.columns_of(name)
            alias, nxt = _alias_after(toks, k)
            binding = (alias or name).lower()
            bindings[binding] = cols
            if alias:
                skip.add(k)
            i = nxt
            continue
        i += 1
    return bindings, skip


def check_statement(stmt, schema, label):
    problems = []
    toks = tokenize(stmt)
    if not toks:
        return problems

    _check_scope(toks, schema, problems, label)
    _check_insert_shape(toks, schema, problems, label)
    _check_update_columns(toks, schema, problems, label)
    _check_delete_target(toks, problems, label)
    return problems


def _check_scope(toks, schema, problems, label, outer=None):
    spans = _subquery_spans(toks)
    bindings, skip = _scope_bindings(toks, spans, schema, problems, label)
    scope = dict(outer or {})
    scope.update(bindings)
    skip |= _skip_spans(toks)
    for a, b in spans:
        skip.update(range(a, b + 1))
    aliases = _select_aliases(toks, spans)

    _check_qualified(toks, scope, schema, problems, label, skip)
    _check_unqualified(toks, bindings, schema, problems, label, skip, aliases)
    for a, b in spans:
        _check_scope(toks[a + 1:b], schema, problems, label, scope)


def _skip_spans(toks):
    """Token indexes that name columns but are not references to read."""
    skip = set()
    # INSERT ... (col, col) column list
    for idx, tok in enumerate(toks):
        if kw(tok, "INTO") and idx + 2 < len(toks) and toks[idx + 1][0] == "ident" \
                and is_paren(toks[idx + 2]):
            close = _token_paren(toks, idx + 2)
            for k in range(idx + 2, close + 1):
                skip.add(k)
    # SET col = ... targets
    for idx, tok in enumerate(toks):
        if kw(tok, "SET"):
            depth = 0
            k = idx + 1
            while k + 1 < len(toks):
                if toks[k][0] == "punct" and toks[k][1] == "(":
                    depth += 1
                elif toks[k][0] == "punct" and toks[k][1] == ")":
                    depth -= 1
                elif kw(toks[k], "WHERE") and depth == 0:
                    break
                if (depth == 0 and toks[k][0] == "ident" and toks[k + 1][0] == "punct"
                        and toks[k + 1][1] == "="):
                    skip.add(k)
                    k += 2
                    continue
                k += 1
    return skip


def _explicit_alias(item):
    """The name after AS, or None. Bare columns are not aliases."""
    depth = 0
    for idx, tok in enumerate(item):
        if tok[0] == "punct" and tok[1] == "(":
            depth += 1
        elif tok[0] == "punct" and tok[1] == ")":
            depth -= 1
        elif depth == 0 and kw(tok, "AS") and idx + 1 < len(item):
            nxt = item[idx + 1]
            return nxt[1].lower() if nxt[0] == "ident" else None
    return None


def _select_aliases(toks, spans=()):
    names = set()
    inside = set()
    for a, b in spans:
        inside.update(range(a, b + 1))
    select_at = None
    for idx, tok in enumerate(toks):
        if idx in inside:
            continue
        if kw(tok, "SELECT"):
            select_at = idx
            break
    if select_at is None:
        return names
    for item in select_items(toks, select_at + 1):
        n = _explicit_alias(item)
        if n:
            names.add(n)
    return names


def _check_qualified(toks, bindings, schema, problems, label, skip):
    for idx in range(len(toks) - 2):
        if idx in skip:
            continue
        qual, dot, col = toks[idx], toks[idx + 1], toks[idx + 2]
        if not (qual[0] == "ident" and dot[0] == "punct" and dot[1] == "."
                and col[0] == "ident"):
            continue
        q, c = qual[1].lower(), col[1].lower()
        if q in bindings:
            cols = bindings[q]
            if cols is not None and c not in cols:
                problems.append(
                    f"{label}: {qual[1]}.{col[1]} - '{qual[1]}' has no column "
                    f"'{col[1]}'")
            continue
        if schema.known(q) and q not in TEMP_RELATIONS:
            cols = schema.columns_of(q)
            if cols is not None and c not in cols:
                problems.append(
                    f"{label}: {qual[1]}.{col[1]} - '{q}' has no column '{c}'")
            continue
        if q in TEMP_RELATIONS:
            continue
        problems.append(
            f"{label}: unknown table or alias '{qual[1]}' in "
            f"{qual[1]}.{col[1]}")


def _check_unqualified(toks, bindings, schema, problems, label, skip, aliases):
    """Column references without a qualifier: valid single-table, or ambiguous."""
    known = [(a, c) for a, c in bindings.items() if c is not None]
    if not known:
        return
    owner = {}
    for alias, cols in known:
        for c in cols:
            owner.setdefault(c, set()).add(alias)
    single = len(known) == 1

    for idx, tok in enumerate(toks):
        if tok[0] != "ident" or idx in skip:
            continue
        up = tok[1].upper()
        if up in KEYWORDS or tok[1].lower() in aliases:
            continue
        prev = toks[idx - 1] if idx > 0 else None
        nxt = toks[idx + 1] if idx + 1 < len(toks) else None
        if prev is not None and prev[0] == "punct" and prev[1] == ".":
            continue
        if nxt is not None and nxt[0] == "punct" and nxt[1] == ".":
            continue
        if nxt is not None and is_paren(nxt):
            continue                       # function call

        low = tok[1].lower()
        if low in owner and len(owner[low]) > 1:
            problems.append(
                f"{label}: '{tok[1]}' is ambiguous - it names a column of "
                + ", ".join(sorted(owner[low])))
            continue
        if single and low not in known[0][1]:
            problems.append(
                f"{label}: unknown column '{tok[1]}' "
                f"(table has no such column)")


def _check_insert_shape(toks, schema, problems, label):
    for idx, tok in enumerate(toks):
        if not kw(tok, "INTO") or idx + 1 >= len(toks):
            continue
        if toks[idx + 1][0] != "ident":
            continue
        table = toks[idx + 1][1].lower()
        if table not in schema.tables:
            continue
        cols = []
        k = idx + 2
        if k < len(toks) and is_paren(toks[k]):
            close = _token_paren(toks, k)
            cols = [t[1].lower() for t in toks[k + 1:close] if t[0] == "ident"]
            k = close + 1
        if not cols:
            problems.append(f"{label}: INSERT INTO {table} has no column list")
            continue
        missing = [c for c in cols if c not in schema.tables[table].columns]
        if missing:
            problems.append(
                f"{label}: INSERT INTO {table} names unknown column(s): "
                + ", ".join(missing))
        if k < len(toks) and kw(toks[k], "VALUES"):
            for row in _value_rows(toks, k + 1):
                if len(row) != len(cols):
                    problems.append(
                        f"{label}: INSERT INTO {table} has {len(cols)} columns "
                        f"but a row of {len(row)} values")


def _value_rows(toks, start):
    rows, i = [], start
    while i < len(toks):
        if is_paren(toks[i]):
            close = _token_paren(toks, i)
            rows.append(_split_tokens(toks[i + 1:close]))
            i = close + 1
        else:
            i += 1
    return rows


def _split_tokens(toks):
    parts, cur, depth = [], [], 0
    for tok in toks:
        if tok[0] == "punct" and tok[1] == "(":
            depth += 1
        elif tok[0] == "punct" and tok[1] == ")":
            depth -= 1
        elif tok[0] == "punct" and tok[1] == "," and depth == 0:
            parts.append(cur)
            cur = []
            continue
        cur.append(tok)
    if cur:
        parts.append(cur)
    return parts


def _set_assignments(toks, set_at):
    """(column, expression tokens) for each assignment in a SET clause."""
    out = []
    for part in _set_parts(toks, set_at):
        eq = None
        for idx, tok in enumerate(part):
            if tok[0] == "punct" and tok[1] == "=":
                eq = idx
                break
        if eq is None or eq == 0:
            continue
        col_tok = part[eq - 1]
        if col_tok[0] != "ident":
            continue
        expr = part[eq + 1:]
        while expr and expr[-1][0] == "punct" and expr[-1][1] == ";":
            expr = expr[:-1]
        out.append((col_tok[1].lower(), expr))
    return out


def _check_update_columns(toks, schema, problems, label):
    table = None
    for idx, tok in enumerate(toks):
        if kw(tok, "UPDATE") and idx + 1 < len(toks) and toks[idx + 1][0] == "ident":
            table = toks[idx + 1][1].lower()
            break
    spec = schema.tables.get(table)
    for idx, tok in enumerate(toks):
        if not kw(tok, "SET"):
            continue
        for col, _expr in _set_assignments(toks, idx):
            if spec is not None and col not in spec.columns \
                    and col not in {c for fk in spec.fks for c in fk[0]}:
                problems.append(f"{label}: SET names unknown column '{col}'")
        return


def _set_parts(toks, set_at):
    parts, cur, depth = [], [], 0
    for tok in toks[set_at + 1:]:
        if tok[0] == "punct" and tok[1] == "(":
            depth += 1
        elif tok[0] == "punct" and tok[1] == ")":
            depth -= 1
        elif depth == 0 and kw(tok, "WHERE"):
            break
        elif tok[0] == "punct" and tok[1] == "," and depth == 0:
            parts.append(cur)
            cur = []
            continue
        cur.append(tok)
    if cur:
        parts.append(cur)
    return parts


def _check_delete_target(toks, problems, label):
    if not (toks and kw(toks[0], "DELETE") and len(toks) > 1
            and toks[1][0] == "ident"):
        return
    alias = toks[1][1].lower()
    aliases = set()
    for idx, tok in enumerate(toks):
        if kw(tok, "FROM") and idx + 2 < len(toks):
            if toks[idx + 2][0] == "ident":
                aliases.add(toks[idx + 2][1].lower())
            elif idx + 1 < len(toks) and toks[idx + 1][0] == "ident":
                aliases.add(toks[idx + 1][1].lower())
    if aliases and alias not in aliases:
        problems.append(
            f"{label}: DELETE target '{toks[1][1]}' is not an alias in FROM")


# ---------------------------------------------------------------------------
# seed: literals, constraints, money rules
# ---------------------------------------------------------------------------

def _literal(expr):
    """A python value for a literal expression, or the string 'expr'."""
    if len(expr) == 1:
        kind, value = expr[0][0], expr[0][1]
        if kind == "num":
            return float(value) if "." in value else int(value)
        if kind == "str":
            return value
        if kind == "ident" and value.upper() == "NULL":
            return None
        if kind == "ident" and value.upper() in ("TRUE", "FALSE"):
            return value.upper() == "TRUE"
    return "expr"


def parse_insert(toks):
    """Dict for INSERT statements: table, cols, rows / select items."""
    for idx, tok in enumerate(toks):
        if not kw(tok, "INTO") or idx + 1 >= len(toks):
            continue
        if toks[idx + 1][0] != "ident":
            return None
        table = toks[idx + 1][1].lower()
        k = idx + 2
        cols = []
        if k < len(toks) and is_paren(toks[k]):
            close = _token_paren(toks, k)
            cols = [t[1].lower() for t in toks[k + 1:close] if t[0] == "ident"]
            k = close + 1
        if k < len(toks) and kw(toks[k], "VALUES"):
            rows = []
            for row in _value_rows(toks, k + 1):
                rows.append({c: _literal(e) for c, e in zip(cols, row)})
            return {"table": table, "cols": cols, "rows": rows, "select": None}
        if k < len(toks) and kw(toks[k], "SELECT"):
            return {"table": table, "cols": cols, "rows": [],
                    "select": select_items(toks, k + 1), "select_at": k}
        return None
    return None


def parse_update(toks):
    for idx, tok in enumerate(toks):
        if not kw(tok, "UPDATE") or idx + 1 >= len(toks):
            continue
        if toks[idx + 1][0] != "ident":
            return None
        table = toks[idx + 1][1].lower()
        for j in range(idx, len(toks)):
            if kw(toks[j], "SET"):
                sets = dict(_set_assignments(toks, j))
                return {"table": table, "sets": sets, "tokens": toks}
    return None


def _norm(expr_tokens):
    return "".join(t[1] for t in expr_tokens).upper().replace(" ", "")


def _check_rules_for(table, col, value):
    """Problems for one literal value against the table's simple CHECKs."""
    problems = []
    for check in table.checks:
        if not _check_applies(check, col, value):
            problems.append(f"CHECK ({check}) rejects {col} = {value!r}")
    return problems


def _check_applies(check, col, value):
    """True when the row satisfies this CHECK (or the CHECK is not decidable)."""
    text = " ".join(check.split())
    alternatives = [a.strip() for a in re.split(r"\s+OR\s+", text, flags=re.I)]
    for alt in alternatives:
        verdict = _eval_simple(alt, col, value)
        if verdict is None:
            return True            # undecidable -> do not fail the row
        if verdict:
            return True
    return False


def _num(text):
    try:
        return float(text)
    except ValueError:
        return None


def _eval_simple(expr, col, value):
    expr = expr.strip().strip("()").strip()
    m = re.match(r"^(\w+)\s+IS\s+NULL$", expr, re.I)
    if m:
        return (value is None) if m.group(1).lower() == col else None
    m = re.match(r"^(\w+)\s+BETWEEN\s+(-?[\d.]+)\s+AND\s+(-?[\d.]+)$", expr, re.I)
    if m and m.group(1).lower() == col:
        if not isinstance(value, (int, float)):
            return None
        return _num(m.group(2)) <= value <= _num(m.group(3))
    m = re.match(r"^(\w+)\s*(>=|<=|<>|!=|>|<|=)\s*(-?[\d.]+)$", expr, re.I)
    if m and m.group(1).lower() == col:
        if not isinstance(value, (int, float)):
            return None
        expect, op = _num(m.group(3)), m.group(2)
        return {">": value > expect, ">=": value >= expect, "<": value < expect,
                "<=": value <= expect, "=": value == expect, "<>": value != expect,
                "!=": value != expect}[op]
    m = re.match(r"^CHAR_LENGTH\((\w+)\)\s+BETWEEN\s+(\d+)\s+AND\s+(\d+)$", expr, re.I)
    if m and m.group(1).lower() == col:
        if not isinstance(value, str):
            return None
        return int(m.group(2)) <= len(value) <= int(m.group(3))
    m = re.match(r"^CHAR_LENGTH\((\w+)\)\s*=\s*(\d+)$", expr, re.I)
    if m and m.group(1).lower() == col:
        if not isinstance(value, str):
            return None
        return len(value) == int(m.group(2))
    m = re.match(r"^(\w+)\s+IN\s*\((.*)\)$", expr, re.I)
    if m and m.group(1).lower() == col:
        allowed = re.findall(r"'([^']*)'", m.group(2))
        return value in allowed
    return None


def validate_insert_data(insert, schema, data, seen, problems, label):
    table = schema.tables.get(insert["table"])
    if table is None:
        return
    name = insert["table"]
    for col in insert["cols"]:
        if col not in table.columns:
            continue
    # rows keyed with defaults for missing columns
    for row_no, row in enumerate(insert["rows"], 1):
        for col, value in row.items():
            if col not in table.columns:
                continue
            spec = table.columns[col]
            if value == "expr":
                continue
            if value is None and spec["not_null"]:
                problems.append(f"{label}: {name} row {row_no} sets NOT NULL "
                                f"'{col}' to NULL")
            if spec["enum"] and value is not None and value not in spec["enum"]:
                problems.append(
                    f"{label}: {name}.{col} = {value!r} is not one of "
                    + "/".join(spec["enum"]))
            for p in _check_rules_for(table, col, value):
                problems.append(f"{label}: {name} row {row_no}: {p}")

    # primary key and unique keys, across every literal row for this table
    keys = [(table.pk, "PRIMARY KEY")] + [(u, "UNIQUE") for u in table.uniques]
    for cols, kind in keys:
        if not cols or any(c not in insert["cols"] for c in cols):
            continue
        bucket = seen.setdefault((name, tuple(cols)), set())
        for row_no, row in enumerate(insert["rows"], 1):
            values = tuple(row.get(c) for c in cols)
            if any(v == "expr" for v in values):
                continue
            if values in bucket:
                problems.append(
                    f"{label}: {name} {kind} ({', '.join(cols)}) repeats "
                    f"{values} at row {row_no}")
            bucket.add(values)

    # literal values in a foreign key must resolve to a literal parent row
    for cols, parent, pcols in table.fks:
        parent_data = data.get(parent)
        if not parent_data:
            continue
        keys = {tuple(r.get(pc) for pc in pcols) for r in parent_data}
        if len(cols) != 1 or len(pcols) != 1:
            continue
        if any(None in key or "expr" in key for key in keys):
            continue           # auto-increment keys are unknowable offline
        col = cols[0]
        if col not in insert["cols"]:
            continue
        for row in insert["rows"]:
            value = row.get(col)
            if value in ("expr", None):
                continue
            if (value,) not in keys:
                problems.append(
                    f"{label}: {name}.{col} = {value!r} has no matching "
                    f"{parent}.{pcols[0]}")

    data.setdefault(name, []).extend(insert["rows"])


def validate_insert_select(insert, stmt_tokens, schema, data, problems, label):
    """Column count, and literal FK values in the select list."""
    table = schema.tables.get(insert["table"])
    if table is None:
        return
    items = insert["select"]
    cols = insert["cols"]
    if len(items) != len(cols):
        problems.append(
            f"{label}: INSERT INTO {insert['table']} lists {len(cols)} columns "
            f"but its SELECT returns {len(items)} expressions")
    for col, expr in zip(cols, items):
        value = _literal(expr)
        if value == "expr" or col not in table.columns:
            continue
        for fk_cols, parent, pcols in table.fks:
            if fk_cols != [col] or len(pcols) != 1:
                continue
            parent_data = data.get(parent)
            if not parent_data:
                continue
            keys = {r.get(pcols[0]) for r in parent_data}
            if keys and None not in keys and value not in keys:
                problems.append(
                    f"{label}: {insert['table']}.{col} = {value!r} has no "
                    f"matching {parent}.{pcols[0]}")


def _num_tokens(expr):
    return [float(t[1]) for t in expr if t[0] == "num"]


def check_money_rules(update, problems, label):
    sets = update["sets"]
    booking_cols = {"ticket_amount", "fnb_amount", "total_amount"}
    present = booking_cols & set(sets)
    if present and update["table"] == "booking":
        if present != booking_cols:
            problems.append(
                f"{label}: booking money is written piecemeal ({', '.join(sorted(present))}) "
                "- the CHECK is evaluated on the finished row, so all three "
                "columns must be set in one statement")
        else:
            ticket = _norm(sets["ticket_amount"])
            fnb = _norm(sets["fnb_amount"])
            total = _norm(sets["total_amount"])
            if total not in (ticket + "+" + fnb, "(" + ticket + ")+(" + fnb + ")",
                             fnb + "+" + ticket, "(" + fnb + ")+(" + ticket + ")"):
                problems.append(
                    f"{label}: total_amount = {total} does not equal "
                    f"ticket_amount + fnb_amount ({ticket} + {fnb})")

    if update["table"] == "booking" and "refund_amount" in sets:
        expr = sets["refund_amount"]
        factors = []
        if "ticket_amount" not in "".join(t[1] for t in expr):
            problems.append(
                f"{label}: refund_amount is not based on ticket_amount - food "
                "would be refunded too")
        if "total_amount" in "".join(t[1] for t in expr):
            problems.append(
                f"{label}: refund_amount is based on total_amount, which "
                "includes food")
        for idx in range(len(expr) - 2):
            if expr[idx][0] == "ident" and expr[idx][1].lower() == "ticket_amount" \
                    and expr[idx + 1][0] == "punct" and expr[idx + 1][1] == "*" \
                    and expr[idx + 2][0] == "num":
                factors.append(float(expr[idx + 2][1]))
        if not factors:
            problems.append(f"{label}: refund_amount has no ticket_amount * factor")
        for f in factors:
            if not 0 < f <= 1.0:
                problems.append(
                    f"{label}: refund factor {f} must be in (0, 1]; refund_amount "
                    "may never exceed ticket_amount")

    status_cancelled = False
    for idx, tok in enumerate(update["tokens"]):
        if kw(tok, "STATUS") and idx + 2 < len(update["tokens"]):
            if update["tokens"][idx + 1][0] == "punct" and update["tokens"][idx + 1][1] == "=":
                value = update["tokens"][idx + 2]
                if value[0] == "str" and value[1].upper() == "CANCELLED":
                    status_cancelled = True
    if status_cancelled and "cancelled_at" not in sets:
        problems.append(
            f"{label}: sets status = 'CANCELLED' without cancelled_at in the "
            "same statement")


def check_ticket_insert(insert, problems, label):
    cols = insert["cols"]
    if "price" not in cols or not insert["select"]:
        problems.append(f"{label}: ticket INSERT has no price column")
        return
    expr = insert["select"][cols.index("price")]
    text = "".join(t[1].lower() for t in expr)
    if "base_price" not in text:
        problems.append(f"{label}: ticket.price is not derived from base_price")
    if "seat_class" not in text:
        problems.append(f"{label}: ticket.price ignores seat_class")
    factors = []
    for idx, tok in enumerate(expr):
        if tok[0] == "ident" and tok[1].upper() in ("THEN", "ELSE") and idx + 1 < len(expr) \
                and expr[idx + 1][0] == "num":
            factors.append(float(expr[idx + 1][1]))
    if len(factors) < 3:
        problems.append(
            f"{label}: ticket.price has {len(factors)} class multipliers, "
            "expected 3 (REGULAR/PREMIUM/RECLINER)")
    for f in factors:
        if f < 1.0:
            problems.append(
                f"{label}: ticket.price multiplier {f} is below the base price")
    if "showtime_id" in cols and insert["select"]:
        expr = insert["select"][cols.index("showtime_id")]
        if "showtime_id" not in "".join(t[1].lower() for t in expr):
            problems.append(
                f"{label}: ticket.showtime_id is not copied from the booking")


def check_payment_insert(insert, problems, label):
    cols = insert["cols"]
    if not insert["select"]:
        return
    if "amount" in cols:
        expr = insert["select"][cols.index("amount")]
        if "total_amount" not in "".join(t[1].lower() for t in expr):
            problems.append(
                f"{label}: payment.amount is not booking.total_amount")
    if "method" in cols:
        expr = insert["select"][cols.index("method")]
        values = {t[1].upper() for t in expr if t[0] == "str"}
        if not {"CARD", "UPI", "CASH"} <= values:
            problems.append(
                f"{label}: payment.method values are not CARD/UPI/CASH")
    if "booking_id" in cols:
        expr = insert["select"][cols.index("booking_id")]
        if "booking_id" not in "".join(t[1].lower() for t in expr):
            problems.append(f"{label}: payment.booking_id is not from booking")


def check_seat_generator(tokens, screen_capacities, problems, label):
    bound = None
    for idx in range(len(tokens) - 2):
        if (tokens[idx][0] == "ident" and tokens[idx][1].lower() == "n"
                and tokens[idx + 1][0] == "punct" and tokens[idx + 1][1] == "<"
                and tokens[idx + 2][0] == "num"):
            bound = int(float(tokens[idx + 2][1]))
    if bound is None:
        problems.append(f"{label}: seat INSERT has no 'nums.n < N' bound")
        return
    for capacity in screen_capacities:
        if capacity != bound:
            problems.append(
                f"{label}: seat generator seats {bound} per screen but a screen "
                f"has seat_capacity {capacity}")
    if bound % 12:
        problems.append(
            f"{label}: seat generator bound {bound} is not a multiple of the "
            "12 seats per row")
    rows = bound // 12
    if rows > 26:
        problems.append(
            f"{label}: {rows} rows exceeds the 26 labels A..Z")
    if rows and rows * 12 > 60 * rows:
        problems.append(f"{label}: seat numbers would exceed chk_seat_no")
    text = "".join(t[1] for t in tokens).lower()
    for needle, why in (("char(", "row label is not derived with CHAR("),
                        ("floor(", "row label is not derived with FLOOR("),
                        ("%12", "seat number is not wrapped with MOD 12"),
                        ("recliner", "RECLINER class missing"),
                        ("premium", "PREMIUM class missing"),
                        ("regular", "REGULAR class missing")):
        if needle not in text:
            problems.append(f"{label}: {why}")


def check_staff_passwords(data, comments, problems, label):
    documented = {}
    for line in comments.splitlines():
        m = re.match(r"^--\s{2,}([A-Za-z_]\w*)\s*/\s*(\S+)", line)
        if m:
            documented[m.group(1).lower()] = m.group(2)
    rows = data.get("staff_user", [])
    for row in rows:
        user = row.get("username")
        if not user or user == "expr":
            continue
        pwd = documented.get(str(user).lower())
        if pwd is None:
            problems.append(f"{label}: no documented password for staff '{user}'")
            continue
        salt, digest = row.get("salt"), row.get("password_hash")
        if not isinstance(salt, str) or not isinstance(digest, str):
            continue
        computed = hashlib.sha256((salt + pwd).encode()).hexdigest()
        if computed != digest.lower():
            problems.append(
                f"{label}: staff '{user}' hash does not match "
                f"SHA-256(salt + {pwd})")
        if len(digest) != 64:
            problems.append(f"{label}: staff '{user}' hash is not 64 hex chars")
        if len(salt) != 32:
            problems.append(f"{label}: staff '{user}' salt is not 32 chars")


def check_seed(src):
    problems = []
    schema = Schema()
    statements = split_statements(src)
    data = {}
    seen = {}
    inserts, updates = [], []

    for stmt, offset in statements:
        label = f"db/02_seed.sql:{src.count(chr(10), 0, offset) + 1}"
        toks = tokenize(stmt)
        if not toks:
            continue
        problems.extend(check_statement(stmt, schema, label))

        insert = parse_insert(toks)
        if insert and insert["table"] not in ("nums",):
            inserts.append(insert)
            if insert["rows"]:
                validate_insert_data(insert, schema, data, seen, problems, label)
            elif insert["select"] is not None:
                validate_insert_select(insert, toks, schema, data, problems, label)
            continue

        update = parse_update(toks)
        if update:
            updates.append(update)
            check_money_rules(update, problems, label)

    for insert in inserts:
        if insert["table"] == "ticket":
            check_ticket_insert(insert, problems, f"db/02_seed.sql: ticket INSERT")
        if insert["table"] == "payment":
            check_payment_insert(insert, problems, "db/02_seed.sql: payment INSERT")
        if insert["table"] == "seat":
            capacities = [r.get("seat_capacity") for r in data.get("screen", [])]
            capacities = [c for c in capacities if isinstance(c, (int, float))]
            check_seat_generator(tokenize(_statement_for(src, "INSERT INTO seat")),
                                 capacities, problems, "db/02_seed.sql: seat INSERT")

    check_staff_passwords(data, src, problems, "db/02_seed.sql: staff")

    # every table must be seeded, and both money aggregates must exist
    inserted = {i["table"] for i in inserts} | {u["table"] for u in updates}
    for name in sorted(schema.tables):
        if name not in inserted:
            problems.append(f"db/02_seed.sql: table '{name}' is never seeded")

    masked = mask(src)
    if not re.search(r"SUM\(\s*price\s*\)", masked, re.I):
        problems.append("db/02_seed.sql: no SUM(price) over ticket for ticket_amount")
    if not re.search(r"SUM\(\s*quantity\s*\*\s*unit_price\s*\)", masked, re.I):
        problems.append(
            "db/02_seed.sql: no SUM(quantity * unit_price) for fnb_amount")
    return problems, {
        "statements": len(statements),
        "literal rows": sum(len(i["rows"]) for i in inserts),
        "tables": len(inserted),
        "updates": len(updates),
    }


def _statement_for(src, needle):
    idx = src.find(needle)
    if idx < 0:
        return ""
    for stmt, offset in split_statements(src):
        if offset <= idx < offset + len(stmt):
            return stmt
    return ""


# ---------------------------------------------------------------------------
# queries
# ---------------------------------------------------------------------------

def check_queries(src):
    problems = []
    schema = Schema()
    statements = split_statements(src)
    for stmt, offset in statements:
        label = f"db/04_queries.sql:{src.count(chr(10), 0, offset) + 1}"
        problems.extend(check_statement(stmt, schema, label))
    return problems, {"statements": len(statements)}


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main(argv):
    verbose = "--verbose" in argv
    only_queries = "--queries" in argv
    only_seed = "--seed" in argv
    do_queries = not only_seed
    do_seed = not only_queries

    failures = 0

    try:
        if do_queries:
            problems, stats = check_queries(read(QUERIES))
            failures += len(problems)
            print(f"db/04_queries.sql: {stats['statements']} statements, "
                  f"{len(problems)} problems")
            for p in problems:
                print("  -", p)

        if do_seed:
            problems, stats = check_seed(read(SEED))
            failures += len(problems)
            print(f"db/02_seed.sql: {stats['statements']} statements, "
                  f"{stats['literal rows']} literal rows in {stats['tables']} tables, "
                  f"{len(problems)} problems")
            for p in problems:
                print("  -", p)
            if verbose:
                print("  checked: enums, simple CHECKs, unique keys, foreign keys, "
                      "money identity, refund factors, seat capacity, staff hashes")
    except Problem as exc:
        print(f"CANNOT CHECK - {exc}")
        return 1
    except OSError as exc:
        print(f"CANNOT CHECK - {exc}")
        return 1

    if failures:
        print(f"FAILED: {failures} problem(s)")
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
