import ImageIO
import UIKit
import UniformTypeIdentifiers
import XCTest
@testable import Skipti

/// D-145: the on-device profile's picture. Android's twin is ProfileTest.
final class ProfileTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("profile-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    func testAPictureSurvivesTheVaultAndAnUnknownOneIsNoPicture() throws {
        let store = LedgerStore(path: dir.appendingPathComponent("ledger.db").path)
        XCTAssertEqual(try store.loadAvatar(), .none, "a new vault has no picture")
        try store.saveAvatar(.emoji("🦊"))
        XCTAssertEqual(try store.loadAvatar(), .emoji("🦊"))
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3])
        try store.saveAvatar(.photo(jpeg))
        XCTAssertEqual(try store.loadAvatar(), .photo(jpeg))
        try store.saveAvatar(.none)
        XCTAssertEqual(try store.loadAvatar(), .none)
        XCTAssertEqual(
            Avatar.decode(Data(#"{"kind":"emoji","emoji":"💀"}"#.utf8)), .none,
            "an emoji outside the offered set is refused on read"
        )
        XCTAssertEqual(Avatar.decode(Data("not json".utf8)), .none, "unreadable is no picture, never a crash")
    }

    /// The offered emoji are the same 48 on both platforms, in order.
    func testTheOfferedEmojiAreFixedAndDistinct() {
        XCTAssertEqual(ProfileRules.emojiChoices.count, 48)
        XCTAssertEqual(Set(ProfileRules.emojiChoices).count, 48)
        XCTAssertEqual(ProfileRules.emojiChoices.first, "😀")
        XCTAssertEqual(ProfileRules.emojiChoices.last, "🧩")
    }

    /// D-225 (L76): the emoji grid in rows of six (eight gave cells under the touch minimum), and the chosen cell's paint
    /// as token names: accentSoft inside an accent border; any other cell clear (Android's ProfileTest pins the same).
    func testTheEmojiGridIsSixColumnsAndOnlyTheChosenCellIsPainted() {
        let rows = EmojiGrid.rows(ProfileRules.emojiChoices, columns: 6)
        XCTAssertEqual(ProfileRules.emojiChoices.count, 48)
        XCTAssertEqual(rows.count, 8)
        XCTAssertTrue(rows.allSatisfy { $0.count == 6 })
        XCTAssertEqual(rows.flatMap { $0 }, ProfileRules.emojiChoices, "every choice once, in order")
        let seven = EmojiGrid.rows(Array(ProfileRules.emojiChoices.prefix(7)), columns: 6)
        XCTAssertEqual(seven.map(\.count), [6, 1])
        XCTAssertEqual(seven[1], [ProfileRules.emojiChoices[6]])
        XCTAssertEqual(EmojiGrid.rows([], columns: 6), [])
        XCTAssertEqual(Int(ThemeArt.m("emojiColumns")), 6)
        XCTAssertEqual(EmojiCell.paint(chosen: true), EmojiCell.Paint(fill: "accentSoft", border: "accent"))
        XCTAssertEqual(EmojiCell.paint(chosen: false), EmojiCell.Paint(fill: nil, border: nil))
    }

    func testInitialsComeFromUpToTwoWords() {
        XCTAssertEqual(ProfileRules.initials("tim"), "T")
        XCTAssertEqual(ProfileRules.initials("Tim Cook"), "TC")
        XCTAssertEqual(ProfileRules.initials("Ana María López"), "AM")
        XCTAssertNil(ProfileRules.initials(nil))
        XCTAssertNil(ProfileRules.initials("   "))
    }

    /// A 600 × 300 photo becomes a centred 256 px square JPEG.
    func testAPhotoIsCroppedSquareAndShrunk() throws {
        XCTAssertEqual(ProfileRules.squareCrop(width: 600, height: 300).x, 150)
        XCTAssertEqual(ProfileRules.squareCrop(width: 600, height: 300).side, 300)
        XCTAssertEqual(ProfileRules.squareCrop(width: 200, height: 500).y, 150)

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let wide = UIGraphicsImageRenderer(size: CGSize(width: 600, height: 300), format: format).image { ctx in
            UIColor.red.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 600, height: 300))
        }
        let stored = try XCTUnwrap(ProfileRules.storedPhoto(from: try XCTUnwrap(wide.pngData())))
        let back = try XCTUnwrap(UIImage(data: stored))
        XCTAssertEqual(back.cgImage?.width, 256)
        XCTAssertEqual(back.cgImage?.height, 256)
        XCTAssertEqual(Array(stored.prefix(2)), [0xFF, 0xD8], "stored as JPEG")
        XCTAssertNil(ProfileRules.storedPhoto(from: Data("not an image".utf8)))
    }

    /// The system picker hands over a photo "Location Included". The stored
    /// picture is re-drawn, so it carries no GPS (or any camera) metadata.
    func testAStoredPhotoCarriesNoLocation() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let cg = try XCTUnwrap(UIGraphicsImageRenderer(size: CGSize(width: 400, height: 400), format: format)
            .image { ctx in
                UIColor.blue.setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: 400, height: 400))
            }.cgImage)
        let tagged = NSMutableData()
        let destination = try XCTUnwrap(
            CGImageDestinationCreateWithData(tagged, UTType.jpeg.identifier as CFString, 1, nil)
        )
        let gps: [CFString: Any] = [
            kCGImagePropertyGPSLatitude: 12.9716, kCGImagePropertyGPSLatitudeRef: "N",
            kCGImagePropertyGPSLongitude: 77.5946, kCGImagePropertyGPSLongitudeRef: "E",
        ]
        CGImageDestinationAddImage(destination, cg, [kCGImagePropertyGPSDictionary: gps] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        func properties(_ data: Data) throws -> [CFString: Any] {
            let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
            return (CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]) ?? [:]
        }
        XCTAssertNotNil(
            try properties(tagged as Data)[kCGImagePropertyGPSDictionary], "the input really carries a location"
        )
        let stored = try XCTUnwrap(ProfileRules.storedPhoto(from: tagged as Data))
        XCTAssertNil(try properties(stored)[kCGImagePropertyGPSDictionary], "no location in the stored picture")
    }

    /// D-225 (Design 9; L75, L76; art.py 43): the placeholders at iOS's ratios on both phones (the glyph at `avatarGlyph`,
    /// the emoji at `avatarEmoji`, the initials at the table's size), and the emoji grid in `emojiColumns` equal square
    /// cells `emojiGap` apart, painted by `EmojiCell` in the theme's panel shape, the emoji at a fixed `emojiGlyph`.
    func testTheProfilePlaceholdersAndGrid() throws {
        let profile = try s2Source("Profile.swift")
        let avatar = try s2Slice(profile, from: "struct AvatarView: View {", to: "\n}\n")
        for needle in ["LucideIcon(glyph: .userRound, size: size * ThemeArt.m(\"avatarGlyph\"))",
                       ".font(.system(size: size * ThemeArt.m(\"avatarEmoji\")))", "size * TypeScale.avatarInitial"] {
            XCTAssertTrue(avatar.contains(needle), "AvatarView: \(needle)")
        }
        for banned in ["size * 0.", "size: 24"] {
            XCTAssertFalse(avatar.contains(banned), "AvatarView: a literal ratio \(banned)")
        }
        let grid = try s2Slice(profile, from: "private var emojiGrid: some View {", to: "\n    }\n")
        for needle in ["let columns = Int(ThemeArt.m(\"emojiColumns\"))", "let gap = ThemeArt.m(\"emojiGap\")",
                       "let border = ThemeArt.m(\"emojiBorder\")",
                       "ThemeArt.panelShape(AppearanceState.shared.theme, radius: ThemeArt.m(\"emojiRadius\"), continuous: true)",
                       "EmojiGrid.rows(ProfileRules.emojiChoices, columns: columns)", "EmojiCell.paint(chosen: avatar == .emoji(e))",
                       ".font(.system(size: ThemeArt.m(\"emojiGlyph\")))", ".aspectRatio(1, contentMode: .fit)",
                       "if let fill = paint.fill", "if let edge = paint.border", "lineWidth: border",
                       ".accessibilityAddTraits(avatar == .emoji(e) ? .isSelected : [])"] {
            XCTAssertTrue(grid.contains(needle), "the emoji grid: \(needle)")
        }
        for banned in ["count: 8", "LazyVGrid", "surfaceRaised", "cornerRadius: 8"] {
            XCTAssertFalse(profile.contains(banned), "Profile.swift: \(banned)")
        }
        XCTAssertEqual(ThemeArt.m("avatarGlyph"), 0.5)
        XCTAssertEqual(ThemeArt.m("avatarEmoji"), 0.55)
        XCTAssertEqual(ThemeArt.m("emojiColumns"), 6)
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
