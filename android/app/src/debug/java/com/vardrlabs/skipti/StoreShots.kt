package com.vardrlabs.skipti

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ffiCategoryTreeDefault
import uniffi.skipti_core.ffiCreateNamedGroup

// D-251 (U32): `--es args "--store-shots --store-theme <theme> [--store-mode <mode>]"` (the flag anywhere, as on iOS),
// the store pages' seed. Debug builds only, twice over: this file is in `src/debug` (a release build compiles
// `src/release`'s stub, which seeds nothing: review M3), and it is a proof argument (ProofArgs.proof and [StoreShots.launch]
// answer null when the build is not debuggable). A look's tool, not a user flow, and for a THROWAWAY device only
// (`.drafts/u32/plan.md`, "Restoring after the store seed"): MainActivity is exported, so on a DEBUG install any app on
// the phone can start it with `--store-shots`; what bounds that is below (a phone with an account is refused, a vault is
// deleted only when it carries the seed's own flag, any other vault is set aside once, never deleted). The launch sets
// the old vault aside, writes a fresh one through the ordinary store paths (core's checks run on every expense and
// payment), names no account and starts nothing in the cloud (a proof launch: MainActivity skips the sync and the lock
// for it), and the app lock stays off for that launch only (nothing is written to its setting). The theme and mode it
// writes stay written (the phone's settings), and D-232's migrated mark goes. The launch's args are used once (review
// I1): the extra is removed, and a relaunch from Recents or a re-creation after the process died runs the ordinary app.
// iOS's twin is StoreShots.swift; StoreShotsTest compares the seed table below with it, row by row.

/** One group of the seed: you ("Sam") plus [people]. */
/** D-253: [id] is the group's FIXED id (32 hex), iOS's own, so its tile takes the same tone on every capture
 *  (`GroupTone.of` reads the id's first byte; a created group's id is random, and D-251's two captures drew two
 *  groups in different tones on the two phones). */
data class StoreShotsGroup(val name: String, val currency: String, val people: List<String>, val id: String)

/** One expense of the seed. [ledger] is a group's name or "Just me"; [category] is "Parent/Leaf" in core's default
 *  tree; [among] is who shares it (empty: everyone in the ledger), split equally, a remainder to the first named. */
data class StoreShotsExpense(
    val ledger: String,
    val payer: String,
    val minor: Long,
    val note: String,
    val category: String,
    val hoursAgo: Int,
    val among: List<String>,
)

/** One payment of the seed, in a group: [from] paid [to]. */
data class StoreShotsSettlement(val ledger: String, val from: String, val to: String, val minor: Long, val hoursAgo: Int)

object StoreShots {
    const val FLAG = "--store-shots"
    const val THEME_FLAG = "--store-theme"
    const val MODE_FLAG = "--store-mode"
    /** Decision 173: the sample "you". */
    const val YOU = "Sam"
    const val JUST_ME = "Just me"
    const val JUST_ME_CURRENCY = "USD"
    /** A row at least this old keeps its own date (Hamptons weekend's first expense, a year back: its milestone is
     *  due); every other row is placed inside the current month, so every shot reads "this month". */
    const val KEPT_FROM_HOURS = 8_760
    /** The vault an earlier launch left is moved here once (never deleted); a later launch's seeded vault is replaced. */
    const val ASIDE_SUFFIX = ".before-store-shots"
    /** Written into a vault first of all by [seed]: the one mark that lets a later run delete it. */
    const val SEEDED_KEY = "storeshots/seeded"
    /** AppRoot's vault (`File(context.filesDir, "ledger-vault.db")`). */
    private const val VAULT_FILE = "ledger-vault.db"

    // ---- The seed table (iOS's StoreShots.swift holds the same rows, in the same order) ----

    val GROUPS = listOf(
        StoreShotsGroup("Hamptons weekend", "USD", listOf("Emma", "Jake", "Olivia"), "02bce5d13d3c6bbabded0d02baa8f38f"),
        StoreShotsGroup("Apartment 4B", "USD", listOf("Emma"), "05812af319f50da19936d26e14706717"),
        StoreShotsGroup("Brooklyn dinners", "USD", listOf("Ben"), "018c2fba8e39317ca229dc1fd08e9854"),
    )

    val EXPENSES = listOf(
        StoreShotsExpense("Hamptons weekend", "Sam", 40_000, "Beach house deposit", "Stay/Rentals", 8_784, listOf()),
        StoreShotsExpense("Hamptons weekend", "Emma", 112_000, "Train tickets", "Travel/Flights", 236, listOf()),
        StoreShotsExpense("Hamptons weekend", "Sam", 96_000, "Beach house, three nights", "Stay/Rentals", 212, listOf()),
        StoreShotsExpense("Hamptons weekend", "Jake", 8_000, "Bike rentals", "Travel/Rentals", 190, listOf()),
        StoreShotsExpense("Hamptons weekend", "Olivia", 6_200, "Lobster rolls", "Food & Drink/Restaurants", 166, listOf()),
        StoreShotsExpense("Hamptons weekend", "Jake", 4_500, "Gas", "Travel/Fuel", 160, listOf()),
        StoreShotsExpense("Hamptons weekend", "Sam", 18_000, "Kayak tour", "Entertainment/Events", 140, listOf("Sam", "Jake", "Olivia")),
        StoreShotsExpense("Hamptons weekend", "Emma", 5_400, "Groceries for the house", "Food & Drink/Groceries", 118, listOf()),
        StoreShotsExpense("Hamptons weekend", "Olivia", 9_600, "Beach club lunch", "Food & Drink/Restaurants", 94, listOf()),
        StoreShotsExpense("Hamptons weekend", "Emma", 2_400, "Coffee and pastries", "Food & Drink/Cafés", 70, listOf()),
        StoreShotsExpense("Hamptons weekend", "Emma", 21_000, "Seafood dinner", "Food & Drink/Restaurants", 30, listOf()),
        StoreShotsExpense("Hamptons weekend", "Jake", 5_800, "Ride to the station", "Travel/Taxis", 6, listOf()),
        StoreShotsExpense("Apartment 4B", "Sam", 380_000, "Rent", "Bills & Utilities/Rent", 250, listOf()),
        StoreShotsExpense("Apartment 4B", "Sam", 14_200, "Electricity", "Bills & Utilities/Electricity", 100, listOf()),
        StoreShotsExpense("Apartment 4B", "Emma", 7_000, "Internet", "Bills & Utilities/Internet", 75, listOf()),
        StoreShotsExpense("Apartment 4B", "Sam", 18_600, "Groceries", "Food & Drink/Groceries", 44, listOf()),
        StoreShotsExpense("Brooklyn dinners", "Ben", 6_400, "Pizza night", "Food & Drink/Restaurants", 72, listOf()),
        StoreShotsExpense("Brooklyn dinners", "Sam", 14_800, "Dinner in Williamsburg", "Food & Drink/Restaurants", 50, listOf()),
        StoreShotsExpense("Brooklyn dinners", "Ben", 4_600, "Tacos", "Food & Drink/Restaurants", 28, listOf()),
        StoreShotsExpense("Just me", "Sam", 14_200, "Weekly groceries", "Food & Drink/Groceries", 5, listOf()),
        StoreShotsExpense("Just me", "Sam", 650, "Cold brew", "Food & Drink/Cafés", 26, listOf()),
        StoreShotsExpense("Just me", "Sam", 2_800, "Cab home", "Travel/Taxis", 52, listOf()),
        StoreShotsExpense("Just me", "Sam", 14_000, "Running shoes", "Shopping/Clothing", 120, listOf()),
        StoreShotsExpense("Just me", "Sam", 12_000, "Concert tickets", "Entertainment/Events", 168, listOf()),
        StoreShotsExpense("Just me", "Sam", 8_900, "Gym membership", "Health/Fitness", 200, listOf()),
    )

    val SETTLEMENTS = listOf(
        StoreShotsSettlement("Hamptons weekend", "Olivia", "Sam", 15_000, 20),
    )

    // ---- Arguments ----

    private fun tokens(args: String?): List<String> =
        args?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

    private fun value(tokens: List<String>, flag: String): String? {
        val i = tokens.indexOf(flag)
        return if (i >= 0) tokens.getOrNull(i + 1) else null
    }

    /** What a launch asks for, or null: debuggable builds only, `--store-shots` anywhere among the args (as on iOS). */
    data class Launch(val theme: AppTheme?, val mode: AppearanceMode?)

    fun launch(args: String?, debuggable: Boolean): Launch? {
        val t = tokens(args)
        if (!debuggable || FLAG !in t) return null
        return Launch(theme(t), mode(t))
    }

    /** Review I1: a launch's `--store-shots` args are used once. A process keeps the args its first fresh launch
     *  admitted, for the activity's re-creations (a theme's night mode makes it again); a launch from Recents
     *  (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`) or a re-creation in a process that admitted nothing (the process died and
     *  the system restored the activity with its first intent) gets no args: the ordinary app, no seed. */
    internal class Admission {
        private var held: String? = null

        @Synchronized
        fun args(raw: String?, fromHistory: Boolean, restored: Boolean, debuggable: Boolean): String? {
            if (restored) held?.let { return it }
            if (!restored) held = null
            if (launch(raw, debuggable) == null) return raw
            if (fromHistory || restored) return null
            held = raw
            return raw
        }
    }

    private val admission = Admission()

    /** `--store-theme norse|aurora|vardr`; anything else, or none, is null (the stored theme is left alone). */
    fun theme(tokens: List<String>): AppTheme? = value(tokens, THEME_FLAG)?.let { v -> AppTheme.entries.firstOrNull { it.stored == v } }

    /** `--store-mode light|dark`; with a theme and no mode, dark (a shot never follows the phone). */
    fun mode(tokens: List<String>): AppearanceMode? =
        value(tokens, MODE_FLAG)?.let { v -> AppearanceMode.entries.firstOrNull { it.stored == v && it != AppearanceMode.SYSTEM } }
            ?: if (theme(tokens) != null) AppearanceMode.DARK else null

    private val prepared = AtomicBoolean(false)

    /** `MainActivity.onCreate`, before the night mode is told and the appearance read. Answers the args this activity
     *  runs with ([Admission]; the `args` extra is removed once read, so it is used once). For an admitted store-shots
     *  launch, once per process (a theme's night mode makes the activity again): the theme and mode the shot asks for,
     *  where Appearance stores them (so You > Appearance shows the chosen theme), on this phone only: no setting record,
     *  no account; nothing when the seed would refuse ([check]). */
    fun prepare(context: Context, intent: Intent?, restored: Boolean, debuggable: Boolean): String? {
        val raw = intent?.getStringExtra("args")
        val fromHistory = intent != null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val args = admission.args(raw, fromHistory, restored, debuggable)
        if (launch(raw, debuggable) != null) intent?.removeExtra("args")
        val launch = launch(args, debuggable) ?: return args
        if (!prepared.compareAndSet(false, true)) return args
        // The check opens an existing vault (after D-232's settle; never creates one) through VaultGate, which onCreate
        // gives its context only after this call.
        VaultGate.appContextRef = context.applicationContext
        val store = LedgerStore(File(context.filesDir, VAULT_FILE).absolutePath)
        if (runCatching { check(store) }.isSuccess) applyAppearance(SettingsSync.prefsOf(context), launch)
        return args
    }

    /** The write itself, through the settings funnel's local write (the one an arrival from the account uses: the value
     *  on this phone, never a record); a mode named is a choice, so D-232's migrated mark goes, as the funnel does. A test
     *  passes its own preferences. */
    internal fun applyAppearance(prefs: (String) -> SharedPreferences, launch: Launch) {
        launch.theme?.let { SettingsSync.applyFromAccount(SettingsSync.THEME, it.stored, prefs) }
        launch.mode?.let {
            SettingsSync.applyFromAccount(SettingsSync.MODE, it.stored, prefs)
            prefs(Appearance.PREFS).edit().remove(AppearanceMode.MIGRATED_KEY).commit()
        }
    }

    // ---- Times ----

    /** Each row's time, in the order given (`hoursAgo` of every expense, then every payment). A row from
     *  [KEPT_FROM_HOURS] on keeps `now - hoursAgo`; every other one is `now - hoursAgo`, but never before the month's
     *  first instant (plus a minute per row, oldest first) and never at or after now, so the rows stay in their order
     *  inside this month. */
    fun times(hoursAgo: List<Int>, nowMs: Long, monthStartMs: Long): List<Long> {
        val hour = 3_600_000L
        val inMonth = hoursAgo.indices.filter { hoursAgo[it] < KEPT_FROM_HOURS }.sortedByDescending { hoursAgo[it] }
        val out = hoursAgo.map { nowMs - minOf(nowMs, it * hour) }.toMutableList()
        inMonth.forEachIndexed { rank, index ->
            val floor = monthStartMs + (rank + 1) * 60_000L
            val ceiling = nowMs - minOf(nowMs, (inMonth.size - rank) * 1_000L)
            out[index] = minOf(maxOf(out[index], floor), ceiling)
        }
        return out
    }

    /** An equal split of [minor] among [people], the remainder a unit each to the first named. */
    fun split(minor: Long, people: List<String>): List<Pair<String, Long>> {
        val n = people.size.toLong()
        val each = minor / n
        val rest = (minor % n).toInt()
        return people.mapIndexed { i, p -> p to each + if (i < rest) 1 else 0 }
    }

    // ---- The seed ----

    class SeedException(message: String) : Exception(message)

    /** Why a run refuses. Nothing has changed when one is thrown; [words] are the proof screen's. */
    class Refusal(val words: String) : Exception(words) {
        companion object {
            const val ACCOUNT = "This phone has an account. The store seed runs only on a throwaway device."
            const val ASIDE_EXISTS = "A set-aside ledger already exists. Restore or remove it first."
        }
    }

    /** What is at the vault path now. */
    enum class Found {
        NOTHING,
        /** An earlier run's seed: it carries [SEEDED_KEY]. */
        SEED,
        /** Anything else: a person's ledger, or a vault that cannot be read (never deleted, only set aside). */
        LEDGER,
    }

    /** Reads, never writes: the vault is opened only when its main file exists (an open creates one). A vault with an
     *  owner is refused; one that cannot be read counts as a ledger (set aside, never deleted); a ledger is refused while
     *  any set-aside file exists, so every destination of the move is free before anything moves. */
    fun check(store: LedgerStore): Found {
        val path = store.path
        if (LedgerErase.ORDER.none { File(path + it).exists() }) return Found.NOTHING
        var seeded = false
        if (File(path).exists()) {
            if (runCatching { Account.readOwner(store) }.getOrNull() != null) throw Refusal(Refusal.ACCOUNT)
            seeded = runCatching { store.rawKvGet(SEEDED_KEY)?.contentEquals(byteArrayOf(1)) == true }.getOrDefault(false)
        }
        if (seeded) return Found.SEED
        if (LedgerErase.ORDER.any { File(path + ASIDE_SUFFIX + it).exists() }) throw Refusal(Refusal.ASIDE_EXISTS)
        return Found.LEDGER
    }

    /** Clears the vault path for the seed. An earlier seed (and only a vault carrying [SEEDED_KEY]) is deleted; any
     *  other vault is moved aside with its write-ahead files, the main file first, all or nothing: a move that fails puts
     *  back every file already moved. The key stays, so the aside still opens when it is moved back. */
    fun setAside(store: LedgerStore, move: (File, File) -> Boolean = { from, to -> from.renameTo(to) }) {
        val path = store.path
        when (check(store)) {
            Found.NOTHING -> return
            Found.SEED -> for (suffix in LedgerErase.ORDER) {
                val file = File(path + suffix)
                if (file.exists() && !file.delete()) throw SeedException("could not remove ${file.name}")
            }
            Found.LEDGER -> {
                val pairs = listOf("", "-wal", "-shm", "-journal").map { File(path + it) to File(path + ASIDE_SUFFIX + it) }
                    .filter { it.first.exists() }
                val moved = mutableListOf<Pair<File, File>>()
                for (pair in pairs) {
                    if (!move(pair.first, pair.second)) {
                        for (back in moved.asReversed()) move(back.second, back.first)
                        throw SeedException("could not set ${pair.first.name} aside")
                    }
                    moved += pair
                }
            }
        }
    }

    /** Writes the seed into a vault that does not exist yet, through the ordinary store paths, oldest first (each
     *  record's clock is its row's time, so Activity and Lately read "6h ago", not "now"); then marks the phone launched
     *  with the welcome not pending. */
    fun seed(store: LedgerStore, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        if (File(store.path).exists()) throw SeedException("the seed writes only a fresh vault")
        // First of all, so even a seed that fails halfway is known as a seed by the next run.
        store.rawKvSet(SEEDED_KEY, byteArrayOf(1))
        val monthStart = SpendingMonth.containing(nowMs, zone).bounds(zone).fromMs.toLong()
        val at = times(EXPENSES.map { it.hoursAgo } + SETTLEMENTS.map { it.hoursAgo }, nowMs, monthStart)
        val start = (at.minOrNull() ?: nowMs) - 3_600_000L
        store.setYourName(YOU, start)
        GROUPS.forEachIndexed { i, g ->
            // D-253: built as `createGroup` builds a group (core's `ffiCreateNamedGroup`), then kept under the seed's
            // fixed id, so the tile's tone is the same on both phones (iOS's seed does the same).
            val madeAt = start + (i + 1) * 1_000L
            val me = store.ensureSelf(madeAt)
            val built = ffiCreateNamedGroup(g.name, g.currency, MemberWire(me.id, YOU), g.people)
            store.adopt(built.copy(group = built.group.copy(id = g.id)), madeAt)
        }
        val named = store.namedGroups()
        val me = store.ensureSelf().id
        val tree = ffiCategoryTreeDefault()
        fun leaf(path: String): String {
            val parts = path.split("/", limit = 2)
            val parent = tree.categories.firstOrNull { it.parent == null && it.name == parts.getOrNull(0) }
            return tree.categories.firstOrNull { parent != null && it.parent == parent.id && it.name == parts.getOrNull(1) }?.id
                ?: throw SeedException("unknown category $path")
        }
        fun group(name: String): NamedGroupWire = named.firstOrNull { it.group.name == name } ?: throw SeedException("unknown group $name")
        fun id(name: String, g: NamedGroupWire): String =
            if (name == YOU) g.selfId else g.members.firstOrNull { it.name == name }?.id ?: throw SeedException("unknown person $name")

        val writes = (EXPENSES.map { it as Any } + SETTLEMENTS).mapIndexed { i, row -> at[i] to row }.sortedBy { it.first }
        for ((time, row) in writes) {
            when (row) {
                is StoreShotsExpense -> if (row.ledger == JUST_ME) {
                    store.appendExpense(JustMeExpense.dto(me, row.minor, JUST_ME_CURRENCY, row.note, leaf(row.category), time), time)
                } else {
                    val g = group(row.ledger)
                    val people = row.among.ifEmpty { listOf(YOU) + GROUPS.first { it.name == row.ledger }.people }
                    val payer = id(row.payer, g)
                    store.appendExpense(
                        ExpenseDTO(
                            payer = payer, paidMinor = row.minor, currencyCode = g.group.settlementCurrency, group = g.group.id,
                            shares = split(row.minor, people).map { ExpenseDTO.ShareDTO(id(it.first, g), it.second) },
                            note = row.note, timestampMs = time, category = leaf(row.category), addedBy = payer,
                        ),
                        time,
                    )
                }
                is StoreShotsSettlement -> {
                    val g = group(row.ledger)
                    store.appendSettlement(
                        SettlementDTO(id(row.from, g), id(row.to, g), row.minor, g.group.settlementCurrency, g.group.id),
                        time,
                    )
                }
            }
        }
        // The launch's own marks (D-175, D-245): launched, then the welcome cleared, as closing it does.
        val launched = VaultRecovery.launch(LAUNCHED_KEY, firstLaunch = { WelcomeGate.mark(it) }) { store }
        if (launched is VaultRecovery.Launch.Unavailable) throw SeedException(launched.reason)
        WelcomeGate.clear(store)
    }

    private val lock = Any()
    private var seeding: Deferred<Result<Unit>>? = null

    /** Once per process (a theme's night mode makes the activity again; the seed must not run twice): set aside, then
     *  seed, off the main thread, outliving the activity that started it. */
    fun seedOnce(vaultPath: String): Deferred<Result<Unit>> = synchronized(lock) {
        seeding ?: CoroutineScope(Dispatchers.IO).async {
            runCatching {
                setAside(LedgerStore(vaultPath))
                seed(LedgerStore(vaultPath))
            }
        }.also { seeding = it }
    }
}

/** The launch's surface: the seed written (once), then [shell], the ordinary tabs on Home. */
@Composable
fun StoreShotsProof(vaultPath: String, shell: @Composable () -> Unit) {
    var result by remember { mutableStateOf<Result<Unit>?>(null) }
    LaunchedEffect(Unit) { result = StoreShots.seedOnce(vaultPath).await() }
    val done = result ?: return
    done.exceptionOrNull()?.let {
        Text(if (it is StoreShots.Refusal) it.words else "store shots: ${it.message}", modifier = Modifier.padding(20.dp))
        return
    }
    shell()
}
