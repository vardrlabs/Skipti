package com.vardrlabs.skipti

import android.content.Context
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
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ffiCategoryTreeDefault

// D-251 (U32): `--es args "--store-shots --store-theme <theme> [--store-mode <mode>]"`, the store pages' seed.
// Debuggable builds only: it is a proof argument (ProofArgs.proof answers null in a release build, and [StoreShots.launch]
// does too), so a release build never sets a vault aside or seeds one. A look's tool, not a user flow: the launch sets
// the old vault aside, writes a fresh one through the ordinary store paths (core's checks run on every expense and
// payment), names no account and starts nothing in the cloud (a proof launch: MainActivity skips the sync and the lock
// for it), and the app lock stays off for that launch only (nothing is written to its setting). iOS's twin is
// StoreShots.swift; StoreShotsTest compares the seed table below with it, row by row.

/** One group of the seed: you ("Sam") plus [people]. */
data class StoreShotsGroup(val name: String, val currency: String, val people: List<String>)

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
    const val JUST_ME_CURRENCY = "INR"
    /** A row at least this old keeps its own date (Goa trip's first expense, a year back: its milestone is due); every
     *  other row is placed inside the current month, so every shot reads "this month". */
    const val KEPT_FROM_HOURS = 8_760
    /** The vault an earlier launch left is moved here once (never deleted); a later launch's seeded vault is replaced. */
    const val ASIDE_SUFFIX = ".before-store-shots"

    // ---- The seed table (iOS's StoreShots.swift holds the same rows, in the same order) ----

    val GROUPS = listOf(
        StoreShotsGroup("Goa trip", "INR", listOf("Meera", "Kiran", "Rahul")),
        StoreShotsGroup("Flat 4B", "INR", listOf("Meera")),
        StoreShotsGroup("Paris", "EUR", listOf("Ana")),
    )

    val EXPENSES = listOf(
        StoreShotsExpense("Goa trip", "Sam", 1_200_000, "Villa deposit", "Stay/Rentals", 8_784, listOf()),
        StoreShotsExpense("Goa trip", "Meera", 3_360_000, "Flights to Goa", "Travel/Flights", 236, listOf()),
        StoreShotsExpense("Goa trip", "Sam", 2_400_000, "Villa, three nights", "Stay/Rentals", 212, listOf()),
        StoreShotsExpense("Goa trip", "Kiran", 240_000, "Scooters", "Travel/Rentals", 190, listOf()),
        StoreShotsExpense("Goa trip", "Rahul", 186_000, "Fish thali lunch", "Food & Drink/Restaurants", 166, listOf()),
        StoreShotsExpense("Goa trip", "Kiran", 80_000, "Fuel", "Travel/Fuel", 160, listOf()),
        StoreShotsExpense("Goa trip", "Sam", 360_000, "Parasailing", "Entertainment/Events", 140, listOf("Sam", "Kiran", "Rahul")),
        StoreShotsExpense("Goa trip", "Meera", 154_000, "Groceries for the villa", "Food & Drink/Groceries", 118, listOf()),
        StoreShotsExpense("Goa trip", "Rahul", 212_000, "Beach shack lunch", "Food & Drink/Restaurants", 94, listOf()),
        StoreShotsExpense("Goa trip", "Meera", 64_000, "Coffee and cake", "Food & Drink/Cafés", 70, listOf()),
        StoreShotsExpense("Goa trip", "Meera", 480_000, "Seafood dinner", "Food & Drink/Restaurants", 30, listOf()),
        StoreShotsExpense("Goa trip", "Kiran", 140_000, "Cab to the airport", "Travel/Taxis", 6, listOf()),
        StoreShotsExpense("Flat 4B", "Sam", 4_200_000, "Rent", "Bills & Utilities/Rent", 250, listOf()),
        StoreShotsExpense("Flat 4B", "Sam", 184_000, "Electricity", "Bills & Utilities/Electricity", 100, listOf()),
        StoreShotsExpense("Flat 4B", "Meera", 99_800, "Internet", "Bills & Utilities/Internet", 75, listOf()),
        StoreShotsExpense("Flat 4B", "Sam", 236_000, "Groceries", "Food & Drink/Groceries", 44, listOf()),
        StoreShotsExpense("Paris", "Ana", 12_800, "Museum passes", "Entertainment/Events", 72, listOf()),
        StoreShotsExpense("Paris", "Sam", 9_600, "Dinner in the Marais", "Food & Drink/Restaurants", 50, listOf()),
        StoreShotsExpense("Paris", "Ana", 3_400, "Metro tickets", "Transport/Public transit", 28, listOf()),
        StoreShotsExpense("Just me", "Sam", 245_000, "Weekly groceries", "Food & Drink/Groceries", 5, listOf()),
        StoreShotsExpense("Just me", "Sam", 18_000, "Filter coffee", "Food & Drink/Cafés", 26, listOf()),
        StoreShotsExpense("Just me", "Sam", 86_000, "Cab home", "Travel/Taxis", 52, listOf()),
        StoreShotsExpense("Just me", "Sam", 489_900, "Running shoes", "Shopping/Clothing", 120, listOf()),
        StoreShotsExpense("Just me", "Sam", 250_000, "Concert tickets", "Entertainment/Events", 168, listOf()),
        StoreShotsExpense("Just me", "Sam", 180_000, "Gym membership", "Health/Fitness", 200, listOf()),
    )

    val SETTLEMENTS = listOf(
        StoreShotsSettlement("Goa trip", "Rahul", "Sam", 300_000, 20),
    )

    // ---- Arguments ----

    private fun tokens(args: String?): List<String> =
        args?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

    private fun value(tokens: List<String>, flag: String): String? {
        val i = tokens.indexOf(flag)
        return if (i >= 0) tokens.getOrNull(i + 1) else null
    }

    /** What a launch asks for, or null: debuggable builds only, and `--store-shots` first (the proof's own flag). */
    data class Launch(val theme: AppTheme?, val mode: AppearanceMode?)

    fun launch(args: String?, debuggable: Boolean): Launch? {
        val t = tokens(args)
        if (!debuggable || t.firstOrNull() != FLAG) return null
        return Launch(theme(t), mode(t))
    }

    /** `--store-theme norse|aurora|vardr`; anything else, or none, is null (the stored theme is left alone). */
    fun theme(tokens: List<String>): AppTheme? = value(tokens, THEME_FLAG)?.let { v -> AppTheme.entries.firstOrNull { it.stored == v } }

    /** `--store-mode light|dark`; with a theme and no mode, dark (a shot never follows the phone). */
    fun mode(tokens: List<String>): AppearanceMode? =
        value(tokens, MODE_FLAG)?.let { v -> AppearanceMode.entries.firstOrNull { it.stored == v && it != AppearanceMode.SYSTEM } }
            ?: if (theme(tokens) != null) AppearanceMode.DARK else null

    private val prepared = AtomicBoolean(false)

    /** `MainActivity.onCreate`, before the night mode is told and the appearance read: once per process (a theme's
     *  night mode makes the activity again), the theme and mode the shot asks for, where Appearance stores them (so You
     *  > Appearance shows the chosen theme), on this phone only: no setting record, no account. */
    fun prepare(context: Context, args: String?, debuggable: Boolean) {
        val launch = launch(args, debuggable) ?: return
        if (!prepared.compareAndSet(false, true)) return
        applyAppearance(SettingsSync.prefsOf(context), launch)
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

    /** The vault an earlier launch left: moved aside once (with its write-ahead files), so nothing a person typed is
     *  deleted; when an aside already exists, the vault is an earlier seed's and is removed. The key stays, so the aside
     *  still opens if it is moved back. */
    fun setAside(vaultPath: String) {
        val files = LedgerErase.ORDER.map { File(vaultPath + it) }
        if (files.none { it.exists() }) return
        val keepFirst = !File(vaultPath + ASIDE_SUFFIX).exists()
        for (suffix in LedgerErase.ORDER) {
            val file = File(vaultPath + suffix)
            if (!file.exists()) continue
            val done = if (keepFirst) file.renameTo(File(vaultPath + ASIDE_SUFFIX + suffix)) else file.delete()
            if (!done) throw SeedException("could not set ${file.name} aside")
        }
    }

    /** Writes the seed into a vault that does not exist yet, through the ordinary store paths, oldest first (each
     *  record's clock is its row's time, so Activity and Lately read "6h ago", not "now"); then marks the phone launched
     *  with the welcome not pending. */
    fun seed(store: LedgerStore, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        if (File(store.path).exists()) throw SeedException("the seed writes only a fresh vault")
        val monthStart = SpendingMonth.containing(nowMs, zone).bounds(zone).fromMs.toLong()
        val at = times(EXPENSES.map { it.hoursAgo } + SETTLEMENTS.map { it.hoursAgo }, nowMs, monthStart)
        val start = (at.minOrNull() ?: nowMs) - 3_600_000L
        store.setYourName(YOU, start)
        GROUPS.forEachIndexed { i, g -> store.createGroup(g.name, g.currency, g.people, start + (i + 1) * 1_000L) }
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
                setAside(vaultPath)
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
        Text("store shots: ${it.message}", modifier = Modifier.padding(20.dp))
        return
    }
    shell()
}
