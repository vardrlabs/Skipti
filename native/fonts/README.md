# native/fonts: TTF for the iOS and Android shells (SPEC §10.4, §11.3)

| File | Face | Size | Source |
|---|---|---|---|
| SpaceGrotesk.ttf | Space Grotesk, **variable** wght 300 to 700 | 54,076 B | subset, 2026-08-24 (below) |
| IBMPlexMono-Regular.ttf | IBM Plex Mono Regular, 2.005 | 173,052 B | **IBM's file, unmodified** (D-148) |
| IBMPlexMono-Medium.ttf | IBM Plex Mono Medium, 2.005 | 174,008 B | **IBM's file, unmodified** (D-148) |
| IBMPlexSans-Regular.ttf | IBM Plex Sans Regular, 3.005 | 200,500 B | **IBM's file, unmodified** (D-215) |
| IBMPlexSans-Medium.ttf | IBM Plex Sans Medium, 3.005 | 202,460 B | **IBM's file, unmodified** (D-215) |
| IBMPlexSans-SemiBold.ttf | IBM Plex Sans SemiBold, 3.005 | 202,632 B | **IBM's file, unmodified** (D-215) |
| IBMPlexSans-Bold.ttf | IBM Plex Sans Bold, 3.005 | 200,872 B | **IBM's file, unmodified** (D-215) |
| VardrRunes-CarvedRegular.ttf | Vardr Runes Carved Regular, 1.000 | 40,460 B | **Vardr Labs' own font**, release v1.000 of `vardrlabs/fonts` (D-215) |
| CormorantSC-SemiBold.ttf | Cormorant SC SemiBold, 4.000 | 762,872 B | google/fonts `ofl/cormorantsc/`, unmodified (D-212) |
| CormorantSC-Bold.ttf | Cormorant SC Bold, 4.000 | 763,124 B | google/fonts `ofl/cormorantsc/`, unmodified (D-212) |

Total **2,774,056 B ≈ 2.65 MiB** against §10.4 budgets (iOS < 30 MB, Android
< 25 MB per ABI). Android's `app/src/main/res/font/` holds byte-identical
copies (lower-case names, as Android resources require);
`scripts/licences.py` refuses if they drift apart.

## IBM Plex Mono: IBM's own files, never trimmed (D-148)

The OFL licence of IBM Plex declares the Reserved Font Name "Plex", and a
Modified Version (a subset is one) may not use it. Until D-148 these were
subsets of the Google Fonts build that kept the name. They are now IBM's
release, unchanged:

- Source: github.com/IBM/plex, release `@ibm/plex-mono@2.5.0` (2026-06-11),
  asset `ibm-plex-mono.zip` (6,940,652 B, sha256
  `6d23f01257663d8cc49a0d64c22ced630b79e0e2a0ac08a0da86e9a38bbc481c`),
  files `fonts/complete/ttf/IBMPlexMono-{Regular,Medium}.ttf`.
- sha256: Regular `7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43`,
  Medium `98fbd727aae340b236955879dabed4d991aac9e8e90b3b2a67ce4a59221cc97c`.
  `scripts/licences.py` (a gate leg) and a test on each shell pin both.
- PostScript names are `IBMPlexMono` and `IBMPlexMono-Medm` (the Google
  Fonts build said `-Regular` / `-Medium`). iOS looks faces up by these
  names, through `TypeRole.plexRegular` / `TypeRole.plexMedium` only.
- 1,049 code points, including ₹ ₽ € − · Á é.

**Do not subset or edit these two files.** If they must ever be smaller,
the family has to be renamed first (the OFL's rule), which changes every
lookup above.

## IBM Plex Sans: IBM's own files, never trimmed (D-215)

The body face of both apps, in place of the phone's system face. The same
rule as Plex Mono applies: the OFL licence of IBM Plex declares the Reserved
Font Name "Plex", so a subset or any other edit could not keep the name.

- Source: github.com/IBM/plex, release `@ibm/plex-sans@1.1.0`, asset
  `ibm-plex-sans.zip` (sha256
  `fb365d910566e6d199cc2c15579a7dd9a267128e18431a394ed81f1970c69200`),
  files `IBMPlexSans-{Regular,Medium,SemiBold,Bold}.ttf`, "Version 3.005".
- sha256: Regular `975dcda37d80f038dcd143c22e33ca2d97a0cc5a929aace1c749153b0fe1afa5`,
  Medium `331c8639d7598b2cde62a911a71db195e30cb655cd6bdf2e324a7e984955f907`,
  SemiBold `a20caf8286023a6a7a85e40b1d2a4ae9fc3e3b1f9eda8f4c542dd4986af67bb1`,
  Bold `9e6c74a889a700d707613d24548fe4ffa6bc59559a0689d2cf9e133bdcdafb2f`.
  `scripts/licences.py` (a gate leg) and a test on each shell pin all four.
- PostScript names are `IBMPlexSans`, `IBMPlexSans-Medm`, `IBMPlexSans-SmBld`
  and `IBMPlexSans-Bold`. iOS looks faces up by these names, through
  `TypeRole.sansRegular`, `sansMedium`, `sansSemiBold` and `sansBold` only.
  Android's `res/font/ibmplexsans_{regular,medium,semibold,bold}.ttf` are
  byte-identical copies.
- Weight buckets, as the system face's were: weight 650 and up Bold, 550 and
  up SemiBold, 450 and up Medium, anything lighter Regular.

**Do not subset or edit these four files.** If they must ever be smaller, the
family has to be renamed first (the OFL's rule), which changes every lookup
above.

## Vardr Runes: Vardr Labs' own font, copied from the release (D-215)

Norse's runes (tab icons, the section mark, separators, the rising runes, the
hero watermark): the Unicode Runic block, U+16A0 to U+16F8, in straight
strokes. Carved Regular only; no other theme draws runes.

- Source: release v1.000 of the private `vardrlabs/fonts` repository, file
  `fonts/ttf/VardrRunes-CarvedRegular.ttf` ("Version 1.000"), SIL OFL 1.1 with
  the Reserved Font Name "Vardr".
- sha256 `6d61b17c4929925f5aeb58a4b5541c9524de0cb531ea0c055043b6267b04cf01`;
  `scripts/licences.py` (a gate leg) and a test on each shell pin it.
- PostScript name `VardrRunesCarved-Regular`; iOS looks it up through
  `TypeRole.runes` only. Android's `res/font/` copy is byte-identical.
- The glyphs are drawn from one stroke table in that repository; the build is
  deterministic.

**Do not subset or edit this file here.** Change the font in `vardrlabs/fonts`,
release it, and copy the release file.

## Cormorant SC: the google/fonts files, unmodified (D-212)

Norse's display and section-label face (ADR-0041). Downloaded 2026-10-14 from
`https://raw.githubusercontent.com/google/fonts/main/ofl/cormorantsc/`,
Version 4.000, SIL OFL 1.1 with no Reserved Font Name. sha256: SemiBold
`648cd1c932871e4e8e1d147b593315409e8ea82282f88e9397e2cceae83d065b`, Bold
`78f70c79b5c0a1e66641d4b4a2e2ed32e1a45774b3de94e56f8de68510219177`;
`scripts/licences.py` pins both. PostScript names `CormorantSC-SemiBold` and
`CormorantSC-Bold`; iOS looks them up through `TypeRole.cormorantSemiBold`
and `TypeRole.cormorantBold` only. Static weights (no variable axis): 600
and 700.

## Space Grotesk: a subset (no reserved name)

Generated 2026-08-24 by lossless decompression of the subsetted
`web/fonts/SpaceGrotesk.woff2` (`fontTools ttLib.woff2 decompress`):
identical tables and glyph set; see `web/fonts/README.md` for the subset
recipe. Its licence (SIL OFL 1.1) declares no Reserved Font Name, so a
subset may keep its name. It keeps its single variable axis `wght`
from 300 to 700; do not flatten it to static weights: the UI interpolates
across that range.

## Which platform consumes which format

- **iOS**: CoreText loads `.ttf` directly (Info.plist `UIAppFonts`; the
  variable axis is honoured by the text engine). It does not load `.woff2`.
- **Android**: `res/font/` accepts `.ttf` natively (API 26+), including
  variable fonts.

All three families are SIL Open Font License 1.1; each file's name table
carries its copyright and licence statement, and both apps show the full
licence under Settings → About → Licences.
