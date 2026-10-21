package com.vardrlabs.skipti

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

/**
 * The account surface's front door (D-126): the screen the Balance
 * header's account glyph opens.
 *
 * WHAT IT MAY NOT DO, and this is the constraint that shapes it:
 * GUARANTEES #5 says an account is opt-in and is **never created,
 * implied, or nudged for**. So the no-account state is written as a
 * statement of fact, not an invitation: it says the app works without an
 * account, because it does, and offers sign-in as a plain affordance
 * with no benefit copy, no badge, no "unlock", and nothing that reads as
 * a reason to want one. A user who never signs in should be able to open
 * this screen and feel finished, not sold to.
 *
 * WHAT LIVES HERE vs ON DEVICES: SPEC §3.5 requires the device list to be
 * "a first-class screen, not a settings sub-page", so Devices is opened
 * as its own full screen rather than nested inside this one: this
 * screen only routes to it. The sync switch moved here from the device
 * list (D-125 put it there because no account surface existed yet): sync
 * is an account-level fact, the device list is about devices.
 */

/** D-155: a plain sign-out. Twin of iOS's `AccountSignOut`. */
object AccountSignOut {
    const val NOTE =
        "Signing out stops sync on this device. Your ledger stays here, and signing " +
            "in again picks up where you left off."
}

/** Which account state the screen is in (D-192: no "waiting for approval";
 *  a signed-in phone simply has the account). */
/** D-206 (the founder's feedback item 4, 2026-10-13): You, in five groups
 *  instead of one long page; the twin of iOS's SettingsMap. The screens
 *  render from it, so the tested map is the layout. Account settings are
 *  absent without an account (GUARANTEES #5). */
enum class SettingsSection(val title: String, val route: String) {
    PROFILE_ACCOUNT("Profile and account", "account/profile"),
    APPEARANCE("Appearance", "account/appearance"),
    SECURITY("Security", "account/security"),
    DATA("Your data", "account/data"),
    ABOUT("About SKIPTI", "about"),
    ;

    /** D-222 (L56): the Lucide drawing in the row's icon tile, a [TabIcons.PATHS] key (iOS's `glyph`). */
    val glyphName: String
        get() = when (this) {
            PROFILE_ACCOUNT -> "user-round"
            APPEARANCE -> "sun"
            SECURITY -> "shield"
            DATA -> "download"
            ABOUT -> "info"
        }
}

/** D-222 (L58): the facts You's row summaries read. */
data class YouRowFacts(
    val signedIn: Boolean,
    val methodLabel: String?,
    val phoneCount: Int?,
    val theme: AppTheme,
    val mode: AppearanceMode,
    val lockTimeout: LockTimeout,
    val lockAvailable: Boolean,
    val version: String,
)

/** D-222 (L58): each of You's rows' summary as parts, joined on screen by [Separator.annotated] (Norse's gold rune) and
 *  spoken as "title, part, part" (the separator is never read aloud). One table on both shells (iOS's `YouRows`). */
object YouRows {
    const val PROMISES = "promises"
    const val LICENCES = "licences"
    const val NO_PHONE_LOCK = "App lock needs a phone lock"

    fun parts(section: SettingsSection, facts: YouRowFacts): List<String> = when (section) {
        SettingsSection.PROFILE_ACCOUNT ->
            if (!facts.signedIn) listOf(YouCopy.NO_ACCOUNT)
            // A failed or unread count (null) and a count of 0 are left out: never "0 phones".
            else listOf(facts.methodLabel ?: YouCopy.SIGNED_IN) + listOfNotNull(facts.phoneCount?.takeIf { it >= 1 }?.let(PhonesCopy::count))
        SettingsSection.APPEARANCE -> listOf(facts.theme.label, ModeChoice.shown(facts.theme, facts.mode).chosen.label)
        SettingsSection.SECURITY -> listOf(securityLine(facts.lockTimeout, facts.lockAvailable))
        SettingsSection.DATA -> listOf(YouCopy.DATA_LINE)
        // The build number stays on About's own page: it differs per shell (CLAUDE.md 10.3).
        SettingsSection.ABOUT -> listOf(facts.version, PROMISES, LICENCES)
    }

    fun spoken(title: String, parts: List<String>): String = (listOf(title) + parts).joinToString(", ")

    /** One wording on both shells; the Security page's own line keeps each platform's words. */
    fun securityLine(timeout: LockTimeout, available: Boolean): String = if (!available) NO_PHONE_LOCK else when (timeout) {
        LockTimeout.IMMEDIATELY -> "App lock immediately"
        LockTimeout.ONE_MINUTE -> "App lock after 1 minute"
        LockTimeout.TWO_MINUTES -> "App lock after 2 minutes"
        LockTimeout.FIVE_MINUTES -> "App lock after 5 minutes"
        LockTimeout.FIFTEEN_MINUTES -> "App lock after 15 minutes"
        LockTimeout.OFF -> "App lock off"
    }
}

/** D-222 (L62): what the mode control shows. Norse is always dark: Dark chosen and the control disabled, the stored
 *  choice untouched (it returns with another theme); the other themes show the stored mode, enabled. */
data class ModeChoice(val chosen: AppearanceMode, val enabled: Boolean) {
    companion object {
        fun shown(theme: AppTheme, stored: AppearanceMode): ModeChoice =
            if (theme.forcesDark) ModeChoice(AppearanceMode.DARK, false) else ModeChoice(stored, true)
    }
}

/** D-222 (L63): the drawn text-size slider's geometry over [TextSize.MIN]..[TextSize.MAX] (any one unit: px or dp). The
 *  thumb's centre runs from thumb / 2 to width - thumb / 2; a touch at x picks floor(v + 0.5), clamped (half up on both
 *  shells, written out: Swift's `.rounded()` rounds half away from zero and differs at -1.5; Kotlin's `roundToInt()`
 *  happens to agree with the formula, D-222 T1). Goldens: art.py 29. */
object StepSlider {
    fun clamped(step: Int): Int = step.coerceIn(TextSize.MIN, TextSize.MAX)

    fun step(atX: Float, width: Float, thumb: Float): Int {
        val travel = (width - thumb).toDouble()
        if (travel <= 0.0) return TextSize.MIN
        val v = TextSize.MIN + (atX - thumb / 2).toDouble() * (TextSize.MAX - TextSize.MIN) / travel
        return clamped(kotlin.math.floor(v + 0.5).toInt())
    }

    fun centre(step: Int, width: Float, thumb: Float): Float =
        thumb / 2 + (clamped(step) - TextSize.MIN) * (width - thumb) / (TextSize.MAX - TextSize.MIN)

    /** D-222 fix wave (look F4): the track runs between the thumb's two end centres, so at either end the track's end
     *  and the fill's start lie under the thumb (no accent beside Norse's diamond at the smallest step, no track past
     *  its tip at the largest). The twin of iOS's `SliderBar`. */
    fun track(width: Float, thumb: Float): ClosedFloatingPointRange<Float> = centre(TextSize.MIN, width, thumb)..centre(TextSize.MAX, width, thumb)

    /** TalkBack's adjust (and a key press): one step, clamped at both ends. */
    fun adjusted(step: Int, by: Int): Int = clamped(step + by)

    /** A hardware key or D-pad press (D-222 fix wave, I-3), as Material's Slider took them: right and up one step
     *  larger, left and down one smaller; any other key is not the slider's. */
    fun byKey(key: Key): Int? = when (key) {
        Key.DirectionRight, Key.DirectionUp -> 1
        Key.DirectionLeft, Key.DirectionDown -> -1
        else -> null
    }

    /** The step a key moves to, or null when it does not move (another key, or an end): the key then passes on, so
     *  focus can leave the slider at either end. */
    fun keyed(step: Int, key: Key): Int? = byKey(key)?.let { adjusted(step, it) }?.takeIf { it != step }
}

/** D-234 (A1, the founder's): Appearance's live sample line, "Lunch at Shake Shack" and 48 US dollars for everyone,
 *  whatever the default currency; converted to minor units by core's currency exponent and formatted by core in the
 *  phone's locale ($48.00 in both locales core knows). Nothing here formats a number (a test reads this body). The twin
 *  of iOS's `SampleLine`. */
object SampleLine {
    const val TITLE = "Lunch at Shake Shack"
    const val WHOLE_AMOUNT = "48"
    const val CURRENCY = "USD"

    fun parts(amount: String): List<String> = listOf(TITLE, amount)

    fun amount(locale: uniffi.skipti_core.LocaleTag = AppLocale.current): String = runCatching {
        val minor = uniffi.skipti_core.ffiMoneyParseMinor(WHOLE_AMOUNT, CURRENCY)
        uniffi.skipti_core.ffiMoneyFormat(minor, CURRENCY, locale)
    }.getOrDefault("")
}

enum class SettingItem {
    SIGN_IN, SIGNED_IN_AS, SIGN_OUT, SYNC, NOTIFICATIONS, FIND_ME,
    THEME, APPEARANCE_MODE, TEXT_SIZE,
    APP_LOCK, PRIVACY, PHONES, ERASE, DELETE_ACCOUNT,
    CURRENCY, EXPORT, IMPORT_DATA,
    ABOUT_PAGE,
    DELETION_PENDING,
}

object SettingsMap {
    val ACCOUNT_ONLY = setOf(
        SettingItem.SIGNED_IN_AS, SettingItem.SIGN_OUT, SettingItem.SYNC, SettingItem.NOTIFICATIONS,
        SettingItem.FIND_ME, SettingItem.PHONES, SettingItem.DELETE_ACCOUNT, SettingItem.DELETION_PENDING,
    )

    fun items(section: SettingsSection, signedIn: Boolean): List<SettingItem> = when (section) {
        SettingsSection.PROFILE_ACCOUNT ->
            if (signedIn) listOf(SettingItem.SIGNED_IN_AS, SettingItem.SIGN_OUT, SettingItem.SYNC, SettingItem.NOTIFICATIONS, SettingItem.FIND_ME)
            else listOf(SettingItem.SIGN_IN)
        SettingsSection.APPEARANCE -> listOf(SettingItem.THEME, SettingItem.APPEARANCE_MODE, SettingItem.TEXT_SIZE)
        SettingsSection.SECURITY ->
            listOf(SettingItem.APP_LOCK, SettingItem.PRIVACY) + (if (signedIn) listOf(SettingItem.PHONES) else emptyList()) +
                listOf(SettingItem.ERASE) + (if (signedIn) listOf(SettingItem.DELETE_ACCOUNT) else emptyList())
        SettingsSection.DATA -> listOf(SettingItem.CURRENCY, SettingItem.EXPORT, SettingItem.IMPORT_DATA)
        SettingsSection.ABOUT -> listOf(SettingItem.ABOUT_PAGE)
    }

    /** Above the sections: the deletion notice, when there is an account. */
    fun hubItems(signedIn: Boolean): List<SettingItem> = if (signedIn) listOf(SettingItem.DELETION_PENDING) else emptyList()

    fun section(route: String?): SettingsSection? = SettingsSection.entries.firstOrNull { it.route == route && it != SettingsSection.ABOUT }
}

/** D-206: Home's greeting, from the phone's clock and the name on this phone;
 *  nothing leaves it. The twin of iOS's Greeting. */
object Greeting {
    fun line(hour: Int, name: String?): String {
        val part = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        val trimmed = name?.trim().orEmpty()
        return if (trimmed.isEmpty()) part else "$part, $trimmed"
    }
}

enum class AccountState { NO_ACCOUNT, ACTIVE }

/** Everything the hub renders, resolved. */
data class AccountHubModel(
    val state: AccountState,
    val syncOn: Boolean,
    /** Sync exists only for an account, never as a teaser. */
    val showsAccountControls: Boolean,
)

/** The pure part, unit-testable without Compose. */
fun accountHubModel(signedIn: Boolean, syncOn: Boolean): AccountHubModel =
    if (signedIn) AccountHubModel(AccountState.ACTIVE, syncOn, showsAccountControls = true)
    else AccountHubModel(AccountState.NO_ACCOUNT, syncOn, showsAccountControls = false)

/** D-217 (the Look Book's `.who`): You's name is the display face at 700 and its handle the body face at 500. */
object YouStyle {
    fun name(dark: Boolean) = Type.display(dark, 700).copy(fontSize = TypeScale.displayName.sp)
    fun handle(dark: Boolean) = Type.bodyWeight(dark, 500).copy(color = Tok.textDim(dark), fontSize = 15.sp)
}

/** D-225 (Design 5): Your data's export row (the words the old card used). */
object YourDataCopy {
    const val EXPORT = "Export your data"
    const val EXPORT_DETAIL = "Your whole ledger in one file: CSV to read, or a JSON backup to restore."
}

/** D-206: You's words; the twin of iOS's YouCopy. */
object YouCopy {
    const val TITLE = "You"
    const val ADD_NAME = "Add your name"
    const val PHONE_ONLY = "On this phone only"
    const val CAREFUL = "Careful"
    const val NO_ACCOUNT = "No account"
    const val SIGNED_IN = "Signed in"
    const val DATA_LINE = "Export, import, currency"

    /** Under your name: your @username; nothing when signed in without one;
     *  "On this phone only" without an account. */
    fun handleLine(signedIn: Boolean, username: String?): String? =
        if (!signedIn) PHONE_ONLY else username?.let { "@$it" }
}

@Composable
fun AccountHubScreen(
    vaultPath: String,
    onOpenSignIn: () -> Unit,
    onBack: () -> Unit,
    /** D-144: Your data → Export (GUARANTEES #4, one tap from Settings). */
    onOpenExport: () -> Unit = {},
    /** D-166: Your data → Import (SPEC §9.5). */
    onOpenImport: () -> Unit = {},
    /** D-145: the on-device profile (name and picture). */
    onOpenProfile: () -> Unit = {},
    /** D-146: About (version, promises, privacy, security, licences). */
    onOpenAbout: () -> Unit = {},
    /** D-198: Your phones (SPEC §3.5) and Delete account (SPEC §4.4). */
    onOpenPhones: () -> Unit = {},
    onOpenDelete: () -> Unit = {},
    /** D-225 (Design 4): Security's erase row opens the Erase page (route "erase"). */
    onOpenErase: () -> Unit = {},
    /** D-155: the device signed out (AppRoot clears the deletion notice). */
    onSignedOut: () -> Unit = {},
    /** D-206: null is You itself; otherwise one of its groups (routes account/…). */
    section: SettingsSection? = null,
    onOpenSection: (SettingsSection) -> Unit = {},
    /** C2: the account's username as the shell knows it (null until read);
     *  a failed read keeps it (UsernameState.merge). */
    usernameState: UsernameState? = null,
    onUsernameState: (UsernameState?) -> Unit = {},
    /** C2: the card under the hero, and Find me's "Choose a username". */
    onPickUsername: () -> Unit = {},
    onFindMePick: () -> Unit = {},
    /** C2: Find me's one change, with the handle it was opened for. */
    onChangeUsername: (String) -> Unit = {},
    /** D-221: the next card's "Start a group" (the create-group screen, returning to You). */
    onOpenCreateGroup: () -> Unit = {},
    /** D-225 fix wave (F6): this page's scroll state. You and its pages share one call site in the shell, so each
     *  passes its own (You's kept across its pushes, each page's fresh): a page never opens at You's offset. */
    scroll: androidx.compose.foundation.ScrollState = androidx.compose.foundation.rememberScrollState(),
) {
    val knownUsername by rememberUpdatedState(usernameState)
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by remember {
        mutableStateOf(accountHubModel(signedIn = false, syncOn = true))
    }
    var busy by remember { mutableStateOf(false) }
    // D-172: how this phone signed in; null for a sign-in from before D-172.
    var method by remember { mutableStateOf<SignInMethod?>(null) }
    var username by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var avatar by remember { mutableStateOf<Avatar>(Avatar.None) }
    // D-211 fix 5: false until the first read of the picture finishes, so a
    // viewer restored after a configuration change is not closed by the
    // placeholder `Avatar.None` that is shown while the vault is read.
    var avatarRead by remember { mutableStateOf(false) }
    // D-222 (L58): the account's phones for You's row, read from the server once per load; null when unknown.
    var phoneCount by remember { mutableStateOf<Int?>(null) }
    // D-225 (Design 4): Security's Your phones summary (names, this phone first), read once per visit; null when unknown.
    var phonesLine by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            val store = LedgerStore(vaultPath)
            method = Account.method(store)
            model = accountHubModel(signedIn = Account.isSignedIn(), syncOn = CloudSync.syncEnabled(store))
            name = runCatching { store.loadSelf()?.name }.getOrNull()
            avatar = runCatching { store.loadAvatar() }.getOrDefault(Avatar.None)
            avatarRead = true
        }
        username = if (section == null && model.state == AccountState.ACTIVE) People.myUsername() else null
        if (section == null) {
            // C2: a failed read (null) keeps what was known (UsernameState.merge).
            val read = if (model.state == AccountState.ACTIVE) People.accountUsername() else null
            onUsernameState(UsernameState.merge(known = knownUsername, read = read, signedIn = Account.isSignedIn()))
        }
        // Fix wave (S7): last, so a slow server read (offline) never holds back the username merge and the setup steps.
        phoneCount = if (section == null && model.state == AccountState.ACTIVE) Phones.activeCount() else null
        phonesLine = if (section == SettingsSection.SECURITY && model.state == AccountState.ACTIVE) Phones.summary(vaultPath) else null
    }

    // D-198: and again after a sign-out while this page is open, which takes
    // the account controls away; D-145: and after a profile change.
    LaunchedEffect(RemoteSignOut.signOuts, ProfileState.revision, section) { reload() }

    val signedIn = model.state == AccountState.ACTIVE
    // D-221 (Design 3): the setup steps' facts, null until first read. Read again on a profile, sync, currency, app
    // lock or username change, on coming back to You, and on every resume (notifications are allowed in the phone's
    // settings, outside SKIPTI).
    var facts by remember { mutableStateOf<SetupFacts?>(null) }
    var resumes by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    // D-222 (L58), D-225 (Design 4): the lock's availability is re-read on every resume (a phone lock is set outside
    // SKIPTI); You's row and Security's App lock row read it.
    val lockAvailable = remember(resumes) { AppLock.available(context) }
    val hasUsername = username != null || usernameState is UsernameState.CanChange || usernameState is UsernameState.Final
    LaunchedEffect(
        RemoteSignOut.signOuts, ProfileState.revision, SyncStatus.revision, section, resumes,
        DefaultCurrency.stored, AppLock.timeout, hasUsername, signedIn,
    ) {
        if (section == null) facts = withContext(Dispatchers.IO) { SetupFacts.read(context, LedgerStore(vaultPath), hasUsername) }
    }
    // D-221 (Design 9): where "Do it" goes; every place has one (no else: a new place does not compile without it).
    fun openStep(step: SetupStep) {
        when (StepPlace.of(step)) {
            StepPlace.PROFILE -> onOpenProfile()
            StepPlace.DATA -> onOpenSection(SettingsSection.DATA)
            StepPlace.SECURITY -> onOpenSection(SettingsSection.SECURITY)
            StepPlace.CREATE_GROUP -> onOpenCreateGroup()
            StepPlace.USERNAME -> onPickUsername()
            StepPlace.PROFILE_ACCOUNT -> onOpenSection(SettingsSection.PROFILE_ACCOUNT)
        }
    }
    // D-211: the viewer is only for a photo that decodes; it closes when the
    // picture stops being one.
    val viewable = remember(avatar) { PictureViewing.canView(avatar) }
    // D-211 fix 5: saved, so a configuration change (dark mode, rotation) keeps it open.
    var viewing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewable, avatarRead) { if (PictureViewing.shouldClose(viewable, avatarRead)) viewing = false }
    // D-216 (Design 4): leaving Appearance switches the launcher entry, once per visit, if the icon wanted is not the one
    // shown. Not when the activity is only being made again (a rotation, the phone's dark mode): the person is still here.
    val activity = remember(context) { context.activity() }
    if (section == SettingsSection.APPEARANCE) {
        DisposableEffect(Unit) {
            onDispose { if (activity?.isChangingConfigurations != true) AppIconSwitcher.applyIfNeeded(context) }
        }
    }
    // D-244 (ADR-0043): what was chosen on a settings page goes up to the account in one push when the page closes
    // (signed in, sync on). Not while the activity is only being made again: the person is still here.
    if (section != null) {
        DisposableEffect(Unit) {
            onDispose { if (activity?.isChangingConfigurations != true) SettingsSync.pageClosed(context) }
        }
    }
    // D-217: "You" and each settings page are pushed pages: the title is the bar's (iOS: inline, no ornament).
    PushedPage(if (section == null) YouCopy.TITLE else section.title, dark, onBack, side = BarDecisions.PAGE_SIDE_WIDE.dp, scroll = scroll) {
        if (section == null) {
            // D-206 (the founder's items 4 and 5): you first, then five groups
            // instead of one long page.
            // D-221: the ring counts the setup steps; zero of them until the facts are read.
            val (done, total) = facts?.let { SetupSteps.progress(it) } ?: (0 to SetupSteps.steps(signedIn, lockAvailable).size)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ThemeArt.cardShape(Appearance.theme))
                    .clickable(onClick = onOpenProfile)
                    .padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) {
                        // D-221: the hero's words carry the pill's.
                        contentDescription = (name?.let { "Profile, $it" } ?: "Profile, add your name") +
                            (if (facts != null) ", " + StepCopy.pill(done, total) else "")
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // D-211: the whole block is ONE click target, Profile. A photo that
                // decodes adds a click target over the picture alone, which opens
                // it full screen. The viewer hangs on this always-present screen.
                // D-221 (Design 4, L38, L39): the ring round the picture, in the ring's box; the picture and its
                // viewer stay 104 and centred.
                Box(Modifier.size(ThemeArt.m("ringBox").dp), contentAlignment = Alignment.Center) {
                    if (facts != null) {
                        // D-243: the ring turns on the art's clock (RingTurn); the picture over it does not.
                        if (Appearance.theme == AppTheme.NORSE) {
                            RuneRing(done, total, dark, Modifier.artTurn { RingTurn.degrees(it) })
                        } else {
                            CompletionRing(done, total, dark)
                        }
                    }
                    AvatarView(avatar, name, 104.dp, dark)
                    if (viewable) {
                        Box(
                            Modifier
                                .size(104.dp)
                                .clip(CircleShape)
                                .semantics { contentDescription = ProfileCopy.YOUR_PICTURE }
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) { viewing = true },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(name ?: YouCopy.ADD_NAME, style = YouStyle.name(dark))
                // D-221 fix wave (S11): iOS's 8 between the name, the line under it and the pill.
                YouCopy.handleLine(signedIn, username)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = YouStyle.handle(dark))
                }
                // D-221 (Design 5; Q11 a): the progress pill takes "Edit"'s place; the hero stays one link to Profile.
                if (facts != null) {
                    Spacer(Modifier.height(8.dp))
                    ProgressPill(done, total, dark)
                }
            }
            if (viewing && viewable) PictureViewer(avatar) { viewing = false }
            // C2: an account signed in before usernames were asked.
            // D-221 fix wave (S11): iOS's 16 between the hero and each card under it.
            if (usernameState == UsernameState.None) {
                Spacer(Modifier.height(16.dp))
                UsernameCard(dark, onPickUsername)
            }
            // D-221 (Design 9, L41): the first undone step; the username step waits while its own card asks.
            facts?.let { SetupSteps.next(it, skipping = if (usernameState == UsernameState.None) setOf(SetupStep.USERNAME) else emptySet()) }?.let { step ->
                Spacer(Modifier.height(16.dp))
                NextStepCard(step, dark) { openStep(step) }
            }
            // The notice spaces itself: with nothing pending it draws nothing and leaves no gap (it left 12 before).
            if (SettingsMap.hubItems(signedIn).contains(SettingItem.DELETION_PENDING)) {
                DeletionPendingCard(dark, Modifier.padding(top = 16.dp))
            }
            Spacer(Modifier.height(16.dp))
            val version = remember { AboutInfo.current(context) }
            val rowFacts = YouRowFacts(
                signedIn = signedIn, methodLabel = method?.rowLabel, phoneCount = phoneCount, theme = Appearance.theme,
                mode = Appearance.mode, lockTimeout = AppLock.timeout, lockAvailable = lockAvailable, version = version.first,
            )
            // D-222 (L57): the card pads 0 and the list lays itself out at the Look Book's list padding. D-225 (Design 1):
            // the shared ListCard and ListRow draw it (nothing on screen changed).
            ListCard(dark) {
                SettingsSection.entries.forEach { s ->
                    row {
                        // D-222 (L56, L58): the icon tile, the title, the live summary; the row speaks "title, part, part"
                        // (the separator is never read aloud) and is a button to TalkBack.
                        ListRow(
                            s.title,
                            dark,
                            parts = YouRows.parts(s, rowFacts),
                            trailing = RowTrailing.Chevron,
                            leading = { SettingsTile(s.glyphName, dark) },
                        ) { if (s == SettingsSection.ABOUT) onOpenAbout() else onOpenSection(s) }
                    }
                }
            }
            return@PushedPage
        }

        // D-225 (Designs 4, 5): Security and Your data are list cards, drawn from the same map (SettingsMap stays the
        // tested layout).
        when (section) {
            SettingsSection.SECURITY -> {
                SecurityPage(dark, signedIn, lockAvailable, phonesLine, onOpenPhones, onOpenErase, onOpenDelete)
                return@PushedPage
            }
            SettingsSection.DATA -> {
                YourDataPage(dark, signedIn, onOpenExport, onOpenImport)
                return@PushedPage
            }
            else -> Unit
        }
        // D-217 look fix 1: iOS's VStack spaces the items 16 apart, and nothing above the first (the page's top is the
        // bar's, [BarDecisions.scrollTop]); the spacer used to come BEFORE every item, the first included.
        SettingsMap.items(section, signedIn).forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.height(16.dp))
            when (item) {
                SettingItem.SIGN_IN -> Card(dark) {
                    Text("No account", style = Type.body(dark))
                    Spacer(Modifier.height(8.dp))
                    // A statement, not a pitch. GUARANTEES #5 forbids the nudge.
                    Text(
                        "SKIPTI works fully without one. Your ledger, your " +
                            "balances and your exports are all on this device.",
                        style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp),
                    )
                    Spacer(Modifier.height(12.dp))
                    // Secondary, never primary (D-128, GUARANTEES #5).
                    SecondaryAction("Sign in", dark, onOpenSignIn)
                }
                SettingItem.SIGNED_IN_AS -> Card(dark) {
                    method?.let { SignInMethodRow(it, dark) } ?: Text("Signed in", style = Type.body(dark))
                }
                SettingItem.SIGN_OUT -> Card(dark) {
                    // D-155: a plain sign-out.
                    Text(AccountSignOut.NOTE, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction("Sign out", dark) {
                        if (busy) return@SecondaryAction
                        // D-235: busy from the tap itself, not from when the coroutine first runs.
                        busy = true
                        scope.launch {
                            // D-235 (Android audit M2): every cloud call inside is bounded, and leaving the page no
                            // longer cancels it half way: this phone is always signed out.
                            withContext(NonCancellable + Dispatchers.IO) {
                                // D-196: the push address leaves the cloud while
                                // the account may still write it.
                                Push.stop(LedgerStore(vaultPath), inCloud = true)
                                Account.signOut()
                            }
                            onSignedOut()
                            // D-224 (Design 7): signed out, said once.
                            DoneCenter.post(DoneMoments.line(DoneCopy.SIGNED_OUT))
                            reload()
                            busy = false
                        }
                    }
                }
                SettingItem.SYNC -> SyncCard(
                    dark = dark,
                    syncOn = model.syncOn,
                    busy = busy,
                    onToggle = {
                        // D-235: busy from the tap itself, so a quick second tap cannot switch it back.
                        if (busy) return@SyncCard
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                CloudSync.setSyncEnabled(LedgerStore(vaultPath), !model.syncOn)
                                CloudSync.syncSwitched()
                            }
                            reload()
                            busy = false
                        }
                    },
                )
                // D-196: pushes come with the account and sync.
                SettingItem.NOTIFICATIONS -> NotificationsCard(dark, model.syncOn)
                // D-197: your username and whether people can find you.
                SettingItem.FIND_ME -> FindMeCard(
                    vaultPath, dark,
                    known = usernameState,
                    onKnown = onUsernameState,
                    onPick = onFindMePick,
                    onChange = onChangeUsername,
                )
                // D-222 (L59, Design 4): Theme and Light or dark sit on the ground; Text size is one card. Each label is 8
                // above its content.
                SettingItem.THEME -> Column {
                    SectionLabel(AppearanceCopy.CARDS[0], dark)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTheme.entries.forEach { theme ->
                            ThemeCard(theme, Appearance.theme == theme, dark, Modifier.weight(1f)) {
                                Appearance.setTheme(context, theme)
                            }
                        }
                    }
                }
                SettingItem.APPEARANCE_MODE -> Column {
                    SectionLabel(AppearanceCopy.CARDS[1], dark)
                    Spacer(Modifier.height(8.dp))
                    ModeControl(dark)
                    // Q10 a: the one line left under the control, in Norse only.
                    if (Appearance.theme.forcesDark) {
                        Spacer(Modifier.height(6.dp))
                        Text(AppearanceCopy.NORSE_ALWAYS_DARK, style = Type.bodyDim(dark).copy(fontSize = AppearanceCopy.DETAIL_SIZE.sp))
                    }
                    // D-216 (Design 5): the App icon section, after Light or dark and before Text size (theme, mode and icon
                    // are the look), 16 below it as the page's items.
                    Spacer(Modifier.height(16.dp))
                    AppIconSection(dark)
                }
                SettingItem.TEXT_SIZE -> Column {
                    SectionLabel(AppearanceCopy.CARDS[2], dark)
                    Spacer(Modifier.height(8.dp))
                    Card(dark) {
                        TextSizeSlider(dark)
                        Spacer(Modifier.height(8.dp))
                        // D-222 (L64): the live sample, at the sample size, growing with the step (the root's override).
                        val amount = remember { SampleLine.amount() }
                        Text(
                            Separator.annotated(SampleLine.parts(amount)),
                            style = Type.name(dark, TypeScale.sample.toInt()),
                            modifier = Modifier.clearAndSetSemantics { contentDescription = SampleLine.parts(amount).joinToString(", ") },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(AppearanceCopy.TEXT_SIZE_CAPTION, style = Type.bodyDim(dark).copy(fontSize = AppearanceCopy.DETAIL_SIZE.sp))
                    }
                    // D-224 (Design 10, L48): the Haptics row, its own card after Text size's (no label: the Look Book has
                    // none), 16 below it as the page's items.
                    Spacer(Modifier.height(16.dp))
                    HapticsRow(dark)
                    // D-231 (decision 69): the Less motion row, after Haptics, 16 below it as the page's items.
                    Spacer(Modifier.height(16.dp))
                    MotionRow(dark)
                    // D-249 (decision 163): Spending on Home, after Less motion; this phone's own (decision 189).
                    Spacer(Modifier.height(16.dp))
                    SpendingHomeRow(dark)
                }
                // D-225: Security's and Your data's items are drawn by SecurityPage and YourDataPage.
                SettingItem.APP_LOCK, SettingItem.PRIVACY, SettingItem.PHONES, SettingItem.ERASE, SettingItem.DELETE_ACCOUNT,
                SettingItem.CURRENCY, SettingItem.EXPORT, SettingItem.IMPORT_DATA -> Unit
                // About opens from You; the deletion notice sits on You.
                SettingItem.ABOUT_PAGE, SettingItem.DELETION_PENDING -> Unit
            }
        }
    }
}

/** D-221 (Design 9, L41): You's next step on glass: its title and why line, "Do it" (the compact primary) on the right;
 *  at the large text sizes (the shells' `fontScale >= 1.15`, iOS's accessibility sizes) the button drops under the
 *  words at full width. The twin of iOS's `NextStepCard`. */
@Composable
private fun NextStepCard(step: SetupStep, dark: Boolean, onDo: () -> Unit) {
    val stacked = TextSize.stacks(LocalDensity.current.fontScale)
    val action: @Composable (Modifier) -> Unit = { m -> PrimaryAction(StepCopy.DO_IT, dark, compact = true, modifier = m, onClick = onDo) }
    val words: @Composable (Modifier) -> Unit = { m ->
        // D-221 fix wave (S11): iOS's 4 between the title and the why line.
        Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(StepCopy.title(step), style = Type.name(dark, 16))
            Text(StepCopy.why(step), style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
        }
    }
    if (stacked) {
        Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            words(Modifier)
            action(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.glassCard(dark), verticalAlignment = Alignment.CenterVertically) {
            words(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            action(Modifier)
        }
    }
}

/** D-225 (Design 4; L67 to L70; Q15 a): Security, top to bottom 16 apart: the rooted warning (only on such a phone),
 *  the safe card (App lock with its timeout, the screen-privacy switch, Your phones with their names when signed in),
 *  then "Careful" alone (no hairline, L70) 8 above the danger card (Erase this phone's ledger, which opens the Erase
 *  page, and Delete account when signed in). The items and their order are [SettingsMap]'s, split by
 *  [SecurityRows.partition]. The twin of iOS's Security page. */
@Composable
private fun SecurityPage(
    dark: Boolean,
    signedIn: Boolean,
    availableOnResume: Boolean,
    phonesLine: String?,
    onOpenPhones: () -> Unit,
    onOpenErase: () -> Unit,
    onOpenDelete: () -> Unit,
) {
    val context = LocalContext.current
    var appLockOpen by rememberSaveable { mutableStateOf(false) }
    // Read again on every resume (the parent's) and whenever the App lock page closes: a phone lock set while the page
    // was open shows at once.
    val lockAvailable = remember(availableOnResume, appLockOpen) { AppLock.available(context) }
    val (safe, careful) = SecurityRows.partition(SettingsMap.items(SettingsSection.SECURITY, signedIn))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ModifiedPhoneWarning(dark)
        ListCard(dark) {
            safe.forEach { item ->
                row {
                    when (item) {
                        // D-163: SPEC §3.7's auto-lock, on by default; its timeout, or that the phone cannot lock.
                        SettingItem.APP_LOCK -> ListRow(
                            AppLockCopy.SECTION, dark,
                            parts = listOf(SecurityRows.appLockLine(AppLock.timeout, lockAvailable)),
                            trailing = RowTrailing.Chevron,
                        ) { appLockOpen = true }
                        // D-164: the rest of SPEC §3.7.
                        SettingItem.PRIVACY -> PrivacyRow(dark)
                        // D-198: SPEC §3.5's phone list; a failed read leaves the summary out.
                        SettingItem.PHONES -> ListRow(
                            PhonesCopy.TITLE, dark, parts = listOfNotNull(phonesLine), trailing = RowTrailing.Chevron,
                        ) { onOpenPhones() }
                        else -> Unit
                    }
                }
            }
        }
        if (careful.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(YouCopy.CAREFUL, dark, modifier = Modifier.semantics { heading() })
                ListCard(dark) {
                    careful.forEach { item ->
                        row {
                            when (item) {
                                // D-168: the deliberate erase, set apart, never automatic: the row opens the page that
                                // says what goes, whose button asks first.
                                SettingItem.ERASE -> ListRow(
                                    EraseCopy.BUTTON, dark, parts = listOf(SecurityRows.ERASE_SUMMARY),
                                    trailing = RowTrailing.Chevron, tone = RowTone.DANGER,
                                ) { onOpenErase() }
                                // D-198: SPEC §4.4's deletion.
                                SettingItem.DELETE_ACCOUNT -> ListRow(
                                    PhonesCopy.DELETE_TITLE, dark, parts = listOf(SecurityRows.DELETE_ACCOUNT_SUMMARY),
                                    trailing = RowTrailing.Chevron, tone = RowTone.DANGER,
                                ) { onOpenDelete() }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
    if (appLockOpen) AppLockPage(dark) { appLockOpen = false }
}

/** D-225 (Design 5; L74): Your data, one list card: Default currency (its code, opening the currency list page, whose
 *  "Common" section holds the old quick choices), Export (GUARANTEES #4: one tap) and Import, each explained on two
 *  lines; the currency caption 8 under the card. The twin of iOS's Your data page. */
@Composable
private fun YourDataPage(dark: Boolean, signedIn: Boolean, onOpenExport: () -> Unit, onOpenImport: () -> Unit) {
    val context = LocalContext.current
    var choosingCurrency by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListCard(dark) {
            // D-225 fix wave (M5): the account's real state, so a signed-in item added to the map is not lost here.
            SettingsMap.items(SettingsSection.DATA, signedIn).forEach { item ->
                row {
                    when (item) {
                        // D-165: SPEC §5.2's currencies; the default is this phone's.
                        SettingItem.CURRENCY -> ListRow(
                            CurrencyCopy.LABEL, dark, trailing = RowTrailing.Value(DefaultCurrency.current, chevron = true),
                        ) { choosingCurrency = true }
                        // D-144: GUARANTEES #4, export at any time, in one tap.
                        SettingItem.EXPORT -> ListRow(
                            YourDataCopy.EXPORT, dark, parts = listOf(YourDataCopy.EXPORT_DETAIL),
                            trailing = RowTrailing.Chevron, summaryLines = 2,
                        ) { onOpenExport() }
                        // D-166: SPEC §9.5's importers.
                        SettingItem.IMPORT_DATA -> ListRow(
                            ImportCopy.ENTRY, dark, parts = listOf(ImportCopy.ENTRY_DETAIL),
                            trailing = RowTrailing.Chevron, summaryLines = 3,
                        ) { onOpenImport() }
                        else -> Unit
                    }
                }
            }
        }
        CardCaption(CurrencyCopy.DETAIL, dark)
    }
    if (choosingCurrency) {
        CurrencyListDialog(
            selected = DefaultCurrency.current,
            suggested = DefaultCurrency.suggestion(),
            dark = dark,
            onPick = {
                DefaultCurrency.set(context, it)
                choosingCurrency = false
            },
            onClose = { choosingCurrency = false },
        )
    }
}

/**
 * ADR-0034 §3 / GUARANTEES #5 as amended (D-125): sync is on once an
 * account exists and off in one tap. The copy refuses to overclaim:
 * turning sync off stops this device sending and cannot reach back
 * through the wire to remove what the account already synced.
 */
@Composable
private fun SyncCard(dark: Boolean, syncOn: Boolean, busy: Boolean, onToggle: () -> Unit) {
    Card(dark) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Sync", dark)
            Text(
                Type.kicker(if (syncOn) "On" else "Off"),
                style = Type.microLabel(dark).copy(
                    color = if (syncOn) Tok.owing(dark) else Tok.textDim(dark),
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (syncOn) {
                "Your expenses sync to your devices as you add them."
            } else {
                "Nothing is leaving this device. Your ledger still works " +
                    "exactly as it does now."
            },
            style = Type.body(dark).copy(fontSize = TypeScale.detail.sp),
        )
        Spacer(Modifier.height(10.dp))
        // D-176: the tonal secondary, as on iOS (it was a small filled button).
        SecondaryAction(if (syncOn) "Turn sync off" else "Turn sync on", dark) { if (!busy) onToggle() }
        if (syncOn) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Turning sync off stops this device sending. It does not " +
                    "delete what your account has already synced.",
                style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp),
            )
        }
    }
}

/** D-212: the Appearance page's words (twins with iOS's AppearanceCopy). */
object AppearanceCopy {
    val CARDS = listOf("Theme", "Light or dark", "Text size")
    const val NORSE_ALWAYS_DARK = "Norse is always dark."
    const val TEXT_SIZE_CAPTION = "Starts at your phone's size; changes SKIPTI only."
    /** D-215 fix (F2): the Norse line under the mode control and the text-size caption, in sp (iOS: detailSize, pt). */
    const val DETAIL_SIZE = 14
}

/** D-222 (L56): a section's Lucide drawing by its [SettingsSection.glyphName]; D-224: the Haptics row's too. */
private fun settingsGlyph(name: String): ImageVector = when (name) {
    "user-round" -> TabIcons.UserRound
    "sun" -> TabIcons.Sun
    "shield" -> TabIcons.Shield
    "download" -> TabIcons.Download
    "info" -> TabIcons.Info
    "vibrate" -> TabIcons.Vibrate
    "sparkles" -> TabIcons.Sparkles
    "chart-pie" -> TabIcons.ChartPie
    else -> error("no drawing for $name")
}

/** D-224 (Design 2, 10; L48): Appearance's Haptics row, one list card as You's: the vibrate tile, the title and the
 *  summary, and the switch. The whole row toggles it and is ONE element to TalkBack, "Haptics, A tap when something is
 *  done", a Switch with its state. Turning it on plays one success, so the person feels what they turned on; off plays
 *  nothing. D-225 (Design 3): S2's `.toggle` ListRow with the drawn ThemeSwitch, its summary one line as iOS's. The twin
 *  of iOS's `HapticsRow`. */
@Composable
private fun HapticsRow(dark: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    ListCard(dark) {
        row {
            ListRow(
                DoneCopy.HAPTICS_TITLE,
                dark,
                parts = listOf(DoneCopy.HAPTICS_SUMMARY),
                trailing = RowTrailing.Toggle(Appearance.haptics) { on ->
                    Appearance.setHaptics(context, on)
                    if (on) Haptics.play(view, HapticKind.SUCCESS)
                },
                leading = { SettingsTile("vibrate", dark) },
            )
        }
    }
}

/** D-231 (decision 69): Appearance's Less motion row, the twin of [HapticsRow] and of iOS's `MotionRow`: one list card,
 *  the sparkles tile, the title over the summary ([MotionCopy]), and the switch on [Appearance.lessMotion] (the
 *  low-memory default until the person sets it; a tap stores an explicit value). ONE element to TalkBack, a Switch named
 *  by the title and the summary. No haptic of its own. */
@Composable
private fun MotionRow(dark: Boolean) {
    val context = LocalContext.current
    ListCard(dark) {
        row {
            ListRow(
                MotionCopy.TITLE,
                dark,
                parts = listOf(MotionCopy.SUMMARY),
                trailing = RowTrailing.Toggle(Appearance.lessMotion) { on -> Appearance.setLessMotion(context, on) },
                leading = { SettingsTile("sparkles", dark) },
            )
        }
    }
}

/** D-249 (decision 163): Appearance's "Spending on Home" row, the twin of iOS's `SpendingHomeRow`: one list card, the
 *  chart-pie tile, the title over the summary on two lines (the approved words do not fit one on the narrowest phones:
 *  the width tables, decision 82), and the switch; on until turned off; this phone's own (decision 189), so it writes
 *  [SpendingPrefs] directly, never through SettingsSync. Off hides Home's Your spending section only. */
@Composable
private fun SpendingHomeRow(dark: Boolean) {
    val context = LocalContext.current
    ListCard(dark) {
        row {
            ListRow(
                SpendingCopy.HOME_ROW,
                dark,
                parts = listOf(SpendingCopy.HOME_SUMMARY),
                summaryLines = 2,
                trailing = RowTrailing.Toggle(SpendingPrefs.shownOnHome) { on -> SpendingPrefs.setOnHome(context, on) },
                leading = { SettingsTile("chart-pie", dark) },
            )
        }
    }
}

/** D-216 (Design 5), D-234 (A2, decision 80): Appearance's App icon section: ONE list card whose header row reads "App
 *  icon", the current choice and the chevron ([RowTrailing.Disclosure]); a tap opens the four rows inline under it (the
 *  Match theme switch, then the three icons as check rows, a radio group), a hairline before each, and a second tap closes
 *  them. Open, the caption sits 8 under the card. The rows fade in while the card grows and the chevron turns, over the
 *  switch's travel, only when art may move; under Less motion or the phone's setting they snap (D-239: battery saver does not). Closed
 *  each time the page opens. A tap on an icon stores it (Match theme turns off); the switch stores `match`, or off the
 *  theme's icon (nothing visible changes). Only the choice is stored here: the icon changes when the person leaves the
 *  page. The twin of iOS's App icon section. */
@Composable
private fun AppIconSection(dark: Boolean) {
    val context = LocalContext.current
    val choice = Appearance.iconChoice
    val theme = Appearance.theme
    var expanded by remember { mutableStateOf(false) }
    val motion = rememberMotionState()
    val still = motion.reduce
    val ms = AppIconPlan.expandMillis(moves = !still)
    Column {
        ListCard(dark) {
            row {
                Column {
                    ListRow(
                        AppIconCopy.TITLE,
                        dark,
                        trailing = RowTrailing.Disclosure(AppIconCopy.choice(choice), expanded),
                    ) { expanded = !expanded }
                    AnimatedVisibility(
                        expanded,
                        enter = if (still) EnterTransition.None else fadeIn(tween(ms, easing = EaseInOut)) + expandVertically(tween(ms, easing = EaseInOut), expandFrom = Alignment.Top),
                        exit = if (still) ExitTransition.None else fadeOut(tween(ms, easing = EaseInOut)) + shrinkVertically(tween(ms, easing = EaseInOut), shrinkTowards = Alignment.Top),
                    ) {
                        Column(Modifier.selectableGroup()) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hairline(dark)))
                            ListRow(
                                AppIconCopy.MATCH,
                                dark,
                                trailing = RowTrailing.Toggle(choice == AppIconChoice.MATCH) { on ->
                                    Appearance.setIconChoice(context, AppIconPlan.matchSwitched(on, theme))
                                },
                                leading = { IconPreview(AppIcon.of(theme)) },
                            )
                            AppIcon.entries.forEach { icon ->
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hairline(dark)))
                                ListRow(
                                    icon.label,
                                    dark,
                                    trailing = RowTrailing.Check(AppIconPlan.checked(choice, theme) == icon, acts = AppIconPlan.tapActs(icon, choice)),
                                    leading = { IconPreview(icon) },
                                    spoken = AppIconCopy.spoken(icon),
                                ) { Appearance.setIconChoice(context, AppIconPlan.tap(icon)) }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            expanded,
            enter = if (still) EnterTransition.None else fadeIn(tween(ms, easing = EaseInOut)) + expandVertically(tween(ms, easing = EaseInOut), expandFrom = Alignment.Top),
            exit = if (still) ExitTransition.None else fadeOut(tween(ms, easing = EaseInOut)) + shrinkVertically(tween(ms, easing = EaseInOut), shrinkTowards = Alignment.Top),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                CardCaption(AppIconCopy.CAPTION, dark)
            }
        }
    }
}

/** D-216 (Design 5): an icon's preview, its own 144 px image at `iconPreview` in its own rounded square
 *  (`iconPreviewRadius` of the side, Apple's icon corner), the same shape in every theme: an icon keeps its shape, so no
 *  theme tile. Decorative: the row speaks for it. */
@Composable
private fun IconPreview(icon: AppIcon) {
    val image = when (icon) {
        AppIcon.AURORA -> R.drawable.icon_preview_aurora
        AppIcon.NORSE -> R.drawable.icon_preview_norse
        AppIcon.VARDR -> R.drawable.icon_preview_vardr
    }
    Image(
        bitmap = ImageBitmap.imageResource(image),
        contentDescription = null,
        // The whole-branch review's M5: the 144 px image is drawn at 36 with a high-quality filter, the twin of iOS's
        // `.interpolation(.high)` (Compose's default filter is low).
        filterQuality = FilterQuality.High,
        modifier = Modifier
            .size(ThemeArt.m("iconPreview").dp)
            .clip(RoundedCornerShape((ThemeArt.m("iconPreview") * ThemeArt.m("iconPreviewRadius")).dp)),
    )
}

/** The activity a context belongs to (Compose's LocalContext is the activity, perhaps wrapped). */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** D-222 (L56, Design 1): You's row tile, the theme's tile: Aurora rounded (0.3 of the size), Norse the diamond drawn at
 *  its drawn side with the centred gold-brown edge (laid before the clip, D-222 A2), Vardr Labs square; `accentSoft`
 *  under the Lucide glyph in `accent`, upright in every shape. Decorative: the row speaks for it. The twin of iOS's
 *  `SettingsTile`. */
@Composable
private fun SettingsTile(glyphName: String, dark: Boolean) {
    val size = ThemeArt.m("settingsTile").toInt()
    val kind = TileShape.of(Appearance.theme, person = false)
    val shape = tileShape(kind, size)
    Box(Modifier.size(size.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredSize(tileDrawnSide(kind, size).dp)
                .tileEdge(kind, size)
                .clip(shape)
                .background(Tok.accentSoft(dark), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                settingsGlyph(glyphName), contentDescription = null, tint = Tok.accent(dark),
                modifier = Modifier.size(ThemeArt.m("settingsGlyph").dp),
            )
        }
    }
}

/** D-222 (L60, Design 5): one theme's card: the CURRENT theme's control shape with a 2 dp border (the accent when
 *  chosen, the hairline when not), the theme's own swatch, then its name. A radio button to TalkBack. */
@Composable
internal fun ThemeCard(theme: AppTheme, selected: Boolean, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = ThemeArt.controlShape(Appearance.theme)
    Column(
        modifier
            .border(2.dp, if (selected) Tok.accent(dark) else Tok.hairline(dark), shape)
            .clip(shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = theme.label }
            .padding(ThemeArt.m("themePad").dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ThemeArt.m("themeGap").dp),
    ) {
        ThemeSwatch(theme)
        // The twin of iOS's minimumScaleFactor(0.8): shrink to 80% before an ellipsis.
        Text(
            theme.label,
            style = Type.name(dark, 14),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.2.sp, maxFontSize = 14.sp, stepSize = 0.2.sp),
        )
    }
}

/** D-222 (L61, Q9 a, Design 5): a theme's swatch draws that theme's OWN dark art at a still pose, whatever the mode or
 *  the phone: Aurora the hero's night (sky, stars, curtains at the hero strengths); Norse its ground, a 1 dp inner line
 *  and ᚠᚷᛗᛏ in its gold; Vardr Labs its dark ground, the dot grid in `ring` and the frame marks in its accent. Colours by
 *  token in a fixed set; still (no art clock); clipped to the current theme's panel shape. Decorative. */
@Composable
private fun ThemeSwatch(theme: AppTheme) {
    val shape = ThemeArt.panelShape(Appearance.theme, ThemeArt.m("swatchRadius").toInt())
    val base = Modifier.fillMaxWidth().height(ThemeArt.m("swatchHeight").dp).clip(shape).clearAndSetSemantics {}
    when (theme) {
        AppTheme.AURORA -> {
            val layer = rememberGraphicsLayer()
            val cache = remember { ArtDraw.CurtainCache() }
            Box(
                base.drawBehind {
                    nightSkyBody()
                    with(ArtDraw) { curtains(Curtains.STILL, true, Strength.of("auroraHeroCurtains"), Strength.of("auroraHeroStars"), layer, cache) }
                },
            )
        }
        AppTheme.NORSE -> {
            val accent = Tok.accent.of(ThemeSet.NORSE)
            val density = LocalDensity.current
            Box(
                base.background(Tok.bg.of(ThemeSet.NORSE), shape).border(1.dp, accent, shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    Runes.BALANCE + Runes.EXPENSES + Runes.GROUPS + Runes.SETTLE,
                    style = Type.rune(runeSize("swatchRune", density)).copy(color = accent, letterSpacing = ThemeArt.m("swatchRuneTracking").em),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        AppTheme.VARDR -> {
            val dot = Tok.ring.of(ThemeSet.VARDR_DARK)
            val mark = Tok.accent.of(ThemeSet.VARDR_DARK)
            Box(
                base.background(Tok.bg.of(ThemeSet.VARDR_DARK), shape).drawBehind {
                    val r = ThemeArt.m("swatchGridDot").dp.toPx()
                    ArtGeometry.gridDots(size.width, size.height, ThemeArt.m("swatchGridPitch").dp.toPx()).forEach { drawCircle(dot, radius = r, center = it) }
                    ArtGeometry.frameMarks(size.width, size.height, ThemeArt.m("swatchMarkInset").dp.toPx(), ThemeArt.m("swatchMarkLength").dp.toPx())
                        .forEach { (a, b) -> drawLine(mark, a, b, strokeWidth = 1.dp.toPx()) }
                },
            )
        }
    }
}

/** D-222 (L62, L66, Design 6): Light or dark, drawn: a 48 dp touch row holding the `modeTrack` track in `accentSoft`,
 *  the theme's pill shape, and three equal segments; the chosen one inset `modeInset`, filled `surface`, with a small
 *  shadow; words in the name role at the segment size, `text` chosen and `textDim` not, one line, shrinking to 0.7.
 *  Norse shows Dark chosen and the control disabled and dimmed ([ModeChoice.shown]). To TalkBack it is a radio group:
 *  each segment a RadioButton ("Light, radio button, not selected, 2 of 3"). The twin of iOS's `ModeControl`. */
@Composable
private fun ModeControl(dark: Boolean) {
    val context = LocalContext.current
    val choice = ModeChoice.shown(Appearance.theme, Appearance.mode)
    val shape = ThemeArt.pillShape(Appearance.theme)
    val track = ThemeArt.m("modeTrack")
    val inset = ThemeArt.m("modeInset")
    // Fix wave (I-2): the row is max(touchTarget, words + 2 * (margin + inset)), the track `margin` inside it and the
    // chosen pill `inset` inside the track, so the track grows with the words as iOS's does.
    val margin = (ThemeArt.m("touchTarget") - track) / 2
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ThemeArt.m("touchTarget").dp)
            .alpha(if (choice.enabled) 1f else ButtonLook.DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().padding(vertical = margin.dp).background(Tok.accentSoft(dark), shape))
        Row(Modifier.fillMaxWidth().padding(horizontal = inset.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            AppearanceMode.entries.forEach { mode ->
                val chosen = mode == choice.chosen
                val size = TypeScale.segment
                // Fix wave (look item 6): the segment is its words, one node, so TalkBack's node is the RadioButton
                // itself with its text and state; the selection takes the full touch row, the pill sits inside it.
                Text(
                    mode.label,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = ThemeArt.m("touchTarget").dp)
                        .selectable(
                            selected = mode == choice.chosen,
                            enabled = choice.enabled,
                            role = Role.RadioButton,
                            onClick = { Appearance.setMode(context, mode) },
                        )
                        .padding(vertical = (margin + inset).dp)
                        .then(
                            if (chosen) Modifier.shadow(ThemeArt.m("segmentShadowRadius").dp, shape).background(Tok.surface(dark), shape)
                            else Modifier,
                        )
                        .heightIn(min = (track - 2 * inset).dp)
                        .wrapContentHeight()
                        .padding(horizontal = 4.dp),
                    style = Type.name(dark, TypeScale.segment.toInt()).copy(color = if (chosen) Tok.text(dark) else Tok.textDim(dark)),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = (size * 0.7f).sp, maxFontSize = size.sp, stepSize = 0.2.sp),
                )
            }
        }
    }
}

/** D-222 (L63, L65, Design 7): the text-size slider, drawn: in a 48 dp touch row, the `sliderTrack` track in `ringTrack`
 *  and its filled part to the thumb's centre in `accent` (the theme's meter shape), the `sliderThumb` thumb in `surface`
 *  ringed `sliderRing` in `accent`, the theme's person tile shape. A tap or drag anywhere on the row picks
 *  [StepSlider.step]; only a new step is written. To TalkBack it is a SeekBar it can adjust (range, steps, set
 *  progress, the spoken value). The two A's are fixed (dp, not sp), both `text`, the large one bold. The twin of iOS's
 *  `TextSizeSlider`. */
@Composable
private fun TextSizeSlider(dark: Boolean) {
    val context = LocalContext.current
    // A step changes the root's font scale, and Compose restarts a pointer handler whenever its density changes, which
    // ended a drag after its first step (seen at the D-222 T3 look). The slider keeps the screen's pixel density with a
    // fixed font scale (everything in it is dp, its A's fixed sizes), so a drag lives through every step it writes.
    val density = Density(LocalDensity.current.density)
    val step = Appearance.textStep
    val thumb = ThemeArt.m("sliderThumb")
    val bar = ThemeArt.m("sliderTrack")
    val meter = ThemeArt.meterShape(Appearance.theme)
    val thumbShape = tileShape(TileShape.of(Appearance.theme, person = true), thumb.toInt())
    CompositionLocalProvider(LocalDensity provides density) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A", style = Type.body(dark).copy(fontSize = with(density) { TypeScale.rangeSmall.dp.toSp() }), modifier = Modifier.clearAndSetSemantics {})
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .heightIn(min = ThemeArt.m("touchTarget").dp)
                    .semantics {
                        contentDescription = AppearanceCopy.CARDS[2]
                        stateDescription = TextSize.accessibilityValue(step)
                        progressBarRangeInfo = ProgressBarRangeInfo(step.toFloat(), TextSize.MIN.toFloat()..TextSize.MAX.toFloat(), steps = TextSize.MAX - TextSize.MIN - 1)
                        setProgress { v ->
                            val s = StepSlider.clamped(kotlin.math.floor(v + 0.5f).toInt())
                            if (s != Appearance.textStep) Appearance.setTextStep(context, s)
                            true
                        }
                    }
                    // Fix wave (I-3): a hardware key or D-pad moves it one step; at an end the key passes on.
                    .onKeyEvent {
                        val next = if (it.type == KeyEventType.KeyDown) StepSlider.keyed(Appearance.textStep, it.key) else null
                        if (next != null) Appearance.setTextStep(context, next)
                        next != null
                    }
                    .focusable()
                    .pointerInput(Unit) {
                        fun pick(x: Float) {
                            val s = StepSlider.step(x, size.width.toFloat(), thumb.dp.toPx())
                            if (s != Appearance.textStep) Appearance.setTextStep(context, s)
                        }
                        // Fix wave (look F2): this block starts at the first pointer event and that event is delivered as
                        // soon as the block suspends; a dispatched launch is not waiting yet, so the first gesture was
                        // lost. Undispatched, both detectors are waiting for the down before it is delivered.
                        coroutineScope {
                            launch(start = CoroutineStart.UNDISPATCHED) { detectTapGestures { pick(it.x) } }
                            launch(start = CoroutineStart.UNDISPATCHED) { detectHorizontalDragGestures(onDragStart = { pick(it.x) }) { change, _ -> pick(change.position.x) } }
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                val centre = StepSlider.centre(step, maxWidth.value, thumb)
                val track = StepSlider.track(maxWidth.value, thumb)
                val accent = Tok.accent(dark)
                Box(Modifier.offset(x = track.start.dp).width((track.endInclusive - track.start).dp).height(bar.dp).background(Tok.ringTrack(dark), meter))
                Box(Modifier.offset(x = track.start.dp).width((centre - track.start).dp).height(bar.dp).background(Tok.accent(dark), meter))
                // The ring is iOS's: a stroke centred on the thumb's shape inset by half the ring (Norse's diamond then
                // ends at its rounded tips, 2.5 through each tip), not an inside border (fix wave, look F4).
                Box(
                    Modifier
                        .offset(x = (centre - thumb / 2).dp)
                        .size(ThemeArt.m("sliderThumb").dp)
                        .background(Tok.surface(dark), thumbShape)
                        .drawBehind {
                            val r = ThemeArt.m("sliderRing").dp.toPx()
                            val outline = thumbShape.createOutline(Size(size.width - r, size.height - r), layoutDirection, this)
                            translate(r / 2, r / 2) { drawOutline(outline, accent, style = Stroke(width = r)) }
                        },
                )
            }
            Text("A", style = Type.body(dark).copy(fontSize = with(density) { TypeScale.rangeLarge.dp.toSp() }, fontWeight = FontWeight.Bold), modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun Card(dark: Boolean, content: @Composable () -> Unit) {
    // D-176: frosted glass over the Aurora glow, 24-dp corners.
    Column(Modifier.glassCard(dark)) { content() }
}
