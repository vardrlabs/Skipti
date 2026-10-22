import FirebaseFirestore
import UIKit
import XCTest
@testable import Skipti

/// D-244 (ADR-0043): settings follow the account. The key table, the one write funnel, the apply decision, the first
/// sign-in, what goes up, the icon's front moment, and the source guards. Goldens are written out by hand from the
/// design (.drafts/u25/design.md sections 3 to 7), not read back from the implementation. Android's twin is
/// SettingsSyncTest.kt.
final class SettingsSyncTests: XCTestCase {
    private let other = String(repeating: "d", count: 32)
    private let uid = "uid-settings"
    private var suites: [String] = []

    override func tearDown() {
        for name in suites { UserDefaults.standard.removePersistentDomain(forName: name) }
        suites = []
        super.tearDown()
    }

    private func tempDefaults() -> UserDefaults {
        let name = "skipti-settings-\(UUID().uuidString)"
        suites.append(name)
        return UserDefaults(suiteName: name)!
    }

    private func tempStore(owner: String? = nil) throws -> LedgerStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("settingssync-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = LedgerStore(path: dir.appendingPathComponent("vault.db").path)
        if let owner {
            try store.kvSet(key: Account.ownerKey, value: Data(owner.utf8))
            // D-254: an owner here is an approved account (ApprovalTests covers the waiting one).
            Approval.setCached(store, uid: owner, .approved)
        }
        return store
    }

    /// A setting record written by another phone of the account.
    /// D-259: `raw` writes the setting's JSON by hand, as a modified client would (core's encode refuses a per-phone key).
    private func theirs(_ key: String, _ value: String, millis: UInt64, device: String? = nil, raw: Bool = false) throws -> RecordWire {
        let d = device ?? other
        let blob = raw ? Data(#"{"op":"setting","key":"\#(key)","value":"\#(value)"}"#.utf8)
            : try ffiEncodeGroupOp(op: .setting(key: key, value: value))
        return RecordWire(
            version: 1, recordType: LedgerStore.Sync.groupOpType, groupId: nil, epoch: 0, hlcMillis: millis,
            hlcCounter: 0, hlcDeviceId: d, authorDeviceId: d, nonceSession: Data([0x47, 0x52, 0x50, 0x01]), flags: 0,
            blob: blob)
    }

    private func settingRecords(_ store: LedgerStore) throws -> [(key: String, value: String)] {
        try store.allRecords().compactMap { record in
            guard record.groupId == nil, case .setting(let key, let value)? = try? ffiDecodeGroupOp(blob: record.blob) else { return nil }
            return (key, value)
        }
    }

    // MARK: - The table

    /// Design section 3: seven keys, the wire key the iOS storage key (permanent), in this order; Less motion and the
    /// default currency only once chosen. The per-phone keys are none of them.
    func testTheSyncedKeysAreTheSevenStorageKeys() {
        XCTAssertEqual(SettingsSync.synced.map { $0.key }, [
            "appearance.theme", "appearance.mode", "appearance.textSize", "appearance.lessMotion", "appearance.haptics",
            "appearance.icon", "money.default-currency",
        ])
        XCTAssertEqual(SettingsSync.synced.map { $0.key }, [
            AppTheme.storageKey, AppearanceMode.storageKey, TextSize.storageKey, MotionSetting.storageKey,
            HapticsSetting.storageKey, AppIconChoice.storageKey, DefaultCurrency.storageKey,
        ], "the wire key is the phone's own key")
        XCTAssertEqual(SettingsSync.synced.map { $0.kind }, [.word, .word, .step, .flag, .flag, .word, .word])
        XCTAssertEqual(SettingsSync.perPhone, [
            LockTimeout.storageKey, ScreenPrivacy.storageKey, CloudSync.syncEnabledKey, AppearanceMode.settledKey,
            AppearanceMode.migratedKey,
        ])
        XCTAssertEqual(SettingsSync.perPhone, ["applock.timeout", "privacy.hide-when-captured", "sync/enabled", "appearance.modeSettled",
                                               "appearance.modeMigrated"])
        for key in SettingsSync.perPhone {
            XCTAssertNil(SettingsSync.kind(of: key), "\(key) never syncs")
            XCTAssertFalse(SettingsSync.synced.map { $0.key }.contains(key))
        }
        XCTAssertEqual(SettingsSync.appliedKey, "settings/applied")
        XCTAssertEqual(SettingsSync.iconPendingKey, "settings/icon-pending")
        XCTAssertEqual(SettingsSync.seededKey("u1"), "settings/seeded-u1")
    }

    /// Design section 5: what this build can apply. Written out by hand: every known word, the steps -2 to 3 in their
    /// plain decimal, the two switch words, a currency core knows; never a near miss.
    func testWhatThisBuildCanApply() {
        let table: [(String, String, Bool)] = [
            ("appearance.theme", "aurora", true), ("appearance.theme", "norse", true), ("appearance.theme", "vardr", true),
            ("appearance.theme", "midnight", false), ("appearance.theme", "Norse", false), ("appearance.theme", "", false),
            ("appearance.mode", "system", true), ("appearance.mode", "light", true), ("appearance.mode", "dark", true),
            ("appearance.mode", "night", false), ("appearance.mode", "Dark", false),
            ("appearance.textSize", "-2", true), ("appearance.textSize", "-1", true), ("appearance.textSize", "0", true),
            ("appearance.textSize", "1", true), ("appearance.textSize", "2", true), ("appearance.textSize", "3", true),
            ("appearance.textSize", "4", false), ("appearance.textSize", "-3", false), ("appearance.textSize", "+1", false),
            ("appearance.textSize", "01", false), ("appearance.textSize", "1.0", false), ("appearance.textSize", "big", false),
            ("appearance.lessMotion", "true", true), ("appearance.lessMotion", "false", true), ("appearance.lessMotion", "1", false),
            ("appearance.lessMotion", "True", false), ("appearance.haptics", "true", true), ("appearance.haptics", "false", true),
            ("appearance.haptics", "yes", false),
            ("appearance.icon", "match", true), ("appearance.icon", "aurora", true), ("appearance.icon", "norse", true),
            ("appearance.icon", "vardr", true), ("appearance.icon", "night", false),
            ("money.default-currency", "EUR", true), ("money.default-currency", "INR", true), ("money.default-currency", "JPY", true),
            ("money.default-currency", "eur", false), ("money.default-currency", "ZZZ", false),
            ("applock.timeout", "never", false), ("privacy.hide-when-captured", "true", false), ("sync/enabled", "1", false),
        ]
        for (key, value, known) in table {
            XCTAssertEqual(SettingsSync.accepts(key, value), known, "\(key) = \(value)")
        }
    }

    // MARK: - The funnel

    /// GUARANTEES #5: without an account the value is stored on the phone and no record is written; nor for a session
    /// this vault does not own (no owner yet, another account).
    func testWithoutAnAccountNothingIsWritten() throws {
        for (owner, signedIn) in [(nil, nil), (nil, uid), (uid, nil), ("uid-other", uid)] as [(String?, String?)] {
            let store = try tempStore(owner: owner)
            let defaults = tempDefaults()
            XCTAssertFalse(SettingsSync.write("appearance.theme", "norse", store: store, defaults: defaults, uid: signedIn))
            XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "norse", "stored on the phone as before")
            XCTAssertEqual(try store.allRecords().count, 0, "no record (owner \(owner ?? "none"), signed in \(signedIn ?? "no"))")
        }
        XCTAssertFalse(SettingsSync.writesRecord(uid: nil, owner: uid, approval: .approved))
        XCTAssertFalse(SettingsSync.writesRecord(uid: uid, owner: nil, approval: .approved))
        XCTAssertTrue(SettingsSync.writesRecord(uid: uid, owner: uid, approval: .approved))
        // D-254: the third condition: an account not approved (or not heard yet) writes no record.
        for state in [ApprovalState.unknown, .pending, .denied] {
            XCTAssertFalse(SettingsSync.writesRecord(uid: uid, owner: uid, approval: state), "\(state)")
        }
    }

    /// Signed in with sync off: the record is written (it waits), and nothing is pushed: the push's gate answers no
    /// account while the switch is off, and the account once it is on.
    @MainActor
    func testSignedInWithSyncOffWritesARecordButPushesNothing() throws {
        let store = try tempStore(owner: uid)
        try CloudSync.setSyncEnabled(store, false)
        let defaults = tempDefaults()
        XCTAssertTrue(SettingsSync.write("appearance.haptics", "false", store: store, defaults: defaults, uid: uid))
        XCTAssertEqual(defaults.object(forKey: HapticsSetting.storageKey) as? Bool, false, "kept as the Bool the reader reads")
        let records = try settingRecords(store)
        XCTAssertEqual(records.map { $0.key }, ["appearance.haptics"])
        XCTAssertEqual(records.map { $0.value }, ["false"])
        XCTAssertNil(LaunchWork.signedInAccount(uid: uid, syncOn: CloudSync.syncEnabled(store), owner: Account.storedOwner(store),
                                                approval: Approval.cached(store, uid: uid), environment: [:]),
                     "sync off: push() finds no account")
        try CloudSync.setSyncEnabled(store, true)
        XCTAssertEqual(LaunchWork.signedInAccount(uid: uid, syncOn: CloudSync.syncEnabled(store), owner: Account.storedOwner(store),
                                                  approval: Approval.cached(store, uid: uid), environment: [:]), uid,
                       "the same gate with sync on (not vacuous)")
        XCTAssertNil(CloudSync.shared.ready(uid: uid), "and never in a test host")
    }

    /// Each kind is stored as its reader reads it: a word as a string, a step as an Int, a switch as a Bool.
    func testTheFunnelStoresEachKindAsItsReaderReadsIt() throws {
        let store = try tempStore(owner: uid)
        let defaults = tempDefaults()
        SettingsSync.write(TextSize.storageKey, SettingsSync.encode(-2), store: store, defaults: defaults, uid: uid)
        SettingsSync.write(MotionSetting.storageKey, SettingsSync.encode(true), store: store, defaults: defaults, uid: uid)
        SettingsSync.write(DefaultCurrency.storageKey, "EUR", store: store, defaults: defaults, uid: uid)
        XCTAssertEqual(TextSize.parse(defaults.object(forKey: TextSize.storageKey) as? Int), -2)
        XCTAssertEqual(MotionSetting.parse(stored: defaults.object(forKey: MotionSetting.storageKey) as? Bool, lowRam: false), true)
        XCTAssertEqual(DefaultCurrency.current(stored: defaults.string(forKey: DefaultCurrency.storageKey)), "EUR")
        XCTAssertEqual(try settingRecords(store).map { "\($0.key)=\($0.value)" },
                       ["appearance.textSize=-2", "appearance.lessMotion=true", "money.default-currency=EUR"])
        XCTAssertEqual(SettingsSync.local(TextSize.storageKey, defaults: defaults), "-2")
        XCTAssertEqual(SettingsSync.local(MotionSetting.storageKey, defaults: defaults), "true")
        XCTAssertNil(SettingsSync.local(HapticsSetting.storageKey, defaults: defaults), "never chosen: nothing")
    }

    // MARK: - The apply decision

    private func setting(_ key: String, _ value: String, id: String, device: String) -> SettingOut {
        SettingOut(key: key, value: value, recordId: id, deviceId: device)
    }

    /// Design section 5, pure: own record marked, not written; unknown value skipped and not marked; unknown key ignored;
    /// already applied skipped; anything else applied and marked.
    func testTheApplyDecision() {
        let me = String(repeating: "e", count: 32)
        let settings = [
            setting("appearance.haptics", "false", id: "5:0:\(other)", device: other),
            setting("appearance.icon", "norse", id: "6:0:\(me)", device: me),
            setting("appearance.mode", "night", id: "7:0:\(other)", device: other),
            setting("appearance.textSize", "3", id: "8:0:\(other)", device: other),
            setting("appearance.theme", "midnight", id: "9:0:\(other)", device: other),
            setting("applock.timeout", "never", id: "10:0:\(other)", device: other),
            setting("money.default-currency", "EUR", id: "4:0:\(other)", device: other),
        ]
        let decision = SettingsSync.toApply(settings, applied: ["money.default-currency": "4:0:\(other)"], device: me)
        XCTAssertEqual(decision.apply, [SettingsSync.Change(key: "appearance.haptics", value: "false"),
                                        SettingsSync.Change(key: "appearance.textSize", value: "3")])
        XCTAssertEqual(decision.applied, [
            "appearance.haptics": "5:0:\(other)", "appearance.icon": "6:0:\(me)", "appearance.textSize": "8:0:\(other)",
            "money.default-currency": "4:0:\(other)",
        ], "own marked; night, midnight and the lock's timeout never marked")
        // A later build that knows the words applies what this one skipped, on its next pass.
        let later = SettingsSync.toApply(settings, applied: decision.applied, device: me) { key, value in
            SettingsSync.accepts(key, value) || (key, value) == ("appearance.theme", "midnight")
        }
        XCTAssertEqual(later.apply, [SettingsSync.Change(key: "appearance.theme", value: "midnight")])
        XCTAssertEqual(later.applied["appearance.theme"], "9:0:\(other)")
        XCTAssertNil(later.applied["applock.timeout"], "an excluded key is never marked, whatever the build")
    }

    /// Applying writes the phone and never a record (no echo); the second pass does nothing.
    func testApplyingWritesNoRecord() throws {
        let store = try tempStore(owner: uid)
        let defaults = tempDefaults()
        defaults.set("aurora", forKey: AppTheme.storageKey)
        _ = try CloudRecords.apply([try theirs("appearance.theme", "norse", millis: 1_000),
                                    try theirs("appearance.textSize", "2", millis: 1_001)], store: store)
        let before = try store.allRecords().count
        let changed = try SettingsSync.applyHeld(store: store, defaults: defaults, shown: nil)
        XCTAssertEqual(changed, ["appearance.textSize", "appearance.theme"], "the projection's key order")
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "norse")
        XCTAssertEqual(defaults.object(forKey: TextSize.storageKey) as? Int, 2)
        XCTAssertEqual(try store.allRecords().count, before, "applying wrote no record")
        XCTAssertEqual(try SettingsSync.applied(store), ["appearance.theme": "1000:0:\(other)", "appearance.textSize": "1001:0:\(other)"])
        XCTAssertEqual(try SettingsSync.applyHeld(store: store, defaults: defaults, shown: nil), [], "nothing new")
        // A newer write elsewhere wins over the applied one.
        _ = try CloudRecords.apply([try theirs("appearance.theme", "vardr", millis: 2_000)], store: store)
        XCTAssertEqual(try SettingsSync.applyHeld(store: store, defaults: defaults, shown: nil), ["appearance.theme"])
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "vardr")
        // An older one arriving late changes nothing (the latest per key wins in core).
        _ = try CloudRecords.apply([try theirs("appearance.theme", "aurora", millis: 1_500)], store: store)
        XCTAssertEqual(try SettingsSync.applyHeld(store: store, defaults: defaults, shown: nil), [])
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "vardr")
    }

    /// A value this build does not know is skipped, not marked, and never read as the default (an unknown theme never
    /// turns the phone Aurora); a later launch of a build that knows it applies it.
    func testAnUnknownValueWaitsForAnUpdate() throws {
        let store = try tempStore(owner: uid)
        let defaults = tempDefaults()
        defaults.set("norse", forKey: AppTheme.storageKey)
        defaults.set(1, forKey: TextSize.storageKey)
        _ = try CloudRecords.apply([try theirs("appearance.theme", "midnight", millis: 1_000),
                                    try theirs("appearance.textSize", "7", millis: 1_001)], store: store)
        try store.kvSet(key: SettingsSync.seededKey(uid), value: Data("1".utf8))
        XCTAssertEqual(try SettingsSync.atLaunch(store: store, defaults: defaults, uid: uid, shown: nil), [])
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "norse", "never Aurora")
        XCTAssertEqual(defaults.object(forKey: TextSize.storageKey) as? Int, 1, "out of range: untouched")
        XCTAssertEqual(try SettingsSync.applied(store), [:], "not marked")
        // The update: the next launch's build knows "midnight".
        let changed = try SettingsSync.atLaunch(store: store, defaults: defaults, uid: uid, shown: nil) { key, value in
            SettingsSync.accepts(key, value) || (key, value) == ("appearance.theme", "midnight")
        }
        XCTAssertEqual(changed, ["appearance.theme"])
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "midnight")
        // Not signed in: a launch applies nothing.
        let fresh = tempDefaults()
        XCTAssertEqual(try SettingsSync.atLaunch(store: store, defaults: fresh, uid: nil, shown: nil), [])
        XCTAssertNil(fresh.string(forKey: AppTheme.storageKey))
    }

    /// The per-phone keys never sync: an arrived lock timeout (or any of them) is ignored, never marked, and the
    /// phone's own value stays; the first sign-in never writes one.
    func testTheExcludedKeysNeverSync() throws {
        let store = try tempStore(owner: uid)
        let defaults = tempDefaults()
        defaults.set("1m", forKey: LockTimeout.storageKey)
        defaults.set(true, forKey: ScreenPrivacy.storageKey)
        // D-259: core refuses a per-phone key (encode and decode), so these come as a modified client would write them.
        XCTAssertThrowsError(try ffiEncodeGroupOp(op: .setting(key: "applock.timeout", value: "never")))
        _ = try CloudRecords.apply([try theirs("applock.timeout", "never", millis: 1_000, raw: true),
                                    try theirs("privacy.hide-when-captured", "false", millis: 1_001, raw: true)], store: store)
        let projected = try LedgerStore.Sync.projectGroups(store)
        XCTAssertEqual(projected.settings.map(\.key), [], "core refuses them (D-259)")
        XCTAssertEqual(projected.rejected.count, 2, "listed as rejected")
        let outcome = try SettingsSync.afterReceive(store: store, defaults: defaults, uid: uid, fresh: true, fromCache: false, shown: nil)
        XCTAssertEqual(outcome.applied, [])
        XCTAssertEqual(defaults.string(forKey: LockTimeout.storageKey), "1m", "the lock's timeout is this phone's")
        XCTAssertEqual(defaults.object(forKey: ScreenPrivacy.storageKey) as? Bool, true)
        XCTAssertEqual(try SettingsSync.applied(store), [:])
        XCTAssertEqual(outcome.filled, 0, "nothing of this phone's per-phone settings goes up")
        SettingsSync.applyFromAccount("applock.timeout", "never", defaults: defaults)
        XCTAssertEqual(defaults.string(forKey: LockTimeout.storageKey), "1m", "the local write refuses a key outside the table")
    }

    // MARK: - The first sign-in

    /// Design section 7: the account wins; the gaps are filled with what this phone chose; never on a cache snapshot;
    /// once per account.
    func testTheFirstSignInTheAccountWinsAndTheGapsAreFilled() throws {
        let store = try tempStore(owner: uid)
        let defaults = tempDefaults()
        defaults.set("vardr", forKey: AppTheme.storageKey)
        defaults.set(true, forKey: HapticsSetting.storageKey)
        defaults.set("light", forKey: AppearanceMode.storageKey)
        defaults.set("EUR", forKey: DefaultCurrency.storageKey)
        // Less motion, text size and the icon were never chosen here.
        _ = try CloudRecords.apply([try theirs("appearance.theme", "norse", millis: 1_000),
                                    try theirs("appearance.haptics", "false", millis: 1_001)], store: store)
        let cached = try SettingsSync.afterReceive(store: store, defaults: defaults, uid: uid, fresh: true, fromCache: true, shown: nil)
        XCTAssertEqual(cached, SettingsSync.Outcome(), "a cache snapshot says nothing of the account")
        XCTAssertFalse(SettingsSync.isSeeded(store, uid: uid))
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "vardr")

        let outcome = try SettingsSync.afterReceive(store: store, defaults: defaults, uid: uid, fresh: false, fromCache: false, shown: nil)
        XCTAssertEqual(outcome.filled, 2)
        XCTAssertEqual(outcome.applied, ["appearance.haptics", "appearance.theme"])
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "norse", "the account wins")
        XCTAssertEqual(defaults.object(forKey: HapticsSetting.storageKey) as? Bool, false)
        XCTAssertEqual(defaults.string(forKey: AppearanceMode.storageKey), "light", "kept, and it fills the account")
        let mine = try settingRecords(store).filter { ["appearance.mode", "money.default-currency"].contains($0.key) }
        XCTAssertEqual(mine.map { "\($0.key)=\($0.value)" }, ["appearance.mode=light", "money.default-currency=EUR"])
        XCTAssertEqual(try settingRecords(store).count, 4, "no record for what was never chosen")
        XCTAssertTrue(SettingsSync.isSeeded(store, uid: uid))
        XCTAssertEqual(Set(try SettingsSync.applied(store).keys),
                       ["appearance.theme", "appearance.haptics", "appearance.mode", "money.default-currency"])
        // Once: the next server snapshot fills nothing again.
        let again = try SettingsSync.afterReceive(store: store, defaults: defaults, uid: uid, fresh: false, fromCache: false, shown: nil)
        XCTAssertEqual(again, SettingsSync.Outcome())
        XCTAssertEqual(try settingRecords(store).count, 4)
    }

    /// The first phone of an account fills it with everything it chose; a phone not signed in does nothing.
    func testTheFirstPhoneFillsTheAccountAndNoAccountDoesNothing() throws {
        let defaults = tempDefaults()
        defaults.set("norse", forKey: AppTheme.storageKey)
        defaults.set(false, forKey: MotionSetting.storageKey)
        defaults.set(-1, forKey: TextSize.storageKey)
        defaults.set("night", forKey: AppearanceMode.storageKey)
        let signedOut = try tempStore(owner: uid)
        XCTAssertEqual(try SettingsSync.afterReceive(store: signedOut, defaults: defaults, uid: nil, fresh: true,
                                                     fromCache: false, shown: nil), SettingsSync.Outcome())
        XCTAssertEqual(try signedOut.allRecords().count, 0)
        XCTAssertFalse(SettingsSync.isSeeded(signedOut, uid: uid))
        let store = try tempStore(owner: uid)
        let outcome = try SettingsSync.afterReceive(store: store, defaults: defaults, uid: uid, fresh: false, fromCache: false, shown: nil)
        XCTAssertEqual(outcome, SettingsSync.Outcome(filled: 3, applied: []))
        XCTAssertEqual(try settingRecords(store).map { "\($0.key)=\($0.value)" },
                       ["appearance.theme=norse", "appearance.textSize=-1", "appearance.lessMotion=false"],
                       "an explicit Less motion off syncs; a pre-D-212 night never does")
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "norse")
    }

    /// Fix wave M3: D-232's launch migration stored `system` on an install that ran before and never chose. That is
    /// this phone's own default, not a choice: the first sign-in never fills the account with it. Once the person taps
    /// a mode (any mode, `system` too), it is a choice and fills the account like any other.
    func testAMigratedModeNeverFillsTheAccountUntilItIsTapped() throws {
        let defaults = tempDefaults()
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("m3-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let vault = dir.appendingPathComponent("ledger-vault.db").path
        XCTAssertTrue(FileManager.default.createFile(atPath: vault, contents: Data([0])), "an install that ran before")
        AppearanceDefault.settle(defaults, vaultPath: vault)
        XCTAssertEqual(defaults.string(forKey: AppearanceMode.storageKey), "system")
        XCTAssertTrue(defaults.bool(forKey: AppearanceMode.migratedKey), "the migrated mark stands")
        XCTAssertEqual(SettingsSync.gaps(held: [], defaults: defaults), [], "an old install with only the settled mode fills nothing")
        let store = try tempStore(owner: uid)
        let first = try SettingsSync.afterReceive(store: store, defaults: defaults, uid: uid, fresh: false, fromCache: false, shown: nil)
        XCTAssertEqual(first, SettingsSync.Outcome(filled: 0, applied: []))
        XCTAssertEqual(try settingRecords(store).count, 0, "System never went up")
        XCTAssertEqual(defaults.string(forKey: AppearanceMode.storageKey), "system", "the phone keeps following the phone")

        // A tap before any account: still `system`, now chosen; the mark goes, and the next first sign-in fills it.
        XCTAssertFalse(SettingsSync.write(AppearanceMode.storageKey, "system", store: try tempStore(owner: uid), defaults: defaults, uid: nil))
        XCTAssertFalse(defaults.bool(forKey: AppearanceMode.migratedKey), "a real tap clears the mark")
        XCTAssertEqual(SettingsSync.gaps(held: [], defaults: defaults), [SettingsSync.Change(key: "appearance.mode", value: "system")])
        let fresh = try tempStore(owner: uid)
        XCTAssertEqual(try SettingsSync.afterReceive(store: fresh, defaults: defaults, uid: uid, fresh: false, fromCache: false,
                                                     shown: nil).filled, 1)
        XCTAssertEqual(try settingRecords(fresh).map { "\($0.key)=\($0.value)" }, ["appearance.mode=system"])

        // A new install never gets the mark (nothing stored, it reads Dark), and an arrival leaves the mark alone.
        let brandNew = tempDefaults()
        AppearanceDefault.settle(brandNew, vaultPath: dir.appendingPathComponent("none.db").path)
        XCTAssertFalse(brandNew.bool(forKey: AppearanceMode.migratedKey))
    }

    /// Fix wave M5: signing out forgets the catch-up (the seeded mark, what was applied, an icon still pending), so a
    /// later sign-in runs the first sign-in again: the account wins and the gaps are filled. The phone keeps its values.
    func testSigningOutForgetsTheCatchUp() throws {
        let store = try tempStore(owner: uid)
        let defaults = tempDefaults()
        defaults.set("vardr", forKey: AppTheme.storageKey)
        _ = try CloudRecords.apply([try theirs("appearance.theme", "norse", millis: 1_000)], store: store)
        _ = try SettingsSync.afterReceive(store: store, defaults: defaults, uid: uid, fresh: false, fromCache: false, shown: nil)
        XCTAssertTrue(SettingsSync.isSeeded(store, uid: uid))
        XCTAssertEqual(try SettingsSync.applied(store).keys.sorted(), ["appearance.theme"])
        try store.kvSet(key: SettingsSync.iconPendingKey, value: Data("1".utf8))

        SettingsSync.signedOut(store, uid: uid)
        XCTAssertFalse(SettingsSync.isSeeded(store, uid: uid), "the seeded mark is gone")
        XCTAssertEqual(try SettingsSync.applied(store), [:], "and what was applied")
        XCTAssertFalse(SettingsSync.iconIsPending(store), "and an icon still pending")
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "norse", "the phone keeps its settings")
        SettingsSync.signedOut(try tempStore(owner: uid), uid: nil)  // the owner alone names the mark; nothing throws

        // Signed out, this phone chose again; the next sign-in's first server snapshot is a first sign-in.
        defaults.set("aurora", forKey: AppTheme.storageKey)
        defaults.set(2, forKey: TextSize.storageKey)
        let again = try SettingsSync.afterReceive(store: store, defaults: defaults, uid: uid, fresh: false, fromCache: false, shown: nil)
        XCTAssertEqual(again, SettingsSync.Outcome(filled: 1, applied: ["appearance.theme"]))
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "norse", "the account wins again")
        XCTAssertTrue(SettingsSync.isSeeded(store, uid: uid))

        let account = try String(contentsOf: appDir().appendingPathComponent("Account.swift"), encoding: .utf8)
        let signOut = try XCTUnwrap(account.components(separatedBy: "    func signOut() {").dropFirst().first?
            .components(separatedBy: "\n    }\n").first)
        let forget = try XCTUnwrap(signOut.range(of: "SettingsSync.signedOut(.defaultStore(), uid: uid)"), "Account.signOut forgets")
        let cleared = try XCTUnwrap(signOut.range(of: "uid = nil"))
        XCTAssertLessThan(forget.lowerBound, cleared.lowerBound, "with the account's uid still known")
    }

    /// Fix wave I1: a setting is personal. One arriving in a GROUP's log (a member's phone, or a modified client) is
    /// refused at the door and never applied on this phone, whatever it says.
    func testASettingInAGroupLogIsRefusedAndNeverApplied() throws {
        let store = try tempStore(owner: uid)
        let defaults = tempDefaults()
        defaults.set("aurora", forKey: AppTheme.storageKey)
        try store.kvSet(key: SettingsSync.seededKey(uid), value: Data("1".utf8))
        let group = String(repeating: "1", count: 32)
        let record = RecordWire(
            version: 1, recordType: LedgerStore.Sync.groupOpType, groupId: group, epoch: 0, hlcMillis: 5_000,
            hlcCounter: 0, hlcDeviceId: other, authorDeviceId: other, nonceSession: Data([0x47, 0x52, 0x50, 0x01]), flags: 0,
            blob: try ffiEncodeGroupOp(op: .setting(key: "appearance.theme", value: "norse")))
        var document = CloudRecords.document(record, uid: "member")
        document["createdAt"] = Timestamp(seconds: 5, nanoseconds: 0)
        XCTAssertEqual(try CloudRecords.absorb([document], groupId: group, store: store).count, 0, "refused at the door")
        XCTAssertEqual(try store.allRecords().count, 0, "nothing came in")
        XCTAssertEqual(try SettingsSync.afterReceive(store: store, defaults: defaults, uid: uid, fresh: true, fromCache: false,
                                                     shown: nil), SettingsSync.Outcome())
        XCTAssertEqual(try SettingsSync.atLaunch(store: store, defaults: defaults, uid: uid, shown: nil), [])
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "aurora", "a member's setting is never applied here")
    }

    // MARK: - What goes up

    /// The avatar rule per key: only the latest of each key goes up; earlier ones are marked, never sent; keys are
    /// independent of each other and of the picture.
    func testOutgoingKeepsOnlyTheLatestOfEachKey() throws {
        let store = try tempStore(owner: uid)
        var stamp: UInt64 = 1_000
        func put(_ key: String, _ value: String) throws {
            stamp += 1
            try LedgerStore.Sync.appendGroupOps(store, [.setting(key: key, value: value)], placing: .none, nowMs: stamp)
        }
        try put("appearance.textSize", "1")
        try put("appearance.textSize", "2")
        try put("appearance.haptics", "false")
        try put("appearance.textSize", "3")
        try store.saveAvatar(.emoji("🦊"))
        let all = try store.allRecords()
        func ids(_ key: String, _ value: String) -> Set<String> {
            Set(all.filter {
                if case .setting(let k, let v)? = try? ffiDecodeGroupOp(blob: $0.blob) { return k == key && v == value }
                return false
            }.map(CloudRecords.documentId))
        }
        let older = ids("appearance.textSize", "1").union(ids("appearance.textSize", "2"))
        XCTAssertEqual(older.count, 2)
        let out = CloudRecords.outgoing(all, uploaded: [], readOnly: [], refused: [])
        XCTAssertEqual(out.superseded, older, "the earlier steps never go up")
        let sent = Set(out.send.map(CloudRecords.documentId))
        XCTAssertTrue(ids("appearance.textSize", "3").isSubset(of: sent))
        XCTAssertTrue(ids("appearance.haptics", "false").isSubset(of: sent), "another key is its own")
        XCTAssertEqual(sent.count, all.count - 2, "the picture and the rest as before")
        // The latest already up: an earlier one still waiting never goes.
        let again = CloudRecords.outgoing(all, uploaded: ids("appearance.textSize", "3"), readOnly: [], refused: [])
        XCTAssertEqual(again.superseded, older)
        XCTAssertEqual(CloudRecords.latestSettings(all), [
            "appearance.textSize": try XCTUnwrap(ids("appearance.textSize", "3").first),
            "appearance.haptics": try XCTUnwrap(ids("appearance.haptics", "false").first),
        ])
    }

    // MARK: - The icon

    /// Design section 6: an arrival leaves the icon pending only when it changed the theme or the choice and the icon
    /// wanted is not the one shown, on a phone that can change icons. Written out by hand.
    func testTheIconIsPendingOnlyWhenTheArrivalChangesTheIconWanted() {
        let rows: [([String], AppIconChoice, AppTheme, AppIcon?, Bool)] = [
            (["appearance.theme"], .match, .norse, .aurora, true),
            (["appearance.theme"], .match, .norse, .norse, false),
            (["appearance.theme"], .aurora, .norse, .aurora, false),
            (["appearance.icon"], .vardr, .aurora, .aurora, true),
            (["appearance.icon"], .vardr, .aurora, .vardr, false),
            (["appearance.haptics"], .match, .norse, .aurora, false),
            (["appearance.theme"], .match, .norse, nil, false),
            ([], .vardr, .aurora, .aurora, false),
        ]
        for (changed, choice, theme, shown, pending) in rows {
            XCTAssertEqual(SettingsSync.iconPending(changed: changed, choice: choice, theme: theme, shown: shown), pending,
                           "\(changed) \(choice) \(theme) \(String(describing: shown))")
        }
        XCTAssertTrue(SettingsSync.frontApplies(pending: true, locked: false, active: true))
        for (pending, locked, active) in [(false, false, true), (true, true, true), (true, false, false), (true, true, false)] {
            XCTAssertFalse(SettingsSync.frontApplies(pending: pending, locked: locked, active: active))
        }
    }

    /// An arrived theme with Match theme marks the icon pending; nothing changes the icon at arrival; the front moment
    /// changes it only unlocked and active, once, and a front moment with nothing pending does nothing.
    func testTheIconWaitsForTheFrontMoment() throws {
        let store = try tempStore(owner: uid)
        let defaults = tempDefaults()
        try store.kvSet(key: SettingsSync.seededKey(uid), value: Data("1".utf8))
        _ = try CloudRecords.apply([try theirs("appearance.theme", "norse", millis: 1_000)], store: store)
        var calls: [(AppIconChoice, AppTheme)] = []
        let change: (AppIconChoice, AppTheme) -> Bool = { calls.append(($0, $1)); return true }
        XCTAssertFalse(SettingsSync.frontMoment(store: store, defaults: defaults, locked: false, active: true, change: change),
                       "nothing pending: a launch or a return changes nothing")
        let outcome = try SettingsSync.afterReceive(store: store, defaults: defaults, uid: uid, fresh: true, fromCache: false, shown: .aurora)
        XCTAssertEqual(outcome.applied, ["appearance.theme"])
        XCTAssertTrue(SettingsSync.iconIsPending(store), "pending at arrival")
        XCTAssertTrue(calls.isEmpty, "never at arrival")
        XCTAssertFalse(SettingsSync.frontMoment(store: store, defaults: defaults, locked: true, active: true, change: change))
        XCTAssertFalse(SettingsSync.frontMoment(store: store, defaults: defaults, locked: false, active: false, change: change))
        XCTAssertTrue(calls.isEmpty, "locked or in the background: it waits")
        XCTAssertTrue(SettingsSync.iconIsPending(store))
        // The switcher could not run (SKIPTI not active after all): it stays pending.
        XCTAssertFalse(SettingsSync.frontMoment(store: store, defaults: defaults, locked: false, active: true) { _, _ in false })
        XCTAssertTrue(SettingsSync.iconIsPending(store))
        XCTAssertTrue(SettingsSync.frontMoment(store: store, defaults: defaults, locked: false, active: true, change: change))
        XCTAssertEqual(calls.map { "\($0.0.rawValue)/\($0.1.rawValue)" }, ["match/norse"], "the stored choice and the arrived theme")
        XCTAssertFalse(SettingsSync.iconIsPending(store), "done")
        XCTAssertFalse(SettingsSync.frontMoment(store: store, defaults: defaults, locked: false, active: true, change: change))
        XCTAssertEqual(calls.count, 1, "once")
    }

    // MARK: - Source guards

    private func appDir() -> URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Skipti/App")
    }

    private func appSources() throws -> [(name: String, text: String)] {
        let walker = try XCTUnwrap(FileManager.default.enumerator(at: appDir(), includingPropertiesForKeys: nil))
        var files: [(name: String, text: String)] = []
        for case let url as URL in walker where url.pathExtension == "swift" {
            files.append((url.lastPathComponent, try String(contentsOf: url, encoding: .utf8)))
        }
        XCTAssertGreaterThan(files.count, 40, "the walk found the app's files")
        return files.sorted { $0.name < $1.name }
    }

    private static let keyExpressions = [
        "AppTheme.storageKey", "AppearanceMode.storageKey", "TextSize.storageKey", "MotionSetting.storageKey",
        "HapticsSetting.storageKey", "AppIconChoice.storageKey", "DefaultCurrency.storageKey",
    ]

    /// Every write of a synced key outside the funnel in `text`: a UserDefaults write naming one, an assignment to (or a
    /// binding of) an `@AppStorage` property on one inside its own type. `allowed`: exact lines that may write.
    static func strayWrites(_ text: String, allowed: Set<String> = []) -> [String] {
        var found: [String] = []
        let keys = keyExpressions + SettingsSync.synced.map { "\"\($0.key)\"" }
        for line in text.components(separatedBy: "\n") where !allowed.contains(line.trimmingCharacters(in: .whitespaces)) {
            // The funnel's own calls are the way in, not a stray write.
            let code = (line.components(separatedBy: "//").first ?? line).replacingOccurrences(of: "SettingsSync.set(", with: "")
            for verb in [".set(", ".setValue(", ".removeObject(forKey:"] where code.contains(verb) {
                if keys.contains(where: { code.contains($0) }) { found.append(line.trimmingCharacters(in: .whitespaces)) }
            }
        }
        let declaration = try! NSRegularExpression(pattern: #"@AppStorage\(([A-Za-z.]+|"[^"]+")\) (?:private )?var (\w+)"#)
        let ns = text as NSString
        for match in declaration.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            let key = ns.substring(with: match.range(at: 1))
            guard keys.contains(key) else { continue }
            let name = ns.substring(with: match.range(at: 2))
            // The type that holds it: from the last top-level declaration before it to its closing brace.
            let start = ["\nstruct ", "\nprivate struct ", "\nfinal class ", "\nclass "].map {
                ns.range(of: $0, options: .backwards, range: NSRange(location: 0, length: match.range.location)).location
            }.filter { $0 != NSNotFound }.max() ?? 0
            let rest = ns.substring(from: start)
            let body = rest.components(separatedBy: "\n}\n").first ?? rest
            let assign = try! NSRegularExpression(pattern: #"(?<![\w.$"])"# + name + #"\s*(=(?!=)|\+=|-=)"#)
            let bound = try! NSRegularExpression(pattern: #"\$"# + name + #"\b"#)
            for line in body.components(separatedBy: "\n") where !line.contains("@AppStorage(") {
                let code = line.components(separatedBy: "//").first ?? line
                let range = NSRange(location: 0, length: (code as NSString).length)
                if assign.firstMatch(in: code, range: range) != nil || bound.firstMatch(in: code, range: range) != nil {
                    found.append(line.trimmingCharacters(in: .whitespaces))
                }
            }
        }
        return found
    }

    /// The one write: no app file writes a synced key but SettingsSync.swift (the funnel and the arrival's local write),
    /// except D-232's once-per-install mode migration (a phone's own default, before the vault exists; never a choice).
    func testTheFunnelIsTheOnlyWriteOfASyncedKey() throws {
        let allowed: Set<String> = ["defaults.set(value, forKey: AppearanceMode.storageKey)"]
        var stray: [String: [String]] = [:]
        var stores = 0
        for file in try appSources() where file.name != "SettingsSync.swift" {
            let hits = Self.strayWrites(file.text, allowed: allowed)
            if !hits.isEmpty { stray[file.name] = hits }
            stores += file.text.components(separatedBy: "@AppStorage(").count - 1
        }
        XCTAssertEqual(stray, [:], "a synced setting written past SettingsSync.set")
        XCTAssertGreaterThan(stores, 15, "the walk saw the app's stored properties")
        XCTAssertTrue(try String(contentsOf: appDir().appendingPathComponent("Appearance.swift"), encoding: .utf8)
            .contains("            defaults.set(value, forKey: AppearanceMode.storageKey)\n"), "the one allowed line is where it was")
        // The detector is not vacuous: each way of writing past the funnel is caught.
        let samples = [
            "struct A: View {\n    @AppStorage(AppTheme.storageKey) private var themeRaw = \"aurora\"\n    func f() { themeRaw = \"norse\" }\n}\n",
            "struct A: View {\n    @AppStorage(TextSize.storageKey) private var textStep = 0\n    var body: some View { S(step: $textStep) }\n}\n",
            "struct A: View {\n    @AppStorage(\"appearance.haptics\") private var on = true\n    func f() { on = false }\n}\n",
            "func f() { UserDefaults.standard.set(\"EUR\", forKey: DefaultCurrency.storageKey) }\n",
            "func f() { defaults.removeObject(forKey: MotionSetting.storageKey) }\n",
        ]
        for sample in samples { XCTAssertEqual(Self.strayWrites(sample).count, 1, sample) }
        // A read, a comparison and a per-phone key are not writes.
        let fine = "struct A: View {\n    @AppStorage(AppTheme.storageKey) private var themeRaw = \"aurora\"\n    @AppStorage(LockTimeout.storageKey) private var lockRaw = \"\"\n    func f() -> Bool { lockRaw = \"x\"; return themeRaw == \"norse\" }\n}\n"
        XCTAssertEqual(Self.strayWrites(fine), [])
        // The literals live only where they are defined (each enum's storageKey) and in the table.
        let others = try appSources().filter { $0.name != "SettingsSync.swift" }.map(\.text).joined(separator: "\n")
        for entry in SettingsSync.synced {
            XCTAssertEqual(others.components(separatedBy: "static let storageKey = \"\(entry.key)\"").count - 1, 1, entry.key)
            XCTAssertEqual(others.components(separatedBy: "\"\(entry.key)\"").count - 1, 1, "\(entry.key) named once, in its enum")
        }
    }

    /// Where the account's settings are read and applied: on arrival (the personal branch of `CloudSync.receive`, after
    /// the records are in), at launch (`loadGate`, after the cloud's start), and the front moment at the scene's return
    /// and the unlock. The apply path never writes a record.
    func testTheApplyPathsAreWhereTheDesignSays() throws {
        let files = try appSources()
        let text = Dictionary(uniqueKeysWithValues: files.map { ($0.name, $0.text) })
        let cloud = try XCTUnwrap(text["CloudSync.swift"])
        let receive = try XCTUnwrap(cloud.components(separatedBy: "    func receive(_ documents: [[String: Any]], groupId: String?, store: LedgerStore, fromCache: Bool) {").dropFirst().first?
            .components(separatedBy: "\n    }\n").first)
        let absorb = try XCTUnwrap(receive.range(of: "guard let fresh = try? CloudRecords.absorb("))
        let hook = try XCTUnwrap(receive.range(of: "if groupId == nil { SettingsSync.received(store: store, fresh: !fresh.isEmpty, fromCache: fromCache) }"))
        XCTAssertLessThan(absorb.lowerBound, hook.lowerBound, "after the records are in")
        func callers(_ needle: String) -> [String: Int] {
            var out: [String: Int] = [:]
            for file in files {
                let n = file.text.components(separatedBy: needle).count - 1
                if n > 0 { out[file.name] = n }
            }
            return out
        }
        XCTAssertEqual(callers("SettingsSync.received("), ["CloudSync.swift": 1])
        XCTAssertEqual(callers("SettingsSync.launched("), ["SkiptiApp.swift": 1])
        XCTAssertEqual(callers("SettingsSync.frontMoment(locked:"), ["SkiptiApp.swift": 1], "one helper in the app")
        XCTAssertEqual(callers("settingsFrontMoment()"), ["SkiptiApp.swift": 3], "the helper, the scene's return and the unlock")
        // D-245 (U9, decision 160): the welcome's Pick a look writes the theme through the same funnel and its close is
        // Appearance's close (`WelcomeLook.choose` / `WelcomeLook.closed`), one site each.
        XCTAssertEqual(callers("SettingsSync.pageClosed()"), ["AccountHubScreen.swift": 1, "Welcome.swift": 1])
        XCTAssertEqual(callers("SettingsSync.set("), ["AccountHubScreen.swift": 8, "Welcome.swift": 1],
                       "theme, mode, text size, Haptics, Less motion, the icon's switch and rows, the currency, and the welcome's theme tile")
        let app = try XCTUnwrap(text["SkiptiApp.swift"])
        let gate = try XCTUnwrap(app.components(separatedBy: "private func loadGate(retry: Bool = false) {").dropFirst().first)
        let start = try XCTUnwrap(gate.range(of: "LaunchFirebase.run("))
        let launched = try XCTUnwrap(gate.range(of: "SettingsSync.launched(locked: lock.locked, active: scenePhase == .active)"))
        XCTAssertLessThan(start.lowerBound, launched.lowerBound, "after the account is attached")
        XCTAssertTrue(app.contains(".onChange(of: lockOpen) { _, open in\n                    if open, scenePhase == .active { settingsFrontMoment() }"))
        XCTAssertTrue(app.contains("private var lockOpen: Bool { !lock.locked }"))
        let helper = try XCTUnwrap(app.components(separatedBy: "private func settingsFrontMoment() {").dropFirst().first?
            .components(separatedBy: "\n    }\n").first)
        XCTAssertTrue(helper.contains("guard !verificationArg, onboardingComplete == true, LaunchWork.startsCloud() else { return }"),
                      "the vault open, never a proof launch or the test host")
        XCTAssertTrue(helper.contains("SettingsSync.frontMoment(locked: lock.locked)"), "the lock's state decides")
        // The apply path writes the phone only.
        let sync = try XCTUnwrap(text["SettingsSync.swift"])
        for function in ["static func applyFromAccount(", "static func applyHeld(", "static func toApply(", "static func atLaunch(",
                         "static func frontMoment(store: LedgerStore,"] {
            let body = try XCTUnwrap(sync.components(separatedBy: function).dropFirst().first?.components(separatedBy: "\n    }\n").first, function)
            XCTAssertFalse(body.contains("appendGroupOps"), "\(function) never writes a record")
        }
        XCTAssertEqual(sync.components(separatedBy: "appendGroupOps(").count - 1, 2, "the funnel and the first sign-in's gaps")
    }
}
