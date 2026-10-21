import SwiftUI

/// §11.4's signature: the amount, "you are owed", a hairline rule, then the
/// per-person decomposition INLINE: one glance answers "how much" and "from
/// whom". Content stays opaque (ADR-0023 §7: Liquid Glass is system chrome
/// only), so §11.7's per-pair contrast verification holds.
///
/// Every money string comes from the FFI: ffiMoneyFormat for the headline,
/// ffiMoneyFormatDecomposition for the inline entries, ffiMoneySpoken for
/// every accessibility label. No NumberFormatter exists in this app.
struct BalanceLineView: View {
    /// Viewer-framed (core): entries are positive when money comes to you.
    let line: BalanceLineOut
    let names: [String: String]
    let viewerID: String
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var others: [ShareOut] {
        line.entries
    }

    private func colour(for minor: Int64) -> Color {
        minor > 0 ? Tokens.owing : (minor < 0 ? Tokens.owed : Tokens.neutral)
    }

    /// All money strings route through core. A failure is a programming
    /// bug (bad currency), never a user condition: the screen shows
    /// `Placeholder.none`, and the spoken amount is empty, as on Android
    /// (D-228 decision 43), so VoiceOver never reads the hyphen as a minus.
    private func display(_ minor: Int64) -> String {
        (try? ffiMoneyFormat(
            minorUnits: minor,
            currencyCode: line.currencyCode,
            locale: AppLocale.current
        )) ?? Placeholder.none
    }

    private func decomposed(_ minor: Int64) -> String {
        (try? ffiMoneyFormatDecomposition(
            minorUnits: minor,
            currencyCode: line.currencyCode,
            locale: AppLocale.current
        )) ?? Placeholder.none
    }

    private func spoken(_ minor: Int64) -> String {
        (try? ffiMoneySpoken(
            minorUnits: minor,
            currencyCode: line.currencyCode,
            locale: AppLocale.current
        )) ?? ""
    }

    private func amount(_ net: Int64) -> some View {
        Text(display(net))
            .font(TypeRole.numeric(40, weight: 500, relativeTo: .largeTitle))
            .foregroundStyle(colour(for: net))
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .accessibilityLabel(spoken(net))
    }

    private func caption(_ net: Int64) -> some View {
        Text(net > 0 ? "you are owed" : (net < 0 ? "you owe" : "settled"))
            .font(TypeRole.body())
            .foregroundStyle(Tokens.textDim)
            .accessibilityHidden(true) // spoken label already says it
    }

    var body: some View {
        let net = line.viewerNetMinor
        VStack(alignment: .leading, spacing: 0) {
            // At accessibility sizes the caption drops BELOW the amount:
            // forcing them side-by-side is exactly where §11.7 said this
            // screen breaks ("you / are / owed" in a ragged column).
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 6) {
                        amount(net)
                        caption(net)
                    }
                } else {
                    HStack(alignment: .firstTextBaseline) {
                        amount(net)
                        Spacer(minLength: 12)
                        caption(net)
                    }
                }
            }
            .padding(.bottom, 10)

            Rectangle()
                .fill(Tokens.hairline)
                .frame(maxWidth: .infinity).frame(height: 1)

            FlowLayout(spacing: dynamicTypeSize.isAccessibilitySize ? 14 : 18) {
                ForEach(others, id: \.id) { entry in
                    HStack(spacing: 6) {
                        Text(names[entry.id] ?? entry.id)
                            .microLabelFont()
                            .foregroundStyle(Tokens.textDim)
                        Text(decomposed(entry.minorUnits))
                            .font(TypeRole.numeric())
                            .foregroundStyle(Tokens.text)
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(
                        "\(names[entry.id] ?? entry.id): \(spoken(entry.minorUnits))"
                    )
                }
            }
            .padding(.top, 12)
        }
    }
}

/// Wrapping flow for the inline decomposition (iOS 17 Layout). At 200%
/// Dynamic Type the entries WRAP. §11.7 names this exact screen as where
/// layouts break, so clipping is never an option.
struct FlowLayout: Layout {
    var spacing: CGFloat = 16

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: maxWidth, height: y + rowHeight)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: .unspecified)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
