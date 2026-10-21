#!/usr/bin/env python3
"""Structural check over docs/BUILD-LOG.md (D-123).

Incident 24 is the reason: buildlog_edit.py prevents an EDIT that drops
content, but cannot detect content already dropped. D-55's decision row
vanished with commit b7f2df8 and stayed gone for five units while five
other entries still cited it in prose. This check is a handful of
invariants that would have caught it at the next gate:

  1. D-number contiguity: the §2 implementation decision rows run
     D-01..D-max with no gaps and no duplicates.
  2. One row per number: each D-number appears exactly once.
  3. Every row is well-formed: exactly four table columns.
  4. Code fences are balanced (an unclosed fence swallows the rest of
     the document and every check with it).

Exits non-zero with the offending numbers printed loudly.
"""

import re
import sys

PATH = "docs/BUILD-LOG.md"


def main() -> int:
    with open(PATH, encoding="utf-8") as f:
        lines = f.read().splitlines()

    errors = []

    # 4. Code fences balanced: ``` toggles; an odd count means an unclosed
    # fence, which silently swallows content (and any checker inside it).
    fence_count = sum(1 for line in lines if line.strip().startswith("```"))
    if fence_count % 2 != 0:
        errors.append(f"code fences are UNBALANCED ({fence_count} fence lines)")

    # 1-3. The §2 implementation decision table: rows like `| D-01 | ... |`.
    numbers: list[int] = []
    for index, line in enumerate(lines, start=1):
        if not line.startswith("| D-"):
            continue
        # An escaped pipe (\|) is CONTENT (a literal pipe inside a cell),
        # not a column separator. D-69's row has carried one since D-69.
        unescaped = line.replace("\\|", "\x00")
        columns = unescaped.split("|")
        # '| a | b | c | d |' splits into ['', ' a ', ' b ', ' c ', ' d ', '']
        inner = [c.strip().replace("\x00", "\\|") for c in columns[1:-1]] if columns[0] == "" else columns
        if len(columns) < 2 or columns[0] != "" or columns[-1] != "":
            errors.append(f"line {index}: row does not start and end with '|': {line[:60]}...")
            continue
        if len(inner) != 4:
            errors.append(
                f"line {index}: decision row has {len(inner)} columns, expected 4: {line[:60]}..."
            )
        match = re.match(r"D-(\d+)", inner[0]) if inner else None
        if not match:
            errors.append(f"line {index}: first column is not a D-number: {inner[0] if inner else '?'}")
            continue
        numbers.append(int(match.group(1)))

    if not numbers:
        errors.append("no D-number decision rows found: the table is gone or malformed")

    seen: dict[int, int] = {}
    for number in numbers:
        seen[number] = seen.get(number, 0) + 1
    duplicates = sorted(n for n, count in seen.items() if count > 1)
    if duplicates:
        errors.append(f"duplicate D-numbers: {duplicates}")
    if numbers:
        expected = list(range(1, max(numbers) + 1))
        missing = sorted(set(expected) - set(numbers))
        if missing:
            errors.append(f"MISSING D-numbers (a dropped row, incident §5.24's shape): {missing}")

    if errors:
        print("BUILD-LOG STRUCTURE FAILED:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"BUILD-LOG STRUCTURE OK: {len(numbers)} decision rows, D-01..D-{max(numbers)} contiguous")
    return 0


if __name__ == "__main__":
    sys.exit(main())
