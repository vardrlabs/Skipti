#!/usr/bin/env python3
"""Gate leg (D-137): the heavy-leg proof and the pre-commit hook's routing.

scripts/gate_inputs.py lets the pre-commit hook skip cargo clippy, cargo test
and the wasm build when a clean gate already passed on exactly the staged
inputs. A bug there does not fail anything: it silently lets untested Rust
into a commit. So every way it could wrongly say "proven" has a case here,
run in throwaway git repositories:

  (a)  a staged Rust change differs from what the gate checked
  (a') the index differs while the working tree was reverted to the gated
       version (hashing the working tree instead of the index would pass)
  (b)  a failed or skipped gate writes no proof and clears an old one
  (c)  an edit made while the gate ran voids the proof
  (d)  a different toolchain voids the proof
  (e)  an unstaged-only difference keeps the proof: the index is committed
  plus: docs and screenshots do not void it; Cargo.lock, fixtures, the
  gate's own scripts and unknown new top-level files do; every workspace
  member and path dependency is a heavy input; and the real hook, driven by
  `git commit` against a stub gate, picks cheap / fast exactly as designed.
  And (D-228) the real commit-msg hook refuses a Co-Authored-By trailer and a
  "Generated with" line.

Isolation matters because this leg runs INSIDE the pre-commit hook, where
git exports GIT_INDEX_FILE: a test git command inheriting it would write the
real repository's index. Every git-local variable is scrubbed, and global and
system git config are ignored.
"""
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import gate_inputs as gi  # noqa: E402

for var in subprocess.run(["git", "rev-parse", "--local-env-vars"], capture_output=True,
                          text=True, check=True).stdout.split():
    os.environ.pop(var, None)
os.environ.update({
    "GIT_CONFIG_GLOBAL": os.devnull,
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_AUTHOR_NAME": "gate test", "GIT_AUTHOR_EMAIL": "gate@test.invalid",
    "GIT_COMMITTER_NAME": "gate test", "GIT_COMMITTER_EMAIL": "gate@test.invalid",
})

REAL_TOOLCHAIN = gi.toolchain_id


def run(repo, *args, check=True):
    out = subprocess.run(args, cwd=repo, capture_output=True, text=True, check=False)
    if check and out.returncode != 0:
        raise AssertionError(f"{args} failed:\n{out.stdout}{out.stderr}")
    return out


class Repo:
    """A throwaway repository shaped like SKIPTI's heavy and light halves."""

    def __init__(self):
        self.dir = tempfile.mkdtemp(prefix="skipti-gate-test-")
        run(self.dir, "git", "init", "-q", "--template=", "-b", "main")
        self.write("Cargo.toml", '[workspace]\nmembers = ["core"]\n')
        self.write("Cargo.lock", "# lock v1\n")
        self.write("core/Cargo.toml", '[package]\nname = "core"\n')
        self.write("core/src/lib.rs", "pub fn one() -> u8 { 1 }\n")
        self.write("core/src/words.txt", "abandon\nability\n")
        self.write("docs/BUILD-LOG.md", "# log\n")
        self.write("README.md", "# readme\n")
        self.write("ios/artifacts/shot.png", "PNG1")
        self.write("scripts/check-buildlog.py", "print('ok')\n")
        self.write(".gitignore", "target/\n")
        run(self.dir, "git", "add", "-A")
        run(self.dir, "git", "commit", "-q", "--no-verify", "-m", "base")

    def write(self, rel, text):
        p = pathlib.Path(self.dir, rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def stage(self, *rels):
        run(self.dir, "git", "add", "-A", "--", *rels)

    def gate(self, passed=9, failed=0, skipped=0, edit_during=None):
        """What gate.sh does around its legs: hash at start, finish at end."""
        start = gi.token(gi.fingerprint(gi.worktree_entries(self.dir)))
        if edit_during:
            edit_during()
        return gi.finish(self.dir, "fast", start, passed, failed, skipped)

    def proven(self):
        return gi.check_index(self.dir)

    def close(self):
        shutil.rmtree(self.dir, ignore_errors=True)


class ProofTests(unittest.TestCase):
    def setUp(self):
        self.toolchain = "rustc 1.99.0 (pinned for the test)"
        gi.toolchain_id = lambda: self.toolchain
        self.r = Repo()

    def tearDown(self):
        gi.toolchain_id = REAL_TOOLCHAIN
        self.r.close()

    def assertProven(self):
        ok, why = self.r.proven()
        self.assertTrue(ok, why)

    def assertNotProven(self, reason):
        ok, why = self.r.proven()
        self.assertFalse(ok, "the hook would have skipped the heavy legs")
        self.assertIn(reason, why)

    def test_clean_pass_proves_the_same_staged_inputs(self):
        self.assertIn("written", self.r.gate())
        self.assertProven()

    def test_staged_rust_change_is_not_proven(self):  # (a)
        self.r.gate()
        self.r.write("core/src/lib.rs", "pub fn one() -> u8 { 2 }\n")
        self.r.stage("core/src/lib.rs")
        self.assertNotProven("staged heavy inputs differ")

    def test_index_is_what_counts_not_the_working_tree(self):  # (a')
        self.r.gate()
        self.r.write("core/src/lib.rs", "pub fn one() -> u8 { 2 }\n")
        self.r.stage("core/src/lib.rs")
        self.r.write("core/src/lib.rs", "pub fn one() -> u8 { 1 }\n")  # unstaged revert
        self.assertNotProven("staged heavy inputs differ")

    def test_unstaged_change_keeps_the_proof(self):  # (e)
        self.r.gate()
        self.r.write("core/src/lib.rs", "pub fn one() -> u8 { 3 }\n")  # not staged
        self.assertProven()

    def test_failed_run_clears_the_marker(self):  # (b)
        self.r.gate()
        self.assertIn("cleared", self.r.gate(passed=8, failed=1))
        self.assertNotProven("no clean gate pass")

    def test_skipped_run_clears_the_marker(self):  # (b)
        self.r.gate()
        self.assertIn("cleared", self.r.gate(passed=8, skipped=1))
        self.assertNotProven("no clean gate pass")

    def test_edit_during_the_run_writes_nothing(self):  # (c)
        edit = lambda: self.r.write("core/src/lib.rs", "pub fn one() -> u8 { 4 }\n")
        self.assertIn("changed while the gate ran", self.r.gate(edit_during=edit))
        self.r.stage("core/src/lib.rs")
        self.assertNotProven("no clean gate pass")

    def test_toolchain_change_voids_the_proof(self):  # (d)
        self.r.gate()
        self.toolchain = "rustc 1.100.0 (a newer compiler)"
        self.assertNotProven("toolchain changed")

    def test_docs_and_screenshots_keep_the_proof(self):
        self.r.gate()
        self.r.write("docs/BUILD-LOG.md", "# log\n| D-137 | recorded after the gate |\n")
        self.r.write("README.md", "# readme, edited\n")
        self.r.write("ios/artifacts/new-shot.png", "PNG2")
        self.r.write("scripts/check-buildlog.py", "print('stricter')\n")
        self.r.stage(".")
        self.assertProven()

    def test_non_rust_heavy_inputs_void_the_proof(self):
        for rel, text in (("Cargo.lock", "# lock v2\n"),
                          ("core/src/words.txt", "abandon\nabout\n"),
                          ("scripts/gate.sh", "# gate changed\n"),
                          ("scripts/gate_inputs.py", "# proof changed\n"),
                          (".githooks/pre-commit", "# hook changed\n"),
                          ("fixtures/new.json", "{}\n")):
            with self.subTest(rel=rel):
                self.r.gate()
                old = pathlib.Path(self.r.dir, rel)
                before = old.read_text() if old.exists() else None
                self.r.write(rel, text)
                self.r.stage(rel)
                self.assertNotProven("staged heavy inputs differ")
                if before is None:
                    run(self.r.dir, "git", "rm", "-q", "--cached", rel)
                    old.unlink()
                else:
                    self.r.write(rel, before)
                    self.r.stage(rel)

    def test_untracked_heavy_file_counts_until_it_is_staged(self):
        self.r.write("core/src/new.rs", "pub fn two() -> u8 { 2 }\n")
        self.r.gate()
        self.assertNotProven("staged heavy inputs differ")
        self.r.stage("core/src/new.rs")
        self.assertProven()

    def test_staged_deletion_voids_the_proof(self):
        self.r.gate()
        run(self.r.dir, "git", "rm", "-q", "core/src/words.txt")
        self.assertNotProven("staged heavy inputs differ")


class ClassificationTests(unittest.TestCase):
    def test_heavy_classification(self):
        heavy = ["Cargo.toml", "Cargo.lock", "deny.toml", "Makefile", ".gitignore",
                 ".githooks/pre-commit", "core/src/ffi.rs", "core/src/lykill_bip39_english.txt",
                 "server/build.rs", "server/tests/relay.rs", "scripts/gate.sh",
                 "scripts/gate_inputs.py", "rust-toolchain.toml", ".cargo/config.toml",
                 "clippy.toml", "fixtures/vectors.json", "core/README.md"]
        light = ["docs/BUILD-LOG.md", "docs/WORKLOG.md", "README.md", "CLAUDE.md", "NOTICE",
                 "LICENSE", "ios/Skipti/App/GroupStore.swift", "ios/artifacts/d135-a.png",
                 "android/app/build.gradle.kts", "web/index.html", "brand/mark.svg",
                 "deploy/skipti-backup.sh", "native/fonts/SpaceGrotesk.ttf",
                 ".github/workflows/ci.yml", "scripts/check-buildlog.py",
                 "scripts/check-swift-autolink.py", "scripts/test_gate_inputs.py"]
        for p in heavy:
            self.assertTrue(gi.is_heavy(p), f"{p} must count as a heavy input")
        for p in light:
            self.assertFalse(gi.is_heavy(p), f"{p} must not void the proof")

    def test_workspace_members_and_path_dependencies_are_heavy(self):
        manifest = (ROOT / "Cargo.toml").read_text()
        block = re.search(r"members\s*=\s*\[([^\]]*)\]", manifest)
        self.assertIsNotNone(block, "no workspace members list in Cargo.toml")
        members = re.findall(r'"([^"]+)"', block.group(1))
        self.assertTrue(members)
        for m in members:
            for rel in (f"{m}/Cargo.toml", f"{m}/src/lib.rs", f"{m}/tests/t.rs"):
                self.assertTrue(gi.is_heavy(rel), f"workspace member file {rel} is not heavy")
            for dep in re.findall(r'path\s*=\s*"([^"]+)"', (ROOT / m / "Cargo.toml").read_text()):
                rel = os.path.relpath(os.path.normpath(ROOT / m / dep), ROOT)
                self.assertFalse(rel.startswith(".."), f"{m} depends on {dep}, outside the repo")
                self.assertTrue(gi.is_heavy(f"{rel}/Cargo.toml"), f"path dependency {rel} is not heavy")


STUB_GATE = """#!/usr/bin/env bash
echo "STUB GATE ARGS=[$*]"
exit "${STUB_GATE_EXIT:-0}"
"""


class HookTests(unittest.TestCase):
    """The real .githooks/pre-commit, run by `git commit`, against a stub gate."""

    def setUp(self):
        self.r = Repo()
        for rel in (".githooks/pre-commit", "scripts/gate_inputs.py"):
            dest = pathlib.Path(self.r.dir, rel)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / rel, dest)
        self.r.write("scripts/gate.sh", STUB_GATE)
        os.chmod(pathlib.Path(self.r.dir, "scripts/gate.sh"), 0o755)
        self.r.stage(".")
        run(self.r.dir, "git", "commit", "-q", "--no-verify", "-m", "tooling")
        run(self.r.dir, "git", "config", "core.hooksPath", ".githooks")

    def tearDown(self):
        self.r.close()

    def commit(self, env=None):
        out = subprocess.run(["git", "commit", "-m", "change"], cwd=self.r.dir,
                             capture_output=True, text=True, check=False,
                             env=dict(os.environ, **(env or {})))
        return out.returncode, out.stdout + out.stderr

    def clean_gate(self):
        start = run(self.r.dir, sys.executable, "scripts/gate_inputs.py", "hash").stdout.strip()
        out = run(self.r.dir, sys.executable, "scripts/gate_inputs.py", "finish",
                  "full", start, "18", "0", "0").stdout
        self.assertIn("written", out)

    def test_docs_only_commit_runs_the_cheap_legs(self):
        self.r.write("docs/BUILD-LOG.md", "# log\nrow\n")
        self.r.stage("docs/BUILD-LOG.md")
        code, out = self.commit()
        self.assertEqual(code, 0, out)
        self.assertIn("STUB GATE ARGS=[--cheap]", out)
        self.assertIn("no heavy input staged", out)

    def test_check_script_only_commit_runs_the_cheap_legs(self):
        self.r.write("scripts/check-buildlog.py", "print('stricter')\n")
        self.r.stage("scripts/check-buildlog.py")
        code, out = self.commit()
        self.assertEqual(code, 0, out)
        self.assertIn("STUB GATE ARGS=[--cheap]", out)

    def test_heavy_commit_without_a_proof_runs_the_fast_gate(self):
        self.r.write("core/src/lib.rs", "pub fn one() -> u8 { 5 }\n")
        self.r.stage("core/src/lib.rs")
        code, out = self.commit()
        self.assertEqual(code, 0, out)
        self.assertIn("STUB GATE ARGS=[]", out)

    def test_heavy_commit_with_a_matching_proof_skips_the_heavy_legs(self):
        self.r.write("core/src/lib.rs", "pub fn one() -> u8 { 6 }\n")
        self.clean_gate()
        self.r.write("docs/BUILD-LOG.md", "# log\nrecorded\n")
        self.r.stage("core/src/lib.rs", "docs/BUILD-LOG.md")
        code, out = self.commit()
        self.assertEqual(code, 0, out)
        self.assertIn("STUB GATE ARGS=[--cheap]", out)
        self.assertIn("heavy legs skipped", out)

    def test_heavy_commit_with_a_stale_proof_runs_the_fast_gate(self):
        self.clean_gate()
        self.r.write("core/src/lib.rs", "pub fn one() -> u8 { 7 }\n")
        self.r.stage("core/src/lib.rs")
        code, out = self.commit()
        self.assertEqual(code, 0, out)
        self.assertIn("STUB GATE ARGS=[]", out)

    def test_staged_deletion_of_a_heavy_file_runs_the_fast_gate(self):
        run(self.r.dir, "git", "rm", "-q", "core/src/words.txt")
        code, out = self.commit()
        self.assertEqual(code, 0, out)
        self.assertIn("STUB GATE ARGS=[]", out)

    def test_a_red_gate_refuses_the_commit(self):
        self.r.write("docs/BUILD-LOG.md", "# log\nbroken\n")
        self.r.stage("docs/BUILD-LOG.md")
        code, out = self.commit(env={"STUB_GATE_EXIT": "1"})
        self.assertNotEqual(code, 0, out)
        self.assertIn("REFUSED", out)


EM = chr(0x2000 + 0x14)
EN = chr(0x2000 + 0x13)
HOOK = ROOT / ".githooks/commit-msg"
TRAILER = "Co-Authored-By: Someone <someone@example.invalid>"


class CommitMsgHookTests(unittest.TestCase):
    """The real .githooks/commit-msg (D-228): no tool attribution in a commit
    message (CLAUDE.md section 12; FENRIR's six commits of 2026-09-11 carried a
    trailer after a context compaction). Dashes are not its business (decision
    42: the em dash rule covers the public surface, and the repo is private)."""

    def verdict(self, message):
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".msg", delete=False) as f:
            f.write(message)
        try:
            out = subprocess.run([str(HOOK), f.name], capture_output=True, text=True, check=False)
        finally:
            os.unlink(f.name)
        return out.returncode, out.stdout + out.stderr

    def assertAccepted(self, message):
        code, out = self.verdict(message)
        self.assertEqual(code, 0, out)

    def assertRefused(self, message, reason):
        code, out = self.verdict(message)
        self.assertNotEqual(code, 0, f"accepted: {message!r}")
        self.assertIn("REFUSED", out)
        self.assertIn(reason, out)

    def test_the_hook_is_executable(self):
        self.assertTrue(os.access(HOOK, os.X_OK), "git skips a hook that is not executable")

    def test_a_clean_message_is_accepted(self):
        self.assertAccepted("D-228 T5: the guard, the hook and the leg [skip ci]\n\n"
                            "Body with a hyphen-minus and bindings generated with make bindings.\n"
                            "Mentions co-authored-by in prose, not as a trailer.\n")

    def test_a_dash_is_not_its_business(self):
        self.assertAccepted("subject " + EM + " with a dash\n\nbody " + EN + " too\n")

    def test_a_co_authored_by_trailer_is_refused(self):
        for line in (TRAILER, "co-authored-by: someone <someone@example.invalid>",
                     "  Co-authored-by:someone"):
            with self.subTest(line=line):
                self.assertRefused("subject\n\nbody\n\n" + line + "\n", "Co-Authored-By")

    def test_a_generated_with_line_is_refused(self):
        for line in ("Generated with a tool (https://example.invalid)",
                     "\U0001F916 Generated with a tool"):
            with self.subTest(line=line):
                self.assertRefused("subject\n\n" + line + "\n", "Generated with")

    def test_a_verbose_diff_below_the_scissors_is_not_read(self):
        self.assertAccepted("subject\n\n# ------------------------ >8 ------------------------\n"
                            "# Do not modify or remove the line above.\n"
                            " " + TRAILER + "\n")  # a diff context line, which the trailer rule matches

    def test_git_runs_it(self):
        r = Repo()
        try:
            dest = pathlib.Path(r.dir, ".githooks/commit-msg")
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(HOOK, dest)
            run(r.dir, "git", "config", "core.hooksPath", ".githooks")
            r.write("README.md", "# readme, edited\n")
            r.stage("README.md")
            out = run(r.dir, "git", "commit", "-q", "-m", "a subject", "-m", TRAILER, check=False)
            self.assertNotEqual(out.returncode, 0, out.stdout + out.stderr)
            self.assertIn("REFUSED", out.stdout + out.stderr)
            run(r.dir, "git", "commit", "-q", "-m", "a subject", "-m", "a plain body")
        finally:
            r.close()

if __name__ == "__main__":
    unittest.main(verbosity=2)
