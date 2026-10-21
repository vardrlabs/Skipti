package com.vardrlabs.skipti

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.math.roundToInt

/**
 * D-212 (E1, ADR-0041, the founder, 2026-10-14): the person's theme, the twin
 * of iOS `AppTheme`. Aurora is the default; Norse is always dark; Vardr Labs
 * is the company's own look. Stored in app-private SharedPreferences, not
 * the vault, like the mode.
 */
enum class AppTheme(val stored: String, val label: String, val cardRadius: Int) {
    AURORA("aurora", "Aurora", 20),
    NORSE("norse", "Norse", 3),
    VARDR("vardr", "Vardr Labs", 0),
    ;

    /** Norse has one set, dark. */
    val forcesDark: Boolean get() = this == NORSE

    /** Buttons never round more than the theme's cards. */
    val buttonRadius: Int get() = minOf(14, cardRadius)

    /** Frosted glass over the glow is Aurora's alone. */
    val hasGlass: Boolean get() = this == AURORA

    companion object {
        /** A stored value this build does not know (or none) is Aurora. */
        fun parse(raw: String?): AppTheme = entries.firstOrNull { it.stored == raw } ?: AURORA
    }
}

/** D-144's choice of light or dark. D-212: Night is retired for now
 *  (ADR-0036 amended); a stored `night` reads as Dark. */
enum class AppearanceMode(val stored: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        /** D-232 (decision 74): set once per install by [Appearance.settleDefault]; iOS's `appearance.modeSettled`. */
        const val SETTLED_KEY = "modeSettled"

        /** D-244 (fix wave, M3): set by [Appearance.settleDefault] only when it stored `system` for an install that ran
         *  before and never chose; cleared by the first real mode tap ([SettingsSync.set]). While it stands the stored
         *  mode is this phone's own default, not a choice, so the first sign-in never fills the account with it
         *  ([SettingsSync.gaps]). Per phone, never synced; iOS's `appearance.modeMigrated`. */
        const val MIGRATED_KEY = "modeMigrated"

        /** D-232 (decisions 73, 75; ADR-0042): nothing stored is a new install, which opens in Dark. A stored `night`
         *  (before D-212) reads as Dark, and so does anything else this build does not know. A stored choice wins. */
        fun parse(raw: String?): AppearanceMode {
            if (raw == null) return DARK
            if (raw == "night") return DARK
            return entries.firstOrNull { it.stored == raw } ?: DARK
        }

        /** D-232 (decision 74): what the launch stores before the mode is first read, the twin of iOS's
         *  `AppearanceMode.migrate`. An install that ran before (its vault file exists) and never chose keeps following
         *  the phone: `system`. A brand-new install stores nothing and reads Dark. A stored word is never touched. */
        fun migrate(stored: String?, vaultExists: Boolean): String? = if (stored == null && vaultExists) SYSTEM.stored else null
    }

    /** Whether the app draws dark, given the phone's own setting: Norse always does. */
    fun isDark(systemDark: Boolean, theme: AppTheme): Boolean = theme.forcesDark || when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

/** The five token sets (ADR-0041): a theme by its effective mode. */
enum class ThemeSet(val theme: AppTheme, val isDark: Boolean) {
    AURORA_LIGHT(AppTheme.AURORA, false),
    AURORA_DARK(AppTheme.AURORA, true),
    NORSE(AppTheme.NORSE, true),
    VARDR_LIGHT(AppTheme.VARDR, false),
    VARDR_DARK(AppTheme.VARDR, true),
    ;

    companion object {
        fun resolve(theme: AppTheme, dark: Boolean): ThemeSet = when (theme) {
            AppTheme.AURORA -> if (dark) AURORA_DARK else AURORA_LIGHT
            AppTheme.NORSE -> NORSE
            AppTheme.VARDR -> if (dark) VARDR_DARK else VARDR_LIGHT
        }
    }
}

/** D-212: SKIPTI's own text size, -2 to +3 steps from the phone's. A step
 *  moves the body size as iOS's Dynamic Type step does from Large (body
 *  15/16/17/19/21/23 pt), so the twins agree at the phone's default. */
object TextSize {
    const val MIN = -2
    const val MAX = 3
    private val FACTORS = mapOf(-2 to 0.88f, -1 to 0.94f, 0 to 1.0f, 1 to 1.12f, 2 to 1.24f, 3 to 1.35f)

    /** A stored step outside the range (or none) is the phone's own size. */
    fun parse(raw: Int?): Int = if (raw != null && raw in MIN..MAX) raw else 0

    fun factor(step: Int): Float = FACTORS[parse(step)] ?: 1.0f

    fun fontScale(phone: Float, step: Int): Float = (phone * factor(step)).coerceIn(0.75f, 2.0f)

    /** D-221 fix wave (S11): the one stacking rule, iOS's accessibility text sizes on Android (the house mapping
     *  every Android screen uses: its text steps of 1.2 and above sit in iOS's accessibility band). A card's button drops
     *  under its words here. */
    const val STACKS_AT = 1.15f

    fun stacks(fontScale: Float): Boolean = fontScale >= STACKS_AT

    fun accessibilityValue(step: Int): String = when {
        step == 0 -> "Your phone's size"
        step == -1 -> "1 step smaller"
        step == 1 -> "1 step larger"
        step < 0 -> "${-step} steps smaller"
        else -> "$step steps larger"
    }
}

/** D-216 (Design 3): the person's app icon choice, the twin of iOS `AppIconChoice`, stored under [STORAGE_KEY] with
 *  the other appearance choices. Match theme (the default) follows the theme; the other three hold one icon whatever
 *  the theme. The stored words are permanent from D-216. */
enum class AppIconChoice(val stored: String) {
    MATCH("match"),
    AURORA("aurora"),
    NORSE("norse"),
    VARDR("vardr"),
    ;

    companion object {
        const val STORAGE_KEY = "icon"

        /** A stored value this build does not know (or none) is Match theme. Case matters: the words are stored lower case. */
        fun parse(raw: String?): AppIconChoice = entries.firstOrNull { it.stored == raw } ?: MATCH
    }
}

/** D-216 (Design 1 and 2): the three icons, one per theme, the twin of iOS `AppIcon`. Each is a launcher
 *  `<activity-alias>` of MainActivity; the alias names are PERMANENT (a launcher stores the component name). Aurora's
 *  alias is the one the manifest enables. */
enum class AppIcon(val stored: String, val alias: String, val theme: AppTheme) {
    AURORA("aurora", "com.vardrlabs.skipti.IconAurora", AppTheme.AURORA),
    NORSE("norse", "com.vardrlabs.skipti.IconNorse", AppTheme.NORSE),
    VARDR("vardr", "com.vardrlabs.skipti.IconVardr", AppTheme.VARDR),
    ;

    /** The row reads the theme's label. */
    val label: String get() = theme.label

    /** Whether the manifest enables this alias (what COMPONENT_ENABLED_STATE_DEFAULT means for it). */
    val enabledByDefault: Boolean get() = this == AURORA

    companion object {
        /** The icon a theme gives. */
        fun of(theme: AppTheme): AppIcon = entries.first { it.theme == theme }
    }
}

/** D-216 (Design 3 to 5): the pure decisions behind the App icon picker and the switcher, the twin of iOS
 *  `AppIconPlan` (one golden table pins both); [enabled], [needsWrite], [settings] and [tapActs] are Android's alone. */
object AppIconPlan {
    /** D-234 (A2, decision 80): how long the App icon rows take to open or close, in ms: the switch's own travel when art
     *  may move, 0 (a snap) when Less motion or the phone's setting holds it still (D-239: never battery saver). The twin of iOS's
     *  `AppIconPlan.expandSeconds`. */
    fun expandMillis(moves: Boolean): Int = if (moves) (ThemeArt.m("switchTravel") * 1000).roundToInt() else 0

    /** The icon wanted: Match theme gives the theme's icon, any other choice gives itself. */
    fun target(choice: AppIconChoice, theme: AppTheme): AppIcon = when (choice) {
        AppIconChoice.MATCH -> AppIcon.of(theme)
        AppIconChoice.AURORA -> AppIcon.AURORA
        AppIconChoice.NORSE -> AppIcon.NORSE
        AppIconChoice.VARDR -> AppIcon.VARDR
    }

    /** The icon to switch to when the person leaves Appearance, or null when the launcher already shows it. */
    fun change(target: AppIcon, current: AppIcon): AppIcon? = if (target == current) null else target

    /** The row that carries the check: the icon wanted (with Match theme on, the theme's row). */
    fun checked(choice: AppIconChoice, theme: AppTheme): AppIcon = target(choice, theme)

    /** What a tap on an icon row stores: that icon, so Match theme turns off. */
    fun tap(icon: AppIcon): AppIconChoice = when (icon) {
        AppIcon.AURORA -> AppIconChoice.AURORA
        AppIcon.NORSE -> AppIconChoice.NORSE
        AppIcon.VARDR -> AppIconChoice.VARDR
    }

    /** What the Match theme switch stores: on, Match theme; off, the theme's icon as an explicit choice (nothing
     *  visible changes). */
    fun matchSwitched(on: Boolean, theme: AppTheme): AppIconChoice = if (on) AppIconChoice.MATCH else tap(AppIcon.of(theme))

    /** The launcher entries that are on, in AppIcon order, from each alias's `getComponentEnabledSetting` (keyed by
     *  [AppIcon.alias]; a missing key is DEFAULT). DEFAULT resolves to the manifest's ([AppIcon.enabledByDefault]),
     *  ENABLED is on, every DISABLED state is off. Two can be on (the moment between the switcher's enable and its
     *  disables, or a run cut short there below API 33) and none can (nothing this app writes leaves that). */
    fun enabled(states: Map<String, Int>): List<AppIcon> = AppIcon.entries.filter { icon ->
        when (states[icon.alias] ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon.enabledByDefault
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }
    }

    /** Whether the switcher writes [settings]: unless the target ALONE is on. It decides on every entry that is on, not
     *  on the first: below API 33 the writes go one by one, and a run cut short after the enable (the process killed, a
     *  write refused) leaves the target and another on; a rule reading only the first would leave both for good (the
     *  whole-branch review's I1). None on also writes, so the phone never stays without a launcher entry. */
    fun needsWrite(target: AppIcon, states: Map<String, Int>): Boolean = enabled(states) != listOf(target)

    /** Whether a tap on [icon]'s row changes what is stored (Android's alone, for TalkBack: the look's F3). With Match
     *  theme on, a tap on the checked row still acts (it turns Match theme off); with that icon chosen, it does not. */
    fun tapActs(icon: AppIcon, choice: AppIconChoice): Boolean = tap(icon) != choice

    /** What the switcher writes, in this order: the target ENABLED first (never a moment with no launcher entry), then
     *  the other two DISABLED, in AppIcon order. */
    fun settings(target: AppIcon): List<Pair<String, Boolean>> =
        listOf(target.alias to true) + AppIcon.entries.filter { it != target }.map { it.alias to false }
}

/** D-216 (Design 4): switches the launcher entry to the icon the person's choice wants, the twin of iOS's
 *  `AppIconSwitcher`. Called from ONE place, the Appearance route's onDispose (skipped while the activity is only being
 *  made again), so the launcher refreshes once, as the answer to leaving the page; never in a lifecycle callback
 *  (AppearanceTest guards both). D-244: and an arrival from the account, at the front moment only ([applyArrived]).
 *  It reads every alias's enabled setting and writes [AppIconPlan.settings]
 *  unless the target alone is on ([AppIconPlan.needsWrite]): the target enabled first, then the other two
 *  disabled, every write with DONT_KILL_APP; on API 33+ in one call, below it one by one in that order, so there is never
 *  a moment with no launcher entry. A failure changes nothing and is tried again at the next leave. */
object AppIconSwitcher {
    fun applyIfNeeded(context: Context) = switchTo(context, AppIconPlan.target(Appearance.iconChoice, Appearance.theme))

    /** D-244 (ADR-0043; ADR-0041's App icon section amended): a theme or icon choice that arrived from the account is
     *  never applied at arrival. It waits for the front moment (`SettingsSync.frontMoment`: SKIPTI in front after a real
     *  stop, a launch or an unlock, and unlocked, with the arrival's pending mark), the one other caller; never in the
     *  background, never after a re-creation. True when it ran (the mark may go); false when SKIPTI is not in front, so
     *  the next front moment tries. The same switch as a leave of Appearance: target first, DONT_KILL_APP. */
    fun applyArrived(context: Context, choice: AppIconChoice, theme: AppTheme): Boolean {
        val active = !AppLock.away
        if (active) switchTo(context, AppIconPlan.target(choice, theme))
        return active
    }

    private fun switchTo(context: Context, target: AppIcon) {
        val pm = context.packageManager
        try {
            val states = AppIcon.entries.associate { it.alias to pm.getComponentEnabledSetting(ComponentName(context, it.alias)) }
            if (!AppIconPlan.needsWrite(target, states)) return
            val settings = AppIconPlan.settings(target)
            if (Build.VERSION.SDK_INT >= 33) {
                pm.setComponentEnabledSettings(
                    settings.map { (alias, on) ->
                        PackageManager.ComponentEnabledSetting(ComponentName(context, alias), state(on), PackageManager.DONT_KILL_APP)
                    },
                )
            } else {
                for ((alias, on) in settings) {
                    pm.setComponentEnabledSetting(ComponentName(context, alias), state(on), PackageManager.DONT_KILL_APP)
                }
            }
        } catch (_: RuntimeException) {
            // An alias the package manager does not know, or a write it refuses: the launcher keeps what it shows.
        }
    }

    private fun state(on: Boolean): Int =
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}

/** D-216 (Design 5): the picker's words, the twin of iOS `AppIconCopy` (AppearanceTest compares them with
 *  Appearance.swift), the same on both phones. Decision 33: Launcher3 keeps the home-screen icon through the switch
 *  (redrawn as the new icon), so Android has no second caption sentence. */
object AppIconCopy {
    const val TITLE = "App icon"
    const val MATCH = "Match theme"
    const val CAPTION = "Your icon changes when you leave this page."

    /** D-234 (A2): the header row's value, the choice stored: Match theme, or the icon's theme label. */
    fun choice(choice: AppIconChoice): String = when (choice) {
        AppIconChoice.MATCH -> MATCH
        AppIconChoice.AURORA -> AppIcon.AURORA.label
        AppIconChoice.NORSE -> AppIcon.NORSE.label
        AppIconChoice.VARDR -> AppIcon.VARDR.label
    }

    /** Each icon row's spoken name (the preview tile itself is decorative). */
    fun spoken(icon: AppIcon): String = when (icon) {
        AppIcon.AURORA -> "Aurora icon"
        AppIcon.NORSE -> "Norse icon"
        AppIcon.VARDR -> "Vardr Labs icon"
    }
}

/**
 * The live settings. Each is Compose snapshot state, so every `Tok` and
 * `Type` read during composition recomposes when one changes: the themes need
 * no second code path through the screens.
 */
object Appearance {
    // D-244 (ADR-0043): internal, so SettingsSync's table names where each synced choice is kept.
    internal const val PREFS = "skipti-appearance"
    internal const val MODE = "mode"
    internal const val THEME = "theme"
    internal const val TEXT = "textSize"

    /** D-232: the vault the launch opens (AppRoot's `File(context.filesDir, "ledger-vault.db")`); only its existence is
     *  asked. AppearanceTest holds the two names together. */
    private const val VAULT_FILE = "ledger-vault.db"

    /** D-232 (decisions 73, 74; ADR-0042): a new install opens in Dark; an install that ran before and never chose keeps
     *  following the phone. Called ONCE, as the first statement after super.onCreate in MainActivity (AppearanceTest
     *  holds it there): before Firebase, the `--emulator-as` sign-in (which opens the vault on an IO thread) and
     *  [load]. Runs once per install: the settled mark stops it, so the second launch of a new install, or an activity
     *  made again, changes nothing. */
    fun settleDefault(context: Context) = settleDefault(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        vaultExists = File(context.filesDir, VAULT_FILE).exists(),
    )

    /** The seam the JVM test drives: stores what [AppearanceMode.migrate] asks for (`system` for an install that ran
     *  before and never chose, with D-244's migrated mark: not a choice), then the mark, in one edit. A brand-new install stores only the mark. The edit is
     *  written with `commit()`, synchronously, not `apply()`: a process killed before an asynchronous write landed
     *  would lose the mark, and the next launch (its vault now present, nothing stored) would store `system` and turn
     *  a new install from Dark to System. One tiny write, once per install. */
    internal fun settleDefault(prefs: SharedPreferences, vaultExists: Boolean) {
        if (prefs.getBoolean(AppearanceMode.SETTLED_KEY, false)) return
        val edit = prefs.edit()
        AppearanceMode.migrate(prefs.getString(MODE, null), vaultExists)?.let { edit.putString(MODE, it).putBoolean(AppearanceMode.MIGRATED_KEY, true) }
        edit.putBoolean(AppearanceMode.SETTLED_KEY, true)
        edit.commit()
    }

    /** D-232 (decision 78): the night mode this install last told the system. `UiModeManager` has
     *  `setApplicationNightMode` (API 31) but no public getter, so the app keeps its own record. */
    private const val NIGHT_TOLD = "nightModeTold"

    /** D-232 (decision 78; ADR-0042): the night mode the app tells the system on Android 12 and later, so the window
     *  theme, the text-selection toolbar and the NEXT launch's splash follow the app's choice rather than the phone.
     *  A theme that is always dark (Norse) and Dark: YES; Light: NO; System: AUTO, which clears the app's override, so
     *  the phone's own setting reaches the app again and `isSystemInDarkTheme()` reports the phone. */
    fun nightModeFor(mode: AppearanceMode, theme: AppTheme): Int = when {
        theme.forcesDark -> UiModeManager.MODE_NIGHT_YES
        mode == AppearanceMode.DARK -> UiModeManager.MODE_NIGHT_YES
        mode == AppearanceMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
        else -> UiModeManager.MODE_NIGHT_AUTO
    }

    /** D-232 (decision 78): the value to tell the system, or null when this install already told it that one. Telling
     *  changes the app's configuration, which makes the activity again (no `configChanges` is declared); the activity
     *  made again finds [told] equal to [wanted] and tells nothing, so a launch never loops. */
    fun nightModeToTell(wanted: Int, told: Int?): Int? = if (wanted == told) null else wanted

    /** D-232 (decision 78): tells the system the app's night mode, on Android 12 (API 31) and later only, and only
     *  when it differs from what this install last told it. Called at launch right after [settleDefault], and from
     *  [setMode] and [setTheme]. Below API 31 nothing changes: the window theme follows the phone (decision 77). The
     *  record is written after the call, in memory at once (`apply()`), so the activity made again reads it; a record
     *  lost to a killed process only means the same value is told once more, which changes nothing. */
    fun tellNightMode(context: Context) {
        if (Build.VERSION.SDK_INT >= 31) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val wanted = nightModeFor(AppearanceMode.parse(prefs.getString(MODE, null)), AppTheme.parse(prefs.getString(THEME, null)))
            val told = if (prefs.contains(NIGHT_TOLD)) prefs.getInt(NIGHT_TOLD, UiModeManager.MODE_NIGHT_AUTO) else null
            val tell = nightModeToTell(wanted, told) ?: return
            val uiModes = context.getSystemService(UiModeManager::class.java) ?: return
            uiModes.setApplicationNightMode(tell)
            prefs.edit().putInt(NIGHT_TOLD, tell).apply()
        }
    }

    var theme by mutableStateOf(AppTheme.AURORA)
        private set
    var mode by mutableStateOf(AppearanceMode.SYSTEM)
        private set
    var textStep by mutableIntStateOf(0)
        private set

    /** D-224 (Design 2): the Haptics switch, on unless the person turned it off (stored under
     *  [HapticsSetting.STORAGE_KEY] with the other appearance choices). */
    var haptics by mutableStateOf(true)
        private set

    /** D-216 (Design 3): the App icon choice, Match theme unless the person chose (stored under
     *  [AppIconChoice.STORAGE_KEY]). The switcher reads it when the person leaves Appearance. */
    var iconChoice by mutableStateOf(AppIconChoice.MATCH)
        private set

    /** D-231 (decisions 68, 70): the Less motion switch as the person sees it: stored once they set it ([setLessMotion]);
     *  until then on by itself on a low-memory phone (read once in [load], never stored). [rememberMotionState] joins it
     *  with the phone's "remove animations". */
    var lessMotion by mutableStateOf(false)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        theme = AppTheme.parse(prefs.getString(THEME, null))
        mode = AppearanceMode.parse(prefs.getString(MODE, null))
        textStep = TextSize.parse(if (prefs.contains(TEXT)) prefs.getInt(TEXT, 0) else null)
        haptics = HapticsSetting.parse(
            if (prefs.contains(HapticsSetting.STORAGE_KEY)) prefs.getBoolean(HapticsSetting.STORAGE_KEY, true) else null,
        )
        iconChoice = AppIconChoice.parse(prefs.getString(AppIconChoice.STORAGE_KEY, null))
        lessMotion = MotionSetting.parse(
            if (prefs.contains(MotionSetting.STORAGE_KEY)) prefs.getBoolean(MotionSetting.STORAGE_KEY, false) else null,
            lowRamDevice(context),
        )
    }

    /** D-231 (decision 70): the platform's low-RAM flag and the phone's total memory, read once per launch. */
    private fun lowRamDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return MotionSetting.lowRam(info.totalMem, am.isLowRamDevice)
    }

    // D-244 (ADR-0043): every setter below writes through SettingsSync.set, the one write of a synced choice: it stores
    // the choice as before, shows it ([reload]: a theme or a mode tells the system the night mode, as before), and with
    // an account appends the setting record that carries it to the account's other phones.

    fun setTheme(context: Context, newTheme: AppTheme) {
        SettingsSync.set(context, SettingsSync.THEME, newTheme.stored)
    }

    fun setMode(context: Context, newMode: AppearanceMode) {
        SettingsSync.set(context, SettingsSync.MODE, newMode.stored)
    }

    fun setTextStep(context: Context, step: Int) {
        SettingsSync.set(context, SettingsSync.TEXT_SIZE, SettingsSync.encode(TextSize.parse(step)))
    }

    /** D-224: turns the Haptics switch on or off and stores it. */
    fun setHaptics(context: Context, on: Boolean) {
        SettingsSync.set(context, SettingsSync.HAPTICS, SettingsSync.encode(on))
    }

    /** D-216: stores the App icon choice (the icon itself changes only when the person leaves Appearance, or, D-244,
     *  at the front moment after the choice arrived from the account). */
    fun setIconChoice(context: Context, choice: AppIconChoice) {
        SettingsSync.set(context, SettingsSync.ICON, choice.stored)
    }

    /** D-231: stores the Less motion switch; from then on the stored value wins over the low-memory default. */
    fun setLessMotion(context: Context, on: Boolean) {
        SettingsSync.set(context, SettingsSync.LESS_MOTION, SettingsSync.encode(on))
    }

    /** D-244 (ADR-0043): the live state follows what SettingsSync just stored, a tap here or an arrival from the
     *  account, by the same reads as [load]; a theme or a mode also tells the system the night mode, as a tap always
     *  has (D-232: on Android 12+ a change makes the activity again). [keys] are the wire keys written. Main thread. */
    fun reload(context: Context, keys: List<String>) {
        load(context)
        if (SettingsSync.THEME in keys || SettingsSync.MODE in keys) tellNightMode(context)
    }

    /** Test seam: the JVM tests set the state without a Context. */
    internal fun setForTest(
        theme: AppTheme = AppTheme.AURORA,
        mode: AppearanceMode = AppearanceMode.SYSTEM,
        textStep: Int = 0,
        haptics: Boolean = true,
        iconChoice: AppIconChoice = AppIconChoice.MATCH,
        lessMotion: Boolean = false,
    ) {
        this.theme = theme
        this.mode = mode
        this.textStep = TextSize.parse(textStep)
        this.haptics = haptics
        this.iconChoice = iconChoice
        this.lessMotion = lessMotion
    }

    /**
     * The phone's configuration with its night bit replaced by the chosen
     * mode (Norse: always night). Provided as `LocalConfiguration` at the root,
     * so every screen's `isSystemInDarkTheme()` follows the setting.
     */
    fun configurationFor(base: Configuration, mode: AppearanceMode, theme: AppTheme): Configuration {
        if (mode == AppearanceMode.SYSTEM && !theme.forcesDark) return base
        val systemDark = (base.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val night = if (mode.isDark(systemDark, theme)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return Configuration(base).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        }
    }

    /** The phone's density with SKIPTI's text-size step applied to its font
     *  scale. Provided as `LocalDensity` at the root, so every `sp` and every
     *  `fontScale >= 1.15f` layout switch follows the setting. */
    fun density(base: Density, step: Int): Density =
        Density(base.density, TextSize.fontScale(base.fontScale, step))
}

/** D-212: SKIPTI's text size (and the chosen night bit) inside a window of its own: a Dialog, sheet or menu resets
 *  LocalDensity and LocalConfiguration to the phone's, so the root override stops at its edge. The step is applied
 *  to the PHONE's font scale (read from the resources, which no provider overrides), so a nested use can never
 *  apply it twice; the night bit is set from the stored mode, so it is the same however often it runs. */
@Composable
fun SkiptiWindowLocals(content: @Composable () -> Unit) {
    val configuration = Appearance.configurationFor(LocalConfiguration.current, Appearance.mode, Appearance.theme)
    val phone = Density(LocalDensity.current.density, LocalContext.current.resources.configuration.fontScale)
    val density = Appearance.density(phone, Appearance.textStep)
    CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides density, content = content)
}

/** D-212: the one AlertDialog in the app. Its window resets the text size, so every slot draws inside
 *  [SkiptiWindowLocals]; WindowLocalsTest refuses a direct Material alert dialog anywhere else. */
@Composable
fun SkiptiAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { SkiptiWindowLocals(confirmButton) },
        dismissButton = dismissButton?.let { slot -> { SkiptiWindowLocals(slot) } },
        title = title?.let { slot -> { SkiptiWindowLocals(slot) } },
        text = text?.let { slot -> { SkiptiWindowLocals(slot) } },
    )
}
