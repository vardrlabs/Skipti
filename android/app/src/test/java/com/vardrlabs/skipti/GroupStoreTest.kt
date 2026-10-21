package com.vardrlabs.skipti

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.CoreException
import uniffi.skipti_core.ShareOut
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

/**
 * Groups and people on Android (D-136), the twin of iOS GroupStoreTests (D-135),
 * against a real vault through the real core: fresh installs carry nothing from
 * testing, names and ids persist in step, your name reaches every group, an old
 * fixture vault migrates without rewriting an expense, a corrupt group list is
 * never overwritten, an import keeps the names already stored, an empty export
 * writes nothing, and a settlement is recorded under your own id. In the shared
 * test set: both flavours run it.
 */
class GroupStoreTest {

    private class Fixture {
        private val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path: String =
            Files.createTempDirectory("groups-test").resolve("ledger-vault.db").toString()
        fun store(): LedgerStore = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        fun raw(key: String): ByteArray? {
            val vault = SkiptiVault.open(path, keystore)
            try { return vault.kvGet(key) } finally { vault.closeVault() }
        }
        fun writeRaw(key: String, bytes: ByteArray) {
            val vault = SkiptiVault.open(path, keystore)
            try { vault.kvSet(key, bytes) } finally { vault.closeVault() }
        }
    }

    private fun groupJson(id: String, name: String, members: List<String>): ByteArray =
        """[{"id":"$id","name":"$name","settlementCurrency":"INR","members":[${members.joinToString(",") { "\"$it\"" }}]}]"""
            .toByteArray(Charsets.UTF_8)

    @Test
    fun a_fresh_install_has_no_groups_and_nothing_from_testing() {
        val f = Fixture()
        val store = f.store()
        assertEquals(emptyList<GroupDTO>(), store.loadGroups())
        assertEquals(0, store.namedGroups().size)
        assertNull(store.currentGroup())
        assertNull("reading must not seed", f.raw(LedgerStore.GROUPS_KEY))
        val me = store.ensureSelf()
        assertNull("no invented name", me.name)
        assertEquals(32, me.id.length)
        assertNotEquals("a new install is not the fixture Ada", LedgerStore.LEGACY_ADA, me.id)
    }

    @Test
    fun creating_a_group_needs_your_name_then_persists_names_and_selection() {
        val f = Fixture()
        val store = f.store()
        try {
            store.createGroup("Goa", "INR", listOf("Rahul"))
            fail("needs your name first")
        } catch (_: NeedsYourNameException) {
        }
        assertEquals("Tim", store.setYourName("  Tim  ").name)
        val created = store.createGroup("Goa", "INR", listOf("Rahul", "", " Priya "))
        assertEquals(listOf("Tim", "Rahul", "Priya"), created.members.map { it.name })
        assertEquals(created.members.map { it.id }, created.group.members)

        val reopened = f.store()
        assertEquals(listOf(created), reopened.namedGroups())
        assertEquals(created.group.id, reopened.currentGroup()?.group?.id)
        assertEquals("Tim (You)", LedgerStore.label(created.selfId, created))
        assertEquals("Rahul", LedgerStore.label(created.members[1].id, created))
    }

    @Test
    fun adding_and_renaming_people_persist_and_duplicates_are_refused() {
        val f = Fixture()
        val store = f.store()
        store.setYourName("Tim")
        val created = store.createGroup("Flat", "INR", listOf("Rahul", "Priya"))
        val rahul = created.members[1].id
        store.addMember(created.group.id, "Amit")
        store.renameMember(created.group.id, rahul, "Rahul K")
        try {
            store.addMember(created.group.id, "priya")
            fail("another member's name")
        } catch (_: CoreException) {
        }
        try {
            store.renameMember(created.group.id, rahul, "Amit")
            fail("renaming onto another member's name")
        } catch (_: CoreException) {
        }
        val reloaded = f.store().namedGroups()[0]
        assertEquals(listOf("Tim", "Rahul K", "Priya", "Amit"), reloaded.members.map { it.name })
        assertEquals("a rename keeps the id", rahul, reloaded.members[1].id)
    }

    @Test
    fun renaming_yourself_carries_into_every_group() {
        val f = Fixture()
        val store = f.store()
        store.setYourName("Tim")
        store.createGroup("Goa", "INR", listOf("Rahul"))
        store.createGroup("Flat", "USD", listOf("Priya"))
        store.setYourName("Timothy")
        val groups = f.store().namedGroups()
        assertEquals(2, groups.size)
        for (named in groups) {
            assertEquals("Timothy", named.members.first { it.id == named.selfId }.name)
        }
    }

    @Test
    fun an_old_fixture_vault_migrates_without_rewriting_an_expense() {
        val f = Fixture()
        val ids = (1..4).map { "a".repeat(31) + it }
        f.writeRaw(LedgerStore.GROUPS_KEY, groupJson("b" + "0".repeat(31), "Goa Trip", ids))
        val expensesBefore = f.raw(LedgerStore.EXPENSES_KEY)

        val store = f.store()
        assertEquals("the fixture Ada becomes you", LedgerStore.LEGACY_ADA, store.ensureSelf().id)
        try {
            store.namedGroups()
            fail("needs your name first")
        } catch (_: NeedsYourNameException) {
        }
        store.setYourName("Tim")
        val migrated = store.namedGroups()[0]
        assertEquals(listOf("Tim", "Rahul", "Priya", "Amit"), migrated.members.map { it.name })
        assertEquals("ids and order untouched", ids, migrated.group.members)
        assertNotNull("names are now stored", store.loadGroups()[0].memberNames)
        val expensesAfter = f.raw(LedgerStore.EXPENSES_KEY)
        if (expensesBefore == null) assertNull(expensesAfter)
        else assertArrayEquals("no expense record rewritten", expensesBefore, expensesAfter)
    }

    /** Twin of the iOS regression test: a group list that cannot be read must
     *  stop createGroup, never be replaced by the new group alone. */
    @Test
    fun a_corrupt_group_list_is_an_error_and_creating_a_group_never_overwrites_it() {
        val f = Fixture()
        val store = f.store()
        store.setYourName("Tim")
        val corrupt = "not json".toByteArray(Charsets.UTF_8)
        f.writeRaw(LedgerStore.GROUPS_KEY, corrupt)
        try {
            store.loadGroups()
            fail("corrupt list must throw")
        } catch (_: LedgerCorruptException) {
        }
        try {
            store.createGroup("New", "INR", emptyList())
            fail("createGroup must not proceed over an unreadable list")
        } catch (_: LedgerCorruptException) {
        }
        assertArrayEquals("left exactly as it was", corrupt, f.raw(LedgerStore.GROUPS_KEY))
    }

    /** Twin of the iOS placeholder test: unnamed people get distinct "Member N"
     *  names, never one repeated "Member" that core's duplicate rule refuses. */
    @Test
    fun a_group_with_unnamed_members_gets_distinct_placeholder_names() {
        val f = Fixture()
        val members = listOf(LedgerStore.LEGACY_ADA) + (1..2).map { "c".repeat(31) + it }
        f.writeRaw(LedgerStore.GROUPS_KEY, groupJson("d" + "0".repeat(31), "Flat", members))
        val store = f.store()
        store.setYourName("Tim")
        val named = store.namedGroups()[0]
        assertEquals(listOf("Tim", "Member 2", "Member 3"), named.members.map { it.name })
        assertEquals("ids and order untouched", members, named.group.members)
    }

    /** An import carries member ids only. Writing the merged snapshot must keep
     *  the names this device already stores; a newcomer gets a placeholder. */
    @Test
    fun importing_a_snapshot_keeps_the_names_of_existing_people() {
        val f = Fixture()
        val store = f.store()
        store.setYourName("Tim")
        val created = store.createGroup("Flat", "INR", listOf("Rahul"))
        val newcomer = "e".repeat(31) + "1"
        val snapshot = store.snapshot()
        val merged = snapshot.copy(
            groups = snapshot.groups.map { it.copy(members = it.members + newcomer) },
        )
        store.writeSnapshot(merged)
        val named = f.store().namedGroups().single()
        assertEquals(listOf("Tim", "Rahul", "Member 3"), named.members.map { it.name })
        assertEquals(created.group.members + newcomer, named.group.members)
    }

    /** Twin of iOS ExportFlowTests.testExportingAnEmptyLedgerSeedsNothing. */
    @Test
    fun exporting_an_empty_ledger_seeds_nothing() {
        val f = Fixture()
        val snapshot = f.store().snapshot()
        assertEquals(0, snapshot.groups.size)
        assertEquals(0, snapshot.expenses.size)
        assertNull("exporting must not write a group", f.raw(LedgerStore.GROUPS_KEY))
    }

    /** Settle records the debt between YOU (this device's self) and them, in
     *  the direction the viewer-framed entry says. */
    @Test
    fun a_settlement_names_you_by_your_own_id() {
        val me = "f".repeat(31) + "1"
        val them = "f".repeat(31) + "2"
        val youOwe = SettleUi.settlementFor(ShareOut(id = them, minorUnits = -1_250L), "INR", "g1", me)
        assertEquals(me, youOwe.from)
        assertEquals(them, youOwe.to)
        assertEquals(1_250L, youOwe.amountMinor)
        val theyOwe = SettleUi.settlementFor(ShareOut(id = them, minorUnits = 700L), "INR", "g1", me)
        assertEquals(them, theyOwe.from)
        assertEquals(me, theyOwe.to)
        assertEquals(700L, theyOwe.amountMinor)
        assertEquals("g1", theyOwe.group)
    }

    /** D-172: a person added by mistake can be taken out, and removing a row
     *  from the middle takes exactly that name. Twin of iOS's test. */
    @Test
    fun a_removed_person_row_is_not_in_the_new_group() {
        var people = PeopleDraft()
        assertEquals("Add another person", people.addLabel)
        people = people.rename(people.rows[0].id, "Ann").add()
        people = people.rename(people.rows[1].id, "Bob").add()
        people = people.rename(people.rows[2].id, "Cy")
        people = people.remove(people.rows[1].id)
        assertEquals("the middle row took its own name", listOf("Ann", "Cy"), people.names)
        people = people.add()
        assertEquals("ids are never reused", people.rows.size, people.rows.map { it.id }.toSet().size)
        for (row in people.rows) people = people.remove(row.id)
        assertEquals(emptyList<String>(), people.names)
        assertEquals("Add a person", people.addLabel)

        var draft = PeopleDraft()
        draft = draft.rename(draft.rows[0].id, "Rahul").add()
        draft = draft.rename(draft.rows[1].id, "Oops")
        draft = draft.remove(draft.rows[1].id)
        val store = Fixture().store()
        store.setYourName("Tim")
        val created = store.createGroup("Goa", "INR", draft.names)
        assertEquals(listOf("Tim", "Rahul"), created.members.map { it.name })
    }

    /** D-174: the group list said "1 people". Twin of iOS's test. */
    @Test
    fun one_person_is_singular() {
        assertEquals("1 person", GroupCopy.people(1))
        assertEquals("2 people", GroupCopy.people(2))
        assertEquals("0 people", GroupCopy.people(0))
    }

    // ---- D-218: who can be invited ----

    /** The People screen offers an invite only to a member who is not you and whose place
     *  has no claim; a claim takes the member off the list. Twin of iOS's test. */
    @Test
    fun invitable_offers_only_unclaimed_places_and_never_you() {
        val store = Fixture().store()
        store.setYourName("Tim")
        val named = store.createGroup("Goa", "INR", listOf("Rahul", "Priya"))
        val rahul = named.members.first { it.name == "Rahul" }
        fun offered(): List<String> = InviteFlow.invitable(store, named, true).map { it.name }
        assertEquals("signed out: nobody", emptyList<String>(), InviteFlow.invitable(store, named, false).map { it.name })
        assertEquals("no group: nobody", emptyList<String>(), InviteFlow.invitable(store, null, true).map { it.name })
        assertEquals("everyone but you", listOf("Rahul", "Priya"), offered())
        val device = LedgerStore.Sync.deviceId(store)
        store.appendGroupOps(listOf(Invites.claim(named.group.id, rahul.id, "e".repeat(32), device)), Placing.NONE)
        assertEquals("a claimed place is never offered", listOf("Priya"), offered())
        // Your own claim (the creator writes it) changes nothing: you were never offered.
        store.appendGroupOps(listOf(Invites.claim(named.group.id, named.selfId, named.selfId, device)), Placing.NONE)
        assertEquals(listOf("Priya"), offered())
    }
}
