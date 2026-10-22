import UIKit
import XCTest
@testable import Skipti

/// D-163 (SPEC §3.7): the app lock's rules, without a device. Android's twin
/// is AppLockTest.
final class AppLockTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_000_000)

    func testTheDefaultIsTwoMinutesAndAnUnknownSettingNeverTurnsItOff() {
        XCTAssertEqual(LockTimeout.parse(nil), .twoMinutes)
        XCTAssertEqual(LockTimeout.parse("sometimes"), .twoMinutes)
        for timeout in LockTimeout.allCases {
            XCTAssertEqual(LockTimeout.parse(timeout.rawValue), timeout)
        }
        XCTAssertEqual(LockTimeout.allCases.map(\.label), [
            "Immediately", "After 1 minute", "After 2 minutes", "After 5 minutes", "After 15 minutes", "Off",
        ])
    }

    func testItLocksAtStartOnlyWhenOnAndPossible() {
        XCTAssertTrue(AppLockRules.lockedAtLaunch(timeout: .twoMinutes, available: true))
        XCTAssertFalse(AppLockRules.lockedAtLaunch(timeout: .off, available: true))
        XCTAssertFalse(AppLockRules.lockedAtLaunch(timeout: .twoMinutes, available: false), "no passcode, no lock")
    }

    func testTimeAwayDecidesAndAClockThatWentBackLocks() {
        func locks(_ timeout: LockTimeout, after seconds: TimeInterval, available: Bool = true) -> Bool {
            AppLockRules.locksOnReturn(
                timeout: timeout, available: available, backgroundedAt: t0, now: t0.addingTimeInterval(seconds)
            )
        }
        XCTAssertFalse(locks(.twoMinutes, after: 119))
        XCTAssertTrue(locks(.twoMinutes, after: 120), "at the limit")
        XCTAssertTrue(locks(.immediately, after: 0))
        XCTAssertFalse(locks(.fifteenMinutes, after: 899))
        XCTAssertTrue(locks(.fifteenMinutes, after: 900))
        XCTAssertFalse(locks(.off, after: 100_000), "off never locks")
        XCTAssertFalse(locks(.twoMinutes, after: 600, available: false))
        XCTAssertTrue(locks(.fifteenMinutes, after: -5), "setting the clock back cannot skip the lock")
        XCTAssertFalse(AppLockRules.locksOnReturn(timeout: .immediately, available: true, backgroundedAt: nil, now: t0),
                       "never left the front: nothing to count")
    }

    /// D-169: Face ID needs a usage description in the app's Info.plist; the
    /// simulator does not check, a real iPhone does. The test host is the app,
    /// so this reads the built bundle.
    func testTheAppSaysWhyItUsesFaceID() {
        let text = Bundle.main.object(forInfoDictionaryKey: "NSFaceIDUsageDescription") as? String
        XCTAssertEqual(text, "SKIPTI uses Face ID to unlock the app.")
    }

    func testTheCopySaysWhatItDoes() {
        XCTAssertEqual(AppLockCopy.confirmBody,
                       "Anyone who picks up your unlocked phone could open SKIPTI and see your ledger.")
        XCTAssertEqual(AppLockCopy.title, "SKIPTI is locked")
    }

    /// D-225 (L67 to L70): the Security page's rows, one table (Android's AppLockTest pins the same). App lock's
    /// summary is the timeout's label, or "Needs a phone lock" whatever the timeout on a phone that cannot lock; the
    /// careful card is Erase and Delete account; SettingsMap's order splits into the two cards.
    func testTheSecurityRows() {
        let labels: [(LockTimeout, String)] = [
            (.immediately, "Immediately"), (.oneMinute, "After 1 minute"), (.twoMinutes, "After 2 minutes"),
            (.fiveMinutes, "After 5 minutes"), (.fifteenMinutes, "After 15 minutes"), (.off, "Off"),
        ]
        XCTAssertEqual(labels.map(\.0), LockTimeout.allCases)
        for (timeout, label) in labels {
            XCTAssertEqual(SecurityRows.appLockLine(timeout: timeout, available: true), label)
            XCTAssertEqual(SecurityRows.appLockLine(timeout: timeout, available: false), "Needs a phone lock", "\(timeout)")
        }
        XCTAssertEqual(SecurityRows.careful, [.erase, .deleteAccount])
        let signedOut = SecurityRows.partition(SettingsMap.items(.security, signedIn: false))
        XCTAssertEqual(signedOut.safe, [.appLock, .privacy])
        XCTAssertEqual(signedOut.careful, [.erase])
        let signedIn = SecurityRows.partition(SettingsMap.items(.security, signedIn: true))
        XCTAssertEqual(signedIn.safe, [.appLock, .privacy, .phones])
        XCTAssertEqual(signedIn.careful, [.erase, .deleteAccount])
        XCTAssertEqual(SecurityRows.noPhoneLock, "Needs a phone lock")
        XCTAssertEqual(SecurityRows.eraseSummary, "Asks first. Cannot be undone.")
        XCTAssertEqual(SecurityRows.deleteAccountSummary, "A week to change your mind")
        for line in [SecurityRows.noPhoneLock, SecurityRows.eraseSummary, SecurityRows.deleteAccountSummary] {
            XCTAssertFalse(line.contains("\u{2014}"), "no em dash: \(line)") // em-dash-ok: the test asserts this dash is absent
        }
    }

    // ---- D-225 Task 4: Security, the App lock page and the lock screen (source guards) ----

    /// D-225 (Design 4; L67 to L70): Security is two list cards, the safe one (App lock, the privacy switch, Your phones)
    /// and, after "Careful" alone (its hairline gone), the danger one (Erase, Delete account) whose rows are `.danger`.
    /// No old card, no glass card of its own for any of the five; the erase row opens the Erase page and never erases.
    func testSecurityIsTwoListCards() throws {
        let hub = try s2Source("AccountHubScreen.swift")
        let page = try s2Slice(hub, from: "private var securityPage: some View {", to: "\n    }\n")
        XCTAssertEqual(s2Count(page, "ListCard {"), 2, "the safe card and the careful card")
        XCTAssertTrue(page.contains("SecurityRows.partition(SettingsMap.items(.security, signedIn: model.showsAccountControls))"))
        s2InOrder(page, ["if DeviceIntegrity.looksJailbroken {", "ModifiedPhoneWarning()", "ListCard {", "ForEach(cards.safe",
                         "if !cards.careful.isEmpty {", "sectionHeader(YouCopy.careful)", "ListCard {", "ForEach(cards.careful"])
        let rows = try s2Slice(hub, from: "private func securityRow(_ item: SettingItem) -> some View {", to: "\n    }\n")
        let appLock = try s2Slice(rows, from: "case .appLock:", to: "case .privacy:")
        for needle in ["NavigationLink { AppLockPage() }", "ListRow(title: AppLockCopy.section,",
                       "SecurityRows.appLockLine(timeout: LockTimeout.parse(lockRaw), available: lockAvailable)", "trailing: .chevron"] {
            XCTAssertTrue(appLock.contains(needle), "App lock: \(needle)")
        }
        XCTAssertTrue(try s2Slice(rows, from: "case .privacy:", to: "case .phones:").contains("PrivacyRow()"))
        let phones = try s2Slice(rows, from: "case .phones:", to: "case .erase:")
        XCTAssertTrue(phones.contains("NavigationLink { PhonesScreen() }"), phones)
        XCTAssertTrue(phones.contains("ListRow(title: PhonesCopy.title, parts: phonesLine.map { [$0] } ?? [], trailing: .chevron)"), phones)
        let erase = try s2Slice(rows, from: "case .erase:", to: "case .deleteAccount:")
        for needle in ["NavigationLink { EraseScreen() }", "title: EraseCopy.button", "parts: [SecurityRows.eraseSummary]",
                       "trailing: .chevron, tone: .danger)"] {
            XCTAssertTrue(erase.contains(needle), "Erase: \(needle)")
        }
        for banned in ["LedgerErase", "EraseLedgerButton", "Button {", ".alert("] {
            XCTAssertFalse(erase.contains(banned), "the erase row never erases: \(banned)")
        }
        let delete = try s2Slice(rows, from: "case .deleteAccount:", to: "default:")
        for needle in ["NavigationLink { DeleteAccountScreen() }", "title: PhonesCopy.deleteTitle",
                       "parts: [SecurityRows.deleteAccountSummary]", "trailing: .chevron, tone: .danger)"] {
            XCTAssertTrue(delete.contains(needle), "Delete account: \(needle)")
        }
        XCTAssertEqual(s2Count(rows, "tone: .danger"), 2, "only the careful rows are danger rows")
        for banned in ["card {", ".glassCard(", "SecondaryButtonStyle", "AppLockSettings", "PrivacySettings"] {
            XCTAssertFalse(page.contains(banned) || rows.contains(banned), "Security: \(banned)")
        }
        let header = try s2Slice(hub, from: "private func sectionHeader(_ title: String) -> some View {", to: "\n    }\n")
        XCTAssertTrue(header.contains(".sectionLabel()"), header)
        XCTAssertFalse(header.contains("Rectangle().fill(Tokens.hairline)"), "Careful's hairline is gone (L70)")
        XCTAssertFalse(header.contains("hairline"), header)
        // The old cards are gone from the tree.
        let lock = try s2Source("AppLock.swift")
        XCTAssertFalse(lock.contains("struct AppLockSettings"))
        XCTAssertFalse(lock.contains("struct PrivacySettings"))
        let privacy = try s2Slice(lock, from: "struct PrivacyRow: View {", to: "\n}\n")
        XCTAssertTrue(privacy.contains("@AppStorage(ScreenPrivacy.storageKey) private var hideWhenCaptured = false"), privacy)
        XCTAssertTrue(privacy.contains("ListRow(title: PrivacyCopy.captureToggle, parts: [PrivacyCopy.captureDetail], summaryLines: 0,"), privacy)
        XCTAssertTrue(privacy.contains("trailing: .toggle($hideWhenCaptured))"), privacy)
        let warning = try s2Slice(lock, from: "struct ModifiedPhoneWarning: View {", to: "\n}\n")
        for needle in ["PrivacyCopy.modified(\"jailbroken\")", ".foregroundStyle(Tokens.watch)", ".glassCard()"] {
            XCTAssertTrue(warning.contains(needle), "the jailbreak warning: \(needle)")
        }
        // Your phones' summary: one server read per visit, through the tested PhonesSummary, nil when signed out.
        let read = try s2Slice(try s2Source("Phones.swift"), from: "@MainActor static func summary() async -> String? {", to: "\n    }\n")
        XCTAssertTrue(read.contains("getDocuments(source: .server)"), read)
        XCTAssertTrue(read.contains("PhonesSummary.line(rows: rows("), read)
        let reload = try s2Slice(hub, from: "lockAvailable = AppLock.available\n        // D-225", to: "\n    }\n")
        XCTAssertTrue(reload.contains("phonesLine = section == .security && model.state == .active ? await Phones.summary() : nil"), reload)
    }

    /// D-225 (Design 4, Q15 a): the App lock page is a choice page of the six timeouts; Off waits on today's alert and
    /// writes nothing until "Turn off" is confirmed, then returns; every other choice writes at once (and ChoicePage
    /// returns). A phone that cannot lock shows the unavailable caption only.
    func testTheAppLockPageConfirmsOff() throws {
        let lock = try s2Source("AppLock.swift")
        let page = try s2Slice(lock, from: "struct AppLockPage: View {", to: "\n}\n")
        for needle in [
            "@AppStorage(LockTimeout.storageKey) private var stored = LockTimeout.standard.rawValue", "ChoicePage(",
            "title: AppLockCopy.section,",
            "options: available ? LockTimeout.allCases.map { ChoiceOption(value: $0, label: $0.label) } : [],",
            "caption: available ? (chosen == .off ? AppLockCopy.offDetail : AppLockCopy.on) : AppLockCopy.unavailable,",
            "stays: { $0 == .off },",
            "switch AppLockPick.of(choice, chosen: chosen) {",
            "case .write: stored = choice.rawValue", "case .ask: confirmingOff = true", "case .close: dismiss()",
            ".alert(AppLockCopy.confirmTitle, isPresented: $confirmingOff) {",
            "Button(AppLockCopy.confirmOff, role: .destructive) { stored = LockTimeout.off.rawValue; dismiss() }",
            "Button(AppLockCopy.keepOn, role: .cancel) {}", "Text(verbatim: AppLockCopy.confirmBody)",
            ".hapticWarning(when: confirmingOff)",
        ] {
            XCTAssertTrue(page.contains(needle), "AppLockPage: \(needle)")
        }
        // Off is written in one place only: the alert's destructive button.
        XCTAssertEqual(s2Count(page, "LockTimeout.off.rawValue"), 1, "Off is written only once confirmed")
        XCTAssertEqual(s2Count(page, "stored = "), 3, "the declaration, a timeout at once, Off from the alert")
        for banned in ["Picker(", ".pickerStyle(", "FilterChip"] {
            XCTAssertFalse(page.contains(banned), "AppLockPage: \(banned)")
        }
        // Fix wave (review M6): Off while Off asks nothing and returns; Off from a timeout asks; a timeout writes.
        XCTAssertEqual(AppLockPick.of(.off, chosen: .off), .close)
        for timeout in LockTimeout.allCases where timeout != .off {
            XCTAssertEqual(AppLockPick.of(.off, chosen: timeout), .ask, timeout.rawValue)
            XCTAssertEqual(AppLockPick.of(timeout, chosen: .off), .write, timeout.rawValue)
            XCTAssertEqual(AppLockPick.of(timeout, chosen: timeout), .write, timeout.rawValue)
        }
        XCTAssertEqual(AppLockCopy.section, "App lock")
        XCTAssertEqual(AppLockCopy.confirmTitle, "Turn off app lock?")
        XCTAssertEqual(AppLockCopy.confirmOff, "Turn off")
        XCTAssertEqual(AppLockCopy.keepOn, "Keep it on")
    }

    /// D-225 (Design 13; art.py 44): the lock screen draws Lucide's lock at `lockGlyph` in textDim above the title at
    /// `TypeScale.lockTitle`, on the art ground, as Android's; the privacy cover stays the plain page colour on purpose
    /// (the app switcher's snapshot).
    func testTheLockScreenTwins() throws {
        let lock = try s2Source("AppLock.swift")
        let screen = try s2Slice(lock, from: "struct AppLockScreen: View {", to: "\n}\n")
        for needle in ["LucideIcon(glyph: .lock, size: ThemeArt.m(\"lockGlyph\"))", ".foregroundStyle(Tokens.textDim)",
                       ".font(TypeRole.display(TypeScale.lockTitle))", ".auroraBackground()"] {
            XCTAssertTrue(screen.contains(needle), "the lock screen: \(needle)")
        }
        s2InOrder(screen, ["LucideIcon(glyph: .lock", "Text(verbatim: AppLockCopy.title)"])
        for banned in ["systemName: \"lock\"", "Image(systemName:", ".background(Tokens.bg)", "display(28)", ".font(.system("] {
            XCTAssertFalse(screen.contains(banned), "the lock screen: \(banned)")
        }
        XCTAssertTrue(try s2Slice(lock, from: "struct PrivacyCover: View {", to: "\n}\n").contains(".background(Tokens.bg)"))
        XCTAssertEqual(ThemeArt.m("lockGlyph"), 40)
        XCTAssertEqual(TypeScale.lockTitle, 28)
    }
}

/// D-164: the rest of SPEC §3.7.
final class PrivacyTests: XCTestCase {
    func testTheLedgerIsCoveredOnlyWhenAskedAndOnlyWhileCaptured() {
        XCTAssertTrue(ScreenPrivacy.covers(enabled: true, captured: true))
        XCTAssertFalse(ScreenPrivacy.covers(enabled: true, captured: false))
        XCTAssertFalse(ScreenPrivacy.covers(enabled: false, captured: true), "off by default: screenshots stay possible")
    }

    func testAJailbreakIsReportedFromAnyKnownSign() {
        XCTAssertFalse(DeviceIntegrity.looksJailbroken { _ in false })
        XCTAssertTrue(DeviceIntegrity.looksJailbroken { $0 == "/var/jb" })
        XCTAssertFalse(DeviceIntegrity.looksJailbroken, "a simulator never reports")
    }

    func testACopiedSecretStaysOnThisPhoneAndExpires() throws {
        let now = Date()
        Clipboard.copySecret("https://skipti.vardrlabs.com/join#x", now: now)
        XCTAssertEqual(UIPasteboard.general.string, "https://skipti.vardrlabs.com/join#x")
        XCTAssertEqual(Clipboard.lifetime, 120)
        // Already expired: the system drops it.
        Clipboard.copySecret("gone", now: now.addingTimeInterval(-3600))
        XCTAssertNil(UIPasteboard.general.string)
    }
}

// D-225 Task 4: source guards over the app's files (no new test file; each file keeps its own helpers).
fileprivate func s2Source(_ file: String) throws -> String {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Skipti/App/\(file)")
    return try String(contentsOf: url, encoding: .utf8)
}

/// The text between the first `from` and the next `to` after it; a missing anchor fails the test, never crashes.
fileprivate func s2Slice(_ text: String, from: String, to: String) throws -> String {
    let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
    return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
}

fileprivate func s2Count(_ text: String, _ needle: String) -> Int { text.components(separatedBy: needle).count - 1 }

/// Each needle found, in this order.
fileprivate func s2InOrder(_ text: String, _ needles: [String], file: StaticString = #filePath, line: UInt = #line) {
    var from = text.startIndex
    for needle in needles {
        guard let at = text.range(of: needle, range: from..<text.endIndex) else {
            XCTFail("not found in order: \(needle)", file: file, line: line)
            return
        }
        from = at.upperBound
    }
}
