# E2: theme art (design)

Approved by the founder 2026-10-15 (popups; section 1 and section 2 each approved). Feedback-round spec
`2026-10-13-feedback-round-design.md`, "Units, in build order" 4, and its "The three themes"; the visual reference is
the approved Look Book v2.1 (https://claude.ai/artifact/EGKh2cQvJkT6d6wESv2QUk, source
`.drafts/lookbook/skipti-lookbook.html`) and, for the two calls below, the demo
https://claude.ai/artifact/Kv3JqDXUz13b2omN3FCwvi (source `.drafts/e2/titles-mock.html`). Builds on D-212 (E1, ADR-0041).
Nothing deploys until 0.5.0.

## Founder's decisions (2026-10-15)

1. **One spec, two units**: D-213 static art for all three themes; D-214 motion (the living backgrounds, the heroes'
   art, the hex bolt's turn, still mode, performance).
2. **Titles** (demo option A): every title takes the theme's display face; the Norse ornament (a gold rule, ᛜ, a
   silver rule) sits under the title on the four tab roots (Balance, Expenses, Groups, Settle up) and You only; pushed
   pages get the face only. iOS keeps the system navigation bar and its large title that folds into the bar on scroll.
3. **Vardr Labs' main button is outlined** (demo, last section): a 1 pt steel border, IBM Plex Mono uppercase words at
   .08 em, square; the second action is plain steel text. Aurora and Norse keep filled buttons.
4. **Performance** is measured on the simulator and emulator in D-214; the founder judges it on his own phones with
   the 0.5.0 internal build. A debug build never goes on his own phone (the same bundle id would replace his real app
   and ledger).

## What exists (facts found, 2026-10-15)

- (Corrected D-215: the bullet below is superseded. Both phones must look identical, type included (the founder,
  2026-10-15), so D-215 bundles our own Vardr Runes Carved Regular on both shells; see ADR-0041 "Theme art".)
  **Runes need no bundled font.** iOS 26.5 and 27 draw the Runic block (U+16A0 to U+16F8) through the system's Apple
  Symbols (checked in both simulator runtimes' fonts); Android 34 and 37 ship `NotoSansRunic-Regular.ttf` (checked in
  the system images). The feedback-round spec bundles Noto Sans Runic only if the system lacks one; it does not. The
  glyphs differ a little between the platforms; that is accepted. A test on each shell proves the glyphs used resolve
  to a real font (iOS: `CTFontCreateForString` over the system font returns a font covering each code point; Android:
  `Paint.hasGlyph` on each).
- E1 put every card through `glassCard`, every section label through `microLabelFont` / `Type.kicker`, every ground
  through `auroraBackground`, every primary through `PrimaryButtonStyle` / `PrimaryAction`; the tab bars are
  `SkiptiTabBar` (iOS, with `RaisedAddStyle`) and `SkiptiTabBar` in TabBar.kt; tiles are `PictureTile` (both shells).
  iOS has 47 `navigationTitle` screens; Android's tab-root titles are in-content `Type.label(dark, 28)`.
- About 20 iOS and 23 Android sites join meta lines with `" · "`.

## Glyphs (one table, both shells)

| Use | Glyph | Code point |
|---|---|---|
| Balance tab | ᚠ Fehu | U+16A0 |
| Expenses tab | ᚷ Gebo | U+16B7 |
| Groups tab | ᛗ Mannaz | U+16D7 |
| Settle up tab | ᛏ Tiwaz | U+16CF |
| Meta separator | ᛫ | U+16EB |
| Section label mark | ᛟ Othala | U+16DF |
| Title ornament | ᛜ Ingwaz | U+16DC |
| Hero watermark | ᚠ | U+16A0 |
| Rune drift | the 24 Elder Futhark runes ᚠ ᚢ ᚦ ᚨ ᚱ ᚲ ᚷ ᚹ ᚺ ᚾ ᛁ ᛃ ᛇ ᛈ ᛉ ᛊ ᛏ ᛒ ᛖ ᛗ ᛚ ᛜ ᛞ ᛟ (all 24 and ᛫ checked present in Apple Symbols, iOS 26.5) | not contiguous in the block |

## 1. D-213: static art (approved section 1)

The art lives inside the shared components screens already use; no screen-by-screen rewrite.

| Piece | Aurora | Norse | Vardr Labs | Where |
|---|---|---|---|---|
| Tab icons | today's Lucide | the runes above (system rune font), labels kept | Lucide | tab bars |
| Active tab | accent pill | no pill: the rune in gold with a 2 pt gold underline | no pill: a square 1 pt outline in the ring colour (cyan) at 50% | tab bars |
| The + | accent circle | gold diamond: a 45-degree square, radius 6, gradient #E2C579 to #A9852F, glyph #120E05 | hex bolt head: a hexagon, brushed-steel gradient #E3EAF0 / #9DB0BF / #6C7F8E / #C3CFD8 with a reticle ring, glyph #0B0F13 (static here; it turns in D-214) (Corrected D-213: these are the DARK bolt's stops; the Look Book draws Vardr Labs light's bolt darker, #8DA0AF 0 / #4C6172 0.45 / #24333F 0.70 / #6C7F8E 1, glyph #EEF1F4, reticle white 0.25, else it vanishes on the light page; D-213 follows the Look Book; both glyphs are the set's onAccent) | `RaisedAddStyle` / Android twin |
| Cards | glass, radius 20 | notched corners (an 8 pt chamfer on each corner) with a #4A3F24 edge, the Look Book's dark gradient | square, with frame marks: an 8 pt L at each corner in textDim at 70%, inset 3 pt | `glassCard` |
| Tiles (group, person) | rounded (size x 0.3) | a diamond (45-degree square, radius 2, #4A3F24 edge), content counter-rotated, the initial in Cormorant SC | square | `PictureTile`, avatar initials (Corrected D-213, reading recorded: "person" tiles are `PersonAvatar`'s initial on a tone; `AvatarView`, the picture slot of a photo, an emoji or your own initials, stays round in every theme with Norse's ring, as the Look Book's `.avatar`; the diamond is an upright rhombus clip, so nothing is counter-rotated) |
| Avatars | as now | a 1 pt #4A3F24 ring | as now | avatar views |
| Section labels | as now | ᛟ before the label, in the accent | as E1 | `microLabelFont` / `Type.kicker` (Android: the mark is prepended inside `kicker`) (Corrected D-213: a mark inside those would put ᛟ before status words, hints and row flags, a third of the sites; the mark is an opt-in `.sectionLabel()` / `SectionLabel` on section labels only, by the plan's rule) |
| Meta separator | " · " | " ᛫ " (the ᛫ in the accent where the line is styled; plain text otherwise) | " · " | one theme-aware constant (`Separator.meta` on both shells) replacing every `" · "` join in app UI; notifications and exported files keep " · " (the system draws them) |
| Titles | display face | Cormorant SC; the ornament under the 4 tab-root titles and You | display face | iOS: the navigation bar's title and large-title attributes take `TypeRole`'s display face for the theme, re-applied to every live bar on a switch; the ornament is the first row of those five screens' content. Android: tab-root titles move from `Type.label(dark, 28)` to `Type.display`, the ornament a composable under them |
| Main button | filled | filled, radius 3 | outlined: 1 pt accent border, accent words in Plex Mono uppercase .08 em, square; the second action plain accent text | `PrimaryButtonStyle` / `SecondaryButtonStyle`, `PrimaryAction` / `SecondaryAction` |
| Page ground | the glow (D-214 makes it curtains) | plain bg (D-214 adds the drift) | a static dot grid: accent at 45% in 0.6 pt dots on a 14 pt pitch, the layer at 45% | `auroraBackground` |

Measured (AppearanceTests, both shells, every set it appears in): the accent separator and label mark on bg, surface
and raised (4.5:1); the outlined button's words and border on bg and surface (4.5:1 words, 3:1 border); the gold
underline and the cyan tab outline on the tab bar (3:1); the frame marks (3:1, non-text); the + glyph on the diamond's
and the bolt's lightest stop (3:1, non-text) (Corrected D-213: for a dark glyph the lightest stop is the best case; the floor is measured against every colour of the art inside the 28 pt glyph box, worst 4.08 on Vardr Labs light; and the D-213 nudge: Vardr Labs light's tab outline is the ring at 0.75, not 0.50, which measured 2.1:1 on the bar); text over the Vardr dot grid at its darkest dot (textDim 4.5:1, text 7:1).

## 2. D-214: motion (approved section 2)

- **One engine per shell.** Every animation is a pure function of time and a fixed seed returning the positions,
  sizes, rotations and opacities of its parts; drawn with `TimelineView` + `Canvas` (iOS) and a Compose draw modifier on the
  frame clock (Android), capped at **30 fps** on one shared 1/30 s grid, paused when off screen or in the background.
  (Corrected D-214: Android's renderer is a draw modifier (`artCanvas`, `withFrameNanos`), not a Compose `Canvas`; and
  "30 fps" is one grid shared by every art in the window, `ArtSchedule` on iOS and `FrameGate` on Android: per-art
  gates landed on alternating vsyncs in about half of Aurora launches and drew 60 frames a second, wave 3 / P2.)
- **Still mode**: under Reduce Motion (iOS `accessibilityReduceMotion`; Android animator duration scale 0) or Low
  Power (iOS `isLowPowerModeEnabled`; Android `isPowerSaveMode`), each background draws a designed still frame and stops
  ticking. A pure function `shouldTick(reduceMotion:lowPower:active:)` decides, twinned and tested.
- **Aurora**: three green-dominant curtains (vertical ray streaks; a faint pink upper fringe, a cyan lower edge)
  swaying on 11, 13 and 17 s cycles, over a starry night sky in dark mode and an arctic dawn in light mode (the
  Look Book's 0.35 each; strengths: dark page curtains 0.26 and stars 0.16, light page 1.00). They replace the three
  blooms (E1 residue (c) closes with them). The hero is always the night sky with its own curtains, at full strength
  (curtains 1.0, stars 1.0; the founder, 2026-10-15), not floored.
  (Corrected D-214: the strengths are the founder's decisions, `motion.py` section 1b. Each curtain hangs in the Look
  Book's arched hem, `border-radius: 0 0 50% 50% / 0 0 30% 30%` (lower corners quarter ellipses of radii 0.5 w by 0.3
  h from 0.70 h), which the plan and `motion.py` omitted: without it the faint tail's square corners read as nested
  boxes under the blur. The "vertical ray streaks" average to their mean (0.60) on both shells, because the blur runs
  after the bar pattern (the Look Book blurs before its mask, so its bars are crisp); crisp bars break the floors
  (dark textDim 4.27 at a 0.80 factor). The Look Book's screen and multiply blends act among the curtains in one layer
  and the layer composites source-over onto the ground: a true multiply on the light page measures textDim 4.40 at y
  0.07, below 4.5.)
- **Norse**: 16 runes from the Elder Futhark rise slowly behind every screen, mostly gold and a few silver,
  opacity at most 0.18 x 0.79, turning up to 14 degrees as they rise. The hero gets the large ᚠ watermark (accent at 16%) and an
  inner frame (accent at 25%) inset 5 pt.
  (Corrected D-214: 16 runes, the Look Book's count; the page strength is 0.79, which holds the peak silver rune
  against textFaint at 4.5 on 8-bit pixels (4.4987 at 0.80); the hero's watermark and frame are at strength 1.00.
  Runes are drawn centred on (x, y), not anchored at the left and bottom as the Look Book's are.)
- **Vardr Labs**: the hero carries 22 currency signs in Plex Mono ($ ₹ € £ ¥ 元 ₩ ₽ ฿ ₺ ₦ ₱ ₪ R$ A$ and more), each
  on its own slow, smooth path at its own size and depth, mostly steel with a few in cyan, quieter behind the balance
  text (opacity x clamp((x - 0.2) / 0.45, 0.28, 1)); ₹ (largest, cyan), $, €, £ and ¥ always float in clear view on the
  card's right side (centre x at least 0.55; measured 0.595 at its furthest left). Light
  mode has a light hero. The hex bolt turns 30 degrees when pressed (a spring), not under Reduce Motion.
  (Corrected D-214: 22 signs, from the Look Book's list; the quietening is the calm clamp, 0.28 at the left, not a
  flat half; strengths 0.60 light and 0.54 dark, after the founder's 8-bit rule moved the light one from 0.61. The
  bolt's turn is a spring of response 0.45 and damping 0.55 and the + glyph turns with the hex, as the Look Book's
  `.plus:active` does.)
- **Text over moving art**: art sits behind cards everywhere except the heroes. On an Aurora page, text holds 7:1 and section labels
  4.5:1 over the art, measured on the three curtains stacked (`stack.py`), and faint and accent text sitting on the page
  ground take `groundFaint` / `groundAccent`. On a hero, the brightest pixel the art can reach (each part's maximum
  opacity composited over each hero stop) is measured: hero text 7:1, dim hero text 4.5:1, in every set except
  Aurora's hero, which is pinned at full strength and not floored (the founder, 2026-10-15). Every floor holds on 8-bit
  pixels.
  (Corrected D-214: as the founder decided 2026-10-15. The first design measured each curtain alone and the three
  overlap in the title band; the stacked composite is the measure, and as drawn (source-over) the Aurora dark page
  reads text 11.68, textDim 5.24 and accent 8.25, the light page text 12.62 and textDim 4.71. The 8-bit rule: colours
  are rounded to 1/255 before every ratio. Vardr Labs' dim hero line is `over(onHero, 0.82, hero stop 0)`, as
  `motion.py` defines it.)
- **Performance** (recorded in the D-row): iOS simulator, Instruments, main-thread work per frame at most 4 ms at
  30 fps on Home in every theme; Android throwaway AVD, `dumpsys gfxinfo` janky frames under 5%; idle CPU on Home in
  every theme. The founder's phones at 0.5.0.
  (Corrected D-214: measured as the whole main thread at 12% of one core, which is the 4 ms a frame at 30 fps; iOS
  Time Profiler on the simulator, Home in every theme, passes after wave 3 (Vardr Labs 7.7% and 9.6%, Norse 8.3%,
  Aurora 10.3% and 10.0%). Android's `dumpsys gfxinfo` janky-frame metric reads 100% at a 30 fps cadence on the AVD
  although no vsync is missed and every frame is 33.33 ms apart, so it fails as written and the phone check at 0.5.0
  decides.)
- **Tests**: field positions at fixed times (goldens worked by hand, e.g. a rune's height after one cycle), bounds
  (every part inside its box over a full period; the five major signs on the right side, x at least 0.55), still mode (`shouldTick`
  both ends), worst-case hero contrast, twins; a sabotage row for every guard; a look on throwaway devices with the
  local emulators.
  (Corrected D-214: the engine keeps the five majors at x 0.5950 or more over all time; "right third" was never what
  the Look Book's positions give (0.62 to 0.94).)

## Records

- ADR-0041 gains a section "Theme art" (the glyph table, the static pieces, the engine, still mode).
- The D-213 and D-214 rows, each with its §3 evidence, §4 residue and changelog line.

## Not in E2

E3 done moments and haptics (the Haptics row, Norse's turning Futhark on a big done moment); E4 the completion ring
(Norse: the 24-rune ring), the settled meter (Norse: segmented), the settled celebration (Aurora sparks, Norse runes,
Vardr Labs cyan points) and milestones; Vardr Labs' "square badges with a dot" belong to E4's progress pill.
