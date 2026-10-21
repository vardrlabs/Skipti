# ADR-0029: CDLA-Permissive-2.0 dependencies are allowed

Status: Accepted

## Context

Sign in with Apple (SPEC §4.2/§12.2) gave the relay its first HTTPS client:
`ureq`, fetching and refreshing Apple's JWKS so identity tokens can be
verified against Apple's rotating signing keys without operator babysitting
(D-63). `cargo deny check` — which had been fully green since ADR-0028 —
immediately failed on the new tree: `webpki-roots v0.26.11` is licensed
**CDLA-Permissive-2.0**, absent from the Phase-0 allow list.

The coupling is structural, not incidental: ureq 2.x's `tls` feature is
defined as `["dep:webpki-roots", "dep:rustls", "dep:rustls-pki-types"]`,
so the Mozilla root-store bundle cannot be declined while keeping rustls.
The alternatives were weighed:

1. **Drop the runtime fetch** (constructor-injected keys only) — rejected:
   Apple rotates signing keys; a static key file silently breaks every
   sign-in on rotation day. That is a control that fails quietly, the exact
   shape this project refuses to ship.
2. **Switch to reqwest with OS trust roots** (`rustls-tls-native-roots`) —
   rejected: an order of magnitude more dependency surface for one GET,
   trading a data licence question for dozens of new supply-chain questions.
3. **Admit CDLA-Permissive-2.0** with reasoning recorded — taken.

CDLA-Permissive-2.0 is the Linux Foundation's Community Data License
Agreement (permissive flavour). It governs DATA, not code: `webpki-roots`
embeds Mozilla's CA root store — the standard TLS trust anchor set for the
entire rustls ecosystem. Its terms are permissive in the ordinary sense:
unrestricted use, reproduction, redistribution and modification of the Data;
no reciprocity, no network-clause, nothing that attaches to combined or
derived WORKS.

Applying ADR-0028's test to it:

1. **Nothing attaches to SKIPTI's work.** Unlike MPL-2.0's file-level
   copyleft there is not even a source-disclosure condition on unmodified
   redistribution. An Apache-2.0 project consuming the crate is outside the
   licence's trigger conditions entirely for anything but the Data itself.
2. **The single obligation — keeping the agreement notice with the Data —
   is satisfied by consuming UNMODIFIED crates.io sources**, exactly as
   ADR-0028 reasoned for MPL-2.0; Cargo.lock pins the version.
3. **Modification of the root-store Data in-tree would change the posture**
   (notices must travel with modified Data), so the tripwire below applies
   on any vendoring or patching, as in ADR-0028.

For contrast: the absence from the allow list was an omission, not a
decision — the Phase-0 list predated the relay's first HTTPS dependency,
and no copyleft-style licence is admitted by this entry.

## Decision

CDLA-Permissive-2.0 is added to `deny.toml`'s license allow list, citing
this ADR. NOTICE gains a section for data-licensed components alongside
its existing provenance records.

## Consequences

- `cargo deny check` returns to green with the Sign in with Apple work in
  the tree; gate-full reaches all-passed again.
- **Tripwire:** if `webpki-roots` (or any CDLA-licensed component) is ever
  vendored, forked, or patched within this repository, the agreement text
  and notices must accompany the redistributed Data, NOTICE must be
  updated, and the fact recorded here. The unmodified-crates.io posture
  ends at that commit.
- Future CDLA-licensed dependencies do not need a new ADR — but any
  dependency addition still needs CONTRIBUTING standing rule 4's written
  justification.
- The allow-list edit is recorded here rather than made quietly so the
  next licence audit finds a decision, not just a diff.
