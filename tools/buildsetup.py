#!/usr/bin/env python3
"""
Builds db/00_setup.sql -- the one-shot script -- from the four files it
claims to contain, in order: 01 schema, 02 seed, 03 objects, 04 queries.

  python tools/buildsetup.py            # rebuild
  python tools/buildsetup.py --check    # verify it is current (exit 1 if stale)

The bundle's own banner (everything above the first section) is kept
verbatim, so the header you see stays the header you wrote. The four parts
are inserted exactly as they are on disk: nothing is reformatted, and the
script refuses to write if a part already appears more than once or if the
banner cannot be found.

No third-party dependencies.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
PARTS = [os.path.join(ROOT, "db", name) for name in (
    "01_schema.sql", "02_seed.sql", "03_objects.sql", "04_queries.sql")]
SETUP = os.path.join(ROOT, "db", "00_setup.sql")

# First line of section 1; everything before it is the bundle's own banner.
FIRST_SECTION = "--  CineBook  --  Cinema Ticketing Management System"


class BuildError(Exception):
    pass


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def banner():
    """The bundle's preamble: its own rule block, up to section 1.

    The cut is the start of the rule line that frames section 1, not the
    text of section 1 itself -- otherwise that rule line would be kept in
    the preamble and then appear a second time from 01_schema.sql.
    """
    lines = read(SETUP).splitlines(keepends=True)
    for i, line in enumerate(lines):
        if FIRST_SECTION not in line:
            continue
        j = i
        while j > 0:
            # A rule line is '--' then only '=' characters.
            above = lines[j - 1].strip().lstrip("-").strip()
            if not above or set(above) != {"="}:
                break
            j -= 1
        head = "".join(lines[:j])
        if not head.lstrip().startswith("--"):
            raise BuildError("db/00_setup.sql preamble is not a comment block")
        return head
    raise BuildError(
        "db/00_setup.sql has no section banner to preserve - refusing to "
        "guess where the preamble ends")


def assemble():
    """The bundle exactly as it should be: banner, then 01-04 with a blank line."""
    parts = [read(p) for p in PARTS]
    names = [os.path.basename(p) for p in PARTS]

    for name, body in zip(names, parts):
        if not body.strip():
            raise BuildError(f"db/{name} is empty")

    # Each part ends with a newline; joining on a blank line keeps the
    # sections visually separated without editing their contents.
    body = "\n\n".join(p.rstrip("\n") for p in parts) + "\n"
    doc = banner() + body

    # Nothing may appear twice: a duplicated section is exactly the fault
    # that made the old bundle unrunnable (CREATE TABLE twice, queries twice).
    for name, part in zip(names, parts):
        if doc.count(part) != 1:
            raise BuildError(
                f"db/{name} appears {doc.count(part)} times in the assembled "
                "bundle - expected exactly once")
    return doc


def main():
    check = "--check" in sys.argv
    try:
        doc = assemble()
    except BuildError as exc:
        print(f"REFUSING TO WRITE - {exc}")
        return 1

    current = read(SETUP)
    if check:
        if current != doc:
            print("db/00_setup.sql is STALE - run: python tools/buildsetup.py")
            return 1
        print("db/00_setup.sql is up to date")
        return 0

    if current == doc:
        print("db/00_setup.sql is up to date")
        return 0

    with open(SETUP, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(doc)
    print(f"wrote db/00_setup.sql ({doc.count(chr(10)) + 1} lines, "
          f"{len(doc)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
