# D-231: Less motion, an in-app switch that holds the app still

Date: 2026-10-19 (session 8, Opus). Status: design decided by the controller under the founder's delegation
(2026-10-18: "you make the decisions yourself, on evidence, and record each"); decisions 68 to 72 in
`docs/DECISIONS-TAKEN.md`, each overturnable in one line. To be committed as
`docs/superpowers/specs/2026-10-19-d231-less-motion-design.md` on branch `d231-less-motion`.

## 1. The problem

The theme art (D-214: Aurora's curtains and stars, Norse's runes and glow, Vardr Labs' signs), the done moments
(D-224), the settle celebration (D-221), the tab bar's springs and the bars' fades all move. On a lower-spec Android
phone that costs frame time, and some people simply want a still app without turning on the phone-wide Reduce Motion
(which changes every app) or battery saver (which throttles the phone). The app already has a designed still state:
D-214's still frame, shown under Reduce Motion or Low Power. It has no switch of its own.

## 2. What it does

One switch, **Less motion**, on Appearance, after Haptics, on both phones (twins). When on, the app behaves exactly as
it does under the phone's own Reduce Motion, everywhere the app reads it:
- the theme art stops ticking and shows its designed still frame (`MotionClock.shouldTick` false);
- the done moments and the settle celebration play their still versions (no burst);
- springs, slides and fades that already honour Reduce Motion snap instead (tab bar scale and add-button turn, the bar
  grounds and inline titles, list row slides, HomeShell's bar transition).

Off: nothing changes from today. The phone's own Reduce Motion and Low Power still hold the app still whatever the
switch says (the switch can only add stillness, never remove it).

Out of scope: the Aurora card blur's scroll cost on Android (decision 31; the switch does not fix it, the bake does,
item 4 of the session); haptics (their own switch); system transitions the app does not draw (navigation pushes, sheets).

## 3. The pieces

### 3.1 The setting (pure, both shells)
- `MotionSetting` (Swift `enum`, Kotlin `object`): `storageKey = "appearance.lessMotion"`, stored in UserDefaults /
  SharedPreferences beside the theme and haptics (ADR-0036's place for appearance preferences, never the vault).
- `parse(stored: Bool?, lowRam: Boolean) -> Bool`: the stored value when the person has set it; nothing stored reads as
  `lowRam` (decision 70).
- `reduced(system: Bool, lessMotion: Bool) -> Bool = system || lessMotion`: the one combine.

### 3.2 Low-RAM default (decision 70)
- `MotionSetting.lowRam(totalBytes: Long, lowRamFlag: Bool) -> Bool = lowRamFlag || totalBytes < 3 GiB`
  (3 * 1024^3 = 3_221_225_472, re-derived in the test, not read back).
- Android: `ActivityManager.isLowRamDevice()` and `ActivityManager.MemoryInfo.totalMem`. iOS:
  `ProcessInfo.processInfo.physicalMemory`, flag false (no such API). No iPhone that runs iOS 17 has under 3 GiB, so on
  iOS this is false in practice; the rule is the same on both shells.
- Read once per launch; never stored. The switch shows the effective value, so the person sees it on and can turn it
  off; a tap stores an explicit value that wins from then on.

### 3.3 One door per shell
- **Android**: `rememberMotionState()` (ThemeMotion.kt) is already the only reader of the system's inputs. Its `reduce`
  becomes `MotionSetting.reduced(animatorsOff(context), lessMotion)`, with `lessMotion` read from `Appearance` (the
  observable that holds the theme). Every existing reader (`AppRoot`, `DoneMoments`, `ListRows`, `OverviewTab`,
  `TabBar`, `Settled`, `SkiptiBar`) follows with no change.
- **iOS**: readers take `@Environment(\.accessibilityReduceMotion)` directly in seven files, and
  `UIAccessibility.isReduceMotionEnabled` once (DoneMoments). New environment value `\.motionReduced`, set once at the
  root by `AppearanceApplier` to `MotionSetting.reduced(system: accessibilityReduceMotion, lessMotion: state.lessMotion)`;
  every reader switches to it; the one imperative read uses `MotionSetting.reducedNow()` (the system flag OR the stored
  setting). `AppearanceState` gains `lessMotion`, loaded and updated like `textStep`.
- **A source guard on each shell** (decision 71): iOS: a test reads `ios/Skipti/App/*.swift` and fails if
  `accessibilityReduceMotion` or `isReduceMotionEnabled` appears outside `Appearance.swift` (the applier) and
  `ThemeMotion.swift` (`reducedNow`). Android: a JVM test fails if `animatorsOff(` is called outside ThemeMotion.kt.

### 3.4 The row (twins)
- `MotionRow`, the twin of `HapticsRow`: one `ListCard`, a `.toggle` `ListRow` with the new `sparkles` tile, title
  "Less motion", summary "Holds the theme art still". Placed after Haptics on Appearance (iOS `AccountHubScreen.swift`
  `.textSize` case after `HapticsRow()`; Android `AccountHubScreen.kt` after `HapticsRow(dark)`), 16 below it as the
  page's stack spaces siblings. No haptic on toggle. VoiceOver/TalkBack: one element, the switch, named by title and
  summary (as Haptics).
- Copy in `DoneCopy`'s neighbour `MotionCopy` (both shells, identical strings; sentence case; no dash).
- Lucide `sparkles` copied verbatim into both shells' glyph sets; NOTICE "twenty drawings" with the D-231 sentence;
  `scripts/licences.py` regenerated if it lists drawings.

## 4. Tests

- Pure (both shells, twins, same vectors): `parse` (nil + lowRam both ways, stored true/false beat lowRam), `lowRam`
  (flag, 3 GiB - 1, exactly 3 GiB, above), `reduced` (truth table, four rows).
- Android: `rememberMotionState`'s combine is pure (`MotionState(reduce = ...)` built through `MotionSetting.reduced`);
  a JVM test pins that `lessMotion` true gives `tick == false` with the system off.
- iOS: `AppearanceState` loads `lessMotion` from defaults (stored true, stored false, nothing stored with lowRam false).
- Source guards (3.3), each sabotaged once.
- Parity: the copy strings and the storage key equal on both shells (the existing parity test style: Android reads the
  Swift file).
- Sabotage (CLAUDE.md 4 rule 2: every new guard once): parse's nil branch, lowRam's bound, reduced's OR, the Android
  combine, the iOS root environment, each source guard, the row's storage key. About 15 rows per lane.

## 5. The look

Paired look on d214-look (iOS) and a throwaway run-look AVD (Android), `--emulators`, Aurora, Norse and Vardr Labs:
Appearance shows the row after Haptics, identical on both; switch on: the home art holds its still frame (two
screenshots seconds apart are pixel-identical in the art region), a settle shows the still celebration; switch off:
the art moves again. A fresh install on an AVD made with `hw.ramSize=2048` shows the switch on by default.

## 6. Decisions (to DECISIONS-TAKEN, after 67)

- **68** Less motion = the app's own Reduce Motion: every place that honours the phone's Reduce Motion honours the
  switch; it adds stillness, never removes it.
- **69** Its home: Appearance, after Haptics, a `HapticsRow` twin with a new Lucide `sparkles` tile; title "Less
  motion", summary "Holds the theme art still".
- **70** It turns on by itself on a low-RAM phone (Android's `isLowRamDevice` or under 3 GiB of RAM) until the person
  sets it; never by measured frame time (one line: RAM class is deterministic, testable and visible as the switch's
  state, while frame-time sampling would flip the app's look under the person after a measuring window and needs a
  sampler running in the very frames it judges).
- **71** One door per shell, held by a source guard (iOS `\.motionReduced`; Android `rememberMotionState`).
- **72** The switch does not touch the Aurora scroll blur (decision 31's bake does) and does not change haptics.

## 7. Changes the plan made against the code (accepted by the controller, 2026-10-19)

1. Android stores appearance keys unprefixed in the `skipti-appearance` prefs file (Haptics is `haptics`), so its key
   is `lessMotion`; iOS keeps `appearance.lessMotion`; the parity test checks iOS key = `appearance.` + Android key.
2. `MotionCopy` lives beside `MotionSetting` in ThemeMotion.swift / ThemeMotion.kt (not beside DoneCopy): `reducedNow()`
   is in ThemeMotion.swift anyway and Android's ThemeMotionTest already reads that file.
3. The iOS guard is tighter than 3.3: the phone's Reduce Motion is read once in Appearance.swift (the applier) and once
   in ThemeMotion.swift (`reducedNow`) only; ThemeMotion.swift's own art readers take `\.motionReduced` too.
4. `AppearanceState.stored()` takes its defaults and the low-RAM flag as arguments, so the load test never touches the
   test host's settings.
5. Lucide `sparkles` at `lucide-static` v1.59.0 (the version D-223 to D-225 used) was read by the controller
   (curl to stdout, nothing saved): four elements, the plan's reading exactly.

## 7. Changes the plan made against the code (accepted by the controller, 2026-10-19)

1. Android stores appearance keys unprefixed in the `skipti-appearance` prefs file (Haptics is `haptics`), so its key
   is `lessMotion`; iOS keeps `appearance.lessMotion`; the parity test checks iOS key = `appearance.` + Android key.
2. `MotionCopy` lives beside `MotionSetting` in ThemeMotion.swift / ThemeMotion.kt (not beside DoneCopy): `reducedNow()`
   is in ThemeMotion.swift anyway and Android's ThemeMotionTest already reads that file.
3. The iOS guard is tighter than 3.3: the phone's Reduce Motion is read once in Appearance.swift (the applier) and once
   in ThemeMotion.swift (`reducedNow`) only; ThemeMotion.swift's own art readers take `\.motionReduced` too.
4. `AppearanceState.stored()` takes its defaults and the low-RAM flag as arguments, so the load test never touches the
   test host's settings.
5. Lucide `sparkles` at `lucide-static` v1.59.0 (the version D-223 to D-225 used) was read by the controller
   (curl to stdout, nothing saved): four elements, the plan's reading exactly.
6. Decision 69 revised after the T2 review (2026-10-19): the summary is "Holds the art still, fewer animations" (the
   switch also drops the tab bar springs, the bar slide and the re-tap scroll animation, so "theme art" undersold it).
7. After the whole-branch review (2026-10-19): decision 68 reads "every place the app draws" (system-drawn dialogs,
   sheets and ripples follow only the phone's own setting; iOS GroupSheets' withAnimation never honoured Reduce Motion:
   residue). Decision 70's record is corrected: physicalMemory and totalMem report less than the nominal RAM, so phones
   sold as 3 GB (on iOS very likely iPhone XR and SE 2, which run iOS 17 and 18) start with Less motion ON; the rule is
   kept, since those are exactly the older phones it is for, and a person can turn it off. I1 (Android tab bar springs and
   re-tap scroll not gated) fixed in the fix wave.
8. The founder then chose the final summary by pop-up after seeing the line truncate in Norse: "Still art, fewer animations" (8a96b93). It supersedes both texts above.
