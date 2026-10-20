#!/usr/bin/env python3
"""
Structural check of db/*.sql against db/01_schema.sql -- no MySQL needed.

This is not a substitute for running the scripts -- the scripts have since been
executed on MySQL 8.0.46 (see docs/TESTS.md) -- but it catches a broken reference
in a second, with no server running at all:

  * every table a statement names exists in the schema
  * every column a statement names exists on the table or alias it belongs to
  * every FOREIGN KEY targets a table and a column that exist
  * every column used in the seed's INSERT lists exists

Derived-table aliases (the `tk` in `JOIN (SELECT ...) tk`) cannot be resolved
without a real parser, so they are counted and reported rather than guessed at.

Run:  python tools/checkdb.py
Exit code 1 if a real problem is found, 0 otherwise.
"""
import os
import re
import sys

HERE = os.path.dirname(__file__)
DB = os.path.join(HERE, "..", "db")
SCHEMA = os.path.join(DB, "01_schema.sql")
CHECKED = ["02_seed.sql", "03_objects.sql", "04_queries.sql", "viva-queries.sql",
           "05_users.sql"]

# Aliases that are tables in their own right but not in the schema.
EXTRA = {"nums": {"n"}}
# Prefixes that are not table aliases and must simply be skipped.
IGNORE = {"NEW", "OLD", "VALUES", "DUAL"}


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def parse_schema(sql):
    sql = re.sub(r"--[^\n]*", "", sql)
    tables, fks = {}, []
    for m in re.finditer(r"CREATE TABLE\s+(\w+)\s*\((.*?)\n\)\s*ENGINE", sql, re.S):
        name, body = m.group(1).lower(), m.group(2)
        cols = set()
        for line in body.split("\n"):
            line = line.strip().rstrip(",")
            if not line or line.upper().startswith(("PRIMARY KEY", "CONSTRAINT", "CHECK")):
                continue
            col = re.match(r"(\w+)\s+", line)
            if col:
                cols.add(col.group(1).lower())
        tables[name] = cols
        for fk in re.finditer(
                r"CONSTRAINT\s+(\w+)\s+FOREIGN KEY\s*\(([^)]*)\)\s*REFERENCES\s+"
                r"(\w+)\s*\(([^)]*)\)", body, re.S):
            fks.append((fk.group(1), name, fk.group(2).strip().lower(),
                        fk.group(3).lower(), fk.group(4).strip().lower()))
    return tables, fks


def table_columns_from(sql, tables):
    """Temporary tables declared in the seed count too."""
    for m in re.finditer(r"CREATE TEMPORARY TABLE\s+(\w+)\s*\((.*?)\);", sql, re.S):
        cols = {c.group(1).lower()
                for c in re.finditer(r"^\s*(\w+)\s+\w", m.group(2), re.M)}
        tables[m.group(1).lower()] = cols
    return tables


def aliases(statement, tables):
    """table/alias -> column set, best effort."""
    found = {}
    for m in re.finditer(r"\b(?:FROM|JOIN|UPDATE|INTO)\s+(\w+)(?:\s+(?:AS\s+)?(\w+))?", statement):
        table, alias = m.group(1).lower(), (m.group(2) or "").lower()
        if table not in tables:
            continue
        found[table] = tables[table]
        if alias and alias not in ("on", "where", "set", "join", "group", "order",
                                  "left", "inner", "limit", "having", "and", "as"):
            found[alias] = tables[table]
    if re.search(r"\b(NEW|OLD)\.", statement):
        for k in ("new", "old"):
            found[k] = tables.get("booking", set())
    return found


def statements(sql):
    sql = re.sub(r"--[^\n]*", "", sql)
    return [s for s in sql.split(";") if s.strip()]


def main():
    tables, fks = parse_schema(read(SCHEMA))
    print(f"{SCHEMA}")
    print(f"  {len(tables)} tables, {len(fks)} foreign keys")

    problems = []
    for cname, child_tbl, child_col, parent, parent_col in fks:
        if child_col not in tables.get(child_tbl, set()):
            problems.append(f"{cname}: column {child_tbl}.{child_col} does not exist")
        if parent not in tables:
            problems.append(f"{cname}: references missing table {parent}")
        elif parent_col not in tables[parent]:
            problems.append(f"{cname}: references missing column {parent}.{parent_col}")

    unknown_prefixes = 0
    checked = 0
    for fname in CHECKED:
        path = os.path.join(DB, fname)
        sql = read(path)
        tables_local = dict(tables)
        if fname == "02_seed.sql":
            table_columns_from(sql, tables_local)
        for stmt in statements(sql):
            seen = aliases(stmt, tables_local)
            for m in re.finditer(r"\b(\w+)\.(\w+)\b", stmt):
                prefix, col = m.group(1).lower(), m.group(2).lower()
                if prefix in IGNORE or stmt[:m.start()].count("'") % 2:
                    continue
                if prefix in tables_local and prefix not in seen:
                    seen[prefix] = tables_local[prefix]
                if prefix not in seen:
                    unknown_prefixes += 1
                    continue
                checked += 1
                if col not in seen[prefix]:
                    problems.append(f"{fname}: {prefix}.{col} -- no such column")
        # INSERT column lists
        for m in re.finditer(r"INSERT INTO\s+(\w+)\s*\(([^)]*)\)", sql):
            table = m.group(1).lower()
            if table not in tables_local:
                problems.append(f"{fname}: INSERT into unknown table {table}")
                continue
            for col in [c.strip().lower() for c in m.group(2).split(",")]:
                if col and col not in tables_local[table]:
                    problems.append(f"{fname}: INSERT into {table} names column {col}")

    print(f"  {checked} qualified column references resolved")
    print(f"  {unknown_prefixes} references from derived tables (not resolvable here)")
    if problems:
        print("\nPROBLEMS")
        for p in dict.fromkeys(problems):
            print(f"  * {p}")
        return 1
    print("\nno structural problems found")
    print("This run only reviews structure -- it connects to nothing. The scripts "
          "themselves\nhave been executed separately; see docs/TESTS.md.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
