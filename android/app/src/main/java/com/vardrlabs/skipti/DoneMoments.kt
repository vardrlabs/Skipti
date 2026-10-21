package com.vardrlabs.skipti

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * D-224 (E3, the founder's item 12, 2026-10-13): done moments and haptics. Every action that finishes says so, the
 * same on both phones: the big moment (a check that draws itself) for once-only moments, the small one (a toast) for
 * everyday ones, and three haptics behind one Haptics switch. This file holds the models, the copy, the haptic door and
 * the centre; the twin of iOS's DoneMoments.swift (DoneMomentsTest compares the copy). Nothing here is logged
 * (GUARANTEES #1) and nothing waits (GUARANTEES #2).
 */

/** Design 1: the three haptics. Light on a tab switch, success on a done moment, warning when a destructive question
 *  opens. The twin of iOS's `HapticKind`. */
enum class HapticKind {
    LIGHT,
    SUCCESS,
    WARNING,
    ;

    companion object {
        /** The SDK's named constant for [kind] on a phone at [sdkInt]: light CLOCK_TICK; success CONFIRM from API 30,
         *  else CONTEXT_CLICK; warning REJECT from API 30, else LONG_PRESS (CONFIRM and REJECT arrived with API 30). */
        fun constant(kind: HapticKind, sdkInt: Int): Int = when (kind) {
            LIGHT -> HapticFeedbackConstants.CLOCK_TICK
            SUCCESS -> if (sdkInt >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            WARNING -> if (sdkInt >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
    }
}

/** Design 2: the Haptics switch's stored form: `haptics` in the appearance preferences, on when nothing is stored. */
object HapticsSetting {
    const val STORAGE_KEY = "haptics"

    fun parse(stored: Boolean?): Boolean = stored ?: true
}

/** Where the door's one platform call lands: a view's haptic feedback ([Haptics.target]), or a test's recorder. */
fun interface HapticTarget {
    fun perform(constant: Int)
}

/** Design 1: the one door. Nothing else in the app touches the platform's haptics (DoneMomentsTest's source guard).
 *  Played with the constant alone, so the phone's own touch-feedback setting is honoured; no permission is needed. The
 *  success gate lives here and only here (views-extra 1): `DoneCenter.post` plays through this door and adds none. */
object Haptics {
    /** The switch silences every kind. */
    fun plays(kind: HapticKind, enabled: Boolean): Boolean = enabled

    /** When the last success played, in seconds of [SystemClock.uptimeMillis]; [HapticGate] skips one too soon after. */
    private var lastSuccess: Double? = null

    /** The view's haptic feedback: the app's one platform call. */
    fun target(view: View): HapticTarget = HapticTarget { view.performHapticFeedback(it) }

    fun play(view: View, kind: HapticKind) = play(target(view), kind, SystemClock.uptimeMillis() / 1000.0)

    /** [kind] at [now] (uptime seconds) on [target]: the switch, then for a success the gate, then the SDK's constant. */
    fun play(target: HapticTarget, kind: HapticKind, now: Double) {
        if (!plays(kind, Appearance.haptics)) return
        if (kind == HapticKind.SUCCESS) {
            if (!HapticGate.allows(lastSuccess, now)) return
            lastSuccess = now
        }
        target.perform(HapticKind.constant(kind, Build.VERSION.SDK_INT))
    }

    /** Test seam: forget the last success. */
    internal fun resetForTest() {
        lastSuccess = null
    }
}

/** Design 4: one line of a small moment: its title, then its parts (joined on screen by the theme's separator). D-247:
 *  [action], the label of the one thing the toast offers (a held settle's "Undo"); what it does is the centre's
 *  ([DoneMomentCenter.act]). The twin of iOS's `DoneLine`. */
data class DoneLine(
    val title: String,
    val parts: List<String>,
    val action: String? = null,
    /** D-247 fix wave (decision 201): how long a line with an action stays (the hold's window: 12 s with a screen reader,
     *  else [DoneCenter.ACTION_SECONDS]). */
    val actionSeconds: Double = DoneCenter.ACTION_SECONDS,
)

/** Design 4: the once-only moments the big check marks. */
enum class BigKind { FIRST_GROUP, SIGNED_IN, JOINED, ERASED }

data class BigMoment(val kind: BigKind, val title: String, val parts: List<String>)

/** Design 4: a done moment, small (the toast) or big (the check). */
sealed interface DoneMoment {
    val title: String
    val parts: List<String>

    data class Small(val line: DoneLine) : DoneMoment {
        override val title: String get() = line.title
        override val parts: List<String> get() = line.parts
    }

    data class Big(val moment: BigMoment) : DoneMoment {
        override val title: String get() = moment.title
        override val parts: List<String> get() = moment.parts
    }
}

/** Design 4, 5, 10: the moments' words, verbatim on both shells (DoneMomentsTest compares with DoneMoments.swift). */
object DoneCopy {
    const val EXPENSE_ADDED = "Expense added"
    const val EXPENSE_SAVED = "Expense saved"
    const val GROUP_CREATED = "Group created"
    const val GROUP_PICTURE_SAVED = "Group picture saved"
    const val GROUP_PICTURE_REMOVED = "Group picture removed"
    const val PICTURE_SAVED = "Picture saved"
    const val PICTURE_REMOVED = "Picture removed"
    const val NAME_SAVED = "Name saved"
    const val USERNAME_SAVED = "Username saved"
    const val SIGNED_OUT = "Signed out"
    const val GROUP_NAME_SAVED = "Group name saved"
    /** U27 (the founder's words, 2026-10-20): a group deleted, here or for everyone. */
    const val GROUP_DELETED = "Group deleted"
    const val HAPTICS_TITLE = "Haptics"
    /** D-234 (decision 83): fits the switch row on a 360 dp phone in every theme (T1's width test). */
    const val HAPTICS_SUMMARY = "A tap when it is done"

    fun personRemoved(name: String): String = "$name removed"

    /** D-247 (decision 182): a settle held for Undo (written when the toast's time is up). */
    fun settledWith(name: String): String = "Settled with $name. No money moved."

    /** D-247: the held settle's toast action, and the toast that says Undo wrote nothing. */
    const val UNDO = "Undo"
    const val NOT_RECORDED = "Not recorded."

    /** D-248 (U30.4): an expense deleted, its Undo on the toast wherever the delete happened. */
    const val EXPENSE_DELETED = "Expense deleted"

    /** D-248 fix wave (review M2): the toast when that Undo could not write the expense back (as [NOT_RECORDED] says a
     *  settle's Undo wrote nothing). */
    const val NOT_RESTORED = "Not restored."

    /** D-247 fix wave (decision 201): what a screen reader hears after a toast that offers an action, so the action is
     *  named and its time said ([seconds], whole seconds as digits). */
    fun actionSpoken(label: String, seconds: String): String = "$label is available for $seconds seconds."

    /** What a screen reader says: the title and the parts joined with a comma ([SpokenLine]). The separator is never
     *  read. */
    fun spoken(title: String, parts: List<String>): String = SpokenLine.join(listOf(title) + parts)

    /** D-247 fix wave (decision 201): a line with an action ends with the action named ([actionSpoken]). */
    fun spoken(line: DoneLine): String {
        val words = spoken(line.title, line.parts)
        val action = line.action ?: return words
        val seconds = Math.round(line.actionSeconds).toString()
        return words + " " + actionSpoken(action, seconds)
    }

    fun spoken(moment: DoneMoment): String =
        if (moment is DoneMoment.Small) spoken(moment.line) else spoken(moment.title, moment.parts)
}

/** The spoken form's joiner: pieces joined with ", ", and a separator inside a piece (Norse's rune or the middle dot,
 *  as in a group's detail line) read as the same comma, or a space when it stands alone. The twin of iOS's
 *  `SpokenLine`. */
private object SpokenLine {
    const val COMMA = ", "

    fun join(pieces: List<String>): String = pieces.joinToString(COMMA) { unseparated(it) }

    fun unseparated(text: String): String {
        var out = text
        for (sep in listOf(Runes.SEPARATOR, "\u00B7")) {
            out = out.replace(" $sep ", COMMA).replace(sep, " ")
        }
        return out
    }
}

/** Design 7: which moments are which. */
object DoneMoments {
    /** A group was created: the first named group (the count after the create is exactly 1) is the big overlay with
     *  its detail; any other count, or a failed read (null), is the toast. Never big without proof. */
    fun groupCreated(namedCountAfter: Int?, name: String, detailParts: List<String>): DoneMoment =
        if (namedCountAfter == 1) {
            DoneMoment.Big(BigMoment(BigKind.FIRST_GROUP, DoneCopy.GROUP_CREATED, listOf(name) + detailParts))
        } else {
            DoneMoment.Small(DoneLine(DoneCopy.GROUP_CREATED, listOf(name)))
        }

    /** Design 7: an everyday moment (the toast): [title], one of [DoneCopy]'s words, then the parts that are there. A
     *  missing or blank part is left out, never shown empty, so a note-less expense reads "Expense added ᛫ 2,400". */
    fun line(title: String, vararg parts: String?): DoneMoment = DoneMoment.Small(DoneLine(title, kept(parts.toList())))

    /** The parts that are there: a missing or blank one is left out. */
    private fun kept(parts: List<String?>): List<String> = parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }

    /** D-247: an everyday moment that offers one action ([action], its label), shown for [seconds] (the hold's window).
     *  Its own name (never an overload of [line], whose parts a second string would otherwise become). The twin of
     *  iOS's `line(_:action:seconds:)`. */
    fun lineWithAction(
        title: String,
        action: String,
        seconds: Double = DoneCenter.ACTION_SECONDS,
        /** D-248 (U30.4): the line's parts (a deleted expense's description), left out when missing or blank. */
        parts: List<String?> = emptyList(),
    ): DoneMoment = DoneMoment.Small(DoneLine(title, kept(parts), action, seconds))

    /** The toast's time on screen: [ToastMotion.TOTAL], or, with an action, the line's [DoneLine.actionSeconds] (the
     *  action's whole window, so it never goes before what it offers does). The twin of iOS's `toastSeconds`. */
    fun toastSeconds(line: DoneLine): Double = if (line.action == null) ToastMotion.TOTAL else line.actionSeconds

    /** Design 7: an expense added (or, [edited], saved): its description and core's amount. */
    fun expense(edited: Boolean, note: String?, paidMinor: Long, currency: String): DoneMoment =
        line(if (edited) DoneCopy.EXPENSE_SAVED else DoneCopy.EXPENSE_ADDED, note, amount(paidMinor, currency))

    /** A moment's amount, from core's `ffiMoneyFormat` in the app's locale (ADR-0002: never built here); null, and so
     *  left out of the line, when core cannot say. */
    fun amount(minor: Long, currency: String): String? =
        runCatching { uniffi.skipti_core.ffiMoneyFormat(minor, currency, AppLocale.current) }.getOrNull()
}

/** Design 3: the warning's latch, pure: whether a warning plays now and whether it has played for this opening. It
 *  plays as a confirmation opens ([show] turns true) and not again while that one stays open (a recreation); closing
 *  it re-arms the latch. */
object WarningLatch {
    fun next(show: Boolean, played: Boolean): Pair<Boolean, Boolean> = when {
        !show -> false to false
        played -> false to true
        else -> true to true
    }
}

/** Design 3: the warning haptic when a destructive or irreversible confirmation OPENS, never on its final tap. [show]
 *  is the flag that presents it (a dialog's), or true for a confirming screen, which warns when it first appears. Put
 *  beside the confirmation, always composed; kept across a recreation ([WarningLatch]), so a turned phone does not warn
 *  again. The twin of iOS's `.hapticWarning(when:)`. */
@Composable
fun HapticWarning(show: Boolean, latch: MutableState<Boolean>? = null) {
    val view = LocalView.current
    // Fix wave (M5, D24): [latch], when given, is the caller's: a flow whose step screens leave and come back keeps it,
    // so a red-button screen warns once per presentation, not again on Back.
    val own = rememberSaveable { mutableStateOf(false) }
    var played by (latch ?: own)
    LaunchedEffect(show) {
        val (play, next) = WarningLatch.next(show, played)
        played = next
        if (play) Haptics.play(view, HapticKind.WARNING)
    }
}

/** Design 5: where the toast's bottom edge sits: `toastClearOfBar` above the tab bar row's bottom while the bar shows
 *  (clear of the tallest + face), plus [ROW_EXCESS], else `toastInset` above the navigation bar's inset. */
object ToastPlace {
    /** Fix wave (F5): Android's bar row is 6 dp taller than iOS's (the 64 footprint and 6 above and below it, 76 against
     *  70, D-219 decision h), so its + faces sit 6 higher above the row's bottom than art.py's iOS figures; the toast
     *  rises by the same, and its gaps to every face are iOS's (Norse's diamond clears by 8.75, art.py's 8 or more). */
    const val ROW_EXCESS = 6f

    fun bottom(barShown: Boolean): Float =
        if (barShown) ThemeArt.m("toastClearOfBar") + ROW_EXCESS else ThemeArt.m("toastInset")
}

/** Fix wave (F6): the shell's toast never draws over a screen iOS presents as a sheet or a cover over the shell, where
 *  the sheet covers iOS's toast (its time runs on underneath; it shows again if the sheet closes inside it). A route
 *  that is such a screen, or a screen inside a route that is one ([CoversShellToast]), holds the cover while composed. */
object ToastCover {
    /** Android's routes that iOS presents as a sheet: Add and edit expense (the + and the list's and detail's Edit),
     *  New group, Your name, the username step, an invitation's join, a pushed expense, the invitations from a push and
     *  every removal (iOS's person and review sheets). Pushed screens (Profile, People, an expense's detail, Join with
     *  a link, settings) keep the toast, as iOS's stacks do. */
    fun covers(route: String?): Boolean {
        if (route == null) return false
        if (route in setOf("add", "new-group", "new-group/you", "your-name", "username", "username/you", "username/find-me", "join-invite", "invitations")) return true
        return listOf("expense-edit/", "pushed/", "username/change/", "remove/").any { route.startsWith(it) }
    }

    private var holds by mutableIntStateOf(0)

    /** A cover is composed: the app's host draws no toast. */
    val covered: Boolean get() = holds > 0

    /** Follow-up (A-I1, SPEC 3.7, D-163): the app's host draws no toast while the app is [locked] (iOS's lock and
     *  privacy cover sit over everything) or a cover is composed. */
    fun hides(locked: Boolean): Boolean = locked || covered

    /** Follow-up (A-I1): the host's live region holds the moment's [spoken] words only while one is [showing] and the
     *  app is not [locked], so TalkBack cannot reach them on the lock screen (they were announced at the post). */
    fun liveText(spoken: String, showing: Boolean, locked: Boolean): String = if (showing && !locked) spoken else ""

    /** Follow-up (A-M3): whether the screen a push [opened] ([PushCover]) still shows, one iOS presents as a sheet over
     *  the shell: Your phones while the route is "devices"; a group's page while that group is open over Balance (a
     *  screen opened from it included, as iOS's sheet holds its own stack). */
    fun pushed(opened: String?, route: String?, group: String?, onBalance: Boolean): Boolean = when {
        opened == null -> false
        opened == PushCover.PHONES -> route == "devices"
        opened.startsWith(PushCover.GROUP) -> onBalance && group != null && group == opened.removePrefix(PushCover.GROUP)
        else -> false
    }

    fun hold() {
        holds += 1
    }

    fun release() {
        holds = maxOf(0, holds - 1)
    }
}

/** Follow-up (A-M3): the screens a tapped push opens that iOS presents as sheets (SkiptiApp.swift's push sheets), as the
 *  shell remembers them until they close. */
object PushCover {
    const val PHONES = "devices"
    const val GROUP = "group/"

    fun group(id: String): String = GROUP + id
}

/** Fix wave (F6): holds [ToastCover] while composed; called by each screen iOS presents as a sheet over the shell. */
@Composable
fun CoversShellToast() {
    DisposableEffect(Unit) {
        ToastCover.hold()
        onDispose { ToastCover.release() }
    }
}

/** Design 6, 11, fix wave (F2, D23; F4): the done overlay's dim, as iOS draws it: iOS lowers the opacity of the whole
 *  page, its ground included, over its window (the system background: white, or black when dark), and the bar and the
 *  + stay bright. The same result here is a scrim of the window's colour at 1 - dim over the content and the ground
 *  behind it (content at alpha a over the window equals the window's colour at 1 - a over the content). */
object DoneDim {
    /** The scrim's alpha for the page's opacity [dim]. */
    fun scrim(dim: Float): Float = 1f - dim

    /** The window under iOS's page (the system background), which the dim fades the page towards. */
    fun window(dark: Boolean): androidx.compose.ui.graphics.Color =
        if (dark) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White

    /** The page's opacity [elapsed] seconds into a big moment: E4's [CelebrationTimeline.dim]; a moment that [holds]
     *  (it replaced a big moment still showing, F4) starts at `DIM_TO` instead of ramping in from 1, so the page never
     *  flashes bright between the two, and ramps out at the end as any other. */
    fun level(elapsed: Double, still: Boolean, holds: Boolean): Float =
        CelebrationTimeline.dim(if (holds) maxOf(elapsed, CelebrationTiming.DIM_IN) else elapsed, still)
}

/** Design 4: a moment showing now, one at a time: [post] replaces whatever shows (no queue, so nothing ever waits) and
 *  records when it started on [SystemClock.uptimeMillis]; [clear] removes it. Snapshot state, so a host recomposes. The
 *  app's centre is [DoneCenter]; a screen above the shell (the add-person screen) keeps a local one for its own host. A
 *  small moment's success plays at the post, through the root's view ([DoneCenter.attach]) and the door's one gate. */
open class DoneMomentCenter {
    var current by mutableStateOf<DoneMoment?>(null)
        private set
    var startedAt by mutableLongStateOf(0L)
        private set

    /** Counts posts, so a host can tell a new moment from the same words posted again. */
    var serial by mutableIntStateOf(0)
        private set

    /** Fix wave (F4): this post is a big moment replacing a big one still showing (its overlay holds the dim). */
    var replacing = false
        private set

    /** Fix wave (M6): the serial whose words a host has announced. */
    private var spokenSerial = 0

    /** D-247: what the showing toast's action does ([post]'s `onAction`); null when it offers none. */
    private var onAction: (() -> Unit)? = null

    /** Posts [moment]. On the main thread (T4 review 7): it writes snapshot state, and its haptic plays through a view.
     *  D-247: [onAction], what the toast's action does (a held settle's Undo), run at most once by [act]. */
    fun post(moment: DoneMoment, now: Long = SystemClock.uptimeMillis(), onAction: (() -> Unit)? = null) {
        replacing = moment is DoneMoment.Big && current is DoneMoment.Big
        this.onAction = onAction
        current = moment
        startedAt = now
        serial += 1
        // Design 3: a small moment's success when the toast appears; a big one's when its check lands (DoneCheck).
        if (moment is DoneMoment.Small) DoneCenter.target?.let { Haptics.play(it, HapticKind.SUCCESS, now / 1000.0) }
    }

    /** D-245 (decision 199): the success haptic alone, no moment shown (the welcome's saves: its done row is the done
     *  state). Through the one door, so the switch and the gate apply. The twin of iOS's `DoneCenter.success()`. */
    fun success(now: Long = SystemClock.uptimeMillis()) {
        DoneCenter.target?.let { Haptics.play(it, HapticKind.SUCCESS, now / 1000.0) }
    }

    fun clear() {
        current = null
        onAction = null
    }

    /** Clears the moment numbered [serial] only if it is still the one showing: a newer moment is left alone. */
    fun clear(ifStill: Int) {
        if (serial == ifStill) clear()
    }

    /** D-247: the showing toast's action, once: it runs only for the moment numbered [serial] (a newer post, or a clear,
     *  makes an old tap do nothing). The twin of iOS's `act`. */
    fun act(serial: Int) {
        val line = (current as? DoneMoment.Small)?.line ?: return
        val run = onAction
        if (serial != this.serial || line.action == null || run == null) return
        onAction = null
        run()
    }

    /** D-224 (Design 9), D-247: a celebration starting clears the toast, unless the toast offers an action (a held
     *  settle's Undo stays reachable for its whole window). The twin of iOS's `giveWay`. */
    fun giveWay() {
        if ((current as? DoneMoment.Small)?.line?.action != null) return
        clear()
    }

    /** Fix wave (M6, D24): whether the host asking may announce the moment [serial]: the first claim of the showing
     *  moment wins; a second host composed for it (You's overlay, then the tabs', or a host composed again after a
     *  recreation) and an older moment stay silent. */
    fun claimSpoken(serial: Int): Boolean {
        if (serial != this.serial || serial == spokenSerial) return false
        spokenSerial = serial
        return true
    }
}

/** The app's centre: the root's host shows its small moments, the shell's overlay its big one. */
object DoneCenter : DoneMomentCenter() {
    /** D-247 (decision 182): how long a toast that offers an action stays, the hold's window ([SettleHold]). */
    const val ACTION_SECONDS = 5.0

    /** The root's view, where the moments' haptics play; set by the root's [DoneToastHost] while it is composed. */
    internal var target: HapticTarget? = null
        private set

    fun attach(target: HapticTarget?) {
        this.target = target
    }
}

/** views-extra 2: the one place a moment's start, [DoneMomentCenter.startedAt] in [SystemClock.uptimeMillis], meets the
 *  art's frame clock (D-214: `withFrameNanos`, Choreographer's frame time). Both count CLOCK_MONOTONIC on Android, so a
 *  post's second is its uptime milliseconds over 1000 on the frame clock as well; the poses count from the first frame
 *  that draws the moment, so they start at frame 0 as E4's `CelebrationPlay` does, unless that frame comes later than
 *  [LATE] after the post (a host composed again over a moment already showing), when the post's own second keeps the
 *  moment's time and it is not played again. Pure, so DoneMomentsTest pins it. */
object MomentFrames {
    /** A quarter second: more than the few frames a post takes to reach the screen, less than any part of a moment
     *  worth playing twice. */
    const val LATE = 0.25

    /** [ms] of uptime as a second of the frame clock. */
    fun seconds(ms: Long): Double = ms / 1000.0

    /** The frame second a moment posted at [startedAtMs] is 0 s old, given the first frame that draws it. */
    fun origin(startedAtMs: Long, firstFrame: Double): Double {
        val posted = seconds(startedAtMs)
        val lag = firstFrame - posted
        return if (lag >= 0.0 && lag <= LATE) firstFrame else posted
    }

    /** How long a moment [age] seconds old waits for a haptic due [delay] seconds in; null when it is past (a check
     *  composed again after it landed plays no second success). */
    fun hapticWait(delay: Double, age: Double): Double? = if (age > delay + LATE) null else maxOf(0.0, delay - age)

    /** What is left, in milliseconds, of a moment [total] seconds long posted at [startedAtMs], at [nowMs]. */
    fun remainingMs(startedAtMs: Long, nowMs: Long, total: Double): Long =
        ((total * 1000).toLong() - (nowMs - startedAtMs)).coerceAtLeast(0L)
}

/** One showing of a moment on the art's frame clock, the twin of E4's `CelebrationPlay`: [origin] is the frame second
 *  it is 0 s old ([MomentFrames.origin]), [now] the frame clock on the shared 30 fps grid ([FrameGate]); still (Reduce
 *  Motion, Less motion, or not resumed; D-239: never battery saver) runs no frames and draws each pose's still frame. Memory only. */
class MomentPlay(
    val startedAtMs: Long,
    val still: Boolean,
    nowMs: Long = SystemClock.uptimeMillis(),
    /** Fix wave (F4): the moment replaced a big one still showing; its dim holds ([DoneDim.level]). */
    val holdsDim: Boolean = false,
) {
    var origin by mutableDoubleStateOf(MomentFrames.seconds(startedAtMs))
        private set
    var now by mutableDoubleStateOf(MomentFrames.seconds(nowMs))
        private set

    /** The moment's age for a pose, or [stillAt] when it does not tick. */
    fun elapsed(stillAt: Double): Double = MomentClock.elapsed(tick = !still, now = now, startedAt = origin, still = stillAt)

    /** Ticks [now] on the grid until the moment is [until] seconds old (forever when infinite). */
    suspend fun run(until: Double) {
        if (still) return
        val gate = FrameGate()
        var first = true
        while (true) {
            val over = withFrameNanos { nanos ->
                val seconds = nanos / 1e9
                val due = gate.due(nanos)
                if (first) {
                    origin = MomentFrames.origin(startedAtMs, seconds)
                    now = seconds
                    first = false
                } else if (due) {
                    now = seconds
                }
                now - origin >= until
            }
            if (over) return
        }
    }
}

/** Design 6: where a big check is shown: a screen the person leaves with a button (signed in, joined, erased), or the
 *  overlay that passes through (the first group). */
enum class DonePresentation { SCREEN, OVERLAY }

/** Design 6 (L49): the big check in D-221's ring box, centred: the circle (accent stroke from 12 o'clock clockwise over
 *  its accent-soft fill; Norse's check fill) and the tick through [DoneCheck.TICK], drawn by the motion engine from
 *  [CheckMotion.pose] (the pop scales the two); in Norse, E4's ring with every rune lit, turning once per
 *  [CheckMotion.RUNE_TURN]. Still: [CheckMotion.STILL_ELAPSED], drawn whole and upright. The success haptic plays when
 *  the check lands ([DoneHapticTiming.delay]): from the screen's appearance, or from the overlay moment's start.
 *  Decorative: the screen's or overlay's words speak for it. The twin of iOS's `DoneCheck`. */
@Composable
fun DoneCheck(presentation: DonePresentation, dark: Boolean, play: MomentPlay? = null) {
    val theme = Appearance.theme
    val art = rememberMotionState()
    val motion = art.moment()
    // A screen's check starts when it first appears (kept across a recreation, so it lands and buzzes once).
    val appeared = rememberSaveable { SystemClock.uptimeMillis() }
    val own = remember(appeared, motion.tick) { MomentPlay(appeared, still = !motion.tick) }
    val p = if (presentation == DonePresentation.OVERLAY && play != null) play else own
    if (p === own) {
        // Restarted when the art rule changes: the ring turns on, or stops where it is, without restarting the moment.
        LaunchedEffect(own, art.tick) {
            own.run(DoneCheck.runFor(theme, artTicks = art.tick))
        }
    }
    val view = LocalView.current
    LaunchedEffect(p.startedAtMs) {
        val age = MomentFrames.seconds(SystemClock.uptimeMillis() - p.startedAtMs)
        val wait = MomentFrames.hapticWait(DoneHapticTiming.delay(big = true, still = p.still), age) ?: return@LaunchedEffect
        delay((wait * 1000).toLong())
        Haptics.play(view, HapticKind.SUCCESS)
    }
    val accent = Tok.accent(dark)
    val fill = if (theme == AppTheme.NORSE) ThemeArt.color("norseCheckFill") else Tok.accentSoft(dark)
    val tick = remember { Path() }
    val trimmed = remember { Path() }
    val measure = remember { PathMeasure() }
    Box(Modifier.size(ThemeArt.m("ringBox").dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (theme == AppTheme.NORSE) Box(
            Modifier.graphicsLayer { rotationZ = CheckMotion.pose(p.elapsed(CheckMotion.STILL_ELAPSED)).degrees.toFloat() },
        ) { RuneRing(1, 1, dark) }
        Box(
            Modifier
                .size(ThemeArt.m("doneCheck").dp)
                // D-239 review (I1): drawn from the moment's own clock ([MomentPlay.run], which ends), never from an art
                // canvas whose loop runs while the screen shows: iOS's check pauses its timeline once landed too.
                .drawBehind {
                    val pose = CheckMotion.pose(p.elapsed(CheckMotion.STILL_ELAPSED))
                    val unit = size.width / ThemeArt.m("doneCheck")
                    val r = ThemeArt.m("doneCircleRadius") * unit
                    scale(pose.scale.toFloat()) {
                        drawCircle(fill, radius = r, center = center)
                        if (pose.circle > 0) drawArc(
                            color = accent,
                            startAngle = -90f,
                            sweepAngle = (360 * pose.circle).toFloat(),
                            useCenter = false,
                            topLeft = Offset(center.x - r, center.y - r),
                            size = Size(2 * r, 2 * r),
                            style = Stroke(width = ThemeArt.m("doneCircleStroke") * unit),
                        )
                        if (pose.tick > 0) {
                            tick.reset()
                            DoneCheck.TICK.forEachIndexed { i, (x, y) ->
                                if (i == 0) tick.moveTo(x.toFloat() * unit, y.toFloat() * unit) else tick.lineTo(x.toFloat() * unit, y.toFloat() * unit)
                            }
                            measure.setPath(tick, false)
                            trimmed.reset()
                            measure.getSegment(0f, measure.length * pose.tick.toFloat(), trimmed, true)
                            drawPath(
                                trimmed, accent,
                                style = Stroke(width = ThemeArt.m("doneTickStroke") * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                },
        )
    }
}

/** Design 5 (L50): the small moment's bar: the `owing` dot holding Lucide's check, then the title and the parts joined
 *  by the theme's separator, at most two lines; `surface` in the theme's panel shape with a `hairline` edge and the +'s
 *  shadow. Its words take no touch. Fix wave (I1): the words are hidden from TalkBack, which hears the moment from its
 *  host's live region (the toast fades in from alpha 0, and TalkBack does not read a region that appears transparent,
 *  E4's S9). D-247: a line that carries an action ([DoneLine.action], a held settle's "Undo") ends in
 *  [DoneToastAction], the toast's one control; [onAction] is what a tap on it does. The twin of iOS's `DoneToast`. */
@Composable
fun DoneToast(line: DoneLine, dark: Boolean, modifier: Modifier = Modifier, onAction: () -> Unit = {}) {
    val shape = ThemeArt.panelShape(Appearance.theme, ThemeArt.m("toastRadius").toInt())
    Row(
        modifier
            .fillMaxWidth()
            .shadow(ThemeArt.m("toastShadowRadius").dp, shape)
            .background(Tok.surface(dark), shape)
            .border(1.dp, Tok.hairline(dark), shape)
            .padding(horizontal = ThemeArt.m("toastPadH").dp, vertical = ThemeArt.m("toastPadV").dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The words: hidden from TalkBack (the host's live region says them), and nothing in them takes a touch.
        Row(Modifier.weight(1f).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(ThemeArt.m("toastDot").dp).background(Tok.owing(dark), CircleShape).clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Icon(TabIcons.ToastCheck, contentDescription = null, tint = Tok.onAccent(dark), modifier = Modifier.size(ThemeArt.m("toastGlyph").dp))
            }
            Spacer(Modifier.width(ThemeArt.m("toastGap").dp))
            Text(
                Separator.annotated(listOf(line.title) + line.parts),
                style = Type.name(dark, TypeScale.toast.toInt()).copy(color = Tok.text(dark)),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        line.action?.let { DoneToastAction(it, dark, onAction) }
    }
}

/** D-247 (decision 182): the toast's one action, the in-card link's look (`accent` on the toast's surface, the toast's
 *  size), Material's 48 dp touch at least. The only control in this file; it never waits on anyone: the moment ends on
 *  its clock whether or not it is tapped (GUARANTEES #2). The twin of iOS's `DoneToastAction`. */
@Composable
fun DoneToastAction(label: String, dark: Boolean, run: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = run,
        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(label, style = Type.label(dark, TypeScale.toast.toInt()).copy(color = Tok.accent(dark)))
    }
}

/** Design 5: where a centre's small moment shows: at the bottom, [ToastMotion.pose]d on the frame clock, `toastInset`
 *  in from each side, its bottom edge [ToastPlace.bottom] above the tab bar row's bottom (or the navigation bar), then
 *  removed when its time is over (only if the same moment still shows). The root's host ([DoneCenter]) lends the root's
 *  view to the centre for the haptics. It fills nothing that takes a touch, so everything under it works. */
@Composable
fun DoneToastHost(barShown: Boolean, center: DoneMomentCenter = DoneCenter) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    if (center === DoneCenter) {
        DisposableEffect(view) {
            DoneCenter.attach(Haptics.target(view))
            onDispose { DoneCenter.attach(null) }
        }
    }
    val serial = center.serial
    val small = center.current as? DoneMoment.Small
    // Fix wave (I1, E4's S9): TalkBack hears the moment from a node that is never transparent and always composed: it
    // starts empty for each moment and is filled once the moment shows, a change of a live region's content, which
    // TalkBack announces; once per moment however many hosts compose it (M6).
    var spoken by remember(serial) { mutableStateOf("") }
    LaunchedEffect(serial) {
        if (small != null && center.claimSpoken(serial)) spoken = DoneCopy.spoken(small.line)
    }
    Box(
        Modifier
            .size(1.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                // Follow-up (A-I1): empty while locked, so TalkBack cannot reach the words on the lock screen.
                contentDescription = ToastCover.liveText(spoken, small != null, AppLock.locked)
            },
    )
    if (small == null) return
    val motion = rememberMotionState().moment()
    val play = remember(serial, motion.tick) { MomentPlay(center.startedAt, still = !motion.tick) }
    // D-247: a toast that offers an action stays the action's whole window.
    val total = DoneMoments.toastSeconds(small.line)
    LaunchedEffect(play) {
        if (play.still) {
            delay(MomentFrames.remainingMs(play.startedAtMs, SystemClock.uptimeMillis(), total))
        } else {
            play.run(total)
        }
        if (center.serial == serial) center.clear()
    }
    // Fix wave (F6): under a screen iOS presents as a sheet, the app's toast is not drawn; its time runs on. Follow-up
    // (A-I1, SPEC 3.7): nor while the app is locked, as iOS's lock and privacy cover sit over everything.
    if (center === DoneCenter && ToastCover.hides(AppLock.locked)) return
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                start = ThemeArt.m("toastInset").dp,
                end = ThemeArt.m("toastInset").dp,
                bottom = ToastPlace.bottom(barShown).dp,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        DoneToast(
            small.line, dark,
            Modifier.graphicsLayer {
                val pose = ToastMotion.pose(play.elapsed(stillAt = 0.0), play.still, total)
                alpha = pose?.opacity?.toFloat() ?: 0f
                translationY = (pose?.dy ?: 0.0).toFloat().dp.toPx()
            },
            // D-247: the action runs only for this moment (a newer one makes an old tap do nothing).
            onAction = { center.act(serial) },
        )
    }
}

/** Design 6: the play of the big moment showing now in [DoneCenter], or null; a new one per post and per change of the
 *  still inputs (a resumed screen counts from the post's own second, [MomentFrames.origin]). */
@Composable
fun rememberDoneOverlay(motion: MotionState): MomentPlay? {
    val big = DoneCenter.current is DoneMoment.Big
    val serial = DoneCenter.serial
    val startedAt = DoneCenter.startedAt
    val moment = motion.moment()
    return remember(serial, big, moment.tick) {
        if (big) MomentPlay(startedAt, still = !moment.tick, holdsDim = DoneCenter.replacing) else null
    }
}

/** Design 6, 11, fix wave (F2, D23): the page under the overlay dims as iOS's does, ground and content together: the
 *  window's colour ([DoneDim.window]) at [DoneDim.scrim] of the dim drawn over this node's content and over everything
 *  drawn before it (the shell's ground), out to the window's edges (the ground runs under the system bars). The tab
 *  bar and the overlay are drawn after it, so they stay bright; it is a drawing only and takes no touch. */
fun Modifier.doneDim(play: MomentPlay?, dark: Boolean): Modifier = composed {
    val window = DoneDim.window(dark)
    var at by remember { mutableStateOf(Offset.Zero) }
    var whole by remember { mutableStateOf(Size.Zero) }
    onGloballyPositioned { c ->
        var root = c
        while (root.parentLayoutCoordinates != null) root = root.parentLayoutCoordinates!!
        at = c.positionInRoot()
        whole = root.size.toSize()
    }.drawWithContent {
        drawContent()
        val a = play?.let { DoneDim.scrim(DoneDim.level(it.elapsed(stillAt = 0.0), it.still, it.holdsDim)) } ?: 0f
        if (a > 0f) drawRect(window.copy(alpha = a), topLeft = -at, size = if (whole == Size.Zero) size else whole)
    }
}

/** Design 6 (L49): the first group's big moment over the shell's tab content and under its bar: the check and the words
 *  (the title at the display name size, the parts in `text` joined by the theme's separator), centred at
 *  [CelebrationTiming.MESSAGE_TOP] of the content, in and out as E4's words ([CelebrationTimeline.message]), for
 *  [CelebrationTiming.TOTAL], then removed if the same moment still shows. TalkBack hears the words once from a polite
 *  live region that is never transparent (E4's S9). It never takes a touch. The twin of iOS's `DoneOverlay`. */
@Composable
fun DoneOverlay(play: MomentPlay?, dark: Boolean) {
    val big = DoneCenter.current as? DoneMoment.Big
    if (play == null || big == null) return
    val serial = DoneCenter.serial
    var spoken by remember(serial) { mutableStateOf("") }
    // Fix wave (M6, D24): once per moment, though You's overlay and then the tabs' may both compose it.
    LaunchedEffect(serial) { if (DoneCenter.claimSpoken(serial)) spoken = DoneCopy.spoken(big) }
    LaunchedEffect(play) {
        if (play.still) {
            delay(MomentFrames.remainingMs(play.startedAtMs, SystemClock.uptimeMillis(), CelebrationTiming.TOTAL))
        } else {
            play.run(CelebrationTiming.TOTAL)
        }
        if (DoneCenter.serial == serial) DoneCenter.clear()
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = spoken
                },
        )
        Layout(
            content = {
                Column(
                    Modifier
                        .padding(horizontal = 24.dp)
                        .graphicsLayer {
                            val m = CelebrationTimeline.message(play.elapsed(stillAt = 0.0), play.still)
                            alpha = m.alpha
                            translationY = m.rise.dp.toPx()
                        }
                        .clearAndSetSemantics {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DoneCheck(DonePresentation.OVERLAY, dark, play)
                    Text(
                        big.title,
                        style = Type.display(dark, 700).copy(fontSize = TypeScale.displayName.sp, color = Tok.text(dark)),
                        textAlign = TextAlign.Center,
                    )
                    if (big.parts.isNotEmpty()) Text(
                        Separator.annotated(big.parts),
                        style = Type.body(dark).copy(fontSize = TypeScale.detail.sp, color = Tok.text(dark)),
                        textAlign = TextAlign.Center,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val words = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                words.place(
                    (constraints.maxWidth - words.width) / 2,
                    (constraints.maxHeight * CelebrationTiming.MESSAGE_TOP - words.height / 2.0).roundToInt(),
                )
            }
        }
    }
}
