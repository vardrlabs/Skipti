import SwiftUI
import UIKit

/// D-128: Lucide's drawings on iOS, from the SAME path data Android ships.
///
/// WHY A PARSER. NOTICE promises the icons are "copied VERBATIM from
/// upstream: their drawing, not a redraw". Android's `TabIcons.kt` feeds
/// Lucide's `d` strings to Compose's `PathParser`; SwiftUI has no SVG path
/// parser, so without this file the only options were SF Symbols (a
/// different drawing, and a broken licence statement) or hand-copying the
/// geometry (a redraw). The strings below are byte-for-byte Android's, and
/// the parser turns them into `Path`s.
///
/// ONE REPRESENTATION CHANGE, stated: SVG elliptical arcs (`A`/`a`) have
/// no direct `Path` primitive, so each arc is converted to cubic Béziers
/// of at most 90° per the W3C SVG endpoint-to-centre algorithm (F.6.5).
/// The geometry is upstream's; only its encoding changes, which is also
/// what Compose's parser does internally.
enum SVGPath {
    enum ParseError: Error, Equatable {
        case expectedCommand(at: Int)
        case malformedNumber(at: Int)
        case malformedFlag(at: Int)
        case unsupportedCommand(Character)
    }

    /// Supports exactly what SKIPTI's Lucide glyphs use: M L H V A C Z
    /// (C since D-189's bell), absolute and relative, with implicit command repetition and
    /// SVG's jammed-number grammar (`1.3.7`, `1-6`). Anything else is a
    /// typed refusal, never a silently partial drawing.
    static func parse(_ d: String) throws -> Path {
        var s = Tokens(d)
        var path = Path()
        var current = CGPoint.zero
        var subpathStart = CGPoint.zero
        var command: Character?
        var needsMoveAfterClose = false

        func beginSegmentIfClosed() {
            if needsMoveAfterClose {
                path.move(to: current)
                needsMoveAfterClose = false
            }
        }

        while !s.atEnd {
            if let letter = s.command() {
                command = letter
            } else if command == nil {
                throw ParseError.expectedCommand(at: s.index)
            }
            guard let c = command else { throw ParseError.expectedCommand(at: s.index) }
            let relative = c.isLowercase

            switch Character(c.lowercased()) {
            case "m":
                let x = try s.number(), y = try s.number()
                let p = relative ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y)
                path.move(to: p)
                current = p
                subpathStart = p
                needsMoveAfterClose = false
                // SVG: coordinate pairs after a moveto are implicit linetos.
                command = relative ? "l" : "L"
            case "l":
                let x = try s.number(), y = try s.number()
                beginSegmentIfClosed()
                let p = relative ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y)
                path.addLine(to: p)
                current = p
            case "h":
                let x = try s.number()
                beginSegmentIfClosed()
                let p = CGPoint(x: relative ? current.x + x : x, y: current.y)
                path.addLine(to: p)
                current = p
            case "v":
                let y = try s.number()
                beginSegmentIfClosed()
                let p = CGPoint(x: current.x, y: relative ? current.y + y : y)
                path.addLine(to: p)
                current = p
            case "a":
                let rx = try s.number(), ry = try s.number(), rotation = try s.number()
                let largeArc = try s.flag(), sweep = try s.flag()
                let x = try s.number(), y = try s.number()
                beginSegmentIfClosed()
                let p = relative ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y)
                addArc(&path, from: current, to: p, rx: rx, ry: ry,
                       rotationDegrees: rotation, largeArc: largeArc, sweep: sweep)
                current = p
            case "c":
                let x1 = try s.number(), y1 = try s.number()
                let x2 = try s.number(), y2 = try s.number()
                let x = try s.number(), y = try s.number()
                beginSegmentIfClosed()
                let o = relative ? current : .zero
                let p = CGPoint(x: o.x + x, y: o.y + y)
                path.addCurve(to: p, control1: CGPoint(x: o.x + x1, y: o.y + y1),
                              control2: CGPoint(x: o.x + x2, y: o.y + y2))
                current = p
            case "z":
                path.closeSubpath()
                current = subpathStart
                needsMoveAfterClose = true
                // `z` takes no arguments: the next token must be a command.
                command = nil
            default:
                throw ParseError.unsupportedCommand(c)
            }
        }
        return path
    }

    /// W3C SVG 1.1 F.6.5 (endpoint → centre parameterisation) with F.6.6's
    /// out-of-range radius correction, emitted as ≤90° cubic segments.
    private static func addArc(
        _ path: inout Path, from p0: CGPoint, to p1: CGPoint,
        rx rxIn: CGFloat, ry ryIn: CGFloat, rotationDegrees: CGFloat,
        largeArc: Bool, sweep: Bool
    ) {
        if p0 == p1 { return }
        var rx = abs(rxIn), ry = abs(ryIn)
        if rx == 0 || ry == 0 { path.addLine(to: p1); return }

        let phi = rotationDegrees * .pi / 180
        let cosPhi = cos(phi), sinPhi = sin(phi)
        let dx2 = (p0.x - p1.x) / 2, dy2 = (p0.y - p1.y) / 2
        let x1p = cosPhi * dx2 + sinPhi * dy2
        let y1p = -sinPhi * dx2 + cosPhi * dy2

        let lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if lambda > 1 { let k = sqrt(lambda); rx *= k; ry *= k }

        let rx2 = rx * rx, ry2 = ry * ry
        let numerator = max(0, rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p)
        let denominator = rx2 * y1p * y1p + ry2 * x1p * x1p
        var coef = denominator == 0 ? 0 : sqrt(numerator / denominator)
        if largeArc == sweep { coef = -coef }
        let cxp = coef * (rx * y1p / ry)
        let cyp = coef * -(ry * x1p / rx)
        let cx = cosPhi * cxp - sinPhi * cyp + (p0.x + p1.x) / 2
        let cy = sinPhi * cxp + cosPhi * cyp + (p0.y + p1.y) / 2

        func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            let dot = ux * vx + uy * vy
            let len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            var a = acos(max(-1, min(1, dot / len)))
            if ux * vy - uy * vx < 0 { a = -a }
            return a
        }
        let theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if !sweep && delta > 0 { delta -= 2 * .pi } else if sweep && delta < 0 { delta += 2 * .pi }

        let segments = max(1, Int(ceil(abs(delta) / (.pi / 2))))
        let step = delta / CGFloat(segments)
        let t = 4.0 / 3.0 * tan(step / 4)

        func map(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            let ex = x * rx, ey = y * ry
            return CGPoint(x: cosPhi * ex - sinPhi * ey + cx, y: sinPhi * ex + cosPhi * ey + cy)
        }
        var theta = theta1
        for _ in 0..<segments {
            let next = theta + step
            let (c1, s1, c2, s2) = (cos(theta), sin(theta), cos(next), sin(next))
            path.addCurve(
                to: map(c2, s2),
                control1: map(c1 - t * s1, s1 + t * c1),
                control2: map(c2 + t * s2, s2 - t * c2)
            )
            theta = next
        }
    }

    /// SVG path tokenizer: command letters, and numbers under SVG's grammar
    /// where separators are optional (`1.3.7` is two numbers, `1-6` is two).
    private struct Tokens {
        private let chars: [Character]
        private(set) var index = 0

        init(_ string: String) { chars = Array(string) }

        private mutating func skipSeparators() {
            while index < chars.count, chars[index] == " " || chars[index] == ","
                || chars[index] == "\n" || chars[index] == "\t" || chars[index] == "\r" {
                index += 1
            }
        }

        var atEnd: Bool {
            mutating get { skipSeparators(); return index >= chars.count }
        }

        mutating func command() -> Character? {
            skipSeparators()
            guard index < chars.count, chars[index].isLetter else { return nil }
            defer { index += 1 }
            return chars[index]
        }

        mutating func number() throws -> CGFloat {
            skipSeparators()
            let start = index
            var text = ""
            if index < chars.count, chars[index] == "-" || chars[index] == "+" {
                text.append(chars[index]); index += 1
            }
            var sawDot = false, sawDigit = false
            while index < chars.count {
                let c = chars[index]
                if c.isASCII && c.isNumber {
                    text.append(c); sawDigit = true; index += 1
                } else if c == "." && !sawDot {
                    text.append(c); sawDot = true; index += 1
                } else if (c == "e" || c == "E") && sawDigit {
                    text.append(c); index += 1
                    if index < chars.count, chars[index] == "-" || chars[index] == "+" {
                        text.append(chars[index]); index += 1
                    }
                    while index < chars.count, chars[index].isASCII, chars[index].isNumber {
                        text.append(chars[index]); index += 1
                    }
                    break
                } else {
                    break
                }
            }
            guard sawDigit, let value = Double(text) else {
                throw ParseError.malformedNumber(at: start)
            }
            return CGFloat(value)
        }

        /// Arc flags are single characters and may be jammed against the
        /// next number (`0 01.4` is flag, flag, 1.4).
        mutating func flag() throws -> Bool {
            skipSeparators()
            guard index < chars.count, chars[index] == "0" || chars[index] == "1" else {
                throw ParseError.malformedFlag(at: index)
            }
            defer { index += 1 }
            return chars[index] == "1"
        }
    }
}

/// D-219 (the Look Book's `stroke-width`, L18): the one stroke every Lucide drawing takes, in 24-unit viewport units,
/// round caps and joins. `width` is the icons' (the Look Book draws them at 2.4); `plus` the + glyph's, heavier (the Look
/// Book draws it inline at 2.8). Android's twin is `LucideStroke` in TabIcons.kt; its ThemeArtTest reads these two lines.
enum LucideStroke {
    static let width: CGFloat = 2.4
    static let plus: CGFloat = 2.8
}

/// The Lucide drawings SKIPTI ships (NOTICE: twenty-one since D-249). Path data is copied
/// VERBATIM from `android/app/src/main/java/com/vardrlabs/skipti/TabIcons.kt`.
/// If these strings ever differ from Android's, one shell is drawing a
/// different icon, and the D-128 test pins them.
enum LucideGlyph: String, CaseIterable {
    case wallet
    case users
    case receipt
    case plus
    case circleCheck = "circle-check"
    case userRound = "user-round"
    /// D-186: add people to a group.
    case userPlus = "user-plus"
    /// D-217: the back button's chevron and the chevron at the end of a list row (the SF Symbols they replace drew a
    /// different shape on each phone).
    case chevronLeft = "chevron-left"
    case chevronRight = "chevron-right"
    /// D-220: the currency list's search field and its selected row (the SF Symbols they replace drew a different shape
    /// on each phone).
    case search
    case check
    /// D-222 (L56): the icon tiles on You's rows: Appearance, Security, Your data, About (Profile and account takes
    /// `user-round`).
    case sun
    case shield
    case download
    case info
    /// D-223 (L-D217-10): the drawn empty state (the empty Expenses list).
    case inbox
    /// D-224 (L48): the Haptics row's icon tile on Appearance.
    case vibrate
    /// D-225: the clear and remove cross (People's remove, New group's clear) and the lock screen's glyph.
    case x
    case lock
    /// D-231 (decision 69): the Less motion row's icon tile on Appearance.
    case sparkles
    /// D-249 (decision 163): the Spending on Home row's icon tile on Appearance.
    case chartPie = "chart-pie"

    var pathData: [String] {
        switch self {
        case .wallet:
            return [
                "M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1",
                "M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4",
            ]
        // D-179: `<circle cx=9 cy=7 r=4>` as its arc path, geometry
        // unchanged (the same encoding as user-round below).
        case .users:
            return [
                "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
                "M16 3.128a4 4 0 0 1 0 7.744",
                "M22 21v-2a4 4 0 0 0-3-3.87",
                "M9 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8",
            ]
        case .receipt:
            return [
                "M12 17V7",
                "M16 8h-6a2 2 0 0 0 0 4h4a2 2 0 0 1 0 4H8",
                "M4 3a1 1 0 0 1 1-1 1.3 1.3 0 0 1 .7.2l.933.6a1.3 1.3 0 0 0 1.4 0l.934-.6a1.3 1.3 0 0 1 1.4 0l.933.6a1.3 1.3 0 0 0 1.4 0l.933-.6a1.3 1.3 0 0 1 1.4 0l.934.6a1.3 1.3 0 0 0 1.4 0l.933-.6A1.3 1.3 0 0 1 19 2a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1 1.3 1.3 0 0 1-.7-.2l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.934.6a1.3 1.3 0 0 1-1.4 0l-.933-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-1.4 0l-.934-.6a1.3 1.3 0 0 0-1.4 0l-.933.6a1.3 1.3 0 0 1-.7.2 1 1 0 0 1-1-1z",
            ]
        case .plus:
            return ["M5 12h14", "M12 5v14"]
        // D-179: `<circle cx=12 cy=12 r=10>` as its arc path.
        case .circleCheck:
            return ["M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20", "m16 9-5.5 5.5L8 12"]
        case .userRound:
            return ["M12 3a5 5 0 1 0 0 10 5 5 0 1 0 0-10", "M20 21a8 8 0 0 0-16 0"]
        // D-186: read 2026-09-30 from upstream main (icons/user-plus.svg):
        // the path verbatim; `<circle cx=9 cy=7 r=4>` as the arc path, as
        // `users` writes it; its two `<line>`s as the same segments.
        case .userPlus:
            return [
                "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
                "M9 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8",
                "M19 8v6",
                "M22 11h-6",
            ]
        // D-217: read 2026-10-15 from upstream (lucide-static icons/chevron-left.svg and chevron-right.svg): the path
        // data verbatim. Both are on Lucide's Feather-derived list: ISC and MIT (NOTICE).
        case .chevronLeft:
            return ["m15 18-6-6 6-6"]
        case .chevronRight:
            return ["m9 18 6-6-6-6"]
        // D-220: read 2026-10-16 from upstream (lucide-static v1.58.0 icons/search.svg and check.svg): the path data
        // verbatim; search's `<circle cx=11 cy=11 r=8>` as its arc path, as `users` writes it. Both are on Lucide's
        // Feather-derived list: ISC and MIT (NOTICE).
        case .search:
            return ["m21 21-4.34-4.34", "M11 3a8 8 0 1 0 0 16 8 8 0 1 0 0-16"]
        case .check:
            return ["M20 6 9 17l-5-5"]
        // D-222: read 2026-10-16 from upstream (lucide-static v1.58.0 icons/sun.svg, shield.svg, download.svg and
        // info.svg): the path data verbatim, in upstream's order; each `<circle>` as its two-arc path from the top (sun
        // cx 12 cy 12 r 4, info cx 12 cy 12 r 10), as `user-round` and `circle-check` write theirs. `download` and `info`
        // are on Lucide's Feather-derived list (ISC and MIT); `sun` and `shield` are not (ISC only). NOTICE names them.
        case .sun:
            return [
                "M12 8a4 4 0 1 0 0 8 4 4 0 1 0 0-8", "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41",
                "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41",
            ]
        case .shield:
            return [
                "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
            ]
        case .download:
            return ["M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5"]
        case .info:
            return ["M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20", "M12 16v-4", "M12 8h.01"]
        // D-223: read 2026-10-17 from upstream (lucide-static v1.59.0 icons/inbox.svg): in upstream's order, the
        // `<polyline points="22 12 16 12 14 15 10 15 8 12 2 12">` written as `M` then `L` through its points in order
        // (the same segments), then the path data verbatim. Not on Lucide's Feather-derived list: ISC only (NOTICE).
        case .inbox:
            return [
                "M22 12 L16 12 L14 15 L10 15 L8 12 L2 12",
                "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
            ]
        // D-224: read 2026-10-17 from upstream (lucide-static v1.59.0 icons/vibrate.svg): in upstream's order, both paths
        // verbatim, then the `<rect width="8" height="14" x="8" y="5" rx="1">` written as SVG 2's equivalent path for a
        // rounded rect (move to (x + rx, y), then line and corner arc clockwise round the four sides, absolute commands
        // spaced as `inbox` writes its polyline; the same geometry). Not on Lucide's Feather-derived list: ISC only (NOTICE).
        case .vibrate:
            return [
                "m2 8 2 2-2 2 2 2-2 2",
                "m22 8-2 2 2 2-2 2 2 2",
                "M9 5 L15 5 A1 1 0 0 1 16 6 L16 18 A1 1 0 0 1 15 19 L9 19 A1 1 0 0 1 8 18 L8 6 A1 1 0 0 1 9 5 Z",
            ]
        // D-225: read 2026-10-17 from upstream (lucide-static v1.59.0 icons/x.svg and lock.svg). `x`: both paths
        // verbatim. `lock`: in upstream's order, its `<rect width="18" height="11" x="3" y="11" rx="2" ry="2">` written as
        // SVG 2's equivalent path for a rounded rect, as `vibrate` writes its rect (move to (x + rx, y), then line and
        // corner arc clockwise round the four sides, absolute commands; the same geometry), then its path verbatim. Both
        // are on Lucide's Feather-derived list: ISC and MIT (NOTICE).
        case .x:
            return ["M18 6 6 18", "m6 6 12 12"]
        case .lock:
            return [
                "M5 11 L19 11 A2 2 0 0 1 21 13 L21 20 A2 2 0 0 1 19 22 L5 22 A2 2 0 0 1 3 20 L3 13 A2 2 0 0 1 5 11 Z",
                "M7 11V7a5 5 0 0 1 10 0v4",
            ]
        // D-231: read 2026-10-19 from upstream (lucide-static v1.59.0 icons/sparkles.svg): in upstream's order, its paths
        // verbatim, then its `<circle cx="4" cy="20" r="2">` as its two-arc path from the top, as `user-round` writes its
        // circle (the same geometry). Not on Lucide's Feather-derived list (Feather has no sparkles): ISC only (NOTICE).
        case .sparkles:
            return [
                "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
                "M20 2v4",
                "M22 4h-4",
                "M4 18a2 2 0 1 0 0 4 2 2 0 1 0 0-4",
            ]
        // D-249: read 2026-10-21 from upstream (lucide-static v1.59.0 icons/chart-pie.svg), its two paths verbatim, in
        // upstream's order. Not on Lucide's Feather-derived list: ISC only (NOTICE).
        case .chartPie:
            return [
                "M21 12c.552 0 1.005-.449.95-.998a10 10 0 0 0-8.953-8.951c-.55-.055-.998.398-.998.95v8a1 1 0 0 0 1 1z",
                "M21.21 15.89A10 10 0 1 1 8 2.83",
            ]
        }
    }

    /// The glyph in Lucide's 24×24 viewport. A string that fails to parse
    /// contributes nothing rather than crashing the UI, and the test
    /// asserts every string parses, so that fallback never ships silently.
    func path() -> Path {
        var combined = Path()
        for d in pathData {
            if let p = try? SVGPath.parse(d) { combined.addPath(p) }
        }
        return combined
    }

    /// A TEMPLATE image of the stroked glyph. This exists because SwiftUI's
    /// `tabItem` renders only `Image` and `Text`: a custom `Shape` placed
    /// there is silently dropped and the tab shows no icon at all. Stroke
    /// matches Android's `TabIcons.kt`: `LucideStroke.width` in the 24-unit
    /// viewport, round caps and joins; colour comes from the tint at render time.
    func templateImage(pointSize: CGFloat = 24) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: pointSize, height: pointSize))
        let image = renderer.image { context in
            let scale = pointSize / 24
            context.cgContext.scaleBy(x: scale, y: scale)
            stroke(in: context.cgContext, color: .black)
        }
        return image.withRenderingMode(.alwaysTemplate)
    }

    /// The one stroke both images share: the glyph's path in [color], `LucideStroke.width` units, round caps and joins,
    /// in the context's current (Lucide unit) coordinates.
    fileprivate func stroke(in cg: CGContext, color: UIColor) {
        cg.addPath(path().cgPath)
        cg.setLineWidth(LucideStroke.width)
        cg.setLineCap(.round)
        cg.setLineJoin(.round)
        cg.setStrokeColor(color.cgColor)
        cg.strokePath()
    }
}

extension LucideGlyph {
    /// D-217: the glyph stroked in [color] on a canvas that fits its ink (the path's bounds plus the stroke), at
    /// [pointsPerUnit] points per Lucide unit: a UIKit image that needs no padding of its own (the back button's
    /// indicator). Stroke as `templateImage`: `LucideStroke.width` units, round caps and joins.
    func inkImage(color: UIColor, pointsPerUnit: CGFloat) -> UIImage {
        let ink = path().cgPath.boundingBoxOfPath.insetBy(dx: -LucideStroke.width / 2, dy: -LucideStroke.width / 2)
        let size = CGSize(width: ink.width * pointsPerUnit, height: ink.height * pointsPerUnit)
        return UIGraphicsImageRenderer(size: size).image { context in
            let cg = context.cgContext
            cg.scaleBy(x: pointsPerUnit, y: pointsPerUnit)
            cg.translateBy(x: -ink.minX, y: -ink.minY)
            stroke(in: cg, color: color)
        }
    }
}

/// D-217: the chevron at the end of a list row: Lucide's chevron-right, the same drawing on both phones, drawn at a
/// size whose ink matches the SF Symbol footnote chevron it replaces. Decorative: hidden from VoiceOver.
struct RowChevron: View {
    static let size: CGFloat = 20
    var color: Color = Tokens.textFaint

    var body: some View {
        LucideIcon(glyph: .chevronRight, size: Self.size)
            .foregroundStyle(color)
            .accessibilityHidden(true)
    }
}

/// The glyph as a SwiftUI view, for places that DO render custom views
/// (toolbars, rows). Stroked in the current foreground style at `stroke` units
/// of the 24-unit viewport (D-219: `LucideStroke.width`; the + takes `LucideStroke.plus`).
struct LucideIcon: View {
    let glyph: LucideGlyph
    var size: CGFloat = 24
    var stroke: CGFloat = LucideStroke.width

    var body: some View {
        LucideShape(glyph: glyph)
            .stroke(style: StrokeStyle(lineWidth: stroke * size / 24, lineCap: .round, lineJoin: .round))
            .frame(width: size, height: size)
    }
}

private struct LucideShape: Shape {
    let glyph: LucideGlyph

    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width, rect.height) / 24
        return glyph.path()
            .applying(CGAffineTransform(scaleX: scale, y: scale))
            .offsetBy(dx: rect.minX, dy: rect.minY)
    }
}
