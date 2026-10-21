package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.skipti_core.ClaimOut
import uniffi.skipti_core.GroupWire
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.NamedGroupWire

/** D-207: the twin of iOS's BalanceLineTests.testRecentsAreYourOtherGroupsPeopleOnceEach. */
class RecentsTest {

    private fun g(id: String, name: String, vararg members: Pair<String, String>) = NamedGroupWire(
        GroupWire(id, name, "INR", members.map { it.first }),
        members[0].first,
        members.map { MemberWire(it.first, it.second) },
    )

    private fun c(member: String, you: String) = ClaimOut(member, you, emptyList())

    @Test
    fun recents_are_your_other_groups_people_once_each() {
        val groups = listOf(
            // Your own name differs between groups: the self rule, not the name
            // rule, keeps "Prithvi" out (D-207's first sabotage found the test
            // could not tell when every self was "Me").
            g("goa", "Goa", "gy" to "Prithvi", "gr" to "Rahul", "gm" to "Meera"),
            g("flat", "Flat", "fy" to "Me", "fr" to "Rahul", "fs" to "Sam"),
            g("paris", "Paris", "py" to "Me", "pa" to "Ana", "pr" to " rahul "),
            g("trip", "Trip", "ty" to "Me"),
        )
        val claims = mapOf("goa" to listOf(c("gr", "p1")), "flat" to listOf(c("fr", "p1"), c("fs", "p2")))
        val all = Recents.people(groups, claims, "trip")
        assertEquals(listOf("Rahul", "Sam", "Ana", "Meera"), all.map { it.name })
        assertEquals("p1", all[0].participantId)
        assertEquals(3, all[0].shared)
        assertEquals(listOf("Goa", "Flat"), all[0].groups)
        assertEquals("goa", all[0].viaGroupId)
        assertEquals("p2", all[1].participantId)
        assertNull(all[2].participantId)
        assertFalse("never yourself", all.any { it.name == "Me" || it.name == "Prithvi" })
        // Adding to Goa: Rahul (p1, and by name) and Meera are already there.
        assertEquals(listOf("Sam", "Ana"), Recents.people(groups, claims, "goa").map { it.name })
        assertEquals(listOf("Rahul", "Sam"), Recents.people(groups, claims, "trip", limit = 2).map { it.name })
    }

    /** D-220 fix wave (F6): a recent row that cannot be tapped (busy, or added) is drawn at half strength, card and chip
     *  included, as iOS's disabled row (0.494 measured); a live one at full. */
    @Test
    fun a_recent_row_dims_while_it_cannot_be_tapped() {
        assertEquals(0.5f, RecentRow.DIMMED_ALPHA, 0f)
        assertEquals(1f, RecentRow.alpha(busy = false, added = false), 0f)
        assertEquals(0.5f, RecentRow.alpha(busy = false, added = true), 0f)
        assertEquals(0.5f, RecentRow.alpha(busy = true, added = false), 0f)
        assertFalse(RecentRow.enabled(busy = true, added = false))
        assertFalse(RecentRow.enabled(busy = false, added = true))
        org.junit.Assert.assertTrue(RecentRow.enabled(busy = false, added = false))
        val people = java.io.File("src/main/java/com/vardrlabs/skipti/People.kt").readText()
        // The whole row dims: the alpha comes before its card.
        org.junit.Assert.assertTrue(
            Regex("""\.alpha\(RecentRow\.alpha\(busy, isAdded\)\)\s*\.glassCard\(dark, padding = 10\)\s*\.clickable\(enabled = RecentRow\.enabled\(busy, isAdded\)\)""").containsMatchIn(people),
        )
        // iOS's twin: the row's button is disabled while busy or added.
        val ios = java.io.File("../../ios/Skipti/App/People.swift").readText()
        org.junit.Assert.assertTrue(ios.contains(".disabled(busy || isAdded)"))
    }
}
