# ADR-0001: Rust core with native shells

Status: Accepted

## Context

Money arithmetic, split remainders, CRDT merge, and crypto must produce
byte-identical results on every platform. Three independent implementations
(iOS, Android, web) means three subtly different rounding behaviours and a
group whose balances disagree by device. Cross-platform UI frameworks
(Flutter, React Native) would ship faster but produce an app that feels like
neither platform; a finance app lives or dies on trust, and trust is partly
texture.

## Decision

One shared core in Rust (`skipti-core`), compiled three ways:

- iOS via UniFFI → Swift, shell in SwiftUI.
- Android via UniFFI → Kotlin, shell in Jetpack Compose.
- Web via wasm-bindgen, shell in React + TypeScript.

No cross-platform UI framework. Native shells own all rendering and platform
integration; the core owns everything where a bug costs money.

## Consequences

- One implementation of splits, balances, crypto, and merge; the class of
  "devices disagree" bugs is made impossible rather than tested for.
- Higher total shell cost than a single framework; accepted deliberately.
- FFI boundary discipline required: serialisable data structures only across
  the boundary, no callbacks carrying financial plaintext into UI logs.
