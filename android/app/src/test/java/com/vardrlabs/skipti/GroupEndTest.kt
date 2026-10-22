package com.vardrlabs.skipti

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.ExpenseRevisionIn
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.RedistributionInput
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

/**
 * D-252: a deleted group ends on every phone (decision 218): the deleting account's other phones, a group that left
 * the account's listing, and a write into a read-only copy. The twin of iOS's GroupEndTests (RolesTests.swift): same
 * inputs, same expected values, and the words read from the Swift table.
 */
class GroupEndTest {
    private val dir = Files.createTempDirectory("group-end")
    private val g = "c".repeat(32)
    private val at = 1_800_000_000_000L

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

    private fun main(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()
    private fun swift(name: String) = java.io.File("../../ios/Skipti/App/$name").readText()

    private fun notice(byUid: String?, id: String = "d1") =
        Notices.Item(id, Notices.Kind.DELETED, g, "Goa", "Prithvi", byUid = byUid, atMs = at)

    // ---- The copy table (iOS's GroupEndCopy is the one table) ----

    @Test
    fun the_copy_table_is_read_from_the_swift_one() {
        val table = swift("Roles.swift").substringAfter("enum GroupEndCopy {").substringBefore("\n}\n")
        val lets = Regex("""static let (\w+) = "([^"\\]*)"""").findAll(table).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            "every line of the Swift table has its Kotlin twin, and nothing more",
            mapOf(
                "noLongerShared" to GroupEndCopy.NO_LONGER_SHARED,
                "refusedVanished" to GroupEndCopy.REFUSED_VANISHED,
                "refusedYouDeleted" to GroupEndCopy.REFUSED_YOU_DELETED,
                "refusedDeleted" to GroupEndCopy.REFUSED_DELETED,
                "refusedRemoved" to GroupEndCopy.REFUSED_REMOVED,
            ),
            lets,
        )
        val youDeleted = Regex("""static func youDeleted\(_ group: String\) -> String \{ "([^"]*)" \}""").find(table)
        assertTrue("the Swift line is there", youDeleted != null)
        assertEquals(youDeleted!!.groupValues[1].replace("\\(group)", "Goa"), GroupEndCopy.youDeleted("Goa"))
        // The values themselves (as iOS pins them): a parse that matched nothing cannot pass by agreeing with itself.
        assertEquals("This group is no longer shared with you.", GroupEndCopy.NO_LONGER_SHARED)
        assertEquals("You deleted Goa.", GroupEndCopy.youDeleted("Goa"))
        assertEquals("This group is no longer shared with you. Nothing new can be added.", GroupEndCopy.REFUSED_VANISHED)
        assertEquals("You deleted this group. Nothing new can be added.", GroupEndCopy.REFUSED_YOU_DELETED)
        assertEquals("This group was deleted. Nothing new can be added.", GroupEndCopy.REFUSED_DELETED)
        assertEquals("You were removed from this group. Nothing new can be added.", GroupEndCopy.REFUSED_REMOVED)
        // The stored kind, as iOS stores it (one vault format on both).
        assertTrue(swift("LedgerStore.swift").contains("static let vanishedKind = \"vanished\""))
        assertEquals("stored in the vault: never renamed", "vanished", ReadOnlyDTO.VANISHED)
        // Each mark's refusal words, as iOS's `GroupEndCopy.refused` chooses them.
        assertEquals(GroupEndCopy.REFUSED_VANISHED, GroupEndCopy.refused(GroupGone.mark(at)))
        assertEquals(GroupEndCopy.REFUSED_REMOVED, GroupEndCopy.refused(ReadOnlyDTO("removed", "Asha", at)))
        assertEquals(GroupEndCopy.REFUSED_YOU_DELETED, GroupEndCopy.refused(ReadOnlyDTO("deleted", "Tim", at, byYou = true)))
        assertEquals(GroupEndCopy.REFUSED_DELETED, GroupEndCopy.refused(ReadOnlyDTO("deleted", "Asha", at)))
        assertEquals(RolesCopy.PLACE_LOST_BANNER, GroupEndCopy.refused(ReadOnlyDTO(ReadOnlyDTO.PLACE_LOST, "", 0)))
    }

    /** The banner: a vanished group says it neutrally; a group this account deleted says "You deleted {group}." on
     *  every one of its phones; someone else's delete and a removal read as they did (D-210). */
    @Test
    fun a_banner_says_how_the_group_ended() {
        val day = "October 13, 2026"
        val vanished = GroupGone.mark(at)
        assertEquals(ReadOnlyDTO("vanished", "", at), vanished)
        assertEquals("This group is no longer shared with you.", ReadOnlyBanner.title(vanished, day, "Goa"))
        assertEquals("This is your copy. Nothing new can be added.", ReadOnlyBanner.body(vanished, "Goa"))
        assertTrue("export and remove from this phone stay", ReadOnlyActions.offersForget(vanished))
        val mine = ReadOnlyDTO("deleted", "Prithvi", at, byYou = true)
        assertEquals("You deleted Goa.", ReadOnlyBanner.title(mine, day, "Goa"))
        assertEquals("Asha deleted this group on $day", ReadOnlyBanner.title(ReadOnlyDTO("deleted", "Asha", at), day, "Goa"))
        assertEquals("Asha removed you on $day", ReadOnlyBanner.title(ReadOnlyDTO("removed", "Asha", at), day, "Goa"))
        assertEquals(RolesCopy.PLACE_LOST_BANNER, ReadOnlyBanner.title(ReadOnlyDTO(ReadOnlyDTO.PLACE_LOST, "", 0), day, "Goa"))
    }

    // ---- 1. The deleting account's own notice ----

    @Test
    fun a_deletion_notice_names_its_deleter() {
        val base = mapOf<String, Any?>("kind" to "deleted", "groupId" to g, "groupName" to "Goa", "byName" to "Prithvi")
        assertNull("a notice written before D-252 names nobody", Notices.item("d1", base)?.byUid)
        assertEquals("ow", Notices.item("d1", base + ("byUid" to "ow"))?.byUid)
        assertNull("not text: refused", Notices.item("d1", base + ("byUid" to 7)))
        assertNull("empty: refused", Notices.item("d1", base + ("byUid" to "")))
        assertNull("null: refused", Notices.item("d1", base + ("byUid" to null)))
    }

    /** The deleting account's other phone reads "You deleted"; another member's reads the deleter's name; the deleting
     *  phone (already marked) and a phone that handled it once keep their mark; a vanished copy says more. */
    @Test
    fun a_deletion_notice_marks_once_and_says_you_deleted_on_the_deleters_other_phones() {
        val fresh = Notices.deletedMark(notice("ow"), "ow", null)
        assertEquals(ReadOnlyDTO("deleted", "Prithvi", at, byYou = true), fresh)
        assertEquals(
            "another member: the deleter's name",
            ReadOnlyDTO("deleted", "Prithvi", at), Notices.deletedMark(notice("ow"), "me", null),
        )
        assertNull("an old notice: never yours", Notices.deletedMark(notice(null), "ow", null)?.byYou)
        assertNull("signed out: never yours", Notices.deletedMark(notice("ow"), null, null)?.byYou)
        assertNull("an empty id matches nothing", Notices.deletedMark(notice(""), "", null)?.byYou)
        // Idempotent: the deleting phone's own mark (its date, "You deleted") stands; so does a mark a first pass wrote.
        val own = ReadOnlyDTO("deleted", "Prithvi", at - 5_000, byYou = true)
        assertNull(Notices.deletedMark(notice("ow"), "ow", own))
        assertNull(Notices.deletedMark(notice("ow"), "me", ReadOnlyDTO("deleted", "Prithvi", at)))
        // A vanished copy (or a lost place) is told what happened.
        assertEquals(fresh, Notices.deletedMark(notice("ow"), "ow", GroupGone.mark(at)))
        assertEquals(fresh, Notices.deletedMark(notice("ow"), "ow", ReadOnlyDTO(ReadOnlyDTO.PLACE_LOST, "", 0)))
    }

    // ---- 2. A group that left the listing ----

    @Test
    fun the_one_read_decides() {
        assertEquals(GroupGone.Outcome.READ_ONLY, GroupGone.decide(GroupGone.Read.REFUSED))
        assertEquals(GroupGone.Outcome.READ_ONLY, GroupGone.decide(GroupGone.Read.NOT_FOUND))
        assertEquals("no answer changes nothing", GroupGone.Outcome.RETRY, GroupGone.decide(GroupGone.Read.NETWORK_ERROR))
        assertEquals(GroupGone.Outcome.KEEP, GroupGone.decide(GroupGone.Read.EXISTS))
        assertEquals("removed, the notice lost: the copy ends", GroupGone.Outcome.READ_ONLY, GroupGone.decide(GroupGone.Read.FORMER))
        assertEquals(GroupGone.Outcome.READ_ONLY, GroupGone.decide(GroupGone.Read.NOT_MEMBER))
        assertEquals(GroupGone.Read.NOT_FOUND, GroupGone.read(Notices.GroupRead.Missing, "me"))
        assertEquals(GroupGone.Read.REFUSED, GroupGone.read(Notices.GroupRead.Refused, "me"))
        assertEquals(GroupGone.Read.NETWORK_ERROR, GroupGone.read(null, "me"))
        // The Firestore codes as the removal read maps them (pinned in RolesTest): only these two are the server's word.
        assertEquals(GroupGone.Read.REFUSED, GroupGone.read(Notices.groupRead("PERMISSION_DENIED"), "me"))
        assertEquals(GroupGone.Read.REFUSED, GroupGone.read(Notices.groupRead("NOT_FOUND"), "me"))
        for (code in listOf("UNAVAILABLE", "DEADLINE_EXCEEDED", "UNAUTHENTICATED", "CANCELLED", "INTERNAL", null)) {
            assertEquals("$code", GroupGone.Outcome.RETRY, GroupGone.decide(GroupGone.read(Notices.groupRead(code), "me")))
        }
    }

    /** D-252 fix wave (M2): a document that is there is "still shared" only for a member. The rules let a removed
     *  account read its group (`former`, firestore.rules), so a removal whose notice was lost reads Found: before this,
     *  that kept the copy writable for good. iOS: testAGroupThatIsThereIsSharedOnlyWithAMember. */
    @Test
    fun a_group_that_is_there_is_shared_only_with_a_member() {
        val member = mapOf("memberUids" to listOf("ow", "me"), "name" to "Goa")
        assertEquals(GroupGone.Read.EXISTS, GroupGone.read(Notices.GroupRead.Found(member), "me"))
        assertEquals(GroupGone.Outcome.KEEP, GroupGone.decide(GroupGone.read(Notices.GroupRead.Found(member), "me")))
        val removed = mapOf("memberUids" to listOf("ow"), "former" to mapOf("me" to mapOf("at" to at, "by" to "ow")))
        assertEquals(GroupGone.Read.FORMER, GroupGone.read(Notices.GroupRead.Found(removed), "me"))
        assertEquals("removed, the notice lost", GroupGone.Outcome.READ_ONLY, GroupGone.decide(GroupGone.read(Notices.GroupRead.Found(removed), "me")))
        assertEquals("the owner is still in it", GroupGone.Read.EXISTS, GroupGone.read(Notices.GroupRead.Found(removed), "ow"))
        assertEquals(GroupGone.Read.NOT_MEMBER, GroupGone.read(Notices.GroupRead.Found(mapOf("memberUids" to listOf("ow"))), "me"))
        assertEquals("no member list: not shared", GroupGone.Read.NOT_MEMBER, GroupGone.read(Notices.GroupRead.Found(emptyMap()), "me"))
        assertEquals("no account: never a member", GroupGone.Read.NOT_MEMBER, GroupGone.read(Notices.GroupRead.Found(member), ""))
        assertEquals(
            GroupGone.Read.NOT_MEMBER,
            GroupGone.read(Notices.GroupRead.Found(mapOf("memberUids" to listOf("ow"), "former" to mapOf("" to mapOf("at" to at)))), ""),
        )
        // Rejoined: `former` cleared, a member again (acceptInvite): shared.
        assertEquals(
            GroupGone.Read.EXISTS,
            GroupGone.read(Notices.GroupRead.Found(mapOf("memberUids" to listOf("ow", "me"), "former" to emptyMap<String, Any>())), "me"),
        )
    }

    /** D-252 fix wave (M3): a listing like the last one checked is passed by (no vault read); a new group, a lost one
     *  or a confirmation is a change; null (a new session, a failed read) always checks. iOS:
     *  testAListingLikeTheLastCheckedIsPassedBy. */
    @Test
    fun a_listing_like_the_last_checked_is_passed_by() {
        val last = mapOf("a" to true, "b" to false)
        assertTrue(GroupGone.unchanged(mapOf("a" to true, "b" to false), last))
        assertFalse("a confirmation can lift a mark", GroupGone.unchanged(mapOf("a" to true, "b" to true), last))
        assertFalse("a group left the listing", GroupGone.unchanged(mapOf("a" to true), last))
        assertFalse("a group joined it", GroupGone.unchanged(mapOf("a" to true, "b" to false, "c" to true), last))
        assertFalse("the session's first listing, or one that must run again", GroupGone.unchanged(last, null))
        assertFalse("even an empty first listing is checked (the sweep)", GroupGone.unchanged(emptyMap(), null))
        assertTrue(GroupGone.unchanged(emptyMap(), emptyMap()))
    }

    /** Which held groups are asked about: the session's first listing sweeps every one in the cloud from this phone that
     *  the listing lacks (0.5.0's victims); never a group only on this phone (asking would end it wrongly), a listed
     *  one, a copy, one a notice explains, or one asked already this session. */
    @Test
    fun which_held_groups_are_asked_about() {
        val (a, b, c, d, e) = listOf("a", "b", "c", "d", "e").map { it + "0".repeat(31) }
        val f = "f" + "0".repeat(31)
        val held = setOf(a, b, c, d, e, f)
        val up = setOf(a, b, c, d, e) // f never left this phone
        assertEquals(
            "the sweep: every held group in the cloud that the listing lacks",
            listOf(a, b, c, d, e), GroupGone.toCheck(held, up, emptySet(), emptySet(), emptySet(), emptySet()),
        )
        assertEquals(listOf(e), GroupGone.toCheck(held, up, setOf(a), setOf(b), setOf(c), setOf(d)))
        assertEquals(
            "a phone-only group is never asked about",
            emptyList<String>(), GroupGone.toCheck(setOf(f), emptySet(), emptySet(), emptySet(), emptySet(), emptySet()),
        )
        assertEquals(
            "a group no longer held (removed from this phone) is not asked about",
            emptyList<String>(), GroupGone.toCheck(emptySet(), up, emptySet(), emptySet(), emptySet(), emptySet()),
        )
        assertEquals("sorted", listOf(a, e), GroupGone.toCheck(setOf(e, a), setOf(a, e), emptySet(), emptySet(), emptySet(), emptySet()))
    }

    @Test
    fun a_vanished_mark_never_makes_a_removal_or_a_deletion_vaguer() {
        assertTrue(GroupGone.marks(over = null))
        assertTrue(GroupGone.marks(over = ReadOnlyDTO(ReadOnlyDTO.PLACE_LOST, "", 0)))
        assertFalse(GroupGone.marks(over = ReadOnlyDTO("removed", "Asha", at)))
        assertFalse(GroupGone.marks(over = ReadOnlyDTO("deleted", "Asha", at)))
        assertFalse("already vanished", GroupGone.marks(over = GroupGone.mark(at)))
        // Listed again (confirmed by the server): only a vanished mark lifts.
        assertTrue(GroupGone.lifts(GroupGone.mark(at), listedConfirmed = true))
        assertFalse("a pending write is not the server", GroupGone.lifts(GroupGone.mark(at), listedConfirmed = false))
        assertFalse(GroupGone.lifts(ReadOnlyDTO("removed", "Asha", at), listedConfirmed = true))
        assertFalse(GroupGone.lifts(ReadOnlyDTO("deleted", "Asha", at), listedConfirmed = true))
        assertFalse(GroupGone.lifts(null, listedConfirmed = true))
    }

    // ---- 3. A write into a read-only copy is refused before it is saved ----

    @Test
    fun a_write_to_a_read_only_copy_is_refused_with_words_before_anything_is_written() {
        val store = device("copy")
        val me = store.setYourName("Tim")
        val goa = store.createGroup("Goa", "INR", listOf("Rahul"))
        val lisbon = store.createGroup("Lisbon", "INR", listOf("Rahul"))
        val rahul = goa.members.first { it.name == "Rahul" }.id
        fun dinner(group: NamedGroupWire): ExpenseDTO {
            val r = group.members.first { it.name == "Rahul" }.id
            return ExpenseDTO(
                payer = me.id, paidMinor = 1_000, currencyCode = "INR", group = group.group.id,
                shares = listOf(ExpenseDTO.ShareDTO(me.id, 500), ExpenseDTO.ShareDTO(r, 500)),
                note = "Dinner", timestampMs = 5_000, addedBy = me.id,
            )
        }
        store.appendExpense(dinner(goa))
        val target = store.projectedExpenses().expenses.first().id
        store.markReadOnly(goa.group.id, GroupGone.mark(at))
        val records = store.allRecords().size
        val kv = store.rawKvGet(LedgerStore.EXPENSES_KEY)
        val settled = store.rawKvGet(LedgerStore.SETTLEMENTS_KEY)
        fun refused(what: String, words: String = GroupEndCopy.REFUSED_VANISHED, write: () -> Unit) {
            try {
                write()
                fail("$what: written into a copy")
            } catch (e: Placing.ReadOnlyCopy) {
                assertEquals(what, Placing.ReadOnlyCopy(GroupGone.mark(at)), e)
                assertEquals("$what: the words a failure line prints", words, e.message)
            }
        }
        refused("an expense") { store.appendExpense(dinner(goa)) }
        refused("an import") { store.appendExpenses(listOf(dinner(lisbon), dinner(goa))) }
        val pay = SettlementDTO(me.id, rahul, 100, "INR", goa.group.id)
        refused("a settle's hold") { store.checkSettlement(pay) }
        refused("a payment") { store.appendSettlement(pay) }
        refused("an edit") { store.reviseExpense(ExpenseRevisionIn(target, emptyList(), me.id, null, "x", null, null)) }
        refused("an op") {
            store.appendGroupOps(listOf(GroupOpIn.RenameMember(goa.group.id, rahul, "R")), Placing.load(store))
        }
        refused("a group rename") { store.renameGroup(goa.group.id, "Goa 2") }
        refused("a redistribution") {
            Placing.load(store).redistribution(RedistributionInput(goa.group.id, rahul, "INR", listOf(IdAmount(me.id, 1))))
        }
        assertEquals("nothing written: no record waits to be parked", records, store.allRecords().size)
        assertArrayEquals(kv, store.rawKvGet(LedgerStore.EXPENSES_KEY))
        assertArrayEquals(settled, store.rawKvGet(LedgerStore.SETTLEMENTS_KEY))
        // Negatives: another group, Just me and the backfills' NONE write as before.
        store.appendExpense(dinner(lisbon))
        assertEquals(records + 1, store.allRecords().size)
        assertNull(Placing.load(store).copyRefusal(lisbon.group.id))
        assertNull(Placing.load(store).copyRefusal(null))
        assertNull("NONE refuses nothing", Placing.NONE.copyRefusal(goa.group.id))
        // Each mark's words.
        store.markReadOnly(goa.group.id, ReadOnlyDTO("deleted", "Tim", at, byYou = true))
        try { store.appendExpense(dinner(goa)); fail() } catch (e: Placing.ReadOnlyCopy) { assertEquals(GroupEndCopy.REFUSED_YOU_DELETED, e.message) }
        store.markReadOnly(goa.group.id, ReadOnlyDTO("deleted", "Asha", at))
        try { store.appendExpense(dinner(goa)); fail() } catch (e: Placing.ReadOnlyCopy) { assertEquals(GroupEndCopy.REFUSED_DELETED, e.message) }
        store.markReadOnly(goa.group.id, ReadOnlyDTO("removed", "Asha", at))
        try { store.appendExpense(dinner(goa)); fail() } catch (e: Placing.ReadOnlyCopy) { assertEquals(GroupEndCopy.REFUSED_REMOVED, e.message) }
        // Shared again (an invite accepted clears the mark): it takes records again.
        store.clearReadOnly(goa.group.id)
        store.appendExpense(dinner(goa))
        assertEquals(records + 2, store.allRecords().size)
        // An unreadable list of marks writes nothing on a guess.
        store.rawKvSet(LedgerStore.READ_ONLY_KEY, "{broken".toByteArray())
        try { store.appendExpense(dinner(lisbon)); fail("an unreadable list is an error") } catch (_: LedgerCorruptException) {}
        assertEquals(records + 2, store.allRecords().size)
    }

    /** A new name for you reaches every group you are in, but not a read-only copy: nothing is written there, and the
     *  name change itself still goes through (a copy is frozen, as a lost place is). */
    @Test
    fun a_new_name_passes_a_read_only_copy_by() {
        val store = device("rename")
        val me = store.setYourName("Tim")
        val goa = store.createGroup("Goa", "INR", listOf("Rahul"))
        val lisbon = store.createGroup("Lisbon", "INR", listOf("Rahul"))
        store.markReadOnly(goa.group.id, ReadOnlyDTO("deleted", "Tim", at, byYou = true))
        store.setYourName("Timothy")
        fun nameIn(group: String) = store.namedGroups().first { it.group.id == group }.members.first { it.id == me.id }.name
        assertEquals("Timothy", nameIn(lisbon.group.id))
        assertEquals("the copy keeps its names", "Tim", nameIn(goa.group.id))
        val renames = store.allRecords().filter { it.recordType == LedgerStore.GROUP_OP_TYPE }
            .map { uniffi.skipti_core.ffiDecodeGroupOp(it.blob) }
            .filterIsInstance<GroupOpIn.RenameMember>()
        assertEquals("one rename, in the group still shared", listOf(lisbon.group.id), renames.map { it.groupId })
    }

    // ---- The sync layer's wiring (pure rules above; these pin where they run) ----

    @Test
    fun the_sync_layer_asks_only_from_the_server_and_never_deletes_anything() {
        val cloud = main("CloudSync.kt")
        assertTrue(cloud.contains("val fromCache = snapshot.metadata.isFromCache\n                listenToGroups(groups, uid, store, fromCache)"))
        assertTrue(
            "a listing from the cache asks nothing",
            cloud.contains("if (!fromCache) checkVanished(groups.map { it.first to it.third }, uid, store)"),
        )
        val check = cloud.substringAfter("private fun checkVanished(").substringBefore("\n    /** One `groupRoles` call")
        assertTrue("found", check.contains("private fun askVanished("))
        assertTrue("the one read, the removal's own, for this account (M2)", check.contains("GroupGone.decide(GroupGone.read(groupRead(groupId), uid))"))
        // D-252 fix wave (M3): a listing like the last checked is passed by before anything is launched.
        val head = check.substringBefore("scope.launch {")
        assertTrue(head.contains("if (GroupGone.unchanged(listing, vanishListing)) return\n        vanishListing = listing"))
        assertFalse("no vault work on the listener's thread", head.contains("store."))
        assertTrue("a failed read or lift checks again", check.contains("if (seen == null || seen.again) synchronized(listenLock) { vanishListing = null }"))
        assertTrue("no answer: the next listing asks again", check.contains("vanishAsked.remove(groupId)\n                        vanishListing = null\n                        return@launch"))
        assertTrue(check.contains("if (!GroupGone.marks(over = store.readOnlyChecked()[groupId])) return@runCatching false"))
        assertTrue(check.contains("store.markReadOnly(groupId, GroupGone.mark(System.currentTimeMillis()))"))
        assertTrue("never after sign-out", check.contains("if (groupsListener == null || Account.uid() != uid) return@launch"))
        assertTrue("listed again meanwhile: nothing", check.contains("groupId !in listedNow"))
        assertTrue(check.contains("GroupGone.toCheck(seen.held, seen.up, ids, seen.copies, noticed, vanishAsked)"))
        for (forbidden in listOf("forgetGroup", "forgetInVault", "rawKvSet(", "delete(")) {
            assertFalse("the check never deletes local data: $forbidden", check.contains(forbidden))
        }
        // D-252 adds no new server read: the group read is the removal's (`groupRead`).
        assertEquals(
            "a record's existence, a group's existence, groupRead (removal and D-252 share it), the catch-up, and " +
                "D-254 fix wave M1's approval read before parking (serverApproval)",
            5, cloud.split("get(Source.SERVER)").size - 1,
        )
        assertTrue("a new session sweeps again", cloud.contains("vanishAsked.clear()\n        listedNow = emptySet()\n        vanishListing = null"))
        val handle = cloud.substringAfter("                    Notices.Kind.DELETED -> {").substringBefore("                    Notices.Kind.ROLE, Notices.Kind.DECLINED -> {")
        assertTrue(handle.contains("Notices.deletedMark(item, uid, store.readOnlyChecked()[item.groupId])?.let {"))
        // Every write path loads the marks: Placing.load reads them, and `check` throws a copy's words first.
        val ledger = main("LedgerStore.kt")
        assertTrue(ledger.contains("fun load(store: LedgerStore): Placing = of(store.projectGroups()).withCopies(store.storedReadOnly())"))
        assertTrue(ledger.contains("        copyRefusal(group)?.let { throw it }\n        if (refuses(group)) throw PlaceLost()"))
    }
}
