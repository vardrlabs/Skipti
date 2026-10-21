package com.vardrlabs.skipti

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.IdAlias
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ffiBalanceLine
import kotlin.math.abs

/**
 * D-210: a group's settings (its name, picture and currency, and deleting
 * it), the banner and actions of a read-only copy. iOS's twin is
 * GroupSettings.swift.
 */

/**
 * D-210 (Task 8 look): the owner's delete for everyone as one sequence. A retry
 * after a first try that deleted in the cloud but failed to mark this copy gets
 * NOT_FOUND from the Function: the group is already gone, so it is not an
 * error, and the copy is marked as it would have been. Every other failure is
 * rethrown for the screen's words. iOS's twin is `GroupDelete`.
 */
object GroupDelete {
    /** The mark failed (the cloud part is done or was already done). */
    class MarkFailed(cause: Throwable) : Exception(cause)

    fun alreadyGone(error: Throwable): Boolean =
        (error as? CloudFunctions.Failure.Refused)?.status == "NOT_FOUND"

    suspend fun run(call: suspend () -> Unit, mark: () -> Unit) {
        try {
            call()
        } catch (e: CloudFunctions.Failure) {
            if (!alreadyGone(e)) throw e
        }
        try {
            mark()
        } catch (e: Exception) {
            throw MarkFailed(e)
        }
    }
}

object GroupSettings {
    /** What the settings screen offers. */
    data class Actions(val name: Boolean, val picture: Boolean, val delete: Boolean)

    /** A copy (or a group whose mark could not be read: fail closed) offers
     *  nothing that changes it. Delete group: the owner of a group in the
     *  cloud, anyone with a group only on their phone; absent otherwise
     *  (D-126). iOS's twin is `GroupSettings.actions(readOnly:isOwner:shared:)`. */
    fun actions(readOnly: Boolean, isOwner: Boolean, shared: Boolean): Actions =
        if (readOnly) Actions(name = false, picture = false, delete = false)
        else Actions(name = true, picture = true, delete = if (shared) isOwner else true)

    /** What is still open in a group, for the delete confirm: every person's
     *  nonzero balance, per currency (the currencies in order, the largest
     *  first), as "Sam owes ₹1,000.00 · you are owed ₹700.00" ([viewer] reads
     *  as "you"); null when nothing is open. Amounts come from core's formatter. */
    fun stillOpen(nets: Map<String, Map<String, Long>>, names: Map<String, String>, viewer: String? = null): String? {
        val parts = mutableListOf<String>()
        for (code in nets.keys.sorted()) {
            val entries = (nets[code] ?: emptyMap()).entries.filter { it.value != 0L }
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { abs(it.value) }.thenBy { it.key })
            for ((id, net) in entries) {
                val amount = RolesMoney.format(abs(net), code) ?: continue
                if (id == viewer) {
                    parts += if (net < 0) RolesCopy.youOwe(amount) else RolesCopy.youAreOwed(amount)
                } else {
                    val name = names[id] ?: LedgerStore.UNKNOWN_NAME
                    parts += if (net < 0) RolesCopy.owes(name, amount) else RolesCopy.isOwed(name, amount)
                }
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(Separator.meta())
    }

    /** The same line from what this phone holds: every person's net per
     *  currency read through core's balance lines (`ffiBalanceLine`, with the
     *  group's redistributions) framed on each person who is, or was, in the
     *  group. */
    fun stillOpen(store: LedgerStore, group: String, viewer: String?): String? {
        val names = store.historyNames(group)
        val expenses = store.loadExpenses().filter { it.group == group }.map { it.toInput() }
        val settlements = store.loadSettlements().filter { it.group == group }.map { it.toInput() }
        val redistributions = store.loadRedistributions().filter { it.group == group }
        val removed = store.removedIds(group)
        val ids = names.keys.toMutableSet()
        if (viewer != null) ids += viewer
        val nets = linkedMapOf<String, MutableMap<String, Long>>()
        for (id in ids) {
            for (line in ffiBalanceLine(expenses, settlements, redistributions, removed, id, group)) {
                if (line.viewerNetMinor != 0L) nets.getOrPut(line.currencyCode) { linkedMapOf() }[id] = line.viewerNetMinor
            }
        }
        return stillOpen(nets, names, viewer)
    }
}

/** Every name a group's history may need, by participant id: the projection
 *  keeps removed people in `members` (D-186), so a line about a removed person
 *  still names them; the screens' names (yours resolved) win where both have
 *  one. */
fun LedgerStore.historyNames(group: String): Map<String, String> {
    val p = projectGroups()
    val projected = p.groups.firstOrNull { it.group.id == group }?.members.orEmpty()
    val names = linkedMapOf<String, String>()
    for (m in projected) m.name?.let { names[m.id] = it }
    for (m in namedGroups().firstOrNull { it.group.id == group }?.members.orEmpty()) names[m.id] = m.name
    return namingMergedPlaces(names, p.you?.id, p.otherYouIds, p.aliases, group)
}

/** D-230 F1 (decision 67; the history line is D-210's): a group's history
 *  records keep raw place ids forever, but the projection shows a place this
 *  phone merged under another id. This account's other places (`otherYouIds`,
 *  merged into you) take your own name; another account's "you", read through
 *  an alias core applied in THIS group, takes the name of the place it
 *  claimed. Only an id with no name yet is filled; a name is never
 *  overwritten. With no "you" nothing is filled (review M2): `Sync.yous`
 *  merges nothing then, so the ledger keeps every raw id and naming one would
 *  show a second row under the same name. Twin: GroupSettings.swift
 *  `namingMergedPlaces`. */
fun namingMergedPlaces(
    names: Map<String, String>, you: String?, otherYouIds: List<String>, aliases: List<IdAlias>, group: String,
): Map<String, String> {
    if (you == null) return names
    val out = LinkedHashMap(names)
    out[you]?.let { n -> for (id in otherYouIds) if (out[id] == null) out[id] = n }
    for (a in aliases) if (a.groupId == group && out[a.fromId] == null) out[a.toId]?.let { out[a.fromId] = it }
    return out
}

/** D-210 (final B1): the people removed from each group, by group, named (the
 *  projection's names, the screens' where they have one, else "Unknown
 *  member"). Core takes a removed person at zero out of the cards
 *  (`ffiBalanceLine`'s `removed`, `OverviewGroupIn.removed`) and names one who
 *  still owes from here. */
fun LedgerStore.removedPeople(): Map<String, List<MemberWire>> {
    val projected = projectGroups().groups
    val named = namedGroups().associateBy { it.group.id }
    val out = linkedMapOf<String, List<MemberWire>>()
    for (g in projected) {
        if (g.removed.isEmpty()) continue
        val screens = named[g.group.id]?.members.orEmpty().associate { it.id to it.name }
        val history = g.members.mapNotNull { m -> m.name?.let { m.id to it } }.toMap()
        out[g.group.id] = g.removed.map { id -> MemberWire(id, screens[id] ?: history[id] ?: LedgerStore.UNKNOWN_NAME) }
    }
    return out
}

/** One group's removed place ids, for `ffiBalanceLine`. */
fun LedgerStore.removedIds(group: String): List<String> = removedPeople()[group].orEmpty().map { it.id }

/** Renames a group as an operation every member's phone applies. A name is
 *  trimmed and cannot be empty (core refuses the operation otherwise). */
fun LedgerStore.renameGroup(groupId: String, name: String) {
    // D-229 (fix wave, M2): load, so a group whose place is lost refuses it (decision 45); the op maps nothing.
    appendGroupOps(listOf(GroupOpIn.RenameGroup(groupId, name.trim())), Placing.load(this))
    rebuildGroups()
}

/** Why the group is a copy, and what that means. */
object ReadOnlyBanner {
    /** The banner's first line. */
    fun title(mark: ReadOnlyDTO, date: String): String {
        // D-229 decision 45: its own one line, and no second.
        if (mark.kind == ReadOnlyDTO.PLACE_LOST) return RolesCopy.PLACE_LOST_BANNER
        if (mark.kind == "removed") return RolesCopy.removedBanner(mark.by, date)
        return if (mark.byYou == true) RolesCopy.deletedByYou(date) else RolesCopy.deletedBanner(mark.by, date)
    }

    /** The banner's second line; none for a group whose place is lost (D-229). */
    fun body(mark: ReadOnlyDTO, group: String): String? = when (mark.kind) {
        ReadOnlyDTO.PLACE_LOST -> null
        "removed" -> RolesCopy.removedBannerBody(group)
        else -> RolesCopy.DELETED_BANNER_BODY
    }
}

// ---- The settings screen ----

/** A group's settings: its name, picture and currency, and deleting it. */
@Composable
fun GroupSettingsScreen(
    vaultPath: String,
    groupId: String,
    dark: Boolean,
    onBack: () -> Unit,
    /** A group deleted (only on this phone, or for everyone): its page closes too, so the person lands where they
     *  opened it (U27). */
    onGone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var named by remember { mutableStateOf<NamedGroupWire?>(null) }
    var people by remember { mutableStateOf(GroupPeople()) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var nameText by rememberSaveable { mutableStateOf("") }
    // The cloud delete's confirm carries what it shows, read when Delete group
    // was tapped, never from screen state that could be stale (iOS L1).
    var deleteShown by rememberSaveable { mutableStateOf(false) }
    var deleteStillOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingLocalDelete by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    // A read that failed counts as a copy: nothing that changes the group is offered.
    var loadFailed by remember { mutableStateOf(false) }

    // The delete for everyone runs in the app, not in this screen (GroupJobs):
    // the call, this phone's read-only mark and the refresh are one piece of
    // work that Back or a rotation cannot cut in half.
    val deleteKey = GroupJobs.Key.delete(groupId)
    val deleteStatus = GroupJobs.status(deleteKey)
    val busy = deleteStatus == GroupJobs.Status.Running
    val error = localError ?: (deleteStatus as? GroupJobs.Status.Failed)?.message
    LaunchedEffect(deleteStatus) {
        if (deleteStatus == GroupJobs.Status.Done) {
            GroupJobs.clear(deleteKey)
            // U27: the done moment, then back at once (its page closes too): nothing confirmed a delete before.
            DoneCenter.post(DoneMoments.line(DoneCopy.GROUP_DELETED))
            onGone()
        }
    }
    // Back is swallowed while the delete runs.
    BackHandler(enabled = busy) {}

    LaunchedEffect(vaultPath, groupId, SyncStatus.revision) {
        withContext(Dispatchers.IO) {
            runCatching {
                val store = LedgerStore(vaultPath)
                // The roles this phone last heard, offline too.
                GroupRoles.load(store, groupId)
                store.namedGroups().firstOrNull { it.group.id == groupId } to GroupPeople.load(store, groupId)
            }
        }.fold(
            onSuccess = { (found, loaded) ->
                named = found
                people = loaded
                loadFailed = false
                localError = null
            },
            onFailure = {
                loadFailed = true
                localError = "Could not load the group. ${it.message ?: it}"
            },
        )
    }

    val viewerName = named?.members?.firstOrNull { it.id == named?.selfId }?.name ?: ""
    val viewerRole = named?.let {
        PeopleRoles.viewerRole(
            PeopleRoles.rows(
                it.members, it.selfId, people.viewerYou, people.claims, GroupRoles.roles[groupId],
                yourPlaces = GroupRoles.roles[groupId]?.places?.you, otherYouIds = people.otherYouIds,
                createdHere = people.createdHere,
            ),
        )
    } ?: RoleRules.Role.MEMBER
    // Name, Picture and Delete group are absent on a copy, live: the screen
    // reloads on every revision, so a group that becomes a copy while this is
    // open loses them. Delete group is the owner's in the cloud (D-126).
    val actions = GroupSettings.actions(
        readOnly = people.readOnly != null || loadFailed,
        isOwner = RoleRules.canDelete(viewerRole),
        shared = people.shared,
    )
    // What is no longer offered closes too, even mid-confirm.
    LaunchedEffect(actions) {
        if (!actions.name) renaming = false
        if (!actions.delete) {
            deleteShown = false
            confirmingLocalDelete = false
        }
    }

    val choosePicture = rememberGroupPictureChooser(
        dark,
        onChosen = { emoji, jpeg ->
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val store = LedgerStore(vaultPath)
                        store.setGroupPicture(groupId, emoji, jpeg)
                        GroupPictures.load(store)
                    }.isSuccess
                }
                if (saved) {
                    localError = null
                    CloudSync.ledgerChanged(vaultPath)
                    SyncStatus.changed()
                    // D-224 (Design 7): "Use letter" (no emoji, no photo) removes the picture.
                    DoneCenter.post(
                        DoneMoments.line(if (emoji == null && jpeg == null) DoneCopy.GROUP_PICTURE_REMOVED else DoneCopy.GROUP_PICTURE_SAVED),
                    )
                } else {
                    localError = GroupPictures.TOO_BIG
                }
            }
        },
        onError = { localError = it },
    )

    fun rename() {
        val text = nameText
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { LedgerStore(vaultPath).renameGroup(groupId, text) }.exceptionOrNull()
            }
            if (failure == null) {
                CloudSync.ledgerChanged(vaultPath)
                SyncStatus.changed()
                localError = null
                DoneCenter.post(DoneMoments.line(DoneCopy.GROUP_NAME_SAVED))
            } else {
                localError = if (failure is uniffi.skipti_core.CoreException) GroupCopy.message(failure) else "Could not save. $failure"
            }
        }
    }

    fun startDelete(n: NamedGroupWire) {
        if (!people.shared) {
            confirmingLocalDelete = true
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { GroupSettings.stillOpen(LedgerStore(vaultPath), groupId, n.selfId) }
            }.fold(
                onSuccess = {
                    deleteStillOpen = it
                    deleteShown = true
                },
                onFailure = { localError = "Could not read the balances. ${it.message ?: it}" },
            )
        }
    }

    /** The owner's delete: the cloud copy is erased, and this phone keeps its
     *  own copy read-only like everyone else's. */
    fun deleteForEveryone(n: NamedGroupWire) {
        val groupName = n.group.name
        val byName = viewerName
        localError = null
        GroupJobs.start(deleteKey, { (it as? GroupJobs.Failure)?.message ?: RolesCopy.failure(it) }) {
            try {
                GroupDelete.run(
                    call = {
                        CloudFunctions.callJson("deleteGroup", buildJsonObject {
                            put("groupId", groupId)
                            put("groupName", RoleRules.wireName(groupName))
                            put("byName", RoleRules.wireName(byName))
                        })
                    },
                    mark = {
                        LedgerStore(vaultPath).markReadOnly(
                            groupId, ReadOnlyDTO("deleted", byName, System.currentTimeMillis(), byYou = true),
                        )
                    },
                )
            } catch (e: GroupDelete.MarkFailed) {
                throw GroupJobs.Failure("Deleted for everyone, but this phone could not keep its copy. ${e.cause}")
            }
            MainThread.run { SyncStatus.changed() }
        }
    }

    /** A group only on this phone: forgotten here, and the page under this closes. */
    fun deleteHere() {
        scope.launch {
            val failure = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { LedgerStore(vaultPath).forgetGroup(groupId) }.exceptionOrNull()
            }
            if (failure == null) {
                SyncStatus.changed()
                DoneCenter.post(DoneMoments.line(DoneCopy.GROUP_DELETED))
                onGone()
            } else {
                localError = "Could not delete the group. ${failure.message ?: failure}"
            }
        }
    }

    // D-217 look fix 1: the first card with no header over it. D-225 (Design 7; L-D217-1): one list card (Name,
    // Picture, Settles in: rows of one height on both phones), then the danger card with Delete group, 16 apart.
    PushedPage(RolesCopy.GROUP_SETTINGS_TITLE, dark, onBack = { if (!busy) onBack() }, spacing = 16.dp, top = BarDecisions.formTop(headerless = true).dp) {
        named?.let { n ->
            ListCard(dark) {
                if (actions.name) {
                    row {
                        ListRow(RolesCopy.SETTINGS_NAME, dark, trailing = RowTrailing.Value(n.group.name, chevron = true), enabled = !busy) {
                            nameText = n.group.name
                            renaming = true
                        }
                    }
                }
                if (actions.picture) {
                    row {
                        ListRow(RolesCopy.SETTINGS_PICTURE, dark, trailing = RowTrailing.Piece(chevron = true) { GroupTile(n.group, dark, ListRows.pieceTile(TileShape.of(Appearance.theme, person = false))) }, enabled = !busy) {
                            choosePicture()
                        }
                    }
                }
                row { ListRow(RolesCopy.SETTINGS_SETTLES_IN, dark, trailing = RowTrailing.Value(n.group.settlementCurrency, chevron = false)) }
            }
            if (actions.delete) {
                ListCard(dark) {
                    row {
                        ListRow(
                            RolesCopy.DELETE_GROUP, dark,
                            parts = if (people.shared) listOf(RolesCopy.DELETE_GROUP_SUB) else emptyList(),
                            tone = RowTone.DANGER, summaryLines = 0, enabled = !busy,
                        ) { startDelete(n) }
                    }
                }
            }
        }
        error?.let { ErrorLine(it, dark) }
    }

    val n = named
    if (renaming && actions.name) {
        SkiptiAlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(RolesCopy.SETTINGS_NAME) },
            text = {
                // D-225: the hairline field (iOS's alert field has no outline either); the alert's words and steps stay.
                HairlineField(
                    nameText, { nameText = it }, RolesCopy.SETTINGS_NAME, dark,
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            },
            confirmButton = {
                TextButton(onClick = { renaming = false; rename() }) { Text(RolesCopy.SAVE, color = Tok.accent(dark)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(RolesCopy.CANCEL) } },
        )
    }
    // D-224 (Design 3): each delete's question warns as it opens.
    HapticWarning(deleteShown && n != null && actions.delete)
    if (deleteShown && n != null && actions.delete) {
        SkiptiAlertDialog(
            onDismissRequest = { deleteShown = false },
            title = { Text(RolesCopy.deleteCloudTitle(n.group.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(RolesCopy.DELETE_CLOUD_BODY)
                    deleteStillOpen?.let { open ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(GroupTone.AMBER.fill(dark), ThemeArt.panelShape(Appearance.theme, 14))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(RolesCopy.STILL_OPEN, style = Type.label(dark, 14).copy(color = GroupTone.AMBER.letter(dark)))
                            Text(open.withRunes(), style = Type.body(dark).copy(fontSize = 14.sp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteShown = false
                    deleteForEveryone(n)
                }) { Text(RolesCopy.DELETE_FOR_EVERYONE, color = Tok.owed(dark)) }
            },
            dismissButton = { TextButton(onClick = { deleteShown = false }) { Text(RolesCopy.CANCEL) } },
        )
    }
    HapticWarning(confirmingLocalDelete && actions.delete)
    if (confirmingLocalDelete && actions.delete) {
        SkiptiAlertDialog(
            onDismissRequest = { confirmingLocalDelete = false },
            title = { Text(RolesCopy.deleteLocalTitle(n?.group?.name ?: "")) },
            text = { Text(RolesCopy.DELETE_LOCAL_BODY) },
            confirmButton = {
                TextButton(onClick = { confirmingLocalDelete = false; deleteHere() }) {
                    Text(RolesCopy.DELETE_LOCAL_CONFIRM, color = Tok.owed(dark))
                }
            },
            dismissButton = { TextButton(onClick = { confirmingLocalDelete = false }) { Text(RolesCopy.CANCEL) } },
        )
    }
}

// ---- A read-only copy ----

/** Why the group is a copy, and what that means. */
@Composable
fun ReadOnlyBannerCard(group: String, mark: ReadOnlyDTO, dark: Boolean) {
    val date = Phones.day(mark.atMs)
    Column(
        Modifier
            .fillMaxWidth()
            .background(GroupTone.AMBER.fill(dark), ThemeArt.panelShape(Appearance.theme, 18))
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(ReadOnlyBanner.title(mark, date), style = Type.label(dark, 15).copy(color = GroupTone.AMBER.letter(dark)))
        ReadOnlyBanner.body(mark, group)?.let {
            Text(it, style = Type.bodyDim(dark).copy(color = Tok.text(dark), fontSize = 14.sp))
        }
    }
}

/** The rule of a copy's actions (iOS's twin is ReadOnlyActions.offersForget). */
object ReadOnlyActions {
    /** D-229 decision 52 (pure): whether a copy offers "Remove from this phone". Not for a
     *  group whose place is lost: you are still in it, forgetting would erase its records
     *  waiting to upload, and it would come straight back, still read-only. Export stays. */
    fun offersForget(mark: ReadOnlyDTO): Boolean = mark.kind != ReadOnlyDTO.PLACE_LOST
}

/** A copy's two actions: export it, or take it off this phone (not for a
 *  group whose place is lost: [ReadOnlyActions.offersForget]). */
@Composable
fun ReadOnlyActions(
    vaultPath: String,
    group: NamedGroupWire,
    mark: ReadOnlyDTO,
    dark: Boolean,
    onExport: () -> Unit,
    /** The copy is gone from this phone; the page closes. */
    onForgotten: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirming by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryAction(RolesCopy.EXPORT_THIS_GROUP, dark, onClick = onExport)
        if (ReadOnlyActions.offersForget(mark)) DangerAction(RolesCopy.FORGET_ROW, dark) { confirming = true }
        error?.let { ErrorLine(it, dark) }
    }
    // D-224 (Design 3): forgetting a copy cannot be undone; its question warns as it opens.
    HapticWarning(confirming)
    if (confirming) {
        SkiptiAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(RolesCopy.forgetTitle(group.group.name)) },
            text = { Text(RolesCopy.FORGET_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch {
                        val failure = withContext(NonCancellable + Dispatchers.IO) {
                            runCatching { LedgerStore(vaultPath).forgetGroup(group.group.id) }.exceptionOrNull()
                        }
                        if (failure == null) {
                            SyncStatus.changed()
                            onForgotten()
                        } else {
                            error = "Could not remove the group. ${failure.message ?: failure}"
                        }
                    }
                }) { Text(RolesCopy.FORGET_CONFIRM, color = Tok.owed(dark)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(RolesCopy.CANCEL) } },
        )
    }
}
