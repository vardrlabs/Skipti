# D-234: the twin-fix unit

Date: 2026-10-19 (session 8, Opus planner, read-only: nothing was built or run while D-233's A/B measurement ran).
Binding list: `.drafts/d234/list.md` (A1, A2 the founder's choices; T1 a test owed; L1 to L14 the looks' findings).
Decisions 80 onward are proposed here for the controller to confirm or overturn (79 is D-233's). To be committed as
`docs/superpowers/specs/2026-10-19-d234-twin-fixes-design.md` on branch `d234-twin-fixes`.

The rule this unit serves (CLAUDE.md 10): iOS and Android implement the same behaviour, labels, order and copy, and
LOOK identical; a visible difference is a defect unless it is truly impossible, and then it is recorded as residue
with the reason. Copy below is public: no em or en dash anywhere in it.

## 0. Verdicts at a glance

| Item | Verdict | Size | Where |
|---|---|---|---|
| A1 sample line in USD | build | S | T1 iOS, T2 Android |
| A2 App icon as one expanding row | build | M | T1, T2 |
| T1 a real width test for every settings summary | build; by the fonts' advance widths it would fail today on Haptics and on You's account line (the test measures for real), so two copy changes come with it (decisions 83, 84) | M | T1, T2 |
| L1 Android import proof crashes on a vault that will not open | build | S | T4 |
| L2 Import and Review laid out differently | **0.5.1** (decision 86) | L | none |
| L3 Fastest plan order | no code change: a tie broken by random ids on two separate installs (decision 85) | none | look note |
| L4 Android Add expense title clipped under the bar | build: the large title rests as UIKit's (decision 92) | M | T6 |
| L5 vault recovery wraps | build the line-break half on iOS; the width half is residue (decision 90) | S | T3 |
| L6 iOS tab bar missing after a join | build (and the Groups join twin, decision 91) | S | T3 |
| L7 misleading empty states, read-only groups | build (copy, decision 87); INR vs USD is the devices' regions, no change | S | T3, T4 |
| L8 "Someone added" for a removed member | build | S | T3, T4 |
| L9 iOS removed group's tile faded | build (iOS only; Android is right) | XS | T3 |
| L10 celebration words on the page's words | build: a card behind them, both phones (decision 95) | S | T5, T6 |
| L11 Vardr still-frame signs; "Marks this as paid here" wrap | residue: the phones' widths differ (decision 96) | none | look note |
| L12 O1 Android press film on + under Less motion | build: no indication, as iOS (decision 97) | XS | T6 |
| L12 largest text step larger on Android | **0.5.1** (decision 98) | L | none |
| L12 expense rows wrap on iPhone | build: one gap, one card padding, one measure order (decision 99) | S | T5, T6 |
| L13 disabled button label colours differ | build: iOS dims once, as a group (decision 94) | XS | T5 |
| L13 Add expense sheet (iOS) vs page (Android) | **0.5.1** (decision 93) | M to L | none |
| L11 group avatar colours | no code change: each phone seeded its own groups with random ids (decision 85) | none | look note |
| L12 O2 re-tap lands collapsed on iOS | **0.5.1** (decision 89) | M, unverified API | none |
| L13 Android Balance frame before the empty state | build | XS | T4 |
| L14 Android landscape: the bar covers Join a group | build: Android phones portrait, as the iPhone is (decision 88) | XS | T4 |

## A1. The text-size sample line, fixed in US dollars

**Today.** iOS `SampleLine` (`ios/Skipti/App/AccountHubScreen.swift:163-185`) and Android `SampleLine`
(`android/app/src/main/java/com/vardrlabs/skipti/AccountHubScreen.kt:239-260`): "Dinner at Thalassa" and 2400 in the
person's default currency (`DefaultCurrency.current` / `.resolve`), minor units and the string from core. The screen
calls `SampleLine.amount(stored: currencyRaw)` (iOS `:1055`, `:1058`) and `remember(DefaultCurrency.stored) {
SampleLine.amount(DefaultCurrency.stored) }` (Android `:685`).

**Decision (the founder's).** Title "Lunch at Shake Shack", whole amount "48", currency `USD` for everyone; still
`ffiMoneyParseMinor` then `ffiMoneyFormat` (no shell formatting). Locale stays `AppLocale.current` (core knows en-IN and
en-US only; both give `$48.00` for 4800 cents, pinned by a test on both locales). `SampleLine.currency(...)` goes; the
stored default currency is no longer read by the sample. Spoken "Lunch at Shake Shack, $48.00".

**Decision 81 (controller's).** The locale stays the phone's (`AppLocale.current`), not a fixed en-US: both locales core
knows print `$48.00`, so the line is the same for everyone today, and a locale core adds later formats the sample as it
formats every other amount on the phone. Risk if wrong: a future locale prints USD differently (for example `US$48.00`)
on that phone only. Overturn: pass `.enUs` / `LocaleTag.EN_US` in `amount`, one line per shell.

## A2. App icon: one row that expands inline

**Today.** iOS `AppIconSection` (`AccountHubScreen.swift:506-541`) and Android `AppIconSection`
(`AccountHubScreen.kt:973-1010`): a section label "App icon", ONE list card of four rows (the Match theme switch row,
then Aurora, Norse, Vardr Labs as check rows with their previews), the caption under it.

**Decision (the founder's).** One row: "App icon", the current choice on the right ("Match theme", "Aurora", "Norse" or
"Vardr Labs"), then the chevron. A tap opens the existing four rows inline in the same card; a second tap closes them.
Drawn by us on both phones; the expand honours the motion door (D-231); one accessibility element for the header with
its state.

**Built as (decision 80, the controller's; each clause can be overturned alone):**
- A new trailing kind on the shared row, `RowTrailing.disclosure(String, expanded: Bool)` (Android
  `RowTrailing.Disclosure(text, expanded)`): the value at 16 in `textDim` on one line, then Lucide's chevron-right (our
  `RowChevron`, already the same drawing on both phones), rotated 0 degrees closed and 90 degrees (pointing down) open.
  The row draws `.value`'s layout otherwise (the value shares the row half and half and ellipsises).
- The section label "App icon" above the card goes: the header row says it. The four rows sit under the header in the
  SAME card, a 1 pt hairline before each (the card's own rule). The caption ("Your icon changes when you leave this
  page.") shows only while the rows are open, 8 under the card.
- The rows open closed every time the page opens (plain `@State` / `remember`, never stored).
- Motion: when art may move (`MotionClock.shouldTick(reduceMotion:lowPower:active: true)` on iOS, `!(reduce ||
  lowPower)` on Android, the rule `ThemeSwitch` already uses), the rows fade in while the card grows and the chevron
  turns, over `ThemeArt.m("switchTravel")` (0.2 s, the switch's own travel: no new art value) with ease-in-out; under
  Less motion, Reduce Motion or Low Power everything snaps (iOS `withAnimation(nil)`, Android `EnterTransition.None` /
  `snap()`). Pure decisions: iOS `AppIconPlan.expandSeconds(moves:) -> Double?`, Android `AppIconPlan.expandMillis(moves):
  Int` (0 = snap).
- Accessibility: the header is one element, a button, read "App icon, Match theme, collapsed" (iOS label "App icon",
  value "Match theme, collapsed"; Android contentDescription "App icon, Match theme" and stateDescription "collapsed",
  with Compose's expand and collapse actions). Words `RowDisclosure.expanded = "expanded"`, `collapsed = "collapsed"`.
  The four rows keep their D-216 accessibility (Android: the inner column is the `selectableGroup()`).
- The current choice's words: `AppIconCopy.choice(_:)`: match "Match theme", the icons their theme labels.

Why: a header row with a value and a chevron is the list's existing language (Default currency's row); the hairline
and card are D-225's; reusing `switchTravel` keeps the motion values from the generators. Risk if wrong: the founder
wanted the section label kept, the caption always shown, or the open state remembered. Overturn: each is one line per
shell (label back above the card; caption outside the `if`; store `expanded`).

## T1. A real width test for every settings-row summary

**Today.** `DoneMomentsTests.testTheMotionSummaryKeepsToOneLine` and `testTheHapticsSummaryKeepsToOneLine`
(`ios/SkiptiTests/DoneMomentsTests.swift:916-930`, `:424-446`) host the row at 362 pt (a 402 pt phone) and compare its
HEIGHT with one line. `ListRow` draws the summary with `lineLimit(1)` (iOS `ListRows.swift:106-110`) / `maxLines = 1,
overflow = Ellipsis` (Android `ListRows.kt:236-243`): a summary that does not fit is cut with an ellipsis and the row
stays one line high, so the test cannot see truncation. That is how D-231's first summary shipped truncated. Android
has no width test at all.

**Measured here (estimates from the bundled TTFs' advance widths at 14 pt, no kerning; the test measures for real).**
Rows on a settings page sit 20 from each edge; the card pads `listPadH` 15; a tile is `settingsTile` 33 plus
`rowGap` 13; a switch is `switchWidth` 51 plus `rowGap`; a chevron is 20 plus `rowGap`. So a tile-and-switch row's text
column is width - 180 (375 pt: 375 - 40 - 30 - 33 - 13 - 13 - 51 = **195**; 360 dp: **180**); a tile-and-chevron
row's is width - 149 (375: **226**; 360: **211**); a chevron row without a tile is width - 103 (375: 272; 360: 257).

| Summary | Plex Sans (Aurora, Norse) | Space Grotesk at 300 (Vardr Labs draws 400, a little wider) | Fits 375 / 360? |
|---|---|---|---|
| "A tap when something is done" (Haptics, switch row) | 188.5 | 205.0 | Aurora 375 yes, 360 NO; Vardr NO on both |
| "Still art, fewer animations" (Less motion) | 160.9 | 172.6 | yes; Vardr at 360 within about 3 |
| "Signed in with Google · 12 phones" (You, Profile and account) | 213.2 | 224.6 | 360 NO in every theme; Vardr 375 NO |
| "Asks first. Cannot be undone." (Erase) | 183.9 | 199.0 | yes |
| "A week to change your mind" (Delete account) | 177.0 | 192.4 | yes |
| "App lock after 15 minutes" (You, Security) | 162.1 | 172.1 | yes |

**Decision 82 (the instrument, controller's).** One table of every fixed summary a settings row draws on one line
(or two, for Export and Import), with its row's geometry, on both shells:
- You's six rows through `YouRows.parts` for every theme, mode, lock timeout, lock availability, signed out, signed in
  with Apple and with Google, and 1 and 12 phones (the email method and the Your phones line are the person's own data
  and are left to the ellipsis, as is the licences list's data; Delete group's summary wraps, `summaryLines: 0`);
- Haptics and Less motion (tile, switch);
- Security: App lock for every timeout label and "Needs a phone lock", Erase, Delete account (no tile, chevron);
- Your data: Export and Import at two lines (no tile, chevron).
iOS measures each summary in each theme's body face at the default size with Core Text (`NSAttributedString` built as
`Separator.attributed` builds it: Norse's separator rune in the rune font), against BOTH columns: the 375 pt iPhone
(narrowest the app supports: iPhone SE) and the 360 dp Android phone (the same TTF files and the same advances at the
same size; sp equals dp at the default scale, and Android draws these styles untracked). Android measures the same
table with `java.awt.font.TextLayout` (kerning on) on the same TTFs: exact for Plex Sans; for Vardr Labs' variable
Space Grotesk, AWT can draw only the default instance (wght 300), so Android multiplies by
`SummaryWidth.VARDR_WEIGHT_BOUND = 1.05`, and iOS PROVES that bound on every string (width at 400 over width at 300,
Core Text driving the wght axis). A summary fits when its width plus 2 (pt or dp: rounding and hinting in hand) is at
most its column; `summaryLines: 2` rows count lines greedily. The table lives in each test file, built from the app's
own constants (a copy change is measured, not copied).
Risk if wrong: Android's real draw differs from the font's advances by more than 2 dp (Compose draws unhinted
fractional advances; the D-231 look measured 187.5 pt on iOS and 185 dp on Android for the same string, inside the
slack). Overturn: an instrumented Android test (none exists; it would be new infrastructure).
The misleading height tests are REPLACED: `testTheMotionSummaryKeepsToOneLine` and `testTheHapticsSummaryKeepsToOneLine`
go; the height checks were D-224's guard against a Spacer, and that source guard stays (moved into the new test).

**Decision 83 (copy, controller's; the founder may overturn on his return).** Haptics' summary becomes "A tap when it
is done" (Plex 130.7, Grotesk 142.4: inside 180 with room). It keeps the old line's meaning (the success haptic plays
on a save, a settle, an export and an import) and its shape. Risk if wrong: the founder prefers other words; the test
then measures his. Overturn: one constant per shell.

**Decision 84 (copy, controller's).** You's "Profile and account" row says the sign-in method in one word: "Apple" or
"Google" (`SignInMethod.rowLabel`; email "Email"), so "Google · 2 phones"; the Profile and account page keeps "Signed in
with Google". Why: "Signed in with Google · 12 phones" does not fit a 360 dp phone in any theme; the title above already
says Profile and account. Risk if wrong: "Google" alone reads as a brand, not a state. Overturn: keep the long label
and drop the phone count from the row instead.

If the test finds any OTHER summary that does not fit, the implementer stops and reports the widths (no copy is
changed without the controller).

## L1. Android `--import-proof` crashes when the vault will not open

**Cause.** `ImportProof.prove(store)` (`android/.../ImportReview.kt:331-345`) reads the vault twice through
`store.rawKvGet`, unguarded; `VaultGate.open` throws `StoreException.WrongKey`, which escapes `withContext(IO)` in
`ImportProofScreen` (`:356-358`) and kills the app. The proof argument skips the launch's vault recovery
(`AppRoot.kt:428-431`). iOS's proof (`ImportProofScreen.swift:104-205`) never opens the vault, so it cannot crash.
**Fix.** The vault leg catches any exception: `atomicRefusalAndVaultUntouched` stays false (untouched was not proven)
and a new `vaultError` names it in `detail` ("the vault would not open: ..."). A JVM test drives `prove` with a
`LedgerStore` whose `openVault` throws `StoreException.WrongKey()`. Debug-only surface; no twin change.

## L2. Import and Review are laid out differently: proposed for 0.5.1 (decision 86)

**Cause.** iOS draws Import as a system `Form` (`ImportFlow.swift:323-443`: inset-grouped sections, a `Picker` menu for
the currency, SF Symbol checkmarks) and Review as a `List` with `checkmark.circle.fill` / `circle` symbols
(`ImportReview.swift:214-353`), both with `.formActionBar`. Android draws both as plain columns
(`ImportFlow.kt:402-471`, `:518-613`): Material `OutlinedTextField`, `CurrencyChips` (`FilterChip`s), a square
Material `Checkbox`, the primary action inline in the scroll, no cards. Neither side uses D-225's ListCard kit, which
New group already uses on both phones (iOS `GroupSheets.swift:153-157`, Android `GroupScreens.kt:219-222`).
**Decision 86 (controller's).** Move to 0.5.1 as its own design unit. Why: a faithful twin rewrites four screens on two
shells (about 300 lines): both onto ListCard / ListRow / FieldRow / CardCaption, the currency as New group's "Settles
in" row, a drawn round check (no Lucide `circle` is bundled; a new drawing means NOTICE and the licence list), a pinned
action on Android's `PushedPage` (it has no actions slot today) and two SF Symbols gone from iOS. That is a look-level
design pass with its own look, not a twin fix; the copy is already identical. Risk if wrong: 0.5.0 ships with the two
phones' import screens looking different (the flow and words match). Overturn: build it here as T7, after T6.

## L3. "Fastest plan" order: no code change (decision 85)

**Cause.** Neither shell sorts transfers (iOS `SettleAll.swift:221-240`, Android `SettleAll.kt:244-257`); both show
core's order. Core's `norn::simplify` (`core/src/norn/mod.rs:423-464`) breaks a tie between equal creditors by the
lower participant id, and every id is 16 random bytes (`ffi.rs:573-575`). In the look the two phones seeded the same
story into SEPARATE groups with different ids; after the two settlements "you" and Rahul were both owed 200, a tie, and
each phone broke it by its own random ids. Two phones in the same group hold the same ids and show the same order.

## L4. Android Add expense: the large title rests clipped under the Cancel bar

**Cause.** iOS's large title is UIKit's (`AddExpenseScreen.swift:322-323`, presented in `HomeShell.swift:178-191`): it
collapses into the bar, the collapse frees about 52 pt of scroll room, and a drag that ends half way snaps open or
shut. Android draws the large title as the first child of `SheetPage`'s scrolling column (`SkiptiBar.kt:720-773`,
`largeTitle = true` from `AddExpenseScreen.kt:549-554`); the bar's own title appears only once the scroll reaches the
fold (`largeSheetInlineTitleShown`, `:233-234`), and between about 12 and 56 dp of scroll the title is half under the
bar with no inline title. A short form (two people, the pinned Save, a keyboard) has a maximum scroll below the fold,
so it can rest there; nothing snaps.
**Decision 92 (controller's).** Android's `SheetPage(largeTitle = true)` rests as UIKit does: two pure decisions in
`BarDecisions` (`largeTitleRest(scrollPx, foldPx)`: under half way back to 0, else on to the fold; past it, stays;
`largeTitleSlackPx(maxScrollPx, foldPx)`: the room a short form lacks to fold its title), a `LaunchedEffect` on
`scroll.isScrollInProgress` that moves a stopped scroll to its rest (snapped under the motion door, the D-231 guard
counts it), and the slack added to the column's bottom padding (computed against the content's own maximum, so it
cannot feed back). Only Add and Edit expense have a large sheet title. Not touched: the tab roots (their scaffold fills
the viewport already and no look found them resting clipped). Risk if wrong: a fling that stops near the fold jumps a
few dp; the look checks it on both themes with a short and a long form. Overturn: remove the effect and the slack (one
function); the page returns to today's behaviour.

## L10. The settle celebration's subtitle sits on "Where the money went"

**Cause.** `CelebrationOverlay` (iOS `Settled.swift:498-540`, Android `Settled.kt:550-624`) places the two lines at 42%
of the page's height with no backing, over the page dimmed to 0.22. The page's own words are wherever the group page
puts them; Android's status bar is shorter (about 50 dp against 62 pt), so its content sits 12 to 14 higher and the
label rises into the subtitle. Any content at 42% collides on either phone.
**Decision 95 (controller's).** The two lines sit on the theme's card (`glassCard()` / `Modifier.glassCard(dark)`:
Aurora's glass, Norse's notched panel, Vardr Labs' frame), 24 in from the page's sides, still at 42%. Why: the words
can never read on the page's words again, whatever the page holds, and the card is the app's own surface for words.
Risk if wrong: the founder wanted the words bare on the dimmed page; then anchor them to the Settled card instead
(option b of the investigation). Overturn: drop one modifier per shell.

## L11. Vardr Labs still-frame signs; "Marks this as paid here" wraps differently

**Cause.** The still frame is one pure function on both phones (`CurrencyField.pose` at `still = 4`, iOS
`ThemeMotion.swift:203-253`, Android `ThemeMotion.kt:356-410`) and every sign sits at the same FRACTION of the hero
(measured within 1%). The hero is 370 pt wide on the iPhone 17 and 379.4 dp on the Pixel 7, while the hero's words are
fixed sizes anchored left, so the signs move against the words by up to about 7 units. "Marks this as paid here. It
does not move money." (`GroupNextCard`, iOS `Settled.swift:360-429`, Android `Settled.kt:401-475`): the same paddings,
gap and button on both; the only difference is 9.4 units of phone width (and the look's Android shot caught the button
reading "Settling", wider).
**Decision 96 (controller's).** Residue, both: the field is proportional by design (D-214), and two phones of different
widths cannot wrap one sentence the same; a fixed design frame for the field, or a capped words column, would make the
wider phone look emptier to match the narrower. Recorded in BUILD-LOG section 4. Risk if wrong: the founder reads the
sign over "3 groups" as a defect; then the fixed frame (x = bx x 370 from the hero's leading edge). Overturn: that
change, in both ThemeMotion files, with art.py's goldens.

## L12 O1. Android's press film on the + under Less motion

**Cause.** The + face takes Material's ripple (`ThemeArt.kt:986-994`, `indication = ripple()`), which fades in at its
own speed whatever the motion door says; its scale and turn are door-gated (`TabBar.kt:167-187`). iOS's + draws no
press film at all, only the scale and turn (`SkiptiTabBar.swift:137-150`).
**Decision 97 (controller's).** `indication = null` on the +: exactly iOS's look, and nothing left to gate. The other
ripples in the app (the back pill, the account glyph, rows' default indication) are not in this finding and stay; a
door-aware indication for them is a 0.5.1 note. Risk if wrong: TalkBack and keyboard users lose a focus film on the +;
focus still shows through the system's focus highlight. Overturn: restore `ripple()`.

## L12 (largest text step). Android draws small text larger, titles smaller, at +3

**Cause.** iOS moves the content-size CATEGORY along a ladder and each role scales by its own text style (body x1.353,
large title x1.176, footnote x1.46 at XXXL; `Appearance.swift:122-150`, `TypeSystem.swift`). Android multiplies ONE
factor (1.35 at +3) into the phone's font scale (`Appearance.kt:105-130`), and Compose 1.12 applies Android 14's
non-linear table above 1.03 to every sp: 14 sp draws 19.6 dp (iOS 18.9), 16 draws 20.9 (iOS 21.65), a 34 title stays 34
(iOS 40). The curves cross; they also differ whenever the phone's own setting is not the default.
**Decision 98 (controller's).** 0.5.1. Why: the twin fix is a per-role scale on Android (a linear `Density` so Compose's
table stops applying, Apple's Dynamic Type ratios per style from the phone's category plus the step, every `Type.*`
role scaled by its iOS style) with parity goldens for every role at every step: a typography change across every screen,
with its own look at six steps in five sets. Not a twin fix. Risk if wrong: 0.5.0 keeps the difference at the largest
steps only (step 0 matches). Overturn: build it here as its own task.

## L12 (expense rows). The iPhone wraps "Meera paid ..." / "you owe ..." where Android keeps one line

**Cause.** Evidence first: the re-look's `retap-*` pairs were shot with the iPhone still at the +3 step (tab labels 131
px against 94), so the wrap is not proven at the default size. The layout does differ: iOS's row is
`HStack(alignment: .top, spacing: 12) { words; Spacer(minLength: 8); effect }` (`ExpenseListView.swift:370-399`), so
32 between the words and the effect; Android's is 8 (`ExpenseListScreen.kt:479-495`); iOS's card pads 14
(`.glassCard(padding: 14)`, `:176`), Android's 16 (`:328`); and Android measures the effect before the weighted words,
where SwiftUI shares the spare width and wraps the effect too.
**Decision 99 (controller's).** iOS: spacing 0 (the gap is the Spacer's 8, Android's) and the effect
`.layoutPriority(1)` (measured first); Android: the card pads 14 (iOS's). The column then differs only by the phones'
widths. The look re-shoots at step 0 with the step read off Appearance first. Risk if wrong: 8 was not the intended gap
(iOS's 32 was); the look shows both. Overturn: three lines.

## L13. Disabled "Add expense": dim green label on iOS, near-black on Android

**Cause.** SwiftUI's `.opacity` without `.compositingGroup()` dims each layer, so iOS's label (onAccent at 0.45) sits on
the fill already dimmed to 0.45: about `#144538` on `#226D59` in Aurora dark (1.75:1). Compose's `Modifier.alpha` dims
the finished button once: the label is about `#050F11` (3.15:1) (`DesignTokens.swift:178-193`, `:247-254`;
`GroupScreens.kt:776-792`). The app's own rule is "dimmed once, as a group" (`ListRows.swift:188-189`,
`testNorsesModeControlIsDimmedOnceAsAGroup`).
**Decision 94 (controller's).** iOS groups before it dims in the three two-layer button bodies (primary filled, primary
outlined, secondary tonal); Android is the reference and stays. Risk if wrong: none known; the label gains contrast.
Overturn: three lines.

## L13. Add expense is a sheet card on iOS, a full page on Android: proposed for 0.5.1 (decision 93)

**Cause.** Deliberate when made: BUILD-LOG section 4 (D-179's residue) "Android's + opens a full page, iOS's a sheet ... a
Material bottom sheet over a long form fights the keyboard"; D-217 then gave Android `SheetBar` / `SheetPage`, bar forms
drawn as full-screen routes (`AppRoot.kt:985-998`). The founder's rule of 2026-10-15 (twins LOOK identical; a visible
difference is a defect unless truly impossible) postdates that residue.
**Decision 93 (controller's).** 0.5.1, with the design: our own in-window card on Android (a `Box` over the shell with the
page visible above it, a scrim, iOS's top inset and corner, `SheetPage(underStatusBar = false)` inside, a door-gated
slide), for Add and Edit; not Material's `ModalBottomSheet` (its show and hide animations cannot be snapped under Less
motion, it is a second window for the keyboard and the Aurora backdrop, and it brings a drag handle iOS does not have).
Why not here: about a day of shell work with keyboard, back and toast interplay (`ToastCover`), a twin look of its own,
and the D-179 residue to retire with the founder's sight of it. Risk if wrong: 0.5.0 keeps the page. Overturn: build it
as T7 before the look.

## L5. Vault recovery: the reason line and the Norse title wrap differently

**Cause.** Two parts. (1) Width: the look's iPhone 17 has 362 pt of room (402 - 40), the Pixel 7 371.4 dp; the reason
line ("The key that opens this ledger is not on this device.", about 364.5 at 16) and Norse's first title line (about
368.5) fit Android only. (2) iOS's text system avoids a one-word last line and moves "this" down with "device."
(`VaultRecoveryView.swift:179-189`); Android fills lines greedily (`AccountOwner.kt:471-473`). D-224 F9 built
`GreedyText` (`TypeSystem.swift:325-380`) for exactly (2) on the erased title.
**Decision 90 (controller's).** Fix (2) on this screen: every paragraph on the vault recovery screen (title, reason,
explanation, warning, note) draws through `GreedyText`, leading-aligned (a new `alignment` parameter, default centre
as today), in the scaled body font (a new `TypeRole.bodyUIFont`, the twin of `nameUIFont`). With the same room the
two phones then break at the same words. (1) is residue: two phones of different widths cannot wrap the same, and no
layout should narrow the larger phone to the smaller. Not done: the same greedy rule for every paragraph in the app
(iOS's orphan rule applies everywhere); a later unit may extend `GreedyText` where a look finds a difference.
Risk if wrong: a GreedyText paragraph measures its width a frame late and draws the plain text first (as the erased
title does). Overturn: drop the GreedyText calls on this screen (one file).

## L6. iOS: the tab bar went missing on Settle up after a join

**Cause** (`ios/Skipti/App/SkiptiTabBar.swift:215-225`, `HomeShell.swift:155`, `:200-203`). `ShellChrome.covered` is
kept by each root's `onAppear` (remove) and `onDisappear` (insert). A join from Balance posts `.skiptiLedgerImported`;
HomeShell bumps `generation`, which rebuilds all four stacks. SwiftUI calls the NEW roots' `onAppear` before the OLD
roots' `onDisappear`, so the old Expenses, Groups and Settle roots insert their tabs last: the set ends
`{expenses, groups, settle}` and the bar shows only on Balance until a push and pop over each root. The D-229 shots
fit exactly (Expenses was already bare in `p2-i08`). Every poster of the notification has the hazard (Import too).
**Fix.** Each root instance carries a token (`@State private var token = UUID()`); `ShellChrome.rootAppeared(tab,
token)` records the live token and removes the tab; `rootDisappeared(tab, token)` inserts the tab only when the token
is still the live one. A late `onDisappear` from a replaced root changes nothing. Pure test: the order new-appear,
old-disappear leaves the bar shown; a real cover (same token) still hides it.
**Decision 91 (controller's).** The same flow from the Groups tab ("Join a group", `GroupsScreen.swift:114-118`) leaves
JoinPasteScreen and the locked JoinScreen pushed on the Groups stack after Done (Android's join is a route that closes,
`AppRoot.kt:956-968`). Its `onJoined` posts `.skiptiLedgerImported`, as Balance's does: every tab reads the ledger
again, the stacks start at their roots, and the shell shows Balance, the twin of Android's `route = null; selected =
BALANCE; generation++`. Risk if wrong: Groups' join used to land on Balance through `onOpen()` already; only the
leftover stack changes. Overturn: revert one closure.

## L7. Misleading empty states when the only group is read-only

**Cause.** Add expense and Settle up drop read-only groups (iOS `AddExpenseScreen.swift:379-380`,
`SettleAll.swift:39-40`; Android `AddExpenseScreen.kt:479-480`, `SettleAll.kt:73-74`) and then show their "no groups"
words: "Expenses belong to a group. Make one on Groups first." (iOS `:222`, Android `:630`) and "Nothing to settle yet.
Add an expense with the + below." (`SettleAllCopy.nothing`). With groups that are all read-only on this phone, both are
wrong. The INR (iOS) against USD (Android) on that empty form is `DefaultCurrency.current`, the same rule on both
(stored, else region, else USD): the simulator's region gave INR, the emulator's en-US gave USD. No code difference.
**Decision 87 (copy, controller's).**
- Add expense, groups exist but none can take an expense here: "You can't add to your groups from this phone. Make a
  new group on Groups to add one." Pure: `AddExpenseRows.emptyLine(groupCount:writableCount:) -> String?` (nil when a
  group can be used; the old words when there are no groups).
- Settle up, nothing to settle because every group is read-only here: "Nothing to settle from this phone. Your groups
  here can't take new records." Pure: `SettleAllCopy.empty(groupCount:) -> String` (0 groups: the old words).
The iOS empty line also gains Android's `error == nil` condition (it showed under an error too).
Why: the read-only banner says "You can't add to this group from this phone"; these follow it. Risk if wrong: the
words. Overturn: two constants per shell.

## L8. "Someone added Taxi" for a removed member

**Cause.** The expense rows name the author from `namedGroups()`'s CURRENT members only: iOS `ActivityFeed.swift:250`
and `HomeParts.swift:336`; Android `ActivityFeed.kt:273` and `HomeParts.kt:351`. `ActivityFeed.load` fills
`historyNames` (removed people too, `GroupSettings.swift:105-131`, `GroupSettings.kt:134-161`) only for groups with a
moved balance (iOS `:148-152`, Android `:159-165`).
**Fix (twin).** `load` fills `names` for every group in the feed's items as well; one pure resolver
`ActivityFeed.who(_:feed:)` / `ActivityFeed.who(item, loaded)`: the current member's name, else the history name, else
"Someone"; used in all four places. Test on both shells end to end: an expense added by Sam, Sam removed, the feed
names Sam.

## L9. iOS: a removed group's tile looked faded

**Cause.** iOS's header tile and photo are `Button`s with `.buttonStyle(.plain)` and `.disabled(isCopy)`
(`OverviewScreen.swift:803-810`, `:840-846`); SwiftUI draws a disabled plain button's label faded. Android drops the
click and keeps the colour (`OverviewTab.kt:841`, `:873-880`). Not the tone (the same group's tile in its Recent
activity row is full strength).
**Fix (iOS).** `.allowsHitTesting(!isCopy)` in place of `.disabled(isCopy)`, and `.accessibilityHidden(isCopy)` (a copy's
tile is a picture, not a button, as on Android). Source test.

## L11 (avatar colours). No code change (decision 85)

Both shells take the first byte of the group id modulo six over the same six tones in the same order, already held by
`PaletteParityTest.every_ios_group_tone_matches_group_tone` and twin goldens. The look's phones seeded their groups
separately and core gives every new group random ids, so "Goa trip" was blue on one and pink on the other. Two phones
in one shared group show one colour.

**Decision 85 (controller's).** L3 and L11's avatar colours are look-setup artefacts, not twin defects: no code
change, and the twin-fix look (T7) checks both like for like, both phones in ONE shared group (the D-229 / D-230
emulator flow). Risk if wrong: none in code; a separately seeded look will keep showing them. Overturn: fixed proof
ids for `--groups-proof` (both shells' seeds) so separate installs match.

## L12 O2. A re-tap lands with the large title collapsed on iOS: proposed for 0.5.1 (decision 89)

**Cause** (`SkiptiTabBar.swift:239-261`). `ShellScrollTop` scrolls with `proxy.scrollTo(ShellBar.top, anchor: .top)`;
the anchor is already the content's top. UIKit's large title collapses into the inline bar on scroll, and a
programmatic scroll goes only to the current minimum offset (under the collapsed bar); it never scrolls past it, so the
title does not grow back. Android's large title is the first child of the scrolled column (`SkiptiBar.kt:486-506`), so
`scrollTo(0)` shows it.
**Decision 89 (controller's).** 0.5.1. Why: the fix needs either iOS 18's `ScrollPosition(edge: .top)` (deployment
target 17, so a fallback is owed) or a `UIViewRepresentable` that finds SwiftUI's `UIScrollView` and sets the offset
past the collapsed minimum; whether either one makes UIKit grow the title back is unverified, and an experiment on the
shell's scroll views does not belong in a twin-fix unit. Risk if wrong: 0.5.0 keeps the difference (iOS lands one
title height lower). Overturn: a spike task here, gated on the simulator showing the title grow back.

## L13 (Android Balance frame before the empty state)

**Cause.** Android Home reads the vault on `Dispatchers.IO` (`OverviewTab.kt:270-273`); until `HomeKeep.loaded` is true
the `when` (`:300-321`) falls to its `else` branch and draws `HomeGreeting` ("Monday, October 19 / Good evening") for a
frame or more. iOS loads synchronously in `.task` before its first frame (`OverviewScreen.swift:257-260`, `:297-318`),
so it never shows it.
**Fix (Android).** A `!loaded -> Unit` arm after `needsName`: nothing under the title until the first read; the empty
arm then reads `groups.isEmpty() && error == null`. `loaded` is true after any outcome, errors included, and HomeKeep
outlives routes, so only the first read is affected. Source test.

## L14. Android landscape: the tab bar covers Balance's "Join a group"

**Cause.** The iPhone runs portrait only (`ios/Skipti/Info.plist:61-64`, `TARGETED_DEVICE_FAMILY = 1`); Android's
`MainActivity` (`AndroidManifest.xml:55-59`) sets no orientation, so phones rotate. In phone landscape (about 360 dp
tall) the tab root spends 52 dp of top padding and a 41 dp large title before the empty state; "Join a group" lands
under the overlaid bar (D-220). The inset is right; the content can scroll; nothing on iOS corresponds.
**Decision 88 (controller's).** Android phones run portrait, as the iPhone does: `android:screenOrientation="portrait"`
on `MainActivity`. Android 16 (targetSdk 36 and later) ignores the restriction on large screens (smallest width 600 dp
and up), so tablets and unfolded foldables still rotate, where the height is ample. Why: landscape is a layout iOS
never draws, so it can never be a twin; a compact-height layout for it would be a new design. Risk if wrong: someone
using a phone on a stand in landscape loses it. The D-232 lock fixes still matter (a theme or a locale change also
re-creates the activity). Overturn: remove the attribute and build a compact-height tab root.

## Decisions for the controller (80 to 99)

| # | What | Status proposed |
|---|---|---|
| 80 | A2's shape: header row with value and a turning chevron (`RowTrailing.disclosure`); no label above; caption only while open; closed each visit; `switchTravel` ease-in-out, snapped under the door; one button element with its state | build |
| 81 | A1's locale stays the phone's (`$48.00` in both locales core knows) | build |
| 82 | T1's instrument: Core Text measures both columns (375 pt, 360 dp) on iOS; AWT on the same TTFs on Android (Vardr Labs by a bound iOS proves); 2 slack; replaces the two height tests | build |
| 83 | Haptics' summary "A tap when it is done" (fits 360 dp in every theme) | build; founder may overturn the words |
| 84 | You's account row names the method in one word ("Google · 2 phones") | build; founder may overturn the words |
| 85 | L3 (Fastest plan order) and L11's avatar colours are look-setup artefacts (random ids on separate installs); the look checks them in one shared group | no code |
| 86 | L2 (Import and Review layouts) to 0.5.1 as a design unit on the ListCard kit | 0.5.1 |
| 87 | L7's two empty lines (Add expense, Settle up) when every group is read-only | build |
| 88 | L14: Android phones run portrait, as the iPhone does | build |
| 89 | L12 O2 (re-tap lands with the iOS large title collapsed) to 0.5.1 (unverified UIKit API) | 0.5.1 |
| 90 | L5: the vault recovery paragraphs break greedily on iOS; the width part is residue | build + residue |
| 91 | L6's twin: Groups' join ends as Balance's (every tab to its root) | build |
| 92 | L4: Android's large sheet title rests as UIKit's (snap, slack) | build |
| 93 | L13: Add expense as a card on Android to 0.5.1 (own in-window card, not Material's sheet) | 0.5.1 |
| 94 | L13: iOS disabled buttons dimmed once, as a group | build |
| 95 | L10: the celebration's words on the theme's card, both phones | build |
| 96 | L11: Vardr still-frame signs and the explainer's wrap are width residue | residue |
| 97 | L12 O1: no press film on Android's + | build |
| 98 | L12: per-role text scaling on Android to 0.5.1 | 0.5.1 |
| 99 | L12: expense rows: one 8 gap, iOS's 14 card padding, the effect measured first | build |

Each is written above with What, Why, Risk if wrong and Overturn, in the DECISIONS-TAKEN shape.

## Proposed for 0.5.1 (and why not here)

- **L2** Import and Review on the ListCard kit, both shells (decision 86): a four-screen redesign with a new drawing.
- **L12 O2** the iOS large title after a re-tap (decision 89): needs a UIKit spike whose API is unproven.
- **L12** per-role text scaling on Android (decision 98): a typography change across every screen.
- **L13** Add expense as an in-window card on Android (decision 93): shell work with keyboard and back interplay.
- Notes found in passing, not on the list: the other ripples (back pill, account glyph, rows) under Less motion; tab
  roots might also rest with the large title half folded on Android (not observed).

## Residue to record in BUILD-LOG section 4 (truly impossible, with the reason)

- L5's and L11's width parts: phones of different widths (402 pt against 411.4 dp) wrap and place width-relative art
  differently; no layout should narrow the wider phone to the narrower.
- L7's INR against USD on an empty form: each phone's own region (the same rule on both).

## Task order

T0 (controller) -> T1 (iOS) -> [T2 (Android) beside T3 (iOS)] -> [T4 (Android) beside T5 (iOS)] -> T6 (Android) ->
T7 (the paired look) -> the controller's close-out (full gate, records, merge). Android tasks read the Swift the iOS
task before them wrote (parity tests), hence the stagger.
