# web/fonts: self-hosted faces (SPEC §11.3)

> **D-148 (2026-09-22): the two IBM Plex Mono files here must be replaced
> before the web build ships.** IBM Plex's licence reserves the name "Plex",
> and these are subsets (Modified Versions under the OFL) that keep it. The
> iOS and Android apps now ship IBM's unmodified TTFs (`native/fonts/README.md`);
> the web needs IBM's own `woff2` files (`fonts/complete/woff2/` in the same
> release) or a renamed subset. Space Grotesk declares no reserved name, so
> its subset may stay.

Subset generated 2026-08-24 from canonical upstreams (google/fonts, OFL).
The Vardr_Labs_Website originals were not reachable from the generating
environment; the unicode-range below is the site's own latin range unioned
with the currency block, so coverage matches the originals plus ₹ ₽ ₺.

| File | Face | Size | Glyphs |
|---|---|---|---|
| SpaceGrotesk.woff2 | Space Grotesk, variable wght 300 to 700 | 22,504 B | 313 |
| IBMPlexMono-Regular.woff2 | IBM Plex Mono Regular | 16,368 B | 290 |
| IBMPlexMono-Medium.woff2 | IBM Plex Mono Medium | 16,580 B | 290 |

Total **54.2 KiB** (§10.4 budgets: iOS < 30 MB, Android < 25 MB per ABI).

Verified via fontTools: U+00B7 MIDDOT, U+00C1 Á, U+00E9 é, U+20B9 ₹,
U+20BD ₽ present in all three faces.

Layout features kept: `tnum,liga,locl,frac,numr,dnom` plus
`kern,ccmp,mark,mkmk` (the range includes combining marks U+0304/0308/0329,
so mark positioning must survive; kern is typographic baseline).
Stylistic sets ss01 to ss05, aalt, case, onum, sups, subs dropped.
IBM Plex Mono defines only what it ships (ccmp/dnom/frac/numr): it is
monospace and needs no tnum.

Regenerate:

    pyftsubset <upstream.ttf> --output-file=<name>.woff2 --flavor=woff2 \
      --unicodes="U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20A0-20BF,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD" \
      --layout-features="tnum,liga,locl,frac,numr,dnom,kern,ccmp,mark,mkmk" \
      --name-IDs='*'

Both families are SIL Open Font License 1.1; license texts ride inside the
name tables (`--name-IDs='*'` is deliberate) and must survive future
re-subsetting. Acceptance check after any regeneration: U+00B7, U+00C1 and
U+20B9 present in all three faces (fontTools).

Native shells: the TTF forms of these exact subsets live in
`native/fonts/` (lossless decompression, verified identical cmap). See
that README for per-platform consumption notes. Do not subset separately
for native.
