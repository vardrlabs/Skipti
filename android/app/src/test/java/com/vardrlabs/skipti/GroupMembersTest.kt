package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.ClaimDeviceIn
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import java.nio.file.Files

/**
 * D-186: taking a person out of a group (the founder, 2026-09-30: "you can't
 * cross their name once you press add another person"; only someone with no
 * history). Through the real core and a real vault. The twin of iOS's
 * GroupMembersTests (in GroupSyncTests.swift).
 */
class GroupMembersTest {

    private val dir = Files.createTempDirectory("group-members")

    private fun device(name: String): LedgerStore {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = dir.resolve("$name.db").toString()
        return LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    private fun idOf(g: NamedGroupWire, name: String) = g.members.single { it.name == name }.id

    /** You never; someone in an expense never; someone in nothing, yes, and
     *  then they are gone from the people every screen offers while the
     *  group's records still hold them, on this phone and on the next. */
    @Test
    fun only_someone_with_no_history_can_be_taken_out() {
        val a = device("a")
        val me = a.setYourName("Tim")
        val goa = a.createGroup("Goa", "INR", listOf("Rahul", "Sam", "Priya"))
        val (rahul, sam, priya) = listOf("Rahul", "Sam", "Priya").map { idOf(goa, it) }
        a.appendExpense(
            ExpenseDTO(
                payer = me.id, paidMinor = 2_000, currencyCode = "INR", group = goa.group.id,
                shares = listOf(ExpenseDTO.ShareDTO(me.id, 1_000), ExpenseDTO.ShareDTO(rahul, 1_000)),
            ),
        )
        a.appendSettlement(SettlementDTO(from = priya, to = me.id, amountMinor = 100, currencyCode = "INR", group = goa.group.id))

        assertEquals(MemberCopy.Refusal.YOU, a.removalRefusal(goa.group.id, me.id))
        assertEquals(MemberCopy.Refusal.HISTORY, a.removalRefusal(goa.group.id, rahul))
        assertEquals("a payment is history too", MemberCopy.Refusal.HISTORY, a.removalRefusal(goa.group.id, priya))
        assertNull(a.removalRefusal(goa.group.id, sam))

        val after = a.removeMember(goa.group.id, sam)
        assertEquals(listOf("Tim", "Rahul", "Priya"), after.members.map { it.name })
        val projected = a.projectGroups().groups.single { it.group.id == goa.group.id }
        assertTrue("the records still hold Sam", sam in projected.group.members)
        assertEquals(listOf(sam), projected.removed)

        try {
            a.removeMember(goa.group.id, rahul)
            fail("Rahul is in an expense")
        } catch (e: MemberCopy.Refused) {
            assertEquals(MemberCopy.Refusal.HISTORY, e.why)
            assertEquals("They are in an expense or payment here, so they stay in the group.", GroupCopy.message(e))
        }

        // The removal is a group record: another phone applies it.
        val b = device("b")
        for (record in a.allRecords()) b.applyRecord(record)
        b.rebuildProjection()
        assertEquals(listOf("Tim", "Rahul", "Priya"), b.namedGroups().single().members.map { it.name })
    }

    /** Someone who joined on their phone stays: taking them out would need
     *  the group's key rotated (SPEC §3), which is not built. */
    @Test
    fun someone_who_joined_stays() {
        val a = device("c")
        a.setYourName("Tim")
        val goa = a.createGroup("Goa", "INR", listOf("Rahul"))
        val rahul = idOf(goa, "Rahul")
        a.appendGroupOps(
            listOf(
                GroupOpIn.Claim(goa.group.id, rahul, "f".repeat(32), listOf(ClaimDeviceIn("e".repeat(32), "11".repeat(32)))),
            ), Placing.NONE,
        )
        a.rebuildGroups()
        assertEquals(MemberCopy.Refusal.JOINED, a.removalRefusal(goa.group.id, rahul))
    }

    @Test
    fun the_words_match_ios() {
        assertEquals("Remove Sam from Goa?", MemberCopy.removeTitle("Sam", "Goa"))
        assertEquals("They are in no expense or payment here. You can add them again later.", MemberCopy.REMOVE_BODY)
        assertEquals(
            "Anyone in an expense or payment here, or who joined on their phone, stays in the group.",
            MemberCopy.STAYS,
        )
        assertEquals("Add people", MemberCopy.ADD_PEOPLE)
        assertEquals("Remove Sam", MemberCopy.spokenRemove("Sam"))
        assertEquals(
            listOf(
                "You cannot take yourself out of a group.",
                "They joined on their phone, so they stay in the group.",
                "They are in an expense or payment here, so they stay in the group.",
            ),
            MemberCopy.Refusal.values().map { MemberCopy.message(it) },
        )
    }

    // ---- D-225 Task 7 (Design 12): New group and Your name in list cards (twins of iOS's Task 6) ----

    private fun body(decl: String): String {
        val text = java.io.File("src/main/java/com/vardrlabs/skipti/GroupScreens.kt").readText()
        val at = text.indexOf(decl)
        assertTrue("GroupScreens.kt declares $decl", at >= 0)
        val end = text.indexOf("\n}\n", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    /** The twin of iOS's testNewGroupIsListCards: the name fields are field rows (no outlined field left in the file),
     *  New group is three list cards (Group, You when asked, People) with captions under them, Settles in is a value row
     *  opening the currency list page (no chip row), each person's clear cross is the Lucide cross; Your name is the same
     *  field card. */
    @Test
    fun new_group_is_list_cards() {
        val file = java.io.File("src/main/java/com/vardrlabs/skipti/GroupScreens.kt").readText()
        assertFalse("no outlined field", "OutlinedTextField" in file)
        assertTrue("the name field is a field row", "FieldRow(" in body("private fun NameField("))
        val create = body("fun CreateGroupScreen(")
        assertEquals("Group and You cards", 2, create.split("ListCard(dark)").size - 1)
        // D-250: People is ListCard's look with its rows moving (MovingRows), the same padding and hairlines.
        assertTrue("People's card", "ListCardColumn(dark) {\n                MovingRows(people.rows, key = { it.id }) { r, at ->" in create)
        for (s in listOf(
            "SectionLabel(\"Group\", dark)", "SectionLabel(\"You\", dark)", "SectionLabel(\"People\", dark)",
            "trailing = RowTrailing.Value(currency, chevron = true)", "CurrencyListDialog(", "RemoveGlyphButton(",
            "CardCaption(\"A group settles in one currency, and it cannot be changed later.\", dark)",
            "SecondaryAction(people.addLabel, dark)",
        )) assertTrue("New group: $s", s in create)
        assertFalse("no chip row", "CurrencyChips(" in create)
        val name = body("fun YourNameScreen(")
        assertEquals("one field card", 1, name.split("ListCard(dark)").size - 1)
        assertTrue("NameField(" in name)
    }

    /** D-225 fix wave (M7, M11): People's rename field tells TalkBack the row is being renamed, in iOS's words (read out of
     *  GroupPicker.swift); no stale opt-in is left over the forms (no FlowRow since D-225). */
    @Test
    fun the_rename_field_says_new_name() {
        val file = java.io.File("src/main/java/com/vardrlabs/skipti/GroupScreens.kt").readText()
        val swift = java.io.File("../../ios/Skipti/App/GroupPicker.swift").readText()
        val ios = Regex("""FieldRow\("Name", text: \${'$'}editText, spoken: "([^"]*)"\)""").find(swift)!!.groupValues[1]
        assertEquals(ios, MemberCopy.RENAME_SPOKEN)
        assertEquals("New name", MemberCopy.RENAME_SPOKEN)
        val rename = file.substringAfter("if (editingId == member.id) {").substringBefore("} else if (isCopy) {")
        assertTrue("the field row speaks it", "editText, { editText = it }, \"Name\", dark," in rename && "spoken = MemberCopy.RENAME_SPOKEN," in rename)
        assertFalse("no stale opt-in", "ExperimentalLayoutApi" in file)
    }
}
