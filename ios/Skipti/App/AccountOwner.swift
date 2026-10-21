import SwiftUI

/// D-168: one account per phone (the founder, 2026-09-25).
///
/// A phone's ledger belongs to the first account that signs in on it. Signing
/// out keeps the sync keys, the push cursor and "you" so that signing back in
/// resumes (D-155), which meant a DIFFERENT account signing in would have
/// received this ledger. Now the phone remembers the account's subject id,
/// kept through sign-out, and a sign-in by another account is refused before
/// its session is stored or anything syncs; the refused session is ended on
/// the relay. To use the other account here, the person exports if they want a
/// copy, then erases this phone's ledger and sets SKIPTI up again. The erase is
/// never automatic. Android's twin is AccountOwner.kt.
enum AccountOwner {
    static let key = "account/owner"

    /// The pure rule: no owner yet, or the same account.
    static func owns(stored: String?, incoming: String) -> Bool {
        stored == nil || stored == incoming
    }

    static func stored(_ store: LedgerStore) throws -> String? {
        guard let data = try store.kvGet(key: key), !data.isEmpty else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func remember(_ subject: String, _ store: LedgerStore) throws {
        try store.kvSet(key: key, value: Data(subject.utf8))
    }
}

/// D-172: how this phone signed in, so Settings can say it (the founder,
/// 2026-09-26: "show exactly what was used to sign in"). Apple and Google give
/// SKIPTI no email (scope `openid`, only `sub` is read: ADR-0035), so those
/// two are named by provider; an email code or a passkey is named by the
/// address typed. Kept in the vault, on this phone only. Android's twin is in
/// AccountOwner.kt.
enum SignInMethod: Equatable {
    case apple
    case google
    case email(String)

    static let key = "account/method"

    var encoded: String {
        switch self {
        case .apple: return "apple"
        case .google: return "google"
        case .email(let address): return "email:" + address
        }
    }

    /// Anything this build does not recognise is no method at all, never a
    /// guess.
    static func decode(_ raw: String) -> SignInMethod? {
        switch raw {
        case "apple": return .apple
        case "google": return .google
        default:
            guard raw.hasPrefix("email:") else { return nil }
            let address = String(raw.dropFirst("email:".count))
            return address.isEmpty ? nil : .email(address)
        }
    }

    /// D-234 (decision 84): You's Profile and account row, in one word (the page keeps `label`; never the address).
    var rowLabel: String {
        switch self {
        case .apple: return "Apple"
        case .google: return "Google"
        case .email: return "Email"
        }
    }

    /// What Settings and the confirmation say.
    var label: String {
        switch self {
        case .apple: return "Signed in with Apple"
        case .google: return "Signed in with Google"
        case .email(let address): return "Signed in as " + address
        }
    }

    static func stored(_ store: LedgerStore) -> SignInMethod? {
        guard let data = try? store.kvGet(key: key), let raw = String(data: data, encoding: .utf8) else {
            return nil
        }
        return decode(raw)
    }

    static func remember(_ method: SignInMethod, _ store: LedgerStore) throws {
        try store.kvSet(key: key, value: Data(method.encoded.utf8))
    }
}

/// D-172: the mark beside the method: Apple's logo (the system's own
/// symbol), Google's "G" (Google's round art from its sign-in bundle,
/// unmodified), nothing for an address.
struct SignInMethodMark: View {
    let method: SignInMethod

    var body: some View {
        switch method {
        case .apple:
            Image(systemName: "apple.logo")
                .font(.system(size: 20, weight: .medium))
                .foregroundStyle(Tokens.text)
                .frame(width: 28, height: 28)
                .accessibilityHidden(true)
        case .google:
            Image("GoogleMark")
                .resizable()
                .frame(width: 28, height: 28)
                .accessibilityHidden(true)
        case .email:
            EmptyView()
        }
    }
}

/// D-172: what a completed sign-in shows (the founder, 2026-09-26: the
/// Sign in screen stayed open after Apple succeeded, as if nothing had
/// happened). Done returns to Settings.
enum SignedInCopy {
    static let title = "You're signed in"
    static let detail = "Settings shows how you signed in. You can sign out there at any time."
    static let done = "Done"
}

struct SignedInConfirmation: View {
    let method: SignInMethod
    var onDone: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Spacer(minLength: 40)
            // D-224 (L49): the big done check, drawing itself (it plays the success haptic when it lands).
            DoneCheck(.screen)
            Text(verbatim: SignedInCopy.title)
                .font(TypeRole.display(28))
                .foregroundStyle(Tokens.text)
            HStack(spacing: 10) {
                SignInMethodMark(method: method)
                Text(verbatim: method.label)
                    .font(TypeRole.body())
                    .foregroundStyle(Tokens.text)
            }
            Text(verbatim: SignedInCopy.detail)
                .font(TypeRole.body(14))
                .foregroundStyle(Tokens.textDim)
                .multilineTextAlignment(.center)
            Spacer()
            Button { onDone() } label: { Text(verbatim: SignedInCopy.done) }
                .buttonStyle(PrimaryButtonStyle())
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .auroraBackground() // D-176
        .accessibilityElement(children: .contain)
    }
}

/// D-184's notice that this phone's ledger is erased, as D-224's big screen (Design 7, 8): the check in place of the
/// alert, then its title, its words and "OK", laid out as the signed-in screen is. Android's twin is `ErasedScreen` in
/// AccountOwner.kt (there it replaces the root; here it covers it).
struct ErasedScreen: View {
    var onDone: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Spacer(minLength: 40)
            DoneCheck(.screen)
            // D-224 fix wave (F9): broken where Android breaks it ("This phone's ledger is / erased").
            GreedyText(EraseCopy.doneTitle, font: TypeRole.displayUIFont(28, category: AppearanceState.shared.contentSizeCategory,
                                                                         theme: AppearanceState.shared.theme))
                .foregroundStyle(Tokens.text)
                .multilineTextAlignment(.center)
            Text(verbatim: EraseCopy.doneBody)
                .font(TypeRole.body(TypeScale.detail))
                .foregroundStyle(Tokens.textDim)
                .multilineTextAlignment(.center)
            Spacer()
            Button { onDone() } label: { Text(verbatim: EraseCopy.doneOK) }
                .buttonStyle(PrimaryButtonStyle())
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .auroraBackground()
        .accessibilityElement(children: .contain)
    }
}

enum OtherAccountCopy {
    static let title = "This phone's ledger belongs to another account"
    static let body =
        "SKIPTI keeps one account's ledger on a phone. You were not signed in, and nothing was synced. "
        + "To use this account here, export your data if you want a copy, then erase this phone's "
        + "ledger and set SKIPTI up again."
}

enum EraseCopy {
    static let title = "Erase this phone"
    static let detail =
        "Deletes everything SKIPTI keeps on this phone: its groups and expenses. "
        + "For selling or handing on a phone. Your account, if you have one, is not deleted."
    static let button = "Erase this phone's ledger"
    static let confirmTitle = "Erase this phone's ledger?"
    static let confirmBody = "This cannot be undone. Export first if you want a copy."
    static let confirm = "Erase"
    // D-184: said once the erase is done (a tester: "no confirmation at all").
    static let doneTitle = "This phone's ledger is erased"
    static let doneBody =
        "Everything SKIPTI kept on this phone is gone. Your account, if you have one, is not deleted."
    static let doneOK = "OK"
    static let failed = "The ledger could not be erased."
}

extension Notification.Name {
    /// D-168: the ledger was erased; the app starts setup again.
    static let skiptiLedgerErased = Notification.Name("skipti.ledger-erased")
}

/// The deliberate erase: the vault's files, then its key. A new key and a new
/// vault are made at setup. The key goes too because a keychain item outlives
/// the app, so a vault file restored later could otherwise still open.
enum LedgerErase {
    /// D-225 fix wave (the controller's A9 decision): the files both erases take, in Android's `LedgerErase.ORDER`:
    /// the write-ahead files (and a rollback journal, if one was left) first, the database last; the key after them.
    static func files(_ vaultPath: String) -> [String] {
        [vaultPath + "-wal", vaultPath + "-shm", vaultPath + "-journal", vaultPath]
    }

    /// D-235 (C4, iOS M1): any plaintext export left in `exports` (the app's tmp) goes first, so `EraseCopy.doneBody`
    /// ("Everything SKIPTI kept on this phone is gone") is true; the confirmation asks the person to export first.
    static func erase(vaultPath: String, keystore: SkiptiKeystore,
                      remove: (String) throws -> Void = { try FileManager.default.removeItem(atPath: $0) },
                      exports: URL = FileManager.default.temporaryDirectory) throws {
        try Exporter.removeExports(in: exports)
        for path in files(vaultPath) where FileManager.default.fileExists(atPath: path) {
            try remove(path)
        }
        try keystore.destroy()
    }

    /// Both of the app's erases, the deliberate one and the recovery screen's confirmed one (D-225 follow-up, WORKLOG
    /// decision 27): sign out of the cloud session first (D-192: of the Firebase account), then `erase`. The account's
    /// owner key lives in the vault, so a session left behind would point at a ledger that no longer exists; the person
    /// can sign in again afterwards. A sign-out that throws erases nothing. Android's twin is `signOutAndErase`.
    static func signOutAndErase(vaultPath: String, keystore: SkiptiKeystore, signOut: () throws -> Void,
                                remove: (String) throws -> Void = { try FileManager.default.removeItem(atPath: $0) }) throws {
        try signOut()
        try erase(vaultPath: vaultPath, keystore: keystore, remove: remove)
    }

    /// The app's own erase (Settings, and the other-account notice): `signOutAndErase` with this phone's keychain,
    /// then tell the app.
    @MainActor
    static func eraseThisPhone() throws {
        try signOutAndErase(vaultPath: LedgerStore.defaultStore().path, keystore: KeychainKeystore(),
                            signOut: { Account.shared.signOut() })
        NotificationCenter.default.post(name: .skiptiLedgerErased, object: nil)
    }
}

/// D-225 (Design 4): Security → Erase this phone's ledger: the page says what goes, then the erase button with its
/// confirmation and its warning haptic (D-224), unchanged; the bar names the page, so the card draws no title of its
/// own (D-217). Never automatic (D-127). Android's twin is `EraseScreen` in AccountOwner.kt.
struct EraseScreen: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: EraseCopy.detail)
                    .font(TypeRole.body(TypeScale.detail))
                    .foregroundStyle(Tokens.textDim)
                    .fixedSize(horizontal: false, vertical: true)
                EraseLedgerButton()
            }
            .glassCard()
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        .navigationTitle(Text(verbatim: EraseCopy.title))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The erase button with its confirmation, used by Settings and by the
/// other-account notice.
struct EraseLedgerButton: View {
    @State private var confirming = false
    @State private var failed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(role: .destructive) { confirming = true } label: {
                Text(verbatim: EraseCopy.button)
            }
            .buttonStyle(SecondaryButtonStyle(destructive: true))
            if failed {
                Text(verbatim: EraseCopy.failed).font(TypeRole.body(14)).foregroundStyle(Tokens.owed)
            }
        }
        .alert(EraseCopy.confirmTitle, isPresented: $confirming) {
            Button(EraseCopy.confirm, role: .destructive) { erase() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(verbatim: EraseCopy.confirmBody)
        }
        // D-224 (Design 3): the warning as the erase's question opens.
        .hapticWarning(when: confirming)
    }

    private func erase() {
        Task { @MainActor in
            do {
                try LedgerErase.eraseThisPhone()
            } catch {
                failed = true
            }
        }
    }
}

/// What a refused sign-in shows, on the sign-in screen itself.
struct OtherAccountNotice: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(verbatim: OtherAccountCopy.title).font(TypeRole.body()).foregroundStyle(Tokens.owed)
            Text(verbatim: OtherAccountCopy.body).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
            NavigationLink {
                ExportScreen(store: LedgerStore.defaultStore())
            } label: {
                Text("Export your data")
            }
            .buttonStyle(SecondaryButtonStyle())
            EraseLedgerButton()
        }
    }
}
