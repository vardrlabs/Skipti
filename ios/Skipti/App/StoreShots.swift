#if DEBUG
import SwiftUI

// D-251 (U32): `--store-shots`, the store pages' seed. Debug builds only: this whole file is compiled out of Release, so
// a Release build has no such argument (StoreShotsTests holds every reference inside `#if DEBUG`). A look's tool, not a
// user flow, and for a THROWAWAY device only (`.drafts/u32/plan.md`, "Restoring after the store seed"): the launch sets
// the old vault aside, writes a fresh one through the ordinary store paths (core's checks run on every expense and
// payment), names no account and starts nothing in the cloud (it is a proof launch: `SkiptiApp.verificationArg` lists
// it, so the gate, the lock, the curtain, the welcome and the sync never run), and the app lock stays off for that
// launch only (nothing is written to its setting). Review fix wave (I1): a vault is deleted only when it carries the
// seed's own flag (`seededKey`); a vault with an account, or any other vault while a set-aside one exists, is refused
// with words on the screen and nothing changes, the theme and mode included. The theme and mode a run writes stay
// written (they are the phone's settings, as Appearance writes them), and D-232's migrated mark goes. Android's twin is
// StoreShots.kt; its StoreShotsTest compares the seed table below with its own, row by row.

/// One group of the seed: you ("Sam") plus `people`. D-253: `id` is the group's FIXED id (32 hex), the same on both
/// shells, so its tile takes the same tone on every capture (`GroupTone.of` reads the id's first byte; a created group's
/// id is random, and D-251's two captures drew two groups in different tones on the two phones).
struct StoreShotsGroup: Equatable {
    let name: String
    let currency: String
    let people: [String]
    let id: String
}

/// One expense of the seed. `ledger` is a group's name or "Just me"; `category` is "Parent/Leaf" in core's default
/// tree; `among` is who shares it (empty: everyone in the ledger), split equally, a remainder to the first named.
struct StoreShotsExpense: Equatable {
    let ledger: String
    let payer: String
    let minor: Int64
    let note: String
    let category: String
    let hoursAgo: Int
    let among: [String]
}

/// One payment of the seed, in a group: `from` paid `to`.
struct StoreShotsSettlement: Equatable {
    let ledger: String
    let from: String
    let to: String
    let minor: Int64
    let hoursAgo: Int
}

enum StoreShots {
    static let flag = "--store-shots"
    static let themeFlag = "--store-theme"
    static let modeFlag = "--store-mode"
    /// Decision 173: the sample "you".
    static let you = "Sam"
    static let justMe = "Just me"
    static let justMeCurrency = "USD"
    /// A row at least this old keeps its own date (Hamptons weekend's first expense, a year back: its milestone is
    /// due); every other row is placed inside the current month, so every shot reads "this month".
    static let keptFromHours = 8_760
    /// The vault an earlier launch left is moved here once (never deleted); a later launch's seeded vault is replaced.
    static let asideSuffix = ".before-store-shots"
    /// Written into a vault first of all by `seed`: the one mark that lets a later run delete it.
    static let seededKey = "storeshots/seeded"

    // MARK: - The seed table (Android's StoreShots.kt holds the same rows, in the same order)

    static let groups: [StoreShotsGroup] = [
        .init(name: "Hamptons weekend", currency: "USD", people: ["Emma", "Jake", "Olivia"], id: "02bce5d13d3c6bbabded0d02baa8f38f"),
        .init(name: "Apartment 4B", currency: "USD", people: ["Emma"], id: "05812af319f50da19936d26e14706717"),
        .init(name: "Brooklyn dinners", currency: "USD", people: ["Ben"], id: "018c2fba8e39317ca229dc1fd08e9854"),
    ]

    static let expenses: [StoreShotsExpense] = [
        .init(ledger: "Hamptons weekend", payer: "Sam", minor: 40_000, note: "Beach house deposit", category: "Stay/Rentals", hoursAgo: 8_784, among: []),
        .init(ledger: "Hamptons weekend", payer: "Emma", minor: 112_000, note: "Train tickets", category: "Travel/Flights", hoursAgo: 236, among: []),
        .init(ledger: "Hamptons weekend", payer: "Sam", minor: 96_000, note: "Beach house, three nights", category: "Stay/Rentals", hoursAgo: 212, among: []),
        .init(ledger: "Hamptons weekend", payer: "Jake", minor: 8_000, note: "Bike rentals", category: "Travel/Rentals", hoursAgo: 190, among: []),
        .init(ledger: "Hamptons weekend", payer: "Olivia", minor: 6_200, note: "Lobster rolls", category: "Food & Drink/Restaurants", hoursAgo: 166, among: []),
        .init(ledger: "Hamptons weekend", payer: "Jake", minor: 4_500, note: "Gas", category: "Travel/Fuel", hoursAgo: 160, among: []),
        .init(ledger: "Hamptons weekend", payer: "Sam", minor: 18_000, note: "Kayak tour", category: "Entertainment/Events", hoursAgo: 140, among: ["Sam", "Jake", "Olivia"]),
        .init(ledger: "Hamptons weekend", payer: "Emma", minor: 5_400, note: "Groceries for the house", category: "Food & Drink/Groceries", hoursAgo: 118, among: []),
        .init(ledger: "Hamptons weekend", payer: "Olivia", minor: 9_600, note: "Beach club lunch", category: "Food & Drink/Restaurants", hoursAgo: 94, among: []),
        .init(ledger: "Hamptons weekend", payer: "Emma", minor: 2_400, note: "Coffee and pastries", category: "Food & Drink/Cafés", hoursAgo: 70, among: []),
        .init(ledger: "Hamptons weekend", payer: "Emma", minor: 21_000, note: "Seafood dinner", category: "Food & Drink/Restaurants", hoursAgo: 30, among: []),
        .init(ledger: "Hamptons weekend", payer: "Jake", minor: 5_800, note: "Ride to the station", category: "Travel/Taxis", hoursAgo: 6, among: []),
        .init(ledger: "Apartment 4B", payer: "Sam", minor: 380_000, note: "Rent", category: "Bills & Utilities/Rent", hoursAgo: 250, among: []),
        .init(ledger: "Apartment 4B", payer: "Sam", minor: 14_200, note: "Electricity", category: "Bills & Utilities/Electricity", hoursAgo: 100, among: []),
        .init(ledger: "Apartment 4B", payer: "Emma", minor: 7_000, note: "Internet", category: "Bills & Utilities/Internet", hoursAgo: 75, among: []),
        .init(ledger: "Apartment 4B", payer: "Sam", minor: 18_600, note: "Groceries", category: "Food & Drink/Groceries", hoursAgo: 44, among: []),
        .init(ledger: "Brooklyn dinners", payer: "Ben", minor: 6_400, note: "Pizza night", category: "Food & Drink/Restaurants", hoursAgo: 72, among: []),
        .init(ledger: "Brooklyn dinners", payer: "Sam", minor: 14_800, note: "Dinner in Williamsburg", category: "Food & Drink/Restaurants", hoursAgo: 50, among: []),
        .init(ledger: "Brooklyn dinners", payer: "Ben", minor: 4_600, note: "Tacos", category: "Food & Drink/Restaurants", hoursAgo: 28, among: []),
        .init(ledger: "Just me", payer: "Sam", minor: 14_200, note: "Weekly groceries", category: "Food & Drink/Groceries", hoursAgo: 5, among: []),
        .init(ledger: "Just me", payer: "Sam", minor: 650, note: "Cold brew", category: "Food & Drink/Cafés", hoursAgo: 26, among: []),
        .init(ledger: "Just me", payer: "Sam", minor: 2_800, note: "Cab home", category: "Travel/Taxis", hoursAgo: 52, among: []),
        .init(ledger: "Just me", payer: "Sam", minor: 14_000, note: "Running shoes", category: "Shopping/Clothing", hoursAgo: 120, among: []),
        .init(ledger: "Just me", payer: "Sam", minor: 12_000, note: "Concert tickets", category: "Entertainment/Events", hoursAgo: 168, among: []),
        .init(ledger: "Just me", payer: "Sam", minor: 8_900, note: "Gym membership", category: "Health/Fitness", hoursAgo: 200, among: []),
    ]

    static let settlements: [StoreShotsSettlement] = [
        .init(ledger: "Hamptons weekend", from: "Olivia", to: "Sam", minor: 15_000, hoursAgo: 20),
    ]

    // MARK: - Arguments

    static func active(_ args: [String]) -> Bool { args.contains(flag) }

    private static func value(_ args: [String], after flag: String) -> String? {
        guard let i = args.firstIndex(of: flag), args.indices.contains(i + 1) else { return nil }
        return args[i + 1]
    }

    /// `--store-theme norse|aurora|vardr`; anything else, or none, is nil (the stored theme is left alone).
    static func theme(_ args: [String]) -> AppTheme? { value(args, after: themeFlag).flatMap(AppTheme.init(rawValue:)) }

    /// `--store-mode light|dark`; with a theme and no mode, dark (a shot never follows the phone).
    static func mode(_ args: [String]) -> AppearanceMode? {
        if let raw = value(args, after: modeFlag), let mode = AppearanceMode(rawValue: raw), mode != .system { return mode }
        return theme(args) == nil ? nil : .dark
    }

    /// Before the first frame (`SkiptiApp.init`): the theme and mode the shot asks for, through the settings funnel's
    /// local write (the one an arrival from the account uses: the value on this phone, never a record), so You >
    /// Appearance shows the chosen theme. A mode named is a choice: D-232's migrated mark goes. A run the seed would
    /// refuse (`check`, which reads an existing vault after D-232's settle and never creates one) writes nothing here.
    static func applyAppearance(_ args: [String], defaults: UserDefaults = .standard,
                                store: @autoclosure () -> LedgerStore = LedgerStore.defaultStore()) {
        guard active(args), (try? check(store())) != nil else { return }
        if let theme = theme(args) { SettingsSync.applyFromAccount(AppTheme.storageKey, theme.rawValue, defaults: defaults) }
        if let mode = mode(args) {
            SettingsSync.applyFromAccount(AppearanceMode.storageKey, mode.rawValue, defaults: defaults)
            defaults.removeObject(forKey: AppearanceMode.migratedKey)
        }
    }

    // MARK: - Times

    /// Each row's time, in the order given (`hoursAgo` of every expense, then every payment). A row from `keptFromHours`
    /// on keeps `now - hoursAgo`; every other one is `now - hoursAgo`, but never before the month's first instant (plus
    /// a minute per row, oldest first) and never at or after now, so the rows stay in their order inside this month.
    static func times(hoursAgo: [Int], nowMs: UInt64, monthStartMs: UInt64) -> [UInt64] {
        let hour: UInt64 = 3_600_000
        let inMonth = hoursAgo.indices.filter { hoursAgo[$0] < keptFromHours }.sorted { hoursAgo[$0] > hoursAgo[$1] }
        var out = hoursAgo.map { nowMs - min(nowMs, UInt64($0) * hour) }
        for (rank, index) in inMonth.enumerated() {
            let floor = monthStartMs + UInt64(rank + 1) * 60_000
            let ceiling = nowMs - min(nowMs, UInt64(inMonth.count - rank) * 1_000)
            out[index] = min(max(out[index], floor), ceiling)
        }
        return out
    }

    /// An equal split of `minor` among `people`, the remainder a unit each to the first named.
    static func split(_ minor: Int64, among people: [String]) -> [(name: String, minor: Int64)] {
        let n = Int64(people.count)
        let each = minor / n, rest = Int(minor % n)
        return people.enumerated().map { ($0.element, each + ($0.offset < rest ? 1 : 0)) }
    }

    // MARK: - The seed

    enum SeedError: Error, Equatable {
        /// The seed writes only a vault that does not exist yet.
        case notFresh
        case unknown(String)
        case launch(String)
    }

    /// Why a run refuses. Nothing has changed when one is thrown; the words are the proof screen's.
    enum Refusal: Error, Equatable {
        case account
        case asideExists

        var words: String {
            switch self {
            case .account: return "This phone has an account. The store seed runs only on a throwaway device."
            case .asideExists: return "A set-aside ledger already exists. Restore or remove it first."
            }
        }
    }

    /// What is at the vault path now.
    enum Found: Equatable {
        case nothing
        /// An earlier run's seed: it carries `seededKey`.
        case seed
        /// Anything else: a person's ledger, or a vault that cannot be read (never deleted, only set aside).
        case ledger
    }

    /// What a vault says: an owner, the seed's flag. A vault that cannot be read says neither.
    struct Marks: Equatable {
        let owner: Bool
        let seeded: Bool
    }

    static func marks(_ store: LedgerStore) -> Marks {
        Marks(owner: (try? Account.readOwner(store)) != nil, seeded: (try? store.kvFlag(key: seededKey)) ?? false)
    }

    /// Reads, never writes: the vault is opened only when its main file exists (an open creates one). A vault with an
    /// owner is refused; one that cannot be read counts as a ledger (set aside, never deleted); a ledger is refused while
    /// any set-aside file exists, so every destination of the move is free before anything moves.
    static func check(_ store: LedgerStore, fileManager: FileManager = .default,
                      read: (LedgerStore) -> Marks = StoreShots.marks) throws -> Found {
        let path = store.path
        guard LedgerErase.files(path).contains(where: { fileManager.fileExists(atPath: $0) }) else { return .nothing }
        var seeded = false
        if fileManager.fileExists(atPath: path) {
            let marks = read(store)
            if marks.owner { throw Refusal.account }
            seeded = marks.seeded
        }
        if seeded { return .seed }
        if LedgerErase.files(path + asideSuffix).contains(where: { fileManager.fileExists(atPath: $0) }) {
            throw Refusal.asideExists
        }
        return .ledger
    }

    /// Clears the vault path for the seed. An earlier seed (and only a vault carrying `seededKey`) is deleted; any other
    /// vault is moved aside with its write-ahead files, the main file first, all or nothing: a move that fails puts back
    /// every file already moved. The key stays, so the aside still opens when it is moved back.
    static func setAside(_ store: LedgerStore, fileManager: FileManager = .default,
                         read: (LedgerStore) -> Marks = StoreShots.marks,
                         move: (String, String) throws -> Void = { try FileManager.default.moveItem(atPath: $0, toPath: $1) })
        throws {
        let path = store.path
        switch try check(store, fileManager: fileManager, read: read) {
        case .nothing:
            return
        case .seed:
            for file in LedgerErase.files(path) where fileManager.fileExists(atPath: file) {
                try fileManager.removeItem(atPath: file)
            }
        case .ledger:
            let pairs = ["", "-wal", "-shm", "-journal"].map { (path + $0, path + asideSuffix + $0) }
                .filter { fileManager.fileExists(atPath: $0.0) }
            var moved: [(String, String)] = []
            do {
                for pair in pairs {
                    try move(pair.0, pair.1)
                    moved.append(pair)
                }
            } catch {
                for pair in moved.reversed() { try? move(pair.1, pair.0) }
                throw error
            }
        }
    }

    /// Writes the seed into a vault that does not exist yet, through the ordinary store paths, oldest first (each
    /// record's clock is its row's time, so Activity and Lately read "6h ago", not "now"); then marks the phone launched
    /// with the welcome not pending.
    static func seed(_ store: LedgerStore, nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000),
                     timeZone: TimeZone = .current) throws {
        guard !FileManager.default.fileExists(atPath: store.path) else { throw SeedError.notFresh }
        // First of all, so even a seed that fails halfway is known as a seed by the next run.
        try store.setKvFlag(key: seededKey)
        let monthStart = SpendingMonth.containing(ms: nowMs, timeZone: timeZone).bounds(timeZone: timeZone).fromMs
        let at = times(hoursAgo: expenses.map(\.hoursAgo) + settlements.map(\.hoursAgo), nowMs: nowMs,
                       monthStartMs: monthStart)
        let start = (at.min() ?? nowMs) - 3_600_000
        try store.setYourName(you, nowMs: start)
        for (i, g) in groups.enumerated() {
            // D-253: built as `createGroup` builds a group (core's `ffiCreateNamedGroup`), then kept under the seed's
            // fixed id, so the tile's tone is the same on both phones (Android's seed does the same).
            let madeAt = start + UInt64(i + 1) * 1_000
            let me = try store.ensureSelf(nowMs: madeAt)
            var built = try ffiCreateNamedGroup(name: g.name, settlementCurrency: g.currency,
                                                selfMember: MemberWire(id: me.id, name: you), otherNames: g.people)
            built.group.id = g.id
            try store.adopt(built, nowMs: madeAt)
        }
        let named = try store.namedGroups()
        let me = try store.ensureSelf().id
        let tree = ffiCategoryTreeDefault()
        func leaf(_ path: String) throws -> String {
            let parts = path.split(separator: "/", maxSplits: 1).map(String.init)
            guard parts.count == 2,
                  let parent = tree.categories.first(where: { $0.parent == nil && $0.name == parts[0] }),
                  let child = tree.categories.first(where: { $0.parent == parent.id && $0.name == parts[1] })
            else { throw SeedError.unknown(path) }
            return child.id
        }
        func group(_ name: String) throws -> NamedGroupWire {
            guard let g = named.first(where: { $0.group.name == name }) else { throw SeedError.unknown(name) }
            return g
        }
        func id(_ name: String, in g: NamedGroupWire) throws -> String {
            if name == you { return g.selfId }
            guard let m = g.members.first(where: { $0.name == name }) else { throw SeedError.unknown(name) }
            return m.id
        }
        enum Write { case expense(StoreShotsExpense), settlement(StoreShotsSettlement) }
        let writes = (expenses.map(Write.expense) + settlements.map(Write.settlement)).enumerated()
            .map { (at[$0.offset], $0.element) }
            .sorted { $0.0 < $1.0 }
        for (time, write) in writes {
            switch write {
            case .expense(let row) where row.ledger == justMe:
                _ = try store.appendExpense(JustMeExpense.dto(you: me, totalMinor: row.minor, currency: justMeCurrency,
                                                              note: row.note, category: try leaf(row.category), nowMs: time),
                                            nowMs: time)
            case .expense(let row):
                let g = try group(row.ledger)
                let people = row.among.isEmpty ? [you] + (groups.first { $0.name == row.ledger }?.people ?? []) : row.among
                let payer = try id(row.payer, in: g)
                _ = try store.appendExpense(LedgerStore.ExpenseDTO(
                    payer: payer, paidMinor: row.minor, currencyCode: g.group.settlementCurrency, group: g.group.id,
                    conversion: nil,
                    shares: try split(row.minor, among: people).map {
                        LedgerStore.ExpenseDTO.ShareDTO(id: try id($0.name, in: g), amountMinor: $0.minor)
                    },
                    note: row.note, timestampMs: time, category: try leaf(row.category), addedBy: payer
                ), nowMs: time)
            case .settlement(let row):
                let g = try group(row.ledger)
                _ = try store.appendSettlement(LedgerStore.SettlementDTO(
                    from: try id(row.from, in: g), to: try id(row.to, in: g), amountMinor: row.minor,
                    currencyCode: g.group.settlementCurrency, group: g.group.id
                ), nowMs: time)
            }
        }
        // The launch's own marks (D-175, D-245): launched, then the welcome cleared, as closing it does.
        if case .unavailable(let reason) = VaultRecovery.launch(store) { throw SeedError.launch(reason) }
        try WelcomeGate.clear(store)
    }
}

/// The launch's surface: the old vault set aside and the seed written (off the main thread), then the ordinary tabs.
struct StoreShotsScreen: View {
    @State private var ready = false
    @State private var failure: String?

    var body: some View {
        if ready {
            HomeShell(startOn: .balance)
        } else if let failure {
            Text(verbatim: failure).font(TypeRole.body()).padding()
        } else {
            ProgressView().task {
                let path = LedgerStore.defaultStore().path
                let outcome: String? = await Task.detached {
                    do {
                        try StoreShots.setAside(LedgerStore(path: path))
                        try StoreShots.seed(LedgerStore(path: path))
                        return nil
                    } catch let refusal as StoreShots.Refusal {
                        return refusal.words
                    } catch {
                        return "store shots: \(error)"
                    }
                }.value
                if let outcome { failure = outcome } else { ready = true }
            }
        }
    }
}
#endif
