# ADR-0042: Dark by default

Status: Accepted (founder-decided 2026-10-18, D-232; supersedes "light first" in ADR-0011, ADR-0023, ADR-0038 and
SPEC section 11.1 only as a reading of the default mode; records that since D-212 dark is no longer derived from light,
which retires ADR-0011's "derive dark from it" as a description of how the sets are made; amends ADR-0036's decision 1)

## Context

Until D-232 a new install followed the phone (ADR-0036, decision 1: "System (the default)"), and the design rule was
"light first": build the light theme first and derive dark from it (ADR-0011), because "Dark-only is a posture; light
mode is a requirement" (SPEC section 11.1). ADR-0023 and ADR-0038 kept that principle; ADR-0041 kept it while adding
five token sets (Aurora light, Aurora dark, Norse, Vardr Labs light, Vardr Labs dark), each its own measured column.

On 2026-10-18 the founder decided: "Aurora DARK becomes the default for a new install, on both phones, before the
final release".

## Decision

1. **A new install opens in Aurora dark** (decision 73). With no mode stored, `AppearanceMode.parse` reads Dark on
   both shells. The theme default stays Aurora (ADR-0041). Light and System stay as choices on Appearance, unchanged,
   and a stored choice always wins.
2. **An install that has run before keeps what it shows** (decision 74). One launch migration, the first thing the
   app does and once per install: if no mode is stored and the vault file exists (the install has run before and
   followed the phone), it stores `system`; in every case it stores a settled mark (iOS `appearance.modeSettled`,
   Android `modeSettled` in the appearance preferences), so it never runs again and a new install's later launches
   stay Dark. It asks only whether the vault file exists; nothing reads the vault. The mark never lands without the
   mode: iOS writes it after the mode, and Android writes both in one synchronous `commit()` rather than the
   asynchronous `apply()`, so a process killed at launch cannot leave an Android install unmarked (its next launch
   would find the vault and store `system`, turning a new install from Dark to System).
3. **An unknown stored value reads as Dark** (decision 75), the default; a stored `night` (before D-212) reads as Dark.
4. **What "light first" was for stands** (decision 76). Light mode is a requirement: a finance app is opened outdoors,
   at a restaurant table, one-handed, in a hurry. Light is designed and measured in every set that has one (Aurora
   light, Vardr Labs light), and every pair in every set is measured by the AppearanceTests on both shells; a light
   pair under its floor fails the gate as a dark one does. "Dark derived from light" no longer describes how the sets
   are made: since D-212 each set is its own column from `.drafts/d212/palette.py`, measured on its own. What changes
   is only which mode a person sees before they choose.
5. **The launch screen follows the phone's own appearance** (decision 77) until the app has told the system otherwise
   (decision 78), because nothing can read the app's stored choice before the app runs. iOS's launch screen is empty
   (`UILaunchScreen` with no keys) and already does. Android's window theme, which draws the starting window, is the
   app's own `Theme.Skipti`: Material Light without an action bar in `values`, Material (dark) without one in
   `values-night`. It is a pair rather than the platform's DayNight
   theme because that theme starts at API 29 and SKIPTI's minSdk is 26. No activity sets a theme of its own over it.
6. **Android 12 and later is told the app's own night mode** (decision 78). On API 31 and later the app calls
   `UiModeManager.setApplicationNightMode`: Dark and any theme that is always dark (Norse) are `MODE_NIGHT_YES`, Light
   is `MODE_NIGHT_NO`, and System is `MODE_NIGHT_AUTO`, which clears the app's override so the phone's own setting
   reaches the app again. It tells at launch right after the migration, and whenever the mode or the theme changes,
   and only when the value differs from the one this install last told (the platform has no public getter, so the app
   keeps its own record). The system keeps the value for the app, so the window theme, the floating text-selection
   toolbar and the splash of every later launch follow the app's choice. Telling a new value makes the activity again
   once; the activity made again finds the record equal and tells nothing, so a launch never loops. Below API 31
   nothing changes. iOS has no equivalent: its launch screen always follows the phone, and its edit menu already
   follows the app. Android 13's `SplashScreen.setSplashScreenTheme` (a splash theme kept for the next launch) is
   superseded by this decision: one call covers the splash and the window theme from Android 12.

## Consequences

- A new person sees Aurora dark first, whatever the phone's own setting; System is one tap away on Appearance.
- Everyone who installed before D-232 sees no change: the migration stores the System they were already seeing.
- A person whose preferences are cleared while the vault stays (no current path does this) reads System, not Dark.
- The launch screen can disagree with the app for the whole cold start, not one frame (decision 77's residue):
  typically hundreds of milliseconds, and 1.2 to 1.7 seconds on a Debug simulator in D-232's look. On iOS it always
  follows the phone: a light phone opening a dark install, which is every new install on a light phone, shows a light
  launch screen before the app draws, and a dark phone opening an install set to Light shows a dark one. On Android 12
  and later the splash follows the app's choice from the launch after the app has told the system (decision 78: the
  second launch of a new install, and the launch after any change); a first launch, and every launch below Android
  12, follows the phone. A first launch cannot follow the app without the app's own code running first; this is
  recorded, not hidden.
- On Android the floating text-selection toolbar, drawn by the window theme, follows the app's choice on Android 12
  and later (decision 78) and the phone below 12. The keyboard is the keyboard app's own on both phones and follows
  the phone (or the keyboard's own setting), not SKIPTI. Every screen the app draws itself follows the chosen mode.
- On Android 12 and later, a change of mode or theme that turns the app from light to dark, or back, makes the
  activity again once, as rotating the phone does. The app lock treats that as staying in front, and a launch's own
  lock decision is never skipped (D-232 confirmation C1).
- Night stays retired (ADR-0036 as amended by ADR-0041).
