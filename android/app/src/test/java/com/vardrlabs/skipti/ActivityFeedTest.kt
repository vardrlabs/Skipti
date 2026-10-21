package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.ProjectedExpenseOut
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import java.nio.file.Files

/**
 * D-189: Activity (the founder: "a notification system for important stuff"
 * and "that bell icon ... inside the app"). The twin of iOS's Activity tests
 * in GroupRecordsTests.swift; the goldens are worked by hand from the rules.
 */
class ActivityFeedTest {

    private fun projected(
        id: String, group: String?, note: String?, addedBy: String, at: Long,
        editedBy: String? = null, editedAt: Long? = null, deleted: Boolean = false,
    ) = ProjectedExpenseOut(
        id, ExpenseInput(addedBy, 480_000L, "INR", emptyList(), group, null),
        note, null, null, addedBy, at.toULong(), deleted, editedAt?.toULong(), editedBy, emptyList(), emptyList(),
    )

    /** One item per expense, its latest change, only when someone else made
     *  it; personal expenses never; newest first; a blank note reads "an
     *  expense". */
    @Test
    fun activity_is_the_latest_change_someone_else_made() {
        val feed = ActivityFeed.items(
            listOf(
                projected("e1", "g", "Dinner", "r", 1_000),
                projected("e2", "g", "Mine", "m", 2_000),
                projected("e3", "g", "Hotel", "m", 1_500, "r", 3_000),
                projected("e4", "g", "Fixed", "r", 1_200, "m", 4_000),
                projected("e5", "g", "Taxi", "p", 500, "p", 5_000, deleted = true),
                projected("e6", null, "Personal", "r", 6_000),
                projected("e7", "g", "Old me", "m2", 7_000),
                projected("e8", "g", "  ", "r", 800),
            ),
            setOf("m", "m2"),
        )
        assertEquals(listOf("e5", "e3", "e1", "e8"), feed.map { it.expenseId })
        assertEquals(
            listOf(ActivityFeed.Kind.DELETED, ActivityFeed.Kind.CHANGED, ActivityFeed.Kind.ADDED, ActivityFeed.Kind.ADDED),
            feed.map { it.kind },
        )
        assertEquals(listOf("p", "r", "r", "r"), feed.map { it.by })
        assertEquals(listOf(5_000L, 3_000L, 1_000L, 800L), feed.map { it.atMs })
        assertEquals(listOf("Taxi", "Hotel", "Dinner", "an expense"), feed.map { it.title })
        assertEquals(4, ActivityFeed.unread(feed, 0))
        assertEquals(2, ActivityFeed.unread(feed, 1_000))
        assertEquals(0, ActivityFeed.unread(feed, 5_000))
    }

    /** Negative: never more than 50, and the 50 are the newest. */
    @Test
    fun activity_keeps_the_newest_fifty() {
        val feed = ActivityFeed.items((1..60).map { projected("e$it", "g", "x", "r", it.toLong()) }, setOf("m"))
        assertEquals(50, feed.size)
        assertEquals(60L, feed.first().atMs)
        assertEquals(11L, feed.last().atMs)
    }

    /** The marker is this phone's, in the vault, and survives a reopen. */
    @Test
    fun activity_seen_up_to_is_kept_in_the_vault() {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = Files.createTempDirectory("activity").resolve("a.db").toString()
        val a = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        assertEquals(0L, a.activitySeenUpTo())
        a.setActivitySeenUpTo(5_000)
        assertEquals(5_000L, LedgerStore(path) { SkiptiVault.open(path, keystore) }.activitySeenUpTo())
    }

    @Test
    fun the_activity_words_match_ios() {
        assertEquals("Activity", ActivityFeed.TITLE)
        assertEquals("Nothing new yet. What others add, change or delete in your groups shows here.", ActivityFeed.EMPTY)
        assertEquals("Rahul added Dinner", ActivityFeed.line(ActivityFeed.Kind.ADDED, "Rahul", "Dinner"))
        assertEquals("Rahul changed Dinner", ActivityFeed.line(ActivityFeed.Kind.CHANGED, "Rahul", "Dinner"))
        assertEquals("Rahul deleted Dinner", ActivityFeed.line(ActivityFeed.Kind.DELETED, "Rahul", "Dinner"))
        assertEquals("Activity", ActivityFeed.spokenBell(0))
        assertEquals("Activity, 3 new", ActivityFeed.spokenBell(3))
        assertEquals("Someone", ActivityFeed.SOMEONE)
    }
}
