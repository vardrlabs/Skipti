#!/usr/bin/env python3
"""Gate leg (D-228): no em dash (U+2014) and no en dash (U+2013) on SKIPTI's
public surface, the text people face.

The founder reads em dashes as "sounds like AI". The repository is private, so
the rule covers what leaves it (decision 42): what the apps show, what the
website serves, and the public docs. Code comments, tests, scripts, internal
docs and the agent guides are out of scope.

  python3 scripts/check-em-dashes.py [--scope public]
      The gate leg. Exit 0 and print `EM DASH CHECK OK: ...`, or exit 1 with
      one `path:line: form (where): excerpt` row per hit and the rule to apply.
  python3 scripts/check-em-dashes.py --scope whole-repo
      Not a gate leg: the check to run, and make pass, before the repository is
      made public (the move from internal builds to the public beta), when
      everything in it becomes public. Every text file git lists, comments,
      tests, scripts and internal docs included; third-party texts (the licence
      inventory, lock files, node_modules) are skipped. Exit 1 lists every hit,
      then a count per file and per top-level area.

The public surface, among the files git lists (tracked, plus untracked files
git does not ignore, so a new file is caught before `git add`):
  app code, everything but comments: the iOS app (every Swift file under
      ios/Skipti, the Relay sign-in files included, but not the generated
      UniFFI bindings), the Android app's non-test source sets (Kotlin; not
      the generated UniFFI bindings), the Rust core (core/src: its errors reach
      both phones), the Cloud Functions (their push notifications reach phones)
      and scripts/licences.py (it writes the Licences page both apps show; the
      third-party licence texts in licences.json are not checked);
  whole files: Android resources and manifests, the iOS app's Info.plist and
      strings files, the Hosting pages (firebase/public, firebase/admin), README, CONTRIBUTING,
      GUARANTEES, SECURITY, THREAT_MODEL, NOTICE and docs/RELEASE*.md.
Not in scope, by decision: data files the app reads but never shows as
written, such as core/src/merki/merchants_starter.txt (merchant match keys).

A public file must be UTF-8, or UTF-16 with a byte order mark; any other public
file fails as unreadable (never skipped silently). In the whole-repo scope a
file outside the public surface that is not text (an image, a font) is skipped.

What counts as a dash, case-insensitive: the two characters; the escapes that
build one at run time (backslash u 2014, backslash u {2014}, and the same for
2013); the HTML entities (named, decimal and hex); and the hex constants (zero,
x, then the code point). This file builds every pattern from fragments, so it
carries none of them. A line marked `em-dash-ok: <reason>` (at least three
words), or the line right under the marker, is exempt, but only in code (Swift,
Kotlin, Rust, JS, Python) that is not a whole public file: it is for code whose
input is itself a dash under test. A public doc or resource has no dash under
test, so a marker there exempts nothing.
"""
import bisect
import collections
import io
import os
import pathlib
import re
import subprocess
import sys
import tokenize

EM = chr(0x2000 + 0x14)
EN = chr(0x2000 + 0x13)
_EM_HEX, _EN_HEX = "20" + "14", "20" + "13"
_EM_DEC, _EN_DEC = str(0x2000 + 0x14), str(0x2000 + 0x13)
_BS = re.escape(chr(92))

# (label, pattern): one entry per form family and code point.
FORMS = (
    ("U+2014", re.escape(EM)),
    ("U+2013", re.escape(EN)),
    ("an escape of U+2014", _BS + r"u\{?0*" + _EM_HEX + r"\}?"),
    ("an escape of U+2013", _BS + r"u\{?0*" + _EN_HEX + r"\}?"),
    ("an HTML entity for U+2014", "&(?:m" + "dash|#0*" + _EM_DEC + "|#x0*" + _EM_HEX + ");"),
    ("an HTML entity for U+2013", "&(?:n" + "dash|#0*" + _EN_DEC + "|#x0*" + _EN_HEX + ");"),
    ("a hex constant of U+2014", r"(?<![0-9a-z_])0x0*" + _EM_HEX + r"(?![0-9a-z_])"),
    ("a hex constant of U+2013", r"(?<![0-9a-z_])0x0*" + _EN_HEX + r"(?![0-9a-z_])"),
)
_ANY = re.compile("|".join(f"(?P<f{i}>{p})" for i, (_, p) in enumerate(FORMS)), re.IGNORECASE)

MARKER = re.compile(r"em-dash-ok:[ \t]*\w+(?:[^\w\n]+\w+){2,}")

PUBLIC_DOCS = ("README.md", "CONTRIBUTING.md", "GUARANTEES.md", "SECURITY.md", "THREAT_MODEL.md", "NOTICE")
LICENCES_SCRIPT = "scripts/licences.py"
CODE_SUFFIXES = (".swift", ".kt", ".kts", ".rs", ".js", ".mjs", ".cjs", ".py")
TEXT_SUFFIXES = (".html", ".htm", ".css", ".js", ".json", ".txt", ".xml", ".svg", ".webmanifest")
_ANDROID_SET = re.compile(r"android/app/src/([^/]+)/")
_IOS_STRINGS = (".strings", ".xcstrings", ".stringsdict")

RULE = ("Public text uses a comma, a colon or a full stop, and the word \"to\" in a range. "
        "App code whose input is itself a dash builds it from its code point, or carries "
        "`em-dash-ok: <reason>` on that line or the line above.")


def _android_app_set(path):
    """The Android source set of path when it is one the app ships, else None."""
    m = _ANDROID_SET.match(path)
    if not m or m.group(1).startswith(("test", "androidTest")):
        return None
    return m.group(1)


def code_language(path):
    """The language whose non-comment text is public, or None."""
    if (path.startswith("ios/Skipti/") and path.endswith(".swift")
            and not path.startswith("ios/Skipti/Bindings/")):
        return "swift"
    if (path.endswith(".kt") and _android_app_set(path)
            and not path.startswith("android/app/src/main/kotlin/uniffi/")):
        return "kotlin"
    if path.startswith("core/src/") and path.endswith(".rs"):
        return "rust"
    if (path.startswith("firebase/functions/") and path.endswith((".js", ".mjs", ".cjs"))
            and "node_modules/" not in path):
        return "js"
    if path == LICENCES_SCRIPT:
        return "python"
    return None


def whole_file(path):
    """True when every line of path is public text."""
    if path in PUBLIC_DOCS or re.fullmatch(r"docs/RELEASE[^/]*\.md", path):
        return True
    if path.startswith(("firebase/public/", "firebase/admin/")):
        name = path.rsplit("/", 1)[-1]
        return path.endswith(TEXT_SUFFIXES) or "." not in name
    if _android_app_set(path) and (path.endswith("/AndroidManifest.xml")
                                   or ("/res/" in path and path.endswith(".xml"))):
        return True
    if path == "ios/Skipti/Info.plist":
        return True
    return path.startswith("ios/Skipti/") and path.endswith(_IOS_STRINGS)


def public(path):
    return whole_file(path) or code_language(path) is not None


def marker_honoured(path):
    """True where `em-dash-ok:` may exempt a line: code, never a whole public file."""
    return path.endswith(CODE_SUFFIXES) and not whole_file(path)


# Third-party texts: never rewritten, so never checked, in either scope.
THIRD_PARTY = ("native/licences/licences.json", "firebase/functions/package-lock.json",
               "firebase/package-lock.json")


def in_scope(path, scope):
    if path in THIRD_PARTY or "node_modules/" in path:
        return False
    return scope == "whole-repo" or public(path)


# --------------------------------------------------------------------------- lexer
# Ported from .drafts/d228/inventory.py (D-228's inventory): marks comment and
# string spans in Swift, Kotlin, Rust and JS. Only the comment spans matter here.
class Lexer:
    def __init__(self, text, lang):
        self.t, self.n, self.lang = text, len(text), lang
        self.comments = []
        self.opener = "(" if lang == "swift" else "{"

    def run(self):
        self.code(0, None)
        return self

    def code(self, i, closer):
        t, n = self.t, self.n
        depth = 0
        while i < n:
            if t.startswith("//", i):
                j = t.find("\n", i)
                j = n if j < 0 else j
                self.comments.append((i, j))
                i = j
                continue
            if t.startswith("/*", i):
                j, d = i + 2, 1
                nested = self.lang != "js"
                while j < n and d:
                    if nested and t.startswith("/*", j):
                        d, j = d + 1, j + 2
                    elif t.startswith("*/", j):
                        d, j = d - 1, j + 2
                    else:
                        j += 1
                self.comments.append((i, j))
                i = j
                continue
            started = self.try_string(i)
            if started is not None:
                i = started
                continue
            if closer is not None:
                if t[i] == self.opener:
                    depth += 1
                elif t[i] == closer:
                    if depth == 0:
                        return i + 1
                    depth -= 1
            i += 1
        return i

    def try_string(self, i):
        t, n, lang, c = self.t, self.n, self.lang, self.t[i]
        if lang == "swift":
            if c in '#"':
                h = 0
                while i + h < n and t[i + h] == "#":
                    h += 1
                if i + h < n and t[i + h] == '"':
                    if c == "#" and i > 0 and (t[i - 1].isalnum() or t[i - 1] == "_"):
                        return None
                    triple = t.startswith('"""', i + h)
                    return self.string(i + h + (3 if triple else 1), '"""' if triple else '"', h)
            return None
        if lang == "kotlin":
            if c == '"':
                triple = t.startswith('"""', i)
                return self.string(i + (3 if triple else 1), '"""' if triple else '"', 0, not triple)
            if c == "'":
                return self.char(i)
            return None
        if lang == "rust":
            prev = t[i - 1] if i else " "
            if c in "rb" and not (prev.isalnum() or prev == "_"):
                j = i + (2 if t.startswith("br", i) else 1)
                if c == "r" or t.startswith("br", i):
                    h = 0
                    while j + h < n and t[j + h] == "#":
                        h += 1
                    if j + h < n and t[j + h] == '"':
                        return self.string(j + h + 1, '"', h, False)
                    return None
                if j < n and t[j] == '"':
                    return self.string(j + 1, '"', 0)
                return None
            if c == '"':
                return self.string(i + 1, '"', 0)
            if c == "'" and (t.startswith("'\\", i) or (i + 2 < n and t[i + 2] == "'")):
                return self.char(i)
            return None
        if c in "\"'`":
            return self.string(i + 1, c, 0)
        return None

    def char(self, i):
        j = i + 1
        if j < self.n and self.t[j] == "\\":
            j += 2
            while j < self.n and self.t[j] != "'" and j - i < 12:
                j += 1
        else:
            j += 1
        return j + 1

    def string(self, i, delim, hashes, escapes=True):
        t, n, lang = self.t, self.n, self.lang
        end_tok = delim + "#" * hashes
        esc_tok = "\\" + "#" * hashes
        while i < n:
            if t.startswith(end_tok, i):
                return i + len(end_tok)
            if lang == "swift":
                if t.startswith(esc_tok, i):
                    k = i + len(esc_tok)
                    if k < n and t[k] == "(":
                        i = self.code(k + 1, ")")
                        continue
                    i = k + 1
                    continue
            elif lang == "kotlin" or (lang == "js" and delim == "`"):
                if escapes and t[i] == "\\":
                    i += 2
                    continue
                if t.startswith("${", i):
                    i = self.code(i + 2, "}")
                    continue
            elif escapes and t[i] == "\\":
                i += 2
                continue
            i += 1
        return n


def python_comments(text):
    """Comment spans of Python source from the stdlib tokenizer; none (so every
    line counts) when the text does not tokenize."""
    starts = [0]
    for line in text.split("\n")[:-1]:
        starts.append(starts[-1] + len(line) + 1)
    out = []
    try:
        for tok in tokenize.generate_tokens(io.StringIO(text).readline):
            if tok.type == tokenize.COMMENT:
                (r0, c0), (r1, c1) = tok.start, tok.end
                out.append((starts[r0 - 1] + c0, starts[r1 - 1] + c1))
    except (tokenize.TokenError, SyntaxError):
        return []
    return out


def comment_spans(text, lang):
    spans = sorted(python_comments(text) if lang == "python" else Lexer(text, lang).run().comments)
    return [s for s, _ in spans], spans


# ------------------------------------------------------------------------ checking
Hit = collections.namedtuple("Hit", "line start end label marked")


class Finding(collections.namedtuple("Finding", "path line label where excerpt")):
    def render(self):
        return f"{self.path}:{self.line}: {self.label} ({self.where}): {self.excerpt}"


_BOMS = (b"\xff\xfe", b"\xfe\xff")


def decode(data):
    """Text, or None for a file that is neither UTF-8 nor UTF-16 with a byte
    order mark, or that holds a NUL (binary)."""
    if isinstance(data, str):
        return None if "\x00" in data else data
    if data.startswith(_BOMS):
        try:
            text = data.decode("utf-16")
        except UnicodeDecodeError:
            return None
        return None if "\x00" in text else text
    if b"\x00" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def raw_hits(text, markers=True):
    """Every form in text, in order; marked only where markers are honoured."""
    if not _ANY.search(text):
        return []
    lines = text.split("\n")
    marked_lines = {i + 1 for i, line in enumerate(lines) if markers and MARKER.search(line)}
    breaks = [i for i, c in enumerate(text) if c == "\n"]
    out = []
    for m in _ANY.finditer(text):
        line = bisect.bisect_left(breaks, m.start()) + 1
        label = FORMS[int(m.lastgroup[1:])][0]
        marked = line in marked_lines or (line - 1) in marked_lines
        out.append(Hit(line, m.start(), m.end(), label, marked))
    return out


def excerpt(text, start, end):
    s = text[max(0, start - 30):end + 30].replace("\r", " ").replace("\n", " ")
    return s.replace(EM, "<U+2014>").replace(EN, "<U+2013>").strip()


def check(files, stats=None, scope="public"):
    """Findings for files ({path: bytes or str}): every unmarked hit in scope.

    public: the public surface, comments in app code exempt. whole-repo: every
    text file, every line (comments too)."""
    findings = []
    counts = {"files": 0, "marked": 0}
    for path in sorted(files):
        if not in_scope(path, scope):
            continue
        lang = code_language(path) if scope == "public" else None
        text = decode(files[path])
        if text is None:
            if public(path):
                findings.append(Finding(path, 1, "not readable as UTF-8, or UTF-16 with a byte order mark",
                                        "public file", "the guard cannot read it, so it cannot pass it"))
            continue
        counts["files"] += 1
        hits = raw_hits(text, marker_honoured(path))
        if not hits:
            continue
        counts["marked"] += len({h.line for h in hits if h.marked})
        starts, spans = comment_spans(text, lang) if lang else ([], [])
        for h in hits:
            if h.marked:
                continue
            k = bisect.bisect_right(starts, h.start) - 1
            if k >= 0 and spans[k][0] <= h.start < spans[k][1]:
                continue  # a comment: not public
            where = f"{lang} code" if lang else ("public file" if scope == "public" else "whole repo")
            findings.append(Finding(path, h.line, h.label, where, excerpt(text, h.start, h.end)))
    if stats is not None:
        stats.update(counts)
    return findings


def collect(root, scope="public"):
    """{path: bytes} for every file in scope git lists: tracked, and untracked but not ignored."""
    out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                         cwd=root, capture_output=True, check=True).stdout
    files = {}
    for raw in out.split(b"\0"):
        path = raw.decode("utf-8", "surrogateescape")
        if not path or path in files or not in_scope(path, scope):
            continue
        try:
            with open(os.path.join(root, path), "rb") as f:
                files[path] = f.read()
        except (FileNotFoundError, IsADirectoryError, NotADirectoryError):
            continue  # deleted in the working tree, or a submodule
    return files


def summary(findings):
    """Lines counting the hits per file, then per top-level area."""
    per_file = collections.Counter(f.path for f in findings)
    per_area = collections.Counter(p.split("/", 1)[0] if "/" in p else "(root files)"
                                   for p in (f.path for f in findings))
    out = ["Per file:"]
    out += [f"  {n:6d}  {p}" for p, n in sorted(per_file.items(), key=lambda kv: (-kv[1], kv[0]))]
    out.append("Per top-level area:")
    out += [f"  {n:6d}  {a}" for a, n in sorted(per_area.items(), key=lambda kv: (-kv[1], kv[0]))]
    return out


def main(argv):
    scope = "public"
    if argv[:1] == ["--scope"] and len(argv) == 2 and argv[1] in ("public", "whole-repo"):
        scope = argv[1]
    elif argv:
        print("usage: python3 scripts/check-em-dashes.py [--scope public | --scope whole-repo]")
        return 2
    root = pathlib.Path(__file__).resolve().parent.parent
    stats = {}
    findings = check(collect(root, scope), stats, scope)
    label = "public files" if scope == "public" else "text files in the whole repo"
    if findings:
        for f in findings:
            print(f.render())
        if scope == "whole-repo":
            print("\n".join(summary(findings)))
        print(f"EM DASH CHECK FAILED ({scope}): {len(findings)} hit(s) in "
              f"{len({f.path for f in findings})} file(s), {stats['files']} {label} read.")
        print(RULE)
        return 1
    print(f"EM DASH CHECK OK ({scope}): {stats['files']} {label}, 0 dashes, {stats['marked']} marked lines")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
