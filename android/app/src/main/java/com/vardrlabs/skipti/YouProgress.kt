package com.vardrlabs.skipti

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * D-221 (E4, L38 to L41; SPEC's completion ring, GUARANTEES #5): You's setup steps, the next one, the ring's count and
 * their words. Every step is a fact the phone already holds (no new stored state); this file holds the pure decisions
 * and the copy, the twin of iOS's YouProgress.swift (YouProgressTest reads it).
 */
enum class SetupStep { NAME, PICTURE, CURRENCY, APP_LOCK, FIRST_GROUP, USERNAME, NOTIFICATIONS }

/** What the phone holds, read where You loads: your name and picture (vault), the stored default currency
 *  (preference), the app lock's setting and whether the phone has a lock, whether you are in a group (vault; a
 *  read-only copy counts), and, signed in only, your username and whether SKIPTI may post notifications
 *  (`areNotificationsEnabled()` and `Push.canPost`). */
data class SetupFacts(
    val signedIn: Boolean = false,
    val hasName: Boolean = false,
    val hasPicture: Boolean = false,
    val storedCurrency: String? = null,
    val lockTimeout: LockTimeout = LockTimeout.STANDARD,
    val lockAvailable: Boolean = false,
    val hasGroup: Boolean = false,
    val hasUsername: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val canPost: Boolean = false,
) {
    companion object {
        /** D-221: the facts as this phone holds them. Reads the vault: call it off the main thread. [hasUsername] is
         *  what You already knows of the account's username (signed in only); the stored currency and the app lock's
         *  setting are the app's own state (`DefaultCurrency`, `AppLock`), so a change there reads again. */
        fun read(context: Context, store: LedgerStore, hasUsername: Boolean = false): SetupFacts {
            val signedIn = Account.isSignedIn()
            return SetupFacts(
                signedIn = signedIn,
                hasName = runCatching { store.loadSelf()?.name }.getOrNull() != null,
                hasPicture = runCatching { store.loadAvatar() }.getOrDefault(Avatar.None) != Avatar.None,
                storedCurrency = DefaultCurrency.stored,
                lockTimeout = AppLock.timeout,
                // D-247: the look's surface only (`--groups-proof --settle-look ring-nolock`, debuggable builds).
                lockAvailable = AppLock.available(context) && !SettleLook.noLock(),
                hasGroup = runCatching { store.namedGroups().isNotEmpty() }.getOrDefault(false),
                hasUsername = signedIn && hasUsername,
                notificationsEnabled = signedIn && NotificationManagerCompat.from(context).areNotificationsEnabled(),
                canPost = signedIn && Push.canPost(context),
            )
        }
    }
}

object SetupSteps {
    /** Without an account five steps, in this order; signed in adds the username and notifications. D-247 (decision 169,
     *  D-221 h): a phone that cannot lock ([lockAvailable] false: no screen lock, `AppLock.available`, read again on every
     *  resume) has no app lock step, so its ring can complete ("4 of 4"); it comes back once the phone has a lock. */
    fun steps(signedIn: Boolean, lockAvailable: Boolean): List<SetupStep> {
        val base = listOf(SetupStep.NAME, SetupStep.PICTURE, SetupStep.CURRENCY, SetupStep.APP_LOCK, SetupStep.FIRST_GROUP)
            .filter { it != SetupStep.APP_LOCK || lockAvailable }
        return if (signedIn) base + listOf(SetupStep.USERNAME, SetupStep.NOTIFICATIONS) else base
    }

    /** The facts' own steps. */
    fun steps(facts: SetupFacts): List<SetupStep> = steps(facts.signedIn, facts.lockAvailable)

    /** Whether one step is done by these facts. */
    fun isDone(step: SetupStep, facts: SetupFacts): Boolean = when (step) {
        SetupStep.NAME -> facts.hasName
        SetupStep.PICTURE -> facts.hasPicture
        // A stored code core knows; unset does not count, though it resolves to the region's currency.
        SetupStep.CURRENCY -> facts.storedCurrency?.let { it in DefaultCurrency.all } == true
        // The default (two minutes) counts on a phone with a lock; off, or no lock, does not.
        SetupStep.APP_LOCK -> AppLockRules.lockedAtLaunch(facts.lockTimeout, facts.lockAvailable)
        SetupStep.FIRST_GROUP -> facts.hasGroup
        SetupStep.USERNAME -> facts.hasUsername
        SetupStep.NOTIFICATIONS -> facts.notificationsEnabled && facts.canPost
    }

    /** The done steps of [steps] for these facts. */
    fun done(facts: SetupFacts): Set<SetupStep> = steps(facts).filter { isDone(it, facts) }.toSet()

    /** The first undone step in order, passing over [skipping] (the username step while the username card shows). */
    fun next(facts: SetupFacts, skipping: Set<SetupStep> = emptySet()): SetupStep? =
        steps(facts).firstOrNull { it !in skipping && !isDone(it, facts) }

    /** (done, total). */
    fun progress(facts: SetupFacts): Pair<Int, Int> = done(facts).size to steps(facts).size
}

/** Where "Do it" goes: name and picture, Profile; currency, Your data; app lock, Security; a first group, the
 *  create-group screen; the username, the username sheet; notifications, Profile and account's notifications row. */
enum class StepPlace {
    PROFILE, DATA, SECURITY, CREATE_GROUP, USERNAME, PROFILE_ACCOUNT;

    companion object {
        fun of(step: SetupStep): StepPlace = when (step) {
            SetupStep.NAME, SetupStep.PICTURE -> PROFILE
            SetupStep.CURRENCY -> DATA
            SetupStep.APP_LOCK -> SECURITY
            SetupStep.FIRST_GROUP -> CREATE_GROUP
            SetupStep.USERNAME -> USERNAME
            SetupStep.NOTIFICATIONS -> PROFILE_ACCOUNT
        }
    }
}

/** The steps' words; no step names signing in or an account (GUARANTEES #5). The twin of iOS's `StepCopy`. */
object StepCopy {
    const val DO_IT = "Do it"

    fun title(step: SetupStep): String = when (step) {
        SetupStep.NAME -> "Add your name"
        SetupStep.PICTURE -> "Add a picture"
        SetupStep.CURRENCY -> "Choose your currency"
        SetupStep.APP_LOCK -> "Set an app lock"
        SetupStep.FIRST_GROUP -> "Start a group"
        SetupStep.USERNAME -> "Pick your username"
        SetupStep.NOTIFICATIONS -> "Turn on notifications"
    }

    fun why(step: SetupStep): String = when (step) {
        SetupStep.NAME -> "Shown on Balance and in your groups."
        SetupStep.PICTURE -> "A photo or an emoji beside your name."
        SetupStep.CURRENCY -> "New groups and expenses start in it."
        SetupStep.APP_LOCK -> "Your phone's lock before anyone sees your ledger."
        SetupStep.FIRST_GROUP -> "Split a trip, a flat or a dinner."
        SetupStep.USERNAME -> "People can find and add you by it."
        SetupStep.NOTIFICATIONS -> "Hear when your groups add an expense."
    }

    fun pill(done: Int, total: Int): String = "$done of $total done"
}

/** Norse's ring of the 24 Futhark runes: how many are lit for [done] of [total] steps, the Look Book's
 *  `Math.round(24 x done / total)` (half up) in integers. */
object RuneRing {
    fun lit(done: Int, total: Int): Int = if (total <= 0) 0 else (48 * done + total) / (2 * total)

    /** D-250: done of total as a share of the ring (0 with no steps, never past the whole). The twin of iOS's
     *  `RuneRing.share(done:total:)`. */
    fun share(done: Int, total: Int): Double = if (total <= 0) 0.0 else done.coerceIn(0, total).toDouble() / total

    /** D-250: the runes lit at a share between two counts while the ring sweeps ([rememberFill]): the Look Book's half-up
     *  rounding of 24 times the share. At rest the integer form ([lit]) decides, so no float ever picks a resting ring.
     *  The twin of iOS's `RuneRing.lit(share:)`. */
    fun lit(share: Double): Int = kotlin.math.floor(Runes.FUTHARK.size * share.coerceIn(0.0, 1.0) + 0.5 + 1e-9).toInt()

    /** D-250: what the ring lights at [fill]: at rest (the share of [done] of [total]) the count's own rounding, mid-sweep
     *  the share's. */
    fun litAt(fill: Double, done: Int, total: Int): Int = if (fill == share(done, total)) lit(done, total) else lit(fill)
}

/** D-221 (Design 4, L38): Aurora's and Vardr Labs' ring round You's picture: the `ringTrack` circle and the `ring` arc
 *  from 12 o'clock clockwise over [done] of [total], round caps; no arc at 0, the whole circle at [total]. Decorative:
 *  hidden from TalkBack (the pill carries the count). The twin of iOS's `CompletionRing`. */
@Composable
fun CompletionRing(done: Int, total: Int, dark: Boolean, sweep: Double = RuneRing.share(done, total)) {
    val radius = ThemeArt.m("ringRadius")
    val stroke = ThemeArt.m("ringStroke")
    val track = Tok.ringTrack(dark)
    val fill = Tok.ring(dark)
    Canvas(Modifier.size(ThemeArt.m("ringBox").dp).clearAndSetSemantics {}) {
        val r = radius.dp.toPx()
        val width = stroke.dp.toPx()
        drawCircle(track, radius = r, center = center, style = Stroke(width = width))
        if (total > 0 && done > 0) {
            drawArc(
                color = fill,
                startAngle = -90f,
                // D-250: the share drawn ([rememberFill] sweeps it between two counts; done of total at rest).
                sweepAngle = 360f * sweep.coerceIn(0.0, 1.0).toFloat(),
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(2 * r, 2 * r),
                style = Stroke(width = width, cap = StrokeCap.Round),
            )
        }
    }
}

/** D-221 (Design 4, L39): Norse's ring, the 24 runes of the Elder Futhark in order, centred on `runeRingRadius`, 15
 *  degrees apart from 12 o'clock clockwise, each turned by its angle (tops outward), in the rune face at
 *  `runeRingSize` (fixed, as every rune drawn as art); the first [RuneRing.lit] in `ring` (gold), the rest `runeUnlit`.
 *  D-246: each lit rune over a static glow, its own shape blurred ([GlyphGlow]'s cached mask, built once) in `ring` at
 *  [RingTurn.LIT_GLOW], drawn behind the rune on the rune's own turned layer with its glyph on the rune's (D-243's disc
 *  halo is gone); [modifier] lets You's hero turn the ring ([artTurn]); the done check turns it on its moment's own
 *  clock. Decorative: hidden from TalkBack. The twin of iOS's `RuneRing` view. */
@Composable
fun RuneRing(done: Int, total: Int, dark: Boolean, modifier: Modifier = Modifier, sweep: Double = RuneRing.share(done, total)) {
    val density = LocalDensity.current
    // D-250: at rest the count's own rounding; mid-sweep the share's, so the runes light one by one as it passes them.
    val lit = RuneRing.litAt(sweep, done, total)
    val radius = ThemeArt.m("runeRingRadius")
    val style = Type.rune(runeSize("runeRingSize", density))
    val ring = Tok.ring(dark)
    // D-246: the lit runes' glows, GlyphGlow's masks at the ring's rune size (built once a process), one tint, and each
    // rune's baseline from its own layout (its glow's glyph goes on it).
    val raster = rememberGlyphRaster()
    val glows = remember(raster, lit) {
        List(lit.coerceAtMost(Runes.FUTHARK.size)) { GlyphGlow.mask(Runes.FUTHARK[it], ThemeArt.m("runeRingSize").toDouble(), RuneGlow.BLUR, raster) }
    }
    val tint = remember(ring) { ColorFilter.tint(ring) }
    val baselines = remember { FloatArray(Runes.FUTHARK.size) }
    Box(modifier.size(ThemeArt.m("ringBox").dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Runes.FUTHARK.forEachIndexed { i, rune ->
            val angle = i * 360f / Runes.FUTHARK.size
            val rad = Math.toRadians(angle.toDouble())
            Text(
                rune,
                style = style.copy(color = if (i < lit) Tok.ring(dark) else ThemeArt.color("runeUnlit")),
                onTextLayout = { baselines[i] = it.firstBaseline },
                modifier = Modifier
                    .offset {
                        val r = radius.dp.toPx()
                        IntOffset((r * sin(rad)).roundToInt(), (-r * cos(rad)).roundToInt())
                    }
                    .rotate(angle)
                    .then(
                        if (i < glows.size) {
                            Modifier.drawBehind { with(ArtDraw) { glyphGlow(glows[i], 0f, baselines[i], RingTurn.LIT_GLOW.toFloat(), tint) } }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/** D-221 (Design 5, L40; Q11 a, in "Edit"'s place): "6 of 7 done" on C2's accent pill on the page, in the label face
 *  at the pill's size, padding 12 / 6 (the hero chips'); Vardr Labs alone leads it with a `progressDot` circle in
 *  `ring`, `progressDotGap` from the words. The twin of iOS's `ProgressPill` view. */
@Composable
fun ProgressPill(done: Int, total: Int, dark: Boolean) {
    Row(
        Modifier
            .themePill(PillTone.ACCENT, dark, onPage = true)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ProgressPill.showsDot(Appearance.theme)) {
            Box(Modifier.size(ThemeArt.m("progressDot").dp).background(Tok.ring(dark), CircleShape))
            Spacer(Modifier.width(ThemeArt.m("progressDotGap").dp))
        }
        // D-250: the count rolls as a step is done (iOS's `.moneyChange(Int64(done))`).
        MoneyText(
            StepCopy.pill(done, total),
            done.toLong(),
            style = Type.label(dark).copy(color = ThemePills.text(PillTone.ACCENT, dark), fontSize = TypeScale.pill.sp),
        )
    }
}
