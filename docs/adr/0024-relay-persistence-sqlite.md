# ADR-0024: Relay persistence on SQLite via rusqlite, behind D-19's boundary

Status: Accepted

## Context

ADR-0021 made persistent server storage a hard precondition of any real user
connecting to the hosted instance: all relay state lives in six in-memory
mutexes, a restart destroys every account, session, passkey credential,
envelope and escrow blob — and a friend whose escrow blob dies loses their
Master Key recovery. D-19 named Postgres when "server" meant a hypothetical
production deployment; the deployment target is now concrete and much
smaller: one Oracle Always Free ARM instance at friends scale, operated by
one person who also maintains everything else.

**The case for Postgres**, argued seriously: it is a real concurrent-writer
engine with MVCC, so N axum workers never serialize on one write lock; it
has an operational ecosystem (pg_dump, PITR/WAL archiving, connection
poolers, managed offerings); scaling to a second instance or read replicas
is a configuration exercise rather than an architecture change; and D-19
named it, so choosing otherwise needs reasons, not vibes.

**The case for SQLite via rusqlite**, which wins here:

1. **Operational surface is the binding constraint.** ADR-0021's operator is
   one person who must patch, back up, monitor, and IP-log-scrub one box.
   SQLite adds zero daemons; Postgres adds a second service with its own
   CVEs, upgrades, and failure modes to a system whose threat model already
   promises minimal server responsibilities.
2. **Concurrency at this scale.** The relay's write pattern is tiny and
   bursty (friend-group envelope pushes, log batches). WAL mode gives
   concurrent readers with a single serialized writer — and the current
   design already serializes every mutation behind six mutexes, so SQLite
   does not introduce a bottleneck that does not exist.
3. **One dependency the project already carries and understands** (D-39:
   rusqlite/bundled-sqlcipher pattern, schema versioning, migration runner).
   No new dependency, no new licence surface.
4. **Backup story is a file.** `VACUUM INTO`/the online backup API plus a
   cron job is a complete backup story a single operator can actually run;
   escrow-blob custody (ADR-0021's sharpest edge) becomes "copy one file."

What changes if a second instance is ever needed: the D-19 boundary holds —
all persistence goes through one trait, so the swap is a new trait impl plus
a data migration; the honest cost is that SQLite's single-writer model means
that day also brings LiteFS-style replication or a move to Postgres *plus*
that migration. The trigger is explicit (see BUILD-LOG §17.6/§4.3): a second
instance or sustained write pressure beyond one ARM box reopens this ADR.

## Decision

Relay state persists to **SQLite (WAL mode) through rusqlite**, behind a
`RelayPersistence` trait so the backend stays swappable (D-19's boundary,
now load-bearing). Schema versioned from row one (`PRAGMA user_version`),
same discipline as skrin (D-39).

Persisted per field, precisely:

| State | Persisted | Why |
|---|---|---|
| Log blobs (records) | **Yes**, row-per-push | The ledger itself |
| Group envelopes | **Yes**, full-set replace per group push | Wrapped keys; losing them locks every member out |
| Escrow blobs | **Yes**, upsert per subject | Losing one loses a Master Key recovery path (ADR-0021) |
| Session/token families incl. live access grants | **Yes**, snapshot per mutation | Restart must not log anyone out or orphan refresh tokens |
| Passkey credentials | **Yes**, JSON per identifier (webauthn-rs serialises `Passkey` for exactly this) | Credentials ARE the account |
| OTP codes/hash state | **No** | 120 s TTL, single-use; losing them on restart costs one re-request, not data loss |
| WebAuthn challenge state (registration/authentication) | **No** | Same shape: short-lived, single-use challenge state; the client retries |

The exclusions are deliberate and stated so nobody "completes" them later by
accident: ephemeral anti-replay material must not outlive a process.

Durability note, kept honest: mutations apply to memory first and persist
immediately after; a persistence error fails the request (5xx) rather than
pretending success, but the in-memory change is not rolled back — so the
residual window is "state changed, response failed," not silent loss on the
success path. WAL + `synchronous=FULL`.

## Consequences

- Restart survival becomes a tested property: close and reopen the vault,
  assert logs/envelopes/escrow/sessions/passkeys return and dead families
  stay dead (not trusted to types).
- The GATE 3 canary extends to the new backing: raw vault bytes contain no
  plaintext financial content.
- One operator, one file to back up; no second daemon in the patch cadence.
- Postgres remains the named escalation if SKIPTI ever runs a second
  instance or outgrows one writer; reopening this ADR then also means a
  data migration, which is why the trait boundary is enforced from day one.
