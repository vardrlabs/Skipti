#!/usr/bin/env python3
"""Anchored edit for BUILD-LOG.md with assert-exactly-once discipline.

D-52, after TWO silent-drop incidents (changelog (12), evidence blocks
D-49/D-50): unasserted str.replace on the session-handoff document
silently removes content. Every BUILD-LOG edit goes through this.

Usage:
  buildlog_edit.py replace --old-file old.txt --new-file new.txt
  buildlog_edit.py replace --old-string '...' --new-string '...'
Exits 1 if the anchor matches zero or multiple times. Writes only after
the assertion passes.
"""
import argparse
import sys
from pathlib import Path

BUILD_LOG = Path("docs/BUILD-LOG.md")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["replace"])
    parser.add_argument("--old-string")
    parser.add_argument("--new-string")
    parser.add_argument("--old-file")
    parser.add_argument("--new-file")
    args = parser.parse_args()

    old = (
        args.old_string
        if args.old_string is not None
        else Path(args.old_file).read_text()
    )
    new = (
        args.new_string
        if args.new_string is not None
        else Path(args.new_file).read_text()
    )

    text = BUILD_LOG.read_text()
    count = text.count(old)
    if count != 1:
        print(
            f"REFUSED: anchor matches {count} times (must be exactly once). "
            "Fix the anchor; never write on a ambiguous match.",
            file=sys.stderr,
        )
        return 1
    BUILD_LOG.write_text(text.replace(old, new, 1))
    print(f"ok: replaced exactly once ({len(old)} -> {len(new)} chars)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
