# ADR-0003: Local-first with pluggable sync backends

Status: Accepted

## Context

"No subscription" collides with "servers cost money." Pretending otherwise
is how free apps quietly become ad apps. The database of record must be on
the device; sync propagates changes; the app must be fully correct with sync
switched off forever.

## Decision

Sync backends behind one trait:

| Backend | Who pays | Notes |
|---|---|---|
| None | Nobody | Default for a single device |
| Own server | User (`docker compose up`) | Easiest to build; default in README |
| Own cloud drive | Nobody extra | iCloud / Drive / Dropbox document APIs |
| Hosted instance | Project, donation-funded | Only if it ever launches; publish costs |
| LAN / QR | Nobody | Same-room group sync |

Build order (SPEC §17.2): own-server first for correctness, then cloud-drive;
document cloud-drive as the default recommendation in the README once it
exists.

## Consequences

- Zero per-user server cost is achievable forever, which is what keeps the
  no-subscription guarantee honest.
- Every backend sees only ciphertext blobs; no backend-specific plaintext
  paths may be added.
- The sync indicator has three honest states (SYNCED/PENDING/OFFLINE) and no
  blocking modal exists for network conditions.
