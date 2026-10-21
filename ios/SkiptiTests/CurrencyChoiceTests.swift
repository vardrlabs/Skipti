import XCTest
@testable import Skipti

/// D-165 (SPEC §5.2): the default currency and the full list. Android's twin
/// is CurrencyChoiceTest.
final class CurrencyChoiceTests: XCTestCase {
    private let known: Set<String> = ["INR", "USD", "EUR", "CHF"]

    func testAStoredChoiceWinsThenTheRegionThenUSD() {
        XCTAssertEqual(DefaultCurrency.resolve(stored: "EUR", region: "INR", known: known), "EUR")
        XCTAssertEqual(DefaultCurrency.resolve(stored: nil, region: "CHF", known: known), "CHF")
        XCTAssertEqual(DefaultCurrency.resolve(stored: nil, region: nil, known: known), "USD")
        XCTAssertEqual(DefaultCurrency.resolve(stored: "", region: "INR", known: known), "INR")
        // Codes core does not know never stick, stored or regional.
        XCTAssertEqual(DefaultCurrency.resolve(stored: "XCG", region: "INR", known: known), "INR")
        XCTAssertEqual(DefaultCurrency.resolve(stored: "usd", region: "ZZZ", known: known), "USD")
    }

    /// U26: nothing stored (or a code core does not know): the resolved code is suggested, nothing ticked; a stored
    /// choice: no suggestion, the choice ticked.
    func testTheCurrencyIsSuggestedUntilOneIsChosen() throws {
        XCTAssertNil(DefaultCurrency.suggestion(stored: "EUR", region: "INR", known: known), "chosen: no suggestion")
        XCTAssertEqual(DefaultCurrency.suggestion(stored: nil, region: "CHF", known: known), "CHF")
        XCTAssertEqual(DefaultCurrency.suggestion(stored: nil, region: nil, known: known), "USD")
        XCTAssertEqual(DefaultCurrency.suggestion(stored: "XCG", region: "INR", known: known), "INR", "an unknown code is no choice")
        XCTAssertEqual(DefaultCurrency.suggestion(stored: "", region: "ZZZ", known: known), "USD")
        XCTAssertEqual(CurrencyCopy.suggested, "Suggested")
        let list = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/CurrencyChoice.swift"), encoding: .utf8)
        XCTAssertTrue(list.contains("if suggested == nil && code == selection {"), "nothing ticked while suggesting")
        XCTAssertTrue(list.contains("if query.isEmpty, let suggested {"), "the suggestion leads, above Common")
        let you = try String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Skipti/App/AccountHubScreen.swift"), encoding: .utf8)
        XCTAssertTrue(you.contains("suggested: DefaultCurrency.suggestion(stored: currencyRaw))"), "You's list suggests")
    }

    func testTheFullListIsCoresSortedAndHoldsThePromotedNine() {
        let all = DefaultCurrency.all
        XCTAssertEqual(all.count, 164, "counted independently from core's tables")
        XCTAssertEqual(all, all.sorted())
        XCTAssertEqual(Set(all).count, all.count)
        for code in AddExpenseScreen.promotedCurrencies {
            XCTAssertTrue(all.contains(code), code)
        }
    }

    func testTheShortListIsThePromotedNineThenExtrasOnce() {
        let nine = ["INR", "USD", "EUR", "GBP", "JPY", "AED", "SGD", "AUD", "CAD"]
        XCTAssertEqual(DefaultCurrency.choices(including: []), nine)
        XCTAssertEqual(DefaultCurrency.choices(including: ["USD", "INR"]), nine, "promoted codes are not repeated")
        XCTAssertEqual(DefaultCurrency.choices(including: ["CHF", "THB", "CHF"]), nine + ["CHF", "THB"])
    }

    func testSearchMatchesCodeOrNameIgnoringCase() {
        XCTAssertTrue(CurrencyCopy.matches("CHF", name: "Swiss Franc", query: ""))
        XCTAssertTrue(CurrencyCopy.matches("CHF", name: "Swiss Franc", query: "chf"))
        XCTAssertTrue(CurrencyCopy.matches("CHF", name: "Swiss Franc", query: " swiss "))
        XCTAssertTrue(CurrencyCopy.matches("ISK", name: "Icelandic Króna", query: "krona"), "accents ignored")
        XCTAssertFalse(CurrencyCopy.matches("CHF", name: "Swiss Franc", query: "euro"))
        XCTAssertFalse(CurrencyCopy.matches("PRB", name: nil, query: "rouble"), "no name: code only")
    }
}
