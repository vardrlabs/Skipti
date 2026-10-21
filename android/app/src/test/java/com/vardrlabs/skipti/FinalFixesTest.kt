package com.vardrlabs.skipti

import com.google.firebase.Timestamp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.RecordWire
import uniffi.skipti_core.RedistributionInput
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.ffiDecodeGroupOp
import uniffi.skipti_core.ffiDecodeRedistribution
import java.nio.file.Files

/**
 * D-210's final fix wave B, both shells. Twins of RolesTests.swift's
 * `FinalFixesTests` (same inputs, same expectations), through the real core
 * and a real vault where the behaviour is the vault's.
 */
class FinalFixesTest {

    private val dir = Files.createTempDirectory("final-fixes")

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

    private val g1 = "a".repeat(32)
    private val g2 = "b".repeat(32)
    private val uid = "uid-asha"

    // ---- B1: a removed person at zero is on no card ----

    /** Asha paid 100 for Sam, Sam paid 100 for Prithvi, Sam is removed at
     *  zero: Home loads, no card names Sam, and Prithvi owes Asha 100. */
    @Test
    fun a_zero_net_removed_person_leaves_home_and_every_card() = runBlocking {
        val store = device("home")
        val me = store.setYourName("Prithvi")
        val group = store.createGroup("Goa", "INR", listOf("Asha", "Sam"))
        val asha = group.members.first { it.name == "Asha" }
        val sam = group.members.first { it.name == "Sam" }
        store.appendExpense(
            ExpenseDTO(
                payer = asha.id, paidMinor = 10_000, currencyCode = "INR", group = group.group.id, conversion = null,
                shares = listOf(ExpenseDTO.ShareDTO(sam.id, 10_000)),
            ),
        )
        store.appendExpense(
            ExpenseDTO(
                payer = sam.id, paidMinor = 10_000, currencyCode = "INR", group = group.group.id, conversion = null,
                shares = listOf(ExpenseDTO.ShareDTO(me.id, 10_000)),
            ),
        )
        Removal.remove(store, group, sam, null, "Prithvi", emptyList(), shared = false)
        assertEquals(listOf(MemberWire(sam.id, "Sam")), store.removedPeople()[group.group.id])
        val home = HomeOverview.load(store)
        assertEquals("Sam is on no card", listOf("Asha"), home.overview.people.map { it.name })
        // D-223: the same read carries each group's settled progress (core's, the ledger card's meter), one entry per
        // group, the same blocks the group's page reads.
        assertEquals(setOf(group.group.id), home.progress.keys)
        assertEquals(
            SettledSummary.blocks(GroupProgress.progress(store, group.group.id), group.group.settlementCurrency),
            home.progress.getValue(group.group.id),
        )
        assertEquals(listOf("INR"), home.progress.getValue(group.group.id).map { it.currencyCode })
        assertEquals(-10_000L, home.overview.people.first().netMinor)
        val mine = Removal.lines(store, group.group.id, me.id)
        assertEquals(listOf(asha.id), mine.first().entries.map { it.id })
        assertEquals(listOf(-10_000L), mine.first().entries.map { it.minorUnits })
    }

    // ---- B2: a role change names the place and the account ----

    @Test
    fun a_role_change_names_the_place_and_the_account() {
        val place = "4".repeat(32)
        val you = "5".repeat(32)
        val on = GroupRoles.setAdminData(g1, place, you, true, "Prithvi", "Goa")
        assertEquals(place, on["member"]?.jsonPrimitive?.content)
        assertEquals(you, on["you"]?.jsonPrimitive?.content)
        assertEquals(true, on["on"]?.jsonPrimitive?.boolean)
        assertEquals(g1, on["groupId"]?.jsonPrimitive?.content)
        assertEquals("Goa", on["groupName"]?.jsonPrimitive?.content)
        val owner = GroupRoles.makeOwnerData(g1, place, you, "Prithvi", "Goa")
        assertEquals(place, owner["member"]?.jsonPrimitive?.content)
        assertEquals(you, owner["you"]?.jsonPrimitive?.content)
        assertNull(owner["on"])
    }

    // ---- B3: removed and deleted notices stay in the cloud; each phone keeps its own list ----

    private fun notice(id: String, kind: Notices.Kind) = Notices.Item(id, kind, g1, "Goa", "Tim")

    @Test
    fun a_removal_or_a_deletion_is_handled_once_per_phone_and_stays_in_the_cloud() {
        assertTrue(Notices.Kind.REMOVED.keptInCloud)
        assertTrue(Notices.Kind.DELETED.keptInCloud)
        assertFalse(Notices.Kind.ROLE.keptInCloud)
        assertFalse(Notices.Kind.DECLINED.keptInCloud)
        assertFalse(Notices.Kind.REQUEST.keptInCloud)
        val items = listOf(
            notice("r1", Notices.Kind.REQUEST), notice("x1", Notices.Kind.REMOVED), notice("x2", Notices.Kind.REMOVED),
            notice("d1", Notices.Kind.DELETED), notice("o1", Notices.Kind.ROLE), notice("c1", Notices.Kind.DECLINED),
        )
        assertEquals(listOf("x1", "x2", "d1", "o1", "c1"), Notices.toHandle(items, emptySet()).map { it.id })
        assertEquals(
            "only a removal or a deletion is skipped once handled; a role is dismissed in the cloud",
            listOf("x2", "o1", "c1"), Notices.toHandle(items, setOf("x1", "d1", "o1")).map { it.id },
        )
        val store = device("notices")
        store.setYourName("Asha")
        assertEquals(emptySet<String>(), Notices.handled(store))
        Notices.markHandled(store, "x1")
        Notices.markHandled(store, "d1")
        Notices.markHandled(store, "x1")
        assertEquals(setOf("x1", "d1"), Notices.handled(store))
        assertEquals(listOf("x2", "o1", "c1"), Notices.toHandle(items, Notices.handled(store)).map { it.id })
    }

    // ---- B4: group cursors start again once, for this build ----

    @Test
    fun every_groups_cursor_starts_again_once_and_the_personal_one_stays() {
        val store = device("cursors")
        store.setYourName("Asha")
        val goa = store.createGroup("Goa", "INR", listOf("Sam"))
        CloudRecords.saveGroupsUp(store, setOf(g2))
        CloudRecords.saveCursor(store, goa.group.id, 1_000)
        CloudRecords.saveCursor(store, g2, 2_000)
        CloudRecords.saveCursor(store, null, 3_000)
        assertTrue(CloudRecords.resetGroupCursorsOnce(store))
        assertEquals(0L, CloudRecords.cursor(store, goa.group.id))
        assertEquals(0L, CloudRecords.cursor(store, g2))
        assertEquals("the personal records were never refused", 3_000L, CloudRecords.cursor(store, null))
        CloudRecords.saveCursor(store, goa.group.id, 4_000)
        assertFalse("once per install of this build", CloudRecords.resetGroupCursorsOnce(store))
        assertEquals(4_000L, CloudRecords.cursor(store, goa.group.id))
    }

    // ---- B5: removals made before a group was shared go up through adoptRemovals ----

    @Test
    fun removals_made_before_sharing_go_up_one_unit_at_a_time() = runBlocking {
        val store = device("adopt")
        val me = store.setYourName("Prithvi")
        val group = store.createGroup("Goa", "INR", listOf("Sam", "Rahul"))
        val sam = group.members.first { it.name == "Sam" }
        val rahul = group.members.first { it.name == "Rahul" }
        store.appendExpense(
            ExpenseDTO(
                payer = me.id, paidMinor = 90_000, currencyCode = "INR", group = group.group.id, conversion = null,
                shares = listOf(me.id, sam.id, rahul.id).map { ExpenseDTO.ShareDTO(it, 30_000) },
            ),
        )
        Removal.remove(
            store, group, sam, null, "Prithvi",
            listOf(RedistributionInput(group.group.id, sam.id, "INR", listOf(IdAmount(me.id, -30_000)))), shared = false,
        )
        val named = store.namedGroups().first()
        Removal.remove(
            store, named, named.members.first { it.id == rahul.id }, null, "Prithvi",
            listOf(RedistributionInput(group.group.id, rahul.id, "INR", listOf(IdAmount(me.id, -30_000)))), shared = false,
        )
        val pending = CloudRecords.pending(store.allRecords(), CloudRecords.uploaded(store))
        val split = CloudRecords.adoptionUnits(pending)
        assertFalse("no removal is written directly", split.direct.any { CloudRecords.removalType(it) })
        assertEquals("nothing is lost", pending.size, split.direct.size + split.units.flatten().size)
        assertEquals("one unit per person", 2, split.units.size)
        fun shape(unit: List<RecordWire>): List<String> = unit.map { r ->
            when {
                r.recordType == LedgerStore.REDISTRIBUTION_TYPE -> "6:" + ffiDecodeRedistribution(r.blob).from
                else -> (ffiDecodeGroupOp(r.blob) as? GroupOpIn.RemoveMember)?.let { "3:" + it.memberId } ?: "?"
            }
        }
        assertEquals(listOf("6:" + sam.id, "3:" + sam.id), shape(split.units[0]))
        assertEquals(listOf("6:" + rahul.id, "3:" + rahul.id), shape(split.units[1]))
        val data = CloudRecords.adoptData(group.group.id, split.units[0])
        assertEquals(group.group.id, data["groupId"]?.jsonPrimitive?.content)
        assertEquals(
            split.units[0].map { CloudRecords.documentId(it) },
            (data["records"] as JsonArray).map { it.jsonObject["id"]?.jsonPrimitive?.content },
        )
        assertEquals(CloudRecords.AdoptOutcome.DONE, CloudRecords.adoptOutcome(null))
        assertEquals(CloudRecords.AdoptOutcome.DONE, CloudRecords.adoptOutcome(CloudFunctions.Failure.Refused("ALREADY_EXISTS")))
        for (status in listOf("FAILED_PRECONDITION", "PERMISSION_DENIED", "NOT_FOUND", "INVALID_ARGUMENT")) {
            assertEquals(status, CloudRecords.AdoptOutcome.REFUSED, CloudRecords.adoptOutcome(CloudFunctions.Failure.Refused(status)))
        }
        assertEquals(CloudRecords.AdoptOutcome.RETRY, CloudRecords.adoptOutcome(CloudFunctions.Failure.Offline()))
        assertEquals(CloudRecords.AdoptOutcome.RETRY, CloudRecords.adoptOutcome(CloudFunctions.Failure.Refused("UNAVAILABLE")))
        // Refused once (others joined): kept on this phone, never sent directly.
        CloudRecords.saveAdoptRefused(store, split.units[0].map { CloudRecords.documentId(it) }.toSet())
        val after = CloudRecords.adoptionUnits(pending, CloudRecords.adoptRefused(store))
        assertEquals(listOf(shape(split.units[1])), after.units.map { shape(it) })
        assertFalse(after.direct.any { CloudRecords.removalType(it) })
    }

    // ---- B6: the removal's bound, a member again, magnitudes, Settle first's sync ----

    private fun stamp(seconds: Long) = Timestamp(seconds, 0)

    @Test
    fun a_removal_is_bounded_by_the_servers_time_and_ignored_for_a_member_again() {
        val former: Map<String, Any?> = mapOf(
            "memberUids" to listOf("uid-tim"),
            "former" to mapOf(uid to mapOf("at" to stamp(100), "by" to "uid-tim")),
        )
        var item = notice("x1", Notices.Kind.REMOVED).copy(atStamp = stamp(105), removedStamp = stamp(100))
        assertEquals("the notice's own stamp", Notices.RemovalBound.UpTo(stamp(100)), Notices.removalBound(item, uid, Notices.GroupRead.Found(former)))
        item = item.copy(removedStamp = null)
        assertEquals(
            "a notice written after the removal is past it: the group's own time",
            Notices.RemovalBound.UpTo(stamp(100)), Notices.removalBound(item, uid, Notices.GroupRead.Found(former)),
        )
        item = item.copy(atStamp = stamp(99))
        assertEquals(Notices.RemovalBound.UpTo(stamp(99)), Notices.removalBound(item, uid, Notices.GroupRead.Found(former)))
        val again: Map<String, Any?> = mapOf("memberUids" to listOf("uid-tim", uid), "former" to emptyMap<String, Any?>())
        item = item.copy(removedStamp = stamp(100))
        assertEquals(Notices.RemovalBound.MemberAgain, Notices.removalBound(item, uid, Notices.GroupRead.Found(again)))
        assertEquals(Notices.RemovalBound.Gone, Notices.removalBound(item, uid, Notices.GroupRead.Missing))
        assertEquals(Notices.RemovalBound.Gone, Notices.removalBound(item, uid, Notices.GroupRead.Refused))
        item = item.copy(removedStamp = null, atStamp = null)
        assertEquals(Notices.RemovalBound.UpTo(stamp(100)), Notices.removalBound(item, uid, Notices.GroupRead.Found(former)))
    }

    /** D-210 M1: a failed read of the group is "refused" (so the removal goes
     *  on as gone) only for permission-denied and not-found; every other code is
     *  transient, and the notice waits. Twin of the iOS table (RolesTests.swift). */
    @Test
    fun only_permission_denied_and_not_found_make_a_group_read_refused() {
        val refused = setOf("NOT_FOUND", "PERMISSION_DENIED")
        val transient = listOf(
            "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT", "DEADLINE_EXCEEDED", "ALREADY_EXISTS",
            "RESOURCE_EXHAUSTED", "FAILED_PRECONDITION", "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED",
            "INTERNAL", "UNAVAILABLE", "DATA_LOSS", "UNAUTHENTICATED",
        )
        for (name in refused) assertEquals(name, Notices.GroupRead.Refused, Notices.groupRead(name))
        for (name in transient) assertNull("$name is transient: the notice waits", Notices.groupRead(name))
        assertNull("a code nobody knows says nothing about the group", Notices.groupRead(null))
    }

    @Test
    fun a_magnitude_never_goes_negative() {
        assertEquals(Long.MAX_VALUE, RedistributeModel.magnitude(Long.MIN_VALUE))
        assertEquals(5L, RedistributeModel.magnitude(-5))
        assertEquals(7L, RedistributeModel.magnitude(7))
        assertEquals(0L, RedistributeModel.magnitude(0))
    }

    @Test
    fun settle_first_removes_even_when_the_payments_did_not_go_up_and_says_so() = runBlocking {
        val progress = SettleFirstProgress(total = 1)
        var removed = 0
        progress.run(append = {}, sync = { false }, remove = { removed += 1 })
        assertEquals("the removal still goes ahead", 1, removed)
        assertEquals("and the progress says the payments are still waiting", false, progress.uploaded)
        val ok = SettleFirstProgress(total = 1)
        ok.run(append = {}, sync = { true }, remove = {})
        assertEquals(true, ok.uploaded)
    }
}
