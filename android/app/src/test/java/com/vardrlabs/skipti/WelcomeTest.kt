package com.vardrlabs.skipti

import androidx.compose.ui.geometry.Offset
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.ffiDecodeGroupOp

/**
 * D-245 (U9, U17; decisions 159 to 162, 171, 177 to 181, 192, 197 to 199): the welcome's gate, flow, progress and
 * words, and the name and picture confirm. The twin of iOS's WelcomeTests; [WelcomeCopy] is compared with iOS's table
 * read out of Welcome.swift.
 */
class WelcomeTest {

    private fun store(): LedgerStore {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = Files.createTempDirectory("welcome-test").resolve("ledger-vault.db").toString()
        return LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    private fun src(name: String) = File("src/main/java/com/vardrlabs/skipti/$name").readText()
    private val swift: String by lazy { File("../../ios/Skipti/App/Welcome.swift").readText() }

    private fun ops(store: LedgerStore, match: (GroupOpIn) -> Boolean): Int =
        store.allRecords().count { r -> runCatching { ffiDecodeGroupOp(r.blob) }.getOrNull()?.let(match) == true }

    private fun avatarRecords(store: LedgerStore) = ops(store) { it is GroupOpIn.Avatar }

    /** `You` records that carry a name (a new vault's first read writes your id with no name). */
    private fun youRecords(store: LedgerStore) = ops(store) { it is GroupOpIn.You && !it.name.isNullOrEmpty() }

    /** Fix wave M6: every string literal in Welcome.kt's `object WelcomeCopy`, read from the source (the table as
     *  written, so a word added to it is scanned without a second list to keep). The iOS-name map is not words. */
    private fun copyTableWords(): List<String> {
        val source = src("Welcome.kt")
        val start = source.indexOf("object WelcomeCopy {")
        var body = source.substring(start, source.indexOf("\n}\n", start))
        body = body.substringBefore("    val byIosName") + body.substringAfter("    val byIosName").substringAfter("\n    )", missingDelimiterValue = "")
        return Regex(""""((?:[^"\\]|\\.)*)"""").findAll(body).map { it.groupValues[1] }.toList()
    }

    /** The launch as AppRoot makes it: the vault's read (marking the welcome on a first launch), then the gate. */
    private fun launch(store: LedgerStore): Boolean {
        val opened = VaultRecovery.launch("app/launched", firstLaunch = { WelcomeGate.mark(it) }) { store } as VaultRecovery.Launch.Opened
        return WelcomeGate.atLaunch(opened.launchedBefore) { WelcomeGate.pending(store) }
    }

    @After
    fun tearDown() {
        StartGroupRequest.pending = false
        DoneCenter.clear()
        DoneCenter.attach(null)
    }

    // ---- The gate (decision 177) ----

    @Test
    fun a_new_install_is_welcomed_and_an_old_one_never() {
        val fresh = store()
        assertTrue("a new install's first launch shows it", launch(fresh))
        assertTrue("and the flag is written", WelcomeGate.pending(fresh))
        // Negative: an install that launched before this build has no flag.
        val old = store()
        old.rawKvSet("app/launched", byteArrayOf(1))
        assertFalse("an earlier install never sees the welcome", launch(old))
        assertFalse("and gets no flag", WelcomeGate.pending(old))
        assertTrue(WelcomeGate.shouldShow(launchedBefore = false, pending = false))
        assertTrue(WelcomeGate.shouldShow(launchedBefore = true, pending = true))
        assertFalse(WelcomeGate.shouldShow(launchedBefore = true, pending = false))
        assertFalse("an unreadable flag never blocks", WelcomeGate.atLaunch(true) { throw IllegalStateException("wrong key") })
    }

    @Test
    fun a_killed_welcome_comes_back_and_closing_ends_it() {
        val s = store()
        assertTrue(launch(s))
        assertTrue("killed mid-welcome: the flag is still set, it shows again", launch(s))
        WelcomeGate.closed(WelcomeMode.FIRST_RUN) { WelcomeGate.clear(s) }
        assertFalse(WelcomeGate.pending(s))
        assertFalse("skip or done ended it for good", launch(s))
    }

    @Test
    fun a_replay_never_touches_the_flag() {
        var cleared = 0
        WelcomeGate.closed(WelcomeMode.REPLAY) { cleared++ }
        assertEquals(0, cleared)
        WelcomeGate.closed(WelcomeMode.FIRST_RUN) { cleared++ }
        assertEquals("first run clears, once", 1, cleared)
        assertFalse(WelcomeFlow.clearsFlag(WelcomeMode.REPLAY))
        // The two callers: the root closes first run, About replays.
        val root = src("AppRoot.kt")
        assertTrue("WelcomeLayer(vaultPath, WelcomeMode.FIRST_RUN, welcome)" in root)
        assertTrue("WelcomeGate.closed(WelcomeMode.FIRST_RUN) { WelcomeGate.clear(LedgerStore(vaultPath)) }" in root)
        val about = src("About.kt")
        assertTrue("WelcomeLayer(vaultPath, WelcomeMode.REPLAY, replay)" in about)
        assertFalse("About never clears the flag", "WelcomeMode.FIRST_RUN" in about)
        assertTrue("the row, no summary", "ListRow(WelcomeCopy.REPLAY, dark, trailing = RowTrailing.Chevron)" in about)
    }

    /** D-245 sabotage pin (A5 survived): the root's first-run close clears the flag on EVERY exit (Skip, Look around
     *  first, Start a group): nothing between the exit and the clear can make it depend on which way out was taken. */
    @Test
    fun every_exit_clears_the_flag_at_the_root() {
        val root = src("AppRoot.kt")
        val close = "WelcomeLayer(vaultPath, WelcomeMode.FIRST_RUN, welcome) { exit ->\n" +
            "                    welcomePending = false\n" +
            "                    welcome.close()\n" +
            "                    scope.launch(Dispatchers.IO) {\n" +
            "                        WelcomeGate.closed(WelcomeMode.FIRST_RUN) { WelcomeGate.clear(LedgerStore(vaultPath)) }\n" +
            "                    }\n" +
            "                    WelcomeLook.after(context, exit)\n" +
            "                }"
        assertTrue("the first-run close, whole: the clear runs for every exit", close in root)
        assertEquals("one first-run close", 1, root.split("WelcomeMode.FIRST_RUN, welcome) { exit ->").size - 1)
        // Negative: the exit is read only by the Appearance close and New group (`after`), never around the clear.
        val body = root.substringAfter("WelcomeMode.FIRST_RUN, welcome) { exit ->").substringBefore("WelcomeLook.after(context, exit)")
        assertFalse("exit" in body)
    }

    /** The first launch marks the welcome BEFORE it marks the phone launched (a launch killed between them still
     *  welcomes): a mark that fails leaves the launch unmarked, so the next launch is a first launch again. */
    @Test
    fun the_welcome_is_marked_before_the_launch() {
        val s = store()
        val failed = VaultRecovery.launch("app/launched", firstLaunch = { throw IllegalStateException("killed") }) { s }
        assertTrue(failed is VaultRecovery.Launch.Unavailable)
        assertNull("the launch was not marked", s.rawKvGet("app/launched"))
        assertTrue("so the next launch welcomes", launch(s))
        val owner = src("AccountOwner.kt")
        assertTrue(owner.indexOf("firstLaunch(store)") in 0 until owner.indexOf("store.rawKvSet(key, byteArrayOf(1))"))
        val root = src("AppRoot.kt")
        assertTrue("VaultRecovery.launch(LAUNCHED_KEY, firstLaunch = { WelcomeGate.mark(it) })" in root)
        // After the lock: the welcome is drawn in the branch the lock screen replaces.
        val lock = root.indexOf("} else if (AppLock.locked) {").takeIf { it >= 0 } ?: root.indexOf("else -> if (AppLock.locked) {")
        assertTrue("the lock comes first", lock in 0 until root.indexOf("WelcomeLayer(vaultPath, WelcomeMode.FIRST_RUN"))
        assertTrue("never on a first launch's lock (there is none)", "if (opened.launchedBefore) AppLock.launch(context)" in root)
    }

    // ---- The flow ----

    @Test
    fun the_flow_runs_w1_to_w5_and_skip_ends_every_step_but_done() {
        val seen = mutableListOf(WelcomeStep.WELCOME)
        while (true) seen += WelcomeFlow.next(seen.last()) ?: break
        assertEquals(WelcomeStep.entries.toList(), seen)
        assertEquals(listOf(true, true, true, true, false), WelcomeStep.entries.map(WelcomeFlow::skippable))
        assertEquals(listOf(1, 2, 3, 4, null), WelcomeStep.entries.map(WelcomeFlow::number))
        assertEquals("Step 2 of 4", WelcomeFlow.spoken(WelcomeStep.LOOK))
        assertNull(WelcomeFlow.spoken(WelcomeStep.DONE))
        assertEquals("Nice to meet you, Sam", WelcomeFlow.doneTitle("Sam"))
        assertEquals("You're set", WelcomeFlow.doneTitle(null))
        assertEquals("a blank name is no name", "You're set", WelcomeFlow.doneTitle("   "))
    }

    /** Decision 198: back on W2 to W4 only, one step, and the progress keeps what is done. */
    @Test
    fun back_goes_one_step_and_the_progress_keeps_what_is_done() {
        assertEquals(
            listOf(null, WelcomeStep.WELCOME, WelcomeStep.LOOK, WelcomeStep.NAME, null),
            WelcomeStep.entries.map(WelcomeFlow::previous),
        )
        assertEquals("back from W4 to W3: three steps stay done", 3, WelcomeFlow.shown(completed = 2, before = 3))
        val session = WelcomeSession()
        assertTrue(session.advance(3, now = 1_000L))
        assertFalse("a step back never sweeps back", session.advance(2, now = 2_000L))
        assertEquals(3.0, session.progressTo, 0.0)
        assertEquals(1_000L, session.progressStart)
        val source = src("Welcome.kt")
        assertTrue(".then(if (back != null) Modifier.clickable { s.step = back } else Modifier)" in source)
        assertTrue("semantics { contentDescription = WelcomeCopy.BACK; role = Role.Button }" in source)
        // System Back is the same step back.
        assertTrue("if (previous != null) session.step = previous else onClose(WelcomeExit.SKIPPED)" in source)
    }

    @Test
    fun one_action_at_the_bottom() {
        assertEquals(WelcomeAction.NEXT, WelcomeFlow.action(WelcomeStep.WELCOME, nameDirty = true, staged = true))
        assertEquals(WelcomeAction.NEXT, WelcomeFlow.action(WelcomeStep.LOOK, nameDirty = false, staged = false))
        assertEquals(WelcomeAction.SAVE_NAME, WelcomeFlow.action(WelcomeStep.NAME, nameDirty = true, staged = false))
        assertEquals("a name is not required", WelcomeAction.NEXT, WelcomeFlow.action(WelcomeStep.NAME, nameDirty = false, staged = true))
        assertEquals(WelcomeAction.USE_PICTURE, WelcomeFlow.action(WelcomeStep.PICTURE, nameDirty = false, staged = true))
        assertEquals(WelcomeAction.NEXT, WelcomeFlow.action(WelcomeStep.PICTURE, nameDirty = true, staged = false))
        assertEquals(WelcomeAction.FINISH, WelcomeFlow.action(WelcomeStep.DONE, nameDirty = false, staged = false))
        assertTrue(NameRule.dirty("Sam", saved = null))
        assertFalse("the saved name, trimmed, is not a change", NameRule.dirty(" Sam ", saved = "Sam"))
        assertFalse("a blank field is not a change to save", NameRule.dirty("   ", saved = "Sam"))
        // Decision 200 (kept): W3 hides Next while the field holds an unsaved name, valid or not; Skip and Back remain.
        assertEquals("decision 200: no Next beside an unsaved name", WelcomeAction.SAVE_NAME,
            WelcomeFlow.action(WelcomeStep.NAME, NameRule.dirty("a".repeat(61), saved = null), staged = false))
        assertEquals(WelcomeAction.SAVE_NAME, WelcomeFlow.action(WelcomeStep.NAME, NameRule.dirty("Ann", saved = "Sam"), staged = false))
        assertTrue(WelcomeFlow.skippable(WelcomeStep.NAME))
        assertEquals(WelcomeStep.LOOK, WelcomeFlow.previous(WelcomeStep.NAME))
    }

    // ---- Decisions 192 and 197: the progress ----

    @Test
    fun the_progress_is_the_completed_share() {
        assertEquals(listOf(0.0, 0.25, 0.5, 0.75, 1.0), WelcomeStep.entries.map { WelcomeProgress.value(WelcomeFlow.completed(it)) })
        assertEquals("Save name completes W3", 3, WelcomeFlow.completed(WelcomeStep.NAME, nameSaved = true))
        assertEquals("Use this picture completes W4", 4, WelcomeFlow.completed(WelcomeStep.PICTURE, pictureSaved = true))
        assertEquals("never past the end", 1.0, WelcomeProgress.value(9), 0.0)
        assertEquals(listOf(0.125, 0.375, 0.625, 0.875), (0 until 4).map(WelcomeProgress::position))
        assertEquals(listOf(0.0, 0.125, 0.375, 0.625, 0.875), (0..4).map { WelcomeProgress.lineFill(it.toDouble()) })
        assertEquals(0.25, WelcomeProgress.lineFill(1.5), 1e-9)
        assertEquals(1.0, WelcomeProgress.segmentShare(0, 0.125), 0.0)
        assertEquals(0.0, WelcomeProgress.segmentShare(1, 0.125), 0.0)
        assertEquals(0.5, WelcomeProgress.segmentShare(1, 0.25), 1e-9)
        // Norse (decision 197): ᚠ ᚢ ᚦ ᚨ ᚱ, ᚠ lit from the start, one more per completed step.
        assertEquals(listOf("ᚠ", "ᚢ", "ᚦ", "ᚨ", "ᚱ"), WelcomeProgress.runes)
        assertEquals(listOf(1, 2, 3, 4, 5), (0..4).map { WelcomeProgress.pose(AppTheme.NORSE, 0.0, it.toDouble(), 0.0, true).lit.count { l -> l } })
        assertEquals(listOf(0, 1, 2, 3, 4), (0..4).map { WelcomeProgress.pose(AppTheme.AURORA, 0.0, it.toDouble(), 0.0, true).lit.count { l -> l } })
        assertEquals(listOf(1.0, 1.0, 0.5, 0.0), (0 until 4).map { WelcomeProgress.roadShare(it, 2.5) })
        // Vardr Labs' segments: each its quarter less a gap, centred on its mark.
        for (i in 0 until 4) {
            val (x, w) = WelcomeProgress.segment(i)
            assertEquals(WelcomeProgress.WIDTH * WelcomeProgress.position(i).toFloat(), x + w / 2, 1e-4f)
        }
    }

    @Test
    fun a_still_frame_is_the_lit_end_state() {
        for (theme in AppTheme.entries) {
            val still = WelcomeProgress.pose(theme, 1.0, 2.0, 0.0, still = true)
            assertEquals("$theme", 2.0, still.progress, 0.0)
            assertFalse("$theme: no spark when still", still.spark)
            val lit = if (theme == AppTheme.NORSE) listOf(true, true, true, false, false) else listOf(true, true, false, false)
            assertEquals("$theme", lit, still.lit)
            assertEquals("$theme", lit.map { if (it) WelcomeProgress.REST_GLOW else 0.0 }, still.glow)
            assertEquals("$theme", setOf(1.0), still.scale.toSet())
            // Negative: moving, the same moment's first frame has not reached the new mark, and the spark rides.
            val first = WelcomeProgress.pose(theme, 1.0, 2.0, 0.0, still = false)
            assertEquals("$theme", 1.0, first.progress, 0.0)
            assertEquals("$theme", lit.count { it } - 1, first.lit.count { it })
            assertTrue("$theme", first.spark)
        }
    }

    @Test
    fun the_road_and_the_lights_are_one_motion() {
        assertEquals("the mark lights as the sweep lands on it", WelcomeProgress.SWEEP_SECONDS, WelcomeProgress.crossing(2.0, 1.0, 2.0)!!, 1e-6)
        assertEquals(WelcomeProgress.SWEEP_SECONDS / 2, WelcomeProgress.crossing(1.5, 1.0, 2.0)!!, 1e-6)
        assertNull("a mark lit before is not crossed again", WelcomeProgress.crossing(1.0, 1.0, 2.0))
        val before = WelcomeProgress.pose(AppTheme.NORSE, 1.0, 2.0, WelcomeProgress.SWEEP_SECONDS - 0.01, false)
        val after = WelcomeProgress.pose(AppTheme.NORSE, 1.0, 2.0, WelcomeProgress.SWEEP_SECONDS, false)
        assertFalse(before.lit[2])
        assertTrue("ᚦ lights as the road reaches it", after.lit[2])
        assertTrue("and flares", after.glow[2] > 0.95)
        assertTrue(after.scale[2] > 1.1)
        assertFalse("the spark goes when the sweep lands", after.spark)
        val settled = WelcomeProgress.pose(AppTheme.NORSE, 1.0, 2.0, 10.0, false)
        assertEquals("then settles, luminous", WelcomeProgress.REST_GLOW, settled.glow[2], 1e-3)
        assertEquals("the mock-up's in-out ease", 0.125, WelcomeProgress.eased(0.25), 1e-9)
        assertEquals(0.875, WelcomeProgress.eased(0.75), 1e-9)
        // A moment's door (D-239): Reduce Motion and Less motion still it, power saving never.
        val source = src("Welcome.kt")
        val bar = source.substringAfter("fun WelcomeProgressBar(").substringBefore("\n}\n")
        assertTrue("val motion = rememberMotionState().moment()" in bar)
        assertTrue("MomentPlay(startedAtMs, still = !motion.tick)" in bar)
        assertFalse("no read of power saving", "lowPower" in source)
        // The founder's look: the glow follows the glyph (the text's own blurred shadow), never a round halo.
        assertFalse("no halo disc", "radialGradient" in source)
        val road = source.substringAfter("private fun NorseRoad(").substringBefore("\n}\n")
        assertTrue("shadow = if (lit) Shadow(ring.copy(alpha = pose.glow[i].toFloat()), Offset.Zero, blur) else null" in road)
    }

    /** Decision 197: the road's geometry is the mock-up's: arches up and down in turn, each ending 14 dp short of the
     *  runes' centres (clear of their ink, measured in the bundled font), nothing past the frame. */
    @Test
    fun the_road_winds_between_the_runes_and_never_touches_their_ink() {
        val font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, File("src/main/res/font/vardrrunes_carvedregular.ttf"))
            .deriveFont(WelcomeProgress.RUNE_SIZE)
        val frc = java.awt.font.FontRenderContext(null, true, true)
        val ink = WelcomeProgress.runes.map { font.createGlyphVector(frc, it).visualBounds }
        for (i in 0 until WelcomeProgress.STEPS) {
            val r = WelcomeProgress.road(i)
            assertEquals(WelcomeProgress.runeCentre(i).x + 14f, r.start.x, 0f)
            assertEquals(WelcomeProgress.runeCentre(i + 1).x - 14f, r.end.x, 0f)
            assertEquals("up, down, up, down", WelcomeProgress.BASELINE + (if (i % 2 == 0) -30f else 30f), r.c1.y, 0f)
            assertEquals(16f, r.c1.x - r.start.x, 0f)
            val mid = WelcomeProgress.roadPoint(i, 0.5)
            assertEquals("a cubic's peak is 3/4 of its controls'", 22.5f, kotlin.math.abs(mid.y - WelcomeProgress.BASELINE), 1e-4f)
            assertTrue("road $i starts clear of its rune's ink", ink[i].width / 2 < WelcomeProgress.ROAD_INSET)
            assertTrue("road $i ends clear of the next rune's ink", ink[i + 1].width / 2 < WelcomeProgress.ROAD_INSET)
            assertTrue(r.start.x >= 0f && r.end.x <= WelcomeProgress.NORSE_WIDTH)
        }
        assertEquals("the row centred in its frame", WelcomeProgress.NORSE_WIDTH, WelcomeProgress.runeCentre(0).x + WelcomeProgress.runeCentre(4).x, 0f)
        assertEquals("the spark reaches the road's end", WelcomeProgress.road(1).end.x, WelcomeProgress.roadPointByShare(1, 1.0).x, 0.01f)
    }

    /** Norse's runes sit on their places by their INK: the placement puts the ink's centre on the place, and the
     *  bundled font's bearings are uneven, so centring by the advance would miss (the negative). */
    @Test
    fun norses_runes_sit_on_their_places_by_their_ink() {
        val at = RuneInk.topLeft(inkLeft = 3f, inkTop = 10f, inkRight = 13f, inkBottom = 40f, centre = Offset(30f, 50f))
        assertEquals(Offset(22f, 25f), at)
        val font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, File("src/main/res/font/vardrrunes_carvedregular.ttf"))
            .deriveFont(WelcomeProgress.RUNE_SIZE)
        val frc = java.awt.font.FontRenderContext(null, true, true)
        val shifts = WelcomeProgress.runes.map {
            val g = font.createGlyphVector(frc, it)
            kotlin.math.abs(g.getGlyphMetrics(0).advance / 2 - g.visualBounds.centerX.toFloat())
        }
        assertTrue("the bearings are uneven: centring by the advance would miss ($shifts)", shifts.max() > 0.5f)
        val road = src("Welcome.kt").substringAfter("private fun NorseRoad(").substringBefore("\n}\n")
        assertTrue("it.getPathForRange(0, it.layoutInput.text.length).getBounds()" in road)
        assertTrue("RuneInk.topLeft(ink.left, ink.top, ink.right, ink.bottom, centre)" in road)
    }

    // ---- Words ----

    /** iOS's `WelcomeCopy` is the table: every `static let` word, the five places and the three templates, equal here
     *  (a word missing or extra on either side fails). */
    @Test
    fun the_words_are_ios_words() {
        val start = swift.indexOf("enum WelcomeCopy {")
        val body = swift.substring(start, swift.indexOf("\n}\n", start))
        val lets = Regex("""(?m)^    static let (\w+) = "((?:[^"\\]|\\.)*)"$""").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\\"", "\"") }
        assertTrue("found iOS's words", lets.size >= 30)
        assertEquals("the same names", lets.keys, WelcomeCopy.byIosName.keys)
        for ((k, v) in lets) assertEquals(k, v, WelcomeCopy.byIosName[k])
        val places = Regex("""static let places = \[(.*)]""").find(body)!!.groupValues[1]
        assertEquals(Regex(""""([^"]*)"""").findAll(places).map { it.groupValues[1] }.toList(), WelcomeCopy.PLACES)
        fun template(name: String) = Regex("""static func $name\(_ \w+: \w+\) -> String \{ "((?:[^"\\]|\\.)*)" \}""").find(body)!!
            .groupValues[1].replace("\\\"", "\"").replace(Regex("""\\\(\w+\)"""), "{}")
        assertEquals(template("step").replace("{}", "3"), WelcomeCopy.step(3))
        assertEquals(template("nameCaption").replace("{}", "Sam"), WelcomeCopy.nameCaption("Sam"))
        assertEquals(template("doneTitle").replace("{}", "Sam"), WelcomeCopy.doneTitle("Sam"))
        // Pinned, so a change on both sides at once is still seen.
        assertEquals("Welcome to SKIPTI", WelcomeCopy.WELCOME_TITLE)
        assertEquals(WelcomeCopy.WELCOME_TITLE, WelcomeCopy.WELCOME_LEAD + " " + WelcomeCopy.WORDMARK)
        assertEquals("Names can be up to 60 characters.", WelcomeCopy.ERR_LONG)
        assertEquals("Show the welcome again", WelcomeCopy.REPLAY)
        assertEquals(AppearanceCopy.NORSE_ALWAYS_DARK, WelcomeCopy.NORSE_DARK)
        assertEquals(ProfileCopy.PHONE_ONLY, WelcomeCopy.PICTURE_FOOT)
        assertEquals("Could not save your name.", WelcomeCopy.NAME_NOT_SAVED)
        assertEquals("Could not save your picture.", WelcomeCopy.PICTURE_NOT_SAVED)
        for (w in WelcomeCopy.byIosName.values + copyTableWords()) {
            assertFalse("a dash: $w", '—' in w || '–' in w)
        }
    }

    /** Plan 1.6 (GUARANTEES #5): no welcome word names an account, sign-in, sync, the cloud, Google, Apple or joining. */
    @Test
    fun no_welcome_word_names_an_account() {
        // Fix wave M6: the whole table as written, plus the other tables the welcome draws from.
        val words = copyTableWords() + listOf(DoneCopy.NAME_SAVED, DoneCopy.PICTURE_SAVED) + AppTheme.entries.map { it.label }
        assertTrue(words.size > 40)
        assertTrue("the parse reads the table", "Everything stays on this phone." in words)
        assertTrue("templates too", "Step \$n of 4" in words)
        assertFalse("not the iOS-name keys", "photoUnreadable" in words)
        assertEquals(emptyList<String>(), WelcomeG5.hits(words))
        // Negative: the scan sees one, whatever its case.
        assertEquals(listOf("SIGN in to Sync"), WelcomeG5.hits(listOf("Pick a look", "SIGN in to Sync")))
        assertEquals(listOf("Join a group"), WelcomeG5.hits(listOf("Join a group")))
        // No word is drawn around the table: every Text and action in the welcome's screens and the confirm reads a name.
        assertEquals(emptyList<String>(), literalsDrawn(src("Welcome.kt").substringAfter("// MARK: - The screens")))
        assertEquals(emptyList<String>(), literalsDrawn(src("ProfileConfirm.kt")))
        // Negative: the guard sees a literal, on its line or the next.
        assertEquals(2, literalsDrawn("Text(\"Sign in\") PrimaryAction(\n    \"Join\", dark) Text(WelcomeCopy.NEXT)").size)
        // And no welcome screen routes to sign-in or Join.
        val source = src("Welcome.kt")
        for (banned in listOf("SignInScreen", "JoinScreen", "InviteCopy", "ImportScreen", "Account.signIn", "onJoin", "route = ")) {
            assertFalse("Welcome.kt: $banned", banned in source)
        }
    }

    // ---- The theme tap (decision 160) ----

    @Test
    fun a_tile_takes_appearances_path_and_closing_is_appearances_close() {
        val writes = mutableListOf<AppTheme>()
        WelcomeLook.choose(AppTheme.NORSE) { writes += it }
        assertEquals(listOf(AppTheme.NORSE), writes)
        var icons = 0
        var closes = 0
        WelcomeLook.closed(applyIcon = { icons++ }, pageClosed = { closes++ })
        assertEquals(1, icons)
        assertEquals(1, closes)
        // The same write Appearance's tiles make, and the same hooks its page's close runs.
        val hub = src("AccountHubScreen.kt")
        assertTrue("Appearance.setTheme(context, theme)" in hub)
        assertTrue("AppIconSwitcher.applyIfNeeded(context)" in hub)
        val welcome = src("Welcome.kt")
        assertTrue("fun choose(context: Context, theme: AppTheme) = choose(theme) { Appearance.setTheme(context, it) }" in welcome)
        assertTrue("closed(applyIcon = { AppIconSwitcher.applyIfNeeded(context) }, pageClosed = { SettingsSync.pageClosed(context) })" in welcome)
        assertTrue("ThemeCard(theme, current == theme, dark, Modifier.fillMaxWidth()) { onChoose(theme) }" in welcome)
        assertTrue("WelcomeStep.LOOK -> LookPage(dark) { WelcomeLook.choose(context, it) }" in welcome)
    }

    @Test
    fun start_a_group_asks_the_shell_for_new_group() {
        // Without a context the Appearance close cannot run; the request is the last step of `after`.
        assertFalse(StartGroupRequest.pending)
        val after = src("Welcome.kt").substringAfter("fun after(context: Context, exit: WelcomeExit?) {").substringBefore("\n    }\n")
        assertTrue("closed(context)" in after)
        assertTrue("if (exit == WelcomeExit.START_GROUP) StartGroupRequest.pending = true" in after)
        val shell = src("AppRoot.kt")
        val listen = shell.substringAfter("LaunchedEffect(StartGroupRequest.pending) {").substringBefore("\n    }\n")
        assertTrue("route = \"new-group\"" in listen)
        assertTrue("selectedName = HomeTab.BALANCE.name" in listen)
        assertTrue("StartGroupRequest.pending = false" in listen)
    }

    // ---- The name (decision 179) ----

    @Test
    fun names_are_refused_in_words_and_nothing_is_written() {
        val s = store()
        var shares = 0
        var changes = 0
        val spy = ProfileSave.Effects(changed = { changes++ }, share = { shares++ })
        assertEquals(ProfileSave.NameOutcome.Refused("Names can be up to 60 characters."), ProfileSave.name("a".repeat(61), s, spy))
        assertEquals(ProfileSave.NameOutcome.Refused("Add a name first."), ProfileSave.name("   ", s, spy))
        assertEquals(ProfileSave.NameOutcome.Refused("That name has a character SKIPTI can't use."), ProfileSave.name("Sam\u0007", s, spy))
        // 31 emoji are 62 UTF-16 units: the rules' count, so it would not be shared.
        assertEquals(NameProblem.TOO_LONG, NameRule.check("😀".repeat(31)))
        assertNull("nothing written", s.loadSelf()?.name)
        assertEquals(0, youRecords(s))
        assertEquals(0, shares + changes)
        val sixty = "b".repeat(60)
        assertEquals(ProfileSave.NameOutcome.Saved(sixty), ProfileSave.name(sixty, s, spy))
        assertEquals(sixty, s.loadSelf()?.name)
        assertEquals(1, youRecords(s))
        assertEquals(1, shares)
        assertEquals(1, changes)
        assertNotNull("a saved name is always shareable", ProfileShare.shareable(sixty))
        assertFalse("no raw core text", "That name cannot be used" in src("Profile.kt"))
    }

    /** The brief's twin defect: the keyboard's Done saves the name, on Profile and in the welcome. */
    @Test
    fun the_keyboards_done_saves_the_name() {
        val field = src("ListRows.kt").substringAfter("fun HairlineField(").substringBefore("\n}\n")
        assertTrue("the hairline field passes its actions on", "keyboardActions = actions," in field)
        val profile = src("Profile.kt").substringAfter("fun ProfileScreen(")
        assertTrue("actions = KeyboardActions(onDone = { saveName(returned = true) })," in profile)
        assertTrue("imeAction = ImeAction.Done" in profile)
        val welcome = src("Welcome.kt")
        assertTrue("actions = KeyboardActions(onDone = { onReturn() })," in welcome)
        assertTrue("WelcomeStep.NAME -> NamePage(s, dark, onSave = { saveName() }, onReturn = { saveName(returned = true) })" in welcome)
        // The field's own default stays inert: a caller opts in to Done (not a negative, a presence check).
        assertTrue("actions: KeyboardActions = KeyboardActions.Default," in field)
    }

    // ---- The picture (decision 161) ----

    /** Fix wave M6: through the real seam ([ProfileSave.answer], which Profile's sheet and the welcome's Use call), with
     *  a real vault: Not now writes, nudges and shares nothing and never opens the vault; Use writes once; a second Use
     *  has nothing staged. */
    @Test
    fun a_tap_stages_and_only_use_writes_once() {
        val s = store()
        var opened = 0
        val vault = { opened++; s }
        var shares = 0
        var changes = 0
        val spy = ProfileSave.Effects(changed = { changes++ }, share = { shares++ })
        var stage = PictureStage().choose(Avatar.Emoji("🦊")).choose(Avatar.Emoji("🐼"))
        assertEquals(Avatar.Emoji("🐼"), stage.staged)
        val notNow = ProfileSave.answer(PictureAnswer.NOT_NOW, stage, vault, spy)
        stage = notNow.stage
        assertNull(notNow.written)
        assertNull(stage.staged)
        assertNull(stage.done)
        assertEquals("Not now never opens the vault", 0, opened)
        assertEquals("Not now writes nothing", 0, avatarRecords(s))
        assertEquals("and nudges and shares nothing", 0, shares + changes)
        assertEquals(Avatar.None, s.loadAvatar())

        stage = stage.choose(Avatar.Emoji("🦉"))
        val use = ProfileSave.answer(PictureAnswer.USE, stage, vault, spy)
        stage = use.stage
        assertEquals(Avatar.Emoji("🦉"), use.written)
        assertEquals("one record", 1, avatarRecords(s))
        assertEquals("one share request", 1, shares)
        assertEquals(1, changes)
        assertEquals(Avatar.Emoji("🦉"), s.loadAvatar())
        assertEquals(DoneCopy.PICTURE_SAVED, stage.done)
        // Negative: a second Use finds nothing staged and writes nothing.
        assertNull(ProfileSave.answer(PictureAnswer.USE, stage, vault, spy).written)
        assertEquals(1, avatarRecords(s))
        assertEquals(1, shares)
        assertEquals(DoneCopy.PICTURE_REMOVED, stage.used(Avatar.None).done)

        // Profile's grid and picker stage; the one write is ProfileSave's.
        val profile = src("Profile.kt")
        val screen = profile.substringAfter("fun ProfileScreen(")
        assertTrue(".clickable { stagePicture(Avatar.Emoji(e)) }" in screen)
        assertTrue("stagePicture(Avatar.Photo(stored))" in screen)
        assertFalse("the old save-on-tap is gone", "setAvatar(" in profile)
        assertFalse("no write of its own", ".saveAvatar(" in screen)
        assertTrue("confirming?.let { staged ->" in screen)
        // The sheet's two answers (and Remove picture) are the seam above, and nothing else.
        assertTrue("onUse = { confirm(PictureAnswer.USE) },\n            onNotNow = { confirm(PictureAnswer.NOT_NOW) }," in screen)
        assertEquals(2, screen.split("ProfileSave.answer(answer, ").size - 1)
        assertFalse("no picture write but the answer", "ProfileSave.picture(" in screen)
        assertEquals(1, src("ProfileConfirm.kt").split("store.saveAvatar(").size - 1)
        assertFalse(".saveAvatar(" in src("Welcome.kt"))
    }

    /** Decision 199: inside the welcome a save plays the success haptic and posts no toast; Profile's posts its toast. */
    @Test
    fun the_welcomes_saves_post_no_toast_and_profiles_do() {
        Appearance.setForTest()
        Haptics.resetForTest()
        val played = mutableListOf<Int>()
        DoneCenter.attach(HapticTarget { played += it })
        val center = DoneMomentCenter()
        ProfileSave.done(ProfileSave.nameMoment, ProfileSave.Feedback.HAPTIC, center, now = 10_000L)
        assertNull("no toast in the welcome", center.current)
        assertEquals("the success haptic plays", listOf(HapticKind.constant(HapticKind.SUCCESS, android.os.Build.VERSION.SDK_INT)), played)
        ProfileSave.done(ProfileSave.pictureMoment(Avatar.Emoji("🦊")), ProfileSave.Feedback.TOAST, center, now = 20_000L)
        assertEquals("Profile's toast", DoneMoments.line(DoneCopy.PICTURE_SAVED), center.current)
        val welcome = src("Welcome.kt")
        assertEquals(2, welcome.split("ProfileSave.Feedback.HAPTIC)").size - 1)
        assertFalse("Feedback.TOAST" in welcome)
        assertFalse("no toast host in the welcome", "DoneToastHost" in welcome)
        assertTrue("the shell's toast is covered while it shows", "CoversShellToast()" in welcome)
        assertFalse("Profile keeps its toast", "Feedback.HAPTIC" in src("Profile.kt"))
    }

    // ---- The look's arg and the replay row ----

    @Test
    fun the_welcome_arg_is_the_ordinary_app_and_debug_only() {
        assertTrue(ProofArgs.welcome("--welcome", debuggable = true))
        assertTrue(ProofArgs.welcome("--emulators --welcome", debuggable = true))
        assertFalse("never in a release build", ProofArgs.welcome("--welcome", debuggable = false))
        assertFalse(ProofArgs.welcome("--emulators", debuggable = true))
        assertNull("not a proof: the lock and the gate run", ProofArgs.proof("--welcome", debuggable = true))
    }

    // ---- Fix wave ----

    /** Fix wave M6: a string literal drawn straight into a view, which the copy table scan cannot see. */
    private fun literalsDrawn(source: String): List<String> =
        Regex("""\b(Text|PrimaryAction|SecondaryAction|WelcomeDetail|WelcomeLine|WelcomeTitle)\(\s*"""").findAll(source).map { it.value }.toList()

    /** Fix wave I1: the welcome starts fresh every time it is shown. A session that closed (first run's exit, kept by the
     *  saver) opens at W1 with nothing in it, as after an erase; one still open (a re-made activity re-runs the launch)
     *  keeps its place. */
    @Test
    fun the_welcome_starts_fresh_every_time_it_is_shown() {
        val s = WelcomeSession().apply {
            step = WelcomeStep.DONE
            name = "Sam"
            savedName = "Sam"
            nameDoneFor = "Sam"
            saved = Avatar.Emoji("🦊")
            stage = PictureStage().used(Avatar.Emoji("🦊"))
            loaded = true
            advance(4, now = 1_000L)
        }
        // Negative: still open (an activity re-made mid-welcome), the launch's opening keeps everything.
        s.opening()
        assertEquals(WelcomeStep.DONE, s.step)
        assertEquals("Sam", s.name)
        assertEquals(4.0, s.progressTo, 0.0)
        // Closed (first run's exit), then shown again (an erase made a new first run): fresh.
        s.close()
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val restored = WelcomeSession.Saver.restore(with(WelcomeSession.Saver) { scope.save(s) }!!)!!
        for (x in listOf(s, restored)) {
            x.opening()
            assertEquals(WelcomeStep.WELCOME, x.step)
            assertEquals("", x.name)
            assertNull(x.savedName)
            assertEquals(Avatar.None, x.saved)
            assertNull(x.nameDoneFor)
            assertEquals(PictureStage(), x.stage)
            assertEquals(0.0, x.progressTo, 0.0)
            assertFalse("the new vault's name is read again", x.loaded)
            assertEquals(WelcomeAction.NEXT, x.action)
        }
        // Wired: the launch opens it, the first-run exit closes it.
        val root = src("AppRoot.kt")
        val goOn = root.substringAfter("fun goOn(opened: VaultRecovery.Launch.Opened, welcomeShows: Boolean) {").substringBefore("\n    }\n")
        assertTrue("if (welcomeShows) welcome.opening()" in goOn)
        assertTrue(goOn.indexOf("welcome.opening()") < goOn.indexOf("welcomePending = welcomeShows"))
        assertTrue("welcome) { exit ->\n                    welcomePending = false\n                    welcome.close()" in root)
    }

    /** Fix wave I2: "Everything stays on this phone." (W1) and "Your name and picture stay on this phone." (W4) show only
     *  without an account, as Profile's footnotes do; a signed-in replay shows neither. */
    @Test
    fun the_phone_only_lines_show_only_without_an_account() {
        assertEquals(WelcomeCopy.WELCOME_FOOT, WelcomeFlow.foot(WelcomeStep.WELCOME, signedIn = false))
        assertEquals(WelcomeCopy.PICTURE_FOOT, WelcomeFlow.foot(WelcomeStep.PICTURE, signedIn = false))
        assertEquals("signed in: no foot on any step", List(5) { null }, WelcomeStep.entries.map { WelcomeFlow.foot(it, signedIn = true) })
        assertFalse("Profile's own rule", WelcomeCopy.PICTURE_FOOT in ProfileCopy.footnotes(true))
        val source = src("Welcome.kt")
        assertTrue("WelcomeFlow.foot(WelcomeStep.WELCOME, signedIn)?.let" in source)
        assertTrue("WelcomeFlow.foot(WelcomeStep.PICTURE, signedIn)?.let" in source)
        assertTrue("WelcomeStep.WELCOME -> WelcomePage(dark, signedIn)" in source)
        // Negative: neither line is drawn by its name any more.
        assertFalse("WelcomeDetail(WelcomeCopy.WELCOME_FOOT" in source)
        assertFalse("WelcomeDetail(WelcomeCopy.PICTURE_FOOT" in source)
    }

    /** Fix wave I3: at the largest font scale the bar grows (never a fixed 44), a place label wraps to two lines, and at
     *  the large sizes the places stack one per row. */
    @Test
    fun the_largest_text_grows_and_nothing_clips() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4)), WelcomeFlow.placeRows(stacked = false))
        assertEquals((0 until 5).map { listOf(it) }, WelcomeFlow.placeRows(stacked = true))
        assertTrue(TextSize.stacks(2.0f))
        assertFalse(TextSize.stacks(1.0f))
        val source = src("Welcome.kt")
        val screen = source.substringAfter("fun WelcomeScreen(").substringBefore("\n}\n")
        assertTrue("Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp)" in screen)
        assertFalse("a fixed height clips Skip at the largest size", ".height(44.dp)" in screen)
        val place = source.substringAfter("private fun WelcomePlace(").substringBefore("\n}\n")
        assertTrue("maxLines = 2," in place)
        assertFalse("one line cut Settle up", "maxLines = 1," in place)
        assertTrue("val stacked = TextSize.stacks(LocalDensity.current.fontScale)" in source)
        assertTrue("WelcomeFlow.placeRows(stacked).forEach { row ->" in source)
        // W4: "More" takes its own row at the large sizes (a seventh of the row cut it).
        val choices = src("ProfileConfirm.kt").substringAfter("fun EmojiChoices(")
        assertTrue("val stacked = TextSize.stacks(LocalDensity.current.fontScale)" in choices)
        assertTrue("if (!stacked) more(Modifier.weight(1f))" in choices)
        assertTrue("if (stacked) more(Modifier.fillMaxWidth())" in choices)
    }

    /** Fix wave M1: Done on the keyboard saves through the same seam, but never the saved name again (nothing written,
     *  nudged or shared); an empty field still says "Add a name first.". */
    @Test
    fun done_does_not_save_an_unchanged_name_again() {
        val s = store()
        var shares = 0
        var changes = 0
        val spy = ProfileSave.Effects(changed = { changes++ }, share = { shares++ })
        assertEquals(ProfileSave.NameOutcome.Saved("Sam"), ProfileSave.returned("Sam", null, s, spy))
        assertEquals(1, youRecords(s))
        // Negative: the same name (trimmed) on Done writes nothing.
        assertNull(ProfileSave.returned(" Sam ", "Sam", s, spy))
        assertEquals("no second record", 1, youRecords(s))
        assertEquals(1, shares)
        assertEquals(1, changes)
        assertEquals(ProfileSave.NameOutcome.Refused(WelcomeCopy.ERR_EMPTY), ProfileSave.returned("  ", "Sam", s, spy))
        assertEquals(ProfileSave.NameOutcome.Saved("Ann"), ProfileSave.returned("Ann", "Sam", s, spy))
        assertEquals(2, youRecords(s))
        for (file in listOf("Welcome.kt", "Profile.kt")) {
            assertTrue(file, "if (returned) ProfileSave.returned(typed, saved, store, effects) else ProfileSave.name(typed, store, effects)" in src(file))
        }
    }

    /** Fix wave M2: the saves run on IO, so one at a time: a second tap while one is in flight does nothing. */
    @Test
    fun a_quick_double_tap_saves_once() {
        val latch = SaveLatch() // D-235's
        assertTrue(latch.begin())
        assertTrue(latch.busy)
        assertFalse("a second tap while the first save runs", latch.begin())
        latch.release()
        assertFalse(latch.busy)
        assertTrue("the next save after it", latch.begin())
        // Every save in the welcome and on Profile takes the latch first and lets it go however it ends.
        val welcome = src("Welcome.kt").substringAfter("fun WelcomeScreen(")
        assertEquals(2, welcome.split("if (!s.saving.begin()) return").size - 1)
        assertEquals(2, welcome.split("} finally {\n                s.saving.release()").size - 1)
        val profile = src("Profile.kt").substringAfter("fun ProfileScreen(")
        assertEquals(2, profile.split("if (!saving.begin()) return").size - 1)
        assertEquals(2, profile.split("} finally {\n                saving.release()").size - 1)
    }

    /** Fix wave M4: a vault failure is said in words, never the raw error, in the welcome and the picture saves. */
    @Test
    fun failures_are_said_in_words() {
        val welcome = src("Welcome.kt")
        val confirm = src("ProfileConfirm.kt")
        for ((file, source) in listOf("Welcome.kt" to welcome, "ProfileConfirm.kt" to confirm)) {
            assertFalse(file, "failure.message" in source)
            assertFalse(file, "error.message" in source)
            assertFalse(file, "e.message" in source)
        }
        assertTrue(".onFailure { s.pictureError = WelcomeCopy.PICTURE_NOT_SAVED }" in welcome)
        assertTrue("NameOutcome.Refused(WelcomeCopy.NAME_NOT_SAVED)" in confirm)
        val profile = src("Profile.kt").substringAfter("fun confirm(answer: PictureAnswer) {").substringBefore("\n    }\n")
        assertTrue("error = WelcomeCopy.PICTURE_NOT_SAVED" in profile)
        assertFalse("failure.message" in profile)
    }

    /** Fix wave M7: no dead code left behind (the flow's unused step count). */
    @Test
    fun no_dead_code() {
        val flow = src("Welcome.kt").substringAfter("object WelcomeFlow {").substringBefore("\n}\n")
        assertFalse("const val STEPS" in flow)
        assertFalse("welcomeScreens" in src("Welcome.kt"))
    }

    /** Fix wave M8: the page change is spoken, "Step n of 4, <title>" (W5 its title alone), by a polite live region on
     *  the progress; iOS posts the same words. */
    @Test
    fun the_step_change_is_spoken() {
        assertEquals(
            listOf("Step 1 of 4, Welcome to SKIPTI", "Step 2 of 4, Pick a look", "Step 3 of 4, Your name", "Step 4 of 4, Your picture", "Nice to meet you, Sam"),
            WelcomeStep.entries.map { WelcomeFlow.announcement(it, "Sam") },
        )
        assertEquals("You're set", WelcomeFlow.announcement(WelcomeStep.DONE, null))
        val source = src("Welcome.kt")
        assertTrue("Modifier.clearAndSetSemantics { contentDescription = spoken; liveRegion = LiveRegionMode.Polite }" in source)
        assertTrue("WelcomeProgressBar(s.progressFrom, s.progressTo, s.progressStart, WelcomeFlow.announcement(to, s.savedName), dark)" in source)
        assertTrue("iOS posts the same", "AccessibilityNotification.Announcement(WelcomeFlow.announcement(new, name: savedName)).post()" in swift)
    }

    /** Fix wave (twins): the step change is ONE crossfade of iOS's length and curve; a control both steps draw stays,
     *  one only the arriving step draws fades in, one only the leaving step draws fades out; the chrome reads the drawn
     *  step, never the session's step ahead of it. */
    @Test
    fun the_step_change_is_ones_crossfade_like_ios() {
        assertTrue("iOS's 0.25 s ease-in-out", ".animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: step)" in swift)
        assertEquals(250, WelcomeFade.MILLIS)
        assertEquals(androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 0.58f, 1f), WelcomeFade.EASING)
        assertEquals("no change yet: the drawn page is whole", 1f, WelcomeFade.progress(fade = 0f, turn = 0), 0f)
        assertEquals("a change starts from the leaving page", 0f, WelcomeFade.progress(fade = 2f, turn = 3), 0f)
        assertEquals(0.5f, WelcomeFade.progress(fade = 2.5f, turn = 3), 0f)
        assertEquals(1f, WelcomeFade.progress(fade = 3f, turn = 3), 0f)
        assertEquals("Skip on W1 and W2 stays put", 1f, WelcomeFade.slot(leaving = true, arriving = true, t = 0.3f), 0f)
        assertEquals("Back arriving on W2 fades in", 0.3f, WelcomeFade.slot(leaving = false, arriving = true, t = 0.3f), 0f)
        assertEquals("Skip leaving on W5 fades out", 0.7f, WelcomeFade.slot(leaving = true, arriving = false, t = 0.3f), 0.0001f)
        assertEquals(0f, WelcomeFade.slot(leaving = false, arriving = false, t = 0.3f), 0f)
        val screen = src("Welcome.kt").substringAfter("fun WelcomeScreen(").substringBefore("\n}\n")
        assertTrue("one gated animation", "if (reduce) snap() else tween(WelcomeFade.MILLIS, easing = WelcomeFade.EASING)" in screen)
        assertTrue("both pages drawn at once", "listOfNotNull(leaving, to).forEach { page ->" in screen)
        // Negative: nothing in the chrome or the bottom reads the session's step (it changes before the fade starts).
        val chrome = screen.substringAfter("Column(Modifier.fillMaxSize().auroraBackground(dark)) {")
        for (early in listOf("WelcomeFlow.previous(s.step)", "WelcomeFlow.skippable(s.step)", "s.action")) assertFalse(early, early in chrome)
        assertTrue("WelcomeFlow.previous(to)" in chrome)
        assertTrue("WelcomeFlow.skippable(to)" in chrome)
        assertFalse("the old out-then-in fade is gone", "finishedListener" in screen)
    }

    /** Fix wave (twins): the picture sheet's ground is the theme's page art, as iOS's sheet draws `.auroraBackground()`,
     *  edge to edge of the sheet: its drag handle on the art and the navigation bar's inset inside it. */
    @Test
    fun the_picture_sheet_stands_on_the_page_art_like_ios() {
        assertTrue(".auroraBackground()\n            .navigationTitle(WelcomeCopy.sheetTitle)" in File("../../ios/Skipti/App/ProfileConfirm.swift").readText())
        val sheet = src("ProfileConfirm.kt").substringAfter("fun PictureConfirmSheet(").substringBefore("\n}\n")
        assertTrue("Column(Modifier.fillMaxWidth().auroraBackground(inDark).navigationBarsPadding()) {" in sheet)
        assertTrue("dragHandle = null," in sheet)
        assertTrue("BottomSheetDefaults.DragHandle(" in sheet)
        assertTrue("contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) }," in sheet)
        // Negative: the old surface ground is gone.
        assertFalse("containerColor = Tok.surface(dark)" in sheet)
    }
}
