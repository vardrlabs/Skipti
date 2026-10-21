package com.vardrlabs.skipti

import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// D-245 (U9; decisions 159, 160, 162, 171, 176 to 181, 192, 197 to 199): the first-run welcome. Five screens, every one
// but the last skippable, shown once on a new install (`WelcomeGate`) and again on request from About ("Show the
// welcome again"). No screen names an account, sign-in, sync or the cloud, and none routes to Join a group
// (GUARANTEES #5; a test scans every word below). The twin of iOS's Welcome.swift; WelcomeTest compares
// [WelcomeCopy] with that file's table.

/** Every word the welcome and the picture confirm show, in one table (WelcomeTest compares it with iOS's). */
object WelcomeCopy {
    const val NEXT = "Next"
    const val SKIP = "Skip"
    /** Decision 198: W2 to W4's back control, spoken (the glyph is the bars' back chevron). */
    const val BACK = "Back"
    fun step(n: Int) = "Step $n of 4"

    // W1
    const val WELCOME_TITLE = "Welcome to SKIPTI"
    /** The title as drawn: a small lead over the wordmark (`WELCOME_LEAD + " " + WORDMARK == WELCOME_TITLE`). */
    const val WELCOME_LEAD = "Welcome to"
    const val WORDMARK = "SKIPTI"
    const val WELCOME_LINE = "Split costs. See who owes whom."
    val PLACES = listOf("Balance", "Groups", "Add", "Settle up", "You")
    const val WELCOME_FOOT = "Everything stays on this phone."

    // W2
    const val LOOK_TITLE = "Pick a look"
    const val LOOK_LINE = "Change it any time on You, under Appearance."
    const val NORSE_DARK = "Norse is always dark."

    // W3
    const val NAME_TITLE = "Your name"
    const val NAME_LINE = "Shown on Balance and in your groups."
    const val NAME_PLACEHOLDER = "Your name"
    fun nameCaption(name: String) = "You will see yourself as \"$name (You)\"."
    const val SAVE_NAME = "Save name"

    // W4
    const val PICTURE_TITLE = "Your picture"
    const val PICTURE_LINE = "A photo or an emoji beside your name."
    const val MORE = "More"
    const val PHOTO = "Choose a photo"
    const val USE = "Use this picture"
    const val PICTURE_FOOT = "Your name and picture stay on this phone."

    // W5
    fun doneTitle(name: String) = "Nice to meet you, $name"
    const val DONE_TITLE_NO_NAME = "You're set"
    const val DONE_LINE = "Start a group for a trip, a flat or a dinner."
    const val START_GROUP = "Start a group"
    const val LOOK_AROUND = "Look around first"

    // You > Profile (U17)
    const val SHEET_TITLE = "Your picture"
    const val GROUPS_PICTURE = "Your groups will see a small copy of this picture."
    const val GROUPS_NAME = "Your groups see this name."
    const val NOT_NOW = "Not now"
    const val ERR_EMPTY = "Add a name first."
    const val ERR_LONG = "Names can be up to 60 characters."
    const val ERR_CHAR = "That name has a character SKIPTI can't use."
    const val PHOTO_UNREADABLE = "That photo could not be read."

    // About
    const val REPLAY = "Show the welcome again"

    /** A name or a picture the vault could not store (not a rule: the rules are the three words above). Fixed words,
     *  never the raw error (fix wave M4): New group's opening words. */
    const val NAME_NOT_SAVED = "Could not save your name."
    const val PICTURE_NOT_SAVED = "Could not save your picture."

    /** Every fixed word, by iOS's name for it (WelcomeTest reads iOS's `static let` lines and compares). */
    val byIosName: Map<String, String> = mapOf(
        "next" to NEXT, "skip" to SKIP, "back" to BACK, "welcomeTitle" to WELCOME_TITLE, "welcomeLead" to WELCOME_LEAD,
        "wordmark" to WORDMARK, "welcomeLine" to WELCOME_LINE, "welcomeFoot" to WELCOME_FOOT, "lookTitle" to LOOK_TITLE,
        "lookLine" to LOOK_LINE, "norseDark" to NORSE_DARK, "nameTitle" to NAME_TITLE, "nameLine" to NAME_LINE,
        "namePlaceholder" to NAME_PLACEHOLDER, "saveName" to SAVE_NAME, "pictureTitle" to PICTURE_TITLE,
        "pictureLine" to PICTURE_LINE, "more" to MORE, "photo" to PHOTO, "use" to USE, "pictureFoot" to PICTURE_FOOT,
        "doneTitleNoName" to DONE_TITLE_NO_NAME, "doneLine" to DONE_LINE, "startGroup" to START_GROUP,
        "lookAround" to LOOK_AROUND, "sheetTitle" to SHEET_TITLE, "groupsPicture" to GROUPS_PICTURE,
        "groupsName" to GROUPS_NAME, "notNow" to NOT_NOW, "errEmpty" to ERR_EMPTY, "errLong" to ERR_LONG,
        "errChar" to ERR_CHAR, "photoUnreadable" to PHOTO_UNREADABLE, "replay" to REPLAY,
        "nameNotSaved" to NAME_NOT_SAVED, "pictureNotSaved" to PICTURE_NOT_SAVED,
    )
}

/** GUARANTEES #5 (plan 1.6): the words the welcome never shows. Kept out of [WelcomeCopy], so a scan of that whole table
 *  (fix wave M6: the table as written, not a list kept beside it) reads only the welcome's words. iOS's `WelcomeG5`. */
object WelcomeG5 {
    /** Case-insensitive. */
    val FORBIDDEN = listOf("account", "sign", "sync", "cloud", "google", "apple", "join")

    /** The forbidden words a list of strings contains (empty when clean). */
    fun hits(strings: List<String>): List<String> = strings.filter { s -> FORBIDDEN.any { s.lowercase().contains(it) } }
}

enum class WelcomeStep { WELCOME, LOOK, NAME, PICTURE, DONE }

/** First run clears the flag when it closes; a replay from About never touches it. */
enum class WelcomeMode { FIRST_RUN, REPLAY }

/** How the welcome closed: Skip, "Look around first", or "Start a group" (which then opens New group). */
enum class WelcomeExit { SKIPPED, LOOK_AROUND, START_GROUP }

/** The one action at the bottom of a step. */
enum class WelcomeAction { NEXT, SAVE_NAME, USE_PICTURE, FINISH }

/** D-245: the flow, pure. W1 to W4 carry Skip; the bottom carries one action at a time (Save name while the field
 *  holds an unsaved name, Use this picture while one is staged, else Next); W5 finishes. Decision 200: W3 hides Next
 *  while the field holds an unsaved name (one action at a time, as mock v2 drew); Skip and Back remain. The twin of
 *  iOS's. */
object WelcomeFlow {
    fun next(after: WelcomeStep): WelcomeStep? = WelcomeStep.entries.getOrNull(after.ordinal + 1)

    fun skippable(step: WelcomeStep) = step != WelcomeStep.DONE

    /** Decision 198: W2 to W4 go back a step (nothing saved is undone); W1 and Done have no back. */
    fun previous(before: WelcomeStep): WelcomeStep? =
        if (before == WelcomeStep.WELCOME || before == WelcomeStep.DONE) null else WelcomeStep.entries[before.ordinal - 1]

    /** Decision 198: the progress shows what is DONE: going back never takes a completed step away. */
    fun shown(completed: Int, before: Int) = maxOf(completed, before)

    /** 1 to 4 on W1 to W4; none on Done. */
    fun number(step: WelcomeStep): Int? = if (step == WelcomeStep.DONE) null else step.ordinal + 1

    /** What TalkBack says for the progress ("Step 2 of 4"). */
    fun spoken(step: WelcomeStep): String? = number(step)?.let(WelcomeCopy::step)

    /** Each step's title (W5's carries the saved name). */
    fun title(step: WelcomeStep, name: String?): String = when (step) {
        WelcomeStep.WELCOME -> WelcomeCopy.WELCOME_TITLE
        WelcomeStep.LOOK -> WelcomeCopy.LOOK_TITLE
        WelcomeStep.NAME -> WelcomeCopy.NAME_TITLE
        WelcomeStep.PICTURE -> WelcomeCopy.PICTURE_TITLE
        WelcomeStep.DONE -> doneTitle(name)
    }

    /** Fix wave M8: what TalkBack says when the page changes, and the progress's label: "Step 2 of 4, Pick a look";
     *  W5 is its title alone. */
    fun announcement(step: WelcomeStep, name: String?): String =
        spoken(step)?.let { "$it, ${title(step, name)}" } ?: title(step, name)

    /** Fix wave I2: W1's and W4's quiet foot says the data stays on this phone, so it shows only WITHOUT an account
     *  (Profile's `ProfileCopy.footnotes(signedIn)` rule); a signed-in replay shows none. */
    fun foot(step: WelcomeStep, signedIn: Boolean): String? = if (signedIn) null else when (step) {
        WelcomeStep.WELCOME -> WelcomeCopy.WELCOME_FOOT
        WelcomeStep.PICTURE -> WelcomeCopy.PICTURE_FOOT
        else -> null
    }

    /** Fix wave I3: W1's five places, as rows of indices: three then two, or one per row at the large text sizes
     *  ([TextSize.stacks]; iOS's accessibility sizes), where a label is wider than a column. */
    fun placeRows(stacked: Boolean): List<List<Int>> = if (stacked) (0 until 5).map { listOf(it) } else listOf(listOf(0, 1, 2), listOf(3, 4))

    fun doneTitle(name: String?): String {
        val trimmed = name?.let(NameRule::trimmed)
        return if (trimmed.isNullOrEmpty()) WelcomeCopy.DONE_TITLE_NO_NAME else WelcomeCopy.doneTitle(trimmed)
    }

    fun action(step: WelcomeStep, nameDirty: Boolean, staged: Boolean): WelcomeAction = when (step) {
        WelcomeStep.WELCOME, WelcomeStep.LOOK -> WelcomeAction.NEXT
        WelcomeStep.NAME -> if (nameDirty) WelcomeAction.SAVE_NAME else WelcomeAction.NEXT
        WelcomeStep.PICTURE -> if (staged) WelcomeAction.USE_PICTURE else WelcomeAction.NEXT
        WelcomeStep.DONE -> WelcomeAction.FINISH
    }

    /** Decision 192: the steps COMPLETED so far: each step passed, and the one on screen once its own save is done. */
    fun completed(step: WelcomeStep, nameSaved: Boolean = false, pictureSaved: Boolean = false): Int = when (step) {
        WelcomeStep.WELCOME -> 0
        WelcomeStep.LOOK -> 1
        WelcomeStep.NAME -> if (nameSaved) 3 else 2
        WelcomeStep.PICTURE -> if (pictureSaved) 4 else 3
        WelcomeStep.DONE -> 4
    }

    fun clearsFlag(mode: WelcomeMode) = mode == WelcomeMode.FIRST_RUN
}

/** D-245 (decision 177): when the welcome shows. A new install's first launch writes `welcome/pending` (in the launch's
 *  own mark, BEFORE D-175's `app/launched`, so a launch killed between the two still welcomes), and the welcome covers
 *  the app while it is set. Closing first run clears it; a replay never touches it; an install that launched before
 *  this build has no flag and never sees it. The twin of iOS's `WelcomeGate`. */
object WelcomeGate {
    const val PENDING_KEY = "welcome/pending"
    private val SET = byteArrayOf(1)

    fun shouldShow(launchedBefore: Boolean, pending: Boolean) = !launchedBefore || pending

    fun mark(store: LedgerStore) = store.rawKvSet(PENDING_KEY, SET)

    /** Only the set value reads as set (iOS's `kvFlag`). */
    fun pending(store: LedgerStore): Boolean = store.rawKvGet(PENDING_KEY)?.contentEquals(SET) == true

    /** The vault has no delete: a cleared flag is any value but the set one. */
    fun clear(store: LedgerStore) = store.rawKvSet(PENDING_KEY, byteArrayOf(0))

    /** The welcome closed (any exit): first run clears the flag, a replay never does. */
    fun closed(mode: WelcomeMode, clear: () -> Unit) {
        if (!WelcomeFlow.clearsFlag(mode)) return
        runCatching { clear() }
    }

    /** The launch's read once the vault opened. A flag that cannot be read is "not pending": the welcome is optional and
     *  never blocks the app. */
    fun atLaunch(launchedBefore: Boolean, readPending: () -> Boolean): Boolean =
        shouldShow(launchedBefore, runCatching(readPending).getOrDefault(false))
}

/** D-245 (decision 160, plan 1.3 W2): a theme tile applies the theme at once through the SAME write Appearance's tiles
 *  make ([Appearance.setTheme], D-244's setting record when signed in), and closing the welcome is closing Appearance:
 *  the app icon follows "Match theme" then (D-216, decision 34), and what was chosen goes up in one push. */
object WelcomeLook {
    /** The tile's write: Appearance's own. */
    fun choose(context: Context, theme: AppTheme) = choose(theme) { Appearance.setTheme(context, it) }

    /** The decision, with the write as a seam (a test passes a spy). */
    fun choose(theme: AppTheme, set: (AppTheme) -> Unit) = set(theme)

    /** Appearance's close: the icon follows "Match theme", then the page's one push. */
    fun closed(context: Context) = closed(applyIcon = { AppIconSwitcher.applyIfNeeded(context) }, pageClosed = { SettingsSync.pageClosed(context) })

    fun closed(applyIcon: () -> Unit, pageClosed: () -> Unit) {
        applyIcon()
        pageClosed()
    }

    /** Once the welcome has gone: the Appearance close, then New group when that was the way out. */
    fun after(context: Context, exit: WelcomeExit?) {
        closed(context)
        if (exit == WelcomeExit.START_GROUP) StartGroupRequest.pending = true
    }
}

/** D-245: the welcome's "Start a group": the shell opens New group over Balance (iOS's `.skiptiStartGroup`). Main
 *  thread only. */
object StartGroupRequest {
    var pending by mutableStateOf(false)
}

// MARK: - Decisions 192 and 197: the progress, a moment that advances as each step is completed

/** D-245 (decisions 192 and 197, the founder's): the welcome's progress, pure, in STEP units: `p` runs 0 to 4, one per
 *  completed step, and each completion sweeps `p` up by one with an in-out ease (0.9 s). A mark lights as the sweep
 *  reaches it (and flares, then settles to a luminous rest). A MOMENT (D-239): Reduce Motion and Less motion still it,
 *  power saving never. Norse: "the winding road" (five runes ᚠ ᚢ ᚦ ᚨ ᚱ centred by their INK, arches between them);
 *  Aurora and Vardr Labs: four marks at the centres of the line's quarters. The twin of iOS's `WelcomeProgress`, its
 *  numbers in dp. */
object WelcomeProgress {
    const val STEPS = 4
    const val SWEEP_SECONDS = 0.9
    const val FLARE_SECONDS = 0.6
    const val REST_GLOW = 0.85
    const val PEAK_GLOW = 1.0
    const val POP_SCALE = 1.18
    /** The glow's blur, as a Gaussian sigma (the mock-up's stdDeviation 3.2; iOS's blur radius). */
    const val GLOW_BLUR = 3.2f

    /** D-246 fix wave (M5): a lit rune's glow is [GlyphGlow]'s cached mask (one glow language with the page, the hero,
     *  the ᚠ and the ring): a Gaussian of [GLOW_BLUR] dp at [RUNE_SIZE], this fraction of the size. */
    val RUNE_GLOW: Double = GLOW_BLUR.toDouble() / RUNE_SIZE.toDouble()

    /** The five runes' glow masks: GlyphGlow's cache, built once a process (asked again, it builds nothing). */
    fun glowMasks(raster: GlyphGlow.Raster): List<GlyphGlow.Mask> = runes.map { GlyphGlow.mask(it, RUNE_SIZE.toDouble(), RUNE_GLOW, raster) }

    // Aurora and Vardr Labs: a 240 dp line, four marks.
    const val WIDTH = 240f
    const val LINE_MARKS = 4
    const val DOT = 10f
    const val AURORA_HEIGHT = 30f
    const val SEGMENT_HEIGHT = 6f
    const val SEGMENT_GAP = 4f
    const val VARDR_HEIGHT = 22f

    // Norse: the winding road.
    const val NORSE_WIDTH = 340f
    const val NORSE_HEIGHT = 92f
    const val RUNE_SIZE = 26f
    const val BASELINE = 50f
    const val RUNE_START = 30f
    const val RUNE_SPACING = 70f
    const val ROAD_INSET = 14f
    const val ROAD_CONTROL = 16f
    const val ROAD_RISE = 30f
    const val TRACK_WIDTH = 1.6f
    const val TRACK_ALPHA = 0.75f
    const val FILL_WIDTH = 2.2f
    const val SPARK_RADIUS = 2.6f
    /** ᚠ ᚢ ᚦ ᚨ ᚱ. */
    val runes: List<String> get() = Runes.FUTHARK.take(5)

    fun marks(theme: AppTheme) = if (theme == AppTheme.NORSE) runes.size else LINE_MARKS

    /** The progress at which mark `i` lights: Norse's rune i at i (ᚠ from the start), a node or segment i at i + 1. */
    fun litAt(i: Int, theme: AppTheme): Double = if (theme == AppTheme.NORSE) i.toDouble() else (i + 1).toDouble()

    /** The completed share of the welcome (0, 1/4, 2/4, 3/4, 1). */
    fun value(completed: Int): Double = completed.coerceIn(0, STEPS).toDouble() / STEPS

    /** The mock-up's in-out ease. */
    fun eased(t: Double): Double = when {
        t <= 0 -> 0.0
        t >= 1 -> 1.0
        t < 0.5 -> 2 * t * t
        else -> 1 - (-2 * t + 2).pow(2) / 2
    }

    fun progress(from: Double, to: Double, elapsed: Double): Double = from + (to - from) * eased(elapsed / SWEEP_SECONDS)

    /** When (seconds into the sweep) the progress reaches `at`, or null when this sweep does not cross it. */
    fun crossing(at: Double, from: Double, to: Double): Double? {
        if (!(to > from && at > from + 1e-9 && at <= to + 1e-9)) return null
        val target = (at - from) / (to - from)
        var lo = 0.0
        var hi = 1.0
        repeat(40) {
            val mid = (lo + hi) / 2
            if (eased(mid) < target) lo = mid else hi = mid
        }
        return hi * SWEEP_SECONDS
    }

    data class Pose(
        val progress: Double,
        val lit: List<Boolean>,
        val glow: List<Double>,
        val scale: List<Double>,
        /** Norse: the spark rides the road's front while it sweeps. */
        val spark: Boolean,
    )

    /** The frame at `elapsed` seconds into the sweep from `from` to `to` (step units) in `theme`; `still` is the end
     *  state at once, every lit mark at its rest glow, no spark. */
    fun pose(theme: AppTheme, from: Double, to: Double, elapsed: Double, still: Boolean): Pose {
        val shown = if (still) to else progress(from, to, elapsed)
        val lit = mutableListOf<Boolean>()
        val glow = mutableListOf<Double>()
        val scale = mutableListOf<Double>()
        for (i in 0 until marks(theme)) {
            val at = litAt(i, theme)
            val on = at <= shown + 1e-9
            var g = if (on) REST_GLOW else 0.0
            var s = 1.0
            val crossed = if (on && !still) crossing(at, from, to) else null
            if (crossed != null) {
                val k = exp(-maxOf(0.0, elapsed - crossed) / (FLARE_SECONDS / 3))
                g = REST_GLOW + (PEAK_GLOW - REST_GLOW) * k
                s = 1 + (POP_SCALE - 1) * k
            }
            lit += on
            glow += g
            scale += s
        }
        return Pose(shown, lit, glow, scale, spark = !still && to > from && elapsed < SWEEP_SECONDS)
    }

    /** Seconds after which nothing moves (the last flare has settled). */
    const val SETTLES = SWEEP_SECONDS + FLARE_SECONDS

    // Aurora and Vardr Labs: the line

    /** Where mark `i` (0 to 3) sits on the line: the centre of its quarter. */
    fun position(i: Int): Double = (i + 0.5) / LINE_MARKS

    /** The line's fill at progress `p`: the start, then each step's mark, straight between. */
    fun lineFill(p: Double): Double {
        val clamped = p.coerceIn(0.0, STEPS.toDouble())
        val k = kotlin.math.floor(clamped).toInt()
        fun at(step: Int) = if (step == 0) 0.0 else position(step - 1)
        if (k >= STEPS) return at(STEPS)
        return at(k) + (at(k + 1) - at(k)) * (clamped - k)
    }

    /** Vardr Labs: how full segment `i` is at line fill `fill`: it fills between the mark before it (or the start) and
     *  its own. */
    fun segmentShare(i: Int, fill: Double): Double {
        val start = if (i == 0) 0.0 else position(i - 1)
        return ((fill - start) / (position(i) - start)).coerceIn(0.0, 1.0)
    }

    /** Vardr Labs: segment `i`'s span on the line (x, width), its quarter less half a gap each side. */
    fun segment(i: Int): Pair<Float, Float> {
        val quarter = WIDTH / LINE_MARKS
        return (quarter * i + SEGMENT_GAP / 2) to (quarter - SEGMENT_GAP)
    }

    // Norse: the road

    /** Rune `i`'s ink centre (dp). */
    fun runeCentre(i: Int) = Offset(RUNE_START + RUNE_SPACING * i, BASELINE)

    data class Road(val start: Offset, val c1: Offset, val c2: Offset, val end: Offset)

    /** Road `i` (from rune i to rune i + 1): its start, two controls and end; up on even roads, down on odd. */
    fun road(i: Int): Road {
        val x0 = runeCentre(i).x + ROAD_INSET
        val x1 = runeCentre(i + 1).x - ROAD_INSET
        val y = BASELINE + (if (i % 2 == 0) -ROAD_RISE else ROAD_RISE)
        return Road(Offset(x0, BASELINE), Offset(x0 + ROAD_CONTROL, y), Offset(x1 - ROAD_CONTROL, y), Offset(x1, BASELINE))
    }

    /** A point on road `i` at parameter `t` (the cubic's own). */
    fun roadPoint(i: Int, t: Double): Offset {
        val r = road(i)
        val u = 1 - t
        val a = u * u * u
        val b = 3 * u * u * t
        val c = 3 * u * t * t
        val d = t * t * t
        return Offset(
            (a * r.start.x + b * r.c1.x + c * r.c2.x + d * r.end.x).toFloat(),
            (a * r.start.y + b * r.c1.y + c * r.c2.y + d * r.end.y).toFloat(),
        )
    }

    /** The point `share` of the way along road `i` by LENGTH (as the fill measures it): where the spark rides. */
    fun roadPointByShare(i: Int, share: Double): Offset {
        val n = 96
        val lengths = DoubleArray(n + 1)
        var last = roadPoint(i, 0.0)
        for (k in 1..n) {
            val p = roadPoint(i, k.toDouble() / n)
            lengths[k] = lengths[k - 1] + hypot((p.x - last.x).toDouble(), (p.y - last.y).toDouble())
            last = p
        }
        val want = share.coerceIn(0.0, 1.0) * lengths[n]
        val k = (0..n).firstOrNull { lengths[it] >= want } ?: return roadPoint(i, 0.0)
        if (k == 0) return roadPoint(i, 0.0)
        val f = (want - lengths[k - 1]) / maxOf(lengths[k] - lengths[k - 1], 1e-9)
        return roadPoint(i, (k - 1 + f) / n)
    }

    /** How much of road `i` is filled at progress `p`. */
    fun roadShare(i: Int, progress: Double): Double = (progress - i).coerceIn(0.0, 1.0)
}

/** D-245 (decisions 192, 197, the founder's look): where a glyph laid out from its box's top-left must start so its
 *  INK centre sits on [centre] (Vardr Runes Carved has uneven side bearings, so a row centred by the box would
 *  wander; the twin of iOS's `RuneInk`). [inkLeft] to [inkBottom] are the ink's box in the layout's coordinates. */
object RuneInk {
    fun topLeft(inkLeft: Float, inkTop: Float, inkRight: Float, inkBottom: Float, centre: Offset): Offset =
        Offset(centre.x - (inkLeft + inkRight) / 2, centre.y - (inkTop + inkBottom) / 2)
}

/** Decisions 192 and 197: the progress, drawn in the theme's language. Spoken as the step's announcement ("Step n of 4,
 *  <title>"), a polite live region, so TalkBack says the page changed (fix wave M8; iOS posts the same words). A moment
 *  ([MotionState.moment]): Reduce Motion and Less motion land it at once, power saving never stills it. */
@Composable
fun WelcomeProgressBar(from: Double, to: Double, startedAtMs: Long, spoken: String, dark: Boolean) {
    val motion = rememberMotionState().moment()
    val theme = Appearance.theme
    val play = remember(startedAtMs, motion.tick) { MomentPlay(startedAtMs, still = !motion.tick) }
    LaunchedEffect(play) { play.run(WelcomeProgress.SETTLES) }
    val pose = WelcomeProgress.pose(theme, from, to, play.elapsed(WelcomeProgress.SETTLES), still = !motion.tick)
    Box(Modifier.clearAndSetSemantics { contentDescription = spoken; liveRegion = LiveRegionMode.Polite }) {
        WelcomeProgressDrawing(pose, theme, dark)
    }
}

/** One frame of the progress in one theme. Every mark is placed by its centre at its place (Norse: its ink centre). */
@Composable
fun WelcomeProgressDrawing(pose: WelcomeProgress.Pose, theme: AppTheme, dark: Boolean) {
    when (theme) {
        AppTheme.NORSE -> NorseRoad(pose, dark)
        AppTheme.AURORA -> AuroraLine(pose, dark)
        AppTheme.VARDR -> VardrBar(pose, dark)
    }
}

/** The glow's blur in px: a Gaussian of [WelcomeProgress.GLOW_BLUR] dp, through Compose's radius ([ShadowBlur]). */
private fun glowRadiusPx(density: Float): Float = ShadowBlur.radiusDp(WelcomeProgress.GLOW_BLUR, density) * density

@Composable
private fun AuroraLine(pose: WelcomeProgress.Pose, dark: Boolean) {
    val w = WelcomeProgress.WIDTH
    val h = WelcomeProgress.AURORA_HEIGHT
    val d = WelcomeProgress.DOT
    val fill = WelcomeProgress.lineFill(pose.progress).toFloat()
    val track = Tok.ringTrack(dark)
    val ring = Tok.ring(dark)
    val brush = Brush.horizontalGradient(listOf(Tok.meterEnd(dark), Tok.meterStart(dark)))
    val density = LocalDensity.current.density
    Box(Modifier.size(w.dp, h.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val y = size.height / 2 - 1.5.dp.toPx()
            val r = CornerRadius(1.5.dp.toPx())
            drawRoundRect(track, Offset(0f, y), Size(size.width, 3.dp.toPx()), r)
            if (fill > 0f) drawRoundRect(brush, Offset(0f, y), Size(size.width * fill, 3.dp.toPx()), r)
        }
        for (i in 0 until WelcomeProgress.LINE_MARKS) {
            val x = w * WelcomeProgress.position(i).toFloat()
            val lit = pose.lit[i]
            val s = pose.scale[i].toFloat()
            Box(
                Modifier
                    .offset((x - d / 2).dp, (h / 2 - d / 2).dp)
                    .size(d.dp)
                    .graphicsLayer { scaleX = s; scaleY = s }
                    .then(
                        if (lit) Modifier.dropShadow(CircleShape) {
                            radius = glowRadiusPx(density)
                            color = ring
                            alpha = pose.glow[i].toFloat()
                        } else Modifier,
                    )
                    .background(if (lit) ring else track, CircleShape),
            )
        }
    }
}

@Composable
private fun VardrBar(pose: WelcomeProgress.Pose, dark: Boolean) {
    val w = WelcomeProgress.WIDTH
    val h = WelcomeProgress.VARDR_HEIGHT
    val fill = WelcomeProgress.lineFill(pose.progress)
    val track = Tok.ringTrack(dark)
    val ring = Tok.ring(dark)
    val mark = Tok.textDim(dark)
    val density = LocalDensity.current.density
    Box(Modifier.size(w.dp, h.dp)) {
        for (i in 0 until WelcomeProgress.LINE_MARKS) {
            val (x, width) = WelcomeProgress.segment(i)
            val share = WelcomeProgress.segmentShare(i, fill).toFloat()
            Box(
                Modifier
                    .offset(x.dp, (h / 2 - WelcomeProgress.SEGMENT_HEIGHT / 2).dp)
                    .size(width.dp, WelcomeProgress.SEGMENT_HEIGHT.dp)
                    .then(
                        if (pose.lit[i]) Modifier.dropShadow(RectangleShape) {
                            radius = glowRadiusPx(density)
                            color = ring
                            alpha = pose.glow[i].toFloat()
                        } else Modifier,
                    )
                    .background(track),
            ) {
                Box(Modifier.fillMaxHeight().width((width * share).dp).background(ring))
            }
        }
        Canvas(Modifier.matchParentSize()) {
            ArtGeometry.frameMarks(size.width, size.height, 0f, 6.dp.toPx())
                .forEach { (a, b) -> drawLine(mark, a, b, strokeWidth = 1.dp.toPx()) }
        }
    }
}

/** Decision 197: the road (each arch's track in the deep gold, the gold fill running along it by length, round caps,
 *  a small bright spark at its front while it sweeps) and the five runes, each placed by its INK centre; a lit rune is
 *  luminous: the lighter gold over a blurred copy of the glyph itself (never a disc). D-246 fix wave (M5): that copy is
 *  [GlyphGlow]'s cached mask, remembered here and only drawn per frame (it was the text's own blurred shadow, re-blurred
 *  every frame). */
@Composable
private fun NorseRoad(pose: WelcomeProgress.Pose, dark: Boolean) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val glyphs = remember(density) {
        val style = Type.rune(with(density) { WelcomeProgress.RUNE_SIZE.dp.toSp() })
        WelcomeProgress.runes.map { measurer.measure(it, style) }
    }
    val inks = remember(glyphs) { glyphs.map { it.getPathForRange(0, it.layoutInput.text.length).getBounds() } }
    val track = ThemeArt.color("norseEdge").copy(alpha = WelcomeProgress.TRACK_ALPHA)
    val accent = Tok.accent(dark)
    val ring = Tok.ring(dark)
    val litInk = ThemeArt.color("diamondTop")
    val unlitInk = ThemeArt.color("runeUnlit")
    val raster = rememberGlyphRaster()
    val glows = remember(raster) { WelcomeProgress.glowMasks(raster) }
    val tint = remember(ring) { ColorFilter.tint(ring) }
    Canvas(Modifier.size(WelcomeProgress.NORSE_WIDTH.dp, WelcomeProgress.NORSE_HEIGHT.dp)) {
        val k = 1.dp.toPx()
        val measure = PathMeasure()
        for (i in 0 until WelcomeProgress.STEPS) {
            val r = WelcomeProgress.road(i)
            val path = Path().apply {
                moveTo(r.start.x * k, r.start.y * k)
                cubicTo(r.c1.x * k, r.c1.y * k, r.c2.x * k, r.c2.y * k, r.end.x * k, r.end.y * k)
            }
            drawPath(path, track, style = Stroke(WelcomeProgress.TRACK_WIDTH * k, cap = StrokeCap.Round))
            val share = WelcomeProgress.roadShare(i, pose.progress)
            if (share > 0) {
                measure.setPath(path, false)
                val part = Path()
                measure.getSegment(0f, measure.length * share.toFloat(), part, true)
                drawPath(part, accent, style = Stroke(WelcomeProgress.FILL_WIDTH * k, cap = StrokeCap.Round))
            }
            if (pose.spark && share > 0 && share < 1) {
                drawCircle(litInk, WelcomeProgress.SPARK_RADIUS * k, WelcomeProgress.roadPointByShare(i, share) * k)
            }
        }
        for (i in WelcomeProgress.runes.indices) {
            val centre = WelcomeProgress.runeCentre(i) * k
            val ink = inks[i]
            val at = RuneInk.topLeft(ink.left, ink.top, ink.right, ink.bottom, centre)
            val lit = pose.lit[i]
            scale(pose.scale[i].toFloat(), pivot = centre) {
                if (lit) with(ArtDraw) { glyphGlow(glows[i], at.x, at.y + glyphs[i].firstBaseline, pose.glow[i].toFloat(), tint) }
                drawText(glyphs[i], color = if (lit) litInk else unlitInk, topLeft = at)
            }
        }
    }
}

// MARK: - The screens

/** D-245: the welcome's state, kept above the screen so a lock or a re-made activity (a theme tap on Android 12+)
 *  keeps the step, the typed name and a staged emoji. A staged photo lives in memory only. */
class WelcomeSession {
    var step by mutableStateOf(WelcomeStep.WELCOME)
    var name by mutableStateOf("")
    var savedName by mutableStateOf<String?>(null)
    /** The name the done row confirms; it shows while the field still holds it ("stays until the field changes"). */
    var nameDoneFor by mutableStateOf<String?>(null)
    var nameError by mutableStateOf<String?>(null)
    var saved by mutableStateOf<Avatar>(Avatar.None)
    var stage by mutableStateOf(PictureStage())
    var moreOpen by mutableStateOf(false)
    var pictureError by mutableStateOf<String?>(null)
    /** Decision 192: the progress sweep, from what was shown to what is completed, from when (uptime ms). */
    var progressFrom by mutableDoubleStateOf(0.0)
    var progressTo by mutableDoubleStateOf(0.0)
    var progressStart by mutableLongStateOf(0L)
    /** The vault's name has been read into the field once (a restored field keeps what was typed). */
    var loaded = false
    /** Fix wave I1: this run of the welcome closed; the next show starts fresh ([opening]). Saved with the session. */
    var closed = false
        private set
    /** Fix wave M2: one name or picture save at a time, D-235's latch (not saved: a re-made activity has none in
     *  flight). */
    val saving = SaveLatch()

    val nameDone: Boolean get() = nameDoneFor != null && nameDoneFor == NameRule.trimmed(name)

    val completed: Int get() = WelcomeFlow.completed(step, nameSaved = nameDone, pictureSaved = stage.done != null)

    val action: WelcomeAction get() = WelcomeFlow.action(step, NameRule.dirty(name, savedName), stage.staged != null)

    /** Decision 192: a new completed share sweeps from the one shown now; decision 198: a step back never takes the
     *  progress back. True when a sweep started. */
    fun advance(completed: Int, now: Long): Boolean {
        val to = WelcomeFlow.shown(completed.coerceIn(0, WelcomeProgress.STEPS), progressTo.toInt()).toDouble()
        if (to <= progressTo) return false
        progressFrom = progressTo
        progressTo = to
        progressStart = now
        return true
    }

    /** Fix wave I1: the welcome closed (any exit). */
    fun close() {
        closed = true
    }

    /** Fix wave I1: the welcome is about to show. A session that closed starts fresh (after an erase the new vault's
     *  first run opens at W1 with an empty field); one still open (a re-made activity, a lock that came back) keeps
     *  its step and what was typed. */
    fun opening() {
        if (closed) reset()
    }

    fun reset() {
        step = WelcomeStep.WELCOME
        name = ""
        savedName = null
        saved = Avatar.None
        nameDoneFor = null
        nameError = null
        stage = PictureStage()
        moreOpen = false
        pictureError = null
        progressFrom = 0.0
        progressTo = 0.0
        progressStart = 0L
        loaded = false
        closed = false
    }

    companion object {
        val Saver = listSaver<WelcomeSession, Any?>(
            save = {
                listOf(it.step.ordinal, it.name, it.nameDoneFor, it.progressTo, it.moreOpen, (it.stage.staged as? Avatar.Emoji)?.emoji,
                    it.stage.done, it.loaded, it.closed)
            },
            restore = { v ->
                WelcomeSession().apply {
                    step = WelcomeStep.entries[v[0] as Int]
                    name = v[1] as String
                    nameDoneFor = v[2] as String?
                    progressTo = v[3] as Double
                    progressFrom = progressTo
                    moreOpen = v[4] as Boolean
                    stage = PictureStage(staged = (v[5] as String?)?.let { Avatar.Emoji(it) }, done = v[6] as String?)
                    loaded = v[7] as Boolean
                    if (v[8] as Boolean) close()
                }
            },
        )
    }
}

/** D-245: the welcome as a full-screen layer: over the shell (first run) or over About (replay). It takes every touch
 *  under it, hides the shell's toast while it shows (iOS's cover does), and system Back goes one step back on W2 to W4;
 *  on W1 and Done, first run leaves Back to the system (Skip is the way out), a replay closes as Skip does. */
@Composable
fun WelcomeLayer(vaultPath: String, mode: WelcomeMode, session: WelcomeSession, onClose: (WelcomeExit) -> Unit) {
    CoversShellToast()
    val previous = WelcomeFlow.previous(session.step)
    BackHandler(enabled = previous != null || mode == WelcomeMode.REPLAY) {
        if (previous != null) session.step = previous else onClose(WelcomeExit.SKIPPED)
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        WelcomeScreen(vaultPath, session, onClose)
    }
}

/** W1's five places: the real bar's glyphs in the current theme (Norse runes, Lucide elsewhere), the + on the accent,
 *  You as the person glyph, each in the theme's person tile (Aurora circle, Norse diamond, Vardr Labs square). Fix wave
 *  I3: a label wraps to two lines in its column; at the large text sizes ([stacked]) the place is a row, its tile then
 *  its label, which wraps as far as it needs. */
@Composable
private fun WelcomePlace(index: Int, dark: Boolean, stacked: Boolean) {
    val theme = Appearance.theme
    val kind = TileShape.of(theme, person = true)
    val side = ThemeArt.m("seal").toInt()
    val add = index == 2
    val tint = if (add) Tok.onAccent(dark) else if (index == 4) Tok.accent(dark) else Tok.ring(dark)
    val tabs = mapOf(0 to HomeTab.BALANCE, 1 to HomeTab.GROUPS, 3 to HomeTab.SETTLE)
    val tile: @Composable () -> Unit = {
        Box(Modifier.size(side.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .requiredSize(tileDrawnSide(kind, side).dp)
                    .tileEdge(kind, side)
                    .background(if (add) Tok.accent(dark) else Tok.accentSoft(dark), tileShape(kind, side)),
                contentAlignment = Alignment.Center,
            ) {
                val tab = tabs[index]
                if (tab != null && TabIndicator.drawsRunes(theme)) {
                    val runeDp = ThemeArt.m("seal") * 0.6f
                    Text(
                        tab.rune,
                        style = Type.rune(with(LocalDensity.current) { runeDp.dp.toSp() }).copy(color = tint),
                        modifier = Modifier.offset(y = (runeDp * ThemeArt.RUNE_INK_SHIFT_EM).dp),
                    )
                } else {
                    val icon = tab?.icon ?: if (add) TabIcons.Plus else TabIcons.UserRound
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
    if (stacked) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            tile()
            Text(WelcomeCopy.PLACES[index], style = Type.name(dark, TypeScale.detail.toInt()))
        }
    } else {
        Column(
            Modifier.width(92.dp).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tile()
            // iOS's lineLimit(2) and minimumScaleFactor(0.8): two lines, shrinking to 80% before an ellipsis.
            Text(
                WelcomeCopy.PLACES[index],
                style = Type.name(dark, TypeScale.detail.toInt()),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = (TypeScale.detail * 0.8f).sp, maxFontSize = TypeScale.detail.sp, stepSize = 0.2.sp),
            )
        }
    }
}

/** A welcome title: the display face at the lock screen's 28 (the check screens' title), centred, Norse's ornament
 *  under it. */
@Composable
private fun WelcomeTitle(text: String, dark: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text,
            style = Type.display(dark, if (Appearance.theme == AppTheme.VARDR) 600 else 700).copy(fontSize = TypeScale.lockTitle.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        WelcomeOrnament(dark)
    }
}

/** Norse's title ornament under every welcome title, narrower than a tab root's. */
@Composable
private fun WelcomeOrnament(dark: Boolean) {
    if (TitleOrnaments.shows(Appearance.theme)) TitleOrnament(dark, above = 12, modifier = Modifier.width(200.dp))
}

@Composable
private fun WelcomeLine(text: String, dark: Boolean) {
    Text(text, style = Type.bodyDim(dark).copy(fontSize = TypeScale.prose.sp), textAlign = TextAlign.Center)
}

@Composable
private fun WelcomeDetail(text: String, dark: Boolean, color: Color = Tok.textDim(dark)) {
    Text(text, style = Type.body(dark).copy(fontSize = TypeScale.detail.sp, color = color), textAlign = TextAlign.Center)
}

/** Fix wave (twins): the step change, iOS's `.animation(.easeInOut(duration: 0.25), value: step)` with the page's
 *  opacity transition: ONE crossfade of the whole page, the leaving page fading out while the arriving one fades in, and
 *  Back, Skip and the bottom action changing WITH it (a control both steps draw stays put, as a view of the same
 *  identity does on iOS). Pure, so the JVM tests read it. */
object WelcomeFade {
    /** iOS's 0.25 s. */
    const val MILLIS = 250
    /** SwiftUI's easeInOut (Core Animation's ease-in-ease-out curve). */
    val EASING = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** How far the current crossfade has run (0 to 1): `fade` is animated to `turn`, one per step change. */
    fun progress(fade: Float, turn: Int): Float = (fade - (turn - 1)).coerceIn(0f, 1f)

    /** A control's opacity at `t`: drawn by both steps it stays; drawn by the arriving one it fades in; by the leaving one
     *  it fades out. */
    fun slot(leaving: Boolean, arriving: Boolean, t: Float): Float = when {
        leaving && arriving -> 1f
        arriving -> t
        leaving -> 1f - t
        else -> 0f
    }
}

/** The leaving layer of a crossfade: drawn at [alpha], silent to TalkBack, and it takes no touch. */
private fun Modifier.leavingLayer(alpha: Float): Modifier = this
    .graphicsLayer { this.alpha = alpha }
    .clearAndSetSemantics {}
    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }

/** D-245: the welcome's five screens. The twin of iOS's `WelcomeView`. */
@Composable
fun WelcomeScreen(vaultPath: String, session: WelcomeSession, onClose: (WelcomeExit) -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val signedIn = remember { runCatching { Account.isSignedIn() }.getOrDefault(false) }
    val s = session

    LaunchedEffect(vaultPath) {
        val read = withContext(Dispatchers.IO) {
            val store = LedgerStore(vaultPath)
            runCatching { store.loadSelf()?.name }.getOrNull() to runCatching { store.loadAvatar() }.getOrDefault(Avatar.None)
        }
        s.savedName = read.first
        s.saved = read.second
        if (!s.loaded) s.name = read.first ?: ""
        s.loaded = true
    }
    LaunchedEffect(s.completed) { s.advance(s.completed, SystemClock.uptimeMillis()) }

    /** [returned]: the keyboard's Done, which never saves an unchanged name again (fix wave M1). One save at a time
     *  (fix wave M2). */
    fun saveName(returned: Boolean = false) {
        if (!s.saving.begin()) return
        val typed = s.name
        val saved = s.savedName
        scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val store = LedgerStore(vaultPath)
                    val effects = ProfileSave.Effects.live(vaultPath)
                    if (returned) ProfileSave.returned(typed, saved, store, effects) else ProfileSave.name(typed, store, effects)
                }
            } finally {
                s.saving.release()
            }
            when (outcome) {
                null -> focus.clearFocus()
                is ProfileSave.NameOutcome.Saved -> {
                    s.savedName = outcome.name
                    s.name = outcome.name
                    s.nameError = null
                    focus.clearFocus()
                    s.nameDoneFor = outcome.name
                    // Decision 199: the done row is the done state here; the success haptic plays, no toast.
                    ProfileSave.done(ProfileSave.nameMoment, ProfileSave.Feedback.HAPTIC)
                }
                is ProfileSave.NameOutcome.Refused -> s.nameError = outcome.words
            }
        }
    }

    /** Use this picture: through the confirm's one seam, once (fix waves M2, M6); a failure in words (M4). */
    fun usePicture() {
        if (!s.saving.begin()) return
        val before = s.stage
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching { ProfileSave.answer(PictureAnswer.USE, before, { LedgerStore(vaultPath) }, ProfileSave.Effects.live(vaultPath)) }
                }
            } finally {
                s.saving.release()
            }
            result.onSuccess { answered ->
                s.stage = answered.stage
                answered.written?.let {
                    s.saved = it
                    s.moreOpen = false
                    ProfileSave.done(ProfileSave.pictureMoment(it), ProfileSave.Feedback.HAPTIC)
                }
            }.onFailure { s.pictureError = WelcomeCopy.PICTURE_NOT_SAVED }
        }
    }

    fun goNext() {
        val next = WelcomeFlow.next(s.step) ?: return
        focus.clearFocus()
        s.step = next
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val stored = withContext(Dispatchers.IO) { ProfileRules.storedPhoto(context, uri) }
            if (stored != null) {
                s.pictureError = null
                s.stage = s.stage.choose(Avatar.Photo(stored))
            } else {
                s.pictureError = WelcomeCopy.PHOTO_UNREADABLE
            }
        }
    }

    // The step change ([WelcomeFade]): `to` is the step drawn, `from` the one leaving; one crossfade per change. Under
    // reduced motion the page changes at once.
    val reduce = rememberMotionState().reduce
    var from by remember { mutableStateOf(s.step) }
    var to by remember { mutableStateOf(s.step) }
    var turn by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.step) {
        if (s.step != to) {
            from = to
            to = s.step
            turn++
        }
    }
    val fade by animateFloatAsState(turn.toFloat(), if (reduce) snap() else tween(WelcomeFade.MILLIS, easing = WelcomeFade.EASING), label = "welcomeStep")
    val t = WelcomeFade.progress(fade, turn)
    val leaving = if (t < 1f && from != to) from else null
    val dirty = NameRule.dirty(s.name, s.savedName)
    val staged = s.stage.staged != null

    @Composable
    fun Actions(step: WelcomeStep, modifier: Modifier) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (WelcomeFlow.action(step, dirty, staged)) {
                WelcomeAction.NEXT -> PrimaryAction(WelcomeCopy.NEXT, dark) { goNext() }
                WelcomeAction.SAVE_NAME -> PrimaryAction(WelcomeCopy.SAVE_NAME, dark, enabled = NameRule.check(s.name) == null) { saveName() }
                WelcomeAction.USE_PICTURE -> PrimaryAction(WelcomeCopy.USE, dark) { usePicture() }
                WelcomeAction.FINISH -> {
                    PrimaryAction(WelcomeCopy.START_GROUP, dark) { onClose(WelcomeExit.START_GROUP) }
                    SecondaryAction(WelcomeCopy.LOOK_AROUND, dark) { onClose(WelcomeExit.LOOK_AROUND) }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().auroraBackground(dark)) {
        // Top: decision 198's back on W2 to W4, Skip on W1 to W4. Fix wave I3: at least 44 high, taller when Skip grows
        // with the font scale (never clipped).
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val back = WelcomeFlow.previous(to)
            val backAlpha = WelcomeFade.slot(leaving?.let { WelcomeFlow.previous(it) != null } == true, back != null, t)
            if (backAlpha > 0f) {
                Box(
                    Modifier
                        .size(44.dp)
                        .then(if (back != null) Modifier.graphicsLayer { alpha = backAlpha } else Modifier.leavingLayer(backAlpha))
                        .then(if (back != null) Modifier.clickable { s.step = back } else Modifier)
                        .semantics { contentDescription = WelcomeCopy.BACK; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TabIcons.ChevronLeft, contentDescription = null, tint = Tok.groundAccent(dark), modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            val skips = WelcomeFlow.skippable(to)
            val skipAlpha = WelcomeFade.slot(leaving?.let(WelcomeFlow::skippable) == true, skips, t)
            if (skipAlpha > 0f) {
                Box(
                    Modifier
                        .heightIn(min = 44.dp)
                        .widthIn(min = 44.dp)
                        .then(if (skips) Modifier.graphicsLayer { alpha = skipAlpha } else Modifier.leavingLayer(skipAlpha))
                        .then(if (skips) Modifier.clickable(role = Role.Button) { onClose(WelcomeExit.SKIPPED) } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(WelcomeCopy.SKIP, style = Type.label(dark, TypeScale.inlineTitle.toInt()).copy(color = Tok.groundAccent(dark)))
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            WelcomeProgressBar(s.progressFrom, s.progressTo, s.progressStart, WelcomeFlow.announcement(to, s.savedName), dark)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = viewport)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    listOfNotNull(leaving, to).forEach { page ->
                        key(page) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (page == to) Modifier.graphicsLayer { alpha = t } else Modifier.leavingLayer(1f - t)),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                when (page) {
                                    WelcomeStep.WELCOME -> WelcomePage(dark, signedIn)
                                    WelcomeStep.LOOK -> LookPage(dark) { WelcomeLook.choose(context, it) }
                                    WelcomeStep.NAME -> NamePage(s, dark, onSave = { saveName() }, onReturn = { saveName(returned = true) })
                                    WelcomeStep.PICTURE -> PicturePage(s, dark, signedIn, onPhoto = {
                                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    })
                                    WelcomeStep.DONE -> DonePage(s, dark)
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
            val leavingAction = leaving?.let { WelcomeFlow.action(it, dirty, staged) }
            if (leavingAction != null && leavingAction != WelcomeFlow.action(to, dirty, staged)) {
                Actions(leaving, Modifier.leavingLayer(1f - t))
                Actions(to, Modifier.graphicsLayer { alpha = t })
            } else {
                Actions(to, Modifier)
            }
        }
    }
}

@Composable
private fun WelcomePage(dark: Boolean, signedIn: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(
            Modifier.clearAndSetSemantics { contentDescription = WelcomeCopy.WELCOME_TITLE; heading() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                WelcomeCopy.WELCOME_LEAD,
                style = Type.display(dark, 600).copy(fontSize = TypeScale.inlineTitle.sp, color = Tok.groundAccent(dark)),
            )
            // iOS's minimumScaleFactor(0.5).
            Text(
                WelcomeCopy.WORDMARK,
                style = Type.display(dark, 700).copy(fontSize = TypeScale.welcomeMark.sp),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = (TypeScale.welcomeMark * 0.5f).sp, maxFontSize = TypeScale.welcomeMark.sp),
            )
        }
        WelcomeOrnament(dark)
        WelcomeLine(WelcomeCopy.WELCOME_LINE, dark)
        // Fix wave I3: one place per row at the large text sizes, where a label outgrows its column.
        val stacked = TextSize.stacks(LocalDensity.current.fontScale)
        Column(
            Modifier.padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (stacked) 14.dp else 20.dp),
        ) {
            WelcomeFlow.placeRows(stacked).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { WelcomePlace(it, dark, stacked) } }
            }
        }
        // Fix wave I2: only without an account (a signed-in replay's data does not stay on this phone).
        WelcomeFlow.foot(WelcomeStep.WELCOME, signedIn)?.let { foot -> Box(Modifier.padding(top = 8.dp)) { WelcomeDetail(foot, dark) } }
    }
}

@Composable
private fun LookPage(dark: Boolean, onChoose: (AppTheme) -> Unit) {
    val current = Appearance.theme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        WelcomeTitle(WelcomeCopy.LOOK_TITLE, dark)
        WelcomeLine(WelcomeCopy.LOOK_LINE, dark)
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppTheme.entries.forEach { theme ->
                ThemeCard(theme, current == theme, dark, Modifier.fillMaxWidth()) { onChoose(theme) }
            }
        }
        if (current.forcesDark) {
            Text(WelcomeCopy.NORSE_DARK, style = Type.bodyDim(dark).copy(fontSize = AppearanceCopy.DETAIL_SIZE.sp))
        }
    }
}

@Composable
private fun NamePage(s: WelcomeSession, dark: Boolean, onSave: () -> Unit, onReturn: () -> Unit) {
    val trimmed = NameRule.trimmed(s.name)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        WelcomeTitle(WelcomeCopy.NAME_TITLE, dark)
        WelcomeLine(WelcomeCopy.NAME_LINE, dark)
        // P1, P2 (stage 1b): the app's carded field, a ListCard holding a FieldRow (the whole row takes the tap and
        // focuses; no underline inside the card), as New group's "Your name". The keyboard's Done saves.
        ListCard(dark) {
            row {
                FieldRow(
                    s.name, { s.name = it; s.nameError = null }, WelcomeCopy.NAME_PLACEHOLDER, dark,
                    style = Type.body(dark).copy(fontSize = 17.sp, textAlign = TextAlign.Center),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    actions = KeyboardActions(onDone = { onReturn() }),
                    centred = true,
                )
            }
        }
        if (trimmed.isNotEmpty()) WelcomeDetail(WelcomeCopy.nameCaption(trimmed), dark)
        val problem = s.nameError ?: NameRule.liveProblem(s.name, s.savedName)?.message
        if (problem != null) {
            WelcomeDetail(problem, dark, Tok.owed(dark))
        } else if (s.nameDone) {
            DoneRow(DoneCopy.NAME_SAVED, dark)
        }
    }
}

@Composable
private fun PicturePage(s: WelcomeSession, dark: Boolean, signedIn: Boolean, onPhoto: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        WelcomeTitle(WelcomeCopy.PICTURE_TITLE, dark)
        WelcomeLine(WelcomeCopy.PICTURE_LINE, dark)
        PictureConfirm(s.stage.staged ?: s.saved, s.savedName, signedIn, dark, done = s.stage.done) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.glassCard(dark, padding = 10)) {
                    EmojiChoices(s.stage.staged ?: s.saved, s.moreOpen, dark, onMore = { s.moreOpen = true }) { e ->
                        s.pictureError = null
                        s.stage = s.stage.choose(Avatar.Emoji(e))
                    }
                }
                SecondaryAction(WelcomeCopy.PHOTO, dark, onClick = onPhoto)
                s.pictureError?.let { WelcomeDetail(it, dark, Tok.owed(dark)) }
                // Fix wave I2: only without an account, never under the signed-in groups line.
                WelcomeFlow.foot(WelcomeStep.PICTURE, signedIn)?.let { WelcomeDetail(it, dark) }
            }
        }
    }
}

@Composable
private fun DonePage(s: WelcomeSession, dark: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        DoneCheck(DonePresentation.SCREEN, dark)
        WelcomeTitle(WelcomeFlow.doneTitle(s.savedName), dark)
        WelcomeLine(WelcomeCopy.DONE_LINE, dark)
    }
}

