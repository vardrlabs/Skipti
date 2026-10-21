pub mod ffi;
pub mod flutningr;
pub mod format;
pub mod fx;
pub mod hlutr;
pub mod lykill;
pub mod merki;
pub mod money;
pub mod norn;
pub mod overview;
pub mod qr;
pub mod straumr;

// SQLCipher cannot target wasm32; the web shell brings its own store per
// SPEC §10.2 (IndexedDB, records encrypted individually).
#[cfg(not(target_arch = "wasm32"))]
pub mod skrin;

uniffi::setup_scaffolding!();

#[cfg(target_arch = "wasm32")]
pub mod wasm {
    //! Web shim over the SAME translation layer the UniFFI surface uses
    //! (ADR-0025 §1): no logic here, ever. skrin has no wasm surface:
    //! SQLCipher cannot target wasm32 and SPEC §10.2 gives the web its own
    //! IndexedDB store. Remaining exports (typed splits, balances) land with
    //! the web shell as more wrappers over the same `ffi` functions.
    use wasm_bindgen::prelude::*;

    #[wasm_bindgen]
    pub fn skipti_core_version() -> String {
        crate::ffi::skipti_core_version()
    }

    /// Formatting crosses from core/src/format.rs on every platform. D-09's
    /// shape must never be reimplemented in a shell language.
    #[wasm_bindgen]
    pub fn money_format(
        minor_units: i64,
        currency_code: &str,
        locale_tag: &str,
    ) -> Result<String, String> {
        let locale = crate::ffi::locale_from_tag(locale_tag).map_err(|e| e.to_string())?;
        crate::ffi::money_format(minor_units, currency_code, locale).map_err(|e| e.to_string())
    }
}
