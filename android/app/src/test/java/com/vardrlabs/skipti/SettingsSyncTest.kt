package com.vardrlabs.skipti

import android.content.SharedPreferences
import com.google.firebase.Timestamp
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.RecordWire
import uniffi.skipti_core.SettingOut
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.ffiDecodeGroupOp
import uniffi.skipti_core.ffiEncodeGroupOp

/**
 * D-244 (ADR-0043): settings follow the account. The key table, the one write funnel, the apply decision, the first
 * sign-in, what goes up, the icon's front moment, and the source guards: the twin of iOS's SettingsSyncTests, test for
 * test (the same names in snake case, the same hand-written goldens from .drafts/u25/design.md sections 3 to 7, never
 * read back from the implementation), through the real core and real vaults. Android keeps its choices in two
 * preferences files under its own names; the wire key is always iOS's, and [SettingsSync.synced] is the one map between
 * them ([the_tables_are_ios_own] reads iOS's tables).
 */
class SettingsSyncTest {
    private val dir = Files.createTempDirectory("settings-sync")
    private val other = "d".repeat(32)
    private val uid = "uid-settings"

    private fun tempStore(owner: String? = null): LedgerStore {
        val keystore = object : SkiptiKeystore {
            var key: ByteArray? = null
            override fun provision(key: ByteArray) { this.key = key }
            override fun load(): ByteArray? = key
            override fun destroy() { key = null }
        }
        val path = dir.resolve("vault-${System.nanoTime()}.db").toString()
        val store = LedgerStore(path) { SkiptiVault.open(path, keystore) }
        if (owner != null) {
            store.rawKvSet(Account.OWNER_KEY, owner.toByteArray(Charsets.UTF_8))
            // D-254: an owner here is an approved account (ApprovalTest covers the waiting one).
            Approval.setCached(store, owner, ApprovalState.APPROVED)
        }
        return store
    }

    /** This phone's preferences: each file a map. */
    private class Phone {
        val files = mutableMapOf<String, MapPrefs>()
        val prefs: (String) -> SharedPreferences = { name -> files.getOrPut(name) { MapPrefs() } }
        val appearance: MapPrefs get() = prefs("skipti-appearance") as MapPrefs
        val money: MapPrefs get() = prefs("skipti-money") as MapPrefs
    }

    /** A setting record written by another phone of the account. */
    private fun theirs(key: String, value: String, millis: Long, device: String = other): RecordWire = RecordWire(
        version = 1u, recordType = LedgerStore.GROUP_OP_TYPE, groupId = null, epoch = 0u, hlcMillis = millis.toULong(),
        hlcCounter = 0u, hlcDeviceId = device, authorDeviceId = device, nonceSession = byteArrayOf(0x47, 0x52, 0x50, 0x01),
        flags = 0u, blob = ffiEncodeGroupOp(GroupOpIn.Setting(key, value)),
    )

    private fun settingRecords(store: LedgerStore): List<Pair<String, String>> = store.allRecords().mapNotNull { record ->
        if (record.groupId != null) return@mapNotNull null
        (runCatching { ffiDecodeGroupOp(record.blob) }.getOrNull() as? GroupOpIn.Setting)?.let { it.key to it.value }
    }

    // ---- The table ----

    /** Design section 3: seven keys, the wire key iOS's storage key (permanent), in this order; Less motion and the
     *  default currency only once chosen. Where Android keeps each, written out by hand. The per-phone keys are none of
     *  them, and no synced place is one of Android's per-phone preferences. */
    @Test
    fun the_synced_keys_are_the_seven_storage_keys() {
        assertEquals(
            listOf(
                "appearance.theme", "appearance.mode", "appearance.textSize", "appearance.lessMotion", "appearance.haptics",
                "appearance.icon", "money.default-currency",
            ),
            SettingsSync.synced.map { it.key },
        )
        assertEquals(
            listOf(
                SettingsSync.Kind.WORD, SettingsSync.Kind.WORD, SettingsSync.Kind.STEP, SettingsSync.Kind.FLAG,
                SettingsSync.Kind.FLAG, SettingsSync.Kind.WORD, SettingsSync.Kind.WORD,
            ),
            SettingsSync.synced.map { it.kind },
        )
        assertEquals(
            "the wire key <-> Android's preference",
            listOf(
                "skipti-appearance/theme", "skipti-appearance/mode", "skipti-appearance/textSize", "skipti-appearance/lessMotion",
                "skipti-appearance/haptics", "skipti-appearance/icon", "skipti-money/default-currency",
            ),
            SettingsSync.synced.map { "${it.file}/${it.pref}" },
        )
        assertEquals(
            "the readers' own names",
            listOf(
                Appearance.THEME, Appearance.MODE, Appearance.TEXT, MotionSetting.STORAGE_KEY, HapticsSetting.STORAGE_KEY,
                AppIconChoice.STORAGE_KEY, DefaultCurrency.KEY,
            ),
            SettingsSync.synced.map { it.pref },
        )
        assertEquals(
            listOf("applock.timeout", "privacy.hide-when-captured", "sync/enabled", "appearance.modeSettled", "appearance.modeMigrated"),
            SettingsSync.perPhone,
        )
        assertEquals("the sync switch is the vault's own key on both phones", "sync/enabled", CloudSync.SYNC_ENABLED_KEY)
        for (key in SettingsSync.perPhone) {
            assertNull("$key never syncs", SettingsSync.kindOf(key))
            assertFalse(key in SettingsSync.synced.map { it.key })
        }
        val places = SettingsSync.synced.map { it.file to it.pref }
        for (perPhone in listOf(
            "skipti-appearance" to AppearanceMode.SETTLED_KEY, "skipti-appearance" to AppearanceMode.MIGRATED_KEY,
            "skipti-appearance" to "nightModeTold",
        )) {
            assertFalse("$perPhone stays on this phone", perPhone in places)
        }
        assertEquals("never the lock's or screen privacy's files", setOf("skipti-appearance", "skipti-money"), SettingsSync.synced.map { it.file }.toSet())
        assertEquals("settings/applied", SettingsSync.APPLIED_KEY)
        assertEquals("settings/icon-pending", SettingsSync.ICON_PENDING_KEY)
        assertEquals("settings/seeded-u1", SettingsSync.seededKey("u1"))
    }

    /** iOS's tables, read from SettingsSync.swift: the same keys, kinds and order, and the same per-phone list. */
    @Test
    fun the_tables_are_ios_own() {
        val swift = java.io.File("../../ios/Skipti/App/SettingsSync.swift").readText()
        val synced = swift.substringAfter("static let synced: [(key: String, kind: Kind)] = [").substringBefore("\n    ]\n")
        val rows = Regex("""\("([^"]+)", \.(\w+)\)""").findAll(synced).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(7, rows.size)
        assertEquals(SettingsSync.synced.map { it.key to it.kind.name.lowercase() }, rows)
        val perPhone = swift.substringAfter("static let perPhone: [String] = [").substringBefore("\n    ]\n")
        assertEquals(SettingsSync.perPhone, Regex(""""([^"]+)"""").findAll(perPhone).map { it.groupValues[1] }.toList())
        fun constant(name: String) = Regex("""static let $name = "([^"]+)"""").find(swift)?.groupValues?.get(1)
        assertEquals(SettingsSync.APPLIED_KEY, constant("appliedKey"))
        assertEquals(SettingsSync.ICON_PENDING_KEY, constant("iconPendingKey"))
        assertTrue("the seeded key", "static func seededKey(_ uid: String) -> String { \"settings/seeded-\" + uid }" in swift)
    }

    /** Design section 5: what this build can apply. Written out by hand: every known word, the steps -2 to 3 in their
     *  plain decimal, the two switch words, a currency core knows; never a near miss. */
    @Test
    fun what_this_build_can_apply() {
        val table = listOf(
            Triple("appearance.theme", "aurora", true), Triple("appearance.theme", "norse", true), Triple("appearance.theme", "vardr", true),
            Triple("appearance.theme", "midnight", false), Triple("appearance.theme", "Norse", false), Triple("appearance.theme", "", false),
            Triple("appearance.mode", "system", true), Triple("appearance.mode", "light", true), Triple("appearance.mode", "dark", true),
            Triple("appearance.mode", "night", false), Triple("appearance.mode", "Dark", false),
            Triple("appearance.textSize", "-2", true), Triple("appearance.textSize", "-1", true), Triple("appearance.textSize", "0", true),
            Triple("appearance.textSize", "1", true), Triple("appearance.textSize", "2", true), Triple("appearance.textSize", "3", true),
            Triple("appearance.textSize", "4", false), Triple("appearance.textSize", "-3", false), Triple("appearance.textSize", "+1", false),
            Triple("appearance.textSize", "01", false), Triple("appearance.textSize", "1.0", false), Triple("appearance.textSize", "big", false),
            Triple("appearance.lessMotion", "true", true), Triple("appearance.lessMotion", "false", true), Triple("appearance.lessMotion", "1", false),
            Triple("appearance.lessMotion", "True", false), Triple("appearance.haptics", "true", true), Triple("appearance.haptics", "false", true),
            Triple("appearance.haptics", "yes", false),
            Triple("appearance.icon", "match", true), Triple("appearance.icon", "aurora", true), Triple("appearance.icon", "norse", true),
            Triple("appearance.icon", "vardr", true), Triple("appearance.icon", "night", false),
            Triple("money.default-currency", "EUR", true), Triple("money.default-currency", "INR", true), Triple("money.default-currency", "JPY", true),
            Triple("money.default-currency", "eur", false), Triple("money.default-currency", "ZZZ", false),
            Triple("applock.timeout", "never", false), Triple("privacy.hide-when-captured", "true", false), Triple("sync/enabled", "1", false),
        )
        for ((key, value, known) in table) assertEquals("$key = $value", known, SettingsSync.accepts(key, value))
    }

    // ---- The funnel ----

    /** GUARANTEES #5: without an account the value is stored on the phone and no record is written; nor for a session
     *  this vault does not own (no owner yet, another account). */
    @Test
    fun without_an_account_nothing_is_written() {
        for ((owner, signedIn) in listOf<Pair<String?, String?>>(null to null, null to uid, uid to null, "uid-other" to uid)) {
            val store = tempStore(owner)
            val phone = Phone()
            assertFalse(SettingsSync.write("appearance.theme", "norse", store, phone.prefs, signedIn))
            assertEquals("stored on the phone as before", "norse", phone.appearance.getString("theme", null))
            assertEquals("no record (owner $owner, signed in $signedIn)", 0, store.allRecords().size)
        }
        assertFalse(SettingsSync.writesRecord(uid = null, owner = uid, approval = ApprovalState.APPROVED))
        assertFalse(SettingsSync.writesRecord(uid = uid, owner = null, approval = ApprovalState.APPROVED))
        assertTrue(SettingsSync.writesRecord(uid = uid, owner = uid, approval = ApprovalState.APPROVED))
        // D-254: the third condition: an account not approved (or not heard yet) writes no record.
        for (state in listOf(ApprovalState.UNKNOWN, ApprovalState.PENDING, ApprovalState.DENIED)) {
            assertFalse("$state", SettingsSync.writesRecord(uid = uid, owner = uid, approval = state))
        }
    }

    /** D-254: a settings change while the account waits for approval is kept on this phone and writes no record (no
     *  record to push later as stale); once approved, the same tap writes one. */
    @Test
    fun while_waiting_a_setting_change_writes_no_record() {
        val store = tempStore(uid)
        val phone = Phone()
        Approval.setCached(store, uid, ApprovalState.PENDING)
        assertFalse(SettingsSync.write("appearance.theme", "norse", store, phone.prefs, uid))
        assertEquals("kept on the phone", "norse", phone.appearance.getString("theme", null))
        assertEquals("no record while waiting", 0, store.allRecords().size)
        Approval.setCached(store, uid, ApprovalState.APPROVED)
        assertTrue("approved: written", SettingsSync.write("appearance.theme", "vardr", store, phone.prefs, uid))
        assertEquals(1, store.allRecords().size)
    }

    /** Signed in with sync off: the record is written (it waits), and nothing is pushed: the push's gate (CloudSync's
     *  `ready`: the vault's owner, and the switch) answers no account while the switch is off, and the account once it
     *  is on. */
    @Test
    fun signed_in_with_sync_off_writes_a_record_but_pushes_nothing() {
        val store = tempStore(uid)
        CloudSync.setSyncEnabled(store, false)
        val phone = Phone()
        assertTrue(SettingsSync.write("appearance.haptics", "false", store, phone.prefs, uid))
        assertEquals("kept as the Boolean the reader reads", false, phone.appearance.map["haptics"])
        val records = settingRecords(store)
        assertEquals(listOf("appearance.haptics"), records.map { it.first })
        assertEquals(listOf("false"), records.map { it.second })
        fun gate(): String? = CloudGate.owned(uid, Account.storedOwner(store))?.takeIf { CloudSync.syncEnabled(store) }
        assertNull("sync off: push() finds no account", gate())
        CloudSync.setSyncEnabled(store, true)
        assertEquals("the same gate with sync on (not vacuous)", uid, gate())
        // That gate is the push's own: ready() reads exactly these two facts, and push() starts there.
        val cloud = java.io.File("src/main/java/com/vardrlabs/skipti/CloudSync.kt").readText()
        val ready = cloud.substringAfter("private fun ready(): Pair<String, LedgerStore>? {").substringBefore("\n    }\n")
        assertTrue("if (CloudGate.owned(uid, Account.storedOwner(store)) == null) return null" in ready)
        assertTrue("return if (runCatching { syncEnabled(store) }.getOrDefault(false)) uid to store else null" in ready)
        assertTrue("fun push() {\n        if (ready() == null) return" in cloud)
    }

    /** Each kind is stored as its reader reads it: a word as a string, a step as an Int, a switch as a Boolean. */
    @Test
    fun the_funnel_stores_each_kind_as_its_reader_reads_it() {
        val store = tempStore(uid)
        val phone = Phone()
        SettingsSync.write(SettingsSync.TEXT_SIZE, SettingsSync.encode(-2), store, phone.prefs, uid)
        SettingsSync.write(SettingsSync.LESS_MOTION, SettingsSync.encode(true), store, phone.prefs, uid)
        SettingsSync.write(SettingsSync.CURRENCY, "EUR", store, phone.prefs, uid)
        assertEquals(-2, TextSize.parse(phone.appearance.map["textSize"] as Int?))
        assertEquals(true, MotionSetting.parse(phone.appearance.map["lessMotion"] as Boolean?, lowRam = false))
        assertEquals("EUR", DefaultCurrency.resolve(phone.money.getString("default-currency", null), null, DefaultCurrency.all.toSet()))
        assertEquals(
            listOf("appearance.textSize=-2", "appearance.lessMotion=true", "money.default-currency=EUR"),
            settingRecords(store).map { "${it.first}=${it.second}" },
        )
        assertEquals("-2", SettingsSync.local(SettingsSync.TEXT_SIZE, phone.prefs))
        assertEquals("true", SettingsSync.local(SettingsSync.LESS_MOTION, phone.prefs))
        assertNull("never chosen: nothing", SettingsSync.local(SettingsSync.HAPTICS, phone.prefs))
    }

    // ---- The apply decision ----

    private fun setting(key: String, value: String, id: String, device: String) = SettingOut(key, value, id, device)

    /** Design section 5, pure: own record marked, not written; unknown value skipped and not marked; unknown key
     *  ignored; already applied skipped; anything else applied and marked. */
    @Test
    fun the_apply_decision() {
        val me = "e".repeat(32)
        val settings = listOf(
            setting("appearance.haptics", "false", "5:0:$other", other),
            setting("appearance.icon", "norse", "6:0:$me", me),
            setting("appearance.mode", "night", "7:0:$other", other),
            setting("appearance.textSize", "3", "8:0:$other", other),
            setting("appearance.theme", "midnight", "9:0:$other", other),
            setting("applock.timeout", "never", "10:0:$other", other),
            setting("money.default-currency", "EUR", "4:0:$other", other),
        )
        val decision = SettingsSync.toApply(settings, mapOf("money.default-currency" to "4:0:$other"), me)
        assertEquals(
            listOf(SettingsSync.Change("appearance.haptics", "false"), SettingsSync.Change("appearance.textSize", "3")),
            decision.apply,
        )
        assertEquals(
            "own marked; night, midnight and the lock's timeout never marked",
            mapOf(
                "appearance.haptics" to "5:0:$other", "appearance.icon" to "6:0:$me", "appearance.textSize" to "8:0:$other",
                "money.default-currency" to "4:0:$other",
            ),
            decision.applied,
        )
        // A later build that knows the words applies what this one skipped, on its next pass.
        val later = SettingsSync.toApply(settings, decision.applied, me) { key, value ->
            SettingsSync.accepts(key, value) || (key to value) == ("appearance.theme" to "midnight")
        }
        assertEquals(listOf(SettingsSync.Change("appearance.theme", "midnight")), later.apply)
        assertEquals("9:0:$other", later.applied["appearance.theme"])
        assertNull("an excluded key is never marked, whatever the build", later.applied["applock.timeout"])
    }

    /** Applying writes the phone and never a record (no echo); the second pass does nothing. */
    @Test
    fun applying_writes_no_record() {
        val store = tempStore(uid)
        val phone = Phone()
        phone.appearance.edit().putString("theme", "aurora").apply()
        store.applyRecords(listOf(theirs("appearance.theme", "norse", 1_000), theirs("appearance.textSize", "2", 1_001)))
        val before = store.allRecords().size
        val changed = SettingsSync.applyHeld(store, phone.prefs, null)
        assertEquals("the projection's key order", listOf("appearance.textSize", "appearance.theme"), changed)
        assertEquals("norse", phone.appearance.getString("theme", null))
        assertEquals(2, phone.appearance.map["textSize"])
        assertEquals("applying wrote no record", before, store.allRecords().size)
        assertEquals(mapOf("appearance.theme" to "1000:0:$other", "appearance.textSize" to "1001:0:$other"), SettingsSync.applied(store))
        assertEquals("nothing new", emptyList<String>(), SettingsSync.applyHeld(store, phone.prefs, null))
        // A newer write elsewhere wins over the applied one.
        store.applyRecords(listOf(theirs("appearance.theme", "vardr", 2_000)))
        assertEquals(listOf("appearance.theme"), SettingsSync.applyHeld(store, phone.prefs, null))
        assertEquals("vardr", phone.appearance.getString("theme", null))
        // An older one arriving late changes nothing (the latest per key wins in core).
        store.applyRecords(listOf(theirs("appearance.theme", "aurora", 1_500)))
        assertEquals(emptyList<String>(), SettingsSync.applyHeld(store, phone.prefs, null))
        assertEquals("vardr", phone.appearance.getString("theme", null))
    }

    /** A value this build does not know is skipped, not marked, and never read as the default (an unknown theme never
     *  turns the phone Aurora); a later launch of a build that knows it applies it. */
    @Test
    fun an_unknown_value_waits_for_an_update() {
        val store = tempStore(uid)
        val phone = Phone()
        phone.appearance.edit().putString("theme", "norse").putInt("textSize", 1).apply()
        store.applyRecords(listOf(theirs("appearance.theme", "midnight", 1_000), theirs("appearance.textSize", "7", 1_001)))
        store.rawKvSet(SettingsSync.seededKey(uid), "1".toByteArray())
        assertEquals(emptyList<String>(), SettingsSync.atLaunch(store, phone.prefs, uid, null))
        assertEquals("never Aurora", "norse", phone.appearance.getString("theme", null))
        assertEquals("out of range: untouched", 1, phone.appearance.map["textSize"])
        assertEquals("not marked", emptyMap<String, String>(), SettingsSync.applied(store))
        // The update: the next launch's build knows "midnight".
        val changed = SettingsSync.atLaunch(store, phone.prefs, uid, null) { key, value ->
            SettingsSync.accepts(key, value) || (key to value) == ("appearance.theme" to "midnight")
        }
        assertEquals(listOf("appearance.theme"), changed)
        assertEquals("midnight", phone.appearance.getString("theme", null))
        // Not signed in: a launch applies nothing.
        val fresh = Phone()
        assertEquals(emptyList<String>(), SettingsSync.atLaunch(store, fresh.prefs, null, null))
        assertNull(fresh.appearance.getString("theme", null))
    }

    /** The per-phone keys never sync: an arrived lock timeout (or any of them) is ignored, never marked, and the phone's
     *  own value stays; the first sign-in never writes one. */
    @Test
    fun the_excluded_keys_never_sync() {
        val store = tempStore(uid)
        val phone = Phone()
        phone.prefs("skipti-applock").edit().putString("timeout", "1m").apply()
        phone.prefs("skipti-privacy").edit().putBoolean("block-capture", true).apply()
        store.applyRecords(listOf(theirs("applock.timeout", "never", 1_000), theirs("privacy.hide-when-captured", "false", 1_001)))
        assertEquals(
            "core holds them (any valid key)",
            listOf("applock.timeout", "privacy.hide-when-captured"), store.projectGroups().settings.map { it.key },
        )
        val outcome = SettingsSync.afterReceive(store, phone.prefs, uid, fresh = true, fromCache = false, shown = null)
        assertEquals(emptyList<String>(), outcome.applied)
        assertEquals("the lock's timeout is this phone's", "1m", phone.prefs("skipti-applock").getString("timeout", null))
        assertEquals(true, (phone.prefs("skipti-privacy") as MapPrefs).map["block-capture"])
        assertEquals(emptyMap<String, String>(), SettingsSync.applied(store))
        assertEquals("nothing of this phone's per-phone settings goes up", 0, outcome.filled)
        val files = phone.files.mapValues { it.value.map.toMap() }
        SettingsSync.applyFromAccount("applock.timeout", "never", phone.prefs)
        assertEquals("the local write refuses a key outside the table", files, phone.files.mapValues { it.value.map.toMap() })
    }

    // ---- The first sign-in ----

    /** Design section 7: the account wins; the gaps are filled with what this phone chose; never on a cache snapshot;
     *  once per account. */
    @Test
    fun the_first_sign_in_the_account_wins_and_the_gaps_are_filled() {
        val store = tempStore(uid)
        val phone = Phone()
        phone.appearance.edit().putString("theme", "vardr").putBoolean("haptics", true).putString("mode", "light").apply()
        phone.money.edit().putString("default-currency", "EUR").apply()
        // Less motion, text size and the icon were never chosen here.
        store.applyRecords(listOf(theirs("appearance.theme", "norse", 1_000), theirs("appearance.haptics", "false", 1_001)))
        val cached = SettingsSync.afterReceive(store, phone.prefs, uid, fresh = true, fromCache = true, shown = null)
        assertEquals("a cache snapshot says nothing of the account", SettingsSync.Outcome(), cached)
        assertFalse(SettingsSync.isSeeded(store, uid))
        assertEquals("vardr", phone.appearance.getString("theme", null))

        val outcome = SettingsSync.afterReceive(store, phone.prefs, uid, fresh = false, fromCache = false, shown = null)
        assertEquals(2, outcome.filled)
        assertEquals(listOf("appearance.haptics", "appearance.theme"), outcome.applied)
        assertEquals("the account wins", "norse", phone.appearance.getString("theme", null))
        assertEquals(false, phone.appearance.map["haptics"])
        assertEquals("kept, and it fills the account", "light", phone.appearance.getString("mode", null))
        val mine = settingRecords(store).filter { it.first in listOf("appearance.mode", "money.default-currency") }
        assertEquals(listOf("appearance.mode=light", "money.default-currency=EUR"), mine.map { "${it.first}=${it.second}" })
        assertEquals("no record for what was never chosen", 4, settingRecords(store).size)
        assertTrue(SettingsSync.isSeeded(store, uid))
        assertEquals(
            setOf("appearance.theme", "appearance.haptics", "appearance.mode", "money.default-currency"),
            SettingsSync.applied(store).keys,
        )
        // Once: the next server snapshot fills nothing again.
        val again = SettingsSync.afterReceive(store, phone.prefs, uid, fresh = false, fromCache = false, shown = null)
        assertEquals(SettingsSync.Outcome(), again)
        assertEquals(4, settingRecords(store).size)
    }

    /** The first phone of an account fills it with everything it chose; a phone not signed in does nothing. */
    @Test
    fun the_first_phone_fills_the_account_and_no_account_does_nothing() {
        val phone = Phone()
        phone.appearance.edit().putString("theme", "norse").putBoolean("lessMotion", false).putInt("textSize", -1)
            .putString("mode", "night").apply()
        val signedOut = tempStore(uid)
        assertEquals(
            SettingsSync.Outcome(),
            SettingsSync.afterReceive(signedOut, phone.prefs, null, fresh = true, fromCache = false, shown = null),
        )
        assertEquals(0, signedOut.allRecords().size)
        assertFalse(SettingsSync.isSeeded(signedOut, uid))
        val store = tempStore(uid)
        val outcome = SettingsSync.afterReceive(store, phone.prefs, uid, fresh = false, fromCache = false, shown = null)
        assertEquals(SettingsSync.Outcome(filled = 3, applied = emptyList()), outcome)
        assertEquals(
            "an explicit Less motion off syncs; a pre-D-212 night never does",
            listOf("appearance.theme=norse", "appearance.textSize=-1", "appearance.lessMotion=false"),
            settingRecords(store).map { "${it.first}=${it.second}" },
        )
        assertEquals("norse", phone.appearance.getString("theme", null))
    }

    /** Fix wave M3: D-232's launch migration stored `system` on an install that ran before and never chose. That is
     *  this phone's own default, not a choice: the first sign-in never fills the account with it. Once the person taps
     *  a mode (any mode, `system` too), it is a choice and fills the account like any other. iOS's twin:
     *  testAMigratedModeNeverFillsTheAccountUntilItIsTapped. */
    @Test
    fun a_migrated_mode_never_fills_the_account_until_it_is_tapped() {
        val phone = Phone()
        Appearance.settleDefault(phone.appearance, vaultExists = true)
        assertEquals("system", phone.appearance.getString("mode", null))
        assertEquals("the migrated mark stands", true, phone.appearance.map["modeMigrated"])
        assertEquals("an old install with only the settled mode fills nothing", emptyList<SettingsSync.Change>(), SettingsSync.gaps(emptySet(), phone.prefs))
        val store = tempStore(uid)
        val first = SettingsSync.afterReceive(store, phone.prefs, uid, fresh = false, fromCache = false, shown = null)
        assertEquals(SettingsSync.Outcome(0, emptyList()), first)
        assertEquals("System never went up", 0, settingRecords(store).size)
        assertEquals("the phone keeps following the phone", "system", phone.appearance.getString("mode", null))

        // A tap before any account: still `system`, now chosen; the mark goes, and the next first sign-in fills it.
        assertFalse(SettingsSync.write("appearance.mode", "system", tempStore(uid), phone.prefs, null))
        assertFalse("a real tap clears the mark", "modeMigrated" in phone.appearance.map)
        assertEquals(listOf(SettingsSync.Change("appearance.mode", "system")), SettingsSync.gaps(emptySet(), phone.prefs))
        val fresh = tempStore(uid)
        assertEquals(1, SettingsSync.afterReceive(fresh, phone.prefs, uid, fresh = false, fromCache = false, shown = null).filled)
        assertEquals(listOf("appearance.mode=system"), settingRecords(fresh).map { "${it.first}=${it.second}" })

        // A new install never gets the mark (nothing stored, it reads Dark).
        val brandNew = Phone()
        Appearance.settleDefault(brandNew.appearance, vaultExists = false)
        assertFalse("modeMigrated" in brandNew.appearance.map)
    }

    /** Fix wave M5: signing out forgets the catch-up (the seeded mark, what was applied, an icon still pending), so a
     *  later sign-in runs the first sign-in again: the account wins and the gaps are filled. The phone keeps its values.
     *  iOS's twin: testSigningOutForgetsTheCatchUp. */
    @Test
    fun signing_out_forgets_the_catch_up() {
        val store = tempStore(uid)
        val phone = Phone()
        phone.appearance.edit().putString("theme", "vardr").apply()
        store.applyRecords(listOf(theirs("appearance.theme", "norse", 1_000)))
        SettingsSync.afterReceive(store, phone.prefs, uid, fresh = false, fromCache = false, shown = null)
        assertTrue(SettingsSync.isSeeded(store, uid))
        assertEquals(setOf("appearance.theme"), SettingsSync.applied(store).keys)
        store.rawKvSet(SettingsSync.ICON_PENDING_KEY, "1".toByteArray(Charsets.UTF_8))

        SettingsSync.signedOut(store, uid)
        assertFalse("the seeded mark is gone", SettingsSync.isSeeded(store, uid))
        assertEquals("and what was applied", emptyMap<String, String>(), SettingsSync.applied(store))
        assertFalse("and an icon still pending", SettingsSync.iconIsPending(store))
        assertEquals("the phone keeps its settings", "norse", phone.appearance.getString("theme", null))
        SettingsSync.signedOut(tempStore(uid), null) // the owner alone names the mark; nothing throws

        // Signed out, this phone chose again; the next sign-in's first server snapshot is a first sign-in.
        phone.appearance.edit().putString("theme", "aurora").putInt("textSize", 2).apply()
        val again = SettingsSync.afterReceive(store, phone.prefs, uid, fresh = false, fromCache = false, shown = null)
        assertEquals(SettingsSync.Outcome(1, listOf("appearance.theme")), again)
        assertEquals("the account wins again", "norse", phone.appearance.getString("theme", null))
        assertTrue(SettingsSync.isSeeded(store, uid))

        val account = java.io.File("src/main/java/com/vardrlabs/skipti/Account.kt").readText()
        val signOut = account.substringAfter("    fun signOut() {").substringBefore("\n    }\n")
        val left = signOut.indexOf("val left = runCatching { uid() }.getOrNull()")
        val out = signOut.indexOf("auth().signOut()")
        val forget = signOut.indexOf("SettingsSync.signedOut(left)")
        assertTrue("the uid read before Firebase forgets it, the marks after: $left $out $forget", left in 0 until out && out < forget)
    }

    /** Fix wave I1: a setting is personal. One arriving in a GROUP's log (a member's phone, or a modified client) is
     *  refused at the door and never applied on this phone, whatever it says. iOS's twin:
     *  testASettingInAGroupLogIsRefusedAndNeverApplied. */
    @Test
    fun a_setting_in_a_group_log_is_refused_and_never_applied() {
        val store = tempStore(uid)
        val phone = Phone()
        phone.appearance.edit().putString("theme", "aurora").apply()
        store.rawKvSet(SettingsSync.seededKey(uid), "1".toByteArray(Charsets.UTF_8))
        val group = "1".repeat(32)
        val record = theirs("appearance.theme", "norse", 5_000).copy(groupId = group)
        val document = CloudRecords.document(record, "member").toMutableMap<String, Any?>()
        document["createdAt"] = Timestamp(5, 0)
        assertEquals("refused at the door", 0, CloudRecords.absorb(listOf(document), group, store).size)
        assertEquals("nothing came in", 0, store.allRecords().size)
        assertEquals(SettingsSync.Outcome(), SettingsSync.afterReceive(store, phone.prefs, uid, fresh = true, fromCache = false, shown = null))
        assertEquals(emptyList<String>(), SettingsSync.atLaunch(store, phone.prefs, uid, null))
        assertEquals("a member's setting is never applied here", "aurora", phone.appearance.getString("theme", null))
    }

    /** Fix wave M4: every piece of this object's vault and apply work runs on ONE serial dispatcher: the arrival
     *  ([SettingsSync.received] switches to it), the launch, the front moment, a tap's record, the page close and the
     *  sign-out. So `applyHeld` never interleaves with itself and a tap's record is ordered against what arrives. */
    @Test
    fun all_settings_work_runs_on_one_serial_dispatcher() {
        val sync = mainFiles().getValue("SettingsSync.kt")
        assertTrue("one lane", "private val serial: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)" in sync)
        assertTrue("the scope is that lane", "private val work = CoroutineScope(SupervisorJob() + serial)" in sync)
        assertTrue(
            "the arrival switches to it before anything else",
            "suspend fun received(store: LedgerStore, fresh: Boolean, fromCache: Boolean): Unit = withContext(serial) {\n        val app = VaultGate.appContextRef ?: return@withContext" in sync,
        )
        assertEquals("no other lane: the one limitedParallelism", 1, sync.split("limitedParallelism(").size - 1)
        assertEquals("no other way off the main thread", 1, sync.split("Dispatchers.IO").size - 1)
        assertFalse("no other scope", "GlobalScope" in sync)
        assertEquals("one scope", 1, sync.split("CoroutineScope(").size - 1)
        for ((function, end) in listOf(
            "fun set(context: Context, key: String, value: String) {" to "\n    }\n",
            "fun pageClosed(context: Context) {" to "\n    }\n",
            "fun signedOut(uid: String?) {" to "\n    }\n",
            "fun frontMoment(context: Context) {" to "\n    }\n",
            "fun launched(context: Context) {" to "\n    }\n",
        )) {
            assertTrue(function, function in sync)
            assertTrue("$function works on the lane", "work.launch {" in sync.substringAfter(function).substringBefore(end))
        }
        assertEquals("five launches, each on the lane", 5, sync.split(".launch {").size - 1)
        assertEquals(5, sync.split("work.launch {").size - 1)
    }

    /** Fix wave M9: what each shell can apply is the same vocabulary: the theme, mode and icon words and the text-size
     *  range are read from iOS's declarations (Appearance.swift) and `accepts` (SettingsSync.swift) and compared with
     *  Android's, word for word, so a word one shell adds the other cannot silently skip. */
    @Test
    fun the_vocabulary_is_ios_own() {
        val appearance = java.io.File("../../ios/Skipti/App/Appearance.swift").readText()
        val sync = java.io.File("../../ios/Skipti/App/SettingsSync.swift").readText()
        fun words(enumHead: String): List<String> {
            assertTrue(enumHead, enumHead in appearance)
            val body = appearance.substringAfter(enumHead).substringBefore("\n\n")
            return Regex("""^\s*case (\w+)(?: = "([^"]+)")?\s*$""", RegexOption.MULTILINE).findAll(body)
                .map { it.groupValues[2].ifEmpty { it.groupValues[1] } }.toList()
        }
        val themes = words("enum AppTheme: String, CaseIterable, Identifiable {")
        val modes = words("enum AppearanceMode: String, CaseIterable, Identifiable {")
        val icons = words("enum AppIconChoice: String, CaseIterable {")
        assertEquals(listOf("aurora", "norse", "vardr"), themes)
        assertEquals(listOf("system", "light", "dark"), modes)
        assertEquals(listOf("match", "aurora", "norse", "vardr"), icons)
        assertEquals(themes, AppTheme.entries.map { it.stored })
        assertEquals(modes, AppearanceMode.entries.map { it.stored })
        assertEquals(icons, AppIconChoice.entries.map { it.stored })
        val steps = Regex("""static let steps = (-?\d+)\.\.\.(-?\d+)""").find(appearance)
        assertNotNull("TextSize.steps", steps)
        assertEquals(steps!!.groupValues[1].toInt() to steps.groupValues[2].toInt(), TextSize.MIN to TextSize.MAX)
        // iOS's accepts reads exactly these declarations, key by key.
        val accepts = sync.substringAfter("static func accepts(_ key: String, _ value: String) -> Bool {").substringBefore("\n    }\n")
        for (line in listOf(
            "case AppTheme.storageKey: return AppTheme(rawValue: value) != nil",
            "case AppearanceMode.storageKey: return AppearanceMode(rawValue: value) != nil",
            "case TextSize.storageKey: return Int(value).map { TextSize.steps.contains(\$0) && String(\$0) == value } ?? false",
            "case MotionSetting.storageKey, HapticsSetting.storageKey: return value == \"true\" || value == \"false\"",
            "case AppIconChoice.storageKey: return AppIconChoice(rawValue: value) != nil",
            "case DefaultCurrency.storageKey: return DefaultCurrency.all.contains(value)",
            "default: return false",
        )) assertTrue(line, line in accepts)
        // And Android's accepts takes the same words and no others.
        for (w in themes) assertTrue(w, SettingsSync.accepts("appearance.theme", w))
        for (w in modes) assertTrue(w, SettingsSync.accepts("appearance.mode", w))
        for (w in icons) assertTrue(w, SettingsSync.accepts("appearance.icon", w))
        for (n in steps.groupValues[1].toInt()..steps.groupValues[2].toInt()) assertTrue("$n", SettingsSync.accepts("appearance.textSize", "$n"))
        for (n in listOf(TextSize.MIN - 1, TextSize.MAX + 1)) assertFalse("$n", SettingsSync.accepts("appearance.textSize", "$n"))
        for ((key, word) in listOf("appearance.theme" to "night", "appearance.mode" to "night", "appearance.icon" to "midnight")) {
            assertFalse("$key $word", SettingsSync.accepts(key, word))
        }
    }

    // ---- What goes up ----

    /** The avatar rule per key: only the latest of each key goes up; earlier ones are marked, never sent; keys are
     *  independent of each other and of the picture. */
    @Test
    fun outgoing_keeps_only_the_latest_of_each_key() {
        val store = tempStore(uid)
        var stamp = 1_000L
        fun put(key: String, value: String) {
            stamp += 1
            store.appendGroupOps(listOf(GroupOpIn.Setting(key, value)), Placing.NONE, nowMs = stamp)
        }
        put("appearance.textSize", "1")
        put("appearance.textSize", "2")
        put("appearance.haptics", "false")
        put("appearance.textSize", "3")
        store.saveAvatar(Avatar.Emoji("🦊"))
        val all = store.allRecords()
        fun ids(key: String, value: String): Set<String> = all.filter {
            (runCatching { ffiDecodeGroupOp(it.blob) }.getOrNull() as? GroupOpIn.Setting)?.let { s -> s.key == key && s.value == value } == true
        }.map(CloudRecords::documentId).toSet()
        val older = ids("appearance.textSize", "1") + ids("appearance.textSize", "2")
        assertEquals(2, older.size)
        val out = CloudRecords.outgoing(all, emptySet(), emptySet(), emptySet())
        assertEquals("the earlier steps never go up", older, out.superseded)
        val sent = out.send.map(CloudRecords::documentId).toSet()
        assertTrue(sent.containsAll(ids("appearance.textSize", "3")))
        assertTrue("another key is its own", sent.containsAll(ids("appearance.haptics", "false")))
        assertEquals("the picture and the rest as before", all.size - 2, sent.size)
        // The latest already up: an earlier one still waiting never goes.
        val again = CloudRecords.outgoing(all, ids("appearance.textSize", "3"), emptySet(), emptySet())
        assertEquals(older, again.superseded)
        assertEquals(
            mapOf(
                "appearance.textSize" to ids("appearance.textSize", "3").single(),
                "appearance.haptics" to ids("appearance.haptics", "false").single(),
            ),
            CloudRecords.latestSettings(all),
        )
    }

    // ---- The icon ----

    /** Design section 6: an arrival leaves the icon pending only when it changed the theme or the choice and the icon
     *  wanted is not the one shown, on a launcher whose entry can be told. Written out by hand. */
    @Test
    fun the_icon_is_pending_only_when_the_arrival_changes_the_icon_wanted() {
        data class Row(val changed: List<String>, val choice: AppIconChoice, val theme: AppTheme, val shown: AppIcon?, val pending: Boolean)
        val rows = listOf(
            Row(listOf("appearance.theme"), AppIconChoice.MATCH, AppTheme.NORSE, AppIcon.AURORA, true),
            Row(listOf("appearance.theme"), AppIconChoice.MATCH, AppTheme.NORSE, AppIcon.NORSE, false),
            Row(listOf("appearance.theme"), AppIconChoice.AURORA, AppTheme.NORSE, AppIcon.AURORA, false),
            Row(listOf("appearance.icon"), AppIconChoice.VARDR, AppTheme.AURORA, AppIcon.AURORA, true),
            Row(listOf("appearance.icon"), AppIconChoice.VARDR, AppTheme.AURORA, AppIcon.VARDR, false),
            Row(listOf("appearance.haptics"), AppIconChoice.MATCH, AppTheme.NORSE, AppIcon.AURORA, false),
            Row(listOf("appearance.theme"), AppIconChoice.MATCH, AppTheme.NORSE, null, false),
            Row(emptyList(), AppIconChoice.VARDR, AppTheme.AURORA, AppIcon.AURORA, false),
        )
        for (r in rows) assertEquals(r.toString(), r.pending, SettingsSync.iconPending(r.changed, r.choice, r.theme, r.shown))
        assertTrue(SettingsSync.frontApplies(pending = true, locked = false, active = true))
        for ((pending, locked, active) in listOf(Triple(false, false, true), Triple(true, true, true), Triple(true, false, false), Triple(true, true, false))) {
            assertFalse(SettingsSync.frontApplies(pending, locked, active))
        }
    }

    /** An arrived theme with Match theme marks the icon pending; nothing changes the icon at arrival; the front moment
     *  changes it only unlocked and in front, once, and a front moment with nothing pending does nothing. */
    @Test
    fun the_icon_waits_for_the_front_moment() {
        val store = tempStore(uid)
        val phone = Phone()
        store.rawKvSet(SettingsSync.seededKey(uid), "1".toByteArray())
        store.applyRecords(listOf(theirs("appearance.theme", "norse", 1_000)))
        val calls = mutableListOf<Pair<AppIconChoice, AppTheme>>()
        val change: (AppIconChoice, AppTheme) -> Boolean = { c, t -> calls += c to t; true }
        assertFalse(
            "nothing pending: a launch or a return changes nothing",
            SettingsSync.frontMoment(store, phone.prefs, locked = false, active = true, change = change),
        )
        val outcome = SettingsSync.afterReceive(store, phone.prefs, uid, fresh = true, fromCache = false, shown = AppIcon.AURORA)
        assertEquals(listOf("appearance.theme"), outcome.applied)
        assertTrue("pending at arrival", SettingsSync.iconIsPending(store))
        assertTrue("never at arrival", calls.isEmpty())
        assertFalse(SettingsSync.frontMoment(store, phone.prefs, locked = true, active = true, change = change))
        assertFalse(SettingsSync.frontMoment(store, phone.prefs, locked = false, active = false, change = change))
        assertTrue("locked or in the background: it waits", calls.isEmpty())
        assertTrue(SettingsSync.iconIsPending(store))
        // The switcher could not run (SKIPTI not in front after all): it stays pending.
        assertFalse(SettingsSync.frontMoment(store, phone.prefs, locked = false, active = true) { _, _ -> false })
        assertTrue(SettingsSync.iconIsPending(store))
        assertTrue(SettingsSync.frontMoment(store, phone.prefs, locked = false, active = true, change = change))
        assertEquals("the stored choice and the arrived theme", listOf("match/norse"), calls.map { "${it.first.stored}/${it.second.stored}" })
        assertFalse("done", SettingsSync.iconIsPending(store))
        assertFalse(SettingsSync.frontMoment(store, phone.prefs, locked = false, active = true, change = change))
        assertEquals("once", 1, calls.size)
    }

    // ---- Source guards ----

    private fun mainFiles(): Map<String, String> {
        val files = java.io.File("src/main/java/com/vardrlabs/skipti").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }
        assertTrue("the walk found the app's files", files.size > 40)
        return files
    }

    companion object {
        /** Every name a synced preference goes by in a write: qualified anywhere, a literal anywhere, and the bare name
         *  inside the file that declares it. */
        private val qualified = listOf(
            "Appearance.THEME", "Appearance.MODE", "Appearance.TEXT", "MotionSetting.STORAGE_KEY", "HapticsSetting.STORAGE_KEY",
            "AppIconChoice.STORAGE_KEY", "DefaultCurrency.KEY",
        )
        private val bare = mapOf(
            "Appearance.kt" to listOf("THEME", "MODE", "TEXT", "STORAGE_KEY", "AppIconChoice.STORAGE_KEY"),
            "CurrencyChoice.kt" to listOf("KEY"),
            "DoneMoments.kt" to listOf("STORAGE_KEY"),
            "ThemeMotion.kt" to listOf("STORAGE_KEY"),
        )
        private val writer = Regex("""(?<![A-Za-z])(putString|putInt|putBoolean|putLong|putFloat|putStringSet|remove)\(\s*([^,)]+)""")

        /** Every write of a synced preference outside the funnel in [text] (the file [name]): an editor's put or remove
         *  whose key is one. [allowed]: exact (trimmed) lines that may write. */
        fun strayWrites(name: String, text: String, allowed: Set<String> = emptySet()): List<String> {
            val names = qualified + SettingsSync.synced.map { "\"${it.pref}\"" } + (bare[name] ?: emptyList())
            val found = mutableListOf<String>()
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed in allowed) continue
                val code = line.substringBefore("//")
                for (m in writer.findAll(code)) {
                    if (m.groupValues[2].trim() in names) found += trimmed
                }
            }
            return found
        }
    }

    /** The one write: no app file writes a synced preference but SettingsSync.kt (the funnel and the arrival's local
     *  write), except D-232's once-per-install mode migration (a phone's own default, before the vault exists; never a
     *  choice). */
    @Test
    fun the_funnel_is_the_only_write_of_a_synced_pref() {
        val allowed = setOf(
            "AppearanceMode.migrate(prefs.getString(MODE, null), vaultExists)?.let { edit.putString(MODE, it).putBoolean(AppearanceMode.MIGRATED_KEY, true) }",
        )
        val files = mainFiles()
        val stray = files.filterKeys { it != "SettingsSync.kt" }.mapValues { (name, text) -> strayWrites(name, text, allowed) }.filterValues { it.isNotEmpty() }
        assertEquals("a synced preference written past SettingsSync.set", emptyMap<String, List<String>>(), stray)
        val puts = files.values.sumOf { writer.findAll(it).count() }
        assertTrue("the walk saw the app's preference writes ($puts)", puts > 10)
        assertTrue(
            "the one allowed line is where it was",
            "        AppearanceMode.migrate(prefs.getString(MODE, null), vaultExists)?.let { edit.putString(MODE, it).putBoolean(AppearanceMode.MIGRATED_KEY, true) }\n" in files.getValue("Appearance.kt"),
        )
        // The detector is not vacuous: each way of writing past the funnel is caught.
        val samples = listOf(
            "Appearance.kt" to "        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(THEME, newTheme.stored).apply()\n",
            "Appearance.kt" to "        prefs.edit().putInt(TEXT, 3).apply()\n",
            "AccountHubScreen.kt" to "    prefs.edit().putBoolean(HapticsSetting.STORAGE_KEY, false).apply()\n",
            "AccountHubScreen.kt" to "    prefs.edit { putString(\"icon\", \"norse\") }\n",
            "CurrencyChoice.kt" to "        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()\n",
            "Push.kt" to "    p.edit().putString(DefaultCurrency.KEY, \"EUR\").apply()\n",
            "ThemeMotion.kt" to "    p.edit().putBoolean(STORAGE_KEY, true).apply()\n",
        )
        for ((name, sample) in samples) assertEquals(sample, 1, strayWrites(name, sample).size)
        // A read, a per-phone key, and another file's own KEY are not writes.
        val fine = listOf(
            "Appearance.kt" to "        theme = AppTheme.parse(prefs.getString(THEME, null))\n",
            "Appearance.kt" to "            prefs.edit().putInt(NIGHT_TOLD, tell).apply()\n",
            "AppLock.kt" to "        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice.stored).apply()\n",
        )
        for ((name, sample) in fine) assertEquals(sample, emptyList<String>(), strayWrites(name, sample))
        // The wire keys live only in SettingsSync.kt, once each.
        for (entry in SettingsSync.synced) {
            val named = files.mapValues { it.value.split("\"${entry.key}\"").size - 1 }.filterValues { it > 0 }
            assertEquals("${entry.key} named once, in the table's constants", mapOf("SettingsSync.kt" to 1), named)
        }
        // The setters are the funnel's only callers, one each.
        fun callers(needle: String) = files.mapValues { it.value.split(needle).size - 1 }.filterValues { it > 0 }
        assertEquals(mapOf("Appearance.kt" to 6, "CurrencyChoice.kt" to 1), callers("SettingsSync.set("))
        val appearance = files.getValue("Appearance.kt")
        for ((setter, call) in listOf(
            "fun setTheme(context: Context, newTheme: AppTheme) {" to "SettingsSync.set(context, SettingsSync.THEME, newTheme.stored)",
            "fun setMode(context: Context, newMode: AppearanceMode) {" to "SettingsSync.set(context, SettingsSync.MODE, newMode.stored)",
            "fun setTextStep(context: Context, step: Int) {" to "SettingsSync.set(context, SettingsSync.TEXT_SIZE, SettingsSync.encode(TextSize.parse(step)))",
            "fun setHaptics(context: Context, on: Boolean) {" to "SettingsSync.set(context, SettingsSync.HAPTICS, SettingsSync.encode(on))",
            "fun setIconChoice(context: Context, choice: AppIconChoice) {" to "SettingsSync.set(context, SettingsSync.ICON, choice.stored)",
            "fun setLessMotion(context: Context, on: Boolean) {" to "SettingsSync.set(context, SettingsSync.LESS_MOTION, SettingsSync.encode(on))",
        )) {
            assertTrue(setter, call in appearance.substringAfter(setter).substringBefore("\n    }\n"))
        }
        val currency = files.getValue("CurrencyChoice.kt").substringAfter("fun set(context: Context, code: String) {").substringBefore("\n    }\n")
        assertTrue("SettingsSync.set(context, SettingsSync.CURRENCY, code)" in currency)
    }

    /** Where the account's settings are read and applied: on arrival (the personal branch of `CloudSync.receive`, after
     *  the records are in), at launch (`goOn`, after the lock's decision), and the front moment at a return after a real
     *  stop and at the unlock. The apply path never writes a record. */
    @Test
    fun the_apply_paths_are_where_the_design_says() {
        val files = mainFiles()
        val cloud = files.getValue("CloudSync.kt")
        val receive = cloud.substringAfter("suspend fun receive(documents: List<Map<String, Any?>>, groupId: String?, store: LedgerStore, fromCache: Boolean) {")
            .substringBefore("\n    }\n")
        val absorb = receive.indexOf("val fresh = runCatching { CloudRecords.absorb(documents, groupId, store) }.getOrNull() ?: return")
        val hook = receive.indexOf("if (groupId == null) SettingsSync.received(store, fresh = fresh.isNotEmpty(), fromCache = fromCache)")
        assertTrue("after the records are in: $absorb $hook", absorb in 0 until hook)
        fun callers(needle: String) = files.mapValues { it.value.split(needle).size - 1 }.filterValues { it > 0 }
        assertEquals(mapOf("CloudSync.kt" to 1), callers("SettingsSync.received("))
        assertEquals(mapOf("AppRoot.kt" to 1), callers("SettingsSync.launched("))
        assertEquals("the return and the unlock", mapOf("AppRoot.kt" to 1, "AppLock.kt" to 1), callers("SettingsSync.frontMoment("))
        // D-245 (U9, decision 160): the welcome's close is Appearance's close (`WelcomeLook.closed`), one site.
        assertEquals(mapOf("AccountHubScreen.kt" to 1, "Welcome.kt" to 1), callers("SettingsSync.pageClosed("))
        val root = files.getValue("AppRoot.kt")
        // D-245: goOn also takes whether the welcome shows.
        val goOn = root.substringAfter("fun goOn(opened: VaultRecovery.Launch.Opened, welcomeShows: Boolean) {").substringBefore("\n    }\n")
        assertTrue("goOn found", "kitLoaded = true" in goOn)
        val lock = goOn.indexOf("if (opened.launchedBefore) AppLock.launch(context)")
        val launched = goOn.indexOf("SettingsSync.launched(context)")
        assertTrue("after the lock's decision: $lock $launched", lock in 0 until launched)
        val start = root.substringAfter("override fun onStart() {").substringBefore("\n    }\n")
        val proof = start.indexOf("if (proofArg() != null) return")
        val away = start.indexOf("val returned = AppLock.away")
        val came = start.indexOf("AppLock.cameToFront(this)")
        val front = start.indexOf("if (returned) SettingsSync.frontMoment(this)")
        assertTrue("never a proof; the return read before the lock decides, the moment after: $proof $away $came $front", proof in 0 until away && away < came && came < front)
        val unlock = files.getValue("AppLock.kt").substringAfter("suspend fun unlock(context: Context) {").substringBefore("\n    }\n")
        assertTrue("the unlock is a front moment", unlock.indexOf("moved(life.copy(locked = false))") in 0 until unlock.indexOf("SettingsSync.frontMoment(context)"))
        assertTrue("away is the lock's own time away", "val away: Boolean get() = life.backgroundedAtMs != null" in files.getValue("AppLock.kt"))
        val hub = files.getValue("AccountHubScreen.kt")
        assertTrue("onDispose { if (activity?.isChangingConfigurations != true) SettingsSync.pageClosed(context) }" in hub)
        // The apply path writes the phone only.
        val sync = files.getValue("SettingsSync.kt")
        for (function in listOf(
            "fun applyFromAccount(", "fun applyHeld(", "fun toApply(", "fun atLaunch(", "fun frontMoment(\n        store: LedgerStore,",
        )) {
            assertTrue(function, function in sync)
            assertFalse("$function never writes a record", "appendGroupOps" in sync.substringAfter(function).substringBefore("\n    }\n"))
        }
        assertEquals("the funnel and the first sign-in's gaps", 2, sync.split("appendGroupOps(").size - 1)
        val launch = sync.substringAfter("fun launched(context: Context) {").substringBefore("\n    }\n")
        assertTrue("once per process", "val first = launchedOnce.compareAndSet(false, true)" in launch)
        assertTrue("in front only", "if (first && !AppLock.away) frontMoment(app)" in launch)
        // A tap and an arrival show through the same path: Appearance.reload, which tells the night mode for a theme or mode.
        assertTrue("if (keys.any { entry(it)?.file == Appearance.PREFS }) Appearance.reload(context, keys)" in sync)
        val reload = files.getValue("Appearance.kt").substringAfter("fun reload(context: Context, keys: List<String>) {").substringBefore("\n    }\n")
        assertTrue("load(context)" in reload)
        assertTrue("if (SettingsSync.THEME in keys || SettingsSync.MODE in keys) tellNightMode(context)" in reload)
    }
}

/** A SharedPreferences held in a map (this project has no Robolectric; SharedPreferences is an interface). */
private class MapPrefs : SharedPreferences {
    val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?): String? = (map[key] as String?) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = (map[key] as Int?) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as Long?) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as Float?) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as Boolean?) ?: defValue
    override fun contains(key: String?): Boolean = key in map
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?): SharedPreferences.Editor {
            removed += key!!
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            removed.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}
