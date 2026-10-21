import SwiftUI

/// D-179: a group's colour, from its id, the same on every phone and on both
/// shells (Android's `GroupTone` in GroupsTab.kt). The first byte of the id
/// (its first two hex digits) modulo six picks one of six tones; the tile is
/// a soft fill with the group's first letter on it, and every letter clears
/// 4.5:1 on its fill in every token set (.drafts/d179/tones.py; AppearanceTests
/// measures them again).
enum GroupTone: String, CaseIterable {
    case coral, blue, teal, amber, sky, pink

    static func of(_ groupID: String) -> GroupTone {
        let byte = Int(groupID.prefix(2), radix: 16) ?? 0
        return allCases[byte % allCases.count]
    }

    /// (fill, letter) per token set: Aurora keeps D-179's tones; Norse and
    /// Vardr Labs take the same six hues, quieter (.drafts/d212/palette.py; D-220 L29: blue replaced violet, .drafts/d220/palette.py).
    var column: (fill: Tokens.Column, letter: Tokens.Column) {
        switch self {
        case .coral: return (fill: ("#FFE4DA", "#3A2119", "#261F21", "#DDDADA", "#2C2829"), letter: ("#B53F18", "#FF9C78", "#E1AA96", "#875342", "#DAAD9D"))
        case .blue: return (fill: ("#E3EBFC", "#17223D", "#1E212C", "#D7DCE4", "#262B34"), letter: ("#2F5BBF", "#93B4FF", "#ABBDE7", "#4F6089", "#B1C0E1"))
        case .teal: return (fill: ("#DDF5F0", "#12302B", "#172125", "#D1DBDD", "#1E2A2C"), letter: ("#0B7A6B", "#5FD6C4", "#7ABBB1", "#2A5B55", "#80B5AD"))
        case .amber: return (fill: ("#FFF1D6", "#33270F", "#23201E", "#D8D9D7", "#2A2926"), letter: ("#8A5A00", "#F2C063", "#D2B683", "#644E26", "#CBB48A"))
        case .sky: return (fill: ("#DFF1FB", "#10293A", "#1B222A", "#D2DBE0", "#222B31"), letter: ("#0B6A96", "#7CC9F0", "#96C0D6", "#315C70", "#9CBFD0"))
        case .pink: return (fill: ("#FFE3F0", "#3A1A2A", "#261E27", "#DCD9DF", "#2D282F"), letter: ("#A8286A", "#FF8CC2", "#E5A6C4", "#854B69", "#DFACC4"))
        }
    }

    var fill: Color { Tokens.dynamic(column.fill) }
    var letter: Color { Tokens.dynamic(column.letter) }

    /// The group's first letter, upper-cased; "?" for a name with none.
    static func initial(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "?"
    }
}

/// A group's tile: its picture, or its first letter on its tone.
struct GroupTile: View {
    let group: GroupWire
    var size: CGFloat = 48

    var body: some View {
        PictureTile(id: group.id, name: group.name, picture: GroupPictureStore.shared.byId[group.id], size: size)
    }
}

/// D-187: a group's picture when it has one: a photo, or an emoji on the
/// group's tone; its initial otherwise. Also the New group preview.
struct PictureTile: View {
    let id: String
    let name: String
    let picture: PictureDTO?
    var size: CGFloat = 48

    var body: some View {
        let tone = GroupTone.of(id)
        let theme = AppearanceState.shared.theme
        let tileShape = TileShape.of(theme, person: false)
        let shape = TileClip.shape(tileShape, size: size)
        // D-220 (L23): the shape is drawn at its own side (Norse's diamond at size x sqrt 2, so its side is the
        // tile's), centred on a `size` footprint; the letter and the emoji follow the size.
        let side = TileClip.drawnSide(tileShape, size: size)
        Group {
            if let hex = picture?.photoHex, let image = GroupPictureStore.shared.image(hex) {
                Image(uiImage: image).resizable().scaledToFill()
            } else if let emoji = picture?.emoji {
                Text(verbatim: emoji).font(.system(size: size * 0.5))
            } else {
                Text(verbatim: GroupTone.initial(name))
                    .font(TileClip.initialFont(tileShape, size: size))
                    .foregroundStyle(tone.letter)
            }
        }
        .frame(width: side, height: side)
        .background(tone.fill, in: shape)
        .clipShape(shape)
        .overlay { TileClip.edge(tileShape) }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// D-179: the Groups tab (the founder's canvas, 2026-09-26): New group and
/// Join a group on top, then a card per group. Tapping a group makes it the
/// one Balance, Expenses, Add and Settle up work on and opens Balance.
struct GroupsScreen: View {
    var store: LedgerStore
    /// Opens Balance on the group just chosen (or created, or joined).
    var onOpen: () -> Void

    @State private var groups: [NamedGroupWire] = []
    /// D-210 (Task 8 look): groups whose place for this account has not arrived.
    @State private var waiting: [LedgerStore.WaitingGroup] = []
    @State private var selectedID: String?
    @State private var needsYourName = false
    @State private var creating = false
    @State private var error: String?
    /// D-210: the groups that are read-only copies: their People link is absent.
    @State private var copies: Set<String> = []
    /// D-208: the invitation being joined; its sheet hangs on this screen.
    @State private var joiningInvite: IncomingInvites.Item?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if TitleOrnament.shows(AppearanceState.shared.theme) { TitleOrnament(above: 8) }
                HStack(spacing: 10) {
                    Button("New group") { creating = true }
                        .buttonStyle(PrimaryButtonStyle())
                    // D-172: for everyone; without an account the join screen
                    // says why joining needs one.
                    NavigationLink {
                        JoinPasteScreen(store: store) { _ in
                            // D-234 (decision 91): as Balance's join ends: every tab reads the ledger again from its root,
                            // on Balance (Android's join route closes the same way), so the join pages never stay here.
                            NotificationCenter.default.post(name: .skiptiLedgerImported, object: nil)
                        }
                    } label: {
                        Text(verbatim: InviteCopy.joinTitle)
                    }
                    .buttonStyle(SecondaryButtonStyle())
                }
                // D-197: invitations sent to this account.
                InvitationsList(joined: Set(groups.map(\.group.id) + waiting.map(\.id)), joining: $joiningInvite)
                if groups.isEmpty && waiting.isEmpty && error == nil {
                    Text(verbatim: GroupsCopy.empty)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.textDim)
                        .glassCard()
                }
                ForEach(groups, id: \.group.id) { named in
                    card(named)
                }
                // A group still arriving: a row, nothing to tap.
                ForEach(waiting, id: \.id) { group in
                    waitingRow(group)
                }
                if let error {
                    Text(verbatim: error)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.owed)
                        .glassCard()
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .shellScrollTop() // D-205
        }
        .joinInvitationSheet($joiningInvite, store: store) {
            reload()
            onOpen()
        }
        .auroraBackground()
        .navigationTitle("Groups")
        .navigationBarTitleDisplayMode(.large)
        .sheet(isPresented: $creating) {
            NavigationStack {
                CreateGroupSheet(store: store, needsYourName: needsYourName) { _ in
                    creating = false
                    reload()
                    onOpen()
                }
            }
        }
        .task(id: SyncStatus.shared.revision) { reload() }
    }

    @ViewBuilder
    private func card(_ named: NamedGroupWire) -> some View {
        if let hex = GroupPictureStore.shared.byId[named.group.id]?.photoHex,
           let photo = GroupPictureStore.shared.image(hex) {
            photoCard(named, photo)
        } else {
            plainCard(named)
        }
    }

    /// D-187 (the founder): a group with a photo is the photo, edge to edge,
    /// its words white over `PhotoCard`'s gradient.
    private func photoCard(_ named: NamedGroupWire, _ photo: UIImage) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            NavigationLink {
                GroupPageScreen(groupID: named.group.id)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    // The top band holds only the chip, on its own film (the photo pill).
                    HStack {
                        Spacer(minLength: 0)
                        if named.group.id == selectedID {
                            Text(verbatim: GroupsCopy.showing)
                                .microLabelFont()
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .themePill(.photo)
                        }
                    }
                    .frame(height: PhotoCard.band - 16, alignment: .top)
                    Spacer(minLength: 12)
                    Text(verbatim: named.group.name)
                        .font(TypeRole.display(22, weight: 700))
                        .foregroundStyle(.white)
                    Text(verbatim: GroupsCopy.detail(people: named.members.count,
                                                     currency: named.group.settlementCurrency))
                        .font(TypeRole.body(TypeScale.detail))
                        .foregroundStyle(.white)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text(verbatim: GroupsCopy.openHint))
            if !copies.contains(named.group.id) {
                NavigationLink {
                    PeopleScreen(store: store, groupID: named.group.id) { reload(); OverviewActions.changed() }
                } label: {
                    // D-220 fix wave (I1): 44 pt tall, the whole frame the tap (Android's 48 dp).
                    Text(verbatim: GroupsCopy.people(named.group.name))
                        .font(TypeRole.label(14))
                        .foregroundStyle(.white)
                        .underline()
                        .frame(minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: PhotoCard.cardHeight, alignment: .bottomLeading)
        .background { PhotoBackdrop(photo: photo) }
        .photoFrame(radius: nil)
    }

    private func plainCard(_ named: NamedGroupWire) -> some View {
        // D-223 (L-D217-1): the stack spacing both shells read (12, iOS's value before).
        VStack(alignment: .leading, spacing: ThemeArt.m("groupsCardSpacing")) {
            // D-180: a group opens its page, which makes it the group Add,
            // Expenses and Settle up use.
            NavigationLink {
                GroupPageScreen(groupID: named.group.id)
            } label: {
                HStack(spacing: 12) {
                    GroupTile(group: named.group)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: named.group.name)
                            .font(TypeRole.name(17))
                            .foregroundStyle(Tokens.text)
                        // D-220 (L25): Norse's gold rune between the parts.
                        Separator.text(GroupsCopy.detailParts(people: named.members.count,
                                                              currency: named.group.settlementCurrency), size: TypeScale.detail)
                            .font(TypeRole.body(TypeScale.detail))
                            .foregroundStyle(Tokens.textDim)
                    }
                    Spacer(minLength: 8)
                    if named.group.id == selectedID {
                        Text(verbatim: GroupsCopy.showing)
                            .microLabelFont()
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .themePill(.accent)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text(verbatim: GroupsCopy.openHint))
            if !copies.contains(named.group.id) {
                NavigationLink {
                    PeopleScreen(store: store, groupID: named.group.id) { reload(); OverviewActions.changed() }
                } label: {
                    // D-220 fix wave (I1): 44 pt tall, the whole frame the tap (Android's 48 dp).
                    Text(verbatim: GroupsCopy.people(named.group.name))
                        .font(TypeRole.body(TypeScale.detail))
                        .foregroundStyle(Tokens.accent)
                        .frame(minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
            }
        }
        .glassCard()
    }

    /// A group whose place for this account has not arrived: its tile and
    /// name, and the waiting line. No actions; it resolves when the missing
    /// records arrive.
    private func waitingRow(_ group: LedgerStore.WaitingGroup) -> some View {
        HStack(spacing: 12) {
            PictureTile(id: group.id, name: group.name, picture: nil)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: group.name)
                    .font(TypeRole.name(17))
                    .foregroundStyle(Tokens.text)
                Text(verbatim: GroupsCopy.waiting)
                    .font(TypeRole.body(TypeScale.detail))
                    .foregroundStyle(Tokens.textDim)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard()
        .accessibilityElement(children: .combine)
    }

    private func reload() {
        do {
            let me = try store.ensureSelf()
            needsYourName = me.name == nil
            copies = Set(try store.readOnlyChecked().keys)
            groups = try store.namedGroups()
            waiting = try store.waitingGroups()
            selectedID = try store.currentGroup()?.group.id
            error = nil
        } catch LedgerStore.GroupStoreError.needsYourName {
            needsYourName = true
            groups = []
            waiting = []
        } catch {
            self.error = String(localized: "Could not load groups. \(String(describing: error))")
        }
    }

}

/// The Groups tab's words, one place; Android's twin is `GroupsCopy` in
/// GroupsTab.kt and the D-179 tests pin both.
enum GroupsCopy {
    static let empty = "No groups yet. Make one for a trip, a flat or a dinner, or join one a friend shared."
    static let showing = "Showing"
    static let openHint = "Opens this group"
    /// D-210 (Task 8 look): a group the records name but this account has no
    /// place in yet; Android's twin has the same words.
    static let waiting = "Waiting for this group to finish arriving."
    /// D-220 (L25): the line's parts; a card's body joins them through `Separator.text` (Norse's gold rune). The
    /// white line over a photo keeps `detail` (a white separator).
    static func detailParts(people: Int, currency: String) -> [String] { [GroupCopy.people(people), currency] }
    static func detail(people: Int, currency: String) -> String {
        detailParts(people: people, currency: currency).joined(separator: Separator.meta)
    }
    static func people(_ group: String) -> String { "People in \(group)" }
}

#if DEBUG
// D-235 (C4, iOS L1, decision 108): a proof surface, Debug builds only.
/// `--groups-proof` (D-179, headless verification, not a user flow): on an
/// empty ledger, gives you a name and makes three groups (the canvas's Goa
/// trip, Flat 4B and Paris), then shows the ordinary tabs on Groups.
/// Android's twin is `GroupsProof` in GroupsTab.kt.
struct GroupsProofScreen: View {
    @State private var ready = false
    @State private var failure: String?

    var body: some View {
        if ready {
            HomeShell(startOn: .groups)
        } else if let failure {
            Text(verbatim: failure).font(TypeRole.body()).padding()
        } else {
            ProgressView().task {
                do {
                    try GroupsProof.seed(LedgerStore.defaultStore())
                    ready = true
                } catch {
                    failure = "\(error)"
                }
            }
        }
    }
}

enum GroupsProof {
    /// Only when there is no group yet: a proof never adds to a real ledger.
    static func seed(_ store: LedgerStore) throws {
        if try store.ensureSelf().name == nil { _ = try store.setYourName("Prithvi") }
        guard try store.namedGroups().isEmpty else { return }
        _ = try store.createGroup(name: "Goa trip", settlementCurrency: "INR", people: ["Rahul", "Meera", "Sam"])
        _ = try store.createGroup(name: "Flat 4B", settlementCurrency: "INR", people: ["Meera", "Kiran"])
        _ = try store.createGroup(name: "Paris", settlementCurrency: "EUR", people: ["Ana"])
        // D-180: a few expenses, so Balance and a group's page have numbers.
        let groups = try store.namedGroups()
        let tree = ffiCategoryTreeDefault()
        func leaf(_ parent: String, _ name: String) -> String? {
            let p = tree.categories.first { $0.parent == nil && $0.name == parent }
            return tree.categories.first { $0.parent == p?.id && $0.name == name }?.id
        }
        func add(_ group: String, payer: Int, _ minor: Int64, _ code: String, _ note: String,
                 _ category: String?, hoursAgo: UInt64) throws {
            guard let g = groups.first(where: { $0.group.name == group }) else { return }
            let ids = g.members.map(\.id)
            let each = minor / Int64(ids.count)
            let now = UInt64(Date().timeIntervalSince1970 * 1000)
            _ = try store.appendExpense(LedgerStore.ExpenseDTO(
                payer: ids[payer], paidMinor: each * Int64(ids.count), currencyCode: code, group: g.group.id,
                conversion: nil, shares: ids.map { .init(id: $0, amountMinor: each) }, note: note,
                // D-189: added by whoever paid, so Activity has something to show.
                timestampMs: now - hoursAgo * 3_600_000, category: category, addedBy: ids[payer]
            ))
        }
        try add("Goa trip", payer: 0, 1_200_000, "INR", "Villa, 2 nights", leaf("Stay", "Rentals"), hoursAgo: 30)
        try add("Goa trip", payer: 1, 480_000, "INR", "Dinner at Britto's", leaf("Food & Drink", "Restaurants"), hoursAgo: 3)
        try add("Goa trip", payer: 2, 160_000, "INR", "Scooters", leaf("Travel", "Rentals"), hoursAgo: 2)
        try add("Flat 4B", payer: 0, 270_000, "INR", "Electricity", leaf("Bills & Utilities", "Electricity"), hoursAgo: 26)
        try add("Flat 4B", payer: 1, 135_000, "INR", "Groceries", leaf("Food & Drink", "Groceries"), hoursAgo: 20)
        try add("Paris", payer: 1, 6_400, "EUR", "Museum passes", leaf("Entertainment", "Events"), hoursAgo: 300)
    }
}
#endif
