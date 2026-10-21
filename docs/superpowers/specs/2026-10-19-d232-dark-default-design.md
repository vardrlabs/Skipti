# D-232: Aurora dark is the default for a new install

Date: 2026-10-19 (session 8, Opus). The founder's call (opener item 3, 2026-10-18): "Aurora DARK becomes the default
for a new install, on both phones, before the final release". Everything below that is not his call is the
controller's, recorded as decisions 73 to 76 in `docs/DECISIONS-TAKEN.md`. To be committed as
`docs/superpowers/specs/2026-10-19-d232-dark-default-design.md` on branch `d232-dark-default`, with ADR-0042.

## 1. Today

`AppearanceMode` (iOS `Appearance.swift`, Android `Appearance.kt`) is System, Light or Dark, stored outside the vault
(`appearance.mode` / the `skipti-appearance` prefs). `parse(nil)` and any unknown value read as System; a stored `night`
(before D-212) reads as Dark. The default theme is Aurora; Norse forces dark at resolve time. SPEC 11.1 and ADR-0038
say "light first" (design light, derive dark; light is a requirement, not an afterthought); ADR-0041 keeps it.

## 2. What changes

- **A new install starts in Dark** (decision 73): with nothing stored, `parse` reads Dark. Light and System stay as
  choices on Appearance, unchanged, and a stored choice always wins. The theme default stays Aurora, so a new install
  opens in Aurora dark.
- **An existing install keeps what it shows today** (decision 74): an install that has run before and never chose a
  mode follows the system now and must not flip to Dark on upgrade. One migration at launch, before the mode is first
  read: if no mode is stored AND the vault file already exists (the install has run before), store `system` once.
  A brand-new install has no vault at that moment, so nothing is stored and it reads Dark. The vault check is the same
  one the launch path already makes (no new read of the vault's contents; only whether the file exists).
- **Unknown stored values read as the default, Dark** (decision 75); `night` still reads as Dark.
- **ADR-0042 "Dark by default"** (decision 76) supersedes SPEC 11.1's and ADR-0038's "light first" ONLY as a reading of
  the default mode. It keeps, word for word, what light first was for: light is a requirement and is designed and
  measured in every set (Aurora light, Vardr Labs light), every pair measured by the AppearanceTests on both shells;
  dark is no longer "derived later" in any sense that matters, since D-212's five token sets are each measured. SPEC
  11.1 gets a one-paragraph "As changed (D-232, ADR-0042)" note at its head, the way D-174's note sits there.

## 3. Checks (the founder named them)

- **Every contrast floor in dark**: the AppearanceTests already measure every pair in all five sets on both shells;
  D-232 adds nothing to the token values. The record quotes the runs. If a first-run screen uses a pair the tests do
  not measure, it is added.
- **The first-run screens in dark**: a fresh install on both phones (throwaway devices, `--emulators`), every screen a
  new person sees before their first expense: the welcome / first launch, the empty Overview, Add expense, the empty
  Expenses list, People, You and Appearance (showing Dark selected), in Aurora dark; one Light check that choosing Light
  still works; one upgrade check (install the pre-D-232 build, launch, install D-232 over it: still System).

## 4. Tests (twins)

- `parse`: nil -> Dark; `light`, `dark`, `system` -> themselves; `night` -> Dark; `"bogus"` -> Dark.
- The migration (pure decision `AppearanceMode.migrate(stored: String?, vaultExists: Bool) -> String?`: returns
  `"system"` to store when stored is nil and the vault exists, else nil): four vectors; and its wiring at launch on
  each shell (a hosted / JVM test that a launch with an existing vault and no key stores `system`, and with no vault
  stores nothing).
- A source guard or test that the migration runs before the first `AppearanceMode.parse` at launch (order matters).
- Sabotage: parse's nil branch, the unknown branch, the migration's vault condition, its nil condition, the launch
  order; about 10 rows per lane.

## 5. Decisions (to DECISIONS-TAKEN, after 72)

- **73** A new install starts in Aurora dark: `parse(nil)` reads Dark; Light and System stay as choices.
- **74** An existing install keeps following the system: one launch migration stores `system` where no mode is stored
  and the vault file exists. Risk if wrong: a tester who never chose sees no change (intended); a person who wiped
  the vault but kept preferences reads System, not Dark (harmless).
- **75** An unknown stored value reads as the default (Dark), not System.
- **76** ADR-0042 supersedes "light first" only as the default mode; light stays a designed, measured requirement.

## 6. Changes after the plan read the code (accepted by the controller, 2026-10-19)

1. Decision 74 gains a settled mark (`appearance.modeSettled` on iOS, `modeSettled` on Android): the migration runs only
   while the mark is absent and always sets it, so a D-232 install launched a second time (it now has a vault) keeps
   reading Dark, and Android's activity re-creation cannot re-run it. `migrate(stored:vaultExists:)` keeps its shape.
2. iOS's three mode readers default to `system` today (Appearance.swift, AccountHubScreen.swift twice), so they become
   optional and read through `parse`, or `parse(nil)` is never reached.
3. ADR-0036 decision 1 also says "System (the default)": ADR-0042 amends it too.
4. The migration is the first statement of `SkiptiApp.init` (iOS) and the first after `super.onCreate` (Android), ahead
   of anything that may open the vault (Android's Debug-only `--emulator-as` opens it on an IO thread).
5. **Decision 77 (new):** the launch frame follows the phone's own appearance, because nothing can read the app's stored
   choice before the app runs. iOS's empty launch screen already does; Android's window theme changes from
   `Theme.Material.Light.NoActionBar` to the DayNight equivalent. Residue: a light phone opening a dark install (every
   new install on a light phone) shows a light launch screen for the whole cold start before the app draws; recorded.
   Section 8 corrects "one frame" and adds decision 78, which narrows it on Android 12 and later.

## 7. As planned and built (`.drafts/d232/plan.md`; T1 to T4)

- Section 6's changes were planned as written. The settled mark keeps `migrate(stored:vaultExists:)`'s signature; the
  once-ness is the wiring's (`settleDefault` on both shells), tested and sabotaged on both.
- iOS's three `@AppStorage(AppearanceMode.storageKey)` readers became `String?`, so `parse` decides the default; a
  guard keeps every reader optional.
- iOS writes the mode, then the mark. Android writes both in one editor with `commit()`, not `apply()` (a fix from the
  T3 review): `apply()` is asynchronous, so a process killed in that window would lose the mark and the next launch,
  vault now present and nothing stored, would store `system` and turn a new install from Dark to System. One small
  synchronous write once per install; a JVM test pins `commit()` and a sabotage row puts `apply()` back.
- Decision 77 as built: Android's window theme is the app's own `Theme.Skipti`, a `values` / `values-night` pair
  (`Theme.Material.Light.NoActionBar` and `Theme.Material.NoActionBar`), because the platform's DayNight theme starts
  at API 29 and minSdk is 26. iOS's empty `UILaunchScreen` already follows the phone. Residue (as corrected in section
  8): a light launch screen for the cold start on a light phone opening a dark install, a dark one on a dark phone
  opening a Light install; Android's floating text-selection toolbar followed the phone until decision 78.
- ADR-0042 records decisions 73 to 77; ADR-0036's decision 1 and SPEC 11.1 carry D-232 notes.

## 8. After the final review (final wave, 2026-10-19)

- **The launch residue is the whole cold start, not one frame** (review M1). iOS's launch screen and Android's
  starting window (the system splash from Android 12) stay up until the app draws: typically hundreds of
  milliseconds, 1.2 to 1.7 seconds on a Debug simulator in D-232's look (its F1). ADR-0042 now says so.
- **Decision 78 (the controller's, from the look's F2):** in the dark app on a light phone, Android's text-selection
  toolbar was light while iOS's edit menu was dark. On Android 12 and later (API 31) the app tells the system its own
  night mode with `UiModeManager.setApplicationNightMode`, through one pure mapping, `Appearance.nightModeFor(mode,
  theme)`: Dark and Norse (`forcesDark`) are `MODE_NIGHT_YES`, Light `MODE_NIGHT_NO`, System `MODE_NIGHT_AUTO` (no
  override, so `isSystemInDarkTheme()` reports the phone again and the app's System still follows it). It tells at
  launch right after `settleDefault`, and from `setMode` and `setTheme`, and only when `Appearance.nightModeToTell`
  says the value differs from the one this install last told. The SDK has no public `getApplicationNightMode`, so the
  record is the app's own (`nightModeTold` in the appearance preferences), written after the call. Telling a new value
  makes the activity again once; the activity made again finds the record equal and tells nothing, so no launch
  loops. Result: on Android 12 and later the window theme and the selection toolbar follow the app's choice as soon as
  it is told, and the splash from the next launch (the second launch of a new install, and the launch after any
  change); a first launch, and Android below 12, follow the phone. The keyboard is the keyboard app's own on both
  phones and follows the phone, not SKIPTI. iOS has no equivalent for its launch screen.
- **Android 13's `SplashScreen.setSplashScreenTheme`**, which the review suggested for later, is superseded by decision
  78: one call covers the splash and the window theme from Android 12, not 13.
- **ADR wording** (review M2): ADR-0042's Status also states that it retires ADR-0011's "derive dark from it" reading,
  since D-212's sets are each their own measured column; ADR-0011 and ADR-0038 carry a one-line "Amended by ADR-0042"
  note under their Status.
- **The iOS order guard** (review M4): Swift runs `SkiptiApp`'s stored-property initialisers before `init`'s body, so
  `testTheDefaultIsSettledFirstAtLaunch` also refuses any initialiser of `SkiptiApp`, above `init` or between it and
  `body`, that names `LedgerStore`, `vault`, `loadGate` or `Sync.`; a comment at the top of `init` says why settle
  stays first.
- **Tests:** three JVM tests on Android (the mapping, every mode in every theme; the differs decision; a source pin
  that the one call sits inside `Build.VERSION.SDK_INT >= 31` and behind the differs check, with its three call sites)
  and the extended iOS guard. Sabotage in throwaway worktrees, one row per new guard; the record has the matrix.
