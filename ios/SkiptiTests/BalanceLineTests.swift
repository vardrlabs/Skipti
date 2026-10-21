import SwiftUI
import XCTest
@testable import Skipti

/// §11.4 + §11.7 proofs over the real FFI path: fixture expenses through
/// ffiBalancesCompute, money strings only from core, spoken forms pinned by
/// goldens, Dynamic Type scaling that actually scales.
final class BalanceLineTests: XCTestCase {
    private let ada = String(repeating: "a", count: 31) + "1"
    private let rahul = String(repeating: "a", count: 31) + "2"

    private func fixture() -> [ExpenseInput] {
        [ExpenseInput(
            payer: ada,
            paidMinor: 428_000,
            currencyCode: "INR",
            shares: [
                IdAmount(id: rahul, amountMinor: 210_000),
                IdAmount(id: String(repeating: "a", count: 31) + "3", amountMinor: 148_000),
                IdAmount(id: String(repeating: "a", count: 31) + "4", amountMinor: 70_000),
            ],
            group: nil,
            conversion: nil
        )]
    }

    func testBalancesComputeReturnsTheMagnitudeSortedDecomposition() throws {
        let out = try ffiBalancesCompute(expenses: fixture(), settlements: [], redistributions: [])
        XCTAssertEqual(out.count, 1)
        let inr = out[0]
        XCTAssertEqual(inr.currencyCode, "INR")
        // Sorted by magnitude descending: the shape §11.4 renders.
        XCTAssertEqual(inr.entries.map(\.minorUnits), [428_000, -210_000, -148_000, -70_000])
        XCTAssertEqual(inr.entries.first?.id, ada, "the viewer (largest positive) leads")
        // Transfers simplify to ≤ n−1 and preserve every net position.
        let total: Int64 = inr.transfers.reduce(0) { $0 + $1.amountMinor }
        XCTAssertEqual(total, 428_000, "all debt routes toward the single creditor")
    }

    func testMoneyStringsComeFromCoreNotFromSwift() throws {
        // D-09 golden through the boundary.
        XCTAssertEqual(
            try ffiMoneyFormat(minorUnits: 428_000, currencyCode: "INR", locale: .enIn),
            "₹4,280.00"
        )
        // §11.4 decomposition goldens.
        XCTAssertEqual(
            try ffiMoneyFormatDecomposition(minorUnits: 210_000, currencyCode: "INR", locale: .enIn),
            "+2,100"
        )
        XCTAssertEqual(
            try ffiMoneyFormatDecomposition(minorUnits: -70_000, currencyCode: "INR", locale: .enIn),
            "-700"
        )
        // §11.7 spoken golden, verbatim from the spec.
        XCTAssertEqual(
            try ffiMoneySpoken(minorUnits: -124_000, currencyCode: "INR", locale: .enIn),
            "minus one thousand two hundred and forty rupees"
        )
        XCTAssertEqual(
            try ffiMoneySpoken(minorUnits: 210_000, currencyCode: "INR", locale: .enIn),
            "two thousand one hundred rupees"
        )
    }

    func testVaultLoopRendersWhatWasPersisted() throws {
        // The closed loop: write the ledger to the skrin vault, CLOSE,
        // REOPEN, read back, and compute: the line must be identical to
        // the fixture-computed one, proving vault → core → UI without
        // literals.
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("balance-ledger-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)

        let seeded = try store.loadOrSeed(seed: [
            .init(
                payer: ada,
                paidMinor: 428_000,
                currencyCode: "INR",
                shares: [
                    .init(id: rahul, amountMinor: 210_000),
                    .init(id: String(repeating: "a", count: 31) + "3", amountMinor: 148_000),
                    .init(id: String(repeating: "a", count: 31) + "4", amountMinor: 70_000),
                ]
            )
        ])
        XCTAssertEqual(seeded.count, 1, "seeded on first run")

        let persisted = try store.loadOrSeed(seed: []) // reopen: no re-seed
        XCTAssertEqual(persisted, seeded, "the ledger came back from persisted bytes")

        // Settlements persist through the same loop, and the recomputed
        // line reflects them (D-49 coverage: a settlement-shaped bug in the
        // pairwise matrix would corrupt exactly this).
        _ = try store.appendSettlement(
            .init(
                from: rahul,
                to: ada,
                amountMinor: 210_000,
                currencyCode: "INR"
            )
        )
        let persistedSettlements = try store.loadSettlements()
        XCTAssertEqual(persistedSettlements.count, 1, "the settlement came back too")

        let line = try ffiBalanceLine(
            expenses: persisted.map(\.asInput),
            settlements: persistedSettlements.map(\.asInput),
            redistributions: [],
            removed: [], viewer: ada,
            group: nil
        ).first { $0.currencyCode == "INR" }

        XCTAssertEqual(line?.viewerNetMinor, 218_000, "receiving a repayment reduces what you are still owed")
        // §11.4's worked example, minus the settled pair, which VANISHES
        // rather than reappearing inverted (the D-49 coverage pin).
        let rendered = try line?.entries.map {
            try ffiMoneyFormatDecomposition(
                minorUnits: $0.minorUnits,
                currencyCode: "INR",
                locale: .enIn
            )
        } ?? []
        XCTAssertFalse(rendered.contains("+2,100"), "the settled debt is gone")
        XCTAssertEqual(rendered, ["+1,480", "+700"], "the untouched pairs are untouched")
    }

    func testMoneyParseMinorNeverTouchesAFloat() throws {
        // D-50's entry path: decimal STRINGS, integer minor units out.
        XCTAssertEqual(try ffiMoneyParseMinor(digits: "12.40", currencyCode: "INR"), 1_240)
        XCTAssertEqual(try ffiMoneyParseMinor(digits: "0.1", currencyCode: "INR"), 10)
        XCTAssertEqual(try ffiMoneyParseMinor(digits: "1.005", currencyCode: "KWD"), 1_005,
                       "three-decimal currency takes three decimals")
        XCTAssertEqual(try ffiMoneyParseMinor(digits: "1005", currencyCode: "JPY"), 1_005,
                       "zero-decimal currency takes none")
        // Silent rounding is the enemy:
        XCTAssertThrowsError(
            try ffiMoneyParseMinor(digits: "1.005", currencyCode: "INR"),
            "2dp currency must refuse a 3rd decimal, not round"
        )
        XCTAssertThrowsError(
            try ffiMoneyParseMinor(digits: "10.05", currencyCode: "JPY")
        )
        XCTAssertThrowsError(
            try ffiMoneyParseMinor(digits: "1,234", currencyCode: "INR"),
            "grouping separators never enter the ledger"
        )
    }

    func testConversionAtEntryCollapsesCurrenciesIntoTheSettlementLine() throws {
        // §5.2: members enter in ANY currency; conversion happens at ENTRY
        // with the rate frozen into the record. The group line is in the
        // settlement currency: a USD expense does NOT create a USD line,
        // it lands converted in the INR line.
        let lines = try ffiBalanceLine(
            expenses: Fixtures.dto(groupID: Fixtures.ada).map(\.asInput),
            settlements: [],
            redistributions: [],
            removed: [], viewer: Fixtures.ada,
            group: Fixtures.ada
        )
        XCTAssertEqual(lines.map(\.currencyCode), ["INR"], "converted at entry: one line")
        let inr = lines[0]
        // ada's net: +428,000 − 30,000 − 200,000 − 416,250 (converted $50) = −218,250.
        XCTAssertEqual(inr.viewerNetMinor, -218_250)
        let rendered = try inr.entries.map {
            try ffiMoneyFormatDecomposition(
                minorUnits: $0.minorUnits, currencyCode: "INR", locale: .enIn
            )
        }
        XCTAssertEqual(
            rendered,
            ["-2,062.50", "-520", "+400"],
            "rahul is now a CREDITOR: the converted $50 flipped him"
        )
    }

    func testAddExpensePersistsThroughTheVaultLoop() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("add-expense-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)

        // The add-expense loop: spec -> core -> shares -> persisted.
        let computed = try ffiSplitCompute(
            totalMinor: 2_500,
            currencyCode: "USD",
            members: [Fixtures.ada, Fixtures.rahul, Fixtures.priya],
            spec: .equal
        )
        XCTAssertEqual(computed.map(\.minorUnits), [834, 833, 833], "largest-remainder via core")
        // Decision 208: a shared expense belongs to a group the vault holds, its people the group's.
        let trip = String(repeating: "0", count: 31) + "7"
        try HeldLedgers.group(store, id: trip, members: [Fixtures.ada, Fixtures.rahul, Fixtures.priya], currency: "USD")
        _ = try store.appendExpense(.init(
            payer: Fixtures.ada,
            paidMinor: 2_500,
            currencyCode: "USD",
            group: trip,
            shares: computed.map { .init(id: $0.id, amountMinor: $0.minorUnits) }
        ))

        let persisted = try store.loadExpenses()
        XCTAssertEqual(persisted.count, 1, "the expense came back from persisted bytes")

        let lines = try ffiBalanceLine(
            expenses: persisted.map(\.asInput),
            settlements: [],
            redistributions: [],
            removed: [], viewer: Fixtures.ada,
            group: trip
        )
        XCTAssertEqual(lines.count, 1)
        XCTAssertEqual(lines[0].currencyCode, "USD")
        XCTAssertEqual(lines[0].viewerNetMinor, 1_666, "payer +2,500 less her own 834 share")
    }

    func testSettleFlowRecordsAndUpdatesThePlan() throws {
        // §5.8: settling records a settlement (straumr record path) and the
        // recomputed plan reflects it: the debt row LEAVES the line.
        let priya = String(repeating: "a", count: 31) + "3"
        let groupID = Fixtures.ada // the fixture group id used in dto()
        let expenses = Fixtures.dto(groupID: groupID).map(\.asInput)

        let before = try ffiBalanceLine(
            expenses: expenses,
            settlements: [],
            redistributions: [],
            removed: [], viewer: Fixtures.ada,
            group: groupID
        )
        .first { $0.currencyCode == "INR" }
        XCTAssertEqual(before?.viewerNetMinor, -218_250, "the converted $50 flips ada to owing")
        XCTAssertEqual(
            try before?.entries.map {
                try ffiMoneyFormatDecomposition(
                    minorUnits: $0.minorUnits, currencyCode: "INR", locale: .enIn
                )
            },
            ["-2,062.50", "-520", "+400"],
            "magnitude order: rahul (creditor after conversion), priya, amit"
        )

        // Settle the Priya debt: ada pays her the net ₹520.
        let settlements = [
            LedgerStore.SettlementDTO(
                from: Fixtures.ada, to: priya,
                amountMinor: 52_000, currencyCode: "INR", group: groupID
            )
        ]
        let after = try ffiBalanceLine(
            expenses: expenses,
            settlements: settlements.map(\.asInput),
            redistributions: [],
            removed: [], viewer: Fixtures.ada,
            group: groupID
        ).first { $0.currencyCode == "INR" }
        XCTAssertEqual(after?.viewerNetMinor, -166_250, "the settlement reduced ada's owing")
        XCTAssertFalse(
            (after?.entries ?? []).contains { $0.id == priya },
            "the settled debt leaves the plan"
        )

        // The plan (≤ n−1 transfers) CONSERVES every position: applying
        // the transfers zeroes all nets, in the ABSOLUTE frame, with the
        // viewer included and viewer-frame entries sign-flipped.
        XCTAssertLessThanOrEqual(after?.transfers.count ?? 99, 3)
        var positions: [String: Int64] = [
            Fixtures.ada: after?.viewerNetMinor ?? 0
        ]
        for entry in after?.entries ?? [] {
            positions[entry.id, default: 0] -= entry.minorUnits
        }
        for t in after?.transfers ?? [] {
            positions[t.from, default: 0] += t.amountMinor
            positions[t.to, default: 0] -= t.amountMinor
        }
        XCTAssertTrue(
            positions.values.allSatisfy { $0 == 0 },
            "the plan must conserve: \(positions)"
        )
    }

    func testSettlementPayloadRejectsWhatALedgerMust() {
        XCTAssertThrowsError(
            try ffiSettlementPayload(
                from: Fixtures.ada, to: Fixtures.rahul,
                amountMinor: 0, currencyCode: "INR", timestampMs: 1, group: nil
            )
        )
        XCTAssertThrowsError(
            try ffiSettlementPayload(
                from: Fixtures.ada, to: Fixtures.ada,
                amountMinor: 5, currencyCode: "INR", timestampMs: 1, group: nil
            )
        )
    }

    func testCrossShellVaultEnvelopeGoldenDecodesIdentically() throws {
        // D-109's cross-shell pin: ONE committed byte sequence, decoded in
        // BOTH shells. The vault envelope is a cross-shell wire format: if
        // Swift and Kotlin ever disagree, data written on one phone is
        // unreadable on the other, and no per-shell test catches it. The
        // canonical file is android/app/src/test/resources/ledger-envelope-
        // golden.json; the Kotlin decoder is LedgerStore.decodeEnvelope.
        // The second record is the OLDER shape (no note/timestampMs/category
        // keys) so the tolerance claim is tested, not asserted.
        //
        // The mutation this catches is a field being made REQUIRED (dropping
        // the `?`), not any decoder-configuration change: both shells'
        // decoders tolerate an absent key for an optional property by
        // construction, and that shared behaviour is the point. Same note
        // sits on the Kotlin half in LedgerStoreTest.
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent(
                "../../android/app/src/test/resources/ledger-envelope-golden.json"
            )
        let data = try Data(contentsOf: url)
        let decoded = try JSONDecoder().decode([LedgerStore.ExpenseDTO].self, from: data)

        XCTAssertEqual(decoded.count, 2)
        let full = decoded[0]
        XCTAssertEqual(full.payer, Fixtures.rahul)
        XCTAssertEqual(full.paidMinor, 5_000)
        XCTAssertEqual(full.currencyCode, "USD")
        XCTAssertEqual(full.group, Fixtures.ada)
        XCTAssertEqual(full.conversion?.convertedMinor, 416_250)
        XCTAssertEqual(full.conversion?.convertedCurrency, "INR")
        XCTAssertEqual(full.conversion?.rateNum, 8_325)
        XCTAssertEqual(full.conversion?.rateDen, 100)
        XCTAssertEqual(full.conversion?.rateTimestampMs, 1_756_281_600_000)
        XCTAssertEqual(full.note, "Rahul paid the USD hotel")
        XCTAssertEqual(full.timestampMs, 1_756_368_000_000)
        XCTAssertEqual(full.category, "food.groceries")
        XCTAssertEqual(full.shares.map(\.amountMinor), [416_250])
        XCTAssertEqual(full.shares.first?.id, Fixtures.ada)

        let old = decoded[1]
        XCTAssertEqual(old.payer, Fixtures.amit)
        XCTAssertEqual(old.paidMinor, 30_000)
        XCTAssertEqual(old.currencyCode, "INR")
        XCTAssertEqual(old.group, Fixtures.ada)
        XCTAssertNil(old.note, "the older shape must decode with the new fields ABSENT")
        XCTAssertNil(old.timestampMs)
        XCTAssertNil(old.category)
        XCTAssertNil(old.conversion)
        XCTAssertEqual(old.shares.map(\.amountMinor), [30_000])
    }

    func testDynamicTypeScalingActuallyScales() {
        // The 200% requirement is structural: the same role at the largest
        // accessibility size must render ~2x the DEFAULT (.large) size, with
        // BOTH ends pinned to explicit traits, never the process env.
        let base = UIFont(name: "SpaceGrotesk-Light", size: 40) ?? .systemFont(ofSize: 40)
        let normal = UIFontMetrics(forTextStyle: .largeTitle).scaledFont(
            for: base,
            compatibleWith: UITraitCollection(preferredContentSizeCategory: .large)
        )
        let ax5 = UIFontMetrics(forTextStyle: .largeTitle).scaledFont(
            for: base,
            compatibleWith: UITraitCollection(
                preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge
            )
        )
        // Apple's published curve: largeTitle reaches 1.7x at AX5 (40→68),
        // body reaches well past 2x. Assert the platform's real numbers.
        XCTAssertGreaterThan(ax5.pointSize, normal.pointSize * 1.6,
                             "AX5 must scale largeTitle ~1.7x: \(normal.pointSize) → \(ax5.pointSize)")
        // And the mono numeric role scales with it (§11.3: every number).
        let monoNormal = UIFontMetrics(forTextStyle: .body).scaledFont(
            for: UIFont(name: TypeRole.plexRegular, size: 15)!,
            compatibleWith: UITraitCollection(preferredContentSizeCategory: .large)
        )
        let monoAX5 = UIFontMetrics(forTextStyle: .body).scaledFont(
            for: UIFont(name: TypeRole.plexRegular, size: 15)!,
            compatibleWith: UITraitCollection(
                preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge
            )
        )
        XCTAssertGreaterThan(monoAX5.pointSize, monoNormal.pointSize * 1.6)
        let bodyNormal = UIFontMetrics(forTextStyle: .body).scaledFont(
            for: .systemFont(ofSize: 16),
            compatibleWith: UITraitCollection(preferredContentSizeCategory: .large)
        )
        let bodyAX5 = UIFontMetrics(forTextStyle: .body).scaledFont(
            for: .systemFont(ofSize: 16),
            compatibleWith: UITraitCollection(
                preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge
            )
        )
        XCTAssertGreaterThan(bodyAX5.pointSize, bodyNormal.pointSize * 2.0,
                             "body text must clear the 200% floor: \(bodyNormal.pointSize) → \(bodyAX5.pointSize)")
    }

    /// D-126's account hub, twin of the Kotlin proof: the pure part.
    ///
    /// The property that carries a GUARANTEE rather than a preference:
    /// with NO account there are no account controls at all. #5 forbids
    /// an account being "created, implied, or nudged for", and a disabled
    /// sync switch or a greyed-out Devices row IS an implication: it
    /// shows someone what they are missing. Absent, not disabled.
    func testTheAccountHubShowsNoAccountControlsWithoutAnAccount() {
        let none = accountHubModel(signedIn: false, syncOn: true)
        XCTAssertEqual(none.state, .noAccount)
        XCTAssertFalse(none.showsAccountControls, "no account must mean NO controls, not disabled ones")
        let noneOff = accountHubModel(signedIn: false, syncOn: false)
        XCTAssertEqual(noneOff.state, .noAccount)
        XCTAssertFalse(noneOff.showsAccountControls)

        // D-192: a signed-in phone has the account (no "waiting for
        // approval": Firebase hands every phone of the account its data).
        let active = accountHubModel(signedIn: true, syncOn: true)
        XCTAssertEqual(active.state, .active)
        XCTAssertTrue(active.showsAccountControls)

        // Sync state rides through untouched: the hub renders it, the
        // switch owns it (D-125).
        XCTAssertTrue(accountHubModel(signedIn: true, syncOn: true).syncOn)
        XCTAssertFalse(accountHubModel(signedIn: true, syncOn: false).syncOn)
    }

    // MARK: - D-128: the iOS product shell

    /// The shell is a TWIN of Android's `HomeTab`: these tabs, this order,
    /// these labels, the + between Groups and Expenses (D-179, the founder's
    /// canvas). The literals are Android's, copied from `TabBar.kt`: if iOS
    /// drifts (a tab added, a rename, a reorder) this goes red. The account
    /// surface is deliberately absent: it is a toolbar glyph, never a tab
    /// (the founder's call, D-126).
    /// D-205: a tap on the tab you are in goes to its first screen, and a
    /// tap there scrolls to the top (UIKit's own tab bar rule); Android's twin
    /// is HomeTabsTest.a_tap_on_the_shown_tab_goes_to_its_root_then_its_top.
    func testATapOnTheShownTabGoesToItsRootThenItsTop() {
        XCTAssertEqual(TabTap.decide(selected: .groups, tapped: .groups, covered: [.groups]), .popToRoot(.groups))
        XCTAssertEqual(TabTap.decide(selected: .groups, tapped: .groups, covered: []), .scrollToTop(.groups))
        XCTAssertEqual(TabTap.decide(selected: .groups, tapped: .groups, covered: [.balance]), .scrollToTop(.groups))
        // Another tab only switches, whatever is covered.
        XCTAssertEqual(TabTap.decide(selected: .groups, tapped: .balance, covered: [.balance, .groups]), .switchTo(.balance))
        for tab in HomeTab.allCases where tab != .settle {
            XCTAssertEqual(TabTap.decide(selected: .settle, tapped: tab, covered: Set(HomeTab.allCases)), .switchTo(tab))
        }
    }

    /// D-234 (L6, the D-229 look's F1): when the shell rebuilds its stacks (a join or an import bumps `generation`), SwiftUI
    /// calls the NEW root's `onAppear` before the OLD root's `onDisappear`; the old root's late call must not hide the
    /// bar. Each root instance carries a token; only the live root's disappearance covers its tab. A real cover (a page
    /// pushed over the live root) still hides the bar, and its return shows it again.
    @MainActor
    func testAReplacedRootsLateDisappearanceKeepsTheBar() throws {
        let chrome = ShellChrome()
        let old = UUID(), new = UUID()
        chrome.rootAppeared(.settle, token: old)
        XCTAssertTrue(chrome.showsBar(on: .settle))
        chrome.rootAppeared(.settle, token: new)
        chrome.rootDisappeared(.settle, token: old)
        XCTAssertTrue(chrome.showsBar(on: .settle), "the replaced root's late call keeps the bar")
        chrome.rootDisappeared(.settle, token: new)
        XCTAssertFalse(chrome.showsBar(on: .settle), "a page pushed over the live root hides it")
        chrome.rootAppeared(.settle, token: new)
        XCTAssertTrue(chrome.showsBar(on: .settle), "and its return shows it")
        // Every tab, the D-229 order: the three roots that were hidden by opacity, never covered, go last.
        let fresh = ShellChrome()
        let before = Dictionary(uniqueKeysWithValues: HomeTab.allCases.map { ($0, UUID()) })
        for tab in HomeTab.allCases { fresh.rootAppeared(tab, token: before[tab]!) }
        for tab in HomeTab.allCases { fresh.rootAppeared(tab, token: UUID()) }
        for tab in HomeTab.allCases where tab != .balance { fresh.rootDisappeared(tab, token: before[tab]!) }
        for tab in HomeTab.allCases { XCTAssertTrue(fresh.showsBar(on: tab), "\(tab)") }
        // Source: the roots go through the token; nothing else writes `covered`.
        let bar = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/SkiptiTabBar.swift"), encoding: .utf8)
        XCTAssertTrue(bar.contains(".onAppear { chrome.rootAppeared(tab, token: token) }\n            .onDisappear { chrome.rootDisappeared(tab, token: token) }"))
        XCTAssertTrue(bar.contains("@State private var token = UUID()"))
        XCTAssertEqual(bar.components(separatedBy: "covered.insert(").count, 2, "one writer inserts")
        XCTAssertEqual(bar.components(separatedBy: "covered.remove(").count, 2, "one writer removes")
    }

    /// D-206: every setting has exactly one home on You, and the account's
    /// own settings are absent without an account (GUARANTEES #5). Android's
    /// twin is YouTest.
    func testEverySettingHasOneHomeAndAccountRowsAreAbsentWithoutAnAccount() {
        XCTAssertEqual(SettingsSection.allCases, [.profileAccount, .appearance, .security, .data, .about])
        let signedIn = SettingsMap.hubItems(signedIn: true)
            + SettingsSection.allCases.flatMap { SettingsMap.items($0, signedIn: true) }
        XCTAssertEqual(signedIn.count, Set(signedIn).count, "a setting in two places")
        XCTAssertEqual(Set(signedIn), Set(SettingItem.allCases).subtracting([.signIn]))
        let none = SettingsMap.hubItems(signedIn: false)
            + SettingsSection.allCases.flatMap { SettingsMap.items($0, signedIn: false) }
        XCTAssertTrue(Set(none).isDisjoint(with: SettingsMap.accountOnly), "an account row without an account")
        XCTAssertEqual(none.filter { $0 == .signIn }.count, 1)
        XCTAssertEqual(SettingsMap.items(.security, signedIn: false), [.appLock, .privacy, .erase])
    }

    /// D-212: Appearance is theme, light or dark, then text size (D-222: You's row line is `YouRows.parts`, tested in
    /// testYouRowsSummaries).
    func testAppearanceIsThemeThenModeThenTextSize() {
        XCTAssertEqual(SettingsMap.items(.appearance, signedIn: false), [.theme, .appearanceMode, .textSize])
        XCTAssertEqual(SettingsMap.items(.appearance, signedIn: true), [.theme, .appearanceMode, .textSize])
        XCTAssertEqual(AppearanceCopy.cards, ["Theme", "Light or dark", "Text size"])
        XCTAssertEqual(AppearanceCopy.norseAlwaysDark, "Norse is always dark.")
        XCTAssertEqual(AppearanceCopy.textSizeCaption, "Starts at your phone's size; changes SKIPTI only.")
    }

    /// D-206: the greeting on Home, by the phone's clock; the twin of
    /// YouTest.the_greeting_follows_the_clock_and_the_name.
    func testTheGreetingFollowsTheClockAndTheName() {
        let cases: [(Int, String)] = [
            (0, "Good evening"), (4, "Good evening"), (5, "Good morning"), (11, "Good morning"),
            (12, "Good afternoon"), (16, "Good afternoon"), (17, "Good evening"), (23, "Good evening"),
        ]
        for (hour, line) in cases {
            XCTAssertEqual(Greeting.line(hour: hour, name: "Prithvi"), line + ", Prithvi")
            XCTAssertEqual(Greeting.line(hour: hour, name: nil), line)
            XCTAssertEqual(Greeting.line(hour: hour, name: "  "), line)
        }
    }

    /// D-207: recents, people from your other groups, for adding to a group;
    /// the twin of PeopleTest.recents_are_your_other_groups_people_once_each.
    func testRecentsAreYourOtherGroupsPeopleOnceEach() {
        func g(_ id: String, _ name: String, _ members: [(String, String)]) -> NamedGroupWire {
            NamedGroupWire(group: GroupWire(id: id, name: name, settlementCurrency: "INR", members: members.map { $0.0 }),
                           selfId: members[0].0, members: members.map { MemberWire(id: $0.0, name: $0.1) })
        }
        func c(_ member: String, _ you: String) -> ClaimOut { ClaimOut(memberId: member, youId: you, devices: []) }
        let groups = [
            // Your own name differs between groups: the self rule, not the
            // name rule, keeps "Prithvi" out (D-207's first sabotage found
            // the test could not tell when every self was "Me").
            g("goa", "Goa", [("gy", "Prithvi"), ("gr", "Rahul"), ("gm", "Meera")]),
            g("flat", "Flat", [("fy", "Me"), ("fr", "Rahul"), ("fs", "Sam")]),
            g("paris", "Paris", [("py", "Me"), ("pa", "Ana"), ("pr", " rahul ")]),
            g("trip", "Trip", [("ty", "Me")]),
        ]
        let claims = ["goa": [c("gr", "p1")], "flat": [c("fr", "p1"), c("fs", "p2")]]
        let all = Recents.people(groups: groups, claims: claims, groupId: "trip")
        XCTAssertEqual(all.map(\.name), ["Rahul", "Sam", "Ana", "Meera"])
        XCTAssertEqual(all[0].participantId, "p1")
        XCTAssertEqual(all[0].shared, 3)
        XCTAssertEqual(all[0].groups, ["Goa", "Flat"])
        XCTAssertEqual(all[0].viaGroupId, "goa")
        XCTAssertEqual(all[1].participantId, "p2")
        XCTAssertNil(all[2].participantId)
        XCTAssertFalse(all.contains { $0.name == "Me" || $0.name == "Prithvi" }, "never yourself")
        // Adding to Goa: Rahul (p1, and by name) and Meera are already there.
        XCTAssertEqual(Recents.people(groups: groups, claims: claims, groupId: "goa").map(\.name), ["Sam", "Ana"])
        XCTAssertEqual(Recents.people(groups: groups, claims: claims, groupId: "trip", limit: 2).map(\.name), ["Rahul", "Sam"])
    }

    func testHomeShellHasTheFourTabsAndTheAddButtonInAndroidsOrder() {
        // D-180: Expenses and Groups swapped (the founder, 2026-09-29).
        XCTAssertEqual(HomeTab.allCases.map(\.label), ["Balance", "Expenses", "Groups", "Settle up"])
        XCTAssertEqual(HomeTab.addLabel, "Add an expense")
        XCTAssertEqual(
            HomeTab.allCases.map(\.glyph),
            [.wallet, .receipt, .users, .circleCheck],
            "each tab draws the same Lucide glyph as Android's TabIcons"
        )
    }

    /// D-179: Lucide's upstream bytes (github.com/lucide-icons/lucide main,
    /// icons/*.svg, read 2026-09-29), written here independently of the app
    /// and in Android's TabIconsTest too: a redraw or a typo goes red on both.
    func testTheNewGlyphsAreLucidesOwnPaths() {
        XCTAssertEqual(LucideGlyph.wallet.pathData, [
            "M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1",
            "M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4",
        ])
        XCTAssertEqual(LucideGlyph.users.pathData, [
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
            "M16 3.128a4 4 0 0 1 0 7.744",
            "M22 21v-2a4 4 0 0 0-3-3.87",
            "M9 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8",
        ])
        XCTAssertEqual(LucideGlyph.circleCheck.pathData, [
            "M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20",
            "m16 9-5.5 5.5L8 12",
        ])
        // D-186: user-plus, from upstream's SVG read 2026-09-30.
        XCTAssertEqual(LucideGlyph.userPlus.pathData, [
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
            "M9 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8",
            "M19 8v6",
            "M22 11h-6",
        ])
        // The parser's C, relative and absolute, on a cubic outline (D-189's bell, which left the
        // table in D-216 when the cut S replaced it as Android's notification icon): bounds from
        // .drafts/d189/bell_bounds.py, sampled independently.
        let bell = try? SVGPath.parse("M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326").cgPath.boundingBoxOfPath
        XCTAssertEqual(bell?.minX ?? 0, 3.001, accuracy: 0.01)
        XCTAssertEqual(bell?.maxX ?? 0, 21, accuracy: 0.01)
        XCTAssertEqual(bell?.minY ?? 0, 2, accuracy: 0.01)
        XCTAssertEqual(bell?.maxY ?? 0, 17, accuracy: 0.01)
        // The users circle and circle-check's circle are arcs: their bounds
        // pin the conversion (centre 9,7 r 4; centre 12,12 r 10).
        let head = try? SVGPath.parse("M9 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8").cgPath.boundingBoxOfPath
        XCTAssertEqual(head?.minX ?? 0, 5, accuracy: 0.01)
        XCTAssertEqual(head?.minY ?? 0, 3, accuracy: 0.01)
        XCTAssertEqual(head?.width ?? 0, 8, accuracy: 0.01)
        let ring = try? SVGPath.parse("M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20").cgPath.boundingBoxOfPath
        XCTAssertEqual(ring?.minX ?? 0, 2, accuracy: 0.01)
        XCTAssertEqual(ring?.width ?? 0, 20, accuracy: 0.01)
    }

    /// D-222 (L56): Lucide's sun, shield, download and info (lucide-static v1.58.0, read 2026-10-16), the path data
    /// pasted here; each `<circle>` as its two-arc path from the top (sun cx 12 cy 12 r 4; info cx 12 cy 12 r 10), the
    /// encoding `user-round` and `circle-check` use. The parse and template-image tests below cover them too.
    func testTheS1GlyphsAreLucidesOwnPaths() {
        XCTAssertEqual(LucideGlyph.sun.pathData, [
            "M12 8a4 4 0 1 0 0 8 4 4 0 1 0 0-8", "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41",
            "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41",
        ])
        XCTAssertEqual(LucideGlyph.shield.pathData, [
            "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        ])
        XCTAssertEqual(LucideGlyph.download.pathData, ["M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5"])
        XCTAssertEqual(LucideGlyph.info.pathData, ["M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20", "M12 16v-4", "M12 8h.01"])
        XCTAssertEqual(["sun", "shield", "download", "info"], [LucideGlyph.sun, .shield, .download, .info].map(\.rawValue))
        // The sun's disc spans 8 to 16 and its rays reach 2 and 22; the info ring spans 2 to 22.
        let disc = try? SVGPath.parse("M12 8a4 4 0 1 0 0 8 4 4 0 1 0 0-8").cgPath.boundingBoxOfPath
        XCTAssertEqual(disc?.minX ?? 0, 8, accuracy: 0.01)
        XCTAssertEqual(disc?.width ?? 0, 8, accuracy: 0.01)
        let sun = LucideGlyph.sun.path().cgPath.boundingBoxOfPath
        XCTAssertEqual(sun.minX, 2, accuracy: 0.01)
        XCTAssertEqual(sun.maxY, 22, accuracy: 0.01)
        let info = LucideGlyph.info.path().cgPath.boundingBoxOfPath
        XCTAssertEqual(info.minX, 2, accuracy: 0.01)
        XCTAssertEqual(info.width, 20, accuracy: 0.01)
    }

    /// D-225: Lucide's x and lock (lucide-static v1.59.0, read 2026-10-17), the path data pasted here. `x`: both paths
    /// verbatim. `lock`: its `<rect width="18" height="11" x="3" y="11" rx="2" ry="2">` as SVG 2's rounded-rect path (the
    /// encoding `vibrate` uses), then its path verbatim. The parse and template-image tests below cover them too.
    func testTheS2GlyphsAreLucidesOwnPaths() throws {
        XCTAssertEqual(LucideGlyph.x.pathData, ["M18 6 6 18", "m6 6 12 12"])
        XCTAssertEqual(LucideGlyph.lock.pathData, [
            "M5 11 L19 11 A2 2 0 0 1 21 13 L21 20 A2 2 0 0 1 19 22 L5 22 A2 2 0 0 1 3 20 L3 13 A2 2 0 0 1 5 11 Z",
            "M7 11V7a5 5 0 0 1 10 0v4",
        ])
        XCTAssertEqual(["x", "lock"], [LucideGlyph.x, .lock].map(\.rawValue))
        // The cross spans 6 to 18 both ways; the lock's body is the rect (3, 11, 18 x 11), its shackle reaches y 2.
        let cross = LucideGlyph.x.path().cgPath.boundingBoxOfPath
        XCTAssertEqual(cross.minX, 6, accuracy: 0.01)
        XCTAssertEqual(cross.width, 12, accuracy: 0.01)
        XCTAssertEqual(cross.height, 12, accuracy: 0.01)
        let body = try SVGPath.parse(LucideGlyph.lock.pathData[0]).cgPath.boundingBoxOfPath
        XCTAssertEqual(body.minX, 3, accuracy: 0.01)
        XCTAssertEqual(body.minY, 11, accuracy: 0.01)
        XCTAssertEqual(body.width, 18, accuracy: 0.01)
        XCTAssertEqual(body.height, 11, accuracy: 0.01)
        let lock = LucideGlyph.lock.path().cgPath.boundingBoxOfPath
        XCTAssertEqual(lock.minY, 2, accuracy: 0.01)
        XCTAssertEqual(lock.maxY, 22, accuracy: 0.01)
    }

    /// D-222 (L56): each of You's rows has its Lucide tile (Android's twin, YouTest, compares the same list).
    func testEachSectionHasItsLucideGlyph() {
        XCTAssertEqual(SettingsSection.allCases.map(\.glyph.rawValue), ["user-round", "sun", "shield", "download", "info"])
    }

    /// D-222 (L58): You's row summaries, one golden table (Android's YouTest pins the same). Joined on screen by the
    /// theme's separator and spoken as "title, part, part": no part carries a separator of its own.
    func testYouRowsSummaries() {
        func facts(signedIn: Bool = false, method: String? = nil, phones: Int? = nil, theme: AppTheme = .aurora,
                   mode: AppearanceMode = .system, lock: LockTimeout = .twoMinutes, canLock: Bool = true) -> YouRowFacts {
            YouRowFacts(signedIn: signedIn, methodLabel: method, phoneCount: phones, theme: theme, mode: mode,
                        lockTimeout: lock, lockAvailable: canLock, version: "0.4.0")
        }
        XCTAssertEqual(YouRows.parts(.profileAccount, facts()), ["No account"])
        XCTAssertEqual(YouRows.parts(.profileAccount, facts(phones: 2)), ["No account"], "no count without an account")
        // D-234 (decision 84): the row names the method in one word; the page keeps "Signed in with ...".
        XCTAssertEqual(YouRows.parts(.profileAccount, facts(signedIn: true, method: SignInMethod.apple.rowLabel, phones: 2)),
                       ["Apple", "2 phones"])
        XCTAssertEqual(YouRows.parts(.profileAccount, facts(signedIn: true, phones: 1)), ["Signed in", "1 phone"])
        XCTAssertEqual(YouRows.parts(.profileAccount, facts(signedIn: true, method: SignInMethod.google.rowLabel)),
                       ["Google"])
        XCTAssertEqual(YouRows.parts(.profileAccount, facts(signedIn: true, method: SignInMethod.google.rowLabel, phones: 0)),
                       ["Google"], "a count of 0 is left out, never \"0 phones\"")
        XCTAssertEqual(YouRows.parts(.appearance, facts(theme: .aurora, mode: .system)), ["Aurora", "System"])
        XCTAssertEqual(YouRows.parts(.appearance, facts(theme: .vardr, mode: .light)), ["Vardr Labs", "Light"])
        XCTAssertEqual(YouRows.parts(.appearance, facts(theme: .norse, mode: .light)), ["Norse", "Dark"])
        let lines: [(LockTimeout, String)] = [
            (.immediately, "App lock immediately"), (.oneMinute, "App lock after 1 minute"),
            (.twoMinutes, "App lock after 2 minutes"), (.fiveMinutes, "App lock after 5 minutes"),
            (.fifteenMinutes, "App lock after 15 minutes"), (.off, "App lock off"),
        ]
        XCTAssertEqual(lines.map(\.0), LockTimeout.allCases)
        for (timeout, line) in lines {
            XCTAssertEqual(YouRows.parts(.security, facts(lock: timeout)), [line])
            XCTAssertEqual(YouRows.securityLine(timeout, available: true), line)
            XCTAssertEqual(YouRows.parts(.security, facts(lock: timeout, canLock: false)), ["App lock needs a phone lock"])
        }
        XCTAssertEqual(YouRows.parts(.data, facts()), ["Export, import, currency"])
        XCTAssertEqual(YouRows.parts(.about, facts()), ["0.4.0", "promises", "licences"])
        XCTAssertEqual(YouRows.spoken(title: "Security", parts: ["App lock off"]), "Security, App lock off")
        XCTAssertEqual(YouRows.spoken(title: "Appearance", parts: ["Norse", "Dark"]), "Appearance, Norse, Dark")
        for section in SettingsSection.allCases {
            for f in [facts(), facts(signedIn: true, method: SignInMethod.apple.label, phones: 3, theme: .norse)] {
                for part in YouRows.parts(section, f) {
                    XCTAssertFalse(part.contains(Runes.separator) || part.contains("\u{00B7}"), "\(section) \(part)")
                }
            }
        }
    }

    // ---- D-222 Task 2: You's rows drawn (source guards over AccountHubScreen.swift) ----

    private func appFile(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The text between the first `from` and the next `to` after it.
    private func between(_ text: String, from: String, to: String) throws -> String {
        let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
        return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
    }

    /// D-222 (L56 to L58): each of You's rows is a `SettingsRow`: the section's Lucide tile, the title through the name
    /// role at 16, the summary as `YouRows.parts` joined by the theme's separator (never a string joined in Swift), and
    /// one spoken label, "title, part, part". The card hugs its rows (padded `listPadH` / `listPadV`, the card at 0).
    /// The facts are live: the lock's timeout from its stored key, whether the phone can lock, and the phone count read
    /// from the cloud when signed in (a failed read is nil, so the count is left out).
    func testYouRowsDrawTilesAndLiveSummaries() throws {
        let hub = try appFile("AccountHubScreen.swift")
        let row = try between(hub, from: "private func row(_ section: SettingsSection) -> some View {", to: "\n    }\n")
        XCTAssertTrue(row.contains("SettingsRow(glyph: section.glyph, title: section.title, parts: YouRows.parts(section, rowFacts))"), row)
        let settingsRow = try between(hub, from: "struct SettingsRow: View {", to: "\n}\n")
        // D-225: SettingsRow is a ListRow with a tile and a chevron; AppearanceTests.testTheListRowIsOneComponent pins
        // the row (tile, rowGap, the name role at 16, the separator summary, rowPadV, the chevron, one spoken label).
        XCTAssertTrue(settingsRow.contains("ListRow(glyph: glyph, title: title, parts: parts, trailing: .chevron)"), settingsRow)
        let listRow = try between(try appFile("ListRows.swift"), from: "struct ListRow: View {", to: "\n}\n")
        for needle in ["SettingsTile(glyph: glyph)", "Separator.text(parts, size: TypeScale.detail)",
                       ".accessibilityLabel(Text(verbatim: spoken))", "YouRows.spoken(title: title, parts: parts)"] {
            XCTAssertTrue(listRow.contains(needle), "ListRow: \(needle)")
        }
        XCTAssertFalse(settingsRow.contains("Text(verbatim: parts"), "a summary joined in Swift")
        XCTAssertFalse(settingsRow.contains(".joined("), "a summary joined in Swift")
        // D-225: the card is a ListCard (its padding and glassCard(padding: 0) pinned on ListCard itself).
        let list = try between(hub, from: "ListCard {\n                    ForEach(SettingsSection.allCases, id: \\.self) { section in", to: ".padding(20)")
        XCTAssertTrue(list.contains("row(section)"), list)
        XCTAssertFalse(hub.contains("YouCopy.securityLine"))
        XCTAssertFalse(hub.contains("AboutInfo.versionLine("))
        XCTAssertFalse(hub.contains("AppearanceCopy.summary("))
        let facts = try between(hub, from: "private var rowFacts: YouRowFacts {", to: "\n    }\n")
        for part in ["signedIn: signedIn", "methodLabel: method?.rowLabel", "phoneCount: phoneCount", "theme: AppTheme.parse(themeRaw)",
                     "mode: AppearanceMode.parse(appearanceRaw)", "lockTimeout: LockTimeout.parse(lockRaw)",
                     "lockAvailable: lockAvailable", "version: AboutInfo.current().app"] {
            XCTAssertTrue(facts.contains(part), "rowFacts: \(part)")
        }
        let reload = try between(hub, from: "private func reload() async {", to: "\n    }\n")
        XCTAssertTrue(reload.contains("phoneCount = signedIn ? await Phones.activeCount() : nil"), reload)
        XCTAssertTrue(reload.contains("lockAvailable = AppLock.available"), reload)
        // Fix wave (S7): the phone count is read last, after the username merge and the facts, so a slow offline server
        // read never holds back the setup steps' gating.
        let order = ["usernameState = UsernameState.merge(", "await readFacts()", "phoneCount = signedIn ? await Phones.activeCount() : nil"]
        let at = try order.map { try XCTUnwrap(reload.range(of: $0), $0).lowerBound }
        XCTAssertEqual(at, at.sorted(), "merge, facts, then the phone read")
        // Fix wave (S8): the first frame reads the phone's lock as it is (Android reads it at once), never an assumed true.
        XCTAssertTrue(hub.contains("@State private var lockAvailable = AppLock.available"))
        XCTAssertFalse(hub.contains("@State private var lockAvailable = true"))
        // A passcode set in the phone's Settings shows on return.
        XCTAssertTrue(try between(hub, from: ".onChange(of: scenePhase) { _, phase in", to: "\n        }\n").contains("lockAvailable = AppLock.available"))
        // The count: the account's devices from the server, through the pure `activeCount`; a failed read is nil.
        let read = try between(try appFile("Phones.swift"), from: "@MainActor static func activeCount() async -> Int? {", to: "\n    }\n")
        XCTAssertTrue(read.contains(".collection(\"devices\").getDocuments(source: .server)"), read)
        XCTAssertTrue(read.contains("return activeCount(snapshot.documents.map { ($0.documentID, $0.data()) })"), read)
        XCTAssertTrue(read.contains("else { return nil }"), read)
        XCTAssertFalse(try appFile("Profile.swift").contains("securityLine"), "YouCopy.securityLine is gone")
    }

    /// D-222 (L56): the row's tile is the theme's tile: `TileShape.of(theme, person: false)` through `TileClip` (Aurora
    /// rounded, Norse C2's diamond at its drawn side with the gold-brown edge, Vardr Labs square) at `settingsTile`, the
    /// glyph in `accent` at `settingsGlyph` on `accentSoft`, upright, hidden from VoiceOver.
    func testTheSettingsTileIsTheThemesTile() throws {
        let tile = try between(try appFile("AccountHubScreen.swift"), from: "struct SettingsTile: View {", to: "\n}\n")
        for needle in [
            "TileShape.of(AppearanceState.shared.theme, person: false)", "TileClip.shape(kind, size: size)",
            "TileClip.drawnSide(kind, size: size)", "TileClip.edge(kind)", "ThemeArt.m(\"settingsTile\")",
            "ThemeArt.m(\"settingsGlyph\")", "LucideIcon(glyph: glyph, size: ThemeArt.m(\"settingsGlyph\"))",
            ".foregroundStyle(Tokens.accent)", ".background(Tokens.accentSoft, in: shape)", ".frame(width: size, height: size)",
            ".accessibilityHidden(true)",
        ] {
            XCTAssertTrue(tile.contains(needle), "SettingsTile: \(needle)")
        }
        for banned in ["Circle()", "RoundedRectangle(", "rotationEffect"] {
            XCTAssertFalse(tile.contains(banned), "SettingsTile: \(banned)")
        }
    }

    /// D-180: the Balance tab's words and the legend's rounding, the same on
    /// Android (OverviewTest).
    func testOverviewWordsAndPercentsMatchAndroid() {
        XCTAssertEqual(OverviewCopy.percent(5903), "59%")
        XCTAssertEqual(OverviewCopy.percent(3450), "35%")
        XCTAssertEqual(OverviewCopy.percent(49), "<1%")
        XCTAssertEqual(OverviewCopy.percent(50), "1%")
        XCTAssertEqual(OverviewCopy.chip(-3_200, "€32.00"), "You owe €32.00")
        XCTAssertEqual(OverviewCopy.chip(90_000, "₹900.00"), "You get back ₹900.00")
        XCTAssertEqual(OverviewCopy.groups(1), "1 group")
        XCTAssertEqual(OverviewCopy.verb(-1), "you owe")
        XCTAssertEqual(OverviewCopy.settlesIn(people: 4, currency: "INR"), "4 people · settles in INR")
    }

    /// D-180 (and D-181's two-step picker): the tree in two levels. The
    /// default tree has 13 parents; Travel's leaves in order; a leaf's parent.
    func testCategoryLevelsSplitTheTreeInTwo() {
        let tree = ffiCategoryTreeDefault()
        let parents = CategoryLevels.parents(tree)
        XCTAssertEqual(parents.count, 13)
        let travel = parents.first { $0.name == "Travel" }?.id
        XCTAssertEqual(CategoryLevels.leaves(of: travel, in: tree).map(\.name),
                       ["Flights", "Trains", "Buses", "Taxis", "Fuel", "Tolls", "Parking", "Rentals"])
        let trains = CategoryLevels.leaves(of: travel, in: tree).first { $0.name == "Trains" }?.id
        XCTAssertEqual(CategoryLevels.parent(of: trains, in: tree), travel)
        XCTAssertNil(CategoryLevels.parent(of: travel, in: tree))
        XCTAssertNil(CategoryLevels.parent(of: nil, in: tree))
        XCTAssertTrue(CategoryLevels.leaves(of: nil, in: tree).isEmpty)
        XCTAssertEqual(CategoryLevels.name(nil, in: tree), "No category")
    }

    /// D-181: the two-step picker keeps a sub-category only under its own
    /// category, and says what is missing (the founder, 2026-09-29).
    func testTheCategoryPickerKeepsASubCategoryOnlyUnderItsParent() {
        let tree = ffiCategoryTreeDefault()
        let parents = CategoryLevels.parents(tree)
        let travel = parents.first { $0.name == "Travel" }?.id
        let stay = parents.first { $0.name == "Stay" }?.id
        let trains = CategoryLevels.leaves(of: travel, in: tree).first { $0.name == "Trains" }?.id
        XCTAssertEqual(CategoryLevels.keep(trains, under: travel, in: tree), trains)
        XCTAssertNil(CategoryLevels.keep(trains, under: stay, in: tree))
        XCTAssertNil(CategoryLevels.keep(trains, under: nil, in: tree))
        XCTAssertNil(CategoryLevels.keep(nil, under: travel, in: tree))
        XCTAssertEqual(CategoryCopy.hint("Travel"), "Choose one to file it under Travel.")
        XCTAssertEqual([CategoryCopy.category, CategoryCopy.subcategory, CategoryCopy.none, CategoryCopy.choose],
                       ["Category", "Sub-category", "None", "Choose one"])
    }

    /// D-181: the Expenses tab in sections: the group with the newest
    /// expense first, newest first inside, "Personal" last; a chip narrows
    /// it to one group. Android's twin: ExpenseSectionsTest.
    func testExpensesAreGroupedByGroupNewestFirst() {
        func e(_ id: String, _ group: String?, _ ts: UInt64) -> ProjectedExpenseOut {
            ProjectedExpenseOut(
                id: id,
                expense: ExpenseInput(payer: String(repeating: "0", count: 31) + "1", paidMinor: 100,
                                      currencyCode: "INR", shares: [], group: group, conversion: nil),
                note: nil, category: nil, timestampMs: ts, addedBy: nil, addedAtMs: ts, deleted: false,
                lastEditedAtMs: nil, lastEditedBy: nil, seen: [], moneyConflict: []
            )
        }
        let goa = String(repeating: "a", count: 32), flat = String(repeating: "b", count: 32)
        let all = [e("1", goa, 10), e("2", flat, 30), e("3", nil, 99), e("4", goa, 20)]
        let sections = ExpenseSections.build(all, only: nil)
        XCTAssertEqual(sections.map(\.groupID), [flat, goa, nil])
        XCTAssertEqual(sections[1].expenses.map(\.id), ["4", "1"])
        let onlyGoa = ExpenseSections.build(all, only: goa)
        XCTAssertEqual(onlyGoa.map(\.groupID), [goa])
        XCTAssertEqual(ExpenseCopy.effect(-120_000, "₹1,200.00"), "you owe ₹1,200.00")
        XCTAssertEqual(ExpenseCopy.effect(900_000, "₹9,000.00"), "you lent ₹9,000.00")
        XCTAssertEqual(ExpenseCopy.effect(0, "₹0.00"), "not involved")
    }

    /// D-182: Settle up across every group, on the groups proof's seed.
    /// Goldens worked independently in .drafts/d182/golden.py: a group's
    /// entries are what each person owes you after what you owe them.
    /// Android's twin: SettleAllTest.
    func testSettleUpCoversEveryGroup() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("settle-all-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        try GroupsProof.seed(store)

        func owed(_ sections: [SettleSection]) -> [String: Set<String>] {
            Dictionary(uniqueKeysWithValues: sections.map { s in
                (s.named.group.name, Set(s.lines.flatMap { line in
                    line.entries.map { "\(LedgerStore.label(for: $0.id, in: s.named)) \(line.currencyCode) \($0.minorUnits)" }
                }))
            })
        }
        let before = try SettleAll.sections(store)
        XCTAssertEqual(owed(before), [
            "Goa trip": ["Rahul INR 180000", "Meera INR 260000", "Sam INR 300000"],
            "Flat 4B": ["Meera INR 45000", "Kiran INR 90000"],
            "Paris": ["Ana EUR -3200"],
        ])
        XCTAssertFalse(before.contains { $0.settled }, "nothing is settled yet")

        // "I paid Ana": the card's record, through §5.8's path.
        let paris = try XCTUnwrap(before.first { $0.named.group.name == "Paris" })
        let ana = try XCTUnwrap(paris.lines.first?.entries.first)
        let settlement = SettleAll.settlement(for: ana, currency: "EUR", in: paris.named)
        XCTAssertEqual(settlement.from, paris.named.selfId)
        XCTAssertEqual(settlement.to, ana.id)
        XCTAssertEqual(settlement.amountMinor, 3_200)
        XCTAssertEqual(settlement.group, paris.named.group.id)
        _ = try store.appendSettlement(settlement)

        let after = try SettleAll.sections(store)
        XCTAssertTrue(try XCTUnwrap(after.first { $0.named.group.name == "Paris" }).settled, "Paris is all settled")
        XCTAssertFalse(try XCTUnwrap(after.first { $0.named.group.name == "Goa trip" }).settled)
        var rest = owed(after), was = owed(before)
        rest["Paris"] = nil
        was["Paris"] = nil
        XCTAssertEqual(rest, was, "a settlement is filed under its group only")

        // The words, the twin of Android's settle_up_words_match_ios.
        XCTAssertEqual(SettleAllCopy.sentence(youOwe: false, other: "Rahul"), "Rahul pays you")
        XCTAssertEqual(SettleAllCopy.sentence(youOwe: true, other: "Ana"), "You pay Ana")
        XCTAssertEqual(SettleAllCopy.action(youOwe: false, other: "Rahul"), "Mark as paid")
        XCTAssertEqual(SettleAllCopy.action(youOwe: true, other: "Ana"), "I paid Ana")
        XCTAssertEqual(SettleAllCopy.settled("Paris"), "Paris is all settled")
        XCTAssertEqual(SettleAllCopy.recorded("Ana"), "Settled with Ana. Recorded in your ledger. No money moved.")
        XCTAssertEqual(SettleAllCopy.explainer, "Marks this as paid here. It does not move money.")
    }

    /// D-234 (L7, decision 87): Settle up's empty line: with no groups at all it says to add an expense; with groups that
    /// are all read-only on this phone (nothing can be recorded in them) it says so. Android's twin:
    /// SettleAllTest.settle_ups_empty_line_says_why.
    func testSettleUpsEmptyLineSaysWhy() {
        XCTAssertEqual(SettleAllCopy.empty(groupCount: 0), "Nothing to settle yet. Add an expense with the + below.")
        XCTAssertEqual(SettleAllCopy.empty(groupCount: 1), "Nothing to settle from this phone. Your groups here can't take new records.")
        XCTAssertEqual(SettleAllCopy.empty(groupCount: 3), SettleAllCopy.nothingHere)
        XCTAssertFalse(SettleAllCopy.nothingHere.contains("\u{2014}") || SettleAllCopy.nothingHere.contains("\u{2013}")) // em-dash-ok: asserts absence
    }

    /// D-228: the "no value" placeholder is SKIPTI's own typed hyphen, one constant (Android's twin:
    /// `Placeholder.NONE`, pinned by its own test).
    func testThePlaceholderIsAPlainHyphen() {
        XCTAssertEqual(Placeholder.none.unicodeScalars.map(\.value), [0x2D])
    }

    /// D-228: every money fallback goes through the placeholder, so no screen spells its own. A source guard over
    /// every file in the App folder: no line formats money and falls back to a visible literal (an empty fallback, as
    /// for a spoken label, shows nothing and stays), no line falls back to a hand-typed hyphen, and the constant is
    /// used at least 14 times (the 13 money fallbacks and the Overview tile).
    func testMoneyFallbacksUseThePlaceholder() throws {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        let files = try FileManager.default.contentsOfDirectory(at: app, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "swift" }
        XCTAssertGreaterThan(files.count, 50, "the App folder was read")
        var uses = 0
        for file in files {
            let text = try String(contentsOf: file, encoding: .utf8)
            for line in text.components(separatedBy: "\n") {
                let visible = line.range(of: #"\?\? "[^"]"#, options: .regularExpression) != nil
                XCTAssertFalse(line.contains("ffiMoney") && visible,
                               "\(file.lastPathComponent): a money fallback spelled by hand: \(line)")
                XCTAssertFalse(line.contains("?? \"-\""), "\(file.lastPathComponent): the placeholder typed by hand: \(line)")
            }
            uses += text.components(separatedBy: "Placeholder.none").count - 1
        }
        XCTAssertGreaterThanOrEqual(uses, 14, "Placeholder.none at every fallback")
    }

    /// D-228: the recorded settle sentence and the placeholder, shown and spoken, carry no em dash and no en dash (the
    /// twin of Android's recorded_copy_and_placeholder_have_no_dash). The code points are written in decimal (8212 is
    /// U+2014, 8211 is U+2013) so this file carries neither character nor an escape for one.
    func testRecordedCopyAndPlaceholderHaveNoDash() {
        for text in [SettleAllCopy.recorded("Ana"), Placeholder.none, Placeholder.spokenNone] {
            XCTAssertFalse(text.unicodeScalars.contains { $0.value == 8212 || $0.value == 8211 }, "a dash: \(text)")
        }
    }

    /// D-228 decision 43: the placeholder shown as a value is SPOKEN as "none", never "hyphen" or "minus" (the twin of
    /// Android's the_placeholder_is_spoken_as_none, same vectors); any other value is spoken as shown.
    func testThePlaceholderIsSpokenAsNone() {
        XCTAssertEqual(Placeholder.spokenNone, "none")
        XCTAssertEqual(Placeholder.spoken("-"), "none")
        XCTAssertEqual(Placeholder.spoken("$1.00"), "$1.00")
        XCTAssertEqual(Placeholder.spoken("-1"), "-1")
        XCTAssertEqual(Placeholder.spoken(""), "")
    }

    /// The App folder's Swift sources, by file name.
    private func appSources() throws -> [String: String] {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        var out: [String: String] = [:]
        for file in try FileManager.default.contentsOfDirectory(at: app, includingPropertiesForKeys: nil)
            where file.pathExtension == "swift" {
            out[file.lastPathComponent] = try String(contentsOf: file, encoding: .utf8)
        }
        return out
    }

    /// D-228 decision 43 (final review I1): when core cannot speak an amount, the spoken fallback is "", never the
    /// placeholder (VoiceOver could read it as a minus), on both shells (Android's twin: the_spoken_money_fallback_is_empty).
    /// Core does refuse a made-up currency, so the fallback is a real path; and every `try? ffiMoneySpoken(` call in the
    /// App folder, one line or several, falls back to `?? ""`.
    func testTheSpokenMoneyFallbackIsEmpty() throws {
        XCTAssertNil(try? ffiMoneySpoken(minorUnits: 100, currencyCode: "ZZZ", locale: .enUs), "core refuses ZZZ")
        XCTAssertNotNil(try? ffiMoneySpoken(minorUnits: 100, currencyCode: "USD", locale: .enUs), "a real currency speaks")
        var calls = 0
        for (name, text) in try appSources() {
            var rest = text[...]
            while let call = rest.range(of: "try? ffiMoneySpoken(") {
                calls += 1
                let after = rest[call.upperBound...]
                guard let fallback = after.range(of: "?? ") else {
                    XCTFail("\(name): a spoken money call with no fallback")
                    break
                }
                let between = after[..<fallback.lowerBound]
                XCTAssertFalse(between.contains("ffiMoney"), "\(name): the fallback found belongs to another call")
                XCTAssertTrue(after[fallback.upperBound...].hasPrefix("\"\""),
                              "\(name): a spoken money fallback that is not \"\": \(after[fallback.upperBound...].prefix(30))")
                rest = after[fallback.upperBound...]
            }
        }
        XCTAssertGreaterThanOrEqual(calls, 8, "every spoken money call was read")
    }

    /// D-228 decision 43: the sites that show the placeholder as a whole value, with no spoken money label of their
    /// own, speak it through `Placeholder.spoken`: the Overview stat tile (the Settled tile and its money fallbacks)
    /// and the import proposal row (its amount fallback). The twin of Android's placeholder_sites_speak_none.
    func testPlaceholderSitesSpeakNone() throws {
        let sources = try appSources()
        let overview = try XCTUnwrap(sources["OverviewScreen.swift"])
        XCTAssertTrue(overview.contains("stat(OverviewCopy.settledShort, Placeholder.none,"), "the Settled tile shows it")
        XCTAssertTrue(overview.contains("Text(verbatim: value)") &&
                      overview.contains(".accessibilityLabel(Text(verbatim: Placeholder.spoken(value)))"),
                      "the stat tile speaks its value through Placeholder.spoken")
        let review = try XCTUnwrap(sources["ImportReview.swift"])
        XCTAssertTrue(review.contains(#"\(p.noteText ?? "untitled"), \(Placeholder.spoken(p.amountText)) \(p.currencyCode)"#),
                      "the proposal row speaks its amount through Placeholder.spoken")
    }

    /// D-183: the Add sheet's live line is core's effect in words (the
    /// founder's canvas: ₹2,400 lunch, four ways, you paid). Worked by hand:
    /// ₹600.00 each; you paid, so the other three owe you ₹1,800.00; Rahul
    /// paid, so you owe him ₹600.00; not in it, nothing. Android's twin:
    /// AddSheetTest.
    func testTheAddSheetSummaryIsCoresEffectInWords() throws {
        func id(_ n: Int) -> String { String(format: "%032x", n) }
        let you = id(1), rahul = id(2), meera = id(3), sam = id(4)
        func expense(_ payer: String, _ shares: [ShareOut]) -> ExpenseInput {
            ExpenseInput(payer: payer, paidMinor: 240_000, currencyCode: "INR",
                         shares: shares.map { IdAmount(id: $0.id, amountMinor: $0.minorUnits) },
                         group: nil, conversion: nil)
        }
        let four = try ffiSplitCompute(totalMinor: 240_000, currencyCode: "INR",
                                       members: [you, rahul, meera, sam], spec: .equal)
        XCTAssertEqual(AddSheetCopy.summary(expense(you, four), viewer: you, locale: .enIn), "You\u{2019}ll get back ₹1,800.00")
        XCTAssertEqual(AddSheetCopy.summary(expense(rahul, four), viewer: you, locale: .enIn), "You\u{2019}ll owe ₹600.00")
        let three = try ffiSplitCompute(totalMinor: 240_000, currencyCode: "INR",
                                        members: [rahul, meera, sam], spec: .equal)
        XCTAssertEqual(AddSheetCopy.summary(expense(rahul, three), viewer: you, locale: .enIn), "Nothing changes for you")

        XCTAssertEqual(AddSheetCopy.howMuch, "How much?")
        XCTAssertEqual(AddSheetCopy.whatFor, "What was it for?")
        XCTAssertEqual(AddSheetCopy.ledger, "Ledger")
        XCTAssertEqual(AddSheetCopy.summary(effect: 3_200, formatted: "€32.00"), "You\u{2019}ll get back €32.00")
        XCTAssertEqual(AddSheetCopy.summary(effect: -3_200, formatted: "€32.00"), "You\u{2019}ll owe €32.00")
        XCTAssertEqual(AddSheetCopy.summary(effect: 0, formatted: "€0.00"), "Nothing changes for you")
    }

    /// D-184: the tester's own numbers, an exact split of ₹30,000 whose four
    /// amounts came to ₹25,000. Core refuses it; the words say by how much,
    /// where the app used to show core's message in paise. Goldens worked by
    /// hand in .drafts/d184/golden.py. Twin of Android's TesterFixesTest.
    func testTheTestersSplitIsRefusedInWords() {
        func id(_ n: Int) -> String { String(format: "%032x", n) }
        let roster = [id(1), id(2), id(3), id(4)]
        let amounts: [Int64] = [50_000, 450_000, 1_000_000, 1_000_000]
        let spec = SplitSpecInput.exact(entries: zip(roster, amounts).map { IdAmount(id: $0, amountMinor: $1) })
        do {
            _ = try ffiSplitCompute(totalMinor: 3_000_000, currencyCode: "INR", members: roster, spec: spec)
            XCTFail("core accepted a split that does not add up")
        } catch let e as CoreError {
            XCTAssertEqual(SplitWords.message(e, currency: "INR", locale: .enIn),
                           "The amounts add up to ₹25,000.00 of ₹30,000.00. ₹5,000.00 is still to assign.")
            XCTAssertEqual(SplitWords.live(e, currency: "INR", locale: .enIn),
                           SplitWords.message(e, currency: "INR", locale: .enIn))
        } catch {
            XCTFail("not a core error: \(error)")
        }
        XCTAssertEqual(SplitWords.message(.ExactSumMismatch(expected: 3_000_000, got: 3_500_000), currency: "INR", locale: .enIn),
                       "The amounts add up to ₹35,000.00, ₹5,000.00 more than the ₹30,000.00 spent.")
        XCTAssertEqual(SplitWords.message(.TotalMismatch(expected: 640_000, got: 400_000), currency: "INR", locale: .enIn),
                       "The items add up to ₹4,000.00 of ₹6,400.00. ₹2,400.00 is still to assign.")
        XCTAssertEqual(SplitWords.message(.PercentMismatch(gotBps: 9_050), currency: "INR", locale: .enIn),
                       "The percentages add up to 90.5%. They need to make 100%.")
        // The live line counts an amount not typed yet as zero; core takes a
        // zero entry and refuses on the sum: ₹15,000 of ₹30,000.
        let partial = SplitSpecInput.exact(entries: zip(roster, [50_000, 450_000, 1_000_000, 0] as [Int64])
            .map { IdAmount(id: $0, amountMinor: $1) })
        do {
            _ = try ffiSplitCompute(totalMinor: 3_000_000, currencyCode: "INR", members: roster, spec: partial)
            XCTFail("core accepted a split that does not add up")
        } catch {
            XCTAssertEqual(SplitWords.live(error, currency: "INR", locale: .enIn),
                           "The amounts add up to ₹15,000.00 of ₹30,000.00. ₹15,000.00 is still to assign.")
        }
        XCTAssertEqual([9_000, 9_050, 9_005, 12_345, 0].map { SplitWords.percent($0) },
                       ["90%", "90.5%", "90.05%", "123.45%", "0%"])
    }

    /// D-184, negative: only a split that does not add up is live; other
    /// errors wait for Save, and one not about the split keeps its words.
    func testOnlyASplitThatDoesNotAddUpIsLive() {
        XCTAssertNil(SplitWords.live(CoreError.EmptyParticipants, currency: "INR", locale: .enIn))
        XCTAssertNil(SplitWords.live(CocoaError(.fileNoSuchFile), currency: "INR", locale: .enIn))
        XCTAssertEqual(SplitWords.message(.EmptyParticipants, currency: "INR", locale: .enIn),
                       "Choose at least one person to split with.")
        XCTAssertNil(SplitWords.message(.InvalidInput("x"), currency: "INR", locale: .enIn))
        XCTAssertEqual(EraseCopy.doneTitle, "This phone's ledger is erased")
        XCTAssertEqual(EraseCopy.doneBody,
                       "Everything SKIPTI kept on this phone is gone. Your account, if you have one, is not deleted.")
    }

    /// D-180: a person's tone from their name (Unicode sum modulo six), worked
    /// in .drafts/d180/chips.py: meera 522 -> coral, ana 304 -> sky, sam 321
    /// -> amber, kiran 533 -> pink. Case does not matter.
    func testAPersonsToneComesFromTheirName() {
        XCTAssertEqual(GroupTone.person("Meera"), .coral)
        XCTAssertEqual(GroupTone.person("Ana"), .sky)
        XCTAssertEqual(GroupTone.person("sam"), .amber)
        XCTAssertEqual(GroupTone.person("KIRAN"), .pink)
    }

    /// D-179: a group's tone is its id's first byte modulo six, the same on
    /// Android (GroupToneTest); worked by hand: 0x00 -> 0 coral, 0x05 -> 5
    /// pink, 0x06 -> 0 coral, 0xa1 = 161 -> 5 pink, 0xff = 255 -> 3 amber.
    func testAGroupsToneComesFromItsId() {
        let tail = String(repeating: "0", count: 30)
        XCTAssertEqual(GroupTone.of("00" + tail), .coral)
        XCTAssertEqual(GroupTone.of("05" + tail), .pink)
        XCTAssertEqual(GroupTone.of("06" + tail), .coral)
        XCTAssertEqual(GroupTone.of("a1" + tail), .pink)
        XCTAssertEqual(GroupTone.of("ff" + tail), .amber)
        XCTAssertEqual(GroupTone.of("01" + tail), .blue, "D-220 (L29): blue is violet's place, the second of the six")
        XCTAssertEqual(GroupTone.initial("  goa trip"), "G")
        XCTAssertEqual(GroupTone.initial("   "), "?")
        XCTAssertEqual(GroupsCopy.detail(people: 1, currency: "INR"), "1 person · INR")
    }

    /// D-223 (L-D217-10): Lucide's inbox (lucide-static v1.59.0, read 2026-10-17), pasted here in upstream's order: the
    /// `<polyline points="22 12 16 12 14 15 10 15 8 12 2 12">` as `M` then `L` through its points in order, then the
    /// path verbatim. Not on Lucide's Feather-derived list (ISC only).
    func testTheInboxIsLucidesOwnPath() {
        XCTAssertEqual(LucideGlyph.inbox.pathData, [
            "M22 12 L16 12 L14 15 L10 15 L8 12 L2 12",
            "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
        ])
        XCTAssertEqual(LucideGlyph.inbox.rawValue, "inbox")
        // The tray spans 2 to 22 across and 4 to 20 down; the polyline's notch reaches 15.
        let tray = LucideGlyph.inbox.path().cgPath.boundingBoxOfPath
        XCTAssertEqual(tray.minX, 2, accuracy: 0.01)
        XCTAssertEqual(tray.maxX, 22, accuracy: 0.01)
        XCTAssertEqual(tray.minY, 4, accuracy: 0.01)
        XCTAssertEqual(tray.maxY, 20, accuracy: 0.01)
        let notch = try? SVGPath.parse("M22 12 L16 12 L14 15 L10 15 L8 12 L2 12").cgPath.boundingBoxOfPath
        XCTAssertEqual(notch?.minY ?? 0, 12, accuracy: 0.01)
        XCTAssertEqual(notch?.maxY ?? 0, 15, accuracy: 0.01)
        XCTAssertEqual(notch?.width ?? 0, 20, accuracy: 0.01)
    }

    /// The parser draws Lucide's data rather than a redraw of it. Straight
    /// lines pin the absolute/relative commands; the user-round CIRCLE is
    /// two large arcs, so its tight bounds pin the arc conversion: a
    /// wrong centre, sweep or segment count moves the bounds off (7,3,10,10).
    func testSvgPathParserDrawsLucideGeometryVerbatim() throws {
        let plus = LucideGlyph.plus.path().cgPath.boundingBoxOfPath
        XCTAssertEqual(plus.minX, 5, accuracy: 0.001)
        XCTAssertEqual(plus.minY, 5, accuracy: 0.001)
        XCTAssertEqual(plus.width, 14, accuracy: 0.001)
        XCTAssertEqual(plus.height, 14, accuracy: 0.001)

        let circle = try SVGPath.parse("M12 3a5 5 0 1 0 0 10 5 5 0 1 0 0-10").cgPath.boundingBoxOfPath
        XCTAssertEqual(circle.minX, 7, accuracy: 0.01)
        XCTAssertEqual(circle.minY, 3, accuracy: 0.01)
        XCTAssertEqual(circle.width, 10, accuracy: 0.01)
        XCTAssertEqual(circle.height, 10, accuracy: 0.01)

        // Every shipped string parses. `LucideGlyph.path()` swallows a
        // parse failure so the UI can never crash, which is exactly why a
        // test must refuse one.
        for glyph in LucideGlyph.allCases {
            for d in glyph.pathData {
                XCTAssertNoThrow(try SVGPath.parse(d), "\(glyph.rawValue): \(d)")
            }
        }

        // A malformed path is a typed refusal, not a partial drawing.
        XCTAssertThrowsError(try SVGPath.parse("M5"))
        XCTAssertThrowsError(try SVGPath.parse("5 5"))
    }

    /// SwiftUI's `tabItem` renders only Image and Text: a custom Shape is
    /// silently DROPPED and the tab shows no icon. So the glyphs are
    /// rasterised; this proves each raster is a template image that
    /// actually contains ink, not a blank square that would pass unseen.
    func testEveryLucideGlyphRendersToANonBlankTemplateImage() {
        for glyph in LucideGlyph.allCases {
            let image = glyph.templateImage()
            XCTAssertEqual(image.size, CGSize(width: 24, height: 24), glyph.rawValue)
            XCTAssertEqual(image.renderingMode, .alwaysTemplate, glyph.rawValue)
            XCTAssertGreaterThan(inkedPixels(image), 20, "\(glyph.rawValue) rendered blank")
        }
    }

    private func inkedPixels(_ image: UIImage) -> Int {
        guard let cg = image.cgImage else { return 0 }
        let width = cg.width, height = cg.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        bytes.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return }
            context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return stride(from: 3, to: bytes.count, by: 4).reduce(0) { $0 + (bytes[$1] > 0 ? 1 : 0) }
    }

    /// D-128: the app shipped with NO AccentColor asset and no
    /// ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME, so iOS's default
    /// accent was system blue (#007AFF). That is what painted the Sign in
    /// placeholder blue on device while every explicitly tinted control was
    /// steel: styling the prompt changed nothing, because the colour came
    /// from the default accent. This pins the asset to ADR-0023 §8's accent
    /// (`Tokens.accent`) so it can neither disappear nor drift.
    func testAccentColorAssetIsTheAccentToken() throws {
        let asset = try XCTUnwrap(
            UIColor(named: "AccentColor"),
            "AccentColor asset missing: iOS falls back to system blue"
        )
        func rgb(_ style: UIUserInterfaceStyle) -> [Int] {
            let resolved = asset.resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            resolved.getRed(&r, green: &g, blue: &b, alpha: &a)
            return [r, g, b].map { Int(($0 * 255).rounded()) }
        }
        // D-176: compared with the accent TOKEN, not pinned hexes. Pinned to
        // ADR-0023's steel, this passed through D-174 while the tokens moved
        // to cobalt and the asset stayed steel, so system controls (toggles,
        // pickers) drew a different accent from everything else.
        func hexRGB(_ hex: String) -> [Int] {
            let v = Int(hex.dropFirst(), radix: 16)!
            return [(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF]
        }
        let token = try XCTUnwrap(Tokens.palette["accent"])
        XCTAssertEqual(rgb(.light), hexRGB(token.auroraLight), "light accent asset must be Aurora's accent token")
        XCTAssertEqual(rgb(.dark), hexRGB(token.auroraDark), "dark accent asset must be Aurora's accent token")
    }

    // MARK: - D-218: a zero-net loop is hidden, through the shipped library

    private func pid(_ n: Int) -> String { String(repeating: "0", count: 31) + String(n) }

    /// A owes B `ab`, B owes C `bc`, as expenses (the payer is the creditor).
    private func chain(_ ab: Int64, _ bc: Int64, group: String) -> [ExpenseInput] {
        [
            ExpenseInput(payer: pid(2), paidMinor: ab, currencyCode: "USD",
                         shares: [IdAmount(id: pid(1), amountMinor: ab)], group: group, conversion: nil),
            ExpenseInput(payer: pid(3), paidMinor: bc, currencyCode: "USD",
                         shares: [IdAmount(id: pid(2), amountMinor: bc)], group: group, conversion: nil),
        ]
    }

    private func line(_ expenses: [ExpenseInput], _ settlements: [SettlementInput], viewer: Int, group: String) throws -> BalanceLineOut? {
        try ffiBalanceLine(expenses: expenses, settlements: settlements, redistributions: [], removed: [],
                           viewer: pid(viewer), group: group).first
    }

    private func overview(_ expenses: [ExpenseInput], _ settlements: [SettlementInput], viewer: Int, group: String) throws -> OverviewOut {
        try ffiOverview(
            groups: [OverviewGroupIn(groupId: group, viewer: pid(viewer),
                                     members: (1...3).map { MemberWire(id: pid($0), name: ["Ann", "Bo", "Cy"][$0 - 1]) },
                                     removed: [])],
            expenses: expenses, settlements: settlements, redistributions: [])
    }

    /// L1: Settle all past a middle person (A pays C what A owed B and B owed C). Everyone
    /// is at zero, so no card and no entry may remain.
    func testASettleAllLoopLeavesNoEntriesAndNoPeopleCards() throws {
        let group = String(repeating: "9", count: 32)
        let settle = [SettlementInput(from: pid(1), to: pid(3), amountMinor: 1000, currencyCode: "USD", group: group)]
        let expenses = chain(1000, 1000, group: group)
        for viewer in 1...3 {
            let out = try XCTUnwrap(try line(expenses, settle, viewer: viewer, group: group), "viewer \(viewer)")
            XCTAssertEqual(out.viewerNetMinor, 0)
            XCTAssertEqual(out.entries, [], "viewer \(viewer) sees no debts nobody has")
        }
        let home = try overview(expenses, settle, viewer: 1, group: group)
        XCTAssertEqual(home.people, [])
        XCTAssertEqual(home.totals, [])
    }

    /// L2, the negative: a partial settle leaves a real debt, and only that one.
    func testAPartialSettleLeavesOnlyTheRealDebt() throws {
        let group = String(repeating: "9", count: 32)
        let settle = [SettlementInput(from: pid(1), to: pid(3), amountMinor: 600, currencyCode: "USD", group: group)]
        let expenses = chain(1000, 600, group: group)
        let a = try XCTUnwrap(try line(expenses, settle, viewer: 1, group: group))
        XCTAssertEqual(a.viewerNetMinor, -400)
        XCTAssertEqual(a.entries, [ShareOut(id: pid(2), minorUnits: -400)])
        let b = try XCTUnwrap(try line(expenses, settle, viewer: 2, group: group))
        XCTAssertEqual(b.viewerNetMinor, 400)
        XCTAssertEqual(b.entries, [ShareOut(id: pid(1), minorUnits: 400)])
        let c = try XCTUnwrap(try line(expenses, settle, viewer: 3, group: group))
        XCTAssertEqual(c.viewerNetMinor, 0)
        XCTAssertEqual(c.entries, [])
        let home = try overview(expenses, settle, viewer: 1, group: group)
        XCTAssertEqual(home.people.count, 1)
        XCTAssertEqual(home.people.first?.name, "Bo")
        XCTAssertEqual(home.people.first?.netMinor, -400)
        XCTAssertEqual(home.totals.map(\.amountMinor), [-400])
    }
}
