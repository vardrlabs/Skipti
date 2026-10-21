package com.vardrlabs.skipti

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * D-210 Task 8, Fix 2 (the look on devices). Through the real core and a real
 * vault where the behaviour is the vault's. Twins of RolesTests.swift's
 * `testAGroupWhoseOwnPlaceIsMissingWaits...` and friends.
 */
class GroupFixesTest {

    private val dir = Files.createTempDirectory("group-fixes")

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

    /** A phone that holds Goa's records (made by someone else) but whose own
     *  place in Goa no create or addMember record names yet: the invitee who
     *  joined before the owner's addMember reached the cloud. */
    private fun phoneWithAGroupItIsNotIn(): Triple<LedgerStore, String, String> {
        val owner = device("owner")
        owner.setYourName("Tim")
        val goa = owner.createGroup("Goa", "INR", listOf("Rahul"))
        val invitee = device("invitee")
        invitee.setYourName("Asha")
        val own = invitee.createGroup("Flat", "INR", listOf("Meera"))
        for (r in owner.allRecords()) if (r.groupId == goa.group.id) invitee.applyRecord(r)
        invitee.rebuildGroups()
        return Triple(invitee, own.group.id, goa.group.id)
    }

    /** Fix 2, item 2: one group whose place for this account is missing never
     *  fails the others. It is marked waiting, with its name, and the rest of
     *  the app (reads and writes alike) works around it. */
    @Test
    fun a_group_whose_own_place_is_missing_waits_and_the_others_load() {
        val (phone, flat, goa) = phoneWithAGroupItIsNotIn()
        assertEquals(listOf(flat), phone.namedGroups().map { it.group.id })
        assertEquals(listOf(LedgerStore.WaitingGroup(goa, "Goa")), phone.waitingGroups())
        // A write to the other group still works, and the waiting one stays.
        phone.addMember(flat, "Sam")
        assertEquals(listOf("Asha", "Meera", "Sam"), phone.namedGroups().single().members.map { it.name })
        assertEquals(listOf(goa), phone.waitingGroups().map { it.id })
    }

    /** A phone with no such group has nothing waiting. */
    @Test
    fun nothing_waits_when_every_group_has_your_place() {
        val a = device("plain")
        a.setYourName("Tim")
        a.createGroup("Goa", "INR", listOf("Rahul"))
        assertEquals(1, a.namedGroups().size)
        assertTrue(a.waitingGroups().isEmpty())
    }

    /** Fix 2, item 2: the waiting copy's words, identical on both shells. */
    @Test
    fun the_waiting_words_match_ios() {
        assertEquals("Waiting for this group to finish arriving.", GroupsCopy.WAITING)
    }

    /** Fix 2, item 1: every way of adding a person tells the sync once the
     *  operation is saved, and not when the add fails. */
    @Test
    fun adding_a_person_tells_the_sync_once_it_is_saved() {
        val a = device("adder")
        a.setYourName("Tim")
        val goa = a.createGroup("Goa", "INR", listOf("Rahul"))
        val synced = AtomicInteger()
        val (named, member) = AddPerson.add(a, goa.group.id, "Sam") { synced.incrementAndGet() }!!
        assertEquals("Sam", member.name)
        assertTrue(named.members.any { it.id == member.id })
        assertEquals(1, synced.get())
        try {
            AddPerson.add(a, goa.group.id, "   ") { synced.incrementAndGet() }
            fail("a blank name is refused by core")
        } catch (_: uniffi.skipti_core.CoreException) {
        }
        assertEquals("a refused add syncs nothing", 1, synced.get())
    }

    /** D-211 fix 5 (found on devices): a new group went up only at the next
     *  unrelated write. Creating one tells the sync once it is all saved (the
     *  name, the group, its picture), and not when anything is refused.
     *  Twin: testCreatingAGroupPushesItAtOnce. */
    @Test
    fun creating_a_group_pushes_it_at_once() {
        val a = device("creator")
        val synced = AtomicInteger()
        val created = CreateGroup.create(a, "Tim", "Goa", "INR", listOf("Rahul"), "\uD83E\uDD8A", null) { synced.incrementAndGet() }
        assertEquals(1, synced.get())
        assertEquals("Goa", created.group.name)
        assertEquals(listOf(created.group.id), a.namedGroups().map { it.group.id })
        try {
            CreateGroup.create(a, null, "   ", "INR", emptyList(), null, null) { synced.incrementAndGet() }
            fail("a blank group name is refused by core")
        } catch (_: uniffi.skipti_core.CoreException) {
        }
        assertEquals("a refused create syncs nothing", 1, synced.get())
        assertEquals(1, a.namedGroups().size)
    }

    /** Fix 2, item 4: a Done nobody observed never finishes the next flow for
     *  the same key; only a Running job refuses a start. */
    @Test
    fun a_new_start_runs_over_an_unseen_done() = runBlocking {
        val key = GroupJobs.Key.removal("a".repeat(32), "b".repeat(32))
        val runs = AtomicInteger()
        try {
            GroupJobs.start(key, { "x" }) { runs.incrementAndGet() }!!.join()
            assertEquals(GroupJobs.Status.Done, GroupJobs.status(key))
            val again = GroupJobs.start(key, { "x" }) { runs.incrementAndGet() }
            assertNotNull("an unseen Done does not stop the next flow", again)
            again!!.join()
            assertEquals(2, runs.get())
            // A failed one is replaced the same way; only Running refuses.
            GroupJobs.start(key, { "words" }) { throw IllegalStateException("offline") }!!.join()
            assertTrue(GroupJobs.status(key) is GroupJobs.Status.Failed)
            val gate = CompletableDeferred<Unit>()
            val running = GroupJobs.start(key, { "x" }) { gate.await() }
            assertNotNull(running)
            assertNull("a running job refuses a second start", GroupJobs.start(key, { "x" }) { runs.incrementAndGet() })
            gate.complete(Unit)
            running!!.join()
        } finally {
            GroupJobs.clear(key)
        }
    }

    /** Fix 2, item 4: a finished removal's progress is spent: the same person
     *  removed again with the same payments never reads "all recorded". */
    @Test
    fun a_finished_jobs_settle_progress_is_not_carried_to_the_next() = runBlocking {
        val g = "c".repeat(32)
        val key = GroupJobs.Key.removal(g, "d".repeat(32))
        val pay = listOf(SettlementDTO("d".repeat(32), "e".repeat(32), 500, "INR", g))
        try {
            val p = GroupJobs.settleProgress(key, pay, null)
            GroupJobs.start(key, { "x" }) { p.run(append = {}, sync = { true }, remove = {}) }!!.join()
            assertTrue(p.removed)
            val next = GroupJobs.settleProgress(key, pay, null)
            assertTrue("fresh progress for the next flow", next !== p)
            assertEquals(0, next.appended)
        } finally {
            GroupJobs.clear(key)
        }
    }

    /** Fix 2, item 5: a retried delete whose first try deleted in the cloud
     *  gets NOT_FOUND: that is "already deleted", and the copy is marked. */
    @Test
    fun a_delete_that_finds_the_group_already_gone_still_marks_the_copy() = runBlocking {
        val marked = AtomicInteger()
        GroupDelete.run(
            call = { throw CloudFunctions.Failure.Refused("NOT_FOUND") },
            mark = { marked.incrementAndGet() },
        )
        assertEquals("already deleted: marked read-only", 1, marked.get())
        GroupDelete.run(call = {}, mark = { marked.incrementAndGet() })
        assertEquals("the ordinary path marks once", 2, marked.get())
        // Every other failure is still a failure, and marks nothing.
        for (failure in listOf(
            CloudFunctions.Failure.Refused("PERMISSION_DENIED"),
            CloudFunctions.Failure.Refused("FAILED_PRECONDITION"),
            CloudFunctions.Failure.Offline(),
        )) {
            try {
                GroupDelete.run(call = { throw failure }, mark = { marked.incrementAndGet() })
                fail("must rethrow ${failure.message}")
            } catch (e: CloudFunctions.Failure) {
                assertEquals(failure, e)
            }
        }
        assertEquals(2, marked.get())
        assertFalse(GroupDelete.alreadyGone(CloudFunctions.Failure.Refused("PERMISSION_DENIED")))
        assertTrue(GroupDelete.alreadyGone(CloudFunctions.Failure.Refused("NOT_FOUND")))
    }
}
