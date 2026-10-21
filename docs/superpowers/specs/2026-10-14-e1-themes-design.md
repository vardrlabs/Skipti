# E1: the theme engine and Appearance (design)

Approved by the founder 2026-10-14 (popups). Feedback-round spec `2026-10-13-feedback-round-design.md`, "Units, in build
order" 3; the visual reference is the approved Look Book v2.1 (https://claude.ai/artifact/EGKh2cQvJkT6d6wESv2QUk,
source `.drafts/lookbook/skipti-lookbook.html`). Builds as the next D-number after D-211. Nothing deploys until 0.5.0.

## Founder's decisions (2026-10-14)

1. **Night is dropped for now** (it returns in a later version). A stored `night` reads as Dark. ADR-0036 is amended
   to say so; the Night columns, `NightTrait` and the Night tests go.
2. **Aurora keeps the Look Book's green** for buttons and accents.
3. **Money in Aurora becomes a calmer red (debt, you owe) and a calmer green (credit, you are owed)**, distinct from
   the button green and from each other in lightness. Vardr Labs keeps cyan and ember (the founder's Look Book choice)
   and Norse keeps its Look Book pair. Money is never colour alone: every amount already carries its sign and its words
   ("owes", "is owed"), which is what keeps red against green safe for colour-blind people.
4. **Cormorant SC is downloaded** (OFL files from github.com/google/fonts), bundled, listed in NOTICE and the Licences
   page. The rune font (Noto Sans Runic) is a separate ask in E2, after checking whether iOS already draws runes.

## What exists today (survey, 2026-10-14)

- iOS: `Tokens.palette` in `ios/Skipti/App/DesignTokens.swift` is a `[name: (light, dark, night)]` hex table resolved
  by trait (window-level `traitOverrides` set by `AppearanceApplier`, `Appearance.swift`); screens read `Tokens.x`
  (about 510 uses) and `TypeRole.x(` (about 462; `TypeSystem.swift`, every role scaled by `UIFontMetrics`). 18 places
  use `.font(.system…)` and bypass TypeRole.
- Android: `enum class Tok(lightC, darkC, nightC)` in `Tokens.kt`, `Tok.x(dark)` reads `Appearance.night` snapshot
  state; `object Type` (Space Grotesk variable, wght 400-700); every composable takes `dark: Boolean`.
- Appearance today: System / Light / Dark / Night (ADR-0036), stored outside the vault (iOS UserDefaults
  `appearance.mode`, Android SharedPreferences `skipti-appearance`/`mode`), on You -> Appearance (D-206).
- About 40 hard-coded `white`/`Color.White` sites are text on the purple hero; `Aurora.hero` gradient and `GroupTone`
  tiles have their own hex.
- Fonts bundled: Space Grotesk (variable), IBM Plex Mono Regular and Medium; `scripts/licences.py` checks each face's
  OFL name table and the Android copies.
- AppearanceTests (both shells) measure every pair per mode: text 7:1 on bg/surface/surfaceRaised, the rest 4.5:1, and
  the fill pairs 4.5:1.
- No haptics anywhere; no in-app text size.

## 1. The engine

- `Theme`: `aurora` (default), `norse`, `vardr`. `Mode`: `system`, `light`, `dark`. Stored next to today's key, outside
  the vault, per phone, never synced: iOS `appearance.theme`, Android `skipti-appearance`/`theme`. Unknown values read
  as the default (`aurora`, `system`).
- **Norse forces dark at resolve time**: the effective mode is dark whatever the stored mode; the stored mode is kept,
  so switching back to Aurora restores the person's choice.
- Every token resolves from (theme, effective mode). iOS screens keep reading `Tokens.x` (the palette gains a theme
  dimension; the window override carries the theme as a trait as Night was carried); Android keeps `Tok.x(dark)`, with
  `Tok` reading the theme from snapshot state as it read `Appearance.night`. No screen-by-screen rewrite.

## 2. Token sets

- Five sets: Aurora light, Aurora dark, Norse (dark), Vardr Labs light, Vardr Labs dark, from the Look Book table
  (`--p-*` on `.stage.t-<theme>.m-<mode>`): bg, surface, hairline, text, textDim, accent, onAccent, accentSoft (the Look
  Book's `soft`), owed, owing, ring and track, meter stops, radius.
- **Derived per theme** (the Look Book has none): textFaint, surfaceRaised, neutral, watch, owingSoft, owedSoft.
- **Aurora money** per decision 3: calmer red and green for light and dark, each clear of the accent green.
- **Floors as today**: text 7:1 on bg, surface and surfaceRaised; textDim, textFaint, owed, owing, neutral, watch,
  accent 4.5:1 on them; the fill pairs 4.5:1. Translucent surfaces are measured composited over the theme's bg. A Look
  Book value that misses a floor is nudged to the nearest value that passes, and the nudge is listed in the D-row.
- **Hero tokens**: `heroGround` (a gradient per theme and mode) and `onHero`, replacing the hard-coded white on the
  hero (Vardr's light hero is light with dark text). `GroupTone` gets per-theme tones, measured.
- **Shape**: card radius per theme (Aurora 20, Norse 3, Vardr 0). Glass (`glassCard`, `auroraBackground`) becomes
  Aurora's only; the other themes draw solid cards.

## 3. Type per theme

- Aurora: Space Grotesk for display and numbers, the system face for body and labels.
- Norse: Cormorant SC for display and labels, Space Grotesk for numbers.
- Vardr Labs: Space Grotesk for body, IBM Plex Mono for numbers and labels, kickers uppercase with .22em tracking.
- TypeRole / `Type` resolve the face per theme. The 18 iOS `.font(.system…)` sites move to TypeRole. (Corrected D-212: all 18 are SF Symbols or emoji, not text; a
  theme's text face does not apply to them, so they stay. Found while planning, recorded in the D-212 row.)
- Cormorant SC: the OFL files, checked like the others by `scripts/licences.py` (name table, Android copies byte
  identical), `UIAppFonts`, `res/font`, NOTICE, `native/fonts/README.md`.

## 4. Text size

- A slider of **−2 to +3 steps relative to the phone's own text size**; the default (0) is the phone's size. It
  changes SKIPTI only. Stored per phone (`appearance.textSize`).
- Applied once at the root: iOS as a `dynamicTypeSize` override (the phone's size moved by the steps, clamped to the
  range iOS has), Android as `fontScale` multiplied at the root `LocalDensity`. The existing large-text layouts
  (`isAccessibilitySize`, `fontScale > 1.15`) keep switching because they read the same values. TypeRole must scale
  from the environment, not the window's trait alone.

## 5. The Appearance page

- Theme: three cards (a small preview of each theme's ground, accent and name).
- Light or dark: System / Light / Dark; with Norse picked the control is shown disabled with "Norse is always dark."
- Text size: the slider with "A" at each end and the caption "Starts at your phone's size; changes SKIPTI only."
- (The Haptics row arrives with E3.) Twins: the same order, labels and behaviour.

## 6. Records and tests

- ADR-0041 (selectable themes) supersedes ADR-0038's single palette; ADR-0036 amended (Night retired for now).
- AppearanceTests on both shells loop over every theme and effective mode for every pair (floors above), the hero
  pairs and the GroupTone pairs.
- Twin tests: theme and mode parsing (unknown values, a stored `night` -> dark), Norse forces dark and keeps the stored
  mode, the text-size step mapping (both ends, the clamp), the type face per theme.
- Look (throwaway devices only): each theme in each mode on both shells, the Appearance page, text size at both ends.

## Not in E1

E2 theme art (Aurora curtains, Norse rune drift and rune details on tabs, ring, separators and section labels, notched
corners, diamond tiles, the gold diamond +, Vardr frame marks, the drifting currency signs, the hex-bolt +, Reduce
Motion, the rune font); E3 done moments and haptics (the Haptics row); E4 the ring, settled meter, celebration and
milestones; Night (a later version).
