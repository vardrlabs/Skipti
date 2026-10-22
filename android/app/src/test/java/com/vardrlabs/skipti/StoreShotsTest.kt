package com.vardrlabs.skipti

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.skipti_core.SkiptiKeystore
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.SpendingView

/** D-251 (U32): the store-shots seed on Android, the twin of iOS's StoreShotsTests. Debuggable builds only (with a
 *  negative), no account and no cloud, the lock never written, the times inside this month, Home's numbers from core,
 *  and the seed table equal to iOS's (read out of StoreShots.swift). Goldens: `.drafts/d255/derive.py` (D-255's New
 *  York seed) and `.drafts/d251/times.py`, re-derived apart from both shells. */
class StoreShotsTest {
    private val keystore = object : SkiptiKeystore {
        var key: ByteArray? = null
        override fun provision(key: ByteArray) { this.key = key }
        override fun load(): ByteArray? = key
        override fun destroy() { key = null }
    }

    private fun tempStore(): LedgerStore {
        val path = Files.createTempDirectory("d251").resolve("v.db").toString()
        return LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    private fun main(name: String) = File("src/main/java/com/vardrlabs/skipti/$name").readText()
    /** Review M3: the seed is in the debug source set; release compiles a stub. */
    private fun debug(name: String) = File("src/debug/java/com/vardrlabs/skipti/$name").readText()
    private fun release(name: String) = File("src/release/java/com/vardrlabs/skipti/$name").readText()
    private fun swift(name: String) = File("../../ios/Skipti/App/$name").readText()
    private fun mainSources(): Map<String, String> =
        File("src/main/java/com/vardrlabs/skipti").listFiles { f -> f.name.endsWith(".kt") }!!.associate { it.name to it.readText() }

    /** 2026-10-21 12:00 UTC; 2026-10-02 01:00 UTC; 2026-10-01 00:00 UTC. */
    private val late = 1_792_584_000_000L
    private val early = 1_790_902_800_000L
    private val october = 1_790_812_800_000L

    // ---- The seed table equals iOS's ----

    private fun swiftBlock(swift: String, from: String): String =
        swift.substringAfter(from).substringBefore("\n    ]\n")

    private fun names(list: String): List<String> =
        Regex("\"([^\"]*)\"").findAll(list).map { it.groupValues[1] }.toList()

    private fun number(s: String) = s.replace("_", "").toLong()

    @Test
    fun the_seed_table_is_ios_own() {
        val file = swift("StoreShots.swift")
        val groups = swiftBlock(file, "static let groups: [StoreShotsGroup] = [")
        val g = Regex("""\.init\(name: "([^"]*)", currency: "([^"]*)", people: \[([^\]]*)\], id: "([0-9a-f]{32})"\),""")
            .findAll(groups)
            .map { StoreShotsGroup(it.groupValues[1], it.groupValues[2], names(it.groupValues[3]), it.groupValues[4]) }.toList()
        assertEquals(groups.split(".init(").size - 1, g.size)
        assertEquals(StoreShots.GROUPS, g)

        val expenses = swiftBlock(file, "static let expenses: [StoreShotsExpense] = [")
        val e = swiftExpenses(file)
        assertEquals("every Swift row parsed", expenses.split(".init(").size - 1, e.size)
        assertEquals(25, e.size)
        assertEquals(StoreShots.EXPENSES, e)

        val settlements = swiftBlock(file, "static let settlements: [StoreShotsSettlement] = [")
        val s = Regex("""\.init\(ledger: "([^"]*)", from: "([^"]*)", to: "([^"]*)", minor: ([0-9_]+), hoursAgo: ([0-9_]+)\),""")
            .findAll(settlements).map {
                val v = it.groupValues
                StoreShotsSettlement(v[1], v[2], v[3], number(v[4]), number(v[5]).toInt())
            }.toList()
        assertEquals(settlements.split(".init(").size - 1, s.size)
        assertEquals(StoreShots.SETTLEMENTS, s)

        for ((swiftName, kotlin) in listOf(
            "flag" to StoreShots.FLAG, "themeFlag" to StoreShots.THEME_FLAG, "modeFlag" to StoreShots.MODE_FLAG,
            "you" to StoreShots.YOU, "justMe" to StoreShots.JUST_ME, "justMeCurrency" to StoreShots.JUST_ME_CURRENCY,
            "asideSuffix" to StoreShots.ASIDE_SUFFIX,
        )) {
            assertTrue("$swiftName = \"$kotlin\"", "static let $swiftName = \"$kotlin\"" in file)
        }
        assertTrue("static let keptFromHours = 8_760" in file)
        assertEquals(8_760, StoreShots.KEPT_FROM_HOURS)
        assertEquals(SpendingCopy.JUST_ME, StoreShots.JUST_ME)
    }

    private fun swiftExpenses(file: String): List<StoreShotsExpense> =
        Regex(
            """\.init\(ledger: "([^"]*)", payer: "([^"]*)", minor: ([0-9_]+), note: "([^"]*)", category: "([^"]*)", """ +
                """hoursAgo: ([0-9_]+), among: \[([^\]]*)\]\),""",
        ).findAll(swiftBlock(file, "static let expenses: [StoreShotsExpense] = [")).map {
            val v = it.groupValues
            StoreShotsExpense(v[1], v[2], number(v[3]), v[4], v[5], number(v[6]).toInt(), names(v[7]))
        }.toList()

    /** The parity test's negative: the Swift table with one amount changed, or one row dropped, is not equal. */
    @Test
    fun a_different_ios_row_is_seen() {
        val file = swift("StoreShots.swift")
        val changed = Regex("""minor: [0-9_]+, note: "Rent"""").replace(file, "minor: 380_001, note: \"Rent\"")
        val parsed = swiftExpenses(changed)
        assertEquals(380_001L, parsed.first { it.note == "Rent" }.minor)
        assertFalse(parsed == StoreShots.EXPENSES)
        val dropped = file.lines().filterNot { "note: \"Gas\"" in it }.joinToString("\n")
        assertFalse(swiftExpenses(dropped) == StoreShots.EXPENSES)
    }

    // ---- Debuggable builds only ----

    @Test
    fun the_seed_is_reached_only_in_debuggable_builds() {
        val args = "--store-shots --store-theme norse"
        assertNotNull(StoreShots.launch(args, debuggable = true))
        assertNull("a release build: no seed", StoreShots.launch(args, debuggable = false))
        assertNull("a release build: no proof", ProofArgs.proof(args, debuggable = false))
        assertEquals(args, ProofArgs.proof(args, debuggable = true))
        // Review M5: the flag anywhere, as on iOS; the proof puts it first (AppRoot's headless word), and `--emulators`
        // does not ride along with it.
        assertNotNull(StoreShots.launch("--emulators --store-shots", debuggable = true))
        assertEquals(AppTheme.NORSE, StoreShots.launch("--store-theme norse --store-shots", debuggable = true)!!.theme)
        assertEquals("--store-shots --emulators --store-theme norse",
            ProofArgs.proof("--emulators --store-theme norse --store-shots", debuggable = true))
        assertNull(ProofArgs.proof("--emulators --store-shots", debuggable = false))
        assertFalse(ProofArgs.emulators("--emulators --store-shots", debuggable = true))
        assertTrue(ProofArgs.emulators("--emulators", debuggable = true))
        assertNull("not the flag", StoreShots.launch("--store-shotsx", debuggable = true))
        val root = main("AppRoot.kt")
        assertTrue(root.contains("private fun proofArg(): String? = ProofArgs.proof(launchArgs, debuggable())"))
        assertTrue(root.contains("verificationArg = proofArg(),"))
        val prepare = debug("StoreShots.kt").substringAfter("fun prepare(context: Context, intent: Intent?, restored: Boolean, debuggable: Boolean): String? {")
        assertTrue(prepare.contains("\n        val launch = launch(args, debuggable) ?: return args\n"))
        assertEquals(listOf<String>(), stray(mainSources()))
    }

    /** Review I1: a launch's store-shots args are used once: kept for the activity's re-creations in the process that
     *  admitted them, never from Recents, never after the process died, and the extra is removed once read. */
    @Test
    fun the_store_shots_args_are_used_once() {
        val args = "--store-shots --store-theme norse"
        val a = StoreShots.Admission()
        assertEquals(args, a.args(args, fromHistory = false, restored = false, debuggable = true))
        assertEquals("a re-creation (the extra already removed) keeps them", args, a.args(null, false, true, true))
        assertEquals(args, a.args(args, false, true, true))
        assertNull("a fresh ordinary launch in the same process ends them", a.args(null, false, false, true))
        assertNull("and its re-creation is ordinary", a.args(null, false, true, true))
        assertNull("the process died, the activity restored with its first intent", StoreShots.Admission().args(args, false, true, true))
        assertNull("from Recents", StoreShots.Admission().args(args, true, false, true))
        assertEquals("other args untouched", "--balance", StoreShots.Admission().args("--balance", true, true, true))
        assertEquals("not debuggable: passed through (ProofArgs honours none)", args, StoreShots.Admission().args(args, false, false, false))
        val prepare = debug("StoreShots.kt").substringAfter("fun prepare(").substringBefore("\n    }\n")
        assertTrue("if (launch(raw, debuggable) != null) intent?.removeExtra(\"args\")" in prepare)
        assertTrue("(intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0" in prepare)
        assertTrue("val args = admission.args(raw, fromHistory, restored, debuggable)" in prepare)
    }

    /** Review M3: the seed is compiled into debug builds only; the release stub names no vault, preference or seed. */
    @Test
    fun the_seed_is_in_debug_builds_only() {
        assertFalse(File("src/main/java/com/vardrlabs/skipti/StoreShots.kt").exists())
        val seed = debug("StoreShots.kt")
        val stub = release("StoreShots.kt")
        assertTrue("fun seed(" in seed && "fun setAside(" in seed)
        assertEquals(listOf<String>(), stubLeaks(stub))
        assertTrue("fun prepare(context: Context, intent: Intent?, restored: Boolean, debuggable: Boolean): String? =\n        intent?.getStringExtra(\"args\")" in stub)
        for (signature in listOf(
            "fun prepare(context: Context, intent: Intent?, restored: Boolean, debuggable: Boolean): String?",
            "fun StoreShotsProof(vaultPath: String, shell: @Composable () -> Unit)",
        )) {
            assertTrue("debug: $signature", signature in seed)
            assertTrue("release: $signature", signature in stub)
        }
        assertTrue("the stub draws nothing", stub.trimEnd().endsWith("fun StoreShotsProof(vaultPath: String, shell: @Composable () -> Unit) {\n}"))
        // The negative: a stub that seeds is seen.
        assertEquals(listOf("setAside", "LedgerStore"), stubLeaks(stub + "\nfun x(p: String) = StoreShots.setAside(LedgerStore(p))\n"))
    }

    private fun stubLeaks(stub: String): List<String> =
        listOf("setAside", "seed(", "seedOnce", "rawKv", "LedgerStore", "File(", "SettingsSync", "applyAppearance", "edit()", "shell()", "removeExtra")
            .filter { it in stub }

    /** Every reference to the seed outside StoreShots.kt: the onCreate call (behind [StoreShots.launch]) and the proof
     *  branch (reached only through ProofArgs.proof) are the only ones allowed. */
    private fun stray(sources: Map<String, String>, proofs: String = debug("Proofs.kt")): List<String> {
        val allowed = listOf(
            "launchArgs = StoreShots.prepare(applicationContext, intent, savedInstanceState != null, debuggable())",
            "StoreShotsProof(vaultPath) { HomeShell(vaultPath = vaultPath, startOn = HomeTab.BALANCE) }",
        )
        val out = mutableListOf<String>()
        for ((name, text) in sources) {
            if (name == "StoreShots.kt") continue
            for (line in text.lines()) {
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/**")) continue
                if ("StoreShots" in t && t !in allowed) out += "$name: $t"
            }
        }
        val root = sources["AppRoot.kt"].orEmpty()
        if (root.isNotEmpty()) {
            // D-260 (G-13): the headless dispatch, the seed's branch with it, moved to src/debug's Proofs.kt.
            val branch = proofs.substringBefore("StoreShotsProof(vaultPath)").substringAfterLast("when (headless) {")
            if ("\"store-shots\" ->" !in branch) out += "Proofs.kt: the proof outside the headless branch"
            if (allowed[0] !in root || allowed[1] !in proofs) out += "AppRoot.kt or Proofs.kt: a call missing"
        }
        return out
    }

    @Test
    fun the_guard_finds_a_stray_reference() {
        val leaked = mapOf("Other.kt" to "fun x() {\n    StoreShots.seedOnce(path)\n}\n", "StoreShots.kt" to "StoreShots.x()")
        assertEquals(listOf("Other.kt: StoreShots.seedOnce(path)"), stray(leaked))
        // D-260 (G-13): the branch is in src/debug's Proofs.kt.
        val moved = debug("Proofs.kt").replace("\"store-shots\" ->", "\"store-shots-x\" ->")
        assertTrue(stray(mapOf("AppRoot.kt" to main("AppRoot.kt")), proofs = moved).any { "headless branch" in it })
        assertEquals(listOf<String>(), stray(mapOf("AppRoot.kt" to main("AppRoot.kt"))))
    }

    // ---- No account, no cloud, no lock written ----

    @Test
    fun the_seed_signs_in_to_nothing_and_starts_no_sync() {
        val file = debug("StoreShots.kt")
        for (entry in listOf("FirebaseConfig", "FirebaseAuth", "FirebaseFirestore", "FirebaseMessaging", "CloudSync", "Push.",
                             "SettingsSync.set(", "SettingsSync.write(", "SettingsSync.pageClosed", "appendGroupOps", "EmulatorSignIn", "signIn", "AppLock", "applock")) {
            assertFalse("the seed: $entry", entry in file)
        }
        // The account is only ever read (the refusal), never written or signed in.
        assertEquals(listOf("Account.readOwner("), Regex("""Account\.\w+\(?""").findAll(file).map { it.value }.distinct().toList())
        val root = main("AppRoot.kt")
        assertTrue("no CloudSync for a proof", root.contains("if (proofArg() == null) {\n            // D-192: coming to the front reaches the cloud sync"))
        assertTrue("no lock for a proof", root.contains("super.onStart()\n        if (proofArg() != null) return"))
        val store = tempStore()
        StoreShots.seed(store, late, ZoneOffset.UTC)
        assertNull("no account", Account.storedOwner(store))
        assertTrue("launched", store.rawKvGet(LAUNCHED_KEY)?.isNotEmpty() == true)
        assertFalse("the welcome not pending", WelcomeGate.pending(store))
    }

    // ---- Arguments ----

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
            private fun put(key: String?, value: Any?): SharedPreferences.Editor { pending[key!!] = value; return this }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor { pending.remove(key); map.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { map.putAll(pending) }
        }
    }

    @Test
    fun the_theme_and_mode_come_from_the_arguments() {
        fun t(a: String) = a.split(" ")
        assertEquals(AppTheme.NORSE, StoreShots.theme(t("--store-shots --store-theme norse")))
        assertEquals(AppTheme.VARDR, StoreShots.theme(t("--store-theme vardr")))
        assertNull("the stored raw value only", StoreShots.theme(t("--store-theme Norse")))
        assertNull(StoreShots.theme(t("--store-theme")))
        assertNull(StoreShots.theme(t("--store-shots")))
        assertEquals(AppearanceMode.LIGHT, StoreShots.mode(t("--store-theme aurora --store-mode light")))
        assertEquals("a theme with no mode: dark", AppearanceMode.DARK, StoreShots.mode(t("--store-theme aurora")))
        assertEquals("never the phone's", AppearanceMode.DARK, StoreShots.mode(t("--store-theme aurora --store-mode system")))
        assertNull(StoreShots.mode(t("--store-shots")))

        val prefs = MapPrefs()
        prefs.map[AppearanceMode.MIGRATED_KEY] = true
        StoreShots.applyAppearance({ prefs }, StoreShots.launch("--store-shots --store-theme vardr --store-mode light", true)!!)
        assertEquals("vardr", prefs.getString(Appearance.THEME, null))
        assertEquals("light", prefs.getString(Appearance.MODE, null))
        assertFalse("a chosen mode", prefs.contains(AppearanceMode.MIGRATED_KEY))
        val untouched = MapPrefs()
        StoreShots.applyAppearance({ untouched }, StoreShots.launch("--store-shots", true)!!)
        assertTrue("no theme asked, nothing written", untouched.map.isEmpty())
    }

    // ---- Times ----

    @Test
    fun every_row_is_this_month_and_in_order() {
        val hours = StoreShots.EXPENSES.map { it.hoursAgo } + StoreShots.SETTLEMENTS.map { it.hoursAgo }
        assertEquals("distinct, so the order is the table's", hours.size, hours.toSet().size)
        val lateBefore = listOf(
            31_622_400_000, 849_600_000, 763_200_000, 684_000_000, 597_600_000, 576_000_000,
            504_000_000, 424_800_000, 338_400_000, 252_000_000, 108_000_000, 21_600_000,
            900_000_000, 360_000_000, 270_000_000, 158_400_000, 259_200_000, 180_000_000,
            100_800_000, 18_000_000, 93_600_000, 187_200_000, 432_000_000, 604_800_000,
            720_000_000, 72_000_000,
        )
        val earlyBefore = listOf(
            31_622_400_000, 89_880_000, 89_820_000, 89_700_000, 89_580_000, 89_520_000,
            89_460_000, 89_340_000, 89_220_000, 89_040_000, 88_800_000, 21_600_000,
            89_940_000, 89_280_000, 89_160_000, 88_860_000, 89_100_000, 88_920_000,
            88_740_000, 18_000_000, 88_680_000, 88_980_000, 89_400_000, 89_640_000,
            89_760_000, 72_000_000,
        )
        for ((now, before) in listOf(late to lateBefore, early to earlyBefore)) {
            val times = StoreShots.times(hours, now, october)
            assertEquals(before, times.map { now - it })
            times.forEachIndexed { i, t ->
                if (hours[i] < StoreShots.KEPT_FROM_HOURS) {
                    assertTrue("row $i in this month", t >= october)
                    assertTrue(t < now)
                }
            }
            val byHours = hours.indices.sortedByDescending { hours[it] }.map { times[it] }
            assertEquals("older rows stay older", byHours.sorted(), byHours)
        }
    }

    @Test
    fun the_split_is_equal_with_the_remainder_first() {
        assertEquals(listOf(4L, 3L, 3L), StoreShots.split(10, listOf("a", "b", "c")).map { it.second })
        assertEquals(listOf(6_000L, 6_000L, 6_000L), StoreShots.split(18_000, listOf("Sam", "Jake", "Olivia")).map { it.second })
        assertEquals(listOf("Sam", "Emma"), StoreShots.split(7_000, listOf("Sam", "Emma")).map { it.first })
    }

    // ---- Set aside, never deleted (review fix wave: only a vault carrying the seed's flag is ever deleted) ----

    private fun storeIn(dir: File): LedgerStore {
        val path = File(dir, "ledger-vault.db").absolutePath
        return LedgerStore(path) { SkiptiVault.open(path, keystore) }
    }

    /** A store whose vault cannot be read (a stand-in file, not a vault: SQLite would treat a tiny main file as empty
     *  and drop its write-ahead file on open). It counts as a ledger: set aside, never deleted. */
    private fun unreadableIn(dir: File): LedgerStore =
        LedgerStore(File(dir, "ledger-vault.db").absolutePath) { throw IllegalStateException("not a vault") }

    private fun contents(dir: File): Map<String, List<Byte>> = dir.listFiles()!!.associate { it.name to it.readBytes().toList() }

    @Test
    fun the_old_vault_is_set_aside_once_and_only_a_seed_replaced() {
        val dir = Files.createTempDirectory("d251-aside").toFile()
        val store = storeIn(dir)
        val vault = store.path
        StoreShots.setAside(store)
        assertEquals("nothing there, nothing done", 0, dir.list()!!.size)
        File(vault).writeText("mine")
        File("$vault-wal").writeText("wal")
        StoreShots.setAside(unreadableIn(dir))
        assertFalse(File(vault).exists())
        assertEquals("mine", File("$vault.before-store-shots").readText())
        assertEquals("wal", File("$vault.before-store-shots-wal").readText())
        // The seed marks its vault; a later run deletes that vault, and the set-aside one stays.
        StoreShots.seed(store, late, ZoneOffset.UTC)
        assertTrue(store.rawKvGet(StoreShots.SEEDED_KEY)!!.contentEquals(byteArrayOf(1)))
        assertEquals(StoreShots.Found.SEED, StoreShots.check(store))
        StoreShots.setAside(store)
        assertFalse("an earlier seed goes", File(vault).exists())
        assertEquals("the first vault is kept", "mine", File("$vault.before-store-shots").readText())
    }

    private fun refusal(block: () -> Unit): String? =
        try { block(); null } catch (e: StoreShots.Refusal) { e.words }

    /** I1: a vault without the seed's flag while an aside exists is refused with the screen's words; no file changes. */
    @Test
    fun a_vault_without_the_flag_is_refused_while_an_aside_exists() {
        val dir = Files.createTempDirectory("d251-flag").toFile()
        val store = storeIn(dir)
        store.setYourName("Priya")
        assertNull(store.rawKvGet(StoreShots.SEEDED_KEY))
        File(store.path + ".before-store-shots").writeText("older")
        val before = contents(dir)
        assertEquals("A set-aside ledger already exists. Restore or remove it first.", refusal { StoreShots.setAside(store) })
        assertEquals("nothing moved, nothing deleted", before, contents(dir))
        // A stray set-aside write-ahead file is a destination in use too.
        val other = Files.createTempDirectory("d251-wal").toFile()
        val plain = unreadableIn(other)
        File(plain.path).writeText("mine")
        File(plain.path + ".before-store-shots-wal").writeText("old wal")
        val was = contents(other)
        assertEquals(StoreShots.Refusal.ASIDE_EXISTS, refusal { StoreShots.setAside(plain) })
        assertEquals(was, contents(other))
    }

    /** I1: a phone with an account is refused, with or without an aside, the seed's flag included. */
    @Test
    fun a_vault_with_an_account_is_refused() {
        val dir = Files.createTempDirectory("d251-owner").toFile()
        val store = storeIn(dir)
        store.rawKvSet(Account.OWNER_KEY, "uid-1".toByteArray())
        val before = contents(dir)
        assertEquals("This phone has an account. The store seed runs only on a throwaway device.", refusal { StoreShots.setAside(store) })
        assertEquals("nothing moved, nothing deleted", before, contents(dir))
        store.rawKvSet(StoreShots.SEEDED_KEY, byteArrayOf(1))
        assertEquals(StoreShots.Refusal.ACCOUNT, refusal { StoreShots.setAside(store) })
        assertTrue(File(store.path).exists())
    }

    /** The move is the main file first, all or nothing: a later move that fails puts the moved files back. */
    @Test
    fun the_move_is_all_or_nothing() {
        val dir = Files.createTempDirectory("d251-move").toFile()
        val store = unreadableIn(dir)
        File(store.path).writeText("mine")
        File(store.path + "-wal").writeText("wal")
        File(store.path + "-shm").writeText("shm")
        val before = contents(dir)
        val order = mutableListOf<String>()
        try {
            StoreShots.setAside(store) { from, to ->
                order += from.path
                from.path != store.path + "-shm" && from.renameTo(to)
            }
            fail("a move failed")
        } catch (e: StoreShots.SeedException) {
            assertTrue(e.message!!.contains("-shm"))
        }
        assertEquals("the main file first", store.path, order.first())
        assertEquals("every moved file put back", before, contents(dir))
    }

    @Test
    fun the_seed_writes_only_a_fresh_vault() {
        val store = tempStore()
        File(store.path).writeBytes(byteArrayOf(1))
        try {
            StoreShots.seed(store, late, ZoneOffset.UTC)
            fail("a vault already there")
        } catch (e: StoreShots.SeedException) {
            assertTrue(e.message!!.contains("fresh"))
        }
    }

    // ---- Home from the seed (core's numbers, pinned once against .drafts/d255/derive.py; iOS pins the same) ----

    /** D-253: the seeded groups keep the seed's fixed ids (iOS's own, compared above), so their tiles take the same
     *  tones on both phones on every capture: Hamptons weekend teal, Apartment 4B pink, Brooklyn dinners blue. The negative: a group built by
     *  core alone gets a random id, never the seed's. */
    @Test
    fun the_seeded_groups_keep_their_fixed_ids_and_tones() {
        val store = tempStore()
        StoreShots.seed(store, late, ZoneOffset.UTC)
        val named = store.namedGroups()
        assertEquals(StoreShots.GROUPS.map { it.id }, named.map { it.group.id })
        assertEquals(listOf(GroupTone.TEAL, GroupTone.PINK, GroupTone.BLUE), named.map { GroupTone.of(it.group.id) })
        assertEquals(listOf(2, 5, 1), named.map { GroupTone.of(it.group.id).ordinal })
        assertEquals("three different ids", 3, named.map { it.group.id }.toSet().size)
        for (g in named) assertTrue(g.group.id, Regex("[0-9a-f]{32}").matches(g.group.id))
        val me = store.ensureSelf()
        val random = uniffi.skipti_core.ffiCreateNamedGroup("Hamptons weekend", "USD", uniffi.skipti_core.MemberWire(me.id, "Sam"), listOf("Emma"))
        assertFalse("core's id is random", random.group.id in StoreShots.GROUPS.map { it.id })
    }

    @Test
    fun the_seeded_vault_gives_home_its_numbers() {
        val store = tempStore()
        StoreShots.seed(store, late, ZoneOffset.UTC)
        assertEquals(25, store.loadExpenses().size)
        assertEquals(1, store.loadSettlements().size)
        val home = HomeOverview.load(store)
        assertEquals(listOf("Hamptons weekend", "Apartment 4B", "Brooklyn dinners"), home.named.map { it.group.name })
        assertEquals(setOf("Sam", "Emma", "Jake", "Olivia", "Ben"), home.named.flatMap { g -> g.members.map { it.name } }.toSet())
        assertEquals(listOf("USD 251675"), home.overview.totals.map { "${it.currencyCode} ${it.amountMinor}" }.sorted())
        fun net(group: String): List<String> {
            val id = home.named.first { it.group.name == group }.group.id
            return home.overview.groups.first { it.groupId == id }.nets.map { "${it.currencyCode} ${it.amountMinor}" }
        }
        assertEquals(listOf("USD 55275"), net("Hamptons weekend"))
        assertEquals(listOf("USD 202900"), net("Apartment 4B"))
        assertEquals(listOf("USD -6500"), net("Brooklyn dinners"))

        // Your spending on Home (Just me, this month): five categories.
        val month = SpendingMonth.containing(late, ZoneOffset.UTC)
        val spent = SpendingLoad.load(store, month, SpendingView.JUST_ME, ZoneOffset.UTC).out
        assertEquals(listOf("USD 52550"), spent.currencies.map { "${it.currencyCode} ${it.totalMinor}" })
        assertEquals(5, spent.currencies.first().categories.size)
        assertEquals(6, spent.rows.size)

        // Hamptons weekend's milestone is due (a year since its first expense); the others have none.
        val live = store.projectedExpenses().expenses
        fun milestone(group: String): Milestone? {
            val id = home.named.first { it.group.name == group }.group.id
            return Milestones.current(
                live.filter { it.expense.group == id && !it.deleted }
                    .map { MilestoneExpense(it.addedAtMs.toLong(), (it.timestampMs ?: it.addedAtMs).toLong()) },
                late,
            )?.milestone
        }
        assertEquals(Milestone.YEAR, milestone("Hamptons weekend"))
        assertNull(milestone("Apartment 4B"))

        // Lately: the newest of others' additions, each at its row's time (not "now").
        val lately = Lately.pick(ActivityFeed.load(store, null).entries).mapNotNull { it.item }
        assertEquals(listOf("Ride to the station", "Tacos", "Seafood dinner"), lately.map { it.title })
        assertEquals(listOf(6L, 28L, 30L), lately.map { (late - it.atMs) / 3_600_000 })
    }
}
