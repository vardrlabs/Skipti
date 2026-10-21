# ADR-0036: Appearance — System, Light, Dark and Night

Status: Accepted (founder-decided 2026-09-21; extends ADR-0023's token set)

**Amended 2026-10-14 (D-212, ADR-0041, the founder):** Night is retired for now. The choices are System,
Light and Dark; a stored `night` reads as Dark (and the picker shows Dark). The Night column, `NightTrait` and the
Night tests are gone; the themes of ADR-0041 carry their own dark sets. Night returns as its own decision in a later
version.

**Amended 2026-10-19 (D-232, ADR-0042, the founder):** decision 1's default is Dark for a new install. System
stays a choice, and an install that ran before D-232 keeps System: a one-time launch migration stores it where no
mode is stored and the vault file exists.

## Context

ADR-0023 fixed SKIPTI's colours as a light set and a dark set, chosen by the
phone's own setting. On 2026-09-21 the founder asked for "the toggle to switch
modes (light, dark, night)" in the account section. Asked what Night should
mean, he chose **pure black**: a true-black, lower-glare version of Dark for
OLED screens and reading in bed. The alternatives were a warm, low-blue theme
(every colour needs a warm variant, and the money pair must stay
distinguishable) and "Night = Automatic" (no new colours).

## Decision

1. **Four choices, one setting: System (the default), Light, Dark, Night.**
   System follows the phone. Night is always dark as well, so anything that
   asks only "dark?" still gets a dark answer.
2. **Night is a third column of ADR-0023's tokens**, not a separate theme.
   Values, and the WCAG 2 contrast each pair measures:

   | Token | Dark | Night |
   |---|---|---|
   | bg | #0b0f13 | #000000 |
   | surface | #0e141a | #080b0e |
   | surface-raised | #121a21 | #0f1418 |
   | hairline | #1d2731 | #1a2128 |
   | text | #e9eef3 | #d5dce2 |
   | text-dim | #8fa0ae | #8797a4 |
   | text-faint | #6e7e8b | #74838f |
   | owed / owing / neutral / accent | unchanged | unchanged |

   Night's text is softer than Dark's (relative luminance 0.71 against 0.85)
   for less glare on black, and still measures 13.4:1 or better on every
   surface. Faint text rises from Dark's #6e7e8b to #74838f, so it clears
   4.5:1 on every surface: 5.39 on bg, 5.06 on surface and 4.75 on raised,
   where Dark's own faint text measures 4.20 on raised. Every money colour
   clears 5.9:1 (neutral on raised is the lowest) and the accent 8.2:1.
3. **Floors, enforced by tests on both shells** (AppearanceTests /
   AppearanceTest recompute them from the shipped values): body text 7:1;
   dim text, faint text and the money colours 4.5:1 on bg, surface and
   raised; the accent 3:1 as a UI boundary; a primary button's label (the
   page colour on the accent) 4.5:1.
4. **Where the setting lives: the device's app preferences** (iOS
   UserDefaults, Android app-private SharedPreferences), not the vault. It
   is not financial data, and the first screens (onboarding, the vault
   recovery screen) draw before the vault opens.
5. **How it reaches the screens.** iOS: a custom `NightTrait` on the trait
   system; the choice is set as window trait overrides (so system chrome,
   sheets and alerts follow) and bridged into SwiftUI's environment,
   because a change to a UIKit-only trait does not make SwiftUI re-resolve
   colours. Android: the chosen mode overrides the night bit of
   `LocalConfiguration`, so every screen's `isSystemInDarkTheme()` follows
   it, and Night is Compose snapshot state read inside `Tok`.

## Consequences

- A new token needs three values, and a Night pair under its floor fails a
  test on both platforms.
- Android's system status bar keeps the platform's own colours until its
  insets are handled (D-138 (e)); Night does not paint it.
- A warm "evening" theme or a scheduled switch would each be a new decision.
