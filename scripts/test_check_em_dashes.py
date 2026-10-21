#!/usr/bin/env python3
"""Gate leg (D-228): the self-test of scripts/check-em-dashes.py.

The checker keeps U+2014 (em dash) and U+2013 (en dash) off SKIPTI's public
surface (decision 42): the apps' text (everything in app code but comments),
resources, the Hosting pages and the public docs, in every form a file can
carry one. Every way it could wrongly say OK has a case here, and so does
every way it could reach past the public surface (a comment, a test, an
internal doc, the generated bindings): those stay quiet in the public scope
and fail in the whole-repo scope, the check run before the repo goes public.

This file names no dash in any form: every form is built from its code point
or from fragments, and a case below checks this file and the checker for it.

Run: python3 scripts/test_check_em_dashes.py
"""
import importlib.util
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
CHECKER = HERE / "check-em-dashes.py"
GATE = HERE / "gate.sh"

_spec = importlib.util.spec_from_file_location("check_em_dashes", CHECKER)
ced = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ced)

# Git commands below run in throwaway repositories. This leg also runs inside
# the pre-commit hook, where git exports GIT_INDEX_FILE: a git command that
# inherited it would read or write the real repository's index.
for _var in subprocess.run(["git", "rev-parse", "--local-env-vars"], capture_output=True,
                           text=True, check=True).stdout.split():
    os.environ.pop(_var, None)
os.environ.update({
    "GIT_CONFIG_GLOBAL": os.devnull,
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_AUTHOR_NAME": "dash test", "GIT_AUTHOR_EMAIL": "dash@test.invalid",
    "GIT_COMMITTER_NAME": "dash test", "GIT_COMMITTER_EMAIL": "dash@test.invalid",
})

BS = chr(92)
EM = chr(0x2000 + 0x14)
EN = chr(0x2000 + 0x13)
EM_HEX, EN_HEX = "20" + "14", "20" + "13"
EM_DEC, EN_DEC = str(0x2000 + 0x14), str(0x2000 + 0x13)

# The forms of Design 6, by family; upper-case spellings ride with their family
# because the checker is case-insensitive.
EM_ESCAPES = {"escape": BS + "u" + EM_HEX, "braced escape": BS + "u{" + EM_HEX + "}"}
EN_ESCAPES = {"escape": BS + "u" + EN_HEX, "braced escape": BS + "u{" + EN_HEX + "}"}
ENTITIES = {
    "em named": "&" + "m" + "dash;", "en named": "&" + "n" + "dash;",
    "em decimal": "&#" + EM_DEC + ";", "en decimal": "&#" + EN_DEC + ";",
    "em hex": "&#x" + EM_HEX + ";", "en hex": "&#x" + EN_HEX + ";",
    "em named, upper case": "&" + "M" + "DASH;", "em hex, upper case": "&#X" + EM_HEX + ";",
}
CODE = {"em": "0x" + EM_HEX, "en": "0x" + EN_HEX, "em, upper case": "0X" + EM_HEX}

PUBLIC_DOC = "README.md"
SWIFT = "ios/Skipti/App/SettleAll.swift"
KOTLIN = "android/app/src/main/java/com/vardrlabs/skipti/SettleAll.kt"
KOTLIN_PLAY = "android/app/src/play/java/com/vardrlabs/skipti/GoogleSignInButton.kt"
RUST = "core/src/merki/mod.rs"
FUNCTIONS = "firebase/functions/push.js"
# Compiled into the app outside ios/Skipti/App (pbxproj: path = Skipti/Relay/...).
SWIFT_RELAY = ("ios/Skipti/Relay/GoogleSignIn.swift", "ios/Skipti/Relay/AppleSignIn.swift")
# Writes the Licences page both apps show (native/licences/licences.json).
LICENCES = "scripts/licences.py"

TEST_FILES = ("ios/SkiptiTests/SettleAllTests.swift",
              "android/app/src/test/java/com/vardrlabs/skipti/SettleAllTest.kt",
              "android/app/src/testPlay/java/com/vardrlabs/skipti/GoogleFlavourTest.kt",
              "android/app/src/androidTest/java/com/vardrlabs/skipti/UiTest.kt",
              "core/tests/ledger_port.rs", "firebase/tests/people.test.mjs")
INTERNAL = ("CLAUDE.md", "AGENTS.md", "docs/BUILD-LOG.md", "docs/WORKLOG.md", "docs/SPEC.md",
            "docs/adr/0001-x.md", "docs/superpowers/plans/x.md", "scripts/gate.sh",
            ".githooks/commit-msg", "Makefile", "deploy/skipti-backup.sh", "brand/README.md",
            "scripts/gate_inputs.py")
BINDINGS = ("android/app/src/main/kotlin/uniffi/skipti_core/skipti_core.kt",
            "ios/Skipti/Bindings/skipti_core.swift")
THIRD_PARTY = ("native/licences/licences.json", "firebase/package-lock.json",
               "firebase/functions/package-lock.json", "firebase/functions/node_modules/x/index.js")


def doc(form):
    return "line one is clean\nsome public text a " + form + " b\nline three is clean\n"


def literal(form):
    return "// a clean comment\nlet s = \"a " + form + " b\"\n"


def comment(form):
    return "// a comment a " + form + " b\nlet s = \"clean\"\n/* block " + form + " */\n"


def found(files, scope="public"):
    return [(f.path, f.line) for f in ced.check(files, scope=scope)]


class FormTests(unittest.TestCase):
    """Each form fails in a public file."""

    def assertFamilyFails(self, family):
        for name, form in family.items():
            with self.subTest(form=name):
                self.assertEqual(found({PUBLIC_DOC: doc(form)}), [(PUBLIC_DOC, 2)])

    def test_em_literal(self):
        self.assertFamilyFails({"U+2014": EM})

    def test_en_literal(self):
        self.assertFamilyFails({"U+2013": EN, "U+2013 in a range": "1" + EN + "7"})

    def test_em_escapes(self):
        self.assertFamilyFails(EM_ESCAPES)

    def test_en_escapes(self):
        self.assertFamilyFails(EN_ESCAPES)

    def test_html_entities(self):
        self.assertFamilyFails(ENTITIES)

    def test_code_constants(self):
        self.assertFamilyFails(CODE)

    def test_the_finding_names_path_line_form_and_where(self):
        (f,) = ced.check({SWIFT: literal(EM)})
        text = f.render()
        self.assertTrue(text.startswith(SWIFT + ":2: U+2014 (swift code): "), text)
        self.assertNotIn(EM, text, "the report spells the code point, not the glyph")

    def test_a_clean_file_passes(self):
        clean = ("a - b, a -- b, 1-7, 1 to 7, x → y, wait…, ── rule, minus − 1,\n"
                 "the year 2014, U+2014 and U+2013 named in prose, 0x20140, &mdash without a semicolon\n")
        for path in (PUBLIC_DOC, SWIFT, KOTLIN, RUST, FUNCTIONS):
            for scope in ("public", "whole-repo"):
                with self.subTest(path=path, scope=scope):
                    self.assertEqual(found({path: clean}, scope), [])


class PublicScopeTests(unittest.TestCase):
    """A dash in public text fails, wherever the public text lives."""

    def test_swift_literal(self):
        self.assertEqual(found({SWIFT: literal(EM)}), [(SWIFT, 2)])
        interp = "let s = \"Paid \\(name) " + EM + " thanks\"\nlet t = \"\"\"\n  a " + EN + " b\n  \"\"\"\n"
        self.assertEqual(found({SWIFT: interp}), [(SWIFT, 1), (SWIFT, 3)])

    def test_kotlin_literal(self):
        for path in (KOTLIN, KOTLIN_PLAY):
            with self.subTest(path=path):
                self.assertEqual(found({path: literal(EM_ESCAPES["escape"])}), [(path, 2)])
        tmpl = "val s = \"Paid ${name} " + EM + "\"\nval c = '" + EM_ESCAPES["escape"] + "'\n"
        self.assertEqual(found({KOTLIN: tmpl}), [(KOTLIN, 1), (KOTLIN, 2)])

    def test_rust_literal(self):
        self.assertEqual(found({RUST: literal(EM_ESCAPES["braced escape"])}), [(RUST, 2)])
        raw = "let s = r#\"raw " + EM + " text\"#;\n"
        self.assertEqual(found({RUST: raw}), [(RUST, 1)])

    def test_functions_literal(self):
        js = "const t = `${who} added ${what} " + EM + "`;\nconst u = 'a " + EN + " b';\n"
        self.assertEqual(found({FUNCTIONS: js}), [(FUNCTIONS, 1), (FUNCTIONS, 2)])

    def test_swift_outside_the_app_folder(self):
        for path in SWIFT_RELAY:
            with self.subTest(path=path):
                self.assertEqual(found({path: literal(EM)}), [(path, 2)])
                self.assertEqual(found({path: comment(EM)}), [], "its comments are not public")

    def test_the_licences_script_literals(self):
        py = "# a comment " + EM + " here\nNOTE = \"Google's library " + EM + " its terms\"\n"
        self.assertEqual(found({LICENCES: py}), [(LICENCES, 2)])

    def test_app_code_outside_strings_counts(self):
        code = "let dash = Character(UnicodeScalar(" + CODE["em"] + ")!)\n"
        self.assertEqual(found({SWIFT: code}), [(SWIFT, 1)])

    def test_whole_public_files(self):
        for path in ("README.md", "CONTRIBUTING.md", "GUARANTEES.md", "SECURITY.md", "THREAT_MODEL.md",
                     "NOTICE", "docs/RELEASE-0.5.0.md", "firebase/public/privacy/index.html",
                     "firebase/public/404.html", "firebase/public/.well-known/apple-app-site-association",
                     "android/app/src/main/res/xml/data_extraction_rules.xml",
                     "android/app/src/main/res/values/strings.xml",
                     "android/app/src/main/AndroidManifest.xml", "android/app/src/play/AndroidManifest.xml",
                     "ios/Skipti/Info.plist", "ios/Skipti/App/Localizable.xcstrings"):
            with self.subTest(path=path):
                self.assertEqual(found({path: doc(EM)}), [(path, 2)])
        res = "android/app/src/main/res/values/strings.xml"
        xml_comment = "<!-- an XML comment " + EM + " in a resource -->\n"
        self.assertEqual(found({res: xml_comment}), [(res, 1)], "a whole file counts, comments too")


class NotPublicTests(unittest.TestCase):
    """The narrowed scope (decision 42): quiet in the public scope."""

    def test_a_comment_in_app_code_is_not_public(self):
        for path in (SWIFT, KOTLIN, RUST, FUNCTIONS):
            with self.subTest(path=path):
                self.assertEqual(found({path: comment(EM)}), [])
        doc_comment = "/// A doc comment " + EM + " for a function\npub fn f() {}\n"
        self.assertEqual(found({RUST: doc_comment}), [])

    def test_test_files_are_not_public(self):
        for path in TEST_FILES:
            with self.subTest(path=path):
                self.assertEqual(found({path: literal(EM)}), [])

    def test_internal_docs_and_tools_are_not_public(self):
        for path in INTERNAL:
            with self.subTest(path=path):
                self.assertEqual(found({path: doc(EM)}), [])

    def test_the_generated_bindings_are_not_public(self):
        for path in BINDINGS:
            with self.subTest(path=path):
                self.assertEqual(found({path: literal(EM)}), [])

    def test_a_binary_file_outside_the_public_surface_is_skipped(self):
        for name, data in (("a NUL", b"\x89PNG\r\n\x1a\n\x00\x00" + doc(EM).encode()),
                           ("cp1252", b"\x93quoted\x94 " + doc(EM).encode())):
            with self.subTest(data=name):
                self.assertEqual(found({"brand/logo.png": data}, "whole-repo"), [])

    def test_bytes_and_text_read_the_same(self):
        self.assertEqual(found({PUBLIC_DOC: doc(EM).encode()}), [(PUBLIC_DOC, 2)])


class UnreadableTests(unittest.TestCase):
    """A public file the guard cannot read fails; it is never skipped silently."""

    def test_utf16_with_a_bom_is_read(self):
        for codec in ("utf-16-le", "utf-16-be"):
            data = ("﻿" + doc(EM)).encode(codec)
            for path in (PUBLIC_DOC, "ios/Skipti/App/Localizable.strings"):
                with self.subTest(codec=codec, path=path):
                    self.assertEqual(found({path: data}), [(path, 2)])
        stats = {}
        self.assertEqual(ced.check({PUBLIC_DOC: "﻿clean text\n".encode("utf-16-le")}, stats), [])
        self.assertEqual(stats["files"], 1)

    def test_a_public_file_that_is_not_text_fails(self):
        for name, data in (("cp1252", b"\x93quoted\x94 " + doc(EM).encode()),
                           ("a NUL", b"head\x00\n" + doc(EM).encode()),
                           ("utf-16 without a BOM", doc(EM).encode("utf-16-le"))):
            for scope in ("public", "whole-repo"):
                with self.subTest(data=name, scope=scope):
                    (f,) = ced.check({PUBLIC_DOC: data}, scope=scope)
                    self.assertEqual((f.path, f.line), (PUBLIC_DOC, 1))
                    self.assertIn("not readable", f.render())


class LexerTests(unittest.TestCase):
    """The lexer decides what is a comment, and a dash it wrongly puts in a
    comment passes the gate. A simpler lexer gets each case wrong that way."""

    CASES = (
        ("swift: // inside a string", SWIFT, 'let u = "https://skipti.app ' + EM + ' y"\n', [1]),
        ("kotlin: /* inside a string", KOTLIN, 'val u = "a /* b ' + EM + ' c"\nval v = 1\n', [1]),
        ("rust: /* inside a string", RUST, 'let u = "a /* b"; let v = "c ' + EM + '";\n', [1]),
        ("js: // inside a template", FUNCTIONS, "const u = `see https://x.y " + EM + "`;\n", [1]),
        ("swift: a quote in a comment", SWIFT, '// say "hi\nlet s = "a // b ' + EM + ' c"\n', [2]),
        ("kotlin: a quote in a comment", KOTLIN, '// it\'s "x\nval s = "a // b ' + EM + ' c"\n', [2]),
        ("kotlin: a block comment over lines", KOTLIN,
         '/* one\n two " three\n */\nval s = "x // y ' + EM + ' z"\n', [4]),
        ("swift: a raw string", SWIFT, 'let s = #"one " two // ' + EM + '"#\n', [1]),
        ("swift: a string inside an interpolation", SWIFT,
         'let s = "a ' + BS + '(f("//")) ' + EM + ' b"\n', [1]),
        ("kotlin: a raw string", KOTLIN, 'val s = """a " // ' + EM + ' b"""\n', [1]),
        ("kotlin: a char of a quote", KOTLIN, 'val q = \'"\'\nval s = "a // b ' + EM + ' c"\n', [2]),
        ("rust: a char of a quote", RUST, 'let q = \'"\';\nlet s = "a // b ' + EM + ' c";\n', [2]),
        ("rust: a raw string", RUST, 'let s = r#"one " two // ' + EM + '"#;\n', [1]),
        ("js: block comments do not nest", FUNCTIONS, '/* a /* b */ const s = "' + EM + '";\n', [1]),
        ("swift: block comments nest", SWIFT, "/* a /* b */ " + EM + ' */\nlet s = "clean"\n', []),
        ("python: # inside a string", LICENCES, 'x = "a # b ' + EM + '"\n', [1]),
        ("python: # inside a triple-quoted string", LICENCES,
         "x = '''a\n# not a comment " + EM + "\n'''\n", [2]),
    )

    def test_cases(self):
        for name, path, text, lines in self.CASES:
            with self.subTest(case=name):
                self.assertEqual(found({path: text}), [(path, n) for n in lines])

    def test_an_escaped_quote_does_not_end_the_string(self):
        for path in (SWIFT, KOTLIN, RUST, FUNCTIONS, LICENCES):
            with self.subTest(path=path):
                text = 's = "say ' + BS + '" // ' + EM + '"\n'
                self.assertEqual(found({path: text}), [(path, 1)])


class WholeRepoScopeTests(unittest.TestCase):
    """The check before the repo goes public: every text file, every line."""

    def test_a_comment_fails(self):
        for path in (SWIFT, KOTLIN, RUST, FUNCTIONS):
            with self.subTest(path=path):
                self.assertEqual(found({path: comment(EM)}, "whole-repo"), [(path, 1), (path, 3)])

    def test_tests_internal_docs_and_bindings_fail(self):
        for path in TEST_FILES + BINDINGS:
            with self.subTest(path=path):
                self.assertEqual(found({path: literal(EM)}, "whole-repo"), [(path, 2)])
        for path in INTERNAL:
            with self.subTest(path=path):
                self.assertEqual(found({path: doc(EM)}, "whole-repo"), [(path, 2)])

    def test_third_party_texts_are_skipped(self):
        for path in THIRD_PARTY:
            with self.subTest(path=path):
                self.assertEqual(found({path: doc(EM)}, "whole-repo"), [])

    def test_the_summary_counts_per_file_and_area(self):
        findings = ced.check({"docs/BUILD-LOG.md": doc(EM) + doc(EN), "CLAUDE.md": doc(EM)},
                             scope="whole-repo")
        lines = ced.summary(findings)
        self.assertIn("       2  docs/BUILD-LOG.md", lines)
        self.assertIn("       1  (root files)", lines)


class MarkerTests(unittest.TestCase):
    REASON = "the input under test is itself a dash"

    def test_a_marked_line_passes(self):
        text = "one\nlet x = \"" + EM + "\" // em-dash-ok: " + self.REASON + "\n"
        self.assertEqual(found({SWIFT: text}), [])

    def test_the_marker_on_the_line_above_passes(self):
        text = "one\n// em-dash-ok: " + self.REASON + "\nlet x = \"" + EM + "\"\n"
        self.assertEqual(found({SWIFT: text}), [])

    def test_the_marker_two_lines_above_does_not(self):
        text = "// em-dash-ok: " + self.REASON + "\nlet y = 1\nlet x = \"" + EM + "\"\n"
        self.assertEqual(found({SWIFT: text}), [(SWIFT, 3)])

    def test_the_marker_is_refused_in_whole_public_files(self):
        for path in (PUBLIC_DOC, "NOTICE", "docs/RELEASE-0.5.0.md", "firebase/public/privacy/index.html",
                     "firebase/public/app.js", "android/app/src/main/res/values/strings.xml"):
            for where, text in (("same line", "one\nsome text " + EM + " em-dash-ok: " + self.REASON + "\n"),
                                ("line above", "one\nem-dash-ok: " + self.REASON + "\nsome text " + EM + "\n")):
                with self.subTest(path=path, where=where):
                    self.assertEqual(len(found({path: text})), 1, "a public doc has no dash under test")

    def test_the_marker_is_honoured_only_in_code_in_the_whole_repo(self):
        text = "one\nlet x = \"" + EM + "\" // em-dash-ok: " + self.REASON + "\n"
        for path in ("ios/SkiptiTests/AppLockTests.swift", "core/tests/ledger_port.rs"):
            with self.subTest(path=path):
                self.assertEqual(found({path: text}, "whole-repo"), [])
        for path in ("docs/BUILD-LOG.md", "CLAUDE.md"):
            with self.subTest(path=path):
                self.assertEqual(found({path: text}, "whole-repo"), [(path, 2)])

    def test_the_marker_without_a_reason_does_not(self):
        for tail in ("// em-dash-ok:", "// em-dash-ok:   ", "// em-dash-ok: ok"):
            with self.subTest(tail=tail):
                text = "one\nlet x = \"" + EM + "\" " + tail + "\n"
                self.assertEqual(found({RUST: text}), [(RUST, 2)])


class CollectorTests(unittest.TestCase):
    """The CLI's file list: tracked files plus untracked ones git does not ignore."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="skipti-dash-test-")

        def git(*args):
            subprocess.run(["git", *args], cwd=self.dir, check=True, capture_output=True)

        git("init", "-q", "--template=", "-b", "main")
        self.write(".gitignore", "ios/Skipti/App/build/\n")
        self.write("ios/Skipti/App/Tracked.swift", "let s = \"clean\"\n")
        git("add", "-A")
        git("commit", "-q", "--no-verify", "-m", "base")
        self.write("ios/Skipti/App/New.swift", literal(EM))
        self.write("ios/Skipti/App/build/Out.swift", literal(EM))
        self.write("CLAUDE.md", doc(EM))

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, rel, text):
        p = pathlib.Path(self.dir, rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")

    def test_an_untracked_file_is_checked_and_an_ignored_one_is_not(self):
        files = ced.collect(self.dir)
        self.assertEqual(sorted(files), ["ios/Skipti/App/New.swift", "ios/Skipti/App/Tracked.swift"])
        self.assertEqual(found(files), [("ios/Skipti/App/New.swift", 2)])
        whole = ced.collect(self.dir, "whole-repo")
        self.assertEqual(sorted(found(whole, "whole-repo")),
                         [("CLAUDE.md", 2), ("ios/Skipti/App/New.swift", 2)])


class TreeTests(unittest.TestCase):
    """The real files: the checker, this test and the gate."""

    def test_the_checker_and_this_test_hold_no_dash_in_any_form(self):
        for path in (CHECKER, pathlib.Path(__file__).resolve()):
            with self.subTest(path=path.name):
                self.assertEqual(ced.raw_hits(path.read_text(encoding="utf-8")), [],
                                 "marked or not, no form may appear")

    def test_the_gate_runs_the_leg_in_every_mode(self):
        text = GATE.read_text(encoding="utf-8")
        stack = []
        legs = []
        for n, line in enumerate(text.splitlines(), 1):
            s = line.strip()
            if re.match(r"if\b", s) and re.search(r"\bthen$", s):  # a shell if, not awk's
                stack.append(s)
            elif re.match(r"fi\b", s):
                self.assertTrue(stack, f"gate.sh:{n}: fi without an if")
                stack.pop()
            if s.startswith("run_gate ") and "U+2014 or U+2013" in s:
                legs.append((n, list(stack)))
        self.assertEqual(stack, [], "gate.sh: unbalanced if/fi")
        self.assertEqual(len(legs), 1, "gate.sh must run the em dash leg exactly once")
        n, enclosing = legs[0]
        self.assertEqual(enclosing, [], f"gate.sh:{n}: the leg sits inside {enclosing}")
        lines = text.splitlines()
        call, k = lines[n - 1].rstrip(), n
        while call.endswith(BS):
            call, k = call[:-1] + " " + lines[k].strip(), k + 1
        self.assertEqual(call.split()[-1], "em_dashes", f"gate.sh:{n}: the leg must run em_dashes: {call}")
        m = re.search(r"^em_dashes\(\) \{\n(.*?)\n\}", text, re.M | re.S)
        self.assertIsNotNone(m, "gate.sh: no em_dashes function")
        body = [ln.strip() for ln in m.group(1).splitlines() if ln.strip() and not ln.strip().startswith("#")]
        self.assertEqual(body, ["python3 scripts/check-em-dashes.py && python3 scripts/test_check_em_dashes.py"],
                         "the leg runs the checker, then its self-test, and nothing else")
        code = [ln for ln in text.splitlines() if not ln.lstrip().startswith("#")]
        self.assertFalse([ln for ln in code if "whole-repo" in ln],
                         "the whole-repo scope is not a gate leg (yet)")


if __name__ == "__main__":
    unittest.main(verbosity=2)
