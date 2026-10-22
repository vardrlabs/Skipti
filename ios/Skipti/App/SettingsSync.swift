import Foundation
import UIKit

/// D-244 (ADR-0043): with an account, a phone's plain preferences follow the account: theme, light or dark, text size,
/// Less motion, Haptics, the app icon choice and the default currency. Each is a personal `setting` record in the
/// account's own log (core's `GroupOpIn.setting`, latest write per key); every phone applies the latest on arrival, at
/// launch, and at its first sign-in, where the account's values win and this phone fills only what the account lacks.
/// Without an account nothing is written (GUARANTEES #5); with sync off a record waits on this phone (`push` is gated by
/// `CloudSync.ready`). App lock, screen privacy, the sync switch, notifications and the device name stay per phone.
///
/// `set` is the ONE write of a synced key (a source guard in SettingsSyncTests holds it): it stores the value on this
/// phone as before, then appends the record when this install is signed in to the vault's owner. Applying an arrival
/// writes the phone only (`applyFromAccount`), never a record, so nothing echoes. Android's twin is SettingsSync.kt; its
/// SettingsSyncTest reads `synced` and `perPhone` below.
enum SettingsSync {
    /// How a value is kept on this phone; on the wire every value is a string.
    enum Kind: Equatable {
        /// A word, kept as a string (theme, mode, icon, currency).
        case word
        /// A text size step, kept as an integer, written as its decimal (`-2` to `3`).
        case step
        /// A switch, kept as a Bool, written `true` or `false`.
        case flag
    }

    /// The settings that follow the account, in this order: the wire key (permanent from D-244; the same word as this
    /// phone's UserDefaults key) and how its value is kept. Less motion and the default currency are stored only once the
    /// person chooses, so only an explicit value ever syncs (never the low-memory default, never the region's currency).
    static let synced: [(key: String, kind: Kind)] = [
        ("appearance.theme", .word),
        ("appearance.mode", .word),
        ("appearance.textSize", .step),
        ("appearance.lessMotion", .flag),
        ("appearance.haptics", .flag),
        ("appearance.icon", .word),
        ("money.default-currency", .word),
    ]

    /// Kept per phone, never synced, and ignored if one ever arrives: the app lock's timeout and screen privacy
    /// (security controls), the sync switch (GUARANTEES #5), the appearance default's settled mark and its migrated mark.
    static let perPhone: [String] = [
        "applock.timeout",
        "privacy.hide-when-captured",
        "sync/enabled",
        "appearance.modeSettled",
        "appearance.modeMigrated",
    ]

    /// The vault's record of what this phone applied: each key's winning record id (`millis:counter:device`).
    static let appliedKey = "settings/applied"
    /// Set when an arrival changed the icon wanted; cleared once the front moment applied it.
    static let iconPendingKey = "settings/icon-pending"
    /// Set once this phone caught up with the account at its first sign-in (one account per phone).
    static func seededKey(_ uid: String) -> String { "settings/seeded-" + uid }

    static func kind(of key: String) -> Kind? { synced.first { $0.key == key }?.kind }

    /// Whether this build can apply `value` for `key`: a word this build knows, a step in range, a switch, a currency core
    /// knows. Anything else waits (not marked applied) for a build that knows it; an unknown theme is never read as
    /// Aurora (`AppTheme.parse` would), nor an unknown mode as Dark.
    static func accepts(_ key: String, _ value: String) -> Bool {
        switch key {
        case AppTheme.storageKey: return AppTheme(rawValue: value) != nil
        case AppearanceMode.storageKey: return AppearanceMode(rawValue: value) != nil
        case TextSize.storageKey: return Int(value).map { TextSize.steps.contains($0) && String($0) == value } ?? false
        case MotionSetting.storageKey, HapticsSetting.storageKey: return value == "true" || value == "false"
        case AppIconChoice.storageKey: return AppIconChoice(rawValue: value) != nil
        case DefaultCurrency.storageKey: return DefaultCurrency.all.contains(value)
        default: return false
        }
    }

    static func encode(_ flag: Bool) -> String { flag ? "true" : "false" }
    static func encode(_ step: Int) -> String { String(step) }

    /// The value this phone holds for `key`, as the wire writes it; nil when nothing is stored (never chosen).
    static func local(_ key: String, defaults: UserDefaults) -> String? {
        switch kind(of: key) {
        case .word?: return defaults.string(forKey: key)
        case .step?: return (defaults.object(forKey: key) as? Int).map(encode)
        case .flag?: return (defaults.object(forKey: key) as? Bool).map(encode)
        case nil: return nil
        }
    }

    /// Writes this phone only: the typed value every reader reads (the root's `AppearanceApplier` watches the same keys,
    /// so an arrival shows at once). Never a record. A key outside `synced` writes nothing.
    static func applyFromAccount(_ key: String, _ value: String, defaults: UserDefaults) {
        switch kind(of: key) {
        case .word?: defaults.set(value, forKey: key)
        case .step?: if let step = Int(value) { defaults.set(step, forKey: key) }
        case .flag?: defaults.set(value == "true", forKey: key)
        case nil: return
        }
    }

    // MARK: - The write funnel

    /// Whether a change writes a record: this install is signed in to the vault's owner. No account, no record.
    /// D-254: and the account is approved; while it waits, a change is kept on this phone only, and the first catch-up
    /// after approval fills the account's gaps with it (`afterReceive`).
    static func writesRecord(uid: String?, owner: String?, approval: ApprovalState) -> Bool {
        Approval.gate(uid: uid, owner: owner, approval: approval) != nil
    }

    /// A record waits to go up since the last page close (`pageClosed` pushes it).
    @MainActor private static var unsent = false

    /// THE write of a synced key, from the Appearance and Your data pages.
    @MainActor static func set(_ key: String, _ value: String) {
        if write(key, value, store: .defaultStore(), defaults: .standard, uid: Account.shared.uid) { unsent = true }
    }

    @MainActor static func set(_ key: String, _ value: Bool) { set(key, encode(value)) }
    @MainActor static func set(_ key: String, _ value: Int) { set(key, encode(value)) }

    /// The funnel with its seams: stores `value` on this phone, then appends one `.setting` record when this install is
    /// signed in to the vault's owner (whatever the sync switch says: the push decides what leaves). True when a record
    /// was written. A record core refuses is not written; the phone keeps the value.
    @discardableResult
    static func write(_ key: String, _ value: String, store: LedgerStore, defaults: UserDefaults, uid: String?) -> Bool {
        guard kind(of: key) != nil else {
            assertionFailure("SettingsSync.set: \(key) is not a synced setting")
            return false
        }
        applyFromAccount(key, value, defaults: defaults)
        // M3: a mode tapped by hand is a choice from now on (D-232's migrated `system` was not).
        if key == AppearanceMode.storageKey { defaults.removeObject(forKey: AppearanceMode.migratedKey) }
        guard writesRecord(uid: uid, owner: Account.storedOwner(store), approval: Approval.cached(store, uid: uid)),
              accepts(key, value) else { return false }
        return (try? LedgerStore.Sync.appendGroupOps(store, [.setting(key: key, value: value)], placing: .none)) != nil
    }

    /// A settings page closed: what was chosen there goes up in one push (`CloudRecords.outgoing` sends only the latest
    /// of each key).
    @MainActor static func pageClosed() {
        guard unsent else { return }
        unsent = false
        CloudSync.shared.ledgerChanged()
    }

    // MARK: - The apply path

    struct Decision: Equatable {
        /// What to write on this phone, in the projection's (key) order.
        var apply: [Change]
        /// The applied record per key after this pass.
        var applied: [String: String]
    }

    struct Change: Equatable {
        let key: String
        let value: String
    }

    /// The pure decision, for each setting the account holds whose winning record this phone has not applied: a key
    /// outside `synced` is ignored (never applied, never marked); this phone's own record is marked (its value is already
    /// here); a value this build does not know is skipped and NOT marked, so a later build applies it; anything else is
    /// applied and marked. `device` is this phone's sync device id; `knows` is what this build can apply (a test stands
    /// in a later build with it).
    static func toApply(_ settings: [SettingOut], applied: [String: String], device: String,
                        knows: (String, String) -> Bool = SettingsSync.accepts) -> Decision {
        var marks = applied
        var changes: [Change] = []
        for setting in settings {
            guard kind(of: setting.key) != nil, applied[setting.key] != setting.recordId else { continue }
            if setting.deviceId == device {
                marks[setting.key] = setting.recordId
                continue
            }
            guard knows(setting.key, setting.value) else { continue }
            changes.append(Change(key: setting.key, value: setting.value))
            marks[setting.key] = setting.recordId
        }
        return Decision(apply: changes, applied: marks)
    }

    /// The first sign-in's gaps: each synced key the account does not hold that this phone has a value for (stored, so
    /// chosen), in `synced` order. The account's own values win; these fill it. A mode D-232's launch migration stored
    /// (`AppearanceMode.migratedKey` still standing) was never chosen, so it fills nothing (M3).
    static func gaps(held: Set<String>, defaults: UserDefaults) -> [Change] {
        synced.compactMap { entry in
            guard !held.contains(entry.key), let value = local(entry.key, defaults: defaults), accepts(entry.key, value)
            else { return nil }
            if entry.key == AppearanceMode.storageKey, defaults.bool(forKey: AppearanceMode.migratedKey) { return nil }
            return Change(key: entry.key, value: value)
        }
    }

    /// Whether an arrival leaves the icon to change at the front moment: it changed the theme or the icon choice, and
    /// the icon wanted is not the one shown. `shown` nil: this phone cannot change icons.
    static func iconPending(changed: [String], choice: AppIconChoice, theme: AppTheme, shown: AppIcon?) -> Bool {
        guard let shown, changed.contains(AppTheme.storageKey) || changed.contains(AppIconChoice.storageKey) else { return false }
        return AppIconPlan.change(target: AppIconPlan.target(choice, theme: theme), current: shown) != nil
    }

    static func applied(_ store: LedgerStore) throws -> [String: String] {
        guard let data = try store.kvGet(key: appliedKey), !data.isEmpty else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
    }

    private static func saveApplied(_ store: LedgerStore, _ applied: [String: String]) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try store.kvSet(key: appliedKey, value: try encoder.encode(applied))
    }

    static func isSeeded(_ store: LedgerStore, uid: String) -> Bool {
        guard let data = try? store.kvGet(key: seededKey(uid)) else { return false }
        return !data.isEmpty
    }

    /// The account signed out (`Account.signOut`, M5): this phone forgets that it caught up with the account and what
    /// it applied, and any icon change still pending, so a later sign-in runs the first sign-in's catch-up again (the
    /// account wins, the gaps are filled). The phone keeps its settings as they are. Never a record.
    static func signedOut(_ store: LedgerStore, uid: String?) {
        var marks = [appliedKey, iconPendingKey]
        for id in Set([uid, Account.storedOwner(store)].compactMap { $0 }) { marks.append(seededKey(id)) }
        for key in marks { try? store.kvSet(key: key, value: Data()) }
    }

    static func iconIsPending(_ store: LedgerStore) -> Bool {
        guard let data = try? store.kvGet(key: iconPendingKey) else { return false }
        return !data.isEmpty
    }

    /// Applies what the account holds and this phone has not applied (`toApply`), then marks the icon pending when that
    /// changed the icon wanted. Never writes a record. Returns the keys written on this phone.
    @discardableResult
    static func applyHeld(store: LedgerStore, defaults: UserDefaults, shown: AppIcon?,
                          knows: (String, String) -> Bool = SettingsSync.accepts) throws -> [String] {
        let before = try applied(store)
        let decision = toApply(try LedgerStore.Sync.projectGroups(store).settings, applied: before,
                               device: try LedgerStore.Sync.deviceId(store), knows: knows)
        for change in decision.apply { applyFromAccount(change.key, change.value, defaults: defaults) }
        if decision.applied != before { try saveApplied(store, decision.applied) }
        let changed = decision.apply.map(\.key)
        if iconPending(changed: changed, choice: AppIconChoice.parse(defaults.string(forKey: AppIconChoice.storageKey)),
                       theme: AppTheme.parse(defaults.string(forKey: AppTheme.storageKey)), shown: shown) {
            try store.kvSet(key: iconPendingKey, value: Data("1".utf8))
        }
        return changed
    }

    /// What a pass of `afterReceive` did.
    struct Outcome: Equatable {
        /// Records written to fill the account (the first sign-in's gaps).
        var filled = 0
        /// Keys written on this phone.
        var applied: [String] = []
    }

    /// The personal listener's snapshot is in the vault (`CloudSync.receive`). Signed in to the vault's owner only. Before
    /// this phone caught up with the account, the first SERVER snapshot is the first sign-in (a cache snapshot says
    /// nothing of the account: the cache is memory only): the account's values win, the gaps are filled with this
    /// phone's, and the seeded mark is set. After that, what arrived is applied.
    static func afterReceive(store: LedgerStore, defaults: UserDefaults, uid: String?, fresh: Bool, fromCache: Bool,
                             shown: AppIcon?) throws -> Outcome {
        guard let uid = InstallSession.owned(uid: uid, owner: Account.storedOwner(store)) else { return Outcome() }
        if isSeeded(store, uid: uid) {
            guard fresh else { return Outcome() }
            return Outcome(filled: 0, applied: try applyHeld(store: store, defaults: defaults, shown: shown))
        }
        guard !fromCache else { return Outcome() }
        let held = Set(try LedgerStore.Sync.projectGroups(store).settings.map(\.key))
        let fill = gaps(held: held, defaults: defaults)
        if !fill.isEmpty {
            try LedgerStore.Sync.appendGroupOps(store, fill.map { .setting(key: $0.key, value: $0.value) }, placing: .none)
        }
        let changed = try applyHeld(store: store, defaults: defaults, shown: shown)
        try store.kvSet(key: seededKey(uid), value: Data("1".utf8))
        return Outcome(filled: fill.count, applied: changed)
    }

    /// At launch, signed in to the vault's owner and caught up once: what the vault holds is applied (a value an older
    /// build skipped is applied now). Before the first catch-up the first server snapshot does it (`afterReceive`).
    @discardableResult
    static func atLaunch(store: LedgerStore, defaults: UserDefaults, uid: String?, shown: AppIcon?,
                         knows: (String, String) -> Bool = SettingsSync.accepts) throws -> [String] {
        guard let uid = InstallSession.owned(uid: uid, owner: Account.storedOwner(store)), isSeeded(store, uid: uid)
        else { return [] }
        return try applyHeld(store: store, defaults: defaults, shown: shown, knows: knows)
    }

    // MARK: - The front moment (the icon)

    /// The icon wanted only changes when SKIPTI is in front and unlocked, and only when an arrival left it pending.
    static func frontApplies(pending: Bool, locked: Bool, active: Bool) -> Bool { pending && !locked && active }

    /// The front moment with its seams: when `frontApplies`, `change` runs with the stored choice and theme; the
    /// pending mark goes once it ran (false: the app was not active after all, it waits for the next front moment).
    /// True when `change` ran.
    @discardableResult
    static func frontMoment(store: LedgerStore, defaults: UserDefaults, locked: Bool, active: Bool,
                            change: (AppIconChoice, AppTheme) -> Bool) -> Bool {
        guard frontApplies(pending: iconIsPending(store), locked: locked, active: active) else { return false }
        let ran = change(AppIconChoice.parse(defaults.string(forKey: AppIconChoice.storageKey)),
                         AppTheme.parse(defaults.string(forKey: AppTheme.storageKey)))
        if ran { try? store.kvSet(key: iconPendingKey, value: Data()) }
        return ran
    }

    /// A front moment happened; the personal listener's first server snapshot after it is one too (an arrival a
    /// second after SKIPTI came to the front).
    @MainActor private static var awaitingServer = false

    /// The icon this phone shows, nil when it cannot change icons.
    @MainActor static func shownIcon() -> AppIcon? {
        let app = UIApplication.shared
        return app.supportsAlternateIcons ? AppIcon(alternateName: app.alternateIconName) : nil
    }

    /// SKIPTI came to the front (scene phase active), was unlocked, or launched: an icon change that arrived is made
    /// now, only if SKIPTI is in front and unlocked. Never anywhere else (AppearanceTests' icon guard).
    @MainActor static func frontMoment(locked: Bool) {
        awaitingServer = true
        changeIfPending(store: .defaultStore(), locked: locked)
    }

    /// The switcher's one `applyArrived` call (AppearanceTests names it).
    @MainActor private static func changeIfPending(store: LedgerStore, locked: Bool) {
        frontMoment(store: store, defaults: .standard, locked: locked,
                    active: UIApplication.shared.applicationState == .active) { choice, theme in
            AppIconSwitcher.applyArrived(choice: choice, theme: theme)
        }
    }

    /// `CloudSync.receive`'s personal branch, after the records are in.
    @MainActor static func received(store: LedgerStore, fresh: Bool, fromCache: Bool) {
        let outcome = try? afterReceive(store: store, defaults: .standard, uid: Account.shared.uid, fresh: fresh,
                                        fromCache: fromCache, shown: shownIcon())
        if (outcome?.filled ?? 0) > 0 { CloudSync.shared.ledgerChanged() }
        guard !fromCache, awaitingServer else { return }
        awaitingServer = false
        changeIfPending(store: store, locked: AppLock.shared.locked)
    }

    /// The launch, once the vault opened and the account is attached (`SkiptiApp.loadGate`).
    @MainActor static func launched(locked: Bool, active: Bool) {
        let store = LedgerStore.defaultStore()
        _ = try? atLaunch(store: store, defaults: .standard, uid: Account.shared.uid, shown: shownIcon())
        if active { frontMoment(locked: locked) }
    }
}
