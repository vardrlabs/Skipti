import CryptoKit
import UIKit
import XCTest
@testable import Skipti

/// D-146: About and the licence inventory. Android's twin is AboutTest.
final class AboutTests: XCTestCase {
    /// The inventory the app bundles is whole, and iOS shows nothing that
    /// only Android ships (OpenSSL, the Android libraries).
    func testTheBundledInventoryIsSoundAndShowsOnlyWhatIOSShips() throws {
        let inventory = try XCTUnwrap(Licences.load(), "licences.json is bundled and parses")
        XCTAssertEqual(Licences.problems(inventory), [])

        let sections = Licences.sections(inventory, shell: "ios")
        // D-191: the linked Swift packages (Firebase Auth's graph) have their own section.
        XCTAssertEqual(sections.map(\.group), ["Fonts and icons", "Shared core", "iPhone"])
        let shown = sections.flatMap(\.items)
        let names = Set(shown.map(\.name))
        XCTAssertGreaterThan(shown.count, 90, "the whole Rust core is listed, not a handful")
        for androidOnly in ["OpenSSL", "linux-raw-sys", "net.java.dev.jna:jna"] {
            XCTAssertFalse(names.contains(androidOnly), androidOnly + " is not in the iOS app")
        }
        XCTAssertFalse(shown.contains { $0.name.hasPrefix("com.google.") || $0.name.hasPrefix("androidx.") })
        XCTAssertTrue(names.contains("Sign in with Google button and G"), "iOS carries Google's button art")
        let firebase = try XCTUnwrap(shown.first { $0.name == "Firebase iOS SDK" })
        XCTAssertEqual(firebase.licence, "Apache-2.0")
        XCTAssertEqual(firebase.version, "12.19.2")

        let sqlcipher = try XCTUnwrap(shown.first { $0.name == "SQLCipher" })
        XCTAssertEqual(sqlcipher.licence, "BSD-3-Clause")
        XCTAssertTrue(Licences.texts(for: sqlcipher, in: inventory).joined().contains("Zetetic LLC"))
        for font in ["Space Grotesk", "IBM Plex Mono", "IBM Plex Sans"] {
            let c = try XCTUnwrap(shown.first { $0.name == font })
            XCTAssertTrue(Licences.texts(for: c, in: inventory).joined().contains("SIL OPEN FONT LICENSE"))
        }
        let uniffi = try XCTUnwrap(shown.first { $0.name == "uniffi" })
        XCTAssertEqual(uniffi.licence, "MPL-2.0")
        XCTAssertNotNil(uniffi.notice, "MPL-2.0 needs to say where the source is")
    }

    /// A broken inventory is caught: a missing text, a licence with no text,
    /// an unknown group and an unknown shell each name the component.
    func testProblemsNameEveryBrokenEntry() throws {
        let json = #"""
        {"format":1,"groups":["G"],"texts":{"k":"text"},"components":[
          {"group":"G","name":"ok","version":"1","licence":"MIT","shells":["ios"],"texts":["k"]},
          {"group":"G","name":"missing","version":"1","licence":"MIT","shells":["ios"],"texts":["nope"]},
          {"group":"G","name":"bare","version":"1","licence":"MIT","shells":["ios"],"texts":[]},
          {"group":"H","name":"stray","version":"1","licence":"MIT","shells":["ios"],"texts":["k"]},
          {"group":"G","name":"nowhere","version":"1","licence":"MIT","shells":["web"],"texts":["k"]},
          {"group":"G","name":"retired","version":"1","licence":"MIT","shells":["play","direct"],"texts":["k"]},
          {"group":"G","name":"sqlite","version":"1","licence":"Public domain","shells":["ios"],"texts":[]}
        ]}
        """#
        let inventory = try Licences.parse(Data(json.utf8))
        XCTAssertEqual(Licences.problems(inventory), [
            "missing: text nope is missing",
            "bare: no licence text for MIT",
            "stray: unknown group H",
            "nowhere: bad shells [\"web\"]",
            "retired: bad shells [\"play\", \"direct\"]",
        ])
    }

    func testSectionsKeepGroupOrderAndDropEmptyGroups() throws {
        let json = #"""
        {"format":1,"groups":["A","B","C"],"texts":{"k":"t"},"components":[
          {"group":"C","name":"c","version":"1","licence":"MIT","shells":["ios","play"],"texts":["k"]},
          {"group":"B","name":"b","version":"1","licence":"MIT","shells":["play"],"texts":["k"]},
          {"group":"A","name":"a","version":"1","licence":"MIT","shells":["ios"],"texts":["k"]}
        ]}
        """#
        let inventory = try Licences.parse(Data(json.utf8))
        let ios = Licences.sections(inventory, shell: "ios")
        XCTAssertEqual(ios.map(\.group), ["A", "C"])
        XCTAssertEqual(ios.flatMap(\.items).map(\.name), ["a", "c"])
        XCTAssertEqual(Licences.sections(inventory, shell: "web"), [])
    }

    /// Hard-wrapped licence lines are joined; paragraphs and list items stay
    /// apart. Expected output derived independently (.drafts/d146/reflow_golden.py).
    func testReflowJoinsWrappedLinesAndKeepsItems() throws {
        let source = "Copyright (c) 2024 A\nAll rights reserved.\n\n\nPermission is hereby\n  granted, free of charge.\n\n"
            + "   * Redistributions must\n     retain the notice.\n   * Neither the name\n\n"
            + "1. Definitions.\n   \"License\" shall mean\n   the terms.\n   (a) You must give\n   a copy.\n"
        XCTAssertEqual(
            Licences.reflow(source),
            "Copyright (c) 2024 A All rights reserved.\n\nPermission is hereby granted, free of charge.\n\n"
                + "* Redistributions must retain the notice.\n* Neither the name\n\n"
                + "1. Definitions. \"License\" shall mean the terms.\n(a) You must give a copy."
        )
        let apache = try XCTUnwrap(Licences.load()?.texts["Apache-2.0"])
        XCTAssertTrue(apache.contains("Version 2.0, January 2004"))
        XCTAssertEqual(
            Licences.reflow(apache).split(whereSeparator: \.isWhitespace),
            apache.split(whereSeparator: \.isWhitespace),
            "reflow moves line breaks and never a word"
        )
    }

    /// D-148: the faces resolve by the PostScript names the app asks for (a
    /// miss falls back to the system font silently), and Plex Mono is IBM's
    /// own release, byte for byte: a trimmed copy may not keep the reserved
    /// name "Plex" (SIL OFL 1.1). Hashes read off IBM's @ibm/plex-mono 2.5.0.
    func testTheBundledFontsResolveAndPlexIsIBMsOwnFiles() throws {
        for name in [TypeRole.grotesk, TypeRole.plexRegular, TypeRole.plexMedium] {
            let font = try XCTUnwrap(UIFont(name: name, size: 12), name + " does not resolve")
            XCTAssertEqual(font.fontName, name)
        }
        XCTAssertEqual(UIFont(name: TypeRole.plexMedium, size: 12)?.familyName, "IBM Plex Mono")
        let pinned = [
            "IBMPlexMono-Regular": "7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43",
            "IBMPlexMono-Medium": "98fbd727aae340b236955879dabed4d991aac9e8e90b3b2a67ce4a59221cc97c",
        ]
        for (file, sha) in pinned {
            let url = try XCTUnwrap(Bundle.main.url(forResource: file, withExtension: "ttf"), file)
            let digest = SHA256.hash(data: try Data(contentsOf: url)).map { String(format: "%02x", $0) }.joined()
            XCTAssertEqual(digest, sha, file + " is not IBM's file")
        }
        let plex = try XCTUnwrap(Licences.load()?.components.first { $0.name == "IBM Plex Mono" })
        XCTAssertEqual(plex.version, "2.005")
    }

    /// D-215: Plex Sans is IBM's own release too (@ibm/plex-sans 1.1.0, "Version 3.005"), byte for byte: the
    /// reserved name "Plex" forbids a modified copy (SIL OFL 1.1). Hashes read off IBM's files.
    func testPlexSansIsIBMsOwnFiles() throws {
        let pinned = [
            "IBMPlexSans-Regular": "975dcda37d80f038dcd143c22e33ca2d97a0cc5a929aace1c749153b0fe1afa5",
            "IBMPlexSans-Medium": "331c8639d7598b2cde62a911a71db195e30cb655cd6bdf2e324a7e984955f907",
            "IBMPlexSans-SemiBold": "a20caf8286023a6a7a85e40b1d2a4ae9fc3e3b1f9eda8f4c542dd4986af67bb1",
            "IBMPlexSans-Bold": "9e6c74a889a700d707613d24548fe4ffa6bc59559a0689d2cf9e133bdcdafb2f",
        ]
        for (file, sha) in pinned {
            let url = try XCTUnwrap(Bundle.main.url(forResource: file, withExtension: "ttf"), file + " is not bundled")
            let digest = SHA256.hash(data: try Data(contentsOf: url)).map { String(format: "%02x", $0) }.joined()
            XCTAssertEqual(digest, sha, file + " is not IBM's file")
        }
        for name in [TypeRole.sansRegular, TypeRole.sansMedium, TypeRole.sansSemiBold, TypeRole.sansBold] {
            let font = CTFontCreateWithName(name as CFString, 12, nil)
            let version = CTFontCopyName(font, kCTFontVersionNameKey) as String?
            XCTAssertEqual(version, "Version 3.005", name)
        }
        // The Licences page lists it, with the OFL text and the reserved name.
        let inventory = try XCTUnwrap(Licences.load())
        let entry = try XCTUnwrap(inventory.components.first { $0.name == "IBM Plex Sans" })
        XCTAssertEqual(entry.version, "3.005")
        XCTAssertEqual(entry.licence, "OFL-1.1")
        XCTAssertTrue((entry.notice ?? "").contains("Reserved Font Name \"Plex\""))
    }

    /// D-215: Vardr Runes Carved Regular is Vardr Labs' own file (release v1.000 of vardrlabs/fonts), byte for byte,
    /// with the reserved name "Vardr" in its copyright line, and the Licences page lists it.
    func testVardrRunesIsTheReleaseFile() throws {
        let sha = "6d61b17c4929925f5aeb58a4b5541c9524de0cb531ea0c055043b6267b04cf01"
        let url = try XCTUnwrap(Bundle.main.url(forResource: "VardrRunes-CarvedRegular", withExtension: "ttf"), "the rune font is not bundled")
        let digest = SHA256.hash(data: try Data(contentsOf: url)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(digest, sha)
        let font = CTFontCreateWithName(TypeRole.runes as CFString, 12, nil)
        XCTAssertEqual(CTFontCopyName(font, kCTFontVersionNameKey) as String?, "Version 1.000")
        XCTAssertTrue((CTFontCopyName(font, kCTFontCopyrightNameKey) as String? ?? "").contains("Reserved Font Name \"Vardr\""))
        let entry = try XCTUnwrap(try XCTUnwrap(Licences.load()).components.first { $0.name == "Vardr Runes" })
        XCTAssertEqual(entry.version, "1.000")
        XCTAssertEqual(entry.licence, "OFL-1.1")
        XCTAssertTrue((entry.notice ?? "").contains("Reserved Font Name \"Vardr\""))
    }

    /// D-215: no visible text draws in the phone's system face. `.font(.system(` is allowed only where its
    /// file is listed here with a reason, and the count per file is exact, so a new site, or one that moves
    /// away, fails until this table says so. `UIFont.systemFont` lives in TypeSystem.swift alone.
    func testNoVisibleTextUsesTheSystemFace() throws {
        let sf = "SF Symbol image (Image(systemName:)), not text"
        let emoji = "emoji glyphs: the emoji font draws them, fixed inside a tile"
        let allowed: [String: (Int, String)] = [
            // D-225: AppLock's lock is Lucide's (the lock screen) and People's cross Lucide's x, so both sites went.
            // D-225 Task 6: New group's clear cross is Lucide's x too, so GroupSheets.swift left the table.
            "ImportReview.swift": (1, sf),
            // D-224: AccountOwner's signed-in symbol became the drawn done check (DoneCheck), so one site is left.
            "AccountOwner.swift": (1, sf), "RemovalFlow.swift": (1, sf),
            "GroupPictures.swift": (1, emoji), "Profile.swift": (2, emoji), "GroupsScreen.swift": (1, emoji),
        ]
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Skipti/App")
        let files = try FileManager.default.contentsOfDirectory(at: app, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "swift" }
        var seen = Set<String>()
        for file in files {
            let name = file.lastPathComponent
            let text = try String(contentsOf: file, encoding: .utf8)
            let sites = text.components(separatedBy: "\n").filter { line in
                let code = line.components(separatedBy: "//").first ?? line
                return code.contains(".font(.system(")
            }.count
            XCTAssertEqual(sites, allowed[name]?.0 ?? 0, "\(name): .font(.system( sites outside the allowlist")
            if sites > 0 { seen.insert(name) }
        }
        XCTAssertEqual(seen, Set(allowed.keys), "an allowlisted file no longer has its site")

        // D-215 fix 1 and 2, fix wave: the other ways a Text slips back to the system face (systemFaceLeaks).
        var found: [String] = []
        for file in files {
            found += Self.systemFaceLeaks(file.lastPathComponent, try String(contentsOf: file, encoding: .utf8))
        }
        XCTAssertEqual(found, [], "use a TypeRole font")
    }

    /// The `.font(` arguments on one line, each to its closing bracket (or the line's end).
    private static func fontArguments(_ line: String) -> [Range<String.Index>] {
        var out: [Range<String.Index>] = []
        var search = line.startIndex..<line.endIndex
        while let open = line.range(of: ".font(", range: search) {
            var depth = 1
            var i = open.upperBound
            while i < line.endIndex {
                if line[i] == "(" { depth += 1 } else if line[i] == ")" { depth -= 1; if depth == 0 { break } }
                i = line.index(after: i)
            }
            out.append(open.upperBound..<i)
            search = open.upperBound..<line.endIndex
        }
        return out
    }

    /// D-215: every way a Text slips back to the phone's system face, one pure function so it is tried on samples as
    /// well as on the tree (fix wave: m5 and B1-m1). Lines are cut at `//`; TypeSystem.swift, where the system font is
    /// the fallback of a name that does not resolve, is exempt.
    /// - A text style is the system face at the system's size: `.font(.footnote)`, chained (`.font(.body.weight(.bold))`),
    ///   `Font.`-qualified anywhere (`Font.body`), or a leading-dot style inside a ternary `.font(` argument
    ///   (`.font(big ? .title : .body)`). It is allowed on an SF Symbol only: an `Image(systemName:` on the SAME line
    ///   before it, or on the line directly above, with no `Text(`, `Label(`, `Label {`, `Button(`, `icon:` or closing
    ///   `}` between the Image and the font (a `}` means the font is on the container: the brace form
    ///   `Label { Text } icon: { Image(systemName:) }`).
    /// - Never: `.font(nil)`; any `UIFont` system font (`systemFont`, `boldSystemFont`, `italicSystemFont`,
    ///   `monospacedSystemFont`, `monospacedDigitSystemFont`) or `preferredFont(`; `Font.system(`; `.fontWeight(` (it
    ///   cannot reach a static Plex cut); `.bold(` and `.italic(`, with or without an argument; `.monospaced()` and
    ///   `.monospacedDigit()`.
    static func systemFaceLeaks(_ name: String, _ text: String) -> [String] {
        if name == "TypeSystem.swift" { return [] }
        let styleNames = "body|caption2?|footnote|headline|subheadline|title[23]?|largeTitle|callout"
        let styles = try! NSRegularExpression(pattern: #"\.font\(\s*\.("# + styleNames + #")\b|\bFont\.("# + styleNames + #")\b"#)
        let leadingStyle = try! NSRegularExpression(pattern: #"(?<![A-Za-z0-9_)\]])\.("# + styleNames + #")\b"#)
        let systemFonts = try! NSRegularExpression(pattern: #"[sS]ystemFont\(|UIFont\.systemFont|preferredFont\("#)
        let voids = ["Text(", "Label(", "Label {", "Label{", "Button(", "icon:", "}"]
        let lines = text.components(separatedBy: "\n").map { $0.components(separatedBy: "//").first ?? $0 }
        var out: [String] = []
        for (i, line) in lines.enumerated() {
            let at = "\(name):\(i + 1)"
            let whole = NSRange(line.startIndex..., in: line)
            var hits = styles.matches(in: line, range: whole).compactMap { Range($0.range, in: line) }
            for arg in fontArguments(line) {
                if line[arg].trimmingCharacters(in: .whitespaces) == "nil" { out.append(at + " uses .font(nil)") }
                if line[arg].contains("?") {
                    hits += leadingStyle.matches(in: line, range: NSRange(arg, in: line)).compactMap { Range($0.range, in: line) }
                }
            }
            for hit in hits {
                var between: String?
                if let image = line.range(of: "Image(systemName:"), image.lowerBound < hit.lowerBound {
                    between = String(line[image.upperBound..<hit.lowerBound])
                } else if i > 0, let image = lines[i - 1].range(of: "Image(systemName:") {
                    between = String(lines[i - 1][image.upperBound...]) + String(line[..<hit.lowerBound])
                }
                let onASymbol = between.map { b in !voids.contains { b.contains($0) } } ?? false
                if !onASymbol { out.append(at + " a text style on a Text") }
            }
            if systemFonts.firstMatch(in: line, range: whole) != nil { out.append(at + " draws the system font") }
            for form in ["Font.system(", ".fontWeight(", ".bold(", ".italic(", ".monospaced()", ".monospacedDigit()"] where line.contains(form) {
                out.append(at + " uses " + form)
            }
        }
        return out
    }

    func testTheSystemFaceGuardFlagsEveryFormItClaimsTo() {
        let bad = [
            "Text(\"a\").font(.footnote)",
            "Text(\"a\").font(.body.weight(.bold))",
            "Text(\"a\").font( .caption2)",
            "Text(\"a\").font(Font.body)",
            "Text(\"a\").font(SwiftUI.Font.title3)",
            "let f: Font = Font.headline",
            "Text(\"a\").font(big ? .title : .body)",
            "Text(\"a\").font(big ? TypeRole.body() : .caption)",
            "Text(\"a\").font(nil)",
            "let f = UIFont.systemFont(ofSize: 12)",
            "let f = UIFont.boldSystemFont(ofSize: 12)",
            "let f = UIFont.italicSystemFont(ofSize: 12)",
            "let f = UIFont.monospacedSystemFont(ofSize: 12, weight: .regular)",
            "let f = UIFont.monospacedDigitSystemFont(ofSize: 12, weight: .regular)",
            "let f = UIFont.preferredFont(forTextStyle: .body)",
            "let f = .systemFont(ofSize: 12)",
            "Text(\"a\").font(Font.system(size: 12))",
            "Text(\"a\").fontWeight(.bold)",
            "Text(\"a\").bold()",
            "Text(\"a\").bold(true)",
            "Text(\"a\").italic()",
            "Text(\"a\").italic(isOn)",
            "Text(\"a\").monospaced()",
            "Text(\"a\").monospacedDigit()",
            "Image(systemName: \"x\")\nText(\"a\").font(.footnote)",
            "Image(systemName: \"x\")\n\n    .font(.footnote)",
            "Label { Text(\"a\") } icon: { Image(systemName: \"x\") }\n.font(.footnote)",
            "HStack { Image(systemName: \"x\") }.font(.footnote)",
            "Image(systemName: \"x\"); Text(\"a\").font(.footnote)",
        ]
        for sample in bad { XCTAssertFalse(Self.systemFaceLeaks("Sample.swift", sample).isEmpty, "flags: " + sample) }
        let good = [
            "Image(systemName: \"x\").font(.footnote)",
            "Image(systemName: \"x\")\n    .font(.title3)",
            "Image(systemName: \"x\").font(big ? .title : .body)",
            "Text(\"a\").font(TypeRole.body())",
            "Text(\"a\").font(TypeRole.rune(13, relativeTo: .footnote))",
            "Text(\"a\").font(big ? TypeRole.body() : TypeRole.label())",
            "// Text(\"a\").font(.footnote) in a comment",
            "let m = UIFontMetrics(forTextStyle: .body)",
            "Text(\"a\").font(.system(size: 20))",
        ]
        for sample in good { XCTAssertEqual(Self.systemFaceLeaks("Sample.swift", sample), [], "passes: " + sample) }
        XCTAssertEqual(Self.systemFaceLeaks("TypeSystem.swift", "let f = UIFont.systemFont(ofSize: 12)"), [])
    }

    /// The About copy is Android's, word for word: both pin the same hash,
    /// derived independently (.drafts/d146/copy_golden.py; D-196: .drafts/d196/copy_golden.py; D-197: .drafts/d197/copy_golden.py).
    func testTheAboutCopyMatchesAndroid() {
        XCTAssertEqual(AboutInfo.promises.count, 7, "GUARANTEES.md has seven")
        let joined = (AboutInfo.promises + AboutInfo.privacy + [
            AboutInfo.securityNote, AboutInfo.disclaimer, AboutInfo.maker, AboutInfo.securityEmail,
        ]).joined(separator: "\n")
        let digest = SHA256.hash(data: Data(joined.utf8)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(digest, "9218fdd5c098d5adbb7463c821a20031a84ff65b9a03227f6bc8fbbdb9b4d1ae")
        XCTAssertEqual(AboutInfo.versionLine(app: "0.1.0", build: "1"), "Version 0.1.0 (1)")
        XCTAssertEqual(AboutInfo.coreLine("0.1.0"), "Core 0.1.0")
        let current = AboutInfo.current()
        XCTAssertNotEqual(current.app, "?")
        XCTAssertNotEqual(current.build, "?")
    }

    /// D-176: Info.plist hard-coded "0.1.0" and build "1", so a version set in
    /// Xcode never reached the app. It now reads the build settings; this
    /// fails on an unexpanded "$(MARKETING_VERSION)" or an empty value.
    func testTheVersionComesFromTheBuildSettings() {
        let (app, build) = AboutInfo.current()
        XCTAssertNotNil(app.range(of: #"^\d+\.\d+\.\d+$"#, options: .regularExpression), app)
        XCTAssertNotNil(build.range(of: #"^\d+$"#, options: .regularExpression), build)
    }

    /// D-220 (L-D217-5): About, Licences and a licence's page sit on the theme's art ground (the curtains, the runes,
    /// the grid), as every other page does and as Android's twins already did; none keeps the flat page colour.
    func testAboutPagesSitOnTheArt() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/About.swift")
        let source = try String(contentsOf: url, encoding: .utf8)
        XCTAssertEqual(source.components(separatedBy: ".auroraBackground()").count - 1, 3, "About, Licences, a licence")
        XCTAssertFalse(source.contains(".background(Tokens.bg"), "a flat page ground")
    }

    // ---- D-225 Task 4: About, Licences and a licence's page (source guards) ----

    /// D-225 (Design 6; L71): About's cards and a licence's text panels are glass cards (no panel of their own on the
    /// surface colour), its section labels stand alone (the hairline went), and a licence's page draws no title of its
    /// own (the bar names it, D-217). Sizes from the table: the wordmark, prose and footnote.
    func testAboutUsesGlassCards() throws {
        let about = try s2Source("About.swift")
        XCTAssertFalse(about.contains("panelShape("), "no panel of its own")
        XCTAssertFalse(about.contains("Tokens.hairline"), "no section hairline")
        let card = try s2Slice(about, from: "private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {", to: "\n}\n")
        XCTAssertTrue(card.contains(".glassCard()"), card)
        let section = try s2Slice(about, from: "private func aboutSection<Content: View>(", to: "\n}\n")
        s2InOrder(section, ["VStack(alignment: .leading, spacing: 8) {", "aboutLabel(title)", "card(content)"])
        let label = try s2Slice(about, from: "private func aboutLabel(_ title: String) -> some View {", to: "\n}\n")
        XCTAssertTrue(label.contains(".sectionLabel()"), label)
        let detail = try s2Slice(about, from: "struct LicenceDetailScreen: View {", to: "\n}\n")
        for needle in [".glassCard(padding: 12)", ".font(TypeRole.body(TypeScale.footnote))", ".navigationTitle(component.name)"] {
            XCTAssertTrue(detail.contains(needle), "a licence's page: \(needle)")
        }
        for banned in ["display(24)", "TypeRole.display(", "Tokens.surface", "body(13)"] {
            XCTAssertFalse(detail.contains(banned), "a licence's page: \(banned)")
        }
        XCTAssertTrue(about.contains(".font(TypeRole.display(TypeScale.wordmark))"))
        for banned in ["display(32)", "body(15)"] {
            XCTAssertFalse(about.contains(banned), "About.swift: \(banned)")
        }
        XCTAssertGreaterThanOrEqual(s2Count(about, ".font(TypeRole.body(TypeScale.prose))"), 5, "About's text and the Licences intro")
        for title in ["Promises", "Privacy", "Security", "Legal"] {
            XCTAssertEqual(s2Count(about, "aboutSection(\"\(title)\") {"), 1, title)
        }
    }

    /// D-225 (Design 6; L73): Licences is, per section, its label and one list card of list rows (the name over the
    /// version and the licence through the theme's separator, the chevron), never a hand-drawn row with a hairline.
    func testLicencesRowsAreListRows() throws {
        let about = try s2Source("About.swift")
        let list = try s2Slice(about, from: "struct LicencesScreen: View {", to: "\n}\n")
        s2InOrder(list, ["ForEach(Licences.sections(inventory, shell: Licences.shell), id: \\.group) { section in",
                         "aboutLabel(section.group)", "ListCard {", "ForEach(section.items) { item in", "NavigationLink {",
                         "ListRow(title: item.name, parts: LicenceRows.parts(item), trailing: .chevron)", ".buttonStyle(.plain)"])
        XCTAssertFalse(about.contains("struct LicenceRow:"), "the hand-drawn row is gone")
        XCTAssertFalse(about.contains("Separator.meta"), "the summary is joined on screen, never in Swift")
        let component = LicenceComponent(group: "g", name: "Plex", version: "1.0", licence: "OFL-1.1", offered: nil,
                                         shells: ["ios"], notice: nil, texts: [])
        XCTAssertEqual(LicenceRows.parts(component), ["1.0", "OFL-1.1"])
        let bare = LicenceComponent(group: "g", name: "Runes", version: "", licence: "OFL-1.1", offered: nil,
                                    shells: ["ios"], notice: nil, texts: [])
        XCTAssertEqual(LicenceRows.parts(bare), ["OFL-1.1"], "no empty version part")
    }
}

// D-225 Task 4: source guards over the app's files (no new test file; each file keeps its own helpers).
fileprivate func s2Source(_ file: String) throws -> String {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Skipti/App/\(file)")
    return try String(contentsOf: url, encoding: .utf8)
}

/// The text between the first `from` and the next `to` after it; a missing anchor fails the test, never crashes.
fileprivate func s2Slice(_ text: String, from: String, to: String) throws -> String {
    let after = try XCTUnwrap(text.components(separatedBy: from).dropFirst().first, "anchor \(from)")
    return try XCTUnwrap(after.components(separatedBy: to).first, "anchor \(to)")
}

fileprivate func s2Count(_ text: String, _ needle: String) -> Int { text.components(separatedBy: needle).count - 1 }

/// Each needle found, in this order.
fileprivate func s2InOrder(_ text: String, _ needles: [String], file: StaticString = #filePath, line: UInt = #line) {
    var from = text.startIndex
    for needle in needles {
        guard let at = text.range(of: needle, range: from..<text.endIndex) else {
            XCTFail("not found in order: \(needle)", file: file, line: line)
            return
        }
        from = at.upperBound
    }
}
