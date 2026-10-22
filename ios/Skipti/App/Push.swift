import FirebaseFirestore
import FirebaseMessaging
import SwiftUI
import UIKit
import UserNotifications

/// D-196 (the Firebase plan's unit 6): push notifications. When you add,
/// change or delete an expense in a shared group, your phone writes what the
/// push is about into the record's `notice`, and the `onGroupRecord` Function
/// sends it to the others' phones, titled with the group's name. A tap opens
/// the expense.
///
/// D-257 (G-18): the phone writes only checked fields, `kind` (added,
/// changed, deleted), `title` (the expense's note as one line, cut to 60 with
/// an ellipsis, or "an expense") and `amount` (core's string, never on a
/// delete); the Function builds the line ("Rahul added Dinner · ₹1,200.00")
/// with the author's name as the server holds it, so no phone chooses whose
/// line it reads as. Lengths are UTF-16 code units, as the rules count them.
///
/// GUARANTEES #5: nothing here reaches Google without an account. Firebase
/// Messaging's automatic start is off (Info.plist), so no token exists and no
/// request is made until the phone is signed in with sync on; signing out or
/// turning sync off deletes the token. Android's twin is Push.kt.
enum PushNotice {
    struct Notice: Equatable {
        let expense: String
        let kind: ActivityFeed.Kind
        let title: String
        let amount: String?
    }

    /// UTF-16 code units, as the rules' size() counts (they take 61 and 40).
    static let maxTitle = 60
    static let maxAmount = 40

    /// What the push for `record` is about, when it is this phone's own
    /// change to an expense in a group: a new expense, an edit, a delete. Nil
    /// for anything else (people, names, settlements, personal records, other
    /// phones' records). The amount is core's string.
    static func notice(
        _ record: RecordWire,
        thisDevice: String,
        expenses: [String: ProjectedExpenseOut],
        locale: LocaleTag
    ) -> Notice? {
        guard let groupId = record.groupId, record.authorDeviceId == thisDevice else { return nil }
        let kind: ActivityFeed.Kind
        let expenseID: String
        switch record.recordType {
        case LedgerStore.Sync.expenseType:
            kind = .added
            expenseID = "\(record.hlcMillis):\(record.hlcCounter):\(record.hlcDeviceId)"
        case LedgerStore.Sync.revisionType:
            guard let revision = try? ffiDecodeExpenseRevision(blob: record.blob) else { return nil }
            kind = revision.deleted == true ? .deleted : .changed
            expenseID = revision.target
        default:
            return nil
        }
        guard PushRoute.isExpenseID(expenseID), let expense = expenses[expenseID],
              expense.expense.group == groupId else { return nil }
        var amount: String?
        if kind != .deleted, let formatted = try? ffiMoneyFormat(
            minorUnits: expense.expense.paidMinor, currencyCode: expense.expense.currencyCode, locale: locale),
           !formatted.trimmingCharacters(in: .whitespaces).isEmpty, formatted.utf16.count <= maxAmount,
           oneLine(formatted) == formatted {
            amount = formatted
        }
        return Notice(expense: expenseID, kind: kind, title: title(expense.note), amount: amount)
    }

    /// `text` with each run of control characters and line or paragraph
    /// separators made one space (the rules refuse them in a notice).
    static func oneLine(_ text: String) -> String {
        text.replacingOccurrences(of: "[\\p{Cc}\\u2028\\u2029]+", with: " ", options: .regularExpression)
    }

    /// The note as one line, trimmed; "an expense" without one; past 60 units
    /// cut at a whole code point to 59 or fewer, then the ellipsis.
    static func title(_ note: String?) -> String {
        let line = oneLine(note ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if line.isEmpty { return ActivityFeed.anExpense }
        if line.utf16.count <= maxTitle { return line }
        var cut = String.UnicodeScalarView()
        var units = 0
        for scalar in line.unicodeScalars {
            let n = String(scalar).utf16.count
            if units + n > maxTitle - 1 { break }
            cut.append(scalar)
            units += n
        }
        return String(cut) + "…"
    }

    /// The notices for the records about to go up, by document id. Reads the
    /// ledger only when one of them could carry a notice.
    static func notices(for records: [RecordWire], store: LedgerStore) -> [String: Notice] {
        guard let device = try? LedgerStore.Sync.deviceId(store) else { return [:] }
        let candidates = records.filter {
            $0.groupId != nil && $0.authorDeviceId == device
                && ($0.recordType == LedgerStore.Sync.expenseType || $0.recordType == LedgerStore.Sync.revisionType)
        }
        guard !candidates.isEmpty, let projected = try? store.projectedExpenses() else { return [:] }
        let expenses = Dictionary(projected.expenses.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        var out: [String: Notice] = [:]
        for record in candidates {
            if let n = notice(record, thisDevice: device, expenses: expenses, locale: AppLocale.current) {
                out[CloudRecords.documentId(record)] = n
            }
        }
        return out
    }

    /// The `notice` field as the rules take it (D-257's shape).
    static func field(_ n: Notice) -> [String: String] {
        var out = ["expense": n.expense, "kind": n.kind.rawValue, "title": n.title]
        if let amount = n.amount { out["amount"] = amount }
        return out
    }
}

/// Where a tapped push goes. Only an expense in a group, named by ids of the
/// shapes the rules allow: a push's data is read, never trusted.
enum PushRoute {
    struct Target: Equatable, Identifiable {
        let groupId: String
        let expense: String
        var id: String { expense }
    }

    static func isExpenseID(_ value: String) -> Bool {
        value.range(of: "^[0-9]{1,20}:[0-9]{1,10}:[0-9a-f]{32}$", options: .regularExpression) != nil
    }

    /// D-197: an invite push (inviteUser) carries only its kind.
    static func isInvite(_ info: [AnyHashable: Any]) -> Bool { info["kind"] as? String == "invite" }

    /// D-198: a "Was this you?" push (onDeviceCreated).
    static func isDevice(_ info: [AnyHashable: Any]) -> Bool { info["kind"] as? String == "device" }

    /// D-210: a group's own push (a removal request, a decision, a role, a
    /// deletion) names its group and nothing else.
    static func groupID(_ info: [AnyHashable: Any]) -> String? {
        guard info["kind"] as? String == "group",
              let group = info["groupId"] as? String, CloudRecords.isHex32(group) else { return nil }
        return group
    }

    static func isGroup(_ info: [AnyHashable: Any]) -> Bool { groupID(info) != nil }

    static func target(_ info: [AnyHashable: Any]) -> Target? {
        guard info["kind"] as? String == "record",
              let group = info["groupId"] as? String, CloudRecords.isHex32(group),
              let expense = info["expense"] as? String, isExpenseID(expense) else { return nil }
        return Target(groupId: group, expense: expense)
    }
}

enum PushCopy {
    static let title = "Notifications"
    static let on = "On"
    static let off = "Off"
    static let explainOn = "You hear when someone adds, changes or deletes an expense in your groups."
    static let explainAsk = "Hear when someone adds, changes or deletes an expense in your groups."
    static let explainDenied = "Notifications for SKIPTI are off in your phone's settings."
    static let explainSyncOff = "Notifications come with sync. Turn sync on to hear about your groups."
    static let turnOn = "Turn on notifications"
    static let openSettings = "Open phone settings"
    static let fetching = "Bringing in this expense…"
}

/// The phone's side of push: FCM's token in this phone's device document
/// while signed in with sync on, the permission, and taps.
@MainActor
final class Push: NSObject, ObservableObject, UNUserNotificationCenterDelegate, MessagingDelegate {
    static let shared = Push()

    /// The expense a tapped push asked to open, until it is shown.
    @Published var opened: PushRoute.Target?
    /// D-197: a tapped invite push opens the invitations.
    @Published var openedInvitations = false
    /// D-198: a tapped "Was this you?" push opens Your phones.
    @Published var openedPhones = false
    /// D-210: a tapped group push (a request, a removal, a role, a deletion)
    /// opens that group's page.
    @Published var openedGroup: String?

    private var running = false
    private var token: String?
    private var savedFor: String?
    /// An owed delete in flight (`settleOwed`).
    private var settling = false

    /// At launch, before any tap can arrive: taps and pushes that arrive while
    /// SKIPTI is in front come here. Local only; nothing is sent.
    func setUp() {
        UNUserNotificationCenter.current().delegate = self
    }

    /// Signed in with sync on (CloudSync): FCM starts, the phone registers
    /// with APNs, and the token FCM returns is saved for this phone.
    func start() {
        // The emulator runs (`--emulators`, proofs) never reach FCM. D-235 round 5: nor does an unconfigured launch.
        guard !Cloud.emulator, FirebaseGate.isConfigured else { return }
        guard !running else {
            saveToken()
            return
        }
        running = true
        // D-235 round 4: the token is wanted again, so a delete still owed must not take it.
        let store = LedgerStore.defaultStore()
        if Self.mark(store, Self.deleteOwedKey) != nil { try? store.kvSet(key: Self.deleteOwedKey, value: Data()) }
        Messaging.messaging().delegate = self
        Messaging.messaging().isAutoInitEnabled = true
        UIApplication.shared.registerForRemoteNotifications()
    }

    /// D-235 round 4 (shells review I1, Android audit M1's twin): the account whose device document holds this phone's
    /// token, in the vault. Memory alone forgot it: after a launch that could not register (offline), sign-out and
    /// sync off saw no token and left it in the cloud and valid at FCM, and the account's group pushes kept coming.
    nonisolated static let registeredKey = "push/registered"
    /// FCM may still hold a token this phone no longer wants (a delete that could not finish offline); tried again at a
    /// launch that is not signed in (`settleOwed`).
    nonisolated static let deleteOwedKey = "push/delete-owed"
    /// How long a sign-out or sync off waits on each cloud call before going on (Android's `CLOUD_TIMEOUT_MS`).
    static let cloudTimeout: TimeInterval = 5

    /// A push mark's value, or nil when it is not set (an empty value is cleared).
    nonisolated static func mark(_ store: LedgerStore, _ key: String) -> String? {
        guard let data = try? store.kvGet(key: key), !data.isEmpty else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    /// D-235 final wave (I-1): whether a delete at FCM is still owed; read by the launch (`LaunchFirebase.plan`), which
    /// configures Firebase only to settle it when no account is signed in.
    nonisolated static func deleteOwed(_ store: LedgerStore) -> Bool { mark(store, deleteOwedKey) != nil }

    /// D-235 final wave (I-1): tells iOS this app wants no remote notifications. Local, no network: APNs delivers nothing
    /// to SKIPTI from then on, whatever FCM or a device document still holds. `start()` registers again at the next
    /// sign-in. A seam for the tests (the hosted suite must not change the test host's registration).
    static var unregisterRemote: () -> Void = { UIApplication.shared.unregisterForRemoteNotifications() }

    /// From the app delegate: APNs gave this phone its address; FCM turns it
    /// into a token (delivered to `messaging(_:didReceiveRegistrationToken:)`).
    func apnsToken(_ token: Data) {
        guard running else { return }
        Messaging.messaging().apnsToken = token
    }

    /// Sync turned off, or signing out: the token is removed from this
    /// phone's device document (when `inCloud` and still signed in) and
    /// deleted at FCM, so no push reaches this phone for the account.
    func stop(inCloud: Bool, store: LedgerStore = .defaultStore()) async {
        let uid = Account.shared.uid
        // D-235 round 4 (I1): what may exist, not only what this process registered.
        let plan = PushCleanup.plan(
            inCloud: inCloud, uid: uid, inMemory: token != nil || savedFor != nil,
            registeredFor: Self.mark(store, Self.registeredKey), wasRunning: running,
            deleteOwed: Self.mark(store, Self.deleteOwedKey) != nil)
        running = false
        token = nil
        savedFor = nil
        // Each cloud call is bounded: offline, a Firestore write never completes, and Sign out waited on it.
        if plan.clearCloud, let uid, let doc = device(uid) {
            if await Bounded.run(seconds: Self.cloudTimeout, { try await doc.updateData(CloudProfile.tokenUpdate(nil)) }) {
                try? store.kvSet(key: Self.registeredKey, value: Data())
            }
        }
        // D-235 final wave (I-1): whenever a token may exist at FCM, APNs stops delivering to this app now, online or
        // not. Only when push ran in this process did it before, so an offline launch's sign-out left the old account's
        // group pushes reaching a signed-out phone until the owed delete settled.
        if plan.deleteAtFcm { Self.unregisterRemote() }
        // D-235 round 5 (decision 116): never configured means no account this launch: no Messaging call (a delete
        // still owed is settled by a later launch, which configures Firebase only for that: `LaunchFirebase.plan`).
        guard FirebaseGate.isConfigured else { return }
        Messaging.messaging().isAutoInitEnabled = false
        if plan.deleteAtFcm { await deleteAtFcm(store) }
    }

    /// D-235 round 4: deletes this phone's token at FCM (a deleted token reaches no phone, whatever a device document
    /// still says). Marked as owed first, so a delete that cannot finish now is tried again (`settleOwed`).
    private func deleteAtFcm(_ store: LedgerStore) async {
        guard !running, FirebaseGate.isConfigured else { return }
        try? store.kvSet(key: Self.deleteOwedKey, value: Data("1".utf8))
        if await Bounded.run(seconds: Self.cloudTimeout, { try await Messaging.messaging().deleteToken() }) {
            try? store.kvSet(key: Self.deleteOwedKey, value: Data())
            try? store.kvSet(key: Self.registeredKey, value: Data())
        }
    }

    /// D-235 round 4: SKIPTI came to the front not signed in: a delete still owed is tried again. Never in the emulator
    /// runs, never while push runs, and nothing at all when none is owed (no FCM contact without an account).
    func settleOwed() {
        guard !Cloud.emulator, !running, !settling, FirebaseGate.isConfigured else { return }
        let store = LedgerStore.defaultStore()
        guard Self.deleteOwed(store) else { return }
        // D-235 final wave (I-1): one at a time (the launch settles, and the first return to the front asks again).
        settling = true
        Task {
            await deleteAtFcm(store)
            settling = false
        }
    }

    /// Asks once, at a moment that explains itself (inviting someone, joining
    /// a group, or the Settings row); never at launch.
    func askPermission() async {
        let center = UNUserNotificationCenter.current()
        guard await center.notificationSettings().authorizationStatus == .notDetermined else { return }
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
    }

    func permission() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    private func device(_ uid: String) -> DocumentReference? {
        guard let id = try? CloudProfile.deviceId(.defaultStore()) else { return nil }
        return Cloud.db.collection("users").document(uid).collection("devices").document(id)
    }

    /// Writes the token into this phone's device document once per token and
    /// account. The document exists once CloudProfile.register has run; until
    /// then the write fails and the next registration retries it.
    func saveToken() {
        guard running, let token, let uid = Account.shared.uid, savedFor != uid + token,
              let doc = device(uid) else { return }
        Task {
            do {
                try await doc.updateData(CloudProfile.tokenUpdate(token))
                if self.token == token { savedFor = uid + token }
                // D-235 round 4 (I1): kept in the vault, so a later launch that never registers still removes it.
                try? LedgerStore.defaultStore().kvSet(key: Self.registeredKey, value: Data(uid.utf8))
            } catch {}
        }
    }

    // MARK: - MessagingDelegate

    nonisolated func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        Task { @MainActor in
            guard self.running else { return }
            self.token = fcmToken
            self.savedFor = nil
            self.saveToken()
        }
    }

    // MARK: - UNUserNotificationCenterDelegate

    /// In front, a push still shows (another group, or this one).
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
    ) async {
        let info = response.notification.request.content.userInfo
        let target = PushRoute.target(info)
        let invite = PushRoute.isInvite(info)
        let device = PushRoute.isDevice(info)
        let group = PushRoute.groupID(info)
        await MainActor.run {
            if let target { self.opened = target }
            if invite { self.openedInvitations = true }
            if device { self.openedPhones = true }
            if let group { self.openedGroup = group }
        }
    }
}

/// D-235 round 4 (shells review I1, Android's `PushCleanup`): what sign-out or sync off removes, decided from what may
/// exist rather than from this process's memory. Pure: the tests pin it.
enum PushCleanup {
    struct Plan: Equatable {
        let clearCloud: Bool
        let deleteAtFcm: Bool
    }

    /// `inCloud`: the account may still write its device document; `uid`: the account signed in. `inMemory`: this
    /// process holds a token. `registeredFor`: the account the vault says holds this phone's token. `wasRunning`: push
    /// ran in this process. `deleteOwed`: a delete at FCM did not finish before.
    static func plan(inCloud: Bool, uid: String?, inMemory: Bool, registeredFor: String?, wasRunning: Bool,
                     deleteOwed: Bool) -> Plan {
        Plan(clearCloud: inCloud && uid != nil && (inMemory || registeredFor == uid),
             deleteAtFcm: wasRunning || inMemory || registeredFor != nil || deleteOwed)
    }
}

/// D-235 round 4 (shells review I1, Android's `Bounded`): a cloud call that may never finish (offline, a Firestore write
/// resolves only on the server's acknowledgement, and Sign out and "Making an invite" waited on one for good) gets
/// `seconds`. The call is left running when its time is up (it cannot be cancelled); the caller goes on.
@MainActor
enum Bounded {
    /// True when `op` finished in time, false when it threw or ran out of time.
    static func run(seconds: TimeInterval, _ op: @escaping @MainActor () async throws -> Void) async -> Bool {
        await value(seconds: seconds, otherwise: false) {
            do { try await op(); return true } catch { return false }
        }
    }

    /// `op`'s answer when it comes in time, else `otherwise`.
    static func value<T>(seconds: TimeInterval, otherwise: T, _ op: @escaping @MainActor () async -> T) async -> T {
        await withCheckedContinuation { (continuation: CheckedContinuation<T, Never>) in
            let once = Once()
            let work = Task { @MainActor in
                let answer = await op()
                if once.first() { continuation.resume(returning: answer) }
            }
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                if once.first() {
                    continuation.resume(returning: otherwise)
                    work.cancel()
                }
            }
        }
    }

    /// The first of the two finishers resumes; both run on the main actor.
    @MainActor
    private final class Once {
        private var done = false
        func first() -> Bool {
            defer { done = true }
            return !done
        }
    }
}

/// D-196: the app delegate, for what SwiftUI has no hook for: the push
/// delegate before launch finishes (so the tap that launched SKIPTI is not
/// lost) and APNs's address for this phone.
final class SkiptiAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // D-211: a test host starts no cloud work (see LaunchWork).
        guard LaunchWork.startsCloud() else { return true }
        MainActor.assumeIsolated { Push.shared.setUp() }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        MainActor.assumeIsolated { Push.shared.apnsToken(deviceToken) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {}
}

/// D-196: the expense a tapped push named. It may not be on this phone yet
/// (the push can beat the records), so it waits for sync to bring it in.
struct PushedExpenseView: View {
    let store: LedgerStore
    let target: PushRoute.Target
    @State private var named: NamedGroupWire?
    @State private var present = false

    var body: some View {
        Group {
            if present {
                ExpenseDetailView(store: store, expenseID: target.expense, named: named)
            } else {
                VStack(spacing: 12) {
                    ProgressView()
                    Text(verbatim: PushCopy.fetching)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.textDim)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .auroraBackground()
            }
        }
        .task(id: SyncStatus.shared.revision) { load() }
    }

    private func load() {
        guard !present else { return }
        named = ((try? store.namedGroups()) ?? []).first { $0.group.id == target.groupId }
        present = ((try? store.projectedExpenses().expenses) ?? []).contains { $0.id == target.expense }
    }
}

/// D-196: Settings' notifications row, shown with an account only.
struct NotificationsCard: View {
    let syncOn: Bool
    @State private var status: UNAuthorizationStatus = .notDetermined
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(verbatim: PushCopy.title)
                    .sectionLabel()
                    .foregroundStyle(Color(Tokens.textDim))
                Spacer()
                Text(verbatim: allowed && syncOn ? PushCopy.on : PushCopy.off)
                    .microLabelFont()
                    .foregroundStyle(Color(allowed && syncOn ? Tokens.owing : Tokens.textDim))
            }
            Text(verbatim: explanation)
                .font(TypeRole.body(14))
                .foregroundStyle(Color(Tokens.text))
            if syncOn && status == .notDetermined {
                Button(PushCopy.turnOn) {
                    Task {
                        await Push.shared.askPermission()
                        status = await Push.shared.permission()
                    }
                }
                .buttonStyle(SecondaryButtonStyle())
            } else if syncOn && status == .denied {
                Button(PushCopy.openSettings) {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
                }
                .buttonStyle(SecondaryButtonStyle())
            }
        }
        .glassCard()
        .task(id: scenePhase) { status = await Push.shared.permission() }
    }

    private var allowed: Bool { status == .authorized || status == .provisional || status == .ephemeral }

    private var explanation: String {
        if !syncOn { return PushCopy.explainSyncOff }
        if allowed { return PushCopy.explainOn }
        return status == .denied ? PushCopy.explainDenied : PushCopy.explainAsk
    }
}
