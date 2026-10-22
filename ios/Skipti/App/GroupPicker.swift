import SwiftUI

/// The group list (D-135; founder decision 2026-09-14: many groups, with a
/// list). Opened from the Balance header. Selecting a group makes it the one
/// Balance, Expenses, Add and Settle work on; "New group" opens the create
/// sheet; each group's people are managed on its People screen.
struct GroupPicker: View {
    var store: LedgerStore
    var onChange: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var groups: [NamedGroupWire] = []
    @State private var selectedID: String?
    @State private var needsYourName = false
    @State private var creating = false
    @State private var error: String?

    var body: some View {
        List {
            if !groups.isEmpty {
                Section {
                    ForEach(groups, id: \.group.id) { named in
                        Button {
                            select(named)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: named.group.name)
                                        .font(TypeRole.body())
                                        .foregroundStyle(Tokens.text)
                                    Separator.text([GroupCopy.people(named.members.count), "settles in \(named.group.settlementCurrency)"], size: 14)
                                        .font(TypeRole.body(14))
                                        .foregroundStyle(Tokens.textDim)
                                }
                                Spacer()
                                if named.group.id == selectedID {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(Tokens.accent)
                                        .accessibilityLabel("Selected")
                                }
                            }
                        }
                        NavigationLink {
                            PeopleScreen(store: store, groupID: named.group.id) {
                                reload()
                                onChange()
                            }
                        } label: {
                            Text("People in \(named.group.name)")
                                .font(TypeRole.body(14))
                                .foregroundStyle(Tokens.textDim)
                        }
                    }
                } header: {
                    header("Groups")
                }
            }
            if let error {
                Section {
                    Text(error)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.owed)
                }
            }
        }
        // Below the list, not in it: on iOS 27 a clear list row is clipped to a
        // pill, which erased this button's outline (seen on the simulator).
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                Button("New group") { creating = true }
                    .buttonStyle(SecondaryButtonStyle())
                // D-172: for everyone (the founder, 2026-09-26); without an
                // account the join screen says why it needs one (D-159 hid it).
                // D-195: back, on SKIPTI's cloud.
                NavigationLink {
                    JoinPasteScreen(store: store) { _ in
                        reload()
                        onChange()
                        dismiss()
                    }
                } label: {
                    Text(verbatim: InviteCopy.joinTitle)
                }
                .buttonStyle(SecondaryButtonStyle())
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 8)
        }
        .auroraFormBackground() // D-176
        .navigationTitle("Groups")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $creating) {
            NavigationStack {
                CreateGroupSheet(store: store, needsYourName: needsYourName) { _ in
                    creating = false
                    reload()
                    onChange()
                    dismiss()
                }
            }
        }
        .task { reload() }
    }

    private func reload() {
        do {
            let me = try store.ensureSelf()
            needsYourName = me.name == nil
            // D-210: a read-only copy is never the group Add works on.
            let copies = Set(try store.readOnlyChecked().keys)
            groups = try store.namedGroups().filter { !copies.contains($0.group.id) }
            selectedID = try store.currentGroup()?.group.id
            error = nil
        } catch LedgerStore.GroupStoreError.needsYourName {
            needsYourName = true
            groups = []
        } catch {
            self.error = String(localized: "Could not load groups. \(String(describing: error))")
        }
    }

    private func select(_ named: NamedGroupWire) {
        do {
            try store.selectGroup(id: named.group.id)
            selectedID = named.group.id
            onChange()
            dismiss()
        } catch {
            self.error = String(localized: "Could not select the group. \(String(describing: error))")
        }
    }

    private func header(_ text: String) -> some View {
        Text(text)
            .sectionLabel()
            .foregroundStyle(Tokens.textDim)
    }
}

/// One group's people: rename anyone, including yourself; add someone by
/// name. Names come from core's rules (D-134), so a duplicate or over-long
/// name is refused with plain copy. Names render verbatim: a typed name may
/// contain an email address, and SwiftUI would autolink a string-literal
/// title (D-128).
/// D-186: taking a person out of a group, in words. Android's twin is
/// `MemberCopy` in GroupScreens.kt.
enum MemberCopy {
    enum Refusal: Equatable { case you, joined, history }

    struct Refused: Error, Equatable { var why: Refusal }

    static func removeTitle(_ name: String, from group: String) -> String { "Remove \(name) from \(group)?" }
    static let removeBody = "They are in no expense or payment here. You can add them again later."
    static let remove = "Remove"
    static let cancel = "Cancel"
    static let stays = "Anyone in an expense or payment here, or who joined on their phone, stays in the group."
    static let addPeople = "Add people"
    static func spokenRemove(_ name: String) -> String { "Remove \(name)" }

    static func message(_ why: Refusal) -> String {
        switch why {
        case .you: return "You cannot take yourself out of a group."
        case .joined: return "They joined on their phone, so they stay in the group."
        case .history: return "They are in an expense or payment here, so they stay in the group."
        }
    }
}

struct PeopleScreen: View {
    var store: LedgerStore
    var groupID: String
    var onChange: () -> Void

    @State private var named: NamedGroupWire?
    @State private var editingID: String?
    @State private var editText = ""
    @State private var error: String?
    /// D-159/D-195: members not yet linked to an account, shown only with an
    /// account of your own (inviting needs one; GUARANTEES #5).
    @State private var invitable: [MemberWire] = []
    /// D-186: who can be taken out (no history, never joined, not you), and
    /// the one being asked about.
    @State private var removable: Set<String> = []
    @State private var removing: MemberWire?
    /// D-207: the add-person sheet.
    @State private var adding = false
    /// D-210: what this phone knows about the group's cloud, roles and requests.
    @State private var people = GroupPeople()
    /// D-211: each group's people with a profile (previews; Rename absent).
    @State private var previews: [String: [String: Avatar]] = [:]
    @State private var viewerHasBalance = false
    /// The person's sheet and a request's review: owned here, attached to the
    /// always-present ScrollView (CLAUDE.md §10.1).
    @State private var personSheet: PersonRow?
    @State private var review: RemovalTarget?
    @State private var cardBusy = false
    /// What each open request's person owes here, by request id.
    @State private var cardBalances: [String: String] = [:]
    @ObservedObject private var roles = GroupRoles.shared
    @ObservedObject private var notices = Notices.shared
    @Environment(\.openSettleTab) private var openSettle

    /// A copy of the group (removed from, or deleted): nothing here can be
    /// done, and its roles are not shown.
    private var isCopy: Bool { people.readOnly != nil || loadFailed }
    /// The last read of the group failed: whether it is a copy is unknown, so
    /// it is treated as one (nothing offered) until a read works.
    @State private var loadFailed = false

    /// Shared groups show roles and a sheet per person; a group only on this
    /// phone keeps D-186's cross and rename-on-tap exactly as they were.
    private var rows: [PersonRow] {
        guard let named else { return [] }
        return PeopleRoles.rows(
            members: named.members, selfId: named.selfId, viewerYou: people.viewerYou, claims: people.claims,
            roles: roles.roles[groupID], yourPlaces: roles.roles[groupID]?.places?.you, otherYouIds: people.otherYouIds,
            readOnly: isCopy, createdHere: people.createdHere)
    }

    /// D-227 fix wave (F4): the open sheet's row as the screen reads it now, so a role the cloud just confirmed shows
    /// at once (the sheet was handed the row it opened with).
    private func liveRow(_ row: PersonRow) -> PersonRow { rows.first { $0.id == row.id } ?? row }

    private var cards: [RequestCardItem] {
        guard named != nil, people.shared, !isCopy else { return [] }
        return RequestCards.cards(items: notices.requests(in: groupID), rows: rows)
    }

    var body: some View {
        // D-225 (Design 8; L-D217-8, A1): the request cards as before; the "People" label over one list card of members
        // (the stays caption under it for a phone-only group); the "Invite" label over a list card of Invite rows and
        // its caption; "Add a person" as the page's primary button below the cards (the card around it was the Form's
        // row-clip workaround, D-139, gone with the Form). 16 apart, each label and caption 8 from its card. The sheets
        // and alerts hang on the always-present ScrollView (CLAUDE.md 10.1). Android's twin is PeopleScreen in
        // GroupScreens.kt.
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let named {
                    ForEach(cards) { card in
                        RequestCardView(
                            card: card, canSettleUp: viewerHasBalance, balance: cardBalances[card.item.id], busy: cardBusy,
                            onDecline: { decline(card.item, named) },
                            onReview: { openReview(card.item, named) },
                            onSettle: openSettle)
                            .requestCardSurface()
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        header("People")
                        ListCard {
                            ForEach(named.members, id: \.id) { member in
                                memberRow(member, named)
                            }
                        }
                        // A group in the cloud lets the owner or an admin remove anyone.
                        if !people.shared {
                            CardCaption(MemberCopy.stays)
                        }
                    }
                    if !invitable.isEmpty && !isCopy, let line = ApprovalCopy.invite(Approval.shared.state) {
                        // D-254: one line instead of the invite rows while the account is not approved.
                        VStack(alignment: .leading, spacing: 8) {
                            header(InviteCopy.section)
                            CardCaption(line)
                        }
                    } else if !invitable.isEmpty && !isCopy {
                        VStack(alignment: .leading, spacing: 8) {
                            header(InviteCopy.section)
                            ListCard {
                                ForEach(invitable, id: \.id) { member in
                                    NavigationLink {
                                        InviteScreen(store: store, group: named, member: member)
                                    } label: {
                                        ListRow(title: InviteCopy.invite(member.name), trailing: .chevron)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            CardCaption(InviteCopy.sectionFooter)
                        }
                    }
                }
                if !isCopy {
                    // D-207: a sheet with the name, an optional @username and recents (the founder's item 1).
                    Button(AddPersonCopy.title) { adding = true }
                        .buttonStyle(PrimaryButtonStyle())
                }
                if let error {
                    Text(error)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.owed)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground() // D-176
        .navigationTitle("People")
        .navigationBarTitleDisplayMode(.inline)
        // Whatever arrived (a removal or a deletion applied to this phone, a
        // role) reads the group again; so does a request coming or going.
        .task(id: SyncStatus.shared.revision) { load() }
        .task(id: notices.requests(in: groupID).map(\.id)) { loadCardBalances() }
        .sheet(isPresented: $adding) {
            NavigationStack {
                AddPersonSheet(store: store, groupID: groupID) { group in
                    _ = apply { group }
                }
            }
        }
        .sheet(item: $personSheet) { row in
            if let named {
                NavigationStack {
                    PersonSheet(
                        store: store, named: named, row: liveRow(row),
                        viewerName: named.members.first { $0.id == named.selfId }?.name ?? "",
                        preview: preview(of: row.member.id),
                        canRename: mayRename(row.member.id),
                        onRename: {
                            personSheet = nil
                            editingID = row.member.id
                            editText = row.member.name
                        },
                        onDone: {
                            personSheet = nil
                            load()
                            onChange()
                        })
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button(RolesCopy.cancel) { personSheet = nil }.barButtonText().barTint() }
                        }
                }
                .presentationDetents([.medium, .large])
            }
        }
        .sheet(item: $review) { target in
            if let named {
                RemovalSheet(
                    store: store, named: named, target: target, shared: people.shared,
                    onFinished: { review = nil; load(); onChange() },
                    onClose: { review = nil })
            }
        }
        .alert(
            removing.map { MemberCopy.removeTitle($0.name, from: named?.group.name ?? "") } ?? "",
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
            presenting: removing
        ) { member in
            Button(MemberCopy.remove, role: .destructive) {
                if apply({ try store.removeMember(groupID: groupID, memberID: member.id) }) {
                    CloudSync.shared.ledgerChanged()
                    // D-224 (Design 7): the person removed from this local group.
                    DoneCenter.shared.post(DoneMoments.line(DoneCopy.personRemoved(member.name)))
                }
            }
            Button(MemberCopy.cancel, role: .cancel) {}
        } message: { _ in
            Text(verbatim: MemberCopy.removeBody)
        }
        // D-224 (Design 3): the warning as the removal's question opens.
        .hapticWarning(when: removing != nil)
    }

    private func openReview(_ item: Notices.Item, _ named: NamedGroupWire) {
        guard let id = item.memberId, let row = rows.first(where: { $0.place == id }) else { return }
        review = RemovalTarget(member: row.member, you: row.you, place: row.place,
                               mayRemove: RequestCards.mayRemove(rows: rows, member: id), request: item)
    }

    private func decline(_ item: Notices.Item, _ named: NamedGroupWire) {
        let viewerName = named.members.first { $0.id == named.selfId }?.name ?? ""
        cardBusy = true
        Task {
            defer { cardBusy = false }
            do {
                try await RoleCalls.decline(item, group: named, byName: viewerName)
                error = nil
            } catch {
                self.error = RolesCopy.failure(error, person: true)
            }
        }
    }

    private func loadCardBalances() {
        cardBalances = RequestCards.balanceLines(store: store, group: groupID, items: notices.requests(in: groupID))
    }

    private func load() {
        // The roles this phone last heard, offline too.
        roles.load(groupID)
        loadCardBalances()
        // D-211: read first and apart from the rest. A picture is decoration:
        // a failed read keeps the last good previews and never fails the
        // screen (which would show the people as a read-only copy).
        previews = MemberPreviews.reloaded(last: previews, read: store.memberPreviews)
        do {
            named = try store.namedGroups().first(where: { $0.group.id == groupID })
            people = try GroupPeople.load(store: store, groupID: groupID)
            loadFailed = false
            if let named {
                viewerHasBalance = try Removal.plan(
                    lines: Removal.lines(store: store, group: groupID, member: named.selfId), member: named.selfId).isEmpty == false
            }
            invitable = try Self.invitable(store, named: named, signedIn: Account.shared.isSignedIn)
            removable = Set(try (named?.members ?? []).map(\.id).filter {
                try store.removalRefusal(groupID: groupID, memberID: $0) == nil
            })
        } catch {
            loadFailed = true
            self.error = String(localized: "Could not load people. \(String(describing: error))")
        }
    }

    private func preview(of memberID: String) -> Avatar {
        MemberPreviews.preview(previews, group: groupID, member: memberID)
    }

    private func mayRename(_ memberID: String) -> Bool {
        MemberPreviews.mayRename(previews, group: groupID, member: memberID)
    }

    private func rename(_ memberID: String) {
        if apply({ try store.renameMember(groupID: groupID, memberID: memberID, name: editText) }) {
            CloudSync.shared.ledgerChanged()
            editingID = nil
        }
    }

    /// Everyone but you who has not joined yet; nobody without an account.
    static func invitable(_ store: LedgerStore, named: NamedGroupWire?, signedIn: Bool) throws -> [MemberWire] {
        guard let named, signedIn else { return [] }
        let claimed = Set(try LedgerStore.Sync.projectGroups(store).groups
            .first { $0.group.id == named.group.id }?.claims.map(\.memberId) ?? [])
        return named.members.filter { $0.id != named.selfId && !claimed.contains($0.id) }
    }

    @discardableResult
    private func apply(_ change: () throws -> NamedGroupWire) -> Bool {
        do {
            named = try change()
            invitable = (try? Self.invitable(store, named: named, signedIn: Account.shared.isSignedIn)) ?? []
            removable = Set((try? (named?.members ?? []).map(\.id).filter {
                try store.removalRefusal(groupID: groupID, memberID: $0) == nil
            }) ?? [])
            error = nil
            onChange()
            return true
        } catch let e as CoreError {
            error = GroupCopy.message(e)
        } catch let e as MemberCopy.Refused {
            error = MemberCopy.message(e.why)
        } catch {
            self.error = String(localized: "Could not save. \(String(describing: error))")
        }
        return false
    }

    /// D-225 (Design 8): one member row of People's card, by what the viewer may do with them: a rename field while
    /// renaming; on a copy, or for a person with a profile, the avatar and the name alone; in a shared group the avatar,
    /// the name, the role pill and the chevron to the person's sheet; in a group only on this phone the name (a tap
    /// renames) and, when they can be taken out, Lucide's cross in a 48 target (D-186).
    @ViewBuilder
    private func memberRow(_ member: MemberWire, _ named: NamedGroupWire) -> some View {
        if editingID == member.id {
            FieldRow("Name", text: $editText, spoken: "New name")
                .onSubmit { rename(member.id) }
                .submitLabel(.done)
        } else if isCopy {
            // A copy: who is in it, nothing to do with them.
            memberLine(member, named, avatar: true)
        } else if people.shared, let row = rows.first(where: { $0.member.id == member.id }) {
            Button { personSheet = row } label: {
                memberLine(member, named, avatar: true, role: row.role, chevron: true)
            }
            .buttonStyle(.plain)
        } else if !mayRename(member.id) {
            // D-211: a person with a profile: their name is theirs, so no Rename.
            memberLine(member, named, avatar: true)
        } else {
            HStack(spacing: ThemeArt.m("rowGap")) {
                Button {
                    editingID = member.id
                    editText = member.name
                } label: {
                    memberLine(member, named, avatar: false)
                }
                .buttonStyle(.plain)
                .accessibilityHint("Rename")
                if removable.contains(member.id) {
                    // D-186, D-225 (Design 8): Lucide's x at `clearGlyph` in textFaint, in a 48 target.
                    Button { removing = member } label: {
                        LucideIcon(glyph: .x, size: ThemeArt.m("clearGlyph"))
                            .foregroundStyle(Tokens.textFaint)
                            .frame(width: ThemeArt.m("touchTarget"), height: ThemeArt.m("touchTarget"))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(MemberCopy.spokenRemove(member.name))
                }
            }
        }
    }

    /// A member row's drawing at the list row's metrics: the avatar 36 when asked, a row gap, the name in the name role
    /// at 16 with the role pill beside it, then the chevron when the row opens the person's sheet.
    private func memberLine(_ member: MemberWire, _ named: NamedGroupWire, avatar: Bool,
                            role: RoleRules.Role? = nil, chevron: Bool = false) -> some View {
        HStack(spacing: ThemeArt.m("rowGap")) {
            if avatar {
                PersonAvatar(name: member.name, size: 36, preview: preview(of: member.id))
            }
            HStack(spacing: 8) {
                Text(verbatim: LedgerStore.label(for: member.id, in: named))
                    .font(TypeRole.name(16))
                    .foregroundStyle(Tokens.text)
                    .lineLimit(1)
                if let role { RolePill(role: role) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if chevron { RowChevron(color: Tokens.textDim) }
        }
        .padding(.vertical, ThemeArt.m("rowPadV"))
        .frame(minHeight: ThemeArt.m("touchTarget"))
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func header(_ text: String) -> some View {
        Text(text)
            .sectionLabel()
            .foregroundStyle(Tokens.textDim)
    }
}
