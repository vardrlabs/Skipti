package com.vardrlabs.skipti

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.skipti_core.GroupWire
import uniffi.skipti_core.NamedGroupWire

/**
 * D-179: a group's colour, from its id, the same on every phone and on both
 * shells (iOS's `GroupTone` in GroupsScreen.swift). The first byte of the id
 * modulo six picks one of six tones: a soft fill with the group's first
 * letter on it, every letter at 4.5:1 or better on its fill in every set
 * (.drafts/d212/palette.py; D-220 L29: blue replaced violet, .drafts/d220/palette.py; AppearanceTest measures them again).
 */
enum class GroupTone(private val fills: LongArray, private val letters: LongArray) {
    CORAL(longArrayOf(0xFFFFE4DA, 0xFF3A2119, 0xFF261F21, 0xFFDDDADA, 0xFF2C2829), longArrayOf(0xFFB53F18, 0xFFFF9C78, 0xFFE1AA96, 0xFF875342, 0xFFDAAD9D)),
    BLUE(longArrayOf(0xFFE3EBFC, 0xFF17223D, 0xFF1E212C, 0xFFD7DCE4, 0xFF262B34), longArrayOf(0xFF2F5BBF, 0xFF93B4FF, 0xFFABBDE7, 0xFF4F6089, 0xFFB1C0E1)),
    TEAL(longArrayOf(0xFFDDF5F0, 0xFF12302B, 0xFF172125, 0xFFD1DBDD, 0xFF1E2A2C), longArrayOf(0xFF0B7A6B, 0xFF5FD6C4, 0xFF7ABBB1, 0xFF2A5B55, 0xFF80B5AD)),
    AMBER(longArrayOf(0xFFFFF1D6, 0xFF33270F, 0xFF23201E, 0xFFD8D9D7, 0xFF2A2926), longArrayOf(0xFF8A5A00, 0xFFF2C063, 0xFFD2B683, 0xFF644E26, 0xFFCBB48A)),
    SKY(longArrayOf(0xFFDFF1FB, 0xFF10293A, 0xFF1B222A, 0xFFD2DBE0, 0xFF222B31), longArrayOf(0xFF0B6A96, 0xFF7CC9F0, 0xFF96C0D6, 0xFF315C70, 0xFF9CBFD0)),
    PINK(longArrayOf(0xFFFFE3F0, 0xFF3A1A2A, 0xFF261E27, 0xFFDCD9DF, 0xFF2D282F), longArrayOf(0xFFA8286A, 0xFFFF8CC2, 0xFFE5A6C4, 0xFF854B69, 0xFFDFACC4));

    fun fillOf(set: ThemeSet) = Color(fills[set.ordinal])
    fun letterOf(set: ThemeSet) = Color(letters[set.ordinal])
    fun fill(dark: Boolean) = fillOf(ThemeSet.resolve(Appearance.theme, dark))
    fun letter(dark: Boolean) = letterOf(ThemeSet.resolve(Appearance.theme, dark))

    companion object {
        fun of(groupId: String): GroupTone {
            val byte = groupId.take(2).toIntOrNull(16) ?: 0
            return entries[byte % entries.size]
        }

        /** The group's first letter, upper-cased; "?" for a name with none. */
        fun initial(name: String): String =
            name.trim().firstOrNull()?.uppercase() ?: "?"
    }
}

/** A group's tile: its first letter on its tone. */
@Composable
fun GroupTile(group: GroupWire, dark: Boolean, size: Int = 48) =
    PictureTile(group.id, group.name, GroupPictures.byId[group.id], dark, size)

/** D-187: a group's picture when it has one: a photo, or an emoji on the
 *  group's tone; its initial otherwise. Also the New group preview. */
@Composable
fun PictureTile(id: String, name: String, picture: PictureDTO?, dark: Boolean, size: Int = 48) {
    val tone = GroupTone.of(id)
    // D-213: Norse's diamond and Vardr Labs' square; Aurora keeps the rounded tile.
    val kind = TileShape.of(Appearance.theme, person = false)
    val shape = tileShape(kind, size)
    val photo = picture?.photoHex?.let { GroupPictures.bitmap(it) }
    // D-220 (L23): the tile keeps its `size` footprint; its shape is drawn at `tileDrawnSide`, centred on it (Norse's
    // diamond at size x sqrt 2, overflowing by 0.207 x size a side, art.py section 15).
    Box(Modifier.size(size.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredSize(tileDrawnSide(kind, size).dp)
                .tileEdge(kind, size)
                .clip(shape)
                .background(tone.fill(dark), shape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                photo != null -> androidx.compose.foundation.Image(
                    photo, contentDescription = null, modifier = Modifier.matchParentSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                picture?.emoji != null -> Text(picture.emoji, fontSize = (size * 0.5f).sp)
                else -> Text(GroupTone.initial(name), style = tileInitialStyle(kind, size, dark, tone.letter(dark)))
            }
        }
    }
}

/** The Groups tab's words, one place; iOS's twin is `GroupsCopy`. */
object GroupsCopy {
    const val EMPTY = "No groups yet. Make one for a trip, a flat or a dinner, or join one a friend shared."
    const val SHOWING = "Showing"
    const val OPEN_HINT = "Opens this group"
    /** D-210 (Task 8 look): a group the records name but this account has no
     *  place in yet; iOS's twin has the same words. */
    const val WAITING = "Waiting for this group to finish arriving."
    /** D-220 (L25): the parts of a group's detail line; the plain card draws them with `Separator.annotated` (Norse's
     *  gold rune), the white line over a photo the joined string. */
    fun detailParts(people: Int, currency: String) = listOf(GroupCopy.people(people), currency)
    fun detail(people: Int, currency: String) = detailParts(people, currency).joinToString(Separator.meta())
    fun people(group: String) = "People in $group"
}

/**
 * D-179: the Groups tab (the founder's canvas, 2026-09-26): New group and
 * Join a group on top, then a card per group. Tapping a group makes it the
 * one Balance, Expenses, Add and Settle up work on and opens Balance.
 */
@Composable
fun GroupsTab(
    vaultPath: String,
    dark: Boolean,
    onOpen: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onPeople: (String) -> Unit,
    onJoinInvite: (IncomingInvites.Item) -> Unit = {},
    /** D-217: the account glyph, the bar's trailing item. */
    onOpenAccount: (() -> Unit)? = null,
    /** D-225 T7 (A5): the tab's place, kept by the shell across a pushed route, a group's page or another tab. */
    keep: TabKeep = remember { TabKeep() },
) {
    val scope = rememberCoroutineScope()
    // D-225 T7 (A5): the kept read, so the cards are there on the first frame back and the kept offset holds.
    var groups by keep.slot<List<NamedGroupWire>>("groups") { emptyList() }
    var waiting by keep.slot<List<LedgerStore.WaitingGroup>>("waiting") { emptyList() }
    var selectedId by keep.slot<String?>("selectedId") { null }
    var error by keep.slot<String?>("error") { null }
    var loaded by keep.slot("loaded") { false }
    // D-210: the groups that are read-only copies: their People link is absent.
    var copies by keep.slot<Set<String>>("copies") { emptySet() }

    LaunchedEffect(vaultPath, SyncStatus.revision) {
        withContext(Dispatchers.IO) {
            runCatching {
                val store = LedgerStore(vaultPath)
                val marked = store.readOnlyChecked().keys
                try {
                    GroupsLoad(store.namedGroups(), store.waitingGroups(), store.currentGroup()?.group?.id, marked)
                } catch (_: NeedsYourNameException) {
                    GroupsLoad(emptyList(), emptyList(), null, marked)
                }
            }
        }.fold(
            onSuccess = { (all, still, current, marked) ->
                groups = all
                waiting = still
                selectedId = current
                copies = marked
                error = null
            },
            onFailure = { error = "Could not load groups. ${GroupCopy.message(it)}" },
        )
        loaded = true
    }

    TabRootScaffold(
        title = "Groups",
        ornament = true,
        scroll = rememberShellScrollState(keep.scroll),
        trailing = accountGlyph(dark, onOpenAccount),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { PrimaryAction("New group", dark, onClick = onCreate) }
            Box(Modifier.weight(1f)) { SecondaryAction(InviteCopy.JOIN_TITLE, dark, onClick = onJoin) }
        }
        // D-197: invitations sent to this account.
        InvitationsList(dark, (groups.map { it.group.id } + waiting.map { it.id }).toSet(), onJoinInvite)
        if (loaded && groups.isEmpty() && waiting.isEmpty() && error == null) {
            Text(GroupsCopy.EMPTY, style = Type.bodyDim(dark), modifier = Modifier.glassCard(dark))
        }
        groups.forEach { named ->
            val photo = GroupPictures.byId[named.group.id]?.photoHex?.let { GroupPictures.bitmap(it) }
            if (photo != null) {
                PhotoGroupCard(named, photo, dark, named.group.id == selectedId, named.group.id !in copies, onOpenGroup, onPeople)
                return@forEach
            }
            // D-223 (L-D217-1): iOS's spacing, from the one table; the row has no 48 dp floor of its own (the tile is 48).
            Column(Modifier.glassCard(dark), verticalArrangement = Arrangement.spacedBy(ThemeArt.m("groupsCardSpacing").dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        // D-180: a group opens its page, which makes it the
                        // group Add, Expenses and Settle up use.
                        .clickable(onClickLabel = GroupsCopy.OPEN_HINT) { onOpenGroup(named.group.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GroupTile(named.group, dark)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(named.group.name, style = Type.name(dark, 17))
                        // D-220 (L25): Norse's gold rune between the parts.
                        Text(
                            Separator.annotated(GroupsCopy.detailParts(named.members.size, named.group.settlementCurrency)),
                            style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp),
                        )
                    }
                    if (named.group.id == selectedId) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            Type.kicker(GroupsCopy.SHOWING),
                            style = Type.microLabel(dark).copy(color = ThemePills.text(PillTone.ACCENT, dark)),
                            modifier = Modifier
                                .themePill(PillTone.ACCENT, dark)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                if (named.group.id !in copies) {
                    // D-220 (L28): plain words that take a tap, as iOS's NavigationLink label (no Material button inset).
                    // D-220 fix wave (I1): a touch target, the words centred in it at the start. D-223 fix wave (decision
                    // 20): laid out 44 dp tall as iOS's 44 pt; Compose widens the touch area to 48 dp where it is free.
                    Box(
                        Modifier
                            .heightIn(min = PeopleLinkMinHeight.dp)
                            .clickable(role = Role.Button) { onPeople(named.group.id) },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            GroupsCopy.people(named.group.name),
                            style = Type.body(dark).copy(color = Tok.accent(dark), fontSize = TypeScale.detail.sp),
                        )
                    }
                }
            }
        }
        // D-210 (Task 8 look): a group still arriving: a row, nothing to tap.
        waiting.forEach { WaitingGroupRow(it, dark) }
        error?.let { Text(it, style = Type.body(dark).copy(color = Tok.owed(dark)), modifier = Modifier.glassCard(dark)) }
    }
}

/** D-220 fix wave (I1): the "People in X" link's height on both card kinds. D-223 fix wave (decision 20): 44 dp, iOS's
 *  44 pt, so the plain card is iOS's 136 (48 made it 140.2). The touch area is at least 44 dp, widened to 48 by
 *  Compose where no neighbour claims the space (the card's row above is clickable, so not on its top edge). */
internal const val PeopleLinkMinHeight = 44

/** D-223 follow-up (R1, decision 22): the photo card's inner gaps, iOS's `photoCard`'s (iOS is the scale, 190.67 pt with
 *  the link): the label stack's spacing (the band, the spacer, the title and the detail), the spacer's least height, and
 *  the card stack's spacing above "People in X". */
internal object PhotoCardGaps {
    const val STACK = 2
    const val TITLE_MIN = 12
    const val LINK = 10
    /** dp from the band to the title at the card's least height: the stack's spacing on both sides of the spacer. */
    const val ABOVE_TITLE = STACK + TITLE_MIN + STACK
}

private data class GroupsLoad(
    val groups: List<NamedGroupWire>,
    val waiting: List<LedgerStore.WaitingGroup>,
    val current: String?,
    val marked: Set<String>,
)

/** A group whose place for this account has not arrived: its tile and name, and
 *  the waiting line. No actions; it resolves when the missing records arrive. */
@Composable
private fun WaitingGroupRow(group: LedgerStore.WaitingGroup, dark: Boolean) {
    Row(
        Modifier.glassCard(dark).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PictureTile(group.id, group.name, null, dark)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(group.name, style = Type.name(dark, 17))
            Text(GroupsCopy.WAITING, style = Type.bodyDim(dark).copy(fontSize = TypeScale.detail.sp))
        }
    }
}

/** D-187 (the founder): a group with a photo is the photo, edge to edge,
 *  its words white over `PhotoCard`'s gradient. iOS's twin is
 *  `GroupsScreen.photoCard`. */
@Composable
private fun PhotoGroupCard(
    named: NamedGroupWire,
    photo: androidx.compose.ui.graphics.ImageBitmap,
    dark: Boolean,
    showing: Boolean,
    /** D-210: false for a read-only copy: no People link. */
    peopleLink: Boolean,
    onOpenGroup: (String) -> Unit,
    onPeople: (String) -> Unit,
) {
    val white = Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = PhotoCard.CARD_HEIGHT.dp)
            // D-220 (L21): the card's own shape and edge (Norse's notch and edge, Vardr Labs' white marks).
            .photoFrame(null, dark),
    ) {
        PhotoBackdrop(photo, Modifier.matchParentSize())
        // The whole card opens the group; the link below takes its own tap.
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = PhotoCard.CARD_HEIGHT.dp)
                .clickable(onClickLabel = GroupsCopy.OPEN_HINT) { onOpenGroup(named.group.id) }
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // The top band holds only the chip, on its own capsule.
            Row(Modifier.fillMaxWidth().height((PhotoCard.BAND - 16).dp), horizontalArrangement = Arrangement.End) {
                if (showing) {
                    Text(
                        Type.kicker(GroupsCopy.SHOWING),
                        style = Type.microLabel(false).copy(color = ThemePills.text(PillTone.PHOTO, dark)),
                        modifier = Modifier
                            .themePill(PillTone.PHOTO, dark)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            // D-223 follow-up (R1, decision 22): iOS's gaps (PhotoCardGaps): 16 from the band to the title at the least
            // height, 2 between the title and the detail, 10 above the link.
            Column(Modifier.padding(top = PhotoCardGaps.ABOVE_TITLE.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(PhotoCardGaps.STACK.dp)) {
                    Text(
                        named.group.name,
                        style = Type.display(false).copy(fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = white),
                    )
                    Text(
                        GroupsCopy.detail(named.members.size, named.group.settlementCurrency).withRunes(),
                        style = Type.body(false).copy(color = white, fontSize = TypeScale.detail.sp),
                    )
                }
                if (peopleLink) {
                    // D-220 fix wave (I1): a touch target with a button role, as the plain card's link (44 dp, decision 20).
                    Box(
                        Modifier
                            .padding(top = PhotoCardGaps.LINK.dp)
                            .heightIn(min = PeopleLinkMinHeight.dp)
                            .clickable(role = Role.Button) { onPeople(named.group.id) },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            GroupsCopy.people(named.group.name),
                            style = Type.label(false, 14).copy(color = white, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                        )
                    }
                }
            }
        }
    }
}

/**
 * `--groups-proof` (D-179, headless verification, not a user flow): on an
 * empty ledger, gives you a name and makes three groups (the canvas's Goa
 * trip, Flat 4B and Paris), then shows the ordinary tabs on Groups. iOS's
 * twin is `GroupsProofScreen`.
 */
@Composable
fun GroupsProof(vaultPath: String, tabs: @Composable () -> Unit) {
    var ready by remember { mutableStateOf<Result<Unit>?>(null) }
    LaunchedEffect(Unit) {
        ready = withContext(Dispatchers.IO) { runCatching { GroupsProofSeed.seed(LedgerStore(vaultPath)) } }
    }
    val result = ready ?: return
    result.exceptionOrNull()?.let {
        Text("groups proof failed: ${it.message}", modifier = Modifier.padding(20.dp))
        return
    }
    tabs()
}

/** D-247: the look's states on top of the groups proof (`--groups-proof --settle-look <step>`, a proof's argument, so
 *  debuggable builds only: ProofArgs): `you` records every one of your Goa trip and Flat 4B settles (Goa all settled
 *  for you; in Flat 4B you are square, Kiran still owes Meera); `partial` holds 1,200.00 of the 3,000.00 Sam owes you
 *  (the toast with Undo, the remainder); `undo` holds it and takes it back ("Not recorded."); `sheet` and `toomuch`
 *  open Paris's "Another amount" (the second typed over the debt); `ring` and `ring-nolock` show You (the second as a
 *  phone with no lock); `group` opens Flat 4B's page. The twin of iOS's `SettleLook`. */
object SettleLook {
    /** Set by the groups proof from its arguments; null for every ordinary launch. */
    @Volatile var step: String? = null

    fun parse(args: List<String>): String? {
        val i = args.indexOf("--settle-look")
        return if (i >= 0) args.getOrNull(i + 1) else null
    }

    /** The step's ledger state, once, on an untouched proof ledger; returns Flat 4B's id (for `group`). The hold runs on
     *  the main thread, as the app's own settles do. */
    suspend fun apply(vaultPath: String): String? {
        val step = step ?: return null
        val store = LedgerStore(vaultPath)
        val (fresh, sections) = withContext(Dispatchers.IO) {
            store.loadSettlements().isEmpty() to SettleAll.sections(store)
        }
        val flat = sections.firstOrNull { it.named.group.name == "Flat 4B" }?.named?.group?.id
        if (!fresh) return flat
        when (step) {
            "you" -> withContext(Dispatchers.IO) {
                for (s in sections.filter { it.named.group.name in listOf("Goa trip", "Flat 4B") }) {
                    for (line in s.lines) for (e in line.entries) SettleAll.append(store, e, line.currencyCode, s.named)
                }
            }
            "partial", "undo" -> {
                val s = sections.firstOrNull { it.named.group.name == "Goa trip" } ?: return flat
                val line = s.lines.firstOrNull() ?: return flat
                val sam = line.entries.firstOrNull { LedgerStore.label(it.id, s.named) == "Sam" } ?: return flat
                SettleHold.shared.hold(SettleAll.settlement(sam, line.currencyCode, s.named, 120_000), "Sam", LedgerStore(vaultPath))
                if (step == "undo") SettleHold.shared.pending?.let { SettleHold.shared.undo(it.id) }
            }
        }
        return flat
    }

    private var opened = false

    /** `sheet` / `toomuch`: the card whose sheet opens, once. */
    fun draft(sections: List<SettleSection>): OtherAmountDraft? {
        if (step != "sheet" && step != "toomuch" || opened) return null
        val s = sections.firstOrNull { it.named.group.name == "Paris" } ?: return null
        val line = s.lines.firstOrNull() ?: return null
        val e = line.entries.firstOrNull() ?: return null
        opened = true
        return OtherAmountDraft(SettleKey(s.named.group.id, line.currencyCode, e), e, line.currencyCode, s.named, "Ana", s.history)
    }

    /** `toomuch`: what the field holds instead of the debt. */
    fun typed(): String? = if (step == "toomuch") "40.00" else null

    /** `ring-nolock`: You reads as on a phone with no lock. */
    fun noLock(): Boolean = step == "ring-nolock"
}

object GroupsProofSeed {
    /** Only when there is no group yet: a proof never adds to a real ledger. */
    fun seed(store: LedgerStore) {
        if (store.ensureSelf().name == null) store.setYourName("Prithvi")
        if (store.namedGroups().isNotEmpty()) return
        store.createGroup("Goa trip", "INR", listOf("Rahul", "Meera", "Sam"))
        store.createGroup("Flat 4B", "INR", listOf("Meera", "Kiran"))
        store.createGroup("Paris", "EUR", listOf("Ana"))
        // D-180: a few expenses, so Balance and a group's page have numbers.
        val groups = store.namedGroups()
        val tree = uniffi.skipti_core.ffiCategoryTreeDefault()
        fun leaf(parent: String, name: String): String? {
            val p = tree.categories.firstOrNull { it.parent == null && it.name == parent }
            return tree.categories.firstOrNull { it.parent == p?.id && it.name == name }?.id
        }
        val now = System.currentTimeMillis()
        fun add(group: String, payer: Int, minor: Long, code: String, note: String, category: String?, hoursAgo: Long) {
            val g = groups.firstOrNull { it.group.name == group } ?: return
            val ids = g.members.map { it.id }
            val each = minor / ids.size
            store.appendExpense(
                ExpenseDTO(
                    payer = ids[payer], paidMinor = each * ids.size, currencyCode = code, group = g.group.id,
                    shares = ids.map { ExpenseDTO.ShareDTO(it, each) }, note = note,
                    // D-189: added by whoever paid, so Activity has something to show.
                    timestampMs = now - hoursAgo * 3_600_000, category = category, addedBy = ids[payer],
                ),
            )
        }
        add("Goa trip", 0, 1_200_000, "INR", "Villa, 2 nights", leaf("Stay", "Rentals"), 30)
        add("Goa trip", 1, 480_000, "INR", "Dinner at Britto's", leaf("Food & Drink", "Restaurants"), 3)
        add("Goa trip", 2, 160_000, "INR", "Scooters", leaf("Travel", "Rentals"), 2)
        add("Flat 4B", 0, 270_000, "INR", "Electricity", leaf("Bills & Utilities", "Electricity"), 26)
        add("Flat 4B", 1, 135_000, "INR", "Groceries", leaf("Food & Drink", "Groceries"), 20)
        add("Paris", 1, 6_400, "EUR", "Museum passes", leaf("Entertainment", "Events"), 300)
    }
}
