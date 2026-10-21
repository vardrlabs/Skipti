# ADR-0026: The WebAuthn relying-party ID

Status: Superseded by ADR-0027 (2026-08-25) — after Play Console
registration placed the company's products under vardrlabs.com, the
RP ID became `skipti.vardrlabs.com`. The reasoning below stands as
written for the facts it knew; nothing was ever deployed under this
decision, so no migration is needed.

## Context

`server/src/lib.rs` reads `SKIPTI_RP_ID` (default `"localhost"` for
development) and binds the value into every passkey credential at
registration time. The RP ID is permanent per credential: change it later
and every existing passkey stops working, with no migration path. Nothing
in docs/ records what production should use.

The founder owns **icrk.dev**. ADR-0021 places the relay on Oracle Always
Free, which will be served from an icrk.dev subdomain once the deployment
story (TLS, DNS) lands. The question is which string becomes the RP ID,
because it cannot be changed without stranding every credential.

## The WebAuthn constraint

The RP ID must be equal to or a registrable suffix of the origin's
effective top-level domain + 1 (eTLD+1). So:

- Origin `skipti.icrk.dev` → RP ID may be `icrk.dev` or `skipti.icrk.dev`
- Origin `icrk.dev` → RP ID must be `icrk.dev`

A credential registered under RP ID `X` works on `X` and all subdomains
of `X`, but never on a sibling or a different domain. Widening is
impossible; narrowing silently excludes existing credentials.

## The options, argued both ways

### `icrk.dev` — the registrable domain

**For:** credentials work on every current and future subdomain of
icrk.dev, including `skipti.icrk.dev`, `api.icrk.dev`, and anything else.
If SKIPTI adds a second service on the same domain, passkeys carry over.

**Against:** the credential's scope includes the founder's entire domain.
A passkey registered for SKIPTI on `icrk.dev` is technically valid on any
other icrk.dev subdomain — the browser will offer it, and the server
must reject it (which SKIPTI's server does, because the user handle
won't match). But the *offer* is confusing: a user sees "icrk.dev
passkey" on a non-SKIPTI site. And if the founder sells or transfers
icrk.dev, the credentials go with it.

### `skipti.icrk.dev` — the product subdomain

**For:** properly scoped to SKIPTI. The browser shows "skipti.icrk.dev"
in the passkey prompt, which is the name the user recognises. If the
founder later uses icrk.dev for other services, SKIPTI credentials don't
interfere. If SKIPTI eventually moves to its own domain, the migration
path is the same as from `icrk.dev` — both are stranded equally.

**Against:** credentials registered under `skipti.icrk.dev` do not work
on `icrk.dev` itself. If the relay ever moves from `skipti.icrk.dev` to
`icrk.dev` (unlikely but possible), those credentials are stranded.

### A dedicated domain (`skipti.app`, `getskipti.com`, …)

**For:** clean separation from the founder's domain. If SKIPTI is ever
sold or transferred, the domain goes with it.

**Against:** the domain doesn't exist today. Registering one blocks
passkey testing on the domain, and the cost is real but small. The
stranding risk is identical: moving from `skipti.icrk.dev` to
`skipti.app` strands credentials the same way moving from `icrk.dev`
does.

## Decision

**`skipti.icrk.dev`**, Accepted.

The reasoning is decisive on three points:

1. **The stranding risk is identical across all icrk.dev options.**
   Moving from `icrk.dev` to a dedicated domain strands credentials;
   moving from `skipti.icrk.dev` does too. Choosing `icrk.dev` buys
   nothing except broader scope that SKIPTI doesn't need and shouldn't
   have (a SKIPTI passkey offered on a non-SKIPTI icrk.dev subdomain is
   a UX bug, not a feature).

2. **Proper scoping is the better default.** A credential scoped to
   `skipti.icrk.dev` belongs to SKIPTI, is labelled SKIPTI in the
   browser, and cannot leak into other services on the same domain.
   If the founder later adds `forum.icrk.dev`, a SKIPTI passkey won't
   be offered there.

3. **Waiting for a dedicated domain blocks all passkey work.** §4.2
   makes passkeys the primary sign-in path; blocking on a domain
   registration that may never happen is worse than the stranding risk.

If SKIPTI later moves to a dedicated domain, the migration is: register
new credentials under the new RP ID alongside the old ones (WebAuthn
supports multiple RP IDs during a transition window via separate
`PublicKeyCredentialCreationOptions`), then deprecate the old ones. This
is real work but not a data-loss event — the vault is unaffected; only
the auth credentials change.

## Operational consequence

The origin must be **HTTPS** in production. WebAuthn requires a secure
context; `http://skipti.icrk.dev` will not invoke the passkey API.
ADR-0021's Oracle deployment therefore needs **TLS termination before a
single real passkey is registered** — the §5.8-era gap "TLS termination"
is upgraded from "deployment story" to "blocking passkey adoption."

For development, `SKIPTI_RP_ID=localhost` remains the default (localhost
is a valid secure context). Production sets
`SKIPTI_RP_ID=skipti.icrk.dev` via the environment.

## Consequences

- The AASA file (apple-app-site-association) must be served from
  `https://skipti.icrk.dev/.well-known/apple-app-site-association`
  with the app's associated-domains entitlement
  (`webcredentials:skipti.icrk.dev`) before iOS passkey assertions
  work on device. This is a deployment prerequisite, recorded in
  BUILD-LOG §4.4.
- The server's `SKIPTI_RP_ID` environment variable must be set to
  `skipti.icrk.dev` in production. The development default
  (`localhost`) is unchanged.
- If a dedicated domain happens later, the migration is: dual-RP-ID
  registration window → deprecate old credentials. Real work, not data
  loss; recorded here so it is not a surprise.
