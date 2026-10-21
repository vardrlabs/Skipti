# D-217: one type scale on both phones (iPhone's way), titles and type roles

Status: the founder's decisions of 2026-10-16 (the Look Book fixes canvas, board "Titles, and one type scale"; the audit
`.superpowers/sdd/lookbook-audit/consolidated.md`, "Founder picks"). Builds on D-215 (both phones draw the same faces);
D-217 makes them draw the same SIZES, the same titles and the same type roles.

## Founder's decisions (2026-10-16)

1. **Titles: Option A, iPhone's way on both phones** ("title and one type scale (iPhone way) looks better"). Tab roots:
   a large title, 34 pt, left, that collapses into the bar as an inline 17 pt centred title when the page scrolls under
   it. Pushed screens: the inline 17 pt title, centred, in a bar with the back button. Android draws iOS's bar.
2. **One type scale, iPhone's way:** where the two phones disagree on a size, iOS's value is the scale (the D-215 look's
   size table, `.drafts/d215/look/look-report.md` section 7).
3. **Type roles folded in (Q13 a):** names, kickers, section headings and tile initials take the Look Book's roles
   (audit entries L08 to L12).
4. **Section headings (Q1 a):** "People", "By group", "Who owes whom", "Recent", "Where the money went" become the theme's
   section label (ᛟ and Cormorant SC in Norse, mono .22em in Vardr Labs), like "Your ledgers".
5. **Norse section labels keep sentence case (Q14 a, ADR-0038).**

## What the scale is

Every number below is iOS's, in points; Android uses the same number in sp (dp for fixed art).

| Family (look D-n) | Size | Where |
|---|---|---|
| Large title (D1) | 34, the theme's title weight and tracking (`TitleStyle` / `Type.title`) | the four tab roots, Add expense (D3) |
| Inline title (D2) | 17, same weight and tracking | every pushed screen and sheet |
| Date line (D4) | 14, as a kicker (L09) | Balance |
| Hero amount (D5) | 44, 700 | Balance hero |
| Hero "You'll get back" (D6) | 15 | Balance hero |
| By-group "Settled" (D7) | 13 | Balance |
| Group header count (D8) | 13 | Expenses |
| Detail lines (D9 to D12, D15, D16) | 14 | group cards, "People in X", waiting line, group page detail, expense attribution, the You sub-screens (seven sites) |
| Stat tile value (D13) | 16, 600, shrinking to fit (iOS min scale 0.6; Android steps down) | group page |
| Settle intro and explainer (D14) | 15 | Settle up |
| Share input amount | 15 | Add expense member rows |
| Avatar initial | 0.38 of the avatar | every initials avatar |

Weights: the greeting 700 on both phones, Norse +.02em tracking (L05, the Look Book's `.hello b`); the You name 700, the
handle 500 (L12). Line height: Android stops fixing `lineHeight = 22.sp` on `Type.body` and its smaller copies; every
style takes the face's natural height, as iOS does (look defect 5). Android row titles that stop at a line limit end in
an ellipsis, as iOS's do (look defect 4).

## Titles and bars (the Android twin of iOS's navigation bar)

- **One bar component on Android** replaces `ScreenHeader` and every per-screen header (inventory:
  `.drafts/d217/screens.md`). Three forms, each the twin of what iOS 26 and 27 draw:
  - **Tab root:** the large title (34) left in the content; when the content scrolls it under the bar, the inline title
    (17, centred) appears in the bar. The bar's ground follows iOS's scroll-edge and standard appearances (clear at the
    top, the theme's bar ground once scrolled).
  - **Pushed screen:** a round back button (a chevron in a circle, the theme's accent) at the left, the inline title
    centred. No parent title beside the chevron (iOS 26 and 27 show none).
  - **Sheet:** Cancel and Done as pill buttons in the bar's corners, the inline title centred.
  Back on Android also answers the system back gesture (as today).
- **The chevron is the same drawing on both phones:** a Lucide `chevron-left` copied verbatim (NOTICE updated), used by
  iOS's back indicator image instead of the SF Symbol and by Android's round button; list-row chevrons use Lucide
  `chevron-right` on both (look: iOS SF chevron vs Android "›").
- **Titles go through one decision per shell:** iOS `TitleStyle` (already), Android `Type.title(large:)` with the same
  weight and tracking; no screen sets a title's size, weight or tracking itself (a source guard, both shells, like
  D-215's face guards). Android's About and Licences titles stop using `Type.display` 500 (L04).
- **Expense detail:** Android's title becomes the note (as iOS's), and the kicker the category (look defect 9).
- **The Norse ornament (L07):** under the large title only (the four tab roots), pulled up to sit about 8 pt under the
  title, its rules 42% of the width each, the rune in the gap (the Look Book's `.title + .ornament`). Pushed screens carry
  none. The You hub, a pushed screen on both phones, loses it.

## Type roles (L08 to L12)

- **`name` role** (new, both shells): the theme's BODY face at 600. Names, row titles, group names, expense titles and
  theme-card names move from `label` to `name`; `label` stays for button words. In Aurora and Norse nothing visible
  changes (label and body are both Plex Sans there); in Vardr Labs names leave Plex Mono for Space Grotesk.
- **Kickers (L09):** the Balance date line and the hero's "Across all your groups" go through the kicker (micro) role;
  the hero's kicker takes the on-hero colour (steel in Vardr Labs).
- **Section headings (L10, Q1 a):** the five headings above through `.sectionLabel()` / `SectionLabel`.
- **Tile initials (L11):** the display face 700 (Norse keeps Cormorant SC).
- **L06** (Vardr Labs kicker 12.4 against 13): left as it is (low; recorded).

## Tests and guards

- Both shells: a table test that pins every family above to its size (one table per shell, compared across shells by a
  parity test like `PaletteParityTest`), a source guard that no screen sets a title's own size/weight, that `ScreenHeader`
  is gone, and that the share-input and avatar-initial factors match; Android: no `lineHeight = 22.sp` survives in
  `Type`; the name role is used for names (a source guard on the listed call sites).
- Sabotage every new guard (D-215's runner, booted simulator).
- A look on throwaway devices (both phones, every theme, the screens in the inventory), a size-table re-measure, and the
  largest text sizes.

## Not in D-217

- C2's per-theme chrome (glass on Android, Vardr Labs' square buttons): D-217's round button and pills take today's
  surface tokens; C2 restyles them per theme.
- The Dynamic Type curve (iOS scales per text style, Android linearly): recorded, not changed.
- Onboarding (the founder, 2026-10-16: "skip the onboarding screen for now to the next 0.5.1 build, or when we make it
  public"): no screen work on the first-run surfaces; they only take what the shared pieces (the bar, the roles, the
  scale) change everywhere.
- Date formats ("Oct 15, 2026 at 6:40 PM" against "15 Oct 2026, 6:40 PM"): a content difference for its own unit.
