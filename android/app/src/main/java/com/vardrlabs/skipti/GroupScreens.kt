package com.vardrlabs.skipti

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.skipti_core.CoreException
import uniffi.skipti_core.NamedGroupWire

/**
 * Groups and people on Android (D-136), the twin of iOS GroupSheets.swift and
 * GroupPicker.swift (D-135). Founder decisions, 2026-09-14: many groups with a
 * list; people are added by name and linked to a device later; your name is
 * asked once and shown as "Tim (You)" to you and "Tim" to everyone else.
 *
 * Ids and name rules live in core (D-134); LedgerStore stores them. These
 * screens are routes over HomeShell's tabs (like the account surface), each
 * with its own Back, and every vault call runs off the main thread.
 */

/** Plain-language copy for core's group and name errors (SPEC §11.6). */
object GroupCopy {
    /** D-174: "1 person", never "1 people" (the founder's screenshot). */
    fun people(count: Int): String = if (count == 1) "1 person" else "$count people"

    fun message(e: Throwable): String = when (e) {
        is CoreException.UnknownCurrency -> "That currency is not supported."
        is CoreException -> {
            val detail = AddExpenseUi.humanError(e)
            when {
                "already has that name" in detail ->
                    "Someone in this group already has that name. Add a surname or initial."
                "longer than" in detail -> "That name is too long. Use 64 characters or fewer."
                "member needs a name" in detail -> "Every person needs a name."
                "group needs a name" in detail -> "The group needs a name."
                "control character" in detail -> "Names can't include line breaks or tabs."
                else -> detail
            }
        }
        is NeedsYourNameException -> "Add your name first."
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.toString()
    }
}

private suspend fun <T> offMain(block: () -> T): Result<T> =
    withContext(Dispatchers.IO) { runCatching(block) }

/** Asks for your name once. The copy states exactly who sees what. */
@Composable
fun YourNameScreen(vaultPath: String, onSaved: () -> Unit, onBack: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val preview = name.trim().ifEmpty { "Your name" }

    fun save() {
        if (name.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            val result = offMain { LedgerStore(vaultPath).setYourName(name) }
            busy = false
            // D-211 fix 5: the saved name is a record; the sync hears of it at once.
            if (result.isSuccess) CloudSync.ledgerChanged(vaultPath)
            result.fold(onSuccess = { onSaved() }, onFailure = { error = GroupCopy.message(it) })
        }
    }

    // D-217 Task 8: iOS's sheet: a Cancel pill, the inline title, and "Save name" pinned under the form.
    SheetPage(
        "Your name", dark, onCancel = onBack, spacing = 12.dp, top = BarDecisions.formTop(headerless = false).dp,
        actions = { PrimaryAction("Save name", dark, enabled = name.isNotBlank() && !busy) { save() } },
    ) {
        // D-225 T7 (Design 12): iOS's section: the "Your name" label, the field card, the caption under it.
        SectionLabel("Your name", dark)
        ListCard(dark) { row { NameField(name, { name = it }, "Your name", dark, onDone = { save() }) } }
        CardCaption("You will see yourself as \"$preview (You)\". People you share a group with see \"$preview\".", dark)
        error?.let { ErrorLine(it, dark) }
    }
}

/** Creates a group: its name, its settlement currency (fixed for the group's
 *  life, SPEC §5.2), and people added by name. Your name is asked here only
 *  while it is still unset. */
@Composable
fun CreateGroupScreen(vaultPath: String, onCreated: (String) -> Unit, onBack: () -> Unit, homeKeep: HomeKeep? = null) {
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    var groupName by rememberSaveable { mutableStateOf("") }
    // D-165: starts in the default currency (it was always INR).
    var currency by rememberSaveable { mutableStateOf(DefaultCurrency.current) }
    var yourName by rememberSaveable { mutableStateOf("") }
    var people by rememberSaveable(stateSaver = PeopleDraft.Saver) { mutableStateOf(PeopleDraft()) }
    // D-248 (U29.2): each person row's focus, by row id, and the row Next moved to (focused once it is composed).
    val rowFocus = remember { mutableMapOf<Int, FocusRequester>() }
    var focusNext by remember { mutableStateOf<Int?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusNext) {
        val id = focusNext ?: return@LaunchedEffect
        runCatching { rowFocus[id]?.requestFocus() }
        focusNext = null
    }
    /** D-248 (U29.2): Next on a person row ([PeopleDraft.afterReturn]); an empty last row ends editing. */
    fun returned(id: Int) {
        val (next, row) = people.afterReturn(id)
        people = next
        if (row == null) {
            focusManager.clearFocus()
            keyboardController?.hide()
        } else {
            focusNext = row
        }
    }
    var needsYourName by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // D-225 T7 (Design 12): the currency list page Settles in opens.
    var listingCurrencies by rememberSaveable { mutableStateOf(false) }
    // D-187 (the founder): a picture when making the group.
    var pictureEmoji by rememberSaveable { mutableStateOf<String?>(null) }
    var picturePhoto by remember { mutableStateOf<ByteArray?>(null) }
    val choosePicture = rememberGroupPictureChooser(
        dark,
        onChosen = { emoji, jpeg -> pictureEmoji = emoji; picturePhoto = jpeg },
        onError = { error = it },
    )

    LaunchedEffect(vaultPath) {
        offMain { LedgerStore(vaultPath).ensureSelf().name == null }.fold(
            onSuccess = { needsYourName = it },
            onFailure = { error = GroupCopy.message(it) },
        )
    }
    val canCreate = groupName.isNotBlank() && !busy &&
        (needsYourName == false || (needsYourName == true && yourName.isNotBlank()))

    // D-217 Task 8: iOS's sheet: a Cancel pill, the inline title, and "Create group" pinned under the form.
    SheetPage("New group", dark, onCancel = onBack, spacing = 12.dp, top = BarDecisions.formTop(headerless = false).dp, actions = {
        PrimaryAction("Create group", dark, enabled = canCreate) {
            busy = true
            error = null
            scope.launch {
                val askName = needsYourName == true
                val result = offMain {
                    val store = LedgerStore(vaultPath)
                    val created = CreateGroup.create(
                        store, if (askName) yourName else null, groupName, currency, people.names,
                        pictureEmoji, picturePhoto,
                    ) { CloudSync.ledgerChanged(vaultPath) }
                    GroupPictures.load(store)
                    // D-224 follow-up (A-R3): Home's new page, when the create returns to it, so the first group's
                    // moment never plays over the empty Home and its reload (iOS's Home is loaded under the sheet).
                    val home = homeKeep?.let { HomeKeep.read { store } }
                    // D-224 (Design 7): the named groups after the create; the first one is the big moment. A failed
                    // read is null, the toast: never big without proof.
                    Triple(created, runCatching { store.namedGroups().size }.getOrNull(), home)
                }
                busy = false
                result.fold(
                    onSuccess = { (created, count, home) ->
                        home?.let { homeKeep?.take(it) }
                        DoneCenter.post(
                            DoneMoments.groupCreated(
                                count, created.group.name,
                                GroupsCopy.detailParts(created.members.size, created.group.settlementCurrency),
                            ),
                        )
                        // D-248 (U30.6): the new group's id, so the shell opens its page.
                        onCreated(created.group.id)
                    },
                    onFailure = { error = GroupCopy.message(it) },
                )
            }
        }
    }) {
        // D-225 T7 (Design 12): each of iOS's sections as a label over a list card, its caption 8 under the card.
        SectionLabel("Group", dark)
        ListCard(dark) {
            row { NameField(groupName, { groupName = it }, "Group name", dark) }
            row {
                Row(
                    Modifier.padding(vertical = ThemeArt.m("rowPadV").dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PictureTile(
                        "new", groupName.ifBlank { "?" },
                        PictureDTO(pictureEmoji, picturePhoto?.let { HexCodec.encode(it) }).takeIf { pictureEmoji != null || picturePhoto != null },
                        dark, 48,
                    )
                    SecondaryAction(GroupPictures.TITLE, dark) { choosePicture() }
                }
            }
            // Settles in opens the currency list page (its "Common" section first), as Your data's Default currency.
            row { ListRow("Settles in", dark, trailing = RowTrailing.Value(currency, chevron = true)) { listingCurrencies = true } }
        }
        CardCaption("A group settles in one currency, and it cannot be changed later.", dark)
        if (needsYourName == true) {
            SectionLabel("You", dark)
            ListCard(dark) { row { NameField(yourName, { yourName = it }, "Your name", dark) } }
            CardCaption("You see yourself as \"Name (You)\". Others in the group see only the name.", dark)
        }
        SectionLabel("People", dark)
        // Every row gone leaves no empty card: the label, then "Add a person".
        // D-250 fix wave (M4): the card itself moves too, so the last person removed leaves with it, as the others do.
        MovingRows(listOfNotNull(people.rows.takeIf { it.isNotEmpty() }), key = { "people" }) { shownRows, _ ->
            // D-250: ListCard's look, its rows moving: a person added comes in and one removed leaves, the rows below
            // closing up (keyed by the row's id, as before); a cut when motion is reduced.
            ListCardColumn(dark) {
                MovingRows(shownRows, key = { it.id }) { r, at ->
                    if (at > 0) ListCardHairline(dark)
                    val index = people.rows.indexOfFirst { it.id == r.id }.coerceAtLeast(0)
                    NameField(
                        r.name,
                        { text -> people = people.rename(r.id, text) },
                        "Name",
                        dark,
                        spoken = "Person ${index + 1}",
                        focus = rowFocus.getOrPut(r.id) { FocusRequester() },
                        onNext = { returned(r.id) },
                        trailing = {
                            RemoveGlyphButton(
                                if (r.name.isBlank()) "Remove person ${index + 1}" else "Remove ${r.name}",
                                dark,
                            ) { people = people.remove(r.id) }
                        },
                    )
                }
            }
        }
        SecondaryAction(people.addLabel, dark) { people = people.add() }
        CardCaption("Add people by name. They do not need SKIPTI; linking a name to their phone comes later.", dark)
        error?.let { ErrorLine(it, dark) }
    }
    if (listingCurrencies) {
        CurrencyListDialog(
            selected = currency,
            dark = dark,
            onPick = {
                currency = it
                listingCurrencies = false
            },
            onClose = { listingCurrencies = false },
        )
    }
}

/**
 * D-211 (the look found it on devices): creating a group is a set of group
 * operations, and they have to reach the cloud at once, not at the next
 * unrelated write. [create] saves the name (when asked), the group and its
 * picture, then calls [synced] once (the sync's `ledgerChanged`); nothing is
 * called when anything is refused. iOS's twin is `CreateGroup` in
 * GroupSheets.swift.
 */
object CreateGroup {
    fun create(
        store: LedgerStore, yourName: String?, name: String, currency: String, people: List<String>,
        emoji: String?, photo: ByteArray?, synced: () -> Unit,
    ): NamedGroupWire {
        if (yourName != null) store.setYourName(yourName)
        val created = store.createGroup(name, currency, people)
        if (emoji != null || photo != null) store.setGroupPicture(created.group.id, emoji, photo)
        synced()
        return created
    }
}

/** What the People screen reads in one go (off the main thread). */
private class PeopleLoad(
    val named: NamedGroupWire?,
    val people: GroupPeople,
    val viewerHasBalance: Boolean,
    val invitable: List<uniffi.skipti_core.MemberWire>,
    val removable: Set<String>,
    val cardBalances: Map<String, String>,
)

/** One group's people: rename anyone, including yourself; add someone by name.
 *  Core's rules (D-134) refuse a duplicate or over-long name with plain copy.
 *  D-210: a group in the cloud shows each person's role and a sheet per person
 *  (remove or ask, rename, role), and the open removal requests as cards; a
 *  group only on this phone keeps D-186's cross and rename-on-tap; a read-only
 *  copy shows who is in it and nothing to do with them. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    vaultPath: String,
    groupId: String,
    onBack: () -> Unit,
    /** D-210: opens a removal (a request's review, or a person's Remove). */
    onRemove: (RemovalRoute) -> Unit = {},
    /** D-210: a request card's Settle up opens that tab. */
    onSettleUp: () -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    // D-186: who can be taken out (no history, never joined, not you), and
    // the one being asked about.
    var removable by remember { mutableStateOf<Set<String>>(emptySet()) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var named by remember { mutableStateOf<NamedGroupWire?>(null) }
    // D-207: the add-person screen is open.
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editText by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loads by remember { mutableIntStateOf(0) }
    // D-159/D-195: members not yet linked to an account, shown only with an
    // account of your own (inviting needs one; GUARANTEES #5).
    var invitable by remember { mutableStateOf<List<uniffi.skipti_core.MemberWire>>(emptyList()) }
    var inviting by rememberSaveable { mutableStateOf<String?>(null) }
    // D-210: what this phone knows about the group's cloud, roles and requests.
    var people by remember { mutableStateOf(GroupPeople()) }
    var viewerHasBalance by remember { mutableStateOf(false) }
    // D-211: each group's people with a profile (previews; Rename absent).
    var previews by remember { mutableStateOf<Map<String, Map<String, Avatar>>>(emptyMap()) }
    var cardBalances by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var cardBusy by remember { mutableStateOf(false) }
    // The person's sheet is owned here, not by a row that can go.
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    // The last read of the group failed: whether it is a copy is unknown, so it
    // is treated as one (nothing offered) until a read works.
    var loadFailed by remember { mutableStateOf(false) }
    val requestIds = Notices.requests(groupId).map { it.id }

    // Whatever arrived (a removal or a deletion applied to this phone, a role)
    // reads the group again; so does a request coming or going.
    LaunchedEffect(vaultPath, groupId, loads, inviting, SyncStatus.revision, requestIds) {
        // D-211: read first and apart from the rest. A picture is decoration: a
        // failed read keeps the last good previews and never fails the screen
        // (which would show the people as a read-only copy).
        val lastPreviews = previews
        previews = offMain { MemberPreviews.reloaded(lastPreviews) { LedgerStore(vaultPath).memberPreviews() } }
            .getOrDefault(lastPreviews)
        offMain {
            val store = LedgerStore(vaultPath)
            // The roles this phone last heard, offline too.
            GroupRoles.load(store, groupId)
            val found = store.namedGroups().firstOrNull { it.group.id == groupId }
            val loaded = GroupPeople.load(store, groupId)
            val balance = found?.let { Removal.plan(Removal.lines(store, groupId, it.selfId), it.selfId).isNotEmpty() } ?: false
            PeopleLoad(
                found, loaded, balance, InviteFlow.invitable(store, found, Account.isSignedIn()),
                found?.members.orEmpty().map { it.id }.filter { store.removalRefusal(groupId, it) == null }.toSet(),
                RequestCards.balanceLines(store, groupId, Notices.requests(groupId)),
            )
        }.fold(
            onSuccess = {
                named = it.named
                people = it.people
                viewerHasBalance = it.viewerHasBalance
                invitable = it.invitable
                removable = it.removable
                cardBalances = it.cardBalances
                loadFailed = false
            },
            onFailure = {
                loadFailed = true
                error = "Could not load people. ${GroupCopy.message(it)}"
            },
        )
    }

    // The typed text is cleared only when the save succeeded, so a refused
    // name stays in the field to be corrected.
    fun save(change: (LedgerStore) -> NamedGroupWire, onSaved: () -> Unit) {
        scope.launch {
            offMain {
                change(LedgerStore(vaultPath))
            }.fold(
                onSuccess = { changed ->
                    // D-211 fix 5: every change here (rename, removal) is a group
                    // operation; the sync hears of it as soon as it is saved.
                    CloudSync.ledgerChanged(vaultPath)
                    named = changed
                    error = null
                    // D-186: who can be taken out changes with every change
                    // (someone just added can); the load works it out again.
                    loads++
                    onSaved()
                },
                onFailure = { error = GroupCopy.message(it) },
            )
        }
    }

    if (adding) {
        androidx.activity.compose.BackHandler { adding = false; loads++ }
        AddPersonScreen(vaultPath, groupId, onBack = { adding = false; loads++ })
        return
    }
    val invitee = inviting?.let { id -> invitable.firstOrNull { it.id == id } }
    named?.let { group ->
        if (invitee != null) {
            // D-217 (oddity 5): system Back goes up one level, to People.
            androidx.activity.compose.BackHandler { inviting = null }
            InviteScreen(vaultPath, group, invitee, onBack = { inviting = null })
            return
        }
    }

    val isCopy = people.readOnly != null || loadFailed
    val rows = named?.let {
        PeopleRoles.rows(
            it.members, it.selfId, people.viewerYou, people.claims, GroupRoles.roles[groupId],
            yourPlaces = GroupRoles.roles[groupId]?.places?.you, otherYouIds = people.otherYouIds, readOnly = isCopy,
            createdHere = people.createdHere,
        )
    } ?: emptyList()
    val cards = if (named != null && people.shared && !isCopy) RequestCards.cards(Notices.requests(groupId), rows) else emptyList()
    val viewerName = named?.members?.firstOrNull { it.id == named?.selfId }?.name ?: ""

    fun decline(item: Notices.Item, group: NamedGroupWire) {
        cardBusy = true
        scope.launch {
            try {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { RoleCalls.decline(item, group, viewerName) }
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = RolesCopy.failure(e, person = true)
            } finally {
                cardBusy = false
            }
        }
    }

    // D-225 (Design 8; L-D217-8): the request cards as before; the "People" label over one list card of members (the
    // stays caption under it for a phone-only group); the "Invite" label over a list card of Invite rows and its caption;
    // "Add a person" as the page's primary button below the cards. 16 apart, each label and caption 8 from its card.
    PushedPage("People", dark, onBack, spacing = 16.dp) {
        named?.let { group ->
            cards.forEach { card ->
                RequestCardView(
                    card, dark, canSettleUp = viewerHasBalance, balance = cardBalances[card.id], busy = cardBusy,
                    onDecline = { decline(card.item, group) },
                    onReview = {
                        card.item.memberId?.let { onRemove(RemovalRoute(groupId, it, review = true, fromPeople = true)) }
                    },
                    onSettle = onSettleUp,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("People", dark)
                ListCard(dark) {
                    group.members.forEach { member ->
                        row {
                            val label = LedgerStore.label(member.id, group)
                            val roleRow = rows.firstOrNull { it.member.id == member.id }
                            val avatar: @Composable () -> Unit = {
                                PersonAvatar(member.name, dark, 36, MemberPreviews.preview(previews, groupId, member.id))
                            }
                            if (editingId == member.id) {
                                FieldRow(
                                    editText, { editText = it }, "Name", dark,
                                    // D-225 fix wave (M7): TalkBack hears that the row is being renamed, as VoiceOver does.
                                    spoken = MemberCopy.RENAME_SPOKEN,
                                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                                    actions = KeyboardActions(onDone = {
                                        val text = editText
                                        save({ it.renameMember(groupId, member.id, text) }) { editingId = null }
                                    }),
                                )
                            } else if (isCopy) {
                                // A copy: who is in it, nothing to do with them.
                                MemberRow(label, dark, avatar = avatar)
                            } else if (people.shared && roleRow != null) {
                                MemberRow(
                                    label, dark, avatar = avatar, pill = { RolePill(roleRow.role, dark) },
                                    end = { RowChevron(Tok.textDim(dark)) }, onClickLabel = member.name,
                                ) { sheetId = member.id }
                            } else if (!MemberPreviews.mayRename(previews, groupId, member.id)) {
                                // D-211: a person with a profile: their name is theirs, so no Rename.
                                MemberRow(label, dark, avatar = avatar)
                            } else {
                                MemberRow(
                                    label, dark,
                                    end = if (member.id in removable) {
                                        { RemoveGlyphButton(MemberCopy.spokenRemove(member.name), dark) { removing = member.id } }
                                    } else {
                                        null
                                    },
                                    onClickLabel = "Rename",
                                ) {
                                    editingId = member.id
                                    editText = member.name
                                }
                            }
                        }
                    }
                }
                // A group in the cloud lets the owner or an admin remove anyone.
                if (!people.shared) CardCaption(MemberCopy.STAYS, dark)
            }
            // D-224 (Design 3): the warning as the removal's question opens.
            HapticWarning(removing != null)
            group.members.firstOrNull { it.id == removing }?.let { member ->
                SkiptiAlertDialog(
                    onDismissRequest = { removing = null },
                    title = { Text(MemberCopy.removeTitle(member.name, group.group.name)) },
                    text = { Text(MemberCopy.REMOVE_BODY) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            removing = null
                            save({ it.removeMember(groupId, member.id) }) {
                                DoneCenter.post(DoneMoments.line(DoneCopy.personRemoved(member.name)))
                            }
                        }) { Text(MemberCopy.REMOVE, color = Tok.owed(dark)) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { removing = null }) { Text(MemberCopy.CANCEL) }
                    },
                )
            }
        }
        if (invitable.isNotEmpty() && !isCopy) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(InviteCopy.SECTION, dark)
                ListCard(dark) {
                    invitable.forEach { member ->
                        row { ListRow(InviteCopy.invite(member.name), dark, trailing = RowTrailing.Chevron) { inviting = member.id } }
                    }
                }
                CardCaption(InviteCopy.SECTION_FOOTER, dark)
            }
        }
        // D-207: the add-person screen, with a @username lookup and recents.
        if (!isCopy) PrimaryAction(AddPersonCopy.TITLE, dark) { adding = true }
        error?.let { ErrorLine(it, dark) }
    }

    // D-210: a person's sheet (shared groups only); the removal it opens is its own screen.
    val sheetRow = sheetId?.let { id -> rows.firstOrNull { it.member.id == id } }
    val group = named
    if (sheetRow != null && group != null && people.shared && !isCopy) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { sheetId = null },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Tok.surface(dark),
        ) {
            SkiptiWindowLocals {
                PersonSheetContent(
                    vaultPath, group, sheetRow, viewerName, dark,
                    preview = MemberPreviews.preview(previews, groupId, sheetRow.member.id),
                    canRename = MemberPreviews.mayRename(previews, groupId, sheetRow.member.id),
                    onRename = {
                        sheetId = null
                        editingId = sheetRow.member.id
                        editText = sheetRow.member.name
                    },
                    onRemove = {
                        sheetId = null
                        onRemove(RemovalRoute(groupId, sheetRow.place, review = false, fromPeople = true))
                    },
                    onDone = {
                        sheetId = null
                        loads++
                        SyncStatus.changed()
                    },
                    onCancel = { sheetId = null },
                )
            }
        }
    }
}

/** D-186: taking a person out of a group, in words. iOS's twin is
 *  `MemberCopy` in GroupPicker.swift. */
object MemberCopy {
    enum class Refusal { YOU, JOINED, HISTORY }

    class Refused(val why: Refusal) : Exception(message(why))

    fun removeTitle(name: String, group: String) = "Remove $name from $group?"
    const val REMOVE_BODY = "They are in no expense or payment here. You can add them again later."
    const val REMOVE = "Remove"
    const val CANCEL = "Cancel"
    const val STAYS = "Anyone in an expense or payment here, or who joined on their phone, stays in the group."
    const val ADD_PEOPLE = "Add people"
    /** D-225 fix wave (M7): the rename field's name to TalkBack (iOS's `spoken: "New name"`). */
    const val RENAME_SPOKEN = "New name"
    fun spokenRemove(name: String) = "Remove $name"

    fun message(why: Refusal): String = when (why) {
        Refusal.YOU -> "You cannot take yourself out of a group."
        Refusal.JOINED -> "They joined on their phone, so they stay in the group."
        Refusal.HISTORY -> "They are in an expense or payment here, so they stay in the group."
    }
}

// ---- Shared pieces ----

/** D-225 T7 (Design 12): a name as a field row of its card ([FieldRow]: the placeholder in `textFaint`, the 48 dp row,
 *  an optional trailing cross); words capitalised, Next or Done on the keyboard. [spoken] names the field to TalkBack
 *  when the placeholder alone would not ("Person 2"). */
@Composable
private fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    dark: Boolean,
    spoken: String? = null,
    onDone: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** D-248 (U29.2): a person row of New group: the keyboard's Next is the form's ([onNext]), and the form moves the
     *  keyboard with [focus]. */
    focus: FocusRequester? = null,
    onNext: (() -> Unit)? = null,
) {
    FieldRow(
        value,
        onValueChange,
        label,
        dark,
        keyboard = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
        ),
        actions = if (onNext != null) {
            KeyboardActions(onNext = { onNext() })
        } else {
            KeyboardActions(onDone = { onDone?.invoke() })
        },
        spoken = spoken ?: label,
        trailing = trailing,
        fieldModifier = focus?.let { Modifier.focusRequester(it) } ?: Modifier,
    )
}

/** D-172: the people rows of a new group, each with a stable id so a row
 *  removed from the middle takes its own name with it (the founder,
 *  2026-09-26: a person added by mistake could not be taken out). Twin of
 *  iOS's `PeopleDraft`. */
data class PeopleDraft(val rows: List<Row> = listOf(Row(0, "")), val nextId: Int = 1) {
    data class Row(val id: Int, val name: String)

    fun add() = copy(rows = rows + Row(nextId, ""), nextId = nextId + 1)
    fun remove(id: Int) = copy(rows = rows.filterNot { it.id == id })
    fun rename(id: Int, name: String) = copy(rows = rows.map { if (it.id == id) it.copy(name = name) else it })

    /** What `createGroup` receives; it ignores blank names. */
    val names: List<String> get() = rows.map { it.name }

    /** The button reads "Add a person" once every row is gone. */
    val addLabel: String get() = if (rows.isEmpty()) "Add a person" else "Add another person"

    /** D-248 (U29.2): Next on row [id]: the draft after it, and the row to focus next (null: editing ends). The last row
     *  with a name adds a new row and moves to it; an empty last row ends editing; any other row moves to the one below.
     *  The twin of iOS's `PeopleDraft.afterReturn`. */
    fun afterReturn(id: Int): Pair<PeopleDraft, Int?> {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return this to null
        if (index < rows.size - 1) return this to rows[index + 1].id
        if (rows[index].name.isBlank()) return this to null
        val added = add()
        return added to added.rows.last().id
    }

    companion object {
        /** Survives rotation, as the plain name list did. */
        val Saver = listSaver<PeopleDraft, Any>(
            save = { d -> listOf<Any>(d.nextId) + d.rows.flatMap { listOf(it.id, it.name) } },
            restore = { l ->
                PeopleDraft(
                    rows = l.drop(1).chunked(2).map { Row(it[0] as Int, it[1] as String) },
                    nextId = l[0] as Int,
                )
            },
        )
    }
}

/** D-225 (Design 8): the cross that removes one row: Lucide's `x` (NOTICE) at `clearGlyph` 18 in `textFaint`, in a 48 dp
 *  target. The twin of iOS's `LucideIcon(glyph: .x)` cross. */
@Composable
internal fun RemoveGlyphButton(spoken: String, dark: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(ThemeArt.m("touchTarget").dp).semantics { contentDescription = spoken },
    ) {
        androidx.compose.material3.Icon(
            TabIcons.X, contentDescription = null, tint = Tok.textFaint(dark), modifier = Modifier.size(ThemeArt.m("clearGlyph").dp),
        )
    }
}

/** D-225 (Design 8; L-D217-8): a member row of People's card, at the list row's metrics: the avatar 36 when given, a
 *  row gap, the name in the name role at 16 with the role pill beside it, then the end piece (the chevron when the row
 *  opens the person's sheet, or the cross). The 48 dp cross stands in the row's own 48 dp minimum, outside the text's
 *  padding, so a row with a cross is as tall as one without. One TalkBack button when the row does something. */
@Composable
private fun MemberRow(
    name: String,
    dark: Boolean,
    avatar: (@Composable () -> Unit)? = null,
    pill: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ThemeArt.m("touchTarget").dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).padding(vertical = ThemeArt.m("rowPadV").dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (avatar != null) {
                avatar()
                Spacer(Modifier.width(ThemeArt.m("rowGap").dp))
            }
            Text(
                name,
                style = Type.name(dark, 16).copy(color = Tok.text(dark)),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (pill != null) {
                Spacer(Modifier.width(8.dp))
                pill()
            }
        }
        if (end != null) {
            Spacer(Modifier.width(ThemeArt.m("rowGap").dp))
            end()
        }
    }
}

/** Filled accent; dims when disabled (the D-128 rule, Android's shape). D-213: Vardr Labs draws it outlined.
 *  [modifier] is the layout the call site wants (full width by default). D-221 (Design 10): [compact] is iOS's
 *  `PrimaryButtonStyle(compact: true)`, the next cards' trailing button: its own width (no full width unless the call
 *  site asks), the label at 15, padding 16 / 10, the same looks, shape and dimming. */
@Composable
internal fun PrimaryAction(
    label: String, dark: Boolean, enabled: Boolean = true, tint: Color? = null,
    compact: Boolean = false,
    modifier: Modifier = if (compact) Modifier else Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    val size = if (compact) 15 else 17
    val padding = if (compact) PaddingValues(horizontal = 16.dp, vertical = 10.dp) else ButtonDefaults.ContentPadding
    val minHeight = if (compact) 0.dp else 52.dp
    if (ButtonLook.of(Appearance.theme) == ButtonLook.OUTLINED) {
        // D-213 (the founder, 2026-10-15): Vardr Labs' main button is outlined: a 1 dp border and the words in
        // the tint, IBM Plex Mono uppercase at .08 em, square (art.py measures words and border).
        val color = tint ?: Tok.accent(dark)
        // D-220 (L-D217-4): a disabled button draws its enabled colours at ButtonLook.DISABLED_ALPHA, as iOS does.
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = RectangleShape,
            border = BorderStroke(1.dp, color),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = color),
            contentPadding = padding,
            modifier = modifier.heightIn(min = minHeight).alpha(enabledAlpha(enabled)),
        ) {
            Text(ButtonLook.label(label, Appearance.theme), style = Type.label(dark, size).copy(color = LocalContentColor.current, letterSpacing = 0.08.em))
        }
        return
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        // D-174 (ADR-0038): taller, rounder, the label in the app's own face
        // and weight (it drew in the system font before), on-accent.
        // D-210: a removal's confirm takes the owe coral (iOS: `tint:`).
        // D-220 (L-D217-4): disabled keeps the enabled colours and dims them (ButtonLook.DISABLED_ALPHA).
        colors = ButtonDefaults.buttonColors(
            containerColor = tint ?: Tok.accent(dark),
            contentColor = Tok.onAccent(dark),
            disabledContainerColor = tint ?: Tok.accent(dark),
            disabledContentColor = Tok.onAccent(dark),
        ),
        shape = ThemeArt.controlShape(Appearance.theme),
        contentPadding = padding,
        modifier = modifier.heightIn(min = minHeight).alpha(enabledAlpha(enabled)),
    ) {
        Text(label, style = Type.label(dark, size).copy(color = LocalContentColor.current))
    }
}

/** Hairline outline, dim text. */
@Composable
internal fun SecondaryAction(label: String, dark: Boolean, onClick: () -> Unit) =
    TonalAction(label, dark, destructive = false, onClick = onClick)

/** D-210: dimmed and inert while [enabled] is false. D-213: [keepsCase] keeps an
 *  identifier's case in Vardr Labs (an @username suggestion; handles are lowercase); the face and tracking stay.
 *  D-213 (fix wave 2): [modifier] is the caller's placement (a Row's `weight`, a wrapped width); it defaults to
 *  the full width, as PrimaryAction's does. */
@Composable
internal fun SecondaryAction(
    label: String,
    dark: Boolean,
    enabled: Boolean = true,
    keepsCase: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) = TonalAction(label, dark, destructive = false, enabled = enabled, keepsCase = keepsCase, modifier = modifier, onClick = onClick)

/** D-176: the Danger zone's actions, tonal in the owe coral. */
@Composable
internal fun DangerAction(label: String, dark: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) =
    TonalAction(label, dark, destructive = true, modifier = modifier, onClick = onClick)

@Composable
internal fun TonalAction(
    label: String,
    dark: Boolean,
    destructive: Boolean,
    enabled: Boolean = true,
    keepsCase: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    if (ButtonLook.of(Appearance.theme) == ButtonLook.OUTLINED) {
        // D-213: Vardr Labs' second action is plain text in the accent (or the owe colour).
        // D-220 (L-D217-4): disabled draws the enabled words at ButtonLook.DISABLED_ALPHA.
        val words = if (destructive) Tok.owed(dark) else Tok.accent(dark)
        TextButton(
            onClick = onClick,
            enabled = enabled,
            shape = RectangleShape,
            colors = ButtonDefaults.textButtonColors(contentColor = words, disabledContentColor = words),
            modifier = modifier.heightIn(min = 52.dp).alpha(enabledAlpha(enabled)),
        ) {
            Text(ButtonLook.label(label, Appearance.theme, keepsCase), style = Type.label(dark).copy(color = LocalContentColor.current, letterSpacing = 0.08.em))
        }
        return
    }
    // D-174 (ADR-0038): tonal, not an outline: the accent's soft fill reads
    // as a button without competing with the primary. Twin of iOS's
    // SecondaryButtonStyle.
    Button(
        onClick = onClick,
        enabled = enabled,
        // D-176: the Danger zone's actions take the owe coral.
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) Tok.owedSoft(dark) else Tok.accentSoft(dark),
            contentColor = if (destructive) Tok.owed(dark) else Tok.accent(dark),
            // Dims when disabled (the D-128 rule, as the primary does). D-220 (L-D217-4): the enabled colours at
            // ButtonLook.DISABLED_ALPHA, as iOS does.
            disabledContainerColor = if (destructive) Tok.owedSoft(dark) else Tok.accentSoft(dark),
            disabledContentColor = if (destructive) Tok.owed(dark) else Tok.accent(dark),
        ),
        shape = ThemeArt.controlShape(Appearance.theme),
        modifier = modifier.heightIn(min = 52.dp).alpha(enabledAlpha(enabled)),
    ) {
        Text(
            label,
            style = Type.label(dark).copy(color = if (destructive) Tok.owed(dark) else Tok.accent(dark)),
        )
    }
}

@Composable
internal fun ErrorLine(message: String, dark: Boolean, detail: Boolean = false) {
    // D-217 look fix 3: iOS draws some error lines at 14 (Import, the erase failure), the rest at the body size.
    val base = Type.bodyDim(dark).copy(color = Tok.owed(dark))
    // D-250: an error line rises in when it appears (iOS's `.reveal`) and leaves at once.
    RevealOnEnter {
        Text(
            message,
            style = if (detail) base.copy(fontSize = TypeScale.detail.sp) else base,
            modifier = Modifier.semantics { contentDescription = message },
        )
    }
}

