import FirebaseAuth
import FirebaseCore
import Foundation

/// Firebase, configured in code (D-191). The values are public identifiers
/// from `firebase apps:sdkconfig IOS` for project skipti-26, so a clean clone
/// builds without the gitignored GoogleService-Info.plist. Android's twin is
/// FirebaseConfig.kt.
enum FirebaseConfig {
    static let projectID = "skipti-26"
    static let googleAppID = "1:403030719865:ios:26a313b36c35afcdcd43a8"
    static let gcmSenderID = "403030719865"
    static let apiKey = "AIzaSyDXwEpZ2z09zjhrtJVoKKuoDQzTnUtIGqc"
    static let storageBucket = "skipti-26.firebasestorage.app"
    static let bundleID = "com.vardrlabs.skipti"
    /// The iOS OAuth client of the same project: Google sign-in's PKCE code
    /// flow (D-138's client, now Firebase's iOS client too).
    static let iosClientID = "403030719865-pmgfvj4eo4l73bao8lqblqmpera4augj.apps.googleusercontent.com"

    static var options: FirebaseOptions {
        let options = FirebaseOptions(googleAppID: googleAppID, gcmSenderID: gcmSenderID)
        options.apiKey = apiKey
        options.projectID = projectID
        options.storageBucket = storageBucket
        options.clientID = iosClientID
        options.bundleID = bundleID
        return options
    }

    /// Once, before any Firebase call. Idempotent: the hosted tests start the
    /// app too.
    static func configure() {
        guard FirebaseApp.app() == nil else { return }
        FirebaseApp.configure(options: options)
    }
}

/// D-235 round 5 (decision 116, wave E's E4 measurement): Firebase is configured only once this install has an account
/// (the vault holds an owner key) or the person taps a sign-in button. `FirebaseApp.configure` starts Messaging, whose
/// reachability check looked up www.google.com at every launch, account or not (GUARANTEES #7: without an account
/// nothing leaves the phone). Every Firebase call goes through here: before configure there is no account, no Auth, no
/// Firestore, no Messaging. Right after configure, before anything can sync, a session this install should not hold is
/// signed out (`InstallSession.signsOutAfterConfigure`, the reinstall rule of decision 107).
@MainActor
enum FirebaseGate {
    /// Tests only: how many times this process configured through the gate.
    private(set) static var configureCount = 0

    /// Our own record, not `FirebaseApp.app()`: asking Firebase before it is configured logs an error (I-COR000003)
    /// at every launch with no account.
    private(set) static var isConfigured = false

    /// Auth, only once configured; nil before (no account can exist yet).
    static var auth: Auth? { isConfigured ? Auth.auth() : nil }

    /// D-235 final wave (I-1): whether the account may attach (read the session, listen). False while Firebase is
    /// configured only to settle an owed token delete (`LaunchFirebase.Plan.settleOwed`): that launch has no account,
    /// so nothing syncs, listens or registers. The first configure that attaches sets it for good.
    private(set) static var accountAttaches = false

    /// Configures (once), points Auth at the local emulator in an emulator run, applies the install's sign-out rule
    /// with `owner` (what the vault said; `.failed` when it was not read), then lets the account attach unless
    /// `attach` is false. Idempotent; a later call that attaches attaches then.
    static func configure(owner: InstallSession.OwnerRead, attach: Bool = true) {
        if !isConfigured {
            FirebaseConfig.configure()
            isConfigured = true
            configureCount += 1
            if Cloud.emulator { Auth.auth().useEmulator(withHost: "localhost", port: 9099) }
            let current = Auth.auth().currentUser?.uid
            if InstallSession.signsOutAfterConfigure(pending: InstallSession.pendingSignOut(), currentUid: current, owner: owner) {
                try? Auth.auth().signOut()
            }
            InstallSession.clearPendingSignOut()
        }
        guard attach else { return }
        accountAttaches = true
        Account.shared.attachToFirebase()
    }
}
