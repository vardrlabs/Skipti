package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.CoreException
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.ffiDecodeGroupOp
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import java.nio.file.Files

/**
 * D-187: a group's picture (the founder, 2026-09-30: a photo or an emoji),
 * through the real core and a real vault. The twin of iOS's GroupPicturesTests
 * (in GroupRecordsTests.swift).
 */
class GroupPicturesTest {

    private val dir = Files.createTempDirectory("group-pictures")

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

    private fun jpeg(size: Int) = ByteArray(size).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }

    /** An emoji, then a photo, then the letter back; the picture is a group
     *  record, so the next phone shows it too. */
    @Test
    fun a_group_carries_its_picture_to_every_phone() {
        val a = device("a")
        a.setYourName("Tim")
        val goa = a.createGroup("Goa", "INR", listOf("Rahul")).group.id
        assertNull(a.groupPictures()[goa])

        a.setGroupPicture(goa, emoji = "🏖️")
        assertEquals(PictureDTO(emoji = "🏖️"), a.groupPictures()[goa])
        val scopes = a.allRecords().filter { it.recordType == LedgerStore.GROUP_OP_TYPE }
            .filter { ffiDecodeGroupOp(it.blob) is GroupOpIn.GroupPicture }.map { it.groupId }
        assertEquals("a picture is its group's record, shared with the group", listOf(goa), scopes)

        val photo = jpeg(1_000)
        a.setGroupPicture(goa, jpeg = photo)
        assertEquals(PictureDTO(photoHex = HexCodec.encode(photo)), a.groupPictures()[goa])

        val b = device("b")
        for (record in a.allRecords()) b.applyRecord(record)
        b.rebuildProjection()
        assertEquals(PictureDTO(photoHex = HexCodec.encode(photo)), b.groupPictures()[goa])

        a.setGroupPicture(goa)
        assertNull("the letter back", a.groupPictures()[goa])
    }

    /** Negative: core takes a JPEG of at most 64,000 bytes and nothing else;
     *  a refused photo leaves the picture as it was. */
    @Test
    fun a_photo_too_big_or_not_a_jpeg_is_refused() {
        val a = device("c")
        a.setYourName("Tim")
        val goa = a.createGroup("Goa", "INR", listOf("Rahul")).group.id
        a.setGroupPicture(goa, emoji = "⛰️")
        for (bad in listOf(jpeg(GroupPictures.MAX_BYTES + 1), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))) {
            try {
                a.setGroupPicture(goa, jpeg = bad)
                fail("core took ${bad.size} bytes")
            } catch (_: CoreException) {
            }
        }
        a.setGroupPicture(goa, jpeg = jpeg(GroupPictures.MAX_BYTES))
        assertEquals(GroupPictures.MAX_BYTES * 2, a.groupPictures()[goa]?.photoHex?.length)
    }

    /** Goldens from .drafts/d187/golden.py: the largest centred 3:2. (The
     *  pixels themselves are proven on iOS: the JVM has no Bitmap.) */
    @Test
    fun the_cover_crop_is_the_centre_3_by_2() {
        val cases = listOf(
            (300 to 900) to listOf(0, 350, 300, 200), (4032 to 3024) to listOf(0, 168, 4032, 2688),
            (1920 to 1080) to listOf(150, 0, 1620, 1080), (3 to 2) to listOf(0, 0, 3, 2), (1000 to 1000) to listOf(0, 167, 1000, 666),
        )
        for ((wh, want) in cases) assertEquals("$wh", want, GroupPictures.coverCrop(wh.first, wh.second))
        assertEquals(listOf(768, 512), listOf(GroupPictures.WIDTH, GroupPictures.HEIGHT))
    }

    /** The founder: words over a photo must not blend in. White over the
     *  gradient on a pure white photo (goldens from golden.py): 4.759:1 where
     *  words sit and under the chip; the top band's 1.834:1 is why nothing but
     *  the chip's own capsule sits there. */
    @Test
    fun words_over_a_photo_clear_4_5_to_1() {
        assertEquals(4.759, PhotoCard.worstContrast(PhotoCard.TEXT_ALPHA.toDouble()), 0.001)
        assertEquals(4.759, PhotoCard.worstContrast(PhotoCard.CHIP_ALPHA.toDouble()), 0.001)
        assertEquals(9.233, PhotoCard.worstContrast(PhotoCard.BOTTOM_ALPHA.toDouble()), 0.001)
        assertEquals(1.834, PhotoCard.worstContrast(PhotoCard.TOP_ALPHA.toDouble()), 0.001)
        assertTrue("darker toward the words, never lighter", PhotoCard.BOTTOM_ALPHA >= PhotoCard.TEXT_ALPHA)
    }

    @Test
    fun the_choices_and_words_match_ios() {
        assertEquals(24, GroupPictures.EMOJI.size)
        assertEquals(GroupPictures.EMOJI.size, GroupPictures.EMOJI.toSet().size)
        assertEquals(listOf("✈️", "🏖️", "⛰️"), GroupPictures.EMOJI.take(3))
        assertEquals("❤️", GroupPictures.EMOJI.last())
        assertEquals("Group picture", GroupPictures.TITLE)
        assertEquals("Choose a photo", GroupPictures.CHOOSE_PHOTO)
        assertEquals("Use the letter", GroupPictures.USE_LETTER)
        assertEquals("That photo could not be made small enough. Try another.", GroupPictures.TOO_BIG)
        assertEquals("Use 🏖️", GroupPictures.spokenEmoji("🏖️"))
    }
}
