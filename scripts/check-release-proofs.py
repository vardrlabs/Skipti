#!/usr/bin/env python3
"""Gate leg (D-260, G-13, decision 269): no proof surface in a release build, on either phone.

The proof surfaces (launch-argument screens and seeds the looks, the sabotage runs and the emulator proofs use) are
test entry points, never user flows. A release build must not carry them: not reachable is not enough (Android's
run-time gate, ProofArgs, keys on FLAG_DEBUGGABLE, which a re-signed APK can turn on).

  python3 scripts/check-release-proofs.py [--ios-binary PATH] [--android-src DIR] [--ios-app DIR]
      Exit 0 and print `RELEASE PROOFS OK: ...`, or exit 1 with one row per finding.

iOS: the Release product the gate's `make ios-app` builds (`ios/build/Build/Products/Release-iphoneos/Skipti.app/Skipti`,
    unsigned, unstripped) is read with `nm`, its names demangled with `swift-demangle`, and no symbol may name a proof
    type below. The gate's Release build is coverage-instrumented (`___profc_<path>:<function>` counters, the pass's side
    finding 1): the path part is dropped before matching, so a counter is read for its function only; a counter of a
    proof function is a proof symbol like any other. Optimized Swift keeps short literals out of `__cstring`, so the
    symbol table, not `strings`, is the proof. A binary that shows no ordinary app symbol (HomeShell, LedgerStore) fails:
    a stripped or wrong binary must not pass for a clean one.
Android: a SOURCE check. The gate builds no release APK (`make android-app` assembles the debug APK; a release needs no
    keystore but is not a gate leg), so the release source sets, `src/main` and `src/release`, are read with comments
    removed: no proof name below as a word, and no string literal whose words (leading `--` dropped) include a dispatch
    word below; no `when (headless)` dispatch. `src/main` must still call `ProofSurface(headless, args, vaultPath)` and keep
    `object ProofArgs` (the run-time gate the JVM tests assert), and `src/release` must hold the stubs.
Both lists stay live: every name must still be declared where the proofs live (iOS: ios/Skipti/App; Android:
    android/app/src/debug), or the check fails as stale, so a rename cannot quietly empty it.
"""
import argparse
import functools
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# iOS proof types and members (Swift names; every one is inside `#if DEBUG`).
IOS_PROOF_SYMBOLS = {
    "StoreShots": "the store pages' seed (D-251, StoreShots.swift)",
    "StoreShotsScreen": "its screen (`--store-shots`)",
    "SmokeScreenView": "`--smoke`, the stack's round-trip screen (SmokeScreenView.swift)",
    "VaultSmoke": "the smoke screen's vault round trip (VaultSmoke.swift)",
    "GroupsProofScreen": "`--groups-proof` (D-179, GroupsScreen.swift)",
    "GroupsProof": "the groups proof's seed",
    "SettleLook": "`--settle-look`, a look's states on the groups proof (D-247)",
    "AuthEmulatorProofScreen": "`--auth-emulator-proof` (D-191, Account.swift)",
    "SyncEmulatorProofScreen": "`--sync-emulator-proof` (D-194)",
    "TwoAccountProofScreen": "`--two-account-proof` (D-195)",
    "SettingsEmulatorProofScreen": "`--settings-emulator-proof` (D-244)",
    "ExpenseConflictProofScreen": "`--expense-conflict` (D-142, ExpenseDetailView.swift)",
    "ImportProofScreen": "`--import-proof` (ImportProofScreen.swift)",
    "ExportProofScreen": "`--export-proof` (ExportProofScreen.swift)",
    "GroupEndProof": "`--group-end`, the two-phone proof's steps (D-252, SkiptiApp.swift)",
    "EmulatorSignIn": "`--emulator-as`, the emulator sign-in (D-210, SkiptiApp.swift)",
    "EmulatorApproval": "the emulator approval helper (D-254, Approval.swift)",
    "proofRoot": "SkiptiApp's proof dispatch",
    "Fixtures": "the fixture ledger (BalanceScreen.swift; D-260)",
    "BalanceScreen": "`--balance`, `--balance-vault` (D-260)",
    "SettleScreen": "`--settle`, `--settle-vault` (D-260)",
    "loadGroupsOrSeed": "the proofs' fixture group seed (LedgerStore.swift; D-260)",
}
# Ordinary app symbols the binary must show (the positive control).
IOS_APP_SYMBOLS = ("HomeShell", "LedgerStore")

# Android proof declarations (Kotlin names; every one is in src/debug since D-260, none has a release stub).
ANDROID_PROOF_NAMES = {
    "AuthEmulatorProof": "`auth-emulator-proof` (D-191)",
    "SyncEmulatorProof": "`sync-emulator-proof` (D-194)",
    "TwoAccountProof": "`two-account-proof` (D-195)",
    "SettingsEmulatorProof": "`settings-emulator-proof` (D-244)",
    "BarProof": "`bar-proof`, the bar's three forms (D-217)",
    "NotificationProof": "`notification-proof` (D-216)",
    "GroupsProof": "`groups-proof` (D-179)",
    "GroupsProofSeed": "its seed",
    "ExpenseConflictProofScreen": "`expense-conflict` (D-143)",
    "seedConflict": "its throwaway vault",
    "ImportProof": "`import-proof`",
    "ImportProofScreen": "its screen",
    "ExportProof": "`export-proof`",
    "ExportProofScreen": "its screen",
    "ProofColumn": "the proof screens' layout",
    "ProofRow": "the proof screens' rows",
    "GroupEndProof": "`--group-end`, the two-phone proof (D-252)",
    "EmulatorSignIn": "`--emulator-as` (D-210)",
    "BalanceScreen": "`balance`, `balance-vault`, `balance-empty`",
    "BalanceSource": "its fixture sources",
    "SettleScreen": "`settle`",
    "BalanceFixtures": "the fixture ledger",
    "loadGroupsOrSeed": "the proofs' fixture group seed",
}
# The proofs' dispatch words: a string literal holding one (with or without its `--`) is a proof in the release sources.
ANDROID_DISPATCH_WORDS = {
    "auth-emulator-proof": "AuthEmulatorProof",
    "sync-emulator-proof": "SyncEmulatorProof",
    "two-account-proof": "TwoAccountProof",
    "settings-emulator-proof": "SettingsEmulatorProof",
    "bar-proof": "BarProof",
    "notification-proof": "NotificationProof",
    "groups-proof": "GroupsProof",
    "balance-vault": "BalanceScreen over the fixture vault",
    "balance-empty": "BalanceScreen, empty",
    "list-vault": "the expense list as a proof",
    "export-proof": "ExportProofScreen",
    "expense-conflict": "ExpenseConflictProofScreen",
    "import-proof": "ImportProofScreen",
    "settle-look": "SettleLook's step (D-247)",
    "ring-nolock": "a SettleLook step",
    "group-end": "GroupEndProof's step",
    "group-end-me": "GroupEndProof's name",
}
DISPATCH_SHAPE = re.compile(r"\bwhen\s*\(\s*headless\s*\)")


def word(name):
    return re.compile(r"(?<![A-Za-z0-9_])" + re.escape(name) + r"(?![A-Za-z0-9_])")


# ---- Kotlin: comments out, strings kept and listed ----

SPECIAL = re.compile(r"[/\"']")


@functools.lru_cache(maxsize=None)
def kotlin_lex(text):
    """Return (code, literals): `code` is `text` with every comment blanked (newlines kept), `literals` the contents of
    every string literal (templates included, as written). Kotlin block comments nest."""
    out = []
    literals = []
    i, n = 0, len(text)

    def string(i, raw):
        start = i
        depth = 0
        while i < n:
            if not raw and text[i] == "\\":
                i += 2
                continue
            if text.startswith("${", i):
                depth += 1
                i += 2
                continue
            if depth and text[i] == "}":
                depth -= 1
                i += 1
                continue
            if depth == 0 and (text.startswith('"""', i) if raw else text[i] == '"'):
                return start, i
            if not raw and text[i] == "\n":
                return start, i  # unterminated: stop at the line's end
            i += 1
        return start, n

    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif text.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            out.append(re.sub(r"[^\n]", " ", text[i:j]))
            i = j
        elif text.startswith('"""', i):
            s, e = string(i + 3, raw=True)
            literals.append(text[s:e])
            out.append(text[i:e + 3])
            i = e + 3
        elif text[i] == '"':
            s, e = string(i + 1, raw=False)
            literals.append(text[s:e])
            out.append(text[i:e + 1])
            i = e + 1
        elif text[i] == "'":
            j = i + 1
            while j < n and text[j] != "'" and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        else:
            m = SPECIAL.search(text, i + 1)
            j = m.start() if m else n
            out.append(text[i:j])
            i = j
    return "".join(out), tuple(literals)


def literal_words(literal):
    return {w[2:] if w.startswith("--") else w for w in literal.split()}


def check_android(src):
    findings = []
    sets = [src / "main", src / "release"]
    files = sorted(p for s in sets for p in (s / "java").rglob("*") if p.suffix in (".kt", ".java"))
    if len(files) < 50:
        return [f"android: only {len(files)} release source files under {src}: wrong folder?"]
    names = re.compile(r"(?<![A-Za-z0-9_])(" + "|".join(sorted(map(re.escape, ANDROID_PROOF_NAMES), key=len, reverse=True))
                       + r")(?![A-Za-z0-9_])")
    main_code = []
    release_code = []
    for f in files:
        code, literals = kotlin_lex(f.read_text(encoding="utf-8"))
        rel = f.relative_to(src)
        (release_code if rel.parts[0] == "release" else main_code).append(code)
        for m in names.finditer(code):
            name = m.group(1)
            line = code.count("\n", 0, m.start()) + 1
            findings.append(f"android {rel}:{line}: proof name {name} ({ANDROID_PROOF_NAMES[name]})")
        for lit in literals:
            for w in sorted(literal_words(lit) & ANDROID_DISPATCH_WORDS.keys()):
                findings.append(f"android {rel}: proof word \"{w}\" in a string ({ANDROID_DISPATCH_WORDS[w]})")
        for m in DISPATCH_SHAPE.finditer(code):
            line = code.count("\n", 0, m.start()) + 1
            findings.append(f"android {rel}:{line}: a headless proof dispatch (when (headless)) in a release source")
    main_all = "\n".join(main_code)
    release_all = "\n".join(release_code)
    for need, where, what in [
        ("ProofSurface(headless, args, vaultPath)", main_all, "src/main calls ProofSurface"),
        ("object ProofArgs", main_all, "src/main keeps ProofArgs, the run-time gate"),
        ("fun ProofSurface(", release_all, "src/release stubs ProofSurface"),
        ("object ProofLaunch", release_all, "src/release stubs ProofLaunch"),
        ("object SettleLook", release_all, "src/release stubs SettleLook"),
    ]:
        if need not in where:
            findings.append(f"android: missing `{need}` ({what})")
    # Live list: every name still declared in src/debug, where the proofs live.
    debug = "\n".join(kotlin_lex(p.read_text(encoding="utf-8"))[0] for p in sorted((src / "debug" / "java").rglob("*.kt")))
    for name in ANDROID_PROOF_NAMES:
        if not re.search(r"\b(?:fun|object|class|enum class|data class)\s+(?:[A-Za-z_][A-Za-z0-9_.]*\.)?" + re.escape(name) + r"\b", debug):
            findings.append(f"android: stale list: {name} is declared nowhere in src/debug")
    return findings


def demangled_symbols(binary):
    nm = subprocess.run(["xcrun", "nm", "-j", str(binary)], capture_output=True, text=True)
    if nm.returncode != 0:
        raise SystemExit(f"RELEASE PROOFS FAILED: nm could not read {binary}: {nm.stderr.strip()}")
    dem = subprocess.run(["xcrun", "swift-demangle", "--simplified"], input=nm.stdout, capture_output=True, text=True)
    if dem.returncode != 0:
        raise SystemExit(f"RELEASE PROOFS FAILED: swift-demangle failed: {dem.stderr.strip()}")
    return dem.stdout.splitlines()


COVERAGE_PREFIX = re.compile(r"^_+prof[a-z]+_(?:[^:]*:)?")


def check_ios_symbols(symbols):
    findings = []
    hits = {}
    names = {n: word(n) for n in IOS_PROOF_SYMBOLS}
    present = {n: 0 for n in IOS_APP_SYMBOLS}
    app = {n: word(n) for n in IOS_APP_SYMBOLS}
    for raw in symbols:
        sym = COVERAGE_PREFIX.sub("", raw)
        for name, rx in names.items():
            if rx.search(sym):
                hits.setdefault(name, []).append(raw)
        for name, rx in app.items():
            if rx.search(sym):
                present[name] += 1
    for name, rows in sorted(hits.items()):
        findings.append(f"ios: {len(rows)} symbol(s) name {name} ({IOS_PROOF_SYMBOLS[name]}), e.g. {rows[0][:160]}")
    for name, count in present.items():
        if count == 0:
            findings.append(f"ios: no symbol names {name}: not the app's unstripped Release binary")
    return findings, len(symbols)


def check_ios_names_live(app_dir):
    # Comments out (a name only mentioned in one is not declared).
    text = re.sub(r"//[^\n]*", "", "\n".join(p.read_text(encoding="utf-8") for p in sorted(app_dir.glob("*.swift"))))
    stale = []
    for name in IOS_PROOF_SYMBOLS:
        if not re.search(r"\b(?:struct|enum|class|func|var|let)\s+" + re.escape(name) + r"\b", text):
            stale.append(f"ios: stale list: {name} is declared nowhere in {app_dir.relative_to(ROOT) if app_dir.is_relative_to(ROOT) else app_dir}")
    return stale


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--ios-binary", default=str(ROOT / "ios/build/Build/Products/Release-iphoneos/Skipti.app/Skipti"))
    ap.add_argument("--ios-app", default=str(ROOT / "ios/Skipti/App"))
    ap.add_argument("--android-src", default=str(ROOT / "android/app/src"))
    a = ap.parse_args(argv)
    binary = pathlib.Path(a.ios_binary)
    findings = []
    if not binary.is_file():
        findings.append(f"ios: no Release binary at {binary}: run `make ios-app` first")
        count = 0
    else:
        f, count = check_ios_symbols(demangled_symbols(binary))
        findings += f
    findings += check_ios_names_live(pathlib.Path(a.ios_app))
    findings += check_android(pathlib.Path(a.android_src))
    if findings:
        print("RELEASE PROOFS FAILED: a proof surface reaches a release build (or the check cannot see):")
        for row in findings:
            print("  " + row)
        print("Proof surfaces are Debug only: iOS inside `#if DEBUG`, Android in src/debug with a src/release stub.")
        return 1
    print(f"RELEASE PROOFS OK: iOS {count} symbols, none of {len(IOS_PROOF_SYMBOLS)} proof names; Android src/main + "
          f"src/release, none of {len(ANDROID_PROOF_NAMES)} proof names or {len(ANDROID_DISPATCH_WORDS)} proof words")
    return 0


if __name__ == "__main__":
    sys.exit(main())
