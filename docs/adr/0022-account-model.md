# ADR-0022: Accounts own their sync surface — subject-scoped buckets and membership-checked groups

Status: Accepted

## Context

SPEC §4.1 says an account is optional; that holds for local-only use, and
the moment a device syncs it has a *subject* — a verified identity behind
its session. The server never enforced what that implies: BUILD-LOG §4.4
self-reported that `push_log` takes `Path(account)` and never consults
`AuthedContext`, so since D-33 any authenticated subject could write any
bucket or read/write any group's envelopes. The bearer gate authenticates;
it has not authorized. ADR-0021 then made persistent multi-user hosting a
goal, which turns this from sloppiness into a blocker: multi-user state
without ownership rules is worse than none.

Design constraint, absolute: SPEC §6.4 — no function inspects record
contents. Whatever authorizes must be built from metadata the server
already legitimately sees: session subjects, device ids, envelope recipient
ids, group ids. All of these are already stored in plaintext today.

## Decision

**1. Every syncing principal gets an opaque `subject_id`.** At first
authentication, the server derives a stable 16-byte random `subject_id` for
the verified identifier and keeps the mapping internally. Sync namespaces
use `subject_id`, never the raw identifier (which stays enumeration-
sensitive per §4.3).

**2. Log buckets are owned by exactly one subject, by construction.** The
bucket namespace *is* the subject: `/v1/logs/{account}` is valid only when
`account` equals the caller's `subject_id`; anything else is one uniform
rejection. Ownership is therefore not claimed, transferred, or raced — it
is the namespace. The old free-form bucket names are deprecated at
implementation time; existing test fixtures migrate with it.

**3. Group membership is a server-side registry over already-visible
identifiers.** Two metadata tables: `devices` (device_id → subject_id) and
per-group membership derived from the envelope set itself. Rules:

- **First push creates.** The first authenticated PUT of envelopes for an
  unknown group registers the pushing device as its first member; the
  payload must include that device as a recipient.
- **Members add members.** Any current member may add devices to the set
  (this is the invite path, matching the QR/group-key flow).
- **Rotation is authoritative.** A push carrying a full replacement envelope
  set at epoch n+1 (the D-37/D-38 shape) replaces membership wholesale —
  removed devices vanish from the set, which is exactly how revocation ends
  access.
- **Reads are per-recipient filtered.** GET returns only rows whose
  `recipient_device_id` belongs to the caller's registered devices —
  strictly stronger than today's dump-everything, and it means a hostile
  member learns nothing about other members' envelope bytes anyway, since
  they are wrapped per-recipient.

**4. Escrow already complies.** D-36 keyed escrow blobs by bearer subject;
no change needed beyond reusing `subject_id`.

This is a design decision only. Implementation lands together with D-19's
persistent storage — the registry needs durable tables, and ADR-0021 makes
that pair the hard precondition of anyone connecting to the hosted
instance. Both are blocking items in BUILD-LOG §4.

## Consequences

- The §4.4 authz gap closes by construction: cross-subject writes become
  unrepresentable in the namespace, and cross-group reads become empty
  results rather than leaks.
- Per-recipient envelope filtering improves on the current design even for
  the legitimate owner.
- The free-form bucket era ends: deployment of this model is a breaking
  API change, acceptable now precisely because no real users exist yet
  (ADR-0021 makes that window explicit — it closes when the first friend
  connects).
- Not settled here, recorded as open follow-ups: account deletion and what
  happens to owned namespaces (SPEC §4.4's 7-day grace flow), subject_id
  behaviour when an identifier changes, and per-subject rate limiting.
