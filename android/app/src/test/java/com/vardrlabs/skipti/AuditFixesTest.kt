package com.vardrlabs.skipti

import com.google.firebase.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.ExpenseRevisionIn
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.RecordWire
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * D-235 wave D: the Android shell's fixes from the Opus pre-release audit (H1, H2, M1, M2, M3, M5, L6) and the ingest
 * half of core's C1 and H1, through the real core and a real vault where the behaviour is theirs.
 */
class AuditFixesTest {

    private val dir = Files.createTempDirectory("audit-fixes")

    private fun device(name: String): LedgerStore {
        val open = opener(name)
        return LedgerStore(dir.resolve("$name.db").toString()) { open() }
    }

    /** How [device] opens its vault, for a store that fails some opens of the same vault. */
    private fun opener(name: String): () -> SkiptiVault {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = dir.resolve("$name.db").toString()
        return { SkiptiVault.open(path, keystore) }
    }

    private fun main(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    /** A declaration's text in [file], from [decl] to the next line that closes a block at [indent]. */
    private fun body(file: String, decl: String, indent: String = ""): String =
        main(file).substringAfter(decl).substringBefore("\n$indent}\n")

    // ---- H1: one save per tap ----

    @Test
    fun a_save_latch_holds_until_it_is_released() {
        val latch = SaveLatch()
        assertFalse(latch.busy)
        assertTrue(latch.begin())
        assertTrue(latch.busy)
        assertFalse("a second tap while the save runs saves nothing", latch.begin())
        latch.release()
        assertFalse(latch.busy)
        assertTrue("a failed save can be tried again", latch.begin())
    }

    /** The latch is taken in the tap, BEFORE the save is launched (the audit's double tap saved twice because `saved`
     *  was set only after the IO work returned), the button reads it, and a failure lets go. */
    @Test
    fun add_expense_takes_its_latch_before_launching_the_save() {
        val screen = body("AddExpenseScreen.kt", "fun AddExpenseScreen(")
        val click = screen.substringAfter("label = when {").substringBefore("{ (minor, code) ->")
        assertTrue("enabled reads the latch", "!saving.busy" in click)
        val begin = click.indexOf("if (saved || !saving.begin()) return@PrimaryAction")
        val launch = click.indexOf("scope.launch {")
        assertTrue("the latch is taken first", begin in 0 until launch)
        assertTrue("a failed save lets go", "error = result\n                            saving.release()" in screen)
    }

    @Test
    fun expense_detail_and_settle_record_once_per_tap() {
        val write = body("ExpenseDetailScreen.kt", "    fun write(revision", "    ")
        assertTrue(write.indexOf("if (!writing.begin()) return") in 0 until write.indexOf("scope.launch"))
        assertTrue("released when the write ends", "writing.release()" in write)
        val settle = main("SettleScreen.kt").substringAfter("onRecord = {").substringBefore("recordSettlement(")
        assertTrue(settle.indexOf("if (settlingKey != null) return@DebtRow") in 0 until settle.indexOf("settlingKey = \""))
    }

    // ---- H2: cloud pictures decoded safely ----

    @Test
    fun a_picture_past_cores_bounds_is_refused_and_the_rest_sampled() {
        val photo = SafeImage.PHOTO
        assertEquals(1, SafeImage.sampleSize(768, 512, photo, SafeImage.TILE_TARGET))
        assertEquals("exactly the pixel cap", 1, SafeImage.sampleSize(2048, 1024, photo, SafeImage.VIEWER_TARGET))
        assertEquals(1, SafeImage.sampleSize(1024, 2048, photo, SafeImage.VIEWER_TARGET))
        assertNull("one pixel past the cap", SafeImage.sampleSize(2048, 1025, photo, SafeImage.TILE_TARGET))
        assertNull("a side past the bound", SafeImage.sampleSize(2049, 10, photo, SafeImage.TILE_TARGET))
        assertNull("the audit's 7000 by 7000", SafeImage.sampleSize(7000, 7000, photo, SafeImage.TILE_TARGET))
        assertNull("no size", SafeImage.sampleSize(0, 512, photo, SafeImage.TILE_TARGET))
        assertNull("no size", SafeImage.sampleSize(512, -1, photo, SafeImage.TILE_TARGET))
        // Sampled to no less than the target on the shorter side: 1536 / 2 = 768 >= 768, / 4 = 384 < 768.
        assertEquals(2, SafeImage.sampleSize(2048, 1536, SafeImage.Caps(4096, 1L shl 30), 768))
        assertEquals(4, SafeImage.sampleSize(2048, 1024, photo, 256))
        assertEquals(1, SafeImage.sampleSize(120, 120, SafeImage.PREVIEW, SafeImage.AVATAR_TARGET))
        assertEquals(1, SafeImage.sampleSize(512, 512, SafeImage.PREVIEW, SafeImage.AVATAR_TARGET))
        assertNull(SafeImage.sampleSize(513, 100, SafeImage.PREVIEW, SafeImage.AVATAR_TARGET))
        assertNull(SafeImage.sampleSize(512, 513, SafeImage.PREVIEW, SafeImage.AVATAR_TARGET))
    }

    /** The bounds are core's (groups.rs), read from the file, not copied by hand. */
    @Test
    fun safe_image_bounds_are_cores() {
        val rust = java.io.File("../../core/src/straumr/groups.rs").readText()
        fun constant(name: String): Long {
            val expr = Regex("pub const $name: u32 = ([0-9 *]+);").find(rust)?.groupValues?.get(1) ?: error("no $name")
            return expr.split("*").map { it.trim().toLong() }.reduce(Long::times)
        }
        assertEquals(constant("PICTURE_MAX_SIDE"), SafeImage.PHOTO.maxSide.toLong())
        assertEquals(constant("PICTURE_MAX_PIXELS"), SafeImage.PHOTO.maxPixels)
        assertEquals(constant("THUMB_MAX_SIDE"), SafeImage.PREVIEW.maxSide.toLong())
        assertEquals(constant("THUMB_MAX_PIXELS"), SafeImage.PREVIEW.maxPixels)
    }

    /** Every picture the app draws goes through SafeImage: no other file decodes bytes whole. Profile's square JPEG
     *  helper (your own picture, read bounds first and sampled, inside runCatching) is the one other decoder. */
    @Test
    fun every_decode_of_a_picture_goes_through_safe_image() {
        val sources = java.io.File("src/main/java/com/vardrlabs/skipti").listFiles { f -> f.name.endsWith(".kt") }!!
        for (file in sources) {
            if (file.name == "SafeImage.kt") continue
            var text = file.readText()
            if (file.name == "Profile.kt") {
                val helper = text.substringAfter("fun squareJPEG(").substringBefore("\n    }\n")
                text = text.replace(helper, "")
            }
            assertFalse("${file.name} decodes bytes outside SafeImage", "decodeByteArray(" in text)
        }
        assertTrue("SafeImage.decodeHex(photoHex, SafeImage.PHOTO" in body("GroupPictures.kt", "fun bitmap(photoHex", "    "))
        assertTrue(main("SafeImage.kt").contains("catch (_: OutOfMemoryError)"))
    }

    // ---- C1 and H1 at ingest; the version filter ----

    private val group = "c".repeat(32)
    private val me = "a".repeat(32)
    private val them = "b".repeat(32)
    private val third = "d".repeat(32)
    private val json = kotlinx.serialization.json.Json { explicitNulls = false }

    private fun record(type: Int, blob: ByteArray, millis: ULong = 1uL, version: UShort = 1u) =
        RecordWire(version, type.toUByte(), group, 0u, millis, 0u, them, them, byteArrayOf(0x45, 0x58, 0x50, 0x01), 0u, blob)

    private fun expense(paid: Long, shares: List<Pair<String, Long>>, payer: String = them) = json.encodeToString(
        ExpenseDTO.serializer(),
        ExpenseDTO(payer = payer, paidMinor = paid, currencyCode = "INR", group = group, shares = shares.map { ExpenseDTO.ShareDTO(it.first, it.second) }),
    ).toByteArray()

    private fun settlement(from: String, to: String, amount: Long, currency: String = "INR") = json.encodeToString(
        SettlementDTO.serializer(), SettlementDTO(from, to, amount, currency, group),
    ).toByteArray()

    @Test
    fun a_groups_bad_money_is_refused_at_ingest() {
        fun refusal(r: RecordWire) = CloudRecords.refusal(r, group, me)
        assertNull(refusal(record(1, expense(300, listOf(me to 150, them to 150)))))
        assertEquals("shares that do not add up", CloudRecords.Refusal.INVALID, refusal(record(1, expense(300, listOf(me to 100, them to 150)))))
        assertEquals("no shares", CloudRecords.Refusal.INVALID, refusal(record(1, expense(300, emptyList()))))
        assertEquals("a negative share", CloudRecords.Refusal.INVALID, refusal(record(1, expense(100, listOf(me to 150, them to -50)))))
        assertEquals("past the bound", CloudRecords.Refusal.INVALID, refusal(record(1, expense(2_000_000_000_000_000, listOf(me to 2_000_000_000_000_000)))))
        assertEquals("a payer that is no id", CloudRecords.Refusal.INVALID, refusal(record(1, expense(100, listOf(me to 100), payer = "Asha"))))
        assertNull(refusal(record(2, settlement(them, me, 500))))
        assertEquals("to oneself", CloudRecords.Refusal.INVALID, refusal(record(2, settlement(them, them, 500))))
        assertEquals("nothing paid", CloudRecords.Refusal.INVALID, refusal(record(2, settlement(them, me, 0))))
        assertEquals("no such currency", CloudRecords.Refusal.INVALID, refusal(record(2, settlement(them, me, 5, "XYZ"))))
        // The group check still comes first.
        val other = json.encodeToString(SettlementDTO.serializer(), SettlementDTO(them, them, 0, "INR", third)).toByteArray()
        assertEquals(CloudRecords.Refusal.WRONG_GROUP, refusal(record(2, other)))
    }

    /** Core C1: a money edit in a group decoded in the group's scope comes in (it was refused as another group's
     *  since D-195); one whose new money breaks the rules does not. */
    @Test
    fun a_groups_money_edit_comes_in_and_a_bad_one_does_not() {
        fun refusal(r: RecordWire) = CloudRecords.refusal(r, group, me)
        val target = "1790000000000:0:$them"
        fun revision(shares: List<Pair<String, Long>>, paid: Long) = uniffi.skipti_core.ffiEncodeExpenseRevision(
            ExpenseRevisionIn(
                target, emptyList(), them,
                uniffi.skipti_core.ExpenseInput(them, paid, "INR", shares.map { IdAmount(it.first, it.second) }, null, null),
                null, null, null,
            ),
        )
        val good = revision(listOf(me to 200, them to 200), 400)
        assertEquals(group, uniffi.skipti_core.ffiDecodeExpenseRevision(good, group).money?.group)
        assertNull("an honest edit comes in", refusal(record(5, good)))
        val noMoney = uniffi.skipti_core.ffiEncodeExpenseRevision(ExpenseRevisionIn(target, emptyList(), them, null, "Lunch", null, null))
        assertNull(refusal(record(5, noMoney)))
        // Core refuses to encode a revision whose new money breaks the rules (its decode refuses one too: wave A's
        // tests); here the shell's half: junk is unreadable.
        assertEquals(CloudRecords.Refusal.UNREADABLE, refusal(record(5, "junk".toByteArray())))
        val scope = body("CloudSync.kt", "    fun refusal(", "    ")
        assertTrue("decoded in the group's scope", "ffiDecodeExpenseRevision(record.blob, group)" in scope)
    }

    /** Wave A's L5: the vault refuses a header version other than 1, so a document of version 2 would throw in absorb
     *  and hold its log's cursor for good. It is skipped like any malformed document, and the cursor moves past it. */
    @Test
    fun a_document_of_another_version_is_skipped_and_the_cursor_moves_on() {
        val store = device("version")
        LedgerStore.Sync.deviceId(store)
        val r = record(1, expense(300, listOf(me to 150, them to 150)))
        val good = CloudRecords.document(r, "u1").toMutableMap<String, Any?>()
        assertNotNull(CloudRecords.record(good, group))
        val v2 = good.toMutableMap().apply {
            this["payload"] = (good["payload"] as Map<*, *>).toMutableMap().apply { this["version"] = 2L }
            this["createdAt"] = Timestamp(1_800_000_000, 0)
        }
        assertNull(CloudRecords.record(v2, group))
        val v0 = good.toMutableMap().apply {
            this["payload"] = (good["payload"] as Map<*, *>).toMutableMap().apply { this["version"] = 0L }
        }
        assertNull(CloudRecords.record(v0, group))
        val fresh = CloudRecords.absorb(listOf(v2), group, store)
        assertTrue(fresh.isEmpty())
        assertEquals("the cursor moved past it", 1_800_000_000_000L, CloudRecords.cursor(store, group))
        assertTrue(store.allRecords().isEmpty())
    }

    /** Round 3 (twin of iOS): the vault also refuses a header whose nonce is not 4 bytes or whose type it does not
     *  know; such a document is skipped before any save and the cursor moves past it. */
    @Test
    fun a_document_of_an_unknown_type_or_odd_nonce_is_skipped_and_the_cursor_moves_on() {
        val store = device("header")
        LedgerStore.Sync.deviceId(store)
        val good = CloudRecords.document(record(1, expense(300, listOf(me to 150, them to 150))), "u1")
        fun with(seconds: Long, edit: MutableMap<String, Any?>.() -> Unit) =
            good.toMutableMap<String, Any?>().apply { edit(); this["createdAt"] = Timestamp(seconds, 0) }
        fun nonce(n: Int) = com.google.firebase.firestore.Blob.fromBytes(ByteArray(n) { 1 })
        fun payload(key: String, value: Any) = (good["payload"] as Map<*, *>).toMutableMap().apply { this[key] = value }
        val bad = listOf(
            with(1_800_000_001) { this["type"] = "7" },
            with(1_800_000_002) { this["type"] = "0" },
            with(1_800_000_003) { this["payload"] = payload("nonce", nonce(3)) },
            with(1_800_000_004) { this["payload"] = payload("nonce", nonce(5)) },
            with(1_800_000_005) { this["payload"] = payload("nonce", nonce(0)) },
        )
        for (d in bad) assertNull(CloudRecords.record(d, group))
        for (t in 1..6) assertNotNull("type $t", CloudRecords.record(with(1) { this["type"] = t.toString() }, group))
        assertTrue(CloudRecords.absorb(bad, group, store).isEmpty())
        assertEquals("the cursor moved past them", 1_800_000_005_000L, CloudRecords.cursor(store, group))
        assertTrue(store.allRecords().isEmpty())
    }

    /** Round 3 (twin of iOS): the note field takes no more than core's 1,000 characters. */
    @Test
    fun the_note_field_stops_at_a_thousand_characters() {
        assertEquals(1_000, AddExpenseUi.NOTE_MAX_CHARS)
        assertEquals("Dinner", AddExpenseUi.capNote("Dinner"))
        assertEquals("x".repeat(1_000), AddExpenseUi.capNote("x".repeat(1_000)))
        assertEquals("x".repeat(1_000), AddExpenseUi.capNote("x".repeat(1_001)))
        assertEquals("a pasted essay keeps its start", "y".repeat(1_000), AddExpenseUi.capNote("y".repeat(1_000) + "z".repeat(5_000)))
        assertTrue("FieldRow(note, { note = AddExpenseUi.capNote(it) }" in body("AddExpenseScreen.kt", "fun AddExpenseScreen("))
        // The cap at core's: a capped note is written (the shell never offers what core refuses).
        val store = device("note")
        val me = store.setYourName("Asha").id
        store.appendExpense(ExpenseDTO(payer = me, paidMinor = 100, currencyCode = "INR", shares = listOf(ExpenseDTO.ShareDTO(me, 100)), note = AddExpenseUi.capNote("n".repeat(1_500))))
        assertEquals(1_000, store.loadExpenses().single().note?.length)
    }

    /** Decision 112: the shell's bound is core's, read from the Rust source, so the two never drift. */
    @Test
    fun the_text_bound_is_cores() {
        val rust = java.io.File("../../core/src/straumr/check.rs").readText()
        val value = Regex("""pub const TEXT_MAX_CHARS: usize = ([0-9_]+);""").find(rust)?.groupValues?.get(1) ?: error("no TEXT_MAX_CHARS")
        assertEquals(value.replace("_", "").toInt(), LedgerStore.TEXT_MAX_CHARS)
        assertEquals(LedgerStore.TEXT_MAX_CHARS, AddExpenseUi.NOTE_MAX_CHARS)
    }

    /** A note past core's bound is refused before anything is written (a new expense, a batch, an edit), with core's
     *  error, so the screens show their existing save error. Characters as core counts: an emoji is one. */
    @Test
    fun a_note_past_the_bound_is_refused_before_anything_is_written() {
        val store = device("long-note")
        val me = store.setYourName("Asha").id
        fun dto(note: String) = ExpenseDTO(payer = me, paidMinor = 100, currencyCode = "INR", shares = listOf(ExpenseDTO.ShareDTO(me, 100)), note = note)
        val before = store.allRecords().size
        fun refused(block: () -> Unit) = try {
            block()
            false
        } catch (e: uniffi.skipti_core.CoreException.InvalidInput) {
            assertEquals("a text longer than 1000 characters", AddExpenseUi.humanError(e))
            true
        }
        assertTrue(refused { store.appendExpense(dto("n".repeat(1_001))) })
        assertTrue(refused { store.appendExpenses(listOf(dto("ok"), dto("n".repeat(1_001)))) })
        assertEquals("nothing written", before, store.allRecords().size)
        // Exactly the bound goes in; 1,000 emoji are 1,000 characters (2,000 UTF-16 units).
        store.appendExpense(dto("n".repeat(1_000)))
        store.appendExpense(dto("\uD83C\uDF55".repeat(1_000)))
        assertEquals(2, store.loadExpenses().size)
        val id = store.projectedExpenses().expenses.first().id
        val edit = { note: String -> uniffi.skipti_core.ExpenseRevisionIn(id, emptyList(), me, null, note, null, null) }
        val records = store.allRecords().size
        assertTrue("an edit's note", refused { store.reviseExpense(edit("e".repeat(1_001))) })
        assertEquals(records, store.allRecords().size)
        store.reviseExpense(edit("e".repeat(1_000)))
        assertEquals(records + 1, store.allRecords().size)
        assertTrue("1,001 emoji are refused", refused { store.appendExpense(dto("\uD83C\uDF55".repeat(1_001))) })
        // The field's cap never cuts a pair in half...
        val pizzas = AddExpenseUi.capNote("\uD83C\uDF55".repeat(1_200))
        assertEquals(1_000, LedgerStore.textChars(pizzas))
        assertEquals(2_000, pizzas.length)
        assertFalse("no lone surrogate", Character.isHighSurrogate(pizzas.last()))
        // ...nor an emoji made of several code points: a family (man ZWJ woman ZWJ girl, 5 code points) that would
        // cross the bound is left out whole.
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(5, LedgerStore.textChars(family))
        assertEquals("a".repeat(998), AddExpenseUi.capNote("a".repeat(998) + family + "b"))
        assertEquals("a".repeat(995) + family, AddExpenseUi.capNote("a".repeat(995) + family + "b"))
        // Mixed text cut on the bound keeps exactly 1,000 code points.
        assertEquals(1_000, LedgerStore.textChars(AddExpenseUi.capNote("x\uD83C\uDF55".repeat(700))))
    }

    /** Core C1's lost edits: a phone that ran D-210's reset runs D-235's once more, and only once. */
    @Test
    fun the_group_cursors_start_again_once_for_d235() {
        val store = device("cursors")
        CloudRecords.saveGroupsUp(store, setOf(group))
        store.rawKvSet(CloudRecords.GROUP_CURSORS_RESET_KEY, "1".toByteArray())
        CloudRecords.saveCursor(store, group, 5_000)
        CloudRecords.saveCursor(store, null, 6_000)
        assertTrue(CloudRecords.resetGroupCursorsOnce(store))
        assertEquals(0L, CloudRecords.cursor(store, group))
        assertEquals(6_000L, CloudRecords.cursor(store, null))
        CloudRecords.saveCursor(store, group, 7_000)
        assertFalse(CloudRecords.resetGroupCursorsOnce(store))
        assertEquals(7_000L, CloudRecords.cursor(store, group))
    }

    /** The write half: this phone never writes what it would refuse to read. A refused expense or settlement throws
     *  core's error (the screen shows its existing save error) and leaves the vault exactly as it was. */
    @Test
    fun bad_money_is_refused_before_anything_is_written() {
        val store = device("write")
        val me = store.setYourName("Asha").id
        val goa = store.createGroup("Goa", "INR", listOf("Sam"))
        val sam = goa.members.first { it.name == "Sam" }.id
        val before = store.allRecords().size
        fun refused(block: () -> Unit) = try {
            block()
            false
        } catch (_: uniffi.skipti_core.CoreException) {
            true
        }
        assertTrue("shares that do not add up", refused {
            store.appendExpense(ExpenseDTO(payer = me, paidMinor = 300, currencyCode = "INR", group = goa.group.id, shares = listOf(ExpenseDTO.ShareDTO(me, 100), ExpenseDTO.ShareDTO(sam, 150))))
        })
        assertTrue("past the bound", refused {
            store.appendExpense(ExpenseDTO(payer = me, paidMinor = 2_000_000_000_000_000, currencyCode = "INR", shares = listOf(ExpenseDTO.ShareDTO(me, 2_000_000_000_000_000))))
        })
        assertTrue("one of a batch", refused {
            store.appendExpenses(listOf(
                ExpenseDTO(payer = me, paidMinor = 100, currencyCode = "INR", shares = listOf(ExpenseDTO.ShareDTO(me, 100))),
                ExpenseDTO(payer = me, paidMinor = 100, currencyCode = "INR", shares = emptyList()),
            ))
        })
        assertTrue("to oneself", refused { store.appendSettlement(SettlementDTO(sam, sam, 500, "INR", goa.group.id)) })
        assertTrue("nothing paid", refused { store.appendSettlement(SettlementDTO(sam, me, 0, "INR", goa.group.id)) })
        assertEquals("nothing written", before, store.allRecords().size)
        assertTrue(store.loadExpenses().isEmpty())
        // The honest ones go in.
        store.appendExpense(ExpenseDTO(payer = me, paidMinor = 300, currencyCode = "INR", group = goa.group.id, shares = listOf(ExpenseDTO.ShareDTO(me, 150), ExpenseDTO.ShareDTO(sam, 150))))
        store.appendSettlement(SettlementDTO(sam, me, 150, "INR", goa.group.id))
        assertEquals(before + 2, store.allRecords().size)
    }

    // ---- Round 2 (wave B review I2): one record the rules refuse never stops sync ----

    // Firestore's error codes cannot be made on the JVM (their enum needs Android), so the tests name the two kinds.
    private class RuleRefusal : Exception("permission denied")
    private class Transient : Exception("unavailable")

    private fun ruled(e: Exception) = e is RuleRefusal

    @Test
    fun a_failed_write_is_uploaded_refused_or_a_stop() {
        for (refused in listOf(true, false)) {
            assertEquals("a document the server holds was uploaded before", CloudRecords.UploadFailure.UPLOADED, CloudRecords.uploadFailure(refused, refused, true, CloudRecords.Held.YES))
            assertEquals("no answer to the read stops", CloudRecords.UploadFailure.STOP, CloudRecords.uploadFailure(refused, refused, true, CloudRecords.Held.UNKNOWN))
        }
        assertEquals(CloudRecords.UploadFailure.REFUSED, CloudRecords.uploadFailure(true, false, false, CloudRecords.Held.NO))
        assertEquals("not the rules: a stop", CloudRecords.UploadFailure.STOP, CloudRecords.uploadFailure(false, false, true, CloudRecords.Held.NO))
        assertFalse("a plain exception is no rule refusal", CloudRecords.isRuleRefusal(IllegalStateException("socket")))
        val codes = body("CloudSync.kt", "    fun isRuleRefusal(", "    ")
        assertTrue("Code.PERMISSION_DENIED" in codes && "Code.INVALID_ARGUMENT" in codes)
        assertFalse("UNAUTHENTICATED" in codes.substringAfter("{") || "UNAVAILABLE" in codes.substringAfter("{"))
    }

    @Test
    fun a_batch_with_one_refused_record_uploads_the_others() = runBlocking {
        val rs = (1..4).map { record(1, expense(300, listOf(me to 150, them to 150)), millis = it.toULong()) }
        val tooFar = rs[1]
        val there = rs[2]
        val r = CloudRecords.oneByOne(
            rs,
            write = { if (it === tooFar || it === there) throw RuleRefusal() },
            exists = { if (it === there) CloudRecords.Held.YES else CloudRecords.Held.NO },
            refusedByRules = ::ruled,
        )
        assertFalse("the push goes on", r.stopped)
        assertEquals(listOf(rs[0], there, rs[3]), r.uploaded)
        assertEquals(2, r.written)
        assertEquals(listOf(tooFar), r.refused)
    }

    /** Negative: offline or signed out still stops, and the records after it wait for the next push. */
    @Test
    fun a_transient_failure_still_stops_the_push() = runBlocking {
        val rs = (1..3).map { record(1, expense(300, listOf(me to 150, them to 150)), millis = it.toULong()) }
        for (exists in listOf(CloudRecords.Held.NO, CloudRecords.Held.UNKNOWN, CloudRecords.Held.DENIED)) {
            val r = CloudRecords.oneByOne(rs, write = { if (it === rs[1]) throw Transient() }, exists = { exists }, refusedByRules = ::ruled)
            assertTrue(r.stopped)
            assertEquals(listOf(rs[0]), r.uploaded)
            assertTrue(r.refused.isEmpty())
        }
        // Refused, but the read could not answer (offline after all): a stop, never a refusal for good.
        val unsure = CloudRecords.oneByOne(rs, write = { if (it === rs[0]) throw RuleRefusal() }, exists = { CloudRecords.Held.UNKNOWN }, refusedByRules = ::ruled)
        assertTrue(unsure.stopped)
        assertTrue(unsure.refused.isEmpty())
        assertTrue(unsure.uploaded.isEmpty())
    }

    /** The refused record is kept on the phone and never sent again; the push no longer stops at it. */
    @Test
    fun a_refused_record_is_marked_and_not_sent_again() {
        val store = device("refused")
        CloudRecords.saveUploadRefused(store, setOf("1-0-$them"))
        assertEquals(setOf("1-0-$them"), CloudRecords.uploadRefused(store))
        val push = body("CloudSync.kt", "    suspend fun pushAll(", "    ")
        assertTrue("pending leaves the parked out (round 4: unless due)", "CloudRecords.sendable(out.send, parked, retries, nowMs)" in push)
        assertTrue("one by one through the tested helper", "CloudRecords.oneByOne(" in push)
        assertTrue("refusals are saved", "CloudRecords.updateParked(store, newlyRefused, parked intersect uploaded)" in push)
        assertTrue("only a stop is offline", "offline = result.stopped" in push)
        // Round 6 (twin of iOS): syncNow leaves out only the parked records not due again.
        assertTrue("CloudRecords.adoptRefused(store) + CloudRecords.heldParked(store, System.currentTimeMillis())" in body("CloudSync.kt", "    suspend fun syncNow(", "    "))
        val day = CloudRecords.RETRY_EVERY_MS
        val t = 4_000_000_000_000L
        CloudRecords.updateParked(
            store,
            mapOf("held" to CloudRecords.Retry(1, t), "due" to CloudRecords.Retry(1, t - day), "done" to CloudRecords.Retry(3, t - 9 * day)),
            emptySet(),
        )
        // "1-0-them" was parked with no entry above: due now. "held" was refused an hour ago: not due.
        assertEquals(setOf("held", "done"), CloudRecords.heldParked(store, t + 3_600_000))
        // The clock went back below "held"'s refusal: due. "due" was refused a day before t, so at t - 1 it is not.
        assertEquals("the clock went back: held is due", setOf("due", "done"), CloudRecords.heldParked(store, t - 1))
        assertFalse("the old stop at any failed record is gone", "offline = true" in push)
    }

    // ---- Round 4 (shells review M7, M9, decision 114) ----

    /** M7: a mark another writer saved while a push waited stays (the push adds to what is stored, never saves its
     *  stale copy). */
    @Test
    fun marks_are_added_to_what_is_stored_now() {
        val store = device("marks")
        CloudRecords.saveUploaded(store, setOf("a"))
        val pushCopy = CloudRecords.uploaded(store)
        CloudRecords.saveUploaded(store, CloudRecords.uploaded(store) + "b") // receive, meanwhile
        CloudRecords.addUploaded(store, (pushCopy + "c") - pushCopy)
        assertEquals(setOf("a", "b", "c"), CloudRecords.uploaded(store))
        CloudRecords.saveGroupsUp(store, setOf(group))
        CloudRecords.addGroupsUp(store, setOf(third))
        assertEquals(setOf(group, third), CloudRecords.groupsUp(store))
        CloudRecords.saveAdoptRefused(store, setOf("x"))
        CloudRecords.addAdoptRefused(store, setOf("y"))
        assertEquals(setOf("x", "y"), CloudRecords.adoptRefused(store))
        CloudRecords.addUploaded(store, emptySet())
        assertEquals(setOf("a", "b", "c"), CloudRecords.uploaded(store))
        // Negative: pushAll saves no whole set of its own copies any more.
        val push = body("CloudSync.kt", "    suspend fun pushAll(", "    ")
        for (stale in listOf("saveUploaded(", "saveGroupsUp(", "saveAdoptRefused(", "saveUploadRefused(")) {
            assertFalse("pushAll calls $stale", stale in push)
        }
        assertTrue("CloudRecords.addUploaded(store, uploaded - uploadedBefore)" in push)
        assertTrue("CloudRecords.addGroupsUp(store, groupsUp - upBefore)" in push)
    }

    /** M9: only the vault's own account syncs it; no owner, an unreadable one (null) or another account: nothing. */
    @Test
    fun only_the_vaults_owner_syncs_it() {
        assertEquals("u1", CloudGate.owned("u1", "u1"))
        assertNull("another account", CloudGate.owned("u1", "u2"))
        assertNull("no owner, or one that could not be read", CloudGate.owned("u1", null))
        assertNull("signed out", CloudGate.owned(null, "u1"))
        assertNull(CloudGate.owned(null, null))
        val ready = body("CloudSync.kt", "    private fun ready(", "    ")
        assertTrue("if (CloudGate.owned(uid, Account.storedOwner(store)) == null) return null" in ready)
        assertTrue("CloudGate.owned(Account.uid(), Account.storedOwner(store)) ?: return" in body("CloudSync.kt", "    fun registerPhone(", "    "))
        // A read error is no owner (fail closed), and nothing signs out for it.
        val read = main("Account.kt").substringAfter("fun storedOwner(").substringBefore("\n\n")
        assertTrue("runCatching" in read)
        assertFalse("signOut" in read)
    }

    /** Decision 114: a parked record is tried again once a day after its last refusal, at most three times. */
    @Test
    fun a_parked_record_is_tried_once_a_day_at_most_three_times() {
        val day = CloudRecords.RETRY_EVERY_MS
        val t0 = 4_000_000_000_000L
        val first = CloudRecords.afterRefusal(null, wasParked = false, nowMs = t0)
        assertEquals(CloudRecords.Retry(0, t0), first)
        assertFalse("not the same day", CloudRecords.retryDue(first, t0 + day - 1))
        assertTrue("a day later", CloudRecords.retryDue(first, t0 + day))
        var r = first
        for (n in 1..3) {
            val at = r.lastMs + day
            assertTrue("try $n is due", CloudRecords.retryDue(r, at))
            r = CloudRecords.afterRefusal(r, wasParked = true, nowMs = at)
            assertEquals(n, r.tries)
        }
        assertFalse("three tries, then it stays parked", CloudRecords.retryDue(r, r.lastMs + 100 * day))
        assertFalse("nor when the clock goes back", CloudRecords.retryDue(r, r.lastMs - 1))
        // Round 5 (iOS's rule): the clock gone back below the last refusal (a fast clock fixed) is due at once...
        assertTrue(CloudRecords.retryDue(first, t0 - 1))
        assertTrue(CloudRecords.retryDue(first, t0 - 30 * day))
        // ...and the same moment, or later within the day, is not.
        assertFalse(CloudRecords.retryDue(first, t0))
        assertFalse(CloudRecords.retryDue(first, t0 + 1))
        assertTrue("parked before this build (no entry): due once now", CloudRecords.retryDue(null, t0))
        assertEquals(CloudRecords.Retry(1, t0), CloudRecords.afterRefusal(null, wasParked = true, nowMs = t0))
        // What a push sends: waiting records, a due parked one, never a parked one that is not due.
        val rs = (1..3).map { record(1, expense(300, listOf(me to 150, them to 150)), millis = it.toULong()) }
        val ids = rs.map(CloudRecords::documentId)
        val retries = mapOf(ids[1] to CloudRecords.Retry(1, t0), ids[2] to CloudRecords.Retry(3, t0 - 10 * day))
        assertEquals(listOf(rs[0]), CloudRecords.sendable(rs, setOf(ids[1], ids[2]), retries, t0 + day - 1))
        assertEquals(listOf(rs[0], rs[1]), CloudRecords.sendable(rs, setOf(ids[1], ids[2]), retries, t0 + day))
    }

    @Test
    fun parked_records_are_kept_and_let_go_against_what_is_stored() {
        val store = device("parked")
        CloudRecords.updateParked(store, mapOf("p1" to CloudRecords.Retry(0, 5), "p2" to CloudRecords.Retry(2, 7)), emptySet())
        assertEquals(setOf("p1", "p2"), CloudRecords.uploadRefused(store))
        assertEquals(mapOf("p1" to CloudRecords.Retry(0, 5), "p2" to CloudRecords.Retry(2, 7)), CloudRecords.uploadRetries(store))
        // Meanwhile another push parked p3; this one lets p1 go (it went up) and counts a try on p2.
        CloudRecords.updateParked(store, mapOf("p3" to CloudRecords.Retry(0, 9)), emptySet())
        CloudRecords.updateParked(store, mapOf("p2" to CloudRecords.Retry(3, 11)), setOf("p1"))
        assertEquals(setOf("p2", "p3"), CloudRecords.uploadRefused(store))
        assertEquals(mapOf("p2" to CloudRecords.Retry(3, 11), "p3" to CloudRecords.Retry(0, 9)), CloudRecords.uploadRetries(store))
    }

    // ---- Decision 122: a quiet line when a change could not sync ----

    /** A group's page shows its line while any of this phone's records in THAT group is parked (due again or not), not
     *  for a record parked in another group or a personal one, not with none, and not once it went up. Real vault, real
     *  group records (iOS's twin: testAGroupWithAParkedChangeSaysSoInOneQuietLine). */
    @Test
    fun a_group_with_a_parked_change_says_so_in_one_quiet_line() {
        val store = device("parked-line")
        store.setYourName("Asha")
        val goa = store.createGroup("Goa", "INR", listOf("Sam")).group.id
        val oslo = store.createGroup("Oslo", "NOK", listOf("Kari")).group.id
        val all = store.allRecords()
        val inGoa = CloudRecords.documentId(all.first { it.groupId == goa })
        val inOslo = CloudRecords.documentId(all.first { it.groupId == oslo })
        assertFalse("nothing parked: no line", CloudRecords.hasParked(store, goa))
        assertFalse(CloudRecords.hasParked(store, oslo))

        CloudRecords.updateParked(store, mapOf(inGoa to CloudRecords.Retry(0, 4_000_000_000_000L)), emptySet())
        assertTrue("parked in Goa: Goa's page says so", CloudRecords.hasParked(store, goa))
        assertFalse("not Oslo's", CloudRecords.hasParked(store, oslo))
        // Exhausted (no retry due again), it is still parked: the line stays.
        CloudRecords.updateParked(store, mapOf(inGoa to CloudRecords.Retry(CloudRecords.RETRY_MAX, 4_000_000_000_000L)), emptySet())
        assertTrue(CloudRecords.hasParked(store, goa))
        // A record parked outside any group (or one this phone does not hold) shows on no group.
        val personal = RecordWire(1u, 1u, null, 0u, 1uL, 0u, them, them, byteArrayOf(0, 0, 0, 1), 0u, "{}".toByteArray())
        assertFalse(CloudRecords.parkedIn(oslo, all + personal, setOf(CloudRecords.documentId(personal), "1-0-x")))
        assertTrue(CloudRecords.parkedIn(oslo, all, setOf(inOslo)))
        // Up at last: the line goes.
        CloudRecords.updateParked(store, emptyMap(), setOf(inGoa))
        assertFalse(CloudRecords.hasParked(store, goa))

        // The page's wiring: read with the page, one quiet line in the header's meta style; a push that parks or lets
        // go asks the open page to read again.
        val page = main("OverviewTab.kt")
        assertTrue("CloudRecords.hasParked(store, groupId)," in page)
        assertTrue("parkedHere = it.parked" in page)
        assertTrue(
            "if (parkedHere) Text(OverviewCopy.COULD_NOT_SYNC, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))" in page,
        )
        assertTrue(
            "if (newlyRefused.isNotEmpty() || (parked intersect uploaded).isNotEmpty()) withContext(Dispatchers.Main) { SyncStatus.changed() }" in
                main("CloudSync.kt"),
        )
    }

    /** Parity: the line's words are iOS's exactly (OverviewScreen.swift's `OverviewCopy.couldNotSync`). */
    @Test
    fun the_could_not_sync_line_is_the_same_words_as_ios() {
        val swift = java.io.File("../../ios/Skipti/App/OverviewScreen.swift")
        assertTrue("iOS has OverviewScreen.swift", swift.exists())
        val ios = Regex("""(?m)^    static let couldNotSync = "([^"]*)"$""").findAll(swift.readText()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("One change could not sync. It is still on this phone."), ios)
        assertEquals(ios.single(), OverviewCopy.COULD_NOT_SYNC)
    }

    // ---- M3: one stamp per write ----

    /** Eight threads mint stamps at the same wall time from one vault: every stamp is different. Without the lock two
     *  writers read the same state and mint one stamp twice (a record's identity: the second REPLACES the first). */
    @Test
    fun concurrent_writers_never_mint_the_same_stamp() {
        // As the app opens it: one open at a time (VaultGate's lock), and the vault made before the threads start, so
        // the only race left is the HLC state's read-modify-write.
        val opening = Any()
        val keystore = object : SkiptiKeystore {
            @Volatile var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = dir.resolve("hlc.db").toString()
        val store = LedgerStore(path) { synchronized(opening) { SkiptiVault.open(path, keystore) } }
        LedgerStore.Sync.deviceId(store)
        val now = 4_000_000_000_000L
        val stamps = java.util.Collections.synchronizedList(mutableListOf<Pair<ULong, UInt>>())
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                start.await()
                repeat(25) {
                    try {
                        val s = LedgerStore.Sync.nextHlc(store, now)
                        stamps += s.millis to s.counter
                        if (it % 5 == 0) LedgerStore.Sync.receiveHlc(store, now.toULong(), 0u, now)
                    } catch (e: Throwable) {
                        errors += e
                    }
                }
            }.also { t -> t.start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertTrue("no writer failed: $errors", errors.isEmpty())
        assertEquals(200, stamps.size)
        assertEquals("every stamp is new", 200, stamps.toSet().size)
    }

    // ---- M1, M2: the push token and offline sign-out ----

    @Test
    fun sign_out_removes_what_may_exist_not_only_what_this_process_registered() {
        // The audit's case: registered in an earlier process, this launch could not register (offline).
        val p = PushCleanup.plan(inCloud = true, uid = "u1", inMemory = false, registeredFor = "u1", wasRunning = false, deleteOwed = false)
        assertEquals(PushCleanup.Plan(clearCloud = true, deleteAtFcm = true), p)
        // Sync off the same way.
        assertTrue(PushCleanup.plan(true, "u1", false, "u1", false, false).clearCloud)
        // Signed out already (inCloud false, or no uid): only FCM.
        assertEquals(PushCleanup.Plan(false, true), PushCleanup.plan(false, null, false, "u1", false, false))
        assertEquals(PushCleanup.Plan(false, true), PushCleanup.plan(true, null, false, "u1", false, false))
        // Another account's mark is not this account's document to clear, but FCM's token still goes.
        assertEquals(PushCleanup.Plan(false, true), PushCleanup.plan(true, "u2", false, "u1", false, false))
        // A delete still owed is tried again.
        assertEquals(PushCleanup.Plan(false, true), PushCleanup.plan(false, null, false, null, false, true))
        // Negative: a phone that never had a token touches nothing.
        assertEquals(PushCleanup.Plan(false, false), PushCleanup.plan(true, "u1", false, null, false, false))
        // This process's own token, as before.
        assertEquals(PushCleanup.Plan(true, true), PushCleanup.plan(true, "u1", true, null, true, false))
    }

    @Test
    fun the_push_marks_live_in_the_vault() {
        val stop = body("Push.kt", "    suspend fun stop(", "    ")
        assertTrue("mark(store, REGISTERED_KEY)" in stop)
        assertTrue("mark(store, DELETE_OWED_KEY)" in stop)
        assertTrue("both cloud calls are bounded", "Bounded.run(CLOUD_TIMEOUT_MS) { device(uid, store)" in stop)
        assertTrue(
            "Bounded.run(CLOUD_TIMEOUT_MS) { messaging.deleteToken()" in body("Push.kt", "    private suspend fun deleteAtFcm(", "    "),
        )
        assertTrue(
            "store.rawKvSet(REGISTERED_KEY, uid.toByteArray" in body("Push.kt", "    fun saveToken(", "    "),
        )
        assertTrue("Push.settleOwed(" in body("CloudSync.kt", "    fun appIsActive(", "    "))
    }

    // A bound that is lost hangs: the timeout makes that a red, not a stuck run.
    @Test(timeout = 20_000)
    fun a_bounded_call_finishes_fails_or_runs_out_of_time() = runBlocking {
        assertTrue(Bounded.run(1_000) { })
        assertFalse(Bounded.run(1_000) { error("refused") })
        val started = System.nanoTime()
        assertFalse("a call that never answers", Bounded.run(50) { awaitCancellation() })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        // The caller's own cancellation is never swallowed.
        var reached = false
        val job = launch {
            Bounded.run(10_000) { awaitCancellation() }
            reached = true
        }
        kotlinx.coroutines.delay(50)
        job.cancel()
        job.join()
        assertFalse("cancellation went on up", reached)
    }

    // A bound that is lost hangs: the timeout makes that a red, not a stuck run.
    @Test(timeout = 20_000)
    fun ensuring_a_group_offline_says_offline_in_bounded_time() = runBlocking {
        val m = CloudSync.GroupState.MEMBER
        assertEquals(m, GroupEnsure.decide(create = { }, read = { fail("not asked"); m }))
        assertEquals(
            CloudSync.GroupState.NOT_MEMBER,
            GroupEnsure.decide(create = { error("exists") }, read = { CloudSync.GroupState.NOT_MEMBER }),
        )
        assertEquals(m, GroupEnsure.decide(create = { error("exists") }, read = { m }))
        val started = System.nanoTime()
        assertEquals(
            "offline: the write never completes, nor the read",
            CloudSync.GroupState.OFFLINE,
            GroupEnsure.decide(create = { awaitCancellation() }, read = { awaitCancellation() }, createMs = 50, readMs = 50),
        )
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertTrue("CloudSync uses it", "GroupEnsure.decide(" in body("CloudSync.kt", "    suspend fun ensureGroup(", "    "))
    }

    @Test
    fun sign_out_is_busy_from_the_tap_and_never_cancelled_half_way() {
        val card = main("AccountHubScreen.kt").substringAfter("SecondaryAction(\"Sign out\", dark) {").substringBefore("onSignedOut()")
        assertTrue(card.indexOf("busy = true") in 0 until card.indexOf("scope.launch"))
        assertTrue("withContext(NonCancellable + Dispatchers.IO)" in card)
        val toggle = main("AccountHubScreen.kt").substringAfter("onToggle = {").substringBefore("reload()")
        assertTrue(toggle.indexOf("if (busy) return@SyncCard") in 0 until toggle.indexOf("scope.launch"))
        assertTrue("addOnCanceledListener { cont.cancel() }" in main("CloudProfile.kt"))
    }

    // ---- M5: export never leaves an empty file without a word ----

    @Test
    fun export_reads_the_ledger_again_when_the_screen_lost_it() {
        val store = device("export")
        store.appendExpense(ExpenseDTO(payer = me, paidMinor = 1200, currencyCode = "INR", shares = listOf(ExpenseDTO.ShareDTO(me, 1200))))
        val out = ByteArrayOutputStream()
        val n = ExportWrite.write(held = null, load = { store.snapshot() }, format = Exporter.Format.JSON, open = { out })
        assertTrue(n > 0)
        assertEquals(n, out.size())
        assertTrue("1200" in out.toString(Charsets.UTF_8))
        // Negatives: a ledger that cannot be read, and a destination that cannot be opened, throw (the screen says so).
        assertThrows { ExportWrite.write(null, { error("vault") }, Exporter.Format.CSV, { ByteArrayOutputStream() }) }
        assertThrows { ExportWrite.write(store.snapshot(), { error("not asked") }, Exporter.Format.CSV, { null }) }
        val write = body("ExportScreen.kt", "    fun writeUri(", "    ")
        assertFalse("no silent return when the snapshot is gone", "snapshot ?: return" in write)
        assertTrue("ExportWrite.write(" in write)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: CancellationException) {
            fail("cancelled")
        } catch (_: Exception) {
            return
        }
        fail("expected a failure")
    }

    // ---- L6: cleartext only in debug builds ----

    @Test
    fun release_permits_no_cleartext_and_debug_only_the_loopback() {
        val release = java.io.File("src/main/res/xml/network_security_config.xml").readText()
        val debug = java.io.File("src/debug/res/xml/network_security_config.xml").readText()
        val releaseXml = release.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse("cleartextTrafficPermitted=\"true\"" in releaseXml)
        assertTrue("<base-config cleartextTrafficPermitted=\"false\" />" in releaseXml)
        assertFalse("<domain" in releaseXml)
        val debugXml = debug.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertEquals(
            listOf("localhost", "127.0.0.1"),
            Regex("<domain includeSubdomains=\"false\">([^<]+)</domain>").findAll(debugXml).map { it.groupValues[1] }.toList(),
        )
        assertTrue("android:networkSecurityConfig=\"@xml/network_security_config\"" in java.io.File("src/main/AndroidManifest.xml").readText())
    }

    // ---- D-235 final wave: the owner read at sign-in fails closed; ProfileShare's owner gate (twins of iOS) ----

    /** iOS M4's twin: a vault that cannot be read at sign-in refuses the sign-in and keeps the vault, never "no owner
     *  yet" (which would write the incoming account over the real owner). */
    @Test
    fun a_sign_in_whose_owner_read_fails_is_refused_and_keeps_the_vault() {
        val open = opener("owner-read")
        val real = LedgerStore(dir.resolve("owner-read.db").toString()) { open() }
        real.rawKvSet(Account.OWNER_KEY, "uid-tim".toByteArray(Charsets.UTF_8))
        // The vault fails its first open (the owner read) and opens after: a fail-open read would write uid-bob.
        var opens = 0
        val flaky = LedgerStore(real.path) {
            opens += 1
            if (opens == 1) throw IllegalStateException("the vault will not open") else open()
        }
        var signedOut = 0
        val outcome = Account.keep("uid-bob", SignInMethod.Google, flaky) { signedOut += 1 }
        assertEquals(Account.Outcome.Failed(SignedInCopy.NOT_SAVED), outcome)
        assertEquals("signed back out", 1, signedOut)
        assertEquals("the owner is untouched", "uid-tim", Account.readOwner(real))
        assertNull("no method kept", SignInMethod.stored(real))
        try {
            Account.readOwner(LedgerStore(real.path) { throw IllegalStateException("no") })
            fail("readOwner must throw when the vault cannot be read")
        } catch (_: IllegalStateException) {
        }
        // The honest cases are unchanged: no owner takes this account, the same account signs in, another is refused.
        val fresh = device("owner-fresh")
        assertEquals(Account.Outcome.SignedIn, Account.keep("uid-bob", SignInMethod.Apple, fresh) { signedOut += 1 })
        assertEquals("uid-bob", Account.readOwner(fresh))
        assertEquals(SignInMethod.Apple, SignInMethod.stored(fresh))
        assertEquals(Account.Outcome.SignedIn, Account.keep("uid-bob", SignInMethod.Google, fresh) { signedOut += 1 })
        assertEquals(Account.Outcome.OtherAccount, Account.keep("uid-eve", SignInMethod.Google, fresh) { signedOut += 1 })
        assertEquals("uid-bob", Account.readOwner(fresh))
        assertEquals("only the refusals signed out", 2, signedOut)
        // signIn decides through keep, and the cloud gates keep their fail-closed read.
        val signIn = body("Account.kt", "    suspend fun signIn(", "    ")
        assertTrue("Account.keep(incoming, method, store)" in signIn || "keep(incoming, method, store)" in signIn)
        assertFalse("signIn reads no owner of its own", "storedOwner(" in signIn)
        assertFalse("keep never reads the fail-closed owner", "storedOwner(" in body("Account.kt", "    internal fun keep(", "    "))
    }

    /** ProfileShare runs only for the vault's own account with sync on (iOS's `LaunchWork.signedInAccount`). */
    @Test
    fun the_profile_share_runs_only_for_the_vaults_owner() {
        assertEquals("u1", ProfileShare.accountFor(syncOn = true, uid = "u1", owner = "u1"))
        assertNull("another account", ProfileShare.accountFor(syncOn = true, uid = "u1", owner = "u2"))
        assertNull("no owner, or one that could not be read", ProfileShare.accountFor(syncOn = true, uid = "u1", owner = null))
        assertNull("signed out", ProfileShare.accountFor(syncOn = true, uid = null, owner = "u1"))
        assertNull("sync off", ProfileShare.accountFor(syncOn = false, uid = "u1", owner = "u1"))
        val real = main("ProfileSync.kt").substringAfter("    val realAccount: (LedgerStore) -> String? = { store ->")
            .substringBefore("\n    }\n")
        assertTrue(real, "accountFor(CloudSync.syncEnabled(store), Account.uid(), Account.storedOwner(store))" in real)
    }

    // ---- Look fix F4: a record for a group this account was removed from parks; it never stops the push ----

    // A write too big for Firestore (INVALID_ARGUMENT): a rule refusal, but not a permission denial.
    private class TooBig : Exception("invalid argument")

    private fun ruledOrBig(e: Exception) = e is RuleRefusal || e is TooBig
    private fun denied(e: Exception) = e is RuleRefusal

    private fun recordIn(groupId: String?, millis: ULong) =
        RecordWire(1u, 1u, groupId, 0u, millis, 0u, them, them, byteArrayOf(0x45, 0x58, 0x50, 0x01), 0u, expense(300, listOf(me to 150, them to 150)))

    /** The pure rule: the write AND the existence read both permission-denied, for a group's record, is a refusal
     *  (parked by decision 114); every other mix with a denied or unanswered read still stops. */
    @Test
    fun a_denied_write_whose_read_is_denied_too_parks_in_a_group() {
        val held = CloudRecords.Held.DENIED
        assertEquals("removed from the group: parked", CloudRecords.UploadFailure.REFUSED, CloudRecords.uploadFailure(true, true, true, held))
        assertEquals("a personal record's read is never a membership denial", CloudRecords.UploadFailure.STOP, CloudRecords.uploadFailure(true, true, false, held))
        assertEquals("too big, read denied: a stop", CloudRecords.UploadFailure.STOP, CloudRecords.uploadFailure(true, false, true, held))
        assertEquals("not the rules (network, auth): a stop", CloudRecords.UploadFailure.STOP, CloudRecords.uploadFailure(false, false, true, held))
        assertEquals("denied, the read unanswered: a stop", CloudRecords.UploadFailure.STOP, CloudRecords.uploadFailure(true, true, true, CloudRecords.Held.UNKNOWN))
        val codes = body("CloudSync.kt", "    fun isPermissionDenied(", "    ")
        assertTrue(codes, "Code.PERMISSION_DENIED" in codes)
        assertFalse(codes, "INVALID_ARGUMENT" in codes || "UNAUTHENTICATED" in codes || "UNAVAILABLE" in codes)
    }

    /** One push: the removed group's record is set aside and the next group's record still goes up. */
    @Test
    fun a_removed_groups_record_parks_and_another_groups_record_still_uploads() = runBlocking {
        val removed = "e".repeat(32)
        val gone = recordIn(removed, 1u)
        val stays = recordIn(group, 2u)
        val r = CloudRecords.oneByOne(
            listOf(gone, stays),
            write = { if (it === gone) throw RuleRefusal() },
            exists = { if (it === gone) CloudRecords.Held.DENIED else CloudRecords.Held.NO },
            refusedByRules = ::ruledOrBig,
            deniedByRules = ::denied,
        )
        assertFalse("the push goes on", r.stopped)
        assertEquals(listOf(gone), r.refused)
        assertEquals(listOf(stays), r.uploaded)
        assertEquals(1, r.written)
    }

    /** Negative: a network or auth failure with a denied or unanswered read still stops, and a personal record denied
     *  both ways stops too (that is auth trouble, not a removal). */
    @Test
    fun a_network_error_still_stops_when_the_read_is_denied() = runBlocking {
        val a = recordIn(group, 1u)
        val b = recordIn(group, 2u)
        for (held in listOf(CloudRecords.Held.DENIED, CloudRecords.Held.UNKNOWN)) {
            val r = CloudRecords.oneByOne(listOf(a, b), write = { if (it === a) throw Transient() }, exists = { held }, refusedByRules = ::ruledOrBig, deniedByRules = ::denied)
            assertTrue(r.stopped)
            assertTrue(r.uploaded.isEmpty() && r.refused.isEmpty())
        }
        val big = CloudRecords.oneByOne(listOf(a, b), write = { if (it === a) throw TooBig() }, exists = { CloudRecords.Held.DENIED }, refusedByRules = ::ruledOrBig, deniedByRules = ::denied)
        assertTrue("too big, read denied", big.stopped)
        val mine = recordIn(null, 3u)
        val personal = CloudRecords.oneByOne(listOf(mine, b), write = { if (it === mine) throw RuleRefusal() }, exists = { CloudRecords.Held.DENIED }, refusedByRules = ::ruledOrBig, deniedByRules = ::denied)
        assertTrue("a personal record denied both ways", personal.stopped)
        assertTrue(personal.refused.isEmpty())
    }

    /** pushAll's read: a permission denial is DENIED only while this account is still the one signed in; any other
     *  failure is UNKNOWN (a stop). */
    @Test
    fun the_push_reads_a_denied_existence_as_denied() {
        val push = body("CloudSync.kt", "    suspend fun pushAll(", "    ")
        assertTrue(push, "exists = { record -> CloudRecords.held(Account.uid() == uid) { doc(record).get(Source.SERVER).awaitTask().exists() } }" in push)
    }

    @Test
    fun the_held_read_maps_each_answer() = runBlocking {
        assertEquals(CloudRecords.Held.YES, CloudRecords.held(true) { true })
        assertEquals(CloudRecords.Held.NO, CloudRecords.held(true) { false })
        assertEquals("a socket failure", CloudRecords.Held.UNKNOWN, CloudRecords.held(true) { throw Transient() })
        assertEquals("signed out or another account: never DENIED", CloudRecords.Held.UNKNOWN, CloudRecords.held(false) { true })
        assertEquals(CloudRecords.Held.DENIED, CloudRecords.held(true, isDenied = { it is RuleRefusal }) { throw RuleRefusal() })
        assertEquals(CloudRecords.Held.UNKNOWN, CloudRecords.held(false, isDenied = { it is RuleRefusal }) { throw RuleRefusal() })
    }

    // ---- Look fix F3: an open expense reads again when sync brings a change ----

    @Test
    fun the_expense_detail_reads_again_on_every_sync_change() {
        val screen = body("ExpenseDetailScreen.kt", "fun ExpenseDetailScreen(")
        assertTrue(screen, "LaunchedEffect(vaultPath, expenseId, reloads, SyncStatus.revision) {" in screen)
        assertFalse("one load only", "LaunchedEffect(vaultPath, expenseId, reloads) {" in screen)
    }
}
