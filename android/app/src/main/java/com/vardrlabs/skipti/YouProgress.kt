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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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
                lockAvailable = AppLock.available(context),
                hasGroup = runCatching { store.namedGroups().isNotEmpty() }.getOrDefault(false),
                hasUsername = signedIn && hasUsername,
                notificationsEnabled = signedIn && NotificationManagerCompat.from(context).areNotificationsEnabled(),
                canPost = signedIn && Push.canPost(context),
            )
        }
    }
}

object SetupSteps {
    /** Without an account five steps, in this order; signed in adds the username and notifications. */
    fun steps(signedIn: Boolean): List<SetupStep> {
        val base = listOf(SetupStep.NAME, SetupStep.PICTURE, SetupStep.CURRENCY, SetupStep.APP_LOCK, SetupStep.FIRST_GROUP)
        return if (signedIn) base + listOf(SetupStep.USERNAME, SetupStep.NOTIFICATIONS) else base
    }

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
    fun done(facts: SetupFacts): Set<SetupStep> = steps(facts.signedIn).filter { isDone(it, facts) }.toSet()

    /** The first undone step in order, passing over [skipping] (the username step while the username card shows). */
    fun next(facts: SetupFacts, skipping: Set<SetupStep> = emptySet()): SetupStep? =
        steps(facts.signedIn).firstOrNull { it !in skipping && !isDone(it, facts) }

    /** (done, total). */
    fun progress(facts: SetupFacts): Pair<Int, Int> = done(facts).size to steps(facts.signedIn).size
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
}

/** D-221 (Design 4, L38): Aurora's and Vardr Labs' ring round You's picture: the `ringTrack` circle and the `ring` arc
 *  from 12 o'clock clockwise over [done] of [total], round caps; no arc at 0, the whole circle at [total]. Decorative:
 *  hidden from TalkBack (the pill carries the count). The twin of iOS's `CompletionRing`. */
@Composable
fun CompletionRing(done: Int, total: Int, dark: Boolean) {
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
                sweepAngle = 360f * done.coerceAtMost(total) / total,
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
 *  D-243: each lit rune over a static halo of `ring` at [RingTurn.LIT_HALO], clear at [RingTurn.LIT_HALO_RADIUS] x the
 *  rune's size, centred on the rune's box (one remembered unit brush, drawn behind the runes); [modifier] lets You's
 *  hero turn the ring ([artTurn]); the done check turns it on its moment's own clock. Decorative: hidden from TalkBack.
 *  The twin of iOS's `RuneRing` view. */
@Composable
fun RuneRing(done: Int, total: Int, dark: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val lit = RuneRing.lit(done, total)
    val radius = ThemeArt.m("runeRingRadius")
    val style = Type.rune(runeSize("runeRingSize", density))
    val ring = Tok.ring(dark)
    val halo = remember(ring) { Brush.radialGradient(listOf(ring, ring.copy(alpha = 0f)), center = Offset.Zero, radius = 1f) }
    val lights = Modifier.drawBehind {
        val r = radius.dp.toPx()
        val disc = (RingTurn.LIT_HALO_RADIUS * ThemeArt.m("runeRingSize")).toFloat().dp.toPx()
        for (i in 0 until lit.coerceAtMost(Runes.FUTHARK.size)) {
            val rad = Math.toRadians(i * 360.0 / Runes.FUTHARK.size)
            withTransform({
                translate(center.x + (r * sin(rad)).roundToInt(), center.y + (-r * cos(rad)).roundToInt())
                scale(disc, disc, Offset.Zero)
            }) { drawCircle(halo, radius = 1f, center = Offset.Zero, alpha = RingTurn.LIT_HALO.toFloat()) }
        }
    }
    Box(modifier.size(ThemeArt.m("ringBox").dp).clearAndSetSemantics {}.then(lights), contentAlignment = Alignment.Center) {
        Runes.FUTHARK.forEachIndexed { i, rune ->
            val angle = i * 360f / Runes.FUTHARK.size
            val rad = Math.toRadians(angle.toDouble())
            Text(
                rune,
                style = style.copy(color = if (i < lit) Tok.ring(dark) else ThemeArt.color("runeUnlit")),
                modifier = Modifier
                    .offset {
                        val r = radius.dp.toPx()
                        IntOffset((r * sin(rad)).roundToInt(), (-r * cos(rad)).roundToInt())
                    }
                    .rotate(angle),
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
        Text(
            StepCopy.pill(done, total),
            style = Type.label(dark).copy(color = ThemePills.text(PillTone.ACCENT, dark), fontSize = TypeScale.pill.sp),
        )
    }
}
