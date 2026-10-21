import FirebaseAuth
import SwiftUI

/// D-188 (the founder, 2026-09-30: "the entire page is taken and only the top
/// part is filled"): what the sign-in page says around the buttons. It
/// informs, never nudges (GUARANTEES #5: the person already chose this
/// screen). D-191: the account is a Firebase account, and the words say where
/// the data goes. Android's twin is `SignInCopy` in SignInScreen.kt.
enum SignInCopy {
    static let heroTitle = "Your ledger, on every phone"
    static let heroBody = "Signing in adds sync and groups. SKIPTI keeps working on this phone without it."
    static let stepsTitle = "What happens when you sign in"

    /// The three steps, the first naming only the providers this build offers.
    static func steps(apple: Bool, google: Bool) -> [String] {
        let who: String
        switch (apple, google) {
        case (true, true): who = "Apple or Google confirms it is you."
        case (true, false): who = "Apple confirms it is you."
        default: who = "Google confirms it is you."
        }
        return [
            who,
            "Your ledger syncs to your other phones through SKIPTI's cloud, which runs on Google Firebase.",
            "You can make and join groups. Only the people in a group can see it.",
        ]
    }
}

/// D-243 (stage 5): where the sign-in hero sits. It is the first Section's header, not a row (see `fields`), and
/// keeps the row's place.
enum SignInLayout {
    /// The Form's top margin above a first row (iOS 27, measured on the simulator 2026-10-20: the hero's still frame
    /// is pixel-identical outside its own box in Aurora, Norse and Vardr Labs): a first header sits flush with the top.
    static let heroTop: CGFloat = 35
}

/// D-191: Sign in with Apple or Google, through Firebase Auth. A completed
/// sign-in becomes its confirmation (D-172); another account's ledger on this
/// phone keeps the page open with the reason (D-168).
struct SignInScreen: View {
    @State private var refused = false
    @State private var signedIn: SignInMethod?
    /// C2: after a sign-in, whether the account still needs a username (nil
    /// while that is being read).
    @State private var usernameNeeded: Bool?
    /// C2: counts sign-ins and sign-outs here, so a username read still
    /// running for an earlier one never decides the page.
    @State private var attempt = 0
    @State private var inFlight = false
    @State private var errorText: String?
    /// C2: a sign-out from another phone (or a deletion) leaves the step.
    @ObservedObject private var remote = RemoteSignOut.shared
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        // A container, not a Group: the onChange below must outlive the
        // branch it switches away from.
        ZStack {
            switch SignInStep.decide(signedIn: signedIn != nil && Account.shared.isSignedIn, needed: usernameNeeded) {
            case .step:
                // C2: required, no close; then the usual confirmation. Once a
                // save has failed, "Later" goes on to it (GUARANTEES #2).
                UsernameStep(store: .defaultStore(), onLater: { usernameNeeded = false }) { usernameNeeded = false }
            case .confirmation:
                // decide() says .confirmation only with a method set.
                if let signedIn {
                    SignedInConfirmation(method: signedIn) { dismiss() }
                        .navigationTitle("Sign in")
                        .navigationBarTitleDisplayMode(.inline)
                        .navigationBarBackButtonHidden()
                }
            case .waiting:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity).auroraBackground()
                    .navigationBarBackButtonHidden()
            case .form:
                Form { fields }
                    .auroraFormBackground() // D-176
                    .navigationTitle("Sign in")
                    .navigationBarTitleDisplayMode(.inline)
            }
        }
        .onChange(of: Account.shared.isSignedIn) { _, now in if !now { leftAccount() } }
        .onChange(of: remote.signOuts) { leftAccount() }
    }

    /// C2: signed out while the step (or its wait) was showing: back to the
    /// form, with nothing of that sign-in kept.
    private func leftAccount() {
        signedIn = nil
        usernameNeeded = nil
        attempt += 1
    }

    @ViewBuilder
    private var fields: some View {
        // D-168: another account's ledger is on this phone.
        if refused {
            Section { OtherAccountNotice() }
        }
        // D-188: what signing in is for, above the buttons. D-243 (stage 5): the Section's header, not a row: iOS 26+
        // clips a row to its rounded cell, which cut Norse's notched corners round and its edge glow away entirely
        // (Android's twin shows both). A header is not clipped.
        Section {
        } header: {
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: SignInCopy.heroTitle)
                    .font(TypeRole.display(24, weight: 700))
                    .foregroundStyle(Tokens.onHero)
                Text(verbatim: SignInCopy.heroBody)
                    .font(TypeRole.body(15))
                    .foregroundStyle(Tokens.onHero)
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background { HeroArt().clipShape(ThemeArt.cardShape(AppearanceState.shared.theme)) }
            // D-243 (T7): Norse's edge glow, outside the clipped art.
            .norseEdgeGlow()
            .accessibilityElement(children: .combine)
            // A Section header reads as a heading to VoiceOver; Android's twin is no heading (fix wave M3a).
            .accessibilityRemoveTraits(.isHeader)
            // A first header sits flush with the Form's top, where a first row sat 35 pt lower: the padding keeps the
            // row's place. After the notice (a section of its own) it is not padded: assumed to take the row's place
            // there too (not measured: no look reaches the notice).
            .padding(.top, refused ? 0 : SignInLayout.heroTop)
            .textCase(nil)
            .listRowInsets(EdgeInsets())
        }
        Section {
            // D-132/D-138: each company's own button (Apple's HIG,
            // Google's branding rules), both at the size of Google's art,
            // because each company requires its button to be at least as
            // prominent as the other's. Apple's is rebuilt when the colour
            // scheme changes, because its style is fixed at creation.
            VStack(spacing: 12) {
                AppleSignInButton(colorScheme: colorScheme) { signInWithApple() }
                    .id(colorScheme)
                    .frame(width: GoogleSignInButton.size.width, height: GoogleSignInButton.size.height)
                    .allowsHitTesting(!inFlight)
                    .opacity(inFlight ? 0.45 : 1)
                if GoogleSignInConfig.isAvailable {
                    GoogleSignInButton { signInWithGoogle() }
                        .disabled(inFlight)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
            // The Form's row separator drew a stray hairline under
            // Apple's button (seen on the simulator, both schemes).
            .listRowSeparator(.hidden)
        }

        // D-188, D-191: who confirms it is you, and what an account adds.
        Section {
            VStack(alignment: .leading, spacing: 14) {
                Text(verbatim: SignInCopy.stepsTitle)
                    .font(TypeRole.label(17))
                    .foregroundStyle(Tokens.text)
                ForEach(Array(SignInCopy.steps(apple: true, google: GoogleSignInConfig.isAvailable).enumerated()),
                        id: \.offset) { i, step in
                    HStack(alignment: .top, spacing: 12) {
                        Text(verbatim: "\(i + 1)")
                            .font(TypeRole.label(14))
                            .foregroundStyle(Tokens.accent)
                            .frame(width: 28, height: 28)
                            .background(Tokens.accentSoft, in: Circle())
                        Text(verbatim: step)
                            .font(TypeRole.body(15))
                            .padding(.top, 4)
                            .foregroundStyle(Tokens.textDim)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(.vertical, 8)
        }

        if let errorText {
            Section {
                Text(errorText)
                    .font(TypeRole.body())
                    .foregroundStyle(Tokens.owed)
                    .accessibilityLabel("Sign-in failed. \(errorText)")
            }
        }
    }

    /// Every path ends here: a refusal keeps the form open with its notice, a
    /// success becomes the confirmation.
    private func finish(_ outcome: Account.Outcome, _ method: SignInMethod) {
        inFlight = false
        switch outcome {
        case .signedIn:
            refused = false
            signedIn = method
            usernameNeeded = nil
            attempt += 1
            let mine = attempt
            // The wait is bounded: after 10 seconds without an answer (a
            // slow or dead network) the confirmation shows and the card asks
            // later. Whichever finishes first decides; the other does nothing.
            let timer = Task { @MainActor in
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                guard !Task.isCancelled, attempt == mine, usernameNeeded == nil else { return }
                usernameNeeded = false
            }
            Task { @MainActor in
                // An account that already has a username (a second phone)
                // skips the step. Right after sign-in the profile may not
                // exist yet (registration runs in the background): register
                // it here, then read again. Still unreadable: no step; the
                // card asks later. (No `await` inside `&&`: Swift refuses it.)
                var state = await People.accountUsername()
                if state == nil, let uid = Account.shared.uid {
                    // D-235 (C4, iOS L3): CloudSync's registration, never a second one beside it.
                    _ = await CloudSync.shared.registration(uid: uid)
                    state = await People.accountUsername()
                }
                timer.cancel()
                guard attempt == mine, Account.shared.isSignedIn else { return }
                if usernameNeeded == nil { usernameNeeded = UsernameState.needsStep(state) }
            }
        case .otherAccount:
            refused = true
        case .failed(let reason):
            refused = false
            errorText = reason
        }
    }

    /// Apple's sheet, then Firebase. A dismissed sheet is not an error.
    private func signInWithApple() {
        guard !inFlight else { return }
        inFlight = true
        errorText = nil
        // D-235 round 5 (decision 116): the tap is where an install with no account first starts Firebase; the gate
        // signs out a session left in the keychain before this sign-in (decision 107).
        FirebaseGate.configure(owner: Account.ownerRead(.defaultStore()))
        Task { @MainActor in
            do {
                let credential = try await AppleSignIn.credential()
                finish(await Account.shared.signIn(credential, method: .apple), .apple)
            } catch let error where AppleSignIn.isCancellation(error) {
                inFlight = false
            } catch {
                inFlight = false
                errorText = String(localized: "Sign in with Apple did not finish.")
            }
        }
    }

    /// Google's page in the system sheet, the code exchanged with Google, then
    /// Firebase. As with Apple, closing the sheet is not an error.
    private func signInWithGoogle() {
        guard !inFlight, let clientID = GoogleSignInConfig.iosClientID else { return }
        inFlight = true
        errorText = nil
        // D-235 round 5 (decision 116): the tap is where an install with no account first starts Firebase; the gate
        // signs out a session left in the keychain before this sign-in (decision 107).
        FirebaseGate.configure(owner: Account.ownerRead(.defaultStore()))
        Task { @MainActor in
            do {
                let credential = try await GoogleSignIn.credential(clientID: clientID)
                finish(await Account.shared.signIn(credential, method: .google), .google)
            } catch GoogleSignInError.cancelled {
                inFlight = false
            } catch {
                inFlight = false
                errorText = String(localized: "Sign in with Google did not finish.")
            }
        }
    }
}
