package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-206: the twin of iOS's BalanceLineTests D-206 tests. */
class YouTest {

    /** D-212: Appearance is theme, light or dark, then text size. */
    @Test
    fun appearance_is_theme_then_mode_then_text_size() {
        val expected = listOf(SettingItem.THEME, SettingItem.APPEARANCE_MODE, SettingItem.TEXT_SIZE)
        assertEquals(expected, SettingsMap.items(SettingsSection.APPEARANCE, false))
        assertEquals(expected, SettingsMap.items(SettingsSection.APPEARANCE, true))
        // D-222: the row's summary is YouRows' (pinned in you_rows_summaries); AppearanceCopy.summary is gone.
        fun appearance(theme: AppTheme, mode: AppearanceMode) =
            YouRows.parts(SettingsSection.APPEARANCE, YouRowFacts(false, null, null, theme, mode, LockTimeout.OFF, true, "0.4.0"))
        assertEquals(listOf("Aurora", "System"), appearance(AppTheme.AURORA, AppearanceMode.SYSTEM))
        assertEquals(listOf("Vardr Labs", "Dark"), appearance(AppTheme.VARDR, AppearanceMode.DARK))
        assertEquals(listOf("Norse", "Dark"), appearance(AppTheme.NORSE, AppearanceMode.LIGHT))
        assertEquals(listOf("Theme", "Light or dark", "Text size"), AppearanceCopy.CARDS)
        assertEquals("Norse is always dark.", AppearanceCopy.NORSE_ALWAYS_DARK)
        assertEquals("Starts at your phone's size; changes SKIPTI only.", AppearanceCopy.TEXT_SIZE_CAPTION)
    }

    /** D-222 (L56): each of You's rows has its Lucide tile, the same names as iOS's, each one Android draws. */
    @Test
    fun each_section_has_its_lucide_glyph() {
        assertEquals(listOf("user-round", "sun", "shield", "download", "info"), SettingsSection.entries.map { it.glyphName })
        for (section in SettingsSection.entries) assertTrue(section.glyphName, section.glyphName in TabIcons.PATHS)
    }

    /** D-222 (L58): You's row summaries, the golden table iOS's testYouRowsSummaries pins. Joined on screen by the
     *  theme's separator and spoken as "title, part, part": no part carries a separator of its own. */
    @Test
    fun you_rows_summaries() {
        fun facts(
            signedIn: Boolean = false, method: String? = null, phones: Int? = null, theme: AppTheme = AppTheme.AURORA,
            mode: AppearanceMode = AppearanceMode.SYSTEM, lock: LockTimeout = LockTimeout.TWO_MINUTES, canLock: Boolean = true,
        ) = YouRowFacts(signedIn, method, phones, theme, mode, lock, canLock, "0.4.0")
        val p = SettingsSection.PROFILE_ACCOUNT
        assertEquals(listOf("No account"), YouRows.parts(p, facts()))
        assertEquals("no count without an account", listOf("No account"), YouRows.parts(p, facts(phones = 2)))
        // D-234 (decision 84): the row names the method in one word; the page keeps "Signed in with ...".
        assertEquals(listOf("Apple", "2 phones"), YouRows.parts(p, facts(signedIn = true, method = SignInMethod.Apple.rowLabel, phones = 2)))
        assertEquals(listOf("Signed in", "1 phone"), YouRows.parts(p, facts(signedIn = true, phones = 1)))
        assertEquals(listOf("Google"), YouRows.parts(p, facts(signedIn = true, method = SignInMethod.Google.rowLabel)))
        assertEquals("a count of 0 is left out", listOf("Google"), YouRows.parts(p, facts(signedIn = true, method = SignInMethod.Google.rowLabel, phones = 0)))
        assertTrue("the screen passes the one word", "methodLabel = method?.rowLabel" in java.io.File("src/main/java/com/vardrlabs/skipti/AccountHubScreen.kt").readText())
        val a = SettingsSection.APPEARANCE
        assertEquals(listOf("Aurora", "System"), YouRows.parts(a, facts(theme = AppTheme.AURORA, mode = AppearanceMode.SYSTEM)))
        assertEquals(listOf("Vardr Labs", "Light"), YouRows.parts(a, facts(theme = AppTheme.VARDR, mode = AppearanceMode.LIGHT)))
        assertEquals(listOf("Norse", "Dark"), YouRows.parts(a, facts(theme = AppTheme.NORSE, mode = AppearanceMode.LIGHT)))
        val lines = listOf(
            LockTimeout.IMMEDIATELY to "App lock immediately", LockTimeout.ONE_MINUTE to "App lock after 1 minute",
            LockTimeout.TWO_MINUTES to "App lock after 2 minutes", LockTimeout.FIVE_MINUTES to "App lock after 5 minutes",
            LockTimeout.FIFTEEN_MINUTES to "App lock after 15 minutes", LockTimeout.OFF to "App lock off",
        )
        assertEquals(LockTimeout.entries.toList(), lines.map { it.first })
        for ((timeout, line) in lines) {
            assertEquals(listOf(line), YouRows.parts(SettingsSection.SECURITY, facts(lock = timeout)))
            assertEquals(line, YouRows.securityLine(timeout, available = true))
            assertEquals(listOf("App lock needs a phone lock"), YouRows.parts(SettingsSection.SECURITY, facts(lock = timeout, canLock = false)))
        }
        assertEquals(listOf("Export, import, currency"), YouRows.parts(SettingsSection.DATA, facts()))
        assertEquals(listOf("0.4.0", "promises", "licences"), YouRows.parts(SettingsSection.ABOUT, facts()))
        assertEquals("Security, App lock off", YouRows.spoken("Security", listOf("App lock off")))
        assertEquals("Appearance, Norse, Dark", YouRows.spoken("Appearance", listOf("Norse", "Dark")))
        for (section in SettingsSection.entries) {
            for (f in listOf(facts(), facts(signedIn = true, method = SignInMethod.Apple.label, phones = 3, theme = AppTheme.NORSE))) {
                for (part in YouRows.parts(section, f)) assertTrue("$section $part", Runes.SEPARATOR !in part && "\u00B7" !in part)
            }
        }
    }

    private fun main(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    /** D-222 (L56 to L58): You's rows draw the icon tile, the title in the name role at 16, and the live summary as
     *  parts joined by the theme's separator, spoken as "title, part, part"; the list card pads 0 and lays its rows out
     *  at the Look Book's list padding. The twin of iOS's testYouRowsDrawTilesAndLiveSummaries. */
    @Test
    fun you_rows_draw_tiles_and_live_summaries() {
        val src = main("AccountHubScreen.kt")
        // D-225 (Design 1): S1's row is now the shared ListRow (with the tile) in the shared ListCard; nothing on screen
        // changed. Its internals are read in ListRows.kt.
        val list = src.substringAfter("SettingsSection.entries.forEach").substringBefore("return@PushedPage")
        for (s in listOf(
            "ListRow(", "s.title,", "leading = { SettingsTile(s.glyphName, dark) }", "parts = YouRows.parts(s, rowFacts)",
            "trailing = RowTrailing.Chevron",
        )) assertTrue("the rows: $s", s in list)
        // Fix wave (S6): TalkBack says "button", as VoiceOver does for iOS's NavigationLink.
        assertTrue("a row opens its page", "{ if (s == SettingsSection.ABOUT) onOpenAbout() else onOpenSection(s) }" in list)
        val rows = main("ListRows.kt")
        val row = rows.substringAfter("fun ListRow(").substringBefore("\n}\n")
        for (s in listOf(
            "Text(title, style = Type.name(dark, 16)", "Separator.annotated(parts)", "contentDescription = ListRows.spoken(title, parts, trailing)",
            ".padding(vertical = ThemeArt.m(\"rowPadV\").dp)", "Spacer(Modifier.width(ThemeArt.m(\"rowGap\").dp))",
            "style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp)", "overflow = TextOverflow.Ellipsis", "role = Role.Button",
        )) assertTrue("ListRow: $s", s in row)
        assertEquals("the words are spoken once, by the row", 2, row.split("Modifier.clearAndSetSemantics {}").size - 1)
        assertTrue("the chevron", "RowChevron(Tok.textDim(dark))" in rows.substringAfter("private fun RowEnd(").substringBefore("\n}\n"))
        assertEquals("a chevron row speaks as You's did", YouRows.spoken("Security", listOf("App lock off")), ListRows.spoken("Security", listOf("App lock off"), RowTrailing.Chevron))
        assertTrue(
            "the list card pads 0, then the list's own padding",
            "Column(modifier.glassCard(dark, padding = 0).padding(horizontal = ThemeArt.m(\"listPadH\").dp, vertical = ThemeArt.m(\"listPadV\").dp))" in rows,
        )
        for (gone in listOf("YouCopy.SECURITY_LINE", "AboutInfo.versionLine(", "AppearanceCopy.summary", "fontSize = 13.sp")) {
            assertFalse("AccountHubScreen.kt still has $gone", gone in src)
        }
        // The facts: the cloud's phone count (signed in), the lock's timeout and whether the phone can lock (re-read on resume).
        for (s in listOf("Phones.activeCount()", "AppLock.timeout", "remember(resumes) { AppLock.available(context) }", "method?.rowLabel")) {
            assertTrue("the facts: $s", s in src)
        }
        assertFalse("YouCopy.SECURITY_LINE is gone", "SECURITY_LINE" in src)
        // Fix wave (S7): the phone read (a server read, slow offline) runs after the username merge, so the setup steps
        // gate at once; the twin of iOS's order.
        val reload = src.substringAfter("suspend fun reload() {").substringBefore("\n    }\n")
        val merge = reload.indexOf("onUsernameState(UsernameState.merge(")
        assertTrue("the phone read after the merge", merge >= 0 && reload.indexOf("Phones.activeCount()") > merge)
        val phones = main("Phones.kt")
        val read = phones.substringAfter("suspend fun activeCount(): Int?").substringBefore("\n    }\n")
        assertTrue("from the server, once", "collection(\"devices\").get(Source.SERVER).awaitTask()" in read)
        assertTrue("a failed read is null, never 0", ".getOrNull()" in read)
    }

    /** D-222 (L56): the settings tile is the theme's tile: Aurora rounded, Norse the diamond drawn at its drawn side with
     *  its centred edge (laid before the clip), Vardr Labs square; accentSoft under the accent glyph; decorative. The twin
     *  of iOS's testTheSettingsTileIsTheThemesTile. */
    @Test
    fun the_settings_tile_is_the_themes_tile() {
        val src = main("AccountHubScreen.kt")
        val tile = src.substringAfter("private fun SettingsTile(").substringBefore("\n}\n")
        for (s in listOf(
            "TileShape.of(Appearance.theme, person = false)", "tileShape(kind, size)", "requiredSize(tileDrawnSide(kind, size).dp)",
            ".tileEdge(kind, size)", "ThemeArt.m(\"settingsTile\")", "ThemeArt.m(\"settingsGlyph\")", "Tok.accentSoft(dark)",
            "tint = Tok.accent(dark)", "clearAndSetSemantics {}", "contentDescription = null",
        )) assertTrue("SettingsTile: $s", s in tile)
        assertTrue("the edge before the clip", tile.indexOf(".tileEdge(") in 0 until tile.indexOf(".clip(shape)"))
        assertFalse("no shape of its own", "CircleShape" in tile || "RoundedCornerShape" in tile)
        // Each section's glyph is the drawing its name says.
        val glyphs = src.substringAfter("private fun settingsGlyph(").substringBefore("\n}\n")
        for ((name, icon) in listOf("user-round" to "UserRound", "sun" to "Sun", "shield" to "Shield", "download" to "Download", "info" to "Info")) {
            assertTrue("$name draws TabIcons.$icon", "\"$name\" -> TabIcons.$icon" in glyphs)
        }
    }

    @Test
    fun every_setting_has_one_home_and_account_rows_are_absent_without_an_account() {
        assertEquals(
            listOf(SettingsSection.PROFILE_ACCOUNT, SettingsSection.APPEARANCE, SettingsSection.SECURITY, SettingsSection.DATA, SettingsSection.ABOUT),
            SettingsSection.entries.toList(),
        )
        val signedIn = SettingsMap.hubItems(true) + SettingsSection.entries.flatMap { SettingsMap.items(it, true) }
        assertEquals("a setting in two places", signedIn.size, signedIn.toSet().size)
        assertEquals(SettingItem.entries.toSet() - SettingItem.SIGN_IN, signedIn.toSet())
        val none = SettingsMap.hubItems(false) + SettingsSection.entries.flatMap { SettingsMap.items(it, false) }
        assertTrue("an account row without an account", none.toSet().intersect(SettingsMap.ACCOUNT_ONLY).isEmpty())
        assertEquals(1, none.count { it == SettingItem.SIGN_IN })
        assertEquals(listOf(SettingItem.APP_LOCK, SettingItem.PRIVACY, SettingItem.ERASE), SettingsMap.items(SettingsSection.SECURITY, false))
    }

    @Test
    fun back_from_a_group_page_returns_to_its_group_then_to_you() {
        for (s in SettingsSection.entries.filter { it != SettingsSection.ABOUT }) {
            assertEquals("account", HomeRoutes.parent(s.route))
            assertEquals(s, SettingsMap.section(s.route))
        }
        assertEquals(null, SettingsMap.section("account"))
        assertEquals(null, SettingsMap.section("about"))
        assertEquals("account/security", HomeRoutes.parent("delete-account"))
        assertEquals("account/data", HomeRoutes.parent("import/account"))
        assertEquals(null, HomeRoutes.parent("account"))
        // D-221: the next card's "Start a group" returns to You.
        assertEquals("account", HomeRoutes.parent("new-group/you"))
        assertEquals(null, HomeRoutes.parent("new-group"))
    }

    @Test
    fun the_greeting_follows_the_clock_and_the_name() {
        val cases = listOf(
            0 to "Good evening", 4 to "Good evening", 5 to "Good morning", 11 to "Good morning",
            12 to "Good afternoon", 16 to "Good afternoon", 17 to "Good evening", 23 to "Good evening",
        )
        for ((hour, line) in cases) {
            assertEquals("$line, Prithvi", Greeting.line(hour, "Prithvi"))
            assertEquals(line, Greeting.line(hour, null))
            assertEquals(line, Greeting.line(hour, "  "))
        }
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

    /** D-225 (Design 5; L74): Your data is one list card: Default currency (its value, opening the currency list
     *  page), Export (GUARANTEES #4: one tap) and Import, each with a two-line summary; the currency caption under the
     *  card. The chips and the old Currency settings are gone. The twin of iOS's testYourDataIsOneListCard. */
    @Test
    fun your_data_is_one_list_card() {
        val page = body225("AccountHubScreen.kt", "private fun YourDataPage(")
        assertEquals(1, page.split("ListCard(dark)").size - 1)
        for (s in listOf(
            "RowTrailing.Value(DefaultCurrency.current, chevron = true)", "CurrencyListDialog(", "DefaultCurrency.set(context, it)",
            "YourDataCopy.EXPORT", "YourDataCopy.EXPORT_DETAIL", "ImportCopy.ENTRY", "ImportCopy.ENTRY_DETAIL",
            "onOpenExport()", "onOpenImport()", "CardCaption(CurrencyCopy.DETAIL, dark)",
        )) assertTrue("Your data: $s", s in page)
        // D-234 (decision 100): Export on two lines, Import on three (two cut "added." on the narrow phones).
        assertEquals("Export on two lines", 1, page.split("summaryLines = 2").size - 1)
        assertTrue("Import on three", "parts = listOf(ImportCopy.ENTRY_DETAIL),\n                            trailing = RowTrailing.Chevron, summaryLines = 3," in page)
        assertFalse("no chips", "CurrencyChips(" in page)
        assertFalse("CurrencySettings is gone", "fun CurrencySettings(" in s225("CurrencyChoice.kt"))
        assertEquals("Export your data", YourDataCopy.EXPORT)
        assertEquals("Your whole ledger in one file: CSV to read, or a JSON backup to restore.", YourDataCopy.EXPORT_DETAIL)
    }

    /** D-225 fix wave (M5): Your data reads the map with the account's real state, as Security does (a signed-in item
     *  added to `SettingsMap.items(DATA, ...)` must not vanish here). */
    @Test
    fun your_data_follows_the_account() {
        val hub = s225("AccountHubScreen.kt")
        val page = body225("AccountHubScreen.kt", "private fun YourDataPage(")
        assertTrue("the page takes the state", "private fun YourDataPage(dark: Boolean, signedIn: Boolean," in page)
        assertTrue("and reads the map with it", "SettingsMap.items(SettingsSection.DATA, signedIn)" in page)
        assertFalse("never a fixed signed-out", "signedIn = false" in page)
        assertTrue("the hub passes its own", "YourDataPage(dark, signedIn, onOpenExport, onOpenImport)" in hub)
    }

    /** D-225 fix wave (F6, the look: at font scale 2.0, Security opened at You's scroll offset): You and its pages share
     *  one call site in the shell, so each passes its own scroll state to the page: You's is kept by the shell across
     *  its pushes, every other page's is keyed by its route (fresh, at the top, each time it opens). */
    @Test
    fun a_pushed_you_page_opens_at_its_top() {
        val root = s225("AppRoot.kt")
        val branch = root.substringAfter("\"account\", \"account/profile\", \"account/appearance\", \"account/security\", \"account/data\" -> {")
            .substringBefore("            return\n")
        assertTrue("one scroll state per page",
            "scroll = if (here == \"account\") youScroll else key(here) { androidx.compose.foundation.rememberScrollState() }," in branch)
        assertTrue("You's kept like the tabs', per entry",
            "val youScroll = key(youEntry) { rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) } }" in root)
        // D-225 follow-up (M1, the re-review): You opened afresh from a tab starts at its top, as iOS pushes a new You;
        // coming back to You from its own pages keeps its offset (only a fresh entry bumps the entry).
        assertTrue("var youEntry by rememberSaveable { mutableIntStateOf(0) }" in root)
        assertTrue("a fresh entry is a new entry", Regex("""fun openYou\(\) \{\s*youEntry\+\+\s*route = "account"\s*\}""").containsMatchIn(root))
        assertEquals("every tab root opens You afresh", 4, root.split("onOpenAccount = ::openYou").size - 1)
        assertFalse("no tab root reuses the old offset", "onOpenAccount = { route = \"account\" }" in root)
        assertEquals("the bumps are openYou's alone", 1, root.split("youEntry++").size - 1)
        assertTrue("You's pages come back to it as it was", "AboutScreen(onBack = { route = \"account\" }" in root && "ProfileScreen(vaultPath = vaultPath, onBack = { route = \"account\" })" in root)
        val hub = s225("AccountHubScreen.kt")
        assertTrue("the hub hands it to its page", "side = BarDecisions.PAGE_SIDE_WIDE.dp, scroll = scroll) {" in hub)
        val bar = s225("SkiptiBar.kt")
        val page = bar.substringAfter("fun PushedPage(").substringBefore("\n}\n")
        assertTrue("PushedPage takes it", "scroll: ScrollState = rememberScrollState()," in page)
        assertFalse("and makes no second one", "val scroll = rememberScrollState()" in page)
        assertTrue(".verticalScroll(scroll)" in page && "PushedBar(title, onBack, trailing, scroll, backdrop = backdrop)" in page)
    }
}
