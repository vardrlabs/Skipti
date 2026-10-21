import ImageIO
import PhotosUI
import SwiftUI
import UIKit

/// D-187: a group's picture (the founder, 2026-09-30: "an option to add logo
/// or photo to the group as a personal touch"; decided: a photo or an emoji).
/// The picture is a GROUP record (core's `GroupOp::GroupPicture`), sealed like
/// every group record, so every member's phone shows it and the relay sees
/// only ciphertext. Android's twin is GroupPictures.kt.
/// D-235 round 4 (shells review M8, Android's SafeImage): a picture from the cloud or the vault, decoded with its size
/// read first and downsampled, never at full size. Core's frame-size check (wave A) stops new oversized pictures at
/// ingest; this keeps one stored before that build (or any other bytes) from forcing a huge decode on every screen
/// that shows it. Nil when the bytes are not an image or declare more than the bounds.
enum SafeImage {
    /// A declared side or pixel count past these is refused unread. D-235 final wave (decision 118): core's own bounds
    /// (`core/src/straumr/groups.rs`), which its ingest applies to every frame a JPEG declares, as Android's SafeImage
    /// has them; a test reads them there.
    struct Caps: Equatable {
        let maxSide: Int
        let maxPixels: Int
    }

    /// A group's photo or your own picture (core's `PICTURE_MAX_SIDE` / `PICTURE_MAX_PIXELS`).
    static let photo = Caps(maxSide: 2_048, maxPixels: 2_048 * 1_024)
    /// A member's preview (core's `THUMB_MAX_SIDE` / `THUMB_MAX_PIXELS`).
    static let preview = Caps(maxSide: 512, maxPixels: 512 * 512)
    /// The largest side ever drawn.
    static let drawMax = 2_048

    static func fits(width: Int, height: Int, caps: Caps = photo) -> Bool {
        width > 0 && height > 0 && width <= caps.maxSide && height <= caps.maxSide && width * height <= caps.maxPixels
    }

    static func decode(_ data: Data, caps: Caps = photo, maxPixel: Int = drawMax) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
              fits(width: width, height: height, caps: caps) else { return nil }
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ] as CFDictionary
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else { return nil }
        return UIImage(cgImage: cg)
    }
}

struct PictureDTO: Codable, Equatable {
    var emoji: String?
    /// A group's photo; in `ledger/member-previews` (D-211) it holds a
    /// member's PREVIEW instead (`thumbHex`: 120 px, at most 12,000 bytes),
    /// never their full picture.
    var photoHex: String?
}

enum GroupPictures {
    /// The emoji offered, in this order on both phones.
    static let emoji = [
        "✈️", "🏖️", "⛰️", "🏕️", "🚗", "🚆", "🏠", "🛒",
        "🍕", "🍜", "☕", "🍻", "🎉", "🎂", "🎁", "🎬",
        "⚽", "🏋️", "🎓", "💼", "💡", "🐶", "🎤", "❤️",
    ]
    static let title = "Group picture"
    static let choosePhoto = "Choose a photo"
    static let useLetter = "Use the letter"
    static let cancel = "Cancel"
    static let tooBig = "That photo could not be made small enough. Try another."
    static func spokenEmoji(_ emoji: String) -> String { "Use \(emoji)" }

    /// The largest photo core takes (`PICTURE_MAX_BYTES`).
    static let maxBytes = 64_000
    /// A photo is kept as a 3:2 landscape (the founder, 2026-09-30: the photo
    /// fills the group's card), sharp enough to cover a card edge to edge.
    static let width = 768
    static let height = 512
    /// The widths tried in turn: a busy photo (a field of flowers) does not
    /// fit 64,000 bytes at 768 even at the lowest quality, so it steps down
    /// rather than being refused (measured, D-187).
    static let widths = [768, 640, 512, 384]

    /// The largest 3:2 rectangle centred in a `width` × `height` image:
    /// (x, y, width, height). Android's twin is `GroupPictures.coverCrop`.
    static func coverCrop(width w: Int, height h: Int) -> (x: Int, y: Int, width: Int, height: Int) {
        if w * 2 >= h * 3 {
            let cw = h * 3 / 2
            return ((w - cw) / 2, 0, cw, h)
        }
        let ch = w * 2 / 3
        return (0, (h - ch) / 2, w, ch)
    }

    /// A chosen photo made into what a group carries: its centre 3:2 crop,
    /// 768 by 512 (smaller only when a busy photo will not fit), as a JPEG at
    /// the best quality that fits `maxBytes`, with
    /// the photo's own orientation applied. The pixels are drawn afresh, so
    /// nothing of the original file (its location, its camera) comes along.
    /// Nil when it will not decode or fit.
    static func prepare(_ data: Data) -> Data? {
        // Decoded at most 1,536 pixels on its long side, turned upright: a
        // 48-megapixel photo never sits whole in memory.
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 1_536,
        ] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else { return nil }
        return prepare(UIImage(cgImage: cg))
    }

    static func prepare(_ image: UIImage) -> Data? {
        let w = Int(image.size.width * image.scale), h = Int(image.size.height * image.scale)
        guard w > 0, h > 0 else { return nil }
        let crop = coverCrop(width: w, height: h)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        for outWidth in widths {
            let scale = CGFloat(outWidth) / CGFloat(crop.width)
            let size = CGSize(width: outWidth, height: outWidth * 2 / 3)
            let rendered = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                image.draw(in: CGRect(x: -CGFloat(crop.x) * scale, y: -CGFloat(crop.y) * scale,
                                      width: CGFloat(w) * scale, height: CGFloat(h) * scale))
            }
            for quality in [0.8, 0.7, 0.6, 0.5, 0.4] {
                if let jpeg = rendered.jpegData(compressionQuality: quality), jpeg.count <= maxBytes { return jpeg }
            }
        }
        return nil
    }
}

/// D-187 (the founder, 2026-09-30: "the entire card looks like that photo
/// with intelligent texts over it so that it doesn't blend in"): a group with
/// a photo shows it edge to edge under a black gradient, and its words are
/// white. The words never depend on the photo: they sit only where the
/// gradient is at least `textAlpha` black, and white there clears 4.5:1 even
/// over a pure white photo (`worstContrast`, measured in the tests). The top
/// `band` holds only the "Showing" chip, on its own film (the photo pill). Android's twin
/// is `PhotoCard` in GroupPictures.kt.
enum PhotoCard {
    static let topAlpha = 0.25
    static let textAlpha = 0.55
    static let bottomAlpha = 0.72
    static let chipAlpha = 0.55
    /// Points from the top where the gradient reaches `textAlpha`.
    static let band: CGFloat = 56
    static let cardHeight: CGFloat = 168
    static let headerHeight: CGFloat = 176

    /// WCAG contrast of white over `alpha` black laid on a white photo, the
    /// brightest a photo can be.
    static func worstContrast(alpha: Double) -> Double {
        let c = 1 - alpha
        let linear = c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        return 1.05 / (linear + 0.05)
    }
}

/// A group's photo edge to edge, under `PhotoCard`'s gradient.
struct PhotoBackdrop: View {
    let photo: UIImage

    var body: some View {
        Color.clear
            .overlay {
                Image(uiImage: photo).resizable().scaledToFill()
            }
            .overlay {
                VStack(spacing: 0) {
                    LinearGradient(colors: [.black.opacity(PhotoCard.topAlpha), .black.opacity(PhotoCard.textAlpha)],
                                   startPoint: .top, endPoint: .bottom)
                        .frame(height: PhotoCard.band)
                    LinearGradient(colors: [.black.opacity(PhotoCard.textAlpha), .black.opacity(PhotoCard.bottomAlpha)],
                                   startPoint: .top, endPoint: .bottom)
                }
            }
            .clipped()
            // Taps land within the frame: a filled photo overflows it, and the
            // overflow must not take its neighbours' taps.
            .contentShape(Rectangle())
            .accessibilityHidden(true)
    }
}

/// Every group's picture, by group id; loaded from the vault on each sync
/// (`SyncStatus.revision`, in HomeShell) and after a change here.
@Observable
@MainActor
final class GroupPictureStore {
    static let shared = GroupPictureStore()

    private(set) var byId: [String: PictureDTO] = [:]
    /// A photo's pixels, decoded once per distinct photo.
    @ObservationIgnored private var images: [String: UIImage] = [:]

    func load(_ store: LedgerStore) {
        byId = (try? store.groupPictures()) ?? [:]
    }

    func image(_ photoHex: String) -> UIImage? {
        if let cached = images[photoHex] { return cached }
        guard let bytes = try? HexCodec.decode(photoHex), let image = SafeImage.decode(bytes) else { return nil }
        images[photoHex] = image
        return image
    }
}

/// The chooser: a photo, one of the emoji, or the letter back.
/// `onChosen(emoji, jpeg)` has both nil for the letter; a photo that will
/// not decode or fit calls `onError`.
struct GroupPictureSheet: View {
    var onChosen: (String?, Data?) -> Void
    var onError: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var photoItem: PhotosPickerItem?
    /// A large photo takes a few seconds to arrive and shrink.
    @State private var preparing = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        if preparing {
                            ProgressView().tint(Tokens.onAccent)
                        } else {
                            Text(verbatim: GroupPictures.choosePhoto)
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(preparing)
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 6), spacing: 6) {
                        ForEach(GroupPictures.emoji, id: \.self) { e in
                            Button {
                                onChosen(e, nil)
                                dismiss()
                            } label: {
                                Text(verbatim: e)
                                    .font(.system(size: 26))
                                    .frame(width: 44, height: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text(verbatim: GroupPictures.spokenEmoji(e)))
                        }
                    }
                    Button(GroupPictures.useLetter) {
                        onChosen(nil, nil)
                        dismiss()
                    }
                    .buttonStyle(SecondaryButtonStyle())
                }
                .padding(20)
            }
            .auroraBackground() // D-176
            .navigationTitle(GroupPictures.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(GroupPictures.cancel) { dismiss() }.barTint()
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            preparing = true
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                let jpeg = data.flatMap(GroupPictures.prepare)
                await MainActor.run {
                    if let jpeg { onChosen(nil, jpeg) } else { onError(GroupPictures.tooBig) }
                    photoItem = nil
                    dismiss()
                }
            }
        }
    }
}
