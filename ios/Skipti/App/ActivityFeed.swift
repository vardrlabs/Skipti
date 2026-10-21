import Foundation
import SwiftUI

/// D-189 (the founder, 2026-09-30: "a notification system for important stuff"
/// and "that bell icon ... inside the app"): what other people added, changed
/// or deleted in your groups, worked out on this phone from the records it
/// already holds, newest first. Nothing is sent anywhere to make it. Each
/// expense appears once, for its latest change, and only when someone else
/// made that change. Android's twin is ActivityFeed.kt.
enum ActivityFeed {
    enum Kind: String { case added, changed, deleted }

    struct Item: Equatable, Identifiable {
        let expenseID: String
        let group: String
        let kind: Kind
        let by: String
        let atMs: UInt64
        let title: String
        let amountMinor: Int64
        let currency: String
        var id: String { expenseID }
    }

    static let limit = 50
    static let title = "Activity"
    static let empty = "Nothing new yet. What others add, change or delete in your groups shows here."
    /// D-196: the list on a group's page.
    static let groupTitle = "Recent activity"
    static let groupEmpty = "Nothing new yet. What others add, change or delete in this group shows here."
    static let someone = "Someone"
    static let anExpense = "an expense"

    /// D-234 (L8): who an expense row names: the group's current member, else the group's history name (a removed person
    /// keeps theirs, `historyNames`), else "Someone". Activity, a group's Recent activity and Home's Lately all say it so.
    /// Android's twin is `ActivityFeed.who`.
    static func who(_ item: Item, groups: [String: NamedGroupWire], names: [String: [String: String]]) -> String {
        groups[item.group]?.members.first { $0.id == item.by }?.name ?? names[item.group]?[item.by] ?? someone
    }

    /// The latest change to each group expense, when someone other than
    /// `me` (your id and its aliases) made it; D-196: in one group only, when
    /// `only` names it.
    static func items(_ expenses: [ProjectedExpenseOut], me: Set<String>, only: String? = nil) -> [Item] {
        var out: [Item] = []
        for e in expenses {
            guard let group = e.expense.group, only == nil || only == group else { continue }
            let kind: Kind, by: String, at: UInt64
            if let edited = e.lastEditedAtMs, let editor = e.lastEditedBy {
                (kind, by, at) = (e.deleted ? .deleted : .changed, editor, edited)
            } else if let author = e.addedBy {
                (kind, by, at) = (.added, author, e.addedAtMs)
            } else {
                continue
            }
            if me.contains(by) { continue }
            let note = e.note?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            out.append(Item(
                expenseID: e.id, group: group, kind: kind, by: by, atMs: at,
                title: note.isEmpty ? anExpense : note,
                amountMinor: e.expense.paidMinor, currency: e.expense.currencyCode
            ))
        }
        return Array(out.sorted { ($0.atMs, $0.expenseID) > ($1.atMs, $1.expenseID) }.prefix(limit))
    }

    /// D-210: a removed person's balance moved onto others, as the group's
    /// history shows it (one line per redistribution record).
    struct Moved: Equatable, Identifiable {
        let id: String
        let group: String
        let atMs: UInt64
        let from: String
        let currency: String
        /// What moved, as a magnitude: the shares' sum.
        let amountMinor: Int64
        /// Who took it, by participant id, in the record's order.
        let to: [String]
    }

    /// The group's redistribution records (type 6) as history lines; a record
    /// core cannot read is skipped. D-210: in one group only when `only` names it.
    static func moved(_ records: [RecordWire], only: String? = nil) -> [Moved] {
        records.compactMap { record in
            guard record.recordType == LedgerStore.Sync.redistributionType,
                  let group = record.groupId, only == nil || only == group,
                  let r = try? ffiDecodeRedistribution(blob: record.blob) else { return nil }
            return Moved(
                id: "moved-" + CloudRecords.documentId(record), group: group, atMs: record.hlcMillis, from: r.from,
                currency: r.currencyCode, amountMinor: abs(r.shares.reduce(0) { $0 + $1.amountMinor }),
                to: r.shares.map(\.id))
        }
    }

    /// "Sam's ₹1,000.00 moved to Prithvi, Rahul and Asha", names from the
    /// group's history (`LedgerStore.historyNames`: removed people too); nil
    /// when core cannot format the amount (the line is not shown).
    static func movedLine(_ m: Moved, names: [String: String]) -> String? {
        guard let amount = RolesMoney.format(m.amountMinor, m.currency) else { return nil }
        return RolesCopy.moved(from: names[m.from] ?? LedgerStore.unknownName, amount: amount, to: m.to.compactMap { names[$0] })
    }

    /// How many are newer than what was on screen when you last looked.
    static func unread(_ items: [Item], seenUpTo: UInt64) -> Int {
        items.filter { $0.atMs > seenUpTo }.count
    }

    static func line(_ kind: Kind, who: String, title: String) -> String {
        switch kind {
        case .added: return "\(who) added \(title)"
        case .changed: return "\(who) changed \(title)"
        case .deleted: return "\(who) deleted \(title)"
        }
    }

    static func spokenBell(_ unread: Int) -> String {
        unread == 0 ? title : "\(title), \(unread) new"
    }

    /// What happened, newest first: expenses others changed, balances moved. D-223 lifted it out of ActivityScreen so
    /// Home's Lately reads the same list (Android's twin: `ActivityFeed.Entry`).
    enum Entry: Equatable, Identifiable {
        case expense(Item), moved(Moved)
        var id: String {
            switch self { case .expense(let i): return i.id; case .moved(let m): return m.id }
        }
        var atMs: UInt64 {
            switch self { case .expense(let i): return i.atMs; case .moved(let m): return m.atMs }
        }
    }

    /// Newest first, ties by id descending, at most `limit`.
    static func entries(_ items: [Item], _ moved: [Moved]) -> [Entry] {
        Array((items.map(Entry.expense) + moved.map(Entry.moved)).sorted { ($0.atMs, $0.id) > ($1.atMs, $1.id) }.prefix(limit))
    }

    /// D-223: one read for Activity, a group's Recent activity and Home's Lately: the feed, the moved balances that can
    /// be said (with their words, by id) and the groups by id. It writes nothing: marking the feed seen stays with
    /// ActivityScreen.
    struct Loaded {
        var items: [Item] = []
        var moved: [Moved] = []
        var movedLines: [String: String] = [:]
        var groups: [String: NamedGroupWire] = [:]
        /// Each moved balance's group history names (removed people too), by group: who a moved row is about.
        var names: [String: [String: String]] = [:]
        var entries: [Entry] { ActivityFeed.entries(items, moved) }
    }

    /// Views-extra 2 (CLAUDE.md 10.1): Home is the launch path, so a vault read that fails throws rather than reading
    /// as an empty feed. A redistribution record core cannot decode is still skipped (`moved`), as before.
    static func load(_ store: LedgerStore, group: String?) throws -> Loaded {
        var out = Loaded()
        out.items = try store.activity(group: group)
        // D-234 (L8): every group the feed names an author in, not only those with a moved balance.
        for g in Set(out.items.map(\.group)) where out.names[g] == nil { out.names[g] = try store.historyNames(group: g) }
        let all = moved(try store.allRecords(), only: group)
        for m in all {
            if out.names[m.group] == nil { out.names[m.group] = try store.historyNames(group: m.group) }
            out.movedLines[m.id] = movedLine(m, names: out.names[m.group] ?? [:])
        }
        out.moved = all.filter { out.movedLines[$0.id] != nil }
        out.groups = Dictionary(try store.namedGroups().map { ($0.group.id, $0) }, uniquingKeysWith: { a, _ in a })
        return out
    }
}

extension LedgerStore {
    static let activitySeenKey = "activity/seen-up-to"

    /// D-189: the newest change that was on screen when Activity was last
    /// opened, in the records' own time (so a phone's clock cannot hide
    /// anything). This phone's own, never synced.
    func activitySeenUpTo() -> UInt64 {
        guard let vault = try? VaultGate.open(path: path, keystore: KeychainKeystore()) else { return 0 }
        defer { try? vault.closeVault() }
        guard let data = try? vault.kvGet(key: Self.activitySeenKey),
              let text = String(data: data, encoding: .utf8) else { return 0 }
        return UInt64(text) ?? 0
    }

    func setActivitySeenUpTo(_ ms: UInt64) throws {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        try vault.kvSet(key: Self.activitySeenKey, value: Data(String(ms).utf8))
    }

    /// D-189: the feed for this phone: your id and its aliases are "me".
    /// D-196: one group's, for its page.
    func activity(group: String? = nil) throws -> [ActivityFeed.Item] {
        let me = try ensureSelf()
        return ActivityFeed.items(try projectedExpenses().expenses, me: Set([me.id] + (me.aliases ?? [])), only: group)
    }
}

/// D-189: the list the bell opens. Opening it marks everything on it seen.
/// D-196: the bell went with the relay (D-192); a group's page opens it for
/// that group as "Recent activity".
struct ActivityScreen: View {
    var store: LedgerStore
    var group: String?
    /// D-223: the shared read (`ActivityFeed.load`): the feed, the moved balances and their words, the groups.
    @State private var feed = ActivityFeed.Loaded()
    @State private var loaded = false
    /// Views-extra 2: a read that failed says so, never "nothing new".
    @State private var failed: String?

    /// What happened, newest first (`ActivityFeed.entries`, D-223).
    private var entries: [ActivityFeed.Entry] { feed.entries }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let failed {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: LedgerCopy.loadError).font(TypeRole.body()).foregroundStyle(Tokens.owed)
                        Text(verbatim: failed).font(TypeRole.body(TypeScale.detail)).foregroundStyle(Tokens.textDim)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .glassCard()
                } else if loaded && entries.isEmpty {
                    Text(verbatim: group == nil ? ActivityFeed.empty : ActivityFeed.groupEmpty)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.textDim)
                        .glassCard()
                }
                if !entries.isEmpty {
                    VStack(spacing: 0) {
                        ForEach(Array(entries.enumerated()), id: \.element.id) { i, entry in
                            if i > 0 { Divider().overlay(Tokens.hairline) }
                            switch entry {
                            case .expense(let item):
                                if item.kind != .deleted, let named = feed.groups[item.group] {
                                    NavigationLink {
                                        ExpenseDetailView(store: store, expenseID: item.expenseID, named: named)
                                    } label: { row(item) }
                                    .buttonStyle(.plain)
                                } else {
                                    row(item)
                                }
                            case .moved(let m):
                                movedRow(m)
                            }
                        }
                    }
                    .glassCard(padding: 14)
                }
            }
            .padding(16)
        }
        .auroraBackground()
        .navigationTitle(group == nil ? ActivityFeed.title : ActivityFeed.groupTitle)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: SyncStatus.shared.revision) { load() }
    }

    private func row(_ item: ActivityFeed.Item) -> some View {
        let named = feed.groups[item.group]
        let who = ActivityFeed.who(item, groups: feed.groups, names: feed.names)
        // D-223 (Design 6): the words both shells say the same way, the day and month on the AppLocale axis.
        let when = RelativeWhen.words(atMs: item.atMs, nowMs: RelativeWhen.nowMs)
        let amount = RolesMoney.format(item.amountMinor, item.currency) ?? ""
        return HStack(alignment: .top, spacing: 12) {
            if let named { GroupTile(group: named.group, size: 36) }
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: ActivityFeed.line(item.kind, who: who, title: item.title))
                    .font(TypeRole.label(15))
                    .foregroundStyle(item.kind == .deleted ? Tokens.textDim : Tokens.text)
                Separator.text([named?.group.name, amount, when].compactMap { $0 }.filter { !$0.isEmpty }, size: 13)
                    .font(TypeRole.body(13))
                    .foregroundStyle(Tokens.textDim)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func movedRow(_ m: ActivityFeed.Moved) -> some View {
        let named = feed.groups[m.group]
        let when = RelativeWhen.words(atMs: m.atMs, nowMs: RelativeWhen.nowMs)
        return HStack(alignment: .top, spacing: 12) {
            if let named { GroupTile(group: named.group, size: 36) }
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: feed.movedLines[m.id] ?? "")
                    .font(TypeRole.label(15)).foregroundStyle(Tokens.text)
                Separator.text([named?.group.name, when].compactMap { $0 }.filter { !$0.isEmpty }, size: 13)
                    .font(TypeRole.body(13)).foregroundStyle(Tokens.textDim)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
    }

    private func load() {
        do {
            feed = try ActivityFeed.load(store, group: group)
            failed = nil
        } catch {
            // The last good list stays under the line that says the read failed.
            failed = String(describing: error)
            return
        }
        loaded = true
        if group == nil, let newest = feed.items.first?.atMs, newest > store.activitySeenUpTo() {
            try? store.setActivitySeenUpTo(newest)
        }
    }
}
