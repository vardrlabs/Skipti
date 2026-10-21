#!/usr/bin/env bash
#
# Firebase security rules, tested (D-193; ADR-0040: the rules ARE the access
# control, since the cloud is not end-to-end encrypted). Starts the Firestore
# and Storage emulators through the pinned CLI (firebase/, project
# demo-skipti: a `demo-` project never reaches production) and runs
# firebase/tests/*.test.mjs, which asserts every refusal. One file at a time:
# each wipes the shared emulator database before every test. D-195: the
# Functions emulator runs firebase/functions (the invite Functions) too. The emulators are
# Java programs the pinned CLI downloads once and checks against the size and
# SHA-256 it pins (the founder's yes under CLAUDE.md §13, 2026-10-02).
# D-198: the Auth emulator too (part of the pinned CLI, no download), so revoking
# tokens and deleting the Auth user are tested.
#
# Prints "RULES TESTS PASSED (N tests)" and exits 0 only when node's runner
# reports N > 0 passing and 0 failing, cancelled, skipped or todo.
set -uo pipefail
cd "$(dirname "$0")/../firebase" || exit 1

out=$(node_modules/.bin/firebase emulators:exec --only firestore,storage,functions,auth \
  --project demo-skipti "node --test --test-concurrency=1 tests/*.test.mjs" 2>&1)
code=$?
printf '%s\n' "$out" | grep -vE 'sun\.misc\.Unsafe|^WARNING: '
[ $code -eq 0 ] || { echo "RULES TESTS FAILED (exit $code)"; exit 1; }

n() { printf '%s\n' "$out" | sed -n "s/^ℹ $1 \([0-9][0-9]*\)$/\1/p" | tail -1; }
tests=$(n tests); pass=$(n pass); fail=$(n fail)
cancelled=$(n cancelled); skipped=$(n skipped); todo=$(n todo)
if [ -z "$tests" ] || [ "$tests" -eq 0 ] || [ "$pass" != "$tests" ] || [ "$fail" != 0 ] \
   || [ "$cancelled" != 0 ] || [ "$skipped" != 0 ] || [ "$todo" != 0 ]; then
  echo "RULES TESTS FAILED (tests=$tests pass=$pass fail=$fail cancelled=$cancelled skipped=$skipped todo=$todo)"
  exit 1
fi
echo "RULES TESTS PASSED ($tests tests)"
