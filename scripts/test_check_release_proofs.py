#!/usr/bin/env python3
"""Gate leg (D-260): the self-test of scripts/check-release-proofs.py.

Every way the checker could wrongly say OK has a case here: a proof symbol in the iOS Release binary (a coverage
counter of a proof function included), a stripped or wrong binary, a proof name or dispatch word in Android's release
sources (src/main, src/release), a headless dispatch left in src/main, a missing release stub, and a name list gone
stale after a rename. The quiet cases too: a coverage counter's path, a comment, an ordinary name that contains a
proof's name. The Android cases run on a copy of the real source sets, so the clean copy must pass as it is.

Run: python3 scripts/test_check_release_proofs.py
"""
import importlib.util
import pathlib
import shutil
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
CHECKER = HERE / "check-release-proofs.py"
GATE = HERE / "gate.sh"

_spec = importlib.util.spec_from_file_location("check_release_proofs", CHECKER)
crp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(crp)

APP = ["SkiptiApp.body.getter", "HomeShell.body.getter", "LedgerStore.namedGroups()", "static LedgerStore.LegacyFixture.ada"]


class Lexer(unittest.TestCase):
    def test_comments_go_and_strings_stay(self):
        code, lits = crp.kotlin_lex('val a = "x // y" // BarProof\n/* GroupsProof /* nested */ still */ val b = 1\n')
        self.assertNotIn("BarProof", code)
        self.assertNotIn("GroupsProof", code)
        self.assertIn('"x // y"', code)
        self.assertIn("val b = 1", code)
        self.assertEqual(("x // y",), lits)
        self.assertEqual(2, code.count("\n"), "the lines are kept")

    def test_templates_and_raw_strings(self):
        code, lits = crp.kotlin_lex('val s = "a ${BalanceFixtures.ada} b"\nval r = """--groups-proof\n x"""\nval c = \'"\'\n')
        self.assertIn("BalanceFixtures", code)
        self.assertEqual(("a ${BalanceFixtures.ada} b", "--groups-proof\n x"), lits)
        self.assertEqual({"groups-proof", "x"}, crp.literal_words(lits[1]))


class Ios(unittest.TestCase):
    def test_a_clean_binary_passes(self):
        findings, count = crp.check_ios_symbols(APP + ["___profc_/tmp/x/BalanceScreen.swift:HomeShell.body.getter"])
        self.assertEqual([], findings)
        self.assertEqual(5, count)

    def test_every_proof_name_is_found(self):
        for name in crp.IOS_PROOF_SYMBOLS:
            with self.subTest(name=name):
                findings, _ = crp.check_ios_symbols(APP + [f"static {name}.shared.getter"])
                self.assertEqual(1, len(findings), findings)
                self.assertIn(name, findings[0])

    def test_a_coverage_counter_of_a_proof_function_is_a_proof_symbol(self):
        findings, _ = crp.check_ios_symbols(APP + ["___profc_/tmp/x/Other.swift:static Fixtures.dto(groupID:nowMs:)"])
        self.assertEqual(1, len(findings))
        self.assertIn("Fixtures", findings[0])

    def test_names_are_whole_words(self):
        findings, _ = crp.check_ios_symbols(APP + ["StoreShotsX.body.getter", "LedgerStore.LegacyFixture.names"])
        self.assertEqual([], findings)

    def test_a_binary_without_app_symbols_fails(self):
        findings, _ = crp.check_ios_symbols(["_main", "_objc_msgSend"])
        self.assertEqual(2, len(findings))
        self.assertTrue(all("not the app's unstripped Release binary" in f for f in findings))

    def test_the_list_is_live(self):
        self.assertEqual([], crp.check_ios_names_live(ROOT / "ios/Skipti/App"))
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d)
            (p / "A.swift").write_text("struct HomeShell {}\n// struct StoreShots\n")
            stale = crp.check_ios_names_live(p)
            self.assertEqual(len(crp.IOS_PROOF_SYMBOLS), len(stale))


class Android(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.src = pathlib.Path(self.tmp.name) / "src"
        for s in ("main", "release", "debug"):
            shutil.copytree(ROOT / "android/app/src" / s / "java", self.src / s / "java")
        self.pkg = "java/com/vardrlabs/skipti"

    def tearDown(self):
        self.tmp.cleanup()

    def append(self, sset, name, text):
        p = self.src / sset / self.pkg / name
        p.write_text(p.read_text() + text)

    def test_the_real_tree_passes(self):
        self.assertEqual([], crp.check_android(self.src))

    def test_a_proof_moved_back_to_main_fails(self):
        shutil.move(str(self.src / "debug" / self.pkg / "BarProof.kt"), str(self.src / "main" / self.pkg / "BarProof.kt"))
        found = crp.check_android(self.src)
        self.assertTrue(any("main/java/com/vardrlabs/skipti/BarProof.kt" in f and "BarProof" in f for f in found), found)
        self.assertTrue(any("stale list: BarProof" in f for f in found), found)

    def test_every_proof_name_in_main_fails(self):
        for name in crp.ANDROID_PROOF_NAMES:
            with self.subTest(name=name):
                p = self.src / "main" / self.pkg / "Probe.kt"
                p.write_text(f"package com.vardrlabs.skipti\nfun probe() = {name}\n")
                found = crp.check_android(self.src)
                self.assertEqual(1, len(found), found)
                self.assertIn(f"proof name {name}", found[0])

    def test_every_dispatch_word_in_a_release_string_fails(self):
        for w in crp.ANDROID_DISPATCH_WORDS:
            for spelled in (w, "--" + w):
                with self.subTest(word=spelled):
                    p = self.src / "release" / self.pkg / "Probe.kt"
                    p.write_text(f'package com.vardrlabs.skipti\nval probe = "x {spelled}"\n')
                    found = crp.check_android(self.src)
                    self.assertEqual(1, len(found), found)
                    self.assertIn(f'proof word "{w}"', found[0])

    def test_a_comment_or_a_longer_name_passes(self):
        self.append("main", "AppRoot.kt", "\n// GroupEndProof, \"--groups-proof\"\n/* BarProof */\nval notAProof = \"groups-proofs\"\nfun BarProofs() {}\n")
        self.assertEqual([], crp.check_android(self.src))

    def test_a_headless_dispatch_in_main_fails(self):
        self.append("main", "AppRoot.kt", "\nfun probe(headless: String?) { when (headless) { else -> {} } }\n")
        found = crp.check_android(self.src)
        self.assertEqual(1, len(found), found)
        self.assertIn("when (headless)", found[0])

    def test_a_missing_stub_or_call_fails(self):
        (self.src / "release" / self.pkg / "SettleLook.kt").unlink()
        p = self.src / "main" / self.pkg / "AppRoot.kt"
        p.write_text(p.read_text().replace("ProofSurface(headless, args, vaultPath)", "ProofSurfaceX(headless, args, vaultPath)"))
        found = crp.check_android(self.src)
        self.assertIn("android: missing `object SettleLook` (src/release stubs SettleLook)", found)
        self.assertIn("android: missing `ProofSurface(headless, args, vaultPath)` (src/main calls ProofSurface)", found)

    def test_a_renamed_proof_makes_the_list_stale(self):
        p = self.src / "debug" / self.pkg / "Proofs.kt"
        p.write_text(p.read_text().replace("object GroupEndProof", "object GroupEndProbe"))
        self.assertIn("android: stale list: GroupEndProof is declared nowhere in src/debug", crp.check_android(self.src))

    def test_a_wrong_folder_fails(self):
        found = crp.check_android(pathlib.Path(self.tmp.name) / "nowhere")
        self.assertEqual(1, len(found))
        self.assertIn("wrong folder", found[0])


class Wiring(unittest.TestCase):
    def test_the_gate_runs_the_leg_and_its_self_test_after_both_shells(self):
        gate = GATE.read_text()
        leg = gate.index('run_gate "release proofs (D-260: no proof in either release build)"')
        self.assertIn("python3 scripts/check-release-proofs.py && python3 scripts/test_check_release_proofs.py", gate)
        self.assertLess(gate.index('run_gate "make ios-app (build + simulator tests)"'), leg)
        self.assertLess(gate.index('run_gate "make android-app (core .so + APK packaging)"'), leg)
        # the full gate only: after `if [ $FULL -eq 1 ]; then` and before the summary
        self.assertLess(gate.index("if [ $FULL -eq 1 ]; then"), leg)
        self.assertLess(leg, gate.index("# --- Summary and evidence"))

    def test_the_default_binary_is_the_one_make_ios_app_builds(self):
        make = (ROOT / "Makefile").read_text()
        self.assertIn("-configuration Release -destination 'generic/platform=iOS'", make)
        self.assertIn("ios/build/Build/Products/Release-iphoneos/Skipti.app/Skipti", make)
        self.assertIn('"ios/build/Build/Products/Release-iphoneos/Skipti.app/Skipti"', CHECKER.read_text())


if __name__ == "__main__":
    unittest.main(verbosity=1)
