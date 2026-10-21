import SwiftUI

/// Asks for your name once (D-135; founder decision 2026-09-14). The copy
/// states exactly who sees what: you see "Tim (You)", and people you share a
/// group with see "Tim". The name stays in the encrypted vault on this device.
struct YourNameSheet: View {
    var store: LedgerStore
    var onSaved: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var error: String?

    var body: some View {
        // D-225 T6 (Design 12): the section as a label over a one-field list card, the preview line as its caption, on
        // the theme's ground (no system Form), as Android's.
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header("Your name")
                ListCard {
                    FieldRow("Your name", text: $name)
                        .textContentType(.givenName)
                }
                CardCaption("You will see yourself as \"\(preview) (You)\". People you share a group with see \"\(preview)\".")
                if let error {
                    Text(verbatim: error)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.owed)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, GroupSheetLayout.labelTop)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        // D-139: under the form, not in it.
        .formActionBar {
            Button("Save name") { save() }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .navigationTitle("Your name")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }.barTint()
            }
        }
    }

    private var preview: String {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? String(localized: "Your name") : trimmed
    }

    private func save() {
        do {
            try store.setYourName(name)
            CloudSync.shared.ledgerChanged()
            onSaved()
        } catch let e as CoreError {
            error = GroupCopy.message(e)
        } catch {
            self.error = String(localized: "Could not save your name. \(String(describing: error))")
        }
    }

    private func header(_ text: String) -> some View {
        Text(text)
            .sectionLabel()
            .foregroundStyle(Tokens.textDim)
    }
}

/// D-225 T6 (Design 12): where New group's and Your name's first section label sits: as the Form put its first header
/// (Android's `BarDecisions.formTop(headerless = false)`, 64 under the bar's top: the bar's 44 and a 20 gap), less the
/// 9.5 the scroll content already starts below the bar. Group settings' headerless page is the currency list's 34.5,
/// that is this and the 24 pt header.
enum GroupSheetLayout {
    static let labelTop: CGFloat = 10.5
}

/// Creates a group: its name, its settlement currency (fixed for the group's
/// life, SPEC §5.2), and people added by name. Your own name is asked here
/// only while it is still unset.
/// D-172: the people rows of a new group. Each row has a stable id, so a row
/// removed from the middle takes its own name with it (the founder,
/// 2026-09-26: a person added by mistake could not be taken out). Twin of
/// Android's `PeopleDraft`.
struct PeopleDraft: Equatable {
    struct Row: Identifiable, Equatable {
        let id: Int
        var name: String
    }

    var rows: [Row] = [Row(id: 0, name: "")]
    private var nextID = 1

    mutating func add() {
        rows.append(Row(id: nextID, name: ""))
        nextID += 1
    }

    mutating func remove(_ id: Int) {
        rows.removeAll { $0.id == id }
    }

    /// What `createGroup` receives; it ignores blank names.
    var names: [String] { rows.map(\.name) }

    /// The button reads "Add a person" once every row is gone.
    var addLabel: String { rows.isEmpty ? "Add a person" : "Add another person" }
}

struct CreateGroupSheet: View {
    var store: LedgerStore
    var needsYourName: Bool
    var onCreated: (NamedGroupWire) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var groupName = ""
    /// D-165: starts in the default currency (it was always INR).
    @State private var currency = DefaultCurrency.current
    @State private var yourName = ""
    @State private var people = PeopleDraft()
    @State private var error: String?
    /// D-187 (the founder): a picture when making the group.
    @State private var pictureEmoji: String?
    @State private var picturePhoto: Data?
    @State private var choosingPicture = false

    var body: some View {
        // D-225 T6 (Design 12): each section as a label over a list card, its caption under the card, on the theme's
        // ground (no system Form), as Android's. Settles in opens the currency list page (its "Common" section first),
        // as Your data's Default currency does, so the quick menu and "All currencies" went.
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header("Group")
                ListCard {
                    FieldRow("Group name", text: $groupName)
                    HStack(spacing: 12) {
                        PictureTile(
                            id: "new", name: groupName.isEmpty ? "?" : groupName,
                            picture: pictureEmoji == nil && picturePhoto == nil
                                ? nil : PictureDTO(emoji: pictureEmoji, photoHex: picturePhoto.map(HexCodec.encode)),
                            size: 48
                        )
                        Button(GroupPictures.title) { choosingPicture = true }
                            .buttonStyle(SecondaryButtonStyle())
                    }
                    .padding(.vertical, ThemeArt.m("rowPadV"))
                    NavigationLink {
                        CurrencyListScreen(selection: $currency)
                    } label: {
                        ListRow(title: "Settles in", trailing: .value(currency, chevron: true))
                    }
                    .buttonStyle(.plain)
                }
                CardCaption("A group settles in one currency, and it cannot be changed later.")

                if needsYourName {
                    header("You")
                    ListCard {
                        FieldRow("Your name", text: $yourName)
                            .textContentType(.givenName)
                    }
                    CardCaption("You see yourself as \"Name (You)\". Others in the group see only the name.")
                }

                header("People")
                // Every row gone leaves no empty card: the label, then "Add a person".
                if !people.rows.isEmpty {
                    ListCard {
                        ForEach(Array($people.rows.enumerated()), id: \.element.id) { index, $row in
                            FieldRow("Name", text: $row.name, spoken: "Person \(index + 1)") {
                                // D-225 (Design 8): Lucide's x at `clearGlyph` in textFaint, in a 48 target; only the
                                // glyph removes.
                                Button {
                                    withAnimation { people.remove(row.id) }
                                } label: {
                                    LucideIcon(glyph: .x, size: ThemeArt.m("clearGlyph"))
                                        .foregroundStyle(Tokens.textFaint)
                                        .frame(width: ThemeArt.m("touchTarget"), height: ThemeArt.m("touchTarget"))
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(row.name.isEmpty ? "Remove person \(index + 1)" : "Remove \(row.name)")
                            }
                        }
                    }
                }
                Button(people.addLabel) { withAnimation { people.add() } }
                    .buttonStyle(SecondaryButtonStyle())
                CardCaption("Add people by name. They do not need SKIPTI; linking a name to their phone comes later.")

                if let error {
                    Text(verbatim: error)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.owed)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, GroupSheetLayout.labelTop)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        // D-139: under the form, not in it.
        .formActionBar {
            Button("Create group") { create() }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!canCreate)
        }
        .navigationTitle("New group")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }.barTint()
            }
        }
        .sheet(isPresented: $choosingPicture) {
            GroupPictureSheet(
                onChosen: { emoji, jpeg in pictureEmoji = emoji; picturePhoto = jpeg },
                onError: { error = $0 }
            )
        }
        // D-224 fix wave (F1): the sheet has finished closing, so the shell shows: the first group's check begins now.
        // Nothing waits (a sheet closed without a create, or a later group): nothing happens.
        .onDisappear { DoneCenter.shared.begin() }
    }

    private var canCreate: Bool {
        !groupName.trimmingCharacters(in: .whitespaces).isEmpty
            && (!needsYourName || !yourName.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    private func create() {
        error = nil
        do {
            let created = try CreateGroup.create(
                store: store, yourName: needsYourName ? yourName : nil, name: groupName,
                currency: currency, people: people.names, emoji: pictureEmoji, photo: picturePhoto
            ) { CloudSync.shared.ledgerChanged() }
            GroupPictureStore.shared.load(store)
            // D-224 (Design 7): posted once here, not in the callers. The named groups after the create: the first one is
            // the big moment; a read that fails counts 0, the toast (never big without proof). Fix wave (F1): held, so the
            // first group's check starts drawing once this sheet has gone (`.onDisappear` below), not under it.
            let namedCount = (try? store.namedGroups().count) ?? 0
            DoneCenter.shared.post(DoneMoments.groupCreated(
                namedCountAfter: namedCount, name: created.group.name,
                detailParts: GroupsCopy.detailParts(people: created.members.count, currency: created.group.settlementCurrency)),
                held: true)
            onCreated(created)
        } catch let e as CoreError {
            error = GroupCopy.message(e)
        } catch {
            self.error = String(localized: "Could not create the group. \(String(describing: error))")
        }
    }

    private func header(_ text: String) -> some View {
        Text(text)
            .sectionLabel()
            .foregroundStyle(Tokens.textDim)
    }
}

/// D-211 (the look found it on devices): creating a group is a set of group
/// operations, and they have to reach the cloud at once, not at the next
/// unrelated write. `create` saves the name (when asked), the group and its
/// picture, then calls `synced` once (the sync's `ledgerChanged`); nothing is
/// called when anything is refused. Android's twin is `CreateGroup` in
/// GroupScreens.kt.
enum CreateGroup {
    static func create(
        store: LedgerStore, yourName: String?, name: String, currency: String, people: [String],
        emoji: String?, photo: Data?, synced: () -> Void
    ) throws -> NamedGroupWire {
        if let yourName { try store.setYourName(yourName) }
        let created = try store.createGroup(name: name, settlementCurrency: currency, people: people)
        if emoji != nil || photo != nil {
            try store.setGroupPicture(groupID: created.group.id, emoji: emoji, jpeg: photo)
        }
        synced()
        return created
    }
}

/// Plain-language copy for core's group and name errors (SPEC §11.6 register).
enum GroupCopy {
    /// D-174: "1 person", never "1 people" (the founder's screenshot).
    static func people(_ count: Int) -> String {
        count == 1 ? "1 person" : "\(count) people"
    }

    static func message(_ error: CoreError) -> String {
        switch error {
        case .InvalidInput(let detail):
            if detail.contains("already has that name") {
                return String(localized: "Someone in this group already has that name. Add a surname or initial.")
            }
            if detail.contains("longer than") {
                return String(localized: "That name is too long. Use 64 characters or fewer.")
            }
            if detail.contains("member needs a name") {
                return String(localized: "Every person needs a name.")
            }
            if detail.contains("group needs a name") {
                return String(localized: "The group needs a name.")
            }
            if detail.contains("control character") {
                return String(localized: "Names can't include line breaks or tabs.")
            }
            return detail
        case .UnknownCurrency:
            return String(localized: "That currency is not supported.")
        default:
            return String(describing: error)
        }
    }
}
