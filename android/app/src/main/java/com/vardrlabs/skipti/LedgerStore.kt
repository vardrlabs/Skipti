package com.vardrlabs.skipti

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import uniffi.skipti_core.ConversionInput
import uniffi.skipti_core.ExpenseInput
import uniffi.skipti_core.GroupWire
import uniffi.skipti_core.HlcStamp
import uniffi.skipti_core.IdAmount
import uniffi.skipti_core.LedgerSnapshot
import uniffi.skipti_core.PortConversion
import uniffi.skipti_core.PortExpense
import uniffi.skipti_core.PortGroup
import uniffi.skipti_core.PortRedistribution
import uniffi.skipti_core.PortSettlement
import uniffi.skipti_core.RedistributionInput
import uniffi.skipti_core.SkiptiVault
import uniffi.skipti_core.StoreException
import uniffi.skipti_core.ExpenseShare
import uniffi.skipti_core.ffiCreateGroup
import uniffi.skipti_core.MemberWire
import uniffi.skipti_core.NamedGroupWire
import uniffi.skipti_core.ffiAddMember
import uniffi.skipti_core.ffiMemberHasHistory
import uniffi.skipti_core.ffiCreateNamedGroup
import uniffi.skipti_core.ffiMemberName
import uniffi.skipti_core.ffiNamedGroup
import uniffi.skipti_core.ffiNewParticipantId
import uniffi.skipti_core.ffiRenameMember
import uniffi.skipti_core.CoreException
import uniffi.skipti_core.GroupOpIn
import uniffi.skipti_core.GroupOpMemberIn
import uniffi.skipti_core.GroupOpRecordIn
import uniffi.skipti_core.GroupsProjectionOut
import uniffi.skipti_core.ffiEncodeGroupOp
import uniffi.skipti_core.ffiProjectGroups
import uniffi.skipti_core.ffiSnapshotForGroup

/**
 * The vault-backed ledger source: port of ios/Skipti/App/LedgerStore.swift
 * for the expense list and the balance screen's vault path. Write the
 * ledger, close, REOPEN, read back, decode: the UI renders what was
 * persisted, never literals.
 *
 * The vault stores opaque bytes; this JSON envelope is app-layer
 * serialization (core owns money semantics, the app owns its persistence
 * envelope), deliberately NOT core's LedgerSnapshot, which is §9.2's
 * portable backup format with a version tag; coupling the two would make
 * every export-format change a vault migration.
 *
 * Serialization is kotlinx.serialization (D-109's decision): the idiomatic
 * JSON codec that runs in plain JVM tests: android.jar's org.json throws
 * "not mocked" there, which would break a GATE leg now that D-108 runs the
 * JVM suite in every full gate. The compose plugin already proved a Kotlin
 * compiler plugin applies under AGP 9's built-in Kotlin; the serialization
 * plugin applies the same way (checked FIRST, as D-109 records).
 *
 * TOLERANCE IS THE CONTRACT: note/timestampMs/category are optional with
 * defaults precisely so vault JSON written before they existed still loads
 * (Swift's decoder tolerates missing keys; ignoreUnknownKeys mirrors that
 * tolerance in the other direction, and explicitNulls=false keeps the
 * written envelope in Swift's omit-nil shape).
 */
@Serializable
data class ExpenseDTO(
    val payer: String,
    val paidMinor: Long,
    val currencyCode: String,
    val group: String? = null,
    val conversion: ConversionDTO? = null,
    val shares: List<ShareDTO> = emptyList(),
    /** §5.7: every record shows what it was and when. Optional for tolerance. */
    val note: String? = null,
    val timestampMs: Long? = null,
    /** §8.1's category id (a merki leaf). Optional for the same reason. */
    val category: String? = null,
    /** §5.7's "added by" (D-141): the participant id of whoever added it.
     *  Optional: expenses from before D-141 have none. */
    val addedBy: String? = null,
) {
    @Serializable
    data class ShareDTO(val id: String, val amountMinor: Long)
}

@Serializable
data class ConversionDTO(
    val convertedMinor: Long,
    val convertedCurrency: String,
    val rateNum: Long,
    val rateDen: Long,
    /** Milliseconds; positive timestamps fit Int64's positive range, and the
     *  FFI boundary widens to ULong where core asks for it. */
    val rateTimestampMs: Long,
)

@Serializable
data class GroupDTO(
    val id: String,
    val name: String,
    val settlementCurrency: String,
    val members: List<String>,
    /** D-136: member names in member order; null in groups written before
     *  names existed, which are named on read (see [LedgerStore.namedGroups]). */
    val memberNames: List<MemberDTO>? = null,
)

/** A member as stored, in the group's member order (D-136). */
@Serializable
data class MemberDTO(val id: String, val name: String)

/** This device's own participant (D-136). `name` stays null until the person
 *  types it; nothing is shown or shared under an invented name. */
@Serializable
data class SelfDTO(
    val id: String,
    val name: String? = null,
    /** D-153: other ids this account's devices wrote as "you" before they met
     *  (the founder: one "you" per account). Until the merge unit they only
     *  let a group made under one of them show it as "(You)". */
    val aliases: List<String>? = null,
)

/** A group needs your name first ("Tim (You)"). */
class NeedsYourNameException : Exception("a group needs your name first")

class UnknownGroupException : Exception("unknown group")

/** D-141: a revision names an expense this vault does not hold. */
class UnknownExpenseException : Exception("unknown expense")

@Serializable
data class SettlementDTO(
    val from: String,
    val to: String,
    val amountMinor: Long,
    val currencyCode: String,
    val group: String? = null,
)

/** D-210: the kv shape of a redistribution (core's own codec carries the
 *  record): a removed person's balance moved onto the members chosen. */
@Serializable
data class RedistributionDTO(
    val group: String,
    val from: String,
    val currencyCode: String,
    val shares: List<ExpenseDTO.ShareDTO> = emptyList(),
) {
    fun toInput(): RedistributionInput = RedistributionInput(
        group = group, from = from, currencyCode = currencyCode,
        shares = shares.map { IdAmount(id = it.id, amountMinor = it.amountMinor) },
    )

    companion object {
        fun of(r: RedistributionInput): RedistributionDTO = RedistributionDTO(
            group = r.group, from = r.from, currencyCode = r.currencyCode,
            shares = r.shares.map { ExpenseDTO.ShareDTO(id = it.id, amountMinor = it.amountMinor) },
        )
    }
}

/** D-210: how a group became a read-only copy: removed by someone, or deleted
 *  by the owner. [atMs] is epoch milliseconds; [byYou]: this phone's own
 *  account did it (the owner who deleted the group). */
@Serializable
data class ReadOnlyDTO(
    val kind: String,
    val by: String,
    val atMs: Long,
    val byYou: Boolean? = null,
) {
    companion object {
        /** D-229 decision 45: the kind of a group whose place is lost. Never
         *  stored: derived from the records each time ([withPlaceLost]), so it
         *  lifts by itself when the group's owner sets People right. */
        const val PLACE_LOST = "placeLost"

        /** The marks as the phone reads them (pure): the stored ones (removed,
         *  deleted; they win), and every group core says the place is lost in. */
        fun withPlaceLost(stored: Map<String, ReadOnlyDTO>, p: GroupsProjectionOut): Map<String, ReadOnlyDTO> {
            val lost = p.groups.filter { it.placeLost && it.group.id !in stored }
            if (lost.isEmpty()) return stored
            return stored + lost.associate { it.group.id to ReadOnlyDTO(PLACE_LOST, "", 0) }
        }
    }
}

/**
 * D-229: the ids this phone writes into a group's RECORDS (never the kv
 * ledger, which stays the merged view of D-154). Core decides, per group,
 * the place the group knows this account by and `youAs`: that place whenever
 * the account's "you" is not readable to the other members there (one
 * account, two phones: the group was made under the other phone's id). Every
 * id of the account ([mine]: "you" and its other ids) is then written as the
 * place; anyone else's id is untouched. A REQUIRED parameter of every record
 * builder and of [LedgerStore.appendGroupOps], so a writer cannot forget the
 * decision: [NONE] is written out where nothing may be mapped (create,
 * addMember, claims, personal records, the backfills and the migrations). With
 * no account, or one "you", `youAs` is none everywhere and nothing changes.
 * D-229 decision 45: a group whose place is lost (core's `placeLost`) is
 * [lost]: every record mapping into it throws [PlaceLost], so nothing is written
 * there (the group is also a read-only copy, [LedgerStore.readOnly]).
 * iOS's twin is LedgerStore.Placing.
 */
class Placing internal constructor(
    /** Group id to the place written there; only groups whose `youAs` is set. */
    private val places: Map<String, String>,
    private val mine: List<String>,
    /** D-229 decision 45: the groups whose place is lost. */
    private val lost: Set<String> = emptySet(),
    /** D-237 (the Fable audit, A1): per group, the places this account claimed THERE (core's aliases to "you"). A
     *  place claimed in another group is someone else's id here (a group's maker can name any id as a member), so
     *  it is never written as this account's place: [mineIn] leaves it out. */
    private val claimed: Map<String, Set<String>> = emptyMap(),
) {
    /** A payment whose two ends become one id (you paying your own place). */
    class SameEnds : Exception("a payment cannot go from a person to themselves")

    /** D-229 decision 45: a record for a group whose place is lost. */
    class PlaceLost : Exception(RolesCopy.PLACE_LOST_BANNER)

    companion object {
        /** Maps nothing. */
        val NONE = Placing(emptyMap(), emptyList())

        fun of(p: GroupsProjectionOut): Placing {
            val you = p.you ?: return NONE
            val places = p.groups.mapNotNull { g -> g.youAs?.let { g.group.id to it } }.toMap()
            val lost = p.groups.filter { it.placeLost }.map { it.group.id }.toSet()
            val claimed = p.aliases.filter { it.toId == you.id }.groupBy({ it.groupId }, { it.fromId }).mapValues { it.value.toSet() }
            return if (places.isEmpty() && lost.isEmpty()) NONE else Placing(places, listOf(you.id) + p.otherYouIds, lost, claimed)
        }

        fun load(store: LedgerStore): Placing = of(store.projectGroups())

        /** The group a group operation is in; none for a personal one. */
        fun groupOf(op: GroupOpIn): String? = when (op) {
            is GroupOpIn.Create -> op.groupId
            is GroupOpIn.AddMember -> op.groupId
            is GroupOpIn.RenameMember -> op.groupId
            is GroupOpIn.RenameGroup -> op.groupId
            is GroupOpIn.RemoveMember -> op.groupId
            is GroupOpIn.GroupPicture -> op.groupId
            is GroupOpIn.Claim -> op.groupId
            is GroupOpIn.GroupKey -> op.groupId
            is GroupOpIn.MemberProfile -> op.groupId
            is GroupOpIn.PlaceAlias -> op.groupId
            is GroupOpIn.HeldPlace -> op.groupId
            is GroupOpIn.You, is GroupOpIn.Security, is GroupOpIn.DeviceName, is GroupOpIn.Avatar,
            is GroupOpIn.Setting -> null
        }
    }

    /** D-229 decision 45 (pure): nothing is written into [group] from this
     *  phone; [expense], [settlement], [revision], [redistribution] and [op]
     *  throw [PlaceLost] for it. */
    fun refuses(group: String?): Boolean = group != null && group in lost

    private fun check(group: String?) {
        if (refuses(group)) throw PlaceLost()
    }

    /** D-237 (A1): the account's ids in [group]: every id of its own, less the places it claimed only elsewhere. */
    internal fun mineIn(group: String): List<String> {
        val here = claimed[group].orEmpty()
        val elsewhere = claimed.filterKeys { it != group }.values.flatten().toSet()
        return mine.filter { it in here || it !in elsewhere }
    }

    /** One id as written into [group]'s records. */
    fun id(id: String, group: String?): String {
        val g = group ?: return id
        val place = places[g] ?: return id
        return if (id in mineIn(g)) place else id
    }

    /** Payer and shares through core (shares that become one are added, in
     *  the first one's position), and who added it. */
    fun expense(e: ExpenseDTO): ExpenseDTO {
        check(e.group)
        val g = e.group ?: return e
        val place = places[g] ?: return e
        val w = uniffi.skipti_core.ffiWriteAsExpense(e.toInput(), place, mineIn(g))
        return e.copy(
            payer = w.payer,
            shares = w.shares.map { ExpenseDTO.ShareDTO(id = it.id, amountMinor = it.amountMinor) },
            addedBy = e.addedBy?.let { id(it, e.group) },
        )
    }

    /** Both ends; throws [SameEnds] when they become one id. */
    fun settlement(s: SettlementDTO): SettlementDTO {
        check(s.group)
        val from = id(s.from, s.group)
        val to = id(s.to, s.group)
        if (from == to && (from != s.from || to != s.to)) throw SameEnds()
        return s.copy(from = from, to = to)
    }

    /** A revision of an expense in [group]: its editor and its new money
     *  (core's rule, as for an expense). The seen list is untouched. */
    fun revision(r: uniffi.skipti_core.ExpenseRevisionIn, group: String?): uniffi.skipti_core.ExpenseRevisionIn {
        check(group)
        val g = group ?: return r
        val place = places[g] ?: return r
        return r.copy(
            editor = r.editor?.let { id(it, g) },
            money = r.money?.let { uniffi.skipti_core.ffiWriteAsExpense(it, place, mineIn(g)) },
        )
    }

    /** From and shares through core; refused when a share would land on
     *  the person it is taken from. */
    fun redistribution(r: RedistributionInput): RedistributionInput {
        check(r.group)
        val place = places[r.group] ?: return r
        return uniffi.skipti_core.ffiWriteAsRedistribution(r, place, mineIn(r.group))
    }

    /** Only `renameMember` and `removeMember` name a person to map; every
     *  other operation is returned as it is. */
    fun op(op: GroupOpIn): GroupOpIn = if (refuses(groupOf(op))) throw PlaceLost() else when (op) {
        is GroupOpIn.RenameMember -> op.copy(memberId = id(op.memberId, op.groupId))
        is GroupOpIn.RemoveMember -> op.copy(memberId = id(op.memberId, op.groupId))
        else -> op
    }
}

/** The vault's bytes do not decode: a broken writer, never an empty list. */
class LedgerCorruptException : Exception("ledger vault envelope is corrupt")

class LedgerStore(
    val path: String,
    /** Injectable so JVM tests drive the real vault through a test keystore
     *  (VaultGate needs an Android context; the app default goes through it). */
    private val openVault: () -> SkiptiVault = { VaultGate.open(path) },
) {
    companion object Sync {
        const val EXPENSES_KEY = "ledger/expenses"
        const val GROUPS_KEY = "ledger/groups"
        /** D-190: what the background check already told you. */
        const val NOTIFIED_UP_TO_KEY = "notify/up-to"
        const val TOLD_PHONES_KEY = "notify/phones"
        /** D-187: each group's picture, rebuilt from the records with the groups. */
        const val PICTURES_KEY = "ledger/group-pictures"
        const val SETTLEMENTS_KEY = "ledger/settlements"
        /** D-210: the balances moved off removed people, in HLC order. */
        const val REDISTRIBUTIONS_KEY = "ledger/redistributions"
        /** D-210: the groups that are read-only copies on this phone. */
        const val READ_ONLY_KEY = "ledger/readonly"
        const val FX_KEY = "fx/rates"
        const val SELF_KEY = "ledger/self"
        /** D-211: set once the picture is a record: written by `saveAvatar`, or
         *  by the one-time migration of a picture the vault kept before. */
        const val AVATAR_RECORD_KEY = "profile.avatar-record"
        const val SELECTED_GROUP_KEY = "ledger/selected-group"

        /** The pre-D-136 fixture people, kept ONLY to migrate vaults the old
         *  seeding wrote; nothing writes these ids into a new vault. */
        val LEGACY_ADA: String = "a".repeat(31) + "1"
        val LEGACY_NAMES: Map<String, String> = mapOf(
            "a".repeat(31) + "2" to "Rahul",
            "a".repeat(31) + "3" to "Priya",
            "a".repeat(31) + "4" to "Amit",
        )

        /** A person this phone has no name for (a line about someone it never held). */
        const val UNKNOWN_NAME = "Unknown member"

        /** "Tim (You)" for yourself, "Tim" for everyone else. Display copy for
         *  this device only; the stored and shared name is "Tim". */
        fun label(memberId: String, named: NamedGroupWire): String {
            val member = named.members.firstOrNull { it.id == memberId } ?: return UNKNOWN_NAME
            return if (memberId == named.selfId) "${member.name} (You)" else member.name
        }

        /** [label] for a row of the group's history: a person the named group no
         *  longer holds (removed) is named from [history] ([historyNames]),
         *  "Unknown member" only when even that has no name. Who is still here
         *  reads exactly as [label]. */
        fun label(memberId: String, named: NamedGroupWire, history: Map<String, String>): String =
            if (named.members.any { it.id == memberId }) label(memberId, named) else history[memberId] ?: UNKNOWN_NAME

        /** D-210 last wave: the names a group's Who owes whom card reads, by id:
         *  everyone still here as [label], and every removed person the history
         *  can name, so a removed person with a balance is never "Unknown
         *  member". An id with no name anywhere is left out (the card says so). */
        fun balanceNames(named: NamedGroupWire, history: Map<String, String>): Map<String, String> =
            (named.members.map { it.id } + history.keys).distinct().associateWith { label(it, named, history) }

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private val expenseList = ListSerializer(ExpenseDTO.serializer())
        private val groupList = ListSerializer(GroupDTO.serializer())
        private val pictureMap = kotlinx.serialization.builtins.MapSerializer(
            String.serializer(), PictureDTO.serializer(),
        )
        private val previewMap = kotlinx.serialization.builtins.MapSerializer(
            String.serializer(),
            kotlinx.serialization.builtins.MapSerializer(String.serializer(), PictureDTO.serializer()),
        )

        /** D-211: the member previews' kv bytes (`photoHex` holds a member's
         *  PREVIEW, never a full picture). Throws on bytes that do not decode. */
        fun decodePreviews(bytes: ByteArray): Map<String, Map<String, PictureDTO>> =
            json.decodeFromString(previewMap, bytes.decodeToString())

        /** D-211: the picture the export carries: the records' when they give
         *  one, else the kv copy. A projection that fails never fails the
         *  export (GUARANTEES #4: everything else still goes out): the kv copy,
         *  which the last good rebuild wrote, stands in for it. */
        /** The picture as the export carries it: one core would refuse (an old
         *  kept photo over 64,000 bytes, or not a JPEG, before the migration
         *  made it a record) is left out, so the file is still written with
         *  everything else (final review M1, GUARANTEES #4). Core's own check,
         *  the one the export runs. */
        fun exportable(picture: Avatar): Avatar {
            val (emoji, photoHex) = picture.recordParts
            return try {
                ffiEncodeGroupOp(GroupOpIn.Avatar(emoji, photoHex))
                picture
            } catch (_: CoreException) {
                Avatar.None
            }
        }

        /** The stamp of a migrated picture: below every stamp a phone makes
         *  (its wall clock), so it never beats a picture set deliberately. */
        val MIGRATED_HLC = HlcStamp(1uL, 0u)

        fun exportedPicture(projected: () -> uniffi.skipti_core.GroupPictureOut?, kept: Avatar): Avatar {
            try {
                projected()?.let { return Avatar.from(it.emoji, it.photoHex) }
            } catch (_: Exception) {
            }
            return kept
        }

        /**
         * The envelope decode, public because it IS the cross-shell contract:
         * the committed golden bytes decode through this in Kotlin and through
         * JSONDecoder in Swift (see LedgerStoreTest /
         * BalanceLineTests.testCrossShellVaultEnvelopeGoldenDecodesIdentically).
         */
        fun decodeEnvelope(bytes: ByteArray): List<ExpenseDTO> = try {
            json.decodeFromString(expenseList, bytes.decodeToString())
        } catch (_: Exception) {
            throw LedgerCorruptException()
        }

        const val DEVICE_KEY = "sync/device-id"
        const val HLC_KEY = "sync/hlc-state"
        /** D-141: an edit or tombstone of an expense (straumr 0x05). */
        const val REVISION_TYPE: UByte = 5u
        /** D-153: a group operation or "you" (straumr 0x03, D-152). */
        const val GROUP_OP_TYPE: UByte = 3u

        /** D-235 round 3 (decision 112): core's `TEXT_MAX_CHARS` (straumr/check.rs; a JVM test reads it there), the
         *  longest note a record carries, in characters (Unicode scalars, as core counts). */
        const val TEXT_MAX_CHARS = 1_000

        /** Characters as core counts them: code points, so an emoji is one (a String's length counts it as two). */
        fun textChars(text: String): Int = text.codePointCount(0, text.length)

        /** Refuses free text past [TEXT_MAX_CHARS] before anything is written, with core's own error and words, so
         *  the screens show their existing save error. */
        fun checkText(text: String?) {
            if (text != null && textChars(text) > TEXT_MAX_CHARS) {
                throw uniffi.skipti_core.CoreException.InvalidInput("a text longer than $TEXT_MAX_CHARS characters")
            }
        }
        /** D-210: a removed person's balance moved onto chosen members
         *  (core's norn::redistribution, straumr 0x06). */
        const val REDISTRIBUTION_TYPE: UByte = 6u
        /** D-141: set once the legacy backfill has run. */
        const val BACKFILLED_KEY = "sync/legacy-backfilled"
        private val random = java.security.SecureRandom()
        private val deviceIdLock = Any()

        /** D-235 (Android audit M3): the HLC state is read, ticked and written in separate vault sessions. Two writers
         *  at once (a save and a sync arrival, ProfileShare, GroupJobs) could read the same state and mint ONE stamp
         *  twice; a record's identity is its stamp, so the second put REPLACES the first (an expense silently gone).
         *  Every read-modify-write of the state holds this lock, as [deviceId] holds its own. */
        private val hlcLock = Any()
        private val dtoJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        private val settlementListSerializer = ListSerializer(SettlementDTO.serializer())
        private val redistributionListSerializer = ListSerializer(RedistributionDTO.serializer())

        /** D-210: the type-6 records as redistributions, in HLC order (millis,
         *  counter, device), so every phone shows the same pairwise cards. A
         *  record core cannot decode is skipped, as an unreadable settlement is. */
        fun redistributions(records: List<uniffi.skipti_core.RecordWire>): List<RedistributionDTO> = records
            .filter { it.recordType == REDISTRIBUTION_TYPE }
            .sortedWith(compareBy<uniffi.skipti_core.RecordWire> { it.hlcMillis }.thenBy { it.hlcCounter }.thenBy { it.hlcDeviceId })
            .mapNotNull { r ->
                try {
                    RedistributionDTO.of(uniffi.skipti_core.ffiDecodeRedistribution(r.blob))
                } catch (_: Exception) {
                    null
                }
            }

        /**
         * This device's 16-byte identity, generated once and persisted:
         * the HLC's tie-breaker and the push-set's author filter. NEVER a
         * participant id: the old placeholder stamped settlements with the
         * payer's participant id, which conflated who paid with which
         * device wrote.
         */
        /** D-247 (decision 170): the groups this phone created: a create record authored by this device (its id, never
         *  another phone's of the same account). Read only: a vault with no device id yet authored nothing (no id is
         *  made here). A record core cannot decode is passed over. The creator fall-back's input ([CreatorFallBack]),
         *  display only. The twin of iOS's `LedgerStore.Sync.createdHere`. */
        fun createdHere(store: LedgerStore): Set<String> {
            val device = store.rawKvGet(DEVICE_KEY)?.let { HexCodec.encode(it) } ?: return emptySet()
            return store.allRecords()
                .filter { it.recordType == GROUP_OP_TYPE && it.authorDeviceId == device }
                .mapNotNull { (runCatching { uniffi.skipti_core.ffiDecodeGroupOp(it.blob) }.getOrNull() as? uniffi.skipti_core.GroupOpIn.Create)?.groupId }
                .toSet()
        }

        fun deviceId(store: LedgerStore): String = synchronized(deviceIdLock) {
            store.rawKvGet(DEVICE_KEY)?.let { return HexCodec.encode(it) }
            val fresh = ByteArray(16).also { random.nextBytes(it) }
            store.rawKvSet(DEVICE_KEY, fresh)
            HexCodec.encode(fresh)
        }

        /**
         * The device's next HLC stamp, computed by CORE's tick rule
         * (ffiHlcNext, no artificial latency; the counter breaks ties
         * within one millisecond) and persisted BEFORE use in the same
         * vault session. Stamps happen AT CREATION, which is why an
         * offline device's records slot into their timestamp position on
         * reconnect instead of appending to the end.
         */
        fun nextHlc(store: LedgerStore, nowMs: Long): HlcStamp = synchronized(hlcLock) {
            val state = store.rawKvGet(HLC_KEY)
            var millis = 0L
            var counter = 0u
            if (state != null && state.size == 12) {
                millis = u64FromBE(state)
                counter = u32FromBE(state.copyOfRange(8, 12))
            }
            val next = uniffi.skipti_core.ffiHlcNext(
                nowMs.toULong(), millis.toULong(), counter,
            )
            store.rawKvSet(
                HLC_KEY,
                u64BE(next.millis.toLong()) + u32BE(next.counter.toInt()),
            )
            next
        }

        /** Merges a REMOTE record's HLC into this device's state on pull,
         *  through core's receive rule: the next local stamp then sorts
         *  after everything the device has seen. */
        fun receiveHlc(store: LedgerStore, millis: ULong, counter: UInt, nowMs: Long) = synchronized(hlcLock) {
            val state = store.rawKvGet(HLC_KEY)
            var sMillis = 0L
            var sCounter = 0u
            if (state != null && state.size == 12) {
                sMillis = u64FromBE(state)
                sCounter = u32FromBE(state.copyOfRange(8, 12))
            }
            val merged = uniffi.skipti_core.ffiHlcReceive(
                sMillis.toULong(), sCounter, millis, counter, nowMs.toULong(),
            )
            store.rawKvSet(
                HLC_KEY,
                u64BE(merged.millis.toLong()) + u32BE(merged.counter.toInt()),
            )
        }

        /** D-192: moved here from the relay's LedgerSync (the HLC state's
         *  millis half). */
        fun u64BE(value: Long): ByteArray {
            val out = ByteArray(8)
            for (i in 0 until 8) out[i] = (value ushr ((7 - i) * 8)).toByte()
            return out
        }

        fun u64FromBE(data: ByteArray): Long {
            var v = 0L
            for (i in 0 until minOf(8, data.size)) {
                v = (v shl 8) or (data[i].toLong() and 0xff)
            }
            return v
        }

        fun u32BE(value: Int): ByteArray {
            val out = ByteArray(4)
            for (i in 0 until 4) out[i] = (value ushr ((3 - i) * 8)).toByte()
            return out
        }

        fun u32FromBE(data: ByteArray): UInt =
            ((data[0].toUInt() and 0xffu) shl 24) or
                ((data[1].toUInt() and 0xffu) shl 16) or
                ((data[2].toUInt() and 0xffu) shl 8) or
                (data[3].toUInt() and 0xffu)

        /** One record's identity: its HLC. Two devices creating records in
         *  the same millisecond still differ by counter and device id. */
        fun recordIdentity(record: uniffi.skipti_core.RecordWire): String =
            "${record.hlcMillis}:${record.hlcCounter}:${record.hlcDeviceId}"

        private fun encodeDto(dto: ExpenseDTO): ByteArray =
            dtoJson.encodeToString(ExpenseDTO.serializer(), dto).toByteArray(Charsets.UTF_8)

        private fun encodeDto(dto: SettlementDTO): ByteArray =
            dtoJson.encodeToString(SettlementDTO.serializer(), dto).toByteArray(Charsets.UTF_8)

        /**
         * The record's nonce session is a CONSTANT ("EXP\x01" / "SET\x01"):
         * a header field, never nonce material. Nothing seals records since
         * the relay went (D-192: Firebase stores them as documents; D-200
         * removed the sealing code from core). If records are ever sealed
         * again, the seal must not derive its nonce from this session and
         * hlc.counter (which resets every millisecond): with a constant
         * session that would reuse nonces.
         */
        fun expenseRecord(
            expense: ExpenseDTO,
            hlc: HlcStamp,
            deviceId: String,
            placing: Placing,
        ): uniffi.skipti_core.RecordWire = uniffi.skipti_core.RecordWire(
            version = 1u,
            recordType = 1u, // straumr RecordType.expense
            groupId = expense.group,
            epoch = 0u,
            hlcMillis = hlc.millis,
            hlcCounter = hlc.counter,
            hlcDeviceId = deviceId,
            authorDeviceId = deviceId,
            nonceSession = byteArrayOf(0x45, 0x58, 0x50, 0x01), // "EXP\x01"
            flags = 0u,
            // D-229: the record names the place its group knows this account by.
            blob = encodeDto(placing.expense(expense)),
        )

        /** Same for a settlement. */
        fun settlementRecord(
            settlement: SettlementDTO,
            hlc: HlcStamp,
            deviceId: String,
            placing: Placing,
        ): uniffi.skipti_core.RecordWire = uniffi.skipti_core.RecordWire(
            version = 1u,
            recordType = 2u, // straumr RecordType.settlement
            groupId = settlement.group,
            epoch = 0u,
            hlcMillis = hlc.millis,
            hlcCounter = hlc.counter,
            hlcDeviceId = deviceId,
            authorDeviceId = deviceId,
            nonceSession = byteArrayOf(0x53, 0x45, 0x54, 0x01), // "SET\x01"
            flags = 0u,
            // D-229: as for an expense; throws when its two ends become one id.
            blob = encodeDto(placing.settlement(settlement)),
        )

        /** D-153: one group operation as a record. Group operations take
         *  their group's scope; "you" takes PERSONAL scope (groupId null), so
         *  it can never be shared into a group's log. "GRP\x01": the same
         *  constant-session rule as "EXP\x01" above (never nonce material). */
        fun groupOpRecord(
            op: GroupOpIn,
            hlc: HlcStamp,
            deviceId: String,
        ): uniffi.skipti_core.RecordWire = uniffi.skipti_core.RecordWire(
            version = 1u,
            recordType = GROUP_OP_TYPE,
            groupId = when (op) {
                is GroupOpIn.Create -> op.groupId
                is GroupOpIn.AddMember -> op.groupId
                is GroupOpIn.RenameMember -> op.groupId
                is GroupOpIn.RenameGroup -> op.groupId
                is GroupOpIn.RemoveMember -> op.groupId
                is GroupOpIn.GroupPicture -> op.groupId
                is GroupOpIn.Claim -> op.groupId
                is GroupOpIn.MemberProfile -> op.groupId
                // D-229: a server-verified alias is a group's, as a profile is.
                is GroupOpIn.PlaceAlias -> op.groupId
                // D-230: and so is a server-held place.
                is GroupOpIn.HeldPlace -> op.groupId
                // D-158: a group key is personal too: the account's own log
                // only, sealed with the account key.
                // D-178: and so is the account's Maximum security choice.
                is GroupOpIn.You, is GroupOpIn.GroupKey, is GroupOpIn.Security,
                // D-185: and so is each phone's name. D-211: and your picture.
                is GroupOpIn.Avatar,
                // D-244 (ADR-0043): and each setting that follows the account.
                is GroupOpIn.Setting,
                is GroupOpIn.DeviceName -> null
            },
            epoch = 0u,
            hlcMillis = hlc.millis,
            hlcCounter = hlc.counter,
            hlcDeviceId = deviceId,
            authorDeviceId = deviceId,
            nonceSession = byteArrayOf(0x47, 0x52, 0x50, 0x01), // "GRP\x01"
            flags = 0u,
            blob = ffiEncodeGroupOp(op),
        )

        /** The operations that turn [before] into [after] (D-153; iOS:
         *  LedgerStore.operations). A group with no create record yet (kept
         *  only in kv) is written whole, as a create, so no change to it is
         *  lost. Pure, so it is tested alone. */
        fun operations(
            before: List<NamedGroupWire>,
            after: List<NamedGroupWire>,
            created: Set<String>,
        ): List<GroupOpIn> {
            val ops = mutableListOf<GroupOpIn>()
            for (group in after) {
                val id = group.group.id
                val old = before.firstOrNull { it.group.id == id }
                if (id !in created || old == null) {
                    ops += GroupOpIn.Create(
                        id, group.group.name, group.group.settlementCurrency,
                        group.members.map { GroupOpMemberIn(it.id, it.name) },
                    )
                    continue
                }
                if (old.group.name != group.group.name) {
                    ops += GroupOpIn.RenameGroup(id, group.group.name)
                }
                for (member in group.members) {
                    val was = old.members.firstOrNull { it.id == member.id }
                    if (was == null) {
                        ops += GroupOpIn.AddMember(id, GroupOpMemberIn(member.id, member.name))
                    } else if (was.name != member.name) {
                        ops += GroupOpIn.RenameMember(id, member.id, member.name)
                    }
                }
            }
            return ops
        }
    }

    private fun <T> withVault(block: (SkiptiVault) -> T): T {
        val vault = openVault()
        try {
            return block(vault)
        } finally {
            try { vault.closeVault() } catch (_: StoreException) {}
        }
    }

    private fun decodeExpenses(data: ByteArray?): List<ExpenseDTO> {
        data ?: return emptyList()
        return decodeEnvelope(data)
    }

    private fun encodeExpenses(expenses: List<ExpenseDTO>): ByteArray =
        json.encodeToString(expenseList, expenses).toByteArray(Charsets.UTF_8)

    /** Raw kv access for the sync layer: the envelope packs these keys'
     *  bytes verbatim (base64 inside the sealed JSON), so a restore writes
     *  back byte-for-byte what was pushed (§5.13). */
    fun rawKvGet(key: String): ByteArray? = withVault { vault ->
        vault.kvGet(key)
    }

    fun rawKvSet(key: String, bytes: ByteArray) = withVault { vault ->
        vault.kvSet(key, bytes)
    }

    /** Every record in the vault, in the record path's own HLC order. */
    fun allRecords(): List<uniffi.skipti_core.RecordWire> = withVault { vault ->
        vault.allRecords()
    }

    /**
     * D-141: records → core's projection. Creations decode from their shell
     * JSON; revisions go to core as raw bytes (core alone reads them). A
     * creation blob this build cannot decode is skipped, as the projection
     * always has (it still takes part in the merge).
     */
    fun project(records: List<uniffi.skipti_core.RecordWire>): uniffi.skipti_core.ExpenseProjectionOut {
        val created = mutableListOf<uniffi.skipti_core.ExpenseCreatedIn>()
        val revisions = mutableListOf<uniffi.skipti_core.ExpenseRevisionRecordIn>()
        for (record in records) {
            when (record.recordType) {
                1u.toUByte() -> {
                    val e = try {
                        json.decodeFromString(ExpenseDTO.serializer(), record.blob.decodeToString())
                    } catch (_: Exception) {
                        continue
                    }
                    created += uniffi.skipti_core.ExpenseCreatedIn(
                        hlcMillis = record.hlcMillis,
                        hlcCounter = record.hlcCounter,
                        hlcDeviceId = record.hlcDeviceId,
                        expense = e.toInput(),
                        note = e.note,
                        category = e.category,
                        timestampMs = e.timestampMs?.toULong(),
                        addedBy = e.addedBy,
                    )
                }
                REVISION_TYPE -> revisions += uniffi.skipti_core.ExpenseRevisionRecordIn(
                    hlcMillis = record.hlcMillis,
                    hlcCounter = record.hlcCounter,
                    hlcDeviceId = record.hlcDeviceId,
                    blob = record.blob,
                    scope = record.groupId,
                )
            }
        }
        val projected = uniffi.skipti_core.ffiProjectExpenses(created, revisions)
        // D-154: one "you" per account. Ids another device wrote as "you"
        // read as the account's "you" in every expense people see.
        val yous = yous(records) ?: return projected
        return projected.copy(
            expenses = uniffi.skipti_core.ffiMergeYouExpenses(
                projected.expenses, yous.you, yous.others, yous.aliases,
            ),
        )
    }

    /** D-154: the account's "you", the other ids its devices wrote as "you",
     *  and (D-158) the other people who joined a group (their "you" reads as
     *  the member they claimed, in that group); null when there is nothing
     *  to merge. */
    data class Yous(val you: String, val others: List<String>, val aliases: List<uniffi.skipti_core.IdAlias>)

    fun yous(records: List<uniffi.skipti_core.RecordWire>): Yous? {
        val p = ffiProjectGroups(
            records.filter { it.recordType == GROUP_OP_TYPE }.map {
                GroupOpRecordIn(it.hlcMillis, it.hlcCounter, it.hlcDeviceId, it.blob)
            },
        )
        val you = p.you ?: return null
        return if (p.otherYouIds.isEmpty() && p.aliases.isEmpty()) null else Yous(you.id, p.otherYouIds, p.aliases)
    }

    /**
     * D-141: every expense as core projects it (edits and tombstones
     * applied, deleted ones INCLUDED and flagged), with its id, "added by",
     * "last edited by" and any money conflict. The legacy backfill runs
     * first, so a pre-record vault's rows are in it.
     */
    fun projectedExpenses(): uniffi.skipti_core.ExpenseProjectionOut {
        backfillLegacyRecords()
        return project(allRecords())
    }

    /**
     * D-141: writes one revision (an edit, a delete or an Undo) of the
     * expense `revision.target`, then rebuilds the kv ledger. The record
     * takes its expense's group scope and is stamped and authored like any
     * other local record, so the next push carries it.
     */
    fun reviseExpense(
        revision: uniffi.skipti_core.ExpenseRevisionIn,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        // D-235 round 3: an edit's note within core's bound, before anything is read or written.
        checkText(revision.note)
        val target = projectedExpenses().expenses.firstOrNull { it.id == revision.target }
            ?: throw UnknownExpenseException()
        // D-229: the editor and the new money name the place its group knows
        // this account by (the seen list is untouched).
        val written = Placing.load(this).revision(revision, target.expense.group)
        val blob = uniffi.skipti_core.ffiEncodeExpenseRevision(written)
        val hlc = Sync.nextHlc(this, nowMs)
        val device = Sync.deviceId(this)
        withVault { vault ->
            vault.putRecord(
                uniffi.skipti_core.RecordWire(
                    version = 1u,
                    recordType = REVISION_TYPE,
                    groupId = target.expense.group,
                    epoch = 0u,
                    hlcMillis = hlc.millis,
                    hlcCounter = hlc.counter,
                    hlcDeviceId = device,
                    authorDeviceId = device,
                    // "REV\x01": the same constant-session rule as "EXP\x01"
                    // (a header field, never nonce material; nothing seals records).
                    nonceSession = byteArrayOf(0x52, 0x45, 0x56, 0x01),
                    flags = 0u,
                    blob = blob,
                ),
            )
        }
        rebuildProjection()
    }

    /**
     * Applies one record from the wire: idempotent by identity (the
     * store's unique HLC index replaces, never duplicates). Returns
     * whether it was NEW: a re-applied record is convergence, not news.
     */
    /** D-194: the records not in the vault yet, written in one vault session;
     *  returns them. iOS's twin is CloudRecords.apply. */
    fun applyRecords(records: List<uniffi.skipti_core.RecordWire>): List<uniffi.skipti_core.RecordWire> {
        if (records.isEmpty()) return emptyList()
        return withVault { vault ->
            val known = vault.allRecords().map { Sync.recordIdentity(it) }.toMutableSet()
            val fresh = mutableListOf<uniffi.skipti_core.RecordWire>()
            for (record in records) {
                if (known.add(Sync.recordIdentity(record))) {
                    vault.putRecord(record)
                    fresh += record
                }
            }
            fresh
        }
    }

    fun applyRecord(record: uniffi.skipti_core.RecordWire): Boolean = withVault { vault ->
        val existing = vault.allRecords().map { Sync.recordIdentity(it) }.toSet()
        val id = Sync.recordIdentity(record)
        val isNew = id !in existing
        if (isNew) vault.putRecord(record)
        isNew
    }

    /**
     * One-time, IDEMPOTENT backfill: mints sync records for ledger rows
     * that predate the record path. A pre-D-121 vault wrote ONLY the kv
     * projection (the old appendExpense never wrote a record), and
     * [rebuildProjection] overwrites the projection from the record path
     * on every pull that applies a record, so the vault's ENTIRE ledger
     * would be lost on the first pull. This runs at the top of every pull,
     * BEFORE anything is applied: the legacy rows become records, the
     * record path becomes the single source of truth, and the projection
     * rebuild keeps them.
     *
     * A row already present in the record path is skipped (data-class
     * equality against the decoded record blobs), so a post-D-121 vault
     * mints nothing and no row is ever minted twice. Each minted record is
     * HLC-stamped from its OWN row timestamp (this device's id as author),
     * so it keeps its creation position, and being authored here, it is
     * pushable, which is what carries the pre-D-121 ledger to the relay.
     *
     * Returns how many records were minted.
     *
     * D-141: it runs ONCE per vault, then a flag stops it. After an edit the
     * kv row no longer equals its creation record (the projection applied
     * the revision), so a second run would have minted the edited row as a
     * brand-new expense on the next pull.
     */
    fun backfillLegacyRecords(nowMs: Long = System.currentTimeMillis()): Int {
        if (rawKvGet(BACKFILLED_KEY) != null) return 0
        val minted = mintLegacyRecords(nowMs)
        rawKvSet(BACKFILLED_KEY, byteArrayOf(1))
        return minted
    }

    private fun mintLegacyRecords(nowMs: Long): Int {
        val recordExpenses = withVault { vault -> vault.allRecords() }
            .filter { it.recordType == 1u.toUByte() }
            .mapNotNull { record ->
                try {
                    json.decodeFromString(ExpenseDTO.serializer(), record.blob.decodeToString())
                } catch (_: Exception) {
                    null // not an expense blob (legacy settlement payload, or corrupt)
                }
            }
        val recordSettlements = withVault { vault -> vault.allRecords() }
            .filter { it.recordType == 2u.toUByte() }
            .mapNotNull { record ->
                try {
                    json.decodeFromString(SettlementDTO.serializer(), record.blob.decodeToString())
                } catch (_: Exception) {
                    null
                }
            }
        var minted = 0
        for (expense in decodeExpenses(rawKvGet(EXPENSES_KEY))) {
            if (expense in recordExpenses) continue
            val vault = openVault()
            try {
                val hlc = Sync.nextHlc(this, expense.timestampMs ?: nowMs)
                vault.putRecord(Sync.expenseRecord(expense, hlc, Sync.deviceId(this), Placing.NONE))
            } finally {
                try { vault.closeVault() } catch (_: StoreException) {}
            }
            minted++
        }
        for (settlement in decodeSettlements(rawKvGet(SETTLEMENTS_KEY))) {
            if (settlement in recordSettlements) continue
            val vault = openVault()
            try {
                val hlc = Sync.nextHlc(this, nowMs)
                vault.putRecord(Sync.settlementRecord(settlement, hlc, Sync.deviceId(this), Placing.NONE))
            } finally {
                try { vault.closeVault() } catch (_: StoreException) {}
            }
            minted++
        }
        return minted
    }

    /**
     * Rebuilds the kv projection from the RECORD path: the HLC-ordered
     * truth. Two devices holding the same record set produce IDENTICAL
     * projection bytes, which is the convergence property the balance
     * line inherits. Records whose blob predates the record format (the
     * old canonical settlement payload) cannot project and are skipped:
     * a one-time transition for pre-ADR-0034 dev vaults, recorded in
     * BUILD-LOG §4. Undecodable records still participate in the MERGE:
     * only the projection skips them.
     */
    fun rebuildProjection() {
        // D-154: the once-per-vault legacy backfill runs BEFORE any rebuild can
        // rewrite a row. A merged row no longer equals its own record (its
        // payer is now the account's "you"), and a first backfill run after
        // that would mint it again as a second expense. Pulls and edits already
        // ran it first; now no caller can get the order wrong.
        backfillLegacyRecords()
        val records = withVault { vault -> vault.allRecords() }
        val settlements = mutableListOf<SettlementDTO>()
        for (record in records) {
            if (record.recordType != 2u.toUByte()) continue
            try {
                settlements += json.decodeFromString(
                    SettlementDTO.serializer(), record.blob.decodeToString(),
                )
            } catch (_: Exception) {
                // Pre-ADR-0034 blob (or a corrupted one): merge-safe, not
                // projectable. Recorded honestly, never silently fused
                // into some other record.
            }
        }
        // D-141: expenses go through core's ONE projection, which applies
        // edits and tombstones (SPEC §5.7, §6.1) identically on both
        // platforms. Deleted expenses leave the kv ledger.
        val expenses = project(records).expenses
            .filter { !it.deleted }
            .map { ExpenseDTO.fromProjected(it) }
        // D-154: a settlement between two of your own ids leaves the projected
        // ledger; the others read with one "you".
        val shown = yous(records)?.let { y ->
            uniffi.skipti_core.ffiMergeYouSettlements(settlements.map { it.toInput() }, y.you, y.others, y.aliases)
                .map { SettlementDTO(it.from, it.to, it.amountMinor, it.currencyCode, it.group) }
        } ?: settlements
        // D-210: the balances moved off removed people, in HLC order; a
        // redistribution reads with one "you" as an expense does: the place a
        // removed joiner had reads as them on their own phone, a remover's
        // ticked share as the remover.
        var redistributions = Sync.redistributions(records)
        yous(records)?.let { y ->
            redistributions = uniffi.skipti_core.ffiMergeYouRedistributions(
                redistributions.map { it.toInput() }, y.you, y.others, y.aliases,
            ).map { RedistributionDTO.of(it) }
        }
        rawKvSet(EXPENSES_KEY, encodeExpenses(expenses))
        rawKvSet(
            SETTLEMENTS_KEY,
            Sync.settlementListSerializer.let {
                json.encodeToString(it, shown)
            }.toByteArray(Charsets.UTF_8),
        )
        rawKvSet(
            REDISTRIBUTIONS_KEY,
            json.encodeToString(Sync.redistributionListSerializer, redistributions).toByteArray(Charsets.UTF_8),
        )
        // D-153: groups and "you" come from the record path too.
        rebuildGroups()
    }

    // ---- Groups and "you" as records (D-153, on core's D-152) ----

    /** Appends operations as records, each stamped and authored here, so the
     *  next push carries them. Encoding is core's; a refusal throws. D-229:
     *  [placing] maps the person a rename or a removal names (Placing.NONE
     *  where nothing may be mapped). */
    fun appendGroupOps(ops: List<GroupOpIn>, placing: Placing, nowMs: Long = System.currentTimeMillis()) {
        if (ops.isEmpty()) return
        // Every operation is mapped (or refused, D-229 decision 45) before anything is written.
        val mapped = ops.map(placing::op)
        val device = Sync.deviceId(this)
        for (op in mapped) {
            val record = Sync.groupOpRecord(op, Sync.nextHlc(this, nowMs), device)
            withVault { vault -> vault.putRecord(record) }
        }
    }

    fun projectGroups(): GroupsProjectionOut = ffiProjectGroups(
        allRecords().filter { it.recordType == GROUP_OP_TYPE }.map {
            GroupOpRecordIn(it.hlcMillis, it.hlcCounter, it.hlcDeviceId, it.blob)
        },
    )

    /**
     * D-211: a picture kept in kv before pictures were records (every phone
     * before 0.5.0) becomes one `Avatar` record, once. Content-based and
     * idempotent: nothing is written when ANY avatar record is in the vault, a
     * clearing one included (another phone's "no picture" must never be undone
     * by an old kept one), and the flag is set either way. A picture that
     * cannot be written (core refuses it) leaves the flag unset and the kv value
     * untouched: the rebuild writes kv from the records only once the flag is
     * set, so it is never overwritten with "none" meanwhile. Returns true when
     * it wrote a record.
     *
     * Final review I1(c): the record is stamped LOW ([MIGRATED_HLC]), not now.
     * On a second phone the migration can run before that phone has the
     * account's newer picture (or its clearing); a fresh stamp would beat it on
     * every phone. Any deliberate picture, set at any time, beats a migrated one.
     */
    fun migrateAvatarOnce(): Boolean {
        if (rawKvGet(AVATAR_RECORD_KEY)?.isNotEmpty() == true) return false
        var wrote = false
        if (!hasAvatarRecord()) {
            val kept = rawKvGet(AVATAR_KEY)
            if (kept != null) {
                var avatar = Avatar.decode(kept)
                // An old photo has no size cap: step it down to what core takes.
                if (avatar is Avatar.Photo && avatar.jpeg.size > ProfileRules.PHOTO_MAX_BYTES) {
                    val smaller = ProfileRules.storedPhoto(avatar.jpeg) ?: throw LedgerCorruptException()
                    avatar = Avatar.Photo(smaller)
                }
                if (avatar != Avatar.None) {
                    val (emoji, photoHex) = avatar.recordParts
                    val record = Sync.groupOpRecord(GroupOpIn.Avatar(emoji, photoHex), MIGRATED_HLC, Sync.deviceId(this))
                    withVault { vault -> vault.putRecord(record) }
                    wrote = true
                }
            }
        }
        rawKvSet(AVATAR_RECORD_KEY, byteArrayOf(1))
        return wrote
    }

    /** D-211: any personal `Avatar` record in the vault, a clearing one
     *  included (the projection reads the same records, but shows a clearing as
     *  no picture, which is not the same as none written). */
    fun hasAvatarRecord(): Boolean = allRecords().any { record ->
        record.recordType == GROUP_OP_TYPE && record.groupId == null &&
            runCatching { uniffi.skipti_core.ffiDecodeGroupOp(record.blob) }.getOrNull() is GroupOpIn.Avatar
    }

    /**
     * D-211: the picture of an imported file, written as a record only when the
     * file has one and this phone has none (an import never replaces the
     * picture already here). Returns true when it wrote one. The name is the
     * phone's own and is not imported. A picture core refuses (not a JPEG, over
     * 64,000 bytes) is skipped: the rest of the import stands.
     */
    fun importProfile(profile: uniffi.skipti_core.PortProfile?): Boolean {
        if (profile == null || (profile.emoji == null && profile.photoHex == null)) return false
        // A picture kept before pictures were records counts as "has one".
        migrateAvatarOnce()
        if (projectGroups().avatar != null) return false
        try {
            appendGroupOps(listOf(GroupOpIn.Avatar(profile.emoji, profile.photoHex)), Placing.NONE)
        } catch (_: CoreException) {
            return false
        }
        rebuildGroups()
        return true
    }

    /** Content-based and idempotent: every kv group that has no create record,
     *  and a kv "you" with no `you` record, get one. Runs before every push
     *  and every group rebuild, so a device that kept groups only in kv (every
     *  device before D-153, and an import) syncs them. An operation core
     *  refuses (damaged legacy data) is skipped, and the rebuild's union rule
     *  keeps that group in kv. Returns how many records were minted. */
    fun backfillGroupRecords(nowMs: Long = System.currentTimeMillis()): Int {
        val p = projectGroups()
        val created = p.groups.map { it.group.id }.toSet()
        val ops = mutableListOf<GroupOpIn>()
        for (g in loadGroups()) {
            if (g.id in created) continue
            val names = g.memberNames.orEmpty().distinctBy { it.id }.associate { it.id to it.name }
            ops += GroupOpIn.Create(
                g.id, g.name, g.settlementCurrency,
                g.members.map { GroupOpMemberIn(it, names[it]) },
            )
        }
        val yous = listOfNotNull(p.you?.id).toSet() + p.otherYouIds
        val me = try { loadSelf() } catch (_: LedgerCorruptException) { null }
        if (me != null && me.id !in yous) ops += GroupOpIn.You(me.id, me.name)
        var minted = 0
        for (op in ops) {
            try { ffiEncodeGroupOp(op) } catch (_: CoreException) { continue }
            appendGroupOps(listOf(op), Placing.NONE, nowMs)
            minted++
        }
        return minted
    }

    /** Records → `ledger/groups` and `ledger/self`. THE UNION RULE: a kv group
     *  with no create record stays, after the projected ones, so a rebuild can
     *  never erase a group the records do not hold yet (D-122 #3's trap, which
     *  a first pull would otherwise spring on every pre-D-153 vault). An
     *  unreadable kv group list THROWS (loadGroups) and is never overwritten.
     *  "You" is the account's one "you" (the founder, 2026-09-24); other "you"
     *  ids are kept as aliases until the merge. */
    fun rebuildGroups() {
        backfillGroupRecords()
        // D-211: a refusal leaves the kv picture where it is (see above). A
        // picture migrated now is shared to the groups (final review I1(a)):
        // the share may already have run without it.
        if (runCatching { migrateAvatarOnce() }.getOrDefault(false)) ProfileShare.request(this)
        val p = projectGroups()
        val current = try { loadSelf() } catch (_: LedgerCorruptException) { null }
        // D-210: this account is never taken out of its own copy. A removed
        // joiner's group stays readable (read-only): "you" must still be in
        // it, or no screen could name the group at all.
        val mine = (listOfNotNull(p.you?.id, current?.id) + p.otherYouIds + current?.aliases.orEmpty()).toSet()
        val projected = p.groups.map { g ->
            val named = g.members.mapNotNull { m -> m.name?.let { MemberDTO(m.id, it) } }
            // D-210: a removed place of this account is kept only while the
            // account has no current one here; once it was invited back under
            // a new place, the old one goes like anyone removed.
            val hasPlace = g.group.members.any { it in mine && it !in g.removed }
            GroupDTO(
                id = g.group.id, name = g.group.name,
                // D-186: someone taken out of the group is left out of the
                // people every screen offers; their records keep their id.
                settlementCurrency = g.group.settlementCurrency, members = g.group.members.filter { it !in g.removed || (it in mine && !hasPlace) },
                memberNames = named.ifEmpty { null },
            )
        }
        val ids = projected.map { it.id }.toSet()
        val kvOnly = loadGroups().filter { it.id !in ids }
        writeKv(GROUPS_KEY, json.encodeToString(groupList, projected + kvOnly))
        // D-187: the pictures every group tile draws.
        val pictures = p.groups.mapNotNull { g -> g.picture?.let { g.group.id to PictureDTO(it.emoji, it.photoHex) } }.toMap()
        writeKv(PICTURES_KEY, json.encodeToString(pictureMap, pictures))
        // D-211: the groups' joined people with their previews.
        val previews = p.groups.mapNotNull { g ->
            if (g.profiles.isEmpty()) null
            else g.group.id to g.profiles.associate { it.memberId to PictureDTO(it.emoji, it.thumbHex) }
        }.toMap()
        writeKv(MEMBER_PREVIEWS_KEY, json.encodeToString(previewMap, previews))
        // D-211: your picture, from the records, once it is one (until the
        // migration has run, an absent record must not wipe a kept picture).
        val migrated = rawKvGet(AVATAR_RECORD_KEY)?.isNotEmpty() == true
        if (p.avatar != null || migrated) {
            rawKvSet(AVATAR_KEY, Avatar.from(p.avatar?.emoji, p.avatar?.photoHex).encoded())
        }
        val you = p.you ?: return
        // A name written for "you" wins; else the name this device already
        // had for itself, since it is the same person.
        val sameYou = current != null && (current.id == you.id || current.id in p.otherYouIds)
        val me = SelfDTO(
            id = you.id,
            name = you.name ?: current?.name?.takeIf { sameYou },
            aliases = p.otherYouIds.ifEmpty { null },
        )
        writeKv(SELF_KEY, json.encodeToString(SelfDTO.serializer(), me))
    }

    /**
     * The vault→core bridge §9 needs (iOS's LedgerStore.snapshot(),
     * one-to-one): one coherent LedgerSnapshot assembled from what the app
     * actually persists, handed to the real FFI exports/planner. Nothing
     * here formats money or invents a serialiser: core writes what this
     * assembles.
     */
    fun snapshot(): LedgerSnapshot = LedgerSnapshot(
        // D-136: never seed while exporting; an empty ledger exports empty.
        groups = loadGroups().map { g ->
            PortGroup(
                id = g.id,
                name = g.name,
                settlementCurrency = g.settlementCurrency,
                members = g.members,
            )
        },
        expenses = loadExpenses().map { e ->
            PortExpense(
                payer = e.payer,
                paidMinor = e.paidMinor,
                currencyCode = e.currencyCode,
                group = e.group,
                conversion = e.conversion?.let { c ->
                    PortConversion(
                        convertedMinor = c.convertedMinor,
                        convertedCurrency = c.convertedCurrency,
                        rateNum = c.rateNum,
                        rateDen = c.rateDen,
                        rateTimestampMs = c.rateTimestampMs.toULong(),
                    )
                },
                shares = e.shares.map { s -> ExpenseShare(id = s.id, amountMinor = s.amountMinor) },
                note = e.note,
                timestampMs = e.timestampMs?.toULong(),
                category = e.category,
            )
        },
        settlements = loadSettlements().map { s ->
            PortSettlement(
                from = s.from,
                to = s.to,
                amountMinor = s.amountMinor,
                currencyCode = s.currencyCode,
                group = s.group,
            )
        },
        // D-210: the balances moved off removed people travel in the export.
        redistributions = loadRedistributions().map { r ->
            PortRedistribution(
                from = r.from, currencyCode = r.currencyCode, group = r.group,
                shares = r.shares.map { ExpenseShare(id = it.id, amountMinor = it.amountMinor) },
            )
        },
        // D-211: your name and picture travel in the JSON export (GUARANTEES #4).
        // The records give it; a phone that has not migrated its kept picture yet
        // ([migrateAvatarOnce]) has it in kv only.
        profile = exportedProfile(),
    )

    private fun exportedProfile(): uniffi.skipti_core.PortProfile? {
        val picture = exportable(exportedPicture({ projectGroups().avatar }, loadAvatar()))
        val name = loadSelf()?.name
        if (name == null && picture == Avatar.None) return null
        val (emoji, photoHex) = picture.recordParts
        return uniffi.skipti_core.PortProfile(name, emoji, photoHex)
    }

    /** D-210: one group's slice of the ledger, by core (a removed person's
     *  read-only copy exports only that group). */
    fun snapshot(group: String): LedgerSnapshot = ffiSnapshotForGroup(snapshot(), group)

    /**
     * The import's arrival path: the merged §9.5 snapshot (core's own
     * output) translated back into the app's envelope shape (the exact
     * inverse of [snapshot]) and written through the same encoders
     * everything else reads. A boundary MAPPING, not a money format: the
     * file's bytes came from core untouched, and the caller only invokes
     * this AFTER ffiApplyImportPlan accepted the merge.
     */
    fun writeSnapshot(merged: LedgerSnapshot) {
        // D-136: an import carries member ids only (PortGroup), so keep the
        // names this device already stores, by member id, in the merged order.
        val storedNames = loadGroups().associate { g ->
            g.id to g.memberNames.orEmpty().associateBy { it.id }
        }
        val groups = merged.groups.map { g ->
            val known = storedNames[g.id].orEmpty()
            GroupDTO(
                id = g.id,
                name = g.name,
                settlementCurrency = g.settlementCurrency,
                members = g.members,
                memberNames = g.members.mapNotNull { known[it] }.ifEmpty { null },
            )
        }
        val expenses = merged.expenses.map { e ->
            ExpenseDTO(
                payer = e.payer,
                paidMinor = e.paidMinor,
                currencyCode = e.currencyCode,
                group = e.group,
                conversion = e.conversion?.let { c ->
                    ConversionDTO(
                        convertedMinor = c.convertedMinor,
                        convertedCurrency = c.convertedCurrency,
                        rateNum = c.rateNum,
                        rateDen = c.rateDen,
                        rateTimestampMs = c.rateTimestampMs.toLong(),
                    )
                },
                shares = e.shares.map { s -> ExpenseDTO.ShareDTO(id = s.id, amountMinor = s.amountMinor) },
                note = e.note,
                timestampMs = e.timestampMs?.toLong(),
                category = e.category,
            )
        }
        val settlements = merged.settlements.map { s ->
            SettlementDTO(
                from = s.from,
                to = s.to,
                amountMinor = s.amountMinor,
                currencyCode = s.currencyCode,
                group = s.group,
            )
        }
        rawKvSet(GROUPS_KEY, json.encodeToString(groupList, groups).toByteArray(Charsets.UTF_8))
        rawKvSet(EXPENSES_KEY, encodeExpenses(expenses))
        rawKvSet(
            SETTLEMENTS_KEY,
            Sync.settlementListSerializer.let {
                json.encodeToString(it, settlements)
            }.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Appends an expense to the persisted ledger, as kv state AND as an
     * ADR-0034 sync record whose HLC is stamped AT CREATION (core's tick
     * rule, persisted before use) and whose blob is the record's own DTO
     * JSON, sealed before it ever leaves the device. Write, CLOSE, REOPEN,
     * read back: the returned list is the persisted truth.
     */
    fun appendExpense(
        expense: ExpenseDTO,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ExpenseDTO> {
        // D-229: the RECORD names the place each group knows this account by;
        // the kv ledger keeps the expense as given (the merged view). Built
        // first, so a record core refuses leaves the vault untouched.
        val placing = Placing.load(this)
        checkText(expense.note)
        // D-235: core's money rules, before anything is stamped or written: this phone never writes what every phone
        // (this one included) refuses to read from a group's log. A refusal is a CoreException the screen shows.
        uniffi.skipti_core.ffiCheckExpense(placing.expense(expense).toInput())
        val record = Sync.expenseRecord(expense, Sync.nextHlc(this, nowMs), Sync.deviceId(this), placing)
        val vault = openVault()
        try {
            val list = decodeExpenses(vault.kvGet(EXPENSES_KEY))
            vault.kvSet(EXPENSES_KEY, encodeExpenses(list + expense))
            vault.putRecord(record)
        } finally {
            try { vault.closeVault() } catch (_: StoreException) {}
        }
        val vault2 = openVault()
        try {
            return decodeExpenses(vault2.kvGet(EXPENSES_KEY))
        } finally {
            try { vault2.closeVault() } catch (_: StoreException) {}
        }
    }

    /**
     * D-166: an import's expenses in ONE vault session, each as kv state AND
     * an ADR-0034 record (HLC stamped at creation, like [appendExpense]), then
     * read back. Every record is built before anything is written, so a record
     * that cannot be built leaves the vault untouched. iOS: appendExpenses.
     */
    fun appendExpenses(
        expenses: List<ExpenseDTO>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ExpenseDTO> {
        if (expenses.isEmpty()) return loadExpenses()
        val device = Sync.deviceId(this)
        // D-229: the records name the place (the "you" column was bound to the
        // merged "you"); kv keeps the expenses as given.
        val placing = Placing.load(this)
        // D-235: every one checked before any is stamped or written.
        expenses.forEach {
            checkText(it.note)
            uniffi.skipti_core.ffiCheckExpense(placing.expense(it).toInput())
        }
        val records = expenses.map { Sync.expenseRecord(it, Sync.nextHlc(this, nowMs), device, placing) }
        val vault = openVault()
        try {
            val list = decodeExpenses(vault.kvGet(EXPENSES_KEY))
            vault.kvSet(EXPENSES_KEY, encodeExpenses(list + expenses))
            records.forEach { vault.putRecord(it) }
        } finally {
            try { vault.closeVault() } catch (_: StoreException) {}
        }
        return loadExpenses()
    }

    /**
     * PROOF SURFACES AND TESTS ONLY (D-136): users' paths call `loadExpenses()`.
     *
     * Opens the vault; seeds `seed` on first run; CLOSES and REOPENS so the
     * returned ledger is read from persisted bytes, never from memory.
     */
    fun loadOrSeed(seed: List<ExpenseDTO>, groupID: String? = null): List<ExpenseDTO> {
        var vault = openVault()
        var data = try {
            vault.kvGet(EXPENSES_KEY)
        } finally {
            try { vault.closeVault() } catch (_: StoreException) {}
        }
        if (data == null) {
            val seeded = seed.map { it.copy(group = groupID) }
            vault = openVault()
            try {
                vault.kvSet(
                    EXPENSES_KEY,
                    json.encodeToString(expenseList, seeded).toByteArray(Charsets.UTF_8),
                )
            } finally {
                try { vault.closeVault() } catch (_: StoreException) {}
            }
            vault = openVault()
            data = try {
                vault.kvGet(EXPENSES_KEY)
            } finally {
                try { vault.closeVault() } catch (_: StoreException) {}
            }
        }
        return decodeExpenses(data)
    }

    /** Reads the vault's expenses; an absent key is an empty ledger, corrupt
     *  bytes are LedgerCorruptException, never an empty list pretending. */
    fun loadExpenses(): List<ExpenseDTO> = withVault { vault ->
        decodeExpenses(vault.kvGet(EXPENSES_KEY))
    }

    /**
     * PROOF SURFACES AND TESTS ONLY (D-136): users' paths call `loadGroups()`
     * or `namedGroups()`, which never seed.
     *
     * Loads the groups, seeding the fixture group through `ffiCreateGroup`
     * on first run: the id is generated IN CORE and the settlement currency
     * is fixed from then on (§5.2). Mirrors iOS exactly, including the
     * fall-through: corrupt group JSON re-seeds rather than throwing (the
     * expenses envelope is the guarded one; groups only name the header).
     */
    fun loadGroupsOrSeed(): List<GroupDTO> = withVault { vault ->
        val data = vault.kvGet(GROUPS_KEY)
        if (data != null) {
            try {
                return@withVault json.decodeFromString(groupList, data.decodeToString())
            } catch (_: Exception) {
                // fall through to re-seed, exactly as iOS's try? does
            }
        }
        val created: GroupWire = ffiCreateGroup(
            name = "Goa Trip",
            settlementCurrency = "INR",
            members = BalanceFixtures.names.keys.sorted(),
        )
        val group = GroupDTO(
            id = created.id,
            name = created.name,
            settlementCurrency = created.settlementCurrency,
            members = created.members,
        )
        vault.kvSet(
            GROUPS_KEY,
            json.encodeToString(groupList, listOf(group)).toByteArray(Charsets.UTF_8),
        )
        listOf(group)
    }

    /**
     * Reads the groups WITHOUT seeding, so a fresh install writes nothing at
     * all. An unreadable group list is an error (LedgerCorruptException),
     * never "no groups": D-136 removed the `emptyList()` that hid corruption
     * as a fresh start (D-127's lesson).
     */
    fun loadGroups(): List<GroupDTO> = withVault { vault ->
        vault.kvGet(GROUPS_KEY)?.let { data ->
            try {
                json.decodeFromString(groupList, data.decodeToString())
            } catch (_: Exception) {
                throw LedgerCorruptException()
            }
        } ?: emptyList()
    }

    // ---- Groups and people (D-136; founder decisions 2026-09-14; core D-134) ----

    fun loadSelf(): SelfDTO? = withVault { vault ->
        vault.kvGet(SELF_KEY)?.let { data ->
            try {
                json.decodeFromString(SelfDTO.serializer(), data.decodeToString())
            } catch (_: Exception) {
                throw LedgerCorruptException()
            }
        }
    }

    /** Created on first need. A vault written by the old fixture seeding adopts
     *  the fixture Ada's id: its expenses already name that id as payer, so the
     *  migration rewrites no record. */
    fun ensureSelf(): SelfDTO {
        loadSelf()?.let { return it }
        val legacy = loadGroups().any { LEGACY_ADA in it.members }
        val created = SelfDTO(id = if (legacy) LEGACY_ADA else ffiNewParticipantId())
        writeKv(SELF_KEY, json.encodeToString(SelfDTO.serializer(), created))
        // D-153: "you" is a record, so the account's other devices know it.
        appendGroupOps(listOf(GroupOpIn.You(created.id, null)), Placing.NONE)
        return created
    }

    /** Sets your name (trimmed and validated by core) and carries it into every
     *  group you are in, so others see the new name too. */
    fun setYourName(raw: String): SelfDTO {
        val name = ffiMemberName(raw)
        val me = ensureSelf().copy(name = name)
        writeKv(SELF_KEY, json.encodeToString(SelfDTO.serializer(), me))
        appendGroupOps(listOf(GroupOpIn.You(me.id, name)), Placing.NONE)
        // D-229 decision 45: a group whose place is lost takes no rename (its
        // Placing refuses every record there); every other group does.
        val placing = Placing.load(this)
        val renamed = namedGroups().map { named ->
            if (named.members.any { it.id == me.id } && !placing.refuses(named.group.id)) ffiRenameMember(named, me.id, name) else named
        }
        saveNamed(renamed)
        return me
    }

    /** Every group with its people, validated by core. Each member's name is
     *  resolved on its own: the stored name, else yours, else the old fixture
     *  name, else "Member N" by position. Placeholders are distinct, so core's
     *  duplicate-name rule never refuses a group only because names were never
     *  stored. Throws NeedsYourNameException while you are in a group that has
     *  no stored name for you and you have not given one. */
    fun namedGroups(): List<NamedGroupWire> = readGroups().named

    /** D-210 (Task 8 look): a group this account has no place in yet. */
    data class WaitingGroup(val id: String, val name: String)

    /** The groups still arriving: the records name this account's place (a
     *  claim) before the create or addMember that makes it. Never in
     *  [namedGroups], so no balance, overview, picker or write meets them;
     *  Groups lists them as waiting, and they resolve when the rest arrives. */
    fun waitingGroups(): List<WaitingGroup> = readGroups().waiting

    private class GroupsRead(val named: List<NamedGroupWire>, val waiting: List<WaitingGroup>)

    private fun readGroups(): GroupsRead {
        val me = ensureSelf()
        val groups = loadGroups()
        // D-153: "you" in a group is your id, or else an id another of your
        // devices used as "you" before it adopted the account's (an alias).
        fun selfId(g: GroupDTO): String =
            if (me.id in g.members) me.id else me.aliases.orEmpty().firstOrNull { it in g.members } ?: me.id
        val waitingForYou = groups.any { g ->
            val you = selfId(g)
            you in g.members && g.memberNames.orEmpty().none { it.id == you }
        }
        if (waitingForYou && me.name == null) throw NeedsYourNameException()
        val named = mutableListOf<NamedGroupWire>()
        val waiting = mutableListOf<WaitingGroup>()
        for (g in groups) {
            val you = selfId(g)
            // Only this case is skipped: this account has no place in the group
            // yet (core would say UnknownParticipant). Any other refusal, and a
            // vault that will not open, still throws.
            if (you !in g.members) {
                waiting += WaitingGroup(g.id, g.name)
                continue
            }
            val stored = g.memberNames.orEmpty().distinctBy { it.id }.associate { it.id to it.name }
            val members = g.members.mapIndexed { index, id ->
                MemberWire(
                    id,
                    stored[id]
                        ?: me.name?.takeIf { id == you }
                        ?: LEGACY_NAMES[id]
                        ?: "Member ${index + 1}",
                )
            }
            named += ffiNamedGroup(GroupWire(g.id, g.name, g.settlementCurrency, g.members), you, members)
        }
        return GroupsRead(named, waiting)
    }

    /** Creates a group with you in it plus the people named, selects it, and
     *  returns it. Needs your name first. */
    fun createGroup(name: String, settlementCurrency: String, people: List<String>): NamedGroupWire {
        val me = ensureSelf()
        val myName = me.name ?: throw NeedsYourNameException()
        val created = ffiCreateNamedGroup(
            name, settlementCurrency, MemberWire(me.id, myName), people.filter { it.isNotBlank() },
        )
        // No catch-to-empty here: an unreadable group list must stop the save.
        // Swallowing it would write ONLY the new group and erase the rest (the
        // bug caught in the iOS draft, D-135).
        val existing = namedGroups()
        saveNamed(existing + created)
        selectGroup(created.group.id)
        return created
    }

    /** D-166: keeps a group built elsewhere (an import builds it with
     *  `ffiCreateNamedGroup` before anything is saved), and selects it. */
    fun adopt(created: NamedGroupWire) {
        saveNamed(namedGroups() + created)
        selectGroup(created.group.id)
    }

    fun addMember(groupId: String, name: String): NamedGroupWire =
        update(groupId) { ffiAddMember(it, name) }

    fun renameMember(groupId: String, memberId: String, name: String): NamedGroupWire =
        update(groupId) { ffiRenameMember(it, memberId, name) }

    /** D-186 (the founder: only someone with no history): why a person
     *  cannot be taken out of a group, or null when they can. Never you;
     *  not someone who joined on a phone (taking them out would need the
     *  group's key rotated, SPEC §3); never someone in an expense or payment
     *  here, whose records must keep their name. */
    fun removalRefusal(groupId: String, memberId: String): MemberCopy.Refusal? {
        val named = namedGroups().firstOrNull { it.group.id == groupId } ?: throw UnknownGroupException()
        if (memberId == named.selfId) return MemberCopy.Refusal.YOU
        val claimed = projectGroups().groups.firstOrNull { it.group.id == groupId }
            ?.claims.orEmpty().any { it.memberId == memberId }
        if (claimed) return MemberCopy.Refusal.JOINED
        val history = ffiMemberHasHistory(
            loadExpenses().map { it.toInput() }, loadSettlements().map { it.toInput() },
            loadRedistributions(), groupId, memberId,
        )
        return if (history) MemberCopy.Refusal.HISTORY else null
    }

    /** D-189: the newest change that was on screen when Activity was last
     *  opened, in the records' own time (a phone's clock cannot hide
     *  anything). This phone's own, never synced. */
    fun activitySeenUpTo(): Long = runCatching {
        withVault { vault -> vault.kvGet(ActivityFeed.SEEN_KEY)?.decodeToString()?.toLongOrNull() }
    }.getOrNull() ?: 0L

    fun setActivitySeenUpTo(ms: Long) = writeKv(ActivityFeed.SEEN_KEY, ms.toString())

    /** D-190: the newest change a notification already covered, and the
     *  phones asking that were already told about. This phone's own. */
    fun notifiedUpTo(): Long = runCatching {
        withVault { vault -> vault.kvGet(NOTIFIED_UP_TO_KEY)?.decodeToString()?.toLongOrNull() }
    }.getOrNull() ?: 0L

    fun toldPhones(): Set<String> = runCatching {
        withVault { vault -> vault.kvGet(TOLD_PHONES_KEY)?.decodeToString() }
            ?.let { json.decodeFromString(ListSerializer(String.serializer()), it).toSet() }
    }.getOrNull() ?: emptySet()

    fun setNotified(upTo: Long, phones: Set<String>) {
        writeKv(NOTIFIED_UP_TO_KEY, upTo.toString())
        writeKv(TOLD_PHONES_KEY, json.encodeToString(ListSerializer(String.serializer()), phones.sorted()))
    }

    /** D-189: the feed for this phone: your id and its aliases are "me".
     *  D-196: one group's, for its page. */
    fun activity(group: String? = null): List<ActivityFeed.Item> {
        val me = ensureSelf()
        return ActivityFeed.items(projectedExpenses().expenses, setOf(me.id) + (me.aliases ?: emptyList()), group)
    }

    /** D-187: every group's picture, by group id; none for a group showing
     *  its initial. */
    fun groupPictures(): Map<String, PictureDTO> = withVault { vault ->
        vault.kvGet(PICTURES_KEY)?.let { runCatching { json.decodeFromString(pictureMap, it.decodeToString()) }.getOrNull() }
            ?: emptyMap()
    }

    /** D-187: a group's picture, as a group record every member's phone
     *  applies: an emoji, a JPEG, or neither for the initial. Core refuses a
     *  photo that is not a JPEG or is over 64,000 bytes. */
    fun setGroupPicture(groupId: String, emoji: String? = null, jpeg: ByteArray? = null) {
        // D-229 (fix wave, M2): load, so a group whose place is lost refuses it (decision 45); the op maps nothing.
        appendGroupOps(listOf(GroupOpIn.GroupPicture(groupId, emoji, jpeg?.let { HexCodec.encode(it) })), Placing.load(this))
        rebuildGroups()
    }

    /** D-186: takes a person out of a group, as an operation every phone
     *  and every member's phone applies; refused as `removalRefusal` says. */
    fun removeMember(groupId: String, memberId: String): NamedGroupWire {
        removalRefusal(groupId, memberId)?.let { throw MemberCopy.Refused(it) }
        // D-229 (fix wave, M1): load, as iOS does and as Design 3 lists removeMember.member among the
        // mapped fields; a group whose place is lost refuses it (decision 45).
        appendGroupOps(listOf(GroupOpIn.RemoveMember(groupId, memberId)), Placing.load(this))
        rebuildGroups()
        return namedGroups().first { it.group.id == groupId }
    }

    fun selectGroup(id: String) {
        writeKv(SELECTED_GROUP_KEY, id)
    }

    fun selectedGroupId(): String? = withVault { vault -> vault.kvGet(SELECTED_GROUP_KEY)?.decodeToString() }

    /** The group to show: the selected one if it still exists, else the first. */
    fun currentGroup(): NamedGroupWire? {
        val groups = namedGroups()
        val selected = selectedGroupId()
        return groups.firstOrNull { it.group.id == selected } ?: groups.firstOrNull()
    }

    private fun update(groupId: String, change: (NamedGroupWire) -> NamedGroupWire): NamedGroupWire {
        val groups = namedGroups().toMutableList()
        val index = groups.indexOfFirst { it.group.id == groupId }
        if (index < 0) throw UnknownGroupException()
        groups[index] = change(groups[index])
        saveNamed(groups)
        return groups[index]
    }

    /** D-153: a change is saved as OPERATIONS, then the kv groups are rebuilt
     *  from the records, so this device and every other one see the same
     *  groups (iOS: GroupStore.save). */
    private fun saveNamed(groups: List<NamedGroupWire>) {
        val before = namedGroups()
        val created = projectGroups().groups.map { it.group.id }.toSet()
        // D-229: a rename of you is written as the place each group knows you by.
        appendGroupOps(Sync.operations(before, groups, created), Placing.load(this))
        rebuildGroups()
    }

    // ---- D-210: read-only copies and forgetting a group ----

    private val readOnlyMap = kotlinx.serialization.builtins.MapSerializer(String.serializer(), ReadOnlyDTO.serializer())

    /** The groups that are read-only copies on this phone, by group id. None
     *  when the list cannot be read: the cloud refuses a removed person's
     *  writes anyway, so a failed read can only mean a refused upload. D-229
     *  decision 45: with every group whose place is lost ([ReadOnlyDTO.withPlaceLost]). */
    fun readOnly(): Map<String, ReadOnlyDTO> {
        val stored = runCatching { storedReadOnly() }.getOrDefault(emptyMap())
        return runCatching { ReadOnlyDTO.withPlaceLost(stored, projectGroups()) }.getOrDefault(stored)
    }

    /** The read-only marks, strictly: absent is none, unreadable THROWS (the
     *  banner must not say "not a copy" because a read failed). D-229
     *  decision 45: with every group whose place is lost. */
    fun readOnlyChecked(): Map<String, ReadOnlyDTO> = ReadOnlyDTO.withPlaceLost(storedReadOnly(), projectGroups())

    /** The stored marks only (removed, deleted): the list the marks below rewrite. */
    private fun storedReadOnly(): Map<String, ReadOnlyDTO> {
        val data = rawKvGet(READ_ONLY_KEY)
        if (data == null || data.isEmpty()) return emptyMap()
        return try {
            json.decodeFromString(readOnlyMap, data.decodeToString())
        } catch (_: Exception) {
            throw LedgerCorruptException()
        }
    }

    /** Strict: a list that cannot be read is never overwritten with one mark. */
    fun markReadOnly(groupId: String, mark: ReadOnlyDTO) {
        val all = storedReadOnly().toMutableMap()
        all[groupId] = mark
        writeKv(READ_ONLY_KEY, json.encodeToString(readOnlyMap, all))
        // D-211: a removed or deleted group never takes this account's profile.
        // The mark is what matters; a failed drop never undoes it (the share's
        // next pass drops a read-only group too).
        runCatching { ProfileShare.dropWaiting(this, groupId) }
    }

    /** The group is shared with this account again (an invite accepted after a
     *  removal): it takes records again. */
    fun clearReadOnly(groupId: String) {
        val all = storedReadOnly().toMutableMap()
        if (all.remove(groupId) == null) return
        writeKv(READ_ONLY_KEY, json.encodeToString(readOnlyMap, all))
    }

    /** "Remove from this phone": the group's records go (core), and with them
     *  everything this phone derived from them: the group and its picture, its
     *  expenses, payments and redistributions, its read-only mark, its cached
     *  roles, its sync cursor and its place in the groups already up. Nothing
     *  else is touched. */
    fun forgetGroup(groupId: String) {
        withVault { vault ->
            vault.forgetGroup(groupId)
            fun <T> rewrite(key: String, read: (String) -> T, keep: (T) -> T, write: (T) -> String) {
                val data = vault.kvGet(key) ?: return
                val list = try { read(data.decodeToString()) } catch (_: Exception) { return }
                vault.kvSet(key, write(keep(list)).toByteArray(Charsets.UTF_8))
            }
            rewrite(GROUPS_KEY, { json.decodeFromString(groupList, it) }, { l -> l.filter { it.id != groupId } },
                { json.encodeToString(groupList, it) })
            rewrite(PICTURES_KEY, { json.decodeFromString(pictureMap, it) }, { m -> m.filterKeys { it != groupId } },
                { json.encodeToString(pictureMap, it) })
            rewrite(MEMBER_PREVIEWS_KEY, { json.decodeFromString(previewMap, it) }, { m -> m.filterKeys { it != groupId } },
                { json.encodeToString(previewMap, it) })
            rewrite(EXPENSES_KEY, { json.decodeFromString(expenseList, it) }, { l -> l.filter { it.group != groupId } },
                { json.encodeToString(expenseList, it) })
            rewrite(SETTLEMENTS_KEY, { json.decodeFromString(settlementList, it) }, { l -> l.filter { it.group != groupId } },
                { json.encodeToString(settlementList, it) })
            rewrite(REDISTRIBUTIONS_KEY, { json.decodeFromString(Sync.redistributionListSerializer, it) },
                { l -> l.filter { it.group != groupId } }, { json.encodeToString(Sync.redistributionListSerializer, it) })
            rewrite(READ_ONLY_KEY, { json.decodeFromString(readOnlyMap, it) }, { m -> m.filterKeys { it != groupId } },
                { json.encodeToString(readOnlyMap, it) })
            // The vault has no delete for a key: an empty value reads as absent
            // (no cached roles, cursor 0).
            vault.kvSet(GroupRoles.key(groupId), ByteArray(0))
            vault.kvSet(CloudRecords.cursorKey(groupId), ByteArray(0))
            vault.kvGet(CloudRecords.GROUPS_UP_KEY)?.takeIf { it.isNotEmpty() }?.let { data ->
                val kept = data.decodeToString().split("\n").filter { it != groupId }
                vault.kvSet(CloudRecords.GROUPS_UP_KEY, kept.joinToString("\n").toByteArray(Charsets.UTF_8))
            }
        }
        // D-211: and it is no longer waited for by the profile share. Under the
        // share's lock, after the vault block, as markReadOnly does.
        runCatching { ProfileShare.dropWaiting(this, groupId) }
        // And the roles held in memory, so no screen offers this group's.
        GroupRoles.forget(groupId)
    }

    private fun writeKv(key: String, text: String) {
        withVault { vault -> vault.kvSet(key, text.toByteArray(Charsets.UTF_8)) }
    }

    private val settlementList = ListSerializer(SettlementDTO.serializer())

    private fun decodeSettlements(data: ByteArray?): List<SettlementDTO> {
        data ?: return emptyList()
        return try {
            json.decodeFromString(settlementList, data.decodeToString())
        } catch (_: Exception) {
            throw LedgerCorruptException()
        }
    }

    /** §5.8's recorded settlements: the debts the recomputed plan subtracts. */
    fun loadSettlements(): List<SettlementDTO> = withVault { vault ->
        decodeSettlements(vault.kvGet(SETTLEMENTS_KEY))
    }

    /** D-210: the balances moved off removed people, in HLC order (millis,
     *  counter, device) so every phone shows the same pairwise cards. Rebuilt
     *  from the type-6 records with the rest of the projection. An absent key
     *  is none; bytes that do not decode are [LedgerCorruptException]. */
    fun loadRedistributions(): List<RedistributionInput> = withVault { vault ->
        val data = vault.kvGet(REDISTRIBUTIONS_KEY) ?: return@withVault emptyList()
        try {
            json.decodeFromString(Sync.redistributionListSerializer, data.decodeToString()).map { it.toInput() }
        } catch (_: Exception) {
            throw LedgerCorruptException()
        }
    }

    /** D-247 fix wave (I1): the whole check a settlement passes before anything is written, and before a settle is held
     *  for Undo ([SettleHold.hold]): this account's place in the group ([Placing]: [Placing.PlaceLost],
     *  [Placing.SameEnds]), then core's rules on the record as it would be written (D-235). [appendSettlement] runs the
     *  same function, so the hold and the write can never disagree. The twin of iOS's `LedgerStore.checkSettlement`. */
    fun checkSettlement(settlement: SettlementDTO): Placing {
        val placing = Placing.load(this)
        uniffi.skipti_core.ffiCheckSettlement(placing.settlement(settlement).toInput())
        return placing
    }

    /**
     * §5.8: records a settlement the only way the app knows how, as
     * persisted ledger state (kv) AND as a typed straumr record through the
     * vault's record path (record_type = settlement, authored, HLC-stamped,
     * blob built by core's `ffiSettlementPayload`, validated). CLOSES and
     * REOPENS so the returned list is read from persisted bytes. iOS
     * appendSettlement, one-to-one (the last D-109 deferral).
     */
    fun appendSettlement(
        settlement: SettlementDTO,
        nowMs: Long = System.currentTimeMillis(),
    ): List<SettlementDTO> {
        // D-229: the record names the place (a payment whose two ends become
        // one id throws here, before anything is written); kv keeps it as given.
        // ADR-0034: the record's HLC is stamped AT CREATION through core's tick
        // rule (never restamped on push) and its blob is the record's own DTO
        // JSON (the old placeholder stamped the payer's PARTICIPANT id as the
        // device id and counter 0 always; the device id is now this device's,
        // and the counter makes same-millisecond records distinct).
        // D-235: core's settlement rules (two different people, more than 0, inside the bound), before anything is written.
        // D-247 fix wave (I1): the one check the hold runs too.
        val placing = checkSettlement(settlement)
        val record = Sync.settlementRecord(settlement, Sync.nextHlc(this, nowMs), Sync.deviceId(this), placing)
        var vault = openVault()
        try {
            val list = try {
                decodeSettlements(vault.kvGet(SETTLEMENTS_KEY))
            } catch (_: LedgerCorruptException) {
                // mirror iOS: a corrupt settlements envelope re-reads as
                // nothing here; the record path below still validates its
                // own payload. (iOS: try? decode ?? [])
                emptyList()
            }
            vault.kvSet(
                SETTLEMENTS_KEY,
                json.encodeToString(settlementList, list + settlement)
                    .toByteArray(Charsets.UTF_8),
            )
            vault.putRecord(record)
        } finally {
            try { vault.closeVault() } catch (_: StoreException) {}
        }
        // Reopen: read the persisted truth, not the in-memory list.
        vault = openVault()
        try {
            return decodeSettlements(vault.kvGet(SETTLEMENTS_KEY))
        } finally {
            try { vault.closeVault() } catch (_: StoreException) {}
        }
    }
}

/** D-141: a projected expense back in the kv ledger's shape. Lossless for
 *  an unedited expense, so the kv ledger reads exactly as before D-141. */
fun ExpenseDTO.Companion.fromProjected(p: uniffi.skipti_core.ProjectedExpenseOut): ExpenseDTO =
    ExpenseDTO(
        payer = p.expense.payer,
        paidMinor = p.expense.paidMinor,
        currencyCode = p.expense.currencyCode,
        group = p.expense.group,
        conversion = p.expense.conversion?.let {
            ConversionDTO(
                convertedMinor = it.convertedMinor,
                convertedCurrency = it.convertedCurrency,
                rateNum = it.rateNum,
                rateDen = it.rateDen,
                rateTimestampMs = it.rateTimestampMs.toLong(),
            )
        },
        shares = p.expense.shares.map { ExpenseDTO.ShareDTO(id = it.id, amountMinor = it.amountMinor) },
        note = p.note,
        timestampMs = p.timestampMs?.toLong(),
        category = p.category,
        addedBy = p.addedBy,
    )

/** DTO → core input; every money value crosses as integer minor units. */
fun ExpenseDTO.toInput(): ExpenseInput = ExpenseInput(
    payer = payer,
    paidMinor = paidMinor,
    currencyCode = currencyCode,
    shares = shares.map { IdAmount(id = it.id, amountMinor = it.amountMinor) },
    group = group,
    conversion = conversion?.let {
        ConversionInput(
            convertedMinor = it.convertedMinor,
            convertedCurrency = it.convertedCurrency,
            rateNum = it.rateNum,
            rateDen = it.rateDen,
            rateTimestampMs = it.rateTimestampMs.toULong(),
        )
    },
)

fun SettlementDTO.toInput(): uniffi.skipti_core.SettlementInput =
    uniffi.skipti_core.SettlementInput(
        from = from,
        to = to,
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        group = group,
    )
