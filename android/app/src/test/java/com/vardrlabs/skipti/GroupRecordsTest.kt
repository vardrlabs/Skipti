package com.vardrlabs.skipti

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.ClaimDeviceIn
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.GroupOpMemberIn
import uniffi.skipti_core.GroupWire
import uniffi.skipti_core.HlcStamp
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.RecordWire
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.ffiDecodeGroupOp

/**
 * D-153: groups and "you" are records on Android (core D-152), the twin of
 * iOS's GroupRecordsTests. The founder's decision (2026-09-24): one "you" per
 * account; a new device adopts it. Two vaults stand in for two devices; "sync"
 * is applying one's records to the other, exactly what a pull does. In the
 * shared test set: both flavours run it.
 */
class GroupRecordsTest {

    private val dir = Files.createTempDirectory("group-records")

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

    /** A pull: every record of [from] applied to [to], then the rebuild. */
    private fun sync(from: LedgerStore, to: LedgerStore) {
        for (record in from.allRecords()) to.applyRecord(record)
        to.rebuildProjection()
    }

    private fun groupOps(store: LedgerStore): List<Pair<RecordWire, GroupOpIn>> =
        store.allRecords().filter { it.recordType == LedgerStore.GROUP_OP_TYPE }
            .map { it to ffiDecodeGroupOp(it.blob) }

    /** HLCs are milliseconds: a short pause makes "later" unambiguous. */
    private fun later() = Thread.sleep(20)

    // ---- Writes ----

    @Test
    fun naming_you_and_creating_a_group_write_records_in_their_scopes() {
        val a = device("a")
        val me = a.setYourName("Tim")
        val group = a.createGroup("Goa", "INR", listOf("Rahul"))
        val ops = groupOps(a)
        val yous = ops.filter { it.second is GroupOpIn.You }
        assertEquals("created, then named", 2, yous.size)
        assertTrue("you is PERSONAL scope, never a group's", yous.all { it.first.groupId == null })
        assertEquals(GroupOpIn.You(me.id, "Tim"), yous.last().second)
        val creates = ops.filter { it.second is GroupOpIn.Create }
        assertEquals(1, creates.size)
        assertEquals("a group operation takes its group's scope", group.group.id, creates[0].first.groupId)
        assertEquals("the kv groups are rebuilt from the records", listOf("Goa"), a.loadGroups().map { it.name })
    }

    @Test
    fun adding_and_renaming_write_only_their_operations() {
        val a = device("a")
        a.setYourName("Tim")
        val group = a.createGroup("Goa", "INR", listOf("Rahul"))
        val before = groupOps(a).size
        val priya = a.addMember(group.group.id, "Priya").members.last()
        a.renameMember(group.group.id, priya.id, "Priya S")
        assertEquals(
            listOf(
                GroupOpIn.AddMember(group.group.id, GroupOpMemberIn(priya.id, "Priya")),
                GroupOpIn.RenameMember(group.group.id, priya.id, "Priya S"),
            ),
            groupOps(a).drop(before).map { it.second },
        )
    }

    // ---- Restore and adoption ----

    /** The D-151 gap closed: a device that has nothing gets the groups and
     *  "you" back, byte for byte. */
    @Test
    fun a_restored_device_gets_its_groups_and_you_back() {
        val a = device("a")
        val me = a.setYourName("Tim")
        val group = a.createGroup("Goa", "INR", listOf("Rahul", "Priya"))
        val restored = device("restored")
        sync(a, restored)
        assertArrayEquals(a.rawKvGet(LedgerStore.GROUPS_KEY), restored.rawKvGet(LedgerStore.GROUPS_KEY))
        assertEquals(SelfDTO(me.id, "Tim"), restored.loadSelf())
        val named = restored.namedGroups().first()
        assertEquals(group, named)
        assertEquals("Tim (You)", LedgerStore.label(me.id, named))
    }

    /** The common case: a second device already made its own "you" at first
     *  launch; on its first pull it adopts the account's (the earliest), and
     *  keeps its own as an alias for the merge. */
    @Test
    fun a_second_device_adopts_the_accounts_you() {
        val a = device("a")
        val me = a.setYourName("Tim")
        a.createGroup("Goa", "INR", listOf("Rahul"))
        later()
        val b = device("b")
        val own = b.ensureSelf()
        assertNotEquals(me.id, own.id)
        sync(a, b)
        val adopted = b.loadSelf()!!
        assertEquals(me.id, adopted.id)
        assertEquals("Tim", adopted.name)
        assertEquals(listOf(own.id), adopted.aliases)
        val made = b.createGroup("Paris", "EUR", emptyList())
        assertEquals("new groups are made under the account's you", me.id, made.selfId)
    }

    /** A group made on B under B's own "you" still loads after B adopts A's,
     *  and since D-154 it holds the account's one "you" (core merges the
     *  member), shown as "(You)". Negative: before either, ffiNamedGroup
     *  refused the group and the whole group list failed to load. */
    @Test
    fun a_group_made_under_another_you_still_loads_as_yours() {
        val a = device("a")
        a.setYourName("Tim")
        a.createGroup("Goa", "INR", listOf("Rahul"))
        later()
        val b = device("b")
        val ownB = b.setYourName("Tim")
        b.createGroup("Paris", "EUR", listOf("Anna"))
        sync(a, b)
        val groups = b.namedGroups()
        assertEquals(listOf("Goa", "Paris"), groups.map { it.group.name })
        val paris = groups.last()
        val you = b.loadSelf()!!.id
        assertNotEquals(ownB.id, you)
        assertEquals("D-154: the account's you, merged in", you, paris.selfId)
        assertTrue(ownB.id !in paris.group.members)
        assertEquals("Tim (You)", LedgerStore.label(you, paris))
    }

    /** D-154, the founder's merge: two "you"s are ONE person in what people
     *  see (iOS twin: testTwoYousAreOnePersonInTheLedger). */
    @Test
    fun two_yous_are_one_person_in_the_ledger() {
        val a = device("a")
        val meA = a.setYourName("Tim")
        a.createGroup("Goa", "INR", listOf("Rahul"))
        later()
        val b = device("b")
        val ownB = b.setYourName("Tim")
        val paris = b.createGroup("Paris", "EUR", listOf("Anna"))
        val anna = paris.members.last().id
        b.appendExpense(
            ExpenseDTO(
                payer = ownB.id, paidMinor = 4000, currencyCode = "EUR", group = paris.group.id,
                conversion = null,
                shares = listOf(ExpenseDTO.ShareDTO(ownB.id, 2000), ExpenseDTO.ShareDTO(anna, 2000)),
                note = "Dinner", timestampMs = 1_000, category = null, addedBy = ownB.id,
            ),
        )
        b.appendSettlement(SettlementDTO(ownB.id, meA.id, 500, "EUR", null))
        sync(a, b)
        sync(b, a)
        for (device in listOf(a, b)) {
            assertEquals("one expense, never a re-minted copy", 1, device.loadExpenses().size)
            val expense = device.loadExpenses().first()
            assertEquals(meA.id, expense.payer)
            assertEquals(listOf(meA.id, anna), expense.shares.map { it.id })
            assertEquals(4000L, expense.shares.sumOf { it.amountMinor })
            assertEquals(meA.id, device.projectedExpenses().expenses.first().addedBy)
            assertEquals("a payment to yourself is not a payment", emptyList<SettlementDTO>(), device.loadSettlements())
        }
        val payers = b.allRecords().filter { it.recordType == 1u.toUByte() }
            .map { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString(ExpenseDTO.serializer(), it.blob.decodeToString()).payer }
            .toSet()
        assertEquals("the record keeps what B wrote", setOf(ownB.id), payers)
    }

    // ---- Backfill and the union rule ----

    /** A vault from before D-153 kept groups and "you" only in kv. They get
     *  records (once), and no rebuild ever erases them. */
    @Test
    fun kv_only_groups_are_backfilled_once_and_never_erased() {
        val a = device("a")
        val me = SelfDTO("0".repeat(31) + "1", "Tim")
        val good = GroupDTO(
            id = "0".repeat(31) + "9", name = "Goa", settlementCurrency = "INR",
            members = listOf(me.id, "0".repeat(31) + "2"),
            memberNames = listOf(MemberDTO(me.id, "Tim")),
        )
        // Core refuses a group name with a leading space: it cannot become a
        // record, so only the union rule keeps it.
        val damaged = GroupDTO(
            id = "0".repeat(31) + "8", name = " Old", settlementCurrency = "INR", members = listOf(me.id),
        )
        a.rawKvSet(LedgerStore.SELF_KEY, """{"id":"${me.id}","name":"Tim"}""".toByteArray())
        a.rawKvSet(
            LedgerStore.GROUPS_KEY,
            ("""[{"id":"${good.id}","name":"Goa","settlementCurrency":"INR","members":["${me.id}","${"0".repeat(31)}2"],""" +
                """"memberNames":[{"id":"${me.id}","name":"Tim"}]},""" +
                """{"id":"${damaged.id}","name":" Old","settlementCurrency":"INR","members":["${me.id}"]}]""")
                .toByteArray(),
        )

        a.rebuildProjection()
        val ops = groupOps(a).map { it.second }
        assertEquals("one create for the good group, one you", 2, ops.size)
        assertTrue(GroupOpIn.You(me.id, "Tim") in ops)
        assertEquals("nothing erased", setOf(good.id, damaged.id), a.loadGroups().map { it.id }.toSet())

        a.rebuildProjection()
        assertEquals("a second run mints nothing", 2, groupOps(a).size)
        assertEquals(0, a.backfillGroupRecords())
    }

    /** A rebuild that cannot read the kv groups must not overwrite them. */
    @Test
    fun an_unreadable_group_list_is_never_overwritten() {
        val a = device("a")
        a.rawKvSet(LedgerStore.GROUPS_KEY, "{not json".toByteArray())
        try {
            a.rebuildProjection()
            fail("must refuse")
        } catch (_: LedgerCorruptException) {
        }
        assertArrayEquals("{not json".toByteArray(), a.rawKvGet(LedgerStore.GROUPS_KEY))
    }

    // ---- Convergence ----

    /** Two devices rename the same person offline; after both pull, both show
     *  the later rename (last writer wins), byte for byte. */
    @Test
    fun concurrent_renames_converge_on_both_devices() {
        val a = device("a")
        a.setYourName("Tim")
        val group = a.createGroup("Goa", "INR", listOf("Rahul"))
        val rahul = group.members.last().id
        val b = device("b")
        sync(a, b)
        a.renameMember(group.group.id, rahul, "Ravi")
        later()
        b.renameMember(group.group.id, rahul, "Rahul K")
        sync(a, b)
        sync(b, a)
        assertArrayEquals(a.rawKvGet(LedgerStore.GROUPS_KEY), b.rawKvGet(LedgerStore.GROUPS_KEY))
        assertEquals("Rahul K", a.namedGroups().first().members.last().name)
    }

    // ---- The diff, on its own ----

    @Test
    fun the_diff_writes_only_what_changed() {
        val g = "0".repeat(31) + "9"
        fun named(name: String, members: List<Pair<String, String>>) = NamedGroupWire(
            GroupWire(g, name, "INR", members.map { it.first }),
            members[0].first,
            members.map { MemberWire(it.first, it.second) },
        )
        val tim = "0".repeat(31) + "1" to "Tim"
        val before = named("Goa", listOf(tim, "0".repeat(31) + "2" to "Rahul"))
        val after = named(
            "Goa 2026",
            listOf(tim, "0".repeat(31) + "2" to "Rahul K", "0".repeat(31) + "3" to "Priya"),
        )
        assertEquals(
            listOf(
                GroupOpIn.RenameGroup(g, "Goa 2026"),
                GroupOpIn.RenameMember(g, "0".repeat(31) + "2", "Rahul K"),
                GroupOpIn.AddMember(g, GroupOpMemberIn("0".repeat(31) + "3", "Priya")),
            ),
            LedgerStore.operations(listOf(before), listOf(after), setOf(g)),
        )
        assertEquals("no change, no op", emptyList<GroupOpIn>(), LedgerStore.operations(listOf(after), listOf(after), setOf(g)))
        assertTrue(
            "an uncreated group is saved as a create",
            LedgerStore.operations(listOf(before), listOf(after), emptySet()).first() is GroupOpIn.Create,
        )
    }

    /** D-157: a claim ("this member is me") belongs to its group's log, like
     *  every group operation; only "you" is personal. */
    @Test
    fun a_claim_takes_its_groups_scope() {
        val g = "0".repeat(31) + "9"
        val claim = GroupOpIn.Claim(
            g,
            "0".repeat(31) + "2",
            "0".repeat(30) + "20",
            listOf(ClaimDeviceIn("0".repeat(30) + "30", "ab".repeat(32))),
        )
        val record = LedgerStore.groupOpRecord(claim, HlcStamp(1uL, 0u), "0".repeat(30) + "30")
        assertEquals(g, record.groupId)
        assertEquals(claim, ffiDecodeGroupOp(record.blob))
    }

    // ---- D-229: the write split (one account, two phones) ----
    // The founder scenario as core's test 7 has it (FounderVault below): y =
    // hex(1) (phone Q's "you", the account's earliest), x = hex(2) (phone P's),
    // c = hex(3) (account C); Old = hex(9) made on P under x, New = hex(8) made
    // on Q under y. This vault is phone P after it adopted y. Core: Old's place
    // and youAs are x; New's place is y and youAs none.

    private fun lastOf(store: LedgerStore, type: UByte): RecordWire =
        store.allRecords().filter { it.recordType == type }.maxWithOrNull(
            compareBy<RecordWire> { it.hlcMillis }.thenBy { it.hlcCounter },
        )!!

    private fun expenseIn(store: LedgerStore): ExpenseDTO =
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(ExpenseDTO.serializer(), lastOf(store, 1u).blob.decodeToString())

    private fun settlementIn(store: LedgerStore): SettlementDTO =
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(SettlementDTO.serializer(), lastOf(store, 2u).blob.decodeToString())

    /** Test 1: an expense in Old is WRITTEN as x (payer, shares, added by);
     *  the kv ledger still reads y, and a rebuild leaves its bytes unchanged. */
    @Test
    fun d229_an_expense_in_old_is_written_as_the_place() {
        val p = FounderVault.p(device("p"), pClaim = 2)
        val dinner = FounderVault.expense(9, 1, listOf(1 to 600L, 3 to 600L))
        p.appendExpense(dinner)
        assertEquals(
            "the record names x",
            FounderVault.expense(9, 2, listOf(2 to 600L, 3 to 600L)).copy(addedBy = FounderVault.hex(2)),
            expenseIn(p),
        )
        assertEquals("kv still shows y", listOf(dinner), p.loadExpenses())
        val before = p.rawKvGet(LedgerStore.EXPENSES_KEY)
        val groupsBefore = p.rawKvGet(LedgerStore.GROUPS_KEY)
        p.rebuildProjection()
        assertArrayEquals("the rebuild reads x back as y", before, p.rawKvGet(LedgerStore.EXPENSES_KEY))
        assertArrayEquals(groupsBefore, p.rawKvGet(LedgerStore.GROUPS_KEY))
    }

    /** Test 2: in New ("you" is readable there) the record keeps y; a
     *  personal expense is untouched. */
    @Test
    fun d229_new_and_personal_expenses_keep_you() {
        val p = FounderVault.p(device("p"), pClaim = 2)
        val lunch = FounderVault.expense(8, 1, listOf(1 to 600L, 3 to 600L))
        p.appendExpense(lunch)
        assertEquals(lunch, expenseIn(p))
        val own = ExpenseDTO(
            payer = FounderVault.hex(1), paidMinor = 500, currencyCode = "INR",
            shares = listOf(ExpenseDTO.ShareDTO(FounderVault.hex(1), 500)), addedBy = FounderVault.hex(1),
        )
        p.appendExpense(own)
        assertEquals(own, expenseIn(p))
    }

    /** Test 3: a payment y to c in Old is recorded x to c; one whose two ends
     *  both become x is refused and writes nothing. */
    @Test
    fun d229_a_settlement_in_old_is_written_as_the_place() {
        val p = FounderVault.p(device("p"), pClaim = 2)
        val paid = SettlementDTO(FounderVault.hex(1), FounderVault.hex(3), 600, "INR", FounderVault.hex(9))
        p.appendSettlement(paid)
        assertEquals(paid.copy(from = FounderVault.hex(2)), settlementIn(p))
        assertEquals("kv still shows y", listOf(paid), p.loadSettlements())
        val records = p.allRecords().size
        try {
            p.appendSettlement(paid.copy(to = FounderVault.hex(2)))
            fail("y to x in Old is x to x")
        } catch (_: Placing.SameEnds) {
        }
        assertEquals("nothing written", records, p.allRecords().size)
        assertEquals(listOf(paid), p.loadSettlements())
    }

    /** Test 4: your name goes to Old as x's rename and to New as y's, and it
     *  lands in both (nothing pending). */
    @Test
    fun d229_your_name_is_written_as_the_place_in_each_group() {
        val p = FounderVault.p(device("p"), pClaim = 2)
        val before = groupOps(p).size
        p.setYourName("Alan")
        val renames = groupOps(p).drop(before).map { it.second }.filterIsInstance<GroupOpIn.RenameMember>()
        assertEquals(
            setOf(
                GroupOpIn.RenameMember(FounderVault.hex(9), FounderVault.hex(2), "Alan"),
                GroupOpIn.RenameMember(FounderVault.hex(8), FounderVault.hex(1), "Alan"),
            ),
            renames.toSet(),
        )
        assertEquals(2, renames.size)
        assertEquals("nothing waits for a member nobody added", emptyList<String>(), p.projectGroups().pending)
        for (named in p.namedGroups()) {
            assertEquals("Alan", named.members.first { it.id == named.selfId }.name)
        }
    }

    /** Test 5: an edit of an Old expense names x as its editor and in its
     *  money; the seen list is untouched. */
    @Test
    fun d229_an_edit_in_old_is_written_as_the_place() {
        val p = FounderVault.p(device("p"), pClaim = 2)
        p.appendExpense(FounderVault.expense(9, 3, listOf(1 to 600L, 3 to 600L)))
        val target = p.projectedExpenses().expenses.single().id
        val seen = listOf(target)
        p.reviseExpense(
            uniffi.skipti_core.ExpenseRevisionIn(
                target = target, seen = seen, editor = FounderVault.hex(1),
                money = FounderVault.expense(9, 1, listOf(1 to 700L, 3 to 700L)).toInput(),
                note = null, category = null, deleted = null,
            ),
        )
        val written = uniffi.skipti_core.ffiDecodeExpenseRevision(lastOf(p, LedgerStore.REVISION_TYPE).blob)
        assertEquals(FounderVault.hex(2), written.editor)
        // The revision's codec carries no group in its money (the record's scope does).
        assertEquals(FounderVault.expense(9, 2, listOf(2 to 700L, 3 to 700L)).toInput().copy(group = null), written.money)
        assertEquals(seen, written.seen)
        assertEquals("kv reads y", FounderVault.hex(1), p.loadExpenses().single().payer)
    }

    /** Test 6: a removal's redistribution with a share to y in Old names x. */
    @Test
    fun d229_a_redistribution_in_old_is_written_as_the_place() {
        val p = FounderVault.p(device("p"), pClaim = 2)
        val r = uniffi.skipti_core.RedistributionInput(
            FounderVault.hex(9), FounderVault.hex(3), "INR",
            listOf(uniffi.skipti_core.IdAmount(FounderVault.hex(1), 600)),
        )
        val records = Removal.records(p, FounderVault.hex(9), FounderVault.hex(3), listOf(r), Placing.load(p))
        val written = uniffi.skipti_core.ffiDecodeRedistribution(records.first().blob)
        assertEquals(r.copy(shares = listOf(uniffi.skipti_core.IdAmount(FounderVault.hex(2), 600))), written)
        assertEquals(
            GroupOpIn.RemoveMember(FounderVault.hex(9), FounderVault.hex(3)),
            ffiDecodeGroupOp(records.last().blob),
        )
        assertTrue("built, not kept", p.allRecords().none { it.recordType == LedgerStore.REDISTRIBUTION_TYPE })
    }

    private fun claims(ops: List<GroupOpIn>): List<Triple<String, String, String>> =
        ops.map { it as GroupOpIn.Claim }.map { Triple(it.groupId, it.memberId, it.youId) }

    /** Test 7: the creator's claim names the place. Case (ii) ({x, you x}
     *  stands): nothing for Old. Case (i) (no claim on x): {Old, x, you y},
     *  which lands, so the next push writes nothing (before D-229 it claimed y,
     *  which is no member of Old, and every push wrote another). New, with no
     *  claim of the account: {New, y, you y}, as before. */
    @Test
    fun d229_the_creators_claim_names_the_place() {
        val y = FounderVault.hex(1)
        val x = FounderVault.hex(2)
        val old = FounderVault.hex(9)
        val new = FounderVault.hex(8)
        val two = FounderVault.p(device("ii"), pClaim = 2)
        val dev = LedgerStore.Sync.deviceId(two)
        assertEquals(listOf(Triple(new, y, y)), claims(CloudRecords.ownClaims(two.projectGroups(), dev)))
        val one = FounderVault.p(device("i"), pClaim = null)
        val first = CloudRecords.ownClaims(one.projectGroups(), dev)
        assertEquals(setOf(Triple(old, x, y), Triple(new, y, y)), claims(first).toSet())
        assertEquals(2, first.size)
        one.appendGroupOps(first, Placing.NONE)
        assertEquals("the claims landed: no leak", emptyList<GroupOpIn>(), CloudRecords.ownClaims(one.projectGroups(), dev))
        assertEquals(emptyList<String>(), one.projectGroups().pending)
    }

    /** Test 8: no account, one "you": nothing is mapped anywhere. */
    @Test
    fun d229_one_you_writes_as_before() {
        val a = device("a")
        val me = a.setYourName("Tim")
        val group = a.createGroup("Goa", "INR", listOf("Rahul"))
        val g = group.group.id
        val rahul = group.members.first { it.id != me.id }.id
        val placing = Placing.load(a)
        val p = a.projectGroups()
        assertEquals(null, p.groups.single().youAs)
        val e = ExpenseDTO(me.id, 600, "INR", g, shares = listOf(ExpenseDTO.ShareDTO(me.id, 300), ExpenseDTO.ShareDTO(rahul, 300)), addedBy = me.id)
        assertEquals(e, placing.expense(e))
        assertEquals(e, Placing.of(p).expense(e))
        val s = SettlementDTO(me.id, rahul, 300, "INR", g)
        assertEquals(s, placing.settlement(s))
        val rev = uniffi.skipti_core.ExpenseRevisionIn("t", emptyList(), me.id, e.toInput(), null, null, null)
        assertEquals(rev, placing.revision(rev, g))
        val r = uniffi.skipti_core.RedistributionInput(g, rahul, "INR", listOf(uniffi.skipti_core.IdAmount(me.id, 300)))
        assertEquals(r, placing.redistribution(r))
        val op = GroupOpIn.RenameMember(g, me.id, "Tim")
        assertEquals(op, placing.op(op))
        assertEquals(me.id, placing.id(me.id, g))
        a.appendExpense(e)
        assertEquals(e, expenseIn(a))
    }

    // ---- D-229 follow-up ----

    /** Item 4 (T3/T4 M1): an import's expense in Old is written as x, as an
     *  added one is; kv keeps y. Fails if appendExpenses passes Placing.NONE. */
    @Test
    fun d229_an_import_in_old_is_written_as_the_place() {
        val p = FounderVault.p(device("import"), pClaim = 2)
        val row = FounderVault.expense(9, 1, listOf(1 to 600L, 3 to 600L))
        p.appendExpenses(listOf(row))
        assertEquals(
            "the record names x",
            FounderVault.expense(9, 2, listOf(2 to 600L, 3 to 600L)).copy(addedBy = FounderVault.hex(2)),
            expenseIn(p),
        )
        assertEquals("kv still shows y", listOf(row), p.loadExpenses())
    }

    /** Item 4: a removal on this phone (Removal.remove, the production path)
     *  writes its redistribution's share to y in Old as x. Fails if remove
     *  passes Placing.NONE. */
    @Test
    fun d229_a_removal_in_old_is_written_as_the_place() = kotlinx.coroutines.runBlocking {
        val p = FounderVault.p(device("remove"), pClaim = 2)
        val old = p.namedGroups().first { it.group.id == FounderVault.hex(9) }
        val bo = old.members.first { it.id == FounderVault.hex(3) }
        val r = uniffi.skipti_core.RedistributionInput(
            FounderVault.hex(9), FounderVault.hex(3), "INR",
            listOf(uniffi.skipti_core.IdAmount(FounderVault.hex(1), 600)),
        )
        Removal.remove(p, old, bo, null, "Al", listOf(r), shared = false)
        val written = uniffi.skipti_core.ffiDecodeRedistribution(lastOf(p, LedgerStore.REDISTRIBUTION_TYPE).blob)
        assertEquals(r.copy(shares = listOf(uniffi.skipti_core.IdAmount(FounderVault.hex(2), 600))), written)
    }

    /** Item 1 (decision 45): Old's x is claimed by another account (D, you
     *  hex(4)) and P has no claim of its own there: core says the place is
     *  lost. Old is then a read-only copy through the D-210 marks, with its
     *  own one-line reason; Placing refuses every record for it, and nothing
     *  is written; nothing of Old goes up and no alias is asked there. New is
     *  untouched. */
    @Test
    fun d229_a_group_whose_place_is_lost_is_read_only() {
        val old = FounderVault.hex(9)
        val new = FounderVault.hex(8)
        val p = FounderVault.lost(device("lost"))
        val projected = p.projectGroups().groups.associateBy { it.group.id }
        assertTrue(projected.getValue(old).placeLost)
        assertEquals(false, projected.getValue(new).placeLost)

        // The read-only mark, derived (never stored) and read by every D-210 reader.
        val mark = ReadOnlyDTO(ReadOnlyDTO.PLACE_LOST, "", 0)
        assertEquals(mapOf(old to mark), p.readOnly())
        assertEquals(mapOf(old to mark), p.readOnlyChecked())
        assertEquals(null, p.rawKvGet(LedgerStore.READ_ONLY_KEY))
        assertEquals(
            "You can't add to this group from this phone: someone else holds your place in it. Ask the group's owner to check People.",
            ReadOnlyBanner.title(mark, "1 Jan"),
        )
        assertEquals("one line only", null, ReadOnlyBanner.body(mark, "Old"))
        // What the group page, its settings and People read: the banner, no settings, no People.
        assertEquals(mark, GroupPeople.load(p, old).readOnly)
        assertEquals(null, GroupPeople.load(p, new).readOnly)

        // Placing refuses (the pure rule), for every kind of record.
        val placing = Placing.load(p)
        assertTrue(placing.refuses(old))
        assertEquals(false, placing.refuses(new))
        assertEquals(false, placing.refuses(null))
        assertEquals("NONE refuses nothing (the backfills)", false, Placing.NONE.refuses(old))
        val dinner = FounderVault.expense(9, 1, listOf(1 to 600L, 3 to 600L))
        val paid = SettlementDTO(FounderVault.hex(1), FounderVault.hex(3), 600, "INR", old)
        val r = uniffi.skipti_core.RedistributionInput(old, FounderVault.hex(3), "INR", listOf(uniffi.skipti_core.IdAmount(FounderVault.hex(1), 600)))
        val refusals = listOf<() -> Unit>(
            { placing.expense(dinner) },
            { placing.settlement(paid) },
            { placing.redistribution(r) },
            { placing.op(GroupOpIn.RenameGroup(old, "Older")) },
            { placing.op(GroupOpIn.RemoveMember(old, FounderVault.hex(3))) },
            { placing.revision(uniffi.skipti_core.ExpenseRevisionIn("t", emptyList(), FounderVault.hex(1), null, null, null, null), old) },
        )
        for ((i, refusal) in refusals.withIndex()) {
            try {
                refusal()
                fail("mapping $i into Old")
            } catch (_: Placing.PlaceLost) {
            }
        }

        // No writer writes: an expense, an import, a payment, a removal.
        // Cy, added in Old by D's phone, has not joined and has no history: removable but for the lost place.
        p.applyRecords(listOf(LedgerStore.groupOpRecord(GroupOpIn.AddMember(old, GroupOpMemberIn(FounderVault.hex(5), "Cy")), HlcStamp(2500uL, 0u), FounderVault.hex(0x54))))
        p.rebuildProjection()
        assertEquals("not vacuous: only the lost place refuses it", null, p.removalRefusal(old, FounderVault.hex(5)))
        val records = p.allRecords().size
        val expenses = p.rawKvGet(LedgerStore.EXPENSES_KEY)
        val lunch = FounderVault.expense(8, 1, listOf(1 to 600L, 3 to 600L))
        val writes = listOf<() -> Unit>(
            { p.appendExpense(dinner) },
            { p.appendExpenses(listOf(lunch, dinner)) },
            { p.appendSettlement(paid) },
            { Removal.records(p, old, FounderVault.hex(3), listOf(r), Placing.load(p)) },
            { Removal.records(p, old, FounderVault.hex(3), emptyList(), Placing.load(p)) },
            // Fix wave (M2, M1): the group's name and picture, and removing a member (as iOS).
            { p.renameGroup(old, "Older") },
            { p.setGroupPicture(old, emoji = "\uD83C\uDF55") },
            { p.removeMember(old, FounderVault.hex(5)) },
            {
                p.appendGroupOps(
                    listOf(GroupOpIn.RenameMember(new, FounderVault.hex(3), "B"), GroupOpIn.RenameMember(old, FounderVault.hex(3), "B")),
                    Placing.load(p),
                )
            },
        )
        for ((i, write) in writes.withIndex()) {
            try {
                write()
                fail("write $i into Old")
            } catch (_: Placing.PlaceLost) {
            }
        }
        assertEquals("nothing written, New's import and rename included", records, p.allRecords().size)
        assertArrayEquals(expenses, p.rawKvGet(LedgerStore.EXPENSES_KEY))
        assertEquals(emptyList<SettlementDTO>(), p.loadSettlements())

        // A record already waiting in Old stays on the phone; New's go.
        p.applyRecords(listOf(LedgerStore.expenseRecord(dinner, HlcStamp(4000uL, 0u), LedgerStore.deviceId(p), Placing.NONE)))
        assertTrue("not vacuous", CloudRecords.pending(p.allRecords(), emptySet()).any { it.groupId == old })
        val out = CloudRecords.outgoing(p.allRecords(), emptySet(), p.readOnly().keys, emptySet())
        assertTrue(out.send.none { it.groupId == old })
        assertTrue(out.send.any { it.groupId == new })
        // The alias share skips it.
        assertEquals(
            emptyList<AliasShare.Alias>(),
            AliasShare.wanted(p.projectGroups(), p.allRecords(), p.readOnly().keys, emptySet()).filter { it.groupId == old },
        )

        // New still takes records, as before.
        p.appendExpense(lunch)
        assertEquals(lunch, expenseIn(p))
    }

    /** Fix wave, decision 52 (M3): a group whose place is lost offers Export only. You are still in it,
     *  so "Remove from this phone" would erase its records waiting to upload and the group would come straight
     *  back, still read-only. A removed member's or a deleted group's copy keeps both actions. The group page
     *  draws the remove action only through the rule (source pinned). Twin of iOS's test. */
    @Test
    fun d229_a_lost_place_offers_export_only() {
        val p = FounderVault.lost(device("exportonly"))
        val mark = p.readOnly().getValue(FounderVault.hex(9))
        assertEquals(ReadOnlyDTO.PLACE_LOST, mark.kind)
        assertEquals(false, ReadOnlyActions.offersForget(mark))
        assertTrue(ReadOnlyActions.offersForget(ReadOnlyDTO("removed", "Bo", 5000)))
        assertTrue(ReadOnlyActions.offersForget(ReadOnlyDTO("deleted", "Bo", 5000)))
        assertTrue(ReadOnlyActions.offersForget(ReadOnlyDTO("deleted", "", 5000, byYou = true)))
        val source = java.io.File("src/main/java/com/vardrlabs/skipti/GroupSettings.kt").readText()
        val actions = source.substringAfter("fun ReadOnlyActions(").substringBefore("HapticWarning(")
        assertTrue(actions, actions.contains("SecondaryAction(RolesCopy.EXPORT_THIS_GROUP"))
        assertTrue(actions, actions.contains("if (ReadOnlyActions.offersForget(mark)) DangerAction(RolesCopy.FORGET_ROW"))
        val page = java.io.File("src/main/java/com/vardrlabs/skipti/OverviewTab.kt").readText()
        assertTrue(page.contains("ReadOnlyActions(vaultPath, n, readOnly, dark,"))
    }

    /** Item 1: renaming yourself still works with a group whose place is
     *  lost, and writes nothing there (New gets its rename). */
    @Test
    fun d229_your_name_skips_a_group_whose_place_is_lost() {
        val p = FounderVault.lost(device("lostname"))
        val before = groupOps(p).size
        p.setYourName("Alan")
        val renames = groupOps(p).drop(before).map { it.second }.filterIsInstance<GroupOpIn.RenameMember>()
        assertEquals(listOf(GroupOpIn.RenameMember(FounderVault.hex(8), FounderVault.hex(1), "Alan")), renames)
    }

    /** Item 1: placeLost false changes nothing (no mark, no refusal), a stored
     *  mark wins over a derived one, and a stored mark never takes a derived
     *  one with it. */
    @Test
    fun d229_place_lost_false_changes_nothing() {
        val p = FounderVault.p(device("kept"), pClaim = 2)
        assertTrue(p.projectGroups().groups.none { it.placeLost })
        assertEquals(emptyMap<String, ReadOnlyDTO>(), p.readOnly())
        val placing = Placing.load(p)
        assertEquals(false, placing.refuses(FounderVault.hex(9)))
        assertEquals(false, placing.refuses(FounderVault.hex(8)))

        val lost = FounderVault.lost(device("marked"))
        val removed = ReadOnlyDTO("removed", "Bo", 5000)
        lost.markReadOnly(FounderVault.hex(8), removed)
        assertEquals(
            mapOf(FounderVault.hex(8) to removed, FounderVault.hex(9) to ReadOnlyDTO(ReadOnlyDTO.PLACE_LOST, "", 0)),
            lost.readOnlyChecked(),
        )
        val stored = lost.rawKvGet(LedgerStore.READ_ONLY_KEY)!!.decodeToString()
        assertTrue("only the removal is stored: $stored", FounderVault.hex(9) !in stored)
        assertEquals(
            "a stored mark wins",
            mapOf(FounderVault.hex(9) to removed),
            ReadOnlyDTO.withPlaceLost(mapOf(FounderVault.hex(9) to removed), lost.projectGroups()),
        )
        lost.markReadOnly(FounderVault.hex(9), removed)
        assertEquals(removed, lost.readOnlyChecked()[FounderVault.hex(9)])
        lost.clearReadOnly(FounderVault.hex(9))
        assertEquals(
            "clearing a mark never clears a lost place",
            ReadOnlyDTO(ReadOnlyDTO.PLACE_LOST, "", 0), lost.readOnlyChecked()[FounderVault.hex(9)],
        )
        lost.clearReadOnly(FounderVault.hex(8))
        assertEquals("clearing another group's mark never stores the lost place", "{}", lost.rawKvGet(LedgerStore.READ_ONLY_KEY)!!.decodeToString())
    }

    /** D-237 (the Fable audit, A1): a place this account claimed in ANOTHER group is someone else's id here: when
     *  this phone writes as its place, a share for that person stays theirs. Without it the global set mapped her
     *  share onto this phone's place, into the log every member reads. Twin: GroupRecordsTests
     *  testD237APlaceClaimedInAnotherGroupIsNotWrittenAsThisAccountsPlace. */
    @Test
    fun d237_a_place_claimed_in_another_group_is_not_written_as_this_accounts_place() {
        val y = "1".repeat(32)
        val x = "2".repeat(32)
        val carol = "c".repeat(32)
        val old = "a".repeat(32)
        val new = "b".repeat(32)
        val placing = Placing(mapOf(old to x), listOf(y, x, carol), emptySet(), mapOf(old to setOf(x), new to setOf(carol)))
        assertEquals(listOf(y, x), placing.mineIn(old))
        // x, claimed only in old, is left out of new the same way.
        assertEquals(listOf(y, carol), placing.mineIn(new))
        assertEquals(carol, placing.id(carol, old))
        assertEquals(x, placing.id(y, old))
        val e = ExpenseDTO(y, 1200, "INR", old, shares = listOf(ExpenseDTO.ShareDTO(y, 600), ExpenseDTO.ShareDTO(carol, 600)), addedBy = y)
        val w = placing.expense(e)
        assertEquals(x, w.payer)
        assertEquals(listOf(ExpenseDTO.ShareDTO(x, 600), ExpenseDTO.ShareDTO(carol, 600)), w.shares)
        // For contrast, the old merge (no per-group claimed set): her share lands on x.
        val oldMerge = Placing(mapOf(old to x), listOf(y, x, carol))
        assertEquals(listOf(ExpenseDTO.ShareDTO(x, 1200)), oldMerge.expense(e).shares)
        // `of` reads the claimed set from core's aliases to "you" (round 2, review M7: a positive check, so the
        // write-side fix cannot quietly revert): an alias to you is a claimed place in its group; one to someone
        // else is not.
        val group = uniffi.skipti_core.ProjectedGroupOut(
            uniffi.skipti_core.GroupWire(old, "Old", "INR", listOf(y, carol)), emptyList(), "1000:0:$y", emptyList(),
            emptyList(), null, emptyList(), emptyList(), x, x, emptyList(), false,
        )
        val projection = uniffi.skipti_core.GroupsProjectionOut(
            uniffi.skipti_core.YouOut(y, null), listOf(x, carol), listOf(group), emptyList(), emptyList(), emptyList(),
            emptyList(),
            listOf(
                uniffi.skipti_core.IdAlias(old, x, y), uniffi.skipti_core.IdAlias(new, carol, y),
                uniffi.skipti_core.IdAlias(old, carol, x),
            ),
            false, emptyList(), null, emptyList(),
        )
        val built = Placing.of(projection)
        assertEquals(listOf(y, x), built.mineIn(old))
        assertEquals(listOf(y, carol), built.mineIn(new))
        assertEquals(carol, built.id(carol, old))
    }
}

/** D-229: the founder scenario's records, as core's test 7 builds them (the
 *  same ids, stamps and devices), put into one vault as phone P of account A:
 *  A's two `you` records and the group scope. [pClaim] is the `you` of P's
 *  claim on x in Old: 2 is case (ii), 1 case (i), null no claim. Ids are
 *  hex(n): 32 hex digits. iOS's twin is the same vault in GroupRecordsTests. */
object FounderVault {
    fun hex(n: Int): String = "%032x".format(n)

    private fun rec(op: GroupOpIn, millis: Long, device: Int): RecordWire =
        LedgerStore.groupOpRecord(op, HlcStamp(millis.toULong(), 0u), hex(device))

    fun claim(group: Int, member: Int, you: Int, millis: Long, device: Int): RecordWire = rec(
        GroupOpIn.Claim(hex(group), hex(member), hex(you), listOf(ClaimDeviceIn(hex(device), "ab".repeat(32)))),
        millis, device,
    )

    private fun create(group: Int, first: Int, millis: Long, device: Int): RecordWire = rec(
        GroupOpIn.Create(
            hex(group), if (group == 9) "Old" else "New", "INR",
            listOf(GroupOpMemberIn(hex(first), "Al"), GroupOpMemberIn(hex(3), "Bo")),
        ),
        millis, device,
    )

    fun records(pClaim: Int?): List<RecordWire> = listOfNotNull(
        rec(GroupOpIn.You(hex(1), "Al"), 1000, 0x51),
        rec(GroupOpIn.You(hex(2), "Al"), 2000, 0x50),
        create(9, 2, 2100, 0x50),
        pClaim?.let { claim(9, 2, it, 2200, 0x50) },
        create(8, 1, 3000, 0x51),
        claim(9, 3, 3, 2300, 0x53),
        claim(8, 3, 3, 3100, 0x53),
    )

    /** D-229 decision 45: [store] as phone P with no claim of its own on x,
     *  and account D (you hex(4), device 0x54) holding x by a claim at 2400
     *  (decision 36's race seen from P): Old's place is lost; New is
     *  untouched. iOS's twin is FounderVault.holdOld. */
    fun lost(store: LedgerStore): LedgerStore {
        store.applyRecords(records(null) + claim(9, 2, 4, 2400, 0x54))
        store.rebuildProjection()
        return store
    }

    /** [store] holding the scenario, rebuilt (kv: "you" is y, the groups merged). */
    fun p(store: LedgerStore, pClaim: Int?): LedgerStore {
        store.applyRecords(records(pClaim))
        store.rebuildProjection()
        return store
    }

    fun expense(group: Int, payer: Int, shares: List<Pair<Int, Long>>): ExpenseDTO = ExpenseDTO(
        payer = hex(payer), paidMinor = shares.sumOf { it.second }, currencyCode = "INR", group = hex(group),
        shares = shares.map { ExpenseDTO.ShareDTO(hex(it.first), it.second) }, addedBy = hex(payer),
    )
}
