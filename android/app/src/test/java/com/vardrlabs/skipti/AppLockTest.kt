package com.vardrlabs.skipti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-163 (SPEC §3.7): the app lock's rules on the JVM; the twin of iOS's
 *  AppLockTests. */
class AppLockTest {
    private val t0 = 1_000_000_000L

    @Test
    fun the_default_is_two_minutes_and_an_unknown_setting_never_turns_it_off() {
        assertEquals(LockTimeout.TWO_MINUTES, LockTimeout.parse(null))
        assertEquals(LockTimeout.TWO_MINUTES, LockTimeout.parse("sometimes"))
        for (timeout in LockTimeout.entries) assertEquals(timeout, LockTimeout.parse(timeout.stored))
        assertEquals(
            listOf("Immediately", "After 1 minute", "After 2 minutes", "After 5 minutes", "After 15 minutes", "Off"),
            LockTimeout.entries.map { it.label },
        )
    }

    @Test
    fun it_locks_at_start_only_when_on_and_possible() {
        assertTrue(AppLockRules.lockedAtLaunch(LockTimeout.TWO_MINUTES, true))
        assertFalse(AppLockRules.lockedAtLaunch(LockTimeout.OFF, true))
        assertFalse("no screen lock, no app lock", AppLockRules.lockedAtLaunch(LockTimeout.TWO_MINUTES, false))
    }

    @Test
    fun time_away_decides_and_a_clock_that_went_back_locks() {
        fun locks(timeout: LockTimeout, afterSeconds: Long, available: Boolean = true) =
            AppLockRules.locksOnReturn(timeout, available, t0, t0 + afterSeconds * 1000)
        assertFalse(locks(LockTimeout.TWO_MINUTES, 119))
        assertTrue("at the limit", locks(LockTimeout.TWO_MINUTES, 120))
        assertTrue(locks(LockTimeout.IMMEDIATELY, 0))
        assertFalse(locks(LockTimeout.FIFTEEN_MINUTES, 899))
        assertTrue(locks(LockTimeout.FIFTEEN_MINUTES, 900))
        assertFalse("off never locks", locks(LockTimeout.OFF, 100_000))
        assertFalse(locks(LockTimeout.TWO_MINUTES, 600, available = false))
        assertTrue("setting the clock back cannot skip the lock", locks(LockTimeout.FIFTEEN_MINUTES, -5))
        assertFalse(
            "never left the front: nothing to count",
            AppLockRules.locksOnReturn(LockTimeout.IMMEDIATELY, true, null, t0),
        )
    }

    /** D-232 final wave: a configuration change is not leaving the app. One table of lifecycle sequences through the
     *  pure transitions; each step is the callback MainActivity makes (`created` in onCreate, `stopped` in onStop with
     *  isChangingConfigurations, `started` in onStart, `launched` when the vault opens). A new process is a fresh
     *  LockLife. Times are seconds after t0. */
    @Test
    fun a_configuration_change_is_not_leaving_the_app() {
        val on = true
        fun run(timeout: LockTimeout, steps: List<String>, from: AppLockRules.LockLife = AppLockRules.LockLife()): Boolean {
            var life = from
            for (step in steps) {
                val (what, at) = step.split("@").let { it[0] to (it.getOrNull(1)?.toLong() ?: 0L) }
                val now = t0 + at * 1000
                life = when (what) {
                    "create" -> AppLockRules.created(life, at * 1000)
                    "start" -> AppLockRules.started(life, timeout, on, now)
                    "launch" -> AppLockRules.launched(life, timeout, on)
                    "stop" -> AppLockRules.stopped(life, false, now, at * 1000)
                    "config" -> AppLockRules.stopped(life, true, now, at * 1000)
                    "unlock" -> life.copy(locked = false)
                    else -> error(what)
                }
            }
            return life.locked
        }
        // A cold launch of a ledger used before, unlocked, in front.
        val open = listOf("create", "start", "launch", "unlock")
        // The activity made again for a configuration change: stop, then a new activity's create, start and launch.
        fun remade(at: Int) = listOf("config@$at", "create@$at", "start@$at", "launch@$at")
        val rows = listOf(
            Triple("cold launch locks", LockTimeout.TWO_MINUTES, listOf("create", "start", "launch")) to true,
            Triple("rotation while unlocked, Immediately", LockTimeout.IMMEDIATELY, open + remade(5)) to false,
            Triple("theme change while unlocked, 2 minutes", LockTimeout.TWO_MINUTES, open + remade(500)) to false,
            Triple("two changes in a row", LockTimeout.IMMEDIATELY, open + remade(5) + remade(6)) to false,
            Triple("a change on the lock screen stays locked", LockTimeout.IMMEDIATELY, listOf("create", "start", "launch") + remade(5)) to true,
            Triple("Home, back before the timeout", LockTimeout.TWO_MINUTES, open + listOf("stop@10", "start@129")) to false,
            Triple("Home, back after the timeout", LockTimeout.TWO_MINUTES, open + listOf("stop@10", "start@130")) to true,
            Triple("Home, back, Immediately", LockTimeout.IMMEDIATELY, open + listOf("stop@10", "start@10")) to true,
            Triple("screen off, on after the timeout", LockTimeout.ONE_MINUTE, open + listOf("stop@10", "start@70")) to true,
            Triple(
                "a change while in the background still locks on return",
                LockTimeout.TWO_MINUTES, open + listOf("stop@10", "config@60", "create@200", "start@200", "launch@200"),
            ) to true,
            Triple(
                "a change while in the background, back early: the launch still locks as before",
                LockTimeout.TWO_MINUTES, open + listOf("stop@10", "config@20", "create@30", "start@30", "launch@30"),
            ) to true,
            Triple(
                "a change while in the background, re-created within the window: still not in front",
                LockTimeout.TWO_MINUTES, open + listOf("stop@10", "config@29", "create@30", "start@30", "launch@30"),
            ) to true,
            Triple(
                "Back out, then open again: the new activity's launch locks as before",
                LockTimeout.TWO_MINUTES, open + listOf("stop@10", "create@20", "start@20", "launch@20"),
            ) to true,
            Triple(
                "made again, then Home before the vault opened: the launch locks as before",
                LockTimeout.TWO_MINUTES, open + listOf("config@5", "create@5", "start@5", "stop@6", "start@30", "launch@30"),
            ) to true,
            // Confirmation C1: made again before the process's first launch decision (a rotation at once, decision
            // 78's first tell): the new activity's launch decides, and locks.
            Triple("made again before the first launch, 2 minutes", LockTimeout.TWO_MINUTES, listOf("create", "start") + remade(1)) to true,
            Triple("made again before the first launch, Immediately", LockTimeout.IMMEDIATELY, listOf("create", "start") + remade(1)) to true,
            Triple("made again twice before the first launch", LockTimeout.TWO_MINUTES, listOf("create", "start", "config@1", "create@1", "start@1") + remade(2)) to true,
            Triple("a change after a real unlock still keeps it open", LockTimeout.TWO_MINUTES, open + listOf("stop@10", "start@200", "unlock@201") + remade(202)) to false,
            // Confirmation 2 (h1): the in-front mark holds only for a create within the window (2 s) of its stop.
            Triple("re-created 1 s after the change: kept open", LockTimeout.TWO_MINUTES, open + listOf("config@10", "create@11", "start@11", "launch@11")) to false,
            Triple("re-created 3 s after the change, 2 minutes: locks", LockTimeout.TWO_MINUTES, open + listOf("config@10", "create@13", "start@13", "launch@13")) to true,
            Triple("re-created 3 s after the change, Immediately: locks", LockTimeout.IMMEDIATELY, open + listOf("config@10", "create@13", "start@13", "launch@13")) to true,
            Triple("off never locks, made again or not", LockTimeout.OFF, listOf("create", "start", "launch") + remade(5) + listOf("stop@6", "start@9999")) to false,
        )
        for ((row, expected) in rows) assertEquals(row.first, expected, run(row.second, row.third))
        // Process death and restore: the old process's facts are gone (even a change it was making), so the new
        // process's launch locks.
        val dying = AppLockRules.stopped(AppLockRules.LockLife(), true, t0, 5_000)
        assertEquals("the dying process was mid-change", 5_000L, dying.stoppedInFrontAtMs)
        assertTrue("process death, then restore, locks", run(LockTimeout.TWO_MINUTES, listOf("create", "start", "launch")))
        assertTrue(
            "process death after a decision: the new process decides again",
            run(LockTimeout.TWO_MINUTES, listOf("create", "start", "launch")) &&
                !AppLockRules.LockLife().launchDecided,
        )
        // The stop that is a change keeps an earlier time away.
        val away = AppLockRules.stopped(AppLockRules.LockLife(), false, t0, 0)
        assertEquals("a change in the background keeps the time away", t0, AppLockRules.stopped(away, true, t0 + 50_000, 50_000).backgroundedAtMs)
        // h1: the window's edges, and a monotonic clock read that would fail open (below zero, or huge) never honours it.
        assertEquals(2_000L, AppLockRules.IN_FRONT_WINDOW_MS)
        fun honoured(stopAt: Long, createAt: Long) =
            AppLockRules.created(AppLockRules.LockLife(stoppedInFrontAtMs = stopAt), createAt).recreatedInFront
        assertTrue("at once", honoured(10_000, 10_000))
        assertTrue("at the edge", honoured(10_000, 12_000))
        assertFalse("past the edge", honoured(10_000, 12_001))
        assertFalse("the clock went back", honoured(10_000, 9_999))
        assertFalse("a huge gap", honoured(0, Long.MAX_VALUE))
        assertFalse("no mark", AppLockRules.created(AppLockRules.LockLife(), 10_000).recreatedInFront)
        assertEquals("the mark is used once", null, AppLockRules.created(AppLockRules.LockLife(stoppedInFrontAtMs = 1), 1).stoppedInFrontAtMs)
    }

    /** D-232 final wave: MainActivity passes isChangingConfigurations to the lock in onStop and tells it of each new
     *  activity in onCreate, before the vault can open. */
    @Test
    fun main_activity_tells_the_lock_of_configuration_changes() {
        val root = s225("AppRoot.kt")
        val stop = root.substring(root.indexOf("override fun onStop()")).substringBefore("\n    }")
        assertTrue("onStop: $stop", "if (proofArg() == null) AppLock.wentToBackground(isChangingConfigurations)" in stop)
        val create = root.substring(root.indexOf("override fun onCreate(")).substringBefore("\n    }\n")
        val made = create.indexOf("AppLock.created()")
        assertTrue("onCreate tells the lock", made >= 0)
        assertTrue("before anything opens the vault", made < create.indexOf("VaultGate.appContextRef") && made < create.indexOf("setContent {"))
        assertEquals("one call", 1, root.split("AppLock.created()").size - 1)
        assertEquals("the launch is the vault's", 1, root.split("AppLock.launch(context)").size - 1)
    }

    @Test
    fun the_copy_says_what_it_does() {
        assertEquals(
            "Anyone who picks up your unlocked phone could open SKIPTI and see your ledger.",
            AppLockCopy.CONFIRM_BODY,
        )
        assertEquals("SKIPTI is locked", AppLockCopy.TITLE)
    }

    /** D-225 (L67 to L70): the Security page's rows, one table (iOS's AppLockTests pins the same). App lock's summary is
     *  the timeout's label, or "Needs a phone lock" whatever the timeout on a phone that cannot lock; the careful card is
     *  Erase and Delete account; SettingsMap's order splits into the two cards. */
    @Test
    fun the_security_rows() {
        val labels = listOf(
            LockTimeout.IMMEDIATELY to "Immediately", LockTimeout.ONE_MINUTE to "After 1 minute",
            LockTimeout.TWO_MINUTES to "After 2 minutes", LockTimeout.FIVE_MINUTES to "After 5 minutes",
            LockTimeout.FIFTEEN_MINUTES to "After 15 minutes", LockTimeout.OFF to "Off",
        )
        assertEquals(LockTimeout.entries.toList(), labels.map { it.first })
        for ((timeout, label) in labels) {
            assertEquals(label, SecurityRows.appLockLine(timeout, available = true))
            assertEquals("$timeout", "Needs a phone lock", SecurityRows.appLockLine(timeout, available = false))
        }
        assertEquals(setOf(SettingItem.ERASE, SettingItem.DELETE_ACCOUNT), SecurityRows.CAREFUL)
        assertEquals(
            listOf(SettingItem.APP_LOCK, SettingItem.PRIVACY) to listOf(SettingItem.ERASE),
            SecurityRows.partition(SettingsMap.items(SettingsSection.SECURITY, signedIn = false)),
        )
        assertEquals(
            listOf(SettingItem.APP_LOCK, SettingItem.PRIVACY, SettingItem.PHONES) to listOf(SettingItem.ERASE, SettingItem.DELETE_ACCOUNT),
            SecurityRows.partition(SettingsMap.items(SettingsSection.SECURITY, signedIn = true)),
        )
        assertEquals("Needs a phone lock", SecurityRows.NO_PHONE_LOCK)
        assertEquals("Asks first. Cannot be undone.", SecurityRows.ERASE_SUMMARY)
        assertEquals("A week to change your mind", SecurityRows.DELETE_ACCOUNT_SUMMARY)
        for (line in listOf(SecurityRows.NO_PHONE_LOCK, SecurityRows.ERASE_SUMMARY, SecurityRows.DELETE_ACCOUNT_SUMMARY)) {
            assertFalse("no em dash: $line", '\u2014' in line)  // em-dash-ok: the escape builds the dash this test asserts is absent
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

    // ---- D-225 Task 5: the Security page, the App lock page, the Erase page and the lock screen (twins of iOS's Task 4) ----

    /** D-225 (Design 4; L67 to L70): Security is two list cards: the safe one (App lock, the privacy switch, Your phones),
     *  then "Careful" alone (no hairline) over the danger card (Erase, Delete account, both in the danger tone). The erase
     *  row opens the Erase page; it never erases from the row. The twin of iOS's testSecurityIsTwoListCards. */
    @Test
    fun security_is_two_list_cards() {
        val page = body225("AccountHubScreen.kt", "private fun SecurityPage(")
        val first = page.indexOf("ListCard(dark)")
        val label = page.indexOf("SectionLabel(YouCopy.CAREFUL")
        val second = page.indexOf("ListCard(dark)", first + 1)
        assertTrue("safe card, then Careful, then the careful card", first in 0 until label && label < second)
        assertEquals("two cards", 2, page.split("ListCard(dark)").size - 1)
        assertEquals("both careful rows in the danger tone", 2, page.substring(second).split("tone = RowTone.DANGER").size - 1)
        assertFalse("no danger row in the safe card", "RowTone.DANGER" in page.substring(0, label))
        for (s in listOf(
            "SecurityRows.partition(SettingsMap.items(SettingsSection.SECURITY, signedIn))",
            "SecurityRows.appLockLine(AppLock.timeout, lockAvailable)",
            "PrivacyRow(dark)", "SecurityRows.ERASE_SUMMARY", "SecurityRows.DELETE_ACCOUNT_SUMMARY",
            "onOpenErase()", "onOpenDelete()", "onOpenPhones()", "AppLockPage(dark)",
        )) assertTrue("Security: $s", s in page)
        assertTrue("the warning first", page.indexOf("ModifiedPhoneWarning(dark)") in 0 until first)
        assertFalse("no hairline under Careful", "Tok.hairline" in page)
        val hub = s225("AccountHubScreen.kt")
        for (gone in listOf("LedgerErase", "EraseLedgerButton(", "AppLockSettings(", "PrivacySettings(", "CurrencySettings(", "fun SectionHeader(", "DangerAction(PhonesCopy.DELETE_TITLE")) {
            assertFalse("AccountHubScreen.kt keeps $gone", gone in hub)
        }
    }

    /** D-225 (Design 4): the App lock page is a choice page; Off asks first and writes nothing until "Turn off" is
     *  tapped (D-224's warning haptic as it opens); every other choice writes at once. The twin of iOS's
     *  testTheAppLockPageConfirmsOff. */
    @Test
    fun the_app_lock_page_confirms_off() {
        val page = body225("AppLock.kt", "fun AppLockPage(")
        for (s in listOf(
            "ChoicePage(", "LockTimeout.entries.map { ChoiceOption(it, it.label) }",
            "AppLockRules.asksToTurnOff(choice, AppLock.timeout) -> confirmingOff = true",
            "choice == LockTimeout.OFF -> onClose()", "else -> AppLock.set(context, choice)",
            "stays = { it == LockTimeout.OFF }", "HapticWarning(confirmingOff)", "title = { Text(AppLockCopy.CONFIRM_TITLE) }",
            "AppLockCopy.UNAVAILABLE",
        )) assertTrue("AppLockPage: $s", s in page)
        val off = "AppLock.set(context, LockTimeout.OFF)"
        assertEquals("Off is written in one place", 1, s225("AppLock.kt").split(off).size - 1)
        assertTrue("only inside the alert's Turn off", page.indexOf("confirmButton = {") in 0 until page.indexOf(off))
        assertFalse("no chips", "FilterChip" in s225("AppLock.kt"))
        assertFalse("AppLockSettings is gone", "fun AppLockSettings(" in s225("AppLock.kt"))
        // D-225 fix wave (M6): Off asks only when the lock is on; Off while off just goes back.
        for (current in LockTimeout.entries) {
            assertEquals("Off over $current", current != LockTimeout.OFF, AppLockRules.asksToTurnOff(LockTimeout.OFF, current))
            for (choice in LockTimeout.entries.filter { it != LockTimeout.OFF }) {
                assertFalse("$choice never asks", AppLockRules.asksToTurnOff(choice, current))
            }
        }
    }

    /** D-225 (Design 4): the Erase page says what goes, then the erase button, whose alert (and its warning haptic) is
     *  unchanged (D-127: never automatic). The twin of iOS's testTheEraseScreenKeepsItsConfirmation. */
    @Test
    fun the_erase_screen_keeps_its_confirmation() {
        val screen = body225("AccountOwner.kt", "fun EraseScreen(")
        for (s in listOf("PushedPage(EraseCopy.TITLE, dark, onBack", "EraseCopy.DETAIL", "EraseLedgerButton(vaultPath, dark)", "glassCard(dark)")) {
            assertTrue("EraseScreen: $s", s in screen)
        }
        val button = body225("AccountOwner.kt", "fun EraseLedgerButton(")
        assertTrue("title = { Text(EraseCopy.CONFIRM_TITLE) }" in button && "HapticWarning(confirming)" in button)
        assertTrue("erases only from the alert", button.indexOf("confirmButton = {") in 0 until button.indexOf("LedgerErase.eraseThisPhone(context, vaultPath)"))
    }

    /** D-225: the Erase page returns to Security, and the route is wired from the Security row. */
    @Test
    fun erase_route_returns_to_security() {
        assertEquals("account/security", HomeRoutes.parent("erase"))
        val root = s225("AppRoot.kt")
        assertTrue("onOpenErase = { route = \"erase\" }" in root)
        assertTrue("EraseScreen(vaultPath, onBack = { route = HomeRoutes.parent(\"erase\") })" in root)
    }

    /** D-225 (Design 13): the lock screen draws Lucide's lock at `lockGlyph` in `textDim` above the title at
     *  `TypeScale.lockTitle`, on the art ground. The twin of iOS's testTheLockScreenTwins. */
    @Test
    fun the_lock_screen_twins() {
        val lock = body225("AppLock.kt", "fun AppLockScreen(")
        for (s in listOf("TabIcons.Lock", "ThemeArt.m(\"lockGlyph\")", "tint = Tok.textDim(dark)", "TypeScale.lockTitle", ".auroraBackground(dark)")) {
            assertTrue("AppLockScreen: $s", s in lock)
        }
        assertTrue("the glyph above the title", lock.indexOf("TabIcons.Lock") < lock.indexOf("AppLockCopy.TITLE"))
        assertFalse("no literal title size", "fontSize = 28.sp" in lock)
        assertEquals(40.0, ThemeArt.m("lockGlyph").toDouble(), 0.0)
        assertEquals(28.0, TypeScale.lockTitle.toDouble(), 0.0)
    }
}
