#!/usr/bin/env python3
"""Gate leg (D-128): no email address or URL inside a SwiftUI string-literal title.

A string literal passed as a SwiftUI title (Text("..."), TextField("...", ...),
Button("..."), and so on) is a LocalizedStringKey, and SwiftUI parses it as
inline Markdown. An email address or URL inside it becomes a LINK, drawn in
the system link colour. D-128 shipped exactly that: Sign in's placeholder
"you@example.com" rendered in iOS blue on a steel design, it compiled, and it
passed every test. Two explanations were tested on device and falsified
(styling the prompt with .foregroundStyle, and adding the missing AccentColor
asset) before Text(verbatim:) turned it grey and confirmed the cause.

The fix for a flagged line is `Text(verbatim: "...")` (or a `prompt:` built
from one). `Link` is deliberately not checked: its title is meant to be a link.

Usage: check-swift-autolink.py [ROOT]   (default: ios/Skipti)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path("ios/Skipti")

CALLS = r"\b(?:Text|TextField|SecureField|Label|Button|NavigationLink|Toggle|Picker|Section)"
LITERAL = re.compile(CALLS + r'\(\s*"([^"\\]*(?:\\.[^"\\]*)*)"')
LINKY = re.compile(
    r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"   # email address
    r"|https?://|www\."                                    # URL
    r"|\b[a-z0-9-]+\.(?:com|org|net|io|app|dev)\b",        # bare domain
    re.IGNORECASE,
)


def main() -> int:
    if not ROOT.is_dir():
        print(f"SWIFT AUTOLINK: root not found: {ROOT}")
        return 2
    hits = []
    for path in sorted(ROOT.rglob("*.swift")):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for match in LITERAL.finditer(line):
                if LINKY.search(match.group(1)):
                    hits.append(f"{path}:{number}: {match.group(0)[:100]}")
    if hits:
        print("SWIFT AUTOLINK FAILED: a string-literal title SwiftUI will render as a link:")
        for hit in hits:
            print(f"  {hit}")
        print("Use Text(verbatim:) for literal text that contains an email or URL.")
        return 1
    print("SWIFT AUTOLINK OK: no string-literal UI title contains an email or URL")
    return 0


if __name__ == "__main__":
    sys.exit(main())
