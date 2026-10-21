// Exposes the UniFFI C surface to the app's Swift sources. The generated
// skipti_core.swift compiles inline (canImport(skipti_coreFFI) fails in an
// app target without a Clang module), so these declarations are how the
// Swift bindings see the Rust symbols. HEADER_SEARCH_PATHS includes
// Bindings/; the symbols themselves live in SkiptiCore.xcframework.
#import "skipti_coreFFI.h"
