# D-215: Vardr Runes and IBM Plex Sans (both phones look identical, type included)

Status: design approved by the founder, 2026-10-15 (popup). Unit D-215, after D-214 (merged 3ee0c24). Ships with 0.5.0;
nothing is deployed before then.

## Founder's decisions (2026-10-15)

1. iPhone and Android must **look identical, type included**. No system font for visible text where the two phones'
   system faces differ (CLAUDE.md, Twins). This supersedes the E2 spec's "runes from system fonts" (2026-10-15 E2 spec,
   "Glyphs") and ADR-0041's "no font is bundled".
2. **Our own runes**: Vardr Runes, drawn in-house, the whole Unicode Runic block (U+16A0 to U+16F8, 89 code points), in
   a private repo `vardrlabs/fonts` (local `/Users/prithvi/Documents/Vardr Labs/fonts`), licensed **OFL 1.1 with the
   Reserved Font Name "Vardr"**. Styles **Carved** (SKIPTI), **Machined** and **Circuit** (other Vardr Labs projects only,
   never SKIPTI), each in Light, Regular, Medium and Bold; Regular is the default beside text.
3. SKIPTI's runes use **Carved, "with character"** (specimen v5, approved "looks good"): stems at full weight, branches
   0.70 of it, a 0.14 waist through each stroke, free ends flaring 0.50 over their last quarter, lone diagonals bowed
   3.5 percent outward, mitred corners, junctions cut at the host stroke's real edge (no spill). Runes appear **only in the
   Norse theme**; the Vardr Labs theme has no runes.
4. **IBM Plex Sans** for Aurora and Norse body text, button words and Aurora's micro labels, on both shells (Vardr Labs
   keeps Space Grotesk and Plex Mono).
5. Downloads approved: IBM's `ibm-plex-sans.zip` (release `@ibm/plex-sans@1.1.0`, 9,921,777 bytes, github.com/IBM/plex)
   and the `fonttools` 4.53.1 wheel from PyPI (hash-pinned, in a venv inside `vardrlabs/fonts` only). Both inert except
   fontTools, which runs only as the font build tool (CLAUDE.md section 13).

Specimen: https://claude.ai/artifact/DmAUPuZbTTWd55LMgfx9dn (source `.drafts/d215/runes-specimen.html`; copy in
`vardrlabs/fonts/specimen/`).

## Part A: `vardrlabs/fonts` (where the font is made)

- **One source of truth**: `source/runes.json`, one entry per code point: name, advance width, strokes as polylines in font
  units (y up, baseline 0, cap 700, em 1000), and a flag for the runes SKIPTI uses today. The specimen page and the build
  both read it, so the approved look is what ships. The 24 Elder Futhark runes and the three punctuation marks come from
  specimen v5; the other 62 are drawn in the same strokes.
- **Drawing the remaining glyphs**: every glyph is compared with the Unicode Runic code chart's form and with a reference
  rendering (Apple Symbols and Noto Sans Runic, drawn in the browser pane) before the founder sees it; variants that differ
  only by a stroke are drawn as such, not guessed. A full-block specimen (all 89, Carved Regular, beside both references)
  goes to the founder; **release waits for his approval**.
- **Build** (`tools/build.py`, fontTools 4.53.1): expands strokes into outlines with the specimen's rules (contrast, waist,
  flare, bow, mitred corners, junction ends cut at the host stroke's real half-width at the junction point, everything
  clipped to the baseline-to-cap band; dots as diamonds). Polygon clipping is pure Python (half-plane clipping), so no
  other library is needed; overlapping contours are allowed and flagged (`OVERLAP_SIMPLE`). Metrics: units per em 1000,
  cap height 700, side bearings 90, ascender 900, descender -250 (to be checked against Space Grotesk and IBM Plex Sans
  so mixed lines do not jump). Names: family "Vardr Runes Carved" (and Machined, Circuit), subfamilies Light, Regular,
  Medium, Bold; version 1.000; the OFL notice and Reserved Font Name in name records 0, 13 and 14.
- **Outputs**: 12 TTFs under `fonts/ttf/` (3 styles x 4 weights), a regenerated specimen, release tag `v1.000`.
- **Tests** (`tools/test_build.py`, run locally): every one of the 89 code points is mapped; every outline lies inside its
  advance box and the band; no degenerate contour; two builds are byte-identical; the stroke tables of the Elder Futhark
  equal specimen v5's.

## Part B: SKIPTI (where the font is used)

- **Files** (in `native/fonts/` with byte-identical lower-case copies in Android `res/font/`, as today):
  `VardrRunes-CarvedRegular.ttf` (from the `v1.000` release, sha256 pinned), and IBM Plex Sans **Regular, Medium,
  SemiBold, Bold** (IBM's unmodified files from `@ibm/plex-sans@1.1.0`, sha256 pinned; the OFL's Reserved Font Name "Plex"
  forbids modified copies, the same rule as Plex Mono's D-148). iOS: four entries per file in the pbxproj (CLAUDE.md 7.1)
  and `UIAppFonts`.
- **Body text**: the `system` face becomes **Plex Sans** everywhere it is used (iOS `TypeRole.Face.system`, Android
  `Face.SYSTEM`): body (Aurora, Norse), label / button words (Aurora, Norse), micro label (Aurora). Weights map as the
  system face's buckets do today (Regular, Medium, SemiBold, Bold). Dynamic Type keeps working (iOS `UIFontMetrics`,
  Android `sp`). Android's `MaterialTheme` gets a `Typography` whose default family is Plex Sans, so the `Text()` calls
  that bypass `Type.*` follow too. The real PostScript names are read from the files (never guessed; `UIFont(name:)`
  falls back silently).
- **Runes**: a `rune` type role on both shells (iOS `TypeRole.rune(size)`, Android `Type.rune(size)`) in Vardr Runes
  Carved. Every draw site uses it: the Norse tab icons, the title ornament ᛜ, the rising runes, the hero watermark ᚠ.
  Runes inside text (the section mark ᛟ, the separator ᛫) carry the rune face as an attribute (iOS `AttributedString`
  font) or a span (Android `SpanStyle(fontFamily = ...)`), so they never fall back to a system font. Sizes and positions
  stay as D-213 and D-214 set them.
- **Retired**: iOS `testEveryRuneDrawsFromASystemFont` (and its helper) and Android
  `the_norse_watermark_is_fehu_in_the_system_runic_font` and the debug `--rune-proof`, replaced by the tests below.
- **Tests (twins)**:
  every rune in `Runes.all` is in the bundled rune font's character map (iOS via Core Text on the bundled file; Android in
  the JVM via `java.awt.Font.createFont(...).canDisplay`), with a negative on U+16F9; every new font name resolves to the
  bundled file (not the fallback); iOS and Android copies are byte-identical (and pinned in `scripts/licences.py`); the
  Face matrix tests read Plex Sans where they read system; a source guard fails on any `.font(.system(` /
  `FontFamily.Default` used for visible text (icons drawn from SF Symbols are allowed and listed); a rune draw site
  without the rune role fails a source guard. Every new guard is sabotaged.
- **Licences**: `scripts/licences.py` gains IBM Plex Sans (unmodified-file rule, sha256) and Vardr Runes (our own,
  OFL 1.1, Reserved Font Name "Vardr", sha256 of the release file); `NOTICE` and `native/fonts/README.md` gain both; the
  in-app Licences page is regenerated (the gate's licence leg compares it).
- **Records**: ADR-0041 amended (runes and body text are bundled; the system-font sentence corrected in place); the E2
  spec's "Glyphs" paragraph corrected in place; BUILD-LOG D-215.

## Look and performance

On throwaway devices against the local emulators (never AFAF4221, EB725812 or skipti-shots): every screen family in all
five sets on both shells, looking for wrapping and truncation (Plex Sans sets wider than SF Pro and Roboto), the Norse runes
in their new face at every size (tab, ornament, section mark, separator, drift, watermark), and iOS and Android side by
side for identical type. The rune drift's cached symbols (D-214 P3) keep their cost; a Time Profiler check on Norse confirms.

## Not in D-215

Releasing Machined and Circuit publicly (they live in `vardrlabs/fonts`); redrawing the Vardr Labs theme's tab icons
(optional, later, never with runes); Latin glyphs in Vardr Runes; any change to Space Grotesk, Plex Mono or Cormorant SC.
