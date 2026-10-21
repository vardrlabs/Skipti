package com.vardrlabs.skipti

import android.view.HapticFeedbackConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-224 (E3, done moments and haptics): the twin of iOS's DoneMomentsTests for the pure decisions, the copy and the
 * haptic door, plus Android's own: the SDK's haptic constants by name, the one door over the main source set, no
 * vibration permission, and parity with DoneMoments.swift's copy.
 */
class DoneMomentsTest {
    @After
    fun reset() {
        Appearance.setForTest()
        DoneCenter.clear()
        DoneCenter.attach(null)
        Haptics.resetForTest()
    }

    // ---- the switch ----

    /** Design 2: on by default; a stored off is off (the negative); the key sits with the other appearance choices. */
    @Test
    fun the_haptics_setting_defaults_on() {
        assertTrue(HapticsSetting.parse(null))
        assertFalse(HapticsSetting.parse(false))
        assertTrue(HapticsSetting.parse(true))
        assertEquals("haptics", HapticsSetting.STORAGE_KEY)
    }

    /** Design 2: `Appearance.haptics` is the live switch, on unless told otherwise; `load` reads the stored key through
     *  `HapticsSetting.parse` and `setHaptics` writes it. */
    @Test
    fun appearance_holds_the_switch() {
        Appearance.setForTest()
        assertTrue(Appearance.haptics)
        Appearance.setForTest(haptics = false)
        assertFalse(Appearance.haptics)
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/Appearance.kt").readText()
        val load = src.substringAfter("fun load(context: Context) {").substringBefore("\n    }\n")
        assertTrue("load reads the switch", "HapticsSetting.parse(" in load && "HapticsSetting.STORAGE_KEY" in load)
        val set = src.substringAfter("fun setHaptics(context: Context, on: Boolean) {").substringBefore("\n    }\n")
        // D-244 (ADR-0043), amended on purpose: through the one write of a synced setting, which keeps it under the same
        // key as a Boolean (SettingsSyncTest pins the table and the funnel).
        assertTrue("setHaptics writes through the funnel", "SettingsSync.set(context, SettingsSync.HAPTICS, SettingsSync.encode(on))" in set)
        assertEquals(
            SettingsSync.Synced(SettingsSync.HAPTICS, SettingsSync.Kind.FLAG, "skipti-appearance", HapticsSetting.STORAGE_KEY),
            SettingsSync.entry(SettingsSync.HAPTICS),
        )
    }

    /** Design 2: the switch silences all three kinds (the negative), and lets all three through when on. */
    @Test
    fun the_switch_silences_every_kind() {
        for (kind in HapticKind.entries) {
            assertFalse("$kind off", Haptics.plays(kind, enabled = false))
            assertTrue("$kind on", Haptics.plays(kind, enabled = true))
        }
        assertEquals(3, HapticKind.entries.size)
    }

    /** Design 3: a success within 1.0 s of the previous one is skipped; the first always plays. */
    @Test
    fun the_success_gate() {
        assertTrue(HapticGate.allows(lastSuccess = null, now = 5.0))
        assertFalse(HapticGate.allows(lastSuccess = 1.0, now = 1.99))
        assertFalse(HapticGate.allows(lastSuccess = 1.0, now = 1.0))
        assertTrue(HapticGate.allows(lastSuccess = 1.0, now = 2.0))
        assertTrue(HapticGate.allows(lastSuccess = 1.0, now = 9.0))
    }

    /** Design 3: small at once; big when the check lands (the pop's start, 0.7 s), or at once when still. */
    @Test
    fun the_haptic_timing() {
        assertEquals(0.0, DoneHapticTiming.delay(big = false, still = false), 0.0)
        assertEquals(0.0, DoneHapticTiming.delay(big = false, still = true), 0.0)
        assertEquals(0.0, DoneHapticTiming.delay(big = true, still = true), 0.0)
        assertEquals(0.7, DoneHapticTiming.delay(big = true, still = false), 0.0)
    }

    // ---- the moments ----

    /** Design 7: the first group (one named group after the create) is the big overlay with the group's detail; any
     *  other count is the toast; a failed read (null or 0) is never big without proof. */
    @Test
    fun the_first_group_is_big_and_no_other() {
        val detail = listOf("4 people", "INR")
        assertEquals(
            DoneMoment.Big(BigMoment(BigKind.FIRST_GROUP, "Group created", listOf("Goa trip", "4 people", "INR"))),
            DoneMoments.groupCreated(namedCountAfter = 1, name = "Goa trip", detailParts = detail),
        )
        val small = DoneMoment.Small(DoneLine("Group created", listOf("Goa trip")))
        assertEquals(small, DoneMoments.groupCreated(namedCountAfter = 2, name = "Goa trip", detailParts = detail))
        assertEquals(small, DoneMoments.groupCreated(namedCountAfter = 0, name = "Goa trip", detailParts = detail))
        assertEquals(small, DoneMoments.groupCreated(namedCountAfter = null, name = "Goa trip", detailParts = detail))
        assertEquals(listOf(BigKind.FIRST_GROUP, BigKind.SIGNED_IN, BigKind.JOINED, BigKind.ERASED), BigKind.entries)
    }

    /** Design 5: the toast's bottom edge above the bar row's bottom when the bar shows, else 16 above the inset. Fix wave
     *  (F5): iOS's 104 (art.py section 39) plus the 6 dp Android's row is taller than iOS's (64 footprint and 6 above and
     *  below it, 76 against iOS's 70, D-219 decision h), whose faces sit those 6 higher; so the gap to every + face is
     *  iOS's, and Norse's diamond (its centre 6 + 32 + 18 above the row's bottom) clears by at least art.py's 8 (it was
     *  2.75 dp at 104; the look measured 6.9 dp against iOS's 12.3 pt). */
    @Test
    fun the_toast_place() {
        assertEquals(110f, ToastPlace.bottom(barShown = true), 0f)
        assertEquals(16f, ToastPlace.bottom(barShown = false), 0f)
        val bar = kt("TabBar.kt")
        assertTrue("the bar row pads 6 above and below its 64 footprint", ".padding(horizontal = 6.dp, vertical = 6.dp)," in bar)
        val iosRow = 70f // D-219 decision h, measured in every set
        assertEquals("Android's row over iOS's", AddButtonGeometry.FOOTPRINT + 2 * 6f - iosRow, ToastPlace.ROW_EXCESS, 0f)
        assertEquals(ThemeArt.m("toastClearOfBar") + ToastPlace.ROW_EXCESS, ToastPlace.bottom(barShown = true), 0f)
        val centre = 6f + AddButtonGeometry.FOOTPRINT / 2 + AddButtonGeometry.LIFT
        val diamondTop = centre + ThemeArt.m("diamondSide") * kotlin.math.sqrt(2f) / 2
        val gap = ToastPlace.bottom(barShown = true) - diamondTop
        assertTrue("Norse's diamond clears by 8 or more: $gap", gap >= 8f)
    }

    /** Design 4: one moment at a time, no queue: a post replaces what shows and records when it started; clear empties. */
    @Test
    fun the_centre_replaces_and_clears() {
        assertNull(DoneCenter.current)
        val first = DoneMoment.Small(DoneLine(DoneCopy.NAME_SAVED, emptyList()))
        val second = DoneMoment.Small(DoneLine(DoneCopy.SIGNED_OUT, emptyList()))
        DoneCenter.post(first, now = 1_000L)
        assertEquals(first, DoneCenter.current)
        assertEquals(1_000L, DoneCenter.startedAt)
        DoneCenter.post(second, now = 1_500L)
        assertEquals(second, DoneCenter.current)
        assertEquals(1_500L, DoneCenter.startedAt)
        val serial = DoneCenter.serial
        DoneCenter.post(second, now = 1_600L)
        assertEquals("the same words posted again are a new moment", serial + 1, DoneCenter.serial)
        assertEquals("Signed out", DoneCenter.current?.title)
        assertEquals(emptyList<String>(), DoneCenter.current?.parts)
        DoneCenter.clear()
        assertNull(DoneCenter.current)
    }

    // ---- the copy ----

    /** The constraints' copy table, verbatim (both shells). */
    @Test
    fun the_copy_is_the_table() {
        assertEquals("Expense added", DoneCopy.EXPENSE_ADDED)
        assertEquals("Expense saved", DoneCopy.EXPENSE_SAVED)
        assertEquals("Group created", DoneCopy.GROUP_CREATED)
        assertEquals("Asha removed", DoneCopy.personRemoved("Asha"))
        assertEquals("Group picture saved", DoneCopy.GROUP_PICTURE_SAVED)
        assertEquals("Group picture removed", DoneCopy.GROUP_PICTURE_REMOVED)
        assertEquals("Picture saved", DoneCopy.PICTURE_SAVED)
        assertEquals("Picture removed", DoneCopy.PICTURE_REMOVED)
        assertEquals("Name saved", DoneCopy.NAME_SAVED)
        assertEquals("Username saved", DoneCopy.USERNAME_SAVED)
        assertEquals("Signed out", DoneCopy.SIGNED_OUT)
        assertEquals("Settled with Asha. No money moved.", DoneCopy.settledWith("Asha"))
        assertEquals("Undo", DoneCopy.UNDO)
        assertEquals("Not recorded.", DoneCopy.NOT_RECORDED)
        assertEquals("Expense deleted", DoneCopy.EXPENSE_DELETED)
        assertEquals("Group name saved", DoneCopy.GROUP_NAME_SAVED)
        assertEquals("Group deleted", DoneCopy.GROUP_DELETED)
        assertEquals("Haptics", DoneCopy.HAPTICS_TITLE)
        assertEquals("A tap when it is done", DoneCopy.HAPTICS_SUMMARY)
        assertEquals(kotlinCopy().size, kotlinCopy().toSet().size)
    }

    /** Design 4: the spoken form is `title, part, part`: the separator is never read; no copy carries an em dash. */
    @Test
    fun the_spoken_form_and_no_em_dash() {
        val line = DoneLine("Expense added", listOf("Dinner", "₹1,200.00"))
        assertEquals("Expense added, Dinner, ₹1,200.00", DoneCopy.spoken(line))
        assertEquals("Signed out", DoneCopy.spoken(DoneLine("Signed out", emptyList())))
        // A part that carries a separator (a group's detail line) reads it as the same comma, never as the glyph.
        val big = DoneMoment.Big(BigMoment(BigKind.FIRST_GROUP, "Group created", listOf("Goa trip", "4 people \u00B7 INR")))
        assertEquals("Group created, Goa trip, 4 people, INR", DoneCopy.spoken(big))
        assertEquals("Group created, a, b", DoneCopy.spoken("Group created", listOf("a ${Runes.SEPARATOR} b")))
        assertEquals("Group created, a b", DoneCopy.spoken("Group created", listOf("a${Runes.SEPARATOR}b")))
        for (s in listOf(DoneCopy.spoken(line), DoneCopy.spoken(big), DoneCopy.spoken("x", listOf("a${Runes.SEPARATOR}b \u00B7 c")))) {
            assertFalse("no rune separator in $s", "᛫" in s)
            assertFalse("no middle dot in $s", "·" in s)
        }
        for (s in kotlinCopy()) assertFalse("no em dash in $s", "\u2014" in s)  // em-dash-ok: the escape builds the dash this test asserts is absent
        val src = java.io.File("src/main/java/com/vardrlabs/skipti/DoneMoments.kt").readText()
        assertFalse("no em dash in DoneMoments.kt", "\u2014" in src)  // em-dash-ok: the escape builds the dash this test asserts is absent
    }

    /** Every DoneCopy string, its functions' templates with `{}` for the name: the const fields by reflection (a string
     *  added on Android alone shows up here) and the two templates. */
    private fun kotlinCopy(): List<String> {
        val consts = DoneCopy::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
        return consts + listOf(DoneCopy.personRemoved("{}"), DoneCopy.settledWith("{}"), DoneCopy.actionSpoken("{}", "{}"))
    }

    /** Parity: every string of iOS's `enum DoneCopy` (DoneMoments.swift; interpolations read as `{}`; literals with no
     *  letter, such as the spoken form's ", ", are joiners, not copy) is Android's, and no string is on one shell only. */
    @Test
    fun the_done_copy_matches_ios() {
        val file = java.io.File("../../ios/Skipti/App/DoneMoments.swift")
        assertTrue("iOS has DoneMoments.swift", file.exists())
        val swift = file.readText()
        val open = Regex("""(?m)^enum DoneCopy \{$""").find(swift)
        assertTrue("DoneMoments.swift has enum DoneCopy", open != null)
        val start = open!!.range.first
        val end = swift.indexOf("\n}\n", start)
        assertTrue("enum DoneCopy closes", end > start)
        val body = swift.substring(start, end).lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        val ios = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(body)
            .map { Regex("""\\\([^)]*\)""").replace(it.groupValues[1], "{}") }
            .filter { s -> s.any { it.isLetter() } }
            .toList()
        assertTrue("iOS copy parsed", ios.size >= 15)
        assertEquals(ios.sorted(), kotlinCopy().sorted())
    }

    // ---- the platform's haptics ----

    /** Design 1: the SDK's named constants (never an integer literal): light CLOCK_TICK everywhere; success
     *  CONTEXT_CLICK below 30 and CONFIRM from 30; warning LONG_PRESS below 30 and REJECT from 30. */
    @Test
    fun the_haptic_constants_are_the_sdks() {
        // The fields are compile-time constants (inlined; the JVM never loads the stub class).
        val fields = mapOf(
            "CLOCK_TICK" to HapticFeedbackConstants.CLOCK_TICK, "CONTEXT_CLICK" to HapticFeedbackConstants.CONTEXT_CLICK,
            "CONFIRM" to HapticFeedbackConstants.CONFIRM, "LONG_PRESS" to HapticFeedbackConstants.LONG_PRESS,
            "REJECT" to HapticFeedbackConstants.REJECT,
        )
        assertEquals("five distinct constants", 5, fields.values.toSet().size)
        fun named(name: String) = fields.getValue(name)
        for (sdk in listOf(26, 29, 30, 34, 37)) {
            assertEquals("light at $sdk", named("CLOCK_TICK"), HapticKind.constant(HapticKind.LIGHT, sdk))
            assertEquals(
                "success at $sdk",
                named(if (sdk >= 30) "CONFIRM" else "CONTEXT_CLICK"),
                HapticKind.constant(HapticKind.SUCCESS, sdk),
            )
            assertEquals(
                "warning at $sdk",
                named(if (sdk >= 30) "REJECT" else "LONG_PRESS"),
                HapticKind.constant(HapticKind.WARNING, sdk),
            )
        }
        // The negative: CONFIRM and REJECT arrived with API 30 and are never handed to an older phone.
        for (sdk in 21..29) for (kind in HapticKind.entries) {
            val k = HapticKind.constant(kind, sdk)
            assertFalse("$kind at $sdk", k == named("CONFIRM") || k == named("REJECT"))
        }
    }

    /** Design 1: one door. Only DoneMoments.kt touches the platform's haptics; nothing in the app touches the vibrator
     *  or asks for it; every `performHapticFeedback(` passes the constant alone (no flag that overrides the phone's own
     *  touch-feedback setting); `Haptics.play` reads the switch. */
    @Test
    fun haptics_have_one_door() {
        val files = java.io.File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        assertTrue("main sources found", files.size > 20)
        val door = files.single { it.name == "DoneMoments.kt" }
        for (f in files) {
            val text = f.readText()
            if (f != door) {
                for (word in listOf("performHapticFeedback", "LocalHapticFeedback", "HapticFeedbackConstants")) {
                    assertFalse("${f.name} uses $word", word in text)
                }
            }
            for (word in listOf("Vibrator", "VibratorManager", "VIBRATE", "FLAG_IGNORE_GLOBAL_SETTING", "FLAG_IGNORE_VIEW_SETTING")) {
                assertFalse("${f.name} names $word", Regex("""\b$word\b""").containsMatchIn(text))
            }
        }
        val text = door.readText()
        val calls = Regex("""performHapticFeedback\(""").findAll(text).map { m ->
            var depth = 1
            var i = m.range.last + 1
            var commas = 0
            while (i < text.length && depth > 0) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 1) commas++
                }
                i++
            }
            commas
        }.toList()
        assertEquals("one call", 1, calls.size)
        assertEquals("one argument", listOf(0), calls)
        val play = text.substringAfter("fun play(target: HapticTarget, kind: HapticKind, now: Double) {").substringBefore("\n    }\n")
        assertTrue("play reads the switch", "plays(kind, Appearance.haptics)" in play)
        assertTrue("play gates success", "HapticGate.allows(" in play)
        assertTrue("the view's play goes through it", "fun play(view: View, kind: HapticKind) = play(target(view), kind, " in text)
        assertEquals("one gate: the door's", 1, Regex("""HapticGate\.allows\(""").findAll(text).count())
    }

    /** Design 1: `performHapticFeedback` needs no permission, so the manifest asks for none. */
    @Test
    fun the_manifest_asks_no_vibrate() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue("manifest read", "<manifest" in manifest)
        assertFalse("android.permission.VIBRATE" in manifest)
        assertFalse(Regex("""(?i)permission\.vibrate""").containsMatchIn(manifest))
    }

    // ---- Task 4: the views, their hosts, the Haptics row and the tab haptic (the twins of iOS's Task 3 guards) ----

    /** views-extra 1: the success haptic is gated exactly once, by the door. Two small posts inside the gate window play
     *  one success; one past it plays a second; a big post plays none at the post (its check plays it when it lands);
     *  the switch off plays nothing; a local centre plays through the root's view as the app's does. */
    @Test
    fun a_success_plays_once_inside_the_gate() {
        Appearance.setForTest()
        Haptics.resetForTest()
        val played = mutableListOf<Int>()
        DoneCenter.attach(HapticTarget { played += it })
        val success = HapticKind.constant(HapticKind.SUCCESS, android.os.Build.VERSION.SDK_INT)
        val small = DoneMoment.Small(DoneLine(DoneCopy.NAME_SAVED, emptyList()))
        DoneCenter.post(small, now = 10_000L)
        DoneCenter.post(small, now = 10_500L)
        assertEquals("two posts inside the window: one success", listOf(success), played)
        DoneCenter.post(small, now = 11_000L)
        assertEquals("one past it: a second", listOf(success, success), played)
        DoneCenter.post(DoneMomentCenterFixtures.big, now = 20_000L)
        assertEquals("a big post plays none at the post", 2, played.size)
        val local = DoneMomentCenter()
        local.post(small, now = 30_000L)
        assertEquals("a local centre plays through the root's view", 3, played.size)
        Appearance.setForTest(haptics = false)
        DoneCenter.post(small, now = 40_000L)
        assertEquals("the switch off plays nothing", 3, played.size)
        Appearance.setForTest()
        DoneCenter.attach(null)
        DoneCenter.post(small, now = 50_000L)
        assertEquals("no root view, no haptic", 3, played.size)
    }

    private object DoneMomentCenterFixtures {
        val big = DoneMoments.groupCreated(namedCountAfter = 1, name = "Goa trip", detailParts = listOf("4 people", "INR"))
    }

    /** views-extra 2: the one conversion from a post's uptime to the frame clock. The poses count from the first frame
     *  that draws the moment when it comes within [MomentFrames.LATE] of the post (frame 0); later (a host composed again
     *  over a moment already showing) or before it, from the post's own second. */
    @Test
    fun the_moment_meets_the_frame_clock_once() {
        assertEquals(12.345, MomentFrames.seconds(12_345L), 1e-12)
        assertEquals("the first frame on time is frame 0", 12.38, MomentFrames.origin(12_345L, 12.38), 1e-12)
        assertEquals("at the limit", 12.595, MomentFrames.origin(12_345L, 12.595), 1e-12)
        assertEquals("a late host keeps the post's time", 12.345, MomentFrames.origin(12_345L, 12.6), 1e-12)
        assertEquals("a frame before the post counts from the post", 12.345, MomentFrames.origin(12_345L, 12.0), 1e-12)
        assertEquals(0.25, MomentFrames.LATE, 0.0)
        // The big check's haptic: waits for the landing, plays at once when still, and never twice for a check come back to.
        assertEquals(0.7, MomentFrames.hapticWait(delay = 0.7, age = 0.0)!!, 1e-12)
        assertEquals(0.5, MomentFrames.hapticWait(delay = 0.7, age = 0.2)!!, 1e-12)
        assertEquals(0.0, MomentFrames.hapticWait(delay = 0.7, age = 0.9)!!, 1e-12)
        assertEquals(0.0, MomentFrames.hapticWait(delay = 0.0, age = 0.1)!!, 1e-12)
        assertNull(MomentFrames.hapticWait(delay = 0.7, age = 0.96))
        assertNull(MomentFrames.hapticWait(delay = 0.0, age = 2.0))
        // A still moment's time left.
        assertEquals(3000L, MomentFrames.remainingMs(1_000L, 1_000L, 3.0))
        assertEquals(1200L, MomentFrames.remainingMs(1_000L, 2_800L, 3.0))
        assertEquals(0L, MomentFrames.remainingMs(1_000L, 9_000L, 3.6))
    }

    private fun kt(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    /** The body of the top-level `fun [name](` in [src], to the next top-level closing brace. */
    private fun body(src: String, name: String): String {
        val at = Regex("""(?m)^(?:internal |private )?fun $name\(""").find(src)
        assertTrue("fun $name exists", at != null)
        return src.substring(at!!.range.first).substringBefore("\n}\n")
    }

    /** GUARANTEES #2 (the twin of testTheMomentsNeverTakeATouch): the toast's words, its host and the overlay never take a
     *  touch and open no window: no click, toggle or pointer modifier and no dialog or popup anywhere in DoneMoments.kt,
     *  so the + and every control under a moment keep working. D-247: the file's one control is a toast's action (a held
     *  settle's Undo), Material's TextButton in `DoneToastAction`, drawn only when the line carries one; it never waits
     *  on anyone (the moment ends on its clock either way). */
    @Test
    fun the_moments_never_take_a_touch() {
        val src = kt("DoneMoments.kt")
        for (name in listOf("DoneToast", "DoneToastHost", "DoneOverlay", "DoneCheck", "DoneToastAction")) body(src, name)
        assertEquals("one control in the file", 1, Regex("""\b(Text|Outlined|Icon)?Button\(""").findAll(src).count())
        assertTrue("the toast's action", "androidx.compose.material3.TextButton(" in body(src, "DoneToastAction"))
        assertTrue("drawn only for a line with an action", "line.action?.let { DoneToastAction(it, dark, onAction) }" in body(src, "DoneToast"))
        for (word in listOf(
            """\bclickable\b""", """\bcombinedClickable\b""", """\btoggleable\b""", """\bselectable\b""", """\bpointerInput\b""",
            """\bdraggable\b""", """(?<![A-Za-z])Dialog\(""", """\bAlertDialog\(""", """\bPopup\(""", """\bModalBottomSheet\(""",
        )) {
            assertFalse("DoneMoments.kt has $word", Regex(word).containsMatchIn(src))
        }
    }

    /** D-214 (the twin of testTheMotionIsTheEngines): the moments move only through the motion engine: the check is drawn
     *  from its moment's clock (`MomentPlay`, on `FrameGate()`, D-239 review I1: never an endless `artCanvas(` loop) with
     *  `rememberMotionState()`, the poses are the pure `CheckMotion.pose(` and `ToastMotion.pose(`
     *  read through `MomentClock.elapsed(`; Compose's own animation APIs appear nowhere in the file. Fix wave (M3): the
     *  ban is closed over the whole family: no `androidx.compose.animation` at all (imported or fully qualified), and no
     *  `animate…(` call, spec (`tween(`, `spring(`, `keyframes`, `snap(`, `repeatable(`) or transition. */
    @Test
    fun the_motion_is_the_engines() {
        val src = kt("DoneMoments.kt")
        for (want in listOf("CheckMotion.pose(p.elapsed(CheckMotion.STILL_ELAPSED))", "rememberMotionState()", "CheckMotion.pose(", "ToastMotion.pose(", "MomentClock.elapsed(", "FrameGate()", "CelebrationTimeline.dim(")) {
            assertTrue("DoneMoments.kt uses $want", want in src)
        }
        assertFalse("DoneMoments.kt reaches Compose's animation package", "androidx.compose.animation" in src)
        for (word in listOf(
            """\banimate[A-Z]\w*AsState\b""", """\bAnimatable\b""", """\bAnimatedVisibility\b""", """\bupdateTransition\b""",
            """\brememberInfiniteTransition\b""", """\banimateContentSize\b""", """\bCrossfade\b""", """\bAnimatedContent\b""",
            """\banimate\w*\s*[({]""", """\btween\s*[(<]""", """\bspring\s*[(<]""", """\bkeyframes\b""", """\bsnap\s*[(<]""",
            """\brepeatable\s*[(<]""", """\binfiniteRepeatable\b""", """\brememberTransition\b""", """\bTransition\b""",
            """\bAnimationSpec\b""", """\bAnimationState\b""",
        )) {
            assertFalse("DoneMoments.kt has $word", Regex(word).containsMatchIn(src))
        }
    }

    /** Design 4, fix wave (I1, E4's S9): TalkBack hears the toast from a node that is never transparent. The host keeps
     *  an always-present 1 dp polite live region, composed before it returns for want of a toast; it starts empty for
     *  each moment and is filled in an effect (a change of a live region's content, which TalkBack announces), with the
     *  spoken form (the separator is never read). The visible toast fades in from alpha 0, so it is cleared from
     *  TalkBack and is not a live region. The overlay's words a polite live region too, as E4's celebration's. */
    @Test
    fun the_toast_announces_itself() {
        val src = kt("DoneMoments.kt")
        val host = body(src, "DoneToastHost")
        val early = host.indexOf("if (small == null) return")
        val region = host.indexOf(".size(1.dp)")
        assertTrue("the live region before the host's early return: $region $early", region in 0 until early)
        assertTrue("empty for each moment", "var spoken by remember(serial) { mutableStateOf(\"\") }" in host)
        val fill = host.substringAfter("LaunchedEffect(serial) {").substringBefore("\n    }\n")
        assertTrue("filled in an effect, once per moment", "center.claimSpoken(serial)" in fill && "spoken = DoneCopy.spoken(small.line)" in fill)
        val node = host.substring(region, early)
        assertTrue("a polite live region", "liveRegion = LiveRegionMode.Polite" in node && "contentDescription = " in node)
        assertFalse("never under the toast's alpha", "graphicsLayer" in node)
        val toast = body(src, "DoneToast")
        assertFalse("the faded toast is not the live region", "liveRegion" in toast)
        // D-247: the words' row is hidden from TalkBack; the action (Undo) stays reachable.
        val row = toast.substringAfter("// The words:").substringAfter("Row(").substringBefore("verticalAlignment")
        assertTrue("the faded words are hidden from TalkBack", ".clearAndSetSemantics {}" in row)
        val overlay = body(src, "DoneOverlay")
        assertTrue("the overlay's words are a polite live region", "liveRegion = LiveRegionMode.Polite" in overlay)
        assertTrue("it speaks the spoken form, once per moment", "DoneCenter.claimSpoken(serial)" in overlay && "DoneCopy.spoken(" in overlay)
    }

    /** Fix wave (M6, D24): a moment is announced once, however many hosts compose it (You's overlay, then the tabs'
     *  after a route change mid-moment; a host composed again after a recreation): the first claim of the showing
     *  moment's serial wins, a second claim of it is refused, an older serial never speaks, and a new post speaks again. */
    @Test
    fun a_moment_is_spoken_once() {
        val c = DoneMomentCenter()
        val small = DoneMoment.Small(DoneLine(DoneCopy.NAME_SAVED, emptyList()))
        c.post(small, now = 1_000L)
        val first = c.serial
        assertTrue("the first host speaks", c.claimSpoken(first))
        assertFalse("a second host for the same moment is silent", c.claimSpoken(first))
        c.post(DoneMomentCenterFixtures.big, now = 2_000L)
        assertFalse("an older moment never speaks", c.claimSpoken(first))
        assertTrue("a new post speaks again", c.claimSpoken(c.serial))
        assertFalse(c.claimSpoken(c.serial))
        val other = DoneMomentCenter()
        other.post(small, now = 3_000L)
        assertTrue("each centre keeps its own", other.claimSpoken(other.serial))
    }

    /** Fix wave (F2, D23): the done overlay's dim covers the page's ground as well as its content, as iOS's opacity over
     *  its window draws it (the tab bar and the + stay bright): a scrim of the window's colour (iOS's system background:
     *  white, or black when dark) at 1 - dim, drawn over the content AND the ground behind it, out to the window's edges.
     *  Re-derived: Aurora light's page colour #EEF5F3 at dim 0.22 over white is #FBFDFC, the colour the look measured in
     *  iOS's dimmed ground (Android's ground stayed undimmed at #DCE5E2). */
    @Test
    fun the_dim_covers_the_ground() {
        assertEquals(0.78f, DoneDim.scrim(0.22f), 1e-6f)
        assertEquals("no dim, no scrim", 0f, DoneDim.scrim(1f), 0f)
        assertEquals(androidx.compose.ui.graphics.Color.White, DoneDim.window(dark = false))
        assertEquals(androidx.compose.ui.graphics.Color.Black, DoneDim.window(dark = true))
        val page = intArrayOf(0xEE, 0xF5, 0xF3)
        val a = DoneDim.scrim(CelebrationTiming.DIM_TO.toFloat())
        val out = page.map { kotlin.math.round(it * (1 - a) + 255 * a).toInt() }
        assertEquals("iOS's measured #FBFDFC", listOf(0xFB, 0xFD, 0xFC), out)
        val dim = body(kt("DoneMoments.kt"), "Modifier.doneDim")
        assertFalse("not the content's alpha (a layer)", "graphicsLayer" in dim)
        val draw = dim.substringAfter("drawWithContent {")
        assertTrue("the content first, then the scrim over it", draw.indexOf("drawContent()") in 0 until draw.indexOf("drawRect("))
        assertTrue("the window's colour", "DoneDim.window(dark)" in dim)
        assertTrue("out to the window's edges", "positionInRoot()" in dim)
        val shell = kt("AppRoot.kt").substringAfter("private fun HomeShell(").substringBefore("\n}\n")
        val ground = shell.indexOf(".auroraBackground(dark)\n    ) {")
        val refresh = shell.indexOf("PullToRefreshBox(")
        assertTrue("the shell's ground is drawn before (under) the dimmed content: $ground $refresh", ground in 0 until refresh)
    }

    /** Fix wave (F4): a big moment that replaces a showing big moment holds the dim (the page never flashes bright for a
     *  frame): the centre says so at the post, and the dim of such a play starts at `DIM_TO`; a first big moment, and any
     *  moment after a small one or a clear, ramps in from 1 as before; the ramp out at the end is unchanged. */
    @Test
    fun a_replacing_big_moment_holds_the_dim() {
        val c = DoneMomentCenter()
        val big = DoneMomentCenterFixtures.big
        c.post(big, now = 1_000L)
        assertFalse("a first big moment", c.replacing)
        c.post(big, now = 1_500L)
        assertTrue("a big over a big", c.replacing)
        c.post(DoneMoment.Small(DoneLine(DoneCopy.NAME_SAVED, emptyList())), now = 1_600L)
        assertFalse("a small moment never holds", c.replacing)
        c.post(big, now = 1_700L)
        assertFalse("a big after a small", c.replacing)
        c.clear()
        c.post(big, now = 9_000L)
        assertFalse("a big after a clear", c.replacing)
        val to = CelebrationTiming.DIM_TO.toFloat()
        assertEquals("a first one starts bright", 1f, DoneDim.level(0.0, still = false, holds = false), 1e-6f)
        assertEquals("a replacing one starts dimmed", to, DoneDim.level(0.0, still = false, holds = true), 1e-6f)
        assertEquals(to, DoneDim.level(0.1, still = false, holds = true), 1e-6f)
        val end = CelebrationTiming.TOTAL - 0.05
        assertEquals("the ramp out is unchanged", DoneDim.level(end, still = false, holds = false), DoneDim.level(end, still = false, holds = true), 1e-6f)
        assertEquals("still: dimmed", to, DoneDim.level(0.0, still = true, holds = false), 1e-6f)
        val overlay = body(kt("DoneMoments.kt"), "rememberDoneOverlay")
        assertTrue("the play carries the centre's word", "holdsDim = DoneCenter.replacing" in overlay)
    }

    /** Fix wave (F6): the shell's toast never draws over a screen iOS presents as a sheet or a cover (iOS's sheet covers
     *  the shell's toast; its time runs on underneath). Android's routes that iOS presents that way hide it: Add and edit
     *  expense, New group, Your name, the username step, an invitation's join, a pushed expense, the invitations, every
     *  removal; and the add-person screen (iOS's add-person sheet). Pushed screens (Profile, People, an expense's detail,
     *  Join with a link, settings) keep it, as iOS's stacks do. */
    @Test
    fun the_shell_toast_hides_under_a_sheet() {
        val g = "a".repeat(32)
        val m = "b".repeat(32)
        for (r in listOf(
            "add", "expense-edit/x/list", "expense-edit/x/detail", "new-group", "new-group/you", "your-name", "username",
            "username/you", "username/find-me", "username/change/x", "join-invite", "pushed/g/e", "invitations",
            RemovalRoute(g, m, review = true, fromPeople = false).route(), RemovalRoute(g, m, review = false, fromPeople = true).route(),
        )) {
            assertTrue("$r covers the shell's toast", ToastCover.covers(r))
        }
        for (r in listOf(null, "account", "account/appearance", "profile", "people/x", "expense/x", "join", "devices", "group-settings/x", "activity", "export", "signin")) {
            assertFalse("$r keeps the shell's toast", ToastCover.covers(r))
        }
        val shell = kt("AppRoot.kt").substringAfter("private fun HomeShell(").substringBefore("\n}\n")
        val cover = shell.indexOf("if (ToastCover.covers(route)) CoversShellToast()")
        assertTrue("the shell covers its toast for such a route, before it draws one", cover in 0 until shell.indexOf("when (route) {"))
        assertTrue("the add-person screen covers it", "CoversShellToast()" in body(kt("People.kt"), "AddPersonScreen"))
        val host = body(kt("DoneMoments.kt"), "DoneToastHost")
        assertTrue("the app's host hides its toast while covered", "center === DoneCenter && ToastCover.hides(AppLock.locked)" in host)
        val fn = body(kt("DoneMoments.kt"), "CoversShellToast")
        assertTrue("held while composed", "DisposableEffect(Unit)" in fn && "ToastCover.hold()" in fn && "onDispose { ToastCover.release() }" in fn)
        ToastCover.hold()
        ToastCover.hold()
        ToastCover.release()
        assertTrue("held while any cover is composed", ToastCover.covered)
        ToastCover.release()
        assertFalse(ToastCover.covered)
    }

    /** Design 6 (the twin of testTheCheckIsTheArtTable): the check reads the art table, the accent and its soft fill (Norse
     *  the check fill), draws the tick through `DoneCheck.TICK`, turns E4's `RuneRing` in Norse alone, and is decorative. */
    @Test
    fun the_check_is_the_art_table() {
        val check = body(kt("DoneMoments.kt"), "DoneCheck")
        for (want in listOf(
            "ThemeArt.m(\"doneCheck\")", "ThemeArt.m(\"doneCircleRadius\")", "ThemeArt.m(\"doneCircleStroke\")",
            "ThemeArt.m(\"doneTickStroke\")", "ThemeArt.m(\"ringBox\")", "DoneCheck.TICK", "Tok.accent(dark)", "Tok.accentSoft(dark)",
            "ThemeArt.color(\"norseCheckFill\")", "clearAndSetSemantics {}", "CheckMotion.STILL_ELAPSED",
        )) {
            assertTrue("DoneCheck reads $want", want in check)
        }
        assertEquals("one rune ring", 1, Regex("""RuneRing\(""").findAll(check).count())
        val cond = check.substringBefore("RuneRing(").substringAfterLast("if (")
        assertTrue("the rune ring in Norse only: $cond", cond.startsWith("theme == AppTheme.NORSE)") && "else" !in cond)
        assertTrue("every rune lit", "RuneRing(1, 1, dark)" in check)
    }

    /** Design 5 (the twin of testTheToastIsTheArtTable): the toast is the art table's bar in the theme's panel shape. */
    @Test
    fun the_toast_is_the_art_table() {
        val toast = body(kt("DoneMoments.kt"), "DoneToast")
        for (want in listOf(
            "ThemeArt.panelShape(", "ThemeArt.m(\"toastRadius\")", "ThemeArt.m(\"toastDot\")", "ThemeArt.m(\"toastGlyph\")",
            "ThemeArt.m(\"toastGap\")", "ThemeArt.m(\"toastPadV\")", "ThemeArt.m(\"toastPadH\")", "ThemeArt.m(\"toastShadowRadius\")",
            "Tok.owing(dark)", "Tok.onAccent(dark)", "Tok.surface(dark)", "Tok.hairline(dark)", "Tok.text(dark)", "TabIcons.ToastCheck",
            "TypeScale.toast", "Separator.annotated(", "maxLines = 2", "TextOverflow.Ellipsis",
        )) {
            assertTrue("DoneToast reads $want", want in toast)
        }
        val icons = kt("TabIcons.kt")
        assertTrue("the toast's check is Lucide's check at toastCheckStroke", Regex("""val ToastCheck: ImageVector by lazy \{ lucide\("check", \*PATHS\.getValue\("check"\)\.toTypedArray\(\), stroke = ThemeArt\.m\("toastCheckStroke"\)\) \}""").containsMatchIn(icons))
        val host = body(kt("DoneMoments.kt"), "DoneToastHost")
        for (want in listOf("ToastPlace.bottom(barShown)", "WindowInsets.navigationBars", "ThemeArt.m(\"toastInset\")", "DoneMoments.toastSeconds(small.line)", "Haptics.target(view)")) {
            assertTrue("DoneToastHost reads $want", want in host)
        }
    }

    /** Design 6, 11 (the twin of testTheShellNeverDimsTheBar): the shell dims its tab content, never the bar: `doneDim(`
     *  is on the content's PullToRefreshBox, the overlay sits between the content and the bar, and the bar's own box is
     *  outside both; the toast's host sits over the root, above every route; a tab SWITCH plays the light haptic, a
     *  re-tap (back to the root, to the top) does not. */
    @Test
    fun the_shell_never_dims_the_bar() {
        val root = kt("AppRoot.kt")
        val shell = root.substringAfter("private fun HomeShell(").substringBefore("\n}\n")
        val refresh = shell.indexOf("PullToRefreshBox(")
        val refreshArgs = shell.substring(refresh).substringBefore(") {")
        assertTrue("the dim is on the tab content", ".doneDim(" in refreshArgs)
        // D-224 T6: You's route dims under its own overlay too (a first group made from You returns there).
        assertEquals("one dim of the tab content", 1, Regex("""\.doneDim\(donePlay, dark\)""").findAll(shell).count())
        assertEquals("and You's", 2, Regex("""\.doneDim\(""").findAll(shell).count())
        val overlay = shell.indexOf("DoneOverlay(donePlay, dark)")
        val bar = shell.indexOf("SkiptiTabBar(")
        assertTrue("content, then the overlay, then the bar: $refresh $overlay $bar", refresh in 0 until overlay && overlay < bar)
        val barBox = shell.substring(shell.lastIndexOf("Box(", bar), bar)
        assertFalse("the bar is never dimmed", "doneDim" in barBox || "graphicsLayer" in barBox)
        assertTrue("the shell says while its bar is shown", "ShellBarShown.value = true" in barBox)
        val activity = root.substringAfter("setContent {").substringBefore("\n    }\n")
        val afterRoot = activity.substringAfter("AppRoot(")
        assertTrue("the toast's host over the root", "DoneToastHost(barShown = ShellBarShown.value)" in afterRoot)
        val switch = shell.substringAfter("is TabTap.SwitchTo -> {").substringBefore("is TabTap.PopToRoot")
        assertTrue("a tab switch plays the light haptic", "Haptics.play(view, HapticKind.LIGHT)" in switch)
        val pop = shell.substringAfter("is TabTap.PopToRoot ->").substringBefore("\n")
        val top = shell.substringAfter("is TabTap.ScrollToTop ->").substringBefore("\n")
        for ((name, line) in listOf("PopToRoot" to pop, "ScrollToTop" to top)) {
            assertFalse("$name plays no haptic", "Haptics" in line)
        }
        assertEquals("one light haptic in the shell", 1, Regex("""HapticKind\.LIGHT""").findAll(root).count())
    }

    /** Design 8 (the twin of testTheSignedInScreenDrawsTheCheck): the signed-in screen draws the big check in place of
     *  the 72 dp circle and its drawn tick. */
    @Test
    fun the_signed_in_screen_draws_the_check() {
        val screen = body(kt("AccountOwner.kt"), "SignedInConfirmation")
        assertTrue("the big check", "DoneCheck(DonePresentation.SCREEN, dark)" in screen)
        for (gone in listOf("CircleShape", "72.dp", "drawLine(", "Canvas(")) {
            assertFalse("SignedInConfirmation still has $gone", gone in screen)
        }
    }

    /** Fix wave (F8, iOS is the scale): the signed-in and erased screens sit their check and words where iOS's do:
     *  iOS's `Spacer(minLength: 40)` over the check and `Spacer()` over the button share the free height, so the block
     *  is centred with at least 40 above it; Android's fixed 24 dp top put it 130 higher (186 dp against 316 and 294 pt). */
    @Test
    fun the_check_screens_sit_where_ios_does() {
        val owner = kt("AccountOwner.kt")
        for (name in listOf("SignedInConfirmation", "ErasedScreen")) {
            val screen = body(owner, name)
            val top = screen.indexOf("Spacer(Modifier.heightIn(min = 40.dp).weight(1f))")
            val check = screen.indexOf("DoneCheck(DonePresentation.SCREEN, dark)")
            val bottom = screen.indexOf("Spacer(Modifier.weight(1f))")
            assertTrue("$name: a flexible top of at least 40, the check, then the flexible bottom: $top $check $bottom", top in 0 until check && check < bottom)
            assertEquals("$name: two flexible spacers, equal", 2, Regex("""\.weight\(1f\)""").findAll(screen).count())
            assertFalse("$name: no fixed top", "Spacer(Modifier.height(24.dp))" in screen)
        }
        val erased = body(owner, "ErasedScreen")
        assertTrue("the erased screen has no bar, so no bar's height above its top (iOS's padding 20)", "top = 20.dp" in erased && "BarDecisions" !in erased && "PushedBar" !in erased)
        assertTrue("the signed-in screen is under its bar", "top = BarDecisions.scrollTop(20f).dp" in body(owner, "SignedInConfirmation"))
    }

    /** Design 10 (the twin of testTheHapticsRowIsOnAppearance): Appearance holds the Haptics row after the Text size
     *  card: the vibrate tile, the switch read from `Appearance.haptics`, the whole row one Switch to TalkBack (the
     *  drawn switch takes no tap of its own: the row toggles, D-225), and one success when it is turned on. */
    @Test
    fun the_haptics_row_is_on_appearance() {
        val hub = kt("AccountHubScreen.kt")
        // D-225 fix wave (M12): the slice ends at the Appearance page's own list of later items (the old anchor,
        // "SettingItem.APP_LOCK ->", now names Security's row further down, so the slice ran past the page).
        val size = hub.substringAfter("SettingItem.TEXT_SIZE ->").substringBefore("SettingItem.APP_LOCK, SettingItem.PRIVACY")
        assertTrue("the slice is Appearance's text size item only", "SecurityPage(" !in size)
        val caption = size.indexOf("AppearanceCopy.TEXT_SIZE_CAPTION")
        val row = size.indexOf("HapticsRow(dark)")
        assertTrue("the row after the text size card: $caption $row", caption in 0 until row)
        assertEquals("one Haptics row", 1, Regex("""HapticsRow\(dark\)""").findAll(hub).count())
        val fn = body(hub, "HapticsRow")
        // D-225 (Design 3): the row is S2's `.toggle` ListRow in a ListCard, the drawn ThemeSwitch inside it (ListRow's
        // own `toggleable(` with the Switch role and the card's padding are pinned by ThemeArtTest and AppearanceTest).
        for (want in listOf(
            "ListCard(dark)", "ListRow(", "leading = { SettingsTile(\"vibrate\", dark) }", "RowTrailing.Toggle(Appearance.haptics) { on ->",
            "Appearance.setHaptics(context, on)", "DoneCopy.HAPTICS_TITLE", "parts = listOf(DoneCopy.HAPTICS_SUMMARY)",
        )) {
            assertTrue("HapticsRow has $want", want in fn)
        }
        for (gone in listOf("Switch(", "onCheckedChange", "switchColors(")) assertFalse("HapticsRow still has $gone", gone in fn)
        assertEquals("one toggle: the row's", 1, Regex("""RowTrailing\.Toggle\(""").findAll(fn).count())
        assertTrue("a success when turned on, nothing when off", "if (on) Haptics.play(view, HapticKind.SUCCESS)" in fn)
        assertTrue("the vibrate tile is drawn", "\"vibrate\" -> TabIcons.Vibrate" in hub)
    }

    // ---- Task 6: the call sites (the twins of iOS's Task 5 guards) ----

    private fun mainSources(): List<java.io.File> =
        java.io.File("src/main/java/com/vardrlabs/skipti").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** The text between the parentheses of the call whose `(` ends at [open] in [src]. */
    private fun argsAt(src: String, open: Int): String {
        var depth = 1
        var i = open + 1
        while (i < src.length && depth > 0) {
            when (src[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return src.substring(open + 1, i - 1)
    }

    /** U27 (the twin of testDeletingAGroupSaysSoAndLeaves): both deletes post "Group deleted", then close the settings
     *  and the group's page at once; the delete for everyone no longer goes back to the group's page. */
    @Test
    fun deleting_a_group_says_so_and_leaves() {
        val src = kt("GroupSettings.kt")
        val done = src.substring(src.indexOf("if (deleteStatus == GroupJobs.Status.Done) {"))
            .let { it.substring(0, it.indexOf("\n        }") ) }
        val here = src.substring(src.indexOf("fun deleteHere() {")).let { it.substring(0, it.indexOf("\n    }\n")) }
        for ((name, body) in listOf("delete for everyone" to done, "delete here" to here)) {
            val post = body.indexOf("DoneCenter.post(DoneMoments.line(DoneCopy.GROUP_DELETED))")
            val gone = body.indexOf("onGone()")
            assertTrue("$name posts", post >= 0)
            assertTrue("$name: the post, then the close", gone > post)
            val between = body.substring(post, gone)
            for (wait in listOf("delay(", "launch", "withContext", "animate")) assertFalse("$name: nothing waits ($wait)", wait in between)
            assertFalse("$name: never back to the group's page", "onBack()" in body)
        }
        // D-240 review (M4): the shell's onGone lands on the tab root: no route and no open group.
        val root = kt("AppRoot.kt")
        val gone = root.substring(root.indexOf("onGone = {")).let { it.substring(0, it.indexOf("},")) }
        assertTrue("onGone clears the route", "route = null" in gone)
        assertTrue("onGone clears the open group", "openGroup = null" in gone)
    }

    /** Every post to a centre: the app's (`DoneCenter`) and the add-person screen's own (`localDone`). */
    private val postCall = Regex("""\b(DoneCenter|localDone)\.post\(""")

    /** Design 7, 8 (the twin of testEverySmallMomentIsPosted): per file, the number of moments posted and the words each
     *  file's posts name, so a lost site fails by name; every post goes through `DoneMoments` and reads `DoneCopy` (or the
     *  add-person screen's kept words), never a string literal; no screen builds a moment of its own. */
    @Test
    fun every_small_moment_is_posted() {
        val table = mapOf(
            "AddExpenseScreen.kt" to (1 to listOf("DoneMoments.expense(editingId != null")),
            "GroupScreens.kt" to (2 to listOf("DoneMoments.groupCreated(", "DoneCopy.personRemoved(member.name)")),
            "People.kt" to (1 to listOf("AddPersonCopy.done(who, invited)")),
            "RemovalFlow.kt" to (1 to listOf("DoneCopy.personRemoved(")),
            "GroupSettings.kt" to (4 to listOf("DoneCopy.GROUP_NAME_SAVED", "DoneCopy.GROUP_PICTURE_REMOVED", "DoneCopy.GROUP_PICTURE_SAVED", "DoneCopy.GROUP_DELETED")),
            "OverviewTab.kt" to (1 to listOf("DoneCopy.GROUP_PICTURE_REMOVED", "DoneCopy.GROUP_PICTURE_SAVED")),
            "UsernameStep.kt" to (2 to listOf("DoneCopy.USERNAME_SAVED, PeopleCopy.handle(h)", "DoneCopy.USERNAME_SAVED, PeopleCopy.handle(saved)")),
            "AccountHubScreen.kt" to (1 to listOf("DoneCopy.SIGNED_OUT")),
        )
        val found = mutableMapOf<String, Int>()
        for (f in mainSources()) {
            val src = f.readText()
            if (f.name == "DoneMoments.kt") continue
            for (word in listOf("DoneLine(", "DoneMoment.Small(", "DoneMoment.Big(", "BigMoment(")) {
                assertFalse("${f.name} builds a moment itself ($word)", word in src)
            }
            val posts = postCall.findAll(src).map { argsAt(src, it.range.last) }.toList()
            if (posts.isEmpty()) continue
            found[f.name] = posts.size
            for (arg in posts) {
                assertTrue("${f.name}: the post goes through DoneMoments: $arg", arg.trimStart().startsWith("DoneMoments."))
                assertFalse("${f.name}: no string literal in a post: $arg", '"' in arg)
                assertTrue(
                    "${f.name}: the post reads DoneCopy: $arg",
                    listOf("DoneCopy.", "DoneMoments.expense(", "DoneMoments.groupCreated(", "AddPersonCopy.done(").any { it in arg },
                )
            }
            val words = posts.joinToString("\n")
            for (want in table[f.name]?.second.orEmpty()) assertTrue("${f.name} posts $want", want in words)
        }
        assertEquals("the posting files and their counts", table.mapValues { it.value.first }, found.toSortedMap().toMap())
        // D-245 (decision 199): Profile and the welcome save through ProfileSave, whose one door posts Profile's toast or,
        // in the welcome, plays the success alone; its moments read DoneCopy through DoneMoments (iOS's twin alike).
        val save = kt("ProfileConfirm.kt").substringAfter("object ProfileSave {").substringBefore("\n}\n")
        assertTrue("val nameMoment: DoneMoment get() = DoneMoments.line(DoneCopy.NAME_SAVED)" in save)
        assertTrue("DoneMoments.line(if (avatar == Avatar.None) DoneCopy.PICTURE_REMOVED else DoneCopy.PICTURE_SAVED)" in save)
        assertTrue("Feedback.TOAST -> center.post(moment, now)" in save)
        assertTrue("Feedback.HAPTIC -> center.success(now)" in save)
        assertEquals("Profile's two saves", 2, kt("Profile.kt").split("ProfileSave.Feedback.TOAST)").size - 1)
        // The add-person screen's own centre, shown by its own host (it is a screen above the shell; iOS's is a sheet).
        val people = kt("People.kt")
        assertTrue("a local centre", "val localDone = remember { DoneMomentCenter() }" in people)
        assertTrue("its own host, no bar", "DoneToastHost(barShown = false, center = localDone)" in people)
        assertFalse("the inline done line is gone", "Text(\"✓" in people)
        // D-247: the settle posts are the hold's (its own centre, the app's by default), so Settle up's cards and the group's
        // next card both say them: the held settle with its Undo, and Undo's "Not recorded.". The name keeps a removed
        // person's (the history's), read by both callers.
        val settle = kt("SettleAll.kt")
        val hold = settle.substringAfter("class SettleHold(").substringBefore("\n}\n")
        assertEquals("two posts, both the hold's", 2, hold.split("center.post(").size - 1)
        assertTrue("center.post(DoneMoments.lineWithAction(DoneCopy.settledWith(other), DoneCopy.UNDO, seconds), clock(), onAction = { undo(id) })" in hold)
        assertTrue("center.post(DoneMoments.line(DoneCopy.NOT_RECORDED), clock())" in hold)
        assertFalse("nothing else in Settle up posts", "DoneCenter.post(" in settle)
        assertTrue("a removed person keeps their name", "LedgerStore.label(entry.id, section.named, section.history)" in settle)
        // A removal's job says so; the review step's job is a decline, which removes nobody.
        assertTrue("never for a decline", "if (step != \"review\") ctx?.let { DoneCenter.post(" in kt("RemovalFlow.kt"))
        // The username step posts only outside the sign-in flow (inside it, the signed-in screen follows).
        val step = body(kt("UsernameStep.kt"), "UsernameStep")
        assertTrue("outside sign-in only", "if (onCancel != null) DoneCenter.post(" in step)
    }

    /** Design 7: a moment's line drops a missing or blank part; an expense reads its description and core's amount. */
    @Test
    fun a_line_holds_only_the_parts_that_are_there() {
        assertEquals(
            DoneMoment.Small(DoneLine(DoneCopy.NAME_SAVED, emptyList())),
            DoneMoments.line(DoneCopy.NAME_SAVED, null, "", "  "),
        )
        assertEquals(
            DoneMoment.Small(DoneLine(DoneCopy.USERNAME_SAVED, listOf("@asha"))),
            DoneMoments.line(DoneCopy.USERNAME_SAVED, " @asha "),
        )
        val amount = uniffi.skipti_core.ffiMoneyFormat(240_000L, "INR", AppLocale.current)
        assertEquals(DoneMoments.amount(240_000L, "INR"), amount)
        assertEquals(
            DoneMoment.Small(DoneLine(DoneCopy.EXPENSE_ADDED, listOf("Dinner", amount))),
            DoneMoments.expense(edited = false, note = "Dinner", paidMinor = 240_000L, currency = "INR"),
        )
        assertEquals(
            DoneMoment.Small(DoneLine(DoneCopy.EXPENSE_SAVED, listOf(amount))),
            DoneMoments.expense(edited = true, note = null, paidMinor = 240_000L, currency = "INR"),
        )
        assertNull("core refuses a made-up currency: no part", DoneMoments.amount(1L, "ZZZ"))
    }

    /** Design 8, GUARANTEES #2 (the twin of testAddExpenseClosesAtOnce): Add closes at once. No "Saved" label phase and no
     *  wait: the success branch posts the moment and calls `onSaved()` with nothing that suspends between them. */
    @Test
    fun add_expense_closes_at_once() {
        val src = kt("AddExpenseScreen.kt")
        assertFalse("no Saved label", "\"Saved\"" in src)
        assertFalse("no saved label phase", Regex("""saved\s*->""").containsMatchIn(src))
        val success = src.substringAfter("if (result == null) {").substringBefore("} else {")
        val post = success.indexOf("DoneCenter.post(DoneMoments.expense(")
        val saved = success.indexOf("onSaved()")
        assertTrue("the post, then onSaved: $post $saved", post in 0 until saved)
        for (wait in listOf("delay(", "withContext(", "await", "postDelayed", "Thread.sleep")) {
            assertFalse("nothing waits in the success branch ($wait)", wait in success)
        }
    }

    /** The files that warn and how many confirmations each warns for (Design 3). */
    private val warnings = mapOf(
        "ExpenseDetailScreen.kt" to 1, // delete this expense
        "ExpenseListScreen.kt" to 1, // delete this expense
        "GroupScreens.kt" to 1, // remove a person from a local group
        "GroupSettings.kt" to 3, // delete for everyone, delete here, forget a copy
        "AccountOwner.kt" to 2, // erase this phone's ledger; D-225 T7 (A7): the recovery screen's erase and start fresh
        "Phones.kt" to 3, // sign a phone out, sign out everywhere, delete the account
        "AppLock.kt" to 1, // turn the lock off
        "UsernameStep.kt" to 1, // the one username change
        "RemovalFlow.kt" to 4, // make owner; the three red-button removal screens
    )

    /** Design 3 (the twin of testEveryDestructiveConfirmationWarns): per file, the count of `HapticWarning(`; every file
     *  with a destructive confirmation (a `DangerAction(` or a dialog whose confirm is in `owed`) is in the table; the
     *  warning plays only through `HapticWarning`, as the confirmation opens. */
    @Test
    fun every_destructive_confirmation_warns() {
        val call = Regex("""(?<![A-Za-z])HapticWarning\(""")
        val found = mutableMapOf<String, Int>()
        for (f in mainSources()) {
            if (f.name == "DoneMoments.kt") continue
            val src = f.readText()
            val n = call.findAll(src).count()
            if (n > 0) found[f.name] = n
            assertFalse("${f.name} plays a warning past HapticWarning", "HapticKind.WARNING" in src)
            val danger = Regex("""(?<![A-Za-z])DangerAction\(""").findAll(src).filterNot { src.substring(maxOf(0, it.range.first - 4), it.range.first) == "fun " }
            // You's "Delete account" row opens the delete screen (Phones.kt), whose confirmation warns.
            val opens = danger.filterNot { "DangerAction(PhonesCopy.DELETE_TITLE, dark, onClick = onOpenDelete)" in src.substring(it.range.first) .substringBefore("\n") }
            val destructiveDialog = Regex("""SkiptiAlertDialog\(""").findAll(src).any { "Tok.owed(" in argsAt(src, it.range.last) }
            if (opens.any() || destructiveDialog) assertTrue("${f.name} confirms something destructive and is in the table", f.name in warnings)
        }
        assertEquals("the warning sites", warnings, found.toSortedMap().toMap())
        val removal = kt("RemovalFlow.kt")
        for (screen in listOf("PlainRemove", "SettleFirst", "Redistribute")) {
            assertTrue("$screen warns when it appears, on the flow's latch", "HapticWarning(true, warned)" in body(removal, screen))
        }
        // Fix wave (M5, D24), follow-up (A-I2): each red-button screen warns once per entry, as iOS's freshly pushed
        // screen does: its latch lives in the flow, saved per group, member and STEP, so a recreation or a reload within
        // the step never warns again, and leaving the step (Back, then Next again) re-arms it.
        val flow = body(removal, "RemovalFlowScreen")
        for (latch in listOf("plainWarned", "settleWarned", "redistributeWarned")) {
            assertTrue("the flow keeps $latch per step", "val $latch = rememberSaveable(gid, mid, step) { mutableStateOf(false) }" in flow)
            assertTrue("and hands it over", "warned = $latch" in flow)
        }
        assertTrue("the role dialog warns for the owner's transfer only", "HapticWarning(action == RoleAction.MAKE_OWNER)" in removal)
        val fn = body(kt("DoneMoments.kt"), "HapticWarning")
        assertTrue("it plays as the flag turns true", "LaunchedEffect(show)" in fn && "WarningLatch.next(show, played)" in fn)
        assertTrue("the warning kind", "Haptics.play(view, HapticKind.WARNING)" in fn)
        assertTrue("kept across a recreation", "rememberSaveable" in fn)
        assertTrue("or on a latch the caller keeps", "latch: MutableState<Boolean>? = null" in fn && "var played by (latch ?: own)" in fn)
    }

    /** Design 3: the latch plays once per opening, not again for a recreation of the same one, and re-arms on close. */
    @Test
    fun the_warning_plays_once_per_opening() {
        assertEquals("closed: nothing", false to false, WarningLatch.next(show = false, played = false))
        assertEquals("opens: plays", true to true, WarningLatch.next(show = true, played = false))
        assertEquals("still open (recreated): nothing", false to true, WarningLatch.next(show = true, played = true))
        assertEquals("closes: re-armed", false to false, WarningLatch.next(show = false, played = true))
    }

    /** Design 7, 8 (the twin of testTheBigMomentsAreTheCheck): the joined page and the erased screen draw the big check;
     *  the erase's alert is gone from the root, replaced by its screen with "OK"; a first group made from You plays its
     *  overlay over You (where the create returns), as iOS's You sits under the shell's overlay. */
    @Test
    fun the_big_moments_are_the_check() {
        val join = body(kt("Invites.kt"), "JoinScreen")
        val joined = join.substringAfter("done != null -> {").substringBefore("p != null -> {")
        val check = joined.indexOf("DoneCheck(DonePresentation.SCREEN, dark)")
        assertTrue("the joined page's check above its heading", check in 0 until joined.indexOf("InviteCopy.joined("))
        val erased = body(kt("AccountOwner.kt"), "ErasedScreen")
        for (want in listOf("DoneCheck(DonePresentation.SCREEN, dark)", "EraseCopy.DONE_TITLE", "EraseCopy.DONE_BODY", "EraseCopy.DONE_OK")) {
            assertTrue("the erased screen has $want", want in erased)
        }
        val root = kt("AppRoot.kt")
        assertFalse("no erase alert at the root", Regex("""SkiptiAlertDialog\(""").findAll(root).any { "EraseCopy" in argsAt(root, it.range.last) })
        val notice = root.substringAfter("if (LedgerErase.notice) {").substringBefore("\n    }\n")
        assertTrue("the erased screen over the root, closed by OK", "ErasedScreen(onDone = { LedgerErase.notice = false })" in notice)
        assertTrue("and by Back", "BackHandler { LedgerErase.notice = false }" in notice)
        val shell = root.substringAfter("private fun HomeShell(").substringBefore("\n}\n")
        val you = shell.substringAfter("\"account\", \"account/profile\"").substringBefore("return\n")
        assertTrue("You dims under a first group's overlay", ".doneDim(youPlay, dark)" in you && "DoneOverlay(youPlay, dark)" in you)
        val create = body(kt("GroupScreens.kt"), "CreateGroupScreen")
        assertTrue("the count read after the create, off the main thread", "runCatching { store.namedGroups().size }.getOrNull()" in create)
    }

    /** Design 9 (the twin of testTheCelebrationClearsAndBuzzes): E4's celebration, when it starts, clears a toast and plays
     *  the success (through the door's gate); the milestone card plays nothing. */
    @Test
    fun the_celebration_clears_and_buzzes() {
        val settled = kt("Settled.kt")
        val start = body(settled, "CelebrationOverlay").substringAfter("LaunchedEffect(play) {").substringBefore("play.run()")
        assertTrue("clears the toast (D-247: unless it offers Undo)", "DoneCenter.giveWay()" in start)
        assertTrue("plays the success", "Haptics.play(view, HapticKind.SUCCESS)" in start)
        assertEquals("the celebration's haptic only", 1, Regex("""Haptics\.""").findAll(settled).count())
        assertFalse("the milestone card plays nothing", "Haptics" in body(settled, "MilestoneCard"))
    }

    /** Fix wave (F7), D-247: the last settle from the group page's next card starts the celebration as iOS's does, from
     *  the settle itself rather than waiting on the page's whole read: once the card's settle is held, it reads whether
     *  the group is now all settled with the held one counted (under the hold's lock, off the main thread); the card
     *  then gives the toast way (a toast offering Undo stays), marks the play at once and the page's reload never plays
     *  it twice. Never on a page not yet loaded, a copy, or a group already settled (`Celebration.plays`). */
    @Test
    fun the_last_settle_celebrates_at_once() {
        val s = CelebrationState().apply { before = false }
        s.settledHere(isCopy = false)
        assertTrue("marked at once", s.pending)
        assertEquals(true, s.before)
        assertFalse("the reload does not play it again", Celebration.plays(s.before, after = true, isCopy = false))
        assertFalse("never on a page not yet loaded", CelebrationState().apply { settledHere(isCopy = false) }.pending)
        assertFalse("never in a copy", CelebrationState().apply { before = false; settledHere(isCopy = true) }.pending)
        assertFalse("never for a group already settled", CelebrationState().apply { before = true; settledHere(isCopy = false) }.pending)
        val card = body(kt("Settled.kt"), "GroupNextCard")
        val held = card.indexOf("SettleHold.shared.hold(")
        val read = card.indexOf("SettleHold.shared.counting { held -> GroupProgress.allSettled(LedgerStore(vaultPath), named.group.id, held) }")
        assertTrue("read with the held one, after it is held", held in 0 until read)
        val done = card.substringAfter("if (settled) {").substringBefore("}")
        assertTrue("the toast gives way and the play is marked", "DoneCenter.giveWay()" in done && "onAllSettled()" in done)
        assertTrue("the page marks its play", "onAllSettled = { celebration.settledHere(isCopy) }" in kt("OverviewTab.kt"))
    }

    /** D-247: a toast's action runs once, only for the moment that offered it; a newer post or a clear makes an old tap
     *  do nothing; a toast with an action stays the action's whole window; a celebration gives way only to a plain toast.
     *  The twin of iOS's testTheToastsActionRunsOnceForItsOwnMoment. */
    @Test
    fun the_toasts_action_runs_once_for_its_own_moment() {
        val c = DoneMomentCenter()
        var ran = 0
        c.post(DoneMoments.lineWithAction("Settled with Ana. No money moved.", "Undo"), now = 1_000L, onAction = { ran += 1 })
        val first = c.serial
        assertEquals(DoneCenter.ACTION_SECONDS, DoneMoments.toastSeconds(DoneLine("x", emptyList(), "Undo")), 0.0)
        assertEquals(ToastMotion.TOTAL, DoneMoments.toastSeconds(DoneLine("x", emptyList())), 0.0)
        assertEquals(5.0, DoneCenter.ACTION_SECONDS, 0.0)
        // D-247 fix wave (decision 201): the line carries its window (12 s with a screen reader).
        assertEquals(12.0, DoneMoments.toastSeconds(DoneLine("x", emptyList(), "Undo", actionSeconds = 12.0)), 0.0)
        assertEquals("no action: the plain toast's time", ToastMotion.TOTAL, DoneMoments.toastSeconds(DoneLine("x", emptyList(), actionSeconds = 12.0)), 0.0)
        c.act(first - 1)
        assertEquals("an old serial does nothing", 0, ran)
        c.giveWay()
        assertTrue("a celebration leaves the Undo toast", c.current != null)
        c.act(first)
        c.act(first)
        assertEquals("once", 1, ran)
        c.post(DoneMoments.lineWithAction("Settled with Ana. No money moved.", "Undo"), now = 2_000L, onAction = { ran += 1 })
        c.post(DoneMoments.line("Expense added"), now = 2_100L)
        c.act(c.serial)
        assertEquals("the newer toast offers nothing", 1, ran)
        c.giveWay()
        assertNull("a plain toast gives way", c.current)
        c.post(DoneMoments.lineWithAction("Settled with Ana. No money moved.", "Undo"), now = 3_000L, onAction = { ran += 1 })
        val third = c.serial
        c.clear()
        c.act(third)
        assertEquals("a cleared toast offers nothing", 1, ran)
        c.post(DoneMoments.lineWithAction("x", "Undo"), now = 4_000L, onAction = { ran += 1 })
        c.post(DoneMoments.line("y"), now = 4_100L)
        c.clear(ifStill = c.serial - 1)
        assertEquals("a clear for an older moment leaves the newer one", "y", c.current?.title)
        // The pose lasts the asked time; the fades are the same.
        assertTrue(ToastMotion.pose(4.0, still = false, total = 5.0) != null)
        assertNull(ToastMotion.pose(4.0, still = false))
        assertNull(ToastMotion.pose(5.0, still = false, total = 5.0))
        // The host keeps a toast with an action for the action's window and passes the tap to the centre.
        val host = body(kt("DoneMoments.kt"), "DoneToastHost")
        assertTrue("val total = DoneMoments.toastSeconds(small.line)" in host)
        assertTrue("ToastMotion.pose(play.elapsed(stillAt = 0.0), play.still, total)" in host)
        assertTrue("onAction = { center.act(serial) }" in host)
    }

    /** Design 7 (the twin of testExportAndImportBuzz): export and import show their own result and add only the success. */
    @Test
    fun export_and_import_buzz() {
        for (name in listOf("ExportScreen.kt", "ImportFlow.kt")) {
            val src = kt(name)
            assertEquals("$name buzzes once", 1, Regex("""Haptics\.play\(view, HapticKind\.SUCCESS\)""").findAll(src).count())
            assertFalse("$name posts no toast", "DoneCenter" in src)
        }
    }

    // ---- Follow-up fix wave (A2): the lock, the removal latch, the pushed sheets, the first group's page ----

    /** A-I1 (SPEC 3.7, D-163; iOS's lock and privacy cover sit over everything, SkiptiApp.swift): no moment shows while
     *  the app is locked: the app's host draws no toast, and its live region is empty (TalkBack cannot reach the words
     *  on the lock screen; they were spoken at the post). The root's covering screens (the erased screen, an opened
     *  invite's join) hold the cover; the overlay is composed only inside HomeShell, which the lock replaces. */
    @Test
    fun no_moment_over_the_lock() {
        assertTrue("locked: hidden with no cover held", ToastCover.hides(locked = true))
        assertFalse("unlocked, uncovered: shown", ToastCover.hides(locked = false))
        ToastCover.hold()
        assertTrue("a cover still hides it", ToastCover.hides(locked = false))
        ToastCover.release()
        assertEquals("unlocked: the words", "Expense added", ToastCover.liveText("Expense added", showing = true, locked = false))
        assertEquals("locked: nothing to reach", "", ToastCover.liveText("Expense added", showing = true, locked = true))
        assertEquals("no moment: nothing", "", ToastCover.liveText("Expense added", showing = false, locked = false))
        val host = body(kt("DoneMoments.kt"), "DoneToastHost")
        assertTrue("the host's live region reads the rule", "contentDescription = ToastCover.liveText(spoken, small != null, AppLock.locked)" in host)
        assertTrue("the app's host draws nothing while locked or covered", "if (center === DoneCenter && ToastCover.hides(AppLock.locked)) return" in host)
        val root = kt("AppRoot.kt").substringAfter("\nfun AppRoot(").substringBefore("\n}\n")
        val erased = root.substringAfter("if (LedgerErase.notice) {").substringBefore("return\n")
        assertTrue("the erased screen covers the toast", "CoversShellToast()" in erased)
        val join = root.substringAfter("} else if (openedInvite != null) {").substringBefore("} else {")
        assertTrue("an opened invite's join covers the toast", "CoversShellToast()" in join)
        val src = kt("AppRoot.kt")
        val shell = src.substringAfter("private fun HomeShell(").substringBefore("\n}\n")
        assertEquals("the overlay is composed only inside HomeShell", Regex("""DoneOverlay\(""").findAll(src).count(), Regex("""DoneOverlay\(""").findAll(shell).count())
        val app = root.substringAfter("when (kitLoaded) {")
        assertTrue("which the lock replaces", app.indexOf("if (AppLock.locked) {") in 0 until app.indexOf("AppLockScreen()") && app.indexOf("AppLockScreen()") < app.indexOf("HomeShell("))
    }

    /** A-M3: the two screens a push opens that iOS presents as sheets (Your phones from "Was this you?", a group's page
     *  from a group push) cover the shell's toast while they show; Your phones opened from You and a group opened from
     *  Balance keep it, as iOS's stacks do; leaving forgets the push. */
    @Test
    fun a_pushed_sheet_covers_the_toast() {
        val g = "a".repeat(32)
        assertTrue(ToastCover.pushed(PushCover.PHONES, route = "devices", group = null, onBalance = true))
        assertFalse("left", ToastCover.pushed(PushCover.PHONES, route = "account", group = null, onBalance = true))
        assertFalse("opened from You", ToastCover.pushed(null, route = "devices", group = null, onBalance = true))
        assertTrue(ToastCover.pushed(PushCover.group(g), route = null, group = g, onBalance = true))
        assertTrue("a screen opened from it is inside it", ToastCover.pushed(PushCover.group(g), route = "expense/x", group = g, onBalance = true))
        assertFalse("closed", ToastCover.pushed(PushCover.group(g), route = null, group = null, onBalance = true))
        assertFalse("another group", ToastCover.pushed(PushCover.group(g), route = null, group = "c".repeat(32), onBalance = true))
        assertFalse("another tab", ToastCover.pushed(PushCover.group(g), route = null, group = g, onBalance = false))
        assertFalse("opened from Balance", ToastCover.pushed(null, route = null, group = g, onBalance = true))
        val shell = kt("AppRoot.kt").substringAfter("private fun HomeShell(").substringBefore("\n}\n")
        assertTrue("the phones push marks it", "route = \"devices\"\n            pushCover = PushCover.PHONES" in shell)
        assertTrue("the group push marks it", "openGroup = pushedGroup\n            pushCover = PushCover.group(pushedGroup)" in shell)
        val rule = "val pushCovered = ToastCover.pushed(pushCover, route, openGroup, selectedTab == HomeTab.BALANCE)"
        assertTrue("the shell reads the rule", rule in shell)
        assertTrue("holds the cover while it applies", "if (pushCovered) CoversShellToast()" in shell)
        assertTrue("and forgets the push once it does not", "LaunchedEffect(pushCovered) { if (!pushCovered) pushCover = null }" in shell)
        assertTrue("before it draws", shell.indexOf("if (pushCovered) CoversShellToast()") in 0 until shell.indexOf("when (route) {"))
    }

    /** A-R3: a first group made from New group plays its moment over Home's NEW page, as iOS's does (its Home is loaded
     *  under the sheet): the create reads Home into the shell's keep, off the main thread with the count, and takes it
     *  before the moment is posted and the route closes, so the page under the moment is never the empty one. */
    @Test
    fun the_first_group_plays_over_its_page() {
        val store = run {
            val keystore = object : uniffi.skipti_core.SkiptiKeystore {
                var key: ByteArray? = null
                override fun provision(key: ByteArray) { this.key = key }
                override fun load(): ByteArray? = key
                override fun destroy() { key = null }
            }
            val path = java.nio.file.Files.createTempDirectory("first-group").resolve("v.db").toString()
            LedgerStore(path) { uniffi.skipti_core.SkiptiVault.open(path, keystore) }
        }
        val keep = HomeKeep()
        keep.take(HomeKeep.read { store })
        assertTrue("an empty ledger reads empty", keep.loaded.value && keep.groups.value.isEmpty())
        CreateGroup.create(store, "Tim", "Goa", "INR", listOf("Rahul"), null, null) {}
        keep.take(HomeKeep.read { store })
        assertEquals("the keep holds the new group", listOf("Goa"), keep.groups.value.map { it.group.name })
        assertTrue("and its overview", keep.overview.value != null && keep.error.value == null)
        keep.take(Result.failure(IllegalStateException("x")))
        assertTrue("a failed read is Home's error line", keep.error.value?.startsWith("Could not load the ledger.") == true)
        val create = body(kt("GroupScreens.kt"), "CreateGroupScreen")
        assertTrue("read off the main thread with the count", "val home = homeKeep?.let { HomeKeep.read { store } }" in create)
        val take = create.indexOf("home?.let { homeKeep?.take(it) }")
        assertTrue("taken before the moment is posted", take in 0 until create.indexOf("DoneCenter.post("))
        assertTrue("and before the route closes", take in 0 until create.indexOf("onCreated(created.group.id)"))
        val shell = kt("AppRoot.kt").substringAfter("private fun HomeShell(").substringBefore("\n}\n")
        assertTrue("the shell hands Home's keep to a create that returns to Balance", "homeKeep = if (fromYou) null else homeKeep" in shell)
        val overview = kt("OverviewTab.kt").substringAfter("\nfun OverviewScreen(").substringBefore("\n}\n")
        assertTrue("Home reads the same way", "keep.take(withContext(Dispatchers.IO) { HomeKeep.read { LedgerStore(vaultPath) } })" in overview)
    }

    // ---- D-231 Task 4: the Less motion row (decision 69) ----

    /** The twin of testTheMotionRowIsOnAppearance: Appearance holds the Less motion row after the Haptics row, 16 below
     *  it: the sparkles tile, the switch read from `Appearance.lessMotion` and stored through `setLessMotion`, the words
     *  from MotionCopy, the whole row one Switch to TalkBack (ListRow's toggle), and no haptic of its own. */
    @Test
    fun the_motion_row_is_on_appearance() {
        val hub = kt("AccountHubScreen.kt")
        val size = hub.substringAfter("SettingItem.TEXT_SIZE ->").substringBefore("SettingItem.APP_LOCK, SettingItem.PRIVACY")
        assertTrue("the slice is Appearance's text size item only", "SecurityPage(" !in size)
        val haptics = size.indexOf("HapticsRow(dark)")
        val motion = size.indexOf("MotionRow(dark)")
        assertTrue("after Haptics: $haptics $motion", haptics in 0 until motion)
        assertTrue("16 below it", "Spacer(Modifier.height(16.dp))\n                    MotionRow(dark)" in size)
        assertEquals("one Less motion row", 1, Regex("""MotionRow\(dark\)""").findAll(hub).count())
        val fn = body(hub, "MotionRow")
        for (want in listOf(
            "ListCard(dark)", "ListRow(", "leading = { SettingsTile(\"sparkles\", dark) }",
            "RowTrailing.Toggle(Appearance.lessMotion) { on -> Appearance.setLessMotion(context, on) }",
            "MotionCopy.TITLE", "parts = listOf(MotionCopy.SUMMARY)",
        )) {
            assertTrue("MotionRow has $want", want in fn)
        }
        for (gone in listOf("Haptics.play(", "Switch(", "onCheckedChange")) assertFalse("MotionRow has $gone", gone in fn)
        assertTrue("the sparkles tile is drawn", "\"sparkles\" -> TabIcons.Sparkles" in hub)
    }

    // ---- D-234 T1: every settings summary fits its row on the narrowest phones (decision 82) ----

    /** iOS's `SummaryWidth` (DoneMomentsTests.swift, read below): the slack in hand, and the bound Vardr Labs' widths are
     *  multiplied by. AWT draws only Space Grotesk's default instance (wght 300) and the app draws 400; iOS's
     *  testVardrLabsSummariesStayInsideTheWeightBound proves 400 over 300 stays inside it. Space Grotesk narrows slightly
     *  as it gets heavier (about 0.999 at 400, Core Text), so the bound is conservative, never short. Decision 101: 1.01,
     *  not 1.05: the measured 400-over-300 ratio is under 1.005 (Less motion: iOS 171.7 at wght 400, AWT 171.01 at 300),
     *  and 1.05 drew a false truncation of Less motion at 360 dp (179.56 in 178); 1.01 keeps margin over the measure. */
    private val slack = 2.0
    private val vardrWeightBound = 1.01

    private data class SummaryRow(val label: String, val parts: List<String>, val lines: Int = 1, val tile: Boolean = false, val trailing: Double)

    /** The text column a row leaves its summary on a phone [phone] dp wide: the page's 20 a side, the card's `listPadH`
     *  a side, the tile and its gap, the trailing piece and its gap. ListRow's own layout. */
    private fun column(phone: Double, row: SummaryRow): Double =
        phone - 2 * BarDecisions.PAGE_SIDE_WIDE - 2 * ThemeArt.m("listPadH") -
            (if (row.tile) ThemeArt.m("settingsTile") + ThemeArt.m("rowGap") else 0f) - row.trailing - ThemeArt.m("rowGap")

    /** Every fixed summary a settings row draws in [theme], from the app's own words (the twin of iOS's table). */
    private fun summaryRows(theme: AppTheme): List<SummaryRow> {
        val chevron = RowChevronSize.toDouble()
        val toggle = ThemeArt.m("switchWidth").toDouble()
        val rows = mutableListOf<SummaryRow>()
        val seen = mutableSetOf<String>()
        for (signedIn in listOf(false, true)) for (method in listOf(null, SignInMethod.Apple.rowLabel, SignInMethod.Google.rowLabel))
            for (phones in listOf(null, 1, 12)) for (mode in AppearanceMode.entries) for (lock in LockTimeout.entries)
                for (canLock in listOf(true, false)) {
                    val facts = YouRowFacts(signedIn, method, phones, theme, mode, lock, canLock, "1.10.10")
                    for (section in SettingsSection.entries) {
                        val parts = YouRows.parts(section, facts)
                        if (seen.add(parts.joinToString("|"))) rows += SummaryRow("You, $section", parts, tile = true, trailing = chevron)
                    }
                }
        rows += SummaryRow("Haptics", listOf(DoneCopy.HAPTICS_SUMMARY), tile = true, trailing = toggle)
        rows += SummaryRow("Less motion", listOf(MotionCopy.SUMMARY), tile = true, trailing = toggle)
        for (lock in LockTimeout.entries) rows += SummaryRow("App lock", listOf(SecurityRows.appLockLine(lock, true)), trailing = chevron)
        rows += SummaryRow("App lock", listOf(SecurityRows.appLockLine(LockTimeout.OFF, false)), trailing = chevron)
        rows += SummaryRow("Erase", listOf(SecurityRows.ERASE_SUMMARY), trailing = chevron)
        rows += SummaryRow("Delete account", listOf(SecurityRows.DELETE_ACCOUNT_SUMMARY), trailing = chevron)
        rows += SummaryRow("Export", listOf(YourDataCopy.EXPORT_DETAIL), lines = 2, trailing = chevron)
        // D-234 (decision 100): Import keeps its words and takes three lines (two cut "added." on the narrow phones).
        rows += SummaryRow("Import", listOf(ImportCopy.ENTRY_DETAIL), lines = 3, trailing = chevron)
        return rows
    }

    private val fonts = java.io.File("src/main/res/font")

    /** The body face Android draws in [theme], at the detail size, kerned (Compose kerns by default). */
    private fun bodyFont(theme: AppTheme): java.awt.Font {
        val file = when (val face = Type.face(Role.BODY, theme)) {
            Face.SANS -> "ibmplexsans_regular.ttf"
            Face.GROTESK -> "spacegrotesk.ttf"
            else -> error("no body file for $face")
        }
        val attributes = mapOf<java.text.AttributedCharacterIterator.Attribute, Any>(
            java.awt.font.TextAttribute.SIZE to TypeScale.detail,
            java.awt.font.TextAttribute.KERNING to java.awt.font.TextAttribute.KERNING_ON,
        )
        return java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, java.io.File(fonts, file)).deriveFont(attributes)
    }

    /** The width [parts] draw at, joined by the theme's separator (Norse's rune in the rune font), from the bundled TTFs;
     *  Vardr Labs' default-instance (wght 300) width times the bound iOS proves for 400. */
    private fun summaryWidth(parts: List<String>, theme: AppTheme): Double {
        val text = parts.joinToString(Separator.meta(theme))
        val styled = java.text.AttributedString(text)
        styled.addAttribute(java.awt.font.TextAttribute.FONT, bodyFont(theme))
        if (theme == AppTheme.NORSE) {
            val rune = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, java.io.File(fonts, "vardrrunes_carvedregular.ttf"))
                .deriveFont(TypeScale.detail)
            var at = text.indexOf(Runes.SEPARATOR)
            while (at >= 0) {
                styled.addAttribute(java.awt.font.TextAttribute.FONT, rune, at, at + Runes.SEPARATOR.length)
                at = text.indexOf(Runes.SEPARATOR, at + 1)
            }
        }
        val drawn = java.awt.font.TextLayout(styled.iterator, java.awt.font.FontRenderContext(null, true, true)).advance.toDouble()
        return if (Type.face(Role.BODY, theme) == Face.GROTESK) drawn * vardrWeightBound else drawn
    }

    /** As many words on a line as fit (Android's line breaker), the twin of iOS's `GreedyWrap.lines`. */
    private fun greedy(text: String, room: Double, measure: (String) -> Double): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(" ")) {
            val longer = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || measure(longer) <= room) line = longer else { lines += line; line = word }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    /** D-234 T1 (decision 82): every summary in every theme fits its row's text column on a 360 dp phone, with the slack
     *  in hand: a one-line summary by its width, a two-line one by its greedy lines. ListRow ends a summary that does not
     *  fit in an ellipsis, so only the width can show it. The twin of iOS's
     *  testEverySettingsSummaryFitsItsRowOnTheNarrowestPhones (which measures this 360 dp column too). */
    @Test
    fun every_settings_summary_fits_its_row_on_the_narrowest_phones() {
        var measured = 0
        for (theme in AppTheme.entries) for (row in summaryRows(theme)) {
            val room = column(360.0, row) - slack
            if (row.lines == 1) {
                val drawn = summaryWidth(row.parts, theme)
                assertTrue("$theme 360 dp ${row.label}: ${row.parts} is $drawn wide in $room", drawn <= room)
            } else {
                val lines = greedy(row.parts.joinToString(Separator.meta(theme)), room) { summaryWidth(listOf(it), theme) }
                assertTrue("$theme 360 dp ${row.label}: $lines", lines.size <= row.lines)
            }
            measured++
        }
        assertTrue("the table is not empty: $measured", measured > 50)
        // The negative: the old Haptics words do not fit a 360 dp switch row in Aurora.
        val haptics = SummaryRow("Haptics", listOf("A tap when something is done"), tile = true, trailing = ThemeArt.m("switchWidth").toDouble())
        assertTrue("the instrument sees truncation", summaryWidth(haptics.parts, AppTheme.AURORA) > column(360.0, haptics) - slack)
        // The twins: iOS's constants, and the geometry both tests assume.
        val ios = java.io.File("../../ios/SkiptiTests/DoneMomentsTests.swift").readText()
        assertTrue("iOS's slack", "static let slack: CGFloat = 2" in ios)
        assertTrue("iOS's bound", "static let vardrWeightBound: CGFloat = 1.01" in ios)
        assertEquals(20, BarDecisions.PAGE_SIDE_WIDE)
        assertEquals(20, RowChevronSize)
        val row = body(kt("ListRows.kt"), "ListRow")
        assertTrue("the words take the rest", "Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ThemeArt.m(\"rowLineGap\").dp))" in row)
        assertTrue("the gap after a tile", "leading()\n            Spacer(Modifier.width(ThemeArt.m(\"rowGap\").dp))" in row)
    }
}
