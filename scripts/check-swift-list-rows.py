#!/usr/bin/env python3
"""Gate leg (D-139): no app-styled button placed as a clear, zero-inset list row.

iOS 26 and later clip a clear-background List/Form row to a pill. A button
styled with PrimaryButtonStyle or SecondaryButtonStyle and made into such a
row (`.listRowBackground(Color.clear)` with `.listRowInsets(EdgeInsets())`)
rendered as a capsule, and a secondary button's outline was erased
altogether (D-135; seen on iOS 26.5 and 27). There is no API to change the
row's clip. The fix is to put the action under the form with
`.formActionBar { … }`, or inside a card row with real insets (D-135's
"Add another person").

Like the autolink leg, this is a scan: only a person looking at the screen
sees the bug, so a pattern check is the whole guard. From each styled
`.buttonStyle`, it walks the button's modifier chain in both directions
(lines that start with `.`, comment lines, and the continuation lines of a
multi-line argument, tracked by bracket depth) and flags either row
modifier at the chain's own level. The first version looked 8 lines ahead
and stopped at a line starting with `)`, which hid Sign in's button behind
its multi-line accessibility hint.

Usage: check-swift-list-rows.py [ROOT]   (default: ios/Skipti)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path("ios/Skipti")
STYLED = re.compile(r"\.buttonStyle\((?:Primary|Secondary)ButtonStyle\(")
ROW = re.compile(r"\.listRowBackground\(Color\.clear\)|\.listRowInsets\(EdgeInsets\(\)\)")
STRINGS = re.compile(r'"(?:[^"\\]|\\.)*"')


def balance(line):
    """Opening minus closing brackets, ignoring string literals and comments."""
    code = STRINGS.sub('""', line.split("//", 1)[0])
    return sum(code.count(c) for c in "([{") - sum(code.count(c) for c in ")]}")


def chain(lines, i, step):
    """Indices of the lines in the modifier chain through line i, walking by step."""
    out, depth, j = [], 0, i + step
    while 0 <= j < len(lines):
        text = lines[j].strip()
        if depth == 0 and not (text.startswith(".") or text.startswith("//") or not text):
            # Walking back, a line that opens a bracket the chain is inside of
            # (`.accessibilityHint(`) was already counted; anything else ends it.
            break
        out.append(j)
        depth += balance(text) * step
        if depth < 0:
            break
        j += step
    return out


def main() -> int:
    if not ROOT.is_dir():
        print(f"SWIFT LIST ROWS: root not found: {ROOT}")
        return 2
    hits = []
    for path in sorted(ROOT.rglob("*.swift")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if not STYLED.search(line):
                continue
            for j in chain(lines, i, +1) + chain(lines, i, -1):
                text = lines[j].strip()
                if text.startswith(".") and ROW.search(text):
                    hits.append(f"{path}:{j + 1}: {text}  (button styled at line {i + 1})")
    if hits:
        print("SWIFT LIST ROWS: a styled button made into a clear, zero-inset list row "
              "(iOS 26+ clips it to a pill; use .formActionBar or a card row with insets):")
        for h in hits:
            print("  " + h)
        return 1
    print("SWIFT LIST ROWS OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
