package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** C2: the twin of iOS's UsernameRulesTests (same list file, same goldens). */
class UsernameRulesTest {
    private val list = UsernameRules.parse(
        File(System.getProperty("user.dir")!!).resolve("../../firebase/functions/blocklist/usernames.json").readText(),
    )
    private val allowed = listOf("classic", "peacock", "grape", "assam", "method", "prithvi_saha", "kshitij", "nazir",
        "therapist", "badminton", "pineapple", "spicy", "bobsled", "tunnel", "dallas", "brandi", "sunni_muslim",
        "team_rocket", "me_and_you", "supportive_sam", "pakistani", "hello", "@Priya_92")
    private val refused = listOf("scunthorpe", "f_u_c_k", "fuuuck", "b1tch", "dick69", "support", "supp0rt",
        "tech_support", "skipti_fan", "x_spic_99", "n4zi", "sh1t", "bullsh1t", "booooobs", "help", "x1488x", "asss",
        "vardr_labs", "SKIPTI")
    private val badShape = listOf("me", "ab", "pri ya", "a".repeat(21))

    @Test
    fun the_goldens() {
        allowed.forEach { assertNull(it, UsernameRules.problem(it, list)) }
        refused.forEach { assertEquals(it, UsernameRules.Problem.UNAVAILABLE, UsernameRules.problem(it, list)) }
        badShape.forEach { assertEquals(it, UsernameRules.Problem.SHAPE, UsernameRules.problem(it, list)) }
    }

    @Test
    fun a_terms_letters_may_repeat_but_never_shrink() {
        assertEquals("b+o{2,}b+s+", UsernameRules.pattern("boobs"))
        assertEquals("a{2,}i+g+h+a+l+", UsernameRules.pattern("aai_ghal"))
    }

    @Test
    fun the_accounts_username_state() {
        assertNull(UsernameState.from(null))
        assertEquals(UsernameState.None, UsernameState.from(emptyMap()))
        assertEquals(UsernameState.CanChange("al"), UsernameState.from(mapOf("username" to "al")))
        assertEquals(UsernameState.CanChange("al"), UsernameState.from(mapOf("username" to "al", "usernameChanged" to false)))
        assertEquals(UsernameState.Final("al"), UsernameState.from(mapOf("username" to "al", "usernameChanged" to true)))
        assertEquals(UsernameState.None, UsernameState.from(mapOf("username" to "")))
    }

    @Test
    fun a_stale_check_never_enables_save() {
        // The cloud said "alice" is free, but the field now holds "al".
        assertNull(UsernameStatus.acceptedHandle(UsernameStatus.Available("alice"), "al"))
        assertEquals("alice", UsernameStatus.acceptedHandle(UsernameStatus.Available("alice"), "@Alice "))
        assertEquals("alice", UsernameStatus.acceptedHandle(UsernameStatus.Available("alice"), "alice"))
        listOf(
            UsernameStatus.Checking, UsernameStatus.Taken("alice"), UsernameStatus.Hint,
            UsernameStatus.NotAvailable, UsernameStatus.Offline, UsernameStatus.Limit,
        ).forEach { assertNull(it.toString(), UsernameStatus.acceptedHandle(it, "alice")) }
    }

    @Test
    fun a_known_username_is_never_forgotten_on_a_failed_read() {
        val can = UsernameState.CanChange("x")
        assertNull(UsernameState.merge(known = can, read = can, signedIn = false))
        assertNull(UsernameState.merge(known = can, read = null, signedIn = false))
        assertEquals(can, UsernameState.merge(known = can, read = null, signedIn = true))
        assertEquals(UsernameState.None, UsernameState.merge(known = UsernameState.None, read = null, signedIn = true))
        assertEquals(UsernameState.None, UsernameState.merge(known = null, read = UsernameState.None, signedIn = true))
        assertEquals(UsernameState.Final("x"), UsernameState.merge(known = UsernameState.None, read = UsernameState.Final("x"), signedIn = true))
        assertNull(UsernameState.merge(known = null, read = null, signedIn = true))
    }

    @Test
    fun only_an_account_without_a_username_gets_the_step() {
        assertFalse(UsernameState.needsStep(null))
        assertTrue(UsernameState.needsStep(UsernameState.None))
        assertFalse(UsernameState.needsStep(UsernameState.CanChange("x")))
        assertFalse(UsernameState.needsStep(UsernameState.Final("x")))
    }

    @Test
    fun what_the_phone_says_before_asking_the_cloud() {
        assertEquals(UsernameStatus.Hint, UsernameStatus.local(""))
        assertEquals(UsernameStatus.Hint, UsernameStatus.local("@ab"))
        assertEquals(UsernameStatus.Hint, UsernameStatus.local("pri ya"))
        // Local only knows the shape without the bundled list; asking the
        // cloud is the rest.
        assertNull(UsernameStatus.local("@Priya_92"))
    }

    @Test
    fun a_handle_is_trimmed_of_newlines_as_the_cloud_does() {
        assertEquals("al", UsernameRules.handle("@Al\n"))
        assertEquals("al_7", UsernameRules.handle("\t al_7 \r\n"))
        assertEquals("priya", UsernameRules.handle(" @Priya "))
    }

    @Test
    fun the_local_status_takes_the_list_it_is_given() {
        assertEquals(UsernameStatus.NotAvailable, UsernameStatus.local("sh1t", list))
        assertEquals(UsernameStatus.Hint, UsernameStatus.local("ab", list))
        assertEquals(UsernameStatus.Hint, UsernameStatus.local("ab"))
        assertNull("free: only the cloud can say taken", UsernameStatus.local("bobsled", list))
        // Without a list only the shape is known: the cloud decides the rest.
        assertNull(UsernameStatus.local("sh1t", null))
    }

    @Test
    fun the_list_is_compiled_once_with_every_term() {
        assertEquals(list.reserved.size, list.reservedPatterns.size)
        assertEquals(list.anywhere.size, list.anywherePatterns.size)
        assertEquals(list.whole.size, list.wholePatterns.size)
        // The same objects on every call: compiled once per loaded list.
        assertTrue(list.reservedPatterns === list.reservedPatterns)
        refused.forEach { assertEquals(it, UsernameRules.Problem.UNAVAILABLE, UsernameRules.problem(it, list)) }
    }

    @Test
    fun the_change_screen_never_offers_the_current_handle() {
        assertEquals(
            listOf("prithvisaha", "prithvi_saha"),
            UsernameRules.changeSuggestions(listOf("prithvi", "prithvisaha", "prithvi_saha"), current = "prithvi"),
        )
        assertEquals(listOf("al26", "its_al"), UsernameRules.changeSuggestions(listOf("al26", "its_al"), current = "bo"))
        assertEquals(emptyList<String>(), UsernameRules.changeSuggestions(emptyList(), current = "bo"))
    }

    @Test
    fun the_daily_limit_is_said_as_the_limit_not_as_offline() {
        val limit = CloudFunctions.Failure.Refused("RESOURCE_EXHAUSTED")
        assertEquals(UsernameStatus.Limit, UsernameStatus.failedCheck(limit))
        assertEquals(UsernameStatus.Offline, UsernameStatus.failedCheck(CloudFunctions.Failure.Offline()))
        assertEquals(UsernameStatus.Offline, UsernameStatus.failedCheck(CloudFunctions.Failure.Refused("UNAUTHENTICATED")))
        assertEquals(UsernameSaveFailure.Message(PeopleCopy.LIMIT), UsernameSaveFailure.from(limit))
        assertEquals(UsernameSaveFailure.Message(UsernameCopy.OFFLINE), UsernameSaveFailure.from(CloudFunctions.Failure.Offline()))
        assertEquals(UsernameSaveFailure.Message(UsernameCopy.OFFLINE), UsernameSaveFailure.from(CloudFunctions.Failure.SignedOut()))
        assertEquals(UsernameSaveFailure.Taken, UsernameSaveFailure.from(CloudFunctions.Failure.Refused("ALREADY_EXISTS")))
        assertEquals(UsernameSaveFailure.NotAvailable, UsernameSaveFailure.from(CloudFunctions.Failure.Refused("INVALID_ARGUMENT")))
        assertEquals(UsernameSaveFailure.Used, UsernameSaveFailure.from(CloudFunctions.Failure.Refused("FAILED_PRECONDITION")))
        assertFalse(PeopleCopy.LIMIT == UsernameCopy.OFFLINE)
    }

    @Test
    fun later_is_offered_only_after_a_save_failed_for_a_reason_the_field_cannot_fix() {
        assertEquals("Later", UsernameCopy.LATER)
        assertFalse("never before a save failed", UsernameSaveFailure.offersLater(null))
        assertFalse("the field says what to do", UsernameSaveFailure.offersLater(UsernameSaveFailure.Taken))
        assertFalse("the field says what to do", UsernameSaveFailure.offersLater(UsernameSaveFailure.NotAvailable))
        assertTrue(UsernameSaveFailure.offersLater(UsernameSaveFailure.Message(UsernameCopy.OFFLINE)))
        assertTrue(UsernameSaveFailure.offersLater(UsernameSaveFailure.Message(PeopleCopy.LIMIT)))
        assertTrue(UsernameSaveFailure.offersLater(UsernameSaveFailure.Used))
    }

    @Test
    fun the_step_can_save_when_only_the_live_check_failed() {
        assertEquals("alice", UsernameStatus.canSave(UsernameStatus.Available("alice"), "@Alice ", list))
        assertEquals("bo_7", UsernameStatus.canSave(UsernameStatus.Offline, " @Bo_7", list))
        assertEquals("bo_7", UsernameStatus.canSave(UsernameStatus.Limit, "bo_7", list))
        assertNull("blocked on the phone", UsernameStatus.canSave(UsernameStatus.Offline, "sh1t", list))
        assertNull("the wrong shape", UsernameStatus.canSave(UsernameStatus.Limit, "ab", list))
        listOf(UsernameStatus.Hint, UsernameStatus.Checking, UsernameStatus.Taken("bo_7"), UsernameStatus.NotAvailable)
            .forEach { assertNull(it.toString(), UsernameStatus.canSave(it, "bo_7", list)) }
        assertNull("an older text's answer", UsernameStatus.canSave(UsernameStatus.Available("alice"), "al", list))
    }

    @Test
    fun a_signed_out_account_never_sees_the_step() {
        assertEquals(SignInStep.FORM, SignInStep.decide(signedIn = false, needed = true))
        assertEquals(SignInStep.FORM, SignInStep.decide(signedIn = false, needed = false))
        assertEquals(SignInStep.FORM, SignInStep.decide(signedIn = false, needed = null))
        assertEquals(SignInStep.WAITING, SignInStep.decide(signedIn = true, needed = null))
        assertEquals(SignInStep.STEP, SignInStep.decide(signedIn = true, needed = true))
        assertEquals(SignInStep.CONFIRMATION, SignInStep.decide(signedIn = true, needed = false))
    }

    @Test
    fun the_username_routes_go_back_where_they_were_opened() {
        assertNull(HomeRoutes.parent("username"))
        assertEquals("account", HomeRoutes.parent("username/you"))
        assertEquals("account/profile", HomeRoutes.parent("username/find-me"))
        assertEquals("account/profile", HomeRoutes.parent("username/change/priya_92"))
    }

    /** The copy is iOS's, character for character: every string here is a
     *  literal in UsernameStep.swift (a function is rendered with Swift's own
     *  interpolations as its arguments). */
    @Test
    fun the_copy_is_the_same_as_ios() {
        val swift = File(System.getProperty("user.dir")!!).resolve("../../ios/Skipti/App/UsernameStep.swift").readText()
        val h = "\\(h)"
        val strings = listOf(
            UsernameCopy.TITLE, UsernameCopy.LEDE, UsernameCopy.YOUR_NAME, UsernameCopy.NAME_PROMPT,
            UsernameCopy.USERNAME, UsernameCopy.PROMPT, UsernameCopy.HINT, UsernameCopy.CHECKING,
            UsernameCopy.available(h), UsernameCopy.taken(h), UsernameCopy.NOT_AVAILABLE, UsernameCopy.SUGGESTED,
            UsernameCopy.SAVE, UsernameCopy.OFFLINE, UsernameCopy.confirmTitle(h),
            UsernameCopy.confirmBody(new = "\\(new)", old = "\\(old)"), UsernameCopy.CHANGE, UsernameCopy.keep(h),
            UsernameCopy.ONE_CHANGE_LEFT, UsernameCopy.CHANGED_ONCE, UsernameCopy.CARD_TITLE, UsernameCopy.CARD_BODY,
            UsernameCopy.CARD_ACTION, UsernameCopy.CANCEL, UsernameCopy.LATER,
        )
        strings.forEach { assertTrue(it, swift.contains("\"$it\"")) }
        // Every static string of iOS's UsernameCopy has its Kotlin twin.
        val ios = Regex("static (?:let|func) \\w+[^\"]*\"([^\"]*)\"").findAll(
            swift.substringAfter("enum UsernameCopy {").substringBefore("\n}\n"),
        ).map { it.groupValues[1] }.toList()
        assertEquals(strings.size, ios.size)
        assertEquals(ios.toSet(), strings.toSet())
    }
}
