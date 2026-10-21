package com.vardrlabs.skipti

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.Timestamp
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uniffi.skipti_core.ffiMoneyFormat
import uniffi.skipti_core.ffiMoneySpoken

/**
 * D-210: who may do what in a group (one owner, admins, members), the words
 * for it, the roles the cloud reports, and the notices it leaves for an
 * account. The founder's rules (2026-10-13): one owner; only the owner makes
 * an owner (the old owner becomes an admin); the owner or an admin makes an
 * admin and only the owner takes it away; the owner or an admin removes
 * members, never the owner, and an admin never removes an admin; nobody
 * leaves, a member asks to be removed. iOS's twin is Roles.swift.
 */

// ---- The rules (pure) ----

object RoleRules {
    /** The cloud's list, as the account ("you") ids the phones know people by.
     *  D-227: [places] is the same list by place (the group's own ids for the
     *  people), and the places the asking account holds; null from a server
     *  before D-227, and in a list cached by 0.4.0. */
    @Serializable
    data class Roles(val owner: String? = null, val admins: List<String> = emptyList(), val places: Places? = null)

    /** D-227: the places that hold the owner's and the admins' roles, and the
     *  places that are yours (`groupRoles`' `places`). iOS's twin is
     *  `RoleRules.Places`. */
    @Serializable
    data class Places(val owner: List<String> = emptyList(), val admins: List<String> = emptyList(), val you: List<String> = emptyList())

    enum class Role { OWNER, ADMIN, MEMBER, UNJOINED }

    /** What a person's sheet offers the viewer; one flag per row. */
    data class Offer(
        val remove: Boolean,
        val ask: Boolean,
        val askSelf: Boolean,
        val makeAdmin: Boolean,
        val removeAdmin: Boolean,
        val makeOwner: Boolean,
    )

    /** `OWNER_DECIDES`: a request naming an admin, seen by another admin: only
     *  the owner decides it, so the card has no buttons. */
    enum class RequestCard { DECIDE, OWNER_DECIDES, ASKER, PERSON }

    /** A person's role from the cloud's list; someone without an account (no
     *  "you") is `UNJOINED`, and so is anyone while the list is unknown. */
    fun role(of: String?, roles: Roles): Role {
        if (of == null) return Role.UNJOINED
        if (roles.owner == of) return Role.OWNER
        if (of in roles.admins) return Role.ADMIN
        return Role.MEMBER
    }

    /** D-227: a row's role by its [place] when the cloud said who holds which
     *  place; otherwise today's rule by the account id [of]. A place in a role
     *  list is joined, whatever this phone knows of a claim. An owner the cloud
     *  cannot place yet (its create not up, `places.owner` empty) reads by the
     *  account rule, as 0.4.0 does (the final review's I1); the admins and your
     *  own place stay by place. */
    fun role(of: String?, place: String, roles: Roles): Role {
        val places = roles.places ?: return role(of, roles)
        if (if (places.owner.isEmpty()) of != null && of == roles.owner else place in places.owner) return Role.OWNER
        if (place in places.admins) return Role.ADMIN
        return if (of == null) Role.UNJOINED else Role.MEMBER
    }

    fun manages(viewer: Role): Boolean = viewer == Role.OWNER || viewer == Role.ADMIN

    fun offer(viewer: Role, target: Role, isSelf: Boolean): Offer {
        val remove = manages(viewer) && target != Role.OWNER && (target != Role.ADMIN || viewer == Role.OWNER) && !isSelf
        return Offer(
            remove = remove,
            ask = !isSelf && !remove && target != Role.OWNER,
            askSelf = isSelf && viewer != Role.OWNER,
            makeAdmin = manages(viewer) && target == Role.MEMBER && !isSelf,
            removeAdmin = viewer == Role.OWNER && target == Role.ADMIN,
            makeOwner = viewer == Role.OWNER && (target == Role.MEMBER || target == Role.ADMIN) && !isSelf,
        )
    }

    /** A role change the cloud confirmed (`setAdmin`, `makeOwner`). iOS's
     *  twin is `RoleRules.Change`. */
    sealed class Change {
        data class Admin(val on: Boolean) : Change()
        object Owner : Change()
    }

    /** D-227 follow-up G (iOS's F4): the roles after a change the cloud
     *  confirmed, by place, so an open sheet reads the new role at once; the
     *  refresh that follows brings the rest. The old owner becomes an admin.
     *  Without places nothing changes here (the account rule waits for the
     *  refresh). */
    fun confirmed(roles: Roles, place: String, change: Change): Roles {
        val places = roles.places ?: return roles
        val next = when (change) {
            is Change.Admin -> places.copy(
                admins = (places.admins.filter { it != place } + if (change.on) listOf(place) else emptyList()).toSortedSet().toList(),
            )
            Change.Owner -> places.copy(owner = listOf(place), admins = (places.admins + places.owner).toSortedSet().minus(place).toList())
        }
        return roles.copy(places = next)
    }

    /** Only the owner deletes a group (an admin never). */
    fun canDelete(viewer: Role): Boolean = viewer == Role.OWNER

    /** The card an open removal request shows this viewer, or null. The owner
     *  and the admins decide, unless the request is about them. [person] is
     *  the role of the person named. */
    fun requestCard(viewer: Role, person: Role, viewerIsAsker: Boolean, viewerIsPerson: Boolean): RequestCard? {
        if (viewerIsPerson) return RequestCard.PERSON
        // A request naming an admin is approved only by the owner.
        if (manages(viewer)) {
            return if (person == Role.ADMIN && viewer != Role.OWNER) RequestCard.OWNER_DECIDES else RequestCard.DECIDE
        }
        if (viewerIsAsker) return RequestCard.ASKER
        return null
    }

    /** A name as the Functions take it: trimmed, at most 80 characters (UTF-16
     *  units here, as the Functions count them), never ending in half of a
     *  surrogate pair. */
    fun wireName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.length <= 80) return trimmed
        val end = if (Character.isHighSurrogate(trimmed[79])) 79 else 80
        return trimmed.substring(0, end)
    }
}

// ---- Money for the words (core's formatter, never a shell's) ----

object RolesMoney {
    /** Core's string for an amount, or null when core cannot format it. */
    fun format(minor: Long, code: String): String? =
        try { ffiMoneyFormat(minor, code, AppLocale.current) } catch (_: Exception) { null }

    fun spoken(minor: Long, code: String): String =
        try { ffiMoneySpoken(minor, code, AppLocale.current) } catch (_: Exception) { "" }
}

// ---- The words (iOS's twin: RolesCopy in Roles.swift) ----

object RolesCopy {
    const val OWNER_PILL = "Owner"
    const val ADMIN_PILL = "Admin"

    fun removeFrom(group: String) = "Remove from $group"
    fun askToRemove(name: String) = "Ask to remove $name"
    const val ASK_SELF = "Ask to be removed"
    const val ROLE_SECTION = "Role"
    const val MAKE_ADMIN_ROW = "Make admin"
    fun makeAdminSub(name: String) = "$name can add and remove members, and name admins"
    const val REMOVE_ADMIN_ROW = "Remove admin"
    fun removeAdminSub(name: String) = "$name stays as a member"
    const val MAKE_OWNER_ROW = "Make owner"
    const val MAKE_OWNER_SUB = "There is one owner; you become an admin"

    /** Someone who has not joined has no phone to see the request. */
    fun askNote(name: String, joined: Boolean = true): String =
        if (joined) {
            "Only the owner or an admin can remove someone. $name, the owner and the admins will see your request."
        } else {
            "Only the owner or an admin can remove someone. The owner and the admins will see your request."
        }

    const val ASKED = "Request sent"
    const val CANCEL = "Cancel"
    /** A person's sheet: the row that renames them (D-186's rename, moved here). */
    const val RENAME = "Rename"
    const val SAVE = "Save"

    fun makeAdminTitle(name: String) = "Make $name an admin?"
    fun makeAdminBody(name: String) =
        "$name will be able to add people, remove members, approve requests and name other admins. Only the owner can take it away."
    fun makeAdminConfirm(name: String) = "Make $name admin"
    fun removeAdminTitle(name: String) = "Remove $name as admin?"
    fun removeAdminBody(name: String) = "$name stays in the group as a member."
    const val REMOVE_ADMIN_CONFIRM = "Remove admin"
    fun makeOwnerTitle(name: String) = "Make $name the owner?"
    fun makeOwnerBody(name: String, group: String) =
        "$name will own $group: names admins, removes anyone, can delete the group. You become an admin. Only $name can undo this."
    fun makeOwnerConfirm(name: String) = "Make $name owner"

    fun cardAsked(asker: String, name: String) = "$asker asked to remove $name"
    const val DECLINE = "Decline"
    const val REVIEW = "Review"
    fun cardAsker(name: String) = "You asked to remove $name"

    /** D-210 last wave: a person who asked for their own removal. Their own
     *  card, and everyone else's. */
    const val CARD_YOU_ASKED_TO_BE_REMOVED = "You asked to be removed"
    fun cardAskedToBeRemoved(name: String) = "$name asked to be removed"
    const val CARD_WAIT = "The owner or an admin will decide."
    /** A request naming an admin, as another admin sees it. */
    const val CARD_OWNER_WILL = "The owner will decide."
    fun cardPerson(asker: String) = "$asker asked to remove you"
    const val SETTLE_UP = "Settle up"

    fun reviewTitle(name: String, group: String) = "Remove $name from $group?"
    fun reviewBody(asker: String, name: String, joined: Boolean, noHistory: Boolean) =
        "$asker asked. " + removalBody(name, joined, noHistory)
    const val CONTINUE_TO_REMOVE = "Continue to remove"
    const val DECLINE_REQUEST = "Decline request"

    fun removeTitle(name: String, group: String) = "Remove $name from $group?"
    fun removeBody(name: String) = "$name will get a notification and keeps a read-only copy of the group's history."

    /** The confirm's body: a joined person is notified and keeps a copy;
     *  someone who never joined is sent nothing, and with no history D-186's
     *  words (true only then) apply. */
    fun removalBody(name: String, joined: Boolean, noHistory: Boolean): String {
        if (joined) return removeBody(name)
        return if (noHistory) REMOVE_UNJOINED_BODY else unjoinedNothingSent(name)
    }

    fun unjoinedNothingSent(name: String) = "$name has not joined, so nothing is sent to them."
    fun youOwe(amount: String) = "you owe $amount"
    fun youAreOwed(amount: String) = "you are owed $amount"

    /** A request card's line under its title: "Sam owes ₹1,000.00 here." */
    fun cardBalance(name: String, plan: Map<String, Long>): String? = balanceHere(name, plan)?.let { "$it." }
    const val REVIEW_CHOOSE_NEXT = "You'll choose next: settle first, or move it to others."

    /** The review sheet's banner, when the person has a balance. */
    fun reviewBanner(name: String, plan: Map<String, Long>): Pair<String, String>? =
        balanceHere(name, plan)?.let { it to REVIEW_CHOOSE_NEXT }
    const val REMOVE_CONFIRM = "Remove"
    const val REMOVE_UNJOINED_BODY = "They are in no expense or payment here. You can add them again later."

    fun balanceTitle(name: String) = "Remove $name"
    fun owes(name: String, amount: String) = "$name owes $amount"
    fun isOwed(name: String, amount: String) = "$name is owed $amount"

    /** A person's sheet, under Remove or Ask: "Asha owes ₹200.00 here"
     *  (currencies in code order, joined with the meta separator, one "here" at the end);
     *  null when settled. A currency core cannot format is left out. */
    fun balanceHere(name: String, plan: Map<String, Long>): String? {
        val parts = plan.keys.sorted().mapNotNull { code ->
            val net = plan[code] ?: return@mapNotNull null
            if (net == 0L) return@mapNotNull null
            val amount = RolesMoney.format(Math.abs(net), code) ?: return@mapNotNull null
            if (net < 0) owes(name, amount) else isOwed(name, amount)
        }
        return if (parts.isEmpty()) null else parts.joinToString(Separator.meta()) + " here"
    }

    fun balanceLede(name: String) = "Before $name goes, decide what happens to it."
    const val SETTLE_FIRST = "Settle first"
    fun settleFirstSub(name: String) = "Record $name's payments, then remove $name."
    const val REDISTRIBUTE = "Redistribute"
    fun redistributeSub(name: String) =
        "Move $name's balance to people you choose. Their balances change; $name's goes to zero."
    const val NEXT = "Next"
    const val RECORD_PAYMENTS_TITLE = "Record these payments?"
    const val CANNOT_WRITE_PAYMENT = "A payment's amount could not be written down, so nothing was recorded."
    fun paid(from: String, to: String, amount: String) = "$from paid $to $amount"
    fun recordAndRemove(name: String) = "Record and remove $name"

    const val REDISTRIBUTE_TITLE = "Redistribute"
    fun redistributeLede(name: String) = "$name's balance goes to the people you tick. Their new balances:"
    const val EDIT_AMOUNTS = "Edit amounts"
    const val SPLIT_EQUALLY = "Split equally"
    const val ADDS_UP = "Adds up"

    /** The tally's right side: what is assigned of what there is. */
    fun ofTotal(assigned: String, total: String) = "$assigned of $total"

    /** A row's balance on the Redistribute screen: "owes ₹200.00", "owed ₹700.00", "settled". */
    fun rowBalance(minor: Long, amount: String) =
        if (minor < 0) "owes $amount" else if (minor > 0) "owed $amount" else "settled"
    fun takes(amount: String) = "takes $amount"
    fun rowNow(balance: String) = "$balance now"
    fun left(amount: String) = "$amount left to assign"
    fun tooMuch(amount: String) = "$amount too much"
    const val TYPE_AMOUNTS = "Type an amount for each ticked person"
    const val TICK_ONE = "Tick at least one person"
    fun removeButton(name: String) = "Remove $name"
    fun recordedFine(group: String) = "Recorded in $group's history for everyone."

    /** The history line for a redistribution: `A`, `A and B`, `A, B and C`. */
    fun moved(from: String, amount: String, to: List<String>): String {
        val list = when (to.size) {
            // Nobody named (a record this phone cannot name anyone in): no
            // dangling "moved to". iOS's twin says the same.
            0 -> return "$from's $amount moved"
            1 -> to[0]
            else -> to.dropLast(1).joinToString(", ") + " and " + to.last()
        }
        return "$from's $amount moved to $list"
    }

    const val GROUP_SETTINGS_TITLE = "Group settings"
    const val SETTINGS_NAME = "Name"
    const val SETTINGS_PICTURE = "Picture"
    const val SETTINGS_SETTLES_IN = "Settles in"
    const val DELETE_GROUP = "Delete group"
    const val DELETE_GROUP_SUB = "Only you, the owner, can do this"
    fun deleteCloudTitle(group: String) = "Delete $group for everyone?"
    const val DELETE_CLOUD_BODY =
        "It's erased from SKIPTI's cloud and nobody can add to it again. Everyone keeps a read-only copy on their phone."
    const val STILL_OPEN = "Still open"
    const val DELETE_FOR_EVERYONE = "Delete for everyone"
    fun deleteLocalTitle(group: String) = "Delete $group?"
    const val DELETE_LOCAL_BODY = "It's removed from this phone."
    const val DELETE_LOCAL_CONFIRM = "Delete"

    fun removedBanner(by: String, date: String) = "$by removed you on $date"
    fun removedBannerBody(group: String) =
        "This is your copy of $group's history. Nothing new reaches it, and nothing can be added."
    fun deletedBanner(by: String, date: String) = "$by deleted this group on $date"
    const val DELETED_BANNER_BODY = "This is your copy. Nothing new can be added."
    /** D-229 decision 45: the reason a group whose place is lost is read-only (the banner's one line). */
    const val PLACE_LOST_BANNER =
        "You can't add to this group from this phone: someone else holds your place in it. Ask the group's owner to check People."
    const val EXPORT_THIS_GROUP = "Export this group"
    const val FORGET_ROW = "Remove from this phone"
    fun forgetTitle(group: String) = "Remove $group from this phone?"
    const val FORGET_BODY = "Its history is erased from this phone only. Export it first if you want to keep it."
    const val FORGET_CONFIRM = "Remove from this phone"

    // What a Function says in a notice and a push (written by the cloud; here
    // so the words stay with their twins).
    fun noticeRequest(asker: String, name: String) = "$asker asked to remove $name"
    fun noticeRequestPerson(asker: String) = "$asker asked to remove you"
    fun noticeDeclined(by: String, name: String, group: String) = "$by kept $name in $group"
    fun noticeDeclinedPerson(by: String, group: String) = "$by kept you in $group"
    fun noticeRemoved(by: String, group: String) = "$by removed you from $group"
    fun noticeAdmin(by: String, group: String) = "$by made you an admin of $group"
    fun noticeOwner(by: String, group: String) = "$by made you the owner of $group"
    fun noticeDeleted(by: String, group: String) = "$by deleted $group"

    // Failures of the role and removal calls.
    const val NOT_ALLOWED = "Only the owner or an admin can do that."
    const val CHANGED = "That changed on another phone. Try again."
    const val GONE = "This group no longer exists."
    /** NOT_FOUND from a call about a person: the group is there, the person is not. */
    const val PERSON_GONE = "That person is not in this group any more."

    /** requestRemoval says ALREADY_EXISTS when that person was already asked
     *  about: the screens show the same "Request sent" as for a new request. */
    fun alreadyAsked(error: Throwable): Boolean =
        (error as? CloudFunctions.Failure.Refused)?.status == "ALREADY_EXISTS"

    /** A refusal as words; anything else is the offline line. [person]: the
     *  call was about one person (requestRemoval, removeMember, declineRemoval,
     *  setAdmin, makeOwner), so NOT_FOUND means that person. */
    fun failure(error: Throwable, person: Boolean = false): String {
        val refused = error as? CloudFunctions.Failure.Refused ?: return InviteCopy.OFFLINE
        return when (refused.status) {
            "PERMISSION_DENIED" -> NOT_ALLOWED
            "FAILED_PRECONDITION" -> CHANGED
            "NOT_FOUND" -> if (person) PERSON_GONE else GONE
            else -> InviteCopy.OFFLINE
        }
    }
}

// ---- D-252: the words of a group that ended (iOS's twin: GroupEndCopy in Roles.swift) ----

/**
 * D-252 (decision 218): the words of a group that ended on this phone: one that left the account's listing and the
 * server no longer gives ([ReadOnlyDTO.VANISHED]: a removal and a deletion read the same from outside), a deletion
 * this account made (on this phone or another of its phones), and a write refused in any read-only copy. iOS's
 * `GroupEndCopy` is the one table; `GroupEndTest.the_copy_table_is_read_from_the_swift_one` reads it line by line.
 */
object GroupEndCopy {
    /** The banner's one line for a vanished group (its second is [RolesCopy.DELETED_BANNER_BODY]). */
    const val NO_LONGER_SHARED = "This group is no longer shared with you."

    /** The banner's line for a group this account deleted, on every one of its phones. */
    fun youDeleted(group: String) = "You deleted $group."

    /** A write into a read-only copy, refused before anything is written ([Placing.ReadOnlyCopy]). */
    const val REFUSED_VANISHED = "This group is no longer shared with you. Nothing new can be added."
    const val REFUSED_YOU_DELETED = "You deleted this group. Nothing new can be added."
    const val REFUSED_DELETED = "This group was deleted. Nothing new can be added."
    const val REFUSED_REMOVED = "You were removed from this group. Nothing new can be added."

    /** The refusal's words for a copy's mark (a lost place has its own, [RolesCopy.PLACE_LOST_BANNER]). */
    fun refused(mark: ReadOnlyDTO): String = when (mark.kind) {
        ReadOnlyDTO.VANISHED -> REFUSED_VANISHED
        "removed" -> REFUSED_REMOVED
        ReadOnlyDTO.PLACE_LOST -> RolesCopy.PLACE_LOST_BANNER
        else -> if (mark.byYou == true) REFUSED_YOU_DELETED else REFUSED_DELETED
    }
}

// ---- D-252: a group that left the account's listing ----

/**
 * D-252 (decision 218): a group this phone holds that the account's listing no longer has, and no removal or deletion
 * notice explained: the phone asks the server once (a read of `groups/{g}`). Pure, so every case is tested alone;
 * [CloudSync]'s `checkVanished` does the reading. iOS's twin is `GroupGone` in Roles.swift.
 */
object GroupGone {
    /** What the one read said. */
    enum class Read {
        /** The document is there and this account may read it (a member, or a removed person: their notice says why). */
        EXISTS,

        /** The rules refused it: the account is no longer in it, or it is gone (a deleted group refuses every read). */
        REFUSED,
        NOT_FOUND,

        /** No answer from the server (offline, a timeout, auth trouble): it says nothing about the group. */
        NETWORK_ERROR,
    }

    enum class Outcome {
        /** Nothing changes (it is still there). */
        KEEP,

        /** The local copy becomes read-only ("This group is no longer shared with you."). */
        READ_ONLY,

        /** Nothing changes now; asked again at the next listing change or launch. */
        RETRY,
    }

    fun decide(read: Read): Outcome = when (read) {
        Read.REFUSED, Read.NOT_FOUND -> Outcome.READ_ONLY
        Read.NETWORK_ERROR -> Outcome.RETRY
        Read.EXISTS -> Outcome.KEEP
    }

    /** The read as `CloudSync.groupRead` gives it (the D-210 removal read: permission-denied and not-found are
     *  [Notices.GroupRead.Refused], null is no answer). */
    fun read(answer: Notices.GroupRead?): Read = when (answer) {
        is Notices.GroupRead.Found -> Read.EXISTS
        Notices.GroupRead.Missing -> Read.NOT_FOUND
        Notices.GroupRead.Refused -> Read.REFUSED
        null -> Read.NETWORK_ERROR
    }

    /** The groups to ask about, sorted: held on this phone AND in the cloud from here (`sync/groups-up`: a group that
     *  never left this phone is not in any listing, and asking about it would end it wrongly), not listed now, not
     *  already a copy, no removal or deletion notice waiting for it (that notice says why), and not already asked this
     *  session ([asked]: a read that said keep, or one still in flight). The first listing of a session sweeps every
     *  such group (0.5.0's groups deleted on another phone of the account, decision 218 point 4). */
    fun toCheck(
        held: Set<String>,
        groupsUp: Set<String>,
        listed: Set<String>,
        copies: Set<String>,
        noticed: Set<String>,
        asked: Set<String>,
    ): List<String> = (held intersect groupsUp).minus(listed).minus(copies).minus(noticed).minus(asked).sorted()

    /** Whether the vanished mark may be written over what the copy already says: only an unmarked group or a lost
     *  place (derived, never stored). A removal or a deletion already says more, and is never made vaguer. */
    fun marks(over: ReadOnlyDTO?): Boolean = over == null || over.kind == ReadOnlyDTO.PLACE_LOST

    /** The mark itself. */
    fun mark(atMs: Long): ReadOnlyDTO = ReadOnlyDTO(ReadOnlyDTO.VANISHED, "", atMs)

    /** A vanished group listed again (the account is in it once more: a false alarm, or a new invite) is shared again:
     *  its vanished mark lifts. Only that mark: a removal or a deletion lifts only through an accepted invite. */
    fun lifts(mark: ReadOnlyDTO?, listedConfirmed: Boolean): Boolean =
        listedConfirmed && mark?.kind == ReadOnlyDTO.VANISHED
}

// ---- The main thread, as a seam ----

/**
 * Compose state in [GroupRoles] and [Notices] is changed on the main thread
 * only, so a read-modify-write (`roles + x`) from two callers never loses one
 * (iOS gets this from @MainActor). Production posts to the main looper; the
 * JVM tests inject their own thread, because the Android looper is not there.
 */
object MainThread {
    @Volatile var executor: java.util.concurrent.Executor = java.util.concurrent.Executor { r ->
        android.os.Handler(android.os.Looper.getMainLooper()).post(r)
    }

    /** True where a change may be made inline. Without a looper (a JVM test)
     *  everything counts as the main thread unless a test says otherwise. */
    @Volatile var isMain: () -> Boolean = {
        try {
            val main = android.os.Looper.getMainLooper()
            main == null || main === android.os.Looper.myLooper()
        } catch (_: Throwable) {
            true
        }
    }

    /** Runs [block] on the main thread: inline when already there, else posted
     *  (not waited for). */
    fun run(block: () -> Unit) {
        if (isMain()) block() else executor.execute { block() }
    }

    /** Runs [block] on the main thread and waits for it, so what the caller
     *  does next sees the change. */
    suspend fun <T> await(block: () -> T): T {
        if (isMain()) return block()
        return kotlin.coroutines.suspendCoroutine { cont ->
            executor.execute { cont.resumeWith(runCatching(block)) }
        }
    }
}

// ---- When to ask the cloud for a group's roles (D-227) ----

/**
 * D-227: when the groups listener asks `groupRoles` for a group. Only for a
 * confirmed snapshot (no pending writes: a group this phone just created is
 * not on the server yet), never twice at once for one group, and the
 * signature is kept only after a successful refresh, so a not-found or
 * offline answer is asked again on the next snapshot. Not thread safe: the
 * caller holds its own lock. iOS's twin is `RoleAsking` in Roles.swift.
 *
 * Decision 35: a refresh that ends after [clear] or after its group left
 * ([keep]) changes nothing, and a signature that arrived while its group was
 * in flight is handed back by [finished], for the caller to ask with.
 *
 * Decision 38: a failed ask (the call failed, or [ok] said no) is asked again
 * on a timer as well as on the next snapshot: [retryAfter] says when
 * ([retryDelay]: 15 s, then 60 s, then 300 s, three per signature), and the
 * timer asks only while [due] says it is still the group's own, through
 * [shouldAsk] like any other ask. [clear], [keep] and a successful ask cancel it.
 */
class RoleAsking {
    /** The signature each group's last successful refresh answered for. */
    private val kept = mutableMapOf<String, String>()
    /** The signature each group in flight was asked for. */
    private val inFlight = mutableMapOf<String, String>()
    /** The latest signature seen while its group was in flight. */
    private val pending = mutableMapOf<String, String>()
    /** D-227 follow-up B: each group's latest confirmed signature. */
    private val latest = mutableMapOf<String, String>()
    /** D-227 follow-up A: the failed answer that counted (its group was in
     *  flight for that signature) and asked nothing next; only it may set a
     *  timer ([failed]). */
    private val failed = mutableMapOf<String, String>()

    /** Decision 38: a group's timed retries of one signature: how many were
     *  scheduled, and the token of the one that is still the group's. */
    private data class Retry(val signature: String, val made: Int, val token: Long)
    private val retries = mutableMapOf<String, Retry>()
    private var nextToken = 0L

    /** A timed retry: ask again after [seconds], if [due] still says so with [token]. */
    data class RetryTimer(val seconds: Long, val token: Long)

    /** True when [group] should be asked now; it is then in flight until
     *  [finished]. A confirmed signature that arrives while it is in flight is
     *  kept for [finished]. */
    fun shouldAsk(group: String, signature: String, confirmed: Boolean): Boolean {
        if (!confirmed) return false
        latest[group] = signature
        if (group in inFlight) {
            pending[group] = signature
            return false
        }
        if (!GroupRoles.needsAsking(kept[group], signature)) return false
        inFlight[group] = signature
        return true
    }

    /** A refresh for [group] at [signature] ended; [ok] keeps the signature.
     *  Nothing happens unless the group was in flight for that signature (an
     *  answer after [clear], or after the group left, counts for nothing).
     *  Returns the signature that arrived meanwhile when it differs from
     *  [signature] (the caller asks again with it, through [shouldAsk]), else
     *  null. */
    fun finished(group: String, signature: String, ok: Boolean): String? {
        if (inFlight[group] != signature) return null
        inFlight -= group
        if (ok) {
            kept[group] = signature
            // Decision 38: a successful ask cancels the group's timer.
            retries -= group
        }
        val next = pending.remove(group)?.takeIf { it != signature }
        // Follow-up A: only this answer may set a timer, and only when nothing is asked next.
        if (!ok && next == null) failed[group] = signature
        return next
    }

    /** D-227 follow-up A (decision 35(a)): the failed answer of [group] at
     *  [signature] arrived: the timer to set ([retryAfter]), or null unless
     *  [finished] counted it (the group was in flight for that signature and
     *  nothing is asked next). A late answer, after [clear] or after the group
     *  left, sets none; one answer sets one timer at most. */
    fun failed(group: String, signature: String): RetryTimer? {
        if (failed[group] != signature) return null
        failed -= group
        return retryAfter(group, signature)
    }

    /** Decision 38: an ask of [group] at [signature] failed and nothing is
     *  asked next: the timer to set, or null once this signature had its three
     *  (the next snapshot or the next foreground asks then). A timer set
     *  earlier for the group is no longer [due]. The listener reaches it only
     *  through [failed]. */
    fun retryAfter(group: String, signature: String): RetryTimer? {
        val made = retries[group]?.takeIf { it.signature == signature }?.made ?: 0
        val seconds = retryDelay(made) ?: return null
        nextToken += 1
        retries[group] = Retry(signature, made + 1, nextToken)
        return RetryTimer(seconds, nextToken)
    }

    /** Decision 38: the timer with [token] fired: true when it is still the
     *  group's own (not cancelled by [clear], [keep], a successful ask or a
     *  later timer) and its signature is still the group's latest confirmed
     *  one (follow-up B: a newer one is asked for its own sake). The caller
     *  then asks through [shouldAsk]. */
    fun due(group: String, token: Long): Boolean {
        val retry = retries[group] ?: return false
        return retry.token == token && latest[group] == retry.signature
    }

    /** Forgets every group not in [only] (it left this account's list). */
    fun keep(only: Set<String>) {
        kept.keys.retainAll(only)
        inFlight.keys.retainAll(only)
        pending.keys.retainAll(only)
        retries.keys.retainAll(only)
        latest.keys.retainAll(only)
        failed.keys.retainAll(only)
    }

    fun clear() {
        kept.clear()
        inFlight.clear()
        pending.clear()
        retries.clear()
        latest.clear()
        failed.clear()
    }

    companion object {
        /** Decision 38: the seconds before a failed ask is asked again, by the
         *  number of timed retries already made for its signature: 15, 60,
         *  300, then none. */
        fun retryDelay(attempt: Int): Long? = when (attempt) {
            0 -> 15
            1 -> 60
            2 -> 300
            else -> null
        }

        /** Design 8: a refresh counts only when the call answered and either
         *  the server is older than D-227 (no places) or it named your place:
         *  an empty `places.you` means the group's create op is not up yet. */
        fun ok(answer: RoleRules.Roles?): Boolean {
            if (answer == null) return false
            val places = answer.places ?: return true
            return places.you.isNotEmpty()
        }
    }
}

// ---- The cloud's roles, cached ----

/**
 * Each group's owner and admins as the cloud last reported them (the
 * `groupRoles` callable), kept in the vault (`cloud/roles/{g}`) so a phone
 * offline still knows what it may offer.
 */
object GroupRoles {
    /** D-191's rule: Compose state made on the main thread before any frame. */
    fun createState() = Unit

    /** Screens read it; only this object sets it. */
    var roles by mutableStateOf<Map<String, RoleRules.Roles>>(emptyMap())
        private set

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** The test seam: sets or clears one group's roles. */
    fun setForTest(groupId: String, value: RoleRules.Roles?) {
        MainThread.run { roles = if (value == null) roles - groupId else roles + (groupId to value) }
    }

    /** The cloud is asked once per launch for every group (first sight, cached
     *  or not: the cache may be stale), and again whenever the group's owner
     *  or admins change in its document. */
    fun needsAsking(seen: String?, now: String): Boolean = seen != now

    fun key(groupId: String): String = "cloud/roles/$groupId"

    /** D-227 (Design 7): each group's cached `places.you`, for
     *  [ProfileShare.places]; a group whose cached roles have no places is left
     *  out. iOS's twin is `GroupRoles.yourPlaces`. */
    fun yourPlaces(store: LedgerStore, groupIds: List<String>): Map<String, List<String>> =
        groupIds.mapNotNull { g -> load(store, g)?.places?.you?.let { g to it } }.toMap()

    /** What the vault last held for a group (and keeps in [roles]). */
    fun load(store: LedgerStore, groupId: String): RoleRules.Roles? {
        roles[groupId]?.let { return it }
        val data = runCatching { store.rawKvGet(key(groupId)) }.getOrNull() ?: return null
        val decoded = decode(data.decodeToString()) ?: return null
        // Only if nothing newer arrived while this was queued.
        MainThread.run { if (groupId !in roles) roles = roles + (groupId to decoded) }
        return decoded
    }

    internal suspend fun keep(store: LedgerStore, groupId: String, value: RoleRules.Roles) {
        MainThread.await { roles = roles + (groupId to value) }
        store.rawKvSet(key(groupId), encode(value).toByteArray(Charsets.UTF_8))
    }

    /** The vault's form of a group's list. */
    fun encode(value: RoleRules.Roles): String = json.encodeToString(RoleRules.Roles.serializer(), value)

    /** A cached list, or null when it is not one. D-227: a list cached by
     *  0.4.0 has no `places`, and one this build cannot read (a later shape)
     *  keeps its owner and admins without places: today's rule then applies. */
    fun decode(text: String): RoleRules.Roles? {
        runCatching { json.decodeFromString(RoleRules.Roles.serializer(), text) }.getOrNull()?.let { return it }
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        if ("places" !in obj) return null
        return runCatching { json.decodeFromJsonElement(RoleRules.Roles.serializer(), JsonObject(obj - "places")) }.getOrNull()
    }

    /** A list of 32-hex ids, or null when any entry is not one. */
    private fun ids(element: kotlinx.serialization.json.JsonElement?): List<String>? {
        val list = element as? JsonArray ?: return if (element == null) emptyList() else null
        val ids = list.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter(CloudRecords::isHex32)
        return if (ids.size == list.size) ids else null
    }

    /** A result as the cloud writes it, or null. D-227: `places` is read when
     *  present (a server before D-227 sends none) and refuses the whole answer
     *  when it is not an object of 32-hex lists. */
    fun parse(result: JsonObject): RoleRules.Roles? {
        val owner = (result["owner"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (owner != null && !CloudRecords.isHex32(owner)) return null
        val admins = (result["admins"] as? JsonArray) ?: JsonArray(emptyList())
        val ids = admins.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter(CloudRecords::isHex32)
        if (ids.size != admins.size) return null
        val raw = result["places"]
        val places = if (raw == null || raw is kotlinx.serialization.json.JsonNull) {
            null
        } else {
            val obj = raw as? JsonObject ?: return null
            RoleRules.Places(
                owner = ids(obj["owner"]) ?: return null,
                admins = ids(obj["admins"]) ?: return null,
                you = ids(obj["you"]) ?: return null,
            )
        }
        return RoleRules.Roles(owner, ids, places)
    }

    /** Asks the cloud who owns the group and who administers it. Offline or
     *  refused leaves what is cached, and says so (false): a notice waiting on
     *  this stays for the next time. */
    suspend fun refresh(store: LedgerStore, groupId: String): Boolean = refreshed(store, groupId) != null

    /** [refresh], with the answer it kept (null when none was): the groups
     *  listener judges it with [RoleAsking.ok]. */
    suspend fun refreshed(store: LedgerStore, groupId: String): RoleRules.Roles? {
        load(store, groupId)
        if (Account.uid() == null) return null
        val fresh = try {
            parse(CloudFunctions.callJson("groupRoles", buildJsonObject { put("groupId", groupId) }))
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            keep(store, groupId, fresh)
            fresh
        } catch (_: Exception) {
            null
        }
    }

    /** The `setAdmin` callable's data: the place ([member], the group's id
     *  for the person) and the account's participant id ([you]); the Function
     *  resolves the place and checks they are one (D-210 final A2). */
    fun setAdminData(groupId: String, member: String, you: String, on: Boolean, byName: String, groupName: String): JsonObject =
        buildJsonObject {
            put("groupId", groupId)
            put("member", member)
            put("you", you)
            put("on", on)
            put("byName", RoleRules.wireName(byName))
            put("groupName", RoleRules.wireName(groupName))
        }

    /** The `makeOwner` callable's data (as [setAdminData], without `on`). */
    fun makeOwnerData(groupId: String, member: String, you: String, byName: String, groupName: String): JsonObject =
        buildJsonObject {
            put("groupId", groupId)
            put("member", member)
            put("you", you)
            put("byName", RoleRules.wireName(byName))
            put("groupName", RoleRules.wireName(groupName))
        }

    /** Makes the person at [member] (account [you]) an admin, or takes it away (`on = false`). */
    suspend fun setAdmin(store: LedgerStore, groupId: String, member: String, you: String, on: Boolean, byName: String, groupName: String) {
        val result = CloudFunctions.callJson("setAdmin", setAdminData(groupId, member, you, on, byName, groupName))
        val admins = (result["admins"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val known = load(store, groupId)
        if (known != null) {
            val legacy = if (admins != null) known.copy(admins = admins.filter { it != known.owner }) else known
            // D-227 follow-up G: by place too, so the open sheet reads the new role before the refresh answers.
            keep(store, groupId, RoleRules.confirmed(legacy, member, RoleRules.Change.Admin(on)))
        }
        refresh(store, groupId)
    }

    /** Hands the group to the person at [member] (account [you]); the old owner becomes an admin. */
    suspend fun makeOwner(store: LedgerStore, groupId: String, member: String, you: String, byName: String, groupName: String) {
        CloudFunctions.callJson("makeOwner", makeOwnerData(groupId, member, you, byName, groupName))
        // D-227 follow-up G: the new owner at once, by place.
        load(store, groupId)?.let { keep(store, groupId, RoleRules.confirmed(it, member, RoleRules.Change.Owner)) }
        refresh(store, groupId)
    }

    /** The group is gone from this phone (or was deleted): its cached list too. */
    fun forget(groupId: String) {
        MainThread.run { roles = roles - groupId }
    }
}

// ---- Notices ----

/**
 * What happened in this account's groups (users/{uid}/notices): a removal
 * request, a request decided, a removal, a deletion, a role. Written by the
 * Functions; the account reads them and dismisses them. Modelled on
 * [IncomingInvites]. The sync layer acts on `REMOVED`, `DELETED`, `ROLE` and
 * `DECLINED` ([CloudSync.handle]); `REQUEST` stays as the card until the
 * Function deletes it.
 */
object Notices {
    enum class Kind(val wire: String) {
        REQUEST("request"), DECLINED("declined"), REMOVED("removed"), DELETED("deleted"), ROLE("role");

        /** Only a removal or a deletion ends a group's syncing on this phone;
         *  a role or a decision changes nothing about what it receives. */
        val endsSync: Boolean get() = this == REMOVED || this == DELETED

        /** D-210 final B3: a removal or a deletion stays in the cloud, so every
         *  phone of the account finds it; each phone keeps its own list of the
         *  ones it has handled ([handled]). */
        val keptInCloud: Boolean get() = this == REMOVED || this == DELETED

        companion object {
            fun of(wire: String?): Kind? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class Item(
        val id: String,
        val kind: Kind,
        val groupId: String,
        val groupName: String,
        val byName: String,
        /** D-252: the account that did it (a deletion's notice names its deleter): the same account as this phone's
         *  reads "You deleted". Absent on notices written before D-252 and on every other kind. */
        val byUid: String? = null,
        val memberId: String? = null,
        val memberName: String? = null,
        val role: String? = null,
        /** Epoch milliseconds; 0 when the document has none. */
        val atMs: Long = 0L,
        val removedAtMs: Long? = null,
        /** The server's own stamp of the removal, kept whole: a catch-up query
         *  bounded by a rounded time could miss the last record. */
        val removedStamp: Timestamp? = null,
        /** The notice's own server stamp, kept whole (the bound when the
         *  removal's own is missing and the notice is not past it). */
        val atStamp: Timestamp? = null,
    ) {
        /** The person named asked for it themself: the asker's name is the
         *  person's (the notice carries names, not accounts). */
        val askedItself: Boolean
            get() {
                val name = memberName ?: return false
                val asker = byName.trim()
                return kind == Kind.REQUEST && asker.isNotEmpty() && asker == name.trim()
            }
    }

    // ---- D-210 final B3 and B6: what this phone has handled, and a removal's bound ----

    const val HANDLED_KEY = "cloud/notices-handled"

    /** The removals and deletions this phone has acted on (ids, this vault's). */
    /** Unreadable reads as none: handling one again is safe (the catch-up and
     *  the mark are idempotent). */
    fun handled(store: LedgerStore): Set<String> =
        runCatching { store.rawKvGet(HANDLED_KEY)?.decodeToString() }.getOrNull()
            ?.split("\n")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun markHandled(store: LedgerStore, id: String) {
        val ids = handled(store)
        if (id in ids) return
        store.rawKvSet(HANDLED_KEY, (ids + id).sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    /** The notices to act on: never a request (the card), never a removal or
     *  a deletion this phone has already handled. */
    fun toHandle(items: List<Item>, handled: Set<String>): List<Item> =
        items.filter { it.kind != Kind.REQUEST && !(it.kind.keptInCloud && it.id in handled) }

    /** How the group reads from the server for a removal: there, missing, or refused. */
    sealed interface GroupRead {
        data class Found(val data: Map<String, Any?>) : GroupRead
        data object Missing : GroupRead
        data object Refused : GroupRead
    }

    /** What a failed read of the group means for a removal (D-210 M1). Only
     *  permission-denied and not-found are the server's own answer, "not for
     *  you" or "not there": [GroupRead.Refused], and the removal goes on as
     *  gone. Anything else (unavailable, deadline, cancelled, aborted,
     *  internal, resource-exhausted, unauthenticated, ...) says nothing about
     *  the group: null, and the notice waits unhandled for another try. The code is
     *  passed by name (`FirebaseFirestoreException.Code.name`): the enum cannot
     *  load in a plain JVM test. */
    fun groupRead(codeName: String?): GroupRead? = when (codeName) {
        "PERMISSION_DENIED", "NOT_FOUND" -> GroupRead.Refused
        else -> null
    }

    /** Where a removal's catch-up ends. */
    sealed interface RemovalBound {
        /** Records up to this server time (never past `former[uid].at`: the rules refuse that). */
        data class UpTo(val stamp: Timestamp) : RemovalBound
        /** The account is a member of the group again: the notice is old news. */
        data object MemberAgain : RemovalBound
        /** The group cannot be read (deleted, or refused): nothing to catch up. */
        data object Gone : RemovalBound
    }

    /** D-210 final B6. The group as the server has it decides: an account that
     *  is a member again (its `former` entry cleared by a new join) has nothing
     *  to catch up and stays writable; otherwise the records are read up to the
     *  removal's own stamp, or, when the notice has none, up to its `at` only
     *  if that is not past `former[uid].at` (the rules refuse a read past it),
     *  else up to `former[uid].at` itself. A group that is gone, or refuses,
     *  has nothing more to give: the copy is what is here. */
    fun removalBound(item: Item, uid: String, group: GroupRead): RemovalBound {
        if (group !is GroupRead.Found) return RemovalBound.Gone
        val former = (group.data["former"] as? Map<*, *>)?.get(uid) as? Map<*, *>
        val formerAt = former?.get("at") as? Timestamp
        val members = (group.data["memberUids"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        if (former == null && uid in members) return RemovalBound.MemberAgain
        item.removedStamp?.let { return RemovalBound.UpTo(it) }
        if (formerAt == null) return RemovalBound.Gone
        val at = item.atStamp
        return if (at != null && at <= formerAt) RemovalBound.UpTo(at) else RemovalBound.UpTo(formerAt)
    }

    /** D-252: the mark a deletion's notice leaves, or null when the copy already says it. A copy already marked
     *  deleted keeps its mark (the deleting phone's own, or an earlier notice's: a second phone of the deleting account
     *  handles the same notice once, and the deleting phone ignores its own); a vanished, removed, lost-place or
     *  unmarked group becomes a deleted copy. The notice's [Item.byUid] against this account's uid says whose delete it
     *  was: "You deleted". iOS: `Notices.deletedMark`. */
    fun deletedMark(item: Item, uid: String?, existing: ReadOnlyDTO?): ReadOnlyDTO? {
        if (existing?.kind == "deleted") return null
        val yours = !uid.isNullOrEmpty() && item.byUid == uid
        return ReadOnlyDTO("deleted", item.byName, item.atMs, byYou = if (yours) true else null)
    }

    /** D-191's rule: Compose state made on the main thread before any frame. */
    fun createState() = Unit

    var items by mutableStateOf<List<Item>>(emptyList())
        private set
    private var listener: ListenerRegistration? = null
    @Volatile private var uid: String? = null

    private val docId = Regex("^[A-Za-z0-9_-]{1,200}$")

    fun isDocId(id: String): Boolean = docId.matches(id)

    private fun millis(any: Any?): Long? = when (any) {
        is Timestamp -> CloudRecords.millis(any)
        is java.util.Date -> any.time
        else -> null
    }

    /** A document the way the Functions write it, or null: a kind it does not
     *  know, an id that is not an id, a name that is not text, all refused. */
    fun item(id: String, data: Map<String, Any?>): Item? {
        if (!isDocId(id)) return null
        val kind = Kind.of(data["kind"] as? String) ?: return null
        val groupId = (data["groupId"] as? String)?.takeIf { CloudRecords.isHex32(it) } ?: return null
        val groupName = data["groupName"] as? String ?: return null
        val byName = data["byName"] as? String ?: return null
        var memberId: String? = null
        if (data.containsKey("memberId")) {
            memberId = (data["memberId"] as? String)?.takeIf { CloudRecords.isHex32(it) } ?: return null
        }
        var memberName: String? = null
        if (data.containsKey("memberName")) {
            memberName = data["memberName"] as? String ?: return null
        }
        var byUid: String? = null
        if (data.containsKey("byUid")) {
            byUid = (data["byUid"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        }
        var role: String? = null
        if (data.containsKey("role")) {
            role = (data["role"] as? String)?.takeIf { it == "admin" || it == "owner" } ?: return null
        }
        return Item(
            id = id, kind = kind, groupId = groupId, groupName = groupName, byName = byName, byUid = byUid,
            memberId = memberId, memberName = memberName, role = role,
            atMs = millis(data["at"]) ?: 0L,
            removedAtMs = millis(data["removedAt"]),
            removedStamp = data["removedAt"] as? Timestamp,
            atStamp = data["at"] as? Timestamp,
        )
    }

    /** The requests open in a group, newest first. */
    fun requests(groupId: String): List<Item> =
        items.filter { it.kind == Kind.REQUEST && it.groupId == groupId }.sortedByDescending { it.atMs }

    @Synchronized
    fun listen(uid: String) {
        if (listener != null) return
        this.uid = uid
        listener = Cloud.db().collection("users").document(uid).collection("notices")
            .addSnapshotListener { snapshot, _ ->
                val docs = snapshot?.documents ?: return@addSnapshotListener
                val fresh = docs.mapNotNull { item(it.id, it.data ?: emptyMap()) }
                MainThread.run { items = fresh }
                CloudSync.handle(fresh)
            }
    }

    @Synchronized
    fun stop() {
        listener?.remove()
        listener = null
        uid = null
        MainThread.run { items = emptyList() }
    }

    fun dismiss(item: Item) {
        val owner = uid ?: return
        MainThread.run { items = items.filterNot { it.id == item.id } }
        Cloud.db().collection("users").document(owner).collection("notices").document(item.id).delete()
    }
}
