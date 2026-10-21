import SwiftUI

/// D-146: About, and the licences of everything SKIPTI is built from, the
/// twin of Android's `About.kt`. The founder (2026-09-21) asked for "things
/// an actual app usually has": the version, who makes it, what it promises,
/// what it does with your data, where to report a security problem, and
/// the licences.
///
/// Every privacy sentence passes SPEC §14.6: true given the threat model,
/// still true if the reader is a malicious operator, and it states its own
/// limit next to it (the metadata the server learns, THREAT_MODEL.md).
enum AboutInfo {
    static let maker = "Made by Vardr Labs"
    static let securityEmail = "security@vardrlabs.com"

    /// GUARANTEES.md 1 to 7, in its order, shortened for a phone screen.
    static let promises = [
        "No subscription, no ads, no telemetry, no analytics. Ever.",
        "No limit on logging expenses: no caps, meters or waiting.",
        "No feature is behind a payment. There is no paid tier.",
        "Export all your data, in an open format, any time, in one tap.",
        "Your own ledger works fully offline and without an account. You are never pushed to make one; sharing a group with others needs one.",
        "The app talks only to SKIPTI's cloud, which runs on Google Firebase, and to Apple or Google to sign you in and deliver notifications.",
        "Only you can read your ledger, and only a group's members can read the group. It is not end-to-end encrypted.",
    ]

    static let privacy = [
        "Your ledger is kept on this phone in an encrypted database. Anyone who can unlock this phone can open SKIPTI.",
        "Without an account, SKIPTI never sends your ledger anywhere. It leaves this phone only when you export it.",
        "With an account, your ledger and your groups are kept in SKIPTI's cloud on Google Firebase, in the United States, so your other phones and your groups' members get them. Only you, and each group's members, can read them. They are encrypted in transit and at rest, but not end to end: Google, as the host, and Vardr Labs, as the operator, could technically access them.",
        "If you sign in with Google or Apple, they learn that you use SKIPTI.",
        // D-196: push (the Firebase Installations ID behind FCM's token).
        "With an account and sync on, Firebase gives this phone an address for notifications, kept with your account. Each notification says who added, changed or deleted which expense and the amount, and Apple or Google delivers it.",
        // D-197: being found, and contacts matching.
        "Unless you switch it off in Settings, people who know your username or email can find you. If you use Find friends in contacts, each contact's email becomes a fingerprint on this phone; only the fingerprints are sent, and SKIPTI's cloud keeps none.",
        "SKIPTI has no ads, trackers or analytics, and it does not report what you do in the app.",
    ]

    static let securityNote = "Found a security problem? Email us. We acknowledge every report within 48 hours."

    /// SPEC §13.5: an honest disclaimer.
    static let disclaimer = "SKIPTI is not a bank, an accountant or a tax adviser, and nothing in it is "
        + "financial advice. It is provided as is, without warranty of any kind."

    static func versionLine(app: String, build: String) -> String {
        "Version \(app) (\(build))"
    }

    static func coreLine(_ core: String) -> String {
        "Core \(core)"
    }

    /// This build's marketing version and build number, from Info.plist.
    static func current(_ bundle: Bundle = .main) -> (app: String, build: String) {
        let info = bundle.infoDictionary ?? [:]
        return (
            info["CFBundleShortVersionString"] as? String ?? "?",
            info["CFBundleVersion"] as? String ?? "?"
        )
    }
}

// MARK: - The licence inventory (scripts/licences.py writes it)

struct LicenceComponent: Codable, Equatable, Identifiable {
    let group: String
    let name: String
    let version: String
    let licence: String
    let offered: String?
    let shells: [String]
    let notice: String?
    let texts: [String]

    var id: String { group + "/" + name + "@" + version }
}

struct LicenceInventory: Codable, Equatable {
    let format: Int
    let groups: [String]
    let components: [LicenceComponent]
    let texts: [String: String]
}

struct LicenceSection: Equatable {
    let group: String
    let items: [LicenceComponent]
}

enum Licences {
    /// This shell's name in the inventory's `shells` lists.
    static let shell = "ios"

    /// Licences that carry no text to show: SQLite is public domain, and
    /// Google's artwork and libraries come under Google's own terms, which
    /// the entry names.
    static let textless: Set<String> = [
        "Public domain", "Google brand asset", "Android Software Development Kit License",
        // D-191: Google libraries Firebase Auth pulls, under Google's own terms
        // (named with a link, as the Android SDK licence is).
        "Play Core Software Development Kit Terms of Service", "Play Integrity API Terms of Service",
    ]

    static func parse(_ data: Data) throws -> LicenceInventory {
        try JSONDecoder().decode(LicenceInventory.self, from: data)
    }

    static func load(_ bundle: Bundle = .main) -> LicenceInventory? {
        guard let url = bundle.url(forResource: "licences", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? parse(data)
    }

    /// What one shell shows, in the inventory's group order, empty groups left out.
    static func sections(_ inventory: LicenceInventory, shell: String) -> [LicenceSection] {
        inventory.groups.compactMap { group in
            let items = inventory.components.filter { $0.group == group && $0.shells.contains(shell) }
            return items.isEmpty ? nil : LicenceSection(group: group, items: items)
        }
    }

    static func texts(for component: LicenceComponent, in inventory: LicenceInventory) -> [String] {
        component.texts.compactMap { inventory.texts[$0] }
    }

    /// Licence files are hard-wrapped at about 80 columns; on a phone the
    /// screen wraps them again and every line breaks twice. This joins the
    /// lines of a paragraph, and keeps paragraphs and list items (`*`, `-`,
    /// `1.`, `(a)`) on their own lines. The words are untouched.
    static func reflow(_ text: String) -> String {
        var paragraphs: [String] = []
        var lines: [String] = []
        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: CharacterSet(charactersIn: " \t"))
            if line.isEmpty {
                if !lines.isEmpty {
                    paragraphs.append(lines.joined(separator: "\n"))
                    lines = []
                }
                continue
            }
            if lines.isEmpty || line.range(of: itemPattern, options: .regularExpression) != nil {
                lines.append(line)
            } else {
                lines[lines.count - 1] += " " + line
            }
        }
        if !lines.isEmpty { paragraphs.append(lines.joined(separator: "\n")) }
        return paragraphs.joined(separator: "\n\n")
    }

    private static let itemPattern = #"^(\*|-|•|\(?[0-9A-Za-z]{1,3}[.)])\s"#

    /// Everything wrong with an inventory; empty when it is sound.
    static func problems(_ inventory: LicenceInventory) -> [String] {
        var out: [String] = []
        let known: Set<String> = ["ios", "play"]
        for c in inventory.components {
            for key in c.texts where inventory.texts[key] == nil {
                out.append("\(c.name): text \(key) is missing")
            }
            if c.texts.isEmpty && !textless.contains(c.licence) {
                out.append("\(c.name): no licence text for \(c.licence)")
            }
            if !inventory.groups.contains(c.group) {
                out.append("\(c.name): unknown group \(c.group)")
            }
            if c.shells.isEmpty || !Set(c.shells).isSubset(of: known) {
                out.append("\(c.name): bad shells \(c.shells)")
            }
        }
        return out
    }
}

// MARK: - Screens

struct AboutScreen: View {
    @Environment(\.openURL) private var openURL
    @State private var mailError = false
    /// D-245 (decision 162): the welcome again, without touching its first-run flag.
    @State private var replaying = false
    @State private var replayExit: WelcomeExit?

    var body: some View {
        let version = AboutInfo.current()
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                card {
                    Text(verbatim: "SKIPTI")
                        .font(TypeRole.display(TypeScale.wordmark))
                        .foregroundStyle(Tokens.text)
                    Text(AboutInfo.maker)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.text)
                    Text(verbatim: AboutInfo.versionLine(app: version.app, build: version.build))
                        .font(TypeRole.numeric(14))
                        .foregroundStyle(Tokens.textDim)
                    Text(verbatim: AboutInfo.coreLine(skiptiCoreVersion()))
                        .font(TypeRole.numeric(14))
                        .foregroundStyle(Tokens.textDim)
                }

                // D-245 (decision 162): one row under the version card; no summary (the settings width tables untouched).
                ListCard {
                    Button { replaying = true } label: {
                        ListRow(title: WelcomeCopy.replay, trailing: .chevron)
                    }
                    .buttonStyle(.plain)
                }

                aboutSection("Promises") {
                    ForEach(Array(AboutInfo.promises.enumerated()), id: \.offset) { index, promise in
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Text(verbatim: "\(index + 1)")
                                .font(TypeRole.numeric(14))
                                .foregroundStyle(Tokens.textDim)
                                .frame(minWidth: 14, alignment: .leading)
                                .accessibilityHidden(true)
                            Text(verbatim: promise)
                                .font(TypeRole.body(TypeScale.prose))
                                .foregroundStyle(Tokens.text)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }

                aboutSection("Privacy") {
                    ForEach(AboutInfo.privacy, id: \.self) { line in
                        Text(verbatim: line)
                            .font(TypeRole.body(TypeScale.prose))
                            .foregroundStyle(Tokens.text)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                aboutSection("Security") {
                    Text(verbatim: AboutInfo.securityNote)
                        .font(TypeRole.body(TypeScale.prose))
                        .foregroundStyle(Tokens.text)
                    Text(verbatim: AboutInfo.securityEmail)
                        .font(TypeRole.numeric(14))
                        .foregroundStyle(Tokens.textDim)
                        .textSelection(.enabled)
                    Button {
                        guard let mail = URL(string: "mailto:" + AboutInfo.securityEmail) else { return }
                        openURL(mail) { accepted in mailError = !accepted }
                    } label: {
                        Text(verbatim: "Report a security problem")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    if mailError {
                        Text(verbatim: "No email app is set up on this phone. Write to "
                            + AboutInfo.securityEmail + ".")
                            .font(TypeRole.body(14))
                            .foregroundStyle(Tokens.owed)
                    }
                }

                aboutSection("Legal") {
                    Text(verbatim: AboutInfo.disclaimer)
                        .font(TypeRole.body(TypeScale.prose))
                        .foregroundStyle(Tokens.text)
                        .fixedSize(horizontal: false, vertical: true)
                    NavigationLink {
                        LicencesScreen()
                    } label: {
                        Text(verbatim: "Licences")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
        .fullScreenCover(isPresented: $replaying, onDismiss: {
            WelcomeLook.after(replayExit)
            replayExit = nil
        }) {
            WelcomeView(store: LedgerStore.defaultStore(), mode: .replay) { exit in
                replayExit = exit
                WelcomeGate.closed(mode: .replay) { try WelcomeGate.clear(LedgerStore.defaultStore()) }
                replaying = false
            }
        }
    }
}

struct LicencesScreen: View {
    @State private var inventory: LicenceInventory?
    @State private var loaded = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                Text(
                    "SKIPTI is built on other people's work. These are the fonts, drawings and "
                        + "libraries in this app, and the licences they come under. Tap one to read it."
                )
                .font(TypeRole.body(TypeScale.prose))
                .foregroundStyle(Tokens.textDim)
                .fixedSize(horizontal: false, vertical: true)

                if let inventory {
                    // D-225 (Design 6; L73): per section its label and one list card of list rows (the name over version
                    // and licence, the chevron), 20 under what is above it, as Android's one lazy item per section.
                    ForEach(Licences.sections(inventory, shell: Licences.shell), id: \.group) { section in
                        VStack(alignment: .leading, spacing: 8) {
                            aboutLabel(section.group)
                            ListCard {
                                ForEach(section.items) { item in
                                    NavigationLink {
                                        LicenceDetailScreen(component: item, texts: Licences.texts(for: item, in: inventory))
                                    } label: {
                                        ListRow(title: item.name, parts: LicenceRows.parts(item), trailing: .chevron)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                        .padding(.top, 20)
                    }
                } else if loaded {
                    Text("The licence list could not be read.")
                        .font(TypeRole.body(TypeScale.prose))
                        .foregroundStyle(Tokens.owed)
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        .navigationTitle("Licences")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            inventory = Licences.load()
            loaded = true
        }
    }
}

/// D-225 (Design 6): a licence row's summary: the version (when there is one) and the licence, joined on screen by the
/// theme's separator. Android's twin builds the same list in About.kt.
enum LicenceRows {
    static func parts(_ component: LicenceComponent) -> [String] {
        (component.version.isEmpty ? [] : [component.version]) + [component.licence]
    }
}

struct LicenceDetailScreen: View {
    let component: LicenceComponent
    let texts: [String]

    var body: some View {
        ScrollView {
            // D-225 (Design 6): the bar names the page (D-217: a pushed page draws no header of its own).
            VStack(alignment: .leading, spacing: 12) {
                if !component.version.isEmpty {
                    Text(verbatim: "Version " + component.version)
                        .font(TypeRole.numeric(14))
                        .foregroundStyle(Tokens.textDim)
                }
                Text(verbatim: "Licence: " + component.licence)
                    .font(TypeRole.body(TypeScale.prose))
                    .foregroundStyle(Tokens.text)
                if let offered = component.offered, offered != component.licence {
                    Text(verbatim: "Offered under " + offered + ". SKIPTI uses it under " + component.licence + ".")
                        .font(TypeRole.body(14))
                        .foregroundStyle(Tokens.textDim)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let notice = component.notice {
                    Text(verbatim: notice)
                        .font(TypeRole.body(TypeScale.prose))
                        .foregroundStyle(Tokens.text)
                        .fixedSize(horizontal: false, vertical: true)
                }
                // D-225 (Design 6, L71): the licence text on a glass card padded 12, at the footnote size.
                ForEach(Array(texts.enumerated()), id: \.offset) { _, text in
                    Text(verbatim: Licences.reflow(text))
                        .font(TypeRole.body(TypeScale.footnote))
                        .foregroundStyle(Tokens.textDim)
                        .fixedSize(horizontal: false, vertical: true)
                        .textSelection(.enabled)
                        .glassCard(padding: 12)
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .auroraBackground()
        .navigationTitle(component.name)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Shared pieces (the section label and card)

/// D-225 (Design 2, L70): a section's label alone (the hairline under it went), 8 above its card, 20 under what is above.
private func aboutLabel(_ title: String) -> some View {
    Text(verbatim: title)
        .sectionLabel()
        .foregroundStyle(Tokens.textDim)
        .accessibilityAddTraits(.isHeader)
        .accessibilityLabel(title)
}

/// About's section: its label 8 above its glass card, 20 under the card above (the page spaces 16, plus 4).
private func aboutSection<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
    VStack(alignment: .leading, spacing: 8) {
        aboutLabel(title)
        card(content)
    }
    .padding(.top, 4)
}

/// D-225 (Design 6, L71): About's cards are glass cards, as every other page's.
private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
    VStack(alignment: .leading, spacing: 10) { content() }
        .glassCard()
}
