# D-225 S2 "Security, data, About and Profile" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The remaining settings pages drawn in the Look Book's list style, and every twin difference the D-217 and
D-218 looks found in settings, People, Profile, New group and Add expense closed, so the two phones draw the same rows,
the same switch, the same fields and the same choice pages in every theme.

**Architecture:** One list vocabulary per shell, built once and used everywhere: `ListCard` (S1's list card made a
component), `ListRow` (S1's `SettingsRow` generalised: optional tile, title, summary parts, a trailing chevron, value,
switch or check, a danger tone), `ThemeSwitch` (a drawn switch, as S1 drew the mode control and the slider),
`FieldRow` (iOS's form field, a plain field as a row), and `ChoicePage` (a pushed page of options with a check, the
founder's Q15 a "app lock opening a picker page", reused for every choice a form makes). Values and pure decisions first
on both shells, compared by parity tests that read the Swift files; then each shell's lane (shared components, then the
settings pages, then the forms), the two lanes in parallel on disjoint files. iOS's `Form` screens in scope become a
`ScrollView` of `ListCard`s on the art ground, so Norse notches them and Vardr Labs squares them (a system `Form`
section cannot take a theme shape).

**Tech Stack:** SwiftUI (iOS 17+), Jetpack Compose, XCTest, JUnit. No core change, no new dependency, no cloud change.

**Builds on, all merged before S2 starts (do not redo):** D-219 C1 (`LucideStroke` 2.4, `TabBarGround`). D-220 C2
(`ThemeArt.pillKind` / `pillShape` (Aurora capsule, Norse notched at `pillChamfer` 4, Vardr Labs square),
`panelShape` square in Vardr Labs, `TileClip`, `ButtonLook.disabledAlpha` 0.45, L69's one-line fix (Delete account
drawn destructive), L72 / L-D217-5 (About, Licences and a licence's page on the art ground: S2 does NOT redo it),
Lucide `search` and `check`, Android's `CurrencyListDialog` drawn as a pushed page). D-221 E4 (`ThemeArt.metrics` 44,
the You hero, `AvatarStyle` / `AvatarFill`, `SetupFacts`). D-222 S1 (`SettingsTile`, `SettingsRow`, the list card
padding `listPadH` 15 / `listPadV` 3 / `rowPadV` 10 / `rowGap` 13 / `touchTarget` 48, Android `glassCard(padding:)`,
`ModeControl`, `StepSlider`, `YouRows`, `Phones.activeCount`, Lucide sun, shield, download, info). D-223 Home (metrics
78, `inbox`). D-224 E3 (metrics 94, `TypeScale.toast`, Lucide `vibrate`, NOTICE at eighteen drawings, `HapticsRow`
on Appearance with each shell's own switch, `Haptics.play(.warning)` before destructive questions, `DoneCenter`
toasts, Add expense's "Saved" hold removed). If any of those has not merged, recount from the tree and say so.

**Rows taken (audit `.superpowers/sdd/lookbook-audit/consolidated.md`, unit S2; founder Q15 a):**
- L67 Security as one list card of rows with live summaries (Q15 a: a trailing switch for hide-during-recording, app
  lock opening a picker page).
- L68 the app-lock control (iOS menu Picker vs Android FilterChips): closed by L67's picker page.
- L69 the "Careful" danger list card, titles in the danger colour (`owed`); C2 already drew Delete account destructive.
- L70 no hairline under "Careful" (and, by the same Look Book rule, under About's and Licences' section labels).
- L71 About's cards and a licence's text panels through `glassCard()` / `Modifier.glassCard(dark)`.
- L73 Licences: each section's rows in a list card with the chevron.
- L74 Your data: one list card of rows on both phones.
- L75 the picture's placeholders at iOS's ratios on both (glyph 0.5, emoji 0.55, initials 0.38).
- L76 the emoji grid: one fill rule and the theme's cell shape.

**L-D217 rows taken:** L-D217-1 (the settings-rows part: Group settings rows 56 pt vs 49 dp, Licences 57 vs 55.5;
one `ListRow` with a 48 minimum on both), L-D217-8 (Android People: the label, the card, the Invite card with
chevrons, captions), L-D217-9 (Android Security switch touching its label).

**Twin differences taken because no other unit owns them** (`.drafts/d217/look/look-report.md` items 13, 14, 16;
`.superpowers/sdd/d218/task-4-report.md` finding 3): Add and Edit expense (outlined fields, dropdowns, chips and
checkboxes vs form rows, menus and switches), New group and Your name sheets (outlined fields and currency chips vs
form rows and a "Settles in" menu), Profile (outlined name box vs hairline field, 6-column filled grid vs 8-column),
Group settings (opaque iOS `Form` section vs Android glass), iOS grouped-list corners about 25 pt vs 20 dp (gone with the
`Form`s in scope), the remove-person cross (iOS SF `xmark.circle.fill` vs Android two drawn lines), the lock screen
(iOS SF `lock` on `Tokens.bg`, Android no glyph on the art ground), every switch (iOS `Toggle` vs Material `Switch`:
Security, Find me, E3's Haptics row, Add expense's people and item lines).

**Left out, and why:**
- L72: C2 owns it (merged).
- The Profile and account page (sign in, sync, notifications, find me cards): no audit row; it keeps its cards (its
  Find me switch becomes `ThemeSwitch`). Residue.
- iOS `Form`s not seen in a look: SignInScreen, ImportFlow, RemovalFlow; Android `OutlinedTextField` outside the screens
  above (Invites, UsernameStep, Import, Removal, the currency search field C2 kept). Residue, for a later sweep.
- The People "Owner" pill seen only on Android in D-218 (finding 2, unverified, likely state): the look checks it with a
  shared group signed in on both; a real difference is reported, not fixed here.
- Group page "$0.00 settled" placement: E4 (L-D217-7).
- System alerts and confirmation dialogs stay each platform's (iOS `.alert`, Android `SkiptiAlertDialog`): today's
  confirmation steps are kept exactly (GUARANTEES, D-127), and an alert is the platform's own window.
- Android's "App signature" section on About: Android-only by purpose (Google sign-in's certificate). Residue as today.

## Design

1. **The list card and row (L67, L73, L74, L-D217-1).** `ListCard { rows }`: S1's card (`glassCard(padding: 0)` with
   the content padded `listPadH` / `listPadV`), a `hairline` divider between rows, none after the last. `ListRow`:
   optional `SettingsTile(glyph:)` (only You's list and E3's Haptics row use tiles; the Look Book's Security page has
   none), `rowGap` 13, a column of the title (`TypeRole.name(16)` / `Type.name(dark, 16)`) over the summary
   (`Separator.text(parts, size: TypeScale.detail)` / `Separator.annotated(parts)` in `textDim`, `rowLineGap` 2 under the
   title, `summaryLines` 1 by default with an ellipsis, 0 for unlimited), then the trailing piece:
   - `.chevron`: S1's `RowChevron` in `textDim`; the whole row is a button (iOS `NavigationLink` or `Button`, Android
     `clickable(role = Role.Button)`).
   - `.value(text, chevron: Bool)`: the value at 16 in `textDim`, one line, then the chevron when tappable (Group
     settings' "Name  Goa trip ›", "Settles in  INR").
   - `.toggle(isOn)`: `ThemeSwitch`; the whole row toggles it (Design 3).
   - `.check(chosen)`: C2's Lucide `check` in `accent` at the currency list's size when chosen, nothing otherwise.
   - `.none`.
   Vertical padding `rowPadV` 10, minimum height `touchTarget` 48, so a one-line row is 48 on both phones (art.py 41:
   39 raised to 48; title with one summary line 58). Tone `.danger`: the title in `owed` (art.py 41: 4.5 on every card
   ground in every set). Accessibility: one element per row, read "title, part, part" (S1's `YouRows.spoken`), the
   trailing piece's role (button, switch with its value, selected).
   **DECIDED (controller, for the founder): S1's `SettingsRow` becomes a call of `ListRow` with a tile**, so You's
   list, Haptics and every settings page draw one row; nothing about You's list changes on screen.
2. **Captions and section labels.** A caption under a card (the explanation a `Form` footer used to carry) is
   `TypeScale.detail` in `textDim` on the page ground, 8 under the card (art.py 41: 4.5 on every ground's worst
   pixel). Section labels are `.sectionLabel()` / `SectionLabel` alone, 8 above their card: the hairline under
   "Careful" and under About's and Licences' labels goes (L70; LB `.label` alone).
   **DECIDED: iOS footers at 13 move to the caption's 14** (People, Invite, Group settings' delete line), one size on
   both phones.
3. **The drawn switch (L-D217-9).** `ThemeSwitch(isOn, enabled)`: iOS's proportions on both (the founder's "iPhone
   way", D-217): track `switchWidth` 51 x `switchHeight` 31 shaped by C2's `ThemeArt.pillShape(theme)` (capsule, Norse
   notched 4, Vardr Labs square); thumb `switchThumb` 27 inset `switchInset` 2, shaped `TileShape.of(theme, person:
   true)` inscribed in its box (circle, diamond, square: S1's slider thumb rule), with S1's segment shadow
   (`segmentShadowAlpha` 0.15, radius 2, y 1). Paint (`SwitchPaint.of(on:)`, token names): ON track `accent`, thumb
   `surface`; OFF track `ringTrack` inside a `switchBorder` 1.5 border in `textFaint`, thumb `textDim` (Material's way
   of marking off: each state holds 3:1 without colour alone, art.py 42). Disabled: the whole switch at
   `ButtonLook.disabledAlpha`. The thumb slides `switchTravel` 0.2 s ease-in-out (none under Reduce Motion or Low Power,
   the D-214 still rule). `SwitchGeometry.thumbCentreX(on:)` 15.5 / 35.5. Accessibility: iOS
   `.accessibilityRepresentation { Toggle(title, isOn:) }` on the ROW; Android the row `toggleable(value, enabled, role =
   Role.Switch)` and the drawn switch `clearAndSetSemantics {}`. **DECIDED (controller, for the founder): a drawn switch on
   both**, closing the residue E3 left for S2 ("a drawn twin switch is S2's call"); it replaces every `Toggle` and
   Material `Switch` in scope (Security, Find me, Haptics, Add expense's people and item lines).
4. **Security (L67, L68, L69, L70, Q15 a).** Top to bottom, 16 apart:
   - On a jailbroken or rooted phone only: today's warning (`PrivacyCopy.modified`) in `watch` in its own `glassCard`
     first (art.py 41: `watch` 4.5 on every card ground). **DECIDED: the warning moves to the top**, so it is not lost
     under a row.
   - `ListCard`: **App lock** (summary `SecurityRows.appLockLine`: `LockTimeout.label` ("Immediately", "After 2
     minutes", ..., "Off"), or "Needs a phone lock" when the phone cannot lock; `.chevron` to the App lock page);
     **the screen-privacy row** (title iOS `PrivacyCopy.captureToggle` "Hide during screen recording", Android "Block
     screenshots and screen recording", each platform's true capability as today; summary the platform's
     `captureDetail`, unlimited lines, so the honest limit stays beside its switch; `.toggle`); **Your phones** (signed in
     only; summary the account's phone names, this phone first, signed-out phones left out, joined with ", ", from the
     read the Your phones page already makes; a failed read leaves the summary out; `.chevron` to `PhonesScreen`).
   - "Careful" label, then a danger `ListCard`: **Erase this phone's ledger** (`EraseCopy.button`; summary "Asks first.
     Cannot be undone."; `.chevron` to the Erase page); **Delete account** (signed in only; summary "A week to change
     your mind" (deletion is 7 days, `PhonesCopy.deleteGrace`); `.chevron` to `DeleteAccountScreen`, unchanged).
   - **The App lock page** (`ChoicePage` titled `AppLockCopy.section` "App lock"): the six `LockTimeout` options as
     `.check` rows; under the card the on or off caption (`AppLockCopy.on` / `offDetail`); choosing Off raises today's
     alert ("Turn off app lock?", Turn off / Keep it on) and writes nothing until confirmed; every other choice writes at
     once and returns. A phone that cannot lock shows only the unavailable caption (each platform's `UNAVAILABLE`).
   - **The Erase page** (titled `EraseCopy.title` "Erase this phone"): today's card content unchanged, the detail
     (`EraseCopy.detail`) and `EraseLedgerButton` with its alert and its E3 warning haptic. **DECIDED (controller, for
     the founder): the erase row opens a page rather than raising the alert from the row**, so the explanation of what
     goes is read before the button, the chevron means a push as on every other row, and the confirmation steps are
     today's (the row adds one step, it removes none).
   SettingsMap stays the tested layout: `.security` items keep their order; the page partitions them into the safe card
   (`.appLock`, `.privacy`, `.phones`) and the careful card (`.erase`, `.deleteAccount`) by `SecurityRows.careful`.
5. **Your data (L74).** One `ListCard`: **Default currency** (`.value(code, chevron: true)` opening the currency list
   page C2 drew (its "Common" section first), so the quick menu and the "All currencies" button go); **Export your data**
   (summary "Your whole ledger in one file: CSV to read, or a JSON backup to restore.", 2 lines; to `ExportScreen`);
   **Import from another app** (`ImportCopy.entry`, summary `ImportCopy.entryDetail`, 2 lines; to the import flow).
   Caption under the card: `CurrencyCopy.detail`. GUARANTEES #4: export stays one tap from Your data.
6. **About, Licences, a licence (L71, L73).** About's cards are `glassCard()`; its section labels lose the hairline.
   Licences: per section a label and one `ListCard` of `ListRow`s (title the component's name, summary
   `version · licence` through `Separator`, `.chevron`); Android puts one lazy item per section. A licence's page: the
   text panels are `glassCard()` (padding 12); iOS's body title (`display(24)` name) goes, because the bar already names
   the page and D-217's rule is that a pushed page draws no header of its own (Android draws none). Sizes from
   `TypeScale.prose` 15 (About's text, the Licences intro), `TypeScale.footnote` 13 (licence text), `TypeScale.wordmark`
   32 ("SKIPTI").
7. **Group settings (L-D217-1).** One `ListCard`: Name (`.value(name, chevron: true)`), Picture (the group tile at 30 as
   the value, chevron), Settles in (`.value(code, chevron: false)`); then a danger `ListCard` with **Delete group** (its
   summary `RolesCopy.deleteGroupSub` when shared). Every confirmation (rename alert, local delete alert, the delete
   sheet with open balances) unchanged. iOS leaves `Form` for a `ScrollView` on `.auroraBackground()`.
8. **People (L-D217-8).** Both shells: request cards as today; "People" label; one `ListCard` of member rows (avatar
   36, name, `RolePill`, chevron when the row opens the person's sheet; a rename field as a `FieldRow` while editing;
   the removable cross as Lucide `x` at `clearGlyph` 18 in `textFaint` in a 48 target); the stays caption under it for a
   phone-only group; then, signed in with invitable people, the "Invite" label, one `ListCard` of "Invite X" rows with
   chevrons, `InviteCopy.sectionFooter` as its caption; "Add a person" as the page's primary button below the cards (not
   inside a card: the card was iOS's workaround for the `Form` row clip, D-139, gone with the `Form`).
9. **Profile (L75, L76, item 16).** Avatar placeholders at iOS's ratios on both (`avatarGlyph` 0.5 of the size for the
   Lucide `user-round`, `avatarEmoji` 0.55, initials `TypeScale.avatarInitial`; Android's fixed 24 dp glyph goes).
   The name: Android draws iOS's hairline field (a plain `BasicTextField`, vertical padding 10, a 1 dp `hairline` under:
   the twin of `HairlineFieldStyle`), no outline. The emoji grid: `emojiColumns` 6 equal columns on both, `emojiGap` 8,
   square cells, the emoji at `emojiGlyph` 24 (fixed, as today); unchosen cells clear, the chosen cell `accentSoft`
   with a `emojiBorder` 2 border in `accent`, shaped `ThemeArt.panelShape(theme, radius: emojiRadius 8)` (art.py 43:
   the border 3:1 on every card ground). **DECIDED (controller, for the founder): six columns, not iOS's eight**: eight
   gives 34 pt cells on an iPhone 17 and 29 dp on a 360 dp phone, under both platforms' 44 / 48 minimums; six gives
   48.3 pt and 41.3 dp (art.py 43).
10. **The choice page (Q15 a, reused).** `ChoicePage(title, options, chosen, onPick)`: a pushed page with one `ListCard`
    of `.check` rows (an option may carry a summary), optional caption; picking writes and returns (one tap, like a
    menu's two taps in all: open, pick). iOS: a `navigationDestination` / `NavigationLink` in the screen's stack (the
    Add expense sheet already has one). Android: C2's `CurrencyListDialog` window (no scrim, under the system bars,
    entering from the end in 250 ms, none under Reduce Motion) extracted as `PushedWindow(title, onClose) { }` and used
    by both the currency list and `ChoicePage`. Accessibility: iOS each row a button with `.isSelected` on the chosen;
    Android the card `selectableGroup()`, each row `selectable(role = Role.RadioButton)`.
    **DECIDED (controller, for the founder): every choice a form makes opens a choice page on both phones** (Category,
    Sub-category, Group, Split type, Paid by), replacing iOS's system menus and Android's dropdowns and chips, because
    the popup menus are each platform's own window and can never look the same, while a pushed list is Apple's own
    pattern for form choices (Calendar's Repeat and Alert) and draws identically. The alternative is a drawn row with
    each platform's popup (a visible residue).
11. **Add and Edit expense (item 16).** The amount block as today; the currency pill (C2's `.themePill(.accent,
    onPage:)`) opens the currency list page directly on both (iOS's quick menu and Android's chip row go; the list's
    "Common" section holds the same codes). Then: a `ListCard` with the "What was it for?" `FieldRow`, Category
    (`.value`, chooser), Sub-category when a category is chosen (with today's hint caption under the card when none is
    chosen yet), Group (`.value` with a chevron when a new expense has more than one group, else no chevron:
    `AddExpenseRows.groupRow(editing:groupCount:)`); the "Split" label; a `ListCard` with Split type (`.value`, chooser),
    Paid by (`.value`, chooser), one `.toggle` row per member (title the name), then the kind's fields as `FieldRow`s
    with today's placeholders ("Amount for X", "Percent for X", "Shares for X", "Adjustment for X") in
    `TypeRole.numeric(TypeScale.shareInput)` with today's keyboards; Itemised: per line a `ListCard` with the "Line amount"
    `FieldRow` and a `.toggle` row per member ("Line for X"), then "Add line" (secondary) and the Tax and Tip `FieldRow`s
    in a card. Summary and problem lines at `TypeScale.prose` 15 centred, the error, the footnote, the action bar: as
    today (E3's toast flow untouched). GUARANTEES #2: the default path is unchanged (amount, then Add expense: you paid,
    everyone in, Equal); nothing new stands before it.
12. **New group and Your name (item 16).** "Group" label; `ListCard` with the "Group name" `FieldRow`, the picture row
    (tile and the Group picture button, as today), Settles in (`.value(code, chevron: true)` opening the currency list
    page); caption "A group settles in one currency, and it cannot be changed later." Then "You" (when asked) with a
    "Your name" `FieldRow` card, "People" with a `ListCard` of name `FieldRow`s each with the Lucide `x` clear, "Add
    another person" (secondary) under the card, the people caption. Your name sheet: the same field card.
13. **The lock screen.** Lucide `lock` at `lockGlyph` 40 in `textDim` above the title on both (iOS's SF `lock` goes,
    Android gains it), the title at `TypeScale.lockTitle` 28, both on `.auroraBackground()` / `auroraBackground(dark)`
    (iOS drew `Tokens.bg`). Art.py 44: glyph 3:1 and title 4.5 on every page ground's worst pixel. The privacy cover
    (`PrivacyCover`) stays plain `bg` on purpose (it is the app switcher's snapshot).

## Global Constraints

- Values come from `.drafts/d225/art.py` (`.drafts/d224/art.py` extended with sections 41 to 45; `python3 -I
  .drafts/d225/art.py`, output `.drafts/d225/art.out`, "Floors failing: 0"; written and run by the planner). Never
  retype a value that is not in this plan or that output; extend the generator for any new one.
- `ThemeArt.metrics` gains 16 keys after D-224's 94 (110), Swift Double, Kotlin twin, in this order: `"rowLineGap": 2,
  "switchWidth": 51, "switchHeight": 31, "switchThumb": 27, "switchInset": 2, "switchBorder": 1.5, "switchTravel":
  0.2, "emojiColumns": 6, "emojiGap": 8, "emojiGlyph": 24, "emojiBorder": 2, "emojiRadius": 8, "avatarGlyph": 0.5,
  "avatarEmoji": 0.55, "lockGlyph": 40, "clearGlyph": 18`.
- `TypeScale` gains, after D-224's `toast`, in this order: `prose = 15`, `footnote = 13`, `lockTitle = 28`,
  `wordmark = 32` (and `all` lists them; Kotlin names as the tree spells its others).
- `SwitchPaint.of(on:)` (token names, one golden table in both shells' tests): on `(track: "accent", border: nil,
  thumb: "surface")`; off `(track: "ringTrack", border: "textFaint", thumb: "textDim")`. `SwitchGeometry.thumbCentreX`:
  off 15.5, on 35.5.
- Tokens by name only: `accent`, `accentSoft`, `surface`, `text`, `textDim`, `textFaint`, `hairline`, `ringTrack`,
  `owed`, `watch`. No new token, no new hex.
- Copy (verbatim, both shells, no em dashes). New: "Asks first. Cannot be undone.", "A week to change your mind",
  "Needs a phone lock". Reused unchanged: `EraseCopy.*`, `PhonesCopy.*`, `AppLockCopy.*` (each platform's), `PrivacyCopy.*`
  (each platform's), `CurrencyCopy.label` / `.detail`, `ImportCopy.entry` / `.entryDetail`, "Export your data", "Your
  whole ledger in one file: CSV to read, or a JSON backup to restore.", `CategoryCopy.*`, `AddSheetCopy.*`, "Split
  type", "Paid by", the split kinds' labels, the field placeholders, `InviteCopy.*`, `MemberCopy.stays`, `RolesCopy.*`.
  Removed: `YouCopy.securityLine` (if S1 left it), iOS `AboutScreen`'s section hairline, the "All currencies" buttons
  on Your data and New group (the list page holds them).
- Lucide `x` and `lock`: read `https://unpkg.com/lucide-static@latest/icons/x.svg` and `.../lock.svg` with WebFetch
  (reading only, nothing executed); copy the path data VERBATIM (a `<rect>` written in the path convention the existing
  drawings use, recorded in the comment). NOTICE's Lucide entry goes from eighteen drawings to twenty, naming both and
  where they are drawn ("the clear and remove cross", "the lock screen"); read Lucide's LICENSE for whether either is
  on the Feather-derived list and word NOTICE and the `scripts/licences.py` comment as it says; regenerate
  `native/licences/licences.json` (`python3 scripts/licences.py`, then `--check`).
- New files (one per shell): `ios/Skipti/App/ListRows.swift` (pbxproj: four insertions, CLAUDE.md 7.1: copy
  `People.swift`'s reference line untruncated, assert every new A- and B-ID has zero occurrences first) and
  `android/app/src/main/java/com/vardrlabs/skipti/ListRows.kt`. No new test file. No new dependency, no core change,
  no rules or Functions change, nothing deployed.
- Shapes only through `ThemeArt.pillShape`, `panelShape`, `TileShape`, `glassCard`; type only through `TypeRole` /
  `Type` and `TypeScale`. No `.buttonStyle(.bordered)`, no bare unstyled `Button` for a real action, no
  `OutlinedTextField`, `FilterChip`, `Checkbox`, `ExposedDropdownMenuBox` or Material `Switch` left in the files in
  scope (source guards).
- Never weaken a confirmation: the erase alert, the delete-account alert, the turn-off-app-lock alert, the rename and
  delete-group confirmations and the member-removal alert keep their words and steps (D-127, GUARANTEES).
- No em dashes anywhere (code, comments, copy, commits). Commits end `[skip ci]`; explicit paths only; `git diff
  --cached --stat` before every commit, refuse if it holds a file you did not stage; no attribution of any kind. Never
  `git stash`, `reset`, `checkout -- <file>` or `restore`.
- iOS tests: boot `AFAF4221-F35B-4CD6-8ED9-A4AFD84C6D51` first, `-collect-test-diagnostics never` on every `xcodebuild
  test`, one class at a time (`-only-testing:SkiptiTests/<Class>`), never two iOS test runs at once in this checkout,
  shut it down when the lane is done. Android: `cd android && zsh -lic './gradlew :app:testPlayDebugUnitTest --tests
  "com.vardrlabs.skipti.<Class>"'`; check result XML mtimes before quoting a tally. Stop your own Monitors and
  watchers before replying.
- Every new guard is sabotaged (matrix below). A red test fails, never crashes. The controller runs the full gate.
- Verify every file and anchor here against the tree before editing: C1 to E3 land first and move lines. Source-guard
  pins S1 and E3 wrote that S2 changes (S1's `SettingsRow` anchors, E3's `HapticsRow` guard naming `Toggle(` /
  `Switch(` and `onCheckedChange = null`, `TypeThemeTests` size-call counts) are updated in the same task, naming each.

---

### Task 1: Values, copy, pure decisions and the two drawings, both shells (one agent; runs first)

**Files:**
- iOS: `ios/Skipti/App/ThemeArt.swift` (16 metrics; `SwitchPaint`, `SwitchGeometry`), `TypeSystem.swift` (four
  `TypeScale` constants), `LucideIcon.swift` (cases `x`, `lock` and their verbatim paths), `AppLock.swift`
  (`SecurityRows`: `careful: Set<SettingItem>`, `appLockLine(timeout:available:)`, the three new strings),
  `Phones.swift` (`PhonesSummary.line(rows:)`: active names, this phone first, ", "), `Profile.swift` (`EmojiGrid.rows(
  _:columns:)`, `EmojiCell.paint(chosen:)` -> token names), `AddExpenseScreen.swift` (`AddExpenseRows.groupRow(editing:
  groupCount:) -> .chooser / .plain / .none`).
- Android: `ThemeArt.kt`, `Tokens.kt` (`TypeScale`), `TabIcons.kt` (`PATHS` `x`, `lock`; `TabIcons.X`, `Lock`),
  `AppLock.kt`, `Phones.kt`, `Profile.kt`, `AddExpenseScreen.kt` (the pure twins).
- `NOTICE`, `scripts/licences.py`, `native/licences/licences.json`.
- Tests: iOS `ThemeArtTests`, `TypeThemeTests`, `BalanceLineTests` (Lucide goldens), `AppLockTests`, `AccountTests`,
  `ProfileTests`, `CategoryWiringTests`; Android `ThemeArtTest`, `TypeThemeTest`, `HomeTabsTest`, `AppLockTest`,
  `AccountTest`, `ProfileTest`, `AddExpenseTest`.

- [ ] **Step 1: Tests first** (declare with WRONG values so the red is an assertion, not a compile failure):
  - `testS2ArtValues` / `the_s2_art_values`: the 16 keys and values; `testEveryArtValueIsPresent` at 110; Android's
    `the_art_values_match_ios` at 110.
  - `TypeScale` table test extended with the four constants in order (Android's parity test already reads Swift).
  - Lucide: iOS pins the two `pathData` arrays (pasted from the WebFetch read); Android's `PATHS` golden gains them;
    S1's `every_lucide_path_matches_ios` covers the pair.
  - `SwitchPaint` and `SwitchGeometry`: the golden table (Global Constraints) in both tests; negative: off has a
    border, on has none.
  - `SecurityRows` (one table pinned in both): `appLockLine` for each `LockTimeout` with `available` true is its label,
    with `available` false is "Needs a phone lock" whatever the timeout; `careful` == {erase, deleteAccount};
    `SettingsMap.items(.security, signedIn: false)` partitions to safe [appLock, privacy] and careful [erase], signed in
    to [appLock, privacy, phones] and [erase, deleteAccount]; the summaries' strings verbatim.
  - `PhonesSummary.line`: rows (this "iPhone 17", other "Moto edge 70", signed-out "Old phone") -> "iPhone 17, Moto
    edge 70"; empty -> nil (the negative: never an empty summary).
  - `EmojiGrid.rows(choices, columns: 6)`: 48 choices -> 8 rows of 6; 7 choices -> rows of 6 and 1; `EmojiCell.paint`:
    chosen (fill "accentSoft", border "accent"), not chosen (nil, nil).
  - `AddExpenseRows.groupRow`: editing -> `.plain`; new with 2 groups -> `.chooser`; new with 1 -> `.plain`; 0 ->
    `.none`.
- [ ] **Step 2:** Run each class alone: red for the right reason.
- [ ] **Step 3:** Implement. Copy `x` and `lock` verbatim; NOTICE twenty; `python3 scripts/licences.py`, then `--check`.
  Green on every class listed.
- [ ] **Step 4:** Sabotage rows V1 to V10 and P1 to P3. Report them.
- [ ] **Step 5:** Commit `D-225 T1: list, switch and security decisions, the S2 values, Lucide x and lock [skip ci]`.

### Task 2: iOS shared components (after Task 1; the iOS lane; parallel with Task 3)

**Files:** Create `ios/Skipti/App/ListRows.swift` (`ListCard`, `ListRow` with `RowTrailing` and `RowTone`,
`ThemeSwitch`, `FieldRow`, `ChoicePage`, `CardCaption`); `ios/Skipti.xcodeproj/project.pbxproj` (four insertions);
`AccountHubScreen.swift` (S1's `SettingsRow` and list card call `ListRow` / `ListCard`; E3's `HapticsRow` uses
`.toggle` with `ThemeSwitch`); `People.swift` (Find me's `Toggle` becomes a `.toggle` row); tests `AppearanceTests`,
`ThemeArtTests`.

- [ ] **Step 1: Tests first** (source guards read the Swift files, each naming file and anchor):
  - `testTheListRowIsOneComponent`: `ListRow` reads `TypeRole.name(16)`, `Separator.text(`, `TypeScale.detail`,
    `ThemeArt.m("rowLineGap")`, `"rowPadV"`, `"rowGap"`, `"touchTarget"` as a minimum height, `RowChevron(`,
    `Tokens.owed` under the danger tone; `ListCard` reads `.glassCard(padding: 0)`, `"listPadH"`, `"listPadV"`,
    `Tokens.hairline`; AccountHubScreen.swift's `SettingsRow` body calls `ListRow(`.
  - `testTheSwitchIsDrawnAndAccessible`: `ThemeSwitch` reads `SwitchPaint.of(`, `SwitchGeometry.thumbCentreX(`,
    `ThemeArt.pillShape(`, `TileShape.of(` (person: true), the five `switch*` metrics, `ButtonLook.disabledAlpha`, the
    reduce-motion check D-214 uses; the `.toggle` row carries `.accessibilityRepresentation {` holding `Toggle(`.
  - `testNoSystemToggleIsLeft`: no `Toggle(` outside `.accessibilityRepresentation` blocks in AccountHubScreen.swift,
    People.swift, ListRows.swift (AppLock.swift and AddExpenseScreen.swift join in Tasks 4 and 6).
  - `testTheChoicePageChecksOneRow`: `ChoicePage` draws `ListRow(` with `.check(`, adds `.isSelected` on the chosen,
    and calls `onPick` then dismisses.
  - Update E3's HapticsRow guard (it named `Toggle(`) to `.toggle(` and `ThemeSwitch`.
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** Design 1, 2, 3, 10 (iOS). `ThemeSwitch` animates with `.animation(_:value:)` scoped to the
  thumb (never `withAnimation(.repeatForever)`, CLAUDE.md 10.1). `FieldRow(placeholder, text, keyboard, font,
  trailing)`: a `TextField` padded `rowPadV`, min height 48, placeholder in `textFaint`.
- [ ] **Step 4:** Green on `AppearanceTests`, `ThemeArtTests`, `TypeThemeTests`, `AccountTests`. Sabotage rows I1 to I8.
- [ ] **Step 5:** Commit `D-225 T2: iOS list card, list row, drawn switch, field row and choice page [skip ci]`.

### Task 3: Android shared components (after Task 1; the Android lane; parallel with Task 2)

**Files:** Create `android/app/src/main/java/com/vardrlabs/skipti/ListRows.kt` (the twins, plus `PushedWindow`
extracted from `CurrencyListDialog` and `HairlineField`); `CurrencyChoice.kt` (`CurrencyListDialog` calls
`PushedWindow`); `AccountHubScreen.kt` (S1's `SettingsRow` and E3's `HapticsRow`); `People.kt` (Find me's `Switch`);
tests `AppearanceTest`, `ThemeArtTest`.

- [ ] **Step 1: Tests first**, the twins of Task 2's guards on the Kotlin files, plus:
  - `the_switch_row_is_one_toggleable`: the `.toggle` row carries `toggleable(` with `role = Role.Switch`; the drawn
    switch `clearAndSetSemantics`; no `androidx.compose.material3.Switch` import or call in ListRows.kt,
    AccountHubScreen.kt, People.kt.
  - `the_choice_page_is_a_radio_group`: `selectableGroup()`, `selectable(` with `role = Role.RadioButton`.
  - `the_pushed_window_is_shared`: `CurrencyListDialog` and `ChoicePage` both call `PushedWindow(`; `PushedWindow` sets
    the dim amount 0 and `decorFitsSystemWindows` false (C2's lines, moved, not rewritten).
  - `the_hairline_field_twins_ios`: `HairlineField` pads vertical `10.dp` and draws a `1.dp` `Tok.hairline(dark)` line,
    no `OutlinedTextField`.
  - Update E3's HapticsRow guard (row AH7 named `Switch(` and `onCheckedChange = null`) to the `.toggle` row.
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** the twins. `ThemeSwitch` animates the thumb with `animateFloatAsState` (snap under the
  D-214 still rule). Rows `heightIn(min = ThemeArt.m("touchTarget").dp)`.
- [ ] **Step 4:** Green on `AppearanceTest`, `ThemeArtTest`, `YouTest`, `CurrencyChoiceTest`, `SkiptiBarTest`. Sabotage
  rows A1 to A8.
- [ ] **Step 5:** Commit `D-225 T3: Android list card, list row, drawn switch, field rows, choice page and the shared pushed window [skip ci]`.

### Task 4: iOS settings pages (after Task 2; the iOS lane)

**Files:** `AccountHubScreen.swift` (Security and Your data in `SettingsSectionScreen`; `sectionHeader` loses its
hairline), `AppLock.swift` (`AppLockPage`, `PrivacyRow`, `AppLockScreen` glyph, title and ground; `AppLockSettings`
and `PrivacySettings` go), `AccountOwner.swift` (`EraseScreen`), `CurrencyChoice.swift` (`CurrencySettings` goes; the
row opens `CurrencyListScreen`), `About.swift`, `GroupSettings.swift`, `GroupPicker.swift` (`PeopleScreen`),
`Profile.swift` (avatar ratios, emoji grid); tests `AppLockTests`, `AboutTests`, `ProfileTests`, `RolesTests`,
`AccountTests`.

- [ ] **Step 1: Tests first** (source guards, each naming file and anchor; plus):
  - `testSecurityIsTwoListCards`: the `.security` page builds `ListCard` twice, the second after `.sectionLabel()` of
    `YouCopy.careful`, rows with `.danger`; no `card {` and no `.glassCard()` call for app lock, privacy, phones, erase or
    delete; no `Rectangle().fill(Tokens.hairline)` in `sectionHeader`; the erase row is a `NavigationLink` to
    `EraseScreen`, never a direct `LedgerErase` call.
  - `testTheAppLockPageConfirmsOff`: `AppLockPage` uses `ChoicePage(`; choosing `.off` sets the confirming state and
    writes `LockTimeout.storageKey` only inside the alert's destructive button (anchor `role: .destructive) { stored =
    LockTimeout.off.rawValue }`).
  - `testTheEraseScreenKeepsItsConfirmation`: `EraseScreen` holds `EraseCopy.detail` and `EraseLedgerButton()`;
    `EraseLedgerButton` still raises `.alert(EraseCopy.confirmTitle`.
  - `testYourDataIsOneListCard`, `testAboutUsesGlassCards` (no `panelShape(` fill in About.swift's `card` or licence
    panels; `glassCard(`; no body `display(24)` title on `LicenceDetailScreen`), `testLicencesRowsAreListRows`,
    `testGroupSettingsAndPeopleLeftTheForm` (no `Form {` in GroupSettings.swift or in `PeopleScreen`; `ListCard`; the
    cross is `LucideIcon(glyph: .x`), `testTheLockScreenTwins` (`LucideIcon(glyph: .lock`, `TypeScale.lockTitle`,
    `.auroraBackground()`, no `systemName: "lock"`), `testTheProfilePlaceholdersAndGrid` (`ThemeArt.m("avatarGlyph")`,
    `"avatarEmoji"`, `EmojiGrid.rows(`, `EmojiCell.paint(`, `ThemeArt.panelShape(`, no `count: 8`).
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** Design 4 to 9 and 13 (iOS). Read `.privacy`'s switch through
  `@AppStorage(ScreenPrivacy.storageKey)`; the phones summary from the Your phones page's existing read (signed in
  only). Keep every `.alert`, `.sheet` and E3 haptic exactly where it fires today; attach sheets and alerts to
  always-present views (CLAUDE.md 10.1).
- [ ] **Step 4:** Green on `AppLockTests`, `AboutTests`, `ProfileTests`, `RolesTests`, `AccountTests`,
  `AppearanceTests`, `TypeThemeTests`. Sabotage rows I9 to I18.
- [ ] **Step 5:** Commit `D-225 T4: iOS Security, Your data, About, Licences, Group settings, People and Profile in list cards; the lock screen [skip ci]`.

### Task 5: Android settings pages (after Task 3; the Android lane; parallel with Task 4)

**Files:** `AccountHubScreen.kt`, `AppLock.kt`, `AccountOwner.kt` (`EraseScreen`), `AppRoot.kt` (route `"erase"`,
`HomeRoutes.parent("erase") == "account/security"`; the App lock page is a `ChoicePage` window, no route),
`CurrencyChoice.kt` (`CurrencySettings` goes), `About.kt`, `GroupSettings.kt` (its private `SettingsRow` goes),
`GroupScreens.kt` (`PeopleScreen`, `RemoveGlyphButton` draws Lucide `x`), `Profile.kt`; tests `AppLockTest`,
`PrivacyTest`, `AboutTest`, `ProfileTest`, `RolesTest`, `ScreenBarsTest`.

- [ ] **Step 1: Tests first**, the twins of Task 4's guards on the Kotlin files, plus:
  - `security_switch_has_room` (L-D217-9): the privacy row is a `ListRow` with `RowTrailing.Toggle`, so the title has
    `rowGap` to the switch; no `FilterChip(` and no material `Switch(` in AppLock.kt.
  - `erase_route_returns_to_security`: `HomeRoutes.parent("erase") == "account/security"`.
  - `people_page_twins_ios` (L-D217-8): `PeopleScreen` draws `SectionLabel("People"`, `ListCard(`, the Invite rows as
    `ListRow(` with the chevron, `InviteCopy.SECTION_FOOTER` through `CardCaption(`; no Canvas `drawLine` cross in
    `RemoveGlyphButton`.
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** the twins. The lock screen gains the glyph and `TypeScale.lockTitle`; `AppLock.timeout` and
  `AppLock.available(context)` re-read on resume for the row's summary.
- [ ] **Step 4:** Green on the classes listed plus `YouTest`, `AppearanceTest`, `TypeThemeTest`. Sabotage rows A9 to
  A18.
- [ ] **Step 5:** Commit `D-225 T5: Android Security, Your data, About, Licences, Group settings, People and Profile in list cards; the lock screen [skip ci]`.

### Task 6: iOS forms (after Task 4; the iOS lane)

**Files:** `AddExpenseScreen.swift`, `GroupSheets.swift` (`CreateGroupSheet`, `YourNameSheet`); tests
`CategoryWiringTests`, `ExpenseEditingTests`, `GroupStoreTests`.

- [ ] **Step 1: Tests first** (source guards):
  - `testAddExpenseIsListCards`: no `Form {`, no `Picker(`, no `Menu {`, no `Toggle(` outside
    `.accessibilityRepresentation`; `ChoicePage(` for Category, Sub-category, Group, Split type, Paid by;
    `AddExpenseRows.groupRow(`; `FieldRow(` with each of the five placeholders; the currency pill opens
    `CurrencyListScreen`; `.formActionBar {` unchanged.
  - `testNewGroupIsListCards`: no `Form {` in GroupSheets.swift; Settles in is a `.value(` row to `CurrencyListScreen`;
    the clear cross is `LucideIcon(glyph: .x`; no `systemName: "xmark.circle.fill"`.
  - Existing behaviour tests (`CategoryWiringTests`, `ExpenseEditingTests`) stay green unchanged: they pin the data,
    not the controls.
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** Design 11 and 12 (iOS): `ScrollView` on `.auroraBackground()`, `.scrollDismissesKeyboard(
  .interactively)` kept, the action bar kept. The default path (amount, then Add expense) unchanged.
- [ ] **Step 4:** Green on the classes listed, `AppearanceTests`, `TypeThemeTests`. Sabotage rows I19 to I22.
- [ ] **Step 5:** Commit `D-225 T6: iOS Add expense, New group and Your name in list cards with choice pages [skip ci]`.

### Task 7: Android forms (after Task 5; the Android lane; parallel with Task 6)

**Files:** `AddExpenseScreen.kt` (`ChoiceDropdown` goes), `GroupScreens.kt` (`CreateGroupScreen`, the Your name sheet,
`NameField` becomes a `FieldRow` caller), `CurrencyChoice.kt` (`CurrencyChips` removed if nothing calls it); tests
`AddExpenseTest`, `AddSheetTest`, `SplitTypesTest`, `GroupMembersTest`.

- [ ] **Step 1: Tests first**, the twins of Task 6's guards: no `OutlinedTextField`, `ExposedDropdownMenuBox`,
  `FilterChip`, `Checkbox(` in AddExpenseScreen.kt and the New group code; `ChoicePage(` for the five choices; member
  rows `RowTrailing.Toggle`; the currency pill opens `CurrencyListDialog`.
- [ ] **Step 2:** Red, for the right reason.
- [ ] **Step 3: Implement** the twins.
- [ ] **Step 4:** Green on the classes listed and `AppearanceTest`, `TypeThemeTest`. `make android-app` once (the APK
  builds). Sabotage rows A19 to A22.
- [ ] **Step 5:** Commit `D-225 T7: Android Add expense, New group and Your name in list cards with choice pages [skip ci]`.

### Task 8: Look (after Tasks 1 to 7 and the sabotage run; controller dispatch)

See **Look**. Write `.drafts/d225/look/look-report.md` and pairs in `.drafts/d225/look/pairs/`.

---

## Sabotage matrix

Sized to the risk (CLAUDE.md section 4 speed rule 2): each new guard once, each value table once. Two lanes in their own
worktrees outside the checkout (`/private/tmp/skipti-d225-ios`, `/private/tmp/skipti-d225-android`; own DerivedData;
`android/local.properties` copied), runner copied from `.drafts/d222/sabotage.py` (or `.drafts/d217/sabotage.py
lanes`) to `.drafts/d225/sabotage.py`. Boot the iOS test simulator first. Each row: back up, sabotage through an
asserted anchor, zero compile errors, exactly the listed tests red, restore, `cmp`.

| row | file | sabotage | expected red |
|---|---|---|---|
| V1 | ThemeArt.swift | `"switchThumb": 27` to `25` | testS2ArtValues |
| V2 | TypeSystem.swift | `lockTitle = 28` to `26` | the TypeScale table test |
| V3 | LucideIcon.swift | one coordinate of `x` changed | BalanceLineTests Lucide golden |
| V4 | ThemeArt.swift | `SwitchPaint` off thumb `textFaint` | the SwitchPaint golden |
| V5 | ThemeArt.swift | `thumbCentreX(on: true)` 33.5 | the SwitchGeometry golden |
| V6 | AppLock.swift | `appLockLine` ignores `available` | SecurityRows table |
| V7 | AppLock.swift | `careful` drops `.deleteAccount` | SecurityRows partition |
| V8 | Phones.swift | `PhonesSummary.line` keeps signed-out phones | PhonesSummary test |
| V9 | Profile.swift | `EmojiCell.paint(chosen: false)` returns the accent border | EmojiCell test |
| V10 | AddExpenseScreen.swift | `groupRow` offers the chooser while editing | groupRow test |
| P1 | ThemeArt.swift (Android lane) | `"emojiColumns": 6` to `8` in the Swift file only | the_art_values_match_ios |
| P2 | TypeSystem.swift (Android lane) | `prose = 15` to `16` in the Swift file only | the_type_scale_is_the_ios_table |
| P3 | LucideIcon.swift (Android lane) | the `lock` path altered in the Swift file only | every_lucide_path_matches_ios |
| I1 | ListRows.swift | `ListRow` title back to `TypeRole.body(16)` | testTheListRowIsOneComponent |
| I2 | ListRows.swift | the minimum height reads `"rowPadV"` instead of `"touchTarget"` | testTheListRowIsOneComponent |
| I3 | ListRows.swift | the danger tone draws `Tokens.text` | testTheListRowIsOneComponent |
| I4 | ListRows.swift | `ListCard` draws a divider after the last row | the ListCard guard |
| I5 | ListRows.swift | `ThemeSwitch` track `Capsule()` in every theme | testTheSwitchIsDrawnAndAccessible |
| I6 | ListRows.swift | the `.toggle` row loses `.accessibilityRepresentation` | testTheSwitchIsDrawnAndAccessible |
| I7 | People.swift | Find me back to a bare `Toggle(` | testNoSystemToggleIsLeft |
| I8 | ListRows.swift | `ChoicePage` drops `.isSelected` | testTheChoicePageChecksOneRow |
| I9 | AccountHubScreen.swift | the careful rows lose `.danger` | testSecurityIsTwoListCards |
| I10 | AccountHubScreen.swift | the hairline restored under `sectionHeader` | testSecurityIsTwoListCards |
| I11 | AccountHubScreen.swift | the erase row calls `LedgerErase.eraseThisPhone()` directly | testSecurityIsTwoListCards |
| I12 | AppLock.swift | choosing Off writes `stored` before the alert | testTheAppLockPageConfirmsOff |
| I13 | AccountOwner.swift | `EraseScreen` drops `EraseCopy.detail` | testTheEraseScreenKeepsItsConfirmation |
| I14 | About.swift | a licence panel back to `panelShape(` on `Tokens.surface` | testAboutUsesGlassCards |
| I15 | GroupSettings.swift | `Form {` restored | testGroupSettingsAndPeopleLeftTheForm |
| I16 | AppLock.swift | the lock screen back to `.background(Tokens.bg)` | testTheLockScreenTwins |
| I17 | Profile.swift | the grid back to `count: 8` | testTheProfilePlaceholdersAndGrid |
| I18 | Profile.swift | the glyph at `size * 0.4` | testTheProfilePlaceholdersAndGrid |
| I19 | AddExpenseScreen.swift | Split type back to a `Picker(` | testAddExpenseIsListCards |
| I20 | AddExpenseScreen.swift | a member row back to `Toggle(` | testAddExpenseIsListCards |
| I21 | GroupSheets.swift | the cross back to `systemName: "xmark.circle.fill"` | testNewGroupIsListCards |
| I22 | GroupSheets.swift | Settles in back to a `Picker(` | testNewGroupIsListCards |
| A1 | ListRows.kt | `ListRow` title back to `Type.body(dark)` | twin of I1 |
| A2 | ListRows.kt | the row's `heightIn(min = touchTarget)` removed | twin of I2 |
| A3 | ListRows.kt | the danger tone draws `Tok.text` | twin of I3 |
| A4 | ListRows.kt | the switch row's `role = Role.Switch` removed | the_switch_row_is_one_toggleable |
| A5 | ListRows.kt | `ThemeSwitch` track `CircleShape` / `RoundedCornerShape(50)` in every theme | twin of I5 |
| A6 | ListRows.kt | `ChoicePage` loses `selectableGroup()` | the_choice_page_is_a_radio_group |
| A7 | CurrencyChoice.kt | `CurrencyListDialog` sets its own window again, not `PushedWindow(` | the_pushed_window_is_shared |
| A8 | ListRows.kt | `HairlineField` becomes an `OutlinedTextField` | the_hairline_field_twins_ios |
| A9 | AppLock.kt | the privacy row back to a Row with a material `Switch(` | security_switch_has_room |
| A10 | AppRoot.kt | `parent("erase")` returns `"account"` | erase_route_returns_to_security |
| A11 | AccountHubScreen.kt | the careful rows lose the danger tone | twin of I9 |
| A12 | AppLock.kt | choosing Off calls `AppLock.set(context, LockTimeout.OFF)` before the dialog | twin of I12 |
| A13 | AccountOwner.kt | `EraseScreen` drops `EraseCopy.DETAIL` | twin of I13 |
| A14 | About.kt | `AboutCard` back to `panelShape` on `Tok.surface` | twin of I14 |
| A15 | GroupScreens.kt | the Invite rows back to accent `Text(` | people_page_twins_ios |
| A16 | GroupScreens.kt | `RemoveGlyphButton` back to two `drawLine`s | people_page_twins_ios |
| A17 | Profile.kt | the placeholder glyph back to a fixed `Icon` | twin of I18 |
| A18 | AppLock.kt | the lock screen without the Lucide lock | twin of I16 |
| A19 | AddExpenseScreen.kt | Split type back to `FilterChip` | twin of I19 |
| A20 | AddExpenseScreen.kt | a member row back to `Checkbox(` | twin of I20 |
| A21 | GroupScreens.kt | `NameField` back to `OutlinedTextField` | twin of I21's guard (New group) |
| A22 | AddExpenseScreen.kt | the currency pill toggles the chips again | the Android currency pill guard |

57 rows: 10 values (V), 3 parity (P, Android lane), 22 iOS (I), 22 Android (A). Both lanes in parallel; about 45 minutes
with the simulator booted.

## Look

Throwaway devices only, against the local emulators: iOS `d214-look` (`xcrun simctl list devices | grep BDC0AE93` for
the full UDID); Android the throwaway AVD `run-look` (run-common; API 34 `system-images;android-34;default;arm64-v8a`,
`-gpu host`), deleted after if created. Never AFAF4221, EB725812, `skipti-shots`, `skipti-smoke` or `Pixel_10_Pro_XL`.
Rebuild after the sabotage run; check the dylib and APK are fresh (mtime, and `strings` for "Asks first. Cannot be
undone."). Data: `--groups-proof` for the forms and pages; the local Auth emulator's proof account (`--emulators
--emulator-as ...`) for the signed-in Security rows, Your phones names, Delete account, a shared group's People with
roles and Invite rows. The lock: iOS Simulator Features, Face ID, Enrolled; Android `adb shell locksettings set-pin
1234` on the throwaway AVD (cleared after with `locksettings clear --old 1234`).

Pairs (iOS beside Android, same unit scale as D-217's `pair.py`) in all five sets (Aurora light, Aurora dark, Norse,
Vardr Labs light, Vardr Labs dark) unless stated:
1. Security without an account (App lock, privacy switch, Careful with Erase) and signed in (Your phones with names,
   Delete account): danger titles, no hairline under Careful, the switch clear of its words (L-D217-9).
2. The App lock page: the check on the stored choice; Off raises the alert (Keep it on leaves it unchanged); the row's
   summary after choosing 5 minutes. On a phone that cannot lock (before the PIN): "Needs a phone lock" and the
   caption only.
3. The switch on and off, enabled and disabled, in every set; its thumb shape per theme; Reduce Motion on (no slide).
4. The Erase page and its alert (Cancel only, unless the vault is a throwaway one).
5. Your data: three rows, the currency row opening the list page.
6. About, Licences, a licence's page (glass cards, no section hairlines, licence rows 48 or taller on both).
7. Group settings: rows of equal height on both (L-D217-1: measure with D-217's `measure.py`), Delete group.
8. People: a phone-only group (rename field, the cross) and a shared group signed in (roles, Invite card, captions);
   check the D-218 "Owner" pill beside you on both and report a difference.
9. Profile: no name (the glyph at half the size), initials, an emoji chosen (the bordered cell), six columns, the
   hairline name field.
10. New group and Your name; Add expense in Equal, Exact, Itemised; each choice page; Edit expense; the default path
    (type an amount, Add expense) takes the same taps as before.
11. The lock screen (glyph, title, art ground).
12. The largest text (iOS `content_size accessibility-extra-extra-extra-large`, Android `font_scale 2.0`; reset after):
    rows grow, summaries ellipsise, values and switches never overlap their titles.
13. Accessibility, Android: `uiautomator dump`: the switch rows `android.widget.Switch` with `checked`; choice rows
    `RadioButton`; iOS: Accessibility Inspector if available, else recorded for the founder's device check.
Shut every device down after; `pgrep -f qemu-system` empty.

## Expected section 4 residue

- System alerts and confirmation dialogs are each platform's own window (kept on purpose with today's steps).
- The Profile and account page keeps its cards; iOS `Form`s in SignInScreen, ImportFlow and RemovalFlow and Android
  `OutlinedTextField`s in Invites, UsernameStep, Import, Removal and the currency search remain (not seen in a look).
- The choice pages add a push where iOS had a menu and Android had chips (same tap count; the founder judges the feel at
  the look).
- The emoji grid is six columns (iOS had eight); the emoji themselves are each platform's emoji font.
- The switch's off state uses Material's dim thumb on both (iOS's own off switch is a white thumb on a pale track).
- Licence names longer than the bar truncate in the bar title (the row shows them whole).
- The screen-privacy row keeps each platform's true title and detail (iOS cannot block screenshots).
- The People "Owner" pill difference from D-218, if the look still sees it, is reported for a separate check.
- iOS VoiceOver behaviour of the drawn switch and choice rows is proven by source guards; no XCUITest target exists.

## Controller amendments (2026-10-16, binding over the text above)

- **A1 (from the D-220 look, `.superpowers/sdd/d220/look-report.md`, pairs `.drafts/d220/look/pairs/`):** iOS's system
  List/Form sections stay rounded in Norse and Vardr Labs (People, Group settings with a 22 pt corner, Add expense);
  C2's plan said Group settings goes square. S2's conversion of the in-scope iOS Forms to themed list cards closes this:
  make sure People, Group settings and Add expense are among them, and the look shows each in Norse and Vardr Labs.
- **A2:** iOS sheet Done/Cancel are system glass capsules on iOS 26+ (cannot be restyled); show the pair and record the
  remainder if Android's pills still differ.
- **A3 (from D-228's inventory):** twin copy gaps: Android-only add-expense kickers ("WEIGHTS, NOT AMOUNTS", "One person
  paid, nobody owes") and an iOS-only `VaultRecoveryView` paragraph: make the twins identical (decide which side is
  right from SPEC by range and the Look Book; record the choice).

## Controller amendments (2026-10-17, session 5; binding)

- **A4 (routed from D-223's look):** the expense detail's "Added by" stamp reads "Oct 17, 2026 at 6:01 AM" on iOS and
  "Oct 17, 2026, 5:53 AM" on Android: one wording on both, on the app's date axis (`AppLocale` / `AppDate`, en-US /
  en-IN), with a golden on each shell for a fixed instant, zone and both tags. Task 6 (iOS) and Task 7 (Android).
- **A5 (routed from D-223's fix re-review m4):** Android's Expenses, Groups and Settle up tabs lose their scroll offset
  after a push and Back or a tab switch; iOS keeps it. Keep each tab's place the way D-223 did for Home (`HomeKeep`,
  the bar height above the route switch), with a guard. Task 7 (Android).
- **Note:** the plan's file:line anchors were read before D-223 and D-224 merged; find each site by its code.
  `ThemeArt.metrics`, `TypeScale` and NOTICE counts moved in D-223 and D-224: recount from the tree.
- **A6 (founder, 2026-10-17, replaces A3's first half):** both phones show the two add-expense hints in the kicker
  style: under shares, "WEIGHTS, NOT AMOUNTS: 2 ADULTS + 1 CHILD AS 2:2:1"; on payer-only, "One person paid, nobody
  owes: logged in the group" (Android's text with its em dashes replaced by colons). iOS gains them (Task 6); Android's
  copy loses its dashes (Task 7); one copy table, parity-tested.
- **A7 (founder, 2026-10-17):** Android gets the twin of iOS's `VaultRecoveryView` (D-127): when the vault will not
  open at launch, the same honest screen ("Ledger unavailable", the reason, the explanation paragraph without its em
  dash, "Start fresh" then the confirm step "Erase and start fresh" / "Cancel"), same order, look and copy, never an
  automatic erase; a JVM test that a vault-open failure routes to it and that erase needs the confirm. Task 7 (Android),
  with iOS's paragraph de-dashed in Task 6 to keep the copy identical.
- **A8 (founder, 2026-10-17):** all four add-expense hints on both phones: A6's two plus "Percentages must add up to
  100" (Percentage) and "Equal split, plus or minus each adjustment" (Adjustment). iOS gains them in Task 6 from the
  same copy table, parity-tested against Android's `AddSheetCopy`.
- **A9 (founder, 2026-10-17):** the vault-recovery screen gains "Try again" on both phones: the primary button re-opens
  the ledger (a success goes on to the app as a normal launch); "Start fresh" stays secondary with its confirm step; the
  paragraph says a retry may help and erasing is the last resort (copy decided in the iOS task, the same on both, no em
  dash). A failed erase shows `EraseCopy` failed wording and keeps the screen on both (T7 review Important; iOS's
  `resetVault` used `try?`). iOS in Task 6, Android in the fix wave.
- **A10 (controller, decision 25):** the "Added by" stamp's time follows the phone's own 12 or 24-hour setting on both
  phones (iOS's template already does; Android picks `h:mm a` or `HH:mm` from `DateFormat.is24HourFormat`), the rest of
  the pattern as A4 pinned it. Goldens for both settings on both shells.
