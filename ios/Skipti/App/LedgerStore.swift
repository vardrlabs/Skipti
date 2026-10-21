import Foundation

/// The vault-backed ledger source: write the ledger, close, REOPEN, read
/// back, decode: the loop D-45 proved end to end, now feeding the balance
/// line so UI renders from what was persisted rather than from literals.
///
/// The vault stores opaque bytes; this JSON envelope is app-layer
/// serialization (core owns money semantics, the app owns its persistence
/// envelope). Core still computes every balance from the decoded inputs.
struct LedgerStore {
    enum LoadError: Error {
        case corrupt
        /// D-141: a revision names an expense this vault does not hold.
        case unknownExpense
    }

    static let key = "ledger/expenses"
    let path: String

    init(path: String) {
        self.path = path
    }

    struct ExpenseDTO: Codable, Equatable {
        var payer: String
        var paidMinor: Int64
        var currencyCode: String
        var group: String?
        var conversion: ConversionDTO?
        var shares: [ShareDTO]
        /// §5.7: every record shows what it was and when. Optional so the
        /// decoder tolerates vault JSON written before these existed.
        var note: String?
        var timestampMs: UInt64?
        /// §8.1's category id (a merki leaf). Optional for the same
        /// tolerance: vault JSON from before categories decodes with nil.
        var category: String?
        /// §5.7's "added by" (D-141): the participant id of whoever added it.
        /// Optional: expenses from before D-141 have none.
        var addedBy: String?

        struct ShareDTO: Codable, Equatable {
            var id: String
            var amountMinor: Int64
        }
    }

    struct ConversionDTO: Codable, Equatable {
        var convertedMinor: Int64
        var convertedCurrency: String
        var rateNum: Int64
        var rateDen: Int64
        var rateTimestampMs: UInt64
    }

    struct GroupDTO: Codable, Equatable {
        var id: String
        var name: String
        var settlementCurrency: String
        var members: [String]
        /// D-135: member names, in member order. nil in groups written before
        /// names existed; those are migrated on read (GroupStore.swift).
        var memberNames: [MemberDTO]? = nil
    }

    struct SettlementDTO: Codable, Equatable {
        var from: String
        var to: String
        var amountMinor: Int64
        var currencyCode: String
        var group: String?
    }

    static let settlementsKey = "ledger/settlements"

    /// D-151: the ONE encoder for the kv ledger (expenses, settlements,
    /// groups). Swift's `JSONEncoder` promises no key order, and on this
    /// toolchain the same value encoded twice came out with its keys in
    /// different orders, so a ledger rebuilt from its own records was the
    /// same data in different bytes. Sorted keys make the bytes a function
    /// of the value: every write and every rebuild, on every device, agree.
    /// Records (the sealed sync payloads) are not written through this.
    static func kvEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }
    static let groupsKey = "ledger/groups"
    /// D-187: each group's picture, rebuilt from the records with the groups.
    static let picturesKey = "ledger/group-pictures"
    static let fxKey = "fx/rates"

    /// Opens the vault; seeds `seed` on first run; CLOSES and REOPENS so the
    /// returned ledger is read from persisted bytes, never from memory.
    /// PROOF SURFACES AND TESTS ONLY (D-135): users' paths call `loadExpenses()`.
    func loadOrSeed(seed: [ExpenseDTO], groupID: String? = nil) throws -> [ExpenseDTO] {
        var vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        var data = try vault.kvGet(key: Self.key)
        if data == nil {
            let seededWithGroup = seed.map { expense in
                var withGroup = expense
                withGroup.group = groupID
                return withGroup
            }
            try vault.kvSet(
                key: Self.key,
                value: try LedgerStore.kvEncoder().encode(seededWithGroup)
            )
            try vault.closeVault()
            vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
            data = try vault.kvGet(key: Self.key)
        }
        defer { try? vault.closeVault() }
        guard let data,
              let decoded = try? JSONDecoder().decode([ExpenseDTO].self, from: data) else {
            throw LoadError.corrupt
        }
        return decoded
    }
}

extension LedgerStore {
    /// D-247 fix wave (I1): the whole check a settlement passes before anything is written, and before a settle is held
    /// for Undo (`SettleHold.hold`): this account's place in the group (`Placing`: `PlaceLost`, `SameEnds`), then core's
    /// rules on the record as it would be written (decision 103). `appendSettlement` runs the same function, so the hold
    /// and the write can never disagree. Android's twin is `LedgerStore.checkSettlement`.
    @discardableResult
    func checkSettlement(_ settlement: SettlementDTO) throws -> Placing {
        let placing = try Placing.load(self)
        try ffiCheckSettlement(settlement: try placing.settlement(settlement).asInput)
        return placing
    }

    /// Records a settlement the only way the app knows how: as persisted
    /// ledger state (kv) AND as an ADR-0034 sync record through the vault's
    /// record path: record_type = settlement, authored by THIS device,
    /// HLC-stamped AT CREATION (core's tick rule), blob = the record's own
    /// DTO JSON, sealed before it ever leaves. CLOSES and REOPENS so the
    /// returned list is read from persisted bytes.
    func appendSettlement(
        _ settlement: SettlementDTO,
        nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) throws -> [SettlementDTO] {
        // D-229: the record is built first, so one that cannot be written as
        // this account's place in the group leaves the vault untouched.
        // D-235 (decision 103): never write what every phone (this one included) refuses at ingest: core's rules on
        // the record as written, before anything is. D-247 fix wave (I1): the one check the hold runs too.
        let placing = try checkSettlement(settlement)
        var vault = try VaultGate.open(path: path, keystore: KeychainKeystore())

        var list = (try vault.kvGet(key: Self.settlementsKey))
            .flatMap { try? JSONDecoder().decode([SettlementDTO].self, from: $0) } ?? []
        list.append(settlement)
        try vault.kvSet(
            key: Self.settlementsKey,
            value: try LedgerStore.kvEncoder().encode(list)
        )

        let hlc = try Sync.nextHlc(self, nowMs: nowMs)
        let device = try Sync.deviceId(self)
        try vault.putRecord(
            record: try Sync.settlementRecord(settlement, hlc: hlc, deviceId: device, placing: placing)
        )
        try vault.closeVault()

        // Reopen: read the persisted truth, not the in-memory list.
        vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        guard let data = try vault.kvGet(key: Self.settlementsKey),
              let persisted = try? JSONDecoder().decode([SettlementDTO].self, from: data) else {
            throw LoadError.corrupt
        }
        return persisted
    }

    /// Appends an expense to the persisted ledger, as kv state AND as an
    /// ADR-0034 sync record (HLC at creation, blob = the DTO JSON). Write,
    /// CLOSE, REOPEN, read back: the returned list is the persisted truth.
    func appendExpense(
        _ expense: ExpenseDTO,
        nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) throws -> [ExpenseDTO] {
        // D-229: the kv ledger keeps the merged "you"; only the record is
        // written as the group knows this account (`Placing`).
        let placing = try Placing.load(self)
        // D-249 (decision 208): the payer and every share are people of the expense's ledger, before anything is written.
        try checkMembers(expense)
        // D-235 (decision 103): never write what every phone (this one included) refuses at ingest: core's rules on
        // the record as written, before anything is.
        try ffiCheckExpense(expense: try placing.expense(expense).asInput)
        // D-235 round 3 (decision 112): the note keeps core's text bound (the record's JSON is built here, so core never
        // sees a new expense's note).
        try NoteLimit.check(expense.note)
        var vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        var list = try loadExpenses(from: &vault)
        list.append(expense)
        try vault.kvSet(key: Self.key, value: try LedgerStore.kvEncoder().encode(list))
        let hlc = try Sync.nextHlc(self, nowMs: nowMs)
        let device = try Sync.deviceId(self)
        try vault.putRecord(record: try Sync.expenseRecord(expense, hlc: hlc, deviceId: device, placing: placing))
        try vault.closeVault()

        vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        return try loadExpenses(from: &vault)
    }

    /// D-166: an import's expenses in ONE vault session, each as kv state
    /// AND an ADR-0034 record (HLC stamped at creation, like `appendExpense`),
    /// then read back. Every record is built before anything is written, so a
    /// record that cannot be built leaves the vault untouched.
    func appendExpenses(
        _ expenses: [ExpenseDTO],
        nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) throws -> [ExpenseDTO] {
        guard !expenses.isEmpty else { return try loadExpenses() }
        let device = try Sync.deviceId(self)
        // D-229: as `appendExpense`: the records as this account's places.
        let placing = try Placing.load(self)
        var records: [RecordWire] = []
        // D-249 (decision 208): each ledger's people, read once.
        var people: [String?: [String]] = [:]
        for expense in expenses {
            let members = try people[expense.group] ?? ledgerMembers(expense.group)
            people[expense.group] = members
            try checkMembers(expense, members: members)
            // D-235 (decision 103): as `appendExpense`; one refused leaves the vault untouched (nothing is written yet).
            try ffiCheckExpense(expense: try placing.expense(expense).asInput)
            try NoteLimit.check(expense.note)
            let hlc = try Sync.nextHlc(self, nowMs: nowMs)
            records.append(try Sync.expenseRecord(expense, hlc: hlc, deviceId: device, placing: placing))
        }
        var vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        var list = try loadExpenses(from: &vault)
        list.append(contentsOf: expenses)
        try vault.kvSet(key: Self.key, value: try LedgerStore.kvEncoder().encode(list))
        for record in records { try vault.putRecord(record: record) }
        try vault.closeVault()

        vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        return try loadExpenses(from: &vault)
    }

    /// D-249 (decision 208): the people an expense may name, as this phone holds them (the kv ledger's ids, before
    /// `Placing` maps "you" to a group's place): a group's members, nobody for a group this phone does not hold; for
    /// Just me (no group), your own ids (you and your aliases; nobody before you exist). Android: `ledgerMembers`.
    func ledgerMembers(_ group: String?) throws -> [String] {
        guard let group else {
            guard let me = try loadSelf() else { return [] }
            return [me.id] + (me.aliases ?? [])
        }
        return try loadGroups().first(where: { $0.id == group })?.members ?? []
    }

    /// D-249 (decision 208): core's money rules and then the people (`ledgerMembers`), before a write. A refusal is a
    /// `CoreError` (`.UnknownParticipant` for someone outside the ledger) and nothing is written.
    func checkMembers(_ expense: ExpenseDTO, members: [String]? = nil) throws {
        try ffiCheckExpenseMembers(expense: expense.asInput, members: try members ?? ledgerMembers(expense.group))
    }

    func loadExpenses() throws -> [ExpenseDTO] {
        var vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        return try loadExpenses(from: &vault)
    }

    private func loadExpenses(from vault: inout SkiptiVault) throws -> [ExpenseDTO] {
        guard let data = try vault.kvGet(key: Self.key) else { return [] }
        guard let decoded = try? JSONDecoder().decode([ExpenseDTO].self, from: data) else {
            throw LoadError.corrupt
        }
        return decoded
    }

    /// Loads the groups, seeding the fixture group through
    /// `ffiCreateGroup` on first run: the id is generated IN CORE and the
    /// settlement currency is fixed from then on (§5.2).
    /// PROOF SURFACES ONLY (D-135): seeds the fixture group. Users' paths call
    /// `loadGroups()` / `namedGroups()`, which never seed.
    func loadGroupsOrSeed() throws -> [GroupDTO] {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        if let data = try vault.kvGet(key: Self.groupsKey),
           let decoded = try? JSONDecoder().decode([GroupDTO].self, from: data) {
            return decoded
        }
        let created = try ffiCreateGroup(
            name: "Goa Trip",
            settlementCurrency: "INR",
            members: Fixtures.names.keys.sorted()
        )
        let group = GroupDTO(
            id: created.id,
            name: created.name,
            settlementCurrency: created.settlementCurrency,
            members: created.members
        )
        try vault.kvSet(
            key: Self.groupsKey,
            value: try LedgerStore.kvEncoder().encode([group])
        )
        return [group]
    }

    /// The offline path for §5.2's reference rates: the LAST KNOWN set
    /// lives in the vault and works with no network.
    func saveFxCache(payload: String, signature: String) throws {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        let envelope = try JSONEncoder().encode(
            ["payload": payload, "signature": signature]
        )
        try vault.kvSet(key: Self.fxKey, value: envelope)
    }

    func loadFxCache() throws -> FxRates.CachedSet? {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        guard let data = try vault.kvGet(key: Self.fxKey),
              let envelope = try? JSONDecoder().decode(
                  [String: String].self, from: data
              ),
              let payload = envelope["payload"],
              let signature = envelope["signature"] else {
            return nil
        }
        return FxRates.CachedSet(payload: payload, signature: signature)
    }

    func loadSettlements() throws -> [SettlementDTO] {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        guard let data = try vault.kvGet(key: Self.settlementsKey) else { return [] }
        guard let decoded = try? JSONDecoder().decode([SettlementDTO].self, from: data) else {
            throw LoadError.corrupt
        }
        return decoded
    }

    static let redistributionsKey = "ledger/redistributions"

    /// D-210: the balances moved off removed people, in HLC order (millis,
    /// counter, device) so every phone shows the same pairwise cards. Rebuilt
    /// from the type-6 records with the rest of the projection.
    func loadRedistributions() throws -> [RedistributionInput] {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        guard let data = try vault.kvGet(key: Self.redistributionsKey) else { return [] }
        guard let decoded = try? JSONDecoder().decode([RedistributionDTO].self, from: data) else {
            throw LoadError.corrupt
        }
        return decoded.map(\.asInput)
    }

    /// The kv shape of a redistribution (core's own codec carries the record).
    struct RedistributionDTO: Codable, Equatable {
        var group: String
        var from: String
        var currencyCode: String
        var shares: [ExpenseDTO.ShareDTO]

        init(_ r: RedistributionInput) {
            group = r.group
            from = r.from
            currencyCode = r.currencyCode
            shares = r.shares.map { ExpenseDTO.ShareDTO(id: $0.id, amountMinor: $0.amountMinor) }
        }

        var asInput: RedistributionInput {
            RedistributionInput(group: group, from: from, currencyCode: currencyCode,
                                shares: shares.map { IdAmount(id: $0.id, amountMinor: $0.amountMinor) })
        }
    }
}

extension LedgerStore {
    /// D-229: the ids this account writes into a group's RECORDS. One account
    /// on two phones has two "you" ids; a group made on one phone knows the
    /// account only by that phone's id (its place), and the group's other
    /// members cannot read the other one. Core decides, per group, whether
    /// "you" is readable there (`youAs` nil) or records must be written as the
    /// place (`youAs`); this maps every id of the account (`mine`: "you" and
    /// `otherYouIds`) to it. The kv ledger is never mapped (D-154: there every
    /// id of the account reads as "you", and a record written as the place
    /// merges back to "you" on the account's own phones, so the rebuild gives
    /// the same bytes). With no account, or one "you", nothing is mapped.
    /// Android's twin is `Placing` in LedgerStore.kt.
    struct Placing: Equatable {
        /// Group id to the place to write as, only where "you" is not readable.
        let places: [String: String]
        let mine: [String]
        /// D-229 decision 45: the groups where this account's place is held or
        /// contested by another account's claim (core's `placeLost`). Nothing
        /// is written there: every mapping refuses with `PlaceLost`.
        let lost: Set<String>
        /// D-237 (the Fable audit, A1): per group, the places this account claimed THERE (core's aliases to "you").
        /// A place claimed in another group is someone else's id here (a group's maker can name any id as a
        /// member), so it is never written as this account's place: `mineIn` leaves it out.
        let claimed: [String: Set<String>]

        init(places: [String: String], mine: [String], lost: Set<String> = [], claimed: [String: Set<String>] = [:]) {
            self.places = places
            self.mine = mine
            self.lost = lost
            self.claimed = claimed
        }

        /// A settlement whose two ends would become one id (both the account's).
        struct SameEnds: Error, Equatable {}

        /// D-229 decision 45: a record for a group where this account has lost
        /// its place (`lost`): whatever it wrote would read as someone else.
        /// D-248 (D-247 residue e): where it shows, it reads as the banner's
        /// one line (`RolesCopy.placeLostBanner`), as Android's `PlaceLost`
        /// message always has; it printed its type name ("PlaceLost()").
        struct PlaceLost: Error, Equatable, CustomStringConvertible, LocalizedError {
            var description: String { RolesCopy.placeLostBanner }
            var errorDescription: String? { description }
        }

        /// Maps nothing: the backfills, the personal writers, and every write
        /// that is not about this account inside a group.
        static let none = Placing(places: [:], mine: [])

        static func of(_ p: GroupsProjectionOut) -> Placing {
            guard let you = p.you else { return .none }
            let places = Dictionary(
                p.groups.compactMap { g in g.youAs.map { (g.group.id, $0) } },
                uniquingKeysWith: { a, _ in a })
            let claimed = Dictionary(grouping: p.aliases.filter { $0.toId == you.id }, by: \.groupId)
                .mapValues { Set($0.map(\.fromId)) }
            return Placing(places: places, mine: [you.id] + p.otherYouIds,
                           lost: Set(p.groups.filter(\.placeLost).map(\.group.id)), claimed: claimed)
        }

        /// D-237 (A1): the account's ids in `group`: every id of its own, less the places it claimed only elsewhere.
        func mineIn(_ group: String) -> [String] {
            let here = claimed[group] ?? []
            let elsewhere = claimed.filter { $0.key != group }.values.reduce(into: Set<String>()) { $0.formUnion($1) }
            return mine.filter { here.contains($0) || !elsewhere.contains($0) }
        }

        /// The pure rule: a record in `group` is refused when this account lost
        /// its place there; anything else (no group, a group it still holds) is not.
        func refusal(group: String?) -> PlaceLost? {
            guard let group, lost.contains(group) else { return nil }
            return PlaceLost()
        }

        private func refuseLost(_ group: String?) throws {
            if let refused = refusal(group: group) { throw refused }
        }

        static func load(_ store: LedgerStore) throws -> Placing {
            of(try Sync.projectGroups(store))
        }

        /// One id as written into `group`: the place when it is the account's
        /// and the group needs it, else as it is.
        func id(_ id: String, group: String?) -> String {
            guard let group, let place = places[group], mineIn(group).contains(id) else { return id }
            return place
        }

        /// Payer and shares through core (`ffiWriteAsExpense`: collapsed shares
        /// are added in the first one's place), "added by" through `id`.
        func expense(_ e: ExpenseDTO) throws -> ExpenseDTO {
            try refuseLost(e.group)
            guard let group = e.group, let place = places[group] else { return e }
            let w = try ffiWriteAsExpense(expense: e.asInput, place: place, mine: mineIn(group))
            var out = e
            out.payer = w.payer
            out.shares = w.shares.map { ExpenseDTO.ShareDTO(id: $0.id, amountMinor: $0.amountMinor) }
            out.addedBy = e.addedBy.map { id($0, group: group) }
            return out
        }

        /// Both ends; throws `SameEnds` when the mapping made them one id (an
        /// input whose ends were already one id is not this rule's to refuse;
        /// Android's twin has the same condition).
        func settlement(_ s: SettlementDTO) throws -> SettlementDTO {
            try refuseLost(s.group)
            guard let group = s.group, places[group] != nil else { return s }
            var out = s
            out.from = id(s.from, group: group)
            out.to = id(s.to, group: group)
            if out.from == out.to && (out.from != s.from || out.to != s.to) { throw SameEnds() }
            return out
        }

        /// The editor and any new money (`seen` and the rest untouched).
        /// `group` is the revised expense's.
        func revision(_ r: ExpenseRevisionIn, group: String?) throws -> ExpenseRevisionIn {
            // The revised expense's group decides, not the new money's own field: a revision record carries no group
            // (its scope does), and new money never leaves the expense's group.
            try refuseLost(group)
            guard let group, let place = places[group] else { return r }
            var out = r
            out.editor = r.editor.map { id($0, group: group) }
            out.money = try r.money.map { try ffiWriteAsExpense(expense: $0, place: place, mine: mineIn(group)) }
            return out
        }

        /// `from` and the shares through core (`ffiWriteAsRedistribution`).
        func redistribution(_ r: RedistributionInput) throws -> RedistributionInput {
            try refuseLost(r.group)
            guard let place = places[r.group] else { return r }
            return try ffiWriteAsRedistribution(r: r, place: place, mine: mineIn(r.group))
        }

        /// A rename or a removal of one of the account's ids; every other
        /// operation as it is (a create, an add and a claim are never mapped).
        /// Any operation in a group in `lost` is refused.
        func op(_ op: GroupOpIn) throws -> GroupOpIn {
            try refuseLost(Sync.scope(of: op))
            switch op {
            case .renameMember(let g, let m, let name):
                return .renameMember(groupId: g, memberId: id(m, group: g), name: name)
            case .removeMember(let g, let m):
                return .removeMember(groupId: g, memberId: id(m, group: g))
            default:
                return op
            }
        }
    }
}

extension LedgerStore {
    /// ADR-0034: the record path is the sync set and the merge authority.
    /// The device's 16-byte identity and its persisted HLC state live in
    /// this vault; records are stamped AT CREATION (core's tick rule: no
    /// artificial latency, the counter breaks ties within one millisecond)
    /// so an offline device's records slot into their timestamp position
    /// on reconnect.
    enum Sync {
        static let deviceKey = "sync/device-id"
        static let hlcKey = "sync/hlc-state"
        static let expenseType: UInt8 = 1
        static let settlementType: UInt8 = 2
        /// D-210: a removed person's balance moved onto chosen members
        /// (core's norn::redistribution, straumr 0x06).
        static let redistributionType: UInt8 = 6
        /// D-141: an edit or tombstone of an expense (straumr 0x05).
        static let revisionType: UInt8 = 5
        /// D-153: a group operation or "you" (straumr 0x03, D-152).
        static let groupOpType: UInt8 = 3
        /// D-141: set once the legacy backfill has run (see below).
        static let backfilledKey = "sync/legacy-backfilled"
        /// D-211: set once the picture is a record: written by `saveAvatar`, or
        /// by the one-time migration of a picture the vault kept before.
        static let avatarRecordKey = "profile.avatar-record"

        /// This device's identity, generated once (random bytes born in
        /// core) and persisted. NEVER a participant id: the old
        /// placeholder stamped the payer's participant id as the device.
        static func deviceId(_ store: LedgerStore) throws -> String {
            let vault = try VaultGate.open(path: store.path, keystore: KeychainKeystore())
            defer { try? vault.closeVault() }
            if let data = try vault.kvGet(key: deviceKey) {
                return data.map { String(format: "%02x", $0) }.joined()
            }
            let fresh = Data(ffiSyncKeyGenerate().prefix(16))
            try vault.kvSet(key: deviceKey, value: fresh)
            return fresh.map { String(format: "%02x", $0) }.joined()
        }

        /// The next HLC stamp: core's tick rule over the persisted state,
        /// written back BEFORE the record exists (a crash wastes a stamp,
        /// never reuses one).
        /// D-235 round 4 (shells review I2, Android's `hlcLock`): one HLC read-modify-write at a time. Settlements are
        /// written from `DispatchQueue.global` while main-actor writers mint or merge; two that read the same state
        /// minted one stamp twice, and a record's identity is its stamp (the second REPLACED the first, or the push
        /// found it "already up").
        static let hlcLock = NSLock()

        static func nextHlc(_ store: LedgerStore, nowMs: UInt64) throws -> HlcStamp {
            hlcLock.lock()
            defer { hlcLock.unlock() }
            let vault = try VaultGate.open(path: store.path, keystore: KeychainKeystore())
            defer { try? vault.closeVault() }
            var millis: UInt64 = 0
            var counter: UInt32 = 0
            if let state = try vault.kvGet(key: hlcKey), state.count == 12 {
                (millis, counter) = decodeHlcState(state)
            }
            let next = try ffiHlcNext(
                wallMillis: nowMs, stateMillis: millis, stateCounter: counter
            )
            try vault.kvSet(
                key: hlcKey,
                value: u64BE(next.millis) + u32BE(next.counter)
            )
            return next
        }

        /// Merges a REMOTE record's HLC into this device's state on pull
        /// (core's receive rule), so the next local stamp sorts after
        /// everything the device has seen.
        static func receiveHlc(
            _ store: LedgerStore, millis: UInt64, counter: UInt32, nowMs: UInt64
        ) throws {
            hlcLock.lock()
            defer { hlcLock.unlock() }
            let vault = try VaultGate.open(path: store.path, keystore: KeychainKeystore())
            defer { try? vault.closeVault() }
            var stateMillis: UInt64 = 0
            var stateCounter: UInt32 = 0
            if let state = try vault.kvGet(key: hlcKey), state.count == 12 {
                (stateMillis, stateCounter) = decodeHlcState(state)
            }
            let merged = try ffiHlcReceive(
                stateMillis: stateMillis, stateCounter: stateCounter,
                otherMillis: millis, otherCounter: counter, wallMillis: nowMs
            )
            try vault.kvSet(
                key: hlcKey,
                value: u64BE(merged.millis) + u32BE(merged.counter)
            )
        }

        /// Decodes the persisted HLC state `u64BE(millis) ‖ u32BE(counter)`,
        /// 12 bytes. The counter lives in the LAST four bytes: reading
        /// the FIRST four (the old `u32FromBE(state)`) takes the top bytes
        /// of `millis`, which is 0 for any timestamp below 2^32 (before
        /// 19 Feb 1970) and a constant garbage counter (416) at production
        /// magnitude, silently collapsing every same-millisecond record
        /// onto one identity (D-122 #2). This helper is the ONLY way HLC
        /// state is decoded, so the offset cannot drift apart again.
        static func decodeHlcState(_ data: Data) -> (millis: UInt64, counter: UInt32) {
            precondition(data.count == 12, "HLC state is u64BE(millis) ‖ u32BE(counter), 12 bytes")
            return (u64FromBE(data), u32FromBE(data.suffix(4)))
        }

        static func u32BE(_ value: UInt32) -> Data {
            Data([
                UInt8((value >> 24) & 0xFF), UInt8((value >> 16) & 0xFF),
                UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF),
            ])
        }

        /// Decodes exactly FOUR big-endian bytes. Never hand this a whole
        /// HLC state: it reads the FIRST four bytes, which is the top of
        /// `millis`, not the counter; use `decodeHlcState(_:)`.
        static func u32FromBE(_ data: Data) -> UInt32 {
            precondition(data.count == 4, "u32FromBE takes exactly 4 bytes")
            let bytes = [UInt8](data)
            return UInt32(bytes[0]) << 24 | UInt32(bytes[1]) << 16
                | UInt32(bytes[2]) << 8 | UInt32(bytes[3])
        }

        static func u64BE(_ value: UInt64) -> Data {
            Data([
                UInt8((value >> 56) & 0xFF), UInt8((value >> 48) & 0xFF),
                UInt8((value >> 40) & 0xFF), UInt8((value >> 32) & 0xFF),
                UInt8((value >> 24) & 0xFF), UInt8((value >> 16) & 0xFF),
                UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF),
            ])
        }

        static func u64FromBE(_ data: Data) -> UInt64 {
            data.prefix(8).reduce(0) { ($0 << 8) | UInt64($1) }
        }

        /// One record's identity: its HLC. Two devices creating records in
        /// the same millisecond still differ by counter and device id.
        static func recordIdentity(_ record: RecordWire) -> String {
            "\(record.hlcMillis):\(record.hlcCounter):\(record.hlcDeviceId)"
        }

        /// The record's nonce session is a CONSTANT ("EXP\x01" /
        /// "SET\x01"): a header field, never nonce material. Nothing seals
        /// records since the relay went (D-192: Firebase stores them as
        /// documents; D-200 removed the sealing code from core). If records
        /// are ever sealed again, the seal must not derive its nonce from
        /// this session and hlc.counter (which resets every millisecond):
        /// with a constant session that would reuse nonces.
        ///
        /// D-229: `placing` writes the expense as the group knows this account
        /// (`Placing.expense`): the RECORD only, never the kv ledger. REQUIRED,
        /// so every writer says which (`.none` at the backfills, which re-create
        /// what was already written).
        static func expenseRecord(
            _ expense: ExpenseDTO, hlc: HlcStamp, deviceId: String, placing: Placing
        ) throws -> RecordWire {
            let expense = try placing.expense(expense)
            return RecordWire(
                version: 1,
                recordType: expenseType,
                groupId: expense.group,
                epoch: 0,
                hlcMillis: hlc.millis,
                hlcCounter: hlc.counter,
                hlcDeviceId: deviceId,
                authorDeviceId: deviceId,
                nonceSession: Data([0x45, 0x58, 0x50, 0x01]), // "EXP\x01"
                flags: 0,
                blob: try JSONEncoder().encode(expense)
            )
        }

        /// D-229: `placing` as `expenseRecord`'s (`Placing.settlement`).
        static func settlementRecord(
            _ settlement: SettlementDTO, hlc: HlcStamp, deviceId: String, placing: Placing
        ) throws -> RecordWire {
            let settlement = try placing.settlement(settlement)
            return RecordWire(
                version: 1,
                recordType: settlementType,
                groupId: settlement.group,
                epoch: 0,
                hlcMillis: hlc.millis,
                hlcCounter: hlc.counter,
                hlcDeviceId: deviceId,
                authorDeviceId: deviceId,
                nonceSession: Data([0x53, 0x45, 0x54, 0x01]), // "SET\x01"
                flags: 0,
                blob: try JSONEncoder().encode(settlement)
            )
        }

        /// Applies one record from the wire: idempotent by identity (the
        /// store's unique HLC index replaces, never duplicates). Returns
        /// whether it was NEW.
        static func applyRecord(
            _ store: LedgerStore, _ record: RecordWire
        ) throws -> Bool {
            let vault = try VaultGate.open(path: store.path, keystore: KeychainKeystore())
            defer { try? vault.closeVault() }
            let existing = Set(try vault.allRecords().map { recordIdentity($0) })
            if existing.contains(recordIdentity(record)) { return false }
            try vault.putRecord(record: record)
            return true
        }

        /// One-time, IDEMPOTENT backfill: mints sync records for ledger
        /// rows that predate the record path. A pre-D-121 vault wrote ONLY
        /// the kv projection (the old appendExpense never wrote a record),
        /// and `rebuildProjection` overwrites the projection from the
        /// record path on every pull that applies a record, so the
        /// vault's ENTIRE ledger would be lost on the first pull. Runs at
        /// the top of every pull, BEFORE anything is applied; a row
        /// already present in the record path is skipped (Equatable
        /// equality against the decoded record blobs), so a post-D-121
        /// vault mints nothing and no row is ever minted twice. Each
        /// minted record is HLC-stamped from its OWN row timestamp (this
        /// device's id as author), so it keeps its creation position, and
        /// being authored here, it is pushable. Returns how many records
        /// were minted.
        ///
        /// D-141: it runs ONCE per vault, then a flag stops it. After an edit
        /// the kv row no longer equals its creation record (the projection
        /// applied the revision), so a second run would have minted the
        /// edited row as a brand-new expense on the next pull.
        @discardableResult
        static func backfillLegacyRecords(
            _ store: LedgerStore,
            nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
        ) throws -> Int {
            if try isBackfilled(store) { return 0 }
            let minted = try mintLegacyRecords(store, nowMs: nowMs)
            let vault = try vaultOf(store)
            defer { try? vault.closeVault() }
            try vault.kvSet(key: backfilledKey, value: Data([1]))
            return minted
        }

        private static func isBackfilled(_ store: LedgerStore) throws -> Bool {
            let vault = try vaultOf(store)
            defer { try? vault.closeVault() }
            return try vault.kvGet(key: backfilledKey) != nil
        }

        private static func mintLegacyRecords(_ store: LedgerStore, nowMs: UInt64) throws -> Int {
            let records = try allRecordsOf(store)
            let recordExpenses = records.filter { $0.recordType == expenseType }.compactMap {
                try? JSONDecoder().decode(ExpenseDTO.self, from: $0.blob)
            }
            let recordSettlements = records.filter { $0.recordType == settlementType }.compactMap {
                try? JSONDecoder().decode(SettlementDTO.self, from: $0.blob)
            }
            var minted = 0
            for expense in try legacyRows(store, LedgerStore.key, ExpenseDTO.self)
                where !recordExpenses.contains(expense) {
                let vault = try VaultGate.open(path: store.path, keystore: KeychainKeystore())
                defer { try? vault.closeVault() }
                let hlc = try nextHlc(store, nowMs: expense.timestampMs ?? nowMs)
                try vault.putRecord(
                    record: try expenseRecord(expense, hlc: hlc, deviceId: try deviceId(store), placing: .none)
                )
                minted += 1
            }
            for settlement in try legacyRows(store, LedgerStore.settlementsKey, SettlementDTO.self)
                where !recordSettlements.contains(settlement) {
                let vault = try VaultGate.open(path: store.path, keystore: KeychainKeystore())
                defer { try? vault.closeVault() }
                let hlc = try nextHlc(store, nowMs: nowMs)
                try vault.putRecord(
                    record: try settlementRecord(
                        settlement, hlc: hlc, deviceId: try deviceId(store), placing: .none
                    )
                )
                minted += 1
            }
            return minted
        }

        /// One kv row's decoded list, or nothing: the tolerance every
        /// projection reader already applies.
        private static func legacyRows<T: Decodable>(
            _ store: LedgerStore, _ key: String, _ type: T.Type
        ) throws -> [T] {
            let vault = try vaultOf(store)
            defer { try? vault.closeVault() }
            guard let data = try vault.kvGet(key: key) else { return [] }
            do {
                return try JSONDecoder().decode([T].self, from: data)
            } catch {
                return []
            }
        }

        private static func vaultOf(_ store: LedgerStore) throws -> SkiptiVault {
            try VaultGate.open(path: store.path, keystore: KeychainKeystore())
        }

        private static func allRecordsOf(_ store: LedgerStore) throws -> [RecordWire] {
            let vault = try vaultOf(store)
            defer { try? vault.closeVault() }
            return try vault.allRecords()
        }

        /// D-141: records → core's projection. Creations decode from their
        /// shell JSON; revisions go to core as raw bytes (core alone reads
        /// them). A creation blob this build cannot decode is skipped, as
        /// the projection always has (it still takes part in the merge).
        static func project(_ records: [RecordWire]) throws -> ExpenseProjectionOut {
            var created: [ExpenseCreatedIn] = []
            var revisions: [ExpenseRevisionRecordIn] = []
            for record in records {
                switch record.recordType {
                case expenseType:
                    guard let e = try? JSONDecoder().decode(ExpenseDTO.self, from: record.blob)
                    else { continue }
                    created.append(ExpenseCreatedIn(
                        hlcMillis: record.hlcMillis, hlcCounter: record.hlcCounter,
                        hlcDeviceId: record.hlcDeviceId, expense: e.asInput,
                        note: e.note, category: e.category, timestampMs: e.timestampMs,
                        addedBy: e.addedBy
                    ))
                case revisionType:
                    revisions.append(ExpenseRevisionRecordIn(
                        hlcMillis: record.hlcMillis, hlcCounter: record.hlcCounter,
                        hlcDeviceId: record.hlcDeviceId, blob: record.blob,
                        scope: record.groupId
                    ))
                default:
                    break
                }
            }
            let projected = try ffiProjectExpenses(created: created, revisions: revisions)
            // D-154: one "you" per account. Ids another device wrote as "you"
            // read as the account's "you" in every expense people see.
            guard let yous = try yous(records) else { return projected }
            return ExpenseProjectionOut(
                expenses: try ffiMergeYouExpenses(
                    expenses: projected.expenses, youId: yous.you, otherIds: yous.others,
                    aliases: yous.aliases
                ),
                rejected: projected.rejected,
                pending: projected.pending
            )
        }

        /// D-154: the account's "you" and the other ids its devices wrote as
        /// "you", or nil when there is only one (nothing to merge). D-158:
        /// with the other people who joined a group (their "you" reads as
        /// the member they claimed, in that group).
        static func yous(_ records: [RecordWire]) throws -> (you: String, others: [String], aliases: [IdAlias])? {
            let p = try ffiProjectGroups(records: records
                .filter { $0.recordType == groupOpType }
                .map {
                    GroupOpRecordIn(
                        hlcMillis: $0.hlcMillis, hlcCounter: $0.hlcCounter,
                        hlcDeviceId: $0.hlcDeviceId, blob: $0.blob
                    )
                })
            guard let you = p.you, !p.otherYouIds.isEmpty || !p.aliases.isEmpty else { return nil }
            return (you.id, p.otherYouIds, p.aliases)
        }

        /// D-210: the type-6 records as redistributions, in HLC order (millis,
        /// counter, device), so every phone shows the same pairwise cards. A
        /// record core cannot decode is skipped, as an unreadable settlement is.
        static func redistributions(_ records: [RecordWire]) -> [LedgerStore.RedistributionDTO] {
            records
                .filter { $0.recordType == redistributionType }
                .sorted { ($0.hlcMillis, $0.hlcCounter, $0.hlcDeviceId) < ($1.hlcMillis, $1.hlcCounter, $1.hlcDeviceId) }
                .compactMap { (try? ffiDecodeRedistribution(blob: $0.blob)).map(LedgerStore.RedistributionDTO.init) }
        }

        /// Rebuilds the kv projection from the RECORD path: the HLC-
        /// ordered truth. Two devices holding the same record set produce
        /// IDENTICAL projection content, which is the convergence property
        /// the balance line inherits. Pre-ADR-0034 blobs (the old
        /// canonical settlement payload) cannot project and are skipped,
        /// recorded in BUILD-LOG §4; they still participate in the MERGE.
        ///
        /// D-141: expenses go through core's ONE projection, which applies
        /// edits and tombstones (SPEC §5.7, §6.1) identically on both
        /// platforms. Deleted expenses leave the kv ledger, so balances,
        /// the list and export skip them.
        static func rebuildProjection(_ store: LedgerStore) throws {
            // D-154: the once-per-vault legacy backfill runs BEFORE any rebuild
            // can rewrite a row. A merged row no longer equals its own record
            // (its payer is now the account's "you"), and a first backfill run
            // after that would mint it again as a second expense. Pulls and
            // edits already ran it first; now no caller can get the order wrong.
            try backfillLegacyRecords(store)
            let vault = try VaultGate.open(path: store.path, keystore: KeychainKeystore())
            defer { try? vault.closeVault() }
            let records = try vault.allRecords()
            var settlements: [SettlementDTO] = []
            for record in records where record.recordType == settlementType {
                if let s = try? JSONDecoder().decode(SettlementDTO.self, from: record.blob) {
                    settlements.append(s)
                }
            }
            // D-154: a settlement between two of your own ids leaves the
            // projected ledger; the others read with one "you".
            if let yous = try yous(records) {
                settlements = try ffiMergeYouSettlements(
                    settlements: settlements.map(\.asInput), youId: yous.you, otherIds: yous.others,
                    aliases: yous.aliases
                ).map {
                    SettlementDTO(
                        from: $0.from, to: $0.to, amountMinor: $0.amountMinor,
                        currencyCode: $0.currencyCode, group: $0.group
                    )
                }
            }
            var redistributions = redistributions(records)
            // D-210: and so does a redistribution: the place a removed joiner
            // had reads as them on their own phone, a remover's ticked share
            // as the remover.
            if let yous = try yous(records) {
                redistributions = try ffiMergeYouRedistributions(
                    redistributions: redistributions.map(\.asInput), youId: yous.you, otherIds: yous.others,
                    aliases: yous.aliases
                ).map(LedgerStore.RedistributionDTO.init)
            }
            let expenses = try project(records).expenses
                .filter { !$0.deleted }
                .map(ExpenseDTO.init(projected:))
            try vault.kvSet(key: LedgerStore.key, value: try LedgerStore.kvEncoder().encode(expenses))
            try vault.kvSet(
                key: LedgerStore.settlementsKey,
                value: try LedgerStore.kvEncoder().encode(settlements)
            )
            try vault.kvSet(
                key: LedgerStore.redistributionsKey,
                value: try LedgerStore.kvEncoder().encode(redistributions)
            )
            try vault.closeVault()
            // D-153: groups and "you" come from the record path too.
            try rebuildGroups(store)
        }

        // MARK: - Groups and "you" as records (D-153, on core's D-152)

        /// One group operation as a record. Group operations take their
        /// group's scope; "you" takes PERSONAL scope (groupId nil), so it can
        /// never be shared into a group's log. "GRP\x01": the same
        /// constant-session rule as "EXP\x01" above (never nonce material).
        static func groupOpRecord(_ op: GroupOpIn, hlc: HlcStamp, deviceId: String) throws -> RecordWire {
            RecordWire(
                version: 1,
                recordType: groupOpType,
                groupId: scope(of: op),
                epoch: 0,
                hlcMillis: hlc.millis,
                hlcCounter: hlc.counter,
                hlcDeviceId: deviceId,
                authorDeviceId: deviceId,
                nonceSession: Data([0x47, 0x52, 0x50, 0x01]), // "GRP\x01"
                flags: 0,
                blob: try ffiEncodeGroupOp(op: op)
            )
        }

        /// The scope a group operation's record takes: its group, or nil
        /// (personal) for "you" and the account's own settings.
        static func scope(of op: GroupOpIn) -> String? {
            switch op {
            case .create(let groupId, _, _, _), .addMember(let groupId, _),
                 .renameMember(let groupId, _, _), .renameGroup(let groupId, _),
                 .removeMember(let groupId, _), .groupPicture(let groupId, _, _),
                 .claim(let groupId, _, _, _), .memberProfile(let groupId, _, _, _, _),
                 .placeAlias(let groupId, _, _), .heldPlace(let groupId, _, _):
                // D-229: a place alias is the group's (only shareAliases writes it there).
                // D-230: and so is a held place (only sharePlaces and acceptInvite write it there).
                return groupId
            case .you, .groupKey, .security, .deviceName, .avatar, .setting:
                // D-158: a group key is personal too: the account's own log
                // only, sealed with the account key. D-178: and so is the
                // account's Maximum security choice. D-185: and each phone's
                // name. D-244 (ADR-0043): and each setting that follows the account.
                return nil
            }
        }

        /// Appends operations as records, each stamped and authored here, so
        /// the next push carries them. Encoding is core's; a refusal throws.
        /// D-229: `placing` maps a rename or a removal of yourself to the id
        /// the group knows you by (`Placing.op`); REQUIRED, so every writer
        /// says which (`.none` for what is not about yourself in a group).
        static func appendGroupOps(
            _ store: LedgerStore, _ ops: [GroupOpIn], placing: Placing,
            nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
        ) throws {
            guard !ops.isEmpty else { return }
            // Every operation is mapped (or refused) before anything is written.
            let mapped = try ops.map { try placing.op($0) }
            let device = try deviceId(store)
            for op in mapped {
                let record = try groupOpRecord(op, hlc: try nextHlc(store, nowMs: nowMs), deviceId: device)
                let vault = try vaultOf(store)
                defer { try? vault.closeVault() }
                try vault.putRecord(record: record)
            }
        }

        /// D-247 (decision 170): the groups this phone created: a `.create` record authored by this device (its id, never
        /// another phone's of the same account). Read only: a vault with no device id yet authored nothing. A record core
        /// cannot decode is passed over. The creator fall-back's input (`CreatorFallBack`), display only.
        static func createdHere(_ store: LedgerStore) throws -> Set<String> {
            let device: String? = try {
                let vault = try vaultOf(store)
                defer { try? vault.closeVault() }
                return try vault.kvGet(key: deviceKey).map { $0.map { String(format: "%02x", $0) }.joined() }
            }()
            guard let device else { return [] }
            var made = Set<String>()
            for record in try allRecordsOf(store) where record.recordType == groupOpType && record.authorDeviceId == device {
                if case .create(let groupId, _, _, _) = try? ffiDecodeGroupOp(blob: record.blob) { made.insert(groupId) }
            }
            return made
        }

        static func projectGroups(_ store: LedgerStore) throws -> GroupsProjectionOut {
            try ffiProjectGroups(records: try allRecordsOf(store)
                .filter { $0.recordType == groupOpType }
                .map {
                    GroupOpRecordIn(
                        hlcMillis: $0.hlcMillis, hlcCounter: $0.hlcCounter,
                        hlcDeviceId: $0.hlcDeviceId, blob: $0.blob
                    )
                })
        }

        /// The kv groups, strictly: absent is none, unreadable THROWS. A
        /// rebuild must never overwrite a group list it could not read.
        private static func kvGroups(_ store: LedgerStore) throws -> [GroupDTO] {
            let vault = try vaultOf(store)
            defer { try? vault.closeVault() }
            guard let data = try vault.kvGet(key: LedgerStore.groupsKey) else { return [] }
            do {
                return try JSONDecoder().decode([GroupDTO].self, from: data)
            } catch {
                throw LoadError.corrupt
            }
        }

        private static func kvSelf(_ store: LedgerStore) throws -> SelfDTO? {
            let vault = try vaultOf(store)
            defer { try? vault.closeVault() }
            guard let data = try vault.kvGet(key: LedgerStore.selfKey) else { return nil }
            return try? JSONDecoder().decode(SelfDTO.self, from: data)
        }

        /// Content-based and idempotent: every kv group that has no create
        /// record, and a kv "you" with no `you` record, get one. Runs before
        /// every push and every group rebuild, so a device that kept groups
        /// only in kv (every device before D-153) syncs them. An operation core
        /// refuses (damaged legacy data) is skipped, and the rebuild's union
        /// rule keeps that group in kv. Returns how many records were minted.
        @discardableResult
        static func backfillGroupRecords(
            _ store: LedgerStore,
            nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
        ) throws -> Int {
            let p = try projectGroups(store)
            let created = Set(p.groups.map { $0.group.id })
            var ops: [GroupOpIn] = []
            for g in try kvGroups(store) where !created.contains(g.id) {
                let names = Dictionary(
                    (g.memberNames ?? []).map { ($0.id, $0.name) },
                    uniquingKeysWith: { first, _ in first }
                )
                ops.append(.create(
                    groupId: g.id, name: g.name, settlementCurrency: g.settlementCurrency,
                    members: g.members.map { GroupOpMemberIn(id: $0, name: names[$0]) }
                ))
            }
            let yous = Set([p.you?.id].compactMap { $0} + p.otherYouIds)
            if let me = try kvSelf(store), !yous.contains(me.id) {
                ops.append(.you(id: me.id, name: me.name))
            }
            var minted = 0
            for op in ops where (try? ffiEncodeGroupOp(op: op)) != nil {
                // D-229: a backfill re-creates what was already written (never mapped).
                try appendGroupOps(store, [op], placing: .none, nowMs: nowMs)
                minted += 1
            }
            return minted
        }

        /// D-211: a picture kept in kv before pictures were records (every phone
        /// before 0.5.0) becomes one `.avatar` record, once. Content-based and
        /// idempotent: nothing is written when ANY avatar record is in the
        /// vault, a clearing one included (another phone's "no picture" must
        /// never be undone by an old kept one), and the flag is set either way.
        /// A picture that cannot be written (core refuses it) leaves the flag unset and the kv value
        /// untouched: the rebuild writes kv from the records only once the flag
        /// is set, so it is never overwritten with "none" meanwhile. Returns
        /// true when it wrote a record.
        ///
        /// Final review I1(c): the record is stamped LOW (`migratedHlc`), not
        /// now. On a second phone the migration can run before that phone has
        /// the account's newer picture (or its clearing); a fresh stamp would
        /// beat it on every phone. Any deliberate picture, set at any time,
        /// beats a migrated one.
        @discardableResult
        static func migrateAvatarOnce(_ store: LedgerStore) throws -> Bool {
            if let done = try store.kvGet(key: avatarRecordKey), !done.isEmpty { return false }
            var wrote = false
            if try !hasAvatarRecord(store),
               let kept = try store.kvGet(key: LedgerStore.avatarKey) {
                var avatar = Avatar.decode(kept)
                // An old photo has no size cap: step it down to what core takes.
                if case .photo(let data) = avatar, data.count > ProfileRules.photoMaxBytes {
                    guard let smaller = ProfileRules.storedPhoto(from: data) else { throw LoadError.corrupt }
                    avatar = .photo(smaller)
                }
                if avatar != .none {
                    let parts = avatar.recordParts
                    let record = try groupOpRecord(
                        .avatar(emoji: parts.emoji, photoHex: parts.photoHex),
                        hlc: migratedHlc, deviceId: try deviceId(store))
                    let vault = try vaultOf(store)
                    defer { try? vault.closeVault() }
                    try vault.putRecord(record: record)
                    wrote = true
                }
            }
            try store.kvSet(key: avatarRecordKey, value: Data("1".utf8))
            return wrote
        }

        /// The stamp of a migrated picture: below every stamp a phone makes
        /// (its wall clock), so it never beats a picture set deliberately.
        static let migratedHlc = HlcStamp(millis: 1, counter: 0)

        /// D-211: any personal `.avatar` record in the vault, a clearing one
        /// included (the projection reads the same records, but shows a
        /// clearing as no picture, which is not the same as none written).
        static func hasAvatarRecord(_ store: LedgerStore) throws -> Bool {
            try allRecordsOf(store).contains { record in
                guard record.recordType == groupOpType, record.groupId == nil else { return false }
                if case .avatar? = try? ffiDecodeGroupOp(blob: record.blob) { return true }
                return false
            }
        }

        /// Records → `ledger/groups` and `ledger/self`. THE UNION RULE: a kv
        /// group with no create record stays, after the projected ones, so a
        /// rebuild can never erase a group the records do not hold yet (D-122
        /// #3's trap, which a first pull would otherwise spring on every
        /// pre-D-153 vault). "You" is the account's one "you" (the founder,
        /// 2026-09-24); other "you" ids are kept as aliases until the merge.
        static func rebuildGroups(_ store: LedgerStore) throws {
            try backfillGroupRecords(store)
            // D-211: a refusal leaves the kv picture where it is (see above).
            // A picture migrated now is shared to the groups (final review
            // I1(a)): the share may already have run without it.
            if (try? migrateAvatarOnce(store)) == true {
                Task { @MainActor in ProfileShare.request(store: store) }
            }
            let p = try projectGroups(store)
            let current = try kvSelf(store)
            // D-210: this account is never taken out of its own copy. A
            // removed joiner's group stays readable (read-only): "you" must
            // still be in it, or no screen could name the group at all.
            // (Unless it has a current place there: see `hasPlace` below.)
            let mine = Set([p.you?.id, current?.id].compactMap { $0 } + p.otherYouIds + (current?.aliases ?? []))
            let projected = p.groups.map { g -> GroupDTO in
                let named = g.members.compactMap { m in m.name.map { MemberDTO(id: m.id, name: $0) } }
                // D-210: a removed place of this account is kept only while the
                // account has no current one here; once it was invited back
                // under a new place, the old one goes like anyone removed.
                let hasPlace = g.group.members.contains { mine.contains($0) && !g.removed.contains($0) }
                return GroupDTO(
                    id: g.group.id, name: g.group.name,
                    // D-186: someone taken out of the group is left out of
                    // the people every screen offers; records keep their id.
                    settlementCurrency: g.group.settlementCurrency,
                    members: g.group.members.filter { !g.removed.contains($0) || (mine.contains($0) && !hasPlace) },
                    memberNames: named.isEmpty ? nil : named
                )
            }
            let ids = Set(projected.map(\.id))
            let kvOnly = try kvGroups(store).filter { !ids.contains($0.id) }
            let vault = try vaultOf(store)
            defer { try? vault.closeVault() }
            try vault.kvSet(key: LedgerStore.groupsKey, value: try LedgerStore.kvEncoder().encode(projected + kvOnly))
            // D-187: the pictures every group tile draws.
            let pictures = Dictionary(
                p.groups.compactMap { g in g.picture.map { (g.group.id, PictureDTO(emoji: $0.emoji, photoHex: $0.photoHex)) } },
                uniquingKeysWith: { a, _ in a }
            )
            try vault.kvSet(key: LedgerStore.picturesKey, value: try LedgerStore.kvEncoder().encode(pictures))
            // D-211: the groups' joined people with their previews.
            let previews = Dictionary(
                p.groups.compactMap { g -> (String, [String: PictureDTO])? in
                    g.profiles.isEmpty ? nil : (g.group.id, Dictionary(
                        g.profiles.map { ($0.memberId, PictureDTO(emoji: $0.emoji, photoHex: $0.thumbHex)) },
                        uniquingKeysWith: { a, _ in a }))
                },
                uniquingKeysWith: { a, _ in a }
            )
            try vault.kvSet(key: LedgerStore.memberPreviewsKey, value: try LedgerStore.kvEncoder().encode(previews))
            // D-211: your picture, from the records, once it is one (until the
            // migration has run, an absent record must not wipe a kept picture).
            let migrated = (try vault.kvGet(key: avatarRecordKey)).map { !$0.isEmpty } ?? false
            if p.avatar != nil || migrated {
                let avatar = Avatar(emoji: p.avatar?.emoji, photoHex: p.avatar?.photoHex)
                try vault.kvSet(key: LedgerStore.avatarKey, value: try avatar.encoded())
            }
            if let you = p.you {
                // A name written for "you" wins; else the name this device
                // already had for itself, since it is the same person.
                let sameYou = current.map { $0.id == you.id || p.otherYouIds.contains($0.id) } ?? false
                let me = SelfDTO(
                    id: you.id,
                    name: you.name ?? (sameYou ? current?.name : nil),
                    aliases: p.otherYouIds.isEmpty ? nil : p.otherYouIds
                )
                try vault.kvSet(key: LedgerStore.selfKey, value: try LedgerStore.kvEncoder().encode(me))
            }
        }
    }

    /// D-141: every expense as core projects it from the record path (edits
    /// and tombstones applied, deleted ones INCLUDED and flagged), with its
    /// id, "added by", "last edited by" and any money conflict. The legacy
    /// backfill runs first, so a pre-record vault's rows are in it.
    func projectedExpenses() throws -> ExpenseProjectionOut {
        try Sync.backfillLegacyRecords(self)
        return try Sync.project(try allRecords())
    }

    /// D-141: writes one revision (an edit, a delete or an Undo) of the
    /// expense `revision.target`, then rebuilds the kv ledger. The record
    /// takes its expense's group scope, and it is stamped and authored like
    /// any other local record, so the next push carries it.
    func reviseExpense(
        _ revision: ExpenseRevisionIn,
        nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) throws {
        // D-235 round 3 (decision 112): an edit's note keeps the same bound, before anything is read or written.
        try NoteLimit.check(revision.note)
        let current = try projectedExpenses()
        guard let target = current.expenses.first(where: { $0.id == revision.target }) else {
            throw LoadError.unknownExpense
        }
        // D-249 (decision 208): new money names only the ledger's people, or the people the expense already had (an
        // edit keeps someone who has since left the group).
        if let money = revision.money {
            let had = [target.expense.payer] + target.expense.shares.map(\.id)
            var members = try ledgerMembers(target.expense.group)
            for id in had where !members.contains(id) { members.append(id) }
            try ffiCheckExpenseMembers(expense: money, members: members)
        }
        // D-229: the editor and new money as the group knows this account.
        let placed = try Placing.load(self).revision(revision, group: target.expense.group)
        let blob = try ffiEncodeExpenseRevision(revision: placed)
        let hlc = try Sync.nextHlc(self, nowMs: nowMs)
        let device = try Sync.deviceId(self)
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        do {
            try vault.putRecord(record: RecordWire(
                version: 1,
                recordType: Sync.revisionType,
                groupId: target.expense.group,
                epoch: 0,
                hlcMillis: hlc.millis,
                hlcCounter: hlc.counter,
                hlcDeviceId: device,
                authorDeviceId: device,
                // "REV\x01": the same constant-session rule as "EXP\x01" above
                // (a header field, never nonce material; nothing seals records).
                nonceSession: Data([0x52, 0x45, 0x56, 0x01]),
                flags: 0,
                blob: blob
            ))
            try vault.closeVault()
        } catch {
            try? vault.closeVault()
            throw error
        }
        try Sync.rebuildProjection(self)
    }

    /// Every record in the vault, in the record path's own HLC order.
    func allRecords() throws -> [RecordWire] {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        return try vault.allRecords()
    }

    /// Applies one record from the wire. Idempotent by identity.
    func applyRecord(_ record: RecordWire) throws -> Bool {
        try Sync.applyRecord(self, record)
    }

    /// Rebuilds the kv projection from the record path.
    func rebuildProjection() throws {
        try Sync.rebuildProjection(self)
    }
}

extension LedgerStore.SettlementDTO {
    var asInput: SettlementInput {
        SettlementInput(
            from: from,
            to: to,
            amountMinor: amountMinor,
            currencyCode: currencyCode,
            group: group
        )
    }
}

extension LedgerStore.ExpenseDTO {
    /// D-141: a projected expense back in the kv ledger's shape. Lossless for
    /// an unedited expense, so the kv ledger reads exactly as before D-141.
    init(projected p: ProjectedExpenseOut) {
        self.init(
            payer: p.expense.payer,
            paidMinor: p.expense.paidMinor,
            currencyCode: p.expense.currencyCode,
            group: p.expense.group,
            conversion: p.expense.conversion.map {
                LedgerStore.ConversionDTO(
                    convertedMinor: $0.convertedMinor,
                    convertedCurrency: $0.convertedCurrency,
                    rateNum: $0.rateNum,
                    rateDen: $0.rateDen,
                    rateTimestampMs: $0.rateTimestampMs
                )
            },
            shares: p.expense.shares.map { ShareDTO(id: $0.id, amountMinor: $0.amountMinor) },
            note: p.note,
            timestampMs: p.timestampMs,
            category: p.category,
            addedBy: p.addedBy
        )
    }

    var asInput: ExpenseInput {
        ExpenseInput(
            payer: payer,
            paidMinor: paidMinor,
            currencyCode: currencyCode,
            shares: shares.map { IdAmount(id: $0.id, amountMinor: $0.amountMinor) },
            group: group,
            conversion: conversion.map {
                ConversionInput(
                    convertedMinor: $0.convertedMinor,
                    convertedCurrency: $0.convertedCurrency,
                    rateNum: $0.rateNum,
                    rateDen: $0.rateDen,
                    rateTimestampMs: $0.rateTimestampMs
                )
            }
        )
    }
}

extension LedgerStore {
    /// The app's standard vault location (mirrors SkiptiApp's path).
    static func defaultStore() -> LedgerStore {
        let dir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ledger", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        VaultBackup.exclude(dir)
        return LedgerStore(path: dir.appendingPathComponent("ledger-vault.db").path)
    }
}

/// D-162 (SPEC §2.3 A7, §3.7): the vault never goes into an iCloud or
/// computer backup. Its key is `WhenUnlockedThisDeviceOnly`, so a restored
/// copy could not be opened anyway, and it would land the person on the
/// "vault will not open" screen; the way back on a new phone is sync or the
/// Recovery Kit. The flag is on the vault's folder, so the database's side
/// files (-wal, -shm) are covered too. Set on every launch: it is cheap, and
/// a failure is retried next time instead of stopping the app.
enum VaultBackup {
    static func exclude(_ dir: URL) {
        var url = dir
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        do {
            try url.setResourceValues(values)
        } catch {
            print("VaultBackup: could not exclude \(dir.lastPathComponent) from backup: \(error)")
        }
    }

    /// Read from disk, not from a URL's cache: Foundation caches resource
    /// values per URL, so a URL that saw the old value keeps reporting it.
    static func isExcluded(_ dir: URL) -> Bool {
        let fresh = URL(fileURLWithPath: dir.path, isDirectory: true)
        return (try? fresh.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup == true
    }
}

// MARK: - D-210: read-only copies and forgetting a group

extension LedgerStore {
    /// How a group became a copy: removed by someone, or deleted by the owner.
    struct ReadOnly: Codable, Equatable {
        var kind: String
        var by: String
        var at: Date
        /// This phone's own account did it (the owner who deleted the group).
        var byYou: Bool? = nil

        /// D-229 decision 45: the kind of a group where this account lost its
        /// place (core's `placeLost`). Never stored: it is read from the
        /// records each time, so it lifts by itself once the owner fixes People.
        static let placeLostKind = "placeLost"
        static let placeLost = ReadOnly(kind: placeLostKind, by: "", at: Date(timeIntervalSince1970: 0))
    }

    static let readOnlyKey = "ledger/readonly"

    /// D-229 decision 45: every group where this account lost its place, as a
    /// read-only copy. Pure, so it is tested alone.
    static func placeLostCopies(_ p: GroupsProjectionOut) -> [String: ReadOnly] {
        Dictionary(p.groups.filter(\.placeLost).map { ($0.group.id, ReadOnly.placeLost) }, uniquingKeysWith: { a, _ in a })
    }

    /// The groups that are read-only copies on this phone, by group id. None
    /// when the list cannot be read: the cloud refuses a removed person's
    /// writes anyway, so a failed read can only mean a refused upload. D-229:
    /// the groups where this account lost its place are read-only too, and
    /// one list failing never drops the other.
    func readOnly() -> [String: ReadOnly] {
        let lost = (try? Self.placeLostCopies(Sync.projectGroups(self))) ?? [:]
        return lost.merging((try? readOnlyMarks()) ?? [:]) { _, mark in mark }
    }

    /// The read-only copies, strictly: absent is none, unreadable THROWS
    /// (the banner must not say "not a copy" because a read failed). The
    /// stored marks (removed, deleted) and, D-229 decision 45, the groups
    /// where this account lost its place; a stored mark wins.
    func readOnlyChecked() throws -> [String: ReadOnly] {
        try Self.placeLostCopies(Sync.projectGroups(self)).merging(try readOnlyMarks()) { _, mark in mark }
    }

    /// The stored marks only (D-210): what `markReadOnly` and `clearReadOnly`
    /// rewrite, so a derived copy is never written down.
    private func readOnlyMarks() throws -> [String: ReadOnly] {
        guard let data = try kvGet(key: Self.readOnlyKey), !data.isEmpty else { return [:] }
        do {
            return try JSONDecoder().decode([String: ReadOnly].self, from: data)
        } catch {
            throw LoadError.corrupt
        }
    }

    func markReadOnly(_ groupID: String, _ readOnly: ReadOnly) throws {
        // Strict: a list that cannot be read is never overwritten with one mark.
        var all = try readOnlyMarks()
        all[groupID] = readOnly
        try kvSet(key: Self.readOnlyKey, value: try Self.kvEncoder().encode(all))
        // D-211: a removed or deleted group never takes this account's
        // profile. The mark is what matters; a failed drop never undoes it
        // (the share's next pass drops a read-only group too).
        try? ProfileShare.dropWaiting(self, groupID)
    }

    /// The group is shared with this account again (an invite accepted after a
    /// removal): it takes records again.
    func clearReadOnly(_ groupID: String) throws {
        var all = try readOnlyMarks()
        guard all.removeValue(forKey: groupID) != nil else { return }
        try kvSet(key: Self.readOnlyKey, value: try Self.kvEncoder().encode(all))
    }

    /// "Remove from this phone": the group's records go (core), and with them
    /// everything this phone derived from them: the group and its picture,
    /// its expenses, payments and redistributions, its read-only mark, its
    /// cached roles, its sync cursor and its place in the groups already up.
    /// Nothing else is touched.
    func forgetGroup(_ groupID: String) throws {
        try forgetInVault(groupID)
        // D-211: and it is no longer waited for by the profile share, after
        // the vault work, as markReadOnly does. A failed drop never undoes the
        // forget (final review M3, Android's form).
        try? ProfileShare.dropWaiting(self, groupID)
        // And the roles held in memory, so no screen offers this group's.
        if Thread.isMainThread {
            MainActor.assumeIsolated { GroupRoles.shared.forget(groupID) }
        } else {
            Task { @MainActor in GroupRoles.shared.forget(groupID) }
        }
    }

    /// `forgetGroup`'s vault work, in one vault session.
    private func forgetInVault(_ groupID: String) throws {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        try vault.forgetGroup(group: groupID)
        func rewrite<T: Codable>(_ key: String, _ type: T.Type, _ keep: (T) -> T) throws {
            guard let data = try vault.kvGet(key: key), let list = try? JSONDecoder().decode(type, from: data) else { return }
            try vault.kvSet(key: key, value: try Self.kvEncoder().encode(keep(list)))
        }
        try rewrite(Self.groupsKey, [GroupDTO].self) { $0.filter { $0.id != groupID } }
        try rewrite(Self.picturesKey, [String: PictureDTO].self) { $0.filter { $0.key != groupID } }
        try rewrite(Self.memberPreviewsKey, [String: [String: PictureDTO]].self) { $0.filter { $0.key != groupID } }
        try rewrite(Self.key, [ExpenseDTO].self) { $0.filter { $0.group != groupID } }
        try rewrite(Self.settlementsKey, [SettlementDTO].self) { $0.filter { $0.group != groupID } }
        try rewrite(Self.redistributionsKey, [RedistributionDTO].self) { $0.filter { $0.group != groupID } }
        try rewrite(Self.readOnlyKey, [String: ReadOnly].self) { $0.filter { $0.key != groupID } }
        // The vault has no delete for a key: an empty value reads as absent
        // (no cached roles, cursor 0).
        try vault.kvSet(key: GroupRoles.key(groupID), value: Data())
        try vault.kvSet(key: CloudRecords.cursorKey(groupID), value: Data())
        if let data = try vault.kvGet(key: CloudRecords.groupsUpKey), !data.isEmpty {
            let kept = String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init).filter { $0 != groupID }
            try vault.kvSet(key: CloudRecords.groupsUpKey, value: Data(kept.joined(separator: "\n").utf8))
        }
    }
}
