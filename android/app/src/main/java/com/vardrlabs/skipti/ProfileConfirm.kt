package com.vardrlabs.skipti

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.skipti_core.ffiMemberName

// D-245 (U17, decisions 161, 178, 179): the name and picture confirm, shared by You's Profile page and the welcome's
// Your name and Your picture steps. A picture is STAGED by a tap (nothing written) and written once when confirmed
// ("Use this picture"); a name is checked in words before core sees it. The twin of iOS's ProfileConfirm.swift.

/** What is wrong with a typed name, in words (decision 179: never core's raw text). */
enum class NameProblem {
    EMPTY, TOO_LONG, BAD_CHARACTER;

    val message: String
        get() = when (this) {
            EMPTY -> WelcomeCopy.ERR_EMPTY
            TOO_LONG -> WelcomeCopy.ERR_LONG
            BAD_CHARACTER -> WelcomeCopy.ERR_CHAR
        }
}

/** D-245 (decision 179): the Your name field's rules. A name is trimmed, at least one character, and at most
 *  [CloudProfile.NAME_MAX] (60, the rules' `shortText(name, 60)`) counted in UTF-16 code units, as
 *  `ProfileShare.shareable` counts it, so a saved name is always one the groups can be sent. Longer is refused, never
 *  cut. Anything else core's `ffiMemberName` refuses (a control character) is [NameProblem.BAD_CHARACTER]. Core's 64
 *  for member names is unchanged. The twin of iOS's `NameRule`. */
object NameRule {
    val MAX: Int get() = CloudProfile.NAME_MAX

    /** Swift's `.whitespacesAndNewlines` trim. */
    fun trimmed(raw: String): String = raw.trim()

    fun check(raw: String): NameProblem? {
        val name = trimmed(raw)
        if (name.isEmpty()) return NameProblem.EMPTY
        if (name.length > MAX) return NameProblem.TOO_LONG
        if (runCatching { ffiMemberName(raw) }.isFailure) return NameProblem.BAD_CHARACTER
        return null
    }

    /** The field differs from the stored name (a blank field never counts as a change to save). */
    fun dirty(raw: String, saved: String?): Boolean {
        val name = trimmed(raw)
        return name.isNotEmpty() && name != (saved ?: "")
    }

    /** What a typed name shows under the field while it is dirty: the problem in words, or nothing. */
    fun liveProblem(raw: String, saved: String?): NameProblem? = if (dirty(raw, saved)) check(raw) else null

    /** Fix wave M1: the keyboard's Done asks for a save unless the field holds the saved name (an empty field still
     *  asks, so it says "Add a name first."). The twin of iOS's `returnSaves`. */
    fun returnSaves(raw: String, saved: String?): Boolean = trimmed(raw).isEmpty() || dirty(raw, saved)
}

/** The picture confirm's two answers. */
enum class PictureAnswer { USE, NOT_NOW }

/** D-245: the one way a name or a picture is saved from Profile and the welcome. Each confirm writes once: one vault
 *  write (`setYourName` / `saveAvatar`), one sync nudge, one profile share request, one toast (or, in the welcome, the
 *  success haptic alone). The effects are a seam so a test can count them. The twin of iOS's `ProfileSave`. Call it
 *  off the main thread (it writes the vault); [done] posts on the main thread through the caller ([Done]). */
object ProfileSave {
    class Effects(
        /** Your other phones (`CloudSync.ledgerChanged`). */
        val changed: () -> Unit,
        /** Your groups (`ProfileShare.request`). */
        val share: () -> Unit,
        /** D-254 look fix 3: an account not approved asks again with the new name (`Approval.nameSaved`), so the admin
         *  page shows it. A saved name only, never a picture. */
        val ask: () -> Unit,
    ) {
        companion object {
            fun live(vaultPath: String) = Effects(
                changed = { CloudSync.ledgerChanged(vaultPath) },
                share = { ProfileShare.request(LedgerStore(vaultPath)) },
                ask = { Approval.nameSaved(LedgerStore(vaultPath)) },
            )
        }
    }

    /** How a save says it is done: Profile's toast (D-224, its success haptic with it), or, inside the welcome, the
     *  success haptic alone (decision 199: the welcome's done row is its done state). */
    enum class Feedback { TOAST, HAPTIC }

    /** The done moment, on the main thread: the top-right picture and the greeting read again, then the toast or the
     *  haptic. */
    fun done(moment: DoneMoment, feedback: Feedback, center: DoneMomentCenter = DoneCenter, now: Long = SystemClock.uptimeMillis()) {
        ProfileState.revision++
        when (feedback) {
            Feedback.TOAST -> center.post(moment, now)
            Feedback.HAPTIC -> center.success(now)
        }
    }

    sealed interface NameOutcome {
        data class Saved(val name: String) : NameOutcome
        data class Refused(val words: String) : NameOutcome
    }

    /** The typed name, checked in words first (nothing is written when it is refused), then saved and shared once.
     *  The vault part: call [done] with [nameMoment] on the main thread after a [NameOutcome.Saved]. */
    fun name(raw: String, store: LedgerStore, effects: Effects): NameOutcome {
        NameRule.check(raw)?.let { return NameOutcome.Refused(it.message) }
        return try {
            val me = store.setYourName(raw)
            effects.changed()
            effects.share()
            effects.ask()
            NameOutcome.Saved(me.name ?: NameRule.trimmed(raw))
        } catch (e: Exception) {
            // Fix wave M4: in words, never the raw error.
            NameOutcome.Refused(WelcomeCopy.NAME_NOT_SAVED)
        }
    }

    /** Fix wave M1: the keyboard's Done. The same save, except that the saved name is not saved again: null, and
     *  nothing is written, nudged or shared. The twin of iOS's `returned`. */
    fun returned(raw: String, saved: String?, store: LedgerStore, effects: Effects): NameOutcome? =
        if (NameRule.returnSaves(raw, saved)) name(raw, store, effects) else null

    val nameMoment: DoneMoment get() = DoneMoments.line(DoneCopy.NAME_SAVED)

    /** A confirmed picture (or the removal, `None`): one `Avatar` record, one share request. Throws what the vault or
     *  core refused; nothing else happens then. Then [done] with [pictureMoment] on the main thread. */
    fun picture(avatar: Avatar, store: LedgerStore, effects: Effects) {
        store.saveAvatar(avatar)
        effects.changed()
        effects.share()
    }

    fun pictureMoment(avatar: Avatar): DoneMoment =
        DoneMoments.line(if (avatar == Avatar.None) DoneCopy.PICTURE_REMOVED else DoneCopy.PICTURE_SAVED)

    /** The stage after an answer, and what was written (null: nothing). */
    data class Answered(val stage: PictureStage, val written: Avatar?)

    /** Fix wave M6: the confirm's answer, the one way Profile's sheet and the welcome's Use reach the vault. Use writes
     *  the staged picture once ([picture]) and records it in the stage; Not now drops the stage and never opens the vault
     *  ([store] is not called). A Use with nothing staged writes nothing. Throws what [picture] throws. Call a Use off the
     *  main thread, then [done] with [pictureMoment] on it. The twin of iOS's `answer`. */
    fun answer(answer: PictureAnswer, stage: PictureStage, store: () -> LedgerStore, effects: Effects): Answered =
        when (answer) {
            PictureAnswer.NOT_NOW -> Answered(stage.notNow(), null)
            PictureAnswer.USE -> {
                val picked = stage.staged
                if (picked == null) {
                    Answered(stage, null)
                } else {
                    picture(picked, store(), effects)
                    Answered(stage.used(picked), picked)
                }
            }
        }
}

/** D-245 (decision 161): a picture tapped or picked waits here until it is confirmed. [choose] only stages (nothing is
 *  written); [notNow] drops it; [used] records what was written and the done row's words, which stay until the next
 *  choice. The twin of iOS's `PictureStage`. */
data class PictureStage(val staged: Avatar? = null, val done: String? = null) {
    fun choose(avatar: Avatar) = PictureStage(staged = avatar, done = null)
    fun notNow() = copy(staged = null)
    fun used(avatar: Avatar) = PictureStage(staged = null, done = if (avatar == Avatar.None) DoneCopy.PICTURE_REMOVED else DoneCopy.PICTURE_SAVED)
}

/** D-245: a done row ("Name saved", "Picture saved"): the toast's owing dot holding Lucide's check, then the words. It
 *  stays until what it confirms changes. Read as one node. The twin of iOS's `DoneRow`. */
@Composable
fun DoneRow(text: String, dark: Boolean) {
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(ThemeArt.m("toastDot").dp).background(Tok.owing(dark), CircleShape).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Icon(TabIcons.ToastCheck, contentDescription = null, tint = Tok.onAccent(dark), modifier = Modifier.size(ThemeArt.m("toastGlyph").dp))
        }
        Spacer(Modifier.width(ThemeArt.m("toastGap").dp))
        Text(text, style = Type.name(dark, TypeScale.detail.toInt()))
    }
}

/** D-245 (decisions 161, 178): the confirm's content: the big preview of the picture (staged or saved), its done row,
 *  and signed in only the line that the groups see a small copy. One view, two presentations: You's sheet (with its
 *  two buttons, [PictureConfirmSheet]) and the welcome's Your picture page (with the choices under it). */
object PictureConfirmLayout {
    const val PREVIEW_SIZE = 104
}

@Composable
fun PictureConfirm(avatar: Avatar, name: String?, signedIn: Boolean, dark: Boolean, done: String? = null, choices: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AvatarView(avatar, name, PictureConfirmLayout.PREVIEW_SIZE.dp, dark)
        if (done != null) DoneRow(done, dark)
        if (signedIn) {
            Text(
                WelcomeCopy.GROUPS_PICTURE,
                style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp),
                textAlign = TextAlign.Center,
            )
        }
        choices()
    }
}

/** D-245 (decision 161): You's picture sheet. "Use this picture" writes once and closes it; "Not now" writes nothing.
 *  A bottom sheet in the app's window style (the person sheet's), its title the bar's, no corner button (iOS's
 *  inline title). Fix wave (twins): the theme's page art fills the sheet behind its content, as iOS's sheet draws
 *  `.auroraBackground()`: the art is the sheet's whole ground, its drag handle drawn on the art and the navigation bar's
 *  inset inside it (the sheet's own container would otherwise show `surface` above and below the art). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PictureConfirmSheet(avatar: Avatar, name: String?, signedIn: Boolean, onUse: () -> Unit, onNotNow: () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onNotNow,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tok.bg(dark),
        dragHandle = null,
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) },
    ) {
        SkiptiWindowLocals {
            val inDark = androidx.compose.foundation.isSystemInDarkTheme()
            Column(Modifier.fillMaxWidth().auroraBackground(inDark).navigationBarsPadding()) {
                androidx.compose.material3.BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                PushedBar(WelcomeCopy.SHEET_TITLE, onBack = null, underStatusBar = false)
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    PictureConfirm(avatar, name, signedIn, inDark)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryAction(WelcomeCopy.USE, inDark, onClick = onUse)
                        SecondaryAction(WelcomeCopy.NOT_NOW, inDark, onClick = onNotNow)
                    }
                }
            }
        }
    }
}

/** D-245 (W4): the welcome's emoji choices, drawn as Profile's grid draws its cells ([EmojiCell], the theme's panel
 *  shape, `emojiGlyph`): the first six and "More" in one row, or, opened, all 48 in `emojiColumns` columns. A tap only
 *  stages. The twin of iOS's `EmojiChoices`. */
object EmojiChoicesLayout {
    /** The first row's emoji (the plan's six). */
    const val FIRST_ROW = 6
}

@Composable
fun EmojiChoices(chosen: Avatar?, open: Boolean, dark: Boolean, onMore: () -> Unit, onTap: (String) -> Unit) {
    val gap = ThemeArt.m("emojiGap").dp
    if (open) {
        val columns = ThemeArt.m("emojiColumns").toInt()
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            EmojiGrid.rows(ProfileRules.EMOJI_CHOICES, columns).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    line.forEach { e -> EmojiChoiceCell(e, chosen == Avatar.Emoji(e), dark, Modifier.weight(1f)) { onTap(e) } }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    } else {
        // Fix wave I3: at the large text sizes "More" outgrows a seventh of the row; it takes its own row (iOS's twin).
        val stacked = TextSize.stacks(LocalDensity.current.fontScale)
        val more: @Composable (Modifier) -> Unit = { modifier ->
            Box(
                modifier
                    .heightIn(min = 44.dp)
                    .clickable(onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    WelcomeCopy.MORE,
                    style = Type.label(dark, TypeScale.detail.toInt()).copy(color = Tok.accent(dark)),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                ProfileRules.EMOJI_CHOICES.take(EmojiChoicesLayout.FIRST_ROW).forEach { e ->
                    EmojiChoiceCell(e, chosen == Avatar.Emoji(e), dark, Modifier.weight(1f)) { onTap(e) }
                }
                if (!stacked) more(Modifier.weight(1f))
            }
            if (stacked) more(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EmojiChoiceCell(e: String, chosen: Boolean, dark: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val cell = ThemeArt.panelShape(Appearance.theme, ThemeArt.m("emojiRadius").toInt())
    val glyph = with(LocalDensity.current) { ThemeArt.m("emojiGlyph").dp.toSp() }
    val paint = EmojiCell.paint(chosen)
    Box(
        modifier
            .aspectRatio(1f)
            .then(paint.fill?.let { Modifier.background(it(dark), cell) } ?: Modifier)
            .then(paint.border?.let { Modifier.border(ThemeArt.m("emojiBorder").dp, it(dark), cell) } ?: Modifier)
            .clip(cell)
            .clickable(onClick = onTap)
            .clearAndSetSemantics { contentDescription = e; selected = chosen },
        contentAlignment = Alignment.Center,
    ) { Text(e, fontSize = glyph) }
}
