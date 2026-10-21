import XCTest
@testable import Skipti

/// D-221 (E4, the spec's completion ring, GUARANTEES #5): the setup steps You counts, their order, their facts, the
/// next step, the Futhark ring's lit count and the copy. Android's twin is YouProgressTest.
final class YouProgressTests: XCTestCase {
    /// Every fact false, the lock off: nothing is done.
    private func none(signedIn: Bool) -> SetupFacts {
        SetupFacts(signedIn: signedIn, hasName: false, hasPicture: false, storedCurrency: nil, lockTimeout: .off,
                   lockAvailable: true, hasGroup: false, hasUsername: false, notificationsOn: false)
    }

    /// Every fact true.
    private func all(signedIn: Bool) -> SetupFacts {
        SetupFacts(signedIn: signedIn, hasName: true, hasPicture: true, storedCurrency: "INR", lockTimeout: .standard,
                   lockAvailable: true, hasGroup: true, hasUsername: true, notificationsOn: true)
    }

    /// GUARANTEES #5: without an account exactly the five, in order, and no step's words mention signing in or an
    /// account (the negative: an account is never implied or nudged for).
    func testWithoutAnAccountTheFiveStepsNeverMentionAnAccount() {
        XCTAssertEqual(SetupSteps.steps(signedIn: false, lockAvailable: true), [.name, .picture, .currency, .appLock, .firstGroup])
        XCTAssertFalse(SetupSteps.steps(signedIn: false, lockAvailable: true).contains(.username))
        XCTAssertFalse(SetupSteps.steps(signedIn: false, lockAvailable: true).contains(.notifications))
        for step in SetupStep.allCases {
            for words in [StepCopy.title(step), StepCopy.why(step)] {
                XCTAssertFalse(words.lowercased().contains("sign"), "\(step): \(words)")
                XCTAssertFalse(words.lowercased().contains("account"), "\(step): \(words)")
            }
        }
        XCTAssertFalse(StepCopy.doIt.lowercased().contains("sign"))
        XCTAssertEqual(SetupSteps.progress(none(signedIn: false)).total, 5)
    }

    func testSignedInAddsTheUsernameAndNotifications() {
        XCTAssertEqual(SetupSteps.steps(signedIn: true, lockAvailable: true),
                       [.name, .picture, .currency, .appLock, .firstGroup, .username, .notifications])
        XCTAssertEqual(SetupSteps.progress(none(signedIn: true)).total, 7)
    }

    /// D-247 (decision 169, D-221 h): a phone that cannot lock (no passcode: `canEvaluatePolicy` false) has no app lock
    /// step, so the ring completes at "4 of 4" (6 of 6 signed in); with a lock the step is back. The capability is the
    /// pure function's input; nothing else about the steps changes.
    func testThePhoneWithoutALockDropsTheAppLockStep() {
        XCTAssertEqual(SetupSteps.steps(signedIn: false, lockAvailable: false), [.name, .picture, .currency, .firstGroup])
        XCTAssertEqual(SetupSteps.steps(signedIn: true, lockAvailable: false),
                       [.name, .picture, .currency, .firstGroup, .username, .notifications])
        var facts = all(signedIn: false)
        facts.lockAvailable = false
        XCTAssertTrue(SetupSteps.progress(facts) == (4, 4), "every step done: the ring completes, \(SetupSteps.progress(facts))")
        XCTAssertEqual(StepCopy.pill(done: 4, total: 4), "4 of 4 done")
        XCTAssertNil(SetupSteps.next(facts), "nothing left to ask")
        facts.hasGroup = false
        XCTAssertEqual(SetupSteps.next(facts), .firstGroup, "the app lock is never the next step without a lock")
        var signed = all(signedIn: true)
        signed.lockAvailable = false
        XCTAssertTrue(SetupSteps.progress(signed) == (6, 6))
        // The negative: with a lock the step counts again, and undone it is asked for.
        var locking = none(signedIn: false)
        locking.lockAvailable = true
        locking.hasName = true
        locking.hasPicture = true
        locking.storedCurrency = "INR"
        XCTAssertTrue(SetupSteps.progress(locking) == (3, 5), "\(SetupSteps.progress(locking))")
        XCTAssertEqual(SetupSteps.next(locking), .appLock)
        locking.lockAvailable = false
        XCTAssertTrue(SetupSteps.progress(locking) == (3, 4), "\(SetupSteps.progress(locking))")
        XCTAssertEqual(SetupSteps.next(locking), .firstGroup)
    }

    /// The hub reads the capability again when SKIPTI comes to the front (a passcode set in the phone's Settings).
    func testTheHubReadsTheLockAgainOnTheFront() throws {
        let hub = try String(contentsOfFile: Self.app("AccountHubScreen.swift"), encoding: .utf8)
        XCTAssertTrue(hub.contains("if phase == .active { Task { await readFacts() } }"))
        let you = try String(contentsOfFile: Self.app("YouProgress.swift"), encoding: .utf8)
        XCTAssertTrue(you.contains("facts.lockAvailable = AppLock.available"))
        let lock = try String(contentsOfFile: Self.app("AppLock.swift"), encoding: .utf8)
        XCTAssertTrue(lock.contains("LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)"))
    }

    private static func app(_ file: String) -> String {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)").path
    }

    /// Each fact flipped alone makes exactly its own step done.
    func testEachFactFlipsExactlyItsStep() {
        let base = none(signedIn: true)
        XCTAssertEqual(SetupSteps.done(base), [])
        let flips: [(SetupStep, (inout SetupFacts) -> Void)] = [
            (.name, { $0.hasName = true }),
            (.picture, { $0.hasPicture = true }),
            (.currency, { $0.storedCurrency = "INR" }),
            (.appLock, { $0.lockTimeout = .standard }),
            (.firstGroup, { $0.hasGroup = true }),
            (.username, { $0.hasUsername = true }),
            (.notifications, { $0.notificationsOn = true }),
        ]
        XCTAssertEqual(flips.map(\.0), SetupStep.allCases)
        for (step, flip) in flips {
            var facts = base
            flip(&facts)
            XCTAssertEqual(SetupSteps.done(facts), [step], "\(step)")
        }
        XCTAssertEqual(SetupSteps.done(all(signedIn: true)), Set(SetupStep.allCases))
        // Without an account the username and notifications facts count for nothing.
        var local = none(signedIn: false)
        local.hasUsername = true
        local.notificationsOn = true
        XCTAssertEqual(SetupSteps.done(local), [])
    }

    /// Unset does not count, though it resolves to the region's currency; a stored code core does not know does not
    /// count either.
    func testTheCurrencyStepNeedsAStoredCodeCoreKnows() {
        var facts = none(signedIn: false)
        XCTAssertFalse(SetupSteps.isDone(.currency, facts), "unset")
        XCTAssertFalse(DefaultCurrency.all.contains("ZZZ"))
        facts.storedCurrency = "ZZZ"
        XCTAssertFalse(SetupSteps.isDone(.currency, facts), "a stored unknown code")
        facts.storedCurrency = "USD"
        XCTAssertTrue(SetupSteps.isDone(.currency, facts))
    }

    /// The app lock counts when it locks at launch: on (the default, two minutes) on a phone with a lock.
    func testTheAppLockStepFollowsTheLockRules() {
        var facts = none(signedIn: false)
        facts.lockTimeout = .off
        facts.lockAvailable = true
        XCTAssertFalse(SetupSteps.isDone(.appLock, facts), "off")
        facts.lockTimeout = .standard
        facts.lockAvailable = false
        XCTAssertFalse(SetupSteps.isDone(.appLock, facts), "the default on a phone with no lock")
        facts.lockAvailable = true
        XCTAssertTrue(SetupSteps.isDone(.appLock, facts), "the default on a phone with a lock")
        XCTAssertTrue(SetupSteps.isDone(.appLock, SetupFacts(lockAvailable: true)), "a fresh phone with a lock")
    }

    func testTheNextStepIsTheFirstUndone() {
        XCTAssertEqual(SetupSteps.next(none(signedIn: true)), .name)
        var facts = all(signedIn: true)
        facts.hasPicture = false
        facts.hasGroup = false
        XCTAssertEqual(SetupSteps.next(facts), .picture)
        facts.hasPicture = true
        XCTAssertEqual(SetupSteps.next(facts), .firstGroup)
        facts.hasGroup = true
        facts.hasUsername = false
        facts.notificationsOn = false
        XCTAssertEqual(SetupSteps.next(facts), .username)
        // While the username card shows, the next card passes over the username step.
        XCTAssertEqual(SetupSteps.next(facts, skipping: [.username]), .notifications)
        facts.notificationsOn = true
        XCTAssertNil(SetupSteps.next(facts, skipping: [.username]))
        XCTAssertNil(SetupSteps.next(all(signedIn: true)))
        XCTAssertNil(SetupSteps.next(all(signedIn: false)))
    }

    func testProgressCountsTheFactsOwnSteps() {
        var facts = all(signedIn: true)
        facts.notificationsOn = false
        XCTAssertTrue(SetupSteps.progress(facts) == (6, 7))
        facts = none(signedIn: false)
        facts.hasName = true
        facts.lockTimeout = .standard
        facts.hasGroup = true
        facts.hasUsername = true
        XCTAssertTrue(SetupSteps.progress(facts) == (3, 5), "\(SetupSteps.progress(facts))")
        XCTAssertTrue(SetupSteps.progress(all(signedIn: false)) == (5, 5))
        XCTAssertTrue(SetupSteps.progress(none(signedIn: true)) == (0, 7))
    }

    /// The Futhark ring: Math.round(24 x done / total), half up (art.py 18).
    func testTheLitRunesAreTheGoldens() {
        let goldens: [(Int, Int, Int)] = [(0, 5, 0), (1, 5, 5), (4, 5, 19), (5, 5, 24), (0, 7, 0), (3, 7, 10), (6, 7, 21), (7, 7, 24)]
        for (done, total, lit) in goldens { XCTAssertEqual(RuneRing.lit(done: done, total: total), lit, "\(done) of \(total)") }
        XCTAssertEqual(RuneRing.lit(done: 0, total: 0), 0)
    }

    /// The copy, verbatim on both phones (the plan's table).
    func testTheCopyTable() {
        let table: [(SetupStep, String, String)] = [
            (.name, "Add your name", "Shown on Balance and in your groups."),
            (.picture, "Add a picture", "A photo or an emoji beside your name."),
            (.currency, "Choose your currency", "New groups and expenses start in it."),
            (.appLock, "Set an app lock", "Your phone's lock before anyone sees your ledger."),
            (.firstGroup, "Start a group", "Split a trip, a flat or a dinner."),
            (.username, "Pick your username", "People can find and add you by it."),
            (.notifications, "Turn on notifications", "Hear when your groups add an expense."),
        ]
        XCTAssertEqual(table.map(\.0), SetupStep.allCases)
        for (step, title, why) in table {
            XCTAssertEqual(StepCopy.title(step), title)
            XCTAssertEqual(StepCopy.why(step), why)
        }
        XCTAssertEqual(StepCopy.doIt, "Do it")
        XCTAssertEqual(StepCopy.pill(done: 6, total: 7), "6 of 7 done")
    }

    /// Every step has a destination: name and picture Profile, currency Your data, app lock Security, the first group
    /// the create-group sheet, the username its sheet, notifications Profile and account.
    func testEveryStepHasItsPlace() {
        XCTAssertEqual(SetupStep.allCases.map(StepPlace.of),
                       [.profile, .profile, .data, .security, .createGroup, .username, .profileAccount])
    }

    // MARK: - D-221 Task 4: the You screen

    /// D-221 (S7): the vault's three reads happen in `VaultFacts.read`, off the main thread; `SetupFacts.read` keeps
    /// only what is cheap (preferences, the lock, the account) on the main actor.
    func testTheVaultFactsAreReadOffTheMainThread() throws {
        let you = try appSource("YouProgress.swift")
        let read = try slice(you, from: "static func read(store: LedgerStore, account: Account, hasUsername: Bool) async -> SetupFacts {",
                             to: "\n    }\n")
        XCTAssertEqual(count(read, "await Task.detached(priority: .userInitiated) { VaultFacts.read(store) }.value"), 1)
        for vaultRead in ["loadSelf(", "loadAvatar(", "namedGroups("] {
            XCTAssertFalse(read.contains(vaultRead), "\(vaultRead) on the main actor")
        }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("facts-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        _ = try store.setYourName("Prithvi")
        XCTAssertEqual(VaultFacts.read(store), VaultFacts(hasName: true, hasPicture: false, hasGroup: false))
        _ = try store.createGroup(name: "Paris", settlementCurrency: "EUR", people: ["Ana"])
        XCTAssertEqual(VaultFacts.read(store), VaultFacts(hasName: true, hasPicture: false, hasGroup: true))
    }

    /// S7: overlapping reads: only the newest one's answer is kept.
    func testAnOlderFactsReadNeverOverwritesANewerOne() {
        var reads = ReadGeneration()
        let first = reads.next()
        let second = reads.next()
        XCTAssertFalse(reads.isLatest(first), "the first read finished late: dropped")
        XCTAssertTrue(reads.isLatest(second))
        let third = reads.next()
        XCTAssertTrue(reads.isLatest(third))
        XCTAssertFalse(reads.isLatest(second))
    }

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The text between the first `from` and the next `to` after it.
    private func slice(_ text: String, from: String, to: String) throws -> String {
        let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
        return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
    }

    private func count(_ text: String, _ needle: String) -> Int { text.components(separatedBy: needle).count - 1 }

    /// L38 to L40, Q11 a: the hero rings the picture (Norse its runes, the others the arc), the pill takes "Edit"'s
    /// place and "Edit" is gone; the name is the display name size; the picture viewer stays 104, centred in the
    /// ring's box; the hero's label carries the pill's words.
    func testYouHeroDrawsTheRingAndPill() throws {
        let profile = try appSource("Profile.swift")
        let hero = try slice(profile, from: "struct YouHero: View {", to: "struct HomeGreeting: View {")
        XCTAssertEqual(count(hero, "RuneRing(done: shown.done, total: shown.total)"), 1, "Norse's rune ring")
        XCTAssertEqual(count(hero, "CompletionRing(done: shown.done, total: shown.total)"), 1, "the arc elsewhere")
        XCTAssertTrue(hero.contains("if AppearanceState.shared.theme == .norse {"), "the ring is chosen by theme")
        XCTAssertEqual(count(hero, "ProgressPill(done: progress.done, total: progress.total)"), 1, "the pill")
        XCTAssertFalse(hero.contains("YouCopy.edit"), "Edit is gone from the hero")
        XCTAssertTrue(hero.contains(".font(TypeRole.display(TypeScale.displayName, weight: 700))"), "the name")
        XCTAssertFalse(hero.contains("display(24"), "no literal name size")
        XCTAssertTrue(hero.contains(".frame(width: ThemeArt.m(\"ringBox\"), height: ThemeArt.m(\"ringBox\"))"), "the ring's box")
        XCTAssertEqual(count(hero, "AvatarView(avatar: avatar, name: name, size: 104)"), 1, "the picture stays 104")
        XCTAssertTrue(hero.contains(".frame(width: 104, height: 104)"), "the viewer's button stays 104")
        XCTAssertTrue(hero.contains(".padding(.top, 8 + (ThemeArt.m(\"ringBox\") - 104) / 2)"), "centred in the ring's box")
        XCTAssertTrue(try slice(hero, from: "private var profileLabel: String {", to: "\n    }\n").contains("StepCopy.pill(done:"),
                      "the label carries the pill's words")
        let you = try slice(profile, from: "enum YouCopy {", to: "\n}\n")
        XCTAssertFalse(you.contains("static let edit"), "YouCopy has no edit")
        XCTAssertFalse(you.contains("\"Edit\""))
    }

    /// L38, L39: the arc and the runes are the art table's, in the ring's colours, hidden from VoiceOver.
    func testTheRingIsTheArtTable() throws {
        let src = try appSource("YouProgress.swift")
        let arc = try slice(src, from: "struct CompletionRing: View {", to: "\n}\n")
        for key in ["ringBox", "ringRadius", "ringStroke"] {
            XCTAssertTrue(arc.contains("ThemeArt.m(\"\(key)\")"), "the arc reads \(key)")
        }
        XCTAssertTrue(arc.contains(".stroke(Tokens.ringTrack, lineWidth: stroke)"), "the track")
        XCTAssertTrue(arc.contains(".stroke(Tokens.ring, style: StrokeStyle(lineWidth: stroke, lineCap: .round))"), "the arc")
        XCTAssertTrue(arc.contains(".rotationEffect(.degrees(-90))"), "from 12 o'clock")
        XCTAssertTrue(arc.contains("if done > 0, total > 0 {"), "no arc at 0")
        XCTAssertTrue(arc.contains(".accessibilityHidden(true)"))
        let runes = try slice(src, from: "extension RuneRing: View {", to: "\n}\n")
        for key in ["ringBox", "runeRingRadius", "runeRingSize"] {
            XCTAssertTrue(runes.contains("ThemeArt.m(\"\(key)\")"), "the runes read \(key)")
        }
        XCTAssertTrue(runes.contains("RuneRing.lit(done: done, total: total)"))
        XCTAssertTrue(runes.contains("Runes.futhark"))
        XCTAssertTrue(runes.contains(".font(TypeRole.rune(size))"), "the rune face")
        XCTAssertTrue(runes.contains(".foregroundStyle(i < lit ? Tokens.ring : ThemeArt.color(\"runeUnlit\"))"))
        XCTAssertTrue(runes.contains(".accessibilityHidden(true)"))
    }

    /// L40: the pill is C2's accent pill on the page, at the pill size; Vardr Labs adds the dot.
    func testThePillIsThePill() throws {
        let pill = try slice(try appSource("YouProgress.swift"), from: "extension ProgressPill: View {", to: "\n}\n")
        XCTAssertTrue(pill.contains(".themePill(.accent, onPage: true)"))
        XCTAssertTrue(pill.contains(".font(TypeRole.label(TypeScale.pill))"))
        XCTAssertTrue(pill.contains("Text(verbatim: StepCopy.pill(done: done, total: total))"))
        XCTAssertTrue(pill.contains("if ProgressPill.showsDot(AppearanceState.shared.theme) {"))
        XCTAssertTrue(pill.contains("Circle().fill(Tokens.ring).frame(width: ThemeArt.m(\"progressDot\"), height: ThemeArt.m(\"progressDot\"))"))
        XCTAssertTrue(pill.contains("HStack(spacing: ThemeArt.m(\"progressDotGap\"))"))
        XCTAssertFalse(pill.contains("Capsule()"))
    }

    /// L41: the next card's "Do it" is the compact primary, and every place a step goes is handled by one switch
    /// with no default (a new place cannot be forgotten); the card skips the username while its own card asks.
    func testNextStepCardUsesTheCompactPrimary() throws {
        let card = try slice(try appSource("YouProgress.swift"), from: "struct NextStepCard: View {", to: "\n}\n")
        XCTAssertTrue(card.contains(".buttonStyle(PrimaryButtonStyle(compact: true))"))
        XCTAssertTrue(card.contains("Text(verbatim: StepCopy.doIt)"))
        XCTAssertTrue(card.contains(".font(TypeRole.name(16))"))
        XCTAssertTrue(card.contains(".font(TypeRole.body(TypeScale.detail))"))
        XCTAssertTrue(card.contains(".glassCard()"))
        let hub = try appSource("AccountHubScreen.swift")
        let go = try slice(hub, from: "private func go(_ place: StepPlace) {", to: "\n    }\n")
        for place in ["profile", "data", "security", "createGroup", "username", "profileAccount"] {
            XCTAssertTrue(go.contains(".\(place)"), "\(place) has a destination")
        }
        XCTAssertFalse(go.contains("default"), "a switch with no default")
        XCTAssertTrue(hub.contains("SetupSteps.next(facts, skipping: usernameState == UsernameState.none ? [.username] : [])"))
        XCTAssertTrue(hub.contains("NextStepCard(step: step) { go(StepPlace.of(step)) }"))
        // The facts reload on the profile, sync and the two preferences, on appearing, and on coming back.
        XCTAssertTrue(hub.contains(".task(id: factsKey) { await readFacts() }"))
        for part in ["profileRevision", "SyncStatus.shared.revision", "currencyRaw", "lockRaw"] {
            XCTAssertTrue(try slice(hub, from: "private var factsKey: String {", to: "\n    }\n").contains(part), part)
        }
        // S7: no second read on appearing (`.task(id:)` runs then), and an older read never overwrites a newer one.
        XCTAssertFalse(hub.contains(".onAppear { Task { await readFacts() } }"))
        let read = try slice(hub, from: "private func readFacts() async {", to: "\n    }\n")
        XCTAssertTrue(read.contains("let generation = factsReads.next()"))
        XCTAssertTrue(read.contains("if factsReads.isLatest(generation) { facts = read }"))
        XCTAssertTrue(hub.contains("if phase == .active { Task { await readFacts() } }"))
        // CLAUDE.md 10.1: the create-group sheet hangs on the always-present ScrollView, not on the card.
        XCTAssertFalse(card.contains(".sheet("))
        XCTAssertTrue(hub.contains(".sheet(isPresented: $creatingGroup)"))
    }
}
