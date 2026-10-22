package com.vardrlabs.skipti

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import uniffi.skipti_core.ClaimDeviceIn
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ffiQrEncode
import java.net.HttpURLConnection
import java.net.URL

/**
 * D-195 (the Firebase plan's unit 5): inviting a NAMED person into a group and
 * joining one, through SKIPTI's Cloud Functions (firebase/functions); the twin
 * of iOS's Invites.swift (same copy, same order). The founder's decisions
 * (2026-09-24, kept): invite by QR when together or a link when apart; the
 * person is in the group's ledger whether or not they ever join, so joining
 * only links them. Inviting and joining need an account (GUARANTEES #5).
 */
object InviteCopy {
    const val SECTION = "Invite"
    const val SECTION_FOOTER =
        "People you invite see this group and add to it. They are in its ledger whether or not they join."
    fun invite(name: String) = "Invite $name"
    const val MAKING = "Making an invite…"
    fun how(name: String) = "$name scans this with their phone's camera, or opens the link you send."
    fun joinsAs(name: String, group: String) =
        "$name joins $group as $name. They are already in its ledger; joining lets them see it and add to it."
    const val ONCE = "The link works once, for 7 days."
    const val SEND = "Send the link"
    const val COPY = "Copy link"
    const val COPIED = "Link copied"
    const val TRY_AGAIN = "Try again"

    const val JOIN_TITLE = "Join a group"
    const val PASTE_PROMPT = "Paste the invite link"
    const val NOT_A_LINK = "That is not a SKIPTI invite link."
    const val CONTINUE = "Continue"
    const val OPENING = "Opening the invite…"
    fun invited(inviter: String?, group: String) =
        inviter?.let { "$it invited you to $group." } ?: "You are invited to $group."
    fun youJoinAs(name: String) = "You join as $name."
    const val JOIN_DETAIL = "You will see this group's expenses and add to them."
    const val JOIN = "Join"
    const val JOINING = "Joining…"
    const val NOT_NOW = "Not now"
    fun joined(group: String) = "You are in $group."
    const val DONE = "Done"
    /** D-172: Join a group opened without an account. */
    const val JOIN_NEEDS_ACCOUNT_TITLE = "Joining needs an account"
    const val JOIN_NEEDS_ACCOUNT_BODY =
        "A shared group reaches you through sync, so joining one means signing in first. " +
            "Everything else in SKIPTI works without an account."
    const val SIGN_IN = "Sign in"
    const val NEEDS_ACCOUNT =
        "Joining someone's group needs an account, because the group is shared through sync. Sign in from Settings, then open the link again."
    const val USED = "This invite was already used or has expired. Ask for a new one."
    /** D-218: the cloud refuses an invite into a place someone has already joined as
     *  (status `already-exists`). The same words acceptInvite uses for this case. */
    const val HELD = "Someone has already joined as this person."
    /** D-229 decision 52: acceptInvite refuses an account that already holds a place in the group
     *  (`already-exists` with details `{member: true}`). The same words the Function uses. */
    const val ALREADY_MEMBER = "You're already in this group."
    const val OFFLINE = "Could not reach SKIPTI's cloud. Check your connection and try again."
    const val NOT_MEMBER = "This group is not in SKIPTI's cloud with your account, so you cannot invite to it."

    fun failure(e: Throwable): String = when (e) {
        // D-254: a refusal for approval says so, never "not a member".
        is CloudFunctions.Failure.AwaitingApproval -> ApprovalCopy.CALL_WAITING
        is CloudFunctions.Failure.NotApproved -> ApprovalCopy.CALL_DENIED
        is CloudFunctions.Failure.SignedOut -> NEEDS_ACCOUNT
        is CloudFunctions.Failure.Refused -> when (e.status) {
            "FAILED_PRECONDITION", "NOT_FOUND" -> USED
            "ALREADY_EXISTS" -> if (e.member) ALREADY_MEMBER else HELD
            "PERMISSION_DENIED" -> NOT_MEMBER
            "INVALID_ARGUMENT" -> NOT_A_LINK
            "UNAUTHENTICATED" -> NEEDS_ACCOUNT
            else -> OFFLINE
        }
        else -> OFFLINE
    }

    /** D-218: whether the failed invite is worth offering "Try again" for. A held place stays
     *  held until its person is removed, so the same call would say the same thing. */
    fun canRetry(e: Throwable): Boolean = !(e is CloudFunctions.Failure.Refused && e.status == "ALREADY_EXISTS") &&
        // D-254: the same call says the same until the account is approved.
        ApprovalCopy.failure(e) == null
}

/** The invite link: SKIPTI's join page with the token in the fragment, which
 *  a browser never sends to any server. Only the Functions turn a token into
 *  a group, and they keep only its hash. */
object InviteLink {
    const val PREFIX = "https://skipti.vardrlabs.com/join#"
    private val token = Regex("^[A-Za-z0-9_-]{43}$")

    fun make(token: String) = PREFIX + token

    /** The token in a pasted or opened link, or null. */
    fun token(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        return trimmed.removePrefix(PREFIX).takeIf { token.matches(it) }
    }
}

/** Calls a Cloud Function the way Firebase's callable protocol defines it
 *  (POST {"data": …} with the account's ID token; {"result": …} or
 *  {"error": {"status": …}} back), without adding the Functions SDK. */
object CloudFunctions {
    sealed class Failure(message: String) : Exception(message) {
        class SignedOut : Failure("signed out")
        /** The Function said no: its status ("PERMISSION_DENIED", …). D-229 decision 52:
         *  [member] is the refusal's `details.member == true` (acceptInvite: you are already
         *  in the group); false for every other refusal. */
        class Refused(val status: String, val member: Boolean = false) : Failure(status)
        class Offline : Failure("offline")
        /** D-254: the account is not approved yet (refused on this phone, or the server's `details.approval` pending
         *  or none). Never "not a member". */
        class AwaitingApproval : Failure("awaiting approval")
        /** D-254: the account was not approved (`details.approval` denied). */
        class NotApproved : Failure("not approved")
    }

    fun url(name: String): String = if (Cloud.emulator) {
        "http://localhost:5001/${FirebaseConfig.PROJECT_ID}/us-central1/$name"
    } else {
        "https://us-central1-${FirebaseConfig.PROJECT_ID}.cloudfunctions.net/$name"
    }

    /** The callable protocol's reply: the result, or the Function's refusal. */
    fun decode(body: String): JsonObject {
        val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: throw Failure.Offline()
        (json["error"] as? JsonObject)?.let { error ->
            // D-254: the approval gate's refusal (permission-denied with `details.approval`) is its own failure.
            ((error["details"] as? JsonObject)?.get("approval") as? JsonPrimitive)?.takeIf { it.isString }?.let { approval ->
                throw if (ApprovalState.server(approval.content) == ApprovalState.DENIED) Failure.NotApproved() else Failure.AwaitingApproval()
            }
            val member = ((error["details"] as? JsonObject)?.get("member") as? JsonPrimitive)
                ?.takeIf { !it.isString }?.booleanOrNull == true
            throw Failure.Refused(error.text("status") ?: "UNKNOWN", member)
        }
        return json["result"] as? JsonObject ?: throw Failure.Offline()
    }

    /** A string field of a reply, or null. */
    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    /** D-254: [callJson]'s gate for [uid] (and the invite paths' before they put a group in the cloud): the vault's
     *  copy of the account's approval. Fix wave I2: nothing heard yet (unknown) asks the server once, bounded
     *  ([Approval.RESOLVE_TIMEOUT_MS]), before refusing: approved goes, pending and denied have their words, and no
     *  answer is offline. A vault that cannot be read asks nothing and is offline (closed). An exempt call asks nothing.
     *  [ask] is the server's answer (a fake in tests). */
    suspend fun gate(
        name: String,
        uid: String,
        store: LedgerStore? = runCatching { VaultGate.appContextRef }.getOrNull()?.let { LedgerStore(CloudSync.vaultPathOf(it)) },
        ask: suspend () -> ApprovalState? = { store?.let { Approval.asking(uid, it).await() } },
    ): Failure? = Approval.callRefusal(name, store?.let { Approval.cached(it, uid) } ?: ApprovalState.UNKNOWN, Approval.RESOLVE_TIMEOUT_MS, ask)

    /** Blocking: call off the main thread. */
    suspend fun call(name: String, data: Map<String, String>): JsonObject =
        callJson(name, JsonObject(data.mapValues { JsonPrimitive(it.value) }))

    /** D-197: any JSON data (lists, booleans, null). */
    suspend fun callJson(name: String, data: JsonObject): JsonObject {
        val user = FirebaseAuth.getInstance().currentUser ?: throw Failure.SignedOut()
        // D-254: a call the server would refuse for approval is refused here first, with no network call (every call
        // but the exempt ones: leaving, and asking for approval).
        gate(name, user.uid)?.let { throw it }
        val token = try {
            user.getIdToken(false).awaitTask().token ?: throw Failure.Offline()
        } catch (e: Failure) {
            throw e
        } catch (_: Exception) {
            throw Failure.Offline()
        }
        val body = JsonObject(mapOf("data" to data)).toString()
        val reply = try {
            withContext(Dispatchers.IO) {
                val connection = URL(url(name)).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.outputStream.use { it.write(body.toByteArray()) }
                val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            }
        } catch (_: Exception) {
            throw Failure.Offline()
        }
        return try {
            decode(reply)
        } catch (e: Failure) {
            // D-254: the server refused for approval (pending, denied, or `none`: this account never asked): ask now, so
            // the request exists and this phone hears the state the server holds.
            if (ApprovalCopy.failure(e) != null && name !in Approval.EXEMPT) Approval.askSoon(user.uid)
            throw e
        }
    }
}

/** The three steps, on top of the Functions. */
object Invites {
    /** D-230: [groupId] and [memberId] name the invite's place (null from a
     *  Function older than D-230: the join then sends no held place and
     *  [PlaceShare] marks it at the next push). */
    data class Offer(
        val groupName: String, val memberName: String, val inviterName: String?,
        val groupId: String? = null, val memberId: String? = null,
    )

    /** A link that invites [member] into the group. The group is put in the
     *  cloud first if this phone has not yet (one made before signing in). */
    suspend fun create(store: LedgerStore, group: NamedGroupWire, member: MemberWire): String {
        val uid = Account.uid() ?: throw CloudFunctions.Failure.SignedOut()
        // D-254: before the group is put in the cloud (a write the rules refuse an account not approved); fix wave I2:
        // through the call's own gate, so nothing heard yet asks the server first.
        CloudFunctions.gate("createInvite", uid, store)?.let { throw it }
        when (CloudSync.ensureGroup(group.group.id, group.group.name, uid)) {
            CloudSync.GroupState.MEMBER -> Unit
            CloudSync.GroupState.NOT_MEMBER -> throw CloudFunctions.Failure.Refused("PERMISSION_DENIED")
            CloudSync.GroupState.OFFLINE -> throw CloudFunctions.Failure.Offline()
        }
        CloudSync.push()
        val data = mutableMapOf(
            "groupId" to group.group.id, "memberId" to member.id, "memberName" to member.name,
            "groupName" to group.group.name,
        )
        withContext(Dispatchers.IO) { store.loadSelf()?.name }?.let { data["inviterName"] = it }
        val result = CloudFunctions.call("createInvite", data)
        return InviteLink.make(with(CloudFunctions) { result.text("token") } ?: throw CloudFunctions.Failure.Offline())
    }

    suspend fun peek(link: String): Offer {
        val token = InviteLink.token(link) ?: throw CloudFunctions.Failure.Refused("INVALID_ARGUMENT")
        return offer(CloudFunctions.call("peekInvite", mapOf("token" to token)))
    }

    /** The offer a `peekInvite` answer makes (D-230: with the invite's place). */
    fun offer(result: JsonObject): Offer = with(CloudFunctions) {
        Offer(
            result.text("groupName") ?: throw CloudFunctions.Failure.Offline(),
            result.text("memberName") ?: throw CloudFunctions.Failure.Offline(),
            result.text("inviterName"),
            result.text("groupId"),
            result.text("memberId"),
        )
    }

    /** acceptInvite takes 1 to 11 held places (A5). */
    const val JOIN_MAX = 11

    /** D-230: the held places the join sends for the server to mark, stamped
     *  by this phone in this order, NOT put into the vault (they come back
     *  marked through the group's log): A1, every place of [group] a claim of
     *  this account holds, with that claim's id ([PlaceShare.claimed]; the
     *  server marks the ones it may and skips the rest), at most ten, in
     *  member order (the rest reach the server through [PlaceShare] at the
     *  next push); then, last, this account's "you" (the projection's: the
     *  account's earliest id, never this phone's own when they differ) on the
     *  invite's [member]. No "you" yet (a brand-new install's first join):
     *  the "you" is minted here ([LedgerStore.ensureSelf], which [accept] runs
     *  right after the call anyway), so the first join carries its mark too.
     *  An old pair that is exactly the new place is sent once (the filter); a
     *  claim on the invite's member under another of this account's ids is
     *  its own record (A8: at least one for the invite's place, each checked
     *  alone). Built one place at a time ([joinBuilt]). */
    fun heldRecords(
        store: LedgerStore, group: String, member: String, nowMs: Long = System.currentTimeMillis(),
    ): List<uniffi.skipti_core.RecordWire> {
        val p = store.projectGroups()
        val you = p.you?.id ?: store.ensureSelf().id
        val place = PlaceShare.Held(group, member, you)
        val old = p.groups.firstOrNull { it.group.id == group }?.let { PlaceShare.claimed(p, it, store.allRecords()) }.orEmpty()
            .filter { it != place }
            .take(JOIN_MAX - 1)
        return joinBuilt(store, old + place, nowMs)
    }

    /** The join's records, built one place at a time
     *  ([PlaceShare.recordsByGroup], as the place job does): an old pair whose
     *  record core cannot build is left out (the place job asks for it at the
     *  next push) and never takes the new place's record with it. The new
     *  place, [places]' last, is required: without its record, none (the join
     *  sends the token alone). */
    fun joinBuilt(
        store: LedgerStore, places: List<PlaceShare.Held>, nowMs: Long = System.currentTimeMillis(),
    ): List<uniffi.skipti_core.RecordWire> {
        val place = places.lastOrNull() ?: return emptyList()
        val built = PlaceShare.recordsByGroup(store, places, nowMs)
        return if (built.any { it.first == place }) built.map { it.second } else emptyList()
    }

    /** The held places a join sends: none without the offer's place (a
     *  Function older than D-230) or when they cannot be built (the join
     *  never waits on them). */
    fun joinRecords(offer: Offer?, store: LedgerStore, nowMs: Long = System.currentTimeMillis()): List<uniffi.skipti_core.RecordWire> {
        val g = offer?.groupId ?: return emptyList()
        val m = offer.memberId ?: return emptyList()
        return runCatching { heldRecords(store, g, m, nowMs) }.getOrNull().orEmpty()
    }

    /** The acceptInvite call's data: the token, and the held places when this
     *  phone could build them. */
    fun acceptData(token: String, records: List<uniffi.skipti_core.RecordWire>): JsonObject {
        val data = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("token" to JsonPrimitive(token))
        if (records.isNotEmpty()) data["records"] = Removal.payload(records)
        return JsonObject(data)
    }

    /** Joins: the Function adds this account to the group and, when the offer
     *  names the place, marks the held places this phone built (D-230); this
     *  phone then claims the named person in the group's own log (0.4.0
     *  readers), and syncs. Returns the group's id. */
    suspend fun accept(link: String, offer: Offer?, store: LedgerStore): String {
        val token = InviteLink.token(link) ?: throw CloudFunctions.Failure.Refused("INVALID_ARGUMENT")
        // Held places that cannot be built send no record: the join never waits on them.
        val records = withContext(Dispatchers.IO) { joinRecords(offer, store) }
        val result = CloudFunctions.callJson("acceptInvite", acceptData(token, records))
        val group = with(CloudFunctions) { result.text("groupId") } ?: throw CloudFunctions.Failure.Offline()
        val member = with(CloudFunctions) { result.text("memberId") } ?: throw CloudFunctions.Failure.Offline()
        withContext(Dispatchers.IO) {
            store.appendGroupOps(listOf(claim(group, member, store.ensureSelf().id, LedgerStore.Sync.deviceId(store))), Placing.NONE)
            runCatching { CloudRecords.saveGroupsUp(store, CloudRecords.groupsUp(store) + group) }
            // D-210: someone removed from this group and invited back writes to it again.
            runCatching { store.clearReadOnly(group) }
        }
        CloudSync.syncSwitched()
        // D-211: your own name and preview go to the place you claimed; until the
        // group's records bring that place, the group waits ([ProfileShare.arrived]
        // shares once when it does).
        ProfileShare.joined(group, store)
        return group
    }

    /** The claim that links this account's "you" to the named person. Core
     *  still asks for a key per device (the relay's agreement key, unused since
     *  ADR-0040): 32 zero bytes. */
    fun claim(group: String, member: String, you: String, device: String): GroupOpIn =
        GroupOpIn.Claim(group, member, you, listOf(ClaimDeviceIn(device, "0".repeat(64))))
}

/** Who can be invited from a group's People screen. */
object InviteFlow {
    /** Everyone but you who has not joined yet; nobody without an account. */
    fun invitable(store: LedgerStore, named: NamedGroupWire?, signedIn: Boolean): List<MemberWire> {
        if (named == null || !signedIn) return emptyList()
        val claimed = store.projectGroups().groups.firstOrNull { it.group.id == named.group.id }
            ?.claims?.map { it.memberId }?.toSet() ?: emptySet()
        return named.members.filter { it.id != named.selfId && it.id !in claimed }
    }
}

private suspend fun <T> io(block: suspend () -> T): Result<T> =
    withContext(Dispatchers.IO) { runCatching { block() } }

/** A QR code from core's encoder (D-159): dark modules on white with a
 *  four-module quiet zone, in light and dark mode alike. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val code = remember(text) { runCatching { ffiQrEncode(text) }.getOrNull() } ?: return
    val n = code.size.toInt()
    Canvas(
        modifier
            .aspectRatio(1f)
            .background(Color.White)
            .semantics { contentDescription = "QR code for the invite link" },
    ) {
        val cell = size.width / (n + 8)
        for (row in 0 until n) {
            for (col in 0 until n) {
                if (code.modules[row * n + col]) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset((col + 4) * cell, (row + 4) * cell),
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

/** Invite one named member (from the group's People screen). */
@Composable
fun InviteScreen(vaultPath: String, group: NamedGroupWire, member: MemberWire, onBack: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    var link by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    // D-196: the moment pushes start to matter: someone may join.
    val askPush = rememberPushAsk()
    // D-197: or find them on SKIPTI and invite them in the app.
    var finding by remember { mutableStateOf(false) }
    if (finding) {
        // D-217 (oddity 5): system Back goes up one level, to the invite.
        BackHandler { finding = false }
        FindPersonScreen(vaultPath, group, member, onBack = { finding = false })
        return
    }

    LaunchedEffect(attempt) {
        link = null
        failure = null
        copied = false
        io { Invites.create(LedgerStore(vaultPath), group, member) }.fold(
            onSuccess = {
                link = it
                askPush()
            },
            onFailure = {
                failure = InviteCopy.failure(it)
                retry = InviteCopy.canRetry(it)
            },
        )
    }

    PushedPage(InviteCopy.invite(member.name), dark, onBack, spacing = 12.dp, side = BarDecisions.PAGE_SIDE_WIDE.dp) {
        val ready = link
        when {
            ready != null -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    QrCode(ready, Modifier.widthIn(max = 280.dp).fillMaxWidth())
                }
                Text(InviteCopy.how(member.name), style = Type.body(dark))
                Text(InviteCopy.joinsAs(member.name, group.group.name), style = Type.bodyDim(dark))
                Text(InviteCopy.ONCE, style = Type.bodyDim(dark))
                PrimaryAction(InviteCopy.SEND, dark) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, ready)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
                SecondaryAction(if (copied) InviteCopy.COPIED else InviteCopy.COPY, dark) {
                    // D-164: marked sensitive (the link is a secret).
                    copySecret(context, ready)
                    copied = true
                }
                SecondaryAction(PeopleCopy.findOnSkipti(member.name), dark) { finding = true }
            }
            failure != null -> {
                ErrorLine(failure!!, dark)
                if (retry) SecondaryAction(InviteCopy.TRY_AGAIN, dark) { attempt++ }
            }
            else -> Busy(InviteCopy.MAKING, dark)
        }
    }
}

@Composable
private fun Busy(label: String, dark: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.heightIn(max = 24.dp))
        Text(label, style = Type.bodyDim(dark))
    }
}

/** Paste a link (the group list's "Join a group"). */
@Composable
fun JoinPasteScreen(
    vaultPath: String,
    onJoined: (String) -> Unit,
    onBack: () -> Unit,
    onOpenSignIn: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    var text by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf<String?>(null) }
    // D-172: null while reading; read each time the screen is shown, so a
    // return from sign-in shows the paste field.
    var hasAccount by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(vaultPath) {
        hasAccount = Account.isSignedIn()
    }
    link?.let {
        // D-217 (oddity 5): system Back goes up one level, to the paste page.
        BackHandler { link = null }
        JoinScreen(vaultPath, it, onJoined, onBack = { link = null }, asSheet = false)
        return
    }
    val trimmed = text.trim()
    val isLink = InviteLink.token(trimmed) != null
    if (hasAccount == false) {
        // Said only in answer to the person's own tap on Join (GUARANTEES
        // #5: no pitch, no benefit copy); sign-in is the secondary style.
        PushedPage(InviteCopy.JOIN_TITLE, dark, onBack, spacing = 12.dp, side = BarDecisions.PAGE_SIDE_WIDE.dp) {
            Text(InviteCopy.JOIN_NEEDS_ACCOUNT_TITLE, style = Type.body(dark))
            Text(InviteCopy.JOIN_NEEDS_ACCOUNT_BODY, style = Type.bodyDim(dark))
            SecondaryAction(InviteCopy.SIGN_IN, dark, onClick = onOpenSignIn)
        }
        return
    }
    PushedPage(InviteCopy.JOIN_TITLE, dark, onBack, spacing = 12.dp, side = BarDecisions.PAGE_SIDE_WIDE.dp) {
        // D-254: no link can be opened yet; the same words as an opened invite.
        ApprovalCopy.join(Approval.state, group = null)?.takeIf { hasAccount == true }?.let { (title, words) ->
            ApprovalJoinBlock(title, words, dark, onBack)
            return@PushedPage
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(InviteCopy.PASTE_PROMPT) },
            textStyle = Type.body(dark),
            shape = ThemeArt.controlShape(Appearance.theme, OutlinedTextFieldDefaults.shape),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = InviteCopy.PASTE_PROMPT },
        )
        if (trimmed.isNotEmpty() && !isLink) ErrorLine(InviteCopy.NOT_A_LINK, dark)
        PrimaryAction(InviteCopy.CONTINUE, dark, enabled = isLink) { link = trimmed }
    }
}

/** D-254: a call refused for approval shows the invite page's waiting (or not approved) words, with the group's name
 *  when the offer gave it; null for any other failure (its own words). */
internal object JoinFailure {
    fun waiting(error: Throwable, group: String?): Pair<String, String>? =
        ApprovalCopy.state(error)?.let { ApprovalCopy.join(it, group) }
}

/** D-254: the invite page while the account is not approved: the title, the words, one Done. iOS's twin is
 *  `ApprovalJoinBlock` in Invites.swift. */
@Composable
fun ApprovalJoinBlock(title: String, words: String, dark: Boolean, onDone: () -> Unit) {
    Text(title, style = Type.display(dark).copy(fontSize = 28.sp))
    Text(words, style = Type.bodyDim(dark))
    PrimaryAction(ApprovalCopy.DONE, dark, onClick = onDone)
}

/** D-217 final fix: once a join has started (and once it is done) Join has no Cancel and no back, as on iOS: Done
 *  (onJoined) is the way out, so the caller's dismissal and switch to Balance always run. */
internal object JoinBar {
    fun locked(joining: Boolean, joined: String?): Boolean = joining || joined != null
}

/** What an invite offers, and joining it. [asSheet]: iOS presents Join as a sheet from an invitation and from an
 *  opened link (a Cancel pill) and pushes it inside Join paste (a back). */
@Composable
fun JoinScreen(vaultPath: String, link: String, onJoined: (String) -> Unit, onBack: () -> Unit, asSheet: Boolean) {
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<Invites.Offer?>(null) }
    var joining by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    /** D-254: the account cannot join yet (waiting) or at all (not approved): the title and the words, one Done. */
    var waiting by remember { mutableStateOf<Pair<String, String>?>(null) }
    // D-196: once joined, this group's news can reach you.
    val askPush = rememberPushAsk()

    LaunchedEffect(link) {
        if (!Account.isSignedIn()) {
            failure = InviteCopy.NEEDS_ACCOUNT
            return@LaunchedEffect
        }
        // D-254: no peek while the account is not approved (the group's name is not known, so "this group").
        ApprovalCopy.join(Approval.state, group = null)?.let {
            waiting = it
            return@LaunchedEffect
        }
        // D-254: a refusal for approval shows the waiting page (it wins over the failure line).
        io { Invites.peek(link) }.fold(onSuccess = { offer = it }, onFailure = { failure = InviteCopy.failure(it); waiting = JoinFailure.waiting(it, group = null) })
    }

    // System Back once locked: Done when joined, nothing while joining (iOS disables the sheet's swipe there).
    BackHandler(enabled = JoinBar.locked(joining, joined)) { joined?.let(onJoined) }
    val exit = if (JoinBar.locked(joining, joined)) null else onBack
    val body: @Composable ColumnScope.() -> Unit = {
        val p = offer
        val done = joined
        val blocked = waiting
        when {
            blocked != null -> ApprovalJoinBlock(blocked.first, blocked.second, dark, onBack)
            failure != null -> {
                ErrorLine(failure!!, dark)
                SecondaryAction(InviteCopy.DONE, dark, onBack)
            }
            done != null -> {
                // D-224 (Design 7, 8): the big check above the heading; the words as they were.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DoneCheck(DonePresentation.SCREEN, dark) }
                Text(InviteCopy.joined(p?.groupName ?: ""), style = Type.display(dark).copy(fontSize = 28.sp))
                PrimaryAction(InviteCopy.DONE, dark) { onJoined(done) }
            }
            p != null -> {
                Text(InviteCopy.invited(p.inviterName, p.groupName), style = Type.display(dark).copy(fontSize = 28.sp))
                Text(InviteCopy.youJoinAs(p.memberName), style = Type.body(dark))
                Text(InviteCopy.JOIN_DETAIL, style = Type.bodyDim(dark))
                PrimaryAction(if (joining) InviteCopy.JOINING else InviteCopy.JOIN, dark, enabled = !joining) {
                    joining = true
                    scope.launch {
                        io {
                            val store = LedgerStore(vaultPath)
                            val group = Invites.accept(link, offer, store)
                            store.selectGroup(group)
                            group
                        }.fold(
                            onSuccess = {
                                joined = it
                                askPush()
                            },
                            // D-254: a refusal for approval shows the waiting page, with the group's name.
                            onFailure = { failure = InviteCopy.failure(it); waiting = JoinFailure.waiting(it, group = p.groupName) },
                        )
                        joining = false
                    }
                }
                SecondaryAction(InviteCopy.NOT_NOW, dark, onBack)
            }
            else -> Busy(InviteCopy.OPENING, dark)
        }
    }
    if (asSheet) SheetPage(InviteCopy.JOIN_TITLE, dark, onCancel = exit, spacing = 12.dp, side = BarDecisions.PAGE_SIDE_WIDE.dp, content = body)
    else PushedPage(InviteCopy.JOIN_TITLE, dark, exit, spacing = 12.dp, side = BarDecisions.PAGE_SIDE_WIDE.dp, content = body)
}
