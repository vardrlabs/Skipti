import SwiftUI
import UniformTypeIdentifiers

/// D-166 (SPEC §9.5): import from another app, as a person would use it.
///
/// Until now the planner and the review screen existed only behind proof
/// launch arguments; Balance's empty state said "or import from another app"
/// with nothing to tap. The flow: pick the app the file came from, pick the
/// file, name the new group and its currency, say which person in a
/// Splitwise sheet is you, review, apply.
///
/// §9.5's "nothing exists until Apply" holds: the new group is built with
/// `ffiCreateNamedGroup` but not saved, the plan runs against the ledger
/// plus that group, and only after `ffiApplyImportPlan` accepts the merge
/// are the group and the expenses written, the expenses as sync records in
/// one vault session (`LedgerStore.appendExpenses`). Android's twin is
/// ImportFlow.kt.
enum ImportSource: String, CaseIterable, Identifiable {
    case splitwise
    case budgetBakers

    var id: String { rawValue }

    var title: String {
        switch self {
        case .splitwise: return "Splitwise"
        case .budgetBakers: return "Wallet by BudgetBakers"
        }
    }

    var detail: String {
        switch self {
        case .splitwise:
            return "One group's spreadsheet export, a .csv file. Everyone in it becomes a person in a new group here."
        case .budgetBakers:
            return "A records export, a .csv file. Your spending comes in as your own expenses, in a new group with just you."
        }
    }

    /// A group name when the file name gives none.
    var fallbackGroupName: String {
        switch self {
        case .splitwise: return "Splitwise import"
        case .budgetBakers: return "Wallet"
        }
    }
}

enum ImportCopy {
    static let title = "Import"
    /// D-217: the review page's bar title (Android's "Review").
    static let reviewTitle = "Review"
    static let entry = "Import from another app"
    static let entryDetail = "Splitwise and Wallet by BudgetBakers exports. You see everything before it is added."
    static let intro = "Bring expenses in from another app. You see everything before anything is added."
    static let from = "From"
    static let file = "File"
    static let chooseFile = "Choose file"
    static let chooseAnother = "Choose another file"
    static let newGroup = "New group"
    static let groupName = "Group name"
    static let settlesIn = "Settles in"
    static let settlesDetail = "A group settles in one currency, and it cannot be changed later. Expenses keep their own currency."
    static let people = "People in this file"
    static let whichIsYou = "Which one is you? The others become people in the group."
    static let you = "You"
    static let yourName = "Your name"
    static let review = "Review import"
    static let tooLarge = "That file is too large to import. The limit is 5 MB."
    static let notText = "That file is not a text file. Choose the .csv file the app exported."
    static let unreadable = "That file could not be opened."
    static func done(_ count: Int, group: String) -> String {
        count == 1 ? "1 expense added to \(group)." : "\(count) expenses added to \(group)."
    }
    static let showGroup = "Show the group"
    static func peopleCount(_ n: Int) -> String { n == 1 ? "1 person" : "\(n) people" }

    /// §11.6's register: what went wrong, in the person's terms.
    static func describe(_ error: Error) -> String {
        switch error {
        case ImportSetup.ReadError.tooLarge: return tooLarge
        case ImportSetup.ReadError.notText: return notText
        case PortError.CsvSyntax: return "That file is not a valid CSV file."
        case PortError.SheetStructure(let why):
            return "That file does not look like the export this app expects: \(why)."
        case PortError.UnmappedMembers(let names):
            return "These people in the file have no match: \(names.joined(separator: ", "))."
        case PortError.InvalidLedger(let why): return "The import was refused: \(why)."
        case LedgerStore.GroupStoreError.needsYourName: return "Add your name first."
        case let core as CoreError: return "The group could not be made: \(core)."
        default: return "The import did not work: \(error)."
        }
    }
}

/// The pure steps, tested without a screen.
enum ImportSetup {
    static let maxBytes = 5 * 1024 * 1024

    enum ReadError: Error, Equatable { case tooLarge, notText }

    /// The file's text: at most 5 MB, UTF-8, one leading byte-order mark dropped.
    static func text(from data: Data) throws -> String {
        guard data.count <= maxBytes else { throw ReadError.tooLarge }
        guard var text = String(data: data, encoding: .utf8) else { throw ReadError.notText }
        if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        return text
    }

    static let emptyLedger = LedgerSnapshot(groups: [], expenses: [], settlements: [], redistributions: [], profile: nil)

    /// A stand-in participant id (32 hex) for planning before any real id exists.
    static func placeholderID(_ index: Int) -> String { String(format: "%032x", index + 1) }

    /// The person columns of a Splitwise group sheet. Core refuses a plan with
    /// no bindings and names every column it could not map, so the names come
    /// from core's own reading of the header.
    static func people(csv: String) throws -> [String] {
        let profile = ImportProfile.splitwiseGroup(
            options: SplitwiseOptions(dateFormat: .isoYmd, members: [], group: nil)
        )
        do {
            _ = try ffiPlanImport(csvText: csv, profile: profile, existing: emptyLedger)
            return []
        } catch PortError.UnmappedMembers(let names) {
            return names
        }
    }

    /// The column that is you: the one whose name is yours, ignoring case.
    static func guessYou(people: [String], yourName: String?) -> String? {
        guard let yours = yourName?.trimmingCharacters(in: .whitespaces).lowercased(), !yours.isEmpty else {
            return nil
        }
        return people.first { $0.trimmingCharacters(in: .whitespaces).lowercased() == yours }
    }

    /// "tahoe_trip_2026-09-21_export.csv" → "Tahoe trip": the words of the
    /// file name without its dates, numbers and "export"; nothing left → the
    /// source's name.
    static func groupName(fromFileName name: String, source: ImportSource) -> String {
        let stem = (name as NSString).deletingPathExtension
        let spaced = stem.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ")
        let words = spaced.split(whereSeparator: \.isWhitespace)
            .filter { !$0.allSatisfy(\.isNumber) && $0.lowercased() != "export" }
            .joined(separator: " ")
        guard let first = words.first else { return source.fallbackGroupName }
        return first.uppercased() + words.dropFirst()
    }

    /// The code most rows use; a tie goes to the one seen first.
    static func mostCommon(_ codes: [String]) -> String? {
        var counts: [String: Int] = [:]
        for code in codes { counts[code, default: 0] += 1 }
        return codes.first { counts[$0] == counts.values.max() }
    }

    static func profile(
        _ source: ImportSource, bindings: [MemberBinding], groupID: String?, selfID: String
    ) -> ImportProfile {
        switch source {
        case .splitwise:
            return .splitwiseGroup(options: SplitwiseOptions(dateFormat: .isoYmd, members: bindings, group: groupID))
        case .budgetBakers:
            return .budgetBakersWallet(options: BudgetBakersOptions(selfParticipant: selfID, group: groupID))
        }
    }

    /// The settlement currency to offer: what most of the file's rows use,
    /// read from a plan with stand-in ids; else the default currency.
    static func likelyCurrency(csv: String, source: ImportSource, people: [String], fallback: String) -> String {
        let bindings = people.enumerated().map { MemberBinding(name: $1, participantId: placeholderID($0)) }
        let profile = profile(source, bindings: bindings, groupID: nil, selfID: placeholderID(0))
        guard let plan = try? ffiPlanImport(csvText: csv, profile: profile, existing: emptyLedger) else {
            return fallback
        }
        return mostCommon(plan.expenses.map(\.currencyCode)) ?? fallback
    }

    /// Each sheet column to its member in the new group: you to your id, the
    /// others by name (core made the group from those same names).
    static func bindings(people: [String], you: String, group: NamedGroupWire) -> [MemberBinding] {
        people.compactMap { column in
            if column == you { return MemberBinding(name: column, participantId: group.selfId) }
            let key = column.trimmingCharacters(in: .whitespaces).lowercased()
            return group.members
                .first { $0.id != group.selfId && $0.name.lowercased() == key }
                .map { MemberBinding(name: column, participantId: $0.id) }
        }
    }

    /// The ledger the plan is checked against: what is saved, plus the group
    /// that is not saved yet.
    static func adding(_ group: NamedGroupWire, to ledger: LedgerSnapshot) -> LedgerSnapshot {
        var out = ledger
        out.groups.append(PortGroup(
            id: group.group.id,
            name: group.group.name,
            settlementCurrency: group.group.settlementCurrency,
            members: group.group.members
        ))
        return out
    }

    static func dto(_ e: PortExpense, addedBy: String) -> LedgerStore.ExpenseDTO {
        LedgerStore.ExpenseDTO(
            payer: e.payer,
            paidMinor: e.paidMinor,
            currencyCode: e.currencyCode,
            group: e.group,
            conversion: e.conversion.map {
                LedgerStore.ConversionDTO(
                    convertedMinor: $0.convertedMinor,
                    convertedCurrency: $0.convertedCurrency,
                    rateNum: $0.rateNum,
                    rateDen: $0.rateDen,
                    rateTimestampMs: $0.rateTimestampMs
                )
            },
            shares: e.shares.map { LedgerStore.ExpenseDTO.ShareDTO(id: $0.id, amountMinor: $0.amountMinor) },
            note: e.note,
            timestampMs: e.timestampMs.map { localWallTime($0) },
            category: e.category,
            addedBy: addedBy
        )
    }

    /// The importers read a file's dates as UTC wall times. Kept that way, a
    /// Splitwise row dated 20 Sep would show as 19 Sep anywhere west of UTC.
    /// Saved, the same wall time is placed in this phone's time zone; a
    /// date-only row (UTC midnight) lands at local noon, which stays on the
    /// same UTC day from UTC−12 to UTC+12, so duplicate checks still match.
    static func localWallTime(_ ms: UInt64, in zone: TimeZone = .current) -> UInt64 {
        let dayMs: UInt64 = 86_400_000
        let wall = ms % dayMs == 0 ? ms + dayMs / 2 : ms
        let offset = Int64(zone.secondsFromGMT(for: Date(timeIntervalSince1970: Double(wall) / 1000))) * 1000
        return UInt64(max(0, Int64(wall) - offset))
    }
}

/// One import from choosing to writing, with the screen kept out of it.
struct ImportJob {
    let group: NamedGroupWire
    let plan: ImportPlan
    /// The saved ledger plus the new group: what the plan was checked against.
    let existing: LedgerSnapshot
    /// Your name, when this import is what gives you one.
    let newName: String?
    let selfID: String

    /// Participant id → name in the new group, for the review's wording.
    var names: [String: String] {
        Dictionary(group.members.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
    }

    /// Builds the group (not saved) and plans the file against it.
    static func prepare(
        store: LedgerStore, csv: String, source: ImportSource, groupName: String,
        currency: String, people: [String], you: String?, typedName: String
    ) throws -> ImportJob {
        let me = try store.ensureSelf()
        let chosen = source == .splitwise ? you : nil
        let typed = typedName.trimmingCharacters(in: .whitespaces)
        guard let selfName = me.name ?? chosen ?? (typed.isEmpty ? nil : typed) else {
            throw LedgerStore.GroupStoreError.needsYourName
        }
        let others = source == .splitwise ? people.filter { $0 != chosen } : []
        let group = try ffiCreateNamedGroup(
            name: groupName,
            settlementCurrency: currency,
            selfMember: MemberWire(id: me.id, name: selfName),
            otherNames: others
        )
        let bindings = chosen.map { ImportSetup.bindings(people: people, you: $0, group: group) } ?? []
        let existing = ImportSetup.adding(group, to: try store.snapshot())
        let plan = try ffiPlanImport(
            csvText: csv,
            profile: ImportSetup.profile(source, bindings: bindings, groupID: group.group.id, selfID: me.id),
            existing: existing
        )
        return ImportJob(
            group: group, plan: plan, existing: existing,
            newName: me.name == nil ? selfName : nil, selfID: me.id
        )
    }

    /// After `ffiApplyImportPlan` accepted the merge: your name if this gave
    /// you one, the group, then the expenses in one vault session.
    func commit(_ expenses: [PortExpense], store: LedgerStore) throws {
        if let newName { try store.setYourName(newName) }
        try store.adopt(group)
        _ = try store.appendExpenses(expenses.map { ImportSetup.dto($0, addedBy: selfID) })
    }
}

extension Notification.Name {
    /// D-166: an import finished; the tabs read the ledger again.
    static let skiptiLedgerImported = Notification.Name("skipti.ledger-imported")
    /// U27: a tab goes back to its root (the object is the `HomeTab`), as a re-tap of its bar item does: a deleted
    /// group's settings and page close together.
    static let skiptiShowTabRoot = Notification.Name("skipti.show-tab-root")
}

/// Settings → Your data → Import, and Balance's empty state.
struct ImportFlowScreen: View {
    @State private var source: ImportSource = .splitwise
    @State private var picking = false
    @State private var csv: String?
    @State private var fileName = ""
    @State private var people: [String] = []
    @State private var you: String?
    @State private var groupName = ""
    @State private var currency = DefaultCurrency.current
    @State private var typedName = ""
    @State private var needsName = false
    @State private var error: String?
    @State private var job: ImportJob?
    @State private var reviewing = false

    private var ready: Bool {
        csv != nil && !groupName.trimmingCharacters(in: .whitespaces).isEmpty
            && (source == .budgetBakers || you != nil)
            && (!needsName || source == .splitwise || !typedName.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    var body: some View {
        Form {
            Section {
                Text(verbatim: ImportCopy.intro)
                    .font(TypeRole.body(14))
                    .foregroundStyle(Tokens.textDim)
            }
            Section {
                ForEach(ImportSource.allCases) { option in
                    Button {
                        if source != option { source = option; reset() }
                    } label: {
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: option.title).font(TypeRole.body()).foregroundStyle(Tokens.text)
                                Text(verbatim: option.detail).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                            }
                            Spacer()
                            if option == source {
                                Image(systemName: "checkmark").foregroundStyle(Tokens.accent)
                                    .accessibilityLabel("Selected")
                            }
                        }
                    }
                }
            } header: { header(ImportCopy.from) }

            Section {
                if csv != nil {
                    Text(verbatim: fileName).font(TypeRole.body()).foregroundStyle(Tokens.text)
                    if source == .splitwise {
                        Text(verbatim: ImportCopy.peopleCount(people.count))
                            .font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                    }
                }
                Button(csv == nil ? ImportCopy.chooseFile : ImportCopy.chooseAnother) { picking = true }
                    .buttonStyle(SecondaryButtonStyle())
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    .listRowSeparator(.hidden)
            } header: { header(ImportCopy.file) }

            if csv != nil {
                Section {
                    TextField(ImportCopy.groupName, text: $groupName).font(TypeRole.body())
                    Picker(ImportCopy.settlesIn, selection: $currency) {
                        ForEach(DefaultCurrency.choices(including: [DefaultCurrency.current, currency]), id: \.self) {
                            Text(verbatim: $0).tag($0)
                        }
                    }
                    NavigationLink {
                        CurrencyListScreen(selection: $currency)
                    } label: {
                        Text(verbatim: CurrencyCopy.allCurrencies).font(TypeRole.body()).foregroundStyle(Tokens.accent)
                    }
                    Text(verbatim: ImportCopy.settlesDetail).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                } header: { header(ImportCopy.newGroup) }

                if source == .splitwise {
                    Section {
                        ForEach(people, id: \.self) { person in
                            Button { you = person } label: {
                                HStack {
                                    Text(verbatim: person).font(TypeRole.body()).foregroundStyle(Tokens.text)
                                    Spacer()
                                    if person == you {
                                        Text(verbatim: "You").font(TypeRole.body(14)).foregroundStyle(Tokens.accent)
                                    }
                                }
                            }
                            .accessibilityAddTraits(person == you ? .isSelected : [])
                        }
                        Text(verbatim: ImportCopy.whichIsYou).font(TypeRole.body(14)).foregroundStyle(Tokens.textDim)
                    } header: { header(ImportCopy.people) }
                } else if needsName {
                    Section {
                        TextField(ImportCopy.yourName, text: $typedName)
                            .textContentType(.givenName).font(TypeRole.body())
                    } header: { header(ImportCopy.you) }
                }
            }

            if let error {
                Section {
                    Text(verbatim: error).font(TypeRole.body(14)).foregroundStyle(Tokens.owed)
                }
            }
        }
        .auroraFormBackground() // D-176
        .navigationTitle(Text(verbatim: ImportCopy.title))
        .navigationBarTitleDisplayMode(.inline)
        .formActionBar {
            Button(ImportCopy.review) { prepare() }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!ready)
        }
        .fileImporter(
            isPresented: $picking,
            allowedContentTypes: [.commaSeparatedText, .plainText, .text],
            allowsMultipleSelection: false
        ) { result in
            load(result)
        }
        .navigationDestination(isPresented: $reviewing) {
            if let job {
                ImportConfirmScreen(
                    existing: job.existing,
                    plan: job.plan,
                    commit: ImportConfirmScreen.Commit(
                        groupName: job.group.group.name,
                        names: job.names,
                        save: { try job.commit($0, store: LedgerStore.defaultStore()) },
                        finish: {
                            CloudSync.shared.ledgerChanged()
                            NotificationCenter.default.post(name: .skiptiLedgerImported, object: nil)
                        }
                    )
                )
            }
        }
        .task { needsName = ((try? LedgerStore.defaultStore().ensureSelf())?.name) == nil }
    }

    private func header(_ text: String) -> some View {
        Text(verbatim: text).sectionLabel().foregroundStyle(Tokens.textDim)
    }

    private func reset() {
        csv = nil
        fileName = ""
        people = []
        you = nil
        error = nil
    }

    private func load(_ result: Result<[URL], Error>) {
        error = nil
        guard case .success(let urls) = result, let url = urls.first else {
            if case .failure = result { error = ImportCopy.unreadable }
            return
        }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let text = try ImportSetup.text(from: try Data(contentsOf: url))
            let found = source == .splitwise ? try ImportSetup.people(csv: text) : []
            let me = try? LedgerStore.defaultStore().ensureSelf()
            csv = text
            fileName = url.lastPathComponent
            people = found
            you = ImportSetup.guessYou(people: found, yourName: me?.name)
            groupName = ImportSetup.groupName(fromFileName: url.lastPathComponent, source: source)
            currency = ImportSetup.likelyCurrency(
                csv: text, source: source, people: found, fallback: DefaultCurrency.current
            )
        } catch let failure as ImportSetup.ReadError {
            reset()
            error = ImportCopy.describe(failure)
        } catch let failure {
            reset()
            error = ImportCopy.describe(failure)
        }
    }

    private func prepare() {
        guard let csv else { return }
        error = nil
        do {
            job = try ImportJob.prepare(
                store: LedgerStore.defaultStore(), csv: csv, source: source,
                groupName: groupName, currency: currency, people: people, you: you, typedName: typedName
            )
            reviewing = true
        } catch {
            self.error = ImportCopy.describe(error)
        }
    }
}
