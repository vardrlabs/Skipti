import AuthenticationServices
import CryptoKit
import FirebaseAuth
import Foundation
import Security
import SwiftUI
import UIKit

/// Whether Sign in with Google can be offered on this build (ADR-0035, D-132).
enum GoogleSignInConfig {
    /// The iOS OAuth client id from Google Cloud (D-138; the founder created
    /// it on 2026-09-18; in project skipti-26, so Firebase accepts its ID
    /// tokens). Public, not a secret: it ships in every build.
    static let iosClientID: String? = FirebaseConfig.iosClientID

    /// Google's branding rules require its official button artwork, taken
    /// from Google's asset bundle rather than redrawn (D-138: the
    /// `GoogleSignInButton` image set, light and dark, with the words drawn
    /// into the image so no font is loaded).
    static let brandMarkBundled = true

    static var isAvailable: Bool {
        isAvailable(clientID: iosClientID, brandMarkBundled: brandMarkBundled)
    }

    /// Pure so the tests can pin every combination.
    static func isAvailable(clientID: String?, brandMarkBundled: Bool) -> Bool {
        guard let clientID else { return false }
        return brandMarkBundled && GoogleSignIn.callbackScheme(clientID: clientID) != nil
    }
}

enum GoogleSignInError: Error, Equatable {
    /// The person closed the sheet or pressed Cancel. Not an error to show.
    case cancelled
    /// Google answered with `error=` (for example `access_denied`).
    case refused
    /// The client id does not have the shape Google issues for iOS.
    case misconfigured
    /// The system could not present the sign-in sheet.
    case couldNotStart
    /// The platform's secure random source failed.
    case randomUnavailable
}

/// Sign in with Google on iOS (SPEC §4.2; ADR-0035 constraints 2 to 4; D-132).
///
/// No Google sign-in SDK. The system's `ASWebAuthenticationSession` shows
/// Google's own page and returns an authorization code; the app exchanges it
/// with PKCE at Google's token endpoint and signs in to Firebase with the ID
/// token (D-191).
enum GoogleSignIn {
    static let authorizationEndpoint = URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!
    private static let clientSuffix = ".apps.googleusercontent.com"

    struct PKCE: Equatable {
        let verifier: String
        let challenge: String
    }

    /// RFC 7636 §4.2, S256: challenge = base64url(SHA-256(verifier)).
    static func makePKCE(verifier: String) -> PKCE {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return PKCE(verifier: verifier, challenge: base64url(Data(digest)))
    }

    /// `bytes` from the platform CSPRNG, base64url without padding. 32 bytes
    /// give a 43-character PKCE verifier (RFC 7636 §4.1's minimum length).
    static func randomToken(bytes: Int) throws -> String {
        var buffer = [UInt8](repeating: 0, count: bytes)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes, &buffer) == errSecSuccess else {
            throw GoogleSignInError.randomUnavailable
        }
        return base64url(Data(buffer))
    }

    /// `123-abc.apps.googleusercontent.com` → `com.googleusercontent.apps.123-abc`.
    /// The token request repeats the identical redirect, and the code exchange
    /// fails unless the two agree byte for byte.
    static func callbackScheme(clientID: String) -> String? {
        guard clientID.hasSuffix(clientSuffix) else { return nil }
        let prefix = clientID.dropLast(clientSuffix.count)
        guard !prefix.isEmpty,
              prefix.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") })
        else { return nil }
        return "com.googleusercontent.apps.\(prefix)"
    }

    static func redirectURI(clientID: String) -> String? {
        callbackScheme(clientID: clientID).map { "\($0):/oauth2redirect" }
    }

    /// The authorization request: the OpenID Connect code flow, scope
    /// `openid` and nothing else (ADR-0035 constraint 4).
    static func authorizationURL(clientID: String, pkce: PKCE, state: String, nonce: String) -> URL? {
        guard let redirect = redirectURI(clientID: clientID),
              var components = URLComponents(url: authorizationEndpoint, resolvingAgainstBaseURL: false)
        else { return nil }
        components.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirect),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: "openid"),
            URLQueryItem(name: "code_challenge", value: pkce.challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "nonce", value: nonce),
        ]
        return components.url
    }

    enum CallbackOutcome: Equatable {
        case code(String)
        case refused
        case stateMismatch
        case malformed
    }

    /// Reads the redirect Google sent back. The state is checked FIRST: a
    /// callback that does not carry this attempt's state is not trusted for
    /// anything, including its error.
    static func parseCallback(_ url: URL, expectedState: String, clientID: String) -> CallbackOutcome {
        guard let scheme = callbackScheme(clientID: clientID),
              url.scheme?.lowercased() == scheme.lowercased(),
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
        else { return .malformed }
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
        guard value("state") == expectedState else { return .stateMismatch }
        if value("error") != nil { return .refused }
        guard let code = value("code"), !code.isEmpty else { return .malformed }
        return .code(code)
    }

    static let tokenEndpoint = URL(string: "https://oauth2.googleapis.com/token")!

    /// RFC 7636 §4.5's token request for a public (iOS) client: the code, the
    /// verifier and the same redirect, and never a client secret. Nil for a
    /// client id that is not Google's iOS shape.
    static func tokenRequestBody(code: String, verifier: String, clientID: String) -> String? {
        guard let redirect = redirectURI(clientID: clientID) else { return nil }
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        func field(_ name: String, _ value: String) -> String {
            name + "=" + (value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")
        }
        return [
            field("grant_type", "authorization_code"),
            field("code", code),
            field("code_verifier", verifier),
            field("client_id", clientID),
            field("redirect_uri", redirect),
        ].joined(separator: "&")
    }

    /// The whole flow, from a tap (D-191): Google's sheet with PKCE, then the
    /// code exchanged at Google's token endpoint by the app, and the ID token
    /// handed to Firebase. Keeps no Google token.
    @MainActor
    static func credential(clientID: String) async throws -> AuthCredential {
        guard let scheme = callbackScheme(clientID: clientID) else {
            throw GoogleSignInError.misconfigured
        }
        let pkce = makePKCE(verifier: try randomToken(bytes: 32))
        let state = try randomToken(bytes: 16)
        let nonce = try randomToken(bytes: 16)
        guard let url = authorizationURL(clientID: clientID, pkce: pkce, state: state, nonce: nonce) else {
            throw GoogleSignInError.misconfigured
        }
        let callback = try await present(url: url, callbackScheme: scheme)
        switch parseCallback(callback, expectedState: state, clientID: clientID) {
        case .code(let code):
            let tokens = try await exchange(code: code, verifier: pkce.verifier, clientID: clientID)
            return GoogleAuthProvider.credential(withIDToken: tokens.idToken, accessToken: tokens.accessToken)
        case .refused:
            throw GoogleSignInError.refused
        case .stateMismatch, .malformed:
            throw GoogleSignInError.refused
        }
    }

    private struct TokenResponse: Decodable {
        let idToken: String
        let accessToken: String
        enum CodingKeys: String, CodingKey {
            case idToken = "id_token"
            case accessToken = "access_token"
        }
    }

    private static func exchange(code: String, verifier: String, clientID: String) async throws -> TokenResponse {
        guard let body = tokenRequestBody(code: code, verifier: verifier, clientID: clientID) else {
            throw GoogleSignInError.misconfigured
        }
        var request = URLRequest(url: tokenEndpoint)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(body.utf8)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let tokens = try? JSONDecoder().decode(TokenResponse.self, from: data)
        else { throw GoogleSignInError.refused }
        return tokens
    }

    /// The system sheet. Ephemeral: nothing is read from or left in Safari's
    /// cookie jar, at the cost of typing Google credentials each time.
    @MainActor
    private static func present(url: URL, callbackScheme: String) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: callbackScheme) { callback, error in
                Task { @MainActor in current = nil }
                if let callback {
                    continuation.resume(returning: callback)
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    continuation.resume(throwing: GoogleSignInError.cancelled)
                } else {
                    continuation.resume(throwing: error ?? GoogleSignInError.couldNotStart)
                }
            }
            session.presentationContextProvider = anchor
            session.prefersEphemeralWebBrowserSession = true
            current = session
            if !session.start() {
                current = nil
                continuation.resume(throwing: GoogleSignInError.couldNotStart)
            }
        }
    }

    /// The session must be held for as long as it is presented.
    @MainActor private static var current: ASWebAuthenticationSession?
    @MainActor private static let anchor = WindowAnchor()

    private final class WindowAnchor: NSObject, ASWebAuthenticationPresentationContextProviding {
        func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
            UIApplication.shared.connectedScenes
                .compactMap { ($0 as? UIWindowScene)?.keyWindow }
                .first ?? ASPresentationAnchor()
        }
    }

    static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

/// Google's own button (D-138): the "Sign in with Google" artwork from
/// Google's asset bundle, light and dark, used as supplied. Google's
/// branding rules forbid redrawing it, and the words are part of the image,
/// so the button needs no font (GUARANTEES #6: nothing is fetched to draw it).
/// D-188 (the founder, 2026-09-30: "bigger and rounder around the corners"):
/// Google's PILL art from the same bundle, scaled with its aspect kept (the
/// branding rules allow scaling; a redrawn button would need the Google Sans
/// font), 231 x 54 pt from 188 x 44, each scale filled from the next-larger
/// file so it stays crisp. SignInScreen gives Apple's button the same size,
/// because each company's rules require its button to be at least as
/// prominent as the other's.
struct GoogleSignInButton: View {
    static let size = CGSize(width: 231, height: 54)
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image("GoogleSignInButton")
                .resizable()
                .frame(width: Self.size.width, height: Self.size.height)
                .accessibilityHidden(true)
        }
        .buttonStyle(ProviderArtButtonStyle())
        .accessibilityLabel("Sign in with Google")
    }
}

/// For a button whose artwork belongs to someone else: it draws nothing of
/// its own, only dims on press and when disabled (D-128: a custom style must
/// read `isEnabled` inside a View to dim at all).
struct ProviderArtButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        ProviderArtButtonBody(configuration: configuration)
    }
}

private struct ProviderArtButtonBody: View {
    let configuration: ButtonStyle.Configuration
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        configuration.label
            .opacity(isEnabled ? (configuration.isPressed ? 0.75 : 1) : 0.45)
    }
}
