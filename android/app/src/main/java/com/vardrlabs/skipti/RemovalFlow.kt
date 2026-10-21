package com.vardrlabs.skipti

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uniffi.skipti_core.BalanceLineOut
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.RecordWire
import uniffi.skipti_core.RedistributionInput
import uniffi.skipti_core.RedistributionTally
import uniffi.skipti_core.ffiBalanceLine
import uniffi.skipti_core.ffiBalancesCompute
import uniffi.skipti_core.ffiEncodeRedistribution
import uniffi.skipti_core.ffiMoneyInputDigits
import uniffi.skipti_core.ffiMoneyParseMinor
import uniffi.skipti_core.ffiRedistributeEqual
import uniffi.skipti_core.ffiRedistributionTally
import kotlin.math.abs

/**
 * D-210: taking a person out of a group: who sees what on the People screen,
 * the removal's records, the redistribution's arithmetic (core's) and the
 * screens that walk through it. iOS's twin is RemovalFlow.swift.
 */

// ---- The removal's records (pure, bar the vault's clock) ----

object Removal {
    /** The nonce session of a redistribution record ("RED\u0001"): a header
     *  field, never nonce material (the same rule as the other records'). */
    val redistributionSession: ByteArray get() = byteArrayOf(0x52, 0x45, 0x44, 0x01)

    /** What [member] is owed (positive) or owes (negative) in each currency,
     *  nonzero only: [lines] are core's balance lines framed on [member]
     *  (`ffiBalanceLine` with `viewer = member`). */
    @Suppress("UNUSED_PARAMETER")
    fun plan(lines: List<BalanceLineOut>, member: String): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        for (line in lines) if (line.viewerNetMinor != 0L) out[line.currencyCode] = line.viewerNetMinor
        return out
    }

    /** One settlement per nonzero entry of the member's lines: a negative
     *  entry is what the member owes that person (the member pays), a
     *  positive one what that person owes the member (they pay). The
     *  direction rule of `SettleUi.settlementFor`, framed on [member]. */
    fun settleFirst(lines: List<BalanceLineOut>, member: String, group: String): List<SettlementDTO> =
        lines.flatMap { line ->
            line.entries.filter { it.minorUnits != 0L }.map { entry ->
                val owes = entry.minorUnits < 0
                SettlementDTO(
                    from = if (owes) member else entry.id, to = if (owes) entry.id else member,
                    amountMinor = abs(entry.minorUnits), currencyCode = line.currencyCode, group = group,
                )
            }
        }

    /** Whether core can write every payment's amount (a payment is never
     *  recorded that the screen could not show). */
    fun canFormat(settlements: List<SettlementDTO>): Boolean =
        settlements.all { RolesMoney.format(it.amountMinor, it.currencyCode) != null }

    /** D-247 fix wave (review I2): every write path other than the hold lands the hold first. Settle first's payments as
     *  they are recorded: the held settle ([SettleHold]) lands first; when one did (and no payment of this Settle first
     *  is in yet, [started]), the payments are worked out again from the vault, so the debt it paid is never recorded
     *  twice. Otherwise the payments the screen showed ([shown]). On the main thread (the hold's). The twin of iOS's
     *  `Removal.settleFirstLanding`. */
    fun settleFirstLanding(
        store: LedgerStore, shown: List<SettlementDTO>, member: String, group: String, started: Boolean,
        hold: SettleHold = SettleHold.shared,
    ): List<SettlementDTO> {
        if (!hold.commit() || started) return shown
        return settleFirst(lines(store, group, member), member, group)
    }

    /** Core's balance lines for [member] in [group], from what this phone holds. */
    fun lines(store: LedgerStore, group: String, member: String): List<BalanceLineOut> = ffiBalanceLine(
        store.loadExpenses().filter { it.group == group }.map { it.toInput() },
        store.loadSettlements().filter { it.group == group }.map { it.toInput() },
        store.loadRedistributions().filter { it.group == group },
        store.removedIds(group), member, group,
    )

    /** Every participant's net per currency in the group (core's): old
     *  balances for the redistribution's rows. */
    fun nets(store: LedgerStore, group: String): Map<String, Map<String, Long>> =
        ffiBalancesCompute(
            store.loadExpenses().filter { it.group == group }.map { it.toInput() },
            store.loadSettlements().filter { it.group == group }.map { it.toInput() },
            store.loadRedistributions().filter { it.group == group },
        ).associate { line ->
            val byId = linkedMapOf<String, Long>()
            for (e in line.entries) byId.putIfAbsent(e.id, e.minorUnits)
            line.currencyCode to byId
        }

    /** The records of a removal: one type-6 record per redistribution, then
     *  the RemoveMember operation, each with a fresh stamp from this phone's
     *  clock (written before the record exists: a failed call wastes stamps,
     *  never reuses one). D-229: [placing] writes this account's ids as the
     *  place the group knows it by (a share you take of the balance). */
    fun records(
        store: LedgerStore, group: String, member: String, redistributions: List<RedistributionInput>,
        placing: Placing, nowMs: Long = System.currentTimeMillis(),
    ): List<RecordWire> {
        val device = LedgerStore.Sync.deviceId(store)
        // D-229: the removal is mapped (or refused: a group whose place is
        // lost) before any stamp is taken.
        val removal = placing.op(GroupOpIn.RemoveMember(group, member))
        val out = mutableListOf<RecordWire>()
        for (r in redistributions) {
            // D-229: built first: a redistribution core refuses wastes no stamp.
            val written = placing.redistribution(r)
            val hlc = LedgerStore.Sync.nextHlc(store, nowMs)
            out += RecordWire(
                1u, LedgerStore.REDISTRIBUTION_TYPE, group, 0u, hlc.millis, hlc.counter, device, device,
                redistributionSession, 0u, ffiEncodeRedistribution(written),
            )
        }
        out += LedgerStore.Sync.groupOpRecord(removal, LedgerStore.Sync.nextHlc(store, nowMs), device)
        return out
    }

    private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    /** The `removeMember` callable's `records`, in the Cloud contract's shape
     *  (nonce and blob in base64; the id is the record's HLC identity). */
    fun payload(records: List<RecordWire>): JsonArray = JsonArray(
        records.map { r ->
            buildJsonObject {
                put("id", CloudRecords.documentId(r))
                put("type", r.recordType.toString())
                put("hlc", buildJsonObject {
                    put("millis", r.hlcMillis.toLong())
                    put("counter", r.hlcCounter.toLong())
                    put("device", r.hlcDeviceId)
                })
                put("deviceId", r.authorDeviceId)
                put("payload", buildJsonObject {
                    put("version", r.version.toLong())
                    put("epoch", r.epoch.toLong())
                    put("flags", r.flags.toLong())
                    put("nonce", base64(r.nonceSession))
                    put("blob", base64(r.blob))
                })
            }
        },
    )

    /** The records into this phone's vault, as the Function wrote them to the
     *  cloud: its own listener never brings them back (a record this device
     *  authored is refused as `THIS_DEVICE`), so they are applied here, and
     *  [uploaded] marks them uploaded so `pushAll` never sends them again. */
    fun applyLocally(records: List<RecordWire>, store: LedgerStore, uploaded: Boolean) {
        store.applyRecords(records)
        if (uploaded) {
            CloudRecords.saveUploaded(store, CloudRecords.uploaded(store) + records.map { CloudRecords.documentId(it) })
        }
        store.rebuildProjection()
    }

    /** Takes [member] out. A group in the cloud goes through the Function (the
     *  only writer of a removal; phones refuse a removal a phone wrote), then
     *  the same records go into this vault. A group only on this phone just
     *  appends them. */
    suspend fun remove(
        store: LedgerStore, group: NamedGroupWire, member: MemberWire, you: String?, byName: String,
        redistributions: List<RedistributionInput>, shared: Boolean, place: String = member.id,
    ) = withContext(Dispatchers.IO) {
        val records = records(store, group.group.id, place, redistributions, Placing.load(store))
        if (shared) {
            CloudFunctions.callJson("removeMember", buildJsonObject {
                put("groupId", group.group.id)
                put("member", place)
                put("memberName", RoleRules.wireName(member.name))
                put("groupName", RoleRules.wireName(group.group.name))
                put("byName", RoleRules.wireName(byName))
                put("records", payload(records))
                if (you != null) put("you", you)
            })
        }
        applyLocally(records, store, uploaded = shared)
    }
}

// ---- Settle first (pure state of the screen) ----

/** How far "Record and remove" got: how many of the payments are in the
 *  vault, and whether the person is out. A payment once appended is never
 *  appended again: a later tap carries on from where the last one stopped, so
 *  a failed removal is retried alone. */
class SettleFirstProgress(val total: Int, appended: Int = 0, removed: Boolean = false) {
    var appended by mutableStateOf(appended)
        private set
    var removed by mutableStateOf(removed)
        private set
    /** D-210 final B6: whether the payments reached the cloud before the
     *  removal (null until the sync ran). The removal goes ahead either way;
     *  false says they were still waiting on this phone when it did. */
    var uploaded by mutableStateOf<Boolean?>(null)
        private set

    /** Every payment is recorded; only the removal is left. */
    val recorded: Boolean get() = appended >= total

    /** At least one payment is in the vault. */
    val started: Boolean get() = appended > 0

    /** One tap: the payments not yet appended (in order), then the sync, then
     *  the removal. Throws where it stopped; the progress says how far. Its
     *  state changes go through [onMain] (the job passes [MainThread.await]). */
    suspend fun run(
        append: suspend (Int) -> Unit, sync: suspend () -> Boolean, remove: suspend () -> Unit,
        onMain: suspend (() -> Unit) -> Unit = { it() },
    ) {
        if (removed) return
        while (appended < total) {
            append(appended)
            onMain { appended += 1 }
        }
        val up = sync()
        onMain { uploaded = up }
        remove()
        onMain { removed = true }
    }

    companion object {
        /** Survives rotation and process death (the screen keeps it while the flow is open). */
        val Saver: Saver<SettleFirstProgress, Any> = listSaver(
            save = { listOf<Any>(it.total, it.appended, it.removed) },
            restore = { SettleFirstProgress(it[0] as Int, it[1] as Int, it[2] as Boolean) },
        )
    }
}

// ---- Redistribute (pure state of the screen) ----

/** The Redistribute screen's state for one currency: who is ticked, equal
 *  shares (core's) or typed ones, and whether they add up. Every amount is
 *  core's: the equal split is `ffiRedistributeEqual`, a typed amount is read
 *  by `ffiMoneyParseMinor` and given the balance's sign, and "adds up" is
 *  `ffiRedistributionTally`. */
data class RedistributeModel(
    val from: String,
    val currency: String,
    /** The person's balance: negative owes, positive is owed. */
    val balance: Long,
    /** The people who can take it, in the order the screen lists them. */
    val candidates: List<String>,
    val ticked: Set<String> = candidates.toSet(),
    val custom: Boolean = false,
    /** Typed amounts by person (digits as typed), used while [custom]. */
    val typed: Map<String, String> = emptyMap(),
) {
    sealed interface Status {
        data object SplitEqually : Status
        data object AddsUp : Status
        /** Minor units still to assign / too much (magnitudes). */
        data class Left(val minor: Long) : Status
        data class TooMuch(val minor: Long) : Status
        data object TypeAmounts : Status
        data object TickOne : Status
    }

    fun toggle(id: String): RedistributeModel =
        if (id !in candidates) this else copy(ticked = if (id in ticked) ticked - id else ticked + id)

    /** "Edit amounts": the fields start at the equal split. */
    fun startEditing(): RedistributeModel {
        val digits = equalShares().associate { share ->
            share.id to runCatching { ffiMoneyInputDigits(magnitude(share.amountMinor), currency) }.getOrDefault("")
        }
        return copy(custom = true, typed = digits)
    }

    /** "Split equally": the typed text is dropped. */
    fun stopEditing(): RedistributeModel = copy(custom = false, typed = emptyMap())

    fun type(id: String, text: String): RedistributeModel = copy(typed = typed + (id to text))

    private val tickedInOrder: List<String> get() = candidates.filter { it in ticked }

    private fun equalShares(): List<IdAmount> {
        if (balance == 0L || ticked.isEmpty()) return emptyList()
        return runCatching { ffiRedistributeEqual(balance, tickedInOrder) }.getOrDefault(emptyList())
    }

    /** Typed amounts of the ticked people, signed like the balance; `complete`
     *  is false when a ticked person has nothing usable (blank, not an amount,
     *  or zero). */
    private fun typedShares(): Pair<List<IdAmount>, Boolean> {
        val out = mutableListOf<IdAmount>()
        var complete = true
        for (id in tickedInOrder) {
            val minor = runCatching { ffiMoneyParseMinor((typed[id] ?: "").trim(), currency) }.getOrNull()
            if (minor == null || minor <= 0L) {
                complete = false
                continue
            }
            out += IdAmount(id, if (balance < 0) -minor else minor)
        }
        return out.sortedBy { it.id } to complete
    }

    /** The shares this screen would record (equal ones are what core gives;
     *  typed ones as parsed, even if short or over: see [status]). */
    fun shares(): List<IdAmount> = if (custom) typedShares().first else equalShares()

    private val tally: RedistributionTally get() = ffiRedistributionTally(balance, from, candidates, shares())

    val status: Status
        get() {
            if (ticked.isEmpty()) return Status.TickOne
            if (!custom) return Status.SplitEqually
            if (!typedShares().second) return Status.TypeAmounts
            val t = tally
            if (t.ok) return Status.AddsUp
            val left = t.leftMinor
            if (left == 0L) return Status.TypeAmounts
            return if ((left < 0) == (balance < 0)) Status.Left(magnitude(left)) else Status.TooMuch(magnitude(left))
        }

    /** Whether Remove is on: shares exist, and core says they add up. */
    val ok: Boolean
        get() = when (status) {
            Status.SplitEqually -> tally.ok
            Status.AddsUp -> true
            else -> false
        }

    /** What is assigned so far, as a magnitude. */
    val assigned: Long get() = magnitude(tally.assignedMinor)

    /** The record this screen makes, when [ok]. */
    fun redistribution(group: String): RedistributionInput? =
        if (ok) RedistributionInput(group, from, currency, shares()) else null

    /** Flat and primitive, so it fits a Bundle (the PeopleDraft pattern). */
    internal fun encode(): List<Any> {
        val out = mutableListOf<Any>(from, currency, balance, custom, candidates.size)
        out.addAll(candidates)
        out += ticked.size
        out.addAll(ticked.sorted())
        out += typed.size
        for ((k, v) in typed) {
            out += k
            out += v
        }
        return out
    }

    companion object {
        /** D-210 final B6: a size that is never negative (`abs(Long.MIN_VALUE)` is). */
        fun magnitude(value: Long): Long = if (value == Long.MIN_VALUE) Long.MAX_VALUE else abs(value)

        /** Reads one model from [flat] at [start]; returns it and where the next begins. */
        internal fun decode(flat: List<Any>, start: Int): Pair<RedistributeModel, Int> {
            var i = start
            val from = flat[i++] as String
            val currency = flat[i++] as String
            val balance = flat[i++] as Long
            val custom = flat[i++] as Boolean
            val nCandidates = flat[i++] as Int
            val candidates = (0 until nCandidates).map { flat[i++] as String }
            val nTicked = flat[i++] as Int
            val ticked = (0 until nTicked).map { flat[i++] as String }.toSet()
            val nTyped = flat[i++] as Int
            val typed = linkedMapOf<String, String>()
            repeat(nTyped) {
                val k = flat[i++] as String
                typed[k] = flat[i++] as String
            }
            return RedistributeModel(from, currency, balance, candidates, ticked, custom, typed) to i
        }

        val Saver: Saver<RedistributeModel, Any> = listSaver(
            save = { it.encode() },
            restore = { decode(it, 0).first },
        )

        /** Every currency's model, one after the other. */
        val ListSaver: Saver<List<RedistributeModel>, Any> = listSaver(
            save = { models -> listOf<Any>(models.size) + models.flatMap { it.encode() } },
            restore = { flat ->
                var i = 1
                (0 until (flat[0] as Int)).map {
                    val (model, next) = decode(flat, i)
                    i = next
                    model
                }
            },
        )
    }
}

// ---- Who is who on the People screen (pure) ----

/** A row of the People screen with the role and the actions it offers. */
data class PersonRow(
    val member: MemberWire,
    /** The account's participant id when the person joined on a phone. */
    val you: String?,
    val role: RoleRules.Role,
    val isSelf: Boolean,
    val offer: RoleRules.Offer,
    /** The person as the cloud knows them: the group's own id for the place
     *  they hold ([PeopleRoles.placeFor]). Equal to `member.id` for everyone
     *  but a viewer who joined through an invite. Every callable about this
     *  row sends it as `member`. */
    val place: String = member.id,
) {
    val id: String get() = member.id

    /** D-227: the `you` a removal request names: none for the viewer's own row
     *  (the server knows the caller, and the place may not be the account's
     *  participant id), the account id for anyone else. */
    val askYou: String? get() = if (isSelf) null else you
}

object PeopleRoles {
    /** The id the cloud knows a row's person by. On the phone of someone who
     *  joined through an invite, the projection aliases the place they claimed
     *  to their account's "you" id, so their own row is called by the you id;
     *  the Functions know the place (the group's member id from the invite).
     *  A row that is the viewer's ([rowId] == [selfId]) is called by the place
     *  the account claimed (the claim whose `you` is one of [yous]); the
     *  creator has no claim (the place IS the you id), and every other row
     *  already holds its place. */
    fun placeFor(rowId: String, selfId: String, yous: List<String>, claims: Map<String, String>): String {
        if (rowId != selfId) return rowId
        return claims.filterValues { it in yous }.keys.minOrNull() ?: rowId
    }

    /** D-227 Design 6: the place the viewer's own row is called by. The cloud's
     *  `places.you` ([yourPlaces]) decides when it named any: the one
     *  [placeFor] picks with every id of the account ([yous]) if it is listed,
     *  else the first sorted. Unknown, it is [placeFor] with every id. */
    fun selfPlace(selfId: String, yous: List<String>, claims: Map<String, String>, yourPlaces: List<String>?): String {
        val picked = placeFor(selfId, selfId, yous, claims)
        if (yourPlaces.isNullOrEmpty()) return picked
        return if (picked in yourPlaces) picked else yourPlaces.sorted().first()
    }

    /** Each member with their role and what the viewer may do. [claims] maps a
     *  member to the account that joined as them; [roles] is the cloud's list,
     *  null while it is not known (everyone then reads as a plain member: the
     *  Functions decide in the end, whatever this phone offers). D-227: a role
     *  is read by place when the cloud gave places ([RoleRules.role]); the
     *  viewer's own place comes from [yourPlaces] (the cloud's `places.you`,
     *  every caller passes it, as on iOS), else from every id of the account
     *  ([viewerYou] and [otherYouIds]). M-f: a row this phone knows no claim
     *  for is never offered a role change (the Functions need the person's
     *  own word for itself, which the phone does not have yet). D-247 (decision 170): [createdHere], this phone wrote
     *  the group's create record ([LedgerStore.Sync.createdHere]). Until the cloud's roles for the group are known
     *  ([roles] null), such a group shows the viewer as its owner, with the owner's options (never "Ask to be removed").
     *  Display only: every action still goes to the Functions, which decide; no rule changes. The cloud's answer, once
     *  cached, always wins. */
    fun rows(
        members: List<MemberWire>, selfId: String, viewerYou: String?, claims: Map<String, String>,
        roles: RoleRules.Roles?, yourPlaces: List<String>? = null, otherYouIds: List<String> = emptyList(),
        readOnly: Boolean = false, createdHere: Boolean = false,
    ): List<PersonRow> {
        val known = roles ?: RoleRules.Roles(null, emptyList())
        val creatorFallBack = CreatorFallBack.applies(roles, createdHere)
        fun you(id: String): String? = if (id == selfId) viewerYou ?: claims[id] else claims[id]
        val ownPlace = selfPlace(selfId, listOfNotNull(viewerYou) + otherYouIds, claims, yourPlaces)
        fun place(id: String): String = if (id == selfId) ownPlace else id
        val viewer = if (creatorFallBack) RoleRules.Role.OWNER else RoleRules.role(viewerYou, ownPlace, known)
        return members.map { m ->
            val isSelf = m.id == selfId
            // A copy of a group (removed from, or deleted): the roles shown
            // were the cloud's of a moment ago and nothing can be done here.
            if (readOnly) {
                val none = RoleRules.Offer(
                    remove = false, ask = false, askSelf = false, makeAdmin = false, removeAdmin = false, makeOwner = false,
                )
                PersonRow(m, you(m.id), RoleRules.Role.MEMBER, isSelf, none, place(m.id))
            } else {
                val role = if (isSelf && creatorFallBack) RoleRules.Role.OWNER else RoleRules.role(you(m.id), place(m.id), known)
                val offer = RoleRules.offer(viewer, role, isSelf).let {
                    if (you(m.id) != null) it else it.copy(makeAdmin = false, removeAdmin = false, makeOwner = false)
                }
                PersonRow(m, you(m.id), role, isSelf, offer, place(m.id))
            }
        }
    }

    fun viewerRole(rows: List<PersonRow>): RoleRules.Role = rows.firstOrNull { it.isSelf }?.role ?: RoleRules.Role.MEMBER
}

/** D-247 (decision 170, D-210 residue (o)): the display-only "this phone made this group" fall-back. The twin of iOS's
 *  `CreatorFallBack`. */
object CreatorFallBack {
    /** Only while the cloud's roles for the group are unknown, and only for a group this phone created. */
    fun applies(roles: RoleRules.Roles?, createdHere: Boolean): Boolean = roles == null && createdHere
}

// ---- What this phone knows about a group's people ----

/** What the People screen and a group's page need beyond the named group: is
 *  the group in the cloud, which account is "you", who joined as whom, and is
 *  it a read-only copy. Read strictly: a vault that cannot answer is an error
 *  (the screen says so), never "no". */
data class GroupPeople(
    val shared: Boolean = false,
    val viewerYou: String? = null,
    val claims: Map<String, String> = emptyMap(),
    val readOnly: ReadOnlyDTO? = null,
    /** D-227: the other ids this account's phones wrote as "you" (D-153), so
     *  the viewer's own place is found under any of them. */
    val otherYouIds: List<String> = emptyList(),
    /** D-247 (decision 170): this phone wrote the group's create record (the creator fall-back's input). */
    val createdHere: Boolean = false,
) {
    companion object {
        fun load(store: LedgerStore, groupId: String): GroupPeople {
            val projection = store.projectGroups()
            val claims = projection.groups.firstOrNull { it.group.id == groupId }?.claims.orEmpty()
            return GroupPeople(
                shared = groupId in CloudRecords.groupsUp(store),
                viewerYou = projection.you?.id,
                otherYouIds = projection.otherYouIds,
                claims = claims.associate { it.memberId to it.youId },
                readOnly = store.readOnlyChecked()[groupId],
                createdHere = groupId in LedgerStore.Sync.createdHere(store),
            )
        }
    }
}

// ---- Open requests as cards (pure) ----

data class RequestCardItem(val item: Notices.Item, val kind: RoleRules.RequestCard) {
    val id: String get() = item.id
}

object RequestCards {
    /** The open removal requests of one group, each shaped for the viewer by
     *  [RoleRules.requestCard], from the People rows (who is here, and each
     *  one's role). A member receives a request's notice only as the asker or
     *  as the person named, so a member who is not the person asked. A request
     *  about someone no longer here is skipped. */
    fun cards(items: List<Notices.Item>, rows: List<PersonRow>): List<RequestCardItem> {
        val viewer = PeopleRoles.viewerRole(rows)
        return items.mapNotNull { item ->
            val member = item.memberId
            if (item.kind != Notices.Kind.REQUEST || member == null || item.memberName == null) return@mapNotNull null
            val row = rows.firstOrNull { it.place == member } ?: return@mapNotNull null
            val kind = RoleRules.requestCard(
                viewer, row.role, viewerIsAsker = !row.isSelf, viewerIsPerson = row.isSelf,
            ) ?: return@mapNotNull null
            RequestCardItem(item, kind)
        }
    }

    /** A card's title, as the Copy table words it: a request the person made
     *  for themself reads "You asked to be removed" on their own card and
     *  "{name} asked to be removed" on everyone else's. */
    fun title(card: RequestCardItem): String {
        val name = card.item.memberName ?: ""
        val own = card.item.askedItself
        return when (card.kind) {
            RoleRules.RequestCard.DECIDE, RoleRules.RequestCard.OWNER_DECIDES ->
                if (own) RolesCopy.cardAskedToBeRemoved(name) else RolesCopy.cardAsked(card.item.byName, name)
            RoleRules.RequestCard.ASKER -> RolesCopy.cardAsker(name)
            RoleRules.RequestCard.PERSON ->
                if (own) RolesCopy.CARD_YOU_ASKED_TO_BE_REMOVED else RolesCopy.cardPerson(card.item.byName)
        }
    }

    /** Whether a request's review may go on to the removal: what the person's
     *  sheet would offer this viewer (`RoleRules.offer(...).remove`). */
    fun mayRemove(rows: List<PersonRow>, member: String): Boolean =
        rows.firstOrNull { it.place == member }?.offer?.remove ?: false

    /** Under each open request, what the person named owes or is owed in the
     *  group ("Sam owes ₹1,000.00 here."), by request id; a settled person, or
     *  a balance that cannot be read, has none. */
    fun balanceLines(store: LedgerStore, group: String, items: List<Notices.Item>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (item in items) {
            val member = item.memberId ?: continue
            val name = item.memberName ?: continue
            if (item.kind != Notices.Kind.REQUEST) continue
            val lines = runCatching { Removal.lines(store, group, member) }.getOrNull() ?: continue
            val line = RolesCopy.cardBalance(name, Removal.plan(lines, member)) ?: continue
            out[item.id] = line
        }
        return out
    }

    /** "Request sent": an open request naming [member] is already on this
     *  account's notices (one open request per person; asking again would be
     *  refused as ALREADY_EXISTS). */
    fun asked(items: List<Notices.Item>, member: String): Boolean =
        items.any { it.kind == Notices.Kind.REQUEST && it.memberId == member }
}

// ---- Where a removal was opened from (pure) ----

/** A removal's route: the group, the person, whether it starts at a request's
 *  review, and where Back and Done return to (People, or the group's page). */
data class RemovalRoute(val group: String, val member: String, val review: Boolean, val fromPeople: Boolean) {
    fun route(): String =
        "remove/$group/$member/${if (review) "review" else "start"}/${if (fromPeople) "people" else "group"}"

    companion object {
        fun parse(route: String): RemovalRoute? {
            val parts = route.split("/")
            if (parts.size != 5 || parts[0] != "remove") return null
            if (!CloudRecords.isHex32(parts[1]) || !CloudRecords.isHex32(parts[2])) return null
            val review = when (parts[3]) { "review" -> true; "start" -> false; else -> return null }
            val fromPeople = when (parts[4]) { "people" -> true; "group" -> false; else -> return null }
            return RemovalRoute(parts[1], parts[2], review, fromPeople)
        }
    }
}

// ---- Pieces the screens share ----

/** The Owner / Admin pill (gold on its soft fill for the owner, the accent on
 *  its soft fill for an admin: existing tokens, measured by AppearanceTest).
 *  D-220 (L19): the theme's pill (outlined in Vardr Labs), through `themePill`. */
@Composable
fun RolePill(role: RoleRules.Role, dark: Boolean) {
    val (text, tone) = when (role) {
        RoleRules.Role.OWNER -> RolesCopy.OWNER_PILL to PillTone.OWNER
        RoleRules.Role.ADMIN -> RolesCopy.ADMIN_PILL to PillTone.ACCENT
        else -> return
    }
    Text(
        Type.kicker(text),
        style = Type.microLabel(dark).copy(color = ThemePills.text(tone, dark)),
        modifier = Modifier
            .themePill(tone, dark)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A person's initial on their tone, as Settle up draws it. D-211: [preview]
 *  is their preview in this group (a picture or an emoji); `Avatar.None` is the
 *  initial on a tone. Tapping it does nothing. */
@Composable
fun PersonAvatar(
    name: String,
    dark: Boolean,
    size: Int = 40,
    preview: Avatar = Avatar.None,
    /** D-223: null keeps the name's tone; [AvatarFill.SOFT] (Home's Lately) gives accentSoft with the initial in the
     *  accent. iOS's twin is `PersonAvatar(fill:)`. */
    fill: AvatarFill? = null,
) {
    if (preview != Avatar.None) {
        AvatarView(preview, name, size.dp, dark)
        return
    }
    val tone = personTone(name)
    // D-213: Norse's diamond, Vardr Labs' square; Aurora keeps the circle.
    val kind = TileShape.of(Appearance.theme, person = true)
    // D-220 (L23): the `size` footprint; the shape drawn at `tileDrawnSide` on it (Norse's diamond at size x sqrt 2).
    Box(Modifier.size(size.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredSize(tileDrawnSide(kind, size).dp)
                .tileEdge(kind, size)
                .background(fill?.ground?.invoke(dark) ?: tone.fill(dark), tileShape(kind, size)),
            contentAlignment = Alignment.Center,
        ) {
            Text(GroupTone.initial(name), style = tileInitialStyle(kind, size, dark, fill?.ink?.invoke(dark) ?: tone.letter(dark)))
        }
    }
}

/** A screen with its action pinned under the scrolling content (iOS's
 *  `formActionBar`): the bar (back and the title) over the content, then the actions. */
@Composable
internal fun FlowScreen(
    dark: Boolean,
    title: String,
    onBack: () -> Unit,
    // D-217 final fix: the root of a request's review is a sheet on iOS (RemovalSheet, Cancel at its root); given a
    // cancel, the bar is the sheet's. Its later steps push, so they keep the back.
    cancel: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    // D-220 (L-D217-3): the page is its bar's backdrop (Aurora blurs it under the bar).
    val backdrop = rememberBackdrop()
    Column(Modifier.fillMaxSize().auroraBackground(dark)) {
        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .backdropSource(backdrop)
                    .verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = BarDecisions.scrollTop(16f).dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
            if (cancel != null) SheetBar(title, onCancel = cancel, scroll = scroll, backdrop = backdrop) else PushedBar(title, onBack, scroll = scroll, backdrop = backdrop)
        }
        // iOS's formActionBar: 20 pt sides, 8 above and below (D-217 Task 8: it was 16, 8 and 12).
        FormActionBar { actions() }
    }
}

/** The tinted box on a request card, the review banner and a copy's banner. */
@Composable
private fun AmberBox(dark: Boolean, radius: Int, padding: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(GroupTone.AMBER.fill(dark), ThemeArt.panelShape(Appearance.theme, radius))
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

private val titleStyle: (Boolean) -> androidx.compose.ui.text.TextStyle = { dark ->
    Type.display(dark).copy(fontSize = 22.sp, fontWeight = FontWeight(700))
}

/** One open removal request, as the Copy table words it. */
@Composable
fun RequestCardView(
    card: RequestCardItem,
    dark: Boolean,
    /** The person's own card offers Settle up while they have a balance here. */
    canSettleUp: Boolean = false,
    /** "Sam owes ₹1,000.00 here." under the title of a card an owner or admin decides. */
    balance: String? = null,
    busy: Boolean = false,
    onDecline: () -> Unit,
    onReview: () -> Unit,
    onSettle: (() -> Unit)? = null,
) {
    AmberBox(dark, radius = 18, padding = 14) {
        when (card.kind) {
            RoleRules.RequestCard.DECIDE -> {
                Text(RequestCards.title(card), style = Type.label(dark, 16))
                balance?.let { Text(it.withRunes(), style = Type.bodyDim(dark).copy(fontSize = 14.sp)) }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { SecondaryAction(RolesCopy.DECLINE, dark, enabled = !busy, onClick = onDecline) }
                    Box(Modifier.weight(1f)) { PrimaryAction(RolesCopy.REVIEW, dark, enabled = !busy, onClick = onReview) }
                }
            }
            RoleRules.RequestCard.OWNER_DECIDES -> {
                Text(RequestCards.title(card), style = Type.label(dark, 16))
                balance?.let { Text(it.withRunes(), style = Type.bodyDim(dark).copy(fontSize = 14.sp)) }
                Text(RolesCopy.CARD_OWNER_WILL, style = Type.bodyDim(dark).copy(fontSize = 14.sp))
            }
            RoleRules.RequestCard.ASKER -> {
                Text(RequestCards.title(card), style = Type.label(dark, 16))
                Text(RolesCopy.CARD_WAIT, style = Type.bodyDim(dark).copy(fontSize = 14.sp))
            }
            RoleRules.RequestCard.PERSON -> {
                Text(RequestCards.title(card), style = Type.label(dark, 16))
                Text(RolesCopy.CARD_WAIT, style = Type.bodyDim(dark).copy(fontSize = 14.sp))
                if (canSettleUp && onSettle != null) {
                    Box(Modifier.padding(top = 6.dp)) { PrimaryAction(RolesCopy.SETTLE_UP, dark, onClick = onSettle) }
                }
            }
        }
    }
}

/** What a decision about a request needs besides the screen's own state: the
 *  calls and their words, shared by the People screen and a group's page. */
object RoleCalls {
    /** Declines a request (`declineRemoval`); the card goes at once, and the
     *  Function deletes the notice everywhere. */
    suspend fun decline(item: Notices.Item, group: NamedGroupWire, byName: String) {
        val member = item.memberId ?: return
        CloudFunctions.callJson("declineRemoval", buildJsonObject {
            put("groupId", group.group.id)
            put("member", member)
            put("byName", RoleRules.wireName(byName))
            put("groupName", RoleRules.wireName(group.group.name))
        })
        Notices.dismiss(item)
    }

    /** Asks for [member]'s removal (`requestRemoval`); also fine when they
     *  were already asked about (ALREADY_EXISTS reads as "Request sent"). */
    suspend fun ask(
        group: NamedGroupWire, member: MemberWire, you: String?, askerName: String, place: String = member.id,
    ) {
        val data = buildJsonObject {
            put("groupId", group.group.id)
            put("member", place)
            put("memberName", RoleRules.wireName(member.name))
            put("groupName", RoleRules.wireName(group.group.name))
            put("askerName", RoleRules.wireName(askerName))
            if (you != null) put("you", you)
        }
        try {
            CloudFunctions.callJson("requestRemoval", data)
        } catch (e: CloudFunctions.Failure) {
            if (!RolesCopy.alreadyAsked(e)) throw e
        }
    }
}

// ---- A person's sheet (shared groups) ----

/** One row of a sheet: a title, a line under it, a chevron. */
@Composable
private fun ActionRow(
    title: String, sub: String?, destructive: Boolean, dark: Boolean, enabled: Boolean = true, onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClickLabel = title, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = PersonSheetLayout.INSET.dp, vertical = PersonSheetLayout.ROW_PAD_V.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PersonSheetLayout.ROW_LINE_GAP.dp)) {
            Text(title, style = Type.body(dark).copy(color = if (destructive) Tok.owed(dark) else Tok.text(dark)))
            if (sub != null) Text(sub.withRunes(), style = Type.bodyDim(dark).copy(fontSize = 13.sp))
        }
        RowChevron(Tok.textFaint(dark))
    }
}

/** A card of [rows] with a hairline between rows, never before the first (iOS's Form section, R4). */
@Composable
private fun RowsCard(dark: Boolean, rows: List<@Composable () -> Unit>) {
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Column(Modifier.fillMaxWidth().background(Tok.surfaceRaised(dark), ThemeArt.panelShape(Appearance.theme, 16))) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.padding(horizontal = PersonSheetLayout.INSET.dp).fillMaxWidth().height(hairline).background(Tok.hairline(dark)))
            row()
        }
    }
}

/** D-225 follow-up (R4, the re-look's pairs role-nd and role-vl): the person sheet laid out as iOS's Form (dp for its
 *  pt, measured on iOS 27 beside API 34; ScreenBarsTest re-derives each from the measurements). */
object PersonSheetLayout {
    /** Cancel's bottom edge to the header card (the avatar touched Cancel). */
    const val TOP = 43f
    /** Between two cards (iOS's section spacing). */
    const val CARD_GAP = 33.67f
    /** A card to the Role label's box: its ink 29.33 under the card (the label face's ink sits 3.24 into its box). */
    const val LABEL_ABOVE = 26.09f
    /** The label's box to its card: its ink 13.33 over the card (3.62 of the box under the ink). */
    const val LABEL_BELOW = 9.71f
    /** A row's side inset, and its hairline's (iOS's separators stop 16 short of both edges). */
    const val INSET = 16f
    /** The header row: the 48 avatar in iOS's 74.67. */
    const val HEADER_PAD_V = 13.33f
    /** A two-line action row: iOS's 67.33. */
    const val ROW_PAD_V = 14.27f
    /** The row's title to its line under it. */
    const val ROW_LINE_GAP = 1.25f
}

/** What a person's sheet may change about their role, each behind a confirm. */
private enum class RoleAction { MAKE_ADMIN, REMOVE_ADMIN, MAKE_OWNER }

/**
 * The content of a person's sheet (a ModalBottomSheet owned by the People
 * screen): Remove first (or Ask), Rename, then the role actions under "Role",
 * each behind a confirm. The removal itself is its own screen: [onRemove].
 */
@Composable
fun PersonSheetContent(
    vaultPath: String,
    named: NamedGroupWire,
    row: PersonRow,
    viewerName: String,
    dark: Boolean,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
    /** D-217 Task 8: the bar's Cancel: closes the sheet without a change. */
    onCancel: () -> Unit,
    /** D-211: their preview here, and whether Rename is offered (absent for a
     *  person with a profile: their name is theirs). */
    preview: Avatar = Avatar.None,
    canRename: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val groupId = named.group.id
    val name = row.member.name
    val group = named.group.name
    var plan by remember(groupId, row.member.id) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // "Request sent": just asked here, or an open request naming them is on
    // this account's notices (so a reopened sheet never offers Ask again).
    val requested = asked || RequestCards.asked(Notices.requests(groupId), row.place)
    val balancePhrase = RolesCopy.balanceHere(name, plan)

    LaunchedEffect(vaultPath, groupId, row.member.id) {
        plan = withContext(Dispatchers.IO) {
            runCatching { Removal.plan(Removal.lines(LedgerStore(vaultPath), groupId, row.member.id), row.member.id) }
                .getOrDefault(emptyMap())
        }
    }

    fun ask() {
        busy = true
        scope.launch {
            try {
                withContext(NonCancellable + Dispatchers.IO) { RoleCalls.ask(named, row.member, row.askYou, viewerName, row.place) }
                asked = true
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = RolesCopy.failure(e, person = true)
            } finally {
                busy = false
            }
        }
    }

    fun run(action: RoleAction) {
        val you = row.you ?: return
        busy = true
        scope.launch {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    val store = LedgerStore(vaultPath)
                    when (action) {
                        RoleAction.MAKE_ADMIN -> GroupRoles.setAdmin(store, groupId, row.place, you, true, viewerName, group)
                        RoleAction.REMOVE_ADMIN -> GroupRoles.setAdmin(store, groupId, row.place, you, false, viewerName, group)
                        RoleAction.MAKE_OWNER -> GroupRoles.makeOwner(store, groupId, row.place, you, viewerName, group)
                    }
                }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = RolesCopy.failure(e, person = true)
            } finally {
                busy = false
            }
        }
    }

    @Composable
    fun askRow(title: String) {
        if (requested) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("✓", style = Type.body(dark).copy(color = Tok.owing(dark)), modifier = Modifier.clearAndSetSemantics {})
                Text(RolesCopy.ASKED, style = Type.body(dark).copy(color = Tok.owing(dark)))
            }
        } else {
            ActionRow(title, balancePhrase, destructive = true, dark = dark, enabled = !busy) { ask() }
        }
    }

    // D-217 Task 8: iOS's person sheet has the name as its inline title and a Cancel (RemovalFlow.swift:742-743, :927); the
    // body repeats the name as iOS does. The sheet's own ground is the surface token, so the pills are drawn raised.
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth()) {
        SheetBar(name, onCancel = onCancel, scroll = scroll, onSurface = true, underStatusBar = false)
        // D-225 follow-up (R4): iOS's Form: the person in a header card 43 under Cancel, cards 33.67 apart, the Role
        // label placed as iOS's section header, a hairline between rows ([PersonSheetLayout]).
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Spacer(Modifier.height(PersonSheetLayout.TOP.dp))
            RowsCard(dark, listOf {
                Row(
                    Modifier.padding(horizontal = PersonSheetLayout.INSET.dp, vertical = PersonSheetLayout.HEADER_PAD_V.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PersonAvatar(name, dark, 48, preview)
                    Text(name, style = Type.display(dark).copy(fontSize = 20.sp, fontWeight = FontWeight(700)))
                    RolePill(row.role, dark)
                }
            })
            if (row.offer.ask) {
                Spacer(Modifier.height(PersonSheetLayout.CARD_GAP.dp))
                RowsCard(dark, listOf {
                    Text(
                        RolesCopy.askNote(name, joined = row.you != null),
                        style = Type.bodyDim(dark).copy(fontSize = 13.sp),
                        modifier = Modifier.padding(horizontal = PersonSheetLayout.INSET.dp, vertical = PersonSheetLayout.ROW_PAD_V.dp),
                    )
                })
            }
            // D-211: the section is skipped when it would be empty.
            val actions = buildList<@Composable () -> Unit> {
                if (row.offer.remove) {
                    add { ActionRow(RolesCopy.removeFrom(group), balancePhrase, destructive = true, dark = dark, enabled = !busy, onClick = onRemove) }
                }
                if (row.offer.ask) add { askRow(RolesCopy.askToRemove(name)) }
                if (row.offer.askSelf) add { askRow(RolesCopy.ASK_SELF) }
                if (canRename) add { ActionRow(RolesCopy.RENAME, null, destructive = false, dark = dark, enabled = !busy, onClick = onRename) }
            }
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(PersonSheetLayout.CARD_GAP.dp))
                RowsCard(dark, actions)
            }
            if (row.offer.makeAdmin || row.offer.removeAdmin || row.offer.makeOwner) {
                Spacer(Modifier.height(PersonSheetLayout.LABEL_ABOVE.dp))
                SectionLabel(RolesCopy.ROLE_SECTION, dark)
                Spacer(Modifier.height(PersonSheetLayout.LABEL_BELOW.dp))
                RowsCard(dark, buildList<@Composable () -> Unit> {
                    if (row.offer.makeAdmin) {
                        add {
                            ActionRow(RolesCopy.MAKE_ADMIN_ROW, RolesCopy.makeAdminSub(name), false, dark, !busy) {
                                confirm = RoleAction.MAKE_ADMIN.name
                            }
                        }
                    }
                    if (row.offer.removeAdmin) {
                        add {
                            ActionRow(RolesCopy.REMOVE_ADMIN_ROW, RolesCopy.removeAdminSub(name), false, dark, !busy) {
                                confirm = RoleAction.REMOVE_ADMIN.name
                            }
                        }
                    }
                    if (row.offer.makeOwner) {
                        add {
                            ActionRow(RolesCopy.MAKE_OWNER_ROW, RolesCopy.MAKE_OWNER_SUB, false, dark, !busy) {
                                confirm = RoleAction.MAKE_OWNER.name
                            }
                        }
                    }
                })
            }
            // iOS says it in a section of its own.
            error?.let {
                Spacer(Modifier.height(PersonSheetLayout.CARD_GAP.dp))
                RowsCard(dark, listOf {
                    Box(Modifier.padding(horizontal = PersonSheetLayout.INSET.dp, vertical = PersonSheetLayout.ROW_PAD_V.dp)) { ErrorLine(it, dark) }
                })
            }
        }
    }

    val action = confirm?.let { c -> RoleAction.values().firstOrNull { it.name == c } }
    // D-224 (Design 3): handing the group over cannot be taken back by you, so its question warns as it opens; making
    // or removing an admin can be undone and does not.
    HapticWarning(action == RoleAction.MAKE_OWNER)
    if (action != null) {
        SkiptiAlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    when (action) {
                        RoleAction.MAKE_ADMIN -> RolesCopy.makeAdminTitle(name)
                        RoleAction.REMOVE_ADMIN -> RolesCopy.removeAdminTitle(name)
                        RoleAction.MAKE_OWNER -> RolesCopy.makeOwnerTitle(name)
                    },
                )
            },
            text = {
                Text(
                    when (action) {
                        RoleAction.MAKE_ADMIN -> RolesCopy.makeAdminBody(name)
                        RoleAction.REMOVE_ADMIN -> RolesCopy.removeAdminBody(name)
                        RoleAction.MAKE_OWNER -> RolesCopy.makeOwnerBody(name, group)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    run(action)
                }) {
                    Text(
                        when (action) {
                            RoleAction.MAKE_ADMIN -> RolesCopy.makeAdminConfirm(name)
                            RoleAction.REMOVE_ADMIN -> RolesCopy.REMOVE_ADMIN_CONFIRM
                            RoleAction.MAKE_OWNER -> RolesCopy.makeOwnerConfirm(name)
                        },
                        color = Tok.accent(dark),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(RolesCopy.CANCEL) } },
        )
    }
}

// ---- The removal screens ----

/** Saves the payments Settle first will record (flat and primitive). */
internal val settlementsSaver: Saver<List<SettlementDTO>, Any> = listSaver(
    save = { list -> list.flatMap { listOf<Any>(it.from, it.to, it.amountMinor, it.currencyCode, it.group ?: "") } },
    restore = { flat ->
        flat.chunked(5).map {
            SettlementDTO(it[0] as String, it[1] as String, it[2] as Long, it[3] as String, (it[4] as String).ifEmpty { null })
        }
    },
)

/** Settle first's progress, or none yet (nothing is saved until a payment is tapped). */
internal val progressSaver: Saver<SettleFirstProgress?, Any> = listSaver(
    save = { p -> if (p == null) emptyList() else listOf<Any>(p.total, p.appended, p.removed) },
    restore = { SettleFirstProgress(it[0] as Int, it[1] as Int, it[2] as Boolean) },
)

/** What every removal screen holds: the group, who is being removed, whether
 *  the group is in the cloud. */
private class RemovalContext(
    val named: NamedGroupWire, val member: MemberWire, val people: GroupPeople, val place: String = member.id,
) {
    val shared: Boolean get() = people.shared
    val you: String? get() = people.claims[member.id]
    val viewerName: String get() = named.members.firstOrNull { it.id == named.selfId }?.name ?: ""

    /** "Sam", or "you" for the viewer, in a payment line. */
    fun who(id: String, first: Boolean = false): String {
        if (id == named.selfId) return if (first) "You" else "you"
        return named.members.firstOrNull { it.id == id }?.name ?: LedgerStore.label(id, named)
    }

    companion object {
        /** A failure as words: the Function's refusals have theirs; anything
         *  else is this phone's own (the vault), said as such. */
        fun message(error: Throwable): String =
            if (error is CloudFunctions.Failure) RolesCopy.failure(error, person = true) else "Could not save the removal. $error"
    }
}

/** What Start reads once: the balance, and whether D-186's "in nothing" is true. */
private class StartData(
    val lines: List<BalanceLineOut>,
    val plan: Map<String, Long>,
    val nets: Map<String, Map<String, Long>>,
    val unjoinedNoHistory: Boolean,
)

/**
 * The removal, from its first screen: a request's review, else the plain
 * confirm or the balance's choice (settle first, or redistribute). One screen
 * with steps, so Back and Next never lose where the payments got to (the steps
 * and what they hold survive rotation).
 */
@Composable
fun RemovalFlowScreen(
    vaultPath: String,
    route: RemovalRoute,
    dark: Boolean,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val gid = route.group
    val mid = route.member
    var step by rememberSaveable(gid, mid) { mutableStateOf(if (route.review) "review" else "start") }
    var choice by rememberSaveable(gid, mid) { mutableStateOf("redistribute") }
    var settle by rememberSaveable(gid, mid, stateSaver = progressSaver) { mutableStateOf<SettleFirstProgress?>(null) }
    var settlements by rememberSaveable(gid, mid, stateSaver = settlementsSaver) { mutableStateOf<List<SettlementDTO>>(emptyList()) }
    var models by rememberSaveable(gid, mid, stateSaver = RedistributeModel.ListSaver) { mutableStateOf<List<RedistributeModel>>(emptyList()) }
    // D-224 fix wave (M5, D24), follow-up (A-I2): each red-button screen's warning latch, kept by the flow per step, so
    // a recreation or a reload that redraws the step never warns again, and each entry to the step warns once, as iOS's
    // freshly pushed screen does (Settle first, Back, Next: it warns again).
    val plainWarned = rememberSaveable(gid, mid, step) { mutableStateOf(false) }
    val settleWarned = rememberSaveable(gid, mid, step) { mutableStateOf(false) }
    val redistributeWarned = rememberSaveable(gid, mid, step) { mutableStateOf(false) }

    var ctx by remember(gid, mid) { mutableStateOf<RemovalContext?>(null) }
    var loadError by remember(gid, mid) { mutableStateOf<String?>(null) }
    var start by remember(gid, mid) { mutableStateOf<StartData?>(null) }
    var localError by remember(step) { mutableStateOf<String?>(null) }

    // The work runs in the app, not in this screen (GroupJobs): a rotation
    // finds it running (busy, a tap refused) or done (the flow finishes).
    val key = GroupJobs.Key.removal(gid, mid)
    val status = GroupJobs.status(key)
    val busy = status == GroupJobs.Status.Running
    val error = localError ?: (status as? GroupJobs.Status.Failed)?.message
    // A screen made again takes the job's progress, never its own older copy.
    val held = GroupJobs.progress(key, settlements)
    SideEffect { if (held != null && held !== settle) settle = held }
    LaunchedEffect(status) {
        if (status == GroupJobs.Status.Done) {
            GroupJobs.clear(key)
            // D-224 (Design 7): a completed removal says so. The review step's only job is a decline, which removes
            // nobody; every other step's job is the removal. `step` is read here, when the job is done (a state read at
            // run time, and Back is refused while busy), never captured when the effect was keyed: keep it that way.
            if (step != "review") ctx?.let { DoneCenter.post(DoneMoments.line(DoneCopy.personRemoved(it.member.name))) }
            onFinished()
        }
    }

    // The request being reviewed, kept once seen: it clears from the notices
    // when it is decided, and the screen must still say who asked.
    val live = Notices.requests(gid).firstOrNull { it.memberId == mid }
    var seen by remember(gid, mid) { mutableStateOf<Notices.Item?>(null) }
    if (live != null && live != seen) seen = live
    val request = live ?: seen

    LaunchedEffect(vaultPath, gid, mid) {
        // D-247 fix wave (review I2): a settle held for Undo lands before the flow reads anything (on the main thread,
        // the hold's); the group page under this route has gone already, so this is the twin of iOS's sheet, kept for
        // parity and for any way in that leaves a hold.
        SettleHold.shared.commit()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val store = LedgerStore(vaultPath)
                val known = GroupRoles.load(store, gid)
                val named = store.namedGroups().firstOrNull { it.group.id == gid }
                val people = GroupPeople.load(store, gid)
                // [mid] is a place (a request names one, a sheet passes the row's):
                // the row is found by it, so the viewer's own row (the you id) is too.
                val row = named?.let {
                    PeopleRoles.rows(
                        it.members, it.selfId, people.viewerYou, people.claims, null,
                        yourPlaces = known?.places?.you, otherYouIds = people.otherYouIds,
                    ).firstOrNull { r -> r.place == mid }
                }
                if (named == null || row == null) null else RemovalContext(named, row.member, people, row.place)
            }
        }
        result.fold(
            onSuccess = {
                ctx = it
                loadError = if (it == null) RolesCopy.CHANGED else null
            },
            onFailure = { loadError = "Could not load the group. ${it.message ?: it}" },
        )
    }
    val c = ctx
    // The balance, read once the group is: not again, so Settle first keeps
    // showing the payments it recorded.
    LaunchedEffect(c) {
        if (c == null || start != null) return@LaunchedEffect
        // D-247 fix wave (I2): and again before the balance is read.
        SettleHold.shared.commit()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val store = LedgerStore(vaultPath)
                val lines = Removal.lines(store, gid, mid)
                // D-186's wording is true only for someone who never joined and is in nothing;
                // a vault that cannot say is an error, not "in nothing".
                StartData(
                    lines, Removal.plan(lines, mid), Removal.nets(store, gid),
                    c.you == null && store.removalRefusal(gid, mid) == null,
                )
            }
        }
        result.fold(
            onSuccess = { start = it },
            onFailure = { loadError = "Could not read the balance. ${it.message ?: it}" },
        )
    }

    fun back() {
        if (busy) return
        // What a failure said belonged to the step being left.
        GroupJobs.clear(key)
        when (step) {
            "settle", "redistribute" -> step = "start"
            "start" -> if (route.review) step = "review" else onBack()
            else -> onBack()
        }
    }
    BackHandler(enabled = true) { back() }

    suspend fun performRemoval(flow: RemovalContext, redistributions: List<RedistributionInput>) {
        Removal.remove(
            LedgerStore(vaultPath), flow.named, flow.member, flow.you, flow.viewerName, redistributions, flow.shared, flow.place,
        )
    }

    /** Runs a removal's work in the app (never twice at once); a failure is
     *  words on the screen. */
    fun attempt(describe: (Throwable) -> String = RemovalContext::message, work: suspend () -> Unit) {
        localError = null
        GroupJobs.start(key, describe) {
            work()
            MainThread.run { SyncStatus.changed() }
        }
    }

    if (c == null) {
        val leave = { if (!busy) { GroupJobs.clear(key); onBack() } }
        FlowScreen(dark, "", onBack = leave, cancel = if (route.review) leave else null, actions = {}) {
            if (loadError != null) ErrorLine(loadError!!, dark) else Text("…", style = Type.bodyDim(dark))
        }
        return
    }
    val name = c.member.name
    val groupName = c.named.group.name
    val rows = PeopleRoles.rows(
        c.named.members, c.named.selfId, c.people.viewerYou, c.people.claims, GroupRoles.roles[gid],
        yourPlaces = GroupRoles.roles[gid]?.places?.you, otherYouIds = c.people.otherYouIds,
    )
    // Absent unless this viewer may remove them (an admin never removes an
    // admin: the owner decides that request).
    val mayRemove = if (route.review) RequestCards.mayRemove(rows, mid) else true
    val data = start

    when (step) {
        "review" -> {
            FlowScreen(
                dark, RolesCopy.balanceTitle(name), onBack = ::back, cancel = ::back,
                actions = {
                    if (mayRemove) {
                        PrimaryAction(RolesCopy.CONTINUE_TO_REMOVE, dark, enabled = !busy, tint = Tok.owed(dark)) {
                            GroupJobs.clear(key)
                            step = "start"
                        }
                    }
                    SecondaryAction(RolesCopy.DECLINE_REQUEST, dark, enabled = !busy) {
                        val item = request ?: return@SecondaryAction
                        attempt(describe = { RolesCopy.failure(it, person = true) }) { RoleCalls.decline(item, c.named, c.viewerName) }
                    }
                },
            ) {
                Text(RolesCopy.reviewTitle(name, groupName), style = titleStyle(dark))
                if (request == null) {
                    Text(RolesCopy.CHANGED, style = Type.bodyDim(dark))
                } else {
                    Text(
                        RolesCopy.reviewBody(request.byName, name, joined = c.you != null, noHistory = data?.unjoinedNoHistory ?: false),
                        style = Type.bodyDim(dark),
                    )
                }
                // What they owe, and that the next screen is where it is settled
                // (only for someone who may carry on to the removal).
                val banner = if (mayRemove && data != null) RolesCopy.reviewBanner(name, data.plan) else null
                if (banner != null) {
                    AmberBox(dark, radius = 14, padding = 12) {
                        Text(banner.first.withRunes(), style = Type.label(dark, 15).copy(color = GroupTone.AMBER.letter(dark)))
                        Text(banner.second, style = Type.bodyDim(dark).copy(color = Tok.text(dark), fontSize = 14.sp))
                    }
                }
                error?.let { ErrorLine(it, dark) }
            }
        }
        "start" -> when {
            data == null -> FlowScreen(dark, RolesCopy.balanceTitle(name), onBack = ::back, actions = {}) {
                if (loadError != null) ErrorLine(loadError!!, dark) else Text("…", style = Type.bodyDim(dark))
            }
            data.plan.isEmpty() -> PlainRemove(c, data, dark, busy, error, warned = plainWarned, onRemove = { attempt { performRemoval(c, emptyList()) } }, onCancel = ::back)
            else -> BalanceChoice(
                c, data, dark, picked = if ((held ?: settle)?.started == true) "settle" else choice,
                started = (held ?: settle)?.started == true, onPick = { choice = it },
                onNext = { picked ->
                    if (picked == "settle") {
                        // The payments are fixed once, and kept: Back and Next never record them twice.
                        if (settle == null) settlements = Removal.settleFirst(data.lines, mid, gid)
                        step = "settle"
                    } else {
                        if (models.isEmpty()) {
                            val others = c.named.members.map { it.id }.filter { it != mid }
                            models = data.plan.keys.sorted().map { code ->
                                // The people who are in this currency's balances take it to start with.
                                val involved = others.filter { (data.nets[code]?.get(it) ?: 0L) != 0L }
                                RedistributeModel(mid, code, data.plan[code] ?: 0L, others, (involved.ifEmpty { others }).toSet())
                            }
                        }
                        step = "redistribute"
                    }
                },
                onBack = ::back,
            )
        }
        "settle" -> SettleFirst(
            c, settlements, dark, busy, error, warned = settleWarned, onBack = ::back,
            onRecord = {
                // Never record a payment the screen could not have shown.
                if (!Removal.canFormat(settlements)) {
                    localError = RolesCopy.CANNOT_WRITE_PAYMENT
                } else if (!busy) {
                    // D-247 fix wave (I2): a settle still held for Undo lands first; the payments are worked out again if
                    // it did (and none of these is in yet).
                    val pay = runCatching {
                        Removal.settleFirstLanding(LedgerStore(vaultPath), settlements, mid, gid, started = (held ?: settle)?.started == true)
                    }.getOrElse {
                        localError = RemovalContext.message(it)
                        return@SettleFirst
                    }
                    if (pay != settlements) settlements = pay
                    if (!Removal.canFormat(pay)) {
                        localError = RolesCopy.CANNOT_WRITE_PAYMENT
                        return@SettleFirst
                    }
                    // The job's progress (or this screen's saved copy, after a
                    // process death), kept with the job from now on.
                    val progress = GroupJobs.settleProgress(key, pay, settle).also { settle = it }
                    attempt {
                        progress.run(
                            // Appends only what is not in yet; never one twice.
                            append = { i -> LedgerStore(vaultPath).appendSettlement(pay[i]) },
                            // The payments reach the cloud before the removal does.
                            sync = { if (c.shared) CloudSync.syncNow(c.named.group.id) else true },
                            remove = { performRemoval(c, emptyList()) },
                            onMain = { MainThread.await(it) },
                        )
                    }
                }
            },
        )
        else -> Redistribute(
            c, models, data?.nets ?: emptyMap(), dark, busy, error, warned = redistributeWarned, onChange = { models = it }, onBack = ::back,
            onRemove = {
                val moved = models.mapNotNull { it.redistribution(gid) }
                if (moved.size == models.size) attempt { performRemoval(c, moved) }
            },
        )
    }
}

/** Nothing to decide: confirm, or cancel. */
@Composable
private fun PlainRemove(
    c: RemovalContext, data: StartData, dark: Boolean, busy: Boolean, error: String?, warned: MutableState<Boolean>,
    onRemove: () -> Unit, onCancel: () -> Unit,
) {
    // D-224 (Design 3): a red-button screen is the removal's question: it warns when it first appears (the flow's latch).
    HapticWarning(true, warned)
    val name = c.member.name
    FlowScreen(
        dark, RolesCopy.balanceTitle(name), onBack = onCancel,
        actions = {
            PrimaryAction(RolesCopy.REMOVE_CONFIRM, dark, enabled = !busy, tint = Tok.owed(dark), onClick = onRemove)
            SecondaryAction(RolesCopy.CANCEL, dark, enabled = !busy, onClick = onCancel)
        },
    ) {
        Text(RolesCopy.removeTitle(name, c.named.group.name), style = titleStyle(dark))
        Text(RolesCopy.removalBody(name, joined = c.you != null, noHistory = data.unjoinedNoHistory), style = Type.bodyDim(dark))
        error?.let { ErrorLine(it, dark) }
    }
}

/** Settle first, or redistribute. */
@Composable
private fun BalanceChoice(
    c: RemovalContext, data: StartData, dark: Boolean, picked: String, started: Boolean,
    onPick: (String) -> Unit, onNext: (String) -> Unit, onBack: () -> Unit,
) {
    val name = c.member.name
    FlowScreen(
        dark, RolesCopy.balanceTitle(name), onBack = onBack,
        actions = { PrimaryAction(RolesCopy.NEXT, dark) { onNext(picked) } },
    ) {
        for (code in data.plan.keys.sorted()) {
            val net = data.plan[code] ?: 0L
            val amount = RolesMoney.format(RedistributeModel.magnitude(net), code) ?: continue
            val spoken = RolesMoney.spoken(RedistributeModel.magnitude(net), code)
            Text(
                if (net < 0) RolesCopy.owes(name, amount) else RolesCopy.isOwed(name, amount),
                style = Type.display(dark).copy(
                    fontSize = 24.sp, fontWeight = FontWeight(700), color = if (net < 0) Tok.owed(dark) else Tok.owing(dark),
                ),
                modifier = Modifier.semantics {
                    contentDescription = if (net < 0) RolesCopy.owes(name, spoken) else RolesCopy.isOwed(name, spoken)
                },
            )
        }
        Text(RolesCopy.balanceLede(name), style = Type.bodyDim(dark))
        ChoiceCard(RolesCopy.SETTLE_FIRST, RolesCopy.settleFirstSub(name), picked == "settle", dark) { onPick("settle") }
        // Once a payment is in, the balance above has moved: Redistribute is over.
        if (!started) {
            ChoiceCard(RolesCopy.REDISTRIBUTE, RolesCopy.redistributeSub(name), picked == "redistribute", dark) { onPick("redistribute") }
        }
    }
}

@Composable
private fun ChoiceCard(title: String, sub: String, selected: Boolean, dark: Boolean, onClick: () -> Unit) {
    Row(
        // The outline goes on the whole card (outside glassCard's padding), as
        // on iOS; applied after the padding it was drawn inside the card and
        // crossed the second text line (the look, Task 8).
        Modifier
            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .then(if (selected) Modifier.border(2.dp, Tok.accent(dark), ThemeArt.cardShape(Appearance.theme)) else Modifier)
            // D-234 wave (decision 102): iOS pads this card 14.
            .glassCard(dark, padding = 14),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Tok.accent(dark), unselectedColor = Tok.textDim(dark)),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.label(dark, 16))
            Text(sub, style = Type.bodyDim(dark).copy(fontSize = 13.sp))
        }
    }
}

/** The payments that clear the balance, recorded, then the removal. */
@Composable
private fun SettleFirst(
    c: RemovalContext, settlements: List<SettlementDTO>, dark: Boolean, busy: Boolean, error: String?,
    warned: MutableState<Boolean>, onBack: () -> Unit, onRecord: () -> Unit,
) {
    // D-224 (Design 3): a red-button screen is the removal's question: it warns when it first appears (the flow's latch).
    HapticWarning(true, warned)
    val name = c.member.name
    FlowScreen(
        dark, RolesCopy.balanceTitle(name), onBack = onBack,
        actions = {
            PrimaryAction(RolesCopy.recordAndRemove(name), dark, enabled = !busy, tint = Tok.owed(dark), onClick = onRecord)
            SecondaryAction(RolesCopy.CANCEL, dark, enabled = !busy, onClick = onBack)
        },
    ) {
        Text(RolesCopy.RECORD_PAYMENTS_TITLE, style = titleStyle(dark))
        // D-234 wave (decision 102): iOS pads this card 14.
        Column(Modifier.glassCard(dark, padding = 14), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in settlements) {
                val amount = RolesMoney.format(s.amountMinor, s.currencyCode) ?: continue
                Text(RolesCopy.paid(c.who(s.from, first = true), c.who(s.to), amount), style = Type.body(dark))
            }
        }
        error?.let { ErrorLine(it, dark) }
    }
}

/** Who takes the balance, per currency: ticks, equal shares or typed ones,
 *  each person's old and new balance, and whether it adds up. */
@Composable
private fun Redistribute(
    c: RemovalContext, models: List<RedistributeModel>, nets: Map<String, Map<String, Long>>, dark: Boolean, busy: Boolean,
    error: String?, warned: MutableState<Boolean>, onChange: (List<RedistributeModel>) -> Unit, onBack: () -> Unit,
    onRemove: () -> Unit,
) {
    val name = c.member.name
    // D-224 (Design 3): a red-button screen is the removal's question: it warns when it first appears (the flow's latch).
    HapticWarning(true, warned)
    val allOk = models.isNotEmpty() && models.all { it.ok }
    FlowScreen(
        dark, RolesCopy.REDISTRIBUTE_TITLE, onBack = onBack,
        actions = {
            PrimaryAction(RolesCopy.removeButton(name), dark, enabled = allOk && !busy, tint = Tok.owed(dark), onClick = onRemove)
            Text(
                RolesCopy.recordedFine(c.named.group.name),
                style = Type.bodyDim(dark).copy(fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Text(RolesCopy.redistributeLede(name), style = Type.bodyDim(dark))
        models.forEachIndexed { i, m ->
            RedistributeSection(c, m, nets[m.currency] ?: emptyMap(), models.size > 1, dark) { changed ->
                onChange(models.toMutableList().also { it[i] = changed })
            }
        }
        error?.let { ErrorLine(it, dark) }
    }
}

@Composable
private fun RedistributeSection(
    c: RemovalContext, m: RedistributeModel, old: Map<String, Long>, showCurrency: Boolean, dark: Boolean,
    onChange: (RedistributeModel) -> Unit,
) {
    val code = m.currency
    val byId = m.shares().associate { it.id to it.amountMinor }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showCurrency) SectionLabel(code, dark)
        // D-234 wave (decision 102): iOS pads this card 12.
        Column(Modifier.glassCard(dark, padding = 12)) {
            m.candidates.forEachIndexed { n, id ->
                if (n > 0) HorizontalDivider(color = Tok.hairline(dark))
                RedistributePerson(c, m, id, old[id] ?: 0L, byId[id] ?: 0L, dark, onChange)
            }
        }
        RedistributeTally(m, dark)
        TextButton(
            onClick = { onChange(if (m.custom) m.stopEditing() else m.startEditing()) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) {
            Text(
                if (m.custom) RolesCopy.SPLIT_EQUALLY else RolesCopy.EDIT_AMOUNTS,
                style = Type.label(dark, 15).copy(color = Tok.groundAccent(dark)),
            )
        }
    }
}

@Composable
private fun RedistributePerson(
    c: RemovalContext, m: RedistributeModel, id: String, oldNet: Long, share: Long, dark: Boolean,
    onChange: (RedistributeModel) -> Unit,
) {
    val code = m.currency
    val name = c.named.members.firstOrNull { it.id == id }?.name ?: ""
    val ticked = id in m.ticked
    val newNet = oldNet + (if (ticked) share else 0L)
    fun word(net: Long): String? = RolesMoney.format(RedistributeModel.magnitude(net), code)?.let { RolesCopy.rowBalance(net, it) }
    val sub = if (ticked && !m.custom) RolesMoney.format(RedistributeModel.magnitude(share), code)?.let { RolesCopy.takes(it) }
    else word(oldNet)?.let { RolesCopy.rowNow(it) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(
            checked = ticked, onCheckedChange = { onChange(m.toggle(id)) },
            colors = CheckboxDefaults.colors(checkedColor = Tok.accent(dark), uncheckedColor = Tok.textDim(dark), checkmarkColor = Tok.onAccent(dark)),
            modifier = Modifier.semantics { contentDescription = name },
        )
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.label(dark, 15))
            if (sub != null) Text(sub, style = Type.bodyDim(dark).copy(fontSize = 12.sp))
        }
        if (m.custom) {
            OutlinedTextField(
                value = m.typed[id] ?: "",
                onValueChange = { text -> onChange(m.type(id, text.filter { it.isDigit() || it == '.' || it == ',' }.take(24))) },
                enabled = ticked,
                singleLine = true,
                textStyle = Type.numeric(15, FontWeight.SemiBold, dark),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(112.dp).semantics { contentDescription = name },
            )
        } else {
            val before = if (ticked && share != 0L) word(oldNet) else null
            val after = word(newNet)
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = listOfNotNull(before, after).joinToString(", ")
                },
            ) {
                if (before != null) {
                    Text(
                        before,
                        style = Type.bodyDim(dark).copy(fontSize = 11.sp, textDecoration = TextDecoration.LineThrough),
                    )
                }
                if (after != null) {
                    Text(
                        after,
                        style = Type.numericColored(
                            14, if (newNet < 0) Tok.owed(dark) else if (newNet > 0) Tok.owing(dark) else Tok.textDim(dark),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RedistributeTally(m: RedistributeModel, dark: Boolean) {
    val code = m.currency
    val (text, good) = when (val st = m.status) {
        RedistributeModel.Status.SplitEqually -> RolesCopy.SPLIT_EQUALLY to true
        RedistributeModel.Status.AddsUp -> RolesCopy.ADDS_UP to true
        is RedistributeModel.Status.Left -> RolesMoney.format(st.minor, code)?.let { RolesCopy.left(it) } to false
        is RedistributeModel.Status.TooMuch -> RolesMoney.format(st.minor, code)?.let { RolesCopy.tooMuch(it) } to false
        RedistributeModel.Status.TypeAmounts -> RolesCopy.TYPE_AMOUNTS to false
        RedistributeModel.Status.TickOne -> RolesCopy.TICK_ONE to false
    }
    val assigned = RolesMoney.format(m.assigned, code)
    val whole = RolesMoney.format(RedistributeModel.magnitude(m.balance), code)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (text != null) {
            Text(text, style = Type.label(dark, 13).copy(color = if (good) Tok.owing(dark) else Tok.owed(dark)))
        }
        Spacer(Modifier.weight(1f))
        if (assigned != null && whole != null) {
            Text(RolesCopy.ofTotal(assigned, whole), style = Type.label(dark, 13).copy(color = Tok.textDim(dark)))
        }
    }
}
