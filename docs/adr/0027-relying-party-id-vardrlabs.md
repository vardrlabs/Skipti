# ADR-0027: The WebAuthn relying-party ID is skipti.vardrlabs.com

Status: Accepted (supersedes ADR-0026)

## Context

ADR-0026 fixed the production RP ID as `skipti.icrk.dev` because the
founder owned icrk.dev and no company domain existed. Since then the
company identity materialised: Play Console registration is complete
under developer name **Vardr Labs**, organisation **Vardr Labs LLC**,
at the company developer address (withheld 2026-10-20 before the repo went public), and the company domain is
**vardrlabs.com**. The question ADR-0026 answered must be answered
again, before any passkey exists to strand.

Nothing is deployed. No user holds a credential, subject_id, escrow
blob or ledger row under either candidate domain — the supersession
costs nothing today, which is precisely why it is being made now.

## The decision's basis (the founder's reasoning)

icrk.dev is a playground for projects to mature. vardrlabs.com is
where company products live. But a **subdomain already provides that
isolation** — `skipti.vardrlabs.com` is as separate from anything else
on the company domain as a separate registrable domain would be, for
every purpose WebAuthn and browsers care about. Staging SKIPTI on a
different registrable domain therefore buys nothing while costing a
forced migration — of passkeys, subject_ids, escrow blobs and ledger
data — at exactly the moment real users exist.

## The rule that generalises

**Anything holding durable user credentials or data launches on its
final domain. Anything ephemeral can live on icrk.dev.**

- RUNA is ephemeral by design and correctly stays on icrk.dev.
- SKIPTI holds Recovery Kits and ledgers and does not: it launches on
  `skipti.vardrlabs.com`, its final domain, from day one.

A future product (Fenrir or otherwise) that accumulates durable state
follows the same rule onto its own vardrlabs.com subdomain.

## Credential scope stays per-product

The RP ID is the **full subdomain**, `skipti.vardrlabs.com` — never
the registrable domain `vardrlabs.com`. WebAuthn offers a credential
on its RP ID and that RP ID's subdomains, so an RP ID of
`vardrlabs.com` would let a SKIPTI passkey be offered on every product
subdomain of the company. Scoping each product to its own subdomain
keeps SKIPTI, RUNA and Fenrir from ever sharing credential scope.

## Decision

**`skipti.vardrlabs.com`**, Accepted, superseding ADR-0026.

Why the supersession rather than an edit: ADR-0026's reasoning was
correct given what it knew (no company domain existed; the options
were all icrk.dev-shaped). Its decision is voided by new facts, not
corrected in place. Nothing shipped under it, so no migration path is
needed — the migration ADR-0026 itself documented (dual-RP-ID
registration window) is simply never exercised.

## Consequences

- Production sets `SKIPTI_RP_ID=skipti.vardrlabs.com` via the
  environment. The development default (`localhost`) is unchanged;
  localhost remains a valid secure context.
- TLS termination remains BLOCKING for passkey adoption exactly as
  ADR-0026 recorded — WebAuthn requires a secure context, and the
  origin is now `https://skipti.vardrlabs.com`. The §4.3 deployment
  gap carries this forward unchanged in substance.
- The AASA file must be served from
  `https://skipti.vardrlabs.com/.well-known/apple-app-site-association`
  with the associated-domains entitlement
  (`webcredentials:skipti.vardrlabs.com`) before iOS passkey
  assertions work on device.
- The application bundle identifier aligns with the same naming
  reality: `com.vardrlabs.skipti` (D-58), with the org-wide scheme
  `com.vardrlabs.<product>` for RUNA and Fenrir.
- If SKIPTI ever outgrows vardrlabs.com, the migration is the dual-RP
  registration window ADR-0026 described — real work, not data loss —
  but it is not expected: the generalising rule above is what makes
  the final domain final.
