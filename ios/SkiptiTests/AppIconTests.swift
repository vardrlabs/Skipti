import XCTest
@testable import Skipti

/// D-216 (Design 1): the three app icons are in the asset catalog, named in the build settings, and compiled into the
/// bundle as alternates. Aurora is the primary icon (`AppIcon`); Norse and Vardr Labs are the alternates whose names
/// `AppIcon.alternateName` gives. Android's twin is AppIconTest (the launcher aliases).
final class AppIconTests: XCTestCase {
    /// ios/, from this file's place in the source tree.
    private var iosRoot: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
    }

    /// The alternate names the Swift enum gives, in AppIcon order (Aurora has none).
    private var enumAlternates: [String] { AppIcon.allCases.compactMap(\.alternateName) }

    /// Exactly three icon sets, each with one Any, one dark and one tinted 1024 image, every named file present.
    func testTheIconSetsAreInTheCatalog() throws {
        let catalog = iosRoot.appendingPathComponent("Skipti/App/Assets.xcassets")
        let sets = try FileManager.default.contentsOfDirectory(atPath: catalog.path)
            .filter { $0.hasPrefix("AppIcon") && $0.hasSuffix(".appiconset") }
            .sorted()
        XCTAssertEqual(sets, ["AppIcon-Norse.appiconset", "AppIcon-Vardr.appiconset", "AppIcon.appiconset"])
        XCTAssertEqual(
            Set(sets.map { String($0.dropLast(".appiconset".count)) }),
            Set(["AppIcon"] + enumAlternates),
            "the catalog's sets are the primary plus the enum's alternates",
        )
        for set in sets {
            let folder = catalog.appendingPathComponent(set)
            let data = try Data(contentsOf: folder.appendingPathComponent("Contents.json"))
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
            let images = try XCTUnwrap(json["images"] as? [[String: Any]], set)
            XCTAssertEqual(images.count, 3, "\(set): Any, dark and tinted")
            func appearance(_ image: [String: Any]) -> String? {
                guard let list = image["appearances"] as? [[String: String]] else { return nil }
                XCTAssertEqual(list.count, 1, "\(set): one appearance per entry")
                XCTAssertEqual(list.first?["appearance"], "luminosity", set)
                return list.first?["value"]
            }
            XCTAssertEqual(images.map(appearance).map { $0 ?? "any" }.sorted(), ["any", "dark", "tinted"], set)
            for image in images {
                XCTAssertEqual(image["size"] as? String, "1024x1024", set)
                XCTAssertEqual(image["idiom"] as? String, "universal", set)
                XCTAssertEqual(image["platform"] as? String, "ios", set)
                let name = try XCTUnwrap(image["filename"] as? String, "\(set): every entry names its file")
                XCTAssertTrue(
                    FileManager.default.fileExists(atPath: folder.appendingPathComponent(name).path),
                    "\(set)/\(name) exists",
                )
            }
        }
    }

    /// actool compiled the alternates: the built Info.plist lists exactly the two the enum names.
    func testTheAlternatesAreBuiltIn() throws {
        let icons = try XCTUnwrap(Bundle.main.infoDictionary?["CFBundleIcons"] as? [String: Any], "CFBundleIcons")
        let alternates = try XCTUnwrap(icons["CFBundleAlternateIcons"] as? [String: Any], "CFBundleAlternateIcons")
        XCTAssertEqual(Set(alternates.keys), ["AppIcon-Norse", "AppIcon-Vardr"])
        XCTAssertEqual(Set(alternates.keys), Set(enumAlternates), "every name the switcher can set is built in")
        let primary = try XCTUnwrap(icons["CFBundlePrimaryIcon"] as? [String: Any], "CFBundlePrimaryIcon")
        XCTAssertEqual(primary["CFBundleIconName"] as? String, "AppIcon")
    }

    /// The build setting names the alternates once in each of the app's two configurations, and nowhere else.
    func testTheBuildSettingNamesTheAlternates() throws {
        let pbxproj = try String(
            contentsOf: iosRoot.appendingPathComponent("Skipti.xcodeproj/project.pbxproj"), encoding: .utf8,
        )
        let key = "ASSETCATALOG_COMPILER_ALTERNATE_APPICON_NAMES = "
        XCTAssertEqual(pbxproj.components(separatedBy: key).count - 1, 2, "twice in the project")
        for header in ["XC0011 /* Debug */ = {", "XC0012 /* Release */ = {"] {
            let parts = pbxproj.components(separatedBy: header)
            XCTAssertEqual(parts.count, 2, "\(header) appears once")
            let block = parts.last?.components(separatedBy: "name = ").first ?? ""
            let line = block.components(separatedBy: "\n").first { $0.contains(key) }
            let value = try XCTUnwrap(line, "\(header) names the alternates")
                .components(separatedBy: key).last?
                .trimmingCharacters(in: CharacterSet(charactersIn: "\";\t "))
            let names = (value ?? "").split(separator: " ").map(String.init)
            XCTAssertEqual(names, ["AppIcon-Norse", "AppIcon-Vardr"], header)
            XCTAssertEqual(names, enumAlternates, "\(header): the enum's names")
            XCTAssertTrue(block.contains("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;"), "\(header): Aurora is primary")
        }
    }
}
