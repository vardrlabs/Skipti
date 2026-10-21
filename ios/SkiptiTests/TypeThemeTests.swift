import UIKit
import XCTest
@testable import Skipti

/// D-212 (ADR-0041): each theme's faces (the plan's table), the bundled
/// Cormorant SC, and the text size reaching the fonts. Android's twin is
/// TypeThemeTest.
final class TypeThemeTests: XCTestCase {
    func testEachRoleDrawsItsThemesFace() {
        let expected: [TypeRole.Role: [AppTheme: TypeRole.Face]] = [
            .display: [.aurora: .grotesk, .norse: .cormorant, .vardr: .grotesk],
            .body: [.aurora: .sans, .norse: .sans, .vardr: .grotesk],
            .numeric: [.aurora: .grotesk, .norse: .grotesk, .vardr: .plex],
            .label: [.aurora: .sans, .norse: .sans, .vardr: .plex],
            .microLabel: [.aurora: .sans, .norse: .cormorant, .vardr: .plex],
        ]
        for role in TypeRole.Role.allCases {
            for theme in AppTheme.allCases {
                XCTAssertEqual(TypeRole.face(role, theme: theme), expected[role]?[theme], "\(role) \(theme)")
            }
        }
    }

    /// F5: a tab's label is the BODY face in every theme (the Look Book's tabs use the body face), never
    /// uppercase or tracked; the bar's file no longer sets its label through `microLabelFont`.
    func testTheTabLabelDrawsTheBodyFaceInEveryTheme() throws {
        for theme in AppTheme.allCases {
            XCTAssertEqual(TypeRole.tabFace(theme: theme), TypeRole.face(.body, theme: theme), "\(theme)")
        }
        let bar = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Skipti/App/SkiptiTabBar.swift")
        let text = try String(contentsOf: bar, encoding: .utf8)
        XCTAssertTrue(text.contains("TypeRole.tabLabel("), "the tab bar draws its labels with TypeRole.tabLabel")
        XCTAssertFalse(text.contains("microLabelFont"), "the tab bar still uses microLabelFont")
    }

    /// O-1: `numeric` takes the text style it scales against (a hero amount scales like a large title).
    func testNumericScalesRelativeToTheStyleItIsGiven() {
        let state = AppearanceState.shared
        let before = state.textStep
        defer { state.textStep = before }
        state.textStep = 2
        let category = state.contentSizeCategory
        let large = TypeRole.scaledSize(44, relativeTo: .largeTitle, category: category)
        let body = TypeRole.scaledSize(44, relativeTo: .body, category: category)
        XCTAssertNotEqual(large, body, accuracy: 0.001, "the two styles scale differently, so the parameter is observable")
    }

    /// O-1 (fix wave 4): the font `numeric` really draws is scaled against the style it is given, not a fixed one.
    func testNumericDrawsAtTheScaleOfTheStyleItIsGiven() {
        let category = UIContentSizeCategory.accessibilityExtraExtraExtraLarge
        for theme in AppTheme.allCases {
            let large = TypeRole.numericUIFont(44, weight: 400, relativeTo: .largeTitle, category: category, theme: theme)
            let body = TypeRole.numericUIFont(44, weight: 400, relativeTo: .body, category: category, theme: theme)
            XCTAssertNotEqual(large.pointSize, body.pointSize, accuracy: 0.01, "\(theme): the style is observable in the drawn font")
            XCTAssertEqual(large.pointSize, TypeRole.scaledSize(44, relativeTo: .largeTitle, category: category), accuracy: 0.01, "\(theme) largeTitle")
            XCTAssertEqual(body.pointSize, TypeRole.scaledSize(44, relativeTo: .body, category: category), accuracy: 0.01, "\(theme) body")
        }
    }

    /// A name that does not resolve falls back to the system font without a
    /// word (D-148), so the bundled names are resolved here.
    func testCormorantSCIsBundledUnderTheNamesTypeRoleUses() throws {
        let semi = try XCTUnwrap(UIFont(name: TypeRole.cormorantSemiBold, size: 17))
        let bold = try XCTUnwrap(UIFont(name: TypeRole.cormorantBold, size: 17))
        XCTAssertEqual(semi.fontName, "CormorantSC-SemiBold")
        XCTAssertEqual(bold.fontName, "CormorantSC-Bold")
    }

    /// D-215: a name that does not resolve falls back to the system font without a word (D-148), so the four
    /// Plex Sans names are resolved here, to IBM's family.
    func testEverySansNameResolvesToTheBundledFile() throws {
        for name in [TypeRole.sansRegular, TypeRole.sansMedium, TypeRole.sansSemiBold, TypeRole.sansBold] {
            let font = try XCTUnwrap(UIFont(name: name, size: 17), name + " does not resolve")
            XCTAssertEqual(font.fontName, name)
            XCTAssertEqual(font.familyName, "IBM Plex Sans", name)
        }
        XCTAssertEqual(TypeRole.sansRegular, "IBMPlexSans")
        XCTAssertEqual(TypeRole.sansMedium, "IBMPlexSans-Medm")
        XCTAssertEqual(TypeRole.sansSemiBold, "IBMPlexSans-SmBld")
        XCTAssertEqual(TypeRole.sansBold, "IBMPlexSans-Bold")
    }

    /// D-215: the buckets are the system face's own (>= 650 Bold, >= 550 SemiBold, >= 450 Medium, else Regular).
    func testSansWeightsUseTheSystemBuckets() {
        let expected: [(CGFloat, String)] = [
            (400, TypeRole.sansRegular), (449, TypeRole.sansRegular),
            (450, TypeRole.sansMedium), (549, TypeRole.sansMedium),
            (550, TypeRole.sansSemiBold), (649, TypeRole.sansSemiBold),
            (650, TypeRole.sansBold), (700, TypeRole.sansBold),
        ]
        for (weight, name) in expected {
            XCTAssertEqual(TypeRole.sansName(weight: weight), name, "weight \(weight)")
        }
    }

    /// D-215: the roles that were the system face now draw Plex Sans, at every weight a role asks for, tabular or not.
    func testTheSansFaceDrawsPlexSans() {
        for (weight, name) in [(CGFloat(400), TypeRole.sansRegular), (500, TypeRole.sansMedium), (600, TypeRole.sansSemiBold), (700, TypeRole.sansBold)] {
            for tabular in [false, true] {
                let font = TypeRole.base(.sans, size: 17, weight: weight, tabular: tabular)
                XCTAssertEqual(font.fontName, name, "weight \(weight) tabular \(tabular)")
                XCTAssertEqual(font.pointSize, 17)
            }
        }
    }

    /// D-215 fix 1: `bodyFixed` is the body face at a size that does NOT follow the text size (the slider's two
    /// A's show the range), while `body` does.
    func testBodyFixedDoesNotScale() {
        let before = AppearanceState.shared.textStep
        defer { AppearanceState.shared.textStep = before }
        AppearanceState.shared.textStep = -2
        let smallFixed = TypeRole.bodyFixed(13), smallBody = TypeRole.body(13)
        AppearanceState.shared.textStep = 3
        XCTAssertEqual(TypeRole.bodyFixed(13), smallFixed, "a fixed size moved with the text size")
        XCTAssertNotEqual(TypeRole.body(13), smallBody, "the scaled body did not move with the text size")
        XCTAssertEqual(TypeRole.bodyFixedUIFont(13, theme: .aurora).pointSize, 13)
        XCTAssertEqual(TypeRole.bodyFixedUIFont(13, theme: .aurora).familyName, "IBM Plex Sans")
    }

    /// D-222 (L65): the slider's large A is the body face at 700, the small one at 400 (the default), both at their fixed
    /// size whatever the text size: the largest step at the phone's largest category draws the same font. Fix wave (S9):
    /// Vardr Labs' body face is Space Grotesk, one variable font whose name never names a weight, so its weight is read
    /// off the font's wght axis (700 and 400); and a scaled role at AX3 is larger where the fixed face is not.
    func testTheFixedFaceTakesAWeight() throws {
        for theme in AppTheme.allCases {
            let face = TypeRole.face(.body, theme: theme)
            let bold = TypeRole.bodyFixedUIFont(TypeScale.rangeLarge, weight: 700, theme: theme)
            XCTAssertEqual(bold.fontName, TypeRole.base(face, size: 23, weight: 700, tabular: false).fontName, "\(theme)")
            XCTAssertEqual(bold.pointSize, 23, "\(theme)")
            let regular = TypeRole.bodyFixedUIFont(TypeScale.rangeSmall, theme: theme)
            XCTAssertEqual(regular.fontName, TypeRole.base(face, size: 14, weight: 400, tabular: false).fontName, "\(theme)")
            XCTAssertEqual(regular.pointSize, 14, "\(theme)")
            switch theme {
            case .aurora, .norse:
                XCTAssertEqual(bold.fontName, TypeRole.sansBold, "\(theme)")
                XCTAssertEqual(regular.fontName, TypeRole.sansRegular, "\(theme)")
            case .vardr:
                XCTAssertTrue(bold.fontName.hasPrefix("SpaceGrotesk"), bold.fontName)
                for (font, weight) in [(bold, 700.0), (regular, 400.0)] {
                    let variation = try XCTUnwrap(CTFontCopyVariation(font as CTFont) as? [NSNumber: NSNumber], "\(weight)")
                    XCTAssertEqual(variation[TypeRole.weightAxis]?.doubleValue ?? -1, weight, accuracy: 0.5, "Vardr Labs at \(weight)")
                }
            }
            let ax3 = UIContentSizeCategory.accessibilityExtraLarge
            XCTAssertGreaterThan(TypeRole.nameUIFont(TypeScale.rangeLarge, category: ax3, theme: theme).pointSize, 23, "\(theme): a scaled role grows at AX3")
            XCTAssertEqual(TypeRole.bodyFixedUIFont(TypeScale.rangeLarge, weight: 700, theme: theme).pointSize, 23, "\(theme): the fixed A does not")
        }
    }

    /// D-215: figures are tabular in Plex Sans too, as they were in the system face (tnum set explicitly).
    func testPlexSansCarriesTabularFiguresWhenAsked() throws {
        func hasTnum(_ d: UIFontDescriptor) -> Bool {
            let settings = d.fontAttributes[.featureSettings] as? [[String: Any]] ?? []
            return settings.contains {
                ($0[kCTFontFeatureTypeIdentifierKey as String] as? Int) == kNumberSpacingType
                    && ($0[kCTFontFeatureSelectorIdentifierKey as String] as? Int) == kMonospacedNumbersSelector
            }
        }
        for name in [TypeRole.sansRegular, TypeRole.sansMedium, TypeRole.sansSemiBold, TypeRole.sansBold] {
            XCTAssertTrue(hasTnum(TypeRole.descriptor(name, size: 17, weight: nil, tabular: true)), name)
            XCTAssertFalse(hasTnum(TypeRole.descriptor(name, size: 17, weight: nil, tabular: false)), name)
        }
        // The sans face reaches it through `base`, and the font still draws one width for every figure.
        let font = TypeRole.base(.sans, size: 17, weight: 600, tabular: true)
        XCTAssertEqual(font.familyName, "IBM Plex Sans")
        let one = ("1" as NSString).size(withAttributes: [.font: font]).width
        let eight = ("8" as NSString).size(withAttributes: [.font: font]).width
        XCTAssertEqual(one, eight, accuracy: 0.01)
    }

    /// D-215 fix 1: UIKit's bar buttons (Done, Cancel, every ToolbarItem) draw in the theme's body face, at
    /// the system's 17, scaled; the bar's own title keeps the display face.
    func testBarButtonsDrawTheBodyFace() {
        for theme in AppTheme.allCases {
            let attrs = TitleStyle.barButtonAttributes(theme: theme, category: .large)
            let font = attrs[.font] as? UIFont
            XCTAssertEqual(font?.pointSize ?? 0, 17, accuracy: 0.01, "\(theme)")
            let face = TypeRole.face(.body, theme: theme)
            let expected = TypeRole.base(face, size: 17, weight: 400, tabular: false)
            XCTAssertEqual(font?.fontName, expected.fontName, "\(theme)")
        }
        let big = TitleStyle.barButtonAttributes(theme: .aurora, category: .accessibilityLarge)[.font] as? UIFont
        XCTAssertGreaterThan(big?.pointSize ?? 0, 17)
    }

    /// D-215 fix 2 (N1): on iOS 17 to 25 a bar that carries UINavigationBarAppearance objects takes its button text from
    /// their button appearances, not from the legacy proxy, so `BarTint.backButton` sets the font on all three (plain,
    /// done, back) for every state, on every appearance of the bar and on the proxy's, with the back colour untouched.
    /// Fix wave (B1-m2, B1-m3): what this proves is the NORMAL state only, so it asserts normal only. Measured on iOS 27:
    /// once a bar appearance's normal state is set, UIKit derives highlighted, disabled and focused from it again, even a
    /// state given its own font before (a stand-in seeded there reads back as normal's font), so a skipped non-normal
    /// state cannot be observed here; the loop over every state stays in `backButton` for the iOS versions this Mac
    /// cannot run. All four appearances are checked on the live bar and on the proxy.
    @MainActor
    func testBarButtonFontReachesEveryAppearanceOfABar() throws {
        let attrs = TitleStyle.barButtonAttributes(theme: .norse, category: .large)
        let font = try XCTUnwrap(attrs[.font] as? UIFont)
        let colour = UIColor(red: 0.3, green: 0.38, blue: 0.37, alpha: 1)
        func check(_ a: UINavigationBarAppearance?, _ name: String) {
            guard let a else { return XCTFail(name + " is missing") }
            for (kind, button) in [("plain", a.buttonAppearance), ("done", a.doneButtonAppearance), ("back", a.backButtonAppearance)] {
                let normal = button.normal.titleTextAttributes[.font] as? UIFont
                XCTAssertEqual(normal?.fontName, font.fontName, "\(name) \(kind) normal")
                XCTAssertEqual(normal?.pointSize ?? 0, font.pointSize, accuracy: 0.01, "\(name) \(kind) normal")
            }
            XCTAssertEqual(a.backButtonAppearance.normal.titleTextAttributes[.foregroundColor] as? UIColor, colour, name + " back colour")
        }
        let bar = UINavigationBar()
        bar.standardAppearance = UINavigationBarAppearance()
        BarTint.applyBackButton(to: bar, proxy: false, theme: .aurora, color: colour, title: [:], large: [:], buttons: attrs)
        check(bar.standardAppearance, "standard")
        check(bar.scrollEdgeAppearance, "scrollEdge")
        check(bar.compactAppearance, "compact")
        check(bar.compactScrollEdgeAppearance, "compactScrollEdge")
        let proxy = UINavigationBar()
        BarTint.applyBackButton(to: proxy, proxy: true, theme: .aurora, color: colour, title: [:], large: [:], buttons: attrs)
        check(proxy.standardAppearance, "proxy standard")
        check(proxy.scrollEdgeAppearance, "proxy scrollEdge")
        check(proxy.compactAppearance, "proxy compact")
        check(proxy.compactScrollEdgeAppearance, "proxy compactScrollEdge")
        // Without the attributes UIKit's own font stays: the test above is not passing by default.
        let plain = BarTint.backButton(UINavigationBarAppearance(), color: colour, title: [:], large: [:], buttons: [:])
        XCTAssertNotEqual((plain.buttonAppearance.normal.titleTextAttributes[.font] as? UIFont)?.fontName, font.fontName)
    }

    /// D-222 (L62): the mode control is drawn, so no UIKit segmented control is left to style: D-215's segment font
    /// (`segmentAttributes`, `styleSegments`, the proxy and the window walk in `TitleStyle.apply`) is gone.
    func testNoSegmentStylingIsLeft() throws {
        let art = try appSource("ThemeArt.swift")
        for gone in ["segmentAttributes", "styleSegments", "segmentedControls", "segmentStates", "UISegmentedControl"] {
            XCTAssertFalse(art.contains(gone), "ThemeArt.swift: \(gone)")
        }
    }

    /// D-215 fix wave (B4-m3): the rune cascade every text face carries is built once, not on every font a role makes.
    func testTheRuneCascadeIsBuiltOnce() {
        XCTAssertTrue(TypeRole.runeCascade === TypeRole.runeCascade, "runeCascade is rebuilt on every read")
        XCTAssertEqual(TypeRole.runeCascade.fontAttributes[.name] as? String, TypeRole.runes)
    }

    /// D-215 fix 2 (N3): `TitleStyle.apply` hands the bar-button attributes to the appearance proxy, to the buttons already
    /// on live bars, and to both BarTint calls; a source pin, since the proxy cannot be read back off an item
    /// that is not in a window.
    func testTheBarButtonFontIsWiredThroughApply() throws {
        let art = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Skipti/App/ThemeArt.swift")
        let text = try String(contentsOf: art, encoding: .utf8)
        func count(_ s: String) -> Int { text.components(separatedBy: s).count - 1 }
        XCTAssertEqual(count("let buttons = barButtonAttributes(theme: state.theme, category: state.contentSizeCategory)"), 1)
        XCTAssertEqual(count("UIBarButtonItem.appearance().setTitleTextAttributes(buttons, for: controlState)"), 1)
        XCTAssertEqual(count("styleBarButton(button, buttons)"), 1)
        XCTAssertEqual(count("title: inline, large: large, buttons: buttons) }"), 2)
    }

    /// D-215 fix 1: unstyled Text takes the body face from the root, not the system's: the applier every window
    /// goes through sets it (it reads the observable appearance, so a theme or text-size change re-evaluates it).
    func testTheRootSetsTheBodyFontDefault() throws {
        let appearance = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Skipti/App/Appearance.swift")
        let text = try String(contentsOf: appearance, encoding: .utf8)
        let start = try XCTUnwrap(text.range(of: "struct AppearanceApplier: ViewModifier"))
        let body = try XCTUnwrap(text[start.upperBound...].range(of: "func body(content: Content)"))
        let tail = text[body.upperBound...].prefix(400)
        XCTAssertTrue(tail.contains(".font(TypeRole.body())"), "AppearanceApplier no longer sets the body font default")
    }

    /// Only Vardr Labs' kickers are uppercase and tracked (.22 em).
    func testOnlyVardrLabsKickersAreUppercaseAndTracked() {
        XCTAssertEqual(TypeRole.microCase(theme: .vardr), .uppercase)
        XCTAssertNil(TypeRole.microCase(theme: .aurora))
        XCTAssertNil(TypeRole.microCase(theme: .norse))
        // The tracking is .22 of the size drawn at SKIPTI's category; at the
        // default (Large) that is 13 points, so 13 * 0.22.
        let category = AppearanceState.shared.contentSizeCategory
        let drawn = TypeRole.scaledSize(13, relativeTo: .footnote, category: category)
        XCTAssertEqual(TypeRole.microTracking(13, theme: .vardr), drawn * 0.22, accuracy: 0.001)
        XCTAssertEqual(TypeRole.scaledSize(13, relativeTo: .footnote, category: .large), 13, accuracy: 0.001)
        // Two steps larger, the tracking grows with the text.
        let before = AppearanceState.shared.textStep
        defer { AppearanceState.shared.textStep = before }
        AppearanceState.shared.textStep = 2
        let larger = TypeRole.scaledSize(13, relativeTo: .footnote, category: AppearanceState.shared.contentSizeCategory)
        XCTAssertGreaterThan(larger, drawn)
        XCTAssertEqual(TypeRole.microTracking(13, theme: .vardr), larger * 0.22, accuracy: 0.001)
        XCTAssertEqual(TypeRole.microTracking(13, theme: .aurora), 0)
    }

    /// The text size reaches the role fonts: +2 steps from Large draws the
    /// body role larger than step 0, and -2 smaller.
    func testTheTextSizeStepScalesTheRoles() {
        let state = AppearanceState()
        state.phoneCategory = .large
        func size(_ step: Int) -> CGFloat {
            state.textStep = step
            return TypeRole.scaledSize(16, relativeTo: .body, category: state.contentSizeCategory)
        }
        XCTAssertGreaterThan(size(2), size(0))
        XCTAssertLessThan(size(-2), size(0))
        XCTAssertEqual(size(0), 16, accuracy: 0.01)
    }

    /// Every section label goes through `microLabelFont`, so Vardr Labs'
    /// uppercase and tracking reach all of them.
    func testNoSectionLabelSetsItsFontDirectly() throws {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Skipti/App")
        let files = try FileManager.default.contentsOfDirectory(at: app, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "swift" && $0.lastPathComponent != "TypeSystem.swift" }
        for file in files {
            let text = try String(contentsOf: file, encoding: .utf8)
            XCTAssertFalse(text.contains(".font(TypeRole.microLabel("), "\(file.lastPathComponent) bypasses microLabelFont")
            // D-213: the ᛟ mark is drawn only by `.sectionLabel()` (SectionLabelMark, ThemeArt.swift); no other file
            // reaches for the mark or its prefix.
            if file.lastPathComponent != "ThemeArt.swift" {
                XCTAssertFalse(text.contains("SectionMark.prefix(") || text.contains("Runes.sectionMark"),
                               "\(file.lastPathComponent) draws the section mark itself")
            }
        }
        // D-215 fix wave (B4-m4): what draws the mark today, once: SectionLabelMark asks for the prefix and draws it in
        // the rune role, scaled like the label's footnote text.
        let art = try String(contentsOf: app.appendingPathComponent("ThemeArt.swift"), encoding: .utf8)
        func count(_ s: String) -> Int { art.components(separatedBy: s).count - 1 }
        XCTAssertEqual(count("SectionMark.prefix("), 1)
        XCTAssertEqual(count("Text(verbatim: prefix).font(TypeRole.rune(size, relativeTo: .footnote))"), 1)
    }

    /// `AppearanceState.shared` can be first touched by a background render:
    /// building the state (which reads the phone's category) and a role must
    /// not trap off the main thread.
    func testARoleReadFromABackgroundThreadDoesNotCrash() {
        let done = expectation(description: "role built off the main thread")
        DispatchQueue.global().async {
            XCTAssertFalse(Thread.isMainThread)
            XCTAssertEqual(AppearanceState.stored().phoneCategory, .large)
            _ = TypeRole.body()
            _ = TypeRole.microLabel()
            done.fulfill()
        }
        wait(for: [done], timeout: 10)
    }

    /// Cards take the theme's radius (Aurora 20, Norse 3, Vardr Labs 0); in
    /// Aurora an explicit radius is kept. Buttons never round more than cards.
    func testCardsAndButtonsTakeTheThemesShape() {
        XCTAssertEqual(GlassCard.resolvedRadius(nil, theme: .aurora), 20)
        XCTAssertEqual(GlassCard.resolvedRadius(16, theme: .aurora), 16)
        XCTAssertEqual(GlassCard.resolvedRadius(16, theme: .norse), 3)
        XCTAssertEqual(GlassCard.resolvedRadius(nil, theme: .vardr), 0)
        XCTAssertEqual(AppTheme.aurora.buttonRadius, 14)
        XCTAssertEqual(AppTheme.norse.buttonRadius, 3)
        XCTAssertEqual(AppTheme.vardr.buttonRadius, 0)
    }

    /// D-215 fix (F2): the Appearance page's detail lines (the line under Light / Dark / System, and the text-size
    /// caption) are 14, the size every other detail line of the iOS account hub uses, and Android's twin draws the
    /// same 14 sp (TypeThemeTest reads this constant from here). One constant, used by both lines, so they cannot drift.
    func testTheAppearanceDetailLinesShareOneSize() throws {
        XCTAssertEqual(AppearanceCopy.detailSize, 14)
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/AccountHubScreen.swift")
        let source = try String(contentsOf: url, encoding: .utf8)
        XCTAssertEqual(source.components(separatedBy: ".font(TypeRole.body(AppearanceCopy.detailSize))").count - 1, 2,
                       "the Appearance card's detail line and the text-size caption both draw AppearanceCopy.detailSize")
    }

    /// D-215 fix (F2): `BarTint.backButton` gives the bar-button font to the plain, done and back appearances in all
    /// four control states. UIKit re-derives the non-normal states on iOS 27, so behaviour cannot prove the loop
    /// (the test above reads only `.normal`); the source is pinned instead, so neither list can be cut silently.
    func testTheBarButtonFontLoopKeepsAllThreeAppearancesAndFourStates() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/ThemeArt.swift")
        let art = try String(contentsOf: url, encoding: .utf8)
        XCTAssertEqual(art.components(separatedBy: "for button in [copy.buttonAppearance, copy.doneButtonAppearance, copy.backButtonAppearance] {").count - 1, 1)
        XCTAssertEqual(art.components(separatedBy: "for look in [button.normal, button.highlighted, button.disabled, button.focused] {").count - 1, 1)
    }

    // ---- D-217: one type scale, iPhone's way ----

    /// D-217: the agreed sizes, one constant each; `all` lists them by name in this order, which Android's parity
    /// test reads from the source.
    func testTheTypeScaleIsTheAgreedTable() {
        let agreed: [(String, CGFloat)] = [
            ("largeTitle", 34), ("inlineTitle", 17), ("dateLine", 14), ("heroAmount", 44), ("heroLine", 15),
            ("settledLine", 13), ("groupCount", 13), ("detail", 14), ("statValue", 16), ("settleIntro", 15),
            ("shareInput", 15), ("avatarInitial", 0.38),
            // D-221 (art.py 21, 22): display names, the settled percent, the seal's number, the progress pill.
            ("displayName", 24), ("settledPercent", 28), ("sealNumber", 15), ("pill", 13),
            // D-222 (art.py 26, 27): the mode control's words, the sample line, the slider's two A's.
            ("segment", 14), ("sample", 19), ("rangeSmall", 14), ("rangeLarge", 23),
            // D-223 (art.py 30, 32, 34): the ledger card's name, Lately's line, the empty state's title.
            ("ledgerName", 16), ("feedLine", 15), ("emptyTitle", 20),
            // D-224 (art.py 38): the done toast's words.
            ("toast", 15),
            // D-225 (art.py 41 to 44): About's prose, a licence's text, the lock screen's title, About's wordmark.
            ("prose", 15), ("footnote", 13), ("lockTitle", 28), ("wordmark", 32),
            // D-245: the welcome's wordmark (W1).
            ("welcomeMark", 52),
        ]
        let constants: [CGFloat] = [
            TypeScale.largeTitle, TypeScale.inlineTitle, TypeScale.dateLine, TypeScale.heroAmount, TypeScale.heroLine,
            TypeScale.settledLine, TypeScale.groupCount, TypeScale.detail, TypeScale.statValue, TypeScale.settleIntro,
            TypeScale.shareInput, TypeScale.avatarInitial,
            TypeScale.displayName, TypeScale.settledPercent, TypeScale.sealNumber, TypeScale.pill,
            TypeScale.segment, TypeScale.sample, TypeScale.rangeSmall, TypeScale.rangeLarge,
            TypeScale.ledgerName, TypeScale.feedLine, TypeScale.emptyTitle,
            TypeScale.toast,
            TypeScale.prose, TypeScale.footnote, TypeScale.lockTitle, TypeScale.wordmark,
            TypeScale.welcomeMark,
        ]
        XCTAssertEqual(TypeScale.all.count, agreed.count)
        for (i, (name, value)) in agreed.enumerated() {
            XCTAssertEqual(constants[i], value, name)
            guard i < TypeScale.all.count else { continue }
            XCTAssertEqual(TypeScale.all[i].0, name, "name at \(i)")
            XCTAssertEqual(TypeScale.all[i].1, value, "value of \(name)")
        }
    }

    /// D-217: the `name` role is the theme's BODY face at 600 (Plex Sans SemiBold in Aurora and Norse, Space Grotesk
    /// at 600 in Vardr Labs), resolved through `TypeRole.base`.
    func testTheNameRoleIsTheBodyFaceAt600() {
        for theme in AppTheme.allCases {
            let expected = TypeRole.base(TypeRole.face(.body, theme: theme), size: 16, weight: 600, tabular: false)
            switch theme {
            case .aurora, .norse: XCTAssertEqual(expected.fontName, TypeRole.sansSemiBold, "\(theme)")
            case .vardr: XCTAssertTrue(expected.fontName.hasPrefix("SpaceGrotesk"), "\(theme) \(expected.fontName)")
            }
            XCTAssertEqual(TypeRole.nameUIFont(16, category: .large, theme: theme).fontName, expected.fontName, "\(theme)")
            XCTAssertEqual(TypeRole.nameUIFont(16, category: .large, theme: theme).pointSize,
                           TypeRole.scaledSize(16, relativeTo: .body, category: .large), accuracy: 0.01, "\(theme) scales like body")
        }
    }

    // ---- D-217 Task 2: the call sites read the table and the roles ----

    private func appSource(_ file: String) throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// Each (file, anchor) must appear exactly `count` times; a red names the file, the anchor and what was found.
    private func assertSites(_ sites: [(file: String, anchor: String, count: Int)], _ what: String,
                             file: StaticString = #filePath, line: UInt = #line) throws {
        for site in sites {
            let found = try appSource(site.file).components(separatedBy: site.anchor).count - 1
            XCTAssertEqual(found, site.count, "\(what): \(site.file) has \(found) of \(site.count) at: \(site.anchor)", file: file, line: line)
        }
    }

    /// D-217: every family's call sites take their size from `TypeScale`, not a literal, so the table is the one
    /// place a size changes (iOS's values are the scale; Android's twin reads the same names).
    func testTheSizeFamiliesComeFromTheTable() throws {
        try assertSites([
            ("ThemeArt.swift", "size: large ? TypeScale.largeTitle : TypeScale.inlineTitle", 1),
            ("OverviewScreen.swift", "TypeRole.numeric(TypeScale.heroAmount, weight: 700, relativeTo: .largeTitle)", 1),
            ("OverviewScreen.swift", ".font(TypeRole.body(TypeScale.heroLine))", 3), // D-223: the who-owes line
            // D-223: the old group card's "Settled" went with the scroller; the ledger card says it at the line's size.
            ("OverviewScreen.swift", ".font(TypeRole.body(TypeScale.settledLine))", 0),
            ("HomeParts.swift", ".font(TypeRole.body(TypeScale.detail))", 3), // the ledger line, See all, Lately's meta
            ("HomeParts.swift", ".font(TypeRole.name(TypeScale.emptyTitle))", 1),
            ("OverviewScreen.swift", ".font(TypeRole.name(TypeScale.emptyTitle))", 1), // Balance's empty title matches
            ("OverviewScreen.swift", "TypeRole.numeric(TypeScale.statValue, weight: 600)", 1),
            ("OverviewScreen.swift", ".font(TypeRole.body(TypeScale.detail))", 3), // decision 122: the could-not-sync line
            ("GroupsScreen.swift", ".font(TypeRole.body(TypeScale.detail))", 4),
            ("ExpenseListView.swift", ".font(TypeRole.body(TypeScale.groupCount))", 1),
            ("ExpenseDetailView.swift", ".font(TypeRole.body(TypeScale.detail))", 1),
            ("ExpenseDetailView.swift", "stamp(ms, locale: AppLocale.current)], size: TypeScale.detail)", 1),
            ("SettleAll.swift", ".font(TypeRole.body(TypeScale.settleIntro))", 1),
            // D-222: 8 = You's row summary (SettingsRow) + sign in, sign out, erase, export, import and sync's two lines;
            // D-224: 9 with the Haptics row's summary. D-225: 7, You's rows and the Haptics row draw through ListRow,
            // whose summary and CardCaption are ListRows.swift's 2. D-225 Task 4: 4, the erase, export and import lines
            // became ListRow summaries (Security, Your data).
            ("AccountHubScreen.swift", ".font(TypeRole.body(TypeScale.detail))", 4),
            ("ListRows.swift", ".font(TypeRole.body(TypeScale.detail))", 2),
            ("Profile.swift", "size * TypeScale.avatarInitial", 1), // D-221: the display face at 700 in every theme
            // D-225 Task 6: the seven share fields are FieldRows that take `font: shareFont`, the one share face.
            ("AddExpenseScreen.swift", "private var shareFont: Font { TypeRole.numeric(TypeScale.shareInput) }", 1),
        ], "a size family")
        // The literals those lines used to carry are gone from where the family lives.
        XCTAssertFalse(try appSource("OverviewScreen.swift").contains("numeric(44"), "OverviewScreen.swift still draws the hero amount at a literal 44")
        XCTAssertFalse(try appSource("AccountHubScreen.swift").contains("body(14)"), "AccountHubScreen.swift still draws a detail line at a literal 14")
        XCTAssertFalse(try appSource("SettleAll.swift").contains("body(15)"), "SettleAll.swift still draws the intro at a literal 15")
        XCTAssertFalse(try appSource("AddExpenseScreen.swift").contains("TypeRole.numeric()"), "AddExpenseScreen.swift still draws a share input at the default size")
    }

    /// D-217: a name (a person, a group, an expense's title, a theme card) is drawn through the `name` role, the body
    /// face at 600, never `label` (which is Plex Mono in Vardr Labs and serves button words).
    func testNamesUseTheNameRole() throws {
        try assertSites([
            ("OverviewScreen.swift", ".font(TypeRole.name(16))", 1),
            ("OverviewScreen.swift", ".font(TypeRole.name(15))", 1), // D-223: the group card's name went to LedgerCard
            ("HomeParts.swift", ".font(TypeRole.name(TypeScale.ledgerName))", 1),
            ("HomeParts.swift", ".font(TypeRole.name(TypeScale.feedLine))", 1),
            ("GroupsScreen.swift", ".font(TypeRole.name(17))", 2),
            ("ExpenseListView.swift", ".font(TypeRole.name(15))", 1),
            ("ExpenseListView.swift", ".font(TypeRole.name(17))", 1), // the Expenses group header
            ("SettleAll.swift", ".font(TypeRole.name(17))", 1),       // Settle up's group name
            ("AccountHubScreen.swift", "Text(verbatim: theme.label).font(TypeRole.name(14))", 1),
        ], "a name")
        for (file, banned) in [("OverviewScreen.swift", "p.name).font(TypeRole.label("),
                               ("OverviewScreen.swift", "named.group.name).font(TypeRole.label("),
                               ("GroupsScreen.swift", "TypeRole.label(17)"),
                               ("ExpenseListView.swift", "TypeRole.label(15)"),
                               ("ExpenseListView.swift", "TypeRole.label(17)"),
                               ("SettleAll.swift", "TypeRole.label(17)"),
                               ("AccountHubScreen.swift", "theme.label).font(TypeRole.label(")] {
            XCTAssertFalse(try appSource(file).contains(banned), "\(file) still draws a name with the label role: \(banned)")
        }
    }

    /// D-217: the date line and the hero's first line are kickers (the micro role: Plex Sans in Aurora, Cormorant SC in
    /// Norse, uppercase tracked Plex Mono in Vardr Labs) at the date line's size; the hero's is steel in Vardr Labs.
    func testTheDateLineAndHeroKickerAreKickers() throws {
        try assertSites([
            ("Profile.swift", ".microLabelFont(TypeScale.dateLine)", 1),
            ("OverviewScreen.swift", ".microLabelFont(TypeScale.dateLine)", 1),
            ("OverviewScreen.swift", ".foregroundStyle(HeroKicker.usesSteel(AppearanceState.shared.theme) ? Tokens.accent : Tokens.onHero.opacity(0.82))", 1),
        ], "a kicker")
        XCTAssertEqual(AppTheme.allCases.map(HeroKicker.usesSteel), [false, false, true])
    }

    /// D-217: the five headings name what follows, so they take the section label (ᛟ before them in Norse), in
    /// sentence case as before.
    func testTheFiveSectionHeadingsAreSectionLabels() throws {
        try assertSites([
            ("OverviewScreen.swift", "Text(verbatim: text).sectionLabel()", 1), // People, Your ledgers (D-223)
            ("OverviewScreen.swift", "Text(verbatim: OverviewCopy.whoOwes).sectionLabel()", 1),
            ("OverviewScreen.swift", "Text(verbatim: OverviewCopy.recent).sectionLabel()", 1),
            ("OverviewScreen.swift", "Text(verbatim: OverviewCopy.whereItWent).sectionLabel()", 1),
        ], "a section heading")
        XCTAssertFalse(try appSource("OverviewScreen.swift").contains("TypeRole.label(17)"), "OverviewScreen.swift still has a 17 point label heading")
    }

    /// D-217: the Look Book's weights: tile initials 700 in the display face; the greeting 700 with Norse tracked
    /// +.02 em; You's name 700 and handle 500.
    func testTheLookBooksWeightsAreSet() throws {
        try assertSites([
            ("ThemeArt.swift", "TypeRole.display(size * 0.42, weight: 700)", 1),
            ("ThemeArt.swift", "TypeRole.display(size * 0.36, weight: 700)", 0), // D-220: one factor, the diamond's smaller letter is gone
            ("Profile.swift", ".font(TypeRole.display(Self.greetingSize, weight: 700))", 1),
            ("Profile.swift", ".tracking(Self.greetingTracking(AppearanceState.shared.theme))", 1),
            ("Profile.swift", ".font(TypeRole.display(TypeScale.displayName, weight: 700))", 1), // D-221
            ("Profile.swift", ".font(TypeRole.body(15, weight: 500))", 1),
        ], "a weight")
        XCTAssertEqual(AppTheme.allCases.map(HomeGreeting.greetingTracking), [0, 0.44, 0])
        XCTAssertEqual(HomeGreeting.greetingSize, 22)
    }

    // ---- D-217 Task 5: one title rule per kind of screen ----

    private func appFiles() throws -> [String] {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App")
        return try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".swift") }.sorted()
    }

    /// D-217: the four tab roots and the Add/Edit expense sheet draw the LARGE title; every other screen and sheet
    /// says `.inline`, whatever the entry (an unstated mode is large at a sheet root and inherits its parent's on a
    /// push, so the same screen changed by where it was reached from). The mode must follow its title within three
    /// lines. The proof screens are exempt; a view with no `.navigationTitle(` at all (the picture viewer, the
    /// removal loader, a pushed expense before it loads) is untitled by design and passes.
    func testEveryTitleSaysItsDisplayMode() throws {
        let proofs: Set<String> = ["BalanceScreen.swift", "SettleScreen.swift", "SmokeScreenView.swift",
                                   "ExportProofScreen.swift", "ImportProofScreen.swift"]
        let large: [(file: String, line: String)] = [
            ("OverviewScreen.swift", ".navigationTitle(\"Balance\")"),
            ("GroupsScreen.swift", ".navigationTitle(\"Groups\")"),
            ("ExpenseListView.swift", ".navigationTitle(\"Expenses\")"),
            ("SettleAll.swift", ".navigationTitle(\"Settle up\")"),
            ("AddExpenseScreen.swift", ".navigationTitle(title)"),
        ]
        var failures: [String] = []
        var largeFound: [String: Int] = [:]
        var scanned = 0
        var sites = 0
        for file in try appFiles() where !proofs.contains(file) {
            scanned += 1
            let lines = try appSource(file).components(separatedBy: "\n")
            for (i, raw) in lines.enumerated() {
                let text = raw.trimmingCharacters(in: .whitespaces)
                // D-217 final fix: a title anywhere but at a line's start would escape the pairing below.
                if !text.hasPrefix("//") && text.dropFirst().contains(".navigationTitle(") {
                    failures.append("\(file):\(i + 1) a .navigationTitle( that does not start its line: \(text)")
                }
                guard !text.hasPrefix("//"), text.hasPrefix(".navigationTitle(") else { continue }
                sites += 1
                let isLarge = large.contains { $0.file == file && $0.line == text }
                if isLarge { largeFound["\(file) \(text)", default: 0] += 1 }
                let want = isLarge ? ".large" : ".inline"
                var mode: String?
                for next in lines[(i + 1)...].prefix(3) {
                    let t = next.trimmingCharacters(in: .whitespaces)
                    if t.hasPrefix(".navigationTitle(") { break }
                    if t.hasPrefix(".navigationBarTitleDisplayMode(") { mode = t; break }
                }
                if mode != ".navigationBarTitleDisplayMode(\(want))" {
                    failures.append("\(file):\(i + 1) \(text) wants \(want), has \(mode ?? "no display mode")")
                }
            }
        }
        XCTAssertGreaterThan(scanned, 30, "the guard scanned too few files")
        XCTAssertGreaterThan(sites, 30, "the guard found too few titles")
        for (file, line) in large {
            XCTAssertEqual(largeFound["\(file) \(line)"], 1, "the large title site \(file) \(line)")
        }
        XCTAssertTrue(failures.isEmpty, "titles without their display mode:\n" + failures.joined(separator: "\n"))
    }

    /// D-217: the title and Cancel decisions of Task 5, pinned at their sites: Import review is "Review", Export
    /// titles itself (so all four entries have it, the callers add none), and New group, Your name and both Join
    /// sheets can be cancelled from the bar.
    func testTheBarsTitleAndCancelWhereTheRuleSaysSo() throws {
        try assertSites([
            ("ImportReview.swift", ".navigationTitle(Text(verbatim: ImportCopy.reviewTitle))", 1),
            ("ImportFlow.swift", "static let reviewTitle = \"Review\"", 1),
            ("ExportFlow.swift", ".navigationTitle(\"Export\")", 1),
            ("AccountHubScreen.swift", ".navigationTitle(\"Export\")", 0),
            ("AccountOwner.swift", ".navigationTitle(\"Export\")", 0),
            ("GroupSheets.swift", "ToolbarItem(placement: .cancellationAction)", 2),   // Your name, New group
            ("GroupSheets.swift", "Button(\"Cancel\") { dismiss() }.barTint()", 2),
            // D-217 final fix: Join's Cancel is JoinScreen's own, shown only before a join starts (the phase is not
            // locked), and the two sheets pass it in; a joined sheet leaves through Done (onJoined) alone.
            ("People.swift", "}, onCancel: { joining.wrappedValue = nil })", 1),  // the invitation's Join sheet
            ("SkiptiApp.swift", "}, onCancel: { openedInvite = nil })", 1),         // the opened link's Join sheet
            ("People.swift", "Button(\"Cancel\")", 0),
            ("SkiptiApp.swift", "Button(\"Cancel\")", 0),
            ("Invites.swift", "if let onCancel, !phase.locked {", 1),
            ("Invites.swift", "Button(\"Cancel\") { onCancel() }.barTint()", 1),
            ("Invites.swift", "case .joining, .joined: true", 1),
            ("Invites.swift", "case .opening, .offer, .failed: false", 1),
            ("Invites.swift", ".navigationBarBackButtonHidden(phase.locked)", 1),
            ("Invites.swift", ".interactiveDismissDisabled(phase.locked)", 1),
            // Join paste pushes JoinScreen with no Cancel (its back is the system's, hidden once locked).
            ("Invites.swift", "JoinScreen(store: store, link: link, onJoined: onJoined)", 1),
        ], "a bar")
    }

    // ---- D-217: the name role's default size, and Join's two ways out once a join has gone through ----

    /// D-217: `TypeRole.name()` with no size is the 16 point name (Android pins its twin default). The two Fonts are
    /// built from the same scaled UIFont, so they are equal only if the default is 16; the signature is pinned too,
    /// so a default that moved cannot hide behind a Font equality that happens to hold.
    func testTheNameRoleDefaultsTo16() throws {
        XCTAssertEqual(TypeRole.name(), TypeRole.name(16), "the default size of TypeRole.name is 16")
        let source = try appSource("TypeSystem.swift")
        XCTAssertEqual(source.components(separatedBy: "static func name(_ size: CGFloat = 16) -> Font {").count - 1, 1,
                       "TypeSystem.swift: TypeRole.name's signature, default 16")
    }

    /// D-217: once a join has gone through, the Done button is JoinScreen's only way out and it calls `onJoined(group)`
    /// (the caller reloads and leaves); a Done that only dismissed would leave the caller stale.
    func testTheJoinedDoneCallsOnJoined() throws {
        try assertSites([
            ("Invites.swift", "Button { onJoined(group) } label: { Text(verbatim: InviteCopy.done) }", 1),
        ], "the joined Done")
    }

    /// D-217: the opened-link Join sheet closes both ways: joined (Done, `onJoined`) and Cancelled (`onCancel`) clear
    /// `openedInvite`, or the sheet stays up after a join.
    func testTheOpenedLinkSheetClosesWhenJoinedAndWhenCancelled() throws {
        try assertSites([
            ("SkiptiApp.swift", "onJoined: { _ in\n                            openedInvite = nil\n                        }, onCancel: { openedInvite = nil })", 1),
        ], "the opened link's Join sheet")
    }
}
