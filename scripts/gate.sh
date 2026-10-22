#!/usr/bin/env bash
#
# SKIPTI gate. One command, one source of truth for "is this tree green?".
#
#   ./scripts/gate.sh          fast gate: the cheap legs plus cargo clippy,
#                              cargo test and the wasm32 build
#   ./scripts/gate.sh --full   adds release sweeps, bindings, supply-chain
#                              audits and both app shells
#   ./scripts/gate.sh --cheap  the legs that take seconds (rustfmt, float
#                              audit, BUILD-LOG, Swift checks, em dashes, the
#                              gate's own self-test); the pre-commit hook's mode when no
#                              heavy input is staged or a clean gate already
#                              proved the staged ones (D-137)
#
# Measured on the founder's Mac (D-137): with macOS's Developer Tools
# exemption OFF, syspolicyd scans every proc-macro dylib and test binary, so
# clippy took 16m37s and the cargo test run 12m44s for about 10 s of tests;
# a full gate was 30-40 minutes. After the founder enabled the exemption,
# D-137's fast gate still took 20m37s (clippy 456s, cargo test 777s) with
# syspolicyd assessing every build product: it was not reaching this Mac's
# gate runs. Every leg's wall time is printed after the evidence block.
#
# A fast or full run that passes with nothing failed and nothing skipped
# records which heavy inputs it checked (scripts/gate_inputs.py); any other
# outcome deletes that record. The hook uses it to skip re-running the heavy
# legs on exactly the same inputs.
#
# Exits non-zero if any gate FAILS. A gate whose tooling is absent is reported
# as SKIP, never as a pass: per BUILD-LOG D-23, green must mean checked, and a
# skipped state has to be visible. The summary counts skips separately so
# "all passed" can never be read off a run that quietly checked nothing.
#
# The evidence block printed at the end is the reproduction record BUILD-LOG §3
# asks for. Claims without one belong in §4, not §1.

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

if [ -t 1 ]; then
  R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; B=$'\033[1m'; N=$'\033[0m'
else
  R=""; G=""; Y=""; B=""; N=""
fi

FULL=0
CHEAP=0
case "${1:-}" in
  --full) FULL=1 ;;
  --cheap) CHEAP=1 ;;
  "") ;;
  *) echo "usage: $0 [--full | --cheap]" >&2; exit 2 ;;
esac

PASSED=0
FAILED=0
SKIPPED=0
EVIDENCE=""
FAILED_NAMES=""
TIMING=""
GATE_T0=$SECONDS

# The heavy inputs as they are now; compared again at the end, so an edit made
# while the gate runs cannot be recorded as checked. "none" (no python3, or a
# file git cannot fingerprint) only means no record is written.
INPUTS_AT_START=none
if [ $CHEAP -eq 0 ]; then
  INPUTS_AT_START=$(python3 scripts/gate_inputs.py hash 2>/dev/null) || INPUTS_AT_START=none
fi

record() { # name, verdict, detail
  # The newline is appended outside the substitution: $(...) strips trailing
  # newlines, which silently collapsed the whole block onto one line.
  EVIDENCE="${EVIDENCE}$(printf '  %-58s %s' "$1" "$3")"$'\n'
}

timed() { # name, seconds: one row of the table printed after the evidence
  TIMING="${TIMING}$(printf '  %-58s %6ss' "$1" "$2")"$'\n'
}

run_gate() { # name, detail-on-pass, command...
  local name="$1"; shift
  local detail="$1"; shift
  printf '%s==>%s %s\n' "$B" "$N" "$name"
  local t0=$SECONDS
  local out
  out=$("$@" 2>&1)
  local code=$?
  local took=$((SECONDS - t0))
  timed "$name" "$took"
  if [ $code -eq 0 ]; then
    printf '%s[PASS]%s %s (%ss)\n' "$G" "$N" "$name" "$took"
    PASSED=$((PASSED + 1))
    record "$name" pass "${detail:-exit 0}"
  else
    # Say WHICH KIND of failure this was (D-97): a genuine test/assertion
    # failure means the CODE regressed; an infrastructure failure (the
    # runner never launched, a daemon died, an SDK went missing mid-flight)
    # says nothing about the code and must never be read as one. A leg
    # failing ~half the time on device lifecycle (D-96) taught us this
    # distinction is load-bearing. The leg counts FAILED either way: a
    # classified infra failure is NOT a pass and NOT a skip.
    if printf '%s\n' "$out" | grep -qE 'Failing tests:|Test Case .* failed \(|XCTAssert[A-Za-z]* failed|Kotlin assertion error|java\.lang\.AssertionError'; then
      fkind="test-failure"
    elif printf '%s\n' "$out" | grep -qiE 'denied by service delegate|failed preflight checks|Failed to install or launch|Simulator device failed to launch|unable to boot|Early unexpected exit|Daemon termination|SDK location not found'; then
      fkind="INFRASTRUCTURE"
    else
      fkind="unclassified"
    fi
    tag="FAIL"; [ "$fkind" = "INFRASTRUCTURE" ] && { tag="FAIL-INFRA"; }
    [ "$fkind" = "test-failure" ] && { tag="FAIL-CODE"; }
    printf '%s[%s]%s %s (%s, exit %d, %ss)\n' "$R" "$tag" "$N" "$name" "$fkind" "$code" "$took"
    printf '%s\n' "$out" | tail -40
    # Preserve the WHOLE failing output, not the exit code alone: a gate leg
    # that reports only its status cannot be diagnosed honestly afterwards,
    # and "re-run until green" is how a real failure gets waved through.
    # §5.9's incident was invisible for exactly this reason.
    local art="scripts/gate-artifacts"
    mkdir -p "$art"
    local flog
    flog="$art/$(date -u '+%Y%m%dT%H%M%SZ')-$(printf '%s' "$name" | tr -c 'A-Za-z0-9._-' '-').log"
    printf '%s\n' "$out" > "$flog"
    echo "--> full failing output preserved: $flog"
    FAILED=$((FAILED + 1))
    FAILED_NAMES="${FAILED_NAMES}    - ${name} (${fkind}; full output: ${flog})"$'\n'
    record "$name" "${tag}" "exit $code: ${fkind}: output: $flog"
  fi
}

skip_gate() { # name, reason
  printf '%s[SKIP]%s %s: %s\n' "$Y" "$N" "$1" "$2"
  SKIPPED=$((SKIPPED + 1))
  record "$1" skip "SKIPPED ($2)"
}

# --- 1. Formatting ----------------------------------------------------------
run_gate "cargo fmt --all -- --check" "" \
  cargo fmt --all -- --check

# --- 2. Lint ----------------------------------------------------------------
if [ $CHEAP -eq 0 ]; then
  run_gate "cargo clippy --workspace --all-targets -- -D warnings" "" \
    cargo clippy --workspace --all-targets -- -D warnings
fi

# --- 3. Float audit (ADR-0002) ----------------------------------------------
# Identical logic to the CI step. Kept byte-for-byte aligned on purpose: a
# local gate that disagrees with CI is worse than no local gate.
float_audit() { ! grep -rnE '\bf(32|64)\b' core/src/ >/dev/null 2>&1; }
run_gate "float audit (ADR-0002: no f32/f64 in core/src)" "" float_audit

# --- 3b. BUILD-LOG structure (D-123) -----------------------------------------
# Incident §5.24's shape: buildlog_edit.py refuses a bad EDIT, but cannot
# detect content already dropped. D-55's decision row vanished and stayed
# gone for five units. Contiguity, one row per number, well-formed rows and
# balanced fences are checked on every gate, fast included: the record the
# whole project reads is guarded by the same command that guards the code.
run_gate "buildlog structure (D-number contiguity, fences)" "" \
  python3 scripts/check-buildlog.py

# --- 3c. Swift string interpolation (D-127) ----------------------------------
# `"\\(word)"` in Swift is a LITERAL backslash-paren, not interpolation. Four
# shipped that way and nothing caught them: the Recovery Kit read "Word 1 of
# 12: \\(word)" to VoiceOver (on the one screen SPEC §3.4 calls "the only way
# back in") and two staleness warnings said "\\(days) days ago". They compile,
# they pass every test, and only a human reading the rendered string sees it.
# A grep is the whole fix, so the bug class cannot come back.
swift_interpolation() {
  ! grep -rn 'Text(\|accessibilityLabel(\|accessibilityHint(' ios/Skipti --include='*.swift' 2>/dev/null \
    | grep -F '\\(' >/dev/null 2>&1
}
run_gate "swift interpolation (no literal backslash-paren in UI strings)" "" \
  swift_interpolation

# --- 3d. Swift autolinking (D-128) -------------------------------------------
# A string-literal SwiftUI title is a LocalizedStringKey: SwiftUI parses it as
# Markdown and turns an email or URL into a system-blue LINK. Sign in's
# "you@example.com" placeholder shipped that way, compiled, and passed every
# test; two other explanations were falsified on device before
# Text(verbatim:) confirmed it. Like 3c, only a human reading the screen sees
# this class, so a scan is the whole guard.
run_gate "swift autolink (no email/URL in string-literal UI titles)" "" \
  python3 scripts/check-swift-autolink.py

# --- 3f. Swift list-row pills (D-139) -----------------------------------------
# iOS 26+ clips a clear-background List/Form row to a pill. Primary buttons
# placed as such rows rendered as capsules and secondary outlines vanished
# (D-135), on four screens, and nothing but a person looking caught it. The
# scan flags a styled button made into a clear, zero-inset row.
run_gate "swift list rows (no styled button as a clear list row)" "" \
  python3 scripts/check-swift-list-rows.py

# --- 3g. No em dash, no en dash on the public surface (D-228) ---------------
# The founder reads em dashes as "sounds like AI". This leg keeps them, in every
# form a file can carry one (the character, an escape, an HTML entity, a hex
# constant), off what people face: the apps' text (app code but comments), the
# resources, the Hosting pages and the public docs (decision 42: the repository
# is private). Before it is made public, run the checker with
# `--scope whole-repo` and sweep until it passes; that scope is not a leg yet.
# The checker's self-test runs in the same leg.
em_dashes() {
  python3 scripts/check-em-dashes.py && python3 scripts/test_check_em_dashes.py
}
run_gate "em dashes (D-228: no U+2014 or U+2013 on the public surface)" "" \
  em_dashes

# --- 3e. The gate's own proof (D-137) ----------------------------------------
# scripts/gate_inputs.py lets the pre-commit hook skip the heavy legs below
# when a clean gate already checked exactly the staged inputs. A bug there
# fails nothing: it silently lets untested Rust into a commit. Its self-test
# runs in every mode, in throwaway repositories, including the real hook.
run_gate "gate self-test (D-137 heavy-leg proof and hook routing)" "" \
  python3 scripts/test_gate_inputs.py

# --- 4. Tests ---------------------------------------------------------------
# The heavy legs: skipped in --cheap mode, which exists so the pre-commit
# hook need not repeat them on inputs a clean gate already checked.
if [ $CHEAP -eq 0 ]; then
  printf '%s==>%s cargo test --workspace\n' "$B" "$N"
  TEST_T0=$SECONDS
  TEST_LOG=$(mktemp)
  cargo test --workspace >"$TEST_LOG" 2>&1
  TEST_CODE=$?
  timed "cargo test --workspace" "$((SECONDS - TEST_T0))"
  # Tally by locating the "passed;"/"failed;" tokens rather than by field index.
  # Splitting on [ ;] looks right and is not: "; " yields an empty field, so the
  # failed count lands one position later than it appears to, and a naive $6
  # reports 0 failures for a suite that failed.
  TEST_TALLY=$(awk '
    /^test result/ {
      for (i = 2; i <= NF; i++) {
        tok = $i; sub(/;$/, "", tok)
        if (tok == "passed") p += $(i - 1)
        else if (tok == "failed") f += $(i - 1)
      }
    }
    END { printf "%d passed, %d failed", p, f }' "$TEST_LOG")
  [ -z "$TEST_TALLY" ] && TEST_TALLY="no results parsed"
  if [ $TEST_CODE -eq 0 ]; then
    printf '%s[PASS]%s cargo test --workspace: %s\n' "$G" "$N" "$TEST_TALLY"
    PASSED=$((PASSED + 1))
    record "cargo test --workspace" pass "$TEST_TALLY"
  else
    printf '%s[FAIL]%s cargo test --workspace: %s\n' "$R" "$N" "$TEST_TALLY"
    grep -E '^(test .* FAILED|failures:|---- )' "$TEST_LOG" | head -40
    FAILED=$((FAILED + 1))
    FAILED_NAMES="${FAILED_NAMES}    - cargo test --workspace"$'\n'
    record "cargo test --workspace" fail "$TEST_TALLY"
  fi
  rm -f "$TEST_LOG"

  # --- 5. wasm target ---------------------------------------------------------
  if rustup target list --installed 2>/dev/null | grep -q wasm32-unknown-unknown; then
    run_gate "cargo build -p skipti-core --target wasm32-unknown-unknown" "" \
      cargo build -p skipti-core --target wasm32-unknown-unknown
  else
    skip_gate "cargo build -p skipti-core --target wasm32-unknown-unknown" \
      "run: rustup target add wasm32-unknown-unknown"
  fi
fi

# --- Full-gate extras -------------------------------------------------------
if [ $FULL -eq 1 ]; then
  run_gate "cargo test --workspace --release -- --ignored" "exit 0 (~40s sweeps)" \
    cargo test --workspace --release -- --ignored

  # --- Bindings freshness (D-43) --------------------------------------------
  # Regenerating the shell bindings must be a no-op on a committed tree.
  # This needs the release dylib (~1 min build), so it deliberately lives in
  # gate-full ONLY: a check that forces a build does not belong in the
  # pre-commit path. On failure: run make bindings and commit the result.
  bindings_fresh() {
    cargo build --release -p skipti-core >/dev/null 2>&1 || {
      echo "release build of skipti-core failed"; return 1
    }
    make bindings >/dev/null 2>&1 || {
      echo "make bindings failed"; return 1
    }
    if ! git diff --exit-code -- ios/Skipti/Bindings android/app/src/main/kotlin; then
      echo "generated bindings are stale: run make bindings and commit the result"
      return 1
    fi
  }
  run_gate "bindings freshness (regenerate == committed)" "exit 0 (byte-identical)" \
    bindings_fresh

  # Generated bindings must be BUILDABLE, not merely byte-current (§5.17):
  # D-91 found committed Kotlin that had never compiled anywhere. This leg
  # compiles the Kotlin tree standalone: it needs only a JDK + kotlinc, NOT the
  # Android SDK, so it is honest even on machines where the android-app leg
  # skips. Swift's equivalent stays covered by the ios-app leg above, which
  # compiles SkiptiCore.swift for real; the asymmetry is deliberate and
  # written in scripts/check-kotlin-bindings.sh.
  kotlin_bindings_tooling_present() {
    local jdk="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    [ -x "$jdk/bin/java" ] || return 1
    local kc="${STUDIO_KOTLINC:-/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc}"
    [ -x "$kc/bin/kotlinc" ] || return 1
  }
  if kotlin_bindings_tooling_present; then
    export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    run_gate "kotlin bindings compile standalone (bytes != buildable)" \
      "KOTLIN BINDINGS COMPILE OK" scripts/check-kotlin-bindings.sh
  else
    skip_gate "kotlin bindings compile standalone (bytes != buildable)" \
      "no JVM Kotlin compiler (Studio's bundled kotlinc) available"
  fi

  for tool in audit deny cyclonedx; do
    if cargo "$tool" --version >/dev/null 2>&1; then
      case $tool in
        audit) run_gate "cargo audit" "" cargo audit ;;
        deny)  run_gate "cargo deny check (ADR-0012 banned SDK scan)" "" cargo deny check ;;
        # SBOM as a real gate leg (D-99): CI used to hold this alone, which
        # meant local full gates were weaker than every push appeared. Where
        # the tool is absent the leg SKIPS, never silently vanishes.
        cyclonedx) run_gate "SBOM generation (cargo cyclonedx)" "" cargo cyclonedx --all --format json ;;
      esac
    else
      skip_gate "cargo $tool" "not installed: cargo install cargo-$tool"
    fi
  done

  # --- Firebase security rules (D-193) ---------------------------------------
  # The cloud is not end-to-end encrypted (ADR-0040), so the Firestore and
  # Storage rules ARE the access control; every allow has a refusal test,
  # run here in the emulators through the pinned CLI. Needs node, a JVM (the
  # emulators are Java) and `npm ci` in firebase/. SKIP, never a pass, when
  # any is absent.
  firebase_tooling_present() {
    command -v node >/dev/null 2>&1 || return 1
    local jdk="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    [ -x "$jdk/bin/java" ] || return 1
    [ -x firebase/node_modules/.bin/firebase ] || return 1
  }
  if firebase_tooling_present; then
    export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    run_gate "firebase rules (emulators: every refusal tested)" "RULES TESTS PASSED" \
      env PATH="$JAVA_HOME/bin:$PATH" scripts/firebase-rules-test.sh
  else
    skip_gate "firebase rules (emulators: every refusal tested)" \
      "node, a JVM or firebase/node_modules is missing: cd firebase && npm ci --ignore-scripts"
  fi

  # --- npm audit: the Cloud Functions' dependencies (D-195) ------------------
  # They run on Google's servers with the Admin SDK's full access to the
  # project, so they are audited like the Rust graph. High or critical fails;
  # the 2 moderate uuid advisories (2026-10-02: the flaw needs a buffer
  # argument nothing passes) are recorded in BUILD-LOG D-195.
  if command -v npm >/dev/null 2>&1 && [ -d firebase/functions/node_modules ]; then
    run_gate "npm audit (Cloud Functions' dependencies)" "exit 0 (no high or critical)" \
      bash -c 'cd firebase/functions && npm audit --omit=dev --audit-level=high'
  else
    skip_gate "npm audit (Cloud Functions' dependencies)" \
      "npm or firebase/functions/node_modules is missing: cd firebase/functions && npm ci --ignore-scripts"
  fi

  # --- iOS app build + simulator tests (D-59) ---------------------------------
  # The fast gate sees only the Rust half of the repo; §5.9 is the proof that
  # this is a real blind spot, not a hypothetical one: committed HEAD failed
  # `make ios-app` outright while every check here reported green, and a test
  # suite that had never executed was logged as running across sessions.
  # gate-full therefore builds the app and runs its XCTest suite, so a full
  # green means the WHOLE tree. SKIP (never a pass) when the tooling is
  # absent: a Linux CI runner must not silently go green. A Mac WITH Xcode
  # but without the Makefile's simulator (SIM, default "iPhone 17") fails
  # loudly instead: that is an environment mismatch to fix, not absent
  # tooling, and skipping it could hide a broken project. Relay nuance: the
  # E2E subset skips ITSELF when the relay is down (D-23's honest-skip), so
  # this leg proves build + suite execution; the with-relay count is a
  # BUILD-LOG §3 claim, not a gate one.
  ios_tooling_present() {
    command -v xcodebuild >/dev/null 2>&1 || return 1
    xcodebuild -version >/dev/null 2>&1 || return 1
    xcrun --sdk iphonesimulator --show-sdk-path >/dev/null 2>&1 || return 1
    xcrun simctl list devices available 2>/dev/null | grep -q 'iPhone' || return 1
  }
  if ios_tooling_present; then
    run_gate "make ios-app (build + simulator tests)" "TEST SUCCEEDED" \
      make ios-app
  else
    skip_gate "make ios-app (build + simulator tests)" \
      "Xcode or an iPhone simulator is unavailable on this machine"
  fi

  # --- Android app build + packaging (D-92) -----------------------------------
  # The iOS leg's lesson (§5.9 / D-59) applied to the second shell: without a
  # leg here, half the repo is invisible to the only command that decides
  # green. This proves the native cross-compile runs and the committed Kotlin
  # bindings COMPILE and ride inside the produced APK. NOT claimed here: any
  # on-emulator execution, because no AVD lives on every machine that runs this
  # gate, so emulator behaviour stays BUILD-LOG §4 material, exactly as
  # VoiceOver-on-device does. SKIP (never a pass) when the Android toolchain
  # is absent: a machine without Studio keeps its "not a full green"; one
  # WITH the SDK but a missing NDK or no wrapper fails loudly instead of
  # skipping, because present-but-broken must not masquerade as not-installed.
  android_tooling_present() {
    local jdk="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    [ -x "$jdk/bin/java" ] || return 1
    local sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    [ -d "$sdk/platforms" ] || return 1
    ls -d "$sdk"/ndk/*/ >/dev/null 2>&1 || return 1
    [ -x android/gradlew ] || return 1
  }
  if android_tooling_present; then
    export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    run_gate "make android-app (core .so + APK packaging)" "BUILD SUCCESSFUL" \
      make android-app

    # --- Android JVM goldens (D-108) ------------------------------------
    # The leg above proves the APK PACKAGES; this proves the Kotlin LOGIC
    # runs. They are different claims and the gap between them is where
    # D-91 lived: committed Kotlin that had never compiled anywhere. When
    # D-107's balance goldens landed they had NO gate leg: 8 tests calling
    # real core through the app's own JNA bindings, protected by nothing and
    # therefore free to rot. Third time this repo would have been bitten by
    # a suite nothing runs (§5.9 was the first, D-91 the second).
    #
    # It needs the HOST cdylib, which `make android-test` builds. Ox Alpha
    # deferred this leg on the worry that a host cdylib might not link on a
    # Linux runner; that worry is already answered by evidence rather than
    # assumption: the bindings-freshness leg above builds exactly such a
    # library and passed on the Linux runner in CI run 33106530545.
    # Detail deliberately says WHAT the leg reports, not a count: the tally
    # is printed by the leg itself (per class + TOTAL) and a static number
    # here went stale the moment D-109 added tests (14 -> 25). A detail
    # that can lie is worse than one that points at the real printout.
    run_gate "make android-test (JVM goldens against real core)" \
      "per-class tally printed; exits non-zero on any failure" \
      make android-test

    # --- Licence inventory (D-146) --------------------------------------
    # Both apps show native/licences/licences.json on their Licences page.
    # It is generated from what the apps are built from (cargo metadata for
    # the shells' targets, Gradle's release runtime classpath per flavour,
    # the bundled fonts), so a dependency added, bumped or dropped without
    # regenerating it would put a false list in front of users and miss a
    # licence notice the app owes. Last in the Android block: it reads the
    # POMs the legs above have just resolved, offline. It also refuses a
    # licence it has not been taught (a person reviews it first).
    run_gate "licence inventory (regenerate == committed)" \
      "exit 0 (current)" \
      python3 scripts/licences.py --check
  else
    skip_gate "make android-app (core .so + APK packaging)" \
      "the Android SDK/NDK or Studio JBR is unavailable on this machine"
    skip_gate "make android-test (JVM goldens against real core)" \
      "the Android SDK/NDK or Studio JBR is unavailable on this machine"
    skip_gate "licence inventory (regenerate == committed)" \
      "the Android SDK/NDK or Studio JBR is unavailable on this machine"
  fi

  # --- No proof surface in a release build (D-260, G-13, decision 269) ----------
  # The proof screens and seeds (launch arguments the looks, sabotage runs and emulator proofs use) are Debug only:
  # iOS keeps them inside `#if DEBUG`, Android in src/debug with src/release stubs. iOS: `nm` over the Release binary
  # `make ios-app` built above (unsigned, unstripped, coverage-instrumented: the counters' paths are dropped), no
  # proof type's symbol. Android: a SOURCE check of src/main and src/release (comments removed): no proof name or
  # dispatch word. It is a source check because the gate builds no release APK. Both name lists, and why each name is
  # there, are in the script; its self-test runs in the same leg. Needs the iOS leg's binary, so it skips (never a
  # pass) where Xcode is absent.
  release_proofs() {
    python3 scripts/check-release-proofs.py && python3 scripts/test_check_release_proofs.py
  }
  if ios_tooling_present; then
    run_gate "release proofs (D-260: no proof in either release build)" "RELEASE PROOFS OK" \
      release_proofs
  else
    skip_gate "release proofs (D-260: no proof in either release build)" \
      "the iOS Release binary needs Xcode (make ios-app)"
  fi
fi

# --- Summary and evidence ---------------------------------------------------
MODE=fast
[ $FULL -eq 1 ] && MODE=full
[ $CHEAP -eq 1 ] && MODE=cheap
COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "no-commit")
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "?")
STAMP=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

echo
printf '%s────────────────────────────────────────────────────────────────%s\n' "$B" "$N"
if [ $FAILED -gt 0 ]; then
  printf '%sGATE FAILED%s: %d passed, %d failed, %d skipped\n' "$R" "$N" "$PASSED" "$FAILED" "$SKIPPED"
  printf '%s' "$FAILED_NAMES"
elif [ $SKIPPED -gt 0 ]; then
  printf '%sGATE PASSED WITH SKIPS%s: %d passed, %d skipped. Not a full green.\n' \
    "$Y" "$N" "$PASSED" "$SKIPPED"
else
  printf '%sGATE PASSED%s: %d passed, 0 skipped\n' "$G" "$N" "$PASSED"
fi
echo
echo "Evidence (paste into docs/BUILD-LOG.md §3 when claiming progress):"
echo
printf '  gate: %s  ·  %s  ·  branch %s  ·  commit %s\n' "$MODE" "$STAMP" "$BRANCH" "$COMMIT"
printf '%s' "$EVIDENCE"
echo

# After the evidence, never inside it: BUILD-LOG §3 blocks are copied from the
# lines above, and their shape predates the timings.
ELAPSED=$((SECONDS - GATE_T0))
echo "Timing (wall clock, this run):"
printf '%s' "$TIMING"
printf '  %-58s %6ss (%dm%02ds)\n' "whole gate" "$ELAPSED" $((ELAPSED / 60)) $((ELAPSED % 60))
if [ $CHEAP -eq 0 ]; then
  # If the recorder itself fails, delete any old record: a proof that cannot
  # be updated must not outlive the run that should have replaced it.
  python3 scripts/gate_inputs.py finish "$MODE" "$INPUTS_AT_START" "$PASSED" "$FAILED" "$SKIPPED" \
    || { rm -f "$(git rev-parse --git-path skipti-heavy-legs-pass.json 2>/dev/null)"
         echo "heavy-leg marker: deleted (scripts/gate_inputs.py failed)"; }
fi
echo

[ $FAILED -gt 0 ] && exit 1
exit 0
