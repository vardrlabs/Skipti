package com.vardrlabs.skipti

import com.google.firebase.Timestamp
import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.vardrlabs.skipti.RoleRules.RequestCard.ASKER
import com.vardrlabs.skipti.RoleRules.RequestCard.DECIDE
import com.vardrlabs.skipti.RoleRules.RequestCard.OWNER_DECIDES
import com.vardrlabs.skipti.RoleRules.RequestCard.PERSON
import com.vardrlabs.skipti.RoleRules.Role.ADMIN
import com.vardrlabs.skipti.RoleRules.Role.MEMBER
import com.vardrlabs.skipti.RoleRules.Role.OWNER
import com.vardrlabs.skipti.RoleRules.Role.UNJOINED
import androidx.compose.runtime.saveable.SaverScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.GroupWire
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ShareOut
import uniffi.skipti_core.ffiBalanceLine
import uniffi.skipti_core.ffiDecodeRedistribution
import uniffi.skipti_core.ffiRedistributeEqual
import uniffi.skipti_core.HlcStamp
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.RecordWire
import uniffi.skipti_core.RedistributionInput
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.ffiBalancesCompute
import uniffi.skipti_core.ffiDecodeGroupOp
import uniffi.skipti_core.ffiEncodeGroupOp
import uniffi.skipti_core.ffiEncodeRedistribution

/**
 * D-210 Task 7: roles, removal and deletion on Android's ledger and sync
 * layer, the twin of iOS's RolesTests (Task 5, as built). Same inputs, same
 * expected values, same copy strings (`the_words_match_ios`). No screens yet.
 */
class RolesTest {
    private val o = "1" + "0".repeat(31)
    private val a = "2" + "0".repeat(31)
    private val m = "3" + "0".repeat(31)

    private val dir = Files.createTempDirectory("roles-test")

    private fun store(name: String = "v${System.nanoTime()}"): LedgerStore {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = dir.resolve("$name.db").toString()
        return LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    private fun pid(n: Int): String = "0".repeat(31) + n.toString(16)

    @Test
    fun a_role_comes_from_the_clouds_list() {
        val roles = RoleRules.Roles(owner = o, admins = listOf(a))
        assertEquals(OWNER, RoleRules.role(o, roles))
        assertEquals(ADMIN, RoleRules.role(a, roles))
        assertEquals(MEMBER, RoleRules.role(m, roles))
        assertEquals(UNJOINED, RoleRules.role(null, roles))
    }

    @Test
    fun a_sheet_offers_what_the_viewer_may_do() {
        // The founder's rules (2026-10-13); twins: RolesTests.swift.
        fun offer(viewer: RoleRules.Role, target: RoleRules.Role, self: Boolean) =
            RoleRules.offer(viewer, target, self)
        fun offerOf(remove: Boolean, ask: Boolean, askSelf: Boolean, makeAdmin: Boolean, removeAdmin: Boolean, makeOwner: Boolean) =
            RoleRules.Offer(remove, ask, askSelf, makeAdmin, removeAdmin, makeOwner)
        assertEquals(offerOf(true, false, false, true, false, true), offer(OWNER, MEMBER, false))
        assertEquals(offerOf(true, false, false, false, true, true), offer(OWNER, ADMIN, false))
        assertEquals(offerOf(true, false, false, false, false, false), offer(OWNER, UNJOINED, false))
        assertEquals(offerOf(true, false, false, true, false, false), offer(ADMIN, MEMBER, false))
        assertEquals(offerOf(false, true, false, false, false, false), offer(ADMIN, ADMIN, false))
        assertEquals(offerOf(false, false, false, false, false, false), offer(ADMIN, OWNER, false))
        assertEquals(offerOf(false, true, false, false, false, false), offer(MEMBER, MEMBER, false))
        assertEquals(offerOf(false, false, true, false, false, false), offer(MEMBER, MEMBER, true))
        assertEquals(offerOf(false, false, false, false, false, false), offer(OWNER, OWNER, true))
        assertEquals(offerOf(false, false, true, false, false, false), offer(ADMIN, ADMIN, true))
        assertTrue(RoleRules.canDelete(OWNER))
        assertFalse(RoleRules.canDelete(ADMIN))
    }

    @Test
    fun a_request_card_shows_to_whoever_it_concerns() {
        assertEquals(DECIDE, RoleRules.requestCard(OWNER, MEMBER, viewerIsAsker = false, viewerIsPerson = false))
        assertEquals(DECIDE, RoleRules.requestCard(ADMIN, MEMBER, viewerIsAsker = true, viewerIsPerson = false))
        assertEquals(ASKER, RoleRules.requestCard(MEMBER, MEMBER, viewerIsAsker = true, viewerIsPerson = false))
        assertEquals(PERSON, RoleRules.requestCard(MEMBER, MEMBER, viewerIsAsker = false, viewerIsPerson = true))
        assertEquals(PERSON, RoleRules.requestCard(ADMIN, ADMIN, viewerIsAsker = false, viewerIsPerson = true))
        assertNull(RoleRules.requestCard(MEMBER, MEMBER, viewerIsAsker = false, viewerIsPerson = false))
    }

    @Test
    fun a_request_naming_an_admin_is_the_owners_alone() {
        assertEquals(OWNER_DECIDES, RoleRules.requestCard(ADMIN, ADMIN, viewerIsAsker = false, viewerIsPerson = false))
        assertEquals(DECIDE, RoleRules.requestCard(OWNER, ADMIN, viewerIsAsker = false, viewerIsPerson = false))
        assertEquals(DECIDE, RoleRules.requestCard(ADMIN, UNJOINED, viewerIsAsker = false, viewerIsPerson = false))
        assertEquals("The owner will decide.", RolesCopy.CARD_OWNER_WILL)
    }

    @Test
    fun a_notice_is_read_only_in_its_own_shape() {
        val g = "c".repeat(32)
        val good = mapOf<String, Any?>("kind" to "removed", "groupId" to g, "groupName" to "Goa trip", "byName" to "Prithvi")
        assertEquals(Notices.Kind.REMOVED, Notices.item("n1", good)?.kind)
        assertNull(Notices.item("n1", good + ("kind" to "hacked")))
        assertNull(Notices.item("n1", good + ("groupId" to "x")))
        assertNull(Notices.item("n1", good + ("groupName" to 4)))
        assertTrue(PushRoute.isGroup { mapOf("kind" to "group", "groupId" to g)[it] })
        assertFalse(PushRoute.isGroup { mapOf("kind" to "group", "groupId" to "x")[it] })
    }

    private fun wire(type: Int, group: String, device: String, blob: ByteArray, millis: ULong = 1uL, counter: UInt = 0u) =
        RecordWire(1u, type.toUByte(), group, 0u, millis, counter, device, device, ByteArray(0), 0u, blob)

    @Test
    fun only_a_function_written_removal_comes_in() {
        val group = "c".repeat(32)
        val me = "a".repeat(32)
        val them = "b".repeat(32)
        val sam = "d".repeat(32)
        val removal = wire(3, group, them, ffiEncodeGroupOp(GroupOpIn.RemoveMember(group, sam)))
        val moved = wire(
            6, group, them,
            ffiEncodeRedistribution(RedistributionInput(group, sam, "INR", listOf(IdAmount(me, -500)))),
        )
        val rename = wire(3, group, them, ffiEncodeGroupOp(GroupOpIn.RenameGroup(group, "Goa")))
        fun refusal(r: RecordWire, removal: Boolean) = CloudRecords.refusal(r, group, me, removal)
        assertNull(refusal(removal, true))
        assertNull(refusal(moved, true))
        assertEquals("a phone wrote it, not the Function", CloudRecords.Refusal.UNVERIFIED_REMOVAL, refusal(removal, false))
        assertEquals(CloudRecords.Refusal.UNVERIFIED_REMOVAL, refusal(moved, false))
        assertNull("other operations need no mark", refusal(rename, false))
    }

    @Test
    fun the_words_match_ios() {
        assertEquals(
            "Only the owner or an admin can remove someone. Sam, the owner and the admins will see your request.",
            RolesCopy.askNote("Sam"),
        )
        assertEquals("Make Asha an admin?", RolesCopy.makeAdminTitle("Asha"))
        assertEquals(
            "Asha will own Goa trip: names admins, removes anyone, can delete the group. You become an admin. Only Asha can undo this.",
            RolesCopy.makeOwnerBody("Asha", "Goa trip"),
        )
        assertEquals(
            "Sam's ₹1,000.00 moved to Prithvi, Rahul and Asha",
            RolesCopy.moved("Sam", "₹1,000.00", listOf("Prithvi", "Rahul", "Asha")),
        )
        assertEquals("Sam's ₹5.00 moved to Nina", RolesCopy.moved("Sam", "₹5.00", listOf("Nina")))
        assertEquals("Sam's ₹5.00 moved to A and B", RolesCopy.moved("Sam", "₹5.00", listOf("A", "B")))
    }

    @Test
    fun the_new_words_match_ios() {
        assertEquals("Rename", RolesCopy.RENAME)
        assertEquals("₹1,000.00 of ₹1,000.00", RolesCopy.ofTotal("₹1,000.00", "₹1,000.00"))
        assertEquals("owes ₹200.00", RolesCopy.rowBalance(-20_000, "₹200.00"))
        assertEquals("owed ₹700.00", RolesCopy.rowBalance(70_000, "₹700.00"))
        assertEquals("settled", RolesCopy.rowBalance(0, "₹0.00"))
        assertEquals("takes ₹333.34", RolesCopy.takes("₹333.34"))
        assertEquals("owes ₹200.00 now", RolesCopy.rowNow("owes ₹200.00"))
        assertTrue("reads as Request sent", RolesCopy.alreadyAsked(CloudFunctions.Failure.Refused("ALREADY_EXISTS")))
        assertFalse(RolesCopy.alreadyAsked(CloudFunctions.Failure.Refused("PERMISSION_DENIED")))
        assertFalse(RolesCopy.alreadyAsked(CloudFunctions.Failure.Offline()))
    }

    @Test
    fun an_unjoined_person_is_told_nothing_is_sent_to_them() {
        assertEquals(
            "Sam will get a notification and keeps a read-only copy of the group's history.",
            RolesCopy.removalBody("Sam", joined = true, noHistory = false),
        )
        assertEquals(
            "They are in no expense or payment here. You can add them again later.",
            RolesCopy.removalBody("Sam", joined = false, noHistory = true),
        )
        assertEquals("Sam has not joined, so nothing is sent to them.", RolesCopy.removalBody("Sam", joined = false, noHistory = false))
        assertEquals(
            "Asha asked. Sam will get a notification and keeps a read-only copy of the group's history.",
            RolesCopy.reviewBody("Asha", "Sam", joined = true, noHistory = false),
        )
        assertEquals(
            "Asha asked. Sam has not joined, so nothing is sent to them.",
            RolesCopy.reviewBody("Asha", "Sam", joined = false, noHistory = false),
        )
        assertEquals(
            "Asha asked. They are in no expense or payment here. You can add them again later.",
            RolesCopy.reviewBody("Asha", "Sam", joined = false, noHistory = true),
        )
        assertEquals(
            "Only the owner or an admin can remove someone. The owner and the admins will see your request.",
            RolesCopy.askNote("Sam", joined = false),
        )
        assertEquals(
            "Only the owner or an admin can remove someone. Sam, the owner and the admins will see your request.",
            RolesCopy.askNote("Sam", joined = true),
        )
    }

    @Test
    fun the_sheets_balance_says_here_and_hides_what_core_cannot_format() {
        val inr = RolesMoney.format(20_000, "INR")!!
        val usd = RolesMoney.format(500, "USD")!!
        assertEquals("Asha owes $inr here", RolesCopy.balanceHere("Asha", mapOf("INR" to -20_000L)))
        assertEquals(
            "Asha owes $inr · Asha is owed $usd here",
            RolesCopy.balanceHere("Asha", mapOf("USD" to 500L, "INR" to -20_000L)),
        )
        assertNull("settled: nothing", RolesCopy.balanceHere("Asha", emptyMap()))
        assertNull("core refuses a code that is not one", RolesMoney.format(100, "zz"))
        assertNull(RolesCopy.balanceHere("Asha", mapOf("zz" to -100L)))
        assertEquals("Asha owes $inr here", RolesCopy.balanceHere("Asha", mapOf("zz" to -100L, "INR" to -20_000L)))
    }

    /** D-213: Norse draws the rune separator in the joined line (the plain string; the theme is restored). */
    @Test
    fun the_sheets_balance_joins_with_the_rune_in_norse() {
        val inr = RolesMoney.format(20_000, "INR")!!
        val usd = RolesMoney.format(500, "USD")!!
        Appearance.setForTest(theme = AppTheme.NORSE)
        try {
            assertEquals(
                "Asha owes $inr \u16EB Asha is owed $usd here",
                RolesCopy.balanceHere("Asha", mapOf("USD" to 500L, "INR" to -20_000L)),
            )
        } finally {
            Appearance.setForTest()
        }
    }

    @Test
    fun a_request_card_and_the_review_say_what_the_person_owes() {
        val inr = RolesMoney.format(100_000, "INR")!!
        assertEquals("Sam owes $inr here.", RolesCopy.cardBalance("Sam", mapOf("INR" to -100_000L)))
        assertEquals("Sam is owed $inr here.", RolesCopy.cardBalance("Sam", mapOf("INR" to 100_000L)))
        assertNull("settled: no line", RolesCopy.cardBalance("Sam", emptyMap()))
        val banner = RolesCopy.reviewBanner("Sam", mapOf("INR" to -100_000L))!!
        assertEquals("Sam owes $inr here", banner.first)
        assertEquals("You'll choose next: settle first, or move it to others.", banner.second)
        assertNull(RolesCopy.reviewBanner("Sam", emptyMap()))
    }

    // ---- The ledger side of D-210 ----

    private fun movedRecord(
        group: String, from: String, to: String, amount: Long, millis: ULong,
        device: String = "b".repeat(32), counter: UInt = 0u,
    ) = RecordWire(
        1u, LedgerStore.REDISTRIBUTION_TYPE, group, 0u, millis, counter, device, device,
        byteArrayOf(0x52, 0x44, 0x53, 0x01), 0u,
        ffiEncodeRedistribution(RedistributionInput(group, from, "INR", listOf(IdAmount(to, amount)))),
    )

    @Test
    fun redistributions_project_in_hlc_order_whatever_the_arrival_order() {
        val s = store()
        val g = "c".repeat(32)
        val a = "a".repeat(32)
        val sam = "d".repeat(32)
        val kit = "e".repeat(32)
        val late = movedRecord(g, kit, a, 300, 2_000uL)
        val early = movedRecord(g, sam, a, -500, 1_000uL)
        s.applyRecords(listOf(late, early))
        s.rebuildProjection()
        val list = s.loadRedistributions()
        assertEquals("oldest HLC first, on every phone", listOf(sam, kit), list.map { it.from })
        assertEquals(listOf(IdAmount(a, -500)), list.first().shares)
        // A record core cannot decode is skipped, not fatal.
        val broken = early.copy(hlcMillis = 3_000uL, blob = byteArrayOf(1, 2, 3))
        s.applyRecords(listOf(broken))
        s.rebuildProjection()
        assertEquals(2, s.loadRedistributions().size)
    }

    @Test
    fun a_read_only_copy_is_marked_and_forgetting_erases_only_that_group() {
        val s = store()
        val g1 = "c".repeat(32)
        val g2 = "f".repeat(32)
        val a = "a".repeat(32)
        val sam = "d".repeat(32)
        s.applyRecords(listOf(movedRecord(g1, sam, a, -500, 1_000uL), movedRecord(g2, sam, a, -700, 2_000uL)))
        s.rebuildProjection()
        assertEquals(emptyMap<String, ReadOnlyDTO>(), s.readOnly())
        val at = 1_800_000_000_000L
        s.markReadOnly(g1, ReadOnlyDTO("removed", "Prithvi", at))
        s.markReadOnly(g2, ReadOnlyDTO("deleted", "Asha", at))
        assertEquals(ReadOnlyDTO("removed", "Prithvi", at), s.readOnly()[g1])
        s.rawKvSet(CloudRecords.cursorKey(g1), "123".toByteArray())
        s.forgetGroup(g1)
        assertEquals("the other group's records stay", listOf(g2), s.allRecords().mapNotNull { it.groupId })
        assertEquals(listOf(g2), s.loadRedistributions().map { it.group })
        assertEquals(setOf(g2), s.readOnly().keys)
        assertEquals(0L, CloudRecords.cursor(s, g1))
        s.clearReadOnly(g2)
        assertEquals(emptyMap<String, ReadOnlyDTO>(), s.readOnly())
    }

    // ---- Fix 1 (review of Task 5) ----

    /** A joiner's phone after the group removed them: Tim's phone (A) made
     *  Goa and Pune with Rahul in each; Rahul's phone (B) claimed both
     *  places; the removeMember Function then wrote Goa's removal of Rahul's
     *  place, and a redistribution of what Rahul owed there onto Sam. */
    private class Removed(
        val a: LedgerStore, val b: LedgerStore,
        val goa: String, val pune: String, val me: String, val tim: String, val rahul: String, val sam: String,
    )

    private fun removedJoiner(): Removed {
        val a = store()
        val b = store()
        val timSelf = a.setYourName("Tim")
        val goa = a.createGroup("Goa", "INR", listOf("Rahul", "Sam"))
        val pune = a.createGroup("Pune", "INR", listOf("Rahul"))
        val rahul = goa.members.first { it.name == "Rahul" }.id
        val sam = goa.members.first { it.name == "Sam" }.id
        val rahulPune = pune.members.first { it.name == "Rahul" }.id
        // Tim paid 1,000 in Goa, half of it Rahul's: Rahul owes 500.
        a.appendExpense(
            ExpenseDTO(
                payer = timSelf.id, paidMinor = 1_000, currencyCode = "INR", group = goa.group.id, conversion = null,
                shares = listOf(ExpenseDTO.ShareDTO(timSelf.id, 500), ExpenseDTO.ShareDTO(rahul, 500)),
            ),
        )
        val me = b.setYourName("Rahul")
        // Only the groups' records travel; Tim's own "you" is his account's.
        for (record in a.allRecords()) if (record.groupId != null) b.applyRecord(record)
        val device = "e".repeat(32)
        b.appendGroupOps(
            listOf(
                Invites.claim(goa.group.id, rahul, me.id, device),
                Invites.claim(pune.group.id, rahulPune, me.id, device),
            ), Placing.NONE,
        )
        b.rebuildProjection()
        assertEquals("joined both", listOf(goa.group.id, pune.group.id), b.namedGroups().map { it.group.id })
        // What the Function writes, a minute later, from Tim's side.
        val later = System.currentTimeMillis().toULong() + 60_000uL
        val timDevice = LedgerStore.Sync.deviceId(a)
        b.applyRecord(
            LedgerStore.Sync.groupOpRecord(GroupOpIn.RemoveMember(goa.group.id, rahul), HlcStamp(later, 0u), timDevice),
        )
        b.applyRecord(movedRecord(goa.group.id, rahul, sam, -500, later, timDevice, counter = 1u))
        b.rebuildProjection()
        return Removed(a, b, goa.group.id, pune.group.id, me.id, timSelf.id, rahul, sam)
    }

    @Test
    fun a_removed_joiners_ledger_still_loads() {
        val r = removedJoiner()
        val groups = r.b.namedGroups()
        assertEquals("every group, the read-only one too", listOf(r.goa, r.pune), groups.map { it.group.id })
        val goa = groups.first { it.group.id == r.goa }
        assertEquals(r.me, goa.selfId)
        assertTrue("you stay in your own copy", goa.members.any { it.id == r.me })
        assertFalse("as one person, not two", goa.members.any { it.id == r.rahul })
        // The projection still lists this account's own place as removed; only
        // the screens' list keeps "you" (as one person, above).
        val projected = r.b.projectGroups().groups.first { it.group.id == r.goa }
        assertEquals(listOf(r.me), projected.removed)
    }

    /** D-210 last wave: removed, then invited back as a NEW place and claimed.
     *  This phone still remembers the old place as one of its own (kv self
     *  aliases) although its claim is no longer in the log: the old, removed
     *  place is not a second person, only the new place stays. */
    @Test
    fun a_removed_account_that_rejoins_has_only_its_new_place() {
        val r = removedJoiner()
        val c = store()
        val me = c.setYourName("Rahul")
        for (record in r.a.allRecords()) if (record.groupId != null) c.applyRecord(record)
        c.rawKvSet(
            LedgerStore.SELF_KEY,
            kotlinx.serialization.json.Json.encodeToString(SelfDTO.serializer(), SelfDTO(me.id, "Rahul", listOf(r.rahul)))
                .toByteArray(Charsets.UTF_8),
        )
        val timDevice = LedgerStore.Sync.deviceId(r.a)
        val rahul2 = "7".repeat(32)
        val later = System.currentTimeMillis().toULong() + 60_000u
        c.applyRecord(LedgerStore.Sync.groupOpRecord(GroupOpIn.RemoveMember(r.goa, r.rahul), HlcStamp(later, 0u), timDevice))
        c.applyRecord(
            LedgerStore.Sync.groupOpRecord(
                GroupOpIn.AddMember(r.goa, uniffi.skipti_core.GroupOpMemberIn(rahul2, "Rahul")), HlcStamp(later, 1u), timDevice,
            ),
        )
        c.appendGroupOps(listOf(Invites.claim(r.goa, rahul2, me.id, "e".repeat(32))), Placing.NONE)
        c.rebuildProjection()
        val goa = c.namedGroups().first { it.group.id == r.goa }
        assertEquals(me.id, goa.selfId)
        assertTrue("the new place, read as you", goa.members.any { it.id == me.id })
        assertFalse("the old removed place is dropped, no second Rahul", goa.members.any { it.id == r.rahul })
        assertEquals(1, goa.members.count { it.name == "Rahul" || it.id == me.id })
        // D-211 final review (M8): the profile share goes to the new place; the
        // old removed place merged into you never drops the group.
        val places = ProfileShare.places(c.projectGroups(), c.readOnly().keys, emptyMap())
        assertEquals("the new place", rahul2, places.firstOrNull { it.groupId == r.goa }?.memberId)
    }

    @Test
    fun the_removed_persons_copy_shows_them_at_zero() {
        val r = removedJoiner()
        val moved = r.b.loadRedistributions()
        assertEquals(
            "the place they had reads as them",
            listOf(RedistributionInput(r.goa, r.me, "INR", listOf(IdAmount(r.sam, -500)))),
            moved,
        )
        val lines = ffiBalancesCompute(
            r.b.loadExpenses().filter { it.group == r.goa }.map { it.toInput() },
            r.b.loadSettlements().filter { it.group == r.goa }.map { it.toInput() },
            moved,
        )
        val inr = lines.first { it.currencyCode == "INR" }
        fun net(id: String): Long = inr.entries.firstOrNull { it.id == id }?.minorUnits ?: 0L
        assertEquals("no old balance plus an opposite entry", 0L, net(r.me))
        assertEquals(0L, net(r.rahul))
        assertEquals(500L, net(r.tim))
        assertEquals(-500L, net(r.sam))
    }

    // ---- D-230 F1 (decision 67): the history names a place this phone merged ----

    private fun inr(minor: Long): String = RolesMoney.format(minor, "INR")!!

    /** The group's "moved" history lines as the activity list reads them. */
    private fun movedLines(s: LedgerStore, group: String): List<String> {
        val names = s.historyNames(group)
        return ActivityFeed.moved(s.allRecords(), group).mapNotNull { ActivityFeed.movedLine(it, names) }
    }

    /** F1 (a): the removed joiner's own copy. The redistribution keeps the raw
     *  old place, which this phone merged into you: it reads as your own name,
     *  not "Unknown member" (before any rejoin). Twin: RolesTests.swift. */
    @Test
    fun the_removed_joiners_own_copy_names_its_old_place() {
        val r = removedJoiner()
        assertTrue("the old place is merged into you", r.b.projectGroups().otherYouIds.contains(r.rahul))
        assertEquals("Rahul", r.b.historyNames(r.goa)[r.rahul])
        assertEquals(listOf(RolesCopy.moved("Rahul", inr(500), listOf("Sam"))), movedLines(r.b, r.goa))
    }

    /** F1 (b): after a rejoin (a new place, claimed by the same account) the
     *  old place stays merged and the line still names the account. */
    @Test
    fun a_rejoiners_own_copy_still_names_its_old_place() {
        val r = removedJoiner()
        val rahul2 = "7".repeat(32)
        val later = System.currentTimeMillis().toULong() + 61_000uL
        r.b.applyRecord(
            LedgerStore.Sync.groupOpRecord(
                GroupOpIn.AddMember(r.goa, uniffi.skipti_core.GroupOpMemberIn(rahul2, "Rahul")), HlcStamp(later, 0u),
                LedgerStore.Sync.deviceId(r.a),
            ),
        )
        r.b.appendGroupOps(listOf(Invites.claim(r.goa, rahul2, r.me, "e".repeat(32))), Placing.NONE)
        r.b.rebuildProjection()
        val p = r.b.projectGroups()
        assertTrue("rejoined: the new place is yours", p.otherYouIds.contains(rahul2))
        assertTrue("the old place stays merged", p.otherYouIds.contains(r.rahul))
        assertEquals("Rahul", r.b.historyNames(r.goa)[r.rahul])
        assertEquals(listOf(RolesCopy.moved("Rahul", inr(500), listOf("Sam"))), movedLines(r.b, r.goa))
    }

    /** F1 (c): on Tim's phone, a share to Rahul's account "you" (read through
     *  the alias of Rahul's claim in Pune) is named as Rahul's place, so
     *  "moved to" keeps it instead of dropping it. */
    @Test
    fun an_aliased_you_is_named_in_moved_to() {
        val r = removedJoiner()
        for (record in r.b.allRecords()) if (record.groupId == r.pune) r.a.applyRecord(record)
        val later = System.currentTimeMillis().toULong() + 61_000uL
        r.a.applyRecord(movedRecord(r.pune, r.tim, r.me, -300, later))
        r.a.rebuildProjection()
        val p = r.a.projectGroups()
        assertTrue("Rahul's claim is an alias here", p.aliases.any { it.groupId == r.pune && it.fromId == r.me })
        assertFalse("not this account's", p.otherYouIds.contains(r.me))
        assertEquals("Rahul", r.a.historyNames(r.pune)[r.me])
        assertEquals(listOf(RolesCopy.moved("Tim", inr(300), listOf("Rahul"))), movedLines(r.a, r.pune))
    }

    /** F1 (d), negative: an id neither merged nor aliased stays unnamed:
     *  "Unknown member" as `from`, left out of "moved to". An alias of another
     *  group, or merged ids with no "you" name, name nothing. */
    @Test
    fun an_id_neither_merged_nor_aliased_stays_unknown() {
        val r = removedJoiner()
        val stranger = "9".repeat(32)
        val names = r.b.historyNames(r.goa)
        assertNull(names[stranger])
        val fromStranger = ActivityFeed.Moved("x", r.goa, 1, stranger, "INR", 200, listOf(r.sam))
        assertEquals(
            RolesCopy.moved("Unknown member", inr(200), listOf("Sam")),
            ActivityFeed.movedLine(fromStranger, names),
        )
        val toStranger = ActivityFeed.Moved("y", r.goa, 1, r.sam, "INR", 200, listOf(stranger, r.tim))
        assertEquals(RolesCopy.moved("Sam", inr(200), listOf("Tim")), ActivityFeed.movedLine(toStranger, names))
        val other = namingMergedPlaces(
            mapOf("t" to "Asha"), "you", listOf("old"),
            listOf(uniffi.skipti_core.IdAlias("other", "f", "t")), "g",
        )
        assertEquals("another group's alias, and a you with no name, fill nothing", mapOf("t" to "Asha"), other)
    }

    /** F1 (e), negative: neither loop overwrites a name the history already has. */
    @Test
    fun a_merged_place_never_overwrites_a_name() {
        val names = mapOf("you" to "Rahul", "old" to "Rahul (old)", "f" to "Kit", "t" to "Asha")
        val out = namingMergedPlaces(names, "you", listOf("old"), listOf(uniffi.skipti_core.IdAlias("g", "f", "t")), "g")
        assertEquals("a merged id keeps its own name", "Rahul (old)", out["old"])
        assertEquals("an aliased id keeps its own name", "Kit", out["f"])
        assertEquals(names, out)
    }

    /** F1 (f), negative (review M2): with no "you" the ledger merges nothing,
     *  so an applied alias of this group names nothing either. */
    @Test
    fun with_no_you_an_alias_names_nothing() {
        val out = namingMergedPlaces(
            mapOf("t" to "Asha"), null, listOf("old"), listOf(uniffi.skipti_core.IdAlias("g", "f", "t")), "g",
        )
        assertNull("an alias with no you stays unnamed", out["f"])
        assertEquals(mapOf("t" to "Asha"), out)
    }

    @Test
    fun only_a_removal_or_a_deletion_stops_a_group_syncing() {
        assertTrue(Notices.Kind.REMOVED.endsSync)
        assertTrue(Notices.Kind.DELETED.endsSync)
        assertFalse("a new role keeps the group syncing", Notices.Kind.ROLE.endsSync)
        assertFalse(Notices.Kind.DECLINED.endsSync)
        assertFalse(Notices.Kind.REQUEST.endsSync)
    }

    private fun documentOf(record: RecordWire): MutableMap<String, Any?> {
        val d = CloudRecords.document(record, "u").toMutableMap<String, Any?>()
        d["createdAt"] = Timestamp(1, 0)
        d["removal"] = true
        return d
    }

    @Test
    fun a_catch_up_that_cannot_apply_its_records_fails() {
        val g = "c".repeat(32)
        val sam = "d".repeat(32)
        val document = documentOf(movedRecord(g, sam, "a".repeat(32), -500, 1_000uL))
        val good = store()
        assertEquals(1, CloudRecords.absorb(listOf(document), g, good).size)
        // A vault that will not open: the catch-up says so, the notice stays.
        val path = dir.resolve("missing-${System.nanoTime()}/none/v.db").toString()
        val broken = LedgerStore(path) { throw IllegalStateException("will not open") }
        try {
            CloudRecords.absorb(listOf(document), g, broken)
            fail("should throw")
        } catch (_: Exception) {
        }
    }

    /** A rebuild that threw after the records were applied: the next snapshot
     *  finds them present (nothing fresh) and must still rebuild. */
    @Test
    fun a_rebuild_that_failed_is_retried_when_the_records_come_again() {
        val g = "c".repeat(32)
        val sam = "d".repeat(32)
        val record = movedRecord(g, sam, "a".repeat(32), -500, 1_000uL)
        val document = documentOf(record)
        val s = store()
        // Applied, projection never rebuilt (as after a failed rebuild).
        assertEquals(1, s.applyRecords(listOf(record)).size)
        assertEquals("the screens' ledger is stale", 0, s.loadRedistributions().size)
        val fresh = CloudRecords.absorb(listOf(document), g, s)
        assertTrue("nothing new: the record was already in", fresh.isEmpty())
        assertEquals("rebuilt anyway", 1, s.loadRedistributions().size)
    }

    @Test
    fun an_unreadable_read_only_list_is_an_error_never_overwritten() {
        val s = store()
        val g = "c".repeat(32)
        val mark = ReadOnlyDTO("removed", "Tim", 1_800_000_000_000L)
        assertEquals(emptyMap<String, ReadOnlyDTO>(), s.readOnlyChecked())
        s.markReadOnly(g, mark)
        assertEquals(mapOf(g to mark), s.readOnlyChecked())
        s.rawKvSet(LedgerStore.READ_ONLY_KEY, "not json".toByteArray())
        try { s.readOnlyChecked(); fail("should throw") } catch (_: LedgerCorruptException) {}
        assertEquals("pushAll's tolerant read", emptyMap<String, ReadOnlyDTO>(), s.readOnly())
        try { s.markReadOnly("f".repeat(32), mark); fail("should throw") } catch (_: LedgerCorruptException) {}
        try { s.clearReadOnly(g); fail("should throw") } catch (_: LedgerCorruptException) {}
        assertEquals("left as it was", "not json", s.rawKvGet(LedgerStore.READ_ONLY_KEY)!!.decodeToString())
    }

    @Test
    fun roles_are_asked_for_once_per_launch_and_on_every_change() {
        assertTrue("first sight, cached or not", GroupRoles.needsAsking(null, "o|a"))
        assertFalse(GroupRoles.needsAsking("o|a", "o|a"))
        assertTrue(GroupRoles.needsAsking("o|a", "o|"))
    }

    // D-227: the roles are asked for only for a confirmed snapshot, never twice
    // at once for one group, and a failed answer is asked again (iOS's twin:
    // RolesTests' RoleAsking cases).

    @Test
    fun role_asking_never_asks_for_an_unconfirmed_snapshot() {
        val asking = RoleAsking()
        assertFalse(asking.shouldAsk("g", "o|a", confirmed = false))
        assertFalse("still not, and nothing was marked", asking.shouldAsk("g", "o|a", confirmed = false))
        assertTrue("the confirmed one asks", asking.shouldAsk("g", "o|a", confirmed = true))
    }

    @Test
    fun role_asking_asks_once_while_in_flight() {
        val asking = RoleAsking()
        assertTrue(asking.shouldAsk("g", "o|a", confirmed = true))
        assertFalse("in flight", asking.shouldAsk("g", "o|a", confirmed = true))
        assertFalse("in flight, even for a new signature", asking.shouldAsk("g", "o|b", confirmed = true))
        assertTrue("another group is its own", asking.shouldAsk("h", "o|a", confirmed = true))
    }

    @Test
    fun role_asking_asks_again_after_a_failed_answer() {
        val asking = RoleAsking()
        assertTrue(asking.shouldAsk("g", "o|a", confirmed = true))
        asking.finished("g", "o|a", ok = false)
        assertTrue("not found or offline: asked again", asking.shouldAsk("g", "o|a", confirmed = true))
    }

    @Test
    fun role_asking_keeps_a_successful_answer() {
        val asking = RoleAsking()
        assertTrue(asking.shouldAsk("g", "o|a", confirmed = true))
        asking.finished("g", "o|a", ok = true)
        assertFalse("the same signature is not asked again", asking.shouldAsk("g", "o|a", confirmed = true))
        assertTrue("a changed signature asks", asking.shouldAsk("g", "o|", confirmed = true))
    }

    @Test
    fun role_asking_forgets_a_group_that_left() {
        val asking = RoleAsking()
        assertTrue(asking.shouldAsk("g", "o|a", confirmed = true))
        asking.finished("g", "o|a", ok = true)
        assertTrue(asking.shouldAsk("h", "o|a", confirmed = true))
        asking.finished("h", "o|a", ok = true)
        asking.keep(only = setOf("h"))
        assertTrue("g left: asked again on its return", asking.shouldAsk("g", "o|a", confirmed = true))
        assertFalse("h stayed", asking.shouldAsk("h", "o|a", confirmed = true))
        asking.clear()
        assertTrue("clear forgets everything", asking.shouldAsk("h", "o|a", confirmed = true))
    }

    // D-227 Decision 35: a late answer after sign-out, or after the group left,
    // is ignored; a signature that changed while a refresh was in flight is
    // asked again once that refresh ends (iOS's twin: RolesTests' RoleAsking cases).

    @Test
    fun role_asking_ignores_a_stale_finished_after_clear_or_keep() {
        val asking = RoleAsking()
        assertTrue(asking.shouldAsk("g", "o|a", confirmed = true))
        asking.clear()
        assertNull("nothing to ask again", asking.finished("g", "o|a", ok = true))
        assertTrue("the late answer after clear kept nothing", asking.shouldAsk("g", "o|a", confirmed = true))
        asking.keep(only = emptySet())
        assertNull(asking.finished("g", "o|a", ok = true))
        assertTrue("the late answer after the group left kept nothing", asking.shouldAsk("g", "o|a", confirmed = true))
    }

    @Test
    fun role_asking_asks_again_once_for_a_signature_that_changed_in_flight() {
        val asking = RoleAsking()
        assertTrue(asking.shouldAsk("g", "o|a", confirmed = true))
        assertFalse("in flight: kept for later", asking.shouldAsk("g", "o|b", confirmed = true))
        assertEquals("the pending signature", "o|b", asking.finished("g", "o|a", ok = true))
        assertTrue("asked again with it", asking.shouldAsk("g", "o|b", confirmed = true))
        assertNull("once: nothing pending now", asking.finished("g", "o|b", ok = true))
        assertFalse(asking.shouldAsk("g", "o|b", confirmed = true))
        // A pending signature after a failed answer is still asked.
        assertTrue(asking.shouldAsk("h", "o|a", confirmed = true))
        assertFalse(asking.shouldAsk("h", "o|c", confirmed = true))
        assertEquals("o|c", asking.finished("h", "o|a", ok = false))
    }

    @Test
    fun role_asking_does_not_ask_again_for_the_signature_just_answered() {
        val asking = RoleAsking()
        assertTrue(asking.shouldAsk("g", "o|a", confirmed = true))
        assertFalse(asking.shouldAsk("g", "o|a", confirmed = true))
        assertNull("the pending one is the one answered", asking.finished("g", "o|a", ok = true))
        // An unconfirmed snapshot in flight leaves nothing pending.
        assertTrue(asking.shouldAsk("h", "o|a", confirmed = true))
        assertFalse(asking.shouldAsk("h", "o|b", confirmed = false))
        assertNull(asking.finished("h", "o|a", ok = true))
    }

    // ---- D-227: roles and your own place by place ----

    private val placeA = pid(10)
    private val placeB = pid(11)
    private val x = pid(20)
    private val y = pid(21)
    private val dee = pid(4)
    private val deeYou = pid(30)

    private fun places(owner: List<String> = emptyList(), admins: List<String> = emptyList(), you: List<String> = emptyList()) =
        RoleRules.Places(owner, admins, you)

    @Test
    fun role_asking_is_not_ok_without_your_place() {
        assertFalse("the call failed", RoleAsking.ok(null))
        assertTrue("an old server answers without places", RoleAsking.ok(RoleRules.Roles(o, emptyList())))
        assertFalse(
            "the create op is not up yet: ask again",
            RoleAsking.ok(RoleRules.Roles(o, emptyList(), places(owner = emptyList(), you = emptyList()))),
        )
        assertTrue(RoleAsking.ok(RoleRules.Roles(o, emptyList(), places(owner = listOf(placeB), you = listOf(placeB)))))
    }

    @Test
    fun the_clouds_places_are_read_strictly() {
        fun obj(places: kotlinx.serialization.json.JsonElement?) = buildJsonObject {
            put("owner", o)
            put("admins", JsonArray(listOf(JsonPrimitive(a))))
            if (places != null) put("places", places)
        }
        fun list(vararg ids: String) = JsonArray(ids.map { JsonPrimitive(it) })
        val full = buildJsonObject {
            put("owner", list(placeB))
            put("admins", list(dee))
            put("you", list(placeB))
        }
        assertEquals(
            RoleRules.Roles(o, listOf(a), places(listOf(placeB), listOf(dee), listOf(placeB))),
            GroupRoles.parse(obj(full)),
        )
        assertEquals("an old server: no places", RoleRules.Roles(o, listOf(a), null), GroupRoles.parse(obj(null)))
        val bad = buildJsonObject {
            put("owner", list(placeB))
            put("admins", list())
            put("you", list("zz"))
        }
        assertNull("a bad entry refuses the whole answer", GroupRoles.parse(obj(bad)))
        assertNull("places that are not an object", GroupRoles.parse(obj(JsonPrimitive("x"))))
        assertNull(
            "an entry that is not text",
            GroupRoles.parse(obj(buildJsonObject { put("owner", JsonArray(listOf(JsonPrimitive(1)))) })),
        )
    }

    @Test
    fun a_cached_list_from_0_4_0_still_reads() {
        assertEquals(
            "places missing",
            RoleRules.Roles(o, listOf(a), null),
            GroupRoles.decode("""{"owner":"$o","admins":["$a"]}"""),
        )
        assertEquals(
            "unknown keys and unknown places",
            RoleRules.Roles(o, listOf(a), null),
            GroupRoles.decode("""{"owner":"$o","admins":["$a"],"later":1,"places":"soon"}"""),
        )
        assertEquals(RoleRules.Roles(null, emptyList(), null), GroupRoles.decode("""{"admins":[]}"""))
        assertNull("not a list at all", GroupRoles.decode("not json"))
        val kept = RoleRules.Roles(o, listOf(a), places(listOf(placeB), listOf(dee), listOf(placeB)))
        assertEquals("written and read back", kept, GroupRoles.decode(GroupRoles.encode(kept)))
    }

    /** The report's repro: Cy's phone made the group under B; the account's
     *  registered id is A. The owner is B by place, with the owner's actions. */
    @Test
    fun the_owner_is_known_by_place_when_the_account_id_differs() {
        val members = listOf(MemberWire(placeB, "Cy"), MemberWire(dee, "Dee"))
        val claims = mapOf(dee to deeYou)
        val roles = RoleRules.Roles(owner = placeA, admins = emptyList(), places = places(owner = listOf(placeB), you = listOf(placeB)))
        val rows = PeopleRoles.rows(members, placeB, placeB, claims, roles, yourPlaces = roles.places?.you)
        assertEquals(listOf(OWNER, MEMBER), rows.map { it.role })
        assertEquals(listOf(placeB, dee), rows.map { it.place })
        assertEquals(OWNER, PeopleRoles.viewerRole(rows))
        assertTrue("the owner's offers", rows[1].offer.makeAdmin && rows[1].offer.makeOwner && rows[1].offer.remove)
        assertFalse("the owner is never asked to be removed", rows[0].offer.askSelf)
        // Without places (an old server), today's rule: B is not A.
        val legacy = PeopleRoles.rows(members, placeB, placeB, claims, roles.copy(places = null))
        assertEquals(listOf(MEMBER, MEMBER), legacy.map { it.role })
    }

    /** The founder's case: a group made under x; this phone's "you" is now y. */
    @Test
    fun your_own_place_is_the_one_the_cloud_says() {
        val members = listOf(MemberWire(y, "Al"), MemberWire(dee, "Dee"))
        val claims = mapOf(dee to deeYou)
        val roles = RoleRules.Roles(owner = o, admins = emptyList(), places = places(owner = listOf(x), you = listOf(x)))
        val rows = PeopleRoles.rows(members, y, y, claims, roles, yourPlaces = roles.places?.you, otherYouIds = listOf(x))
        assertEquals("called by x", listOf(x, dee), rows.map { it.place })
        assertEquals(OWNER, rows[0].role)
        assertEquals(OWNER, PeopleRoles.viewerRole(rows))
        assertTrue(rows[1].offer.makeOwner)
        // Several places: the one this phone would pick, if the cloud lists it; else the first sorted.
        val p1 = pid(40)
        val p2 = pid(41)
        val claimed = mapOf(p2 to x)
        assertEquals(p2, PeopleRoles.selfPlace(y, listOf(y, x), claimed, listOf(p1, p2)))
        assertEquals(p1, PeopleRoles.selfPlace(y, listOf(y, x), emptyMap(), listOf(p2, p1)))
        assertEquals(p1, PeopleRoles.selfPlace(y, listOf(y, x), claimed, listOf(p1)))
    }

    /** Places unknown: your own row is found with every id of the account. */
    @Test
    fun without_the_clouds_place_your_row_is_found_with_every_id_of_the_account() {
        val placed = pid(42)
        val members = listOf(MemberWire(y, "Al"), MemberWire(dee, "Dee"))
        val claims = mapOf(placed to x, dee to deeYou)
        val rows = PeopleRoles.rows(members, y, y, claims, RoleRules.Roles(o, emptyList()), otherYouIds = listOf(x))
        assertEquals("the place an older id of the account claimed", listOf(placed, dee), rows.map { it.place })
        assertEquals(placed, PeopleRoles.selfPlace(y, listOf(y, x), claims, emptyList()))
        assertEquals("only one id: the row's own", y, PeopleRoles.selfPlace(y, listOf(y), claims, emptyList()))
    }

    /** Dee joined on a phone whose claim id is not Dee's participant id. */
    @Test
    fun a_joiner_is_an_admin_by_place() {
        val members = listOf(MemberWire(placeB, "Cy"), MemberWire(dee, "Dee"), MemberWire(pid(5), "Nina"))
        val claims = mapOf(dee to deeYou)
        val known = RoleRules.Roles(owner = placeA, admins = listOf(pid(31)), places = places(listOf(placeB), listOf(dee), listOf(placeB)))
        val rows = PeopleRoles.rows(members, placeB, placeB, claims, known, yourPlaces = known.places?.you)
        assertEquals(listOf(OWNER, ADMIN, UNJOINED), rows.map { it.role })
        assertTrue(rows[1].offer.removeAdmin)
        // Seen from Dee's phone: Dee is the admin by place, Cy the owner.
        val deeRows = PeopleRoles.rows(members, dee, deeYou, mapOf(placeB to placeA), known.copy(places = places(listOf(placeB), listOf(dee), listOf(dee))), yourPlaces = listOf(dee))
        assertEquals(listOf(OWNER, ADMIN, UNJOINED), deeRows.map { it.role })
        assertEquals(ADMIN, PeopleRoles.viewerRole(deeRows))
        // A place in a role list counts as joined.
        val nina = PeopleRoles.rows(members, placeB, placeB, claims, known.copy(places = places(listOf(placeB), listOf(pid(5)), listOf(placeB))), yourPlaces = listOf(placeB))
        assertEquals(ADMIN, nina[2].role)
        // places unknown: the legacy result, unchanged.
        val legacy = PeopleRoles.rows(members, placeB, placeB, claims, known.copy(places = null))
        assertEquals(listOf(MEMBER, MEMBER, UNJOINED), legacy.map { it.role })
        assertEquals(RoleRules.role(deeYou, known.copy(places = null)), RoleRules.role(deeYou, dee, known.copy(places = null)))
    }

    @Test
    fun asking_for_your_own_removal_sends_no_you() {
        val members = listOf(MemberWire(placeB, "Cy"), MemberWire(dee, "Dee"))
        val rows = PeopleRoles.rows(members, dee, deeYou, mapOf(dee to deeYou), RoleRules.Roles(placeA, emptyList()))
        assertNull("the server knows the caller", rows[1].askYou)
        assertEquals(deeYou, rows[1].you)
        assertNull(rows[0].askYou)
        val fromCy = PeopleRoles.rows(members, placeB, placeB, mapOf(dee to deeYou), RoleRules.Roles(placeA, emptyList()))
        assertEquals(deeYou, fromCy[1].askYou)
    }

    @Test
    fun a_push_asks_the_roles_again_where_your_place_is_not_known() {
        val g1 = pid(50)
        val g2 = pid(51)
        val g3 = pid(52)
        val g4 = pid(53)
        val cached = mapOf(
            g1 to RoleRules.Roles(o, emptyList(), places(listOf(placeB), emptyList(), listOf(placeB))),
            g2 to RoleRules.Roles(o, emptyList(), places()),
            g3 to RoleRules.Roles(o, emptyList()),
        )
        assertEquals(setOf(g2, g3, g4), CloudRecords.rolesAfterPush(setOf(g1, g2, g3, g4), cached::get))
        assertEquals(emptySet<String>(), CloudRecords.rolesAfterPush(emptySet(), cached::get))
        assertEquals(emptySet<String>(), CloudRecords.rolesAfterPush(setOf(g1), cached::get))
    }

    // ---- D-227 fix wave ----

    /** I1: places known but `places.owner` empty (the create not up yet): the
     *  owner reads by the account rule, as 0.4.0 does. iOS's twin has the same
     *  vectors (RolesTests). */
    @Test
    fun an_owner_the_cloud_cannot_place_yet_reads_by_the_account_rule() {
        val members = listOf(MemberWire(placeB, "Cy"), MemberWire(dee, "Dee"))
        val claims = mapOf(dee to deeYou)
        val notUp = RoleRules.Roles(owner = placeB, admins = emptyList(), places = places())
        val rows = PeopleRoles.rows(members, placeB, placeB, claims, notUp, yourPlaces = notUp.places?.you)
        assertEquals("the legacy owner is the viewer: Owner", listOf(OWNER, MEMBER), rows.map { it.role })
        assertEquals(OWNER, PeopleRoles.viewerRole(rows))
        assertTrue("the owner's offers", rows[1].offer.makeAdmin && rows[1].offer.makeOwner && rows[1].offer.remove)
        assertFalse(rows[0].offer.askSelf)
        // Only the owner falls back: the admins stay by place.
        val admin = notUp.copy(admins = listOf(deeYou))
        assertEquals(MEMBER, PeopleRoles.rows(members, placeB, placeB, claims, admin, yourPlaces = listOf())[1].role)
        // Places name the owner: by place, no fall-back. Dee's claim is the legacy owner A, Dee's place is not B.
        val placed = RoleRules.Roles(owner = placeA, admins = emptyList(), places = places(owner = listOf(placeB), you = listOf(placeB)))
        val byPlace = PeopleRoles.rows(members, placeB, placeB, mapOf(dee to placeA), placed, yourPlaces = placed.places?.you)
        assertEquals(listOf(OWNER, MEMBER), byPlace.map { it.role })
        assertEquals(MEMBER, RoleRules.role(placeA, dee, placed))
        assertEquals("owner empty, not the legacy owner: a member", MEMBER, RoleRules.role(deeYou, dee, notUp))
        assertEquals("owner empty, no account: unjoined", UNJOINED, RoleRules.role(null, dee, notUp.copy(owner = null)))
    }

    /** Decision 38: a failed ask is asked again after 15 s, then 60 s, then
     *  300 s (three per signature); a successful ask, clear and keep cancel the
     *  timer. iOS's twin: RolesTests' RoleAsking retry case. */
    @Test
    fun the_retry_schedule_is_15_then_60_then_300_seconds() {
        assertEquals(listOf<Long?>(15, 60, 300, null), (0..3).map { RoleAsking.retryDelay(it) })
        assertNull(RoleAsking.retryDelay(-1))
    }

    /** One failed ask of [group] at [signature] that counted (it was in
     *  flight): the timer it sets, or null. */
    private fun failOnce(asking: RoleAsking, group: String, signature: String): RoleAsking.RetryTimer? {
        assertTrue("asked", asking.shouldAsk(group, signature, confirmed = true))
        assertNull(asking.finished(group, signature, ok = false))
        return asking.failed(group, signature)
    }

    @Test
    fun role_asking_retries_a_failed_ask_on_a_timer() {
        val asking = RoleAsking()
        val first = failOnce(asking, "g", "o|a")
        assertEquals(15L, first?.seconds)
        assertTrue("the timer is the group's", asking.due("g", first!!.token))
        val second = failOnce(asking, "g", "o|a")
        assertEquals(60L, second?.seconds)
        assertFalse("a later timer replaces the earlier one", asking.due("g", first.token))
        assertEquals(300L, failOnce(asking, "g", "o|a")?.seconds)
        assertNull("three per signature", failOnce(asking, "g", "o|a"))
        assertEquals("a new signature starts again", 15L, failOnce(asking, "g", "o|b")?.seconds)
        // A successful ask cancels the timer.
        val h = RoleAsking()
        val t = failOnce(h, "h", "o|a")!!
        assertTrue(h.shouldAsk("h", "o|a", confirmed = true))
        h.finished("h", "o|a", ok = true)
        assertFalse("answered: no timed retry", h.due("h", t.token))
        // A changed signature, so the group is asked again; the count starts again.
        assertEquals("and the count starts again", 15L, failOnce(h, "h", "o|c")?.seconds)
        // clear and keep cancel it.
        val c = failOnce(h, "h", "o|d")!!
        h.clear()
        assertFalse("cleared", h.due("h", c.token))
        val k = failOnce(h, "h", "o|d")!!
        h.keep(only = setOf("other"))
        assertFalse("the group left", h.due("h", k.token))
        val stays = failOnce(h, "h", "o|d")!!
        h.keep(only = setOf("h"))
        assertTrue("a group still listed keeps it", h.due("h", stays.token))
    }

    /** D-227 follow-up A (decision 35(a)): only an answer that counted (its
     *  group was in flight for that signature) sets a timer; a failed answer
     *  that arrives after clear() or after its group left sets none. iOS's
     *  twin: RolesTests' testOnlyAFailedAnswerThatCountedArmsARetry. */
    @Test
    fun only_a_failed_answer_that_counted_arms_a_retry() {
        val cleared = RoleAsking()
        assertTrue(cleared.shouldAsk("g", "o|a", confirmed = true))
        cleared.clear()
        assertNull(cleared.finished("g", "o|a", ok = false))
        assertNull("after clear: no timer", cleared.failed("g", "o|a"))
        val left = RoleAsking()
        assertTrue(left.shouldAsk("g", "o|a", confirmed = true))
        left.keep(only = setOf("other"))
        assertNull(left.finished("g", "o|a", ok = false))
        assertNull("after the group left: no timer", left.failed("g", "o|a"))
        val flying = RoleAsking()
        assertTrue(flying.shouldAsk("g", "o|a", confirmed = true))
        assertNull(flying.finished("g", "o|a", ok = false))
        val timer = flying.failed("g", "o|a")
        assertEquals("in flight: the timer at 15", 15L, timer?.seconds)
        assertTrue(flying.due("g", timer!!.token))
        assertNull("one answer arms once", flying.failed("g", "o|a"))
        // An answer that hands back a newer signature asks that at once and arms nothing.
        assertTrue(flying.shouldAsk("h", "o|a", confirmed = true))
        assertFalse(flying.shouldAsk("h", "o|b", confirmed = true))
        assertEquals("o|b", flying.finished("h", "o|a", ok = false))
        assertNull(flying.failed("h", "o|a"))
        // A successful answer arms nothing either.
        assertTrue(flying.shouldAsk("h", "o|b", confirmed = true))
        flying.finished("h", "o|b", ok = true)
        assertNull(flying.failed("h", "o|b"))
        // An old signature's answer while a newer one is in flight (after clear) counts for nothing.
        val old = RoleAsking()
        assertTrue(old.shouldAsk("g", "o|a", confirmed = true))
        old.clear()
        assertTrue(old.shouldAsk("g", "o|b", confirmed = true))
        assertNull(old.finished("g", "o|a", ok = false))
        assertNull("not the signature in flight: no timer", old.failed("g", "o|a"))
        assertFalse("o|b is still in flight", old.shouldAsk("g", "o|b", confirmed = true))
        assertNull(old.finished("g", "o|b", ok = false))
        assertEquals(15L, old.failed("g", "o|b")?.seconds)
    }

    /** D-227 follow-up B (re-review M-1): a timer set for an old signature
     *  does not ask once a newer confirmed signature is the group's; an
     *  unconfirmed one changes nothing. iOS's twin: RolesTests'
     *  testARetryForAnOlderSignatureIsNotDueOnceANewerOneIsCurrent. */
    @Test
    fun a_retry_for_an_older_signature_is_not_due_once_a_newer_one_is_current() {
        val asking = RoleAsking()
        val old = failOnce(asking, "g", "o|a")!!
        assertFalse("an unconfirmed snapshot asks nothing", asking.shouldAsk("g", "o|b", confirmed = false))
        assertTrue("an unconfirmed one is not current", asking.due("g", old.token))
        assertTrue("the newer signature asks", asking.shouldAsk("g", "o|b", confirmed = true))
        assertFalse("the old timer is not due", asking.due("g", old.token))
        // Seen while in flight, it is current too.
        val h = RoleAsking()
        val t = failOnce(h, "h", "o|a")!!
        assertTrue(h.shouldAsk("h", "o|a", confirmed = true))
        assertFalse("in flight: kept for later", h.shouldAsk("h", "o|b", confirmed = true))
        assertFalse(h.due("h", t.token))
        // The same signature seen again keeps it due.
        val k = RoleAsking()
        val same = failOnce(k, "k", "o|a")!!
        assertTrue(k.shouldAsk("k", "o|a", confirmed = true))
        assertTrue("the same signature: still due", k.due("k", same.token))
    }

    /** D-227 follow-up G (re-look R5, iOS's F4): a role change the cloud
     *  confirmed shows at once by place, so the open sheet reads the new role
     *  before the refresh answers. iOS's twin: RolesTests'
     *  testAConfirmedRoleChangeShowsAtOnceByPlace (the same vectors). */
    @Test
    fun a_confirmed_role_change_shows_at_once_by_place() {
        val cy = pid(12)
        val de = pid(13)
        val eve = pid(14)
        val roles = RoleRules.Roles(owner = pid(15), admins = emptyList(), places = places(listOf(cy), listOf(eve), listOf(cy)))
        assertEquals(places(listOf(cy), listOf(de, eve), listOf(cy)), RoleRules.confirmed(roles, de, RoleRules.Change.Admin(on = true)).places)
        val twice = RoleRules.confirmed(RoleRules.confirmed(roles, de, RoleRules.Change.Admin(on = true)), de, RoleRules.Change.Admin(on = true))
        assertEquals("once", listOf(de, eve), twice.places?.admins)
        assertEquals(places(listOf(cy), emptyList(), listOf(cy)), RoleRules.confirmed(roles, eve, RoleRules.Change.Admin(on = false)).places)
        assertEquals(
            "the old owner becomes an admin",
            places(listOf(eve), listOf(cy), listOf(cy)),
            RoleRules.confirmed(roles, eve, RoleRules.Change.Owner).places,
        )
        val legacy = RoleRules.Roles(owner = o, admins = listOf(a))
        assertEquals("no places: the refresh says", legacy, RoleRules.confirmed(legacy, de, RoleRules.Change.Admin(on = true)))
        val rows = PeopleRoles.rows(
            listOf(MemberWire(cy, "Cy"), MemberWire(de, "Dee")), cy, cy, mapOf(de to pid(9)),
            RoleRules.confirmed(roles, de, RoleRules.Change.Admin(on = true)), yourPlaces = listOf(cy),
        )
        assertEquals(ADMIN, rows[1].role)
        assertTrue(rows[1].offer.removeAdmin)
        assertFalse(rows[1].offer.makeAdmin)
    }

    /** G's wiring: the open person sheet reads its row from the screen's rows
     *  (live, iOS's `liveRow`), and both role calls keep the confirmed change
     *  before their refresh. */
    @Test
    fun the_open_person_sheet_reads_its_row_live() {
        val people = java.io.File("src/main/java/com/vardrlabs/skipti/GroupScreens.kt").readText()
        assertTrue(people.contains("val sheetRow = sheetId?.let { id -> rows.firstOrNull { it.member.id == id } }"))
        val roles = java.io.File("src/main/java/com/vardrlabs/skipti/Roles.kt").readText()
        val calls = roles.substringAfter("suspend fun setAdmin(").substringBefore("fun forget(groupId: String)")
        assertEquals("setAdmin and makeOwner", 2, Regex(Regex.escape("keep(store, groupId, RoleRules.confirmed(")).findAll(calls).count())
        assertTrue("before the refresh", calls.indexOf("RoleRules.confirmed(") < calls.indexOf("refresh(store, groupId)"))
    }

    /** M-f: a row that is an owner or admin by place, with no claim on this
     *  phone, is offered no role change (the tap could only fail); with the
     *  claim, it is. */
    @Test
    fun a_row_with_no_claim_is_never_offered_a_role_change() {
        val nina = pid(5)
        val members = listOf(MemberWire(placeB, "Cy"), MemberWire(dee, "Dee"), MemberWire(nina, "Nina"))
        val roles = RoleRules.Roles(owner = placeA, admins = emptyList(), places = places(listOf(placeB), listOf(dee, nina), listOf(placeB)))
        val rows = PeopleRoles.rows(members, placeB, placeB, mapOf(dee to deeYou), roles, yourPlaces = roles.places?.you)
        assertEquals(listOf(OWNER, ADMIN, ADMIN), rows.map { it.role })
        assertTrue("claimed: the owner's role offers", rows[1].offer.removeAdmin && rows[1].offer.makeOwner)
        assertNull(rows[2].you)
        assertFalse("no claim: no Remove admin", rows[2].offer.removeAdmin)
        assertFalse("no claim: no Make owner", rows[2].offer.makeOwner)
        assertFalse(rows[2].offer.makeAdmin)
        // A member by place with no claim reads unjoined (no role offers either way); one with a claim is offered Make admin.
        val member = roles.copy(places = places(listOf(placeB), emptyList(), listOf(placeB)))
        val asMembers = PeopleRoles.rows(members, placeB, placeB, mapOf(dee to deeYou), member, yourPlaces = listOf(placeB))
        assertTrue(asMembers[1].offer.makeAdmin)
        assertFalse(asMembers[2].offer.makeAdmin)
    }

    /** Look F2: a group that reaches the cloud while its People page is open
     *  turns the page into the cloud's layout at once: the push says the screens
     *  changed as soon as the group is up (iOS reads it again on the same revision). */
    @Test
    fun a_group_that_reaches_the_cloud_reads_its_screens_again() {
        val sync = java.io.File("src/main/java/com/vardrlabs/skipti/CloudSync.kt").readText()
        val push = sync.substringAfter("suspend fun pushAll(").substringBefore("enum class GroupState")
        val gained = push.indexOf("val gainedGroups = groupsUp - upBefore")
        assertTrue(gained >= 0)
        val bump = push.indexOf("if (gainedGroups.isNotEmpty()) withContext(Dispatchers.Main) { SyncStatus.changed() }")
        assertTrue("the push bumps the revision once a group is up", bump > gained)
        assertTrue("before its records go up", bump < push.indexOf("for (chunk in pending.chunked(100))"))
        val people = java.io.File("src/main/java/com/vardrlabs/skipti/GroupScreens.kt").readText()
        assertTrue(
            "People reads the group again on every revision",
            people.contains("LaunchedEffect(vaultPath, groupId, loads, inviting, SyncStatus.revision, requestIds)"),
        )
    }

    @Test
    fun forgetting_a_group_forgets_its_roles_in_memory() {
        val s = store()
        val g = "c".repeat(32)
        val other = "f".repeat(32)
        GroupRoles.setForTest(g, RoleRules.Roles(o, listOf(a)))
        GroupRoles.setForTest(other, RoleRules.Roles(o, emptyList()))
        try {
            s.forgetGroup(g)
            assertNull(GroupRoles.roles[g])
            assertNotNull(GroupRoles.roles[other])
        } finally {
            GroupRoles.setForTest(other, null)
        }
    }

    @Test
    fun a_move_to_nobody_has_no_dangling_to() {
        assertEquals("Sam's ₹5.00 moved", RolesCopy.moved("Sam", "₹5.00", emptyList()))
    }

    @Test
    fun the_clouds_roles_are_read_strictly_and_failures_have_words() {
        val o = "1".repeat(32)
        val a = "2".repeat(32)
        fun obj(owner: kotlinx.serialization.json.JsonElement, admins: List<String>) = buildJsonObject {
            put("owner", owner)
            put("admins", JsonArray(admins.map { JsonPrimitive(it) }))
        }
        assertEquals(RoleRules.Roles(o, listOf(a)), GroupRoles.parse(obj(JsonPrimitive(o), listOf(a))))
        assertEquals(RoleRules.Roles(null, emptyList()), GroupRoles.parse(obj(JsonNull, emptyList())))
        assertNull(GroupRoles.parse(obj(JsonPrimitive("x"), emptyList())))
        assertNull(GroupRoles.parse(obj(JsonPrimitive(o), listOf("zz"))))
        assertEquals("Only the owner or an admin can do that.", RolesCopy.failure(CloudFunctions.Failure.Refused("PERMISSION_DENIED")))
        assertEquals("That changed on another phone. Try again.", RolesCopy.failure(CloudFunctions.Failure.Refused("FAILED_PRECONDITION")))
        assertEquals("This group no longer exists.", RolesCopy.failure(CloudFunctions.Failure.Refused("NOT_FOUND")))
        assertEquals(InviteCopy.OFFLINE, RolesCopy.failure(CloudFunctions.Failure.Offline()))
    }

    /** L4: the phone that deleted the group says "You deleted". */
    @Test
    fun the_deleting_phone_says_you_deleted() {
        assertEquals("You deleted this group on October 13, 2026", RolesCopy.deletedByYou("October 13, 2026"))
        assertEquals("Asha deleted this group on October 13, 2026", RolesCopy.deletedBanner("Asha", "October 13, 2026"))
        assertEquals("Asha removed you on October 13, 2026", RolesCopy.removedBanner("Asha", "October 13, 2026"))
        // A mark stored before the field existed still reads, and a new one round-trips.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        val old = json.decodeFromString(ReadOnlyDTO.serializer(), """{"kind":"deleted","by":"Asha","atMs":0}""")
        assertNull(old.byYou)
        val mine = ReadOnlyDTO("deleted", "Prithvi", 1_800_000_000_000L, byYou = true)
        assertEquals(mine, json.decodeFromString(ReadOnlyDTO.serializer(), json.encodeToString(ReadOnlyDTO.serializer(), mine)))
    }

    /** This phone called removeMember, the Function wrote the records, and
     *  saving them here failed: they come in when they carry the mark. */
    @Test
    fun this_phones_own_removal_comes_in_when_its_copy_was_lost() {
        val s = store()
        s.setYourName("Prithvi")
        val group = s.createGroup("Goa", "INR", listOf("Sam"))
        val g = group.group.id
        val sam = group.members.first { it.name == "Sam" }.id
        val device = LedgerStore.Sync.deviceId(s)
        // After the group's own records, as the Function's are.
        val later = System.currentTimeMillis().toULong() + 60_000uL
        val moved = RecordWire(
            1u, LedgerStore.REDISTRIBUTION_TYPE, g, 0u, later, 0u, device, device, byteArrayOf(0x52, 0x44, 0x53, 0x01), 0u,
            ffiEncodeRedistribution(RedistributionInput(g, sam, "INR", listOf(IdAmount(group.selfId, -100)))),
        )
        val removal = LedgerStore.Sync.groupOpRecord(GroupOpIn.RemoveMember(g, sam), HlcStamp(later, 1u), device)
        assertNull("the Function wrote it", CloudRecords.refusal(removal, g, device, removal = true))
        assertNull(CloudRecords.refusal(moved, g, device, removal = true))
        assertEquals(CloudRecords.Refusal.THIS_DEVICE, CloudRecords.refusal(removal, g, device, removal = false))
        val expense = wire(
            1, g, device,
            """{"payer":"${group.selfId}","paidMinor":100,"currencyCode":"INR","group":"$g","shares":[{"id":"${group.selfId}","amountMinor":100}]}"""
                .toByteArray(),
        )
        assertEquals(
            "the mark lets in only a removal or a redistribution",
            CloudRecords.Refusal.THIS_DEVICE, CloudRecords.refusal(expense, g, device, removal = true),
        )
        val documents = listOf(moved, removal).map { documentOf(it) }
        assertEquals(2, CloudRecords.absorb(documents, g, s).size)
        assertFalse("Sam is out on this phone too", s.namedGroups().first().members.any { it.id == sam })
        assertEquals("applied once", 0, CloudRecords.absorb(documents, g, s).size)
        assertTrue(ffiDecodeGroupOp(removal.blob) is GroupOpIn.RemoveMember)
    }

    @Test
    fun a_wire_name_never_ends_in_half_an_emoji() {
        // The Functions count UTF-16 units; twin: RolesTests.swift.
        val grin = "\uD83D\uDE00"
        assertEquals("a".repeat(79), RoleRules.wireName("a".repeat(79) + grin))
        assertEquals("a".repeat(80), RoleRules.wireName("a".repeat(80)))
        assertEquals("a".repeat(80), RoleRules.wireName("a".repeat(90)))
        assertEquals("a".repeat(78) + grin, RoleRules.wireName("a".repeat(78) + grin + "b"))
        assertEquals("Ana", RoleRules.wireName("  Ana  "))
    }

    /** Runs [body] with the "main thread" a test owns, then puts the real one back. */
    private fun withMainThread(executor: java.util.concurrent.Executor, isMain: () -> Boolean, body: () -> Unit) {
        val oldExecutor = MainThread.executor
        val oldIsMain = MainThread.isMain
        MainThread.executor = executor
        MainThread.isMain = isMain
        try { body() } finally {
            MainThread.executor = oldExecutor
            MainThread.isMain = oldIsMain
        }
    }

    @Test
    fun roles_are_only_written_on_the_main_thread() {
        // A held "main thread": nothing may change until it runs the posted work.
        val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val g = pid(0x77)
        withMainThread({ queue.put(it) }, { false }) {
            val s = store()
            val worker = Thread { kotlinx.coroutines.runBlocking { GroupRoles.keep(s, g, RoleRules.Roles(o, listOf(a))) } }
            worker.start()
            val posted = queue.poll(2, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull("keep did not post its write to the main thread", posted)
            assertNull("roles changed off the main thread", GroupRoles.roles[g])
            posted!!.run()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertEquals(RoleRules.Roles(o, listOf(a)), GroupRoles.roles[g])
        }
        GroupRoles.setForTest(g, null)
    }

    @Test
    fun two_concurrent_keeps_for_different_groups_both_survive() {
        val mainExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var mainThread: Thread? = null
        mainExecutor.submit { mainThread = Thread.currentThread() }.get()
        val groups = (1..48).map { pid(0x100 + it) }
        try {
            withMainThread(mainExecutor, { Thread.currentThread() === mainThread }) {
                val s = store()
                s.rawKvGet("warm-up") // open the vault once, before the threads race to
                val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
                val start = java.util.concurrent.CountDownLatch(1)
                val done = groups.map { g ->
                    pool.submit {
                        start.await()
                        kotlinx.coroutines.runBlocking { GroupRoles.keep(s, g, RoleRules.Roles(o, listOf(a))) }
                    }
                }
                start.countDown()
                done.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                pool.shutdown()
                for (g in groups) assertNotNull("a keep was lost: $g", GroupRoles.roles[g])
            }
        } finally {
            mainExecutor.shutdown()
            groups.forEach { GroupRoles.setForTest(it, null) }
        }
    }

    // ---- Task 8: the screens' pure parts (twins of RolesTests.swift's Task 6 tests) ----

    private fun line(code: String, net: Long, entries: List<ShareOut>) = BalanceLineOut(code, net, entries, emptyList())

    @Test
    fun the_plan_is_the_members_nonzero_net_per_currency() {
        val sam = pid(4)
        val lines = listOf(
            line("INR", -100_000, listOf(ShareOut(pid(1), -70_000), ShareOut(pid(2), -30_000))),
            line("USD", 500, listOf(ShareOut(pid(3), 500))),
            line("EUR", 0, emptyList()),
        )
        assertEquals(mapOf("INR" to -100_000L, "USD" to 500L), Removal.plan(lines, sam))
        assertEquals("settled: nothing to decide", emptyMap<String, Long>(), Removal.plan(listOf(line("EUR", 0, emptyList())), sam))
    }

    @Test
    fun settle_first_records_every_pair_from_the_members_side() {
        val sam = pid(4)
        val g = "c".repeat(32)
        val lines = listOf(
            line("INR", -100_000, listOf(ShareOut(pid(1), -70_000), ShareOut(pid(2), -30_000))),
            line("USD", 500, listOf(ShareOut(pid(3), 500))),
        )
        assertEquals(
            "Sam pays what Sam owes; Asha pays what Sam is owed",
            listOf(
                SettlementDTO(sam, pid(1), 70_000, "INR", g),
                SettlementDTO(sam, pid(2), 30_000, "INR", g),
                SettlementDTO(pid(3), sam, 500, "USD", g),
            ),
            Removal.settleFirst(lines, sam, g),
        )
        assertEquals(emptyList<SettlementDTO>(), Removal.settleFirst(listOf(line("INR", 0, emptyList())), sam, g))
    }

    @Test
    fun the_plan_and_the_settlements_clear_a_real_balance() {
        val s = store()
        val me = s.setYourName("Prithvi")
        val group = s.createGroup("Goa", "INR", listOf("Sam", "Rahul"))
        val sam = group.members.first { it.name == "Sam" }.id
        val rahul = group.members.first { it.name == "Rahul" }.id
        // Prithvi paid 1,000, split three ways: Sam and Rahul each owe 333.33 or 333.34 to Prithvi.
        val shares = ffiRedistributeEqual(100_000, listOf(me.id, sam, rahul))
        s.appendExpense(
            ExpenseDTO(
                payer = me.id, paidMinor = 100_000, currencyCode = "INR", group = group.group.id, conversion = null,
                shares = shares.map { ExpenseDTO.ShareDTO(it.id, it.amountMinor) },
            ),
        )
        val before = Removal.plan(Removal.lines(s, group.group.id, sam), sam)
        assertEquals(listOf("INR"), before.keys.sorted())
        assertTrue("Sam owes", before.getValue("INR") < 0)
        for (p in Removal.settleFirst(Removal.lines(s, group.group.id, sam), sam, group.group.id)) s.appendSettlement(p)
        assertEquals("the payments leave Sam at zero", emptyMap<String, Long>(), Removal.plan(Removal.lines(s, group.group.id, sam), sam))
    }

    @Test
    fun removal_records_are_one_redistribution_per_currency_then_the_removal() {
        val s = store()
        val g = "c".repeat(32)
        val sam = "d".repeat(32)
        val a = "a".repeat(32)
        val moved = RedistributionInput(g, sam, "INR", listOf(IdAmount(a, -500)))
        val records = Removal.records(s, g, sam, listOf(moved), Placing.NONE)
        assertEquals(listOf(6, 3), records.map { it.recordType.toInt() })
        val device = LedgerStore.Sync.deviceId(s)
        assertTrue(records.all { it.groupId == g && it.hlcDeviceId == device && it.authorDeviceId == device && it.flags == 0.toUByte() })
        assertTrue("RED\u0001", byteArrayOf(0x52, 0x45, 0x44, 0x01).contentEquals(records[0].nonceSession))
        assertEquals(moved, ffiDecodeRedistribution(records[0].blob))
        val op = ffiDecodeGroupOp(records[1].blob) as GroupOpIn.RemoveMember
        assertEquals(listOf(g, sam), listOf(op.groupId, op.memberId))
        assertTrue(
            "each stamp is later than the one before",
            records[0].hlcMillis < records[1].hlcMillis ||
                (records[0].hlcMillis == records[1].hlcMillis && records[0].hlcCounter < records[1].hlcCounter),
        )
        assertEquals("no balance to move: only the removal", listOf(3), Removal.records(s, g, sam, emptyList(), Placing.NONE).map { it.recordType.toInt() })
    }

    @Test
    fun the_payload_is_the_cloud_contracts_shape() {
        val g = "c".repeat(32)
        val sam = "d".repeat(32)
        val dev = "b".repeat(32)
        val nonce = byteArrayOf(0x47, 0x52, 0x50, 0x01)
        val op = RecordWire(
            1u, 3u, g, 0u, 1_800_000_000_123uL, 7u, dev, dev, nonce, 0u,
            ffiEncodeGroupOp(GroupOpIn.RemoveMember(g, sam)),
        )
        val json = Removal.payload(listOf(op))
        assertEquals(1, json.size)
        val r = json[0].jsonObject
        assertEquals("1800000000123-7-$dev", r["id"]!!.jsonPrimitive.content)
        assertEquals("3", r["type"]!!.jsonPrimitive.content)
        assertTrue("the type travels as a string", r["type"]!!.jsonPrimitive.isString)
        assertEquals(dev, r["deviceId"]!!.jsonPrimitive.content)
        val hlc = r["hlc"]!!.jsonObject
        assertEquals(1_800_000_000_123L, hlc["millis"]!!.jsonPrimitive.long)
        assertEquals(7L, hlc["counter"]!!.jsonPrimitive.long)
        assertEquals(dev, hlc["device"]!!.jsonPrimitive.content)
        val payload = r["payload"]!!.jsonObject
        assertEquals(1L, payload["version"]!!.jsonPrimitive.long)
        assertEquals(0L, payload["epoch"]!!.jsonPrimitive.long)
        assertEquals(0L, payload["flags"]!!.jsonPrimitive.long)
        assertEquals(java.util.Base64.getEncoder().encodeToString(nonce), payload["nonce"]!!.jsonPrimitive.content)
        val blob = java.util.Base64.getDecoder().decode(payload["blob"]!!.jsonPrimitive.content)
        val decoded = Json.parseToJsonElement(blob.decodeToString()).jsonObject
        assertEquals("removeMember", decoded["op"]!!.jsonPrimitive.content)
        assertEquals(g, decoded["group"]!!.jsonPrimitive.content)
        assertEquals(sam, decoded["member"]!!.jsonPrimitive.content)
        // And it travels as JSON.
        assertTrue(buildJsonObject { put("records", json) }.toString().startsWith("{\"records\":[{"))
    }

    @Test
    fun redistributing_equally_is_cores_split_with_the_balances_sign() {
        val sam = pid(4)
        var m = RedistributeModel(sam, "INR", -100_000, listOf(pid(3), pid(1), pid(2)))
        assertEquals("everyone is ticked to start", setOf(pid(1), pid(2), pid(3)), m.ticked)
        assertEquals(
            "leftover to the lowest id, owed to nobody",
            listOf(IdAmount(pid(1), -33_334), IdAmount(pid(2), -33_333), IdAmount(pid(3), -33_333)), m.shares(),
        )
        assertEquals(RedistributeModel.Status.SplitEqually, m.status)
        assertTrue(m.ok)
        m = m.toggle(pid(3))
        assertEquals(listOf(IdAmount(pid(1), -50_000), IdAmount(pid(2), -50_000)), m.shares())
        m = m.toggle(pid(1)).toggle(pid(2))
        assertEquals(RedistributeModel.Status.TickOne, m.status)
        assertFalse(m.ok)
        assertEquals(emptyList<IdAmount>(), m.shares())
        // What is owed to the person is shared out with a plus sign.
        val owed = RedistributeModel(sam, "INR", 1_000, listOf(pid(1), pid(2), pid(3)))
        assertEquals(listOf(334L, 333L, 333L), owed.shares().map { it.amountMinor })
    }

    @Test
    fun typed_amounts_must_add_up_exactly() {
        val sam = pid(4)
        var m = RedistributeModel(sam, "INR", -100_000, listOf(pid(1), pid(2), pid(3)))
        m = m.startEditing()
        assertTrue(m.custom)
        assertEquals("the equal split, to edit", mapOf(pid(1) to "333.34", pid(2) to "333.33", pid(3) to "333.33"), m.typed)
        assertEquals(RedistributeModel.Status.AddsUp, m.status)
        m = m.type(pid(2), "")
        assertEquals("nothing typed for a ticked person", RedistributeModel.Status.TypeAmounts, m.status)
        assertFalse(m.ok)
        m = m.copy(typed = mapOf(pid(1) to "500.00", pid(2) to "300.00", pid(3) to "150.00"))
        assertEquals(RedistributeModel.Status.Left(5_000), m.status)
        assertFalse("50.00 still to assign", m.ok)
        m = m.type(pid(3), "200.00")
        assertEquals(RedistributeModel.Status.AddsUp, m.status)
        assertTrue(m.ok)
        assertEquals(
            "the balance's sign, applied to what was typed",
            listOf(IdAmount(pid(1), -50_000), IdAmount(pid(2), -30_000), IdAmount(pid(3), -20_000)), m.shares(),
        )
        m = m.type(pid(3), "250.00")
        assertEquals(RedistributeModel.Status.TooMuch(5_000), m.status)
        assertFalse(m.ok)
        m = m.type(pid(3), "abc")
        assertEquals("an amount that is not one", RedistributeModel.Status.TypeAmounts, m.status)
        m = m.type(pid(3), "0")
        assertEquals("a ticked person takes something", RedistributeModel.Status.TypeAmounts, m.status)
        m = m.type(pid(3), "200.00").toggle(pid(3))
        assertEquals("an unticked person's typed amount does not count", RedistributeModel.Status.Left(20_000), m.status)
        // Back to equal: typed text is dropped.
        m = m.stopEditing()
        assertFalse(m.custom)
        assertEquals(RedistributeModel.Status.SplitEqually, m.status)
    }

    @Test
    fun a_redistribute_model_survives_rotation() {
        val m = RedistributeModel(pid(4), "INR", -100_000, listOf(pid(1), pid(2), pid(3)))
            .toggle(pid(2)).startEditing().type(pid(1), "700.00")
        val saver = RedistributeModel.Saver
        val saved = with(saver) { SaverScope { true }.save(m) }
        assertEquals(m, saver.restore(saved!!))
        val progress = SettleFirstProgress(3, appended = 2)
        val savedProgress = with(SettleFirstProgress.Saver) { SaverScope { true }.save(progress) }
        val back = SettleFirstProgress.Saver.restore(savedProgress!!)!!
        assertEquals(listOf(3, 2, false), listOf(back.total, back.appended, back.removed))
    }

    @Test
    fun the_people_sheet_follows_the_roles_of_each_row() {
        val roles = RoleRules.Roles(owner = o, admins = listOf(a))
        val members = listOf(
            MemberWire(pid(1), "Prithvi"), MemberWire(pid(2), "Rahul"), MemberWire(pid(3), "Asha"), MemberWire(pid(5), "Nina"),
        )
        val claims = mapOf(pid(1) to o, pid(2) to a, pid(3) to m)
        val rows = PeopleRoles.rows(members, pid(1), o, claims, roles)
        assertEquals(listOf(OWNER, ADMIN, MEMBER, UNJOINED), rows.map { it.role })
        assertEquals(listOf(true, false, false, false), rows.map { it.isSelf })
        assertEquals(listOf<String?>(o, a, m, null), rows.map { it.you })
        assertTrue("the owner may take admin away", rows[1].offer.removeAdmin)
        assertTrue(rows[2].offer.makeAdmin)
        assertFalse("someone who has not joined cannot be an admin", rows[3].offer.makeAdmin)
        assertTrue(rows[3].offer.remove)
        assertEquals(OWNER, PeopleRoles.viewerRole(rows))
        // The same group, from Asha's phone: a member asks, never removes.
        val asha = PeopleRoles.rows(members, pid(3), m, claims, roles)
        assertEquals(MEMBER, PeopleRoles.viewerRole(asha))
        assertEquals(
            "the owner cannot be asked about",
            RoleRules.Offer(remove = false, ask = false, askSelf = false, makeAdmin = false, removeAdmin = false, makeOwner = false),
            asha[0].offer,
        )
        assertTrue(asha[2].offer.askSelf)
        assertTrue(asha[3].offer.ask)
        // Roles not known yet (offline, first launch): everyone is a plain member.
        val unknown = PeopleRoles.rows(members, pid(1), o, claims, null)
        assertEquals(listOf(MEMBER, MEMBER, MEMBER, UNJOINED), unknown.map { it.role })
    }

    /** The bug found on devices: a member who joined through an invite sees
     *  their own row under their account's "you" id (the projection aliases the
     *  place they claimed to it), but the cloud knows the person by the place.
     *  Every role or removal call about the viewer names the place. The twin of
     *  iOS's testASelfRowIsCalledByThePlaceTheAccountClaimed. */
    @Test
    fun a_self_row_is_called_by_the_place_the_account_claimed() {
        val you = pid(13)
        val place = pid(12)
        val sam = pid(4)
        val samYou = pid(10)
        val samPlace = pid(11)
        val claims = mapOf(place to you, samPlace to samYou)
        // The joiner: members hold the you id for themself, the places for the rest.
        assertEquals(place, PeopleRoles.placeFor(you, you, listOf(you), claims))
        // Another device of the same account wrote another "you" id: still the claimed place.
        assertEquals(place, PeopleRoles.placeFor(pid(14), pid(14), listOf(you, pid(14)), claims))
        // The creator has no claim: the place IS the you id.
        assertEquals(you, PeopleRoles.placeFor(you, you, listOf(you), emptyMap()))
        assertEquals("someone else's claim is not mine", you, PeopleRoles.placeFor(you, you, listOf(you), mapOf(samPlace to samYou)))
        // A row that is already the claimed place stays.
        assertEquals(place, PeopleRoles.placeFor(place, place, listOf(you), claims))
        // Everyone else is unchanged, claimed or not.
        assertEquals(samPlace, PeopleRoles.placeFor(samPlace, you, listOf(you), claims))
        assertEquals(sam, PeopleRoles.placeFor(sam, you, listOf(you), claims))

        val members = listOf(MemberWire(you, "Rahul"), MemberWire(samPlace, "Sam"), MemberWire(sam, "Nina"))
        val rows = PeopleRoles.rows(members, you, you, claims, RoleRules.Roles(samYou, emptyList()))
        assertEquals(listOf(place, samPlace, sam), rows.map { it.place })
        assertEquals("the row keeps the id the screens know it by", listOf(you, samPlace, sam), rows.map { it.member.id })
        // A request about the joiner names the place: the joiner's card and "Request sent" find the row.
        val aboutMe = listOf(request("r", "Sam", place, "Rahul"))
        assertEquals(listOf(PERSON), RequestCards.cards(aboutMe, rows).map { it.kind })
        assertTrue(RequestCards.asked(aboutMe, rows[0].place))
    }

    @Test
    fun a_failure_about_a_person_does_not_say_the_group_is_gone() {
        val gone = CloudFunctions.Failure.Refused("NOT_FOUND")
        assertEquals("That person is not in this group any more.", RolesCopy.PERSON_GONE)
        assertEquals("That person is not in this group any more.", RolesCopy.failure(gone, person = true))
        assertEquals("a group-level call keeps its words", "This group no longer exists.", RolesCopy.failure(gone))
        assertEquals(RolesCopy.NOT_ALLOWED, RolesCopy.failure(CloudFunctions.Failure.Refused("PERMISSION_DENIED"), person = true))
        assertEquals(InviteCopy.OFFLINE, RolesCopy.failure(CloudFunctions.Failure.Offline(), person = true))
    }

    /** The bug found on devices: a group page's Recent rows named a person
     *  removed from the group "Unknown member", because the named group drops
     *  removed people. The history names (`historyNames`) keep them. */
    @Test
    fun a_removed_payer_is_named_from_the_history() {
        val a = pid(1)
        val sam = pid(4)
        val gone = pid(7)
        val named = NamedGroupWire(
            GroupWire(id = "c".repeat(32), name = "Goa", settlementCurrency = "INR", members = listOf(a, sam)),
            a, listOf(MemberWire(a, "Rahul"), MemberWire(sam, "Sam")),
        )
        val history = mapOf(a to "Rahul", sam to "Sam", gone to "Nina")
        assertEquals("Nina", LedgerStore.label(gone, named, history))
        assertEquals("the group alone cannot name them", "Unknown member", LedgerStore.label(gone, named))
        assertEquals("Unknown member", LedgerStore.label(gone, named, emptyMap()))
        // Who is still here reads as before: "(You)" for the viewer.
        assertEquals("Rahul (You)", LedgerStore.label(a, named, history))
        assertEquals("Sam", LedgerStore.label(sam, named, history))
    }

    /** D-210 last wave: the names a group page's Who owes whom card reads. */
    @Test
    fun the_balance_line_names_a_removed_person_from_the_history() {
        val a = pid(1)
        val sam = pid(4)
        val gone = pid(7)
        val named = NamedGroupWire(
            GroupWire(id = "c".repeat(32), name = "Goa", settlementCurrency = "INR", members = listOf(a, sam)),
            a, listOf(MemberWire(a, "Rahul"), MemberWire(sam, "Sam")),
        )
        val names = LedgerStore.balanceNames(named, mapOf(a to "Rahul", sam to "Sam", gone to "Nina"))
        assertEquals("a removed person who still owes is named, not Unknown member", "Nina", names[gone])
        assertEquals("Rahul (You)", names[a])
        assertEquals("Sam", names[sam])
        assertNull("no name anywhere: the card says Unknown member", LedgerStore.balanceNames(named, emptyMap())[gone])
    }

    @Test
    fun a_self_request_is_worded_in_the_first_person() {
        val sam = pid(4)
        val own = request("a", "Sam", sam)
        val other = request("b", "Asha", sam)
        assertEquals("You asked to be removed", RequestCards.title(RequestCardItem(own, PERSON)))
        assertEquals("the body under it", "The owner or an admin will decide.", RolesCopy.CARD_WAIT)
        assertEquals("Sam asked to be removed", RequestCards.title(RequestCardItem(own, DECIDE)))
        assertEquals("Sam asked to be removed", RequestCards.title(RequestCardItem(own, OWNER_DECIDES)))
        // Someone else asking keeps the old words.
        assertEquals("Asha asked to remove you", RequestCards.title(RequestCardItem(other, PERSON)))
        assertEquals("Asha asked to remove Sam", RequestCards.title(RequestCardItem(other, DECIDE)))
        assertEquals("You asked to remove Sam", RequestCards.title(RequestCardItem(other, ASKER)))
    }

    private fun request(id: String, asker: String, member: String?, name: String? = "Sam") =
        Notices.Item(id, Notices.Kind.REQUEST, "c".repeat(32), "Goa", asker, memberId = member, memberName = name)

    @Test
    fun request_cards_are_shaped_for_the_viewer_and_skip_the_gone() {
        val g = "c".repeat(32)
        val members = listOf(MemberWire(pid(1), "Prithvi"), MemberWire(pid(3), "Asha"), MemberWire(pid(4), "Sam"))
        val open = listOf(
            request("a", "Asha", pid(4)), request("b", "Asha", pid(9)), request("c", "Asha", null),
            request("d", "Asha", pid(4), name = null),
            Notices.Item("e", Notices.Kind.REMOVED, g, "Goa", "Asha", memberId = pid(4), memberName = "Sam"),
        )
        fun kinds(me: String, you: String?, admins: List<String> = emptyList()): List<RoleRules.RequestCard> {
            val rows = PeopleRoles.rows(members, me, you, mapOf(pid(1) to o, pid(3) to m), RoleRules.Roles(o, admins))
            return RequestCards.cards(open, rows).map { it.kind }
        }
        assertEquals("only the request about someone here, and only a request", listOf(DECIDE), kinds(pid(1), o))
        assertEquals("a member holding the notice who is not the person asked", listOf(ASKER), kinds(pid(3), m))
        assertEquals("the person named", listOf(PERSON), kinds(pid(4), null))
        assertEquals("an admin decides on a member", listOf(DECIDE), kinds(pid(3), m, admins = listOf(m)))
    }

    @Test
    fun a_request_naming_an_admin_is_decided_by_the_owner_alone_on_the_cards() {
        val a2 = "4" + "0".repeat(31)
        val members = listOf(
            MemberWire(pid(1), "Prithvi"), MemberWire(pid(2), "Rahul"), MemberWire(pid(3), "Asha"), MemberWire(pid(4), "Sam"),
        )
        val claims = mapOf(pid(1) to o, pid(2) to a, pid(3) to m, pid(4) to a2)
        val roles = RoleRules.Roles(o, listOf(a, a2))
        fun rows(me: String, you: String) = PeopleRoles.rows(members, me, you, claims, roles)
        val aboutSam = listOf(request("r", "Asha", pid(4)))
        // Rahul, an admin: Asha's request about Sam, another admin, is not his to decide.
        assertEquals(listOf(OWNER_DECIDES), RequestCards.cards(aboutSam, rows(pid(2), a)).map { it.kind })
        assertFalse("no Continue to remove for an admin", RequestCards.mayRemove(rows(pid(2), a), pid(4)))
        assertTrue("an admin removes a member", RequestCards.mayRemove(rows(pid(2), a), pid(3)))
        // Prithvi, the owner, decides it.
        assertEquals(listOf(DECIDE), RequestCards.cards(aboutSam, rows(pid(1), o)).map { it.kind })
        assertTrue(RequestCards.mayRemove(rows(pid(1), o), pid(4)))
        assertFalse("nobody removes the owner", RequestCards.mayRemove(rows(pid(1), o), pid(1)))
        assertFalse("nobody who is not here", RequestCards.mayRemove(rows(pid(1), o), pid(9)))
        // Asha asked; Sam is the person.
        assertEquals(listOf(ASKER), RequestCards.cards(aboutSam, rows(pid(3), m)).map { it.kind })
        assertEquals(listOf(PERSON), RequestCards.cards(aboutSam, rows(pid(4), a2)).map { it.kind })
    }

    @Test
    fun request_sent_comes_from_the_open_request() {
        val open = listOf(request("r", "Asha", pid(4)))
        assertTrue(RequestCards.asked(open, pid(4)))
        assertFalse(RequestCards.asked(open, pid(3)))
        assertFalse(
            "a decided request is no longer open",
            RequestCards.asked(listOf(Notices.Item("x", Notices.Kind.DECLINED, "c".repeat(32), "Goa", "Tim", memberId = pid(4))), pid(4)),
        )
    }

    @Test
    fun a_line_core_cannot_format_is_hidden() {
        assertNull(GroupSettings.stillOpen(mapOf("zz" to mapOf(pid(1) to 100L)), mapOf(pid(1) to "Prithvi")))
        val moved = ActivityFeed.Moved("m", "g", 1L, pid(4), "zz", 100L, listOf(pid(1)))
        assertNull("the line is not shown", ActivityFeed.movedLine(moved, mapOf(pid(4) to "Sam", pid(1) to "Prithvi")))
    }

    /** Review fix I1: the payments are appended once, whatever fails after. */
    @Test
    fun settle_first_never_records_the_payments_twice() = kotlinx.coroutines.runBlocking {
        class Down : Exception()
        val appended = mutableListOf<Int>()
        var removals = 0
        var syncs = 0
        var failAppendAt: Int? = 1
        var failRemove = true
        val p = SettleFirstProgress(total = 3)
        val append: suspend (Int) -> Unit = { i ->
            if (i == failAppendAt) { failAppendAt = null; throw Down() }
            appended += i
        }
        val sync: suspend () -> Boolean = { syncs += 1; true }
        val remove: suspend () -> Unit = { removals += 1; if (failRemove) throw Down() }
        // A vault write fails on the second payment.
        try { p.run(append, sync, remove); fail("should throw") } catch (_: Down) {}
        assertEquals(listOf(0), appended)
        assertFalse(p.recorded)
        assertEquals("no removal while a payment is missing", 0, removals)
        // The next tap appends the rest only; the removal fails (offline).
        try { p.run(append, sync, remove); fail("should throw") } catch (_: Down) {}
        assertEquals("the first payment is not appended again", listOf(0, 1, 2), appended)
        assertTrue(p.recorded)
        assertFalse(p.removed)
        assertEquals(1, removals)
        // Recorded: a tap retries the removal alone.
        failRemove = false
        p.run(append, sync, remove)
        assertEquals("never the same payments twice", listOf(0, 1, 2), appended)
        assertEquals(2, removals)
        assertTrue(p.removed)
        assertEquals("the payments reach the cloud before each removal", 2, syncs)
        p.run(append, sync, remove)
        assertEquals("done is done", 2, removals)
    }

    /** Minor 1: Redistribute goes once any payment is recorded. */
    @Test
    fun redistribute_is_over_once_a_payment_is_recorded() = kotlinx.coroutines.runBlocking {
        class Down : Exception()
        val p = SettleFirstProgress(total = 2)
        assertFalse(p.started)
        try {
            p.run({ i -> if (i == 1) throw Down() }, { true }, {})
            fail("should throw")
        } catch (_: Down) {}
        assertEquals(1, p.appended)
        assertFalse("only partly recorded", p.recorded)
        assertTrue("but the balance has already moved", p.started)
    }

    /** Minor 2: a payment core cannot write down is never recorded. */
    @Test
    fun no_payment_is_recorded_that_core_cannot_format() {
        fun pay(code: String) = SettlementDTO(pid(1), pid(2), 100, code, pid(9))
        assertTrue(Removal.canFormat(listOf(pay("INR"), pay("USD"))))
        assertFalse(Removal.canFormat(listOf(pay("INR"), pay("zz"))))
        assertTrue(Removal.canFormat(emptyList()))
    }

    /** Review fix I2: a history line names the person who was removed. */
    @Test
    fun a_history_line_names_the_removed_person() = kotlinx.coroutines.runBlocking {
        val s = store()
        val me = s.setYourName("Prithvi")
        val group = s.createGroup("Goa", "INR", listOf("Sam", "Rahul"))
        val sam = group.members.first { it.name == "Sam" }
        val rahul = group.members.first { it.name == "Rahul" }
        s.appendExpense(
            ExpenseDTO(
                payer = me.id, paidMinor = 90_000, currencyCode = "INR", group = group.group.id, conversion = null,
                shares = listOf(me.id, sam.id, rahul.id).map { ExpenseDTO.ShareDTO(it, 30_000) },
            ),
        )
        val shares = ffiRedistributeEqual(-30_000, listOf(me.id, rahul.id))
        Removal.remove(s, group, sam, null, "Prithvi", listOf(RedistributionInput(group.group.id, sam.id, "INR", shares)), shared = false)
        assertFalse("Sam is out", s.namedGroups().first().members.any { it.id == sam.id })
        val moved = ActivityFeed.moved(s.allRecords(), group.group.id)
        assertEquals(1, moved.size)
        val to = shares.map { if (it.id == me.id) "Prithvi" else "Rahul" }
        assertEquals(
            RolesCopy.moved("Sam", RolesMoney.format(30_000, "INR")!!, to),
            ActivityFeed.movedLine(moved[0], s.historyNames(group.group.id)),
        )
    }

    /** D-234 (L8, the D-230 look's F2): the twin of iOS's testAnExpenseRowNamesARemovedAuthor: an expense row names a
     *  removed author from the group's history ("Sam added Taxi"), a current member by the group, and an id nobody named
     *  "Someone"; `load` reads the history names of every group in the feed. */
    @Test
    fun an_expense_row_names_a_removed_author() = kotlinx.coroutines.runBlocking {
        val s = store()
        s.setYourName("Prithvi")
        val group = s.createGroup("Goa", "INR", listOf("Sam", "Rahul"))
        val sam = group.members.first { it.name == "Sam" }
        val rahul = group.members.first { it.name == "Rahul" }
        Removal.remove(s, group, sam, null, "Prithvi", emptyList(), shared = false)
        assertFalse("Sam is out", s.namedGroups().first().members.any { it.id == sam.id })
        val g = group.group.id
        fun item(by: String) = ActivityFeed.Item("e-$by", g, ActivityFeed.Kind.ADDED, by, 1L, "Taxi", 100L, "INR")
        val groups = s.namedGroups().associateBy { it.group.id }
        val names = mapOf(g to s.historyNames(g))
        assertEquals("the removed author", "Sam", ActivityFeed.who(item(sam.id), groups, names))
        assertEquals("a current member", "Rahul", ActivityFeed.who(item(rahul.id), groups, names))
        assertEquals("Someone", ActivityFeed.who(item("f".repeat(32)), groups, names))
        assertEquals("without the history, as before", "Someone", ActivityFeed.who(item(sam.id), groups, emptyMap()))
        val feed = java.io.File("src/main/java/com/vardrlabs/skipti/ActivityFeed.kt").readText()
        val home = java.io.File("src/main/java/com/vardrlabs/skipti/HomeParts.kt").readText()
        assertTrue("for (g in items.map { it.group }.toSet()) namesByGroup.getOrPut(g) { store.historyNames(g) }" in feed)
        assertTrue("ActivityRow(it, groups[it.group], ActivityFeed.who(it, groups, names), now, dark, onOpenExpense)" in feed)
        assertTrue("item != null -> ActivityFeed.who(item, loaded.groups, loaded.names)" in home)
        assertFalse("no row words it from current members alone", "?.name ?: ActivityFeed.SOMEONE" in feed || "?.name ?: ActivityFeed.SOMEONE" in home)
        assertTrue("iOS words it the same way", "?? names[item.group]?[item.by] ?? someone" in java.io.File("../../ios/Skipti/App/ActivityFeed.swift").readText())
    }

    @Test
    fun a_redistribution_is_a_history_line_with_the_groups_names() {
        val g = "c".repeat(32)
        val other = "e".repeat(32)
        val dev = "b".repeat(32)
        fun moved(group: String, blob: ByteArray, millis: ULong) =
            RecordWire(1u, 6u, group, 0u, millis, 0u, dev, dev, Removal.redistributionSession, 0u, blob)
        val sam = pid(4)
        val blob = ffiEncodeRedistribution(
            RedistributionInput(
                g, sam, "INR", listOf(IdAmount(pid(1), -33_334), IdAmount(pid(2), -33_333), IdAmount(pid(3), -33_333)),
            ),
        )
        val elsewhere = ffiEncodeRedistribution(RedistributionInput(other, sam, "INR", listOf(IdAmount(pid(1), -100))))
        val records = listOf(
            moved(g, blob, 2_000uL), moved(g, byteArrayOf(1, 2), 3_000uL), moved(other, elsewhere, 4_000uL),
            RecordWire(1u, 2u, g, 0u, 5_000uL, 0u, dev, dev, ByteArray(0), 0u, blob),
        )
        val all = ActivityFeed.moved(records)
        assertEquals("an unreadable record and a payment are not history lines", listOf(g, other), all.map { it.group })
        val mine = ActivityFeed.moved(records, g)
        assertEquals(1, mine.size)
        assertEquals("the shares' sum", 100_000L, mine[0].amountMinor)
        assertEquals(2_000L, mine[0].atMs)
        val names = mapOf(pid(1) to "Prithvi", pid(2) to "Rahul", pid(3) to "Asha", sam to "Sam")
        assertEquals(
            "Sam's ${RolesMoney.format(100_000, "INR")} moved to Prithvi, Rahul and Asha",
            ActivityFeed.movedLine(mine[0], names),
        )
        assertEquals(
            "nobody the phone can name: no dangling to",
            "Sam's ${RolesMoney.format(100_000, "INR")} moved",
            ActivityFeed.movedLine(mine[0], mapOf(sam to "Sam")),
        )
    }

    @Test
    fun still_open_names_every_open_balance_by_currency_largest_first() {
        val names = mapOf(pid(1) to "Prithvi", pid(2) to "Rahul", pid(3) to "Asha", pid(4) to "Sam", pid(5) to "Nina")
        val nets = mapOf(
            "USD" to mapOf(pid(3) to 500L),
            "INR" to mapOf(pid(1) to 70_000L, pid(2) to 50_000L, pid(3) to -20_000L, pid(4) to -100_000L, pid(5) to 0L),
        )
        fun f(n: Long, c: String) = RolesMoney.format(n, c)!!
        assertEquals(
            "joined with a middle dot; Nina is settled and left out",
            listOf(
                "Sam owes ${f(100_000, "INR")}", "Prithvi is owed ${f(70_000, "INR")}", "Rahul is owed ${f(50_000, "INR")}",
                "Asha owes ${f(20_000, "INR")}", "Asha is owed ${f(500, "USD")}",
            ).joinToString(" · "),
            GroupSettings.stillOpen(nets, names),
        )
        assertNull(GroupSettings.stillOpen(mapOf("INR" to mapOf(pid(1) to 0L)), names))
        assertNull(GroupSettings.stillOpen(emptyMap(), names))
    }

    /** L1: the delete confirm names every open balance; you are "you". */
    @Test
    fun still_open_reads_you_and_everyone_else() {
        val names = mapOf(pid(1) to "Prithvi", pid(5) to "Nina")
        val f = RolesMoney.format(50_001, "INR")!!
        val g = RolesMoney.format(2_000, "INR")!!
        assertEquals(
            "you are owed $f · Nina owes $f",
            GroupSettings.stillOpen(mapOf("INR" to mapOf(pid(1) to 50_001L, pid(5) to -50_001L)), names, viewer = pid(1)),
        )
        assertEquals(
            "you owe $g · Nina is owed $g",
            GroupSettings.stillOpen(mapOf("INR" to mapOf(pid(1) to -2_000L, pid(5) to 2_000L)), names, viewer = pid(1)),
        )
        assertEquals(
            "no viewer: named",
            "Prithvi owes $g · Nina is owed $g",
            GroupSettings.stillOpen(mapOf("INR" to mapOf(pid(1) to -2_000L, pid(5) to 2_000L)), names),
        )
    }

    /** L1, from a real ledger: a redistribution moved Sam's balance, and the
     *  line reads what is open after it. */
    @Test
    fun still_open_from_the_real_ledger_after_a_redistribution() = kotlinx.coroutines.runBlocking {
        val s = store()
        val me = s.setYourName("Prithvi")
        val group = s.createGroup("Goa", "INR", listOf("Sam", "Rahul"))
        val sam = group.members.first { it.name == "Sam" }
        val rahul = group.members.first { it.name == "Rahul" }
        s.appendExpense(
            ExpenseDTO(
                payer = me.id, paidMinor = 90_000, currencyCode = "INR", group = group.group.id, conversion = null,
                shares = listOf(me.id, sam.id, rahul.id).map { ExpenseDTO.ShareDTO(it, 30_000) },
            ),
        )
        fun parts(line: String?) = line.orEmpty().split(" · ").filter { it.isNotEmpty() }.sorted()
        val c300 = RolesMoney.format(30_000, "INR")!!
        val c600 = RolesMoney.format(60_000, "INR")!!
        assertEquals(
            listOf("Rahul owes $c300", "Sam owes $c300", "you are owed $c600"),
            parts(GroupSettings.stillOpen(s, group.group.id, me.id)),
        )
        val shares = ffiRedistributeEqual(-30_000, listOf(me.id, rahul.id))
        Removal.remove(s, group, sam, null, "Prithvi", listOf(RedistributionInput(group.group.id, sam.id, "INR", shares)), shared = false)
        val c450 = RolesMoney.format(45_000, "INR")!!
        assertEquals(
            "Sam is out and at zero; the others took it",
            listOf("Rahul owes $c450", "you are owed $c450"),
            parts(GroupSettings.stillOpen(s, group.group.id, me.id)),
        )
        assertNull("a group with nothing in it: nothing open", GroupSettings.stillOpen(s, "9".repeat(32), me.id))
    }

    @Test
    fun a_group_is_renamed_by_an_operation_and_never_to_nothing() {
        val s = store()
        s.setYourName("Prithvi")
        val g = s.createGroup("Goa", "INR", listOf("Sam")).group.id
        s.renameGroup(g, "  Goa trip  ")
        assertEquals("trimmed", "Goa trip", s.namedGroups().first().group.name)
        assertTrue(
            s.allRecords().any { r ->
                r.recordType == 3.toUByte() &&
                    (ffiDecodeGroupOp(r.blob) as? GroupOpIn.RenameGroup)?.name == "Goa trip"
            },
        )
        try { s.renameGroup(g, "   "); fail("a blank name is refused") } catch (_: Exception) {}
        assertEquals("left as it was", "Goa trip", s.namedGroups().first().group.name)
    }

    @Test
    fun a_local_removal_moves_the_balance_and_takes_the_person_out() = kotlinx.coroutines.runBlocking {
        val s = store()
        val me = s.setYourName("Prithvi")
        val group = s.createGroup("Goa", "INR", listOf("Sam", "Rahul"))
        val sam = group.members.first { it.name == "Sam" }
        val rahul = group.members.first { it.name == "Rahul" }
        // Prithvi paid 900, split three ways: Sam owes 300.
        s.appendExpense(
            ExpenseDTO(
                payer = me.id, paidMinor = 90_000, currencyCode = "INR", group = group.group.id, conversion = null,
                shares = listOf(me.id, sam.id, rahul.id).map { ExpenseDTO.ShareDTO(it, 30_000) },
            ),
        )
        fun balance(viewer: String): Long =
            ffiBalanceLine(
                s.loadExpenses().map { it.toInput() }, s.loadSettlements().map { it.toInput() },
                s.loadRedistributions(), emptyList(), viewer, group.group.id,
            ).firstOrNull()?.viewerNetMinor ?: 0L
        assertEquals(-30_000L, balance(sam.id))
        assertEquals(60_000L, balance(me.id))
        val shares = ffiRedistributeEqual(-30_000, listOf(me.id, rahul.id))
        Removal.remove(s, group, sam, null, "Prithvi", listOf(RedistributionInput(group.group.id, sam.id, "INR", shares)), shared = false)
        assertEquals("Sam's goes to zero", 0L, balance(sam.id))
        assertEquals("and the others take it: 600 less 150", 45_000L, balance(me.id))
        assertEquals(listOf("Prithvi", "Rahul"), s.namedGroups().first().members.map { it.name })
        assertEquals("a group only here marks nothing uploaded", 0, CloudRecords.uploaded(s).size)
    }

    @Test
    fun removal_records_applied_for_a_cloud_group_are_marked_uploaded() {
        val s = store()
        s.setYourName("Prithvi")
        val group = s.createGroup("Goa", "INR", listOf("Sam"))
        val sam = group.members.first { it.name == "Sam" }
        val records = Removal.records(s, group.group.id, sam.id, emptyList(), Placing.NONE)
        Removal.applyLocally(records, s, uploaded = true)
        assertEquals(
            "pushAll never sends what the Function already wrote",
            records.map { CloudRecords.documentId(it) }.toSet(), CloudRecords.uploaded(s),
        )
        val pending = CloudRecords.pending(s.allRecords(), CloudRecords.uploaded(s))
            .filter { it.recordType == 3.toUByte() && it.groupId == group.group.id }
            .filter { r -> records.any { CloudRecords.documentId(it) == CloudRecords.documentId(r) } }
        assertEquals(0, pending.size)
        assertEquals(listOf("Prithvi"), s.namedGroups().first().members.map { it.name })
    }

    /** L5: a copy's People shows nobody's role and offers nothing. */
    @Test
    fun a_copys_people_offer_nothing_and_show_no_roles() {
        val roles = RoleRules.Roles(o, listOf(a))
        val members = listOf(MemberWire(pid(1), "Prithvi"), MemberWire(pid(2), "Rahul"), MemberWire(pid(3), "Asha"))
        val claims = mapOf(pid(1) to o, pid(2) to a, pid(3) to m)
        val live = PeopleRoles.rows(members, pid(3), m, claims, roles)
        assertEquals(listOf(OWNER, ADMIN, MEMBER), live.map { it.role })
        val copy = PeopleRoles.rows(members, pid(3), m, claims, roles, readOnly = true)
        assertEquals("no Owner or Admin pill in a copy", listOf(MEMBER, MEMBER, MEMBER), copy.map { it.role })
        val none = RoleRules.Offer(remove = false, ask = false, askSelf = false, makeAdmin = false, removeAdmin = false, makeOwner = false)
        assertEquals(listOf(none, none, none), copy.map { it.offer })
        assertEquals("who is who still reads", live.map { it.you }, copy.map { it.you })
    }

    /** What this phone knows about a group: read strictly. */
    @Test
    fun a_groups_people_are_read_strictly() {
        val s = store()
        s.setYourName("Prithvi")
        val g = s.createGroup("Goa", "INR", listOf("Sam")).group.id
        val p = GroupPeople.load(s, g)
        assertFalse(p.shared)
        assertNull(p.readOnly)
        assertEquals(s.projectGroups().you?.id, p.viewerYou)
        CloudRecords.saveGroupsUp(s, setOf(g))
        s.markReadOnly(g, ReadOnlyDTO("removed", "Asha", 1L))
        val q = GroupPeople.load(s, g)
        assertTrue(q.shared)
        assertEquals("removed", q.readOnly?.kind)
        s.rawKvSet(LedgerStore.READ_ONLY_KEY, "{broken".toByteArray())
        try { GroupPeople.load(s, g); fail("an unreadable mark is an error, never 'not a copy'") } catch (_: LedgerCorruptException) {}
    }

    /** L4: the deleting phone says "You deleted"; twin of the Swift banner test. */
    @Test
    fun the_banner_names_who_did_it() {
        val day = "October 13, 2026"
        val at = 1_800_000_000_000L
        assertEquals("You deleted this group on $day", ReadOnlyBanner.title(ReadOnlyDTO("deleted", "Prithvi", at, byYou = true), day))
        assertEquals("Asha deleted this group on $day", ReadOnlyBanner.title(ReadOnlyDTO("deleted", "Asha", at), day))
        assertEquals("Asha removed you on $day", ReadOnlyBanner.title(ReadOnlyDTO("removed", "Asha", at), day))
    }

    @Test
    fun a_removal_routes_back_to_where_it_was_opened() {
        val g = "c".repeat(32)
        val sam = "d".repeat(32)
        val review = RemovalRoute(g, sam, review = true, fromPeople = false)
        assertEquals("remove/$g/$sam/review/group", review.route())
        assertEquals(review, RemovalRoute.parse(review.route()))
        val plain = RemovalRoute(g, sam, review = false, fromPeople = true)
        assertEquals("remove/$g/$sam/start/people", plain.route())
        assertEquals(plain, RemovalRoute.parse(plain.route()))
        assertNull("an id that is not an id", RemovalRoute.parse("remove/x/$sam/start/people"))
        assertNull(RemovalRoute.parse("remove/$g/$sam/start"))
        assertNull(RemovalRoute.parse("people/$g"))
        assertEquals("people/$g", HomeRoutes.parent(plain.route()))
        assertNull("a request card on a group's page: back to the page", HomeRoutes.parent(review.route()))
        assertNull(HomeRoutes.parent("group-settings/$g"))
        assertNull(HomeRoutes.parent("export-group/$g"))
    }

    /** `--emulators --emulator-as <sub>`: Debug builds only, a look's tool. */
    @Test
    fun emulator_as_signs_in_as_one_subject_in_debug_builds_only() {
        assertEquals("sub-b", ProofArgs.emulatorAs("--emulators --emulator-as sub-b", debuggable = true))
        assertEquals("  spaces are fine", "sub-b", ProofArgs.emulatorAs("  --emulators   --emulator-as  sub-b ", debuggable = true))
        assertNull("never in a release build", ProofArgs.emulatorAs("--emulators --emulator-as sub-b", debuggable = false))
        assertNull("not without --emulators", ProofArgs.emulatorAs("--emulator-as sub-b", debuggable = true))
        assertNull("not without a subject", ProofArgs.emulatorAs("--emulators --emulator-as", debuggable = true))
        assertNull(ProofArgs.emulatorAs("--emulators", debuggable = true))
        assertNull(ProofArgs.emulatorAs(null, debuggable = true))
        // It is the ordinary app, never a headless proof.
        assertNull(ProofArgs.proof("--emulators --emulator-as sub-b", debuggable = true))
        assertNull(ProofArgs.proof("--emulators", debuggable = true))
        assertEquals("--balance", ProofArgs.proof("--balance", debuggable = true))
        assertNull(ProofArgs.proof("--balance", debuggable = false))
        assertTrue(ProofArgs.emulators("--emulators --emulator-as sub-b", debuggable = true))
        assertTrue(ProofArgs.emulators("--emulators", debuggable = true))
        assertFalse(ProofArgs.emulators("--emulators", debuggable = false))
        assertFalse(ProofArgs.emulators("--balance", debuggable = true))
    }

    /** The Activity list: expenses others changed and balances moved, newest first, capped. */
    @Test
    fun the_activity_list_merges_expenses_and_moved_balances_newest_first() {
        fun item(id: String, at: Long) = ActivityFeed.Item(id, "g", ActivityFeed.Kind.ADDED, pid(1), at, "Dinner", 100, "INR")
        fun moved(id: String, at: Long) = ActivityFeed.Moved(id, "g", at, pid(4), "INR", 100, listOf(pid(1)))
        val merged = ActivityFeed.entries(listOf(item("e1", 1_000), item("e2", 3_000)), listOf(moved("m1", 2_000), moved("m2", 3_000)))
        assertEquals("same time: the larger id first", listOf("m2", "e2", "m1", "e1"), merged.map { it.id })
        assertEquals(listOf(true, false, true, false), merged.map { it.moved != null })
        val many = ActivityFeed.entries((1..40).map { item("e$it", it.toLong()) }, (1..40).map { moved("m$it", 100L + it) })
        assertEquals(ActivityFeed.LIMIT, many.size)
        assertEquals("the newest are kept", "m40", many.first().id)
    }

    /** What the removal screen keeps across a rotation: the payments it will
     *  record and how far they got, so Back and Next never record them twice. */
    @Test
    fun the_removal_screens_state_survives_rotation() {
        val g = "c".repeat(32)
        val pay = listOf(SettlementDTO(pid(4), pid(1), 70_000, "INR", g), SettlementDTO(pid(3), pid(4), 500, "USD", g))
        val scope = SaverScope { true }
        val saved = with(settlementsSaver) { scope.save(pay) }
        assertEquals(pay, settlementsSaver.restore(saved!!))
        assertNull("nothing to keep: the default (none) stands", with(settlementsSaver) { scope.save(emptyList()) })
        // Progress: none yet saves nothing; some saves where it got to.
        assertNull(with(progressSaver) { scope.save(null) })
        val partly = SettleFirstProgress(2, appended = 1)
        val back = progressSaver.restore(with(progressSaver) { scope.save(partly) }!!)!!
        assertEquals(listOf(2, 1, false), listOf(back.total, back.appended, back.removed))
        assertTrue("the balance has already moved", back.started)
    }

    // ---- Task 8 review, Fix 1: work that outlives a screen ----

    /** The removal's and the delete's work runs in the app, keyed by what it
     *  changes: a second start while it runs (a rotated screen's tap) never
     *  runs it again (a Done nobody saw is replaced: GroupFixesTest). */
    @Test
    fun a_removal_started_twice_runs_once() = kotlinx.coroutines.runBlocking {
        val key = GroupJobs.Key.removal("d".repeat(32), pid(4))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val runs = java.util.concurrent.atomic.AtomicInteger()
        try {
            val first = GroupJobs.start(key, { "x" }) { runs.incrementAndGet(); gate.await() }
            assertNotNull("the first start runs", first)
            assertEquals(GroupJobs.Status.Running, GroupJobs.status(key))
            assertNull("refused while it runs", GroupJobs.start(key, { "x" }) { runs.incrementAndGet() })
            gate.complete(Unit)
            first!!.join()
            assertEquals(GroupJobs.Status.Done, GroupJobs.status(key))
            assertEquals("the work ran once", 1, runs.get())
            // Another group's delete is another key: it runs.
            val other = GroupJobs.Key.delete("d".repeat(32))
            GroupJobs.start(other, { "x" }) { runs.incrementAndGet() }!!.join()
            assertEquals(2, runs.get())
            GroupJobs.clear(other)
            // Seen: a later removal of the same person (added again) runs.
            // (A Done nobody saw does not stop it either: GroupFixesTest.)
            GroupJobs.clear(key)
            assertNull(GroupJobs.status(key))
            GroupJobs.start(key, { "x" }) { runs.incrementAndGet() }!!.join()
            assertEquals(3, runs.get())
        } finally {
            GroupJobs.clear(key)
        }
    }

    /** A failure is words the screen shows, and a tap may try again. A running
     *  job is never cleared by a screen leaving. */
    @Test
    fun a_failed_removal_says_why_and_may_be_retried() = kotlinx.coroutines.runBlocking {
        val key = GroupJobs.Key.removal("e".repeat(32), pid(5))
        try {
            GroupJobs.start(key, { "words: ${it.message}" }) { throw IllegalStateException("offline") }!!.join()
            assertEquals(GroupJobs.Status.Failed("words: offline"), GroupJobs.status(key))
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val retry = GroupJobs.start(key, { "x" }) { gate.await() }
            assertNotNull("a failure may be retried", retry)
            GroupJobs.clear(key)
            assertEquals("a running job is not cleared", GroupJobs.Status.Running, GroupJobs.status(key))
            gate.complete(Unit)
            retry!!.join()
            assertEquals(GroupJobs.Status.Done, GroupJobs.status(key))
        } finally {
            GroupJobs.clear(key)
        }
    }

    /** Settle first's progress lives with the job: a screen made again (a
     *  rotation, the process alive) reads how far the job got, never its own
     *  saved copy from before, and its tap is refused while the job runs. */
    @Test
    fun settle_first_progress_survives_a_recreated_screen() = kotlinx.coroutines.runBlocking {
        val g = "f".repeat(32)
        val key = GroupJobs.Key.removal(g, pid(4))
        val pay = listOf(SettlementDTO(pid(4), pid(1), 70_000, "INR", g), SettlementDTO(pid(3), pid(4), 500, "USD", g))
        val appended = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            // The first screen's copy (as rememberSaveable restored it) is adopted.
            val saved = SettleFirstProgress(pay.size)
            val p = GroupJobs.settleProgress(key, pay, saved)
            assertTrue("the job takes the screen's copy", p === saved)
            val job = GroupJobs.start(key, { "x" }) {
                p.run(
                    append = { i -> appended += i; if (i == 1) { reached.complete(Unit); gate.await() } },
                    sync = { true }, remove = {},
                )
            }!!
            reached.await()
            // The screen is made again: its saved copy is from before the tap.
            val stale = SettleFirstProgress(pay.size)
            val seen = GroupJobs.settleProgress(key, pay, stale)
            assertTrue("the recreated screen reads the job's progress", seen === p)
            assertEquals(1, seen.appended)
            assertTrue(seen.started)
            assertNull("its tap is refused while the job runs",
                GroupJobs.start(key, { "x" }) { seen.run(append = { i -> appended += i }, sync = { true }, remove = {}) })
            gate.complete(Unit)
            job.join()
            assertEquals("every payment once", listOf(0, 1), appended.toList())
            assertTrue(seen.removed)
            // Other payments (the balance changed since) never take this progress.
            val fresh = GroupJobs.settleProgress(key, pay.take(1), null)
            assertTrue(fresh !== p)
            assertEquals(0, fresh.appended)
            // Seen and cleared: the progress goes with it.
            GroupJobs.clear(key)
            assertNull(GroupJobs.progress(key, pay))
        } finally {
            GroupJobs.clear(key)
        }
    }

    /** What group settings offers (twin of RolesTests.swift): a copy offers
     *  nothing that changes the group; in the cloud only the owner deletes it;
     *  a group only on this phone is anyone's to delete. */
    @Test
    fun group_settings_offers_nothing_on_a_copy() {
        fun a(name: Boolean, picture: Boolean, delete: Boolean) = GroupSettings.Actions(name, picture, delete)
        val none = a(name = false, picture = false, delete = false)
        assertEquals(none, GroupSettings.actions(readOnly = true, isOwner = true, shared = true))
        assertEquals(none, GroupSettings.actions(readOnly = true, isOwner = false, shared = true))
        assertEquals(none, GroupSettings.actions(readOnly = true, isOwner = true, shared = false))
        assertEquals(none, GroupSettings.actions(readOnly = true, isOwner = false, shared = false))
        assertEquals(a(true, true, true), GroupSettings.actions(readOnly = false, isOwner = true, shared = true))
        assertEquals(a(true, true, false), GroupSettings.actions(readOnly = false, isOwner = false, shared = true))
        assertEquals(a(true, true, true), GroupSettings.actions(readOnly = false, isOwner = true, shared = false))
        assertEquals(a(true, true, true), GroupSettings.actions(readOnly = false, isOwner = false, shared = false))
    }

    /** `--emulator-as` signs in once per process, not on every onCreate (a rotation). */
    @Test
    fun emulator_as_signs_in_once_per_process() {
        EmulatorSignIn.started.set(false)
        assertTrue(EmulatorSignIn.firstThisProcess())
        assertFalse(EmulatorSignIn.firstThisProcess())
        assertFalse(EmulatorSignIn.firstThisProcess())
    }

    private fun s225(name: String): String = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    /** A declaration's text in [file], to its closing brace at column 0. */
    private fun body225(file: String, decl: String): String {
        val text = s225(file)
        val at = text.indexOf(decl)
        assertTrue("$file declares $decl", at >= 0)
        val end = text.indexOf("\n}\n", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    // ---- D-225 Task 5 (Designs 7, 8): Group settings and People in list cards (twins of iOS's Task 4) ----

    /** Group settings: one list card (Name, Picture, Settles in) and a danger card with Delete group; the private
     *  SettingsRow and the outlined field are gone; every confirmation stays. The twin of iOS's
     *  testGroupSettingsAndPeopleLeftTheForm (its Group settings half). */
    @Test
    fun group_settings_is_list_cards() {
        val t = s225("GroupSettings.kt")
        assertFalse("fun SettingsRow(" in t)
        assertFalse("OutlinedTextField" in t)
        val screen = body225("GroupSettings.kt", "fun GroupSettingsScreen(")
        assertEquals(2, screen.split("ListCard(dark)").size - 1)
        for (s in listOf(
            "RowTrailing.Value(n.group.name, chevron = true)", // D-225 fix wave (F3): the tile sized to its slot (AppearanceTest.a_drawn_piece_sits_inside_its_slot).
            "RowTrailing.Piece(chevron = true) { GroupTile(n.group, dark, ListRows.pieceTile(TileShape.of(Appearance.theme, person = false))) }",
            "RowTrailing.Value(n.group.settlementCurrency, chevron = false)", "tone = RowTone.DANGER", "RolesCopy.DELETE_GROUP_SUB",
            "title = { Text(RolesCopy.SETTINGS_NAME) }", "RolesCopy.deleteCloudTitle(", "RolesCopy.deleteLocalTitle(",
        )) assertTrue("Group settings: $s", s in screen)
    }

    /** People (L-D217-8): the "People" label over one list card of members, the stays caption, the "Invite" card of
     *  list rows with chevrons and its caption; the rename field is a field row; the removable cross is Lucide's x at
     *  `clearGlyph` in `textFaint`, no drawn strokes. The twin of iOS's People guard. */
    @Test
    fun people_page_twins_ios() {
        val people = body225("GroupScreens.kt", "fun PeopleScreen(")
        for (s in listOf(
            "SectionLabel(\"People\", dark)", "SectionLabel(InviteCopy.SECTION, dark)",
            "ListRow(InviteCopy.invite(member.name), dark, trailing = RowTrailing.Chevron)",
            "CardCaption(InviteCopy.SECTION_FOOTER, dark)", "CardCaption(MemberCopy.STAYS, dark)", "FieldRow(",
        )) assertTrue("People: $s", s in people)
        assertEquals("members and invites", 2, people.split("ListCard(dark)").size - 1)
        assertTrue("the members' card after its label", people.indexOf("SectionLabel(\"People\", dark)") < people.indexOf("ListCard(dark)"))
        assertFalse("no accent invite text", "Tok.groundAccent(dark)" in people)
        assertFalse("no outlined rename", "NameField(" in people)
        val cross = body225("GroupScreens.kt", "internal fun RemoveGlyphButton(")
        for (s in listOf("TabIcons.X", "ThemeArt.m(\"clearGlyph\")", "Tok.textFaint(dark)", "ThemeArt.m(\"touchTarget\")")) {
            assertTrue("RemoveGlyphButton: $s", s in cross)
        }
        assertFalse("no drawn cross", "drawLine" in cross)
    }
}
