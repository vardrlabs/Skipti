# ADR-0030: Relay vault backup policy — tenancy-internal, no operator keys

Date: 2026-08-26
Status: Accepted

## Context

The hosted relay (ADR-0021) holds `/var/lib/skipti/relay-vault.db`: sealed
ledger blobs, escrow blobs, passkey credentials, session state. THREAT_MODEL's
custody commitment states the consequence plainly — losing the vault loses
every user's Master Key recovery path, the one thing SKIPTI cannot rebuild.
Until today the production vault had **no backup at all**; the deployment doc
said "anything is better than nothing", which is how unaccounted-for copies
get made.

The vault's contents are ciphertext by construction (ADR-0018 sealed blobs;
D-36 escrow blobs are Argon2id-wrapped; D-66 sync blobs are client-keyed
AEAD), plus operational metadata (headers, timestamps, subject ids, credential
public keys). A backup therefore inherits the vault's at-rest safety only if
it lands somewhere the existing threat model already accounts for.

## Options considered

1. **On-host snapshots only.** Dies with the instance (Oracle reclaims or
   corrupts the boot volume → backup gone with it). Necessary, not sufficient.
2. **Copy the file to a third-party object store.** Refused: it hands the
   vault — even ciphertext — to a party the threat model has never modelled,
   along with every metadata column in plaintext. §2.6's "the relay sees
   only what it must" and ADR-0021's custody boundary are written about ONE
   server; a second, weaker, unmodelled copy of the same bytes makes both
   false. This is the founder's explicit red line.
3. **Operator-encrypted uploads (age/GPG against an operator key) to object
   storage.** Refused for now: it mints NEW long-lived key material whose
   generation, storage, rotation, and compromise story the threat model does
   not yet cover — and the key's loss is the users' loss, identical to the
   problem being solved.
4. **SQLite `.backup` on-host + Oracle Block Volume backup policy.** CHOSEN.
   The on-host snapshot is crash-consistent by construction (`.backup` is the
   only correct way to copy a live WAL database). The off-host layer is the
   instance's own volume, backed up by Oracle under the tenancy's control:
   encrypted at rest with tenancy-managed keys, restorable within the
   tenancy, and adding NO new party beyond the cloud provider already
   trusted to host the instance itself. No third-party bucket, no operator
   key material, nothing the threat model has not already assumed.

## Decision

Backups consist of exactly two layers, both inside the Oracle tenancy:

1. **On-host**: a systemd timer runs `sqlite3 … ".backup"` daily into
   `/var/backups/skipti` (`0700 root`), retaining 14 daily snapshots.
2. **Off-host**: an Oracle Block Volume backup policy on the instance's
   volume — daily incremental, 7-day retention — so instance-level
   destruction is survivable.

Restore verification is a REQUIRED drill (quarterly, and after any schema
migration): restore the newest backup to a scratch path, run
`PRAGMA integrity_check`, compare per-table row counts against the live
vault, byte-compare one known ciphertext row between live and backup, then
boot a second relay instance against the restored file on loopback and fetch
`/` and `/version`. Every step needs no key material — everything sensitive
in the file is ciphertext, so byte-fidelity of the copy is the whole proof.

## Consequences and tripwires

* Backup availability is bounded by the tenancy: an attacker (or Oracle)
  compromising the tenancy compromises instance and backups together. This
  is accepted as IDENTICAL to the existing trust in the instance host, and
  is written down here so it is a known boundary rather than an accident.
* TRIPWIRE: if the vault ever stores anything in plaintext (a schema or
  record change), if backups ever leave the tenancy, or if any step needs
  operator-held key material, this ADR must be revisited BEFORE the change
  ships — each of those reopens §2.6 and ADR-0021's custody story.
* The drill lives as a numbered procedure in DEPLOY-ORACLE.md §10; the ADR
  records the policy, the runbook records the commands.
