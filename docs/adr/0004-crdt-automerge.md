# ADR-0004: CRDT library selection

Status: Superseded by ADR-0018 (straumr shipped without Automerge; the swap
condition this ADR anticipated held, and was not taken — see ADR-0018)

## Context

Two people editing the same expense on different phones with no network must
merge deterministically. The ledger is a set of signed immutable operations
(sets merge trivially); field-level edits need per-field last-writer-wins
registers with a hybrid logical clock. Yjs is optimised for text sequences
and is the wrong shape for a ledger of records.

## Decision

**Automerge for v1.** Mature, good Rust core, well-understood document model.
Loro is faster and more modern but younger; revisit only if Automerge
demonstrably fails a performance budget in SPEC §10.4.

Sync by log index, not state vector: state vectors leak per-device operation
counts; an index leaks strictly less.

Amount conflicts are surfaced to the user, never auto-resolved.

## Consequences

- Deterministic merge is inherited, not hand-rolled; the three-device
  convergence test remains the proof of record either way.
- Library swap later is contained behind `straumr` if its public surface is
  not allowed to leak into other modules.
