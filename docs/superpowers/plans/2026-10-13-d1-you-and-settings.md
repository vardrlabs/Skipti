# D1: You and Settings regrouped, and the Home greeting (D-206) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The profile glyph opens "You": a profile hero (big picture, name, @username) above five groups (Profile and account, Appearance, Security, Your data, About SKIPTI), replacing the one long Settings page; Home greets you by name and time of day. Both shells. Spec: `docs/superpowers/specs/2026-10-13-feedback-round-design.md`, "D and E", unit D1.

**Architecture:** One pure map, `SettingsMap`, says which section holds which setting and drops account-only settings without an account (GUARANTEES #5); the screens render from it, so the tested map IS the layout. One pure rule, `Greeting.line(hour:name:)`. Both twinned. iOS: `AccountHubScreen` becomes the You hub with NavigationLinks to four section pages (About opens `AboutScreen` directly). Android: four new routes `account/profile`, `account/appearance`, `account/security`, `account/data` rendered by `AccountHubScreen(section = …)`; `HomeRoutes.parent` sends Your phones and Delete account back to Security, Export and Import to Your data, Sign in to Profile and account.

**Tech Stack:** SwiftUI, Jetpack Compose, XCTest, JUnit.

## Global Constraints

- No theme work, no ring, no new data (those are E1, E4, D2). Existing card views move; their insides do not change.
- GUARANTEES #5: account-only rows are ABSENT without an account (never disabled); "Sign in" stays a SecondaryButtonStyle.
- Labels (twins, exact): "You", "Profile and account", "Appearance", "Security", "Your data", "About SKIPTI", "On this phone only", "Careful". Greetings: "Good morning", "Good afternoon", "Good evening".
- Greeting hours: 5-11 morning, 12-16 afternoon, 17-23 and 0-4 evening. Name from the vault's self profile; none or blank gives the greeting alone. Nothing leaves the phone.
- No em dashes in copy. Sentence case. `Text(verbatim:)` for any string that could contain an @ or a URL (swift autolink gate leg).
- Asserted-anchor edits; sabotage each new guard; look on the iOS 27 simulator and skipti-shots; full gate; BUILD-LOG through `buildlog_edit.py`; branch `d206-you`, merge `--no-ff`, `[skip ci]`, no attribution.

---

### Task 1: The map and the greeting, twinned, tests first

**Files:** `ios/Skipti/App/AccountHubScreen.swift` (types at the top), `android/.../AccountHubScreen.kt` (same), tests in `ios/SkiptiTests/BalanceLineTests.swift` (beside the D-205 test) and a new `android/app/src/test/java/com/vardrlabs/skipti/YouTest.kt` (JVM, no pbxproj change needed on Android).

**Produces (Swift):**
```swift
enum SettingsSection: String, CaseIterable { case profileAccount, appearance, security, data, about }
enum SettingItem: String, CaseIterable {
    case signIn, signedInAs, signOut, sync, notifications, findMe       // profileAccount
    case appearanceMode                                                // appearance
    case appLock, privacy, phones, erase, deleteAccount                // security
    case currency, export, importData                                  // data
    case aboutPage                                                     // about
    case deletionPending                                               // the hub, above the sections
}
enum SettingsMap {
    static let accountOnly: Set<SettingItem> = [.signedInAs, .signOut, .sync, .notifications, .findMe, .phones, .deleteAccount, .deletionPending]
    static func items(_ section: SettingsSection, signedIn: Bool) -> [SettingItem]
    static func hubItems(signedIn: Bool) -> [SettingItem]   // [.deletionPending] or []
}
enum Greeting { static func line(hour: Int, name: String?) -> String }
```
`items`: profileAccount = signedIn ? [signedInAs, signOut, sync, notifications, findMe] : [signIn];
appearance = [appearanceMode]; security = [appLock, privacy] + (signedIn ? [phones] : []) + [erase] + (signedIn ? [deleteAccount] : []);
data = [currency, export, importData]; about = [aboutPage].
**Kotlin twin:** `enum class SettingsSection`, `enum class SettingItem` (same names), `object SettingsMap { ACCOUNT_ONLY; fun items(section, signedIn); fun hubItems(signedIn) }`, `object Greeting { fun line(hour: Int, name: String?): String }`.

- [ ] Tests (both shells, same cases): (1) signed in: hubItems + all sections' items = every SettingItem except signIn, each exactly once; (2) no account: no item from accountOnly anywhere (negative), signIn present once; (3) section order is profileAccount, appearance, security, data, about; (4) greeting at hours 0, 4, 5, 11, 12, 16, 17, 23 with name "Prithvi" and with nil and "  " (golden strings written out by hand).
- [ ] Run, see them fail (types missing), implement, see them pass (fresh result files).

### Task 2: iOS

- [ ] `People.myUsername() async -> String?` (moved out of FindMeCard.load; FindMeCard calls it).
- [ ] `YouHero`: AvatarView size 104, name (`loadSelf()?.name`, else "You"), the handle line: signed in and a username -> `@name`; signed in, none -> nothing; no account -> "On this phone only". Tapping it opens ProfileScreen.
- [ ] `AccountHubScreen` body: hero, `DeletionPendingCard` when `hubItems` has it, one glass card of five rows (icon tile, title, one-line summary: Profile and account = sign-in method label or "No account"; Appearance = `AppearanceMode.label`; Security = "App lock and erasing"; Your data = "Export, import, currency"; About SKIPTI = version line). Title "You", inline.
- [ ] `SettingsSectionScreen(section:)` renders `SettingsMap.items` through one `switch` to the existing views (methodRow/"Signed in", sign-out card, syncCard, NotificationsCard, FindMeCard, PhonesScreen link, DeleteAccountScreen link, the Appearance picker, AppLockSettings, PrivacySettings, EraseLedgerButton card under a "Careful" header, CurrencySettings, Export, Import). The sign-out, sync and reload logic moves with it.
- [ ] Home greeting: `OverviewScreen` top, above the hero: date line (`.formatted(.dateTime.weekday(.wide).day().month(.wide))`) and `Greeting.line`; reads the name on appear and on sync revision.

### Task 3: Android

- [ ] Routes in `AppRoot.kt`: `"account/profile"`, `"account/appearance"`, `"account/security"`, `"account/data"` render `AccountHubScreen(section = …)`; `"account"` renders the hub. `HomeRoutes.parent`: the four -> "account"; "devices", "delete-account" -> "account/security"; "export", "import/account" -> "account/data"; "signin" -> "account/profile"; "profile", "about", "kit", "other-account" -> "account" (unchanged). Update the existing HomeRoutes test for the moved parents.
- [ ] `YouHero`, the hub's five rows, `SettingsSection` pages: the twin of Task 2, rendering from `SettingsMap`.
- [ ] Home greeting on `OverviewScreen`.

### Task 4: Sabotage, look, gate, record, merge

- [ ] Sabotage: S1 iOS map leaks `.phones` without an account -> only the map test red; S2 Kotlin same; S3 iOS greeting 12 -> morning -> only the greeting test red; S4 Kotlin same.
- [ ] Look (rebuild after sabotage): both shells, signed out and (iOS 26.5 founder ledger NOT used) the gate simulator: You hero, each section, Back from Your phones lands in Security (Android), the greeting. Light and dark. Screenshots `.drafts/d206/`.
- [ ] Full gate, BUILD-LOG D-206, WORKLOG, commit, merge `--no-ff`, push, no Actions run.
