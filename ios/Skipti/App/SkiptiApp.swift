import FirebaseAuth
import SwiftUI

/// D-211: what the launch path may start. The hosted XCTest suite launches the
/// real app as its test host, on a simulator that may be signed in to a real
/// account, so a test host starts NO cloud work at launch: no CloudSync (and
/// so no registration, listeners, profile share or backfill) and no push
/// registration. Tests exercise those components directly, through their own
/// seams. The real app is unchanged.
enum LaunchWork {
    /// The environment variable XCTest sets in every process it hosts.
    static let testHostKey = "XCTestConfigurationFilePath"

    /// True when the process is an XCTest host.
    static func isTestHost(environment: [String: String] = ProcessInfo.processInfo.environment) -> Bool {
        environment[testHostKey] != nil
    }

    /// Whether the launch path may start cloud work (CloudSync, push).
    static func startsCloud(environment: [String: String] = ProcessInfo.processInfo.environment) -> Bool {
        !isTestHost(environment: environment)
    }

    /// The account cloud work may act as: the signed-in account when sync is
    /// on. Never, in a test host: the simulator a hosted run uses can be a
    /// clone of one signed in to a real (production) account, and a pass of
    /// the profile share or a push would reach it. `ProfileShare.realAccount`
    /// and `CloudSync.ready` both answer through this. D-235 (C2, decision
    /// 107): and only when it is the vault's `owner` (`InstallSession.owned`).
    static func signedInAccount(
        uid: String?, syncOn: Bool, owner: String?, environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> String? {
        guard startsCloud(environment: environment), syncOn else { return nil }
        return InstallSession.owned(uid: uid, owner: owner)
    }
}

/// D-235 round 5 (decision 116): whether a launch configures Firebase: only when the vault names its owner (an install
/// that has had an account). No owner, or a vault that could not be read: no Firebase call this launch.
/// Decision 117: and only while this install has not signed out since (`Account.signedInMark`: false after any
/// sign-out; nil, never written, configures so an install from before this build keeps its session).
/// D-235 final wave (I-1): a signed-out install whose sign-out could not delete its push token at FCM (offline) still
/// owes that delete; it configures only to settle it (`Plan.settleOwed`: no account attached, no sync, no listener, no
/// registration). Without that, decision 117 never configured again and the old account's pushes kept their token.
enum LaunchFirebase {
    static func configuresAtLaunch(_ owner: InstallSession.OwnerRead, signedIn: Bool?) -> Bool {
        guard signedIn != false, case .read(let stored?) = owner else { return false }
        return !stored.isEmpty
    }

    enum Plan: Equatable {
        /// No Firebase call this launch.
        case none
        /// The account's launch: configure, attach the account, start the cloud work.
        case account
        /// Signed out with a token delete still owed: configure only to settle it.
        case settleOwed
    }

    /// `deleteOwed`: `Push.deleteOwed` (a delete at FCM that did not finish). Only an install with an owner (that had an
    /// account) can owe one; a vault that could not be read configures nothing (fail closed).
    static func plan(_ owner: InstallSession.OwnerRead, signedIn: Bool?, deleteOwed: Bool) -> Plan {
        if configuresAtLaunch(owner, signedIn: signedIn) { return .account }
        guard deleteOwed, case .read(let stored?) = owner, !stored.isEmpty else { return .none }
        return .settleOwed
    }

    /// What the launch does with `plan` once the vault opened. `active`: SKIPTI is in front (its first activation came
    /// before the vault opened, so the work it could not start starts here). `configure(attach)`: the gate.
    static func run(_ plan: Plan, active: Bool, configure: (_ attach: Bool) -> Void, settle: () -> Void, sync: () -> Void) {
        switch plan {
        case .none:
            return
        case .account:
            configure(true)
            if active { sync() }
        case .settleOwed:
            configure(false)
            // Inactive: the next return to the front settles it (`CloudSync.appIsActive`, no one signed in).
            if active { settle() }
        }
    }
}

/// The app root. A first launch goes straight into the app (D-175); D-192
/// removed the Recovery Kit with the relay, so there is no kit step at all.
///
/// Launch args (D-52/D-53): --balance/--settle/--add/--list-vault and the
/// -vault variants are HEADLESS VERIFICATION paths and bypass the gate:
/// they are not user flows.
@main
struct SkiptiApp: App {
    /// D-196: push needs an app delegate (APNs's address, early taps).
    @UIApplicationDelegateAdaptor(SkiptiAppDelegate.self) private var appDelegate
    @ObservedObject private var push = Push.shared
    /// D-198: signed out from elsewhere, or a deletion asked for.
    @ObservedObject private var remote = RemoteSignOut.shared
    /// D-191: Firebase before anything can ask for the account. D-232: the appearance default is settled first of all,
    /// before anything can read the mode or open (and so create) the vault.
    init() {
        // D-232 (decision 74): settle stays FIRST. It asks whether the vault file exists; anything that opens the vault
        // before it (here, or in a stored property's initialiser above or below, which Swift runs before this body)
        // creates the file and turns every new install from Dark to System. AppearanceTests holds both.
        AppearanceDefault.settle()
        // D-235 round 5 (decision 116): Firebase is NOT configured here. It is configured once the vault says this
        // install has an account (`loadGate`) or at a sign-in tap (`FirebaseGate`); a launch with no account makes no
        // Firebase call at all (Messaging's start looked up www.google.com at every launch).
        // D-235 (C2, decision 107): a session the keychain kept through an uninstall is marked for sign-out on this
        // install's first launch; the sign-out runs right after the first configure, before anything can sync. Only
        // whether the vault file exists is asked (D-232's rule above: nothing here opens it).
        if LaunchWork.startsCloud() { InstallSession.settle() }
        // D-235 (C4, iOS M1): an export left in tmp by an earlier run (shared, or the app ended first) goes now.
        _ = try? Exporter.removeExports()
        #if DEBUG
        // D-194: `--emulators` runs the ordinary app (not a proof screen)
        // against the local Auth and Firestore emulators. Debug builds only.
        if ProcessInfo.processInfo.arguments.contains("--emulators") {
            // D-235 round 5: Auth is pointed at the emulator when the gate configures (never configured here).
            Cloud.emulator = true
            // D-210: `--emulator-as <sub>` signs in to the Auth emulator as
            // that subject first (a look with several accounts), then the
            // ordinary app runs.
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "--emulator-as"), args.indices.contains(i + 1) {
                let sub = args[i + 1]
                Task { @MainActor in await EmulatorSignIn.signIn(as: sub) }
            }
        }
        #endif
    }

    @State private var onboardingComplete: Bool?
    /// D-127: set when the vault exists but will not open. Never a
    /// toast: it gets a screen. D-225 T6 (A9): the screen offers "Try again"
    /// first, and `vaultNote` is what the last attempt there left.
    @State private var vaultError: String?
    @State private var vaultNote: VaultRecovery.Note?
    /// D-225 fix wave: one recovery attempt at a time (Try again, the erase).
    @State private var vaultAttempts = VaultRecovery.Attempts()
    /// D-184: a finished erase, until the notice saying so is dismissed.
    @State private var erasedNotice = false
    @State private var eraseGeneration = 0
    @Environment(\.scenePhase) private var scenePhase
    /// D-163: the app lock (SPEC §3.7).
    @ObservedObject private var lock = AppLock.shared
    /// D-164: screen recording and mirroring, and the setting that hides the
    /// ledger while they run (off by default).
    @ObservedObject private var capture = CaptureWatch.shared
    @AppStorage(ScreenPrivacy.storageKey) private var hideWhenCaptured = false
    /// D-159/D-195: an invite link the system handed the app.
    @State private var openedInvite: OpenedInvite?
    /// D-245 (U9, decision 177): the vault's `welcome/pending` as this launch read it; the cover while it shows; and the
    /// way it was closed, for after the cover has gone.
    @State private var welcomePending = false
    @State private var welcomeShown = false
    @State private var welcomeExit: WelcomeExit?

    struct OpenedInvite: Identifiable {
        let link: String
        var id: String { link }
    }

    /// D-210: the group a tapped push named.
    struct OpenedGroup: Identifiable {
        let id: String
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if let vaultError, !verificationArg {
                    // D-127: a vault that will not open gets its own honest
                    // screen. It used to surface as "Could not store the
                    // kit. Confirm again." under the kit screen: advice
                    // that could never work, on a screen the user could
                    // never leave. D-225 T6 (A9): Try again re-reads it.
                    VaultRecoveryView(reason: vaultError, note: vaultNote, busy: vaultAttempts.busy,
                                      onRetry: { loadGate(retry: true) }, onReset: { resetVault() })
                } else if onboardingComplete == true || verificationArg {
                    // D-184: a new identity after an erase, so no screen of
                    // the erased ledger (Settings, where the erase was) stays
                    // open under the notice; Android returns to Balance too.
                    root.id(eraseGeneration)
                } else {
                    // Flag not yet read from the vault: one tick.
                    Text("…")
                        .font(TypeRole.body())
                        .background(Tokens.bg)
                }
            }
            .task { loadGate() }
            // D-168: a deliberate erase: back to setup, nothing locked.
            .onReceive(NotificationCenter.default.publisher(for: .skiptiLedgerErased)) { _ in
                vaultError = nil
                loadGate()
                eraseGeneration += 1
                erasedNotice = true
            }
            // D-184: the erase is done, and the app says so. D-224 (Design 7, 8): as the big screen, the check over the
            // root, closed by the same one tap (OK) the alert had.
            .fullScreenCover(isPresented: $erasedNotice) {
                ErasedScreen { erasedNotice = false }
            }
            // D-245 (U9): the welcome covers the app once the vault opened and the lock (on a relaunch) is open, and after
            // an erase's notice. Once shown it stays until closed (a lock that comes back draws its curtain over it).
            .onChange(of: welcomeReady, initial: true) { _, ready in
                if ready { welcomeShown = true }
            }
            .fullScreenCover(isPresented: $welcomeShown, onDismiss: {
                WelcomeLook.after(welcomeExit)
                welcomeExit = nil
            }) {
                WelcomeView(store: LedgerStore.defaultStore(), mode: .firstRun) { exit in
                    welcomeExit = exit
                    welcomePending = false
                    WelcomeGate.closed(mode: .firstRun) { try WelcomeGate.clear(LedgerStore.defaultStore()) }
                    welcomeShown = false
                }
            }
                // D-192: coming to the front and leaving reach the cloud
                // sync (Firestore listeners from unit 4; nothing yet).
                .onChange(of: scenePhase) { _, phase in
                    // D-211: a test host (the hosted XCTest run) starts no cloud
                    // work; the tests reach the cloud components through their
                    // own seams.
                    if !verificationArg && LaunchWork.startsCloud() { CloudSync.shared.appIsActive(phase == .active) }
                    // D-247 (decision 182): a settle held for Undo lands as SKIPTI leaves the front (inactive too: the
                    // app switcher, where a swipe ends the app with no further word).
                    if phase != .active { SettleHold.shared.commit() }
                    // D-163: time away decides whether it locks again.
                    if !verificationArg {
                        if phase == .background { lock.wentToBackground() }
                        if phase == .active { lock.cameToFront() }
                    }
                    // D-235: the curtain from the state this handler just set (cameToFront may have locked).
                    updateCurtain()
                    // D-244 (ADR-0043): the front moment, after the lock's decision above: an icon change that arrived
                    // from the account is made now, only if it is pending and SKIPTI is unlocked.
                    if phase == .active { settingsFrontMoment() }
                }
                // D-163, D-235 (C1, decision 106): the lock screen while locked, and a plain cover whenever SKIPTI is
                // not in front (the app switcher's snapshot), drawn in their own window above every sheet and alert
                // (`LockCurtain`). Proof launches are not user flows and skip both.
                .onChange(of: curtainInputs, initial: true) { _, _ in updateCurtain() }
                // D-244 (ADR-0043): opening the lock is a front moment too (the arrived icon change waited for it).
                .onChange(of: lockOpen) { _, open in
                    if open, scenePhase == .active { settingsFrontMoment() }
                }
                // D-159/D-195: an invite link opened from outside (a scanned QR,
                // a message) comes here once the domain's app links are live.
                .onOpenURL { url in
                    if InviteLink.token(url.absoluteString) != nil {
                        openedInvite = OpenedInvite(link: url.absoluteString)
                    }
                }
                // D-196: a tapped push opens its expense, never over the lock.
                .sheet(item: Binding(
                    get: { held || verificationArg ? nil : push.opened },
                    // D-235 (C4, iOS L2): the lock taking the sheet down keeps the tapped push for after the unlock.
                    set: { if !held { push.opened = $0 } }
                )) { target in
                    NavigationStack {
                        PushedExpenseView(store: LedgerStore.defaultStore(), target: target)
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    Button("Close") { push.opened = nil }.barButtonText().barTint()
                                }
                            }
                    }
                }
                // D-210: a tapped group push opens that group's page.
                .sheet(item: Binding(
                    get: { held || verificationArg ? nil : push.openedGroup.map(OpenedGroup.init) },
                    set: { if $0 == nil && !held { push.openedGroup = nil } }
                )) { opened in
                    NavigationStack {
                        GroupPageScreen(groupID: opened.id)
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    Button("Close") { push.openedGroup = nil }.barButtonText().barTint()
                                }
                            }
                    }
                }
                // D-198: a tapped "Was this you?" push opens Your phones.
                .sheet(isPresented: Binding(
                    get: { !held && !verificationArg && push.openedPhones },
                    set: { if !held { push.openedPhones = $0 } }
                )) {
                    NavigationStack {
                        PhonesScreen()
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    Button("Close") { push.openedPhones = false }.barButtonText().barTint()
                                }
                            }
                    }
                }
                .alert(PhonesCopy.remoteTitle, isPresented: $remote.notice) {
                    Button(PhonesCopy.ok, role: .cancel) {}
                } message: { Text(verbatim: PhonesCopy.remoteBody) }
                .alert(PhonesCopy.deleteDoneTitle, isPresented: Binding(
                    get: { remote.deletionDate != nil }, set: { if !$0 { remote.deletionDate = nil } }
                )) {
                    Button(PhonesCopy.ok, role: .cancel) {}
                } message: { Text(verbatim: PhonesCopy.deleteDone(Phones.day(remote.deletionDate ?? Date()))) }
                // D-197: a tapped invite push opens the invitations.
                .sheet(isPresented: Binding(
                    get: { !held && !verificationArg && push.openedInvitations },
                    set: { if !held { push.openedInvitations = $0 } }
                )) {
                    NavigationStack {
                        InvitationsScreen(store: LedgerStore.defaultStore()) { push.openedInvitations = false }
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    Button("Close") { push.openedInvitations = false }.barButtonText().barTint()
                                }
                            }
                    }
                }
                // D-235 (C1): an invite link opened while locked is held until the lock is opened; the lock taking
                // the sheet down never drops it (L2), only the person closing it does.
                .sheet(item: Binding(
                    get: { held || verificationArg ? nil : openedInvite },
                    set: { if $0 == nil && !held { openedInvite = nil } }
                )) { invite in
                    NavigationStack {
                        // D-217: Cancel is "Not now", shown by JoinScreen only before a join starts.
                        JoinScreen(store: LedgerStore.defaultStore(), link: invite.link, onJoined: { _ in
                            openedInvite = nil
                        }, onCancel: { openedInvite = nil })
                    }
                }
                // D-144: the person's System / Light / Dark / Night choice,
                // on every window, from the first screen.
                .appliesStoredAppearance()
        }
    }

    /// D-235 round 4 (shells review M2): a tapped push or an opened link waits for the lock's first decision (the vault
    /// opens, then `lock.launch()` runs, then `onboardingComplete`), and then for the unlock: a cold launch from one
    /// never shows it before the lock exists.
    private var held: Bool { onboardingComplete != true || lock.locked }

    /// D-245 (U9): the welcome may cover the app now: pending, the vault open, the lock open, no erase notice up, no proof.
    private var welcomeReady: Bool {
        welcomePending && !held && vaultError == nil && !erasedNotice && !verificationArg
    }

    /// D-235 (C1): what the curtain is drawn from.
    private struct CurtainInputs: Equatable {
        let enabled: Bool
        let locked: Bool
        let active: Bool
        let captureCovers: Bool
    }

    private var curtainInputs: CurtainInputs {
        CurtainInputs(enabled: !verificationArg && onboardingComplete == true && vaultError == nil, locked: lock.locked,
                      active: scenePhase == .active,
                      captureCovers: ScreenPrivacy.covers(enabled: hideWhenCaptured, captured: capture.captured))
    }

    private func updateCurtain() {
        let inputs = curtainInputs
        LockCurtain.shared.update(enabled: inputs.enabled, locked: inputs.locked, active: inputs.active,
                                  captureCovers: inputs.captureCovers)
    }

    /// D-244 (ADR-0043): the lock is open (its opening is a front moment for an arrived icon change).
    private var lockOpen: Bool { !lock.locked }

    /// D-244 (ADR-0043): SKIPTI came to the front or was unlocked, the vault open: an icon change that arrived from the
    /// account and is still pending is made now, unless the lock is up (`SettingsSync.frontMoment` decides). Proof launches
    /// and the test host never.
    private func settingsFrontMoment() {
        guard !verificationArg, onboardingComplete == true, LaunchWork.startsCloud() else { return }
        SettingsSync.frontMoment(locked: lock.locked)
    }

    /// The headless verification args bypass the gate: they are not user
    /// flows. Recorded in BUILD-LOG §3. D-235 (C4, iOS L1, decision 108): Debug
    /// builds only, as Android's `ProofArgs` are debuggable-only; a Release build
    /// has none, so it never bypasses the lock or reads the vault for a proof.
    private var verificationArg: Bool {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        // D-192: the relay's proofs went with the relay.
        return ["--balance", "--balance-vault", "--settle", "--settle-vault",
                "--add", "--list-vault",
                "--import-proof", "--export-proof", "--export", "--smoke",
                "--expense-conflict", "--groups-proof", "--auth-emulator-proof",
                "--sync-emulator-proof", "--two-account-proof", "--settings-emulator-proof"].contains { args.contains($0) }
        #else
        return false
        #endif
    }

    /// The launch argument that follows `flag`, if any.
    private func argument(after flag: String) -> String? {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: flag), args.indices.contains(index + 1) else { return nil }
        return args[index + 1]
    }

    /// D-128: a person gets the product shell; a proof arg gets its
    /// surface. Until this unit both paths ended at `SmokeScreenView`, so
    /// finishing the Recovery Kit landed on a developer menu.
    @ViewBuilder
    private var root: some View {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--auth-emulator-proof") {
            // D-191: a Firebase sign-in against the local Auth emulator.
            AuthEmulatorProofScreen()
        } else if ProcessInfo.processInfo.arguments.contains("--sync-emulator-proof") {
            // D-194: two phones of one account converge through Firestore.
            SyncEmulatorProofScreen()
        } else if ProcessInfo.processInfo.arguments.contains("--two-account-proof") {
            // D-195: two accounts, one group, through an invite.
            TwoAccountProofScreen()
        } else if ProcessInfo.processInfo.arguments.contains("--settings-emulator-proof") {
            // D-244: settings follow one account across two phones.
            SettingsEmulatorProofScreen()
        } else if ProcessInfo.processInfo.arguments.contains("--groups-proof") {
            // D-179: three groups, then the ordinary tabs on Groups (not in
            // proofRoot's stack, for the reason below).
            GroupsProofScreen()
        } else if verificationArg {
            proofRoot
        } else {
            // NOT inside a NavigationStack: each tab owns its own stack, and
            // a TabView nested in one would draw a second navigation bar.
            HomeShell()
        }
        #else
        HomeShell()
        #endif
    }

    #if DEBUG
    /// The headless verification surfaces: test entry points, not user
    /// flows (BUILD-LOG §3). Unchanged by D-128 except that the smoke
    /// screen is now reached deliberately, through `--smoke`.
    @ViewBuilder
    private var proofRoot: some View {
        NavigationStack {
            if ProcessInfo.processInfo.arguments.contains("--balance") {
                BalanceScreen(source: .fixture)
            } else if ProcessInfo.processInfo.arguments.contains("--balance-vault") {
                BalanceScreen(source: .vault)
            } else if ProcessInfo.processInfo.arguments.contains("--settle") {
                SettleScreen(source: .fixture)
            } else if ProcessInfo.processInfo.arguments.contains("--settle-vault") {
                SettleScreen(source: .vault)
            } else if ProcessInfo.processInfo.arguments.contains("--add") {
                AddExpenseScreen()
            } else if ProcessInfo.processInfo.arguments.contains("--list-vault") {
                ExpenseListView()
            } else if ProcessInfo.processInfo.arguments.contains("--expense-conflict") {
                // D-142: SPEC §6.1's question, over a throwaway vault.
                ExpenseConflictProofScreen()
            } else if ProcessInfo.processInfo.arguments.contains("--import-proof") {
                ImportProofScreen()
            } else if ProcessInfo.processInfo.arguments.contains("--export-proof") {
                ExportProofScreen()
            } else if ProcessInfo.processInfo.arguments.contains("--export") {
                ExportScreen(store: LedgerStore.defaultStore())
            } else {
                // `--smoke`: Swift -> UniFFI -> core -> SQLCipher -> Keychain,
                // the stack's own round-trip proof. Kept, not deleted: it
                // is what showed "wrong key rejected as WrongKey" in D-127.
                SmokeScreenView()
            }
        }
    }
    #endif

    /// The ONLY destructive path, and it is never automatic: the user has
    /// read what is lost and asked for it anyway. Deletes the vault file
    /// so the next launch provisions a fresh one against the key this
    /// device actually holds. The ledger inside is unrecoverable by
    /// design (no key, no plaintext), and that is GUARANTEES #7 working,
    /// not failing. D-225 T6 (A9): a failed erase says so
    /// (`EraseCopy.failed`) and keeps the screen; it was `try?`, which
    /// went on as if the files had gone. Fix wave: one attempt at a time,
    /// off the main thread so the screen shows it working; the files, then
    /// the key (`VaultRecovery.erase`). D-225 follow-up (decision 27): it
    /// signs out of the cloud session first, as the deliberate erase does.
    private func resetVault() {
        guard vaultAttempts.begin() else { return }
        let dir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ledger", isDirectory: true)
        let db = dir.appendingPathComponent("ledger-vault.db")
        let reason = vaultError ?? ""
        DispatchQueue.global(qos: .userInitiated).async {
            // D-225 follow-up (decision 27): signed out first, as the deliberate erase is; the account lives on main.
            let kept = VaultRecovery.erase(vaultPath: db.path, reason: reason, keystore: KeychainKeystore(), signOut: {
                DispatchQueue.main.sync { MainActor.assumeIsolated { Account.shared.signOut() } }
            })
            Task { @MainActor in
                vaultAttempts.end()
                if let kept {
                    vaultNote = kept.note
                    return
                }
                vaultError = nil
                vaultNote = nil
                loadGate()
            }
        }
    }

    /// `retry` (A9): the recovery screen's "Try again"; a vault that still
    /// will not open keeps the screen with the retry note.
    private func loadGate(retry: Bool = false) {
        // Headless verification args bypass the gate entirely: root renders
        // via verificationArg and never consults the flag, so opening the
        // vault here would only race the screen's own open on a fresh
        // install (incident §5.10).
        if verificationArg { return }
        if retry { guard vaultAttempts.begin() else { return } }
        DispatchQueue.global(qos: .userInitiated).async {
            let dir = FileManager.default
                .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("ledger", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            // D-162: never in a backup (SPEC §3.7).
            VaultBackup.exclude(dir)
            let store = LedgerStore(path: dir.appendingPathComponent("ledger-vault.db").path)
            // D-127: this `try?` used to swallow EVERY failure into
            // `false`, which is how a vault that cannot be opened became
            // "onboarding not done yet": the kit screen forever, and a
            // confirm that could never succeed. A first run throws
            // nothing (the store provisions itself), so a throw here
            // means the vault exists and will not open. Say which.
            // D-175: "onboarding/complete" now means "this phone has
            // opened SKIPTI before" (it used to mean "the kit is
            // confirmed"; every earlier install that set it did both).
            let launch = VaultRecovery.launch(store)
            // D-235 (C2, decision 107): the account this vault belongs to, read once the vault opened.
            // D-235 round 4 (shells review M4): read so a vault error is an error, never "no owner" (which signed the
            // real owner out); on an error the check is skipped and the session kept (the cloud gate fails closed).
            let ownerRead: InstallSession.OwnerRead = {
                guard case .opened = launch else { return .failed }
                return Account.ownerRead(store)
            }()
            // Decision 117: whether this install has signed out since its last sign-in.
            let signedInMark = Account.signedInMark(store)
            // D-235 final wave (I-1): a push token delete that an offline sign-out could not finish.
            let deleteOwed = Push.deleteOwed(store)
            // D-245 (U9, decision 177): whether the welcome is pending (a first launch has just marked it).
            let welcome: Bool = {
                guard case .opened(let launchedBefore) = launch else { return false }
                #if DEBUG
                // `--welcome`: the ordinary app with the welcome forced (a look's tool; Debug builds only).
                if ProcessInfo.processInfo.arguments.contains("--welcome") { return true }
                #endif
                return WelcomeGate.atLaunch(launchedBefore: launchedBefore) { try WelcomeGate.pending(store) }
            }()
            Task { @MainActor in
                if retry { vaultAttempts.end() }
                switch launch {
                case .opened(let launchedBefore):
                    // A9: a retry that opens it is a normal launch.
                    vaultError = nil
                    vaultNote = nil
                    // D-163: locked BEFORE the ledger first renders, so it
                    // never flashes. A first launch has nothing to lock
                    // yet, and meeting a lock prompt first is no welcome.
                    if launchedBefore { lock.launch() }
                    // D-235 round 5 (decision 116): Firebase starts only when this install has an account (the vault
                    // holds its owner). The gate signs out a session the vault does not own before anything syncs
                    // (decision 107), then the cloud work this launch's first activation could not start begins.
                    // D-235 final wave (I-1): or, signed out, only to settle a token delete still owed.
                    if LaunchWork.startsCloud() {
                        LaunchFirebase.run(
                            LaunchFirebase.plan(ownerRead, signedIn: signedInMark, deleteOwed: deleteOwed),
                            active: scenePhase == .active,
                            configure: { attach in FirebaseGate.configure(owner: ownerRead, attach: attach) },
                            settle: { Push.shared.settleOwed() },
                            sync: { CloudSync.shared.appIsActive(true) })
                        // D-244 (ADR-0043): signed in, the account's settings the vault holds are applied (one an older
                        // build could not apply is applied now), then an icon change still pending, at the front only.
                        SettingsSync.launched(locked: lock.locked, active: scenePhase == .active)
                    }
                    welcomePending = welcome
                    onboardingComplete = true
                case .unavailable(let reason):
                    vaultError = reason
                    vaultNote = retry ? VaultRecovery.retried(launch)?.note : nil
                }
            }
        }
    }
}

extension LedgerStore {
    var vaultPath: String { path }

    func kvFlag(key: String) throws -> Bool {
        let vault = try VaultGate.open(path: vaultPath, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        guard let data = try vault.kvGet(key: key) else { return false }
        return data == Data([1])
    }

    func setKvFlag(key: String) throws {
        let vault = try VaultGate.open(path: vaultPath, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        try vault.kvSet(key: key, value: Data([1]))
    }

    func kvGet(key: String) throws -> Data? {
        let vault = try VaultGate.open(path: vaultPath, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        return try vault.kvGet(key: key)
    }

    func kvSet(key: String, value: Data) throws {
        let vault = try VaultGate.open(path: vaultPath, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        try vault.kvSet(key: key, value: value)
    }
}

#if DEBUG
/// `--emulators --emulator-as <sub>` (D-210, Debug builds only, a look's
/// tool, not a user flow): signs in to the local Auth emulator with an
/// unsigned Google token for `sub`, as SyncEmulatorProofScreen does, unless
/// this phone is already signed in as it. Never compiled into Release.
@MainActor
enum EmulatorSignIn {
    static func signIn(as sub: String) async {
        // D-235 round 5: a sign-in configures Firebase first (through the gate, which points Auth at the emulator).
        FirebaseGate.configure(owner: Account.ownerRead(.defaultStore()))
        let current = Auth.auth().currentUser?.providerData.first { $0.providerID == "google.com" }?.uid
        guard current != sub else { print("emulator-as: already signed in as \(sub)"); return }
        if Account.shared.isSignedIn { Account.shared.signOut() }
        let claims: [String: Any] = ["sub": sub, "email": "\(sub)@example.test", "email_verified": true]
        guard let data = try? JSONSerialization.data(withJSONObject: claims, options: [.sortedKeys]),
              let token = String(data: data, encoding: .utf8) else { print("emulator-as: could not build the token"); return }
        let outcome = await Account.shared.signIn(GoogleAuthProvider.credential(withIDToken: token, accessToken: ""), method: .google)
        print("emulator-as: signed in as \(sub): \(outcome)")
    }
}
#endif
