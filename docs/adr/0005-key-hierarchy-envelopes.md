# ADR-0005: Key hierarchy, HKDF domain separation, group envelope wrapping

Status: Accepted

## Context

Personal-only E2EE is easy; shared-ledger E2EE is where products give up and
quietly decrypt server-side. A malicious server can also substitute its own
key in any unauthenticated key agreement, making most "E2EE" theatre against
exactly the adversary it exists to stop.

## Decision

Primitives are fixed: Argon2id (m=64 MiB, t=3, p=1), HKDF-SHA-256,
AES-256-GCM (12-byte counter nonce, 16-byte tag), Ed25519 device identity,
X25519 group agreement, SQLCipher local store.

```
Master Key (MK), 32B, never leaves device
├── Personal DEK        HKDF info="skipti/v1/personal"
├── Group capability    GroupKey_G random 32B per group,
│                       wrapped once per member device via
│                       X25519 → HKDF(info="skipti/v1/groupwrap" ‖ G ‖ epoch)
│                       → AES-GCM envelope
└── Device auth         HKDF info="skipti/v1/auth"
```

All info strings distinct: personal, groupwrap, auth, escrow, search, backup,
fingerprint.

Peer keys are verified out of band with a 6-word safety phrase from a fixed
2048-word list, shown on the group screen; unverified members render UNVERIFIED,
not hidden. Key changes hold new envelopes until re-acknowledged.

AAD binds every header field (version, recordType, groupId/personal, epoch,
authorDeviceId, nonce) so records cannot be replayed across groups or after
mutation.

## Consequences

- The server sees opaque envelopes; it cannot read or substitute keys without
  detection via safety-phrase mismatch.
- Epoch ships in every record header from v1 although rotation lands in v1.1:
  adding the field pre-launch is a line of code; post-launch it is a migration.
