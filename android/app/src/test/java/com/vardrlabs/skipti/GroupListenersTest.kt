package com.vardrlabs.skipti

import com.vardrlabs.skipti.GroupListeners.Listed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which groups' records the phone listens to (CloudSync's groups listener).
 * Found on the emulators (2026-10-14): the owner's phone creates a group, the
 * groups listing fires at once with this phone's own pending write, and a
 * records listener attached then is refused by the server (the group is not
 * there yet: "Null value error" in isMember). Firestore ends a refused
 * listener for good; the phone ignored the error and kept the dead
 * registration, so it never heard the group's other members again.
 */
class GroupListenersTest {

    private class Fake {
        var next = 0
        val started = mutableListOf<Pair<String, Int>>()
        val stopped = mutableListOf<Int>()
        val listeners = GroupListeners<Int>(
            start = { id -> (++next).also { started += id to it } },
            stop = { stopped += it },
        )
    }

    @Test
    fun aGroupNotYetOnTheServerIsListenedToOnceTheServerHasIt() {
        val f = Fake()
        // The listing carries this phone's own create, not yet on the server.
        f.listeners.update(listOf(Listed("g", confirmed = false)))
        assertTrue("no listener before the server holds the group", f.started.isEmpty())
        assertFalse("g" in f.listeners)
        // The server confirmed it.
        f.listeners.update(listOf(Listed("g", confirmed = true)))
        assertEquals(listOf("g" to 1), f.started)
        assertTrue("g" in f.listeners)
    }

    @Test
    fun aPendingChangeToAGroupAlreadyListenedToKeepsItsListener() {
        val f = Fake()
        f.listeners.update(listOf(Listed("g", confirmed = true)))
        // A rename from this phone: the listing shows a pending write again.
        f.listeners.update(listOf(Listed("g", confirmed = false)))
        assertEquals(listOf("g" to 1), f.started)
        assertTrue(f.stopped.isEmpty())
        assertTrue("g" in f.listeners)
    }

    @Test
    fun aListenerThatFailedIsLetGoAndStartedAgainByTheNextListing() {
        val f = Fake()
        f.listeners.update(listOf(Listed("g", confirmed = true)))
        f.listeners.failed("g", 1)
        assertFalse("a failed listener is not kept", "g" in f.listeners)
        assertEquals(listOf(1), f.stopped)
        f.listeners.update(listOf(Listed("g", confirmed = true)))
        assertEquals(listOf("g" to 1, "g" to 2), f.started)
        assertTrue("g" in f.listeners)
    }

    @Test
    fun aLateFailureOfAnOldListenerLeavesTheNewOne() {
        val f = Fake()
        f.listeners.update(listOf(Listed("g", confirmed = true)))
        f.listeners.failed("g", 1)
        f.listeners.update(listOf(Listed("g", confirmed = true)))
        // Registration 1 reports again: registration 2 is untouched.
        f.listeners.failed("g", 1)
        assertTrue("g" in f.listeners)
        assertEquals(listOf(1), f.stopped)
    }

    @Test
    fun aGroupThatLeftTheListIsLetGo() {
        val f = Fake()
        f.listeners.update(listOf(Listed("g", confirmed = true), Listed("h", confirmed = true)))
        f.listeners.update(listOf(Listed("h", confirmed = true)))
        assertFalse("g" in f.listeners)
        assertTrue("h" in f.listeners)
        assertEquals(listOf(1), f.stopped)
        f.listeners.clear()
        assertEquals(listOf(1, 2), f.stopped)
        assertFalse("h" in f.listeners)
    }
}
