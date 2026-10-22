package com.vardrlabs.skipti

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** C2 copy (the approved mock-up, v2). Character for character iOS's
 *  `UsernameCopy` in UsernameStep.swift (UsernameRulesTest compares them). */
object UsernameCopy {
    const val TITLE = "Pick your username"
    const val LEDE = "People find and add you by it. You can change it once later."
    const val YOUR_NAME = "Your name"
    const val NAME_PROMPT = "What friends call you"
    const val USERNAME = "Username"
    const val PROMPT = "yourname"
    const val HINT = "3 to 20 letters, numbers or _"
    const val CHECKING = "Checking…"
    fun available(h: String) = "@$h is available"
    fun taken(h: String) = "@$h is taken. Try one below."
    const val NOT_AVAILABLE = "That username is not available."
    const val SUGGESTED = "Suggested for you"
    const val SAVE = "Save username"
    const val OFFLINE = "Could not reach SKIPTI's cloud. Check your connection and try again."
    fun confirmTitle(h: String) = "Change to @$h?"
    fun confirmBody(new: String, old: String) =
        "You can change your username once. After this, @$new is yours for good, and @$old is free for anyone to take."
    const val CHANGE = "Change username"
    fun keep(h: String) = "Keep @$h"
    const val ONE_CHANGE_LEFT = "1 change left"
    const val CHANGED_ONCE = "Changed once, it stays now"
    const val CARD_TITLE = "Pick your username"
    const val CARD_BODY = "People can find and add you by it. It takes a few seconds."
    const val CARD_ACTION = "Pick username"
    const val CANCEL = "Cancel"
    /** C2 (GUARANTEES #2): the required step's way on after a save failed. */
    const val LATER = "Later"
}

/** The live status under the field. Pure: the JVM tests call it. */
sealed class UsernameStatus {
    data object Hint : UsernameStatus()
    data object Checking : UsernameStatus()
    data class Available(val handle: String) : UsernameStatus()
    data class Taken(val handle: String) : UsernameStatus()
    data object NotAvailable : UsernameStatus()
    data object Offline : UsernameStatus()
    data object Limit : UsernameStatus()
    /** D-254 fix wave I1: the check was refused for approval: the account is waiting, or was not approved ([words]:
     *  the call's approval words). Never the offline line, and Save stays off (it would be refused the same way). iOS's
     *  `.approval(String)`. */
    data class Approval(val words: String) : UsernameStatus()

    companion object {
        /** What the phone can say at once, before any cloud check. `list`:
         *  the blocklist (the bundled one unless a test passes the parsed file). */
        fun local(raw: String, list: UsernameRules.TermList? = UsernameRules.list): UsernameStatus? {
            if (UsernameRules.handle(raw).length < 3) return Hint
            return when (UsernameRules.problem(raw, list)) {
                UsernameRules.Problem.SHAPE -> Hint
                UsernameRules.Problem.UNAVAILABLE -> NotAvailable
                null -> null
            }
        }

        /** The handle Save may send: only an answer the cloud gave for exactly
         *  what the field holds now. A check for an older text never counts. */
        fun acceptedHandle(status: UsernameStatus, text: String): String? =
            (status as? Available)?.handle?.takeIf { it == UsernameRules.handle(text) }

        /** The handle the step (never the change screen) may send: a free
         *  answer for exactly this text, or, when only the live check failed
         *  (offline, the day's limit), what the field holds if the phone finds
         *  nothing wrong with it. The cloud still decides; offline the save
         *  fails and "Later" appears (GUARANTEES #2). Twin of iOS's. */
        fun canSave(status: UsernameStatus, text: String, list: UsernameRules.TermList? = UsernameRules.list): String? {
            acceptedHandle(status, text)?.let { return it }
            return when (status) {
                Offline, Limit -> if (local(text, list) == null) UsernameRules.handle(text) else null
                else -> null
            }
        }

        /** A live check that failed: the day's limit says so; D-254 fix wave I1: a refusal for approval says that;
         *  anything else is the offline line. */
        fun failedCheck(error: Throwable): UsernameStatus {
            ApprovalCopy.failure(error)?.let { return Approval(it) }
            return if ((error as? CloudFunctions.Failure.Refused)?.status == "RESOURCE_EXHAUSTED") Limit else Offline
        }
    }
}

/** C2: why a save failed, and what the screen does about it. Twin of iOS's
 *  `UsernameSaveFailure`. */
sealed class UsernameSaveFailure {
    /** The field says it (taken, not available): pick another. */
    data object Taken : UsernameSaveFailure()
    data object NotAvailable : UsernameSaveFailure()
    /** The cloud refused the step's first set or the one change: a username
     *  exists already (another phone), or the profile is not ready. */
    data object Used : UsernameSaveFailure()
    /** A line under the field (offline, signed out, the day's limit). */
    data class Message(val text: String) : UsernameSaveFailure()

    companion object {
        fun from(error: Throwable): UsernameSaveFailure = when ((error as? CloudFunctions.Failure.Refused)?.status) {
            // D-254: the account is not approved yet (or was not): its words, never the offline line.
            null -> ApprovalCopy.failure(error)?.let(::Message) ?: Message(UsernameCopy.OFFLINE)
            "ALREADY_EXISTS" -> Taken
            "INVALID_ARGUMENT" -> NotAvailable
            "FAILED_PRECONDITION" -> Used
            "RESOURCE_EXHAUSTED" -> Message(PeopleCopy.LIMIT)
            else -> Message(UsernameCopy.OFFLINE)
        }

        /** GUARANTEES #2: the required step offers "Later" only once a save
         *  has failed for a reason the field cannot fix. */
        fun offersLater(failure: UsernameSaveFailure?): Boolean = when (failure) {
            null, Taken, NotAvailable -> false
            Used, is Message -> true
        }
    }
}

/** C2: what the sign-in route shows. A signed-out account never sees the
 *  step (or the confirmation): it is back on the form. Twin of iOS's
 *  `SignInStep`. */
enum class SignInStep {
    FORM, WAITING, STEP, CONFIRMATION;

    companion object {
        fun decide(signedIn: Boolean, needed: Boolean?): SignInStep = when {
            !signedIn -> FORM
            needed == true -> STEP
            needed == false -> CONFIRMATION
            else -> WAITING
        }
    }
}

/** The @username field, its live status and the three suggestions; shared
 *  by the step after sign-in and the change screen. */
@Composable
fun UsernameField(
    text: String,
    onText: (String) -> Unit,
    status: UsernameStatus,
    onStatus: (UsernameStatus) -> Unit,
    suggestions: List<String>,
    dark: Boolean,
) {
    LaunchedEffect(text) {
        UsernameStatus.local(text)?.let {
            onStatus(it)
            return@LaunchedEffect
        }
        onStatus(UsernameStatus.Checking)
        delay(400)
        val h = UsernameRules.handle(text)
        try {
            val answer = People.checkUsername(h)
            // The text changed while the cloud answered: this answer is for
            // an older text, and the newer effect owns the status.
            if (!isActive) return@LaunchedEffect
            onStatus(
                when (answer) {
                    People.UsernameAvailability.AVAILABLE -> UsernameStatus.Available(h)
                    People.UsernameAvailability.TAKEN -> UsernameStatus.Taken(h)
                    People.UsernameAvailability.SHAPE -> UsernameStatus.Hint
                    People.UsernameAvailability.UNAVAILABLE -> UsernameStatus.NotAvailable
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // CloudFunctions turns a cancelled call into Offline: only a live
            // effect may say so.
            if (isActive) onStatus(UsernameStatus.failedCheck(e))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            label = { Text(UsernameCopy.USERNAME) },
            placeholder = { Text(UsernameCopy.PROMPT) },
            prefix = { Text("@", style = Type.body(dark).copy(color = Tok.accent(dark))) },
            singleLine = true,
            textStyle = Type.body(dark),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        val line = Type.body(dark).copy(fontSize = 13.sp)
        when (status) {
            UsernameStatus.Hint -> Text(UsernameCopy.HINT, style = line.copy(color = Tok.textDim(dark)))
            UsernameStatus.Checking -> Text(UsernameCopy.CHECKING, style = line.copy(color = Tok.textDim(dark)))
            is UsernameStatus.Available ->
                Text("✓ " + UsernameCopy.available(status.handle), style = line.copy(color = Tok.owing(dark)))
            is UsernameStatus.Taken -> Text(UsernameCopy.taken(status.handle), style = line.copy(color = Tok.owed(dark)))
            UsernameStatus.NotAvailable -> Text(UsernameCopy.NOT_AVAILABLE, style = line.copy(color = Tok.owed(dark)))
            UsernameStatus.Offline -> Text(UsernameCopy.OFFLINE, style = line.copy(color = Tok.owed(dark)))
            UsernameStatus.Limit -> Text(PeopleCopy.LIMIT, style = line.copy(color = Tok.owed(dark)))
            is UsernameStatus.Approval -> Text(status.words, style = line.copy(color = Tok.owed(dark)))
        }
        if (suggestions.isNotEmpty()) {
            SectionLabel(UsernameCopy.SUGGESTED, dark)
            // A row when the three fit, else one under another (a long name
            // makes long suggestions): iOS's ViewThatFits.
            RowOrStack(spacing = 8) {
                suggestions.forEach { s -> SecondaryAction("@$s", dark, keepsCase = true) { onText(s) } }
            }
        }
    }
}

/** Its children side by side when their natural widths fit (each widened by
 *  an equal share of what is left), else stacked at full width. */
@Composable
private fun RowOrStack(spacing: Int, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val gap = spacing.dp.roundToPx()
        val max = constraints.maxWidth
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val needed = natural.sum() + gap * (measurables.size - 1).coerceAtLeast(0)
        if (constraints.hasBoundedWidth && needed <= max && measurables.isNotEmpty()) {
            val extra = (max - needed) / measurables.size
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(natural[i] + extra)) }
            val height = placeables.maxOf { it.height }
            layout(max, height) {
                var x = 0
                placeables.forEach {
                    it.placeRelative(x, (height - it.height) / 2)
                    x += it.width + gap
                }
            }
        } else {
            val width = if (constraints.hasBoundedWidth) max else natural.maxOrNull() ?: 0
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
            layout(width, height) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(0, y)
                    y += it.height + gap
                }
            }
        }
    }
}

/** A screen of the C2 flow: the bar (Cancel when it can be left, the inline title), a scrolling column at 20 dp (as
 *  iOS's `.padding(20)`), and its one action kept at the bottom (above the keyboard: the root pads for it), as iOS's
 *  `formActionBar`. D-217 Task 8: it was a Cancel text button and a 28 sp title in the column. With no [onCancel] (the
 *  required step inside sign-in) the bar has the title and no button. */
@Composable
private fun UsernameScreen(
    title: String,
    dark: Boolean,
    onCancel: (() -> Unit)?,
    action: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    SheetPage(
        title, dark, onCancel = onCancel, spacing = 14.dp, side = BarDecisions.PAGE_SIDE_WIDE.dp,
        actions = { action() },
    ) { content() }
}

/** C2: right after sign-in, for an account with no username. Required: no
 *  way out there (no Cancel, Back does nothing), but once a save has failed
 *  (offline, the day's limit) "Later" goes on to the usual confirmation and
 *  the card asks later (`onLater`). Asks your name first when this phone has
 *  none. Opened from the card or You (`onCancel` set), it can be left: the
 *  person chose to open it, and it must never stand in the way (GUARANTEES
 *  #2). Twin of iOS's `UsernameStep`. */
@Composable
fun UsernameStep(
    vaultPath: String,
    dark: Boolean,
    onCancel: (() -> Unit)? = null,
    onLater: (() -> Unit)? = null,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // What was typed survives recreation; the status re-derives from it.
    var name by rememberSaveable { mutableStateOf("") }
    var needsName by remember { mutableStateOf(false) }
    var selfName by remember { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<UsernameStatus>(UsernameStatus.Hint) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** Once offered, "Later" stays: a way on never disappears again. */
    var laterOffered by rememberSaveable { mutableStateOf(false) }

    // The step after sign-in has no way out: Back is swallowed here (the
    // shell's own handler would leave the sign-in route).
    BackHandler(enabled = onCancel == null) {}

    LaunchedEffect(vaultPath) {
        selfName = withContext(Dispatchers.IO) {
            runCatching { LedgerStore(vaultPath).loadSelf()?.name }.getOrNull() ?: ""
        }
        needsName = selfName.isEmpty()
    }
    LaunchedEffect(if (needsName) name else "self", selfName) {
        val source = if (needsName) name else selfName
        if (source.isBlank()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        val found = People.suggestUsernames(source)
        if (isActive) suggestions = found
    }

    val accepted = UsernameStatus.canSave(status, text)
    val canSave = accepted != null && (!needsName || name.trim().isNotEmpty())

    fun save() {
        val h = UsernameStatus.canSave(status, text) ?: return
        saving = true
        error = null
        scope.launch {
            try {
                val store = LedgerStore(vaultPath)
                if (needsName) {
                    withContext(Dispatchers.IO) { store.setYourName(name) }
                    CloudSync.ledgerChanged(vaultPath)
                }
                // A None read earlier may be stale (a username picked on
                // another phone since): setUsername would then spend the one
                // change without its confirm. Already set: nothing to pick.
                val now = People.accountUsername()
                if (now != null && now != UsernameState.None) {
                    // D-211: a name saved just now reaches the account's groups.
                    if (needsName) ProfileShare.request(store)
                    onDone()
                    return@launch
                }
                val uid = Account.uid() ?: throw CloudFunctions.Failure.SignedOut()
                // The profile (and its participantId) first: setUsername
                // refuses until it exists, and must never make a half one.
                val version = AboutInfo.current(context).first
                // D-254: through the approval gate (an account not approved registers nothing).
                withContext(Dispatchers.IO) { CloudSync.registration(uid, store, version) }
                // D-211: the name just saved reaches the account's groups.
                if (needsName) ProfileShare.request(store)
                // `first`: never spends the one change. A username set on
                // another phone since the read above is refused, not replaced.
                People.setUsername(h, first = true)
                // D-224 (Design 7): opened from a card or You, the toast says it; inside the sign-in flow the signed-in
                // screen follows instead. A username found already set (another phone) was not saved by this tap.
                if (onCancel != null) DoneCenter.post(DoneMoments.line(DoneCopy.USERNAME_SAVED, PeopleCopy.handle(h)))
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failed = UsernameSaveFailure.from(e)
                if (UsernameSaveFailure.offersLater(failed)) laterOffered = true
                when (failed) {
                    UsernameSaveFailure.Taken -> status = UsernameStatus.Taken(h)
                    UsernameSaveFailure.NotAvailable -> status = UsernameStatus.NotAvailable
                    UsernameSaveFailure.Used -> {
                        // Set on another phone meanwhile: nothing left to pick.
                        val now = People.accountUsername()
                        if (now != null && now != UsernameState.None) onDone() else error = UsernameCopy.OFFLINE
                    }
                    is UsernameSaveFailure.Message -> error = failed.text
                }
            } finally {
                saving = false
            }
        }
    }

    UsernameScreen(
        title = UsernameCopy.TITLE,
        dark = dark,
        onCancel = onCancel,
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryAction(UsernameCopy.SAVE, dark, enabled = !saving && canSave) { save() }
                val later = onLater
                if (later != null && laterOffered) SecondaryAction(UsernameCopy.LATER, dark) { if (!saving) later() }
            }
        },
    ) {
        Text(UsernameCopy.LEDE, style = Type.bodyDim(dark))
        if (needsName) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(UsernameCopy.YOUR_NAME) },
                placeholder = { Text(UsernameCopy.NAME_PROMPT) },
                singleLine = true,
                textStyle = Type.body(dark),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        UsernameField(text, { text = it }, status, { status = it }, suggestions, dark)
        error?.let { Text(it, style = Type.body(dark).copy(color = Tok.owed(dark), fontSize = 14.sp)) }
    }
}

/** C2: the one change, from You. Asks first. `onUsed`: the cloud says the
 *  one change is already spent (another phone); the caller closes this and
 *  reads the account again. Twin of iOS's `UsernameChangeSheet`. */
@Composable
fun UsernameChangeScreen(
    current: String,
    vaultPath: String,
    dark: Boolean,
    onSaved: (String) -> Unit,
    onUsed: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<UsernameStatus>(UsernameStatus.Hint) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirming by remember { mutableStateOf(false) }
    /** The handle the confirm names, captured at the tap: the one sent. */
    var pending by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vaultPath) {
        val name = withContext(Dispatchers.IO) {
            runCatching { LedgerStore(vaultPath).loadSelf()?.name }.getOrNull() ?: ""
        }
        if (name.isNotEmpty()) {
            val found = UsernameRules.changeSuggestions(People.suggestUsernames(name), current)
            if (isActive) suggestions = found
        }
    }

    // Only a free username, checked for exactly what the field holds, that
    // is not the one you have.
    val acceptedChange = UsernameStatus.acceptedHandle(status, text)?.takeIf { it != current }

    fun save(h: String) {
        saving = true
        error = null
        scope.launch {
            try {
                val saved = People.setUsername(h) ?: current
                // D-224 (Design 7): the one change, done.
                DoneCenter.post(DoneMoments.line(DoneCopy.USERNAME_SAVED, PeopleCopy.handle(saved)))
                onSaved(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (val failed = UsernameSaveFailure.from(e)) {
                    UsernameSaveFailure.Used -> onUsed()
                    UsernameSaveFailure.Taken -> status = UsernameStatus.Taken(h)
                    UsernameSaveFailure.NotAvailable -> status = UsernameStatus.NotAvailable
                    is UsernameSaveFailure.Message -> error = failed.text
                }
            } finally {
                saving = false
            }
        }
    }

    UsernameScreen(
        title = UsernameCopy.CHANGE,
        dark = dark,
        onCancel = onCancel,
        action = {
            PrimaryAction(UsernameCopy.CHANGE, dark, enabled = !saving && acceptedChange != null) {
                pending = acceptedChange
                confirming = pending != null
            }
        },
    ) {
        UsernameField(text, { text = it }, status, { status = it }, suggestions, dark)
        error?.let { Text(it, style = Type.body(dark).copy(color = Tok.owed(dark), fontSize = 14.sp)) }
    }

    // D-224 (Design 3): the one change cannot be made again; its question warns as it opens.
    HapticWarning(confirming)
    if (confirming) {
        SkiptiAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(UsernameCopy.confirmTitle(pending ?: "")) },
            text = { Text(UsernameCopy.confirmBody(new = pending ?: "", old = current)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    pending?.let { save(it) }
                }) { Text(UsernameCopy.CHANGE) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(UsernameCopy.keep(current)) }
            },
        )
    }
}

/** C2: for an account signed in before usernames were asked: a card on Home
 *  and You, never a screen in the way (GUARANTEES #2). The parent draws it
 *  only while its own `UsernameState` reads None (read in the parent's
 *  existing effect, so nothing loads inside a view that can be empty), and
 *  `onPick` opens the step as a route; returning reads the state again. */
@Composable
fun UsernameCard(dark: Boolean, onPick: () -> Unit) {
    Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(UsernameCopy.CARD_TITLE, style = Type.label(dark, 16))
        Text(UsernameCopy.CARD_BODY, style = Type.bodyDim(dark).copy(fontSize = 14.sp))
        PrimaryAction(UsernameCopy.CARD_ACTION, dark, onClick = onPick)
    }
}
