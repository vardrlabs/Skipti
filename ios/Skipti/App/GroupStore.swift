import Foundation

/// Groups and people on iOS (D-135). The founder decided on 2026-09-14:
/// many groups; add people by name and link them to a device later; your name
/// is asked once and shown as "Tim (You)" to you and "Tim" to everyone else;
/// a fresh install contains nothing from testing.
///
/// Ids and name rules live in core (D-134): `ffiNewParticipantId`,
/// `ffiMemberName`, `ffiCreateNamedGroup`, `ffiAddMember`, `ffiRenameMember`,
/// and `ffiNamedGroup` for validating what comes back out of the vault. This
/// file only stores and loads.
extension LedgerStore {
    static let selfKey = "ledger/self"
    static let selectedGroupKey = "ledger/selected-group"

    /// This device's own participant. `name` stays nil until the person types
    /// it, and nothing is shown or shared under an invented name.
    struct SelfDTO: Codable, Equatable {
        var id: String
        var name: String?
        /// D-153: other ids this account's devices wrote as "you" before they
        /// met (the founder: one "you" per account). Until the merge unit
        /// they only let a group made under one of them show it as "(You)".
        var aliases: [String]? = nil
    }

    /// A member as stored, in the group's member order.
    struct MemberDTO: Codable, Equatable {
        var id: String
        var name: String
    }

    /// The pre-D-135 fixture people. Kept ONLY to migrate vaults that the old
    /// seeding created (the founder's simulator ledger); nothing writes these
    /// ids into a new vault.
    enum LegacyFixture {
        static let ada = String(repeating: "a", count: 31) + "1"
        static let names: [String: String] = [
            String(repeating: "a", count: 31) + "2": "Rahul",
            String(repeating: "a", count: 31) + "3": "Priya",
            String(repeating: "a", count: 31) + "4": "Amit",
        ]
    }

    enum GroupStoreError: Error, Equatable {
        /// A group needs your name first ("Tim (You)").
        case needsYourName
        case unknownGroup
    }

    // MARK: - Reading, never seeding

    /// The stored groups, or none. A corrupt group list is an error, never an
    /// empty list: D-127's lesson that `try?` on the vault turns "cannot
    /// read" into "nothing there".
    func loadGroups() throws -> [GroupDTO] {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        guard let data = try vault.kvGet(key: Self.groupsKey) else { return [] }
        do {
            return try JSONDecoder().decode([GroupDTO].self, from: data)
        } catch {
            throw LoadError.corrupt
        }
    }

    func loadSelf() throws -> SelfDTO? {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        guard let data = try vault.kvGet(key: Self.selfKey) else { return nil }
        do {
            return try JSONDecoder().decode(SelfDTO.self, from: data)
        } catch {
            throw LoadError.corrupt
        }
    }

    func selectedGroupID() throws -> String? {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        return try vault.kvGet(key: Self.selectedGroupKey).flatMap { String(data: $0, encoding: .utf8) }
    }

    // MARK: - You

    /// This device's self, created on first need. A vault that the old
    /// fixture seeding populated adopts the fixture Ada's id: its expenses
    /// already name that id as payer, so migrating rewrites no record.
    func ensureSelf() throws -> SelfDTO {
        if let existing = try loadSelf() { return existing }
        let legacy = try loadGroups().contains { $0.members.contains(LegacyFixture.ada) }
        let created = SelfDTO(id: legacy ? LegacyFixture.ada : ffiNewParticipantId(), name: nil)
        try write(created, key: Self.selfKey)
        // D-153: "you" is a record, so the account's other devices know it.
        try Sync.appendGroupOps(self, [.you(id: created.id, name: nil)], placing: .none)
        return created
    }

    /// Sets your name (trimmed and validated by core) and carries it into
    /// every group you are in, so others see the new name too.
    @discardableResult
    func setYourName(_ raw: String) throws -> SelfDTO {
        let name = try ffiMemberName(raw: raw)
        var me = try ensureSelf()
        me.name = name
        try write(me, key: Self.selfKey)
        try Sync.appendGroupOps(self, [.you(id: me.id, name: name)], placing: .none)
        // D-229 decision 45: a group where this account lost its place keeps its names (nothing is written there).
        let lost = try Placing.load(self).lost
        let renamed = try namedGroups().map { named -> NamedGroupWire in
            named.members.contains(where: { $0.id == me.id }) && !lost.contains(named.group.id)
                ? try ffiRenameMember(named: named, memberId: me.id, name: name)
                : named
        }
        try save(renamed)
        return me
    }

    // MARK: - Groups with names

    /// Every group with its people, validated by core. Each member's name is
    /// resolved on its own: the stored name, else yours, else the old fixture
    /// name, else "Member N" by position. Placeholders are distinct, so core's
    /// duplicate-name rule never refuses a group only because names were never
    /// stored. Throws `needsYourName` while you are in a group that has no
    /// stored name for you and you have not given one.
    func namedGroups() throws -> [NamedGroupWire] { try readGroups().named }

    /// D-210 (Task 8 look): a group this account has no place in yet.
    struct WaitingGroup: Equatable {
        let id: String
        let name: String
    }

    /// The groups still arriving: the records name this account's place (a
    /// claim) before the create or addMember that makes it. Never in
    /// `namedGroups()`, so no balance, overview, picker or write meets them;
    /// Groups lists them as waiting, and they resolve when the rest arrives.
    func waitingGroups() throws -> [WaitingGroup] { try readGroups().waiting }

    private func readGroups() throws -> (named: [NamedGroupWire], waiting: [WaitingGroup]) {
        let me = try ensureSelf()
        let groups = try loadGroups()
        // D-153: "you" in a group is your id, or else an id another of your
        // devices used as "you" before it adopted the account's (an alias).
        func selfID(in group: GroupDTO) -> String {
            if group.members.contains(me.id) { return me.id }
            return (me.aliases ?? []).first(where: group.members.contains) ?? me.id
        }
        let waitingForYou = groups.contains { group in
            let id = selfID(in: group)
            return group.members.contains(id)
                && !(group.memberNames ?? []).contains(where: { $0.id == id })
        }
        if waitingForYou, me.name == nil {
            throw GroupStoreError.needsYourName
        }
        var named: [NamedGroupWire] = []
        var waiting: [WaitingGroup] = []
        for group in groups {
            let you = selfID(in: group)
            // Only this case is skipped: this account has no place in the
            // group yet (core would say UnknownParticipant). Any other refusal,
            // and a vault that will not open, still throws.
            guard group.members.contains(you) else {
                waiting.append(WaitingGroup(id: group.id, name: group.name))
                continue
            }
            let stored = Dictionary(
                (group.memberNames ?? []).map { ($0.id, $0.name) },
                uniquingKeysWith: { first, _ in first }
            )
            let members = group.members.enumerated().map { index, id -> MemberWire in
                if let name = stored[id] { return MemberWire(id: id, name: name) }
                if id == you, let name = me.name { return MemberWire(id: id, name: name) }
                if let name = LegacyFixture.names[id] { return MemberWire(id: id, name: name) }
                return MemberWire(id: id, name: String(localized: "Member \(index + 1)"))
            }
            named.append(try ffiNamedGroup(
                group: GroupWire(
                    id: group.id,
                    name: group.name,
                    settlementCurrency: group.settlementCurrency,
                    members: group.members
                ),
                selfId: you,
                members: members
            ))
        }
        return (named, waiting)
    }

    /// Creates a group with you in it plus the people named, selects it, and
    /// returns it. Needs your name first.
    @discardableResult
    func createGroup(name: String, settlementCurrency: String, people: [String]) throws -> NamedGroupWire {
        let me = try ensureSelf()
        guard let myName = me.name else { throw GroupStoreError.needsYourName }
        let created = try ffiCreateNamedGroup(
            name: name,
            settlementCurrency: settlementCurrency,
            selfMember: MemberWire(id: me.id, name: myName),
            otherNames: people.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        )
        // `try`, never `try?`: a group list that cannot be read must stop the
        // save. `try? … ?? []` would write ONLY the new group and silently
        // erase every existing one (D-127's `try?` lesson, found in this draft).
        let existing = try namedGroups()
        try save(existing + [created])
        try selectGroup(id: created.group.id)
        return created
    }

    /// D-166: keeps a group built elsewhere (an import builds it with
    /// `ffiCreateNamedGroup` before anything is saved), and selects it.
    func adopt(_ created: NamedGroupWire) throws {
        let existing = try namedGroups()
        try save(existing + [created])
        try selectGroup(id: created.group.id)
    }

    @discardableResult
    func addMember(groupID: String, name: String) throws -> NamedGroupWire {
        try update(groupID: groupID) { try ffiAddMember(named: $0, name: name) }
    }

    @discardableResult
    func renameMember(groupID: String, memberID: String, name: String) throws -> NamedGroupWire {
        try update(groupID: groupID) { try ffiRenameMember(named: $0, memberId: memberID, name: name) }
    }

    /// D-186 (the founder: only someone with no history): why a person
    /// cannot be taken out of a group, or nil when they can. Never you; not
    /// someone who joined on a phone (taking them out would need the group's
    /// key rotated, SPEC §3); never someone in an expense or payment here,
    /// whose records must keep their name. Android's twin is in LedgerStore.kt.
    func removalRefusal(groupID: String, memberID: String) throws -> MemberCopy.Refusal? {
        guard let named = try namedGroups().first(where: { $0.group.id == groupID }) else {
            throw GroupStoreError.unknownGroup
        }
        if memberID == named.selfId { return .you }
        let claimed = try Sync.projectGroups(self).groups.first { $0.group.id == groupID }?
            .claims.contains { $0.memberId == memberID } ?? false
        if claimed { return .joined }
        let history = ffiMemberHasHistory(
            expenses: try loadExpenses().map(\.asInput), settlements: try loadSettlements().map(\.asInput),
            redistributions: try loadRedistributions(), group: groupID, memberId: memberID
        )
        return history ? .history : nil
    }

    /// D-187: every group's picture, by group id; none for a group showing
    /// its initial.
    func groupPictures() throws -> [String: PictureDTO] {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        guard let data = try vault.kvGet(key: Self.picturesKey) else { return [:] }
        return (try? JSONDecoder().decode([String: PictureDTO].self, from: data)) ?? [:]
    }

    /// D-187: a group's picture, as a group record every member's phone
    /// applies: an emoji, a JPEG, or neither for the initial. Core refuses a
    /// photo that is not a JPEG or is over 64,000 bytes. D-229 decision 45:
    /// refused in a group where this account lost its place.
    func setGroupPicture(groupID: String, emoji: String? = nil, jpeg: Data? = nil) throws {
        try Sync.appendGroupOps(self, [.groupPicture(groupId: groupID, emoji: emoji, photoHex: jpeg.map(HexCodec.encode))],
                                placing: try Placing.load(self))
        try Sync.rebuildGroups(self)
    }

    /// D-186: takes a person out of a group, as an operation every phone and
    /// every member's phone applies; refused as `removalRefusal` says.
    @discardableResult
    func removeMember(groupID: String, memberID: String) throws -> NamedGroupWire {
        if let why = try removalRefusal(groupID: groupID, memberID: memberID) { throw MemberCopy.Refused(why: why) }
        // D-229: a removal of one of your own ids names the group's place for you.
        try Sync.appendGroupOps(self, [.removeMember(groupId: groupID, memberId: memberID)], placing: try Placing.load(self))
        try Sync.rebuildGroups(self)
        guard let named = try namedGroups().first(where: { $0.group.id == groupID }) else {
            throw GroupStoreError.unknownGroup
        }
        return named
    }

    func selectGroup(id: String) throws {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        try vault.kvSet(key: Self.selectedGroupKey, value: Data(id.utf8))
    }

    /// The group to show: the selected one if it still exists, else the first.
    func currentGroup() throws -> NamedGroupWire? {
        let groups = try namedGroups()
        let selected = try selectedGroupID()
        return groups.first(where: { $0.group.id == selected }) ?? groups.first
    }

    // MARK: - Labels

    /// "Tim (You)" for yourself, "Tim" for everyone else. The "(You)" is
    /// display copy for this device only; the stored and shared name is "Tim".
    static func label(for memberID: String, in named: NamedGroupWire) -> String {
        guard let member = named.members.first(where: { $0.id == memberID }) else {
            return String(localized: "Unknown member")
        }
        return memberID == named.selfId
            ? String(localized: "\(member.name) (You)")
            : member.name
    }

    /// `label(for:in:)` for a row of the group's history: a person the named
    /// group no longer holds (removed) is named from `history`
    /// (`historyNames(group:)`), "Unknown member" only when even that has no
    /// name. Who is still here reads exactly as `label(for:in:)`.
    static func label(for memberID: String, in named: NamedGroupWire, history: [String: String]) -> String {
        if named.members.contains(where: { $0.id == memberID }) { return label(for: memberID, in: named) }
        return history[memberID] ?? String(localized: "Unknown member")
    }

    /// D-210 last wave: the names a group's Who owes whom card reads, by id:
    /// everyone still here as `label(for:in:)`, and every removed person the
    /// history can name, so a removed person with a balance is never "Unknown
    /// member". An id with no name anywhere is left out (the card says so).
    static func balanceNames(_ named: NamedGroupWire, history: [String: String]) -> [String: String] {
        var out: [String: String] = [:]
        for id in Set(named.members.map(\.id)).union(history.keys) {
            out[id] = label(for: id, in: named, history: history)
        }
        return out
    }

    // MARK: - Private

    private func update(groupID: String, _ change: (NamedGroupWire) throws -> NamedGroupWire) throws -> NamedGroupWire {
        var groups = try namedGroups()
        guard let index = groups.firstIndex(where: { $0.group.id == groupID }) else {
            throw GroupStoreError.unknownGroup
        }
        groups[index] = try change(groups[index])
        try save(groups)
        return groups[index]
    }

    /// D-153: a change is saved as OPERATIONS, then the kv groups are rebuilt
    /// from the records, so this device and every other one see the same
    /// groups. The operations are the difference between the groups as they
    /// were and as they are now. A group with no create record yet (kept only
    /// in kv) is written whole, as a create, so no change to it is lost.
    private func save(_ groups: [NamedGroupWire]) throws {
        let before = try namedGroups()
        let created = Set(try Sync.projectGroups(self).groups.map { $0.group.id })
        // D-229: a rename of yourself (`setYourName`) names the group's place for you.
        try Sync.appendGroupOps(
            self, Self.operations(from: before, to: groups, created: created), placing: try Placing.load(self))
        try Sync.rebuildGroups(self)
    }

    /// The operations that turn `before` into `after`. Pure, so it is tested
    /// on its own.
    static func operations(
        from before: [NamedGroupWire], to after: [NamedGroupWire], created: Set<String>
    ) -> [GroupOpIn] {
        var ops: [GroupOpIn] = []
        for group in after {
            let members = group.members.map { GroupOpMemberIn(id: $0.id, name: $0.name) }
            guard created.contains(group.group.id),
                  let old = before.first(where: { $0.group.id == group.group.id }) else {
                ops.append(.create(
                    groupId: group.group.id, name: group.group.name,
                    settlementCurrency: group.group.settlementCurrency, members: members
                ))
                continue
            }
            if old.group.name != group.group.name {
                ops.append(.renameGroup(groupId: group.group.id, name: group.group.name))
            }
            for member in group.members {
                if let was = old.members.first(where: { $0.id == member.id }) {
                    if was.name != member.name {
                        ops.append(.renameMember(groupId: group.group.id, memberId: member.id, name: member.name))
                    }
                } else {
                    ops.append(.addMember(
                        groupId: group.group.id,
                        member: GroupOpMemberIn(id: member.id, name: member.name)
                    ))
                }
            }
        }
        return ops
    }

    private func write<T: Encodable>(_ value: T, key: String) throws {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        // D-151: the kv ledger's one byte form (sorted keys).
        try vault.kvSet(key: key, value: try LedgerStore.kvEncoder().encode(value))
    }
}
