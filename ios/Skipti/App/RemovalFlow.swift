import SwiftUI

/// D-210: taking a person out of a group: who sees what on the People screen,
/// the removal's records, the redistribution's arithmetic (core's) and the
/// screens that walk through it. Android's twin is RemovalFlow.kt.

/// A money string the way every screen has it: from core, never built here.
/// nil when core cannot format it: the line that would show it is hidden,
/// never shown with a stand-in glyph.
enum RolesMoney {
    static func format(_ minor: Int64, _ code: String) -> String? {
        try? ffiMoneyFormat(minorUnits: minor, currencyCode: code, locale: AppLocale.current)
    }

    static func spoken(_ minor: Int64, _ code: String) -> String {
        (try? ffiMoneySpoken(minorUnits: minor, currencyCode: code, locale: AppLocale.current)) ?? ""
    }
}

// MARK: - The removal's records (pure, bar the vault's clock)

enum Removal {
    /// The nonce session of a redistribution record ("RED\u{1}"): a header
    /// field, never nonce material (the same rule as the other records').
    static let redistributionSession = Data([0x52, 0x45, 0x44, 0x01])

    /// What `member` is owed (positive) or owes (negative) in each currency,
    /// nonzero only: `lines` are core's balance lines framed on `member`
    /// (`ffiBalanceLine` with `viewer: member`).
    static func plan(lines: [BalanceLineOut], member: String) -> [String: Int64] {
        var out: [String: Int64] = [:]
        for line in lines where line.viewerNetMinor != 0 {
            out[line.currencyCode] = line.viewerNetMinor
        }
        return out
    }

    /// One settlement per nonzero entry of the member's lines: a negative
    /// entry is what the member owes that person (the member pays), a
    /// positive one what that person owes the member (they pay). The
    /// direction rule of `SettleAll.settlement`, framed on `member`.
    static func settleFirst(lines: [BalanceLineOut], member: String, group: String) -> [LedgerStore.SettlementDTO] {
        lines.flatMap { line in
            line.entries.filter { $0.minorUnits != 0 }.map { entry in
                let owes = entry.minorUnits < 0
                return LedgerStore.SettlementDTO(
                    from: owes ? member : entry.id, to: owes ? entry.id : member,
                    amountMinor: abs(entry.minorUnits), currencyCode: line.currencyCode, group: group)
            }
        }
    }

    /// Whether core can write every payment's amount (a payment is never
    /// recorded that the screen could not show).
    static func canFormat(_ settlements: [LedgerStore.SettlementDTO]) -> Bool {
        settlements.allSatisfy { RolesMoney.format($0.amountMinor, $0.currencyCode) != nil }
    }

    /// Core's balance lines for `member` in `group`, from what this phone holds.
    static func lines(store: LedgerStore, group: String, member: String) throws -> [BalanceLineOut] {
        try ffiBalanceLine(
            expenses: try store.loadExpenses().filter { $0.group == group }.map(\.asInput),
            settlements: try store.loadSettlements().filter { $0.group == group }.map(\.asInput),
            redistributions: try store.loadRedistributions().filter { $0.group == group },
            removed: try store.removedIds(group: group), viewer: member, group: group)
    }

    /// Every participant's net per currency in the group (core's): old
    /// balances for the redistribution's rows.
    static func nets(store: LedgerStore, group: String) throws -> [String: [String: Int64]] {
        let lines = try ffiBalancesCompute(
            expenses: try store.loadExpenses().filter { $0.group == group }.map(\.asInput),
            settlements: try store.loadSettlements().filter { $0.group == group }.map(\.asInput),
            redistributions: try store.loadRedistributions().filter { $0.group == group })
        return Dictionary(lines.map { ($0.currencyCode, Dictionary($0.entries.map { ($0.id, $0.minorUnits) },
                                                                  uniquingKeysWith: { a, _ in a })) },
                          uniquingKeysWith: { a, _ in a })
    }

    /// The records of a removal: one type-6 record per redistribution, then
    /// the RemoveMember operation, each with a fresh stamp from this phone's
    /// clock (written before the record exists: a failed call wastes stamps,
    /// never reuses one). D-229: `placing` writes the shares and the removal
    /// as the group knows this account (`LedgerStore.Placing`); REQUIRED.
    static func records(
        store: LedgerStore, group: String, member: String, redistributions: [RedistributionInput],
        placing: LedgerStore.Placing, nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) throws -> [RecordWire] {
        let device = try LedgerStore.Sync.deviceId(store)
        // Mapped before any stamp is taken: one that cannot be written wastes none.
        let placed = try redistributions.map(placing.redistribution)
        let removal = try placing.op(.removeMember(groupId: group, memberId: member))
        var out: [RecordWire] = []
        for r in placed {
            let hlc = try LedgerStore.Sync.nextHlc(store, nowMs: nowMs)
            out.append(RecordWire(
                version: 1, recordType: LedgerStore.Sync.redistributionType, groupId: group, epoch: 0,
                hlcMillis: hlc.millis, hlcCounter: hlc.counter, hlcDeviceId: device, authorDeviceId: device,
                nonceSession: redistributionSession, flags: 0, blob: try ffiEncodeRedistribution(r: r)))
        }
        out.append(try LedgerStore.Sync.groupOpRecord(
            removal, hlc: try LedgerStore.Sync.nextHlc(store, nowMs: nowMs), deviceId: device))
        return out
    }

    /// The `removeMember` callable's `records`, in the Cloud contract's shape
    /// (nonce and blob in base64; the id is the record's HLC identity).
    static func payload(_ records: [RecordWire]) -> [[String: Any]] {
        records.map { r in
            [
                "id": CloudRecords.documentId(r),
                "type": String(r.recordType),
                "hlc": ["millis": Int64(r.hlcMillis), "counter": Int64(r.hlcCounter), "device": r.hlcDeviceId],
                "deviceId": r.authorDeviceId,
                "payload": [
                    "version": Int64(r.version), "epoch": Int64(r.epoch), "flags": Int64(r.flags),
                    "nonce": r.nonceSession.base64EncodedString(), "blob": r.blob.base64EncodedString(),
                ] as [String: Any],
            ]
        }
    }

    /// The records into this phone's vault, as the Function wrote them to the
    /// cloud: its own listener never brings them back (a record this device
    /// authored is refused as `.thisDevice`), so they are applied here, and
    /// `shared` marks them uploaded so `pushAll` never sends them again.
    static func applyLocally(_ records: [RecordWire], store: LedgerStore, uploaded markUploaded: Bool) throws {
        _ = try CloudRecords.apply(records, store: store)
        if markUploaded {
            var ids = try CloudRecords.uploaded(store)
            records.forEach { ids.insert(CloudRecords.documentId($0)) }
            try CloudRecords.saveUploaded(store, ids)
        }
        try LedgerStore.Sync.rebuildProjection(store)
    }

    /// Takes `member` out. A group in the cloud goes through the Function
    /// (the only writer of a removal; phones refuse a removal a phone wrote),
    /// then the same records go into this vault. A group only on this phone
    /// just appends them.
    @MainActor
    static func remove(
        store: LedgerStore, group: NamedGroupWire, member: MemberWire, place: String? = nil, you: String?, byName: String,
        redistributions: [RedistributionInput], shared: Bool
    ) async throws {
        let place = place ?? member.id
        let records = try records(store: store, group: group.group.id, member: place, redistributions: redistributions,
                                  placing: try LedgerStore.Placing.load(store))
        if shared {
            var data: [String: Any] = [
                "groupId": group.group.id, "member": place,
                "memberName": RoleRules.wireName(member.name), "groupName": RoleRules.wireName(group.group.name),
                "byName": RoleRules.wireName(byName), "records": payload(records),
            ]
            if let you { data["you"] = you }
            _ = try await CloudFunctions.call("removeMember", data)
        }
        try applyLocally(records, store: store, uploaded: shared)
        SyncStatus.shared.changed()
    }
}

// MARK: - Settle first (pure state of the screen)

/// How far "Record and remove" got: how many of the payments are in the
/// vault, and whether the person is out. A payment once appended is never
/// appended again: a later tap carries on from where the last one stopped,
/// so a failed removal is retried alone.
struct SettleFirstProgress: Equatable {
    let total: Int
    private(set) var appended = 0
    private(set) var removed = false
    /// D-210 final B6: whether the payments reached the cloud before the
    /// removal (nil until the sync ran). The removal goes ahead either way;
    /// false says they were still waiting on this phone when it did.
    private(set) var uploaded: Bool?

    init(total: Int) { self.total = total }

    /// Every payment is recorded; only the removal is left.
    var recorded: Bool { appended >= total }

    /// At least one payment is in the vault.
    var started: Bool { appended > 0 }

    /// One tap: the payments not yet appended (in order), then the sync,
    /// then the removal. Throws where it stopped; `progress` says how far.
    @MainActor
    static func run(
        _ progress: inout SettleFirstProgress,
        append: (Int) throws -> Void, sync: () async -> Bool, remove: () async throws -> Void
    ) async throws {
        guard !progress.removed else { return }
        while progress.appended < progress.total {
            try append(progress.appended)
            progress.appended += 1
        }
        progress.uploaded = await sync()
        try await remove()
        progress.removed = true
    }
}

// MARK: - Redistribute (pure state of the screen)

/// The Redistribute screen's state for one currency: who is ticked, equal
/// shares (core's) or typed ones, and whether they add up. Every amount is
/// core's: the equal split is `ffiRedistributeEqual`, a typed amount is read
/// by `ffiMoneyParseMinor` and given the balance's sign, and "adds up" is
/// `ffiRedistributionTally`.
struct RedistributeModel: Equatable {
    enum Status: Equatable {
        case splitEqually, addsUp
        /// Minor units still to assign / too much (magnitudes).
        case left(Int64), tooMuch(Int64)
        case typeAmounts, tickOne
    }

    /// D-210 final B6: a size that never traps (`abs(Int64.min)` does).
    static func magnitude(_ value: Int64) -> Int64 {
        Int64(clamping: value.magnitude)
    }

    let from: String
    let currency: String
    /// The person's balance: negative owes, positive is owed.
    let balance: Int64
    /// The people who can take it, in the order the screen lists them.
    let candidates: [String]
    private(set) var ticked: Set<String>
    private(set) var custom = false
    /// Typed amounts by person (digits as typed), used while `custom`.
    var typed: [String: String] = [:]

    init(from: String, currency: String, balance: Int64, candidates: [String], ticked: Set<String>? = nil) {
        self.from = from
        self.currency = currency
        self.balance = balance
        self.candidates = candidates
        self.ticked = ticked ?? Set(candidates)
    }

    mutating func toggle(_ id: String) {
        guard candidates.contains(id) else { return }
        if ticked.contains(id) { ticked.remove(id) } else { ticked.insert(id) }
    }

    /// "Edit amounts": the fields start at the equal split.
    mutating func startEditing() {
        let equal = equalShares()
        typed = [:]
        for share in equal {
            typed[share.id] = (try? ffiMoneyInputDigits(minorUnits: Self.magnitude(share.amountMinor), currencyCode: currency)) ?? ""
        }
        custom = true
    }

    /// "Split equally": the typed text is dropped.
    mutating func stopEditing() {
        custom = false
        typed = [:]
    }

    private var tickedInOrder: [String] { candidates.filter(ticked.contains) }

    private func equalShares() -> [IdAmount] {
        guard balance != 0, !ticked.isEmpty else { return [] }
        return (try? ffiRedistributeEqual(balanceMinor: balance, members: tickedInOrder)) ?? []
    }

    /// Typed amounts of the ticked people, signed like the balance; `complete`
    /// is false when a ticked person has nothing usable (blank, not an
    /// amount, or zero).
    private func typedShares() -> (shares: [IdAmount], complete: Bool) {
        var out: [IdAmount] = []
        var complete = true
        for id in tickedInOrder {
            guard let minor = try? ffiMoneyParseMinor(digits: (typed[id] ?? "").trimmingCharacters(in: .whitespaces), currencyCode: currency),
                  minor > 0 else { complete = false; continue }
            out.append(IdAmount(id: id, amountMinor: balance < 0 ? -minor : minor))
        }
        return (out.sorted { $0.id < $1.id }, complete)
    }

    /// The shares this screen would record (equal ones are what core gives;
    /// typed ones as parsed, even if short or over: see `status`).
    func shares() -> [IdAmount] {
        custom ? typedShares().shares : equalShares()
    }

    private var tally: RedistributionTally {
        ffiRedistributionTally(balanceMinor: balance, from: from, members: candidates, shares: shares())
    }

    var status: Status {
        if ticked.isEmpty { return .tickOne }
        if !custom { return .splitEqually }
        guard typedShares().complete else { return .typeAmounts }
        let t = tally
        if t.ok { return .addsUp }
        let left = t.leftMinor
        if left == 0 { return .typeAmounts }
        return (left < 0) == (balance < 0) ? .left(Self.magnitude(left)) : .tooMuch(Self.magnitude(left))
    }

    /// Whether Remove is on: shares exist, and core says they add up.
    var ok: Bool {
        switch status {
        case .splitEqually: return tally.ok
        case .addsUp: return true
        default: return false
        }
    }

    /// What is assigned so far, as a magnitude.
    var assigned: Int64 { Self.magnitude(tally.assignedMinor) }

    /// The record this screen makes, when `ok`.
    func redistribution(group: String) -> RedistributionInput? {
        ok ? RedistributionInput(group: group, from: from, currencyCode: currency, shares: shares()) : nil
    }
}

// MARK: - Who is who on the People screen (pure)

/// A row of the People screen with the role and the actions it offers.
struct PersonRow: Equatable, Identifiable {
    let member: MemberWire
    /// The account's participant id when the person joined on a phone.
    let you: String?
    let role: RoleRules.Role
    let isSelf: Bool
    let offer: RoleRules.Offer
    /// The person as the cloud knows them: the group's own id for the place
    /// they hold (see `PeopleRoles.placeFor`). Equal to `member.id` for
    /// everyone but a viewer who joined through an invite. Every callable
    /// about this row sends it as `member`.
    let place: String
    var id: String { member.id }
    /// D-227 (Design 6): the `you` an ask about this row sends. Nil for the viewer's own row: the cloud knows the
    /// caller, and the phone's "you" may not be the id the group knows the account by.
    var askYou: String? { isSelf ? nil : you }
}

enum PeopleRoles {
    /// Each member with their role and what the viewer may do. `claims` maps a
    /// member to the account that joined as them; `roles` is the cloud's list,
    /// nil while it is not known (everyone then reads as a plain member: the
    /// Functions decide in the end, whatever this phone offers).
    /// The id the cloud knows a row's person by. On the phone of someone who
    /// joined through an invite, the projection aliases the place they claimed
    /// to their account's "you" id, so their own row is called by the you id;
    /// the Functions know the place (the group's member id from the invite).
    /// A row that is the viewer's (`rowId == selfId`) is called by the place
    /// the account claimed (the claim whose `you` is one of `yous`); the
    /// creator has no claim (the place IS the you id), and every other row
    /// already holds its place.
    static func placeFor(rowId: String, selfId: String, yous: [String], claims: [String: String]) -> String {
        guard rowId == selfId else { return rowId }
        return claims.filter { yous.contains($0.value) }.keys.sorted().first ?? rowId
    }

    /// D-227 (Design 6): the viewer's own place. The cloud's `places.you` when it lists any: the one `placeFor`
    /// picks if it is listed, else the first in order. Otherwise `placeFor` with every id of the account.
    static func selfPlace(yourPlaces: [String]?, selfId: String, yous: [String], claims: [String: String]) -> String {
        let picked = placeFor(rowId: selfId, selfId: selfId, yous: yous, claims: claims)
        guard let listed = yourPlaces, let first = listed.sorted().first else { return picked }
        return listed.contains(picked) ? picked : first
    }

    static func rows(
        members: [MemberWire], selfId: String, viewerYou: String?, claims: [String: String], roles: RoleRules.Roles?,
        yourPlaces: [String]? = nil, otherYouIds: [String] = [], readOnly: Bool = false
    ) -> [PersonRow] {
        let known = roles ?? RoleRules.Roles(owner: nil, admins: [])
        func you(_ id: String) -> String? { id == selfId ? (viewerYou ?? claims[id]) : claims[id] }
        // D-227 (Design 6): the viewer's own place, from the cloud when it said, else from every id of the account.
        let mine = selfPlace(yourPlaces: yourPlaces, selfId: selfId, yous: (viewerYou.map { [$0] } ?? []) + otherYouIds,
                             claims: claims)
        func place(_ id: String) -> String { id == selfId ? mine : id }
        let viewer = RoleRules.role(of: viewerYou, place: mine, in: known)
        return members.map { m in
            let isSelf = m.id == selfId
            // A copy of a group (removed from, or deleted): the roles shown
            // were the cloud's of a moment ago and nothing can be done here.
            if readOnly {
                let none = RoleRules.Offer(remove: false, ask: false, askSelf: false, makeAdmin: false, removeAdmin: false, makeOwner: false)
                return PersonRow(member: m, you: you(m.id), role: .member, isSelf: isSelf, offer: none, place: place(m.id))
            }
            let role = RoleRules.role(of: you(m.id), place: place(m.id), in: known)
            var offer = RoleRules.offer(viewer: viewer, target: role, isSelf: isSelf)
            // D-227 fix wave (M-f): a role call names the person's account (`you`). A row in a role list by place with
            // no claim on this phone has none to name (the server takes the place itself only from its creator), so it
            // offers no role action rather than a button that does nothing.
            if you(m.id) == nil {
                offer.makeAdmin = false
                offer.removeAdmin = false
                offer.makeOwner = false
            }
            return PersonRow(member: m, you: you(m.id), role: role, isSelf: isSelf, offer: offer, place: place(m.id))
        }
    }

    static func viewerRole(_ rows: [PersonRow]) -> RoleRules.Role {
        rows.first { $0.isSelf }?.role ?? .member
    }
}

// MARK: - What this phone knows about a group's people

/// What the People screen and a group's page need beyond the named group: is
/// the group in the cloud, which account is "you", who joined as whom, and is
/// it a read-only copy. Read strictly: a vault that cannot answer is an error
/// (the screen says so), never "no".
struct GroupPeople: Equatable {
    var shared = false
    var viewerYou: String?
    var claims: [String: String] = [:]
    /// D-227: the account's other ids (the places it claimed, the ids its other phones wrote).
    var otherYouIds: [String] = []
    var readOnly: LedgerStore.ReadOnly?

    static func load(store: LedgerStore, groupID: String) throws -> GroupPeople {
        let projection = try LedgerStore.Sync.projectGroups(store)
        let claims = projection.groups.first { $0.group.id == groupID }?.claims ?? []
        return GroupPeople(
            shared: try CloudRecords.groupsUp(store).contains(groupID),
            viewerYou: projection.you?.id,
            claims: Dictionary(claims.map { ($0.memberId, $0.youId) }, uniquingKeysWith: { a, _ in a }),
            otherYouIds: projection.otherYouIds,
            readOnly: try store.readOnlyChecked()[groupID]
        )
    }
}

/// Opens Settle up from a page pushed over another tab (HomeShell sets it).
private struct OpenSettleKey: EnvironmentKey { static let defaultValue: (() -> Void)? = nil }

extension EnvironmentValues {
    var openSettleTab: (() -> Void)? {
        get { self[OpenSettleKey.self] }
        set { self[OpenSettleKey.self] = newValue }
    }
}

// MARK: - Open requests as cards (pure)

struct RequestCardItem: Equatable, Identifiable {
    let item: Notices.Item
    let kind: RoleRules.RequestCard
    var id: String { item.id }
}

enum RequestCards {
    /// The open removal requests of one group, each shaped for the viewer by
    /// `RoleRules.requestCard`, from the People rows (who is here, and each
    /// one's role). A member receives a request's notice only as the asker
    /// or as the person named, so a member who is not the person asked. A
    /// request about someone no longer here is skipped.
    static func cards(items: [Notices.Item], rows: [PersonRow]) -> [RequestCardItem] {
        let viewer = PeopleRoles.viewerRole(rows)
        return items.compactMap { item in
            guard item.kind == .request, let member = item.memberId, item.memberName != nil,
                  let row = rows.first(where: { $0.place == member }),
                  let kind = RoleRules.requestCard(
                      viewer: viewer, person: row.role, viewerIsAsker: !row.isSelf, viewerIsPerson: row.isSelf)
            else { return nil }
            return RequestCardItem(item: item, kind: kind)
        }
    }

    /// A card's title, as the Copy table words it: a request the person made
    /// for themself reads "You asked to be removed" on their own card and
    /// "{name} asked to be removed" on everyone else's.
    static func title(_ card: RequestCardItem) -> String {
        let name = card.item.memberName ?? ""
        let own = card.item.askedItself
        switch card.kind {
        case .decide, .ownerDecides:
            return own ? RolesCopy.cardAskedToBeRemoved(name: name) : RolesCopy.cardAsked(asker: card.item.byName, name: name)
        case .asker:
            return RolesCopy.cardAsker(name: name)
        case .person:
            return own ? RolesCopy.cardYouAskedToBeRemoved : RolesCopy.cardPerson(asker: card.item.byName)
        }
    }

    /// Whether a request's review may go on to the removal: what the
    /// person's sheet would offer this viewer (`RoleRules.offer(...).remove`).
    static func mayRemove(rows: [PersonRow], member: String) -> Bool {
        rows.first { $0.place == member }?.offer.remove ?? false
    }

    /// Under each open request, what the person named owes or is owed in the
    /// group ("Sam owes ₹1,000.00 here."), by request id; a settled person,
    /// or a balance that cannot be read, has none.
    static func balanceLines(store: LedgerStore, group: String, items: [Notices.Item]) -> [String: String] {
        var out: [String: String] = [:]
        for item in items {
            guard item.kind == .request, let member = item.memberId, let name = item.memberName,
                  let lines = try? Removal.lines(store: store, group: group, member: member),
                  let line = RolesCopy.cardBalance(name, plan: Removal.plan(lines: lines, member: member)) else { continue }
            out[item.id] = line
        }
        return out
    }

    /// "Request sent": an open request naming `member` is already on this
    /// account's notices (one open request per person; asking again would be
    /// refused as ALREADY_EXISTS).
    static func asked(items: [Notices.Item], member: String) -> Bool {
        items.contains { $0.kind == .request && $0.memberId == member }
    }
}

// MARK: - Pieces the screens share

/// The Owner / Admin pill (gold on its soft fill for the owner, the accent on
/// its soft fill for an admin: existing tokens, measured by AppearanceTests).
/// D-220 (L19): drawn by the theme's pill (`.themePill`), outlined in Vardr Labs.
struct RolePill: View {
    let role: RoleRules.Role

    var body: some View {
        switch role {
        case .owner:
            pill(RolesCopy.ownerPill, .owner)
        case .admin:
            pill(RolesCopy.adminPill, .accent)
        default:
            EmptyView()
        }
    }

    private func pill(_ text: String, _ tone: PillTone) -> some View {
        Text(verbatim: text)
            .microLabelFont()
            .padding(.horizontal, 10).padding(.vertical, 4)
            .themePill(tone)
    }
}

/// A person's initial on their tone, as Settle up draws it.
struct PersonAvatar: View {
    let name: String
    var size: CGFloat = 40
    /// D-211: their preview in this group; `.none` is the initial on a tone.
    /// Tapping it does nothing.
    var preview: Avatar = .none
    /// D-223 (K09, Q3 a): nil keeps the person's tone; `.soft` is Lately's (`accentSoft` with the initial in `accent`).
    var fill: AvatarFill? = nil

    /// The ground and the initial's colour.
    private var colours: (ground: Color, ink: Color) {
        switch fill {
        case .soft?: return (Tokens.accentSoft, Tokens.accent)
        case .accent?: return (Tokens.accent, Tokens.onAccent)
        case nil: let tone = GroupTone.person(name); return (tone.fill, tone.letter)
        }
    }

    var body: some View {
        if preview != .none {
            AvatarView(avatar: preview, name: name, size: size)
        } else {
            let colours = colours
            let tileShape = TileShape.of(AppearanceState.shared.theme, person: true)
            let shape = TileClip.shape(tileShape, size: size)
            // D-220 (L23): Norse's diamond is drawn at size x sqrt 2 on a `size` footprint (PictureTile's rule).
            let side = TileClip.drawnSide(tileShape, size: size)
            Text(verbatim: GroupTone.initial(name))
                .font(TileClip.initialFont(tileShape, size: size))
                .foregroundStyle(colours.ink)
                .frame(width: side, height: side)
                .background(colours.ground, in: shape)
                .overlay { TileClip.edge(tileShape) }
                .frame(width: size, height: size)
                .accessibilityHidden(true)
        }
    }
}

/// One open removal request, as the Copy table words it.
struct RequestCardView: View {
    let card: RequestCardItem
    /// The person's own card offers Settle up while they have a balance here.
    var canSettleUp = false
    /// "Sam owes ₹1,000.00 here." under the title of a card an owner or admin decides.
    var balance: String?
    var busy = false
    var onDecline: () -> Void
    var onReview: () -> Void
    var onSettle: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            switch card.kind {
            case .decide:
                Text(verbatim: RequestCards.title(card)).font(TypeRole.label(16)).foregroundStyle(Tokens.text)
                if let balance { Text(verbatim: balance).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim) }
                HStack(spacing: 8) {
                    Button { onDecline() } label: { Text(verbatim: RolesCopy.decline) }
                        .buttonStyle(SecondaryButtonStyle())
                    Button { onReview() } label: { Text(verbatim: RolesCopy.review) }
                        .buttonStyle(PrimaryButtonStyle())
                }
                .disabled(busy)
            case .ownerDecides:
                Text(verbatim: RequestCards.title(card)).font(TypeRole.label(16)).foregroundStyle(Tokens.text)
                if let balance { Text(verbatim: balance).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim) }
                Text(verbatim: RolesCopy.cardOwnerWill).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
            case .asker:
                Text(verbatim: RequestCards.title(card)).font(TypeRole.label(16)).foregroundStyle(Tokens.text)
                Text(verbatim: RolesCopy.cardWait).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
            case .person:
                Text(verbatim: RequestCards.title(card)).font(TypeRole.label(16)).foregroundStyle(Tokens.text)
                Text(verbatim: RolesCopy.cardWait).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                if canSettleUp, let onSettle {
                    Button { onSettle() } label: { Text(verbatim: RolesCopy.settleUp) }
                        .buttonStyle(PrimaryButtonStyle())
                }
            }
        }
        .accessibilityElement(children: .contain)
    }
}

/// The tint behind a request card: the amber tone, text stays on the surface colour.
extension View {
    func requestCardSurface() -> some View {
        padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(GroupTone.amber.fill, in: ThemeArt.panelShape(AppearanceState.shared.theme, radius: 18, continuous: true))
    }
}

/// What a decision about a request needs besides the screen's own state: the
/// calls and their words, shared by the People screen and a group's page.
@MainActor
enum RoleCalls {
    /// Declines a request (`declineRemoval`); the card goes at once, and the
    /// Function deletes the notice everywhere.
    static func decline(_ item: Notices.Item, group: NamedGroupWire, byName: String) async throws {
        guard let member = item.memberId else { return }
        _ = try await CloudFunctions.call("declineRemoval", [
            "groupId": group.group.id, "member": member,
            "byName": RoleRules.wireName(byName), "groupName": RoleRules.wireName(group.group.name),
        ])
        Notices.shared.dismiss(item)
    }

    /// Asks for `member`'s removal (`requestRemoval`); true also when they
    /// were already asked about (ALREADY_EXISTS reads as "Request sent").
    static func ask(group: NamedGroupWire, member: MemberWire, place: String? = nil, you: String?, askerName: String) async throws {
        var data: [String: Any] = [
            "groupId": group.group.id, "member": place ?? member.id,
            "memberName": RoleRules.wireName(member.name), "groupName": RoleRules.wireName(group.group.name),
            "askerName": RoleRules.wireName(askerName),
        ]
        if let you { data["you"] = you }
        do {
            _ = try await CloudFunctions.call("requestRemoval", data)
        } catch where RolesCopy.alreadyAsked(error) {
            return
        }
    }
}

// MARK: - A person's sheet (shared groups)

struct RemovalTarget: Identifiable, Hashable {
    let member: MemberWire
    let you: String?
    /// The person as the cloud knows them (`PersonRow.place`): what the
    /// removal call names. Nil reads as `member.id`.
    var place: String? = nil
    /// What the person's sheet would offer this viewer (`offer.remove`): a
    /// request's review goes on to the removal only when it is true.
    let mayRemove: Bool
    var request: Notices.Item?
    var id: String { member.id + "/" + (request?.id ?? "") }

    // By identity: a request's notice changes under it (a Timestamp is not Hashable).
    static func == (a: RemovalTarget, b: RemovalTarget) -> Bool { a.id == b.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

/// What a person's sheet may change about their role, each behind a confirm.
private enum RoleAction {
    case makeAdmin, removeAdmin, makeOwner
}

struct PersonSheet: View {
    let store: LedgerStore
    let named: NamedGroupWire
    let row: PersonRow
    let viewerName: String
    /// D-211: their preview here, and whether Rename is offered (absent for a
    /// person with a profile: their name is theirs).
    var preview: Avatar = .none
    var canRename = true
    /// Closes the sheet and starts renaming this person on the People screen.
    var onRename: () -> Void
    /// Closes the sheet after something changed (the screen reloads).
    var onDone: () -> Void

    @State private var plan: [String: Int64] = [:]
    @State private var confirm: RoleAction?
    @State private var removal: RemovalTarget?
    @State private var error: String?
    @State private var asked = false
    @State private var busy = false
    @ObservedObject private var notices = Notices.shared

    private var name: String { row.member.name }

    /// "Request sent": just asked here, or an open request naming them is on
    /// this account's notices (so a reopened sheet never offers Ask again).
    private var requested: Bool {
        asked || RequestCards.asked(items: notices.requests(in: named.group.id), member: row.place)
    }
    private var group: String { named.group.name }

    var body: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    PersonAvatar(name: name, size: 48, preview: preview)
                    Text(verbatim: name).font(TypeRole.display(20, weight: 700)).foregroundStyle(Tokens.text)
                    RolePill(role: row.role)
                    Spacer(minLength: 0)
                }
            }
            if row.offer.ask {
                Section {
                    Text(verbatim: RolesCopy.askNote(name: name, joined: row.you != nil))
                        .font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
                }
            }
            if row.offer.remove || row.offer.ask || row.offer.askSelf || canRename {
                Section {
                    if row.offer.remove {
                        Button { removal = RemovalTarget(member: row.member, you: row.you, place: row.place, mayRemove: true) } label: {
                            actionLabel(RolesCopy.removeFrom(group: group), sub: balancePhrase, destructive: true)
                        }
                    }
                    if row.offer.ask {
                        askRow(RolesCopy.askToRemove(name), sub: balancePhrase)
                    }
                    if row.offer.askSelf {
                        askRow(RolesCopy.askSelf, sub: balancePhrase)
                    }
                    if canRename {
                        Button { onRename() } label: { actionLabel(RolesCopy.rename, sub: nil, destructive: false) }
                    }
                }
            }
            if row.offer.makeAdmin || row.offer.removeAdmin || row.offer.makeOwner {
                Section {
                    if row.offer.makeAdmin {
                        Button { confirm = .makeAdmin } label: {
                            actionLabel(RolesCopy.makeAdminRow, sub: RolesCopy.makeAdminSub(name), destructive: false)
                        }
                    }
                    if row.offer.removeAdmin {
                        Button { confirm = .removeAdmin } label: {
                            actionLabel(RolesCopy.removeAdminRow, sub: RolesCopy.removeAdminSub(name), destructive: false)
                        }
                    }
                    if row.offer.makeOwner {
                        Button { confirm = .makeOwner } label: {
                            actionLabel(RolesCopy.makeOwnerRow, sub: RolesCopy.makeOwnerSub, destructive: false)
                        }
                    }
                } header: {
                    Text(verbatim: RolesCopy.roleSection)
                        .sectionLabel().foregroundStyle(Tokens.textDim)
                }
            }
            if let error {
                Section { Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed) }
            }
        }
        .auroraFormBackground()
        .navigationTitle(Text(verbatim: name))
        .navigationBarTitleDisplayMode(.inline)
        .task { loadPlan() }
        .disabled(busy)
        .navigationDestination(item: $removal) { target in
            RemovalFlowView(store: store, named: named, target: target, shared: true, onFinished: onDone)
        }
        .alert(
            confirmTitle, isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }),
            presenting: confirm
        ) { action in
            Button(confirmButton(action)) { run(action) }
            Button(RolesCopy.cancel, role: .cancel) {}
        } message: { action in
            Text(verbatim: confirmBody(action))
        }
        // D-224 (Design 3): only the owner's transfer warns (making or removing an admin can be undone).
        .hapticWarning(when: confirm == .makeOwner)
    }

    private func actionLabel(_ title: String, sub: String?, destructive: Bool) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title).font(TypeRole.body()).foregroundStyle(destructive ? Tokens.owed : Tokens.text)
                if let sub {
                    Text(verbatim: sub).font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
                }
            }
            Spacer(minLength: 8)
            RowChevron()
        }
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private func askRow(_ title: String, sub: String?) -> some View {
        if requested {
            HStack(spacing: 8) {
                Image(systemName: "checkmark").foregroundStyle(Tokens.owing).accessibilityHidden(true)
                Text(verbatim: RolesCopy.asked).font(TypeRole.body()).foregroundStyle(Tokens.owing)
            }
        } else {
            Button { ask() } label: { actionLabel(title, sub: sub, destructive: true) }
        }
    }

    private var balancePhrase: String? { RolesCopy.balanceHere(name, plan: plan) }

    private func loadPlan() {
        guard let lines = try? Removal.lines(store: store, group: named.group.id, member: row.member.id) else { return }
        plan = Removal.plan(lines: lines, member: row.member.id)
    }

    private func ask() {
        busy = true
        Task {
            defer { busy = false }
            do {
                try await RoleCalls.ask(group: named, member: row.member, place: row.place, you: row.askYou, askerName: viewerName)
                asked = true
                error = nil
            } catch {
                self.error = RolesCopy.failure(error, person: true)
            }
        }
    }

    private var confirmTitle: String {
        switch confirm {
        case .makeAdmin: return RolesCopy.makeAdminTitle(name)
        case .removeAdmin: return RolesCopy.removeAdminTitle(name)
        case .makeOwner: return RolesCopy.makeOwnerTitle(name)
        case nil: return ""
        }
    }

    private func confirmBody(_ action: RoleAction) -> String {
        switch action {
        case .makeAdmin: return RolesCopy.makeAdminBody(name)
        case .removeAdmin: return RolesCopy.removeAdminBody(name)
        case .makeOwner: return RolesCopy.makeOwnerBody(name, group: group)
        }
    }

    private func confirmButton(_ action: RoleAction) -> String {
        switch action {
        case .makeAdmin: return RolesCopy.makeAdminConfirm(name)
        case .removeAdmin: return RolesCopy.removeAdminConfirm
        case .makeOwner: return RolesCopy.makeOwnerConfirm(name)
        }
    }

    private func run(_ action: RoleAction) {
        guard let you = row.you else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                switch action {
                case .makeAdmin:
                    try await GroupRoles.shared.setAdmin(groupID: named.group.id, member: row.place, you: you, on: true, byName: viewerName, groupName: group)
                case .removeAdmin:
                    try await GroupRoles.shared.setAdmin(groupID: named.group.id, member: row.place, you: you, on: false, byName: viewerName, groupName: group)
                case .makeOwner:
                    try await GroupRoles.shared.makeOwner(groupID: named.group.id, member: row.place, you: you, byName: viewerName, groupName: group)
                }
                onDone()
            } catch {
                self.error = RolesCopy.failure(error, person: true)
            }
        }
    }
}

// MARK: - The removal screens

/// What every removal screen holds: the group, who is being removed, whether
/// the group is in the cloud, and what to do when it is done.
struct RemovalContext {
    let store: LedgerStore
    let named: NamedGroupWire
    let target: RemovalTarget
    let shared: Bool
    let onFinished: () -> Void

    var member: MemberWire { target.member }
    var viewerName: String { named.members.first { $0.id == named.selfId }?.name ?? "" }

    /// "Sam", or "you" for the viewer, in a payment line.
    func who(_ id: String, first: Bool = false) -> String {
        if id == named.selfId { return first ? "You" : "you" }
        return named.members.first { $0.id == id }?.name ?? LedgerStore.label(for: id, in: named)
    }

    /// The removal itself, then the screens' ledger and the caller's reload.
    @MainActor
    func perform(redistributions: [RedistributionInput]) async throws {
        try await Removal.remove(
            store: store, group: named, member: member, place: target.place, you: target.you, byName: viewerName,
            redistributions: redistributions, shared: shared)
        // D-224 (Design 7): the removal is done (a request's decline goes through `onFinished` alone: it removes nobody).
        DoneCenter.shared.post(DoneMoments.line(DoneCopy.personRemoved(member.name)))
        onFinished()
    }

    /// A failure as words: the Function's refusals have theirs; anything else
    /// is this phone's own (the vault), said as such.
    static func message(_ error: Error) -> String {
        if error is CloudFunctions.Failure { return RolesCopy.failure(error, person: true) }
        return String(localized: "Could not save the removal. \(String(describing: error))")
    }
}

/// The removal, from its first screen: a request's review, else the plain
/// confirm or the balance's choice. Pushed inside a person's sheet, or as the
/// root of `RemovalSheet`.
struct RemovalFlowView: View {
    let store: LedgerStore
    let named: NamedGroupWire
    let target: RemovalTarget
    let shared: Bool
    var onFinished: () -> Void

    private var context: RemovalContext {
        RemovalContext(store: store, named: named, target: target, shared: shared, onFinished: onFinished)
    }

    var body: some View {
        if let request = target.request {
            ReviewRemovalView(context: context, request: request)
        } else {
            RemovalStartView(context: context)
        }
    }
}

/// A card's Review: its own sheet with a stack, so the flow can push screens.
struct RemovalSheet: View {
    let store: LedgerStore
    let named: NamedGroupWire
    let target: RemovalTarget
    let shared: Bool
    var onFinished: () -> Void
    var onClose: () -> Void

    var body: some View {
        NavigationStack {
            RemovalFlowView(store: store, named: named, target: target, shared: shared, onFinished: onFinished)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(RolesCopy.cancel) { onClose() }.barTint() }
                }
        }
    }
}

/// A request's review: who asked, what happens, then on to the removal or a decline.
private struct ReviewRemovalView: View {
    let context: RemovalContext
    let request: Notices.Item

    @State private var go = false
    @State private var busy = false
    @State private var error: String?
    @State private var plan: [String: Int64] = [:]
    @State private var noHistory = false

    var body: some View {
        let name = context.member.name
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(verbatim: RolesCopy.reviewTitle(name, group: context.named.group.name))
                    .font(TypeRole.display(22, weight: 700)).foregroundStyle(Tokens.text)
                Text(verbatim: RolesCopy.reviewBody(
                    asker: request.byName, name: name, joined: context.target.you != nil, noHistory: noHistory))
                    .font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                // What they owe, and that the next screen is where it is settled
                // (only for someone who may carry on to the removal).
                if context.target.mayRemove, let banner = RolesCopy.reviewBanner(name, plan: plan) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: banner.title).font(TypeRole.label(15)).foregroundStyle(GroupTone.amber.letter)
                        Text(verbatim: banner.sub).font(TypeRole.body(14)).foregroundStyle(Tokens.text)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(GroupTone.amber.fill, in: ThemeArt.panelShape(AppearanceState.shared.theme, radius: 14, continuous: true))
                    .accessibilityElement(children: .combine)
                }
                if let error { Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed) }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        .navigationTitle(Text(verbatim: RolesCopy.balanceTitle(name)))
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $go) { RemovalStartView(context: context) }
        .task { load() }
        .formActionBar {
            VStack(spacing: 8) {
                // Absent unless this viewer may remove them (an admin never
                // removes an admin: the owner decides that request).
                if context.target.mayRemove {
                    Button { go = true } label: { Text(verbatim: RolesCopy.continueToRemove) }
                        .buttonStyle(PrimaryButtonStyle(tint: Tokens.owed))
                }
                Button { decline() } label: { Text(verbatim: RolesCopy.declineRequest) }
                    .buttonStyle(SecondaryButtonStyle())
            }
            .disabled(busy)
        }
    }

    /// The balance for the banner, and whether D-186's "in nothing" wording is
    /// true. A read that fails leaves the banner out and the safe wording.
    private func load() {
        let group = context.named.group.id, member = context.member.id
        if let lines = try? Removal.lines(store: context.store, group: group, member: member) {
            plan = Removal.plan(lines: lines, member: member)
        }
        noHistory = (try? context.store.removalRefusal(groupID: group, memberID: member) == nil) ?? false
    }

    private func decline() {
        busy = true
        Task {
            defer { busy = false }
            do {
                try await RoleCalls.decline(request, group: context.named, byName: context.viewerName)
                context.onFinished()
            } catch {
                self.error = RolesCopy.failure(error, person: true)
            }
        }
    }
}

/// Reads the person's balance, then shows the plain confirm (nothing to
/// decide) or the choice (settle first, or redistribute).
private struct RemovalStartView: View {
    let context: RemovalContext

    @State private var loaded = false
    @State private var lines: [BalanceLineOut] = []
    @State private var plan: [String: Int64] = [:]
    @State private var nets: [String: [String: Int64]] = [:]
    @State private var unjoinedNoHistory = false
    @State private var error: String?
    /// Settle first's progress, kept here so going back and on again never
    /// records the same payments twice.
    @State private var settle: SettleFirstProgress?

    var body: some View {
        ZStack {
            if !loaded {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error {
                Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed).padding(16)
            } else if plan.isEmpty {
                PlainRemoveView(context: context, unjoinedNoHistory: unjoinedNoHistory)
            } else {
                BalanceChoiceView(context: context, lines: lines, plan: plan, nets: nets, settle: $settle)
            }
        }
        .auroraBackground()
        .task { load() }
    }

    private func load() {
        do {
            let group = context.named.group.id, member = context.member.id
            lines = try Removal.lines(store: context.store, group: group, member: member)
            plan = Removal.plan(lines: lines, member: member)
            nets = try Removal.nets(store: context.store, group: group)
            // D-186's wording is true only for someone who never joined and is
            // in nothing; a vault that cannot say is an error, not "in nothing".
            unjoinedNoHistory = try context.target.you == nil
                && context.store.removalRefusal(groupID: group, memberID: member) == nil
            error = nil
        } catch {
            self.error = String(localized: "Could not read the balance. \(String(describing: error))")
        }
        loaded = true
    }
}

/// Nothing to decide: confirm, or cancel.
private struct PlainRemoveView: View {
    let context: RemovalContext
    let unjoinedNoHistory: Bool

    @Environment(\.dismiss) private var dismiss
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        let name = context.member.name
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(verbatim: RolesCopy.removeTitle(name, group: context.named.group.name))
                    .font(TypeRole.display(22, weight: 700)).foregroundStyle(Tokens.text)
                Text(verbatim: RolesCopy.removalBody(name: name, joined: context.target.you != nil, noHistory: unjoinedNoHistory))
                    .font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                if let error { Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed) }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(Text(verbatim: RolesCopy.balanceTitle(name)))
        .navigationBarTitleDisplayMode(.inline)
        // D-224 (Design 3): this screen is the removal's question: it warns as it appears.
        .hapticWarning(when: true)
        .formActionBar {
            VStack(spacing: 8) {
                Button { remove() } label: { Text(verbatim: RolesCopy.removeConfirm) }
                    .buttonStyle(PrimaryButtonStyle(tint: Tokens.owed))
                Button { dismiss() } label: { Text(verbatim: RolesCopy.cancel) }
                    .buttonStyle(SecondaryButtonStyle())
            }
            .disabled(busy)
        }
    }

    private func remove() {
        busy = true
        Task {
            defer { busy = false }
            do { try await context.perform(redistributions: []) } catch { self.error = RemovalContext.message(error) }
        }
    }
}

/// Settle first, or redistribute.
private struct BalanceChoiceView: View {
    let context: RemovalContext
    let lines: [BalanceLineOut]
    let plan: [String: Int64]
    let nets: [String: [String: Int64]]
    @Binding var settle: SettleFirstProgress?

    private enum Choice { case settle, redistribute }
    @State private var choice = Choice.redistribute
    @State private var go = false

    /// Any payment already recorded: the balance above has moved, so
    /// Redistribute is over and Settle first carries on (its tap appends
    /// what is missing, then retries the removal).
    private var started: Bool { settle?.started == true }
    private var picked: Choice { started ? .settle : choice }

    var body: some View {
        let name = context.member.name
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ForEach(plan.keys.sorted(), id: \.self) { code in
                    let net = plan[code] ?? 0
                    if let amount = RolesMoney.format(RedistributeModel.magnitude(net), code) {
                        Text(verbatim: net < 0 ? RolesCopy.owes(name, amount) : RolesCopy.isOwed(name, amount))
                            .font(TypeRole.display(24, weight: 700)).foregroundStyle(net < 0 ? Tokens.owed : Tokens.owing)
                            .accessibilityLabel(Text(verbatim: net < 0
                                ? RolesCopy.owes(name, RolesMoney.spoken(RedistributeModel.magnitude(net), code))
                                : RolesCopy.isOwed(name, RolesMoney.spoken(RedistributeModel.magnitude(net), code))))
                    }
                }
                Text(verbatim: RolesCopy.balanceLede(name)).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                option(.settle, RolesCopy.settleFirst, RolesCopy.settleFirstSub(name))
                if !started {
                    option(.redistribute, RolesCopy.redistribute, RolesCopy.redistributeSub(name))
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(Text(verbatim: RolesCopy.balanceTitle(name)))
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $go) {
            if picked == .settle {
                SettleFirstView(context: context, lines: lines, settle: $settle)
            } else {
                RedistributeView(context: context, plan: plan, nets: nets)
            }
        }
        .formActionBar {
            Button { go = true } label: { Text(verbatim: RolesCopy.next) }
                .buttonStyle(PrimaryButtonStyle())
        }
    }

    private func option(_ value: Choice, _ title: String, _ sub: String) -> some View {
        Button { choice = value } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: picked == value ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(picked == value ? Tokens.accent : Tokens.textDim)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: title).font(TypeRole.label(16)).foregroundStyle(Tokens.text)
                    Text(verbatim: sub).font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .glassCard(padding: 14)
        .overlay {
            // D-220 fix wave (I3): 2 pt inside the card's own shape (notched in Norse): a stroke twice the width, clipped.
            let ring = ThemeArt.cardShape(AppearanceState.shared.theme)
            ring.stroke(picked == value ? Tokens.accent : Color.clear, lineWidth: 4).clipShape(ring)
                .allowsHitTesting(false)
        }
        .accessibilityAddTraits(picked == value ? [.isSelected] : [])
    }
}

/// The payments that clear the balance, recorded, then the removal.
private struct SettleFirstView: View {
    let context: RemovalContext
    let lines: [BalanceLineOut]
    @Binding var settle: SettleFirstProgress?

    @Environment(\.dismiss) private var dismiss
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        let name = context.member.name
        let settlements = Removal.settleFirst(lines: lines, member: context.member.id, group: context.named.group.id)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(verbatim: RolesCopy.recordPaymentsTitle).font(TypeRole.display(22, weight: 700)).foregroundStyle(Tokens.text)
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(settlements.enumerated()), id: \.offset) { _, s in
                        if let amount = RolesMoney.format(s.amountMinor, s.currencyCode) {
                            Text(verbatim: RolesCopy.paid(context.who(s.from, first: true), context.who(s.to), amount))
                                .font(TypeRole.body()).foregroundStyle(Tokens.text)
                        }
                    }
                }
                .glassCard(padding: 14)
                if let error { Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed) }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        .navigationTitle(Text(verbatim: RolesCopy.balanceTitle(name)))
        .navigationBarTitleDisplayMode(.inline)
        // D-224 (Design 3): this screen is the removal's question: it warns as it appears.
        .hapticWarning(when: true)
        .formActionBar {
            VStack(spacing: 8) {
                Button { record(settlements) } label: { Text(verbatim: RolesCopy.recordAndRemove(name)) }
                    .buttonStyle(PrimaryButtonStyle(tint: Tokens.owed))
                Button { dismiss() } label: { Text(verbatim: RolesCopy.cancel) }
                    .buttonStyle(SecondaryButtonStyle())
            }
            .disabled(busy)
        }
    }

    /// Appends the payments not yet recorded (never one twice: a tap after
    /// they are in retries only the removal), syncs, then removes.
    private func record(_ settlements: [LedgerStore.SettlementDTO]) {
        // Never record a payment the screen could not have shown.
        guard Removal.canFormat(settlements) else { error = RolesCopy.cannotWritePayment; return }
        busy = true
        Task {
            defer { busy = false }
            var progress = settle ?? SettleFirstProgress(total: settlements.count)
            defer { settle = progress }
            do {
                try await SettleFirstProgress.run(
                    &progress,
                    append: { i in _ = try context.store.appendSettlement(settlements[i]) },
                    // The payments reach the cloud before the removal does.
                    sync: {
                        guard context.shared else { return true }
                        return await CloudSync.shared.syncNow(group: context.named.group.id)
                    },
                    remove: { try await context.perform(redistributions: []) })
            } catch {
                self.error = RemovalContext.message(error)
            }
        }
    }
}

/// Who takes the balance, per currency: ticks, equal shares or typed ones,
/// each person's old and new balance, and whether it adds up.
private struct RedistributeView: View {
    let context: RemovalContext
    let plan: [String: Int64]
    let nets: [String: [String: Int64]]

    @State private var models: [RedistributeModel]
    @State private var busy = false
    @State private var error: String?

    init(context: RemovalContext, plan: [String: Int64], nets: [String: [String: Int64]]) {
        self.context = context
        self.plan = plan
        self.nets = nets
        let others = context.named.members.map(\.id).filter { $0 != context.member.id }
        _models = State(initialValue: plan.keys.sorted().map { code in
            // The people who are in this currency's balances take it to start with.
            let involved = others.filter { (nets[code]?[$0] ?? 0) != 0 }
            return RedistributeModel(
                from: context.member.id, currency: code, balance: plan[code] ?? 0, candidates: others,
                ticked: Set(involved.isEmpty ? others : involved))
        })
    }

    private var allOK: Bool { !models.isEmpty && models.allSatisfy(\.ok) }

    var body: some View {
        let name = context.member.name
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(verbatim: RolesCopy.redistributeLede(name)).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
                ForEach(models.indices, id: \.self) { i in section(i) }
                if let error { Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed) }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        .navigationTitle(Text(verbatim: RolesCopy.redistributeTitle))
        .navigationBarTitleDisplayMode(.inline)
        // D-224 (Design 3): this screen is the removal's question: it warns as it appears.
        .hapticWarning(when: true)
        .formActionBar {
            VStack(spacing: 6) {
                Button { remove() } label: { Text(verbatim: RolesCopy.removeButton(name)) }
                    .buttonStyle(PrimaryButtonStyle(tint: Tokens.owed))
                    .disabled(!allOK || busy)
                Text(verbatim: RolesCopy.recordedFine(group: context.named.group.name))
                    .font(TypeRole.body(12)).foregroundStyle(Tokens.textDim)
            }
        }
    }

    private func section(_ i: Int) -> some View {
        let m = models[i]
        let code = m.currency
        let byId = Dictionary(m.shares().map { ($0.id, $0.amountMinor) }, uniquingKeysWith: { a, _ in a })
        return VStack(alignment: .leading, spacing: 10) {
            if models.count > 1 {
                Text(verbatim: code).sectionLabel().foregroundStyle(Tokens.textDim)
            }
            VStack(spacing: 0) {
                ForEach(Array(m.candidates.enumerated()), id: \.element) { n, id in
                    if n > 0 { Divider().overlay(Tokens.hairline) }
                    personRow(i, id: id, code: code, share: byId[id] ?? 0)
                }
            }
            .glassCard(padding: 12)
            tally(m)
            Button { toggleEditing(i) } label: {
                Text(verbatim: m.custom ? RolesCopy.splitEqually : RolesCopy.editAmounts)
                    .font(TypeRole.label(15)).foregroundStyle(Tokens.groundAccent).frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.plain)
        }
    }

    private func personRow(_ i: Int, id: String, code: String, share: Int64) -> some View {
        let m = models[i]
        let name = context.named.members.first { $0.id == id }?.name ?? ""
        let ticked = m.ticked.contains(id)
        let old = nets[code]?[id] ?? 0
        let new = old + (ticked ? share : 0)
        func word(_ net: Int64) -> String? { RolesMoney.format(RedistributeModel.magnitude(net), code).map { RolesCopy.rowBalance(net, $0) } }
        let sub = ticked && !m.custom ? RolesMoney.format(RedistributeModel.magnitude(share), code).map(RolesCopy.takes) : word(old).map(RolesCopy.rowNow)
        return HStack(spacing: 10) {
            Button { models[i].toggle(id) } label: {
                Image(systemName: ticked ? "checkmark.square.fill" : "square")
                    .font(.system(size: 22)).foregroundStyle(ticked ? Tokens.accent : Tokens.textDim)
                    .frame(width: 44, height: 44).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: name))
            .accessibilityAddTraits(ticked ? [.isSelected] : [])
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: name).font(TypeRole.label(15)).foregroundStyle(Tokens.text)
                if let sub { Text(verbatim: sub).font(TypeRole.body(12)).foregroundStyle(Tokens.textDim) }
            }
            Spacer(minLength: 8)
            if m.custom {
                TextField("", text: Binding(get: { models[i].typed[id] ?? "" }, set: { models[i].typed[id] = $0 }))
                    .keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                    .font(TypeRole.numeric(15, weight: 600)).foregroundStyle(Tokens.text)
                    .frame(width: 96).hairlineField()
                    .disabled(!ticked).opacity(ticked ? 1 : 0.35)
                    .accessibilityLabel(Text(verbatim: name))
            } else {
                let before = ticked && share != 0 ? word(old) : nil
                VStack(alignment: .trailing, spacing: 0) {
                    if let before {
                        Text(verbatim: before).font(TypeRole.body(11)).strikethrough().foregroundStyle(Tokens.textDim)
                    }
                    if let after = word(new) {
                        Text(verbatim: after).font(TypeRole.numeric(14, weight: 600))
                            .foregroundStyle(new < 0 ? Tokens.owed : (new > 0 ? Tokens.owing : Tokens.textDim))
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(verbatim: [before, word(new)].compactMap { $0 }.joined(separator: ", ")))
            }
        }
    }

    private func tally(_ m: RedistributeModel) -> some View {
        let code = m.currency
        let total = RedistributeModel.magnitude(m.balance)
        let (text, good): (String?, Bool) = {
            switch m.status {
            case .splitEqually: return (RolesCopy.splitEqually, true)
            case .addsUp: return (RolesCopy.addsUp, true)
            case .left(let n): return (RolesMoney.format(n, code).map(RolesCopy.left), false)
            case .tooMuch(let n): return (RolesMoney.format(n, code).map(RolesCopy.tooMuch), false)
            case .typeAmounts: return (RolesCopy.typeAmounts, false)
            case .tickOne: return (RolesCopy.tickOne, false)
            }
        }()
        let assigned = RolesMoney.format(m.assigned, code), whole = RolesMoney.format(total, code)
        return HStack {
            if let text { Text(verbatim: text).foregroundStyle(good ? Tokens.owing : Tokens.owed) }
            Spacer(minLength: 8)
            if let assigned, let whole {
                Text(verbatim: RolesCopy.ofTotal(assigned, whole)).foregroundStyle(Tokens.textDim)
            }
        }
        .font(TypeRole.label(13))
        .padding(.horizontal, 4)
        .accessibilityElement(children: .combine)
    }

    private func toggleEditing(_ i: Int) {
        if models[i].custom { models[i].stopEditing() } else { models[i].startEditing() }
    }

    private func remove() {
        let moved = models.compactMap { $0.redistribution(group: context.named.group.id) }
        guard moved.count == models.count else { return }
        busy = true
        Task {
            defer { busy = false }
            do { try await context.perform(redistributions: moved) } catch { self.error = RemovalContext.message(error) }
        }
    }
}
