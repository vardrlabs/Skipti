# Brand assets

## The app icon since D-216: the cut S

SKIPTI's icon is an **S cut in two**, one per theme: Aurora (the primary icon on both phones), Norse and Vardr Labs.
On iOS each has Any, Dark and Tinted images; on Android each is an adaptive icon (background, foreground) and the
three share one monochrome layer for Android 13+ themed icons. The person picks the icon on Appearance ("Match theme"
by default), and it changes when they leave that page.

### Files

| path | what |
|---|---|
| `skipti-icon-assets/ios-appicon-1024.png`, `-dark-`, `-tinted-` | Aurora's iOS Any (RGB, opaque), Dark and Tinted (RGBA); the tinted image serves all three icons |
| `skipti-icon-assets/ios-appicon-norse-1024.png`, `-norse-dark-` | Norse's iOS Any and Dark |
| `skipti-icon-assets/ios-appicon-vardr-1024.png`, `-vardr-dark-` | Vardr Labs' iOS Any and Dark |
| `skipti-icon-assets/android-background-432.png`, `android-foreground-432.png` | Aurora's Android layers (432 px is xxxhdpi's 108 dp) |
| `skipti-icon-assets/android-norse-*-432.png`, `android-vardr-*-432.png` | Norse's and Vardr Labs' Android layers |
| `skipti-icon-assets/android-monochrome-432.png` | the shared monochrome layer (white only) |
| `skipti-icon-assets/play-store-512.png` | the Play listing's icon |
| `skipti-icon-assets/ic_stat_skipti.xml` | Android's notification glyph (a VectorDrawable, two white paths) |
| `make_icon_resources.sh` | writes every icon resource both shells ship from the files above |

Unsuffixed names are Aurora's. The masters are exports; the drawing sources (the S's analytic geometry, the SVG and
Affinity build scripts, the status-bar glyph's generator) are kept outside the repository, in the founder's
`.drafts/d216/` (local only, never committed), and `skipti-icon-affinity/` (the Affinity documents) is git-ignored.
The BUILD-LOG D-216 row names them.

### Regenerating

```
brand/make_icon_resources.sh
```

It copies the iOS images into `ios/Skipti/App/Assets.xcassets/AppIcon{,-Norse,-Vardr}.appiconset/` (with their
`Contents.json`), resizes each Android layer with macOS's `sips` to mdpi 108, hdpi 162, xhdpi 216 and xxhdpi 324 px
(xxxhdpi is the 432 px master), writes the picker's 144 px previews once and copies them byte for byte to iOS's
`IconPreview-<Theme>.imageset/` and Android's `drawable-nodpi/icon_preview_<theme>.png`, and copies the glyph to
`res/drawable/ic_stat_skipti.xml`. It is idempotent and refuses if a master is missing. Never hand-edit the outputs:
change a master, run the script, and the JVM and XCTest suites (AppIconTest, AppIconTests) check the result.

The icon set names (`AppIcon`, `AppIcon-Norse`, `AppIcon-Vardr`) and Android's launcher aliases (`IconAurora`,
`IconNorse`, `IconVardr`) are permanent identifiers (CLAUDE.md section 10).

## Retired: the D-115 particle mark

Everything below describes the mark the app shipped from D-115 until D-216. Its SVG masters and `make_masters.py`
stay here as a record; no resource is made from them any more.

The mark is **one whole thing, cut clean, into unequal parts**: the disc is
the total, the cut is the *skipti*. Above the cut the particles fall thick and
cyan; below they thin out to steel. The division is carried by **density, not
hue**, which is the language of the rotor cloud on vardrlabs.com.

## The distribution is the design

The first version scattered points with **uniform random sampling** and it
looked like static: random clumps, random voids, blotchy at every size. The
founder caught it immediately, and it is the single thing most likely to be
undone by accident.

Points sit on a **Vogel (sunflower) lattice**: successive points at the golden
angle, radius as √i. That fills a disc with no clumping and no gaps, because
that is exactly what phyllotaxis solves. Keeping every third lattice point
below the cut produces the spiral arms, a real property of the golden angle,
not an artifact, and the same family of structure as the studio's rotor.

**Do not swap the lattice for random.** That is the mistake this file exists
to stop happening twice.

## These are generated, not drawn

`make_masters.py` emits every SVG here from seeded RNG, so the same particles
fall the same way on every run:

```
python3 brand/make_masters.py
```

Regenerate rather than hand-edit. A hand-tweaked master silently stops
matching the script, and then nobody knows which is canonical.

## Palette

Read from `vardrlabs.com`'s own `:root`, not matched by eye:

| token | value | use |
|---|---|---|
| `--bg` | `#0b0f13` | ground |
| `--cyan` | `#5ad0f2` | the dense side |
| `--steel` | `#9db0bf` | the sparse side |

These are the STUDIO's colours. SPEC §11.2's ember/teal (`#E8734F` owed,
`#5FB88C` owing) are the LEDGER's and stay inside the balance line. An
earlier draft of this mark used them and read as a different company.

## Files

| file | what |
|---|---|
| `skipti-icon-ios-1024.svg` | full-bleed iOS master; Xcode generates the ladder |
| `skipti-adaptive-foreground.svg` | Android foreground, inside the 66/108 dp safe zone |
| `skipti-adaptive-background.svg` | Android background, flat ground |
| `skipti-mark.svg` | transparent ground, for in-app use and the lockup |
| `skipti-mark-mono.svg` | monochrome, for iOS tinted mode and the Play listing |

## The one thing to know before changing it

**It resolves, it does not dissolve.** Past ~48 px the particles stop being
separable and merge into tone; the faint base beneath them is what keeps the
silhouette and the cut readable down to 29 px. The first attempt had no base
and vanished into a black square at icon sizes. If you thin that base, check
29 px before committing: that is where this design fails first.
