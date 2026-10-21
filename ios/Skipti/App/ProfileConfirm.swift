import SwiftUI

// D-245 (U17, decisions 161, 178, 179): the name and picture confirm, shared by You's Profile page and the welcome's
// Your name and Your picture steps. A picture is STAGED by a tap (nothing written) and written once when confirmed
// ("Use this picture"); a name is checked in words before core sees it. Android's twin is ProfileConfirm.kt.

/// What is wrong with a typed name, in words (decision 179: never core's raw text).
enum NameProblem: Equatable {
    case empty
    case tooLong
    case badCharacter

    var message: String {
        switch self {
        case .empty: return WelcomeCopy.errEmpty
        case .tooLong: return WelcomeCopy.errLong
        case .badCharacter: return WelcomeCopy.errChar
        }
    }
}

/// D-245 (decision 179): the Your name field's rules. A name is trimmed, at least one character, and at most
/// `ProfileShare.nameMax` (60, the rules' `shortText(name, 60)`) counted in UTF-16 code units, as `ProfileShare.shareable`
/// counts it, so a saved name is always one the groups can be sent. Longer is refused, never cut. Anything else core's
/// `ffiMemberName` refuses (a control character) is `badCharacter`. Core's 64 for member names is unchanged.
enum NameRule {
    static var max: Int { ProfileShare.nameMax }

    static func trimmed(_ raw: String) -> String { raw.trimmingCharacters(in: .whitespacesAndNewlines) }

    static func check(_ raw: String) -> NameProblem? {
        let name = trimmed(raw)
        if name.isEmpty { return .empty }
        if name.utf16.count > max { return .tooLong }
        if (try? ffiMemberName(raw: raw)) == nil { return .badCharacter }
        return nil
    }

    /// The field differs from the stored name (a blank field never counts as a change to save).
    static func dirty(_ raw: String, saved: String?) -> Bool {
        let name = trimmed(raw)
        return !name.isEmpty && name != (saved ?? "")
    }

    /// What a typed name shows under the field while it is dirty: the problem in words, or nothing. An empty field shows
    /// nothing until a save is asked for (Return).
    static func liveProblem(_ raw: String, saved: String?) -> NameProblem? {
        guard dirty(raw, saved: saved) else { return nil }
        return check(raw)
    }

    /// Fix wave M1: Return asks for a save unless the field holds the saved name (an empty field still asks, so it says
    /// "Add a name first.").
    static func returnSaves(_ raw: String, saved: String?) -> Bool {
        trimmed(raw).isEmpty || dirty(raw, saved: saved)
    }
}

/// The picture confirm's two answers.
enum PictureAnswer: Equatable {
    case use, notNow
}

/// D-245: the one way a name or a picture is saved from Profile and the welcome. Each confirm writes once: one vault
/// write (`setYourName` / `saveAvatar`), one sync nudge, one profile share request, one toast. The effects are a seam so
/// a test can count them.
@MainActor
enum ProfileSave {
    struct Effects {
        /// Your other phones (`CloudSync.ledgerChanged`).
        var changed: @MainActor () -> Void
        /// Your groups (`ProfileShare.request`).
        var share: @MainActor () -> Void
        /// The top-right picture and the greeting read again (`profile.revision`).
        var bump: @MainActor () -> Void

        static let live = Effects(
            changed: { CloudSync.shared.ledgerChanged() },
            share: { _ = ProfileShare.request() },
            bump: {
                let defaults = UserDefaults.standard
                defaults.set(defaults.integer(forKey: LedgerStore.profileRevisionKey) + 1, forKey: LedgerStore.profileRevisionKey)
            })
    }

    /// How a save says it is done: Profile's toast (D-224, its success haptic with it), or, inside the welcome, the
    /// success haptic alone (decision 199: the welcome's done row is its done state).
    enum Feedback: Equatable {
        case toast, haptic
    }

    @MainActor
    static func done(_ moment: DoneMoment, _ feedback: Feedback, center: DoneCenter) {
        switch feedback {
        case .toast: center.post(moment)
        case .haptic: center.success()
        }
    }

    enum NameOutcome: Equatable {
        case saved(String)
        case refused(String)
    }

    /// The typed name, checked in words first (nothing is written when it is refused), then saved and shared once.
    static func name(_ raw: String, store: LedgerStore, effects: Effects = .live, feedback: Feedback = .toast,
                     center: DoneCenter = .shared) -> NameOutcome {
        if let problem = NameRule.check(raw) { return .refused(problem.message) }
        do {
            let me = try store.setYourName(raw)
            effects.bump()
            effects.changed()
            effects.share()
            done(DoneMoments.line(DoneCopy.nameSaved), feedback, center: center)
            return .saved(me.name ?? NameRule.trimmed(raw))
        } catch {
            // Fix wave M4: in words, never the raw error.
            return .refused(WelcomeCopy.nameNotSaved)
        }
    }

    /// Fix wave M1: the keyboard's Return. The same save, except that the saved name is not saved again: nil, and
    /// nothing is written, nudged or shared.
    static func returned(_ raw: String, saved: String?, store: LedgerStore, effects: Effects = .live,
                         feedback: Feedback = .toast, center: DoneCenter = .shared) -> NameOutcome? {
        guard NameRule.returnSaves(raw, saved: saved) else { return nil }
        return name(raw, store: store, effects: effects, feedback: feedback, center: center)
    }

    /// A confirmed picture (or the removal, `.none`): one `Avatar` record, one share request. Throws what the vault or
    /// core refused; nothing else happens then.
    static func picture(_ avatar: Avatar, store: LedgerStore, effects: Effects = .live, feedback: Feedback = .toast,
                        center: DoneCenter = .shared) throws {
        try store.saveAvatar(avatar)
        effects.bump()
        effects.changed()
        effects.share()
        done(DoneMoments.line(avatar == .none ? DoneCopy.pictureRemoved : DoneCopy.pictureSaved), feedback, center: center)
    }

    /// Fix wave M6: the confirm's answer, the one way Profile's sheet and the welcome's Use reach the vault. Use writes
    /// the staged picture once (`picture`) and records it in the stage; Not now drops the stage and writes nothing.
    /// Returns what was written (nil: nothing, including a Use with nothing staged). Throws what `picture` throws; the
    /// stage is unchanged then.
    @discardableResult
    static func answer(_ answer: PictureAnswer, stage: inout PictureStage, store: LedgerStore, effects: Effects = .live,
                       feedback: Feedback = .toast, center: DoneCenter = .shared) throws -> Avatar? {
        switch answer {
        case .notNow:
            stage.notNow()
            return nil
        case .use:
            guard let picked = stage.staged else { return nil }
            try picture(picked, store: store, effects: effects, feedback: feedback, center: center)
            stage.used(picked)
            return picked
        }
    }
}

/// D-245 (decision 161): a picture tapped or picked waits here until it is confirmed. `choose` only stages (nothing is
/// written); `notNow` drops it; `used` records what was written and the done row's words, which stay until the next
/// choice.
struct PictureStage: Equatable {
    private(set) var staged: Avatar?
    private(set) var done: String?

    mutating func choose(_ avatar: Avatar) {
        staged = avatar
        done = nil
    }

    mutating func notNow() { staged = nil }

    mutating func used(_ avatar: Avatar) {
        staged = nil
        done = avatar == .none ? DoneCopy.pictureRemoved : DoneCopy.pictureSaved
    }
}

/// The sheet's item: a staged picture (a new identity per choice, so a second choice re-presents it).
struct StagedPicture: Identifiable {
    let id = UUID()
    let avatar: Avatar
}

/// D-245: a done row ("Name saved", "Picture saved"): the toast's owing dot holding Lucide's check, then the words. It
/// stays until what it confirms changes. Read as one element.
struct DoneRow: View {
    let text: String

    var body: some View {
        let dot = ThemeArt.m("toastDot")
        HStack(spacing: ThemeArt.m("toastGap")) {
            LucideIcon(glyph: .check, size: ThemeArt.m("toastGlyph"), stroke: ThemeArt.m("toastCheckStroke"))
                .foregroundStyle(Tokens.onAccent)
                .frame(width: dot, height: dot)
                .background(Tokens.owing, in: Circle())
                .accessibilityHidden(true)
            Text(verbatim: text)
                .font(TypeRole.name(TypeScale.detail))
                .foregroundStyle(Tokens.text)
        }
        .accessibilityElement(children: .combine)
    }
}

/// D-245 (decisions 161, 178): the confirm's content: the big preview of the picture (staged or saved), its done row, and
/// signed in only the line that the groups see a small copy. One view in two places: You's sheet (with its two buttons
/// around it in `PictureConfirmSheet`) and the welcome's Your picture page (with the choices under it and the step's
/// own bottom action).
struct PictureConfirm<Choices: View>: View {
    static var previewSize: CGFloat { 104 }

    let avatar: Avatar
    let name: String?
    let signedIn: Bool
    var done: String?
    @ViewBuilder var choices: () -> Choices

    var body: some View {
        VStack(spacing: 16) {
            AvatarView(avatar: avatar, name: name, size: Self.previewSize)
            if let done { DoneRow(text: done) }
            if signedIn {
                Text(verbatim: WelcomeCopy.groupsPicture)
                    .font(TypeRole.body(TypeScale.detail))
                    .foregroundStyle(Tokens.textDim)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            choices()
        }
        .frame(maxWidth: .infinity)
    }
}

/// D-245 (decision 161): You's picture sheet. "Use this picture" writes once and closes it; "Not now" writes nothing.
struct PictureConfirmSheet: View {
    let avatar: Avatar
    let name: String?
    let signedIn: Bool
    let onUse: () -> Void
    let onNotNow: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Spacer(minLength: 0)
                PictureConfirm(avatar: avatar, name: name, signedIn: signedIn) { EmptyView() }
                Spacer(minLength: 0)
                VStack(spacing: 10) {
                    Button(action: onUse) { Text(verbatim: WelcomeCopy.use) }
                        .buttonStyle(PrimaryButtonStyle())
                    Button(action: onNotNow) { Text(verbatim: WelcomeCopy.notNow) }
                        .buttonStyle(SecondaryButtonStyle())
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .auroraBackground()
            .navigationTitle(WelcomeCopy.sheetTitle)
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
}

/// D-245 (W4): the welcome's emoji choices, drawn as Profile's grid draws its cells (`EmojiCell`, the theme's panel
/// shape, `emojiGlyph`): the first six and "More" in one row, or, opened, all 48 in `emojiColumns` columns. A tap only
/// stages.
struct EmojiChoices: View {
    let chosen: Avatar?
    let open: Bool
    let onMore: () -> Void
    let onTap: (String) -> Void

    /// The first row's emoji (the plan's six).
    static let firstRow = 6

    var body: some View {
        let gap = ThemeArt.m("emojiGap")
        if open {
            let columns = Int(ThemeArt.m("emojiColumns"))
            VStack(spacing: gap) {
                ForEach(Array(EmojiGrid.rows(ProfileRules.emojiChoices, columns: columns).enumerated()), id: \.offset) { _, line in
                    HStack(spacing: gap) {
                        ForEach(line, id: \.self) { cell($0) }
                        ForEach(0..<(columns - line.count), id: \.self) { _ in
                            Color.clear.aspectRatio(1, contentMode: .fit).frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        } else if AppearanceState.shared.contentSizeCategory.isAccessibilityCategory {
            // Fix wave I3: at the accessibility sizes "More" outgrows a seventh of the row; it takes its own row.
            VStack(spacing: gap) {
                HStack(spacing: gap) {
                    ForEach(Array(ProfileRules.emojiChoices.prefix(Self.firstRow)), id: \.self) { cell($0) }
                }
                more
            }
        } else {
            HStack(spacing: gap) {
                ForEach(Array(ProfileRules.emojiChoices.prefix(Self.firstRow)), id: \.self) { cell($0) }
                more
            }
        }
    }

    private var more: some View {
        Button(action: onMore) {
            Text(verbatim: WelcomeCopy.more)
                .font(TypeRole.label(TypeScale.detail))
                .foregroundStyle(Tokens.accent)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
    }

    private func cell(_ e: String) -> some View {
        let border = ThemeArt.m("emojiBorder")
        let shape = ThemeArt.panelShape(AppearanceState.shared.theme, radius: ThemeArt.m("emojiRadius"), continuous: true)
        let paint = EmojiCell.paint(chosen: chosen == .emoji(e))
        return Button { onTap(e) } label: {
            Color.clear
                .aspectRatio(1, contentMode: .fit)
                .overlay { Text(verbatim: e).font(.system(size: ThemeArt.m("emojiGlyph"))) }
                .background { if let fill = paint.fill { shape.fill(ThemeSwitch.ink(fill)) } }
                .overlay { if let edge = paint.border { shape.stroke(ThemeSwitch.ink(edge), lineWidth: border).padding(border / 2) } }
                .contentShape(shape)
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
        .accessibilityLabel(e)
        .accessibilityAddTraits(chosen == .emoji(e) ? .isSelected : [])
    }
}
