import AuthenticationServices
import CryptoKit
import FirebaseAuth
import SwiftUI
import UIKit

/// Sign in with Apple on iOS (SPEC §4.2/§12.2; D-132; D-191: through Firebase).
///
/// Flow, from a tap: a random raw nonce is made on the phone, Apple's request
/// carries its SHA-256 with NO scopes (Apple shares neither name nor email),
/// the system sheet runs, and the identity token goes to Firebase Auth with
/// the raw nonce, which Firebase checks against the hash inside the token.
enum AppleSignIn {
    static func request(hashedNonce: String) -> ASAuthorizationAppleIDRequest {
        let request = ASAuthorizationAppleIDProvider().createRequest()
        // Nothing beyond the stable user id.
        request.requestedScopes = []
        request.nonce = hashedNonce
        return request
    }

    /// SHA-256 in lowercase hex: the form Firebase compares.
    static func sha256Hex(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// The identity token from a completed authorization, as the JWT text.
    static func identityToken(from credential: ASAuthorizationCredential) throws -> String {
        guard let apple = credential as? ASAuthorizationAppleIDCredential,
              let data = apple.identityToken,
              let token = String(data: data, encoding: .utf8),
              !token.isEmpty
        else { throw GoogleSignInError.couldNotStart }
        return token
    }

    /// True when the person dismissed Apple's sheet: not an error to show.
    static func isCancellation(_ error: Error) -> Bool {
        (error as? ASAuthorizationError)?.code == .canceled
    }

    /// Apple's sheet, then the credential Firebase signs in with.
    @MainActor
    static func credential() async throws -> AuthCredential {
        let rawNonce = try GoogleSignIn.randomToken(bytes: 32)
        let authorization = try await run(requests: [request(hashedNonce: sha256Hex(rawNonce))])
        return OAuthProvider.appleCredential(
            withIDToken: try identityToken(from: authorization), rawNonce: rawNonce, fullName: nil
        )
    }

    /// Runs one ASAuthorizationController presentation and returns the
    /// credential the user authorised (moved here from the passkey ceremony,
    /// D-192, when passkeys were removed). Requires a foreground window
    /// scene; on a simulator without the entitlement this fails in the
    /// delegate with ASAuthorizationError, surfaced as-is.
    @MainActor
    static func run(requests: [ASAuthorizationRequest]) async throws -> ASAuthorizationCredential {
        try await withCheckedThrowingContinuation { continuation in
            let controller = ASAuthorizationController(authorizationRequests: requests)
            let delegate = CeremonyDelegate(continuation)
            delegate.install(on: controller)
            controller.performRequests()
        }
    }

    @MainActor
    private final class CeremonyDelegate: NSObject,
        ASAuthorizationControllerDelegate,
        ASAuthorizationControllerPresentationContextProviding {

        private var continuation: CheckedContinuation<ASAuthorizationCredential, Error>?

        init(_ continuation: CheckedContinuation<ASAuthorizationCredential, Error>) {
            self.continuation = continuation
        }

        func install(on controller: ASAuthorizationController) {
            objc_setAssociatedObject(controller, &Self.key, self, .OBJC_ASSOCIATION_RETAIN)
            controller.delegate = self
            controller.presentationContextProvider = self
        }
        private static var key: UInt8 = 0

        func authorizationController(
            controller: ASAuthorizationController,
            didCompleteWithAuthorization authorization: ASAuthorization
        ) {
            continuation?.resume(returning: authorization.credential)
            continuation = nil
        }

        func authorizationController(
            controller: ASAuthorizationController, didCompleteWithError error: Error
        ) {
            continuation?.resume(throwing: error)
            continuation = nil
        }

        func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
            UIApplication.shared.connectedScenes
                .compactMap { ($0 as? UIWindowScene)?.keyWindow }
                .first ?? ASPresentationAnchor()
        }
    }
}

/// Apple's own button, UIKit's `ASAuthorizationAppleIDButton`, not SwiftUI's
/// `SignInWithAppleButton`. The SwiftUI button configures its request
/// synchronously; this one runs an action, so the nonce is made only after a
/// tap. Its style is fixed at creation,
/// so callers give it `.id(colorScheme)` to rebuild it when the scheme changes.
struct AppleSignInButton: UIViewRepresentable {
    var colorScheme: ColorScheme
    var action: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(action: action) }

    func makeUIView(context: Context) -> ASAuthorizationAppleIDButton {
        let button = ASAuthorizationAppleIDButton(
            authorizationButtonType: .signIn,
            authorizationButtonStyle: colorScheme == .dark ? .white : .black
        )
        // D-188: a pill, like Google's pill art directly beneath at the same
        // size (D-138 matched its square corners at 4).
        button.cornerRadius = GoogleSignInButton.size.height / 2
        button.addTarget(context.coordinator, action: #selector(Coordinator.tapped), for: .touchUpInside)
        return button
    }

    func updateUIView(_ uiView: ASAuthorizationAppleIDButton, context: Context) {
        context.coordinator.action = action
    }

    final class Coordinator: NSObject {
        var action: () -> Void
        init(action: @escaping () -> Void) { self.action = action }
        @objc func tapped() { action() }
    }
}
