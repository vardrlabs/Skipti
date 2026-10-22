import XCTest
@testable import Skipti

/// D-251 (U32): the store-shots seed. Debug only (every reference inside `#if DEBUG`, with a negative), no account and
/// no cloud (no entry point in the seed, a proof launch, and a seeded vault an ordinary launch would not configure
/// Firebase for), the lock never written, the times inside this month, and Home's numbers from core. Goldens:
/// `.drafts/d255/derive.py` (D-255's New York seed) and `.drafts/d251/times.py` (re-derived apart from both shells). Android's twin is StoreShotsTest,
/// which also compares the seed table with this file's.
final class StoreShotsTests: XCTestCase {
    private func source(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    private func appFiles() throws -> [String: String] {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        var out: [String: String] = [:]
        for name in try FileManager.default.contentsOfDirectory(atPath: dir.path) where name.hasSuffix(".swift") {
            out[name] = try String(contentsOf: dir.appendingPathComponent(name), encoding: .utf8)
        }
        return out
    }

    private func tempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("d251-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// 2026-10-21 12:00 UTC, and the month it is in.
    private let late: UInt64 = 1_792_584_000_000
    /// 2026-10-02 01:00 UTC: a day into the month, so most rows are pulled forward into it.
    private let early: UInt64 = 1_790_902_800_000
    private let october: UInt64 = 1_790_812_800_000
    private let utc = TimeZone(identifier: "UTC")!

    /// The lines of `text` that sit outside every `#if DEBUG` block (an `#else` of one is outside too).
    static func outsideDebug(_ text: String) -> [String] {
        var stack: [Bool] = []
        var out: [String] = []
        for line in text.components(separatedBy: "\n") {
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("#if") {
                stack.append(t == "#if DEBUG")
            } else if t.hasPrefix("#else"), !stack.isEmpty {
                // The other branch of any `#if` is not Debug code.
                stack[stack.count - 1] = false
            } else if t.hasPrefix("#endif") {
                _ = stack.popLast()
            } else if !stack.contains(true) {
                out.append(line)
            }
        }
        return out
    }

    // MARK: - Debug only

    func testTheSeedExistsOnlyInDebugBuilds() throws {
        let file = try source("StoreShots.swift")
        let lines = file.components(separatedBy: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        XCTAssertEqual(lines.first, "#if DEBUG", "the whole file is Debug only")
        XCTAssertEqual(lines.last, "#endif")
        XCTAssertEqual(Self.outsideDebug(file).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }, [])
        // Every other file that names it does so inside `#if DEBUG` only, and only the app root does.
        for (name, text) in try appFiles() where name != "StoreShots.swift" {
            let outside = Self.outsideDebug(text).filter { $0.contains("StoreShots") || $0.contains("--store-") }
            XCTAssertEqual(outside, [], "\(name): a store-shots reference outside #if DEBUG")
            if text.contains("StoreShots") { XCTAssertEqual(name, "SkiptiApp.swift") }
        }
    }

    /// The guard's negative: a reference outside `#if DEBUG` (after its `#endif`, or in its `#else`) is found.
    func testTheDebugGuardFindsAReferenceOutside() {
        let leaked = "#if DEBUG\nStoreShots.a()\n#else\nStoreShots.b()\n#endif\nStoreShots.c()\n"
        XCTAssertEqual(Self.outsideDebug(leaked).filter { !$0.isEmpty }, ["StoreShots.b()", "StoreShots.c()"])
        let nested = "#if DEBUG\n#if targetEnvironment(simulator)\nStoreShots.a()\n#endif\n#endif\n#if os(iOS)\nStoreShots.d()\n#endif"
        XCTAssertEqual(Self.outsideDebug(nested), ["StoreShots.d()"])
    }

    // MARK: - No account, no cloud, no lock written

    /// The seed names no cloud entry point (the ones D235ShellTests counts, and the sync, push and settings writers);
    /// the launch is a proof launch, so the gate (Firebase), the lock, the curtain, the welcome and CloudSync never
    /// run; nothing writes the lock's setting.
    func testTheSeedSignsInToNothingAndStartsNoSync() throws {
        let file = try source("StoreShots.swift")
        for entry in ["FirebaseConfig.configure", "FirebaseGate", "Auth.auth()", "CloudSync", "Push.", "SettingsSync.set(", "SettingsSync.write(",
                      "SettingsSync.pageClosed", "appendGroupOps",
                      "EmulatorSignIn", "Account.shared", "Firestore", "Messaging", "signIn", "AppLock", "applock", "lock."] {
            XCTAssertFalse(file.contains(entry), "the seed: \(entry)")
        }
        let app = try source("SkiptiApp.swift")
        let arg = try XCTUnwrap(app.components(separatedBy: "private var verificationArg: Bool {").dropFirst().first?
            .components(separatedBy: "#else").first)
        XCTAssertTrue(arg.contains("StoreShots.flag].contains { args.contains($0) }"), "a proof launch")
        let gate = try XCTUnwrap(app.components(separatedBy: "private func loadGate(retry: Bool = false) {").dropFirst().first)
        XCTAssertTrue(gate.hasPrefix("\n        // Headless verification args bypass the gate entirely") && gate.contains("if verificationArg { return }"))
        XCTAssertTrue(StoreShots.active(["app", "--store-shots"]))
        XCTAssertFalse(StoreShots.active(["app", "--groups-proof"]))
    }

    /// The seeded vault has no owner and no sign-in mark: even an ordinary launch of it later makes no Firebase call.
    func testTheSeededVaultHasNoAccount() throws {
        let store = LedgerStore(path: tempDir().appendingPathComponent("v.db").path)
        try StoreShots.seed(store, nowMs: late, timeZone: utc)
        XCTAssertNil(Account.storedOwner(store))
        XCTAssertNil(Account.signedInMark(store))
        XCTAssertEqual(LaunchFirebase.plan(Account.ownerRead(store), signedIn: Account.signedInMark(store),
                                           deleteOwed: Push.deleteOwed(store)), .none)
        // The launch's marks: launched, the welcome not pending.
        XCTAssertTrue(try store.kvFlag(key: VaultRecovery.launchedFlag))
        XCTAssertFalse(try WelcomeGate.pending(store))
        XCTAssertFalse(WelcomeGate.atLaunch(launchedBefore: true) { try WelcomeGate.pending(store) })
    }

    // MARK: - Arguments

    func testTheThemeAndModeComeFromTheArguments() {
        XCTAssertEqual(StoreShots.theme(["--store-shots", "--store-theme", "norse"]), .norse)
        XCTAssertEqual(StoreShots.theme(["--store-theme", "vardr"]), .vardr)
        XCTAssertNil(StoreShots.theme(["--store-theme", "Norse"]), "the stored raw value only")
        XCTAssertNil(StoreShots.theme(["--store-theme"]))
        XCTAssertNil(StoreShots.theme(["--store-shots"]))
        XCTAssertEqual(StoreShots.mode(["--store-theme", "aurora", "--store-mode", "light"]), .light)
        XCTAssertEqual(StoreShots.mode(["--store-theme", "aurora"]), .dark, "a theme with no mode: dark")
        XCTAssertEqual(StoreShots.mode(["--store-theme", "aurora", "--store-mode", "system"]), .dark, "never the phone's")
        XCTAssertNil(StoreShots.mode(["--store-shots"]))

        let suite = "d251-\(UUID().uuidString)"
        let defaults = try! XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(true, forKey: AppearanceMode.migratedKey)
        let fresh = LedgerStore(path: tempDir().appendingPathComponent("v.db").path)
        StoreShots.applyAppearance(["--store-theme", "norse"], defaults: defaults, store: fresh)
        XCTAssertNil(defaults.string(forKey: AppTheme.storageKey), "without --store-shots nothing is written")
        // Review fix wave: a run the seed refuses (here, a phone with an account) writes no theme either.
        let owned = LedgerStore(path: tempDir().appendingPathComponent("v.db").path)
        try! owned.kvSet(key: Account.ownerKey, value: Data("uid-1".utf8))
        StoreShots.applyAppearance(["--store-shots", "--store-theme", "vardr"], defaults: defaults, store: owned)
        XCTAssertNil(defaults.string(forKey: AppTheme.storageKey), "refused: nothing written")
        XCTAssertTrue(defaults.bool(forKey: AppearanceMode.migratedKey))
        // The flag anywhere among the arguments (Android takes it anywhere too).
        StoreShots.applyAppearance(["--store-theme", "vardr", "--store-mode", "light", "--store-shots"], defaults: defaults,
                                   store: fresh)
        XCTAssertEqual(defaults.string(forKey: AppTheme.storageKey), "vardr")
        XCTAssertEqual(defaults.string(forKey: AppearanceMode.storageKey), "light")
        XCTAssertFalse(defaults.bool(forKey: AppearanceMode.migratedKey), "a chosen mode")
    }

    // MARK: - Times

    func testEveryRowIsThisMonthAndInOrder() {
        let hours = StoreShots.expenses.map(\.hoursAgo) + StoreShots.settlements.map(\.hoursAgo)
        XCTAssertEqual(Set(hours).count, hours.count, "distinct, so the order is the table's")
        let lateBefore: [UInt64] = [31_622_400_000, 849_600_000, 763_200_000, 684_000_000, 597_600_000, 576_000_000,
                                    504_000_000, 424_800_000, 338_400_000, 252_000_000, 108_000_000, 21_600_000,
                                    900_000_000, 360_000_000, 270_000_000, 158_400_000, 259_200_000, 180_000_000,
                                    100_800_000, 18_000_000, 93_600_000, 187_200_000, 432_000_000, 604_800_000,
                                    720_000_000, 72_000_000]
        let earlyBefore: [UInt64] = [31_622_400_000, 89_880_000, 89_820_000, 89_700_000, 89_580_000, 89_520_000,
                                     89_460_000, 89_340_000, 89_220_000, 89_040_000, 88_800_000, 21_600_000,
                                     89_940_000, 89_280_000, 89_160_000, 88_860_000, 89_100_000, 88_920_000,
                                     88_740_000, 18_000_000, 88_680_000, 88_980_000, 89_400_000, 89_640_000,
                                     89_760_000, 72_000_000]
        for (now, before) in [(late, lateBefore), (early, earlyBefore)] {
            let times = StoreShots.times(hoursAgo: hours, nowMs: now, monthStartMs: october)
            XCTAssertEqual(times.map { now - $0 }, before)
            for (i, t) in times.enumerated() where hours[i] < StoreShots.keptFromHours {
                XCTAssertGreaterThanOrEqual(t, october, "row \(i) in this month")
                XCTAssertLessThan(t, now)
            }
            let byHours = hours.indices.sorted { hours[$0] > hours[$1] }.map { times[$0] }
            XCTAssertEqual(byHours, byHours.sorted(), "older rows stay older")
        }
    }

    func testTheSplitIsEqualWithTheRemainderFirst() {
        XCTAssertEqual(StoreShots.split(10, among: ["a", "b", "c"]).map(\.minor), [4, 3, 3])
        XCTAssertEqual(StoreShots.split(18_000, among: ["Sam", "Jake", "Olivia"]).map(\.minor), [6_000, 6_000, 6_000])
        XCTAssertEqual(StoreShots.split(7_000, among: ["Sam", "Emma"]).map(\.name), ["Sam", "Emma"])
    }

    // MARK: - Set aside, never deleted (review fix wave: only a vault carrying the seed's flag is ever deleted)

    private func contents(_ dir: URL) -> [String: Data] {
        let fm = FileManager.default
        var out: [String: Data] = [:]
        for name in (try? fm.contentsOfDirectory(atPath: dir.path)) ?? [] {
            out[name] = fm.contents(atPath: dir.appendingPathComponent(name).path)
        }
        return out
    }

    /// A vault that cannot be read (the stand-in files below are not vaults: SQLite would treat a tiny main file as empty
    /// and drop its write-ahead file on open). It counts as a ledger: set aside, never deleted.
    private let unreadable: (LedgerStore) -> StoreShots.Marks = { _ in StoreShots.Marks(owner: false, seeded: false) }

    func testTheOldVaultIsSetAsideOnceAndOnlyASeedIsReplaced() throws {
        let dir = tempDir()
        let vault = dir.appendingPathComponent("ledger-vault.db").path
        let fm = FileManager.default
        try StoreShots.setAside(LedgerStore(path: vault))
        XCTAssertEqual(try fm.contentsOfDirectory(atPath: dir.path), [], "nothing there, nothing done")
        fm.createFile(atPath: vault, contents: Data("mine".utf8))
        fm.createFile(atPath: vault + "-wal", contents: Data("wal".utf8))
        try StoreShots.setAside(LedgerStore(path: vault), read: unreadable)
        XCTAssertFalse(fm.fileExists(atPath: vault))
        XCTAssertEqual(fm.contents(atPath: vault + ".before-store-shots"), Data("mine".utf8))
        XCTAssertEqual(fm.contents(atPath: vault + ".before-store-shots-wal"), Data("wal".utf8))
        // The seed marks its vault; a later run deletes that vault, and the set-aside one stays.
        try StoreShots.seed(LedgerStore(path: vault), nowMs: late, timeZone: utc)
        XCTAssertTrue(try LedgerStore(path: vault).kvFlag(key: StoreShots.seededKey))
        XCTAssertEqual(try StoreShots.check(LedgerStore(path: vault)), .seed)
        try StoreShots.setAside(LedgerStore(path: vault))
        XCTAssertFalse(fm.fileExists(atPath: vault), "an earlier seed goes")
        XCTAssertEqual(fm.contents(atPath: vault + ".before-store-shots"), Data("mine".utf8), "the first vault is kept")
    }

    /// I1: a vault without the seed's flag while an aside exists (a ledger copied back, a second ledger) is refused
    /// with the screen's words, and no file changes.
    func testAVaultWithoutTheFlagIsRefusedWhileAnAsideExists() throws {
        let dir = tempDir()
        let vault = dir.appendingPathComponent("ledger-vault.db").path
        let store = LedgerStore(path: vault)
        _ = try store.setYourName("Priya")
        XCTAssertFalse(try store.kvFlag(key: StoreShots.seededKey))
        FileManager.default.createFile(atPath: vault + ".before-store-shots", contents: Data("older".utf8))
        let before = contents(dir)
        XCTAssertThrowsError(try StoreShots.setAside(store)) {
            XCTAssertEqual($0 as? StoreShots.Refusal, .asideExists)
        }
        XCTAssertEqual(contents(dir), before, "nothing moved, nothing deleted")
        XCTAssertEqual(StoreShots.Refusal.asideExists.words, "A set-aside ledger already exists. Restore or remove it first.")
        // A stray set-aside write-ahead file is a destination in use too.
        let other = tempDir()
        let path = other.appendingPathComponent("ledger-vault.db").path
        FileManager.default.createFile(atPath: path, contents: Data("mine".utf8))
        FileManager.default.createFile(atPath: path + ".before-store-shots-wal", contents: Data("old wal".utf8))
        let was = contents(other)
        XCTAssertThrowsError(try StoreShots.setAside(LedgerStore(path: path), read: unreadable)) {
            XCTAssertEqual($0 as? StoreShots.Refusal, .asideExists)
        }
        XCTAssertEqual(contents(other), was)
    }

    /// I1: a phone with an account is refused, with or without an aside, the seed's flag included.
    func testAVaultWithAnAccountIsRefused() throws {
        let dir = tempDir()
        let vault = dir.appendingPathComponent("ledger-vault.db").path
        let store = LedgerStore(path: vault)
        try store.kvSet(key: Account.ownerKey, value: Data("uid-1".utf8))
        let before = contents(dir)
        XCTAssertThrowsError(try StoreShots.setAside(store)) { XCTAssertEqual($0 as? StoreShots.Refusal, .account) }
        XCTAssertEqual(contents(dir), before, "nothing moved, nothing deleted")
        try store.setKvFlag(key: StoreShots.seededKey)
        XCTAssertThrowsError(try StoreShots.setAside(store)) { XCTAssertEqual($0 as? StoreShots.Refusal, .account) }
        XCTAssertTrue(FileManager.default.fileExists(atPath: vault))
        XCTAssertEqual(StoreShots.Refusal.account.words,
                       "This phone has an account. The store seed runs only on a throwaway device.")
    }

    /// The move is the main file first, all or nothing: a later move that fails puts the moved files back.
    func testTheMoveIsAllOrNothing() throws {
        let dir = tempDir()
        let vault = dir.appendingPathComponent("ledger-vault.db").path
        let fm = FileManager.default
        fm.createFile(atPath: vault, contents: Data("mine".utf8))
        fm.createFile(atPath: vault + "-wal", contents: Data("wal".utf8))
        fm.createFile(atPath: vault + "-shm", contents: Data("shm".utf8))
        let before = contents(dir)
        var order: [String] = []
        let failing: (String, String) throws -> Void = { from, to in
            order.append(from)
            if from == vault + "-shm" { throw CocoaError(.fileWriteUnknown) }
            try fm.moveItem(atPath: from, toPath: to)
        }
        XCTAssertThrowsError(try StoreShots.setAside(LedgerStore(path: vault), read: unreadable, move: failing))
        XCTAssertEqual(order.first, vault, "the main file first")
        XCTAssertEqual(contents(dir), before, "every moved file put back")
    }

    func testTheSeedWritesOnlyAFreshVault() throws {
        let path = tempDir().appendingPathComponent("v.db").path
        FileManager.default.createFile(atPath: path, contents: Data([1]))
        XCTAssertThrowsError(try StoreShots.seed(LedgerStore(path: path), nowMs: late, timeZone: utc)) {
            XCTAssertEqual($0 as? StoreShots.SeedError, .notFresh)
        }
    }

    // MARK: - Home from the seed (core's numbers, pinned once against .drafts/d255/derive.py)

    /// D-253: the seeded groups keep the seed's fixed ids (Android's StoreShotsTest compares them with its own), so
    /// their tiles take the same tones on both phones on every capture: Hamptons weekend teal, Apartment 4B pink,
    /// Brooklyn dinners blue. The negative: a group built by core alone gets a random id, never the seed's.
    func testTheSeededGroupsKeepTheirFixedIdsAndTones() throws {
        let store = LedgerStore(path: tempDir().appendingPathComponent("v.db").path)
        try StoreShots.seed(store, nowMs: late, timeZone: utc)
        let named = try store.namedGroups()
        XCTAssertEqual(named.map(\.group.id), StoreShots.groups.map(\.id))
        XCTAssertEqual(named.map { GroupTone.of($0.group.id) }, [.teal, .pink, .blue])
        XCTAssertEqual(named.map { GroupTone.allCases.firstIndex(of: GroupTone.of($0.group.id)) }, [2, 5, 1])
        XCTAssertEqual(Set(named.map(\.group.id)).count, 3, "three different ids")
        for g in named {
            XCTAssertNotNil(g.group.id.range(of: "^[0-9a-f]{32}$", options: .regularExpression), g.group.id)
        }
        let me = try store.ensureSelf()
        let random = try ffiCreateNamedGroup(name: "Hamptons weekend", settlementCurrency: "USD",
                                             selfMember: MemberWire(id: me.id, name: "Sam"), otherNames: ["Emma"])
        XCTAssertFalse(StoreShots.groups.map(\.id).contains(random.group.id), "core's id is random")
    }

    func testTheSeededVaultGivesHomeItsNumbers() throws {
        let store = LedgerStore(path: tempDir().appendingPathComponent("v.db").path)
        try StoreShots.seed(store, nowMs: late, timeZone: utc)
        XCTAssertEqual(try store.loadExpenses().count, 25)
        XCTAssertEqual(try store.loadSettlements().count, 1)
        let home = try HomeOverview.load(store)
        XCTAssertEqual(home.named.map(\.group.name), ["Hamptons weekend", "Apartment 4B", "Brooklyn dinners"])
        XCTAssertEqual(Set(home.named.flatMap { $0.members.map(\.name) }), ["Sam", "Emma", "Jake", "Olivia", "Ben"])
        XCTAssertEqual(home.overview.totals.map { "\($0.currencyCode) \($0.amountMinor)" }.sorted(),
                       ["USD 260075"])
        func net(_ group: String) -> [String] {
            let id = home.named.first { $0.group.name == group }?.group.id
            return home.overview.groups.first { $0.groupId == id }?.nets.map { "\($0.currencyCode) \($0.amountMinor)" } ?? []
        }
        XCTAssertEqual(net("Hamptons weekend"), ["USD 55275"])
        XCTAssertEqual(net("Apartment 4B"), ["USD 202900"])
        XCTAssertEqual(net("Brooklyn dinners"), ["USD 1900"])

        // Your spending on Home (Just me, this month): five categories.
        let month = SpendingMonth.containing(ms: late, timeZone: utc)
        let spent = try SpendingLoad.load(store, month: month, view: .justMe, timeZone: utc).out
        XCTAssertEqual(spent.currencies.map { "\($0.currencyCode) \($0.totalMinor)" }, ["USD 52550"])
        XCTAssertEqual(spent.currencies.first?.categories.count, 5)
        XCTAssertEqual(spent.rows.count, 6)

        // Hamptons weekend's milestone is due (a year since its first expense); the others have none.
        let live = try store.projectedExpenses().expenses
        func milestone(_ group: String) -> Milestone? {
            let id = home.named.first { $0.group.name == group }?.group.id
            return Milestones.current(live.filter { $0.expense.group == id && !$0.deleted }
                .map { MilestoneExpense(addedAtMs: $0.addedAtMs, dateMs: $0.timestampMs ?? $0.addedAtMs) }, now: late)?.milestone
        }
        XCTAssertEqual(milestone("Hamptons weekend"), .year)
        XCTAssertNil(milestone("Apartment 4B"))

        // Lately: the newest of others' additions, each at its row's time (not "now").
        let lately = Lately.pick(try ActivityFeed.load(store, group: nil).entries).compactMap { entry -> ActivityFeed.Item? in
            if case .expense(let item) = entry { return item } else { return nil }
        }
        XCTAssertEqual(lately.map(\.title), ["Ride to the station", "Tacos", "Seafood dinner"])
        XCTAssertEqual(lately.map { (late - $0.atMs) / 3_600_000 }, [6, 28, 30])
    }
}
