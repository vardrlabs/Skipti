package com.vardrlabs.skipti

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault

/** D-168: one account per phone (the founder, 2026-09-25); D-192: the owner
 *  is the Firebase uid (AccountTest covers the rule itself). The twin of iOS's
 *  AccountOwnerTests. */
class AccountOwnerTest {
    private class MemoryKeystore : SkiptiKeystore {
        var key: ByteArray? = null
        var destroyed = false
        override fun provision(key: ByteArray) { this.key = key }
        override fun load(): ByteArray? = key
        override fun destroy() { key = null; destroyed = true }
    }

    private class Phone {
        val keystore = MemoryKeystore()
        val path: String = Files.createTempDirectory("owner").resolve("ledger-vault.db").toString()
        fun store() = LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    /** D-172: how the phone signed in is kept for Settings, and a stored
     *  value this build does not know is no method at all. */
    @Test
    fun the_sign_in_method_is_kept_and_an_unknown_one_is_none() {
        assertEquals(SignInMethod.Apple, SignInMethod.decode("apple"))
        assertEquals(SignInMethod.Google, SignInMethod.decode("google"))
        assertEquals(SignInMethod.Email("tim@example.com"), SignInMethod.decode("email:tim@example.com"))
        assertNull(SignInMethod.decode("email:"))
        assertNull(SignInMethod.decode("facebook"))
        assertNull(SignInMethod.decode("Apple"))
        for (method in listOf(SignInMethod.Apple, SignInMethod.Google, SignInMethod.Email("a@b.c"))) {
            assertEquals(method, SignInMethod.decode(method.encoded))
        }
        assertEquals("Signed in with Apple", SignInMethod.Apple.label)
        assertEquals("Signed in with Google", SignInMethod.Google.label)
        // D-234 (decision 84): You's row says the method in one word, never an address.
        assertEquals("Apple", SignInMethod.Apple.rowLabel)
        assertEquals("Google", SignInMethod.Google.rowLabel)
        assertEquals("Email", SignInMethod.Email("a@b.c").rowLabel)

        val store = Phone().store()
        assertNull(SignInMethod.stored(store))
        SignInMethod.remember(SignInMethod.Apple, store)
        assertEquals(SignInMethod.Apple, SignInMethod.stored(store))
        SignInMethod.remember(SignInMethod.Google, store)
        assertEquals("the latest way in", SignInMethod.Google, SignInMethod.stored(store))
    }

    /** D-168: the erase takes the vault's files and its key; a new ledger at
     *  the same place has no owner, so any account may sign in there. */
    @Test
    fun the_erase_takes_the_files_and_the_key_and_leaves_a_fresh_ledger() {
        val phone = Phone()
        phone.store().rawKvSet(Account.OWNER_KEY, "uid-tim".toByteArray(Charsets.UTF_8))
        phone.store().ensureSelf()
        assertEquals("uid-tim", Account.storedOwner(phone.store()))
        assertTrue(File(phone.path).exists())

        LedgerErase.erase(phone.path, phone.keystore)
        assertFalse(File(phone.path).exists())
        assertTrue(phone.keystore.destroyed)

        assertNull(Account.storedOwner(phone.store()))
        assertNull(phone.store().loadSelf())
        assertTrue(Account.owns(Account.storedOwner(phone.store()), "uid-bob"))
    }

    // ---- D-225 T7 (A7, the founder 2026-10-17; D-127): Android's twin of iOS's VaultRecoveryView ----

    /** A vault that exists and will not open (its key is not on this phone) routes the launch to the recovery screen
     *  with the honest reason, and writes and erases nothing: the files are still there, and the right key still opens
     *  them. A fresh phone opens (a first launch, then a launch before). */
    @Test
    fun a_vault_that_will_not_open_routes_to_the_recovery_screen() {
        val phone = Phone()
        phone.store().rawKvSet("probe", byteArrayOf(7))
        assertEquals(VaultRecovery.Launch.Opened(launchedBefore = false), VaultRecovery.launch("app/launched") { phone.store() })
        assertEquals(VaultRecovery.Launch.Opened(launchedBefore = true), VaultRecovery.launch("app/launched") { phone.store() })
        val stranger = MemoryKeystore().apply { provision(ByteArray(32) { 9 }) }
        val launch = VaultRecovery.launch("app/launched") { LedgerStore(phone.path) { SkiptiVault.open(phone.path, stranger) } }
        assertEquals(VaultRecovery.Launch.Unavailable(VaultRecoveryCopy.WRONG_KEY), launch)
        assertTrue("nothing erased", File(phone.path).exists())
        assertFalse("the key is kept", phone.keystore.destroyed)
        assertEquals("the right key still opens it", 7.toByte(), phone.store().rawKvGet("probe")!!.single())
        // The shell shows the screen for it, and runs the erase only from the screen's confirm.
        val root = File("src/main/java/com/vardrlabs/skipti/AppRoot.kt").readText()
        assertFalse("never swallowed as a launch", "getOrDefault(true)" in root)
        assertTrue("is VaultRecovery.Launch.Unavailable -> vaultScreen = VaultRecovery.Screen(launch.reason)" in root)
        val shown = root.substringAfter("vaultScreen?.let { screen ->").substringBefore("        return\n")
        assertTrue("the recovery screen", "VaultRecoveryScreen(" in shown)
        assertEquals("one erase, inside the screen's erase callback", 1, root.split("LedgerErase.signOutAndErase(vaultPath").size - 1)
        val erase = shown.substringAfter("onErase = {")
        assertTrue("the erase is the screen's", "LedgerErase.signOutAndErase(vaultPath" in erase)
    }

    /** D-225 fix wave (I1, A9): a launch that fails with an Error (not an Exception) is not a locked vault: it is not
     *  caught, so it is not shown as one. A thrown Exception is the recovery screen. */
    @Test
    fun only_an_exception_is_a_ledger_that_will_not_open() {
        val unavailable = VaultRecovery.launch("app/launched") { throw IllegalStateException("no") }
        assertEquals(VaultRecovery.Launch.Unavailable("no"), unavailable)
        val thrown = runCatching { VaultRecovery.launch("app/launched") { throw OutOfMemoryError("oom") } }.exceptionOrNull()
        assertTrue("an Error is not swallowed into the screen: $thrown", thrown is OutOfMemoryError)
    }

    /** D-225 fix wave (I1, A9, iOS's testTryAgainThatOpensTheLedgerGoesOnAsANormalLaunch): Try again that opens the
     *  ledger leaves the screen, and the shell goes on as a normal launch (the app lock for a ledger opened before),
     *  through the same step the launch takes. */
    @Test
    fun try_again_that_opens_the_ledger_goes_on_as_a_normal_launch() {
        assertNull(VaultRecovery.retried(VaultRecovery.Launch.Opened(launchedBefore = true)))
        assertNull(VaultRecovery.retried(VaultRecovery.Launch.Opened(launchedBefore = false)))
        val root = File("src/main/java/com/vardrlabs/skipti/AppRoot.kt").readText()
        // D-245: goOn also takes whether the welcome shows (read from the vault by the same two paths).
        val goOn = root.substringAfter("fun goOn(opened: VaultRecovery.Launch.Opened, welcomeShows: Boolean) {").substringBefore("\n    }\n")
        assertTrue("the lock for a ledger opened before", "if (opened.launchedBefore) AppLock.launch(context)" in goOn)
        assertTrue("the screen goes", "vaultScreen = null" in goOn)
        assertEquals("the launch and the retry both go on through it", 2, Regex("""goOn\(""").findAll(root).count() - 1)
        val retry = root.substringAfter("onRetry = {").substringBefore("onErase = {")
        assertTrue("the retry reads the vault as the launch does", "readVault()" in retry)
        assertTrue("and decides with retried", "VaultRecovery.retried(launch)" in retry)
        assertFalse("Try again erases nothing", "LedgerErase" in retry)
    }

    /** D-225 fix wave (I1, A9, iOS's testTryAgainThatStillFailsKeepsTheScreen): Try again that still cannot open the
     *  ledger keeps the screen, with the reason read now and the retry line; the files and the key are untouched. */
    @Test
    fun try_again_that_still_fails_keeps_the_screen() {
        val phone = Phone()
        phone.store().rawKvSet("probe", byteArrayOf(7))
        val stranger = MemoryKeystore().apply { provision(ByteArray(32) { 9 }) }
        val launch = VaultRecovery.launch("app/launched") { LedgerStore(phone.path) { SkiptiVault.open(phone.path, stranger) } }
        val screen = VaultRecovery.retried(launch)
        assertEquals(VaultRecovery.Screen(VaultRecoveryCopy.WRONG_KEY, VaultRecovery.Note.RETRY_FAILED), screen)
        assertEquals("Tried again just now. It still will not open.", screen!!.note!!.text)
        assertTrue("nothing erased", File(phone.path).exists())
        assertFalse("the key is kept", phone.keystore.destroyed)
        assertEquals(7.toByte(), phone.store().rawKvGet("probe")!!.single())
    }

    /** D-225 fix wave (I1, A9, iOS's testAFailedEraseShowsTheFailedWordingAndKeepsTheScreen): an erase that fails keeps
     *  the screen with its reason and says so in EraseCopy's words; one that works leaves the screen (the launch runs
     *  again). Never swallowed. */
    @Test
    fun a_failed_erase_shows_the_failed_wording_and_keeps_the_screen() {
        val failed = VaultRecovery.erase(VaultRecoveryCopy.WRONG_KEY) { throw java.io.IOException("refused") }
        assertEquals(VaultRecovery.Screen(VaultRecoveryCopy.WRONG_KEY, VaultRecovery.Note.ERASE_FAILED), failed)
        assertEquals(EraseCopy.FAILED, failed!!.note!!.text)
        var ran = 0
        assertNull(VaultRecovery.erase(VaultRecoveryCopy.WRONG_KEY) { ran++ })
        assertEquals("the erase ran once", 1, ran)
        // A real refusal: the files stay, the key stays.
        val phone = Phone()
        phone.store().rawKvSet("probe", byteArrayOf(7))
        val refused = VaultRecovery.erase("why") { LedgerErase.erase(phone.path, phone.keystore) { false } }
        assertEquals(VaultRecovery.Note.ERASE_FAILED, refused!!.note)
        assertTrue(File(phone.path).exists())
        assertFalse(phone.keystore.destroyed)
        // The shell keeps the screen with what the erase returned (it used to drop the result and launch again).
        val root = File("src/main/java/com/vardrlabs/skipti/AppRoot.kt").readText()
        val erase = root.substringAfter("vaultScreen?.let { screen ->").substringBefore("        return\n").substringAfter("onErase = {")
        assertTrue("the erase through the decision", "VaultRecovery.erase(screen.reason) { LedgerErase.signOutAndErase(vaultPath" in erase)
        assertTrue("a failure stays on the screen", Regex("""\} else \{\s*vaultScreen = after\s*\}""").containsMatchIn(erase))
    }

    /** D-225 fix wave (A9, the controller's decision): the erase takes the write-ahead files first, the database last,
     *  and the key only after every file is gone; a refused file stops it there, the key kept. */
    @Test
    fun the_erase_takes_the_write_ahead_files_first_and_the_key_last() {
        val phone = Phone()
        phone.store().rawKvSet("probe", byteArrayOf(7))
        for (suffix in listOf("-wal", "-shm", "-journal")) File(phone.path + suffix).writeBytes(byteArrayOf(1))
        val order = mutableListOf<String>()
        LedgerErase.erase(phone.path, phone.keystore) { f ->
            order += f.name.removePrefix("ledger-vault.db").ifEmpty { "db" }
            assertFalse("the key goes last", phone.keystore.destroyed)
            f.delete()
        }
        assertEquals(listOf("-wal", "-shm", "-journal", "db"), order)
        assertTrue(phone.keystore.destroyed)
        // iOS's erase takes the same files in the same order, then its key.
        val swift = File("../../ios/Skipti/App/AccountOwner.swift").readText()
        val files = swift.substringAfter("static func files(_ vaultPath: String) -> [String] {").substringBefore("}")
        val ios = Regex("""vaultPath(?: \+ "([^"]*)")?""").findAll(files).map { it.groupValues[1] }.toList()
        assertEquals(LedgerErase.ORDER, ios)
        val other = Phone()
        other.store().rawKvSet("probe", byteArrayOf(7))
        File(other.path + "-wal").writeBytes(byteArrayOf(1))
        val thrown = runCatching { LedgerErase.erase(other.path, other.keystore) { f -> if (f.name.endsWith("-wal")) false else f.delete() } }
        assertTrue(thrown.isFailure)
        assertTrue("the database is not touched before its write-ahead file", File(other.path).exists())
        assertFalse("the key stays", other.keystore.destroyed)
    }

    /** D-225 follow-up (WORKLOG decision 27): the recovery screen's confirmed erase signs out of the cloud session first,
     *  as the deliberate erase does (the account's owner key lives in the vault, so a session left behind would point at
     *  a ledger that no longer exists), then takes the files and the key. Both erases go through the one operation. */
    @Test
    fun the_recovery_erase_signs_out_first_as_the_deliberate_erase_does() {
        val phone = Phone()
        phone.store().rawKvSet(Account.OWNER_KEY, "uid-tim".toByteArray(Charsets.UTF_8))
        val steps = mutableListOf<String>()
        val screen = VaultRecovery.erase(VaultRecoveryCopy.WRONG_KEY) {
            LedgerErase.signOutAndErase(phone.path, phone.keystore, signOut = { steps += "sign out" }) { f ->
                steps += f.name
                f.delete()
            }
        }
        assertNull("the erase is done", screen)
        assertEquals("signed out before any file goes", listOf("sign out", "ledger-vault.db"), steps)
        assertFalse(File(phone.path).exists())
        assertTrue("the key went too", phone.keystore.destroyed)
        // A sign-out that throws erases nothing and keeps the screen with the failed wording.
        val other = Phone()
        other.store().rawKvSet("probe", byteArrayOf(7))
        val refused = VaultRecovery.erase("why") {
            LedgerErase.signOutAndErase(other.path, other.keystore, signOut = { throw IllegalStateException("no") })
        }
        assertEquals(VaultRecovery.Note.ERASE_FAILED, refused!!.note)
        assertTrue("nothing erased", File(other.path).exists())
        assertFalse("the key stays", other.keystore.destroyed)
        // The shell: the recovery erase and the deliberate erase are the same call; nothing erases without signing out.
        val root = File("src/main/java/com/vardrlabs/skipti/AppRoot.kt").readText()
        val shown = root.substringAfter("vaultScreen?.let { screen ->").substringBefore("        return\n")
        assertTrue("the recovery erase signs out", "VaultRecovery.erase(screen.reason) { LedgerErase.signOutAndErase(vaultPath, AndroidKeystore(context.applicationContext)) }" in shown)
        val owner = File("src/main/java/com/vardrlabs/skipti/AccountOwner.kt").readText()
        val deliberate = owner.substringAfter("fun eraseThisPhone(").substringBefore("\n    }\n")
        assertTrue("the deliberate erase is the same operation", "signOutAndErase(vaultPath, AndroidKeystore(context.applicationContext))" in deliberate)
        val both = owner.substringAfter("fun signOutAndErase(").substringBefore("\n    }\n")
        assertTrue("sign out, then erase", Regex("""signOut\(\)\s*\n\s*erase\(vaultPath, keystore, remove\)""").containsMatchIn(both))
        val app = AppSources.walk().filter { it.isFile && it.extension == "kt" }
            .sumOf { it.readText().split("LedgerErase.erase(").size - 1 }
        assertEquals("no erase outside the signed-out one", 0, app)
    }

    /** D-225 fix wave (I1, M10): a tap while a retry or an erase runs does nothing (the twin of iOS's busy flag). */
    @Test
    fun a_second_tap_while_working_does_nothing() {
        val run = VaultRecovery.Busy()
        assertFalse(run.working)
        assertTrue("the first tap starts", run.start())
        assertTrue(run.working)
        assertFalse("a second tap is ignored", run.start())
        run.finish()
        assertFalse(run.working)
        assertTrue("free again", run.start())
        val src = File("src/main/java/com/vardrlabs/skipti/AppRoot.kt").readText()
        val shown = src.substringAfter("vaultScreen?.let { screen ->").substringBefore("        return\n")
        assertEquals("both the retry and the erase start through it", 2, Regex("""if \(!vaultBusy\.start\(\)\) return@""").findAll(shown).count())
        assertEquals("and finish", 2, Regex("""vaultBusy\.finish\(\)""").findAll(shown).count())
        assertTrue("the screen shows it", "busy = vaultBusy.working" in shown)
    }

    /** Start fresh only asks: the erase is offered only at the confirm step, and Cancel goes back; the screen calls its
     *  erase in that step alone, from "Erase and start fresh". Try again comes first and is primary (A9). The words are
     *  iOS's, word for word (task-6-report "The exact copy for Android"). While working, every button is dimmed and
     *  inert; the retry line is hidden in the confirm step, a failed erase's is not. */
    @Test
    fun the_erase_needs_the_confirm() {
        assertEquals(listOf("Try again", "Start fresh"), VaultRecovery.actions(VaultRecovery.Step.EXPLAIN))
        assertEquals(listOf("Erase and start fresh", "Cancel"), VaultRecovery.actions(VaultRecovery.Step.CONFIRM))
        val retry = VaultRecovery.Note.RETRY_FAILED
        val erase = VaultRecovery.Note.ERASE_FAILED
        assertEquals(retry, VaultRecovery.noteShown(VaultRecovery.Step.EXPLAIN, retry))
        assertNull("the retry line is not under the erase warning", VaultRecovery.noteShown(VaultRecovery.Step.CONFIRM, retry))
        assertEquals(erase, VaultRecovery.noteShown(VaultRecovery.Step.CONFIRM, erase))
        assertEquals(erase, VaultRecovery.noteShown(VaultRecovery.Step.EXPLAIN, erase))
        assertNull(VaultRecovery.noteShown(VaultRecovery.Step.EXPLAIN, null))
        val src = File("src/main/java/com/vardrlabs/skipti/AccountOwner.kt").readText()
        val screen = src.substringAfter("fun VaultRecoveryScreen(").substringBefore("\n}\n")
        assertEquals("onErase is called once", 1, screen.split("onErase()").size - 1)
        assertEquals("onRetry is called once", 1, screen.split("onRetry()").size - 1)
        val confirm = screen.substringAfter("if (step == VaultRecovery.Step.CONFIRM) {").substringBefore("} else {")
        assertTrue("from the erase button of the confirm step", "PrimaryAction(VaultRecoveryCopy.ERASE, dark, enabled = !busy, tint = Tok.owed(dark)) { onErase() }" in confirm)
        assertTrue("Cancel goes back", "SecondaryAction(VaultRecoveryCopy.CANCEL, dark, enabled = !busy) { step = VaultRecovery.Step.EXPLAIN }" in confirm)
        assertTrue("the warning first", confirm.indexOf("VaultRecoveryCopy.WARNING") in 0 until confirm.indexOf("VaultRecoveryCopy.ERASE"))
        val explain = screen.substringAfter("} else {")
        val again = explain.indexOf("PrimaryAction(VaultRecoveryCopy.TRY_AGAIN, dark, enabled = !busy) { onRetry() }")
        val fresh = explain.indexOf("SecondaryAction(VaultRecoveryCopy.START_FRESH, dark, enabled = !busy) { step = VaultRecovery.Step.CONFIRM }")
        assertTrue("Try again first and primary, then Start fresh, which only asks: $again $fresh", again in 0 until fresh)
        assertTrue("the warning plays as the question opens", "HapticWarning(step == VaultRecovery.Step.CONFIRM)" in screen)
        assertTrue("the note through the one rule", "VaultRecovery.noteShown(step, note)" in screen)
        assertTrue("a failed erase in owed, the retry line dim",
            "if (shown == VaultRecovery.Note.ERASE_FAILED) Tok.owed(dark) else Tok.textDim(dark)" in screen)
        // iOS's order: kicker, hairline, title, reason, explanation, warning, note, buttons.
        val order = listOf("VaultRecoveryCopy.KICKER", "Tok.hairline(dark)", "VaultRecoveryCopy.TITLE", "Text(reason", "VaultRecoveryCopy.EXPLAIN",
            "VaultRecoveryCopy.WARNING", "VaultRecovery.noteShown(step, note)", "VaultRecoveryCopy.ERASE")
        assertEquals(order, order.sortedBy { screen.indexOf(it) })
        assertEquals("Ledger unavailable", VaultRecoveryCopy.KICKER)
        assertEquals("This device cannot open your ledger.", VaultRecoveryCopy.TITLE)
        assertEquals(
            "Your entries are encrypted with a key held by this device. Without that key nobody can read them: not us, not " +
                "anyone with the file. That is the guarantee working. Sometimes the key is only out of reach for a moment, " +
                "after a restart or a restore, so try again first. Starting fresh erases this ledger for good: keep it for last.",
            VaultRecoveryCopy.EXPLAIN,
        )
        assertEquals("Try again", VaultRecoveryCopy.TRY_AGAIN)
        assertEquals("Tried again just now. It still will not open.", VaultRecoveryCopy.RETRY_FAILED)
        assertEquals(
            "Starting fresh erases the ledger on this device permanently. If you have it on another device, sign in there instead.",
            VaultRecoveryCopy.WARNING,
        )
        assertFalse("no dash", '\u2014' in VaultRecoveryCopy.EXPLAIN || '\u2013' in VaultRecoveryCopy.EXPLAIN)  // em-dash-ok: the escape builds the dash this test asserts is absent
    }

    /** The words are iOS's, read out of VaultRecoveryView.swift: each Android constant is the Swift constant's text. */
    @Test
    fun the_recovery_words_are_ios_words() {
        val swift = File("../../ios/Skipti/App/VaultRecoveryView.swift").readText()
        val copy = swift.substringAfter("enum VaultRecoveryCopy {").substringBefore("\n}\n")
        fun ios(name: String): String {
            val decl = copy.substringAfter("static let $name =").substringBefore("\n    static let ").substringBefore("\n}")
            return Regex(""""((?:[^"\\]|\\.)*)"""").findAll(decl).joinToString("") { it.groupValues[1] }
        }
        assertEquals(ios("kicker"), VaultRecoveryCopy.KICKER)
        assertEquals(ios("title"), VaultRecoveryCopy.TITLE)
        assertEquals(ios("explain"), VaultRecoveryCopy.EXPLAIN)
        assertEquals(ios("tryAgain"), VaultRecoveryCopy.TRY_AGAIN)
        assertEquals(ios("retryFailed"), VaultRecoveryCopy.RETRY_FAILED)
        assertEquals(ios("startFresh"), VaultRecoveryCopy.START_FRESH)
        assertEquals(ios("eraseWarning"), VaultRecoveryCopy.WARNING)
        assertEquals(ios("erase"), VaultRecoveryCopy.ERASE)
        assertEquals(ios("cancel"), VaultRecoveryCopy.CANCEL)
        assertEquals(ios("wrongKey"), VaultRecoveryCopy.WRONG_KEY)
    }
}
