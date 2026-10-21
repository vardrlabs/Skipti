package com.vardrlabs.skipti

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sign in with Google on Android (ADR-0035; D-133; D-191: through Firebase):
 * everything provable without Google: the button's availability rule for every
 * combination, the client Firebase accepts, and the page's words.
 */
class GoogleSignInTest {

    private val web = "1234567890-web.apps.googleusercontent.com"

    @Test
    fun google_is_offered_only_with_this_build_a_client_id_and_googles_mark() {
        assertFalse(GoogleSignInConfig.isAvailable(buildSupports = false, webClientId = web, brandMarkBundled = true))
        assertFalse(GoogleSignInConfig.isAvailable(buildSupports = true, webClientId = null, brandMarkBundled = true))
        assertFalse(GoogleSignInConfig.isAvailable(buildSupports = true, webClientId = web, brandMarkBundled = false))
        assertFalse(GoogleSignInConfig.isAvailable(buildSupports = true, webClientId = "not-a-client-id", brandMarkBundled = true))
        assertFalse(GoogleSignInConfig.isAvailable(buildSupports = true, webClientId = ".apps.googleusercontent.com", brandMarkBundled = true))
        assertFalse(GoogleSignInConfig.isAvailable(buildSupports = true, webClientId = "12:/x.apps.googleusercontent.com", brandMarkBundled = true))
        assertTrue(GoogleSignInConfig.isAvailable(buildSupports = true, webClientId = web, brandMarkBundled = true))
        // Whether THIS build offers it differs by flavour: GoogleFlavourTest.
    }

    /** D-191: Firebase's auto-created web client in skipti-26, copied by hand
     *  from `firebase apps:sdkconfig ANDROID` (client_type 3). Firebase accepts
     *  Google ID tokens with this audience. */
    @Test
    fun this_build_uses_firebases_web_client() {
        assertEquals(
            "403030719865-p4a2s68o0rn18rn37h2v4pq110hih40o.apps.googleusercontent.com",
            GoogleSignInConfig.WEB_CLIENT_ID,
        )
    }


    /** D-188: the sign-in page's words (twin of iOS's ProviderSignInTests). */
    @Test
    fun the_sign_in_page_words_match_ios() {
        assertEquals("Your ledger, on every phone", SignInCopy.HERO_TITLE)
        assertEquals("Signing in adds sync and groups. SKIPTI keeps working on this phone without it.", SignInCopy.HERO_BODY)
        assertEquals("What happens when you sign in", SignInCopy.STEPS_TITLE)
        val rest = listOf(
            "Your ledger syncs to your other phones through SKIPTI's cloud, which runs on Google Firebase.",
            "You can make and join groups. Only the people in a group can see it.",
        )
        assertEquals(listOf("Apple or Google confirms it is you.") + rest, SignInCopy.steps(apple = true, google = true))
        assertEquals(listOf("Apple confirms it is you.") + rest, SignInCopy.steps(apple = true, google = false))
        assertEquals(listOf("Google confirms it is you.") + rest, SignInCopy.steps(apple = false, google = true))
    }
}
