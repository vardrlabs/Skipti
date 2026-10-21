import Foundation

/// §5.2's reference-rate boundary. The operator publishes a DAILY SIGNED
/// STATIC FILE; this app caches it (24 h window), works OFFLINE against
/// the last known set, and shows a staleness indicator when the frozen
/// rate is old. There is NO per-transaction API call: that would leak
/// spending patterns to an FX provider.
///
/// The FETCH itself is stubbed this session (D-52): `fetchLatest` is the
/// boundary a real fetcher implements without touching callers. The
/// offline path (cache in the vault, verify, staleness) is complete.
enum FxRates {
    /// The operator's pinned public key. An unverified rate file is never
    /// returned, so a compromised fetch cannot re-price a ledger.
    /// DEVELOPMENT KEY: replaced by the production operator key at
    /// deployment (gap recorded in BUILD-LOG §4.4).
    static let pinnedPublicKey =
        "4cf7a980576685413a5e001342289d8eb4bc2f4e1eeb465f9c2e40a073ffbe53"

    struct CachedSet {
        let payload: String
        let signature: String
    }

    /// The fetch boundary. Stubbed: a real implementation fetches the
    /// operator's daily static file (payload + signature) and returns it;
    /// verification and staleness happen in core, cache happens in the
    /// vault, and callers never change.
    static func fetchLatest() -> CachedSet? {
        nil
    }

    static func verifyAndParse(
        payload: String, signature: String, nowMs: UInt64
    ) throws -> FxRateSetWire {
        try ffiFxRateSetParse(
            payloadUtf8: payload,
            signatureHex: signature,
            publicKeyHex: pinnedPublicKey,
            nowMs: nowMs
        )
    }

    static func rate(_ set: FxRateSetWire, from: String, to: String) -> FxRateEntryWire? {
        set.rates.first { $0.from == from && $0.to == to }
    }

    static func nowMs() -> UInt64 {
        UInt64(Date().timeIntervalSince1970 * 1000)
    }
}
