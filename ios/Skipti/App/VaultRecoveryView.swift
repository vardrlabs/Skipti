import SwiftUI

/// The screen's words, in order; Android's `VaultRecoveryCopy` carries the same. No dashes.
enum VaultRecoveryCopy {
    static let kicker = "Ledger unavailable"
    static let title = "This device cannot open your ledger."
    static let explain =
        "Your entries are encrypted with a key held by this device. Without that key nobody can read them: not us, "
        + "not anyone with the file. That is the guarantee working. Sometimes the key is only out of reach for a "
        + "moment, after a restart or a restore, so try again first. Starting fresh erases this ledger for good: keep "
        + "it for last."
    static let tryAgain = "Try again"
    static let retryFailed = "Tried again just now. It still will not open."
    static let startFresh = "Start fresh"
    static let eraseWarning =
        "Starting fresh erases the ledger on this device permanently. If you have it on another device, sign in there instead."
    static let erase = "Erase and start fresh"
    static let cancel = "Cancel"
    static let wrongKey = "The key that opens this ledger is not on this device."
}

/// D-225 T6 (A9): the launch's one read of the vault and what follows from it, apart from the screen so each step is
/// tested. Nothing here erases anything except `erase`, which only the confirm step calls.
enum VaultRecovery {
    enum Launch: Equatable {
        /// The vault opened; `launchedBefore` is D-175's "this phone has opened SKIPTI before".
        case opened(launchedBefore: Bool)
        /// The vault exists and will not open, with the honest reason (`describe`).
        case unavailable(reason: String)
    }

    /// The line the screen adds after an attempt failed.
    enum Note: Equatable {
        case retryFailed, eraseFailed

        var text: String {
            switch self {
            case .retryFailed: return VaultRecoveryCopy.retryFailed
            case .eraseFailed: return EraseCopy.failed
            }
        }
    }

    /// The screen as it stands: why, and what the last attempt left.
    struct Screen: Equatable {
        var reason: String
        var note: Note?
    }

    /// D-225 fix wave (the controller's A9 decision): what the screen shows of the last attempt. The retry line is
    /// about trying again, so it hides while the erase is asked; a failed erase's line shows where it happened.
    static func shown(_ note: Note?, confirming: Bool) -> Note? {
        confirming && note == .retryFailed ? nil : note
    }

    /// D-225 fix wave (the controller's A9 decision): one attempt at a time. While "Try again" reads or the erase
    /// works, every button is disabled and a second tap starts nothing (it used to queue reads, and a second erase
    /// could remove the vault the first one's launch had just made).
    struct Attempts: Equatable {
        private(set) var busy = false

        mutating func begin() -> Bool {
            guard !busy else { return false }
            busy = true
            return true
        }

        mutating func end() { busy = false }
    }

    static let launchedFlag = "onboarding/complete"

    /// D-127, D-175: read the flag, set it on a first launch. A first run throws nothing (the store provisions itself),
    /// so a throw means the vault exists and will not open: never "a first launch", never swallowed.
    static func launch(read: () throws -> Bool, mark: () throws -> Void) -> Launch {
        do {
            let launchedBefore = try read()
            if !launchedBefore { try mark() }
            return .opened(launchedBefore: launchedBefore)
        } catch {
            return .unavailable(reason: describe(error))
        }
    }

    /// D-245 (decision 177): a first launch marks the welcome pending BEFORE it marks the phone launched, so a launch
    /// killed between the two still welcomes (the next launch is a first launch again).
    static func launch(_ store: LedgerStore) -> Launch {
        launch(read: { try store.kvFlag(key: launchedFlag) }, mark: {
            try WelcomeGate.mark(store)
            try store.setKvFlag(key: launchedFlag)
        })
    }

    /// After "Try again": nil when the ledger opened (the app goes on as a normal launch), else the screen with the
    /// reason read now and the retry note.
    static func retried(_ launch: Launch) -> Screen? {
        switch launch {
        case .opened: return nil
        case .unavailable(let reason): return Screen(reason: reason, note: .retryFailed)
        }
    }

    /// The ONLY destructive step, called from the confirm step alone. D-225 follow-up (WORKLOG decision 27): the same
    /// operation as the deliberate erase (`LedgerErase.signOutAndErase`) and Android's: sign out of the cloud session
    /// first (the account's owner key lives in the vault, so a session left behind would point at a ledger that no
    /// longer exists; the person can sign in again afterwards), then the write-ahead files, the database last, then the
    /// keychain item once every file is gone. A sign-out that throws erases nothing. The key goes too because a key the
    /// keychain holds but cannot hand back (the wrong length, a refused read) would fail the next launch the same way,
    /// and a key left behind could still open a vault file restored later; the next launch makes a fresh key and vault.
    /// nil when all of it went (the launch runs again); otherwise the screen stays with its reason and `EraseCopy.failed`.
    static func erase(vaultPath: String, reason: String, keystore: SkiptiKeystore, signOut: () throws -> Void,
                      remove: (String) throws -> Void = { try FileManager.default.removeItem(atPath: $0) }) -> Screen? {
        do {
            try LedgerErase.signOutAndErase(vaultPath: vaultPath, keystore: keystore, signOut: signOut, remove: remove)
            return nil
        } catch {
            return Screen(reason: reason, note: .eraseFailed)
        }
    }

    /// The honest name for why the vault would not open. `WrongKey` is
    /// the one users actually meet: the database survived but the key in
    /// the Keychain did not (a restored backup, a reinstalled simulator, a
    /// wiped keychain). §4.2's deferred backup-exclusion work is what makes
    /// that reachable on a real device.
    static func describe(_ error: Error) -> String {
        if let store = error as? StoreError {
            switch store {
            case .WrongKey:
                return VaultRecoveryCopy.wrongKey
            case .NewerSchema(let found, let latest):
                return "This ledger was written by a newer version (schema \(found), this build reads \(latest))."
            case .Keystore(let message):
                return "The device keychain refused: \(message)"
            default:
                return String(describing: store)
            }
        }
        return String(describing: error)
    }
}

/// D-127: the screen for a vault that exists and will not open.
///
/// WHY THIS EXISTS. The onboarding gate used to read its flag through
/// `try?`, so every failure became `false` ("onboarding not done yet").
/// A device whose Keychain key no longer matches its database therefore
/// showed the Recovery Kit screen forever, and confirming the words
/// failed with "Could not store the kit. Confirm again." Advice that
/// could never work, on a screen with no way out. Observed on a real
/// simulator install; reachable on a real device whenever the database
/// is restored from a backup and the Keychain item is not, which §4.2's
/// deferred backup-exclusion work is exactly about.
///
/// WHAT IT REFUSES TO DO. It does not delete anything on its own. The
/// ledger behind a missing key is unrecoverable (no key, no plaintext),
/// and that is GUARANTEES #7 working rather than failing, so the honest
/// move is to say so and let the person decide. Silently recreating the
/// vault would look like a fix and would be destroying a ledger.
///
/// D-225 T6 (A7, A9, the founder 2026-10-17): "Try again" first (a key can be out of reach for a moment, after a
/// restart or a restore): a success goes on as a normal launch. "Start fresh" stays secondary with its confirm step,
/// and a failed erase says so and keeps the screen (it used `try?`). Android's twin is `VaultRecoveryScreen`.
struct VaultRecoveryView: View {
    let reason: String
    var note: VaultRecovery.Note? = nil
    /// An attempt (Try again, the erase) is working: every button waits, dimmed.
    var busy = false
    let onRetry: () -> Void
    let onReset: () -> Void

    @State private var confirming = false

    var body: some View {
        // D-234 (L5, decision 90): every paragraph breaks greedily, as Android's text does (iOS's own line breaker moves a
        // lone last word down), so with the same room both phones break at the same words.
        let category = AppearanceState.shared.contentSizeCategory
        let theme = AppearanceState.shared.theme
        let paragraph = TypeRole.bodyUIFont(category: category, theme: theme)
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(verbatim: VaultRecoveryCopy.kicker)
                    .microLabelFont()
                    .foregroundStyle(Tokens.owed)
                Rectangle()
                    .fill(Tokens.hairline)
                    .frame(height: 1)

                GreedyText(VaultRecoveryCopy.title, font: TypeRole.displayUIFont(TypeScale.lockTitle, category: category, theme: theme), alignment: .leading)
                    .foregroundStyle(Tokens.text)

                GreedyText(reason, font: paragraph, alignment: .leading)
                    .foregroundStyle(Tokens.textDim)

                GreedyText(VaultRecoveryCopy.explain, font: paragraph, alignment: .leading)
                    .foregroundStyle(Tokens.text)

                if confirming {
                    GreedyText(VaultRecoveryCopy.eraseWarning, font: paragraph, alignment: .leading)
                        .foregroundStyle(Tokens.owed)
                }

                // What the last attempt left: a retry that still could not open it (calm; hidden while the erase is
                // asked), or an erase that failed.
                if let note = VaultRecovery.shown(note, confirming: confirming) {
                    GreedyText(note.text, font: paragraph, alignment: .leading)
                        .foregroundStyle(note == .eraseFailed ? Tokens.owed : Tokens.textDim)
                }

                if confirming {
                    Button(VaultRecoveryCopy.erase, role: .destructive) { onReset() }
                        .buttonStyle(PrimaryButtonStyle(tint: Tokens.owed))
                        .accessibilityHint("Permanently deletes the ledger stored on this device.")
                        .disabled(busy)

                    Button(VaultRecoveryCopy.cancel) { confirming = false }
                        .buttonStyle(SecondaryButtonStyle())
                        .disabled(busy)
                } else {
                    // A9: trying again is the first thing offered; starting fresh is the last resort and only asks.
                    Button(VaultRecoveryCopy.tryAgain) { onRetry() }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityHint("Opens the ledger again.")
                        .disabled(busy)

                    Button(VaultRecoveryCopy.startFresh) { confirming = true }
                        .buttonStyle(SecondaryButtonStyle())
                        .accessibilityHint("Asks you to confirm before erasing anything.")
                        .disabled(busy)
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Tokens.bg.ignoresSafeArea())
        // D-224 (Design 3): the erase is asked in place; the warning plays as that question opens.
        .hapticWarning(when: confirming)
    }
}
