package com.vardrlabs.skipti

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PLAY build carries Google's sign-in library (ADR-0035 constraint 5;
 * D-133). D-191 retired the direct flavour and its twin of this class.
 */
class GoogleFlavourTest {
    @Test
    fun this_build_carries_googles_sign_in_classes() {
        assertTrue(GoogleCredential.SUPPORTED)
        val loader = javaClass.classLoader
        for (name in GOOGLE_CLASSES) {
            // initialize = false: prove presence without running static
            // initialisers that would touch the Android framework stubs.
            Class.forName(name, false, loader)
        }
    }

    /** D-138: this build offers Google, and carries Google's button art. */
    @Test
    fun this_build_offers_google_with_googles_art() {
        assertTrue(GoogleCredential.BRAND_MARK_BUNDLED)
        assertTrue(GoogleSignInConfig.isAvailable)
        R.drawable::class.java.getField("google_sign_in")
    }

    /** D-172: Credential Manager reports a sign-in Google refused after an
     *  account was picked as a cancellation. It is said, never swallowed
     *  (the founder's Play build closed the sheet with no word, 2026-09-26),
     *  and Google's round G ships for Settings. */
    @Test
    fun a_closed_google_sheet_is_said_not_swallowed() {
        val closed = GoogleCredential.failureFor(GetCredentialCancellationException("activity is cancelled by the user."))
        assertEquals(GoogleCredential.CLOSED, closed.message)
        assertEquals(GoogleCredential.NO_ACCOUNT, GoogleCredential.failureFor(NoCredentialException()).message)
        assertEquals(GoogleCredential.UNAVAILABLE, GoogleCredential.failureFor(GetCredentialUnknownException()).message)
        R.drawable::class.java.getField("google_mark")
    }

    /** D-177: Google's own reason travels with the sentence, so a tester can
     *  read it on the phone; a message-less failure adds nothing. */
    @Test
    fun googles_reason_is_shown_under_the_failure() {
        val refused = GoogleCredential.failureFor(GetCredentialCancellationException("  [16] Account reauth failed. "))
        assertEquals(GoogleCredential.CLOSED, refused.message)
        assertEquals("Google's reason: [16] Account reauth failed.", refused.detail)
        assertEquals(null, GoogleCredential.failureFor(NoCredentialException()).detail)
        assertEquals(null, GoogleCredential.failureFor(GetCredentialUnknownException("   ")).detail)
    }

    private companion object {
        val GOOGLE_CLASSES = listOf(
            "com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption",
            "com.google.android.libraries.identity.googleid.GoogleIdTokenCredential",
        )
    }
}
