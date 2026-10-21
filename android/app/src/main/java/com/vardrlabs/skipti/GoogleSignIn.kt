package com.vardrlabs.skipti

import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.GoogleAuthProvider
import java.security.SecureRandom

import android.content.Context

/**
 * Whether Sign in with Google can be offered in this build (ADR-0035; D-133),
 * the Android twin of iOS's GoogleSignInConfig.
 */
object GoogleSignInConfig {
    /** D-191: Firebase's web client (project skipti-26). Credential Manager
     *  sends it as `serverClientId`, and it is the audience Firebase accepts.
     *  Public, not a secret. */
    val WEB_CLIENT_ID: String? = FirebaseConfig.WEB_CLIENT_ID

    /** Google's branding rules require its official button artwork, taken
     *  from Google's asset bundle rather than redrawn. Only the play build
     *  carries it (D-138: `google_sign_in.png` in `src/play/res`, per density). */
    val BRAND_MARK_BUNDLED: Boolean = GoogleCredential.BRAND_MARK_BUNDLED

    private const val CLIENT_SUFFIX = ".apps.googleusercontent.com"

    val isAvailable: Boolean
        get() = isAvailable(GoogleCredential.SUPPORTED, WEB_CLIENT_ID, BRAND_MARK_BUNDLED)

    /** Pure, so the tests can pin every combination. */
    fun isAvailable(buildSupports: Boolean, webClientId: String?, brandMarkBundled: Boolean): Boolean {
        if (!buildSupports || !brandMarkBundled || webClientId == null) return false
        val prefix = webClientId.removeSuffix(CLIENT_SUFFIX)
        return prefix != webClientId && prefix.isNotEmpty() &&
            prefix.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }
    }
}

/**
 * The whole flow, from a tap (D-191): Google's sheet through Credential
 * Manager with a random nonce, then the ID token as a Firebase credential.
 * Keeps no Google token.
 */
suspend fun googleCredential(context: Context, webClientId: String): AuthCredential {
    val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    val idToken = GoogleCredential(context).idToken(webClientId, nonce)
    return GoogleAuthProvider.getCredential(idToken, null)
}

/** The person closed the provider's sheet: not an error to show. */
class CeremonyCancelled : Exception("cancelled")

/**
 * The DEVICE could not run the sign-in sheet: a local capability, so the copy
 * may say why.
 */
class CeremonyUnavailable(
    message: String,
    cause: Exception? = null,
    /** D-177: the platform's own words, shown under [message] when present. */
    val detail: String? = null,
) : Exception(message, cause)
