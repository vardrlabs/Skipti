# D-232 Aurora dark is the default for a new install: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **READ FIRST: the spec's key assumption holds only for the FIRST launch, and the plan amends decision 74 (controller to confirm).**
> On a brand-new install the vault file does not exist when the migration runs, and on every install that has launched
> before it does (evidence below). But the spec's rule ("no mode stored AND the vault exists: store `system`") has no
> "once": a D-232 install launched a SECOND time also has a vault and no stored mode, so it would flip from Dark to
> System on its second launch (on Android already on the first activity re-creation, because `onCreate` runs again).
> Smallest correct signal: a one-time settled mark beside the mode (`appearance.modeSettled` on iOS, `modeSettled` in
> `skipti-appearance` on Android), written by the first D-232 launch whatever it decides; the migration runs only while
> the mark is absent. No marker exists today on the launch path outside the vault (D-175's `onboarding/complete` /
> `app/launched` live INSIDE the vault, which the spec rightly refuses to read), so the mark is new.
> Second contradiction: on iOS `parse(nil)` is never reached, because all three `@AppStorage(AppearanceMode.storageKey)`
> readers declare `= AppearanceMode.system.rawValue` as their default (`Appearance.swift:393`,
> `AccountHubScreen.swift:640`, `AccountHubScreen.swift:805`). They become `String?` (T2), held by a guard.

**Goal:** A new install opens in Aurora dark on both phones; an install that ran before D-232 and never chose keeps following the phone; a stored choice always wins.

**Architecture:** One pure decision pair per shell beside `AppearanceMode` (`parse`: nil and unknown read Dark; `migrate(stored:vaultExists:)`: `system` only for an install that ran before and never chose). One wiring function per shell (`AppearanceDefault.settle` on iOS, `Appearance.settleDefault` on Android) runs once per install, guarded by a settled mark, as the FIRST statement of the launch (`SkiptiApp.init`, `MainActivity.onCreate` after `super`), before Firebase, the `--emulator-as` sign-in (which can open the vault) and any read of the mode. It checks only whether the vault file exists. A source guard on each shell holds the order and the single call site.

**Tech Stack:** SwiftUI (iOS 17+, XCTest hosted in the app), Jetpack Compose (minSdk 26, JUnit 4 JVM tests, no Robolectric), Python 3 stdlib for the sabotage runner. No new dependency.

**Spec:** `.drafts/d232/spec.md` (binding; decisions 73 to 76; the founder's call is the default itself). Branch: `d232-dark-default`, cut from main by the controller after D-231 merges. Implementers never switch branches.

## Evidence for the vault-existence verdict

- **iOS, a new install has no vault when `SkiptiApp.init` runs.** `init()` (`SkiptiApp.swift:51`) runs before `body`; the vault is first opened by `loadGate`, started from `.task { loadGate() }` (`SkiptiApp.swift:125`), which builds the path (`:378-384`) and calls `VaultRecovery.launch(store)` (`:394`) -> `store.kvFlag` -> `VaultGate.open` (`SkiptiApp.swift:419-420`), which provisions the database (`VaultGate.swift:4-5`; "A first run throws nothing (the store provisions itself)", `VaultRecoveryView.swift:73-74`). `LedgerStore.defaultStore()` creates only the `ledger` DIRECTORY (`LedgerStore.swift:1291-1297`), so the check is on the file `ledger-vault.db`, never the folder. Nothing in `init`'s stored properties (`Push.shared`, `RemoteSignOut.shared`, `AppLock.shared`, `CaptureWatch.shared`) opens the vault or reads the mode.
- **iOS, every install that launched before has the file.** The first launch's `loadGate` created it. A launch that ended on the vault-recovery screen had a file that would not open (`.unavailable` only when the open throws, which a first run does not). The recovery erase and the deliberate erase delete the files and re-run `loadGate` in the same process, which re-creates them. The path has been `Application Support/ledger/ledger-vault.db` since D-5x (`git log -S`).
- **Android, a new install has no vault when `onCreate` starts.** `MainActivity.onCreate` (`AppRoot.kt:99`); the vault is first opened by `AppRoot`'s `LaunchedEffect(vaultPath, erased, vaultRetry)` (`AppRoot.kt:445-451`) -> `VaultRecovery.launch(LAUNCHED_KEY) { LedgerStore(vaultPath) }` (`AppRoot.kt:430-431`) -> `rawKvGet`/`rawKvSet` (`AccountOwner.kt:391-395`) -> `VaultGate.open` (`VaultGate.kt:17-18`), at `File(context.filesDir, "ledger-vault.db")` (`AppRoot.kt:319-321`). One earlier opener exists: the Debug-only `--emulator-as` sign-in launches an IO coroutine with the vault path (`AppRoot.kt:114-117`) BEFORE `Appearance.load` (`AppRoot.kt:143`); the migration therefore goes first in `onCreate`, before it. Android backs up and transfers nothing (`allowBackup="false"`, `data_extraction_rules.xml` excludes every domain), so no restored vault appears on a fresh install.
- **Android, every install that launched before has the file**, by the same `LaunchedEffect`; the recovery screen's `Unavailable` means the file exists and will not open; the erase re-runs the effect (`vaultRetry++`, `LedgerErase.generation`).
- **Where the assumption fails:** the second launch of a D-232 install (both shells) and any activity re-creation (Android). Fixed by the settled mark (Deviation 1).

## Global Constraints

- **Decision 73 (the founder's call):** a new install opens in Aurora dark: with nothing stored, `parse` reads Dark. Light and System stay as choices on Appearance, unchanged; a stored choice always wins; the theme default stays Aurora.
- **Decision 74 (amended, see Deviation 1):** one launch migration, before the mode is first read and once per install: if no settled mark is stored, then if no mode is stored AND the vault file exists, store `system`; in every case store the mark. The vault check is file existence only, never a read of its contents.
- **Decision 75:** an unknown stored value reads Dark; `night` still reads Dark; `system`, `light`, `dark` read as themselves.
- **Decision 76:** ADR-0042 supersedes "light first" only as a reading of the default mode; light stays a designed, measured requirement.
- Storage: iOS UserDefaults keys `appearance.mode` (existing, unchanged) and `appearance.modeSettled` (new); Android `skipti-appearance` prefs keys `mode` (existing) and `modeSettled` (new). Never the vault. The parity test asserts iOS key == `"appearance." + Android key`.
- Twins: same behaviour and same golden tables on both shells.
- No em dash (U+2014) or en dash (U+2013) in code, copy or comments written by this unit.
- Commits: explicit paths only; `git diff --cached --stat` before every commit (refuse if the index holds a file you did not stage); subject ends `[skip ci]`; **no attribution of any kind** (no Co-Authored-By, no "Generated with"); after committing, `git log -1 --format=%B | grep -ciE 'co-authored|generated with'` must print 0.
- Never `git stash`, `git reset`, `git checkout -- <file>`, `git restore`; never switch branches; never `git add -A`, `git add .`, `git commit -a`.
- iOS tests: boot `AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51` once before a series (ignore "already booted"); every `xcodebuild test` passes `-collect-test-diagnostics never -parallel-testing-enabled NO`; shut it down when you finish. Android: `zsh -lic './gradlew ... --no-watch-fs'`; check result XML mtimes before quoting a tally.
- Sabotage: every new guard once (CLAUDE.md section 4 rule 2), in worktrees outside the checkout (`/private/tmp/skipti-d232-tN-ios|android`) through `.drafts/d232/sab.py`; anchors match exactly once or the run stops; a compile error is the wrong red; exactly the expected tests red; restore and `cmp`.
- No new Swift file (no pbxproj edit; CLAUDE.md 7.1 does not apply). No new Kotlin file. No core change, so no `make bindings`, no `make ios-core` / `android-core`.
- Check the machine before builds: `df -h /System/Volumes/Data` (under 30 GB free: stop and tell the controller), `pgrep -f qemu-system` empty, `ps -Ao pcpu,comm -r | head`.
- Stop every background process, Monitor or shell loop you start before you reply. Write your report to `.drafts/d232/tN/report.md` and reply in under 15 lines.

## Deviations from the spec (proposed; the controller confirms or overturns)

1. **A settled mark makes the migration run once** (decision 74 amended). Without it a D-232 install reads System from its second launch (the vault exists by then and nothing is stored). The pure decision keeps the spec's signature `migrate(stored:vaultExists:)`; the once-ness lives in the wiring (`guard !settled`), tested by a "second launch of a new install" vector and sabotaged on both shells. A tester who wiped preferences but kept the vault reads System (the spec's own stated, harmless residue).
2. **iOS: the three `@AppStorage(AppearanceMode.storageKey)` readers become `String?` with no default**, so `parse` decides the default (`Appearance.swift:393`, `AccountHubScreen.swift:640`, `AccountHubScreen.swift:805`). A source guard requires every reader of that key to be `String?`. Without this, iOS stays in System whatever `parse(nil)` says.
3. **ADR-0036 is amended too.** Its decision 1 reads "System (the default)"; the spec names only SPEC 11.1 and ADR-0038. T4 adds an "Amended (D-232)" line to ADR-0036, and ADR-0042 lists ADR-0011, 0023, 0036, 0038 and 0041.
4. **The migration runs before `FirebaseConfig.configure()` on both shells**, not merely "before the first parse": the `--emulator-as` sign-in (Debug) can open, and so create, the vault before `Appearance.load` on Android, and `EmulatorSignIn.signIn` follows `FirebaseConfig` on iOS.
5. **Android's wiring test uses a map-backed `SharedPreferences` fake** (no Robolectric in this project; `SharedPreferences` is an interface, so a JVM test can implement it). The `Context` overload's file check (`File(context.filesDir, VAULT_FILE).exists()`) is held by a source guard plus a guard that `VAULT_FILE` names AppRoot's file. iOS's wiring test runs the real `FileManager` check against a temp file in the hosted suite, and a path test pins `AppearanceDefault.defaultVaultPath() == LedgerStore.defaultStore().vaultPath`.
6. **Unchanged on purpose:** `AppearanceState.mode`'s initial `.system` (Swift) and `Appearance.mode`'s initial `SYSTEM` plus `setForTest`'s default (Kotlin). Both are overwritten from storage before the first frame (`AppearanceState.stored`, `Appearance.load` before `setContent`), and the JVM and hosted suites rely on the test seams' SYSTEM default.
7. **No new contrast test is owed.** Both suites already measure every pair in all five sets, Aurora dark included: iOS `AppearanceTests.testEveryPairClearsItsContrastFloorInEveryThemeAndMode` iterates `ThemeSet.allCases` (`AppearanceTests.swift:72-105`), Android `every_pair_clears_its_contrast_floor_in_every_theme_and_mode` (`AppearanceTest.kt:344`), plus the moving-art, stacked-curtain, E4, art and group-tone floors over every set. The first-run screens draw only tokens; the look (T5) checks by eye and reports any hard-coded colour.
8. **Open for the controller (not built here): the launch frame.** iOS's `UILaunchScreen` is an empty dict (`ios/Skipti/Info.plist:19-20`, so `systemBackground`) and Android's window theme is `Theme.Material.Light.NoActionBar` (`AndroidManifest.xml:49`). Both are drawn before SKIPTI reads anything, so on a LIGHT phone a new install shows a light launch frame before the dark app. T5 records it on video; a fix (a fixed dark launch background on both shells, which a Light chooser would then see) would be its own decision (proposed 77).

## File Structure

| File | Task | Responsibility |
|---|---|---|
| `ios/Skipti/App/Appearance.swift` | T1, T2 | `parse` dark default, `settledKey`, `migrate` (T1); `AppearanceDefault` (vault path, `settle`), the applier's reader `String?` (T2) |
| `android/app/src/main/java/com/vardrlabs/skipti/Appearance.kt` | T1, T3 | `parse` dark default, `SETTLED_KEY`, `migrate` (T1); `VAULT_FILE`, `settleDefault` (T3) |
| `ios/Skipti/App/SkiptiApp.swift` | T2 | `AppearanceDefault.settle()` first in `init()` |
| `ios/Skipti/App/AccountHubScreen.swift` | T2 | the two `appearanceRaw` readers become `String?` |
| `android/app/src/main/java/com/vardrlabs/skipti/AppRoot.kt` | T3 | `Appearance.settleDefault(applicationContext)` first in `onCreate` |
| `ios/SkiptiTests/AppearanceTests.swift` | T1, T2 | parse and migrate goldens (T1); launch wiring, order guard, path pin (T2) |
| `android/app/src/test/java/com/vardrlabs/skipti/AppearanceTest.kt` | T1, T3 | goldens and the iOS parity test (T1); launch wiring with a fake prefs, order guard (T3) |
| `docs/adr/0042-dark-by-default.md` (new), `docs/adr/0036-...md`, `docs/SPEC.md`, `CLAUDE.md`, `docs/superpowers/specs/2026-10-19-d232-dark-default-design.md` (new) | T4 | the record |
| `.drafts/d232/sab.py`, `.drafts/d232/tN/rows.py`, `.drafts/d232/look/` | T1 to T5 | runner, rows, look evidence (git-excluded) |

**Order:** T0 (controller), T1, then **T2 and T3 in parallel** (T2 only iOS files, T3 only Android files; disjoint, CLAUDE.md section 4 rule 4: neither stashes, resets, checks out or restores; each commits only its own paths and refuses if the index holds a foreign file; the controller dispatches the second committer only when `git diff --cached --name-only` is empty), T4 (docs; may run beside T2/T3 under the same rule, it touches no code), then T5 (the look), then the controller's close-out (full gate, BUILD-LOG, DECISIONS-TAKEN 73 to 76 with 74's amended wording, WORKLOG, merge, clean-up).

---

### Task 0 (controller, before any D-232 build): keep the pre-D-232 builds for the upgrade check

The D-231 full gate running now leaves the last pre-D-232 builds in the shared build dirs. T5's upgrade check installs them, so copy them out before anything rebuilds.

- [ ] **Step 1: Copy both builds and prove they are pre-D-232**

```bash
cd /Users/prithvi/Documents/Skipti
mkdir -p .drafts/d232/pre
git rev-parse HEAD > .drafts/d232/pre/HEAD.txt
cp -cR ios/build/Build/Products/Debug-iphonesimulator/Skipti.app .drafts/d232/pre/Skipti.app
cp android/app/build/outputs/apk/play/debug/app-play-debug.apk .drafts/d232/pre/app-play-debug.apk
stat -f '%Sm %N' .drafts/d232/pre/Skipti.app/Skipti.debug.dylib .drafts/d232/pre/app-play-debug.apk
strings .drafts/d232/pre/Skipti.app/Skipti.debug.dylib | grep -c 'appearance.modeSettled'
unzip -p .drafts/d232/pre/app-play-debug.apk 'classes*.dex' | strings | grep -c modeSettled
```

Expected: both mtimes after the D-231 gate started; both counts `0`.

- [ ] **Step 2: Cut the branch** after D-231 merges: `git switch -c d232-dark-default main` (controller only).

---

### Task 1: The pure decisions on both shells

**Files:**
- Modify: `ios/Skipti/App/Appearance.swift:69-74` (`AppearanceMode.parse`, plus two new members after it)
- Modify: `android/app/src/main/java/com/vardrlabs/skipti/Appearance.kt:55-58` (`AppearanceMode`'s companion)
- Test: `ios/SkiptiTests/AppearanceTests.swift` (edit lines 559-560; add two tests after `testStoredValuesThisBuildDoesNotKnowReadAsTheDefaults`)
- Test: `android/app/src/test/java/com/vardrlabs/skipti/AppearanceTest.kt` (edit lines 743-744; add three tests after `stored_values_this_build_does_not_know_read_as_the_defaults`)
- Create (git-excluded): `.drafts/d232/sab.py`, `.drafts/d232/t1/rows.py`

**Interfaces:**
- Consumes: nothing.
- Produces (Swift, `enum AppearanceMode`):
  - `static func parse(_ raw: String?) -> AppearanceMode` (nil, unknown, `night` -> `.dark`)
  - `static let settledKey = "appearance.modeSettled"`
  - `static func migrate(stored: String?, vaultExists: Bool) -> String?` (`"system"` iff `stored == nil && vaultExists`)
- Produces (Kotlin, `AppearanceMode.Companion`):
  - `fun parse(raw: String?): AppearanceMode` (null, unknown, `night` -> `DARK`)
  - `const val SETTLED_KEY = "modeSettled"`
  - `fun migrate(stored: String?, vaultExists: Boolean): String?`

- [ ] **Step 1: Write the failing parse tests (iOS)**

In `ios/SkiptiTests/AppearanceTests.swift`, replace these two lines inside `testStoredValuesThisBuildDoesNotKnowReadAsTheDefaults`:

```swift
        XCTAssertEqual(AppearanceMode.parse(nil), .system)
        XCTAssertEqual(AppearanceMode.parse("sepia"), .system)
```

with:

```swift
        XCTAssertEqual(AppearanceMode.parse(nil), .dark, "D-232: a new install opens in Dark")
        XCTAssertEqual(AppearanceMode.parse("sepia"), .dark, "D-232: an unknown word reads as the default, Dark")
```

Then add, directly after that test function's closing brace:

```swift

    // MARK: - D-232: Aurora dark is the default for a new install

    /// Decisions 73 and 75: nothing stored, or a word this build does not know, reads as Dark; the three words read as
    /// themselves; a stored `night` (before D-212) still reads as Dark. Android's twin: a_new_install_reads_dark.
    func testANewInstallReadsDark() {
        XCTAssertEqual(AppearanceMode.parse(nil), .dark, "nothing stored")
        XCTAssertEqual(AppearanceMode.parse("bogus"), .dark, "a word this build does not know")
        XCTAssertEqual(AppearanceMode.parse(""), .dark, "an empty word")
        XCTAssertEqual(AppearanceMode.parse("System"), .dark, "case matters: the words are stored lower case")
        XCTAssertEqual(AppearanceMode.parse("night"), .dark, "Night is retired: it reads as Dark")
        XCTAssertEqual(AppearanceMode.parse("system"), .system)
        XCTAssertEqual(AppearanceMode.parse("light"), .light)
        XCTAssertEqual(AppearanceMode.parse("dark"), .dark)
    }
```

- [ ] **Step 2: Run it to see it fail**

```bash
cd /Users/prithvi/Documents/Skipti
mkdir -p .drafts/d232/t1
xcrun simctl boot AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51 || true
xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti -derivedDataPath ios/build \
  -destination "platform=iOS Simulator,id=AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51" \
  -collect-test-diagnostics never -parallel-testing-enabled NO test \
  -only-testing:SkiptiTests/AppearanceTests > .drafts/d232/t1/ios-red.log 2>&1
grep -E "error:|' failed|Executed [0-9]+ tests?" .drafts/d232/t1/ios-red.log | tail -12
```

Expected: zero `error:` lines; `testANewInstallReadsDark` and `testStoredValuesThisBuildDoesNotKnowReadAsTheDefaults` failed; nothing else failed.

- [ ] **Step 3: Implement parse (iOS)**

In `ios/Skipti/App/Appearance.swift` replace:

```swift
    /// A stored `night` (a build before D-212) reads as Dark; anything else
    /// this build does not know is System.
    static func parse(_ raw: String?) -> AppearanceMode {
        if raw == "night" { return .dark }
        return raw.flatMap(AppearanceMode.init(rawValue:)) ?? .system
    }
```

with:

```swift
    /// D-232 (decisions 73, 75; ADR-0042): nothing stored is a new install, which opens in Dark. A stored `night` (a
    /// build before D-212) reads as Dark, and so does anything else this build does not know. A stored choice wins.
    static func parse(_ raw: String?) -> AppearanceMode {
        guard let raw else { return .dark }
        if raw == "night" { return .dark }
        return AppearanceMode(rawValue: raw) ?? .dark
    }
```

- [ ] **Step 4: Run it to see it pass**

Run the Step 2 command again, writing to `.drafts/d232/t1/ios-parse-green.log`. Expected: `Executed N tests, with 0 failures` for AppearanceTests, zero `error:` lines.

- [ ] **Step 5: Write the failing migration test (iOS)**

Add after `testANewInstallReadsDark`:

```swift

    /// Decision 74: the launch stores `system` only where nothing is stored and the vault file exists (the install has
    /// run before and followed the phone); a brand-new install (no vault yet) stores nothing and so reads Dark; a stored
    /// word, known or not, is never touched. The table is the twin of Android's a_launch_keeps_an_existing_install_on_system.
    func testTheLaunchMigrationKeepsAnExistingInstallOnSystem() {
        let table: [(stored: String?, vault: Bool, want: String?)] = [
            (nil, true, "system"), (nil, false, nil),
            ("light", true, nil), ("light", false, nil),
            ("dark", true, nil), ("system", true, nil),
            ("night", true, nil), ("bogus", true, nil),
        ]
        for row in table {
            XCTAssertEqual(AppearanceMode.migrate(stored: row.stored, vaultExists: row.vault), row.want,
                           "\(row.stored ?? "nil"), vault \(row.vault)")
        }
        XCTAssertEqual(AppearanceMode.settledKey, "appearance.modeSettled")
        XCTAssertEqual(AppearanceMode.storageKey, "appearance.mode", "the key every earlier build wrote")
    }
```

- [ ] **Step 6: Run it to see it fail**

Run the Step 2 command, writing to `.drafts/d232/t1/ios-migrate-red.log`. Expected: a compile error `type 'AppearanceMode' has no member 'migrate'` (and `'settledKey'`): the API does not exist yet.

- [ ] **Step 7: Implement migrate and the key (iOS)**

In `ios/Skipti/App/Appearance.swift`, directly after the new `parse` function's closing brace (still inside `enum AppearanceMode`), add:

```swift

    /// D-232 (decision 74): set once per install by `AppearanceDefault.settle`, at the first launch of a build with the
    /// dark default; from then on the launch migration never runs again.
    static let settledKey = "appearance.modeSettled"

    /// D-232 (decision 74): what the launch stores before the mode is first read. An install that ran before (its vault
    /// file exists) and never chose a mode followed the phone, and keeps following it: `system`. A brand-new install has
    /// no vault yet, so nothing is stored and it reads Dark. A stored word is never touched. nil: store nothing.
    static func migrate(stored: String?, vaultExists: Bool) -> String? {
        stored == nil && vaultExists ? AppearanceMode.system.rawValue : nil
    }
```

- [ ] **Step 8: Run it to see it pass**

Run the Step 2 command, writing to `.drafts/d232/t1/ios-green.log`. Expected: AppearanceTests `0 failures`, zero `error:` lines.

- [ ] **Step 9: Write the failing Android tests**

In `android/app/src/test/java/com/vardrlabs/skipti/AppearanceTest.kt`, replace inside `stored_values_this_build_does_not_know_read_as_the_defaults`:

```kotlin
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.parse(null))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.parse("sepia"))
```

with:

```kotlin
        assertEquals("D-232: a new install opens in Dark", AppearanceMode.DARK, AppearanceMode.parse(null))
        assertEquals("D-232: an unknown word reads as the default, Dark", AppearanceMode.DARK, AppearanceMode.parse("sepia"))
```

Then add directly after that test function's closing brace:

```kotlin

    /** D-232 (decisions 73, 75): nothing stored, or a word this build does not know, reads as Dark; the three words read
     *  as themselves; a stored `night` still reads as Dark. The twin of iOS's testANewInstallReadsDark. */
    @Test
    fun a_new_install_reads_dark() {
        assertEquals("nothing stored", AppearanceMode.DARK, AppearanceMode.parse(null))
        assertEquals("a word this build does not know", AppearanceMode.DARK, AppearanceMode.parse("bogus"))
        assertEquals("an empty word", AppearanceMode.DARK, AppearanceMode.parse(""))
        assertEquals("case matters: the words are stored lower case", AppearanceMode.DARK, AppearanceMode.parse("System"))
        assertEquals("Night is retired: it reads as Dark", AppearanceMode.DARK, AppearanceMode.parse("night"))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.parse("system"))
        assertEquals(AppearanceMode.LIGHT, AppearanceMode.parse("light"))
        assertEquals(AppearanceMode.DARK, AppearanceMode.parse("dark"))
    }

    /** D-232 (decision 74): the launch stores `system` only where nothing is stored and the vault file exists; a
     *  brand-new install stores nothing; a stored word is never touched. The twin of iOS's
     *  testTheLaunchMigrationKeepsAnExistingInstallOnSystem (same table). */
    @Test
    fun a_launch_keeps_an_existing_install_on_system() {
        val table = listOf<Triple<String?, Boolean, String?>>(
            Triple(null, true, "system"), Triple(null, false, null),
            Triple("light", true, null), Triple("light", false, null),
            Triple("dark", true, null), Triple("system", true, null),
            Triple("night", true, null), Triple("bogus", true, null),
        )
        for ((stored, vault, want) in table) assertEquals("$stored, vault $vault", want, AppearanceMode.migrate(stored, vault))
        assertEquals("modeSettled", AppearanceMode.SETTLED_KEY)
    }

    /** D-232: the twins. Appearance.swift's parse falls back to Dark on all three branches, its migrate stores `system` on
     *  the same condition, and its settled key is "appearance." + Android's (the existing key pattern). */
    @Test
    fun the_dark_default_matches_ios() {
        val swift = java.io.File("../../ios/Skipti/App/Appearance.swift").readText()
        fun body(anchor: String): String {
            assertTrue("Appearance.swift has `$anchor`", anchor in swift)
            return swift.substringAfter(anchor).substringBefore("\n    }\n").trim().lines().joinToString("\n") { it.trim() }
        }
        assertEquals(
            "guard let raw else { return .dark }\nif raw == \"night\" { return .dark }\nreturn AppearanceMode(rawValue: raw) ?? .dark",
            body("static func parse(_ raw: String?) -> AppearanceMode {"),
        )
        assertEquals(
            "stored == nil && vaultExists ? AppearanceMode.system.rawValue : nil",
            body("static func migrate(stored: String?, vaultExists: Bool) -> String? {"),
        )
        assertEquals(
            "appearance." + AppearanceMode.SETTLED_KEY,
            Regex("""static let settledKey = "([^"]*)"""").find(swift)?.groupValues?.get(1),
        )
    }
```

- [ ] **Step 10: Run them to see them fail**

```bash
cd /Users/prithvi/Documents/Skipti/android && zsh -lic './gradlew :app:testPlayDebugUnitTest --tests "com.vardrlabs.skipti.AppearanceTest" --no-watch-fs' > ../.drafts/d232/t1/jvm-red.log 2>&1; tail -30 ../.drafts/d232/t1/jvm-red.log
```

Expected: compilation fails with `Unresolved reference 'migrate'` / `'SETTLED_KEY'` (the API does not exist yet).

- [ ] **Step 11: Implement (Android)**

In `android/app/src/main/java/com/vardrlabs/skipti/Appearance.kt` replace:

```kotlin
    companion object {
        fun parse(raw: String?): AppearanceMode =
            if (raw == "night") DARK else entries.firstOrNull { it.stored == raw } ?: SYSTEM
    }
```

with:

```kotlin
    companion object {
        /** D-232 (decision 74): set once per install by [Appearance.settleDefault]; iOS's `appearance.modeSettled`. */
        const val SETTLED_KEY = "modeSettled"

        /** D-232 (decisions 73, 75; ADR-0042): nothing stored is a new install, which opens in Dark. A stored `night`
         *  (before D-212) reads as Dark, and so does anything else this build does not know. A stored choice wins. */
        fun parse(raw: String?): AppearanceMode {
            if (raw == null) return DARK
            if (raw == "night") return DARK
            return entries.firstOrNull { it.stored == raw } ?: DARK
        }

        /** D-232 (decision 74): what the launch stores before the mode is first read, the twin of iOS's
         *  `AppearanceMode.migrate`. An install that ran before (its vault file exists) and never chose keeps following
         *  the phone: `system`. A brand-new install stores nothing and reads Dark. A stored word is never touched. */
        fun migrate(stored: String?, vaultExists: Boolean): String? = if (stored == null && vaultExists) SYSTEM.stored else null
    }
```

- [ ] **Step 12: Run them to see them pass**

Run the Step 10 command, writing to `.drafts/d232/t1/jvm-green.log`, then:

```bash
cd /Users/prithvi/Documents/Skipti/android
grep -E 'tests=|failures=' app/build/test-results/testPlayDebugUnitTest/TEST-com.vardrlabs.skipti.AppearanceTest.xml | head -2
stat -f '%Sm' app/build/test-results/testPlayDebugUnitTest/TEST-com.vardrlabs.skipti.AppearanceTest.xml
```

Expected: `failures="0" errors="0"`, mtime from this run.

- [ ] **Step 13: Set up the sabotage runner and T1's rows**

```bash
cd /Users/prithvi/Documents/Skipti
sed 's/d231/d232/g' .drafts/d231/sab.py > .drafts/d232/sab.py
grep -c d231 .drafts/d232/sab.py   # expect 0
grep -c d232 .drafts/d232/sab.py   # expect 5
```

Write `.drafts/d232/t1/rows.py`:

```python
FILES = ['ios/Skipti/App/Appearance.swift', 'ios/SkiptiTests/AppearanceTests.swift',
         'android/app/src/main/java/com/vardrlabs/skipti/Appearance.kt',
         'android/app/src/test/java/com/vardrlabs/skipti/AppearanceTest.kt']
SW = 'ios/Skipti/App/Appearance.swift'
KT = 'android/app/src/main/java/com/vardrlabs/skipti/Appearance.kt'
IC = ['AppearanceTests']
KC = ['AppearanceTest']
I_PARSE = ['AppearanceTests.testANewInstallReadsDark', 'AppearanceTests.testStoredValuesThisBuildDoesNotKnowReadAsTheDefaults']
I_MIG = ['AppearanceTests.testTheLaunchMigrationKeepsAnExistingInstallOnSystem']
A_PARSE = ['AppearanceTest.a_new_install_reads_dark', 'AppearanceTest.stored_values_this_build_does_not_know_read_as_the_defaults']
A_MIG = ['AppearanceTest.a_launch_keeps_an_existing_install_on_system']
ROWS = {
    'I0': (None, [], [], IC),
    'I1': (SW, [('guard let raw else { return .dark }', 'guard let raw else { return .system }')], I_PARSE, IC),
    'I2': (SW, [('return AppearanceMode(rawValue: raw) ?? .dark', 'return AppearanceMode(rawValue: raw) ?? .system')], I_PARSE, IC),
    'I3': (SW, [('stored == nil && vaultExists ? AppearanceMode.system.rawValue : nil',
                 'vaultExists ? AppearanceMode.system.rawValue : nil')], I_MIG, IC),
    'I4': (SW, [('stored == nil && vaultExists ? AppearanceMode.system.rawValue : nil',
                 'stored == nil ? AppearanceMode.system.rawValue : nil')], I_MIG, IC),
    'I5': (SW, [('static let settledKey = "appearance.modeSettled"', 'static let settledKey = "appearance.modesettled"')], I_MIG, IC),
    'A0': (None, [], [], KC),
    'A1': (KT, [('if (raw == null) return DARK', 'if (raw == null) return SYSTEM')], A_PARSE, KC),
    'A2': (KT, [('return entries.firstOrNull { it.stored == raw } ?: DARK', 'return entries.firstOrNull { it.stored == raw } ?: SYSTEM')], A_PARSE, KC),
    'A3': (KT, [('if (stored == null && vaultExists) SYSTEM.stored else null', 'if (vaultExists) SYSTEM.stored else null')], A_MIG, KC),
    'A4': (KT, [('if (stored == null && vaultExists) SYSTEM.stored else null', 'if (stored == null) SYSTEM.stored else null')], A_MIG, KC),
    'A5': (KT, [('const val SETTLED_KEY = "modeSettled"', 'const val SETTLED_KEY = "modesettled"')],
           A_MIG + ['AppearanceTest.the_dark_default_matches_ios'], KC),
    # The parity test catches a drift on the iOS side, run in the Android lane.
    'A6': (SW, [('static let settledKey = "appearance.modeSettled"', 'static let settledKey = "appearance.modesettled"')],
           ['AppearanceTest.the_dark_default_matches_ios'], KC),
}
```

- [ ] **Step 14: Run both lanes in parallel**

```bash
cd /Users/prithvi/Documents/Skipti
python3 .drafts/d232/sab.py t1 setup ios && python3 .drafts/d232/sab.py t1 ios I0 I1 I2 I3 I4 I5 > .drafts/d232/t1/lane-ios.out 2>&1; python3 .drafts/d232/sab.py t1 teardown ios
python3 .drafts/d232/sab.py t1 setup android && python3 .drafts/d232/sab.py t1 android A0 A1 A2 A3 A4 A5 A6 > .drafts/d232/t1/lane-android.out 2>&1; python3 .drafts/d232/sab.py t1 teardown android
```

Run each line with `run_in_background`; while the iOS lane works, `xcrun simctl list devices booted` must list AFAF4221. Expected `.drafts/d232/t1/sab.out`: I0 and A0 green with zero compile errors; every other row zero compile errors and exactly its listed tests red; every restore `cmp`-clean. A row that is not exactly as expected: stop and report it by name.

- [ ] **Step 15: Commit**

```bash
cd /Users/prithvi/Documents/Skipti
git status --porcelain
git add ios/Skipti/App/Appearance.swift ios/SkiptiTests/AppearanceTests.swift \
  android/app/src/main/java/com/vardrlabs/skipti/Appearance.kt android/app/src/test/java/com/vardrlabs/skipti/AppearanceTest.kt
git diff --cached --stat   # exactly these 4 files, or refuse
git commit -m "D-232 T1: nothing stored reads Dark on both phones, and the pure launch migration (system only where the vault exists and nothing is stored) [skip ci]"
git log -1 --format=%B | grep -ciE 'co-authored|generated with'   # 0
```

---

### Task 2: iOS wiring (parallel with Task 3)

**Files:**
- Modify: `ios/Skipti/App/Appearance.swift` (the applier's reader at line ~393; append `enum AppearanceDefault` at the end of the file)
- Modify: `ios/Skipti/App/SkiptiApp.swift:50-52` (`init()`)
- Modify: `ios/Skipti/App/AccountHubScreen.swift:640` and `:805` (the two `appearanceRaw` readers)
- Test: `ios/SkiptiTests/AppearanceTests.swift` (add three tests after `testTheLaunchMigrationKeepsAnExistingInstallOnSystem`)
- Create (git-excluded): `.drafts/d232/t2/rows.py`

**Interfaces:**
- Consumes (T1): `AppearanceMode.parse(_:)`, `AppearanceMode.migrate(stored:vaultExists:)`, `AppearanceMode.settledKey`, `AppearanceMode.storageKey`.
- Consumes (existing): `AppearanceState.stored(_ defaults: UserDefaults, lowRam: Bool) -> AppearanceState`, `LedgerStore.defaultStore()`, `LedgerStore.vaultPath`.
- Produces: `enum AppearanceDefault { static func defaultVaultPath() -> String; static func settle(_ defaults: UserDefaults = .standard, vaultPath: String = AppearanceDefault.defaultVaultPath()) }`.

- [ ] **Step 1: Write the failing tests**

In `ios/SkiptiTests/AppearanceTests.swift`, after `testTheLaunchMigrationKeepsAnExistingInstallOnSystem`, add:

```swift

    // MARK: - D-232 Task 2: the launch settles the default once, before the mode is first read

    /// The launch's wiring over a throwaway suite and a throwaway vault file. A new install stores nothing (reads Dark)
    /// and stays that way at its second launch, when its vault exists; an install that ran before and never chose
    /// stores `system`; a stored choice is kept. Every first launch leaves the settled mark.
    func testTheLaunchSettlesTheDefaultOnce() throws {
        let name = "d232-settle-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let vault = dir.appendingPathComponent("ledger-vault.db").path

        // A brand-new install: no vault yet.
        AppearanceDefault.settle(defaults, vaultPath: vault)
        XCTAssertNil(defaults.string(forKey: AppearanceMode.storageKey), "a new install stores nothing")
        XCTAssertTrue(defaults.bool(forKey: AppearanceMode.settledKey), "the first launch leaves the mark")
        XCTAssertEqual(AppearanceState.stored(defaults, lowRam: false).mode, .dark, "and opens in Dark")
        // Its second launch: the first one made the vault.
        XCTAssertTrue(FileManager.default.createFile(atPath: vault, contents: Data([0])))
        AppearanceDefault.settle(defaults, vaultPath: vault)
        XCTAssertNil(defaults.string(forKey: AppearanceMode.storageKey), "the second launch of a new install is still Dark")

        // An install that ran before D-232 and never chose: its vault exists, nothing is settled.
        defaults.removeObject(forKey: AppearanceMode.settledKey)
        AppearanceDefault.settle(defaults, vaultPath: vault)
        XCTAssertEqual(defaults.string(forKey: AppearanceMode.storageKey), "system", "an existing install keeps following the phone")
        XCTAssertTrue(defaults.bool(forKey: AppearanceMode.settledKey))
        XCTAssertEqual(AppearanceState.stored(defaults, lowRam: false).mode, .system)

        // An install that chose Light before D-232: kept.
        defaults.set("light", forKey: AppearanceMode.storageKey)
        defaults.removeObject(forKey: AppearanceMode.settledKey)
        AppearanceDefault.settle(defaults, vaultPath: vault)
        XCTAssertEqual(defaults.string(forKey: AppearanceMode.storageKey), "light")
        XCTAssertTrue(defaults.bool(forKey: AppearanceMode.settledKey))
    }

    /// The migration checks the file the launch opens: the path `LedgerStore.defaultStore()` gives and `loadGate` builds.
    func testTheMigrationChecksTheLaunchsVaultFile() throws {
        XCTAssertEqual(AppearanceDefault.defaultVaultPath(), LedgerStore.defaultStore().vaultPath)
        let gate = try between(try appFile("SkiptiApp.swift"), from: "private func loadGate(retry: Bool = false) {",
                               to: "let launch = VaultRecovery.launch(store)")
        XCTAssertTrue(gate.contains(".appendingPathComponent(\"ledger\", isDirectory: true)"), gate)
        XCTAssertTrue(gate.contains("dir.appendingPathComponent(\"ledger-vault.db\").path"), gate)
    }

    /// Order matters (decision 74): settling the default is the first thing the app does, before Firebase, the
    /// emulator sign-in (which can open, and so create, the vault) and any read of the mode; it is called nowhere else;
    /// and every `@AppStorage` reader of the mode is `String?` (a non-optional default would read "system" and hide the
    /// dark default from `AppearanceMode.parse`).
    func testTheDefaultIsSettledFirstAtLaunch() throws {
        let app = try appFile("SkiptiApp.swift")
        let initBody = try between(app, from: "    init() {\n", to: "\n    }\n")
        let first = initBody.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty && !$0.hasPrefix("//") }
        XCTAssertEqual(first, "AppearanceDefault.settle()", "the first statement of SkiptiApp.init")
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let optional = try NSRegularExpression(pattern: #"@AppStorage\(AppearanceMode\.storageKey\) private var \w+: String\?\n"#)
        var calls = 0, readers = 0, optionalReaders = 0
        for file in try FileManager.default.contentsOfDirectory(atPath: dir.path) where file.hasSuffix(".swift") {
            let text = try String(contentsOf: dir.appendingPathComponent(file), encoding: .utf8)
            calls += occurrences(text, "AppearanceDefault.settle(")
            readers += occurrences(text, "@AppStorage(AppearanceMode.storageKey)")
            optionalReaders += optional.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text))
        }
        XCTAssertEqual(calls, 1, "settled once, from SkiptiApp.init")
        XCTAssertEqual(readers, 3, "the applier, the You tab and the Appearance page")
        XCTAssertEqual(optionalReaders, readers, "every reader of the mode is String?")
    }
```

- [ ] **Step 2: Run them to see them fail**

```bash
cd /Users/prithvi/Documents/Skipti
mkdir -p .drafts/d232/t2
xcrun simctl boot AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51 || true
xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti -derivedDataPath ios/build \
  -destination "platform=iOS Simulator,id=AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51" \
  -collect-test-diagnostics never -parallel-testing-enabled NO test \
  -only-testing:SkiptiTests/AppearanceTests > .drafts/d232/t2/ios-red.log 2>&1
grep -E "error:" .drafts/d232/t2/ios-red.log | sort -u | head
```

Expected: compile error `cannot find 'AppearanceDefault' in scope`.

- [ ] **Step 3: Implement `AppearanceDefault`**

Append to the end of `ios/Skipti/App/Appearance.swift` (after `extension View { func appliesStoredAppearance() ... }`):

```swift

/// D-232 (decisions 73, 74; ADR-0042): a new install opens in Dark; an install that ran before and never chose keeps
/// following the phone. `settle()` is the FIRST statement of `SkiptiApp.init` (AppearanceTests holds it there), before
/// Firebase, the emulator sign-in and any read of the mode, and it runs once per install: the settled mark stops it.
enum AppearanceDefault {
    /// The vault the launch opens (`SkiptiApp.loadGate`, `LedgerStore.defaultStore()`). Only whether the file exists is
    /// asked; nothing reads it, and nothing here creates the folder.
    static func defaultVaultPath() -> String {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ledger", isDirectory: true)
            .appendingPathComponent("ledger-vault.db").path
    }

    /// Once per install: stores what `AppearanceMode.migrate` asks for (`system` for an install that ran before and
    /// never chose), then the mark. A brand-new install stores only the mark, so its mode stays nil and reads Dark, and
    /// its later launches (when the vault exists) change nothing.
    static func settle(_ defaults: UserDefaults = .standard, vaultPath: String = AppearanceDefault.defaultVaultPath()) {
        guard !defaults.bool(forKey: AppearanceMode.settledKey) else { return }
        if let value = AppearanceMode.migrate(stored: defaults.string(forKey: AppearanceMode.storageKey),
                                              vaultExists: FileManager.default.fileExists(atPath: vaultPath)) {
            defaults.set(value, forKey: AppearanceMode.storageKey)
        }
        defaults.set(true, forKey: AppearanceMode.settledKey)
    }
}
```

- [ ] **Step 4: Make every reader of the mode `String?`**

In `ios/Skipti/App/Appearance.swift` (inside `struct AppearanceApplier`) replace:

```swift
    @AppStorage(AppearanceMode.storageKey) private var modeRaw = AppearanceMode.system.rawValue
```

with:

```swift
    /// D-232: nil until the person chooses (or the launch migration stores `system`); `AppearanceMode.parse` decides.
    @AppStorage(AppearanceMode.storageKey) private var modeRaw: String?
```

In `ios/Skipti/App/AccountHubScreen.swift` replace BOTH occurrences (lines ~640 and ~805; assert the count is 2 before and 0 after) of:

```swift
    @AppStorage(AppearanceMode.storageKey) private var appearanceRaw = AppearanceMode.system.rawValue
```

with:

```swift
    @AppStorage(AppearanceMode.storageKey) private var appearanceRaw: String?
```

```bash
cd /Users/prithvi/Documents/Skipti
grep -c '@AppStorage(AppearanceMode.storageKey) private var appearanceRaw = AppearanceMode.system.rawValue' ios/Skipti/App/AccountHubScreen.swift  # 0
grep -c '@AppStorage(AppearanceMode.storageKey) private var appearanceRaw: String?' ios/Skipti/App/AccountHubScreen.swift  # 2
```

The readers' uses compile unchanged: `AppearanceMode.parse(appearanceRaw)` and `.parse(modeRaw)` take `String?`; the Appearance page's binding `set: { appearanceRaw = $0.rawValue }` assigns a `String` to a `String?`; `.onChange(of: modeRaw)` works on an `Optional<String>`. BalanceLineTests' pin `"mode: AppearanceMode.parse(appearanceRaw)"` is unchanged.

- [ ] **Step 5: Settle first in `SkiptiApp.init`**

In `ios/Skipti/App/SkiptiApp.swift` replace:

```swift
    /// D-191: Firebase before anything can ask for the account.
    init() {
        FirebaseConfig.configure()
```

with:

```swift
    /// D-191: Firebase before anything can ask for the account. D-232: the appearance default is settled first of all,
    /// before anything can read the mode or open (and so create) the vault.
    init() {
        AppearanceDefault.settle()
        FirebaseConfig.configure()
```

- [ ] **Step 6: Run the tests to see them pass, with the neighbours that read these files**

```bash
cd /Users/prithvi/Documents/Skipti
xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti -derivedDataPath ios/build \
  -destination "platform=iOS Simulator,id=AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51" \
  -collect-test-diagnostics never -parallel-testing-enabled NO test \
  -only-testing:SkiptiTests/AppearanceTests -only-testing:SkiptiTests/BalanceLineTests \
  -only-testing:SkiptiTests/ThemeMotionTests -only-testing:SkiptiTests/TypeThemeTests > .drafts/d232/t2/ios-green.log 2>&1
grep -E "error:|' failed|Test Suite 'All tests'" -A1 .drafts/d232/t2/ios-green.log | tail -6
```

Expected: zero `error:` lines; the line after `Test Suite 'All tests'` reads `Executed N tests, with 0 failures` (CLAUDE.md 9.1's pattern).

- [ ] **Step 7: T2's sabotage rows**

Write `.drafts/d232/t2/rows.py`:

```python
FILES = ['ios/Skipti/App/Appearance.swift', 'ios/Skipti/App/SkiptiApp.swift',
         'ios/Skipti/App/AccountHubScreen.swift', 'ios/SkiptiTests/AppearanceTests.swift']
SW = 'ios/Skipti/App/Appearance.swift'
APP = 'ios/Skipti/App/SkiptiApp.swift'
IC = ['AppearanceTests']
ONCE = ['AppearanceTests.testTheLaunchSettlesTheDefaultOnce']
FIRST = ['AppearanceTests.testTheDefaultIsSettledFirstAtLaunch']
ROWS = {
    'J0': (None, [], [], IC),
    'J1': (SW, [('        guard !defaults.bool(forKey: AppearanceMode.settledKey) else { return }\n', '')], ONCE, IC),
    'J2': (SW, [('vaultExists: FileManager.default.fileExists(atPath: vaultPath))', 'vaultExists: false)')], ONCE, IC),
    'J3': (APP, [('        AppearanceDefault.settle()\n        FirebaseConfig.configure()',
                  '        FirebaseConfig.configure()\n        AppearanceDefault.settle()')], FIRST, IC),
    'J4': (SW, [('@AppStorage(AppearanceMode.storageKey) private var modeRaw: String?',
                 '@AppStorage(AppearanceMode.storageKey) private var modeRaw = AppearanceMode.system.rawValue')], FIRST, IC),
    'J5': (SW, [('.appendingPathComponent("ledger-vault.db").path', '.appendingPathComponent("ledger.db").path')],
           ['AppearanceTests.testTheMigrationChecksTheLaunchsVaultFile'], IC),
    'J6': (SW, [('        defaults.set(true, forKey: AppearanceMode.settledKey)\n', '')], ONCE, IC),
}
```

```bash
cd /Users/prithvi/Documents/Skipti
python3 .drafts/d232/sab.py t2 setup ios && python3 .drafts/d232/sab.py t2 ios J0 J1 J2 J3 J4 J5 J6 > .drafts/d232/t2/lane-ios.out 2>&1; python3 .drafts/d232/sab.py t2 teardown ios
```

Run in the background. Expected in `.drafts/d232/t2/sab.out`: J0 green; J1 to J6 zero compile errors, exactly the listed test red, restores `cmp`-clean. (J1: the second launch stores `system`. J2: the upgrade stores nothing. J6: the mark is never written.)

- [ ] **Step 8: Commit**

```bash
cd /Users/prithvi/Documents/Skipti
git diff --cached --name-only   # must be empty before you stage; otherwise refuse and report
git add ios/Skipti/App/Appearance.swift ios/Skipti/App/SkiptiApp.swift ios/Skipti/App/AccountHubScreen.swift ios/SkiptiTests/AppearanceTests.swift
git diff --cached --stat   # exactly these 4 files
git commit -m "D-232 T2: iOS settles the default first in SkiptiApp.init, once per install (a new install stays Dark, an earlier one keeps System); every mode reader is String? [skip ci]"
git log -1 --format=%B | grep -ciE 'co-authored|generated with'   # 0
xcrun simctl shutdown AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51
```

---

### Task 3: Android wiring (parallel with Task 2)

**Files:**
- Modify: `android/app/src/main/java/com/vardrlabs/skipti/Appearance.kt` (two imports; `VAULT_FILE` and `settleDefault` inside `object Appearance`)
- Modify: `android/app/src/main/java/com/vardrlabs/skipti/AppRoot.kt:99-101` (`onCreate`)
- Test: `android/app/src/test/java/com/vardrlabs/skipti/AppearanceTest.kt` (two tests after `the_dark_default_matches_ios`; a private `FakePrefs` class at the end of the file)
- Create (git-excluded): `.drafts/d232/t3/rows.py`

**Interfaces:**
- Consumes (T1): `AppearanceMode.parse(raw)`, `AppearanceMode.migrate(stored, vaultExists)`, `AppearanceMode.SETTLED_KEY`.
- Produces: `Appearance.settleDefault(context: Context)` and the seam `internal fun Appearance.settleDefault(prefs: SharedPreferences, vaultExists: Boolean)`.

- [ ] **Step 1: Write the failing tests**

In `android/app/src/test/java/com/vardrlabs/skipti/AppearanceTest.kt`, after `the_dark_default_matches_ios`, add:

```kotlin

    /** D-232 Task 3: the launch's wiring over a map-backed prefs (no Robolectric). A new install stores nothing (reads
     *  Dark) and stays that way at its second launch, when its vault exists; an install that ran before and never chose
     *  stores `system`; a stored choice is kept. Every first launch leaves the settled mark. iOS's twin:
     *  testTheLaunchSettlesTheDefaultOnce. */
    @Test
    fun the_launch_settles_the_default_once() {
        val fresh = FakePrefs()
        Appearance.settleDefault(fresh, vaultExists = false)
        assertEquals("a new install stores nothing", null, fresh.getString("mode", null))
        assertTrue("the first launch leaves the mark", fresh.getBoolean(AppearanceMode.SETTLED_KEY, false))
        assertEquals("and opens in Dark", AppearanceMode.DARK, AppearanceMode.parse(fresh.getString("mode", null)))
        Appearance.settleDefault(fresh, vaultExists = true)
        assertEquals("the second launch of a new install is still Dark", null, fresh.getString("mode", null))

        val upgraded = FakePrefs()
        Appearance.settleDefault(upgraded, vaultExists = true)
        assertEquals("an existing install keeps following the phone", "system", upgraded.getString("mode", null))
        assertTrue(upgraded.getBoolean(AppearanceMode.SETTLED_KEY, false))

        val chose = FakePrefs().also { it.map["mode"] = "light" }
        Appearance.settleDefault(chose, vaultExists = true)
        assertEquals("light", chose.getString("mode", null))
        assertTrue(chose.getBoolean(AppearanceMode.SETTLED_KEY, false))
    }

    /** Order matters (decision 74): settling the default is the first statement after super.onCreate, before Firebase,
     *  the `--emulator-as` sign-in (whose IO coroutine opens the vault) and Appearance.load; it is called nowhere else;
     *  and the Context overload asks only whether AppRoot's vault file exists. */
    @Test
    fun the_default_is_settled_first_at_launch() {
        fun kt(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()
        val root = kt("AppRoot.kt")
        val anchor = "override fun onCreate(savedInstanceState: Bundle?) {"
        assertTrue("AppRoot.kt has `$anchor`", anchor in root)
        val create = root.substringAfter(anchor).substringBefore("\n    }\n")
        val lines = create.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals("super.onCreate(savedInstanceState)", lines[0])
        assertEquals("Appearance.settleDefault(applicationContext)", lines[1])
        assertTrue("the mode is read after", create.indexOf("Appearance.load(applicationContext)") > create.indexOf("Appearance.settleDefault("))
        val calls = java.io.File("src/main/java/com/vardrlabs/skipti").listFiles()!!.filter { it.name.endsWith(".kt") }
            .sumOf { it.readText().split("Appearance.settleDefault(").size - 1 }
        assertEquals("settled once, from MainActivity.onCreate", 1, calls)
        val appearance = kt("Appearance.kt")
        assertTrue("the launch's vault file", "private const val VAULT_FILE = \"ledger-vault.db\"" in appearance)
        assertTrue("asked for existence only", "vaultExists = File(context.filesDir, VAULT_FILE).exists()" in appearance)
        assertTrue("the file AppRoot opens", "File(context.filesDir, \"ledger-vault.db\")" in root)
        assertTrue("the mode's key", "private const val KEY = \"mode\"" in appearance)
    }
```

At the very end of the file (after the class's closing brace), add:

```kotlin

/** D-232: a SharedPreferences held in a map, for the launch migration's JVM test (this project has no Robolectric;
 *  SharedPreferences is an interface, so a plain class can stand in). Only what the migration uses needs to work. */
private class FakePrefs : android.content.SharedPreferences {
    val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?): String? = (map[key] as String?) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = (map[key] as Int?) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as Long?) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as Float?) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as Boolean?) ?: defValue
    override fun contains(key: String?): Boolean = key in map
    override fun edit(): android.content.SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class Editor : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false

        private fun put(key: String?, value: Any?): android.content.SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?): android.content.SharedPreferences.Editor {
            removed += key!!
            return this
        }
        override fun clear(): android.content.SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearAll) map.clear()
            removed.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}
```

(If the compiler reports a nullability mismatch on an override against the SDK stubs' annotations, change only that parameter's or return's `?` to match the error's expected type; the bodies stay.)

- [ ] **Step 2: Run them to see them fail**

```bash
mkdir -p /Users/prithvi/Documents/Skipti/.drafts/d232/t3
cd /Users/prithvi/Documents/Skipti/android && zsh -lic './gradlew :app:testPlayDebugUnitTest --tests "com.vardrlabs.skipti.AppearanceTest" --no-watch-fs' > ../.drafts/d232/t3/jvm-red.log 2>&1; grep -E "^e: |FAILED|tests completed" ../.drafts/d232/t3/jvm-red.log | head
```

Expected: compile error `Unresolved reference 'settleDefault'` (only that; any FakePrefs error: fix per the note in Step 1 and run again).

- [ ] **Step 3: Implement `settleDefault`**

In `android/app/src/main/java/com/vardrlabs/skipti/Appearance.kt`:

Replace `import android.content.Context\n` with:

```kotlin
import android.content.Context
import android.content.SharedPreferences
```

Replace `import androidx.compose.ui.unit.Density\n` with:

```kotlin
import androidx.compose.ui.unit.Density
import java.io.File
```

(Assert each import anchor matches exactly once; `import androidx.compose.ui.unit.Density` is the last import.)

Inside `object Appearance`, replace:

```kotlin
    private const val TEXT = "textSize"
```

with:

```kotlin
    private const val TEXT = "textSize"

    /** D-232: the vault the launch opens (AppRoot's `File(context.filesDir, "ledger-vault.db")`); only its existence is
     *  asked. AppearanceTest holds the two names together. */
    private const val VAULT_FILE = "ledger-vault.db"

    /** D-232 (decisions 73, 74; ADR-0042): a new install opens in Dark; an install that ran before and never chose keeps
     *  following the phone. Called ONCE, as the first statement after super.onCreate in MainActivity (AppearanceTest
     *  holds it there): before Firebase, the `--emulator-as` sign-in (which opens the vault on an IO thread) and
     *  [load]. Runs once per install: the settled mark stops it, so the second launch of a new install, or an activity
     *  made again, changes nothing. */
    fun settleDefault(context: Context) = settleDefault(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        vaultExists = File(context.filesDir, VAULT_FILE).exists(),
    )

    /** The seam the JVM test drives: stores what [AppearanceMode.migrate] asks for (`system` for an install that ran
     *  before and never chose) with the mark, in one edit. A brand-new install stores only the mark. */
    internal fun settleDefault(prefs: SharedPreferences, vaultExists: Boolean) {
        if (prefs.getBoolean(AppearanceMode.SETTLED_KEY, false)) return
        val edit = prefs.edit().putBoolean(AppearanceMode.SETTLED_KEY, true)
        AppearanceMode.migrate(prefs.getString(KEY, null), vaultExists)?.let { edit.putString(KEY, it) }
        edit.apply()
    }
```

(`apply()` updates the in-memory map at once, so `load` in the same `onCreate` reads it; the platform flushes pending applies before the activity pauses. Same as every other writer in this object.)

- [ ] **Step 4: Settle first in `onCreate`**

In `android/app/src/main/java/com/vardrlabs/skipti/AppRoot.kt` replace:

```kotlin
        super.onCreate(savedInstanceState)
        VaultGate.appContextRef = applicationContext
```

with:

```kotlin
        super.onCreate(savedInstanceState)
        // D-232 (decision 74): the appearance default is settled first of all, before anything can read the mode or
        // open (and so create) the vault: the `--emulator-as` sign-in below opens it on an IO thread.
        Appearance.settleDefault(applicationContext)
        VaultGate.appContextRef = applicationContext
```

- [ ] **Step 5: Run the class, then the whole JVM suite**

```bash
cd /Users/prithvi/Documents/Skipti/android
zsh -lic './gradlew :app:testPlayDebugUnitTest --tests "com.vardrlabs.skipti.AppearanceTest" --no-watch-fs' > ../.drafts/d232/t3/jvm-green.log 2>&1; tail -5 ../.drafts/d232/t3/jvm-green.log
zsh -lic './gradlew :app:testPlayDebugUnitTest --no-watch-fs' > ../.drafts/d232/t3/suite.log 2>&1; tail -5 ../.drafts/d232/t3/suite.log
python3 - <<'EOF'
import glob, os, re, time
t = f = 0
newest = 0
for x in glob.glob('app/build/test-results/testPlayDebugUnitTest/*.xml'):
    s = open(x).read(2000)
    m = re.search(r'tests="(\d+)".*?failures="(\d+)"', s, re.S)
    t += int(m.group(1)); f += int(m.group(2)); newest = max(newest, os.path.getmtime(x))
print('tests', t, 'failures', f, 'newest', time.ctime(newest))
EOF
```

Expected: BUILD SUCCESSFUL both times; failures 0; the XML mtimes from this run.

- [ ] **Step 6: T3's sabotage rows**

Write `.drafts/d232/t3/rows.py`:

```python
FILES = ['android/app/src/main/java/com/vardrlabs/skipti/Appearance.kt',
         'android/app/src/main/java/com/vardrlabs/skipti/AppRoot.kt',
         'android/app/src/test/java/com/vardrlabs/skipti/AppearanceTest.kt']
KT = 'android/app/src/main/java/com/vardrlabs/skipti/Appearance.kt'
ROOT = 'android/app/src/main/java/com/vardrlabs/skipti/AppRoot.kt'
KC = ['AppearanceTest']
ONCE = ['AppearanceTest.the_launch_settles_the_default_once']
FIRST = ['AppearanceTest.the_default_is_settled_first_at_launch']
ROWS = {
    'B0': (None, [], [], KC),
    'B1': (KT, [('        if (prefs.getBoolean(AppearanceMode.SETTLED_KEY, false)) return\n', '')], ONCE, KC),
    'B2': (KT, [('val edit = prefs.edit().putBoolean(AppearanceMode.SETTLED_KEY, true)', 'val edit = prefs.edit()')], ONCE, KC),
    'B3': (KT, [('?.let { edit.putString(KEY, it) }', '?.let { }')], ONCE, KC),
    'B4': (ROOT, [('        Appearance.settleDefault(applicationContext)\n', ''),
                  ('        Appearance.load(applicationContext)\n',
                   '        Appearance.load(applicationContext)\n        Appearance.settleDefault(applicationContext)\n')], FIRST, KC),
    'B5': (KT, [('vaultExists = File(context.filesDir, VAULT_FILE).exists()', 'vaultExists = false')], FIRST, KC),
    'B6': (KT, [('private const val VAULT_FILE = "ledger-vault.db"', 'private const val VAULT_FILE = "ledger.db"')], FIRST, KC),
}
```

```bash
cd /Users/prithvi/Documents/Skipti
python3 .drafts/d232/sab.py t3 setup android && python3 .drafts/d232/sab.py t3 android B0 B1 B2 B3 B4 B5 B6 > .drafts/d232/t3/lane-android.out 2>&1; python3 .drafts/d232/sab.py t3 teardown android
```

Run in the background. Expected in `.drafts/d232/t3/sab.out`: B0 green; B1 to B6 zero compile errors, exactly the listed test red, restores `cmp`-clean. (B1: the second launch of a new install stores `system`. B2: no mark. B3: the upgrade stores nothing. B4: the mode is read before the default is settled.)

- [ ] **Step 7: Commit**

```bash
cd /Users/prithvi/Documents/Skipti
git diff --cached --name-only   # must be empty before you stage; otherwise refuse and report
git add android/app/src/main/java/com/vardrlabs/skipti/Appearance.kt android/app/src/main/java/com/vardrlabs/skipti/AppRoot.kt \
  android/app/src/test/java/com/vardrlabs/skipti/AppearanceTest.kt
git diff --cached --stat   # exactly these 3 files
git commit -m "D-232 T3: Android settles the default first in MainActivity.onCreate, once per install (a new install stays Dark, an earlier one keeps System) [skip ci]"
git log -1 --format=%B | grep -ciE 'co-authored|generated with'   # 0
```

---

### Task 4: The record: ADR-0042, SPEC 11.1, ADR-0036, CLAUDE.md, the spec

**Files:**
- Create: `docs/adr/0042-dark-by-default.md`
- Create: `docs/superpowers/specs/2026-10-19-d232-dark-default-design.md` (the spec plus an "As planned" section)
- Modify: `docs/SPEC.md` (a note before line 1482's `**As built (D-174, ...`)
- Modify: `docs/adr/0036-appearance-system-light-dark-night.md` (an amendment line after line 5's paragraph)
- Modify: `CLAUDE.md` (lines 78, 106, 613, 640)
- Create (git-excluded): `.drafts/d232/t4/apply.py`

**Interfaces:** none (docs only). The controller's close-out adds BUILD-LOG, WORKLOG and DECISIONS-TAKEN 73 to 76 (74 with the settled mark), not this task.

- [ ] **Step 1: Write ADR-0042**

Create `docs/adr/0042-dark-by-default.md`:

```markdown
# ADR-0042: Dark by default

Status: Accepted (founder-decided 2026-10-18, D-232; supersedes "light first" in ADR-0011, ADR-0023, ADR-0038 and
SPEC section 11.1 only as a reading of the default mode, and amends ADR-0036's decision 1)

## Context

Until D-232 a new install followed the phone (ADR-0036, decision 1: "System (the default)"), and the design rule was
"light first": build the light theme first and derive dark from it (ADR-0011), because "Dark-only is a posture; light
mode is a requirement" (SPEC section 11.1). ADR-0023 and ADR-0038 kept that principle; ADR-0041 kept it while adding
five token sets (Aurora light, Aurora dark, Norse, Vardr Labs light, Vardr Labs dark), each its own measured column.

On 2026-10-18 the founder decided: "Aurora DARK becomes the default for a new install, on both phones, before the
final release".

## Decision

1. **A new install opens in Aurora dark** (decision 73). With no mode stored, `AppearanceMode.parse` reads Dark on
   both shells. The theme default stays Aurora (ADR-0041). Light and System stay as choices on Appearance, unchanged,
   and a stored choice always wins.
2. **An install that has run before keeps what it shows** (decision 74). One launch migration, the first thing the
   app does and once per install: if no mode is stored and the vault file exists (the install has run before and
   followed the phone), it stores `system`; in every case it stores a settled mark (iOS `appearance.modeSettled`,
   Android `modeSettled` in the appearance preferences), so it never runs again and a new install's later launches
   stay Dark. It asks only whether the vault file exists; nothing reads the vault.
3. **An unknown stored value reads as Dark** (decision 75), the default; a stored `night` (before D-212) reads as Dark.
4. **What "light first" was for stands** (decision 76). Light mode is a requirement: a finance app is opened outdoors,
   at a restaurant table, one-handed, in a hurry. Light is designed and measured in every set that has one (Aurora
   light, Vardr Labs light), and every pair in every set is measured by the AppearanceTests on both shells; a light
   pair under its floor fails the gate as a dark one does. "Dark derived from light" no longer describes how the sets
   are made: since D-212 each set is its own column from `.drafts/d212/palette.py`, measured on its own. What changes
   is only which mode a person sees before they choose.

## Consequences

- A new person sees Aurora dark first, whatever the phone's own setting; System is one tap away on Appearance.
- Everyone who installed before D-232 sees no change: the migration stores the System they were already seeing.
- A person whose preferences are cleared while the vault stays (no current path does this) reads System, not Dark.
- The launch frame is drawn by the system before SKIPTI reads anything (iOS's launch screen, Android's starting
  window) and follows the phone: on a light phone a new install can show a light frame before the dark app. Changing
  it is its own decision.
- Night stays retired (ADR-0036 as amended by ADR-0041).
```

- [ ] **Step 2: Write the edit script for SPEC, ADR-0036 and CLAUDE.md**

Create `.drafts/d232/t4/apply.py`:

```python
#!/usr/bin/env python3
"""D-232 T4: asserted, exactly-once edits to SPEC 11.1, ADR-0036 and CLAUDE.md. Refuses on any anchor miss."""
import sys

ROOT = '/Users/prithvi/Documents/Skipti/'
EN = '\u2013'


def edit(path, old, new):
    text = open(ROOT + path, encoding='utf-8').read()
    n = text.count(old)
    if n != 1:
        sys.exit(f'REFUSED {path}: anchor found {n} times: {old[:80]!r}')
    open(ROOT + path, 'w', encoding='utf-8').write(text.replace(old, new))
    print('edited', path)


SPEC_NOTE = (
    '**As changed (D-232, ADR-0042, the founder, 2026-10-18):** a new install opens in Aurora dark: with no mode\n'
    'stored, the app reads Dark. Light and System stay as choices, a stored choice always wins, and an install that ran\n'
    'before D-232 keeps following the phone (a one-time launch migration stores System for it). "Light first" below,\n'
    'and in ADR-0011, ADR-0023 and ADR-0038, no longer names the default mode. What it was for stands: light mode is a\n'
    'requirement, designed and measured in every set that has one (Aurora light, Vardr Labs light), every pair measured\n'
    'by the AppearanceTests on both shells.\n\n'
)
edit('docs/SPEC.md', '**As built (D-174, ADR-0038, the founder, 2026-09-26):** after using the',
     SPEC_NOTE + '**As built (D-174, ADR-0038, the founder, 2026-09-26):** after using the')

edit('docs/adr/0036-appearance-system-light-dark-night.md',
     'Night returns as its own decision in a later\nversion.\n',
     'Night returns as its own decision in a later\nversion.\n\n'
     '**Amended 2026-10-19 (D-232, ADR-0042, the founder):** decision 1\'s default is Dark for a new install. System\n'
     'stays a choice, and an install that ran before D-232 keeps System: a one-time launch migration stores it where no\n'
     'mode is stored and the vault file exists.\n')

edit('CLAUDE.md', f'there are 41 ADRs (0001{EN}0041).', f'there are 42 ADRs (0001{EN}0042).')
edit('CLAUDE.md', f'0001{EN}0041, binding decisions (0040: Firebase replaces the relay; 0041: selectable themes)',
     f'0001{EN}0042, binding decisions (0040: Firebase replaces the relay; 0041: selectable themes; 0042: dark by default)')
edit('CLAUDE.md', '- **Themes (ADR-0041, D-212)**: Aurora (default), Norse (always dark), Vardr Labs; five token sets\n',
     '- **Themes (ADR-0041, D-212)**: Aurora (default), Norse (always dark), Vardr Labs; a new install opens in Aurora\n'
     '  dark (D-232, ADR-0042: nothing stored reads Dark; a once-per-install launch migration, the first statement of\n'
     '  `SkiptiApp.init` / `MainActivity.onCreate`, keeps an earlier install on System); five token sets\n')
edit('CLAUDE.md', '  light first, dark derived, every pair measured by the AppearanceTests on\n',
     '  light a designed, measured requirement (ADR-0042 retires "light first" only as the default mode), every\n'
     '  pair measured by the AppearanceTests on\n')
```

Before running, confirm the ADR-0036 anchor: `sed -n 5,8p docs/adr/0036-appearance-system-light-dark-night.md` must end its paragraph with `Night returns as its own decision in a later` / `version.` (it does at 2026-10-19: lines 7 and 8).

- [ ] **Step 3: Apply and check**

```bash
cd /Users/prithvi/Documents/Skipti
python3 .drafts/d232/t4/apply.py
grep -n 'As changed (D-232' docs/SPEC.md
grep -n 'Amended 2026-10-19 (D-232' docs/adr/0036-appearance-system-light-dark-night.md
grep -n '0042' CLAUDE.md
python3 - <<'EOF'
for p in ['docs/adr/0042-dark-by-default.md']:
    t = open(p, encoding='utf-8').read()
    print(p, 'em/en dashes:', t.count('\u2014') + t.count('\u2013'))
EOF
```

Expected: four `edited` lines (six edits, four files names repeated), each grep finds its line, ADR-0042 has 0 dashes.

- [ ] **Step 4: Commit the spec as built**

```bash
cd /Users/prithvi/Documents/Skipti
cp .drafts/d232/spec.md docs/superpowers/specs/2026-10-19-d232-dark-default-design.md
cat >> docs/superpowers/specs/2026-10-19-d232-dark-default-design.md <<'EOF'

## 6. As planned (`.drafts/d232/plan.md`)

- Decision 74 runs once per install: a settled mark (`appearance.modeSettled` / `modeSettled`) beside the mode stops
  the migration after the first D-232 launch, so the second launch of a new install (its vault exists by then) stays
  Dark. The pure decision keeps the signature above; the once-ness is the wiring's, tested and sabotaged on both
  shells.
- iOS's three `@AppStorage(AppearanceMode.storageKey)` readers became `String?`, so `parse` decides the default (they
  defaulted to "system" and hid it). A guard keeps every reader optional.
- The migration is the first statement of `SkiptiApp.init` and of `MainActivity.onCreate` (after `super`), before
  Firebase and the `--emulator-as` sign-in, which can open the vault.
- ADR-0036's decision 1 ("System (the default)") is amended too.
EOF
git diff --cached --name-only   # must be empty before you stage
git add docs/adr/0042-dark-by-default.md docs/adr/0036-appearance-system-light-dark-night.md docs/SPEC.md CLAUDE.md \
  docs/superpowers/specs/2026-10-19-d232-dark-default-design.md
git diff --cached --stat   # exactly these 5 files
git commit -m "D-232 T4: ADR-0042 dark by default; SPEC 11.1 and ADR-0036 notes; CLAUDE.md; the spec as planned [skip ci]"
git log -1 --format=%B | grep -ciE 'co-authored|generated with'   # 0
```

---

### Task 5: The paired look (both phones, throwaway devices only)

**Files:** evidence only, under `.drafts/d232/look/` (git-excluded): `pairs/`, `video/`, `prefs/`, `report.md`.

**Interfaces:** consumes the T0 pre-D-232 builds (`.drafts/d232/pre/`) and a fresh D-232 build of each shell.

Devices: iOS **d214-look** only (`UDID=$(xcrun simctl list devices | grep BDC0AE93 | grep -oE '[0-9A-F-]{36}')`), never AFAF4221, EB725812 or skipti-shots. Android **run-look** only (`~/.android/avd/run-look.avd/config.ini` must hold `hw.ramSize=4096`: set it with an asserted edit before booting, so D-231's low-memory rule does not turn Less motion on), never skipti-shots, skipti-smoke or Pixel_10_Pro_XL. Every launch passes `--emulators` (no sign-in happens in this look, so the Firebase emulators need not run; the flag keeps a Debug build off production). The phone itself is set to LIGHT for every check: on a dark phone, System and Dark look the same and the check proves nothing.

- [ ] **Step 1: Check the machine and build D-232**

```bash
cd /Users/prithvi/Documents/Skipti
df -h /System/Volumes/Data; pgrep -f qemu-system; ps -Ao pcpu,comm -r | head -5
git log --oneline -1   # the D-232 tip
UDID=$(xcrun simctl list devices | grep BDC0AE93 | grep -oE '[0-9A-F-]{36}'); echo $UDID
xcodebuild -project ios/Skipti.xcodeproj -scheme Skipti -configuration Debug \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath .drafts/d232/look/dd build > .drafts/d232/look/ios-build.log 2>&1; tail -2 .drafts/d232/look/ios-build.log
strings .drafts/d232/look/dd/Build/Products/Debug-iphonesimulator/Skipti.app/Skipti.debug.dylib | grep -c 'appearance.modeSettled'   # 1 or more
cd android && zsh -lic './gradlew :app:assemblePlayDebug --no-watch-fs' > ../.drafts/d232/look/android-build.log 2>&1; tail -2 ../.drafts/d232/look/android-build.log
unzip -p app/build/outputs/apk/play/debug/app-play-debug.apk 'classes*.dex' | strings | grep -c modeSettled   # 1 or more
```

(No core change in D-232, so the packaged Rust library is current.)

- [ ] **Step 2: Boot both devices in light mode**

```bash
xcrun simctl boot $UDID; xcrun simctl ui $UDID appearance light
~/Library/Android/sdk/emulator/emulator -avd run-look -no-window -gpu host -no-audio -no-boot-anim &   # run_in_background
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB wait-for-device; until [ "$($ADB shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
$ADB shell cmd uimode night no
```

(Kill the boot-wait loop if it is still running when you reply.)

- [ ] **Step 3: Upgrade check (pre-D-232 then D-232 over it): still System**

iOS:

```bash
xcrun simctl uninstall $UDID com.vardrlabs.skipti
xcrun simctl install $UDID .drafts/d232/pre/Skipti.app
xcrun simctl launch $UDID com.vardrlabs.skipti --emulators
```

Wait until Overview shows (the first launch made the vault); screenshot `pairs/up-1-pre-ios.png` (light). Then:

```bash
xcrun simctl terminate $UDID com.vardrlabs.skipti
xcrun simctl install $UDID .drafts/d232/look/dd/Build/Products/Debug-iphonesimulator/Skipti.app
xcrun simctl launch $UDID com.vardrlabs.skipti --emulators
plutil -p "$(xcrun simctl get_app_container $UDID com.vardrlabs.skipti data)/Library/Preferences/com.vardrlabs.skipti.plist" | grep appearance > .drafts/d232/look/prefs/up-ios.txt
```

Expected: Overview still light (`pairs/up-2-d232-ios.png`); You shows "Aurora" and "System" in Appearance's summary; Appearance shows System selected (`pairs/up-3-appearance-ios.png`); the plist holds `"appearance.mode" => "system"` and `"appearance.modeSettled" => 1`.

Android (same steps):

```bash
$ADB uninstall com.vardrlabs.skipti
$ADB install .drafts/d232/pre/app-play-debug.apk
$ADB shell am start -n com.vardrlabs.skipti/.MainActivity --es args --emulators
# wait for Overview; screenshot: $ADB exec-out screencap -p > .drafts/d232/look/pairs/up-1-pre-android.png
$ADB shell am force-stop com.vardrlabs.skipti
$ADB install -r android/app/build/outputs/apk/play/debug/app-play-debug.apk
$ADB shell am start -n com.vardrlabs.skipti/.MainActivity --es args --emulators
$ADB shell run-as com.vardrlabs.skipti cat shared_prefs/skipti-appearance.xml > .drafts/d232/look/prefs/up-android.txt
```

Expected: the same three screens as iOS (light, "System", System selected); the prefs file holds `mode` = `system` and `modeSettled` = `true`.

- [ ] **Step 4: Fresh install: Aurora dark on every first-run screen**

iOS: `xcrun simctl uninstall $UDID com.vardrlabs.skipti`, start a recording (`xcrun simctl io $UDID recordVideo .drafts/d232/look/video/fresh-ios.mov`, run in the background; stop it with Ctrl-C equivalent `kill -INT` after the first screen settles), install the D-232 app, `xcrun simctl launch $UDID com.vardrlabs.skipti --emulators`. Android: `$ADB uninstall com.vardrlabs.skipti`, `$ADB shell screenrecord --time-limit 10 /sdcard/fresh.mp4 &` then install and `am start ... --es args --emulators`, pull the video to `video/fresh-android.mp4`.

Screenshot each, iOS then Android, named `pairs/fresh-NN-<screen>-ios.png` / `-android.png`:
1. the first screen the launch shows (D-175: straight into the app), 2. the empty Overview, 3. Add expense (the + button), 4. the empty Expenses list, 5. People, 6. You (Appearance's summary reads "Aurora" and "Dark"), 7. Appearance (Dark selected in "Light or dark").

Expected: every screen Aurora dark on a light phone, nothing unreadable; the prefs hold `appearance.modeSettled` / `modeSettled` and NO mode key.

- [ ] **Step 5: The second launch stays Dark (the settled mark's whole point)**

iOS: `xcrun simctl terminate`, `xcrun simctl launch $UDID com.vardrlabs.skipti --emulators`: still dark (`pairs/fresh-08-relaunch-ios.png`); prefs still without `appearance.mode`. Android: `am force-stop`, start again: still dark; then rotate once (`$ADB shell settings put system accelerometer_rotation 0; $ADB shell settings put system user_rotation 1`, screenshot, `user_rotation 0`): still dark (the activity was made again). Prefs still without `mode`.

- [ ] **Step 6: Light check**

On each phone open Appearance, tap Light: the app turns light (`pairs/light-1-*.png`); relaunch: still light (`pairs/light-2-*.png`); prefs hold `mode` = `light`. Tap System: light (the phone is light), then Dark: dark. Leave it on Dark.

- [ ] **Step 7: The launch frame (record only)**

From `video/fresh-ios.mov` and `video/fresh-android.mp4`, note whether a light frame (iOS launch screen, Android starting window) shows before the dark app on the light phone, and for how long. Record the finding in the report; change nothing (Deviation 8).

- [ ] **Step 8: Contrast by eye**

On the dark first-run screens, report any text, icon or control that is hard to read, with its screenshot. No new pair is expected (Deviation 7); a finding goes to the controller, it is not fixed here.

- [ ] **Step 9: Clean up and report**

```bash
xcrun simctl uninstall $UDID com.vardrlabs.skipti; xcrun simctl shutdown $UDID
$ADB uninstall com.vardrlabs.skipti; $ADB emu kill; sleep 5; pgrep -f qemu-system   # nothing
rm -rf .drafts/d232/look/dd
```

Keep the screenshots, videos, prefs dumps and logs. Write `.drafts/d232/look/report.md`: one line per step with pass or the finding and its screenshot pair; the launch-frame finding; the paths. Kill every background recording or wait loop you started. Reply in under 15 lines.

---

## Self-review

- **Spec coverage:** decision 73 (parse nil -> Dark): T1 Steps 1-4, 9-12. Decision 74 (the migration, its wiring at launch, before the first parse): T1 Steps 5-8 (pure), T2 and T3 (wiring, order guards, hosted and JVM tests), amended with the settled mark (Deviation 1). Decision 75 (unknown -> Dark, `night` -> Dark): T1 tests. Decision 76 (ADR-0042, SPEC 11.1 note): T4, plus ADR-0036 (Deviation 3) and CLAUDE.md. Section 3 checks: contrast floors already measured (Deviation 7) and checked by eye (T5 Step 8); first-run screens, the Light check and the upgrade check: T5 Steps 3-6; the second-launch check added (T5 Step 5). Section 4 tests: parse vectors and the four-plus migration vectors on both shells (T1), wiring tests (T2 Step 1, T3 Step 1), order guards (same), sabotage of parse's nil branch (I1, A1), the unknown branch (I2, A2), the migration's vault condition (I4, A4), its nil condition (I3, A3), the launch order (J3, B4), plus the mark (J1, J6, B1, B2), the readers (J4) and the vault path (J5, B5, B6): 6 + 7 + 7 + 7 rows.
- **Placeholder scan:** none; every code step carries its code, every run its command and expected result.
- **Type consistency:** `AppearanceMode.migrate(stored:vaultExists:) -> String?` (Swift) / `migrate(stored: String?, vaultExists: Boolean): String?` (Kotlin); `AppearanceMode.settledKey = "appearance.modeSettled"` / `AppearanceMode.SETTLED_KEY = "modeSettled"`; `AppearanceDefault.settle(_:vaultPath:)` and `AppearanceDefault.defaultVaultPath()`; `Appearance.settleDefault(context)` and `Appearance.settleDefault(prefs, vaultExists)`; test names match the rows files.
