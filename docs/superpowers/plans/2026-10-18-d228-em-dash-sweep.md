# D-228 "The em dash sweep" Implementation Plan

> **Decision 42 narrowed this unit; see the controller amendments.** Superseded where they disagree by decision 42
> (docs/WORKLOG.md, 2026-10-18): the dash rule covers the public surface only, the guard is strict with no baseline
> and no tiers, it has a `--scope whole-repo` mode for the day the repo goes public, and the commit-msg hook checks
> attribution only. Amendments A3, A8, the dash parts of A4 and A9, and decision 41 are void. The text below is the
> plan as written; it is not rewritten.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** No em dash (U+2014) and no en dash (U+2013) anywhere a person can read it, and none ever again: every
user-visible string on both phones, in Rust error text, in the public docs and in the tools' own output is rewritten, the
comments and tests of the live code are swept with a proof that no code changed, and a gate leg fails the build when a
dash comes back. History (BUILD-LOG, ADRs, plans, WORKLOG, SPEC, CLAUDE.md, AGENTS.md) is never rewritten: it is
grandfathered by a baseline that only shrinks.

**Architecture:** One checker (`scripts/check-em-dashes.py`, stdlib, language agnostic: it reads files, it does not parse
them) with two tiers: STRICT (any dash in any form fails, comments included) and GRANDFATHERED (a dash is allowed only
where the baseline already holds it). Four parallel sweep tasks over disjoint files (iOS, Android, Rust core plus
bindings, tools and docs), each proved by `.drafts/d228/proof.py` (a lexer comparison: code identical, strings changed only
as decided). The guard is wired into the gate last, after the sweeps merge, so the tree it first runs on is clean.

**Tech Stack:** Python 3 stdlib, Swift / XCTest, Kotlin / JUnit, Rust, shell.

**Snapshot (2026-10-16, branch d221, `.drafts/d228/inventory.out`; re-run at the unit's start, the numbers will have moved):**
3,375 U+2014 and 165 U+2013 (all 165 are ranges such as `1-7`; none is a spaced en dash), no escape that builds one at
run time (`\u{2014}`, `\u2014`, `&mdash;`, `&#8212;`, `0x2014`), none in code position. By category:

| cat | what | em | en | action |
|---|---|---|---|---|
| A1 | iOS Swift string literals (`ios/Skipti`) | 24 (14 are the `"<D>"` placeholder, 4 are proof screens) | 0 | rewrite |
| A2 | Android Kotlin string literals | 17 (6 placeholder, 5 proof or diagnostic) | 0 | rewrite |
| A3 to A6, A9 | strings.xml, manifests, Info.plist, Functions JS, Hosting pages, release docs | 0 | 4 (RELEASE-INTERNAL-TESTING ranges) | none (en: rewrite) |
| A7 | Rust core non-test literals (import and category error text the shells show) | 8 | 0 | rewrite |
| A8 | public docs (README, CONTRIBUTING, GUARANTEES) | 7 | 0 | rewrite |
| B | comments in live code: iOS 106, Android 161 (25 are `build.gradle.kts`), core 144, scripts 39, deploy 18, Makefile 7, `.github` 7, `.githooks` 2, `.gitignore` 4, `deny.toml` 3, brand and fonts READMEs 10, core SQL and TOML 2, one deploy data line | 504 | 11 | sweep with proof |
| B | strings in tools: scripts 32 (23 are the gate's and its legs' own output lines), deploy 9, `.githooks` 5, Makefile 2, brand 3 | 51 | 0 | rewrite |
| C | tests: iOS 40 comments + 4 strings, Android 53 + 1, core 51 + 6, firebase 0 (3 en) | 155 | 5 | sweep |
| D | generated bindings (Swift 45, Kotlin 39) | 84 | 4 | regenerate from the Rust source |
| E | BUILD-LOG (1,946) | 1,946 | 50 | NEVER rewritten, baseline |
| B | grandfathered docs: WORKLOG 218, ADRs, specs, plans, SPEC, DEPLOY-ORACLE, CLOUD-SECURITY 297, CLAUDE.md 50, AGENTS.md 12 | 577 | 91 | baseline |
| F | `native/licences/licences.json` (the OFL text, third party) | 2 | 0 | exempt |

## Design

1. **The rule per context (decided by the planner; the founder reads em dashes as "sounds like AI").**
   (In this plan `<D>` stands for an em dash, written that way so the plan itself stays clean; the code points are
   spelled `\u2014` and `\u2013`.)
   - Spaced dash between clauses, the right side explains or lists what the left side said: colon. `Duplicate: Ana`.
   - Spaced dash, right side is a trailing aside or consequence: comma.
   - Spaced dash, right side starts a new sentence (capital letter, a digit, a section sign, a backticked identifier that
     starts a sentence): full stop, capital kept.
   - A pair of dashes around an aside (`A <D> B <D> C` in the old form): parentheses, or two commas if the aside is short.
   - A dash that ends a wrapped comment line or starts the next one: join to a comma at the end of the earlier line; never
     start a line with a comma or a dash.
   - Dated list lead-ins (`2026-10-01 <D> #5, #6 and #7 change`): colon after the date.
   - Table cells and parentheses (`*(not started <D> brand fonts only)*`): colon.
   - Unspaced dash inside a word pair (`uber-eats` with a dash): never in prose; as test INPUT it stays, written as an
     escape on one marked line (Design 6).
   - Numeric and letter ranges (`1-7`, `a-z`, `3-20`, `D-100-D-110`): the word "to" in prose and UI (`1 to 7`); a plain
     hyphen only inside an identifier or a compact reference that already has one (`D-100 to D-110`).
   - The deliberate hyphen-minus: SKIPTI's own typed hyphen (`-`) is allowed everywhere. Only U+2014 and U+2013 are banned.
2. **Strings are decided by a table, never by a script.** The twin rule: iOS and Android copy is identical, so each
   decision below is written once and both shells apply the same text. The inventory's A-TWINS section lists every pair;
   the table covers them all:

   | where (iOS / Android) | old | new |
   |---|---|---|
   | 14 iOS sites and 6 Android sites of `?? "<D>"` (money fallbacks, the Overview "Settled" tile, ImportReview) | a lone em dash | `Placeholder.none` = `-` (iOS `enum Placeholder { static let none = "-" }` in `AppLocale.swift`; Android `object Placeholder { const val NONE = "-" }` in `AppLocale.kt`) |
   | `SettleAllCopy.recorded` (`SettleAll.swift`, `SettleScreen.swift:285`) / `SettleAllCopy.recorded` (`SettleAll.kt`) and `SettleScreen.kt:316-320` (builds it by hand from `CONFIRMATION_TAIL`) | `Settled with Ana <D> recorded in your ledger. No money moved.` | `Settled with Ana. Recorded in your ledger. No money moved.` Both screens call the one function (Android: delete `CONFIRMATION_TAIL`, a second spelling of the sentence). |
   | `ImportReview.swift:319` / `ImportFlow.kt:556`, `ImportScreen.kt:271` | `Duplicate <D> <label>` | `Duplicate: <label>` |
   | `VaultRecoveryView.swift:46` (iOS only; Android's recovery screen has other copy) | `nobody can read them <D> not us, not anyone with the file` | `nobody can read them, not us and not anyone with the file` |
   | `AddExpenseScreen.kt:905` (Android only, see residue) | `WEIGHTS, NOT AMOUNTS <D> 2 ADULTS + 1 CHILD AS 2:2:1` | `WEIGHTS, NOT AMOUNTS: 2 ADULTS + 1 CHILD AS 2:2:1` |
   | `AddExpenseScreen.kt:1026` (Android only) | `One person paid, nobody owes <D> logged in the group` | `One person paid, nobody owes. Logged in the group` |
   | proof and diagnostic text (developer surfaces behind launch args, not the product): `ExportProofScreen.swift`, `ImportProofScreen.swift`, `Exporter.kt:265,268`, `ImportReview.kt:368`, `ImportScreen.kt:204`, `VaultSmoke.swift:62`, `BalanceScreen.swift:340,351`, `AndroidKeystore.kt:57` | `X <D> Y` | `X: Y` where Y explains X, `X, Y` for an aside, `X (Y)` for `Member bindings (one per line): Name=participant id`; each pair (iOS `Section(...)` and Android `ProofColumn(header = ...)`) gets the SAME words. Any test that reads one of these strings moves with it (grep each old string in the test trees first). |
   | Rust import and category errors (`budgetbakers.rs:122,138,147`, `splitwise.rs:101,242,339`, `merki/mod.rs:268,622`) | `A <D> B` | `A: B` for a cause, `A (B)` for the section aside (`{parent} is already a child (§8.1 is two levels, hard)`), `A. B` where B is a new sentence; every `contains(` assertion on these messages moves with them |
   | public docs: `README.md:24,39`, `CONTRIBUTING.md:26,59`, `GUARANTEES.md:52,59,68` | `X <D> Y` | `X: Y` / `X. Y` by the rule; GUARANTEES.md changes punctuation only, in three dated amendment headings (`**2026-09-14: #6 gains one exception...**`), no word of a guarantee |

   **DECIDED (planner, for the founder): the placeholder is a plain hyphen-minus.** It is not an em dash, one named
   constant per shell replaces 20 literals, and it keeps the Overview tile and the money fallbacks as they are. The
   alternative (show `0.00` from core on the Overview tile) changes what the "Settled" tile says; the founder can veto
   at the look.
3. **Comments are swept mechanically, with a proof.** `.drafts/d228/sweep.py` (written by the implementer of each task, one
   file per area, never shared) applies Design 1 to comments only, using `inventory.py`'s `Lexer` to find them, with a
   golden table of 12 before/after pairs as its own test, asserting every anchor. After a run,
   `python3 -I .drafts/d228/proof.py --strict-strings <paths>` must print `PROOF OK`: the text with every comment and
   string blanked is byte-identical to `HEAD`, and no string literal changed. The implementer then reads the diff
   (`git diff -U0 | grep '^[+]'`) once for comma splices and fixes those by hand. Shell, Python, YAML, TOML, Makefile and
   `.gitignore` files cannot be lexed by `proof.py`: they get `bash -n` / `python3 -m py_compile` and a `git diff -U0`
   read, and every changed non-comment line is listed in the report.
4. **Generated files are fixed at the source.** `ios/Skipti/Bindings/skipti_core.swift` and
   `android/.../uniffi/skipti_core/skipti_core.kt` copy Rust `///` doc comments. Task 4 sweeps `core/src`, runs
   `make bindings`, and the diff of both bindings must be comment-only and end at 0 dashes. Doc comments do not enter
   UniFFI's checksums (they hash signatures) (Corrected D-228: UniFFI 0.32 hashes doc comments into its checksums; a
   doc-comment change changes the bindings' checksums and needs a core rebuild. Task 3 measured seven checksums
   change in both bindings, exactly the seven exports whose doc text changed.), and `core/src/skrin/schema_v1.sql`'s two dashes are in the header comment
   before the first `CREATE`, so no stored SQL changes (assert this: `git diff` shows only comment lines in that file).
5. **The gate's own output changes, and history does not.** `GATE PASSED <D> N passed, 0 skipped` becomes
   `GATE PASSED: N passed, 0 skipped`, and every sibling (`GATE FAILED`, `GATE PASSED WITH SKIPS`, `[SKIP] name: reason`,
   `[PASS] cargo test --workspace: tally`, `exit N: kind: output: path`, `BUILD-LOG STRUCTURE OK: ...`,
   `SWIFT AUTOLINK OK: ...`, `PLAY MANIFEST CHECK OK: ...`, `exported uniffi symbols: ...`) uses a colon, so new evidence
   blocks pasted into BUILD-LOG section 3 are clean. What reads the old line: ONLY `.drafts/common/record_unit.py`
   (`PASSED = "GATE PASSED ... 22 passed, 0 skipped"` and `startswith("GATE PASSED")`), git-excluded, so Task 4 edits it in
   place (new constant `GATE PASSED: <N+1> passed, 0 skipped`, N+1 because Design 7 adds a leg; read N from a real
   run), and CLAUDE.md section 9.1, which quotes the line (the edit removes a baselined line and adds a clean one).
   `scripts/check-buildlog.py` does not read any gate line (it checks D-number contiguity, row shape and fences), so
   BUILD-LOG's 130 old evidence lines stay valid and untouched; the checker's own `OK` line changes to a colon.
   `scripts/gate_inputs.py` and `scripts/test_gate_inputs.py` read no gate text (verified by grep).
6. **The guard (`scripts/check-em-dashes.py`).**
   - Files: `git ls-files --cached` plus `--others --exclude-standard` filtered to text suffixes (so an untracked new
     source file is caught before `git add`, and `brand/skipti-icon-assets/` PNGs are not read). A file that is not valid
     UTF-8 or has a NUL is skipped. Skipped by path: `native/licences/licences.json`, `firebase/functions/package-lock.json`,
     `firebase/package-lock.json`, `node_modules/`.
   - Detected in a STRICT file (literal and escaped, whole file, comments included, no lexing): U+2014, U+2013, `\u2014`,
     `\u{2014}`, `\u2013`, `\u{2013}`, `&mdash;`, `&ndash;`, `&#8212;`, `&#8211;`, `&#x2014;`, `&#x2013;`, `0x2014`,
     `0x2013`, case-insensitive. The checker and its test build every pattern from `chr()` and hex fragments, so neither
     file contains one (the leg runs on itself).
   - STRICT is every scanned file that is not GRANDFATHERED. GRANDFATHERED (a fixed list in the script):
     `docs/BUILD-LOG.md`, `docs/WORKLOG.md`, `docs/adr/`, `docs/superpowers/`, `docs/SPEC.md`, `docs/DEPLOY-ORACLE.md`,
     `docs/CLOUD-SECURITY.md`, `CLAUDE.md`, `AGENTS.md`. Comments are therefore INCLUDED in the strict tier, because they
     are swept in this unit; the one deliberate exception is a line carrying the marker `em-dash-ok:` (same line or the line
     above) with a reason, for input that is itself a dash under test (core `merki/auto.rs`'s no-folding test, written
     `"uber\u{2014}eats"` on one marked line).
   - GRANDFATHERED rule: the baseline `scripts/em-dash-grandfathered.txt` holds, per path, the 8-hex hash of a window (20
     characters each side of every dash, newlines folded to a space) for each dash the file had at D-228 (about 2,600 hashes,
     about 30 KB). A file passes when its multiset of windows is a subset of the baseline's. Editing a long BUILD-LOG
     row far from its dashes keeps its windows, so `buildlog_edit.py` corrections keep working; editing within 20
     characters of an old dash, or adding any dash, fails and the author removes it. The baseline only shrinks:
     `--write-baseline` refuses to write a path outside the GRANDFATHERED list and refuses a hash set larger than the
     committed one unless `--allow-grow` is passed (used once, at D-228).
   - Cost: one pass over about 900 files, under a second. It is a CHEAP leg (runs in `--cheap`, the fast gate and
     `--full`), so the pre-commit hook enforces it on every commit.
   - Output (colon style): `EM DASH CHECK OK: N files, M grandfathered dashes` or, on failure, one
     `path:line: U+2014 (strict)` row per hit with a 60 character excerpt and the rule to apply.
   - Per-shell source tests: **none**, decided. The leg is language agnostic, already covers both shells' Swift and Kotlin
     (strings, comments, resources, manifests) plus Rust, JS, HTML, shell and docs, and runs where the hook runs; a second
     copy in XCTest and JUnit would run only inside the slow legs and add two sabotage lanes for no extra reach. The only
     per-shell tests are the ones that pin the new copy (Task 2, Task 3).
   - Optional, NOT planned unless the founder says yes: a `.githooks/commit-msg` hook rejecting a dash (and, since the
     FENRIR incident, a `Co-Authored-By` trailer) in the message. Commit messages are covered today by his standing rule
     and run-common.md only.
7. **Gate wiring.** One new leg after "swift list rows": `run_gate "em dashes (D-228: no U+2014 or U+2013, strict plus
   grandfathered)" "" python3 scripts/check-em-dashes.py` (the leg name spells the code points, not the glyphs), and the
   self-test runs inside the same leg through `scripts/test_check_em_dashes.py` (`python3 scripts/check-em-dashes.py &&
   python3 scripts/test_check_em_dashes.py`, wrapped in one function like `swift_interpolation`). Leg count 22 becomes 23;
   CLAUDE.md section 4 and 9.1 say so (clean text). `scripts/gate_inputs.py` treats only `gate.sh` and `gate_inputs.py`
   as heavy scripts, so the new files are not heavy inputs.

## Global Constraints

- No em dash and no en dash in anything this unit writes, plan and plans of its tasks included (the guard runs on them).
- Run order: Tasks 1 to 4 in parallel on disjoint files (below), then Task 5 (guard, serial, after 1 to 4 are
  committed), then Task 6 (look), then the controller's full gate and the records. The unit starts from a fresh
  branch off `main` after the other units' branches have merged, with `inventory.py` re-run first and its numbers
  compared with the Snapshot.
- Files per task are disjoint. Task 1: `ios/**` except `ios/Skipti/Bindings/`. Task 2: `android/**` except
  `android/app/src/main/kotlin/uniffi/`. Task 3: `core/**`, both bindings files, `.drafts/common/record_unit.py`.
  Task 4: `scripts/`, `deploy/`, `Makefile`, `.github/`, `.githooks/`, `.gitignore`, `deny.toml`, `brand/`, `native/fonts/README.md`,
  `web/fonts/README.md`, `firebase/tests/`, `README.md`, `CONTRIBUTING.md`, `GUARANTEES.md`, `docs/RELEASE*.md`. Task 5: the
  new guard files, `scripts/gate.sh` (after Task 4's commit), `CLAUDE.md`. Nobody edits a GRANDFATHERED file except Task 5's
  CLAUDE.md lines named below.
- No new Swift file (no pbxproj edit); tests go into existing classes (`BalanceLineTests`, `SettleAllTests` equivalents).
- Values and words come from the table in Design 2; a string that is not in it is added to it (in the plan's copy
  table, `.drafts/d228/copy-table.tsv`, column `replacement`) by the task that finds it, with the twin's text, before it is
  edited.
- Parallel agents in one checkout (CLAUDE.md section 4 item 4): none may `git stash`, `reset`, `checkout -- <file>` or
  `restore`; each commits only its own explicit paths and refuses (waits, then reports) if `git diff --cached --name-only`
  holds a file it did not stage. Commits end `[skip ci]`, carry no attribution of any kind, and contain no dash.
- iOS tests: boot `AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51` first, `-collect-test-diagnostics never`, one class at a time;
  shut it down when done. Android: `cd android && zsh -lic './gradlew :app:testPlayDebugUnitTest --tests "..."'`, result XML
  mtimes before quoting a tally. Rust: `cargo test -p skipti-core --lib <name>`; after Task 3, `make bindings`.
- Money strings still come from core; this unit changes no amount formatting. No new dependency. Developer Tools
  exemption rules (CLAUDE.md 13) hold. No deploy of anything.
- Never weaken a test to make it pass: a test that asserted old copy is updated to the new copy from the table, and says
  so in the report.

---

### Task 1: iOS sweep (parallel with Tasks 2 to 4)

**Files:** `ios/Skipti/App/*.swift`, `ios/Skipti/Relay/*.swift`, `ios/SkiptiTests/*.swift`. Not `ios/Skipti/Bindings/`.
Counts at snapshot: 24 strings (14 placeholder, 6 copy, 4 proof), 106 + 40 comments, 3 + 2 en ranges.

- [ ] **Step 1: Pin the new copy first (tests).** In `BalanceLineTests.swift`: update
  `XCTAssertEqual(SettleAllCopy.recorded("Ana"), "Settled with Ana. Recorded in your ledger. No money moved.")`; add
  `testThePlaceholderIsAPlainHyphen` (`Placeholder.none.unicodeScalars.map(\.value) == [0x2D]`); add
  `testMoneyFallbacksUseThePlaceholder` (a source guard: no file under `ios/Skipti/App` has a line containing both
  `ffiMoney` and `?? "`, and `Placeholder.none` appears at least 14 times); add
  `testDuplicateAndRecordedCopyHaveNoDash` over the strings reachable from code (`SettleAllCopy.recorded("Ana")`,
  `Placeholder.none`) asserting no scalar 0x2014 or 0x2013. Run: red for the right reason (no `Placeholder`).
- [ ] **Step 2: The strings.** `enum Placeholder` in `AppLocale.swift`; replace the 14 `?? "<D>"` and
  `stat(OverviewCopy.settledShort, "<D>", ...)`; `SettleAll.swift:22` and `SettleScreen.swift:285` call one function (the
  second site stops composing its own copy); `Duplicate: \(d.label)`; `VaultRecoveryView.swift:46`; the four proof and
  diagnostic strings and `BalanceScreen.swift:340,351` from the table. Update `Text(...)` call sites only inside the string.
- [ ] **Step 3: The comments and test comments** with `.drafts/d228/ios/sweep.py` (Design 3). `proof.py --strict-strings`
  over every changed comment-only file prints `PROOF OK`; over the files with Step 2 edits, run without `--strict-strings`
  and check every printed string pair against the table.
- [ ] **Step 4:** the three en ranges in `About.swift`, `CloudProfile.swift`, `GoogleSignIn.swift` and `AccountTests.swift`
  become `1 to 7`, `1 to 60`, `2 to 4`. `python3 -I .drafts/d228/inventory.py` shows iOS (`ios/Skipti`, `ios/SkiptiTests`) at 0.
- [ ] **Step 5:** run `BalanceLineTests`, `SettleAllTests` (or whichever class holds the settle copy), `AboutTests`,
  `ImportReviewTests`, `VaultGateTests`, `ExporterTests`, `AccountTests`; then `make ios-app` is the controller's gate, not
  yours. Report tallies, not "green".

### Task 2: Android sweep (parallel with Tasks 1, 3, 4)

**Files:** `android/app/src/main/java/**`, `android/app/src/main/AndroidManifest.xml` and `res/**` (comments), `android/app/src/play/**`,
`android/app/src/test/**`, `android/app/build.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`. Not
`android/app/src/main/kotlin/uniffi/`. Counts: 17 strings (6 placeholder, 5 proof or diagnostic, 6 copy), 161 + 53 comments.

- [ ] **Step 1: Tests first.** `SettleAllTest.kt:89` expects the new sentence; add the twins of Task 1's three tests
  (`the_placeholder_is_a_plain_hyphen`, `money_fallbacks_use_the_placeholder` reading the Kotlin sources like
  `ThemeArtTest` reads files, `copy_has_no_dash`). Red for the right reason.
- [ ] **Step 2: The strings**: `object Placeholder { const val NONE = "-" }` in `AppLocale.kt`; the 6 `"<D>"` sites;
  `SettleScreen.kt:316-320` calls `SettleAllCopy.recorded` (delete `CONFIRMATION_TAIL`, check no other reader);
  `Duplicate: ${...}` at `ImportFlow.kt:556` and `ImportScreen.kt:271`; the two add-expense kickers; the proof strings
  (`Exporter.kt`, `ImportReview.kt:368`, `ImportScreen.kt:204`) and `AndroidKeystore.kt:57`, each with the same words as its iOS
  twin.
- [ ] **Step 3: Comments** via `.drafts/d228/android/sweep.py` and `proof.py` exactly as Task 1 (`.kts` files lex as Kotlin).
  XML comments (9) in `res/` and manifests: edited by the same sweep with a `xmllint`-free check (`python3 -c
  'import xml.dom.minidom,sys; xml.dom.minidom.parse(sys.argv[1])'` per file).
- [ ] **Step 4:** the en ranges in `About.kt` and `AccountTest.kt`. Inventory shows Android at 0 (bindings excepted).
- [ ] **Step 5:** run `SettleAllTest`, `SettleTest`, `AboutTest`, `ImportFlowTest`, `AddExpenseTest`, `BalanceScreenTest`,
  `AccountTest`, `PortTest`, `LedgerStoreTest`, then `:app:testPlayDebugUnitTest` in full once (JVM only; it needs
  `cargo build -p skipti-core` fresh after Task 3, so run the full class set after Task 3 lands, and say which). Check mtimes.
  Add to the report: the three Android-only kickers have no iOS twin (Design residue); do not add one.

### Task 3: Rust core and the bindings (parallel with Tasks 1, 2, 4)

**Files:** `core/src/**`, `core/tests/**`, `core/Cargo.toml`, `core/src/skrin/schema_v1.sql`,
`ios/Skipti/Bindings/skipti_core.swift`, `android/app/src/main/kotlin/uniffi/skipti_core/skipti_core.kt`,
`.drafts/common/record_unit.py`. Counts: 8 error strings, 144 + 51 comments (32 in `ffi.rs`), 84 in bindings, 3 + 2 en ranges.

- [ ] **Step 1: Tests first.** For each of the 8 error strings, find the tests that assert it
  (`grep -rn '<fragment of the old text>' core`); update them to the table's text first and see them red.
  `merki/auto.rs`: the no-unicode-folding test becomes `let input = "uber\u{2014}eats"; // em-dash-ok: the input under test`
  then `assert_eq!(normalise(input), input, "no unicode folding, determinism beats cleverness")` (it still proves what it
  proved: an em dash is not folded).
- [ ] **Step 2: Strings and comments.** Error text from the table; comments by `.drafts/d228/core/sweep.py` (Rust lexes);
  `proof.py` over every `.rs` file: strings differ only on the 8 rows and the 2 test messages. `schema_v1.sql` and
  `Cargo.toml` comment lines by hand (3 lines), `git diff` shows comment lines only.
- [ ] **Step 3: Regenerate.** `cargo build -p skipti-core`, `make bindings`; `git diff --stat` on both bindings: comment lines
  only, 0 dashes left (`grep -c` with the escaped code points via `python3 -I .drafts/d228/inventory.py`, section D empty).
- [ ] **Step 4:** `cargo test -p skipti-core --lib` over `flutningr` and `merki`, and `cargo test -p skipti-core --test ledger_port`;
  `cargo clippy -p skipti-core -- -D warnings` is the controller's gate. `record_unit.py`: edit the constant and
  the `startswith` per Design 5 (a git-excluded file: report the diff, there is nothing to commit).

### Task 4: Tools, scripts, docs and Firebase tests (parallel with Tasks 1 to 3)

**Files:** see Global Constraints. Counts: scripts 71, deploy 28, Makefile 9, `.github` 7, `.githooks` 7, `.gitignore` 4, `deny.toml` 3,
brand 8, fonts READMEs 5, public docs 7, release docs 4 en ranges, `firebase/tests/people.test.mjs` 3 en ranges, `scripts/gate_inputs.py` 1.

- [ ] **Step 1: The gate's own text first.** In `scripts/gate.sh` change the ten output strings of Design 5 (the sibling
  scripts' `OK` and `FAILED` lines too). `bash -n scripts/gate.sh` and the cheap run (`./scripts/gate.sh --cheap`) must
  print `GATE PASSED: N passed, 0 skipped` and the evidence block with no dash (the run is the proof; do not paste it
  anywhere yet). Gate-comment dashes in `gate.sh` (16) go in the same pass; do not touch the file while a gate runs.
- [ ] **Step 2: Everything else** by `.drafts/d228/tools/sweep.py` on comment lines (a `#` comment test per file type), and
  by hand on the 51 string lines (echo, printf, print). Checks: `bash -n` on every changed `.sh`, `python3 -m py_compile` on every
  changed `.py`, `python3 -c 'import yaml'` is NOT available, so `ci.yml` is read by eye (7 comment lines), `deny.toml` by
  `cargo deny check licenses` (the controller's gate runs it), the Makefile by `make -n gate` (prints, runs nothing).
- [ ] **Step 3: Public docs and release docs** from the table (7 + 4); `firebase/tests/people.test.mjs` test name
  `a username is 3 to 20 of a to z, 0 to 9 and _, stored lower case without @`. Do NOT touch `docs/WORKLOG.md`,
  `docs/BUILD-LOG.md`, `CLAUDE.md` or `AGENTS.md` here.
- [ ] **Step 4:** `scripts/firebase-rules-test.sh` is the controller's gate; run only `node --test firebase/tests/people.test.mjs`
  if it needs no emulator, else report that it was not run. Inventory shows Task 4's files at 0.

### Task 5: The guard (serial, after Tasks 1 to 4 are committed)

**Files:** create `scripts/check-em-dashes.py`, `scripts/test_check_em_dashes.py`, `scripts/em-dash-grandfathered.txt`;
modify `scripts/gate.sh` (one leg), `CLAUDE.md` (three lines, below).

- [ ] **Step 1: Tests first** (`unittest`, no git, no network: `check(files: dict, baseline: dict) -> list` is a pure
  function; the CLI collects files and calls it). Cases: each of the 14 forms in Design 6 in a `.swift`, `.kt`, `.rs`, `.mjs`,
  `.html`, `.md`, `.sh` and `.xml` fixture fails; a clean fixture passes; a marked line (`em-dash-ok:`) passes, the marker two
  lines above does not, the marker without a reason does not; a GRANDFATHERED file passes with its own baseline
  windows, fails with one new dash, fails when the text within 20 characters of an old dash changes, passes when text far
  from it changes (the long-BUILD-LOG-row case); `--write-baseline` refuses a strict path and refuses growth without
  `--allow-grow`; a non-UTF-8 file and a file with a NUL are skipped; the checker's and the test's own source contain no
  dash in any form (read both and run `check` on them); `gate.sh` contains the leg and the leg sits outside every
  `if [ $CHEAP -eq 0 ]` block (read the file, count the nesting by the `fi` lines like the existing scripts do); the
  GRANDFATHERED list equals the paths in the baseline file.
- [ ] **Step 2: The checker** per Design 6 (stdlib, `git ls-files`, one pass, output in colon style).
- [ ] **Step 3: The baseline.** Re-run `inventory.py` first: STRICT sections A to D must read 0 for EM and for en dashes;
  then `python3 scripts/check-em-dashes.py --write-baseline --allow-grow`; check the file is under 40 KB and lists only
  GRANDFATHERED paths; `python3 scripts/check-em-dashes.py` exits 0.
- [ ] **Step 4: Wire it** (Design 7) and edit CLAUDE.md: section 4's leg count and list (add the leg), section 9.1's
  `GATE PASSED: N passed` quote, and one line in section 10 under "No em dashes" stating the guard and the marker. These three
  edits are the only CLAUDE.md edits; they add no dash. `./scripts/gate.sh --cheap` passes and prints the leg.
- [ ] **Step 5:** `.drafts/d228/sabotage.py` (Sabotage matrix) in the controller's lanes; controller's full gate; then the records
  (`scripts/buildlog_edit.py`; the BUILD-LOG row and block use no dash; the inventory output after the sweep is the evidence).

### Task 6: Look (after Task 5; controller dispatch)

See **Look**. Write `.drafts/d228/look/look-report.md` and pairs in `.drafts/d228/look/pairs/`.

---

## Sabotage matrix

Sized to the risk (CLAUDE.md section 4 speed rule 2): each new guard once, each form once. The guard rows run only the
checker and its test (seconds, no build), in one throwaway git worktree outside the checkout
(`/private/tmp/skipti-d228-guard`); the copy rows run in two lanes (`-ios`, `-android`, own DerivedData,
`android/local.properties` copied) from `.drafts/d217/sabotage.py lanes`. Each row: back up, sabotage through an asserted
anchor, confirm the edit landed, zero compile errors (copy rows), exactly the listed tests red, restore, `cmp`. About 38 rows.

Guard (G): sabotage `scripts/check-em-dashes.py`; the test file is the red.

| row | sabotage | expected red |
|---|---|---|
| G1 | the U+2013 pattern deleted | the en dash cases |
| G2 | the escape patterns (`\u{2014}`, `\u2014`) deleted | the escape cases |
| G3 | `&mdash;` and the numeric entities deleted | the HTML entity cases |
| G4 | `.kt` dropped from the text suffix list | the Kotlin cases |
| G5 | untracked files not listed (`--others` removed) | the untracked-file case |
| G6 | the marker accepted anywhere in the file | the marker-two-lines-above case |
| G7 | the marker accepted without a reason | the marker-without-reason case |
| G8 | the window hash ignores the text (constant) | the changed-near-a-dash case |
| G9 | the window is 200 characters instead of 20 | the far-edit-passes case |
| G10 | a GRANDFATHERED path added to the strict skip list | the grandfathered-fails-on-new-dash case |
| G11 | `docs/BUILD-LOG.md` removed from GRANDFATHERED | the baseline-equals-list case |
| G12 | `--allow-grow` check removed | the write-baseline growth case |
| G13 | the NUL check removed | the binary-skip case |
| G14 | the leg's name changed in `gate.sh` to a heavy-only position (inside the `CHEAP -eq 0` block) | the gate-wiring case |
| G15 | the checker's own source gains a literal dash | the self-clean case |

Tree (T): reintroduce one dash into a clean file; `python3 scripts/check-em-dashes.py` must fail naming exactly that file and
line (and pass again after restore).

| row | where | form |
|---|---|---|
| T1 | a Swift string in `ios/Skipti/App/SettleAll.swift` | U+2014 |
| T2 | a Swift comment in `ios/Skipti/App/SettleScreen.swift` | U+2014 |
| T3 | a Kotlin string in `SettleAll.kt` | `\u2014` |
| T4 | a Kotlin comment in `android/app/build.gradle.kts` | U+2014 |
| T5 | a Rust doc comment in `core/src/ffi.rs` | U+2014 |
| T6 | a Rust string in `core/src/merki/mod.rs` | `\u{2014}` |
| T7 | a Functions string in `firebase/functions/push.js` | U+2014 |
| T8 | `firebase/public/privacy/index.html` | `&mdash;` |
| T9 | an `echo` in `scripts/check-play-manifest.sh` | U+2014 |
| T10 | `README.md` | U+2013 between spaces |
| T11 | a new untracked `.swift` file | U+2014 |
| T12 | a line of `docs/BUILD-LOG.md` | a NEW dash (fails) and an edit far from an old dash (passes) |
| T13 | `ios/Skipti/Bindings/skipti_core.swift` | U+2014 (also the bindings freshness leg in the full gate, once) |

Copy (C), per shell, from the table in Design 2:

| row | file | sabotage | expected red |
|---|---|---|---|
| C-i1 | `AppLocale.swift` | `Placeholder.none` to `"n/a"` | testThePlaceholderIsAPlainHyphen |
| C-i2 | one call site `?? "-"` back to a literal | | testMoneyFallbacksUseThePlaceholder |
| C-i3 | `SettleAll.swift` | `Settled with \(other). Recorded` to a comma | the SettleAllCopy assertion |
| C-i4 | `ImportReview.swift` | `Duplicate: ` to `Duplicate ` | ImportReviewTests (if it asserts; else the row is dropped and said so) |
| C-a1 | `AppLocale.kt` | `NONE = "-"` to `"n/a"` | the_placeholder_is_a_plain_hyphen |
| C-a2 | one call site back to a literal | | money_fallbacks_use_the_placeholder |
| C-a3 | `SettleAll.kt` | the sentence's first full stop to a comma | the SettleAllTest assertion |
| C-a4 | `SettleScreen.kt` | the screen composes its own sentence again | a source guard in `SettleAllTest` that `CONFIRMATION_TAIL` is gone and `recorded(` is called |
| C-r1 | one of the 8 Rust messages | the `: ` to ` <D> ` is NOT a row (the leg catches it, T6); instead the text changes by one word | its `contains(` test |
| C-b1 | a regenerated bindings file | one doc comment line restored with a dash | the bindings freshness leg (full gate, once) and T13 |

## Look

Throwaway devices only, against the local emulators (CLAUDE.md run-common): iOS `d214-look` (find the full UDID with
`xcrun simctl list devices | grep BDC0AE93`); Android the throwaway AVD `run-look` (create it if missing; `-gpu host`);
never AFAF4221, EB725812 or `skipti-shots`; every launch passes `--emulators`; uninstall the app first. Rebuild after the
sabotage run before looking and check the dylib or APK is fresh (`strings` for `Recorded in your ledger`).

Pairs (iOS beside Android, the same seeded ledger), in Aurora light and Norse (the face and the wrap differ most):
1. Settle up: settle one line; the confirmation reads `Settled with <name>. Recorded in your ledger. No money moved.` on both,
   identical, no stray space or capital, and wraps cleanly at the largest Dynamic Type / font scale.
2. Overview of a fully settled group: the fourth tile shows `Settled` over `-`, the hyphen sits on the figures' baseline and
   reads as a placeholder, not as a minus. If it looks wrong, the founder's call is Design 2's alternative.
3. Add expense, Android only: the weights kicker and the "one person paid" kicker with the new punctuation (capitals kept).
4. Import review with a duplicate row: `Duplicate: <label>` on both shells.
5. iOS vault recovery (a vault with a deleted key on the throwaway sim, or the view in a preview): the paragraph reads
   naturally with the comma. Where a screen is not reachable on a throwaway device, say so and give the string-level
   evidence (the test and the guard).
Shut every device down after; `pgrep -f qemu-system` empty.

## Expected section 4 residue

- History is grandfathered, not rewritten: about 2,600 dashes remain in BUILD-LOG (1,946), WORKLOG, ADRs, specs, plans, SPEC,
  DEPLOY-ORACLE, CLOUD-SECURITY, CLAUDE.md (50) and AGENTS.md (12), held by `scripts/em-dash-grandfathered.txt`, which may only
  shrink. Sweeping CLAUDE.md and AGENTS.md (62) is a founder yes (they are his rule files), a ten minute follow-up
  that removes their paths from the list.
- The guard sees characters, not meaning: it cannot catch a dash-like glyph (`--`, a spaced hyphen, U+2212 minus, U+2015),
  and it does not read commit messages or pull request text (an optional `commit-msg` hook is proposed, not built).
- Dashes that arrive at run time from outside the repo (a group or merchant name a person typed, Apple's and Google's own
  system strings, ICU date formats) are not ours to sweep. No `DateIntervalFormatter` or `formatDateRange` is used today.
- `native/licences/licences.json` keeps its two dashes in the OFL text (third party, `scripts/licences.py` regenerates it).
- Twin gaps found by the inventory and NOT fixed here (product scope, the founder's): Android's add-expense kickers
  `WEIGHTS, NOT AMOUNTS` and `One person paid, nobody owes` have no iOS counterpart; iOS's `VaultRecoveryView` paragraph has no
  Android counterpart (Android's recovery screen has other copy); `ImportScreen.kt` is a launch-arg proof screen.
- The Android proof surfaces' casing (`EXPORT FILE WRITING` against iOS `Export file writing`) predates this unit.
- The placeholder is a hyphen-minus by the planner's decision (Design 2); the founder judges it at the look.

## Controller amendments (2026-10-16, binding over the text above)

- **A1** The "no value" placeholder becomes a plain hyphen through one constant per shell (the planner's recommendation).
- **A2** GUARANTEES.md: punctuation-only edits in its three dated headings (no promise changes; recorded in the row).
- **A3** CLAUDE.md and AGENTS.md ARE swept (the founder's rule: no em dashes anywhere, headings and prose included);
  history files (BUILD-LOG, WORKLOG, ADRs, specs, plans) stay grandfathered by the baseline.
- **A4** Build the `commit-msg` hook (refuses U+2014 and spaced U+2013 in a commit message), installed the way the
  pre-commit hook is, with a self-test in the gate's self-test leg; the founder's rule covers commit messages.
- **A5** The twin gaps the inventory found (Android-only add-expense kickers "WEIGHTS, NOT AMOUNTS" and "One person
  paid, nobody owes"; an iOS-only `VaultRecoveryView` paragraph) go to S2 (D-225, form twins), not D-228.

## Controller amendments (session 6, 2026-10-18; bind over the text above and over A1 to A5 where they differ)

- **A6 (the snapshot moved):** `inventory.py` re-run on main at 87da0e7: `.drafts/d228/inventory-s6.out` (3,360 U+2014, 0
  spaced U+2013, 165 range U+2013, 15 escapes, all 15 in tests). Each task works from THIS output, not the 2026-10-16
  numbers. Design 2's table: the `VaultRecoveryView` row is gone (D-225 rewrote that screen); A5's two Android kickers are
  already twins with colon copy on both phones (D-225 A6, A8): no work. Any user-visible string not in the table goes
  into `.drafts/d228/copy-table.tsv` with its twin's text before it is edited.
- **A7 (escapes in tests):** the 15 escapes are tests that ASSERT a dash is absent (e.g. `AccountOwnerTest.kt` reading
  `VaultRecoveryCopy.EXPLAIN`). They stay, each on a line marked `em-dash-ok:` with the reason, or are rebuilt from a
  code point (`Character(UnicodeScalar(0x2014)!)` / `'—'` built by arithmetic) if the checker's escape patterns
  would otherwise trip. Task 5's checker treats the marker as Design 6 says.
- **A8 (A3 binds):** CLAUDE.md and AGENTS.md are swept, so they leave the GRANDFATHERED list; Task 5 owns both (it
  already owns CLAUDE.md) and sweeps them by Design 1 with the word-for-word meaning kept (punctuation only, and the
  "Evidence" quote of the gate line updated to the colon form). The founder's global `~/.claude/CLAUDE.md` is outside
  the repo: not touched.
- **A9 (A4 binds, the hook):** `core.hooksPath` is already `.githooks`, so the commit-msg hook is a new executable file
  `.githooks/commit-msg` (no git config change). It refuses U+2014, a spaced U+2013, and (FENRIR 2026-09-11) a
  `Co-Authored-By:` trailer or a "Generated with" line; self-test in `scripts/test_gate_inputs.py`'s leg or its own
  script wired into the gate's self-test leg; Task 5 owns it.
- **A10 (the gate line):** "GATE PASSED: N passed, 0 skipped" with N = 23 after the new leg. Every reader of the old
  line: `.drafts/common/record_unit.py` (Task 3, as planned) and the records scripts of later units (D-229's apply.py is
  drafted after this unit and uses the new form).
- **A11 (process):** every dispatch points at `.superpowers/sdd/run-common.md`. Four lanes in one checkout on disjoint
  files (Tasks 1 to 4). iOS: AFAF4221 booted once, `-parallel-testing-enabled NO`, `-collect-test-diagnostics never`.
  Gradle in the checkout `--no-watch-fs`; in a sabotage worktree `--no-daemon --no-watch-fs --no-build-cache`. The look
  (Task 6) is short: the screens whose copy changed, on both phones, throwaway devices, local emulators.
