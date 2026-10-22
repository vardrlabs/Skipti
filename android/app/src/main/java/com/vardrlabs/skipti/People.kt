package com.vardrlabs.skipti

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.NamedGroupWire
import java.security.MessageDigest

/**
 * D-197 (the Firebase plan's unit 7): finding people. Three ways, all among
 * people who let themselves be found (Settings, on by default; the founder's
 * decision 14): their @username, their exact email, or the emails in your
 * contacts (the founder, 2026-10-02: "match all contacts"). An email never
 * leaves this phone as text when you search: the phone sends its SHA-256
 * (trimmed, lower case), and SKIPTI's cloud compares it with the fingerprint
 * it keeps of each findable person's verified sign-in email, written only by a
 * Function. Someone found is invited as a named person of the group: an
 * invite in their account and a push. iOS's twin is People.swift.
 */
object PeopleCopy {
    const val FIND_ME = "Find me"
    const val USERNAME = "Username"
    fun handle(h: String) = "@$h"
    const val NO_USERNAME = "Not set"
    const val CHOOSE_USERNAME = "Choose a username"
    const val CHANGE_USERNAME = "Change username"
    const val USERNAME_TITLE = "Your username"
    const val USERNAME_PLACEHOLDER = "username"
    const val USERNAME_HELP = "3 to 20 letters, numbers or _. People can find you by it."
    const val USERNAME_TAKEN = "That username is taken."
    const val USERNAME_SHAPE = "Use 3 to 20 letters, numbers or _."
    const val USERNAME_RESERVED = "That username is not available."
    const val SAVE = "Save"
    const val FINDABLE_TOGGLE = "Let people find me by my username or email"
    const val FINDABLE_ON = "People who know your username or email can find you and invite you to their groups."
    const val FINDABLE_OFF = "Nobody can find you. You can still join a group with an invite link."

    fun findTitle(name: String) = "Find $name"
    fun findOnSkipti(name: String) = "Find $name on SKIPTI"
    const val QUERY_PROMPT = "Their username or email"
    const val SEARCH = "Search"
    const val SEARCHING = "Searching…"
    const val NOT_A_QUERY = "Type a username (3 to 20 letters, numbers or _) or a whole email address."
    const val FROM_CONTACTS = "Find friends in contacts"
    const val CONTACTS_DETAIL =
        "Each email in your contacts is turned into a fingerprint on this phone. SKIPTI's cloud compares the fingerprints and keeps none of them."
    const val CONTACTS_DENIED = "SKIPTI cannot read your contacts. You can allow it in your phone's settings."
    const val NO_CONTACTS = "None of your contacts have an email address."
    const val NO_MATCH = "Nobody on SKIPTI matches. They may have switched off being found; an invite link always works."
    const val SOMEONE = "Someone on SKIPTI"
    fun inContacts(name: String) = "In your contacts as $name"
    const val INVITE = "Invite"
    const val INVITING = "Inviting…"
    fun invited(name: String) = "Invited. $name gets a notification and the invite in SKIPTI."
    const val LIMIT = "Too many for today. Try again tomorrow."
    const val ALREADY_IN = "They are already in this group."

    const val INVITATIONS = "Invitations"
    fun invitedYou(from: String?, group: String) = from?.let { "$it invited you to $group" } ?: "You are invited to $group"
    fun joinAs(name: String) = "You join as $name."
    const val JOIN = "Join"
    const val DISMISS = "Dismiss"
    const val NO_INVITATIONS = "No invitations."
}

/** An email's fingerprint, exactly as the cloud makes it (people.js). */
object EmailFingerprint {
    private val email = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun hash(address: String): String =
        MessageDigest.getInstance("SHA-256").digest(address.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun looksLikeEmail(text: String): Boolean = email.matches(text)
}

/** What a search box holds: a username or a whole email, else nothing. */
sealed class PeopleQuery {
    data class Username(val handle: String) : PeopleQuery()
    data class Email(val address: String) : PeopleQuery()

    companion object {
        private val handle = Regex("^[a-z0-9_]{3,20}$")

        fun parse(raw: String): PeopleQuery? {
            val text = raw.trim()
            if ('@' in text && !text.startsWith("@")) return if (EmailFingerprint.looksLikeEmail(text)) Email(text) else null
            val h = text.removePrefix("@").lowercase()
            return if (handle.matches(h)) Username(h) else null
        }
    }
}

data class FoundPerson(val uid: String, val name: String?, val username: String?, val matched: String?) {
    /** Their name, else @handle, else the contact's name, else "Someone on SKIPTI". */
    fun title(contacts: Map<String, String> = emptyMap()): String =
        name ?: username?.let(PeopleCopy::handle) ?: matched?.let { contacts[it] } ?: PeopleCopy.SOMEONE

    fun detail(contacts: Map<String, String> = emptyMap()): String? =
        listOfNotNull(username?.let(PeopleCopy::handle), matched?.let { contacts[it] }?.let(PeopleCopy::inContacts))
            .joinToString(Separator.meta()).ifEmpty { null }
}

/** D-207 (the founder, 2026-10-13): someone from your other groups, offered
 *  when you add people to a group; the twin of iOS's RecentPerson. */
data class RecentPerson(
    val name: String,
    val shared: Int,
    val groups: List<String>,
    val participantId: String?,
    val viaGroupId: String?,
    /** D-211: their preview (a picture or an emoji) from a group of theirs that
     *  has one; `Avatar.None` draws their initials. */
    val preview: Avatar = Avatar.None,
) {
    val id: String get() = participantId ?: ("name:" + name.lowercase())
}

/** D-207: recents from what is already on this phone; the twin of iOS's Recents. */
object Recents {
    private fun key(name: String) = name.trim().split(Regex("\\s+")).joinToString(" ").lowercase()

    private class Acc(
        var name: String, val groups: MutableList<String>, val pid: String?, val via: String?,
        /** D-211: the (group, member) places this person is, in the projection's order. */
        val seen: MutableList<Pair<String, String>> = mutableListOf(),
    )

    fun people(
        groups: List<uniffi.skipti_core.NamedGroupWire>,
        claims: Map<String, List<uniffi.skipti_core.ClaimOut>>,
        groupId: String,
        limit: Int = 8,
        previews: Map<String, Map<String, Avatar>> = emptyMap(),
    ): List<RecentPerson> {
        val target = groups.firstOrNull { it.group.id == groupId }
        val targetPids = claims[groupId].orEmpty().map { it.youId }.toSet()
        val targetNames = target?.members.orEmpty().map { key(it.name) }.toSet()
        val byPid = LinkedHashMap<String, Acc>()
        val byName = LinkedHashMap<String, Acc>()
        for (g in groups) {
            if (g.group.id == groupId) continue
            val claimOf = claims[g.group.id].orEmpty().associate { it.memberId to it.youId }
            for (m in g.members) {
                if (m.id == g.selfId) continue
                val k = key(m.name)
                if (k.isEmpty()) continue
                val pid = claimOf[m.id]
                if (pid != null) {
                    if (pid in targetPids) continue
                    val acc = byPid.getOrPut(pid) { Acc(m.name.trim(), mutableListOf(), pid, g.group.id) }
                    if (g.group.name !in acc.groups) acc.groups.add(g.group.name)
                    acc.seen.add(g.group.id to m.id)
                } else {
                    val acc = byName.getOrPut(k) { Acc(m.name.trim(), mutableListOf(), null, null) }
                    if (g.group.name !in acc.groups) acc.groups.add(g.group.name)
                    acc.seen.add(g.group.id to m.id)
                }
            }
        }
        // An unclaimed name that matches a participant's name is that person.
        for (k in byName.keys.toList()) {
            val match = byPid.values.firstOrNull { key(it.name) == k } ?: continue
            val merged = byName.remove(k) ?: continue
            merged.groups.forEach { if (it !in match.groups) match.groups.add(it) }
            match.seen.addAll(merged.seen)
        }
        return (byPid.values + byName.values)
            .filter { key(it.name) !in targetNames }
            // The card is about no one group: the first that has a preview.
            .map {
                RecentPerson(
                    it.name, it.groups.size, it.groups.take(2), it.pid, it.via,
                    MemberPreviews.pick(previews, it.seen, preferred = null),
                )
            }
            .sortedWith(
                compareByDescending<RecentPerson> { it.shared }
                    .thenBy { if (it.participantId != null) 0 else 1 }
                    .thenBy { it.name.lowercase() },
            )
            .take(limit)
    }
}

/** D-220 fix wave (F6): a recent person's row while it cannot be tapped (being added, or added) is drawn at half
 *  strength, card and chip included, as iOS's disabled plain button is (0.494 measured on the T6 look's "Added" row:
 *  .drafts/d220/fix/android/dim-measure.py). */
object RecentRow {
    const val DIMMED_ALPHA = 0.5f

    /** iOS's `.disabled(busy || isAdded)`. */
    fun enabled(busy: Boolean, added: Boolean): Boolean = !busy && !added

    fun alpha(busy: Boolean, added: Boolean): Float = if (enabled(busy, added)) 1f else DIMMED_ALPHA
}

/** D-207: the add-person screen's words; the twin of iOS's AddPersonCopy. */
object AddPersonCopy {
    const val TITLE = "Add a person"
    const val NAME = "Name"
    const val NAME_PROMPT = "Their name"
    const val USERNAME = "Username (optional)"
    const val USERNAME_PROMPT = "find them on SKIPTI"
    const val ADD = "Add"
    const val ADD_AND_INVITE = "Add and invite"
    const val ADD_ONLY = "Add only"
    const val RECENTS = "Recently with you"
    const val ON_SKIPTI = "On SKIPTI"
    const val ADDED = "Added"
    const val ADDED_AND_INVITED = "Added and invited"
    const val LOOKING = "Looking…"
    fun missing(handle: String) =
        "Nobody on SKIPTI is @$handle. Check the spelling, or add the name and send an invite link later."
    fun done(name: String, invited: Boolean) = if (invited) "$name added and invited" else "$name added"
}

object People {
    /** D-206: your @username from your cloud profile, or null (no account,
     *  none chosen, or offline). FindMeCard and the You hero read it. */
    suspend fun myUsername(): String? {
        val uid = Account.uid() ?: return null
        return runCatching {
            Cloud.db().collection("users").document(uid).get().awaitTask().getString("username")
        }.getOrNull()
    }

    const val FINDABLE_KEY = "find/findable"
    const val SYNCED_KEY = "find/synced"
    const val CHUNK = 500
    private val hash = Regex("^[0-9a-f]{64}$")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    /** findPeople's result: well-formed people only. */
    fun decode(result: JsonObject): List<FoundPerson> =
        ((result["people"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { element ->
            val p = element as? JsonObject ?: return@mapNotNull null
            val uid = p.str("uid")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            FoundPerson(uid, p.str("name"), p.str("username"), p.str("matched")?.takeIf { hash.matches(it) })
        }

    suspend fun find(query: PeopleQuery): List<FoundPerson> = when (query) {
        is PeopleQuery.Username ->
            decode(CloudFunctions.callJson("findPeople", JsonObject(mapOf("username" to JsonPrimitive(query.handle)))))
        is PeopleQuery.Email -> find(listOf(EmailFingerprint.hash(query.address)))
    }

    suspend fun find(hashes: List<String>): List<FoundPerson> {
        val out = mutableListOf<FoundPerson>()
        for (part in hashes.toSortedSet().toList().chunked(CHUNK)) {
            out += decode(
                CloudFunctions.callJson("findPeople", JsonObject(mapOf("emailHashes" to JsonArray(part.map(::JsonPrimitive))))),
            )
        }
        return out.distinctBy { it.uid }
    }

    /** `first`: the required step's save (C2), which the cloud refuses once
     *  a username exists, so it never spends the one change. */
    suspend fun setUsername(handle: String?, first: Boolean = false): String? {
        val fields = mutableMapOf<String, JsonElement>("username" to (handle?.let(::JsonPrimitive) ?: JsonNull))
        if (first) fields["first"] = JsonPrimitive(true)
        val result = CloudFunctions.callJson("setUsername", JsonObject(fields))
        return result.str("username")
    }

    /** C2: the account's username and whether its one change is left; null
     *  when there is no profile to read (signed out, offline, not registered). */
    suspend fun accountUsername(): UsernameState? {
        val uid = Account.uid() ?: return null
        return try {
            val doc = Cloud.db().collection("users").document(uid).get(Source.SERVER).awaitTask()
            if (doc.exists()) UsernameState.from(doc.data) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    enum class UsernameAvailability { AVAILABLE, TAKEN, SHAPE, UNAVAILABLE }

    /** C2: the live check while typing. Throws when the cloud cannot be reached. */
    suspend fun checkUsername(raw: String): UsernameAvailability {
        val r = CloudFunctions.callJson("checkUsername", JsonObject(mapOf("username" to JsonPrimitive(raw))))
        if ((r["available"] as? JsonPrimitive)?.booleanOrNull == true) return UsernameAvailability.AVAILABLE
        return when (r.str("reason")) {
            "taken" -> UsernameAvailability.TAKEN
            "shape" -> UsernameAvailability.SHAPE
            else -> UsernameAvailability.UNAVAILABLE
        }
    }

    /** C2: up to three free usernames from a name; empty on any failure. */
    suspend fun suggestUsernames(name: String): List<String> = try {
        val r = CloudFunctions.callJson("suggestUsernames", JsonObject(mapOf("name" to JsonPrimitive(name))))
        ((r["usernames"] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter { UsernameRules.problem(it) == null }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /** This phone's choice (absent = on, the founder's default). */
    fun findable(store: LedgerStore): Boolean =
        runCatching { store.rawKvGet(FINDABLE_KEY)?.decodeToString() != "0" }.getOrDefault(true)

    suspend fun setFindable(on: Boolean, store: LedgerStore) {
        withContext(Dispatchers.IO) { store.rawKvSet(FINDABLE_KEY, (if (on) "1" else "0").toByteArray(Charsets.UTF_8)) }
        CloudFunctions.callJson("setFindable", JsonObject(mapOf("findable" to JsonPrimitive(on))))
        Account.uid()?.let { uid ->
            withContext(Dispatchers.IO) {
                runCatching { store.rawKvSet(SYNCED_KEY, "$uid:${if (on) 1 else 0}".toByteArray(Charsets.UTF_8)) }
            }
        }
    }

    /** After a sign-in (CloudSync.registerPhone): the cloud learns this
     *  phone's choice once per account and choice. */
    suspend fun syncFindable(uid: String, store: LedgerStore) {
        val on = findable(store)
        val want = "$uid:${if (on) 1 else 0}"
        if (runCatching { store.rawKvGet(SYNCED_KEY)?.decodeToString() }.getOrNull() == want) return
        runCatching { setFindable(on, store) }
    }

    suspend fun invite(store: LedgerStore, group: NamedGroupWire, member: MemberWire, person: FoundPerson) {
        val uid = Account.uid() ?: throw CloudFunctions.Failure.SignedOut()
        // D-254: before the group is put in the cloud (a write the rules refuse an account not approved).
        Approval.localRefusal("inviteUser", Approval.cached(store, uid))?.let { throw it }
        when (CloudSync.ensureGroup(group.group.id, group.group.name, uid)) {
            CloudSync.GroupState.MEMBER -> Unit
            CloudSync.GroupState.NOT_MEMBER -> throw CloudFunctions.Failure.Refused("PERMISSION_DENIED")
            CloudSync.GroupState.OFFLINE -> throw CloudFunctions.Failure.Offline()
        }
        val data = mutableMapOf(
            "groupId" to group.group.id, "memberId" to member.id, "memberName" to member.name,
            "groupName" to group.group.name, "targetUid" to person.uid,
        )
        withContext(Dispatchers.IO) { store.loadSelf()?.name }?.let { data["inviterName"] = it }
        CloudFunctions.call("inviteUser", data)
    }

    /** D-207: invites someone from your recents who joined a group you share
     *  (inviteKnown); the cloud finds their account, the phone never sees it. */
    suspend fun inviteKnown(store: LedgerStore, group: NamedGroupWire, member: MemberWire, recent: RecentPerson) {
        val uid = Account.uid() ?: throw CloudFunctions.Failure.SignedOut()
        val participantId = recent.participantId ?: return
        val via = recent.viaGroupId ?: return
        // D-254: before the group is put in the cloud (a write the rules refuse an account not approved).
        Approval.localRefusal("inviteKnown", Approval.cached(store, uid))?.let { throw it }
        when (CloudSync.ensureGroup(group.group.id, group.group.name, uid)) {
            CloudSync.GroupState.MEMBER -> Unit
            CloudSync.GroupState.NOT_MEMBER -> throw CloudFunctions.Failure.Refused("PERMISSION_DENIED")
            CloudSync.GroupState.OFFLINE -> throw CloudFunctions.Failure.Offline()
        }
        val data = mutableMapOf(
            "groupId" to group.group.id, "memberId" to member.id, "memberName" to member.name,
            "groupName" to group.group.name, "viaGroupId" to via, "participantId" to participantId,
        )
        withContext(Dispatchers.IO) { store.loadSelf()?.name }?.let { data["inviterName"] = it }
        CloudFunctions.call("inviteKnown", data)
    }

    /** D-207: the recents for adding to [groupId], from this phone's ledger. */
    fun recents(store: LedgerStore, groupId: String): List<RecentPerson> = runCatching {
        val claims = store.projectGroups().groups.associate { it.group.id to it.claims }
        // D-211: a preview is decoration (PeopleScreen keeps the last good ones when a
        // read fails); a failed read here only means initials.
        val previews = MemberPreviews.reloaded(emptyMap()) { store.memberPreviews() }
        Recents.people(store.namedGroups(), claims, groupId, previews = previews)
    }.getOrDefault(emptyList())

    fun failure(error: Throwable): String = when ((error as? CloudFunctions.Failure.Refused)?.status) {
        "ALREADY_EXISTS" -> PeopleCopy.USERNAME_TAKEN
        "RESOURCE_EXHAUSTED" -> PeopleCopy.LIMIT
        "FAILED_PRECONDITION" -> PeopleCopy.ALREADY_IN
        "NOT_FOUND" -> PeopleCopy.NO_MATCH
        else -> InviteCopy.failure(error)
    }

    /** D-218: the failure of an invite call (inviteUser, inviteKnown). `already-exists` there
     *  means the place is held; on the username path it still means "taken", so only the
     *  invite call sites read it this way. */
    fun inviteFailure(error: Throwable): String =
        if ((error as? CloudFunctions.Failure.Refused)?.status == "ALREADY_EXISTS") InviteCopy.HELD else failure(error)

    /** Why a username cannot be saved, before asking the cloud (C2: the
     *  cloud's own list, bundled). */
    fun usernameProblem(raw: String): String? = when (UsernameRules.problem(raw)) {
        UsernameRules.Problem.SHAPE -> PeopleCopy.USERNAME_SHAPE
        UsernameRules.Problem.UNAVAILABLE -> PeopleCopy.USERNAME_RESERVED
        null -> null
    }
}

/** Your contacts' emails, read only when you ask, kept only on screen. */
object ContactBook {
    data class Entry(val name: String, val email: String)

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Call off the main thread, after the permission. */
    fun entries(context: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val email = cursor.getString(0) ?: continue
                out += Entry(cursor.getString(1) ?: "", email)
            }
        }
        return out
    }

    /** Fingerprint → the contact's name (the first one with that email). */
    fun byHash(entries: List<Entry>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (e in entries) {
            if (!EmailFingerprint.looksLikeEmail(e.email.trim())) continue
            out.getOrPut(EmailFingerprint.hash(e.email)) { e.name.ifEmpty { e.email } }
        }
        return out
    }
}

/** Invitations sent to this account (users/{uid}/invites), while signed in
 *  with sync on. */
object IncomingInvites {
    data class Item(
        val id: String, val groupName: String, val fromName: String?, val memberName: String, val token: String,
        /** The group it joins (D-208); null in an invitation older than that field. */
        val groupId: String? = null,
    ) {
        val link: String get() = InviteLink.make(token)
    }

    private val token = Regex("^[A-Za-z0-9_-]{43}$")
    private val groupHex = Regex("^[0-9a-f]{32}$")

    /** D-191's rule: Compose state made on the main thread before any frame. */
    fun createState() = Unit

    var items by mutableStateOf<List<Item>>(emptyList())
        private set
    private var listener: ListenerRegistration? = null
    private var uid: String? = null

    /** A document the way inviteUser writes it, or null. */
    fun item(id: String, data: Map<String, Any?>): Item? {
        val group = (data["groupName"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val member = (data["memberName"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val t = (data["token"] as? String)?.takeIf { token.matches(it) } ?: return null
        val groupId = (data["groupId"] as? String)?.takeIf { groupHex.matches(it) }
        return Item(id, group, data["fromName"] as? String, member, t, groupId)
    }

    /** D-208: the invitations worth offering: not one to a group already on
     *  this phone, whose Join could only say "already used". */
    fun waiting(items: List<Item>, joined: Set<String>): List<Item> =
        items.filter { item -> item.groupId?.let { it !in joined } ?: true }

    @Synchronized
    fun listen(uid: String) {
        if (listener != null) return
        this.uid = uid
        listener = Cloud.db().collection("users").document(uid).collection("invites")
            .addSnapshotListener { snapshot, _ ->
                val docs = snapshot?.documents ?: return@addSnapshotListener
                items = docs.mapNotNull { item(it.id, it.data ?: emptyMap()) }
            }
    }

    @Synchronized
    fun stop() {
        listener?.remove()
        listener = null
        uid = null
        items = emptyList()
    }

    fun dismiss(item: Item) {
        val owner = uid ?: return
        items = items.filterNot { it.id == item.id }
        Cloud.db().collection("users").document(owner).collection("invites").document(item.id).delete()
    }
}

// ---- Screens ----

/** Settings: your username and whether people can find you (account only).
 *  C2: set once, changed once, never removed. Picking and the one change are
 *  routes over the tabs (`onPick`, `onChange` with the handle captured at the
 *  tap); returning draws this card afresh, which reads the account again.
 *  `known`: what the shell already knows (the handle just changed to), kept
 *  when the read fails (UsernameState.merge); `onKnown` hands the result back. */
@Composable
fun FindMeCard(
    vaultPath: String,
    dark: Boolean,
    known: UsernameState? = null,
    onKnown: (UsernameState?) -> Unit = {},
    onPick: () -> Unit = {},
    onChange: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var findable by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf(known) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vaultPath) {
        findable = withContext(Dispatchers.IO) { People.findable(LedgerStore(vaultPath)) }
        // A failed read keeps what was known (C2, UsernameState.merge).
        val read = People.accountUsername()
        state = UsernameState.merge(known = state, read = read, signedIn = Account.isSignedIn())
        onKnown(state)
    }
    Column(Modifier.glassCard(dark)) {
        SectionLabel(PeopleCopy.FIND_ME, dark)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(PeopleCopy.USERNAME, style = Type.body(dark))
            when (val s = state) {
                is UsernameState.CanChange -> Text(PeopleCopy.handle(s.handle), style = Type.body(dark))
                is UsernameState.Final -> Text(PeopleCopy.handle(s.handle), style = Type.body(dark))
                UsernameState.None -> Text(PeopleCopy.NO_USERNAME, style = Type.bodyDim(dark))
                // Unread (offline): say nothing rather than "Not set".
                null -> Unit
            }
        }
        // C2: set once, changed once; never removed.
        when (val s = state) {
            is UsernameState.CanChange -> {
                Spacer(Modifier.height(10.dp))
                Text(UsernameCopy.ONE_CHANGE_LEFT, style = Type.bodyDim(dark))
                Spacer(Modifier.height(10.dp))
                SecondaryAction(PeopleCopy.CHANGE_USERNAME, dark) { onChange(s.handle) }
            }
            is UsernameState.Final -> {
                Spacer(Modifier.height(10.dp))
                Text(UsernameCopy.CHANGED_ONCE, style = Type.bodyDim(dark))
            }
            UsernameState.None -> {
                Spacer(Modifier.height(10.dp))
                SecondaryAction(PeopleCopy.CHOOSE_USERNAME, dark) { onPick() }
            }
            null -> Unit
        }
        Spacer(Modifier.height(10.dp))
        // D-225 (Design 3): a `.toggle` row with the drawn switch; the whole row toggles it, one Switch to TalkBack.
        ListRow(
            PeopleCopy.FINDABLE_TOGGLE,
            dark,
            trailing = RowTrailing.Toggle(findable) { on ->
                findable = on
                scope.launch {
                    error = runCatching { People.setFindable(on, LedgerStore(vaultPath)) }
                        .exceptionOrNull()?.let(People::failure)
                }
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(if (findable) PeopleCopy.FINDABLE_ON else PeopleCopy.FINDABLE_OFF, style = Type.bodyDim(dark))
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = Type.body(dark).copy(color = Tok.owed(dark)))
        }
    }
}

/** Find one named member of a group on SKIPTI, then invite them. */
@Composable
fun FindPersonScreen(vaultPath: String, group: NamedGroupWire, member: MemberWire, onBack: () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoundPerson>?>(null) }
    var contacts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    /** Which of the two buttons is working: only Search says "Searching…". */
    var searchingByName by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var invitedUid by remember { mutableStateOf<String?>(null) }
    var invitingUid by remember { mutableStateOf<String?>(null) }
    val askPush = rememberPushAsk()

    fun search() {
        val parsed = PeopleQuery.parse(query) ?: run {
            message = PeopleCopy.NOT_A_QUERY
            return
        }
        busy = true
        searchingByName = true
        scope.launch {
            runCatching { People.find(parsed) }
                .onSuccess {
                    results = it
                    message = if (it.isEmpty()) PeopleCopy.NO_MATCH else null
                }
                .onFailure { message = People.failure(it) }
            busy = false
            searchingByName = false
        }
    }

    fun searchContacts() {
        busy = true
        scope.launch {
            val byHash = withContext(Dispatchers.IO) { ContactBook.byHash(ContactBook.entries(context)) }
            if (byHash.isEmpty()) {
                message = PeopleCopy.NO_CONTACTS
            } else {
                contacts = byHash
                runCatching { People.find(byHash.keys.toList()) }
                    .onSuccess {
                        results = it
                        message = if (it.isEmpty()) PeopleCopy.NO_MATCH else null
                    }
                    .onFailure { message = People.failure(it) }
            }
            busy = false
        }
    }

    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) searchContacts() else message = PeopleCopy.CONTACTS_DENIED
    }

    PushedPage(PeopleCopy.findTitle(member.name), dark, onBack, spacing = 12.dp) {
        // D-254: one line instead of the search and Find friends in contacts while the account is not approved.
        ApprovalCopy.invite(Approval.state)?.let {
            Text(it, style = Type.bodyDim(dark))
            return@PushedPage
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(PeopleCopy.QUERY_PROMPT) },
            singleLine = true,
            textStyle = Type.body(dark),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email, imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryAction(if (searchingByName) PeopleCopy.SEARCHING else PeopleCopy.SEARCH, dark, enabled = !busy) { search() }
        SecondaryAction(PeopleCopy.FROM_CONTACTS, dark) {
            if (busy) return@SecondaryAction
            if (ContactBook.canRead(context)) searchContacts() else askContacts.launch(Manifest.permission.READ_CONTACTS)
        }
        Text(PeopleCopy.CONTACTS_DETAIL, style = Type.bodyDim(dark))
        message?.let { Text(it, style = Type.body(dark), modifier = Modifier.glassCard(dark)) }
        val found = results
        if (!found.isNullOrEmpty()) {
            // D-234 wave (decision 102): iOS pads this card 14.
            Column(Modifier.glassCard(dark, padding = 14)) {
                found.forEachIndexed { i, person ->
                    if (i > 0) HorizontalDivider(color = Tok.hairline(dark))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(person.title(contacts), style = Type.label(dark, 15))
                            person.detail(contacts)?.let { Text(it.withRunes(), style = Type.bodyDim(dark)) }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (invitedUid != person.uid) {
                            androidx.compose.material3.TextButton(
                                enabled = invitingUid == null && invitedUid == null,
                                onClick = {
                                    invitingUid = person.uid
                                    scope.launch {
                                        runCatching { People.invite(LedgerStore(vaultPath), group, member, person) }
                                            .onSuccess {
                                                invitedUid = person.uid
                                                message = PeopleCopy.invited(person.title(contacts))
                                                askPush()
                                            }
                                            .onFailure { message = People.inviteFailure(it) }
                                        invitingUid = null
                                    }
                                },
                            ) {
                                Text(
                                    if (invitingUid == person.uid) PeopleCopy.INVITING else PeopleCopy.INVITE,
                                    style = Type.body(dark).copy(color = Tok.accent(dark)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Invitations to this account, each with Join and Dismiss. */
@Composable
fun InvitationsList(dark: Boolean, joined: Set<String>, onJoin: (IncomingInvites.Item) -> Unit) {
    // D-208: not the invitations to groups already on this phone.
    val items = IncomingInvites.waiting(IncomingInvites.items, joined)
    if (items.isEmpty()) return
    Text(PeopleCopy.INVITATIONS, style = Type.label(dark, 17))
    items.forEach { item ->
        Column(Modifier.glassCard(dark)) {
            Text(PeopleCopy.invitedYou(item.fromName, item.groupName), style = Type.label(dark, 15))
            Spacer(Modifier.height(4.dp))
            Text(PeopleCopy.joinAs(item.memberName), style = Type.bodyDim(dark))
            Spacer(Modifier.height(10.dp))
            PrimaryAction(PeopleCopy.JOIN, dark) { onJoin(item) }
            Spacer(Modifier.height(8.dp))
            SecondaryAction(PeopleCopy.DISMISS, dark) { IncomingInvites.dismiss(item) }
        }
    }
}

/** Opened by a tapped invite push. */
@Composable
fun InvitationsScreen(vaultPath: String, dark: Boolean, onJoin: (IncomingInvites.Item) -> Unit, onBack: () -> Unit) {
    var joined by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(vaultPath) {
        joined = withContext(Dispatchers.IO) {
            runCatching { LedgerStore(vaultPath).namedGroups().map { it.group.id }.toSet() }.getOrDefault(emptySet())
        }
    }
    // D-217 final fix: opened only by a tapped push, a sheet with Close as on iOS (SkiptiApp.swift).
    SheetPage(PeopleCopy.INVITATIONS, dark, onCancel = onBack, spacing = 12.dp, cancelLabel = BarDecisions.CLOSE_LABEL) {
        if (IncomingInvites.waiting(IncomingInvites.items, joined).isEmpty()) {
            Text(PeopleCopy.NO_INVITATIONS, style = Type.bodyDim(dark), modifier = Modifier.glassCard(dark))
        }
        InvitationsList(dark, joined, onJoin)
    }
}

/**
 * D-210 (Task 8 look, found on devices): adding a person is a group operation,
 * and it has to reach the cloud log before the invitee can join and claim the
 * place. [add] saves it and then calls [synced] (the sync's `ledgerChanged`);
 * nothing is called when the add is refused. iOS's twin is `AddPerson` in
 * People.swift.
 */
object AddPerson {
    fun add(
        store: LedgerStore, groupId: String, memberName: String, synced: () -> Unit,
    ): Pair<NamedGroupWire, MemberWire>? {
        val before = store.namedGroups().firstOrNull { it.group.id == groupId }?.members.orEmpty().map { it.id }.toSet()
        val named = store.addMember(groupId, memberName)
        synced()
        return named.members.firstOrNull { it.id !in before }?.let { named to it }
    }
}

/** D-207 (the founder's item 1 and his recents note, 2026-10-13): a name, an
 *  optional @username looked up and shown before anything is added, "Add and
 *  invite" in one tap, and recents from your other groups. Without an account
 *  the username field, the "On SKIPTI" marks and inviting are absent
 *  (GUARANTEES #5). The twin of iOS's AddPersonSheet (a full screen here, as
 *  InviteScreen is). */
@Composable
fun AddPersonScreen(vaultPath: String, groupId: String, onBack: () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    // D-224 fix wave (F6): iOS's add-person sheet covers the shell's toast; this screen shows only its own.
    CoversShellToast()
    val scope = rememberCoroutineScope()
    val signedIn = remember { Account.isSignedIn() }
    /** D-254: signed in, and the account not waiting or refused: Add by @username and the recents' invites are there. */
    val invites = signedIn && ApprovalCopy.invite(Approval.state) == null
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var looking by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<FoundPerson?>(null) }
    var missing by remember { mutableStateOf<String?>(null) }
    var recents by remember { mutableStateOf<List<RecentPerson>>(emptyList()) }
    var added by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // D-224 (Design 8): the done line is the toast, in this screen's own centre and host (it sits above the shell).
    val localDone = remember { DoneMomentCenter() }

    LaunchedEffect(vaultPath, groupId) {
        recents = withContext(Dispatchers.IO) { People.recents(LedgerStore(vaultPath), groupId) }
    }

    /** Adds [memberName]; the group and the new member, or null. Every path
     *  (name only, add and invite, recents) comes through here, and the sync
     *  hears of the operation as soon as it is saved ([AddPerson.add]). */
    suspend fun addMember(memberName: String): Pair<NamedGroupWire, MemberWire>? = withContext(Dispatchers.IO) {
        AddPerson.add(LedgerStore(vaultPath), groupId, memberName) { CloudSync.ledgerChanged(vaultPath) }
    }

    /** One add: busy while it runs, the done moment after, the error if not. */
    fun run(who: String, work: suspend () -> Boolean, after: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { work() }
                .onSuccess { invited ->
                    error = null
                    name = ""
                    localDone.post(DoneMoments.line(AddPersonCopy.done(who, invited)))
                    after()
                }
                .onFailure { error = if (it is CloudFunctions.Failure) People.inviteFailure(it) else GroupCopy.message(it) }
            busy = false
        }
    }

    fun find() {
        val h = handle.replace("@", "").trim()
        val parsed = PeopleQuery.parse("@$h") as? PeopleQuery.Username ?: run {
            error = PeopleCopy.NOT_A_QUERY
            return
        }
        looking = true
        found = null
        missing = null
        scope.launch {
            runCatching { People.find(parsed) }
                .onSuccess { list ->
                    found = list.firstOrNull()
                    if (found == null) missing = parsed.handle
                    if (name.isBlank()) found?.let { name = it.name ?: parsed.handle }
                    error = null
                }
                .onFailure { error = People.failure(it) }
            looking = false
        }
    }

    fun addName() {
        val n = name.trim()
        if (n.isEmpty()) return
        run(n, { addMember(n); false })
    }

    // D-217 Task 8: iOS's sheet: the inline title and "Done" in the leading corner (People.swift:898-907,
    // `.cancellationAction` labelled Done: it only closes), 20 pt sides.
    Box(Modifier.fillMaxSize()) {
        SheetPage(
            AddPersonCopy.TITLE, dark, onCancel = onBack, spacing = 12.dp,
            side = BarDecisions.PAGE_SIDE_WIDE.dp, cancelLabel = BarDecisions.DONE_LABEL,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(AddPersonCopy.NAME) },
                placeholder = { Text(AddPersonCopy.NAME_PROMPT) },
                singleLine = true,
                textStyle = Type.body(dark),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (found == null) addName() }),
                modifier = Modifier.fillMaxWidth(),
            )
            val inviteLine = ApprovalCopy.invite(Approval.state)
            if (signedIn && inviteLine != null) {
                // D-254: one line instead of Add by @username while the account is not approved.
                Text(inviteLine, style = Type.bodyDim(dark).copy(fontSize = 14.sp))
            } else if (signedIn) {
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it; found = null; missing = null },
                    label = { Text(AddPersonCopy.USERNAME) },
                    placeholder = { Text(AddPersonCopy.USERNAME_PROMPT) },
                    prefix = { Text("@", style = Type.body(dark).copy(color = Tok.accent(dark))) },
                    singleLine = true,
                    textStyle = Type.body(dark),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Email, imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { find() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (looking) Text(AddPersonCopy.LOOKING, style = Type.bodyDim(dark))
            found?.let { person ->
                Column(Modifier.glassCard(dark)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarView(Avatar.None, person.name ?: person.username, 44.dp, dark)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(person.title(), style = Type.label(dark, 16))
                            person.username?.let { Text(Separator.annotated(listOf("@$it", AddPersonCopy.ON_SKIPTI)), style = Type.bodyDim(dark)) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryAction(AddPersonCopy.ADD_AND_INVITE, dark, enabled = !busy) {
                        val n = name.trim().ifEmpty { person.title() }
                        run(n, {
                            val (named, member) = addMember(n) ?: return@run false
                            People.invite(LedgerStore(vaultPath), named, member, person)
                            true
                        }) { handle = ""; found = null }
                    }
                    Spacer(Modifier.height(8.dp))
                    SecondaryAction(AddPersonCopy.ADD_ONLY, dark) {
                        val n = name.trim().ifEmpty { person.title() }
                        run(n, { addMember(n); false }) { handle = ""; found = null }
                    }
                }
            }
            missing?.let { Text(AddPersonCopy.missing(it), style = Type.bodyDim(dark), modifier = Modifier.glassCard(dark)) }
            if (found == null) PrimaryAction(AddPersonCopy.ADD, dark, enabled = !busy && name.isNotBlank()) { addName() }
            if (recents.isNotEmpty()) {
                Text(AddPersonCopy.RECENTS.uppercase(), style = Type.bodyDim(dark))
                recents.forEach { r ->
                    val isAdded = r.id in added
                    val onSkipti = invites && r.participantId != null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // D-220 fix wave (F6): dimmed while it cannot be tapped, as iOS's row is. D-234 wave
                            // (decision 102): the card pads 10, as iOS's row pads 10 on a card padded 0.
                            .alpha(RecentRow.alpha(busy, isAdded))
                            .glassCard(dark, padding = 10)
                            .clickable(enabled = RecentRow.enabled(busy, isAdded)) {
                                run(r.name, {
                                    val (named, member) = addMember(r.name) ?: return@run false
                                    if (!onSkipti) return@run false
                                    People.inviteKnown(LedgerStore(vaultPath), named, member, r)
                                    true
                                }) { added = added + r.id }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarView(r.preview, r.name, 32.dp, dark)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = Type.body(dark))
                            Text(
                                if (isAdded) (if (onSkipti) AddPersonCopy.ADDED_AND_INVITED else AddPersonCopy.ADDED) else r.groups.joinToString(", "),
                                style = Type.bodyDim(dark), maxLines = 1,
                            )
                        }
                        if (onSkipti || isAdded) {
                            // D-220: iOS's chip: a pill (selected when added, the accent otherwise), label face at 11, 8 by 3.
                            val tone = if (isAdded) PillTone.SELECTED else PillTone.ACCENT
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isAdded) AddPersonCopy.ADDED else AddPersonCopy.ON_SKIPTI,
                                style = Type.label(dark, 11).copy(color = ThemePills.text(tone, dark)),
                                modifier = Modifier
                                    .themePill(tone, dark)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            error?.let { Text(it, style = Type.body(dark).copy(color = Tok.owed(dark))) }
        }
        DoneToastHost(barShown = false, center = localDone)
    }
}

