#!/usr/bin/env python3
"""Gate machinery (D-137): prove that the heavy legs already passed on exactly
the inputs being committed, so the pre-commit hook need not run them again.

The fast gate's heavy legs (cargo clippy, cargo test, the wasm32 build) take
minutes, and took 30 to 40 with macOS malware scanning on. A unit's commit
usually lands straight after a full gate passed on the same Rust tree, with
only docs and screenshots changed in between. Running the heavy legs again
then proves nothing new; it only makes every unit commit a long wait.

  gate_inputs.py hash
      Digest of the WORKING TREE's heavy inputs. gate.sh takes it at start.
  gate_inputs.py finish MODE START PASSED FAILED SKIPPED
      Run by gate.sh after a fast or full gate. Writes the marker only when
      the run passed with nothing failed and nothing skipped AND the heavy
      inputs are still exactly START (an edit during the run voids it).
      Any other outcome deletes the marker.
  gate_inputs.py check-index
      Exit 0 when the INDEX's heavy inputs and the toolchain match the
      marker; the index, not the working tree, is what a commit records.
  gate_inputs.py staged-heavy
      Exit 0 when a staged change (deletions included) touches a heavy input.

Heavy inputs are every file git sees (tracked, and untracked but not ignored)
EXCEPT the paths cargo never reads, listed below. It is deliberately a
DENYLIST: a new crate, fixture directory or config file counts until someone
decides it does not, so forgetting to update this file costs a slower commit,
never a skipped check. The toolchain is part of the proof: rustc -vV, cargo
-V, cargo clippy -V, the installed targets, and the RUSTFLAGS family.

Exit status: 0 yes / done, 1 no, 2 cannot tell. Callers treat anything but 0
as "run the heavy legs".
"""
import datetime as dt
import hashlib
import json
import os
import pathlib
import subprocess
import sys

MARKER = "skipti-heavy-legs-pass.json"

# Directories cargo never reads: the two app shells, docs, the unbuilt web
# app, brand art, deploy units, fonts for the shells, CI config, and the
# Firebase project (rules, tests and its pinned CLI; D-193).
EXCLUDED_DIRS = (
    ".github/",
    "android/",
    "brand/",
    "deploy/",
    "docs/",
    "firebase/",
    "ios/",
    "native/",
    "web/",
)
EXCLUDED_ROOT_FILES = ("LICENSE", "NOTICE")
# scripts/ holds the cheap legs' checkers and the shells' build helpers, which
# cargo never reads. The gate's own machinery is the exception: a change to
# how the heavy legs run, or to how this proof is taken, must run them again.
HEAVY_SCRIPTS = ("scripts/gate.sh", "scripts/gate_inputs.py")

TOOLCHAIN_COMMANDS = (
    ("rustc", "-vV"),
    ("cargo", "-V"),
    ("cargo", "clippy", "-V"),
    ("rustup", "target", "list", "--installed"),
)
TOOLCHAIN_ENV = (
    "RUSTFLAGS",
    "CARGO_ENCODED_RUSTFLAGS",
    "CARGO_BUILD_RUSTFLAGS",
    "RUSTDOCFLAGS",
    "RUSTC_WRAPPER",
)


class CannotTell(Exception):
    """The inputs cannot be fingerprinted; nothing may be skipped."""


def is_heavy(path):
    """True when cargo clippy, cargo test or the wasm build could read path."""
    if path in HEAVY_SCRIPTS:
        return True
    if path.startswith("scripts/"):
        return False
    if path.startswith(EXCLUDED_DIRS):
        return False
    if "/" not in path and (path.endswith(".md") or path in EXCLUDED_ROOT_FILES):
        return False
    return True


def git(repo, *args, stdin=None):
    out = subprocess.run(
        ("git",) + args, cwd=repo, input=stdin, capture_output=True, check=False
    )
    if out.returncode != 0:
        raise CannotTell(f"git {' '.join(args)}: {out.stderr.decode(errors='replace').strip()}")
    return out.stdout


def toolchain_id():
    parts = []
    for cmd in TOOLCHAIN_COMMANDS:
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=120, check=False)
            parts.append(f"$ {' '.join(cmd)} [{out.returncode}]\n{out.stdout}{out.stderr}")
        except (OSError, subprocess.TimeoutExpired) as exc:
            parts.append(f"$ {' '.join(cmd)} unavailable: {type(exc).__name__}")
    for var in TOOLCHAIN_ENV:
        parts.append(f"{var}={os.environ.get(var, '<unset>')}")
    return "\n".join(parts)


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def digest(entries):
    """entries: {path: blob id}. Order-free, and a path's blob changes it."""
    return sha("".join(f"{entries[p]} {p}\n" for p in sorted(entries)))


def worktree_entries(repo):
    listed = git(repo, "ls-files", "-z", "--cached", "--others", "--exclude-standard")
    paths = sorted({p for p in listed.decode().split("\0") if p and is_heavy(p)})
    present = []
    for p in paths:
        if p.endswith("/") or "\n" in p:
            raise CannotTell(f"cannot fingerprint {p!r}")
        if os.path.lexists(os.path.join(repo, p)):
            present.append(p)  # a deleted file is absent from what cargo sees
    if not present:
        return {}
    blobs = git(repo, "hash-object", "--stdin-paths", stdin="".join(p + "\n" for p in present).encode())
    ids = blobs.decode().split()
    if len(ids) != len(present):
        raise CannotTell("git hash-object returned the wrong number of ids")
    return dict(zip(present, ids))


def index_entries(repo):
    # `ls-files -s` reads GIT_INDEX_FILE when git sets it, which it does for
    # the pre-commit hook: under `git commit <paths>` that is the temporary
    # index holding exactly what the commit will record.
    listed = git(repo, "ls-files", "-z", "--stage")
    entries = {}
    for rec in listed.decode().split("\0"):
        if not rec:
            continue
        meta, path = rec.split("\t", 1)
        _mode, blob, stage = meta.split()
        if stage != "0":
            raise CannotTell(f"{path} is unmerged")
        if is_heavy(path):
            entries[path] = blob
    return entries


def fingerprint(entries):
    return {"toolchain": sha(toolchain_id()), "inputs": digest(entries), "files": len(entries)}


def token(fp):
    return f"{fp['toolchain']}:{fp['inputs']}:{fp['files']}"


def marker_path(repo):
    rel = git(repo, "rev-parse", "--git-path", MARKER).decode().strip()
    return pathlib.Path(repo, rel)


def clear(repo):
    marker_path(repo).unlink(missing_ok=True)


def finish(repo, mode, start, passed, failed, skipped):
    """Returns the one line gate.sh prints about the marker."""
    if failed or skipped or not passed:
        clear(repo)
        return (f"heavy-leg marker: cleared ({passed} passed, {failed} failed, "
                f"{skipped} skipped is not a clean pass)")
    try:
        end = fingerprint(worktree_entries(repo))
    except CannotTell as exc:
        clear(repo)
        return f"heavy-leg marker: not written ({exc})"
    if start != token(end):
        clear(repo)
        return "heavy-leg marker: not written (the heavy inputs changed while the gate ran)"
    head = git(repo, "rev-parse", "--short", "HEAD").decode().strip() if has_head(repo) else "none"
    record = dict(end, mode=mode, head=head,
                  stamp=dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    path = marker_path(repo)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(record, indent=1) + "\n")
    tmp.replace(path)
    return (f"heavy-leg marker: written ({mode} gate, {end['files']} heavy inputs, "
            f"digest {end['inputs'][:12]})")


def has_head(repo):
    return subprocess.run(("git", "rev-parse", "--verify", "-q", "HEAD"), cwd=repo,
                          capture_output=True, check=False).returncode == 0


def check_index(repo):
    """(matches, message)."""
    path = marker_path(repo)
    if not path.exists():
        return False, "no clean gate pass is recorded for these inputs"
    try:
        record = json.loads(path.read_text())
        want = (record["toolchain"], record["inputs"], record["files"])
    except (ValueError, KeyError, TypeError):
        return False, "the heavy-leg marker is unreadable"
    have = fingerprint(index_entries(repo))
    if have["toolchain"] != want[0]:
        return False, "the Rust toolchain changed since the last clean gate"
    if (have["inputs"], have["files"]) != want[1:]:
        return False, "the staged heavy inputs differ from what the last clean gate checked"
    return True, (f"the {record.get('mode', '?')} gate at {record.get('stamp', '?')} "
                  f"(HEAD {record.get('head', '?')}) passed on exactly these "
                  f"{have['files']} staged heavy inputs and this toolchain")


def staged_heavy(repo):
    args = ["diff", "--cached", "--name-only", "-z", "--no-renames"]
    if not has_head(repo):
        args.append(git(repo, "hash-object", "-t", "tree", "--stdin", stdin=b"").decode().strip())
    names = git(repo, *args).decode().split("\0")
    return any(n and is_heavy(n) for n in names)


def main(argv):
    repo = git(os.getcwd(), "rev-parse", "--show-toplevel").decode().strip()
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "hash" and len(argv) == 2:
        print(token(fingerprint(worktree_entries(repo))))
        return 0
    if cmd == "finish" and len(argv) == 7:
        mode, start = argv[2], argv[3]
        passed, failed, skipped = (int(x) for x in argv[4:7])
        print(finish(repo, mode, start, passed, failed, skipped))
        return 0
    if cmd == "check-index" and len(argv) == 2:
        ok, message = check_index(repo)
        print(message)
        return 0 if ok else 1
    if cmd == "staged-heavy" and len(argv) == 2:
        return 0 if staged_heavy(repo) else 1
    print(__doc__.split("\n\n")[1], file=sys.stderr)
    return 2


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except CannotTell as exc:
        print(f"gate_inputs: cannot tell ({exc})", file=sys.stderr)
        sys.exit(2)
