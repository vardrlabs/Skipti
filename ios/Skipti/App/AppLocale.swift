import Foundation

/// Locale is an axis SEPARATE from currency (D-50): en-IN formatting gives
/// lakh/crore grouping (₹12,34,567.89); anything else formats western.
/// Currency comes from the RECORD, never from the device: en-US
/// formatting of INR and en-IN formatting of USD are both legitimate.
enum AppLocale {
    static func tag(_ locale: Locale = .current) -> LocaleTag {
        let language = locale.language.languageCode?.identifier ?? "en"
        let region = locale.region?.identifier ?? ""
        if language == "en" && region == "IN" {
            return .enIn
        }
        return .enUs
    }

    /// The tag every money-formatting call site uses.
    static var current: LocaleTag { tag() }
}

/// D-228: what a screen shows where a value cannot be formatted (the money fallbacks, the Overview's settled tile):
/// SKIPTI's own typed hyphen, one constant. Android's twin is `Placeholder.NONE` in AppLocale.kt.
enum Placeholder {
    static let none = "-"

    /// D-228 decision 43: how VoiceOver says `none` where it is shown as a value (never "hyphen" or "minus"); the
    /// twin of Android's `Placeholder.SPOKEN_NONE`. A spoken money label that core cannot form stays "" instead.
    static let spokenNone = "none"

    /// The spoken form of a shown value: `spokenNone` for the placeholder, else the value itself (Android's twin is
    /// `Placeholder.spoken`).
    static func spoken(_ shown: String) -> String {
        shown == none ? spokenNone : shown
    }
}

/// D-144: every date the app shows, formatted in ONE place. The same
/// template bug ("dMMMmy": `m` is MINUTE, not month) was fixed in the
/// onboarding kit (D-127) and the Expenses list (D-135) and survived in the
/// Recovery Kit and import screens; one helper ends the copies. Dates are
/// presentation and format in Swift (D-54); money never does.
enum AppDate {
    /// Day, month, year: "Sep 16, 2026" / "16 Sept 2026".
    static func day(_ ms: UInt64, locale: LocaleTag = AppLocale.current, timeZone: TimeZone = .current) -> String {
        format(ms, template: "dMMMy", locale: locale, timeZone: timeZone)
    }

    /// Day and month: "Oct 16" / "16 Oct" (D-223: an older change on Lately and Recent activity).
    static func dayMonth(_ ms: UInt64, locale: LocaleTag = AppLocale.current, timeZone: TimeZone = .current) -> String {
        format(ms, template: "dMMM", locale: locale, timeZone: timeZone)
    }

    /// Day, month, year and time, for SPEC §5.7's "added by" and "last
    /// edited by" timestamps. D-225 T6 (A4, A10): one wording on both phones, written out rather than asked of the
    /// platform: the patterns the old `dMMMyjmm` template resolved to (read off Foundation, en_US and en_IN: "Oct 17,
    /// 2026 at 6:01 AM", "17 Oct 2026 at 6:01 AM", a narrow no-break space before the marker, "Sep" on both tags),
    /// formatted with fixed month names and markers (`en_US_POSIX`), so no ICU update can move them; Android's
    /// `AppDate.STAMP_US` / `STAMP_IN` are the same strings. The time follows the phone's own 12 or 24-hour setting
    /// (`HH:mm` when it is 24-hour), which a fixed-identifier locale would not.
    static func stamp(
        _ ms: UInt64, locale: LocaleTag = AppLocale.current, timeZone: TimeZone = .current,
        hours24: Bool = phoneUses24Hour
    ) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        // D-225 fix wave: Gregorian, explicitly and after the locale (setting a locale resets the calendar).
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = stampPattern(locale, hours24: hours24)
        return formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    static let stampUS = "MMM d, y 'at' h:mm\u{202F}a"
    static let stampIN = "d MMM y 'at' h:mm\u{202F}a"
    static let stampUS24 = "MMM d, y 'at' HH:mm"
    static let stampIN24 = "d MMM y 'at' HH:mm"

    static func stampPattern(_ locale: LocaleTag, hours24: Bool) -> String {
        switch (locale, hours24) {
        case (.enIn, false): return stampIN
        case (.enIn, true): return stampIN24
        case (_, false): return stampUS
        case (_, true): return stampUS24
        }
    }

    /// Whether the phone shows 24-hour time: its own locale's hour pattern (the setting in Language and Region is
    /// carried by `Locale.autoupdatingCurrent`) has no day-period marker.
    static var phoneUses24Hour: Bool {
        uses24Hour(hourPattern: DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .autoupdatingCurrent) ?? "h a")
    }

    /// The decision, apart from the phone: "h a" is 12-hour, "HH" 24-hour. A quoted literal is not a marker.
    static func uses24Hour(hourPattern: String) -> Bool {
        let unquoted = hourPattern.split(separator: "'", omittingEmptySubsequences: false)
            .enumerated().filter { $0.offset % 2 == 0 }.map(\.element).joined()
        return !unquoted.contains("a") && !unquoted.contains("b") && !unquoted.contains("B")
    }

    private static func format(
        _ ms: UInt64, template: String, locale: LocaleTag, timeZone: TimeZone = .current
    ) -> String {
        let formatter = DateFormatter()
        formatter.timeZone = timeZone
        formatter.locale = Locale(identifier: locale == .enIn ? "en_IN" : "en_US")
        formatter.setLocalizedDateFormatFromTemplate(template)
        return formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}
