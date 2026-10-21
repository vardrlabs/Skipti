import AuthenticationServices
import UIKit
import XCTest
@testable import Skipti

/// Sign in with Apple and Google on iOS (ADR-0035; D-132): everything that can
/// be proven without Apple's or Google's servers. Goldens are written out by
/// hand or taken from the RFC, never read back from the code under test.
final class ProviderSignInTests: XCTestCase {
    private let clientID = "1234567890-ios.apps.googleusercontent.com"

    // MARK: PKCE and random tokens

    /// RFC 7636 Appendix B's example, re-derived independently (Python
    /// hashlib) before this test was written.
    /// D-191: sign-in is Apple and Google through Firebase; Google is
    /// offered on iPhone. Android's twin is in GoogleSignInTest.
    func testGoogleIsOfferedOnIPhone() {
        XCTAssertTrue(GoogleSignInConfig.isAvailable)
        XCTAssertEqual(GoogleSignInConfig.iosClientID, FirebaseConfig.iosClientID)
    }

    func testPKCEChallengeMatchesRFC7636AppendixB() {
        let pkce = GoogleSignIn.makePKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        XCTAssertEqual(pkce.challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    func testRandomTokensAreUnpaddedBase64urlAndDistinct() throws {
        let allowed = CharacterSet(charactersIn:
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        let verifier = try GoogleSignIn.randomToken(bytes: 32)
        XCTAssertEqual(verifier.count, 43, "32 bytes: RFC 7636's minimum verifier length")
        XCTAssertTrue(verifier.unicodeScalars.allSatisfy(allowed.contains))
        XCTAssertEqual(try GoogleSignIn.randomToken(bytes: 16).count, 22)
        XCTAssertNotEqual(verifier, try GoogleSignIn.randomToken(bytes: 32))
    }

    // MARK: Redirect and authorization request

    /// Written out by hand: the same string the relay test pins
    /// (`server/tests/google_http.rs` IOS_REDIRECT). If the two ever differ,
    /// Google refuses the relay's code exchange.
    func testRedirectMatchesTheRelaysDerivation() {
        XCTAssertEqual(
            GoogleSignIn.redirectURI(clientID: clientID),
            "com.googleusercontent.apps.1234567890-ios:/oauth2redirect"
        )
        XCTAssertNil(GoogleSignIn.redirectURI(clientID: "1234567890-ios.example.com"))
        XCTAssertNil(GoogleSignIn.redirectURI(clientID: ".apps.googleusercontent.com"))
        XCTAssertNil(GoogleSignIn.redirectURI(clientID: "123:/evil.apps.googleusercontent.com"))
    }

    func testAuthorizationRequestIsTheOpenIDCodeFlowAndNothingMore() throws {
        let verifier = "VERIFIER-must-stay-on-the-device-0123456789"
        let pkce = GoogleSignIn.PKCE(verifier: verifier, challenge: "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        let url = try XCTUnwrap(GoogleSignIn.authorizationURL(
            clientID: clientID, pkce: pkce, state: "st4te", nonce: "n0nce"
        ))
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.scheme, "https")
        XCTAssertEqual(components.host, "accounts.google.com")
        XCTAssertEqual(components.path, "/o/oauth2/v2/auth")
        let items = try XCTUnwrap(components.queryItems)
        XCTAssertEqual(items.count, 8, "no parameter beyond the eight below")
        XCTAssertEqual(
            Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") }),
            [
                "client_id": clientID,
                "redirect_uri": "com.googleusercontent.apps.1234567890-ios:/oauth2redirect",
                "response_type": "code",
                "scope": "openid",
                "code_challenge": "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "code_challenge_method": "S256",
                "state": "st4te",
                "nonce": "n0nce",
            ]
        )
        XCTAssertFalse(url.absoluteString.contains(verifier), "the verifier itself never goes to Google")
    }

    // MARK: Callback

    func testCallbackParsing() throws {
        let scheme = "com.googleusercontent.apps.1234567890-ios:/oauth2redirect"
        func parse(_ query: String) throws -> GoogleSignIn.CallbackOutcome {
            GoogleSignIn.parseCallback(
                try XCTUnwrap(URL(string: "\(scheme)?\(query)")),
                expectedState: "st4te", clientID: clientID
            )
        }
        XCTAssertEqual(try parse("state=st4te&code=4/0Acode"), .code("4/0Acode"))
        XCTAssertEqual(try parse("state=other&code=4/0Acode"), .stateMismatch)
        XCTAssertEqual(try parse("code=4/0Acode"), .stateMismatch, "no state at all")
        XCTAssertEqual(try parse("state=st4te&error=access_denied"), .refused)
        XCTAssertEqual(try parse("state=other&error=access_denied"), .stateMismatch,
                       "an error without this attempt's state is not trusted either")
        XCTAssertEqual(try parse("state=st4te"), .malformed)
        XCTAssertEqual(try parse("state=st4te&code="), .malformed)
        XCTAssertEqual(
            GoogleSignIn.parseCallback(
                try XCTUnwrap(URL(string: "com.evil.app:/oauth2redirect?state=st4te&code=x")),
                expectedState: "st4te", clientID: clientID
            ),
            .malformed, "another app's scheme"
        )
    }

    // MARK: Availability

    func testGoogleIsOfferedOnlyWithAClientIdAndGooglesMark() {
        XCTAssertFalse(GoogleSignInConfig.isAvailable(clientID: nil, brandMarkBundled: true))
        XCTAssertFalse(GoogleSignInConfig.isAvailable(clientID: clientID, brandMarkBundled: false))
        XCTAssertFalse(GoogleSignInConfig.isAvailable(clientID: "not-a-client-id", brandMarkBundled: true))
        XCTAssertTrue(GoogleSignInConfig.isAvailable(clientID: clientID, brandMarkBundled: true))
        XCTAssertTrue(GoogleSignInConfig.isAvailable, "this build: the iOS client id and Google's art (D-138)")
    }

    /// D-138: the client the founder created in Google Cloud on 2026-09-18,
    /// copied by hand from what he sent (and from the relay's unit file), with
    /// the redirect written out by hand. The relay derives the same redirect
    /// from SKIPTI_GOOGLE_IOS_CLIENT_ID; a different id here means Google
    /// refuses the code exchange.
    func testThisBuildUsesTheIssuedIOSClient() {
        XCTAssertEqual(
            GoogleSignInConfig.iosClientID,
            "403030719865-pmgfvj4eo4l73bao8lqblqmpera4augj.apps.googleusercontent.com"
        )
        XCTAssertEqual(
            GoogleSignInConfig.iosClientID.flatMap(GoogleSignIn.redirectURI(clientID:)),
            "com.googleusercontent.apps.403030719865-pmgfvj4eo4l73bao8lqblqmpera4augj:/oauth2redirect"
        )
    }

    /// D-138: Google's art is bundled, and the dark appearance gets Google's
    /// dark button, not the light one. D-188: the pill art, shown larger with
    /// Google's own aspect (188:44) kept, and with pixels enough to be crisp
    /// at the size it is shown on this screen.
    func testGoogleButtonArtIsBundledForLightAndDark() throws {
        let light = try XCTUnwrap(UIImage(
            named: "GoogleSignInButton", in: .main,
            compatibleWith: UITraitCollection(userInterfaceStyle: .light)
        ))
        let dark = try XCTUnwrap(UIImage(
            named: "GoogleSignInButton", in: .main,
            compatibleWith: UITraitCollection(userInterfaceStyle: .dark)
        ))
        let google = 188.0 / 44.0
        XCTAssertEqual(light.size.width / light.size.height, google, accuracy: 0.01)
        XCTAssertEqual(dark.size.width / dark.size.height, google, accuracy: 0.01)
        let shown = GoogleSignInButton.size
        XCTAssertEqual(shown.width / shown.height, google, accuracy: 0.01, "never stretched")
        XCTAssertGreaterThan(shown.height, 44, "bigger than the art's native size")
        XCTAssertGreaterThanOrEqual(light.size.width * light.scale, shown.width * UIScreen.main.scale, "crisp")
        XCTAssertNotEqual(light.pngData(), dark.pngData(), "dark must be Google's dark art")
    }

    // MARK: Apple

    func testAppleRequestAsksForNothingAndCarriesTheHashedNonce() {
        let request = AppleSignIn.request(hashedNonce: AppleSignIn.sha256Hex("n0nce"))
        XCTAssertEqual(request.requestedScopes ?? [], [], "no name, no email")
        XCTAssertEqual(request.nonce, AppleSignIn.sha256Hex("n0nce"), "Firebase compares the raw nonce's hash")
        XCTAssertNotEqual(request.nonce, "n0nce")
    }

    func testOnlyApplesCancelIsSilent() {
        XCTAssertTrue(AppleSignIn.isCancellation(ASAuthorizationError(.canceled)))
        XCTAssertFalse(AppleSignIn.isCancellation(ASAuthorizationError(.failed)))
        XCTAssertFalse(AppleSignIn.isCancellation(URLError(.notConnectedToInternet)))
    }

    // MARK: D-188 the sign-in page's words (twin of GoogleSignInTest's)

    func testTheSignInPageWordsMatchAndroid() {
        XCTAssertEqual(SignInCopy.heroTitle, "Your ledger, on every phone")
        XCTAssertEqual(SignInCopy.heroBody, "Signing in adds sync and groups. SKIPTI keeps working on this phone without it.")
        XCTAssertEqual(SignInCopy.stepsTitle, "What happens when you sign in")
        let rest = ["Your ledger syncs to your other phones through SKIPTI's cloud, which runs on Google Firebase.",
                    "You can make and join groups. Only the people in a group can see it."]
        XCTAssertEqual(SignInCopy.steps(apple: true, google: true), ["Apple or Google confirms it is you."] + rest)
        XCTAssertEqual(SignInCopy.steps(apple: true, google: false), ["Apple confirms it is you."] + rest)
        XCTAssertEqual(SignInCopy.steps(apple: false, google: true), ["Google confirms it is you."] + rest)
    }
}
