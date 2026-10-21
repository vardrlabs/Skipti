# D-224 E3 "Done moments and haptics" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every action that finishes says so, the same on both phones (the founder's item 12, 2026-10-13: "a nice
animation showing the confirmation of that action like an animated check mark"). Two sizes from one component: the
**big** moment (a check that draws itself, with the theme's ring; Norse: the Futhark turning, D-221's rune ring, Q12 a)
for once-only moments, and the **small** moment (a bar with a check and one line, the Look Book's toast) for everyday
ones. Haptics on both shells (light on a tab change, success on every done moment, warning before anything
destructive) behind one Haptics switch on Appearance.

**Architecture:** Pure decisions and value tables first on both shells (haptic kinds and their platform constants, the
switch's default, the moment model and its copy, the check's and the toast's poses as pure functions of time, the
toast's place), compared across shells by parity tests that read the Swift files. One `DoneCenter` per shell holds the
current moment (no queue: a new one replaces the old at once); one host at the shell's root draws it over the content,
never taking a touch. Motion through D-214's engine: the check and the toast read the shared 30 fps grid
(`ArtSchedule` / `FrameGate`) and draw pure poses; under Reduce Motion or Low Power they draw the still end state.
Haptics only through the platform APIs (UIKit feedback generators; Android `View.performHapticFeedback` with
`HapticFeedbackConstants`), no dependency, no permission. Call sites last, per shell, in parallel lanes.

**Tech Stack:** SwiftUI / UIKit (iOS 17+), Jetpack Compose (Android, minSdk 26), XCTest, JUnit.

**Builds on D-219 C1, D-220 C2, D-221 E4, D-222 S1 and D-223 Home, all merged before E3 starts** (`.drafts/d219/plan.md`
to `.drafts/d223/plan.md`). E3 reuses and never redraws: E4's `RuneRing` view and `RuneRing.lit`, `CelebrationOverlay`,
`CelebrationTiming`, `SettledBurst.ease` (generalised here, its goldens unchanged); S1's `SettingsTile`, `SettingsRow`
geometry (`settingsTile`, `settingsGlyph`, `rowGap`, `rowPadV`, `listPadH`, `listPadV`) and the Appearance page layout;
C2's Lucide `check`, `ThemeArt.panelShape`, `Separator.text` / `Separator.annotated`, `GroupsCopy.detailParts`; C1's
`AddButtonGeometry` (faces centred 50 above the bar row's bottom, the Norse diamond's top 95.25) and `LucideStroke`.
`ThemeArt.metrics` has 78 keys after D-223; E3 adds 16 (94). `TypeScale` ends with D-223's `emptyTitle`; E3 adds
`toast`. NOTICE's Lucide entry is at seventeen drawings after D-223; E3 adds `vibrate` (eighteen). If any of those
units has not merged, recount from the tree and say so in the report.

Values come from `.drafts/d224/art.py` (D-223's art.py extended with sections 37 to 40; output `art.out`, "Floors
failing: 0") and `.drafts/d224/motion.py` (D-221's motion.py extended with part 6; output `motion.out`). Both were
written and run by the planner. Never retype a value that is not in this plan or those outputs; extend the generator
for a new one and re-run it.

**Rows taken (audit `.superpowers/sdd/lookbook-audit/consolidated.md`, unit E3):**
- L48 the Haptics row: "Haptics / A tap when something is done" on Appearance (LB 396), with a switch.
- L49 the big done moment: the self-drawing check (LB 178-192), Norse's turning Futhark (LB 246-247) through E4's
  `RuneRing`; today's one success screen (`SignedInConfirmation`: iOS SF `checkmark.circle.fill`, Android a 72 dp
  circle with a drawn tick) replaced on both shells by the same check (the TWIN closes).
- L50 the small done moment: the toast (LB 193-194), radius 14 scaled, hairline edge, the `owing` dot with a check.
- **E3's own scope (feedback-round spec "Done moments, celebrations, milestones, haptics" and "Units": "the shared
  component in two sizes, wired to every action the audit found silent")**: the friction audit's seven silent actions
  (creating a group, changing people, a group picture, a username, signing out, your picture, settling up) and the
  spec's small list (expense added, person added, picture or username saved), the big list (first group, signed in, a
  group settled, a ledger erased), and the three haptics.

**L-D217 rows taken:** none are routed to E3.

**Left out, and why:**
- The settled celebration itself (burst, dim, words): E4 (D-221) built it. E3 only adds its success haptic and clears a
  toast when it starts (Design 9).
- Milestone haptic: none (Design 9: the card shows on every visit for 14 days, a haptic each time would be noise).
- Settle up's Undo (friction audit row 7): a new record-reversal feature, not a done moment; residue for the founder.
- The Undo bar after deleting an expense keeps its look and behaviour (it takes a touch, so it cannot be the
  pass-through toast); residue: restyle it to the toast's shape later.
- Toasts for role changes, invite link copied, keep account, sign out one phone, notifications on, app lock and
  currency choices: each screen already shows the new state in place; residue.
- A drawn switch identical on both phones: the Haptics row uses the switch each shell draws today (iOS `Toggle`,
  Android `Switch` with `switchColors`), as Security does; S2 owns switches (Q15 a). Residue.

## Design

1. **Haptic kinds and their platform calls.** `HapticKind { light, success, warning }` on both shells.
   - iOS: light `UIImpactFeedbackGenerator(style: .light).impactOccurred()`; success
     `UINotificationFeedbackGenerator().notificationOccurred(.success)`; warning `... (.warning)`. iOS itself silences
     them when the phone's System Haptics setting is off.
   - Android: `HapticKind.constant(kind, sdkInt)` returns the SDK's named constant (never an integer literal):
     light `CLOCK_TICK` (API 21); success `CONFIRM` on API 30 and above, else `CONTEXT_CLICK` (23); warning `REJECT` on
     30 and above, else `LONG_PRESS`. Verified against the installed SDK's `platforms/android-37.0/data/api-versions.xml`
     (CONFIRM since 30, REJECT since 30, CONTEXT_CLICK since 23, CLOCK_TICK since 21). Played with
     `view.performHapticFeedback(constant)` and NO flags, so the phone's own touch-feedback setting is honoured.
     **No `VIBRATE` permission and no `Vibrator`:** `performHapticFeedback` needs neither, and
     `scripts/check-play-manifest.sh`'s allowlist stays as it is.
   - One door per shell: `Haptics.play(_ kind:)` (iOS, `@MainActor`) / `Haptics.play(view: View, kind: HapticKind)`
     (Android). Nothing else in the app touches a feedback generator or `performHapticFeedback` (source guard).
2. **The Haptics switch (L48).** Stored with the other appearance preferences: iOS `UserDefaults` key
   `appearance.haptics` (Bool; `HapticsSetting.storageKey`), Android `SharedPreferences("skipti-appearance")` key
   `haptics` with `Appearance.haptics` snapshot state and `Appearance.setHaptics(context, on)`. `HapticsSetting.parse(nil)
   == true`. **DECIDED (controller, for the founder): on by default**, because both platforms ship haptics on and keep the
   system switch as the master (Apple HIG "Playing haptics": use the system patterns, people can turn haptics off in
   Settings; Material: confirmation haptics through `CONFIRM` / `REJECT`, honouring the system setting), the Look Book's
   row describes a feature that is working ("A tap when something is done"), and the friction audit named haptics the
   cheapest "feels alive" fix the founder approved. `Haptics.plays(kind, enabled)` is `enabled` for every kind (the
   switch silences all three). Reduce Motion never silences a haptic (spec: "haptics stay"). Turning the switch ON
   plays one success haptic, so the person feels what they turned on; turning it off plays nothing.
3. **When each haptic plays.**
   - **light:** a tab tap that SWITCHES tabs (`TabTap.decide` returned `switchTo`). Not on a re-tap (back to root, to
     top), not on a tab changed by code, not on the + (it opens Add; Add's own done moment plays success).
   - **success:** every done moment (Design 5 to 9). Small: when the toast appears. Big: when the check lands, at the
     pop's start (`CheckMotion.popAt` 0.7 s), or at once in still mode (`DoneHapticTiming.delay(big:still:)`: small 0,
     big still 0, big moving 0.7). Celebration: when it starts. `HapticGate.allows(lastSuccess:now:)`: a success within
     1.0 s of the previous success is skipped (a settle that also completes the group would otherwise buzz twice).
   - **warning:** when a destructive or irreversible confirmation OPENS (the alert, dialog or confirming screen appears),
     never on the final tap. **DECIDED (controller, for the founder):** "before anything destructive" means the moment
     the person is asked, which is the only "before" with a pause in it; actions that act at once and can be undone or
     redone (sign out, remove a picture, a group picture's "Use letter", dismiss an invitation, sync off, ask to remove)
     get no warning. The owner-transfer and the username-change confirmations count as irreversible and warn.
4. **The moment model and its centre.** `enum DoneMoment { case small(DoneLine), case big(BigMoment) }` with
   `DoneLine(title: String, parts: [String])` and `BigMoment(kind: BigKind, title: String, parts: [String])`,
   `BigKind { firstGroup, signedIn, joined, erased }`. `DoneCenter` (iOS `@Observable @MainActor final class`, `shared`;
   Android `object DoneCenter` with snapshot state): `post(_:)` replaces whatever shows (no queue, so nothing ever waits:
   GUARANTEES #2), records `startedAt` (reference-date seconds on iOS, `SystemClock.uptimeMillis` on Android, the
   clocks the engines already use), plays the haptic per Design 3, and posts the accessibility announcement (iOS
   `UIAccessibility.post(notification: .announcement, argument: DoneCopy.spoken(line))`; Android the toast's text is a
   `LiveRegionMode.Polite` node). `clear()` removes it. Spoken form: `title, part, part` (the separator is never read).
   Nothing is logged (GUARANTEES #1).
5. **The small moment: the toast (L50).** One line: the title, then the parts, joined with `Separator.text` /
   `Separator.annotated` (Norse's gold ᛫), all in `TypeRole.name(TypeScale.toast)` / `Type.name(dark, TypeScale.TOAST)`
   in `text` (15 at 600; the LB's 600 12 x 1.28), at most two lines, the last part ending in an ellipsis. Leading dot
   `toastDot` 26, a circle filled `owing` (the LB's `--p-owing`), holding C2's Lucide `check` at `toastGlyph` 15 in
   `onAccent` with stroke `toastCheckStroke` 3.5 (Lucide units; the LB's `stroke-width:3.5`), gap `toastGap` 10. The
   bar: `surface` (C1 decided the LB's `--p-solid` is `surface`), a 1 pt `hairline` edge, padding `toastPadV` 12 /
   `toastPadH` 15, shape `ThemeArt.panelShape(theme, radius: toastRadius 18)` (Aurora rounded 18, Norse notched, Vardr
   Labs square; no frame marks: the LB draws them on `.card` only), shadow black `toastShadowAlpha` 0.14, radius
   `toastShadowRadius` 9, y `toastShadowY` 6 (Android: `shadow(9.dp, shape)` with default black colours, as D-219's +).
   Width: the screen less `toastInset` 16 a side. Place (`ToastPlace.bottom(barShown:)`): its bottom edge
   `toastClearOfBar` 104 above the tab bar row's bottom when the bar shows (clear of the tallest + face, the Norse
   diamond's top at 95.25, by 8: art.py 39), else `toastInset` 16 above the safe area / navigation bar inset. Motion
   (`ToastMotion.pose(elapsed:still:) -> (opacity, dy)?`, motion.py part 6): in over 0.2 s (opacity 0 to 1, rising 10
   pt, `Bezier.y(u, (0.2, 0.7, 0.3, 1))`), held, out over the last 0.25 s (opacity, linear), gone at 3.0 s; still:
   shown whole for 3.0 s, then gone. **DECIDED (controller, for the founder): 3.0 s** (the LB gives no time; long enough
   to read a short line, shorter than Material's 4 s snackbar because nothing on it can be tapped; the announcement
   carries it for screen readers). It never takes a touch (`.allowsHitTesting(false)` / no pointer modifier), so the +
   and every control under it work.
6. **The big moment: the check (L49).** `DoneCheck` in a `ringBox` 137 square (D-221's), centred:
   - the check, `doneCheck` 104 (the picture's size inside the same ring, D-221): a circle of radius `doneCircleRadius`
     48 (in the 104 box), stroke `doneCircleStroke` 3 in `accent`, filled `accentSoft` (Norse
     `ThemeArt.color("norseCheckFill")` #141109, LB 247), the stroke drawn from 12 o'clock clockwise; the tick through
     `DoneCheck.tick` (32, 54), (46, 67), (73, 39) of the 104 box, stroke `doneTickStroke` 6 in `accent`, round caps
     and joins. **DECIDED (controller, for the founder): `accent`, as the Look Book draws the check in every theme**
     (Vardr Labs' steel, not the ring's cyan); the circle starts at 12 o'clock like the completion ring (the LB's 3
     o'clock start is SVG's default, not a choice).
   - Norse only: E4's `RuneRing` with every rune lit (`RuneRing.lit(done: 1, total: 1)` = 24; radius 60, size 14, the
     `ring` gold), turned by the pose's degrees about the box's centre. Aurora and Vardr Labs draw no ring (LB
     `.runecircle { display: none }` outside Norse): the circle drawing itself is their ring.
   - Pose (`CheckMotion.pose(elapsed) -> (circle, tick, scale, degrees)`, motion.py part 6): the circle's trim over 0.55
     s, `Bezier.y` (0.6, 0, 0.2, 1), times 302 / its length (closes a little early, as the LB's dash); the tick's trim
     from 0.45 s over 0.35 s, (0.3, 0, 0.2, 1), times 60 / its length; the pop from 0.7 s for 0.5 s, 1 to 1.08 to 1, each
     half eased by (0.3, 1.6, 0.5, 1) (CSS applies the timing to each keyframe segment; it overshoots); the rune ring one
     turn per 14 s, clockwise, forever while shown. Still (Reduce Motion or Low Power): `CheckMotion.stillElapsed` 14,
     whose pose is (1, 1, 1, 0): everything drawn, upright, unmoving.
   - Words under it: the title in `TypeRole.display(TypeScale.displayName, weight: 700)` (24, D-221's) in `text`; the
     detail parts joined with `Separator.text` at `TypeScale.detail`.
   - Two presentations of the same `DoneCheck`:
     - **screen** (the person leaves with a button): signed in, joined, erased. The check replaces today's icon at the
       top; title, detail and the button as today; detail in `textDim` (art.py 37: 4.55 or more on every page ground).
     - **overlay** (passes through, ends by itself): the first group. Over the shell's tab content, under the tab bar:
       the content dims to `CelebrationTiming.dimTo` 0.22 over `dimIn` 0.3 s, the check and the words centred at
       `messageTop` 0.42 of the content's height, for `CelebrationTiming.total` 3.6 s, then gone (E4's timing, so the two
       big moments feel like one family). Detail in `text`, not `textDim` (E4's reason: textDim fails 4.5 over the
       dimmed page; art.py 37 measures text at 8.58 or more). It never takes a touch; the tab bar and the + are never
       dimmed or covered. **DECIDED (controller, for the founder): the first group is an overlay, not a screen**, because
       a screen with a Done button adds a tap to creating a group (the friction audit's target cut taps), while sign-in
       and erase already end on a screen or alert the person dismisses.
7. **What is big, what is small (every call site; Design 8 lists the files).**
   - **Big, screen:** signed in (`SignedInConfirmation`); joined a group (the "You are in X." page); this phone's
     ledger erased (today an alert after the restart: it becomes the screen, with `EraseCopy.doneTitle`, `doneBody` and
     the button `EraseCopy.doneOK`, the same one tap as the alert's OK).
   - **Big, overlay:** the first group created (the vault's named groups go from 0 to 1 through create). Title "Group
     created", parts `[name] + GroupsCopy.detailParts(...)` (the LB's "Goa trip ᛫ 4 people ᛫ INR").
   - **Big, E4's celebration:** a group settled (E4 draws it; E3 adds the haptic, Design 9).
   - **Small (toast + success):** expense added; expense saved (an edit); group created (not the first); person added
     (the add-person screen's own done bar becomes the toast, Design 8); person removed (local group, and a completed
     cloud removal); group picture saved or removed; your picture saved or removed; your name saved; username saved or
     changed (outside the sign-in flow: inside it the signed-in screen follows); signed out; settled with someone
     (Settle up's card and E4's group next card, one shared record function); group name saved.
   - **Success haptic only (the page already shows the result):** export done; import done.
   - **DECIDED (controller, for the founder):** the delete-account request stays as it is (a goodbye, not a moment to
     celebrate); the milestone card gets no haptic (Design 9).
8. **Changes the moments make to today's screens.**
   - Add expense: the 0.6 s "Saved" hold before the sheet closes goes. On success the screen posts the toast and closes
     at once (iOS `AddExpenseScreen` posts `DoneCenter` then calls `onSaved` or `dismiss()` with no `asyncAfter`;
     Android `onSaved()` at once, no "Saved" label phase). Logging gets faster, never slower (GUARANTEES #2).
   - Profile: the "Saved" label on the name button goes (it never reset, a friction-audit bug); the toast says it.
   - Add person: iOS's `safeAreaInset` done bar (People.swift) and Android's inline "✓ ..." glassCard text (People.kt)
     both become the toast with the same words, drawn by a LOCAL host in that screen (it is a sheet on iOS, above the
     shell's host): `DoneToastHost(center: local)` at the screen's bottom, `ToastPlace.bottom(barShown: false)`.
   - Settle up keeps its green line (it carries the section 11.6 sentence); the toast is what shows when that line is
     scrolled away (the friction audit's complaint).
   - Signed in: the 64 pt SF symbol (iOS) and the 72 dp drawn tick (Android) go; `DoneCheck(screen)` takes their place.
   - Erase: iOS `SkiptiApp`'s `.alert(EraseCopy.doneTitle ...)` and Android `AppRoot`'s erase notice dialog become the
     big screen (iOS a `fullScreenCover` on the root bound to the same flag; Android a full-size composable over the root
     with the same flag), button "OK".
   - Joined: the page's heading gains `DoneCheck(screen)` above it; copy unchanged.
9. **The celebration and the milestone.** E4's `CelebrationOverlay`, when it starts: `DoneCenter.clear()` (a settle
   toast posted a moment before gives way) and `Haptics.play(.success)` through the gate. The milestone card: no haptic.
10. **The Haptics row (L48).** On Appearance, after S1's Text size card, one list card (`glassCard(padding: 0)` with
    `listPadH` / `listPadV`, S1's) holding one row: `SettingsTile(glyph: .vibrate)` (Lucide `vibrate`, copied verbatim;
    LB 396 draws its own rays, Lucide has no such drawing, `vibrate` names the thing), the title "Haptics" in
    `TypeRole.name(16)`, the summary "A tap when something is done" at `TypeScale.detail` in `textDim`, and the
    trailing switch (iOS `Toggle` with `.labelsHidden()` tinted by the window's accent; Android `Switch` with
    `switchColors(dark)`); the whole row toggles it. No section label (the LB has none). Accessibility: one element,
    "Haptics, A tap when something is done", with the switch's value and role (iOS the `Toggle` labelled by the title;
    Android `Modifier.toggleable(value, role = Role.Switch)` on the row with the switch's own `onCheckedChange = null`).
11. **GUARANTEES #2 (no moment may block or delay logging an expense).** Every moment is posted and forgotten: no
    `await`, no delay before a sheet closes, no queue; the toast and the overlay never take a touch; the tab bar and the
    + are never dimmed or covered (the dim is applied to the tab content, inside the bar's overlay); the + opens Add
    during any moment. The big screens are the two that existed already (signed in, erase), plus the join page, none of
    them on the path of logging an expense.

## Global Constraints

- Values (Swift `Double` / `CGFloat`, Kotlin twins; never retype a value that is not here or in the generator outputs):
  - `ThemeArt.metrics` gains, after D-223's 78, in this order (94 keys): `"doneCheck": 104, "doneCircleRadius": 48,
    "doneCircleStroke": 3, "doneTickStroke": 6, "toastRadius": 18, "toastPadV": 12, "toastPadH": 15, "toastGap": 10,
    "toastDot": 26, "toastGlyph": 15, "toastCheckStroke": 3.5, "toastInset": 16, "toastClearOfBar": 104,
    "toastShadowAlpha": 0.14, "toastShadowRadius": 9, "toastShadowY": 6`.
  - `ThemeArt.hex` gains `"norseCheckFill": "#141109"`.
  - `enum DoneCheck` / `object DoneCheck` in ThemeArt: `tick = [(32, 54), (46, 67), (73, 39)]` (points in the 104 box).
  - `TypeScale` gains `toast = 15` after D-223's `emptyTitle` (and `all` lists it).
  - ThemeMotion gains (Swift `enum`, Kotlin `object`):
    - `Bezier.y(_ u: Double, _ p: (Double, Double, Double, Double)) -> Double`: CSS cubic-bezier solved by bisection, 48
      halvings (motion.py `bezier`); `SettledBurst.ease(u)` becomes `Bezier.y(u, SettledBurst.easeCurve)`, its goldens
      unchanged.
    - `CheckMotion`: `circleDraw = 0.55`, `circleEase = (0.6, 0, 0.2, 1)`, `circleDash = 302`, `tickAt = 0.45`,
      `tickDraw = 0.35`, `tickEase = (0.3, 0, 0.2, 1)`, `tickDash = 60`, `popAt = 0.7`, `popDuration = 0.5`,
      `popScale = 1.08`, `popEase = (0.3, 1.6, 0.5, 1)`, `runeTurn = 14`, `stillElapsed = 14`;
      `pose(_ elapsed: Double) -> (circle: Double, tick: Double, scale: Double, degrees: Double)` with the circle's
      length `2 pi 48` and the tick's length computed from `DoneCheck.tick`.
    - `ToastMotion`: `fadeIn = 0.2`, `fadeOut = 0.25`, `total = 3.0`, `rise = 10`, `ease = (0.2, 0.7, 0.3, 1)`;
      `pose(elapsed:still:) -> (opacity: Double, dy: Double)?`.
    - `DoneHapticTiming.delay(big: Bool, still: Bool) -> Double` (0, 0, 0.7) and `HapticGate.window = 1.0`,
      `allows(lastSuccess: Double?, now: Double) -> Bool`.
    - `MomentClock.elapsed(tick: Bool, now: Double, startedAt: Double, still: Double) -> Double`: `now - startedAt` when
      ticking and finite, else `still` (the check's 14, the toast's own still handling).
  - Goldens (re-typed from `motion.out` part 6, never read back from the code): the nine ease points; check poses at
    0, 0.2, 0.4, 0.5, 0.55, 0.6, 0.7, 0.8, 0.95, 1.1, 1.2, 3.5, 13.99, 14 (accuracy 1e-5; degrees 1e-3); toast poses at
    -0.1, 0, 0.05, 0.1, 0.2, 1.5, 2.75, 2.875, 2.99, 3.0, moving and still.
- Android haptic constants by name: light `HapticFeedbackConstants.CLOCK_TICK`; success `CONFIRM` (sdk >= 30) else
  `CONTEXT_CLICK`; warning `REJECT` (sdk >= 30) else `LONG_PRESS`. Goldens at sdk 26, 29, 30, 34, 37.
- Copy (both shells, verbatim, no em dashes; `DoneCopy`): "Expense added"; "Expense saved"; "Group created";
  "\(name) removed"; "Group picture saved"; "Group picture removed"; "Picture saved"; "Picture removed"; "Name saved";
  "Username saved"; "Signed out"; "Settled with \(name)"; "Group name saved"; the row "Haptics" / "A tap when something
  is done". Parts: expense `[description, amount]` (amount `ffiMoneyFormat`, never shell-built); username `["@handle"]`;
  settled `[amount]`; group created `[name]`; first group `[name] + GroupsCopy.detailParts(...)`. Kept as they are:
  `SignedInCopy`, `EraseCopy.done*`, `InviteCopy.joined`, the add-person `done` words.
- New files (iOS: four pbxproj insertions each, CLAUDE.md 7.1, IDs asserted free first; app file: copy `People.swift`'s
  reference line untruncated; test file: `path = X.swift; sourceTree = "<group>";`): `ios/Skipti/App/DoneMoments.swift`,
  `ios/SkiptiTests/DoneMomentsTests.swift`; Android `android/app/src/main/java/com/vardrlabs/skipti/DoneMoments.kt`,
  `android/app/src/test/java/com/vardrlabs/skipti/DoneMomentsTest.kt`.
- Names (Kotlin SCREAMING case for constants): `HapticKind`, `Haptics.play`, `Haptics.plays`, `HapticsSetting`
  (`storageKey`, `parse`), `HapticGate`, `DoneHapticTiming`, `DoneMoment`, `DoneLine`, `BigMoment`, `BigKind`,
  `DoneCenter` (`post`, `clear`, `current`, `startedAt`), `DoneCopy` (+ `spoken`), `DoneMoments.groupCreated(
  namedCountAfter:name:detailParts:) -> DoneMoment` (big when 1, small otherwise), `ToastPlace.bottom(barShown:)`,
  views `DoneToast`, `DoneToastHost`, `DoneCheck` (with `presentation: .screen | .overlay`), `DoneOverlay`,
  `HapticsRow`.
- Lucide `vibrate`: read `https://unpkg.com/lucide-static@latest/icons/vibrate.svg` with WebFetch (reading only,
  nothing executed), copy the path data VERBATIM (a `<rect>` written as the path convention the existing drawings use,
  recorded in the comment). NOTICE's Lucide entry goes from seventeen drawings to eighteen, naming `vibrate` and "the
  Haptics row"; read Lucide's LICENSE for whether `vibrate` is on the Feather-derived list and word NOTICE, the
  `scripts/licences.py` comment and notice string as it says; regenerate `native/licences/licences.json` (`python3
  scripts/licences.py`, then `--check`).
- Motion only through the engine: no `withAnimation`, `.animation(`, `.transition(` in DoneMoments.swift; no
  `animate*AsState`, `Animatable`, `AnimatedVisibility` or `updateTransition` in DoneMoments.kt. Poses are pure
  functions of `elapsed` on the shared grid (iOS `TimelineView(ArtSchedule(paused: !tick))` or `ArtCanvas`; Android
  `rememberMotionState()` / `FrameGate`'s grid, as E4's `CelebrationOverlay` reads it).
- Shapes only through `ThemeArt.panelShape`; type only through `TypeRole` / `Type` and `TypeScale`; colours by token name
  (`text`, `textDim`, `surface`, `hairline`, `owing`, `onAccent`, `accent`, `accentSoft`, `ring`) and
  `ThemeArt.color("norseCheckFill")`. No new token, no new dependency, no core change, no network, nothing logged. One
  new persisted key per shell (the switch).
- No em dashes anywhere. Commits end `[skip ci]`; explicit paths only; `git diff --cached --stat` before every commit,
  refuse if it holds a file you did not stage; no attribution of any kind. Never `git stash`, `reset`, `checkout --
  <file>` or `restore`.
- iOS tests: boot `AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51` first, `-collect-test-diagnostics never` on every `xcodebuild
  test`, one class at a time (`-only-testing:SkiptiTests/<Class>`), shut it down when done; never two iOS test runs at
  once in this checkout. Android: `cd android && zsh -lic './gradlew :app:testPlayDebugUnitTest --tests
  "com.vardrlabs.skipti.<Class>"'`; check result XML mtimes before quoting a tally. Stop your own Monitors and watchers
  before replying.
- Every new guard is sabotaged (matrix below). A red test fails, never crashes. The controller runs the full gate.
- File:line references below were read on 2026-10-16 at `c2b8877` (D-218): C1 to Home move them. Find every site by its
  anchor text before editing.

---

### Task 1: iOS values, pure decisions, copy and the `vibrate` drawing (one agent; parallel with Task 2)

**Files:** `ios/Skipti/App/ThemeArt.swift` (16 metrics, the hex, `DoneCheck.tick`), `ios/Skipti/App/TypeSystem.swift`
(`TypeScale.toast`), `ios/Skipti/App/ThemeMotion.swift` (`Bezier`, `SettledBurst.ease` through it, `CheckMotion`,
`ToastMotion`, `DoneHapticTiming`, `HapticGate`, `MomentClock`), `ios/Skipti/App/LucideIcon.swift` (`vibrate`), create
`ios/Skipti/App/DoneMoments.swift` (MODELS, copy and the `Haptics` door only: `HapticKind`, `Haptics.play` / `plays`,
`HapticsSetting`, `DoneMoment`, `DoneLine`, `BigMoment`, `BigKind`, `DoneCopy`, `DoneMoments.groupCreated`,
`ToastPlace`, `DoneCenter` without views), `ios/Skipti.xcodeproj/project.pbxproj` (two files), `NOTICE`,
`scripts/licences.py`, `native/licences/licences.json`; tests `ios/SkiptiTests/ThemeArtTests.swift`,
`TypeThemeTests.swift`, `ThemeMotionTests.swift`, new `ios/SkiptiTests/DoneMomentsTests.swift`.

- [ ] **Step 1: Tests first.**
  - `ThemeArtTests.testE3ArtValues`: the 16 keys and values; `testEveryArtValueIsPresent` at 94; `norseCheckFill`;
    `DoneCheck.tick`.
  - `TypeThemeTests.testTheTypeScaleIsTheAgreedTable` extended with `toast` 15 last.
  - `ThemeMotionTests.testTheDoneMotionMatchesTheGoldens`: `Bezier.y` at the nine points; `CheckMotion.pose` at the 14
    times; `ToastMotion.pose` at the ten times both ways; `SettledBurst` goldens still green (the refactor);
    `testTheStillFrameIsTheEndState`: `CheckMotion.pose(CheckMotion.stillElapsed) == (1, 1, 1, 0)`;
    `MomentClock.elapsed` (ticking, not ticking, a non-finite `now`).
  - `DoneMomentsTests`: `HapticsSetting.parse(nil) == true`, stored false is false (negative); `Haptics.plays(k, enabled:
    false)` false for all three kinds (negative), true when on; `HapticGate.allows` (nil true; 0.99 s false; 1.0 s
    true); `DoneHapticTiming.delay` the three cases; `DoneMoments.groupCreated(namedCountAfter: 1, ...)` is `.big` of
    `.firstGroup` with parts `[name] + detailParts`, at 2 is `.small` "Group created" `[name]`, at 0 (a failed read) is
    `.small` (negative: never big without proof); `ToastPlace.bottom(barShown: true) == 104`, `false == 16`;
    `DoneCopy` table verbatim; `DoneCopy.spoken` joins with ", " and never contains "᛫" or "·"; no copy contains an em
    dash.
  - `testHapticsHaveOneDoor` (source guard over every file in `ios/Skipti/App/`): `UIImpactFeedbackGenerator`,
    `UINotificationFeedbackGenerator`, `UISelectionFeedbackGenerator` and `.sensoryFeedback(` appear only in
    DoneMoments.swift; `Haptics.play` checks `HapticsSetting` (reads `HapticsSetting.storageKey`).
- [ ] **Step 2:** Run each class alone: red for the right reason (declare with wrong values first).
- [ ] **Step 3:** Implement. Copy `vibrate` verbatim; NOTICE eighteen; `python3 scripts/licences.py`, then `--check`.
  Green on `ThemeArtTests`, `TypeThemeTests`, `ThemeMotionTests`, `DoneMomentsTests`, plus the existing Lucide test.
- [ ] **Step 4:** Sabotage rows V1 to V6, M1 to M8. Report them.
- [ ] **Step 5:** Commit `D-224 T1: iOS done-moment values, check and toast motion, haptic decisions, the vibrate drawing [skip ci]`.

### Task 2: Android values, pure decisions, copy and parity (one agent; parallel with Task 1, parity runs last)

**Files:** `android/app/src/main/java/com/vardrlabs/skipti/ThemeArt.kt`, `Tokens.kt` (`TypeScale.TOAST`),
`ThemeMotion.kt` (the twins of Task 1's), `TabIcons.kt` (`vibrate` path), `Appearance.kt` (`haptics` state,
`setHaptics`, `load` reads it, `setForTest(haptics = true)`), create `DoneMoments.kt` (models, copy, `HapticKind`,
`HapticKind.constant(kind, sdkInt)`, `Haptics.play(view, kind)`, `Haptics.plays`, `HapticsSetting`, `DoneCenter`
without views); tests `ThemeArtTest.kt`, `TypeThemeTest.kt`, `ThemeMotionTest.kt`, new `DoneMomentsTest.kt`.

- [ ] **Step 1: Tests first:** the twins of Task 1's, plus:
  - `the_haptic_constants_are_the_sdks`: `HapticKind.constant` at sdk 26, 29, 30, 34, 37 equals the named
    `HapticFeedbackConstants` fields (light CLOCK_TICK everywhere; success CONTEXT_CLICK below 30, CONFIRM from 30;
    warning LONG_PRESS below 30, REJECT from 30); the negative: CONFIRM and REJECT never returned below 30.
  - `haptics_have_one_door` (source guard over the main source set): `performHapticFeedback`, `LocalHapticFeedback`,
    `HapticFeedbackConstants` appear only in DoneMoments.kt; `Vibrator`, `VibratorManager` and `VIBRATE` appear nowhere;
    `performHapticFeedback(` passes one argument (no `FLAG_IGNORE_GLOBAL_SETTING`).
  - `the_manifest_asks_no_vibrate`: `AndroidManifest.xml` has no `android.permission.VIBRATE`.
  - Parity (each reads the Swift file): `the_art_values_match_ios` at 94; `the_e3_hex_and_tick_match_ios`;
    `the_type_scale_is_the_ios_table` (already parses `TypeScale`); `the_done_motion_matches_ios` (every `CheckMotion`,
    `ToastMotion`, `HapticGate`, `DoneHapticTiming` constant parsed from ThemeMotion.swift); `the_done_copy_matches_ios`
    (every `DoneCopy` string parsed from DoneMoments.swift); the existing icon-path parity covers `vibrate`.
- [ ] **Step 2:** Red for the right reason. Parity goes green once Task 1 has committed (run it last).
- [ ] **Step 3:** Implement. Green on the four classes plus `PaletteParityTest` and the icon parity test.
- [ ] **Step 4:** Sabotage rows P1 to P5 (Android lane, Swift-only edits) and AM1 to AM6.
- [ ] **Step 5:** Commit `D-224 T2: Android done-moment values, check and toast motion, haptic constants, parity [skip ci]`.

### Task 3: iOS components, hosts, the Haptics row and the tab haptic (after Tasks 1 and 2; parallel with Task 4)

**Files:** `ios/Skipti/App/DoneMoments.swift` (views: `DoneToast`, `DoneToastHost`, `DoneCheck`, `DoneOverlay`;
`DoneCenter.post` plays haptics and the announcement), `ios/Skipti/App/HomeShell.swift` (the host over the tab
content, the dim on the tab ZStack BEFORE the bar's `.overlay(alignment: .bottom)`, the light haptic in `tapped`'s
`.switchTo` branch), `ios/Skipti/App/AccountHubScreen.swift` (`HapticsRow` after the Text size card on Appearance),
`ios/Skipti/App/AccountOwner.swift` (`SignedInConfirmation` draws `DoneCheck(.screen)`); tests `DoneMomentsTests.swift`.

- [ ] **Step 1: Tests first** (source guards name file and anchor):
  - `testTheMomentsNeverTakeATouch`: `DoneToast`, `DoneToastHost` and `DoneOverlay` carry `.allowsHitTesting(false)`;
    DoneMoments.swift has no `.sheet(`, `.fullScreenCover(`, `.alert(`, `.confirmationDialog(`, `Button(`.
  - `testTheMotionIsTheEngines`: DoneMoments.swift draws through `ArtSchedule(` or `ArtCanvas(`, reads
    `CheckMotion.pose(`, `ToastMotion.pose(` and `MomentClock.elapsed(` with `MotionClock.shouldTick(`; contains no
    `withAnimation(`, `.animation(`, `.transition(`.
  - `testTheCheckIsTheArtTable`: `DoneCheck` reads `ThemeArt.m("doneCheck")`, `"doneCircleRadius"`,
    `"doneCircleStroke"`, `"doneTickStroke"`, `"ringBox"`, `DoneCheck.tick`, `Tokens.accent`, `Tokens.accentSoft`,
    `ThemeArt.color("norseCheckFill")`, and uses `RuneRing(` only under a Norse condition; `.accessibilityHidden(true)`.
  - `testTheToastIsTheArtTable`: `ThemeArt.panelShape(`, `"toastRadius"`, `"toastDot"`, `Tokens.owing`, `Tokens.surface`,
    `Tokens.hairline`, `LucideIcon.check` (C2's case name: verify), `TypeScale.toast`, `Separator.text(`.
  - `testTheShellNeverDimsTheBar`: in HomeShell.swift's body, the `DoneOverlay` dim modifier appears before
    `.overlay(alignment: .bottom)` that holds `SkiptiTabBar(`; `DoneToastHost(` is present; the `case .switchTo` branch
    calls `Haptics.play(.light)` and the `.popToRoot` and `.scrollToTop` branches do not.
  - `testTheSignedInScreenDrawsTheCheck`: `SignedInConfirmation` contains `DoneCheck(` and no `checkmark.circle.fill`.
  - `testTheHapticsRowIsOnAppearance`: AccountHubScreen.swift's Appearance case builds `HapticsRow(` after the text size
    card; `HapticsRow` uses `SettingsTile(glyph: .vibrate)`, `@AppStorage(HapticsSetting.storageKey)`, a `Toggle(`, and
    plays `.success` only when turned on.
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** Design 4, 5, 6, 10, 11, the tab haptic (Design 3) and the signed-in screen. The overlay's
  `startedAt` and the toast's come from `DoneCenter`; the host removes a moment after its total (a `.task(id:)` that
  sleeps the remaining time and calls `clear()` only if the same moment still shows). The announcement goes once, at
  `post`. The big screen's haptic: `DoneCheck(.screen)` schedules it at `DoneHapticTiming.delay(big: true, still:)` from
  its appearance.
- [ ] **Step 4:** Green on `DoneMomentsTests`, `ThemeMotionTests`, `AppearanceTests`, `AccountTests`, `HomeTabsTests` (or
  the class holding `TabTap`). Sabotage rows H1 to H7.
- [ ] **Step 5:** Commit `D-224 T3: iOS toast, check and overlay through the motion engine, the Haptics row, the tab haptic [skip ci]`.

### Task 4: Android components, hosts, the Haptics row and the tab haptic (after Tasks 1 and 2; parallel with Task 3)

**Files:** `DoneMoments.kt` (composables `DoneToast`, `DoneToastHost`, `DoneCheck`, `DoneOverlay`; `DoneCenter.post`
plays through the root's `LocalView`), `AppRoot.kt` (the host over the root, above every route; the dim on the shell's
tab content, not on `SkiptiTabBar`; the light haptic in the `SwitchTo` branch near `TabTap.decide`),
`AccountHubScreen.kt` (`HapticsRow` on Appearance), `AccountOwner.kt` (`SignedInConfirmation` draws `DoneCheck`, the
72 dp circle and its tick go); tests `DoneMomentsTest.kt`.

- [ ] **Step 1: Tests first:** the twins of Task 3's guards: no `clickable`, `pointerInput`, `Dialog(`, `AlertDialog(`
  or `Popup(` in the toast, host and overlay; drawn through `artCanvas(` / `rememberMotionState()` and the pure poses, no
  `animate*AsState`, `Animatable`, `AnimatedVisibility`, `updateTransition` in DoneMoments.kt; the toast's text is
  `liveRegion = LiveRegionMode.Polite`; the check reads the same metrics and `ThemeArt.color("norseCheckFill")` and
  `RuneRing(` only for Norse; in AppRoot.kt the dim wraps the tab content and `SkiptiTabBar(` sits outside it; the
  `SwitchTo` branch plays `HapticKind.LIGHT`, the other two branches do not; `SignedInConfirmation` has `DoneCheck(` and
  no `CircleShape` tick; `HapticsRow` uses `SettingsTile(`, `Appearance.haptics`, `toggleable(` with `Role.Switch` and
  `Switch(` with `onCheckedChange = null` and `switchColors(dark)`.
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** the twin. `ToastPlace.bottom(barShown)`: `barShown` is true while the shell's tab bar is
  composed; the inset adds `WindowInsets.navigationBars`.
- [ ] **Step 4:** Green on `DoneMomentsTest`, `ThemeMotionTest`, `AppearanceTest`, `HomeTabsTest`, `AccountTest`.
  `make android-app` once (the APK builds). Sabotage rows AH1 to AH7.
- [ ] **Step 5:** Commit `D-224 T4: Android toast, check and overlay through the motion engine, the Haptics row, the tab haptic [skip ci]`.

### Task 5: iOS call sites (after Task 3; parallel with Task 6)

**Files (anchors read at `c2b8877`; find each by its text):** `AddExpenseScreen.swift` (success at :615 and :663: post
`expenseAdded` / `expenseSaved`, remove both `asyncAfter(deadline: .now() + 0.6)` holds and the `saved ? "Saved"`
label), `GroupSheets.swift` (:248 `onCreated`: `DoneMoments.groupCreated(namedCountAfter: try? store.namedGroups().count
...)` posted ONCE here, not in the four callers), `People.swift` (:1062 and the `.safeAreaInset` done bar at :912 become
a local `DoneToastHost`; the username card's change success :586), `GroupPicker.swift` (:406 local removal),
`RemovalFlow.swift` (:880 completed removal), `GroupSettings.swift` (:286 group name, :300 picture), `OverviewScreen.swift`
(:580 picture), `Profile.swift` (:409 name: the "Saved" label goes; :423 picture), `UsernameStep.swift` (:313, :325,
:335, :414: only outside the sign-in flow), `AccountHubScreen.swift` (:584 signed out), `SettleAll.swift` (:222, the
shared record function E4 extracted: one post covers Settle up and the group next card), `Invites.swift` (:448 the
joined page gains `DoneCheck(.screen)`), `SkiptiApp.swift` (:126 to :133 the erase alert becomes a `fullScreenCover`
of the big screen with `EraseCopy.doneOK`), `ExportFlow.swift` (:317 success haptic only), `ImportReview.swift` (:148
/ :150 success haptic only), `Settled.swift` (E4's `CelebrationOverlay` start: `DoneCenter.shared.clear()` and the
success haptic), and the warning sites (Design 3) through one modifier `.hapticWarning(when: Bool)` defined in
DoneMoments.swift, applied to the flag that presents each confirmation: `ExpenseListView.swift` (:166 dialog),
`ExpenseDetailView.swift` (:204), `GroupSettings.swift` (:230 delete-for-everyone sheet, :244 local, :449 copy),
`GroupPicker.swift` (:405), `RemovalFlow.swift` (:749 role alert; the three red-button screens :1089, :1222, :1410 warn
on appear), `AccountOwner.swift` (:225 erase), `VaultRecoveryView.swift` (:61, verify it confirms first; warn when its
confirmation opens), `Phones.swift` (:225, :231, :326), `AppLock.swift` (:223), `UsernameStep.swift` (:383). Tests
`DoneMomentsTests.swift`.

- [ ] **Step 1: Tests first** (source guards; a table of file to expected count, so a lost site fails by name):
  - `testEverySmallMomentIsPosted`: per file, the count of `DoneCenter.shared.post(` with the named `DoneCopy` member
    (AddExpenseScreen 2, GroupSheets 1 via `DoneMoments.groupCreated(`, People 2, GroupPicker 1, RemovalFlow 1,
    GroupSettings 2 or 3 (save and remove picture: count the branches you write and pin them), OverviewScreen 1 or 2,
    Profile 2 or 3, UsernameStep as written, AccountHubScreen 1, SettleAll 1); every `post(` site reads a `DoneCopy`
    member (no string literal).
  - `testAddExpenseClosesAtOnce` (GUARANTEES #2): AddExpenseScreen.swift contains no `asyncAfter` and no `"Saved"`; the
    post precedes `onSaved` / `dismiss()` in both branches.
  - `testEveryDestructiveConfirmationWarns`: per file, the count of `.hapticWarning(` equals the table above (11 files);
    and every `role: .destructive` button inside an `.alert(` or `.confirmationDialog(` in `ios/Skipti/App/` lives in a
    file of the table (a new destructive confirmation without a warning fails).
  - `testTheBigMomentsAreTheCheck`: Invites.swift's joined page and SkiptiApp.swift's erase cover contain `DoneCheck(`;
    SkiptiApp.swift has no `.alert(EraseCopy.doneTitle`.
  - `testTheCelebrationClearsAndBuzzes`: Settled.swift's `CelebrationOverlay` calls `DoneCenter.shared.clear()` and
    `Haptics.play(.success)`; no milestone code calls `Haptics`.
  - `testExportAndImportBuzz`: ExportFlow.swift and ImportReview.swift each call `Haptics.play(.success)` once and post
    no toast.
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** Design 7 and 8. Amounts through `ffiMoneyFormat` with the record's own currency; names as
  the screens already hold them. Remove nothing else.
- [ ] **Step 4:** Green on `DoneMomentsTests`, `AddExpenseTests` (update any "Saved" pin, naming it), `ProfileTests`,
  `SettledTests`, `AccountTests`, `InvitesTests`, `ExportTests`. Sabotage rows S1 to S8.
- [ ] **Step 5:** Commit `D-224 T5: iOS done moments on every finished action, warnings before destructive confirmations [skip ci]`.

### Task 6: Android call sites (after Task 4; parallel with Task 5)

**Files (anchors read at `c2b8877`):** `AddExpenseScreen.kt` (:584 to :591 success: post `EXPENSE_ADDED` /
`EXPENSE_SAVED`, `onSaved()` at once, the "Saved" label at :534 goes), `GroupScreens.kt` (:171 to :180: read
`namedGroups().size` after the create inside the `offMain` block, post `DoneMoments.groupCreated` once; :359 to :368
local removal), `People.kt` (:826 to :830 and the inline `done?.let { Text("✓ $it" ...` at :966 become a local
`DoneToastHost`), `RemovalFlow.kt` (:1105 to :1108 completed removal), `GroupSettings.kt` (:317 to :320 name, :299 to
:302 picture), `OverviewTab.kt` (:467 picture), `Profile.kt` (:542 to :546 name, the "Saved" label at :586 goes; :523 to
:525 picture), `UsernameStep.kt` (:362, :375 outside the sign-in flow; :468 change), `AccountHubScreen.kt` (:366 to :374
signed out), `SettleAll.kt` (:126 to :129, the shared record function), `Invites.kt` (:476 joined page gains
`DoneCheck`), `AppRoot.kt` (:426 to :437 the erase dialog becomes the big screen over the root with "OK"),
`ExportScreen.kt` (:89 to :91 haptic only), `ImportFlow.kt` (:591 to :597 haptic only), `Settled.kt` (E4's
`CelebrationOverlay` start), and the warning sites through one `HapticWarning(show: Boolean)` effect in DoneMoments.kt
(a `LaunchedEffect(show)` that plays `WARNING` when `show` turns true): `ExpenseDetailScreen.kt` (:243),
`ExpenseListScreen.kt` (:415), `GroupScreens.kt` (:490), `GroupSettings.kt` (:442, :472, :528), `AccountOwner.kt`
(:244), `Phones.kt` (:252, :268, :314), `AppLock.kt` (:230), `UsernameStep.kt` (:501), `RemovalFlow.kt` (:980 role
dialog; the red-button screens :1317, :1395, :1422 warn on first composition). Tests `DoneMomentsTest.kt`.

- [ ] **Step 1: Tests first:** the twins of Task 5's guards (per-file post counts, every post reads `DoneCopy`;
  AddExpenseScreen.kt has no "Saved" label phase and calls `onSaved()` in the success branch without a delay; the
  per-file `HapticWarning(` table (12 files) and every `DangerAction(` or destructive-confirm dialog file is in it; the
  joined page and the erase screen contain `DoneCheck(`, AppRoot.kt has no erase `SkiptiAlertDialog`; the celebration
  clears and buzzes; export and import buzz without a toast).
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** the twin.
- [ ] **Step 4:** Green on `DoneMomentsTest`, `AddExpenseTest`, `ProfileTest`, `SettledTest`, `AccountTest`. `make
  android-app` once. Sabotage rows AS1 to AS8.
- [ ] **Step 5:** Commit `D-224 T6: Android done moments on every finished action, warnings before destructive confirmations [skip ci]`.

### Task 7: Look (after Tasks 1 to 6 and the sabotage run; controller dispatch)

See **Look**. Write `.drafts/d224/look/look-report.md` and pairs in `.drafts/d224/look/pairs/`.

---

## Sabotage matrix

Each row: back up, apply through an asserted anchor, confirm it landed, build with zero compile errors, exactly the
named test red and nothing else, restore, `cmp`. iOS and Android lanes in parallel, each in its own worktree outside the
checkout (`/private/tmp/skipti-d224-ios`, `-android`; own DerivedData; `android/local.properties` copied), the iOS test
simulator booted first. Runner template: `.drafts/d217/sabotage.py lanes`.

| id | file | sabotage | expected red |
|---|---|---|---|
| V1 | ThemeArt.swift | `"toastClearOfBar"` 104 to 96 | testE3ArtValues |
| V2 | ThemeArt.swift | `norseCheckFill` to `#241F12` | testE3ArtValues |
| V3 | ThemeMotion.swift | `CheckMotion.popEase` y1 1.6 to 1.0 | testTheDoneMotionMatchesTheGoldens |
| V4 | ThemeMotion.swift | `CheckMotion.stillElapsed` 14 to 1.2 | testTheStillFrameIsTheEndState |
| V5 | ThemeMotion.swift | `ToastMotion.total` 3.0 to 2.5 | testTheDoneMotionMatchesTheGoldens |
| V6 | TypeSystem.swift | `toast` 15 to 14 | testTheTypeScaleIsTheAgreedTable |
| M1 | DoneMoments.swift | `HapticsSetting.parse(nil)` returns false | DoneMomentsTests setting |
| M2 | DoneMoments.swift | `Haptics.plays` ignores `enabled` | DoneMomentsTests plays |
| M3 | ThemeMotion.swift | `HapticGate.window` 1.0 to 0 | DoneMomentsTests gate |
| M4 | DoneMoments.swift | `groupCreated` big at count 0 | DoneMomentsTests groupCreated |
| M5 | DoneMoments.swift | `ToastPlace.bottom(barShown: true)` returns 16 | DoneMomentsTests place |
| M6 | DoneMoments.swift | `DoneCopy.spoken` joins with " ᛫ " | DoneMomentsTests spoken |
| M7 | ThemeMotion.swift | `DoneHapticTiming.delay(big: true, still: true)` returns 0.7 | DoneMomentsTests timing |
| M8 | AccountHubScreen.swift | a `UINotificationFeedbackGenerator()` call added | testHapticsHaveOneDoor |
| P1 | ThemeArt.swift (Android lane) | `"toastDot"` 26 to 24 | the_art_values_match_ios |
| P2 | ThemeMotion.swift (Android lane) | `ToastMotion.rise` 10 to 8 | the_done_motion_matches_ios |
| P3 | DoneMoments.swift (Android lane) | "Signed out" to "Signed out." | the_done_copy_matches_ios |
| P4 | ThemeArt.swift (Android lane) | `DoneCheck.tick` (46, 67) to (46, 66) | the_e3_hex_and_tick_match_ios |
| P5 | LucideIcon.swift (Android lane) | one digit of `vibrate` | the icon path parity test |
| AM1 | DoneMoments.kt | success returns CONFIRM below 30 | the_haptic_constants_are_the_sdks |
| AM2 | DoneMoments.kt | warning returns LONG_PRESS at 30 | the_haptic_constants_are_the_sdks |
| AM3 | DoneMoments.kt | `performHapticFeedback(c, FLAG_IGNORE_GLOBAL_SETTING)` | haptics_have_one_door |
| AM4 | AndroidManifest.xml | `VIBRATE` permission added | the_manifest_asks_no_vibrate |
| AM5 | DoneMoments.kt | `HapticsSetting.parse(null)` false | DoneMomentsTest setting |
| AM6 | DoneMoments.kt | `groupCreated` big at count 2 | DoneMomentsTest groupCreated |
| H1 | DoneMoments.swift | `.allowsHitTesting(false)` removed from `DoneToast` | testTheMomentsNeverTakeATouch |
| H2 | DoneMoments.swift | a `withAnimation(` around the post | testTheMotionIsTheEngines |
| H3 | DoneMoments.swift | `Tokens.accentSoft` to `Tokens.surface` in `DoneCheck` | testTheCheckIsTheArtTable |
| H4 | HomeShell.swift | the dim moved after the bar's overlay | testTheShellNeverDimsTheBar |
| H5 | HomeShell.swift | `Haptics.play(.light)` moved into `.popToRoot` | testTheShellNeverDimsTheBar |
| H6 | AccountOwner.swift | `checkmark.circle.fill` restored | testTheSignedInScreenDrawsTheCheck |
| H7 | AccountHubScreen.swift | `HapticsRow(` removed from Appearance | testTheHapticsRowIsOnAppearance |
| AH1 | DoneMoments.kt | a `clickable {}` on the toast | the twin of H1 |
| AH2 | DoneMoments.kt | `AnimatedVisibility(` around the toast | the twin of H2 |
| AH3 | DoneMoments.kt | the live region removed | the toast announcement guard |
| AH4 | AppRoot.kt | the dim wraps `SkiptiTabBar(` | the twin of H4 |
| AH5 | AppRoot.kt | the light haptic in `ScrollToTop` | the twin of H5 |
| AH6 | AccountOwner.kt | the 72 dp `CircleShape` tick restored | the twin of H6 |
| AH7 | AccountHubScreen.kt | `onCheckedChange = null` replaced by a lambda (two toggles) | the HapticsRow guard |
| S1 | AddExpenseScreen.swift | the 0.6 s `asyncAfter` restored | testAddExpenseClosesAtOnce |
| S2 | GroupSheets.swift | the `groupCreated` post removed | testEverySmallMomentIsPosted |
| S3 | Profile.swift | the name post replaced by a string literal | testEverySmallMomentIsPosted |
| S4 | SettleAll.swift | the settle post removed | testEverySmallMomentIsPosted |
| S5 | ExpenseListView.swift | `.hapticWarning(` removed | testEveryDestructiveConfirmationWarns |
| S6 | Phones.swift | `.hapticWarning(` removed from the delete-account confirm | testEveryDestructiveConfirmationWarns |
| S7 | SkiptiApp.swift | the erase alert restored in place of the cover | testTheBigMomentsAreTheCheck |
| S8 | Settled.swift | `DoneCenter.shared.clear()` removed | testTheCelebrationClearsAndBuzzes |
| AS1 to AS8 | the Android twins of S1 to S8 | the same sabotage in the twin file | the twin guards |

59 rows: iOS 6 values + 8 models + 7 hosts + 8 sites = 29; Android 5 parity + 6 models + 7 hosts + 8 sites = 26; plus
4 spare for subtle code the implementer finds (name them in the report). Both lanes in parallel; about 35 minutes with
the simulator booted.

## Look

Throwaway devices only, against the local emulators: iOS `d214-look` (`xcrun simctl list devices | grep BDC0AE93` for
the full UDID); Android the throwaway AVD `run-look` (API 34, `-gpu host`; create it from
`system-images;android-34;default;arm64-v8a` if missing; it is ours to wipe). Never AFAF4221, EB725812, `skipti-shots`,
`skipti-smoke` or `Pixel_10_Pro_XL`. Rebuild after the sabotage run before looking; check the dylib and APK are fresh
(mtime and `strings` for "A tap when something is done"). Data: start each device wiped (no groups) for the first-group
overlay; `--groups-proof` afterwards; the local Auth emulator's proof account for signed in. Haptics cannot be felt on a
simulator or an emulator: they are proven by tests, and the founder feels them on his own phones (residue).

Pairs (iOS beside Android) in all five sets (Aurora light, Aurora dark, Norse, Vardr Labs light, Vardr Labs dark) unless
stated:
1. The first group created on a wiped device: the overlay mid-draw (about 0.4 s: circle three quarters, no tick) and
   landed (about 1.0 s: tick drawn, the pop), the tab content dimmed, the tab bar and + bright; Norse's turning Futhark.
   During it, tap the +: Add expense opens (GUARANTEES #2).
2. A second group created: the toast "Group created ᛫ name" above the tab bar, clear of the + (Norse's diamond).
3. Add an expense from the +: the sheet closes at once, the toast "Expense added ᛫ Dinner ᛫ amount" on Balance; tap
   the + while the toast shows: Add opens.
4. Profile: save the name ("Name saved"), set an emoji ("Picture saved"); the button never reads "Saved".
5. Add a person from a group: the local toast in the add-person screen (iOS sheet, Android screen), the same look.
6. Settle up: the toast with the amount; settle the last debt from E4's group next card: the toast gives way to E4's
   celebration.
7. Sign in with the proof account: the signed-in screen with the big check (Aurora dark, Norse, Vardr Labs light).
8. Erase this phone's ledger (a throwaway device only): the confirmation, then the erased screen with the check and OK.
9. Appearance: the Haptics row under Text size, on and off.
10. Reduce Motion on (iOS Settings, Android "Remove animations"): pairs 1 and 3 again: the still check (drawn, upright),
    the toast whole without rising.
Shut every device down after; `pgrep -f qemu-system` empty.

## Expected section 4 residue

- Haptics are proven by tests and source guards only; no simulator or emulator plays them. The founder feels them on
  his iPhone and Android phone (light on a tab switch, success on a done moment, warning when a destructive question
  opens) before 0.5.0.
- The Haptics switch draws each platform's own switch (as Security does today); a drawn twin switch is S2's call (Q15).
- A toast posted while a sheet that stays open covers the shell is hidden under it (iOS) or under a dialog window
  (Android); every listed site posts from the shell or after its sheet closes, and the add-person screen hosts its own.
- No Undo on Settle up (friction audit row 7); the Undo bar after deleting an expense keeps its own look.
- Role changes, invite link copied, keep account, sign out one phone, notifications on, app lock and currency choices
  get no toast (their screens show the new state).
- Android below API 30 plays CONTEXT_CLICK for success and LONG_PRESS for warning (the CONFIRM and REJECT patterns arrive
  with API 30).
- The milestone card plays no haptic; the delete-account request has no done moment.
- Aurora light's check circle over the dimmed page measures 3.01 against 3:1 (art.py 37): passing, with no margin.
