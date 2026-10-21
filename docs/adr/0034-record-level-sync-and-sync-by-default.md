# ADR-0034: Record-level sync, and sync on by default

Date: 2026-08-29
Status: Accepted (founder-decided 2026-08-29)

## Context

The founder described the behaviour he expects from two phones and a laptop
on one account: each device stamps an expense with a time identifier fine
enough to be unique, offline devices keep working and slot their records into
the right position on reconnect, and every device converges on the same
ordered ledger.

That description is a Hybrid Logical Clock, and **SKIPTI already has one.**
`straumr::Hlc` is `{ millis, counter, device_id }`; `skrin` reads records back
`ORDER BY hlc_millis, hlc_counter, hlc_device_id`; and two gate legs already
prove the property —
`gate_three_devices_converge_to_byte_identical_balances` and
`gate_edits_and_reversed_order_still_converge` apply the same records in
different orders and get byte-identical balances out.

The shells do not use any of it.

## The defect

`LedgerSync` pushes a **whole sealed snapshot of local state** as one log
entry, and restore reads the newest snapshot. D-67 recorded this honestly as
"single-subject backup/restore — the newest snapshot wins", and the code
header says so. But the consequence was never written down in the terms that
matter:

1. Phone adds expense X, pushes a snapshot containing X at index 5.
2. Laptop, which has not pulled, adds expense Y. Its snapshot contains Y and
   not X.
3. Laptop pushes, gets **409**, and — this is the part that matters — pulls,
   **advances its cursor past index 5, and pushes its own snapshot anyway**
   at index 6. It never merges X in.
4. Restore reads the newest snapshot. **X is gone.**

Nothing crashes. Nothing is logged. X still sits at index 5 in the relay's
log, so the bytes are not destroyed — but the effective state is whichever
device pushed last, and that snapshot never contained X.

**This is why sync-on-by-default could not simply be switched on.** Off by
default, this bites the few who enable it deliberately. On by default, it
bites everyone with two devices, every day, silently. The ordering of this
ADR's two halves is the whole point.

## Decision

### 1. The shells push RECORDS, not snapshots

The unit of sync becomes the `straumr` record — carrying its HLC, its author
device, and its sealed payload — not a photograph of local state. Ordering
and convergence are then core's job, which core already does and already
proves.

A device offline for three days stamps each record with its HLC **at
creation**. On reconnect those records slot into their correct position by
their own timestamps rather than jumping to the end because they arrived
late. Snapshot sync cannot express that; record sync gets it for free.

**No artificial latency, and no reliance on clock precision.** The founder
proposed manufacturing a small delay so simultaneous writes get distinct
identifiers. That is unnecessary and should not be built: `counter` and
`device_id` already give a total order for records created in the same
millisecond, deterministically, on any clock.

### 2. Conflicts resolve silently, and nothing is destroyed

When the same object is edited on two devices, the HLC picks a winner
deterministically. That resolution is **silent** — no prompt, no merge UI.

The losing version is **retained**, not discarded. Silence is a presentation
choice, not a licence to lose data, and the two must not be confused: a user
who never sees a conflict screen should still be able to recover what the
other device wrote.

### 3. Sync is on by default — for accounts

Once an account exists, sync is on unless the user turns it off.

**This does not touch GUARANTEES #5.** "Fully functional offline and with no
account" is unchanged: no account is created, implied, or nudged for. Default-on
means *"once you have chosen to have an account"*, never *"you should get
one"*. An offline user's experience is identical to today's.

> **AMENDED 2026-09-07 (D-125), and the correction is the point.** The
> paragraph above was wrong, and wrong in a specific way: it quoted only
> the first clause of GUARANTEES #5 and declared no conflict. The clause it
> did not quote read **"Account is opt-in; sync is opt-in; neither is
> required"** — and "on by default once an account exists" is opt-OUT, not
> opt-in. Two governing documents disagreed, and the ADR asserted they did
> not.
>
> Found while building D-125, before the repo went public and therefore
> before SPEC's "fork-worthy event" bar applied. The founder's call
> (2026-09-07): the BEHAVIOUR here stands, and the guarantee's wording was
> amended to match it — an account is opt-in and never nudged for, sync
> requires an account, is on once you have one, and can be turned off at
> any time in one tap.
>
> The lesson worth keeping: an ADR that claims a guarantee is untouched
> must quote the guarantee IN FULL. Half a quotation is how a contradiction
> ships.

### 4. Ordering, which is the operative part

**Record-level sync ships BEFORE default-on.** Same destination, safe
sequence. Enabling default-on against the snapshot engine would make the
data-loss path above the normal case rather than the rare one.

## Consequences

- The per-device keys D-42 deferred and D-67 carried are now on the critical
  path. Record sync needs them; snapshot sync did not.
- The relay needs no change. It already stores opaque indexed blobs and
  parses nothing (§6.4); records are simply smaller, more numerous blobs.
- The cursor semantics (D-18: exclusive, 409-is-normal) survive unchanged —
  they were always about the log, not about what the entries mean.
- Sync volume rises: many small records instead of one large snapshot. That
  is the correct trade and it also makes the 256-byte padding bucket (§6.4)
  do more useful work.
- D-67's honest-scope note in both shells' `LedgerSync` headers becomes
  obsolete on completion and must be removed, not left to mislead.

## What this ADR does NOT cover

Recorded so they are not folded in by accident — each is its own decision:

- **Google Sign-In** (ADR-0007 lists it; nothing implements it). Founder has
  confirmed it should be built: Android users largely do not hold Apple IDs.
- **A local profile name** for the no-account path, so a user has something
  to open and later attach an account to. When an account is added, its name
  seeds the profile and stays editable.
- **Selective offline→cloud migration** — an offline user who signs in is
  asked what to sync (transactions, invoices, profile: all, some, or none)
  rather than having it decided for them.

## Tripwires

- Sync becomes default-on before record-level sync ships. That inverts this
  ADR's only ordering requirement and reintroduces the silent-loss path.
- Any conflict path DISCARDS the losing version. Silent resolution and data
  loss are different things and this ADR permits only the first.
- An account is created, implied, or nudged for as a consequence of
  default-on. GUARANTEES #5 is not scoped by this ADR.
- Artificial delay is introduced to disambiguate timestamps. The HLC's
  counter exists for exactly that and does it without lying about time.
