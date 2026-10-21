import SwiftUI

/// D-210: a group's settings (its name, picture and currency, and deleting
/// it), the banner and actions of a read-only copy. Android's twin is
/// GroupSettings.kt.

/// D-210 (Task 8 look): the owner's delete for everyone as one sequence. A
/// retry after a first try that deleted in the cloud but failed to mark this
/// copy gets NOT_FOUND from the Function: the group is already gone, so it is
/// not an error, and the copy is marked as it would have been. Every other
/// failure is rethrown for the screen's words. Android's twin is `GroupDelete`.
enum GroupDelete {
    /// The mark failed (the cloud part is done or was already done).
    struct MarkFailed: Error {
        let underlying: Error
    }

    static func alreadyGone(_ error: Error) -> Bool {
        guard case .refused(let status)? = error as? CloudFunctions.Failure else { return false }
        return status == "NOT_FOUND"
    }

    static func run(call: () async throws -> Void, mark: () throws -> Void) async throws {
        do {
            try await call()
        } catch {
            if !alreadyGone(error) { throw error }
        }
        do {
            try mark()
        } catch {
            throw MarkFailed(underlying: error)
        }
    }
}

enum GroupSettings {
    /// What the settings screen offers.
    struct Actions: Equatable {
        let name: Bool
        let picture: Bool
        let delete: Bool
    }

    /// A copy (or a group whose mark could not be read: fail closed) offers
    /// nothing that changes it. Delete group: the owner of a group in the
    /// cloud, anyone with a group only on their phone; absent otherwise
    /// (D-126). Android's twin is `GroupSettings.actions(readOnly, isOwner, shared)`.
    static func actions(readOnly: Bool, isOwner: Bool, shared: Bool) -> Actions {
        if readOnly { return Actions(name: false, picture: false, delete: false) }
        return Actions(name: true, picture: true, delete: shared ? isOwner : true)
    }

    /// What is still open in a group, for the delete confirm: every person's
    /// nonzero balance, per currency (the currencies in order, the largest
    /// first), as "Sam owes ₹1,000.00 · you are owed ₹700.00" (`viewer` reads
    /// as "you"); nil when nothing is open. Amounts come from core's formatter.
    static func stillOpen(nets: [String: [String: Int64]], names: [String: String], viewer: String? = nil) -> String? {
        var parts: [String] = []
        for code in nets.keys.sorted() {
            let entries = (nets[code] ?? [:]).filter { $0.value != 0 }
                .sorted { (abs($0.value), $1.key) > (abs($1.value), $0.key) }
            for (id, net) in entries {
                guard let amount = RolesMoney.format(abs(net), code) else { continue }
                if id == viewer {
                    parts.append(net < 0 ? RolesCopy.youOwe(amount) : RolesCopy.youAreOwed(amount))
                } else {
                    let name = names[id] ?? LedgerStore.unknownName
                    parts.append(net < 0 ? RolesCopy.owes(name, amount) : RolesCopy.isOwed(name, amount))
                }
            }
        }
        return parts.isEmpty ? nil : parts.joined(separator: Separator.meta)
    }

    /// The same line from what this phone holds: every person's net per
    /// currency read through core's balance lines (`ffiBalanceLine`, with the
    /// group's redistributions) framed on each person who is, or was, in the
    /// group.
    static func stillOpen(store: LedgerStore, group: String, viewer: String?) throws -> String? {
        let names = try store.historyNames(group: group)
        let expenses = try store.loadExpenses().filter { $0.group == group }.map(\.asInput)
        let settlements = try store.loadSettlements().filter { $0.group == group }.map(\.asInput)
        let redistributions = try store.loadRedistributions().filter { $0.group == group }
        let removed = try store.removedIds(group: group)
        var ids = Set(names.keys)
        if let viewer { ids.insert(viewer) }
        var nets: [String: [String: Int64]] = [:]
        for id in ids {
            let lines = try ffiBalanceLine(
                expenses: expenses, settlements: settlements, redistributions: redistributions, removed: removed, viewer: id, group: group)
            for line in lines where line.viewerNetMinor != 0 { nets[line.currencyCode, default: [:]][id] = line.viewerNetMinor }
        }
        return stillOpen(nets: nets, names: names, viewer: viewer)
    }
}

extension LedgerStore {
    static var unknownName: String { String(localized: "Unknown member") }

    /// Every name a group's history may need, by participant id: the
    /// projection keeps removed people in `members` (D-186), so a line about
    /// a removed person still names them; the screens' names (yours
    /// resolved) win where both have one.
    func historyNames(group: String) throws -> [String: String] {
        let p = try Sync.projectGroups(self)
        let projected = p.groups.first { $0.group.id == group }?.members ?? []
        var names = Dictionary(projected.compactMap { m in m.name.map { (m.id, $0) } }, uniquingKeysWith: { a, _ in a })
        for m in try namedGroups().first(where: { $0.group.id == group })?.members ?? [] { names[m.id] = m.name }
        return LedgerStore.namingMergedPlaces(
            names, you: p.you?.id, otherYouIds: p.otherYouIds, aliases: p.aliases, group: group)
    }

    /// D-230 F1 (decision 67; the history line is D-210's): a group's history
    /// records keep raw place ids forever, but the projection shows a place
    /// this phone merged under another id. This account's other places
    /// (`otherYouIds`, merged into you) take your own name; another account's
    /// "you", read through an alias core applied in THIS group, takes the
    /// name of the place it claimed. Only an id with no name yet is filled; a
    /// name is never overwritten. With no "you" nothing is filled (review
    /// M2): `Sync.yous` merges nothing then, so the ledger keeps every raw id
    /// and naming one would show a second row under the same name. Twin:
    /// GroupSettings.kt `namingMergedPlaces`.
    static func namingMergedPlaces(_ names: [String: String], you: String?, otherYouIds: [String],
                                   aliases: [IdAlias], group: String) -> [String: String] {
        guard let you else { return names }
        var names = names
        if let n = names[you] { for id in otherYouIds where names[id] == nil { names[id] = n } }
        for a in aliases where a.groupId == group && names[a.fromId] == nil { if let n = names[a.toId] { names[a.fromId] = n } }
        return names
    }

    /// D-210 (final B1): the people removed from each group, by group, named
    /// (the projection's names, the screens' where they have one, else
    /// "Unknown member"). Core takes a removed person at zero out of the
    /// cards (`ffiBalanceLine`'s `removed`, `OverviewGroupIn.removed`) and
    /// names one who still owes from here.
    func removedPeople() throws -> [String: [MemberWire]] {
        let projected = try Sync.projectGroups(self).groups
        let named = Dictionary(try namedGroups().map { ($0.group.id, $0) }, uniquingKeysWith: { a, _ in a })
        var out: [String: [MemberWire]] = [:]
        for g in projected where !g.removed.isEmpty {
            let screens = Dictionary((named[g.group.id]?.members ?? []).map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
            let history = Dictionary(g.members.compactMap { m in m.name.map { (m.id, $0) } }, uniquingKeysWith: { a, _ in a })
            out[g.group.id] = g.removed.map { id in
                MemberWire(id: id, name: screens[id] ?? history[id] ?? LedgerStore.unknownName)
            }
        }
        return out
    }

    /// One group's removed place ids, for `ffiBalanceLine`.
    func removedIds(group: String) throws -> [String] {
        try removedPeople()[group]?.map(\.id) ?? []
    }

    /// Renames a group as an operation every member's phone applies. A name
    /// is trimmed and cannot be empty (core refuses the operation otherwise).
    /// D-229 decision 45: refused in a group where this account lost its place
    /// (`Placing.op` maps nothing here, it only refuses).
    func renameGroup(groupID: String, name: String) throws {
        try Sync.appendGroupOps(
            self, [.renameGroup(groupId: groupID, name: name.trimmingCharacters(in: .whitespacesAndNewlines))],
            placing: try Placing.load(self))
        try Sync.rebuildGroups(self)
    }
}

// MARK: - The settings screen

struct GroupSettingsScreen: View {
    /// D-225 fix wave (look F3): the Picture row's tile, drawn inside its 30 slot (`TileClip.sizeInSlot`) and laid
    /// out by its drawn side (`tileFootprint`), so Norse's diamond takes the slot Aurora's tile takes.
    private func pictureTile(_ group: GroupWire) -> some View {
        let shape = TileShape.of(AppearanceState.shared.theme, person: false)
        let size = TileClip.sizeInSlot(shape, slot: 30)
        return GroupTile(group: group, size: size).tileFootprint(shape, size: size)
    }

    let store: LedgerStore
    let groupID: String
    /// A group deleted (only on this phone, or for everyone): the page under
    /// this screen closes too, so the person lands where they opened it (U27).
    var onGone: () -> Void

    @Environment(\.dismiss) private var dismiss
    /// U27: the tab this screen was pushed in (nil in the push-opened sheet, outside the shell).
    @Environment(\.shellTab) private var shellTab
    @ObservedObject private var roles = GroupRoles.shared
    @State private var named: NamedGroupWire?
    @State private var people = GroupPeople()
    @State private var renaming = false
    @State private var nameText = ""
    @State private var choosingPicture = false
    @State private var deleteConfirm: DeleteConfirm?
    @State private var confirmingLocalDelete = false
    @State private var busy = false
    @State private var error: String?
    /// A read that failed counts as a copy: nothing that changes the group is offered.
    @State private var loadFailed = false

    private var viewerName: String { named?.members.first { $0.id == named?.selfId }?.name ?? "" }

    private var viewerRole: RoleRules.Role {
        guard let named else { return .member }
        return PeopleRoles.viewerRole(PeopleRoles.rows(
            members: named.members, selfId: named.selfId, viewerYou: people.viewerYou, claims: people.claims,
            roles: roles.roles[groupID], yourPlaces: roles.roles[groupID]?.places?.you, otherYouIds: people.otherYouIds))
    }

    /// Name, Picture and Delete group are absent on a copy, live: the screen
    /// reloads on every revision, so a group that becomes a copy while this
    /// is open loses them. Delete group is the owner's in the cloud (D-126).
    private var actions: GroupSettings.Actions {
        GroupSettings.actions(
            readOnly: people.readOnly != nil || loadFailed, isOwner: RoleRules.canDelete(viewerRole), shared: people.shared)
    }

    var body: some View {
        // D-225 (Design 7; L-D217-1, A1): one list card (Name, Picture, Settles in: rows of one height on both phones),
        // then the danger card with Delete group, 16 apart, on the theme's ground (no system Form: its sections stayed
        // rounded in Norse and Vardr Labs). The first card where the Form put it, as the currency list's.
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let named {
                    ListCard {
                        if actions.name {
                            Button { nameText = named.group.name; renaming = true } label: {
                                ListRow(title: RolesCopy.settingsName, trailing: .value(named.group.name, chevron: true))
                            }
                            .buttonStyle(.plain)
                        }
                        if actions.picture {
                            Button { choosingPicture = true } label: {
                                ListRow(title: RolesCopy.settingsPicture,
                                        // Fix wave (F3): the tile drawn inside its 30 slot in every theme, and laid out by
                                        // its drawn side (the slot), so the chevron keeps Aurora's gap.
                                        trailing: .piece(AnyView(pictureTile(named.group)), chevron: true))
                            }
                            .buttonStyle(.plain)
                        }
                        ListRow(title: RolesCopy.settingsSettlesIn, trailing: .value(named.group.settlementCurrency, chevron: false))
                    }
                    if actions.delete {
                        ListCard {
                            Button { startDelete(named) } label: {
                                ListRow(title: RolesCopy.deleteGroup, parts: people.shared ? [RolesCopy.deleteGroupSub] : [],
                                        summaryLines: 0, tone: .danger)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                if let error {
                    Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, CurrencyListScreen.topPadding)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        .navigationTitle(Text(verbatim: RolesCopy.groupSettingsTitle))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: SyncStatus.shared.revision) { load() }
        .disabled(busy)
        // D-240 review (M1): no way back while the delete runs, as Android swallows Back (`BackHandler(enabled = busy)`).
        .navigationBarBackButtonHidden(busy)
        .sheet(isPresented: $choosingPicture) {
            GroupPictureSheet(onChosen: { emoji, jpeg in setPicture(emoji, jpeg) }, onError: { error = $0 })
        }
        // The sheet carries what it shows: the open balances are read when
        // Delete group is tapped and travel in the item, never in screen state.
        .sheet(item: $deleteConfirm) { confirm in
            DeleteGroupConfirm(group: confirm.group, stillOpen: confirm.stillOpen) {
                deleteConfirm = nil
                if let named { deleteForEveryone(named) }
            } onCancel: { deleteConfirm = nil }
        }
        // D-224 (Design 3): the warning as the delete-for-everyone question opens.
        .hapticWarning(when: deleteConfirm != nil)
        .alert(RolesCopy.settingsName, isPresented: $renaming) {
            TextField(RolesCopy.settingsName, text: $nameText)
            Button(RolesCopy.save) { rename() }
            Button(RolesCopy.cancel, role: .cancel) {}
        }
        .alert(
            RolesCopy.deleteLocalTitle(named?.group.name ?? ""), isPresented: $confirmingLocalDelete
        ) {
            Button(RolesCopy.deleteLocalConfirm, role: .destructive) { deleteHere() }
            Button(RolesCopy.cancel, role: .cancel) {}
        } message: {
            Text(verbatim: RolesCopy.deleteLocalBody)
        }
        .hapticWarning(when: confirmingLocalDelete)
    }

    private func load() {
        // The roles this phone last heard, offline too.
        roles.load(groupID)
        do {
            named = try store.namedGroups().first { $0.group.id == groupID }
            people = try GroupPeople.load(store: store, groupID: groupID)
            loadFailed = false
            error = nil
        } catch {
            loadFailed = true
            self.error = String(localized: "Could not load the group. \(String(describing: error))")
        }
        // What is no longer offered closes too, even mid-confirm.
        let now = actions
        if !now.name { renaming = false }
        if !now.picture { choosingPicture = false }
        if !now.delete { deleteConfirm = nil; confirmingLocalDelete = false }
    }

    private func rename() {
        do {
            try store.renameGroup(groupID: groupID, name: nameText)
            CloudSync.shared.ledgerChanged()
            SyncStatus.shared.changed()
            load()
            // D-224 (Design 7).
            DoneCenter.shared.post(DoneMoments.line(DoneCopy.groupNameSaved))
        } catch let e as CoreError {
            error = GroupCopy.message(e)
        } catch {
            self.error = String(localized: "Could not save. \(String(describing: error))")
        }
    }

    private func setPicture(_ emoji: String?, _ jpeg: Data?) {
        do {
            try store.setGroupPicture(groupID: groupID, emoji: emoji, jpeg: jpeg)
            GroupPictureStore.shared.load(store)
            CloudSync.shared.ledgerChanged()
            SyncStatus.shared.changed()
            error = nil
            // D-224 (Design 7): "Use letter" (no emoji, no photo) removes the picture.
            DoneCenter.shared.post(DoneMoments.line(
                emoji == nil && jpeg == nil ? DoneCopy.groupPictureRemoved : DoneCopy.groupPictureSaved))
        } catch {
            self.error = GroupPictures.tooBig
        }
    }

    private func startDelete(_ named: NamedGroupWire) {
        guard people.shared else { confirmingLocalDelete = true; return }
        do {
            deleteConfirm = DeleteConfirm(
                group: named.group.name,
                stillOpen: try GroupSettings.stillOpen(store: store, group: groupID, viewer: named.selfId))
        } catch {
            self.error = String(localized: "Could not read the balances. \(String(describing: error))")
        }
    }

    /// The owner's delete: the cloud copy is erased, and this phone keeps its
    /// own copy read-only like everyone else's.
    private func deleteForEveryone(_ named: NamedGroupWire) {
        busy = true
        Task {
            defer { busy = false }
            do {
                try await GroupDelete.run(
                    call: {
                        _ = try await CloudFunctions.call("deleteGroup", [
                            "groupId": groupID, "groupName": RoleRules.wireName(named.group.name),
                            "byName": RoleRules.wireName(viewerName),
                        ])
                    },
                    mark: {
                        try store.markReadOnly(
                            groupID, LedgerStore.ReadOnly(kind: "deleted", by: viewerName, at: Date(), byYou: true))
                    })
            } catch let e as GroupDelete.MarkFailed {
                self.error = String(localized: "Deleted for everyone, but this phone could not keep its copy. \(String(describing: e.underlying))")
                return
            } catch {
                self.error = RolesCopy.failure(error)
                return
            }
            SyncStatus.shared.changed()
            // U27: the done moment, then back at once (its page closes too): nothing confirmed a delete before.
            DoneCenter.shared.post(DoneMoments.line(DoneCopy.groupDeleted))
            leave()
        }
    }

    /// U27: the settings AND the group's page close at once. In a tab, the tab goes back to its root: the page under
    /// this one may already be gone from the list that pushed it, and then its own dismiss does nothing (the look found
    /// an empty Group settings left on screen). Outside the shell (the push-opened sheet) the page's own close.
    private func leave() {
        if let shellTab {
            NotificationCenter.default.post(name: .skiptiShowTabRoot, object: shellTab)
        } else {
            onGone()
        }
    }

    /// A group only on this phone: forgotten here, and the page under this closes.
    private func deleteHere() {
        do {
            try store.forgetGroup(groupID)
            SyncStatus.shared.changed()
            DoneCenter.shared.post(DoneMoments.line(DoneCopy.groupDeleted))
            leave()
        } catch {
            self.error = String(localized: "Could not delete the group. \(String(describing: error))")
        }
    }
}

/// What the cloud delete's confirm shows, as one value.
private struct DeleteConfirm: Identifiable {
    let id = UUID()
    let group: String
    let stillOpen: String?
}

/// The cloud delete's confirm: what it does and what is still open, in its own
/// sheet (an alert cannot lay the open balances out).
private struct DeleteGroupConfirm: View {
    let group: String
    let stillOpen: String?
    var onConfirm: () -> Void
    var onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(verbatim: RolesCopy.deleteCloudTitle(group)).font(TypeRole.display(20, weight: 700)).foregroundStyle(Tokens.text)
            Text(verbatim: RolesCopy.deleteCloudBody).font(TypeRole.body()).foregroundStyle(Tokens.textDim)
            if let stillOpen {
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: RolesCopy.stillOpen).font(TypeRole.label(14)).foregroundStyle(GroupTone.amber.letter)
                    Text(verbatim: stillOpen).font(TypeRole.body(14)).foregroundStyle(Tokens.text)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(GroupTone.amber.fill, in: ThemeArt.panelShape(AppearanceState.shared.theme, radius: 14, continuous: true))
            }
            Spacer(minLength: 8)
            Button { onConfirm() } label: { Text(verbatim: RolesCopy.deleteForEveryone) }
                .buttonStyle(PrimaryButtonStyle(tint: Tokens.owed))
            Button { onCancel() } label: { Text(verbatim: RolesCopy.cancel) }
                .buttonStyle(SecondaryButtonStyle())
        }
        .padding(20)
        .presentationDetents([.medium, .large])
    }
}

// MARK: - A read-only copy

/// Why the group is a copy, and what that means.
struct ReadOnlyBanner: View {
    let group: String
    let mark: LedgerStore.ReadOnly

    /// The banner's first line. D-229 decision 45: a group where this
    /// account lost its place says why in this one line (no date: it was
    /// never marked, it is read from the records).
    static func title(mark: LedgerStore.ReadOnly, date: String) -> String {
        if mark.kind == LedgerStore.ReadOnly.placeLostKind { return RolesCopy.placeLostBanner }
        if mark.kind == "removed" { return RolesCopy.removedBanner(by: mark.by, date: date) }
        return mark.byYou == true ? RolesCopy.deletedByYou(date: date) : RolesCopy.deletedBanner(by: mark.by, date: date)
    }

    /// The banner's second line; none for a lost place (its one line says it all).
    static func body(mark: LedgerStore.ReadOnly, group: String) -> String? {
        if mark.kind == LedgerStore.ReadOnly.placeLostKind { return nil }
        return mark.kind == "removed" ? RolesCopy.removedBannerBody(group: group) : RolesCopy.deletedBannerBody
    }

    var body: some View {
        let date = Phones.day(mark.at)
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: Self.title(mark: mark, date: date))
                .font(TypeRole.label(15)).foregroundStyle(GroupTone.amber.letter)
            if let line = Self.body(mark: mark, group: group) {
                Text(verbatim: line)
                    .font(TypeRole.body(14)).foregroundStyle(Tokens.text)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(GroupTone.amber.fill, in: ThemeArt.panelShape(AppearanceState.shared.theme, radius: 18, continuous: true))
        .accessibilityElement(children: .combine)
    }
}

/// A copy's two actions: export it, or take it off this phone (not for a lost place: `offersForget`).
struct ReadOnlyActions: View {
    let store: LedgerStore
    let group: NamedGroupWire
    let mark: LedgerStore.ReadOnly
    /// The copy is gone from this phone; the page closes.
    var onForgotten: () -> Void

    /// D-229 decision 52: whether the copy offers "Remove from this phone". Not for a lost place
    /// (decision 45): the account is still a member there, so forgetting would erase this phone's
    /// records still waiting to upload and the group would come straight back, still read-only.
    /// A removed member's or a deleted group's copy keeps it. Export stays for every copy.
    static func offersForget(_ mark: LedgerStore.ReadOnly) -> Bool {
        mark.kind != LedgerStore.ReadOnly.placeLostKind
    }

    @State private var confirming = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 8) {
            NavigationLink {
                ExportScreen(store: store, group: group.group.id)
            } label: { Text(verbatim: RolesCopy.exportThisGroup) }
                .buttonStyle(SecondaryButtonStyle())
            if Self.offersForget(mark) {
                Button { confirming = true } label: { Text(verbatim: RolesCopy.forgetRow) }
                    .buttonStyle(SecondaryButtonStyle(destructive: true))
            }
            if let error { Text(verbatim: error).font(TypeRole.body()).foregroundStyle(Tokens.owed) }
        }
        .alert(RolesCopy.forgetTitle(group.group.name), isPresented: $confirming) {
            Button(RolesCopy.forgetConfirm, role: .destructive) { forget() }
            Button(RolesCopy.cancel, role: .cancel) {}
        } message: {
            Text(verbatim: RolesCopy.forgetBody)
        }
        .hapticWarning(when: confirming)
    }

    private func forget() {
        do {
            try store.forgetGroup(group.group.id)
            SyncStatus.shared.changed()
            onForgotten()
        } catch {
            self.error = String(localized: "Could not remove the group. \(String(describing: error))")
        }
    }
}
