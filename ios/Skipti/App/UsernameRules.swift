import Foundation

/// C2: the username rule as the cloud decides it (firebase/functions/people.js),
/// so the phone answers at once. The list is the cloud's own file
/// (firebase/functions/blocklist/usernames.json), bundled as it is.
enum UsernameRules {
    enum Problem: Equatable { case shape, unavailable }

    struct List: Decodable, Equatable {
        let reserved: [String]
        let anywhere: [String]
        let whole: [String]
        let raw: [String]
    }

    /// The list with every term's pattern compiled once, as people.js does:
    /// `problem` runs on each keystroke and never compiles.
    struct Compiled {
        let raw: [String]
        let reserved: [NSRegularExpression]
        let anywhere: [NSRegularExpression]
        let whole: [NSRegularExpression]

        /// nil if a pattern does not compile (never: each letter is escaped);
        /// the cloud still decides then.
        init?(_ list: List) {
            func compile(_ terms: [String], anchored: Bool) -> [NSRegularExpression]? {
                var out: [NSRegularExpression] = []
                for t in terms {
                    let p = UsernameRules.pattern(t)
                    guard let r = try? NSRegularExpression(pattern: anchored ? "^" + p + "$" : p) else { return nil }
                    out.append(r)
                }
                return out
            }
            guard let reserved = compile(list.reserved, anchored: true),
                  let anywhere = compile(list.anywhere, anchored: false),
                  let whole = compile(list.whole, anchored: true) else { return nil }
            self.raw = list.raw
            self.reserved = reserved
            self.anywhere = anywhere
            self.whole = whole
        }
    }

    static let list: Compiled? = load(Bundle.main)

    static func load(_ bundle: Bundle) -> Compiled? {
        guard let url = bundle.url(forResource: "usernames", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let parsed = try? JSONDecoder().decode(List.self, from: data) else { return nil }
        return Compiled(parsed)
    }

    /// Trimmed (newlines too, as the cloud and Android trim), without a
    /// leading @, lower case.
    static func handle(_ raw: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return (t.hasPrefix("@") ? String(t.dropFirst()) : t).lowercased()
    }

    /// The change screen's suggestions: never the handle you already have
    /// (the cloud counts your own as free).
    static func changeSuggestions(_ all: [String], current: String) -> [String] {
        all.filter { $0 != current }
    }

    /// Why a username cannot be had; nil when the cloud alone can say (taken).
    static func problem(_ raw: String, list: Compiled? = list) -> Problem? {
        let h = handle(raw)
        guard h.range(of: "^[a-z0-9_]{3,20}$", options: .regularExpression) != nil else { return .shape }
        if let list, blocked(h, list: list) { return .unavailable }
        return nil
    }

    /// Each run of n equal letters may repeat, never shrink: boobs is b+o{2,}b+s+.
    static func pattern(_ term: String) -> String {
        let chars = Array(term.replacingOccurrences(of: "_", with: ""))
        var out = ""
        var i = 0
        while i < chars.count {
            var j = i
            while j < chars.count && chars[j] == chars[i] { j += 1 }
            out += NSRegularExpression.escapedPattern(for: String(chars[i])) + (j - i > 1 ? "{\(j - i),}" : "+")
            i = j
        }
        return out
    }

    private static let leetMap: [Character: Character] = ["0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "8": "b"]
    private static func leet(_ s: String) -> String { String(s.map { leetMap[$0] ?? $0 }) }
    private static func matches(_ r: NSRegularExpression, _ s: String) -> Bool {
        r.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    static func blocked(_ h: String, list: Compiled) -> Bool {
        if list.raw.contains(where: { h.contains($0) }) { return true }
        let full = leet(h).replacingOccurrences(of: "_", with: "")
        let bare = h.replacingOccurrences(of: "[0-9_]", with: "", options: .regularExpression)
        let parts = h.split(separator: "_").map { leet(String($0)) }
            + h.split(whereSeparator: { $0 == "_" || $0.isASCII && $0.isNumber }).map(String.init)
        if list.reserved.contains(where: { r in [full, bare].contains { matches(r, $0) } }) { return true }
        if list.anywhere.contains(where: { r in [full, bare].contains { matches(r, $0) } }) { return true }
        return list.whole.contains(where: { r in ([full, bare] + parts).contains { matches(r, $0) } })
    }
}

/// C2: the account's username, read from its cloud profile.
enum UsernameState: Equatable {
    case none
    case canChange(String)
    case final(String)

    /// nil: no profile document (not signed in, offline, or not registered yet).
    static func from(_ data: [String: Any]?) -> UsernameState? {
        guard let data else { return nil }
        guard let h = data["username"] as? String, !h.isEmpty else { return UsernameState.none }
        return data["usernameChanged"] as? Bool == true ? .final(h) : .canChange(h)
    }
}
