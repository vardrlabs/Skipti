package com.vardrlabs.skipti

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.SettingOut

/**
 * D-244 (ADR-0043): with an account, a phone's plain preferences follow the account: theme, light or dark, text size,
 * Less motion, Haptics, the app icon choice and the default currency. Each is a personal `setting` record in the
 * account's own log (core's `GroupOpIn.Setting`, latest write per key); every phone applies the latest on arrival, at
 * launch, and at its first sign-in, where the account's values win and this phone fills only what the account lacks.
 * Without an account nothing is written (GUARANTEES #5); with sync off a record waits on this phone (`push` is gated by
 * `CloudSync.ready`). App lock, screen privacy, the sync switch, notifications and the device name stay per phone.
 *
 * [set] is the ONE write of a synced preference (a source guard in SettingsSyncTest holds it): it stores the value on
 * this phone as before, then appends the record when this install is signed in to the vault's owner. Applying an
 * arrival writes the phone only ([applyFromAccount]), never a record, so nothing echoes. The twin of iOS's
 * SettingsSync.swift: the same tables in the same order (SettingsSyncTest reads iOS's `synced` and `perPhone` and
 * compares them), the same vault keys, the same decisions.
 */
object SettingsSync {
    /** How a value is kept on this phone; on the wire every value is a string. */
    enum class Kind {
        /** A word, kept as a string (theme, mode, icon, currency). */
        WORD,

        /** A text size step, kept as an Int, written as its decimal (`-2` to `3`). */
        STEP,

        /** A switch, kept as a Boolean, written `true` or `false`. */
        FLAG,
    }

    // The wire keys: iOS's storage keys, permanent from D-244. Named once, here.
    const val THEME = "appearance.theme"
    const val MODE = "appearance.mode"
    const val TEXT_SIZE = "appearance.textSize"
    const val LESS_MOTION = "appearance.lessMotion"
    const val HAPTICS = "appearance.haptics"
    const val ICON = "appearance.icon"
    const val CURRENCY = "money.default-currency"

    /** A synced setting: its wire key, how its value is kept, and where THIS phone keeps it (the preferences file and
     *  the key in it). The one map between the wire's names and Android's. */
    data class Synced(val key: String, val kind: Kind, val file: String, val pref: String)

    /** The settings that follow the account, in this order (iOS's `synced`, the same keys and kinds). Less motion and
     *  the default currency are stored only once the person chooses, so only an explicit value ever syncs (never the
     *  low-memory default, never the region's currency). */
    val synced: List<Synced> = listOf(
        Synced(THEME, Kind.WORD, Appearance.PREFS, Appearance.THEME),
        Synced(MODE, Kind.WORD, Appearance.PREFS, Appearance.MODE),
        Synced(TEXT_SIZE, Kind.STEP, Appearance.PREFS, Appearance.TEXT),
        Synced(LESS_MOTION, Kind.FLAG, Appearance.PREFS, MotionSetting.STORAGE_KEY),
        Synced(HAPTICS, Kind.FLAG, Appearance.PREFS, HapticsSetting.STORAGE_KEY),
        Synced(ICON, Kind.WORD, Appearance.PREFS, AppIconChoice.STORAGE_KEY),
        Synced(CURRENCY, Kind.WORD, DefaultCurrency.PREFS, DefaultCurrency.KEY),
    )

    /** Kept per phone, never synced, and ignored if one ever arrives (iOS's names, the wire's): the app lock's timeout
     *  and screen privacy (security controls), the sync switch (GUARANTEES #5), the appearance default's settled mark
     *  and its migrated mark. */
    val perPhone: List<String> = listOf(
        "applock.timeout",
        "privacy.hide-when-captured",
        "sync/enabled",
        "appearance.modeSettled",
        "appearance.modeMigrated",
    )

    /** The vault's record of what this phone applied: each key's winning record id (`millis:counter:device`). */
    const val APPLIED_KEY = "settings/applied"

    /** Set when an arrival changed the icon wanted; cleared once the front moment applied it. */
    const val ICON_PENDING_KEY = "settings/icon-pending"

    /** Set once this phone caught up with the account at its first sign-in (one account per phone). */
    fun seededKey(uid: String): String = "settings/seeded-$uid"

    fun entry(key: String): Synced? = synced.firstOrNull { it.key == key }

    fun kindOf(key: String): Kind? = entry(key)?.kind

    /** Whether this build can apply [value] for [key]: a word this build knows, a step in range, a switch, a currency
     *  core knows. Anything else waits (not marked applied) for a build that knows it; an unknown theme is never read
     *  as Aurora (`AppTheme.parse` would), nor an unknown mode as Dark. */
    fun accepts(key: String, value: String): Boolean = when (key) {
        THEME -> AppTheme.entries.any { it.stored == value }
        MODE -> AppearanceMode.entries.any { it.stored == value }
        TEXT_SIZE -> value.toIntOrNull()?.let { it in TextSize.MIN..TextSize.MAX && it.toString() == value } ?: false
        LESS_MOTION, HAPTICS -> value == "true" || value == "false"
        ICON -> AppIconChoice.entries.any { it.stored == value }
        CURRENCY -> value in DefaultCurrency.all
        else -> false
    }

    fun encode(flag: Boolean): String = if (flag) "true" else "false"

    fun encode(step: Int): String = step.toString()

    /** The value this phone holds for [key], as the wire writes it; null when nothing is stored (never chosen). */
    fun local(key: String, prefs: (String) -> SharedPreferences): String? {
        val e = entry(key) ?: return null
        val p = prefs(e.file)
        if (!p.contains(e.pref)) return null
        return when (e.kind) {
            Kind.WORD -> p.getString(e.pref, null)
            Kind.STEP -> encode(p.getInt(e.pref, 0))
            Kind.FLAG -> encode(p.getBoolean(e.pref, false))
        }
    }

    /** Writes this phone's preferences only, as each reader reads them; never a record. A key outside [synced] writes
     *  nothing. The live state follows through [showOnPhone] (a tap's path too). */
    fun applyFromAccount(key: String, value: String, prefs: (String) -> SharedPreferences) {
        val e = entry(key) ?: return
        val edit = prefs(e.file).edit()
        when (e.kind) {
            Kind.WORD -> edit.putString(e.pref, value)
            Kind.STEP -> edit.putInt(e.pref, value.toIntOrNull() ?: return)
            Kind.FLAG -> edit.putBoolean(e.pref, value == "true")
        }
        edit.apply()
    }

    // ---- The write funnel ----

    /** Whether a change writes a record: this install is signed in to the vault's owner. No account, no record. */
    fun writesRecord(uid: String?, owner: String?): Boolean = CloudGate.owned(uid, owner) != null

    /** A record waits to go up since the last page close ([pageClosed] pushes it). Touched only on [work]. */
    private var unsent = false

    /** ALL of this object's vault and apply work, one at a time and in order (fix wave M4): the funnel's records (two
     *  quick taps write their records in tap order, so the latest tap is the latest record), an arrival ([received]
     *  runs on it), the launch, the front moment and the sign-out. So [applyHeld] never interleaves with itself, and a
     *  tap's record is ordered against what arrives. Android keeps vault work off the main thread. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serial: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val work = CoroutineScope(SupervisorJob() + serial)

    fun prefsOf(context: Context): (String) -> SharedPreferences =
        { file -> context.getSharedPreferences(file, Context.MODE_PRIVATE) }

    /** THE write of a synced setting, from Appearance's setters and [DefaultCurrency.set]: stores it on this phone and
     *  shows it at once (main thread), then writes the record on [work]. */
    fun set(context: Context, key: String, value: String) {
        if (kindOf(key) == null) return
        stored(key, value, prefsOf(context))
        showOnPhone(context, listOf(key))
        val app = context.applicationContext
        work.launch {
            val uid = runCatching { Account.uid() }.getOrNull()
            if (runCatching { record(key, value, LedgerStore(CloudSync.vaultPathOf(app)), uid) }.getOrDefault(false)) unsent = true
        }
    }

    /** The funnel with its seams, as iOS's `write`: stores [value] on this phone, then appends one setting record when
     *  this install is signed in to the vault's owner (whatever the sync switch says: the push decides what leaves).
     *  True when a record was written. A record core refuses is not written; the phone keeps the value. */
    fun write(key: String, value: String, store: LedgerStore, prefs: (String) -> SharedPreferences, uid: String?): Boolean {
        if (kindOf(key) == null) return false
        stored(key, value, prefs)
        return record(key, value, store, uid)
    }

    /** The funnel's local write: the value, and (M3) a mode tapped by hand is a choice from now on, so D-232's migrated
     *  mark goes. */
    private fun stored(key: String, value: String, prefs: (String) -> SharedPreferences) {
        applyFromAccount(key, value, prefs)
        if (key == MODE) prefs(Appearance.PREFS).edit().remove(AppearanceMode.MIGRATED_KEY).apply()
    }

    /** The funnel's record: only signed in to the vault's owner, and only a value this build would apply. */
    private fun record(key: String, value: String, store: LedgerStore, uid: String?): Boolean {
        if (!writesRecord(uid, Account.storedOwner(store)) || !accepts(key, value)) return false
        return runCatching { store.appendGroupOps(listOf(GroupOpIn.Setting(key, value)), Placing.NONE) }.isSuccess
    }

    /** A settings page closed: what was chosen there goes up in one push ([CloudRecords.outgoing] sends only the latest
     *  of each key). After the funnel's records ([work] keeps the order). */
    fun pageClosed(context: Context) {
        val app = context.applicationContext
        work.launch {
            if (!unsent) return@launch
            unsent = false
            CloudSync.ledgerChanged(CloudSync.vaultPathOf(app))
        }
    }

    /** The live state follows what was just stored, on the main thread: Appearance's (a theme or a mode tells the
     *  system the night mode, as a tap always has: D-232's re-creation on Android 12+), and the default currency. */
    private fun showOnPhone(context: Context, keys: List<String>) {
        if (keys.isEmpty()) return
        if (keys.any { entry(it)?.file == Appearance.PREFS }) Appearance.reload(context, keys)
        if (CURRENCY in keys) DefaultCurrency.load(context)
    }

    // ---- The apply path ----

    /** The pure decision: what to write on this phone, in the projection's (key) order, and the applied record per key
     *  after this pass. */
    data class Decision(val apply: List<Change>, val applied: Map<String, String>)

    data class Change(val key: String, val value: String)

    /** The pure decision, for each setting the account holds whose winning record this phone has not applied: a key
     *  outside [synced] is ignored (never applied, never marked); this phone's own record is marked (its value is
     *  already here); a value this build does not know is skipped and NOT marked, so a later build applies it; anything
     *  else is applied and marked. [device] is this phone's sync device id; [knows] is what this build can apply (a test
     *  stands in a later build with it). */
    fun toApply(
        settings: List<SettingOut>,
        applied: Map<String, String>,
        device: String,
        knows: (String, String) -> Boolean = ::accepts,
    ): Decision {
        val marks = applied.toMutableMap()
        val changes = mutableListOf<Change>()
        for (setting in settings) {
            if (kindOf(setting.key) == null || applied[setting.key] == setting.recordId) continue
            if (setting.deviceId == device) {
                marks[setting.key] = setting.recordId
                continue
            }
            if (!knows(setting.key, setting.value)) continue
            changes += Change(setting.key, setting.value)
            marks[setting.key] = setting.recordId
        }
        return Decision(changes, marks)
    }

    /** The first sign-in's gaps: each synced key the account does not hold that this phone has a value for (stored,
     *  so chosen), in [synced] order. The account's own values win; these fill it. A mode D-232's launch migration
     *  stored ([AppearanceMode.MIGRATED_KEY] still standing) was never chosen, so it fills nothing (M3). */
    fun gaps(held: Set<String>, prefs: (String) -> SharedPreferences): List<Change> = synced.mapNotNull { e ->
        if (e.key in held) return@mapNotNull null
        if (e.key == MODE && prefs(Appearance.PREFS).getBoolean(AppearanceMode.MIGRATED_KEY, false)) return@mapNotNull null
        val value = local(e.key, prefs) ?: return@mapNotNull null
        if (accepts(e.key, value)) Change(e.key, value) else null
    }

    /** Whether an arrival leaves the icon to change at the front moment: it changed the theme or the icon choice, and
     *  the icon wanted is not the one shown. [shown] null: the launcher's entry cannot be told (see [shownIcon]). */
    fun iconPending(changed: List<String>, choice: AppIconChoice, theme: AppTheme, shown: AppIcon?): Boolean {
        if (shown == null || (THEME !in changed && ICON !in changed)) return false
        return AppIconPlan.change(AppIconPlan.target(choice, theme), shown) != null
    }

    fun applied(store: LedgerStore): Map<String, String> {
        val data = store.rawKvGet(APPLIED_KEY)?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(data.decodeToString()).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    private fun saveApplied(store: LedgerStore, applied: Map<String, String>) {
        val json = JsonObject(applied.toSortedMap().mapValues { JsonPrimitive(it.value) })
        store.rawKvSet(APPLIED_KEY, json.toString().toByteArray(Charsets.UTF_8))
    }

    fun isSeeded(store: LedgerStore, uid: String): Boolean =
        runCatching { store.rawKvGet(seededKey(uid)) }.getOrNull()?.isNotEmpty() == true

    /** The account signed out ([Account.signOut], M5): this phone forgets that it caught up with the account, what it
     *  applied, and any icon change still pending, so a later sign-in runs the first sign-in's catch-up again (the
     *  account wins, the gaps are filled). The phone keeps its settings as they are. Never a record. */
    fun signedOut(store: LedgerStore, uid: String?) {
        val marks = mutableListOf(APPLIED_KEY, ICON_PENDING_KEY)
        for (id in setOfNotNull(uid, runCatching { Account.storedOwner(store) }.getOrNull())) marks += seededKey(id)
        for (key in marks) runCatching { store.rawKvSet(key, ByteArray(0)) }
    }

    /** [Account.signOut]'s call, on [work] (after anything this object was doing). [uid] is the account just left. */
    fun signedOut(uid: String?) {
        val app = VaultGate.appContextRef ?: return
        work.launch { runCatching { signedOut(LedgerStore(CloudSync.vaultPathOf(app)), uid) } }
    }

    fun iconIsPending(store: LedgerStore): Boolean =
        runCatching { store.rawKvGet(ICON_PENDING_KEY) }.getOrNull()?.isNotEmpty() == true

    private fun storedChoice(prefs: (String) -> SharedPreferences): AppIconChoice =
        AppIconChoice.parse(prefs(Appearance.PREFS).getString(AppIconChoice.STORAGE_KEY, null))

    private fun storedTheme(prefs: (String) -> SharedPreferences): AppTheme =
        AppTheme.parse(prefs(Appearance.PREFS).getString(Appearance.THEME, null))

    /** Applies what the account holds and this phone has not applied ([toApply]), then marks the icon pending when
     *  that changed the icon wanted. Never writes a record. Returns the keys written on this phone. */
    fun applyHeld(
        store: LedgerStore,
        prefs: (String) -> SharedPreferences,
        shown: AppIcon?,
        knows: (String, String) -> Boolean = ::accepts,
    ): List<String> {
        val before = applied(store)
        val decision = toApply(store.projectGroups().settings, before, LedgerStore.deviceId(store), knows)
        for (change in decision.apply) applyFromAccount(change.key, change.value, prefs)
        if (decision.applied != before) saveApplied(store, decision.applied)
        val changed = decision.apply.map { it.key }
        if (iconPending(changed, storedChoice(prefs), storedTheme(prefs), shown)) {
            store.rawKvSet(ICON_PENDING_KEY, "1".toByteArray(Charsets.UTF_8))
        }
        return changed
    }

    /** What a pass of [afterReceive] did: records written to fill the account (the first sign-in's gaps), and the keys
     *  written on this phone. */
    data class Outcome(val filled: Int = 0, val applied: List<String> = emptyList())

    /** The personal listener's snapshot is in the vault (`CloudSync.receive`). Signed in to the vault's owner only.
     *  Before this phone caught up with the account, the first SERVER snapshot is the first sign-in (a cache snapshot
     *  says nothing of the account: the cache is memory only): the account's values win, the gaps are filled with this
     *  phone's, and the seeded mark is set. After that, what arrived is applied. */
    fun afterReceive(
        store: LedgerStore,
        prefs: (String) -> SharedPreferences,
        uid: String?,
        fresh: Boolean,
        fromCache: Boolean,
        shown: AppIcon?,
    ): Outcome {
        val owned = CloudGate.owned(uid, Account.storedOwner(store)) ?: return Outcome()
        if (isSeeded(store, owned)) {
            if (!fresh) return Outcome()
            return Outcome(0, applyHeld(store, prefs, shown))
        }
        if (fromCache) return Outcome()
        val held = store.projectGroups().settings.map { it.key }.toSet()
        val fill = gaps(held, prefs)
        if (fill.isNotEmpty()) store.appendGroupOps(fill.map { GroupOpIn.Setting(it.key, it.value) }, Placing.NONE)
        val changed = applyHeld(store, prefs, shown)
        store.rawKvSet(seededKey(owned), "1".toByteArray(Charsets.UTF_8))
        return Outcome(fill.size, changed)
    }

    /** At launch, signed in to the vault's owner and caught up once: what the vault holds is applied (a value an older
     *  build skipped is applied now). Before the first catch-up the first server snapshot does it ([afterReceive]). */
    fun atLaunch(
        store: LedgerStore,
        prefs: (String) -> SharedPreferences,
        uid: String?,
        shown: AppIcon?,
        knows: (String, String) -> Boolean = ::accepts,
    ): List<String> {
        val owned = CloudGate.owned(uid, Account.storedOwner(store)) ?: return emptyList()
        if (!isSeeded(store, owned)) return emptyList()
        return applyHeld(store, prefs, shown, knows)
    }

    // ---- The front moment (the icon) ----

    /** The icon wanted only changes when SKIPTI is in front and unlocked, and only when an arrival left it pending. */
    fun frontApplies(pending: Boolean, locked: Boolean, active: Boolean): Boolean = pending && !locked && active

    /** The front moment with its seams: when [frontApplies], [change] runs with the stored choice and theme; the pending
     *  mark goes once it ran (false: SKIPTI was not in front after all, it waits for the next front moment). True when
     *  [change] ran. */
    fun frontMoment(
        store: LedgerStore,
        prefs: (String) -> SharedPreferences,
        locked: Boolean,
        active: Boolean,
        change: (AppIconChoice, AppTheme) -> Boolean,
    ): Boolean {
        if (!frontApplies(iconIsPending(store), locked, active)) return false
        val ran = change(storedChoice(prefs), storedTheme(prefs))
        if (ran) runCatching { store.rawKvSet(ICON_PENDING_KEY, ByteArray(0)) }
        return ran
    }

    /** A front moment happened; the personal listener's first server snapshot after it is one too (an arrival a second
     *  after SKIPTI came to the front). */
    @Volatile private var awaitingServer = false

    /** The process's launch front moment ran ([launched]): an activity made again (a rotation, D-232's night mode)
     *  never makes another. */
    private val launchedOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The icon the launcher shows: the one entry that is on, or null when it is not exactly one (a switch cut short;
     *  the next leave of Appearance sets it right, as before). */
    fun shownIcon(context: Context): AppIcon? = runCatching {
        val pm = context.packageManager
        AppIconPlan.enabled(
            AppIcon.entries.associate { it.alias to pm.getComponentEnabledSetting(android.content.ComponentName(context, it.alias)) },
        ).singleOrNull()
    }.getOrNull()

    /** SKIPTI came to the front after a real stop (MainActivity's onStart, never a re-creation), was unlocked, or
     *  launched: an icon change that arrived is made now, only if SKIPTI is in front and unlocked. Never anywhere else
     *  (AppearanceTest's icon guard). */
    fun frontMoment(context: Context) {
        awaitingServer = true
        val app = context.applicationContext
        work.launch { runCatching { changeIfPending(app, LedgerStore(CloudSync.vaultPathOf(app))) } }
    }

    /** The switcher's one `applyArrived` call (AppearanceTest names it). */
    private fun changeIfPending(app: Context, store: LedgerStore) {
        frontMoment(store, prefsOf(app), locked = AppLock.locked, active = !AppLock.away) { choice, theme ->
            AppIconSwitcher.applyArrived(app, choice, theme)
        }
    }

    /** `CloudSync.receive`'s personal branch, after the records are in: on [serial] (M4), so it never runs beside a
     *  tap's record, the launch or the front moment. */
    suspend fun received(store: LedgerStore, fresh: Boolean, fromCache: Boolean): Unit = withContext(serial) {
        val app = VaultGate.appContextRef ?: return@withContext
        val outcome = runCatching {
            afterReceive(store, prefsOf(app), runCatching { Account.uid() }.getOrNull(), fresh, fromCache, shownIcon(app))
        }.getOrNull()
        if (outcome != null && outcome.applied.isNotEmpty()) withContext(Dispatchers.Main) { showOnPhone(app, outcome.applied) }
        if ((outcome?.filled ?: 0) > 0) CloudSync.ledgerChanged(CloudSync.vaultPathOf(app))
        if (fromCache || !awaitingServer) return@withContext
        awaitingServer = false
        runCatching { changeIfPending(app, store) }
        Unit
    }

    /** The launch, once the vault opened and the lock decided (AppRoot's `goOn`): signed in, the account's settings the
     *  vault holds are applied (one an older build could not apply is applied now), then, once per process and with
     *  SKIPTI in front, the front moment. */
    fun launched(context: Context) {
        val app = context.applicationContext
        val first = launchedOnce.compareAndSet(false, true)
        work.launch {
            val changed = runCatching {
                atLaunch(LedgerStore(CloudSync.vaultPathOf(app)), prefsOf(app), runCatching { Account.uid() }.getOrNull(), shownIcon(app))
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                showOnPhone(app, changed)
                if (first && !AppLock.away) frontMoment(app)
            }
        }
    }
}
