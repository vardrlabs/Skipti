package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-221 (E4, Design 3 and 4): the setup steps, the next step, the rune ring's lit count and their words; the twin
 *  of iOS's YouProgressTests, plus parity (every string of YouProgress.swift's `StepCopy` is Kotlin's). */
class YouProgressTest {
    /** Every fact true: every step done (the phone has a lock, a stored currency core knows, notifications allowed). */
    private val all = SetupFacts(
        signedIn = true, hasName = true, hasPicture = true, storedCurrency = "INR", lockTimeout = LockTimeout.STANDARD,
        lockAvailable = true, hasGroup = true, hasUsername = true, notificationsEnabled = true, canPost = true,
    )
    private val none = SetupFacts(signedIn = true, lockTimeout = LockTimeout.OFF)

    private val noAccount = listOf(SetupStep.NAME, SetupStep.PICTURE, SetupStep.CURRENCY, SetupStep.APP_LOCK, SetupStep.FIRST_GROUP)

    @Test
    fun the_steps_without_an_account_are_the_five_and_never_mention_one() {
        assertEquals(noAccount, SetupSteps.steps(signedIn = false, lockAvailable = true))
        // GUARANTEES #5: an account is never implied or nudged for; no step's words name signing in or an account.
        for (step in SetupStep.entries) {
            for (words in listOf(StepCopy.title(step), StepCopy.why(step))) {
                assertFalse("$step: $words", "sign" in words.lowercase())
                assertFalse("$step: $words", "account" in words.lowercase())
            }
        }
        for (words in listOf(StepCopy.DO_IT, StepCopy.pill(2, 5))) {
            assertFalse(words, "sign" in words.lowercase() || "account" in words.lowercase())
        }
        // Without an account the two account steps never count, even when their facts hold.
        assertEquals(5 to 5, SetupSteps.progress(all.copy(signedIn = false)))
    }

    @Test
    fun the_steps_signed_in_are_the_seven() {
        assertEquals(noAccount + listOf(SetupStep.USERNAME, SetupStep.NOTIFICATIONS), SetupSteps.steps(signedIn = true, lockAvailable = true))
    }

    /** D-247 (decision 169, D-221 h): a phone that cannot lock (no screen lock: `AppLock.available` false) has no app lock
     *  step, so the ring completes at "4 of 4" (6 of 6 signed in); with a lock the step is back. The capability is the pure
     *  function's input; nothing else about the steps changes. The twin of iOS's testThePhoneWithoutALockDropsTheAppLockStep. */
    @Test
    fun the_phone_without_a_lock_drops_the_app_lock_step() {
        assertEquals(noAccount - SetupStep.APP_LOCK, SetupSteps.steps(signedIn = false, lockAvailable = false))
        assertEquals(
            noAccount - SetupStep.APP_LOCK + listOf(SetupStep.USERNAME, SetupStep.NOTIFICATIONS),
            SetupSteps.steps(signedIn = true, lockAvailable = false),
        )
        var facts = all.copy(signedIn = false, lockAvailable = false)
        assertEquals("every step done: the ring completes", 4 to 4, SetupSteps.progress(facts))
        assertEquals("4 of 4 done", StepCopy.pill(4, 4))
        assertNull("nothing left to ask", SetupSteps.next(facts))
        facts = facts.copy(hasGroup = false)
        assertEquals("the app lock is never the next step without a lock", SetupStep.FIRST_GROUP, SetupSteps.next(facts))
        assertEquals(6 to 6, SetupSteps.progress(all.copy(lockAvailable = false)))
        // The negative: with a lock the step counts again, and undone it is asked for.
        var locking = SetupFacts(lockAvailable = true, lockTimeout = LockTimeout.OFF, hasName = true, hasPicture = true, storedCurrency = "INR")
        assertEquals(3 to 5, SetupSteps.progress(locking))
        assertEquals(SetupStep.APP_LOCK, SetupSteps.next(locking))
        locking = locking.copy(lockAvailable = false)
        assertEquals(3 to 4, SetupSteps.progress(locking))
        assertEquals(SetupStep.FIRST_GROUP, SetupSteps.next(locking))
    }

    /** The hub reads the capability again when SKIPTI comes to the front (a screen lock set in the phone's Settings):
     *  the facts are read on every resume, and the capability is the lock's own (`AppLock.available`). */
    @Test
    fun the_hub_reads_the_lock_again_on_the_front() {
        val hub = java.io.File("src/main/java/com/vardrlabs/skipti/AccountHubScreen.kt").readText()
        assertTrue("LifecycleResumeEffect(Unit) {\n        resumes++" in hub)
        assertTrue("facts = withContext(Dispatchers.IO) { SetupFacts.read(context, LedgerStore(vaultPath), hasUsername) }" in hub)
        assertTrue("SetupSteps.steps(signedIn, lockAvailable)" in hub)
        val you = java.io.File("src/main/java/com/vardrlabs/skipti/YouProgress.kt").readText()
        assertTrue("lockAvailable = AppLock.available(context) && !SettleLook.noLock()," in you)
    }

    @Test
    fun each_fact_flips_exactly_its_step() {
        assertEquals(SetupStep.entries.toSet(), SetupSteps.done(all))
        assertEquals(emptySet<SetupStep>(), SetupSteps.done(none))
        val flips = listOf(
            SetupStep.NAME to none.copy(hasName = true),
            SetupStep.PICTURE to none.copy(hasPicture = true),
            SetupStep.CURRENCY to none.copy(storedCurrency = "INR"),
            SetupStep.APP_LOCK to none.copy(lockTimeout = LockTimeout.STANDARD, lockAvailable = true),
            SetupStep.FIRST_GROUP to none.copy(hasGroup = true),
            SetupStep.USERNAME to none.copy(hasUsername = true),
            SetupStep.NOTIFICATIONS to none.copy(notificationsEnabled = true, canPost = true),
        )
        for ((step, facts) in flips) assertEquals("$step", setOf(step), SetupSteps.done(facts))
        val undo = listOf(
            SetupStep.NAME to all.copy(hasName = false),
            SetupStep.PICTURE to all.copy(hasPicture = false),
            SetupStep.CURRENCY to all.copy(storedCurrency = null),
            SetupStep.APP_LOCK to all.copy(lockTimeout = LockTimeout.OFF),
            SetupStep.FIRST_GROUP to all.copy(hasGroup = false),
            SetupStep.USERNAME to all.copy(hasUsername = false),
            SetupStep.NOTIFICATIONS to all.copy(canPost = false),
        )
        for ((step, facts) in undo) assertEquals("$step", SetupStep.entries.toSet() - step, SetupSteps.done(facts))
    }

    @Test
    fun the_currency_counts_only_once_chosen_and_known() {
        assertFalse("unset", SetupStep.CURRENCY in SetupSteps.done(all.copy(storedCurrency = null)))
        assertFalse("a stored code core does not know", SetupStep.CURRENCY in SetupSteps.done(all.copy(storedCurrency = "XQZ")))
        assertTrue("a stored code core knows", SetupStep.CURRENCY in SetupSteps.done(none.copy(storedCurrency = "JPY")))
    }

    @Test
    fun the_app_lock_counts_only_when_it_locks_at_launch() {
        assertFalse("off", SetupStep.APP_LOCK in SetupSteps.done(all.copy(lockTimeout = LockTimeout.OFF)))
        assertFalse("the default on a phone with no lock", SetupStep.APP_LOCK in SetupSteps.done(all.copy(lockAvailable = false)))
        assertTrue("the default on a phone with a lock", SetupStep.APP_LOCK in SetupSteps.done(none.copy(lockTimeout = LockTimeout.STANDARD, lockAvailable = true)))
        assertTrue("immediately", SetupStep.APP_LOCK in SetupSteps.done(none.copy(lockTimeout = LockTimeout.IMMEDIATELY, lockAvailable = true)))
    }

    @Test
    fun notifications_count_only_when_the_phone_lets_skipti_post() {
        assertFalse("enabled, cannot post", SetupStep.NOTIFICATIONS in SetupSteps.done(all.copy(canPost = false)))
        assertFalse("can post, disabled", SetupStep.NOTIFICATIONS in SetupSteps.done(all.copy(notificationsEnabled = false)))
        assertTrue(SetupStep.NOTIFICATIONS in SetupSteps.done(none.copy(notificationsEnabled = true, canPost = true)))
    }

    @Test
    fun the_next_step_is_the_first_undone() {
        assertEquals(SetupStep.NAME, SetupSteps.next(none))
        assertEquals(SetupStep.CURRENCY, SetupSteps.next(all.copy(storedCurrency = null, hasGroup = false)))
        assertEquals(SetupStep.FIRST_GROUP, SetupSteps.next(all.copy(hasGroup = false, canPost = false)))
        // While the username card shows, the next card skips the username step.
        val noName = all.copy(hasUsername = false, canPost = false)
        assertEquals(SetupStep.USERNAME, SetupSteps.next(noName))
        assertEquals(SetupStep.NOTIFICATIONS, SetupSteps.next(noName, skipping = setOf(SetupStep.USERNAME)))
        assertNull(SetupSteps.next(all.copy(hasUsername = false), skipping = setOf(SetupStep.USERNAME)))
        assertNull(SetupSteps.next(all))
        // Without an account the account steps are never next.
        assertNull(SetupSteps.next(all.copy(signedIn = false, hasUsername = false, canPost = false)))
    }

    @Test
    fun progress_counts_the_done_steps_of_the_list() {
        // D-247: `none`'s phone has no lock, so it has no app lock step; with a lock the step counts (undone).
        assertEquals(0 to 6, SetupSteps.progress(none))
        assertEquals(0 to 7, SetupSteps.progress(none.copy(lockAvailable = true)))
        assertEquals(7 to 7, SetupSteps.progress(all))
        assertEquals(6 to 7, SetupSteps.progress(all.copy(hasPicture = false)))
        assertEquals(4 to 5, SetupSteps.progress(all.copy(signedIn = false, hasGroup = false)))
        assertEquals(0 to 5, SetupSteps.progress(none.copy(signedIn = false, lockAvailable = true)))
    }

    /** art.py section 18: (done, total) -> lit, the Look Book's Math.round(24 x done / total), half up. */
    @Test
    fun the_rune_ring_lights_the_goldens() {
        val goldens = listOf(
            Triple(0, 5, 0), Triple(1, 5, 5), Triple(4, 5, 19), Triple(5, 5, 24),
            Triple(0, 7, 0), Triple(3, 7, 10), Triple(6, 7, 21), Triple(7, 7, 24),
        )
        for ((done, total, lit) in goldens) assertEquals("$done of $total", lit, RuneRing.lit(done, total))
        assertEquals("no steps lights none", 0, RuneRing.lit(0, 0))
    }

    @Test
    fun each_step_has_its_place() {
        assertEquals(
            listOf(
                StepPlace.PROFILE, StepPlace.PROFILE, StepPlace.DATA, StepPlace.SECURITY, StepPlace.CREATE_GROUP,
                StepPlace.USERNAME, StepPlace.PROFILE_ACCOUNT,
            ),
            SetupStep.entries.map(StepPlace::of),
        )
    }

    @Test
    fun the_words_are_the_plans() {
        assertEquals(
            listOf(
                "Add your name" to "Shown on Balance and in your groups.",
                "Add a picture" to "A photo or an emoji beside your name.",
                "Choose your currency" to "New groups and expenses start in it.",
                "Set an app lock" to "Your phone's lock before anyone sees your ledger.",
                "Start a group" to "Split a trip, a flat or a dinner.",
                "Pick your username" to "People can find and add you by it.",
                "Turn on notifications" to "Hear when your groups add an expense.",
            ),
            SetupStep.entries.map { StepCopy.title(it) to StepCopy.why(it) },
        )
        assertEquals("Do it", StepCopy.DO_IT)
        assertEquals("6 of 7 done", StepCopy.pill(6, 7))
        for (step in SetupStep.entries) assertFalse("no em dash", '\u2014' in StepCopy.title(step) + StepCopy.why(step))  // em-dash-ok: the escape builds the dash this test asserts is absent
    }

    /** Parity: every string literal of iOS's `enum StepCopy` (YouProgress.swift), an interpolation read as `§`, is one of
     *  Kotlin's, and every one of Kotlin's is there. */
    @Test
    fun the_step_copy_matches_ios() {
        val kotlin = SetupStep.entries.flatMap { listOf(StepCopy.title(it), StepCopy.why(it)) } +
            listOf(StepCopy.DO_IT, StepCopy.pill(3, 7).replace("3", "§").replace("7", "§"))
        assertEquals(kotlin.toSet(), SwiftCopy.literals("YouProgress.swift", "enum StepCopy"))
        // D-221 fix wave (S13): pairs, not a set: each step's own title and why line are iOS's for that step (a swapped
        // pair passed the set comparison).
        fun camel(step: SetupStep) = step.name.lowercase().split('_').mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar { it.uppercase() } }.joinToString("")
        assertEquals(SetupStep.entries.associate { camel(it) to StepCopy.title(it) }, SwiftCopy.cases("YouProgress.swift", "static func title(_ step: SetupStep) -> String {"))
        assertEquals(SetupStep.entries.associate { camel(it) to StepCopy.why(it) }, SwiftCopy.cases("YouProgress.swift", "static func why(_ step: SetupStep) -> String {"))
    }

    /** D-221 fix wave (S11): the next card and the hero take iOS's measured layout: 4 between the title and the why line,
     *  12 when stacked (one stacking rule, TextSize.stacks), 8 between the name and the line under it, 16 between the
     *  hero and each card (the deletion notice spaces itself, so nothing pending leaves no gap), and the placeholder
     *  person glyph is half the avatar. */
    @Test
    fun the_hero_and_next_card_take_ios_gaps() {
        val card = body("AccountHubScreen.kt", "private fun NextStepCard(")
        assertTrue("4 between the lines", "Column(m, verticalArrangement = Arrangement.spacedBy(4.dp))" in card)
        assertTrue("12 stacked", "Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(12.dp))" in card)
        assertTrue("one stacking rule", "TextSize.stacks(LocalDensity.current.fontScale)" in card && "1.15f" !in card)
        assertTrue(TextSize.stacks(1.15f))
        assertFalse(TextSize.stacks(1.14f))
        val h = hero()
        val handle = h.substringAfter("YouCopy.handleLine(signedIn, username)?.let {").substringBefore("}")
        assertTrue("8 above the line under the name", "Spacer(Modifier.height(8.dp))" in handle)
        for (anchor in listOf("UsernameCard(dark, onPickUsername)", "NextStepCard(step, dark)")) {
            val before = h.substring(0, h.indexOf(anchor)).trimEnd().lines().last()
            assertEquals("16 above $anchor", "Spacer(Modifier.height(16.dp))", before.trim())
        }
        assertTrue("the notice spaces itself", "DeletionPendingCard(dark, Modifier.padding(top = 16.dp))" in h)
        assertFalse("no gap left when nothing is pending", "Spacer(Modifier.height(12.dp))" in h)
        assertTrue("the notice takes it", "Column(modifier.glassCard(dark))" in main("Phones.kt"))
        // D-225 T5 (L75): the ratio is the `avatarGlyph` metric (0.5), read in AvatarView.
        assertTrue("half the avatar", "modifier = Modifier.size((size.value * ThemeArt.m(\"avatarGlyph\")).dp)," in body("Profile.kt", "fun AvatarView("))
    }

    private fun main(name: String) = java.io.File("src/main/java/com/vardrlabs/skipti/$name").readText()

    /** A declaration's body: from [decl] to the next line that is a lone `}`. A missing one is an assertion. */
    private fun body(file: String, decl: String): String {
        val text = main(file)
        val at = text.indexOf(decl)
        assertTrue("$file declares $decl", at >= 0)
        val end = text.indexOf("\n}\n", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    /** You's hero, from the page's start to the five groups. */
    private fun hero(): String {
        val src = main("AccountHubScreen.kt")
        val start = src.indexOf("if (section == null) {")
        // D-225: You's list is the shared ListCard (Design 1).
        val end = src.indexOf("ListCard(dark) {\n                SettingsSection.entries.forEach", start)
        assertTrue("the hero is found: $start, $end", start >= 0 && end > start)
        return src.substring(start, end)
    }

    /** D-221 (Design 4, 5; Q11 a): the hero draws the ring (Norse's runes, else the arc) round the picture and the
     *  pill under the handle; "Edit" is gone from the hero and from YouCopy; the name is the display name's size. */
    @Test
    fun you_hero_draws_the_ring_and_pill() {
        val h = hero()
        // D-243 (amended on purpose): Norse's ring takes the art's turn (Modifier.artTurn); the choice is the same.
        assertTrue("the ring is chosen by theme", Regex("""if \(Appearance\.theme == AppTheme\.NORSE\) \{\s*RuneRing\(done, total, dark, Modifier\.artTurn \{ RingTurn\.degrees\(it\) \}\)\s*\} else \{\s*CompletionRing\(done, total, dark\)\s*\}""").containsMatchIn(h))
        assertTrue("the pill", "ProgressPill(done, total, dark)" in h)
        assertTrue("the ring's box", "Modifier.size(ThemeArt.m(\"ringBox\").dp)" in h)
        assertTrue("the picture stays 104", "AvatarView(avatar, name, 104.dp, dark)" in h)
        assertFalse("no Edit link in the hero", "YouCopy.EDIT" in h)
        assertFalse("no Edit in YouCopy", YouCopy::class.java.declaredFields.any { it.name == "EDIT" })
        assertTrue("the hero's words carry the pill", "StepCopy.pill(done, total)" in h)
        assertTrue("the name's size", "fontSize = TypeScale.displayName.sp" in body("AccountHubScreen.kt", "object YouStyle"))
    }

    /** D-221 (Design 4): the rings are the art table's: the arc's track and fill, Norse's 24 runes lit by
     *  `RuneRing.lit` in the rune face, unlit in `runeUnlit`; both hidden from TalkBack (the pill carries the count). */
    @Test
    fun the_ring_is_the_art_table() {
        val arc = body("YouProgress.kt", "fun CompletionRing(")
        for (key in listOf("ringBox", "ringRadius", "ringStroke")) assertTrue("arc reads $key", "ThemeArt.m(\"$key\")" in arc)
        assertTrue("the track", "Tok.ringTrack(dark)" in arc)
        assertTrue("the fill", "Tok.ring(dark)" in arc)
        assertTrue("from 12 o'clock", "startAngle = -90f" in arc)
        assertTrue("round caps", "cap = StrokeCap.Round" in arc)
        assertTrue("hidden from TalkBack", ".clearAndSetSemantics {}" in arc)
        val runes = body("YouProgress.kt", "fun RuneRing(")
        for (key in listOf("ringBox", "runeRingRadius", "runeRingSize")) assertTrue("runes read $key", "\"$key\"" in runes)
        assertTrue("the 24 runes", "Runes.FUTHARK.forEachIndexed" in runes)
        assertTrue("lit by the rule", "RuneRing.lit(done, total)" in runes)
        assertTrue("in the rune face", "Type.rune(runeSize(\"runeRingSize\", density))" in runes)
        assertTrue("lit in ring, else unlit", "if (i < lit) Tok.ring(dark) else ThemeArt.color(\"runeUnlit\")" in runes)
        assertTrue("turned by its angle", ".rotate(angle)" in runes)
        assertTrue("hidden from TalkBack", ".clearAndSetSemantics {}" in runes)
        assertEquals("15 degrees apart", 360f / Runes.FUTHARK.size, 15f, 0f)
    }

    /** D-221 (Design 5, L40): the pill is C2's accent pill on the page in the pill's size; Vardr Labs leads it with
     *  the dot. */
    @Test
    fun the_pill_is_the_pill() {
        val pill = body("YouProgress.kt", "fun ProgressPill(")
        assertTrue("C2's pill", ".themePill(PillTone.ACCENT, dark, onPage = true)" in pill)
        assertTrue("its words", "StepCopy.pill(done, total)" in pill)
        assertTrue("its words' colour", "ThemePills.text(PillTone.ACCENT, dark)" in pill)
        assertTrue("its size", "TypeScale.pill.sp" in pill)
        assertTrue("the hero chips' padding", ".padding(horizontal = 12.dp, vertical = 6.dp)" in pill)
        assertTrue("the dot by the decision", "if (ProgressPill.showsDot(Appearance.theme))" in pill)
        assertTrue("the dot's size", "ThemeArt.m(\"progressDot\")" in pill)
        assertTrue("the dot's gap", "ThemeArt.m(\"progressDotGap\")" in pill)
        assertFalse("no shape of its own", "RoundedCornerShape" in pill)
    }

    /** D-221 (Design 9, 10): the next card's "Do it" is the compact primary, and every place a step names has its
     *  destination (a `when` with no `else`, so a new place is a compile error). */
    @Test
    fun next_step_card_uses_the_compact_primary() {
        val card = body("AccountHubScreen.kt", "private fun NextStepCard(")
        assertEquals("one compact Do it", 1, card.split("PrimaryAction(StepCopy.DO_IT, dark, compact = true").size - 1)
        assertTrue("the title", "StepCopy.title(step)" in card)
        assertTrue("the why line", "StepCopy.why(step)" in card)
        assertTrue("on glass", "Modifier.glassCard(dark)" in card)
        val src = main("AccountHubScreen.kt")
        val at = src.indexOf("when (StepPlace.of(step)) {")
        assertTrue("the destinations", at >= 0)
        val block = src.substring(at, src.indexOf("\n        }", at))
        for (place in StepPlace.entries) assertEquals("$place has one destination", 1, block.split("StepPlace.${place.name} ->").size - 1)
        assertFalse("no else", "else ->" in block)
        val h = hero()
        assertTrue("the card skips the username while its card shows", "SetupSteps.next(it, skipping = if (usernameState == UsernameState.None) setOf(SetupStep.USERNAME) else emptySet())" in h)
        val card1 = h.indexOf("UsernameCard(dark, onPickUsername)")
        val next = h.indexOf("NextStepCard(")
        val deletion = h.indexOf("DeletionPendingCard(dark,")
        assertTrue("after the username card, before the deletion notice: $card1 $next $deletion", next in (card1 + 1) until deletion)
    }
}

/** The string literals of a Swift declaration's body (from [decl] to the next line that is a lone `}`), comments
 *  dropped, each `\(…)` interpolation read as `§`. A missing file or declaration is an assertion, never a crash. */
object SwiftCopy {
    /** D-221 fix wave (S13): a Swift `switch` function's `case .x: return "..."` lines, as case name to string, from
     *  [decl] to the function's closing brace (four spaces in). */
    fun cases(file: String, decl: String): Map<String, String> {
        val src = java.io.File("../../ios/Skipti/App/$file").readText()
        val start = src.indexOf(decl)
        assertTrue("$file has `$decl`", start >= 0)
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        return Regex("""case \.(\w+): return "((?:[^"\\\n]|\\.)*)"""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
    }

    fun literals(file: String, decl: String): Set<String> {
        val f = java.io.File("../../ios/Skipti/App/$file")
        assertTrue("$file exists", f.exists())
        val src = f.readText()
        val start = src.indexOf("$decl {")
        assertTrue("$file has `$decl {`", start >= 0)
        val end = src.indexOf("\n}\n", start)
        assertTrue("$decl closes", end > start)
        val body = src.substring(start, end).lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        return Regex(""""((?:[^"\\\n]|\\.)*)"""").findAll(body)
            .map { m -> m.groupValues[1].replace(Regex("""\\\([^)]*\)"""), "§").replace("\\\"", "\"") }
            .toSet()
    }
}
