# ADR-0028: MPL-2.0 dependencies are allowed

Status: Accepted

## Context

`cargo deny check` has never once run in this project's history — the tool
was not installed on any machine that ran the gate until the 2026-08-25
audit installed it. Its first run failed (exit 6) on a clean tree: thirteen
crates are rejected because `deny.toml`'s allow list, written at Phase 0,
never included MPL-2.0.

The rejected crates are not incidental. They are:

- **The entire UniFFI toolchain** (`uniffi`, `uniffi-core`, `uniffi-bindgen`,
  `uniffi-macros`, `uniffi-internal-macros`, `uniffi-meta`,
  `uniffi-pipeline`, `uniffi-udl`) — the mechanism behind ADR-0001's binding
  strategy and ADR-0025's shell surface. Removing it is removing how iOS and
  Android call the core.
- **The webauthn-rs family** (`webauthn-rs`, `webauthn-rs-core`,
  `webauthn-rs-proto`, `webauthn-attestation-ca`, and
  `webauthn-authenticator-rs` as the D-32 dev-dependency) — SPEC §4.2's
  primary authentication path and GATE 4's passkey proofs.
- **`base64urlsafedata`** — a webauthn-rs dependency, pulled in with it.

Removing or replacing them was never an option: they ARE decisions already
made and proven in this tree. So the question is only whether MPL-2.0 is
compatible with an Apache-2.0 project, and the answer is yes:

1. **MPL-2.0 is file-level weak copyleft.** Its copyleft attaches to
   individual source files of the licensed work, not to the larger work.
   Section 3.2 permits linking against an executable regardless of the
   executable's own licence; section 3.3 permits combining with code under
   any licence ("Greater Source Rights" notwithstanding for static
   inclusion of unmodified files).
2. **An Apache-2.0 project depending on UNMODIFIED MPL-2.0 crates carries
   exactly one obligation:** make the MPL-2.0 sources available. Public
   crates.io crates satisfy that by construction — the source is what we
   downloaded, unchanged, and Cargo.lock pins which version. No licence of
   ours is affected, nothing of ours becomes copyleft, and Apache-2.0 ↔
   MPL-2.0 is a compatible pairing recognised by both the OSI ecosystems
   and Google/Norton-style compatibility matrices.
3. **The obligation changes the moment anyone forks or patches one of these
   crates in-tree.** MPL-2.0 §3.1 then requires the modified files to be
   made available under MPL-2.0. Vendoring a patched `uniffi` into this
   repository without noticing that would be a compliance failure wearing a
   convenience — so this ADR is written precisely so it cannot happen
   unnoticed.

For contrast: GPL/AGPL were correctly absent from the allow list (their
copyleft attaches to the combined work); MPL-2.0's absence was an omission,
not a decision — the Phase 0 list simply predated the crates that need it.

## Decision

MPL-2.0 is added to `deny.toml`'s license allow list, citing this ADR. The
NOTICE file records the MPL-2.0 components alongside its existing BIP-39
provenance section.

## Consequences

- `cargo deny check` passes on the tree as it stands; gate-full can reach a
  genuine all-passed state for the first time.
- **Tripwire:** if any crate currently consumed from crates.io under
  MPL-2.0 is ever vendored, forked, or patched within this repository, the
  modified files must be published under MPL-2.0 (§3.1) and this fact
  recorded in NOTICE. The unmodified-crates.io posture this ADR relies on
  ends at that commit, not at some later distribution event.
- Future MPL-2.0 dependencies do not need a new ADR — but any dependency
  addition still needs CONTRIBUTING standing rule 4's written justification.
- The allow-list edit is recorded here rather than made quietly so that the
  next licence audit finds a decision, not just a diff.
