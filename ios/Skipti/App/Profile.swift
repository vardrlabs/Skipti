import PhotosUI
import SwiftUI
import UIKit

/// D-145: the on-device profile (the founder, 2026-09-21: "set up local
/// account (on device) name and stuff, maybe an option to add photo like an
/// emoji"; he chose emoji OR photo). It is NOT an account (GUARANTEES #5)
/// and never asks for one: without an account nothing here leaves the phone;
/// with one, the name and picture sync as below (D-211).
///
/// The name is the one D-135 already stores (`SelfDTO.name`); `setYourName`
/// carries a change into every group ("Tim (You)"). The picture is new: an
/// emoji from a fixed set, or a photo from the system picker (no photo
/// library permission: the picker hands over only the one photo chosen),
/// cropped square and shrunk to 256 px before it is stored in the vault.
///
/// D-211: the picture is a personal RECORD (core's `GroupOp::Avatar`), so it
/// syncs to your own phones and travels in the export; the kv value is a cache
/// the ledger rebuild writes from the records, as `ledger/self` is for the
/// name. Your groups see a small preview of it (`ProfileThumb`), sent through
/// `ProfileShare`.
enum Avatar: Equatable {
    case none
    case emoji(String)
    case photo(Data)

    /// The vault's shape. A photo is JPEG bytes, base64 in JSON.
    private struct Stored: Codable {
        var kind: String
        var emoji: String?
        var jpeg: String?
    }

    func encoded() throws -> Data {
        let stored: Stored
        switch self {
        case .none: stored = Stored(kind: "none")
        case .emoji(let e): stored = Stored(kind: "emoji", emoji: e)
        case .photo(let data): stored = Stored(kind: "photo", jpeg: data.base64EncodedString())
        }
        return try JSONEncoder().encode(stored)
    }

    /// The record's two fields: an emoji, or a photo as lowercase hex, or
    /// neither (`.none` clears the picture).
    var recordParts: (emoji: String?, photoHex: String?) {
        switch self {
        case .none: return (nil, nil)
        case .emoji(let e): return (e, nil)
        case .photo(let data): return (nil, HexCodec.encode(data))
        }
    }

    /// A picture as the projection reports it; unreadable hex is no picture.
    init(emoji: String?, photoHex: String?) {
        if let emoji { self = .emoji(emoji) }
        else if let photoHex, let bytes = try? HexCodec.decode(photoHex) { self = .photo(bytes) }
        else { self = .none }
    }

    /// Anything unreadable is no picture, never a crash: a picture is
    /// decoration, and the ledger must not fail to open over one.
    static func decode(_ data: Data?) -> Avatar {
        guard let data, let s = try? JSONDecoder().decode(Stored.self, from: data) else { return .none }
        switch s.kind {
        case "emoji":
            guard let e = s.emoji, ProfileRules.emojiChoices.contains(e) else { return .none }
            return .emoji(e)
        case "photo":
            guard let b64 = s.jpeg, let bytes = Data(base64Encoded: b64) else { return .none }
            return .photo(bytes)
        default:
            return .none
        }
    }
}

/// D-225 (L76, .drafts/d225/art.py section 43): the emoji picker's grid, `emojiColumns` (6) equal columns on both
/// phones (eight gave cells under both platforms' touch minimums). Android's twin is `EmojiGrid` in Profile.kt.
enum EmojiGrid {
    /// The choices in rows of `columns`, in order; the last row holds what is left.
    static func rows(_ choices: [String], columns: Int) -> [[String]] {
        stride(from: 0, to: choices.count, by: max(columns, 1)).map { Array(choices[$0..<min($0 + columns, choices.count)]) }
    }
}

/// D-225 (L76): how an emoji cell is painted, as token names: the chosen cell `accentSoft` inside an `emojiBorder`
/// border in `accent`; every other cell clear (no fill, no border). Android's twin is `EmojiCell` in Profile.kt.
enum EmojiCell {
    struct Paint: Equatable {
        let fill: String?
        let border: String?
    }

    static func paint(chosen: Bool) -> Paint {
        chosen ? Paint(fill: "accentSoft", border: "accent") : Paint(fill: nil, border: nil)
    }
}

enum ProfileRules {
    /// The emoji offered: a fixed set, identical on Android, so what one
    /// platform stores the other would accept.
    static let emojiChoices: [String] = [
        "😀", "😎", "🤓", "🥳", "😇", "🤠", "🧐", "😺",
        "🦊", "🐼", "🐯", "🦁", "🐸", "🐙", "🦉", "🐝",
        "🌻", "🌵", "🍀", "🌊", "🔥", "⭐", "🌙", "☀️",
        "🍕", "🍜", "🍩", "☕", "🍉", "🥑", "🌮", "🍣",
        "⚽", "🏏", "🎸", "🎧", "📚", "✈️", "🚲", "🏔️",
        "💼", "🎨", "🧭", "🪴", "🐾", "💡", "🎯", "🧩",
    ]

    /// The largest side of a stored photo, in pixels.
    static let photoSide = 256

    /// Initials for a picture-less profile: the first letter of up to two
    /// words, or nil when there is no name to take them from.
    static func initials(_ name: String?) -> String? {
        guard let name else { return nil }
        let letters = name.split(whereSeparator: \.isWhitespace).prefix(2).compactMap(\.first)
        return letters.isEmpty ? nil : String(letters).uppercased()
    }

    /// The centred square inside a `width` × `height` image.
    static func squareCrop(width: Int, height: Int) -> (x: Int, y: Int, side: Int) {
        let side = min(width, height)
        return ((width - side) / 2, (height - side) / 2, side)
    }

    /// The largest photo core takes (`PICTURE_MAX_BYTES`).
    static let photoMaxBytes = 64_000

    /// A chosen photo as the stored picture: centre-cropped square, at most
    /// `photoSide` pixels, JPEG at the best quality (0.8, stepping down by 0.1
    /// to 0.4) that fits `photoMaxBytes`. Nil when the bytes are not an image
    /// or never fit.
    static func storedPhoto(from data: Data) -> Data? {
        squareJPEG(from: data, side: photoSide, maxBytes: photoMaxBytes, startQuality: 0.8, minQuality: 0.4)
    }

    /// The centre square of `data` drawn at `side` pixels (at most the
    /// square's own side: never enlarged), as a JPEG at the best quality from
    /// `startQuality` stepping down by 0.1 to `minQuality` that fits
    /// `maxBytes`. Nil when the bytes are not an image or never fit. Your
    /// stored photo and a group's preview of it (`ProfileThumb`) both use it.
    static func squareJPEG(from data: Data, side: Int, maxBytes: Int, startQuality: Double, minQuality: Double) -> Data? {
        guard let image = UIImage(data: data), let cg = image.cgImage else { return nil }
        let crop = squareCrop(width: cg.width, height: cg.height)
        guard crop.side > 0,
              let square = cg.cropping(to: CGRect(x: crop.x, y: crop.y, width: crop.side, height: crop.side))
        else { return nil }
        let drawn = min(side, crop.side)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let rendered = UIGraphicsImageRenderer(size: CGSize(width: drawn, height: drawn), format: format).image { _ in
            UIImage(cgImage: square, scale: 1, orientation: image.imageOrientation)
                .draw(in: CGRect(x: 0, y: 0, width: drawn, height: drawn))
        }
        // Integer tenths, so 0.8 steps to exactly 0.4 with no drift.
        let first = Int((startQuality * 10).rounded()), last = Int((minQuality * 10).rounded())
        for tenth in stride(from: first, through: last, by: -1) {
            if let jpeg = rendered.jpegData(compressionQuality: Double(tenth) / 10), jpeg.count <= maxBytes { return jpeg }
        }
        return nil
    }
}

extension LedgerStore {
    static let avatarKey = "ledger/self-avatar"
    /// D-211: each group's joined people with a profile, by group id then by
    /// the id the person is SHOWN by: their preview (`thumbHex` in the
    /// photo field). Rebuilt from the records with the groups.
    static let memberPreviewsKey = "ledger/member-previews"
    /// Bumped on every profile change so the top-right picture, drawn on
    /// every tab, reloads. Device preferences: it holds no personal data.
    static let profileRevisionKey = "profile.revision"

    func loadAvatar() throws -> Avatar {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        return Avatar.decode(try vault.kvGet(key: Self.avatarKey))
    }

    /// D-211: writes the picture as a personal record (clearing writes an
    /// empty one), then the kv cache the screens read. Core refuses a photo
    /// over 64,000 bytes or that is not a JPEG: nothing is then written.
    func saveAvatar(_ avatar: Avatar) throws {
        let parts = avatar.recordParts
        try Sync.appendGroupOps(self, [.avatar(emoji: parts.emoji, photoHex: parts.photoHex)], placing: .none)
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        try vault.kvSet(key: Self.avatarKey, value: try avatar.encoded())
        // A record now exists: the one-time migration has nothing left to do.
        try vault.kvSet(key: Sync.avatarRecordKey, value: Data("1".utf8))
    }

    /// D-211: the previews of the joined people in each group, by group id,
    /// then by the id the person is shown by. Present means that person has a
    /// profile of their own (their name is theirs: Rename is absent); `.none`
    /// is a name with no picture.
    func memberPreviews() throws -> [String: [String: Avatar]] {
        let vault = try VaultGate.open(path: path, keystore: KeychainKeystore())
        defer { try? vault.closeVault() }
        guard let data = try vault.kvGet(key: Self.memberPreviewsKey), !data.isEmpty,
              let stored = try? JSONDecoder().decode([String: [String: PictureDTO]].self, from: data)
        else { return [:] }
        return stored.mapValues { $0.mapValues { Avatar(emoji: $0.emoji, photoHex: $0.photoHex) } }
    }
}

/// The picture: a photo, an emoji, your initials, or the person glyph. D-221 (Q2 a): on the fill's ground (soft:
/// `accentSoft` with `accent` ink, every avatar but Home's badge; accent: `accent` with `onAccent`); a photo covers it.
struct AvatarView: View {
    let avatar: Avatar
    let name: String?
    var size: CGFloat = 56
    var fill: AvatarFill = .soft

    var body: some View {
        ZStack {
            Circle().fill(fill.ground)
            switch avatar {
            case .photo(let data):
                // D-235 round 4 (M8): a member's picture decodes safely (SafeImage), never at full size.
                if let image = SafeImage.decode(data) {
                    Image(uiImage: image).resizable().scaledToFill()
                }
            case .emoji(let e):
                // D-225 (Design 9, L75): the emoji at `avatarEmoji` of the size, the glyph at `avatarGlyph` (both phones).
                Text(verbatim: e).font(.system(size: size * ThemeArt.m("avatarEmoji")))
            case .none:
                if let initials = ProfileRules.initials(name) {
                    // D-221: the display face at 700 in every theme (Norse's Cormorant SC, the others' display face).
                    Text(verbatim: initials)
                        .font(TypeRole.display(size * TypeScale.avatarInitial, weight: 700))
                        .foregroundStyle(fill.ink)
                } else {
                    LucideIcon(glyph: .userRound, size: size * ThemeArt.m("avatarGlyph"))
                        .foregroundStyle(fill.ink)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        // D-221: Norse edges a picture in its gold-brown, 1 pt; the others draw no edge (the hairline went).
        .overlay {
            if let edge = AvatarStyle.edgeHex(AppearanceState.shared.theme) {
                Circle().stroke(Color(UIColor(hex: edge)), lineWidth: 1)
            }
        }
        .accessibilityHidden(true)
    }
}

/// D-211: Profile's footnotes. Android's twin is ProfileCopy in Profile.kt.
enum ProfileCopy {
    static let sectionTitle = "Name and picture"
    static let accountPicture = "Your picture syncs to your phones. Your groups see a small preview."
    static let accountHistory = "Earlier pictures stay in your private history until you delete your account."
    static let phoneOnly = "Your name and picture stay on this phone."
    static let done = "Done"

    static func footnotes(signedIn: Bool) -> [String] {
        signedIn ? [accountPicture, accountHistory] : [phoneOnly]
    }
}

/// D-211: the viewer is offered only for a photo whose bytes decode to an
/// image; any other `.photo` behaves like no picture.
enum PictureViewing {
    static func canView(_ avatar: Avatar) -> Bool {
        if case .photo(let data) = avatar { return SafeImage.decode(data) != nil }
        return false
    }
}

/// D-211: your own picture, big, on black. Only a photo opens it (an emoji or
/// no picture has nothing to enlarge). Nothing here changes the picture.
struct PictureViewer: View {
    let avatar: Avatar
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if case .photo(let data) = avatar, let image = SafeImage.decode(data) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .accessibilityLabel("Your picture")
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    // Always white on the black bar: the bar's own tint is the
                    // app accent in light mode (pale purple on black, weak
                    // contrast) and white only in dark (found in the look).
                    Button(ProfileCopy.done) { dismiss() }
                        .barButtonText()
                        .foregroundStyle(Color.white)
                }
            }
            .toolbarBackground(.black, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
        .tint(Color.white)
    }
}

/// Settings → Profile.
struct ProfileScreen: View {
    let store: LedgerStore
    /// `Account` is `@Observable`: reading `isSignedIn` in the body follows
    /// the account live (a sign-out elsewhere changes the footnote at once).
    private let account = Account.shared

    @AppStorage(LedgerStore.profileRevisionKey) private var revision = 0
    @State private var name: String = ""
    @State private var savedName: String?
    @State private var avatar: Avatar = .none
    @State private var photoItem: PhotosPickerItem?
    @State private var error: String?
    /// D-245 (U17): the name the done row confirms; the row shows while the field still holds it.
    @State private var nameDoneFor: String?
    /// D-245 (decision 161): a tapped emoji or picked photo waits in the sheet; nothing is written before "Use this
    /// picture". The done row under the picture says what the last confirm wrote.
    @State private var stage = PictureStage()
    @State private var confirming: StagedPicture?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(spacing: 10) {
                    AvatarView(avatar: avatar, name: savedName, size: 96)
                    if let done = stage.done { DoneRow(text: done) }
                }
                .frame(maxWidth: .infinity)

                card {
                    micro("Your name")
                    TextField("Your name", text: $name)
                        .font(TypeRole.body(17))
                        .textContentType(.name)
                        .submitLabel(.done)
                        .onSubmit { saveName(returned: true) }
                        .hairlineField()
                    // D-245 (U17): signed in, who sees the name.
                    if account.isSignedIn {
                        Text(verbatim: WelcomeCopy.groupsName)
                            .font(TypeRole.body(TypeScale.detail))
                            .foregroundStyle(Tokens.textDim)
                    }
                    if let shown = savedName {
                        Text("Shown as “\(shown) (You)” in your groups.")
                            .font(TypeRole.body(TypeScale.detail))
                            .foregroundStyle(Tokens.textDim)
                    }
                    // D-245 (U17, decision 179): the rules in words, as the field is typed.
                    if let problem = NameRule.liveProblem(name, saved: savedName) {
                        Text(verbatim: problem.message)
                            .font(TypeRole.body(TypeScale.detail))
                            .foregroundStyle(Tokens.owed)
                    }
                    // D-224 (Design 8): the toast says it; D-245 (U17): and the done row, until the field changes.
                    if nameDoneFor != nil && nameDoneFor == NameRule.trimmed(name) {
                        DoneRow(text: DoneCopy.nameSaved)
                    }
                    // D-245 (U17): the primary style when there is a name to save; the secondary, dimmed, when not.
                    if nameSavable {
                        Button { saveName() } label: { Text(verbatim: WelcomeCopy.saveName) }
                            .buttonStyle(PrimaryButtonStyle())
                    } else {
                        Button { saveName() } label: { Text(verbatim: WelcomeCopy.saveName) }
                            .buttonStyle(SecondaryButtonStyle())
                            .disabled(true)
                    }
                }

                card {
                    micro("Picture")
                    emojiGrid
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Text("Choose a photo")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    if avatar != .none {
                        // D-245: removing keeps today's flow (no confirm), through the one save.
                        // Fix wave: through the same answer as the sheet's Use, staged and used at once (no sheet).
                        Button { stage.choose(.none); confirm(.use) } label: { Text("Remove picture").font(TypeRole.label()) }
                            .buttonStyle(SecondaryButtonStyle())
                    }
                }

                if let error {
                    Text(verbatim: error)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.owed)
                }

                // D-211: what the picture does depends on whether an account
                // exists; without one no cloud word appears (GUARANTEES #5).
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(ProfileCopy.footnotes(signedIn: account.isSignedIn), id: \.self) { line in
                        Text(verbatim: line)
                            .font(TypeRole.body(14))
                            .foregroundStyle(Tokens.textDim)
                    }
                }
            }
            .padding(20)
        }
        .auroraBackground() // D-176
        .navigationTitle("Profile")
        .navigationBarTitleDisplayMode(.inline)
        // D-245 (decision 161; CLAUDE.md 10.1): the confirm hangs on the always-present scroll view.
        .sheet(item: $confirming) { staged in
            PictureConfirmSheet(avatar: staged.avatar, name: savedName, signedIn: account.isSignedIn,
                                onUse: { confirm(.use) }, onNotNow: { confirm(.notNow) })
        }
        .task { load() }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                await MainActor.run {
                    if let data, let stored = ProfileRules.storedPhoto(from: data) {
                        stagePicture(.photo(stored))
                    } else {
                        error = WelcomeCopy.photoUnreadable
                    }
                    photoItem = nil
                }
            }
        }
    }

    private func load() {
        do {
            let me = try store.ensureSelf()
            savedName = me.name
            name = me.name ?? ""
            avatar = try store.loadAvatar()
        } catch {
            self.error = "\(error)"
        }
    }

    /// D-225 (Design 9, L76; art.py 43): `emojiColumns` equal square columns `emojiGap` apart; the chosen cell `accentSoft`
    /// inside an `emojiBorder` border in `accent` (`EmojiCell`), in the theme's panel shape; the emoji at a fixed
    /// `emojiGlyph` (it does not grow with the text size). Android's twin draws the same grid.
    private var emojiGrid: some View {
        let columns = Int(ThemeArt.m("emojiColumns"))
        let gap = ThemeArt.m("emojiGap")
        let border = ThemeArt.m("emojiBorder")
        let cell = ThemeArt.panelShape(AppearanceState.shared.theme, radius: ThemeArt.m("emojiRadius"), continuous: true)
        return VStack(spacing: gap) {
            ForEach(Array(EmojiGrid.rows(ProfileRules.emojiChoices, columns: columns).enumerated()), id: \.offset) { _, line in
                HStack(spacing: gap) {
                    ForEach(line, id: \.self) { e in
                        let paint = EmojiCell.paint(chosen: avatar == .emoji(e))
                        Button {
                            stagePicture(.emoji(e))
                        } label: {
                            Color.clear
                                .aspectRatio(1, contentMode: .fit)
                                .overlay { Text(verbatim: e).font(.system(size: ThemeArt.m("emojiGlyph"))) }
                                .background { if let fill = paint.fill { cell.fill(ThemeSwitch.ink(fill)) } }
                                .overlay {
                                    if let edge = paint.border {
                                        cell.stroke(ThemeSwitch.ink(edge), lineWidth: border).padding(border / 2)
                                    }
                                }
                                .contentShape(cell)
                        }
                        .buttonStyle(.plain)
                        .frame(maxWidth: .infinity)
                        .accessibilityLabel(e)
                        .accessibilityAddTraits(avatar == .emoji(e) ? .isSelected : [])
                    }
                    // A short last row keeps the columns' width.
                    ForEach(0..<(columns - line.count), id: \.self) { _ in
                        Color.clear.aspectRatio(1, contentMode: .fit).frame(maxWidth: .infinity)
                    }
                }
            }
        }
    }

    /// D-245 (U17): a name differs from the stored one and passes the rules.
    private var nameSavable: Bool { NameRule.dirty(name, saved: savedName) && NameRule.check(name) == nil }

    /// D-245 (decision 179): the rules in words, then the one save (D-211: your other phones and your groups; D-224's
    /// toast).
    /// `returned`: the keyboard's Return, which never saves an unchanged name again (fix wave M1).
    private func saveName(returned: Bool = false) {
        error = nil
        let outcome = returned ? ProfileSave.returned(name, saved: savedName, store: store) : ProfileSave.name(name, store: store)
        switch outcome {
        case nil:
            break
        case .saved(let stored)?:
            savedName = stored
            name = stored
            nameDoneFor = stored
        case .refused(let words)?:
            error = words
        }
    }

    /// D-245 (decision 161): a tap or a pick only stages the picture and opens the confirm.
    private func stagePicture(_ new: Avatar) {
        error = nil
        stage.choose(new)
        confirming = StagedPicture(avatar: new)
    }

    /// The sheet's answer (fix wave M6: through `ProfileSave.answer`, the one seam): Use writes the staged picture once
    /// (D-211: one record, one share request; D-224's toast), Not now writes nothing. Remove picture answers Use for
    /// `.none` (no sheet).
    private func confirm(_ answer: PictureAnswer) {
        error = nil
        confirming = nil
        do {
            if let written = try ProfileSave.answer(answer, stage: &stage, store: store) { avatar = written }
        } catch {
            stage.notNow()
            self.error = WelcomeCopy.pictureNotSaved
        }
    }


    private func micro(_ text: String) -> some View {
        Text(verbatim: text)
            .sectionLabel()
            .foregroundStyle(Tokens.textDim)
            .accessibilityLabel(text)
    }

    @ViewBuilder
    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) { content() }
            .glassCard() // D-176
    }
}

/// The top-right picture and the Settings profile card read the same thing:
/// your name and picture, reloaded whenever the profile changes.
struct ProfileBadge: View {
    let store: LedgerStore
    var size: CGFloat = 30
    /// D-221 (S12): in a navigation bar the badge is drawn as a bitmap in its original colours: the iOS 26 bar's glass
    /// re-tints a drawn view (Aurora light's accent read #027156 for #0B7A5F, Norse's #E7C16A for #CDA750), never an
    /// original-rendering image. Android draws the plain accent.
    var original = false
    @AppStorage(LedgerStore.profileRevisionKey) private var revision = 0
    @State private var avatar: Avatar = .none
    @State private var name: String?
    @State private var rendered: UIImage?
    @Environment(\.colorScheme) private var scheme
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        Group {
            if original {
                if let rendered {
                    Image(uiImage: rendered).renderingMode(.original).frame(width: size, height: size)
                } else {
                    Color.clear.frame(width: size, height: size)
                }
            } else {
                face
            }
        }
        .task(id: "\(revision)|\(scheme)|\(AppearanceState.shared.theme.rawValue)|\(displayScale)") {
            name = (try? store.loadSelf())?.name
            avatar = (try? store.loadAvatar()) ?? .none
            if original { rendered = BarBadge.render(face, scheme: scheme, scale: displayScale) }
        }
    }

    @ViewBuilder
    private var face: some View {
        if avatar == .none, ProfileRules.initials(name) == nil {
            // Nothing chosen yet: the plain glyph the button always had.
            LucideIcon(glyph: .userRound, size: size * 0.73)
                .foregroundStyle(Tokens.textDim)
                .frame(width: size, height: size)
        } else {
            // D-221 (Q2 a): Home's account badge is the one accent avatar.
            AvatarView(avatar: avatar, name: name, size: size, fill: .accent)
        }
    }
}

/// D-221 (S12): the bar badge's bitmap: the face rendered once per change (profile, appearance, scale) in the page's
/// colour scheme, so the bar shows the tokens' own colours.
enum BarBadge {
    @MainActor
    static func render(_ face: some View, scheme: ColorScheme, scale: CGFloat) -> UIImage? {
        let renderer = ImageRenderer(content: face.environment(\.colorScheme, scheme))
        renderer.scale = scale
        return renderer.uiImage
    }
}

/// D-206 (the founder's feedback item 5): the top of You. Your picture, big
/// and centred, your name, and your @username; without an account, "On this
/// phone only" (a fact, not a prompt: GUARANTEES #5). Opens Profile.
/// D-221 (L38 to L40, Q11 a): the ring round the picture (Norse the 24 runes, the others the arc), and the
/// progress pill in "Edit"'s place. `progress` is nil until the facts are read: the ring draws empty, no pill.
struct YouHero: View {
    let store: LedgerStore
    let signedIn: Bool
    let username: String?
    var progress: (done: Int, total: Int)?
    @AppStorage(LedgerStore.profileRevisionKey) private var revision = 0
    @State private var avatar: Avatar = .none
    @State private var name: String?
    @State private var viewing = false

    var body: some View {
        // D-211: the whole block is ONE link to Profile whatever the picture
        // is: a link whose place in the tree changed with the picture would be
        // torn down (and Profile popped) when saving a first photo flipped it.
        // A photo that decodes adds a button over the picture alone, which
        // opens it full screen; the cover hangs on this always-present stack
        // (CLAUDE.md 10.1).
        VStack(spacing: 0) {
            NavigationLink {
                ProfileScreen(store: store)
            } label: {
                VStack(spacing: 8) {
                    ringed
                    details
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(profileLabel)
            .overlay(alignment: .top) {
                if PictureViewing.canView(avatar) {
                    Button { viewing = true } label: {
                        Color.clear
                            .frame(width: 104, height: 104)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    // D-221: still the picture alone, centred in the ring's box.
                    .padding(.top, 8 + (ThemeArt.m("ringBox") - 104) / 2)
                    .accessibilityLabel("Your picture")
                }
            }
        }
        .fullScreenCover(isPresented: $viewing) { PictureViewer(avatar: avatar) }
        .onChange(of: avatar) { _, new in
            if !PictureViewing.canView(new) { viewing = false }
        }
        .task(id: revision) {
            name = (try? store.loadSelf())?.name
            avatar = (try? store.loadAvatar()) ?? .none
        }
    }

    /// The picture (D-206's 104) in the ring's box, the ring round it.
    private var ringed: some View {
        let shown = progress ?? (0, 0)
        return ZStack {
            Group {
                if AppearanceState.shared.theme == .norse {
                    // D-243: the ring turns on the art's clock (RingTurn); the picture inside it does not.
                    ArtTurn {
                        RuneRing(done: shown.done, total: shown.total)
                    }
                } else {
                    CompletionRing(done: shown.done, total: shown.total)
                }
            }
            // D-250: a step done while away sweeps the ring from what it showed (the welcome's sweep; runes light as it
            // passes); the first read lands at once. Fix wave (M12): on the ring alone, so a picture changed in the same
            // update never crossfades over the sweep's 0.9 s.
            .fillMotion(progress.map { RuneRing.share(done: $0.done, total: $0.total) })
            AvatarView(avatar: avatar, name: name, size: 104)
        }
        .frame(width: ThemeArt.m("ringBox"), height: ThemeArt.m("ringBox"))
    }

    private var profileLabel: String {
        let profile = name.map { String(localized: "Profile, \($0)") } ?? String(localized: "Profile, add your name")
        guard let progress else { return profile }
        return profile + ", " + StepCopy.pill(done: progress.done, total: progress.total)
    }

    private var details: some View {
        VStack(spacing: 8) {
            Text(verbatim: name ?? YouCopy.addName)
                .font(TypeRole.display(TypeScale.displayName, weight: 700))
                .foregroundStyle(Tokens.text)
            if let line = YouCopy.handleLine(signedIn: signedIn, username: username) {
                Text(verbatim: line)
                    .font(TypeRole.body(15, weight: 500))
                    .foregroundStyle(Tokens.textDim)
            }
            if let progress {
                ProgressPill(done: progress.done, total: progress.total)
            }
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
    }
}

/// D-206: the top of Balance: today's date and a greeting by the phone's
/// clock and the name on this phone (Greeting). Nothing leaves the phone.
struct HomeGreeting: View {
    let store: LedgerStore
    @AppStorage(LedgerStore.profileRevisionKey) private var revision = 0
    @State private var name: String?
    @State private var now = Date()

    /// D-217 (the Look Book's `.hello b`): 22 at 700; Norse's Cormorant SC is tracked +.02 em.
    static let greetingSize: CGFloat = 22
    static func greetingTracking(_ theme: AppTheme) -> CGFloat { theme == .norse ? greetingSize * 0.02 : 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: now.formatted(.dateTime.weekday(.wide).day().month(.wide)))
                .microLabelFont(TypeScale.dateLine)
                .foregroundStyle(Tokens.textDim)
            Text(verbatim: Greeting.line(hour: Calendar.current.component(.hour, from: now), name: name))
                .font(TypeRole.display(Self.greetingSize, weight: 700))
                .tracking(Self.greetingTracking(AppearanceState.shared.theme))
                .foregroundStyle(Tokens.text)
                .accessibilityAddTraits(.isHeader)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .task(id: revision) {
            now = Date()
            name = (try? store.loadSelf())?.name
        }
    }
}

/// D-206: You's words. Android's twin is YouCopy in AccountHubScreen.kt.
enum YouCopy {
    static let title = "You"
    static let addName = "Add your name"
    static let phoneOnly = "On this phone only"
    static let careful = "Careful"
    static let noAccount = "No account"
    static let signedIn = "Signed in"
    static let dataLine = "Export, import, currency"

    /// Under your name: your @username; nothing when signed in without one;
    /// "On this phone only" without an account.
    static func handleLine(signedIn: Bool, username: String?) -> String? {
        guard signedIn else { return phoneOnly }
        return username.map { "@" + $0 }
    }
}

/// Settings' first row: your picture and name, opening Profile.
struct ProfileCard: View {
    let store: LedgerStore
    @AppStorage(LedgerStore.profileRevisionKey) private var revision = 0
    @State private var avatar: Avatar = .none
    @State private var name: String?

    var body: some View {
        NavigationLink {
            ProfileScreen(store: store)
        } label: {
            HStack(spacing: 14) {
                AvatarView(avatar: avatar, name: name, size: 56)
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: name ?? String(localized: "Add your name"))
                        .font(TypeRole.body(17))
                        .foregroundStyle(Tokens.text)
                    Text(verbatim: ProfileCopy.sectionTitle)
                        .font(TypeRole.body(14))
                        .foregroundStyle(Tokens.textDim)
                }
                Spacer()
                Text("Edit")
                    .font(TypeRole.body(15))
                    .foregroundStyle(Tokens.accent)
                    .accessibilityHidden(true)
            }
            .glassCard() // D-176
            .contentShape(ThemeArt.cardShape(AppearanceState.shared.theme))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(name.map { String(localized: "Profile, \($0)") } ?? String(localized: "Profile, add your name"))
        .task(id: revision) {
            name = (try? store.loadSelf())?.name
            avatar = (try? store.loadAvatar()) ?? .none
        }
    }
}
