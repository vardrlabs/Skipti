# ADR-0025: The UniFFI surface shells build against

Status: Accepted

## Context

`core/src/ffi.rs` exposed only `skipti_core_roundtrip` (D-21's plumbing
proof that bytes cross the boundary) and `skipti_core_version`. The echo is
gone now: it was never an API, and under this ADR every exported symbol is
a two-shell compatibility commitment — deleting it before any shell binds
is free, deleting it after would be a breaking change. `skipti_core_version`
stays: a version call is legitimately useful to a shell. Nothing in money, format, hlutr, norn or skrin is reachable from Swift
or Kotlin today, so a shell built now could only render fabricated data and
would be rewritten once the real surface landed. Phase 7 (shells-first per
ADR-0019) needs the real surface now.

Every exported symbol is a compatibility commitment across two shells
(iOS first, Android to follow to TestFlight/Play internal testing together).
The surface is therefore deliberately small: a few coarse calls over the
modules that already exist and are pinned by their own tests, not a mirror
of every public Rust item.

## Decision

**1. One shared translation layer; two declarative shims over it.** All
validation and mapping lives in plain Rust functions in `core/src/ffi.rs`
(`money_make`, `money_format`, `split_compute`, `balances_compute`, …). The
UniFFI exports are thin `#[uniffi::export]` annotations on those functions;
the wasm-bindgen exports call exactly the same functions from the `cfg`
gated `wasm` module. Behaviour cannot drift between the surfaces without
editing shared code, which core tests and ffi golden-vector tests pin.
Encodings may differ per platform idiom (Swift/Kotlin get typed enums via
UniFFI; JS gets the same calls with primitive arguments), but semantics
cannot.

**2. Money crosses as `i64` minor units plus an ISO code string.** Core
maths stays `i128`; adapters reject values outside `i64` with a typed
overflow error *before* touching core arithmetic. Ledger amounts in minor
units fit `i64` by ~14 orders of magnitude; the narrowing is documented at
the boundary rather than smuggled through generics. Per ADR-0002 nothing on
the surface returns or accepts a float — there is no float type in the
entire generated API by construction, not merely by audit.

**3. Participant ids cross as lowercase hex strings** (32 chars). Exact
bytes are preserved through checked hex decoding (`CoreError::InvalidInput`
otherwise); hex keeps Swift dictionaries and Kotlin maps readable in
debuggers and logs without inventing a second identity encoding.

**4. Splits expose the complete `hlutr::SplitSpec` enum now**, including
Itemised, because adding a variant to an exported enum later is itself a
breaking change. Balance/simplification is one pure call taking the shell's
expense/settlement lists and returning, per currency, the net positions
sorted by magnitude descending (ties by participant id ascending — the
exact shape §11.4's balance line renders) plus the ≤ n−1 simplification
transfers. FX conversion is deliberately absent for now: §11.4's balance
line is single-currency, and `fx::Conversion` deserves its own reviewed
surface rather than riding along.

**5. skrin lifecycle is an exported object with a foreign Keystore.**
`SkiptiVault.open(path, keystore)` wraps `skrin::Skrin`; the iOS Keychain /
Android Keystore implementations are Phase 7 shell work — this session
defines the contract they must supply: a `SkiptiKeystore` callback interface
with `provision(key: 32B) / load() -> Option<32B> / destroy()`, no export
path beyond memory (A-17 holds by construction, same as the native trait).
An explicit `close()` exists alongside ARC-drop closing; use after close is
the typed `StoreError::Closed`, never a crash. Exposed store operations are
the one-ledger minimum: put/all records (header fields flattened into a
wire record), kv set/get for sync cursors, schema version. Device keys,
group-key state and HLC persistence remain reachable natively but are not
exported yet — they belong to the sync screens, and exporting them before
those screens exist would freeze shapes prematurely. skrin stays out of the
wasm surface entirely (native-only cfg; the web store is SPEC §10.2's
separate IndexedDB build).

**6. Panic policy.** Every fallible entry point returns a typed error and
validates inputs (hex decode, range checks, membership) before any
arithmetic, because D-02's typed-error discipline matters more across FFI
than in-process — a panic crossing into Swift/Kotlin is at best an opaque
exception and at worst an abort. UniFFI additionally converts residual
panics in exported functions into host exceptions rather than aborts, but
the suite treats that as a backstop, not a design: tests drive every entry
point with hostile inputs and assert typed errors.

## Consequences

- Shells consume `ios/Skipti/Bindings` (Swift) and
  `android/app/src/main/kotlin` (Kotlin) output of `make bindings`. They
  must add the generated sources to their app targets and link the built
  static/dynamic library of `skipti_core` for each platform; the paths
  BUILD-LOG §4.4 flagged are now materialised and verified to generate.
- Adding capability later follows one rule: extend the shared translation
  layer + its tests first, then re-export. Never implement logic in a shim.
- Deferred, recorded here so deferral is visible: FX conversion surface,
  device-keys/group-state/HLC exports, and any streaming/pagination of
  `all_records` (fine at friends-scale ledger sizes; revisit if a vault
  grows past interactive latency).
