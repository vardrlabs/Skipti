# ADR-0041: Selectable themes (Aurora, Norse, Vardr Labs)

Status: Accepted (founder-decided 2026-10-14, D-212; supersedes ADR-0038's single palette, amends ADR-0036).
Amended by ADR-0043 (2026-10-20, D-244, the founder's request): with an account, the theme, light or dark, the text
size and the app icon choice follow the account to its other phones; decision 1's and the App icon section's "never
synced" no longer hold.

## Context

ADR-0038 (revised by D-176) gave SKIPTI one look, the violet "Aurora", in a
light and a dark set, plus ADR-0036's Night. In the 2026-10-13 feedback round
the founder asked for a revamp and approved a Look Book (v2.1,
`.drafts/lookbook/skipti-lookbook.html`) with three looks: Aurora (an arctic
green, the new default), Norse (black, gold and silver, always dark) and Vardr
Labs (the company site's steel). On 2026-10-14 he decided, in popups: Night is
dropped for now; Aurora keeps the Look Book green for buttons; Aurora's money
becomes a calmer red (you owe) and green (you are owed), apart from the accent
and from each other in lightness; Vardr Labs keeps cyan and ember and Norse its
pair; Cormorant SC may be downloaded for Norse.

## Decision

1. **Three themes, chosen per phone**: Aurora (default), Norse, Vardr Labs.
   Stored outside the vault beside the mode (iOS UserDefaults
   `appearance.theme`, Android `skipti-appearance`/`theme`), never synced. An
   unknown value reads as Aurora.
   *Amended by ADR-0043 (2026-10-20, D-244):* with an account the theme, the
   mode, the text size and the icon choice follow the account (a personal
   `setting` record each, the latest write per key wins); without one they stay
   on the phone.
2. **Five token sets**: Aurora light, Aurora dark, Norse (dark only), Vardr
   Labs light, Vardr Labs dark. Every token has a value in each. **Norse forces
   dark at resolve time**; the stored mode is kept for when the person leaves
   Norse.
3. **Values** come from the Look Book table; derived tokens (surfaceRaised,
   textFaint, neutral, watch, the soft fills), Aurora's money pair, the hero
   grounds and the per-theme group tones are computed and measured by
   `.drafts/d212/palette.py`. A Look Book value under a floor is nudged to the
   nearest passing value (D-212 lists them: Aurora light accent #0C8A6C ->
   #0B7A5F, Vardr Labs light owing #0A6D93 -> #0A6C92, two Vardr Labs light
   tone letters).
4. **Floors** (both shells' AppearanceTests, every set): text 7:1 on bg,
   surface and raised; dim, faint, money, neutral, watch and accent 4.5:1 on
   them; each fill pair 4.5:1; in Aurora also on the glass composites; the
   ring 3:1; the hero's text 7:1 on every stop, its dim text and chips 4.5:1;
   every group tone's letter 4.5:1 on its fill. Money is never colour alone:
   the sign and the words are always printed.
5. **Faces per theme**: display Space Grotesk (Norse: Cormorant SC); body
   IBM Plex Sans (Vardr Labs: Space Grotesk); numbers Space Grotesk with
   tabular figures (Vardr Labs: IBM Plex Mono); button words IBM Plex Sans
   (Vardr Labs: Plex Mono); section labels IBM Plex Sans (Norse: Cormorant
   SC; Vardr Labs: Plex Mono, uppercase, 0.22 em). The Recovery Kit's words
   stay Plex Mono everywhere.
6. **Shape**: card radius Aurora 20, Norse 3, Vardr Labs 0; buttons never
   rounder than cards (14 in Aurora). Glass and the glow are Aurora's alone;
   the others draw solid cards on a hairline (their art is E2).
7. **Text size**: -2 to +3 steps from the phone's own size, SKIPTI only
   (`appearance.textSize`). iOS moves the phone's Dynamic Type category along
   its ladder (window trait override; the type roles scale from the same
   category); Android multiplies the phone's font scale by the same body-size
   ratios (0.88 to 1.35, clamped 0.75 to 2.0) at the root density.
8. **How it reaches the screens**: iOS carries the theme as a trait
   (`ThemeTrait`, bridged into SwiftUI's environment, as ADR-0036's Night
   was) so colours resolve per window, and as observable state
   (`AppearanceState`) that the type roles read inside each body so open
   screens re-draw their faces on a switch; system controls take the accent
   through the window tint. Android reads `Appearance.theme` (snapshot state)
   inside `Tok`, `Type` and the group tones, and overrides `LocalConfiguration`
   (Norse: night) and `LocalDensity` at the root.

## Consequences

- A new token needs five values, measured in every set on both shells.
- ADR-0038's palette is superseded; its language rules (sentence case, the
  button styles, light first) stand. ADR-0036 is amended: Night is retired
  for now and returns as its own decision in a later version.
- Cormorant SC (OFL 1.1, no Reserved Font Name) ships unmodified, pinned by
  sha256 in `scripts/licences.py`, listed in NOTICE and the Licences page.
- E2 adds each theme's art (curtains, runes, frame marks), E3 the done moments
  and haptics, E4 the ring and meter (their tokens are measured now).

## Theme art (D-213, 2026-10-15)

E2's static half (spec `docs/superpowers/specs/2026-10-15-e2-theme-art-design.md` section 1; D-214 adds the motion).

- **One place per shell.** `ThemeArt.swift` / `ThemeArt.kt` hold the runes, the art's own colours and sizes (derived
  and measured by `.drafts/d213/art.py`), the geometry as vertex lists, and one pure decision per piece:
  `TabIndicator` (Aurora pill, Norse 2 pt gold underline, Vardr Labs square outline in the ring colour), `AddButtonArt`
  (circle, gold diamond, steel hex bolt), `CardStyle` (glass, notched 8 pt with the #4A3F24 edge on a #161512 to
  #101011 gradient, square with frame marks), `TileShape` (group: rounded / diamond / square; a person's initial:
  circle / diamond / square), `ButtonLook` (Vardr Labs' main button outlined, second action plain words; the others
  filled), `Ground` (glow / plain / a still dot grid), `SectionMark` (Norse's ᛟ), `AvatarStyle` (Norse's ring on a
  picture). The shared components read them; no screen draws its own art. Android's parity test reads
  `ThemeArt.swift` and compares every rune, colour and size.
- **Runes come from our own font, Vardr Runes Carved Regular** (D-215; OFL 1.1, Reserved Font Name "Vardr", built in
  the private `vardrlabs/fonts` repo, release v1.000, pinned by sha256 on both shells), so both phones draw the same
  shapes. iOS makes it the first Core Text cascade fallback of every text face, so a rune inside any text draws from it;
  Android (minSdk 26, no per-glyph fallback) puts a rune span (Normal weight, no synthesis) on every rune character.
  Each shell's tests open the bundled file and prove every glyph used is in it, with U+16F9 as the negative.
  (Corrected D-215: this line said the runes came from the system's fonts, Apple Symbols and Noto Sans Runic, with no
  font bundled and a debug `--rune-proof` on Android; the founder required both phones to look identical, type
  included, and D-215 bundled Vardr Runes and retired the proof.)
- **Glyphs**: tabs ᚠ ᚷ ᛗ ᛏ, separator ᛫, section mark ᛟ, title ornament ᛜ; the 24 Elder Futhark runes for D-214's drift.
- **Words**: the ᛟ mark is opt-in (`.sectionLabel()` / `SectionLabel`) and goes on section labels only (a label that
  names what follows); status words, hints, row flags and labels carrying a name or a debt keep the plain micro face.
  The meta separator is theme-aware (`Separator`): ᛫ in Norse, in the accent where a body draws the line, plain in
  copy; notifications and exported files keep the middle dot. Titles take the theme's display face on the system's
  navigation bars (iOS, live bars re-styled on a switch) and through `Type.title`, which only Android's `SkiptiBar` calls (D-217; this said Android's five tab roots); the Norse
  ornament (gold rule, ᛜ, silver rule) sits under the four tab-root titles only (Corrected D-217: this said "and You only"; You is a pushed screen
  on both phones and has no ornament).
- **Measured** (AppearanceTests, both shells): every text floor on the Norse card gradient; the gold on the tab bar;
  Vardr Labs' outline, glyph, outlined words and border, frame marks, and text over the grid's darkest dot; the + glyph
  against every colour of the art inside the glyph's box. One nudge: Vardr Labs light's active-tab outline is the ring
  at 0.75, not the Look Book's 0.50 (3:1 on the bar). Vardr Labs light's bolt uses the Look Book's darker light stops.

## Type and shapes (D-215, 2026-10-15)

- **Faces:** body text is IBM Plex Sans 1.1.0 (unmodified, bundled on both shells) in Aurora and Norse; Vardr Labs keeps
  Space Grotesk and IBM Plex Mono; Norse display stays Cormorant SC. Runes come from our own Vardr Runes Carved Regular
  (vardrlabs/fonts v1.000), bundled on both shells; no system font draws visible text the two phones would draw
  differently (the founder: both phones must look identical, type included).
- **Shapes:** Norse notches every rectangle the user touches or reads in, like its cards: buttons and inputs at a chamfer
  of 8, inner panels and stat tiles at 8 above 40 pt and 6 below. One decision per shell (`controlShape`, `panelShape`)
  and tests that no screen draws its own shape.
- **Tab runes:** 34 pt in a 52 x 30 slot, centred by their ink (the founder found 22 and 26 too small for the space).
- **One type scale, iPhone's way (D-217, 2026-10-16):** where the two phones disagreed on a size, iOS's value is the scale,
  one table per shell (`TypeScale`, 12 constants; Android's is compared with the Swift file by a parity test), and no
  screen writes a family's size as a literal. Android no longer fixes a line height (the face's natural one, as iOS).
  Titles and bars are iPhone's on both phones (the founder, Option A): the four tab roots draw a large 34 title, left,
  that folds into the bar as a centred 17 title on scroll; every other screen draws the centred 17 title, with a round
  Lucide back button; a sheet draws Cancel (or Close, or Done) and the title. iOS states a display mode on every
  `.navigationTitle(`; Android draws one bar, `SkiptiBar.kt` (`TabRootScaffold`, `PushedBar` and `PushedPage`, `SheetBar`
  and `SheetPage`; pure decisions in `BarDecisions`), and no screen draws its own header, title or "Back". The `name`
  type role (`TypeRole.name` / `Type.name`) is the theme's body face at 600, for names and row titles; kickers, section
  headings and tile initials take the Look Book's roles. The Norse ornament sits under the large title only.
  Both phones draw the same Lucide `chevron-left` and `chevron-right`. On iOS 26 and later the system draws the back
  chevron itself, in the label colour; Android's takes the text colour to match.

## Motion (D-214, 2026-10-15)

E2's second half (spec `docs/superpowers/specs/2026-10-15-e2-theme-art-design.md` section 2; the strengths and floors
below are the founder's decisions of 2026-10-15).

- **One engine per shell, pure functions of time.** `ThemeMotion.swift` / `ThemeMotion.kt` return the positions, sizes,
  rotations and opacities of every part from a time and a fixed seed: `Curtains` (three, 13, 17 and 11 s, the Look Book's
  sway keys eased per half, an arched hem), `Stars`, `RuneDrift` (16 runes), `CurrencyField` (22 signs, Park-Miller seed
  7, the five majors on the right side), `AddButtonMotion` (the bolt's 30 degree turn, response 0.45, damping 0.55) and
  `Strength`. The renderers only draw what they return: iOS `ArtCanvas` (`TimelineView(ArtSchedule)` and `Canvas`, with
  runes and signs drawn from cached symbols), Android `Modifier.artCanvas` (`withFrameNanos` through `FrameGate`). Android's
  parity test reads `ThemeMotion.swift` (specs, stops, keys, stars, majors, strengths, the turn). `Ground` is now
  `.curtains` (Aurora), `.runes` (Norse) and `.grid` (Vardr Labs, still); the heroes draw through one `HeroArt` /
  `heroArt` (Aurora: the night sky with its curtains and stars; Norse: a still ᚠ watermark and inner frame; Vardr
  Labs: the signs).
- **Still mode.** `MotionClock.shouldTick(reduceMotion:lowPower:active:)` is the one decision, twinned and tested: art
  ticks only when Reduce Motion and Low Power (iOS) or animator scale 0 and battery saver (Android) are off and the art
  is on an active, visible screen. Otherwise it draws a designed still frame (curtains at t 0, the Look Book's
  reduced-motion rune layout, signs at t 4) and does not tick. The renderer's time is always finite
  (`ArtCanvas.seconds`). **Amended 2026-10-20 (D-239, decision 137, the founder's):** power saving stills the art
  only. A moment (a done check, the settle celebration and its burst, a switch's travel, a row opening) follows Reduce
  Motion, Less motion and an active screen, never power saving (`MotionClock.momentTicks` on iOS, `MotionState.moment()`
  on Android). What a moment leaves running after it lands is art again: the done check's Norse ring stops turning under
  power saving (`MotionClock.artTicks`, `DoneCheck.runFor`). Why: the founder's iPhone 17 reported Low Power at launch
  with the switch off (iOS's Adaptive Power is the likely source; it cannot be proven from outside), and every moment
  froze.
- **Livelier Norse (amended 2026-10-20, D-243, decisions 144 to 150; the founder's U24 and his approved Vibrant
  mock-up).** The page runes flare now and then with a soft halo (`RuneGlow`: 0.19 at the flare, halo 0.05), each in
  its own lane (lane k at 2 + 92k/15 % of the width: no two runes ever overlap, so every floor holds on every frame);
  the Norse hero carries 7 rising runes (`HeroRunes`, lanes left of the ᚠ, clipped inside the inner frame) and its ᚠ
  glows on a 9 s cycle (`WatermarkGlow`, 0.16 to 0.208); You's Futhark ring turns once per 90 s (`RingTurn`, art: from
  upright on each start, upright when still); Norse cards carry a static gold glow outside their edge (0.5 pt bands,
  sigma 3.0, reach 6 pt; page text keeps 6 pt from a Norse card). Glows are cached discs or bands, never blurs. Still
  frames carry no flare and no halo. Values from `.drafts/d214/motion.py` sections 5 and 6.
- **30 fps on one grid.** Every art in a window draws on the same 1/30 s grid, so two arts never land on alternating
  vsyncs: iOS `ArtSchedule` (multiples of 1/30 s from the reference date, empty when paused), Android a shared slot grid
  with a minimum gap (`FrameGate`). A frame cap is never a per-art tolerance.
- **Strengths** (each multiplies every Look Book opacity of its layer; `motion.py` section 1b): Aurora dark page
  curtains 0.26 and stars 0.16, Aurora light page 1.00, Aurora hero curtains 1.00 and stars 1.00 (both modes, always the
  night sky), Norse page runes 0.79, Norse hero 1.00, Vardr Labs hero signs 0.60 light and 0.54 dark. The Look Book's own
  opacities break the floors (a dark page's title at 1.1:1), so these are the founder's choices and the floors below
  are what measures them.
- **Floors** (founder decisions): text on an Aurora page holds 7:1 and section labels 4.5:1 over the art; faint and accent
  text sitting on the Aurora page ground take the label colour. The Aurora hero is the exception by decision: at full
  strength and today's layout it is pinned, not floored (about 1.7:1 at the curtains' lowest swing, accepted, a 66% shade
  rejected); Norse's and Vardr Labs' heroes are floored. **Every floor holds on 8-bit pixels**: colours are rounded to
  1/255 before the ratio, in `motion.py` and in both shells' floor tests (this moved Norse page runes 0.80 to 0.79, the
  peak silver rune against textFaint being 4.4987 on a pixel, and Vardr Labs light hero signs 0.61 to 0.60, measured
  against `motion.py`'s dim line `over(onHero, 0.82, hero stop 0)`).
- **Ground tokens.** Two new five-set tokens, `groundFaint` (Aurora light #4D615E, Aurora dark #8FA9A4, Norse #8D8675,
  Vardr Labs light #5E6C75, dark #7A8995) and `groundAccent` (#4D615E, #45E6B0, #CDA750, #4C6172, #9DB0BF): in Aurora
  light both are the label colour, in Aurora dark the accent passes and stays, every other set keeps `textFaint` and
  `accent`. Faint and accent text sitting directly on the page ground uses them; text in a card or a row on its own fill
  does not. Counts are pinned per shell by the tests (they differ, see BUILD-LOG D-214 section 4).
- **The bar tint.** On iOS a SwiftUI toolbar item takes the tint of where its `.toolbar` is declared and the environment
  tint wins over a bar's, so every toolbar item carries `barTint()` (`tint(Tokens.groundAccent)`) on its control, and
  cards keep the accent. On iOS 17 to 25 the back button is made through `UINavigationBarAppearance` copies (title
  colour attributes and an always-original chevron), made again when the phone flips light and dark; iOS 26 and later
  draw the chevron in the label colour in their glass.
- **Stacked curtains are the floor method.** The three curtains overlap where a title sits, so a floor is measured on
  the stacked composite as drawn, never on one curtain alone: `stack.py` (real geometry, t 0 to 441 s, premultiplied
  stops, streak mean 0.60, source-over, 8-bit) and its port in both shells' tests, pinned to its output. The curtains
  composite source-over onto the ground on both shells: a true multiply on the light page measures textDim 4.40 at y
  0.07, below 4.5, so it is not used, and crisp streak bars break the dark page's floors, so the streak pattern is
  blurred to its mean. A change to a strength, a stop, a streak or a blend means re-running `stack.py` first.
- **Performance** is measured on the simulator and the emulator only (BUILD-LOG D-214 section 3); the founder judges it
  on his phones with the 0.5.0 internal build. A debug build never goes on his own phone.

## App icon (D-216, 2026-10-18)

- **Three icons, one per theme**: the cut S in Aurora (the primary icon, so nothing changes for anyone on Aurora), Norse
  and Vardr Labs. iOS: the asset catalog's `AppIcon`, `AppIcon-Norse` and `AppIcon-Vardr`, each with Any, Dark and
  Tinted entries (one grey tinted image serves all three); the two alternates are named in the build settings and
  compiled by actool. Android: an adaptive icon per theme with the shared monochrome layer (Android 13+ themed icons);
  the application icon in system Settings stays Aurora's.
- **The App icon picker** sits on Appearance between "Light or dark" and "Text size": a "Match theme" switch, **on by
  default** (the theme's icon follows the theme), then the three icons as rows, always live (a tap picks that icon and
  turns matching off). Stored outside the vault beside the theme (iOS `appearance.icon`, Android `icon` in the
  Appearance prefs; `match`, `aurora`, `norse`, `vardr`; unknown reads `match`), never synced. *Amended by ADR-0043
  (2026-10-20, D-244): with an account the choice follows the account.*
- **The icon changes when the Appearance page closes** (decision 34), and only if the phone shows another icon: never
  at launch, on a theme tap or in the background, and not when the page is only covered (an alert, the lock, a push).
  iOS shows its own system alert on every change and has no public way to suppress it, so this keeps it to at most one
  per visit, as the answer to what the person just did. The caption under the card says so on both phones.
  *Amended by ADR-0043 (2026-10-20, D-244), the arrival exception:* a choice that arrives from another phone of the
  account changes the icon when SKIPTI next comes to the front, never at the moment it arrives and never in the
  background.
- **Android's launcher entries are three activity aliases**, `com.vardrlabs.skipti.IconAurora` (on by default),
  `.IconNorse` and `.IconVardr`, all opening `MainActivity`, which is no longer a launcher entry itself (it cannot be
  disabled). The switch enables the wanted alias first and then disables the other two, every write without killing
  the app. **These names are permanent**, like the iOS icon set names (CLAUDE.md section 10): a launcher stores the
  component name, so renaming one drops the person's home-screen icon. The Play manifest check enforces the contract.
- Android's notification glyph is the cut S (it replaced Lucide's bell, which left NOTICE); iOS draws the app icon in
  its notifications.
