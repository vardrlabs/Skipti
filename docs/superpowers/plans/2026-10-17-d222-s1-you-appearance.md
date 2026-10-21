# D-222 S1 "You list and Appearance" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The list under the You hero and the Appearance page drawn as the Look Book draws them in every theme, the same
on both phones: Lucide icon tiles and live summaries on You's five rows, and on Appearance the theme swatches as each
theme's own dark art, one drawn mode control and one drawn text-size slider (no system control left), and a live sample.

**Architecture:** Values, copy and pure decisions first on both shells (25 art values, four type sizes, four Lucide
drawings, `SettingsSection.glyph`, `YouRows` summaries, `StepSlider`, `ModeChoice`, the phone count), each compared
across shells by a parity test that reads the Swift file or by one golden table pinned in both tests. Then each shell's
views in two parallel lanes with disjoint files. The drawn controls keep the platform's accessibility: iOS hands
VoiceOver a real segmented `Picker` and a real `Slider` through `.accessibilityRepresentation`; Android uses
`selectableGroup` / `selectable(role = RadioButton)` and progress semantics with `setProgress`.

**Tech Stack:** SwiftUI (iOS 17+), Jetpack Compose, XCTest, JUnit. No core change (money through the existing
`ffiMoneyParseMinor` / `ffiMoneyFormat`).

**Builds on, merged before S1 starts (do not redo):** D-219 C1 (`.drafts/d219/plan.md`): `LucideStroke` (2.4; every
drawing here takes it), `TabBarGround`, solid = `surface`. D-220 C2 (`.drafts/d220/plan.md`): `ThemeArt.pillKind` /
`pillShape` (Aurora capsule, Norse notched at `pillChamfer` 4, Vardr Labs square), `meterKind` / `meterShape`,
`TileClip.drawnSide` (Norse diamonds at full size), `panelKind` square in Vardr Labs, `ButtonLook.disabledAlpha` 0.45,
NOTICE at twelve drawings (`search`, `check` added), C2's `the_lucide_search_and_check_match_ios`. D-221 E4
(`.drafts/d221/plan.md`): the You hero (ring, progress pill, next card, "Edit" dropped), `ThemeArt.metrics` at 44 keys,
`TypeScale` ending `displayName, settledPercent, sealNumber, pill`, `SetupFacts` (reads the app-lock facts), the
`.drafts/d221/art.py` generator. Everything here sits BELOW E4's `NextStepCard` / `UsernameCard` / `DeletionPendingCard`
on You, and on the pages behind the Appearance row.

**Rows taken (audit `.superpowers/sdd/lookbook-audit/consolidated.md`, unit S1):**
- L56 icon tiles on You's five rows: `user-round`, `sun`, `shield`, `download`, `info` (four new Lucide drawings).
- L57 row titles at 600 and the list card hugging its rows.
- L58 row summaries: the theme's separator, a live app-lock line, the phone count when signed in.
- L59 Appearance: labels, theme row and mode control on the ground; only Text size is a card.
- L60 theme swatches: each theme's own dark art (Q9 a: always dark, whatever the mode).
- L61 theme card and swatch square in Vardr Labs.
- L62 one drawn System / Light / Dark control on both shells (Norse notched: the consolidator's call, his 2026-10-15 rule).
- L63 one drawn text-size slider on both shells.
- L64 a live sample line, its amount from core.
- L65 the two "A"s: large bold, both in the text colour.
- L66 no line under the mode control outside Norse (Q10 a); "Norse is always dark." stays.

**L-D217 rows taken:** none are routed to S1.

**Left out, and why:**
- The Haptics row under Text size (LB 396): E3 (D-224) owns the Haptics switch; it adds a list card there with a
  `SettingsTile`-style row (this plan's `SettingsRow` is reusable).
- Security, Your data, About, Profile pages (L67 to L76): S2. Their own rows are S2's.
- The You hero, avatars, next card: E4.
- Theme cards' accessibility roles stay as today (iOS button with the selected trait, Android `Role.RadioButton`):
  working and platform idiom, not a Look Book row.

## Design

1. **Icon tiles (L56).** `SettingsTile(glyph:)`: a `settingsTile` (33) footprint in `TileShape.of(theme, person: false)`
   through C2's `TileClip` (Aurora rounded 0.3 of the size = 9.9, the LB's 8 x 1.28 = 10.24; Norse C2's diamond drawn at
   `TileClip.drawnSide` with the 1 pt `norseEdge`, overflowing 6.83 a side, inside the list's 15 side padding and the
   rows' 10; Vardr Labs square), fill `accentSoft`, the Lucide glyph in `accent` at `settingsGlyph` (19) with
   `LucideStroke.width`, upright in every shape. Decorative (hidden from VoiceOver and TalkBack). Map
   `SettingsSection.glyph`: profileAccount `user-round`, appearance `sun`, security `shield`, data `download`, about
   `info`. The four new drawings are copied VERBATIM from upstream (`<circle>` written as the two-arc path from its top,
   the convention `user-round` and `circle-check` use).
2. **The list (L57).** Rows: tile, `rowGap` 13, the title in `TypeRole.name(16)` / `Type.name(dark, 16)` over the
   summary at `TypeScale.detail` in `textDim` (one line, ellipsis), `RowChevron`; vertical padding `rowPadV` 10. The card:
   `glassCard` at padding 0 with the content padded `listPadH` 15 / `listPadV` 3 (LB `.list` 2 px 12 px). Android's
   `Modifier.glassCard` gains `padding: Int = 16` (today it always pads 16 where iOS's You list padded 14: a twin
   difference that goes). Hairline dividers between rows, as today.
3. **Summaries (L58), one pure function per shell, `YouRows.parts(section, facts) -> [String]`,** joined on screen with
   `Separator.text(parts, size: TypeScale.detail)` / `Separator.annotated(parts)` (Norse's gold ᛫); spoken as
   `title, part, part` (the separator is never read aloud). Facts: signed in, the method's label, the phone count, the
   theme, the stored mode, the lock timeout, whether the phone can lock, the marketing version.
   - Profile and account: no account `["No account"]`; signed in `[method label or "Signed in"]` plus `"1 phone"` /
     `"N phones"` when the count is known and at least 1. **DECIDED (controller, for the founder): no account keeps "No
     account", not the LB's "Name and picture · no account"**, because that section holds only Sign in (name and
     picture live on Profile, opened from the hero).
   - Appearance: `[theme, mode]`, Norse `["Norse", "Dark"]` (the LB's JS line 472; today Norse shows "Norse" alone).
     The stored mode's label otherwise ("Aurora · System").
   - Security: one live part from the lock: "App lock immediately", "App lock after 1 minute", "... after 2 minutes",
     "... after 5 minutes", "... after 15 minutes", "App lock off", and on a phone that cannot lock "App lock needs a
     phone lock" (**DECIDED: one wording on both shells**; each shell's own long line on the Security page keeps its
     platform's words). Sources: iOS `@AppStorage(LockTimeout.storageKey)` and `AppLock.available`; Android
     `AppLock.timeout` (state) and `AppLock.available(context)`, re-read on resume, as E4's `SetupFacts` reads them.
   - Your data: `["Export, import, currency"]` (unchanged).
   - About: `[version, "promises", "licences"]` (LB 389). **DECIDED: the build number leaves the row** (it differs per
     shell by CLAUDE.md 10.3; About's own page keeps "Version 0.4.0 (6)"), so the row is identical on both phones.
   - The phone count: `Phones.activeCount(docs)` counts this account's device documents not marked `signedOut`; read
     once per hub load, signed in only, from `users/{uid}/devices` with `Source.SERVER` / `source: .server` (the
     collection Your phones already lists; no rules change, no new origin, nothing logged). A failed read leaves the
     count out (never "0 phones").
4. **Appearance on the ground (L59).** Top to bottom, 16 apart (the page's spacing), each label 8 above its content:
   "Theme" (`.sectionLabel()` / `SectionLabel`) and the three theme cards on the ground; "Light or dark" and the mode
   control on the ground, then "Norse is always dark." in Norse only; "Text size" and ONE `glassCard` holding the slider
   row, the sample line and the caption. Measured (art.py 25): theme names (`text`) 10.08 or more, labels and the
   Norse line (`textDim`) 4.54 or more, the chosen card's `accent` border 3.67 or more (3:1) on every page ground's
   worst pixel.
5. **Theme cards and swatches (L60, L61, Q9 a).** The card: `ThemeArt.controlShape(theme)` of the CURRENT theme
   (Aurora 14, Norse notched 8, Vardr Labs square), a 2 pt border (`accent` chosen, `hairline` not), padding `themePad`
   8, the swatch then the name (`TypeRole.name(14)`, `text`) `themeGap` 6 apart. The swatch: `swatchHeight` 59, clipped to
   `ThemeArt.panelShape(theme, radius: swatchRadius 10)` of the current theme (Vardr Labs square through C2), drawing
   its OWN theme's dark art at a still pose, whatever the mode or the phone (`ThemeCard.previewDark` and the phone-style
   reads go):
   - Aurora: `NightSky`, then one still `Canvas` calling `ArtDraw.curtains(&ctx, size, Curtains.still, night: true,
     curtainStrength: Strength.of("auroraHeroCurtains"), starStrength: Strength.of("auroraHeroStars"))` (the hero's
     full-strength night; Android `nightSkyBody()` made `internal` and `ArtDraw.curtains` at the same arguments).
   - Norse: the Norse set's `bg`, a 1 pt inner border in the Norse set's `accent`, and `Runes.balance + expenses +
     groups + settle` (ᚠᚷᛗᛏ) centred in `TypeRole.rune(swatchRune 20)` / `Type.rune` in that accent, tracked
     `swatchRuneTracking` 0.12 em. **DECIDED (controller, for the founder): the runes appear in the Norse swatch in
     every theme**, Vardr Labs included, because the swatch is Norse's own preview and Q9 a chose the LB's swatch; the
     Vardr Labs theme still draws no runes of its own.
   - Vardr Labs: the vardrDark set's `bg`, dots at `ArtGeometry.gridDots(size, pitch: swatchGridPitch 8)` radius
     `swatchGridDot` 0.9 in the vardrDark `ring`, and `FrameMarks(inset: swatchMarkInset 6, length: swatchMarkLength 10)`
     1 pt in the vardrDark `accent`.
   Colours by token NAME resolved in a fixed set (`Tokens.hex(Tokens.palette[name]!, set)` / `Tok.x.of(set)`), never a
   new hex. Still: a plain `Canvas` / `drawBehind`, no `ArtCanvas` ticking. Decorative.
6. **The mode control (L62, L66).** `ModeControl`: a touch row `touchTarget` 48 tall (iOS 44 pt and Android 48 dp
   minimums; one value so both draw the same) holding the track `modeTrack` 40 centred, `accentSoft`, shaped by C2's
   `ThemeArt.pillShape(theme)` (Aurora capsule, Norse notched 4, Vardr Labs square: shape only, never C2's pill
   paint); three equal segments; the chosen one inset `modeInset` 4, filled `surface`, the same pill shape, a black
   shadow at `segmentShadowAlpha` 0.15, radius 2, y 1 (Android: `shadow(2.dp, shape)` with the default black colours,
   recorded as residue). Words `TypeRole.name(TypeScale.segment)` (14 at 600): chosen `text`, others `textDim`
   (**DECIDED (controller, for the founder): the others' words dim**, a second cue the LB lacks: the chosen segment
   against the track measures 1.05 to 1.24, art.py 26; both colours hold 4.5). Each segment's touch area is the full 48
   row and a third of the width. Lines limited to one, shrinking to 0.7 before an ellipsis at large text.
   `ModeChoice.shown(theme, stored) -> (chosen, enabled)`: Norse `(.dark, false)` (the LB shows Dark chosen in Norse;
   the stored choice is untouched and returns with another theme), else `(stored, true)`. Disabled: the whole control at
   `ButtonLook.disabledAlpha`. Under it, ONLY in Norse, "Norse is always dark." (Q10 a); `AppearanceMode.detail` and its
   three lines go from both shells.
   - iOS accessibility: `.accessibilityRepresentation { Picker(AppearanceCopy.cards[1], selection:) { ... }
     .pickerStyle(.segmented).disabled(!enabled) }`: VoiceOver reads the native segmented control (each segment a
     button, "selected", "n of 3"). With no visible segmented control left, `TitleStyle.segmentAttributes`,
     `styleSegments`, `segmentedControls` and their proxy lines in `TitleStyle.apply` go, with
     `testTheSegmentedPickerDrawsTheBodyFace` and the segment lines of `testTheBarButtonFontIsWiredThroughApply`.
   - Android: the Row `selectableGroup()`, each segment `selectable(selected, enabled, role = Role.RadioButton,
     onClick)`, so TalkBack reads "Light, radio button, not selected, 2 of 3" and the dump shows `RadioButton` nodes.
7. **The slider (L63, L65).** `StepSlider` over `TextSize` (-2...3, six steps), in a 48 touch row: the track
   `sliderTrack` 5 in `ringTrack`, the filled part to the thumb's centre in `accent`, both shaped by C2's
   `ThemeArt.meterShape(theme)`; the thumb `sliderThumb` 20, filled `surface`, ringed `sliderRing` 2.5 in `accent`,
   shaped `TileShape.of(theme, person: true)` (circle, diamond, square). **DECIDED (controller, for the founder): the
   track and thumb take the theme's shapes** (the LB rounds both in every theme; C2 squares every bar in Norse and Vardr
   Labs and its stated goal is nothing round inside a square Vardr Labs card). No tick marks (the LB draws none). A tap
   or drag anywhere on the row picks `StepSlider.step(atX:width:thumb:)`: `v = lo + (x - thumb/2) / (width - thumb) x
   (hi - lo)`, `floor(v + 0.5)` clamped (half up on both: Swift's `.rounded()` and Kotlin's `roundToInt()` differ at
   -1.5, so neither is used); only a NEW step is written. `StepSlider.centre(step:width:thumb:)` places the thumb.
   `StepSlider.adjusted(step, by:)` clamps.
   - The A's: `TypeScale.rangeSmall` 14 at 400 and `TypeScale.rangeLarge` 23 at 700, both `text`, FIXED (iOS
     `TypeRole.bodyFixed(_:weight:)`, gaining the weight; Android `dp.toSp()` with `FontWeight.Bold`), hidden from
     accessibility as today.
   - iOS accessibility: `.accessibilityRepresentation { Slider(value:in:step:) }` with the label "Text size" and the
     value `TextSize.accessibilityValue(step)`, plus `.accessibilityAdjustableAction` driving `StepSlider.adjusted`, so
     VoiceOver's swipe up and down move one step.
   - Android: `semantics { contentDescription = "Text size"; stateDescription = TextSize.accessibilityValue(step);
     progressBarRangeInfo = ProgressBarRangeInfo(step, MIN..MAX, steps = MAX - MIN - 1); setProgress { v -> write the
     clamped step from floor(v + 0.5); true } }` and `focusable()`, so TalkBack reads a `SeekBar` it can adjust.
8. **The sample (L64).** Between the slider row and the caption: `Separator.text(["Dinner at Thalassa", amount], size:
   TypeScale.sample)` in `TypeRole.name(TypeScale.sample)` (19 at 600, `text`) / `Text(Separator.annotated(parts),
   style = Type.name(dark, TypeScale.SAMPLE))`, scaling with the text size, so it grows as the thumb moves (the root's
   size override, E1 section 4). The amount: `ffiMoneyFormat(minorUnits: ffiMoneyParseMinor(digits: "2400",
   currencyCode: "INR"), currencyCode: "INR", locale: AppLocale.current)`, computed once. **DECIDED (controller, for the
   founder): the Look Book's own line, ₹2,400 in INR**, formatted by core in the phone's locale; the alternative is the
   phone's default currency at the same whole amount. Spoken as written (accessibility reads the amount).

## Global Constraints

- Values come from `.drafts/d222/art.py` (`.drafts/d221/art.py` extended with sections 24 to 29; run `python3 -I
  .drafts/d222/art.py`, output `.drafts/d222/art.out`, "Floors failing: 0"). Written and run by the planner. Never retype
  a value that is not in this plan or that output; extend the generator for any new one.
- `ThemeArt.metrics` gains 25 keys after D-221's 44 (69), Swift Double, Kotlin twin, in this order:
  `"settingsTile": 33, "settingsGlyph": 19, "listPadH": 15, "listPadV": 3, "rowPadV": 10, "rowGap": 13, "themePad": 8,
  "themeGap": 6, "swatchHeight": 59, "swatchRadius": 10, "swatchRune": 20, "swatchRuneTracking": 0.12,
  "swatchGridPitch": 8, "swatchGridDot": 0.9, "swatchMarkInset": 6, "swatchMarkLength": 10, "modeTrack": 40,
  "modeInset": 4, "touchTarget": 48, "segmentShadowAlpha": 0.15, "segmentShadowRadius": 2, "segmentShadowY": 1,
  "sliderTrack": 5, "sliderThumb": 20, "sliderRing": 2.5`.
- `TypeScale` gains, after D-221's `pill`, in this order: `segment = 14`, `sample = 19`, `rangeSmall = 14`,
  `rangeLarge = 23` (and `all` lists them).
- Tokens by name only: `accent`, `accentSoft`, `surface`, `text`, `textDim`, `hairline`, `ringTrack`, `bg`, `ring`,
  `norseEdge` (ThemeArt.hex). No new token, no new hex.
- Copy (both shells, verbatim, no em dashes): "No account", "Signed in", "1 phone", "\(n) phones", "App lock
  immediately", "App lock after 1 minute", "App lock after 2 minutes", "App lock after 5 minutes", "App lock after 15
  minutes", "App lock off", "App lock needs a phone lock", "Export, import, currency", "promises", "licences", "Dinner
  at Thalassa", "Norse is always dark." (kept), "Starts at your phone's size; changes SKIPTI only." (kept). Removed:
  "Follows your phone's light and dark setting.", "Always light.", "Always dark.", `YouCopy.securityLine` "App lock and
  erasing".
- Lucide `sun`, `shield`, `download`, `info`: read `https://unpkg.com/lucide-static@latest/icons/<name>.svg` with
  WebFetch (reading only, nothing executed), copy the path data VERBATIM (circles as two-arc paths from the top). NOTICE's
  Lucide entry goes from twelve drawings to sixteen, naming the four and "the icon tiles on You's rows"; read Lucide's
  LICENSE for which of the four are on the Feather-derived list and word NOTICE and the `scripts/licences.py` comment and
  notice string as it says; regenerate `native/licences/licences.json` (`python3 scripts/licences.py`, then `--check`).
- No new Swift file, no new test file (no pbxproj edit). No new dependency. No core change.
- Shapes only through `ThemeArt.controlShape`, `panelShape`, `pillShape`, `meterShape`, `TileClip`; type only through
  `TypeRole` / `Type` and `TypeScale` (no literal sizes on screens; the fixed A's read `TypeScale.rangeSmall/Large`).
- No em dashes anywhere (code, comments, copy, commits). Commits end `[skip ci]`; explicit paths only; `git diff
  --cached --stat` before every commit, refuse if it holds a file you did not stage; no attribution of any kind. Never
  `git stash`, `reset`, `checkout -- <file>` or `restore`.
- iOS tests: boot `AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51` first, `-collect-test-diagnostics never` on every `xcodebuild
  test`, one class at a time (`-only-testing:SkiptiTests/<Class>`), shut it down when done; never two iOS test runs at
  once in this checkout. Android: `cd android && zsh -lic './gradlew :app:testPlayDebugUnitTest --tests
  "com.vardrlabs.skipti.<Class>"'`; check result XML mtimes before quoting a tally. Stop your own Monitors and watchers
  before replying.
- Every new guard is sabotaged (matrix below). A red test fails, never crashes. The controller runs the full gate.
- Verify every file:line here against the tree before editing: D-219, D-220 and D-221 land first and move lines (E4
  rewrites the top of `AccountHubScreen` on both shells and `YouCopy`). Source-guard counts that S1 changes (for
  example `TypeThemeTests`' `.font(TypeRole.body(TypeScale.detail))` count for AccountHubScreen.swift, 7 today) are
  re-counted and the pin updated in the same task, naming the sites.

---

### Task 1: Values, copy, pure decisions and the four drawings, both shells (one agent; runs first)

**Files:**
- iOS: `ios/Skipti/App/ThemeArt.swift` (metrics only), `TypeSystem.swift` (`TypeScale` four constants;
  `bodyFixed(_:weight:)` and `bodyFixedUIFont(_:weight:theme:)`, default 400), `LucideIcon.swift` (cases `sun`,
  `shield`, `download`, `info` and their verbatim paths), `AccountHubScreen.swift` (pure enums only:
  `SettingsSection.glyph`, `YouRowFacts`, `YouRows.parts(_:_:)` / `.spoken(title:parts:)` / `.securityLine(_:available:)`,
  `ModeChoice.shown(theme:stored:)`, `StepSlider.step(atX:width:thumb:)` / `.centre(step:width:thumb:)` /
  `.adjusted(_:by:)`, `SampleLine.parts(amount:)` and `.amount()`), `Phones.swift` (`PhonesCopy.count(_:)`,
  `Phones.activeCount(_ docs:)`).
- Android: `ThemeArt.kt` (metrics), `Tokens.kt` (`TypeScale` four constants), `TabIcons.kt` (`PATHS` four entries,
  `TabIcons.Sun`, `Shield`, `Download`, `Info`), `AccountHubScreen.kt` (the pure twins:
  `SettingsSection.glyphName`, `YouRowFacts`, `YouRows`, `ModeChoice`, `StepSlider`, `SampleLine`), `Phones.kt`
  (`PhonesCopy.count`, `Phones.activeCount(docs)`).
- `NOTICE`, `scripts/licences.py`, `native/licences/licences.json`.
- Tests: iOS `ThemeArtTests`, `TypeThemeTests`, `BalanceLineTests` (Lucide goldens, beside today's
  `AppearanceCopy.summary` test, which becomes the `YouRows` test), `AppearanceTests` (`StepSlider`, `ModeChoice`),
  `AccountTests` (phone count). Android `ThemeArtTest`, `TypeThemeTest`, `HomeTabsTest` (the `PATHS` golden),
  `YouTest` (YouRows), `AppearanceTest` (StepSlider, ModeChoice), `AccountTest` (phone count).

No view changes in this task (Tasks 2 and 3). `AppearanceCopy.summary` stays until Task 2 and 3 replace its readers;
then it goes.

- [ ] **Step 1: Tests first.**
  - `ThemeArtTests.testS1ArtValues` / `the_s1_art_values`: the 25 keys and values; `testEveryArtValueIsPresent` at 69;
    Android `the_art_values_match_ios` at 69 (it reads the Swift table).
  - `TypeThemeTests.testTheTypeScaleIsTheAgreedTable` extended with the four constants in order (Android's parity test
    already reads the Swift `TypeScale`); `testTheFixedFaceTakesAWeight`: `bodyFixedUIFont(23, weight: 700, theme:)` is the
    body face at 700 and 23 pt at `.accessibilityExtraExtraExtraLarge` too (unscaled), per theme; the twin pins the
    Android A style's weight in `AppearanceTest` in Task 3.
  - Lucide: iOS `BalanceLineTests` pins the four new `pathData` arrays (pasted from the WebFetch read) and the existing
    every-string-parses and non-blank-template-image tests cover them; Android `HomeTabsTest`'s `PATHS` golden gains the
    four; **C2's `the_lucide_search_and_check_match_ios` becomes `every_lucide_path_matches_ios`**: it parses every
    `case .x:` block of `LucideGlyph.pathData` in LucideIcon.swift (the string literals up to the next `case .` or the
    switch's end), maps the case to its raw name, and compares the whole map with `TabIcons.PATHS` (a missing or extra
    name fails).
  - `SettingsSection.glyph`: iOS `SettingsSection.allCases.map(\.glyph.rawValue)` and Android
    `SettingsSection.entries.map { it.glyphName }` both equal `["user-round", "sun", "shield", "download", "info"]`; Android
    also asserts each name is a `TabIcons.PATHS` key.
  - `YouRows` (one golden table pinned in both tests): no account `["No account"]`; signed in with Apple and 2 phones
    `["Signed in with Apple", "2 phones"]`; signed in, method unknown, count 1 `["Signed in", "1 phone"]`; signed in,
    count nil `["Signed in with Google"]`; count 0 is left out (the negative); Aurora System `["Aurora", "System"]`;
    Vardr Labs Light `["Vardr Labs", "Light"]`; Norse with a stored Light `["Norse", "Dark"]`; every `LockTimeout` with
    `available` true to its line, and off; `available` false with any timeout `["App lock needs a phone lock"]`; data;
    about `["0.4.0", "promises", "licences"]`; `spoken(title: "Security", parts: ["App lock off"])` ==
    "Security, App lock off" and no part contains a separator rune or a middle dot.
  - `ModeChoice.shown`: Norse with each stored mode is `(.dark, false)`; Aurora and Vardr Labs return the stored mode,
    enabled.
  - `StepSlider` (goldens from art.py 29, width 300, thumb 20): `step(atX:)` at -20, 0, 10, 37.9 is -2; 38 and 66 are
    -1; 150 is 1; 290, 300, 400 are 3; `centre` -2..3 is 10, 66, 122, 178, 234, 290; `adjusted` (-2, -1) -2, (-2, +1) -1,
    (0, +1) 1, (3, +1) 3, (3, -1) 2.
  - `SampleLine.parts(amount: "₹2,400.00")` == `["Dinner at Thalassa", "₹2,400.00"]`; a source guard that
    `SampleLine.amount` calls `ffiMoneyParseMinor(` and `ffiMoneyFormat(` and nothing formats a number in Swift / Kotlin
    (no `NumberFormatter`, `String(format:`, `NumberFormat`, `"%`).
  - Phones: `PhonesCopy.count(1) == "1 phone"`, `count(3) == "3 phones"`; `Phones.activeCount` over three docs, one
    `signedOut: true`, is 2; an empty list is 0.
- [ ] **Step 2:** Run each class alone: red for the right reason (declare with WRONG values first so the red is an
  assertion, not a compile failure).
- [ ] **Step 3:** Implement. Regenerate the licence inventory; update NOTICE. Green on iOS `ThemeArtTests`,
  `TypeThemeTests`, `BalanceLineTests`, `AppearanceTests`, `AccountTests`, `AboutTests`; Android `ThemeArtTest`,
  `TypeThemeTest`, `HomeTabsTest`, `YouTest`, `AppearanceTest`, `AccountTest`, `PaletteParityTest`; `python3
  scripts/licences.py --check`.
- [ ] **Step 4:** Sabotage rows V1 to V12 and P1 to P4. Report them.
- [ ] **Step 5:** Commit `D-222 T1: You's row glyphs and summaries, the step slider and mode decisions, Lucide sun, shield, download and info [skip ci]`.

### Task 2: iOS views (after Task 1; parallel with Task 3; files disjoint)

**Files:** `ios/Skipti/App/AccountHubScreen.swift` (the You list rows: `SettingsTile`, `SettingsRow`; `reload()` reads
the phone count and the lock's availability; the Appearance items: `ThemeCard` and `ThemeSwatch` rewritten, `ModeControl`,
`TextSizeSlider`, the sample; `AppearanceCopy.summary` removed), `ios/Skipti/App/Phones.swift` (`Phones.activeCount() async
-> Int?`), `ios/Skipti/App/Appearance.swift` (`AppearanceMode.detail` removed), `ios/Skipti/App/ThemeArt.swift`
(`TitleStyle` segment styling removed), `ios/Skipti/App/Profile.swift` (`YouCopy.securityLine` removed, nothing else);
tests `AppearanceTests.swift`, `TypeThemeTests.swift`, `BalanceLineTests.swift`.

- [ ] **Step 1: Tests first** (source guards read the Swift files; each names file and anchor):
  - `testYouRowsDrawTilesAndLiveSummaries`: the row draws `SettingsTile(glyph: section.glyph)`; the title
    `TypeRole.name(16)`; the summary `Separator.text(YouRows.parts(`; the row's `.accessibilityLabel(` from
    `YouRows.spoken(`; the list card `.glassCard(padding: 0)` after `.padding(.horizontal, ThemeArt.m("listPadH"))` and
    `.padding(.vertical, ThemeArt.m("listPadV"))`; no `YouCopy.securityLine`, no `AboutInfo.versionLine(` in the file.
  - `testTheSettingsTileIsTheThemesTile`: `SettingsTile` reads `TileShape.of(`, `TileClip.`, `TileClip.drawnSide(`,
    `ThemeArt.m("settingsTile")`, `ThemeArt.m("settingsGlyph")`, `Tokens.accentSoft`, `Tokens.accent`,
    `.accessibilityHidden(true)`.
  - `testAppearanceSitsOnTheGround`: in `SettingsSectionScreen`, the `.theme` and `.appearanceMode` cases call no
    `card {` and no `.glassCard(`; `.textSize` uses exactly one `card {`; the order inside it is the slider, the sample
    (`SampleLine`), the caption; no `.pickerStyle(.segmented)` outside `ModeControl`'s `.accessibilityRepresentation`
    and no `Slider(` outside `TextSizeSlider`'s.
  - `testTheModeControlIsDrawnAndAccessible`: `ModeControl` reads `ModeChoice.shown(`, `ThemeArt.pillShape(`,
    `ThemeArt.m("touchTarget")` as its minimum height, `ThemeArt.m("modeTrack")`, `ThemeArt.m("modeInset")`,
    `TypeScale.segment`, `.minimumScaleFactor(`, `ButtonLook.disabledAlpha`, and `.accessibilityRepresentation {`
    holding `Picker(` and `.pickerStyle(.segmented)`; the Norse line is `AppearanceCopy.norseAlwaysDark` under `if`
    of `forcesDark` with no else branch; `AppearanceMode` has no `detail`.
  - `testTheSliderIsDrawnAndAdjustable`: `TextSizeSlider` reads `StepSlider.step(atX:`, `StepSlider.centre(`,
    `ThemeArt.meterShape(`, `TileShape.of(` (person: true), `Tokens.ringTrack`, `ThemeArt.m("sliderThumb")`,
    `"sliderRing"`, `"sliderTrack"`, `"touchTarget"`, `.accessibilityRepresentation {` holding `Slider(`,
    `.accessibilityAdjustableAction`, `TextSize.accessibilityValue(`; the A's `TypeRole.bodyFixed(TypeScale.rangeSmall)`
    and `TypeRole.bodyFixed(TypeScale.rangeLarge, weight: 700)`, both `Tokens.text`.
  - `testSwatchesAreEachThemesDarkArt`: `ThemeSwatch` has a branch per theme drawing `NightSky()` and
    `ArtDraw.curtains(` with `night: true` and the hero strengths; the Norse runes through `TypeRole.rune(` and
    `ThemeArt.m("swatchRune")`; `ArtGeometry.gridDots(` with `"swatchGridPitch"` and `FrameMarks(`; colours resolved in
    `.norse`, `.vardrDark` sets; clipped to `ThemeArt.panelShape(`; the card shaped `ThemeArt.controlShape(`; no
    `previewDark`, no `phoneIsDark`, no `ArtCanvas(` in the file. Replace `testThemePreviewsFollowTheStoredModeAndThePhone`
    with this (the decision it pinned is gone by Q9 a).
  - `testTheModeControlReadsAsASegmentedPicker` (hosted, best effort): render `ModeControl` for Aurora / Dark in a
    `UIHostingController` inside a window, lay it out, and walk the hosting view's accessibility elements: three with
    labels System, Light, Dark, the Dark one carrying `.selected`; for Norse every element carries `.notEnabled`. If the
    SwiftUI accessibility tree is not reachable from a hosted test, delete the test, say so in the report, and the
    source guard above stands (the look then checks VoiceOver order by Accessibility Inspector if available).
  - `TypeThemeTests`: remove `testTheSegmentedPickerDrawsTheBodyFace` and the three segment lines of
    `testTheBarButtonFontIsWiredThroughApply`; add `testNoSegmentStylingIsLeft` (ThemeArt.swift has no
    `segmentAttributes`, `styleSegments`, `UISegmentedControl`); re-count the AccountHubScreen.swift pins.
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** Design 1 to 8 (iOS). `reload()` reads `Phones.activeCount()` when signed in (a failed read
  is nil) and `AppLock.available`; the security line follows `@AppStorage(LockTimeout.storageKey)` live. The slider
  writes `textStep` only on a new step; the drag is a `DragGesture(minimumDistance: 0)` on the 48 row. The swatch's
  colours read `Tokens.hex(Tokens.palette[name]!, set)` for `.norse` / `.vardrDark`. Update the doc comments.
- [ ] **Step 4:** Green on `AppearanceTests`, `TypeThemeTests`, `BalanceLineTests`, `ThemeArtTests`, `AccountTests`,
  `ProfileTests`. Sabotage rows I1 to I12.
- [ ] **Step 5:** Commit `D-222 T2: iOS You rows with tiles and live summaries, Appearance on the ground with drawn controls and swatches [skip ci]`.

### Task 3: Android views (after Task 1; parallel with Task 2; files disjoint)

**Files:** `android/app/src/main/java/com/vardrlabs/skipti/AccountHubScreen.kt` (the twins of Task 2's views; the
`FilterChip`, `Slider`, `SliderDefaults` and `chipColors` uses there go), `Aurora.kt` (`Modifier.glassCard(dark, radius,
tint, padding: Int = 16)`), `ThemeMotion.kt` (`nightSkyBody` made `internal`, nothing else), `Appearance.kt`
(`AppearanceMode`'s `detail` removed), `Phones.kt` (`suspend fun activeCount(): Int?` with
`get(Source.SERVER).awaitTask()`); tests `AppearanceTest.kt`, `YouTest.kt`, `TypeThemeTest.kt`.

- [ ] **Step 1: Tests first**, the twins of Task 2's guards on the Kotlin files (`SettingsTile` through `tileShape(`,
  `tileDrawnSide(`, `tileEdge(`; the row title `Type.name(dark, 16)`; the summary `Separator.annotated(YouRows.parts(`;
  the list `glassCard(dark, padding = 0)` then `padding(horizontal = ThemeArt.m("listPadH").dp, vertical =
  ThemeArt.m("listPadV").dp)`; Appearance on the ground with one `Card(dark)` for Text size; no `FilterChip(`, no
  `Slider(` and no `SliderDefaults` in AccountHubScreen.kt; swatches through `nightSkyBody()`, `ArtDraw` curtains at the
  hero strengths, `Type.rune` / `RuneSpans` on the four runes, `ArtGeometry.gridDots(` and `frameMarks(`, colours `.of(
  ThemeSet.NORSE)` / `.of(ThemeSet.VARDR_DARK)`; no `previewDark`, no `UI_MODE_NIGHT` read, no `artCanvas(` in the
  file), plus:
  - `the_mode_control_is_a_radio_group`: the Row carries `selectableGroup()`; each segment `selectable(` with
    `role = Role.RadioButton` and `enabled =` from `ModeChoice.shown`; `heightIn(min = ThemeArt.m("touchTarget").dp)`;
    `ThemeArt.pillShape(`; `Type.name(dark, TypeScale.SEGMENT`; the dim through `ButtonLook.DISABLED_ALPHA`; the
    segment shadow `shadow(ThemeArt.m("segmentShadowRadius").dp`.
  - `the_slider_is_an_adjustable_seekbar`: `progressBarRangeInfo = ProgressBarRangeInfo(`, `setProgress {`,
    `stateDescription = TextSize.accessibilityValue(`, `contentDescription = AppearanceCopy.CARDS[2]`, `focusable()`,
    `StepSlider.step(` in the pointer handler, `heightIn(min = ThemeArt.m("touchTarget").dp)`, `ThemeArt.meterShape(`;
    the A's `FontWeight.Bold` on the large one and `Tok.text(dark)` on both.
  - `glass_card_takes_a_padding` (Aurora.kt): `padding: Int = 16` and each branch pads `padding.dp`; today's 16 call sites
    unchanged.
  - Update `YouTest`'s `AppearanceCopy.summary` lines to `YouRows` (Task 1 pinned the table) and `TypeThemeTest`'s
    detail-size pin (the Norse line and the caption still draw `DETAIL_SIZE`: count 2).
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** the twins. The slider: `pointerInput` with `detectTapGestures` and
  `detectHorizontalDragGestures` mapping x to `StepSlider.step`, writing `Appearance.setTextStep` only on a new step;
  `setProgress` writes the clamped `floor(v + 0.5)`. The phone count reloads with the hub; the lock line reads
  `AppLock.timeout` and `AppLock.available(context)` (re-read on `ON_RESUME`).
- [ ] **Step 4:** Green on `AppearanceTest`, `YouTest`, `TypeThemeTest`, `ThemeArtTest`, `HomeTabsTest`, `SkiptiBarTest`,
  `ThemeMotionTest`. `make android-app` once (the APK builds). Sabotage rows A1 to A12.
- [ ] **Step 5:** Commit `D-222 T3: Android You rows with tiles and live summaries, Appearance on the ground with drawn controls and swatches [skip ci]`.

### Task 4: Look (after Tasks 1 to 3 and the sabotage run; controller dispatch)

See **Look**. Write `.drafts/d222/look/look-report.md` and pairs in `.drafts/d222/look/pairs/`.

---

## Sabotage matrix

Sized to the risk (CLAUDE.md section 4 speed rule 2): each new guard once, each value table once. Two lanes in their own
worktrees outside the checkout (`/private/tmp/skipti-d222-ios`, `/private/tmp/skipti-d222-android`; own DerivedData;
`android/local.properties` copied), runner copied from `.drafts/d221/sabotage.py` (or `.drafts/d217/sabotage.py lanes`)
to `.drafts/d222/sabotage.py`. Boot the iOS test simulator first. Each row: back up, sabotage through an asserted anchor,
zero compile errors, exactly the listed tests red, restore, `cmp`.

| row | file | sabotage | expected red |
|---|---|---|---|
| V1 | ThemeArt.swift | `"modeTrack": 40` to `36` | testS1ArtValues |
| V2 | TypeSystem.swift | `sample = 19` to `17` | testTheTypeScaleIsTheAgreedTable |
| V3 | TypeSystem.swift | `bodyFixedUIFont` ignores `weight` (400) | testTheFixedFaceTakesAWeight |
| V4 | LucideIcon.swift | one coordinate in `shield` changed | BalanceLineTests Lucide golden |
| V5 | AccountHubScreen.swift | `.security` glyph `.shield` to `.bell` | SettingsSection glyph test |
| V6 | AccountHubScreen.swift | `YouRows` shows a count of 0 | YouRows table (count 0 negative) |
| V7 | AccountHubScreen.swift | Norse appearance parts drop "Dark" | YouRows table |
| V8 | AccountHubScreen.swift | `securityLine` ignores `available` | YouRows table |
| V9 | AccountHubScreen.swift | `StepSlider.step` uses `.rounded()` | StepSlider goldens (37.9 / 38) |
| V10 | AccountHubScreen.swift | `adjusted` not clamped at 3 | StepSlider goldens |
| V11 | AccountHubScreen.swift | `ModeChoice.shown(.norse)` enabled | ModeChoice test |
| V12 | Phones.swift | `activeCount` counts signed-out phones | AccountTests phone count |
| P1 | ThemeArt.swift (Android lane) | `"sliderRing": 2.5` to `3` in the Swift file only | the_art_values_match_ios |
| P2 | TypeSystem.swift (Android lane) | `rangeLarge = 23` to `22` in the Swift file only | the_type_scale_is_the_ios_table |
| P3 | LucideIcon.swift (Android lane) | the `info` path altered in the Swift file only | every_lucide_path_matches_ios |
| P4 | TabIcons.kt | the `download` path altered | HomeTabsTest PATHS golden, every_lucide_path_matches_ios |
| I1 | AccountHubScreen.swift | the row loses `SettingsTile(` | testYouRowsDrawTilesAndLiveSummaries |
| I2 | AccountHubScreen.swift | the summary back to `Text(verbatim:` of a joined string | testYouRowsDrawTilesAndLiveSummaries |
| I3 | AccountHubScreen.swift | `SettingsTile` draws `Circle()` in every theme | testTheSettingsTileIsTheThemesTile |
| I4 | AccountHubScreen.swift | the list back to `.glassCard(padding: 14)` | testYouRowsDrawTilesAndLiveSummaries |
| I5 | AccountHubScreen.swift | `.theme` wrapped in `card {` | testAppearanceSitsOnTheGround |
| I6 | AccountHubScreen.swift | `ModeControl` loses `.accessibilityRepresentation` | testTheModeControlIsDrawnAndAccessible |
| I7 | AccountHubScreen.swift | the segment's min height `"modeTrack"` instead of `"touchTarget"` | testTheModeControlIsDrawnAndAccessible |
| I8 | AccountHubScreen.swift | the "Always light." line restored under the control | testTheModeControlIsDrawnAndAccessible |
| I9 | AccountHubScreen.swift | `.accessibilityAdjustableAction` removed | testTheSliderIsDrawnAndAdjustable |
| I10 | AccountHubScreen.swift | the large A at 400 | testTheSliderIsDrawnAndAdjustable |
| I11 | AccountHubScreen.swift | the Aurora swatch reads `scheme == .dark` for `night:` | testSwatchesAreEachThemesDarkArt |
| I12 | AccountHubScreen.swift | the sample's amount from `String(format:` | the T1 sample guard |
| A1 | AccountHubScreen.kt | the row loses `SettingsTile(` | twin of I1 |
| A2 | AccountHubScreen.kt | the summary as `parts.joinToString(", ")` | twin of I2 |
| A3 | AccountHubScreen.kt | `SettingsTile` clips `CircleShape` | twin of I3 |
| A4 | Aurora.kt | the GLASS branch pads `16.dp`, not `padding.dp` | glass_card_takes_a_padding |
| A5 | AccountHubScreen.kt | Theme back in `Card(dark)` | twin of I5 |
| A6 | AccountHubScreen.kt | a segment's `role = Role.RadioButton` removed | the_mode_control_is_a_radio_group |
| A7 | AccountHubScreen.kt | `selectableGroup()` removed | the_mode_control_is_a_radio_group |
| A8 | AccountHubScreen.kt | the "Always light." line restored | twin of I8 |
| A9 | AccountHubScreen.kt | `setProgress` removed | the_slider_is_an_adjustable_seekbar |
| A10 | AccountHubScreen.kt | the slider row's `heightIn(min = touchTarget)` removed | the_slider_is_an_adjustable_seekbar |
| A11 | AccountHubScreen.kt | the Vardr Labs swatch uses `ThemeSet.VARDR_LIGHT` | twin of I11 (the swatch guard) |
| A12 | AccountHubScreen.kt | the Android `StepSlider.step` uses `roundToInt()` | AppearanceTest StepSlider goldens |

40 rows: 12 values (V), 4 parity (P, Android lane), 12 iOS views (I), 12 Android (A). Both lanes in parallel; about 30
minutes with the simulator booted.

## Look

Throwaway devices only, against the local emulators: iOS `d214-look` (`xcrun simctl list devices | grep BDC0AE93` for the
full UDID); Android the throwaway AVD `run-look` (run-common; `d217-look` if it still exists; API 34
`system-images;android-34;default;arm64-v8a`, `-gpu host`), deleted after if created. Never AFAF4221, EB725812,
`skipti-shots`, `skipti-smoke` or `Pixel_10_Pro_XL`. Rebuild after the sabotage run before looking; check the dylib and
APK are fresh (mtime, and `strings` for "Dinner at Thalassa"). Data: the local Auth emulator's proof account for the
signed-in rows (the phone count comes from the Firestore emulator's devices).

Pairs (iOS beside Android) in all five sets (Aurora light, Aurora dark, Norse, Vardr Labs light, Vardr Labs dark) unless
stated:
1. You without an account: the five rows with tiles (Norse diamonds with their edge, Vardr Labs squares), titles at 600,
   summaries with the separator (gold ᛫ in Norse), the card hugging its rows.
2. You signed in: "Signed in with Google ᛫ 1 phone" (or the method the proof uses); the Security line after setting the
   lock to Off and to 5 minutes (it changes on return to You).
3. Appearance: the theme row and the mode control on the ground, the swatches as the three dark arts in every set (Aurora
   night curtains and stars, Norse ᚠᚷᛗᛏ in a gold inset, Vardr Labs dots and marks), the chosen card's border, Vardr
   Labs square cards and swatches, Norse notched.
4. Norse: the control dimmed with Dark chosen and "Norse is always dark." under it; Aurora and Vardr Labs: no line.
5. Text size at -2, 0 and +3: the sample growing, the thumb on its step, the A's (small regular, large bold).
6. Accessibility, Android: `adb shell uiautomator dump`: three `android.widget.RadioButton` nodes with `checked` on the
   stored mode and `enabled="false"` in Norse; the slider an `android.widget.SeekBar` with content-desc "Text size";
   every segment's and the slider's bounds at least 48 dp tall (px / density). With TalkBack on (if the AVD has it),
   swipe the slider up once: the step and the sample change. iOS: Accessibility Inspector on the simulator if available
   (Light or dark as a segmented control, Text size as an adjustable slider); else record it for the founder's
   device check.
7. The largest text step with the phone's own largest size (iOS `xcrun simctl ui <UDID> content_size
   accessibility-extra-extra-extra-large`, Android `adb shell settings put system font_scale 2.0`; reset after): segment
   words shrink but stay on one line, the row grows, nothing clips.
8. Android at 360 dp (`adb shell wm density 480` on the 1080-wide AVD; `wm density reset` after): three theme cards fit
   with their names.
Shut every device down after; `pgrep -f qemu-system` empty.

## Expected section 4 residue

- The chosen mode segment differs from its track by 1.05 to 1.24 (art.py 26): the words' colour and the accessibility
  selected state carry the choice; the LB draws it so.
- The segment's shadow is a drop shadow on iOS and an elevation shadow on Android (as C1's +).
- The swatches' curtains are the page's 9 pt blur on a 59 pt swatch (the LB blurs 5 px on 46 px); the founder judges at
  the look.
- The phone count is read from the cloud once per visit to You; offline or failing, the row shows the method only.
- The sample amount is INR in every region (the LB's line); a founder's call to switch to the default currency.
- "No account" stays on the Profile and account row instead of the LB's "Name and picture · no account".
- About's row drops the build number; About's page keeps it.
- The theme cards keep each platform's accessibility role (iOS button with selected, Android radio button).
- iOS VoiceOver behaviour of the drawn controls is proven by source guards (and the hosted test if SwiftUI's tree was
  reachable); no XCUITest target exists.
- The Haptics row on Appearance arrives with E3 (D-224).

## Controller amendments (2026-10-16, binding over the text above)

- **A1 (decision 7 overturned):** the live sample line uses the phone's default currency (the stored
  `money.default-currency` the app already reads; INR when none is set), amount 2,400 in major units converted to
  minor units by core's currency exponent, formatted by core. Reason: the sample previews the user's own text size;
  a European or Japanese user seeing rupees reads it as someone else's data. The title stays "Dinner at Thalassa".
- **A2 (from D-221's re-review, I-1):** FIRST fix of this unit: the Norse diamond tile is 65.9 wide on Android against
  67.7 on iOS because Android's edge is a `border` drawn inside the shape (ThemeArt.kt ~798) while iOS's stroke is
  centred on the outline: draw Android's centred like iOS's (the D-219 + diamond fix is the precedent), a guard and a
  sabotage row; S1's `SettingsTile` Norse diamonds then inherit the right size.
