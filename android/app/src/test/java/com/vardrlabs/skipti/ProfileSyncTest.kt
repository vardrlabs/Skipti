package com.vardrlabs.skipti

import com.google.firebase.Timestamp
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.CoreException
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.GroupOpMemberIn
import uniffi.skipti_core.GroupPictureOut
import uniffi.skipti_core.PortProfile
import uniffi.skipti_core.RecordWire
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.ffiDecodeGroupOp
import uniffi.skipti_core.ffiEncodeGroupOp
import uniffi.skipti_core.ffiExportLedgerJson
import uniffi.skipti_core.ffiImportLedgerJson

/**
 * D-211: your name and picture on your phones and in your groups. The twin of
 * iOS's ProfileSyncTests (same inputs, same expectations), through the real
 * core and real vaults. The job's tests run it on a queue the test drains, so
 * what runs when is the test's to say (the main actor's order on iOS). The
 * pixel work needs Android's Bitmap and is seen on the emulator; its rule
 * ([ProfileRules.squareJpegSteps]) is tested here with real JPEG sizes.
 */
class ProfileSyncTest {

    private val dir = Files.createTempDirectory("profile-sync")
    private val me = "a".repeat(32)
    private val device = "b".repeat(32)
    private val other = "d".repeat(32)
    private val gA = "1".repeat(32)
    private val gB = "2".repeat(32)
    private val gC = "3".repeat(32)
    private val tim = "4".repeat(32)
    private val bo = "5".repeat(32)
    private val placeX = "6".repeat(32)
    private val cy = "7".repeat(32)
    private val gD = "8".repeat(32)
    private val placeY = "9".repeat(32)
    private val jpegHead = "ffd8ffe0"

    private fun tempStore(): LedgerStore {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = dir.resolve("vault-${System.nanoTime()}.db").toString()
        return LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    private fun rec(op: GroupOpIn, group: String?, device: String = other, millis: ULong = 1uL): RecordWire = RecordWire(
        version = 1u, recordType = LedgerStore.GROUP_OP_TYPE, groupId = group, epoch = 0u, hlcMillis = millis,
        hlcCounter = 0u, hlcDeviceId = device, authorDeviceId = device, nonceSession = ByteArray(0), flags = 0u,
        blob = ffiEncodeGroupOp(op),
    )

    private fun profileOp(group: String, member: String, name: String = "Al"): GroupOpIn =
        GroupOpIn.MemberProfile(group, member, name, "🦊", null)

    private fun members(vararg ids: Pair<String, String>) = ids.map { GroupOpMemberIn(it.first, it.second) }

    /** A vault that holds: this account's you; group A it made (it is the
     *  place); group B made by Tim, which it joined as `placeX`; group C it was
     *  taken out of. */
    private fun fixture(): LedgerStore {
        val store = tempStore()
        store.appendGroupOps(
            listOf(
                GroupOpIn.You(me, "Al"),
                GroupOpIn.Create(gA, "Goa", "USD", members(me to "Al", bo to "Bo")),
                GroupOpIn.Create(gB, "Trip", "USD", members(tim to "Tim", placeX to "Al on Tim's list")),
                Invites.claim(gB, placeX, me, device),
                GroupOpIn.Create(gC, "Old", "USD", members(me to "Al", cy to "Cy")),
                GroupOpIn.RemoveMember(gC, me),
            ), Placing.NONE,
        )
        return store
    }

    private fun keys(places: List<ProfileShare.Place>): Set<String> = places.map { it.groupId + "/" + it.memberId }.toSet()

    private fun avatarRecords(store: LedgerStore): Int = store.allRecords().count {
        runCatching { ffiDecodeGroupOp(it.blob) }.getOrNull() is GroupOpIn.Avatar
    }

    private fun document(record: RecordWire, uid: String, seconds: Long, marked: Boolean): MutableMap<String, Any?> {
        val d = CloudRecords.document(record, uid).toMutableMap<String, Any?>()
        d["createdAt"] = Timestamp(seconds, 0)
        if (marked) d["profile"] = true
        return d
    }

    // ---- What a group's log may bring in ----

    @Test
    fun an_unmarked_member_profile_is_refused_and_a_marked_one_comes_in() {
        val theirs = rec(profileOp(gA, bo), gA)
        fun refusal(r: RecordWire, profile: Boolean = false, from: String = gA) =
            CloudRecords.refusal(r, from, device, profile = profile)
        assertEquals("a phone wrote it, not the Function", CloudRecords.Refusal.UNVERIFIED_PROFILE, refusal(theirs))
        assertNull(refusal(theirs, profile = true))
        assertEquals(
            "marked, but another group's",
            CloudRecords.Refusal.WRONG_GROUP, refusal(rec(profileOp(gB, bo), gA), profile = true),
        )
        // This phone built it and never kept it: it comes back marked, and only marked.
        val mine = rec(profileOp(gA, me), gA, device = device)
        assertNull("this phone's own, marked", refusal(mine, profile = true))
        assertEquals("claiming to be this phone's, unmarked", CloudRecords.Refusal.THIS_DEVICE, refusal(mine))
        // The mark does not excuse another kind of record from this phone.
        val renamed = rec(GroupOpIn.RenameMember(gA, bo, "X"), gA, device = device)
        assertEquals(CloudRecords.Refusal.THIS_DEVICE, refusal(renamed, profile = true))
    }

    @Test
    fun an_avatar_in_a_group_log_is_refused_as_personal() {
        val avatar = rec(GroupOpIn.Avatar("🦊", null), gA)
        assertEquals(CloudRecords.Refusal.PERSONAL, CloudRecords.refusal(avatar, gA, device))
        assertEquals(
            "the mark makes no difference to a personal operation",
            CloudRecords.Refusal.PERSONAL, CloudRecords.refusal(avatar, gA, device, profile = true),
        )
    }

    /** D-244 (ADR-0043): a setting is personal: written with no group, refused from a group's log (marked or not),
     *  at home in the account's own. Twin: ProfileSyncTests.testASettingIsPersonal. */
    @Test
    fun a_setting_is_personal() {
        val op = GroupOpIn.Setting("appearance.theme", "norse")
        assertNull(Placing.groupOf(op))
        assertNull(LedgerStore.groupOpRecord(op, uniffi.skipti_core.HlcStamp(1000uL, 0u), device).groupId)
        val inGroup = rec(op, gA)
        assertEquals(CloudRecords.Refusal.PERSONAL, CloudRecords.refusal(inGroup, gA, device))
        assertEquals(
            "the mark makes no difference to a personal operation",
            CloudRecords.Refusal.PERSONAL, CloudRecords.refusal(inGroup, gA, device, profile = true),
        )
        assertNull(CloudRecords.personalRefusal(rec(op, null)))
    }

    @Test
    fun a_member_profile_in_the_personal_log_is_refused() {
        val profile = rec(profileOp(gA, me), null)
        assertEquals(CloudRecords.Refusal.WRONG_SCOPE, CloudRecords.personalRefusal(profile))
        assertNull(CloudRecords.personalRefusal(rec(GroupOpIn.Avatar("🦊", null), null)))
        assertNull(CloudRecords.personalRefusal(rec(GroupOpIn.You(me, "Al"), null)))
        // Through absorb: a personal document carrying one brings nothing in, marked or not.
        val store = tempStore()
        assertEquals(0, CloudRecords.absorb(listOf(document(profile, "u", 1, marked = true)), null, store).size)
        assertEquals(0, store.allRecords().size)
    }

    // ---- The places ----

    @Test
    fun the_places_are_the_claimed_ones_and_the_creators_own() {
        val store = fixture()
        val p = store.projectGroups()
        assertEquals(
            "the creator's own id, the claimed person; none for the group it was removed from",
            setOf("$gA/$me", "$gB/$placeX"), keys(ProfileShare.places(p, emptySet(), emptyMap())),
        )
        assertEquals("a read-only copy takes nothing", setOf("$gB/$placeX"), keys(ProfileShare.places(p, setOf(gA), emptyMap())))
        // No account's "you" yet: no places.
        assertEquals(0, ProfileShare.places(tempStore().projectGroups(), emptySet(), emptyMap()).size)
    }

    /** D-227: the cloud's own place for you in a group (its cached `places.you`)
     *  wins; without it, today's rule with every id of the account. A group made
     *  on the account's other phone under its older id is shared only by the
     *  cloud's place. The twin of iOS's ProfileSync places case. */
    @Test
    fun the_clouds_place_for_you_wins_and_every_id_is_tried_without_it() {
        val store = tempStore()
        val older = placeY
        val gE = "e".repeat(32)
        val gF = "f".repeat(32)
        store.appendGroupOps(listOf(GroupOpIn.You(me, "Al")), Placing.NONE, nowMs = 1_000L)
        // The account's other phone, before it met this one: its own "you", a
        // group it made, and a group it joined.
        val otherPhone = tempStore()
        otherPhone.appendGroupOps(
            listOf(
                GroupOpIn.You(older, "Al"),
                GroupOpIn.Create(gE, "Made there", "USD", members(older to "Al", bo to "Bo")),
                GroupOpIn.Create(gF, "Joined there", "USD", members(tim to "Tim", placeX to "Al")),
                Invites.claim(gF, placeX, older, other),
            ), Placing.NONE,
            nowMs = 2_000L,
        )
        store.applyRecords(otherPhone.allRecords())
        val p = store.projectGroups()
        assertEquals(me, p.you?.id)
        assertTrue("an older id of the account", older in p.otherYouIds)
        assertEquals(
            "none known: today's rule, the merged id for the group made there (the cloud does not know it), the claim",
            setOf("$gE/$me", "$gF/$placeX"), keys(ProfileShare.places(p, emptySet(), emptyMap())),
        )
        assertEquals(
            "the cloud's place",
            setOf("$gE/$older", "$gF/$placeX"),
            keys(ProfileShare.places(p, emptySet(), known = mapOf(gE to listOf(older)))),
        )
        assertEquals(
            "a read-only copy still takes nothing",
            setOf("$gF/$placeX"),
            keys(ProfileShare.places(p, setOf(gE), known = mapOf(gE to listOf(older)))),
        )
        // What is known comes from the cached roles.
        GroupRoles.setForTest(gE, RoleRules.Roles(null, emptyList(), RoleRules.Places(listOf(older), emptyList(), listOf(older))))
        GroupRoles.setForTest(gF, RoleRules.Roles(null, emptyList()))
        try {
            assertEquals(mapOf(gE to listOf(older)), GroupRoles.yourPlaces(store, p.groups.map { it.group.id }))
        } finally {
            GroupRoles.setForTest(gE, null)
            GroupRoles.setForTest(gF, null)
        }
    }

    // ---- The records ----

    @Test
    fun the_records_are_one_per_place_and_never_in_the_vault() {
        val store = fixture()
        val before = store.allRecords().size
        val places = ProfileShare.places(store.projectGroups(), emptySet(), emptyMap())
        val records = ProfileShare.records(store, places, "Al", "🦊", null)
        assertEquals(places.size, records.size)
        assertEquals("never put into the vault", before, store.allRecords().size)
        records.zip(places).forEach { (record, place) ->
            assertEquals(LedgerStore.GROUP_OP_TYPE, record.recordType)
            assertEquals("group scope", place.groupId, record.groupId)
            val op = ffiDecodeGroupOp(record.blob)
            assertTrue("a profile", op is GroupOpIn.MemberProfile)
            if (op is GroupOpIn.MemberProfile) {
                assertEquals(
                    listOf(place.groupId, place.memberId, "Al", "🦊", null),
                    listOf(op.groupId, op.memberId, op.name, op.emoji, op.thumbHex),
                )
            }
        }
        assertEquals("each its own identity", records.size, records.map { CloudRecords.documentId(it) }.toSet().size)
        // The call's data: the D-210 record shape, one entry per record.
        val sent = ProfileShare.callData(records).getValue("records").jsonArray
        assertEquals(records.map { CloudRecords.documentId(it) }, sent.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        val first = sent.firstOrNull()?.jsonObject
        assertNotNull(first)
        val firstRecord = records.firstOrNull()
        assertNotNull(firstRecord)
        if (first != null && firstRecord != null) {
            assertEquals("3", first.getValue("type").jsonPrimitive.content)
            assertEquals(firstRecord.authorDeviceId, first.getValue("deviceId").jsonPrimitive.content)
            assertEquals(
                Base64.getEncoder().encodeToString(firstRecord.blob),
                first.getValue("payload").jsonObject.getValue("blob").jsonPrimitive.content,
            )
        }
        // A name or preview core refuses (an emoji with a photo) builds nothing.
        try {
            ProfileShare.records(store, places, "Al", "🦊", jpegHead)
            fail("an emoji with a photo must be refused")
        } catch (_: CoreException) {
        }
    }

    @Test
    fun the_clouds_answers_decide_what_the_job_does() {
        assertEquals(ProfileShare.Answer.DONE, ProfileShare.answer(null))
        fun refused(status: String) = ProfileShare.answer(CloudFunctions.Failure.Refused(status))
        assertEquals(ProfileShare.Answer.NAME_CHANGED, refused("FAILED_PRECONDITION"))
        assertEquals(ProfileShare.Answer.DONE, refused("INVALID_ARGUMENT"))
        assertEquals(ProfileShare.Answer.LATER, refused("UNAVAILABLE"))
        assertEquals(ProfileShare.Answer.LATER, refused("PERMISSION_DENIED"))
        assertEquals(ProfileShare.Answer.LATER, ProfileShare.answer(CloudFunctions.Failure.Offline()))
        assertEquals(ProfileShare.Answer.LATER, ProfileShare.answer(CloudFunctions.Failure.SignedOut()))
    }

    // ---- The picture as a record ----

    @Test
    fun the_migration_writes_one_avatar_record_once() {
        val store = tempStore()
        store.rawKvSet(AVATAR_KEY, Avatar.Emoji("🦊").encoded())
        assertTrue(store.migrateAvatarOnce())
        assertFalse("the second run writes nothing", store.migrateAvatarOnce())
        assertEquals(1, avatarRecords(store))
        assertEquals(GroupPictureOut("🦊", null), store.projectGroups().avatar)
        // A rebuild never wipes it, and writes no second record.
        store.rebuildProjection()
        assertEquals(Avatar.Emoji("🦊"), store.loadAvatar())
        assertEquals(1, avatarRecords(store))
        // No picture kept: the flag is set and nothing is written.
        val empty = tempStore()
        assertFalse(empty.migrateAvatarOnce())
        assertEquals(0, avatarRecords(empty))
        assertNotNull(empty.rawKvGet(LedgerStore.AVATAR_RECORD_KEY))
    }

    @Test
    fun a_rebuild_before_the_migration_keeps_a_kept_picture() {
        val store = tempStore()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        store.rawKvSet(AVATAR_KEY, Avatar.Photo(jpeg).encoded())
        // The flag is not set yet and the records hold no picture: kv stays.
        assertNull(store.projectGroups().avatar)
        store.rawKvSet(LedgerStore.AVATAR_RECORD_KEY, ByteArray(0))
        store.rebuildGroups()
        assertEquals("migrated by the rebuild, never wiped", Avatar.Photo(jpeg), store.loadAvatar())
    }

    /** D-211 fix 6: a kept photo above the cap that cannot be stepped down (not
     *  an image), so the migration throws and the rebuild ignores it. The
     *  rebuild's kv guard must then leave the picture alone and the flag unset. */
    @Test
    fun a_rebuild_never_wipes_a_kept_picture_it_could_not_migrate() {
        val store = tempStore()
        val unreadable = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(ProfileRules.PHOTO_MAX_BYTES + 1) { 7 }
        store.rawKvSet(AVATAR_KEY, Avatar.Photo(unreadable).encoded())
        try {
            store.migrateAvatarOnce()
            fail("it cannot be stepped down")
        } catch (_: LedgerCorruptException) {
        }
        assertNull(store.rawKvGet(LedgerStore.AVATAR_RECORD_KEY))
        store.rebuildGroups()
        assertEquals("never wiped", Avatar.Photo(unreadable), store.loadAvatar())
        val flag = store.rawKvGet(LedgerStore.AVATAR_RECORD_KEY)
        assertTrue("the migration has still not run", flag == null || flag.isEmpty())
    }

    @Test
    fun a_picture_reaches_another_phone_and_clearing_reaches_it_too() {
        val a = tempStore()
        val b = tempStore()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 8, 7)
        a.saveAvatar(Avatar.Photo(jpeg))
        assertEquals(Avatar.Photo(jpeg), a.loadAvatar())
        b.applyRecords(a.allRecords())
        b.rebuildProjection()
        assertEquals("the other phone shows it, from the records", Avatar.Photo(jpeg), b.loadAvatar())
        a.saveAvatar(Avatar.None)
        b.applyRecords(a.allRecords())
        b.rebuildProjection()
        assertEquals(Avatar.None, b.loadAvatar())
        // Core refuses a photo that is not a JPEG: nothing is written.
        try {
            a.saveAvatar(Avatar.Photo(byteArrayOf(1, 2, 3)))
            fail("a photo that is not a JPEG must be refused")
        } catch (_: CoreException) {
        }
        assertEquals(Avatar.None, a.loadAvatar())
    }

    @Test
    fun every_offered_emoji_is_an_accepted_picture() {
        for (emoji in ProfileRules.EMOJI_CHOICES) {
            try {
                ffiEncodeGroupOp(GroupOpIn.Avatar(emoji, null))
            } catch (e: CoreException) {
                fail("$emoji is refused: $e")
            }
        }
    }

    @Test
    fun the_migration_never_brings_back_a_cleared_picture() {
        // Another phone of this account cleared the picture; this phone, not
        // migrated yet, still holds the old one in kv.
        val elsewhere = tempStore()
        elsewhere.appendGroupOps(listOf(GroupOpIn.Avatar(null, null)), Placing.NONE)
        val store = tempStore()
        store.rawKvSet(AVATAR_KEY, Avatar.Emoji("🦊").encoded())
        store.applyRecords(elsewhere.allRecords())
        val before = store.allRecords().size
        assertFalse("a picture record exists: nothing written", store.migrateAvatarOnce())
        assertEquals(before, store.allRecords().size)
        store.rebuildProjection()
        assertEquals("the clearing stands", Avatar.None, store.loadAvatar())
    }

    // ---- The preview ----

    /** A JPEG of [image] at [quality] (1..100), the way the phone's encoder would. */
    private fun jpeg(image: BufferedImage, quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality / 100f
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** A square of [side] pixels: a gradient with seeded noise, so the JPEG's
     *  size falls as its quality does. */
    private fun busy(side: Int, noise: Int): BufferedImage {
        val random = java.util.Random(7)
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until side) for (x in 0 until side) {
            fun channel(base: Int) = if (noise >= 255) random.nextInt(256) else (base + random.nextInt(2 * noise + 1) - noise).coerceIn(0, 255)
            image.setRGB(x, y, Color(channel(x * 255 / side), channel(y * 255 / side), channel((x + y) * 127 / side)).rgb)
        }
        return image
    }

    @Test
    fun the_thumb_is_at_most_12000_bytes_and_120_pixels() {
        val tried = mutableListOf<Int>()
        val thumb = ProfileRules.squareJpegSteps(1500, ProfileThumb.SIDE, ProfileThumb.MAX_BYTES, 80, 40) { drawn, quality ->
            tried += quality
            jpeg(busy(drawn, noise = 255), quality)
        }
        assertNotNull("it fits at some quality", thumb)
        if (thumb == null) return
        assertTrue("it is at most 12,000 bytes", thumb.size <= 12_000)
        assertEquals("a result that fits stops the steps", listOf(80), tried)
        val decoded = ImageIO.read(ByteArrayInputStream(thumb))
        assertEquals(listOf(120, 120), listOf(decoded?.width, decoded?.height))
        assertEquals("core takes it as a JPEG", listOf(0xFF, 0xD8, 0xFF), thumb.take(3).map { it.toInt() and 0xFF })
        // A size that falls with the quality (200 bytes a point): 80 and 70 are
        // too big, 60 fits, and nothing below it is tried.
        val stepped = mutableListOf<Int>()
        val fitted = ProfileRules.squareJpegSteps(1500, 120, 12_000, 80, 40) { _, quality ->
            stepped += quality
            ByteArray(quality * 200)
        }
        assertEquals(listOf(80, 70, 60), stepped)
        assertEquals(12_000, fitted?.size)
        // Never enlarged: a smaller source is drawn at its own side.
        val small = ProfileRules.squareJpegSteps(100, 120, 12_000, 80, 40) { drawn, quality -> jpeg(busy(drawn, 10), quality) }
        assertEquals(100, ImageIO.read(ByteArrayInputStream(small ?: ByteArray(0)))?.width)
        // Never fits: the name goes alone. Every step is tried, none past 40.
        val steps = mutableListOf<Int>()
        assertNull(ProfileRules.squareJpegSteps(1500, 120, 12_000, 80, 40) { _, quality -> steps += quality; ByteArray(12_001) })
        assertEquals(listOf(80, 70, 60, 50, 40), steps)
        assertNull("no square, no thumb", ProfileRules.squareJpegSteps(0, 120, 12_000, 80, 40) { _, _ -> ByteArray(1) })
        // The share: a photo becomes a preview, an emoji stays an emoji, nothing stays nothing.
        val hex = HexCodec.encode(thumb)
        assertEquals("🦊", ProfileShare.preview(GroupPictureOut("🦊", null)).emoji)
        val preview = ProfileShare.preview(GroupPictureOut(null, hex)) { thumb }
        assertNull(preview.emoji)
        assertEquals(hex, preview.thumbHex)
        assertNull(ProfileShare.preview(null).thumbHex)
        assertNull("unreadable: name only", ProfileShare.preview(GroupPictureOut(null, hex)) { null }.thumbHex)
        assertNull("not hex: name only", ProfileShare.preview(GroupPictureOut(null, "zz")) { thumb }.thumbHex)
    }

    @Test
    fun a_stored_photo_steps_down_until_it_fits() {
        val tried = mutableListOf<Int>()
        val stored = ProfileRules.squareJpegSteps(400, ProfileRules.PHOTO_SIDE, ProfileRules.PHOTO_MAX_BYTES, 80, 40) { drawn, quality ->
            tried += quality
            jpeg(busy(drawn, noise = 255), quality)
        }
        assertNotNull("it fits at some quality", stored)
        if (stored == null) return
        assertTrue(stored.size <= ProfileRules.PHOTO_MAX_BYTES)
        val decoded = ImageIO.read(ByteArrayInputStream(stored))
        assertEquals(listOf(256, 256), listOf(decoded?.width, decoded?.height))
        // A size that falls with the quality (1,000 bytes a point): 80 and 70
        // are over 64,000, 60 fits, and nothing below it is tried.
        val stepped = mutableListOf<Int>()
        val fitted = ProfileRules.squareJpegSteps(400, 256, ProfileRules.PHOTO_MAX_BYTES, 80, 40) { drawn, quality ->
            stepped += quality
            assertEquals("drawn at 256, the photo's own square being larger", 256, drawn)
            ByteArray(quality * 1000)
        }
        assertEquals(listOf(80, 70, 60), stepped)
        assertEquals(60_000, fitted?.size)
    }

    // ---- The job's flags ----

    @Test
    fun the_backfill_requests_once() {
        val store = tempStore()
        assertFalse(ProfileShare.isPending(store))
        assertTrue("the first sync start asks", ProfileShare.backfillOnce(store))
        assertTrue(ProfileShare.isPending(store))
        ProfileShare.setPending(store, false)
        assertFalse("never again on this install", ProfileShare.backfillOnce(store))
        assertFalse(ProfileShare.isPending(store))
    }

    // ---- A joiner's own profile ----

    @Test
    fun a_joiners_own_profile_shows_under_their_own_row() {
        val store = fixture()
        store.ensureSelf()
        // The profile the Function wrote for the place this account claimed in B, back through B's log.
        val records = ProfileShare.records(store, listOf(ProfileShare.Place(gB, placeX)), "Al Real", "🦊", null)
        val before = store.allRecords().size
        val record = records.firstOrNull()
        assertNotNull(record)
        if (record == null) return
        assertEquals(
            "unmarked: refused",
            0, CloudRecords.absorb(listOf(document(record, "u", 1, marked = false)), gB, store).size,
        )
        assertEquals(
            "marked, from this very phone: it comes in",
            1, CloudRecords.absorb(listOf(document(record, "u", 1, marked = true)), gB, store).size,
        )
        assertEquals(before + 1, store.allRecords().size)
        val group = store.projectGroups().groups.firstOrNull { it.group.id == gB }
        assertNotNull(group)
        if (group == null) return
        assertEquals("under this account's shown id", listOf(me), group.profiles.map { it.memberId })
        assertEquals("🦊", group.profiles.firstOrNull()?.emoji)
        val named = store.namedGroups().firstOrNull { it.group.id == gB }
        assertNotNull(named)
        assertEquals("their own name, not the typed one", "Al Real", named?.members?.firstOrNull { it.id == me }?.name)
        assertEquals(Avatar.Emoji("🦊"), store.memberPreviews()[gB]?.get(me))
        // A name only is a profile with no picture; a group with none has no entry.
        assertNull(store.memberPreviews()[gA])
    }

    // ---- The job: never loops, one pass per trigger ----

    /** The job's coroutines wait here until the test drains them: what runs
     *  when is the test's to say. */
    private class Queue : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() {
            while (true) (tasks.poll() ?: break).run()
        }
    }

    private val queue = Queue()
    private val sent = CopyOnWriteArrayList<List<String>>()
    private val answers = ConcurrentLinkedQueue<ProfileShare.Answer>()
    private val ensured = AtomicInteger()
    @Volatile private var nameOutcome = ProfileShare.NameOutcome.OK
    @Volatile private var during: (() -> Unit)? = null
    @Volatile private var suspends = true

    /** What each sent record carried (the final review): name, emoji, thumb. */
    private data class Shared(val name: String, val emoji: String?, val thumbHex: String?)
    private val shared = CopyOnWriteArrayList<Shared>()

    /** The test's own vault: the fakes answer it only, so a run of any other
     *  vault never counts here. */
    @Volatile private var ownPath = ""
    @Volatile private var ownDevice = ""

    private fun fakeTheCloud() {
        sent.clear()
        shared.clear()
        answers.clear()
        ensured.set(0)
        nameOutcome = ProfileShare.NameOutcome.OK
        during = null
        suspends = true
        ProfileShare.scope = CoroutineScope(SupervisorJob() + queue)
        ProfileShare.account = { store -> if (store.path == ownPath) "uid-test" else null }
        // Decision 40's timers never fire unless a test says so.
        ProfileShare.retryWait = { kotlinx.coroutines.awaitCancellation() }
        ProfileShare.ensure = { _, _ ->
            ensured.incrementAndGet()
            nameOutcome
        }
        ProfileShare.send = { data ->
            val records = (data["records"] as? JsonArray) ?: JsonArray(emptyList())
            val device = records.firstOrNull()?.jsonObject?.get("deviceId")?.jsonPrimitive?.content
            if (device != ownDevice) {
                ProfileShare.Answer.DONE
            } else {
                sent += records.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                for (record in records) {
                    val blob = record.jsonObject["payload"]?.jsonObject?.get("blob")?.jsonPrimitive?.content ?: continue
                    val op = runCatching { ffiDecodeGroupOp(Base64.getDecoder().decode(blob)) }.getOrNull()
                    if (op is GroupOpIn.MemberProfile) shared += Shared(op.name, op.emoji, op.thumbHex)
                }
                during?.let {
                    during = null
                    it()
                }
                // A real call suspends; a test may also run it straight through.
                if (suspends) yield()
                answers.poll() ?: ProfileShare.Answer.DONE
            }
        }
    }

    @After
    fun restoreTheCloud() {
        ProfileShare.scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        ProfileShare.send = { ProfileShare.realSend(it) }
        ProfileShare.ensure = { uid, name -> ProfileShare.realEnsure(uid, name) }
        ProfileShare.account = ProfileShare.realAccount
        ProfileShare.retryWait = { ProfileShare.realRetryWait(it) }
        AliasShare.send = { AliasShare.realSend(it) }
        PlaceShare.send = { PlaceShare.realSend(it) }
    }

    /** Also marks the vault caught up (its personal listener's first snapshot
     *  is in), as the app does once it has its own records. */
    private fun own(store: LedgerStore) {
        ownPath = store.path
        ownDevice = LedgerStore.deviceId(store)
        ProfileShare.markCaughtUp(store)
    }

    /** The fixture, with this account's name in `ledger/self` and the backfill
     *  already done (so a sync start only runs what is pending). */
    private fun jobFixture(): LedgerStore {
        val store = fixture()
        own(store)
        store.rebuildProjection()
        assertEquals("Al", store.loadSelf()?.name)
        store.rawKvSet(ProfileShare.BACKFILLED_KEY, byteArrayOf(1))
        return store
    }

    private fun request(store: LedgerStore) = ProfileShare.request(store).also { queue.drain() }

    @Test
    fun a_retry_after_a_failure_resends_the_same_records() {
        fakeTheCloud()
        val store = jobFixture()
        answers += ProfileShare.Answer.LATER
        request(store)
        assertEquals(1, sent.size)
        assertEquals("one record per place", 2, sent.firstOrNull()?.size)
        assertTrue("a failure keeps it for the next sync start", ProfileShare.isPending(store))
        ProfileShare.syncStarted(store)
        queue.drain()
        assertEquals(2, sent.size)
        if (sent.size != 2) return
        assertEquals("the same records, the same ids", sent[0], sent[1])
        assertFalse(ProfileShare.isPending(store))
        queue.drain()
        assertEquals("and nothing more", 2, sent.size)
    }

    @Test
    fun a_failed_precondition_rebuilds_once() {
        fakeTheCloud()
        val store = jobFixture()
        answers += listOf(ProfileShare.Answer.NAME_CHANGED, ProfileShare.Answer.DONE)
        request(store)
        assertEquals("the name ensured again, then one more call", 2, sent.size)
        if (sent.size != 2) return
        assertTrue("rebuilt: new records", sent[0].toSet().intersect(sent[1].toSet()).isEmpty())
        assertEquals(2, ensured.get())
        assertFalse(ProfileShare.isPending(store))
        // Refused twice: the second refusal is the answer, never a third call.
        answers += listOf(ProfileShare.Answer.NAME_CHANGED, ProfileShare.Answer.NAME_CHANGED)
        request(store)
        assertEquals(4, sent.size)
        assertFalse(ProfileShare.isPending(store))
        queue.drain()
        assertEquals(4, sent.size)
    }

    @Test
    fun more_than_fifty_places_go_in_chunks_of_fifty() {
        fakeTheCloud()
        val store = tempStore()
        val groups = (0 until 51).map { "%032x".format(0xA000 + it) }
        store.appendGroupOps(
            listOf<GroupOpIn>(GroupOpIn.You(me, "Al")) +
                groups.map { GroupOpIn.Create(it, "G", "USD", members(me to "Al", bo to "Bo")) }, Placing.NONE,
        )
        store.rebuildProjection()
        own(store)
        store.rawKvSet(ProfileShare.BACKFILLED_KEY, byteArrayOf(1))
        request(store)
        assertEquals(listOf(50, 1), sent.map { it.size })
        assertEquals(51, sent.flatten().toSet().size)
        assertFalse(ProfileShare.isPending(store))
    }

    /** The loop the review found: a share writes new marked records, they come
     *  back through the group's listener, and that must never share again, even
     *  while a joined group has not arrived (it never may: deleted). */
    @Test
    fun the_share_never_runs_again_on_its_own_returning_records() {
        fakeTheCloud()
        val store = jobFixture()
        // Joined D (claimed placeY); D's create is here but the addMember that
        // makes placeY has not arrived (and may never: D is deleted).
        val timsPhone = tempStore()
        timsPhone.appendGroupOps(listOf(GroupOpIn.Create(gD, "Ski", "USD", members(tim to "Tim"))), Placing.NONE)
        store.applyRecords(timsPhone.allRecords())
        store.appendGroupOps(listOf(Invites.claim(gD, placeY, me, device)), Placing.NONE)
        store.rebuildProjection()
        assertEquals("the group is here, its place is not", listOf(gD), store.waitingGroups().map { it.id })
        ProfileShare.joined(gD, store)
        queue.drain()
        assertEquals(1, sent.size)
        assertEquals(setOf(gD), ProfileShare.waiting(store))
        assertFalse("every place found was shared to, whatever is waiting", ProfileShare.isPending(store))
        // Build what was sent (the same path) to feed it back marked.
        val places = ProfileShare.places(store.projectGroups(), emptySet(), emptyMap())
        val copies = ProfileShare.records(store, places, "Al", null, null)
        val byGroup = copies.withIndex().groupBy({ it.value.groupId ?: "" }) { (i, record) ->
            document(record, "uid-test", 100L + i, marked = true)
        }
        for ((group, documents) in byGroup) {
            val fresh = CloudRecords.absorb(documents, group, store)
            assertNull("nothing waiting arrives", ProfileShare.afterReceive(group, fresh, store, fromCache = false))
        }
        assertEquals("they came in", 1, store.projectGroups().groups.firstOrNull { it.group.id == gA }?.profiles?.size)
        assertNull("a group that is not waiting triggers nothing", ProfileShare.arrived(gA, store))
        queue.drain()
        assertEquals("no share on its own returning records", 1, sent.size)
        assertEquals(setOf(gD), ProfileShare.waiting(store))
    }

    @Test
    fun a_waiting_group_triggers_one_share_when_it_arrives() {
        fakeTheCloud()
        val store = jobFixture()
        store.appendGroupOps(listOf(Invites.claim(gD, placeY, me, device)), Placing.NONE)
        ProfileShare.joined(gD, store)
        queue.drain()
        assertEquals(1, sent.size)
        assertEquals(setOf(gD), ProfileShare.waiting(store))
        // Tim's phone writes the create that names placeY (a real record, its own device).
        val timsPhone = tempStore()
        timsPhone.appendGroupOps(
            listOf(GroupOpIn.Create(gD, "Ski", "USD", members(tim to "Tim", placeY to "Al on Tim's list"))), Placing.NONE,
        )
        val create = timsPhone.allRecords().firstOrNull { it.groupId == gD }
        assertNotNull(create)
        if (create == null) return
        val before = store.allRecords().size
        val fresh = CloudRecords.absorb(listOf(document(create, "tim", 50, marked = false)), gD, store)
        val job = ProfileShare.afterReceive(gD, fresh, store, fromCache = false)
        assertNotNull("the arrival asks for one share", job)
        assertEquals("the create came in", before + 1, store.allRecords().size)
        assertTrue(
            "out of the set at once, before the share runs: a second arrival cannot ask again",
            ProfileShare.waiting(store).isEmpty(),
        )
        assertTrue(
            "the place is here now",
            ProfileShare.places(store.projectGroups(), emptySet(), emptyMap()).any { it.groupId == gD },
        )
        queue.drain()
        assertEquals("exactly one share when it arrives", 2, sent.size)
        assertEquals("to every place, D's now among them", 3, sent.lastOrNull()?.size)
        assertTrue(ProfileShare.waiting(store).isEmpty())
        assertFalse(ProfileShare.isPending(store))
        // D's later records (its own profile coming back among them) trigger nothing.
        timsPhone.appendGroupOps(listOf(GroupOpIn.RenameMember(gD, tim, "Timo")), Placing.NONE)
        val more = timsPhone.allRecords().lastOrNull { it.groupId == gD && it != create }
        assertNotNull(more)
        if (more == null) return
        val count = store.allRecords().size
        val later = CloudRecords.absorb(listOf(document(more, "tim", 60, marked = false)), gD, store)
        assertEquals("it came in", count + 1, store.allRecords().size)
        assertNull(ProfileShare.afterReceive(gD, later, store, fromCache = false))
        queue.drain()
        assertEquals(2, sent.size)
    }

    @Test
    fun a_waiting_group_that_is_deleted_leaves_the_set() {
        fakeTheCloud()
        val store = jobFixture()
        val gE = "e".repeat(32)
        val gF = "f".repeat(32)
        for (g in listOf(gD, gE, gF)) {
            ProfileShare.joined(g, store)
            queue.drain()
        }
        assertEquals(setOf(gD, gE, gF), ProfileShare.waiting(store))
        store.markReadOnly(gD, ReadOnlyDTO("deleted", "Tim", 1_000))
        assertEquals("deleted", setOf(gE, gF), ProfileShare.waiting(store))
        store.markReadOnly(gE, ReadOnlyDTO("removed", "Tim", 1_000))
        assertEquals("removed", setOf(gF), ProfileShare.waiting(store))
        store.forgetGroup(gF)
        assertTrue("forgotten on this phone", ProfileShare.waiting(store).isEmpty())
    }

    @Test
    fun a_trigger_during_a_run_causes_exactly_one_more_pass() {
        fakeTheCloud()
        for (suspending in listOf(true, false)) {
            sent.clear()
            suspends = suspending
            val store = jobFixture()
            // Two triggers while the first call is out: one more pass, not two.
            during = {
                ProfileShare.request(store)
                ProfileShare.request(store)
            }
            request(store)
            queue.drain()
            assertEquals("suspending: $suspending", 2, sent.size)
            assertFalse(ProfileShare.isPending(store))
        }
    }

    @Test
    fun a_refused_name_keeps_the_share_pending_without_a_retry() {
        fakeTheCloud()
        val store = jobFixture()
        nameOutcome = ProfileShare.NameOutcome.REFUSED
        request(store)
        queue.drain()
        assertEquals("no immediate retry", 1, ensured.get())
        assertEquals(0, sent.size)
        assertTrue(ProfileShare.isPending(store))
    }

    /** D-227 (look F3): a share left for later because the phone was offline
     *  (the name's server read failed) runs again when the personal listener
     *  hears the server after speaking from the cache, not at the next sync
     *  start; a server snapshot with no cache one before it shares nothing. */
    @Test
    fun a_share_left_for_later_runs_when_the_personal_listener_hears_the_server_again() {
        fakeTheCloud()
        val store = jobFixture()
        nameOutcome = ProfileShare.NameOutcome.LATER
        request(store)
        assertEquals(0, sent.size)
        assertTrue("offline: it waits", ProfileShare.isPending(store))
        nameOutcome = ProfileShare.NameOutcome.OK
        assertNull("a server snapshot that is no reconnect", ProfileShare.afterReceive(null, emptyList(), store, fromCache = false))
        queue.drain()
        assertEquals(0, sent.size)
        assertNull("the listener speaks from the cache", ProfileShare.afterReceive(null, emptyList(), store, fromCache = true))
        val back = ProfileShare.afterReceive(null, emptyList(), store, fromCache = false)
        assertNotNull("the server again: the share runs", back)
        queue.drain()
        assertEquals(1, sent.size)
        assertFalse(ProfileShare.isPending(store))
        assertNull("once", ProfileShare.afterReceive(null, emptyList(), store, fromCache = false))
        // A new listener (listening) forgets the cache snapshot: its first server one is the sync start.
        assertNull(ProfileShare.afterReceive(null, emptyList(), store, fromCache = true))
        ProfileShare.listening(store)
        ProfileShare.markCaughtUp(store)
        assertNull(ProfileShare.afterReceive(null, emptyList(), store, fromCache = false))
        queue.drain()
        assertEquals("nothing pending, nothing sent", 1, sent.size)
    }

    /** D-227 follow-up C (decision 40, re-look R2): a share whose call failed
     *  is tried again on decision 38's schedule (`RoleAsking.retryDelay`: 15,
     *  60, 300 s, then the next sync start), cancelled by a successful share
     *  or a sign-out (`listening`). A name that waits is no failed call. iOS's
     *  twin: ProfileSyncTests' testAFailedShareCallIsRetriedAfter15Then60Then300Seconds. */
    @Test
    fun a_failed_share_call_is_retried_after_15_then_60_then_300_seconds() {
        fakeTheCloud()
        val store = jobFixture()
        val waited = CopyOnWriteArrayList<Long>()
        ProfileShare.retryWait = { waited += it }
        // A name that waits is no failed call: no timer.
        nameOutcome = ProfileShare.NameOutcome.LATER
        request(store)
        assertEquals(emptyList<Long>(), waited)
        assertEquals(0, sent.size)
        nameOutcome = ProfileShare.NameOutcome.OK
        // A failed call, then its 15 s retry answers.
        answers += ProfileShare.Answer.LATER
        request(store)
        assertEquals(listOf(15L), waited)
        assertEquals("the call and its retry", 2, sent.size)
        assertFalse("the retry shared", ProfileShare.isPending(store))
        // The success started the count again: three timed retries, then nothing until the next sync start.
        waited.clear()
        repeat(4) { answers += ProfileShare.Answer.LATER }
        request(store)
        assertEquals("three timed retries", listOf(15L, 60L, 300L), waited)
        assertEquals("the call and three more", 6, sent.size)
        assertTrue(ProfileShare.isPending(store))
        waited.clear()
        answers += ProfileShare.Answer.LATER
        request(store)
        assertEquals("the three are spent: the next sync start asks", emptyList<Long>(), waited)
        assertEquals(7, sent.size)
        // A new listener starts the count again, and a sign-out during the wait cancels the retry (a listener caught
        // up again before the timer fires does not bring it back).
        ProfileShare.listening(store)
        ProfileShare.markCaughtUp(store)
        ProfileShare.retryWait = {
            waited += it
            ProfileShare.listening(store)
            ProfileShare.markCaughtUp(store)
        }
        answers += ProfileShare.Answer.LATER
        request(store)
        assertEquals(listOf(15L), waited)
        assertEquals("no call after the sign-out", 8, sent.size)
        assertTrue(ProfileShare.isPending(store))
    }

    @Test
    fun without_an_account_nothing_is_sent_and_the_share_waits() {
        fakeTheCloud()
        val store = jobFixture()
        ProfileShare.account = { null }
        request(store)
        queue.drain()
        assertEquals(0, ensured.get())
        assertEquals(0, sent.size)
        assertTrue("it waits for a sign-in", ProfileShare.isPending(store))
    }

    @Test
    fun a_failed_account_read_never_crashes_and_keeps_the_share_pending() {
        fakeTheCloud()
        // An exception that leaves the job would reach the handler here (on the
        // app, the thread's handler, which is a crash).
        val escaped = CopyOnWriteArrayList<Throwable>()
        ProfileShare.scope = CoroutineScope(SupervisorJob() + queue + CoroutineExceptionHandler { _, e -> escaped += e })
        val store = jobFixture()
        // The vault will not open while the account is read: no crash, no send.
        ProfileShare.account = { throw IllegalStateException("the vault will not open") }
        request(store)
        queue.drain()
        assertTrue("nothing escaped the job", escaped.isEmpty())
        assertEquals(0, ensured.get())
        assertEquals(0, sent.size)
        assertTrue("pending stays set for the next sync start", ProfileShare.isPending(store))
        // The next sync start, with the vault readable, sends what waited.
        ProfileShare.account = { s -> if (s.path == ownPath) "uid-test" else null }
        ProfileShare.syncStarted(store)
        queue.drain()
        assertTrue("nothing escaped the retry", escaped.isEmpty())
        assertEquals(1, sent.size)
        assertFalse(ProfileShare.isPending(store))
    }

    @Test
    fun a_name_is_counted_as_the_rules_count_it() {
        assertEquals("Al", ProfileShare.shareable(" Al "))
        assertNull(ProfileShare.shareable("  "))
        assertNull(ProfileShare.shareable(null))
        assertNotNull(ProfileShare.shareable("x".repeat(60)))
        assertNull(ProfileShare.shareable("x".repeat(61)))
        // An emoji is two UTF-16 units: 30 fit, 31 do not.
        assertNotNull(ProfileShare.shareable("🦊".repeat(30)))
        assertNull(ProfileShare.shareable("🦊".repeat(31)))
        // The account's name is cut at a whole character within 60 units.
        assertEquals("🦊".repeat(30), CloudProfile.cloudName("🦊".repeat(40)))
        assertEquals(59, CloudProfile.cloudName("x" + "🦊".repeat(40))?.length)
        assertEquals("x".repeat(60), CloudProfile.cloudName("x".repeat(70)))
        // The rules refusing the name is not "offline".
        assertEquals(ProfileShare.NameOutcome.REFUSED, ProfileShare.nameOutcome("PERMISSION_DENIED"))
        assertEquals(ProfileShare.NameOutcome.LATER, ProfileShare.nameOutcome("UNAVAILABLE"))
        assertEquals(ProfileShare.NameOutcome.LATER, ProfileShare.nameOutcome(null as String?))
        assertEquals(ProfileShare.NameOutcome.LATER, ProfileShare.nameOutcome(CloudProfile.NoProfileException()))
    }

    // ---- The export ----

    @Test
    fun an_export_never_fails_because_the_profile_cannot_be_projected() {
        assertEquals(
            "the kv copy stands in",
            Avatar.Emoji("🐼"), LedgerStore.exportedPicture({ throw IllegalStateException("broken") }, Avatar.Emoji("🐼")),
        )
        assertEquals(
            "the records win",
            Avatar.Emoji("🦊"), LedgerStore.exportedPicture({ GroupPictureOut("🦊", null) }, Avatar.Emoji("🐼")),
        )
        assertEquals("not migrated yet: kv", Avatar.Emoji("🐼"), LedgerStore.exportedPicture({ null }, Avatar.Emoji("🐼")))
    }

    @Test
    fun an_export_carries_the_profile_and_the_import_brings_the_picture_back() {
        val a = tempStore()
        assertNull("no name and no picture: no profile", a.snapshot().profile)
        a.setYourName("Al")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 4, 5, 6)
        a.saveAvatar(Avatar.Photo(jpeg))
        val profile = a.snapshot().profile
        assertEquals(PortProfile("Al", null, HexCodec.encode(jpeg)), profile)
        val text = ffiExportLedgerJson(a.snapshot())
        val back = ffiImportLedgerJson(text).profile
        assertEquals("through the file and back", profile, back)

        // A phone with no picture takes it, as a record.
        val fresh = tempStore()
        assertTrue(fresh.importProfile(back))
        assertEquals(Avatar.Photo(jpeg), fresh.loadAvatar())
        assertNull("the name is the phone's own", fresh.loadSelf()?.name)
        // A phone that has one keeps it, and writes nothing.
        val has = tempStore()
        has.saveAvatar(Avatar.Emoji("🦊"))
        val count = has.allRecords().size
        assertFalse(has.importProfile(back))
        assertEquals(Avatar.Emoji("🦊"), has.loadAvatar())
        assertEquals(count, has.allRecords().size)
        // A picture kept from before pictures were records counts as one.
        val kept = tempStore()
        kept.rawKvSet(AVATAR_KEY, Avatar.Emoji("🐼").encoded())
        assertFalse(kept.importProfile(back))
        assertEquals(Avatar.Emoji("🐼"), kept.loadAvatar())
        // Nothing to bring: nothing written. A name alone brings nothing.
        assertFalse(tempStore().importProfile(null))
        assertFalse(tempStore().importProfile(PortProfile("Al", null, null)))
    }

    // ---- D-211 Task 7: the screens' pure rules ----

    @Test
    fun rename_is_absent_for_a_joined_person() {
        val previews = mapOf(gA to mapOf(tim to Avatar.Emoji("🦊"), bo to Avatar.None))
        // A person with a profile here: absent, whether they have a picture or only a name.
        assertFalse(MemberPreviews.mayRename(previews, gA, tim))
        assertFalse(MemberPreviews.mayRename(previews, gA, bo))
        // Someone who never joined stays renamable, and so does a person's id in another group.
        assertTrue(MemberPreviews.mayRename(previews, gA, cy))
        assertTrue(MemberPreviews.mayRename(previews, gB, tim))
        assertTrue(MemberPreviews.mayRename(emptyMap(), gA, tim))
    }

    @Test
    fun a_preview_is_the_groups_own_else_the_first() {
        val fox = Avatar.Emoji("🦊")
        val panda = Avatar.Emoji("🐼")
        val previews = mapOf(
            gA to mapOf(tim to Avatar.None), // a name only: no picture to draw
            gB to mapOf(placeX to fox),
            gC to mapOf(bo to panda),
        )
        val candidates = listOf(gA to tim, gB to placeX, gC to bo)
        // The group the card is about, when it has a picture.
        assertEquals(panda, MemberPreviews.pick(previews, candidates, gC))
        assertEquals(fox, MemberPreviews.pick(previews, candidates, gB))
        // Else the first group, in order, that has one (gA has none).
        assertEquals(fox, MemberPreviews.pick(previews, candidates, gA))
        assertEquals(fox, MemberPreviews.pick(previews, candidates, null))
        assertEquals(fox, MemberPreviews.pick(previews, candidates, "none"))
        // The order is the caller's: the same people, the other way round.
        assertEquals(panda, MemberPreviews.pick(previews, candidates.reversed(), null))
        // No group has a picture, or no candidates: nothing to draw.
        assertEquals(Avatar.None, MemberPreviews.pick(previews, listOf(gA to tim), gA))
        assertEquals(Avatar.None, MemberPreviews.pick(previews, emptyList(), gA))
        assertEquals(Avatar.None, MemberPreviews.pick(emptyMap(), candidates, null))
    }

    @Test
    fun the_footnotes_switch_on_the_account() {
        assertEquals(
            listOf(
                "Your picture syncs to your phones. Your groups see a small preview.",
                "Earlier pictures stay in your private history until you delete your account.",
            ),
            ProfileCopy.footnotes(true),
        )
        assertEquals(listOf("Your name and picture stay on this phone."), ProfileCopy.footnotes(false))
        assertEquals("Name and picture", ProfileCopy.SECTION_TITLE)
        assertEquals("Done", ProfileCopy.DONE)
        // No account, no cloud words (GUARANTEES #5).
        val offline = ProfileCopy.footnotes(false).joinToString(" ").lowercase()
        for (word in listOf("sync", "group", "account", "cloud")) assertFalse(word, offline.contains(word))
    }

    @Test
    fun a_recent_person_carries_the_preview_of_the_first_group_that_has_one() {
        fun g(id: String, name: String, vararg members: Pair<String, String>) = uniffi.skipti_core.NamedGroupWire(
            uniffi.skipti_core.GroupWire(id, name, "INR", members.map { it.first }),
            members.first().first,
            members.map { uniffi.skipti_core.MemberWire(it.first, it.second) },
        )
        fun c(member: String, you: String) = uniffi.skipti_core.ClaimOut(member, you, emptyList())
        val groups = listOf(
            g("goa", "Goa", "gy" to "Me", "gr" to "Rahul"),
            g("flat", "Flat", "fy" to "Me", "fr" to "Rahul"),
            g("trip", "Trip", "ty" to "Me"),
        )
        val claims = mapOf("goa" to listOf(c("gr", "p1")), "flat" to listOf(c("fr", "p1")))
        // Goa has no picture for Rahul (a name only); Flat has one.
        val previews = mapOf("goa" to mapOf("gr" to Avatar.None), "flat" to mapOf("fr" to Avatar.Emoji("🦊")))
        val rahul = Recents.people(groups, claims, "trip", previews = previews).firstOrNull()
        assertNotNull(rahul)
        assertEquals("Rahul", rahul?.name)
        assertEquals(Avatar.Emoji("🦊"), rahul?.preview)
        // No previews: initials, as before.
        assertEquals(Avatar.None, Recents.people(groups, claims, "trip").firstOrNull()?.preview)
    }

    @Test
    fun a_failed_preview_read_keeps_the_people_and_the_last_previews() {
        val last = mapOf(gA to mapOf(tim to Avatar.Emoji("🦊")))
        // A read that throws: the last good previews stay, and the caller is never handed an error.
        assertEquals(last, MemberPreviews.reloaded(last) { throw IllegalStateException("vault") })
        // A read that works replaces them, an empty answer included (a cleared picture clears).
        val fresh = mapOf(gA to mapOf(tim to Avatar.Emoji("🐼")))
        assertEquals(fresh, MemberPreviews.reloaded(last) { fresh })
        assertEquals(emptyMap<String, Map<String, Avatar>>(), MemberPreviews.reloaded(last) { emptyMap() })
        // Nothing known yet and a failed read: nothing, so initials.
        assertEquals(
            emptyMap<String, Map<String, Avatar>>(),
            MemberPreviews.reloaded(emptyMap()) { throw IllegalStateException("vault") },
        )
        // A cancelled load is not swallowed.
        try {
            MemberPreviews.reloaded(last) { throw kotlinx.coroutines.CancellationException("gone") }
            fail("a cancellation must pass through")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }

    @Test
    fun the_picture_viewer_is_only_for_a_photo_that_decodes() {
        // Bitmap needs a device, so the rule is tested through its seam with the JVM's
        // own decoder (ImageIO); the Bitmap adapter is seen on the emulator.
        val viaImageIO: (ByteArray) -> Boolean = { ImageIO.read(ByteArrayInputStream(it)) != null }
        val real = jpeg(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).also { it.graphics.run { color = Color.RED; fillRect(0, 0, 8, 8) } }, 80)
        assertTrue(PictureViewing.canView(Avatar.Photo(real), viaImageIO))
        // A photo whose bytes are not an image behaves like no picture.
        assertFalse(PictureViewing.canView(Avatar.Photo(byteArrayOf(1, 2, 3)), viaImageIO))
        assertFalse(PictureViewing.canView(Avatar.Emoji("🦊"), viaImageIO))
        assertFalse(PictureViewing.canView(Avatar.None, viaImageIO))
        // The decoder is never asked about an emoji or no picture.
        var asked = 0
        PictureViewing.canView(Avatar.Emoji("🦊")) { asked++; true }
        PictureViewing.canView(Avatar.None) { asked++; true }
        assertEquals(0, asked)
    }

    /** D-211 fix 5: an open viewer survives a configuration change. While the
     *  picture is still being read the placeholder is not a photo, and that
     *  must not close a restored viewer; it closes only when a picture that
     *  HAS been read is not a photo (it was removed or replaced by an emoji). */
    @Test
    fun the_viewer_closes_only_once_the_picture_has_been_read_and_is_no_photo() {
        assertFalse("not read yet: the placeholder is no verdict", PictureViewing.shouldClose(viewable = false, pictureRead = false))
        assertTrue("read, and no longer a photo", PictureViewing.shouldClose(viewable = false, pictureRead = true))
        assertFalse("read, still a photo", PictureViewing.shouldClose(viewable = true, pictureRead = true))
        assertFalse(PictureViewing.shouldClose(viewable = true, pictureRead = false))
    }

    // ---- The final review's fixes ----

    private fun isAvatar(record: RecordWire) =
        runCatching { ffiDecodeGroupOp(record.blob) }.getOrNull() is GroupOpIn.Avatar

    /** I1(a): a phone updating to this build has its picture in kv only. The
     *  backfill's pass must share that picture, not "none". */
    @Test
    fun the_share_migrates_a_kept_picture_before_it_reads_the_picture() {
        fakeTheCloud()
        val store = jobFixture()
        store.rawKvSet(LedgerStore.AVATAR_RECORD_KEY, ByteArray(0))
        store.rawKvSet(ProfileShare.BACKFILLED_KEY, ByteArray(0))
        store.rawKvSet(AVATAR_KEY, Avatar.Emoji("🦊").encoded())
        assertNull("not migrated yet", store.projectGroups().avatar)
        ProfileShare.syncStarted(store)
        queue.drain()
        assertEquals(1, sent.size)
        assertEquals("the kept picture, in every place", listOf("🦊", "🦊"), shared.map { it.emoji })
        assertEquals("and it is a record now", "🦊", store.projectGroups().avatar?.emoji)
    }

    /** I1(a): a migration that writes a record (from a rebuild, outside the
     *  job) asks for a share; one that writes nothing asks for nothing. */
    @Test
    fun a_migration_that_writes_a_record_asks_for_a_share() {
        fakeTheCloud()
        val store = jobFixture()
        store.rawKvSet(LedgerStore.AVATAR_RECORD_KEY, ByteArray(0))
        store.rawKvSet(AVATAR_KEY, Avatar.Emoji("🦊").encoded())
        assertFalse(ProfileShare.isPending(store))
        store.rebuildGroups()
        queue.drain()
        assertEquals("the migration asked for a share", 1, sent.size)
        assertEquals(listOf("🦊", "🦊"), shared.map { it.emoji })
        assertFalse(ProfileShare.isPending(store))
        store.rebuildGroups()
        queue.drain()
        assertEquals("nothing migrated: nothing asked", 1, sent.size)
    }

    /** I1(b): a phone that was offline shares only after the personal
     *  listener's first snapshot is in: its name was changed on another phone
     *  meanwhile, and a share built before would carry the old one with a
     *  fresh stamp that beats the right one in every group. [CloudSync.receive]
     *  is absorb then [ProfileShare.afterReceive]; it touches the Main
     *  dispatcher, so the test makes the same two calls. */
    @Test
    fun the_share_waits_for_the_first_personal_snapshot() {
        fakeTheCloud()
        val store = jobFixture()
        ProfileShare.listening(store)
        request(store)
        assertEquals("nothing before the personal snapshot", 0, sent.size)
        assertTrue(ProfileShare.isPending(store))
        // Another phone of this account renamed it while this one was offline.
        val otherPhone = tempStore()
        otherPhone.appendGroupOps(listOf(GroupOpIn.You(me, "Alfie")), Placing.NONE, System.currentTimeMillis() + 60_000)
        val rename = otherPhone.allRecords().firstOrNull()
        assertNotNull(rename)
        if (rename == null) return
        val fresh = CloudRecords.absorb(listOf(document(rename, "uid-test", 40, marked = false)), null, store)
        assertEquals("the snapshot is in", "Alfie", store.loadSelf()?.name)
        ProfileShare.afterReceive(null, fresh, store, fromCache = false)
        queue.drain()
        assertEquals("one share, once caught up", 1, sent.size)
        assertEquals("with the name from the snapshot", listOf("Alfie", "Alfie"), shared.map { it.name })
        assertFalse(ProfileShare.isPending(store))
        // A later personal snapshot starts nothing.
        ProfileShare.afterReceive(null, CloudRecords.absorb(emptyList(), null, store), store, fromCache = false)
        queue.drain()
        assertEquals(1, sent.size)
    }

    /** I1(c): the migration on a second phone, before that phone has the
     *  account's newer picture, never undoes it: it is stamped low. */
    @Test
    fun a_migrated_picture_never_beats_a_newer_one_from_another_phone() {
        val elsewhere = tempStore()
        elsewhere.appendGroupOps(listOf(GroupOpIn.Avatar("🐼", null)), Placing.NONE, System.currentTimeMillis() - 86_400_000)
        val store = tempStore()
        store.rawKvSet(AVATAR_KEY, Avatar.Emoji("🦊").encoded())
        assertTrue("no picture record here yet: it migrates", store.migrateAvatarOnce())
        val migrated = store.allRecords().firstOrNull { isAvatar(it) }
        assertNotNull(migrated)
        if (migrated == null) return
        assertEquals(1uL, migrated.hlcMillis)
        assertEquals(0u, migrated.hlcCounter)
        assertEquals(LedgerStore.deviceId(store), migrated.hlcDeviceId)
        assertEquals("alone, it is the picture", "🦊", store.projectGroups().avatar?.emoji)
        store.applyRecords(elsewhere.allRecords())
        store.rebuildProjection()
        assertEquals("the picture set elsewhere stands", Avatar.Emoji("🐼"), store.loadAvatar())
        elsewhere.applyRecords(listOf(migrated))
        elsewhere.rebuildProjection()
        assertEquals("and the migrated one changes nothing there", Avatar.Emoji("🐼"), elsewhere.loadAvatar())
    }

    /** I2 (founder decision 5): at the first sign-in only the latest picture
     *  goes up; the earlier ones are marked uploaded without being sent. */
    @Test
    fun only_the_latest_picture_goes_up_at_the_first_sign_in() {
        val store = tempStore()
        store.setYourName("Al")
        for (emoji in listOf("🦊", "🐼", "🌿")) store.saveAvatar(Avatar.Emoji(emoji))
        val all = store.allRecords()
        fun avatarIds(emoji: String): Set<String> = all.filter {
            (runCatching { ffiDecodeGroupOp(it.blob) }.getOrNull() as? GroupOpIn.Avatar)?.emoji == emoji
        }.map { CloudRecords.documentId(it) }.toSet()
        val older = avatarIds("🦊") + avatarIds("🐼")
        assertEquals(2, older.size)
        val out = CloudRecords.outgoing(all, emptySet(), emptySet(), emptySet())
        assertEquals("the earlier pictures are marked, never sent", older, out.superseded)
        val sentIds = out.send.map { CloudRecords.documentId(it) }.toSet()
        assertTrue("the latest goes up", sentIds.containsAll(avatarIds("🌿")))
        assertTrue(sentIds.intersect(older).isEmpty())
        assertEquals("everything else goes up as before", all.size - 2, sentIds.size)
        // The latest already up: an earlier one still waiting never goes.
        val again = CloudRecords.outgoing(all, avatarIds("🌿"), emptySet(), emptySet())
        assertEquals(older, again.superseded)
        assertTrue(again.send.map { CloudRecords.documentId(it) }.toSet().intersect(older).isEmpty())
        // One picture: it goes up.
        val one = tempStore()
        one.saveAvatar(Avatar.Emoji("🦊"))
        assertEquals(emptySet<String>(), CloudRecords.outgoing(one.allRecords(), emptySet(), emptySet(), emptySet()).superseded)
    }

    /** Fix 3 item 2 (M-A): the first personal snapshot opens the share only
     *  when the server sent it. The cache is memory only, so a cache snapshot
     *  is empty and says nothing of the account's own records. */
    @Test
    fun a_cache_snapshot_never_opens_the_share() {
        fakeTheCloud()
        val store = jobFixture()
        ProfileShare.listening(store)
        request(store)
        assertEquals(0, sent.size)
        val none = CloudRecords.absorb(emptyList(), null, store)
        ProfileShare.afterReceive(null, none, store, fromCache = true)
        queue.drain()
        assertEquals("a cache snapshot opens nothing", 0, sent.size)
        assertTrue(ProfileShare.isPending(store))
        ProfileShare.afterReceive(null, none, store, fromCache = true)
        queue.drain()
        assertEquals("nor a second one", 0, sent.size)
        ProfileShare.afterReceive(null, none, store, fromCache = false)
        queue.drain()
        assertEquals("the server's snapshot opens it", 1, sent.size)
        assertFalse(ProfileShare.isPending(store))
    }

    /** Fix 3 item 3 (M-B): a phone never writes a profile: one found in the
     *  vault as pending (its uploaded mark lost) is marked, never sent. */
    @Test
    fun outgoing_never_sends_a_member_profile() {
        val store = tempStore()
        store.setYourName("Al")
        store.saveAvatar(Avatar.Emoji("🦊"))
        val profile = ProfileShare.records(store, listOf(ProfileShare.Place(gA, me)), "Al", "🦊", null).firstOrNull()
        assertNotNull(profile)
        if (profile == null) return
        assertTrue(CloudRecords.profileType(profile))
        val all = store.allRecords() + profile
        assertTrue("pending would take it (the test is not vacuous)",
            CloudRecords.pending(all, emptySet()).any { CloudRecords.profileType(it) })
        val out = CloudRecords.outgoing(all, emptySet(), emptySet(), emptySet())
        assertFalse("a profile is never sent", out.send.any { CloudRecords.profileType(it) })
        assertTrue("it is marked uploaded instead", CloudRecords.documentId(profile) in out.superseded)
        assertTrue("everything else still goes", out.send.isNotEmpty())
        // Already marked: nothing to mark again.
        val again = CloudRecords.outgoing(all, setOf(CloudRecords.documentId(profile)), emptySet(), emptySet())
        assertFalse(again.send.any { CloudRecords.profileType(it) })
        assertFalse(CloudRecords.documentId(profile) in again.superseded)
    }

    /** Fix 3 item 4: the migration can run twice at once (the share's pass and
     *  the rebuild) and write the same record; the vault takes the second as a
     *  no-op (core: INSERT OR REPLACE on the unique HLC identity). */
    @Test
    fun putting_an_identical_record_twice_is_a_no_op() {
        val store = tempStore()
        store.rawKvSet(AVATAR_KEY, Avatar.Emoji("🦊").encoded())
        assertTrue(store.migrateAvatarOnce())
        val record = store.allRecords().firstOrNull { isAvatar(it) }
        assertNotNull(record)
        if (record == null) return
        val before = store.allRecords().map { CloudRecords.documentId(it) }
        assertEquals("nothing new the second time", emptyList<RecordWire>(), store.applyRecords(listOf(record)))
        assertEquals("no duplicate", before, store.allRecords().map { CloudRecords.documentId(it) })
        assertEquals("and the picture is still the one", Avatar.Emoji("🦊"), store.loadAvatar())
    }

    /** M1 (GUARANTEES #4): a kept picture core would refuse (an old photo over
     *  64,000 bytes, not migrated yet) never fails the export: it goes without
     *  the picture. */
    @Test
    fun an_export_leaves_out_a_picture_core_would_refuse() {
        val a = tempStore()
        a.setYourName("Al")
        a.rawKvSet(LedgerStore.AVATAR_RECORD_KEY, ByteArray(0))
        val big = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(70_000)
        a.rawKvSet(AVATAR_KEY, Avatar.Photo(big).encoded())
        val snapshot = a.snapshot()
        try {
            ffiExportLedgerJson(snapshot)
        } catch (e: Exception) {
            fail("core refuses a file with that picture: $e")
        }
        assertEquals(PortProfile("Al", null, null), snapshot.profile)
        // One core takes still goes out.
        a.rawKvSet(AVATAR_KEY, Avatar.Emoji("🦊").encoded())
        assertEquals(PortProfile("Al", "🦊", null), a.snapshot().profile)
    }

    /** M4: a name the vault cannot read is not "no name": the share waits. */
    @Test
    fun a_name_that_cannot_be_read_keeps_the_share_pending() {
        fakeTheCloud()
        val store = jobFixture()
        store.rawKvSet(LedgerStore.SELF_KEY, "not json".toByteArray(Charsets.UTF_8))
        request(store)
        assertEquals(0, ensured.get())
        assertEquals(0, sent.size)
        assertTrue("a failed read waits for the next try", ProfileShare.isPending(store))
        // No name at all: nothing to share, and the share is done.
        val bare = tempStore()
        own(bare)
        bare.rawKvSet(ProfileShare.BACKFILLED_KEY, byteArrayOf(1))
        request(bare)
        assertEquals(0, sent.size)
        assertFalse(ProfileShare.isPending(bare))
    }

    // ---- D-229 (Phase B): a server-verified alias ----
    // FounderVault (GroupRecordsTest.kt): y = hex(1), x = hex(2), c = hex(3),
    // Old = hex(9) (place x, "you" y unreadable there), New = hex(8).

    private val vy = FounderVault.hex(1)
    private val vx = FounderVault.hex(2)
    private val vOld = FounderVault.hex(9)
    private val vNew = FounderVault.hex(8)

    /** Test 9: a `placeAlias` comes in only with the server's mark, only from
     *  its own group's log, never from the account's own log; this phone's
     *  own comes back marked. A push never sends one. */
    @Test
    fun d229_a_place_alias_comes_in_only_marked() {
        val op = GroupOpIn.PlaceAlias(vOld, vx, vy)
        val theirs = rec(op, vOld)
        fun refusal(r: RecordWire, alias: Boolean = false, profile: Boolean = false) =
            CloudRecords.refusal(r, vOld, device, profile = profile, alias = alias)
        assertEquals("a phone wrote it, not the Function", CloudRecords.Refusal.UNVERIFIED_ALIAS, refusal(theirs))
        assertNull(refusal(theirs, alias = true))
        assertEquals("a profile's mark is not an alias's", CloudRecords.Refusal.UNVERIFIED_ALIAS, refusal(theirs, profile = true))
        assertEquals(
            "an alias's mark is not a profile's",
            CloudRecords.Refusal.UNVERIFIED_PROFILE, refusal(rec(profileOp(vOld, bo), vOld), alias = true),
        )
        assertEquals(
            "marked, but another group's",
            CloudRecords.Refusal.WRONG_GROUP, refusal(rec(GroupOpIn.PlaceAlias(vNew, vx, vy), vOld), alias = true),
        )
        assertEquals("never from the account's own log", CloudRecords.Refusal.WRONG_SCOPE, CloudRecords.personalRefusal(rec(op, null)))
        val mine = rec(op, vOld, device = device)
        assertNull("this phone's own, marked", refusal(mine, alias = true))
        assertEquals("this phone's own, unmarked", CloudRecords.Refusal.THIS_DEVICE, refusal(mine))

        // Through absorb, as a snapshot brings them: the mark is the document's.
        val store = tempStore()
        fun doc(r: RecordWire, marked: Boolean): Map<String, Any?> {
            val d = CloudRecords.document(r, "u").toMutableMap<String, Any?>()
            d["createdAt"] = Timestamp(1, 0)
            if (marked) d["alias"] = true
            return d
        }
        // (Built as a phone builds it: the vault takes only a real record header.)
        val built = LedgerStore.groupOpRecord(op, uniffi.skipti_core.HlcStamp(1uL, 0u), other)
        assertEquals(0, CloudRecords.absorb(listOf(doc(built, marked = false)), vOld, store).size)
        assertEquals(1, CloudRecords.absorb(listOf(doc(built, marked = true)), vOld, store).size)
        val personal = built.copy(groupId = null, hlcMillis = 2uL)
        assertEquals(0, CloudRecords.absorb(listOf(doc(personal, marked = true)), null, store).size)

        // A placeAlias in the vault (its mark lost) is never sent: only the Function writes one.
        val pending = rec(op, vOld, device = device, millis = 3uL)
        val out = CloudRecords.outgoing(listOf(pending), emptySet(), emptySet(), emptySet())
        assertEquals(emptyList<RecordWire>(), out.send)
        assertEquals(setOf(CloudRecords.documentId(pending)), out.superseded)
    }

    /** A record in [group] written under y before the place was known: the
     *  kind of record a `placeAlias` repairs. Applied raw, as a pull would. */
    private fun yRecordIn(store: LedgerStore, group: Int, millis: Long) {
        store.applyRecords(
            listOf(
                LedgerStore.expenseRecord(
                    FounderVault.expense(group, 1, listOf(1 to 600L, 3 to 600L)),
                    uniffi.skipti_core.HlcStamp(millis.toULong(), 0u), FounderVault.hex(0x50), Placing.NONE,
                ),
            ),
        )
        store.rebuildProjection()
    }

    /** Test 10: which aliases are wanted: Old's y once a record there names
     *  it; nothing without one; never an answered pair; never a read-only
     *  group; New's unreadable x is named by no record there. */
    @Test
    fun d229_alias_share_wants_only_ids_a_record_names() {
        val store = FounderVault.p(tempStore(), pClaim = 2)
        fun wanted(readOnly: Set<String> = emptySet(), answered: Set<String> = emptySet()) =
            AliasShare.wanted(store.projectGroups(), store.allRecords(), readOnly, answered)
        assertEquals("no record names y in Old", emptyList<AliasShare.Alias>(), wanted())
        yRecordIn(store, 9, 4000)
        assertEquals(listOf(AliasShare.Alias(vOld, vx, vy)), wanted())
        assertEquals("$vOld:$vy", AliasShare.Alias(vOld, vx, vy).key)
        assertEquals(emptyList<AliasShare.Alias>(), wanted(answered = setOf("$vOld:$vy")))
        assertEquals(emptyList<AliasShare.Alias>(), wanted(readOnly = setOf(vOld)))
        // Each kind of record names the ids it holds.
        val rename = LedgerStore.groupOpRecord(GroupOpIn.RenameMember(vOld, vy, "Al"), uniffi.skipti_core.HlcStamp(5000uL, 0u), FounderVault.hex(0x50))
        assertEquals(setOf(vy), AliasShare.named(listOf(rename), vOld))
        val paid = LedgerStore.settlementRecord(
            SettlementDTO(vy, FounderVault.hex(3), 600, "INR", vOld), uniffi.skipti_core.HlcStamp(5001uL, 0u),
            FounderVault.hex(0x50), Placing.NONE,
        )
        assertEquals(setOf(vy, FounderVault.hex(3)), AliasShare.named(listOf(paid), vOld))
        assertEquals("another group's record names nothing here", emptySet<String>(), AliasShare.named(listOf(paid), vNew))
    }

    /** The job: asks once per (group, id), with the record the Function
     *  checks (built here, never kept), and remembers what the reply answered;
     *  a failed call is asked again. Only groups in the cloud are asked. */
    @Test
    fun d229_alias_share_asks_once_and_remembers_the_answer() = kotlinx.coroutines.runBlocking {
        val store = FounderVault.p(tempStore(), pClaim = 2)
        yRecordIn(store, 9, 4000)
        val sent = mutableListOf<JsonObject>()
        var reply: (JsonObject) -> JsonObject = { throw CloudFunctions.Failure.Offline() }
        AliasShare.send = { data -> sent += data; reply(data) }
        assertEquals("a group not in the cloud is not asked", AliasShare.Answer.DONE, AliasShare.share(store))
        assertEquals(0, sent.size)
        CloudRecords.saveGroupsUp(store, setOf(vOld, vNew))
        assertEquals(AliasShare.Answer.LATER, AliasShare.share(store))
        assertEquals(1, sent.size)
        assertEquals("a failed call answers nothing", emptySet<String>(), AliasShare.answered(store))
        reply = { data ->
            val ids = data["records"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            JsonObject(mapOf("shared" to JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }), "skipped" to JsonArray(emptyList())))
        }
        assertEquals(AliasShare.Answer.DONE, AliasShare.share(store))
        assertEquals(2, sent.size)
        val records = sent.last()["records"]!!.jsonArray
        assertEquals(1, records.size)
        val r = records.single().jsonObject
        assertEquals("3", r["type"]!!.jsonPrimitive.content)
        assertEquals(
            GroupOpIn.PlaceAlias(vOld, vx, vy),
            ffiDecodeGroupOp(Base64.getDecoder().decode(r["payload"]!!.jsonObject["blob"]!!.jsonPrimitive.content)),
        )
        assertEquals(setOf("$vOld:$vy"), AliasShare.answered(store))
        assertTrue("built, never kept", store.allRecords().none { runCatching { ffiDecodeGroupOp(it.blob) }.getOrNull() is GroupOpIn.PlaceAlias })
        assertEquals("answered: not asked again", AliasShare.Answer.DONE, AliasShare.share(store))
        assertEquals(2, sent.size)
        // The answer to an error.
        assertEquals(AliasShare.Answer.DONE, AliasShare.answer(null))
        assertEquals(AliasShare.Answer.REFUSED, AliasShare.answer(CloudFunctions.Failure.Refused("INVALID_ARGUMENT")))
        assertEquals(AliasShare.Answer.LATER, AliasShare.answer(CloudFunctions.Failure.Refused("UNAVAILABLE")))
        assertEquals(AliasShare.Answer.LATER, AliasShare.answer(CloudFunctions.Failure.Offline()))
    }

    // ---- D-229 follow-up ----

    /** The reply that answers every record of a call as shared. */
    private val sharedReply: (JsonObject) -> JsonObject = { data ->
        val ids = data["records"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        JsonObject(mapOf("shared" to JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }), "skipped" to JsonArray(emptyList())))
    }

    /** Item 2: the daily quota (`resource-exhausted`, decision 48) is LATER:
     *  nothing is remembered, and the next push asks again. */
    @Test
    fun d229_alias_share_the_quota_is_asked_again_later() = kotlinx.coroutines.runBlocking {
        assertEquals(AliasShare.Answer.LATER, AliasShare.answer(CloudFunctions.Failure.Refused("RESOURCE_EXHAUSTED")))
        val store = FounderVault.p(tempStore(), pClaim = 2)
        yRecordIn(store, 9, 4000)
        CloudRecords.saveGroupsUp(store, setOf(vOld, vNew))
        var calls = 0
        var reply: (JsonObject) -> JsonObject = { throw CloudFunctions.Failure.Refused("RESOURCE_EXHAUSTED") }
        AliasShare.send = { data -> calls += 1; reply(data) }
        assertEquals(AliasShare.Answer.LATER, AliasShare.share(store))
        assertEquals("never answered", emptySet<String>(), AliasShare.answered(store))
        reply = sharedReply
        assertEquals(AliasShare.Answer.DONE, AliasShare.share(store))
        assertEquals("asked again", 2, calls)
        assertEquals(setOf("$vOld:$vy"), AliasShare.answered(store))
    }

    /** Item 3: a refused call (`invalid-argument`) answers its pairs for good
     *  and the job returns DONE (iOS's twin returned refused). */
    @Test
    fun d229_alias_share_a_refused_call_is_done() = kotlinx.coroutines.runBlocking {
        val store = FounderVault.p(tempStore(), pClaim = 2)
        yRecordIn(store, 9, 4000)
        CloudRecords.saveGroupsUp(store, setOf(vOld, vNew))
        var calls = 0
        AliasShare.send = { calls += 1; throw CloudFunctions.Failure.Refused("INVALID_ARGUMENT") }
        assertEquals(AliasShare.Answer.DONE, AliasShare.share(store))
        assertEquals(setOf("$vOld:$vy"), AliasShare.answered(store))
        assertEquals(AliasShare.Answer.DONE, AliasShare.share(store))
        assertEquals("answered: not asked again", 1, calls)
    }

    /** Item 5: the records are built one group at a time: a group with a bad
     *  alias (core refuses a member that is the id itself) is skipped, and
     *  every other group's still go, each record with its own alias. */
    @Test
    fun d229_alias_share_one_bad_alias_does_not_block_the_others() {
        val store = tempStore()
        store.setYourName("Al")
        val bad = AliasShare.Alias(vOld, vx, vx)
        val fine = AliasShare.Alias(vOld, vx, vy)
        val other = AliasShare.Alias(vNew, vy, vx)
        try {
            AliasShare.records(store, listOf(bad, fine, other))
            fail("all at once: none")
        } catch (_: Exception) {
        }
        val built = AliasShare.recordsByGroup(store, listOf(bad, fine, other))
        assertEquals("Old (one bad alias) is skipped, New goes", listOf(other), built.map { it.first })
        assertEquals(listOf<GroupOpIn>(GroupOpIn.PlaceAlias(vNew, vy, vx)), built.map { ffiDecodeGroupOp(it.second.blob) })
        val both = AliasShare.recordsByGroup(store, listOf(fine, other))
        assertEquals(listOf(fine, other), both.map { it.first })
        assertEquals(listOf<String?>(vOld, vNew), both.map { it.second.groupId })
    }

    /** Item 6 (T4 I1, T3 M3): a push with nothing to send still asks for the
     *  aliases (it returned before the share). Every record is marked up, so
     *  pushAll touches no cloud before the share. */
    @Test
    fun d229_a_push_with_nothing_pending_shares_the_aliases() = kotlinx.coroutines.runBlocking {
        val store = FounderVault.p(tempStore(), pClaim = 2)
        yRecordIn(store, 9, 4000)
        store.appendGroupOps(CloudRecords.ownClaims(store.projectGroups(), LedgerStore.deviceId(store)), Placing.NONE)
        CloudRecords.saveUploaded(store, store.allRecords().map(CloudRecords::documentId).toSet())
        CloudRecords.saveGroupsUp(store, setOf(vOld, vNew))
        val sent = mutableListOf<JsonObject>()
        AliasShare.send = { data -> sent += data; sharedReply(data) }
        assertEquals(0, CloudSync.pushAll("uid", store))
        assertEquals(1, sent.size)
        assertEquals(setOf("$vOld:$vy"), AliasShare.answered(store))
    }

    // ---- D-230: held places ----

    /** A held place comes in only marked, never from a personal log, never
     *  sent; this phone's own comes back marked. Twin of iOS's
     *  testAHeldPlaceComesInOnlyMarkedAndIsNeverSent. */
    @Test
    fun d230_a_held_place_comes_in_only_marked_and_is_never_sent() {
        val op = GroupOpIn.HeldPlace(vOld, vx, vy)
        val theirs = rec(op, vOld)
        fun refusal(r: RecordWire, held: Boolean = false, alias: Boolean = false) =
            CloudRecords.refusal(r, vOld, device, alias = alias, held = held)
        assertEquals("a phone wrote it, not the Function", CloudRecords.Refusal.UNVERIFIED_PLACE, refusal(theirs))
        assertNull(refusal(theirs, held = true))
        assertEquals("an alias's mark is not a held place's", CloudRecords.Refusal.UNVERIFIED_PLACE, refusal(theirs, alias = true))
        assertEquals("nor the reverse", CloudRecords.Refusal.UNVERIFIED_ALIAS, refusal(rec(GroupOpIn.PlaceAlias(vOld, vx, vy), vOld), held = true))
        assertEquals(CloudRecords.Refusal.WRONG_GROUP, refusal(rec(GroupOpIn.HeldPlace(vNew, vx, vy), vOld), held = true))
        assertEquals(CloudRecords.Refusal.WRONG_SCOPE, CloudRecords.personalRefusal(rec(op, null)))
        val mine = rec(op, vOld, device = device)
        assertNull("this phone's own, marked", refusal(mine, held = true))
        assertEquals(CloudRecords.Refusal.THIS_DEVICE, refusal(mine))
        val store = tempStore()
        store.setYourName("Al")
        val built = PlaceShare.records(store, listOf(PlaceShare.Held(vOld, vx, vy)))
        assertEquals(1, built.size)
        assertEquals(op, ffiDecodeGroupOp(built[0].blob))
        assertFalse("never put in the vault", store.allRecords().any { CloudRecords.heldType(it) })
        val all = store.allRecords() + built
        assertTrue(CloudRecords.pending(all, emptySet()).any { CloudRecords.heldType(it) })
        val out = CloudRecords.outgoing(all, emptySet(), emptySet(), emptySet())
        assertFalse("a held place is never sent", out.send.any { CloudRecords.heldType(it) })
        assertTrue(CloudRecords.documentId(built[0]) in out.superseded)
    }

    /** Which places are wanted: every group with a place that is not
     *  read-only and whose `held` does not list (place, you); an answered key
     *  is not asked again; a held place the mark brought back is not wanted.
     *  Twin of iOS's testThePlacesWantedAreTheUnmarkedOnes. */
    @Test
    fun d230_the_places_wanted_are_the_unmarked_ones() {
        val store = FounderVault.p(tempStore(), pClaim = 2)
        val p = store.projectGroups()
        val you = p.you!!.id
        val old = p.groups.first { it.group.id == vOld }
        val want = PlaceShare.wanted(p, store.allRecords(), emptySet(), emptySet())
        // A1 (second form): every claim of this account (Old's {x, you x}), then the place with "you".
        assertEquals(listOf("$vOld:$vx:$vx", "$vOld:$vx:$vy", "$vNew:$vy:$vy"), want.map { it.key })
        assertEquals(vx, old.place)
        assertTrue(PlaceShare.Held(vOld, old.place!!, you) in want)
        assertEquals(emptyList<PlaceShare.Held>(), PlaceShare.wanted(p, store.allRecords(), setOf(vOld), emptySet()).filter { it.groupId == vOld })
        assertEquals(emptyList<PlaceShare.Held>(), PlaceShare.wanted(p, store.allRecords(), emptySet(), want.map { it.key }.toSet()))
        // Held already (as the projection lists it once the mark comes back): not wanted.
        val heldBack = LedgerStore.groupOpRecord(
            GroupOpIn.HeldPlace(vOld, old.place!!, you), uniffi.skipti_core.HlcStamp(5000uL, 0u), FounderVault.hex(0x60),
        )
        val doc = CloudRecords.document(heldBack, "u").toMutableMap<String, Any?>()
        doc["createdAt"] = Timestamp(1, 0)
        doc["held"] = true
        assertEquals(1, CloudRecords.absorb(listOf(doc), vOld, store).size)
        val marked = store.projectGroups()
        assertTrue(marked.groups.first { it.group.id == vOld }.held.any { it.memberId == old.place && it.youId == you })
        // The mark is the held member's claim (D4): Old's {x, you x} is void in core, and A7 reads the
        // phone's own claim record, but A10 (M1): x is held by one of this account's ids (y), so no claim
        // on x is a candidate under the other id: after the (x, y) mark, Old wants nothing.
        assertEquals(listOf("$vNew:$vy:$vy"), PlaceShare.wanted(marked, store.allRecords(), emptySet(), emptySet()).map { it.key })
        assertEquals(emptyList<PlaceShare.Held>(), PlaceShare.wanted(marked, store.allRecords(), emptySet(), emptySet()).filter { it.groupId == vOld })
        assertEquals(emptyList<PlaceShare.Held>(), PlaceShare.claimed(marked, marked.groups.first { it.group.id == vOld }, store.allRecords()))

        // A1: a removed place a claim of this account held (with its other id x)
        // is wanted too, before the place, with that claim's id (no removed check:
        // the server decides).
        val six = FounderVault.hex(0x20)
        store.applyRecords(
            listOf(
                LedgerStore.groupOpRecord(
                    GroupOpIn.AddMember(vNew, uniffi.skipti_core.GroupOpMemberIn(six, "Al")),
                    uniffi.skipti_core.HlcStamp(5100uL, 0u), FounderVault.hex(0x51),
                ),
                FounderVault.claim(8, 0x20, 2, 5200, 0x50),
                LedgerStore.groupOpRecord(GroupOpIn.RemoveMember(vNew, six), uniffi.skipti_core.HlcStamp(5300uL, 0u), FounderVault.hex(0x53)),
            ),
        )
        store.rebuildProjection()
        val rejoined = store.projectGroups()
        // (A10: Old's x is held by this account, so Old wants nothing; no answered set is needed for it.)
        val oldOwn = emptySet<String>()
        assertEquals(
            listOf(PlaceShare.Held(vNew, six, vx), PlaceShare.Held(vNew, vy, vy)),
            PlaceShare.wanted(rejoined, store.allRecords(), emptySet(), oldOwn),
        )
        assertEquals("$vNew:$six:$vx", PlaceShare.Held(vNew, six, vx).key)
        assertEquals(
            "a skipped pair is answered, never asked again",
            listOf(PlaceShare.Held(vNew, vy, vy)),
            PlaceShare.wanted(rejoined, store.allRecords(), emptySet(), oldOwn + "$vNew:$six:$vx"),
        )
    }

    /** The job remembers what the cloud answered, asks again later on a
     *  failure or the quota, and is done on invalid-argument: the AliasShare
     *  contract. Twin of iOS's testThePlaceJobRemembersWhatTheCloudAnswered. */
    @Test
    fun d230_the_place_job_remembers_what_the_cloud_answered() = kotlinx.coroutines.runBlocking {
        val store = FounderVault.p(tempStore(), pClaim = 2)
        val want = PlaceShare.wanted(store.projectGroups(), store.allRecords(), emptySet(), emptySet())
        assertEquals(listOf("$vOld:$vx:$vx", "$vOld:$vx:$vy", "$vNew:$vy:$vy"), want.map { it.key })
        val sent = mutableListOf<JsonObject>()
        PlaceShare.send = { data ->
            sent += data
            val ids = data["records"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            JsonObject(
                mapOf(
                    "shared" to JsonArray(ids.take(1).map { kotlinx.serialization.json.JsonPrimitive(it) }),
                    "skipped" to JsonArray(ids.drop(1).map { kotlinx.serialization.json.JsonPrimitive(it) }),
                ),
            )
        }
        assertEquals("a group not in the cloud is not asked", AliasShare.Answer.DONE, PlaceShare.share(store))
        assertEquals(0, sent.size)
        CloudRecords.saveGroupsUp(store, setOf(vOld, vNew))
        assertEquals(AliasShare.Answer.DONE, PlaceShare.share(store))
        assertEquals(1, sent.size)
        val records = sent.single()["records"]!!.jsonArray
        assertEquals(
            want.map { GroupOpIn.HeldPlace(it.groupId, it.memberId, it.youId) },
            records.map {
                ffiDecodeGroupOp(Base64.getDecoder().decode(it.jsonObject["payload"]!!.jsonObject["blob"]!!.jsonPrimitive.content))
            },
        )
        assertEquals("shared and skipped alike are answered", want.map { it.key }.toSet(), PlaceShare.answered(store))
        assertFalse("built, never kept", store.allRecords().any { CloudRecords.heldType(it) })
        assertEquals("nothing left", AliasShare.Answer.DONE, PlaceShare.share(store))
        assertEquals("asked once", 1, sent.size)
        PlaceShare.saveAnswered(store, emptySet())
        PlaceShare.send = { throw CloudFunctions.Failure.Refused("RESOURCE_EXHAUSTED") }
        assertEquals(AliasShare.Answer.LATER, PlaceShare.share(store))
        assertEquals("the quota answers nothing", emptySet<String>(), PlaceShare.answered(store))
        PlaceShare.send = { throw CloudFunctions.Failure.Offline() }
        assertEquals(AliasShare.Answer.LATER, PlaceShare.share(store))
        assertEquals("offline answers nothing", emptySet<String>(), PlaceShare.answered(store))
        PlaceShare.send = { throw CloudFunctions.Failure.Refused("INVALID_ARGUMENT") }
        assertEquals(AliasShare.Answer.DONE, PlaceShare.share(store))
        assertEquals("a refused call is an answer", want.map { it.key }.toSet(), PlaceShare.answered(store))
        // The answer to an error: AliasShare's, exactly.
        assertEquals(AliasShare.Answer.DONE, PlaceShare.answer(null))
        assertEquals(AliasShare.Answer.REFUSED, PlaceShare.answer(CloudFunctions.Failure.Refused("INVALID_ARGUMENT")))
        assertEquals(AliasShare.Answer.LATER, PlaceShare.answer(CloudFunctions.Failure.Refused("RESOURCE_EXHAUSTED")))
        assertEquals(AliasShare.Answer.LATER, PlaceShare.answer(CloudFunctions.Failure.Offline()))
    }

    /** A push with nothing pending shares the places after the aliases
     *  (source pinned, both branches). Twin of iOS's
     *  testAPushSharesThePlacesAfterTheAliases. */
    @Test
    fun d230_a_push_shares_the_places_after_the_aliases() {
        val source = java.io.File("src/main/java/com/vardrlabs/skipti/CloudSync.kt").readText()
        val nothing = source.substringAfter("if (pending.isEmpty()) {").substringBefore("return 0")
        assertTrue(nothing.contains("shareAliases(store)") && nothing.contains("sharePlaces(store)"))
        assertTrue(nothing.indexOf("shareAliases(store)") < nothing.indexOf("sharePlaces(store)"))
        val after = source.substringAfter("if (!offline) shareAliases(store)").substringBefore("return count")
        assertTrue(after.contains("if (!offline) sharePlaces(store)"))
    }

    /** D-230 A6 (I-B): `wanted` no longer needs a place: in a group with no
     *  place, every claimed pair of this account is still asked (the server
     *  marks the one it holds, core seeds it, the place returns). Vector 1:
     *  this account's id claimed on two present members is contested, so no
     *  place, and `wanted` itself lists both pairs; but core reads that group
     *  as `placeLost`, so it is a read-only copy ([LedgerStore.readOnly],
     *  D-229 decision 45) and the job ([PlaceShare.share]) skips it and sends
     *  nothing (final fix wave item 7, review M4). Vector 2, the amendment's
     *  rejoin (the old place marked, the new place's mark failed), is the one
     *  the job reaches: core drops the void claim on the new place from
     *  `claims` (D4 reports it contested), so A7 reads the phone's own claim
     *  records, and the job sends (seven, you).
     *  Twin of iOS's testAGroupWithNoPlaceStillWantsItsClaimedPlace. */
    @Test
    fun d230_a_group_with_no_place_still_wants_its_claimed_place() {
        val store = tempStore()
        store.setYourName("Al")
        val me = store.ensureSelf().id
        val device = LedgerStore.deviceId(store)
        val group = FounderVault.hex(0x70)
        val five = FounderVault.hex(0x75)
        val six = FounderVault.hex(0x76)
        val seven = FounderVault.hex(0x77)
        val them = FounderVault.hex(0x73)
        fun op(o: GroupOpIn, millis: Long, by: String) = LedgerStore.groupOpRecord(o, uniffi.skipti_core.HlcStamp(millis.toULong(), 0u), by)
        store.applyRecords(
            listOf(
                op(
                    GroupOpIn.Create(
                        group, "Trip", "INR",
                        listOf(GroupOpMemberIn(FounderVault.hex(0x72), "Bo"), GroupOpMemberIn(five, "Al"), GroupOpMemberIn(six, "Al")),
                    ),
                    1000, them,
                ),
                op(Invites.claim(group, five, me, device), 1050, device),
                op(Invites.claim(group, six, me, device), 1100, device),
            ),
        )
        store.rebuildProjection()
        val two = store.projectGroups()
        assertNull("one id on two present members: contested, no place", two.groups.single().place)
        val both = PlaceShare.wanted(two, store.allRecords(), emptySet(), emptySet())
        assertEquals(listOf(PlaceShare.Held(group, five, me), PlaceShare.Held(group, six, me)), both)
        assertEquals(emptyList<PlaceShare.Held>(), PlaceShare.wanted(two, store.allRecords(), setOf(group), emptySet()))
        assertEquals(emptyList<PlaceShare.Held>(), PlaceShare.wanted(two, store.allRecords(), emptySet(), both.map { it.key }.toSet()))
        assertNull(HeldRule.check(PlaceShare.callData(PlaceShare.records(store, both, 4_000_000_000_000L))["records"]!!.jsonArray, PlaceShare.CALL_MAX))
        // The place is lost, so the group is a read-only copy: the job asks nothing for it.
        assertTrue("place lost", two.groups.single().placeLost)
        assertTrue("a read-only copy", group in store.readOnly())
        assertEquals(emptyList<PlaceShare.Held>(), PlaceShare.wanted(two, store.allRecords(), store.readOnly().keys, emptySet()))
        CloudRecords.saveGroupsUp(store, setOf(group))
        val sent = mutableListOf<List<GroupOpIn>>()
        PlaceShare.send = { data ->
            sent += data["records"]!!.jsonArray.map {
                ffiDecodeGroupOp(Base64.getDecoder().decode(it.jsonObject["payload"]!!.jsonObject["blob"]!!.jsonPrimitive.content))
            }
            JsonObject(mapOf("shared" to JsonArray(data["records"]!!.jsonArray.map { it.jsonObject["id"]!! })))
        }
        kotlinx.coroutines.runBlocking { assertEquals(AliasShare.Answer.DONE, PlaceShare.share(store, 4_000_000_000_000L)) }
        assertEquals("vector 1: the job sends nothing", emptyList<List<GroupOpIn>>(), sent)

        // Vector 2: six's mark comes back through the group's log; five is removed, six too, and the
        // account is invited back to seven, whose join-time mark failed.
        val doc = CloudRecords.document(op(GroupOpIn.HeldPlace(group, six, me), 1200, FounderVault.hex(0x60)), "u").toMutableMap<String, Any?>()
        doc["createdAt"] = Timestamp(1, 0)
        doc["held"] = true
        assertEquals(1, CloudRecords.absorb(listOf(doc), group, store).size)
        store.applyRecords(
            listOf(
                op(GroupOpIn.RemoveMember(group, five), 1250, them),
                op(GroupOpIn.RemoveMember(group, six), 1300, them),
                op(GroupOpIn.AddMember(group, GroupOpMemberIn(seven, "Al")), 1400, them),
                op(Invites.claim(group, seven, me, device), 1500, device),
            ),
        )
        store.rebuildProjection()
        val p = store.projectGroups()
        val g = p.groups.single { it.group.id == group }
        assertNull("no place: the claim on seven is void while six holds the id", g.place)
        assertTrue(g.held.any { it.memberId == six && it.youId == me })
        // Core lists the void claim on seven nowhere but `contested`; the vault's own claim record brings it (A7).
        assertFalse(g.claims.any { it.memberId == seven })
        val rejoin = PlaceShare.wanted(p, store.allRecords(), emptySet(), emptySet())
        assertEquals(listOf(PlaceShare.Held(group, seven, me)), rejoin.filter { it.memberId == seven })
        assertTrue(PlaceShare.Held(group, seven, me) in PlaceShare.claimed(p, g, store.allRecords()))
        assertFalse("not from the projection alone", PlaceShare.Held(group, seven, me) in PlaceShare.claimed(p, g, emptyList()))
        assertNull(HeldRule.check(PlaceShare.callData(PlaceShare.records(store, rejoin, 4_100_000_000_000L))["records"]!!.jsonArray, PlaceShare.CALL_MAX))
        // Vector 2 is not a read-only copy: the job reaches it and sends (seven, you).
        assertFalse("not read-only", group in store.readOnly())
        kotlinx.coroutines.runBlocking { assertEquals(AliasShare.Answer.DONE, PlaceShare.share(store, 4_200_000_000_000L)) }
        assertTrue(GroupOpIn.HeldPlace(group, seven, me) in sent.single())
    }

    /** Final fix wave item 5: the job sends at most 50 records a call
     *  (`sharePlaces` takes 1 to 50): 51 wanted places go as chunks of 50
     *  and 1, each passing the server's rule ([HeldRule]), every place once
     *  and all answered. Twin of iOS's testFiftyOnePlacesGoAsTwoCalls. */
    @Test
    fun d230_fifty_one_places_go_as_chunks_of_fifty_and_one() = kotlinx.coroutines.runBlocking {
        val store = tempStore()
        store.setYourName("Al")
        val me = store.ensureSelf().id
        val device = LedgerStore.deviceId(store)
        val them = FounderVault.hex(0x73)
        val bo = FounderVault.hex(0x72)
        // 51 groups, each joined the ordinary way (one claim on its own place): one wanted place each.
        val groups = (0 until 51).map { "%032x".format(0x1000 + it) }
        val places = (0 until 51).map { "%032x".format(0x2000 + it) }
        fun op(o: GroupOpIn, millis: Long, by: String) = LedgerStore.groupOpRecord(o, uniffi.skipti_core.HlcStamp(millis.toULong(), 0u), by)
        store.applyRecords(
            groups.indices.flatMap { i ->
                listOf(
                    op(GroupOpIn.Create(groups[i], "Trip $i", "INR", listOf(GroupOpMemberIn(bo, "Bo"), GroupOpMemberIn(places[i], "Al"))), 1000L + i, them),
                    op(Invites.claim(groups[i], places[i], me, device), 2000L + i, device),
                )
            },
        )
        store.rebuildProjection()
        assertTrue(store.readOnly().isEmpty())
        val want = PlaceShare.wanted(store.projectGroups(), store.allRecords(), emptySet(), emptySet())
        assertEquals("51 wanted", groups.indices.map { PlaceShare.Held(groups[it], places[it], me) }.toSet(), want.toSet())
        assertEquals(51, want.size)
        CloudRecords.saveGroupsUp(store, groups.toSet())
        val sizes = mutableListOf<Int>()
        val sentKeys = mutableListOf<String>()
        PlaceShare.send = { data ->
            val records = data["records"]!!.jsonArray
            sizes += records.size
            assertNull(HeldRule.check(records, PlaceShare.CALL_MAX))
            records.forEach {
                val h = ffiDecodeGroupOp(Base64.getDecoder().decode(it.jsonObject["payload"]!!.jsonObject["blob"]!!.jsonPrimitive.content)) as GroupOpIn.HeldPlace
                sentKeys += PlaceShare.Held(h.groupId, h.memberId, h.youId).key
            }
            JsonObject(mapOf("shared" to JsonArray(records.map { it.jsonObject["id"]!! })))
        }
        assertEquals(AliasShare.Answer.DONE, PlaceShare.share(store, 4_000_000_000_000L))
        assertEquals(listOf(50, 1), sizes)
        assertEquals("every place once, in order", want.map { it.key }, sentKeys)
        assertEquals("all answered", want.map { it.key }.toSet(), PlaceShare.answered(store))
    }

    /** D-230 A5 (review I1): what the place job sends passes the server's own
     *  check ([HeldRule], `groups.checkHeldRecords`): no record id twice, no
     *  (group, member, you) twice, every id 32 lowercase hex. A two-phone
     *  account marks one member once per id: Old's x with x and with y, which
     *  the old (group, member) rule refused. Twin of iOS's
     *  testThePlacesWantedPassTheServersRule. */
    @Test
    fun d230_the_places_wanted_pass_the_servers_rule() {
        val store = FounderVault.p(tempStore(), pClaim = 2)
        val want = PlaceShare.wanted(store.projectGroups(), store.allRecords(), emptySet(), emptySet())
        assertEquals(listOf("$vOld:$vx:$vx", "$vOld:$vx:$vy", "$vNew:$vy:$vy"), want.map { it.key })
        val data = PlaceShare.callData(PlaceShare.records(store, want, 4_000_000_000_000L))
        val records = data["records"]!!.jsonArray
        assertEquals(3, records.size)
        assertNull(HeldRule.check(records, PlaceShare.CALL_MAX))
        assertEquals("one member, two ids: the pair rule would refuse it", "records", HeldRule.check(records, PlaceShare.CALL_MAX, triple = false))
        // The rule bites: a triple twice, an id that is not 32 lowercase hex, too many.
        val twice = PlaceShare.records(store, want + want.first(), 4_000_000_000_000L)
        assertEquals("records", HeldRule.check(PlaceShare.callData(twice)["records"]!!.jsonArray, PlaceShare.CALL_MAX))
        // (core refuses to build an upper-case id, so the wire is edited by hand)
        val blob = records[2].jsonObject["payload"]!!.jsonObject["blob"]!!.jsonPrimitive.content
        val upperBlob = Base64.getEncoder().encodeToString(
            String(Base64.getDecoder().decode(blob), Charsets.UTF_8).replace(vy, vy.uppercase().replace('0', 'A')).toByteArray(Charsets.UTF_8),
        )
        assertTrue(upperBlob != blob)
        val upper = JsonArray(
            records.take(2) + JsonObject(
                records[2].jsonObject + ("payload" to JsonObject(records[2].jsonObject["payload"]!!.jsonObject + ("blob" to kotlinx.serialization.json.JsonPrimitive(upperBlob)))),
            ),
        )
        assertEquals("records", HeldRule.check(upper, PlaceShare.CALL_MAX))
        assertEquals("records", HeldRule.check(records, 2))
        // The rejoin vector (a removed place of the other id) passes as well.
        val six = FounderVault.hex(0x20)
        store.applyRecords(
            listOf(
                LedgerStore.groupOpRecord(
                    GroupOpIn.AddMember(vNew, GroupOpMemberIn(six, "Al")), uniffi.skipti_core.HlcStamp(5100uL, 0u), FounderVault.hex(0x51),
                ),
                FounderVault.claim(8, 0x20, 2, 5200, 0x50),
                LedgerStore.groupOpRecord(GroupOpIn.RemoveMember(vNew, six), uniffi.skipti_core.HlcStamp(5300uL, 0u), FounderVault.hex(0x53)),
            ),
        )
        store.rebuildProjection()
        val again = PlaceShare.wanted(store.projectGroups(), store.allRecords(), emptySet(), emptySet())
        assertTrue(PlaceShare.Held(vNew, six, vx) in again)
        assertNull(HeldRule.check(PlaceShare.callData(PlaceShare.records(store, again, 4_100_000_000_000L))["records"]!!.jsonArray, PlaceShare.CALL_MAX))

        // The ordinary join: this account's claim names its own place with its "you", so the claimed
        // pair and (place, you) are one triple, asked once (a triple twice is refused whole).
        val joined = tempStore()
        joined.setYourName("Al")
        val me = joined.ensureSelf().id
        val group = FounderVault.hex(0x70)
        val member = FounderVault.hex(0x71)
        joined.applyRecords(
            listOf(
                LedgerStore.groupOpRecord(
                    GroupOpIn.Create(group, "Trip", "INR", listOf(GroupOpMemberIn(FounderVault.hex(0x72), "Bo"), GroupOpMemberIn(member, "Al"))),
                    uniffi.skipti_core.HlcStamp(1000uL, 0u), FounderVault.hex(0x73),
                ),
                LedgerStore.groupOpRecord(
                    Invites.claim(group, member, me, LedgerStore.deviceId(joined)), uniffi.skipti_core.HlcStamp(1100uL, 0u), LedgerStore.deviceId(joined),
                ),
            ),
        )
        joined.rebuildProjection()
        val jp = joined.projectGroups()
        assertEquals(member, jp.groups.single().place)
        assertEquals(listOf(PlaceShare.Held(group, member, me)), PlaceShare.claimed(jp, jp.groups.single(), joined.allRecords()))
        val one = PlaceShare.wanted(jp, joined.allRecords(), emptySet(), emptySet())
        assertEquals(listOf(PlaceShare.Held(group, member, me)), one)
        assertNull(HeldRule.check(PlaceShare.callData(PlaceShare.records(joined, one, 4_000_000_000_000L))["records"]!!.jsonArray, PlaceShare.CALL_MAX))
    }
}

/** D-230 A5: the server's `groups.checkHeldRecords` and `recordShape`
 *  (firebase/functions/groups.js), as the shells read them: 1 to [max]
 *  records (50 for `sharePlaces`, 11 for `acceptInvite`), each well formed
 *  (id is millis-counter-device with safe non-negative integers, 32-hex
 *  devices, a payload whose blob (at most 65536) and nonce (at most 64) are
 *  non-empty base64 and whose version, epoch and flags are integers, type 3,
 *  a heldPlace blob of exactly op, group, member and you, three 32 lowercase
 *  hex ids), no record id twice and no (group, member, you) twice ([triple]
 *  false: the pair rule A5 replaced, kept to show a vector needs the triple).
 *  With [invite] (group, member), `acceptInvite`'s own rule too: every record
 *  in the invite's group and at least one for its member (A8: each checked
 *  alone, so two ids on the place pass). "records" or null. */
object HeldRule {
    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val BASE64 = Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
    private const val SAFE = 9_007_199_254_740_991L

    private fun b64(v: kotlinx.serialization.json.JsonElement?, max: Int): Boolean {
        val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return false
        return p.isString && p.content.isNotEmpty() && p.content.length <= max && BASE64.matches(p.content)
    }

    /** A JSON integer within JavaScript's safe range (`Number.isSafeInteger`). */
    private fun int(v: kotlinx.serialization.json.JsonElement?): Long? {
        val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (p.isString) return null
        return p.content.toLongOrNull()?.takeIf { it in -SAFE..SAFE }
    }

    fun check(records: JsonArray, max: Int, triple: Boolean = true, invite: Pair<String, String>? = null): String? {
        if (records.size < 1 || records.size > max) return "records"
        val ids = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        var forPlace = 0
        for (element in records) {
            val r = element as? JsonObject ?: return "records"
            val hlc = r["hlc"] as? JsonObject ?: return "records"
            val millis = int(hlc["millis"]) ?: return "records"
            val counter = int(hlc["counter"]) ?: return "records"
            val device = (hlc["device"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return "records"
            if (millis < 0 || counter < 0 || !HEX32.matches(device)) return "records"
            val id = r["id"]?.jsonPrimitive?.content
            if (id != "$millis-$counter-$device") return "records"
            if (!HEX32.matches(r["deviceId"]?.jsonPrimitive?.content ?: "")) return "records"
            val payload = r["payload"] as? JsonObject ?: return "records"
            if (!b64(payload["blob"], 65536) || !b64(payload["nonce"], 64)) return "records"
            if (listOf("version", "epoch", "flags").any { int(payload[it]) == null }) return "records"
            if (!ids.add(id) || r["type"]?.jsonPrimitive?.content != "3") return "records"
            val blob = payload["blob"]!!.jsonPrimitive.content
            val op = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(String(Base64.getDecoder().decode(blob), Charsets.UTF_8)) as JsonObject
            }.getOrNull() ?: return "records"
            if (op["op"]?.jsonPrimitive?.content != "heldPlace") return "records"
            if (op.keys.any { it !in setOf("op", "group", "member", "you") }) return "records"
            val (g, m, y) = listOf("group", "member", "you").map { (op[it] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "" }
            if (listOf(g, m, y).any { !HEX32.matches(it) }) return "records"
            if (!seen.add(if (triple) "$g:$m:$y" else "$g:$m")) return "records"
            if (invite != null) {
                if (g != invite.first) return "records"
                if (m == invite.second) forPlace += 1
            }
        }
        if (invite != null && forPlace < 1) return "records"
        return null
    }
}
