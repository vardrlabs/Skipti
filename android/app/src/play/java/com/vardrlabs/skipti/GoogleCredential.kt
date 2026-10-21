package com.vardrlabs.skipti

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sign in with Google, PLAY build (SPEC §4.2; ADR-0035; D-133).
 *
 * Google's own sheet, shown by Credential Manager through Google Play
 * services, and only ever from a tap (ADR-0035 constraint 1). The request
 * carries the relay's single-use nonce, and Google returns an ID token that
 * the relay verifies against it. The app's own requests go to the relay alone,
 * and no Google token is kept.
 *
 * The direct flavour has a file with the same name and shape that carries
 * no Google library at all (ADR-0010, ADR-0035 constraint 5).
 */
class GoogleCredential(private val context: Context) {

    suspend fun idToken(serverClientId: String, nonce: String): String =
        withContext(Dispatchers.IO) {
            val manager = CredentialManager.create(context)
            val credential = try {
                manager.getCredential(
                    context,
                    GetCredentialRequest(listOf(signInOption(serverClientId, nonce))),
                ).credential
            } catch (e: GetCredentialException) {
                Log.i("GoogleCredential", "getCredential failed: ${e.type}: ${e.errorMessage}")
                throw failureFor(e)
            }
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                throw CeremonyUnavailable("Google did not return a sign-in.")
            }
            try {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } catch (e: GoogleIdTokenParsingException) {
                throw local(e, "Google's answer could not be read.")
            }
        }

    private fun local(e: Exception, message: String): CeremonyUnavailable {
        Log.i("GoogleCredential", "unavailable: ${e.javaClass.simpleName}")
        return CeremonyUnavailable(message, e)
    }

    companion object {
        const val CLOSED = "Google closed without signing you in."
        const val NO_ACCOUNT = "No Google account is available on this device."
        const val UNAVAILABLE = "This device could not show Google's sign-in."

        /**
         * D-172: a cancellation is NOT silent. Credential Manager reports a
         * sign-in that Google refused after an account was picked (this app's
         * package and signing key not registered with Google, or the account
         * not a test user of a consent screen still in Testing) as the same
         * cancellation as a dismissed sheet. Treating it as a non-event hid
         * the failure: the founder picked his account on the Play build and
         * the sheet just closed (2026-09-26). The logcat line above carries
         * Google's own reason.
         */
        fun failureFor(e: GetCredentialException): CeremonyUnavailable = when (e) {
            is GetCredentialCancellationException -> CeremonyUnavailable(CLOSED, e, reasonFor(e))
            is NoCredentialException -> CeremonyUnavailable(NO_ACCOUNT, e, reasonFor(e))
            else -> CeremonyUnavailable(UNAVAILABLE, e, reasonFor(e))
        }

        /**
         * D-177: Google's own words, shown under the sentence above. The
         * founder's Play build on his LG said only CLOSED, and the reason
         * (e.g. "[16] Account reauth failed.") was in a logcat he could not
         * easily reach (2026-09-29). It is Play services describing the
         * device and the app's registration: no ledger data, no token.
         */
        fun reasonFor(e: GetCredentialException): String? =
            e.errorMessage?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { "Google's reason: $it" }

        /** This build carries Google's sign-in library. */
        const val SUPPORTED = true

        /** Google's button art ships in this build's res (D-138). */
        const val BRAND_MARK_BUNDLED = true

        /** The button flow's option: the Web client id as `serverClientId`
         *  (the audience the relay checks) and the relay's nonce. */
        fun signInOption(serverClientId: String, nonce: String): GetSignInWithGoogleOption =
            GetSignInWithGoogleOption.Builder(serverClientId)
                .setNonce(nonce)
                .build()
    }
}
