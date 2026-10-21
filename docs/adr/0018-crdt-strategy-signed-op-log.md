# ADR-0018: CRDT strategy — signed-op log with field-LWW registers supersedes Automerge

Status: Accepted (supersedes ADR-0004)

## Context

ADR-0004 selected Automerge before Phase 3 existed, and its reasoning was
sound at the time. Stated at full strength, the case FOR Automerge is:

1. **Inherited merge correctness.** Hand-rolled CRDTs are where correctness
   goes to die — concurrent insert/reorder, move operations, and text
   interleaving are research-paper territory. Automerge carries years of
   fuzzing and production hardening; inheriting that beats writing it.
2. **Headroom.** A document model grows with the product. If SKIPTI ever
   needs concurrently edited lists (line items), reordered trees (category
   drag-and-drop), or free-text notes, those need sequence/tree CRDTs that a
   per-field register cannot express.
3. **Change history for free**, aligning with append-mostly records and
   capture-as-proposal (ADR-0017).

Phase 3 then shipped `core/src/straumr/mod.rs` without Automerge, and the
tree has contradicted an Accepted ADR since. This ADR resolves that on the
evidence, not by ratifying what happens to exist.

**What actually shipped** is exactly what SPEC §6.1 prescribes, and §6.1 is
authority over ADRs wherever they conflict:

- The ledger is a set of signed, immutable operations — sets merge trivially.
- Field-level edits use per-field last-writer-wins registers stamped by a
  hybrid logical clock (`LwwRegister<T>`, canonical Kulkarni `Hlc::receive`).
- Amount conflicts are surfaced to the user, never auto-resolved
  (`merge_amount_field` returns `Err(AmountConflict)` regardless of which
  stamp is newer).

`straumr` implements this in ~380 lines: HLC, `RecordHeader` with canonical
bytes binding every header field as AEAD AAD (§6.3), 256-byte padding
buckets, `SyncCursor` sync-by-log-index, LWW registers, conflict surfacing,
and group-key epoch state. The library line in §6.1 ("Automerge or Loro")
named a vehicle for these mechanics; the mechanics themselves are the
requirement, and they predate and outrank the library choice.

**Is GATE 1 sufficient proof?** ADR-0004 called the three-device convergence
test "the proof of record either way," and it passes (600 expenses, 3
shuffled orders → byte-identical balances). Honestly weighed: it is
*necessary* in every world, and *sufficient only for the current op
vocabulary*. It proves order-independence of merge for the shipped record
types at scale through the real types; it cannot prove anything about data
shapes that do not exist yet. That gap is not closed by faith in either
direction — Automerge would demand the same convergence test for new shapes,
so the proof burden transfers only partially. What closes it is the
tripwire duty in the Decision below.

**Containment verified.** ADR-0004's swap condition was "contained behind
straumr if its public surface does not leak into other modules." Checked
against the tree: no module inside core outside `straumr/` imports it;
the UniFFI surface (`core/src/ffi.rs`) exposes two string functions and no
sync types; the wasm surface mirrors those two functions; server production
code treats records as opaque blobs (D-18, D-28); the sole external consumer
is `server/tests/relay.rs`, which exercises the public API as an integration
test should. **The swap point is real and intact today.** One adjacent
finding: the baseline tree already contained `straumr::rotate_group_key`
(over `GroupKeyState`), and D-37 later added `lykill::rotate_group_key`
(over `Vec<GroupEnvelope>`) — one protocol step with two homes. Consolidate
when skrin decides how group-key state persists; do not decide it here.

**What integrating Automerge now would cost,** concretely: §6.3's wire format
is per-record signatures, AEAD with header-bound AAD, and 256-byte size
buckets. An Automerge document grows monotonically with its change history
and has its own chunked storage format — it cannot be sealed per-record into
quantized buckets without abandoning incremental change upload or coupling
to library internals, which is precisely the leakage ADR-0004 forbade.
Automerge's core competency — automatic convergence — is forbidden for the
one field where correctness matters most: amounts must surface conflicts.
We would adopt an audit-surface-heavy dependency tree (§10.5 makes the
dependency budget a security control; §10.4 caps wasm budgets) to replace
~60 lines of auditable merge logic that already pass the proof of record.

## Decision

Supersede ADR-0004. The CRDT strategy for SKIPTI is the one specified by
SPEC §6.1 and implemented in `straumr`: signed immutable operations merged
as a set, per-field LWW registers under a hybrid logical clock, amount
conflicts surfaced to the user. Automerge is not integrated, and the choice
is made on the current evidence — not because the status quo exists.

This decision carries explicit falsification conditions rather than open
faith:

1. **The tripwire.** Any proposed feature whose data shape is not expressible
   as {append-only signed op, per-field LWW register, set union} triggers a
   new ADR before implementation. Known shapes that trip it today:
   concurrently reordered lists (expense line items, category trees) and
   collaborative free-text notes or comment threads on expenses — both need
   sequence/tree CRDTs, which is research territory this project should buy
   in, not improvise.
2. **Detection duty.** Every new operation type enters the convergence
   harness (multi-device, shuffled-order replay, byte-identical state) as a
   condition of shipping; touching `Hlc::receive` requires adversarial
   clock-skew tests. A shape that cannot pass shuffled-order replay is the
   alarm that reopens this decision.
3. **Reversal economics.** If a tripped shape becomes a real requirement,
   the cheapest reversal point is before the skrin store schema freezes that
   shape. After persistence lands, the cost is data migration across every
   installed device. This asymmetry is why the decision is being made now,
   before skrin, in either direction.

Consolidate the duplicate `rotate_group_key` implementations when skrin
settles group-key-state persistence (see Context finding).

## Consequences

- No CRDT dependency tree joins the audit surface: cargo-deny scope, SBOM,
  and wasm bundle stay at their §10.4/§10.5 budgets.
- The record format stays coherent as designed: signatures, epoch-bound
  AAD, and 256-byte buckets all hold without fighting a library's own
  storage model (§6.3 as implemented).
- Merge logic remains ~60 lines of fully auditable code — and note honestly
  that we would have configured Automerge to *not* converge the most
  important field anyway.
- **Given up:** inherited correctness for sequence/tree/document shapes.
  Hand-rolled merge correctness for anything beyond the current vocabulary
  is permanently this project's problem. The tripwire above converts that
  from silent risk to loud review gate; it does not eliminate it.
- **Given up:** Automerge's change-history machinery (undo, inspection).
  The audit story rests instead on append-mostly signed records and the
  Recovery Kit, which cover what the product currently promises.
- GATE 1 remains the proof of record and extends per operation type: the
  convergence harness grows with the vocabulary or the vocabulary does not
  grow.
