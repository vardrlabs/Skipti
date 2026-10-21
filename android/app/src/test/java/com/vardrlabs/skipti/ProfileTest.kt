package com.vardrlabs.skipti

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

/**
 * D-145: the on-device profile's picture, the twin of iOS ProfileTests. In
 * the shared test set: both flavours run it. (The photo's pixel work needs
 * Android's Bitmap and is seen on the emulator; its crop rule is tested.)
 */
class ProfileTest {

    private fun store(): LedgerStore {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = Files.createTempDirectory("profile-test").resolve("ledger-vault.db").toString()
        return LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    @Test
    fun a_picture_survives_the_vault_and_an_unknown_one_is_no_picture() {
        val s = store()
        assertEquals("a new vault has no picture", Avatar.None, s.loadAvatar())
        s.saveAvatar(Avatar.Emoji("🦊"))
        assertEquals(Avatar.Emoji("🦊"), s.loadAvatar())
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        s.saveAvatar(Avatar.Photo(jpeg))
        assertEquals(Avatar.Photo(jpeg), s.loadAvatar())
        s.saveAvatar(Avatar.None)
        assertEquals(Avatar.None, s.loadAvatar())
        assertEquals(
            "an emoji outside the offered set is refused on read",
            Avatar.None, Avatar.decode("""{"kind":"emoji","emoji":"💀"}""".toByteArray()),
        )
        assertEquals("unreadable is no picture, never a crash", Avatar.None, Avatar.decode("not json".toByteArray()))
    }

    /** The offered emoji are iOS's 48, in iOS's order. */
    @Test
    fun the_offered_emoji_are_fixed_and_distinct() {
        assertEquals(48, ProfileRules.EMOJI_CHOICES.size)
        assertEquals(48, ProfileRules.EMOJI_CHOICES.toSet().size)
        assertEquals("😀", ProfileRules.EMOJI_CHOICES.first())
        assertEquals("🧩", ProfileRules.EMOJI_CHOICES.last())
    }

    /** D-225 (L76): the emoji grid in rows of six, and the chosen cell's paint by token name: accentSoft inside an accent
     *  border; any other cell clear. The twin of iOS's testTheEmojiGridIsSixColumnsAndOnlyTheChosenCellIsPainted. */
    @Test
    fun the_emoji_grid_is_six_columns_and_only_the_chosen_cell_is_painted() {
        val rows = EmojiGrid.rows(ProfileRules.EMOJI_CHOICES, columns = 6)
        assertEquals(8, rows.size)
        assertEquals(List(8) { 6 }, rows.map { it.size })
        assertEquals("every choice once, in order", ProfileRules.EMOJI_CHOICES, rows.flatten())
        val seven = EmojiGrid.rows(ProfileRules.EMOJI_CHOICES.take(7), columns = 6)
        assertEquals(listOf(6, 1), seven.map { it.size })
        assertEquals(listOf(ProfileRules.EMOJI_CHOICES[6]), seven[1])
        assertEquals(emptyList<List<String>>(), EmojiGrid.rows(emptyList(), columns = 6))
        assertEquals(6, ThemeArt.m("emojiColumns").toInt())
        val chosen = EmojiCell.paint(chosen = true)
        assertEquals("accentSoft" to "accent", chosen.fill?.name to chosen.border?.name)
        val clear = EmojiCell.paint(chosen = false)
        assertNull(clear.fill)
        assertNull(clear.border)
    }

    @Test
    fun initials_come_from_up_to_two_words() {
        assertEquals("T", ProfileRules.initials("tim"))
        assertEquals("TC", ProfileRules.initials("Tim Cook"))
        assertEquals("AM", ProfileRules.initials("Ana María López"))
        assertNull(ProfileRules.initials(null))
        assertNull(ProfileRules.initials("   "))
    }

    @Test
    fun a_photo_is_cropped_to_its_centred_square() {
        assertEquals(Triple(150, 0, 300), ProfileRules.squareCrop(600, 300))
        assertEquals(Triple(0, 150, 200), ProfileRules.squareCrop(200, 500))
    }

    /** Back from Profile returns to Settings (D-143's rule). */
    @Test
    fun back_from_profile_returns_to_settings() {
        assertEquals("account", HomeRoutes.parent("profile"))
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

    /** D-225 (Design 9; L75, L76): the placeholders at iOS's ratios (glyph `avatarGlyph`, emoji `avatarEmoji` of the
     *  size), the emoji grid in `emojiColumns` equal columns painted by [EmojiCell] in the theme's panel shape, and the
     *  name in the hairline field. The twin of iOS's testTheProfilePlaceholdersAndGrid. */
    @Test
    fun the_profile_placeholders_and_grid() {
        val avatar = body225("Profile.kt", "fun AvatarView(")
        assertTrue("Modifier.size((size.value * ThemeArt.m(\"avatarGlyph\")).dp)" in avatar)
        assertTrue("(size.value * ThemeArt.m(\"avatarEmoji\")).sp" in avatar)
        val screen = body225("Profile.kt", "fun ProfileScreen(")
        for (s in listOf(
            "EmojiGrid.rows(ProfileRules.EMOJI_CHOICES, ThemeArt.m(\"emojiColumns\").toInt())", "EmojiCell.paint(",
            "ThemeArt.panelShape(Appearance.theme, ThemeArt.m(\"emojiRadius\").toInt())", "ThemeArt.m(\"emojiBorder\")",
            "ThemeArt.m(\"emojiGap\")", "ThemeArt.m(\"emojiGlyph\")", "HairlineField(",
        )) assertTrue("ProfileScreen: $s", s in screen)
        val t = s225("Profile.kt")
        for (gone in listOf("OutlinedTextField", "FlowRow(", "RoundedCornerShape(8.dp)")) assertFalse("Profile.kt keeps $gone", gone in t)
    }

    /** D-225 fix wave (F5): the name card is iOS's: its cards' spacing and the name field's size, read out of
     *  Profile.swift (the look measured 194.3 pt against 183.6 dp: 2 dp a gap and the field a size smaller here). */
    @Test
    fun the_name_card_is_ios_name_card() {
        val swift = java.io.File("../../ios/Skipti/App/Profile.swift").readText()
        val iosCard = Regex("""private func card<Content: View>\(@ViewBuilder _ content: \(\) -> Content\) -> some View \{\s*VStack\(alignment: \.leading, spacing: (\d+)\)""")
            .find(swift)!!.groupValues[1].toInt()
        val iosField = Regex("""TextField\("Your name", text: \${'$'}name\)\s*\.font\(TypeRole\.body\((\d+)\)\)""").find(swift)!!.groupValues[1].toFloat()
        assertEquals(iosCard, ProfileLayout.CARD_SPACING)
        assertEquals(iosField, ProfileLayout.NAME_FIELD_SIZE, 0f)
        val screen = body225("Profile.kt", "fun ProfileScreen(")
        assertEquals("both cards", 2, screen.split("verticalArrangement = Arrangement.spacedBy(ProfileLayout.CARD_SPACING.dp),").size - 1)
        // D-245 (U17): the field's change also clears the error, and Done on the keyboard saves (WelcomeTest).
        assertTrue("the field at iOS's size",
            "HairlineField(\n                name, { name = it; error = null }, \"Your name\", dark,\n                style = Type.body(dark).copy(fontSize = ProfileLayout.NAME_FIELD_SIZE.sp)," in screen)
    }

    /** D-225 follow-up (R6, the re-look's dump): each emoji cell is ONE node to TalkBack, its emoji with its chosen
     *  state and its tap (it was a checkable node with no words and a second node holding the emoji, same bounds), as
     *  the list rows are since the fix wave. iOS: `.accessibilityLabel(e)` with `.isSelected` on the chosen one. */
    @Test
    fun each_emoji_cell_is_one_node() {
        val screen = body225("Profile.kt", "fun ProfileScreen(")
        val cell = screen.substringAfter("val paint = EmojiCell.paint(chosen)").substringBefore("contentAlignment = Alignment.Center,")
        assertTrue("the tap, then the cell's own words and state, its children cleared",
            // D-245 (decision 161): the tap stages the picture (the confirm sheet writes it).
            ".clickable { stagePicture(Avatar.Emoji(e)) }\n                                    .clearAndSetSemantics { contentDescription = e; selected = chosen }," in cell)
        assertFalse("no merging semantics left beside it", ".semantics {" in cell)
        val swift = java.io.File("../../ios/Skipti/App/Profile.swift").readText()
        assertTrue("iOS: the emoji and its selection", ".accessibilityLabel(e)" in swift && ".accessibilityAddTraits(avatar == .emoji(e) ? .isSelected : [])" in swift)
    }
}
