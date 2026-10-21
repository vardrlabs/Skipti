import SwiftUI

/// D-165 (SPEC §5.2): the default currency, and every currency core knows.
///
/// SPEC §5.2 asks for "Full ISO 4217, with INR, USD, EUR, GBP, JPY, AED,
/// SGD, AUD, CAD promoted in the picker"; until now both shells offered the
/// nine and nothing else. The full list is core's own (`ffiCurrencyCodes`),
/// so a person can pick exactly what core can store, on both shells.
///
/// The default currency only pre-selects the currency of a new group and of a
/// fresh expense. D-244 (ADR-0043): once chosen, it follows the account to its
/// other phones (`SettingsSync`; a code this build's core does not know is not
/// applied); the region's default is never synced. Unset, it
/// is the phone region's currency when core knows it, else USD. Money
/// formatting still follows the record's currency and the locale (D-50).
/// Android's twin is CurrencyChoice.kt.
enum DefaultCurrency {
    static let storageKey = "money.default-currency"
    static let fallback = "USD"

    /// Core's list, sorted. It cannot change while the app runs.
    static let all: [String] = ffiCurrencyCodes()
    private static let known = Set(all)

    /// A stored choice core knows, else the region's currency core knows,
    /// else USD. A stored code this build does not know never sticks.
    static func resolve(stored: String?, region: String?, known: Set<String>) -> String {
        if let stored, known.contains(stored) { return stored }
        if let region, known.contains(region) { return region }
        return fallback
    }

    static func current(stored: String?) -> String {
        resolve(stored: stored, region: Locale.current.currency?.identifier, known: known)
    }

    static var current: String { current(stored: UserDefaults.standard.string(forKey: storageKey)) }

    /// U26 (the founder, 2026-10-20): until a currency core knows is stored, nothing has been chosen, so the list
    /// ticks nothing and offers the code `current` resolves to (the region's, else USD) first, under "Suggested". Nil
    /// once a choice is stored. The same "chosen" as D-221's currency step.
    static func suggestion(stored: String?, region: String?, known: Set<String>) -> String? {
        if let stored, known.contains(stored) { return nil }
        return resolve(stored: nil, region: region, known: known)
    }

    static func suggestion(stored: String?) -> String? {
        suggestion(stored: stored, region: Locale.current.currency?.identifier, known: known)
    }

    /// The short list a picker shows: the promoted nine in SPEC's order, then
    /// any of `extra` not among them (the default, the current choice), once.
    static func choices(including extra: [String]) -> [String] {
        var list = AddExpenseScreen.promotedCurrencies
        for code in extra where !list.contains(code) { list.append(code) }
        return list
    }
}

enum CurrencyCopy {
    static let section = "Currency"
    static let label = "Default currency"
    static let detail = "New groups and new expenses start in this currency. You can pick another each time."
    static let allCurrencies = "All currencies"
    static let common = "Common"
    /// U26: the label over the one suggested code while nothing is chosen.
    static let suggested = "Suggested"
    static let everything = "All currencies"
    static let search = "Code or name"
    static let noMatch = "No currency matches."

    /// The phone's name for a code, when it has one ("Indian Rupee").
    static func name(_ code: String) -> String? {
        Locale.current.localizedString(forCurrencyCode: code)
    }

    /// Search by code or name, ignoring case and accents.
    static func matches(_ code: String, name: String?, query: String) -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces)
        if q.isEmpty { return true }
        let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        return code.range(of: q, options: options) != nil
            || (name?.range(of: q, options: options) != nil)
    }
}

/// Every currency core knows, searchable; tapping one picks it and goes back.
struct CurrencyListScreen: View {
    @Binding var selection: String
    /// U26: nothing chosen yet: no row is ticked, and this code leads the list under "Suggested"
    /// (`DefaultCurrency.suggestion`). Nil: `selection` is a real choice and is ticked.
    var suggested: String? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var filtered: [String] {
        DefaultCurrency.all.filter { CurrencyCopy.matches($0, name: CurrencyCopy.name($0), query: query) }
    }

    /// D-220 fix wave (I4): where the first card starts, as the grouped list did (its first card 44 pt under the bar:
    /// Android's `BarDecisions.formTop(headerless = true)`), less the 9.5 pt the scroll content already starts below it.
    static let topPadding: CGFloat = 34.5

    var body: some View {
        // D-220 fix wave (I4): the theme's cards, as Android's (notched in Norse, square and framed in Vardr Labs), not the
        // system's rounded inset-grouped cells: the search in its own card, then a section label over a card of rows.
        let shown = filtered // filtered once per body (it names every code)
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                // A plain field, not `.searchable`: going back from the system
                // search left its bar and keyboard floating over Settings, and
                // closing the search first kept the screen from going back
                // (both seen on iOS 27).
                HStack(spacing: 8) {
                    // D-220 (L-D217-6): Lucide's search, at Android's 20 (NOTICE).
                    LucideIcon(glyph: .search, size: 20)
                        .foregroundStyle(Tokens.textDim)
                        .accessibilityHidden(true)
                    TextField(CurrencyCopy.search, text: $query)
                        .font(TypeRole.body())
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityLabel(Text(verbatim: CurrencyCopy.search))
                }
                .glassCard()
                if query.isEmpty, let suggested {
                    Text(verbatim: CurrencyCopy.suggested).sectionLabel().foregroundStyle(Tokens.textDim).padding(.top, 12).padding(.leading, 4)
                    card([suggested])
                }
                if query.isEmpty {
                    // A section label 12 above its card and 4 in from its edge (Android's `SectionLabel` padding).
                    Text(verbatim: CurrencyCopy.common).sectionLabel().foregroundStyle(Tokens.textDim).padding(.top, 12).padding(.leading, 4)
                    card(AddExpenseScreen.promotedCurrencies)
                }
                Text(verbatim: CurrencyCopy.everything).sectionLabel().foregroundStyle(Tokens.textDim).padding(.top, 12).padding(.leading, 4)
                if shown.isEmpty {
                    Text(verbatim: CurrencyCopy.noMatch)
                        .font(TypeRole.body())
                        .foregroundStyle(Tokens.textDim)
                        .glassCard()
                } else {
                    card(shown)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, Self.topPadding)
            .padding(.bottom, 16)
        }
        .auroraBackground() // D-176
        .navigationTitle(Text(verbatim: CurrencyCopy.allCurrencies))
        .navigationBarTitleDisplayMode(.inline)
    }

    /// A card of currency rows with a hairline rule between rows (Android's `CurrencyCard`).
    private func card(_ codes: [String]) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(codes.enumerated()), id: \.element) { i, code in
                if i > 0 { Rectangle().fill(Tokens.hairline).frame(height: 1) }
                row(code)
            }
        }
        .glassCard()
    }

    private func row(_ code: String) -> some View {
        Button {
            selection = code
            dismiss()
        } label: {
            HStack(spacing: 8) {
                Text(verbatim: code)
                    .font(TypeRole.numeric(17))
                    .foregroundStyle(Tokens.text)
                if let name = CurrencyCopy.name(code) {
                    Text(verbatim: name)
                        .font(TypeRole.body(14))
                        .foregroundStyle(Tokens.textDim)
                }
                Spacer(minLength: 0)
                if suggested == nil && code == selection {
                    // D-220: Lucide's check, at Android's 20.
                    LucideIcon(glyph: .check, size: 20)
                        .foregroundStyle(Tokens.accent)
                        .accessibilityLabel("Selected")
                }
            }
            .padding(.vertical, 11)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
