# ADR-0023: Design tokens from Vardr Labs — light-first lift, rupee-complete fonts, Liquid Glass as system chrome

Status: Accepted

## Context

SPEC §11.2's hex values were proposals ("I did not scrape your site CSS.
Substitute the real tokens before building"). The founder has now supplied
the authoritative source: `Vardr_Labs_Website/public/css/style.css`. This
ADR substitutes those tokens, verifies the accessibility floor they must
meet (§11.7) rather than assuming it, fixes a font defect that would have
broken the product's signature element, and settles where Liquid Glass may
appear.

## Decision

**1. Structural tokens adopted from Vardr Labs, dark and light.**

| Token | Dark | Light |
|---|---|---|
| `--bg` | `#0b0f13` | `#F4F7F9` (lifted — see 3) |
| `--surface` | `#0e141a` | `#eef1f4` |
| `--surface-raised` | `#121a21` | `#dfe5e9` |
| `--hairline` | `#1d2731` | `#c3cdd4` |
| `--text` | `#e9eef3` | `#182430` |
| `--text-dim` | `#8fa0ae` | `#46555f` |
| `--text-faint` | `#6e7e8b` | `#5b6872` |

**2. Money colours are not brand colours.** `--owed`, `--owing`, `--neutral`
stay exactly as SPEC §11.2 wrote them (`#B4442A`/`#E8734F`,
`#2E6B52`/`#5FB88C`, `#5A6169`/`#8A939B`). The warm-rust/cool-teal pair was
chosen because red/green fails for roughly 1 in 12 men; brand fit does not
override colourblind safety. Two observations recorded: `--owed` dark
(`#E8734F`) is essentially the brand's ember, so it lands in the family
regardless; `--owing` is the one genuinely new colour, because the brand
palette contains no green at all.

**3. State colours map onto the brand almost exactly**, using §11.2's
values: WATCH ≈ brand gold (`#d9bc6e` ↔ `#E8C170`), ALERT ≈ ember
(`#e89055` ↔ `#E8734F`), SECURE ≈ steel/cyan family, COLD ≈ faint.

**4. Light mode is lifted, and the lift is verified rather than argued.**
The site is dark-first with a paper-gray light background (`#e8ecef`,
"deliberately not glaring"). SPEC §11.1 requires the reverse for SKIPTI:
light first, bright enough outdoors. Light `--bg` lifts to `#F4F7F9` with
the site's dark text tokens unchanged. Raising background luminance against
unchanged dark text can only improve ratios — and measurement confirms it:
`--text` 13.25 → **14.63**, `--text-dim` 6.49 → **7.16**, `--text-faint`
4.82 → **5.32** on the lifted background.

Full §11.7 verification, computed per pair in both themes (WCAG ratios):

- **Dark:** every body-text pair passes 4.5:1 — text/bg 16.47,
  text/surface 15.87, dim/bg 7.15, dim/surface 6.89, owed/surface 6.17,
  owing/surface 7.69, all state labels ≥ 6.16. One constraint:
  `--text-faint` on surfaces measures **4.43** (passes large/UI, misses
  body). Rule: faint is used on `--bg` or at large sizes only; anything
  small on a card renders in `--text-dim`.
- **Light:** text/bg 14.63, text/surface 13.89, text/raised 12.39, dim/bg
  7.16, dim/surface 6.80, faint 5.32/5.05, owed 5.14/4.87, owing
  5.84/5.54, ALERT 5.14 — all pass body.
- **State colours in light mode need usage rules.** As large text or UI
  indicators (3:1): WATCH 3.16/3.00 ✓, SECURE 4.11/3.90 ✓. As small text
  they miss 4.5, so derived text-safe variants are defined for light mode:
  `--watch-text #8a6d1f` (4.55) and `--secure-text #3d6e8c` (5.13), used
  only if small coloured state text is ever required.
- **COLD fails in light mode outright**: 2.90 on bg, 2.75 on surface —
  under even the 3:1 indicator floor. COLD means "offline, local only," the
  least urgent state; its light-mode rendering uses `--text-dim` for both
  dot and label (7.16 ✓), which matches the brand's own use of dim for this
  register.
- **Hairlines are decorative** (1.22 dark / 1.42 light against surface) and
  carry no UI-boundary duty; interactive component boundaries meet 3:1 via
  their own borders and focus rings.

**5. Fonts: Space Grotesk (variable 300–700) for display/body, IBM Plex
Mono for the numeric role; both self-hosted woff2** (SIL OFL), satisfying
§11.3's no-Google-Fonts rule.

**Font blocker found and fixed.** The site's three woff2 subsets carry
229–230 glyphs each and are missing U+20B9 RUPEE SIGN (and ₽, ₺); only € £
¥ survived subsetting. Every §11.6 example is ₹ and D-07 registers ~170
currencies, so as-subsetted the rupee falls back to a system font and breaks
the tabular alignment of §11.4's balance line — the signature element.
Resolution executed in this commit: re-subset from the canonical upstreams
(google/fonts, OFL) with pyftsubset; fontTools verification confirms ₹, ₽,
and ₺ present in all three files. Candidate subsets land at `web/fonts/`.
Final numbers should be confirmed against the site's own originals when
shell work begins; the acceptance check is the required codepoints present,
verified by fontTools.

> **Corrected 2026-08-24, same day.** The first regeneration used a narrow
> ad-hoc unicode set that gained the currency block but *lost 117 glyphs* —
> including U+00B7 MIDDOT (the separator in every §11.3 micro-label) and the
> accented-Latin range (U+00C0–U+00FF), which would have sent names like José
> or Müller to a system font. Re-subsetted with the site's original
> unicode-range unioned with the currency block, verified for U+00B7 + U+00C1
> + U+20B9 in all three faces. Stylistic features nobody uses (ss01–ss05,
> aalt, case, onum, sups, subs) were dropped — keeping `tnum, liga, locl,
> frac, numr, dnom` plus `kern, ccmp, mark, mkmk` for combining marks — and
> the final sizes are **54.2 KiB total**: SpaceGrotesk.woff2 22,504 B
> (313 glyphs), IBMPlexMono-Regular.woff2 16,368 B, IBMPlexMono-Medium.woff2
> 16,580 B, against §10.4's app-size budgets (iOS < 30 MB, Android < 25 MB
> per ABI).

**6. Tabular figures are set explicitly.** Neither font stack declares
`font-variant-numeric: tabular-nums` anywhere in the site CSS. §11.3
requires tabular figures for "every amount, count, date, percentage, and
currency code. No exceptions." Verified in this commit that Space Grotesk
carries the `tnum` GSUB feature, so the mandate is implementable there:
`font-variant-numeric: tabular-nums` is set explicitly on every numeric
surface regardless of which face renders it. IBM Plex Mono defines no `tnum`
and needs none — it is monospace by construction — but the CSS property is
still applied wherever a number renders, so the rule does not depend on
which face a given string lands in.

**7. Liquid Glass is system chrome only** (founder decision). Nav bars, tab
bars, sheets, toolbars — what standard SwiftUI components give for free.
Content surfaces and the balance line stay opaque. Reason: §11.7 requires
contrast "verified per token pair actually used," and a refractive backdrop
makes one side of every pair variable, so verification becomes impossible
on glass carrying text. Android gets no equivalent: Material 3 Expressive's
vocabulary is motion and shape, not refraction, and blur costs the §10.4
cold-start budget (< 1.2 s on a 2022 mid-range device). Cross-platform
identity comes from §11.4's balance line and tabular figures, not from
matching materials.

**8. Amended 2026-08-24 (D-51): the brand's `--accent` was missed.** The
Vardr site CSS defines `--accent: var(--steel)` — `#9db0bf` dark /
`#4c6172` light — and this ADR ported the structural tokens, money colours
and state colours but skipped the accent. Consequence observed in the
shell: interactive controls (toggles, buttons) fell back to stock iOS
green, a colour absent from the entire SKIPTI palette. Adopted now as
`--accent`, verified per §11.7 BEFORE adoption: **3:1 UI floor passes on
every surface in both themes** (dark 8.60 bg / 8.29 surface / 7.86 raised;
light 5.99 / 5.68 / 5.07 — every pair also clears the 4.5 body floor).
Applied to interactive controls (tints, toggles); content text keeps
`--text`/`--text-dim`.

## Consequences

- Phase 7 is unblocked on tokens: shells can be built against these values
  immediately; no design decision blocks UI code.
- The light theme is now measurably brighter than the site's own while
  remaining in its family, and every colour pair that carries information
  has a measured ratio with a named rule where a value misses a floor.
- Rupee rendering is correct out of the box; any future re-subsetting must
  keep U+20A0–20BF and re-run the fontTools check (regen command recorded
  in `web/fonts/README.md`).
- Tabular alignment of the balance line depends on two things now pinned:
  complete currency glyphs and explicit `tnum`.
- Liquid Glass scope is bounded to system chrome; any future attempt to put
  text over transclusive material must reopen this ADR with a contrast
  verification story, which currently cannot exist.
